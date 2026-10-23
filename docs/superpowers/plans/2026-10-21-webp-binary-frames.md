# Rencana Implementasi Frame Biner OBSERVE dan Live Stream

> **Status: DRAFT, belum disetujui.** Belum ada kode aplikasi yang diubah.
> Spesifikasi: `docs/superpowers/specs/2026-10-21-webp-binary-frames-design.md`.
> Tanggal analisis: 2026-10-21. Kebenaran versi selalu dibaca ulang dari `app/build.gradle.kts` dan tag Git.

## Tujuan

Menambahkan gambar OBSERVE dan stream live Level 1 sebagai WebSocket binary yang memiliki `frame_meta`, `binary_message_id`, dan ACK ber-sesi. WebP lossy bersifat opt-in. JPEG tetap default dan jalur legacy tidak berubah.

## Batas yang ditetapkan

- Hanya accessibility screenshot API 30+, `Bitmap`, dan OkHttp WebSocket yang ada.
- Tidak ada MediaProjection, H.264, MediaCodec, WebCodecs, `START_COCKPIT`, header biner tersembunyi, atau pemilih mode di APK.
- Tidak mengubah pairing, overlay, notification, manifest, izin, backend, browser, atau monorepo privat.
- OBSERVE dan live memakai capture gate yang sama. OBSERVE tidak memakai antrean live dan tidak memakai ACK.
- Satu sesi live per koneksi, fps integer 1–3, antrean kapasitas satu, drop-newest, satu capture dan satu frame in-flight, ACK timeout 2000 ms tanpa retry.
- `frame_meta` dikirim sebelum binary. Binary tidak diberi header. `frame_meta.frame_seq` live berasal dari snapshot global `SnapshotCollector`.
- `capabilities.binary_frames` selalu `[]` pada build ini. Probe screenshot nyata saat handshake tidak dapat dilakukan karena `AgentWsClient.register()` hanya menerima `WebSocket`, dan menambahkan probe tersebut berisiko menahan handshake, memicu interaksi layar, dan menyimpan bytes. Aktivasi backend menunggu bukti device. Saklar lokal binary hanya untuk rollback; tidak memvalidasi kapabilitas perangkat.

## Gerbang sebelum menulis kode fitur

| Gate | Syarat | Bukti yang harus disimpan |
|---|---|---|
| G0 Versi | Pengguna mengonfirmasi sumber build `1.2.3-dev` atau secara eksplisit menyetujui mulai dari `v1.2.2` | `git rev-parse HEAD`, `git status --short`, tag terkait |
| G1 Kontrak | Spesifikasi disetujui, termasuk nama kanonik OBSERVE, kode error, batas, dan statistik | Persetujuan pengguna |
| G2 Baseline | `testDebugUnitTest --rerun-tasks` dan `assembleDebug` berhasil terpisah pada commit awal tanpa perubahan fitur | Dua log terpisah dengan exit code 0 |
| G3 Backend | Pemilik backend menerapkan `frame_meta`, pairing binary, dan ACK ber-sesi sebelum binary diaktifkan | Persetujuan backend di §6, tanpa mengedit monorepo privat |
| G4 Bukti perangkat | Encoder, memori, dan rate capture diukur di HP nyata sebelum klaim "ringan" atau tag stabil | Tabel benchmark di §4 |
| G5 Browser | Harness browser lulus decode berurutan, tanpa kebocoran bitmap/Promise, dan heap stabil | Laporan harness di §5 |
| G6 Angka | Ambang CPU, memori, dan latensi ditetapkan pengguna sebelum benchmark; angka lampiran belum dipakai sebagai target lulus | Catatan persetujuan ambang sebelum G4/G5 |

**Urutan gate:** G0 versi dan G1 kontrak disetujui sebelum menulis kode. G2 baseline lulus sebelum status RED/GREEN dicatat. G3 backend boleh berjalan paralel setelah kontrak, tetapi lulus sebelum `binary_frames_v1` dipublikasikan.

