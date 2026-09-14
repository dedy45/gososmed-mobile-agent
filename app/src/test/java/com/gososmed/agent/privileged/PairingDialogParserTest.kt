package com.gososmed.agent.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingDialogParserTest {

    @Test
    fun `parses AOSP pairing dialog in English`() {
        val snapshot = PairingDialogParser.parse(
            listOf(
                "Pair with device",
                "Pairing code",
                "123456",
                "IP address & Port",
                "192.168.1.10:42137",
            )
        )

        assertTrue(snapshot.isComplete)
        assertEquals(42137, snapshot.port)
        assertEquals("123456", snapshot.code)
    }

    @Test
    fun `parses Indonesian pairing dialog`() {
        val snapshot = PairingDialogParser.parse(
            listOf(
                "Sambungkan ke perangkat",
                "Kode pairing",
                "654321",
                "Alamat IP & Port",
                "192.168.43.1:39077",
            )
        )

        assertTrue(snapshot.isComplete)
        assertEquals(39077, snapshot.port)
        assertEquals("654321", snapshot.code)
    }

    @Test
    fun `parses grouped and spaced code formats`() {
        val grouped = PairingDialogParser.parse(
            listOf("192.168.1.2:37123", "123 456")
        )
        assertEquals("123456", grouped.code)

        val spaced = PairingDialogParser.parse(
            listOf("192.168.1.2:37123", "6 5 4 3 2 1")
        )
        assertEquals("654321", spaced.code)
    }

    @Test
    fun `does not confuse six digit pairing port with code`() {
        val snapshot = PairingDialogParser.parse(
            listOf(
                "192.168.1.2:61234",
                "Kode pairing",
                "456789",
            )
        )

        assertEquals(61234, snapshot.port)
        assertEquals("456789", snapshot.code)
    }

    @Test
    fun `rejects main wireless debugging screen connect port`() {
        val snapshot = PairingDialogParser.parse(
            listOf(
                "Debug nirkabel",
                "IP address & Port",
                "192.168.1.10:45555",
                "Perangkat tersambung",
            )
        )

        assertFalse(snapshot.hasPort)
        assertNull(snapshot.code)
        assertFalse(snapshot.isComplete)
    }

    @Test
    fun `rejects invalid port`() {
        val snapshot = PairingDialogParser.parse(
            listOf("192.168.1.10:123456", "123456")
        )

        assertFalse(snapshot.hasPort)
        assertFalse(snapshot.isComplete)
    }

    @Test
    fun `does not mix connect port from main window with pairing code window`() {
        val snapshot = PairingDialogParser.parseWindows(
            listOf(
                listOf("Debug nirkabel", "IP address & Port", "192.168.1.10:45555"),
                listOf("Pair with device", "Pairing code", "123456"),
            )
        )

        assertFalse(snapshot.hasPort)
        assertFalse(snapshot.isComplete)
    }

    @Test
    fun `uses pairing port from same window as code`() {
        val snapshot = PairingDialogParser.parseWindows(
            listOf(
                listOf("Debug nirkabel", "IP address & Port", "192.168.1.10:45555"),
                listOf("Pair with device", "192.168.1.10:42137", "123456"),
            )
        )

        assertTrue(snapshot.isComplete)
        assertEquals(42137, snapshot.port)
        assertEquals("123456", snapshot.code)
    }
}
