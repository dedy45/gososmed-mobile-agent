package com.gososmed.agent.privileged

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.Collections

/**
 * Penemu port `_adb-tls-pairing._tcp` yang dibuat untuk kondisi OEM nyata.
 *
 * `libadb-android.AdbMdns` bekerja di banyak perangkat, tetapi memiliki dua
 * titik buta yang persis cocok dengan laporan lapangan "port tidak terdeteksi":
 *
 *  1. ia TIDAK memegang `WifiManager.MulticastLock`. Pada sebagian Xiaomi/Oppo/
 *     Vivo, kernel membuang paket multicast sampai aplikasi memegang lock ini;
 *  2. callback kegagalan NSD-nya diam (`onStartDiscoveryFailed` dan
 *     `onResolveFailed` kosong), sehingga aplikasi tidak bisa membedakan
 *     "belum ada dialog pairing" dari "discovery memang ditolak sistem".
 *
 * Kelas ini memakai NsdManager langsung, memegang MulticastLock, menulis setiap
 * kegagalan ke log, membatasi satu resolve aktif (menghindari FAILURE_MAX_LIMIT),
 * dan mencoba ulang dengan backoff selama sesi pairing masih hidup.
 */
class AdbPairingPortDiscovery(
    context: Context,
    private val onPort: (port: Int) -> Unit,
    private val onDiagnostic: (message: String) -> Unit = {},
) {
    companion object {
        private const val TAG = "GoAgentPortNsd"
        private const val SERVICE_TYPE = "_adb-tls-pairing._tcp"
        private const val RETRY_BASE_MS = 1_000L
        private const val RETRY_MAX_MS = 5_000L
    }

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val main = Handler(Looper.getMainLooper())

    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile private var discoveryStarted = false
    @Volatile private var resolveBusy = false
    @Volatile private var pendingResolve: NsdServiceInfo? = null
    @Volatile private var retryAttempt = 0

    private val resolveRetried = Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    private var running = false

    private val retryRunnable = Runnable {
        if (running) startDiscoveryInternal()
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        retryAttempt = 0
        acquireMulticastLock()
        startDiscoveryInternal()
    }

    @Synchronized
    fun stop() {
        running = false
        main.removeCallbacks(retryRunnable)
        pendingResolve = null
        resolveBusy = false
        resolveRetried.clear()
        val listener = discoveryListener
        discoveryListener = null
        if (listener != null) {
            try {
                // Panggil walau callback onDiscoveryStarted belum tiba; bila
                // listener belum terdaftar, exception ditangkap dan diabaikan.
                nsd?.stopServiceDiscovery(listener)
            } catch (t: Throwable) {
                Log.w(TAG, "stopServiceDiscovery gagal", t)
            }
        }
        discoveryStarted = false
        releaseMulticastLock()
    }

    @Synchronized
    private fun startDiscoveryInternal() {
        val manager = nsd
        if (manager == null) {
            diagnostic("NsdManager tidak tersedia di perangkat ini")
            return
        }
        if (discoveryListener != null) return

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                discoveryStarted = true
                retryAttempt = 0
                diagnostic("mDNS pairing aktif ($serviceType)")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryStarted = false
                // Listener yang gagal tidak boleh dipakai ulang; tanpa baris ini
                // retry akan berhenti di guard `discoveryListener != null`.
                discoveryListener = null
                diagnostic("mDNS pairing gagal mulai (error=$errorCode) — mencoba ulang")
                scheduleRetry()
            }

            override fun onDiscoveryStopped(serviceType: String) {
                discoveryStarted = false
                discoveryListener = null
                if (running) {
                    diagnostic("mDNS pairing berhenti tak terduga — memulai ulang")
                    scheduleRetry()
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "onStopDiscoveryFailed error=$errorCode")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val type = serviceInfo.serviceType.orEmpty()
                if (!type.contains("adb-tls-pairing")) return
                queueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // Dialog pairing ditutup. Sesi kita tetap hidup sampai timeout,
                // sehingga pengguna bisa membuka dialog baru tanpa menekan ulang.
                diagnostic("layanan mDNS pairing hilang (${serviceInfo.serviceName ?: "tanpa nama"})")
            }
        }
        discoveryListener = listener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            discoveryListener = null
            diagnostic("discoverServices melempar ${t.javaClass.simpleName} — mencoba ulang")
            Log.w(TAG, "discoverServices gagal", t)
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (!running) return
        retryAttempt += 1
        val delay = (RETRY_BASE_MS * retryAttempt).coerceAtMost(RETRY_MAX_MS)
        main.removeCallbacks(retryRunnable)
        main.postDelayed(retryRunnable, delay)
    }

    @Synchronized
    private fun queueResolve(service: NsdServiceInfo) {
        if (resolveBusy) {
            // Hanya service terbaru yang penting; dialog pairing lama kedaluwarsa.
            pendingResolve = service
            return
        }
        resolveNow(service)
    }

    @Synchronized
    private fun resolveNow(service: NsdServiceInfo) {
        val manager = nsd ?: return
        resolveBusy = true
        try {
            manager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(failedService: NsdServiceInfo, errorCode: Int) {
                    resolveBusy = false
                    val key = serviceKey(failedService)
                    // Android 14 sering gagal sekali dengan FAILURE_INTERNAL_ERROR.
                    // Coba service yang sama SATU kali sebelum menyerah.
                    if (resolveRetried.add(key)) {
                        main.postDelayed({
                            if (running) resolveNow(failedService)
                        }, 300L)
                    } else {
                        diagnostic("resolve mDNS pairing gagal (error=$errorCode)")
                        drainPendingResolve()
                    }
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    resolveBusy = false
                    resolveRetried.remove(serviceKey(resolved))
                    handleResolved(resolved)
                    drainPendingResolve()
                }
            })
        } catch (t: Throwable) {
            resolveBusy = false
            diagnostic("resolveService melempar ${t.javaClass.simpleName}")
            Log.w(TAG, "resolveService gagal", t)
        }
    }

    @Synchronized
    private fun drainPendingResolve() {
        val next = pendingResolve
        pendingResolve = null
        if (next != null && running) resolveNow(next)
    }

    private fun serviceKey(service: NsdServiceInfo): String {
        return "${service.serviceName}|${service.serviceType}|${service.port}"
    }

    private fun handleResolved(service: NsdServiceInfo) {
        val port = service.port
        if (port !in 1..65535) return

        val hosts = serviceHosts(service)
        val looksLocal = hosts.isEmpty() || hosts.any { it.isLoopbackAddress || isLocalAddress(it) }
        if (!looksLocal) {
            diagnostic("mDNS pairing dari perangkat lain diabaikan (${hosts.firstOrNull()})")
            return
        }

        // Atribusi tambahan: port pairing milik HP ini harus sudah TERISI di
        // loopback. Jika port masih bisa kita bind, itu hampir pasti milik
        // perangkat lain di LAN, bukan pairing dialog lokal.
        if (!portLooksOccupiedOnLoopback(port)) {
            diagnostic("port $port dari mDNS tidak terbukti milik HP ini — diabaikan")
            return
        }

        retryAttempt = 0
        onPort(port)
    }

    private fun serviceHosts(service: NsdServiceInfo): List<InetAddress> {
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                service.hostAddresses ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                listOfNotNull(service.host)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun isLocalAddress(address: InetAddress): Boolean {
        return try {
            NetworkInterface.getNetworkInterfaces().toList().any { iface ->
                iface.inetAddresses.toList().any { local ->
                    local.address.contentEquals(address.address)
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun portLooksOccupiedOnLoopback(port: Int): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1)
            }
            false
        } catch (_: IOException) {
            true
        } catch (_: SecurityException) {
            // Bila sistem melarang probe bind, jangan membuang kandidat yang
            // sudah lolos filter alamat lokal.
            true
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wifi.createMulticastLock("gososmed-adb-pairing").apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MulticastLock tidak bisa diambil", t)
            diagnostic("MulticastLock tidak tersedia — mDNS mungkin dibatasi OEM")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        multicastLock = null
    }

    private fun diagnostic(message: String) {
        Log.i(TAG, message)
        try {
            onDiagnostic(message)
        } catch (_: Throwable) {
        }
    }
}
