package com.gososmed.agent

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
 */
class AgentWsClient(
    private val url: String,
    private val deviceId: String,
    private val pairingCode: String,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "GoAgentWS"
        private const val RECONNECT_BASE_MS = 2000L
        private const val RECONNECT_MAX_MS = 30000L
        private const val HEARTBEAT_MS = 15000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout on WS
        .build()
    private var ws: WebSocket? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var closed = false
    private var idSeq = 0L
    private val pending = mutableMapOf<Long, (JSONObject) -> Unit>()

    fun start() {
        closed = false
        connect()
    }

    fun stop() {
        closed = true
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        ws?.close(1000, "client stop")
        ws = null
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    private fun connect() {
        if (closed) return
        val request = Request.Builder().url(url).build()
        onStatus("connecting…")
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStatus("connected")
                register(webSocket)
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
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
                onStatus("disconnected (${t.message ?: "?"})")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStatus("closed")
                scheduleReconnect()
            }
        })
    }

    private fun register(webSocket: WebSocket) {
        val hello = JSONObject()
            .put("type", "register")
            .put("device_id", deviceId)
            .put("pairing_code", pairingCode)
        webSocket.send(hello.toString())
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (!closed) {
                delay(HEARTBEAT_MS)
                val id = nextId()
                pending[id] = { }
                send(JSONObject().put("id", id).put("cmd", AgentCommand.CMD_PING))
            }
        }
    }

    private fun scheduleReconnect() {
        heartbeatJob?.cancel()
        if (closed) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var backoff = RECONNECT_BASE_MS
            while (!closed) {
                delay(backoff)
                if (!closed) connect()
                backoff = (backoff * 2).coerceAtMost(RECONNECT_MAX_MS)
            }
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
