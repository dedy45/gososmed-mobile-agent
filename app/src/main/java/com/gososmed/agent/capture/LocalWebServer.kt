package com.gososmed.agent.capture

import android.content.Context
import android.os.Build
import android.util.Log
import com.gososmed.agent.control.RemoteControlManager
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Lightweight embedded HTTP & WebSocket server running strictly on local port [port] (default 8765).
 * Active ONLY when Remote Controller toggle is switched ON and Wireless Debugging + Accessibility are active.
 *
 * Serves:
 *  - GET / or GET /index.html: Self-contained interactive HTML5 browser viewer.
 *  - GET /ws: WebSocket endpoint for bidirectional 30-60 FPS live video streaming and real-time touch control.
 */
object LocalWebServer {
    private const val TAG = "LocalWebServer"
    const val DEFAULT_PORT = 8765

    /** Max simultaneous WebSocket viewers. Prevents CPU/buffer flooding. */
    private const val MAX_CLIENTS = 4

    /** How long a browser stays authenticated after a correct PIN. */
    private const val SESSION_TTL_MS = 12 * 60 * 60 * 1000L // 12 h

    /** Max failed PIN attempts before the server refuses further tries. */
    private const val MAX_PIN_ATTEMPTS = 8

    private var serverSocket: ServerSocket? = null
    private var sslServerSocket: ServerSocket? = null
    @Volatile private var running = false
    private val clientSockets = CopyOnWriteArrayList<WebSocketClient>()
    private var boundIp: String = "127.0.0.1"
    @Volatile var cachedConfig: ByteArray? = null
        internal set

    /** String codec WebCodecs (`hev1.…`) untuk config yang sedang berlaku. */
    @Volatile var codec: String? = null
        internal set

    // ---------------------------------------------------------------------
    // Access control
    //
    // Threat model: the server listens on the LAN so a PC browser can connect.
    // Without authentication ANY device on the same Wi-Fi (cafe, office, hotel,
    // guest network) could watch the screen and inject taps — i.e. a remote
    // access trojan. A per-session 6-digit PIN, shown only on the phone, gates
    // every HTTP page and every WebSocket upgrade.
    // ---------------------------------------------------------------------
    @Volatile private var sessionPin: String = ""

    /**
     * Apakah PIN diwajibkan. Default true. Bisa dimatikan pengguna lewat UI
     * untuk skenario tepercaya (mis. demo di jaringan pribadi), tetapi
     * aplikasi memberi peringatan jelas karena mematikan PIN berarti siapa pun
     * di Wi-Fi yang sama bisa mengendalikan perangkat.
     */
    @Volatile private var pinRequired: Boolean = true

    fun setPinRequired(required: Boolean) {
        pinRequired = required
        if (!required) {
            // Mode tanpa PIN: terbitkan satu sesi panjang agar browser yang
            // sudah terbuka tetap bekerja tanpa halaman login.
            sessionPin = ""
            sessions.clear()
            pinAttempts.clear()
            Log.w(TAG, "PIN DINONAKTIFKAN — server lokal terbuka bagi siapa pun di jaringan ini")
        } else {
            rotatePin()
        }
    }

    /** Issued auth cookies -> expiry epoch millis. */
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Failed PIN attempts per client IP, to slow brute force. */
    private val pinAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** True when a PIN is required (always, unless the host app disabled it). */
    val isAuthEnabled: Boolean get() = pinRequired && sessionPin.isNotEmpty()

    /** Whether the user turned PIN protection off (open LAN access). */
    val isPinDisabledByUser: Boolean get() = !pinRequired

    /** The PIN to display on the phone. Empty when the server is stopped. */
    fun currentPin(): String = sessionPin

    /**
     * Generates a fresh 6-digit PIN and clears all sessions. Called whenever the
     * server starts, so a PIN never survives an app restart.
     */
    fun rotatePin(): String {
        if (!pinRequired) {
            // PIN dimatikan pengguna: jangan buat PIN, jangan minta login.
            sessionPin = ""
            sessions.clear()
            pinAttempts.clear()
            Log.w(TAG, "Server lokal dimulai TANPA PIN (dimatikan pengguna)")
            return ""
        }
        val rnd = java.security.SecureRandom()
        val pin = String.format("%06d", rnd.nextInt(1_000_000))
        sessionPin = pin
        sessions.clear()
        pinAttempts.clear()
        // PIN TIDAK boleh masuk logcat: `adb logcat` dapat dibaca siapa pun
        // yang punya akses ADB, dan PIN itu satu-satunya penjaga stream layar.
        Log.i(TAG, "PIN sesi baru dibuat (6 digit)")
        return pin
    }

