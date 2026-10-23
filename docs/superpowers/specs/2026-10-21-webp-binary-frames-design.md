# DRAFT — Desain Frame Biner WebP untuk GoSosmed Agent

> **Status: DRAFT — not approved for implementation.**
> Tanggal analisis: 2026-10-21.
> Dokumen ini mengusulkan label sementara untuk siklus berikutnya, bukan sumber build yang sudah ada. Tidak ada kode, versi, atau rilis yang diubah oleh dokumen ini.

## 1. Ringkasan keputusan

Fitur yang diusulkan adalah **frame gambar biner yang jujur dan terkendali** untuk `observe` dan `startDebugFrames`/`stopDebugFrames`:

- Tetap memakai `AccessibilityService.takeScreenshot()`; **tidak** menambah `MediaProjection`, H.264, MediaCodec, WebCodecs, `START_COCKPIT`, byte header `0x01`/`0x02`, atau pilihan mode baru di APK pada siklus ini.
- JPEG tetap default. PNG tetap tersedia untuk kompatibilitas. **WebP lossy** ditambahkan sebagai opsi eksplisit, hanya di API 30+.
- Field metadata OBSERVE yang sudah didefinisikan dalam fixture, yaitu `include_image`, `image_format`, `image_quality`, dan `image_scale`, menjadi nama kanonik. Nama lama hanya diterima sebagai alias pada OBSERVE.
- `observe` mengirim satu metadata + satu payload biner **sebagai respons langsung**, bukan lewat antrean live.
- Streaming live maksimal **satu sesi aktif**, hanya **1–3 FPS**, satu frame in-flight yang menunggu ACK server, dan berhenti saat ACK terlambat atau koneksi gagal.
- Klaim ukuran 15–25 KB/frame dan 2–3 FPS dari lampiran belum diverifikasi. Dokumen ini tidak menjanjikannya.

Alasan utamanya: WebP saja tidak memperbaiki kenyataan bahwa saat ini START/STOP dapat melaporkan sukses palsu, OBSERVE sukses walaupun capture gagal, format tak dikenal diam-diam menjadi PNG, dan stream mengirim bytes tanpa metadata maupun flow control.

## 2. Kondisi repo dan konteks versi

Bagian ini adalah snapshot analisis 2026-10-21; angka versi harus dibaca ulang dari `app/build.gradle.kts` sebelum implementasi.

| Fakta | Bukti saat analisis |
|---|---|
| Produksi | Tag `v1.2.2`, `versionCode = 36`, `versionName = "1.2.2"` |
| Salinan lokal | Branch `dev`, bersih, sinkron dengan `origin/dev`, HEAD commit rilis `v1.2.2` |
| `v1.2.3` | **Tidak ditemukan** sebagai tag, branch, atau commit lokal/remote |
| `minSdk` / `targetSdk` / `compileSdk` | 26 / 34 / 34 |

**Prasyarat sebelum implementasi:** tentukan sumber `v1.2.3` yang dimaksud. Jika salinan `v1.2.3` ada di mesin lain, pekerjaan harus dimulai dari commit itu, bukan dari HEAD `v1.2.2` ini, agar perubahan tidak hilang atau terduplikasi. Jika salinan itu berasal dari branch `dev` yang belum di-push, minta commit SHA dan jangan bangun di atas salinan itu sampai commit tersebut dipush. Jangan menaikkan versi secara otomatis sebelum pihak yang meminta fitur mengonfirmasi sumber tersebut.

Karena `v1.2.3` belum ada sebagai tag, rilis berikutnya yang lebih tinggi dari `versionCode = 36` dapat memakai `versionCode = 37`; label `-dev` ditentukan oleh `versionName`, bukan `versionCode`. Label final menunggu konfirmasi agar tidak menimpa build dev yang dibuat di mesin lain. Satu build lokal tanpa tag tidak membuktikan bahwa build itu satu-satunya `1.2.3`.

`README.md`, `CHANGELOG.md`, dan kode saat ini masih menyatakan `v1.2.2`. Itu konsisten dengan status repo, tetapi bertentangan dengan laporan pengguna tentang `v1.2.3` di mode dev. Repo ini tidak dapat membuktikan bahwa build dev `1.2.3` yang dilaporkan identik dengan HEAD ini. Build itu bisa berasal dari working tree yang belum dipush atau mesin lain.

## 3. Temuan kode yang harus diperbaiki

| Area | Keadaan saat ini | Akibat |
|---|---|---|
| `AgentCommand.kt`, handler V2 START/STOP | Selalu membentuk respons `ok: true` dengan `"started"`/`"stopped"` | Klien tidak tahu apakah stream benar-benar berjalan |
| `AgentAccessibilityService.takeScreenshotRawBytes` | Format selain JPEG/JPG jatuh ke PNG, termasuk `webp` dan format salah ketik | Metadata/format bisa bohong |
| Pipeline capture | Proses latch → HardwareBuffer → Bitmap → scale → compress ditulis dua kali | Perbaikan rawan hanya diterapkan pada satu jalur |
| `AgentCommand.kt`, OBSERVE | Hanya membaca `screenshot`, `format`, `quality`, `scale`; capture gagal diabaikan; snapshot selalu didaftarkan; frame masuk antrean live global | Respons bisa sukses tanpa gambar, dan OBSERVE bercampur dengan stream |
| `AgentWsClient.kt`, stream | Hasil `send` diabaikan, tidak ada metadata atau ACK, memakai `delay(1000/fps)`, START kedua menimpa stream lama | Kirim cepat tanpa backpressure; urutan/identitas frame tidak dapat dipertanggungjawabkan |
| `AgentWsClient.kt`, OBSERVE | Mengosongkan queue global, mengirim bytes, lalu melakukan ACK lokal | ACK palsu, dan frame OBSERVE bisa tertukar dengan frame stream |
| `DebugFrameEncoder.kt` | Queue drop-oldest, ACK hanya berdasarkan ID, timeout 10 detik, `clear()` tidak mereset statistik | Menyembunyikan frame baru yang lossy, ACK sesi lain bisa diterima, metrik antar-sesi bercampur |
| `SnapshotImage` | Belum menyimpan scale yang benar-benar diterapkan | Server tidak bisa memetakan koordinat preview ke layar |
| `docs/contracts/v2-fixtures.json` | OBSERVE sudah memakai nama `include_image`/`image_format`; START belum meminta session; STOP memakai `session_id` | Kontrak kode berbeda dari fixture |

Catatan: unit test di host JVM tidak membuktikan encoding WebP perangkat, rate limit `takeScreenshot()` pada OEM, maupun perilaku network/ACK di HP nyata.

### 3.1 Ketidaksesuaian kontrak publik yang ditemukan

| Sumber | Klaim | Keadaan yang harus diperbaiki |
|---|---|---|
| `docs/AGENT-COMMAND-CONTRACT.md` §10.4 | `observe` menerima `include_image`, `max_depth`, dan mengembalikan `quality`, `image` | Kode OBSERVE belum membaca `include_image` atau `max_depth`, dan `image` tidak dipasangkan dengan `frame_meta`; kontrak harus diperbarui sebagai T9 sebelum fitur dinyatakan selesai |
| `docs/AGENT-COMMAND-CONTRACT.md` §3.3 | `screenshot` "digantikan" oleh `observe` dan `startDebugFrames` | Kegunaan v1 `screenshot` tetap tersedia di APK; kata "digantikan" hanya kebijakan untuk kartu baru, bukan bukti bahwa command lama tidak lagi berfungsi |
| `docs/AGENT-COMMAND-CONTRACT.md` §10.4 | `startDebugFrames` memakai alias lama serta drop-oldest dan eviksi ACK 10 detik | Kode memang memakai alias lama, tetapi `format: "webp"` saat ini jatuh ke PNG dan ACK tidak terikat pada sesi; kontrak baru yang kanonik harus menggantikan klaim tersebut |
| Lampiran pengguna | 1–3 FPS dan ukuran 10–55 KB | Belum ada pengukuran dari repo atau HP yang dapat membuktikan angka tersebut; angka bukan jaminan produk |