## 1. Kontrak yang dibekukan sebelum implementasi

### 1.1 OBSERVE

Field kanonik `include_image`, `image_format`, `image_quality`, `image_scale`. Default `false`, `jpeg`, `75`, `0.5`. Format `jpeg`, `png`, `webp`, dengan `jpg` menjadi `jpeg`. WebP ditolak `action_not_supported` di bawah API 30. PNG melaporkan `quality: null`.

Alias `screenshot`, `format`, `quality`, `scale` hanya dipakai bila field kanonik tidak ada di request. Nilai yang sama dengan sintaks berbeda (misalnya `jpg` dan `jpeg`, atau `70` dan `70.0`) bukan konflik. Nilai yang berbeda selalu `conflicting_image_args`, begitu juga alias `format`/`quality`/`scale` tanpa `screenshot: true`. Field kanonik bernilai `null` adalah salah tipe, bukan sinyal untuk memakai alias. Alias gambar tanpa `include_image: true` menghasilkan `image_options_require_include_image`.

Error OBSERVE memakai `ok: false`, `reason_code`, dan `result.error`:

| Keadaan | `reason_code` | `result.error` |
|---|---|---|
| Tipe/rentang salah, termasuk `include_image` bukan boolean dan `screenshot` bukan boolean | `action_rejected` | `invalid_image_arg` |
| Format tak dikenal | `action_rejected` | `invalid_image_arg` |
| Kanonik/alias bertentangan atau alias format/quality/scale tanpa `screenshot: true` | `action_rejected` | `conflicting_image_args` |
| Format/quality/scale tanpa `include_image: true` | `action_rejected` | `image_options_require_include_image` |
| WebP tidak tersedia atau API < 30 | `action_not_supported` | `unsupported_image_format` |

Urutan validasi: tipe/rentang, konflik kanonik/alias, opsi gambar tanpa gambar, lalu dukungan runtime. Urutan sukses: state A, hierarchy, state B, ambil capture gate, capture dan encode, state C, bandingkan state, registrasi snapshot, lepas gate, `send(envelope)`, `send(frame_meta)`, `send(bytes)`. Kegagalan capture melepas gate di `finally`, tidak registrasi snapshot, tidak mengubah `frame_seq`, dan mengirim `ok: false`, `reason_code: "screenshot_failed"`, `retryable: true`, tanpa metadata/binary.

Gate capture hanya menyerialkan capture. OBSERVE saat live `CAPTURING` menunggu gate lalu capture sendiri; OBSERVE tidak ditolak saat live `WAITING_ACK` atau `DRAINING`. Bila gate tidak didapat dalam 5 detik: `device_busy`, `result.error: "screen_capture_busy"`, `retryable: true`, tanpa capture, dan `frame_seq` tidak berubah.

Bila state berubah, capture tetap dikirim dengan `result.state_changed_during_capture: true`, `result.state_change`, `state_before_capture`, `state_after_capture`, dan `capture_elapsed_ms`. Pemanggil tidak boleh menganggap koordinat hierarchy masih berlaku.

`include_image: false` tetap mendaftarkan hierarchy tetapi tidak memanggil capture dan tidak mengirim metadata/binary.

### 1.2 START dan STOP

START wajib `session_id` 1–128 karakter, `fps` integer 1–3, service siap, satu sesi saja, format valid, dan satu capture/encode uji pertama berhasil. Urutan sukses: reserve sesi, capture dan encode, simpan frame sebagai `READY`, kirim respons, tunggu `finish()` berhasil, kirim frame itu sebagai frame live pertama, masuk `WAITING_ACK`, lalu mulai scheduler. Frame validasi tidak dibuang. Jika `finish()` gagal, sesi berhenti dengan `send_failed` tanpa mengirim ulang frame. START kedua menghasilkan `device_busy` dan `stream_already_active`.

