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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout
import com.gososmed.agent.privileged.AdbPairingController
import com.gososmed.agent.privileged.AdbPairingService
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
    private lateinit var panelSetup: View
    private lateinit var panelLog: View
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Foreground service menjaga proses tetap hidup; service memulihkan
        // koneksi tersimpan sendiri (auto-reconnect setelah kill/reboot).
        startForegroundService(Intent(this, AgentForegroundService::class.java))

        statusBigTv = findViewById(R.id.statusBigTv)
        statusTv = findViewById(R.id.statusTv)
        versionTv = findViewById(R.id.versionTv)
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
        panelSetup = findViewById(R.id.panelSetup)
        panelLog = findViewById(R.id.panelLog)
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

        pairTv.text = "ID perangkat: $deviceId"
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
                val req = okhttp3.Request.Builder()
                    .url("https://api.github.com/repos/dedy45/gososmed-mobile-agent/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                okhttp3.OkHttpClient().newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) throw IllegalStateException("GitHub HTTP ${resp.code}")
                    val json = org.json.JSONObject(body)
                    val tag = json.optString("tag_name", "").removePrefix("v")
                    var apk = ""
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            if (a.optString("name", "").endsWith(".apk")) {
                                apk = a.optString("browser_download_url", "")
                                break
                            }
                        }
                    }
                    if (tag.isNotEmpty()) {
                        AgentUpdateState.latestVersion = tag
                        if (apk.isNotEmpty()) AgentUpdateState.apkUrl = apk
                        AgentUpdateState.checkedAt = System.currentTimeMillis()
                    }
                    val msg = if (AgentUpdateState.isNewer(BuildConfig.VERSION_NAME)) {
                        "Update tersedia: v${AgentUpdateState.latestVersion}"
                    } else {
                        "Sudah versi terbaru (v${BuildConfig.VERSION_NAME})"
                    }
                    runOnUiThread {
                        refreshUpdateState()
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        AgentLog.event("cek update: $msg")
                    }
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

    // ---- Tab ----

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_beranda))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_setup))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_log))
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPanel(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        showPanel(0)
    }

    private fun showPanel(index: Int) {
        panelBeranda.visibility = if (index == 0) View.VISIBLE else View.GONE
        panelSetup.visibility = if (index == 1) View.VISIBLE else View.GONE
        panelLog.visibility = if (index == 2) View.VISIBLE else View.GONE
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
        logPauseBtn.text = if (logPaused) "Lanjut" else "Jeda"
        updateLogCount()
    }

    private fun clearLog() {
        AgentLog.clear()
        logPaused = false
        pausedDirty = false
        logPauseBtn.text = "Jeda"
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
        logCountTv.text = "${AgentLog.size()} baris$suffix"
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
        permA11yTv.text = "Akses otomatisasi (Aksesibilitas) — ${if (a11y) "AKTIF ✓" else "BELUM AKTIF"}"
        openA11yBtn.isEnabled = !a11y
        openA11yBtn.text = if (a11y) "Sudah Aktif" else "Aktifkan"

        // Baterai: cek nyata ke sistem (bukan tebakan).
        val pm = getSystemService(PowerManager::class.java)
        val batteryFree = pm?.isIgnoringBatteryOptimizations(packageName) == true
        permBatteryTv.text = "Bebas hemat baterai — ${if (batteryFree) "AKTIF ✓" else "BELUM"}"
        batteryBtn.isEnabled = !batteryFree
        batteryBtn.text = if (batteryFree) "Sudah Bebas" else "Bebaskan"

        // Notifikasi: wajib hanya di Android 13+.
        val notifGranted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        permNotifTv.text = "Notifikasi — ${if (notifGranted) "AKTIF ✓" else "BELUM"}"
        notifBtn.isEnabled = !notifGranted
        notifBtn.text = if (notifGranted) "Sudah Aktif" else "Izinkan"

        // v0.9.0 — Langkah 2 (WAJIB): izin overlay / Background Activity Launch.
        // Sebelum v0.9.0 kartu ini TIDAK ADA di UI, padahal tanpa izin ini
        // Android menelan permintaan buka aplikasi tanpa error apa pun —
        // pemilik HP bisa merasa "sudah mengaktifkan semuanya" tetapi otomasi
        // tetap gagal tanpa petunjuk.
        val overlayOk = AgentOverlay.canDraw(this)
        permOverlayTv.text = "Tampilkan di atas aplikasi lain — ${if (overlayOk) "AKTIF ✓" else "BELUM AKTIF"}"
        openOverlayBtn.isEnabled = !overlayOk
        openOverlayBtn.text = if (overlayOk) "Sudah Aktif" else "Aktifkan"

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
        val label = when {
            st.connected -> "Otomasi Lanjutan (ADB) — TERSAMBUNG ✓"
            st.paired -> "Otomasi Lanjutan (ADB) — TERPUTUS"
            else -> "Otomasi Lanjutan (ADB) — BELUM DIHUBUNGKAN"
        }
        adbStatusTv.text = label
        adbPairBtn.text = if (st.connected) "Hubungkan Ulang" else "Hubungkan"
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
     *     waktu untuk `startForeground()` dan memasang overlay; sementara
     *     Setelan sudah merebut fokus lebih dulu. Akibatnya `addView` ditolak
     *     dan notifikasi pun bisa belum terpasang saat pengguna sudah berada
     *     di layar Setelan. Di sini kami memberi jeda pendek yang terukur
     *     (250 ms) supaya notifikasi + overlay sempat terpasang.
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
                    append("     port+kode otomatis lewat Aksesibilitas. Jika berhasil,\n")
                    append("     kartu akan terisi sendiri dan Anda cukup menekan\n")
                    append("     \"Hubungkan Sekarang\".\n\n")
                    append("     Jika pembacaan otomatis tidak tersedia di OEM ini, ada\n")
                    append("     DUA cara manual — pilih salah satu:\n")
                    append("     •  Kartu melayang GoSosmed di layar, atau\n")
                    append("     •  Tarik panel notifikasi, lalu ketik di baris\n")
                    append("        \"Ketik Kode Pairing\".\n\n")
                    append("     Keduanya TIDAK menutup layar kode di Setelan — jadi\n")
                    append("     kodenya tidak berganti. Justru JANGAN menutup layar itu.\n\n")
                    append("Port juga terdeteksi otomatis lewat mDNS; Anda tidak perlu\n")
                    append("mengetik IP atau port apa pun.")
                }
            )
            .setPositiveButton("Mengerti, buka Setelan") { _, _ ->
                // BARU setelah pengguna siap: nyalakan service...
                AdbPairingService.start(this)
                // ...beri waktu terpasang (kartu melayang + notifikasi), lalu
                // navigasi. 400 ms cukup karena onCreate/onStartCommand service
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
                toast("Buka Setelan → Baterai → bebaskan GoSosmed Agent")
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
                "GoSosmed Agent butuh izin \"Tampilkan di atas aplikasi lain\".\n\n" +
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
                toast("Buka Setelan → Aplikasi → GoSosmed Agent → Tampilkan di atas aplikasi lain")
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
        when {
            lastStatus.contains("paired") -> {
                statusBigTv.text = "● TERSAMBUNG"
                statusBigTv.setTextColor(ContextCompat.getColor(this, R.color.status_ok))
            }
            lastStatus.contains("connecting") -> {
                statusBigTv.text = "● MENGHUBUNGKAN…"
                statusBigTv.setTextColor(ContextCompat.getColor(this, R.color.status_warn))
            }
            lastStatus.contains("ditolak") || lastStatus.contains("stopped") -> {
                statusBigTv.text = "● TERPUTUS"
                statusBigTv.setTextColor(ContextCompat.getColor(this, R.color.status_err))
            }
            else -> {
                statusBigTv.text = "● BELUM TERHUBUNG"
                statusBigTv.setTextColor(ContextCompat.getColor(this, R.color.status_idle))
            }
        }
        statusTv.text = buildString {
            append(if (a11yReady) "✓ Akses otomatisasi aktif" else "✗ Akses otomatisasi belum aktif — buka tab Setup")
            if (paired) append("\n✓ Kode tersimpan — agent akan menyambung otomatis")
        }
        refreshPerms()
    }

    private fun connectWs() {
        // Mode produksi: URL tidak diketik user. Override hanya dari field
        // debug (tersembunyi). Kosong → default produksi.
        val overrideUrl = if (debugSection.visibility == View.VISIBLE)
            wsUrlEt.text.toString().trim() else ""
        val url = overrideUrl.ifEmpty { BuildConfig.DEFAULT_WS_URL }
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

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshUpdateState()
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
        handleValidationIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
            // belum terdaftar — abaikan
        }
    }

    override fun onDestroy() {
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
