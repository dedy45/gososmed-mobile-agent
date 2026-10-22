package com.gososmed.agent.capture

import org.junit.Assert.*
import org.junit.Test

class SpsFixTest {

    @Test
    fun testSplitAnnexB() {
        val stream = byteArrayOf(
            0, 0, 1, 0x67, 1, 2, 3, // NAL 1 (SPS)
            0, 0, 1, 0x68, 4, 5,    // NAL 2 (PPS)
            0, 0, 0, 1, 0x65, 6, 7  // NAL 3 (IDR Frame)
        )
        val nals = SpsFix.split(stream)
        assertEquals(3, nals.size)
        assertEquals(0x67.toByte(), nals[0][0])
        assertEquals(0x68.toByte(), nals[1][0])
        assertEquals(0x65.toByte(), nals[2][0])
    }

    @Test
    fun testNonSpsNaluReturnedUnchanged() {
        val ppsNal = byteArrayOf(0x68.toByte(), 1, 2, 3)
        val res = SpsFix.sps(ppsNal)
        assertArrayEquals(ppsNal, res)
    }

    @Test
    fun testAnnexBOutputFormatting() {
        val stream = byteArrayOf(
            0, 0, 1, 0x68, 1, 2 // PPS
        )
        val res = SpsFix.annexB(stream)
        // Should have 4-byte start code 0, 0, 0, 1 prefix
        assertEquals(0.toByte(), res[0])
        assertEquals(0.toByte(), res[1])
        assertEquals(0.toByte(), res[2])
        assertEquals(1.toByte(), res[3])
        assertEquals(0x68.toByte(), res[4])
    }
}
