# Advanced Capture Modes — H.265/AV1, Camera Mirroring, Virtual Displays

> **Untuk agent:** REQUIRED SUB-SKILL: gunakan `executing-plans` untuk mengeksekusi plan ini task-per-task.
> Dokumen ini **untuk pengguna akhir** — tidak memuat host internal, kredensial, atau detail infrastruktur privat.

**Goal:** Menambahkan tiga mode tangkapan tingkat lanjut ke APK GoSosmed Agent: encoder H.265/AV1 (dengan fallback otomatis ke H.264), mirroring kamera (Android 12+), dan pembuatan virtual display tambahan (Android 11+) — semuanya tetap kompatibel dengan viewer browser yang sudah ada.

**Architecture:** `CaptureService` saat ini mengunci satu jalur: `MediaProjection` → `VirtualDisplay(AUTO_MIRROR)` → `MediaCodec(AVC)` → WebSocket. Perubahan memperluas titik-titik itu menjadi tiga sumber frame yang dapat dipilih saat runtime, dengan satu `EncoderPipeline` yang menegosiasikan codec berdasarkan dukungan perangkat dan browser, dan tetap mempertahankan satu `VirtualDisplay` per token MediaProjection (batasan Android 14+).

**Tech Stack:** Kotlin, Android MediaCodec, MediaProjection, Camera2, DisplayManager, WebCodecs/MSE (viewer).

---

## Ringkasan Eksekutif

| Fitur | Status Sekarang | Target |
|---|---|---|
| H.264 AVC Baseline | ✅ Ada | Tetap jadi default & fallback |
| **H.265 HEVC** | ❌ Belum | Nego otomatis, fallback ke AVC |
| **AV1** | ❌ Belum | Nego otomatis, fallback ke HEVC→AVC |
| **Mirror kamera** | ❌ Belum | Android 12+, sumber frame alternatif |
| **Virtual display tambahan** | ❌ Belum | Android 11+, display terpisah dari layar utama |

---

## Temuan Validasi dari Referensi Produksi

Plan ini **tidak dibangun dari nol**. Tiga aplikasi produksi yang sudah dianalisis memakai pola yang sama; kita meniru yang terbukti, bukan menebak.

### Bukti dari DeskIn / ToDesk (`com.zuler.deskin`)

| Temuan | Lokasi | Implikasi untuk kita |
|---|---|---|
| `video/hevc`, `video/av01`, `video/avc` sebagai string MIME | `classes.dex`, `classes4.dex`, `classes5.dex` | Mereka menegosiasikan codec saat runtime, tidak mengunci AVC |
| `VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD` | `classes3.dex` | Flag ini memungkinkan capture saat lockscreen aktif — berguna untuk mode headless |
| `MediaScreenService$startPeriodicCheck` + `numCapturedFrames` | `classes3.dex` | **Watchdog berkala** menghitung frame; jika mandek, projection dianggap mati |
| `MediaProjection is no longer active (periodic check)` | `classes3.dex` | Deteksi proaktif token mati, bukan menunggu exception |
| `bus_restart_media_projection` | `classes3.dex` | Ada event bus khusus untuk memicu permintaan ulang izin |
| `createVirtualDisplay use surface:` / `after createVirtualDisplay` | `classes4.dex` | Log terstruktur di sekitar pembuatan display |
| `Camera2Session`, `getCameraIdList`, `CameraManagerCompat` | `classes.dex` | Mirroring kamera lewat Camera2 + WebRTC, bukan MediaProjection |

### Bukti dari iMyFone MirrorTo (`app-MirrorTo.apk`)

| Temuan | Implikasi |
|---|---|
| `video/hevc`, `video/av01` di `classes2.dex` | Negosiasi codec juga dilakukan di sini |
| `CameraManager$AvailabilityCallback` | Mereka memantau kamera tersedia/hilang saat runtime |
| `askMediaProjectionPermission` / `createMediaProjection` | Pemisahan eksplisit antara minta izin dan membuat projection |

