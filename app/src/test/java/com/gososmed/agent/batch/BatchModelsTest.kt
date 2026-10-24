package com.gososmed.agent.batch

import com.gososmed.agent.ProtocolV2
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchModelsTest {

    @Test
    fun `BatchSecurityGuard enforces whitelisted actions only`() {
        val safeActions = listOf(
            "startApp", "waitForScreen", "waitForNode", "actNode",
            "actAndVerify", "scrollUntilVisible", "inputText", "setText",
            "replaceText", "pressKey", "back", "home", "recents", "sleep"
        )
        for (action in safeActions) {
            assertTrue("Aksi aman '$action' harus diizinkan", BatchSecurityGuard.isActionAllowed(action))
        }

        val dangerousActions = listOf(
            "reboot", "rm", "dd", "eval", "sh", "exec", "shell", "format",
            "installPackage", "customScript", "wipe"
        )
        for (action in dangerousActions) {
            assertFalse("Aksi berbahaya '$action' harus ditolak", BatchSecurityGuard.isActionAllowed(action))
        }
    }

    @Test
    fun `BatchStep parsing extracts fields with defaults`() {
        val json = JSONObject().apply {
            put("step_id", "s1_click")
            put("action", "actNode")
            put("params", JSONObject().put("action", "click"))
            put("timeout_ms", 5000L)
        }

        val step = BatchStep.fromJson(json)
        assertEquals("s1_click", step.stepId)
        assertEquals("actNode", step.action)
        assertEquals("click", step.params.getString("action"))
        assertEquals(5000L, step.timeoutMs)

        val serialized = step.toJson()
        assertEquals("s1_click", serialized.getString("step_id"))
        assertEquals("actNode", serialized.getString("action"))
        assertEquals(5000L, serialized.getLong("timeout_ms"))
    }

    @Test
    fun `BatchRequest parses steps from args or root with fallback`() {
        // Form 1: inside args
        val reqInArgs = JSONObject().apply {
            put("protocol_version", 2)
            put("request_id", "req_123")
            put("cmd", ProtocolV2.Commands.EXECUTE_BATCH)
            put("args", JSONObject().apply {
                put("flow_id", "flow_test_01")
                put("timeout_total_ms", 25000L)
                put("steps", JSONArray().apply {
                    put(JSONObject().apply {
                        put("step_id", "s1")
                        put("action", "startApp")
                        put("params", JSONObject().put("package", "com.example.app"))
                    })
                })
            })
        }

        val parsed1 = BatchRequest.fromJson(reqInArgs.getJSONObject("args"), reqInArgs)
        assertEquals("flow_test_01", parsed1.flowId)
        assertEquals(25000L, parsed1.timeoutTotalMs)
        assertEquals(1, parsed1.steps.size)
        assertEquals("s1", parsed1.steps[0].stepId)
        assertEquals("startApp", parsed1.steps[0].action)

        // Form 2: at root level
        val reqAtRoot = JSONObject().apply {
            put("flow_id", "flow_root_02")
            put("timeout_total_ms", 15000L)
            put("steps", JSONArray().apply {
                put(JSONObject().apply {
                    put("step_id", "s2")
                    put("action", "waitForNode")
                })
            })
        }

        val parsed2 = BatchRequest.fromJson(reqAtRoot, reqAtRoot)
        assertEquals("flow_root_02", parsed2.flowId)
        assertEquals(15000L, parsed2.timeoutTotalMs)
        assertEquals(1, parsed2.steps.size)
        assertEquals("s2", parsed2.steps[0].stepId)
    }

    @Test
    fun `StepTrajectoryReport serializes complete telemetry`() {
        val telemetry = DeviceTelemetry(
            activePackage = "com.instagram.android",
            activeActivity = "com.instagram.MainActivity",
            elementsCount = 42,
            elementsHash = "abc12345",
            topSemanticTexts = listOf("Beranda", "Reels", "Profil")
        )

        val report = StepTrajectoryReport(
            flowId = "flow_1",
            stepId = "s1",
            stepIndex = 1,
            totalSteps = 2,
            status = "COMPLETED",
            durationMs = 250L,
            deviceTelemetry = telemetry
        )

        val json = report.toJson()
        assertEquals(ProtocolV2.Events.STEP_TRAJECTORY, json.getString("event"))
        assertEquals("flow_1", json.getString("flow_id"))
        assertEquals("s1", json.getString("step_id"))
        assertEquals(1, json.getInt("step_index"))
        assertEquals(2, json.getInt("total_steps"))
        assertEquals("COMPLETED", json.getString("status"))
        assertEquals(250L, json.getLong("duration_ms"))

        val telemJson = json.getJSONObject("device_telemetry")
        assertEquals("com.instagram.android", telemJson.getString("active_package"))
        assertEquals(42, telemJson.getInt("elements_count"))
        assertEquals("abc12345", telemJson.getString("elements_hash"))
        assertEquals(3, telemJson.getJSONArray("top_semantic_texts").length())
    }

    @Test
    fun `BatchResult captures failed step and failure reason`() {
        val result = BatchResult(
            flowId = "flow_err",
            status = "FAILED",
            stepsCompleted = 1,
            totalSteps = 3,
            failedStepId = "s2_click",
            reasonCode = ProtocolV2.ReasonCodes.NO_NODE
        )

        val json = result.toJson()
        assertEquals("flow_err", json.getString("flow_id"))
        assertEquals("FAILED", json.getString("status"))
        assertEquals(1, json.getInt("steps_completed"))
        assertEquals(3, json.getInt("total_steps"))
        assertEquals("s2_click", json.getString("failed_step_id"))
        assertEquals(ProtocolV2.ReasonCodes.NO_NODE, json.getString("reason_code"))
    }
}
