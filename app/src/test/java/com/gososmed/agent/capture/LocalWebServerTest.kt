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
}
