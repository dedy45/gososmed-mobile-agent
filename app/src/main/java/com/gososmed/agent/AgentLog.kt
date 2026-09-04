package com.gososmed.agent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log perintah in-app (v0.5.0) — entri bertipe agar UI bisa memberi WARNA
 * per baris (✓ hijau / ✗ merah / kejadian biru), dan detail jelas tentang
 * ke mana DATA perintah pergi (mis. screenshot = dikirim ke server, bukan
 * disimpan di HP).
 *
 * Ring buffer 300 baris, thread-safe, listener opsional untuk UI.
 */
object AgentLog {

    /** Jenis entri → menentukan warna di panel Log. */
    enum class Kind { OK, ERR, INFO }

    /** Satu baris log + jenisnya (untuk render berwarna). */
    data class Entry(val time: String, val text: String, val kind: Kind) {
        override fun toString(): String = "$time  $text"
    }

    private const val MAX_LINES = 300
    private val entries = ArrayDeque<Entry>()
    private val lock = Any()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    var listener: ((Entry) -> Unit)? = null

    /**
     * Catat hasil eksekusi satu command dengan latensinya.
     * @param detail keterangan tambahan, mis. ke mana data dikirim.
     */
    fun add(cmd: String, ok: Boolean, latencyMs: Long, detail: String? = null) {
        val mark = if (ok) "✓" else "✗"
        val text = buildString {
            append(cmd)
            append("  ")
            append(latencyMs)
            append("ms  ")
            append(mark)
            if (!detail.isNullOrEmpty()) {
                append("\n         ↳ ")
                append(detail)
            }
        }
        emit(Entry(fmt.format(Date()), text, if (ok) Kind.OK else Kind.ERR))
    }

    /** Catat kejadian non-command (koneksi, pairing, dsb). */
    fun event(text: String) {
        emit(Entry(fmt.format(Date()), text, Kind.INFO))
    }

    /** Seluruh isi log (untuk render ulang saat UI dibuka). */
    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    /** Jumlah baris tersimpan (untuk counter UI). */
    fun size(): Int = synchronized(lock) { entries.size }

    fun clear() = synchronized(lock) { entries.clear() }

    private fun emit(e: Entry) {
        synchronized(lock) {
            entries.addLast(e)
            while (entries.size > MAX_LINES) entries.removeFirst()
        }
        listener?.invoke(e)
    }
}