### Kesimpulan Validasi

Ketiga aplikasi produksi **tidak** mengunci satu codec. Mereka semua:
1. Menegosiasikan codec saat runtime (AVC/HEVC/AV1),
2. Memantau kesehatan projection secara berkala,
3. Memakai Camera2 untuk kamera, bukan MediaProjection.

Plan ini mengikuti pola tersebut.

---

## Batasan Platform (jujur, harus dibaca)

| Batasan | Detail | Dampak |
|---|---|---|
| **H.265 lisensi** | Encoder HEVC di sebagian perangkat ada, sebagian tidak | Wajib negosiasi + fallback |
| **AV1 encode di HP** | Sangat jarang ada hardware encoder AV1 di HP 2024 | Biasanya akan jatuh ke HEVC atau AVC |
| **Dukungan browser HEVC** | Chrome/Edge desktop: ya (tergantung OS). Firefox: tidak. Safari: ya | Viewer harus mendeteksi `MediaSource.isTypeSupported` |
| **AV1 decode browser** | Chrome/Firefox modern: ya. Safari 17+: ya | Aman untuk WebCodecs |
| **Kamera butuh izin terpisah** | `CAMERA` runtime permission, bukan bagian MediaProjection | Perlu alur izin kedua |
| **Virtual display tambahan** | Butuh `VIRTUAL_DISPLAY_FLAG_PUBLIC` atau `OWN_CONTENT_ONLY`; di Android 14+ display tambahan bisa dibatasi OEM | Uji di perangkat nyata |
| **Satu token MediaProjection = satu VirtualDisplay** | Sudah ditangani di `CaptureService` | Jangan pernah `release()` lalu buat ulang |

---

## Arsitektur Target

```
                    ┌──────────────────────────────┐
                    │      CaptureService          │
                    │                              │
   ┌────────────────┼──────────────────────────────┼────────────────┐
   │                │                              │                │
   ▼                ▼                              ▼                ▼
┌──────────┐  ┌───────────┐              ┌──────────────┐  ┌──────────────┐
│ Screen   │  │  Camera   │              │ Virtual      │  │  (masa      │
│ Source   │  │  Source   │              │ Display      │  │   depan)     │
│(Projection)│ │ (Camera2) │              │ Source       │  │              │
└────┬─────┘  └─────┬─────┘              └──────┬───────┘  └──────────────┘
     │              │                            │
     └──────────────┴────────────┬───────────────┘
                                 ▼
                    ┌────────────────────────┐
                    │   EncoderPipeline      │
                    │  (codec negotiation)   │
                    │  AV1 → HEVC → AVC      │
                    └───────────┬────────────┘
                                ▼
                    ┌────────────────────────┐
                    │  LocalWebServer (WS)   │
                    │  MSG_CONFIG / MSG_FRAME│
                    └────────────────────────┘
```

**Prinsip desain:**
- Satu `EncoderPipeline` yang menerima `Surface` dari sumber mana pun.
- Sumber frame dipilih saat start, bisa diganti tanpa mematikan service.
- Codec dinegosiasikan sekali saat pipeline dibuat; fallback berjenjang.
- Protokol WS **tidak berubah** — viewer lama tetap jalan.

---

## Rencana Task (TDD, bite-sized)

### Task 1: Codec Capability Probe

**Files:**
- Create: `app/src/main/java/com/gososmed/agent/capture/CodecCapability.kt`
- Test: `app/src/test/java/com/gososmed/agent/capture/CodecCapabilityTest.kt`

**Step 1: Tulis test yang gagal**

