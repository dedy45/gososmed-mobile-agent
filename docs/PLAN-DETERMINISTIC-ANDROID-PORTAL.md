# Plan Implementasi APK — Deterministic Android Portal

> **Status:** READY FOR CODING AGENT
> **Tanggal:** 2026-10-05
> **Repo:** `C:\Users\dedy\Documents\gososmed-mobile-agent`
> **Owner kode:** coding agent APK
> **Batas keras:** agent pada repo ini DILARANG mengedit `C:\Users\dedy\Documents\Go-sosmed`
> **Pasangan kontrak server:** `C:\Users\dedy\Documents\Go-sosmed\docs\7-plans\2026-10-05-mobile-control-plane-and-web-inspector.md`
> **Perubahan sesi penyusunan:** dokumentasi saja; belum ada kode APK yang diubah

---

## 1. Goal

Mengubah APK dari executor primitive berbasis teks/koordinat menjadi portal Android deterministik yang:

1. mengobservasi seluruh interactive windows;
2. mengikat hierarchy dan screenshot dalam satu snapshot;
3. memberi identitas sementara pada actionable nodes;
4. memilih node secara deterministik;
5. mengeksekusi action pada node atau clickable ancestor;
6. memverifikasi perubahan layar setelah setiap action;
7. mengembalikan reason code yang jujur;
8. menyediakan metadata overlay untuk Web UI Inspector;
9. tetap memakai outbound WebSocket;
10. tidak menyimpan frame layar di HP.

Target produksi bukan “tidak pernah error”. Targetnya adalah nol false success, nol tap ambigu, dan kegagalan yang berhenti dengan bukti terstruktur.

---

## 2. Scope Repo APK

### Termasuk

- protocol command versi baru;
- snapshot collector;
- multi-window hierarchy;
- actionable node registry;
- deterministic resolver;
- node action executor;
- smart wait dan postcondition verifier;
- screenshot/frame encoding;
- structured trace result;
- capability handshake;
- lifecycle, reconnect, cancellation, dan rate guard;
- unit test dan real-device validation;
- pembaruan dokumentasi wire contract dan changelog.

### Tidak termasuk

- database PostgreSQL;
- endpoint HTTP backend;
- Svelte Web UI Inspector;
- state machine job server;
- migration SQL;
- perubahan adapter Go;
- deploy backend/frontend;
- implementasi Mobilerun Python;
- menyalin kode AGPL DroidRun Portal.

---

## 3. Baseline APK Saat Ini

### Sudah tersedia

- `AgentForegroundService` dengan `START_STICKY`;
- outbound WSS di `AgentWsClient`;
- `AgentAccessibilityService`;
- `dumpXml` dan `dumpWindows`;
- screenshot API 30+ dengan `scale`, `format`, dan `quality`;
- `dispatchGesture`;
- `ACTION_SET_TEXT`;
- transport ADB lokal opsional;
- wake lock dan Wi-Fi lock;
- pairing dan reconnect;
- media staging;
- overlay 1x1 untuk Background Activity Launch.

### Gap yang terbukti

- `tapByText` memilih hasil pertama dan mengetuk tengah bounds;
- `tapFirstClickable` tidak mempunyai konteks;
- click belum memakai `ACTION_CLICK` sebagai jalur utama;
- tidak ada clickable ancestor resolver;
- tidak ada snapshot-scoped node ID;
- tidak ada selector score atau ambiguity stop;
- tidak ada precondition/postcondition;
- screenshot dan hierarchy tidak memiliki correlation snapshot;
- envelope beberapa command dapat terlihat sukses walaupun hasil internal gagal;
- screenshot masih dikirim sebagai base64 JSON;
- tidak ada transition trace before/after;
- belum ada stream MediaProjection/WebRTC;
- belum ada custom IME.

---

## 4. Keputusan Arsitektur APK

