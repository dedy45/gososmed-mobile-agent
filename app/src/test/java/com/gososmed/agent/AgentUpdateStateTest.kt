package com.gososmed.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentUpdateStateTest {

    @Test
    fun `compareVersions menangani versi numerik standar`() {
        assertTrue(AgentUpdateState.compareVersions("0.10.0", "0.9.11") > 0)
        assertTrue(AgentUpdateState.compareVersions("0.9.11", "0.9.10") > 0)
        assertTrue(AgentUpdateState.compareVersions("0.9.9", "0.9.11") < 0)
        assertEquals(0, AgentUpdateState.compareVersions("0.9.11", "0.9.11"))
        assertEquals(0, AgentUpdateState.compareVersions("v0.9.11", "0.9.11"))
    }

    @Test
    fun `compareVersions stabil selalu lebih baru daripada dev pada versi basis sama`() {
        // SemVer: 0.9.11 > 0.9.11-dev.1
        assertTrue(AgentUpdateState.compareVersions("0.9.11", "0.9.11-dev.1") > 0)
        assertTrue(AgentUpdateState.compareVersions("0.9.11-dev.1", "0.9.11") < 0)
    }

    @Test
    fun `compareVersions membandingkan nomor dev berurutan`() {
        // dev.2 > dev.1
        assertTrue(AgentUpdateState.compareVersions("0.9.11-dev.2", "0.9.11-dev.1") > 0)
        assertTrue(AgentUpdateState.compareVersions("0.9.11-dev.1", "0.9.11-dev.2") < 0)
        assertEquals(0, AgentUpdateState.compareVersions("0.9.11-dev.2", "0.9.11-dev.2"))
    }

    @Test
    fun `compareVersions dev pada versi baru lebih tinggi dari rilis stabil lama`() {
        // 0.10.0-dev.1 > 0.9.11
        assertTrue(AgentUpdateState.compareVersions("0.10.0-dev.1", "0.9.11") > 0)
        assertTrue(AgentUpdateState.compareVersions("0.9.11", "0.10.0-dev.1") < 0)
    }

    @Test
    fun `isNewer mengembalikan true bila latestVersion lebih baru`() {
        AgentUpdateState.latestVersion = "0.9.12"
        assertTrue(AgentUpdateState.isNewer("0.9.11"))
        assertFalse(AgentUpdateState.isNewer("0.9.12"))
        assertFalse(AgentUpdateState.isNewer("0.10.0"))
    }
}