```kotlin
class CodecCapabilityTest {
    @Test fun `preferred order is av1 then hevc then avc`() {
        assertEquals(
            listOf(CodecKind.AV1, CodecKind.HEVC, CodecKind.AVC),
            CodecCapability.preferenceOrder
        )
    }

    @Test fun `mime for each kind`() {
        assertEquals("video/av01", CodecCapability.mimeOf(CodecKind.AV1))
        assertEquals("video/hevc", CodecCapability.mimeOf(CodecKind.HEVC))
        assertEquals("video/avc",  CodecCapability.mimeOf(CodecKind.AVC))
    }

    @Test fun `avc is always supported fallback`() {
        assertTrue(CodecCapability.isFallback(CodecKind.AVC))
        assertFalse(CodecCapability.isFallback(CodecKind.HEVC))
    }
}
```

**Step 2: Jalankan, pastikan GAGAL**

```bash
gradle testDebugUnitTest --tests '*CodecCapabilityTest*'
```
Expected: FAIL — `Unresolved reference: CodecCapability`

**Step 3: Implementasi minimal**

```kotlin
package com.gososmed.agent.capture

import android.media.MediaCodecList

enum class CodecKind { AV1, HEVC, AVC }

object CodecCapability {
    val preferenceOrder = listOf(CodecKind.AV1, CodecKind.HEVC, CodecKind.AVC)

    fun mimeOf(kind: CodecKind): String = when (kind) {
        CodecKind.AV1  -> "video/av01"
        CodecKind.HEVC -> "video/hevc"
        CodecKind.AVC  -> "video/avc"
    }

    fun isFallback(kind: CodecKind): Boolean = kind == CodecKind.AVC

    /**
     * Memilih codec pertama di [preferenceOrder] yang punya encoder hardware
     * di perangkat ini. AVC selalu tersedia (software minimal), jadi fungsi
     * ini tidak pernah mengembalikan null.
     */
    fun negotiate(preferred: List<CodecKind> = preferenceOrder): CodecKind {
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .codecInfos
            .filter { it.isEncoder }
        for (kind in preferred) {
            val mime = mimeOf(kind)
            if (encoders.any { enc -> enc.supportedTypes.any { it.equals(mime, true) } }) {
                return kind
            }
        }
        return CodecKind.AVC
    }
}
```

**Step 4: Jalankan, pastikan LULUS**

```bash
gradle testDebugUnitTest --tests '*CodecCapabilityTest*'
```
Expected: PASS (3 tests)

**Step 5: Commit**

```bash
git add app/src/main/java/com/gososmed/agent/capture/CodecCapability.kt \
        app/src/test/java/com/gososmed/agent/capture/CodecCapabilityTest.kt
git commit -m "feat(capture): add codec capability probe with AV1/HEVC/AVC preference"
```

---

### Task 2: EncoderPipeline dengan fallback berjenjang

**Files:**
- Create: `app/src/main/java/com/gososmed/agent/capture/EncoderPipeline.kt`
- Test: `app/src/test/java/com/gososmed/agent/capture/EncoderPipelineTest.kt`

**Step 1: Tulis test yang gagal**

```kotlin
class EncoderPipelineTest {
    @Test fun `falls back to next codec when configure throws`() {
        val attempts = mutableListOf<CodecKind>()
        val selected = EncoderPipeline.selectWithFallback(
            order = listOf(CodecKind.AV1, CodecKind.HEVC, CodecKind.AVC),
            tryConfigure = { kind ->
                attempts += kind
                if (kind != CodecKind.AVC) throw IllegalStateException("unsupported")
            }
        )
        assertEquals(CodecKind.AVC, selected)
        assertEquals(listOf(CodecKind.AV1, CodecKind.HEVC, CodecKind.AVC), attempts)
    }

    @Test fun `stops at first success`() {
        val attempts = mutableListOf<CodecKind>()
        val selected = EncoderPipeline.selectWithFallback(
            order = listOf(CodecKind.AV1, CodecKind.HEVC, CodecKind.AVC),
            tryConfigure = { kind -> attempts += kind }
        )
        assertEquals(CodecKind.AV1, selected)
        assertEquals(listOf(CodecKind.AV1), attempts)
    }
}
```

**Step 2: Jalankan, pastikan GAGAL**

Expected: FAIL — `Unresolved reference: EncoderPipeline`

**Step 3: Implementasi minimal**

