package com.gososmed.agent

import android.content.Context
import android.content.Intent
import com.gososmed.agent.privileged.AdbPairingController
import com.gososmed.agent.privileged.PrivilegedShellHolder
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

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
    val debugFrameQueue = DebugFrameEncoder.BoundedFrameQueue(maxCapacity = 5)


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
    // v0.9.11: query kesehatan lengkap semua subsistem (accessibility, ADB, WS).
    // Dipakai server untuk preflight sebelum harvest dan oleh dasbor untuk badge
    // status real-time di kartu device.
    const val CMD_HEALTH = "health"
    // v1.0.0: native HTTP media staging — download, SHA-256 verify, register
    // to MediaStore. Does NOT need AccessibilityService; runs on IO thread.
    const val CMD_STAGE_MEDIA = "stageMedia"
    // v1.0.1: 3 fitur performa tinggi (Native waitForNode, replaceText, annotatedScreenshot).
    const val CMD_WAIT_FOR_NODE = "waitForNode"
    const val CMD_REPLACE_TEXT = "replaceText"
    const val CMD_ANNOTATED_SCREENSHOT = "annotatedScreenshot"

    /**
     * v0.9.0 — command yang TIDAK memerlukan AccessibilityService.
     *
     * Dipakai dua hal:
     *  1. [execute] menjawabnya tanpa memeriksa `AgentAccessibilityService.instance`,
     *     sehingga tetap bekerja walau layanan aksesibilitas mati.
     *  2. `AgentWsClient` menjalankannya di thread IO, bukan main thread —
     *     wajib, karena `adbPair` dan `shell` bisa memakan belasan detik dan
     *     memblokir main thread akan memicu ANR.
     *
     * v0.9.10 (audit ANR 2026-09-22): `hasPackage` dan `listPackages` ditambahkan.
     * Keduanya hanya memanggil PackageManager (API Context standar, bukan
     * Accessibility tree). Saat accessibility service mati/unbound oleh OEM,
     * command ini tetap HARUS berfungsi — backend memeriksa package terpasang
     * di setiap awal harvest (ResolvePackage). Kegagalannya menyebabkan 3 dari
     * 5 akun gagal verifikasi total pada insiden produksi 22-Sep-2026.
     *
     * v0.9.11: `ping` dan `health` ditambahkan. Ping HARUS selalu bisa dijawab
     * (itulah fungsinya) — sebelumnya gagal dengan "accessibility service not
     * connected" jika service mati, membuat server salah mengira HP offline.
     * Health mengembalikan snapshot diagnostik lengkap semua subsistem.
     */
    val SERVICE_FREE_COMMANDS = setOf(
        CMD_ADB_PAIR, CMD_SHELL, CMD_HAS_PACKAGE, CMD_LIST_PACKAGES,
        CMD_PING, CMD_HEALTH, CMD_CAPABILITIES, CMD_STAGE_MEDIA
    )

    /**
     * Command yang berpotensi memakan waktu (menunggu launch/kill/wake)
     * tetapi tidak memerlukan main looper UI tree. Dijalankan di coroutine
     * latar belakang agar main thread tidak pernah terblokir (anti-ANR).
     */
    val ASYNC_BACKGROUND_COMMANDS = setOf(
        CMD_START_APP, CMD_KILL_APP, CMD_WAKE, CMD_STAGE_MEDIA,
        CMD_WAIT_FOR_NODE, CMD_REPLACE_TEXT, CMD_ANNOTATED_SCREENSHOT
    )

    /** Executes one command request and returns the response JSONObject. */
    fun execute(req: JSONObject): JSONObject {
        // Protocol V2 routing
        if (req.has("protocol_version")) {
            val version = req.optInt("protocol_version", ProtocolV2.LEGACY_VERSION)
            val requestId = req.optString("request_id", "")
            if (version > ProtocolV2.CURRENT_VERSION || version < ProtocolV2.LEGACY_VERSION) {
                return ProtocolV2.Response.protocolMismatch(requestId, version).toJson()
            }
            if (version == ProtocolV2.CURRENT_VERSION && requestId.isBlank()) {
                return ProtocolV2.Response.error(
                    requestId = "",
                    reasonCode = ProtocolV2.ReasonCodes.ACTION_REJECTED,
                    result = JSONObject().apply { put("error", "missing_or_blank_request_id") }
                ).toJson()
            }
            if (version == ProtocolV2.CURRENT_VERSION) {
                return runBlocking { executeV2(req) }
            }
        }
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
            // v0.9.11 — READINESS GATE: diagnostik terstruktur, bukan pesan
            // generik. Server (dan dasbor) bisa membedakan tiga keadaan:
            //  1. OS reports enabled tapi proses service mati → user harus
            //     toggle Off/On di Accessibility Settings
            //  2. OS reports disabled → user harus mengaktifkan service
            //  3. Kondisi sementara (OEM restart, layanan sedang bind)
            //
            // Sebelum v0.9.11: semua keadaan menghasilkan pesan yang sama
            // ("accessibility service not connected/ready"), server tidak
            // bisa membedakan, dan user tidak tahu harus berbuat apa.
            val osEnabled = AgentAccessibilityService.getAppContext()?.let {
                AgentAccessibilityService.osEnabled(it)
            } ?: false
            val health = AgentAccessibilityService.healthSnapshot()

            val reason = if (osEnabled) {
                "a11y_dead: service terdaftar aktif di OS tapi prosesnya mati — perlu Off/On manual di Accessibility Settings"
            } else {
                "a11y_disabled: service belum diaktifkan di Accessibility Settings"
            }

            resp.put("ok", false)
                .put("error", "accessibility service not connected/ready")
                .put("reason", reason)
                .put("health", health)
            AgentLog.add(cmd, false, 0, reason)
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

    /**
     * Dispatches Protocol V2 commands (PLAN-DETERMINISTIC-ANDROID-PORTAL.md).
     */
    suspend fun executeV2(req: JSONObject): JSONObject {
        val startTime = System.currentTimeMillis()
        val v2Req = ProtocolV2.Request.fromJson(req)
        val requestId = v2Req.requestId
        val cmd = v2Req.cmd
        val args = v2Req.args
        if (requestId.isBlank()) {
            val timing = ProtocolV2.Timing.create(startTime)
            return ProtocolV2.Response.error(
                requestId = "",
                reasonCode = ProtocolV2.ReasonCodes.ACTION_REJECTED,
                result = JSONObject().apply { put("error", "missing_or_blank_request_id") },
                timing = timing
            ).toJson()
        }

        val registered = CommandCancellationRegistry.register(requestId, v2Req.deadlineMs, startTime)
        if (!registered) {
            val timing = ProtocolV2.Timing.create(startTime)
            return ProtocolV2.Response.error(
                requestId = requestId,
                reasonCode = ProtocolV2.ReasonCodes.DEVICE_BUSY,
                result = JSONObject().apply { put("error", "duplicate_request_id_in_flight") },
                timing = timing
            ).toJson()
        }

        try {
            return when (cmd) {
                ProtocolV2.Commands.CANCEL_ACTION -> {
                    val targetId = args.optString("target_request_id", requestId).ifBlank { requestId }
                    val cancelled = CommandCancellationRegistry.cancel(targetId)
                    val timing = ProtocolV2.Timing.create(startTime)
                    ProtocolV2.Response.success(
                        requestId = requestId,
                        result = JSONObject().apply { put("cancelled", cancelled) },
                        timing = timing
                    ).toJson()
                }

                ProtocolV2.Commands.START_DEBUG_FRAMES, ProtocolV2.Commands.STOP_DEBUG_FRAMES -> {
                    val timing = ProtocolV2.Timing.create(startTime)
                    ProtocolV2.Response.success(
                        requestId = requestId,
                        result = JSONObject().apply {
                            put("status", if (cmd == ProtocolV2.Commands.START_DEBUG_FRAMES) "started" else "stopped")
                            put("queue_stats", debugFrameQueue.stats().toJson())
                        },
                        timing = timing
                    ).toJson()
                }


                ProtocolV2.Commands.OBSERVE -> {
                    val svc = AgentAccessibilityService.instance
                    if (svc == null) {
                        val timing = ProtocolV2.Timing.create(startTime)
                        return ProtocolV2.Response.error(
                            requestId = requestId,
                            reasonCode = ProtocolV2.ReasonCodes.DEVICE_BUSY,
                            result = JSONObject().apply { put("error", "accessibility_not_ready") },
                            timing = timing
                        ).toJson()
                    }
                    val displayProvider = SnapshotCollector.AndroidDisplayInfoProvider(svc, svc)
                    val treeSource = WindowRootCollector.AndroidWindowTreeSource(svc)
                    val includeScreenshot = args.optBoolean("screenshot", false)
                    var image: SnapshotImage? = null
                    if (includeScreenshot) {
                        val scale = args.optDouble("scale", 0.5).toFloat()
                        val format = args.optString("format", "jpeg")
                        val quality = args.optInt("quality", 75)
                        val (bytes, width, height) = svc.takeScreenshotRawBytes(scale, format, quality)
                        if (bytes != null) {
                            val binMsgId = "snap-img-$requestId"
                            image = SnapshotImage(
                                format = format,
                                width = width,
                                height = height,
                                quality = quality,
                                binaryMessageId = binMsgId
                            )
                            // Stage frame in bounded queue if needed
                            val meta = DebugFrameEncoder.FrameMetadata(
                                binaryMessageId = binMsgId,
                                frameSeq = 1L,
                                timestampMs = System.currentTimeMillis(),
                                format = format,
                                width = width,
                                height = height,
                                quality = quality,
                                byteLength = bytes.size
                            )
                            debugFrameQueue.enqueue(bytes, meta)
                        }
                    }
                    val snapshot = SnapshotCollector.capture(
                        displayProvider = displayProvider,
                        treeSource = treeSource,
                        image = image
                    )
                    SnapshotRegistry.register(snapshot)
                    val timing = ProtocolV2.Timing.create(startTime)
                    ProtocolV2.Response.success(
                        requestId = requestId,
                        result = snapshot.toJson(),
                        timing = timing
                    ).toJson()
                }

                ProtocolV2.Commands.RESOLVE -> {
                    val snapshotId = if (args.has("snapshot_id")) args.getString("snapshot_id") else null
                    val registeredSnapshot = if (snapshotId != null) {
                        val snap = SnapshotRegistry.get(snapshotId)
                        if (snap == null) {
                            val timing = ProtocolV2.Timing.create(startTime)
                            return ProtocolV2.Response.error(
                                requestId = requestId,
                                reasonCode = ProtocolV2.ReasonCodes.STALE_SNAPSHOT,
                                result = JSONObject().apply {
                                    put("error", "snapshot_not_found_or_expired")
                                    put("snapshot_id", snapshotId)
                                },
                                timing = timing
                            ).toJson()
                        }
                        snap
                    } else null

                    val query = if (args.has("selector")) SelectorQuery.fromJson(args.getJSONObject("selector")) else SelectorQuery.fromJson(args)
                    val nodesList = mutableListOf<SnapshotNode>()
                    if (args.has("nodes")) {
                        val rawNodesArr = args.optJSONArray("nodes") ?: org.json.JSONArray()
                        for (i in 0 until rawNodesArr.length()) {
                            nodesList.add(SnapshotNode.fromJson(rawNodesArr.getJSONObject(i)))
                        }
                    } else if (registeredSnapshot != null) {
                        nodesList.addAll(registeredSnapshot.nodes)
                    } else {
                        val latest = SnapshotRegistry.getLatest()
                        if (latest != null) {
                            nodesList.addAll(latest.nodes)
                        }
                    }

                    val displayW = registeredSnapshot?.display?.width ?: 1080
                    val displayH = registeredSnapshot?.display?.height ?: 2400
                    val resolveResult = DeterministicResolver.resolve(
                        query = query,
                        nodes = nodesList,
                        displayWidth = displayW,
                        displayHeight = displayH
                    )
                    val timing = ProtocolV2.Timing.create(startTime)
                    if (resolveResult.reasonCode != null) {
                        ProtocolV2.Response.error(
                            requestId = requestId,
                            reasonCode = resolveResult.reasonCode,
                            result = JSONObject().apply { put("resolve_result", resolveResult.toJson()) },
                            timing = timing
                        ).toJson()
                    } else {
                        ProtocolV2.Response.success(
                            requestId = requestId,
                            result = JSONObject().apply { put("resolve_result", resolveResult.toJson()) },
                            timing = timing
                        ).toJson()
                    }
                }

                ProtocolV2.Commands.ACT_NODE, ProtocolV2.Commands.ACT_AND_VERIFY -> {
                    val snapshotId = if (args.has("snapshot_id")) args.getString("snapshot_id") else null
                    if (snapshotId.isNullOrEmpty()) {
                        val timing = ProtocolV2.Timing.create(startTime)
                        return ProtocolV2.Response.error(
                            requestId = requestId,
                            reasonCode = ProtocolV2.ReasonCodes.STALE_SNAPSHOT,
                            result = JSONObject().apply { put("error", "missing_mandatory_snapshot_id") },
                            timing = timing
                        ).toJson()
                    }
                    val refSnapshot = SnapshotRegistry.get(snapshotId)
                    if (refSnapshot == null) {
                        val timing = ProtocolV2.Timing.create(startTime)
                        return ProtocolV2.Response.error(
                            requestId = requestId,
                            reasonCode = ProtocolV2.ReasonCodes.STALE_SNAPSHOT,
                            result = JSONObject().apply {
                                put("error", "snapshot_not_found_or_expired")
                                put("snapshot_id", snapshotId)
                            },
                            timing = timing
                        ).toJson()
                    }

                    val isSubmit = args.optBoolean("is_submit", false)
                    val opId = if (args.has("operation_id")) {
                        args.getString("operation_id")
                    } else if (args.has("idempotency_key")) {
                        args.getString("idempotency_key")
                    } else {
                        null
                    }
                    val acquire = MutationGuard.tryAcquireMutation(
                        requestId = requestId,
                        isSubmitAction = isSubmit,
                        operationId = opId
                    )
                    if (!acquire.acquired) {
                        val timing = ProtocolV2.Timing.create(startTime)
                        return ProtocolV2.Response.error(
                            requestId = requestId,
                            reasonCode = acquire.reasonCode ?: ProtocolV2.ReasonCodes.DEVICE_BUSY,
                            timing = timing
                        ).toJson()
                    }
                    try {
                        val svc = AgentAccessibilityService.instance
                        if (svc == null) {
                            val timing = ProtocolV2.Timing.create(startTime)
                            return ProtocolV2.Response.error(
                                requestId = requestId,
                                reasonCode = ProtocolV2.ReasonCodes.DEVICE_BUSY,
                                result = JSONObject().apply { put("error", "accessibility_not_ready") },
                                timing = timing
                            ).toJson()
                        }

                        val selector = if (args.has("selector")) SelectorQuery.fromJson(args.getJSONObject("selector")) else SelectorQuery()
                        val postQuery = if (args.has("postcondition")) SelectorQuery.fromJson(args.getJSONObject("postcondition")) else null
                        val allowGesture = args.optBoolean("allow_gesture_fallback", true)
                        val requireScreenChange = if (cmd == ProtocolV2.Commands.ACT_AND_VERIFY) true else args.optBoolean("require_screen_change", true)
                        val expectedPackage = if (args.has("expected_package")) args.getString("expected_package") else null

                        val actionReq = NodeActionExecutor.ActionRequest(
                            requestId = requestId,
                            selector = selector,
                            action = args.optInt("action", NodeActionExecutor.ACTION_CLICK),
                            actionArgs = if (args.has("action_args")) {
                                val m = mutableMapOf<String, Any>()
                                val jsonArgs = args.getJSONObject("action_args")
                                val keys = jsonArgs.keys()
                                while (keys.hasNext()) {
                                    val k = keys.next()
                                    m[k] = jsonArgs.get(k)
                                }
                                m
                            } else null,
                            expectedPackage = expectedPackage,
                            postconditionQuery = postQuery,
                            allowGestureFallback = allowGesture,
                            requireScreenChange = requireScreenChange,
                            timeoutMs = args.optLong("timeout_ms", 3000L),
                            deadlineMs = v2Req.deadlineMs,
                            expectedTreeHash = refSnapshot.treeHash
                        )

                        val dispatcher = NodeActionExecutor.AndroidActionDispatcher(svc)
                        val displayProvider = SnapshotCollector.AndroidDisplayInfoProvider(svc, svc)
                        val treeSource = WindowRootCollector.AndroidWindowTreeSource(svc)

                        val snapshotProvider: suspend () -> SnapshotResult = {
                            SnapshotCollector.capture(
                                displayProvider = displayProvider,
                                treeSource = treeSource
                            )
                        }

                        val response = NodeActionExecutor.executeAsync(
                            request = actionReq,
                            dispatcher = dispatcher,
                            currentSnapshotProvider = snapshotProvider
                        )
                        response.toJson()
                    } finally {
                        MutationGuard.releaseMutation(requestId)
                    }
                }

                ProtocolV2.Commands.WAIT_FOR_NODE -> {
                    val svc = AgentAccessibilityService.instance
                    if (svc == null) {
                        val timing = ProtocolV2.Timing.create(startTime)
                        return ProtocolV2.Response.error(
                            requestId = requestId,
                            reasonCode = ProtocolV2.ReasonCodes.DEVICE_BUSY,
                            result = JSONObject().apply { put("error", "accessibility_not_ready") },
                            timing = timing
                        ).toJson()
                    }
                    val selector = if (args.has("selector")) SelectorQuery.fromJson(args.getJSONObject("selector")) else SelectorQuery()
                    val timeoutMs = args.optLong("timeout_ms", 5000L)
                    val pollIntervalMs = args.optLong("poll_interval_ms", 100L)
                    val displayProvider = SnapshotCollector.AndroidDisplayInfoProvider(svc, svc)
                    val treeSource = WindowRootCollector.AndroidWindowTreeSource(svc)

                    var matchedNode: SnapshotNode? = null
                    val loopDeadline = startTime + timeoutMs.coerceAtMost(v2Req.deadlineMs)

                    while (System.currentTimeMillis() < loopDeadline) {
                        if (CommandCancellationRegistry.isCancelled(requestId)) {
                            val timing = ProtocolV2.Timing.create(startTime)
                            return ProtocolV2.Response.error(
                                requestId = requestId,
                                reasonCode = ProtocolV2.ReasonCodes.CANCELLED,
                                timing = timing
                            ).toJson()
                        }
                        val snap = SnapshotCollector.capture(displayProvider, treeSource)
                        val resolveResult = DeterministicResolver.resolve(
                            query = selector,
                            nodes = snap.nodes,
                            displayWidth = snap.display.width,
                            displayHeight = snap.display.height
                        )
                        if (resolveResult.selectedNode != null && !resolveResult.isAmbiguous) {
                            matchedNode = resolveResult.selectedNode
                            break
                        }
                        if (CommandCancellationRegistry.isCancelled(requestId)) {
                            val timing = ProtocolV2.Timing.create(startTime)
                            return ProtocolV2.Response.error(
                                requestId = requestId,
                                reasonCode = ProtocolV2.ReasonCodes.CANCELLED,
                                timing = timing
                            ).toJson()
                        }
                        if (pollIntervalMs > 0) {
                            delay(pollIntervalMs)
                        }
                    }

                    val timing = ProtocolV2.Timing.create(startTime)
                    if (matchedNode != null) {
                        ProtocolV2.Response.success(
                            requestId = requestId,
                            result = JSONObject().apply {
                                put("found", true)
                                put("node", matchedNode.toJson())
                            },
                            timing = timing
                        ).toJson()
                    } else {
                        ProtocolV2.Response.error(
                            requestId = requestId,
                            reasonCode = ProtocolV2.ReasonCodes.TIMEOUT,
                            result = JSONObject().apply { put("found", false) },
                            timing = timing
                        ).toJson()
                    }
                }

                ProtocolV2.Commands.WAIT_FOR_SCREEN -> {
                    val svc = AgentAccessibilityService.instance
                    if (svc == null) {
                        val timing = ProtocolV2.Timing.create(startTime)
                        return ProtocolV2.Response.error(
                            requestId = requestId,
                            reasonCode = ProtocolV2.ReasonCodes.DEVICE_BUSY,
                            result = JSONObject().apply { put("error", "accessibility_not_ready") },
                            timing = timing
                        ).toJson()
                    }
                    val timeoutMs = args.optLong("timeout_ms", 5000L)
                    val pollIntervalMs = args.optLong("poll_interval_ms", 100L)
                    val expectedPackage = if (args.has("expected_package")) args.getString("expected_package") else null
                    val postQuery = if (args.has("postcondition")) SelectorQuery.fromJson(args.getJSONObject("postcondition")) else null
                    val displayProvider = SnapshotCollector.AndroidDisplayInfoProvider(svc, svc)
                    val treeSource = WindowRootCollector.AndroidWindowTreeSource(svc)

                    val beforeSnap = SnapshotCollector.capture(displayProvider, treeSource)
                    val transition = ScreenTransitionVerifier.verifyTransitionAsync(
                        beforeSnapshot = beforeSnap,
                        expectedPackage = expectedPackage,
                        postconditionQuery = postQuery,
                        requireScreenChange = args.optBoolean("require_screen_change", false),
                        timeoutMs = timeoutMs.coerceAtMost(v2Req.deadlineMs),
                        pollIntervalMs = pollIntervalMs,
                        isCancelled = { CommandCancellationRegistry.isCancelled(requestId) },
                        snapshotProvider = {
                            if (CommandCancellationRegistry.isCancelled(requestId)) {
                                throw kotlinx.coroutines.CancellationException("cancelled")
                            }
                            SnapshotCollector.capture(displayProvider, treeSource)
                        }
                    )

                    val timing = ProtocolV2.Timing.create(startTime)
                    if (transition.isSettled) {
                        ProtocolV2.Response.success(
                            requestId = requestId,
                            result = JSONObject().apply {
                                put("settled", true)
                                if (transition.afterSnapshot != null) {
                                    put("snapshot", transition.afterSnapshot.toJson())
                                }
                            },
                            timing = timing
                        ).toJson()
                    } else {
                        ProtocolV2.Response.error(
                            requestId = requestId,
                            reasonCode = transition.reasonCode ?: ProtocolV2.ReasonCodes.TIMEOUT,
                            result = JSONObject().apply { put("settled", false) },
                            timing = timing
                        ).toJson()
                    }
                }

                else -> {
                    val timing = ProtocolV2.Timing.create(startTime)
                    ProtocolV2.Response.error(
                        requestId = requestId,
                        reasonCode = ProtocolV2.ReasonCodes.ACTION_NOT_SUPPORTED,
                        result = JSONObject().apply { put("unknown_cmd", cmd) },
                        timing = timing
                    ).toJson()
                }
            }
        } finally {
            CommandCancellationRegistry.unregister(requestId)
        }
    }

    private var lastScreenshotLogAt = 0L
    private const val SCREENSHOT_LOG_INTERVAL_MS = 30_000L

    /**
     * v0.9.10 — applicationContext untuk command SERVICE_FREE yang butuh
     * PackageManager tapi TIDAK butuh AccessibilityService.
     *
     * Sumber: AgentAccessibilityService.appContext (disetel oleh AgentApp.onCreate),
     * atau instance?.applicationContext sebagai fallback. Mengembalikan null hanya
     * pada kasus patologis di mana AgentApp belum pernah dibuat (seharusnya
     * mustahil di runtime normal, tapi JVM unit test bisa).
     */
    private fun appContext(): Context? {
        // Coba instance service dulu (paling cepat, paling murah)
        AgentAccessibilityService.instance?.applicationContext?.let { return it }
        // Fallback ke appContext statis yang disetel AgentApp.onCreate()
        return AgentAccessibilityService.getAppContext()
    }

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
            CMD_HAS_PACKAGE -> {
                // v0.9.10 (audit ANR): dipindah ke SERVICE_FREE karena hanya
                // memanggil PackageManager.getPackageInfo — tidak butuh
                // AccessibilityService. Memakai appContext dari AgentApp.
                val pkg = req.optString("package", "")
                val ctx = appContext()
                if (ctx == null) {
                    resp.put("ok", false).put("error", "application context not available")
                } else {
                    val installed = try {
                        ctx.packageManager.getPackageInfo(pkg, 0)
                        true
                    } catch (_: Exception) {
                        false
                    }
                    resp.put("ok", true).put("result", JSONObject().put("installed", installed))
                }
            }
            CMD_LIST_PACKAGES -> {
                // v0.9.10 (audit ANR): dipindah ke SERVICE_FREE karena hanya
                // memanggil PackageManager.queryIntentActivities — tidak butuh
                // AccessibilityService. Memakai appContext dari AgentApp.
                val ctx = appContext()
                if (ctx == null) {
                    resp.put("ok", false).put("error", "application context not available")
                } else {
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    val apps = ctx.packageManager.queryIntentActivities(intent, 0)
                    val packages = apps.map { it.activityInfo.packageName }.distinct().sorted()
                    resp.put("ok", true).put("result", JSONObject().put("packages", JSONArray(packages)))
                }
            }
            CMD_PING -> {
                // v0.9.11: dipindah ke SERVICE_FREE. Ping HARUS selalu bisa
                // dijawab — sebelumnya gagal saat service mati, membuat server
                // salah mengira HP offline.
                resp.put("ok", true).put("result", JSONObject().put("pong", true))
            }
            CMD_HEALTH -> {
                // v0.9.11: snapshot kesehatan lengkap semua subsistem.
                // Dipakai server untuk preflight job harvest (tolak SEBELUM
                // kirim jika accessibility/ADB mati), dan dasbor untuk badge
                // status real-time di kartu device.
                resp.put("ok", true).put("result", AgentAccessibilityService.healthSnapshot())
            }
            CMD_CAPABILITIES -> {
                resp.put("ok", true).put("result", AgentAccessibilityService.capabilitiesSnapshot(appContext()))
            }
            CMD_STAGE_MEDIA -> {
                val ctx = appContext()
                if (ctx == null) {
                    resp.put("ok", false).put("error", "application context not available")
                } else {
                    val stageUrl      = req.optString("url", "")
                    val stageFilename = req.optString("filename", "staged_${System.currentTimeMillis()}.mp4")
                    val stageSha256   = req.optString("sha256", "")
                    val stageBytes    = req.optLong("bytes", 0L)
                    if (stageUrl.isBlank()) {
                        resp.put("ok", false).put("error", "stageMedia requires url")
                    } else {
                        val result = MediaStager.stageMedia(ctx, stageUrl, stageFilename, stageSha256, stageBytes)
                        // MediaStager returns { ok, result/error } — merge into resp.
                        resp.put("ok", result.optBoolean("ok", false))
                        if (result.optBoolean("ok", false)) {
                            resp.put("result", result.optJSONObject("result"))
                        } else {
                            resp.put("error", result.optString("error", "stageMedia failed"))
                        }
                    }
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
            CMD_SCREENSHOT -> "miror layar ke server"
            CMD_PING -> "Terkoneksi (ping)"
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
            CMD_WAIT_FOR_NODE -> {
                val text = req.optString("text", "").takeIf { it.isNotEmpty() }
                val desc = req.optString("content_desc", "").takeIf { it.isNotEmpty() }
                val resId = req.optString("resource_id", "").takeIf { it.isNotEmpty() }
                val timeout = req.optLong("timeout_ms", 4000L)

                val start = System.currentTimeMillis()
                val (found, rect) = svc.waitForNode(text, desc, resId, timeout)
                val elapsed = System.currentTimeMillis() - start

                resp.put("ok", found)
                resp.put("result", JSONObject().apply {
                    put("found", found)
                    put("elapsed_ms", elapsed)
                    if (rect != null) {
                        put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
                        put("center", JSONObject().apply {
                            put("x", rect.centerX())
                            put("y", rect.centerY())
                        })
                    }
                })
            }
            CMD_REPLACE_TEXT -> {
                val text = req.optString("text", "")
                // 1) Set text baru via Accessibility
                val ok = svc.setText(text)
                // 2) Tekan tombol Back (keyevent 4) satu kali untuk menutup keyboard virtual
                val shell = PrivilegedShellHolder.get()
                if (shell.status().connected) {
                    shell.exec("input keyevent 4", 1000L)
                }
                resp.put("ok", ok)
                resp.put("result", JSONObject().apply {
                    put("replaced", ok)
                    put("keyboard_dismissed", true)
                })
            }
            CMD_ANNOTATED_SCREENSHOT -> {
                // Ambil bitmap layar pada skala 1.0f agar koordinat bounds 1:1 dengan layar
                val (bytes, width, height) = svc.takeScreenshotRawBytes(scale = 1.0f, format = "jpeg", quality = 85)
                if (bytes == null) {
                    resp.put("ok", false).put("error", "gagal mengambil screenshot")
                } else {
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val root = svc.rootInActiveWindow
                    val (base64, elements) = AnnotatedScreenshotHelper.annotate(bmp, root)
                    root?.recycle()
                    bmp.recycle()

                    resp.put("ok", true)
                    resp.put("result", JSONObject().apply {
                        put("image_base64", base64)
                        put("elements", elements)
                        put("width", width)
                        put("height", height)
                    })
                }
            }
            else -> resp.put("ok", false).put("error", "unknown cmd: $cmd")
        }
        return resp
    }
}
