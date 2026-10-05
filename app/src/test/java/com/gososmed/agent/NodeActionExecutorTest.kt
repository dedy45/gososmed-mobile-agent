package com.gososmed.agent

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NodeActionExecutorTest {

    private class MockActionDispatcher : NodeActionExecutor.ActionDispatcher {
        var performActionSuccess = true
        var gestureSuccess = true
        var lastPerformedNodeId: String? = null
        var lastAction: Int? = null
        var lastGestureTap: Pair<Int, Int>? = null

        override fun performNodeAction(nodeId: String, action: Int, args: Map<String, Any>?): Boolean {
            lastPerformedNodeId = nodeId
            lastAction = action
            return performActionSuccess
        }

        override fun dispatchGestureTap(x: Int, y: Int): Boolean {
            lastGestureTap = Pair(x, y)
            return gestureSuccess
        }
    }

    @Before
    fun setup() {
        CommandCancellationRegistry.clear()
    }

    private fun createSnapshot(
        snapshotId: String,
        treeHash: String,
        nodes: List<SnapshotNode>,
        packageName: String = "com.example.app"
    ): SnapshotResult {
        return SnapshotResult(
            snapshotId = snapshotId,
            frameSeq = 1L,
            capturedAtMs = 1000L,
            display = SnapshotDisplay(0, 1080, 2400, 0, 420),
            foreground = SnapshotForeground(packageName, 1),
            treeHash = treeHash,
            screenFingerprint = "fp-$snapshotId",
            quality = SnapshotQuality(valid = true, invalidBoundsRatio = 0.0, windowChanged = false),
            nodes = nodes
        )
    }

    @Test
    fun testCancellationReturnsCancelled() {
        val reqId = "req-cancelled"
        CommandCancellationRegistry.register(reqId, 5000L)
        CommandCancellationRegistry.cancel(reqId)

        val dispatcher = MockActionDispatcher()
        val request = NodeActionExecutor.ActionRequest(
            requestId = reqId,
            selector = SelectorQuery(resourceId = "btn")
        )

        val response = NodeActionExecutor.execute(
            request = request,
            dispatcher = dispatcher,
            currentSnapshotProvider = { createSnapshot("s1", "h1", emptyList()) }
        )

        assertFalse(response.ok)
        assertEquals(ProtocolV2.ReasonCodes.CANCELLED, response.reasonCode)
    }

    @Test
    fun testDeadlineExpiredReturnsTimeout() {
        val reqId = "req-timeout"
        CommandCancellationRegistry.register(reqId, deadlineMs = 100L, currentTimeMs = 1000L)

        val dispatcher = MockActionDispatcher()
        val request = NodeActionExecutor.ActionRequest(
            requestId = reqId,
            selector = SelectorQuery(resourceId = "btn")
        )

        val response = NodeActionExecutor.execute(
            request = request,
            dispatcher = dispatcher,
            currentSnapshotProvider = { createSnapshot("s1", "h1", emptyList()) },
            timeSource = { 1500L } // past deadline
        )

        assertFalse(response.ok)
        assertEquals(ProtocolV2.ReasonCodes.TIMEOUT, response.reasonCode)
    }

    @Test
    fun testAmbiguousSelectorReturnsAmbiguous() {
        val reqId = "req-ambig"
        CommandCancellationRegistry.register(reqId, 5000L)

        val node1 = SnapshotNode(
            nodeId = "n1",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            text = "Click",
            bounds = SnapshotBounds(10, 10, 100, 50),
            clickable = true
        )
        val node2 = SnapshotNode(
            nodeId = "n2",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            text = "Click",
            bounds = SnapshotBounds(10, 60, 100, 100),
            clickable = true
        )

        val dispatcher = MockActionDispatcher()
        val request = NodeActionExecutor.ActionRequest(
            requestId = reqId,
            selector = SelectorQuery(text = "Click")
        )

        val response = NodeActionExecutor.execute(
            request = request,
            dispatcher = dispatcher,
            currentSnapshotProvider = { createSnapshot("s1", "h1", listOf(node1, node2)) }
        )

        assertFalse(response.ok)
        assertEquals(ProtocolV2.ReasonCodes.AMBIGUOUS, response.reasonCode)
    }

    @Test
    fun testClickingChildDelegatesToClickableAncestor() {
        val reqId = "req-ancestor"
        CommandCancellationRegistry.register(reqId, 5000L)

        val childNode = SnapshotNode(
            nodeId = "n-text",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.TextView",
            text = "Clickable Text",
            bounds = SnapshotBounds(10, 10, 100, 50),
            clickable = false,
            clickableAncestorId = "n-parent-button"
        )

        val dispatcher = MockActionDispatcher()
        val request = NodeActionExecutor.ActionRequest(
            requestId = reqId,
            selector = SelectorQuery(text = "Clickable Text"),
            requireScreenChange = false
        )

        val snapshot = createSnapshot("s1", "h1", listOf(childNode))
        val response = NodeActionExecutor.execute(
            request = request,
            dispatcher = dispatcher,
            currentSnapshotProvider = { snapshot },
            sleeper = {}
        )

        assertTrue(response.ok)
        assertEquals("n-parent-button", dispatcher.lastPerformedNodeId)
        assertEquals(NodeActionExecutor.ACTION_CLICK, dispatcher.lastAction)
    }

    @Test
    fun testGestureFallbackWhenNodeActionFails() {
        val reqId = "req-gesture"
        CommandCancellationRegistry.register(reqId, 5000L)

        val node = SnapshotNode(
            nodeId = "n-btn",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            text = "Tap Target",
            bounds = SnapshotBounds(100, 200, 300, 400),
            clickable = true
        )

        val dispatcher = MockActionDispatcher()
        dispatcher.performActionSuccess = false // node action fails!
        dispatcher.gestureSuccess = true

        val request = NodeActionExecutor.ActionRequest(
            requestId = reqId,
            selector = SelectorQuery(text = "Tap Target"),
            allowGestureFallback = true,
            requireScreenChange = false
        )

        val snapshot = createSnapshot("s1", "h1", listOf(node))
        val response = NodeActionExecutor.execute(
            request = request,
            dispatcher = dispatcher,
            currentSnapshotProvider = { snapshot },
            sleeper = {}
        )

        assertTrue(response.ok)
        assertNotNull(dispatcher.lastGestureTap)
        assertEquals(200, dispatcher.lastGestureTap?.first) // centerX
        assertEquals(300, dispatcher.lastGestureTap?.second) // centerY
        assertTrue(response.result.getBoolean("used_gesture_fallback"))
    }

    @Test
    fun testScreenNotChangedReturnsScreenNotChanged() {
        val reqId = "req-no-change"
        CommandCancellationRegistry.register(reqId, 5000L)

        val node = SnapshotNode(
            nodeId = "n-btn",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            text = "No Op",
            bounds = SnapshotBounds(100, 200, 300, 400),
            clickable = true
        )

        val dispatcher = MockActionDispatcher()
        val request = NodeActionExecutor.ActionRequest(
            requestId = reqId,
            selector = SelectorQuery(text = "No Op"),
            requireScreenChange = true,
            timeoutMs = 100L
        )

        var virtualTime = 0L
        val snapshot = createSnapshot("s1", "same-hash", listOf(node))
        val response = NodeActionExecutor.execute(
            request = request,
            dispatcher = dispatcher,
            currentSnapshotProvider = { snapshot },
            timeSource = {
                virtualTime += 40L
                virtualTime
            },
            sleeper = {}
        )

        assertFalse(response.ok)
        assertEquals(ProtocolV2.ReasonCodes.SCREEN_NOT_CHANGED, response.reasonCode)
    }

    @Test
    fun testSuccessfulActionAndPostconditionVerified() {
        val reqId = "req-success-post"
        CommandCancellationRegistry.register(reqId, 5000L)

        val button = SnapshotNode(
            nodeId = "n-submit",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            text = "Submit",
            bounds = SnapshotBounds(100, 100, 300, 200),
            clickable = true
        )
        val successLabel = SnapshotNode(
            nodeId = "n-success-msg",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.TextView",
            text = "Order Placed",
            bounds = SnapshotBounds(100, 100, 400, 200),
            clickable = false
        )

        val initialSnapshot = createSnapshot("s-before", "hash-before", listOf(button))
        val afterSnapshot = createSnapshot("s-after", "hash-after", listOf(successLabel))

        val dispatcher = MockActionDispatcher()
        val request = NodeActionExecutor.ActionRequest(
            requestId = reqId,
            selector = SelectorQuery(text = "Submit"),
            postconditionQuery = SelectorQuery(text = "Order Placed"),
            requireScreenChange = true,
            timeoutMs = 500L
        )

        var calls = 0
        var virtualTime = 0L
        val response = NodeActionExecutor.execute(
            request = request,
            dispatcher = dispatcher,
            currentSnapshotProvider = {
                calls++
                if (calls >= 2) afterSnapshot else initialSnapshot
            },
            timeSource = {
                virtualTime += 50L
                virtualTime
            },
            sleeper = {}
        )

        assertTrue(response.ok)
        assertNull(response.reasonCode)
        assertEquals("s-before", response.result.getString("before_snapshot_id"))
        assertEquals("s-after", response.result.getString("after_snapshot_id"))
    }

    @Test
    fun testTreeHashDivergenceReturnsStaleSnapshot() {
        val reqId = "req-stale-hash"
        val initialSnapshot = createSnapshot(
            snapshotId = "s-current",
            treeHash = "sha256:current-screen-hash",
            nodes = listOf(
                SnapshotNode(
                    nodeId = "node-btn",
                    windowId = 1,
                    packageName = "com.example.app",
                    className = "android.widget.Button",
                    resourceId = "com.example.app:id/submit",
                    bounds = SnapshotBounds(100, 100, 300, 200),
                    clickable = true
                )
            )
        )

        val request = NodeActionExecutor.ActionRequest(
            requestId = reqId,
            selector = SelectorQuery(resourceId = "submit"),
            expectedTreeHash = "sha256:different-snapshot-hash"
        )

        val dispatcher = MockActionDispatcher()
        val response = NodeActionExecutor.execute(
            request = request,
            dispatcher = dispatcher,
            currentSnapshotProvider = { initialSnapshot }
        )

        assertFalse(response.ok)
        assertEquals(ProtocolV2.ReasonCodes.STALE_SNAPSHOT, response.reasonCode)
        assertEquals("sha256:different-snapshot-hash", response.result.getString("expected_tree_hash"))
        assertEquals("sha256:current-screen-hash", response.result.getString("actual_tree_hash"))
    }
}
