package com.gososmed.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthReportTest {

    private fun item(name: String, ok: Boolean, detail: String = "ok") =
        HealthItem(name, ok, detail)

    @Test
    fun `summary reports all ok`() {
        val r = HealthReport(
            items = listOf(item("A", true), item("B", true)),
            errorLines = emptyList()
        )
        assertTrue(r.summary().contains("2/2 OK"))
        assertEquals(0, r.errorCount)
    }

    @Test
    fun `summary counts failures`() {
        val r = HealthReport(
            items = listOf(item("A", true), item("B", false, "mati")),
            errorLines = listOf("err1")
        )
        assertEquals(1, r.errorCount)
        assertTrue(r.summary().contains("1/2 OK"))
        assertTrue(r.summary().contains("1 perlu perhatian"))
    }

    @Test
    fun `summary handles empty list`() {
        assertEquals("Belum ada pemeriksaan", HealthReport(emptyList(), emptyList()).summary())
    }

    @Test
    fun `rows use ok and fail marks`() {
        val r = HealthReport(
            items = listOf(item("Aksesibilitas", true, "aktif"), item("ADB", false, "belum")),
            errorLines = emptyList()
        )
        val rows = r.rowsText()
        assertTrue(rows.contains("✅"))
        assertTrue(rows.contains("❌"))
        assertTrue(rows.contains("Aksesibilitas"))
        assertTrue(rows.contains("belum"))
    }

    @Test
    fun `errors text is friendly when empty`() {
        assertEquals("Tidak ada error.", HealthReport(emptyList(), emptyList()).errorsText())
    }

    @Test
    fun `errors text lists each line`() {
        val r = HealthReport(emptyList(), listOf("e1", "e2"))
        assertEquals("e1\ne2", r.errorsText())
    }

    @Test
    fun `full text contains all sections`() {
        val r = HealthReport(
            items = listOf(item("Aksesibilitas", true, "aktif")),
            errorLines = listOf("boom")
        )
        val txt = r.fullText()
        assertTrue(txt.contains("Health Check"))
        assertTrue(txt.contains("Aksesibilitas"))
        assertTrue(txt.contains("LOG ERROR TERAKHIR"))
        assertTrue(txt.contains("boom"))
    }

    @Test
    fun `optional items are excluded from core error count and summary`() {
        val r = HealthReport(
            items = listOf(
                item("Aksesibilitas", true, "aktif"),
                item("WebSocket", true, "tersambung"),
                HealthItem("ADB Wireless", false, "belum dipasang", isOptional = true, category = HealthCategory.CORE),
                HealthItem("Server Lokal", false, "mati", isOptional = true, category = HealthCategory.REMOTE_CONTROL),
                HealthItem("Capture Layar", false, "mati", isOptional = true, category = HealthCategory.REMOTE_CONTROL)
            ),
            errorLines = emptyList()
        )

        // Core mandatory hanya 2 (Aksesibilitas, WebSocket). Karena keduanya true, errorCount harus 0
        assertEquals(0, r.errorCount)
        assertTrue("Summary harus 2/2 OK", r.summary().contains("2/2 OK"))
        assertTrue("Summary tidak boleh menyebut perlu perhatian", r.summary().contains("semua subsistem inti sehat"))
    }

    @Test
    fun `rowsText formats remote control section with separator and neutral mark`() {
        val r = HealthReport(
            items = listOf(
                item("Aksesibilitas", true, "aktif & siap"),
                HealthItem("Server Lokal", false, "nonaktif (switch off)", isOptional = true, category = HealthCategory.REMOTE_CONTROL),
                HealthItem("Capture Layar", true, "streaming aktif", isOptional = true, category = HealthCategory.REMOTE_CONTROL)
            ),
            errorLines = emptyList()
        )

        val rows = r.rowsText()
        assertTrue(rows.contains("─── FITUR OPSIONAL (REMOTE CONTROL) ───"))
        assertTrue(rows.contains("⚪ Server Lokal"))
        assertTrue(rows.contains("✅ Capture Layar"))
    }
}
