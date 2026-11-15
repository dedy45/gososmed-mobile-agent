# Katalog Lengkap Tools & Spesifikasi Wire Contract APK (v1.2.21)

> **Lokasi File:** `docs/superpowers/GOSOSMED-MOBILE-AGENT-TOOL-CATALOG-MCP.md`  
> **Status:** KANONIK & TERUJI (100% Sesuai Kode Aktual Kotlin v1.2.21)  
> **Tanggal Rilis:** 2026-11-16  
> **Kanal Versi:** `v1.2.21` (versionCode: 61, dev release: `v1.2.21-dev.1`)  
> **Target Runtime:** Android 8.0+ (API 26..34) | Hak Istimewa: UID 10xxx (A11y) & UID 2000 (Local ADB Loopback)  

---

## 1. Ikhtisar Arsitektur Dual-Protocol

Aplikasi `gososmed-mobile-agent` menerima perintah masuk (inbound command) melalui koneksi WebSocket keluar (`/v1/agent/ws`) dari server GoSosmed atau MCP Hub. Terdapat dua format protokol yang didukung penuh:

1. **Protocol V1 (Legacy Envelope)**: Format permintaan JSON sederhana dengan field `cmd`, `id`, dan argumen datar.
2. **Protocol V2 (High-Reliability Automation Envelope)**: Format deterministik dengan field `protocol_version: 2`, `request_id`, `cmd`, `args`, penjejak snapshot state (`snapshot_id`), asersi mutasi layar (`mutation_guard`), dan compound action.

Aplikasi juga membagi perintah menjadi 2 kategori eksekusi:
- **`SERVICE_FREE_COMMANDS`**: Dapat dieksekusi langsung tanpa memerlukan `AgentAccessibilityService` aktif (misal: adbPair, shell, ping, health, stageMedia, localWebServer, screen power). Dijalankan di thread IO.
- **`ASYNC_BACKGROUND_COMMANDS`**: Perintah yang memerlukan aksesibilitas atau motorik UI, dijalankan di coroutine latar belakang tanpa memblokir Looper UI utama (anti-ANR).

---

## 2. Tabel Ringkasan Seluruh Command / Tools (48 Tools Terdaftar)