| ID | Keputusan |
|---|---|
| A1 | Outbound WSS tetap satu-satunya jalur produksi dari HP ke server |
| A2 | Tidak membuka HTTP/TCP server inbound pada HP |
| A3 | `AccessibilityNodeInfo.performAction()` menjadi jalur utama untuk click, focus, scroll, dan set text |
| A4 | `dispatchGesture` hanya fallback ber-policy |
| A5 | Node object tidak disimpan lintas snapshot |
| A6 | `node_id` selalu dipasangkan dengan `snapshot_id` |
| A7 | Screenshot dan hierarchy harus mempunyai correlation yang sama |
| A8 | APK tidak menyimpan screenshot/frame ke disk |
| A9 | Overlay visual on-device bukan jalur utama; APK mengirim metadata boxes untuk dirender web |
| A10 | `AgentOverlay` 1x1 tetap khusus Background Activity Launch dan tidak dicampur dengan inspector |
| A11 | Vision/LLM tidak berjalan di APK |
| A12 | Custom IME dan WebRTC ditunda sampai foundation lulus |
| A13 | Satu command mutasi aktif per device |
| A14 | Submit action harus mempunyai barrier dan tidak diulang dari APK |

---

## 5. Protocol Version 2

### 5.1 Envelope request

```json
{
  "protocol_version": 2,
  "request_id": "uuid-or-ulid",
  "cmd": "observe",
  "deadline_ms": 10000,
  "args": {}
}
```

### 5.2 Envelope response

```json
{
  "protocol_version": 2,
  "request_id": "uuid-or-ulid",
  "ok": false,
  "reason_code": "unstable_tree",
  "retryable": true,
  "result": {},
  "timing": {"started_at_ms": 0, "completed_at_ms": 0, "duration_ms": 0}
}
```

Aturan:

- top-level `ok` adalah kebenaran final command;
- `reason_code` wajib untuk `ok=false`;
- nested result tidak boleh bertentangan dengan top-level;
- request tanpa versi ditangani sebagai protocol v1 selama masa kompatibilitas;
- unknown protocol ditolak sebagai `protocol_mismatch`;
- response selalu mengembalikan `request_id` yang sama;
- deadline dan cancellation diperiksa sebelum langkah mahal.

### 5.3 Command baru

- `observe`
- `resolve`
- `actNode`
- `actAndVerify`
- `waitForNode`
- `waitForScreen`
- `cancelAction`
- `startDebugFrames`
- `stopDebugFrames`

Command legacy:

- `dump`
- `dumpWindows`
- `tap`
- `tapByText`
- `tapFirstClickable`
- `setText`
- `screenshot`

Command legacy tetap kompatibel sementara, tetapi tidak boleh dipakai app card produksi baru.

---

## 6. Snapshot Contract

```json
{
  "snapshot_id": "01J...",
  "frame_seq": 1842,
  "captured_at_ms": 0,
  "display": {
    "id": 0,
    "width": 1220,
    "height": 2712,
    "rotation": 0,
    "density_dpi": 440
  },
  "foreground": {
    "package": "com.facebook.katana",
    "window_id": 12
  },
  "tree_hash": "sha256:...",
  "screen_fingerprint": "...",
  "quality": {
    "valid": true,
    "invalid_bounds_ratio": 0.02,
    "window_changed": false
  },
  "nodes": [],
  "image": {
    "format": "jpeg",
    "width": 610,
    "height": 1356,
    "quality": 70,
    "binary_message_id": "img-1842"
  }
}
```

### Snapshot rules

- Enumerasi seluruh `windows` saat capability tersedia.
- Pilih roots dengan package dan type yang relevan.
- Tangkap orientasi dan display metrics sebelum dan sesudah collection.
- Tolak snapshot jika orientasi atau foreground berubah di tengah capture.
- Capture screenshot setelah overlay debug disembunyikan.
- Jangan memasukkan overlay 1x1 milik agent sebagai node target.
- Batasi traversal depth dan total node.
- Catat truncation secara eksplisit.
- Lepaskan `AccessibilityNodeInfo` sesuai lifecycle API yang didukung tanpa menyimpan reference.

---

## 7. Actionable Node Registry

### Node yang masuk registry

- clickable;
- long-clickable;
- editable;
- checkable;
- scrollable;
- focusable dengan supported action;
- child informatif yang memiliki clickable ancestor.

### Node yang ditolak

- disabled;
- bounds kosong atau negatif;
- sepenuhnya di luar viewport;
- area sangat kecil;
- package agent sendiri;
- node dekoratif tanpa semantic value;
- ancestor yang menutupi hampir seluruh layar tanpa anchor kuat.

