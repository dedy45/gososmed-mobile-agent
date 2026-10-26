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
        pollIntervalMs: Long = ProtocolV2.DEFAULT_POLL_INTERVAL_MS,
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
            val effectivePollInterval = ProtocolV2.clampPollInterval(pollIntervalMs)
            if (isCancelled()) {
                return TransitionResult(
                    isSettled = false,
                    reasonCode = ProtocolV2.ReasonCodes.CANCELLED,
                    beforeTreeHash = beforeSnapshot.treeHash,
                    afterSnapshot = lastSnapshot
                )
            }
            if (effectivePollInterval > 0) {
                sleeper(effectivePollInterval)
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
        pollIntervalMs: Long = ProtocolV2.DEFAULT_POLL_INTERVAL_MS,
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
            val effectivePollInterval = ProtocolV2.clampPollInterval(pollIntervalMs)
            if (isCancelled()) {
                return TransitionResult(
                    isSettled = false,
                    reasonCode = ProtocolV2.ReasonCodes.CANCELLED,
                    beforeTreeHash = beforeSnapshot.treeHash,
                    afterSnapshot = lastSnapshot
                )
            }
            if (effectivePollInterval > 0) {
                kotlinx.coroutines.delay(effectivePollInterval)
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

    data class SettleResult(
        val settled: Boolean,
        val durationMs: Long,
        val treeHash: String?,
        val reason: String? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("ok", true)
            put("settled", settled)
            put("duration_ms", durationMs)
            if (treeHash != null) put("tree_hash", treeHash)
            if (reason != null) put("reason", reason)
        }
    }

    /**
     * Menunggu hingga pohon UI layar stabil (settle) secara kontinu tanpa animasi perubahan.
     * Jika treeHash tidak berubah selama [settleMs] berturut-turut, kembalikan settled = true.
     * Jika [timeoutMs] terlampaui dan layar masih berubah, kembalikan settled = false ("timeout_unsettled").
     */
    suspend fun waitForSettle(
        timeoutMs: Long = 5000L,
        settleMs: Long = 500L,
        pollIntervalMs: Long = 100L,
        timeSource: () -> Long = { System.currentTimeMillis() },
        isCancelled: () -> Boolean = { false },
        delayProvider: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
        snapshotProvider: suspend () -> SnapshotResult
    ): SettleResult {
        val startTime = timeSource()
        var lastHash: String? = null
        var stableSince = startTime

        while (timeSource() - startTime <= timeoutMs) {
            if (isCancelled()) {
                return SettleResult(
                    settled = false,
                    durationMs = timeSource() - startTime,
                    treeHash = lastHash,
                    reason = ProtocolV2.ReasonCodes.CANCELLED
                )
            }

            val currentSnap = snapshotProvider()
            val currentHash = currentSnap.treeHash

            if (currentHash != lastHash) {
                lastHash = currentHash
                stableSince = timeSource()
            } else {
                if (timeSource() - stableSince >= settleMs) {
                    return SettleResult(
                        settled = true,
                        durationMs = timeSource() - startTime,
                        treeHash = currentHash,
                        reason = null
                    )
                }
            }

            val effectiveInterval = ProtocolV2.clampPollInterval(pollIntervalMs)
            if (effectiveInterval > 0) {
                delayProvider(effectiveInterval)
            }
        }

        return SettleResult(
            settled = false,
            durationMs = timeSource() - startTime,
            treeHash = lastHash,
            reason = "timeout_unsettled"
        )
    }
}
