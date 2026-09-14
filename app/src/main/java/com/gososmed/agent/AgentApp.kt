package com.gososmed.agent

import android.app.Application
import android.content.Context
import android.util.Log

/**
 * v0.9.7 — Application: titik pasang yang berjalan di SETIAP awal proses.
 *
 * Dua tugas, keduanya menjawab masalah nyata yang dilaporkan dari lapangan:
 *
 *  1. **Menyerahkan applicationContext ke [AgentAccessibilityService]** supaya
 *     status "Langkah 1 aktif" bisa ditanyakan LANGSUNG ke sistem operasi
 *     (`AccessibilityManager`), bukan hanya dari flag dalam memori. Tanpa ini,
 *     begitu OEM (MIUI/HyperOS) me-restart proses agent, UI kehilangan
 *     satu-satunya sumber informasinya dan menampilkan "BELUM AKTIF" untuk
 *     layanan yang sebenarnya masih hidup di Setelan.
 *
 *  2. **Mencatat sebab crash terakhir ke disk.** `AdbPairingService`,
 *     `AgentForegroundService`, `AgentAccessibilityService` dan `MainActivity`
 *     hidup di SATU proses. Karena itu satu exception yang lolos dari jalur
 *     pairing akan mematikan seluruh proses — dan `AccessibilityService` ikut
 *     mati. Gejalanya di HP pengguna persis seperti yang dilaporkan:
 *     "klik Hubungkan di Langkah 3 → Langkah 1 mati".
 *
 *     Sejak v0.9.7 jalur pairing dibungkus try/catch sehingga seharusnya tidak
 *     bisa lagi menjatuhkan proses. Penangkap ini adalah jaring terakhir: bila
 *     tetap terjadi, penyebabnya tersimpan dan ditampilkan di tab Log pada
 *     peluncuran berikutnya — bukan hilang bersama prosesnya.
 *
 * Sengaja dibuat TIDAK PERNAH MELEMPAR: `Application.onCreate()` yang gagal
 * berarti aplikasi tidak bisa dibuka sama sekali.
 */
class AgentApp : Application() {

    companion object {
        private const val TAG = "GoAgentApp"
        private const val PREFS = "agent"
        private const val KEY_LAST_CRASH = "last_crash"

        /** Panjang maksimum catatan crash yang disimpan (cukup untuk diagnosa). */
        private const val MAX_CRASH_CHARS = 1_500

        /**
         * Ambil catatan crash terakhir SEKALI, lalu hapus. Dipanggil dari
         * `MainActivity` untuk memunculkannya di tab Log.
         */
        fun takeLastCrash(ctx: Context): String? {
            return try {
                val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val value = prefs.getString(KEY_LAST_CRASH, null) ?: return null
                prefs.edit().remove(KEY_LAST_CRASH).commit()
                value
            } catch (_: Throwable) {
                null
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            AgentAccessibilityService.attach(this)
        } catch (t: Throwable) {
            Log.w(TAG, "gagal menyerahkan context ke AgentAccessibilityService", t)
        }
        installCrashRecorder()
    }

    private fun installCrashRecorder() {
        try {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                try {
                    recordCrash(thread, error)
                } catch (_: Throwable) {
                    // Merekam crash TIDAK BOLEH ikut gagal.
                }
                // Selalu teruskan ke penangan sebelumnya supaya perilaku
                // standar Android (tombstone, Play Console) tetap berjalan.
                previous?.uncaughtException(thread, error)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "gagal memasang penangkap crash", t)
        }
    }

    private fun recordCrash(thread: Thread, error: Throwable) {
        val text = buildString {
            append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date()))
            append("  ")
            append(thread.name)
            append("  ")
            append(error.javaClass.name)
            append(": ")
            append(error.message ?: "(tanpa pesan)")
            append('\n')
            // Beberapa frame teratas sudah cukup untuk menunjuk berkas & baris.
            error.stackTrace.take(12).forEach { frame ->
                append("    at ")
                append(frame.toString())
                append('\n')
            }
        }.take(MAX_CRASH_CHARS)

        Log.e(TAG, "CRASH TERCATAT:\n$text")
        // commit() (bukan apply()) — proses sedang mati, penulisan asinkron
        // tidak akan sempat selesai.
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_CRASH, text)
            .commit()
    }
}