### Stable key dalam satu snapshot

Hash input:

- display ID;
- window ID;
- package;
- view ID;
- class/role;
- normalized text;
- normalized content description;
- ancestor path;
- sibling ordinal;
- quantized bounds.

`node_id` bukan ID permanen dan tidak boleh digunakan pada snapshot lain.

---

## 8. Resolver

### Selector priority

1. view/resource ID exact;
2. content description exact;
3. text exact;
4. role plus ancestor/descendant relation;
5. locale alias;
6. zone/bounds relatif;
7. OCR/vision hint dari server;
8. raw coordinate fallback.

### Resolver output

- seluruh candidates;
- score;
- reasons;
- selected node;
- ambiguity state;
- clickable ancestor;
- fallback yang akan dipakai.

Jika dua kandidat teratas terlalu dekat, kembalikan `ambiguous`. Jangan memilih kandidat pertama.

---

## 9. Node Action Executor

Urutan click:

1. validasi request, deadline, dan cancellation;
2. pastikan package dan screen precondition cocok;
3. re-observe state minimal;
4. resolve node terhadap state terbaru;
5. pilih clickable ancestor yang aman;
6. panggil `ACTION_CLICK`;
7. bila action tidak didukung dan policy mengizinkan, hitung safe point;
8. jalankan `dispatchGesture`;
9. tunggu accessibility event atau settle detector;
10. ambil after snapshot;
11. evaluasi postcondition;
12. kembalikan hasil jujur.

`performAction=true` atau gesture callback sukses tidak cukup untuk menyatakan workflow sukses.

### Reason codes minimum

- `no_node`
- `ambiguous`
- `invalid_bounds`
- `stale_snapshot`
- `wrong_package`
- `unstable_tree`
- `action_not_supported`
- `action_rejected`
- `gesture_cancelled`
- `screen_not_changed`
- `unexpected_screen`
- `blocked_dialog`
- `timeout`
- `cancelled`
- `device_busy`
- `submit_barrier`
- `screenshot_failed`
- `protocol_mismatch`

---

## 10. Transition Verifier

### Settle detection

Screen dianggap settle bila:

- foreground package tetap;
- hierarchy hash stabil pada observasi berurutan;
- tidak ada window-state event yang belum tenang;
- postcondition anchor ditemukan;
- timeout belum habis.

Jangan memakai `Thread.sleep` tetap sebagai verifier. Sleep pendek hanya boleh menjadi debounce di dalam bounded polling.

### Screen fingerprint

Fingerprint berasal dari:

- package;
- window types;
- selected anchors;
- role counts;
- normalized stable texts;
- layout zones;
- optional perceptual image hash.

APK hanya menghasilkan observation primitives. Definisi screen/app card final tetap dimiliki server.

---

## 11. Debug Frame Transport

### Foundation

- Tambahkan dukungan binary WebSocket message.
- Metadata JSON dikirim lebih dahulu dengan `binary_message_id`.
- Image bytes dikirim sebagai binary frame terpisah.
- Terapkan bounded queue dan drop-old-frame.
- Satu frame menunggu ACK atau dibuang saat timeout.
- Tidak ada file temporary.
- Tidak ada frame dalam logcat.

### Default debug mode

- snapshot on demand;
- JPEG/WebP dengan scale dan quality configurable;
- frame rate rendah dan adaptif;
- berhenti otomatis saat subscriber hilang;
- accessibility command tetap prioritas terhadap frame.

### Live stream opsional

MediaProjection plus WebRTC hanya dimulai setelah seluruh gate foundation lulus.

- consent user wajib;
- foreground service type `mediaProjection`;
- session-scoped token;
- encrypted transport;
- stream dapat dihentikan dari HP;
- automation tidak bergantung pada stream;
- patuhi `FLAG_SECURE`.

---

## 12. File Plan

### Modify

