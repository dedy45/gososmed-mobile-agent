package com.gososmed.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
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

        /** Batas tunggu bukti foreground setelah launch (v0.7.1). Cold start
         *  app berat (Facebook/TikTok) di HP kelas menengah bisa 5-7 detik. */
        private const val LAUNCH_VERIFY_TIMEOUT_MS = 10_000L

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

        /** Scan manual sekali (dipakai saat overlay baru dipasang/ditampilkan). */
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

        /** Sumber 1: API resmi AccessibilityManager. */
        private fun apiListed(ctx: Context): Boolean {
            return try {
                val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                    ?: return false
                val list = am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                ) ?: return false
                val myName = AgentAccessibilityService::class.java.name
                list.any { info ->
                    val si = info.resolveInfo?.serviceInfo ?: return@any false
                    si.packageName == ctx.packageName && si.name == myName
                }
            } catch (_: Throwable) {
                false
            }
        }

        /** Sumber 2: Settings.Secure (bentuk panjang maupun pendek). */
        private fun secureListed(ctx: Context): Boolean {
            return try {
                val enabled = android.provider.Settings.Secure.getString(
                    ctx.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                if (enabled.isNullOrEmpty()) {
                    false
                } else {
                    val me = ctx.packageName + "/" + AgentAccessibilityService::class.java.name
                    val meShort =
                        ctx.packageName + "/." + AgentAccessibilityService::class.java.simpleName
                    enabled.split(':').any {
                        it.equals(me, ignoreCase = true) || it.equals(meShort, ignoreCase = true)
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
        // v0.9.0: tidak ada lagi permintaan izin Shizuku di sini. Transport
        // shell kini ADB lokal, dan pairing-nya dipicu dari UI/command
        // `adbPair` (kontrak §3.2) — bukan otomatis saat service ter-bind.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Hierarki aplikasi target tetap dibaca on-demand. Satu-satunya reaksi
        // per-event adalah pemindai dialog pairing ADB, dan ia aktif hanya saat
        // AdbPairingService mendaftarkan listener.
        val listener = pairingDialogListener ?: return
        val type = event?.eventType ?: return
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
        // v0.9.6 — layanan dilepas (mis. dimatikan manual / di-restart OEM).
        bound = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        bound = false
        instance = null
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
     * Captures the current display, lalu mengembalikannya base64.
     *
     * Parameter efisiensi (menjawab beban bandwidth saat puluhan user membuka
     * layar device bersamaan):
     *  - [scale] 0,25–1,0 mengecilkan resolusi (0,5 = ¼ jumlah piksel).
     *  - [format] "png" (default, kompatibel) atau "jpeg" (±10× lebih kecil
     *    untuk foto layar; dipadukan [quality] 1–100).
     *
     * Backend menagih lewat field opsional di command "screenshot"; default
     * tanpa parameter tetap PNG penuh agar audit tidak kehilangan detail.
     */
    fun takeScreenshotBase64(scale: Float = 1f, format: String = "png", quality: Int = 85): Pair<String?, String?> {
        if (Build.VERSION.SDK_INT < 30) {
            return null to "takeScreenshot requires API 30+ (device API ${Build.VERSION.SDK_INT})"
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
                    error = "takeScreenshot failed errorCode=$errorCode"
                    latch.countDown()
                }
            }
        )

        // Wait up to 8s for the OS to produce the frame (typically <500ms).
        try {
            latch.await(8, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null to "interrupted waiting for screenshot"
        }
        val buffer = hw ?: return null to (error ?: "screenshot timed out")

        val bitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
        buffer.close()
        if (bitmap == null) return null to "failed to wrap hardware buffer"
        // wrapHardwareBuffer returns an immutable hardware-backed bitmap;
        // copy to a software ARGB_8888 before compress.
        var soft = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        bitmap.recycle()
        if (soft == null) return null to "failed to copy screenshot bitmap"

        // Downscale bila diminta (hemat bandwidth WS + edge Cloudflare).
        val s = scale.coerceIn(0.25f, 1f)
        if (s < 1f) {
            val scaled = Bitmap.createScaledBitmap(
                soft,
                (soft.width * s).toInt().coerceAtLeast(1),
                (soft.height * s).toInt().coerceAtLeast(1),
                true
            )
            soft.recycle()
            soft = scaled
        }

        val out = ByteArrayOutputStream()
        val fmt = if (format.equals("jpeg", ignoreCase = true) || format.equals("jpg", ignoreCase = true)) {
            soft.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        } else {
            soft.compress(Bitmap.CompressFormat.PNG, 90, out)
        }
        soft.recycle()
        if (!fmt) return null to "compress failed"
        val bytes = out.toByteArray()
        if (bytes.isEmpty()) return null to "empty image"
        return Base64.encodeToString(bytes, Base64.NO_WRAP) to null
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

    // ---- v0.9.7: jendela overlay milik layanan aksesibilitas ----

    /**
     * WindowManager yang sah untuk memasang jendela aksesibilitas.
     * [PairingOverlay] memerlukannya agar drag bisa memanggil
     * `updateViewLayout`.
     */
    fun overlayWindowManager(): WindowManager? =
        getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    /**
     * Pasang jendela `TYPE_ACCESSIBILITY_OVERLAY`.
     *
     * ================== INI KUNCI PERBAIKAN "OVERLAY TIDAK MUNCUL" ==================
     *
     * Jendela jenis ini HANYA boleh dipasang oleh layanan aksesibilitas, dan
     * imbalannya: ia **TIDAK memerlukan izin `SYSTEM_ALERT_WINDOW`** sama sekali.
     * Itu menghapus DUA penghalang sekaligus yang membuat v0.9.4–v0.9.6 gagal:
     *
     *  1. izin "Tampilkan di atas aplikasi lain" yang belum diberikan pengguna, dan
     *  2. saklar OEM MIUI/HyperOS yang TERPISAH ("Tampilkan jendela sembulan
     *     saat berjalan di latar belakang") yang tidak bisa dibaca maupun
     *     diminta lewat API publik.
     *
     * Karena jendelanya milik layanan sistem — bukan "aplikasi yang menggambar
     * di atas aplikasi lain" — penjagaan pop-up latar belakang OEM tidak
     * berlaku padanya.
     *
     * WAJIB dipanggil dari main thread. Mengembalikan false bila sistem
     * menolak, dan alasannya dicatat ke log — bukan gagal senyap.
     */
    fun attachAccessibilityOverlay(view: View, params: WindowManager.LayoutParams): Boolean {
        return try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
            params.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            wm.addView(view, params)
            val attached = view.isAttachedToWindow
            if (!attached) {
                Log.w(TAG, "accessibility overlay: addView tidak error tetapi TIDAK attached")
            }
            attached
        } catch (t: Throwable) {
            Log.w(TAG, "accessibility overlay ditolak sistem", t)
            false
        }
    }

    /** Lepas jendela overlay aksesibilitas. Aman dipanggil berkali-kali. */
    fun detachAccessibilityOverlay(view: View) {
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
            wm.removeViewImmediate(view)
        } catch (t: Throwable) {
            Log.w(TAG, "gagal melepas accessibility overlay", t)
        }
    }

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
            if (res.ok && !rejected && awaitForeground(packageName, LAUNCH_VERIFY_TIMEOUT_MS)) {
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