| No | Nama Command (`cmd`) | Kategori | Bebas A11y? | Async Coroutine? | Fungsi Utama |
|---|---|---|---|---|---|
| 1 | `ping` | Sistem & Jaringan | **YA** | Tidak | Cek konektivitas live agent-ke-server |
| 2 | `health` | Sistem & Jaringan | **YA** | Tidak | Snapshot kesehatan seluruh subsistem agent |
| 3 | `capabilities` | Sistem & Jaringan | **YA** | Tidak | Query fitur yang didukung APK saat ini |
| 4 | `package` | Observasi | Tidak | Tidak | Ambil nama package aplikasi di foreground |
| 5 | `hasPackage` | Kontrol Aplikasi | **YA** | Tidak | Cek apakah package tertentu terpasang di OS |
| 6 | `listPackages` | Kontrol Aplikasi | **YA** | Tidak | Daftar package aplikasi launcher terpasang |
| 7 | `startApp` | Kontrol Aplikasi | Tidak | **YA** | Luncurkan aplikasi dengan verifikasi foreground |
| 8 | `killApp` | Kontrol Aplikasi | Tidak | **YA** | Hentikan aplikasi (force-stop via shell jika ada) |
| 9 | `dump` | Observasi Layar | Tidak | Tidak | Dump hierarki pohon XML uiautomator |
| 10 | `dumpWindows` | Observasi Layar | Tidak | Tidak | Daftar jendela & layer sistem (deteksi popup/overlay) |
| 11 | `screenshot` | Observasi Layar | Tidak | Tidak | Ambil tangkapan layar polos (Base64 JPEG/PNG) |
| 12 | `annotatedScreenshot` | Observasi Visual AI | Tidak | **YA** | Screenshot Set-of-Marks Ember Orange + JSON elemen |
| 13 | `waitForNode` | Mesin Tunggu | Tidak | **YA** | Tunggu kemunculan elemen di RAM HP (polling 80ms) |
| 14 | `clickAndWait` | Compound Action | Tidak | **YA** | Ketuk elemen & langsung tunggu transisi halaman (1 turn) |
| 15 | `checkSecurityChallenge` | Keamanan & Anti-Ban | Tidak | **YA** | Deteksi fail-closed CAPTCHA, 2FA, puzzle, checkpoint |
| 16 | `nodeInspector` | Observasi Selektif | Tidak | **YA** | Inspeksi atribut detail 1 node tanpa dump XML |
| 17 | `tap` | Motorik Sentuh | Tidak | Tidak | Ketuk koordinat layar fisik `x, y` |
| 18 | `tapByText` | Motorik Sentuh | Tidak | Tidak | Cari elemen berdasarkan teks/deskripsi lalu klik |
| 19 | `tapFirstClickable` | Motorik Sentuh | Tidak | Tidak | Ketuk elemen interaktif pertama di jendela aktif |
| 20 | `swipe` | Motorik Sentuh | Tidak | Tidak | Usap layar dari `(x1, y1)` ke `(x2, y2)` |
| 21 | `setText` | Input Teks | Tidak | Tidak | Isi teks ke elemen fokus tanpa menghapus teks lama |
| 22 | `replaceText` | Input Teks | Tidak | **YA** | Hapus isi field, ketik teks baru, dan submit/tutup keyboard |
| 23 | `back` | Tombol Sistem | Tidak | Tidak | Tekan tombol Back sistem Android |
| 24 | `home` | Tombol Sistem | Tidak | Tidak | Tekan tombol Home sistem Android |
| 25 | `recents` | Tombol Sistem | Tidak | Tidak | Buka tampilan Recent Apps / App Switcher |
| 26 | `notify` | Tombol Sistem | Tidak | Tidak | Buka tirai notifikasi (Notification Shade) |
| 27 | `volume_up` | Kontrol Perangkat | Tidak | Tidak | Naikkan volume audio perangkat |
| 28 | `volume_down` | Kontrol Perangkat | Tidak | Tidak | Turunkan volume audio perangkat |
| 29 | `power` | Kontrol Perangkat | Tidak | Tidak | Tekan tombol fisik Power |
| 30 | `rotate` | Kontrol Perangkat | Tidak | Tidak | Putar orientasi layar |
| 31 | `wake` | Kontrol Layar | Tidak | **YA** | Nyalakan layar HP (WakeLock + keyevent) |
| 32 | `setDisplayPower` | Kontrol Layar | **YA** | Tidak | Nyalakan/matikan display via privileged shell |
| 33 | `globalDim` | Kontrol Layar | **YA** | Tidak | Redupkan layar hingga batas minimum hemat daya |
| 34 | `restartSystemUi` | Perbaikan Sistem | **YA** | Tidak | Restart SystemUI tanpa merestart kernel ponsel |
| 35 | `displaySize` | Display & Resolusi | **YA** | Tidak | Baca / setel resolusi layar fisik via `wm size` |
| 36 | `displayDensity` | Display & Resolusi | **YA** | Tidak | Baca / setel DPI layar fisik via `wm density` |
| 37 | `shell` | Privileged Shell | **YA** | Tidak | Jalankan perintah shell ADB dengan filter keamanan |
| 38 | `adbPair` | Pairing Nirkabel | **YA** | Tidak | Pairing loopback ADB internal 127.0.0.1 (UID 2000) |
| 39 | `stageMedia` | Media Gallery | **YA** | **YA** | Download file media ke DCIM & trigger MediaStore |
| 40 | `cleanupMedia` | Media Gallery | **YA** | **YA** | Bersihkan file uji coba di galeri tanpa ghost files |
| 41 | `scanMedia` / `addMedia`| Media Gallery | **YA** | **YA** | Trigger MediaScanner pada path file lokal |
| 42 | `setClipboard` | Clipboard & Teks | **YA** | **YA** | Setel teks ke clipboard sistem Android |
| 43 | `copyText` | Clipboard & Teks | Tidak | **YA** | Salin teks dari elemen UI ke clipboard |
| 44 | `pasteText` | Clipboard & Teks | Tidak | **YA** | Tempel isi clipboard ke input field |
| 45 | `eraseText` | Clipboard & Teks | Tidak | **YA** | Hapus $N$ karakter ke belakang (Backspace keyevent) |
| 46 | `waitForSettle` | Transisi Layar | Tidak | **YA** | Tunggu layar stabil tanpa ada animasi/mutasi baru |
| 47 | `startLocalServer` | Remote Control Web | **YA** | Tidak | Aktifkan server HTTP/WS lokal di port 8765/8766 |
| 48 | `stopLocalServer` | Remote Control Web | **YA** | Tidak | Hentikan server streaming lokal |
| 49 | `executeBatch` | Otomasi Batch | Tidak | **YA** | Jalankan serangkaian aksi berantai secara deterministik |

