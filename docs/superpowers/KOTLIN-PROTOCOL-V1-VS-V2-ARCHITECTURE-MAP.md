# Arsitektur & Peta Lokasi Kode Kotlin: Protocol V1 vs Protocol V2

> **Lokasi File:** `docs/superpowers/KOTLIN-PROTOCOL-V1-VS-V2-ARCHITECTURE-MAP.md`  
> **Status:** KANONIK & 100% TRANSPARAN (Bedah Tuntas Berkas Kode Kotlin APK v1.2.21)  
> **Tanggal:** 2026-11-16  

---

## 1. Mengapa Ada Dua Protokol di Kode Kotlin?

Di dalam codebase `gososmed-mobile-agent`, terdapat dua generasi protokol yang hidup berdampingan di berkas utama **`app/src/main/java/com/gososmed/agent/AgentCommand.kt`**:

```
                                  [Inbound WebSocket JSON]
                                             │
                                             ▼
                                  AgentCommand.execute(req)
                                             │
                        ┌────────────────────┴────────────────────┐
                        │ Apakah punya "protocol_version": 2 ?    │
                        ▼                                         ▼
                     [ YA ]                                    [ TIDAK ]
            ┌──────────────────────┐                    ┌──────────────────────┐
            │     PROTOCOL V2      │                    │     PROTOCOL V1      │
            │   High-Reliability   │                    │   Legacy Automation  │
            │  State & Determinism │                    │   Direct Command     │
            └──────────┬───────────┘                    └──────────┬───────────┘
                       │                                           │
         suspend fun executeV2Internal()               ┌───────────┴───────────┐
                       │                               │ Di SERVICE_FREE?      │
         ┌─────────────┴─────────────┐                 ▼                       ▼
         │ `observe`                 │             [ YA ]                   [ TIDAK ]
         │ `resolve`                 │      executeServiceFree()     executeWith(svc, ...)
         │ `actNode`                 │      (IO Thread: shell,       (Main Thread Looper:
         │ `actAndVerify`            │       adbPair, ping, dll)      tap, replaceText, dll)
         │ `scrollUntilVisible`      │
         │ `waitForNode`             │
         │ `waitForScreen`           │
         │ `executeBatch`            │
         │ `cancelAction`            │
         │ `start/stopDebugFrames`   │
         └───────────────────────────┘
```

---

## 2. Peta Lokasi Berkas & Garis Kode Konkret

### A. Titik Percabangan Utama (Router Dispatcher)
- **Berkas:** `app/src/main/java/com/gososmed/agent/AgentCommand.kt`
- **Baris 180 - 198 (`fun execute`):**
  ```kotlin
  fun execute(req: JSONObject): JSONObject {
      // 1. Deteksi Protocol V2
      if (req.has("protocol_version")) {
          val version = req.optInt("protocol_version", ProtocolV2.LEGACY_VERSION)
          val requestId = req.optString("request_id", "")
          if (version > ProtocolV2.CURRENT_VERSION || version < ProtocolV2.LEGACY_VERSION) {
              return ProtocolV2.Response.protocolMismatch(requestId, version).toJson()
          }
          if (version == ProtocolV2.CURRENT_VERSION && requestId.isBlank()) {
              return ProtocolV2.Response.error(...)
          }
          if (version == ProtocolV2.CURRENT_VERSION) {
              // Menjalankan coroutine V2 secara blocking untuk WebSocket listener
              return runBlocking { executeV2(req) }
          }
      }

      // 2. Jika tanpa "protocol_version" -> Jatuh ke PROTOCOL V1
      val id = req.optInt("id", -1)
      val cmd = req.optString("cmd", "")
      ...
  ```

---

### B. PROTOCOL V2: Di Mana Tempatnya & Apa Saja Komponennya?

Protocol V2 adalah arsitektur otomasi deterministik modern (berdasarkan `PLAN-DETERMINISTIC-ANDROID-PORTAL.md`). Karakteristik utamanya: **Stateless Server, Stateful Client**, dilindungi oleh `snapshot_id`, `mutation_guard`, dan verifikasi transisi layar.

