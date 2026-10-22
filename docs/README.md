# Portal Dokumentasi Resmi GoSosmed Mobile Agent

Selamat datang di pusat dokumentasi resmi dan pusat transparansi (*Trust Center*) **GoSosmed Mobile Agent** (*BYOD Local Device Bridge*).

Aplikasi ini adalah jembatan otomasi perangkat Android berbasis Kotlin murni yang berjalan langsung di ponsel pengguna, menghubungkan perangkat secara *outbound* (keluar) melalui koneksi WebSocket aman ke infrastruktur backend GoSosmed, dan mengeksekusi otomatisasi media sosial secara deterministik.

---

## 🛡️ Kebijakan Transparansi & Kepercayaan Publik (Trust Center)

Aplikasi ini meminta izin tingkat tinggi di sistem operasi Android, terutama **Aksesibilitas (`AccessibilityService`)** dan **Transport Shell ADB Lokal (UID 2000)**. Kami meyakini bahwa meminta kepercayaan sebesar itu pada perangkat pribadi pengguna menuntut keterbukaan kode sumber 100% tanpa kompromi:

1. **Kode Sumber Terbuka Penuh (Apache-2.0)**: Seluruh baris kode pada repositori ini dapat diaudit secara bebas oleh pengguna, tim keamanan, maupun peneliti independen.
2. **Nol Pelacak & Nol Iklan Pihak Ketiga**: Aplikasi ini **100% bebas dari SDK periklanan (AdMob), pelacak perilaku (Google Analytics/AppsFlyer), maupun library crash reporting komersial**. Privasi ponsel dan akun media sosial Anda sepenuhnya terlindungi.
3. **Nol Library Native Pihak Ketiga (.so)**: Aplikasi ini tidak menimbun puluhan megabyte library C++ eksternal yang tidak dapat diaudit. Seluruh fungsi encoding video mengandalkan chip silikon hardware Android bawaan (`android.media.MediaCodec`).
4. **Pemisahan Batas Sistem (Boundary Discipline)**: Repositori ini hanya berisi aplikasi klien Android. Kode backend, dashboard web, dan basis data dikelola di repositori terpisah. Dokumen internal pengembang, log audit lokal, dan catatan kerja privat diabaikan oleh git (`.gitignore`) dan tidak pernah dilacak ke publik.

---

## 🧰 Katalog Lengkap Tools & Kapabilitas APK (Keterbukaan Jujur)

Berikut adalah daftar lengkap seluruh *tools* dan kemampuan perintah yang dapat dijalankan oleh APK ini, beserta fungsi nyata, izin yang dibutuhkan, dan batasan keamanannya:

### 1. Otomasi UI & Pemeriksaan Layar (Protocol v2)

| Tool / Perintah | Izin Sistem | Deskripsi Fungsi | Batasan & Keamanan |
|---|---|---|---|
| `observe` | Aksesibilitas | Mengumpulkan pohon elemen hierarki UI layar aktif, orientasi, resolusi layar, hash integritas pohon elemen, dan tangkapan layar terkompresi. | Tidak pernah membaca layar saat aplikasi target ditandai `FLAG_SECURE` (aplikasi perbankan/kata sandi). Field kata sandi disamarkan otomatis menjadi `[REDACTED]`. |
| `resolve` | Tidak butuh | Menilai kandidat elemen UI menggunakan 8-tier scoring selektor berbasis teks, ID resource, atau deskripsi konten. | Berhenti aman bila ada dua elemen yang membingungkan (*zero ambiguous taps*) untuk mencegah salah klik. |
| `actNode` / `actAndVerify` | Aksesibilitas | Mengeksekusi pengetukan native (`ACTION_CLICK`), pengisian teks, atau scroll pada elemen yang terverifikasi, lalu menunggu layar stabil (*settle*). | Memeriksa kecocokan hash pohon layar (`tree_hash`) sebelum klik dieksekusi. Bila layar bergeser, aksi ditolak demi keamanan. |
| `waitForNode` / `waitForScreen` | Aksesibilitas | Menunggu elemen UI tertentu muncul atau layar berpindah aplikasi target secara in-memory. | Dilengkapi batas waktu mati (*timeout*) maksimal 15 detik agar tidak memicu ANR. |
| `cancelAction` | Tidak butuh | Membatalkan aksi asinkron yang sedang berjalan di memori seketika. | Hanya membatalkan aksi in-flight yang cocok dengan `request_id` target. |

