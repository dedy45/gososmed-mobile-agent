---
name: gososmed-mobile-mcp
description: 'Panduan kanonik dan spesifikasi lengkap pengoperasian 24 Tools, 3 Prompts, dan 2 Resources GoSosmed MCP Hub untuk kontrol fisik Android BYOD. Mencegah halusinasi dump(), menegakkan mesin tunggu native wait_for_node & click_and_wait, observasi visual Set-of-Marks annotated_screenshot, semantic click_element, dan 5 pilar arsitektur anti-fragile.'
version: 1.2.0
user-invocable: true
argument-hint: ""
---

# GoSosmed Mobile MCP Hub — Panduan Kanonik & Harness Automasi HP Fisik

Skill ini adalah **harness operasional resmi** bagi AI Agent (Claude Code, OpenAI Codex, Cursor IDE, Windsurf, ZeroClaw) untuk mengendalikan perangkat fisik Android BYOD melalui **GoSosmed MCP Hub** (`/mcp/sse`).

---

## 1. Arsitektur & Prinsip Kerja MCP Hub (Dual-Engine)

GoSosmed MCP Hub menghubungkan model AI langsung ke runtime ponsel Android melalui koneksi persisten WebSocket (`/v1/agent/ws`) yang dipadukan dengan **Dual-Engine Control Plane**:
1. **Accessibility Service (UID 10xxx)**: Pembacaan DOM in-memory secepat kilat (<2ms) dan Set-of-Marks visual painter.
2. **Local ADB Loopback (Port 5555, UID 2000)**: Eksekusi shell berizin istimewa (am/pm, MediaStore ContentResolver Scoped Storage bypass, reduksi kecerahan layar 1%).

```
┌──────────────────────────────────────────────┐
│       AI Agent (Claude / Codex / Cursor)     │
└───────────────────────┬──────────────────────┘
                        │ (JSON-RPC 2.0 via SSE / Streamable HTTP)
                        ▼
┌──────────────────────────────────────────────┐
│        GoSosmed MCP Server (:8080 / :8081)   │
│            internal/mcphub/                  │
│   - 24 Tools Resmi & Protokol v2             │
│   - Smart Auto-Serial Device Resolver        │
│   - 3 Built-in Prompts & 2 Live Resources    │
└───────────────────────┬──────────────────────┘
                        │ (Internal Persistent WebSocket Hub)
                        ▼
┌──────────────────────────────────────────────┐
│        HP Fisik Android (BYOD v1.2.0)        │
│   - AgentAccessibilityService (In-Memory)    │
│   - AnnotatedScreenshotHelper (ROI Zoom)     │
│   - Local Wireless ADB Loopback (Port 5555)  │
└──────────────────────────────────────────────┘
```

---

## 2. Lima Pilar Fundamental Anti-Fragile (Wajib Dipatuhi)

Semua keputusan observasi, navigasi, dan sentuhan WAJIB berpegang pada 5 pilar berikut:

### Pilar 0: In-Memory Fast-Path & Mesin Tunggu Native (ATURAN MUTLAK)
1. **DILARANG LOOP XML DUMP & SCREENSHOT UNTUK MENUNGGU**:
   - Pemanggilan `dump()` atau `screenshot()` berulang kali di dalam loop polling untuk menunggu layar baru **DIHARAMKAN**.
   - Setiap XML dump mentah memboroskan >50.000 token dan latensi 2-5 detik.
   - **WAJIB gunakan `wait_for_node`** atau **`click_and_wait`**: Sistem melakukan polling langsung di RAM HP (polling 80ms, latensi internal ~2ms) tanpa overhead XML.
2. **ZERO RAW COORDINATES**:
   - Dilarang keras menebak koordinat piksel mentah (`input tap X Y`).
   - Gunakan `click_element` (semantik) atau ambil koordinat pusat `center.x, center.y` dari elemen bernomor Set-of-Marks hasil `annotated_screenshot`.

### Pilar 1: Topological Dock Anchoring
- Tab navigasi profil/menu utama selalu berada di kuadran kanan bawah ($X > 75\%, Y \ge 85\%$ area aktif).
- Prioritaskan pencocokan semantik node (`click_element`) daripada posisi statis pixel.

### Pilar 2: Dynamic Inset Clamping
- Android memiliki bilah navigasi sistem (`com.android.systemui`).
- Titik sentuh vertikal wajib di-clamp: $Y_{\text{tap}} \le \text{SystemNavTop} - 100\text{ px} \approx 2495\text{ px}$ (pada layar standar 1220×2712) agar tidak salah menekan tombol Back/Home sistem OS.