#### 1. Definisi Kontrak & Tipe Data V2
- **Berkas:** `app/src/main/java/com/gososmed/agent/ProtocolV2.kt` (Baris 1 - 250)
  - `const val CURRENT_VERSION: Int = 2`
  - Objek `ProtocolV2.Commands`:
    - `OBSERVE = "observe"`
    - `RESOLVE = "resolve"`
    - `ACT_NODE = "actNode"`
    - `ACT_AND_VERIFY = "actAndVerify"`
    - `SCROLL_UNTIL_VISIBLE = "scrollUntilVisible"`
    - `WAIT_FOR_NODE = "waitForNode"`
    - `WAIT_FOR_SCREEN = "waitForScreen"`
    - `EXECUTE_BATCH = "executeBatch"`
    - `CANCEL_ACTION = "cancelAction"`
    - `START_DEBUG_FRAMES = "startDebugFrames"` / `STOP_DEBUG_FRAMES = "stopDebugFrames"`
  - Objek `ProtocolV2.ReasonCodes`: Kode alasan kegagalan formal (`no_node`, `ambiguous`, `invalid_bounds`, `stale_snapshot`, `screen_not_changed`, `blocked_dialog`, `timeout`, dll.).

#### 2. Implementasi Eksekutor V2
- **Berkas:** `app/src/main/java/com/gososmed/agent/AgentCommand.kt`
- **Baris 292 - 872 (`private suspend fun executeV2Internal`):**
  - **Baris 347 (`OBSERVE`):** Mengambil snapshot multi-window lengkap (`SnapshotCollector.capture`), menghitung hash pohon visual (`tree_hash`), sidik jari layar (`screen_fingerprint`), dan menyinkronkan frame screenshot biner.
  - **Baris 435 (`RESOLVE`):** Mencari node target secara deterministik menggunakan `DeterministicResolver.resolve` berdasarkan `NodeSelector` (ID, teks, bounds).
  - **Baris 495 (`ACT_NODE` & `ACT_AND_VERIFY`):** Mengetuk atau berinteraksi dengan node, lalu memvalidasi apakah layar berubah menggunakan `MutationGuard` dan `ScreenTransitionVerifier`. Jika layar tidak berubah, mengembalikan error `screen_not_changed`.
  - **Baris 609 (`SCROLL_UNTIL_VISIBLE`):** Menggulir layar berulang kali sampai selektor node yang dicari muncul di viewport.
  - **Baris 650 (`WAIT_FOR_NODE`):** Menunggu node muncul pada memori internal dengan polling interval dinamis.
  - **Baris 738 (`WAIT_FOR_SCREEN`):** Menunggu perubahan tampilan layar (berdasarkan fingerprint/hash) atau menunggu hingga layar stabil (quiescence settle).
  - **Baris 794 (`EXECUTE_BATCH`):** Menjalankan alur batch langkah-langkah deterministik via `BatchFlowExecutor`.

#### 3. Modul Pendukung Protocol V2
- `SnapshotCollector.kt`: Pengumpul pohon multi-window, display metrics, dan window layer.
- `DeterministicResolver.kt`: Algoritma pencocokan node tanpa ambiguitas selektor.
- `MutationGuard.kt`: Penjaga integritas state agar aksi tidak dieksekusi di atas snapshot layar yang sudah usang (`stale_snapshot`).
- `ScreenTransitionVerifier.kt`: Pengukur stabilitas layar dan transisi animasi.
- `CommandCancellationRegistry.kt`: Manajemen pembatalan aksi di tengah jalan (`cancelAction`).

---

### C. PROTOCOL V1 (Legacy & Direct Action): Di Mana Tempatnya?

Protocol V1 adalah protokol komunikasi aksi langsung (direct command) yang dipakai oleh kartu UI App Dashboard, skrip automasi sederhana, dan pemanggilan tool MCP GoSosmed.

#### 1. Perintah Bebas Aksesibilitas (`SERVICE_FREE_COMMANDS`)
- **Berkas:** `app/src/main/java/com/gososmed/agent/AgentCommand.kt`
- **Baris 874 - 1184 (`private fun executeServiceFree`):**
  - **`shell` (Baris 877):** Eksekusi perintah ADB UID 2000 (`PrivilegedShellHolder.get().exec`).
  - **`adbPair` (Baris 898):** Pairing ADB lokal ke `127.0.0.1` (`AdbPairingController.pair`).
  - **`hasPackage` & `listPackages` (Baris 934 - 952):** Query package manager Android.
  - **`ping` & `health` (Baris 966 - 972):** Health check koneksi dan snapshot subsistem.
  - **`stageMedia` & `cleanupMedia` (Baris 982 - 1005):** Download media ke galeri & pembersihan DCIM.
  - **`startLocalServer` & `stopLocalServer` (Baris 1061 - 1071):** Server web remote & streaming H.264.
  - **`setDisplayPower`, `globalDim`, `displaySize`, `displayDensity` (Baris 1075 - 1135):** Kontrol layar fisik.