STOP dengan sesi lain menghasilkan `device_busy` dan `session_mismatch` tanpa menghentikan sesi aktif. STOP saat idle idempoten dengan `was_streaming: false`. STOP sesi terakhir setelah berhenti sendiri mengembalikan `last_stats`. STOP sah membekukan semua statistik, membersihkan antrean dan waiter ACK, lalu membalas.

Validasi STOP: ketiadaan stream dicek lebih dulu (idempoten, `was_streaming: false`). Baru setelah itu `session_id` diperiksa; koneksi berbeda selalu menghasilkan `session_mismatch` dan tidak dapat menyentuh sesi di koneksi lain.

`stop_reason` hanya `requested`, `ack_timeout`, `send_failed`, `capture_failed`, `protocol_error`, `connection_lost`.

### 1.3 Transport dan statistik

`frame_meta` live memuat `type`, `session_id`, `local_session_id`, `frame_seq`, `binary_message_id`, `timestamp_ms`, `format`, `width`, `height`, `source_width`, `source_height`, `source_scale`, `quality`, dan `byte_length`. `frame_meta` OBSERVE juga memuat `snapshot_id`. `binary_message_id` OBSERVE adalah `snap-img-<snapshot_id>`; live adalah `s<local_session_id>:<frame_seq>`. ID ini bukan token keamanan. `local_session_id` dan `frame_seq` memakai counter global monoton `SnapshotCollector` agar tidak bentrok setelah reconnect, START baru, atau restart proses. Metadata maksimal 16 KiB, payload 1–524.288 byte.

ACK live hanya `{"type":"ack_frame","binary_message_id":"...","session_id":"..."}`. ACK sah membutuhkan socket, token sesi lokal, ID in-flight, dan `session_id` yang sama. `elapsed >= 2000` adalah timeout. Tidak ada resend. ACK dari socket lama tidak boleh menyentuh sesi baru.

Tiga metrik utama tidak boleh dicampur. `frames_meta_sent` adalah send metadata berhasil. `frames_sent` adalah send binary berhasil. `frames_acked` adalah ACK tervalidasi. Statistik lain: `pending_unacked` (0/1), `dropped_newest_count`, `capture_failed_count`, `missed_slot_count`, `ack_timeout_count`, `ack_refused_count`.

### 1.4 Bentuk hasil OBSERVE

Tambahkan tipe internal berikut pada `AgentCommand` dan ekspos melalui `executeV2Execution`:

```kotlin
sealed interface V2Execution {
    val envelope: JSONObject
    data class Json(override val envelope: JSONObject) : V2Execution
    data class ObserveImage(
        override val envelope: JSONObject,
        val bytes: ByteArray,
        val metadata: JSONObject
    ) : V2Execution
}
```

`executeV2` hanya mengembalikan `envelope` dari pemanggilan yang sama. Bytes tidak disimpan di queue global atau field statis.

## 2. Implementasi APK

Setiap task memiliki test JVM yang gagal lebih dahulu, implementasi minimal, lalu test yang sama lulus. Test tidak memakai `Thread.sleep` untuk memajukan scheduler atau ACK; waktu dan transport disuntikkan.

