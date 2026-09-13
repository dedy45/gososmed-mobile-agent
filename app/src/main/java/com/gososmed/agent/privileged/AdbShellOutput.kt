package com.gososmed.agent.privileged

/**
 * v0.9.0 — pengurai keluaran perintah shell ADB.
 *
 * KENAPA DIPISAH DAN MURNI:
 * Layanan `shell:` pada protokol ADB hanya mengembalikan SATU aliran teks;
 * ia tidak memberi exit code seperti `adb shell` versi baru (shell v2).
 * Supaya exit code tetap jujur, perintah dibungkus dan diakhiri penanda:
 *
 *     (perintah) 2>&1; echo __GOSOSMED_EXIT__$?
 *
 * Kelas ini PURE (tanpa Android, tanpa IO) supaya bisa diuji di JVM — bagian
 * yang paling mudah salah dan paling mahal untuk diuji di perangkat nyata.
 */
internal object AdbShellOutput {

    /**
     * Penanda akhiran. Sengaja memakai awalan ganda yang tidak akan muncul di
     * keluaran normal `am`/`input`/`dumpsys`.
     */
    const val MARKER = "__GOSOSMED_EXIT__"

    /** Exit code yang dipakai bila penanda tidak ditemukan. */
    const val EXIT_UNKNOWN = -1

    /**
     * Pisahkan keluaran dari exit code.
     *
     * Memakai `lastIndexOf` supaya bila perintah sendiri mencetak penanda
     * (mis. `dumpsys` memuat teksnya), yang diambil tetap penanda TERAKHIR
     * yang kita tambahkan sendiri.
     *
     * @return pasangan (keluaran tanpa penanda, exit code; -1 bila tak terbaca)
     */
    fun parse(raw: String): Pair<String, Int> {
        val markerAt = raw.lastIndexOf(MARKER)
        if (markerAt < 0) {
            return raw.trimEnd('\n', '\r', ' ') to EXIT_UNKNOWN
        }
        val output = raw.substring(0, markerAt).trimEnd('\n', '\r')
        val codeText = raw.substring(markerAt + MARKER.length).trim()
        // `echo` normal mengembalikan angka; tolak apa pun yang bukan angka
        // utuh supaya exit code tidak pernah "ditebak".
        val code = codeText.toIntOrNull() ?: EXIT_UNKNOWN
        return output to code
    }

    /**
     * Bungkus perintah supaya exit code-nya bisa dibaca dan stderr ikut
     * tertangkap.
     *
     * CATATAN KONTRAK: karena layanan `shell:` hanya punya satu aliran,
     * `stderr` DIGABUNG ke stdout (`2>&1`). Ini berbeda dari v0.8.0 yang bisa
     * memisahkannya. Perbedaan ini dicatat di AGENT-COMMAND-CONTRACT.md §3.1 —
     * satu-satunya konsumen (`startAppShell`) sudah memeriksa gabungan
     * keduanya, jadi perilakunya tidak berubah.
     */
    fun wrap(command: String): String = "($command) 2>&1; echo $MARKER\$?"
}
