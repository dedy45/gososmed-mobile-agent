package com.gososmed.agent.stream

import com.gososmed.agent.AgentAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Manages a single active live streaming session.
 * Enforces:
 *  - Single-session concurrency guard (rejects new session with device_busy if another session is active).
 *  - Preflight screenshot capture check before returning start success.
 *  - Deterministic 1-2 FPS scheduler loop.
 *  - 1-in-flight flow control via [FrameFlow] (drop-newest policy, auto-unblock on ACK timeout).
 *  - Two-message protocol emission: text [frame_meta] JSON followed by raw JPEG byte array.
 */
class LiveFrameSession(
    private val captureProvider: FrameCaptureProvider,
    private val frameSender: FrameSender,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    val flow: FrameFlow = FrameFlow(maxInFlight = 1, ackTimeoutMs = 2000L)
) {
    fun interface FrameCaptureProvider {
        suspend fun capture(): AgentAccessibilityService.RawScreenshotResult
    }

    fun interface FrameSender {
        suspend fun sendFrame(metaJson: JSONObject, rawBytes: ByteArray)
    }

    data class SessionConfig(
        val sessionId: String,
        val fps: Double = DEFAULT_FPS,
        val format: String = NormalizedImageConfig.FORMAT_JPEG,
        val quality: Int = NormalizedImageConfig.DEFAULT_QUALITY,
        val maxWidth: Int = NormalizedImageConfig.DEFAULT_MAX_WIDTH
    ) {
        companion object {
            const val DEFAULT_FPS = 1.5
            const val MIN_FPS = 0.5
            const val MAX_FPS = 2.0
        }
    }

    data class StartResult(
        val ok: Boolean,
        val sessionId: String,
        val reasonCode: String? = null,
        val error: String? = null
    )

    private val lock = Any()
    private var activeConfig: SessionConfig? = null
    private var loopJob: Job? = null
    private var currentFrameSeq = 0L

    val isRunning: Boolean
        get() = synchronized(lock) {
            activeConfig != null && loopJob?.isActive == true
        }

    val activeSessionId: String?
        get() = synchronized(lock) {
            activeConfig?.sessionId
        }

    fun stats(): FrameFlow.Stats = flow.stats()

    suspend fun start(config: SessionConfig): StartResult {
        // 1. Concurrency guard: check if another session is actively running
        synchronized(lock) {
            val current = activeConfig
            if (current != null && loopJob?.isActive == true) {
                if (current.sessionId == config.sessionId) {
                    return StartResult(ok = true, sessionId = current.sessionId)
                } else {
                    return StartResult(
                        ok = false,
                        sessionId = config.sessionId,
                        reasonCode = "device_busy",
                        error = "Active streaming session '${current.sessionId}' is in progress"
                    )
                }
            }
        }

        // 2. Preflight frame capture before confirming start
        val preflight = captureProvider.capture()
        val preflightBytes = preflight.bytes
        if (preflightBytes == null) {
            return StartResult(
                ok = false,
                sessionId = config.sessionId,
                reasonCode = "screenshot_failed",
                error = preflight.error ?: "Preflight screenshot capture failed"
            )
        }

        val firstBinMsgId: String
        val firstSeq: Long
        val firstMeta: JSONObject
        val frameIntervalMs: Long

        synchronized(lock) {
            // Re-check after suspend capture in case stop() was called during capture
            val current = activeConfig
            if (current != null && loopJob?.isActive == true && current.sessionId != config.sessionId) {
                return StartResult(
                    ok = false,
                    sessionId = config.sessionId,
                    reasonCode = "device_busy",
                    error = "Another streaming session '${current.sessionId}' started during preflight"
                )
            }

            loopJob?.cancel()
            flow.reset()
            activeConfig = config
            currentFrameSeq = 0L

            currentFrameSeq++
            firstSeq = currentFrameSeq
            firstBinMsgId = "${config.sessionId}:$firstSeq"

            firstMeta = createFrameMeta(
                sessionId = config.sessionId,
                binMsgId = firstBinMsgId,
                frameSeq = firstSeq,
                format = config.format,
                rawResult = preflight,
                bytes = preflightBytes,
                quality = config.quality
            )

            val clampedFps = config.fps.coerceIn(SessionConfig.MIN_FPS, SessionConfig.MAX_FPS)
            frameIntervalMs = (1000.0 / clampedFps).toLong().coerceIn(500L, 2000L)
        }

        // Send initial preflight frame
        flow.onMetaSent()
        frameSender.sendFrame(firstMeta, preflightBytes)
        flow.onFrameSent(firstBinMsgId, firstSeq)

        // Launch scheduler coroutine loop
        synchronized(lock) {
            loopJob = scope.launch {
                try {
                    while (isActive) {
                        delay(frameIntervalMs)
                        if (!isActive) break

                        if (!flow.canSend()) {
                            flow.onFrameDropped()
                            continue
                        }

                        val res = captureProvider.capture()
                        val bytes = res.bytes
                        if (bytes == null) {
                            flow.onFrameDropped()
                            continue
                        }

                        val (seq, binMsgId, meta) = synchronized(lock) {
                            if (!isActive || activeConfig?.sessionId != config.sessionId) {
                                return@launch
                            }
                            if (!flow.canSend()) {
                                flow.onFrameDropped()
                                return@launch
                            }
                            currentFrameSeq++
                            val nextSeq = currentFrameSeq
                            val nextBinMsgId = "${config.sessionId}:$nextSeq"
                            val frameMeta = createFrameMeta(
                                sessionId = config.sessionId,
                                binMsgId = nextBinMsgId,
                                frameSeq = nextSeq,
                                format = config.format,
                                rawResult = res,
                                bytes = bytes,
                                quality = config.quality
                            )
                            Triple(nextSeq, nextBinMsgId, frameMeta)
                        }

                        flow.onMetaSent()
                        frameSender.sendFrame(meta, bytes)
                        flow.onFrameSent(binMsgId, seq)
                    }
                } catch (e: CancellationException) {
                    // Normal coroutine cancellation
                } catch (t: Throwable) {
                    // Guard against loop crashes
                }
            }
        }

        return StartResult(ok = true, sessionId = config.sessionId)
    }

    fun onAck(binaryMessageId: String): Boolean {
        return flow.onAck(binaryMessageId)
    }

    fun stop(sessionId: String? = null): Boolean = synchronized(lock) {
        if (sessionId != null && activeConfig?.sessionId != sessionId) {
            return false
        }
        loopJob?.cancel()
        loopJob = null
        activeConfig = null
        flow.reset()
        currentFrameSeq = 0L
        return true
    }

    private fun createFrameMeta(
        sessionId: String,
        binMsgId: String,
        frameSeq: Long,
        format: String,
        rawResult: AgentAccessibilityService.RawScreenshotResult,
        bytes: ByteArray,
        quality: Int
    ): JSONObject {
        return JSONObject().apply {
            put("type", "frame_meta")
            put("session_id", sessionId)
            put("binary_message_id", binMsgId)
            put("frame_seq", frameSeq)
            put("format", format)
            put("width", rawResult.width)
            put("height", rawResult.height)
            put("source_width", rawResult.sourceWidth)
            put("source_height", rawResult.sourceHeight)
            put("source_scale", rawResult.sourceScale.toDouble())
            put("quality", quality)
            put("byte_length", bytes.size)
            put("timestamp_ms", System.currentTimeMillis())
        }
    }
}
