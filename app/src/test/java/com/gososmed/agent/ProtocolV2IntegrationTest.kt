package com.gososmed.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProtocolV2IntegrationTest {

    @Before
    fun setup() {
        MutationGuard.reset()
        CommandCancellationRegistry.clear()
        SnapshotRegistry.clear()
    }

    private fun registerSnapshot(id: String, hash: String, nodes: List<SnapshotNode>) {
        val snap = SnapshotResult(
            snapshotId = id,
            frameSeq = 1L,
            capturedAtMs = System.currentTimeMillis(),
            display = SnapshotDisplay(0, 1080, 2400, 0, 420),
            foreground = SnapshotForeground("com.example.app", 1),
            treeHash = hash,
            screenFingerprint = "fp-$id",
            quality = SnapshotQuality(valid = true, invalidBoundsRatio = 0.0, windowChanged = false),
            nodes = nodes
        )
        SnapshotRegistry.register(snap)
    }

    @Test
    fun testProtocolMismatchOnVersion3() {
        val req = JSONObject().apply {
            put("protocol_version", 3)
            put("request_id", "req-v3")
            put("cmd", "observe")
        }

        val resp = AgentCommand.execute(req)
        assertFalse(resp.getBoolean("ok"))
        assertEquals("protocol_mismatch", resp.getString("reason_code"))
        assertEquals("req-v3", resp.getString("request_id"))
    }

    @Test
    fun testV2CancelAction() {
        // Register an active command first
        CommandCancellationRegistry.register("target-cmd-1", 10000L)

        val cancelReq = JSONObject().apply {
            put("protocol_version", 2)
            put("request_id", "cancel-req-1")
            put("cmd", "cancelAction")
            put("args", JSONObject().apply {
                put("target_request_id", "target-cmd-1")
            })
        }

        val resp = AgentCommand.execute(cancelReq)
        assertTrue(resp.getBoolean("ok"))
        assertEquals("cancel-req-1", resp.getString("request_id"))
        assertTrue(resp.getJSONObject("result").getBoolean("cancelled"))
        assertTrue(CommandCancellationRegistry.isCancelled("target-cmd-1"))
    }

    @Test
    fun testV2ResolveCommand() {
        val node1 = SnapshotNode(
            nodeId = "node-login",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            resourceId = "com.example.app:id/btn_login",
            text = "Log In",
            bounds = SnapshotBounds(100, 100, 300, 200),
            clickable = true
        )

        val nodesArr = JSONArray().apply {
            put(node1.toJson())
        }

        val resolveReq = JSONObject().apply {
            put("protocol_version", 2)
            put("request_id", "res-req-1")
            put("cmd", "resolve")
            put("args", JSONObject().apply {
                put("resource_id", "btn_login")
                put("nodes", nodesArr)
            })
        }

        val resp = AgentCommand.execute(resolveReq)
        assertTrue(resp.getBoolean("ok"))
        val result = resp.getJSONObject("result")
        val resolveRes = result.getJSONObject("resolve_result")
        assertFalse(resolveRes.getBoolean("is_ambiguous"))
        assertEquals("node-login", resolveRes.getJSONObject("selected_node").getString("node_id"))
    }

    @Test
    fun testV2ResolveUsesRegisteredSnapshotWhenNodesNotSupplied() {
        val node = SnapshotNode(
            nodeId = "node-from-reg",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            resourceId = "com.example.app:id/submit_button",
            text = "Submit",
            bounds = SnapshotBounds(100, 100, 300, 200),
            clickable = true
        )
        registerSnapshot("snap-reg-1", "sha256:reg-1", listOf(node))

        val resolveReq = JSONObject().apply {
            put("protocol_version", 2)
            put("request_id", "res-req-reg")
            put("cmd", "resolve")
            put("args", JSONObject().apply {
                put("snapshot_id", "snap-reg-1")
                put("resource_id", "submit_button")
            })
        }

        val resp = AgentCommand.execute(resolveReq)
        assertTrue(resp.getBoolean("ok"))
        val result = resp.getJSONObject("result")
        val resolveRes = result.getJSONObject("resolve_result")
        assertEquals("node-from-reg", resolveRes.getJSONObject("selected_node").getString("node_id"))
    }

    @Test
    fun testV2ResolveWithStaleSnapshotIdReturnsStaleSnapshot() {
        val resolveReq = JSONObject().apply {
            put("protocol_version", 2)
            put("request_id", "res-req-stale")
            put("cmd", "resolve")
            put("args", JSONObject().apply {
                put("snapshot_id", "non-existent-snap")
                put("resource_id", "submit_button")
            })
        }

        val resp = AgentCommand.execute(resolveReq)
        assertFalse(resp.getBoolean("ok"))
        assertEquals(ProtocolV2.ReasonCodes.STALE_SNAPSHOT, resp.getString("reason_code"))
    }

    @Test
    fun testV2ActNodeRequiresMandatorySnapshotId() {
        val actReq = JSONObject().apply {
            put("protocol_version", 2)
            put("request_id", "act-missing-snap")
            put("cmd", "actNode")
            put("args", JSONObject().apply {
                put("selector", JSONObject().apply { put("text", "Submit") })
            })
        }

        val resp = AgentCommand.execute(actReq)
        assertFalse(resp.getBoolean("ok"))
        assertEquals(ProtocolV2.ReasonCodes.STALE_SNAPSHOT, resp.getString("reason_code"))
        assertFalse("Missing snapshotId must NOT be retryable", resp.getBoolean("retryable"))
    }

    @Test
    fun testActionChainingUsesAfterSnapshotIdSuccessfully() {
        // Setup initial snapshot
        val node1 = SnapshotNode(
            nodeId = "node-btn1",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            text = "Next",
            bounds = SnapshotBounds(100, 100, 300, 200),
            clickable = true
        )
        registerSnapshot("snap-init", "sha256:init", listOf(node1))

        // Simulate that after action 1, an after snapshot is registered (what actNode/actAndVerify now guarantees)
        val node2 = SnapshotNode(
            nodeId = "node-btn2",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            text = "Finish",
            bounds = SnapshotBounds(100, 100, 300, 200),
            clickable = true
        )
        val afterSnap = SnapshotResult(
            snapshotId = "snap-after-1",
            frameSeq = 2L,
            capturedAtMs = System.currentTimeMillis(),
            display = SnapshotDisplay(0, 1080, 2400, 0, 420),
            foreground = SnapshotForeground("com.example.app", 1),
            treeHash = "sha256:after1",
            screenFingerprint = "fp-after1",
            quality = SnapshotQuality(valid = true, invalidBoundsRatio = 0.0, windowChanged = false),
            nodes = listOf(node2)
        )
        SnapshotRegistry.register(afterSnap)

        // Step 2 in trajectory: resolve and act using snap-after-1
        val resolveReq = JSONObject().apply {
            put("protocol_version", 2)
            put("request_id", "req-step2-resolve")
            put("cmd", "resolve")
            put("args", JSONObject().apply {
                put("snapshot_id", "snap-after-1")
                put("text", "Finish")
            })
        }
        val resolveResp = AgentCommand.execute(resolveReq)
        assertTrue(resolveResp.getBoolean("ok"))
        assertEquals("node-btn2", resolveResp.getJSONObject("result").getJSONObject("resolve_result").getJSONObject("selected_node").getString("node_id"))
    }
    @Test
    fun testV2ActNodeWithUnknownSnapshotIdReturnsStaleSnapshot() {
        val actReq = JSONObject().apply {
            put("protocol_version", 2)
            put("request_id", "act-unknown-snap")
            put("cmd", "actNode")
            put("args", JSONObject().apply {
                put("snapshot_id", "snap-does-not-exist")
                put("selector", JSONObject().apply { put("text", "Submit") })
            })
        }

        val resp = AgentCommand.execute(actReq)
        assertFalse(resp.getBoolean("ok"))
        assertEquals(ProtocolV2.ReasonCodes.STALE_SNAPSHOT, resp.getString("reason_code"))
    }

    @Test
    fun testV2ActNodeSubmitBarrierOnOperationId() {
        val node = SnapshotNode(
            nodeId = "node-submit",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            bounds = SnapshotBounds(100, 100, 300, 200),
            clickable = true
        )
        registerSnapshot("snap-submit-1", "sha256:sub", listOf(node))

        // Acquire operationId first directly
        val acq = MutationGuard.tryAcquireMutation(
            requestId = "prior-req",
            isSubmitAction = true,
            operationId = "op-transfer-100"
        )
        assertTrue(acq.acquired)
        MutationGuard.releaseMutation("prior-req")

        // Now a client sends actNode with the same operation_id
        val actReq = JSONObject().apply {
            put("protocol_version", 2)
            put("request_id", "new-req-retry")
            put("cmd", "actNode")
            put("args", JSONObject().apply {
                put("snapshot_id", "snap-submit-1")
                put("operation_id", "op-transfer-100")
                put("selector", JSONObject().apply { put("text", "Submit") })
            })
        }

        val resp = AgentCommand.execute(actReq)
        assertFalse(resp.getBoolean("ok"))
        assertEquals(ProtocolV2.ReasonCodes.SUBMIT_BARRIER, resp.getString("reason_code"))
    }

    @Test
    fun testV1CommandsContinueWorkingWithoutProtocolVersion() {
        val v1Req = JSONObject().apply {
            put("id", 42)
            put("cmd", "hasPackage")
            put("package", "com.android.settings")
        }

        val resp = AgentCommand.execute(v1Req)
        assertEquals(42, resp.getInt("id"))
        // V1 response schema doesn't have protocol_version
        assertFalse(resp.has("protocol_version"))
    }

    @Test
    fun testV2ObserveRejectsWebPWithImageConfigException() {
        val req = JSONObject().apply {
            put("protocol_version", 2)
            put("request_id", "req-obs-webp")
            put("cmd", "observe")
            put("args", JSONObject().apply {
                put("include_image", true)
                put("format", "webp")
            })
        }
        val res = kotlinx.coroutines.runBlocking {
            AgentCommand.executeV2WithBinary(req)
        }
        assertFalse(res.responseJson.getBoolean("ok"))
        assertEquals("unsupported_image_format", res.responseJson.getString("reason_code"))
        assertNull(res.observeBinaryPayload)
    }

    @Test
    fun testV2ObserveRejectsQualityOutOfRange() {
        val req = JSONObject().apply {
            put("protocol_version", 2)
            put("request_id", "req-obs-quality")
            put("cmd", "observe")
            put("args", JSONObject().apply {
                put("include_image", true)
                put("quality", 0)
            })
        }
        val res = kotlinx.coroutines.runBlocking {
            AgentCommand.executeV2WithBinary(req)
        }
        assertFalse(res.responseJson.getBoolean("ok"))
        assertEquals("invalid_image_arg", res.responseJson.getString("reason_code"))
        assertNull(res.observeBinaryPayload)
    }

    @Test
    fun testV2ObserveRejectsOptionsWithoutIncludeImage() {
        val req = JSONObject().apply {
            put("protocol_version", 2)
            put("request_id", "req-obs-no-inc")
            put("cmd", "observe")
            put("args", JSONObject().apply {
                put("quality", 75)
            })
        }
        val res = kotlinx.coroutines.runBlocking {
            AgentCommand.executeV2WithBinary(req)
        }
        assertFalse(res.responseJson.getBoolean("ok"))
        assertEquals("image_options_require_include_image", res.responseJson.getString("reason_code"))
        assertNull(res.observeBinaryPayload)
    }

    @Test
    fun testV2ObserveWithoutServiceReturnsDeviceBusy() {
        val req = JSONObject().apply {
            put("protocol_version", 2)
            put("request_id", "req-obs-no-svc")
            put("cmd", "observe")
            put("args", JSONObject().apply {
                put("include_image", false)
            })
        }
        val res = kotlinx.coroutines.runBlocking {
            AgentCommand.executeV2WithBinary(req)
        }
        assertFalse(res.responseJson.getBoolean("ok"))
        assertEquals("device_busy", res.responseJson.getString("reason_code"))
        assertEquals("accessibility_not_ready", res.responseJson.getJSONObject("result").getString("error"))
        assertNull(res.observeBinaryPayload)
    }

    @Test
    fun testV2ExecuteV2WithBinaryMatchesExecuteV2Response() {
        val req = JSONObject().apply {
            put("protocol_version", 2)
            put("request_id", "req-obs-match")
            put("cmd", "observe")
            put("args", JSONObject().apply {
                put("include_image", false)
            })
        }
        val resWithBinary = kotlinx.coroutines.runBlocking {
            AgentCommand.executeV2WithBinary(req)
        }
        val resPlain = kotlinx.coroutines.runBlocking {
            AgentCommand.executeV2(req)
        }
        assertEquals(resWithBinary.responseJson.getBoolean("ok"), resPlain.getBoolean("ok"))
        assertEquals(resWithBinary.responseJson.getString("request_id"), resPlain.getString("request_id"))
        assertEquals(resWithBinary.responseJson.getString("reason_code"), resPlain.getString("reason_code"))
        assertEquals(resWithBinary.responseJson.getJSONObject("result").getString("error"), resPlain.getJSONObject("result").getString("error"))
    }
}
