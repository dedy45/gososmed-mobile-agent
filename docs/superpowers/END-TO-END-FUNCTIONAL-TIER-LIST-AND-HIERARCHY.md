# Peta Fungsionalitas End-to-End APK: Hierarki, Tier List, & Panduan Agent (v1.2.21)

> **Lokasi File:** `docs/superpowers/END-TO-END-FUNCTIONAL-TIER-LIST-AND-HIERARCHY.md`  
> **Status:** KANONIK, KOMPREHENSIF & AUDIT SOURCE CODE HULU-KE-HILIR  
> **Versi Aktual:** `v1.2.21` (versionCode: 61, dev release: `v1.2.21-dev.1`)  
> **Lingkup Audit:** Seluruh 60 Berkas Source Code Kotlin/Java Android APK  

---

## 1. Arsitektur Hulu-ke-Hilir: Alur Perjalanan Perintah (Inbound Flow)

```
[Server GoSosmed / MCP Hub / Client External]
                      │ (WSS / HTTP / adb broadcast)
                      ▼
 ┌─────────────────────────────────────────────────────────────┐
 │ 1. PINTU MASUK (Ingress Boundaries)                         │
 │    • AgentWsClient.kt (WebSocket Port /v1/agent/ws)         │
 │    • LocalWebServer.kt (Embedded HTTP/WS Port 8765/8766)    │
 │    • AgentReceiver.kt (ADB Broadcast com.gososmed.agent.CMD)│
 └────────────────────────────┬────────────────────────────────┘
                              │
                              ▼
 ┌─────────────────────────────────────────────────────────────┐
 │ 2. DISPATCHER & PROTOCOL ROUTER (AgentCommand.kt)           │
 │    • Deteksi JSON {"protocol_version": 2}                   │
 └─────────────┬───────────────────────────────┬───────────────┘
               │ (Jika V2)                     │ (Jika V1)
               ▼                               ▼
 ┌───────────────────────────┐   ┌─────────────────────────────┐
 │ 3A. PROTOCOL V2 DETERMINIK│   │ 3B. PROTOCOL V1 DIRECT CMD  │
 │     • State Matching      │   │     • SERVICE_FREE (IO)     │
 │     • MutationGuard       │   │     • A11Y-BOUND (Looper/BG)│
 └─────────────┬─────────────┘   └─────────────┬───────────────┘
               │                               │
               └───────────────┬───────────────┘
                               │
                               ▼
 ┌─────────────────────────────────────────────────────────────┐
 │ 4. SUBSISTEM EKSEKUTOR HILIR (Execution Engines)            │
 │    • Accessibility Engine (AgentAccessibilityService.kt)    │
 │    • Vision & OCR Engine (AnnotatedScreenshotHelper.kt)     │
 │    • Deterministic Selector (DeterministicResolver.kt)      │
 │    • Local ADB Shell (AdbLocalShell.kt, UID 2000)          │
 │    • Hardware Video Encoder (CaptureService.kt, GPU H.264)  │
 │    • MediaStore ContentResolver (MediaStager.kt)            │
 │    • Headless Power / Dimming (DisplayPowerManager.kt)      │
 │    • Dynamic Hiding / Privacy (DynamicHidingManager.kt)     │
 └─────────────────────────────────────────────────────────────┘
```

---

## 2. Tier List Perintah: Efisiensi Token, Keandalan, & Rekomendasi Agen

Tier ini disusun secara objektif berdasarkan **biaya token LLM**, **latensi**, dan **keandalan deterministik** di atas Android OS:

