package com.gososmed.agent

import org.json.JSONObject

/**
 * Command protocol between the GoSosmed agenthub (server) and the agent.
 *
 * We define a small JSON-over-WebSocket protocol so the server-side
 * `AgentHub` can drive the device exactly the way the adb `Device` driver
 * does. Messages are request/response keyed by `id` so the server can map
 * replies to the awaited call.
 *
 * Request  : { "id": 1, "cmd": "dump" | "tap" | "tapByText" | "setText"
 *                    | "back" | "home" | "recents" | "notify" | "package"
 *                    | "startApp" | "killApp" | "hasPackage" | "listPackages"
 *                    | "dumpWindows" | "screenshot" | "ping", ...args }
 * Response : { "id": 1, "ok": true,  "result": {...} }
 *          : { "id": 1, "ok": false, "error": "..." }
 */
object AgentCommand {

    const val CMD_DUMP = "dump"
    const val CMD_TAP = "tap"
    const val CMD_TAP_BY_TEXT = "tapByText"
    const val CMD_TAP_FIRST_CLICKABLE = "tapFirstClickable"
    const val CMD_SET_TEXT = "setText"
    const val CMD_BACK = "back"
    const val CMD_HOME = "home"
    const val CMD_RECENTS = "recents"
    const val CMD_NOTIFY = "notify"
    const val CMD_PACKAGE = "package"
    const val CMD_PING = "ping"
    const val CMD_START_APP = "startApp"
    const val CMD_KILL_APP = "killApp"
    const val CMD_HAS_PACKAGE = "hasPackage"
    const val CMD_LIST_PACKAGES = "listPackages"
    // FASE 0 V1: enumerate getWindows() windows vs rootInActiveWindow.
    const val CMD_DUMP_WINDOWS = "dumpWindows"
    // FG2: capture current display as PNG (AccessibilityService API 30+).
    const val CMD_SCREENSHOT = "screenshot"

    /** Executes one command request and returns the response JSONObject. */
    fun execute(req: JSONObject): JSONObject {
        val id = req.optInt("id", -1)
        val cmd = req.optString("cmd", "")
        val resp = JSONObject()
        resp.put("id", id)

        // Retry: the AccessibilityService's static instance can briefly become
        // null when MIUI calls onDestroy (on startActivity to another app,
        // e.g. XSpace Dual Apps resolver) then quickly re-binds. Wait a short
        // moment for the re-bound instance to come back before giving up.
        var svc = AgentAccessibilityService.instance
        if (svc == null) {
            for (i in 0..9) {
                Thread.sleep(200)
                svc = AgentAccessibilityService.instance
                if (svc != null) break
            }
        }

        if (svc == null) {
            resp.put("ok", false).put("error", "accessibility service not connected/ready")
            AgentLog.add(cmd, false, 0, "aksesibilitas belum aktif")
            return resp
        }
        // Use the non-null svc instance safely inside the block.
        // v0.4.1: ukur latensi tiap command dan catat ke AgentLog agar pemilik
        // HP melihat langsung apa yang diminta server dan seberapa cepat
        // dikerjakan (transparansi ala tab Logs pada referensi NeuralBridge).
        // v0.5.1: screenshot dipoll dasbor tiap ~1,5 dtk saat viewer aktif —
        // kalau semua dicatat, tab Log penuh spam. Sukses screenshot dicatat
        // paling cepat tiap 30 dtk (ringkas); GAGAL selalu dicatat (itu penting).
        val start = System.currentTimeMillis()
        val result = executeWith(svc, cmd, req)
        val ms = System.currentTimeMillis() - start
        result.put("id", id)
        val ok = result.optBoolean("ok", false)
        if (cmd == CMD_SCREENSHOT && ok) {
            val now = System.currentTimeMillis()
            if (now - lastScreenshotLogAt < SCREENSHOT_LOG_INTERVAL_MS) return result
            lastScreenshotLogAt = now
        }
        AgentLog.add(cmd, ok, ms, dataDetail(cmd, result))
        return result
    }

    private var lastScreenshotLogAt = 0L
    private const val SCREENSHOT_LOG_INTERVAL_MS = 30_000L

    /**
     * v0.5.0: keterangan jujur ke mana DATA sebuah command pergi, supaya log
     * tidak ambigu. Screenshot TIDAK disimpan di HP — gambarnya dikirim ke
     * server sebagai base64 di respons WS. Dump/hierarchy juga dikirim, bukan
     * disimpan permanen (kecuali mode debug menulis raw XML lokal).
     */
    private fun dataDetail(cmd: String, resp: JSONObject): String? {
        if (!resp.optBoolean("ok", false)) return null
        return when (cmd) {
            CMD_SCREENSHOT -> {
                val fmt = resp.optJSONObject("result")?.optString("format", "png.base64")
                "gambar $fmt dikirim ke server (base64) — TIDAK disimpan di HP"
            }
            CMD_DUMP -> "hierarki layar dikirim ke server"
            CMD_DUMP_WINDOWS -> "daftar window dikirim ke server"
            CMD_LIST_PACKAGES -> "daftar paket dikirim ke server"
            else -> null
        }
    }

