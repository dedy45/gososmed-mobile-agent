package com.gososmed.agent.stream

import com.gososmed.agent.AgentAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LiveFrameSessionTest {

    private val dummyJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

    @Test
    fun testStartSuccessfulAndPreflightFrameSent() {
        runBlocking {
            val captureProvider = LiveFrameSession.FrameCaptureProvider {
                AgentAccessibilityService.RawScreenshotResult(
                    bytes = dummyJpeg,
                    width = 480,
                    height = 800,
                    sourceWidth = 1080,
                    sourceHeight = 2400,
                    sourceScale = 0.4444f
                )
            }

            val sentMetas = CopyOnWriteArrayList<JSONObject>()
            val sentBytes = CopyOnWriteArrayList<ByteArray>()
            val frameSender = LiveFrameSession.FrameSender { metaJson, rawBytes ->
                sentMetas.add(metaJson)
                sentBytes.add(rawBytes)
            }

            val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val session = LiveFrameSession(
                captureProvider = captureProvider,
                frameSender = frameSender,
                scope = testScope,
                flow = FrameFlow(maxInFlight = 1, ackTimeoutMs = 2000L)
            )

            val config = LiveFrameSession.SessionConfig(
                sessionId = "sess_test_1",
                fps = 1.0
            )

            val startRes = session.start(config)
            assertTrue(startRes.ok)
            assertEquals("sess_test_1", startRes.sessionId)
            assertTrue(session.isRunning)
            assertEquals("sess_test_1", session.activeSessionId)

            // Preflight frame should have been sent synchronously during start()
            assertEquals(1, sentMetas.size)
            assertEquals(1, sentBytes.size)

            val meta = sentMetas[0]
            assertEquals("frame_meta", meta.getString("type"))
            assertEquals("sess_test_1", meta.getString("session_id"))
            assertEquals("sess_test_1:1", meta.getString("binary_message_id"))
            assertEquals(1L, meta.getLong("frame_seq"))
            assertEquals("jpeg", meta.getString("format"))
            assertEquals(480, meta.getInt("width"))
            assertEquals(800, meta.getInt("height"))
            assertEquals(1080, meta.getInt("source_width"))
            assertEquals(2400, meta.getInt("source_height"))
            assertEquals(4, meta.getInt("byte_length"))

            // Stats check
            val stats = session.stats()
            assertEquals(1L, stats.framesMetaSent)
            assertEquals(1L, stats.framesSent)
            assertEquals(0L, stats.framesAcked)
            assertTrue(stats.inFlight)
            assertEquals("sess_test_1:1", stats.inFlightId)

            session.stop()
            assertFalse(session.isRunning)
            assertNull(session.activeSessionId)
        }
    }

    @Test
    fun testDeviceBusyWhenAnotherSessionActive() {
        runBlocking {
            val captureProvider = LiveFrameSession.FrameCaptureProvider {
                AgentAccessibilityService.RawScreenshotResult(
                    bytes = dummyJpeg,
                    width = 480,
                    height = 800
                )
            }
            val frameSender = LiveFrameSession.FrameSender { _, _ -> }

            val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val session = LiveFrameSession(
                captureProvider = captureProvider,
                frameSender = frameSender,
                scope = testScope
            )

            val res1 = session.start(LiveFrameSession.SessionConfig(sessionId = "sess_1"))
            assertTrue(res1.ok)

            val res2 = session.start(LiveFrameSession.SessionConfig(sessionId = "sess_2"))
            assertFalse(res2.ok)
            assertEquals("device_busy", res2.reasonCode)
            assertEquals("sess_2", res2.sessionId)

            session.stop()
        }
    }

    @Test
    fun testIdempotentStartWithSameSessionId() {
        runBlocking {
            val captureProvider = LiveFrameSession.FrameCaptureProvider {
                AgentAccessibilityService.RawScreenshotResult(
                    bytes = dummyJpeg,
                    width = 480,
                    height = 800
                )
            }
            val frameSender = LiveFrameSession.FrameSender { _, _ -> }

            val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val session = LiveFrameSession(
                captureProvider = captureProvider,
                frameSender = frameSender,
                scope = testScope
            )

            val res1 = session.start(LiveFrameSession.SessionConfig(sessionId = "sess_1"))
            assertTrue(res1.ok)

            val res2 = session.start(LiveFrameSession.SessionConfig(sessionId = "sess_1"))
            assertTrue(res2.ok)
            assertEquals("sess_1", res2.sessionId)

            session.stop()
        }
    }

    @Test
    fun testPreflightFailureReturnsScreenshotFailed() {
        runBlocking {
            val captureProvider = LiveFrameSession.FrameCaptureProvider {
                AgentAccessibilityService.RawScreenshotResult(
                    bytes = null,
                    width = 0,
                    height = 0,
                    error = "hardware buffer null"
                )
            }
            val frameSender = LiveFrameSession.FrameSender { _, _ -> }

            val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val session = LiveFrameSession(
                captureProvider = captureProvider,
                frameSender = frameSender,
                scope = testScope
            )

            val res = session.start(LiveFrameSession.SessionConfig(sessionId = "sess_err"))
            assertFalse(res.ok)
            assertEquals("screenshot_failed", res.reasonCode)
            assertEquals("hardware buffer null", res.error)
            assertFalse(session.isRunning)
        }
    }

    @Test
    fun testAckRoutingAndStats() {
        runBlocking {
            val captureProvider = LiveFrameSession.FrameCaptureProvider {
                AgentAccessibilityService.RawScreenshotResult(
                    bytes = dummyJpeg,
                    width = 480,
                    height = 800
                )
            }

            val frameSender = LiveFrameSession.FrameSender { _, _ -> }
            val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val session = LiveFrameSession(
                captureProvider = captureProvider,
                frameSender = frameSender,
                scope = testScope
            )

            session.start(LiveFrameSession.SessionConfig(sessionId = "sess_ack"))
            assertEquals("sess_ack:1", session.stats().inFlightId)
            assertEquals(0L, session.stats().framesAcked)

            val ackResult = session.onAck("sess_ack:1")
            assertTrue(ackResult)
            assertEquals(1L, session.stats().framesAcked)
            assertFalse(session.stats().inFlight)

            session.stop()
        }
    }

    @Test
    fun testStopWithSpecificSessionId() {
        runBlocking {
            val captureProvider = LiveFrameSession.FrameCaptureProvider {
                AgentAccessibilityService.RawScreenshotResult(
                    bytes = dummyJpeg,
                    width = 480,
                    height = 800
                )
            }
            val frameSender = LiveFrameSession.FrameSender { _, _ -> }

            val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val session = LiveFrameSession(
                captureProvider = captureProvider,
                frameSender = frameSender,
                scope = testScope
            )

            session.start(LiveFrameSession.SessionConfig(sessionId = "active_sess"))

            // Stop with mismatching ID should return false and not stop
            val stoppedMismatch = session.stop(sessionId = "other_sess")
            assertFalse(stoppedMismatch)
            assertTrue(session.isRunning)

            // Stop with matching ID should succeed
            val stoppedMatch = session.stop(sessionId = "active_sess")
            assertTrue(stoppedMatch)
            assertFalse(session.isRunning)
        }
    }

    @Test
    fun testSchedulerLoopEmitsSubsequentFramesAfterAck() {
        runBlocking {
            val secondFrameLatch = CountDownLatch(2)
            val sentMetas = CopyOnWriteArrayList<JSONObject>()

            val captureProvider = LiveFrameSession.FrameCaptureProvider {
                AgentAccessibilityService.RawScreenshotResult(
                    bytes = dummyJpeg,
                    width = 480,
                    height = 800
                )
            }

            val frameSender = LiveFrameSession.FrameSender { meta, _ ->
                sentMetas.add(meta)
                secondFrameLatch.countDown()
            }

            val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val session = LiveFrameSession(
                captureProvider = captureProvider,
                frameSender = frameSender,
                scope = testScope
            )

            // fps = 2.0 -> interval = 500ms
            session.start(LiveFrameSession.SessionConfig(sessionId = "sess_loop", fps = 2.0))
            assertEquals(1, sentMetas.size) // preflight frame sess_loop:1

            // ACK frame 1 immediately to allow frame 2
            session.onAck("sess_loop:1")

            // Wait up to 2500ms for second frame to be sent by scheduler
            val completed = secondFrameLatch.await(2500, TimeUnit.MILLISECONDS)
            assertTrue("Scheduler should have sent second frame after ACK", completed)
            assertEquals(2, sentMetas.size)
            assertEquals("sess_loop:2", sentMetas[1].getString("binary_message_id"))

            session.stop()
        }
    }
}
