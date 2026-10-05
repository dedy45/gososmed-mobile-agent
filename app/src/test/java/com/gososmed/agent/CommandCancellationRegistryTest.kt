package com.gososmed.agent

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CommandCancellationRegistryTest {

    @Before
    fun setup() {
        CommandCancellationRegistry.clear()
    }

    @Test
    fun testRegistrationAndExpiry() {
        val reqId = "req-1"
        CommandCancellationRegistry.register(reqId, deadlineMs = 1000L, currentTimeMs = 5000L)

        assertFalse(CommandCancellationRegistry.isCancelled(reqId))
        assertFalse(CommandCancellationRegistry.isExpired(reqId, currentTimeMs = 5500L))
        assertTrue(CommandCancellationRegistry.isExpired(reqId, currentTimeMs = 6001L))
    }

    @Test
    fun testCancellation() {
        val reqId = "req-cancel"
        CommandCancellationRegistry.register(reqId, deadlineMs = 5000L)

        assertFalse(CommandCancellationRegistry.isCancelled(reqId))
        assertTrue(CommandCancellationRegistry.cancel(reqId))
        assertTrue(CommandCancellationRegistry.isCancelled(reqId))

        // Cancelling non-existent returns false
        assertFalse(CommandCancellationRegistry.cancel("non-existent"))
    }

    @Test
    fun testUnregister() {
        val reqId = "req-unreg"
        CommandCancellationRegistry.register(reqId, deadlineMs = 5000L)
        CommandCancellationRegistry.unregister(reqId)

        assertFalse(CommandCancellationRegistry.isCancelled(reqId))
        assertFalse(CommandCancellationRegistry.isExpired(reqId))
    }

    @Test
    fun testActiveInFlightTargetCancellationReturnsTrue() {
        val targetId = "target-active-req"
        val registered = CommandCancellationRegistry.register(targetId, deadlineMs = 15000L)
        assertTrue(registered)
        assertTrue(CommandCancellationRegistry.isRegistered(targetId))
        assertFalse(CommandCancellationRegistry.isCancelled(targetId))

        // Cancel action targeting the active in-flight request
        val cancelResult = CommandCancellationRegistry.cancel(targetId)
        assertTrue(cancelResult)
        assertTrue(CommandCancellationRegistry.isCancelled(targetId))
    }

    @Test
    fun testNonExistentTargetCancellationReturnsFalse() {
        val unknownTargetId = "target-does-not-exist"
        assertFalse(CommandCancellationRegistry.isRegistered(unknownTargetId))

        val cancelResult = CommandCancellationRegistry.cancel(unknownTargetId)
        assertFalse(cancelResult)
        assertFalse(CommandCancellationRegistry.isCancelled(unknownTargetId))
    }

    @Test
    fun testCancelledCommandHaltsExecutionAndReportsCancelled() {
        val reqId = "req-to-halt"
        CommandCancellationRegistry.register(reqId, deadlineMs = 10000L)

        val dummyNode = SnapshotNode(
            nodeId = "node-1",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            bounds = SnapshotBounds(100, 100, 200, 200),
            clickable = true
        )
        val snapshot = SnapshotResult(
            snapshotId = "snap-1",
            frameSeq = 1L,
            capturedAtMs = 1000L,
            display = SnapshotDisplay(0, 1080, 2400, 0, 420),
            foreground = SnapshotForeground("com.example.app", 1),
            treeHash = "tree-1",
            screenFingerprint = "fp-1",
            quality = SnapshotQuality(valid = true, invalidBoundsRatio = 0.0, windowChanged = false),
            nodes = listOf(dummyNode)
        )

        // Cancel the command before/during execution
        CommandCancellationRegistry.cancel(reqId)

        val actionRequest = NodeActionExecutor.ActionRequest(
            requestId = reqId,
            selector = SelectorQuery(nodeId = "node-1")
        )

        var actionDispatched = false
        val mockDispatcher = object : NodeActionExecutor.ActionDispatcher {
            override fun performNodeAction(nodeId: String, action: Int, args: Map<String, Any>?): Boolean {
                actionDispatched = true
                return true
            }
            override fun dispatchGestureTap(x: Int, y: Int): Boolean {
                actionDispatched = true
                return true
            }
        }

        val response = NodeActionExecutor.execute(
            request = actionRequest,
            dispatcher = mockDispatcher,
            currentSnapshotProvider = { snapshot }
        )

        assertFalse(response.ok)
        assertEquals(ProtocolV2.ReasonCodes.CANCELLED, response.reasonCode)
        assertFalse("Action must not be dispatched when command is cancelled", actionDispatched)
    }
}
