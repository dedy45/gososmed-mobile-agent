package com.gososmed.agent

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AgentOverlay memasang SATU overlay window 1x1 transparan milik agent.
 *
 * MENGAPA INI ADA (akar masalah harvest, terbukti di produksi 2026-09-11):
 * `startApp` selalu melapor sukses tetapi foreground tetap `com.miui.home`
 * di kelima platform. Penyebabnya BUKAN cold start lambat, melainkan
 * Background Activity Launch (BAL) yang dibatasi Android sejak API 29:
 * AccessibilityService tidak punya window yang terlihat, sehingga setiap
 * `startActivity` / `LauncherApps.startMainActivity` dari service dihitung
 * sebagai launch dari background dan DITELAN sistem tanpa melempar exception.
 *
 * Pengecualian BAL yang didokumentasikan Android: app yang memegang window
 * overlay (SYSTEM_ALERT_WINDOW) dianggap punya window terlihat. Inilah pola
 * yang dipakai mobilerun-portal (droidrun) dan satu-satunya kapabilitas
 * penting yang belum dimiliki agent ini.
 *
 * Desain sengaja minimal dan tidak mengganggu pemilik HP:
 *  - ukuran 1x1 piksel, warna transparan, di pojok kiri-atas;
 *  - FLAG_NOT_TOUCHABLE + FLAG_NOT_FOCUSABLE -> tidak pernah mencuri sentuhan
 *    atau fokus keyboard, jadi tidak bisa merusak automasi maupun pemakaian
 *    normal HP;
 *  - idempoten: [ensure] aman dipanggil sebelum setiap launch.
 *
 * JUJUR soal batasnya: overlay ini membebaskan pembatasan BAL milik AOSP.
 * MIUI/HyperOS masih punya izin terpisah ("Display pop-up windows while
 * running in the background") yang hanya bisa dinyalakan manual oleh pemilik
 * HP. Karena itu [ensure] TIDAK dianggap sebagai jaminan sukses — kebenaran
 * tetap dibaca dari foreground setelah launch (lihat startAppVerified).
 */
object AgentOverlay {

    private const val TAG = "GoAgent"

    @Volatile
    private var view: View? = null

    private val main = Handler(Looper.getMainLooper())

    /** true bila pemilik HP sudah memberi izin "tampil di atas app lain". */
    fun canDraw(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** true bila overlay agent sedang terpasang (window terlihat aktif). */
    fun isAttached(): Boolean = view != null

    /**
     * Memastikan overlay terpasang. Mengembalikan true bila terpasang (atau
     * sudah terpasang sebelumnya), false bila izin belum diberikan atau
     * WindowManager menolak.
     *
     * WindowManager.addView WAJIB dipanggil di main looper; pemanggil kita
     * (AgentCommand.execute lewat WS) berjalan di thread IO, jadi kita post ke
     * main dan menunggu sebentar agar hasilnya deterministik bagi caller.
     */
    fun ensure(ctx: Context): Boolean {
        if (!canDraw(ctx)) return false
        view?.let { return true }
        val app = ctx.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            attach(app)
            return view != null
        }
        val latch = CountDownLatch(1)
        main.post {
            try {
                attach(app)
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return view != null
    }

    private fun attach(ctx: Context) {
        if (view != null) return
        try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val lp = WindowManager.LayoutParams(
                1,
                1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            val v = View(ctx).apply { setBackgroundColor(Color.TRANSPARENT) }
            wm.addView(v, lp)
            view = v
            Log.i(TAG, "overlay 1x1 terpasang (pengecualian BAL aktif)")
        } catch (e: Exception) {
            Log.w(TAG, "overlay gagal dipasang: ${e.message}")
            view = null
        }
    }

    /** Melepas overlay (dipakai saat service dimatikan). Aman dipanggil ganda. */
    fun release(ctx: Context) {
        val v = view ?: return
        val app = ctx.applicationContext
        val detach = Runnable {
            try {
                val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(v)
            } catch (e: Exception) {
                Log.w(TAG, "overlay gagal dilepas: ${e.message}")
            }
            view = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) detach.run() else main.post(detach)
    }
}
