package com.gososmed.agent.capture

import android.media.MediaCodecList

/**
 * Jenis codec video yang dapat dinegosiasikan untuk streaming.
 *
 * Urutan preferensi: AV1 (paling hemat bandwidth) → HEVC → AVC (paling
 * kompatibel). AVC selalu tersedia karena Android menyertakan encoder AVC
 * software minimal di semua perangkat.
 */
enum class CodecKind { AV1, HEVC, AVC }

/**
 * Probe dan negosiasi codec.
 *
 * Pola ini diambil dari aplikasi produksi yang sudah dianalisis:
 * - DeskIn/ToDesk memakai string `video/hevc`, `video/av01`, `video/avc`
 *   (ditemukan di `classes.dex`, `classes4.dex`, `classes5.dex`).
 * - iMyFone MirrorTo memakai `video/hevc` dan `video/av01`
 *   (ditemukan di `classes2.dex`).
 *
 * Keduanya TIDAK mengunci satu codec: mereka menegosiasikan saat runtime dan
 * jatuh ke AVC bila encoder tidak tersedia.
 */
object CodecCapability {

    /** Urutan percobaan dari paling hemat bandwidth ke paling kompatibel. */
    val preferenceOrder: List<CodecKind> = listOf(
        CodecKind.AV1,
        CodecKind.HEVC,
        CodecKind.AVC
    )

    /** MIME type yang dipakai `MediaCodec.createEncoderByType`. */
    fun mimeOf(kind: CodecKind): String = when (kind) {
        CodecKind.AV1 -> "video/av01"
        CodecKind.HEVC -> "video/hevc"
        CodecKind.AVC -> "video/avc"
    }

    /**
     * Identifier codec untuk sisi browser (MSE/WebCodecs).
     * Berbeda dari MIME Android: `avc1`, `hvc1`, `av01`.
     */
    fun viewerCodecOf(kind: CodecKind): String = when (kind) {
        CodecKind.AV1 -> "av01"
        CodecKind.HEVC -> "hvc1"
        CodecKind.AVC -> "avc1"
    }

    /** AVC adalah fallback terakhir yang dijamin ada. */
    fun isFallback(kind: CodecKind): Boolean = kind == CodecKind.AVC

    /**
     * Memilih codec pertama pada [preferred] yang punya encoder terdaftar di
     * perangkat ini. Tidak pernah mengembalikan null: AVC selalu jadi jawaban
     * terakhir.
     *
     * Catatan: ini hanya memeriksa KEBERADAAN encoder, bukan apakah encoder
     * tersebut benar-benar bisa `configure()` dengan resolusi/bitrate kita.
     * Kegagalan configure ditangani oleh [EncoderPipeline.selectWithFallback].
     */
    fun negotiate(preferred: List<CodecKind> = preferenceOrder): CodecKind {
        val encoders = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
        } catch (_: Throwable) {
            return CodecKind.AVC
        }
        for (kind in preferred) {
            val mime = mimeOf(kind)
            val hasEncoder = encoders.any { info ->
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
            if (hasEncoder) return kind
        }
        return CodecKind.AVC
    }
}
