package com.gososmed.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import com.gososmed.agent.privileged.PairingDialogParser
import com.gososmed.agent.privileged.PrivilegedShellHolder
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AccessibilityService = the agent's "source of truth" for the device UI.
 *
 * It (a) reads the active window's hierarchy (no root, no adb), and
 * (b) executes actions via the Accessibility API (gestures, ACTION_SET_TEXT,
 * global actions) — mirroring what the GoSosmed `internal/mobile/Device`
 * driver does over adb. Because the service runs on the phone, "the real
 * platform load stays on the user's phone" (BYOD model).
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GoAgent"

        /** Batas tunggu bukti foreground setelah launch (v0.7.1).
         *
         *  v0.9.10 (audit ANR 2026-09-22): DIKURANGI dari 10s ke 4s.
         *  Android menampilkan dialog ANR bila main thread diblokir >5 detik.
         *  startAppVerified() fallback accessibility berjalan di main thread
         *  dan memanggil awaitForeground() dengan Thread.sleep — dengan 10s
         *  PASTI memicu ANR. 4s memberi margin 1s di bawah ambang ANR.
         *
         *  Jalur shell (startAppShell) menggunakan SHELL_LAUNCH_VERIFY_TIMEOUT_MS
         *  yang lebih panjang karena berjalan di thread IO (aman dari ANR). */
        private const val LAUNCH_VERIFY_TIMEOUT_MS = 4_000L

        /** Batas tunggu bukti foreground untuk jalur shell (v0.9.10).
         *  Jalur shell dijalankan di thread IO oleh AgentWsClient, sehingga
         *  aman menggunakan timeout lebih panjang tanpa risiko ANR. Cold start
         *  app berat (Facebook/TikTok) di HP kelas menengah bisa 5-7 detik. */
        private const val SHELL_LAUNCH_VERIFY_TIMEOUT_MS = 15_000L

        /** Jarak antar pemeriksaan foreground; murah karena lokal di HP. */
        private const val FOREGROUND_POLL_MS = 250L

        /** Nama transport yang dilaporkan ke server (v0.9.0). Backend memakai
         *  ini untuk tahu tingkat keandalan perintah: shell = deterministik
         *  (uid 2000), accessibility = best-effort dan tunduk pada BAL/OEM. */
        const val TRANSPORT_SHELL = "shell_adb"
        const val TRANSPORT_A11Y = "accessibility"

        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        /**
         * v0.9.6 — PEMISAHAN TEGAS antara "service TER-BIND" dan "window SEDANG
         * bisa dibaca".
         *
         * CACAT v0.9.5 (dan versi sebelumnya) — dinyatakan oleh laporan lapangan
         * "Langkah 1 menjadi SANGAT tidak stabil, padahal sudah diaktifkan dan
         * sukses; ketika berpindah tab / menutup aplikasi, Langkah 1 disuruh
         * mengaktifkan lagi":
         *
         *  - `MainActivity.refreshPerms()` memakai `instance?.isServiceReady()`,
         *    sedangkan `isServiceReady()` = `rootInActiveWindow != null`.
         *  - `rootInActiveWindow` dapat bernilai null SEMENTARA pada banyak
         *    kejadian normal: transisi antar-activity, layar terkunci, app
         *    target memasang FLAG_SECURE, atau sistem menjeda layanan saat
         *    berpindah tab. Itu BUKAN tanda "service mati".
         *  - Selain itu `onDestroy()` meng-null-kan `instance`. OEM agresif
         *    (MIUI/HyperOS) rutin me-restart layanan accessibility, sehingga
         *    ada jendela waktu `instance == null` sesaat — lagi-lagi terbaca
         *    sebagai "BELUM AKTIF" oleh UI.
         *
         * Akibatnya UI memantulkan status yang salah: pengguna yang sudah
         * mengaktifkan dengan benar tetap dipaksa "Aktifkan" lagi setiap kali
         * berpindah tab. Itu regresi UX yang fatal.
         *
         * PERBAIKAN: `bound` adalah sumber kebenaran untuk "layanan aktif".
         * Ia ditandai true di `onServiceConnected()` dan tetap true walau
         * `rootInActiveWindow` sedang null. `onDestroy()` mengubahnya ke false
         * (layanan benar-benar putus).
         */
        @Volatile
        var bound: Boolean = false
            private set

        /**
         * True bila pengguna sendiri mematikan layanan dari Setelan.
         *
         * Dipakai `onUnbind()` untuk menahan auto-revive: tanpa flag ini,
         * setiap kali pengguna mematikan aksesibilitas, watchdog langsung
         * menyalakannya kembali lewat `settings put secure` — pengguna melihat
         * sakelar yang tidak bisa dimatikan.
         */
        @Volatile
        var userDisabled: Boolean = false

        /**
         * Callback saat status koneksi layanan berubah (connected/unbound/destroyed).
         * Digunakan oleh MainActivity agar UI Langkah 1 langsung terbarui seketika
         * tanpa menunggu pengguna berpindah layar.
         */
        @Volatile
        var onStateChanged: (() -> Unit)? = null

        private val stateListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

        fun addStateListener(listener: () -> Unit) {
            if (!stateListeners.contains(listener)) {
                stateListeners.add(listener)
            }
        }

        fun removeStateListener(listener: () -> Unit) {
            stateListeners.remove(listener)
        }

        fun notifyStateChanged() {
            try {
                onStateChanged?.invoke()
            } catch (t: Throwable) {
                Log.w(TAG, "onStateChanged error: ${t.message}")
            }
            for (l in stateListeners) {
                try {
                    l.invoke()
                } catch (t: Throwable) {
                    Log.w(TAG, "stateListener error: ${t.message}")
                }
            }
        }

        /**
         * v0.9.7 — applicationContext. Diperlukan supaya `isEnabled()` bisa
         * bertanya LANGSUNG ke sistem operasi (lihat [osEnabled]) tanpa harus
         * menunggu sebuah Activity hidup. Sebelum ini, `isEnabled()` hanya
         * mengandalkan flag dalam memori — dan flag itu hilang begitu proses
         * agent dimatikan OEM, sehingga UI melaporkan "BELUM AKTIF" untuk
         * layanan yang sesungguhnya masih aktif di Setelan.
         */
        @Volatile
        private var appContext: Context? = null

        /** v0.9.7 — daftarkan applicationContext (dipanggil dari AgentApp). */
        fun attach(ctx: Context) {
            appContext = ctx.applicationContext
        }

        /**
         * v0.9.10 — akses applicationContext untuk command SERVICE_FREE
         * (hasPackage, listPackages) yang butuh PackageManager tapi tidak
         * butuh AccessibilityService. Mengembalikan null hanya jika
         * AgentApp.onCreate() belum pernah dijalankan.
         */
        fun getAppContext(): Context? = appContext

        /**
         * v0.9.11 — STATUS LIVE sejati: apakah service BENAR-BENAR HIDUP
         * di proses ini dan bisa menerima command.
         *
         * BERBEDA dari [isEnabled] yang hanya memeriksa apakah OS mendata
         * layanan ini sebagai "diaktifkan" (Settings/AccessibilityManager).
         * OS bisa mendata layanan sebagai aktif PADAHAL prosesnya sudah mati
         * (killed oleh OEM battery saver / ANR).
         *
         * isServiceAlive() = true HANYA jika:
         *   - instance != null (onServiceConnected telah dipanggil di proses ini)
         *   - bound == true (onDestroy/onUnbind belum dipanggil)
         *
         * Inilah yang HARUS diperiksa sebelum mengeksekusi command yang butuh
         * Accessibility tree (dump, tap, startApp, dll.) — bukan isEnabled().
         */
        fun isServiceAlive(): Boolean = instance != null && bound

        /**
         * v0.9.11 — STATUS READY: apakah service hidup DAN layar bisa dibaca.
         *
         * Ini level kesiapan tertinggi: selain service ter-bind, root window
         * accessibility juga harus tersedia (layar nyala, tidak terkunci oleh
         * FLAG_SECURE, dan OS tidak sedang menjeda layanan).
         *
         * Dipakai oleh readiness gate di heartbeat untuk melaporkan status
         * akurat ke server. TIDAK dipakai untuk menentukan tampilan UI izin
         * (itu tetap pakai isEnabled).
         */
        fun isReady(): Boolean {
            val svc = instance ?: return false
            return bound && svc.rootInActiveWindow != null
        }

        /**
         * v0.9.11 — Snapshot kesehatan semua subsistem, dikirim ke server di
         * setiap heartbeat agar dasbor menampilkan status real-time.
         *
         * Tiga subsistem diperiksa:
         *  1. Accessibility Service: alive (instance+bound) dan ready (root window)
         *  2. ADB Shell Transport: available (koneksi ke adbd hidup)
         *  3. WebSocket: connected (diasumsikan true karena heartbeat dikirim lewat WS)
         *
         * Server memakai informasi ini untuk:
         *  - Menampilkan badge kesehatan per-subsistem di kartu device
         *  - Memutuskan apakah job harvest/verify bisa dijalankan
         *  - Menyarankan tindakan spesifik (bukan generik "coba lagi")
         */
        fun healthSnapshot(): JSONObject {
            val shell = PrivilegedShellHolder.get()
            val shellStatus = shell.status()
            return JSONObject().apply {
                put("a11y_alive", isServiceAlive())
                put("a11y_ready", isReady())
                put("a11y_os_enabled", appContext?.let { osEnabled(it) } ?: false)
                put("a11y_blocked", appContext?.let { isRestrictedBlocked(it) } ?: false)
                put("adb_available", shellStatus.available)
                put("adb_connected", shellStatus.connected)
                put("adb_paired", shellStatus.paired)
                put("adb_error", shellStatus.error)
            }
        }
        /**
         * Snapshot kapabilitas perangkat yang aman dipanggil kapan pun,
         * bahkan saat `instance` service sedang null/re-binding.
         */
        fun capabilitiesSnapshot(ctx: Context?): JSONObject {
            val svc = instance
            if (svc != null) return svc.capabilitiesJson()
            val effectiveCtx = ctx ?: appContext
            val pm = effectiveCtx?.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val shellStatus = PrivilegedShellHolder.get().status()
            val overlayOk = effectiveCtx?.let { AgentOverlay.canDraw(it) } ?: false
            return JSONObject().apply {
                put("protocol_versions", org.json.JSONArray(listOf(1, 2)))
                put("agent_version", BuildConfig.VERSION_NAME)
                put("api_level", Build.VERSION.SDK_INT)
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("a11y_enabled", isEnabled())
                put("a11y_ready", false)
                put("a11y_blocked", effectiveCtx?.let { isRestrictedBlocked(it) } ?: false)
                put("can_draw_overlay", overlayOk)
                put("overlay_attached", AgentOverlay.isAttached())
                put("transport_tier", if (shellStatus.available) TRANSPORT_SHELL else TRANSPORT_A11Y)
                put("last_launch_transport", TRANSPORT_A11Y)
                put("adb_paired", shellStatus.paired)
                put("adb_connected", shellStatus.connected)
                put("adb_uid", shellStatus.uid)
                put("adb_error", shellStatus.error)
                put("can_shell", shellStatus.available)
                put("can_launch_app", shellStatus.available || overlayOk)
                put("can_force_stop", shellStatus.available)
                put("can_inject_input", shellStatus.available)
                put("can_screenshot", Build.VERSION.SDK_INT >= 30)
                put("battery_unrestricted", effectiveCtx?.let { pm?.isIgnoringBatteryOptimizations(it.packageName) } == true)
                put("screen_interactive", pm?.isInteractive == true)
            }
        }


        // ---- Pemindai dialog pairing Debug nirkabel (aktif hanya selama sesi pairing) ----

        private val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.google.android.settings",
            "com.samsung.android.settings",
            "com.miui.settings",
            "com.coloros.settings",
            "com.oplus.settings",
            "com.vivo.settings",
            "com.hihonor.settings",
        )

        @Volatile
        private var pairingDialogListener: ((PairingDialogParser.Snapshot) -> Unit)? = null

        @Volatile
        private var lastPairingDialogScanAt = 0L

        /**
         * Daftarkan pendengar dialog pairing. Listener wajib dibersihkan saat
         * sesi selesai supaya layanan tidak membaca layar di luar kebutuhan.
         */
        fun setPairingDialogListener(listener: ((PairingDialogParser.Snapshot) -> Unit)?) {
            pairingDialogListener = listener
            lastPairingDialogScanAt = 0L
        }

        /** Scan manual sekali (dipakai tepat setelah sesi pairing dimulai). */
        fun scanPairingDialogNow(): PairingDialogParser.Snapshot? {
            return instance?.capturePairingDialogSnapshot()
        }

        /** Paket layar event ini memang milik Setelan yang berpeluang memuat dialog pairing. */
        private fun isSettingsPackage(pkg: String): Boolean = pkg in SETTINGS_PACKAGES

        /**
         * Status kesiapan untuk UI/KONTROL IZIN — memisahkan dua makna:
         *
         *  - "layanan sudah ter-bind"(bound) → dipakai untuk menentukan apakah
         *    tombol "Aktifkan" di Setup perlu ditampilkan. Ini yang benar untuk
         *    UX izin: layanan yang ter-bind berarti pengguna sudah selesai.
         *  - "window aktif bisa dibaca sekarang"(live) → hanya dibutuhkan saat
         *    benar-benar menjalankan perintah dump/tap, BUKAN untuk status izin.
         *
         * `instance != null` diperiksa sebagai pinggir-aman proses (kalau proses
         * agent mati total, `bound` juga ikut hilang).
         */
        fun isEnabled(): Boolean {
            // Bukti terkuat lebih dulu: layanan benar-benar ter-bind di proses
            // ini. Tidak ada pembacaan sistem yang bisa menyangkalnya.
            if (bound || instance != null) return true
            // v0.9.7 — JARING PENGAMAN. Bila flag memori hilang (proses agent
            // di-restart OEM, atau layanan di-bind di proses lain), tanyakan
            // langsung ke sistem. Sebelum ini fungsi ini mengembalikan `false`
            // pada keadaan tersebut — itulah yang membuat pengguna yang SUDAH
            // mengaktifkan dipaksa mengaktifkan ulang, dan yang membuat Langkah 1
            // tampak "mati" tepat setelah menekan Hubungkan di Langkah 3.
            val ctx = appContext ?: return false
            return osEnabled(ctx)
        }

        /**
         * v0.9.7 — PEMBACAAN STATUS LANGSUNG DARI SISTEM OPERASI.
         *
         * Dua sumber, keduanya harus sepakat sebelum kita menyatakan "mati":
         *
         *  1. `AccessibilityManager.getEnabledAccessibilityServiceList()` —
         *     API RESMI. Ia mengembalikan objek `AccessibilityServiceInfo`
         *     beserta `ComponentName` yang sudah terurai, jadi TIDAK ada
         *     penguraian string dan tidak ada ketergantungan pada format yang
         *     dipakai OEM (bentuk panjang `pkg/pkg.Service` vs bentuk pendek
         *     `pkg/.Service`).
         *
         *  2. `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` — cadangan.
         *     Dipakai HANYA untuk menyelamatkan kasus API mengembalikan daftar
         *     tanpa menyebut kita (beberapa OEM menyaring daftar itu). Tanpa
         *     cadangan ini, kesalahan baca API justru menghidupkan kembali bug
         *     "disuruh aktifkan ulang".
         *
         * Urutannya sengaja "salah satu menyebut aktif = AKTIF": menampilkan
         * tombol "Aktifkan" untuk layanan yang sudah aktif adalah regresi UX
         * yang jauh lebih merugikan daripada kebalikannya.
         */
        fun osEnabled(ctx: Context): Boolean {
            if (apiListed(ctx)) return true
            if (secureListed(ctx)) return true
            return false
        }

        /**
         * v1.2.2 — Deteksi Restricted Settings (Setelan Dibatasi) Android 13+ (API 33+).
         *
         * Saat aplikasi dipasang melalui sideload (di luar Play Store / tanpa installer terpercaya),
         * Android 13+ otomatis menetapkan app-op "android:access_restricted_settings" ke status
         * MODE_ERRORED (2). Akibatnya, toggle Aksesibilitas tidak bisa diaktifkan dan sistem
         * memunculkan dialog "Restricted setting / Setelan dibatasi".
         *
         * Nilai false dikembalikan jika:
         * - Android versi di bawah 13 (API < 33, di mana fitur ini belum ada)
         * - Aksesibilitas sudah aktif (berarti izin sudah tembus / aktif)
         * - AppOps tidak mengembalikan MODE_ERRORED (misal sudah diizinkan lewat menu ⋮)
         */
        fun isRestrictedBlocked(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < 33) return false
            if (isEnabled()) return false
            return try {
                val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                    ?: return false
                try {
                    val m = AppOpsManager::class.java.getMethod(
                        "checkOpNoThrow",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        String::class.java
                    )
                    val res = m.invoke(appOps, 119, Process.myUid(), ctx.packageName) as? Int
                    if (res == AppOpsManager.MODE_ERRORED) return true
                } catch (_: Throwable) {}

                val mode = appOps.unsafeCheckOpNoThrow(
                    "android:access_restricted_settings",
                    Process.myUid(),
                    ctx.packageName
                )
                mode == AppOpsManager.MODE_ERRORED
            } catch (_: Throwable) {
                false
            }
        }

        /** Sumber 1: API resmi AccessibilityManager. */
        private fun apiListed(ctx: Context): Boolean {
            return try {
                val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                    ?: return false
                val myPkg = ctx.packageName
                val myClass = AgentAccessibilityService::class.java.name
                val mySimple = AgentAccessibilityService::class.java.simpleName

                // Cek feedback mask umum, lalu fallback ke FEEDBACK_GENERIC (sesuai config XML)
                val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    ?: am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
                    ?: return false

                list.any { info ->
                    // 1. Cek info.id (format: "pkg/className" atau "pkg/.simpleName")
                    val id = info.id
                    if (!id.isNullOrEmpty()) {
                        val cn = android.content.ComponentName.unflattenFromString(id)
                        if (cn != null && cn.packageName == myPkg &&
                            (cn.className == myClass || cn.className == "$myPkg.$mySimple")
                        ) {
                            return@any true
                        }
                        if (id.equals("$myPkg/$myClass", ignoreCase = true) ||
                            id.equals("$myPkg/.$mySimple", ignoreCase = true) ||
                            id.equals("$myPkg/$mySimple", ignoreCase = true)
                        ) {
                            return@any true
                        }
                    }
                    // 2. Cek resolveInfo bila tersedia
                    val si = info.resolveInfo?.serviceInfo
                    si != null && si.packageName == myPkg &&
                        (si.name == myClass || si.name == "$myPkg.$mySimple")
                }
            } catch (_: Throwable) {
                false
            }
        }

        /** Sumber 2: Settings.Secure (bentuk panjang maupun pendek, tahan format OEM). */
        private fun secureListed(ctx: Context): Boolean {
            return try {
                val enabled = android.provider.Settings.Secure.getString(
                    ctx.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                if (enabled.isNullOrEmpty()) {
                    false
                } else {
                    val myPkg = ctx.packageName
                    val myClass = AgentAccessibilityService::class.java.name
                    val mySimple = AgentAccessibilityService::class.java.simpleName
                    val meLong = "$myPkg/$myClass"
                    val meShort = "$myPkg/.$mySimple"
                    val meBare = "$myPkg/$mySimple"

                    enabled.split(':').any { raw ->
                        val token = raw.trim()
                        if (token.isEmpty()) return@any false
                        if (token.equals(meLong, ignoreCase = true) ||
                            token.equals(meShort, ignoreCase = true) ||
                            token.equals(meBare, ignoreCase = true)
                        ) {
                            return@any true
                        }
                        val cn = android.content.ComponentName.unflattenFromString(token)
                        cn != null && cn.packageName == myPkg &&
                            (cn.className == myClass || cn.className == "$myPkg.$mySimple")
                    }
                }
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * v0.9.6 — dipanggil dari `Activity.onResume()` / dari tab Setup untuk
         * memastikan `bound` benar-benar mencerminkan keadaan OS, bahkan bila
         * layanan di-restart OEM tanpa `onServiceConnected()` pada proses ini.
         *
         * Sumber kebenaran eksternal: `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`.
         *
         * PENTING — arah dua arah:
         *  - ADA di daftar sementara `bound == false` → tandai true (layanan
         *    hidup di proses lain / OEM belum memanggil balik). Ini yang
         *    mencegah UI menyuruh mengaktifkan ulang.
         *  - TIDAK ADA di daftar → tandai false. Tanpa cabang ini, `bound`
         *    akan lengket `true` selamanya setelah sekali benar, dan UI tidak
         *    akan pernah menampilkan tombol "Aktifkan" walau pengguna baru
         *    saja mematikan layanan. Itu kegagalan yang berlawanan arah.
         *
         * Bila pembacaan Settings gagal (mis. OEM menyembunyikannya), kami
         * TIDAK mengubah `bound` — menghindari status palsu karena error baca.
         *
         * Selain itu, instance yang HIDUP selalu menang: bila layanan benar-benar
         * terhubung di proses ini, `bound` tetap true walau daftar Settings
         * belum ter-flush (mencegah kedip "BELUM AKTIF" tepat setelah aktivasi).
         */
        fun reconcileFromSettings(ctx: android.content.Context): Boolean {
            appContext = ctx.applicationContext
            // v0.9.7 — instance hidup adalah bukti terkuat; tidak perlu tanya OS.
            if (instance != null) {
                bound = true
                return true
            }
            // v0.9.7 — sumber kebenaran dipindah ke API resmi (lihat osEnabled).
            // v0.9.6 hanya membaca string Settings, yang rentan terhadap
            // perbedaan format per-OEM; kini API resmi dipakai lebih dulu dan
            // string Settings menjadi cadangan.
            bound = osEnabled(ctx)
            return bound
        }

        /** Bounds-based tap center → mirror of Go's ScreenBounds.Center(). */
        data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
            fun centerX() = (left + right) / 2
            fun centerY() = (top + bottom) / 2
        }

        /** Runs [block] on the current service instance. Returns null if the
         *  service is not connected, else the block's result. */
        inline fun <T> withInstance(block: (AgentAccessibilityService) -> T): T? {
            val svc = instance ?: return null
            return block(svc)
        }
    }

    /** Transport yang dipakai pada launch terakhir (v0.8.0, dipertahankan
     *  semantiknya di v0.9.0). Dilaporkan di hasil startApp + capabilities
     *  agar backend tidak menebak keandalan. */
    @Volatile
    var lastLaunchTransport: String = TRANSPORT_A11Y
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        bound = true
        Log.i(TAG, "AccessibilityService connected")
        notifyStateChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return

        // Capture toast notifications (Temuan #5, #7, ADR-01)
        if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            ToastTracker.recordEvent(event)
            return
        }
        // Dynamic Anti-Detection Hiding (Bypass deteksi developer/accessibility saat membuka Jenius/banking)
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val windowPkg = event.packageName?.toString().orEmpty()
            if (windowPkg.isNotEmpty()) {
                com.gososmed.agent.hiding.DynamicHidingManager.getInstance(applicationContext)
                    .reconcileAsync(windowPkg)
            }
        }


        // Hierarki aplikasi target tetap dibaca on-demand. Satu-satunya reaksi
        // per-event adalah pemindai dialog pairing ADB, dan ia aktif hanya saat
        // AdbPairingService mendaftarkan listener.
        val listener = pairingDialogListener ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }
        val pkg = event.packageName?.toString().orEmpty()
        if (!isSettingsPackage(pkg)) return

        val now = SystemClock.uptimeMillis()
        if (now - lastPairingDialogScanAt < 250L) return
        lastPairingDialogScanAt = now

        val snapshot = capturePairingDialogSnapshot()
        if (snapshot.hasPort || snapshot.code != null) {
            try {
                listener(snapshot)
            } catch (t: Throwable) {
                Log.w(TAG, "pendengar dialog pairing gagal", t)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        bound = false
        instance = null
        notifyStateChanged()
        // Auto-revive hanya bila pengguna memang mengaktifkan layanan ini.
        //
        // Bahaya tanpa penjagaan: `settings put secure accessibility_enabled 1`
        // dipanggil setiap unbind. Bila pengguna sengaja MEMATIKAN layanan,
        // unbind berikutnya akan menyalakannya lagi — perang setting yang tidak
        // pernah berhenti dan membuat tombol mati di Setelan terasa rusak.
        // Karena itu hanya dijalankan bila (a) ADB shell tersedia, dan
        // (b) penonaktifan bukan berasal dari pengguna (ditandai flag).
        if (userDisabled) return super.onUnbind(intent)
        kotlin.concurrent.thread(name = "a11y-watchdog") {
            try {
                Thread.sleep(800)
                val shell = com.gososmed.agent.privileged.PrivilegedShellHolder.get()
                if (!shell.status().available) return@thread
                if (userDisabled) return@thread
                val cmd = "settings put secure enabled_accessibility_services " +
                          "com.gososmed.agent/com.gososmed.agent.AgentAccessibilityService && " +
                          "settings put secure accessibility_enabled 1"
                shell.exec(cmd)
            } catch (_: Exception) {}
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        bound = false
        instance = null
        notifyStateChanged()
        super.onDestroy()
    }

    // ---- Read ----

    /**
     * Baca teks dialog pairing Debug nirkabel yang sedang terlihat.
     *
     * Fungsi ini hanya dipanggil selama sesi pairing dan hanya memindai window
     * yang package-nya Setelan. Hasil parse TIDAK dilog; kode pairing adalah
     * rahasia lokal yang langsung diteruskan ke libadb.
     */
    fun capturePairingDialogSnapshot(): PairingDialogParser.Snapshot {
        val roots = mutableListOf<AccessibilityNodeInfo>()

        try {
            val windowList = getWindows() ?: emptyList()
            for (window in windowList) {
                val root = window.root ?: continue
                val pkg = root.packageName?.toString().orEmpty()
                if (isSettingsPackage(pkg)) roots.add(root)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "getWindows gagal saat scan dialog pairing", t)
        }

        if (roots.isEmpty()) {
            rootInActiveWindow?.let { root ->
                val pkg = root.packageName?.toString().orEmpty()
                if (isSettingsPackage(pkg)) roots.add(root)
            }
        }

        // Parse PER WINDOW. Layar utama Debug nirkabel memiliki IP:port CONNECT;
        // menggabungkannya dengan kode dari window pairing akan memasangkan
        // kode yang benar dengan port yang salah.
        val windowsTexts = roots.map { root ->
            mutableListOf<String>().also { collectVisibleText(root, it, depth = 0) }
        }
        return PairingDialogParser.parseWindows(windowsTexts)
    }

    private fun collectVisibleText(
        node: AccessibilityNodeInfo,
        out: MutableList<String>,
        depth: Int,
    ) {
        if (depth > 40 || out.size >= 300) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectVisibleText(child, out, depth + 1)
        }
    }

    /** Dumps the current active window as uiautomator-compatible XML. */
    fun dumpXml(): String = HierarchySerializer.dump(rootInActiveWindow)

    fun currentPackage(): String {
        val root = rootInActiveWindow ?: return ""
        return root.packageName?.toString() ?: ""
    }

    // ---- FASE 0 V1: getWindows() vs rootInActiveWindow ----

    /**
     * Enumerates ALL interactive windows the service can see via getWindows()
     * and reports, for each, whether it exposes a root node that
     * `rootInActiveWindow` would MISS. This is the FASE 0 V1 experiment:
     * which system windows (permission dialog, notification shade, app
     * chooser, OAuth webview, Compose screen, lockscreen) are only reachable
     * through getWindows()?
     *
     * Requires flagRetrieveInteractiveWindows + canRetrieveWindowContent
     * (both already set in accessibility_service_config.xml).
     */
    fun dumpWindows(): JSONArray {
        val arr = JSONArray()
        // getWindows() requires API 21+; safe for minSdk 26.
        val windows = getWindows() ?: emptyList()
        for ((i, w) in windows.withIndex()) {
            val b = Rect()
            w.getBoundsInScreen(b)
            val root = w.root
            val o = JSONObject()
            o.put("index", i)
            o.put("windowId", w.id)
            o.put("type", winTypeName(w.type))
            o.put("isFocused", w.isFocused)
            o.put("isActive", w.isActive)
            o.put("bounds", "[${b.left},${b.top}][${b.right},${b.bottom}]")
            o.put("title", w.title?.toString() ?: "")
            o.put("package", root?.packageName?.toString() ?: "")
            o.put("hasRoot", root != null)
            // Apakah window ini ADALAH source dari rootInActiveWindow?
            // `w.isActive` secara langsung menandakan window aktif, tempat
            // rootInActiveWindow diambil. Tidak pakai `root === activeRoot`
            // (referential equality) karena getWindows() mengembalikan
            // instance node baru, bukan instance yang sama — bug sebelumnya.
            o.put("isActiveWindowRoot", w.isActive)
            if (root != null) {
                o.put("rootSummary", HierarchySerializer.summarize(root))
            }
            arr.put(o)
        }
        return arr
    }

    /** Deprecated getWindows() warning suppression (intentional for the
     *  FASE 0 V1 experiment; `windows` property is equivalent). */
    @Suppress("DEPRECATION")
    private fun winTypeName(type: Int): String {
        return when (type) {
            android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
            android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT_METHOD"
            android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
            android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "ACCESSIBILITY_OVERLAY"
            android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT_SCREEN_DIVIDER"
            android.view.accessibility.AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> "MAGNIFICATION_OVERLAY"
            else -> "UNKNOWN($type)"
        }
    }

    // ---- FG2: takeScreenshot (API 30+) ----

    /** Executor bersama untuk callback screenshot — SEBELUMNYA dibuat baru per
     *  panggilan (thread leak di HP user bila polling berjalan lama). */
    private val screenshotExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * Result container for raw screenshot bytes with explicit diagnostics and error code.
     * Implements component1..component4 for backwards-compatible Kotlin destructuring:
     * val (bytes, width, height) = res OR val (bytes, width, height, err) = res.
     */
    data class RawScreenshotResult(
        val bytes: ByteArray?,
        val width: Int,
        val height: Int,
        val error: String? = null,
        val sourceWidth: Int = width,
        val sourceHeight: Int = height,
        val sourceScale: Float = 1.0f
    )

    /**
     * Captures screenshot and returns raw bytes without base64 encoding (PLAN-DETERMINISTIC-ANDROID-PORTAL.md).
     * Returns RawScreenshotResult with detailed error codes if capture fails.
     */
    fun takeScreenshotRawBytes(
        scale: Float = 1f,
        format: String = "jpeg",
        quality: Int = 75,
        maxWidth: Int = 480
    ): RawScreenshotResult {
        if (Build.VERSION.SDK_INT < 30) {
            val err = "takeScreenshot requires API 30+ (device API ${Build.VERSION.SDK_INT})"
            Log.e(TAG, "takeScreenshotRawBytes failed: $err")
            return RawScreenshotResult(null, 0, 0, err)
        }
        if (format.equals("webp", ignoreCase = true)) {
            val err = "unsupported_image_format: format 'webp' is obsolete and forbidden. Use 'jpeg'."
            Log.e(TAG, "takeScreenshotRawBytes failed: $err")
            return RawScreenshotResult(null, 0, 0, err)
        }
        val latch = CountDownLatch(1)
        var hw: android.hardware.HardwareBuffer? = null
        var colorSpace: android.graphics.ColorSpace? = null
        var error: String? = null

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            screenshotExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    hw = screenshot.hardwareBuffer
                    colorSpace = screenshot.colorSpace
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    val codeName = when (errorCode) {
                        1 -> "ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR"
                        2 -> "ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS"
                        3 -> "ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT"
                        4 -> "ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY"
                        5 -> "ERROR_TAKE_SCREENSHOT_INVALID_WINDOW"
                        else -> "UNKNOWN"
                    }
                    error = "takeScreenshot failed errorCode=$errorCode ($codeName)"
                    Log.e(TAG, "takeScreenshotRawBytes onFailure: $error")
                    latch.countDown()
                }
            }
        )

        try {
            val completed = latch.await(4, TimeUnit.SECONDS)
            if (!completed) {
                val err = "screenshot latch await timed out after 4s"
                Log.e(TAG, "takeScreenshotRawBytes failed: $err")
                return RawScreenshotResult(null, 0, 0, err)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            val err = "interrupted waiting for screenshot latch"
            Log.e(TAG, "takeScreenshotRawBytes failed: $err")
            return RawScreenshotResult(null, 0, 0, err)
        }

        val buffer = hw
        if (buffer == null) {
            val err = error ?: "hardware buffer is null without error code"
            Log.e(TAG, "takeScreenshotRawBytes failed: $err")
            return RawScreenshotResult(null, 0, 0, err)
        }

        val bitmap = try {
            Bitmap.wrapHardwareBuffer(buffer, colorSpace)
        } finally {
            buffer.close()
        }
        if (bitmap == null) {
            val err = "failed to wrap hardware buffer"
            Log.e(TAG, "takeScreenshotRawBytes failed: $err")
            return RawScreenshotResult(null, 0, 0, err)
        }

        var soft: Bitmap? = null
        try {
            soft = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            Log.e(TAG, "takeScreenshotRawBytes copy hardware buffer failed", t)
        } finally {
            bitmap.recycle()
        }
        if (soft == null) {
            val err = "failed to copy hardware buffer to software ARGB_8888 (possible OOM)"
            Log.e(TAG, "takeScreenshotRawBytes failed: $err")
            return RawScreenshotResult(null, 0, 0, err)
        }

        try {
            val sourceWidth = soft.width
            val sourceHeight = soft.height

            val effectiveScale = when {
                scale < 1.0f -> scale.coerceIn(0.1f, 1.0f)
                maxWidth in 120..1920 && sourceWidth > maxWidth -> (maxWidth.toFloat() / sourceWidth.toFloat()).coerceIn(0.1f, 1.0f)
                else -> 1.0f
            }

            if (effectiveScale < 1.0f) {
                val targetW = (sourceWidth * effectiveScale).toInt().coerceAtLeast(1)
                val targetH = (sourceHeight * effectiveScale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(soft, targetW, targetH, true)
                if (scaled != soft) {
                    soft.recycle()
                    soft = scaled
                }
            }

            val finalWidth = soft.width
            val finalHeight = soft.height
            val out = ByteArrayOutputStream()
            val fmt = if (format.equals("png", ignoreCase = true)) {
                soft.compress(Bitmap.CompressFormat.PNG, 90, out)
            } else {
                soft.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
            }
            if (!fmt) {
                val err = "compress failed (format=$format, quality=$quality)"
                Log.e(TAG, "takeScreenshotRawBytes failed: $err")
                return RawScreenshotResult(null, 0, 0, err, sourceWidth, sourceHeight, effectiveScale)
            }
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) {
                val err = "compressed image bytes are empty"
                Log.e(TAG, "takeScreenshotRawBytes failed: $err")
                return RawScreenshotResult(null, 0, 0, err, sourceWidth, sourceHeight, effectiveScale)
            }
            Log.d(TAG, "takeScreenshotRawBytes success: ${bytes.size} bytes (${finalWidth}x${finalHeight}, src=${sourceWidth}x${sourceHeight}, s=$effectiveScale)")
            return RawScreenshotResult(bytes, finalWidth, finalHeight, null, sourceWidth, sourceHeight, effectiveScale)
        } finally {
            if (!soft.isRecycled) {
                soft.recycle()
            }
        }
    }

    /**
     * Unified base64 screenshot method delegating to [takeScreenshotRawBytes].
     */
    fun takeScreenshotBase64(scale: Float = 1f, format: String = "png", quality: Int = 85, maxWidth: Int = 0): Pair<String?, String?> {
        val res = takeScreenshotRawBytes(scale = scale, format = format, quality = quality, maxWidth = maxWidth)
        if (res.bytes == null) {
            return null to (res.error ?: "screenshot failed")
        }
        return Base64.encodeToString(res.bytes, Base64.NO_WRAP) to null
    }

    /**
     * v0.9.6 — KESIAPAN LIVE: apakah window aktif BENAR-BENAR bisa dibaca
     * SEKARANG. Ini satu-satunya makna yang benar untuk fungsi ini.
     *
     * JANGAN pakai fungsi ini untuk menampilkan status IZIN di Setup — pakai
     * `AgentAccessibilityService.isEnabled()` (lihat companion object).
     * `rootInActiveWindow` null pada transisi activity / layar kunci / app
     * FLAG_SECURE adalah kondisi NORMAL dan sementara; memperlakukannya
     * sebagai "layanan belum aktif" adalah bug regresi v0.9.5.
     */
    fun isServiceReady(): Boolean = rootInActiveWindow != null

    // ---- Act: gesture tap (mirror of Go Device.tapNode) ----

    /**
     * Taps the center of the whole window at absolute screen coords.
     *
     * v0.9.0 — bila transport shell siap, tap dikirim lewat `input tap`
     * (uid shell, izin INJECT_EVENTS). Ini jalur yang dipakai scrcpy/
     * uiautomator2 dan TIDAK terpengaruh dispatchGesture yang bisa senyap
     * gagal saat app target memasang FLAG_SECURE, saat overlay OEM aktif,
     * atau saat layanan accessibility di-throttle sistem.
     */
    fun tap(x: Int, y: Int): Boolean {
        val shell = PrivilegedShellHolder.get()
        if (shell.status().available) {
            val res = shell.exec("input tap $x $y")
            if (res.failure == null && res.ok) return true
            Log.w(TAG, "tap via shell gagal: ${res.failure ?: res.stderr} — fallback gesture")
        }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /** Finds the first node whose text/content-desc matches [text] and taps it. */
    fun tapByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root) { n ->
            (n.text?.toString() == text) || (n.contentDescription?.toString() == text)
        } ?: return false
        val b = boundsOf(node)
        return tap(b.centerX(), b.centerY())
    }

    /** Taps the first clickable node; returns its bounds when found. */
    fun tapFirstClickable(): Bounds? {
        val root = rootInActiveWindow ?: return null
        val node = findNode(root) { it.isClickable } ?: return null
        val b = boundsOf(node)
        return if (tap(b.centerX(), b.centerY())) b else null
    }

    /**
     * Swipes from (x1, y1) to (x2, y2) over [durationMs].
     * Prefers privileged shell (UID 2000) when available, falling back to dispatchGesture.
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 200L): Boolean {
        val dur = durationMs.coerceIn(50L, 2000L)
        val shell = PrivilegedShellHolder.get()
        if (shell.status().available) {
            val res = shell.exec("input swipe $x1 $y1 $x2 $y2 $dur")
            if (res.failure == null && res.ok) return true
            Log.w(TAG, "swipe via shell failed: ${res.failure ?: res.stderr} — fallback gesture")
        }
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, dur)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Maestro Parity: Refreshes OS AccessibilityInteractionClient cache to prevent stale hierarchy.
     */
    fun refreshAccessibilityCache() {
        try {
            val info = serviceInfo
            serviceInfo = info
        } catch (_: Throwable) {}
    }

    /**
     * Swipes based on semantic direction and amount (down, up, left, right).
     */
    fun swipeDirection(direction: String = "down", amount: String = "medium"): Boolean {
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val midX = width / 2
        val midY = height / 2

        val distance = when (amount.lowercase()) {
            "small" -> (height * 0.20f).toInt()
            "large" -> (height * 0.60f).toInt()
            else -> (height * 0.40f).toInt() // medium default
        }

        return when (direction.lowercase()) {
            "down" -> swipe(midX, midY + distance / 2, midX, midY - distance / 2, 250L)
            "up" -> swipe(midX, midY - distance / 2, midX, midY + distance / 2, 250L)
            "left" -> swipe(midX + distance / 2, midY, midX - distance / 2, midY, 250L)
            "right" -> swipe(midX - distance / 2, midY, midX + distance / 2, midY, 250L)
            else -> swipe(midX, midY + distance / 2, midX, midY - distance / 2, 250L)
        }
    }
    // ---- Act: text ----

    /**
     * Types [text] into the currently focused EDITABLE node using
     * ACTION_SET_TEXT (unicode-safe, unlike shell `input text`).
     *
     * FIX (4 Sep 2026): predicate lama `{ isEditable || isFocused }` bisa
     * memilih node focused yang TIDAK editable (mis. tombol fokus) lalu gagal
     * diam-diam. Kini dua tahap: editable dulu, baru focused+editable.
     */
    fun setText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root) { it.isEditable }
            ?: findNode(root) { it.isFocused && it.isEditable }
            ?: return false
        val bundle = Bundle()
        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    }

    // ---- Act: key events (via global actions) ----

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun notifyAction(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    // ---- Act: app lifecycle (startApp / killApp / hasPackage / listPackages) ----

    /**
     * Launches the app identified by [packageName] (e.g. com.facebook.katana).
     *
     * v0.7.0 — jalur utama memakai LauncherApps.startMainActivity dengan
     * UserHandle PRIMARY (user 0): MIUI XSpace TIDAK menampilkan resolver
     * Dual Apps bila user target eksplisit, jadi chooser bukan kewajiban dan
     * seharusnya tidak pernah muncul (pelajaran produksi 2026-09-11: chooser
     * menelan launch TikTok/FB dan verify gagal "layar tidak dikenali").
     * Clone XSpace hidup di user lain (999) — getActivityList(primary) tidak
     * melihatnya, jadi app murni selalu yang dipilih; jika yang terpasang
     * HANYA clone, daftar kosong → jujur gagal lewat jalur fallback.
     *
     * Fallback (perilaku lama): [activity] → komponen eksplisit setClassName;
     * tanpa [activity] → getLaunchIntentForPackage (implisit).
     * Returns false if the app is not installed or launch fails.
     */
    fun startApp(packageName: String, activity: String? = null): Boolean =
        startAppVerified(packageName, activity).first

    /**
     * v0.7.1 — launch yang TIDAK BOLEH berbohong.
     *
     * Versi lama mengembalikan true begitu startMainActivity/startActivity
     * tidak melempar exception. Itu hanya tanda terima PENGIRIMAN, bukan bukti
     * eksekusi: sejak Android 10 permintaan launch dari background (BAL)
     * ditelan sistem tanpa exception, sehingga server menerima ok=true padahal
     * layar tetap di launcher (insiden produksi 2026-09-11 di 5 platform).
     *
     * Urutan baru:
     *  1. pasang overlay 1x1 (pengecualian BAL) — lihat AgentOverlay;
     *  2. kirim launch;
     *  3. TUNGGU sampai foreground benar-benar milik package target;
     *  4. bila tidak terbukti → false + alasan yang bisa ditindak, bukan
     *     sukses palsu.
     *
     * Menunggu di sisi APK (event lokal, 250 ms) jauh lebih murah daripada
     * polling dari server yang butuh puluhan round-trip WebSocket.
     */
    fun startAppVerified(packageName: String, activity: String? = null): Pair<Boolean, String?> {
        if (!hasPackage(packageName)) {
            return false to "not_installed: $packageName tidak terpasang di HP ini"
        }
        // v0.9.0 TIER 1 — coba jalur shell (uid 2000) LEBIH DULU bila transport
        // ADB siap. `am start` dari uid shell BUKAN background activity launch,
        // jadi ia lolos dari blokade BAL Android 10+ maupun izin pop-up MIUI
        // yang menjadi akar semua kegagalan 5 platform pada 2026-09-11.
        val shell = PrivilegedShellHolder.get()
        if (shell.status().available) {
            val shellReason = startAppShell(packageName, activity)
            if (shellReason == null) {
                lastLaunchTransport = TRANSPORT_SHELL
                return true to null
            }
            Log.w(TAG, "launch via shell gagal ($shellReason) — fallback accessibility")
        }
        lastLaunchTransport = TRANSPORT_A11Y
        val overlayOk = AgentOverlay.ensure(this)
        if (!startAppRaw(packageName, activity)) {
            return false to "launch_rejected: sistem menolak permintaan membuka $packageName"
        }
        val shown = awaitForeground(packageName, LAUNCH_VERIFY_TIMEOUT_MS)
        if (shown) return true to null
        val current = currentPackage().ifEmpty { "tidak diketahui" }
        val reason = if (!overlayOk) {
            "bal_blocked: launch diblokir sistem (yang tampil: $current). " +
                "Izin 'tampil di atas app lain' belum aktif untuk GoSosmed Agent" +
                if (android.os.Build.MANUFACTURER.lowercase().contains("xiaomi") ||
                    android.os.Build.MANUFACTURER.lowercase().contains("redmi") ||
                    android.os.Build.MANUFACTURER.lowercase().contains("poco")
                ) {
                    "; di MIUI/HyperOS aktifkan juga Izin lainnya → 'Tampilkan jendela pop-up saat berjalan di latar belakang' dan Autostart"
                } else {
                    ""
                }
        } else {
            "launch_not_foreground: $packageName tidak muncul dalam " +
                "${LAUNCH_VERIFY_TIMEOUT_MS / 1000}s (yang tampil: $current)"
        }
        return false to reason
    }

    /**
     * Menunggu package target menjadi pemilik window aktif. Mengembalikan
     * false bila grace habis — pemanggil WAJIB memperlakukannya sebagai gagal
     * jujur, bukan alasan untuk menap buta (insiden browser Comet terbuka
     * karena tap jatuh di home screen).
     */
    private fun awaitForeground(packageName: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (currentPackage() == packageName) return true
            try {
                Thread.sleep(FOREGROUND_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return currentPackage() == packageName
            }
        }
        return currentPackage() == packageName
    }

    /**
     * v0.9.0 — launch lewat `am start` sebagai uid shell (ADB lokal).
     *
     * Mengembalikan null bila SUKSES TERVERIFIKASI (foreground benar-benar
     * milik package target), atau string alasan bila gagal. Sama seperti jalur
     * accessibility, sukses TIDAK PERNAH diasumsikan dari exit code saja:
     * `am start` bisa exit 0 padahal activity ditolak, jadi bukti tetap
     * diambil dari foreground yang sesungguhnya.
     */
    private fun startAppShell(packageName: String, activity: String?): String? {
        val shell = PrivilegedShellHolder.get()
        val target = if (!activity.isNullOrEmpty()) {
            "-n $packageName/${expandActivityName(packageName, activity)}"
        } else {
            null
        }
        // Urutan percobaan: komponen eksplisit (paling deterministik) →
        // intent LAUNCHER lewat `am start` → `monkey` (paling toleran, dipakai
        // openatx/uiautomator2 sebagai fallback saat activity tidak diketahui).
        val attempts = buildList {
            if (target != null) {
                add("am start -W --user 0 $target")
            }
            add("am start -W --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $packageName")
            add("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
        }
        var lastError = "tidak ada percobaan yang dijalankan"
        for (cmd in attempts) {
            val res = shell.exec(cmd)
            if (res.failure != null) {
                // Transport mati/ditolak di tengah jalan — percuma mencoba sisanya.
                return res.failure
            }
            // `am start` melaporkan Error/Warning di stdout walau exit code 0.
            val combined = (res.stdout + "\n" + res.stderr)
            val rejected = combined.contains("Error:", true) || combined.contains("Permission Denial", true)
            if (res.ok && !rejected && awaitForeground(packageName, SHELL_LAUNCH_VERIFY_TIMEOUT_MS)) {
                return null
            }
            lastError = when {
                rejected -> combined.trim().lineSequence().firstOrNull { it.isNotBlank() } ?: "ditolak"
                !res.ok -> "exit=${res.exitCode} ${res.stderr.take(160)}"
                else -> "tidak muncul di foreground (yang tampil: ${currentPackage().ifEmpty { "tidak diketahui" }})"
            }
        }
        return lastError
    }

    private fun startAppRaw(packageName: String, activity: String? = null): Boolean {
        try {
            val lm = getSystemService(android.content.Context.LAUNCHER_APPS_SERVICE) as? android.content.pm.LauncherApps
            if (lm != null) {
                val me = android.os.Process.myUserHandle()
                val items = lm.getActivityList(packageName, me)
                if (items.isNotEmpty()) {
                    val target = if (!activity.isNullOrEmpty()) {
                        android.content.ComponentName(packageName, expandActivityName(packageName, activity))
                    } else {
                        null
                    }
                    // Activity yang diminta dipakai hanya bila ia memang
                    // launcher activity user primary; selain itu pakai
                    // launcher default agar startMainActivity tidak gagal diam-diam.
                    val component = if (target != null && items.any { it.componentName == target }) {
                        target
                    } else {
                        items[0].componentName
                    }
                    lm.startMainActivity(component, me, null, null)
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "startApp($packageName, $activity) jalur LauncherApps gagal, fallback intent", e)
        }
        val intent = if (activity != null) {
            Intent().setClassName(packageName, activity)
        } else {
            packageManager.getLaunchIntentForPackage(packageName) ?: return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            // Use applicationContext so the lifecycle of the AccessibilityService
            // is NOT tied to the launched activity. MIUI was observed to call
            // onDestroy (and clear instance = null) when startActivity was
            // invoked from the service context directly, which made every
            // subsequent command (dump, tap, hasPackage) fail with
            // "accessibility service not connected/ready".
            applicationContext.startActivity(intent)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "startApp($packageName, $activity) gagal", e)
            return false
        }
    }

    /** Mengubah nama activity relatif (".LoginActivity") menjadi absolut. */
    private fun expandActivityName(pkg: String, activity: String): String {
        return if (activity.startsWith(".")) pkg + activity else activity
    }

    // ---- Act: screen state (wake) ----

    /**
     * v0.7.0 — command `wake` (blueprint Go-sosmed docs/3-ops/15 P0-4):
     * nyalakan layar saat job tiba dalam keadaan layar padam. Tanpa ini dump
     * UI mengembalikan null root dan verify/harvest gagal "layar tidak
     * dikenali".
     *
     * Wakelock ACQUIRE_CAUSES_WAKEUP menyalakan layar tanpa izin khusus
     * (deprecated tapi masih berfungsi di API 26+). Keyguard PIN/pola TIDAK
     * bisa dibuka dari sini — caller membaca dump berikutnya sebagai kondisi
     * terkunci yang jujur, bukan retry buta. true berarti layar menyala atau
     * wake terkirim; false bila PowerManager tidak tersedia/gagal.
     */
    fun wakeScreen(): Boolean {
        // v0.9.0 — jalur shell paling andal: keyevent WAKEUP setara adb dan
        // tidak bergantung pada WAKE_LOCK yang bisa ditolak kebijakan OEM.
        val shell = PrivilegedShellHolder.get()
        if (shell.status().available) {
            val res = shell.exec("input keyevent 224")
            if (res.failure == null && res.ok) return true
            Log.w(TAG, "wake via shell gagal: ${res.failure ?: res.stderr} — fallback wakelock")
        }
        return try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                ?: return false
            if (!pm.isInteractive) {
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    android.os.PowerManager.FULL_WAKE_LOCK or
                        android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        android.os.PowerManager.ON_AFTER_RELEASE,
                    "gososmed:wake"
                )
                wl.acquire(5_000)
                wl.release()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "wakeScreen gagal", e)
            false
        }
    }

    /** Returns true if [packageName] is installed on the device. */
    fun hasPackage(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Kills an app's background processes. NOTE: without root we cannot force-stop
     * a foreground app (that is `am force-stop`, shell-only). This is an honest
     * subset: it stops background processes of [packageName].
     */
    fun killApp(packageName: String): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return false
        am.killBackgroundProcesses(packageName)
        return true
    }

    /**
     * v0.7.1 — killApp yang menyatakan BATASNYA, bukan hanya true.
     *
     * `killBackgroundProcesses` TIDAK sama dengan `am force-stop`: proses yang
     * sedang di foreground / punya service hidup tidak akan mati. Server dulu
     * menganggap true = layar sudah direset ke kondisi deterministik — asumsi
     * palsu yang membuat harvest menap layar yang salah. Sekarang mode
     * dilaporkan apa adanya supaya Go bisa memilih strategi (HOME + relaunch)
     * ketimbang percaya reset yang tidak pernah terjadi.
     */
    fun killAppMode(packageName: String): String {
        // v0.9.0 — dengan transport shell, force-stop yang SEBENARNYA bisa
        // dilakukan. Ini syarat kondisi awal deterministik: harvest tidak lagi
        // mendarat di layar sisa sesi sebelumnya (feed, dialog, story viewer).
        val shell = PrivilegedShellHolder.get()
        if (shell.status().available) {
            val res = shell.exec("am force-stop $packageName")
            if (res.failure == null && res.ok) return "force_stop"
            Log.w(TAG, "force-stop via shell gagal: ${res.failure ?: res.stderr} — fallback killBackgroundProcesses")
        }
        val am = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return "unavailable"
        am.killBackgroundProcesses(packageName)
        return if (currentPackage() == packageName) "foreground_survived" else "background_only"
    }

    /**
     * v0.9.0 — kapabilitas nyata perangkat ini, dibaca dari sistem (bukan
     * asumsi). Dipakai backend untuk PREFLIGHT: job harvest yang pasti gagal
     * ditolak lebih awal dengan alasan yang bisa ditindak pemilik HP,
     * ketimbang menumpuk job yang berakhir platform_error.
     *
     * Nama field netral `adb_*` (kontrak §5). Pemetaan dari v0.8.0:
     *   shizuku_running -> adb_connected ; shizuku_uid -> adb_uid
     *   shizuku_installed/version/permission_denied_forever -> DIHAPUS
     *   can_shell -> nama SAMA, sumbernya sekarang sesi ADB
     */
    fun capabilitiesJson(): JSONObject {
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        val shellStatus = PrivilegedShellHolder.get().status()
        return JSONObject().apply {
            put("protocol_versions", org.json.JSONArray(listOf(1, 2)))
            put("agent_version", BuildConfig.VERSION_NAME)
            put("api_level", Build.VERSION.SDK_INT)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            // v0.9.6 — DUA dimensi yang sengaja dipisah agar backend tidak
            // salah menyimpulkan. `a11y_enabled` = layanan ter-bind (dipakai
            // untuk keandalan transport jangka panjang). `a11y_ready` = window
            // sedang terbaca SEKARANG (dipakai untuk memutuskan apakah perintah
            // dump/tap punya peluang sukses pada detik ini). Nilai yang berbeda
            // adalah keadaan NORMAL, bukan cacat.
            put("a11y_enabled", isEnabled())
            put("a11y_ready", isServiceReady())
            put("a11y_blocked", isRestrictedBlocked(this@AgentAccessibilityService))
            // Pengecualian Background Activity Launch — penentu bisa/tidaknya
            // membuka app target dari server.
            put("can_draw_overlay", AgentOverlay.canDraw(this@AgentAccessibilityService))
            put("overlay_attached", AgentOverlay.isAttached())
            // v0.9.0 — tingkat transport. shell = deterministik (uid 2000),
            // accessibility = best-effort dan tunduk pada BAL/kebijakan OEM.
            put("transport_tier", if (shellStatus.available) TRANSPORT_SHELL else TRANSPORT_A11Y)
            put("last_launch_transport", lastLaunchTransport)
            put("adb_paired", shellStatus.paired)
            put("adb_connected", shellStatus.connected)
            put("adb_uid", shellStatus.uid)
            put("adb_error", shellStatus.error)
            put("can_shell", shellStatus.available)
            // Dengan shell, launch tidak butuh izin overlay sama sekali.
            put("can_launch_app", shellStatus.available || AgentOverlay.canDraw(this@AgentAccessibilityService))
            put("can_force_stop", shellStatus.available)
            put("can_inject_input", shellStatus.available)
            put("can_screenshot", Build.VERSION.SDK_INT >= 30)
            put("battery_unrestricted", pm?.isIgnoringBatteryOptimizations(packageName) == true)
            put("screen_interactive", pm?.isInteractive == true)
        }
    }

    /**
     * Returns the list of all installed packages that have a launcher intent
     * (apps visible on the home screen). Used by the backend to detect clone
     * apps (e.g. com.facebook.katana vs com.facebook.katana:parasitical)
     * and choose the correct one before launching.
     */
    fun listPackages(): List<String> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
        return apps.map { it.activityInfo.packageName }.distinct().sorted()
    }

    /**
     * Menunggu kemunculan node secara lokal in-memory (polling tiap 80ms)
     * tanpa overhead serialisasi XML. Menghemat 90% waktu tunggu.
     */
    fun waitForNode(
        textMatch: String? = null,
        descMatch: String? = null,
        resIdMatch: String? = null,
        timeoutMs: Long = 4000L
    ): Pair<Boolean, android.graphics.Rect?> {
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs
        val targetText = textMatch?.lowercase()?.trim()
        val targetDesc = descMatch?.lowercase()?.trim()
        val targetResId = resIdMatch?.lowercase()?.trim()

        while (System.currentTimeMillis() < deadline) {
            var found: android.graphics.Rect? = null
            val root = rootInActiveWindow
            if (root != null) {
                found = searchNodeRecursive(root, targetText, targetDesc, targetResId, 0)
                root.recycle()
            }
            if (found == null) {
                val winList = windows
                for (w in winList) {
                    val wRoot = w.root ?: continue
                    found = searchNodeRecursive(wRoot, targetText, targetDesc, targetResId, 0)
                    wRoot.recycle()
                    if (found != null) break
                }
            }
            if (found != null) {
                return true to found
            }
            try {
                Thread.sleep(80L)
            } catch (_: InterruptedException) {
                break
            }
        }
        return false to null
    }

    /**
     * Compound Action: Mengetuk elemen target (via text/desc/resId/koordinat)
     * lalu langsung menunggu kemunculan elemen tujuan (wait target) di memori HP.
     * Menghemat 50% giliran percakapan LLM dan round-trip jaringan.
     */
    fun clickAndWait(
        clickText: String? = null,
        clickDesc: String? = null,
        clickResId: String? = null,
        clickX: Int? = null,
        clickY: Int? = null,
        waitText: String? = null,
        waitDesc: String? = null,
        waitResId: String? = null,
        timeoutMs: Long = 4000L
    ): Triple<Boolean, Boolean, android.graphics.Rect?> {
        var clicked = false
        if (clickX != null && clickY != null && clickX > 0 && clickY > 0) {
            clicked = tap(clickX, clickY)
        } else if (!clickText.isNullOrBlank()) {
            clicked = tapByText(clickText)
        } else if (!clickDesc.isNullOrBlank()) {
            clicked = tapByText(clickDesc)
        } else if (!clickResId.isNullOrBlank()) {
            val root = rootInActiveWindow
            if (root != null) {
                val r = searchNodeRecursive(root, null, null, clickResId.lowercase().trim(), 0)
                root.recycle()
                if (r != null) {
                    clicked = tap(r.centerX(), r.centerY())
                }
            }
        }

        if (!clicked) {
            return Triple(false, false, null)
        }

        if (waitText.isNullOrBlank() && waitDesc.isNullOrBlank() && waitResId.isNullOrBlank()) {
            return Triple(true, true, null)
        }

        val (found, rect) = waitForNode(waitText, waitDesc, waitResId, timeoutMs)
        return Triple(true, found, rect)
    }

    private fun searchNodeRecursive(
        node: AccessibilityNodeInfo,
        textTarget: String?,
        descTarget: String?,
        resIdTarget: String?,
        depth: Int = 0
    ): android.graphics.Rect? {
        if (depth > 50) return null
        val t = node.text?.toString()?.lowercase()?.trim().orEmpty()
        val cd = node.contentDescription?.toString()?.lowercase()?.trim().orEmpty()
        val id = node.viewIdResourceName?.lowercase()?.trim().orEmpty()

        val textMatches = textTarget != null && (t.contains(textTarget) || cd.contains(textTarget))
        val descMatches = descTarget != null && cd.contains(descTarget)
        val idMatches = resIdTarget != null && id.contains(resIdTarget)

        if (textMatches || descMatches || idMatches) {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) return r
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchNodeRecursive(child, textTarget, descTarget, resIdTarget, depth + 1)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    // ---- Helpers ----

    private fun boundsOf(node: AccessibilityNodeInfo): Bounds {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        return Bounds(rect.left, rect.top, rect.right, rect.bottom)
    }

    private fun findNode(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findNode(child, predicate)?.let { return it }
        }
        return null
    }
}
