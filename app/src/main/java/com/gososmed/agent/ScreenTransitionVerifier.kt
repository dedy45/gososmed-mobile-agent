package com.gososmed.agent

import org.json.JSONObject

/**
 * Screen Transition and Settle Verifier (PLAN-DETERMINISTIC-ANDROID-PORTAL.md Section 10).
 * Verifies that the screen state has settled or changed predictably following an action.
 */
object ScreenTransitionVerifier {

    data class TransitionResult(
        val isSettled: Boolean,
        val reasonCode: String?, // screen_not_changed, unexpected_screen, timeout, or null on success
        val beforeTreeHash: String,
        val afterSnapshot: SnapshotResult?
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("is_settled", isSettled)
            if (reasonCode != null) put("reason_code", reasonCode)
            put("before_tree_hash", beforeTreeHash)
            if (afterSnapshot != null) put("after_snapshot", afterSnapshot.toJson())
        }
    }

    /**
     * Verifies screen transition after an action.
     * Supports configurable polling intervals, time limits, package stability check,
     * tree hash changes, and postcondition anchor queries.
     */
    fun verifyTransition(
        beforeSnapshot: SnapshotResult,
        expectedPackage: String? = null,
        postconditionQuery: SelectorQuery? = null,
        requireScreenChange: Boolean = true,
        timeoutMs: Long = 3000L,
        pollIntervalMs: Long = 50L,
        timeSource: () -> Long = { System.currentTimeMillis() },
        isCancelled: () -> Boolean = { false },
        sleeper: (Long) -> Unit = { Thread.sleep(it) },
        snapshotProvider: () -> SnapshotResult
    ): TransitionResult {
        val startTime = timeSource()
        var lastSnapshot: SnapshotResult? = null
        var lastTreeHash: String? = null
        var consecutiveStableCount = 0

        while (timeSource() - startTime <= timeoutMs) {
            if (isCancelled()) {
                return TransitionResult(
                    isSettled = false,
                    reasonCode = ProtocolV2.ReasonCodes.CANCELLED,
                    beforeTreeHash = beforeSnapshot.treeHash,
                    afterSnapshot = lastSnapshot
                )
            }
            val current = snapshotProvider()
            lastSnapshot = current

            // 1. Validate foreground package if expected
            if (expectedPackage != null && current.foreground.packageName != expectedPackage) {
                return TransitionResult(
                    isSettled = false,
                    reasonCode = ProtocolV2.ReasonCodes.UNEXPECTED_SCREEN,
                    beforeTreeHash = beforeSnapshot.treeHash,
                    afterSnapshot = current
                )
            }

            // 2. Check if tree hash is stable between consecutive checks
            if (lastTreeHash != null && lastTreeHash == current.treeHash) {
                consecutiveStableCount++
            } else {
                consecutiveStableCount = 0
            }
            lastTreeHash = current.treeHash

            // Consider settled if observed 2 times consecutively stable
            val isStable = consecutiveStableCount >= 1

            if (isStable) {
                // If a postcondition query is specified, check if anchor is present
                if (postconditionQuery != null) {
                    val resolveResult = DeterministicResolver.resolve(
                        query = postconditionQuery,
                        nodes = current.nodes,
                        displayWidth = current.display.width,
                        displayHeight = current.display.height
                    )
                    if (resolveResult.selectedNode != null && !resolveResult.isAmbiguous) {
                        return TransitionResult(
                            isSettled = true,
                            reasonCode = null,
                            beforeTreeHash = beforeSnapshot.treeHash,
                            afterSnapshot = current
                        )
                    }
                } else {
                    // No specific postcondition query; check screen change requirement
                    val screenChanged = current.treeHash != beforeSnapshot.treeHash
                    if (requireScreenChange && !screenChanged) {
                        // Still same screen; continue polling until timeout
                    } else {
                        return TransitionResult(
                            isSettled = true,
                            reasonCode = null,
                            beforeTreeHash = beforeSnapshot.treeHash,
                            afterSnapshot = current
                        )
                    }
                }
            }

            if (pollIntervalMs > 0) {
                sleeper(pollIntervalMs)
            }
            if (isCancelled()) {
                return TransitionResult(
                    isSettled = false,
                    reasonCode = ProtocolV2.ReasonCodes.CANCELLED,
                    beforeTreeHash = beforeSnapshot.treeHash,
                    afterSnapshot = lastSnapshot
                )
            }
        }

        // Timeout reached. Determine reason
        val finalSnapshot = lastSnapshot ?: snapshotProvider()
        val screenChanged = finalSnapshot.treeHash != beforeSnapshot.treeHash

        return if (requireScreenChange && !screenChanged) {
            TransitionResult(
                isSettled = false,
                reasonCode = ProtocolV2.ReasonCodes.SCREEN_NOT_CHANGED,
                beforeTreeHash = beforeSnapshot.treeHash,
                afterSnapshot = finalSnapshot
            )
        } else {
            TransitionResult(
                isSettled = false,
                reasonCode = ProtocolV2.ReasonCodes.TIMEOUT,
                beforeTreeHash = beforeSnapshot.treeHash,
                afterSnapshot = finalSnapshot
            )
        }
    }

    /**
     * Coroutine-friendly non-blocking transition verification (P0.3 ANR protection).
     * Uses [kotlinx.coroutines.delay] instead of [Thread.sleep] so the main looper/thread is never blocked.
     */
    suspend fun verifyTransitionAsync(
        beforeSnapshot: SnapshotResult,
        expectedPackage: String? = null,
        postconditionQuery: SelectorQuery? = null,
        requireScreenChange: Boolean = true,
        timeoutMs: Long = 3000L,
        pollIntervalMs: Long = 50L,
        timeSource: () -> Long = { System.currentTimeMillis() },
        isCancelled: () -> Boolean = { false },
        snapshotProvider: suspend () -> SnapshotResult
    ): TransitionResult {
        val startTime = timeSource()
        var lastSnapshot: SnapshotResult? = null
        var lastTreeHash: String? = null
        var consecutiveStableCount = 0

        while (timeSource() - startTime <= timeoutMs) {
            if (isCancelled()) {
                return TransitionResult(
                    isSettled = false,
                    reasonCode = ProtocolV2.ReasonCodes.CANCELLED,
                    beforeTreeHash = beforeSnapshot.treeHash,
                    afterSnapshot = lastSnapshot
                )
            }
            val current = snapshotProvider()
            lastSnapshot = current

            // 1. Validate foreground package if expected
            if (expectedPackage != null && current.foreground.packageName != expectedPackage) {
                return TransitionResult(
                    isSettled = false,
                    reasonCode = ProtocolV2.ReasonCodes.UNEXPECTED_SCREEN,
                    beforeTreeHash = beforeSnapshot.treeHash,
                    afterSnapshot = current
                )
            }

            // 2. Check if tree hash is stable between consecutive checks
            if (lastTreeHash != null && lastTreeHash == current.treeHash) {
                consecutiveStableCount++
            } else {
                consecutiveStableCount = 0
            }
            lastTreeHash = current.treeHash

            // Consider settled if observed 2 times consecutively stable
            val isStable = consecutiveStableCount >= 1

            if (isStable) {
                // If a postcondition query is specified, check if anchor is present
                if (postconditionQuery != null) {
                    val resolveResult = DeterministicResolver.resolve(
                        query = postconditionQuery,
                        nodes = current.nodes,
                        displayWidth = current.display.width,
                        displayHeight = current.display.height
                    )
                    if (resolveResult.selectedNode != null && !resolveResult.isAmbiguous) {
                        return TransitionResult(
                            isSettled = true,
                            reasonCode = null,
                            beforeTreeHash = beforeSnapshot.treeHash,
                            afterSnapshot = current
                        )
                    }
                } else {
                    // No specific postcondition query; check screen change requirement
                    val screenChanged = current.treeHash != beforeSnapshot.treeHash
                    if (requireScreenChange && !screenChanged) {
                        // Still same screen; continue polling until timeout
                    } else {
                        return TransitionResult(
                            isSettled = true,
                            reasonCode = null,
                            beforeTreeHash = beforeSnapshot.treeHash,
                            afterSnapshot = current
                        )
                    }
                }
            }

            if (pollIntervalMs > 0) {
                kotlinx.coroutines.delay(pollIntervalMs)
            }
            if (isCancelled()) {
                return TransitionResult(
                    isSettled = false,
                    reasonCode = ProtocolV2.ReasonCodes.CANCELLED,
                    beforeTreeHash = beforeSnapshot.treeHash,
                    afterSnapshot = lastSnapshot
                )
            }
        }

        // Timeout reached. Determine reason
        val finalSnapshot = lastSnapshot ?: snapshotProvider()
        val screenChanged = finalSnapshot.treeHash != beforeSnapshot.treeHash

        return if (requireScreenChange && !screenChanged) {
            TransitionResult(
                isSettled = false,
                reasonCode = ProtocolV2.ReasonCodes.SCREEN_NOT_CHANGED,
                beforeTreeHash = beforeSnapshot.treeHash,
                afterSnapshot = finalSnapshot
            )
        } else {
            TransitionResult(
                isSettled = false,
                reasonCode = ProtocolV2.ReasonCodes.TIMEOUT,
                beforeTreeHash = beforeSnapshot.treeHash,
                afterSnapshot = finalSnapshot
            )
        }
    }
}
