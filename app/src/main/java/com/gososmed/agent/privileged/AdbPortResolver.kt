package com.gososmed.agent.privileged

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Penemu port nirkabel ADB (`_adb-tls-connect._tcp`) dinamis untuk Android 11+.
 *
 * Mengatasi rotasi port acak oleh daemon `adbd` saat Wi-Fi reconnect, roaming,
 * atau deep sleep di HyperOS / MIUI dan FuntouchOS tanpa memerlukan root.
 */
object AdbPortResolver {

    private const val TAG = "GoAgentPortResolver"
    const val MDNS_IP = "224.0.0.251"
    const val MDNS_PORT = 5353
    const val SERVICE_NAME = "_adb-tls-connect._tcp.local"
    const val NSD_SERVICE_TYPE = "_adb-tls-connect._tcp"

    /**
     * Memeriksa apakah socket TCP pada host dan port target sedang membuka port (LISTEN).
     * Sangat cepat pada loopback 127.0.0.1 (sub-1ms).
     */
    fun isSocketAlive(host: String = "127.0.0.1", port: Int, timeoutMs: Int = 300): Boolean {
        if (port !in 1024..65535) return false
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Membangun payload paket DNS PTR Query untuk mDNS.
     * Murni manipulasi byte, aman diuji di unit test JVM.
     */
    fun buildMdnsQuery(service: String = SERVICE_NAME): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeShort(0x0000) // Transaction ID
        dos.writeShort(0x0000) // Flags: Standard query
        dos.writeShort(0x0001) // Questions: 1
        dos.writeShort(0x0000) // Answer RRs
        dos.writeShort(0x0000) // Authority RRs
        dos.writeShort(0x0000) // Additional RRs

        // QNAME parts
        for (part in service.split(".")) {
            if (part.isNotEmpty()) {
                dos.writeByte(part.length)
                dos.write(part.toByteArray(Charsets.US_ASCII))
            }
        }
        dos.writeByte(0x00)    // Terminating zero
        dos.writeShort(0x000C) // Type: PTR (12)
        dos.writeShort(0x0001) // Class: IN (1)
        return out.toByteArray()
    }

    /**
     * Mengekstrak nomor port dari paket jawaban mDNS yang memuat record SRV (Type 0x0021).
     * Murni byte parsing, aman diuji di unit test JVM.
     */
    fun extractPortFromMdnsPayload(data: ByteArray, length: Int): Int? {
        val bound = length.coerceAtMost(data.size)
        // Cari pola SRV Record: Type 0x0021
        for (i in 0 until bound - 15) {
            if (data[i] == 0x00.toByte() && data[i + 1] == 0x21.toByte()) {
                // Class byte 1 biasanya 0x01 (IN) atau 0x80 (cache-flush), byte 2 adalah 0x01
                val classLow = data[i + 3]
                if (classLow == 0x01.toByte()) {
                    val dataLen = ((data[i + 8].toInt() and 0xFF) shl 8) or (data[i + 9].toInt() and 0xFF)
                    if (dataLen >= 6 && (i + 10 + dataLen) <= bound) {
                        // Port ada di offset +4 di dalam SRV RDATA (setelah Priority: 2B, Weight: 2B)
                        val port = ((data[i + 14].toInt() and 0xFF) shl 8) or (data[i + 15].toInt() and 0xFF)
                        if (port in 1024..65535) {
                            return port
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Menemukan port ADB aktif melalui query multicast UDP mDNS langsung.
     * Mengembalikan null jika timeout atau tidak ada respon.
     */
    fun discoverViaMdnsUdp(context: Context?, timeoutMs: Long = 1200L): Int? {
        var multicastLock: WifiManager.MulticastLock? = null
        var socket: DatagramSocket? = null
        try {
            if (context != null) {
                try {
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    multicastLock = wm?.createMulticastLock("gososmed-adb-port")?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "MulticastLock tidak dapat diperoleh: ${t.message}")
                }
            }

            socket = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = timeoutMs.toInt()
                bind(InetSocketAddress(0))
            }

            val query = buildMdnsQuery(SERVICE_NAME)
            val group = InetAddress.getByName(MDNS_IP)
            val outPacket = DatagramPacket(query, query.size, group, MDNS_PORT)
            socket.send(outPacket)

            val buffer = ByteArray(4096)
            val inPacket = DatagramPacket(buffer, buffer.size)
            val deadline = System.currentTimeMillis() + timeoutMs

            while (System.currentTimeMillis() < deadline) {
                try {
                    socket.receive(inPacket)
                    val port = extractPortFromMdnsPayload(inPacket.data, inPacket.length)
                    if (port != null && port in 1024..65535) {
                        if (isSocketAlive("127.0.0.1", port, 250)) {
                            Log.i(TAG, "Ditemukan port ADB via UDP mDNS: $port")
                            return port
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    break
                }
            }
            return null
        } catch (t: Throwable) {
            Log.w(TAG, "discoverViaMdnsUdp gagal: ${t.message}")
            return null
        } finally {
            try { socket?.close() } catch (_: Throwable) {}
            try {
                if (multicastLock?.isHeld == true) multicastLock.release()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Jalur penemuan cadangan menggunakan NsdManager bawaan sistem Android.
     */
    fun discoverViaNsd(context: Context?, timeoutMs: Long = 1500L): Int? {
        val ctx = context?.applicationContext ?: return null
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val resolvedPort = AtomicInteger(-1)
        val latch = CountDownLatch(1)

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                latch.countDown()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (resolvedPort.get() > 0) return
                try {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val p = resolved.port
                            if (p in 1024..65535 && isSocketAlive("127.0.0.1", p, 250)) {
                                resolvedPort.set(p)
                                latch.countDown()
                            }
                        }
                    })
                } catch (_: Throwable) {
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }

        try {
            nsd.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "discoverViaNsd gagal: ${t.message}")
        } finally {
            try { nsd.stopServiceDiscovery(discoveryListener) } catch (_: Throwable) {}
        }

        val port = resolvedPort.get()
        return if (port > 0) port else null
    }

    /**
     * Resolusi komprehensif port wireless ADB aktif pada loopback 127.0.0.1.
     * Menggabungkan UDP mDNS cepat, NsdManager, dan verifikasi socket liveness.
     */
    fun discoverActiveAdbPort(context: Context?, timeoutMs: Long = 2000L): Int? {
        // 1. Coba UDP mDNS (biasanya selesai dalam 50-200ms)
        val udpPort = discoverViaMdnsUdp(context, timeoutMs.coerceAtMost(1200L))
        if (udpPort != null) return udpPort

        // 2. Fallback ke NsdManager
        val nsdPort = discoverViaNsd(context, timeoutMs.coerceAtMost(1500L))
        if (nsdPort != null) return nsdPort

        return null
    }

    /**
     * Versi coroutine non-blocking untuk dipanggil dari coroutine scope.
     */
    suspend fun discoverActiveAdbPortSuspend(context: Context?, timeoutMs: Long = 2000L): Int? =
        withContext(Dispatchers.IO) {
            discoverActiveAdbPort(context, timeoutMs)
        }
}