### 2. Navigasi Sistem & Layar

| Tool / Perintah | Izin Sistem | Deskripsi Fungsi | Batasan & Keamanan |
|---|---|---|---|
| `back` | Aksesibilitas | Menekan tombol Kembali (*Back*) sistem Android (`GLOBAL_ACTION_BACK`). | Standar navigasi global sistem OS. |
| `home` | Aksesibilitas | Menekan tombol Beranda (*Home*) sistem Android (`GLOBAL_ACTION_HOME`). | Mengembalikan tampilan ke launcher utama. |
| `recents` | Aksesibilitas | Membuka layar Recent Apps / Multitasking Switcher (`GLOBAL_ACTION_RECENTS`). | Menampilkan daftar aplikasi aktif. |
| `wake` | WakeLock | Menyalakan layar bila padam atau mengunci layar bila aktif. | Menggunakan wakelock resmi bertenggang 1 detik agar hemat daya baterai. |
| `notify` | Aksesibilitas | Membuka panel notifikasi sistem Android. | Hanya membuka tray notifikasi sistem. |

### 3. Kontrol Gestur & Sentuhan Interaktif

| Tool / Perintah | Izin Sistem | Deskripsi Fungsi | Batasan & Keamanan |
|---|---|---|---|
| `tap` | ADB / Aksesibilitas | Mengetuk koordinat piksel tertentu `(x, y)` pada layar. | Mengutamakan shell ADB `input tap` bila UID 2000 terhubung, fallback ke gestur aksesibilitas. |
| `swipe` | ADB / Aksesibilitas | Menggeser atau menggulir layar dari titik `(x1, y1)` ke `(x2, y2)` dengan durasi milidetik yang ditentukan. | Dibatasi rentang durasi 50–2000 ms agar gerakan tetap wajar dan tidak merusak UI. |
| `setText` / `replaceText` | Aksesibilitas | Mengetikkan teks ke dalam kolom input formulir yang sedang fokus. | Hanya dapat mengetik pada elemen yang berstatus `isEditable == true`. |

### 4. Siklus Hidup Aplikasi & Pementasan Media

| Tool / Perintah | Izin Sistem | Deskripsi Fungsi | Batasan & Keamanan |
|---|---|---|---|
| `startApp` | Package Visibility | Membuka aplikasi media sosial target (TikTok, Instagram, YouTube, Facebook, Threads) menggunakan intent resmi. | Menggunakan izin overlay 1x1 untuk pengecualian Background Activity Launch (BAL) di Android 10+. |
| `killApp` | Aksesibilitas / Shell | Menutup proses aplikasi target untuk membebaskan RAM dan mencegah freeze. | Tanpa ADB, hanya bisa memanggil `killBackgroundProcesses`. Dengan ADB UID 2000, dapat menjalankan `am force-stop` tuntas. |
| `hasPackage` / `listPackages` | Package Visibility | Memeriksa apakah aplikasi media sosial terpasang di perangkat. | Hanya membaca daftar paket yang relevan dengan otomatisasi media sosial. |
| `stageMedia` | Internet / Storage | Mengunduh video/foto konten resmi dari server langsung ke galeri HP (`MediaStore`). | Memvalidasi kecocokan checksum SHA-256 sebelum file didaftarkan ke galeri perangkat. |
| `cleanupMedia` | Storage | Menghapus file sementara/staging setelah posting selesai agar penyimpanan HP tidak penuh. | Hanya menghapus file yang sebelumnya diunduh oleh agen GoSosmed di folder staging. |

