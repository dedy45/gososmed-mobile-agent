# AUDIT & DEEP DEBUG: AGENT AI HUB (/agent) — DESIGN, TATA LETAK, DEDUPLIKASI & STABILITAS SISTEM

> **Target Evaluasi:** https://dev-app.bamsbung.id/agent
> **Target Platform:** Web Desktop (1440x900, 1280x800), Tablet Landscape/Portrait (1024x768, 768x1024), Mobile (375x812)
> **Live Backend Target:** WebSocket wss://dev-api.bamsbung.id/v1/agent/chat & Device Bridge agent-ef08ec4a (Xiaomi fisik)
> **Tanggal Audit:** 30 September 2026
> **Dokumentasi Visual:** 15 Tangkapan Layar resolusi tinggi tersedia di docs/5-testing/screenshots-agent-audit/

---

## 1. Executive Summary

Audit menyeluruh dan mendalam telah dilakukan pada halaman **Agent AI GoSosmed (/agent)** dengan fokus eksklusif pada halaman ini:
1. **Deduplikasi Navigasi & Elemen UI:** Ditemukan redundansi tombol aksi utama (tombol Tools dan Layar HP muncul duplikat di 2-3 lokasi berbeda sekaligus).
2. **Tabrakan & Konflik Tata Letak Panel Kanan (Layout Collision):** Panel Layar Live HP (w-420, z-auto) dan drawer Katalog & Inspector MCP Tools (w-480, z-50) menempati koordinat horizontal yang sama (x: 960-1440), saling menutupi satu sama lain tanpa sistem docking terpadu (Unified Dock).
3. **Masalah Horizontal Real Estate:** Penggunaan double sidebar pada desktop (Sidebar Utama 256px + Sub-sidebar Sesi Agent 288px = 544px) menyisakan area chat sempit ketika panel kanan aktif.
4. **Audit Fungsional & Eksekusi MCP Tools:**
   - Tool dump (Android UI dump via AccessibilityService) berhasil 100% membaca layar Xiaomi fisik dan memetakan elemen UI secara presisi.
   - Tool screenshot mengalami kegagalan sistemik errorCode=3 (ERROR_TAKE_SCREENSHOT_INTERVAL_TIME) karena tabrakan frekuensi polling live stream dengan background capture.
5. **Bug Visual State & Data Mapping:** Label meta pada riwayat sesi menampilkan " pesan • —" akibat ketidaksesuaian casing properti JSON API (snake_case vs camelCase).

---

## 2. Bukti Visual Audit (Visual Artifacts)

Seluruh tangkapan layar verifikasi disimpan di:
docs/5-testing/screenshots-agent-audit/

| ID | Nama File | Deskripsi & Bukti Temuan |
|---|---|---|
| IMG-01 | 01-deep-desktop-initial.png | Tampilan awal desktop 1440x900, dual sidebar, suggestion cards, status HP. |
| IMG-02 | 02-deep-session-search.png | Interaksi pencarian riwayat sesi & visual bug formatting string. |
| IMG-03 | 03-deep-suggestion-card-filled.png | Eksekusi klik suggestion card langsung men-trigger workflow chat. |
| IMG-04 | 04-deep-accounts-dropdown.png | Dropdown multi-akun media sosial terpilih (Instagram, TikTok, dll). |
| IMG-05 | 05-deep-model-selector-open.png | Modal pilihan model AI (Gemini 3.7 Flash, Claude 3.5 Sonnet, GPT-4o). |
| IMG-06 | 06-deep-model-claude-selected.png | State peralihan model aktif menjadi Claude 3.5 Sonnet. |
| IMG-07 | 07-deep-memory-modal.png | Modal inspeksi memori otonom agent & preferensi user. |
| IMG-08 | 08-deep-hp-panel-open.png | Layar Live HP Android Xiaomi aktif (JPEG streaming dari perangkat fisik). |
| IMG-09 | 09-deep-collision-hp-and-tools.png | BUKTI KRITIS: Drawer MCP Tools menimpa Layar HP (z-index & layout collision). |
| IMG-10 | 10-deep-responsive-1280x800.png | Evaluasi viewport laptop 1280x800: area chat sangat sempit (terhimpit sidebar & drawer). |
| IMG-11 | 11-deep-responsive-1024x768.png | Evaluasi tablet landscape: overflow horizontal dan tombol terpotong. |
| IMG-12 | 12-deep-responsive-768x1024.png | Evaluasi tablet portrait: sidebar menutupi 70% viewport jika tidak di-dock. |
| IMG-13 | 13-deep-responsive-375x812.png | Evaluasi mobile: tombol overflow, layout tertekan, usability buruk. |
| IMG-14 | 14-deep-send-button-enabled.png | Kondisi tombol kirim aktif saat textarea prompt terisi. |
| IMG-15 | 15-deep-bottom-tools-clicked.png | Drawer MCP Tools berhasil dibuka dari tombol bawah (toolbar prompt). |

