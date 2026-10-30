package com.gososmed.agent.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class AdbPortResolverTest {

    @Test
    fun `buildMdnsQuery generates valid DNS PTR question`() {
        val query = AdbPortResolver.buildMdnsQuery("_adb-tls-connect._tcp.local")
        assertNotNull(query)
        assertTrue(query.size > 20)

        // Verifikasi ID = 0, Flags = 0, Questions = 1
        assertEquals(0, query[0].toInt())
        assertEquals(0, query[1].toInt())
        assertEquals(0, query[2].toInt())
        assertEquals(0, query[3].toInt())
        assertEquals(0, query[4].toInt())
        assertEquals(1, query[5].toInt())
    }

    @Test
    fun `extractPortFromMdnsPayload parses SRV record correctly`() {
        val testPort = 39987
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)

        // Header / Padding palsu
        dos.write(ByteArray(10) { 0x00 })

        // SRV Record Header:
        // Type: 0x0021 (33)
        dos.writeShort(0x0021)
        // Class: 0x8001 (IN dengan cache flush bit)
        dos.writeShort(0x8001.toInt())
        // TTL: 120 detik
        dos.writeInt(120)
        // Data length: 12 bytes (Priority: 2, Weight: 2, Port: 2, Target: 6)
        dos.writeShort(12)
        // RDATA:
        dos.writeShort(0) // Priority
        dos.writeShort(0) // Weight
        dos.writeShort(testPort) // Port
        dos.write(byteArrayOf(0x04, 't'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(), 0x00))

        val payload = bos.toByteArray()
        val extracted = AdbPortResolver.extractPortFromMdnsPayload(payload, payload.size)

        assertNotNull(extracted)
        assertEquals(testPort, extracted)
    }

    @Test
    fun `extractPortFromMdnsPayload returns null on invalid or truncated data`() {
        val emptyPayload = ByteArray(10)
        assertNull(AdbPortResolver.extractPortFromMdnsPayload(emptyPayload, emptyPayload.size))

        // SRV type tapi data length terpotong
        val truncated = byteArrayOf(0x00, 0x21, 0x00, 0x01, 0x00, 0x00, 0x00, 0x78, 0x00, 0x08)
        assertNull(AdbPortResolver.extractPortFromMdnsPayload(truncated, truncated.size))
    }
}
