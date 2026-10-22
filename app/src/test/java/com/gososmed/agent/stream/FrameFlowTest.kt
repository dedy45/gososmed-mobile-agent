package com.gososmed.agent.stream

import org.junit.Assert.*
import org.junit.Test

class FrameFlowTest {

    @Test
    fun testNormalSendAndAckCycle() {
        val flow = FrameFlow(maxInFlight = 1, ackTimeoutMs = 2000L)
        val t0 = 1000L

        assertTrue(flow.canSend(t0))

        flow.onMetaSent()
        flow.onFrameSent(binaryMessageId = "frame-1", frameSeq = 1L, nowMs = t0)

        assertFalse("Cannot send another frame while 1 in flight", flow.canSend(t0 + 100))

        val statsInFlight = flow.stats()
        assertEquals(1L, statsInFlight.framesMetaSent)
        assertEquals(1L, statsInFlight.framesSent)
        assertEquals(0L, statsInFlight.framesAcked)
        assertTrue(statsInFlight.inFlight)
        assertEquals("frame-1", statsInFlight.inFlightId)

        val acked = flow.onAck("frame-1", t0 + 200)
        assertTrue(acked)

        val statsAcked = flow.stats()
        assertEquals(1L, statsAcked.framesAcked)
        assertFalse(statsAcked.inFlight)
        assertNull(statsAcked.inFlightId)

        assertTrue("Can send next frame after ACK", flow.canSend(t0 + 300))
    }

    @Test
    fun testDropNewestWhenBlocked() {
        val flow = FrameFlow(maxInFlight = 1, ackTimeoutMs = 2000L)
        val t0 = 1000L

        assertTrue(flow.canSend(t0))
        flow.onFrameSent("frame-1", 1L, t0)

        // Simulate 3 ticks while frame-1 is still in flight
        for (i in 1..3) {
            val now = t0 + (i * 200)
            if (!flow.canSend(now)) {
                flow.onFrameDropped()
            }
        }

        val stats = flow.stats()
        assertEquals(1L, stats.framesSent)
        assertEquals(3L, stats.framesDropped)
        assertEquals(0L, stats.framesAcked)
    }

    @Test
    fun testAckTimeoutUnblocksNextFrame() {
        val flow = FrameFlow(maxInFlight = 1, ackTimeoutMs = 2000L)
        val t0 = 1000L

        assertTrue(flow.canSend(t0))
        flow.onFrameSent("frame-1", 1L, t0)

        assertFalse(flow.canSend(t0 + 1999L))
        assertTrue("Must unblock after 2000ms timeout", flow.canSend(t0 + 2000L))

        // Next frame can now be sent
        flow.onFrameSent("frame-2", 2L, t0 + 2100L)
        assertFalse(flow.canSend(t0 + 2200L))

        val ack2 = flow.onAck("frame-2", t0 + 2300L)
        assertTrue(ack2)
        assertTrue(flow.canSend(t0 + 2400L))
    }

    @Test
    fun testUnknownAckIgnored() {
        val flow = FrameFlow(maxInFlight = 1, ackTimeoutMs = 2000L)
        val t0 = 1000L

        flow.onFrameSent("frame-1", 1L, t0)
        val ackUnknown = flow.onAck("non-existent-frame", t0 + 100L)
        assertFalse(ackUnknown)

        val stats = flow.stats()
        assertEquals(0L, stats.framesAcked)
        assertTrue(stats.inFlight)
        assertEquals("frame-1", stats.inFlightId)
    }

    @Test
    fun testStatsJsonSerializationAndReset() {
        val flow = FrameFlow()
        flow.onMetaSent()
        flow.onFrameSent("frame-1", 1L)
        flow.onFrameDropped()

        val json = flow.stats().toJson()
        assertEquals(1L, json.getLong("frames_meta_sent"))
        assertEquals(1L, json.getLong("frames_sent"))
        assertEquals(0L, json.getLong("frames_acked"))
        assertEquals(1L, json.getLong("frames_dropped"))
        assertTrue(json.getBoolean("in_flight"))
        assertEquals("frame-1", json.getString("in_flight_id"))

        flow.reset()
        val resetStats = flow.stats()
        assertEquals(0L, resetStats.framesSent)
        assertEquals(0L, resetStats.framesDropped)
        assertFalse(resetStats.inFlight)
    }
}