    private fun issueToken(): String {
        val bytes = ByteArray(24)
        java.security.SecureRandom().nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        sessions[token] = System.currentTimeMillis() + SESSION_TTL_MS
        return token
    }

    private fun isValidToken(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val exp = sessions[token] ?: return false
        if (System.currentTimeMillis() > exp) {
            sessions.remove(token)
            return false
        }
        return true
    }

    /** Extracts the auth token from the Cookie header or the `?t=` query. */
    private fun extractToken(headers: Map<String, String>, requestLine: String): String? {
        headers["cookie"]?.let { raw ->
            for (part in raw.split(';')) {
                val kv = part.trim()
                if (kv.startsWith("goso_auth=")) return kv.substringAfter('=').trim()
            }
        }
        // requestLine is "GET /path?t=xxx HTTP/1.1" — strip the trailing version.
        val query = requestLine.substringAfter('?', "").substringBefore(' ')
        if (query.isNotEmpty()) {
            for (part in query.split('&')) {
                if (part.startsWith("t=")) return part.substringAfter('=').trim()
            }
        }
        return null
    }

    /**
     * Rejects cross-origin WebSocket upgrades (DNS-rebinding defence).
     * A browser always sends Origin; a legitimate viewer sends the server's own.
     */
    private fun originAllowed(headers: Map<String, String>): Boolean {
        val origin = headers["origin"] ?: return true // non-browser client
        return try {
            val host = java.net.URI(origin).host ?: return false
            host == boundIp || host == "localhost" || host == "127.0.0.1" || host == "::1"
        } catch (_: Exception) {
            false
        }
    }

    fun setConfig(payload: ByteArray, codecString: String? = null) {
        cachedConfig = payload
        codec = codecString
        broadcastBinary(payload)
    }
    val isRunning: Boolean
        get() = running && serverSocket?.isBound == true

    val hasClients: Boolean
        get() = running && clientSockets.isNotEmpty()

    fun getLocalUrl(): String = "http://$boundIp:$DEFAULT_PORT"
    fun getHttpsUrl(): String = "https://$boundIp:${DEFAULT_PORT + 1}"

    fun start(port: Int = DEFAULT_PORT, hostIp: String = "127.0.0.1", context: Context? = null) {
        if (running) return
        boundIp = hostIp
        rotatePin()
        running = true
        thread(name = "goso-web-server", isDaemon = true) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                // Bind to the Wi-Fi interface ONLY. Binding to the bare port would
                // listen on 0.0.0.0, exposing the viewer on every interface
                // (mobile data, USB tether, VPN). Falls back to all interfaces only
                // if the address is not assignable.
                val addr = try {
                    java.net.InetSocketAddress(java.net.InetAddress.getByName(hostIp), port)
                } catch (_: Exception) {
                    java.net.InetSocketAddress(port)
                }
                ss.bind(addr)
                serverSocket = ss
                Log.i(TAG, "LocalWebServer started on http://$hostIp:$port (${if (pinRequired) "PIN aktif" else "TANPA PIN"})")
                while (running && !ss.isClosed) {
                    val client = ss.accept()
                    thread(name = "goso-web-client", isDaemon = true) {
                        handleClient(client, context)
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Server socket error: ${e.message}")
            } finally {
                stop()
            }
        }

        // Auto-HTTPS dual-port listener on port 8766 to unlock WebCodecs 60 FPS in browser
        val sslCtx = Tls.context
        if (sslCtx != null) {
            thread(name = "goso-https-server", isDaemon = true) {
                try {
                    val sss = sslCtx.serverSocketFactory.createServerSocket()
                    (sss as? javax.net.ssl.SSLServerSocket)?.reuseAddress = true
                    val sslAddr = try {
                        java.net.InetSocketAddress(java.net.InetAddress.getByName(hostIp), port + 1)
                    } catch (_: Exception) {
                        java.net.InetSocketAddress(port + 1)
                    }
                    sss.bind(sslAddr)
                    sslServerSocket = sss
                    while (running && !sss.isClosed) {
                        val client = sss.accept()
                        thread(name = "goso-https-client", isDaemon = true) {
                            handleClient(client, context)
                        }
                    }
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "SSL Server socket error: ${e.message}")
                }
            }
        }