## 4. Tujuan dan non-tujuan

### 4.1 Tujuan

1. Satu pipeline capture yang dapat diuji pada batas konfigurasi/encoding, dengan WebP lossy opt-in.
2. Format, quality, scale, dan dimensi dalam metadata mencerminkan nilai yang benar-benar diterapkan.
3. OBSERVE berhasil hanya bila semua bagian yang diminta berhasil; kegagalan capture bersifat eksplisit dan **tidak** mendaftarkan snapshot.
4. Streaming live memiliki identitas sesi, urutan frame, tidak ada frame in-flight ganda, dan berhenti jujur saat alirannya gagal.
5. Kompatibilitas lama dipertahankan: `screenshot` v1 tidak berubah, dan OBSERVE tanpa gambar tetap berfungsi.

### 4.2 Prasyarat platform dan lintasan uji
- Hanya perintah gambar V2 (`observe` dengan gambar dan `startDebugFrames`) yang memakai pipeline baru ini. Keduanya membutuhkan API 30+ karena `AccessibilityService.takeScreenshot` mulai tersedia di API 30. Pada API 26–29, gambar V2 ditolak eksplisit dengan `action_not_supported`, terlepas dari formatnya; tidak ada fallback ke command legacy atau format lain.
- Perintah legacy v1 `screenshot` dan `annotatedScreenshot` tidak berubah. Aturan penolakan format kanonik pada §6.1 tidak boleh diterapkan diam-diam ke kontrol/format legacy.
- Pipeline produksi tetap membaca service melalui `AgentAccessibilityService.instance`. Batas capture harus dapat diganti dengan provider palsu pada unit test JVM; tes JVM tidak boleh memanggil `Bitmap` atau kelas platform Android yang di-stub.

### 4.3 Non-tujuan siklus ini

- MediaProjection, MediaCodec, H.264, WebCodecs, keyframe/delta, 30–60 FPS.
- Mengubah UI APK menjadi pemilih mode. Mode tidak diperlukan bila fitur format dipilih per perintah.
- Header biner `0x01`/`0x02` atau command `START_COCKPIT`.
- Record layar, file di disk, cache frame, atau penyimpanan screenshot di HP.
- Perubahan backend atau browser. Keduanya berada di monorepo privat dan merupakan tugas terpisah.
- Handshake versi penuh. Satu kapabilitas non-live `binary_frames_v1` didefinisikan sebagai pilihan untuk fase backend; lihat §6.6. Sampai backend privat lulus gate, nilai yang dikirim tetap `[]`.

## 5. Alternatif yang dipertimbangkan

| Alternatif | Penilaian |
|---|---|
| **Level 1: Accessibility screenshot + WebP opt-in + kontrak biner/ACK** | **Dipilih.** Tidak menambah izin, bisa memakai fondasi metadata/queue yang ada |
| Hanya memperbaiki pemrosesan gambar OBSERVE di APK, tanpa stream live | Boleh menjadi subfase bila backend belum siap. OBSERVE tetap dapat diuji dengan mock lokal; stream live ditunda |
| JPEG saja, tanpa perubahan protokol | Ditolak: tidak memperbaiki format salah, metadata palsu, ACK, dan START palsu |
| WebP menggantikan JPEG secara default | Ditolak: encoding perangkat dan kompatibilitas belum diverifikasi; WebP API 30+, sementara APK mendukung API 26 |
| Header byte pada setiap biner | Ditolak: payload legacy tidak terikat metadata; menebak protokol dari byte pertama rawan collision |
| MediaProjection + H.264 | Ditunda: menambah izin, lifecycle, surface/encoder, dan format bitstream; masalah kontrak lebih dulu harus benar |
| OBSERVE ikut masuk antrean live | Ditolak: respons satu-request dan stream kontinu memiliki aturan backpressure berbeda |

## 6. Kontrak yang diusulkan

### 6.1 Parameter gambar

Field kanonik OBSERVE. `annotatedScreenshot` hanya mode baca; perintah gambar V2 yang berubah pada siklus ini adalah OBSERVE dan START. START memakai `image_format`, `image_quality`, `image_scale`, `fps`, dan `session_id`; `include_image` tidak berlaku karena START selalu berarti gambar.

| Field | Tipe | Default | Aturan |
|---|---|---|---|
| `include_image` | boolean | `false` | OBSERVE saja. Jika `true`, capture wajib berhasil; jika tidak ada/tidak `true`, hanya hierarki yang dikirim |
| `image_format` | string | `jpeg` | `jpeg`, `png`, atau `webp`, tidak peka huruf besar/kecil |
| `image_quality` | integer | `75` | 1–100; diabaikan untuk PNG dan dilaporkan `null` |
| `image_scale` | number | `0.5` | 0,2–1,0; nilai di luar rentang adalah kesalahan, bukan clamp diam-diam |

Normalisasi:

- `jpg` menjadi `jpeg`; output metadata selalu memakai nama kanonik.
- `webp` hanya untuk API 30+ yang mendukung `Bitmap.CompressFormat.WEBP_LOSSY`; di bawahnya ditolak.
- Format tak dikenal ditolak sebagai `invalid_image_arg`. Tidak boleh diam-diam menjadi PNG. `unsupported_image_format` hanya dipakai untuk format yang dikenali (`jpeg`/`jpg`/`png`/`webp`) tetapi tidak dapat dipakai oleh runtime, misalnya WebP di API < 30 atau probe encoder yang gagal.
- `include_image` yang tidak ada berarti `false`. Opsi `image_format`, `image_quality`, atau `image_scale` pada OBSERVE yang memiliki `include_image != true` ditolak dengan `action_rejected` dan `result.error: "image_options_require_include_image"`, bukan diabaikan. Aturan ini hanya untuk OBSERVE. `annotatedScreenshot` dan perintah legacy tidak ikut berubah. `startDebugFrames` selalu berarti gambar, sehingga field `include_image` tidak dipakai di sana.
- Konfigurasi internal yang berhasil menormalisasi scale dipakai untuk resize dan dilaporkan di `source_scale`.
- Toleransi pembulatan: `source_scale` harus sama dengan scale request setelah validasi; dimensi dapat berbeda beberapa piksel karena pembulatan.

Kompatibilitas OBSERVE:

- Jika field kanonik dan alias untuk dimensi yang sama hadir, nilainya harus setara setelah normalisasi (`jpg` setara `jpeg`, `70` setara `70.0`). Nilai yang berbeda adalah `conflicting_image_args`; tidak ada sumber yang menang secara diam-diam.
- `include_image: false` hadir bersama `screenshot: true` adalah `conflicting_image_args`.
- Alias berikut diterima untuk siklus migrasi: `screenshot` → `include_image`, `format` → `image_format`, `quality` → `image_quality`, `scale` → `image_scale`.
- `screenshot` tidak hadir membuat opsi alias format/quality/scale tidak valid. `screenshot: false` bersama alias format/quality/scale adalah `conflicting_image_args`. Alias format/quality/scale hanya sah bila gambar diaktifkan oleh `include_image: true` atau `screenshot: true`.
- `include_image: false` atau `screenshot: false` tanpa alias format/quality/scale adalah permintaan tanpa gambar, bukan konflik.
- Nilai tipe salah (misalnya `image_quality: "70"` atau `image_scale: "0.5"`) ditolak. Parser tidak melakukan cast diam-diam. Field kanonik bernilai `null` juga salah tipe.
- Kontrak legacy `screenshot` v1 tidak disentuh.

Urutan validasi OBSERVE bersifat deterministik. Error yang dilaporkan mengikuti urutan berikut; hanya cabang pertama yang cocok yang dipakai:

1. Field yang hadir memiliki tipe atau rentang salah: `invalid_image_arg`.
2. Kanonik/alias untuk dimensi yang sama bertentangan: `conflicting_image_args`.
3. Opsi gambar hadir saat gambar tidak diminta: `image_options_require_include_image`.
4. Format yang sudah valid tidak didukung runtime: `action_not_supported` + `unsupported_image_format`.

Untuk gambar V2 di API 26–29, probe runtime selalu menjadi langkah terakhir. Karena WebP juga tidak tersedia di sana, permintaan `webp` pada API 26–29 melaporkan `action_not_supported` + `unsupported_image_format`. Permintaan `jpeg` atau `png` pada API 26–29 melaporkan `action_not_supported` tanpa `result.error` format.

Tabel error konfigurasi OBSERVE (`ok: false`, `reason_code: "action_rejected"`):

| Penyebab | `result.error` |
|---|---|
| format bukan string atau bukan salah satu `jpeg`/`jpg`/`png`/`webp` | `invalid_image_arg` (bukan `unsupported_image_format`) |
| `image_quality` bukan integer atau di luar 1–100 | `invalid_image_arg` |
| `image_scale` bukan number (termasuk `NaN`, `Infinity`, atau boolean) atau di luar 0,2–1,0 | `invalid_image_arg` |
| kanonik dan alias untuk dimensi yang sama berbeda nilai | `conflicting_image_args` |
| opsi format/quality/scale hadir tanpa gambar yang diaktifkan | `image_options_require_include_image` |
| `include_image`/`screenshot` bukan boolean | `invalid_image_arg` |

`invalid_image_arg` adalah satu-satunya kode untuk tipe atau rentang nilai yang salah. Kode seperti `invalid_format`, `invalid_quality`, `invalid_scale`, `invalid_include_image`, atau `unsupported_format` tidak dipakai pada kontrak ini.

Tabel error dukungan runtime (`reason_code` bukan `action_rejected`):

| Keadaan | `reason_code` | `result.error` |
|---|---|---|
| API 26–29 untuk gambar V2 berformat `jpeg`/`jpg`/`png` | `action_not_supported` | tidak ada |
| `webp` diminta pada API < 30 atau probe encoder gagal | `action_not_supported` | `unsupported_image_format` |

### 6.2 OBSERVE

Contoh request:

```json
{
  "protocol_version": 2,
  "request_id": "req-observe-001",
  "cmd": "observe",
  "args": {
    "include_nodes": true,
    "include_image": true,
    "image_format": "webp",
    "image_quality": 70,
    "image_scale": 0.5
  }
}
```

Urutan sukses di WebSocket:

1. Satu text message berisi envelope V2 `ok: true` yang telah selesai dieksekusi.
2. Satu text message `type: "frame_meta"`.
3. Satu binary message berisi bytes gambar.

`result.image.binary_message_id` menunjuk ke `binary_message_id` di metadata. `result.image.format`, `width`, `height`, `quality: Int?` (`null` untuk PNG), `source_width`, `source_height`, dan `source_scale` dilaporkan dari hasil pipeline (termasuk normalisasi). `result.frame_seq` terus memakai urutan snapshot yang ada **hanya bila gambar didaftarkan**; pada `include_image: false` tidak ada gambar sehingga `frame_seq` tidak maju. Nilai itu harus sama dengan `frame_meta.frame_seq`; urutan snapshot bukan urutan frame live.

`result` juga memuat `state_before_capture` dan `state_after_capture`. Masing-masing berisi `foreground_package` dan `rotation`, keduanya boleh `null`. `capture_elapsed_ms` adalah durasi capture + encode. Jika ada perbedaan state, `result.state_changed_during_capture` bernilai `true`, `result.state_change` memuat daftar field yang berubah (`foreground`, `rotation`, `snapshot`), dan pemanggil **tidak boleh** menganggap koordinat hierarchy masih berlaku untuk gambar itu. Capture tetap dikirim supaya klien melihat layar yang benar-benar tertangkap, bukan layar lain yang dipilih ulang diam-diam.

`JSONObject.NULL` hanya diterima pada operasi (`.toString()`) sebagai `quality: null` yang sah untuk PNG. Representasi internal `SnapshotImage.quality` menjadi `Int?`; `fromJson` harus membaca `has` + `isNull` dan bukan `getInt()` tanpa pengecekan. Test lama yang memakai constructor integer tetap kompilasi.

Field `result.image.quality` adalah `null` hanya untuk PNG. `JSONObject.getInt` tidak dipakai untuk membacanya di test/klien; gunakan `isNull` sebelum membaca. Bytes tetap dipasangkan dengan metadata, bukan ganda di dalam JSON.

Jika `include_image: false`, hierarki tetap dikumpulkan dan didaftarkan, dan **tidak ada** text `frame_meta` maupun binary yang dikirim. Capture tidak dipanggil.

Jika `include_image` bernilai `true` tetapi capture gagal:

- Tidak ada JSON sukses, metadata, atau biner.
- Respons: `ok: false`, `reason_code: "screenshot_failed"`, `retryable: true`, `result.error` berisi kode penyebab yang tidak sensitif.
- Hierarchy tidak didaftarkan sebagai snapshot baru. Snapshot sebelumnya tidak dihapus.

OBSERVE tidak boleh mengambil frame dari antrean stream dan tidak memakai ACK live. Respons OBSERVE harus selesai dieksekusi sebelum pengiriman metadata/biner; proses pengiriman binary berada di coroutine/di luar `runBlocking` router agar tidak menahan pembaca WebSocket.

Mekanisme metadata OBSERVE ke transport:

| Lapisan | Tanggung jawab | Alasan |
|---|---|---|
| `AgentCommand.executeV2(...)` | Validasi, gate capture, ambil hierarchy, capture, baca ulang state, registrasi, lalu bentuk envelope dan metadata | Tetap `suspend`; tanpa `AsyncTask` atau job UI |
| Hasil internal `executeV2` | `V2Execution.ObserveImage(envelope, bytes, metadata)` atau `V2Execution.Json(envelope)` | Bytes tidak dibuang ke queue global atau field statis |
| `AgentWsClient` | `send(envelope)`, lalu metadata, lalu biner pada satu jalur; ACK live tidak pernah dipanggil | Urutan dan pemilihan socket bisa diuji |
| Tambahkan `AgentCommand.executeV2Execution(...)` | `executeV2` hanya mengembalikan envelope dari hasil internal yang sama | Pemanggil lama dan `runBlocking` tetap kompatibel |

