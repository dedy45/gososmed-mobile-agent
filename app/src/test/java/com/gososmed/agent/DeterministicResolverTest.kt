package com.gososmed.agent

import org.junit.Assert.*
import org.junit.Test

class DeterministicResolverTest {

    @Test
    fun testExactResourceIdWinsCleanlyWithoutAmbiguity() {
        val node1 = SnapshotNode(
            nodeId = "id-btn-login",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            resourceId = "com.example.app:id/login_button",
            text = "Sign In",
            bounds = SnapshotBounds(100, 100, 300, 150),
            clickable = true,
            actionable = true
        )
        val node2 = SnapshotNode(
            nodeId = "id-btn-cancel",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            resourceId = "com.example.app:id/cancel_button",
            text = "Cancel",
            bounds = SnapshotBounds(100, 200, 300, 250),
            clickable = true,
            actionable = true
        )

        val query = SelectorQuery(resourceId = "login_button")
        val result = DeterministicResolver.resolve(query, listOf(node1, node2))

        assertFalse(result.isAmbiguous)
        assertNull(result.reasonCode)
        assertNotNull(result.selectedNode)
        assertEquals("id-btn-login", result.selectedNode?.nodeId)
        assertEquals(100, result.score)
    }

    @Test
    fun testDuplicateTextTriggersAmbiguousReasonCode() {
        val node1 = SnapshotNode(
            nodeId = "id-follow-user1",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            resourceId = "com.example.app:id/follow_button",
            text = "Follow",
            bounds = SnapshotBounds(100, 100, 300, 150),
            clickable = true,
            actionable = true
        )
        val node2 = SnapshotNode(
            nodeId = "id-follow-user2",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            resourceId = "com.example.app:id/follow_button",
            text = "Follow",
            bounds = SnapshotBounds(100, 200, 300, 250),
            clickable = true,
            actionable = true
        )

        // Querying purely by text="Follow" where both have same resourceId or only text matches
        val query = SelectorQuery(text = "Follow")
        val result = DeterministicResolver.resolve(query, listOf(node1, node2))

        assertTrue("Duplicate identical candidates must trigger ambiguity", result.isAmbiguous)
        assertNull("Selected node must be null when ambiguous", result.selectedNode)
        assertEquals(ProtocolV2.ReasonCodes.AMBIGUOUS, result.reasonCode)
        assertEquals(2, result.candidates.size)
    }

    @Test
    fun testAmbiguityDisambiguatedByZone() {
        val nodeTop = SnapshotNode(
            nodeId = "id-follow-top",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            text = "Follow",
            bounds = SnapshotBounds(100, 200, 300, 300), // centerY = 250 < 1200
            clickable = true,
            actionable = true
        )
        val nodeBottom = SnapshotNode(
            nodeId = "id-follow-bottom",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            text = "Follow",
            bounds = SnapshotBounds(100, 1500, 300, 1600), // centerY = 1550 >= 1200
            clickable = true,
            actionable = true
        )

        // Text matches both (score 80), but relativeZone="top_half" gives +35 to nodeTop
        val query = SelectorQuery(text = "Follow", relativeZone = "top_half")
        val result = DeterministicResolver.resolve(query, listOf(nodeTop, nodeBottom), 1080, 2400)

        assertFalse(result.isAmbiguous)
        assertNotNull(result.selectedNode)
        assertEquals("id-follow-top", result.selectedNode?.nodeId)
        assertEquals(115, result.score) // 80 + 35
    }

    @Test
    fun testNoNodeFoundReturnsNoNodeReasonCode() {
        val node = SnapshotNode(
            nodeId = "id-something",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            text = "Hello",
            bounds = SnapshotBounds(100, 100, 200, 200),
            clickable = true,
            actionable = true
        )

        val query = SelectorQuery(text = "NonExistentText")
        val result = DeterministicResolver.resolve(query, listOf(node))

        assertFalse(result.isAmbiguous)
        assertNull(result.selectedNode)
        assertEquals(ProtocolV2.ReasonCodes.NO_NODE, result.reasonCode)
        assertEquals(0, result.candidates.size)
    }

