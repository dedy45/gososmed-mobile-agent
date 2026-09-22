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
}