### 6.3 START dan STOP

START request:

```json
{
  "protocol_version": 2,
  "request_id": "req-startframes-001",
  "cmd": "startDebugFrames",
  "args": {
    "session_id": "dbg-sess-001",
    "fps": 2,
    "image_format": "webp",
    "image_quality": 70,
    "image_scale": 0.5
  }
}
```

START hanya berhasil setelah seluruh langkah validasi dan capture/encode uji pertama berhasil. Scheduler baru dimulai **setelah** `finish()` respons START berhasil. START hanya berhasil jika:

- `session_id` memiliki 1–128 karakter;
- `fps` integer 1–3, default 2;
- konfigurasi format/quality/scale valid dan WebP didukung perangkat;
- `AccessibilityService` tersedia;
- tidak ada stream lain aktif pada koneksi ini.

START kedua saat stream masih aktif mengembalikan `ok: false`, `reason_code: "device_busy"`, dan `result.error: "stream_already_active"`. Pemanggil harus melakukan STOP dahulu. Respons sukses mengembalikan `streaming: true`, `session_id`, `local_session_id`, dan nilai efektif fps/format/quality/scale. Respons error START kedua hanya membawa `stream_already_active` tanpa menjalankan handler stream kedua.

Frame hasil capture validasi tidak dibuang dan tidak dikirim sebelum respons START bisa dipastikan tayang. Urutannya: reserve sesi, capture + encode, simpan frame sebagai `READY`, kirim respons START, tunggu `finish()` berhasil, lalu kirim frame itu sebagai frame live pertama dan masukkan ke `WAITING_ACK`. Scheduler baru membunyikan slot berikutnya setelah frame pertama ACK (selama menunggu ACK, slot berikutnya dicatat `missed_slot_count`). Bila `finish()` gagal sebelum frame dikirim, sesi dibatalkan lalu dihentikan dengan `stop_reason: "send_failed"`: capture tidak dikirim ulang, dan tidak ada bytes yang dikirim tanpa ACK pending. Dengan demikian tidak ada capture validasi ganda, dan START sukses membuktikan capture pertama benar-benar menghasilkan bytes.

STOP hanya berhasil bila `session_id` cocok dengan stream aktif dan merupakan koneksi yang sama. Jika tidak ada stream aktif, STOP tidak melakukan perubahan apa pun dan mengembalikan sukses idempoten dengan `was_streaming: false`; semua statistik bernilai 0 atau `last_stats` sesi terakhir. Jika `session_id` STOP sama dengan sesi yang baru saja berhenti sendiri, STOP tetap sukses dan mengembalikan `last_stats`. Bila tidak cocok, kembalikan `ok: false`, `reason_code: "device_busy"`, dengan `result.error: "session_mismatch"`; stream aktif tidak dihentikan. STOP berhasil setelah job berhenti, antrean dibersihkan, ACK waiter dibatalkan, dan statistik sesi dibekukan; respons dikirim sesudah proses tersebut selesai.

Respons STOP memuat `was_streaming`, `last_stats`, `frames_meta_sent`, `frames_sent`, `frames_acked`, `pending_unacked`, `dropped_newest_count`, `capture_failed_count`, `missed_slot_count`, `ack_timeout_count`, `ack_refused_count`, dan `stop_reason`. Metrik transport dibedakan secara eksplisit:

- `frames_meta_sent` bertambah saat `send(frame_meta)` berhasil.
- `frames_sent` bertambah saat `send(binary)` berhasil.
- `frames_acked` bertambah saat ACK tervalidasi.
- `ack_refused_count` bertambah saat ACK mencapai sesi aktif tetapi ID atau `session_id` tidak cocok.

`pending_unacked` adalah 0 atau 1 karena hanya boleh ada satu frame `WAITING_ACK`. Batas ACK diukur sejak frame masuk `WAITING_ACK`; timer berjalan `>= 2000 ms` berarti timeout, bukan masih menunggu. Waktu menyusun metadata, menunggu slot, dan menunggu antrean capture tidak memperpanjang batas.

Rumus error yang dipakai:

- START saat stream aktif, service tidak siap, atau STOP dengan session tidak cocok: `device_busy`.
- START kedua: `result.error: "stream_already_active"`.
- OBSERVE yang meminta gambar saat capture live sedang `CAPTURING` dan capture gate benar-benar dipegang: menunggu gate selesai, lalu mengambil capture OBSERVE sendiri. Gate hanya menyerialkan capture; OBSERVE tidak ditolak karena live sedang `WAITING_ACK` atau `DRAINING`. Jika penantian gate melewati batas 5 detik, OBSERVE mengembalikan `device_busy`, `result.error: "screen_capture_busy"`, `retryable: true`, tanpa capture sehingga `frame_seq` tidak berubah.
- Parameter tidak valid atau `session_id` kosong: `action_rejected`, `retryable: false`.
- Format tidak didukung oleh level API: `action_not_supported`, `retryable: false`.
- Capture atau metadata frame gagal sesudah respons START sukses pada koneksi yang masih hidup: stream dihentikan dengan jujur, `AgentLog` menerima satu event `live_stream_stopped` berisi `stop_reason` + statistik agregat (tanpa bytes/session_id), dan tidak ada retry diam-diam. Error capture saat START hanya dilaporkan di respons START gagal.

Pesan error tidak boleh mengklaim stream baru selesai berhenti bila respons gagal sebelum stop.

### 6.4 Metadata dan binary live

```json
{
  "type": "frame_meta",
  "binary_message_id": "s14:7",
  "session_id": "dbg-sess-001",
  "local_session_id": 14,
  "frame_seq": 7,
  "timestamp_ms": 1728000000123,
  "format": "webp",
  "width": 540,
  "height": 1200,
  "source_width": 1080,
  "source_height": 2400,
  "source_scale": 0.5,
  "quality": 70,
  "byte_length": 18342
}
```

Aturan:

- ID live adalah `s<local_session_id>:<frame_seq>`; ID OBSERVE tetap `snap-img-<snapshot_id>`. ID ini unik di dalam koneksi dan **bukan token keamanan**, sehingga server tetap memvalidasi `session_id` pada ACK. Agar tidak bentrok setelah stream baru, `SnapshotCollector` memerlukan satu counter `frameSeq` global yang monoton; `local_session_id` dan `frame_seq` tidak boleh berasal dari timer yang bisa berulang atau dari counter yang bisa reset pada reconnect.
- Untuk OBSERVE, `binary_message_id` dibentuk dari counter snapshot internal, bukan dari `request_id` maupun `session_id` bebas.
- `local_session_id` adalah counter positif per koneksi, tidak dipakai ulang selama objek klien hidup.
- `session_id` berasal dari server, tidak kosong, dan maksimal 128 karakter.
- `frame_meta.frame_seq` live adalah snapshot `frameSeq` global `SnapshotCollector` saat capture, mulai 1 atau nilai berikutnya, dan sama dengan `SnapshotRegistry`; ia tidak boleh menyamai frame yang masih in-flight.
- `timestamp_ms` adalah epoch milliseconds saat capture berhasil.
- `width`/`height` adalah dimensi output setelah scale; `source_width`/`source_height` berasal dari bitmap screenshot sebelum scale, bukan `Display` yang bisa keliru pada landscape; `source_scale` adalah rasio lebar output/sumber.
- `format`, `quality`, `width`, `height`, `source_width`, `source_height`, dan `source_scale` adalah nilai efektif.
- `byte_length` harus sama dengan panjang binary.
- Tidak ada header byte pada payload. Metadata dan biner dipasangkan oleh `binary_message_id`; menerima pesan biner tidak boleh dianggap menerima gambar yang valid.
- Tidak ada frame/bytes di logcat atau log UI. Log hanya berisi event, jumlah byte, dan kode kesalahan.
- Batas protokol: `binary_message_id` 1–160 karakter ASCII, `session_id` 1–128 karakter Unicode, `local_session_id`/`frame_seq` bilangan bulat positif, `width`/`height`/`source_width`/`source_height` > 0, `source_scale` > 0, `timestamp_ms` >= 0, dan payload 1–524.288 byte. Metadata lebih besar dari 16 KiB atau keluar dari batas ini adalah kegagalan protokol internal yang menutup stream, bukan data parsial.
- Slot scheduler bukan nomor frame dan tidak memakai counter sendiri. Frame yang gagal capture, dibuang, atau missed tidak dapat menjadi metadata, jadi bukan celah pada identitas frame. `binary_message_id` memakai format `s<local_session_id>:<frame_seq>`, dengan `frame_seq` dari capture. ID unik dalam koneksi karena satu `local_session_id` hanya aktif saat satu frame in-flight.

