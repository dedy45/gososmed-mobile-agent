package com.gososmed.agent

/**
 * Model laporan kesehatan subsistem untuk tab Diagnostik.
 *
 * Murni data + logika agregasi (tanpa Android API) sehingga bisa diuji di JVM.
 * Pengambilan status sebenarnya dilakukan di [MainActivity] lalu diumpankan
 * ke [HealthReport].
 */
data class HealthItem(
    val name: String,
    val ok: Boolean,
    val detail: String
)

/**
 * Laporan kesehatan lengkap.
 *
 * [errorLines] adalah entri log error/peringatan terakhir yang relevan untuk
 * pengguna — bukan dump logcat mentah.
 */
data class HealthReport(
    val items: List<HealthItem>,
    val errorLines: List<String>
) {
    val errorCount: Int get() = items.count { !it.ok }

    /** Ringkasan satu baris, mis. "6/7 OK — 1 perlu perhatian". */
    fun summary(): String = when {
        items.isEmpty() -> "Belum ada pemeriksaan"
        errorCount == 0 -> "✅ ${items.size}/${items.size} OK — semua subsistem sehat"
        else -> "⚠️ ${items.size - errorCount}/${items.size} OK — $errorCount perlu perhatian"
    }

    /** Baris per subsistem dengan simbol status. */
    fun rowsText(): String = items.joinToString("\n") { item ->
        val mark = if (item.ok) "✅" else "❌"
        val pad = item.name.padEnd(18)
        "$mark $pad: ${item.detail}"
    }

    /** Blok log error untuk ditampilkan; kosong = pesan ramah. */
    fun errorsText(): String =
        if (errorLines.isEmpty()) "Tidak ada error." else errorLines.joinToString("\n")

    /** Laporan teks lengkap untuk tombol "Salin Laporan". */
    fun fullText(): String = buildString {
        appendLine("GoSosmed Agent — Health Check")
        appendLine(summary())
        appendLine()
        appendLine(rowsText())
        appendLine()
        appendLine("LOG ERROR TERAKHIR")
        appendLine(errorsText())
    }
}
