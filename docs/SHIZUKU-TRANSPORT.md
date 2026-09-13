# Transport Tier 1 — Shizuku (uid 2000 / shell)

> ## ⚠️ DOKUMEN HISTORIS — SUDAH TIDAK BERLAKU
>
> Dokumen ini menjelaskan desain **v0.8.0**. Mulai **v0.9.0**, Shizuku
> **dihapus total** dan digantikan transport ADB lokal milik sendiri.
>
> **Baca sebagai gantinya:**
> - [TRANSPORT-ADB-LOKAL.md](TRANSPORT-ADB-LOKAL.md) — cara kerja transport baru
> - [AGENT-COMMAND-CONTRACT.md](AGENT-COMMAND-CONTRACT.md) — kontrak terbaru
> - [F0-LIBRARY-VALIDATION.md](F0-LIBRARY-VALIDATION.md) — kenapa Shizuku ditinggalkan
>
> Disimpan hanya untuk konteks sejarah: alasan hak uid 2000 dibutuhkan tetap
> sah dan dijelaskan di dokumen pengganti.

Dokumen desain v0.8.0. Menjelaskan **kenapa** jalur ini dipilih, **apa** yang
berubah di APK, dan **apa** yang harus dilakukan pemilik HP.

---

## 1. Akar masalah (FAKTA produksi, bukan dugaan)

Bukti 2026-09-11 dari 5 platform:

```
platform_error: mobileharvest: com.facebook.katana tidak berada di foreground
(yang tampil: com.miui.home)
```

Penyebabnya bukan selector, bukan backend, bukan UI — tetapi **kanal eksekusi**:

1. `AccessibilityService` tidak punya window terlihat → setiap `startApp`
   dihitung **Background Activity Launch (BAL)** dan ditelan sistem sejak
   Android 10, **tanpa exception**. APK melapor sukses palsu.
2. Accessibility **tidak punya** izin `INJECT_EVENTS` → `dispatchGesture`
   bisa gagal senyap (app dengan `FLAG_SECURE`, overlay OEM, throttling).
3. `am force-stop` **mustahil** dari app biasa. `killBackgroundProcesses`
   bukan force-stop → kondisi awal layar tidak pernah deterministik.

Kesimpulan riset repo produksi (scrcpy 148k★, appium-uiautomator2-server,
openatx/atx-agent, DeviceFarmer/stf): **yang membuat automasi Android stabil
bukan framework-nya, tetapi menjalankan perintah sebagai uid `shell` (2000)**.
Semua proyek itu memakai uid shell; tidak satu pun mengandalkan
AccessibilityService sebagai kanal utama. Proyek berbasis accessibility
(droidrun/mobilerun-portal 368★, Auto.js/AutoX) justru kelas yang rapuh.

Shizuku memberi hak uid 2000 itu **tanpa root dan tanpa PC**, sesudah sekali
pairing wireless debugging.

---

## 2. Arsitektur dua tingkat (degradasi mulus, bukan crash)

```
           server (Go)  ->  WebSocket  ->  APK
                                            |
                          +-----------------+------------------+
                          |                                    |
                  TIER 1: shell (Shizuku)            TIER 2: accessibility
                  uid 2000, setara adb               fallback lama
                  am start / am force-stop           LauncherApps + overlay 1x1
                  input tap / input keyevent         dispatchGesture + wakelock
                  DETERMINISTIK                      BEST-EFFORT (tunduk BAL/OEM)
```

Aturan implementasi yang dipegang ketat:

- Tier 1 **selalu dicoba lebih dulu**; gagal → jatuh ke Tier 2, bukan error.
- Semua akses kelas Shizuku dibungkus `try/catch Throwable` (termasuk
  `NoClassDefFoundError`) → APK tetap berfungsi walau Shizuku tidak dipasang.
- Sukses **tidak pernah** disimpulkan dari exit code saja. `am start` bisa
  exit 0 padahal activity ditolak, jadi bukti tetap diambil dari foreground
  nyata (`awaitForeground`, 10 s, poll 250 ms).

---

## 3. Berkas yang berubah