| Task | File utama | Perubahan | Test wajib |
|---|---|---|---|
| T1 Normalizer | `ImageConfigNormalizer.kt`, `StreamFrameCodec.kt` | Parsing ketat, `sdkInt` disuntik, alias OBSERVE, konfigurasi live; codec murni untuk metadata, `binary_message_id`, dan batas payload | Tabel error, alias kanonik, `null`, PNG quality, API 29 WebP, batas session/FPS/scale, metadata 16 KiB, payload 524.288 byte |
| T2 Model snapshot | `SnapshotModels.kt` | `SnapshotImage.quality: Int?`, `sourceScale`, padding dimensi yang dihitung di normalizer, `fromJson` menerima `null`, regression test pair `takeScreenshotBase64` | PNG round-trip, snapshot legacy, dimensi/skala sumber, pair `(bytes, width, height)` tidak berubah |
| T3 Pipeline capture | `AgentAccessibilityService.kt` | Satu pipeline latch, buffer, bitmap, scale, compress, dimensi efektif, capture gate, timestamp capture asli; target benchmark: hindari salinan ARGB penuh bila didukung, dibuktikan dengan profiling PSS/CPU di HP | JVM hanya test konfigurasi/state; implementasi pipeline dibuktikan di HP |
| T4 OBSERVE | `AgentCommand.kt` | Normalizer, state A/B/C, gate 5 dtk, registrasi hanya saat sukses, `V2Execution.ObserveImage`, `executeV2Execution`; hapus pemakaian queue global debug untuk OBSERVE | Fake hierarchy/capture/state, urutan, state change, capture gagal, tanpa gambar, gate timeout |
| T5 FrameFlow | `FrameFlow.kt`, `DebugStreamScheduler.kt` | State `READY/CAPTURING/DRAINING/IN_FLIGHT/WAITING_ACK`, kapasitas satu, drop-newest, slot absolut, statistik, ACK ber-token/sesi/ID | Drop-newest, missed, satu in-flight, ACK salah ID/sesi/duplikat, timeout tepat 2000 ms, tanpa burst catch-up, reset per sesi |
| T6 Sesi live | `LiveFrameSession.kt` | Capture/encode/meta/binary/ACK lewat abstraksi, frame validasi dikirim setelah `finish()`, cleanup deterministik, auto-stop dengan `stop_reason`, `last_stats` | Metadata gagal tanpa binary, binary gagal, capture gagal, `finish()` gagal, STOP, reconnect, callback lama, START kedua |
| T7 Koneksi | `AgentWsClient.kt` | START/STOP nyata, registry satu sesi, routing ACK hanya `type`, kirim OBSERVE envelope/meta/binary, saklar lokal `binary_frames`, cleanup tepat sekali | Adapter socket palsu/urutan, kapabilitas `[]` default, ACK OBSERVE tidak dibuat, disconnect membersihkan sesi |
| T8 Uji API 30 | Build varian/emulator API 30 | `WEBP_LOSSY` hanya dipanggil saat `Build.VERSION.SDK_INT >= 30`; legacy dan normalizer tetap bisa diuji di JVM | Unit test batas sdkInt 29/30, compile lint/CI, dan smoke test gambar di emulator API 30, bukan hanya API 34 |
| T9 Kontrak publik | `docs/AGENT-COMMAND-CONTRACT.md`, `docs/contracts/v2-fixtures.json` | Kontrak dan fixture lengkap untuk OBSERVE, START, STOP, metadata, ACK, statistik; hapus klaim `drop-oldest`/ACK 10 detik/`quality` yang tidak benar; fixture lama tidak boleh diam-diam berubah | Fixture valid: OBSERVE field kanonik dan `image`, START memakai `session_id` dan fps 1–3, STOP memakai statistik baru, `reason_code` runtime `action_not_supported`, `frame_meta` OBSERVE memuat `snapshot_id`; paritas tabel §10.4 dengan normalizer diuji |
| T10 Dokumentasi rilis | `CHANGELOG.md`, `README.md` | Bukti terverifikasi dan batas jujur, tanpa menaikkan versi sebelum G0 disetujui | Pemeriksaan teks dan kebenaran versi |

### 2.1 Aturan implementasi