```kotlin
package com.gososmed.agent.capture

object EncoderPipeline {
    /**
     * Mencoba setiap codec berurutan sampai satu berhasil dikonfigurasi.
     * [tryConfigure] melempar exception bila codec tidak bisa dipakai.
     * Mengembalikan codec yang berhasil, atau AVC bila semua gagal
     * (AVC dijamin ada di semua perangkat Android).
     */
    inline fun selectWithFallback(
        order: List<CodecKind>,
        tryConfigure: (CodecKind) -> Unit
    ): CodecKind {
        for (kind in order) {
            try {
                tryConfigure(kind)
                return kind
            } catch (_: Throwable) {
                // lanjut ke codec berikutnya
            }
        }
        return CodecKind.AVC
    }
}
```

**Step 4: Jalankan, pastikan LULUS**

Expected: PASS (2 tests)

**Step 5: Commit**

```bash
git commit -am "feat(capture): add encoder fallback chain AV1 -> HEVC -> AVC"
```

---

### Task 3: Integrasi codec ke CaptureService

**Files:**
- Modify: `app/src/main/java/com/gososmed/agent/capture/CaptureService.kt:259-270`

**Step 1: Ubah pembuatan format agar memakai codec ternegosiasi**

Ganti blok tetap AVC:

```kotlin
// SEBELUM
val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, tw, th).apply {
    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
}
val newCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
```

Menjadi:

```kotlin
// SESUDAH
val negotiatedKind = CodecCapability.negotiate()
val negotiatedMime = CodecCapability.mimeOf(negotiatedKind)

val format = MediaFormat.createVideoFormat(negotiatedMime, tw, th).apply {
    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    setInteger(MediaFormat.KEY_BIT_RATE, targetKbps * 1000)
    setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 40_000L)
    if (Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_PRIORITY, 0)
    if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
    // Profile hanya untuk AVC; HEVC/AV1 memakai default encoder.
    if (negotiatedKind == CodecKind.AVC) {
        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
    }
}

val newCodec = MediaCodec.createEncoderByType(negotiatedMime)
Log.i("CaptureService", "Codec ternegosiasi: $negotiatedMime")
```

**Step 2: Kirim jenis codec ke viewer lewat MSG_CONFIG**

Protokol `MSG_CONFIG` saat ini: `[3][wHi][wLo][hHi][hLo][SPS][PPS]`. Tambahkan byte codec di indeks 5 **hanya jika** bukan AVC, agar viewer lama tidak rusak. Lebih aman: kirim pesan teks terpisah.

```kotlin
// setelah publishConfig(...)
LocalWebServer.broadcastText(
    """{"type":"codec","kind":"${negotiatedKind.name.lowercase()}","mime":"$negotiatedMime"}"""
)
```

**Step 3: Verifikasi di perangkat**

```bash
gradle assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s CaptureService | grep "Codec ternegosiasi"
```
Expected: `Codec ternegosiasi: video/avc` pada perangkat tanpa HEVC, atau `video/hevc` bila ada.

**Step 4: Commit**

```bash
git commit -am "feat(capture): negotiate codec at runtime with AVC fallback"
```

---

### Task 4: Viewer — dukungan HEVC/AV1 di WebCodecs & MSE

**Files:**
- Modify: `app/src/main/assets/html/js/stream.js`

**Step 1: Tangani pesan `codec`**

```js
} else if (msg.type === 'codec') {
  const mime = msg.mime;
  const supported = window.MediaSource
    ? MediaSource.isTypeSupported(`video/mp4; codecs="${mime === 'video/hevc' ? 'hvc1' : mime === 'video/av01' ? 'av01' : 'avc1.42E01E'}"`)
    : false;
  log(`Codec dari HP: ${mime} — ${supported ? 'didukung browser' : 'TIDAK didukung, minta fallback'}`, supported ? 'success' : 'warn');
  if (!supported && window.GoApp) {
    window.GoApp.sendControl(JSON.stringify({ cmd: 'setCodec', mime: 'video/avc' }));
  }
}
```

