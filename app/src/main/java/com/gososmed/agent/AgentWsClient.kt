package com.gososmed.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Outbound WebSocket client to the GoSosmed agenthub.
 *
 * The DEVICE calls OUT to the server (never the other way around) so the
 * connection works behind NAT/CGNAT with no port forwarding. On drop it
 * auto-reconnects with backoff, and sends a lightweight ping periodically so
 * the server can detect liveness (mirrors the worker heartbeat pattern).
 *
 * M2 (server-issued pairing): the pairing code is now entered by the user from
 * the GoSosmed dashboard (single-use, 8-char), NOT generated locally. The
 * server replies to the "register" hello with a `register_ack` message; on
 * `ok:false` we STOP (no more reconnect loop) and surface the rejection so the
 * UI can ask for a fresh code — this breaks the old "fake connected" loop where
 * a wrong code kept retrying forever.
 *
 * M3: the client now owns the connection lifecycle properly (started/stopped by
 * AgentForegroundService), uses OkHttp `pingInterval` for transport keepalive,
 * and (S3) reconnects immediately when ConnectivityManager reports the network
 * is back instead of waiting out the backoff.
 */
class AgentWsClient(
    private val context: Context,
    private val url: String,
    private val deviceId: String,
    private var pairingCode: String,
    private val onStatus: (String) -> Unit,
    private val onPairingRejected: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "GoAgentWS"
        private const val RECONNECT_BASE_MS = 2000L
        private const val RECONNECT_MAX_MS = 30000L
        private const val HEARTBEAT_MS = 15000L
        private const val PING_INTERVAL_MS = 20000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Accessibility API (rootInActiveWindow / performAction / dispatchGesture)
    // must run on the main thread, but OkHttp invokes onMessage on its own
    // reader thread. We post inbound command execution to the main looper.
    private val mainHandler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout on WS
        .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS) // M3: transport keepalive
        .build()
    private var ws: WebSocket? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var closed = false
    private var rejected = false // M2: pairing ditolak → stop reconnect loop
    private var connected = false
    // Single-flight guard: mencegah dua koneksi paralel saat reconnect-loop dan
    // network-callback saling memicu. Dua socket dengan device_id sama akan
    // saling menendang di server (duplicate registration → flap register/putus).
    private var connecting = false
    private var idSeq = 0L
    private val pending = mutableMapOf<Long, (JSONObject) -> Unit>()

    fun start() {
        if (connected || connecting) return
        closed = false
        rejected = false
        connect()
        registerNetwork()
    }

    fun stop() {
        closed = true
        rejected = false
        connecting = false
        unregisterNetwork()
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        ws?.close(1000, "client stop")
        ws = null
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    /** M2: perbarui pairing code (dari UI) lalu putus WS agar register ulang
     *  memakai kode baru. Dipanggil AgentForegroundService.setPairingCode. */
    fun updatePairingCode(code: String) {
        pairingCode = code
        if (!closed && connected) {
            // Tutup koneksi aktif → onClosed → reconnect dengan kode baru.
            ws?.close(1000, "pairing code updated")
        } else if (!closed) {
            reconnectJob?.cancel()
            connect()
        }
    }

    private fun connect() {
        if (closed || rejected || connecting || connected) return
        connecting = true
        val request = Request.Builder().url(url).build()
        onStatus("connecting…")
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connecting = false
                connected = true
                // BUGFIX flap: batalkan reconnect-loop begitu koneksi sukses,
                // kalau tidak loop tetap membuka socket baru tiap backoff →
                // dua koneksi → server tendang yang lama → register/putus terus.
                reconnectJob?.cancel()
                reconnectJob = null
                onStatus("connected")
                AgentLog.event("tersambung ke server")
                register(webSocket)
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    // M2: server membalas hello register dengan register_ack.
                    if (obj.optString("type") == "register_ack") {
                        if (obj.optBoolean("ok")) {
                            onStatus("paired ✓")
                            AgentLog.event("pairing diterima ✓")
                        } else {
                            val reason = obj.optString("error", "pairing ditolak")
                            onStatus("pairing ditolak: $reason")
                            AgentLog.event("pairing ditolak: $reason")
                            handleReject(reason)
                        }
                        return
                    }
                    connected = true
                    // Inbound command from the agenthub (has a cmd field): this
                    // is the server demanding we act (dump/tap/setText/back/...).
                    // Execute it and reply — this is the whole point of P1.
                    // Accessibility API must run on the main thread, so we post
                    // the execution there (webSocket.send is thread-safe).
                    if (obj.has("cmd")) {
                        mainHandler.post {
                            try {
                                val resp = AgentCommand.execute(obj)
                                webSocket.send(resp.toString())
                            } catch (e: Exception) {
                                Log.w(TAG, "cmd exec error", e)
                            }
                        }
                        return
                    }
                    // Otherwise it's a reply to one of our own client-side
                    // requests; route it to the matching pending callback.
                    val id = obj.optLong("id", -1)
                    pending.remove(id)?.invoke(obj)
                } catch (e: Exception) {
                    Log.w(TAG, "bad server message", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure: ${t.message}")
                connecting = false
                connected = false
                onStatus("disconnected (${t.message ?: "?"})")
                AgentLog.event("koneksi gagal: ${t.message ?: "?"}")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connecting = false
                connected = false
                onStatus("closed")
                AgentLog.event("koneksi ditutup")
                scheduleReconnect()
            }
        })
    }

    private fun register(webSocket: WebSocket) {
        // M2: sertakan id agar server bisa mengaitkan register_ack ke hello ini.
        val id = nextId()
        // v0.4.1: laporkan info perangkat (model, Android, layar, density) agar
        // dasbor menampilkan kartu perangkat informatif ala referensi
        // NeuralBridge — user tidak lagi menebak device_id.
        val dm = context.resources.displayMetrics
        val hello = JSONObject()
            .put("type", "register")
            .put("id", id)
            .put("device_id", deviceId)
            .put("pairing_code", pairingCode)
            .put("device_info", JSONObject()
                .put("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                .put("android_ver", Build.VERSION.RELEASE ?: "")
                .put("sdk_int", Build.VERSION.SDK_INT)
                .put("screen", "${dm.widthPixels}x${dm.heightPixels}")
                .put("density", "${dm.density}x"))
        webSocket.send(hello.toString())
    }

    private fun handleReject(reason: String) {
        // M2: hentikan loop reconnect — user harus memasukkan kode baru.
        rejected = true
        closed = true
        connected = false
        unregisterNetwork()
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        ws?.close(1000, "rejected")
        ws = null
        onPairingRejected(reason)
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (!closed && !rejected) {
                delay(HEARTBEAT_MS)
                val id = nextId()
                pending[id] = { }
                send(JSONObject().put("id", id).put("cmd", AgentCommand.CMD_PING))
            }
        }
    }

    private fun scheduleReconnect() {
        heartbeatJob?.cancel()
        if (closed || rejected) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var backoff = RECONNECT_BASE_MS
            while (!closed && !rejected) {
                delay(backoff)
                // S3: hentikan loop segera begitu koneksi terbentuk; onOpen
                // juga membatalkan job ini, tapi guard ini mencegah socket
                // ganda seandainya ternyata sudah terhubung.
                if (connected || connecting) break
                connect()
                // Backoff eksponensial + jitter acak (hindari reconnect storm
                // saat banyak device restart bersamaan) — S3.
                val jitter = (backoff / 2).coerceAtLeast(0)
                backoff = ((backoff * 2) + kotlin.random.Random.nextLong(0, jitter + 1))
                    .coerceAtMost(RECONNECT_MAX_MS)
            }
        }
    }

    // M3 (S3): reconnect segera saat koneksi jaringan kembali tersedia, alih-alih
    // menunggu backoff habis.
    private fun registerNetwork() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    if (!closed && !rejected && !connected && !connecting) {
                        reconnectJob?.cancel()
                        connect()
                    }
                }
            }
        }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback gagal: ${e.message}")
        }
    }

    private fun unregisterNetwork() {
        val cb = networkCallback ?: return
        networkCallback = null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
            // sudah dilepas / belum terpasang — abaikan
        }
    }

    private fun nextId(): Long = ++idSeq

    private fun send(json: JSONObject) {
        val w = ws ?: return
        w.send(json.toString())
    }

    /**
     * Sends a command and awaits the matching reply. Used by the server-side
     * hub → not invoked from the UI in P0 (we validate dump/tap/text via the
     * in-app local command channel instead).
     */
    fun request(req: JSONObject, timeoutMs: Long = 15_000, onDone: (JSONObject) -> Unit) {
        val id = nextId()
        req.put("id", id)
        pending[id] = onDone
        send(req)
        scope.launch {
            delay(timeoutMs)
            pending.remove(id)?.invoke(JSONObject().put("id", id).put("ok", false).put("error", "timeout"))
        }
    }
}
