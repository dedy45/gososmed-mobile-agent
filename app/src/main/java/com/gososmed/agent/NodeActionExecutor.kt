package com.gososmed.agent

import org.json.JSONObject

/**
 * Node Action Executor (PLAN-DETERMINISTIC-ANDROID-PORTAL.md Section 9).
 * Implements the 12-step deterministic execution pipeline with pre/post checks,
 * clickable ancestor delegation, gesture fallback policy, and transition verification.
 */
object NodeActionExecutor {

    // Standard AccessibilityNodeInfo action constants mirrored for pure JVM testability
    const val ACTION_CLICK = 0x00000010
    const val ACTION_LONG_CLICK = 0x00000020
    const val ACTION_FOCUS = 0x00000001
    const val ACTION_SCROLL_FORWARD = 0x00001000
    const val ACTION_SCROLL_BACKWARD = 0x00002000

    /**
     * Interface for dispatching node actions or gesture fallback taps.
     */
    interface ActionDispatcher {
        fun performNodeAction(nodeId: String, action: Int, args: Map<String, Any>? = null): Boolean
        fun dispatchGestureTap(x: Int, y: Int): Boolean
    }

    /**
     * Live Android runtime implementation of [ActionDispatcher].
     * Executes [AccessibilityNodeInfo.performAction] or gesture tap via [AgentAccessibilityService].
     */
    class AndroidActionDispatcher(
        private val service: AgentAccessibilityService
    ) : ActionDispatcher {

        override fun performNodeAction(nodeId: String, action: Int, args: Map<String, Any>?): Boolean {
            val root = try {
                service.rootInActiveWindow
            } catch (t: Throwable) {
                android.util.Log.w("GoAgent", "Failed to get rootInActiveWindow: ${t.message}", t)
                null
            } ?: return false

            val targetInfo = findNodeById(root, nodeId) ?: return false

            return if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT) {
                val text = args?.get("text")?.toString() ?: ""
                val bundle = android.os.Bundle()
                bundle.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                targetInfo.performAction(action, bundle)
            } else {
                targetInfo.performAction(action)
            }
        }

        override fun dispatchGestureTap(x: Int, y: Int): Boolean {
            return service.tap(x, y)
        }

        private fun findNodeById(
            root: android.view.accessibility.AccessibilityNodeInfo,
            targetNodeId: String
        ): android.view.accessibility.AccessibilityNodeInfo? {
            // Check root itself
            val simulated = WindowRootCollector.AndroidWindowTreeSource.convertNode(root)
            val rootNodeId = ActionableNodeRegistry.generateNodeId(
                displayId = 0,
                windowId = root.windowId,
                packageName = simulated.packageName,
                resourceId = simulated.resourceId,
                className = simulated.className,
                text = simulated.text,
                contentDescription = simulated.contentDescription,
                ancestorPath = "",
                siblingIndex = 0,
                bounds = simulated.bounds
            )
            if (rootNodeId == targetNodeId) {
                return root
            }

            // Traversal search
            return searchSubtree(root, targetNodeId, ancestorPath = "", siblingIndex = 0)
        }

