package com.gososmed.agent

import com.gososmed.agent.capture.HevcCodecString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser string codec HEVC.
 *
 * Data uji adalah NALU asli yang direkam dari `c2.qti.hevc.encoder` di
 * Redmi Note 13 Pro 5G (Android 15) lewat paket MSG_CONFIG WebSocket.
 *
 * Hasil yang diharapkan (`hev1.1.00000060.L120.B0`) bukan tebakan: string
 * itulah yang diterima `VideoDecoder.configure()` di Chrome dan menghasilkan
 * gambar bergerak pada viewer port 8766.
 */
class HevcCodecStringTest {

    /** VPS (nal_unit_type 32) — profile Main, level 4.0 (120/30). */
    private val vps = hex(
        "40010c01ffff016000000300b00000030000030078ac0900"
    )

    /** SPS (nal_unit_type 33), 720x1600. */
    private val sps = hex(
        "420101016000000300b00000030000030078a005c200641c4f96bb9324ba9b82830281768509"
    )

    /** PPS (nal_unit_type 34). */
    private val pps = hex("4401c0e30f03b084")

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun annexB(vararg nalus: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (n in nalus) {
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(n)
        }
        return out.toByteArray()
    }

    @Test
    fun `menghasilkan string codec yang sama dengan yang diterima browser`() {
        val s = HevcCodecString.fromAnnexB(annexB(vps, sps, pps))
        assertEquals("hev1.1.00000060.L120.B0", s)
    }

    @Test
    fun `memakai hev1 bukan hvc1 karena parameter set dikirim in-band`() {
        val s = HevcCodecString.fromAnnexB(annexB(vps, sps, pps))
        assertTrue("harus hev1, bukan hvc1: $s", s!!.startsWith("hev1."))
    }

    @Test
    fun `urutan NALU bebas - VPS dan SPS dicari berdasarkan tipe`() {
        val s = HevcCodecString.fromAnnexB(annexB(sps, pps, vps))
        assertEquals("hev1.1.00000060.L120.B0", s)
    }

    @Test
    fun `mengembalikan null bila VPS tidak ada`() {
        assertNull(HevcCodecString.fromAnnexB(annexB(sps, pps)))
    }

    @Test
    fun `mengembalikan null bila SPS tidak ada`() {
        assertNull(HevcCodecString.fromAnnexB(annexB(vps)))
    }

    @Test
    fun `mengembalikan null untuk data kosong atau sampah`() {
        assertNull(HevcCodecString.fromAnnexB(ByteArray(0)))
        assertNull(HevcCodecString.fromAnnexB(byteArrayOf(1, 2, 3, 4, 5)))
    }
}