    private fun executeWith(svc: AgentAccessibilityService, cmd: String, req: JSONObject): JSONObject {
        val resp = JSONObject()
        when (cmd) {
            CMD_PING -> {
                resp.put("ok", true)
                resp.put("result", JSONObject().put("pong", true))
            }
            CMD_DUMP -> {
                val xml = svc.dumpXml()
                resp.put("ok", true)
                resp.put("result", JSONObject().apply {
                    put("xml", xml)
                    put("package", svc.currentPackage())
                })
            }
            CMD_TAP -> {
                val x = req.optInt("x", -1)
                val y = req.optInt("y", -1)
                if (x < 0 || y < 0) {
                    resp.put("ok", false).put("error", "tap requires x,y")
                } else {
                    resp.put("ok", true).put("result", JSONObject().put("ok", svc.tap(x, y)))
                }
            }
            CMD_TAP_BY_TEXT -> {
                val text = req.optString("text", "")
                if (text.isEmpty()) {
                    resp.put("ok", false).put("error", "tapByText requires text")
                } else {
                    resp.put("ok", true).put("result", JSONObject().put("ok", svc.tapByText(text)))
                }
            }
            CMD_TAP_FIRST_CLICKABLE -> {
                val b = svc.tapFirstClickable()
                resp.put("ok", true).put(
                    "result",
                    JSONObject().apply {
                        put("ok", b != null)
                        if (b != null) {
                            put("bounds", "[${b.left},${b.top}][${b.right},${b.bottom}]")
                        }
                    }
                )
            }
            CMD_SET_TEXT -> {
                val text = req.optString("text", "")
                resp.put("ok", true).put("result", JSONObject().put("ok", svc.setText(text)))
            }
            CMD_BACK -> resp.put("ok", true).put("result", JSONObject().put("ok", svc.pressBack()))
            CMD_HOME -> resp.put("ok", true).put("result", JSONObject().put("ok", svc.pressHome()))
            CMD_RECENTS -> resp.put("ok", true).put("result", JSONObject().put("ok", svc.openRecents()))
            CMD_NOTIFY -> resp.put("ok", true).put("result", JSONObject().put("ok", svc.notifyAction()))
            CMD_PACKAGE -> resp.put("ok", true).put("result", JSONObject().put("package", svc.currentPackage()))
            CMD_START_APP -> {
                val pkg = req.optString("package", "")
                if (pkg.isEmpty()) {
                    resp.put("ok", false).put("error", "startApp requires package")
                } else {
                    val activity = req.optString("activity", "")
                    resp.put("ok", true).put(
                        "result",
                        JSONObject().put("ok", svc.startApp(pkg, activity.ifEmpty { null }))
                    )
                }
            }
            CMD_KILL_APP -> {
                val pkg = req.optString("package", "")
                if (pkg.isEmpty()) {
                    resp.put("ok", false).put("error", "killApp requires package")
                } else {
                    resp.put("ok", true).put("result", JSONObject().put("ok", svc.killApp(pkg)))
                }
            }
            CMD_HAS_PACKAGE -> {
                val pkg = req.optString("package", "")
                resp.put("ok", true).put("result", JSONObject().put("installed", svc.hasPackage(pkg)))
            }
            CMD_LIST_PACKAGES -> {
                resp.put("ok", true).put("result", JSONObject().put("packages", svc.listPackages()))
            }
            CMD_DUMP_WINDOWS -> {
                try {
                    val windows = svc.dumpWindows()
                    resp.put("ok", true).put("result", JSONObject().apply {
                        put("windows", windows)
                        put("activePackage", svc.currentPackage())
                    })
                } catch (e: Exception) {
                    resp.put("ok", false).put("error", "dumpWindows: ${e.message}")
                }
            }
            CMD_SCREENSHOT -> {
                try {
                    // Parameter efisiensi opsional dari server: scale (0.25–1),
                    // format (png|jpeg), quality (1–100). Default = PNG penuh
                    // (kompatibel dengan perilaku sebelum v0.4.0).
                    val scale = req.optDouble("scale", 1.0).toFloat()
                    val format = req.optString("format", "png")
                    val quality = req.optInt("quality", 85)
                    val (b64, err) = svc.takeScreenshotBase64(scale, format, quality)
                    if (b64 != null) {
                        val fmt = if (format.equals("jpeg", true) || format.equals("jpg", true)) "jpeg" else "png"
                        resp.put("ok", true).put("result", JSONObject().apply {
                            put("format", "$fmt.base64")
                            put("data", b64)
                        })
                    } else {
                        resp.put("ok", false).put("error", "screenshot: $err")
                    }
                } catch (e: Exception) {
                    resp.put("ok", false).put("error", "screenshot: ${e.message}")
                }
            }
            else -> resp.put("ok", false).put("error", "unknown cmd: $cmd")
        }
        return resp
    }
}
