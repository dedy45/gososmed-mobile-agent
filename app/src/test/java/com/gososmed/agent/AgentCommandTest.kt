package com.gososmed.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan 07 P1-1: jalur fail-cepat K13 harus jujur di JVM murni — saat
 * AccessibilityService belum pernah di-bind, execute() menolak dengan pesan
 * jelas (bukan crash / bukan sukses palsu). Di JVM unit test instance
 * service selalu null (tidak ada framework Android yang berjalan).
 */
class AgentCommandTest {

    @Test
    fun `execute tanpa service gagal jujur dengan id ter-echo`() {
        val resp = AgentCommand.execute(JSONObject("""{"id":42,"cmd":"dump"}"""))
        assertEquals(42, resp.getInt("id"))
        assertFalse(resp.optBoolean("ok"))
        assertEquals("accessibility service not connected/ready", resp.getString("error"))
    }

    // v0.9.11: readiness gate yang terstruktur — error sekarang menyertakan
    // `reason` berkode `a11y_dead` atau `a11y_disabled` dan `health` snapshot,
    // bukan hanya pesan generik.
    @Test
    fun `execute tanpa service menyertakan reason dan health di error`() {
        val resp = AgentCommand.execute(JSONObject("""{"id":50,"cmd":"dump"}"""))
        assertFalse(resp.optBoolean("ok"))
        // reason harus ada dan berkode a11y_*
        val reason = resp.optString("reason", "")
        assertTrue("reason harus berkode a11y_: $reason",
            reason.startsWith("a11y_"))
        // health snapshot harus ada
        assertTrue("health snapshot harus disertakan",
            resp.has("health"))
    }

    // v0.9.11: ping sekarang SERVICE_FREE — harus SELALU berhasil, bahkan
    // tanpa service. Sebelumnya gagal "accessibility service not connected".
    @Test
    fun `ping adalah SERVICE_FREE dan selalu berhasil`() {
        assertTrue("ping harus ada di SERVICE_FREE_COMMANDS",
            AgentCommand.CMD_PING in AgentCommand.SERVICE_FREE_COMMANDS)
        val resp = AgentCommand.execute(JSONObject("""{"id":7,"cmd":"ping"}"""))
        assertTrue("ping harus selalu ok=true", resp.optBoolean("ok"))
        assertTrue("ping harus punya pong=true",
            resp.optJSONObject("result")?.optBoolean("pong") == true)
    }

    // v0.9.11: health command — snapshot kesehatan semua subsistem.
    @Test
    fun `health adalah SERVICE_FREE dan mengembalikan snapshot`() {
        assertTrue("health harus ada di SERVICE_FREE_COMMANDS",
            AgentCommand.CMD_HEALTH in AgentCommand.SERVICE_FREE_COMMANDS)
        val resp = AgentCommand.execute(JSONObject("""{"id":20,"cmd":"health"}"""))
        assertTrue("health harus ok=true", resp.optBoolean("ok"))
        val result = resp.optJSONObject("result")
        assertTrue("health harus punya result", result != null)
        // Harus punya field a11y_alive (akan false di JVM test, tapi field harus ada)
        assertTrue("health harus punya a11y_alive", result!!.has("a11y_alive"))
        assertTrue("health harus punya adb_available", result.has("adb_available"))
    }

    // v0.9.10 (audit ANR 2026-09-22): hasPackage dan listPackages sekarang
    // masuk SERVICE_FREE_COMMANDS. Di JVM unit test tanpa applicationContext,
    // command ini tetap harus mengembalikan error yang jujur (bukan crash),
    // bukan error "accessibility service not connected/ready".
    @Test
    fun `hasPackage adalah SERVICE_FREE dan tidak gagal karena accessibility`() {
        assertTrue("hasPackage harus ada di SERVICE_FREE_COMMANDS",
            AgentCommand.CMD_HAS_PACKAGE in AgentCommand.SERVICE_FREE_COMMANDS)
        val resp = AgentCommand.execute(JSONObject("""{"id":10,"cmd":"hasPackage","package":"com.example.test"}"""))
        assertEquals(10, resp.getInt("id"))
        // Di JVM unit test tanpa applicationContext, hasilnya "application context not available",
        // BUKAN "accessibility service not connected/ready".
        val error = resp.optString("error", "")
        assertTrue("hasPackage seharusnya tidak gagal karena accessibility: $error",
            !error.contains("accessibility"))
    }

    @Test
    fun `listPackages adalah SERVICE_FREE dan tidak gagal karena accessibility`() {
        assertTrue("listPackages harus ada di SERVICE_FREE_COMMANDS",
            AgentCommand.CMD_LIST_PACKAGES in AgentCommand.SERVICE_FREE_COMMANDS)
        val resp = AgentCommand.execute(JSONObject("""{"id":11,"cmd":"listPackages"}"""))
        assertEquals(11, resp.getInt("id"))
        val error = resp.optString("error", "")
        assertTrue("listPackages seharusnya tidak gagal karena accessibility: $error",
            !error.contains("accessibility"))
    }