### Pilar 3: Spatial Partitioning
- Ekstraksi profil/nama dibatasi ketat hanya pada zona header identitas ($Y \le 35\%$ tinggi layar aktif). Abaikan feed/komentar di bawah 35%.

### Pilar 4: Visual Set-of-Marks Grounding & ROI Zoom
- Observasi visual WAJIB menggunakan `annotated_screenshot`. Setiap elemen interaktif diberi nomor oranye `[1]`, `[2]`, dst.
- Gunakan parameter `roi: [x1, y1, x2, y2]` untuk memotong area fokus (misal kotak CAPTCHA puzzle) guna memangkas konsumsi token visual dari 1.200 menjadi ~85 token.

### Pilar 5: Strict Fail-Closed (Anti-Sukses Palsu)
- Dilarang menyatakan job selesai atau sukses sebelum ada bukti fisik nyata postingan tayang di profil/feed.
- Jika terdeteksi tantangan keamanan, segera panggil `check_security_challenge` dan jeda otomasi.

---

## 3. Tiga Aturan Emas Agen (Anti-Patterns vs Best Practices)

| Jangan Lakukan (Anti-Pattern) ❌ | Selalu Lakukan (Best Practice) ✅ |
| :--- | :--- |
| **Memanggil `dump()`** untuk membaca layar (boros >50K token, lambat >1.5s). | Panggil **`annotated_screenshot(scale=0.5)`** (cepat <300ms, hemat token, berpenanda Set-of-Marks). |
| **Menggunakan loop sleep manual** untuk menunggu transisi halaman. | Panggil **`wait_for_node(text=..., timeout_ms=4000)`** (polling 80ms di RAM HP). |
| **Klik lalu tunggu secara terpisah** (2 turn LLM terbuang). | Panggil **`click_and_wait`** (Compound Action Protokol v2 — 1 turn selesai). |
| **Menebak koordinat $X, Y$** dari visual kasar. | Ambil koordinat `center.x, center.y` dari elemen bernomor di JSON `annotated_screenshot`. |
| **Mengetik dengan `setText`** saat field sudah berisi teks lama. | Gunakan **`replace_text(text=..., submit=true)`** (otomatis bersihkan field dan sembunyikan soft keyboard). |

---

## 4. Alur Baku Automasi Mobile (The Canonical 6-Step Loop)

Setiap tugas automasi wajib mengikuti alur baku berikut:

```
[1. Luncurkan App] ──► startApp(package="com.instagram.android")
                             │
[2. Tunggu Siap]   ──► wait_for_node(text="Beranda" / content_desc="Home", timeout_ms=5000)
                             │
[3. Observasi]     ──► annotated_screenshot(scale=0.5) ──► Dapatkan elemen [1], [2], [3]
                             │
[4. Aksi Cerdas]   ──► click_and_wait(click_text="Buat", wait_text="Posting", timeout_ms=4000)
                       ATAU click_element(text="Berikutnya")
                             │
[5. Input Teks]    ──► replace_text(text="Caption konten #viral", submit=true)
                             │
[6. Verifikasi]    ──► wait_for_node(text="Dibagikan" / "Just now", timeout_ms=10000)
                       check_security_challenge() ──► Pastikan zero checkpoint
```

---

## 5. Katalog Lengkap 24 Tools MCP GoSosmed

### Kelompok A: Observasi Visual & Layar
1. **`annotated_screenshot` (PRIMARY OBSERVATION)**:
   - Input: `scale: 0.5` (opsional), `quality: 75`, `roi: [x1, y1, x2, y2]` (opsional untuk crop zoom), `serial`
   - Output: `image_base64` JPEG berkotak nomor oranye `[1]`, `[2]`, dan array JSON `elements`:
     ```json
     { "id": 1, "class": "Button", "text": "Posting", "bounds": [1010,2520,1130,2640], "center": {"x": 1070, "y": 2580} }
     ```
2. **`wait_for_node` (PRIMARY WAITING ENGINE)**:
   - Input: `text`, `content_desc`, `resource_id`, `timeout_ms: 4000`, `serial`
   - Output: `{"found": true, "elapsed_ms": 120}`
   - *Kelebihan: Deteksi langsung di memori HP, tanpa serialisasi XML.*
3. **`check_security_challenge` (GUARD CAPTCHA/2FA)**:
   - Input: `serial`
   - Output: `{"detected": true, "challenge_type": "puzzle_slide", "human_intervention_required": true}`