### 6.5 ACK server

```json
{
  "type": "ack_frame",
  "binary_message_id": "s14:7",
  "session_id": "dbg-sess-001"
}
```

ACK sah hanya bila keempat syarat berikut benar: koneksi WebSocket sama, token sesi lokal sama, `binary_message_id` sama dengan satu frame in-flight, dan `session_id` sama dengan sesi aktif. `cmd: "ack_frame"` tidak diterima; hanya `type: "ack_frame"`. ACK ganda atau ACK sesi lain diabaikan. Setelah metadata/biner terkirim, stream menunggu ACK maksimal **2 detik** (`elapsed >= 2000` adalah timeout). Tanpa ACK, stream berhenti untuk sesi tersebut, antrean dibersihkan, dan **tidak ada retry/resend**. `frames_acked` adalah jumlah ACK tervalidasi per sesi (reset pada START baru). `frames_sent` **tidak** berarti ACK.

### 6.6 Kapabilitas

`capabilities.binary_frames` default `[]`. Nilai `["binary_frames_v1"]` hanya dipublikasikan setelah backend privat menerima `frame_meta`, memasangkan binary tanpa header, memproses ACK ber-sesi, dan gate backend lulus. Nilai itu berarti APK mampu membuat `frame_meta`, memasangkan binary, dan memproses `ack_frame` ber-sesi; **bukan** jaminan WebP atau stream aktif pada device tertentu. `webp_lossy` tidak diklaim hanya dari versi API. Server harus memilih format per request. Legacy v1 tidak memakai metadata.

Server lama boleh mengabaikan field ini. Server yang belum menerima `binary_frames_v1` tidak boleh mengaktifkan jalur binary/ACK terhadap APK ini; server lama tetap dapat memakai `observe` tanpa gambar atau format JPEG lewat kontrak lama.

## 7. Model aliran dan urutan pengiriman

### 7.1 OBSERVE

```text
request
  -> validasi konfigurasi
  -> baca state A (foreground + rotasi)
  -> baca hierarchy
  -> baca state B (foreground + rotasi + `SnapshotCollector` saat ini)
  -> ambil capture gate
  -> capture + encode (pipeline tunggal)
  -> baca state C (foreground + rotasi + `SnapshotCollector`)
  -> bandingkan state sebelum dan sesudah capture
  -> bangun snapshot (tanpa register) / gagal eksplisit
  -> register snapshot (lepas gate di `finally`)
  -> kirim JSON respons utuh
  -> kirim frame_meta
  -> kirim bytes
  -> tidak ada ACK yang dibuat lokal
```

Kegagalan capture terjadi sebelum respons sukses dan gate dilepas di `finally` tanpa registrasi snapshot. Jika pengiriman metadata/biner gagal setelah respons sukses, server wajib menganggap respons tersebut tidak lengkap; OBSERVE tidak membuat ACK lokal. Jika binary ternyata tidak pernah tiba, frame itu tidak boleh dirender; server dapat mengulang OBSERVE dengan `request_id` baru.

State B adalah pembanding langsung terhadap capture. Perbedaan state A dan B tidak membatalkan OBSERVE, tetapi dilaporkan sebagai perubahan state. `SnapshotCollector.capture` tetap mencatat waktu capture asli, bukan waktu sesudah pembacaan state C.

### 7.2 Stream live

```text
idle
  -> START valid + service siap
  -> ambil capture gate
  -> reserve sesi + token baru (sinkron)
  -> capture + encode frame pertama + registrasi snapshot
  -> lepas capture gate + simpan frame sebagai READY
  -> kirim respons START ok -> tunggu finish() berhasil
  -> kirim frame READY -> WAITING_ACK -> mulai scheduler
  -> slot READY -> ambil capture gate -> capture -> lepas gate -> enqueue(capacity 1)
  -> drain satu frame -> send(frame_meta JSON) -> send(bytes)
  -> tunggu ACK (2s) atau STOP/disconnect/failure
  -> selesai jujur: job berhenti; queue/koneksi dibersihkan
```

Scheduler memakai `System.nanoTime` dan target waktu (`start + n * 1e9 / fps`). Bila deadline sudah lewat, **tidak ada catch-up burst**; frame terlewat dicatat sebagai `missed`, dan target maju ke slot berikutnya. Tidak ada `delay` per frame sebagai pengganti jadwal absolut.

Antrean/penanda "frame berikutnya" hanya berkapasitas satu:

- Jika masih ada frame yang belum diambil, frame baru dibuang (**drop-newest**).
- Saat satu frame menunggu ACK, capture berikutnya tidak boleh menambah satu frame in-flight lagi; kapasitas 1 berlaku juga untuk in-flight. Scheduler tetap membunyikan slot, tetapi `FrameFlow` mencatatnya sebagai `missed_slot_count` bila state bukan `READY`.
- `dropped_newest_count`, `capture_failed_count`, dan `missed_slot_count` dilaporkan dalam stats. Queue kosong bukan bukti bahwa stream berhasil.

Urutan transport ditegakkan: `FrameFlow` memilih satu `QueuedFrame`, lalu **hanya** `LiveFrameSession` mengirim `frame_meta` JSON dan bytes secara berurutan pada satu mutex. Enqueue tidak memulai transport dan tidak boleh mengirim bytes sendiri.

Ukuran antrean adalah nol atau satu. Slot `READY` yang mengambil capture terlebih dahulu masuk `CAPTURING`; selama `CAPTURING`, `WAITING_ACK`, atau `DRAINING`, slot berikutnya dicatat `missed_slot_count`. Dengan demikian tidak pernah ada dua capture live atau dua frame live yang siap dikirim sekaligus. Capture live memakai gate capture yang sama dengan OBSERVE.

`frame_meta.frame_seq` live adalah snapshot `frameSeq` global `SnapshotCollector` untuk capture tersebut, bukan nomor slot dan bukan nomor frame lokal sesi. `frame_meta.quality` adalah nilai efektif, yaitu bilangan bulat 1–100 untuk JPEG/WebP dan `null` untuk PNG.

Statistik `ack_refused_count` menghitung ACK yang datang untuk sesi aktif tetapi ditolak karena ID atau `session_id` tidak cocok. ACK dari socket lama tidak mencapai sesi baru dan tidak menambah statistik sesi baru.

