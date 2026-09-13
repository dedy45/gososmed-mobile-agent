# Changelog

Semua perubahan penting pada GoSosmed Agent tercatat di sini.
Format mengikuti [Keep a Changelog](https://keepachangelog.com/id/1.1.0/)
dan versi mengikuti [SemVer](https://semver.org/lang/id/).

> **Kanal rilis:** rilis ber-label `vX.Y.Z` = **stabil** (dua angka terakhir
> naik saat fitur/fix). Rilis ber-label `vX.Y.Z-dev.N` = **dev** (build
> berkelanjutan dari `main`, belum diuji luas). Semua build ditandai jelas
> di GitHub Releases; APK dari CI `main` selalu berstatus **dev**.

## [Unreleased]

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

[Unreleased]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.7.0...HEAD
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
