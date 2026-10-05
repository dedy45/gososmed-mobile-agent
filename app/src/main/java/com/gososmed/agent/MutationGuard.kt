package com.gososmed.agent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Mutation Guard and Rate Limiter (PLAN-DETERMINISTIC-ANDROID-PORTAL.md Section 4 Decisions A13 & A14).
 * Enforces single active mutation per device (DEVICE_BUSY),
 * prevents repeat submission on submit barrier actions (SUBMIT_BARRIER),
 * and enforces minimal rate limit delay between successive actions.
 */
object MutationGuard {

    private const val MIN_MUTATION_INTERVAL_MS = 100L
    const val SUBMIT_TTL_MS = 10 * 60 * 1000L // 10 minutes

    data class AcquireResult(
        val acquired: Boolean,
        val reasonCode: String? = null // device_busy, submit_barrier, or null on success
    )

    private val activeMutationRequestId = AtomicReference<String?>(null)
    private val submittedOperations = ConcurrentHashMap<String, Long>()
    private val lastMutationCompletedAtMs = AtomicLong(0L)

    /**
     * Checks whether an operation or request ID has been submitted within TTL.
     */
    fun isOperationSubmitted(key: String, currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        val submittedAt = submittedOperations[key] ?: return false
        if (currentTimeMs - submittedAt > SUBMIT_TTL_MS) {
            submittedOperations.remove(key)
            return false
        }
        return true
    }
    /**
     * Attempts to acquire exclusive mutation lock.
     */
    fun tryAcquireMutation(
        requestId: String,
        isSubmitAction: Boolean = false,
        operationId: String? = null,
        currentTimeMs: Long = System.currentTimeMillis()
    ): AcquireResult {
        pruneExpiredOperations(currentTimeMs)

        // Submit barrier: cannot repeat a submitted operation or submit action
        if (operationId != null && isOperationSubmitted(operationId, currentTimeMs)) {
            return AcquireResult(acquired = false, reasonCode = ProtocolV2.ReasonCodes.SUBMIT_BARRIER)
        }
        if (isSubmitAction && isOperationSubmitted(requestId, currentTimeMs)) {
            return AcquireResult(acquired = false, reasonCode = ProtocolV2.ReasonCodes.SUBMIT_BARRIER)
        }

        // Single active mutation per device
        val currentInFlight = activeMutationRequestId.get()
        if (currentInFlight != null && currentInFlight != requestId) {
            return AcquireResult(acquired = false, reasonCode = ProtocolV2.ReasonCodes.DEVICE_BUSY)
        }

        // Rate limiter check
        val lastTime = lastMutationCompletedAtMs.get()
        if (lastTime > 0L && currentTimeMs - lastTime < MIN_MUTATION_INTERVAL_MS) {
            return AcquireResult(acquired = false, reasonCode = ProtocolV2.ReasonCodes.DEVICE_BUSY)
        }

        if (activeMutationRequestId.compareAndSet(null, requestId) || activeMutationRequestId.get() == requestId) {
            if (operationId != null) {
                submittedOperations[operationId] = currentTimeMs
            }
            if (isSubmitAction) {
                submittedOperations[requestId] = currentTimeMs
            }
            return AcquireResult(acquired = true, reasonCode = null)
        }

        return AcquireResult(acquired = false, reasonCode = ProtocolV2.ReasonCodes.DEVICE_BUSY)
    }

    fun pruneExpiredOperations(currentTimeMs: Long = System.currentTimeMillis()): Int {
        var pruned = 0
        val it = submittedOperations.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (currentTimeMs - entry.value > SUBMIT_TTL_MS) {
                it.remove()
                pruned++
            }
        }
        return pruned
    }

    fun submittedOperationsCount(): Int = submittedOperations.size

    /**
     * Releases exclusive mutation lock.
     */
    fun releaseMutation(requestId: String, currentTimeMs: Long = System.currentTimeMillis()) {
        activeMutationRequestId.compareAndSet(requestId, null)
        lastMutationCompletedAtMs.set(currentTimeMs)
    }

    /**
     * Resets state for tests or service restarts.
     */
    fun reset() {
        activeMutationRequestId.set(null)
        submittedOperations.clear()
        lastMutationCompletedAtMs.set(0L)
    }
}
