# TASK — Stabilkan `annotatedScreenshot` (DEV)

> **Status**: BUG TERVERIFIKASI — SIAP DIKERJAKAN
> **Repo**: `gososmed-mobile-agent` (APK Android)
> **Lingkungan uji**: DEV (`agent-ef08ec4a` — Xiaomi 2312DRA50G, Android 15/API 35)
> **APK terpasang**: v1.2.0 (versionCode 32)
> **Dibuat**: 2026-10-11 · dari sesi audit Ubuntu
> **Rujukan lintas-repo**: `gososmed` → `docs/5-testing/16-AUDIT-DEV-AGENT-MOBILE-JOBQUEUE-2026-10-11.md` §3
>
> **Dokumen ini TIDAK memuat kode perbaikan.** Hanya bukti, lokasi (`file:baris`),
> dan kriteria verifikasi — agar agent APK langsung tahu apa & di mana.

---

## 0. RINGKASAN

`annotatedScreenshot` **gagal intermiten** dan ketika gagal, pesan errornya
**generik tanpa informasi apa pun** sehingga akar penyebab tidak bisa dilacak.

**Akarnya bukan "tool rusak"** — melainkan **3 cacat penanganan error** yang
membuat kegagalan transient tampak seperti kegagalan misterius.

| # | Cacat | Lokasi |
|---|---|---|
| **C1** | `takeScreenshotRawBytes` **membuang `errorCode`** dari Android | `AgentAccessibilityService.kt:774-776` |
| **C2** | Blok `CMD_ANNOTATED_SCREENSHOT` **tidak punya `try/catch`**; `decodeByteArray` juga tidak dibungkus | `AgentCommand.kt:1058-1080` |
| **C3** | 6 titik kegagalan mengembalikan `Triple(null,0,0)` **tanpa membedakan penyebab** | `AgentAccessibilityService.kt:757,783,786,789,792,822` |

**Bukti bahwa ini cacat penanganan — bukan cacat fungsional**:
`CMD_SCREENSHOT` (tool `screenshot`) memanggil API OS **yang sama** dan
**selalu berhasil**, karena ia **punya** `try/catch` + **menyertakan
`e.message`**. Lihat §2.3.

---

## 1. BUKTI PENGUJIAN

### 1.1 Hasil uji lapangan (20 percobaan)

Device `agent-ef08ec4a`, layar 1220×2712:

| # | Konteks | Hasil |
|---|---|---|
| 1 | Chrome Trends (setelah `screenshot`) | **GAGAL** |
| 2 | Chrome Trends (ulang) | **GAGAL** |
| 3 | Chrome Trends (ulang) | **GAGAL** |
| 4 | Launcher (tepat setelah `home`) | **GAGAL** |
| 5 | Launcher (tepat setelah `back`) | **GAGAL** |
| 6–20 | Launcher/Chrome (berbagai kondisi) | BERHASIL |

**Ringkasan: 5 GAGAL / 15 BERHASIL (75%).**

### 1.2 Yang TERBUKTI dari pengujian

1. **Bukan rusak permanen** — layar identik bisa GAGAL lalu BERHASIL tanpa
   perubahan apa pun → **transient**.
2. **Bukan rate-limit interval murni** — 3 panggilan beruntun pernah berhasil
   tanpa jeda.