        // Fallback live JPEG frame streaming when MediaProjection is standby or ungranted
        thread(name = "goso-jpeg-fallback", isDaemon = true) {
            while (running) {
                if (clientSockets.isNotEmpty() && CaptureService.instance == null) {
                    try {
                        val svc = com.gososmed.agent.AgentAccessibilityService.instance
                        if (svc != null && svc.isServiceReady()) {
                            val res = svc.takeScreenshotRawBytes(scale = 0.5f, format = "jpeg", quality = 70, maxWidth = 720)
                            if (res.bytes != null && res.bytes.isNotEmpty()) {
                                val packet = ByteArray(1 + res.bytes.size)
                                packet[0] = 1.toByte() // MSG_JPEG opcode
                                System.arraycopy(res.bytes, 0, packet, 1, res.bytes.size)
                                broadcastBinary(packet)
                            }
                        }
                    } catch (_: Exception) {}
                    try { Thread.sleep(250) } catch (_: Exception) {}
                } else {
                    try { Thread.sleep(500) } catch (_: Exception) {}
                }
            }
        }
    }
    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        try {
            sslServerSocket?.close()
        } catch (_: Exception) {}
        sslServerSocket = null
        for (client in clientSockets) {
            client.close()
        }
        clientSockets.clear()
        Log.i(TAG, "LocalWebServer stopped")
    }

    // ---------------------------------------------------------------------
    // PIN gate
    // ---------------------------------------------------------------------
    private fun handlePinSubmit(
        socket: Socket,
        output: OutputStream,
        headers: Map<String, String>,
        requestLine: String,
        clientIp: String
    ) {
        try {
            val attempts = pinAttempts.getOrDefault(clientIp, 0)
            if (attempts >= MAX_PIN_ATTEMPTS) {
                val body = "Terlalu banyak percobaan. Nyalakan ulang Remote Controller di HP.".toByteArray()
                output.write(
                    ("HTTP/1.1 429 Too Many Requests\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                     "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
                )
                output.write(body)
                output.flush()
                return
            }

            // PIN arrives as ?pin=NNNNNN (GET form).
            val supplied = requestLine.substringAfter("pin=", "")
                .substringBefore('&')
                .substringBefore(' ')
                .trim()

            if (supplied.isNotEmpty() && supplied == sessionPin) {
                pinAttempts.remove(clientIp)
                val token = issueToken()
                val secure = if (requestLine.startsWith("POST")) "" else ""
                val header = "HTTP/1.1 302 Found\r\n" +
                        "Set-Cookie: goso_auth=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=${SESSION_TTL_MS / 1000}\r\n" +
                        "Location: /?t=$token\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n"
                output.write(header.toByteArray(Charsets.UTF_8))
                output.flush()
                Log.i(TAG, "PIN benar dari $clientIp — sesi dibuat")
            } else {
                pinAttempts[clientIp] = attempts + 1
                Log.w(TAG, "PIN salah dari $clientIp (percobaan ${attempts + 1}/$MAX_PIN_ATTEMPTS)")
                serveLoginPage(output, clientIp, wrong = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "handlePinSubmit gagal: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun serveLoginPage(output: OutputStream, clientIp: String, wrong: Boolean = false) {
        val attempts = pinAttempts.getOrDefault(clientIp, 0)
        val html = """<!DOCTYPE html>
<html lang="id"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>GoSosmed — Masukkan PIN</title>
<style>
:root{--bg:#070409;--panel:#160c20;--border:rgba(255,170,130,.18);--ember:#ff7a2f;--txt:#fff7f2;--muted:#b6a6b0;--err:#ef4444}
*{box-sizing:border-box;margin:0;padding:0}
body{background:var(--bg);color:var(--txt);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
min-height:100vh;display:flex;align-items:center;justify-content:center;padding:20px}
.card{background:var(--panel);border:1px solid var(--border);border-radius:18px;padding:28px 24px;width:100%;max-width:360px;
box-shadow:0 24px 60px rgba(0,0,0,.8);text-align:center}
.logo{width:44px;height:44px;margin:0 auto 14px;border-radius:12px;
background:linear-gradient(135deg,#ff7a2f,#ff5a1f);display:flex;align-items:center;justify-content:center;
font-weight:800;font-size:22px;color:#0c0612}
h1{font-size:1.05rem;margin-bottom:6px}
p{font-size:.8rem;color:var(--muted);line-height:1.5;margin-bottom:18px}
.err{color:var(--err);font-size:.78rem;margin-bottom:12px;font-weight:600}
input{width:100%;background:#09040e;border:1px solid var(--border);color:var(--txt);
font-size:1.6rem;letter-spacing:.5em;text-align:center;padding:14px 10px;border-radius:12px;
font-family:ui-monospace,monospace;outline:none}
input:focus{border-color:var(--ember);box-shadow:0 0 0 3px rgba(255,122,47,.15)}
button{width:100%;margin-top:14px;background:linear-gradient(135deg,#ff7a2f,#ff5a1f);color:#070409;
border:none;padding:13px;border-radius:12px;font-size:.92rem;font-weight:700;cursor:pointer}
button:active{transform:translateY(1px)}
.hint{margin-top:16px;font-size:.7rem;color:var(--muted);line-height:1.6}
</style></head><body>
<div class="card">
  <div class="logo">G</div>
  <h1>GoSosmed Remote Controller</h1>
  <p>Masukkan PIN 6 digit yang tampil di layar HP.</p>
  ${if (wrong) """<div class="err">PIN salah. Coba lagi.</div>""" else ""}
  <form method="GET" action="/unlock" autocomplete="off">
    <input name="pin" inputmode="numeric" pattern="[0-9]{6}" maxlength="6"
           placeholder="••••••" autofocus required>
    <button type="submit">Buka</button>
  </form>
  <div class="hint">PIN berubah setiap kali Remote Controller dinyalakan ulang.
  ${if (attempts > 0) "Percobaan gagal: $attempts" else ""}</div>
</div>
</body></html>"""
        val body = html.toByteArray(Charsets.UTF_8)
        val status = if (wrong) "401 Unauthorized" else "200 OK"
        output.write(
            ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
             "Cache-Control: no-store\r\n" +
             "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
        )
        output.write(body)
        output.flush()
    }

    fun broadcastBinary(data: ByteArray) {
        if (!running || clientSockets.isEmpty()) return
        val frame = encodeWsFrame(data, isBinary = true)
        for (client in clientSockets) {
            client.sendRaw(frame)
        }
    }

    fun broadcastText(text: String) {
        if (!running || clientSockets.isEmpty()) return
        val frame = encodeWsFrame(text.toByteArray(Charsets.UTF_8), isBinary = false)
        for (client in clientSockets) {
            client.sendRaw(frame)
        }
    }

    private fun handleClient(socket: Socket, context: Context?) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))

            val requestLine = reader.readLine() ?: return socket.close()
            val headers = mutableMapOf<String, String>()
            var line: String? = reader.readLine()
            while (!line.isNullOrBlank()) {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val k = line.substring(0, idx).trim().lowercase()
                    val v = line.substring(idx + 1).trim()
                    headers[k] = v
                }
                line = reader.readLine()
            }

            val isWsUpgrade = headers["upgrade"]?.equals("websocket", ignoreCase = true) == true
            val clientIp = try { socket.inetAddress.hostAddress ?: "?" } catch (_: Exception) { "?" }

            // ---- Access control -------------------------------------------
            // Every request (page AND websocket) must present a valid session
            // token. Without this, any device on the same Wi-Fi can watch the
            // screen and inject taps.
            val rawPath = requestLine.split(' ').getOrElse(1) { "/" }.substringBefore('?')
            val token = extractToken(headers, requestLine)

            if (rawPath == "/unlock" || rawPath == "/auth") {
                handlePinSubmit(socket, output, headers, requestLine, clientIp)
                return
            }

            // When the user explicitly disabled the PIN, skip the gate entirely.
            // The UI warns loudly that this exposes the device to the whole LAN.
            if (pinRequired && !isValidToken(token)) {
                if (isWsUpgrade) {
                    // WebSocket cannot render a login page: reject clearly.
                    val body = "Unauthorized".toByteArray()
                    output.write(
                        ("HTTP/1.1 401 Unauthorized\r\n" +
                         "Content-Type: text/plain\r\n" +
                         "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                    output.write(body)
                    output.flush()
                    socket.close()
                    return
                }
                serveLoginPage(output, clientIp)
                socket.close()
                return
            }

            if (!originAllowed(headers)) {
                Log.w(TAG, "WebSocket ditolak: Origin tidak cocok (${headers["origin"]})")
                val body = "Forbidden origin".toByteArray()
                output.write(
                    ("HTTP/1.1 403 Forbidden\r\n" +
                     "Content-Type: text/plain\r\n" +
                     "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
                )
                output.write(body)
                output.flush()
                socket.close()
                return
            }

            if (isWsUpgrade) {
                if (clientSockets.size >= MAX_CLIENTS) {
                    Log.w(TAG, "Klien ditolak: batas $MAX_CLIENTS koneksi tercapai")
                    val body = "Too many clients".toByteArray()
                    output.write(
                        ("HTTP/1.1 503 Service Unavailable\r\n" +
                         "Content-Type: text/plain\r\n" +
                         "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                    output.write(body)
                    output.flush()
                    socket.close()
                    return
                }
                val wsKey = headers["sec-websocket-key"] ?: return socket.close()
                val acceptKey = computeWsAccept(wsKey)
                val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"
                output.write(response.toByteArray(Charsets.UTF_8))
                output.flush()

                val wsClient = WebSocketClient(socket, output)
                clientSockets.add(wsClient)

                // Immediately send status info to client
                try {
                    val defaultIme = try {
                        android.provider.Settings.Secure.getString(context?.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
                    } catch (_: Exception) { "" }
                    val imeName = when {
                        defaultIme.contains("latin", ignoreCase = true) || defaultIme.contains("gboard", ignoreCase = true) -> "Gboard"
                        defaultIme.contains("adbkeyboard", ignoreCase = true) -> "ADB Keyboard"
                        defaultIme.contains("stf", ignoreCase = true) -> "STF Keyboard"
                        defaultIme.contains("swiftkey", ignoreCase = true) -> "SwiftKey"
                        defaultIme.contains("samsung", ignoreCase = true) -> "Samsung Keyboard"
                        defaultIme.contains("xm", ignoreCase = true) -> "Xiaomi Keyboard"
                        else -> defaultIme.substringAfterLast('.').ifBlank { "System Keyboard" }
                    }
                    val statusObj = org.json.JSONObject().apply {
                        put("type", "status")
                        put("captureActive", CaptureService.instance != null)
                        put("model", android.os.Build.MODEL)
                        put("manufacturer", android.os.Build.MANUFACTURER)
                        put("activeIme", defaultIme)
                        put("activeImeName", imeName)
                        codec?.let { put("codec", it) }
                    }
                    wsClient.sendRaw(encodeWsFrame(statusObj.toString().toByteArray(Charsets.UTF_8), isBinary = false))
                } catch (_: Exception) {}

                // Immediately send cached SPS/PPS config to newly connected client
                val config = cachedConfig
                if (config != null) {
                    val frame = encodeWsFrame(config, isBinary = true)
                    wsClient.sendRaw(frame)
                }

                // Request keyframe or provide instant snapshot fallback if MediaProjection not active
                if (CaptureService.instance != null) {
                    CaptureService.instance?.onClientConnected()
                } else if (com.gososmed.agent.AgentAccessibilityService.isServiceAlive()) {
                    kotlin.concurrent.thread(name = "goso-initial-snapshot", isDaemon = true) {
                        try {
                            val snap = com.gososmed.agent.AgentAccessibilityService.instance?.takeScreenshotRawBytes(
                                scale = 0.5f, format = "jpeg", quality = 70
                            )
                            if (snap?.bytes != null && !wsClient.isClosed) {
                                val jpegMsg = ByteArray(1 + snap.bytes.size).apply {
                                    this[0] = 0x01.toByte() // MSG_JPEG
                                    System.arraycopy(snap.bytes, 0, this, 1, snap.bytes.size)
                                }
                                wsClient.sendRaw(encodeWsFrame(jpegMsg, isBinary = true))
                            }
                        } catch (_: Exception) {}
                    }
                }
                handleWebSocketConnection(wsClient, input, context)
            } else {
                if (rawPath == "/api/ime") {
                    val defaultIme = try {
                        android.provider.Settings.Secure.getString(context?.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
                    } catch (_: Exception) { "" }
                    val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    val allImes = imm?.inputMethodList?.map { it.id } ?: emptyList()
                    val imeName = when {
                        defaultIme.contains("latin", ignoreCase = true) || defaultIme.contains("gboard", ignoreCase = true) -> "Gboard"
                        defaultIme.contains("adbkeyboard", ignoreCase = true) -> "ADB Keyboard"
                        defaultIme.contains("stf", ignoreCase = true) -> "STF Keyboard"
                        defaultIme.contains("swiftkey", ignoreCase = true) -> "SwiftKey"
                        defaultIme.contains("samsung", ignoreCase = true) -> "Samsung Keyboard"
                        defaultIme.contains("xm", ignoreCase = true) -> "Xiaomi Keyboard"
                        else -> defaultIme.substringAfterLast('.').ifBlank { "System Keyboard" }
                    }
                    val json = org.json.JSONObject().apply {
                        put("ok", true)
                        put("default_ime", defaultIme)
                        put("default_ime_name", imeName)
                        put("all_imes", org.json.JSONArray(allImes))
                    }
                    val jsonBytes = json.toString().toByteArray(Charsets.UTF_8)
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: ${jsonBytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                    output.write(jsonBytes)
                    output.flush()
                    socket.close()
                    return
                }

                if (rawPath == "/api/files") {
                    val fullUrl = requestLine.split(' ').getOrElse(1) { "/" }
                    val query = fullUrl.substringAfter('?', "")
                    val dirParam = query.split('&').find { it.startsWith("dir=") }?.substringAfter("dir=")?.let {
                        try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { null }
                    } ?: "/sdcard/DCIM/Camera"
                    val dir = java.io.File(dirParam)
                    val json = org.json.JSONObject().apply {
                        put("ok", true)
                        put("dir", dir.absolutePath)
                        val arr = org.json.JSONArray()
                        if (dir.exists() && dir.isDirectory) {
                            dir.listFiles()?.sortedByDescending { it.lastModified() }?.take(100)?.forEach { f ->
                                arr.put(org.json.JSONObject().apply {
                                    put("name", f.name)
                                    put("size", f.length())
                                    put("mtime", f.lastModified())
                                    put("is_dir", f.isDirectory)
                                })
                            }
                        }
                        put("files", arr)
                    }
                    val jsonBytes = json.toString().toByteArray(Charsets.UTF_8)
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: ${jsonBytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                    output.write(jsonBytes)
                    output.flush()
                    socket.close()
                    return
                }
                // Static assets router
                if (rawPath.contains("..")) {
                    val msg = "Not Found".toByteArray()
                    output.write("HTTP/1.1 404 Not Found\r\nContent-Length: ${msg.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(msg)
                    output.flush()
                    socket.close()
                    return
                }

                val target = when (rawPath) {
                    "/", "/index.html" -> "html/index.html"
                    else -> "html" + (if (rawPath.startsWith("/")) rawPath else "/$rawPath")
                }

                val mime = when {
                    target.endsWith(".html") -> "text/html; charset=utf-8"
                    target.endsWith(".js")   -> "application/javascript; charset=utf-8"
                    target.endsWith(".css")  -> "text/css; charset=utf-8"
                    target.endsWith(".png")  -> "image/png"
                    target.endsWith(".svg")  -> "image/svg+xml"
                    else -> "application/octet-stream"
                }

                val body = try { context?.assets?.open(target)?.use { it.readBytes() } } catch (_: Exception) { null }
                if (body != null) {
                    val header = "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    output.write(header.toByteArray(Charsets.UTF_8))
                    output.write(body)
                } else {
                    val msg = "Not Found".toByteArray()
                    output.write("HTTP/1.1 404 Not Found\r\nContent-Length: ${msg.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(msg)
                }
                output.flush()
                socket.close()
            }
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleWebSocketConnection(client: WebSocketClient, input: InputStream, context: Context?) {
        try {
            while (running && !client.isClosed) {
                val b0 = input.read()
                if (b0 == -1) break
                val b1 = input.read()
                if (b1 == -1) break

                val opcode = b0 and 0x0F
                if (opcode == 0x08) { // Connection Close
                    break
                }

                val isMasked = (b1 and 0x80) != 0
                var payloadLen = (b1 and 0x7F).toLong()
                if (payloadLen == 126L) {
                    val p0 = input.read()
                    val p1 = input.read()
                    payloadLen = ((p0 shl 8) or p1).toLong()
                } else if (payloadLen == 127L) {
                    payloadLen = 0L
                    for (i in 0 until 8) {
                        payloadLen = (payloadLen shl 8) or (input.read().toLong() and 0xFF)
                    }
                }

                val mask = ByteArray(4)
                if (isMasked) {
                    var readMask = 0
                    while (readMask < 4) {
                        val r = input.read(mask, readMask, 4 - readMask)
                        if (r <= 0) break
                        readMask += r
                    }
                }

                val payload = ByteArray(payloadLen.toInt().coerceAtMost(65536))
                var totalRead = 0
                while (totalRead < payload.size) {
                    val r = input.read(payload, totalRead, payload.size - totalRead)
                    if (r <= 0) break
                    totalRead += r
                }

                if (isMasked) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    }
                }

                if (opcode == 0x01) { // Text message (command)
                    val cmdStr = String(payload, Charsets.UTF_8).trim()
                    if (cmdStr.startsWith("{")) {
                        try {
                            val json = org.json.JSONObject(cmdStr)
                            val cmd = json.optString("cmd", "")
                            when (cmd) {
                                "tap" -> {
                                    val x = json.optInt("x", -1)
                                    val y = json.optInt("y", -1)
                                    RemoteControlManager.handleCommand("tap:$x,$y")
                                }
                                "swipe" -> {
                                    val x1 = json.optInt("x1", -1)
                                    val y1 = json.optInt("y1", -1)
                                    val x2 = json.optInt("x2", -1)
                                    val y2 = json.optInt("y2", -1)
                                    val dur = json.optLong("duration", 200L)
                                    RemoteControlManager.handleCommand("swipe:$x1,$y1,$x2,$y2,$dur")
                                }
                                "back" -> RemoteControlManager.handleCommand("back")
                                "home" -> RemoteControlManager.handleCommand("home")
                                "recents" -> RemoteControlManager.handleCommand("recents")
                                "wake" -> RemoteControlManager.handleCommand("wake")
                                "power", "lock" -> RemoteControlManager.handleCommand("power")
                                "volume_up", "volup" -> RemoteControlManager.handleCommand("volume_up")
                                "volume_down", "voldown" -> RemoteControlManager.handleCommand("volume_down")
                                "rotate" -> RemoteControlManager.handleCommand("rotate")
                                "key", "keyevent" -> {
                                    val code = json.optInt("code", json.optInt("key", -1))
                                    if (code > 0) RemoteControlManager.handleCommand("key:$code")
                                }
                                "requestKeyFrame" -> {
                                    if (CaptureService.instance != null) {
                                        CaptureService.instance?.requestKeyFrame()
                                    } else if (com.gososmed.agent.AgentAccessibilityService.isServiceAlive()) {
                                        kotlin.concurrent.thread(name = "goso-req-snapshot", isDaemon = true) {
                                            try {
                                                val snap = com.gososmed.agent.AgentAccessibilityService.instance?.takeScreenshotRawBytes(
                                                    scale = 0.5f, format = "jpeg", quality = 70
                                                )
                                                if (snap?.bytes != null && !client.isClosed) {
                                                    val jpegMsg = ByteArray(1 + snap.bytes.size).apply {
                                                        this[0] = 0x01.toByte()
                                                        System.arraycopy(snap.bytes, 0, this, 1, snap.bytes.size)
                                                    }
                                                    client.sendRaw(encodeWsFrame(jpegMsg, isBinary = true))
                                                }
                                            } catch (_: Exception) {}
                                        }
                                    }
                                }
                                "setCodec" -> {
                                    val mime = json.optString("mime", "video/avc")
                                    CaptureService.instance?.requestCodecFallback(mime)
                                }
                                "setResolution" -> {
                                    val width = json.optInt("width", 720)
                                    val kbps = json.optInt("kbps", 2500)
                                    val fps = json.optInt("fps", 30)
                                    CaptureService.instance?.setStreamConfig(width, kbps, fps)
                                    CaptureService.instance?.requestKeyFrame()
                                }
                                "setText" -> RemoteControlManager.handleCommand("text:${json.optString("text")}")
                                "startAudio" -> CaptureService.instance?.setAudioEnabled(true)
                                "stopAudio" -> CaptureService.instance?.setAudioEnabled(false)
                                "maestroType", "typeUnicode" -> {
                                    val text = json.optString("text", "")
                                    RemoteControlManager.handleCommand("maestro_type:$text")
                                }
                                "getIme" -> {
                                    val defaultIme = try {
                                        android.provider.Settings.Secure.getString(context?.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
                                    } catch (_: Exception) { "" }
                                    val imeName = when {
                                        defaultIme.contains("latin", ignoreCase = true) || defaultIme.contains("gboard", ignoreCase = true) -> "Gboard"
                                        defaultIme.contains("adbkeyboard", ignoreCase = true) -> "ADB Keyboard"
                                        defaultIme.contains("stf", ignoreCase = true) -> "STF Keyboard"
                                        defaultIme.contains("swiftkey", ignoreCase = true) -> "SwiftKey"
                                        defaultIme.contains("samsung", ignoreCase = true) -> "Samsung Keyboard"
                                        defaultIme.contains("xm", ignoreCase = true) -> "Xiaomi Keyboard"
                                        else -> defaultIme.substringAfterLast('.').ifBlank { "System Keyboard" }
                                    }
                                    val resp = org.json.JSONObject().apply {
                                        put("type", "imeStatus")
                                        put("ok", true)
                                        put("default_ime", defaultIme)
                                        put("default_ime_name", imeName)
                                    }
                                    client.sendRaw(encodeWsFrame(resp.toString().toByteArray(Charsets.UTF_8), isBinary = false))
                                }
                                "listFiles" -> {
                                    val dirPath = json.optString("dir", "/sdcard/DCIM/Camera")
                                    val targetDir = java.io.File(dirPath)
                                    val listResp = org.json.JSONObject().apply {
                                        put("type", "fileList")
                                        put("ok", true)
                                        put("dir", targetDir.absolutePath)
                                        val arr = org.json.JSONArray()
                                        if (targetDir.exists() && targetDir.isDirectory) {
                                            targetDir.listFiles()?.sortedByDescending { it.lastModified() }?.take(100)?.forEach { f ->
                                                arr.put(org.json.JSONObject().apply {
                                                    put("name", f.name)
                                                    put("size", f.length())
                                                    put("mtime", f.lastModified())
                                                    put("is_dir", f.isDirectory)
                                                })
                                            }
                                        }
                                        put("files", arr)
                                    }
                                    client.sendRaw(encodeWsFrame(listResp.toString().toByteArray(Charsets.UTF_8), isBinary = false))
                                }
                                "uploadFile" -> {
                                    val filename = json.optString("filename", "file_${System.currentTimeMillis()}.bin")
                                    val dir = json.optString("dir", "/sdcard/DCIM/Camera")
                                    val base64 = json.optString("data", "")
                                    if (base64.isNotEmpty()) {
                                        kotlin.concurrent.thread(name = "goso-upload", isDaemon = true) {
                                            try {
                                                val targetDir = java.io.File(dir).apply { mkdirs() }
                                                val targetFile = java.io.File(targetDir, filename)
                                                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                                                targetFile.writeBytes(bytes)
                                                val appContext = context ?: com.gososmed.agent.AgentAccessibilityService.getAppContext()
                                                if (appContext != null) {
                                                    android.media.MediaScannerConnection.scanFile(appContext, arrayOf(targetFile.absolutePath), null) { p: String?, uri: android.net.Uri? ->
                                                        val resp = org.json.JSONObject().apply {
                                                            put("type", "uploadResult")
                                                            put("ok", true)
                                                            put("filename", filename)
                                                            put("path", p ?: targetFile.absolutePath)
                                                            put("uri", uri?.toString())
                                                            put("size", bytes.size)
                                                        }
                                                        client.sendRaw(encodeWsFrame(resp.toString().toByteArray(Charsets.UTF_8), isBinary = false))
                                                    }
                                                } else {
                                                    val resp = org.json.JSONObject().apply {
                                                        put("type", "uploadResult")
                                                        put("ok", true)
                                                        put("filename", filename)
                                                        put("path", targetFile.absolutePath)
                                                        put("size", bytes.size)
                                                    }
                                                    client.sendRaw(encodeWsFrame(resp.toString().toByteArray(Charsets.UTF_8), isBinary = false))
                                                }
                                            } catch (t: Throwable) {
                                                val errResp = org.json.JSONObject().apply {
                                                    put("type", "uploadResult")
                                                    put("ok", false)
                                                    put("error", t.message ?: "Upload failed")
                                                }
                                                client.sendRaw(encodeWsFrame(errResp.toString().toByteArray(Charsets.UTF_8), isBinary = false))
                                            }
                                        }
                                    }
                                }
                                else -> RemoteControlManager.handleCommand(cmd)
                            }
                        } catch (_: Exception) {}
                    } else if (cmdStr.startsWith("audio:")) {
                        val on = cmdStr.substringAfter("audio:").trim() == "1"
                        CaptureService.instance?.setAudioEnabled(on)
                    } else {
                        RemoteControlManager.handleCommand(cmdStr)
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            client.close()
            clientSockets.remove(client)
            if (clientSockets.isEmpty()) {
                CaptureService.instance?.setAudioEnabled(false)
            }
        }
    }

    private class WebSocketClient(private val socket: Socket, private val output: OutputStream) {
        var isClosed = false
            private set

        @Synchronized
        fun sendRaw(bytes: ByteArray) {
            if (isClosed) return
            try {
                output.write(bytes)
                output.flush()
            } catch (_: Exception) {
                close()
            }
        }

        fun close() {
            isClosed = true
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun computeWsAccept(key: String): String {
        val magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest((key + magic).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(digest)
    }

    fun encodeWsFrame(data: ByteArray, isBinary: Boolean): ByteArray {
        val opcode = if (isBinary) 0x02 else 0x01
        val firstByte = (0x80 or opcode).toByte() // FIN = 1
        val len = data.size

        return when {
            len <= 125 -> {
                val out = ByteArray(2 + len)
                out[0] = firstByte
                out[1] = len.toByte()
                System.arraycopy(data, 0, out, 2, len)
                out
            }
            len <= 65535 -> {
                val out = ByteArray(4 + len)
                out[0] = firstByte
                out[1] = 126.toByte()
                out[2] = (len shr 8).toByte()
                out[3] = (len and 0xFF).toByte()
                System.arraycopy(data, 0, out, 4, len)
                out
            }
            else -> {
                val out = ByteArray(10 + len)
                out[0] = firstByte
                out[1] = 127.toByte()
                for (i in 0 until 8) {
                    out[2 + i] = ((len.toLong() shr ((7 - i) * 8)) and 0xFF).toByte()
                }
                System.arraycopy(data, 0, out, 10, len)
                out
            }
        }
    }

}