Jika `send(meta)` gagal, bytes tidak dikirim. Jika `send(bytes)` gagal, frame itu tidak pernah masuk `IN_FLIGHT` ACK; stream berhenti dengan `stop_reason: "send_failed"`. `onClosing`/`onClosed`/disconnect menggagalkan waiter ACK dengan `connection_lost` (bukan `disconnected`; lihat tabel di bawah). Timeout tidak memicu resend. Seluruh IO berjalan di satu `CoroutineScope` per sesi dengan `SupervisorJob`; token sesi + `WebSocket` dibandingkan sebelum accept ACK atau update state agar sesi lama/koneksi lama tidak bisa menghapus sesi baru. Disconnect dan STOP membatalkan scheduler/ACK waiter dan membersihkan antrean tepat satu kali. START kedua tidak menimpa job lama.

## 8. Komponen dan batas tanggung jawab

| Komponen | Tanggung jawab | Tidak boleh | Tes |
|---|---|---|---|
| `ImageConfigNormalizer` (baru, pure JVM) | Membaca tipe JSON secara ketat, format/quality/scale/fps, alias OBSERVE, `sdkInt` dan probe encoder disuntik, memetakan error API/format | Mengambil screenshot atau mengirim data | Semua cabang validasi dan normalisasi |
| `SnapshotCapture` (baru, antarmuka) | Batas tipis di sekitar pipeline capture tunggal milik service | Memilih format, membuat metadata, atau menjadi service kedua | Fake capture untuk OBSERVE/START |
| `AgentAccessibilityService` | Pipeline real tunggal: latch, buffer hardware, scale, compress, validasi header result; target benchmark adalah menghindari salinan ARGB penuh, dibuktikan dengan profiling di HP, dimensi final, capture gate | Mengubah state stream atau mencetak bytes | Hanya perangkat; JVM tidak memanggil pipeline ini |
| `StreamFrameCodec` (baru, pure JVM) | Membuat metadata efektif, `binary_message_id`, dan membungkus payload | Menjalankan scheduler atau WebSocket | Metadata/ID/ukuran payload |
| `FrameFlow` (baru, pure JVM) | Kapasitas satu, drop-newest, satu frame in-flight, validasi ACK ber-sesi, timeout dan statistik | Membuat jaringan sendiri atau mengosongkan sesi lain | State machine deterministik |
| `DebugStreamScheduler` (baru, pure JVM) | Hanya membunyikan slot absolut (FPS, tanpa catch-up burst); **tidak** memiliki state READY/in-flight | Compress, send, atau memutuskan drop | Clock palsu + timeouts |
| `LiveFrameSession` (baru) | Orkestrasi per sesi lewat `SnapshotCapture` dan transport terinjeksi: capture → encode → meta → bytes → ACK; pembatalan dan cleanup | Dipakai OBSERVE atau memanggil `Bitmap` langsung | Capture/transport/scheduler palsu |
| `AgentCommand` | Validasi semantik dan respons OBSERVE/START/STOP; `executeV2Execution` mengembalikan hasil internal | Menyimpan queue global live | Fixture/regresi OBSERVE |
| `AgentWsClient` | Pemilik koneksi, registry satu sesi, routing biner/ACK, send `ok`, cleanup | ACK lokal OBSERVE atau menimpa sesi aktif | Adapter WebSocket palsu |

Catatan handshake: `AgentWsClient.register()` saat ini hanya mengirim `type`, info perangkat, dan `agent_version`; objek `capabilities` **belum dikirim**. Jangan menambah probe `takeScreenshot` di `register()`, karena handshake tidak boleh menangkap layar atau memblokir koneksi. Bukti kemampuan capture hanya berasal dari OBSERVE atau START eksplisit yang memang memintanya. Jika field `capabilities` ditambahkan kelak, ia hanya membawa nilai yang sudah diverifikasi dan tetap default `[]` untuk `binary_frames` sampai gate backend lulus.

### 8.1 OBSERVE

`AgentCommand` menormalisasi konfigurasi lebih dulu. Jika `include_image` benar, ia memerlukan service dan capture provider. Foreground + rotasi dibaca sebelum hierarchy, lalu dibaca lagi tepat sebelum gate. Capture + encode dijalankan pada executor screenshot agar accessibility tidak dipanggil dari reader thread. Sesudah capture, state dibaca ketiga kalinya. Capture tersebut tetap dipakai bila state berubah; tidak ada capture ulang diam-diam. Hanya setelah capture sukses ia membuat `SnapshotImage` + metadata, lalu `SnapshotCollector.capture(...)`. Capture gagal mengembalikan respons `screenshot_failed` tanpa `SnapshotRegistry.register`.

Respons sukses membawa `result.image.binary_message_id` beserta `bytes`/metadata yang sudah terikat. `AgentWsClient` mengirim JSON, metadata, lalu biner dari payload tersebut, bukan mengambil queue live. Jika transport gagal sesudah JSON, data itu dianggap jawaban terpotong; tidak ada ACK atau biner kedua.

### 8.2 Live stream

`AgentWsClient` membuat satu `LiveFrameSession` untuk satu `appSocket`. `LiveFrameSession` hanya bergantung pada `SnapshotCapture` dan abstraksi transport/jadwal yang dapat diuji di JVM. Capture produksi tetap satu di `AgentAccessibilityService`; `AgentWsClient` menjadi satu-satunya pemanggil yang mengirim `frame_meta` dan binary. Komponen menerima token sesi, sehingga STOP/disconnect/START membersihkan job lama tanpa race, tetapi START baru **tidak** menggantikan sesi aktif: hasilnya `device_busy`. Sebelum START disetujui, sesi melakukan satu capture+encode validasi di bawah capture gate dan menyimpannya sebagai frame pertama; ini memastikan error izin/OEM tidak menjadi "STOP tanpa pernah streaming".

START, STOP, dan registrasi sesi OBSERVE diserialisasi oleh gate milik registry koneksi. Gate itu mencegah perubahan registry, sedangkan capture gate hidup di service dan mencegah capture bertabrakan, termasuk OBSERVE. Sebelum capture pertama dimulai, sesi di-reserve secara sinkron agar request START lain melihat `device_busy`; bila koneksi berubah sebelum `finish()`, reserve dibatalkan, capture gate dilepas, dan frame READY tidak boleh diikat ke socket baru. Setelah respons START `finish()` sukses, frame READY dipindahkan ke `WAITING_ACK`; bila `finish()` gagal, sesi dibatalkan tanpa mengirim ulang frame. Scheduler baru mulai setelah frame pertama terkirim dan masuk `WAITING_ACK`. Scheduler hanya membunyikan slot; bila `FrameFlow` belum `READY` (belum ACK/in-flight/lambat), slot dicatat missed dan **tidak** ada capture baru. Slots yang terlewat tidak di-burst. Capture/encode berjalan lewat satu executor screenshot; send JSON metadata dan bytes harus mengembalikan `true`.

Di reader thread `AgentWsClient`, `ack_frame` dirouting ke registry sesi berdasarkan koneksi aktif. `FrameFlow.acknowledge(token, sessionId, id, sentAtElapsedMs)` menerima hanya token + session + in-flight yang sama; `AgentWsClient` sudah memastikan token milik socket tersebut. ACK yang mencapai sesi aktif tetapi ID/session-nya tidak cocok menambah `ack_refused_count`; ACK dari socket lama tidak menyentuh sesi baru. ACK ganda tidak menambah `frames_acked`.

