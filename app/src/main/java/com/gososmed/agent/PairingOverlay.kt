package com.gososmed.agent

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * v0.9.7 — KARTU PAIRING MELAYANG. Satu implementasi, DUA jenis jendela.
 *
 * ===================== KENAPA FILE INI DIPISAH =====================
 *
 * v0.9.4–v0.9.6 memasang kartu ini dari `AdbPairingService` dengan
 * `TYPE_APPLICATION_OVERLAY`. Itu satu-satunya jalur, dan ia bergantung pada
 * izin `SYSTEM_ALERT_WINDOW` + saklar OEM MIUI/HyperOS yang terpisah
 * ("Tampilkan jendela sembulan saat berjalan di latar belakang"). Bila salah
 * satu belum aktif, `addView` gagal — dan karena kegagalannya hanya masuk log,
 * pengguna melihat PERSIS apa yang dilaporkan: "klik Hubungkan → tidak ada
 * overlay apa pun".
 *
 * Karena itu kartu ini sekarang dipakai oleh DUA jalur independen:
 *
 *  1. JALUR UTAMA — `TYPE_ACCESSIBILITY_OVERLAY`, dipasang oleh
 *     [AgentAccessibilityService] lewat `attachAccessibilityOverlay()`.
 *     Jendela jenis ini HANYA boleh dipasang oleh layanan aksesibilitas dan
 *     **TIDAK memerlukan `SYSTEM_ALERT_WINDOW` sama sekali**, juga tidak
 *     terkena penjagaan pop-up latar belakang OEM — sebab jendelanya milik
 *     layanan sistem, bukan "aplikasi yang menggambar di atas aplikasi lain".
 *     Ini yang membuat overlay akhirnya muncul di HP pengguna.
 *
 *  2. JALUR CADANGAN — `TYPE_APPLICATION_OVERLAY`, dipasang langsung oleh
 *     [com.gososmed.agent.privileged.AdbPairingService] bila layanan
 *     aksesibilitas belum ter-bind. Memerlukan `SYSTEM_ALERT_WINDOW`.
 *
 *  3. Jalur ketiga yang selalu ada: notifikasi dengan RemoteInput. Kartu ini
 *     TIDAK menggantikannya — keduanya hidup bersama, dan notifikasi tetap
 *     satu-satunya jalur yang tidak bisa diblokir OEM.
 *
 * ===================== CATATAN TEKNIS DRAG =====================
 *
 * v0.9.6 memasang `setOnTouchListener` pada LinearLayout kartu. Itu TIDAK
 * berfungsi: `ViewGroup.dispatchTouchEvent` menyerahkan event ke ANAK yang
 * menjadi sasaran sentuhan, sehingga listener induk tidak pernah dipanggil saat
 * jari menyentuh EditText/tombol — praktis seluruh permukaan kartu. Akibatnya
 * kartu tidak bisa digeser sama sekali.
 *
 * Sekarang listener dipasang pada `titleTv` (TextView daun). `View.dispatchTouchEvent`
 * memanggil `OnTouchListener` lebih dulu untuk view daun, jadi geser berfungsi
 * dari mana pun pada baris judul, sementara EditText dan tombol tetap menerima
 * sentuhannya seperti biasa.
 */
object PairingOverlay {

    const val TAG_PORT = "gososmed_port"
    const val TAG_CODE = "gososmed_code"
    const val TAG_STATUS = "gososmed_status"
    const val TAG_PAIR_BTN = "gososmed_pair_btn"
    const val TAG_TITLE = "gososmed_title"
    const val TAG_CLOSE = "gososmed_close"

    private const val CARD_WIDTH_DP = 304

    /**
     * Kartu beserta LayoutParams-nya. `params` wajib dipakai ulang saat drag.
     *
     * v0.9.9 — `wm` adalah WindowManager yang MEMASANG kartu ini, dan kini
     * disimpan di sini. Tanpa referensi ini, kartu hanya bisa dilepas lewat
     * `AgentAccessibilityService.instance`; begitu layanan aksesibilitas
     * terputus (`instance == null`) pelepasan batal dan jendela jadi yatim —
     * persis keluhan "overlay tidak bisa ditutup". Menyimpan `wm` membuat
     * pelepasan tetap mungkin tanpa bergantung pada service itu hidup.
     */
    class Card(val view: View, val params: WindowManager.LayoutParams, val wm: WindowManager)