---

## 3. Detail Parameter & Format Respon Setiap Kelompok Perintah

### A. Observasi Layar & Analisis AI Vision

#### 1. `annotatedScreenshot` (Paling Direkomendasikan untuk Vision Model)
- **Tujuan:** Menghasilkan frame tangkapan layar beranotasi Set-of-Marks (kotak oranye bernomor ID) beserta metadata JSON array elemen interaktif. Memotong pemborosan token hingga 95% dibandingkan XML dump dan mencegah salah klik koordinat.
- **Request:**
  ```json
  {
    "id": 101,
    "cmd": "annotatedScreenshot",
    "scale": 0.5,
    "quality": 75,
    "roi": [0, 500, 1080, 1920]
  }
  ```
- **Response:**
  ```json
  {
    "id": 101,
    "ok": true,
    "result": {
      "image_base64": "/9j/4AAQSkZJRgABAQ...",
      "elements": [
        {
          "id": 1,
          "class": "Button",
          "text": "Posting",
          "bounds": [900, 2400, 1050, 2550],
          "center": { "x": 975, "y": 2475 }
        }
      ],
      "width": 1080,
      "height": 2400,
      "scale": 0.5,
      "quality": 75
    }
  }
  ```

#### 2. `checkSecurityChallenge` (Fail-Closed Security Guard)
- **Tujuan:** Memeriksa seluruh jendela interaktif secara in-memory untuk mendeteksi apakah layar menampilkan tantangan keamanan (CAPTCHA, Puzzle Slide, 2FA, SMS code, Suspicious Activity, Checkpoint Akun).
- **Request:**
  ```json
  { "id": 102, "cmd": "checkSecurityChallenge" }
  ```
- **Response:**
  ```json
  {
    "id": 102,
    "ok": true,
    "result": {
      "detected": true,
      "challenge_type": "puzzle_slide",
      "human_intervention_required": true
    }
  }
  ```

#### 3. `nodeInspector` (Observasi Cepat Hemat Token)
- **Tujuan:** Mengambil atribut lengkap sebuah node tanpa membaca keseluruhan pohon XML.
- **Request:**
  ```json
  { "id": 103, "cmd": "nodeInspector", "text": "Berikutnya" }
  ```
- **Response:**
  ```json
  {
    "id": 103,
    "ok": true,
    "result": {
      "class": "android.widget.Button",
      "package": "com.instagram.android",
      "text": "Berikutnya",
      "content_desc": "",
      "resource_id": "com.instagram.android:id/next_button",
      "clickable": true,
      "editable": false,
      "focused": false,
      "enabled": true,
      "scrollable": false,
      "password": false,
      "bounds": [800, 2200, 1020, 2350],
      "center": { "x": 910, "y": 2275 }
    }
  }
  ```

#### 4. `dumpWindows`
- **Tujuan:** Mendeteksi seluruh window dan layer OS (aplikasi, popup, dialog izin, overlay, soft keyboard IME) untuk mengetahui layer mana yang aktif.
- **Request:**
  ```json
  { "id": 104, "cmd": "dumpWindows" }
  ```
- **Response:**
  ```json
  {
    "id": 104,
    "ok": true,
    "result": {
      "activePackage": "com.instagram.android",
      "windows": [
        {
          "index": 0,
          "windowId": 42,
          "type": "APPLICATION",
          "isFocused": true,
          "isActive": true,
          "bounds": "[0,0][1080,2400]",
          "title": "Instagram",
          "package": "com.instagram.android",
          "hasRoot": true
        }
      ]
    }
  }
  ```

---

### B. Motorik Sentuh & Mesin Tunggu (Protokol v2 & Compound Action)

#### 5. `clickAndWait` (Compound Action: Tap + Tunggu Transisi)
- **Tujuan:** Menggabungkan aksi pengetukan dan penantian perubahan UI dalam 1 round-trip jaringan, menghemat 50% giliran token LLM.
- **Request:**
  ```json
  {
    "id": 105,
    "cmd": "clickAndWait",
    "click_text": "Lanjutkan",
    "wait_text": "Berhasil",
    "timeout_ms": 5000
  }
  ```
