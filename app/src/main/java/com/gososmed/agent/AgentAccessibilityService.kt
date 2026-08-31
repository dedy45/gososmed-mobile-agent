package com.gososmed.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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

    fun dumpJson(): String = HierarchySerializer.dumpToJson(rootInActiveWindow)

    fun currentPackage(): String {
        val root = rootInActiveWindow ?: return ""
        return root.packageName?.toString() ?: ""
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
     * Types [text] into the currently focused editable node using
     * ACTION_SET_TEXT (unicode-safe, unlike shell `input text`).
     * Returns false if no focused editable field is found.
     */
    fun setText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root) { it.isEditable || it.isFocused } ?: return false
        val bundle = Bundle()
        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    }

    // ---- Act: key events (via global actions) ----

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun notifyAction(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    // ---- Act: app lifecycle (startApp / killApp / hasPackage) ----

    /**
     * Launches the app identified by [packageName] (e.g. com.ss.android.ugc.aweme)
     * using its MAIN/LAUNCHER intent. Returns false if the app is not installed
     * or has no launcher intent. This is the accessibility-legal equivalent of
     * `adb shell am start` — no root, no shell.
     */
    fun startApp(packageName: String): Boolean {
        val pm = packageManager
        val intent = pm.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "startApp($packageName) gagal", e)
            return false
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

    /** Returns true if [packageName] is installed on the device. */
    fun hasPackage(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
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
