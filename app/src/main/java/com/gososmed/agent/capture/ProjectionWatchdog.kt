package com.gososmed.agent.capture

/**
 * Mendeteksi pipeline tangkapan layar yang "mandek" (tidak menghasilkan frame).
 *
 * Pola ini diambil dari DeskIn/ToDesk: `MediaScreenService$startPeriodicCheck`
 * menghitung `numCapturedFrames` dan mencatat
 * "MediaProjection is no longer active (periodic check)". Mereka tidak menunggu
 * exception — mereka memeriksa secara berkala.
 *
 * Kelas ini murni logika (tanpa Android API) sehingga bisa diuji di JVM.
 */
class ProjectionWatchdog(
    private val stallThresholdMs: Long = DEFAULT_STALL_MS
) {
    companion object {
        const val DEFAULT_STALL_MS = 3000L
    }

    @Volatile
    private var lastFrameMs: Long = 0L

    @Volatile
    private var frameCount: Long = 0L

    /** Dipanggil setiap kali encoder menghasilkan frame. */
    fun onFrame(nowMs: Long) {
        lastFrameMs = nowMs
        frameCount++
    }

    /**
     * True bila sudah ada frame sebelumnya tetapi tidak ada frame baru selama
     * lebih dari [stallThresholdMs].
     *
     * Mengembalikan false sebelum frame pertama, karena pipeline yang baru
     * mulai belum bisa dianggap mandek.
     */
    fun isStalled(nowMs: Long): Boolean {
        if (lastFrameMs <= 0L) return false
        return (nowMs - lastFrameMs) > stallThresholdMs
    }

    /** Jumlah frame yang tercatat — dipakai untuk log diagnostik. */
    fun totalFrames(): Long = frameCount

    /** Waktu frame terakhir (epoch ms dari sumber waktu pemanggil). */
    fun lastFrameAt(): Long = lastFrameMs

    /** Reset setelah pemulihan agar tidak memicu berulang. */
    fun reset(nowMs: Long) {
        lastFrameMs = nowMs
    }
}