- **Response:**
  ```json
  {
    "id": 105,
    "ok": true,
    "result": {
      "clicked": true,
      "settled": true,
      "elapsed_ms": 320,
      "bounds": [100, 500, 980, 620],
      "center": { "x": 540, "y": 560 }
    }
  }
  ```

#### 6. `waitForNode`
- **Tujuan:** Menunggu kemunculan node tertentu di memori Android (polling tiap 80ms) tanpa overhead pembacaan XML berulang kali.
- **Request:**
  ```json
  {
    "id": 106,
    "cmd": "waitForNode",
    "text": "Posting",
    "timeout_ms": 4000
  }
  ```
- **Response:**
  ```json
  {
    "id": 106,
    "ok": true,
    "result": {
      "found": true,
      "elapsed_ms": 160,
      "bounds": [800, 2200, 1020, 2350],
      "center": { "x": 910, "y": 2275 }
    }
  }
  ```

#### 7. `replaceText` (Smart Text Input)
- **Tujuan:** Mengosongkan field input yang ada, menyetel teks baru secara aman (mendukung emoji dan karakter Unicode panjang), lalu opsional mengirimkan tombol ENTER (submit form) atau menutup keyboard virtual.
- **Request:**
  ```json
  {
    "id": 107,
    "cmd": "replaceText",
    "text": "Caption postingan otomatis #viral",
    "submit": true
  }
  ```
- **Response:**
  ```json
  {
    "id": 107,
    "ok": true,
    "result": {
      "replaced": true,
      "submitted": true,
      "keyboard_dismissed": false
    }
  }
  ```

---

### C. Privileged Shell & Transport ADB Lokal (UID 2000)

#### 8. `adbPair`
- **Tujuan:** Memasangkan agen ke daemon ADB internal perangkat via loopback `127.0.0.1` menggunakan kode pairing 6 digit dari layar Opsi Pengembang Android.
- **Request:**
  ```json
  {
    "id": 108,
    "cmd": "adbPair",
    "port": 37123,
    "code": "123456"
  }
  ```
- **Response:**
  ```json
  {
    "id": 108,
    "ok": true,
    "result": {
      "paired": true,
      "message": "pairing berhasil"
    }
  }
  ```

#### 9. `shell`
- **Tujuan:** Menjalankan perintah biner `adb shell` (UID 2000) yang diizinkan denylist keamanan (`am`, `pm`, `input`, `cmd`, `wm`, `dumpsys`, dll.).
- **Request:**
  ```json
  {
    "id": 109,
    "cmd": "shell",
    "command": "input keyevent 224",
    "timeout_ms": 2000
  }
  ```
- **Response:**
  ```json
  {
    "id": 109,
    "ok": true,
    "result": {
      "ok": true,
      "exit_code": 0,
      "stdout": "",
      "stderr": ""
    }
  }
  ```

---

### D. Manajemen Galeri Media (MediaStore Compliant)

#### 10. `stageMedia`
- **Tujuan:** Mengunduh berkas video/gambar dari URL eksternal ke direktori kamera fisik `/sdcard/DCIM/Camera`, memvalidasi hash SHA-256, dan mendaftarkannya ke database MediaStore Android agar langsung berada di urutan teratas galeri TikTok / Instagram.
- **Request:**
  ```json
  {
    "id": 110,
    "cmd": "stageMedia",
    "url": "https://example.com/video.mp4",
    "target_dir": "DCIM/Camera",
    "filename": "video_konten.mp4",
    "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  }
  ```

#### 11. `cleanupMedia`
- **Tujuan:** Menghapus file media hasil pengujian secara massal melalui ContentResolver MediaStore Android sehingga tidak menyisakan file rusak / thumbnail abu-abu di galeri.
- **Request:**
  ```json
  {
    "id": 111,
    "cmd": "cleanupMedia",
    "prefix": "video_konten"
  }
  ```

---

## 4. Protokol Streaming MediaCodec & Web Remote Control (Port 8765 / 8766)

Aplikasi memiliki embedded web server internal (`LocalWebServer.kt`) yang melayani:
- **Port 8765 (HTTP)**: Antarmuka Web Cockpit untuk melihat layar HP tanpa menginstal software tambahan di PC, manajemen file web (upload/download berkas langsung ke internal storage), dan inspeksi log real-time.
- **Port 8766 (WebSocket Video / Binary Stream)**: Aliran video perangkat keras H.264 / HEVC zero-copy langsung dari GPU encoder HP ke browser HTML5 via Broadway.js / VideoDecoder API.
