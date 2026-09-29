package com.gososmed.agent

/**
 * v0.7.0 — info update APK yang diketahui agent.
 *
 * Sumber kebenaran ganda (resilien):
 *  1. Pasif: server menyertakan latest_agent_version + apk_url di
 *     register_ack setiap koneksi WS (satu sumber dengan dasbor BYOD).
 *  2. Aktif: tombol "Cek Update" di tab Setup menanyakan GitHub Releases
 *     langsung — tetap jalan walau env server belum diperbarui.
 *
 * Ditulis dari thread reader OkHttp, dibaca di UI thread → semua @Volatile.
 */
object AgentUpdateState {
    @Volatile var latestVersion: String = ""
    @Volatile var apkUrl: String = ""
    @Volatile var checkedAt: Long = 0L

    /** true bila versi terbaru yang diketahui lebih baru dari [current]. */
    fun isNewer(current: String): Boolean {
        if (latestVersion.isEmpty()) return false
        return compareVersions(latestVersion, current) > 0
    }

    /**
     * Perbandingan SemVer x.y.z[-prerelease]:
     *  1. Bagian numerik (major.minor.patch) dibandingkan terlebih dahulu.
     *  2. Bila numerik sama: versi tanpa prerelease (stabil) lebih baru dari prerelease.
     *  3. Bila keduanya prerelease: bandingkan token suffix (dev.2 > dev.1).
     */
    fun compareVersions(a: String, b: String): Int {
        fun clean(v: String) = v.trim().removePrefix("v")
        val ca = clean(a)
        val cb = clean(b)

        val coreA = ca.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val coreB = cb.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }

        for (i in 0 until maxOf(coreA.size, coreB.size)) {
            val x = coreA.getOrElse(i) { 0 }
            val y = coreB.getOrElse(i) { 0 }
            if (x != y) return x - y
        }

        val hasPreA = ca.contains("-")
        val hasPreB = cb.contains("-")

        if (!hasPreA && hasPreB) return 1   // a stabil > b dev
        if (hasPreA && !hasPreB) return -1  // a dev < b stabil
        if (!hasPreA && !hasPreB) return 0

        val preA = ca.substringAfter("-")
        val preB = cb.substringAfter("-")
        if (preA == preB) return 0

        // Bandingkan segmen prerelease numerik (mis. dev.2 vs dev.1)
        val toksA = preA.split(".")
        val toksB = preB.split(".")
        for (i in 0 until maxOf(toksA.size, toksB.size)) {
            val tA = toksA.getOrElse(i) { "" }
            val tB = toksB.getOrElse(i) { "" }
            val numA = tA.filter { it.isDigit() }.toIntOrNull()
            val numB = tB.filter { it.isDigit() }.toIntOrNull()
            if (numA != null && numB != null && numA != numB) {
                return numA - numB
            }
            val cmp = tA.compareTo(tB)
            if (cmp != 0) return cmp
        }
        return 0
    }
}