| Tier | Simbol & Makna | Perintah / Tools | Alasan & Karakteristik |
|---|---|---|---|
| **Tier S** | 🏆 **Gold Standard (Wajib Prioritas)** | `clickAndWait`, `waitForNode`, `annotatedScreenshot`, `replaceText`, `checkSecurityChallenge`, `nodeInspector` | **Paling Hemat Token & Anti-Fragile.** Polling dan koordinasi dilakukan langsung di memori HP. Gambar dilengkapi nomor Set-of-Marks berkoordinat fisik 1:1. Menghemat 50%–95% token percakapan. |
| **Tier A** | 🛡️ **Deterministik Kuat (High Reliability)** | `shell`, `actNode` (V2), `observe` (V2), `actAndVerify` (V2), `executeBatch`, `stageMedia`, `cleanupMedia`, `setClipboard`, `startApp` (verified), `killApp` (force-stop) | **Deterministik.** Menggunakan hak istimewa Shell UID 2000 atau Protokol V2 dengan `snapshot_id` dan `mutation_guard`. Kebal pembatasan Background Activity Launch (BAL). |
| **Tier B** | ⚙️ **Kontrol Perangkat & Utilitas** | `tap`, `swipe`, `setText`, `back`, `home`, `recents`, `wake`, `ping`, `health`, `hasPackage`, `listPackages`, `copyText`, `pasteText`, `eraseText`, `waitForSettle`, `dumpWindows` | **Primitif Sistem Standar.** Bekerja dengan baik namun memerlukan orkestrasi berurutan dari pemanggil. |
| **Tier C** | ⚠️ **Boros / Situasional** | `screenshot` (polos tanpa tanda), `startLocalServer`, `globalDim`, `setDisplayPower`, `displaySize`, `displayDensity`, `rotate`, `volume_up/down` | **Boros Token atau Perangkat Keras Khusus.** Screenshot polos memaksa Vision Model menebak piksel absolut. Kontrol display hanya untuk penghematan daya operasional farm. |
| **Tier D** | 🛑 **Legacy / Dihindari (Anti-Pattern)** | `dump` (XML mentah uiautomator) | **Sangat Boros (>50.000 Token).** Menyebabkan context window blow-up, latensi 1.5–3 detik, dan model rentan mengalami halusinasi teks XML raksasa. |

---

## 3. Hierarki Modul Kode Sumber (Hulu ke Hilir)

### Lapisan 1: Pintu Masuk Jaringan & Broadcast
- **`AgentWsClient.kt`**: Client WebSocket outbound WSS ke backend GoSosmed. Mengatur koneksi ulang otomatis, auth token, heartbeat ping, dan parsing pesan JSON masuk.
- **`capture/LocalWebServer.kt`**: HTTP & WebSocket Server lokal di port 8765/8766. Melayani Web Cockpit, unggah/unduh berkas bypassing Scoped Storage, dan websocket frame biner.
- **`AgentReceiver.kt`**: Penerima broadcast intent `com.gososmed.agent.CMD` khusus build DEBUG (`adb shell am broadcast`).

### Lapisan 2: Routing & Batas Protokol
- **`AgentCommand.kt`**: Single point of dispatch. Membedakan Protocol V1 vs V2, memisahkan `SERVICE_FREE_COMMANDS` ke thread IO, dan membungkus `ASYNC_BACKGROUND_COMMANDS` ke coroutine latar belakang.
- **`ProtocolV2.kt`**: Kontrak formal Protokol V2, mencakup nama command, request/response envelope, dan dictionary `ReasonCodes`.
- **`CommandCancellationRegistry.kt`**: Registry token pembatalan untuk membatalkan perintah V2 yang sedang berjalan via `cancelAction`.

### Lapisan 3: Mesin Observasi, Selektor & State
- **`AnnotatedScreenshotHelper.kt`**: Menangkap bitmap layar fisik, menyelaraskan rasio `coordScale` menggunakan `DisplayMetrics`, menggambar kotak oranye Ember Orange bernomor badge `[1]`, `[2]`, dan menghasilkan array JSON `elements`.
- **`SnapshotCollector.kt`**: Mengumpulkan multi-window hierarchy, bounds, hash integritas pohon visual (`tree_hash`), dan sidik jari layar (`screen_fingerprint`).
- **`DeterministicResolver.kt`**: Mesin pencocokan elemen UI tanpa ambiguitas selektor (pencarian ID unik, teks persis, atau koordinat terkuantisasi).
- **`MutationGuard.kt`**: Menjaga agar aksi tidak dieksekusi jika layar telah berubah di luar sepengetahuan agent (`stale_snapshot`).
- **`ScreenTransitionVerifier.kt`**: Mengukur apakah suatu aksi benar-benar memicu transisi layar atau transisi gagal (`screen_not_changed`).
- **`HierarchySerializer.kt`**: Serializer pohon AccessibilityNodeInfo ke format XML yang kompatibel dengan uiautomator.

### Lapisan 4: Mesin Eksekusi Motorik UI (Accessibility)
- **`AgentAccessibilityService.kt`**: Service inti Android Accessibility. Melakukan in-memory node search (`waitForNode`, `clickAndWait`), fail-closed CAPTCHA scanning (`checkSecurityChallenge`), node inspection (`nodeInspector`), gestur sentuh (`tap`, `swipe`), dan input teks (`setText`, `replaceText`).
- **`NodeActionExecutor.kt`**: Pipeline eksekusi 12-langkah Protocol V2, penelusuran clickable ancestor, auto-scroll (`scrollUntilVisible`), dan operasi clipboard.
- **`ToastTracker.kt`**: Mendeteksi kemunculan toast notifikasi sistem Android.