- `AgentCommand`, normalizer, dan state machine tidak memanggil `Bitmap` pada test JVM.
- Capture produksi tetap satu fungsi service. `SnapshotCapture` adalah batas injeksi tipis, bukan service kedua.
- Gate capture dilepas di `finally`. Tidak ada `return` diam-diam pada kegagalan izin atau capture.
- OBSERVE tidak memasukkan gambar ke `debugFrameQueue`. Hanya `LiveFrameSession` yang mengirim frame live.
- `AgentWsClient` memeriksa hasil `send`; `false` atau exception adalah kegagalan eksplisit.
- `register()` saat ini tidak mengirim `capabilities`. Jangan menambah probe screenshot di handshake. Kapabilitas biner diverifikasi saat START/OBSERVE nyata; START gagal secara eksplisit tanpa sesi atau ACK palsu.
- OBSERVE sukses baru mengirim envelope, lalu `frame_meta`, lalu binary. Karena handshake belum membawa `capabilities`, rollout harus backend-first: backend privat harus mengenali urutan baru sekaligus tetap membaca respons lama sebelum APK dev dipakai. Uji APK baru dengan backend baru dan APK `v1.2.2` dengan backend baru; rollback ke APK lama tidak boleh kehilangan gambar OBSERVE.
- Tinggalkan handler V2 dalam try/catch. Jangan mengubah jalur pairing, notification, atau `onStartCommand`.
- Legacy `screenshot`, `takeScreenshotBase64`, dan `annotatedScreenshot` memakai perilaku lama sampai regression test hijau membuktikan output dan pair `(bytes, width, height)` identik. Delegasi `takeScreenshotBase64` ke pipeline tunggal hanya boleh dilakukan tanpa mengubah API/output itu.
- Tidak memperluas siklus ini ke event-driven debounce, `AccessibilityEvent` sebagai pemicu capture, frame hash, atau berhenti menangkap saat layar diam. Capture hanya terjadi saat server meminta OBSERVE atau selama START yang sah. Klaim penghematan akibat frame hashing hanya boleh dipakai setelah skenario dan angka p95-nya diukur.

## 3. Verifikasi JVM

Gunakan toolchain lokal. Tidak ada Gradle wrapper.

```powershell
$env:JAVA_HOME='C:\Users\dedy\tools\jdk\jdk-17.0.20.1+1'
$env:ANDROID_HOME='C:\Users\dedy\tools\sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:PATH="$env:JAVA_HOME\bin;C:\Users\dedy\tools\gradle-8.9\bin;$env:PATH"
gradle testDebugUnitTest --rerun-tasks
gradle assembleDebug
```

Gate JVM:

- Semua test, termasuk regression test legacy, lulus.
- Test deterministik tanpa sleep nyata dan tanpa Android framework stubbed.
- Tidak ada perubahan pada keystore, manifest, atau izin.
- Build gagal karena lingkungan tidak boleh dilaporkan sebagai PASS.

## 4. Uji perangkat nyata

### 4.0 Ambang sebelum benchmark

Tetapkan ambang lulus sebelum melihat hasil. Angka lampiran, yaitu JPEG 5–12 ms, WebP 25–45 ms, 15–25 KB per frame, dan 1–3 FPS, adalah hipotesis dan tidak dipakai sebagai bukti atau target lulus. Tetapkan ambang untuk:

- CPU proses APK p95 selama 60 detik stream, dibanding baseline diam, dengan `jank`/frame drop foreground bila dapat diukur;
- puncak PSS/RSS dan jumlah bytes gambar yang tetap di heap;
- latency submit ke terlihatnya gambar di browser per p50/p95;
- heap JavaScript (dan memori GPU bila tersedia) selama 5 menit;
- keterbacaan tombol dan teks kecil pada gambar hasil decode;
- format dan `image_scale` yang disetujui untuk backend, serta budget bytes per frame.

Tanpa ambang yang disetujui, hasil benchmark hanya data, dan klaim "ringan" atau "efisien" tidak boleh dirilis.

Test minimal menggunakan dua HP, satu API 30 dan satu API 34 atau lebih baru, idealnya dua OEM. Rekam model, API, OEM, dan build SHA.

### 4.1 Benchmark encoder dan capture

Untuk tiga jenis layar (chat, feed bergambar, form/login) dan dua kondisi (terang/gelap), ukur untuk setiap format dan `image_scale` 0.5, lalu 0.3 dan 0.7 sebagai data pembanding. Kualitas awal 75; uji 55/60 hanya untuk WebP dan tidak menamainya "terbaik" tanpa data.

