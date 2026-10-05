package com.gososmed.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolV2Test {

    @Test
    fun testRequestSerializationAndDeserialization() {
        val args = JSONObject().apply {
            put("timeout_ms", 5000)
            put("selector", "btn_login")
        }
        val request = ProtocolV2.Request(
            protocolVersion = 2,
            requestId = "req-1234-abcd",
            cmd = ProtocolV2.Commands.OBSERVE,
            deadlineMs = 15000L,
            args = args
        )

        val json = request.toJson()
        assertEquals(2, json.getInt("protocol_version"))
        assertEquals("req-1234-abcd", json.getString("request_id"))
        assertEquals(ProtocolV2.Commands.OBSERVE, json.getString("cmd"))
        assertEquals(15000L, json.getLong("deadline_ms"))
        assertEquals("btn_login", json.getJSONObject("args").getString("selector"))

        val parsed = ProtocolV2.Request.fromJson(json)
        assertEquals(request.protocolVersion, parsed.protocolVersion)
        assertEquals(request.requestId, parsed.requestId)
        assertEquals(request.cmd, parsed.cmd)
        assertEquals(request.deadlineMs, parsed.deadlineMs)
        assertEquals(5000, parsed.args.getInt("timeout_ms"))
        assertEquals("btn_login", parsed.args.getString("selector"))
    }

    @Test
    fun testResponseSuccess() {
        val timing = ProtocolV2.Timing(startedAtMs = 1000L, completedAtMs = 1050L, durationMs = 50L)
        val result = JSONObject().apply {
            put("screen_fingerprint", "abc-xyz")
        }

        val response = ProtocolV2.Response.success(
            requestId = "req-success",
            result = result,
            timing = timing
        )

        assertTrue(response.ok)
        assertNull(response.reasonCode)
        assertFalse(response.retryable)

        val json = response.toJson()
        assertEquals(2, json.getInt("protocol_version"))
        assertEquals("req-success", json.getString("request_id"))
        assertTrue(json.getBoolean("ok"))
        assertFalse(json.has("reason_code"))
        assertEquals(50L, json.getJSONObject("timing").getLong("duration_ms"))

        val parsed = ProtocolV2.Response.fromJson(json)
        assertTrue(parsed.ok)
        assertNull(parsed.reasonCode)
        assertEquals("abc-xyz", parsed.result.getString("screen_fingerprint"))
    }

    @Test
    fun testResponseErrorMandatesReasonCode() {
        val timing = ProtocolV2.Timing(startedAtMs = 100L, completedAtMs = 200L, durationMs = 100L)
        val response = ProtocolV2.Response.error(
            requestId = "req-fail",
            reasonCode = ProtocolV2.ReasonCodes.UNSTABLE_TREE,
            retryable = true,
            timing = timing
        )

        assertFalse(response.ok)
        assertEquals(ProtocolV2.ReasonCodes.UNSTABLE_TREE, response.reasonCode)
        assertTrue(response.retryable)

        val json = response.toJson()
        assertEquals("unstable_tree", json.getString("reason_code"))
        assertTrue(json.getBoolean("retryable"))

        val parsed = ProtocolV2.Response.fromJson(json)
        assertFalse(parsed.ok)
        assertEquals(ProtocolV2.ReasonCodes.UNSTABLE_TREE, parsed.reasonCode)
        assertTrue(parsed.retryable)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testResponseErrorRequiresReasonCodeValidation() {
        ProtocolV2.Response(
            protocolVersion = 2,
            requestId = "req-bad",
            ok = false,
            reasonCode = null
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testResponseSuccessForbidsReasonCode() {
        ProtocolV2.Response(
            protocolVersion = 2,
            requestId = "req-bad",
            ok = true,
            reasonCode = ProtocolV2.ReasonCodes.NO_NODE
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testResponseContradictoryNestedOkValidation() {
        val result = JSONObject().apply {
            put("ok", true)
        }
        ProtocolV2.Response(
            protocolVersion = 2,
            requestId = "req-contradictory",
            ok = false,
            reasonCode = ProtocolV2.ReasonCodes.TIMEOUT,
            result = result
        )
    }

    @Test
    fun testLegacyV1DetectionAndFallback() {
        val legacyJson = JSONObject().apply {
            put("type", "tap")
            put("payload", JSONObject().apply {
                put("x", 100)
                put("y", 200)
            })
        }

        assertTrue(ProtocolV2.Request.isLegacyV1(legacyJson))
        val parsed = ProtocolV2.Request.fromJson(legacyJson)
        assertEquals(1, parsed.protocolVersion)
        assertEquals("tap", parsed.cmd)
        assertEquals(100, parsed.args.getInt("x"))
    }

    @Test
    fun testUnsupportedVersionRejection() {
        assertFalse(ProtocolV2.isSupportedVersion(3))
        assertFalse(ProtocolV2.isSupportedVersion(0))
        assertTrue(ProtocolV2.isSupportedVersion(1))
        assertTrue(ProtocolV2.isSupportedVersion(2))

        val rejection = ProtocolV2.Response.protocolMismatch(
            requestId = "req-v3",
            unsupportedVersion = 3,
            timing = ProtocolV2.Timing(startedAtMs = 100L, completedAtMs = 105L)
        )

        assertFalse(rejection.ok)
        assertEquals(ProtocolV2.ReasonCodes.PROTOCOL_MISMATCH, rejection.reasonCode)
        assertEquals(3, rejection.result.getInt("received_version"))
    }

    @Test
    fun testTimingCalculation() {
        val timing = ProtocolV2.Timing.create(startedAtMs = 1000L, completedAtMs = 1250L)
        assertEquals(1000L, timing.startedAtMs)
        assertEquals(1250L, timing.completedAtMs)
        assertEquals(250L, timing.durationMs)

        val json = timing.toJson()
        val parsed = ProtocolV2.Timing.fromJson(json)
        assertEquals(250L, parsed.durationMs)
    }

    @Test
    fun testAllCanonicalReasonCodesExist() {
        val expectedCodes = listOf(
            "no_node",
            "ambiguous",
            "invalid_bounds",
            "stale_snapshot",
            "wrong_package",
            "unstable_tree",
            "action_not_supported",
            "action_rejected",
            "gesture_cancelled",
            "screen_not_changed",
            "unexpected_screen",
            "blocked_dialog",
            "timeout",
            "cancelled",
            "device_busy",
            "submit_barrier",
            "screenshot_failed",
            "protocol_mismatch"
        )

        for (code in expectedCodes) {
            assertTrue("Expected reason code $code to be present in ALL", ProtocolV2.ReasonCodes.ALL.contains(code))
        }
        assertEquals(expectedCodes.size, ProtocolV2.ReasonCodes.ALL.size)
    }

    @Test
    fun testAllCommandsExist() {
        val expectedCommands = listOf(
            "observe",
            "resolve",
            "actNode",
            "actAndVerify",
            "waitForNode",
            "waitForScreen",
            "cancelAction",
            "startDebugFrames",
            "stopDebugFrames"
        )

        for (cmd in expectedCommands) {
            assertTrue("Expected command $cmd to be present in ALL", ProtocolV2.Commands.ALL.contains(cmd))
        }
        assertEquals(expectedCommands.size, ProtocolV2.Commands.ALL.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testEmptyRequestIdRejectionInV2Constructor() {
        ProtocolV2.Request(
            protocolVersion = 2,
            requestId = "",
            cmd = ProtocolV2.Commands.OBSERVE
        )
    }

    @Test
    fun testEmptyRequestIdRejectedByProtocolValidate() {
        val json = JSONObject().apply {
            put("protocol_version", 2)
            put("request_id", "")
            put("cmd", ProtocolV2.Commands.OBSERVE)
        }
        val validationResp = ProtocolV2.validate(json)
        assertNotNull(validationResp)
        assertFalse(validationResp!!.ok)
        assertEquals(ProtocolV2.ReasonCodes.ACTION_REJECTED, validationResp.reasonCode)
        assertEquals("missing_or_blank_request_id", validationResp.result.getString("error"))
    }

    @Test
    fun testDuplicateRequestIdHandlingInFlight() {
        CommandCancellationRegistry.clear()
        val reqId = "req-dup-test"
        val registeredFirst = CommandCancellationRegistry.register(reqId, deadlineMs = 10000L)
        assertTrue(registeredFirst)

        // Second registration while still in-flight must be rejected
        val registeredDuplicate = CommandCancellationRegistry.register(reqId, deadlineMs = 10000L)
        assertFalse(registeredDuplicate)

        // AgentCommand.execute rejects duplicate in-flight request with DEVICE_BUSY
        val reqJson = JSONObject().apply {
            put("protocol_version", 2)
            put("request_id", reqId)
            put("cmd", ProtocolV2.Commands.OBSERVE)
        }
        val resp = AgentCommand.execute(reqJson)
        assertFalse(resp.getBoolean("ok"))
        assertEquals(ProtocolV2.ReasonCodes.DEVICE_BUSY, resp.getString("reason_code"))
        assertEquals("duplicate_request_id_in_flight", resp.getJSONObject("result").getString("error"))
        CommandCancellationRegistry.clear()
    }

    @Test
    fun testInvalidDeadlineAndOverflowHandling() {
        CommandCancellationRegistry.clear()

        // Zero or negative deadline defaults to 10s safely
        val zeroDeadlineJson = JSONObject().apply {
            put("protocol_version", 2)
            put("request_id", "req-zero-dl")
            put("cmd", ProtocolV2.Commands.OBSERVE)
            put("deadline_ms", 0L)
        }
        val reqZero = ProtocolV2.Request.fromJson(zeroDeadlineJson)
        assertEquals(10000L, reqZero.deadlineMs)
        assertEquals(10000L, reqZero.effectiveDeadlineMs)

        val negDeadlineJson = JSONObject().apply {
            put("protocol_version", 2)
            put("request_id", "req-neg-dl")
            put("cmd", ProtocolV2.Commands.OBSERVE)
            put("deadline_ms", -5000L)
        }
        val reqNeg = ProtocolV2.Request.fromJson(negDeadlineJson)
        assertEquals(10000L, reqNeg.deadlineMs)
        assertEquals(10000L, reqNeg.effectiveDeadlineMs)

        // CommandCancellationRegistry with negative deadline does not expire immediately
        CommandCancellationRegistry.register("req-neg-reg", -100L, currentTimeMs = 1000L)
        assertFalse(CommandCancellationRegistry.isExpired("req-neg-reg", currentTimeMs = 1500L))

        // Extreme deadline does not overflow to negative timestamp
        CommandCancellationRegistry.register("req-extreme", Long.MAX_VALUE, currentTimeMs = 1000L)
        assertFalse(CommandCancellationRegistry.isExpired("req-extreme", currentTimeMs = 2000L))
        CommandCancellationRegistry.clear()
    }

    @Test
    fun testProtocolV1BackwardCompatibility() {
        // V1 payload with legacy fields
        val legacyCmd = JSONObject().apply {
            put("id", 42)
            put("cmd", "ping")
        }
        assertTrue(ProtocolV2.Request.isLegacyV1(legacyCmd))

        // Execute v1 ping without protocol_version returns v1 envelope
        val resp = AgentCommand.execute(legacyCmd)
        assertEquals(42, resp.getInt("id"))
        assertTrue(resp.getBoolean("ok"))
        assertTrue(resp.getJSONObject("result").getBoolean("pong"))
        assertFalse(resp.has("protocol_version"))
    }
}
