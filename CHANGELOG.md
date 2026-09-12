# Changelog

Semua perubahan penting pada GoSosmed Agent tercatat di sini.
Format mengikuti [Keep a Changelog](https://keepachangelog.com/id/1.1.0/)
dan versi mengikuti [SemVer](https://semver.org/lang/id/).

> **Kanal rilis:** rilis ber-label `vX.Y.Z` = **stabil** (dua angka terakhir
> naik saat fitur/fix). Rilis ber-label `vX.Y.Z-dev.N` = **dev** (build
> berkelanjutan dari `main`, belum diuji luas). Semua build ditandai jelas
> di GitHub Releases; APK dari CI `main` selalu berstatus **dev**.

## [Unreleased]

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
