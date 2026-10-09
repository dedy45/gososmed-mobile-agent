# GoSosmed Mobile Agent

**Enterprise-Grade Android Automation Engine untuk Arsitektur GoSosmed BYOD (Bring Your Own Device).**

Aplikasi ini menghubungkan HP Android fisik ke server otomasi GoSosmed **tanpa PC perantara, tanpa root, dan tanpa aplikasi pihak ketiga**. Menggantikan model peternakan HP sewaan (*datacenter emulator farm*) yang rawan terdeteksi fraud, GoSosmed mengorkestrasi perangkat fisik nyata dengan identitas perangkat dan jaringan seluler rumahan yang 100% legal, aman, dan anti-banned.

🌐 **[Bahasa Indonesia](README.md)** • **[English](README.en.md)**

[![Build APK](https://github.com/dedy45/gososmed-mobile-agent/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/dedy45/gososmed-mobile-agent/actions/workflows/build.yml)
[![Rilis terbaru](https://img.shields.io/github/v/release/dedy45/gososmed-mobile-agent?include_prereleases&label=rilis)](https://github.com/dedy45/gososmed-mobile-agent/releases)
[![SaaS Platform](https://img.shields.io/badge/SaaS-bamsbung.id-FF7A2F)](https://bamsbung.id)
[![Dokumentasi](https://img.shields.io/badge/docs-docs.bamsbung.id-4f46e5)](https://docs.bamsbung.id)
[![System Status](https://img.shields.io/badge/status-status.bamsbung.id-10b981)](https://status.bamsbung.id)
[![Lisensi](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

> **Versi Aktif: v1.2.0 (Stabil).**  
> Sumber kebenaran versi (*Single Source of Truth*): `app/build.gradle.kts` (`versionName`) dan [GitHub Releases](https://github.com/dedy45/gososmed-mobile-agent/releases).  
> **Mandiri & Terintegrasi Penuh:** Sejak v0.9.0, transport shell setara ADB UID 2000 terintegrasi langsung di dalam APK. Anda **tidak lagi membutuhkan Shizuku** atau perkakas tambahan pihak ketiga.

---

## 🏛️ Arsitektur Dual-Engine Control Plane

GoSosmed Agent merekayasa arsitektur **Dual-Engine** native Android yang bekerja secara paralel tanpa saling mengganggu:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                             PERANGKAT ANDROID FISIK                         │
├──────────────────────────────────────┬──────────────────────────────────────┤
│  ENGINE 1: ACCESSIBILITY SERVICE     │  ENGINE 2: LOCAL ADB PRIVILEGED      │
│  (UID 10xxx — Latensi ~2ms)          │  (UID 2000 — 127.0.0.1:5555 Loopback)│
├──────────────────────────────────────┼──────────────────────────────────────┤
│ • In-Memory UI Traversal (Depth 8+)  │ • Bypass Scoped Storage Android 10+  │
│ • Pelukis Set-of-Marks (Ember 2048)  │ • Peluncur Aplikasi Langsung (am)   │
│ • Deteksi Elemen Semantik Tak-Klik   │ • MediaScanner Broadcast Trigger     │
│ • Refleks Native Fast-Polling (80ms) │ • Mode Kunci Kecerahan Global (1%)   │
│ • Aksi Semantik ACTION_CLICK         │ • Shell Whitelist Terkendali         │
└──────────────────────────────────────┴──────────────────────────────────────┘
                                  │
                  Koneksi Keluar (Outbound WSS / TLS 1.3)
                  Heartbeat · Auto-Reconnect · Anti-DDoS
                                  ▼
                [ GoSosmed Cloud / Local MCP Server ]
```

---

## ⚡ Matriks Kapabilitas Perintah (v1.1.0+)

APK ini menyediakan **28+ perintah native** berkinerja tinggi yang dirancang khusus untuk efisiensi token AI dan stabilitas publishing:

| Kategori | Perintah Native | Keunggulan Arsitektur & Efisiensi Token |
|---|---|---|
| **AI Vision & Marks** | `annotatedScreenshot` | Menghasilkan Set-of-Marks berpenanda nomor oranye `[1]`, `[2]` tepat di atas tombol fisik. Dilengkapi **Region of Interest (ROI)** sub-region cropping untuk zoom CAPTCHA/puzzle dengan token super hemat (~85 token). |
| **Compound Action** | `clickAndWait` | Mengetuk elemen target dan langsung menunggu transisi layar muncul dalam satu siklus memori HP. **Memangkas 50% putaran giliran komunikasi LLM.** |
| **Refleks Cepat** | `waitForNode` | Native in-memory polling (80ms, latensi <200ms) tanpa overhead serialisasi XML mentah. |
| **Teks & Formulir** | `replaceText` | Mengosongkan kolom, mengetik teks unicode (emoji & karakter lokal), otomatis menyembunyikan keyboard, dan opsi `submit: true` untuk eksekusi pencarian instan. |
| **Siklus Media** | `stageMedia` | Pengunduhan aset video/foto via HTTP mandiri langsung ke MediaStore (`DCIM/Camera`) dengan validasi ukuran byte dan checksum SHA-256. |
| **Pembersih Galeri** | `cleanupMedia` | Pembersihan berkas duplikat/sementara secara legal melalui `ContentResolver.delete()` dan `MediaScannerConnection` agar galeri bersih tanpa file hantu. |
| **Deterministik v2** | `observe`, `resolve`, `actAndVerify` | Eksekusi berbasis `snapshot_id`, verifikasi transisi settle, resolusi *clickable ancestor*, dan proteksi ambiguitas (*zero ambiguous taps*). |
| **Manajemen Daya** | `globalDim` | Mengunci kecerahan sistem ke 1% via shell ADB agar baterai dingin dan layar AMOLED bebas *burn-in* selama automasi 24/7. |
| **Diagnostik Sistem** | `health`, `capabilities` | Pelaporan komprehensif status koneksi WS, kesiapan Accessibility, baterai, suhu, dan lisensi pabrikan (OEM). |

---

## 🔒 Pusat Kepercayaan & Privasi (Enterprise Trust Center)

Karena agent ini berjalan di perangkat pribadi dan meminta izin **Accessibility Service**, transparansi keamanan adalah prioritas nomor satu:

### 1. Prinsip Zero-Spyware & Anti-Keylogger
* **Redaksi Sandi Otomatis:** Seluruh kolom bertipe kata sandi Android otomatis diredaksi menjadi `[REDACTED]`. Sistem tidak pernah membaca, mencatat, atau mentransmisikan kata sandi akun sosial media Anda ke server mana pun.
* **Sesi Login Tetap di HP:** Pengguna melakukan login secara manual di aplikasi resmi (Instagram, TikTok, dsb.). Kredensial tidak pernah keluar dari penyimpanan aman aplikasi target.

### 2. Isolasi Penyimpanan Berkas (Strict Scoped Storage Isolation)
* **Kamera Pribadi 100% Aman:** Logika penghapusan media dikunci secara kriptografis dan selektif hanya pada awalan `gosmed_*`, `reel_*`, dan `staged_*`.
* **Berkas Pribadi Terlindungi:** Foto keluarga, video pribadi, dan dokumen kamera (`IMG_*`, `VID_*`, WhatsApp Media) **dijamin secara hukum dan kode tidak akan pernah disentuh atau dihapus**.

### 3. Prinsip Hak Akses Terkecil (Least-Privilege Shell)
* Eksekusi shell tingkat sistem dikunci ketat di dalam `PrivilegedShell.ALLOWED_BINARIES`: hanya mengizinkan `am`, `input`, `pm`, `dumpsys`, `wm`, `settings`, `cmd`, `rm`, dan `svc`.
* Perintah berbahaya seperti membaca SMS, membaca kontak buku telepon, atau memodifikasi file OS diblokir secara mutlak pada tingkat kernel APK.

### 4. Keamanan Jaringan Searah (Outbound-Only TLS)
* HP Anda tidak memerlukan IP publik, tidak memerlukan port masuk yang terbuka, dan tidak dapat diakses dari luar. Semua komunikasi terjalin melalui WebSocket keluar (*outbound persistent TLS 1.3*) yang diotentikasi via pairing token.

---

## ⚠️ Kepatuhan Distribusi Google Play

Agent ini memanfaatkan **Accessibility Service untuk otomasi antarmuka**. Kebijakan publik Google Play Store melarang penggunaan Accessibility Service untuk keperluan selain aksesibilitas disabilitas umum.

| Jalur Distribusi | Status | Keterangan |
|---|---|---|
| **Sideload APK Langsung (BYOD)** | **Resmi Didukung** | Jalur standar untuk pemilik perangkat fisik |
| **Play Console Internal / Closed Track** | Didukung | Untuk keperluan pengujian organisasi tertutup |
| **Play Store Publik (Production)** | Tidak Tersedia | Kebijakan Google melarang kategori automasi |

---

## 🛠️ Panduan Pemasangan & Persiapan (Setup 3 Langkah)

```bash
# Pasang biner APK via ADB (atau salin langsung ke penyimpanan HP)
adb install -r app-debug.apk
```

1. **Langkah 1 — Aksesibilitas (Wajib):**  
   Buka **Setelan → Aksesibilitas → GoSosmed Agent** → Aktifkan.
2. **Langkah 2 — Tampilkan di Atas Aplikasi Lain (Wajib):**  
   Buka **Setelan → Aplikasi → GoSosmed Agent → Tampilkan di atas aplikasi lain** → Aktifkan. (Pada ROM Xiaomi/HyperOS, aktifkan juga *Autostart*).
3. **Langkah 3 — Wireless ADB / Kontrol Lanjutan (Sangat Disarankan):**  
   Aktifkan **Opsi Pengembang → Debug Nirkabel**. Tekan tombol **Hubungkan** di aplikasi agent untuk memicu pairing lokal otomatis ke `127.0.0.1`.
4. **Layar Redup Otomasi (Opsional):**  
   Nyalakan switch **"Layar Redup Otomasi"** di bawah Wireless ADB untuk mengunci kecerahan layar ke 1% saat agent bekerja semalaman.

---

## 🏗️ Kompilasi dari Sumber (Build Instructions)

### Cara 1 — GitHub Actions CI/CD (Rekomendasi Otomatis)
Setiap kali ada commit pada branch `main` atau `dev`, pipeline GitHub Actions akan mem-build APK secara bersih dan mempublikasikannya ke tab Artifacts/Releases:
```bash
gh workflow run build-apk --repo dedy45/gososmed-mobile-agent
```

### Cara 2 — Kompilasi Lokal (Windows / macOS / Linux)
Prasyarat: JDK 17, Android SDK Platform 34, Build-Tools 34.0.0.
```powershell
# Di Windows PowerShell:
git clone https://github.com/dedy45/gososmed-mobile-agent.git
cd gososmed-mobile-agent

# Build APK Debug
gradle assembleDebug     # Hasil: app/build/outputs/apk/debug/app-debug.apk

# Build APK Release (Signed)
gradle assembleRelease
```

---

## 📜 Lisensi & Integritas Perangkat Lunak

Repositori ini dilisensikan di bawah **[Apache License 2.0](LICENSE)** — Copyright © 2026 Dedy (dedy45).

* **Integritas Rilis:** Setiap biner APK yang dipublikasikan secara resmi diverifikasi menggunakan tanda tangan digital kriptografis (*Signed Release*) dan checksum SHA-256 yang tercantum pada [GitHub Releases Tags](https://github.com/dedy45/gososmed-mobile-agent/releases).
* **Arsitektur Bersih (*Clean-Room Architecture*):** Seluruh logika kontrol memori, format pesan, dan serializer dibangun secara independen mengikuti standar terbuka Android Open Source Project (AOSP).

---

## 🤖 Integrasi AI Agent & Skill Resmi (MCP)

Repositori ini menyertakan harness operasional dan skill kanonik untuk AI Coding Agent (Claude Code, OpenAI Codex, Cursor IDE, Windsurf):
* **File Skill Resmi:** [`skills/gososmed-mobile-mcp/SKILL.md`](skills/gososmed-mobile-mcp/SKILL.md)
* **Pemasangan Instan (1-Liner):**
  ```bash
  # Untuk Claude Code
  mkdir -p .claude/skills/gososmed-mobile-mcp && \
  curl -fsSL https://raw.githubusercontent.com/dedy45/gososmed-mobile-agent/main/skills/gososmed-mobile-mcp/SKILL.md -o .claude/skills/gososmed-mobile-mcp/SKILL.md

  # Untuk OpenAI Codex / Cursor / Universal Agent
  mkdir -p .agents/skills/gososmed-mobile-mcp && \
  curl -fsSL https://raw.githubusercontent.com/dedy45/gososmed-mobile-agent/main/skills/gososmed-mobile-mcp/SKILL.md -o .agents/skills/gososmed-mobile-mcp/SKILL.md
  ```

---

## 🌐 Ekosistem & Layanan Resmi Bamsbung

* **Platform SaaS Utama:** [bamsbung.id](https://bamsbung.id)
* **Dokumentasi Resmi:** [docs.bamsbung.id](https://docs.bamsbung.id)
* **Status Sistem & Ketersediaan Layanan:** [status.bamsbung.id](https://status.bamsbung.id)
* **Repositori GitHub:** [github.com/dedy45/gososmed-mobile-agent](https://github.com/dedy45/gososmed-mobile-agent)
