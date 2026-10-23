package com.gososmed.agent.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.WindowManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Surface
import com.gososmed.agent.AgentWsClient
import com.gososmed.agent.R

/**
 * Hardware H.264 video encoder service via MediaProjection and MediaCodec Surface zero-copy.
 * Dual-Mode Transport:
 *  - Mode A: Outbound WebSocket frames via [AgentWsClient.sendBinary]
 *  - Mode B: Direct Local Browser streaming via [LocalWebServer.broadcastBinary]
 *
 * Compliant with Android 14+ (requires FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION before getMediaProjection).
 * Eliminates 250ms browser VideoDecoder reorder lag via [SpsFix.annexB].
 */
class CaptureService : Service() {

    companion object {
        const val MSG_CONFIG = 3 // Binary [3][w:2][h:2][codecId:1][VPS/SPS/PPS Annex-B]
        const val MSG_FRAME = 4  // Binary [4][key:1][NALU Annex-B]
        const val MSG_AUDIO = 5  // Binary [5][PCM 16-bit 44100Hz Stereo bytes]

        /** Byte [5] pada MSG_CONFIG. Viewer memilih decoder dari nilai ini. */
        const val CODEC_ID_AVC = 1
        const val CODEC_ID_HEVC = 2
        const val CODEC_ID_AV1 = 3
        private val CODEC_IDS = mapOf(
            CodecKind.AVC to CODEC_ID_AVC,
            CodecKind.HEVC to CODEC_ID_HEVC,
            CodecKind.AV1 to CODEC_ID_AV1
        )

        /** Jeda sebelum reconfigure penuh saat pipeline mandek. */
        const val RECONFIGURE_AFTER_MS = 10_000L

        @Volatile var instance: CaptureService? = null
            private set

        fun start(ctx: Context, code: Int, data: Intent) {
            val i = Intent(ctx, CaptureService::class.java).apply {
                putExtra("code", code)
                putExtra("data", data)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CaptureService::class.java))
        }
    }

    private val thread = HandlerThread("goso-encoder").apply { start() }
    private val handler = Handler(thread.looper)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null

    /**
     * Stable identity of the encoder that is currently allowed to broadcast.
     * Set BEFORE start() so callbacks never observe a null during reconfigure().
     * Never cleared except in release(), so stale codecs can still release buffers.
     */
    @Volatile private var activeCodec: MediaCodec? = null

    /** Set once the MediaProjection token is rejected; prevents endless retries. */
    @Volatile private var projectionDead = false

    /** Codec yang sedang dipakai pipeline ini (hasil negosiasi). */
    @Volatile private var currentCodecKind: CodecKind = CodecKind.AVC

    /**
     * Codec yang diminta viewer (mis. browser tidak bisa decode HEVC).
     *
     * DEFAULT = HEVC. Alasannya: jalur utama viewer adalah WebCodecs
     * (port 8766 / HTTPS), yang menerima Annex-B mentah tanpa muxer — pola
     * yang sama dengan ya-webadb/Tango. Di jalur itu HEVC didukung penuh:
     * viewer memakai string `hev1.…` yang dibangun [HevcCodecString].
     *
     * Muxer MSE (`avc1` + `avcC`) tetap H.264-saja dan hanya dipakai sebagai
     * fallback di HTTP :8765 atau saat browser menolak HEVC. Karena itu
     * viewer mengirim `setCodec` → AVC bila decode HEVC gagal, dan
     * [CodecCapability.preferenceOrder] selalu berakhir di AVC.
     */
    @Volatile private var preferredCodec: CodecKind? = CodecKind.HEVC

    /**
     * Mendeteksi pipeline mandek dan memulihkannya.
     *
     * PENTING: layar statis memang menghasilkan 0 frame — itu normal, bukan
     * kerusakan. Karena itu urutannya:
     *   1. Hanya periksa bila ada klien yang menonton.
     *   2. Setelah 3 dtk tanpa frame, MINTA keyframe (murah, tidak merusak).
     *   3. Hanya bila tetap mandek >10 dtk, lakukan reconfigure penuh.
     *
     * Tanpa urutan ini, layar diam akan memicu reconfigure setiap 3 detik dan
     * justru merusak tampilan.
     */
    private val watchdog = ProjectionWatchdog(ProjectionWatchdog.DEFAULT_STALL_MS)

    private val watchdogTicker = object : Runnable {
        override fun run() {
            if (projection == null || projectionDead) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (LocalWebServer.hasClients && watchdog.isStalled(now)) {
                val stalledFor = now - watchdog.lastFrameAt()
                if (stalledFor < RECONFIGURE_AFTER_MS) {
                    // Layar mungkin hanya diam. Minta keyframe, jangan bongkar pipeline.
                    if (stalledFor >= ProjectionWatchdog.DEFAULT_STALL_MS) {
                        requestKeyFrame()
                    }
                } else {
                    android.util.Log.w(
                        "CaptureService",
                        "Pipeline mandek ${stalledFor}ms (total frame ${watchdog.totalFrames()}) " +
                            "— memicu reconfigure"
                    )
                    LocalWebServer.broadcastText(
                        """{"type":"capture_error","reason":"stalled",""" +
                        """"message":"Streaming mandek, mencoba memulihkan otomatis."}"""
                    )
                    watchdog.reset(now)
                    reconfigure()
                }
            }
            handler.postDelayed(this, 1000L)
        }
    }

    private var targetWidth = 720
    private var targetKbps = 2500
    private var targetFps = 30
    private var currentConfigKey = ""
    @Volatile private var cachedRawConfig: ByteArray? = null
    private var audioRecord: AudioRecord? = null
    @Volatile var isAudioEnabled: Boolean = false
        private set

    fun setAudioEnabled(enabled: Boolean) {
        isAudioEnabled = enabled
        if (enabled && audioRecord == null && projection != null) {
            handler.post {
                val p = projection
                if (p != null && audioRecord == null) {
                    startAudioCapture(p)
                }
            }
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            if (id == 0) handler.post { reconfigure() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null || projection != null) return START_NOT_STICKY

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = try {
            mpm.getMediaProjection(code, data)
        } catch (_: Exception) {
            null
        }
        if (p == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { release() }
                stopSelf()
            }
        }, handler)

        projection = p
        projectionDead = false
        instance = this
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        dm?.registerDisplayListener(displayListener, handler)

        handler.post {
            reconfigure()
            startAudioCapture(p)
            handler.removeCallbacks(watchdogTicker)
            handler.postDelayed(watchdogTicker, 2000L)
        }
        return START_NOT_STICKY
    }
    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "remote_stream"
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(channelId, "Remote Live Streaming", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val b = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val n = b.setSmallIcon(R.drawable.ic_logo_gososmed)
            .setContentTitle("GoSosmed Remote Stream")
            .setContentText("Layar sedang ditransmisikan (H.264 MediaCodec)")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(102, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(102, n)
        }
    }

    fun setStreamConfig(w: Int, kbps: Int, fps: Int) = handler.post {
        if (w <= 0) {
            release()
            return@post
        }
        this.targetWidth = w
        this.targetKbps = kbps
        this.targetFps = fps
        reconfigure()
    }

    fun requestKeyFrame() = handler.post {
        try {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (_: Exception) {}
    }

    /**
     * Viewer meminta codec tertentu (biasanya AVC karena browsernya tidak bisa
     * decode HEVC/AV1). Hanya bereaksi bila codec benar-benar berbeda, supaya
     * tidak memicu reconfigure berulang.
     */
    fun requestCodecFallback(mime: String) = handler.post {
        val target = when (mime.lowercase()) {
            "video/avc", "avc", "avc1" -> CodecKind.AVC
            "video/hevc", "hevc", "hvc1" -> CodecKind.HEVC
            "video/av01", "av1", "av01" -> CodecKind.AV1
            else -> return@post
        }
        if (target != currentCodecKind) {
            android.util.Log.i(
                "CaptureService",
                "Viewer meminta codec ${mime} (sekarang ${currentCodecKind.name}) — reconfigure"
            )
            preferredCodec = target
            reconfigure()
        }
    }

    private fun reconfigure() {
        val p = projection ?: return
        if (projectionDead) return
        try {
            val dm = resources.displayMetrics
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val (realW, realH) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
                val bounds = wm.maximumWindowMetrics.bounds
                bounds.width() to bounds.height()
            } else if (wm != null) {
                val point = Point()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealSize(point)
                point.x to point.y
            } else {
                dm.widthPixels to dm.heightPixels
            }

            // Skalakan proporsional sesuai rasio layar fisik asli (misal 1220x2712 -> 720x1600 kelipatan 16)
            val isPortrait = realW <= realH
            val (tw, th) = if (isPortrait) {
                val w = (minOf(targetWidth, realW) / 16 * 16).coerceAtLeast(160)
                val h = ((realH.toLong() * w / realW).toInt() / 16 * 16)
                w to h
            } else {
                val h = (minOf(targetWidth, realH) / 16 * 16).coerceAtLeast(160)
                val w = ((realW.toLong() * h / realH).toInt() / 16 * 16)
                w to h
            }
            // Kunci HARUS menyertakan codec: tanpa itu, perpindahan
            // HEVC -> AVC hanya mengubah bitrate pada encoder lama, dan
            // viewer tetap menerima HEVC walau sudah meminta AVC.
            val key = "$tw:$th:$targetKbps:$targetFps:${preferredCodec ?: currentCodecKind}"

            val canReuse = codec != null &&
                currentConfigKey.startsWith("$tw:$th:") &&
                currentConfigKey.endsWith(":${preferredCodec ?: currentCodecKind}")
            if (canReuse) {
                try {
                    codec!!.setParameters(Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, targetKbps * 1000)
                    })
                    currentConfigKey = key
                    return
                } catch (_: Exception) {}
            }

            // Android 14+ (API 34+): a MediaProjection token is SINGLE-USE for
            // createVirtualDisplay(). Releasing the VirtualDisplay invalidates the
            // projection, and the next createVirtualDisplay() throws:
            //   SecurityException: Don't re-use the resultData to retrieve the same
            //   projection instance / Cannot create VirtualDisplay with non-current
            //   MediaProjection
            // Proven on-device (Redmi Note 13 Pro 5G, Android 15) by rotating the
            // screen: the whole pipeline died and the viewer fell back to JPEG.
            //
            // Correct strategy (same as DeskIn/ToDesk MediaScreenService): keep the
            // ONE VirtualDisplay for the lifetime of the projection and only swap its
            // input Surface + resize it. The old codec/surface may be released, the
            // display must NOT be.
            val oldCodec = codec
            val oldSurface = inputSurface
            codec = null
            inputSurface = null
            try { oldCodec?.stop() } catch (_: Exception) {}
            try { oldCodec?.release() } catch (_: Exception) {}
            try { oldSurface?.release() } catch (_: Exception) {}

            // ---- Codec negotiation with real configure() attempts -------------
            // CodecCapability.negotiate() only checks whether an encoder is
            // REGISTERED. An AV1/HEVC encoder can be registered yet reject our
            // resolution/bitrate. EncoderPipeline tries each codec for real and
            // falls back to AVC, which is guaranteed present on every device.
            //
            // IMPORTANT: setCallback() must be called BEFORE configure() —
            // Android throws IllegalStateException otherwise.
            var chosenKind = CodecKind.AVC
            var newCodec: MediaCodec? = null

            val encoderCallback = object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(mc: MediaCodec, idx: Int) {}

                override fun onOutputFormatChanged(mc: MediaCodec, fmt: MediaFormat) {
                    // AVC mengirim csd-0 (SPS) + csd-1 (PPS). HEVC bisa
                    // mengirim VPS/SPS/PPS sebagai csd-0 gabungan, atau
                    // terpisah di csd-0..csd-2 tergantung pabrikan. Kumpulkan
                    // semua csd-N yang ada, berurutan.
                    val parts = ArrayList<ByteArray>(3)
                    var i = 0
                    while (true) {
                        val buf = fmt.getByteBuffer("csd-$i") ?: break
                        parts.add(toByteArray(buf))
                        i++
                    }
                    if (parts.isEmpty()) return
                    val rawConfig = parts.reduce { a, b -> a + b }
                    publishConfig(tw, th, rawConfig)
                }
                override fun onOutputBufferAvailable(mc: MediaCodec, idx: Int, info: MediaCodec.BufferInfo) {
                    // NOTE: always releaseOutputBuffer() even for a stale codec, otherwise
                    // that codec's output buffer pool starves and the encoder stalls.
                    // The identity check must use a stable reference that is set BEFORE
                    // start(), because `codec` is nulled while reconfigure() is running.
                    if (mc !== activeCodec) {
                        try { mc.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
                        return
                    }
                    try {
                        val buf = mc.getOutputBuffer(idx)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)

                            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (isConfig) {
                                publishConfig(tw, th, toByteArray(buf))
                                mc.releaseOutputBuffer(idx, false)
                                return
                            }

                            val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                            // Rekatkan byte SPS + PPS di depan keyframe
                            val configHeader = if (isKey) (cachedRawConfig ?: ByteArray(0)) else ByteArray(0)
                            val payload = ByteArray(2 + configHeader.size + info.size).apply {
                                this[0] = MSG_FRAME.toByte()
                                this[1] = if (isKey) 1 else 0
                                if (configHeader.isNotEmpty()) {
                                    System.arraycopy(configHeader, 0, this, 2, configHeader.size)
                                }
                                buf.get(this, 2 + configHeader.size, info.size)
                            }

                            // Broadcast directly and exclusively to local high-speed web server (Port 8765)
                            LocalWebServer.broadcastBinary(payload)
                            watchdog.onFrame(android.os.SystemClock.elapsedRealtime())
                        }
                        mc.releaseOutputBuffer(idx, false)
                    } catch (_: Exception) {}
                }

                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    handler.post {
                        if (mc === activeCodec) {
                            reconfigure()
                        }
                    }
                }
            }

            // When the viewer requested a specific codec, try it first but keep
            // AVC as a guaranteed last resort (preferenceOrder always ends there).
            val order = preferredCodec?.let { pref ->
                listOf(pref) + CodecCapability.preferenceOrder.filter { it != pref }
            } ?: CodecCapability.preferenceOrder

            val report = EncoderPipeline.attemptWithReport(order = order) { kind ->
                val mime = CodecCapability.mimeOf(kind)
                val fmt = MediaFormat.createVideoFormat(mime, tw, th).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, targetKbps * 1000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 40_000L)
                    if (Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_PRIORITY, 0)
                    if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
                    // Profile is AVC-specific; HEVC/AV1 use the encoder default.
                    if (kind == CodecKind.AVC) {
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    }
                }
                val candidate = MediaCodec.createEncoderByType(mime)
                try {
                    // setCallback MUST precede configure().
                    candidate.setCallback(encoderCallback, handler)
                    candidate.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                } catch (e: Throwable) {
                    try { candidate.release() } catch (_: Throwable) {}
                    throw e
                }
                newCodec = candidate
                chosenKind = kind
                currentCodecKind = kind
            }

            if (report.failures.isNotEmpty()) {
                android.util.Log.w("CaptureService", "Codec fallback: ${report.failures.joinToString("; ")}")
            }
            android.util.Log.i(
                "CaptureService",
                "Codec ternegosiasi: ${CodecCapability.mimeOf(chosenKind)} (percobaan: ${report.attempted})"
            )

            val newCodecRef = newCodec ?: run {
                android.util.Log.e("CaptureService", "Tidak ada encoder yang bisa dikonfigurasi")
                return
            }

            // Advertise the codec to the viewer so it can pick the right decoder.
            LocalWebServer.broadcastText(
                """{"type":"codec","kind":"${chosenKind.name.lowercase()}",""" +
                """"mime":"${CodecCapability.mimeOf(chosenKind)}",""" +
                """"viewer":"${CodecCapability.viewerCodecOf(chosenKind)}"}"""
            )

            val newSurface = newCodecRef.createInputSurface()
            // Claim ownership BEFORE start() so the first output callbacks are accepted.
            activeCodec = newCodecRef
            newCodecRef.start()

            val existing = virtualDisplay
            if (existing == null) {
                // First pipeline for this projection: create the ONE VirtualDisplay.
                virtualDisplay = p.createVirtualDisplay(
                    "GoSosmedStream", tw, th, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    newSurface, null, handler
                )
            } else {
                // Reuse: swap the encoder input surface and resize in place.
                // Never release()/recreate — that would invalidate the projection token.
                existing.setSurface(newSurface)
                existing.resize(tw, th, dm.densityDpi)
            }

            codec = newCodecRef
            inputSurface = newSurface
            currentConfigKey = key
        } catch (e: Exception) {
            android.util.Log.e("CaptureService", "Reconfigure failed", e)
            // A dead/invalid projection can never be revived: creating a new
            // VirtualDisplay will throw forever. Stop retrying and surface it.
            if (e is SecurityException) {
                projectionDead = true
                android.util.Log.e("CaptureService", "MediaProjection token invalid — pipeline halted. " +
                    "User must re-grant screen capture.")
                LocalWebServer.broadcastText(
                    """{"type":"capture_error","reason":"projection_invalid",""" +
                    """"message":"Izin tangkapan layar kedaluwarsa. Nyalakan ulang Remote Controller di HP."}"""
                )
            }
        }
    }

    private fun publishConfig(w: Int, h: Int, raw: ByteArray) {
        val kind = currentCodecKind
        val isAvc = kind == CodecKind.AVC
        val fixedConfig = SpsFix.annexB(raw, rewriteSps = isAvc)
        cachedRawConfig = fixedConfig

        // Codec string dibangun sekali di sini supaya viewer tidak perlu
        // mem-parse bitstream. Hanya HEVC yang butuh; AVC dihitung viewer
        // dari SPS[1..3] seperti sebelumnya.
        val codecStr = if (kind == CodecKind.HEVC) HevcCodecString.fromAnnexB(fixedConfig) else null
        val id = CODEC_IDS[kind] ?: 0

        val payload = ByteArray(6 + fixedConfig.size).apply {
            this[0] = MSG_CONFIG.toByte()
            this[1] = (w shr 8).toByte()
            this[2] = (w and 0xFF).toByte()
            this[3] = (h shr 8).toByte()
            this[4] = (h and 0xFF).toByte()
            this[5] = id.toByte()
            System.arraycopy(fixedConfig, 0, this, 6, fixedConfig.size)
        }
        LocalWebServer.setConfig(payload, codecStr)
    }

    private fun toByteArray(buffer: java.nio.ByteBuffer): ByteArray {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    private fun release() {
        activeCodec = null
        virtualDisplay?.release()
        virtualDisplay = null
        try { codec?.stop() } catch (_: Exception) {}
        codec?.release()
        codec = null
        inputSurface?.release()
        inputSurface = null
        currentConfigKey = ""
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (_: Exception) {}
    }

    private fun startAudioCapture(p: MediaProjection) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            val config = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                44100,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .build()

            record.startRecording()
            audioRecord = record

            kotlin.concurrent.thread(name = "goso-audio", isDaemon = true) {
                // 4096 bytes = 1024 stereo frames = 23.2 ms of audio per packet.
                // record.read() is BLOCKING and returns real-time-paced data, so it
                // provides the pacing. Never sleep() here: an extra sleep throttles
                // the loop below real-time, AudioRecord's internal FIFO overflows and
                // ~75% of samples are discarded -> "robotic" stuttering audio.
                val buf = ByteArray(4096)
                while (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    if (!isAudioEnabled || !LocalWebServer.hasClients) {
                        try { Thread.sleep(60) } catch (_: Exception) {}
                        continue
                    }
                    val read = record.read(buf, 0, buf.size)
                    if (read > 0) {
                        val packet = ByteArray(1 + read)
                        packet[0] = MSG_AUDIO.toByte()
                        System.arraycopy(buf, 0, packet, 1, read)
                        LocalWebServer.broadcastBinary(packet)
                    } else if (read < 0) {
                        // ERROR_INVALID_OPERATION / DEAD_OBJECT — stop instead of spinning
                        android.util.Log.w("CaptureService", "AudioRecord.read error=$read")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("CaptureService", "Audio capture not supported or denied: ${e.message}")
        }
    }
    override fun onDestroy() {
        instance = null
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            dm?.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {}
        handler.post {
            release()
            projection?.stop()
            projection = null
        }
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
