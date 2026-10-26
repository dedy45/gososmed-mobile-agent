package com.gososmed.agent.batch

import android.view.accessibility.AccessibilityNodeInfo
import com.gososmed.agent.AgentAccessibilityService
import com.gososmed.agent.CommandCancellationRegistry
import com.gososmed.agent.NodeActionExecutor
import com.gososmed.agent.DeterministicResolver
import com.gososmed.agent.ToastTracker
import com.gososmed.agent.privileged.PrivilegedShellHolder
import com.gososmed.agent.ProtocolV2
import com.gososmed.agent.SelectorQuery
import com.gososmed.agent.SnapshotCollector
import com.gososmed.agent.SnapshotResult
import com.gososmed.agent.WindowRootCollector
import com.gososmed.agent.MediaStager
import com.gososmed.agent.ScreenTransitionVerifier
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Hasil eksekusi satu langkah dalam Batch.
 */
data class StepExecutionResult(
    val ok: Boolean,
    val reasonCode: String? = null,
    val failureMessage: String? = null,
    val telemetry: DeviceTelemetry? = null
)

/**
 * Interface abstraksi eksekutor per langkah untuk menjamin
 * 100% testabilitas di tingkat unit test JVM tanpa stubs Android unmocked.
 */
interface BatchStepExecutor {
    suspend fun executeStep(
        step: BatchStep,
        overallRequestId: String,
        deadlineMs: Long
    ): StepExecutionResult

    fun captureTelemetry(): DeviceTelemetry?
}

/**
 * Implementasi produksi default berbasis Android Accessibility & Platform Primitives.
 */
