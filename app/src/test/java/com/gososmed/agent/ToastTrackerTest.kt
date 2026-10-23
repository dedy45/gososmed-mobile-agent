package com.gososmed.agent

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ToastTrackerTest {

    @Before
    fun setup() {
        ToastTracker.clear()
    }

    @Test
    fun testRecordAndRetrieveValidToast() {
        val now = 1000L
        val recorded = ToastTracker.recordToast(
            packageName = "com.zhiliaoapp.musically",
            className = "android.widget.Toast",
            text = "Video telah dibagikan",
            nowMs = now
        )
        assertTrue(recorded)

        val latest = ToastTracker.getLatestToast("com.zhiliaoapp.musically", nowMs = now + 1000L)
        assertNotNull(latest)
        assertEquals("Video telah dibagikan", latest?.text)
        assertEquals("com.zhiliaoapp.musically", latest?.packageName)
    }

    @Test
    fun testRejectToastWithBlankPackage() {
        val recorded = ToastTracker.recordToast(
            packageName = "",
            className = "android.widget.Toast",
            text = "Some text"
        )
        assertFalse("Toast with empty packageName must be rejected", recorded)
        assertTrue(ToastTracker.getRecentToasts().isEmpty())
    }

    @Test
    fun testRejectToastWithNonToastClass() {
        val recorded = ToastTracker.recordToast(
            packageName = "com.example.app",
            className = "android.widget.TextView",
            text = "Not a toast"
        )
        assertFalse(recorded)
        assertTrue(ToastTracker.getRecentToasts().isEmpty())
    }

    @Test
    fun testRejectToastFromAgentPackage() {
        val recorded = ToastTracker.recordToast(
            packageName = "com.gososmed.agent",
            className = "android.widget.Toast",
            text = "Internal message"
        )
        assertFalse(recorded)
        assertTrue(ToastTracker.getRecentToasts().isEmpty())
    }

    @Test
    fun testToastExpiresAfterTtl() {
        val now = 1000L
        ToastTracker.recordToast(
            packageName = "com.example.app",
            className = "android.widget.Toast",
            text = "Short lived",
            nowMs = now
        )

        // 3400ms later: still fresh (TTL is 3500ms)
        assertNotNull(ToastTracker.getLatestToast("com.example.app", nowMs = now + 3400L))

        // 3501ms later: expired
        assertNull(ToastTracker.getLatestToast("com.example.app", nowMs = now + 3501L))
    }

    @Test
    fun testToVirtualNodeProducesNonAuthoritativeToastNode() {
        val entry = ToastTracker.ToastEntry(
            packageName = "com.example.app",
            text = "Upload complete",
            timestampMs = 5000L
        )
        val node = ToastTracker.toVirtualNode(entry)
        assertEquals("android.widget.Toast", node.className)
        assertEquals("Upload complete", node.text)
        assertEquals("toast", node.ancestorPath)
        assertFalse(node.clickable)
    }

    @Test
    fun testFilterByTargetPackage() {
        ToastTracker.recordToast(
            packageName = "com.instagram.android",
            className = "android.widget.Toast",
            text = "Instagram toast",
            nowMs = 1000L
        )
        ToastTracker.recordToast(
            packageName = "com.zhiliaoapp.musically",
            className = "android.widget.Toast",
            text = "TikTok toast",
            nowMs = 1000L
        )

        val igToasts = ToastTracker.getRecentToasts("com.instagram.android", nowMs = 1500L)
        assertEquals(1, igToasts.size)
        assertEquals("Instagram toast", igToasts[0].text)

        val ttToasts = ToastTracker.getRecentToasts("com.zhiliaoapp.musically", nowMs = 1500L)
        assertEquals(1, ttToasts.size)
        assertEquals("TikTok toast", ttToasts[0].text)

        val otherToasts = ToastTracker.getRecentToasts("com.unknown.app", nowMs = 1500L)
        assertTrue(otherToasts.isEmpty())
    }
}