    @Test
    fun `capabilities adalah SERVICE_FREE dan mengembalikan snapshot tanpa butuh service`() {
        assertTrue("capabilities harus ada di SERVICE_FREE_COMMANDS",
            AgentCommand.CMD_CAPABILITIES in AgentCommand.SERVICE_FREE_COMMANDS)
        val resp = AgentCommand.execute(JSONObject("""{"id":30,"cmd":"capabilities"}"""))
        assertTrue("capabilities harus selalu ok=true", resp.optBoolean("ok"))
        val result = resp.optJSONObject("result")
        assertTrue("result capabilities harus ada", result != null)
        assertTrue("capabilities harus punya agent_version", result!!.has("agent_version"))
        assertTrue("capabilities harus punya a11y_enabled", result.has("a11y_enabled"))
        assertTrue("capabilities harus punya can_draw_overlay", result.has("can_draw_overlay"))
        assertTrue("capabilities harus punya protocol_versions", result.has("protocol_versions"))
        val versions = result.getJSONArray("protocol_versions")
        assertEquals(2, versions.length())
        assertEquals(1, versions.getInt(0))
        assertEquals(2, versions.getInt(1))
    }

    @Test
    fun `ASYNC_BACKGROUND_COMMANDS berisi startApp killApp dan wake`() {
        assertTrue("startApp harus async background", AgentCommand.CMD_START_APP in AgentCommand.ASYNC_BACKGROUND_COMMANDS)
        assertTrue("killApp harus async background", AgentCommand.CMD_KILL_APP in AgentCommand.ASYNC_BACKGROUND_COMMANDS)
        assertTrue("wake harus async background", AgentCommand.CMD_WAKE in AgentCommand.ASYNC_BACKGROUND_COMMANDS)
    }

    // --- Regression tests for v1 command envelopes and contract freeze (Phase M0) ---

    @Test
    fun `v1 shell execution failure produces outer ok true with inner result ok false`() {
        val req = JSONObject("""{"id":101,"cmd":"shell","command":"input tap 10 20"}""")
        val resp = AgentCommand.execute(req)

        // Envelope: outer ok=true, inner result.ok=false (contradictory envelope in v1 contract)
        assertEquals(101, resp.getInt("id"))
        assertTrue("v1 shell envelope returns outer ok=true when command is recognized", resp.optBoolean("ok"))
        val result = resp.optJSONObject("result")
        assertTrue("v1 shell response must contain result object", result != null)
        assertFalse("v1 shell failure sets inner result.ok=false", result!!.optBoolean("ok"))
        assertTrue("v1 shell failure must include failure reason", result.has("reason"))
        val reason = result.optString("reason", "")
        assertTrue("v1 shell reason must indicate failure detail: $reason", reason.isNotEmpty())
        assertEquals("shell_adb", result.optString("transport"))
        assertEquals(-1, result.optInt("exit_code"))
    }

    @Test
    fun `v1 adbPair query or failure produces outer ok true with inner result ok false`() {
        // Query status when not paired/connected
        val reqQuery = JSONObject("""{"id":102,"cmd":"adbPair"}""")
        val respQuery = AgentCommand.execute(reqQuery)

        assertEquals(102, respQuery.getInt("id"))
        assertTrue("v1 adbPair status query returns outer ok=true", respQuery.optBoolean("ok"))
        val resultQuery = respQuery.optJSONObject("result")
        assertTrue("result object must exist", resultQuery != null)
        assertFalse("adb_connected must be false in uninitialized test environment", resultQuery!!.optBoolean("adb_connected"))
        assertFalse("inner result.ok must reflect connection failure", resultQuery.optBoolean("ok"))
        assertTrue("reason must be included", resultQuery.has("reason"))

        // Pair attempt to unreachable port
        val reqPair = JSONObject("""{"id":103,"cmd":"adbPair","host":"127.0.0.1","port":5555,"code":"123456"}""")
        val respPair = AgentCommand.execute(reqPair)

        assertEquals(103, respPair.getInt("id"))
        assertTrue("v1 adbPair attempt returns outer ok=true", respPair.optBoolean("ok"))
        val resultPair = respPair.optJSONObject("result")
        assertTrue("result object must exist for pair attempt", resultPair != null)
        assertFalse("inner result.ok must be false when pairing fails", resultPair!!.optBoolean("ok"))
        assertTrue("reason must be included for failed pair attempt", resultPair.has("reason"))
    }

    @Test
    fun `v1 shell with missing or blank command argument fails with outer ok false`() {
        val reqMissing = JSONObject("""{"id":104,"cmd":"shell"}""")
        val respMissing = AgentCommand.execute(reqMissing)
        assertEquals(104, respMissing.getInt("id"))
        assertFalse("shell without command argument returns outer ok=false", respMissing.optBoolean("ok"))
        assertEquals("shell requires command", respMissing.optString("error"))
        assertFalse("shell missing args must not have result object", respMissing.has("result"))

        val reqBlank = JSONObject("""{"id":105,"cmd":"shell","command":"   "}""")
        val respBlank = AgentCommand.execute(reqBlank)
        assertEquals(105, respBlank.getInt("id"))
        assertFalse("shell with blank command returns outer ok=false", respBlank.optBoolean("ok"))
        assertEquals("shell requires command", respBlank.optString("error"))
    }

