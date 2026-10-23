package com.gososmed.agent

import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory sliding-window tracker for active Toast messages (system-design-feedback-orpv-2026-10-23.md ADR-01 & §3.1).
 *
 * Rules:
 *  1. TTL: 3500ms sliding window.
 *  2. Filter: className MUST contain "Toast" and packageName MUST NOT be empty.
 *  3. Non-authoritative: virtual node has ancestorPath="toast" and cannot serve as authoritative verification.
 */
object ToastTracker {
    const val TOAST_TTL_MS: Long = 3500L

    data class ToastEntry(
        val packageName: String,
        val text: String,
        val timestampMs: Long
    ) {
        fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
            (nowMs - timestampMs) > TOAST_TTL_MS
    }

    private val lock = Any()
    private val recentToasts = CopyOnWriteArrayList<ToastEntry>()

    /**
     * Records a validated toast notification.
     * Rejects empty package name (Temuan #7) or non-toast class names.
     */
    fun recordToast(
        packageName: String?,
        className: String?,
        text: String?,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (className.isNullOrBlank() || !className.contains("Toast", ignoreCase = true)) return false
        if (text.isNullOrBlank()) return false

        // Do not accept toast from agent package itself
        if (packageName.trim() == "com.gososmed.agent") return false

        synchronized(lock) {
            pruneExpired(nowMs)
            recentToasts.add(ToastEntry(packageName.trim(), text.trim(), nowMs))
            return true
        }
    }

    /**
     * Records toast directly from Android AccessibilityEvent.
     */
    fun recordEvent(event: android.view.accessibility.AccessibilityEvent?, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (event == null) return false
        if (event.eventType != android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return false

        val pkg = event.packageName?.toString() ?: return false
        val cls = event.className?.toString() ?: return false
        val textList = event.text
        if (textList.isNullOrEmpty()) return false
        val joinedText = textList.joinToString(" ").trim()
        if (joinedText.isEmpty()) return false

        return recordToast(pkg, cls, joinedText, nowMs)
    }

    /**
     * Retrieves unexpired toasts, optionally filtered by target package.
     */
    fun getRecentToasts(
        targetPackage: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): List<ToastEntry> {
        synchronized(lock) {
            pruneExpired(nowMs)
            return recentToasts.filter { entry ->
                !entry.isExpired(nowMs) && (targetPackage.isNullOrBlank() || entry.packageName.equals(targetPackage.trim(), ignoreCase = true))
            }
        }
    }

    /**
     * Retrieves the newest active toast for target package.
     */
    fun getLatestToast(
        targetPackage: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): ToastEntry? {
        return getRecentToasts(targetPackage, nowMs).lastOrNull()
    }

    /**
     * Converts a toast entry to a non-authoritative virtual SnapshotNode (ADR-01).
     */
    fun toVirtualNode(entry: ToastEntry): SnapshotNode {
        return SnapshotNode(
            nodeId = "toast-${entry.timestampMs}",
            windowId = 0,
            packageName = entry.packageName,
            className = "android.widget.Toast",
            text = entry.text,
            contentDescription = entry.text,
            bounds = SnapshotBounds(0, 0, 1080, 200),
            clickable = false,
            longClickable = false,
            actionable = false,
            ancestorPath = "toast"
        )
    }

    /**
     * Clears all recorded toasts (used on job stop / reset to prevent leakage across jobs).
     */
    fun clear() {
        synchronized(lock) {
            recentToasts.clear()
        }
    }

    private fun pruneExpired(nowMs: Long) {
        recentToasts.removeAll { it.isExpired(nowMs) }
    }
}
