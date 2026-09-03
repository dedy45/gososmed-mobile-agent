package com.gososmed.agent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log perintah in-app (v0.4.1) — ala tab "Logs" NeuralBridge: setiap command
 * yang dieksekusi tercatat dengan waktu dan latensinya, terlihat langsung oleh
 * pengguna di layar utama. Tujuannya transparansi: pemilik HP bisa melihat
 * persis apa yang diminta server dan seberapa cepat dikerjakan.
 *
 * Ring buffer 200 baris, thread-safe, listener opsional untuk UI.
 */
object AgentLog {
    private const val MAX_LINES = 200
    private val lines = ArrayDeque<String>()
    private val lock = Any()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    var listener: ((String) -> Unit)? = null

    /** Catat hasil eksekusi satu command dengan latensinya. */
    fun add(cmd: String, ok: Boolean, latencyMs: Long) {
        val mark = if (ok) "✓" else "✗"
        val line = "${fmt.format(Date())}  $cmd  ${latencyMs}ms  $mark"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        listener?.invoke(line)
    }

    /** Catat kejadian non-command (koneksi, pairing, dsb). */
    fun event(text: String) {
        val line = "${fmt.format(Date())}  $text"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        listener?.invoke(line)
    }

    /** Seluruh isi log (untuk render ulang saat UI dibuka). */
    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() = synchronized(lock) { lines.clear() }
}
