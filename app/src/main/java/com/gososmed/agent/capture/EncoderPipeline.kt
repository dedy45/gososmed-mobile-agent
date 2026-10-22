package com.gososmed.agent.capture

/**
 * Rantai fallback encoder: AV1 → HEVC → AVC.
 *
 * Alasan keberadaan kelas ini: `CodecCapability.negotiate()` hanya memeriksa
 * apakah encoder TERDAFTAR di perangkat. Encoder bisa terdaftar tetapi gagal
 * `configure()` untuk resolusi/bitrate tertentu (sering terjadi pada encoder
 * AV1/HEVC software di chipset lama). Kelas ini mencoba sungguhan, dan jatuh
 * ke codec berikutnya bila gagal.
 *
 * Pola ini mengikuti aplikasi produksi yang dianalisis (DeskIn/ToDesk dan
 * iMyFone): keduanya menyebut `video/hevc`/`video/av01` tetapi tetap berfungsi
 * di perangkat yang hanya punya AVC.
 */
object EncoderPipeline {

    /** Hasil percobaan lengkap, dipakai untuk logging/diagnostik. */
    data class Report(
        val selected: CodecKind,
        val attempted: List<CodecKind>,
        val failures: List<String>
    )

    /**
     * Mencoba setiap codec pada [order] sampai satu berhasil.
     *
     * @param tryConfigure dipanggil per codec; lempar exception bila gagal.
     * @return codec yang berhasil, atau [CodecKind.AVC] bila semuanya gagal
     *         (AVC dijamin ada di semua perangkat Android).
     */
    inline fun selectWithFallback(
        order: List<CodecKind> = CodecCapability.preferenceOrder,
        tryConfigure: (CodecKind) -> Unit
    ): CodecKind {
        for (kind in order) {
            try {
                tryConfigure(kind)
                return kind
            } catch (_: Throwable) {
                // lanjut ke codec berikutnya
            }
        }
        return CodecKind.AVC
    }

    /**
     * Sama seperti [selectWithFallback], tetapi mengembalikan jejak lengkap
     * untuk log. Dipakai di jalur produksi agar kegagalan codec tidak senyap.
     */
    inline fun attemptWithReport(
        order: List<CodecKind> = CodecCapability.preferenceOrder,
        tryConfigure: (CodecKind) -> Unit
    ): Report {
        val attempted = mutableListOf<CodecKind>()
        val failures = mutableListOf<String>()
        for (kind in order) {
            attempted += kind
            try {
                tryConfigure(kind)
                return Report(kind, attempted, failures)
            } catch (e: Throwable) {
                failures += "$kind: ${e.message ?: e::class.java.simpleName}"
            }
        }
        return Report(CodecKind.AVC, attempted, failures)
    }
}