`stop_reason` yang disepakati:

| Nilai | Arti |
|---|---|
| `requested` | STOP yang sah |
| `ack_timeout` | `.toString()` valid tapi tidak di-ACK dalam 2 s |
| `send_failed` | `send` metadata/bytes mengembalikan `false` atau melempar |
| `capture_failed` | capture/encode gagal setelah START |
| `protocol_error` | metadata/ID/ukuran di luar batas |
| `connection_lost` | `onClosing`/`onClosed`/disconnect sebelum STOP |

`disconnected` tidak dipakai sebagai `stop_reason`; istilahnya diganti `connection_lost` agar tidak ambigu dengan STOP yang diminta klien.

### 8.3 Data yang tidak berubah dan kompatibilitas destructuring

`RawScreenshotResult` dan `SnapshotImage` akan mendapat field baru di akhir dengan default. Kode `val (bytes, width, height, error) = ...` dan snapshot legacy tetap kompilasi. Legacy `takeScreenshotBase64`/`annotatedScreenshot`/v1 `screenshot` tetap menggunakan pipeline mereka sampai regression test hijau membuktikan output dan pair `(bytes, width, height)` tidak berubah. Setelah itu, `takeScreenshotBase64` boleh didelegasikan ke pipeline tunggal hanya tanpa mengubah API/output tersebut. Tidak ada perubahan pairing/overlay/notification.

### 8.4 Penyimpanan memori dan bukti privacy

Klaim ”tanpa disk” untuk screenshot memerlukan pemeriksaan terpisah, bukan hanya pipeline capture. APK dapat menggambar ke buffer, tetapi bytes yang lewat WebSocket tetap milik OkHttp dan heap, sementara jalur Android bisa menyimpan/memulihkan state yang retensinya tidak dikendalikan aplikasi selama manifest backup aktif. Pada analisis 2026-10-21, backup rules tidak ditemukan, jadi kandidat cache/held file perlu diuji.

Tidak ada layar capture yang boleh diserialisasi ke `debugFrameQueue` atau disimpan ke disk sebagai riwayat. Pengujian memori hanya menyimpan byte hasil encode untuk stream aktif dan frame in-flight; setelah frame dikirim atau sesi dihentikan, selain statistik dan metadata non-bytes tidak boleh ada referensi bytes yang tersisa. Verifikasi penyimpanan di sisi aplikasi dilakukan juga pada PSS/RSS, jumlah bytes yang di-retain di heap, dan snapshot crash (bila platform membuatnya). Konfigurasi backup direkonsiliasi dengan klaim privacy dalam tugas terpisah setelah bukti perilaku dikumpulkan; jangan mengubah manifest sebagai bagian fitur gambar.

### 8.5 Dependency dan harness tes

Unit test tetap memakai JUnit 4 + `org.json`; tidak menambah library baru. Capturing/pengirim/jam/scheduler dibuat sebagai `fun interface` atau kelas kecil yang diuji dengan `CompletableFuture`, `AtomicReference`, dan executor virtual JVM. Tidak menggunakan `runBlocking` dalam test tanpa dispatcher terkendali. Setelah implementasi, jalankan dengan toolchain lokal:

```powershell
$env:JAVA_HOME='C:\Users\dedy\tools\jdk\jdk-17.0.20.1+1'
$env:ANDROID_HOME='C:\Users\dedy\tools\sdk'
$env:PATH="$env:JAVA_HOME\bin;C:\Users\dedy\tools\gradle-8.9\bin;$env:PATH"
gradle testDebugUnitTest assembleDebug
```

### 8.6 Kontrak backend terpisah (tidak diedit di repo ini)

Backend privat harus menangani `frame_meta`, memasangkan biner dengan ID, mengirim ACK ber-sesi untuk live, tidak mengirim ACK untuk OBSERVE, dan menahan render browser agar hanya satu bitmap aktif. Pemilihan WebP/JPEG/PNG bukan mode global di APK; server memintanya per perintah. H.264/MediaProjection, header `0x01`/`0x02`, `START_COCKPIT`, dan WebCodecs ditunda ke siklus berikutnya.


## 9. Privasi, keamanan, dan keandalan

- Screenshot hanya dibuat saat server memintanya dan dikirim lewat koneksi keluar. Aplikasi tidak menyimpan riwayat frame sendiri ke disk. Kemungkinan penyimpanan oleh heap transport, snapshot crash, atau backup harus diukur sebelum dan sesudah stream; bila platform memang menyimpannya, itu dicatat sebagai batas, bukan disembunyikan, sesuai §8.4, sebelum klaim “tanpa disk” dipakai.
- Tidak mengubah izin, manifest, `SYSTEM_ALERT_WINDOW`, pairing, atau komponen overlay.
- Format biner mengurangi overhead Base64/JSON, bukan menerapkan end-to-end encryption atau izin baru. APK tetap hanya menangkap ketika server meminta dan mengirim keluar melalui koneksi yang ada.
- Format/scale/quality adalah batas server, tidak dapat melewati rentang validasi.
- Lapisan WebSocket tetap wajib TLS seperti koneksi agent saat ini. Fitur ini tidak mengubah autentikasi.
- Input server yang memengaruhi nama sesi tidak boleh masuk mentah ke identifier internal/log sensitif. `binary_message_id` dibentuk dari counter lokal, bukan dari `session_id`; session hanya memvalidasi ACK.
- `quality=1..100` adalah batas kompresi lossy. PNG tetap dilaporkan `quality: null`; UI/log tidak boleh mengubahnya menjadi 90.
- Semua handler V2 tetap dalam jalur coroutine yang menangkap error; START/STOP/ACK tidak boleh mengurangi perlindungan try/catch entry point.
- Tidak mengubah `screenshotExecutor` menjadi multi-thread; tidak memanggil accessibility dari thread yang melanggar lifecycle.

## 10. Kompatibilitas dan migrasi

1. Server lama yang hanya mengirim `screenshot`,`format`,`quality`,`scale` tetap bisa meminta gambar OBSERVE.
2. Server lama yang menginginkan WebP dengan field lama tetap harus memakai format kanonik pada respons; agent tidak lagi memetakan format tak dikenal ke PNG.
3. Kontrak binary lama yang mengirim bytes tanpa metadata dianggap tidak memenuhi kontrak baru. Backend privat harus menyesuaikan OBSERVE dan live stream sebelum APK ini dipakai bersama portal yang mengandalkan frame.
4. Fixture perlu direkonsiliasi, bukan sekadar diubah agar tes lolos. Ini menyentuh keputusan bersama Android ↔ backend. Repo ini tidak boleh mengubah backend/browser.

Respons OBSERVE baru mengubah urutan dari satu envelope menjadi envelope → `frame_meta` → binary. Handshake saat ini belum membawa `capabilities`, sehingga APK tidak dapat mengandalkan server untuk mendeteksi dukungan ini. Karena itu rollout harus backend-first: backend privat dulu harus mengenali urutan baru dan mempertahankan pembaca respons lama, baru APK dev dipasang. Bila jalur lama dihentikan sebelum backend terpasang, rollback ke APK `v1.2.2` dapat kehilangan gambar OBSERVE. Uji kompatibilitas dua arah ini harus lulus sebelum tag `-dev`; perubahan backend tetap dilakukan di sesi terpisah, bukan di repo ini.