Catat per sampel:

- waktu callback `takeScreenshot`, copy ARGB, scale, encode, dan total capture, diukur terpisah tidak kurang dari 20 sampel per kombinasi dan dilaporkan distribusinya;
- `width`, `height`, `byte_length`, dan status decode;
- ukuran awal sebelum downscale;
- PSS/RSS baseline dan puncak, lonjakan memori, GC, OOM;
- suhu awal/akhir dan thermal throttling;
- keterbacaan tombol, teks kecil, dan tantangan visual pada gambar hasil decode.

Uji 1, 2, dan 3 FPS selama 60 detik untuk masing-masing format pada satu sesi live. Catat `capture_failed_count`, `missed_slot_count`, `dropped_newest_count`, jeda antarframe, dan apakah ada burst. 1–3 FPS adalah batas aplikasi; rate maksimum `takeScreenshot` bergantung OEM/API dan dicatat per perangkat, tidak dianggap universal. Benchmark encoder terpisah harus mencakup beberapa `Bitmap` uji representatif, bukan hanya satu layar. Benchmark capture eksplisit tidak mengukur optimisasi hash atau event-driven; optimisasi itu ditunda.

### 4.2 Latency dari HP ke server uji

Gunakan gateway mock lokal di luar monorepo privat, dengan WebSocket TLS sungguhan, bukan loopback JVM. Gateway menerima metadata dan bytes, memverifikasi magic bytes (`JPEG`, `PNG`, `WEBP`), mengirim ACK, lalu mencatat waktu di `send(meta)`, `send(bytes)`, dan ACK diterima. Uji minimal 5 menit dengan kondisi:

- ACK normal di bawah 2 detik;
- ACK tidak dikirim (stream harus berhenti dengan `ack_timeout`);
- ACK datang tepat 2000 ms (harus timeout);
- ACK dengan ID/sesi salah (`ack_refused_count`);
- disconnect dan reconnect (callback lama tidak menghapus sesi baru);
- jaringan lambat/putus di tengah binary.

Gate perangkat lulus hanya jika semua format yang diklaim didekode oleh decoder independen dari bytes yang diterima gateway, bukan hanya dari metadata. Gambar yang tidak lolos decode atau teks yang tidak terbaca membatalkan klaim kualitas tersebut. Klaim "ringan" memerlukan angka p95 CPU proses APK, PSS/RSS, dan latensi input-ke-render dengan ambang yang disetujui sebelum benchmark.

## 5. Harness browser

Harness berada di direktori uji lokal di luar repo aplikasi dan bukan perubahan aplikasi. Jangan menambahkan dependency browser ke APK.

Harness menjalankan gateway mock dan browser sendiri. Setiap sampel mencatat build SHA APK, hash gateway/harness, versi browser dan WebKit, `devicePixelRatio`, format, `image_scale`, kualitas, fps, ambang, suhu, dan model/API OEM. Lakukan warmup, lalu ukur profil masing-masing untuk Chromium dan WebKit:

- Menerima `frame_meta` sebelum binary.
- Mengabaikan binary tanpa metadata yang cocok, tanpa menebak dari byte pertama.
- Decode melalui `createImageBitmap` atau decoder browser lain, lalu menggambar tepat satu bitmap.
- `bitmap.close()` di `finally`; tidak menyimpan Blob URL, riwayat gambar, atau banyak Promise decode.
- Menolak/melewati frame yang datang saat frame sebelumnya belum selesai, tanpa antrean tak terbatas.
- Mengukur waktu submit hingga image terlihat, heap 5 menit, memori GPU bila tersedia, dan jumlah decode gagal.