class DefaultBatchStepExecutor(
    private val serviceProvider: () -> AgentAccessibilityService? = { AgentAccessibilityService.instance }
) : BatchStepExecutor {

    override fun captureTelemetry(): DeviceTelemetry? {
        val svc = serviceProvider() ?: return null
        return try {
            val root = svc.rootInActiveWindow
            val activePkg = root?.packageName?.toString()
            val semanticTexts = mutableListOf<String>()

            fun traverse(node: AccessibilityNodeInfo?, depth: Int = 0) {
                if (node == null || depth > 8 || semanticTexts.size >= 10) return
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()
                if (!text.isNullOrEmpty() && text !in semanticTexts) {
                    semanticTexts.add(text)
                } else if (!desc.isNullOrEmpty() && desc !in semanticTexts) {
                    semanticTexts.add(desc)
                }
                for (i in 0 until node.childCount) {
                    traverse(node.getChild(i), depth + 1)
                }
            }

            traverse(root)
            val count = semanticTexts.size
            val hashInput = semanticTexts.joinToString("|")
            val hash = if (hashInput.isNotEmpty()) {
                val md = MessageDigest.getInstance("SHA-256")
                md.digest(hashInput.toByteArray(Charsets.UTF_8)).take(4).joinToString("") { "%02x".format(it) }
            } else null

            DeviceTelemetry(
                activePackage = activePkg,
                activeActivity = null,
                elementsCount = count,
                elementsHash = hash,
                topSemanticTexts = semanticTexts.take(5)
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun executeStep(
        step: BatchStep,
        overallRequestId: String,
        deadlineMs: Long
    ): StepExecutionResult {
        val svc = serviceProvider()
            ?: return StepExecutionResult(
                ok = false,
                reasonCode = ProtocolV2.ReasonCodes.DEVICE_BUSY,
                failureMessage = "accessibility_service_not_ready",
                telemetry = null
            )

        val telemetry = captureTelemetry()

        return try {
            when (step.action) {
                "startApp" -> {
                    val pkg = step.params.optString("package", step.params.optString("packageName", ""))
                    if (pkg.isBlank()) {
                        StepExecutionResult(
                            ok = false,
                            reasonCode = ProtocolV2.ReasonCodes.ACTION_REJECTED,
                            failureMessage = "missing package parameter",
                            telemetry = telemetry
                        )
                    } else {
                        svc.startApp(pkg)
                        delay(600L) // Settle window transition
                        StepExecutionResult(ok = true, telemetry = captureTelemetry() ?: telemetry)
                    }
                }

                "sleep" -> {
                    val durationMs = step.params.optLong("duration_ms", step.params.optLong("ms", 1000L))
                        .coerceIn(10L, 30_000L)
                    delay(durationMs)
                    StepExecutionResult(ok = true, telemetry = telemetry)
                }

                "waitForScreen" -> {
                    val targetPkg = step.params.optString("target_package", step.params.optString("package", ""))
                    val timeoutMs = step.timeoutMs
                    val startTime = System.currentTimeMillis()
                    var matched = false

                    while (System.currentTimeMillis() - startTime < timeoutMs) {
                        if (CommandCancellationRegistry.isCancelled(overallRequestId)) break
                        val currentPkg = svc.rootInActiveWindow?.packageName?.toString()
                        if (targetPkg.isBlank() || currentPkg == targetPkg) {
                            matched = true
                            break
                        }
                        delay(ProtocolV2.DEFAULT_POLL_INTERVAL_MS)
                    }

                    if (!matched) {
                        StepExecutionResult(
                            ok = false,
                            reasonCode = ProtocolV2.ReasonCodes.TIMEOUT,
                            failureMessage = "waitForScreen timeout waiting for $targetPkg",
                            telemetry = captureTelemetry() ?: telemetry
                        )
                    } else {
                        StepExecutionResult(ok = true, telemetry = captureTelemetry() ?: telemetry)
                    }
                }

                "waitForNode" -> {
                    val selectorJson = step.params.optJSONObject("selector") ?: step.params
                    val selector = try {
                        SelectorQuery.fromJson(selectorJson)
                    } catch (_: Exception) {
                        return StepExecutionResult(
                            ok = false,
                            reasonCode = ProtocolV2.ReasonCodes.ACTION_REJECTED,
                            failureMessage = "invalid_selector_query",
                            telemetry = telemetry
                        )
                    }

                    val timeoutMs = step.timeoutMs
                    val startTime = System.currentTimeMillis()
                    var found = false
                    val displayProvider = SnapshotCollector.AndroidDisplayInfoProvider(svc, svc)
                    val treeSource = WindowRootCollector.AndroidWindowTreeSource(svc)
                    while (System.currentTimeMillis() - startTime < timeoutMs) {
                        if (CommandCancellationRegistry.isCancelled(overallRequestId)) break
                        val snap = SnapshotCollector.capture(displayProvider, treeSource)
                        val resolveResult = DeterministicResolver.resolve(
                            query = selector,
                            nodes = snap.nodes,
                            displayWidth = snap.display.width,
                            displayHeight = snap.display.height
                        )
                        if (resolveResult.selectedNode != null && !resolveResult.isAmbiguous) {
                            found = true
                            break
                        }
                        val toasts = ToastTracker.getRecentToasts(targetPackage = selector.packageName)
                        for (t in toasts) {
                            val vNode = ToastTracker.toVirtualNode(t)
                            val res = DeterministicResolver.resolve(query = selector, nodes = listOf(vNode))
                            if (res.selectedNode != null) {
                                found = true
                                break
                            }
                        }
                        if (found) break
                        delay(ProtocolV2.DEFAULT_POLL_INTERVAL_MS)
                    }

                    if (!found) {
                        StepExecutionResult(
                            ok = false,
                            reasonCode = ProtocolV2.ReasonCodes.TIMEOUT,
                            failureMessage = "waitForNode timed out",
                            telemetry = captureTelemetry() ?: telemetry
                        )
                    } else {
                        StepExecutionResult(ok = true, telemetry = captureTelemetry() ?: telemetry)
                    }
                }

                "actNode", "actAndVerify" -> {
                    val selectorJson = step.params.optJSONObject("selector") ?: step.params
                    val selector = try {
                        SelectorQuery.fromJson(selectorJson)
                    } catch (_: Exception) {
                        return StepExecutionResult(
                            ok = false,
                            reasonCode = ProtocolV2.ReasonCodes.ACTION_REJECTED,
                            failureMessage = "invalid_selector_query",
                            telemetry = telemetry
                        )
                    }

                    val requireScreenChange = if (step.action == "actAndVerify") true else step.params.optBoolean("require_screen_change", false)
                    val actionCode = when (step.params.optString("action", "click").lowercase()) {
                        "click" -> NodeActionExecutor.ACTION_CLICK
                        "long_click" -> NodeActionExecutor.ACTION_LONG_CLICK
                        "scroll_forward" -> NodeActionExecutor.ACTION_SCROLL_FORWARD
                        "scroll_backward" -> NodeActionExecutor.ACTION_SCROLL_BACKWARD
                        else -> NodeActionExecutor.ACTION_CLICK
                    }

                    val actionReq = NodeActionExecutor.ActionRequest(
                        requestId = overallRequestId,
                        selector = selector,
                        action = actionCode,
                        requireScreenChange = requireScreenChange,
                        timeoutMs = step.timeoutMs,
                        deadlineMs = deadlineMs
                    )

                    val dispatcher = NodeActionExecutor.AndroidActionDispatcher(svc)
                    val displayProvider = SnapshotCollector.AndroidDisplayInfoProvider(svc, svc)
                    val treeSource = WindowRootCollector.AndroidWindowTreeSource(svc)
                    val snapshotProvider: suspend () -> SnapshotResult = {
                        SnapshotCollector.capture(displayProvider, treeSource)
                    }

                    val res = NodeActionExecutor.executeAsync(
                        request = actionReq,
                        dispatcher = dispatcher,
                        currentSnapshotProvider = snapshotProvider
                    )

                    if (res.ok) {
                        StepExecutionResult(ok = true, telemetry = captureTelemetry() ?: telemetry)
                    } else {
                        StepExecutionResult(
                            ok = false,
                            reasonCode = res.reasonCode ?: ProtocolV2.ReasonCodes.ACTION_REJECTED,
                            failureMessage = "action_execution_failed: ${res.reasonCode}",
                            telemetry = captureTelemetry() ?: telemetry
                        )
                    }
                }

                "scrollUntilVisible" -> {
                    val selectorJson = step.params.optJSONObject("selector") ?: step.params
                    val selector = try {
                        SelectorQuery.fromJson(selectorJson)
                    } catch (_: Exception) {
                        return StepExecutionResult(
                            ok = false,
                            reasonCode = ProtocolV2.ReasonCodes.ACTION_REJECTED,
                            failureMessage = "invalid_selector_query",
                            telemetry = telemetry
                        )
                    }

                    val actionReq = NodeActionExecutor.ActionRequest(
                        requestId = overallRequestId,
                        selector = selector,
                        action = NodeActionExecutor.ACTION_SCROLL_UNTIL_VISIBLE,
                        deadlineMs = deadlineMs
                    )

                    val dispatcher = NodeActionExecutor.AndroidActionDispatcher(svc)
                    val displayProvider = SnapshotCollector.AndroidDisplayInfoProvider(svc, svc)
                    val treeSource = WindowRootCollector.AndroidWindowTreeSource(svc)
                    val snapshotProvider: suspend () -> SnapshotResult = {
                        SnapshotCollector.capture(displayProvider, treeSource)
                    }

                    val res = NodeActionExecutor.executeAsync(
                        request = actionReq,
                        dispatcher = dispatcher,
                        currentSnapshotProvider = snapshotProvider
                    )

                    if (res.ok) {
                        StepExecutionResult(ok = true, telemetry = captureTelemetry() ?: telemetry)
                    } else {
                        StepExecutionResult(
                            ok = false,
                            reasonCode = res.reasonCode ?: ProtocolV2.ReasonCodes.NO_NODE,
                            failureMessage = "scrollUntilVisible_failed",
                            telemetry = captureTelemetry() ?: telemetry
                        )
                    }
                }

                "tap", "click" -> {
                    val displayProvider = SnapshotCollector.AndroidDisplayInfoProvider(svc, svc)
                    val display = displayProvider.getDisplayMetrics()
                    val displayWidth = display.width.coerceAtLeast(1)
                    val displayHeight = display.height.coerceAtLeast(1)

                    var targetX = when {
                        step.params.has("x_ratio") -> (displayWidth * step.params.optDouble("x_ratio", 0.5)).toInt()
                        step.params.has("x_percent") -> (displayWidth * (step.params.optDouble("x_percent", 50.0) / 100.0)).toInt()
                        step.params.has("x") -> step.params.optInt("x", displayWidth / 2)
                        else -> -1
                    }
                    var targetY = when {
                        step.params.has("y_ratio") -> (displayHeight * step.params.optDouble("y_ratio", 0.5)).toInt()
                        step.params.has("y_percent") -> (displayHeight * (step.params.optDouble("y_percent", 50.0) / 100.0)).toInt()
                        step.params.has("y") -> step.params.optInt("y", displayHeight / 2)
                        else -> -1
                    }

                    if (targetX < 0 || targetY < 0) {
                        val selectorJson = step.params.optJSONObject("selector")
                        if (selectorJson != null) {
                            try {
                                val selector = SelectorQuery.fromJson(selectorJson)
                                val treeSource = WindowRootCollector.AndroidWindowTreeSource(svc)
                                val snap = SnapshotCollector.capture(displayProvider, treeSource)
                                val res = DeterministicResolver.resolve(selector, snap.nodes, displayWidth, displayHeight)
                                res.selectedNode?.let { node ->
                                    targetX = node.bounds.centerX
                                    targetY = node.bounds.centerY
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    if (targetX < 0) targetX = displayWidth / 2
                    if (targetY < 0) targetY = displayHeight / 2

                    targetX = targetX.coerceIn(0, displayWidth - 1)
                    targetY = targetY.coerceIn(0, displayHeight - 1)

                    val tapOk = svc.tap(targetX, targetY)
                    if (tapOk) {
                        delay(300L)
                        StepExecutionResult(ok = true, telemetry = captureTelemetry() ?: telemetry)
                    } else {
                        StepExecutionResult(
                            ok = false,
                            reasonCode = ProtocolV2.ReasonCodes.GESTURE_CANCELLED,
                            failureMessage = "tap failed at ($targetX, $targetY) on screen ${displayWidth}x${displayHeight}",
                            telemetry = telemetry
                        )
                    }
                }

                "inputText", "setText" -> {
                    val text = step.params.optString("text", "")
                    val ok = svc.setText(text)
                    if (ok) {
                        StepExecutionResult(ok = true, telemetry = captureTelemetry() ?: telemetry)
                    } else {
                        StepExecutionResult(
                            ok = false,
                            reasonCode = ProtocolV2.ReasonCodes.ACTION_REJECTED,
                            failureMessage = "setText_failed",
                            telemetry = telemetry
                        )
                    }
                }

                "replaceText" -> {
                    val text = step.params.optString("text", "")
                    val submit = step.params.optBoolean("submit", false)
                    val ok = svc.setText(text)
                    val shell = PrivilegedShellHolder.get()
                    if (submit) {
                        if (shell.status().connected) {
                            shell.exec("input keyevent 66", 1000L)
                        }
                    } else {
                        if (shell.status().connected) {
                            shell.exec("input keyevent 4", 1000L)
                        }
                    }
                    if (ok) {
                        StepExecutionResult(ok = true, telemetry = captureTelemetry() ?: telemetry)
                    } else {
                        StepExecutionResult(
                            ok = false,
                            reasonCode = ProtocolV2.ReasonCodes.ACTION_REJECTED,
                            failureMessage = "replaceText_failed",
                            telemetry = telemetry
                        )
                    }
                }

                "back" -> {
                    svc.pressBack()
                    delay(300L)
                    StepExecutionResult(ok = true, telemetry = captureTelemetry() ?: telemetry)
                }

                "home" -> {
                    svc.pressHome()
                    delay(300L)
                    StepExecutionResult(ok = true, telemetry = captureTelemetry() ?: telemetry)
                }

                "recents" -> {
                    svc.openRecents()
                    delay(300L)
                    StepExecutionResult(ok = true, telemetry = captureTelemetry() ?: telemetry)
                }

                "pressKey" -> {
                    val key = step.params.optString("key", "").uppercase()
                    val keyCode = step.params.optInt("keycode", -1)
                    val ok = when {
                        key == "BACK" -> { svc.pressBack(); true }
                        key == "HOME" -> { svc.pressHome(); true }
                        key == "RECENTS" -> { svc.openRecents(); true }
                        keyCode > 0 -> {
                            val shell = PrivilegedShellHolder.get()
                            if (shell.status().connected) {
                                shell.exec("input keyevent $keyCode", 1000L)
                                true
                            } else false
                        }
                        else -> false
                    }
                    if (ok) {
                        delay(200L)
                        StepExecutionResult(ok = true, telemetry = captureTelemetry() ?: telemetry)
                    } else {
                        StepExecutionResult(
                            ok = false,
                            reasonCode = ProtocolV2.ReasonCodes.ACTION_NOT_SUPPORTED,
                            failureMessage = "unsupported_key: $key / $keyCode",
                            telemetry = telemetry
                        )
                    }
                }

                "setClipboard" -> {
                    val text = step.params.optString("text", "")
                    val ok = NodeActionExecutor.setClipboard(svc.applicationContext, text)
                    if (ok) {
                        StepExecutionResult(ok = true, telemetry = captureTelemetry() ?: telemetry)
                    } else {
                        StepExecutionResult(
                            ok = false,
                            reasonCode = ProtocolV2.ReasonCodes.ACTION_REJECTED,
                            failureMessage = "setClipboard_failed",
                            telemetry = telemetry
                        )
                    }
                }

                "copyText" -> {
                    val targetNodeId = step.params.optString("node_id", step.params.optJSONObject("target")?.optString("node_id", "") ?: "")
                    val (ok, _) = NodeActionExecutor.copyText(svc, targetNodeId.ifBlank { null })
                    if (ok) {
                        StepExecutionResult(ok = true, telemetry = captureTelemetry() ?: telemetry)
                    } else {
                        StepExecutionResult(
                            ok = false,
                            reasonCode = ProtocolV2.ReasonCodes.NO_NODE,
                            failureMessage = "copyText_failed",
                            telemetry = telemetry
                        )
                    }
                }

                "pasteText" -> {
                    val targetNodeId = step.params.optString("node_id", step.params.optJSONObject("target")?.optString("node_id", "") ?: "")
                    val ok = NodeActionExecutor.pasteText(svc, targetNodeId.ifBlank { null })
                    if (ok) {
                        delay(200L)
                        StepExecutionResult(ok = true, telemetry = captureTelemetry() ?: telemetry)
                    } else {
                        StepExecutionResult(
                            ok = false,
                            reasonCode = ProtocolV2.ReasonCodes.ACTION_REJECTED,
                            failureMessage = "pasteText_failed",
                            telemetry = telemetry
                        )
                    }
                }

                "eraseText" -> {
                    val characters = step.params.optInt("characters", 1).coerceAtLeast(1)
                    val ok = NodeActionExecutor.eraseText(svc, characters)
                    if (ok) {
                        delay(200L)
                        StepExecutionResult(ok = true, telemetry = captureTelemetry() ?: telemetry)
                    } else {
                        StepExecutionResult(
                            ok = false,
                            reasonCode = ProtocolV2.ReasonCodes.ACTION_REJECTED,
                            failureMessage = "eraseText_failed",
                            telemetry = telemetry
                        )
                    }
                }

                "scanMedia", "addMedia" -> {
                    val path = step.params.optString("path", "")
                    val res = MediaStager.scanMedia(svc.applicationContext, path, step.timeoutMs)
                    val ok = res.optBoolean("ok", false)
                    if (ok) {
                        StepExecutionResult(ok = true, telemetry = captureTelemetry() ?: telemetry)
                    } else {
                        StepExecutionResult(
                            ok = false,
                            reasonCode = ProtocolV2.ReasonCodes.TIMEOUT,
                            failureMessage = res.optString("error", "scanMedia_failed"),
                            telemetry = telemetry
                        )
                    }
                }

                "waitForSettle" -> {
                    val timeoutMs = step.timeoutMs
                    val settleMs = step.params.optLong("settle_ms", 500L)
                    val displayProvider = SnapshotCollector.AndroidDisplayInfoProvider(svc, svc)
                    val treeSource = WindowRootCollector.AndroidWindowTreeSource(svc)
                    val res = ScreenTransitionVerifier.waitForSettle(
                        timeoutMs = timeoutMs,
                        settleMs = settleMs,
                        isCancelled = { CommandCancellationRegistry.isCancelled(overallRequestId) },
                        snapshotProvider = { SnapshotCollector.capture(displayProvider, treeSource) }
                    )
                    if (res.settled) {
                        StepExecutionResult(ok = true, telemetry = captureTelemetry() ?: telemetry)
                    } else {
                        StepExecutionResult(
                            ok = false,
                            reasonCode = ProtocolV2.ReasonCodes.TIMEOUT,
                            failureMessage = res.reason ?: "timeout_unsettled",
                            telemetry = captureTelemetry() ?: telemetry
                        )
                    }
                }

                else -> {
                    StepExecutionResult(
                        ok = false,
                        reasonCode = ProtocolV2.ReasonCodes.ACTION_NOT_SUPPORTED,
                        failureMessage = "action '${step.action}' is not supported in batch",
                        telemetry = telemetry
                    )
                }
            }
        } catch (e: Exception) {
            StepExecutionResult(
                ok = false,
                reasonCode = ProtocolV2.ReasonCodes.ACTION_REJECTED,
                failureMessage = e.message ?: "step_execution_exception",
                telemetry = telemetry
            )
        }
    }
}

/**
 * Orkestrator eksekusi batch terstruktur.
 * Mengimplementasikan eksekusi sekuensial, fail-fast, dan pemancaran trajectory.
 */
class BatchFlowExecutor(
    private val stepExecutor: BatchStepExecutor = DefaultBatchStepExecutor(),
    private val telemetryEmitter: (StepTrajectoryReport) -> Unit = {}
) {

    suspend fun execute(
        request: BatchRequest,
        requestId: String,
        deadlineMs: Long = 30_000L
    ): BatchResult {
        val totalSteps = request.steps.size
        if (totalSteps == 0) {
            return BatchResult(
                flowId = request.flowId,
                status = "COMPLETED",
                stepsCompleted = 0,
                totalSteps = 0
            )
        }

        // Pagar Keamanan: Validasi bahwa seluruh aksi masuk dalam daftar izin sebelum mulai eksekusi
        for (step in request.steps) {
            if (!BatchSecurityGuard.isActionAllowed(step.action)) {
                val report = StepTrajectoryReport(
                    flowId = request.flowId,
                    stepId = step.stepId,
                    stepIndex = 1,
                    totalSteps = totalSteps,
                    status = "FAILED",
                    durationMs = 0L,
                    failureDetail = JSONObject().apply {
                        put("reason_code", ProtocolV2.ReasonCodes.ACTION_NOT_SUPPORTED)
                        put("message", "Aksi '${step.action}' tidak diizinkan dalam batch execution")
                    }
                )
                telemetryEmitter(report)
                return BatchResult(
                    flowId = request.flowId,
                    status = "FAILED",
                    stepsCompleted = 0,
                    totalSteps = totalSteps,
                    failedStepId = step.stepId,
                    reasonCode = ProtocolV2.ReasonCodes.ACTION_NOT_SUPPORTED,
                    trajectories = listOf(report)
                )
            }
        }

        val trajectories = mutableListOf<StepTrajectoryReport>()
        var completedCount = 0
        val startTime = System.currentTimeMillis()

        for (index in 0 until totalSteps) {
            val step = request.steps[index]
            val stepIndex = index + 1

            // 1. Periksa Pembatalan (Cancellation Check)
            if (CommandCancellationRegistry.isCancelled(requestId)) {
                val abortReport = StepTrajectoryReport(
                    flowId = request.flowId,
                    stepId = step.stepId,
                    stepIndex = stepIndex,
                    totalSteps = totalSteps,
                    status = "ABORTED",
                    durationMs = 0L,
                    failureDetail = JSONObject().apply {
                        put("reason_code", ProtocolV2.ReasonCodes.CANCELLED)
                        put("message", "Batch dibatalkan oleh klien/server")
                    }
                )
                trajectories.add(abortReport)
                telemetryEmitter(abortReport)
                return BatchResult(
                    flowId = request.flowId,
                    status = "CANCELLED",
                    stepsCompleted = completedCount,
                    totalSteps = totalSteps,
                    failedStepId = step.stepId,
                    reasonCode = ProtocolV2.ReasonCodes.CANCELLED,
                    trajectories = trajectories
                )
            }

            // 2. Periksa Timeout Total Batch
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= request.timeoutTotalMs || (deadlineMs > 0 && elapsed >= deadlineMs)) {
                val timeoutReport = StepTrajectoryReport(
                    flowId = request.flowId,
                    stepId = step.stepId,
                    stepIndex = stepIndex,
                    totalSteps = totalSteps,
                    status = "ABORTED",
                    durationMs = 0L,
                    failureDetail = JSONObject().apply {
                        put("reason_code", ProtocolV2.ReasonCodes.TIMEOUT)
                        put("message", "Batas waktu total batch tercapai")
                    }
                )
                trajectories.add(timeoutReport)
                telemetryEmitter(timeoutReport)
                return BatchResult(
                    flowId = request.flowId,
                    status = "TIMEOUT",
                    stepsCompleted = completedCount,
                    totalSteps = totalSteps,
                    failedStepId = step.stepId,
                    reasonCode = ProtocolV2.ReasonCodes.TIMEOUT,
                    trajectories = trajectories
                )
            }

            // 3. Eksekusi Langkah
            val stepStartTime = System.currentTimeMillis()
            val remainingDeadline = (request.timeoutTotalMs - elapsed).coerceAtLeast(1000L)
            val stepResult = stepExecutor.executeStep(step, requestId, remainingDeadline)
            val stepDuration = System.currentTimeMillis() - stepStartTime

            if (stepResult.ok) {
                completedCount++
                val report = StepTrajectoryReport(
                    flowId = request.flowId,
                    stepId = step.stepId,
                    stepIndex = stepIndex,
                    totalSteps = totalSteps,
                    status = "COMPLETED",
                    durationMs = stepDuration,
                    deviceTelemetry = stepResult.telemetry
                )
                trajectories.add(report)
                telemetryEmitter(report)
            } else {
                // FAIL-FAST: Berhenti segera saat anomali pertama terjadi
                val failReport = StepTrajectoryReport(
                    flowId = request.flowId,
                    stepId = step.stepId,
                    stepIndex = stepIndex,
                    totalSteps = totalSteps,
                    status = "FAILED",
                    durationMs = stepDuration,
                    deviceTelemetry = stepResult.telemetry,
                    failureDetail = JSONObject().apply {
                        put("reason_code", stepResult.reasonCode ?: ProtocolV2.ReasonCodes.BATCH_STEP_FAILED)
                        put("message", stepResult.failureMessage ?: "Langkah batch gagal")
                    }
                )
                trajectories.add(failReport)
                telemetryEmitter(failReport)

                return BatchResult(
                    flowId = request.flowId,
                    status = "FAILED",
                    stepsCompleted = completedCount,
                    totalSteps = totalSteps,
                    failedStepId = step.stepId,
                    reasonCode = stepResult.reasonCode ?: ProtocolV2.ReasonCodes.BATCH_STEP_FAILED,
                    trajectories = trajectories
                )
            }
        }

        return BatchResult(
            flowId = request.flowId,
            status = "COMPLETED",
            stepsCompleted = completedCount,
            totalSteps = totalSteps,
            trajectories = trajectories
        )
    }
}
