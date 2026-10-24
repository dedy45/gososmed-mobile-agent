package com.gososmed.agent.batch

import com.gososmed.agent.CommandCancellationRegistry
import com.gososmed.agent.ProtocolV2
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchFlowExecutorTest {

    private class MockStepExecutor(
        val stepHandler: (BatchStep) -> StepExecutionResult
    ) : BatchStepExecutor {
        val executedSteps = mutableListOf<String>()

        override suspend fun executeStep(
            step: BatchStep,
            overallRequestId: String,
            deadlineMs: Long
        ): StepExecutionResult {
            executedSteps.add(step.stepId)
            return stepHandler(step)
        }

        override fun captureTelemetry(): DeviceTelemetry = DeviceTelemetry(
            activePackage = "com.test.app",
            elementsCount = 10,
            topSemanticTexts = listOf("OK")
        )
    }

    @Test
    fun `empty steps returns completed with zero count`() = runBlocking {
        val executor = BatchFlowExecutor()
        val req = BatchRequest(flowId = "empty_flow", steps = emptyList())
        val result = executor.execute(req, "req_empty")

        assertEquals("COMPLETED", result.status)
        assertEquals(0, result.stepsCompleted)
        assertEquals(0, result.totalSteps)
        assertTrue(result.trajectories.isEmpty())
    }

    @Test
    fun `sequential batch with 3 steps all succeed`() = runBlocking {
        val emittedReports = mutableListOf<StepTrajectoryReport>()
        val mock = MockStepExecutor { step ->
            StepExecutionResult(
                ok = true,
                telemetry = DeviceTelemetry(activePackage = "com.mock.${step.stepId}", elementsCount = 5)
            )
        }

        val executor = BatchFlowExecutor(
            stepExecutor = mock,
            telemetryEmitter = { emittedReports.add(it) }
        )

        val steps = listOf(
            BatchStep("s1_launch", "startApp", JSONObject().put("package", "com.mock.app")),
            BatchStep("s2_wait", "waitForScreen", JSONObject().put("package", "com.mock.app")),
            BatchStep("s3_click", "actNode", JSONObject().put("action", "click"))
        )
        val req = BatchRequest(flowId = "flow_3_steps", steps = steps)
        val result = executor.execute(req, "req_3_success")

        assertEquals("COMPLETED", result.status)
        assertEquals(3, result.stepsCompleted)
        assertEquals(3, result.totalSteps)
        assertNull(result.failedStepId)
        assertNull(result.reasonCode)

        // Verifikasi semua langkah dieksekusi berurutan
        assertEquals(listOf("s1_launch", "s2_wait", "s3_click"), mock.executedSteps)

        // Verifikasi 3 trajectory events dipancarkan dengan status COMPLETED
        assertEquals(3, emittedReports.size)
        assertEquals("COMPLETED", emittedReports[0].status)
        assertEquals(1, emittedReports[0].stepIndex)
        assertEquals("COMPLETED", emittedReports[1].status)
        assertEquals(2, emittedReports[1].stepIndex)
        assertEquals("COMPLETED", emittedReports[2].status)
        assertEquals(3, emittedReports[2].stepIndex)
    }

    @Test
    fun `fail-fast aborts on first failure and stops subsequent steps`() = runBlocking {
        val emittedReports = mutableListOf<StepTrajectoryReport>()
        val mock = MockStepExecutor { step ->
            when (step.stepId) {
                "s1_ok" -> StepExecutionResult(ok = true)
                "s2_fail" -> StepExecutionResult(
                    ok = false,
                    reasonCode = ProtocolV2.ReasonCodes.NO_NODE,
                    failureMessage = "Target node not found"
                )
                "s3_never" -> StepExecutionResult(ok = true)
                else -> StepExecutionResult(ok = false)
            }
        }

        val executor = BatchFlowExecutor(
            stepExecutor = mock,
            telemetryEmitter = { emittedReports.add(it) }
        )

        val steps = listOf(
            BatchStep("s1_ok", "startApp"),
            BatchStep("s2_fail", "actNode"),
            BatchStep("s3_never", "back")
        )
        val req = BatchRequest(flowId = "flow_fail_fast", steps = steps)
        val result = executor.execute(req, "req_fail_fast")

        assertEquals("FAILED", result.status)
        assertEquals(1, result.stepsCompleted)
        assertEquals(3, result.totalSteps)
        assertEquals("s2_fail", result.failedStepId)
        assertEquals(ProtocolV2.ReasonCodes.NO_NODE, result.reasonCode)

        // S3 TIDAK BOLEH dieksekusi (fail-fast guarantee)
        assertEquals(listOf("s1_ok", "s2_fail"), mock.executedSteps)

        // 2 reports: s1 COMPLETED, s2 FAILED
        assertEquals(2, emittedReports.size)
        assertEquals("COMPLETED", emittedReports[0].status)
        assertEquals("FAILED", emittedReports[1].status)
        assertEquals(ProtocolV2.ReasonCodes.NO_NODE, emittedReports[1].failureDetail?.getString("reason_code"))
    }

    @Test
    fun `disallowed action is rejected upfront without touching step executor`() = runBlocking {
        val emittedReports = mutableListOf<StepTrajectoryReport>()
        val mock = MockStepExecutor { StepExecutionResult(ok = true) }

        val executor = BatchFlowExecutor(
            stepExecutor = mock,
            telemetryEmitter = { emittedReports.add(it) }
        )

        val steps = listOf(
            BatchStep("s1_ok", "startApp"),
            BatchStep("s2_illegal", "rebootDevice"), // Bukan whitelisted action
            BatchStep("s3_ok", "back")
        )
        val req = BatchRequest(flowId = "flow_disallowed", steps = steps)
        val result = executor.execute(req, "req_disallowed")

        assertEquals("FAILED", result.status)
        assertEquals(0, result.stepsCompleted)
        assertEquals(3, result.totalSteps)
        assertEquals(ProtocolV2.ReasonCodes.ACTION_NOT_SUPPORTED, result.reasonCode)

        // Mock executor sama sekali TIDAK PERNAH dipanggil
        assertTrue(mock.executedSteps.isEmpty())
        assertEquals(1, emittedReports.size)
        assertEquals("FAILED", emittedReports[0].status)
    }

    @Test
    fun `cancellation aborts execution gracefully`() = runBlocking {
        val reqId = "req_cancel_test"
        CommandCancellationRegistry.register(reqId, 30_000L)

        val emittedReports = mutableListOf<StepTrajectoryReport>()
        val mock = MockStepExecutor { step ->
            if (step.stepId == "s1") {
                // Trigger cancel saat step 1 selesai
                CommandCancellationRegistry.cancel(reqId)
                StepExecutionResult(ok = true)
            } else {
                StepExecutionResult(ok = true)
            }
        }

        val executor = BatchFlowExecutor(
            stepExecutor = mock,
            telemetryEmitter = { emittedReports.add(it) }
        )

        val steps = listOf(
            BatchStep("s1", "startApp"),
            BatchStep("s2", "back")
        )
        val req = BatchRequest(flowId = "flow_cancel", steps = steps)
        val result = executor.execute(req, reqId)

        assertEquals("CANCELLED", result.status)
        assertEquals(1, result.stepsCompleted)
        assertEquals("s2", result.failedStepId)
        assertEquals(ProtocolV2.ReasonCodes.CANCELLED, result.reasonCode)

        // s2 tidak dieksekusi oleh step executor karena sudah terbatalkan
        assertEquals(listOf("s1"), mock.executedSteps)

        CommandCancellationRegistry.unregister(reqId)
    }
}
