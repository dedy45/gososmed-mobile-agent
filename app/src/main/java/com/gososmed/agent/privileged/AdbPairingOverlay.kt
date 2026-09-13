package com.gososmed.agent.privileged

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.gososmed.agent.AgentLog
import com.gososmed.agent.AgentOverlay
import io.github.muntashirakon.adb.android.AdbMdns
import io.github.muntashirakon.adb.android.AndroidUtils
import java.net.InetAddress

/**
 * v0.9.4 — Floating Window Pairing Overlay & Notification Prompt.
 *
 * MENGAPA INI MUTLAK DIPERLUKAN (UX & Invarian Android 11+):
 * Pada Android Wireless Debugging, pop-up "Pair with pairing code" di Setelan
 * adalah modal dialog sementara. Bila pengguna beralih aplikasi (pindah ke GoSosmed),
 * sistem Android OTOMATIS MENUTUP dialog tersebut dan mengenerate port serta 6-digit
 * code BARU.
 *
 * Solusi:
 * 1. Saat pairing dimulai, luncurkan Floating Overlay Window (memakai izin SYSTEM_ALERT_WINDOW)
 *    yang melayang di atas layar Setelan Wireless Debugging.
 * 2. Menjalankan listener AdbMdns (_adb-tls-pairing._tcp) untuk mendeteksi host/port pairing
 *    secara otomatis jika perangkat menyiarkannya.
 * 3. Mengambil IP Wi-Fi aktual (bukan 127.0.0.1 statis) via AndroidUtils.getHostIpAddress.
 * 4. Pengguna bisa langsung memasukkan 6-digit code pada overlay tanpa pop-up tertutup!
 */
object AdbPairingOverlay {

    private const val TAG = "GoAgentPairOverlay"

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var floatView: View? = null

    @Volatile
    private var adbMdns: AdbMdns? = null

    @Volatile
    private var discoveredHost: String? = null

    @Volatile
    private var discoveredPort: Int = -1

    /** Menampilkan Floating Window Overlay untuk input pairing code */
    fun show(context: Context, onComplete: () -> Unit = {}) {
        val app = context.applicationContext
        if (!AgentOverlay.canDraw(app)) {
            Log.w(TAG, "Izin overlay belum aktif, tidak dapat memunculkan floating window")
            return
        }

        main.post {
            hide(app) // Bersihkan jika ada instance lama

            val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post

            // Deteksi IP lokal awal
            val localIp = AndroidUtils.getHostIpAddress(app)
            discoveredHost = localIp

            // Mulai mDNS discovery untuk pairing port otomatis
            startMdnsPairingDiscovery(app)

            val dp = app.resources.displayMetrics.density
            val pad = (14 * dp).toInt()

            val cardLayout = LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B")) // Slate 800
                    cornerRadius = 16 * dp
                    setStroke((1.5 * dp).toInt(), Color.parseColor("#38BDF8")) // Sky blue border
                }
                elevation = 20 * dp
            }

            val titleTv = TextView(app).apply {
                text = "⚡ GoSosmed ADB Pairing"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }

            val descTv = TextView(app).apply {
                text = "Buka: Setelan > Opsi Pengembang > Debug nirkabel > 'Pairing baru'. Ketik port & kode 6-angka di bawah:"
                setTextColor(Color.parseColor("#94A3B8")) // Slate 400
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
            }

