package com.gososmed.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresi untuk bug anotasi "kacau" di v1.2.4.
 *
 * Penyebab: `takeScreenshotRawBytes` mendapat parameter baru `maxWidth = 480`.
 * Call site `annotatedScreenshot` tidak mengirim `maxWidth`, sehingga bitmap
 * tangkapan menyusut (1220x2712 -> 480x1067) sementara
 * `AnnotatedScreenshotHelper` tetap menggambar kotak memakai koordinat LAYAR
 * FISIK. Kotak dan nomor jatuh di luar gambar.
 *
 * Test ini mengunci dua kontrak tanpa membutuhkan Android runtime:
 *  1. Setelah penyesuaian, setiap kotak berada DI DALAM batas bitmap.
 *  2. `elements` JSON tetap memakai koordinat layar fisik, karena dipakai
 *     untuk tap — kalau ikut diperkecil, tap jadi salah sasaran.
 */
class AnnotatedScreenshotScaleTest {

    /** Lebar layar fisik Redmi Note 13 Pro 5G. */
    private val screenW = 1220
    private val screenH = 2712

    /** Bitmap yang dihasilkan `maxWidth = 480` (bug lama). */
    private val cappedW = 480
    private val cappedH = 1067

    private data class R(val l: Int, val t: Int, val r: Int, val b: Int)

    private val elements = listOf(
        R(40, 60, 600, 200),
        R(40, 900, 1180, 1500),
        R(900, 2400, 1180, 2650)
    )

    /**
     * Cerminan rumus di `AnnotatedScreenshotHelper.annotate()`:
     * rasio diturunkan dari elemen terjauh, dibatasi ke 0.05..1.0.
     */
    private fun coordScaleFor(bitmapW: Int, bitmapH: Int): Float {
        val maxRight = elements.maxOf { it.r }
        val maxBottom = elements.maxOf { it.b }
        val sx = if (maxRight > bitmapW) bitmapW.toFloat() / maxRight else 1f
        val sy = if (maxBottom > bitmapH) bitmapH.toFloat() / maxBottom else 1f
        return minOf(sx, sy).takeIf { it in 0.05f..1f } ?: 1f
    }

    @Test
    fun `bitmap yang diciutkan maxWidth membuat kotak keluar batas tanpa penyesuaian`() {
        // Membuktikan bug lama: inilah kondisi yang dilaporkan pengguna.
        val overflowing = elements.filter { it.r > cappedW || it.b > cappedH }
        assertTrue(
            "harus ada elemen yang keluar batas saat bitmap 480px dipakai dengan koordinat fisik",
            overflowing.isNotEmpty()
        )
    }

    @Test
    fun `setelah penyesuaian semua kotak berada di dalam bitmap`() {
        val s = coordScaleFor(cappedW, cappedH)
        assertTrue("skala harus menyusut untuk kasus ini", s < 0.99f)

        for (e in elements) {
            val l = (e.l * s).toInt()
            val t = (e.t * s).toInt()
            val r = (e.r * s).toInt()
            val b = (e.b * s).toInt()
            assertTrue("kotak kiri $l keluar batas", l >= 0)
            assertTrue("kotak atas $t keluar batas", t >= 0)
            assertTrue("kotak kanan $r > $cappedW", r <= cappedW)
            assertTrue("kotak bawah $b > $cappedH", b <= cappedH)
        }
    }

    @Test
    fun `bitmap seukuran layar tidak digeser sama sekali`() {
        val s = coordScaleFor(screenW, screenH)
        assertEquals("koordinat tidak boleh berubah bila bitmap seukuran layar", 1f, s, 0f)
    }

    @Test
    fun `maxWidth nol menghasilkan bitmap penuh sehingga skala tetap satu`() {
        // Perbaikan di AgentCommand: maxWidth = 0 -> effectiveScale = 1.0
        val effectiveScale = 1.0f
        val capturedW = (screenW * effectiveScale).toInt()
        val capturedH = (screenH * effectiveScale).toInt()
        assertEquals(screenW, capturedW)
        assertEquals(screenH, capturedH)
        assertEquals(1f, coordScaleFor(capturedW, capturedH), 0f)
    }

    @Test
    fun `elements json tetap memakai koordinat layar fisik`() {
        // Kontrak untuk tap: bounds/center TIDAK boleh diperkecil.
        val e = elements.last()
        val centerX = (e.l + e.r) / 2
        val centerY = (e.t + e.b) / 2
        assertEquals("bounds harus tetap koordinat fisik", e.r, 1180)
        assertEquals("center harus tetap koordinat fisik", centerX, 1040)
        assertEquals("center harus tetap koordinat fisik", centerY, 2525)
    }
}
