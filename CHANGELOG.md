# Changelog

Semua perubahan penting pada GoSosmed Agent tercatat di sini.
Format mengikuti [Keep a Changelog](https://keepachangelog.com/id/1.1.0/)
dan versi mengikuti [SemVer](https://semver.org/lang/id/).

> **Kanal rilis:** rilis ber-label `vX.Y.Z` = **stabil** (dua angka terakhir
> naik saat fitur/fix). Rilis ber-label `vX.Y.Z-dev.N` = **dev** (build
> berkelanjutan dari `main`, belum diuji luas). Semua build ditandai jelas
> di GitHub Releases; APK dari CI `main` selalu berstatus **dev**.

## [1.2.6-dev.1] - 2026-10-23

### Added
- **ORPV Feedback Layer**:
  - Registrasi otomatis snapshot `after` ke `SnapshotRegistry` setelah aksi settle (`actNode`/`actAndVerify`) agar rangkaian aksi (trajectory) berikutnya tidak ditolak `stale_snapshot` (Temuan #0).
  - Pemancaran field kontrak `verified = true/false` dan `action_performed = true/false` pada respons aksi (Temuan #1 & #2).
  - Penjagaan retry policy: `stale_snapshot` tanpa `snapshot_id` dikembalikan dengan `retryable = false` (Temuan #3); kegagalan `screen_not_changed` pada aksi submit (`is_submit = true`) dikembalikan dengan `retryable = false` (Temuan #4).
  - Registrasi `typeNotificationStateChanged` di konfigurasi aksesibilitas (`accessibility_service_config.xml`) dan pelacak Toast terfilter per package & class (`ToastTracker`) dengan window TTL 3500ms (Temuan #5, #7, ADR-01).
  - Penyeragaman kadens observasi: konstanta `DEFAULT_POLL_INTERVAL_MS = 100L`, pembatasan clamp `poll_interval_ms` ke rentang ketat `[50, 200]` ms, dan pemeriksaan pembatalan ganda sebelum delay (Temuan #12, ADR-06).
  - `nodes_digest` deterministik: hashing SHA-256 berbasis tuple semantik `(resourceId, className, text, bounds)` independen terhadap urutan traversal dan `node_id` (Temuan #14, ADR-05, §2.8).
  - Penghitungan `node_count` sebelum pemotongan (pre-cap) dan flag `truncated = node_count > MAX_NODES` (§2.10.j.3).
  - Kapasitas `SnapshotRegistry` dinaikkan dari 5 ke 8 slot LRU, ditambah penyimpanan terpisah `pin-on-failure` (maksimal 2 snapshot global) (§2.9.c).
- **Maestro Parity Primitives**:
  - **Spatial Filtering**: seleksi elemen 2D geometris relatif (`below`, `above`, `leftOf`, `rightOf`) berbasis jarak Euclidean terdekat.
  - **Native `scrollUntilVisible`**: pengguliran terkalibrasi in-memory hingga elemen masuk area aman viewport (15% - 85% layar).
  - **Stale A11y Cache Refresh**: mekanisme penyegaran cache internal aksesibilitas OS (`refreshAccessibilityCache`).

### Fixed — UI & Policy Hardening
- **Perbaikan Switcher Bahasa (i18n ID ↔ EN)**:
  - Menambahkan resource qualifier eksplisit `res/values-in/strings.xml` (dan `locales_config.xml`) agar sistem Android 13+ pada perangkat dengan default bahasa sistem Inggris (`en-US`) tidak lagi memaksa fallback ke `values-en/` ketika pengguna memilih Bahasa Indonesia.
  - Memperbaiki `MainActivity.attachBaseContext` dan sinkronisasi preference `commit()` sebelum `recreate()`.
- **Perbaikan Switcher Tema (Ember 2048 Dark ↔ Glacier Glass Light)**:
  - Mengatasi penolakan recreate pada activity yang memiliki `uiMode` di `android:configChanges` dengan secara eksplisit mengeset `delegate.localNightMode` dan memicu `recreate()`.
  - Memperbarui label emoji tombol dan persistensi sinkron `commit()`.
- **Penajaman Retry Policy `screen_not_changed`**:
  - Memastikan aksi klik navigasi biasa (`isSubmitAction = false`) tetap memiliki `retryable = true` saat menghadapi lag sementara, sementara aksi submit (`isSubmitAction = true`) tetap diproteksi ketat dengan `retryable = false` guna mencegah *double post*.
- **Anti-ANR & Eliminasi Freeze Main Looper (P0)**:
  - Menghapus pemanggilan `mainHandler.post` pada eksekusi command inbound di `AgentWsClient.kt`. Semua eksekusi command (termasuk `annotatedScreenshot`, `tap`, `clickAndWait`) dialihkan ke `scope.launch(Dispatchers.IO)` berpelindung `commandExecutionMutex`.
  - Mencegah deadlock dan pemblokiran Main Looper saat UI dashboard menguji/mengetuk tombol node secara beruntun (`[⚡ Test]`), sehingga popup sistem Android "Agent GoSosmed isn't responding (Wait / Close app)" tidak akan pernah terpicu.
  - Memperketat batas tunggu screenshot latch dari 8 detik ke 4 detik.

## [1.2.5] - 2026-10-23

> **Catatan penomoran: kenapa 1.2.2 langsung ke 1.2.4?**
>
> `1.2.3` tidak pernah terbit dan tidak pernah di-tag. Kodenya ada: commit
> `9704d78` menaikkan `versionName` ke `1.2.3-dev.1` (versionCode 37), tetapi
> tag `v1.2.3` / `v1.2.3-dev.1` tidak pernah dibuat — `git tag -l` dan GitHub
> Releases sama-sama tidak memuatnya, jadi CI tidak pernah membangun APK-nya.
> Nomor itu hanya hidup di CHANGELOG dan di `versionName` saat dikerjakan.
>
> Saat audit pra-rilis, versi dinaikkan langsung ke `1.2.4` (versionCode 38)
> karena isinya sudah mencakup seluruh pekerjaan `1.2.3-dev.1` ditambah
> perbaikan keamanan. Nomor `1.2.3` karena itu **dilewati**, bukan gagal:
> tidak ada APK `1.2.3` yang pernah ada. SemVer mengizinkan lompatan nomor;
> yang tidak boleh adalah memakai ulang nomor yang sudah terbit.
>
> Rilis yang benar-benar ada di GitHub: `v1.2.2` (36), `v1.2.4` (38),
> `v1.2.5` (39).

### Fixed — Anotasi Kacau Setelah `maxWidth`, Regresi Core Aksesibilitas

Rilis ini menutup regresi yang muncul di v1.2.4. Dua kelompok masalah, keduanya
ditemukan lewat audit menyeluruh dan divalidasi di perangkat fisik.

**1. Anotasi (Set-of-Marks) tergambar di luar gambar**

`takeScreenshotRawBytes` mendapat parameter baru `maxWidth: Int = 480` di
commit `c6fc187`. Hanya call site `annotatedScreenshot` yang tidak mengirimnya,
sehingga bitmap tangkapan menyusut `1220×2712 → 480×1067` sementara
`AnnotatedScreenshotHelper` tetap menggambar kotak memakai koordinat **layar
fisik**. Semua kotak di x > 480 jatuh di luar gambar — inilah tampilan "kacau"
yang dilaporkan.

`AnnotatedScreenshotHelper.kt` sendiri **tidak berubah sama sekali** antara
v1.2.2 dan v1.2.4 (`git diff --stat` kosong), jadi regresi datang dari
pemanggil, bukan dari renderer. Itu juga sebabnya downgrade ke v1.2.2 kembali
rapi: versi itu belum punya `maxWidth`.

- `CMD_ANNOTATED_SCREENSHOT` kini mengirim `maxWidth = 0` (tanpa batas).
- `annotate()` kini menurunkan skala koordinat sendiri dari elemen terjauh,
  sehingga pemanggil mana pun yang mengecilkan bitmap tetap mendapat kotak di
  dalam gambar (pertahanan berlapis).
- `elements` JSON **tidak** ikut diskalakan — tetap koordinat fisik karena
  dipakai untuk tap. Men-skala JSON akan membuat tap salah sasaran.

Divalidasi di Redmi Note 13 Pro 5G (Android 15):
`takeScreenshotRawBytes success: (1220x2712, s=1.0)`, 33 elemen, dan di
inspector dasbor `Nodes (32)` dengan kotak 1–32 sejajar.

**2. Dua regresi core dari pekerjaan remote controller**

- `accessibility_service_config.xml` melebar ke `typeAllMask` +
  `notificationTimeout=50`. `typeAllMask` (`0xFFFFFFFF`) berlangganan
  `TYPE_TOUCH_INTERACTION_*`, `TYPE_GESTURE_DETECTION_*`, `TYPE_VIEW_SCROLLED`,
  dan `TYPE_VIEW_TEXT_CHANGED` — tidak satu pun dipakai `onAccessibilityEvent`
  (handler hanya memindai dialog pairing, dibatasi 250 ms sekali). Kembali ke
  tiga tipe yang benar-benar dipakai + `notificationTimeout` 100 ms.
  Flag baru dari commit itu (`flagIncludeNotImportantViews`,
  `flagReportViewIds`) **dipertahankan** karena benar-benar membantu visibilitas
  node.
- `onUnbind()` menambah auto-revive yang menjalankan
  `settings put secure accessibility_enabled 1` tanpa syarat. Bila pengguna
  mematikan aksesibilitas dari Setelan, unbind berikutnya langsung
  menyalakannya lagi — sakelar terasa rusak. Kini dijaga flag `userDisabled`
  dan ketersediaan shell.

**Yang diaudit dan tidak berubah** (tidak perlu tindakan):
`healthSnapshot()` dan `capabilitiesJson()` nol diff terhadap v1.2.2, jadi
kontrak server dan permukaan MCP tool tetap. Permukaan command 28 → 35, murni
aditif. `CaptureService` tetap hanya start dari switch eksplisit + izin
MediaProjection, tanpa auto-start.

**Diverifikasi:** 209 unit test (termasuk 5 test regresi geometri anotasi),
`assembleDebug`, `assembleRelease`, `lintVitalRelease`, dan validasi perangkat
+ browser.

## [1.2.4] - 2026-10-23

### Fixed & Hardened — Pre-release Security Audit, HEVC/H.265 via WebCodecs, HTTPS :8766 Default

Audit menyeluruh sebelum promosi ke production menemukan tiga jalur di mana
aplikasi lain di HP dapat bertindak atas nama pengguna, satu kebocoran memori
tak terbatas, dan satu build release yang tidak bisa lolos lint. Semuanya
diperbaiki di rilis ini.

**Blocker keamanan:**

1. **MainActivity — kanal validasi tanpa guard release.** `MainActivity`
   exported (LAUNCHER + deep link `gososmed://`), dan
   `handleValidationIntent()` menjalankan `cmd=dump/tap/setText` untuk
   pemanggil mana pun:
   `adb shell am start -n com.gososmed.agent/.MainActivity --es cmd dump`.
   `AgentReceiver` sudah menolak ini di release; `MainActivity` tidak. Kini
   dijaga `BuildConfig.DEBUG`.
2. **AgentForegroundService — exported + tanpa autentikasi.** Menerima
   `ACTION_SET_PAIRING` (`ws_url` + `pairing_code`) dan `ACTION_STOP_WS` tanpa
   memeriksa pemanggil, sehingga aplikasi lain bisa mengalihkan agen ke
   WebSocket milik penyerang atau memutus sambungan sah. Kini
   `exported="false"`.
3. **AgentReceiver — exported di release.** `BuildConfig.DEBUG` memblokir saat
   runtime, tetapi komponennya tetap dapat dialamatkan. Kini manifest
   placeholder: `true` di debug (QA `adb shell am broadcast`), `false` di
   release.

**Kebocoran & kebersihan:**

4. **`allowBackup` tidak diset** sehingga Android memakai default `true`.
   `AdbKeyStore` menulis kunci privat ADB (PKCS#8) ke `filesDir`; kunci itu
   memberi hak `shell` (uid 2000). Kini `allowBackup=false` +
   `data_extraction_rules` yang mengecualikan semua domain untuk cloud backup
   dan device transfer.
5. **PIN sesi ditulis plaintext ke logcat** — dapat dibaca siapa pun dengan
   akses ADB. Kini hanya mencatat bahwa PIN dibuat.
6. **Kebocoran blob URL pada MSE.** `revokeObjectURL(vid.src)` dipanggil
   SETELAH `removeAttribute('src')`, sehingga menerima string kosong dan tidak
   me-revoke apa pun — satu blob bocor setiap MSE dibangun ulang (rotasi,
   ganti codec). URL kini diambil sebelum atribut dihapus. Urutan yang sama
   diperbaiki di `reset()`.
7. **Release build gagal `lintVitalRelease`**: `values-en` mendeklarasikan
   `telemetry_target_prefix` yang tidak ada di locale default dan tidak dipakai
   kode. Dihapus.

**HEVC/H.265 lewat WebCodecs (jalur :8766):**

8. **Negosiasi codec default ke HEVC.** `CaptureService.preferredCodec` kini
   `HEVC`; `CodecCapability.preferenceOrder` tetap `[AV1, HEVC, AVC]` dan AVC
   selalu fallback terakhir.
9. **String codec HEVC dibangun di server** (`HevcCodecString`) dari
   `profile_tier_level()` di VPS, menghasilkan format RFC 6381
   `hev1.<profile>.<compat>.<tier><level>`. Dipakai `hev1` (bukan `hvc1`)
   karena parameter set dikirim in-band di Annex-B tanpa box `hvcC`.
10. **Protokol config bertambah 1 byte**: `[3][w:2][h:2][codecId:1][param set]`.
    `codecId`: 1=AVC, 2=HEVC, 3=AV1. Paket lama tanpa id tetap dibaca sebagai
    AVC.
11. **Fallback berjenjang tanpa intervensi.** Tanpa WebCodecs (HTTP :8765) atau
    bila `configure()`/decode HEVC gagal, viewer mengirim `setCodec` → AVC
    sekali saja, server reconfigure, viewer pindah ke MSE.
12. **`onOutputFormatChanged` mengumpulkan semua `csd-N`** — sebagian encoder
    HEVC memakai `csd-0..csd-2`; sebelumnya hanya dua dibaca sehingga VPS atau
    PPS bisa hilang.
13. **`SpsFix` tidak lagi merusak HEVC.** Rewrite "no frame reordering" hanya
    untuk H.264; pada HEVC `nal[0] and 0x1f` bukan tipe NALU dan bisa merusak
    AUD (35).
14. **UI default ke HTTPS :8766.** Tombol "Buka di Browser" membuka
    `https://<ip>:8766`; banner menampilkan HTTPS sebagai URL utama dan HTTP
    :8765 sebagai fallback MSE.

**Bug yang ditemukan saat validasi:**

15. **`currentConfigKey` tanpa identitas codec** — permintaan HEVC→AVC hanya
    mengubah bitrate pada encoder lama dan viewer tetap menerima HEVC. Kunci
    kini menyertakan codec.
16. **`d is not defined` di `onConfigAvc`** — sisa refactor membuat setiap
    paket config AVC melempar `ReferenceError` sebagai unhandled promise,
    sehingga jalur WebCodecs AVC selalu gagal diam-diam dan jatuh ke MSE.
    `WC.csd` (dead state yang tidak boleh dipakai sebagai
    `VideoDecoder.description`) dihapus.
17. **Probe codec memakai MSE untuk HEVC** — HEVC tidak pernah lewat MSE di
    viewer ini (muxer fMP4 hanya membangun `avc1`/`avcC`), sehingga probe
    mengukur jalur yang salah dan bisa mematikan HEVC yang berfungsi. HEVC/AV1
    kini diprobe lewat `VideoDecoder` + `isSecureContext`.

**Diverifikasi di perangkat nyata** (Redmi Note 13 Pro 5G, Android 15,
`c2.qti.hevc.encoder`):

| Jalur | Engine | Codec string | Hasil |
|---|---|---|---|
| `https://192.168.1.125:8766` | WebCodecs HEVC | `hev1.1.00000060.L120.B0` | 720x1600, 88 fps saat scroll |
| `http://192.168.1.125:8765` | MSE fMP4 | turun ke AVC otomatis | `readyState=2`, video 720x1600 berjalan |

**Diukur di perangkat** (CPU ticks `/proc/<pid>/stat`, 3 run per kondisi):
HEVC 587–597 vs AVC 617–635 ticks/8s saat scroll (HEVC ~5% lebih ringan);
idle setara (67–72). Encoder video 0.0% CPU (DSP hardware). Biaya CPU
terbesar justru audio (`goso-audio` 3.5–7.1%); audio OFF menurunkan idle dari
~70 menjadi ~25 ticks/8s.

**Verifikasi statis APK** (`aapt2 dump xmltree`): release —
`AgentReceiver exported=false`, `AgentForegroundService exported=false`,
`allowBackup=false`; debug — `AgentReceiver exported=true` (kanal QA tetap).
`apksigner verify` lolos dengan keystore env; `lintVitalRelease` lolos;
204 unit test lulus.

**Yang belum diverifikasi:** perangkat non-Qualcomm (MediaTek/Exynos), browser
selain Chromium, dan perilaku runtime build release di hardware (MIUI/HyperOS
menolak instalasi APK saat audit berlangsung). HEVC hanya tersedia di secure
context, jadi Firefox via HTTP :8765 selalu memakai fallback AVC/MSE.

## [1.2.3-dev.1] - 2026-10-21

### Added & Enhanced — Pure JPEG Two-Message Wire Protocol, Isolated OBSERVE, LiveFrameSession & 1-in-Flight Flow Control

Rilis **DEV** `v1.2.3-dev.1` (versionCode 37) mengimplementasikan arsitektur streaming frame deterministik dan protokol dua pesan terpadu:

1. **Isolasi Eksekusi `OBSERVE` & ImageConfigNormalizer**:
   - Menghubungkan eksekusi `OBSERVE` ke `ImageConfigNormalizer` (validasi batas kualitas 30–95, `maxWidth` 480, penolakan tegas terhadap format usang `webp` dengan `unsupported_image_format`).
   - Mengembalikan `V2ExecutionResult` dengan `observeBinaryPayload` terisolasi tanpa membebani antrean live frames.
2. **Protokol Dua Pesan Berpasangan (Two-Message Wire Protocol)**:
   - **Pesan 1**: Teks WebSocket JSON bertipe `frame_meta` yang membawa metadata ukuran display (`width`, `height`), dimensi sumber asli (`source_width`, `source_height`, `source_scale`), kualitas, dan `byte_length`.
   - **Pesan 2**: Binary WebSocket murni 100% raw JPEG bytes (`0xFF, 0xD8, ...`) tanpa prefiks magic byte byte-header.
   - Feedback browser: `ack_frame` (`session_id`, `binary_message_id`) langsung diteruskan ke active session.
   - Bebas bocor VRAM browser: pemanggilan `bitmap.close()` segera setelah `ctx.drawImage()`.
3. **LiveFrameSession & Flow Control 1-in-Flight (`FrameFlow`)**:
   - Manajer sesi live streaming tunggal dengan proteksi `device_busy`.
   - Preflight frame capture sebelum mengembalikan respons sukses start.
   - Scheduler deterministik 1–2 FPS dengan kendali flow 1-in-flight, batas timeout unblock 2000 ms, dan kebijakan *drop-newest*.
4. **Bidirectional Interactive Browser Viewer & Navigasi Cepat**:
   - Menambahkan viewer HTML5 Canvas mandiri di `tools/browser-viewer/index.html` dengan auto-ACK, pemanggilan `bitmap.close()` segera setelah `ctx.drawImage()` (bebas bocor VRAM), dan telemetri FPS/RTT.
   - Menambahkan event listener sentuh kanvas: klik otomatis dikonversi ke `tap` pada koordinat asli HP, dan drag kursor otomatis dikonversi ke perintah `swipe`.
   - Menambahkan bilah navigasi hardware virtual: tombol **◀ Back**, **⌂ Home**, **▢ Recents**, **⚡ Wake**, serta bilah input pengetikan teks langsung.
5. **Command `swipe` Interaktif Terakselerasi Shell UID 2000**:
   - Menambahkan metode `swipe(x1, y1, x2, y2, durationMs)` pada `AgentAccessibilityService.kt` dan command `swipe` pada `AgentCommand.kt`.
   - Mengeksekusi gestur via `input swipe` berkecepatan tinggi saat transport ADB lokal (UID 2000) aktif, dengan fallback ke `dispatchGesture()` aksesibilitas.
6. **Verifikasi & Pembuktian Nyata (Device Evidence)**:
   - **Uji Unit JVM:** 100% lulus (161 unit tests passing, mencakup `AgentCommandTest`, `LiveFrameSessionTest`, `ImageConfigNormalizerTest`, `FrameFlowTest`, `ProtocolV2IntegrationTest`).
   - **Uji Perangkat Fisik Nyata (Gate Rilis LULUS):** Terverifikasi sukses langsung via ADB pada perangkat fisik **Redmi Note 13 Pro 5G (`2312DRA50G`) Android 15 (API 35)**. Eksekusi `ping`, `health`, `capabilities`, `tap`, `swipe`, dan Protocol v2 `OBSERVE` dengan screenshot terbukti bekerja 100% di perangkat fisik. Penolakan format usang `webp` terbukti aktif di runtime nyata.
7. **Fix: Mouse/Touch Remote Control — Discrete-Only Pipeline (vitRemote 1:1 Proven)**:
   - **Root cause**: Browser viewer sent BOTH `d:x,y` (ADB `input motionevent DOWN`) on `pointerdown` AND `t:x,y` (Accessibility `dispatchGesture` tap) on `pointerup` plus `u:x,y` (ADB `input motionevent UP`). Two competing touch injection mechanisms fired simultaneously, canceling each other — producing zero effective taps, clicks, and swipes.
   - **Fix**: Matched vitRemote's proven discrete-only pattern. Browser now sends ONLY one discrete command on `pointerup`: `t:` (tap, <450ms no-move), `l:` (long press, >450ms no-move), or `s:` (swipe, >8px move). No `d:`/`m:`/`u:` sent from browser.
   - Right-click → `a:back` (Android Back). Mouse wheel → `s:` vertical swipe (250ms debounce).
   - Coordinate normalization: letterbox-compensated `pos(e)` mapping to `0..10000` integer space, matching vitRemote's `pos()` exactly.
   - Android `RemoteControlManager.kt`: `t:`, `l:`, `s:` prefer ADB `input tap`/`input swipe` when PrivilegedShell (UID 2000) available, fallback to `AccessibilityService.dispatchGesture()`.
   - Added `longpress`, `a:` (action multiplexer), and single-char shortcut aliases (`t`, `l`, `s`, `a`) to command router.
8. **Cleanup: Removed Dead "Platform Terpasang" Telemetry String**:
   - Removed unreferenced `telemetry_target_prefix` ("📱 Platform Terpasang:\n") from `strings.xml`. No layout or code referenced this string — it was a leftover from removed fake platform checkmarks.
9. **Production Remote Browser Improvements (MASTER_BLUEPRINT_REMOTE_BROWSER_STABIL.md)**:
   - **Screen Rotation without Re-prompt**: Added `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode"` to `MainActivity` in `AndroidManifest.xml`. Registered `DisplayManager.DisplayListener` in `CaptureService.kt` to handle orientation changes internally via `reconfigure()` without recycling `MediaProjection` token. Removed service termination from `MainActivity.onDestroy()`.
   - **Internal Audio Streaming (Android 10+)**: Implemented `AudioPlaybackCapture` in `CaptureService.kt` capturing game/media sound, broadcast as binary type 5 (`MSG_AUDIO`) 44.1 kHz 16-bit PCM stereo via WebSocket. Implemented Web Audio API player with `#btnAudioToggle` in `index.html`.
   - **Client-Side Screen Recording**: Added `#btnRecordScreen` in `index.html` recording 60 FPS video directly in browser via HTML5 `MediaRecorder` from canvas/video with audio track mixing, saving `.webm` directly to user's PC with 0% CPU/storage load on the phone.
   - **Responsive Landscape CSS & Fullscreen PC**: Removed broken `flex-direction: row` on `.phone-frame.landscape`. Configured dynamic `aspect-ratio: var(--stream-aspect)` calculated from incoming frame dimensions. Added comprehensive `:fullscreen` styles giving edge-to-edge 100vw × 100vh display with auto-hiding floating softkeys.
   - **Game Keymapping & Physical Keyboard**: Added WASD + Action overlay (`#keymapOverlay`) with toggle button `#btnToggleKeymap`. Added global `keydown` keyboard listener in `index.html` to type directly into phone fields and handle special keys (Backspace, Enter, Tab, Space, Esc, Arrows) via `key:` and `text:` commands. Added `clipboard:` command to `RemoteControlManager.kt` and `#btnClipboardSync` to toolbar.
   - **Auto-HTTPS (WebCodecs 60 FPS on Wi-Fi)**: Created `Tls.kt` generating EC self-signed cert in `AndroidKeyStore`. Opened HTTPS port 8766 in `LocalWebServer.kt`. Added 1-click HTTPS upgrade banner in `index.html` to unlock secure context `VideoDecoder` hardware acceleration over LAN Wi-Fi.
10. **Fix: Dynamic System IP Resolution & Live Screen Display Pipeline**:
   - **Zero Hardcoded IPs**: Eliminated static `127.0.0.1` strings across `activity_main.xml`, `MainActivity.kt`, `index.html`, and `AgentCommand.kt`. Replaced with dynamic interface detection prioritizing `wlan0` and physical LAN adapters (`192.168.1.125`). In `index.html`, WebSocket URL now adapts dynamically to `window.location.host` and preserves user endpoint in `localStorage`.
   - **Screen Display Restoration**: Removed premature `URL.revokeObjectURL(vid.src)` inside MSE `sourceopen` that detached the MediaSource before buffers could play. Fixed WebCodecs chunk payload to prevent duplicate SPS/PPS headers. Added live JPEG fallback streaming (~3 FPS) in `LocalWebServer.kt` when `CaptureService` is in standby, transitioning seamlessly to 60 FPS H.264 video once granted.
   - **Unblocked Remote Controller Activation**: Removed blocking prerequisite requiring wireless ADB pairing before toggling screen capture. The remote controller switch now activates with AccessibilityService alone, using privileged shell acceleration whenever ADB is connected. Added `reuseAddress = true` on both HTTP and HTTPS server sockets to eliminate `BindException` on restarts.
11. **Production Parity (DeskIn & iMyFone MirrorTo Pattern) — Pointer, Overlay & Audio**:
   - **Audit DeskIn & iMyFone Strategy**: Audited `C:\Users\dedy\Documents\MCPAI\rea-py2many\imyfone` (`mouse.js`, `app.js`, `style.css`) and `deskin` (`REA_ANALYSIS_REPORT.md`, `ToDeskService`). Aligned cursor states, overlay pointer-events, coordinate normalization, and audio pipeline with production-grade standards.
   - **Dynamic Cursor & Pointer Feedback**: Added `.mouse-active` (`cursor: crosshair`), `.mouse-active:active` (`cursor: none`), and `.mouse-disabled` (`cursor: not-allowed; pointer-events: none`) in `style.css`. Added `.touch-ripple` visual feedback on click/tap mimicking native mobile touch rings. Wired `#btnToggleMouse` in `control.js` to toggle mouse interaction and cursor appearance dynamically.
   - **Physical Display Coordinate Alignment (Eliminating ~240px Vertical Error)**: Fixed `parseCoords` and `swipe` in `RemoteControlManager.kt` to use `getRealDisplaySize(svc)` (`wm.maximumWindowMetrics.bounds` on API 30+), matching `CaptureService.reconfigure()`. Previously, `svc.resources.displayMetrics` used inset bounds (2466px instead of 2712px), causing touch events to land ~240px too high.
   - **Audio Streaming Operationalization**: Granted runtime `android.permission.RECORD_AUDIO`. Added lazy initialization in `CaptureService.setAudioEnabled()` so enabling audio via toolbar or WebSocket automatically initializes `AudioPlaybackCapture` if not already running. Verified live streaming of 88 PCM audio packets (2049 bytes each, 44.1 kHz 16-bit stereo) in 4 seconds.
12. **Fix: Silent Audio Failure, Invisible Errors & Static Gamepad Overlay**:
   - **Audio Was Silently Dead — `RangeError: Byte offset is not aligned`**: The wire packet is `[opcode=5][PCM...]`, so `data.subarray(1)` yields an **odd** `byteOffset` (1). `new Int16Array(buf, 1, n)` throws `RangeError` on *every* packet and the surrounding `catch (_) {}` swallowed it — zero audio, zero logs. Verified red-green in-browser: OLD throws `RangeError: start offset of Int16Array should be a multiple of 2`; NEW decodes 1024 samples. Fix: reusable 0-aligned scratch buffer (`aScratchU8`/`aScratchI16`) with `set()` memcpy, no per-packet allocation.
   - **Gapless Audio Scheduler**: Replaced per-packet `source.start()` (overlapping every ~45 ms) with a monotonic playhead (`aPlayhead`) scheduled back-to-back, resyncing only when drift exceeds 350 ms. Buffers are built at the **device** sample rate (`audioCtx.sampleRate`, typically 48 kHz) rather than hardcoded 44.1 kHz, eliminating the implicit resampler. Stereo is properly deinterleaved into two planar channels instead of the previous mono downmix.
   - **Errors Are Now Visible in the UI**: Added `window.onerror`, `unhandledrejection`, and `console.error` passthrough that write red `log-line error` entries into the telemetry box, plus an `N err / N warn` badge that turns red on first error and a ✕ clear button. Log lines are buffered until the panel exists, capped at 500 entries, and timestamped with milliseconds. Verified: uncaught `Error`, `TypeError`, `Promise.reject`, and `console.error` all surface with full messages.
   - **Audio Auto-Start & First-Gesture Unlock**: `startAudio` is now sent on WebSocket connect and the engine unmutes by default. A one-shot `pointerdown`/`keydown`/`touchstart` capture listener resumes the suspended `AudioContext` (browser autoplay policy), so audio begins without hunting for the toggle.
   - **Audio Diagnostics**: New `AUDIO` metric card with a live level meter driven by decoded peak (`GoStream.onAudioLevel`), showing elapsed PCM seconds and percentage. A 4-second watchdog logs `Audio menunggu gesture browser` when packets are dropped and re-reports repeated decode errors.
   - **Gamepad Overlay Rebuilt as a Real Editor (DeskIn `presetkeyboard_*.json` pattern)**: Replaced the hardcoded WASD block with a dynamic overlay. Keys are absolutely positioned by **normalized 0..1 fractions**, so layouts survive rotation and window resize. Supports drag-to-move (`pointermove`), corner-handle resize (28–96 px), live inspector for label/keycode/size/opacity, add/delete key, add tap-button, reset to preset, and JSON export. Layout persists to `localStorage['goso_keymap_v2']`. Verified in-browser: 8 keys render, drag moved `18%,72% → 33.79%,57.68%`, slider resized to `76px`, add/delete 8→9→8, and `localStorage` persisted.
   - **Live Hardware Validation (Redmi Note 13 Pro 5G, Android 15)**: WebCodecs engine `avc1.428020` at `720x1600`, **30.2 FPS**, audio **peak 0.666 / RMS 0.135**, meter **82%**, `0 err / 0 warn`. Tap mapping verified at center/top/bottom (`5000,5000` / `5000,500` / `5000,9500`) and a bottom-center tap navigated Android Settings into `SubSettings`, proving the corrected physical-display scaling.
13. **Fix: Robotic Audio (Sample Loss) & Full Gamepad Configuration Menu**:
   - **Robotic Audio Root Cause — `Thread.sleep(45)` Throttled the Capture Loop Below Real-Time**: The audio thread read only `2048` bytes (512 stereo frames = **11.6 ms** of audio) then slept `45` ms. That is **22.4 reads/s × 11.6 ms = 0.248× real-time** — `AudioRecord`'s internal FIFO overflowed and **~75% of PCM samples were discarded**, producing the characteristic metallic/robotic stutter. `record.read()` is already blocking and real-time-paced, so the extra sleep was pure throttling.
     - **Fix**: removed the sleep, enlarged the buffer to `4096` bytes (1024 frames = 23.2 ms per packet), and break the loop on `read < 0` instead of spinning. Measured on device: **`packets=223 pps=44.6 frames/pkt=1024 ratio=1.036×`** (was `0.248×`).
     - **Isolated offline proof** (3 s window, no live stream): OLD cadence supplies `0.755 s → 0.252×` and the playhead falls **−2.24 s behind** the clock (starvation); NEW cadence supplies `3.111 s → 1.037×` and stays **+0.13 s ahead**.
   - **Jitter Buffer + Native-Rate Buffers**: `playPcmAudio` now keeps an `AUDIO_JITTER = 80 ms` lead on the playhead so ordinary network jitter cannot starve the graph, and buffers are built at the **source rate (44100 Hz)** letting Web Audio's own resampler convert to the device rate. The hand-rolled nearest-neighbour conversion was measured as harmless (996 Hz / RMS 0.707 vs native 0.706) but is unnecessary. Live continuity test: **buffered 234–280 ms, 0 underruns over 4 s**.
   - **Full Gamepad Configuration Menu (new sidebar ⚙️ button)**: Previously `🎮` only toggled a static WASD overlay with no way to configure it. Added a dedicated `#btnKeymapConfig` sidebar entry that opens a modal modelled on DeskIn's `presetkeyboard_deskin_*.json` (`GameKeyBoardData` + `GameKeyDataBean` with `x/y/width/height/scale/keyValue/pressType`) and iMyFone's virtual-keyboard editor:
     - **Preset tab**: 6 ready layouts — FPS/Shooter, MOBA, Racing, WASD Basic, Media/Nav, Kosong.
     - **Tombol tab**: key-chip list, add Key / add Tap / delete, and a live inspector for label, Android keycode, X, Y, size (28–96 px), and per-key opacity.
     - **Tampilan tab**: global overlay opacity, overlay scale (60–140%), haptic feedback toggle, show-labels toggle.
     - **Import/Export JSON** round-trip plus Reset Semua.
     - All state persists to `localStorage` (`goso_keymap_v2`, `goso_gamepad_style`).
     - Verified in-browser: modal opens (`display:flex`), 6 presets render, FPS preset loads **7 tombol** into the overlay, sliders set `left:40% / top:30% / width:80px / opacity:0.6`, label→`JUMP` and keycode→`23` persist and re-render, add/delete `7→8→7`, style tab applies `opacity 0.5` + `scale(1.2)`, and export/import round-trips `7 → 7` with a success log.
14. **Fix: WebCodecs Killed on Rotation/Fullscreen → Forced JPEG Fallback**:
   - **Root Cause Proven by Stack Trace (not inference)**: Rotating the phone or entering immersive fullscreen fires `DisplayManager.DisplayListener.onDisplayChanged` → `reconfigure()` → `oldDisplay.release()` → `createVirtualDisplay()`. On Android 14/15 a `MediaProjection` token is **single-use** for `createVirtualDisplay()`, so the second call throws:
     ```
     CaptureService: java.lang.SecurityException: Don't re-use the resultData to retrieve
       the same projection instance, and don't use a token that has timed out.
       at MediaProjection.createVirtualDisplay
       at CaptureService.reconfigure(CaptureService.kt:307)
     Caused by: MediaProjectionManagerService$MediaProjection.isValid
     CaptureService: java.lang.SecurityException: Cannot create VirtualDisplay with non-current MediaProjection
     ```
     The pipeline died, `CaptureService.instance` went null, and the viewer dropped to the JPEG fallback thread.
   - **Fix — Reuse the single VirtualDisplay (DeskIn/ToDesk `MediaScreenService` strategy)**: `reconfigure()` no longer calls `virtualDisplay.release()`. The display is created **once** per projection; subsequent reconfigurations only swap the encoder input surface via `virtualDisplay.setSurface(newSurface)` + `virtualDisplay.resize(tw, th, dpi)`. Only the `MediaCodec` and its old `Surface` are released.
   - **Stale-Encoder Buffer Starvation**: `onOutputBufferAvailable` used `if (mc !== codec) return` **without** `releaseOutputBuffer()`, starving the retired codec's pool during a swap. Now every buffer is always released, and identity is checked against a stable `@Volatile activeCodec` that is assigned **before** `start()` (so callbacks never observe `null` mid-reconfigure).
   - **Dead-Token Guard**: A `projectionDead` flag is set on `SecurityException` so the service stops retrying an invalid token, logs it, and pushes `{"type":"capture_error"}` to the viewer — which now shows "Izin tangkapan layar kedaluwarsa" and resets decoders instead of silently showing a frozen/JPEG frame.
   - **Viewer Engine Hardening (`stream.js`)**:
     - `VideoDecoder` in state `closed` is now torn down instead of being fed (previously every later frame was dropped).
     - `decode()` failures demote to MSE in the same call instead of being swallowed by `catch (_) {}`.
     - `configure()` tries `prefer-hardware`, then `prefer-software`, then MSE — a real 3-step chain, not a silent JPEG drop.
     - `configureMse()` is now a reusable function that tears down the previous `MediaSource`/`SourceBuffer` before rebuilding, so an encoder resolution change no longer freezes the picture.
     - `pumpSB()` handles `QuotaExceededError` by evicting played media and retrying, instead of clearing the queue permanently (the old code froze forever after rotation).
   - **On-Device Validation (Redmi Note 13 Pro 5G, Android 15)**: portrait→landscape→portrait plus `policy_control immersive.full=*`. `Reconfigure failures: 0`, `SecurityException(VirtualDisplay): 0`, encoder re-created cleanly (`c2.qti.avc.encoder id 999 → 1004` at **40.99 FPS**), 125 frames streamed post-rotation. 20-second browser stability run: `enginesSeen: ["WebCodecs"]`, `fellBackToJpeg: false`, **avg 47.6 FPS**, min 30, zero 0-FPS samples, `0 err / 0 warn` — and it survived a live mid-test rotation (`720x1600` → `1600x720`).
15. **Security Hardening: LAN Server Authentication & Public Repo Threat Model (`docs/SECURITY.md`)**:
   - **Per-Session 6-Digit PIN Gate**: `LocalWebServer.kt` now generates a fresh 6-digit PIN on start using `SecureRandom`. Every unauthenticated request to `/` is served a dark-themed PIN login page (`/unlock`). Correct PIN issues a 192-bit cryptographic session token via `SameSite=Strict; HttpOnly` cookie (`goso_auth`) valid for 12 hours. Wrong PINs are rejected with HTTP 401; 8 failed attempts trigger an HTTP 429 lockout.
   - **Activation Gating**: Removed unconditional `LocalWebServer.start()` from `MainActivity.onCreate()` and `onResume()`. The local server now runs **strictly when the Remote Controller switch is turned ON** and MediaProjection is granted, remaining completely silent otherwise.
   - **Wi-Fi Specific Binding**: Server sockets now bind exclusively to the resolved Wi-Fi network interface address (`wlan0`), rather than wildcard `0.0.0.0`, eliminating unintended exposure across mobile data, USB tether, or VPN adapters.
   - **Origin Validation & Client Limiting**: WebSocket upgrade now checks the `Origin` header against `boundIp`, `localhost`, and `127.0.0.1` to block DNS-rebinding attacks from malicious web pages. Maximum concurrent WebSocket viewers capped at 4 (`MAX_CLIENTS`).
   - **UI PIN Display**: `tvRemoteLocalUrl` now displays the active session PIN directly on the phone screen below the connection URLs. Moved `applyRemoteState(true)` in `MainActivity` to after `LocalWebServer.start()` so the generated PIN is immediately visible to the user.
   - **On-Device Proof**: Verified complete authentication lifecycle: unauthenticated access receives login page (200), wrong PIN rejected (401), correct PIN (`742452`) redirects (302) with token, authenticated URL loads full viewer (200), and WebSocket connects with `?t=token`.
16. **Fix: Gamepad Config Panel Obscured the Preview → Floating Draggable Panel**:
   - **Problem**: The configuration modal was a centered dialog with `backdrop-filter: blur(4px)` and a 82%-opaque overlay, covering the phone preview. Users could not see where the real buttons would land, making position mapping guesswork.
   - **No Blur, No Dimming**: `.gpc-backdrop` is now fully transparent (`background: transparent`, `backdrop-filter: none`) with `pointer-events: none`, so the live phone screen stays sharp and clickable through it.
   - **Floating, Draggable, Resizable Panel**: `.gpc-modal` became a `position: fixed` box docked top-left (empty area beside the phone), draggable by its header via pointer events, with `resize: both`, clamped to the viewport, and position/size persisted to `localStorage['goso_gpc_panel_pos']`.
   - **Collapse Toggle**: Added a ▾/▸ button so the panel shrinks to just its title bar, leaving the whole preview visible.
   - **Overlay Auto-Shown While Editing**: Opening the panel now force-enables the keymap overlay and enters edit mode, so the real key positions are visible while tuning them. The inline editor bar is suppressed while the panel is open (the panel supersedes it).
   - **Critical Mapping Bug Fixed**: Closing the panel previously left `keymapEditing = true`, so pressing a key selected it instead of dispatching it — mapping appeared broken. `closeGpc()` now calls `setEditing(false)`, clears selection, and re-renders, so keys fire immediately after saving.
   - **Validated in-browser**: `backdropFilter: none`, `backgroundColor: rgba(0,0,0,0)`, `pointerEvents: none`; panel `position: fixed` at `left 16 / top 72`, drag moved it `+700px` horizontally and persisted; collapse hides tabs but keeps the header; overlay shows 8 keys while editing; and after Save the log confirms `Keymap KEY: W (keycode 51)` — the mapping actually dispatches.

## [1.2.2] - 2026-10-16

### Fixed & Enhanced — Onboarding Permission Unblocking, Dual-Action Buttons, SettingsActivity, Bottom/Menu ⋮ Variant & OEM Guides

Rilis **STABIL** `v1.2.2` (versionCode 36) menuntaskan penyempurnaan menyeluruh pada alur perizinan awal dan onboarding perangkat BYOD Android 13/14:

1. **Dual-Action Buttons & Panduan Visual Terbuka di Langkah 1**:
   - Menghadirkan tombol langsung **"Buka Info App (Menu ⋮)"** berdampingan dengan **"Buka Aksesibilitas"** saat layanan belum aktif pada Android 13+.
   - Menambahkan kotak panduan visual di dalam kartu yang memandu 4 tahapan membuka blokir Setelan Dibatasi (*Restricted Settings*).
   - Menyertakan panduan varian lokasi tombol unblock: baik melalui menu titik tiga (⋮) di atas maupun gulir ke paling bawah layar Info Aplikasi (*Restricted settings / default disable*).
2. **Interaktivitas Entri Layanan Aksesibilitas (`accessibility_service_config.xml`)**:
   - Menambahkan `android:settingsActivity="com.gososmed.agent.MainActivity"` dan `android:summary="@string/accessibility_service_summary"`.
   - Entri "Agent GoSosmed" di menu Pengaturan Aksesibilitas Android kini dapat diketuk langsung untuk membuka aplikasi (`singleTask`).
   - Melengkapi string lokalisasi bahasa Indonesia (`values/strings.xml`) dan bahasa Inggris (`values-en/strings.xml`).
3. **Deteksi Multi-Level & Telemetri AppOps**:
   - Menggabungkan refleksi `checkOpNoThrow(119, ...)` dan fallback string app-op `android:access_restricted_settings` untuk mendeteksi status `MODE_ERRORED`.
   - Telemetri `a11y_blocked` dipublikasikan ke `healthSnapshot()` dan `capabilitiesJson()`.
4. **Panduan Opsi Pengembang & Debug Nirkabel Multi-OEM (`docs/SETUP-3-LANGKAH.md`)**:
   - Panduan terperinci aktivasi Opsi Pengembang (ketuk 7×) dan letak menu sistem untuk Xiaomi/HyperOS, Samsung One UI, OPPO/Realme ColorOS, Vivo Funtouch OS, dan Google Pixel/AOSP.
5. **Verifikasi Perangkat Nyata (Gate Rilis §2 LULUS)**:
   - **Uji Perangkat Fisik:** Terverifikasi sukses pada perangkat fisik Android 13/14 oleh pengguna ("ok sudah saya tes dan berfungsi"). Alur pembukaan blokir Setelan Dibatasi via Info Aplikasi berhasil tembus dan Aksesibilitas aktif normal.
   - **Uji Unit JVM:** `testDebugUnitTest` 100% lulus. Kompilasi `assembleDebug` sukses.

## [1.2.2-dev.2] - 2026-10-16
### Enhanced — Dual-Action Onboarding UX, Direct "Buka Info App (Menu ⋮)" Button, Toast Reminder & Reflection AppOps

Rilis **DEV** `v1.2.2-dev.2` (versionCode 35) merombak alur UI Setup Langkah 1 agar ramah pengguna (*user-friendly*) dan tidak lagi bergantung pada deteksi vendor yang kerap ambigu:

1. **Dual-Action Buttons di Langkah 1**:
   - Menampilkan tombol langsung **"Buka Info App (Menu ⋮)"** berdampingan dengan **"Buka Aksesibilitas"** pada Android 13+ saat Aksesibilitas belum aktif.
   - Pengguna tidak lagi terjebak hanya memiliki tombol "Aktifkan" yang langsung terbentur dialog Restricted Settings di sistem.
2. **Notice Box Panduan Visual Terbuka**:
   - Menyematkan kotak panduan visual di dalam kartu Langkah 1 yang menguraikan 4 tahapan membuka kunci *Restricted Settings* sebelum pengguna menekan tombol apa pun.
3. **Toast Reminder Sebelum Pindah Layar**:
   - Menampilkan Toast pengingat saat pengguna mengetuk "Buka Aksesibilitas" di Android 13+: *"Bila sakelar abu-abu, kembali dan ketuk tombol 'Buka Info App (Menu ⋮)'"*.
4. **Enhanced AppOps Reflection Check**:
   - Menambahkan pemanggilan refleksi `checkOpNoThrow(119, ...)` (OP 119 di Android 13/14) di samping fallback string op untuk deteksi status `MODE_ERRORED` yang lebih andal.

## [1.2.2-dev.1] - 2026-10-16
### Fixed & Enhanced — Onboarding Permission Unblocking, Restricted Settings Detection, SettingsActivity & Multi-OEM Developer Guides

Rilis **DEV** `v1.2.2-dev.1` (versionCode 34) mengatasi kendala pengguna saat mengaktifkan izin pada Android 13/14 dan mempermudah onboarding perangkat BYOD:

1. **[CELAH-1] Interaktivitas Entri Layanan Aksesibilitas (`accessibility_service_config.xml`, `strings.xml`)**:
   - Menambahkan konfigurasi `android:settingsActivity="com.gososmed.agent.MainActivity"` dan `android:summary="@string/accessibility_service_summary"`.
   - Entri "Agent GoSosmed" pada daftar Aksesibilitas sistem kini dapat diketuk untuk membuka langsung aplikasi tanpa kebingungan.
   - Melengkapi string lokalisasi bahasa Indonesia (`values/strings.xml`) dan bahasa Inggris (`values-en/strings.xml`).
2. **[CELAH-2] Deteksi Tiga Keadaan & Panduan Restricted Settings (`AgentAccessibilityService`, `MainActivity`)**:
   - Menambahkan fungsi pembacaan AppOps `isRestrictedBlocked(ctx)` untuk memeriksa apakah app-op `android:access_restricted_settings` berada dalam status `MODE_ERRORED` (Android 13+).
   - Memperbarui kartu Langkah 1 di tab Setup dengan 3 keadaan jujur: `AKTIF ✓`, `DIBLOKIR ANDROID ⚠️`, dan `BELUM AKTIF`.
   - Saat terblokir, tombol menampilkan **"Cara Mengizinkan"** yang membuka dialog panduan terperinci, lalu mengarahkan langsung (*deep-link*) ke `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` (`package:$packageName`).
   - Panduan UI memberikan peringatan keras bahwa menu titik tiga (⋮) di Info Aplikasi hanya dapat dibuka satu kali oleh sistem sebelum hilang permanen.
   - Telemetri `a11y_blocked` ditambahkan ke snapshot kesehatan (`healthSnapshot`) dan kapabilitas (`capabilitiesJson`).
3. **[CELAH-3] Panduan Komprehensif Opsi Pengembang & Debug Nirkabel per OEM (`docs/SETUP-3-LANGKAH.md`)**:
   - Menambahkan panduan spesifik ketuk 7× Nomor Build dan lokasi menu Opsi Pengembang untuk Xiaomi/HyperOS, Samsung One UI, OPPO/Realme ColorOS, Vivo Funtouch OS, dan Google Pixel/AOSP.
   - Menambahkan sub-bagian panduan pembebasan Setelan Dibatasi (Restricted Settings) dan penambahan solusi pada tabel kendala.
4. **Status Gate Rilis & Batasan yang Jujur**:
   - **Verifikasi JVM:** `testDebugUnitTest` (termasuk verifikasi health snapshot `a11y_blocked`) lulus 100%. Kompilasi `assembleDebug` sukses.
   - **Kanal Rilis:** Ditetapkan sebagai kanal **DEV (`v1.2.2-dev.1`)** sesuai §2 Gate Rilis karena belum tersambung ke perangkat fisik nyata di sesi pengujian ini. Verifikasi alur di HP nyata dijadwalkan sebelum promosi rilis stabil.

## [1.2.1] - 2026-10-12
### Fixed & Hardened — `annotatedScreenshot` Stabilization, Zero-Leak Mutable Decoding, Strict TLS & App Signature Attestation

Rilis **STABIL** `v1.2.1` (versionCode 33) menuntaskan stabilisasi visual Set-of-Marks dan pengerasan keamanan enterprise:

1. **Stabilisasi `annotatedScreenshot` (C1, C2, C3 Resolved)**:
   - Menangkap dan mengekspos Android `errorCode` (1–5) pada `takeScreenshotBase64` dan `takeScreenshotRawBytes` via `RawScreenshotResult`.
   - Mengeliminasi 6 titik kegagalan senyap dan menggantinya dengan pesan diagnostik spesifik serta logging `Log.e("GoAgent", ...)`.
   - Membungkus blok `CMD_ANNOTATED_SCREENSHOT` dalam `try { ... } catch (t: Throwable) { ... }` dengan pembersihan `root?.recycle()` dan `bmp?.recycle()` di dalam `finally`.
2. **Optimalisasi Memori (Pemangkasan Alokasi Ganda 12,6 MB)**:
   - Menerapkan `BitmapFactory.Options().apply { inMutable = true }` saat dekode byte array gambar, dan deteksi `if (bitmap.isMutable)` pada `AnnotatedScreenshotHelper`.
   - Menghilangkan proses kloning bitmap ganda, memangkas beban puncak alokasi memori hingga ~50% dan mencegah risiko OOM di HyperOS/MIUI.
3. **Pengerasan Keamanan Enterprise (Zero-Trust BYOD)**:
   - **App Signature Attestation**: Menambahkan SHA-256 fingerprint certificate extractor (`getSigningCertificateFingerprint`) pada payload `register` WebSocket untuk validasi integritas build resmi di backend.
   - **Strict Modern TLS**: OkHttpClient ditegakkan menggunakan `ConnectionSpec.MODERN_TLS` dan `ConnectionSpec.RESTRICTED_TLS` (TLS 1.2/1.3 only).
   - **Network Security Config Anti-MITM**: Membatasi trust-anchors ke `<certificates src="system" />` pada domain `bamsbung.id` untuk menolak sertifikat proxy pihak ketiga (Charles/Burp).
4. **Verifikasi Perangkat Nyata (Xiaomi HyperOS Android 14 API 34)**:
   - Uji matriks 20× lintas konteks (Home, transisi Back/Home, Chrome, YouTube, burst 4×) lulus **100% (20/20 sukses)** dengan rata-rata latensi ~370ms.
   - PSS memori stabil di 154 MB (zero memory leak terbukti via `dumpsys meminfo`).

## [1.2.0] - 2026-10-09

### Added — Enterprise Trust Center, Credential Redaction, Sub-Region ROI Vision, Compound Actions & Scoped Storage Scrubbing

Rilis **STABIL** `v1.2.0` (versionCode 32) menghadirkan penyempurnaan menyeluruh pada privasi, keamanan enterprise, dan efisiensi automasi:

1. **Trust & Legal Center (`MainActivity`, `activity_main.xml`)**:
   - Menambahkan menu dialog resmi Pusat Kepercayaan & Legalitas di header aplikasi: About, Kebijakan Privasi (Anti-Spyware), Ketentuan Layanan (ToS), dan tautan verifikasi integritas rilis GitHub.
2. **Pencegahan Kebocoran Kredensial (Anti-Keylogger)**:
   - Seluruh kolom sandi Android (`node.isPassword`) otomatis diredaksi menjadi `[REDACTED]` baik pada XML hierarki (`HierarchySerializer`) maupun metadata visual (`AnnotatedScreenshotHelper`).
3. **Compound Action `clickAndWait` (`AgentAccessibilityService`, `AgentCommand`)**:
   - Mengetuk elemen target dan langsung menunggu transisi UI selesai dalam satu siklus memori HP, memangkas 50% putaran giliran komunikasi LLM.
4. **Region of Interest (ROI) Sub-Region Cropping (`AnnotatedScreenshotHelper`)**:
   - Mendukung parameter `roi` untuk memotong dan memperbesar area detail layar (seperti CAPTCHA/puzzle) dengan efisiensi token vision hingga ~85 token, dengan tetap mempertahankan koordinat fisik layar asli HP.
5. **Smart IME Submit pada Input Teks (`replaceText`)**:
   - Menambahkan opsi `submit: true` untuk mengeksekusi aksi Enter/Search secara otomatis setelah input teks selesai.
6. **Native Scoped Storage Cleanup (`MediaStager`, `cleanupMedia`)**:
   - Pembersihan berkas media sementara secara legal via `ContentResolver.delete()` dan `MediaScannerConnection` tanpa menyisakan berkas hantu di galeri.
7. **Layar Redup Otomasi Global (`globalDim`)**:
   - Switch baru di bawah Wireless ADB untuk mengunci kecerahan sistem Android ke 1% secara global saat automasi berjalan semalaman.

## [1.1.0] - 2026-10-08

### Added — Extreme Stability, Sticky 3D Dock, Sub-10ms Native Performance & Production Release (Stabil)

Rilis **STABIL** `v1.1.0` (versionCode 31) menghadirkan arsitektur performa tinggi remote control sub-10ms, perombakan desain UI modern bertema Nexus 2048 / Glacier Glass, navigasi 4-menu sticky dock, dan stabilitas 24/7:

1. **Auto-Start on Boot (`BootReceiver`)**:
   - Menambahkan receiver `android.intent.action.BOOT_COMPLETED` dan `QUICKBOOT_POWERON` yang otomatis menjalankan `AgentForegroundService` seketika HP dinyalakan/reboot, memulihkan koneksi WebSocket dan ADB nirkabel tanpa sentuhan manual.

2. **Server Mode / OLED Black Screen Saver (`MainActivity`)**:
   - Menambahkan toggle "Mode Server (Tetap Menyala & Redup)" dengan `FLAG_KEEP_SCREEN_ON` dan kecerahan minimum (`0.01`).
   - Menyediakan overlay hitam pekat OLED dengan jam dan status minimal untuk mencegah lockscreen / `FLAG_SECURE` Android memblokir otomatisasi Aksesibilitas, menjaga suhu HP tetap dingin dan hemat baterai.
   - Ketuk layar 2× untuk kembali ke antarmuka normal.

3. **Zombie Socket Detection (TCP Half-Open) (`AgentWsClient`)**:
   - Heartbeat dinamis 10 detik dengan pelacak missed pong berurutan.
   - Jika 2 ping berturut-turut tidak menerima respons pong (`missedPongs >= 2`), soket di-reset paksa via `ws?.cancel()` dan koneksi ulang dipicu seketika dalam tempo ~3 detik.

4. **Live Device Health Telemetry Card (`DeviceTelemetryHelper`, `activity_main.xml`)**:
   - Menampilkan persentase baterai live, status pengisian daya, dan peringatan jika charger terlepas.
   - Indikator suhu baterai (°C) dengan visual warning jika suhu melebihi 40°C.
   - Info SSID Wi-Fi aktif dan latensi ping round-trip server (ms).
   - Deteksi otomatis 5 platform target terpasang: TikTok, Instagram, Facebook, Threads, dan YouTube.

5. **One-Click Echo Test & QR Scanner Pairing (`MainActivity`)**:
   - Tombol "Tes Koneksi & Ukur Latensi" satu-klik dengan umpan balik getaran haptic dan konfirmasi waktu round-trip.
   - Tombol "Scan QR" di samping kode 8 karakter untuk memindai kode pairing dasbor secara otomatis.

6. **Pintasan Optimasi Khusus Pabrikan HP (`OemOptimizationHelper`)**:
   - Deteksi cerdas pabrikan perangkat (Xiaomi/HyperOS, Samsung, Oppo/Realme, Vivo/iQOO) dengan tombol pintasan 1-klik ke menu perizinan latar belakang dan autostart OEM terkait.

7. **Sistem 2-Tema (Ember 2048 & Glacier Glass) + i18n Dwibahasa (`strings.xml`)**:
   - Dukungan mode Gelap (Ember 2048 Cyberpunk) dan Terang (Glacier Glass) berbasis token `app.css`.
   - Dukungan bahasa ganda Bahasa Indonesia (`values/strings.xml`) dan English (`values-en/strings.xml`).

8. **Sticky 3D Dock Bottom Navigation (`activity_main.xml`, `ic_3d_*`)**:
   - Memindahkan menu navigasi Beranda, Setup, dan Log ke dermaga sticky bawah dengan simbol 3D isometrik (Home, Setup, Log).
   - Panel log diringkas menjadi 30 baris dengan teks ringkas `miror layar ke server` dan `Terkoneksi (ping)`.

## [1.0.0] - 2026-10-05

### Added — Deterministic Android Portal & Protocol Version 2 Hardening (Stabil)

Rilis **STABIL** `v1.0.0` (versionCode 30) meresmikan arsitektur Deterministic Android Portal dan Protocol Version 2 dengan seluruh penguatan runtime, anti-bloat, dan regression suite lengkap:

1. **Active In-Flight Target Cancellation (`CommandCancellationRegistry`, `AgentCommand`)**:
   - Mendukung pembatalan target spesifik via `target_request_id` pada command `cancelAction`.
   - Mengembalikan `{"cancelled": true}` jika request target aktif ditemukan di memori, dan `{"cancelled": false}` jika target tidak aktif atau tidak ditemukan.
   - Loop aksi dan polling transisi (`waitForNode`, `waitForScreen`, `actNode`, `actAndVerify`) secara aktif memeriksa `isCancelled(requestId)` pada setiap iterasi dan abort seketika dengan reason code `cancelled`.

2. **Binary Debug Frame Pipeline & Zero ACK Leak (`DebugFrameEncoder`, `AgentWsClient`)**:
   - Bounded queue memvalidasi kapasitas minimum 1 (`maxCapacity.coerceAtLeast(1)`), mencegah alokasi antrean nol atau negatif.
   - Mekanisme `evictExpiredAcks(timeoutMs = 10_000L)` membersihkan ACK kadaluwarsa secara berkala di background loop, mencegah kebocoran memori pada koneksi lambat.
   - Aliran biner WebSocket mentransmisikan frame langsung dari memori (`webSocket.send(bytes.toByteString())`) tanpa menyentuh disk.
   - Pembersihan otomatis (`clear()`) dan pembatalan job saat koneksi ditutup atau terputus.

3. **Password Redaction & Privacy Hardening (`ActionableNodeRegistry`, `WindowRootCollector`)**:
   - Redaksi menyeluruh: node dengan `isPassword == true` secara konsisten mengubah `text` dan `contentDescription` menjadi `[REDACTED]`, baik pada level traversal window, kalkulasi SHA-256 node ID, maupun payload JSON snapshot.

4. **Envelope Validation & Duplicate Protection (`ProtocolV2`, `AgentCommand`)**:
   - Validasi ketat Protocol v2 menolak `request_id` kosong/blank dengan reason code `action_rejected`.
   - Deteksi duplicate request in-flight mencegah race condition dan menolak request kedua dengan reason code `device_busy`.
   - Deadline dinormalisasi: nilai non-positif (`<= 0`) secara aman didefault ke 10 detik, dan nilai ekstrem dilindungi dari overflow `Long.MAX_VALUE`.
   - Kompatibilitas penuh Protocol v1 dipertahankan untuk backward compatibility dasbor.

5. **Comprehensive Regression Unit Tests (135 Tests / 18 Suites Passing 100%)**:
   - Menambahkan unit test komprehensif pada `ProtocolV2Test`, `CommandCancellationRegistryTest`, `SnapshotRegistryTest`, `MutationGuardTest`, `DebugFrameEncoderTest`, `ActionableNodeRegistryTest`, dan `DeterministicResolverTest`.
   - 100% lulus tanpa kegagalan (0 failures, 0 errors, 0 skipped).

6. **Real-Device Acceptance Matrix (19/19 Vektor Terverifikasi di Redmi Note 13 Pro 5G `2c76b8a3`)**:
   - **Vektor 1 (Observe Settings)**: Menangkap display 1220x2466 @ 480dpi, package `com.android.settings`, 25 nodes, hash SHA-256 valid.
   - **Vektor 2 (Password Redaction)**: Node password diredaksi menjadi `[REDACTED]` (`password: true`), zero plaintext leak.
   - **Vektor 3 (Resolve Exact)**: Resolusi deterministik node tunggal dengan tautan clickable ancestor yang benar.
   - **Vektor 4 (Ambiguous Selector)**: Kandidat teks identik ganda dideteksi dan ditolak dengan `ambiguous` tanpa klik blind.
   - **Vektor 5 (Stale Snapshot)**: Permintaan dengan snapshot ID yang tidak terdaftar ditolak seketika dengan `stale_snapshot`.
   - **Vektor 6 (Wrong Package)**: Aksi ditolak dengan `wrong_package` jika foreground package tidak sesuai prakondisi.
   - **Vektor 7 (Native Click)**: Klik native `performAction` berhasil tanpa gesture fallback (`used_gesture_fallback: false`), layar berganti.
   - **Vektor 8 (Gesture Fallback)**: Resolusi fallback koordinat titik (`point`) berhasil memetakan view container.
   - **Vektor 9 (Set Text Unicode)**: Injeksi teks Unicode (`"GoSosmed 🚀 测试 123"`) via `ACTION_SET_TEXT` terbaca kembali secara verbatim.
   - **Vektor 10 (Wait Success)**: Elemen terdeteksi sebelum deadline dan dilaporkan berhasil (`found: true`).
   - **Vektor 11 (Wait Timeout)**: Elemen fiktif dilaporkan timeout secara jujur setelah batas waktu habis (`found: false`).
   - **Vektor 12 (Cancellation Aktif)**: Target operasi tunggu in-flight (deadline 15s) dibatalkan via `cancelAction` dalam 769ms (`reason_code: "cancelled"`).
   - **Vektor 13 (Submit Barrier)**: Replay aksi mutasi dengan `operation_id` sama tetapi `request_id` berbeda ditolak dengan `submit_barrier`.
   - **Vektor 14 (Debug Frame Start)**: Producer coroutine frame streaming aktif memproduksi frame biner di memori dengan statistik antrean.
   - **Vektor 15 (Stop Frames)**: Streaming frame dihentikan bersih, antrean frame dibersihkan (`status: "stopped"`).
   - **Vektor 16 (Orientation Gate)**: Perubahan orientasi layar memvalidasi penolakan snapshot pra-rotasi sebagai stale.
   - **Vektor 17 (Keyboard / Dialog)**: Jendela dialog sistem dan notification shade OS (`com.android.systemui`) terbaca tanpa crash.
   - **Vektor 18 (Process Restart & Reconnect)**: Pemulihan proses pasca `am force-stop` via Launcher monkey berhasil menyambungkan kembali layanan aksesibilitas (`ok: true`).
   - **Vektor 19 (Repetition 100x)**: 100 siklus observasi loop selesai dalam 71.05s (rata-rata 710.5ms/siklus) dengan kestabilan memori PSS 144MB $\to$ 151MB.

7. **Artifact Output & Limitations**:
   - Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (18,786,616 bytes / 17.92 MB, `versionCode = 30`, `versionName = "1.0.0"`).
   - Limitations: Integrasi otomatisasi end-to-end pada 5 platform sosial (Threads, TikTok, Instagram, YouTube, Facebook) bergantung pada backend DEV remote yang dijalankan terkontrol di fase deployment berikutnya.
## [0.9.11-dev.1] — 2026-10-05

### Added — Deterministic Android Portal & Protocol Version 2 (Real-Device Validated)

Rilis **DEV** mengimplementasikan arsitektur Deterministic Android Portal dan Protocol Version 2 sesuai spesifikasi `docs/PLAN-DETERMINISTIC-ANDROID-PORTAL.md`, dengan penutupan lengkap seluruh 7 Quality Gates hasil audit:

1. **Hierarchy Bloat Guard (`WindowRootCollector`)**:
   - Traversal pohon window dibatasi kuota `NodeBudget(MAX_NODES = 2000)` dan kedalaman `MAX_DEPTH = 16`.
   - Rekursi anak dihentikan seketika bila kuota habis atau batas kedalaman tercapai, mencegah OOM dan StackOverflow pada layout kompleks (WebView / nested RecyclerView).

2. **Active SnapshotRegistry & Stale Snapshot Guard (`SnapshotRegistry`)**:
   - Cache in-memory berstruktur LRU (kapasitas 5 snapshot, TTL 30 detik) mencatat snapshot layar aktif.
   - Command `observe` otomatis mendaftarkan snapshot ke registry.
   - `resolve`: mendukung pencarian node berbasis `snapshot_id` teregistrasi bila array `nodes` tidak dikirim; snapshot usang ditolak dengan `stale_snapshot`.
   - `actNode` & `actAndVerify`: parameter `snapshot_id` diwajibkan; snapshot tidak terdaftar atau kadaluarsa ditolak seketika dengan `stale_snapshot`. Integritas state divalidasi via perbandingan `treeHash` sebelum aksi dieksekusi (`expectedTreeHash`).

3. **Non-blocking Coroutine Delays (`AgentCommand`, `ScreenTransitionVerifier`)**:
   - `executeV2` dijadikan suspend function; `waitForNode` dan `waitForScreen` bermigrasi dari blocking `Thread.sleep` ke `kotlinx.coroutines.delay(pollIntervalMs)`.
   - Pemeriksaan `CommandCancellationRegistry.isCancelled(requestId)` dilakukan pada setiap iterasi loop sehingga permintaan pembatalan (`cancelAction`) merespons seketika.
   - Verifikasi transisi settle pada `actNode` dan `actAndVerify` didelegasikan ke `ScreenTransitionVerifier.verifyTransitionAsync`.

4. **Durable Submit Barrier & Idempotency Key (`MutationGuard`)**:
   - Menambahkan parameter `operationId` dan penyimpanan in-memory tabel `submittedOperations` ber-TTL 10 menit.
   - Aksi mutasi dengan `is_submit = true`, `operation_id`, atau `idempotency_key` yang diulang (meskipun menggunakan `requestId` baru) diblokir seketika dengan reason code kanonikal `submit_barrier`.

5. **Recurring Debug Frame Streaming (`AgentWsClient`)**:
   - Background producer coroutine `debugStreamingJob` memproduksi snapshot frame berkala sesuai `fps` (1..10), `quality` (10..100), dan `scale` (0.2..1.0).
   - Frame dikompresi murni di memori (zero disk write), diantrekan ke `BoundedFrameQueue`, dan dikirimkan sebagai binary WebSocket message (`toByteString()`).
   - Otomatis dibatalkan seketika saat `stopDebugFrames` atau penutupan WebSocket.

6. **Real-Device Protocol v2 Validation Matrix (10 Vektor Uji)**:
   - Terverifikasi 100% pada Redmi Note 13 Pro 5G (`2c76b8a3`), Android 14 (HyperOS, API 34):
     - **Vektor 1 (Fresh `observe`)**: Menangkap snapshot layar riil `com.android.settings`, 34 nodes, display 1220x2466 @ 480dpi, tree hash sha256 valid.
     - **Vektor 2 (`stale_snapshot`)**: Aksi dengan `snapshot_id` fiktif/usang ditolak dengan `ok: false, reason_code: "stale_snapshot"`.
     - **Vektor 3 (`wrong_package`)**: Validasi precondition menolak aksi saat foreground adalah Settings tetapi `expected_package: "com.facebook.katana"`.
     - **Vektor 4 (`ambiguous`)**: Resolusi selektor `TextView` menghasilkan 15 kandidat dengan skor seri 50, ditolak dengan `reason_code: "ambiguous"` (zero blind tap).
     - **Vektor 5 (`actNode` live)**: Eksekusi klik pada `"Additional settings"`, delegasi ke clickable ancestor, dan verifikasi transisi sukses (`ok: true`).
     - **Vektor 6 (`actAndVerify` live)**: Eksekusi klik pada `"Date and time"` dan verifikasi perubahan layar sukses dengan bukti `before_snapshot_id` & `after_snapshot_id`.
     - **Vektor 7 (`waitForNode` timeout)**: Polling elemen fiktif berakhir jujur dengan `reason_code: "timeout"` setelah 1551 ms.
     - **Vektor 8 (`cancelAction`)**: Perintah pembatalan aksi diakui (`ok: true, cancelled: false`).
     - **Vektor 9 (`submit_barrier`)**: Retry mutasi ber-`operation_id: "op-durable-001"` dengan `requestId` baru diblokir seketika dengan `reason_code: "submit_barrier"`.
     - **Vektor 10 (`startDebugFrames` & `stopDebugFrames`)**: Siklus hidup streaming frame debug binary berjalan bersih (`started` -> `stopped`).

7. **Test Suite Completeness**:
   - 86 unit tests JVM lulus 100% tanpa regresi.

## [0.9.10] — 2026-09-29

### Fixed — Stabilisasi Deteksi Langkah 1 & 2, Perbaikan Auto-Update Fetcher & SemVer, Anti-ANR Background Commands

Rilis **STABIL** melanjutkan urutan rilis pasca v0.9.9 dengan audit mendalam dan perbaikan menyeluruh:

1. **Auto-Update & GitHub Releases Fetcher Tangguh**:
   - `MainActivity.checkUpdateNow()` kini menyertakan header resmi `User-Agent: GoSosmedAgent/<version>` agar tidak ditolak atau dibatasi GitHub REST API.
   - Endpoint query beralih ke `/releases?per_page=5` (dengan fallback `/releases/latest`) sehingga rilis dapat dideteksi dengan benar dan andal.
   - Menggunakan instance HTTP client bersama (`updateHttpClient`) dengan timeout koneksi/baca (10s/15s), menghindari pemborosan koneksi.
   - Memperbaiki `AgentUpdateState.compareVersions` agar SemVer dievaluasi penuh: versi stabil diakui lebih baru dari prerelease (`0.9.10` > `0.9.10-dev.1`), dan prerelease berurutan dibandingkan dengan benar (`dev.2` > `dev.1`).

2. **Stabilisasi Deteksi Langkah 1 (Aksesibilitas)**:
   - Menambahkan event callback `AgentAccessibilityService.onStateChanged` pada siklus hidup service (`onServiceConnected`, `onUnbind`, `onDestroy`) dibungkus try-catch sehingga perubahan status langsung terkirim ke `MainActivity` seketika.
   - Mendaftarkan `AccessibilityStateChangeListener` di `MainActivity` agar toggle aktivasi di layar Setelan OS memicu pembaruan UI secara instan saat kembali ke aplikasi.
   - Memperbaiki `apiListed`: memeriksa `info.id` (menggunakan parsing `ComponentName`) selain `resolveInfo`, serta menambahkan fallback query `FEEDBACK_GENERIC` jika `FEEDBACK_ALL_MASK` terhalang filter ROM OEM.
   - Memperbaiki `secureListed`: parsing token dengan `ComponentName.unflattenFromString()` dan trimming spasi/titik untuk mendukung variasi format string di Xiaomi, Samsung, Oppo, Vivo.
   - Menjadwalkan sinkronisasi multi-interval (+300ms, +800ms, +1500ms) saat `onResume()` dan saat pergantian tab (`showPanel`) untuk mengantisipasi jeda asinkron binder service Android.

3. **Stabilisasi Deteksi Langkah 2 (Overlay / Pengecualian BAL)**:
   - `AgentOverlay.canDraw()` kini memeriksa `view != null` (langsung lolos bila window aktif terpasang) dan fallback langsung ke `AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW` (`unsafeCheckOpNoThrow`/`checkOpNoThrow`) untuk mengatasi jeda cache `Settings.canDrawOverlays(ctx)` pada Android 11+.
   - Menjadwalkan refresh beruntun pasca kembali dari layar izin overlay.
   - Menjadikan `AgentOverlay.main` lazy (`by lazy`) untuk mencegah kegagalan JVM unit test terkait unmocked Looper.

4. **Optimasi Kinerja Otomasi & Anti-ANR**:
   - Menambahkan kategori `ASYNC_BACKGROUND_COMMANDS` (`startApp`, `killApp`, `wake`) di `AgentCommand`: dieksekusi di coroutine latar belakang (`scope.launch`), tidak memblokir main looper UI. Operasi tunggu foreground (sampai 4s) dan shell exec tidak lagi membekukan tampilan aplikasi atau memicu dialog ANR sistem.
   - Menambahkan `capabilities` ke `SERVICE_FREE_COMMANDS` dengan dukungan snapshot fallback `AgentAccessibilityService.capabilitiesSnapshot()`, sehingga server preflight tetap mendapatkan status kapabilitas nyata (ADB, baterai, overlay, dsb.) meski service aksesibilitas sedang dalam proses restart oleh sistem.

5. **Perbaikan CI Release Workflow**:
   - `.github/workflows/release.yml`: menghapus action usang `android-actions/setup-android@v3` dan menggantinya dengan SDK bawaan `ubuntu-latest` + penerimaan lisensi via `sdkmanager` (konsisten dengan perbaikan pada `build.yml` di commit e511656).

6. **Dokumentasi Toolchain Lokal & Perbaikan README**:
   - `docs/TOOLCHAIN-LOKAL.md`: peta toolchain build APK lokal (JDK 17, Gradle 8.9, Android SDK 34 preinstalled).
   - README "Cara 2 — Gradle lokal" diperbaiki dari `./gradlew` (wrapper tidak ada) menjadi `gradle` lokal.

### Bukti Verifikasi
- Unit test JVM: 33 tes lolos (100% hijau).
- Build APK release & debug berhasil dikompilasi secara lokal.
- Siap diuji langsung di perangkat fisik pengguna.
## [0.9.9] — 2026-09-14

### Fixed — pairing ADB stabil: notification-only, mDNS auto-port, auto-fetch kode, overlay dihapus

Rilis **STABIL** setelah serangkaian iterasi dev (`v0.9.9-dev.1` s/d `v0.9.9-dev.3`)
dan verifikasi langsung di perangkat fisik:

1. **Overlay pairing DIHAPUS TOTAL**
   - Menghilangkan potensi jendela yatim/tidak bisa ditutup di berbagai OEM.
   - Mengikuti pola produksi AppManager: foreground service + notifikasi RemoteInput + mDNS.

2. **mDNS Auto-Port Tangguh**
   - Menggunakan `NsdManager` langsung dengan `WifiManager.MulticastLock` (izin `CHANGE_WIFI_MULTICAST_STATE`).
   - Dilengkapi mekanisme retry, penanganan satu resolve aktif, dan probe loopback untuk atribuasi port lokal.

3. **RemoteInput Notifikasi Handal**
   - State machine terpadu (`SEARCHING` -> `INPUT` -> `WORKING` -> `RESULT`).
   - Tidak melakukan rebuild notifikasi selama input berlangsung agar teks ketikan tidak hilang.
   - Tombol buka setelan menggunakan `PendingIntent.getActivity` langsung untuk melewati limitasi BAL.

4. **Auto-Fetch Kode Pairing via Accessibility (Best-effort)**
   - Membaca port & kode secara otomatis dari dialog Setelan tanpa perlu input manual jika layar terbuka.
   - Parsing per-window untuk mencegah tertukarnya port connect dengan port pairing.

### Bukti pengujian perangkat (Gate Rilis Stabil)

Telah diuji langsung di perangkat fisik:
- Aksesibilitas terhubung dan stabil.
- ADB Wireless self-pairing terhubung (`127.0.0.1`) dengan auto-port via mDNS.
- Notifikasi RemoteInput berfungsi dan pairing berhasil.
- Tidak ada overlay mengganggu.

## [0.9.8] — 2026-09-14

### Fixed — PAIRING AKHIRNYA BERFUNGSI: Conscrypt versi sendiri

Laporan lapangan setelah v0.9.7: overlay dan notifikasi **sudah muncul** (kedua
perbaikan UI bekerja), tetapi saat kode 6 angka dimasukkan:

```
✗ Gagal: adb_pair_failed: java.lang.NoSuchMethodException:
  com.android.org.conscrypt.Conscrypt.exportKeyingMaterial
  [class javax.net.ssl.SSLSocket, class java.lang.String, class [B, int]
```

#### Akar masalah

Penelusuran ke sumber `libadb-android`
(`libadb/src/main/java/io/github/muntashirakon/adb/PairingConnectionCtx.java:157-179`):

```java
if (SslUtils.isCustomConscrypt()) {
    conscryptClass = Class.forName("org.conscrypt.Conscrypt");
} else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
    throw new SSLException("TLSv1.3 isn't supported on your platform. ...");
} else {
    conscryptClass = Class.forName("com.android.org.conscrypt.Conscrypt");
}
Method exportKeyingMaterial = conscryptClass.getMethod(
        "exportKeyingMaterial", SSLSocket.class, String.class, byte[].class, int.class);
```

`com.android.org.conscrypt.Conscrypt.exportKeyingMaterial` adalah **API
tersembunyi** (`@UnsupportedAppUsage`) yang tidak dapat direfleksikan oleh
aplikasi dengan `targetSdk 34`. Karena itu `getMethod(...)` melempar
`NoSuchMethodException` — dan pairing mati **sebelum** sempat mengirim kode apa
pun ke `adbd`. Pesan "kode salah" tidak pernah benar di sini.

`SslUtils.getSslContext()` memilih jalur Conscrypt platform karena
`Class.forName("org.conscrypt.OpenSSLProvider")` gagal — kita memang tidak
membawa Conscrypt sendiri.

#### Akar kesalahannya: satu kalimat di komentar kita

`app/build.gradle.kts` sebelumnya berbunyi *"libadb sudah membawa
bcprov-jdk15to18 dan spake2-android sebagai dependensi runtime, **jadi
TLS/pairing tidak perlu ditambah manual**"*. Itu asumsi yang salah. README
`libadb-android` bagian *Adding Dependencies* mensyaratkan **salah satu** dari:

- `org.lsposed.hiddenapibypass:hiddenapibypass:6.1` — menembus API tersembunyi
  (trik rapuh, bisa patah di Android berikutnya), **atau**
- `org.conscrypt:conscrypt-android:2.5.3` — *"the recommended choice"* menurut
  README.

Kita tidak punya keduanya.

#### Perbaikan

Ditambahkan `org.conscrypt:conscrypt-android:2.5.3` (versi terbaru di Maven
Central, sama dengan yang direkomendasikan README). Dipilih jalur Conscrypt
sendiri, bukan bypass API tersembunyi — tanpa trik rapuh, dan tidak akan patah
saat Google memperketat kebijakan API lagi.

Efeknya: `SslUtils.getSslContext()` berhasil memuat
`org.conscrypt.OpenSSLProvider`, menandai `customConscrypt = true`, sehingga
`PairingConnectionCtx` memakai `org.conscrypt.Conscrypt` — API **publik** milik
library yang kita bawa — alih-alih Conscrypt platform yang tersembunyi.

#### Catatan

APK bertambah besar karena artefak ini membawa `.so` native untuk setiap ABI.
Itu konsekuensi yang diterima: pairing yang berfungsi jauh lebih penting
daripada APK yang ramping.

## [0.9.7] — 2026-09-14

### Fixed — v0.9.6 masih gagal di HP: overlay & pengisian kode tidak muncul, Langkah 1 mati

Laporan setelah v0.9.6 dipasang di HP: *"masih tidak bisa konek wireless debug,
tidak menampilkan overlay atau pengisian kode untuk pairing"* dan *"ketika saya
klik hubungkan langkah 3, langkah 1 mati / status belum aktif, padahal di
Setelan aksesibilitas sudah ON"*.

Akar masalah ditemukan dengan membandingkan kode kita terhadap **implementasi
produksi AppManager** (`io.github.muntashirakon.AppManager.adb.AdbPairingService`)
— aplikasi dari penulis `libadb-android`, library yang kita pakai juga.

#### 1. Overlay TIDAK PERNAH BISA muncul tanpa izin SYSTEM_ALERT_WINDOW

v0.9.4–v0.9.6 memasang satu-satunya overlay dengan `TYPE_APPLICATION_OVERLAY`.
Jendela jenis itu menuntut izin "Tampilkan di atas aplikasi lain" **dan** saklar
OEM MIUI/HyperOS yang terpisah. Bila salah satu belum aktif, kode langsung
`return` tanpa memberi tahu pengguna — kegagalan senyap, dan pengguna melihat
persis "tidak ada overlay apa pun".

**Fix:** overlay kini dipasang lewat `TYPE_ACCESSIBILITY_OVERLAY` oleh
`AgentAccessibilityService`. Jendela jenis ini **tidak memerlukan izin apa pun**
dan tidak terkena penjagaan pop-up latar belakang OEM, sebab jendelanya milik
layanan sistem, bukan "aplikasi yang menggambar di atas aplikasi lain".
`TYPE_APPLICATION_OVERLAY` tetap tersedia sebagai jalur cadangan, dan notifikasi
RemoteInput tetap jalur ketiga yang selalu ada.

#### 2. Kode dari notifikasi tidak pernah sampai ke service

v0.9.5/v0.9.6 mengirim hasil RemoteInput lewat `PendingIntent.getBroadcast()`
dan `BroadcastReceiver` yang didaftarkan dinamis dengan `RECEIVER_EXPORTED`.
Rantai itu punya banyak titik gagal senyap.

**Fix (mengikuti AppManager):** aksi notifikasi kini memakai
`PendingIntent.getForegroundService()` yang menunjuk ke `AdbPairingService`
sendiri dengan flag `FLAG_MUTABLE` (wajib — SystemUI menuliskan hasil ketikan ke
Intent itu), dan hasilnya dibaca di `onStartCommand()` lewat
`RemoteInput.getResultsFromIntent(intent)`. `BroadcastReceiver` dinamis dihapus
seluruhnya. `startForeground()` dipanggil lebih dulu di cabang itu, karena
PendingIntent bertipe foreground service menuntutnya dalam 5 detik.

#### 3. Klik "Hubungkan" mematikan Langkah 1 (Langkah 1 "mati")

`AdbPairingService`, `AgentForegroundService`, `AgentAccessibilityService` dan
`MainActivity` berada di **satu proses**. Satu exception yang lolos dari
`onStartCommand()` — mis. dari `buildNotification()` atau pembuatan view, yang
di v0.9.6 **tidak** dibungkus try/catch — mematikan seluruh proses, dan
`AccessibilityService` ikut mati bersamanya. Itulah sebab keluhan ini muncul
persis saat tombol Langkah 3 ditekan.

**Fix berlapis:**
- Seluruh `onCreate`/`onStartCommand` dan pembuatan notifikasi/kartu dibungkus
  try/catch. Jalur pairing tidak boleh bisa menjatuhkan proses.
- `AgentApp` (Application baru) memasang penangkap exception terakhir yang
  menuliskan sebab crash ke disk; `MainActivity` menampilkannya di tab Log pada
  peluncuran berikutnya. Kegagalan tidak lagi hilang bersama prosesnya.

#### 4. Status Langkah 1 dibaca dari API resmi, bukan dari flag memori

v0.9.6 menyimpulkan status dari flag `bound` + penguraian string
`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. String itu berbeda format
antar-OEM, dan flag `bound` hilang begitu OEM me-restart proses agent.

**Fix:** `AgentAccessibilityService.osEnabled()` memakai API resmi
`AccessibilityManager.getEnabledAccessibilityServiceList()` (mengembalikan
`ComponentName` yang sudah terurai — tidak ada penguraian string), dengan
`Settings.Secure` sebagai cadangan. Aturannya "salah satu menyebut aktif =
AKTIF", sebab menampilkan tombol "Aktifkan" untuk layanan yang sudah aktif
jauh lebih merugikan daripada kebalikannya. `isEnabled()` dan
`capabilitiesJson().a11y_enabled` keduanya memakai jalur baru ini.

#### 5. Kartu tidak bisa digeser (drag) — cacat yang belum pernah terlaporkan

`setOnTouchListener` dipasang pada LinearLayout kartu, padahal
`ViewGroup.dispatchTouchEvent` menyerahkan event ke anak yang menjadi sasaran
sentuhan. Akibatnya listener induk tidak pernah dipanggil saat jari menyentuh
EditText/tombol — praktis seluruh permukaan kartu.

**Fix:** listener dipasang pada TextView judul (view daun), sehingga geser
berfungsi dari baris judul tanpa menelan sentuhan EditText dan tombol.

#### Catatan
Kartu pairing dipindahkan ke berkas sendiri (`PairingOverlay.kt`) supaya satu
implementasi dipakai oleh kedua jenis jendela. `AdbPairingService` juga
mendapat batas umur sesi 10 menit (sama seperti AppManager) agar layanan tidak
menggantung selamanya bila pengguna meninggalkannya.

## [0.9.6] — 2026-09-13

### Fixed — Pairing ADB ala Shizuku + Stabilitas Langkah 1 (regresi v0.9.4/v0.9.5)

Perbaikan mendalam atas 3 keluhan lapangan + 1 regresi UX yang paling mengganggu.

#### 1. Overlay pairing TIDAK muncul → AKAR TEKNIS DITEMUKAN
- **`AdbPairingService` didaftarkan TANPA `android:foregroundServiceType`.** Dengan
  `targetSdk 34`, memanggil `startForeground()` pada service tanpa tipe yang
  dideklarasikan melempar `MissingForegroundServiceTypeException` — service MATI
  sebelum overlay maupun notifikasi sempat dibuat. Jadi bukan overlay-nya yang
  ditolak; service-nya crash lebih dulu. Kini dideklarasikan
  `foregroundServiceType="specialUse"` + property subtype.
- `startForeground()` dibungkus `promoteToForeground()` (overload bertipe API 34 →
  fallback overload lama), dan kegagalan FGS kini memberi pesan yang benar, bukan
  diam lalu mati senyap.
- Overlay dipasang **sinkron** pada main thread (v0.9.5 memakai `main.post{}` yang
  tiba setelah fokus OS sudah berpindah ke Setelan). Ditambah verifikasi
  `isAttachedToWindow` agar penolakan sistem tercatat di log.
- Deteksi izin OEM MIUI/HyperOS ("Tampilkan jendela sembulan saat berjalan di latar
  belakang") yang terpisah dari `Settings.canDrawOverlays()`.
- Bug tombol: `updateOverlayStatus()` memakai `findViewById<Button>(View.NO_ID)`
  yang selalu null → tombol "Hubungkan Sekarang" tak pernah aktif kembali setelah
  gagal. Kini dicari lewat tag.

#### 2. Kode pairing berubah saat pindah ke app GoSosmed → jalur Notifikasi RemoteInput
- Notifikasi interaktif (**jalan utama**, seperti Shizuku) diperbaiki total:
  `RECEIVER_EXPORTED` (v0.9.5 memakai `NOT_EXPORTED`, sehingga RemoteInput dari
  SystemUI DITOLAK senyap di Android 13+/14 — notifikasi terlihat tapi tak berfungsi),
  `BigTextStyle` berisi instruksi, aksi "Buka Debug Nirkabel" & "Batal", channel
  `IMPORTANCE_HIGH` + `VISIBILITY_PUBLIC`.
- Ditangani balapan: kode bisa diketik sebelum mDNS menemukan port. Kini menunggu
  hingga 6 detik, dan bila port tetap tak ada pesannya BENAR ("port belum
  terdeteksi"), bukan menyesatkan ("kode salah").
- `MainActivity` menampilkan dialog 3 langkah lebih dulu; service dinyalakan lalu
  navigasi ke Setelan diberi jeda 250 ms agar notifikasi benar-benar terpasang.
- Prasyarat izin `POST_NOTIFICATIONS` dicek & diminta lebih dulu — tanpa itu jalur
  utama tidak akan terlihat.

#### 3. "IP tidak 127.0.0.1" → klaim v0.9.4 berbasis premis SALAH
- **Fakta dari sumber library (libadb-android 3.1.1, `AndroidUtils.java:39-57`):**
  `AndroidUtils.getHostIpAddress()` mengembalikan `InetAddress.getLoopbackAddress()`
  = `127.0.0.1`, dan pada EMULATOR `10.0.2.2`. Ia TIDAK PERNAH mengembalikan IP Wi-Fi.
  Jadi "Dynamic IP Adapter" v0.9.4 tidak hanya tidak berguna — pada emulator ia
  MERUSAK pairing.
- **Pairing = SELF-PAIRING** (HP memasangkan dirinya dengan `adbd` di HP yang sama),
  sehingga host pairing WAJIB loopback. `AdbLocalShell` & `AdbPairingService` kini
  memakai konstanta eksplisit `LOOPBACK_HOST = "127.0.0.1"`; import `AndroidUtils`
  dihapus dari keduanya.
- Yang sebenarnya dibutuhkan pengguna adalah **PORT**, dan itu sudah dideteksi
  otomatis via mDNS `_adb-tls-pairing._tcp`. Untuk CONNECT setelah pairing, libadb
  memakai mDNS `_adb-tls-connect._tcp` dan **mengabaikan** `setHostAddress(...)`
  sepenuhnya. IP Wi-Fi hanya relevan pada jalur cadangan manual
  (`AdbPairingController.connectTo(host, port)`), yang tetap utuh.

#### 4. REGRESI: "Langkah 1 disuruh diaktifkan ulang setiap pindah tab"
- **Akar masalah:** `MainActivity.refreshPerms()` memakai
  `AgentAccessibilityService.instance?.isServiceReady()`, sedangkan
  `isServiceReady()` = `rootInActiveWindow != null`. Nilai itu SEMENTARA null saat
  berpindah activity, layar terkunci, atau app target `FLAG_SECURE` — kondisi NORMAL,
  bukan tanda layanan mati. Diperparah `onDestroy()` yang meng-null-kan `instance`
  (OEM agresif seperti MIUI rutin me-restart layanan accessibility).
- **Perbaikan:** dipisahkan tegas antara "layanan ter-BIND" dan "window siap dibaca".
  Ditambahkan `AgentAccessibilityService.bound` (di-set di `onServiceConnected`,
  di-reset di `onUnbind`/`onDestroy`) dan `isEnabled()` sebagai sumber kebenaran
  untuk status IZIN, diperkuat `reconcileFromSettings()` yang membaca
  `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. Kesiapan live
  (`isServiceReady()`) tetap dipakai internal oleh perintah dump/tap.
- Laporan `capabilities` kini memisahkan `a11y_enabled` (bind) dari `a11y_ready`
  (window live) — dua hal berbeda yang selama ini tertukar.

#### 5. Perbaikan build: error kompilasi yang menjatuhkan CI (commit 8ccce13)
- `AdbPairingService.codeReceiver` adalah `object : BroadcastReceiver()`; di dalam
  blok `main.post { ... }` pada cabang "port belum terdeteksi", pemanggilan
  `Toast.makeText(this, ...)` membuat `this` menunjuk ke **BroadcastReceiver**
  (bukan `Context`), sehingga Kotlin gagal mengompilasi:
  `None of the following candidates is applicable` + `Unresolved reference 'show'`.
  Ini persis penyebab `build-apk` (step *Unit tests*) dan `release`
  (step *Build release APK*) GAGAL pada commit `8ccce131`.
- Diperbaiki menjadi `this@AdbPairingService` (qualified `this`). Pemanggilan
  `Toast.makeText` lain di berkas yang sama sudah benar karena berada di dalam
  member function `Service` (bukan di dalam objek anonim).
- Diverifikasi lokal: `gradle testDebugUnitTest assembleDebug` → **BUILD SUCCESSFUL**.

### Notes
- `MainActivity` (Langkah 3) tetap membaca status ADB apa adanya; perubahan ini tidak
  menyentuh kontrak wire/command server.

## [0.9.5] — 2026-09-13

### Fixed & Enhanced — Foreground Pairing Service & Dual Input (Overlay + Notification)
Mengatasi masalah overlay tidak muncul di HyperOS / MIUI / Android 11+:
1. **`AdbPairingService` (Foreground Service):** Mengangkat konteks overlay ke tingkat Service sistem
   sehingga tidak di-kill atau ditahan oleh OS saat Activity kehilangan fokus saat membuka Pengaturan.
2. **Notification RemoteInput (Metode Shizuku Resmi):** Notifikasi prioritas tinggi dengan tombol
   "Ketik Kode Pairing" langsung di status bar Android! Pengguna bisa mengetik 6 digit dari tirai notifikasi
   tanpa perlu overlay jika perangkat membatasi float view.
3. **Draggable Floating Card:** Overlay window kini bisa digeser (touch & drag) jika menutupi angka pop-up sistem.

## [0.9.4] — 2026-09-13

### Added — Floating Overlay Window & Dynamic IP for Seamless ADB Pairing
Solusi tuntas untuk masalah Wireless Debugging Android 11+:
1. **Floating Window Overlay (`AdbPairingOverlay`):** Saat tombol "Hubungkan" ditekan,
   jendela input melayang di atas layar Pengaturan Wireless Debugging dan otomatis membuka
   menu Opsi Pengembang/Debug nirkabel. Pop-up kode 6-digit Android **TIDAK AKAN TERTUTUP**
   dan kodenya tidak berubah-ubah lagi!
2. **mDNS Auto-Discovery Listener:** Otomatis mendeteksi port pairing acak dari sistem via `_adb-tls-pairing._tcp`
   sehingga pengguna tidak perlu mengetik port secara manual jika mDNS aktif.
3. **Dynamic IP Adapter:** Tidak lagi memakai hardcoded `127.0.0.1`, melainkan membaca antarmuka
   Wi-Fi lokal (`AndroidUtils.getHostIpAddress`) sesuai yang ditampilkan pada layar Debug nirkabel.

## [0.9.3] — 2026-09-13

### Fixed — ANR pada tombol "Putuskan" (lanjutan temuan v0.9.2)
Menutup satu-satunya jalur yang masih memanggil metode library ber-kunci dari
**main thread**.

`disconnectNow()` memanggil `disconnect()` pada library, yang meminta
`synchronized (mLock)` — kunci yang bisa sedang ditahan penyambungan latar
sampai ~11 detik. Metode ini dipanggil dari main thread (tombol **Putuskan** di
tab Setup, dan `AdbPairingController.forget()`), sehingga menekan tombol itu
saat penyambungan berjalan bisa membekukan UI.

Sekarang status diturunkan **seketika** (UI langsung jujur bahwa sesi tidak lagi
dipakai), sedangkan penutupan socket sebenarnya dijadwalkan ke thread IO
sehingga tidak menahan pemanggil.

### Notes
- Setelah perbaikan ini, **tidak ada lagi** pemanggilan metode library yang
  menahan `mLock` dari main thread: `status()`/`exec()` hanya membaca field
  `@Volatile`; `connect*`, `disconnect`, `openStream` seluruhnya di thread IO.
- Yang MASIH berjalan di main thread dan dicatat sebagai pekerjaan lanjutan:
  pemanggilan shell dari command aksesibilitas (`tap`, `startApp`, `wakeScreen`,
  `killAppMode`) serta `awaitForeground()`. Ini diwarisi dari v0.8.0 dan
  perbaikannya menuntut pemindahan dispatch perintah secara keseluruhan —
  terlalu berisiko dilakukan tanpa pengujian perangkat.
- Belum diuji pada perangkat nyata (rencana fase F9).

## [0.9.2] — 2026-09-13

### Fixed — tiga cacat pada transport ADB, ditemukan lewat penelusuran ulang
Semuanya ditemukan dengan **membaca ulang kode library** (`AbsAdbConnectionManager`)
dan menelusuri ulang jalur eksekusi — **sebelum** pengujian di perangkat.
Dua di antaranya akan membuat pairing tampak gagal walaupun di HP berhasil.

1. **SELF-DEADLOCK: sesi setelah pairing tidak pernah bisa terbentuk.**
   `connectAsync()` mengirim tugas ke executor satu-thread, lalu memanggil
   `connectNow()` yang **menyerahkan tugas LAGI ke executor yang sama** dan
   menunggu hasilnya. Karena hanya ada satu thread dan thread itu sedang
   menunggu, tugas di dalamnya tidak akan pernah berjalan: hasilnya **selalu**
   timeout ~13 detik dan dilaporkan gagal.
   Sekarang bodi penyambungan ([doConnect]) dipanggil langsung di dalam tugas
   itu, tanpa penyerahan bersarang.

2. **ANR: `status()` bisa memblokir main thread sampai ~11 detik.**
   `isConnected()` pada library meminta `synchronized (mLock)` — kunci yang
   ditahan `autoConnect()` **selama seluruh** discovery + socket. Sejak
   penyambungan dipindahkan ke latar, `status()`/`capabilities` yang dipanggil
   dari main thread bisa menunggu kunci itu sampai 11 detik.
   Sekarang status koneksi disimpan di field `@Volatile` milik kami sendiri
   (`linkUp`) yang di-set thread IO; `status()` dan `exec()` hanya membaca field
   itu — tanpa kunci, tanpa IO.

3. **Risiko thread IO macet permanen oleh probe uid.**
   `openStream()` pada library tidak menerima timeout dan menahan kunci; bila
   `adbd` tidak menjawab, panggilan itu bisa menggantung dan menempati
   satu-satunya thread ADB selamanya. Probe dipindahkan keluar dari jalur
   connect, hanya dijalankan **setelah satu perintah benar-benar sukses**, dan
   stream-nya didaftarkan sebagai `activeStream` sehingga timeout perintah bisa
   menutupnya paksa. Bila tetap gagal, `adb_uid` tetap `-1` ("belum diketahui") —
   jujur, bukan tebakan. Field ini murni informasi; tidak ada logika backend
   atau frontend yang bercabang atas nilainya.

### Notes
- Belum diuji pada perangkat nyata (rencana fase F9). Perbaikan 1 dan 2 hanya
  bisa dibuktikan pada perangkat, jadi verifikasi yang tersedia saat ini adalah
  pembacaan kode + kompilasi CI.

## [0.9.1] — 2026-09-13

### Fixed — dua cacat pada alur pairing transport ADB
Keduanya ditemukan lewat penelusuran ulang alur pairing terhadap perilaku
library dan batas waktu server, **sebelum** pengujian di perangkat. Keduanya
akan membuat pairing tampak GAGAL di dasbor walaupun di HP sebenarnya berhasil.

1. **Anggaran waktu perangkap (batas luar lebih pendek dari bagian dalam).**
   `connectTls()` memakai timeout yang diberikan untuk penemuan mDNS **lalu
   menambah** timeout socket sendiri, sehingga durasi terburuknya lebih panjang
   daripada batas yang dipasang pemanggil. Akibatnya agen menyerah lebih dulu,
   dan — karena socket blocking tidak bisa diinterupsi — tugas itu tetap
   menempati satu-satunya thread ADB, sehingga perintah berikutnya ikut macet.
   Sekarang anggaran dipisah eksplisit (penemuan 5 dtk, socket 6 dtk) dan batas
   luar selalu ≥ durasi terburuk bagian dalam.

2. **Pairing melewati batas 30 detik command server.** Versi 0.9.0 menyambung
   secara sinkron setelah pairing, sehingga durasi terburuknya ~40 dtk —
   melewati `agenthub.DefaultTimeout` (30 dtk). Dasbor akan melaporkan gagal
   padahal kunci sudah tersimpan di HP. Sekarang pairing dikembalikan sebagai
   hasil (15 dtk + kelonggaran), dan penyambungan dijalankan di belakang.
   `capabilities` melaporkan keadaan sesi yang sebenarnya.

### Notes
- `adbPair` mengembalikan `ok = true` ketika **pairing** berhasil; sesi bisa
  masih `adb_connected = false` sesaat karena penyambungan berjalan di belakang.
  UI menampilkan "menyambung…" lalu diperbarui. Kontrak §3.2 sudah diperbarui.
- Belum diuji pada perangkat nyata (rencana fase F9).

## [0.9.0] — 2026-09-13

### Changed — BREAKING: Shizuku DIHAPUS TOTAL
Transport shell (hak uid 2000 / setara `adb shell`) kini memakai **klien ADB
milik aplikasi sendiri**, bukan aplikasi pihak ketiga Shizuku.
**Anda tidak perlu memasang Shizuku lagi** — cukup satu APK.

Yang dihapus:
- `ShizukuShell.kt`, izin `moe.shizuku.manager.permission.API_V23`, provider
  `rikka.shizuku.ShizukuProvider`, dependency `dev.rikka.shizuku:api` dan
  `:provider`, serta command `shizukuRequest`.

Yang menggantikannya (modul `privileged/`):
- **`AdbKeyStore`** — pasangan kunci RSA 2048 + sertifikat X.509 self-signed,
  digenerate sekali dan disimpan di penyimpanan privat aplikasi. Memakai
  BouncyCastle, **bukan** `sun-security-android` (pilihan itu butuh menembus
  API tersembunyi Android lewat `hiddenapibypass` — trik rapuh).
- **`AdbLocalShell`** — pairing, connect, dan eksekusi shell. Semua operasi
  berjalan di SATU thread IO (serialisasi mencegah dua stream merusak paket),
  setiap operasi dibatasi waktu, dan `close()` induk sengaja TIDAK dipanggil
  karena ia memusnahkan kunci privat sehingga koneksi berikutnya mustahil.
- **`AdbPairingController`** — menyiapkan transport, menyimpan status "pernah
  dipasangkan" sehingga UI bisa membedakan "belum pernah" dari "terputus
  setelah HP restart".
- **`AdbShellOutput`** — pengurai keluaran murni (teruji di JVM).

Nama transport berubah: `shell_shizuku` → **`shell_adb`**. Kapabilitas
`shizuku_*` diganti **`adb_paired` / `adb_connected` / `adb_uid` / `adb_error`**.
`can_shell` dipertahankan namanya. Kode alasan `shizuku_*` diganti enam kode
`adb_*`: `adb_not_paired`, `adb_pair_failed`, `adb_auth_failed`,
`adb_disconnected`, `adb_port_unknown`, `adb_disabled`.

### Added
- **Command `adbPair`** — memulai alur pairing transport ADB dari sisi server.
- **UI Setup 3 langkah berurut**: (1) Aksesibilitas — WAJIB, (2) izin
  "Tampilkan di atas aplikasi lain" — WAJIB, (3) Otomasi Lanjutan (ADB) —
  OPSIONAL dengan dialog pairing.
- **Kartu izin overlay di tab Setup.** Sebelumnya kartu ini TIDAK ADA sama
  sekali, padahal izinnya wajib sejak Android 10; tanpa itu Android membatalkan
  permintaan membuka aplikasi TANPA pesan error, sehingga pemilik HP bisa merasa
  "sudah mengaktifkan semuanya" sementara otomasi tetap gagal.
- Command `shell` dan `adbPair` kini dijalankan di thread IO, bukan main thread.
  Keduanya bisa memakan belasan detik; menjalankannya di main thread berisiko ANR.

### Fixed
- **Bug: tombol izin overlay tidak pernah di-`findViewById`** sehingga akan
  melempar `UninitializedPropertyAccessException` saat diketuk. Ditemukan lewat
  pemeriksaan silang otomatis antara id layout dan seluruh `lateinit var`.
- **Bug: error launch menyesatkan.** Versi lama membuang error percobaan
  pertama dan hanya melaporkan yang terakhir, sehingga kegagalan TikTok tampil
  sebagai masalah Shizuku padahal akar aslinya adalah launch aksesibilitas yang
  tidak muncul di foreground. Semua error percobaan kini dilaporkan berurutan.

### Notes — batas yang harus Anda tahu
- **Wireless Debugging tetap diperlukan** untuk fitur shell, dan pairing harus
  **diulang setiap HP selesai di-restart** (Android mematikan Debug nirkabel
  otomatis). Ini batas platform, bukan kekurangan aplikasi. Tanpa langkah ini
  aplikasi tetap berfungsi dengan kemampuan terbatas (aksesibilitas + overlay).
- Pairing terjadi antara HP dan **dirinya sendiri** lewat `127.0.0.1` — koneksi
  lokal, tidak menyentuh server GoSosmed.
- Teknik ini **tidak kompatibel** dengan Shizuku yang masih terpasang. Bila Anda
  pernah memasang Shizuku untuk versi lama, silakan hapus — tidak dipakai lagi.

## [0.8.0] — 2026-09-12

### Added
- **Transport Tier 1 — Shizuku (uid 2000 / shell).** Perintah kini bisa
  dijalankan dengan hak setara `adb shell` tanpa root dan tanpa PC:
  `am start` (lolos Background Activity Launch), `am force-stop` (reset
  layar deterministik), `input tap` (INJECT_EVENTS), `input keyevent 224`.
  Berkas baru `ShizukuShell.kt` — lihat `docs/SHIZUKU-TRANSPORT.md`.
- **Command `shell`**: eksekusi perintah dengan daftar putih biner
  (`am input monkey pm dumpsys wm settings cmd`). Di luar daftar ditolak
  dengan `reason=blocked`.
- **Command `shizukuRequest`**: server bisa memunculkan dialog izin Shizuku
  di HP.
- **Field `transport`** pada hasil `startApp`/`killApp`/`shell`
  (`shell_shizuku` = deterministik, `accessibility` = best-effort).
- **`capabilities` diperluas**: `transport_tier`, `last_launch_transport`,
  `shizuku_installed/running/permission/permission_denied_forever/uid/version`,
  `can_shell`, `can_inject_input`.
- **Dokumentasi kontrak** `docs/AGENT-COMMAND-CONTRACT.md`: daftar command,
  envelope JSON, seluruh kode `reason`, aturan preflight fail-closed untuk
  backend, dan copy remediasi untuk frontend `/accounts`.

### Changed
- `killApp` kini melaporkan `force_stop=true` **hanya** bila force-stop nyata
  terjadi (jalur Shizuku). Sebelumnya selalu `false`; server tidak boleh lagi
  mengasumsikan layar sudah bersih tanpa memeriksa field ini.
- `can_force_stop` dan `can_launch_app` dihitung dari kondisi nyata perangkat,
  bukan konstanta.
- `tap` dan `wake` memakai jalur shell bila tersedia, dengan fallback otomatis
  ke gesture/wakelock.

### Notes
- Tanpa Shizuku, agent **tetap berfungsi** di Tier 2 (accessibility + overlay
  v0.7.1) — degradasi mulus, bukan crash.
- `Shizuku.newProcess` privat sejak API 13; dipakai lewat refleksi dengan
  fallback aman. Risiko dan rencana migrasi ke `UserService` AIDL
  didokumentasikan di `docs/SHIZUKU-TRANSPORT.md`.
- Setelah HP reboot, Shizuku harus di-start ulang (pairing wireless
  debugging); selama itu transport turun ke Tier 2 secara jujur.

## [0.7.0] — 2026-09-11

### Added
- **Command `wake`** (blueprint Go-sosmed P0-4): server bisa menyalakan layar
  HP yang padam sebelum verify/harvest (wakelock ACQUIRE_CAUSES_WAKEUP).
  Tanpa ini dump UI mengembalikan null root saat layar mati. Keyguard
  PIN/pola tetap tidak bisa dibuka — kondisi itu dilaporkan jujur.
- **Sinkron versi / cek update**: kartu "Pembaruan aplikasi" baru di tab
  Setup — tombol **Cek Update** (menanyakan GitHub Releases) + tombol
  **Unduh APK** saat ada versi lebih baru. Otomatis: server menyertakan
  `latest_agent_version` + `apk_url` di `register_ack` setiap koneksi, jadi
  status update tampil tanpa cek manual. Cadangan: tahan teks versi di header.
- **`installed_platforms` di hello**: agent melaporkan platform sosial yang
  benar-benar terpasang di user primary (instagram/tiktok/youtube/facebook/
  threads) supaya dasbor hanya menawarkan app yang ada di HP ini.

### Fixed
- **Anti-chooser Dual Apps MIUI di sumbernya**: `startApp` kini memakai
  `LauncherApps.startMainActivity` dengan UserHandle primary (user 0) —
  resolver XSpace (com.miui.securitycore) tidak lagi muncul saat server
  meluncurkan TikTok/Facebook di HP yang punya app clone (insiden produksi
  2026-09-11: chooser menelan launch → verify "layar tidak dikenali").
  Clone berbagi nama package dengan app murni tetapi hidup di user lain
  (999); getActivityList(primary) tidak melihatnya, jadi yang diluncurkan
  selalu app murni. Jalur intent lama tetap ada sebagai fallback.

## [0.6.1] — 2026-09-04

### Added
- **agent_version di hello register**: agent kini melaporkan versinya
  (`BuildConfig.VERSION_NAME`) dalam `device_info` saat menyambung ke
  server. Dasbor BYOD memakainya untuk menampilkan badge
  "update tersedia" (server bandingkan dengan `GOSOSMED_AGENT_LATEST_VERSION`).
  Agent ≤0.6.0 tidak mengirim field ini → tanpa badge (degradasi jujur).

## [0.6.0] — 2026-09-04

> ⚠️ **WAJIB uninstall-install ulang**: keystore signing dirotasi (lihat
> Security di bawah) — APK lama TIDAK bisa di-upgrade ke 0.6.0
> (INSTALL_FAILED_UPDATE_INCOMPATIBLE). Ini perubahan sekali jalan demi
> keamanan distribusi.

### Security (Plan 07 Fase P0)
- **Rotasi keystore (P0-1)**: keystore lama yang pernah tersimpan di repo
  publik (password in-repo) dinyatakan MATI. Signing kini dari GitHub
  Secrets (`GOSOSMED_KEYSTORE_B64`/`STORE_PASSWORD`/`KEY_ALIAS`/
  `KEY_PASSWORD`); folder `keystore/` dihapus dari repo dan workflow
  `generate-keystore.yml` dihapus.
- **Receiver dikunci (P0-2)**: broadcast `com.gososmed.agent.CMD` (jalur adb)
  kini hanya aktif di build DEBUG — build release menolaknya, app lain di HP
  tidak bisa memicu dump/tap/setText.
- **Cleartext ditutup (P0-3)**: `usesCleartextTraffic` global diganti
  `networkSecurityConfig` — build release menolak semua trafik cleartext
  (wss:// saja); build debug tetap boleh ws:// LAN untuk pengembangan.

### Fixed
- **Race map pending (GAP-K12)**: `AgentWsClient.pending` kini
  `ConcurrentHashMap` + id atomik + `pairingCode` `@Volatile` — diakses
  bersamaan oleh thread reader OkHttp dan coroutine heartbeat/timeout.
- **Tidak ada sleep di main thread (K13)**: retry re-bind MIUI dipindah ke
  thread IO di `AgentWsClient`; `AgentCommand.execute` kini fail cepat dengan
  pesan jujur, bukan tidur ±2 detik menyumbat main looper.
- **Dump dibatasi (GAP-K6)**: `MAX_NODES` (2000) diterapkan sungguhan —
  dump layar berat berhenti tepat waktu dan ditandai
  `<hierarchy truncated="true">`; sebelumnya konstanta hanya dead code.

### Removed
- Jalur JSON dump tanpa pemanggil: `dumpJson()` /
  `HierarchySerializer.dumpToJson()` / `appendNodeJson()` (dead code).

## [0.5.1-dev.1] — 2026-09-04

### Changed
- **Log tenang**: sukses `screenshot` (dipoll dasbor tiap ~1,5 dtk saat viewer
  aktif) hanya dicatat maksimal tiap 30 detik; kegagalan selalu dicatat.
  Tab Log kini berisi kejadian penting, bukan spam polling.

## [0.5.0-dev.1] — 2026-09-04

> **Kanal DEV** — perubahan UI mayor; belum diuji lintas perangkat.

### Added
- **UI tab-based** (Beranda / Setup / Log): halaman TIDAK lagi scroll panjang
  tanpa henti; hanya konten tab yang scroll internal, banner status & tab
  selalu terlihat.
- **Panel Log profesional**: warna per jenis entri (sukses hijau, gagal merah,
  kejadian biru), tombol **Jeda/Lanjut**, **Salin** (clipboard), **Bersihkan**,
  dan counter baris + indikator "DIJEDA (ada entri baru)".
- **Klarifikasi data di log**: setiap command mencatat ke mana data pergi —
  screenshot kini menjelaskan "**gambar dikirim ke server (base64) — TIDAK
  disimpan di HP**"; dump/hierarki juga dijelaskan tujuannya.
- Dark mode penuh (Material 3 DayNight): palet semantik `values-night`,
  panel log konsol tetap gelap di kedua mode.
- Kartu izin dengan status NYATA (AKTIF ✓ / BELUM) + tombol yang otomatis
  nonaktif saat izin sudah diberikan; pemeriksaan baterai via
  `PowerManager.isIgnoringBatteryOptimizations`.

### Changed
- Tema naik ke `Theme.Material3.DayNight.NoActionBar` + header sendiri;
  kartu memakai latar membulat (`bg_card`), warna tidak lagi hex statis.
- Tombol debug (Dump/Package/Back/Home/Tap) dipindah ke kartu "Mode debug"
  tersembunyi (tap 7× versi) di tab Beranda — produksi bersih.

### Fixed
- Warna status sebelumnya hardcode abu terang `#EEF2F7`/`#F7F8FA` — tidak
  terbaca di dark mode; kini semua via resource tema.

## [0.4.1] — 2026-09-04

### Added
- Info perangkat (model, Android, layar) dikirim di hello `register` untuk
  kartu perangkat di dasbor.
- Log perintah dengan latensi (pemerintah v0.4.1 `AgentLog.add(cmd, ok, ms)`).

### Fixed
- Literal warna hex `Long` → `Int` pada `setTextColor` (crash build).

## [0.4.0] — 2026-09-04

### Added
- **Auto-pairing deep link** `gososmed://pair?ws=<url>&code=<KODE8>`:
  terbitkan kode di dasbor → buka tautan di HP → tersambung tanpa mengetik.
- Onboarding 3 langkah di UI produksi; input URL server dihilangkan dari
  jalur utama (default `wss://api.bamsbung.id`, override hanya mode debug).

## [0.3.1] — 2026-09-04

### Fixed
- **Flap reconnect** (register/putus tiap 2–8 s di perangkat nyata):
  reconnect loop kini dibatalkan saat koneksi sukses + single-flight guard
  mencegah dua socket paralel; backoff eksponensial + jitter.
  Ditemukan & diverifikasi lewat tes perangkat nyata (Xiaomi garnet).

## [0.3.0] — 2026-09-04

### Added
- **Pairing multi-user (auth server-issued)**: kode 8 karakter dari dasbor
  (sekali pakai, TTL 15 menit) mengikat HP ke akun pemiliknya — menggantikan
  kode 6 digit self-generated yang tidak aman.
- `register_ack`: pairing ditolak (kode salah/kedaluwarsa) → agent berhenti
  mencoba ulang dan menunggu kode baru (bukan loop tanpa akhir).
- **Stabilitas koneksi (M3)**: WS dimiliki foreground service (bertahan saat
  UI ditutup / proses dibunuh / reboot), `pingInterval` OkHttp 20 s,
  reconnect segera saat jaringan kembali (ConnectivityManager).
- Input kode pairing + tampilan status di UI; version bump APK.

### Changed
- **BREAKING**: enroll-first anonim dihapus — device lama tanpa pairing
  server-issued harus di-pair ulang dengan kode baru dari dasbor.

## [0.2.0] — 2026-09-02

### Added
- `dumpWindows` (getWindows) + `takeScreenshot` (AccessibilityService API 30+)
  — bukti visual untuk audit job.
- `startApp` / `killApp` / `hasPackage` / `listPackages` — agent bisa
  meluncurkan/menghentikan aplikasi tanpa root.

### Fixed
- Kompatibilitas MIUI (Xiaomi): service tidak hancur saat `startApp`
  (applicationContext + retry ketika instance aksesibilitas re-bind).

## [0.1.0] — 2026-08-31

### Added
- Fondasi agent: AccessibilityService, serializer hierarki kompatibel
  `uiautomator dump`, eksekusi tap/text/global tanpa root.
- Klien WebSocket outbound (auto-reconnect + heartbeat), pairing code +
  `device_id` persisten.
- CI GitHub Actions: build APK per push (artifact `gososmed-agent-debug`).

[Unreleased]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.9.11-dev.1...HEAD
[0.9.11-dev.1]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.9.10...v0.9.11-dev.1
[0.9.10]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.9.9...v0.9.10
[0.7.0]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.6.1...v0.7.0
[0.6.1]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.6.0...v0.6.1
[0.6.0]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.5.0-dev.1...v0.6.0
[0.5.0-dev.1]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.4.1...v0.5.0-dev.1
[0.4.1]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.3.1...v0.4.0
[0.3.1]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/dedy45/gososmed-mobile-agent/releases/tag/v0.1.0
