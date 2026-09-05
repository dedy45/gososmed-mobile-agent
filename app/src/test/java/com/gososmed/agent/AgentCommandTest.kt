package com.gososmed.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
