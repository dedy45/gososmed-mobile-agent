# F0 — Bukti Validasi Library ADB (Gate F0)

**Tanggal:** 2026-09-13
**Fase:** F0 dari [docs/2-architecture/23-PLAN-LOCAL-ADB-TRANSPORT-DAN-KONTRAK-AGENT.md](../../Go-sosmed/docs/2-architecture/23-PLAN-LOCAL-ADB-TRANSPORT-DAN-KONTRAK-AGENT.md)
**Status:** ✅ GATE LULUS — keputusan library dikunci

---

## 1. Keputusan

**DIPILIH: `com.github.MuntashirAkon:libadb-android:3.1.1`** (via JitPack, lisensi Apache-2.0 pilihan kita).

**DITOLAK: `rhythmcache/adb-kt`** — lisensi benar (Apache-2.0) tetapi kematangan nol.

---

## 2. Bukti terverifikasi (bukan asumsi)

### 2.1 libadb-android — DIPILIH

| Kriteria | Hasil | Sumber bukti |
|---|---|---|
| **API pairing ada** | ✅ `AdbConnectionManager.getInstance().pair(host, port, pairingCode)` | README resmi repo |
| **API connect + auto-discovery port** | ✅ `connectTls(Context, timeout)`, `autoConnect(Context, timeout)` — *"Discover host address and port number automatically"* | README resmi repo |
| **Shell streaming** | ✅ `openStream("shell:")` → `AdbStream` dengan `openInputStream()`/`openOutputStream()` | README resmi repo |
| **Lisensi** | ✅ `SPDX-License-Identifier: GPL-3.0-or-later or Apache-2.0` — **kita pilih Apache-2.0** | `COPYING` + header tiap berkas + `build.gradle` |
| **Repositori Maven Central** | ❌ TIDAK ADA (`numFound: 0` untuk kueri `libadb`) | `search.maven.org/solrsearch` |
| **JitPack** | ✅ Tersedia, tag `1.0.0` … `3.1.1` semua berstatus `ok` | `jitpack.io/api/builds/com.github.MuntashirAkon/libadb-android` |
| **Tag terakhir** | `3.1.1` → commit `c849886ebc6d48e7b46d967e78a6bb65c90c3b74` | GitHub tags API |
| **Kematangan** | ✅ 407 bintang, 92 fork, dibuat 2021-12-04, dipakai App Manager (produksi nyata) | GitHub repos API |
| **Aktivitas** | Push terakhir 2026-06-03 | GitHub repos API |

**Dependensi tambahan yang wajib (dari README):**
```gradle
// TLS 1.3 + pairing. Wajib untuk Android 11+ wireless debugging.
implementation 'org.conscrypt:conscrypt-android:2.5.3'
```
Untuk pembuatan sertifikat X509, dua pilihan:
- **BouncyCastle** — bersih, tanpa hidden API. **Ini yang dipilih.**
- `com.github.MuntashirAkon:sun-security-android:1.1` + `org.lsposed.hiddenapibypass:hiddenapibypass:6.1` — memakai hidden API, dihindari.

### 2.2 Peringatan yang harus dicatat jujur

| Peringatan | Kutipan sumber | Dampak |
|---|---|---|
| Belum diaudit keamanan | *"This library has never gone through a security audit. Please, proceed with caution if security is..."* | Risiko diterima; library berjalan lokal di HP pengguna, bukan di server |
| Dependensi LGPL | *"this library has an LGPL dependency which may go against the policy of some organizations such as ASF"* | Dependensi SPAKE2 ber-LGPL. Untuk distribusi APK kita: aman, cukup atribusi. Bukan blocker |
| Hanya Android untuk pairing | *"Spake2-Java only provides stable releases for Android"* | Relevan hanya bila kelak butuh versi JVM; saat ini tidak |

### 2.3 adb-kt — DITOLAK

| Kriteria | Hasil |
|---|---|
| Lisensi | ✅ Apache-2.0 (teks lengkap terverifikasi di `LICENSE`) |
| Kematangan | ❌ **0 bintang, 0 fork, 0 watcher** |
| Usia | ❌ Dibuat 2026-07-22, push terakhir 2026-08-21 — kurang dari 2 bulan |
| Validasi komunitas | ❌ Tidak ada sama sekali |

**Alasan penolakan:** untuk aplikasi produksi, risiko rantai pasok terlalu tinggi. Bila pustaka ini punya bug pada implementasi SPAKE2 (kriptografi pairing) atau ditinggalkan penulisnya, kita tidak punya jalan keluar. `libadb-android` punya rekam jejak 5 tahun dan dipakai App Manager.

---

## 3. Konsekuensi untuk implementasi (F3)

Yang berubah dari asumsi awal rencana:

| Asumsi awal | Kenyataan dari F0 |
|---|---|
| Perlu implementasi mDNS sendiri (`NsdManager`) untuk cari port | **Tidak perlu** — `connectTls(Context, ...)` sudah menemukan host dan port otomatis |
| Perlu implementasi SPAKE2 sendiri | **Tidak perlu** — `pair(host, port, code)` sudah tersedia |
| Library di Maven Central | **JitPack** — `settings.gradle.kts` wajib ditambah `maven { url "https://jitpack.io" }` |
| Bahasa library Kotlin | **Java** — tetap bisa dipakai dari Kotlin tanpa masalah |
| `RepositoriesMode.FAIL_ON_PROJECT_REPOS` | Repo JitPack **harus** ditambahkan di `settings.gradle.kts`, bukan di modul `app` |

---

## 4. Yang menjadi lingkup F3 (bukan F0)

F0 hanya memvalidasi keberadaan dan kelayakan. Yang belum diuji dan menjadi tugas F3:

- Apakah pairing benar-benar berhasil di HP target (Xiaomi/HyperOS) — **butuh uji perangkat nyata**.
- Apakah `connectTls` menemukan port saat mDNS dibatasi OEM.
- Apakah sesi bertahan untuk perintah berulang (`shell:` stream jangka panjang).
- Perilaku setelah Wireless Debugging mati (reboot) — pemulihan dan pelaporan status.

**F0 tidak boleh diklaim membuktikan hal-hal di atas.** F0 hanya membuktikan: library ada, berlisensi benar, API yang kita butuhkan tersedia, dan tersedia untuk diunduh.

---

## 5. Cara mengulang validasi ini

```powershell
# Lisensi (wajib Apache-2.0, bukan GPL saja)
Invoke-WebRequest https://raw.githubusercontent.com/MuntashirAkon/libadb-android/master/COPYING

# Ketersediaan artefak di JitPack (harus ada versi yang berstatus "ok")
Invoke-WebRequest https://jitpack.io/api/builds/com.github.MuntashirAkon/libadb-android

# Kematangan (bintang/usia/pemeliharaan)
Invoke-WebRequest https://api.github.com/repos/MuntashirAkon/libadb-android

# API pairing/connect/shell
Invoke-WebRequest https://raw.githubusercontent.com/MuntashirAkon/libadb-android/master/README.md
```
