package com.gososmed.agent

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MutationGuardTest {

    @Before
    fun setup() {
        MutationGuard.reset()
    }

    @Test
    fun testSingleMutationLockRejectsConcurrentMutationsWithDeviceBusy() {
        val res1 = MutationGuard.tryAcquireMutation("req-1", isSubmitAction = false, currentTimeMs = 1000L)
        assertTrue(res1.acquired)
        assertNull(res1.reasonCode)

        // Concurrent request from another command
        val res2 = MutationGuard.tryAcquireMutation("req-2", isSubmitAction = false, currentTimeMs = 1005L)
        assertFalse(res2.acquired)
        assertEquals(ProtocolV2.ReasonCodes.DEVICE_BUSY, res2.reasonCode)

        // Release first request
        MutationGuard.releaseMutation("req-1", currentTimeMs = 1100L)

        // Now req-2 can acquire after interval
        val res3 = MutationGuard.tryAcquireMutation("req-2", isSubmitAction = false, currentTimeMs = 1250L)
        assertTrue(res3.acquired)
        assertNull(res3.reasonCode)
        MutationGuard.releaseMutation("req-2", currentTimeMs = 1300L)
    }

    @Test
    fun testSubmitBarrierBlocksRepeatedSubmission() {
        val reqId = "submit-order-123"

        // First submit acquire succeeds
        val res1 = MutationGuard.tryAcquireMutation(reqId, isSubmitAction = true, currentTimeMs = 1000L)
        assertTrue(res1.acquired)
        MutationGuard.releaseMutation(reqId, currentTimeMs = 1050L)

        // Attempting to acquire the same submit action again must be blocked by SUBMIT_BARRIER
        val res2 = MutationGuard.tryAcquireMutation(reqId, isSubmitAction = true, currentTimeMs = 1500L)
        assertFalse(res2.acquired)
        assertEquals(ProtocolV2.ReasonCodes.SUBMIT_BARRIER, res2.reasonCode)
    }

    @Test
    fun testOperationIdBlocksRetriedRequestWithDifferentRequestId() {
        val opId = "op-transfer-99"

        // First submission with req-1 and operationId succeeds
        val res1 = MutationGuard.tryAcquireMutation(
            requestId = "req-1",
            isSubmitAction = true,
            operationId = opId,
            currentTimeMs = 1000L
        )
        assertTrue(res1.acquired)
        MutationGuard.releaseMutation("req-1", currentTimeMs = 1050L)

        // Client retries with new requestId "req-2" but the same operationId
        val res2 = MutationGuard.tryAcquireMutation(
            requestId = "req-2",
            isSubmitAction = false,
            operationId = opId,
            currentTimeMs = 1200L
        )
        assertFalse(res2.acquired)
        assertEquals(ProtocolV2.ReasonCodes.SUBMIT_BARRIER, res2.reasonCode)
    }

    @Test
    fun testSubmitBarrierExpiresAfterTtl() {
        val opId = "op-expiring-1"

        // First submission at t = 1000L
        val res1 = MutationGuard.tryAcquireMutation(
            requestId = "req-1",
            isSubmitAction = true,
            operationId = opId,
            currentTimeMs = 1000L
        )
        assertTrue(res1.acquired)
        MutationGuard.releaseMutation("req-1", currentTimeMs = 1050L)

        // Blocked at t = 5 minutes (300,000ms)
        val res2 = MutationGuard.tryAcquireMutation(
            requestId = "req-2",
            isSubmitAction = true,
            operationId = opId,
            currentTimeMs = 1000L + 300_000L
        )
        assertFalse(res2.acquired)
        assertEquals(ProtocolV2.ReasonCodes.SUBMIT_BARRIER, res2.reasonCode)

        // Allowed after TTL (> 10 minutes = 600,000ms, e.g. at t = 601,050ms)
        val res3 = MutationGuard.tryAcquireMutation(
            requestId = "req-3",
            isSubmitAction = true,
            operationId = opId,
            currentTimeMs = 1000L + 601_000L
        )
        assertTrue(res3.acquired)
        assertNull(res3.reasonCode)
        MutationGuard.releaseMutation("req-3", currentTimeMs = 1000L + 601_050L)
    }

    @Test
    fun testRateLimitingEnforcesMinimalInterval() {
        val res1 = MutationGuard.tryAcquireMutation("req-1", currentTimeMs = 1000L)
        assertTrue(res1.acquired)
        MutationGuard.releaseMutation("req-1", currentTimeMs = 1050L)

        // Attempting immediate next mutation 20ms later (< 100ms interval)
        val res2 = MutationGuard.tryAcquireMutation("req-2", currentTimeMs = 1070L)
        assertFalse(res2.acquired)
        assertEquals(ProtocolV2.ReasonCodes.DEVICE_BUSY, res2.reasonCode)

        // Attempting after 120ms (> 100ms interval) succeeds
        val res3 = MutationGuard.tryAcquireMutation("req-2", currentTimeMs = 1200L)
        assertTrue(res3.acquired)
        MutationGuard.releaseMutation("req-2", currentTimeMs = 1250L)
    }

    @Test
    fun testPruningOfExpiredOperationsAfterTtl() {
        val op1 = "op-prune-1"
        val op2 = "op-prune-2"
        MutationGuard.tryAcquireMutation(requestId = "req-p1", isSubmitAction = true, operationId = op1, currentTimeMs = 1000L)
        MutationGuard.releaseMutation("req-p1", currentTimeMs = 1050L)

        MutationGuard.tryAcquireMutation(requestId = "req-p2", isSubmitAction = true, operationId = op2, currentTimeMs = 2000L)
        MutationGuard.releaseMutation("req-p2", currentTimeMs = 2050L)

        assertTrue(MutationGuard.submittedOperationsCount() >= 2)

        // After TTL (> 10 minutes = 600,000ms), prune removes all expired items
        val pruned = MutationGuard.pruneExpiredOperations(currentTimeMs = 1000L + MutationGuard.SUBMIT_TTL_MS + 5000L)
        assertTrue(pruned >= 2)
        assertEquals(0, MutationGuard.submittedOperationsCount())
    }
}
