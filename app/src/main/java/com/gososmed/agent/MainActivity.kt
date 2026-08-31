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
        logTv = findViewById(R.id.logTv).apply { movementMethod = ScrollingMovementMethod() }
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
        AgentAccessibilityService.withInstance { svc ->
            val xml = svc.dumpXml()
            val pkg = svc.currentPackage()
            runOnUiThread {
                log("PACKAGE: $pkg")
                log("--- DUMP (${xml.length} chars) ---")
                log(xml.take(4000))
                toast("dump ${xml.length} chars")
            }
        } ?: runOnUiThread { toast("Service tidak aktif") }
    }

    private fun doPackage() {
        AgentAccessibilityService.withInstance { svc ->
            runOnUiThread { log("CURRENT PACKAGE: ${svc.currentPackage()}") }
        } ?: runOnUiThread { toast("Service tidak aktif") }
    }

    private fun doGlobal(action: (AgentAccessibilityService) -> Boolean) {
        AgentAccessibilityService.withInstance { svc ->
            val ok = action(svc)
            runOnUiThread { log("global action ok=$ok"); refreshStatus() }
        } ?: runOnUiThread { toast("Service tidak aktif") }
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
