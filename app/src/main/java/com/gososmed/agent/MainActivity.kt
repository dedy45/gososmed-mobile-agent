package com.gososmed.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID

/**
 * Console UI: shows service/pairing state, lets the user enter the SERVER-ISSUED
 * pairing code (from the GoSosmed dashboard) + WS URL, and exposes a local
 * command channel (dump/tap/text/global actions) so the agent can be validated
 * on a device WITHOUT a running agenthub server yet.
 *
 * M2/M3: the WebSocket is owned by AgentForegroundService (not this activity),
 * so the connection survives the UI being closed. This activity only sends
 * intents (set pairing / stop) and renders status broadcasts.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusTv: TextView
    private lateinit var pairTv: TextView
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

    private val deviceId: String by lazy { loadOrCreateDeviceId() }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(AgentForegroundService.EXTRA_STATUS) ?: return
            val rejected = intent.getBooleanExtra(AgentForegroundService.EXTRA_REJECTED, false)
            runOnUiThread {
                log(status)
                if (rejected) {
                    pairTv.text = "Device: $deviceId\nPairing DITOLAK — ambil kode baru dari dasbor GoSosmed lalu simpan di sini."
                }
            }
        }
    }

    private fun prefs() = getSharedPreferences("agent", MODE_PRIVATE)

    private fun loadWsUrl(): String = prefs().getString("ws_url", "") ?: ""

    private fun loadPairingCode(): String = prefs().getString("pairing_code", "") ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Start the foreground service so the process stays foreground and
        // the WS connection survives the UI (M3/S1). The service auto-restores
        // a saved connection in its onCreate.
        startForegroundService(Intent(this, AgentForegroundService::class.java))

        statusTv = findViewById(R.id.statusTv)
        pairTv = findViewById(R.id.pairTv)
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

        val savedCode = loadPairingCode()
        pairTv.text = "Device: $deviceId\nPairing code: ${if (savedCode.isEmpty()) "(belum diisi — ambil dari dasbor)" else savedCode}"

        // Restore persisted WS URL + pairing code.
        val saved = loadWsUrl()
        if (saved.isNotEmpty()) {
            wsUrlEt.setText(saved)
        }
        if (savedCode.isNotEmpty()) {
            pairCodeEt.setText(savedCode)
        }

        connectBtn.setOnClickListener { connectWs() }
        disconnectBtn.setOnClickListener { disconnectWs() }
        dumpBtn.setOnClickListener { doDump() }
        packageBtn.setOnClickListener { doPackage() }
        backBtn.setOnClickListener { doGlobal { it.pressBack() } }
        homeBtn.setOnClickListener { doGlobal { it.pressHome() } }
        tapBtn.setOnClickListener { doTapFirstClickable() }

        findViewById<Button>(R.id.openAccessibilityBtn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // Terima update status dari service (WS owned by service).
        val filter = IntentFilter(AgentForegroundService.ACTION_STATUS)
        try {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) {
            Log.w("GoAgent", "registerReceiver gagal: ${e.message}")
        }
        // Validation driver: intent extras let us drive dump/tap/text
        // deterministically from adb (no guessing button coordinates).
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

    private fun handleValidationIntent(intent: Intent?) {
        val cmd = intent?.getStringExtra("cmd") ?: return
        // Service may still be binding (async). Retry with a Handler.
        var attempts = 0
        val runner = object : Runnable {
            override fun run() {
                attempts++
                val ready = AgentAccessibilityService.instance?.isServiceReady() == true
                if (!ready && attempts < 6) {
                    // Retry after 500ms (up to 3s total).
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
        // Clear the intent so a resume (after returning from another app)
        // does not re-run the same command.
        intent.removeExtra("cmd")
    }

    /** Forces a fresh validation run even if the activity was already resumed
     *  (e.g. `am start` with --es after the process is alive). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleValidationIntent(intent)
    }

    private fun refreshStatus() {
        val ready = AgentAccessibilityService.instance?.isServiceReady() == true
        statusTv.text = if (ready)
            "Accessibility: READY (service connected)"
        else
            "Accessibility: NOT ready — enable the service in Settings"
    }

    private fun connectWs() {
        val url = wsUrlEt.text.toString().trim()
        val code = pairCodeEt.text.toString().trim().uppercase()
        if (url.isEmpty()) {
            toast("Masukkan URL WS server (mis. wss://api.bamsbung.id/v1/agent/ws)")
            return
        }
        if (code.isEmpty()) {
            toast("Masukkan kode pairing dari dasbor GoSosmed (8 karakter)")
            return
        }
        // M3/S1: WS dimiliki service — kirim kode + URL, service yang konek.
        val i = Intent(this, AgentForegroundService::class.java).apply {
            action = AgentForegroundService.ACTION_SET_PAIRING
            putExtra(AgentForegroundService.EXTRA_WS_URL, url)
            putExtra(AgentForegroundService.EXTRA_PAIRING_CODE, code)
        }
        startForegroundService(i)
        pairTv.text = "Device: $deviceId\nPairing code: $code"
        log("menghubungkan ke $url …")
    }

    private fun disconnectWs() {
        val i = Intent(this, AgentForegroundService::class.java).apply {
            action = AgentForegroundService.ACTION_STOP_WS
        }
        startService(i)
        log("disconnected")
    }

    // ---- Local command channel (validates agent without server) ----

    private fun doDump() {
        val r = runDump()
        log(r)
    }

    private fun runDump(): String {
        return AgentAccessibilityService.withInstance { svc ->
            val xml = svc.dumpXml()
            val pkg = svc.currentPackage()
            runOnUiThread { log("PACKAGE: $pkg"); log("--- DUMP (${xml.length} chars) ---"); log(xml.take(4000)) }
            // Write raw XML to a separate file for ParseHierarchy validation.
            writeRawXml("agent_dump_raw.xml", xml)
            "PACKAGE=$pkg DUMP_CHARS=${xml.length}"
        } ?: "ERR_SERVICE_NOT_READY"
    }

    private fun doPackage() {
        val r = runPackage()
        log(r)
    }

    private fun runPackage(): String {
        return AgentAccessibilityService.withInstance { svc ->
            val pkg = svc.currentPackage()
            runOnUiThread { log("CURRENT PACKAGE: $pkg") }
            "PACKAGE=$pkg"
        } ?: "ERR_SERVICE_NOT_READY"
    }

    private fun doGlobal(action: (AgentAccessibilityService) -> Boolean) {
        val r = runGlobal("global", action)
        log(r)
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
        } ?: runOnUiThread { toast("Service tidak aktif") }
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
            runOnUiThread { log("setText($text) ok=$ok") }
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

    // Writes a short result to logcat + internal files dir (run-as readable)
    // so `adb` can fetch deterministic evidence without storage permissions.
    private fun writeResult(tag: String, value: String) {
        AgentReceiver.ResultStore.write(this, tag, value)
    }

    // Writes the raw dump XML to the internal files dir for ParseHierarchy
    // validation via `adb shell run-as ... cat files/agent_dump_raw.xml`.
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

    // ---- Persist identity (device_id) in SharedPreferences ----
    // M2: pairing code TIDAK lagi dibuat lokal — server-issued dari dasbor,
    // disimpan oleh AgentForegroundService saat ACTION_SET_PAIRING.

    private fun loadOrCreateDeviceId(): String {
        val prefs = getSharedPreferences("agent", MODE_PRIVATE)
        prefs.getString("device_id", null)?.let { return it }
        val id = "agent-" + UUID.randomUUID().toString().substring(0, 8)
        prefs.edit().putString("device_id", id).apply()
        return id
    }
}