- `app/src/main/java/com/gososmed/agent/AgentAccessibilityService.kt`
- `app/src/main/java/com/gososmed/agent/AgentCommand.kt`
- `app/src/main/java/com/gososmed/agent/AgentWsClient.kt`
- `app/src/main/java/com/gososmed/agent/HierarchySerializer.kt`
- `app/src/main/java/com/gososmed/agent/AgentForegroundService.kt` bila lifecycle command perlu diperluas
- `app/src/main/AndroidManifest.xml` hanya bila service/permission baru benar-benar diperlukan
- `app/src/main/res/xml/accessibility_service_config.xml`
- `docs/AGENT-COMMAND-CONTRACT.md`
- `CHANGELOG.md`

### Create

Nama final boleh mengikuti konvensi package, tetapi tanggung jawab tidak boleh dicampur:

- `SnapshotModels.kt`
- `SnapshotCollector.kt`
- `WindowRootCollector.kt`
- `ActionableNodeRegistry.kt`
- `NodeSelector.kt`
- `DeterministicResolver.kt`
- `NodeActionExecutor.kt`
- `ScreenTransitionVerifier.kt`
- `CommandCancellationRegistry.kt`
- `DebugFrameEncoder.kt`
- `ProtocolV2.kt`
- unit tests untuk setiap komponen;
- instrumentation tests untuk behavior Android yang tidak dapat dibuktikan JVM.

Jangan mengubah `AgentOverlay.kt` menjadi visual inspector.

---

## 13. Fase Eksekusi APK

### M0 — Baseline dan contract freeze

- Catat versi dari `app/build.gradle.kts`.
- Simpan fixture command v1 yang saat ini dipakai backend.
- Tambahkan regression test untuk response envelope yang salah.
- Tambahkan benchmark ukuran dump dan screenshot.
- Dokumentasikan command deprecation.

**Gate:** behavior v1 terdokumentasi dan seluruh test lama lulus.

### M1 — Protocol v2

- Implementasikan request/response envelope.
- Tambahkan request ID, deadline, reason code, dan retryability.
- Pertahankan adapter v1.
- Tambahkan capability `protocol_versions: [1,2]`.

**Gate:** golden JSON contract tests lulus.

### M2 — Snapshot collector

- Collect all windows.
- Tambahkan display/orientation metadata.
- Sinkronkan hierarchy dan screenshot.
- Tambahkan tree quality report.
- Tambahkan tree hash.

**Gate:** rotation/window-change menghasilkan snapshot invalid, bukan data palsu.

### M3 — Node registry dan resolver

- Filter actionable nodes.
- Buat node IDs.
- Implementasikan selector tiers dan candidate scores.
- Implementasikan ambiguity margin.
- Temukan clickable ancestor.

**Gate:** fixture overlap dan duplicate text selalu menghasilkan selected yang deterministik atau `ambiguous`.

### M4 — Act and verify

- Jalankan node action.
- Tambahkan gesture fallback policy.
- Tambahkan before/after observation.
- Tambahkan settle detector dan postcondition result.
- Tambahkan cancellation check.

**Gate:** screen tidak berubah menghasilkan `screen_not_changed`.

### M5 — Binary debug frames

- Tambahkan WebSocket binary send.
- Tambahkan metadata/frame correlation.
- Bounded queue, ACK, timeout, dan frame drop metrics.
- Pastikan no-disk policy lewat test.

**Gate:** long-running debug tidak menyebabkan memory growth tanpa batas.

### M6 — Hardening

- Satu mutation mutex/registry.
- Rate limit aksi.
- ANR guard.
- reconnect behavior.
- process crash recording.
- service restart recovery.

**Gate:** reconnect tidak mengulang command yang sudah selesai atau submit barrier.

### M7 — Real-device validation

Urutan validasi:

1. Settings atau aplikasi aman untuk primitive actions.
2. Threads navigation tanpa submit.
3. Threads submit terkontrol.
4. Satu platform berikutnya setelah Threads lulus.

Bukti wajib:

- log request/response;
- before/after snapshot IDs;
- reason codes;
- screenshot operator;
- package dan app version;
- hasil publikasi nyata bila menyentuh submit.

**Gate:** tidak ada stable tag sebelum real-device evidence dicatat di `CHANGELOG.md`.

### M8 — Optional IME dan WebRTC