### Lapisan 5: Mesin Hak Istimewa Shell UID 2000 (Local ADB)
- **`privileged/AdbLocalShell.kt`**: Transport ADB lokal internal via loopback socket `127.0.0.1` ke daemon `adbd` sistem.
- **`privileged/PrivilegedShell.kt`**: Antarmuka tunggal eksekusi perintah shell dengan denylist keamanan ketat.
- **`privileged/AdbPairingController.kt` & `AdbPairingService.kt`**: State machine proses pairing nirkabel lokal via notifikasi inline `RemoteInput`.
- **`privileged/AdbPortResolver.kt` & `AdbPairingPortDiscovery.kt`**: Penemuan port dynamic wireless debugging menggunakan mDNS / NSD UDP socket.
- **`privileged/PhantomProcessGuard.kt`**: Pencegah pembunuhan proses latar belakang oleh Android 12+ Phantom Process Killer.

### Lapisan 6: Streaming Video Perangkat Keras (MediaCodec H.264 GPU)
- **`capture/CaptureService.kt`**: Virtual display `MediaProjection` zero-copy ke hardware video encoder H.264/HEVC.
- **`capture/EncoderPipeline.kt`**: Pengaturan bitrate, FPS, dan format warna encoder perangkat keras.
- **`capture/ProjectionWatchdog.kt`**: Watchdog pemantau kelancaran streaming tanpa false alarm pada layar statis.
- **`capture/SpsFix.kt`**: Penyuntik NAL unit SPS/PPS pada stream H.264 mentah agar dapat dirender oleh browser modern.

### Lapisan 7: Pengelolaan Galeri & Privasi
- **`MediaStager.kt`**: Pengunduhan media, verifikasi hash SHA-256, dan integrasi Android `MediaStore` ContentResolver.
- **`hiding/DynamicHidingManager.kt`**: Penyembunyian elemen sensitif pengguna secara dinamis saat streaming.
- **`headless/DisplayPowerManager.kt`**: Manajemen daya layar fisik (layar mati saat automasi tetap berjalan penuh).

---

## 4. Panduan Pola Penggunaan yang "Agent-Friendly" (Best Practices)

Agar agen AI (Claude, GPT-4o, ZeroClaw, OMP) dapat mengontrol perangkat dengan tingkat keberhasilan 100%:

### Aturan 1: Jangan Gunakan `dump()` untuk Membaca Layar
- ❌ **Anti-Pattern:** Memanggil `dump()`, menerima 50.000 baris XML, lalu bingung mencari tombol di dalam ribuan teks layout.
- ✅ **Best Practice:** Panggil **`annotatedScreenshot(scale=0.5)`**. Agen akan menerima gambar beranotasi nomor badge `[1]`, `[2]` serta JSON sederhana:
  ```json
  { "id": 2, "class": "Button", "text": "Unggah", "center": {"x": 540, "y": 1800} }
  ```
  Agen cukup memilih ID `2` lalu mengeksekusi klik.

### Aturan 2: Gunakan Compound Action `clickAndWait`
- ❌ **Anti-Pattern:** Agen memanggil `tap(540, 1800)`, lalu menunggu, lalu memanggil `screenshot()` lagi untuk melihat apakah halaman berganti (menghabiskan 2–3 turn percakapan).
- ✅ **Best Practice:** Panggil **`clickAndWait(click_text="Unggah", wait_text="Selesai", timeout_ms=5000)`**.
  Aksi pengetukan dan verifikasi perpindahan layar diselesaikan di RAM ponsel dalam 1 turn.

### Aturan 3: Pasang Guard CAPTCHA / 2FA dengan `checkSecurityChallenge`
- ❌ **Anti-Pattern:** Agen terus mengklik tombol saat akun terkena checkpoint atau slide puzzle, menyebabkan akun media sosial diblokir permanen.
- ✅ **Best Practice:** Panggil **`checkSecurityChallenge()`** di awal alur kritis atau setelah login. Jika `detected: true`, hentikan automasi seketika dan minta intervensi manusia (fail-closed).

### Aturan 4: Gunakan `replaceText` untuk Form Input
- ❌ **Anti-Pattern:** Memanggil `setText` yang menumpuk teks baru di atas teks lama, atau keyboard virtual menutupi tombol Submit di bawah layar.
- ✅ **Best Practice:** Panggil **`replaceText(text="Konten baru", submit=true)`**. Teks lama otomatis dibersihkan, teks baru disalin via clipboard, dan keyboard virtual otomatis disembunyikan.