    @Test
    fun `v1 tap envelope when service disconnected rejects both valid and invalid arguments`() {
        val requests = listOf(
            JSONObject("""{"id":201,"cmd":"tap","x":500,"y":800}"""),
            JSONObject("""{"id":202,"cmd":"tap"}"""),
            JSONObject("""{"id":203,"cmd":"tap","x":-1,"y":-1}""")
        )

        for (req in requests) {
            val id = req.getInt("id")
            val resp = AgentCommand.execute(req)
            assertEquals("id must be echoed", id, resp.getInt("id"))
            assertFalse("outer ok must be false when service disconnected", resp.optBoolean("ok"))
            assertEquals("accessibility service not connected/ready", resp.optString("error"))
            assertTrue("reason must start with a11y_: ${resp.optString("reason")}",
                resp.optString("reason").startsWith("a11y_"))
            assertTrue("health snapshot must be present", resp.has("health"))
            assertFalse("result object must not be present", resp.has("result"))
        }
    }

    @Test
    fun `v1 setText envelope when service disconnected rejects command`() {
        val requests = listOf(
            JSONObject("""{"id":210,"cmd":"setText","text":"hello world"}"""),
            JSONObject("""{"id":211,"cmd":"setText"}""")
        )

        for (req in requests) {
            val id = req.getInt("id")
            val resp = AgentCommand.execute(req)
            assertEquals("id must be echoed", id, resp.getInt("id"))
            assertFalse("outer ok must be false when service disconnected", resp.optBoolean("ok"))
            assertEquals("accessibility service not connected/ready", resp.optString("error"))
            assertTrue("reason must start with a11y_", resp.optString("reason").startsWith("a11y_"))
            assertTrue("health snapshot must be present", resp.has("health"))
            assertFalse("result object must not be present", resp.has("result"))
        }
    }

    @Test
    fun `v1 tapByText envelope when service disconnected rejects both present and missing text`() {
        val requests = listOf(
            JSONObject("""{"id":220,"cmd":"tapByText","text":"Masuk"}"""),
            JSONObject("""{"id":221,"cmd":"tapByText"}"""),
            JSONObject("""{"id":222,"cmd":"tapByText","text":""}""")
        )

        for (req in requests) {
            val id = req.getInt("id")
            val resp = AgentCommand.execute(req)
            assertEquals("id must be echoed", id, resp.getInt("id"))
            assertFalse("outer ok must be false when service disconnected", resp.optBoolean("ok"))
            assertEquals("accessibility service not connected/ready", resp.optString("error"))
            assertTrue("reason must start with a11y_", resp.optString("reason").startsWith("a11y_"))
            assertTrue("health snapshot must be present", resp.has("health"))
            assertFalse("result object must not be present", resp.has("result"))
        }
    }

    @Test
    fun `v1 screenshot envelope when service disconnected rejects capture request`() {
        val requests = listOf(
            JSONObject("""{"id":230,"cmd":"screenshot"}"""),
            JSONObject("""{"id":231,"cmd":"screenshot","scale":0.5,"format":"jpeg","quality":80}""")
        )

        for (req in requests) {
            val id = req.getInt("id")
            val resp = AgentCommand.execute(req)
            assertEquals("id must be echoed", id, resp.getInt("id"))
            assertFalse("outer ok must be false when service disconnected", resp.optBoolean("ok"))
            assertEquals("accessibility service not connected/ready", resp.optString("error"))
            assertTrue("reason must start with a11y_", resp.optString("reason").startsWith("a11y_"))
            assertTrue("health snapshot must be present", resp.has("health"))
            assertFalse("result object must not be present", resp.has("result"))
        }
    }

    @Test
    fun `v1 dump envelope when service disconnected rejects hierarchy requests`() {
        val requests = listOf(
            JSONObject("""{"id":240,"cmd":"dump"}"""),
            JSONObject("""{"id":241,"cmd":"dumpWindows"}"""),
            JSONObject("""{"id":242,"cmd":"tapFirstClickable"}"""),
            JSONObject("""{"id":243,"cmd":"startApp","package":"com.example.app"}"""),
            JSONObject("""{"id":244,"cmd":"startApp"}"""),
            JSONObject("""{"id":245,"cmd":"killApp","package":"com.example.app"}"""),
            JSONObject("""{"id":246,"cmd":"killApp"}""")
        )

        for (req in requests) {
            val id = req.getInt("id")
            val cmd = req.getString("cmd")
            val resp = AgentCommand.execute(req)
            assertEquals("id must be echoed for $cmd", id, resp.getInt("id"))
            assertFalse("outer ok must be false when service disconnected for $cmd", resp.optBoolean("ok"))
            assertEquals("accessibility service not connected/ready", resp.optString("error"))
            assertTrue("reason must start with a11y_ for $cmd", resp.optString("reason").startsWith("a11y_"))
            assertTrue("health snapshot must be present for $cmd", resp.has("health"))
            assertFalse("result object must not be present for $cmd", resp.has("result"))
        }
    }
}
