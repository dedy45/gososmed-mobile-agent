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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout
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
    private lateinit var permBatteryTv: TextView
    private lateinit var permNotifTv: TextView
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
    private lateinit var debugSection: View

    private val deviceId: String by lazy { loadOrCreateDeviceId() }
    private var versionTapCount = 0
    private var lastStatus = ""
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
        permBatteryTv = findViewById(R.id.permBatteryTv)
        permNotifTv = findViewById(R.id.permNotifTv)
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
        debugSection = findViewById(R.id.debugSection)

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
        batteryBtn.setOnClickListener { requestBatteryExemption() }
        notifBtn.setOnClickListener { requestNotifPermission() }

        // Panel log: render isi yang sudah ada + dengarkan entri baru.
        // Pemilik HP selalu melihat apa yang diminta server (transparansi).
        logPauseBtn.setOnClickListener { toggleLogPause() }
        logClearBtn.setOnClickListener { clearLog() }
        logCopyBtn.setOnClickListener { copyLog() }
        AgentLog.listener = { entry -> runOnUiThread { appendLogEntry(entry) } }
        rerenderLog()

        // Auto-pairing via deep link (bila activity dibuka dari tautan dasbor).
        handlePairIntent(intent)
        refreshStatus()
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
        // Aksesibilitas: sumber kebenaran = service benar-benar terhubung.
        val a11y = AgentAccessibilityService.instance?.isServiceReady() == true
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPerms()
    }

    private fun refreshStatus() {
        val a11yReady = AgentAccessibilityService.instance?.isServiceReady() == true
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
                val ready = AgentAccessibilityService.instance?.isServiceReady() == true
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
