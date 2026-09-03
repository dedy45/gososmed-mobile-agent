package com.gososmed.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID

/**
 * UI produksi agent (v0.4.0): onboarding 3 langkah untuk pengguna akhir.
 *
 * Prinsip: pengguna TIDAK mengetik URL server, TIDAK wajib mengetik kode.
 * Jalur utama = auto-pairing lewat deep link `gososmed://pair?ws=<url>&code=<kode>`
 * yang diterbitkan dasbor (tap dari browser HP / pindai QR kamera bawaan).
 * Jalur cadangan = ketik kode 8 karakter saja (URL memakai default produksi).
 *
 * Mode debug (log + uji lokal + override URL) tersembunyi; buka dengan tap 7×
 * pada teks versi — pola developer-options agar UI produksi tetap bersih.
 *
 * WS tetap dimiliki AgentForegroundService (bukan activity) sehingga koneksi
 * bertahan saat UI ditutup. Activity ini hanya mengirim intent dan merender
 * status broadcast.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusTv: TextView
    private lateinit var versionTv: TextView
    private lateinit var pairTv: TextView
    private lateinit var pairHintTv: TextView
    private lateinit var logTv: TextView
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
    private lateinit var debugSection: LinearLayout

    private val deviceId: String by lazy { loadOrCreateDeviceId() }
    private var versionTapCount = 0
    private var lastStatus = ""

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(AgentForegroundService.EXTRA_STATUS) ?: return
            val rejected = intent.getBooleanExtra(AgentForegroundService.EXTRA_REJECTED, false)
            lastStatus = status
            runOnUiThread {
                log(status)
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

        statusTv = findViewById(R.id.statusTv)
        versionTv = findViewById(R.id.versionTv)
        pairTv = findViewById(R.id.pairTv)
        pairHintTv = findViewById(R.id.pairHintTv)
        logTv = findViewById<TextView>(R.id.logTv).apply { movementMethod = ScrollingMovementMethod() }
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
        debugSection = findViewById(R.id.debugSection)

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

        connectBtn.setOnClickListener { connectWs() }
        disconnectBtn.setOnClickListener { disconnectWs() }
        dumpBtn.setOnClickListener { log(runDump()) }
        packageBtn.setOnClickListener { log(runPackage()) }
        backBtn.setOnClickListener { doGlobal { it.pressBack() } }
        homeBtn.setOnClickListener { doGlobal { it.pressHome() } }
        tapBtn.setOnClickListener { doTapFirstClickable() }

        findViewById<Button>(R.id.openAccessibilityBtn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Langkah 2: bebaskan dari hemat baterai — penyebab kegagalan #1 di
        // Xiaomi/Oppo/Vivo/Realme (service dibunuh sistem di latar belakang).
        batteryBtn.setOnClickListener { requestBatteryExemption() }

        // Auto-pairing via deep link (bila activity dibuka dari tautan dasbor).
        handlePairIntent(intent)
        refreshStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Bisa berupa deep link pairing ATAU kanal validasi adb (cmd extra).
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
        log("tautan pairing diterima — menghubungkan otomatis…")
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

    private fun refreshStatus() {
        val a11yReady = AgentAccessibilityService.instance?.isServiceReady() == true
        val paired = loadPairingCode().isNotEmpty()
        statusTv.text = buildString {
            append(if (a11yReady) "✓ Akses otomatisasi aktif" else "✗ Akses otomatisasi belum aktif — selesaikan Langkah 1")
            append("\n")
            append(
                when {
                    lastStatus.contains("paired") -> "✓ Tersambung ke server GoSosmed"
                    lastStatus.contains("connecting") -> "Menghubungkan ke server…"
                    paired -> "Kode tersimpan — menunggu sambungan"
                    else -> "Belum terhubung — selesaikan Langkah 3"
                }
            )
        }
    }

    private fun connectWs() {
        // Mode produksi: URL tidak diketik user. Override hanya dari field
        // debug (tersembunyi). Kosong → default produksi.
        val overrideUrl = if (debugSection.visibility == View.VISIBLE)
            wsUrlEt.text.toString().trim() else ""
        val url = overrideUrl.ifEmpty { BuildConfig.DEFAULT_WS_URL }
        val code = pairCodeEt.text.toString().trim().uppercase()
        if (code.isEmpty()) {
            toast("Ketik kode 8 karakter dari dasbor, atau gunakan tombol Hubungkan HP di dasbor")
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
        log("menghubungkan…")
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
        log("disconnected")
        refreshStatus()
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
            runOnUiThread { log("PACKAGE: $pkg"); log("--- DUMP (${xml.length} chars) ---"); log(xml.take(4000)) }
            writeRawXml("agent_dump_raw.xml", xml)
            "PACKAGE=$pkg DUMP_CHARS=${xml.length}"
        } ?: "ERR_SERVICE_NOT_READY"
    }

    private fun runPackage(): String {
        return AgentAccessibilityService.withInstance { svc ->
            val pkg = svc.currentPackage()
            runOnUiThread { log("CURRENT PACKAGE: $pkg") }
            "PACKAGE=$pkg"
        } ?: "ERR_SERVICE_NOT_READY"
    }

    private fun doGlobal(action: (AgentAccessibilityService) -> Boolean) {
        log(runGlobal("global", action))
        refreshStatus()
    }

    private fun runGlobal(label: String, action: (AgentAccessibilityService) -> Boolean): String {
        return AgentAccessibilityService.withInstance { svc ->
            val ok = action(svc)
            runOnUiThread { log("$label ok=$ok"); refreshStatus() }
            "$label=$ok"
        } ?: "ERR_SERVICE_NOT_READY"
    }

    private fun doTapFirstClickable() {
        AgentAccessibilityService.withInstance { svc ->
            val b = svc.tapFirstClickable()
            runOnUiThread {
                if (b != null) log("tap first clickable at [${b.left},${b.top}][${b.right},${b.bottom}]")
                else log("no clickable node found")
            }
        } ?: runOnUiThread { toast("Aktifkan aksesibilitas dulu (Langkah 1)") }
    }

    private fun runTapByText(text: String): String {
        return AgentAccessibilityService.withInstance { svc ->
            val ok = svc.tapByText(text)
            runOnUiThread { log("tapByText($text) ok=$ok") }
            "tapByText=$ok text=$text"
        } ?: "ERR_SERVICE_NOT_READY"
    }

    private fun runSetText(text: String): String {
        return AgentAccessibilityService.withInstance { svc ->
            val ok = svc.setText(text)
            runOnUiThread { log("setText ok=$ok") }
            "setText=$ok"
        } ?: "ERR_SERVICE_NOT_READY"
    }

    private fun runStartApp(pkg: String, activity: String = ""): String {
        return AgentAccessibilityService.withInstance { svc ->
            val act = activity.ifEmpty { null }
            val ok = svc.startApp(pkg, act)
            runOnUiThread { log("startApp($pkg, $activity) ok=$ok") }
            "startApp=$ok pkg=$pkg activity=$activity"
        } ?: "ERR_SERVICE_NOT_READY"
    }

    private fun runListPackages(): String {
        return AgentAccessibilityService.withInstance { svc ->
            val pkgs = svc.listPackages()
            runOnUiThread { log("listPackages count=${pkgs.size}") }
            "listPackages=${pkgs.size} ${pkgs.joinToString(",")}"
        } ?: "ERR_SERVICE_NOT_READY"
    }

    private fun runKillApp(pkg: String): String {
        return AgentAccessibilityService.withInstance { svc ->
            val ok = svc.killApp(pkg)
            runOnUiThread { log("killApp($pkg) ok=$ok") }
            "killApp=$ok pkg=$pkg"
        } ?: "ERR_SERVICE_NOT_READY"
    }

    private fun runHasPackage(pkg: String): String {
        return AgentAccessibilityService.withInstance { svc ->
            val ok = svc.hasPackage(pkg)
            runOnUiThread { log("hasPackage($pkg) ok=$ok") }
            "hasPackage=$ok pkg=$pkg"
        } ?: "ERR_SERVICE_NOT_READY"
    }

    // Menulis hasil ke logcat + internal files dir (run-as readable) agar adb
    // dapat mengambil bukti deterministik tanpa izin storage.
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

    private fun log(line: String) {
        logTv.append(line + "\n")
        val scroll = (logTv.layout?.getLineTop(logTv.lineCount) ?: 0) - logTv.height
        if (scroll > 0) logTv.scrollTo(0, scroll)
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
