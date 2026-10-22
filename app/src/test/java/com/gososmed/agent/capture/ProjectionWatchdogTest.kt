package com.gososmed.agent.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionWatchdogTest {

    @Test
    fun `not stalled before the first frame`() {
        val wd = ProjectionWatchdog(stallThresholdMs = 3000)
        assertFalse(wd.isStalled(nowMs = 100_000))
    }

    @Test
    fun `not stalled within threshold`() {
        val wd = ProjectionWatchdog(stallThresholdMs = 3000)
        wd.onFrame(nowMs = 1000)
        assertFalse(wd.isStalled(nowMs = 2000))
        assertFalse(wd.isStalled(nowMs = 4000)) // exactly at threshold, not beyond
    }

    @Test
    fun `stalled after threshold`() {
        val wd = ProjectionWatchdog(stallThresholdMs = 3000)
        wd.onFrame(nowMs = 1000)
        assertTrue(wd.isStalled(nowMs = 5000))
    }

    @Test
    fun `recovers when frames resume`() {
        val wd = ProjectionWatchdog(stallThresholdMs = 3000)
        wd.onFrame(nowMs = 1000)
        assertTrue(wd.isStalled(nowMs = 5000))
        wd.onFrame(nowMs = 5100)
        assertFalse(wd.isStalled(nowMs = 5200))
    }

    @Test
    fun `reset clears stall without adding a frame`() {
        val wd = ProjectionWatchdog(stallThresholdMs = 3000)
        wd.onFrame(nowMs = 1000)
        assertTrue(wd.isStalled(nowMs = 5000))
        wd.reset(nowMs = 5000)
        assertFalse(wd.isStalled(nowMs = 5500))
        assertEquals(1L, wd.totalFrames())
    }

    @Test
    fun `counts frames`() {
        val wd = ProjectionWatchdog()
        repeat(5) { wd.onFrame(nowMs = it * 10L) }
        assertEquals(5L, wd.totalFrames())
    }

    @Test
    fun `reports timestamp of the last frame`() {
        val wd = ProjectionWatchdog()
        wd.onFrame(nowMs = 1234)
        assertEquals(1234L, wd.lastFrameAt())
        wd.onFrame(nowMs = 5678)
        assertEquals(5678L, wd.lastFrameAt())
    }
}