3. **Bukan `FLAG_SECURE`** — berhasil di Chrome Trends (aplikasi biasa).
4. **Bukan transisi `back`/`home`** — GAGAL setelah `back` (#5) tetapi
   **BERHASIL** setelah `home` (percobaan ke-26 elemen).
5. **`screenshot` (tool kembar) 3/3 selalu berhasil** — termasuk pada saat
   `annotatedScreenshot` gagal.

### 1.3 Log runtime (trace ZeroClaw)

Pengamatan terbaru (14:25) **berhasil** — dan itu konsisten dengan sifat
intermiten. Tidak ada jejak kegagalan detail karena **APK tidak menulis log**
di jalur ini (`Log.*` = **0 kemunculan** di `takeScreenshotRawBytes`).

### 1.4 Koreksi hipotesis yang SALAH (jangan diulang)

Hipotesis awal audit **sudah dibantah** — jangan menyelidiki ulang:

| Hipotesis | Status |
|---|---|
| "`screenshot` dan `annotated` lewat jalur berbeda" | ❌ **SALAH** — keduanya `execCmd` yang sama, API OS yang sama |
| "versi APK tidak sinkron dengan backend" | ❌ **SALAH** — APK v1.2.0, command ada sejak v1.0.1; APK **menjawab** = handler jalan |
| "approval gate menolak" | ✅ **Sudah teratasi** — `annotated_screenshot` kini ada di `auto_approve` profil `gososmed_mcp` |
| "device offline" | ✅ **Bukan bug** — pesannya berbeda (`device ... tidak online`) |

---

## 2. AKAR MASALAH (TERVERIFIKASI DI KODE)

### 2.1 C1 — `errorCode` dibuang

**Berkas**: `app/src/main/java/com/gososmed/agent/AgentAccessibilityService.kt`

Dua fungsi kembar memanggil API OS **yang sama persis**
(`takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, ...)`),
tetapi menangani kegagalan **berbeda**:

| Aspek | `takeScreenshotBase64` (~baris 682) | `takeScreenshotRawBytes` (~baris 756) |
|---|---|---|
| Dipakai tool | `screenshot` | `annotatedScreenshot` |
| Variabel error | **ADA** (`var error: String? = null`) | **TIDAK ADA** |
| `onFailure(errorCode)` | `error = "takeScreenshot failed errorCode=$errorCode"` | **hanya `latch.countDown()`** |
| Pesan gagal | Informatif (memuat errorCode) | Generik `Triple(null, 0, 0)` |
| `Log.*` | — | **TIDAK ADA SAMA SEKALI** |

**Baris kunci**:

| Baris | Isi |
|---|---|
| **682** | `fun takeScreenshotBase64(...)` — **pembanding yang benar** |
| **701-703** | `onFailure` versi benar (menyimpan `errorCode`) |
| **756** | `fun takeScreenshotRawBytes(...)` |
| **760-763** | `var latch / var hw / var colorSpace` — **tidak ada `var error`** |
| **774-776** | `onFailure(errorCode) { latch.countDown() }` ← **errorCode DIBUANG** |
| **786** | `val buffer = hw ?: return Triple(null, 0, 0)` ← tidak bisa bedakan timeout vs failure |

### 2.2 C2 — Tidak ada `try/catch` (ini penyebab pesan generik)

**Berkas**: `app/src/main/java/com/gososmed/agent/AgentCommand.kt`

Perbandingan langsung dua blok yang berdekatan:

**`CMD_SCREENSHOT` (baris ~988-1010) — BENAR**:
```
try {
    val (b64, err) = svc.takeScreenshotBase64(scale, format, quality)
    if (b64 != null) { ... } else {
        resp.put("ok", false).put("error", "screenshot: $err")        <-- err SPESIFIK
    }
} catch (e: Exception) {
    resp.put("ok", false).put("error", "screenshot: ${e.message}")    <-- TANGKAP EXCEPTION
}
```

**`CMD_ANNOTATED_SCREENSHOT` (baris ~1058-1080) — CACAT**:
```
CMD_ANNOTATED_SCREENSHOT -> {
    val scale = ...
    val quality = ...
    val roi = parseRoi(...)
    // TIDAK ADA try/catch
    val (bytes, width, height) = svc.takeScreenshotRawBytes(scale = 1.0f, format="jpeg", quality=85)
    if (bytes == null) {
        resp.put("ok", false).put("error", "gagal mengambil screenshot")   <-- GENERIK
    } else {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)  <-- BISA THROW
        val root = svc.rootInActiveWindow
        val (base64, elements) = AnnotatedScreenshotHelper.annotate(bmp, root, scale, quality, roi)
        root?.recycle()
        bmp.recycle()
        ...
    }
}
```

**Konsekuensi berantai**:
1. Jika `takeScreenshotRawBytes` **melempar** exception (mis.
   `OutOfMemoryError`, `IllegalStateException` dari `wrapHardwareBuffer`),
   **tidak ada yang menangkapnya**.
2. `BitmapFactory.decodeByteArray` (baris ~1067) **juga tidak dibungkus** —
   berisiko OOM (lihat §2.4).
3. `root?.recycle()` dan `bmp.recycle()` **tidak ada di `finally`** — jika
   `annotate()` melempar, bitmap **tidak di-recycle** → kebocoran memori →
   memperburuk kegagalan berikutnya.

**Perhatikan juga**: blok lain di berkas yang sama (`CMD_DUMP_WINDOWS` baris
~985, `CMD_SCREENSHOT` baris ~1007) **sudah** memakai `try/catch`. Jadi ini
**inkonsistensi**, bukan keterbatasan teknis.

### 2.3 Mengapa `screenshot` selalu berhasil tapi `annotated` tidak

| | `screenshot` | `annotatedScreenshot` |
|---|---|---|
| `scale` | `0.5` (dikirim backend, `mcphub.go:898`) | **dipaksa `1.0f`** (`AgentCommand.kt:1063`) |
| Alokasi memori | kecil | **full-res 12,6 MB × beberapa** |
| `try/catch` | ✅ ADA | ❌ TIDAK ADA |
| Pesan error | memuat `e.message` / `err` | generik |

**Dua faktor pembeda**: (a) beban memori jauh lebih besar, (b) tidak ada
penangkap exception. Kombinasi ini menjelaskan mengapa yang satu tangguh dan
yang lain rapuh.

### 2.4 Analisis memori (kandidat penyebab terkuat)

`annotatedScreenshot` memaksa `scale = 1.0f` (`AgentCommand.kt:1063`), padahal
backend mengirim `0.35` (`mcphub.go:2159`) dan default lain `0.6`.

Perhitungan pada layar 1220×2712:

```
Satu bitmap ARGB_8888        : 12,6 MB
Alur annotatedScreenshot:
  1. bytes JPEG full-res     : ~1,3-3,8 MB
  2. BitmapFactory.decode... : 12,6 MB   (AgentCommand.kt ~1067)
  3. AnnotatedScreenshotHelper: 12,6 MB  (copy mutable)
  4. ROI/scale bitmap        : ~12,6 MB
  PUNCAK bersamaan           : ~38-50 MB
```

Di Android, bitmap besar dialokasikan di **heap native**, dan OEM seperti
HyperOS/MIUI membatasi alokasi besar pada **background service**. Jika OOM:
`catch` tidak ada → exception naik tanpa jejak → pesan generik.

> **PENTING**: ini **hipotesis kuat**, **bukan** fakta terverifikasi. Yang
> **sudah pasti** adalah: `errorCode` dibuang (C1) dan `try/catch` tidak ada
> (C2). Perbaiki keduanya lebih dulu, lalu **ukur** — jangan mengasumsikan OOM
> sebagai penyebab final sebelum ada data.

### 2.5 Enam titik kegagalan yang tak terbedakan (C3)

Semua di `AgentAccessibilityService.kt`, semua mengembalikan `Triple(null,0,0)`
tanpa membedakan sebab:

| # | Baris | Kondisi |
|---|---|---|
| 1 | **757** | `SDK_INT < 30` |
| 2 | **783** | `InterruptedException` saat `latch.await` |
| 3 | **786** | `hw == null` — **timeout ATAU `onFailure`** (tak bisa dibedakan) |
| 4 | **789** | `buffer.close()` dinilai sebagai kondisi (`close()` mengembalikan `Unit`) |
| 5 | **792** | `soft == null` (copy gagal / OOM) |
| 6 | **822** | `compress(...)` mengembalikan `false` |

Keenamnya menghasilkan pesan **identik** di `AgentCommand.kt:1065`.

---

## 3. YANG HARUS DICAPAI

### Sasaran 1 — `errorCode` harus sampai ke pemanggil
Kegagalan `takeScreenshotRawBytes` harus menyertakan **`errorCode` asli**
Android, sama seperti `takeScreenshotBase64` (`AgentAccessibilityService.kt:701-703`).

### Sasaran 2 — Setiap titik kegagalan harus punya identitas
Enam titik di §2.5 harus dapat dibedakan (mis. melalui pesan/penanda berbeda),
sehingga kegagalan berikutnya bisa langsung dipastikan penyebabnya.

### Sasaran 3 — `try/catch` + `finally` di blok command
Blok `CMD_ANNOTATED_SCREENSHOT` (`AgentCommand.kt:1058-1080`) harus:
- menangkap exception dan melaporkan `e.message` (mengikuti pola
  `CMD_SCREENSHOT` baris ~1007);
- me-recycle bitmap di `finally` agar tidak bocor saat `annotate()` gagal.

### Sasaran 4 — Logging
Jalur screenshot harus menulis log (minimal saat gagal) agar bisa didiagnosis
dari `adb logcat`.

### Sasaran 5 — (Opsional, setelah data ada) kurangi tekanan memori
Pertimbangkan apakah `scale = 1.0f` yang dipaksa di `AgentCommand.kt:1063`
masih diperlukan. Backend mengirim `0.35`, `agentclient.go:1163` default `0.6`,
APK memaksa `1.0f` — **tiga nilai berbeda**. Jika capture boleh di-scale,
koordinat bounds perlu dikompensasi agar tetap 1:1 dengan layar fisik.

> **JANGAN** mengerjakan Sasaran 5 sebelum Sasaran 1–4. Tanpa data
> `errorCode`, perubahan scale hanya menebak.

### Sasaran 6 — (Opsional) pertimbangkan retry
API `takeScreenshot` Android diketahui dapat gagal sementara (kuota/interval).
Retry terbatas mungkin tepat — tetapi **tentukan setelah melihat `errorCode`**,
bukan sebelumnya.

---

## 4. PERTANYAAN TERBUKA YANG PERLU DIJAWAB

1. **Apakah `scale = 1.0f` disengaja?** Komentar di `AgentCommand.kt:1062`
   menyebut *"agar koordinat bounds 1:1 dengan layar fisik"*. Namun backend
   mengirim `0.35` dan `AnnotatedScreenshotHelper` punya parameter `scale`
   sendiri. Perlu diputuskan mana sumber kebenaran.

2. **Apakah `buffer.close() ?: return Triple(null,0,0)` (baris 789) benar?**
   `close()` pada `HardwareBuffer` mengembalikan `Unit` — menilainya sebagai
   kondisi perlu dikonfirmasi bukan bug.

3. **Bisakah `AnnotatedScreenshotHelper.annotate()` melempar?**
   Commit `2b9e301` menambahkan `try/finally` berlapis untuk guard bitmap.
   Perlu dipastikan exception di helper tidak berubah jadi pesan generik.

4. **Apakah `svc.rootInActiveWindow` bisa `null`?** Kode memanggil
   `root?.recycle()` — jadi penulisnya sadar bisa null. Perlu dipastikan
   `annotate` menangani `root == null`.

---

## 5. KRITERIA VERIFIKASI (WAJIB)

| Uji | Kriteria lulus |
|---|---|
| Panggil `annotatedScreenshot` 20× (lintas konteks: launcher, Chrome, YouTube, aplikasi sosial) | **≥95% berhasil** |
| Saat gagal (jika masih ada) | Pesan **memuat `errorCode`** dan identitas titik kegagalan — **bukan** `"gagal mengambil screenshot"` |
| `adb logcat` saat gagal | Ada entri log dari jalur screenshot |
| Bandingkan dengan tool `screenshot` | Keduanya konsisten; jika `screenshot` berhasil, `annotatedScreenshot` juga harus berhasil pada kondisi sama |
| Uji tepat setelah `home`/`back` (kondisi pemicu kegagalan lama) | Berhasil |
| Uji beruntun 5× tanpa jeda | Berhasil (tidak ada rate-limit tersembunyi) |
| Cek kebocoran memori | Setelah 20 panggilan, tidak ada pertumbuhan memori tak wajar (`adb shell dumpsys meminfo`) |

---

## 6. CARA REPRODUKSI

1. Pastikan device `agent-ef08ec4a` online (APK v1.2.0+, a11y aktif).
2. Dari backend DEV, panggil tool `annotated_screenshot` (via MCP atau chat
   `/agent`).
3. Untuk memicu kondisi kegagalan lama, lakukan **tepat setelah** `home` atau
   `back`, atau di layar dengan banyak elemen (Chrome Trends, YouTube).
4. Ulangi 20× dan catat keberhasilan/kegagalan.
5. Ketika gagal, periksa `adb logcat` — **saat ini tidak akan ada entri**;
   setelah perbaikan, harus ada.

---

## 7. BATASAN (JUJUR)

1. **Penyebab transient final belum diketahui.** Audit membuktikan *lokasi*
   kegagalan (C1, C2, C3) dan *sifatnya* (intermiten 75%), **bukan** akar
   fisiologisnya. OOM (§2.4) adalah **hipotesis kuat**, belum terbukti.

2. **Angka 75% bersifat indikatif** — dari 20 percobaan **lintas konteks**,
   bukan 20× beruntun pada satu konteks.

3. **Pengamatan terbaru (14:25) berhasil** — konsisten dengan sifat intermiten,
   tetapi berarti bug **mungkin sulit direproduksi** dalam sesi singkat.

4. **Belum ada perubahan kode apa pun** dari sisi dokumen ini.

5. **Analisis memori (§2.4) adalah estimasi aritmetika**, bukan pengukuran
   `dumpsys meminfo` nyata.

6. **Tidak bisa diverifikasi dari Ubuntu**: repo ini adalah repo APK yang
   memerlukan Android Studio/Gradle. Build & uji harus di workstation Windows.

---

## 8. PETA LOKASI (AGENT-FRIENDLY)

```
app/src/main/java/com/gososmed/agent/
├── AgentAccessibilityService.kt
│   ├── 682        fun takeScreenshotBase64(...)          <- PEMBANDING BENAR
│   ├── 701-703    onFailure(errorCode) { error = ... }   <- pola yang harus ditiru
│   ├── 756        fun takeScreenshotRawBytes(...)        <- C1: errorCode dibuang
│   ├── 760-763    deklarasi var (tidak ada `var error`)
│   ├── 774-776    onFailure(errorCode) { countDown() }   <- C1 inti
│   ├── 757,783,786,789,792,822                           <- C3: 6 titik null
│   └── 668        screenshotExecutor (dipakai bersama)
│
└── AgentCommand.kt
    ├── 76         const CMD_ANNOTATED_SCREENSHOT
    ├── 114        ASYNC_BACKGROUND_COMMANDS (terdaftar)
    ├── 988-1010   CMD_SCREENSHOT            <- BENAR (try/catch + e.message)
    ├── 1058       CMD_ANNOTATED_SCREENSHOT  <- C2: tidak ada try/catch
    ├── 1063       takeScreenshotRawBytes(scale = 1.0f, ...)  <- scale dipaksa
    ├── 1065       "gagal mengambil screenshot"  <- pesan generik
    ├── 1067       BitmapFactory.decodeByteArray  <- tidak dibungkus try
    └── 1069-1070  root?.recycle() / bmp.recycle() <- tidak di finally
```

Referensi terkait di repo `gososmed` (untuk konteks lintas-repo):
- `internal/agenthub/agentclient.go:1173-1190` — `AnnotatedScreenshotWithRoi`
- `internal/mcphub/mcphub.go:2152-2206` — handler + filter bounds
- `internal/mcphub/mcphub.go:2159` — default `scale = 0.35` dari backend

---

## 9. DEFINISI SELESAI

Perbaikan dinyatakan selesai HANYA jika:

1. `errorCode` muncul di pesan kegagalan.
2. Setiap titik kegagalan (§2.5) punya identitas berbeda.
3. `try/catch` + `finally` terpasang di `CMD_ANNOTATED_SCREENSHOT`.
4. Logging aktif di jalur screenshot.
5. Uji 20× memenuhi ≥95% berhasil.
6. Jika masih ada kegagalan, pesannya **diagnostik** (memuat kode/penyebab).

**Item 1–4 wajib lebih dulu.** Item 5–6 bergantung pada kemampuan mereproduksi
kegagalan — jika bug tidak muncul dalam 20 percobaan, tetap laporkan hasilnya
dengan jujur, jangan mengklaim selesai tanpa bukti.
