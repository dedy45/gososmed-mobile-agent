package com.gososmed.agent.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbReconnectPolicyTest {
    @Test
    fun `needsReconnect returns false when linkUp and connectionHealthy even if activePort is -1`() {
        assertFalse(AdbLocalShell.needsReconnect(linkUp = true, connectionHealthy = true))
        assertTrue(AdbLocalShell.needsReconnect(linkUp = true, connectionHealthy = false))
        assertTrue(AdbLocalShell.needsReconnect(linkUp = false, connectionHealthy = true))
    }

    @Test
    fun `backoff escalates from 10s to 300s cap without hammering every 10s`() {
        assertEquals(10_000L, AdbPairingController.reconnectBackoffMs(0))
        assertEquals(20_000L, AdbPairingController.reconnectBackoffMs(1))
        assertEquals(40_000L, AdbPairingController.reconnectBackoffMs(2))
        assertEquals(300_000L, AdbPairingController.reconnectBackoffMs(10))
    }
}
