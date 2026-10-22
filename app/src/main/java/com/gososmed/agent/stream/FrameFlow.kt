package com.gososmed.agent.stream

import org.json.JSONObject

/**
 * Pure JVM flow controller for 1-in-flight ACK-based live streaming.
 * Enforces:
 *  - At most [maxInFlight] frame in flight (default 1).
 *  - Automatic unblock after [ackTimeoutMs] (default 2000 ms) if ACK was lost.
 *  - Drop-newest policy when downstream consumer/network is slower than scheduler FPS.
 *  - Distinct tracking for metadata sent, binary frames sent, frames acknowledged, and frames dropped.
 */
class FrameFlow(
    val maxInFlight: Int = 1,
    val ackTimeoutMs: Long = 2000L
) {
    private val lock = Any()

    private var inFlightId: String? = null
    private var inFlightSeq: Long? = null
    private var inFlightSentAtMs: Long = 0L

    private var framesMetaSent: Long = 0L
    private var framesSent: Long = 0L
    private var framesAcked: Long = 0L
    private var framesDropped: Long = 0L

    data class Stats(
        val framesMetaSent: Long,
        val framesSent: Long,
        val framesAcked: Long,
        val framesDropped: Long,
        val inFlight: Boolean,
        val inFlightId: String?
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("frames_meta_sent", framesMetaSent)
            put("frames_sent", framesSent)
            put("frames_acked", framesAcked)
            put("frames_dropped", framesDropped)
            put("in_flight", inFlight)
            if (inFlightId != null) {
                put("in_flight_id", inFlightId)
            } else {
                put("in_flight_id", JSONObject.NULL)
            }
        }
    }

    /**
     * Checks if a new frame can be captured/sent at [nowMs].
     * If an in-flight frame exceeded [ackTimeoutMs], it is considered expired and unblocked.
     */
    fun canSend(nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val currentId = inFlightId ?: return true
        val elapsed = nowMs - inFlightSentAtMs
        if (elapsed >= ackTimeoutMs) {
            // ACK timed out -> unblock next frame
            inFlightId = null
            inFlightSeq = null
            inFlightSentAtMs = 0L
            return true
        }
        return false
    }

    fun onMetaSent() = synchronized(lock) {
        framesMetaSent++
    }

    /**
     * Marks a binary frame as sent and occupying the in-flight slot.
     */
    fun onFrameSent(binaryMessageId: String, frameSeq: Long, nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        inFlightId = binaryMessageId
        inFlightSeq = frameSeq
        inFlightSentAtMs = nowMs
        framesSent++
    }

    /**
     * Acknowledges the in-flight frame. Returns true if this ACK matched the active in-flight frame.
     */
    fun onAck(binaryMessageId: String, nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        if (inFlightId == binaryMessageId) {
            inFlightId = null
            inFlightSeq = null
            inFlightSentAtMs = 0L
            framesAcked++
            true
        } else {
            false
        }
    }

    fun onFrameDropped() = synchronized(lock) {
        framesDropped++
    }

    fun stats(): Stats = synchronized(lock) {
        Stats(
            framesMetaSent = framesMetaSent,
            framesSent = framesSent,
            framesAcked = framesAcked,
            framesDropped = framesDropped,
            inFlight = inFlightId != null,
            inFlightId = inFlightId
        )
    }

    fun reset() = synchronized(lock) {
        inFlightId = null
        inFlightSeq = null
        inFlightSentAtMs = 0L
        framesMetaSent = 0L
        framesSent = 0L
        framesAcked = 0L
        framesDropped = 0L
    }
}
