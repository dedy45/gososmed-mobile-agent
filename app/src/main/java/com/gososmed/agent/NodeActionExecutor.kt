package com.gososmed.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.gososmed.agent.privileged.PrivilegedShellHolder
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    // Maestro Parity: Native scroll until element is visible in safe viewport
    const val ACTION_SCROLL_UNTIL_VISIBLE = 0x00000080
    const val ACTION_COPY = 0x00004000
    const val ACTION_PASTE = 0x00008000
    /**
     * Interface for dispatching node actions or gesture fallback taps.
     */
    interface ActionDispatcher {
        fun performNodeAction(nodeId: String, action: Int, args: Map<String, Any>? = null): Boolean
        fun dispatchGestureTap(x: Int, y: Int): Boolean
        fun dispatchGestureSwipe(direction: String = "down", amount: String = "medium"): Boolean = false
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

        override fun dispatchGestureSwipe(direction: String, amount: String): Boolean {
            return service.swipeDirection(direction, amount)
        }
        override fun dispatchGestureTap(x: Int, y: Int): Boolean {
            return service.tap(x, y)
        }
        private fun findNodeById(
            root: AccessibilityNodeInfo,
            targetNodeId: String
        ): AccessibilityNodeInfo? = NodeActionExecutor.findNodeById(root, targetNodeId)
    }

    fun findNodeById(
        root: AccessibilityNodeInfo,
        targetNodeId: String
    ): AccessibilityNodeInfo? {
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
        return searchSubtree(root, targetNodeId, ancestorPath = "", siblingIndex = 0)
    }

    private fun searchSubtree(
        node: AccessibilityNodeInfo,
        targetNodeId: String,
        ancestorPath: String,
        siblingIndex: Int
    ): AccessibilityNodeInfo? {
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

    /**
     * Mengisi clipboard sistem Android dengan [text] pada context utama.
     * Menangani pemanggilan di luar Main Looper secara aman dengan CountDownLatch.
     */
    fun setClipboard(context: Context, text: String): Boolean {
        return try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("gosmed", text))
                    true
                } else false
            } else {
                val latch = CountDownLatch(1)
                var success = false
                Handler(Looper.getMainLooper()).post {
                    try {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("gosmed", text))
                            success = true
                        }
                    } catch (t: Throwable) {
                        android.util.Log.w("GoAgent", "setClipboard error: ${t.message}")
                    } finally {
                        latch.countDown()
                    }
                }
                latch.await(2000, TimeUnit.MILLISECONDS)
                success
            }
        } catch (t: Throwable) {
            android.util.Log.w("GoAgent", "setClipboard failed: ${t.message}")
            false
        }
    }

    /**
     * Membaca teks atau contentDescription dari node target (atau node yang sedang difokuskan),
     * menyalinnya ke Clipboard sistem, dan mengembalikan teks tersebut.
     */
    fun copyText(service: AgentAccessibilityService, targetNodeId: String? = null): Pair<Boolean, String> {
        val root = try {
            service.rootInActiveWindow
        } catch (t: Throwable) {
            null
        } ?: return Pair(false, "")

        val node = if (!targetNodeId.isNullOrBlank()) {
            findNodeById(root, targetNodeId)
        } else {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        } ?: return Pair(false, "")

        val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        val ctx = service.applicationContext
        val copied = setClipboard(ctx, text)
        return Pair(copied, text)
    }

    /**
     * Menempelkan teks clipboard ke node target atau node yang sedang fokus.
     * Fallback ke shell ADB UID 2000: input keyevent 279 (KEYCODE_PASTE).
     */
    fun pasteText(service: AgentAccessibilityService, targetNodeId: String? = null): Boolean {
        val root = try {
            service.rootInActiveWindow
        } catch (t: Throwable) {
            null
        }

        var pasted = false
        if (root != null) {
            val node = if (!targetNodeId.isNullOrBlank()) {
                findNodeById(root, targetNodeId)
            } else {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            }

            if (node != null) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
        }

        if (!pasted) {
            val shell = PrivilegedShellHolder.get()
            if (shell.status().connected) {
                val res = shell.exec("input keyevent 279", 1000L)
                pasted = res.ok
            }
        }

        return pasted
    }

    /**
     * Menghapus teks bertahap:
     * - characters <= 10: input keyevent 67 berulang.
     * - characters > 10: batch input keyevent 67 67 ... atau ACTION_SET_TEXT "" jika fokus input teridentifikasi.
     */
    fun eraseText(service: AgentAccessibilityService, characters: Int = 1): Boolean {
        val count = characters.coerceAtLeast(1)
        val shell = PrivilegedShellHolder.get()

        if (shell.status().connected) {
            return if (count <= 10) {
                var allOk = true
                repeat(count) {
                    val res = shell.exec("input keyevent 67", 500L)
                    if (!res.ok) allOk = false
                }
                allOk
            } else {
                val root = try { service.rootInActiveWindow } catch (_: Throwable) { null }
                val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null && focused.text != null && count >= focused.text.length) {
                    val bundle = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                    }
                    focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                } else {
                    val batchCmd = "input keyevent " + (1..count).joinToString(" ") { "67" }
                    val res = shell.exec(batchCmd, (1000L + count * 50L).coerceAtMost(10_000L))
                    res.ok
                }
            }
        } else {
            val root = try { service.rootInActiveWindow } catch (_: Throwable) { null }
            val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            return if (focused != null) {
                val cur = focused.text?.toString() ?: ""
                val newText = if (count >= cur.length) "" else cur.dropLast(count)
                val bundle = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                }
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            } else {
                true
            }
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
        val expectedTreeHash: String? = null,
        val isSubmitAction: Boolean = false
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

        // Fast-path: Native scrollUntilVisible (Maestro Parity)
        if (request.action == ACTION_SCROLL_UNTIL_VISIBLE) {
            return executeScrollUntilVisible(request, dispatcher, currentSnapshotProvider, timeSource, sleeper)
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
                put("before_snapshot_id", beforeSnapshot.snapshotId)
                put("before_state_ref", beforeSnapshot.snapshotId)
                put("verified", false)
                put("action_performed", false)
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
            // Temuan #0 & §2.9.c: pin failure snapshot in separate storage
            if (transition.afterSnapshot != null) {
                SnapshotRegistry.register(transition.afterSnapshot)
                SnapshotRegistry.pin(transition.afterSnapshot.snapshotId)
            }
            val res = JSONObject().apply {
                put("target_node_id", targetNode.nodeId)
                put("execution_node_id", executionNodeId)
                put("used_gesture_fallback", usedGestureFallback)
                put("before_snapshot_id", beforeSnapshot.snapshotId)
                put("before_state_ref", beforeSnapshot.snapshotId)
                if (transition.afterSnapshot != null) {
                    put("after_snapshot_id", transition.afterSnapshot.snapshotId)
                }
                put("verified", false)
                put("action_performed", true)
            }
            val reason = transition.reasonCode ?: ProtocolV2.ReasonCodes.SCREEN_NOT_CHANGED
            // Temuan #4: submit action on screen_not_changed MUST NOT be retryable to prevent double-submit.
            // Non-submit clicks remain retryable (e.g. temporary lag / tap missed).
            val isRetryable = !request.isSubmitAction && reason == ProtocolV2.ReasonCodes.SCREEN_NOT_CHANGED
            return ProtocolV2.Response.error(
                requestId = request.requestId,
                reasonCode = reason,
                retryable = isRetryable,
                result = res,
                timing = ProtocolV2.Timing.create(startTime, timeSource())
            )
        }

        // 11. Final Success
        // Temuan #0: Register settled after-snapshot so subsequent actions in trajectory can use after_snapshot_id
        if (transition.afterSnapshot != null) {
            SnapshotRegistry.register(transition.afterSnapshot)
        }

        val successResult = JSONObject().apply {
            put("target_node_id", targetNode.nodeId)
            put("execution_node_id", executionNodeId)
            put("used_gesture_fallback", usedGestureFallback)
            put("before_snapshot_id", beforeSnapshot.snapshotId)
            put("before_state_ref", beforeSnapshot.snapshotId)
            put("after_snapshot_id", transition.afterSnapshot?.snapshotId)
            put("screen_fingerprint", transition.afterSnapshot?.screenFingerprint)
            put("verified", true)
            put("action_performed", true)
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

        // Fast-path: Native scrollUntilVisible (Maestro Parity)
        if (request.action == ACTION_SCROLL_UNTIL_VISIBLE) {
            return executeScrollUntilVisibleAsync(request, dispatcher, currentSnapshotProvider, timeSource)
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
                put("before_snapshot_id", beforeSnapshot.snapshotId)
                put("before_state_ref", beforeSnapshot.snapshotId)
                put("verified", false)
                put("action_performed", false)
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
            // Temuan #0 & §2.9.c: pin failure snapshot in separate storage
            if (transition.afterSnapshot != null) {
                SnapshotRegistry.register(transition.afterSnapshot)
                SnapshotRegistry.pin(transition.afterSnapshot.snapshotId)
            }
            val res = JSONObject().apply {
                put("target_node_id", targetNode.nodeId)
                put("execution_node_id", executionNodeId)
                put("used_gesture_fallback", usedGestureFallback)
                put("before_snapshot_id", beforeSnapshot.snapshotId)
                put("before_state_ref", beforeSnapshot.snapshotId)
                if (transition.afterSnapshot != null) {
                    put("after_snapshot_id", transition.afterSnapshot.snapshotId)
                }
                put("verified", false)
                put("action_performed", true)
            }
            val reason = transition.reasonCode ?: ProtocolV2.ReasonCodes.SCREEN_NOT_CHANGED
            // Temuan #4: submit action on screen_not_changed MUST NOT be retryable to prevent double-submit.
            // Non-submit clicks remain retryable (e.g. temporary lag / tap missed).
            val isRetryable = !request.isSubmitAction && reason == ProtocolV2.ReasonCodes.SCREEN_NOT_CHANGED
            return ProtocolV2.Response.error(
                requestId = request.requestId,
                reasonCode = reason,
                retryable = isRetryable,
                result = res,
                timing = ProtocolV2.Timing.create(startTime, timeSource())
            )
        }

        // 11. Final Success
        // Temuan #0: Register settled after-snapshot so subsequent actions in trajectory can use after_snapshot_id
        if (transition.afterSnapshot != null) {
            SnapshotRegistry.register(transition.afterSnapshot)
        }

        val successResult = JSONObject().apply {
            put("target_node_id", targetNode.nodeId)
            put("execution_node_id", executionNodeId)
            put("used_gesture_fallback", usedGestureFallback)
            put("before_snapshot_id", beforeSnapshot.snapshotId)
            put("before_state_ref", beforeSnapshot.snapshotId)
            put("after_snapshot_id", transition.afterSnapshot?.snapshotId)
            put("screen_fingerprint", transition.afterSnapshot?.screenFingerprint)
            put("verified", true)
            put("action_performed", true)
        }

        return ProtocolV2.Response.success(
            requestId = request.requestId,
            result = successResult,
            timing = ProtocolV2.Timing.create(startTime, timeSource())
        )
    }

    private fun executeScrollUntilVisible(
        request: ActionRequest,
        dispatcher: ActionDispatcher,
        currentSnapshotProvider: () -> SnapshotResult,
        timeSource: () -> Long,
        sleeper: (Long) -> Unit
    ): ProtocolV2.Response {
        val startTime = timeSource()
        val maxScrolls = (request.actionArgs?.get("max_scrolls") as? Number)?.toInt() ?: 8
        val direction = (request.actionArgs?.get("direction") as? String) ?: "down"

        for (scrollIndex in 0 until maxScrolls) {
            if (CommandCancellationRegistry.isCancelled(request.requestId)) {
                return ProtocolV2.Response.error(
                    requestId = request.requestId,
                    reasonCode = ProtocolV2.ReasonCodes.CANCELLED,
                    timing = ProtocolV2.Timing.create(startTime, timeSource())
                )
            }

            val snap = currentSnapshotProvider()
            val resolveRes = DeterministicResolver.resolve(
                query = request.selector,
                nodes = snap.nodes,
                displayWidth = snap.display.width,
                displayHeight = snap.display.height
            )

            val node = resolveRes.selectedNode
            if (node != null && !resolveRes.isAmbiguous) {
                val displayH = snap.display.height
                val safeTop = displayH * 0.15
                val safeBottom = displayH * 0.85
                if (node.bounds.top >= safeTop && node.bounds.bottom <= safeBottom) {
                    SnapshotRegistry.register(snap)
                    val res = JSONObject().apply {
                        put("found", true)
                        put("target_node_id", node.nodeId)
                        put("scrolls_performed", scrollIndex)
                        put("after_snapshot_id", snap.snapshotId)
                        put("screen_fingerprint", snap.screenFingerprint)
                        put("verified", true)
                        put("action_performed", true)
                    }
                    return ProtocolV2.Response.success(
                        requestId = request.requestId,
                        result = res,
                        timing = ProtocolV2.Timing.create(startTime, timeSource())
                    )
                }
            }

            dispatcher.dispatchGestureSwipe(direction, "medium")
            sleeper(300L)
        }

        return ProtocolV2.Response.error(
            requestId = request.requestId,
            reasonCode = ProtocolV2.ReasonCodes.TIMEOUT,
            retryable = false,
            result = JSONObject().apply {
                put("found", false)
                put("scrolls_performed", maxScrolls)
            },
            timing = ProtocolV2.Timing.create(startTime, timeSource())
        )
    }

    private suspend fun executeScrollUntilVisibleAsync(
        request: ActionRequest,
        dispatcher: ActionDispatcher,
        currentSnapshotProvider: suspend () -> SnapshotResult,
        timeSource: () -> Long
    ): ProtocolV2.Response {
        val startTime = timeSource()
        val maxScrolls = (request.actionArgs?.get("max_scrolls") as? Number)?.toInt() ?: 8
        val direction = (request.actionArgs?.get("direction") as? String) ?: "down"

        for (scrollIndex in 0 until maxScrolls) {
            if (CommandCancellationRegistry.isCancelled(request.requestId)) {
                return ProtocolV2.Response.error(
                    requestId = request.requestId,
                    reasonCode = ProtocolV2.ReasonCodes.CANCELLED,
                    timing = ProtocolV2.Timing.create(startTime, timeSource())
                )
            }

            val snap = currentSnapshotProvider()
            val resolveRes = DeterministicResolver.resolve(
                query = request.selector,
                nodes = snap.nodes,
                displayWidth = snap.display.width,
                displayHeight = snap.display.height
            )

            val node = resolveRes.selectedNode
            if (node != null && !resolveRes.isAmbiguous) {
                val displayH = snap.display.height
                val safeTop = displayH * 0.15
                val safeBottom = displayH * 0.85
                if (node.bounds.top >= safeTop && node.bounds.bottom <= safeBottom) {
                    SnapshotRegistry.register(snap)
                    val res = JSONObject().apply {
                        put("found", true)
                        put("target_node_id", node.nodeId)
                        put("scrolls_performed", scrollIndex)
                        put("after_snapshot_id", snap.snapshotId)
                        put("screen_fingerprint", snap.screenFingerprint)
                        put("verified", true)
                        put("action_performed", true)
                    }
                    return ProtocolV2.Response.success(
                        requestId = request.requestId,
                        result = res,
                        timing = ProtocolV2.Timing.create(startTime, timeSource())
                    )
                }
            }

            dispatcher.dispatchGestureSwipe(direction, "medium")
            kotlinx.coroutines.delay(300L)
        }

        return ProtocolV2.Response.error(
            requestId = request.requestId,
            reasonCode = ProtocolV2.ReasonCodes.TIMEOUT,
            retryable = false,
            result = JSONObject().apply {
                put("found", false)
                put("scrolls_performed", maxScrolls)
            },
            timing = ProtocolV2.Timing.create(startTime, timeSource())
        )
    }
}