Custom IME hanya bila kegagalan input terbukti. WebRTC hanya bila snapshot inspector belum cukup untuk kebutuhan operator.

**Gate:** masing-masing fitur mempunyai threat model, permission review, dan rollback terpisah.

---

## 14. Testing Matrix

### JVM unit tests

- protocol parsing;
- envelope truthfulness;
- node filter;
- node ID generation;
- selector scoring;
- ambiguity;
- clickable ancestor selection pada model tree;
- bounds validation;
- postcondition matcher;
- cancellation registry;
- bounded frame queue.

### Android instrumentation tests

- `ACTION_CLICK` pada clickable node;
- child ke clickable ancestor;
- multi-window dialog;
- IME/window overlay;
- rotation selama capture;
- node stale;
- screenshot denied/failure;
- gesture completion/cancellation;
- AccessibilityService reconnect.

### Real phone tests

- Xiaomi HyperOS Android 15;
- screen on/off;
- battery saver;
- Wi-Fi reconnect;
- app cold start;
- modal tak dikenal;
- locale Indonesia dan Inggris;
- target app update;
- process restart.

---

## 15. Performance Budget

Budget awal harus diukur pada M0, bukan diasumsikan.

Kontrol wajib:

- traversal bounded;
- actionable-only default;
- screenshot scale rendah untuk inspector;
- binary frames;
- no base64 untuk continuous debug;
- drop old frame;
- no screenshot bila tree dan postcondition cukup;
- no LLM di APK;
- no background stream tanpa subscriber.

---

## 16. Security dan Privacy

- Jangan membuka port inbound.
- Jangan log screenshot atau secret text.
- Jangan memasukkan password/OTP ke trace.
- Redact node text bertipe password.
- Batasi command berdasarkan allowlist.
- Validate package sebelum action.
- Pisahkan capability view dan control dalam handshake.
- Session token harus dapat dicabut.
- Stream berhenti ketika session berakhir.
- Jangan mencoba melewati `FLAG_SECURE`.
- Review permission baru sebelum manifest berubah.

---

## 17. Handoff ke Agent Backend

Setelah M1 atau perubahan contract apa pun, agent APK wajib menghasilkan:

1. versi protocol;
2. JSON fixtures request/response;
3. capability fixture;
4. daftar reason code;
5. command compatibility matrix;
6. APK version aktual;
7. commit SHA;
8. bukti test;
9. catatan behavior yang belum diuji pada device.

Simpan fixture netral di repo APK dalam `docs/contracts/` atau test resources. Agent backend menyalin spesifikasi, bukan source Kotlin.

Jangan mengubah env backend dari repo ini. Stable release APK dan update `GOSOSMED_AGENT_LATEST_VERSION` adalah maintenance window lintas repo yang dikoordinasikan manusia.

---

## 18. Definition of Done APK

Fase APK dianggap selesai bila:

- `gradle testDebugUnitTest assembleDebug` lulus;
- regression tests baru ada;
- `docs/AGENT-COMMAND-CONTRACT.md` sinkron;
- `CHANGELOG.md` diperbarui;
- tidak ada secret/build output;
- real-device gap dilaporkan jujur;
- tidak ada edit monorepo;
- satu commit scoped;
- stable tag hanya setelah real phone verification;
- backend menerima contract handoff lengkap.

---

## 19. Stop Conditions

Coding agent harus berhenti dan meminta keputusan jika:

- membutuhkan izin Android baru yang sensitif;
- protocol v2 tidak dapat kompatibel dengan v1;
- menemukan kebutuhan menyimpan screenshot;
- perlu menyalin kode AGPL;
- perlu mengubah database/backend;
- perubahan dapat menyebabkan submit ulang;
- test real-device menunjukkan false success;
- working tree memiliki perubahan user pada file yang sama.

---

## 20. Urutan Final

```text
M0 baseline
  -> M1 protocol v2
  -> M2 snapshot
  -> M3 node resolver
  -> M4 act+verify
  -> handoff backend
  -> M5 binary frames
  -> M6 hardening
  -> M7 device validation
  -> M8 IME/WebRTC only if proven necessary
```

Jangan mulai WebRTC, custom IME, stealth, atau macro sebelum `actAndVerify` lulus pada perangkat nyata.