Gate browser lulus hanya jika semua ambang yang ditetapkan di §4.0 terpenuhi: tidak ada kebocoran bitmap/URL, heap tidak terus naik pada 5 menit stream, tidak ada decode tak terkendali, dan frame yang di-decode sesuai metadata terakhir. Jika WebKit tidak tersedia, tulis batas itu secara eksplisit. Chrome desktop bukan bukti browser HP. Untuk `image_scale` 0.5 saja, browser tidak memakai `image-rendering: pixelated` secara default. Format dan scale dipilih per request; jangan mengganti default secara diam-diam.

## 5.1 Keterjangkauan sebelum uji

Pastikan HP dapat menjangkau gateway di URL WS/WSS yang akan dipakai, browser harness dapat menjangkau gateway, dan metadata serta binary melewati jalur yang sama tanpa header biner tambahan. Probe juga URI WebSocket dengan dan tanpa path agar jalur server tidak diasumsikan. Jika backend privat belum siap, pengujian berhenti di gateway mock; itu bukan bukti produksi.

## 6. Gate backend privat

Tugas ini tidak mengedit `C:\Users\\dedy\\Documents\\Go-sosmed`. Pemilik backend harus menerima kontrak berikut sebelum live diaktifkan:

1. Simpan pemetaan `binary_message_id` ke metadata; jangan menebak metadata dari binary.
2. Pasangkan binary dengan metadata yang belum terpakai; tolak binary tanpa metadata.
3. Kirim ACK live setelah frame aman diterima; jangan ACK OBSERVE.
4. Jangan memberi ACK dari koneksi/sesi lama ke sesi baru.
5. Jaga render browser hanya satu bitmap aktif dan tutup bitmap lama.
6. Uji slow network, disconnect, reconnect, dan ACK telat.
7. Browser harus memiliki satu renderer aktif, menutup bitmap lama, dan memuat hanya frame yang terpasang metadata.
8. Bukti browser HP dan backend harus disediakan pemilik monorepo privat. Repo ini tidak mengedit backend atau browser.

API APK tetap menerbitkan `binary_frames: []` sampai gate ini lulus. Saat lulus, aktivasi diatur melalui konfigurasi rilis yang disetujui, bukan asumsi dari tag aplikasi.

## 7. Penetapan versi, rilis, dan rollback

- Jangan membuat tag atau menaikkan versi selama G0 belum disetujui.
- Jika mulai dari `v1.2.2`, implementasi pertama hanya dapat memakai versi lebih tinggi yang belum dipakai, misalnya `1.2.3-dev.1` dengan `versionCode` lebih tinggi dari 36, setelah konfirmasi.
- Tag stabil hanya setelah build CI hijau, `apksigner verify` berhasil, bukti HP nyata, gateway ACK, browser, dan batas jujur di `CHANGELOG.md`.
- Rollback APK adalah saklar lokal binary, ACK wajib untuk live, dan jalur legacy tetap. Saklar itu tidak menjamin build lama atau backend lama dapat menerima protokol baru. Uji rollback pada server yang disetujui. Jangan meluncurkan mode best effort yang mengirim tanpa ACK sebagai pengganti rollback.
- Satu commit akhir setelah semua perubahan aplikasi/test/docs diperiksa. Jangan commit APK, keystore, `build/`, atau file lokal.

## 8. Urutan eksekusi dan bukti

1. Konfirmasi G0 dan G1, lalu catat baseline G2.
2. Implementasikan T1–T9 dengan test bertahap; jalankan `testDebugUnitTest` untuk test yang relevan, lalu seluruh suite.
3. Jalankan `assembleDebug` dan verifikasi tidak ada file terlarang yang masuk commit.
4. Jalankan benchmark HP dan gateway mock di §4, lalu simpan log G4.
5. Jalankan harness browser di §5, lalu simpan log G5.
6. Serahkan kontrak ke pemilik backend pada G3 di §6. Jangan mengedit monorepo privat.
7. Hanya setelah G0–G6 lulus pada commit tetap setelah `cf6dba8`, buat commit, minta persetujuan push `dev`, lalu pertimbangkan tag `-dev`. Tag stabil menunggu bukti perangkat dan persetujuan pengguna.