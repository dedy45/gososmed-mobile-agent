package com.gososmed.agent.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhantomProcessGuardTest {

    @Test
    fun testDisableCommandsStructure() {
        val cmd = PhantomProcessGuard.DISABLE_COMMAND
        assertTrue(cmd.contains("settings_enable_monitor_phantom_procs 0"))
        assertTrue(cmd.contains("max_phantom_processes 2147483647"))
    }

    @Test
    fun testRestoreCommandsStructure() {
        val cmd = PhantomProcessGuard.RESTORE_COMMAND
        assertTrue(cmd.contains("settings delete global settings_enable_monitor_phantom_procs"))
        assertTrue(cmd.contains("device_config delete activity_manager max_phantom_processes"))
    }

    @Test
    fun testParseIsDisabled() {
        assertTrue(PhantomProcessGuard.parseIsDisabled("0"))
        assertTrue(PhantomProcessGuard.parseIsDisabled("false"))
        assertTrue(PhantomProcessGuard.parseIsDisabled(" 0 \n"))
        assertTrue(PhantomProcessGuard.parseIsDisabled("FALSE"))

        assertFalse(PhantomProcessGuard.parseIsDisabled("1"))
        assertFalse(PhantomProcessGuard.parseIsDisabled("true"))
        assertFalse(PhantomProcessGuard.parseIsDisabled(null))
        assertFalse(PhantomProcessGuard.parseIsDisabled("null"))
        assertFalse(PhantomProcessGuard.parseIsDisabled(""))
    }

    @Test
    fun testExecutionWithMockShell() {
        val mockShell = object : PrivilegedShell {
            var executedCmd: String = ""
            override fun status() = ShellStatus(available = true, paired = true, connected = true, uid = 2000, error = "")
            override fun exec(command: String, timeoutMs: Long): ShellResult {
                executedCmd = command
                return ShellResult(ok = true, exitCode = 0, stdout = "done", stderr = "")
            }
        }

        val ok = PhantomProcessGuard.disable(mockShell)
        assertTrue(ok)
        assertEquals(PhantomProcessGuard.DISABLE_COMMAND, mockShell.executedCmd)

        val restored = PhantomProcessGuard.restore(mockShell)
        assertTrue(restored)
        assertEquals(PhantomProcessGuard.RESTORE_COMMAND, mockShell.executedCmd)
    }
}
