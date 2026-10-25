package com.gososmed.agent.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemDisplayControllerTest {

    @Test
    fun testParseWmSizeWithOverride() {
        val output = "Physical size: 1220x2712\nOverride size: 1080x2400"
        val res = SystemDisplayController.parseWmSize(output)
        assertEquals("1080x2400", res.current)
        assertTrue(res.isOverridden)
        assertEquals("1220x2712", res.physical)
    }

    @Test
    fun testParseWmSizeWithoutOverride() {
        val output = "Physical size: 1080x1920\n"
        val res = SystemDisplayController.parseWmSize(output)
        assertEquals("1080x1920", res.current)
        assertFalse(res.isOverridden)
        assertEquals("1080x1920", res.physical)
    }

    @Test
    fun testParseWmDensityWithOverride() {
        val output = "Physical density: 440\nOverride density: 400"
        val res = SystemDisplayController.parseWmDensity(output)
        assertEquals("400", res.current)
        assertTrue(res.isOverridden)
        assertEquals("440", res.physical)
    }

    @Test
    fun testParseWmDensityWithoutOverride() {
        val output = "Physical density: 480"
        val res = SystemDisplayController.parseWmDensity(output)
        assertEquals("480", res.current)
        assertFalse(res.isOverridden)
        assertEquals("480", res.physical)
    }

    @Test
    fun testExecutionWithMockShell() {
        val executed = mutableListOf<String>()
        val mockShell = object : PrivilegedShell {
            override fun status() = ShellStatus(available = true, paired = true, connected = true, uid = 2000, error = "")
            override fun exec(command: String, timeoutMs: Long): ShellResult {
                executed.add(command)
                return ShellResult(ok = true, exitCode = 0, stdout = "Success", stderr = "")
            }
        }

        assertTrue(SystemDisplayController.sleepScreen(mockShell))
        assertEquals("input keyevent 223", executed.last())

        assertTrue(SystemDisplayController.wakeScreen(mockShell))
        assertEquals("input keyevent 224", executed.last())

        assertTrue(SystemDisplayController.restartSystemUi(mockShell))
        assertEquals("pkill -f com.android.systemui", executed.last())

        assertTrue(SystemDisplayController.setDisplaySize(1080, 2400, mockShell))
        assertEquals("wm size 1080x2400", executed.last())

        assertTrue(SystemDisplayController.resetDisplaySize(mockShell))
        assertEquals("wm size reset", executed.last())

        assertTrue(SystemDisplayController.setDensity(400, mockShell))
        assertEquals("wm density 400", executed.last())

        assertTrue(SystemDisplayController.resetDensity(mockShell))
        assertEquals("wm density reset", executed.last())
    }
}