**Step 2: Minta fallback ke AVC bila browser tidak mendukung**

Tambahkan handler di `LocalWebServer.handleWebSocketConnection`:

```kotlin
"setCodec" -> {
    val mime = json.optString("mime", "video/avc")
    CaptureService.instance?.requestCodecFallback(mime)
}
```

Dan di `CaptureService`:

```kotlin
fun requestCodecFallback(mime: String) = handler.post {
    if (mime == MediaFormat.MIMETYPE_VIDEO_AVC && currentCodecMime != mime) {
        currentCodecMime = mime
        reconfigure()   // pipeline akan memilih AVC
    }
}
```

**Step 3: Verifikasi**

Buka viewer di Chrome, cek log:
- Perangkat HEVC + Chrome desktop → `Codec dari HP: video/hevc — didukung browser`
- Firefox → `TIDAK didukung, minta fallback` → stream tetap jalan dengan AVC

**Step 4: Commit**

```bash
git commit -am "feat(viewer): negotiate codec support and request AVC fallback"
```

---

### Task 5: Watchdog MediaProjection (pola DeskIn `startPeriodicCheck`)

**Files:**
- Modify: `app/src/main/java/com/gososmed/agent/capture/CaptureService.kt`

**Step 1: Tulis test logika watchdog**

```kotlin
class ProjectionWatchdogTest {
    @Test fun `detects stalled pipeline after threshold`() {
        val wd = ProjectionWatchdog(stallThresholdMs = 3000)
        wd.onFrame(nowMs = 1000)
        assertEquals(false, wd.isStalled(nowMs = 2000))
        assertEquals(true,  wd.isStalled(nowMs = 5000))
    }

    @Test fun `recovers when frames resume`() {
        val wd = ProjectionWatchdog(stallThresholdMs = 3000)
        wd.onFrame(nowMs = 1000)
        assertTrue(wd.isStalled(nowMs = 5000))
        wd.onFrame(nowMs = 5100)
        assertFalse(wd.isStalled(nowMs = 5200))
    }
}
```

**Step 2: Implementasi**

```kotlin
package com.gososmed.agent.capture

class ProjectionWatchdog(private val stallThresholdMs: Long = 3000L) {
    @Volatile private var lastFrameMs: Long = 0L

    fun onFrame(nowMs: Long) { lastFrameMs = nowMs }

    fun isStalled(nowMs: Long): Boolean =
        lastFrameMs > 0 && (nowMs - lastFrameMs) > stallThresholdMs
}
```

**Step 3: Pasang di CaptureService**

```kotlin
private val watchdog = ProjectionWatchdog(3000L)
private val watchdogTicker = object : Runnable {
    override fun run() {
        if (watchdog.isStalled(SystemClock.elapsedRealtime())) {
            Log.w("CaptureService", "Pipeline mandek >3s — memicu reconfigure")
            LocalWebServer.broadcastText("""{"type":"capture_error","reason":"stalled"}""")
            reconfigure()
            watchdog.onFrame(SystemClock.elapsedRealtime()) // reset agar tidak loop
        }
        handler.postDelayed(this, 1000L)
    }
}
```

Panggil `watchdog.onFrame(SystemClock.elapsedRealtime())` di `onOutputBufferAvailable`.

**Step 4: Verifikasi**

```bash
# Matikan layar HP saat streaming, tunggu 4 detik, lihat log
adb logcat -s CaptureService | grep "mandek"
```
Expected: muncul peringatan, lalu stream pulih setelah layar dinyalakan.

**Step 5: Commit**

```bash
git commit -am "feat(capture): add projection watchdog to detect and recover stalled pipeline"
```

---

### Task 6: Camera Source (Android 12+)

**Files:**
- Create: `app/src/main/java/com/gososmed/agent/capture/CameraCaptureSource.kt`
- Modify: `AndroidManifest.xml` (tambah `CAMERA` runtime request di MainActivity)

