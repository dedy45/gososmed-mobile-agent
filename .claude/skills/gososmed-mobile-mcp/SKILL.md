---
name: gososmed-mobile-mcp
description: 'Panduan kanonik dan spesifikasi lengkap pengoperasian 24 Tools, 3 Prompts, dan 2 Resources GoSosmed MCP Hub untuk kontrol fisik Android BYOD. Mencegah halusinasi dump(), menegakkan observasi visual Set-of-Marks annotated_screenshot, semantic click_element, in-memory wait_for_node, dan 4 pilar arsitektur anti-fragile.'
version: 1.0.0
user-invocable: true
argument-hint: ""
---

# GoSosmed Mobile MCP Hub — Panduan Kanonik & Harness Automasi HP Fisik

(Mirror kanonik dari `.claude/skills/gososmed-mobile-mcp/SKILL.md`)

Skill ini adalah **harness operasional resmi** bagi AI Agent (Claude, Cursor, Codex, ZeroClaw) untuk mengendalikan perangkat fisik Android BYOD melalui **GoSosmed MCP Hub** (`/mcp/sse`).

---

## 1. Arsitektur & Prinsip Kerja MCP Hub

GoSosmed MCP Hub menghubungkan model AI langsung ke runtime ponsel Android melalui koneksi persisten WebSocket (`/v1/agent/ws`) yang dipadukan dengan Accessibility Service dan Local ADB (UID 2000).

```
┌─────────────────────────────────┐
│     AI Agent (Claude / Codex)   │
└────────────────┬────────────────┘
                 │ (JSON-RPC 2.0 via SSE / Streamable HTTP)
                 ▼
┌─────────────────────────────────┐
│   GoSosmed Backend (:8080/8081) │
│       internal/mcphub/          │
│   - 24 Tools                    │
│   - 3 Built-in Prompts          │
│   - 2 Live Resources            │
└────────────────┬────────────────┘
                 │ (Internal WebSocket Hub)
                 ▼
┌─────────────────────────────────┐
│  HP Fisik (Xiaomi Redmi Note)   │
│  - AgentAccessibilityService    │
│  - AnnotatedScreenshotHelper    │
│  - Local ADB Loopback (Port 5555)│
└─────────────────────────────────┘
```

---

## 2. Empat Pilar Arsitektur Anti-Fragile (Wajib Dipatuhi)

Semua keputusan navigasi dan sentuhan wajib berpegang pada 4 pilar:

1. **Pilar 1: Topological Dock Anchoring**
   - Tab navigasi profil/menu utama selalu berada di kuadran kanan bawah ($X > 75\%, Y \ge 85\%$ area aktif).
   - Selalu prioritaskan `click_element` berbasis semantik node daripada koordinat absolut.

2. **Pilar 2: Dynamic Inset Clamping**
   - Fallback ketukan wajib di-clamp: $Y \le 2495\text{ px}$ (pada resolusi 1220×2712) agar tidak menabrak bilah navigasi 3-tombol sistem Android (`com.android.systemui`).

3. **Pilar 3: Spatial Partitioning**
   - Ekstraksi profil/nama dibatasi ketat hanya pada zona header identitas ($Y \le 35\%$ tinggi layar). Abaikan feed/komentar di bawah 35%.

4. **Pilar 4: Visual Set-of-Marks Grounding**
   - Observasi visual WAJIB menggunakan `annotated_screenshot`. Setiap elemen interaktif diberi nomor oranye `[1]`, `[2]`, dst. Ketuk menggunakan koordinat pusat `center.x, center.y`.

---

## 3. Tiga Aturan Emas Agen (Anti-Patterns vs Best Practices)

| Jangan Lakukan (Anti-Pattern) ❌ | Selalu Lakukan (Best Practice) ✅ |
| :--- | :--- |
| **Memanggil `dump()`** untuk melihat layar (boros ribuan token, lambat >1.5s, tidak ada visual). | Panggil **`annotated_screenshot(scale=0.5)`** (cepat <400ms, hemat token, ada kotak nomor presisi). |
| **Menggunakan `time.Sleep()`** atau loop polling untuk menunggu layar baru. | Panggil **`wait_for_node(text=..., timeout_ms=4000)`** (in-memory polling 80ms, latency ~2ms). |
| **Menebak koordinat $X, Y$** dari visual kasar. | Ambil koordinat `center.x, center.y` dari elemen bernomor di JSON `annotated_screenshot`, atau pakai `click_element`. |
| **Mengetik dengan `setText`** saat field sudah berisi teks lama. | Gunakan **`replace_text(text=...)`** (otomatis bersihkan field dan sembunyikan soft keyboard). |

---

## 4. Katalog Lengkap 24 Tools MCP GoSosmed

### Kelompok A: Observasi Visual & Layar
* **`annotated_screenshot` (PRIMARY)**:
  * Input: `scale: 0.5` (opsional), `quality: 75`, `serial`
  * Output: `image_base64` JPEG berkotak nomor oranye `[1]`, `[2]`, dan array JSON `elements`:
    ```json
    { "id": 1, "class": "Button", "text": "Profil", "bounds": [1010,2520,1130,2640], "center": {"x": 1070, "y": 2580} }
    ```
* **`wait_for_node` (PRIMARY WAITING)**:
  * Input: `text`, `content_desc`, `resource_id`, `timeout_ms: 4000`, `serial`
  * Output: `{"found": true, "elapsed_ms": 120}`