    @Test
    fun testDirectNodeIdMatchFastPath() {
        val node = SnapshotNode(
            nodeId = "exact-node-id-1234",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            text = "Submit",
            bounds = SnapshotBounds(100, 100, 200, 200),
            clickable = true,
            actionable = true,
            clickableAncestorId = "ancestor-5678"
        )

        val query = SelectorQuery(nodeId = "exact-node-id-1234")
        val result = DeterministicResolver.resolve(query, listOf(node))

        assertFalse(result.isAmbiguous)
        assertNotNull(result.selectedNode)
        assertEquals("exact-node-id-1234", result.selectedNode?.nodeId)
        assertEquals("ancestor-5678", result.clickableAncestorId)
        assertNull(result.reasonCode)
    }

    @Test
    fun testRawCoordinateFallback() {
        val node1 = SnapshotNode(
            nodeId = "id-left-card",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.ImageView",
            bounds = SnapshotBounds(0, 0, 500, 500),
            clickable = true,
            actionable = true
        )
        val node2 = SnapshotNode(
            nodeId = "id-right-card",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.ImageView",
            bounds = SnapshotBounds(500, 0, 1000, 500),
            clickable = true,
            actionable = true
        )

        val query = SelectorQuery(point = Pair(250, 250))
        val result = DeterministicResolver.resolve(query, listOf(node1, node2))

        assertFalse(result.isAmbiguous)
        assertNotNull(result.selectedNode)
        assertEquals("id-left-card", result.selectedNode?.nodeId)
        assertEquals(15, result.score)
    }

    @Test
    fun testWrongPackageRejection() {
        val node1 = SnapshotNode(
            nodeId = "node-threads-1",
            windowId = 1,
            packageName = "com.instagram.barcelona",
            className = "android.widget.Button",
            text = "Post",
            bounds = SnapshotBounds(100, 100, 300, 200),
            clickable = true
        )

        // Target query specifies package "com.instagram.android", but snapshot has only "com.instagram.barcelona"
        val wrongPkgQuery = SelectorQuery(text = "Post", packageName = "com.instagram.android")
        val result = DeterministicResolver.resolve(wrongPkgQuery, listOf(node1))

        assertNull(result.selectedNode)
        assertFalse(result.isAmbiguous)
        assertEquals(ProtocolV2.ReasonCodes.WRONG_PACKAGE, result.reasonCode)

        // If matching package exists among multiple nodes, only the matching package node is selected
        val nodeInstagram = SnapshotNode(
            nodeId = "node-ig-1",
            windowId = 1,
            packageName = "com.instagram.android",
            className = "android.widget.Button",
            text = "Post",
            bounds = SnapshotBounds(100, 100, 300, 200),
            clickable = true
        )
        val successResult = DeterministicResolver.resolve(wrongPkgQuery, listOf(node1, nodeInstagram))
        assertNotNull(successResult.selectedNode)
        assertEquals("node-ig-1", successResult.selectedNode?.nodeId)
        assertNull(successResult.reasonCode)
    }

    @Test
    fun testAmbiguousSelectorRejection() {
        val candidateA = SnapshotNode(
            nodeId = "cand-a",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            text = "Confirm",
            bounds = SnapshotBounds(100, 100, 400, 200),
            clickable = true
        )
        val candidateB = SnapshotNode(
            nodeId = "cand-b",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            text = "Confirm",
            bounds = SnapshotBounds(100, 300, 400, 400),
            clickable = true
        )

        val ambiguousQuery = SelectorQuery(text = "Confirm")
        val result = DeterministicResolver.resolve(ambiguousQuery, listOf(candidateA, candidateB))

        assertTrue("Ambiguous candidates must produce isAmbiguous = true", result.isAmbiguous)
        assertNull("Ambiguous candidates must not select a node", result.selectedNode)
        assertEquals(ProtocolV2.ReasonCodes.AMBIGUOUS, result.reasonCode)
    }
}