        private fun searchSubtree(
            node: android.view.accessibility.AccessibilityNodeInfo,
            targetNodeId: String,
            ancestorPath: String,
            siblingIndex: Int
        ): android.view.accessibility.AccessibilityNodeInfo? {
            val count = node.childCount
            val simpleClassName = (node.className?.toString() ?: "").substringAfterLast('.')
            val currentPath = if (ancestorPath.isEmpty()) simpleClassName else "$ancestorPath/$simpleClassName"

            for (i in 0 until count) {
                val child = try {
                    node.getChild(i)
                } catch (t: Throwable) {
                    android.util.Log.w("GoAgent", "Failed to get child $i: ${t.message}", t)
                    null
                } ?: continue

                val simulated = WindowRootCollector.AndroidWindowTreeSource.convertNode(child)
                val childNodeId = ActionableNodeRegistry.generateNodeId(
                    displayId = 0,
                    windowId = child.windowId,
                    packageName = simulated.packageName,
                    resourceId = simulated.resourceId,
                    className = simulated.className,
                    text = simulated.text,
                    contentDescription = simulated.contentDescription,
                    ancestorPath = currentPath,
                    siblingIndex = i,
                    bounds = simulated.bounds
                )

                if (childNodeId == targetNodeId) {
                    return child
                }

                val found = searchSubtree(child, targetNodeId, currentPath, i)
                if (found != null) {
                    return found
                }
            }
            return null
        }
    }

    data class ActionRequest(
        val requestId: String,
        val selector: SelectorQuery,
        val action: Int = ACTION_CLICK,
        val actionArgs: Map<String, Any>? = null,
        val expectedPackage: String? = null,
        val postconditionQuery: SelectorQuery? = null,
        val allowGestureFallback: Boolean = true,
        val requireScreenChange: Boolean = true,
        val timeoutMs: Long = 3000L,
        val deadlineMs: Long = 10000L,
        val expectedTreeHash: String? = null
    )

    /**
     * Executes action following the 12-step deterministic sequence.
     */
    fun execute(
        request: ActionRequest,
        dispatcher: ActionDispatcher,
        currentSnapshotProvider: () -> SnapshotResult,
        timeSource: () -> Long = { System.currentTimeMillis() },
        sleeper: (Long) -> Unit = { Thread.sleep(it) }
    ): ProtocolV2.Response {
        val startTime = timeSource()

        // 1. Validate cancellation and deadline
        if (CommandCancellationRegistry.isCancelled(request.requestId)) {
            return ProtocolV2.Response.error(
                requestId = request.requestId,
                reasonCode = ProtocolV2.ReasonCodes.CANCELLED,
                timing = ProtocolV2.Timing.create(startTime, timeSource())
            )
        }
        if (CommandCancellationRegistry.isExpired(request.requestId, timeSource())) {
            return ProtocolV2.Response.error(
                requestId = request.requestId,
                reasonCode = ProtocolV2.ReasonCodes.TIMEOUT,
                timing = ProtocolV2.Timing.create(startTime, timeSource())
            )
        }

        // 2. Observe initial state (before snapshot)
        val beforeSnapshot = currentSnapshotProvider()


        // 2b. Validate screen tree hash has not diverged from snapshot
        if (request.expectedTreeHash != null && beforeSnapshot.treeHash != request.expectedTreeHash) {
            val res = JSONObject().apply {
                put("expected_tree_hash", request.expectedTreeHash)
                put("actual_tree_hash", beforeSnapshot.treeHash)
            }
            return ProtocolV2.Response.error(
                requestId = request.requestId,
                reasonCode = ProtocolV2.ReasonCodes.STALE_SNAPSHOT,
                result = res,
                timing = ProtocolV2.Timing.create(startTime, timeSource())
            )
        }
        // 3. Precondition check: Package match
        if (request.expectedPackage != null && beforeSnapshot.foreground.packageName != request.expectedPackage) {
            val res = JSONObject().apply {
                put("expected_package", request.expectedPackage)
                put("actual_package", beforeSnapshot.foreground.packageName)
            }
            return ProtocolV2.Response.error(
                requestId = request.requestId,
                reasonCode = ProtocolV2.ReasonCodes.WRONG_PACKAGE,
                result = res,
                timing = ProtocolV2.Timing.create(startTime, timeSource())
            )
        }

        // 4. Resolve node via DeterministicResolver
        val resolveResult = DeterministicResolver.resolve(
            query = request.selector,
            nodes = beforeSnapshot.nodes,
            displayWidth = beforeSnapshot.display.width,
            displayHeight = beforeSnapshot.display.height
        )

        if (resolveResult.reasonCode != null) {
            val res = JSONObject().apply {
                put("resolve_result", resolveResult.toJson())
            }
            return ProtocolV2.Response.error(
                requestId = request.requestId,
                reasonCode = resolveResult.reasonCode,
                result = res,
                timing = ProtocolV2.Timing.create(startTime, timeSource())
            )
        }

        val targetNode = resolveResult.selectedNode ?: return ProtocolV2.Response.error(
            requestId = request.requestId,
            reasonCode = ProtocolV2.ReasonCodes.NO_NODE,
            timing = ProtocolV2.Timing.create(startTime, timeSource())
        )

        // 5. Validate node bounds
        if (targetNode.bounds.isEmpty || targetNode.bounds.isNegative) {
            val res = JSONObject().apply {
                put("node_id", targetNode.nodeId)
                put("bounds", targetNode.bounds.toJson())
            }
            return ProtocolV2.Response.error(
                requestId = request.requestId,
                reasonCode = ProtocolV2.ReasonCodes.INVALID_BOUNDS,
                result = res,
                timing = ProtocolV2.Timing.create(startTime, timeSource())
            )
        }

        // 6. Clickable target identification: node itself or clickable ancestor
        val executionNodeId = if (request.action == ACTION_CLICK && !targetNode.clickable && targetNode.clickableAncestorId != null) {
            targetNode.clickableAncestorId
        } else {
            targetNode.nodeId
        }
        // 6b. Pre-action cancellation check
        if (CommandCancellationRegistry.isCancelled(request.requestId)) {
            return ProtocolV2.Response.error(
                requestId = request.requestId,
                reasonCode = ProtocolV2.ReasonCodes.CANCELLED,
                timing = ProtocolV2.Timing.create(startTime, timeSource())
            )
        }


        // 7. Perform Action: primary via performNodeAction
        var actionSuccess = dispatcher.performNodeAction(executionNodeId, request.action, request.actionArgs)
        var usedGestureFallback = false

        // 8. Fallback to gesture if node action failed
        if (!actionSuccess && request.allowGestureFallback && request.action == ACTION_CLICK) {
            val tapX = targetNode.bounds.centerX
            val tapY = targetNode.bounds.centerY
            if (targetNode.bounds.contains(tapX, tapY)) {
                actionSuccess = dispatcher.dispatchGestureTap(tapX, tapY)
                usedGestureFallback = true
            }
        }

        if (!actionSuccess) {
            val res = JSONObject().apply {
                put("target_node_id", targetNode.nodeId)
                put("execution_node_id", executionNodeId)
                put("action", request.action)
                put("used_gesture_fallback", usedGestureFallback)
            }
            return ProtocolV2.Response.error(
                requestId = request.requestId,
                reasonCode = ProtocolV2.ReasonCodes.ACTION_REJECTED,
                result = res,
                timing = ProtocolV2.Timing.create(startTime, timeSource())
            )
        }

        // 9. Mid-flight cancellation check
        if (CommandCancellationRegistry.isCancelled(request.requestId)) {
            return ProtocolV2.Response.error(
                requestId = request.requestId,
                reasonCode = ProtocolV2.ReasonCodes.CANCELLED,
                timing = ProtocolV2.Timing.create(startTime, timeSource())
            )
        }

        // 10. Post-action transition verification
        val transition = ScreenTransitionVerifier.verifyTransition(
            beforeSnapshot = beforeSnapshot,
            expectedPackage = request.expectedPackage,
            postconditionQuery = request.postconditionQuery,
            requireScreenChange = request.requireScreenChange,
            timeoutMs = request.timeoutMs,
            timeSource = timeSource,
            isCancelled = { CommandCancellationRegistry.isCancelled(request.requestId) },
            sleeper = sleeper,
            snapshotProvider = currentSnapshotProvider
        )

        if (!transition.isSettled) {
            val res = JSONObject().apply {
                put("target_node_id", targetNode.nodeId)
                put("execution_node_id", executionNodeId)
                put("used_gesture_fallback", usedGestureFallback)
                put("before_snapshot_id", beforeSnapshot.snapshotId)
                if (transition.afterSnapshot != null) {
                    put("after_snapshot_id", transition.afterSnapshot.snapshotId)
                }
            }
            return ProtocolV2.Response.error(
                requestId = request.requestId,
                reasonCode = transition.reasonCode ?: ProtocolV2.ReasonCodes.SCREEN_NOT_CHANGED,
                result = res,
                timing = ProtocolV2.Timing.create(startTime, timeSource())
            )
        }

        // 11. Final Success
        val successResult = JSONObject().apply {
            put("target_node_id", targetNode.nodeId)
            put("execution_node_id", executionNodeId)
            put("used_gesture_fallback", usedGestureFallback)
            put("before_snapshot_id", beforeSnapshot.snapshotId)
            put("after_snapshot_id", transition.afterSnapshot?.snapshotId)
            put("screen_fingerprint", transition.afterSnapshot?.screenFingerprint)
        }

        return ProtocolV2.Response.success(
            requestId = request.requestId,
            result = successResult,
            timing = ProtocolV2.Timing.create(startTime, timeSource())
        )
    }

    /**
     * Coroutine-friendly non-blocking action execution (P0.3 ANR protection).
     * Uses coroutine delays during transition verification.
     */
    suspend fun executeAsync(
        request: ActionRequest,
        dispatcher: ActionDispatcher,
        currentSnapshotProvider: suspend () -> SnapshotResult,
        timeSource: () -> Long = { System.currentTimeMillis() }
    ): ProtocolV2.Response {
        val startTime = timeSource()

        // 1. Validate cancellation and deadline
        if (CommandCancellationRegistry.isCancelled(request.requestId)) {
            return ProtocolV2.Response.error(
                requestId = request.requestId,
                reasonCode = ProtocolV2.ReasonCodes.CANCELLED,
                timing = ProtocolV2.Timing.create(startTime, timeSource())
            )
        }
        if (CommandCancellationRegistry.isExpired(request.requestId, timeSource())) {
            return ProtocolV2.Response.error(
                requestId = request.requestId,
                reasonCode = ProtocolV2.ReasonCodes.TIMEOUT,
                timing = ProtocolV2.Timing.create(startTime, timeSource())
            )
        }

        // 2. Observe initial state (before snapshot)
        val beforeSnapshot = currentSnapshotProvider()


        // 2b. Validate screen tree hash has not diverged from snapshot
        if (request.expectedTreeHash != null && beforeSnapshot.treeHash != request.expectedTreeHash) {
            val res = JSONObject().apply {
                put("expected_tree_hash", request.expectedTreeHash)
                put("actual_tree_hash", beforeSnapshot.treeHash)
            }
            return ProtocolV2.Response.error(
                requestId = request.requestId,
                reasonCode = ProtocolV2.ReasonCodes.STALE_SNAPSHOT,
                result = res,
                timing = ProtocolV2.Timing.create(startTime, timeSource())
            )
        }
        // 3. Precondition check: Package match
        if (request.expectedPackage != null && beforeSnapshot.foreground.packageName != request.expectedPackage) {
            val res = JSONObject().apply {
                put("expected_package", request.expectedPackage)
                put("actual_package", beforeSnapshot.foreground.packageName)
            }
            return ProtocolV2.Response.error(
                requestId = request.requestId,
                reasonCode = ProtocolV2.ReasonCodes.WRONG_PACKAGE,
                result = res,
                timing = ProtocolV2.Timing.create(startTime, timeSource())
            )
        }

        // 4. Resolve node via DeterministicResolver
        val resolveResult = DeterministicResolver.resolve(
            query = request.selector,
            nodes = beforeSnapshot.nodes,
            displayWidth = beforeSnapshot.display.width,
            displayHeight = beforeSnapshot.display.height
        )

        if (resolveResult.reasonCode != null) {
            val res = JSONObject().apply {
                put("resolve_result", resolveResult.toJson())
            }
            return ProtocolV2.Response.error(
                requestId = request.requestId,
                reasonCode = resolveResult.reasonCode,
                result = res,
                timing = ProtocolV2.Timing.create(startTime, timeSource())
            )
        }

        val targetNode = resolveResult.selectedNode ?: return ProtocolV2.Response.error(
            requestId = request.requestId,
            reasonCode = ProtocolV2.ReasonCodes.NO_NODE,
            timing = ProtocolV2.Timing.create(startTime, timeSource())
        )

        // 5. Validate node bounds
        if (targetNode.bounds.isEmpty || targetNode.bounds.isNegative) {
            val res = JSONObject().apply {
                put("node_id", targetNode.nodeId)
                put("bounds", targetNode.bounds.toJson())
            }
            return ProtocolV2.Response.error(
                requestId = request.requestId,
                reasonCode = ProtocolV2.ReasonCodes.INVALID_BOUNDS,
                result = res,
                timing = ProtocolV2.Timing.create(startTime, timeSource())
            )
        }

        // 6. Clickable target identification: node itself or clickable ancestor
        val executionNodeId = if (request.action == ACTION_CLICK && !targetNode.clickable && targetNode.clickableAncestorId != null) {
            targetNode.clickableAncestorId
        } else {
            targetNode.nodeId
        }
        // 6b. Pre-action cancellation check
        if (CommandCancellationRegistry.isCancelled(request.requestId)) {
            return ProtocolV2.Response.error(
                requestId = request.requestId,
                reasonCode = ProtocolV2.ReasonCodes.CANCELLED,
                timing = ProtocolV2.Timing.create(startTime, timeSource())
            )
        }


        // 7. Perform Action: primary via performNodeAction
        var actionSuccess = dispatcher.performNodeAction(executionNodeId, request.action, request.actionArgs)
        var usedGestureFallback = false

        // 8. Fallback to gesture if node action failed
        if (!actionSuccess && request.allowGestureFallback && request.action == ACTION_CLICK) {
            val tapX = targetNode.bounds.centerX
            val tapY = targetNode.bounds.centerY
            if (targetNode.bounds.contains(tapX, tapY)) {
                actionSuccess = dispatcher.dispatchGestureTap(tapX, tapY)
                usedGestureFallback = true
            }
        }

        if (!actionSuccess) {
            val res = JSONObject().apply {
                put("target_node_id", targetNode.nodeId)
                put("execution_node_id", executionNodeId)
                put("action", request.action)
                put("used_gesture_fallback", usedGestureFallback)
            }
            return ProtocolV2.Response.error(
                requestId = request.requestId,
                reasonCode = ProtocolV2.ReasonCodes.ACTION_REJECTED,
                result = res,
                timing = ProtocolV2.Timing.create(startTime, timeSource())
            )
        }

        // 9. Mid-flight cancellation check
        if (CommandCancellationRegistry.isCancelled(request.requestId)) {
            return ProtocolV2.Response.error(
                requestId = request.requestId,
                reasonCode = ProtocolV2.ReasonCodes.CANCELLED,
                timing = ProtocolV2.Timing.create(startTime, timeSource())
            )
        }

        // 10. Post-action transition verification via coroutine
        val transition = ScreenTransitionVerifier.verifyTransitionAsync(
            beforeSnapshot = beforeSnapshot,
            expectedPackage = request.expectedPackage,
            postconditionQuery = request.postconditionQuery,
            requireScreenChange = request.requireScreenChange,
            timeoutMs = request.timeoutMs,
            timeSource = timeSource,
            isCancelled = { CommandCancellationRegistry.isCancelled(request.requestId) },
            snapshotProvider = currentSnapshotProvider
        )

        if (!transition.isSettled) {
            val res = JSONObject().apply {
                put("target_node_id", targetNode.nodeId)
                put("execution_node_id", executionNodeId)
                put("used_gesture_fallback", usedGestureFallback)
                put("before_snapshot_id", beforeSnapshot.snapshotId)
                if (transition.afterSnapshot != null) {
                    put("after_snapshot_id", transition.afterSnapshot.snapshotId)
                }
            }
            return ProtocolV2.Response.error(
                requestId = request.requestId,
                reasonCode = transition.reasonCode ?: ProtocolV2.ReasonCodes.SCREEN_NOT_CHANGED,
                result = res,
                timing = ProtocolV2.Timing.create(startTime, timeSource())
            )
        }

        // 11. Final Success
        val successResult = JSONObject().apply {
            put("target_node_id", targetNode.nodeId)
            put("execution_node_id", executionNodeId)
            put("used_gesture_fallback", usedGestureFallback)
            put("before_snapshot_id", beforeSnapshot.snapshotId)
            put("after_snapshot_id", transition.afterSnapshot?.snapshotId)
            put("screen_fingerprint", transition.afterSnapshot?.screenFingerprint)
        }

        return ProtocolV2.Response.success(
            requestId = request.requestId,
            result = successResult,
            timing = ProtocolV2.Timing.create(startTime, timeSource())
        )
    }
}
