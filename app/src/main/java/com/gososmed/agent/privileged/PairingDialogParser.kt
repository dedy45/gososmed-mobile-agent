package com.gososmed.agent.privileged

/**
 * Parser teks dialog "Debug nirkabel → Pasangkan perangkat dengan kode pairing".
 *
 * Dialog sistem menampilkan dua nilai yang kita butuhkan:
 *
 *   • port pairing, dalam bentuk `192.168.x.y:37123`; dan
 *   • kode pairing, dalam bentuk enam angka (`123456`, kadang `123 456`).
 *
 * Parser ini SENGAJA tidak bergantung pada kata "Pairing code"/"Kode pairing":
 * label itu berubah mengikuti bahasa dan OEM. Yang dipakai sebagai jangkar adalah
 * keberadaan `IPv4:port` pada layar Setelan yang sama; kandidat kode enam angka
 * hanya diterima setelah jangkar port itu ada. Ini menjaga pembacaan tetap
 * konservatif dan tidak menyentuh layar aplikasi lain.
 *
 * Nilai yang diparse TIDAK PERNAH ditulis ke log — kode pairing adalah rahasia
 * otorisasi lokal. Simpan hanya di memori sesi dan teruskan ke libadb.
 */
object PairingDialogParser {

    data class Snapshot(
        val port: Int = -1,
        val code: String? = null,
    ) {
        val hasPort: Boolean get() = port in 1..65535
        val isComplete: Boolean get() = hasPort && code?.length == 6
    }

    private val ipPortRegex = Regex(
        """\b(?:\d{1,3}\.){3}\d{1,3}\s*:\s*(\d{1,5})\b"""
    )
    private val sixDigitRegex = Regex("""(?<!\d)(\d{6})(?!\d)""")
    private val groupedCodeRegex = Regex("""(?<!\d)(\d{3})[\s\u00A0-](\d{3})(?!\d)""")
    private val spacedCodeRegex = Regex(
        """(?<!\d)(\d)\s+(\d)\s+(\d)\s+(\d)\s+(\d)\s+(\d)(?!\d)"""
    )

    /**
     * Parse setiap window secara terpisah. Wajib: port dan kode harus berasal
     * dari SATU dialog yang sama. Layar utama Debug nirkabel juga punya
     * `IP:port` (port CONNECT); jangan sampai port itu dipasangkan dengan kode
     * dari window pairing yang lain.
     */
    fun parseWindows(windowsTexts: List<List<String>>): Snapshot {
        for (texts in windowsTexts) {
            val snapshot = parse(texts)
            if (snapshot.isComplete) return snapshot
        }
        return Snapshot()
    }

    /** Gabungkan teks-teks dari satu hierarki dialog Setelan. */
    fun parse(texts: List<String>): Snapshot {
        var port = -1

        for (raw in texts) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            val match = ipPortRegex.find(text) ?: continue
            val candidate = match.groupValues[1].toIntOrNull() ?: continue
            if (candidate in 1..65535) {
                port = candidate
                break
            }
        }
        if (port <= 0) return Snapshot()

        for (raw in texts) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            // Hapus bentuk IP:port lebih dulu supaya port enam digit (mis. 61234)
            // tidak pernah salah terbaca sebagai kode pairing.
            val withoutEndpoint = ipPortRegex.replace(text, " ")

            sixDigitRegex.find(withoutEndpoint)?.let {
                return Snapshot(port = port, code = it.value)
            }
            groupedCodeRegex.find(withoutEndpoint)?.let {
                return Snapshot(port = port, code = it.groupValues[1] + it.groupValues[2])
            }
            spacedCodeRegex.find(withoutEndpoint)?.let {
                return Snapshot(port = port, code = (1..6).joinToString("") { i -> it.groupValues[i] })
            }
        }

        // Layar utama Debug nirkabel juga menampilkan `IP:port` — tetapi itu
        // PORT CONNECT, bukan port pairing. Tanpa kode enam angka pada layar
        // yang sama, kandidat port tidak boleh dipakai untuk pairing.
        return Snapshot()
    }
}
