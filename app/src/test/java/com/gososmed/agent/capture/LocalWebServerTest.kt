package com.gososmed.agent.capture

import org.junit.Assert.*
import org.junit.Test

class LocalWebServerTest {

    @Test
    fun testComputeWsAcceptStandardRfc6455() {
        // RFC 6455 test vector: Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
        // Expected Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
        val key = "dGhlIHNhbXBsZSBub25jZQ=="
        val accept = LocalWebServer.computeWsAccept(key)
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", accept)
    }

    @Test
    fun testEncodeWsFrameSmallText() {
        val text = "hello"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val frame = LocalWebServer.encodeWsFrame(bytes, isBinary = false)

        assertEquals(0x81.toByte(), frame[0]) // FIN (0x80) | Text (0x01)
        assertEquals(5.toByte(), frame[1])    // Length 5, unmasked
        val payload = frame.copyOfRange(2, frame.size)
        assertEquals(text, String(payload, Charsets.UTF_8))
    }

    @Test
    fun testEncodeWsFrameBinary16BitLength() {
        // 200 bytes triggers 16-bit extended length (126)
        val binaryData = ByteArray(200) { it.toByte() }
        val frame = LocalWebServer.encodeWsFrame(binaryData, isBinary = true)

        assertEquals(0x82.toByte(), frame[0]) // FIN (0x80) | Binary (0x02)
        assertEquals(126.toByte(), frame[1])  // 16-bit extended marker
        assertEquals(0.toByte(), frame[2])    // High byte
        assertEquals(200.toByte(), frame[3])  // Low byte
        assertEquals(204, frame.size)
    }

    @Test
    fun testServerStateInitiallyStopped() {
        assertFalse(LocalWebServer.isRunning)
        assertTrue(LocalWebServer.getLocalUrl().contains(":8765"))
    }

    @Test
    fun testStoragePathConfinementAllowsPublicStorageAndRejectsTraversal() {
        assertTrue(LocalWebServer.isAllowedStoragePath("/sdcard"))
        assertTrue(LocalWebServer.isAllowedStoragePath("/sdcard/DCIM/Camera"))
        assertTrue(LocalWebServer.isAllowedStoragePath("/storage/emulated/0/Download/video.mp4"))

        assertFalse(LocalWebServer.isAllowedStoragePath(""))
        assertFalse(LocalWebServer.isAllowedStoragePath("   "))
        assertFalse(LocalWebServer.isAllowedStoragePath("/sdcard/DCIM/../../etc/passwd"))
        assertFalse(LocalWebServer.isAllowedStoragePath("/data/data/com.gososmed.agent/shared_prefs/agent_prefs.xml"))
        assertFalse(LocalWebServer.isAllowedStoragePath("/etc/passwd"))
        assertFalse(LocalWebServer.isAllowedStoragePath("/sdcard/test\u0000.png"))
    }

    @Test
    fun testSanitizeUploadFilenamePreventsPathTraversal() {
        assertEquals("clip.mp4", LocalWebServer.sanitizeUploadFilename("/tmp/../clip.mp4"))
        assertNull(LocalWebServer.sanitizeUploadFilename(".."))
        assertNull(LocalWebServer.sanitizeUploadFilename("."))
        assertNull(LocalWebServer.sanitizeUploadFilename("   "))
    }

    @Test
    fun testWsFrameLengthBoundsRejectOversizedOrNegativeFrames() {
        assertTrue(LocalWebServer.isValidWsFrameLength(0L))
        assertTrue(LocalWebServer.isValidWsFrameLength(65536L))
        assertFalse(LocalWebServer.isValidWsFrameLength(-1L))
        assertFalse(LocalWebServer.isValidWsFrameLength(65537L))
    }

    @Test
    fun testBoundedFrameQueueDropsOldestDroppableFrameUnderBackpressure() {
        val queue = LocalWebServer.BoundedFrameQueue(maxSize = 3)
        queue.enqueue(byteArrayOf(1), droppable = false) // e.g., SPS/PPS config
        queue.enqueue(byteArrayOf(2), droppable = true)  // older video frame
        queue.enqueue(byteArrayOf(3), droppable = true)  // newer video frame
        queue.enqueue(byteArrayOf(4), droppable = true)  // latest video frame -> must evict frame 2

        val drained = listOf(queue.take(), queue.take(), queue.take()).map { it.data[0].toInt() }
        assertEquals(listOf(1, 3, 4), drained)
    }
}
