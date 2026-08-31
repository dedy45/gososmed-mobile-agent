package com.gososmed.agent

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID

/**
 * P0 console: shows service/pairing state and exposes a local command
 * channel (dump/tap/text/global actions) so the agent can be validated on a
 * device WITHOUT a running agenthub server yet. In P1+, the server drives the
 * same commands over AgentWsClient instead.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusTv: TextView
    private lateinit var pairTv: TextView
    private lateinit var logTv: TextView
    private lateinit var wsUrlEt: EditText
    private lateinit var connectBtn: Button
    private lateinit var disconnectBtn: Button
    private lateinit var dumpBtn: Button
    private lateinit var packageBtn: Button
    private lateinit var backBtn: Button
    private lateinit var homeBtn: Button
    private lateinit var tapBtn: Button
    private var ws: AgentWsClient? = null

    private val deviceId: String by lazy { loadOrCreateDeviceId() }
    private val pairingCode: String by lazy { loadOrCreatePairingCode() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTv = findViewById(R.id.statusTv)
        pairTv = findViewById(R.id.pairTv)
        logTv = findViewById<TextView>(R.id.logTv).apply { movementMethod = ScrollingMovementMethod() }
        wsUrlEt = findViewById(R.id.wsUrlEt)
        connectBtn = findViewById(R.id.connectBtn)
        disconnectBtn = findViewById(R.id.disconnectBtn)
        dumpBtn = findViewById(R.id.dumpBtn)
        packageBtn = findViewById(R.id.packageBtn)
        backBtn = findViewById(R.id.backBtn)
        homeBtn = findViewById(R.id.homeBtn)
        tapBtn = findViewById(R.id.tapBtn)

        pairTv.text = "Device: $deviceId\nPairing code: $pairingCode"

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
        // Validation driver: intent extras let us drive dump/tap/text
        // deterministically from adb (no guessing button coordinates).
        handleValidationIntent(intent)
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
        if (url.isEmpty()) {
            toast("Masukkan URL WS server (mis. ws://192.168.1.77:8080/v1/agent/ws)")
            return
        }
        ws?.destroy()
        ws = AgentWsClient(url, deviceId, pairingCode) {
            runOnUiThread { log(it) }
        }
        ws?.start()
    }

    private fun disconnectWs() {
        ws?.destroy()
        ws = null
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

    // Writes a short result to logcat + internal files dir (run-as readable)
    // so `adb` can fetch deterministic evidence without storage permissions.
    private fun writeResult(tag: String, value: String) {
        AgentReceiver.ResultStore.write(this, tag, value)
    }

    private fun log(line: String) {
        logTv.append(line + "\n")
        val scroll = (logTv.layout?.getLineTop(logTv.lineCount) ?: 0) - logTv.height
        if (scroll > 0) logTv.scrollTo(0, scroll)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---- Persist identity (device_id + pairing code) in SharedPreferences ----

    private fun loadOrCreateDeviceId(): String {
        val prefs = getSharedPreferences("agent", MODE_PRIVATE)
        prefs.getString("device_id", null)?.let { return it }
        val id = "agent-" + UUID.randomUUID().toString().substring(0, 8)
        prefs.edit().putString("device_id", id).apply()
        return id
    }

    private fun loadOrCreatePairingCode(): String {
        val prefs = getSharedPreferences("agent", MODE_PRIVATE)
        prefs.getString("pairing_code", null)?.let { return it }
        // 6-digit numeric code; deterministic enough for P0.
        val code = (100000..999999).random().toString()
        prefs.edit().putString("pairing_code", code).apply()
        return code
    }
}