Gate kompatibilitas sebelum rilis dev:

- Server/portal mampu menerima `frame_meta`, memasangkan biner tanpa header, dan mengirim ACK ber-sesi.
- OBSERVE lama tanpa gambar tetap berfungsi.
- Server tanpa dukungan WebP tetap memakai JPEG; pemilihan format adalah tanggung jawab pemanggil.

Hanya ada bit kapabilitas non-live `binary_frames_v1`; tidak ada handshake versi penuh dan tidak ada fallback format otomatis. Bit itu **tidak** disetel bila backend belum menerapkan biner + ACK. Selama backend belum menerapkan biner + ACK, stream live tidak boleh diaktifkan terhadap backend produksi; ketersediaan format WebP di perangkat diperiksa pada implementasi APK, bukan ditebak.

## 11. Release dan bukti

- Rujukan `§2` berarti bagian "Kondisi repo dan konteks versi" di dokumen ini, bukan tag `v1.2.3`: tag atau commit bernama `v1.2.3` belum ada di repo lokal/remote saat analisis.
- Versi implementasi pertama yang diusulkan: `versionName = "1.2.3-dev.1"`, `versionCode = 37`, **hanya setelah** kondisi versi di `§2` diverifikasi.
- Tag `v1.2.3-dev.1` harus `-dev`; belum menjadi tag stabil.
- `v1.2.3` stabil hanya setelah:
  1. CI hijau dan `apksigner verify` berhasil;
  2. pengujian HP nyata menghasilkan bukti WebP/JPEG/PNG yang dapat didekode;
  3. batas aplikasi 1–3 FPS diuji dan perilaku capture OEM/API terukur;
  4. ACK sah, ACK sesi salah, timeout 2 detik, dan reconnect diuji;
  5. browser lulus gate heap/decode selama 5 menit;
  6. `CHANGELOG.md` memuat batas jujur apa yang teruji dan tidak teruji.
- Tidak menjanjikan 15–25 KB, 2–3 FPS, waktu kompresi 5–45 ms, atau penghematan RAM browser tanpa pengukuran. Angka lampiran pengguna adalah hipotesis awal, bukan hasil uji repo ini.

Sebelum benchmark, tetapkan ambang lulus yang tidak boleh diubah setelah melihat hasil: p95 CPU proses APK, PSS/RSS, heap JavaScript Chromium, latency request ke render, dan keterbacaan teks pada skala target. Ukur secara terpisah waktu callback `takeScreenshot`, salinan ARGB bila masih ada, scale, compress, dan total capture. Event-driven debounce, frame hash, atau berhenti capture saat layar diam **bukan** bagian siklus ini; klaim penghematan sampai 90% baru boleh dipakai bila diukur pada skenario yang didefinisikan. Hasil ringan di satu HP atau satu browser tidak digeneralisasi ke semua perangkat.

**Verifikasi transport di HP nyata.** Gateway uji adalah WebSocket TLS sungguhan, bukan loopback JVM, dan bukan backend privat. Sebelum klaim "ringan": ukur PSS/RSS proses APK dan heap JavaScript di Chromium selama 5 menit per format, catat baseline sebelum stream dan puncaknya; catat waktu `takeScreenshot`, scale, encode, total capture, jumlah frame terkirim/ACK, serta thermal sebelum/sesudah. Batas lulus harus berupa angka p95 CPU proses APK, PSS/RSS, dan latensi input-ke-render yang disetujui sebelum benchmark, bukan plafon yang ditentukan setelah melihat hasil. Gateway uji harus menerima kedua URI WebSocket (dengan dan tanpa path) agar pemilihan server tidak diasumsikan tanpa probe.

## 12. Kompatibilitas dan risiko terbuka

| Risiko | Mitigasi / keputusan yang dibutuhkan |
|---|---|
| Sumber `v1.2.3` tidak ada di repo | Konfirmasi branch/commit sebelum mulai; jangan menimpa pekerjaan yang tidak terlihat |
| Encoding WebP berbeda antar-OEM | Uji beberapa API 30+, decode bytes pada perangkat/CI device matrix |
| Rate limit `takeScreenshot()` | Jadwal absolut dan metrik missed; 1–3 FPS adalah batas aplikasi, sedangkan rate maksimum `takeScreenshot` bergantung OEM/API dan harus diukur per perangkat |
| Server belum mengirim ACK | Stream berhenti setelah 2 detik; backend privat harus menerapkan ACK sebelum uji live |
| Timeout ACK 2 detik pada jaringan seluler lambat | Ukur latensi nyata; bila 2 detik terlalu ketat, ubah melalui keputusan kontrak baru, bukan menambah retry diam-diam |
| Binary atau metadata lama di klien | `binary_message_id` + `session_id`; jangan pernah ACK lokal |
| Tes JVM tidak bisa membuktikan `Bitmap.compress` / WebP | Tes JVM hanya menguji parser/state; bukti perangkat wajib |
| Fixture saat ini belum konsisten (OBSERVE memakai field kanonik tetapi START tanpa session/fps 5, STOP tanpa kontrak stats) | Rekonsiliasi fixture + backend dalam tugas lintas-repo terpisah; fixture hanya diubah setelah kontrak disetujui |
| Aplikasi berjalan sebelum backend paham ACK | Fitur live dijaga di balik implementasi server yang teruji; ganti kontrak di masa depan harus melalui handshake versi, bukan asumsi diam-diam |

Implementasi hanya dimulai setelah draft ini disetujui atau direvisi.

## 13. Matriks verifikasi

| Perilaku | JVM | HP nyata |
|---|---|---|
| `jpg`, `JPEG`, `webp`, format tak dikenal, API <30 | Unit tes normalizer | APK API 30+ dan API minimal yang relevan |
| Scale/quality efektif dan metadata | Unit tes model/pipeline config | Bandingkan dimensi/format hasil decode |
| OBSERVE image gagal | Unit tes respons tanpa service / injected capture | Matikan akses screenshot saat diminta |
| OBSERVE tanpa image | Unit tes regresi | Opsional |
| OBSERVE metadata → biner dan binary ID | Unit tes urutan/kontrak | WebSocket lokal/backend uji |
| OBSERVE berubah state saat capture | Unit tes state A/B/C + envelope | Ubah aplikasi/rotasi di tengah capture |
| OBSERVE/START bertabrakan gate capture | Unit tes gate bersama + timeout 5 dtk | OBSERVE saat live `CAPTURING` menunggu lalu capture sendiri; OBSERVE saat `WAITING_ACK` tidak ditolak; tahan gate >5 dtk dan pastikan `screen_capture_busy` tanpa `frame_seq` berubah |
| START ganda, STOP salah session, invalid FPS | Unit test state machine | Kirim lewat server uji |
| Drop-newest, satu in-flight, ACK timeout | Unit test queue/state | Matikan ACK dan ukur berhenti ~2 dtk |
| Statistik meta/sent/acked/refused terpisah | Unit test state machine + codec | Bandingkan stats STOP dengan hitungan gateway |
| Send failure/reconnect cleanup | Unit test abstraksi transport | Putus jaringan di tengah stream |
| Rate 1–3 FPS / missed | Unit test scheduler dengan clock palsu | Ukur timestamp/waktu antar-frame |
| WebP decode | Tidak dapat dibuktikan penuh di JVM Android-free | Decode/lihat pada perangkat nyata |
