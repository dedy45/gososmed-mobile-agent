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
     * Perbandingan semver sederhana x.y.z — suffix kanal (-dev.N) diabaikan
     * supaya rilis stabil 0.7.0 dianggap lebih baru dari 0.7.0-dev.3.
     */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore("-")
            .split(".").map { it.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