#### 2. Perintah Berbasis Aksesibilitas (`executeWith(svc, ...)`)
- **Berkas:** `app/src/main/java/com/gososmed/agent/AgentCommand.kt`
- **Baris 1213 - 1680 (`private fun executeWith`):**
  - **`dump` (Baris 1220):** `svc.dumpXml()` (XML uiautomator mentah).
  - **`dumpWindows` (Baris 1362):** `svc.dumpWindows()` (Daftar seluruh jendela aktif).
  - **`screenshot` (Baris 1373):** `svc.takeScreenshotBase64(scale, format, quality)`.
  - **`annotatedScreenshot` (Baris 1445):** `AnnotatedScreenshotHelper.annotateRoots(...)` (Set-of-Marks Ember Orange + JSON elemen berkoordinat fisik).
  - **`waitForNode` (Baris 1391):** `svc.waitForNode(...)` (Polling 80ms di memori HP).
  - **`clickAndWait` (Baris 1500):** `svc.clickAndWait(...)` (Tap + tunggu transisi dalam 1 turn).
  - **`checkSecurityChallenge` (Baris 1656):** `svc.checkSecurityChallenge()` (Deteksi fail-closed CAPTCHA/2FA/Checkpoint).
  - **`nodeInspector` (Baris 1661):** `svc.inspectNode(...)` (Ambil atribut detail 1 elemen).
  - **`tap`, `tapByText`, `tapFirstClickable`, `swipe` (Baris 1228 - 1265):** Motorik gesture sentuhan.
  - **`setText` & `replaceText` (Baris 1270 & 1414):** Input teks dan clipboard paste.
  - **`back`, `home`, `recents`, `notify`, `volume_up/down`, `power` (Baris 1278 - 1285):** Tombol hardware sistem.
  - **`startApp` & `killApp` (Baris 1287 - 1312):** Siklus hidup aplikasi.
  - **`setClipboard`, `copyText`, `pasteText`, `eraseText` (Baris 1572 - 1604):** Manipulasi teks tingkat lanjut.
  - **`waitForSettle` (Baris 1638):** Menunggu redanya animasi layar.

---

## 3. Matriks Perbandingan Format JSON: V1 vs V2

| Parameter | Protocol V1 (Legacy / Direct) | Protocol V2 (High-Reliability) |
|---|---|---|
| **Header Identifikasi** | `{"id": 12, "cmd": "tap", ...}` | `{"protocol_version": 2, "request_id": "req-12", "cmd": "actNode", "args": {...}}` |
| **Pemeriksaan State** | Tidak ada (buta kondisi layar sebelumnya) | Wajib: `snapshot_id` dicocokkan dengan `tree_hash` layar saat ini |
| **Respon Sukses** | `{"id": 12, "ok": true, "result": {...}}` | `{"protocol_version": 2, "request_id": "req-12", "status": "ok", "result": {...}, "timing": {...}}` |
| **Respon Gagal** | `{"id": 12, "ok": false, "error": "pesan"}` | `{"protocol_version": 2, "request_id": "req-12", "status": "error", "reason_code": "stale_snapshot", "result": {"error": "..."}}` |
| **Penanganan Pembatalan** | Tidak bisa dibatalkan saat berjalan | Bisa dibatalkan sewaktu-waktu via `cancelAction` |
| **Streaming Frame Layar** | Polling command `screenshot` berulang | Stream kuantisasi terkoordinasi via `startDebugFrames` & payload biner `observe` |

---

## 4. Panduan Debugging & Troubleshooting

1. **Bagaimana Mengetahui Permintaan Masuk V1 atau V2?**
   - Periksa log aplikasi di tab Log atau via `adb logcat -s GoAgent AgentWsClient`:
     - Jika payload memiliki kunci `"protocol_version": 2`, alur masuk ke `AgentCommand.executeV2()`.
     - Jika payload tidak memiliki kunci tersebut, alur masuk ke `AgentCommand.executeServiceFree()` (jika command bebas a11y) atau `AgentCommand.executeWith()` (jika perlu a11y).
2. **Kenapa `observe` atau `actNode` Tidak Ada di Switch V1?**
   - Karena `observe`, `resolve`, `actNode`, dan `actAndVerify` adalah perintah eksklusif **Protocol V2** (berada di dalam `executeV2Internal` baris 324-600).
   - Sebaliknya, `tap`, `swipe`, `annotatedScreenshot`, `checkSecurityChallenge`, dan `nodeInspector` adalah perintah direct **Protocol V1** (berada di baris 1215-1670).
3. **Apakah Keduanya Bisa Dipanggil dari Backend yang Sama?**
   - **BISA**. WebSocket handler APK secara otomatis mendeteksi keberadaan `"protocol_version": 2` pada setiap pesan JSON yang masuk dan mengarahkannya ke eksekutor yang tepat secara instan.
