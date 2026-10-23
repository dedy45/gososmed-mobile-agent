package com.gososmed.agent.capture

/**
 * Membangun string codec WebCodecs untuk HEVC dari VPS/SPS yang dikirim
 * `MediaCodec.onOutputFormatChanged`.
 *
 * Format RFC 6381:
 *
 * ```
 * hev1.<profile_space><profile_idc>.<compatibility_flags>.<tier><level>[.<constraint>]
 * ```
 *
 * Contoh nyata: `hev1.1.6.L120.B0`.
 *
 * Kenapa `hev1` dan bukan `hvc1`: `hev1` mengizinkan parameter set berada
 * in-band di stream Annex-B, sedangkan `hvc1` mewajibkannya di box `hvcC`.
 * Kita mengirim Annex-B mentah tanpa muxer, jadi `hev1` yang benar. Pola ini
 * sama dengan `toCodecString()` di ya-webadb.
 */
object HevcCodecString {

    /**
     * `nal_unit_type` HEVC menempati bit 1..6 byte pertama (6 bit),
     * berbeda dari H.264 yang memakai bit 0..4 (5 bit).
     */
    private fun naluType(byte0: Int): Int = (byte0 shr 1) and 0x3F

    /**
     * @param config NALU Annex-B berisi VPS (32) dan SPS (33).
     * @return string codec WebCodecs, atau null bila VPS/SPS tidak ada atau
     *         bitstream tidak bisa di-parse. Pemanggil harus jatuh ke AVC.
     */
    fun fromAnnexB(config: ByteArray): String? = try {
        var vps: ByteArray? = null
        var sps: ByteArray? = null
        for (nal in SpsFix.split(config)) {
            if (nal.size < 3) continue
            when (naluType(nal[0].toInt() and 0xFF)) {
                32 -> vps = nal
                33 -> sps = nal
            }
            if (vps != null && sps != null) break
        }
        val v = vps
        val s = sps
        if (v == null || s == null) null else build(v, s)
    } catch (_: Exception) {
        null
    }

    private fun build(vps: ByteArray, sps: ByteArray): String {
        val p = ProfileTierLevel.parse(vps)

        val sb = StringBuilder(24)
        sb.append("hev1.")
        // profile_space: 0 = tidak ditulis, 1..3 = A/B/C.
        when (p.profileSpace) {
            1 -> sb.append('A')
            2 -> sb.append('B')
            3 -> sb.append('C')
        }
        sb.append(p.profileIdc).append('.')
        sb.append(compatibilityHex(p.compatibility)).append('.')
        sb.append(if (p.tierFlag == 1) 'H' else 'L').append(p.levelIdc)

        for (b in p.constraints) sb.append('.').append(String.format("%02X", b))
        return sb.toString()
    }

    /**
     * 4 byte compatibility flags dibaca little-endian (urutan byte di
     * bitstream), lalu ditulis 8 digit hex seperti RFC 6381.
     */
    private fun compatibilityHex(compat: ByteArray): String {
        val v = (compat[0].toInt() and 0xFF) or
            ((compat[1].toInt() and 0xFF) shl 8) or
            ((compat[2].toInt() and 0xFF) shl 16) or
            ((compat[3].toInt() and 0xFF) shl 24)
        return String.format("%08X", v)
    }

    /** Field `profile_tier_level()` yang dibutuhkan string codec. */
    private class ProfileTierLevel(
        val profileSpace: Int,
        val tierFlag: Int,
        val profileIdc: Int,
        val compatibility: ByteArray,
        val levelIdc: Int,
        val constraints: List<Int>
    ) {
        companion object {
            fun parse(vps: ByteArray): ProfileTierLevel {
                // Buang header NALU 2 byte, lalu emulation prevention 0x03.
                val r = BitReader(unescape(vps, 2))

                // --- awal VPS, sebelum profile_tier_level() ---
                r.skip(4)              // vps_video_parameter_set_id
                r.skip(2)              // base_layer_internal + base_layer_available
                r.skip(6)              // vps_max_layers_minus1
                val maxSubLayersMinus1 = r.bits(3)
                r.skip(1)              // vps_temporal_id_nesting_flag
                r.skip(16)             // vps_reserved_0xffff_16bits

                // --- profile_tier_level(1, vps_max_sub_layers_minus1) ---
                val profileSpace = r.bits(2)
                val tierFlag = r.bit()
                val profileIdc = r.bits(5)
                val compatibility = ByteArray(4) { r.bits(8).toByte() }
                val constraintBytes = ArrayList<Int>(6)
                repeat(6) { constraintBytes.add(r.bits(8)) }
                val levelIdc = r.bits(8)

                // Constraint: buang byte nol di belakang (RFC 6381 opsional).
                while (constraintBytes.isNotEmpty() && constraintBytes.last() == 0) {
                    constraintBytes.removeAt(constraintBytes.size - 1)
                }

                // Sub-layer profile/level tidak dipakai untuk string codec.
                @Suppress("UNUSED_VARIABLE")
                val ignored = maxSubLayersMinus1

                return ProfileTierLevel(
                    profileSpace, tierFlag, profileIdc,
                    compatibility, levelIdc, constraintBytes
                )
            }

            /** Buang emulation prevention byte `0x03` setelah dua `0x00`. */
            private fun unescape(d: ByteArray, from: Int): ByteArray {
                val out = java.io.ByteArrayOutputStream(d.size)
                var zeros = 0
                for (i in from until d.size) {
                    val b = d[i].toInt() and 0xFF
                    if (zeros >= 2 && b == 0x03) {
                        zeros = 0
                        continue
                    }
                    out.write(b)
                    zeros = if (b == 0) zeros + 1 else 0
                }
                return out.toByteArray()
            }
        }
    }

    private class BitReader(private val d: ByteArray) {
        private var pos = 0

        fun bit(): Int {
            val byteIdx = pos ushr 3
            if (byteIdx >= d.size) throw IndexOutOfBoundsException("bitstream habis")
            val b = (d[byteIdx].toInt() ushr (7 - (pos and 7))) and 1
            pos++
            return b
        }

        fun bits(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or bit() }
            return v
        }

        fun skip(n: Int) = repeat(n) { bit() }
    }
}