| Berkas | Perubahan |
|---|---|
| `ShizukuShell.kt` (**baru**) | `ready()`, `binderAlive()`, `hasPermission()`, `requestPermission()`, `exec()`, `privilegeUid()`, `managerInstalled()`; daftar putih biner |
| `AgentAccessibilityService.kt` | `startAppShell()` baru; `startAppVerified` coba shell dulu; `killAppMode` → `force_stop` nyata; `tap` → `input tap`; `wakeScreen` → `keyevent 224`; `capabilitiesJson` + 10 field; minta izin Shizuku sekali saat service connect |
| `AgentCommand.kt` | command `shell` + `shizukuRequest`; field `transport` pada `startApp`/`killApp` |
| `AndroidManifest.xml` | izin `moe.shizuku.manager.permission.API_V23`; `rikka.shizuku.ShizukuProvider` |
| `app/build.gradle.kts` | `dev.rikka.shizuku:api:13.1.5`, `dev.rikka.shizuku:provider:13.1.5`; versi 0.8.0 / versionCode 15 |

---

## 4. Catatan API yang jujur (risiko + mitigasi)

**FAKTA:** sejak Shizuku-API 13, `Shizuku.newProcess` dijadikan **privat**
(RikkaApps/Shizuku-API issue #211). Upstream menyatakan API itu tidak dijamin
untuk produksi.

**Pilihan yang diambil:** refleksi ke `newProcess`, dengan nilai balik di-cast
ke `java.lang.Process` (kelas `ShizukuRemoteProcess` memang turunannya),
sehingga kode kita **tidak** bergantung pada tipe internal Shizuku.

**Alasan (kritis, bukan malas):**
- Alternatifnya `UserService` + AIDL: lebih future-proof tetapi menambah
  file AIDL, service terpisah, binding lifecycle — permukaan kompilasi jauh
  lebih besar. Repo ini **tidak punya `gradlew`**, jadi tidak ada build lokal;
  kesalahan kompilasi hanya terlihat di CI. Menambah permukaan besar =
  risiko gagal rilis berulang (pola error-loop yang sudah terjadi).
- Kegagalan refleksi **tidak fatal**: mengembalikan `null` → `reason =
  shizuku_api_unavailable` → agent turun ke Tier 2 dan tetap jalan.

**Rencana lanjutan (bila diperlukan):** migrasi ke `UserService` AIDL setelah
ada satu siklus build CI yang hijau dan bukti harvest sukses. Tier 2 tetap
menjadi jaring pengaman, jadi migrasi bisa dilakukan tanpa downtime.

**Catatan build:** Shizuku-API 13.1.0 menyebut desugaring diperlukan bila
`minSdk = 23`. Proyek ini `minSdk = 26` sehingga diharapkan tidak perlu. Bila
CI gagal dengan galat desugaring, tambahkan:

```kotlin
compileOptions { isCoreLibraryDesugaringEnabled = true }
dependencies { coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4") }
```

---

## 5. Langkah pemilik HP (sekali pasang, ulang start setelah reboot)

1. Pasang APK v0.8.0 (rilis CI dari tag `v0.8.0`).
2. Pasang **Shizuku** dari Play Store / GitHub RikkaApps.
3. Aktifkan **Opsi pengembang** → **Wireless debugging** (Android 11+).
4. Di Shizuku: **Pairing** (masukkan kode dari notifikasi wireless debugging)
   → **Start**.
5. Buka GoSosmed Agent. Dialog izin Shizuku muncul → **Setujui**.
6. Verifikasi di dasbor: `transport_tier` harus `shell_shizuku`.

> **Penting:** setelah HP reboot, Shizuku **mati** dan harus di-start ulang
> (pairing wireless debugging diulang). Selama itu agent otomatis turun ke
> Tier 2 — masih jalan, tetapi `force_stop` tidak tersedia. Frontend harus
> menampilkan kondisi ini secara jujur, jangan diam-diam.

Bila tersedia root (Magisk), modul **Sui** memberi hak setara tanpa pairing
ulang (`shizuku_uid = 0`). APK mendukungnya otomatis — tanpa perubahan kode.

---

## 6. Batas yang tetap ada (jangan dijanjikan ke user)

- Keyguard PIN/pola **tidak** bisa dibuka; `wake` hanya menyalakan layar.
- uid 2000 **tidak** bisa membaca `/data/user/0/<package>` → tidak ada
  pembacaan sesi/cookie dari penyimpanan privat app.
- Daftar putih biner (`am input monkey pm dumpsys wm settings cmd`) adalah
  batas keamanan yang disengaja; menambah biner = ubah APK secara sadar.
- Pada MIUI/HyperOS, tanpa Shizuku, izin "pop-up saat berjalan di latar
  belakang" + Autostart tetap **wajib** agar Tier 2 berfungsi.