    /**
     * Bangun kartu. [onPair] dipanggil dengan (port, kode) saat tombol ditekan;
     * [onClose] saat tombol ✕ ditekan.
     *
     * [ctx] menentukan tampilan (tema); untuk jalur aksesibilitas kirim
     * instance [AgentAccessibilityService] supaya jendela dipasang oleh pemilik
     * yang sah.
     *
     * [wm] adalah WindowManager yang AKAN dipakai memasang kartu ini. Ia
     * diperlukan agar fitur geser bisa memanggil `updateViewLayout`. Nilai ini
     * tidak bisa diambil dari `view.windowManager` karena view belum tentu
     * ter-attach saat listener dipanggil.
     */
    fun build(
        ctx: Context,
        wm: WindowManager,
        onPair: (port: Int, code: String) -> Unit,
        onClose: () -> Unit
    ): Card {
        val dp = ctx.resources.displayMetrics.density
        val pad = (12 * dp).toInt()

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = 14 * dp
                setStroke((1.5 * dp).toInt(), Color.parseColor("#0EA5E9"))
            }
            elevation = 25 * dp
        }

        val titleTv = TextView(ctx).apply {
            tag = TAG_TITLE
            text = "⚡ Pairing ADB GoSosmed"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(ctx).apply {
            tag = TAG_CLOSE
            text = "✕"
            contentDescription = "Tutup kartu pairing"
            gravity = Gravity.CENTER
            minWidth = (48 * dp).toInt()
            minHeight = (48 * dp).toInt()
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding((8 * dp).toInt(), (2 * dp).toInt(), (8 * dp).toInt(), (2 * dp).toInt())
            setOnClickListener { onClose() }
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(titleTv)
        header.addView(closeBtn)

        val statusTv = TextView(ctx).apply {
            tag = TAG_STATUS
            text = "Menyimak port pairing (mDNS)…"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setPadding(0, (2 * dp).toInt(), 0, (8 * dp).toInt())
        }

        val portEt = EditText(ctx).apply {
            tag = TAG_PORT
            hint = "Port (otomatis dari mDNS)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = inputBg(dp)
            setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val codeEt = EditText(ctx).apply {
            tag = TAG_CODE
            hint = "Kode 6 angka"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            background = inputBg(dp)
            setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (6 * dp).toInt()
            layoutParams = lp
        }

        val pairBtn = Button(ctx).apply {
            tag = TAG_PAIR_BTN
            text = "Hubungkan Sekarang"
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0EA5E9"))
                cornerRadius = 8 * dp
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (10 * dp).toInt()
            layoutParams = lp
            setOnClickListener {
                val p = portEt.text.toString().trim().toIntOrNull() ?: -1
                val c = codeEt.text.toString().trim().filter { ch -> ch.isDigit() }
                if (p <= 0) {
                    portEt.error = "Port belum terdeteksi — tunggu sebentar"
                    return@setOnClickListener
                }
                if (c.length < 6) {
                    codeEt.error = "Kode harus 6 angka"
                    return@setOnClickListener
                }
                isEnabled = false
                // v0.9.9 — jangan menuliskan IP di sini. Self-pairing memang
                // selalu lewat loopback, tetapi mencetak "127.0.0.1" mentah
                // membuat pengguna mengira IP-nya salah (dan teks ini dulu
                // tidak pernah diperbarui ke hasil nyata). Tujuan koneksi
                // yang sebenarnya dipublikasikan AdbPairingService.
                status(statusTv, "Menghubungkan…", Color.parseColor("#FBBF24"))
                onPair(p, c)
            }
        }

        val hintTv = TextView(ctx).apply {
            text = "Tip: bila kode terbaca otomatis cukup tekan Hubungkan. " +
                "Jika tidak, ketik manual di sini atau dari notifikasi — jangan tutup layar kode."
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }

        card.addView(header)
        card.addView(statusTv)
        card.addView(portEt)
        card.addView(codeEt)
        card.addView(pairBtn)
        card.addView(hintTv)

        val params = layoutParams(ctx, android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        val holder = Card(card, params, wm)

        // BACK harus menutup kartu, bukan tampak "tidak merespons" karena
        // jendela overlay yang fokus menelan tombol tanpa handler.
        card.isFocusable = true
        card.isFocusableInTouchMode = true
        card.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onClose()
                true
            } else {
                false
            }
        }

        // Drag dari baris judul. Dipasang pada TextView daun (lihat KDoc kelas).
        titleTv.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - touchX).toInt()
                        params.y = startY + (event.rawY - touchY).toInt()
                        try {
                            wm.updateViewLayout(card, params)
                        } catch (_: Throwable) {
                        }
                        return true
                    }
                }
                return false
            }
        })

        return holder
    }

    /** LayoutParams untuk jenis jendela [type]. `type` ditentukan pemanggil. */
    fun layoutParams(ctx: Context, type: Int): WindowManager.LayoutParams {
        val dp = ctx.resources.displayMetrics.density
        return WindowManager.LayoutParams(
            (CARD_WIDTH_DP * dp).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (48 * dp).toInt()
        }
    }

    /** Terapkan port hasil discovery; discovery lebih otoritatif daripada isian lama. */
    fun setPort(card: Card?, port: Int) {
        if (card == null || port <= 0) return
        val et = card.view.findViewWithTag<EditText>(TAG_PORT) ?: return
        val value = port.toString()
        if (et.text.toString() != value) et.setText(value)
    }

    /** Isi kode hasil pembacaan dialog sistem (tetap lokal; jangan log nilainya). */
    fun setCode(card: Card?, code: String) {
        if (card == null) return
        val normalized = code.filter { it.isDigit() }.take(6)
        if (normalized.length != 6) return
        val et = card.view.findViewWithTag<EditText>(TAG_CODE) ?: return
        if (et.text.toString() != normalized) {
            et.setText(normalized)
            et.setSelection(normalized.length)
        }
    }

    /** Perbarui baris status; tombol hanya diaktifkan lagi bila sesi tidak sedang berjalan. */
    fun status(card: Card?, msg: String, color: Int, enablePairButton: Boolean = true) {
        if (card == null) return
        status(card.view.findViewWithTag(TAG_STATUS), msg, color)
        card.view.findViewWithTag<Button>(TAG_PAIR_BTN)?.isEnabled = enablePairButton
    }

    private fun status(tv: TextView?, msg: String, color: Int) {
        tv?.text = msg
        tv?.setTextColor(color)
    }

    private fun inputBg(dp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#020617"))
        cornerRadius = 6 * dp
        setStroke((1 * dp).toInt(), Color.parseColor("#334155"))
    }
}
