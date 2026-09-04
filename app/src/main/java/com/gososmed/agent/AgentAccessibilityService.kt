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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilityService connected")
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

    /** Taps the center of the whole window at absolute screen coords. */
    fun tap(x: Int, y: Int): Boolean {
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
     * When [activity] is provided, launches its explicit component via
     * setClassName — this avoids the Android resolver (app chooser) that
     * appears when a clone / dual-app matches the same implicit launcher intent.
     * Without [activity], falls back to getLaunchIntentForPackage (implicit).
     * Returns false if the app is not installed or launch fails.
     */
    fun startApp(packageName: String, activity: String? = null): Boolean {
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
