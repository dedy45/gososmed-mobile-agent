package com.gososmed.agent.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.0 F3 — uji pengurai keluaran shell ADB.
 *
 * Kelas ini SENGAJA murni (tanpa Android, tanpa IO) supaya bagian yang paling
 * rawan salah — menerjemahkan keluaran teks menjadi exit code — bisa diuji di
 * JVM. Menguji ini di perangkat nyata akan mahal dan tidak deterministik.
 */
class AdbShellOutputTest {

    @Test
    fun `parse membaca exit code nol sebagai sukses`() {
        val raw = "Starting: Intent { cmp=x }\n${AdbShellOutput.MARKER}0"
        val (output, code) = AdbShellOutput.parse(raw)
        assertEquals(0, code)
        assertEquals("Starting: Intent { cmp=x }", output)
    }

    @Test
    fun `parse membaca exit code non-nol`() {
        val raw = "Error: Activity not started\n${AdbShellOutput.MARKER}1"
        val (output, code) = AdbShellOutput.parse(raw)
        assertEquals(1, code)
        assertEquals("Error: Activity not started", output)
    }

    @Test
    fun `tanpa penanda exit code tidak ditebak`() {
        val (output, code) = AdbShellOutput.parse("keluaran tanpa penanda")
        assertEquals(AdbShellOutput.EXIT_UNKNOWN, code)
        assertEquals("keluaran tanpa penanda", output)
    }

    @Test
    fun `penanda ganda memakai yang TERAKHIR`() {
        // Skenario nyata: keluaran perintah sendiri memuat string penanda
        // (mis. dumpsys yang menampilkan isi berkas). Yang benar adalah penanda
        // yang KITA tambahkan, yaitu yang terakhir.
        val raw = "dump: ${AdbShellOutput.MARKER}99 di tengah\n${AdbShellOutput.MARKER}0"
        val (output, code) = AdbShellOutput.parse(raw)
        assertEquals(0, code)
        assertTrue("keluaran harus memuat teks asli", output.contains("di tengah"))
    }

    @Test
    fun `exit code bukan angka dianggap tidak diketahui`() {
        val raw = "keluaran\n${AdbShellOutput.MARKER}abc"
        val (_, code) = AdbShellOutput.parse(raw)
        assertEquals(AdbShellOutput.EXIT_UNKNOWN, code)
    }

    @Test
    fun `wrap menambahkan penanda dan menangkap stderr`() {
        val wrapped = AdbShellOutput.wrap("am force-stop com.x")
        assertTrue("harus membungkus perintah", wrapped.contains("am force-stop com.x"))
        assertTrue("stderr harus digabung", wrapped.contains("2>&1"))
        assertTrue("harus mencetak penanda", wrapped.contains(AdbShellOutput.MARKER))
        assertTrue("harus memakai exit code shell", wrapped.contains("\$?"))
    }

    @Test
    fun `parse pada keluaran kosong aman`() {
        val (output, code) = AdbShellOutput.parse("")
        assertEquals("", output)
        assertEquals(AdbShellOutput.EXIT_UNKNOWN, code)
    }
}