### 5. Remote Live Streaming & Kontrol Browser (Dual-Mode)

| Fitur / Mode | Izin Sistem | Deskripsi Fungsi | Batasan & Keamanan |
|---|---|---|---|
| **Live Stream JPEG (1–2 FPS)** | Aksesibilitas | Mengalirkan tangkapan layar berpasangan (`frame_meta` JSON + binary JPEG murni) untuk pemantauan hemat kuota. | Menggunakan kendali 1-in-flight flow control (`FrameFlow`). Frame baru otomatis di-drop bila browser belum siap. |
| **MediaCodec H.264 (30–60 FPS)** | MediaProjection | Streaming video hardware zero-copy Surface H.264 dengan eliminasi jeda 250ms (`SpsFix`). | **Wajib Default OFF**. Memerlukan persetujuan dialog sistem OS `MediaProjection` dari pengguna setiap kali dinyalakan. |
| **Direct Local Web Server (Port 8765)** | Internet Lokal | Menyajikan web viewer interaktif langsung dari HP ke browser PC di jaringan Wi-Fi lokal yang sama. | **Hanya Aktif** bila toggle Remote dinyalakan DAN Aksesibilitas aktif DAN Wireless Debugging aktif. |

### 6. Transport Shell ADB Mandiri (UID 2000)

| Tool / Perintah | Izin Sistem | Deskripsi Fungsi | Batasan & Keamanan |
|---|---|---|---|
| `adbPair` | Wi-Fi Multicast | Memulai alur pairing ADB nirkabel lokal menggunakan protokol TLS + SPAKE2. | Bekerja sepenuhnya di dalam loopback lokal HP tanpa aplikasi pihak ketiga (Shizuku sudah dihapus). |
| `shell` | Shell ADB | Menjalankan perintah sistem Android tingkat lanjut dengan hak UID 2000 setara kabel USB. | **Daftar Putih Ketat**: Hanya 8 biner yang diizinkan (`am`, `input`, `pm`, `dumpsys`, `wm`, `settings`, `cmd`, `monkey`). Perintah berbahaya seperti `rm -rf`, `reboot`, atau `su` **DITOLAK KERAS**. |

---

## 🗺️ Peta Dokumentasi Publik

Dokumentasi repositori ini distrukturkan secara terpisah dan runtut agar mudah dipelajari:

### 1. Panduan Pengguna & Operasional (How-To Guides)
- **[SETUP-3-LANGKAH.md](SETUP-3-LANGKAH.md)**: Panduan visual 3 langkah mengaktifkan izin Aksesibilitas, izin Overlay (tampilkan di atas aplikasi lain), dan pairing ADB lokal nirkabel.
- **[PAIRING-FLOW.md](PAIRING-FLOW.md)**: Panduan menghubungkan HP ke server melalui 1-tap deep link (`gososmed://pair`) atau kode pairing 8-karakter.

### 2. Spesifikasi Teknis & Protokol Komunikasi (Reference)
- **[AGENT-COMMAND-CONTRACT.md](AGENT-COMMAND-CONTRACT.md)**: Kontrak kawat resmi protokol komunikasi WebSocket (amplop JSON, daftar 22 perintah, Two-Message JPEG protocol, dan kontrol sentuh realtime).
- **[TRANSPORT-ADB-LOKAL.md](TRANSPORT-ADB-LOKAL.md)**: Penjelasan arsitektur klien ADB tertanam (UID 2000) di dalam APK tanpa ketergantungan tool eksternal.
- **[DB-AND-API-MAP.md](DB-AND-API-MAP.md)**: Pemetaan keterkaitan antara field database backend, endpoint API, dan perintah agen di HP.
- **`contracts/`**: Kumpulan berkas *fixture* JSON kanonikal (`v1-fixtures.json`, `v2-fixtures.json`, `capability-fixture.json`) untuk validasi protokol.

