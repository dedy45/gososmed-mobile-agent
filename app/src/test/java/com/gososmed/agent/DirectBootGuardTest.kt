package com.gososmed.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectBootGuardTest {
    @Test
    fun `credential encrypted storage is gated on user unlock state`() {
        assertFalse(AgentForegroundService.canAccessCredentialStorage(userUnlocked = false))
        assertTrue(AgentForegroundService.canAccessCredentialStorage(userUnlocked = true))
    }
}
