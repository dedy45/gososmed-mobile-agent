package com.gososmed.agent

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Binary Debug Frames & Transport (PLAN-DETERMINISTIC-ANDROID-PORTAL.md Section 11).
 * Supports bounded queuing, drop-oldest overflow policy, ACK tracking,
 * zero-disk footprint (pure in-memory ByteArrays), and no frame data logging.
 */
object DebugFrameEncoder {

    data class FrameMetadata(
        val binaryMessageId: String,
        val frameSeq: Long,
        val timestampMs: Long,
        val format: String,
        val width: Int,
        val height: Int,
        val quality: Int,
        val byteLength: Int
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("binary_message_id", binaryMessageId)
            put("frame_seq", frameSeq)
            put("timestamp_ms", timestampMs)
            put("format", format)
            put("width", width)
            put("height", height)
            put("quality", quality)
            put("byte_length", byteLength)
        }

        companion object {
            fun fromJson(json: JSONObject): FrameMetadata {
                return FrameMetadata(
                    binaryMessageId = json.getString("binary_message_id"),
                    frameSeq = json.getLong("frame_seq"),
                    timestampMs = json.getLong("timestamp_ms"),
                    format = json.getString("format"),
                    width = json.getInt("width"),
                    height = json.getInt("height"),
                    quality = json.getInt("quality"),
                    byteLength = json.getInt("byte_length")
                )
            }
        }
    }

    data class QueuedFrame(
        val metadata: FrameMetadata,
        val data: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as QueuedFrame
            return metadata == other.metadata && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = metadata.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    data class QueueStats(
        val enqueuedCount: Long,
        val sentCount: Long,
        val ackedCount: Long,
        val droppedCount: Long,
        val currentQueueSize: Int
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("enqueued_count", enqueuedCount)
            put("sent_count", sentCount)
            put("acked_count", ackedCount)
            put("dropped_count", droppedCount)
            put("current_queue_size", currentQueueSize)
        }
    }

    /**
     * In-memory bounded queue with drop-oldest eviction policy and ACK tracking.
     */
    class BoundedFrameQueue(maxCapacity: Int = 5) {
        val maxCapacity: Int = maxCapacity.coerceAtLeast(1)
        private val queue = ArrayDeque<QueuedFrame>()
        private val inFlightAcks = ConcurrentHashMap<String, Long>() // id -> sentTimestamp

        private var enqueuedCount: Long = 0L
        private var sentCount: Long = 0L
        private var ackedCount: Long = 0L
        private var droppedCount: Long = 0L

        @Synchronized
        fun enqueue(data: ByteArray, metadata: FrameMetadata): Boolean {
            enqueuedCount++
            while (queue.size >= maxCapacity) {
                queue.pollFirst()
                droppedCount++
            }
            return queue.offerLast(QueuedFrame(metadata, data))
        }

        @Synchronized
        fun poll(): QueuedFrame? {
            val frame = queue.pollFirst() ?: return null
            sentCount++
            inFlightAcks[frame.metadata.binaryMessageId] = System.currentTimeMillis()
            return frame
        }

        @Synchronized
        fun acknowledge(binaryMessageId: String): Boolean {
            if (inFlightAcks.remove(binaryMessageId) != null) {
                ackedCount++
                return true
            }
            return false
        }

        @Synchronized
        fun evictExpiredAcks(timeoutMs: Long = 10_000L, currentTimeMs: Long = System.currentTimeMillis()): Int {
            val cutoff = currentTimeMs - timeoutMs
            var evicted = 0
            val it = inFlightAcks.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (entry.value < cutoff) {
                    it.remove()
                    evicted++
                }
            }
            return evicted
        }

        fun inFlightAckCount(): Int = inFlightAcks.size

        @Synchronized
        fun stats(): QueueStats {
            return QueueStats(
                enqueuedCount = enqueuedCount,
                sentCount = sentCount,
                ackedCount = ackedCount,
                droppedCount = droppedCount,
                currentQueueSize = queue.size
            )
        }

        @Synchronized
        fun clear() {
            queue.clear()
            inFlightAcks.clear()
        }
    }

    /**
     * Interface for compressing frames into memory ByteArrays without disk writes.
     */
    interface FrameCompressor {
        fun compress(
            sourcePixels: ByteArray,
            width: Int,
            height: Int,
            format: String,
            quality: Int
        ): ByteArray
    }

    /**
     * Pure JVM simulated frame compressor for tests.
     */
    class SimulatedFrameCompressor : FrameCompressor {
        override fun compress(
            sourcePixels: ByteArray,
            width: Int,
            height: Int,
            format: String,
            quality: Int
        ): ByteArray {
            val out = ByteArrayOutputStream()
            // Synthetic header
            val header = "IMG:$format:$width:$height:$quality:".toByteArray(Charsets.UTF_8)
            out.write(header)
            out.write(sourcePixels)
            return out.toByteArray()
        }
    }
}