### 3. Panduan Pengembang & Kompilasi (Development)
- **[TOOLCHAIN-LOKAL.md](TOOLCHAIN-LOKAL.md)**: Spesifikasi toolchain resmi (JDK 17, Gradle 8.9, Android SDK 34) untuk memproduksi build yang 100% identik dengan GitHub Actions CI.
- **[F0-LIBRARY-VALIDATION.md](F0-LIBRARY-VALIDATION.md)**: Catatan evaluasi dan pembuktian empiris pemilihan library pairing ADB nirkabel.
- **[SHIZUKU-TRANSPORT.md](SHIZUKU-TRANSPORT.md)**: Catatan arsip sejarah migrasi pelepasan Shizuku v0.8.0 menuju transport ADB lokal mandiri v0.9.0.

### 4. Rancang Bangun & Rencana Arsitektur (`plans/`)
Seluruh dokumen perencanaan dan desain teknis dikumpulkan secara rapi di dalam direktori `docs/plans/`:
- **[plans/PLAN-REMOTE-CONTROLLER-STREAMING-MEDIACODEC.md](plans/PLAN-REMOTE-CONTROLLER-STREAMING-MEDIACODEC.md)**: Rancang bangun lengkap engine video hardware MediaCodec H.264 zero-copy dan eliminasi latency WebCodecs.
- **[plans/ANALISIS-WEB-VIEWER-BROWSER-TANPA-INSTALL.md](plans/ANALISIS-WEB-VIEWER-BROWSER-TANPA-INSTALL.md)**: Analisis trilemma remote control tanpa instalasi di Windows, komparasi Tango Web, ws-scrcpy, dan audit biner iMyFone MirrorTo.
- **[plans/PLAN-DETERMINISTIC-ANDROID-PORTAL.md](plans/PLAN-DETERMINISTIC-ANDROID-PORTAL.md)**: Fondasi arsitektur Portal Deterministik Protocol v2 (8-tier selector, submit barrier, single mutation guard).
- **[plans/KOTLIN-PERFORMANCE-AND-REMOTE-CONTROL-ARCHITECTURE.md](plans/KOTLIN-PERFORMANCE-AND-REMOTE-CONTROL-ARCHITECTURE.md)**: Optimalisasi performa pemrosesan pohon elemen UI dan Set-of-Marks.
- **[plans/2026-10-22-remote-controller-mediacodec-dashboard.md](plans/2026-10-22-remote-controller-mediacodec-dashboard.md)**: Rencana integrasi kartu Remote Controller di dashboard Kotlin dan pengujian hybrid dual-mode.

### 5. Laporan Pengujian & Jaminan Mutu (`5-testing/`)
- **`5-testing/`**: Laporan audit verifikasi pengujian perangkat fisik, verifikasi 7 Quality Gates Protocol v2, dan penanganan stabilisasi memory leak Android.

---

## 🔒 Kebijakan Rilis & Integritas Build

Proyek ini menerapkan aturan rilis ketat untuk menjamin keamanan pengguna:
1. **Pemisahan Kanal Rilis**:
   - `vX.Y.Z`: Rilis **STABIL** untuk pengguna akhir, wajib telah diuji langsung pada ponsel fisik nyata dan dicatat pada `CHANGELOG.md`.
   - `vX.Y.Z-dev.N`: Rilis **DEV** (prerelease) untuk pengujian pengembang.
2. **Integritas Tanda Tangan APK**:
   - Seluruh rilis resmi ditandatangani melalui GitHub Actions menggunakan APK Signature Scheme v2/v3 dengan verifikasi `apksigner verify`. Keystore signing tidak pernah disimpan di repositori git publik.
3. **Penyelidikan Mandiri**:
   - Kompilasi dan pengujian unit dapat dilakukan secara mandiri di komputer Anda menggunakan perintah:
     ```bash
     gradle testDebugUnitTest assembleDebug
     ```
