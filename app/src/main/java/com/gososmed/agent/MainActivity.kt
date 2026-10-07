package com.gososmed.agent

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.google.android.material.tabs.TabLayout
import com.google.android.material.materialswitch.MaterialSwitch
import com.gososmed.agent.privileged.AdbPairingController
import com.gososmed.agent.privileged.AdbPairingService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
/**
 * UI produksi agent (v0.5.0) — tab-based, TANPA scroll halaman panjang.
 *
 * Tiga tab tetap: Beranda (status perangkat + hubungkan), Setup (izin),
 * Log (aktivitas). Banner status selalu terlihat di atas tab. Panel log
 * bergaya console dengan warna per jenis entri (✓ hijau / ✗ merah /
 * kejadian biru) plus aksi Jeda / Salin / Bersihkan.
 *
 * Prinsip pairing tidak berubah: pengguna TIDAK mengetik URL server; jalur
 * utama auto-pairing lewat deep link `gososmed://pair?ws=<url>&code=<kode>`,
 * cadangan ketik kode 8 karakter. Mode debug (override URL + uji lokal)
 * tersembunyi — tap 7× teks versi. WS tetap dimiliki AgentForegroundService.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusBigTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var versionTv: TextView
    private lateinit var btnLangToggle: TextView
    private lateinit var btnThemeToggle: TextView
    private lateinit var deviceInfoTv: TextView
    private lateinit var pairTv: TextView
    private lateinit var permA11yTv: TextView
    private lateinit var permOverlayTv: TextView
    private lateinit var permBatteryTv: TextView
    private lateinit var permNotifTv: TextView
    private lateinit var adbStatusTv: TextView
    private lateinit var adbPairBtn: Button
    private lateinit var adbForgetBtn: Button
    private lateinit var tabLayout: TabLayout
    private lateinit var panelBeranda: View
    private lateinit var panelDiagnostik: View
    private lateinit var panelSetup: View
    private lateinit var panelLog: View
    private lateinit var btnNavHome: View
    private lateinit var btnNavDiag: View
    private lateinit var btnNavSetup: View
    private lateinit var btnNavLog: View
    private lateinit var ivNavHome: ImageView
    private lateinit var ivNavDiag: ImageView
    private lateinit var ivNavSetup: ImageView
    private lateinit var ivNavLog: ImageView
    private lateinit var tvNavHome: TextView
    private lateinit var tvNavDiag: TextView
    private lateinit var tvNavSetup: TextView
    private lateinit var tvNavLog: TextView
    private lateinit var logTv: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logCountTv: TextView
    private lateinit var logPauseBtn: Button
    private lateinit var logCopyBtn: Button
    private lateinit var logClearBtn: Button
    private lateinit var wsUrlEt: EditText
    private lateinit var pairCodeEt: EditText
    private lateinit var connectBtn: Button
    private lateinit var disconnectBtn: Button
    private lateinit var dumpBtn: Button
    private lateinit var packageBtn: Button
    private lateinit var backBtn: Button
    private lateinit var homeBtn: Button
    private lateinit var tapBtn: Button
    private lateinit var batteryBtn: Button
    private lateinit var notifBtn: Button
    private lateinit var openA11yBtn: Button
    private lateinit var openOverlayBtn: Button
    private lateinit var debugSection: View
    private lateinit var updateInfoTv: TextView
    private lateinit var checkUpdateBtn: Button
    private lateinit var downloadUpdateBtn: Button

    // Telemetri Live & Kontrol Ekstrem
    private lateinit var tvTelemetryBattery: TextView
    private lateinit var tvTelemetryTemp: TextView
    private lateinit var tvTelemetryBatteryAlert: TextView
    private lateinit var tvTelemetryNetwork: TextView
    private lateinit var tvTelemetryApps: TextView
    private lateinit var btnEchoTest: Button
    private lateinit var btnScanQr: Button

    // Mode Server (Layar Redup & Anti-Lockscreen)
    private lateinit var switchServerMode: MaterialSwitch
    private lateinit var serverModeOverlay: View
    private lateinit var serverClockTv: TextView
    private lateinit var serverStatusTv: TextView
    private lateinit var serverInfoTv: TextView

    // Optimasi Pabrikan HP (OEM)
    private lateinit var tvOemTitle: TextView
    private lateinit var tvOemDesc: TextView
    private lateinit var btnOemSettings: Button

    private val qrScanLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val contents = result.data?.getStringExtra("SCAN_RESULT")?.trim().orEmpty()
            if (contents.isNotEmpty()) {
                handleScannedPairCode(contents)
            }
        }
    }

    private val deviceId: String by lazy { loadOrCreateDeviceId() }
    private var versionTapCount = 0
    // v0.7.1: dialog izin overlay hanya sekali per sesi UI (tidak menghantui
    // pemilik HP setiap kali activity resume).
    private var overlayAsked = false
    private var lastStatus = ""
    /**
     * v0.9.6 — Bila pengguna menekan "Hubungkan ADB" sementara izin Notifikasi
     * belum ada, kami meminta izin dulu dan MELANJUTKAN panduan pairing secara
     * otomatis begitu izin diberikan. Tanpa flag ini, pengguna harus menekan
     * tombol dua kali (membingungkan, dan terasa seperti bug).
     */
    private var pendingPairAfterNotif = false
    private var logPaused = false
    private var pausedDirty = false
    private val logSb = SpannableStringBuilder()
    private val a11yStateChangeListener =
        android.view.accessibility.AccessibilityManager.AccessibilityStateChangeListener {
            if (!isFinishing && !isDestroyed) runOnUiThread { refreshStatus() }
        }
    private val a11yUiListener: () -> Unit = {
        if (!isFinishing && !isDestroyed) runOnUiThread { refreshStatus() }
    }
    private val adbUiListener: () -> Unit = {
        if (!isFinishing && !isDestroyed) runOnUiThread { refreshAdbStatus() }
    }

    private val updateHttpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(AgentForegroundService.EXTRA_STATUS) ?: return
            val rejected = intent.getBooleanExtra(AgentForegroundService.EXTRA_REJECTED, false)
            lastStatus = status
            runOnUiThread {
                if (rejected) {
                    pairTv.text = "Kode pairing ditolak atau kedaluwarsa.\nTerbitkan kode baru di dasbor GoSosmed, lalu coba lagi."
                }
                refreshStatus()
            }
        }
    }

    private fun prefs() = getSharedPreferences("agent", MODE_PRIVATE)

    private fun loadWsUrl(): String = prefs().getString("ws_url", "") ?: ""

    private fun loadPairingCode(): String = prefs().getString("pairing_code", "") ?: ""
    override fun attachBaseContext(newBase: Context) {
        val savedLang = newBase.getSharedPreferences("agent", MODE_PRIVATE)
            .getString("app_lang", "id") ?: "id"
        val locale = Locale(savedLang)
        Locale.setDefault(locale)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocales(android.os.LocaleList(locale))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        val isDark = prefs().getBoolean("theme_dark", true)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Foreground service menjaga proses tetap hidup; service memulihkan
        // koneksi tersimpan sendiri (auto-reconnect setelah kill/reboot).
        startForegroundService(Intent(this, AgentForegroundService::class.java))

        statusBigTv = findViewById(R.id.statusBigTv)
        statusTv = findViewById(R.id.statusTv)
        versionTv = findViewById(R.id.versionTv)
        btnLangToggle = findViewById(R.id.btnLangToggle)
        btnThemeToggle = findViewById(R.id.btnThemeToggle)
        deviceInfoTv = findViewById(R.id.deviceInfoTv)
        pairTv = findViewById(R.id.pairTv)
        permA11yTv = findViewById(R.id.permA11yTv)
        permOverlayTv = findViewById(R.id.permOverlayTv)
        permBatteryTv = findViewById(R.id.permBatteryTv)
        permNotifTv = findViewById(R.id.permNotifTv)
        adbStatusTv = findViewById(R.id.adbStatusTv)
        adbPairBtn = findViewById(R.id.adbPairBtn)
        adbForgetBtn = findViewById(R.id.adbForgetBtn)
        tabLayout = findViewById(R.id.tabLayout)
        panelBeranda = findViewById(R.id.panelBeranda)
        panelDiagnostik = findViewById(R.id.panelDiagnostik)
        panelSetup = findViewById(R.id.panelSetup)
        panelLog = findViewById(R.id.panelLog)
        btnNavHome = findViewById(R.id.btnNavHome)
        btnNavDiag = findViewById(R.id.btnNavDiag)
        btnNavSetup = findViewById(R.id.btnNavSetup)
        btnNavLog = findViewById(R.id.btnNavLog)
        ivNavHome = findViewById(R.id.ivNavHome)
        ivNavDiag = findViewById(R.id.ivNavDiag)
        ivNavSetup = findViewById(R.id.ivNavSetup)
        ivNavLog = findViewById(R.id.ivNavLog)
        tvNavHome = findViewById(R.id.tvNavHome)
        tvNavDiag = findViewById(R.id.tvNavDiag)
        tvNavSetup = findViewById(R.id.tvNavSetup)
        tvNavLog = findViewById(R.id.tvNavLog)

        btnNavHome.setOnClickListener { showPanel(0) }
        btnNavSetup.setOnClickListener { showPanel(1) }
        btnNavDiag.setOnClickListener { showPanel(2) }
        btnNavLog.setOnClickListener { showPanel(3) }

        // In-App Theme Switcher (Ember 2048 Dark ↔ Glacier Glass Light)
        btnThemeToggle.text = if (isDark) "🌙" else "☀️"
        btnThemeToggle.setOnClickListener {
            val currentDark = prefs().getBoolean("theme_dark", true)
            val newDark = !currentDark
            prefs().edit()
                .putBoolean("theme_dark", newDark)
                .putString("theme_mode", if (newDark) "dark" else "light")
                .apply()
            btnThemeToggle.text = if (newDark) "🌙" else "☀️"
            AppCompatDelegate.setDefaultNightMode(
                if (newDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        // In-App Language Switcher (i18n ID ↔ EN 1-Tap)
        val activeLang = prefs().getString("app_lang", "id") ?: "id"
        val isEn = activeLang.startsWith("en")
        btnLangToggle.text = if (isEn) "ID" else "EN"
        btnLangToggle.setOnClickListener {
            val nextLang = if (isEn) "id" else "en"
            prefs().edit().putString("app_lang", nextLang).apply()
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(nextLang)
            )
            recreate()
        }
        logTv = findViewById(R.id.logTv)
        logScroll = findViewById(R.id.logScroll)
        logCountTv = findViewById(R.id.logCountTv)
        logPauseBtn = findViewById(R.id.logPauseBtn)
        logCopyBtn = findViewById(R.id.logCopyBtn)
        logClearBtn = findViewById(R.id.logClearBtn)
        wsUrlEt = findViewById(R.id.wsUrlEt)
        pairCodeEt = findViewById(R.id.pairCodeEt)
        connectBtn = findViewById(R.id.connectBtn)
        disconnectBtn = findViewById(R.id.disconnectBtn)
        dumpBtn = findViewById(R.id.dumpBtn)
        packageBtn = findViewById(R.id.packageBtn)
        backBtn = findViewById(R.id.backBtn)
        homeBtn = findViewById(R.id.homeBtn)
        tapBtn = findViewById(R.id.tapBtn)
        batteryBtn = findViewById(R.id.batteryBtn)
        notifBtn = findViewById(R.id.notifBtn)
        openA11yBtn = findViewById(R.id.openAccessibilityBtn)
        openOverlayBtn = findViewById(R.id.openOverlayBtn)
        debugSection = findViewById(R.id.debugSection)
        updateInfoTv = findViewById(R.id.updateInfoTv)
        checkUpdateBtn = findViewById(R.id.checkUpdateBtn)
        downloadUpdateBtn = findViewById(R.id.downloadUpdateBtn)

        // Telemetri
        tvTelemetryBattery = findViewById(R.id.tvTelemetryBattery)
        tvTelemetryTemp = findViewById(R.id.tvTelemetryTemp)
        tvTelemetryBatteryAlert = findViewById(R.id.tvTelemetryBatteryAlert)
        tvTelemetryNetwork = findViewById(R.id.tvTelemetryNetwork)
        tvTelemetryApps = findViewById(R.id.tvTelemetryApps)
        btnEchoTest = findViewById(R.id.btnEchoTest)
        btnScanQr = findViewById(R.id.btnScanQr)

        // Mode Server
        switchServerMode = findViewById(R.id.switchServerMode)
        serverModeOverlay = findViewById(R.id.serverModeOverlay)
        serverClockTv = findViewById(R.id.serverClockTv)
        serverStatusTv = findViewById(R.id.serverStatusTv)
        serverInfoTv = findViewById(R.id.serverInfoTv)

        // OEM
        tvOemTitle = findViewById(R.id.tvOemTitle)
        tvOemDesc = findViewById(R.id.tvOemDesc)
        btnOemSettings = findViewById(R.id.btnOemSettings)

        btnEchoTest.setOnClickListener { performEchoTest() }
        btnScanQr.setOnClickListener { startQrScan() }
        setupServerMode()
        setupOemCard()
        checkUpdateBtn.setOnClickListener { checkUpdateNow() }
        downloadUpdateBtn.setOnClickListener { openApkDownload() }
        // Jalur manual cadangan: tahan teks versi (tap biasa tetap mode debug).
        versionTv.setOnLongClickListener { checkUpdateNow(); true }
        refreshUpdateState()

        setupTabs()

        versionTv.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        // Mode debug: tap 7× pada versi (pola developer options).
        versionTv.setOnClickListener {
            versionTapCount++
            if (versionTapCount >= 7) {
                debugSection.visibility =
                    if (debugSection.visibility == View.GONE) View.VISIBLE else View.GONE
                versionTapCount = 0
                if (debugSection.visibility == View.VISIBLE && wsUrlEt.text.isEmpty()) {
                    loadWsUrl().takeIf { it.isNotEmpty() }?.let { wsUrlEt.setText(it) }
                }
            }
        }

        pairTv.text = getString(R.string.device_id_format, deviceId)
        loadPairingCode().takeIf { it.isNotEmpty() }?.let { pairCodeEt.setText(it) }
        renderDeviceInfo()

        connectBtn.setOnClickListener { connectWs() }
        disconnectBtn.setOnClickListener { disconnectWs() }
        dumpBtn.setOnClickListener { AgentLog.event(runDump()) }
        packageBtn.setOnClickListener { AgentLog.event(runPackage()) }
        backBtn.setOnClickListener { doGlobal { it.pressBack() } }
        homeBtn.setOnClickListener { doGlobal { it.pressHome() } }
        tapBtn.setOnClickListener { doTapFirstClickable() }

        openA11yBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        openOverlayBtn.setOnClickListener { openOverlaySettings() }
        batteryBtn.setOnClickListener { requestBatteryExemption() }
        notifBtn.setOnClickListener { requestNotifPermission() }
        // v0.9.0 — Langkah 3 (opsional): otomasi lanjutan lewat ADB lokal.
        adbPairBtn.setOnClickListener { showAdbPairDialog() }
        adbForgetBtn.setOnClickListener { confirmAdbForget() }

        // Panel log: render isi yang sudah ada + dengarkan entri baru.
        // Pemilik HP selalu melihat apa yang diminta server (transparansi).
        logPauseBtn.setOnClickListener { toggleLogPause() }
        logClearBtn.setOnClickListener { clearLog() }
        logCopyBtn.setOnClickListener { copyLog() }
        AgentLog.listener = { entry -> runOnUiThread { appendLogEntry(entry) } }
        // v0.9.7 — tampilkan sebab crash terakhir (bila ada). Sebelum ini,
        // exception yang menjatuhkan proses hilang bersama prosesnya, sehingga
        // gejala "klik Hubungkan → Langkah 1 mati" tidak bisa ditelusuri.
        AgentApp.takeLastCrash(this)?.let { crash ->
            AgentLog.add("crash sebelumnya", false, 0L, crash.replace("\n", "  |  "))
        }
        rerenderLog()
        // Dengarkan perubahan status langsung dari service di proses ini (realtime)
        AgentAccessibilityService.addStateListener(a11yUiListener)
        AdbPairingController.addStateListener(adbUiListener)


        // Auto-pairing via deep link (bila activity dibuka dari tautan dasbor).
        handlePairIntent(intent)
        refreshStatus()
    }

    // ---- Cek update APK (v0.7.0) ----

    private fun refreshUpdateState() {
        val current = BuildConfig.VERSION_NAME
        when {
            AgentUpdateState.isNewer(current) -> {
                updateInfoTv.text = "Update tersedia: v${AgentUpdateState.latestVersion} (terpasang v$current)."
                downloadUpdateBtn.visibility =
                    if (AgentUpdateState.apkUrl.isNotEmpty()) View.VISIBLE else View.GONE
            }
            AgentUpdateState.latestVersion.isNotEmpty() -> {
                updateInfoTv.text = "Sudah versi terbaru (v$current)."
                downloadUpdateBtn.visibility = View.GONE
            }
        }
    }

    private fun checkUpdateNow() {
        updateInfoTv.text = "Memeriksa update…"
        Thread {
            try {
                val userAgent = "GoSosmedAgent/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; ${Build.MODEL})"
                val releasesUrl = "https://api.github.com/repos/dedy45/gososmed-mobile-agent/releases?per_page=5"
                val req = okhttp3.Request.Builder()
                    .url(releasesUrl)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", userAgent)
                    .build()

                var bestTag = ""
                var bestApk = ""
                val current = BuildConfig.VERSION_NAME
                val isCurrentDev = current.contains("-dev")

                val resp = updateHttpClient.newCall(req).execute()
                resp.use { r ->
                    if (r.isSuccessful) {
                        val body = r.body?.string().orEmpty()
                        val array = org.json.JSONArray(body)
                        for (i in 0 until array.length()) {
                            val rel = array.getJSONObject(i)
                            if (rel.optBoolean("draft", false)) continue
                            val isPre = rel.optBoolean("prerelease", false)
                            val tag = rel.optString("tag_name", "").removePrefix("v")
                            if (tag.isEmpty()) continue

                            var apkUrl = ""
                            val assets = rel.optJSONArray("assets")
                            if (assets != null) {
                                for (j in 0 until assets.length()) {
                                    val a = assets.getJSONObject(j)
                                    if (a.optString("name", "").endsWith(".apk")) {
                                        apkUrl = a.optString("browser_download_url", "")
                                        break
                                    }
                                }
                            }

                            if (AgentUpdateState.compareVersions(tag, current) > 0) {
                                if (!isCurrentDev && isPre && bestTag.isNotEmpty()) {
                                    continue
                                }
                                bestTag = tag
                                bestApk = apkUrl
                                if (!isPre) break
                            } else if (bestTag.isEmpty()) {
                                bestTag = tag
                                bestApk = apkUrl
                            }
                        }
                    } else if (r.code == 404 || r.code == 403) {
                        val latestReq = okhttp3.Request.Builder()
                            .url("https://api.github.com/repos/dedy45/gososmed-mobile-agent/releases/latest")
                            .header("Accept", "application/vnd.github+json")
                            .header("User-Agent", userAgent)
                            .build()
                        updateHttpClient.newCall(latestReq).execute().use { lr ->
                            if (!lr.isSuccessful) throw IllegalStateException("GitHub HTTP ${lr.code}")
                            val json = org.json.JSONObject(lr.body?.string().orEmpty())
                            bestTag = json.optString("tag_name", "").removePrefix("v")
                            val assets = json.optJSONArray("assets")
                            if (assets != null) {
                                for (i in 0 until assets.length()) {
                                    val a = assets.getJSONObject(i)
                                    if (a.optString("name", "").endsWith(".apk")) {
                                        bestApk = a.optString("browser_download_url", "")
                                        break
                                    }
                                }
                            }
                        }
                    } else {
                        throw IllegalStateException("GitHub HTTP ${r.code}")
                    }
                }

                if (bestTag.isNotEmpty()) {
                    AgentUpdateState.latestVersion = bestTag
                    if (bestApk.isNotEmpty()) AgentUpdateState.apkUrl = bestApk
                    AgentUpdateState.checkedAt = System.currentTimeMillis()
                }
                val msg = if (AgentUpdateState.isNewer(current)) {
                    "Update tersedia: v${AgentUpdateState.latestVersion}"
                } else {
                    "Sudah versi terbaru (v$current)"
                }
                runOnUiThread {
                    refreshUpdateState()
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    AgentLog.event("cek update: $msg")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateInfoTv.text = "Gagal cek update: ${e.message}"
                    AgentLog.event("cek update gagal: ${e.message}")
                }
            }
        }.start()
    }

    private fun openApkDownload() {
        val url = AgentUpdateState.apkUrl
        if (url.isEmpty()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak bisa membuka tautan unduhan", Toast.LENGTH_LONG).show()
        }
    }

    // ---- Tab & Sticky Bottom Navigation ----

    private fun setupTabs() {
        tabLayout.removeAllTabs()
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_beranda))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_setup))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_diagnostik))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_log))
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPanel(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        val savedTab = prefs().getInt("active_tab", 0).coerceIn(0, 3)
        showPanel(savedTab)
    }

    private fun showPanel(index: Int) {
        prefs().edit().putInt("active_tab", index).apply()
        panelBeranda.visibility = if (index == 0) View.VISIBLE else View.GONE
        panelSetup.visibility = if (index == 1) View.VISIBLE else View.GONE
        panelDiagnostik.visibility = if (index == 2) View.VISIBLE else View.GONE
        panelLog.visibility = if (index == 3) View.VISIBLE else View.GONE

        // Update Bottom Nav Bar Visual State (1: Beranda, 2: Setup, 3: Diagnostik, 4: Log)
        val primaryColor = ContextCompat.getColor(this, R.color.primary)
        val mutedColor = ContextCompat.getColor(this, R.color.text_secondary)

        btnNavHome.setBackgroundResource(if (index == 0) R.drawable.bg_bottom_nav_item_active else 0)
        btnNavSetup.setBackgroundResource(if (index == 1) R.drawable.bg_bottom_nav_item_active else 0)
        btnNavDiag.setBackgroundResource(if (index == 2) R.drawable.bg_bottom_nav_item_active else 0)
        btnNavLog.setBackgroundResource(if (index == 3) R.drawable.bg_bottom_nav_item_active else 0)

        ivNavHome.alpha = if (index == 0) 1.0f else 0.5f
        ivNavSetup.alpha = if (index == 1) 1.0f else 0.5f
        ivNavDiag.alpha = if (index == 2) 1.0f else 0.5f
        ivNavLog.alpha = if (index == 3) 1.0f else 0.5f

        tvNavHome.setTextColor(if (index == 0) primaryColor else mutedColor)
        tvNavSetup.setTextColor(if (index == 1) primaryColor else mutedColor)
        tvNavDiag.setTextColor(if (index == 2) primaryColor else mutedColor)
        tvNavLog.setTextColor(if (index == 3) primaryColor else mutedColor)

        tvNavHome.paint.isFakeBoldText = (index == 0)
        tvNavSetup.paint.isFakeBoldText = (index == 1)
        tvNavDiag.paint.isFakeBoldText = (index == 2)
        tvNavLog.paint.isFakeBoldText = (index == 3)

        if (index == 0 || index == 1 || index == 2) {
            refreshStatus()
        }
    }

    // ---- Panel log (berwarna + jeda/salin/bersih) ----

    private fun colorFor(kind: AgentLog.Kind): Int = ContextCompat.getColor(this, when (kind) {
        AgentLog.Kind.OK -> R.color.log_ok
        AgentLog.Kind.ERR -> R.color.log_err
        AgentLog.Kind.INFO -> R.color.log_info
    })

    private fun renderEntry(sb: SpannableStringBuilder, e: AgentLog.Entry) {
        val start = sb.length
        sb.append(e.toString()).append("\n")
        sb.setSpan(
            ForegroundColorSpan(colorFor(e.kind)),
            start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun rerenderLog() {
        logSb.clear()
        AgentLog.snapshot().forEach { renderEntry(logSb, it) }
        logTv.text = logSb
        updateLogCount()
        scrollLogToBottom()
    }

    private fun appendLogEntry(e: AgentLog.Entry) {
        if (logPaused) {
            pausedDirty = true
            updateLogCount()
            return
        }
        renderEntry(logSb, e)
        logTv.text = logSb
        updateLogCount()
        scrollLogToBottom()
    }

    private fun toggleLogPause() {
        logPaused = !logPaused
        if (!logPaused && pausedDirty) {
            pausedDirty = false
            rerenderLog()
        }
        logPauseBtn.text = getString(if (logPaused) R.string.log_resume else R.string.log_pause)
        updateLogCount()
    }

    private fun clearLog() {
        AgentLog.clear()
        logPaused = false
        pausedDirty = false
        logPauseBtn.text = getString(R.string.log_pause)
        logSb.clear()
        logTv.text = logSb
        updateLogCount()
    }

    private fun copyLog() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(
            ClipData.newPlainText("gososmed-agent-log", AgentLog.snapshot().joinToString("\n"))
        )
        toast("Log disalin ke clipboard")
    }

    private fun updateLogCount() {
        val suffix = when {
            logPaused && pausedDirty -> " · DIJEDA (ada entri baru)"
            logPaused -> " · DIJEDA"
            else -> ""
        }
        logCountTv.text = getString(R.string.log_lines_format, AgentLog.size()) + suffix
    }

    private fun scrollLogToBottom() {
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---- Info perangkat & status izin ----

    private fun renderDeviceInfo() {
        val dm = resources.displayMetrics
        deviceInfoTv.text = buildString {
            append("Model: ${Build.MANUFACTURER} ${Build.MODEL}".trim())
            append("\nAndroid: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            append("\nLayar: ${dm.widthPixels}×${dm.heightPixels} @ ${dm.density}x")
            append("\nID: $deviceId")
        }
    }

    private fun refreshPerms() {
        // v0.9.6 — PERBAIKAN REGRESI LANGKAH 1.
        //
        // v0.9.5 memakai `instance?.isServiceReady()`, yaitu
        // `rootInActiveWindow != null`. Nilai itu SEMENTARA null saat berpindah
        // activity, layar terkunci, atau app target FLAG_SECURE — sehingga
        // pengguna yang sudah mengaktifkan dengan benar tetap melihat
        // "BELUM AKTIF" dan dipaksa mengaktifkan ulang setiap pindah tab.
        //
        // Sumber kebenaran yang benar untuk IZIN adalah
        // `AgentAccessibilityService.isEnabled()` (flag `bound`), diperkuat
        // dengan pembacaan `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
        // supaya status tetap benar walau OEM me-restart layanan di proses
        // terpisah. Kesiapan live (rootInActiveWindow) TIDAK lagi dipakai di
        // sini; ia tetap dipakai internal oleh perintah dump/tap.
        AgentAccessibilityService.reconcileFromSettings(this)
        val a11y = AgentAccessibilityService.isEnabled()
        permA11yTv.text = "${getString(R.string.step_1_title)} — ${if (a11y) "AKTIF ✓" else "BELUM AKTIF"}"
        openA11yBtn.isEnabled = !a11y
        openA11yBtn.text = getString(if (a11y) R.string.btn_already_active else R.string.btn_activate)

        // Baterai: cek nyata ke sistem (bukan tebakan).
        val pm = getSystemService(PowerManager::class.java)
        val batteryFree = pm?.isIgnoringBatteryOptimizations(packageName) == true
        permBatteryTv.text = "${getString(R.string.battery_title)} — ${if (batteryFree) "AKTIF ✓" else "BELUM"}"
        batteryBtn.isEnabled = !batteryFree
        batteryBtn.text = getString(if (batteryFree) R.string.btn_already_exempt else R.string.btn_exempt)

        // Notifikasi: wajib hanya di Android 13+.
        val notifGranted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        permNotifTv.text = "${getString(R.string.notif_title)} — ${if (notifGranted) "AKTIF ✓" else "BELUM"}"
        notifBtn.isEnabled = !notifGranted
        notifBtn.text = getString(if (notifGranted) R.string.btn_already_active else R.string.btn_allow)

        // v0.9.0 — Langkah 2 (WAJIB): izin overlay / Background Activity Launch.
        val overlayOk = AgentOverlay.canDraw(this)
        permOverlayTv.text = "${getString(R.string.step_2_title)} — ${if (overlayOk) "AKTIF ✓" else "BELUM AKTIF"}"
        openOverlayBtn.isEnabled = !overlayOk
        openOverlayBtn.text = getString(if (overlayOk) R.string.btn_already_active else R.string.btn_activate)
        refreshAdbStatus()
    }

    /**
     * v0.9.0 — render status Langkah 3 (otomasi lanjutan / ADB lokal).
     *
     * Kejujuran status adalah inti kartu ini: "belum pernah dihubungkan"
     * (tindakan: hubungkan) berbeda dari "terputus setelah HP restart"
     * (tindakan: hubungkan ULANG). Keduanya ditampilkan apa adanya, bukan
     * disamarkan menjadi satu pesan "tidak aktif".
     */
    private fun refreshAdbStatus() {
        val st = AdbPairingController.status()
        val step3Title = getString(R.string.step_3_title)
        val label = when {
            st.connected -> "$step3Title — TERSAMBUNG ✓"
            st.paired -> "$step3Title — TERPUTUS"
            else -> "$step3Title — BELUM DIHUBUNGKAN"
        }
        adbStatusTv.text = label
        adbPairBtn.text = getString(if (st.connected) R.string.btn_reconnect else R.string.btn_connect)
        // Tombol "Putuskan" hanya berguna bila sudah pernah dipasangkan,
        // karena itulah yang menghapus identitas tersimpan.
        adbForgetBtn.visibility = if (st.paired) View.VISIBLE else View.GONE

        // Alasan spesifik ditampilkan supaya pemilik HP tahu langkah berikutnya.
        if (!st.connected && st.error.isNotEmpty()) {
            adbStatusTv.append("\n${adbReasonText(st.error)}")
        }
    }

    /**
     * Terjemahkan kode `adb_*` (kontrak §4.1) menjadi kalimat yang bisa
     * ditindak. Kode tak dikenal ditampilkan APA ADANYA, bukan disembunyikan —
     * menyembunyikan kode membuat penelusuran mustahil.
     */
    private fun adbReasonText(code: String): String {
        val bare = code.substringBefore(':').trim()
        // v0.9.8 — KEGAGALAN TEKNIS BUKAN "KODE SALAH". Tanpa cabang ini, kartu
        // status menyuruh pengguna membuat kode baru untuk kegagalan yang tidak
        // ada hubungannya dengan kode — persis kebingungan yang terjadi pada
        // v0.9.7 (NoSuchMethodException Conscrypt disajikan sebagai "buat kode
        // baru"). Pesannya sengaja menyebutkan bahwa kode pengguna benar.
        if (AdbPairingController.isTechnicalFailure(code)) {
            return "Pairing gagal karena masalah TEKNIS di APK ini — kode pairing Anda " +
                "TIDAK salah, jadi membuat kode baru tidak akan menolong.\n$code"
        }
        return when (bare) {
            "adb_not_paired" ->
                "Belum dihubungkan. Ketuk Hubungkan, lalu masukkan kode 6 angka dari Pengaturan > Opsi Pengembang > Debug nirkabel > Pairing baru."
            "adb_pair_failed" ->
                "Pairing gagal — kode salah atau sudah kedaluwarsa (berlaku 10 menit). Buat kode baru lalu coba lagi."
            "adb_auth_failed" ->
                "Kunci agent ditolak perangkat. Ketuk Putuskan, lalu Hubungkan lagi dengan kode baru."
            "adb_disconnected" ->
                "Sesi terputus. Biasanya karena Debug nirkabel mati (HP baru di-restart). Nyalakan lagi lalu Hubungkan."
            "adb_port_unknown" ->
                "Port Debug nirkabel tidak ditemukan otomatis. Isi alamat IP dan port secara manual di dialog Hubungkan."
            "adb_disabled" ->
                "Debug nirkabel sedang mati di HP. Nyalakan di Pengaturan > Opsi Pengembang."
            else -> code
        }
    }

    /**
     * v0.9.6 — ALUR PAIRING ala SHIZUKU (Notifikasi RemoteInput = jalur utama).
     *
     * CACAT v0.9.5 yang diperbaiki di sini:
     *
     *  1. `AdbPairingService.start(this)` lalu `startActivity(Settings)` pada
     *     BARIS BERIKUTNYA — keduanya dalam satu frame. Service memerlukan
     *     waktu untuk `startForeground()` dan memasang notifikasi; sementara
     *     Setelan sudah merebut fokus lebih dulu. Di sini kami memberi jeda
     *     pendek yang terukur supaya notifikasi benar-benar terpasang.
     *
     *  2. TIDAK ADA penjelasan apa pun kepada pengguna tentang APA yang harus
     *     dilakukan di layar Setelan, dan tentang KENAPA kode bisa berganti.
     *     Kami tampilkan dialog singkat 3 langkah — inilah bagian yang membuat
     *     alur ini benar-benar bisa dipakai orang biasa.
     */
    private fun showAdbPairDialog() {
        // v0.9.6 — PRASYARAT KERAS jalur utama (notifikasi).
        // Bila POST_NOTIFICATIONS belum diberikan pada Android 13+, notifikasi
        // pairing TIDAK AKAN TERLIHAT — dan karena itu jalur utamanya hilang.
        // Kami meminta izin lebih dulu, lalu membuka panduan setelahnya.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Izin Notifikasi diperlukan")
                .setMessage(
                    "Pairing memakai baris notifikasi untuk mengetik kode 6 angka tanpa " +
                        "menutup layar kode di Setelan. Tanpa izin Notifikasi, cara ini " +
                        "tidak bisa dipakai.\n\nKetuk \"Izinkan\" pada permintaan berikutnya."
                )
                .setPositiveButton("Lanjut") { _, _ ->
                    pendingPairAfterNotif = true
                    requestNotifPermission()
                }
                .setNegativeButton("Batal", null)
                .show()
            return
        }
        openAdbPairGuide()
    }

    private fun openAdbPairGuide() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Hubungkan Otomasi Lanjutan (ADB)")
            .setMessage(
                buildString {
                    append("Ikuti 3 langkah ini — jangan tutup layar kode di tengah jalan:\n\n")
                    append("1.  Di Setelan yang terbuka, masuk ke \"Debug nirkabel\".\n\n")
                    append("2.  Ketuk \"Pasangkan perangkat dengan kode pairing\".\n")
                    append("     Layar kode 6 angka muncul — BIARKAN TERBUKA.\n\n")
                    append("3.  Begitu dialog kode terbuka, agent mencoba membaca\n")
                    append("     port+kode otomatis lewat Aksesibilitas dan langsung\n")
                    append("     memasangkan. Jika pembacaan otomatis tidak tersedia\n")
                    append("     di OEM ini, tarik panel notifikasi lalu ketik 6 angka\n")
                    append("     di baris \"Ketik Kode Pairing\".\n\n")
                    append("     Cara itu TIDAK menutup layar kode di Setelan — jadi\n")
                    append("     kodenya tidak berganti. Justru JANGAN menutup layar itu.\n\n")
                    append("Port juga terdeteksi otomatis lewat mDNS; Anda tidak perlu\n")
                    append("mengetik IP atau port apa pun.")
                }
            )
            .setPositiveButton("Mengerti, buka Setelan") { _, _ ->
                // BARU setelah pengguna siap: nyalakan service...
                AdbPairingService.start(this)
                // ...beri waktu notifikasi terpasang, lalu navigasi.
                // 400 ms cukup karena onCreate/onStartCommand service
                // berjalan di main looper yang sama dan sudah dijadwalkan lebih
                // dulu daripada runnable ini.
                adbStatusTv.postDelayed({ openDeveloperSettingsForPairing() }, 400L)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun openDeveloperSettingsForPairing() {
        // v0.9.6 — Prioritaskan layar Debug nirkabel bila OEM menyediakannya
        // (jalur paling sedikit ketukan), lalu Opsi Pengembang, terakhir
        // Setelan umum.
        val candidates = listOf(
            "android.settings.WIRELESS_DEBUGGING_SETTINGS",
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
            Settings.ACTION_SETTINGS
        )
        for (action in candidates) {
            try {
                startActivity(Intent(action))
                return
            } catch (_: Exception) {
                // lanjut ke kandidat berikutnya
            }
        }
        toast("Buka Setelan > Opsi Pengembang > Debug nirkabel")
    }

    /**
     * Jalankan pairing di thread IO dengan umpan balik di UI.
     *
     * PESAN DIBUAT AKURAT: `pair()` mengembalikan hasil PAIRING, lalu
     * penyambungan berjalan di belakang (lihat AdbPairingController.pair —
     * alasan: batas 30 dtk command server). Jadi pesan sukses menyebut
     * pairing, dan status koneksi dibaca dari kartu status beberapa saat
     * kemudian — bukan diklaim "terhubung" padahal sesinya belum terbentuk.
     */
    private fun runAdbPair(host: String, port: Int, code: String) {
        AgentLog.event("otomasi lanjutan: pairing…")
        adbPairBtn.isEnabled = false
        adbStatusTv.text = "Otomasi Lanjutan (ADB) — PAIRING…"
        Thread {
            val (ok, reason) = AdbPairingController.pair(host, port, code)
            runOnUiThread {
                adbPairBtn.isEnabled = true
                if (ok) {
                    toast("Pairing berhasil — menyambung…")
                    AgentLog.event("otomasi lanjutan: pairing berhasil ✓ (menyambung)")
                } else {
                    toast(adbReasonText(reason))
                    AgentLog.event("otomasi lanjutan gagal: ${reason.ifEmpty { "tanpa alasan" }}")
                }
                refreshPerms()
                // Penyambungan berjalan di belakang; segarkan sekali lagi
                // supaya kartu status menampilkan keadaan sebenarnya.
                adbStatusTv.postDelayed({ refreshAdbStatus() }, 4_000)
            }
        }.start()
    }

    /** Konfirmasi sebelum menghapus identitas otomasi secara permanen. */
    private fun confirmAdbForget() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Putuskan otomasi lanjutan?")
            .setMessage(
                "Kunci otomasi di HP ini akan dihapus. Setelah itu Anda harus " +
                    "melakukan pairing ulang dengan kode baru dari Debug nirkabel.\n\n" +
                    "Otomasi dasar (Aksesibilitas) TIDAK terpengaruh dan tetap berjalan."
            )
            .setPositiveButton("Putuskan") { _, _ ->
                AdbPairingController.forget()
                AgentLog.event("otomasi lanjutan: diputuskan, kunci dihapus")
                refreshPerms()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---- Auto-pairing (deep link dari dasbor) ----

    /**
     * Menangani `gososmed://pair?ws=<url>&code=<KODE8>`. Kode langsung
     * disimpan dan koneksi dijalin tanpa input manual. Parameter `ws`
     * opsional — kosong berarti default produksi (BuildConfig.DEFAULT_WS_URL).
     */
    private fun handlePairIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "gososmed" || data.host != "pair") return
        val code = data.getQueryParameter("code")?.trim()?.uppercase().orEmpty()
        if (code.isEmpty()) {
            toast("Tautan pairing tidak lengkap — minta kode baru dari dasbor")
            return
        }
        val ws = data.getQueryParameter("ws")?.trim().orEmpty()
            .ifEmpty { BuildConfig.DEFAULT_WS_URL }
        AgentLog.event("tautan pairing diterima — menghubungkan otomatis…")
        doConnect(ws, code)
        // Konsumsi data agar rotasi/resume tidak memicu ulang.
        intent.data = null
    }

    private fun requestBatteryExemption() {
        try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            startActivity(i)
        } catch (e: Exception) {
            // Beberapa ROM menolak intent langsung — buka halaman setelan saja.
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                toast("Buka Setelan → Baterai → bebaskan Agent GoSosmed")
            }
        }
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    // ---- Izin "tampil di atas app lain" (v0.7.1, PENENTU harvest) ----

    /**
     * Tanpa izin ini agent TIDAK BISA membuka app target dari server: Android
     * memblokir Background Activity Launch dan permintaan launch ditelan tanpa
     * error (insiden produksi 2026-09-11: kelima platform gagal, layar tetap
     * di launcher). Izin overlay adalah pengecualian BAL resmi — pola yang
     * dipakai mobilerun-portal. Karena itu izin ini diminta SEKALI per sesi
     * secara proaktif, bukan disembunyikan di tab Setup.
     */
    private fun maybeAskOverlay() {
        if (AgentOverlay.canDraw(this) || overlayAsked) return
        overlayAsked = true
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Satu izin lagi agar otomasi bisa jalan")
            .setMessage(
                "Agent GoSosmed butuh izin \"Tampilkan di atas aplikasi lain\".\n\n" +
                    "Tanpa izin ini Android memblokir agent saat membuka " +
                    "Instagram/TikTok/Facebook/Threads/YouTube, sehingga cek sesi " +
                    "selalu gagal." +
                    if (isXiaomiFamily()) {
                        "\n\nKhusus MIUI/HyperOS: di halaman izin aplikasi, nyalakan juga " +
                            "\"Tampilkan jendela pop-up saat berjalan di latar belakang\" " +
                            "dan \"Mulai otomatis\" (Autostart)."
                    } else {
                        ""
                    }
            )
            .setPositiveButton("Buka Setelan") { _, _ -> openOverlaySettings() }
            .setNegativeButton("Nanti", null)
            .show()
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            // Sebagian ROM menolak intent per-package — buka daftar umumnya.
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (e2: Exception) {
                toast("Buka Setelan → Aplikasi → Agent GoSosmed → Tampilkan di atas aplikasi lain")
            }
        }
    }

    private fun isXiaomiFamily(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPerms()
        // v0.9.6 — lanjutkan alur pairing yang sempat tertahan oleh permintaan
        // izin Notifikasi (lihat showAdbPairDialog).
        if (requestCode == 1001 && pendingPairAfterNotif) {
            pendingPairAfterNotif = false
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                openAdbPairGuide()
            } else {
                toast("Tanpa izin Notifikasi, pairing lewat notifikasi tidak bisa dipakai.")
            }
        }
    }

    private fun refreshStatus() {
        // v0.9.6 — status banner memakai definisi izin yang benar (lihat
        // refreshPerms); bukan kesiapan live yang fluktuatif.
        AgentAccessibilityService.reconcileFromSettings(this)
        val a11yReady = AgentAccessibilityService.isEnabled()
        val paired = loadPairingCode().isNotEmpty()
        val svc = AgentForegroundService.instance
        val wsConnected = svc?.getWsClient()?.isConnected() == true
        val effectiveStatus = lastStatus.ifEmpty { svc?.getLastWsStatus().orEmpty() }
        when {
            wsConnected || effectiveStatus.contains("paired") || effectiveStatus == "connected" -> {
                statusBigTv.text = getString(R.string.status_connected)
                statusBigTv.setTextColor(ContextCompat.getColor(this, R.color.status_ok))
            }
            effectiveStatus.contains("connecting") || effectiveStatus.contains("menghubungkan") -> {
                statusBigTv.text = getString(R.string.status_connecting)
                statusBigTv.setTextColor(ContextCompat.getColor(this, R.color.status_warn))
            }
            effectiveStatus.contains("ditolak") || effectiveStatus.contains("stopped") ||
                effectiveStatus.contains("disconnected") || effectiveStatus.contains("closed") -> {
                statusBigTv.text = getString(R.string.status_disconnected)
                statusBigTv.setTextColor(ContextCompat.getColor(this, R.color.status_err))
            }
            else -> {
                statusBigTv.text = getString(R.string.status_not_connected)
                statusBigTv.setTextColor(ContextCompat.getColor(this, R.color.status_idle))
            }
        }
        statusTv.text = buildString {
            append(getString(if (a11yReady) R.string.status_a11y_ok else R.string.status_a11y_off))
            if (paired) append("\n").append(getString(R.string.status_code_saved))
        }
        refreshPerms()
        refreshTelemetry()
    }

    private fun refreshTelemetry() {
        val snap = DeviceTelemetryHelper.getSnapshot(this)

        // Baterai & status charging
        val chargingStr = getString(if (snap.isCharging) R.string.telemetry_battery_charging else R.string.telemetry_battery_discharging)
        val batteryVal = if (snap.batteryPct >= 0) "${snap.batteryPct}%$chargingStr" else "N/A"
        tvTelemetryBattery.text = getString(R.string.telemetry_battery_format, batteryVal)
        tvTelemetryBatteryAlert.visibility = if (!snap.isCharging && snap.batteryPct in 0..20) View.VISIBLE else View.GONE

        // Suhu perangkat
        val tempColor = if (snap.temperatureCelsius >= 40.0f) {
            ContextCompat.getColor(this, R.color.status_err)
        } else {
            ContextCompat.getColor(this, R.color.text_primary)
        }
        val tempState = getString(if (snap.temperatureCelsius >= 40.0f) R.string.telemetry_temp_hot else R.string.telemetry_temp_normal)
        val tempStr = String.format(Locale.US, "%.1f", snap.temperatureCelsius)
        tvTelemetryTemp.text = getString(R.string.telemetry_temp_format, tempStr, tempState)
        tvTelemetryTemp.setTextColor(tempColor)

        // Jaringan & latensi
        val latencyStr = if (snap.pingLatencyMs >= 0) "${snap.pingLatencyMs}ms" else getString(R.string.telemetry_network_waiting)
        tvTelemetryNetwork.text = getString(R.string.telemetry_network_format, snap.wifiSsid, latencyStr)

        // Status platform aplikasi terpasang (terstruktur 2 baris rapi)
        fun statusIcon(installed: Boolean): String = if (installed) "✓" else "—"
        tvTelemetryApps.text = buildString {
            append(getString(R.string.telemetry_target_prefix))
            append("• TikTok [${statusIcon(snap.isTiktokInstalled)}]   ")
            append("• IG [${statusIcon(snap.isInstagramInstalled)}]   ")
            append("• FB [${statusIcon(snap.isFacebookInstalled)}]\n")
            append("• Threads [${statusIcon(snap.isThreadsInstalled)}]   ")
            append("• YouTube [${statusIcon(snap.isYoutubeInstalled)}]")
        }

        // Sinkronisasi data ke Server Mode Overlay jika sedang aktif
        if (serverModeOverlay.visibility == View.VISIBLE) {
            serverClockTv.text = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            serverInfoTv.text = getString(R.string.server_overlay_info_format, snap.batteryPct, tempStr, latencyStr)
        }
    }

    private fun performEchoTest() {
        btnEchoTest.isEnabled = false
        btnEchoTest.text = getString(R.string.btn_echo_testing)
        val client = AgentForegroundService.instance?.getWsClient()
        if (client == null || !client.isConnected()) {
            btnEchoTest.isEnabled = true
            btnEchoTest.text = getString(R.string.btn_echo_test)
            toast("Perangkat belum terhubung ke server GoSosmed")
            return
        }
        client.sendEchoPing { rttMs ->
            runOnUiThread {
                btnEchoTest.isEnabled = true
                btnEchoTest.text = getString(R.string.btn_echo_test)
                vibrateFeedback()
                if (rttMs >= 0) {
                    toast("✓ Koneksi Server Berhasil! Latensi: ${rttMs}ms")
                } else {
                    toast("Gagal mendapatkan respons echo dari server")
                }
                refreshTelemetry()
            }
        }
    }

    private fun vibrateFeedback() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(45, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(45)
            }
        } catch (_: Throwable) {}
    }

    private fun startQrScan() {
        val isEn = (prefs().getString("app_lang", "id") ?: "id").startsWith("en")
        val options = if (isEn) {
            arrayOf(
                "⚡ In-App Auto QR Scanner (Recommended)",
                "🔍 Open Google Lens Camera",
                "📱 Open Native QR Scanner / AI Camera"
            )
        } else {
            arrayOf(
                "⚡ Scan QR Otomatis di Aplikasi (Disarankan)",
                "🔍 Buka Kamera Google Lens",
                "📱 Buka Pemindai QR Bawaan HP / Kamera AI"
            )
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (isEn) "Select QR Code Scanner" else "Pilih Metode Scan QR Code")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startInAppGmsQrScan()
                    1 -> openGoogleLensCamera()
                    2 -> openNativeQrScannerOrFullCamera()
                }
            }
            .setNegativeButton(if (isEn) "Cancel" else "Batal", null)
            .show()
    }

    private fun startInAppGmsQrScan() {
        try {
            val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
            val scanner = com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this, options)
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val raw = barcode.rawValue?.trim().orEmpty()
                    if (raw.isNotEmpty()) {
                        handleScannedPairCode(raw)
                    }
                }
                .addOnFailureListener {
                    // Jika modul Play Services belum siap, otomatis alihkan ke Google Lens / Pemindai Bawaan
                    openGoogleLensCamera()
                }
        } catch (_: Throwable) {
            openGoogleLensCamera()
        }
    }

    private fun openGoogleLensCamera() {
        val candidates = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("googleapp://lens")).apply {
                setPackage("com.google.android.googlequicksearchbox")
            },
            Intent(Intent.ACTION_VIEW, Uri.parse("googleapp://lens")),
            packageManager.getLaunchIntentForPackage("com.google.ar.lens")
        ).filterNotNull()

        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                toast("Arahkan Google Lens ke QR Code di dasbor GoSosmed")
                return
            } catch (_: Throwable) {}
        }
        openNativeQrScannerOrFullCamera()
    }

    private fun openNativeQrScannerOrFullCamera() {
        // 1) Prioritaskan aplikasi pemindai QR resmi bawaan OEM (mis. Xiaomi Scanner)
        val scannerPackages = listOf(
            "com.xiaomi.scanner",
            "com.miui.qr",
            "com.coloros.ocrscanner",
            "com.oplus.scanner",
            "com.sec.android.app.qragent"
        )
        for (pkg in scannerPackages) {
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    toast("Arahkan pemindai QR ke dasbor GoSosmed")
                    return
                }
            } catch (_: Throwable) {}
        }

        // 2) Gunakan INTENT_ACTION_STILL_IMAGE_CAMERA (mode kamera penuh dengan AI/QR aktif),
        //    BUKAN ACTION_IMAGE_CAPTURE (yang mematikan fitur deteksi QR & Google Lens!).
        try {
            val fullCam = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(fullCam)
            toast("Arahkan kamera ke QR Code di dasbor GoSosmed")
        } catch (_: Throwable) {
            toast("Buka Google Lens atau Pemindai QR bawaan HP Anda")
        }
    }

    private fun handleScannedPairCode(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.startsWith("gososmed://pair")) {
            handlePairIntent(Intent(Intent.ACTION_VIEW, Uri.parse(trimmed)))
        } else {
            val code = if (trimmed.length > 8) trimmed.take(8) else trimmed.uppercase()
            pairCodeEt.setText(code)
            toast("Kode pairing terisi: $code")
            connectWs()
        }
    }

    private fun setupServerMode() {
        val isServerMode = prefs().getBoolean("server_mode", false)
        switchServerMode.isChecked = isServerMode
        applyServerMode(isServerMode)

        switchServerMode.setOnCheckedChangeListener { _, isChecked ->
            applyServerMode(isChecked)
        }

        var lastTapTime = 0L
        serverModeOverlay.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapTime < 400L) {
                applyServerMode(false)
                switchServerMode.isChecked = false
            } else {
                lastTapTime = now
            }
        }
    }

    private fun applyServerMode(enabled: Boolean) {
        prefs().edit().putBoolean("server_mode", enabled).apply()
        val lp = window.attributes
        if (enabled) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            lp.screenBrightness = 0.01f
            window.attributes = lp
            serverModeOverlay.visibility = View.VISIBLE
            refreshTelemetry()
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
            serverModeOverlay.visibility = View.GONE
        }
    }

    private fun setupOemCard() {
        val oemName = OemOptimizationHelper.getOemName()
        tvOemTitle.text = getString(R.string.oem_title_format, oemName)
        btnOemSettings.text = getString(R.string.btn_oem_format, oemName)
        btnOemSettings.setOnClickListener {
            OemOptimizationHelper.openOemBackgroundSettings(this)
        }
    }

    private fun connectWs() {
        // Prioritas URL:
        // 1) Override manual di debugSection (jika sedang dibuka)
        // 2) URL tersimpan sebelumnya (mis. dari deep link dev/prod terakhir)
        // 3) Default sesuai kanal build (DEV_WS_URL di dev/debug, PROD_WS_URL di release stabil)
        val overrideUrl = if (debugSection.visibility == View.VISIBLE)
            wsUrlEt.text.toString().trim() else ""
        val url = overrideUrl
            .ifEmpty { loadWsUrl() }
            .ifEmpty { BuildConfig.DEFAULT_WS_URL }
        val code = pairCodeEt.text.toString().trim().uppercase()
        if (code.isEmpty()) {
            toast("Ketik kode 8 karakter dari dasbor, atau gunakan tombol Hubungkan HP ini di dasbor")
            return
        }
        doConnect(url, code)
    }

    private fun doConnect(url: String, code: String) {
        val i = Intent(this, AgentForegroundService::class.java).apply {
            action = AgentForegroundService.ACTION_SET_PAIRING
            putExtra(AgentForegroundService.EXTRA_WS_URL, url)
            putExtra(AgentForegroundService.EXTRA_PAIRING_CODE, code)
        }
        startForegroundService(i)
        pairTv.text = "ID perangkat: $deviceId"
        lastStatus = "connecting…"
        refreshStatus()
    }

    private fun disconnectWs() {
        val i = Intent(this, AgentForegroundService::class.java).apply {
            action = AgentForegroundService.ACTION_STOP_WS
        }
        startService(i)
        prefs().edit().remove("pairing_code").apply()
        lastStatus = "stopped"
        AgentLog.event("sambungan diputus oleh pengguna")
        refreshStatus()
    }

    // ---- Lifecycle: terima broadcast status dari service ----

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairIntent(intent)
        handleValidationIntent(intent)
    }

    /** Watchdog berkala untuk memastikan UI selalu menampilkan status nyata tanpa delay. */
    private val liveStatusTicker = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                refreshStatus()
                window.decorView.postDelayed(this, 1500L)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshUpdateState()
        window.decorView.removeCallbacks(liveStatusTicker)
        window.decorView.postDelayed(liveStatusTicker, 1500L)
        // v0.7.1: izin overlay = pengecualian Background Activity Launch.
        // Tanpa ini agent tidak pernah bisa membuka app target dari server.
        maybeAskOverlay()
        if (AgentOverlay.canDraw(this)) AgentOverlay.ensure(this)
        val filter = IntentFilter(AgentForegroundService.ACTION_STATUS)
        try {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) {
            Log.w("GoAgent", "registerReceiver gagal: ${e.message}")
        }
        try {
            val am = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            am?.addAccessibilityStateChangeListener(a11yStateChangeListener)
        } catch (e: Exception) {
            Log.w("GoAgent", "addAccessibilityStateChangeListener gagal: ${e.message}")
        }
        // Sinkronisasi asinkron pasca kembali dari Setelan OS (binder service / AppOps delay)
        val delays = longArrayOf(300L, 800L, 1500L)
        for (d in delays) {
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    refreshStatus()
                    if (AgentOverlay.canDraw(this)) AgentOverlay.ensure(this)
                }
            }, d)
        }
        handleValidationIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        window.decorView.removeCallbacks(liveStatusTicker)
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
            // belum terdaftar — abaikan
        }
        try {
            val am = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            am?.removeAccessibilityStateChangeListener(a11yStateChangeListener)
        } catch (_: Exception) {
            // abaikan
        }
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(liveStatusTicker)
        AgentAccessibilityService.removeStateListener(a11yUiListener)
        AdbPairingController.removeStateListener(adbUiListener)
        AgentLog.listener = null
        super.onDestroy()
    }

    // ---- Kanal validasi adb (dipakai QA; tidak terlihat di UI produksi) ----

    private fun handleValidationIntent(intent: Intent?) {
        val cmd = intent?.getStringExtra("cmd") ?: return
        var attempts = 0
        val runner = object : Runnable {
            override fun run() {
                attempts++
                // v0.9.6 — Di sini kesiapan LIVE memang relevan (kita akan
                // benar-benar dump/tap window). Tetap tahan terhadap instance
                // null sesaat akibat restart layanan OEM: anggap siap bila
                // layanan ter-bind, lalu serahkan hasil apa adanya.
                val svc = AgentAccessibilityService.instance
                val ready = svc != null && (svc.isServiceReady() || AgentAccessibilityService.isEnabled())
                if (!ready && attempts < 6) {
                    logTv.postDelayed(this, 500L)
                    return
                }
                when (cmd) {
                    AgentCommand.CMD_DUMP -> { val r = runDump(); writeResult("dump", r) }
                    AgentCommand.CMD_PACKAGE -> { val r = runPackage(); writeResult("package", r) }
                    AgentCommand.CMD_BACK -> { val r = runGlobal("back") { it.pressBack() }; writeResult("back", r) }
                    AgentCommand.CMD_HOME -> { val r = runGlobal("home") { it.pressHome() }; writeResult("home", r) }
                    AgentCommand.CMD_TAP_BY_TEXT -> {
                        val text = intent.getStringExtra("text") ?: ""
                        val r = runTapByText(text)
                        writeResult("tapByText", r)
                    }
                    AgentCommand.CMD_SET_TEXT -> {
                        val text = intent.getStringExtra("text") ?: ""
                        val r = runSetText(text)
                        writeResult("setText", r)
                    }
                    AgentCommand.CMD_START_APP -> {
                        val pkg = intent.getStringExtra("package") ?: ""
                        val activity = intent.getStringExtra("activity") ?: ""
                        val r = runStartApp(pkg, activity)
                        writeResult("startApp", r)
                    }
                    AgentCommand.CMD_KILL_APP -> {
                        val pkg = intent.getStringExtra("package") ?: ""
                        val r = runKillApp(pkg)
                        writeResult("killApp", r)
                    }
                    AgentCommand.CMD_HAS_PACKAGE -> {
                        val pkg = intent.getStringExtra("package") ?: ""
                        val r = runHasPackage(pkg)
                        writeResult("hasPackage", r)
                    }
                    AgentCommand.CMD_LIST_PACKAGES -> {
                        val r = runListPackages()
                        writeResult("listPackages", r)
                    }
                    else -> writeResult(cmd, "ERR_UNKNOWN_CMD")
                }
            }
        }
        logTv.post(runner)
        intent.removeExtra("cmd")
    }

    // ---- Helper perintah lokal (mode debug) ----

    private fun runDump(): String {
        return AgentAccessibilityService.withInstance { svc ->
            val xml = svc.dumpXml()
            val pkg = svc.currentPackage()
            writeRawXml("agent_dump_raw.xml", xml)
            "PACKAGE=$pkg DUMP_CHARS=${xml.length}"
        } ?: "ERR_SERVICE_NOT_READY"
    }

    private fun runPackage(): String {
        return AgentAccessibilityService.withInstance { svc ->
            "PACKAGE=${svc.currentPackage()}"
        } ?: "ERR_SERVICE_NOT_READY"
    }

    private fun doGlobal(action: (AgentAccessibilityService) -> Boolean) {
        AgentLog.event(runGlobal("global", action))
        refreshStatus()
    }

    private fun runGlobal(label: String, action: (AgentAccessibilityService) -> Boolean): String {
        return AgentAccessibilityService.withInstance { svc ->
            val ok = action(svc)
            "$label=$ok"
        } ?: "ERR_SERVICE_NOT_READY"
    }

    private fun doTapFirstClickable() {
        AgentAccessibilityService.withInstance { svc ->
            val b = svc.tapFirstClickable()
            runOnUiThread {
                if (b != null) AgentLog.event("tap first clickable at [${b.left},${b.top}][${b.right},${b.bottom}]")
                else AgentLog.event("no clickable node found")
            }
        } ?: runOnUiThread { toast("Aktifkan aksesibilitas dulu di tab Setup") }
    }

    private fun runTapByText(text: String): String {
        return AgentAccessibilityService.withInstance { svc ->
            val ok = svc.tapByText(text)
            "tapByText=$ok text=$text"
        } ?: "ERR_SERVICE_NOT_READY"
    }

    private fun runSetText(text: String): String {
        return AgentAccessibilityService.withInstance { svc ->
            val ok = svc.setText(text)
            "setText=$ok"
        } ?: "ERR_SERVICE_NOT_READY"
    }

    private fun runStartApp(pkg: String, activity: String = ""): String {
        return AgentAccessibilityService.withInstance { svc ->
            val act = activity.ifEmpty { null }
            val ok = svc.startApp(pkg, act)
            "startApp=$ok pkg=$pkg activity=$activity"
        } ?: "ERR_SERVICE_NOT_READY"
    }

    private fun runListPackages(): String {
        return AgentAccessibilityService.withInstance { svc ->
            val pkgs = svc.listPackages()
            "listPackages=${pkgs.size} ${pkgs.joinToString(",")}"
        } ?: "ERR_SERVICE_NOT_READY"
    }

    private fun runKillApp(pkg: String): String {
        return AgentAccessibilityService.withInstance { svc ->
            val ok = svc.killApp(pkg)
            "killApp=$ok pkg=$pkg"
        } ?: "ERR_SERVICE_NOT_READY"
    }

    private fun runHasPackage(pkg: String): String {
        return AgentAccessibilityService.withInstance { svc ->
            val ok = svc.hasPackage(pkg)
            "hasPackage=$ok pkg=$pkg"
        } ?: "ERR_SERVICE_NOT_READY"
    }

    // Menulis hasil ke internal files dir (run-as readable) agar adb dapat
    // mengambil bukti deterministik tanpa izin storage.
    private fun writeResult(tag: String, value: String) {
        AgentReceiver.ResultStore.write(this, tag, value)
    }

    private fun writeRawXml(name: String, xml: String) {
        try {
            java.io.File(filesDir, name).writeText(xml)
            Log.i("GoAgent", "raw xml ${xml.length} chars -> files/$name")
        } catch (e: Exception) {
            Log.e("GoAgent", "writeRawXml failed: ${e.message}")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // device_id persisten di SharedPreferences (identitas HP untuk agenthub).
    private fun loadOrCreateDeviceId(): String {
        val prefs = getSharedPreferences("agent", MODE_PRIVATE)
        prefs.getString("device_id", null)?.let { return it }
        val id = "agent-" + UUID.randomUUID().toString().substring(0, 8)
        prefs.edit().putString("device_id", id).apply()
        return id
    }
}