* **`check_security_challenge`**:
  * Input: `serial`
  * Output: Mendeteksi CAPTCHA puzzle, verifikasi SMS/2FA, atau checkpoint akun (`{"detected": true/false}`).
* **`node_inspector`**:
  * Input: `text` (filter teks), `serial`
  * Output: Detail bounds, center point, dan kelas node yang cocok tanpa dump penuh.
* **`screenshot`**:
  * Mengambil frame gambar polos mentah tanpa overlay.
* **`dump` (LEGACY - HINDARI)**:
  * Dump hierarki XML mentah uiautomator. Hanya digunakan jika selektor semantik gagal.

### Kelompok B: Sentuhan & Input Teks
* **`click_element` (PRIMARY ACTION)**:
  * Input: `text`, `content_desc`, `resource_id`, `serial`
  * Eksekusi `AccessibilityNodeInfo.ACTION_CLICK` native.
* **`tap`**:
  * Input: `x`, `y`, `serial` (ambil dari `center.x, center.y` hasil `annotated_screenshot`).
* **`replace_text` (PRIMARY INPUT)**:
  * Input: `text` (wajib), `serial`
  * Kosongkan field, ketik via clipboard unicode, kirim KEYEVENT_BACK untuk tutup keyboard virtual.
* **`setText`**: Ketik teks ke field yang sedang fokus tanpa menghapus teks lama.
* **`swipe`**: Input `x1`, `y1`, `x2`, `y2`, `duration_ms` (geser layar untuk scroll reels/feed).
* **`press_key`**: Kirim tombol sistem: `ENTER`, `SEARCH`, `BACK`, `HOME`.
* **`back`**: Tekan tombol Back.
* **`home`**: Tekan tombol Home.
* **`click_and_wait` (COMPOUND ACTION — hemat 50% round-trip)**:
  * Input klik: `click_text`, `click_desc`, `click_res_id`, atau `click_x` + `click_y`
  * Input tunggu: `wait_text`, `wait_desc`, `wait_res_id`, `timeout_ms` (default 4000)
  * Mengetuk target lalu LANGSUNG menunggu transisi UI di memori HP dalam 1 panggilan tool.
  * Fallback otomatis ke `click_element` + `wait_for_node` bila APK belum mendukung `clickAndWait`.

### Kelompok C: Aplikasi & Otomasi
* **`startApp`**:
  * Input: `package` (`com.instagram.android`, `com.ss.android.ugc.trill`, `youtube`, dll.)
* **`search_mobile_app`**:
  * Automasi multi-step pencarian kata kunci pada TikTok/Instagram/Shopee.

### Kelompok D: Pengelolaan Media Galeri
* **`stage_media` / `stage_media_to_device`**:
  * Input: `media_url` (wajib), `filename` (opsional)
  * Unduh video/gambar ke `/sdcard/DCIM/Camera` dan broadcast `MEDIA_SCANNER_SCAN_FILE`.
* **`list_media`**: Melihat file di penyimpanan bersama dan rekaman Android MediaStore.
* **`cleanup_media`**: Hapus massal video bekas uji coba (`gosmed_*.mp4`, `reel_*.mp4`).

### Kelompok E: Manajemen Perangkat & Akun
* **`list_devices`**: Daftar perangkat aktif, serial, baterai, status online.
* **`check_device_status`**: Diagnostik mendalam aksesibilitas, koneksi WS, dan ADB.
* **`list_accounts`**: Daftar akun media sosial yang terikat ke perangkat.
* **`schedule_post`**: Masukkan postingan ke antrian database GoSosmed.

---

## 5. Built-in MCP Prompts (Siap Dipakai Klien)

MCP Hub menyediakan 3 template prompt universal:
1. `mobile_observation_sop`: Prosedur kanonik observasi layar dan eksekusi presisi tanpa tebak koordinat.
2. `universal_app_automation`: SOP automasi universal segala aplikasi Android (E-Commerce, Chat, Medsos, Browser, Utility) berbasis parameter `package` dan `goal`.
3. `self_healing_sentinel`: Prosedur pemulihan Tier-2 saat automasi mendeteksi popup perizinan OS, dialog update, atau CAPTCHA.

---

## 6. Built-in MCP Resources (Live Grounding)

MCP Hub menyediakan 2 live resources:
1. `gososmed://guidelines/anti-fragile`: Teks markdown 4 pilar arsitektur anti-fragile.
2. `gososmed://devices/status`: Data JSON live seluruh perangkat Android yang sedang online.

---

## 7. Resep Standar Automasi Universal Aplikasi Android

```text
Kasus: "Buka aplikasi target dan jalankan aksi"
1. Luncurkan aplikasi: startApp(package="com.example.app")
2. Observasi visual: annotated_screenshot(scale=0.5)
3. Dari JSON elements, temukan elemen target yang sesuai:
   {"id": 3, "text": "Pencarian", "center": {"x": 540, "y": 320}}
4. Eksekusi interaktif: click_element(text="Pencarian") atau tap(x=540, y=320)
5. Input jika diperlukan: replace_text(text="kata kunci")
6. Sinkronisasi: wait_for_node(text="Hasil", timeout_ms=3000)
7. Selesai (<1 detik, 0 salah klik, 100% presisi dan agnostik platform).
```