            val hostPortLayout = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val hostEt = EditText(app).apply {
                hint = "IP (cth: 192.168.x.x)"
                setText(localIp)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                background = createInputBg(dp)
                setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.8f)
            }

            val portEt = EditText(app).apply {
                tag = "portEt"
                hint = "Port"
                inputType = InputType.TYPE_CLASS_NUMBER
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                background = createInputBg(dp)
                setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
                lp.marginStart = (6 * dp).toInt()
                layoutParams = lp
            }

            hostPortLayout.addView(hostEt)
            hostPortLayout.addView(portEt)

            val codeEt = EditText(app).apply {
                hint = "Kode Pairing 6 Digit"
                inputType = InputType.TYPE_CLASS_NUMBER
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                background = createInputBg(dp)
                setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = (8 * dp).toInt()
                layoutParams = lp
            }

            val statusTv = TextView(app).apply {
                tag = "statusTv"
                text = "Mencari port pairing otomatis via mDNS..."
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, (4 * dp).toInt(), 0, (6 * dp).toInt())
            }

            val btnLayout = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = (8 * dp).toInt()
                layoutParams = lp
            }

            val cancelBtn = Button(app).apply {
                text = "Tutup"
                setTextColor(Color.parseColor("#94A3B8"))
                setBackgroundColor(Color.TRANSPARENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setOnClickListener {
                    hide(app)
                    onComplete()
                }
            }

            val pairBtn = Button(app).apply {
                text = "Pasangkan"
                setTextColor(Color.BLACK)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#38BDF8"))
                    cornerRadius = 8 * dp
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setOnClickListener {
                    val h = hostEt.text.toString().trim().ifEmpty { localIp }
                    val p = portEt.text.toString().trim().toIntOrNull() ?: discoveredPort
                    val c = codeEt.text.toString().trim()

                    if (p <= 0) {
                        portEt.error = "Isi port!"
                        return@setOnClickListener
                    }
                    if (c.length < 6) {
                        codeEt.error = "6 digit!"
                        return@setOnClickListener
                    }

                    isEnabled = false
                    statusTv.text = "Memasangkan ke $h:$p..."
                    statusTv.setTextColor(Color.YELLOW)

                    Thread {
                        val (ok, reason) = AdbPairingController.pair(h, p, c)
                        main.post {
                            if (ok) {
                                statusTv.text = "✓ Pairing Berhasil! Menyambungkan..."
                                statusTv.setTextColor(Color.GREEN)
                                AgentLog.event("ADB Pairing Berhasil ($h:$p)")
                                main.postDelayed({
                                    hide(app)
                                    onComplete()
                                }, 1500)
                            } else {
                                isEnabled = true
                                statusTv.text = "✗ Gagal: ${reason.take(60)}"
                                statusTv.setTextColor(Color.parseColor("#F87171"))
                                AgentLog.event("ADB Pairing Gagal: $reason")
                            }
                        }
                    }.start()
                }
            }

            btnLayout.addView(cancelBtn)
            btnLayout.addView(pairBtn)

            cardLayout.addView(titleTv)
            cardLayout.addView(descTv)
            cardLayout.addView(hostPortLayout)
            cardLayout.addView(codeEt)
            cardLayout.addView(statusTv)
            cardLayout.addView(btnLayout)

            // LayoutParams Floating Window
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                (320 * dp).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (40 * dp).toInt()
            }

            try {
                wm.addView(cardLayout, params)
                floatView = cardLayout
            } catch (e: Exception) {
                Log.e(TAG, "Gagal menempelkan floating overlay window", e)
            }
        }
    }

    /** Menutup Floating Window Overlay dan stop discovery */
    fun hide(context: Context) {
        main.post {
            stopMdnsPairingDiscovery()
            floatView?.let { v ->
                try {
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    wm?.removeView(v)
                } catch (e: Exception) {
                    Log.w(TAG, "removeView error", e)
                }
                floatView = null
            }
        }
    }

    private fun startMdnsPairingDiscovery(context: Context) {
        try {
            stopMdnsPairingDiscovery()
            adbMdns = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host: InetAddress?, port: Int ->
                if (host != null && port > 0) {
                    discoveredHost = host.hostAddress
                    discoveredPort = port
                    main.post {
                        floatView?.let { v ->
                            val portEt = v.findViewWithTag<EditText>("portEt")
                            if (portEt != null && portEt.text.isEmpty()) {
                                portEt.setText(port.toString())
                            }
                            val statusTv = v.findViewWithTag<TextView>("statusTv")
                            statusTv?.text = "✓ Port pairing terdeteksi otomatis: $port"
                            statusTv?.setTextColor(Color.GREEN)
                        }
                    }
                }
            }
            adbMdns?.start()
        } catch (e: Exception) {
            Log.w(TAG, "mDNS pairing discovery error", e)
        }
    }

    private fun stopMdnsPairingDiscovery() {
        try {
            adbMdns?.stop()
            adbMdns = null
        } catch (_: Exception) {}
    }

    private fun createInputBg(dp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#0F172A")) // Slate 900
        cornerRadius = 6 * dp
        setStroke((1 * dp).toInt(), Color.parseColor("#475569")) // Slate 600
    }
}
