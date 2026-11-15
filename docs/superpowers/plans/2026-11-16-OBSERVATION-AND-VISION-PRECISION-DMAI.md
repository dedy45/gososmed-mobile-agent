# Plan: Perbaikan Observasi Hemat Token & Presisi Vision (D.M.A.I.) — v1.2.21-dev.1

> **Status:** APPROVED FOR IMPLEMENTATION (DEV RELEASE)  
> **Target Version:** `1.2.21` (dev tag: `v1.2.21-dev.1`, versionCode: 61)  
> **Tanggal:** 2026-11-16  

---

## 1. D (Define) — Pendefinisian Masalah & Sasaran Proyek

Berdasarkan audit menyeluruh terhadap implementasi observasi mobile agent pada APK Kotlin (`gososmed-mobile-agent`):

### Masalah yang Didefinisikan:
1. **Kotak Anotasi Vision Melenceng (Coordinate Scaling Heuristic Bug)**:
   - Pada `AnnotatedScreenshotHelper.kt`, penyesuaian rasio `coordScale` menggunakan `screenRight = elements.maxOfOrNull { it.bounds.right }`.
   - Jika layar sedang menampilkan modal dialog atau popup sempit di tengah layar (misal dialog izin atau CAPTCHA), elemen paling kanan hanya mencapai misal 800px dari lebar fisik 1220px. Akibatnya rasio salah dihitung (`bitmap.width / 800`), dan semua kotak penanda Set-of-Marks digambar di koordinat yang melenceng dari objek fisik aslinya.
2. **Missing Commands di Sisi APK (`checkSecurityChallenge` & `nodeInspector`)**:
   - Di MCP Hub dan panduan agen, terdapat tool `check_security_challenge` (fail-closed guard CAPTCHA/2FA) dan `node_inspector` (inspeksi atribut node tanpa dump penuh).
   - Di APK Kotlin (`AgentCommand.kt`), kedua command ini belum ada di `execute()`, sehingga pemanggilan langsung menghasilkan `unknown command`.
3. **Potensi Crash Recycled Node pada `waitForNode`**:
   - Pada `AgentAccessibilityService.kt:1561-1568`, `root.recycle()` dan `wRoot.recycle()` dipanggil di dalam loop polling ketat 80ms saat rekursi sedang berlangsung, berisiko memicu `IllegalStateException: Cannot perform this action on a recycled node` pada Android 12+.

### Sasaran:
- Menghilangkan bug skala koordinat dengan mengikat resolusi ke ukuran layar riil `DisplayMetrics`/`WindowManager` atau dimensi eksplisit screenshot asli.
- Mengimplementasikan `checkSecurityChallenge` (pemeriksaan kata kunci checkpoint/CAPTCHA/2FA secara fail-closed dan cepat di memori) dan `nodeInspector` (pengambilan atribut lengkap sebuah elemen spesifik) di `AgentCommand.kt` dan `AgentAccessibilityService.kt`.
- Memperbaiki penanganan `recycle()` agar aman dari crash daur ulang objek node.
- Menambahkan unit test regresi di JVM.
- Menaikkan versi ke `1.2.21` (versionCode 61) dan mempublikasikan tag `v1.2.21-dev.1` sesuai AGENTS.md (Kanal Dev).

---

## 2. M (Measure) — Pengukuran Kinerja & Baseline Data

- **Token Consumption Baseline**:
  - Raw XML dump: >50.000 token, latensi 1.5 - 3 detik per putaran.
  - Raw screenshot: ~1.500 token, akurasi penentuan koordinat oleh Vision LLM < 40% (kerap salah klik pada tombol berdekatan).
  - Target `annotatedScreenshot`: ~800 - 1.200 token dengan akurasi 100% via pemilihan nomor mark ID.
- **Error Frequency**:
  - `checkSecurityChallenge`: 100% unhandled (unknown command).
  - `nodeInspector`: 100% unhandled (unknown command).
  - Skala Mark pada sub-window: melenceng proporsional terhadap `(display_width - modal_width) / display_width`.

---

## 3. A (Analyze) — Analisis Akar Masalah (Root Causes)

1. **Root Cause Koordinat**:
   - `AnnotatedScreenshotHelper.annotateRoots` tidak menerima dimensi fisik layar asli, melainkan hanya `bitmap` yang mungkin sudah di-crop atau ditangkap dengan ukuran tertentu, lalu menebak dimensi layar dari koordinat terjauh elemen yang ditemukan.
   - Solusi: Sediakan dimensi fisik layar referensi (`displayWidth`, `displayHeight`) secara eksplisit atau turunkan secara deterministik dari dimensi bitmap asal sebelum downscaling.
2. **Root Cause Missing Commands**:
   - Arsitektur evolusi bertahap: tools ditambahkan di spesifikasi MCP Go/Node, namun belum dipetakan ke switch-case `AgentCommand.kt` di APK.
3. **Root Cause Recycled Node**:
   - Polling 80ms AccessibilityNodeInfo melakukan `.recycle()` manual yang sudah deprecated dan berbahaya pada framework Android modern yang mengelola lifecycle node secara otomatis.

---

## 4. I (Improve) — Rencana Perbaikan & Pengujian

1. **`AnnotatedScreenshotHelper.kt`**:
   - Terima ukuran layar fisik referensi atau gunakan `rawBitmapWidth` / `rawBitmapHeight` langsung.
   - Perbaiki kalkulasi rasio agar Set-of-Marks selalu presisi 1:1 terhadap layar fisik.
2. **`AgentAccessibilityService.kt`**:
   - Implementasikan fungsi `checkSecurityChallenge()`:
     - Mendeteksi teks / indikasi CAPTCHA, Puzzle Challenge, Two-Factor Authentication, Verifikasi Akun, Bot Check, Suspicious Activity pada seluruh interactive roots.
     - Mengembalikan status `detected: Boolean`, `challenge_type: String?`, dan `human_intervention_required: Boolean`.
   - Implementasikan fungsi `inspectNode(text, desc, resId)`:
     - Mengembalikan JSON detail: `class`, `text`, `content_desc`, `resource_id`, `bounds`, `center`, `clickable`, `editable`, `focused`, `scrollable`, `password`.
   - Hapus pemanggilan `.recycle()` manual yang rentan exception saat polling `waitForNode()`.
3. **`AgentCommand.kt`**:
   - Daftarkan konstanta `CMD_CHECK_SECURITY_CHALLENGE = "checkSecurityChallenge"` dan `CMD_NODE_INSPECTOR = "nodeInspector"`.
   - Tangani di `execute()` dan kembalikan response JSON terstruktur yang sesuai wire contract.
4. **Unit Tests**:
   - Buat unit test regresi untuk `checkSecurityChallenge`, `nodeInspector`, dan verifikasi kalkulasi bounds di `AnnotatedScreenshotHelper`.
5. **Rilis**:
   - Update `app/build.gradle.kts`: `versionName = "1.2.21"`, `versionCode = 61`.
   - Update `CHANGELOG.md` dengan entri dev rilis `[1.2.21-dev.1]`.
