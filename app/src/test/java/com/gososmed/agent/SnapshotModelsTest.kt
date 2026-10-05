package com.gososmed.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SnapshotModelsTest {

    @Test
    fun testBoundsMath() {
        val bounds = SnapshotBounds(left = 100, top = 200, right = 400, bottom = 600)
        assertEquals(300, bounds.width)
        assertEquals(400, bounds.height)
        assertEquals(250, bounds.centerX)
        assertEquals(400, bounds.centerY)
        assertEquals(120000L, bounds.area)
        assertFalse(bounds.isEmpty)
        assertFalse(bounds.isNegative)

        assertTrue(bounds.contains(250, 400))
        assertTrue(bounds.contains(100, 200))
        assertFalse(bounds.contains(400, 600)) // boundary exclusive right/bottom
        assertFalse(bounds.contains(50, 50))

        val inner = SnapshotBounds(left = 150, top = 250, right = 350, bottom = 550)
        assertTrue(bounds.contains(inner))
        assertFalse(inner.contains(bounds))

        val overlapping = SnapshotBounds(left = 300, top = 500, right = 500, bottom = 700)
        assertTrue(bounds.intersects(overlapping))
        assertTrue(overlapping.intersects(bounds))

        val separate = SnapshotBounds(left = 500, top = 700, right = 600, bottom = 800)
        assertFalse(bounds.intersects(separate))

        val emptyBounds = SnapshotBounds(left = 100, top = 100, right = 100, bottom = 200)
        assertTrue(emptyBounds.isEmpty)
        assertEquals(0L, emptyBounds.area)

        val negativeBounds = SnapshotBounds(left = -10, top = 20, right = 50, bottom = 100)
        assertTrue(negativeBounds.isNegative)
    }

    @Test
    fun testBoundsSerialization() {
        val bounds = SnapshotBounds(left = 10, top = 20, right = 30, bottom = 40)
        val json = bounds.toJson()
        assertEquals(10, json.getInt("left"))
        assertEquals(20, json.getInt("top"))
        assertEquals(30, json.getInt("right"))
        assertEquals(40, json.getInt("bottom"))

        val parsed = SnapshotBounds.fromJson(json)
        assertEquals(bounds, parsed)
    }

    @Test
    fun testDisplayAndForegroundSerialization() {
        val display = SnapshotDisplay(id = 0, width = 1080, height = 2400, rotation = 0, densityDpi = 420)
        val dJson = display.toJson()
        val parsedDisplay = SnapshotDisplay.fromJson(dJson)
        assertEquals(display, parsedDisplay)

        val foreground = SnapshotForeground(packageName = "com.facebook.katana", windowId = 15)
        val fJson = foreground.toJson()
        val parsedForeground = SnapshotForeground.fromJson(fJson)
        assertEquals(foreground, parsedForeground)
    }

    @Test
    fun testQualitySerialization() {
        val quality = SnapshotQuality(
            valid = false,
            invalidBoundsRatio = 0.15,
            windowChanged = true,
            reasons = listOf("bounds_outside_display", "window_transition_in_progress")
        )
        val json = quality.toJson()
        val parsed = SnapshotQuality.fromJson(json)
        assertEquals(quality.valid, parsed.valid)
        assertEquals(quality.invalidBoundsRatio, parsed.invalidBoundsRatio, 0.0001)
        assertEquals(quality.windowChanged, parsed.windowChanged)
        assertEquals(quality.reasons, parsed.reasons)
    }

    @Test
    fun testNodeSerialization() {
        val bounds = SnapshotBounds(left = 50, top = 100, right = 200, bottom = 150)
        val node = SnapshotNode(
            nodeId = "hash-12345",
            windowId = 1,
            packageName = "com.instagram.android",
            className = "android.widget.Button",
            resourceId = "com.instagram.android:id/like_button",
            text = "Like",
            contentDescription = "Like button",
            bounds = bounds,
            clickable = true,
            longClickable = false,
            editable = false,
            checkable = true,
            checked = false,
            scrollable = false,
            focusable = true,
            focused = false,
            enabled = true,
            selected = false,
            password = false,
            ancestorPath = "FrameLayout/ViewGroup/Button",
            siblingIndex = 2,
            childCount = 0,
            actionable = true,
            clickableAncestorId = "hash-parent-0"
        )

        val json = node.toJson()
        assertEquals("hash-12345", json.getString("node_id"))
        assertEquals("com.instagram.android:id/like_button", json.getString("resource_id"))
        assertEquals("Like", json.getString("text"))
        assertEquals("Like button", json.getString("content_description"))
        assertTrue(json.getBoolean("clickable"))
        assertTrue(json.getBoolean("actionable"))
        assertEquals("hash-parent-0", json.getString("clickable_ancestor_id"))

        val parsed = SnapshotNode.fromJson(json)
        assertEquals(node, parsed)
    }

    @Test
    fun testSnapshotResultSerialization() {
        val display = SnapshotDisplay(id = 0, width = 1220, height = 2712, rotation = 0, densityDpi = 440)
        val foreground = SnapshotForeground(packageName = "com.facebook.katana", windowId = 12)
        val quality = SnapshotQuality(valid = true, invalidBoundsRatio = 0.0, windowChanged = false)
        val image = SnapshotImage(format = "jpeg", width = 610, height = 1356, quality = 70, binaryMessageId = "img-1842")

        val node1 = SnapshotNode(
            nodeId = "n1",
            windowId = 12,
            packageName = "com.facebook.katana",
            className = "android.widget.TextView",
            text = "Feed",
            bounds = SnapshotBounds(10, 10, 100, 50),
            clickable = false,
            actionable = false
        )
        val node2 = SnapshotNode(
            nodeId = "n2",
            windowId = 12,
            packageName = "com.facebook.katana",
            className = "android.widget.Button",
            text = "Post",
            bounds = SnapshotBounds(100, 10, 200, 50),
            clickable = true,
            actionable = true
        )

        val snapshot = SnapshotResult(
            snapshotId = "01JXYZ1234567890",
            frameSeq = 1842L,
            capturedAtMs = 1718000000000L,
            display = display,
            foreground = foreground,
            treeHash = "sha256:dummyhash",
            screenFingerprint = "fingerprint-feed",
            quality = quality,
            nodes = listOf(node1, node2),
            image = image,
            truncated = false
        )

        val json = snapshot.toJson()
        assertEquals("01JXYZ1234567890", json.getString("snapshot_id"))
        assertEquals(1842L, json.getLong("frame_seq"))
        assertEquals(2, json.getJSONArray("nodes").length())
        assertEquals("img-1842", json.getJSONObject("image").getString("binary_message_id"))

        val parsed = SnapshotResult.fromJson(json)
        assertEquals(snapshot.snapshotId, parsed.snapshotId)
        assertEquals(snapshot.frameSeq, parsed.frameSeq)
        assertEquals(snapshot.capturedAtMs, parsed.capturedAtMs)
        assertEquals(snapshot.display, parsed.display)
        assertEquals(snapshot.foreground, parsed.foreground)
        assertEquals(snapshot.treeHash, parsed.treeHash)
        assertEquals(snapshot.screenFingerprint, parsed.screenFingerprint)
        assertEquals(snapshot.quality.valid, parsed.quality.valid)
        assertEquals(snapshot.nodes.size, parsed.nodes.size)
        assertEquals(snapshot.nodes[0], parsed.nodes[0])
        assertEquals(snapshot.nodes[1], parsed.nodes[1])
        assertEquals(snapshot.image, parsed.image)
        assertEquals(snapshot.truncated, parsed.truncated)
    }
}