**Step 1: Implementasi sumber kamera**

```kotlin
package com.gososmed.agent.capture

import android.content.Context
import android.hardware.camera2.*
import android.media.MediaCodec
import android.os.Handler
import android.view.Surface

/**
 * Sumber frame dari kamera belakang/depan.
 * Memakai Camera2 langsung (pola DeskIn/iMyFone), bukan MediaProjection.
 */
class CameraCaptureSource(
    private val context: Context,
    private val handler: Handler
) {
    private var session: CameraCaptureSession? = null
    private var device: CameraDevice? = null

    fun start(lensFacing: Int, target: Surface, onError: (String) -> Unit) {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = mgr.cameraIdList.firstOrNull { id ->
            mgr.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == lensFacing
        } ?: run { onError("kamera tidak ditemukan"); return }

        try {
            mgr.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(dev: CameraDevice) {
                    device = dev
                    val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(target)
                    }.build()
                    dev.createCaptureSession(
                        listOf(target),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                s.setRepeatingRequest(req, null, handler)
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                onError("konfigurasi sesi kamera gagal")
                            }
                        },
                        handler
                    )
                }
                override fun onDisconnected(dev: CameraDevice) { dev.close() }
                override fun onError(dev: CameraDevice, err: Int) {
                    dev.close(); onError("camera error $err")
                }
            }, handler)
        } catch (e: SecurityException) {
            onError("izin kamera belum diberikan")
        }
    }

    fun stop() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        session = null; device = null
    }
}
```

**Step 2: Izin runtime di MainActivity**

```kotlin
private val cameraPermLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
) { granted ->
    if (granted) startCameraMirror()
    else toast("Izin kamera ditolak")
}

fun startCameraMirror() {
    if (Build.VERSION.SDK_INT < 31) { toast("Butuh Android 12+"); return }
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        != PackageManager.PERMISSION_GRANTED) {
        cameraPermLauncher.launch(Manifest.permission.CAMERA)
        return
    }
    CaptureService.instance?.startCameraSource(lensFacing = CameraCharacteristics.LENS_FACING_BACK)
}
```

**Step 3: Verifikasi di perangkat**

```bash
adb shell pm grant com.gososmed.agent android.permission.CAMERA
# Tekan tombol kamera di UI, lihat viewer
```
Expected: gambar kamera muncul di browser, bukan layar.

**Step 4: Commit**

```bash
git commit -am "feat(capture): add Camera2 mirroring source for Android 12+"
```

---

### Task 7: Virtual Display tambahan (Android 11+)

**Files:**
- Create: `app/src/main/java/com/gososmed/agent/capture/ExtraVirtualDisplay.kt`

**Step 1: Implementasi**

```kotlin
package com.gososmed.agent.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.view.Surface

/**
 * Membuat VirtualDisplay TAMBAHAN (bukan mirror layar utama).
 * Memakai flag PUBLIC agar aplikasi lain bisa dipindahkan ke display ini.
 *
 * CATATAN: token MediaProjection hanya boleh membuat SATU VirtualDisplay
 * di Android 14+. Karena itu kelas ini WAJIB dipakai sebagai pengganti,
 * bukan pendamping, VirtualDisplay utama.
 */
class ExtraVirtualDisplay(
    private val projection: MediaProjection,
    private val handler: Handler
) {
    private var display: VirtualDisplay? = null

    fun create(name: String, w: Int, h: Int, dpi: Int, surface: Surface): VirtualDisplay? {
        if (display != null) return display
        display = projection.createVirtualDisplay(
            name, w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            surface, null, handler
        )
        return display
    }

    fun release() {
        try { display?.release() } catch (_: Exception) {}
        display = null
    }
}
```

**Step 2: Verifikasi**

```bash
adb shell dumpsys display | grep -i "GoSosmed"
```
Expected: display baru muncul dengan nama yang diberikan.

**Step 3: Commit**

```bash
git commit -am "feat(capture): add extra virtual display creation (Android 11+)"
```

