package com.gososmed.agent

import com.gososmed.agent.privileged.AdbPairingController
import com.gososmed.agent.privileged.PrivilegedShellHolder
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
 *                    | "dumpWindows" | "screenshot" | "ping" | "wake"
 *                    | "capabilities" | "shell" | "adbPair", ...args }
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
    // v0.7.0: nyalakan layar sebelum job saat layar padam (blueprint P0-4).
    const val CMD_WAKE = "wake"
    // v0.7.1: kapabilitas nyata perangkat (izin overlay/BAL, force-stop,
    // screenshot, baterai) untuk PREFLIGHT backend — menolak job yang pasti
    // gagal, bukan menumpuk platform_error.
    const val CMD_CAPABILITIES = "capabilities"
    // v0.9.0: eksekusi shell sebagai uid 2000 lewat transport ADB LOKAL.
    // Hanya biner di daftar izin PrivilegedShell yang boleh jalan; bila
    // transport tidak siap perintah DITOLAK dengan reason yang bisa ditindak,
    // bukan gagal senyap. Server memakainya untuk am/input/pm/dumpsys.
    const val CMD_SHELL = "shell"
    // v0.9.0: mulai alur pairing transport ADB dari sisi server (kontrak §3.2).
    // Menggantikan `shizukuRequest` v0.8.0 yang sudah DIHAPUS.
    const val CMD_ADB_PAIR = "adbPair"

    /**
     * v0.9.0 — command yang TIDAK memerlukan AccessibilityService.
     *
     * Dipakai dua hal:
     *  1. [execute] menjawabnya tanpa memeriksa `AgentAccessibilityService.instance`,
     *     sehingga tetap bekerja walau layanan aksesibilitas mati.
     *  2. `AgentWsClient` menjalankannya di thread IO, bukan main thread —
     *     wajib, karena `adbPair` dan `shell` bisa memakan belasan detik dan
     *     memblokir main thread akan memicu ANR.
     */
    val SERVICE_FREE_COMMANDS = setOf(CMD_ADB_PAIR, CMD_SHELL)

    /** Executes one command request and returns the response JSONObject. */
    fun execute(req: JSONObject): JSONObject {
        val id = req.optInt("id", -1)
        val cmd = req.optString("cmd", "")
        val resp = JSONObject()
        resp.put("id", id)

        // v0.9.0 — command yang TIDAK menyentuh UI perangkat bisa dijawab tanpa
        // AccessibilityService, jadi pemanggil boleh menjalankannya di thread IO.
        // Ini penting untuk dua command yang bisa LAMA:
        //   - `adbPair` : pairing SPAKE2 + TLS handshake, sampai ~20 detik
        //   - `shell`   : round-trip ADB, timeout kontrak sampai 60 detik
        // Menjalankannya di main thread berisiko ANR. Keduanya tidak butuh
        // service, jadi tidak ada alasan menahannya di sana.
        if (cmd in SERVICE_FREE_COMMANDS) {
            val result = executeServiceFree(cmd, req)
            result.put("id", id)
            AgentLog.add(cmd, result.optBoolean("ok", false), 0, dataDetail(cmd, result))
            return result
        }

        // K13 (Plan 07): fail cepat + status jujur — TIDAK ada Thread.sleep
        // di sini lagi (execute berjalan di main looper; tidur ±2 dtk di
        // main thread = risiko ANR dan menumpuk saat burst dasbor). Retry
        // re-bind MIUI sudah ditangani pemanggil di thread IO
        // (AgentWsClient menunggu maks 10×200 ms) atau AgentReceiver
        // (menunggu isServiceReady maks ±3 s) sebelum memanggil execute.
        val svc = AgentAccessibilityService.instance

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
     * v0.9.0 — jawab command yang tidak butuh UI perangkat.
     *
     * Aman dijalankan di thread mana pun (tidak menyentuh AccessibilityService).
     * Kegagalan SELALU membawa `reason` berkode `adb_*` yang bisa ditindak,
     * tidak pernah sukses palsu.
     */
    private fun executeServiceFree(cmd: String, req: JSONObject): JSONObject {
        val resp = JSONObject()
        when (cmd) {
            CMD_SHELL -> {
                val command = req.optString("command", "")
                if (command.isBlank()) {
                    // `ok` luar = command tidak bisa diproses sama sekali.
                    resp.put("ok", false).put("error", "shell requires command")
                } else {
                    val timeout = req.optLong("timeoutMs", 15_000L).coerceIn(1_000L, 60_000L)
                    val res = PrivilegedShellHolder.get().exec(command, timeout)
                    resp.put("ok", true).put(
                        "result",
                        JSONObject().apply {
                            put("ok", res.ok)
                            put("exit_code", res.exitCode)
                            put("stdout", res.stdout)
                            put("stderr", res.stderr)
                            put("transport", AgentAccessibilityService.TRANSPORT_SHELL)
                            if (res.failure != null) put("reason", res.failure)
                        }
                    )
                }
            }
            CMD_ADB_PAIR -> {
                // Dua bentuk pemanggilan:
                //  a) kirim host+port+code → jalankan pairing (blocking, di IO).
                //  b) tanpa argumen → laporkan status saja.
                val code = req.optString("code", "").trim()
                val port = req.optInt("port", -1)
                if (code.isNotEmpty() || port > 0) {
                    val host = req.optString("host", "127.0.0.1").ifBlank { "127.0.0.1" }
                    val (ok, reason) = AdbPairingController.pair(host, port, code)
                    val status = AdbPairingController.status()
                    resp.put("ok", true).put(
                        "result",
                        JSONObject().apply {
                            put("ok", ok)
                            put("paired", status.paired)
                            put("adb_connected", status.connected)
                            if (!ok) put("reason", reason.ifEmpty { status.error })
                        }
                    )
                } else {
                    val status = AdbPairingController.status()
                    resp.put("ok", true).put(
                        "result",
                        JSONObject().apply {
                            put("ok", status.connected)
                            put("paired", status.paired)
                            put("adb_connected", status.connected)
                            if (!status.connected) {
                                put("reason", status.error.ifEmpty { "adb_not_paired: belum dihubungkan" })
                            }
                        }
                    )
                }
            }
            else -> resp.put("ok", false).put("error", "unknown cmd: $cmd")
        }
        return resp
    }

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
                    // v0.7.1: hasil launch TERVERIFIKASI (foreground benar-benar
                    // milik package target), bukan tanda terima pengiriman.
                    // result.ok=false + reason yang bisa ditindak pemilik HP.
                    val (launched, reason) = svc.startAppVerified(pkg, activity.ifEmpty { null })
                    resp.put("ok", true).put(
                        "result",
                        JSONObject().apply {
                            put("ok", launched)
                            put("package", pkg)
                            put("foreground", svc.currentPackage())
                            // v0.9.0: server tahu keandalan launch ini
                            // (shell_adb = deterministik, accessibility =
                            // best-effort dan bisa diblokir BAL/OEM).
                            put("transport", svc.lastLaunchTransport)
                            if (reason != null) put("reason", reason)
                        }
                    )
                }
            }
            CMD_KILL_APP -> {
                val pkg = req.optString("package", "")
                if (pkg.isEmpty()) {
                    resp.put("ok", false).put("error", "killApp requires package")
                } else {
                    // v0.7.1: nyatakan BATAS killApp. killBackgroundProcesses
                    // bukan force-stop; app yang sedang di foreground selamat.
                    // mode dilaporkan agar server tidak mengasumsikan layar
                    // sudah direset ke kondisi deterministik.
                    val mode = svc.killAppMode(pkg)
                    resp.put("ok", true).put(
                        "result",
                        JSONObject().apply {
                            put("ok", mode != "unavailable")
                            put("mode", mode)
                            // v0.9.0: force_stop kini BISA true — hanya bila
                            // jalur shell (ADB lokal) dipakai. Server boleh
                            // menganggap layar benar-benar direset HANYA saat
                            // mode == "force_stop".
                            put("force_stop", mode == "force_stop")
                            put(
                                "transport",
                                if (mode == "force_stop") {
                                    AgentAccessibilityService.TRANSPORT_SHELL
                                } else {
                                    AgentAccessibilityService.TRANSPORT_A11Y
                                }
                            )
                        }
                    )
                }
            }
            CMD_HAS_PACKAGE -> {
                val pkg = req.optString("package", "")
                resp.put("ok", true).put("result", JSONObject().put("installed", svc.hasPackage(pkg)))
            }
            CMD_LIST_PACKAGES -> {
                resp.put("ok", true).put("result", JSONObject().put("packages", svc.listPackages()))
            }
            CMD_CAPABILITIES -> {
                // v0.7.1: kapabilitas dibaca dari sistem, dipakai backend untuk
                // preflight (tolak job yang pasti gagal, dengan alasan jelas).
                resp.put("ok", true).put("result", svc.capabilitiesJson())
            }
            CMD_WAKE -> {
                // v0.7.0: result.ok=false = PowerManager gagal — sisi Go
                // memperlakukan wake sebagai best-effort dan membaca dump
                // berikutnya sebagai kebenaran (layar terkunci = jujur gagal).
                resp.put("ok", true).put("result", JSONObject().put("ok", svc.wakeScreen()))
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
