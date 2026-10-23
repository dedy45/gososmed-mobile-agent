package com.gososmed.agent.capture

import java.io.ByteArrayOutputStream

/**
 * Modifies H.264 SPS NALUs to declare "no frame reordering" (VUI bitstream_restriction
 * with max_num_reorder_frames = 0).
 * Prevents browser VideoDecoder (WebCodecs) from holding an 8-10 frame (~250ms) reorder buffer.
 * Adopted from vitRemote/scrcpy low-latency standards.
 */
object SpsFix {

    /**
     * @param rewriteSps true hanya untuk H.264. Untuk HEVC, `nal[0] and 0x1f`
     *   bukan tipe NALU (HEVC memakai 6 bit: `(b shr 1) and 0x3F`), sehingga
     *   NALU lain seperti AUD (35) akan salah dianggap SPS dan dirusak.
     */
    fun annexB(data: ByteArray, rewriteSps: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream(data.size + 16)
        for (nal in split(data)) {
            out.write(byteArrayOf(0, 0, 0, 1))
            val isH264Sps = rewriteSps && nal.isNotEmpty() && nal[0].toInt() and 0x1f == 7
            out.write(if (isH264Sps) sps(nal) else nal)
        }
        return out.toByteArray()
    }

    fun split(d: ByteArray): List<ByteArray> {
        val res = ArrayList<ByteArray>()
        var start = -1
        var i = 0
        while (i + 2 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) {
                if (start >= 0) {
                    var e = i
                    while (e > start && d[e - 1].toInt() == 0) e--
                    res.add(d.copyOfRange(start, e))
                }
                start = i + 3
                i += 3
            } else {
                i++
            }
        }
        if (start >= 0) res.add(d.copyOfRange(start, d.size))
        return res
    }

    fun sps(nal: ByteArray): ByteArray = try { rewrite(nal) } catch (_: Exception) { nal }

    private fun rewrite(nal: ByteArray): ByteArray {
        val rbsp = unescape(nal, 1)
        val r = BitReader(rbsp)
        val w = BitWriter()
        fun u(n: Int) = r.u(n).also { w.u(it, n) }
        fun ue() = r.ue().also { w.ue(it) }
        fun se() = r.se().also { w.se(it) }

        val profile = u(8)
        u(8)
        u(8)
        ue()
        if (profile in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            if (ue() == 3) u(1)
            ue()
            ue()
            u(1)
            if (u(1) == 1) return nal
        }
        ue()
        when (ue()) {
            0 -> ue()
            1 -> {
                u(1)
                se()
                se()
                repeat(ue()) { se() }
            }
        }
        val maxRefFrames = ue()
        u(1)
        ue()
        ue()
        if (u(1) == 0) u(1)
        u(1)
        if (u(1) == 1) {
            ue()
            ue()
            ue()
            ue()
        }

        if (r.u(1) == 0) {
            w.u(1, 1)
            w.u(0, 1)
            w.u(0, 1)
            w.u(0, 1)
            w.u(0, 1)
            w.u(0, 1)
            w.u(0, 1)
            w.u(0, 1)
            w.u(0, 1)
            restriction(w, maxRefFrames)
        } else {
            w.u(1, 1)
            if (u(1) == 1 && u(8) == 255) {
                u(16)
                u(16)
            }
            if (u(1) == 1) u(1)
            if (u(1) == 1) {
                u(3)
                u(1)
                if (u(1) == 1) {
                    u(8)
                    u(8)
                    u(8)
                }
            }
            if (u(1) == 1) {
                ue()
                ue()
            }
            if (u(1) == 1) {
                u(32)
                u(32)
                u(1)
            }
            val nalHrd = u(1)
            val vclHrd = u(1)
            if (nalHrd == 1 || vclHrd == 1) return nal
            u(1)
            if (r.u(1) == 1) {
                r.u(1)
                r.ue()
                r.ue()
                r.ue()
                r.ue()
                r.ue()
                r.ue()
            }
            restriction(w, maxRefFrames)
        }
        w.trailing()
        return byteArrayOf(nal[0]) + escape(w.bytes())
    }

    private fun restriction(w: BitWriter, maxRefFrames: Int) {
        w.u(1, 1)          // bitstream_restriction_flag = 1
        w.u(1, 1)          // motion_vectors_over_pic_boundaries_flag
        w.ue(0)            // max_bytes_per_pic_denom
        w.ue(0)            // max_bits_per_mb_denom
        w.ue(16)           // log2_max_mv_length_horizontal
        w.ue(16)           // log2_max_mv_length_vertical
        w.ue(0)            // max_num_reorder_frames = 0 (Eliminates 250ms delay)
        w.ue(maxOf(1, maxRefFrames))
    }

    private fun unescape(d: ByteArray, from: Int): ByteArray {
        val o = ByteArrayOutputStream(d.size)
        var zeros = 0
        for (i in from until d.size) {
            val b = d[i].toInt() and 0xff
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            o.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return o.toByteArray()
    }

    private fun escape(d: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(d.size + 8)
        var zeros = 0
        for (x in d) {
            val b = x.toInt() and 0xff
            if (zeros >= 2 && b <= 3) {
                o.write(3)
                zeros = 0
            }
            o.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return o.toByteArray()
    }

    private class BitReader(private val d: ByteArray) {
        private var pos = 0
        fun bit(): Int {
            val v = (d[pos shr 3].toInt() shr (7 - (pos and 7))) and 1
            pos++
            return v
        }
        fun u(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or bit() }
            return v
        }
        fun ue(): Int {
            var z = 0
            while (bit() == 0) z++
            return (1 shl z) - 1 + u(z)
        }
        fun se(): Int {
            val k = ue()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }
    }

    private class BitWriter {
        private val o = ByteArrayOutputStream()
        private var cur = 0
        private var n = 0
        fun bit(b: Int) {
            cur = (cur shl 1) or (b and 1)
            n++
            if (n == 8) {
                o.write(cur)
                cur = 0
                n = 0
            }
        }
        fun u(v: Int, bits: Int) {
            for (i in bits - 1 downTo 0) bit(v ushr i)
        }
        fun ue(v: Int) {
            val x = v + 1
            val len = 32 - Integer.numberOfLeadingZeros(x)
            repeat(len - 1) { bit(0) }
            u(x, len)
        }
        fun se(v: Int) = ue(if (v > 0) 2 * v - 1 else -2 * v)
        fun trailing() {
            bit(1)
            while (n != 0) bit(0)
        }
        fun bytes(): ByteArray = o.toByteArray()
    }
}