---

### Task 8: UI — pemilih mode capture

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/gososmed/agent/MainActivity.kt`

**Step 1: Tambah pemilih di kartu Remote Controller**

```xml
<RadioGroup android:id="@+id/rgCaptureSource"
    android:orientation="horizontal">
    <RadioButton android:id="@+id/rbScreen"  android:text="Layar"  android:checked="true"/>
    <RadioButton android:id="@+id/rbCamera"  android:text="Kamera"/>
    <RadioButton android:id="@+id/rbVirtual" android:text="Virtual"/>
</RadioGroup>
```

**Step 2: Wire ke CaptureService**

```kotlin
rgCaptureSource.setOnCheckedChangeListener { _, id ->
    when (id) {
        R.id.rbScreen  -> CaptureService.instance?.setSource(CaptureSource.SCREEN)
        R.id.rbCamera  -> startCameraMirror()
        R.id.rbVirtual -> CaptureService.instance?.setSource(CaptureSource.EXTRA_DISPLAY)
    }
}
```

**Step 3: Verifikasi UI di perangkat**

```bash
adb shell uiautomator dump /sdcard/ui.xml
adb exec-out cat /sdcard/ui.xml | grep rgCaptureSource
```
Expected: tiga radio button muncul, dan memilih salah satu mengubah sumber stream.

**Step 4: Commit**

```bash
git commit -am "feat(ui): add capture source selector (screen/camera/virtual)"
```

---

### Task 9: Dokumentasi pengguna

**Files:**
- Create: `docs/CAPTURE-MODES.md`

**Step 1: Tulis dokumentasi**

Isi minimal:
- Tabel perbandingan AVC/HEVC/AV1 (bandwidth vs kompatibilitas)
- Cara memakai mode kamera (izin + tombol)
- Cara memakai virtual display
- Troubleshooting: browser tidak mendukung HEVC → otomatis AVC

**Step 2: Commit**

```bash
git commit -am "docs: add capture modes guide for end users"
```

---

## Kriteria Selesai

| Kriteria | Cara Verifikasi |
|---|---|
| Unit test lulus | `gradle testDebugUnitTest` → 0 failures |
| Codec ternegosiasi di perangkat | `adb logcat -s CaptureService \| grep "Codec ternegosiasi"` |
| Fallback AVC berfungsi | Buka di Firefox, stream tetap jalan |
| Watchdog mendeteksi mandek | Matikan layar 4 dtk, log "mandek" muncul, stream pulih |
| Kamera tampil | Aktifkan mode kamera, gambar muncul di browser |
| Virtual display dibuat | `adb shell dumpsys display \| grep GoSosmed` |
| UI pemilih berfungsi | UIAutomator dump menunjukkan 3 radio button |
| Build release sukses | `gradle assembleRelease` |

---

## Risiko & Mitigasi

| Risiko | Kemungkinan | Mitigasi |
|---|---|---|
| HEVC tidak didukung perangkat | Tinggi | Fallback otomatis ke AVC (Task 2) |
| Browser tidak bisa decode HEVC | Sedang | Deteksi `isTypeSupported` + minta AVC (Task 4) |
| Kamera bentrok dengan app lain | Sedang | Tangani `onDisconnected`, laporkan ke user |
| Virtual display ditolak OEM | Sedang | Deteksi null, laporkan alasan |
| Watchdog memicu reconfigure berulang | Rendah | Reset counter setelah reconfigure |

---

## Urutan Pengerjaan yang Disarankan

1. **Task 1 → 2 → 3 → 4** (codec) — fondasi, tanpa UI
2. **Task 5** (watchdog) — meningkatkan keandalan yang sudah ada
3. **Task 6** (kamera) — fitur baru
4. **Task 7** (virtual display) — fitur baru
5. **Task 8** (UI) — menyatukan semuanya
6. **Task 9** (dokumentasi) — terakhir

Setiap task diakhiri commit, sehingga kegagalan bisa di-rollback per fitur.