4. **`node_inspector`**:
   - Input: `text`, `resource_id`, `content_desc`, `serial`
   - Output: Detail bounds, center point, dan kelas node tanpa dump penuh.
5. **`screenshot`**: Mengambil frame gambar polos mentah tanpa overlay Set-of-Marks.
6. **`dump` (LEGACY - HINDARI)**: Dump XML uiautomator mentah. Hanya gunakan jika selektor semantik gagal total.

### Kelompok B: Sentuhan & Input Teks (Protokol v2)
7. **`click_and_wait` (PRIMARY COMPOUND ACTION — HEMAT 50% TOKEN & ROUND-TRIP)**:
   - Input klik: `click_text`, `click_desc`, `click_res_id`, atau `click_x` + `click_y`
   - Input tunggu: `wait_text`, `wait_desc`, `wait_res_id`, `timeout_ms: 4000`
   - Mengetuk target lalu **LANGSUNG** menunggu transisi UI di RAM HP dalam satu panggilan tool.
8. **`click_element` (SEMANTIC CLICK)**:
   - Input: `text`, `content_desc`, `resource_id`, `serial`
   - Menjalankan aksi native Android `AccessibilityNodeInfo.ACTION_CLICK`.
9. **`tap`**:
   - Input: `x`, `y`, `serial` (ambil dari koordinat `center.x, center.y` hasil `annotated_screenshot`).
10. **`replace_text` (PRIMARY TEXT INPUT)**:
    - Input: `text` (wajib), `submit: true/false` (opsional IME Search/Go), `serial`
    - Mengosongkan field, mengisi teks via Unicode clipboard, dan menutup virtual keyboard.
11. **`setText`**: Ketik teks ke field yang sedang fokus tanpa menghapus teks lama.
12. **`swipe`**: Input `x1`, `y1`, `x2`, `y2`, `duration_ms: 300` (untuk scroll timeline/feed/reels).
13. **`press_key`**: Kirim tombol sistem navigasi: `ENTER`, `SEARCH`, `BACK`, `HOME`.
14. **`back`**: Menekan tombol Back sistem Android.
15. **`home`**: Menekan tombol Home sistem Android.

### Kelompok C: Kontrol Aplikasi
16. **`startApp`**:
    - Input: `package` (`com.instagram.android`, `com.ss.android.ugc.trill`, `com.google.android.youtube`, dll.)
17. **`search_mobile_app`**:
    - Otomasi multi-step pencarian kata kunci live pada TikTok, Instagram, YouTube, atau Shopee.

### Kelompok D: Pengelolaan Media Galeri (Android MediaStore Compliant)
18. **`stage_media`**:
    - Input: `media_url` (wajib), `filename` (opsional)
    - Mengunduh aset media ke `/sdcard/DCIM/Camera` dan memicu MediaScanner agar langsung muncul teratas di galeri aplikasi.
19. **`list_media`**: Memeriksa file media aktif pada galeri perangkat.
20. **`cleanup_media`**:
    - Menghapus massal video bekas pengujian (`gosmed_*`, `reel_*`, `staged_*`) menggunakan ContentResolver MediaStore Android tanpa meninggalkan ghost files.

### Kelompok E: Manajemen Perangkat & Akun
21. **`list_devices`**: Daftar perangkat fisik terhubung, status baterai, dan ketersediaan agent online.
22. **`check_device_status`**: Diagnostik mendalam aksesibilitas, WebSocket link, dan status ADB loopback.
23. **`list_accounts`**: Daftar akun media sosial yang terikat ke perangkat Android.
24. **`schedule_post`**: Menjadwalkan postingan konten langsung ke antrian PostgreSQL GoSosmed.

---

## 6. Smart Auto-Serial Fallback

Setiap tool yang memiliki parameter `serial` dapat dipanggil dengan **`serial: ""`** (atau dikosongkan):
- Jika tepat 1 perangkat fisik sedang online di tenant Anda, MCP Hub **otomatis memilih perangkat tersebut**.
- Agen tidak perlu menghafal atau meng-hardcode nomor serial HP.

---

## 7. Built-in MCP Prompts & Live Resources

* **Prompts**:
  1. `mobile_observation_sop`: Prosedur kanonik observasi layar tanpa tebak koordinat.
  2. `universal_app_automation`: Template automasi segala aplikasi Android.
  3. `self_healing_sentinel`: Prosedur pemulihan saat mendeteksi dialog izin atau CAPTCHA.
* **Resources**:
  1. `gososmed://guidelines/anti-fragile`: Panduan 5 pilar anti-fragile.
  2. `gososmed://devices/status`: Data status telemetri perangkat live.
