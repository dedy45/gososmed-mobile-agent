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

    @Test
    fun `execute ping tanpa service juga gagal jujur`() {
        val resp = AgentCommand.execute(JSONObject("""{"id":7,"cmd":"ping"}"""))
        assertFalse(resp.optBoolean("ok"))
        assertEquals("accessibility service not connected/ready", resp.getString("error"))
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