---

## 3. Temuan Detail, Analisis Masalah & Solusi Arsitektur

### Temuan 1: Redundansi & Duplikasi Elemen Tombol (Duplicate Actions)

#### Masalah Terdeteksi
Dari audit DOM terperinci, ditemukan duplikasi tombol fungsional yang membingungkan pengguna:
1. Tombol Tools muncul di 2 tempat:
   - Top Header Bar (Kanan Atas): x: 1301, y: 66, w: 75, h: 34
   - Prompt Toolbar (Bawah Input): x: 571, y: 820, w: 69, h: 30
2. Tombol Layar HP / Status Perangkat:
   - Muncul sebagai tombol di Top Header Bar (Layar HP (1)).
   - Muncul sebagai card indikator device di Sub-Sidebar Agent.

#### Rekomendasi Solusi & Desain Tata Letak Profesional
- Hilangkan tombol Tools dari toolbar bawah input prompt.
- Fokuskan toolbar bawah khusus untuk konfigurasi pesan: [Target Akun] | [Model AI] | [Lampiran] | [Kirim].
- Konsolidasikan panel sekunder di Top Action Bar sebelah kanan: [Layar HP (1)] | [Tools MCP (11)] | [Memori].

---

### Temuan 2: Konflik Tata Letak & Tabrakan Panel Kanan (Layout Collision)

#### Masalah Terdeteksi
Ketika tombol Layar HP dibuka dan pengguna kemudian menekan tombol Tools:
- Panel Layar HP dibuka dengan lebar 420px (x: 1020, y: 64, w: 420, h: 836).
- Drawer MCP Tools dibuka sebagai fixed overlay dengan lebar 480px (x: 960, y: 0, w: 480, h: 900, z-index: 50).
- Hasil: Drawer MCP Tools menutupi layar HP 100%, tetapi stream HP di bawahnya masih aktif dan merender gambar secara tersembunyi.

#### Rekomendasi Solusi: "Unified Right Workstation Panel" (Tabbed Dock)
Satukan panel kanan menjadi satu kontainer terpadu selebar 440px dengan sistem tab:
- Tab 1: Layar HP Live
- Tab 2: Tools MCP (11)

---

### Temuan 3: Double Sidebar & Penumpukan Horizontal pada Viewport < 1440px

#### Masalah Terdeteksi
- Sidebar Utama GoSosmed: 256px
- Sub-Sidebar Agent: 288px
- Total ruang yang diambil sebelum area chat: 544px
- Pada layar laptop 1280x800, sisa ruang untuk chat hanya 736px. Jika panel HP atau Tools dibuka (440px), sisa ruang chat hanya tinggal 296px.

#### Rekomendasi Solusi Responsif
- Auto-collapse sub-sidebar sesi agent menjadi mode icon-only (64px) saat panel kanan aktif pada resolusi < 1440px.
- Pada tablet portrait (768px) dan mobile (375px), sidebar dan panel kanan harus menjadi full drawer overlay dengan backdrop blur.

---

### Temuan 4: Bug MCP Tool screenshot (Android Interval Time Collision)

#### Detail Investigasi Teknis
- Tool dump berhasil membaca UI tree Xiaomi secara presisi.
- Namun tool screenshot gagal dengan errorCode=3 (AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME).
- Root Cause: Frame rate polling live stream (/screenshot?scale=0.5) bertabrakan dengan background screenshot capture.
- Solusi: Gunakan frame cache memory buffer stream atau rate limit 350ms pada endpoint screenshot.

---

### Temuan 5: Bug Visual Rendering Label Riwayat Sesi (" pesan • —")

#### Masalah Terdeteksi
Pada kartu daftar riwayat sesi di Sub-Sidebar, sub-label menampilkan " pesan • —".
- Root Cause: API mengembalikan snake_case (message_count, updated_at), sedangkan frontend membaca camelCase (messageCount, updatedAt).
- Solusi: Gunakan fallback: item.message_count ?? item.messageCount dan item.updated_at ?? item.updatedAt.

---

## 4. Checklist Rencana Aksi Perbaikan

- [ ] UI/Layout: Hapus tombol Tools dari toolbar bawah input prompt.
- [ ] UI/Layout: Konsolidasikan panel kanan menjadi Unified Right Panel bertab [Layar HP | Tools MCP].
- [ ] UI/Layout: Selesaikan masalah collision z-index antara drawer Tools dan Layar HP.
- [ ] Responsif: Tambahkan auto-collapse sub-sidebar sesi saat panel kanan terbuka di resolusi < 1440px.
- [ ] Responsif: Optimalkan tampilan mobile (375px) dan tablet (768px).
- [ ] Bug Fix: Tambahkan fallback camelCase/snake_case untuk label riwayat sesi.
- [ ] Bug Fix: Implementasikan frame cache buffer untuk mencegah errorCode=3 pada tool screenshot Android.
