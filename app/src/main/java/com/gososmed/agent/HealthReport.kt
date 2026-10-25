package com.gososmed.agent

/**
 * Kategori penempatan subsistem dalam Health Check.
 */
enum class HealthCategory {
    CORE,           // Subsistem inti automasi server (A11y, WS, Overlay, Baterai, Shell, Log)
    REMOTE_CONTROL  // Fitur tambahan yang berdiri sendiri untuk remote control via browser
}

/**
 * Model satu item status kesehatan subsistem untuk tab Diagnostik.
 *
 * Murni data + logika agregasi (tanpa Android API) sehingga bisa diuji di JVM.
 * Pengambilan status sebenarnya dilakukan di [MainActivity] lalu diumpankan
 * ke [HealthReport].
 *
 * @param isOptional jika true, item ini tidak dianggap kegagalan sistem inti
 *   (tidak menambah [errorCount] dan menampilkan tanda netral ⚪ bila nonaktif).
 * @param category kategori pengelompokan visual (CORE atau REMOTE_CONTROL).
 */
data class HealthItem(
    val name: String,
    val ok: Boolean,
    val detail: String,
    val isOptional: Boolean = false,
    val category: HealthCategory = HealthCategory.CORE
)

/**
 * Laporan kesehatan lengkap dengan pemisahan visual antara Core Automasi dan Fitur Remote Control.
 *
 * [errorLines] adalah entri log error/peringatan terakhir yang relevan untuk
 * pengguna — bukan dump logcat mentah.
 */
data class HealthReport(
    val items: List<HealthItem>,
    val errorLines: List<String>
) {
    val coreItems: List<HealthItem> get() = items.filter { it.category == HealthCategory.CORE }
    val remoteControlItems: List<HealthItem> get() = items.filter { it.category == HealthCategory.REMOTE_CONTROL }
    val coreMandatoryItems: List<HealthItem> get() = coreItems.filter { !it.isOptional }

    /** Hanya menghitung error pada item inti yang wajib aktif. */
    val errorCount: Int get() = coreMandatoryItems.count { !it.ok }

    /** Ringkasan satu baris komponen inti, mis. "5/5 OK — semua subsistem inti sehat". */
    fun summary(): String = when {
        coreMandatoryItems.isEmpty() -> "Belum ada pemeriksaan"
        errorCount == 0 -> "✅ ${coreMandatoryItems.size}/${coreMandatoryItems.size} OK — semua subsistem inti sehat"
        else -> "⚠️ ${coreMandatoryItems.size - errorCount}/${coreMandatoryItems.size} OK — $errorCount perlu perhatian"
    }

    /** Baris per subsistem dengan pemisahan visual jelas antara Core Automasi dan Fitur Opsional. */
    fun rowsText(): String = buildString {
        // 1. Core Automasi Server
        coreItems.forEach { item ->
            val mark = when {
                item.ok -> "✅"
                item.isOptional -> "⚪"
                else -> "❌"
            }
            val pad = item.name.padEnd(17)
            appendLine("$mark $pad: ${item.detail}")
        }

        // 2. Garis Pemisah & Fitur Opsional Remote Control
        if (remoteControlItems.isNotEmpty()) {
            appendLine()
            appendLine("─── FITUR OPSIONAL (REMOTE CONTROL) ───")
            remoteControlItems.forEach { item ->
                val mark = when {
                    item.ok -> "✅"
                    item.isOptional -> "⚪"
                    else -> "❌"
                }
                val pad = item.name.padEnd(17)
                appendLine("$mark $pad: ${item.detail}")
            }
        }
    }.trimEnd()

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
