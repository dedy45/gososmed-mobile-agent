package com.gososmed.agent

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ScreenTransitionVerifierTest {

    private fun createDummySnapshot(
        snapshotId: String,
        treeHash: String,
        packageName: String = "com.example.app",
        nodes: List<SnapshotNode> = emptyList()
    ): SnapshotResult {
        return SnapshotResult(
            snapshotId = snapshotId,
            frameSeq = 1L,
            capturedAtMs = 1000L,
            display = SnapshotDisplay(0, 1080, 2400, 0, 420),
            foreground = SnapshotForeground(packageName, 1),
            treeHash = treeHash,
            screenFingerprint = "fingerprint-$snapshotId",
            quality = SnapshotQuality(valid = true, invalidBoundsRatio = 0.0, windowChanged = false),
            nodes = nodes
        )
    }

    @Test
    fun testScreenNotChangedReturnsScreenNotChanged() {
        val initial = createDummySnapshot("snap-1", "sha256:same-hash")

        // Provider keeps returning the same treeHash
        var virtualTime = 0L
        val result = ScreenTransitionVerifier.verifyTransition(
            beforeSnapshot = initial,
            requireScreenChange = true,
            timeoutMs = 200L,
            pollIntervalMs = 50L,
            timeSource = {
                virtualTime += 50L
                virtualTime
            },
            sleeper = {},
            snapshotProvider = { initial }
        )

        assertFalse(result.isSettled)
        assertEquals(ProtocolV2.ReasonCodes.SCREEN_NOT_CHANGED, result.reasonCode)
    }

    @Test
    fun testUnexpectedPackageReturnsUnexpectedScreen() {
        val initial = createDummySnapshot("snap-1", "sha256:hash-1", packageName = "com.example.app")
        val changed = createDummySnapshot("snap-2", "sha256:hash-2", packageName = "com.other.app")

        val result = ScreenTransitionVerifier.verifyTransition(
            beforeSnapshot = initial,
            expectedPackage = "com.example.app",
            requireScreenChange = true,
            timeoutMs = 1000L,
            pollIntervalMs = 0L,
            timeSource = { 0L },
            sleeper = {},
            snapshotProvider = { changed }
        )

        assertFalse(result.isSettled)
        assertEquals(ProtocolV2.ReasonCodes.UNEXPECTED_SCREEN, result.reasonCode)
    }

    @Test
    fun testPostconditionAnchorFoundSettlesSuccessfully() {
        val initial = createDummySnapshot("snap-1", "sha256:hash-1")
        val anchorNode = SnapshotNode(
            nodeId = "id-home",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.TextView",
            text = "Welcome Home",
            bounds = SnapshotBounds(100, 100, 500, 200),
            clickable = false
        )
        val settled = createDummySnapshot("snap-2", "sha256:hash-2", nodes = listOf(anchorNode))

        var virtualTime = 0L
        var calls = 0
        val result = ScreenTransitionVerifier.verifyTransition(
            beforeSnapshot = initial,
            postconditionQuery = SelectorQuery(text = "Welcome Home"),
            requireScreenChange = true,
            timeoutMs = 500L,
            pollIntervalMs = 50L,
            timeSource = {
                virtualTime += 50L
                virtualTime
            },
            sleeper = {},
            snapshotProvider = {
                calls++
                // Transition arrives on 2nd check and stabilizes
                if (calls >= 2) settled else initial
            }
        )

        assertTrue(result.isSettled)
        assertNull(result.reasonCode)
        assertEquals("snap-2", result.afterSnapshot?.snapshotId)
    }

    @Test
    fun testVerifyTransitionAsyncSettlesOnScreenChange() = runBlocking {
        val initial = createDummySnapshot("snap-1", "sha256:hash-1")
        val after = createDummySnapshot("snap-2", "sha256:hash-2")

        var calls = 0
        val result = ScreenTransitionVerifier.verifyTransitionAsync(
            beforeSnapshot = initial,
            requireScreenChange = true,
            timeoutMs = 500L,
            pollIntervalMs = 10L,
            snapshotProvider = {
                calls++
                after
            }
        )

        assertTrue(result.isSettled)
        assertNull(result.reasonCode)
        assertEquals("snap-2", result.afterSnapshot?.snapshotId)
    }

    @Test
    fun testVerifyTransitionAsyncCancellationReturnsCancelled() = runBlocking {
        val initial = createDummySnapshot("snap-1", "sha256:hash-1")

        val result = ScreenTransitionVerifier.verifyTransitionAsync(
            beforeSnapshot = initial,
            requireScreenChange = true,
            timeoutMs = 500L,
            pollIntervalMs = 10L,
            isCancelled = { true },
            snapshotProvider = { initial }
        )

        assertFalse(result.isSettled)
        assertEquals(ProtocolV2.ReasonCodes.CANCELLED, result.reasonCode)
    }

    @Test
    fun testPollIntervalClamping() {
        assertEquals(50L, ProtocolV2.clampPollInterval(10L))
        assertEquals(50L, ProtocolV2.clampPollInterval(0L))
        assertEquals(100L, ProtocolV2.clampPollInterval(100L))
        assertEquals(200L, ProtocolV2.clampPollInterval(200L))
        assertEquals(200L, ProtocolV2.clampPollInterval(500L))
        assertEquals(200L, ProtocolV2.clampPollInterval(60000L))
    }

    @Test
    fun testCancelBeforeSleepAbortsImmediatelyWithoutSleeping() {
        val initial = createDummySnapshot("snap-1", "sha256:hash-1")
        var sleeperCalled = false
        var cancelTriggered = false

        var virtualTime = 0L
        val result = ScreenTransitionVerifier.verifyTransition(
            beforeSnapshot = initial,
            requireScreenChange = true,
            timeoutMs = 1000L,
            pollIntervalMs = 100L,
            timeSource = {
                virtualTime += 10L
                virtualTime
            },
            isCancelled = {
                cancelTriggered
            },
            sleeper = {
                sleeperCalled = true
            },
            snapshotProvider = {
                // Trigger cancel right after first snapshot capture, before sleep
                cancelTriggered = true
                initial
            }
        )

        assertFalse(result.isSettled)
        assertEquals(ProtocolV2.ReasonCodes.CANCELLED, result.reasonCode)
        assertFalse("Sleeper should NOT be called when cancelled before sleep", sleeperCalled)
    }

    @Test
    fun testWaitForSettleSucceedsWhenStable() = runBlocking {
        val snap = createDummySnapshot("snap-1", "sha256:stable-hash")
        var virtualTime = 0L

        val result = ScreenTransitionVerifier.waitForSettle(
            timeoutMs = 2000L,
            settleMs = 300L,
            pollIntervalMs = 50L,
            timeSource = {
                virtualTime += 100L
                virtualTime
            },
            delayProvider = {},
            snapshotProvider = { snap }
        )

        assertTrue("Screen should settle when treeHash is stable", result.settled)
        assertEquals("sha256:stable-hash", result.treeHash)
        assertNull(result.reason)
    }

    @Test
    fun testWaitForSettleTimesOutWhenContinuouslyChanging() = runBlocking {
        var counter = 0
        var virtualTime = 0L

        val result = ScreenTransitionVerifier.waitForSettle(
            timeoutMs = 500L,
            settleMs = 200L,
            pollIntervalMs = 50L,
            timeSource = {
                virtualTime += 50L
                virtualTime
            },
            delayProvider = {},
            snapshotProvider = {
                counter++
                createDummySnapshot("snap-$counter", "sha256:hash-$counter")
            }
        )

        assertFalse("Screen should NOT settle when continuously changing", result.settled)
        assertEquals("timeout_unsettled", result.reason)
    }

    @Test
    fun testWaitForSettleAbortsWhenCancelled() = runBlocking {
        val snap = createDummySnapshot("snap-1", "sha256:hash-1")
        var cancelTriggered = false

        val result = ScreenTransitionVerifier.waitForSettle(
            timeoutMs = 1000L,
            settleMs = 500L,
            pollIntervalMs = 50L,
            isCancelled = { cancelTriggered },
            delayProvider = {},
            snapshotProvider = {
                cancelTriggered = true
                snap
            }
        )

        assertFalse(result.settled)
        assertEquals(ProtocolV2.ReasonCodes.CANCELLED, result.reason)
    }
}
