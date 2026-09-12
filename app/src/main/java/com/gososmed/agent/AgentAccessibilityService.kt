package com.gososmed.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

        /** Nama transport yang dilaporkan ke server (v0.8.0). Backend memakai
         *  ini untuk tahu tingkat keandalan perintah: shell = deterministik
         *  (uid 2000), accessibility = best-effort dan tunduk pada BAL/OEM. */
        const val TRANSPORT_SHELL = "shell_shizuku"
        const val TRANSPORT_A11Y = "accessibility"
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

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

    /** Transport yang dipakai pada launch terakhir (v0.8.0). Dilaporkan di
     *  hasil startApp + capabilities agar backend tidak menebak keandalan. */
    @Volatile
    var lastLaunchTransport: String = TRANSPORT_A11Y
        private set

    /** Izin Shizuku hanya diminta sekali per hidup layanan agar pemilik HP
     *  tidak dihujani dialog saat layanan di-rebind (sering di MIUI). */
    @Volatile
    private var shizukuAsked = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilityService connected")
        // v0.8.0: bila Shizuku sudah berjalan tetapi agent belum diizinkan,
        // minta izin SEKALI di sini. Dialog dimunculkan aplikasi Shizuku
        // sendiri, jadi tidak butuh Activity milik kita dan tidak mengubah
        // layout. Semua kegagalan ditelan ShizukuShell (tidak pernah crash);
        // bila user menolak, agent tetap jalan di jalur accessibility.
        if (!shizukuAsked && ShizukuShell.binderAlive() && !ShizukuShell.hasPermission()) {
            shizukuAsked = true
            val asked = ShizukuShell.requestPermission()
            AgentLog.add(
                "shizuku",
                asked,
                0,
                if (asked) {
                    "meminta izin Shizuku (setujui dialog di HP untuk transport shell)"
                } else {
                    "Shizuku berjalan tetapi permintaan izin gagal"
                }
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // P0: no per-event reaction needed; hierarchy is pulled on demand.
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ---- Read ----

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

    fun isServiceReady(): Boolean = rootInActiveWindow != null

    // ---- Act: gesture tap (mirror of Go Device.tapNode) ----

    /**
     * Taps the center of the whole window at absolute screen coords.
     *
     * v0.8.0 — bila Shizuku siap, tap dikirim lewat `input tap` (uid shell,
     * izin INJECT_EVENTS). Ini jalur yang dipakai scrcpy/uiautomator2 dan
     * TIDAK terpengaruh dispatchGesture yang bisa senyap gagal saat app target
     * memasang FLAG_SECURE, saat overlay OEM aktif, atau saat layanan
     * accessibility di-throttle sistem.
     */
    fun tap(x: Int, y: Int): Boolean {
        if (ShizukuShell.ready()) {
            val res = ShizukuShell.exec("input tap $x $y")
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
        // v0.8.0 TIER 1 — coba jalur shell (uid 2000) LEBIH DULU bila Shizuku
        // siap. `am start` dari uid shell BUKAN background activity launch,
        // jadi ia lolos dari blokade BAL Android 10+ maupun izin pop-up MIUI
        // yang menjadi akar semua kegagalan 5 platform pada 2026-09-11.
        if (ShizukuShell.ready()) {
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
     * v0.8.0 — launch lewat `am start` sebagai uid shell (Shizuku).
     *
     * Mengembalikan null bila SUKSES TERVERIFIKASI (foreground benar-benar
     * milik package target), atau string alasan bila gagal. Sama seperti jalur
     * accessibility, sukses TIDAK PERNAH diasumsikan dari exit code saja:
     * `am start` bisa exit 0 padahal activity ditolak, jadi bukti tetap
     * diambil dari foreground yang sesungguhnya.
     */
    private fun startAppShell(packageName: String, activity: String?): String? {
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
            val res = ShizukuShell.exec(cmd)
            if (res.failure != null) {
                // Shizuku mati/ditolak di tengah jalan — percuma mencoba sisanya.
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
        // v0.8.0 — jalur shell paling andal: keyevent WAKEUP setara adb dan
        // tidak bergantung pada WAKE_LOCK yang bisa ditolak kebijakan OEM.
        if (ShizukuShell.ready()) {
            val res = ShizukuShell.exec("input keyevent 224")
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
        // v0.8.0 — dengan Shizuku, force-stop yang SEBENARNYA bisa dilakukan.
        // Ini syarat kondisi awal deterministik: harvest tidak lagi mendarat
        // di layar sisa sesi sebelumnya (feed, dialog, story viewer).
        if (ShizukuShell.ready()) {
            val res = ShizukuShell.exec("am force-stop $packageName")
            if (res.failure == null && res.ok) return "force_stop"
            Log.w(TAG, "force-stop via shell gagal: ${res.failure ?: res.stderr} — fallback killBackgroundProcesses")
        }
        val am = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return "unavailable"
        am.killBackgroundProcesses(packageName)
        return if (currentPackage() == packageName) "foreground_survived" else "background_only"
    }

    /**
     * v0.7.1 — kapabilitas nyata perangkat ini, dibaca dari sistem (bukan
     * asumsi). Dipakai backend untuk PREFLIGHT: job harvest yang pasti gagal
     * ditolak lebih awal dengan alasan yang bisa ditindak pemilik HP,
     * ketimbang menumpuk job yang berakhir platform_error.
     */
    fun capabilitiesJson(): JSONObject {
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        return JSONObject().apply {
            put("agent_version", BuildConfig.VERSION_NAME)
            put("api_level", Build.VERSION.SDK_INT)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("a11y_ready", isServiceReady())
            // Pengecualian Background Activity Launch — penentu bisa/tidaknya
            // membuka app target dari server.
            put("can_draw_overlay", AgentOverlay.canDraw(this@AgentAccessibilityService))
            put("overlay_attached", AgentOverlay.isAttached())
            // v0.8.0 — tingkat transport. shell = deterministik (uid 2000),
            // accessibility = best-effort dan tunduk pada BAL/kebijakan OEM.
            val shizukuAlive = ShizukuShell.binderAlive()
            val shizukuReady = ShizukuShell.ready()
            put("transport_tier", if (shizukuReady) TRANSPORT_SHELL else TRANSPORT_A11Y)
            put("last_launch_transport", lastLaunchTransport)
            put("shizuku_installed", ShizukuShell.managerInstalled(this@AgentAccessibilityService))
            put("shizuku_running", shizukuAlive)
            put("shizuku_permission", ShizukuShell.hasPermission())
            put("shizuku_permission_denied_forever", ShizukuShell.permissionPermanentlyDenied())
            put("shizuku_uid", ShizukuShell.privilegeUid())
            put("shizuku_version", ShizukuShell.serverVersion())
            put("can_shell", shizukuReady)
            // Dengan shell, launch tidak butuh izin overlay sama sekali.
            put("can_launch_app", shizukuReady || AgentOverlay.canDraw(this@AgentAccessibilityService))
            put("can_force_stop", shizukuReady)
            put("can_inject_input", shizukuReady)
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
