# Transport ADB Lokal (v0.9.0) — Cara Kerja, Syarat, dan Batas

> Menggantikan `SHIZUKU-TRANSPORT.md` (v0.8.0). Shizuku **dihapus total**
> mulai v0.9.0.
> Bukti pemilihan library: [F0-LIBRARY-VALIDATION.md](F0-LIBRARY-VALIDATION.md).

---

## 1. Masalah yang diselesaikan

Sejak Android 10, aplikasi biasa tidak bisa:
- memulai activity dari latar belakang (**Background Activity Launch** diblokir),
- memakai `INJECT_EVENTS` untuk tap,
- melakukan `am force-stop` sungguhan.

Akibatnya `startApp` bisa "berhasil" tanpa exception padahal layar tetap di
launcher — inilah sumber status "sukses palsu" pada insiden 2026-09-11.

Satu-satunya hak yang membebaskan ketiganya di perangkat **non-root** adalah
**uid 2000 (`shell`)** — hak yang sama dengan `adb shell`.

## 2. Kenapa butuh ADB (dan kenapa ini tidak bisa dihilangkan)

| Fakta | Konsekuensi |
|---|---|
| uid 2000 hanya diberikan oleh `adbd` atau root | Harus ada jalur ADB |
| `adbd` butuh autentikasi ADB | Wireless Debugging **atau** USB debugging |
| Wireless Debugging otomatis mati saat HP reboot | **Pairing harus diulang setiap reboot** |
| Tidak ada API publik untuk naik ke uid 2000 | Tidak ada jalan pintas |

**Ini batas platform, bukan kekurangan implementasi kita.** Yang bisa
dihilangkan hanyalah **aplikasi pihak ketiga** (Shizuku), bukan langkah ADB.

### 2.1 Wireless Debugging = koneksi LOKAL

Pairing terjadi antara HP dan **dirinya sendiri** lewat `127.0.0.1`. Tidak
menyentuh server `api.bamsbung.id`. Server hanya menerima WebSocket **keluar**
dari HP. Jadi tidak ada port masuk yang dibuka ke internet.

## 3. Arsitektur dua tingkat

| | **Tier 2 — Accessibility** (WAJIB) | **Tier 1 — Shell ADB** (OPSIONAL) |
|---|---|---|
| Syarat | Layanan aksesibilitas + izin overlay | Kedua syarat Tier 2 **plus** ADB ter-pair |
| `transport` | `accessibility` | `shell_adb` |
| Buka aplikasi | Bisa, tunduk blokade BAL | `am start`, tidak kena BAL |
| Tap | `dispatchGesture` (bisa senyap gagal) | `input tap` (INJECT_EVENTS) |
| Reset paksa | **Tidak bisa** | `am force-stop` sungguhan |
| Wake | `WAKE_LOCK` (bisa ditolak OEM) | `input keyevent 224` |
| Sifat | Best-effort | Deterministik |

**Aturan desain yang tidak boleh dilanggar:** tidak ada fitur yang HANYA bisa
jalan di Tier 1. Setiap fitur wajib punya jalur degradasi jujur ke Tier 2, dan
perbedaan keandalan harus terlihat di UI.

**Bukti Tier 2 bisa berhasil:** akun Instagram pernah `verified` tanpa shell ADB.

## 4. Komponen

```
app/src/main/java/com/gososmed/agent/privileged/
  PrivilegedShell.kt        # antarmuka tunggal; satu-satunya pintu ke shell
  AdbLocalShell.kt          # klien ADB lokal: pairing, connect, exec
  AdbKeyStore.kt            # generate + simpan kunci RSA (penyimpanan privat app)
  AdbPairingController.kt   # state machine pairing + status untuk UI
```

`AgentAccessibilityService` hanya bicara ke `PrivilegedShell`. Implementasinya
bisa diganti tanpa menyentuh pemanggil.

### 4.1 Library dan dependensi

```gradle
// settings.gradle.kts — repository WAJIB di sini, bukan di modul app
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url "https://jitpack.io" }   // BARU
    }
}

// app/build.gradle.kts
implementation 'com.github.MuntashirAkon:libadb-android:3.1.1'  // Apache-2.0
implementation 'org.conscrypt:conscrypt-android:2.5.3'          // TLS 1.3 + pairing
implementation 'org.bouncycastle:bcpkix-jdk18on:1.78.1'         // sertifikat X509
```

**Kenapa BouncyCastle, bukan `sun-security-android`:** pilihan terakhir butuh
`hiddenapibypass` untuk menembus API tersembunyi Android. BouncyCastle bersih
dari trik itu, sehingga lebih aman untuk rilis jangka panjang.

### 4.2 API yang dipakai (terverifikasi di F0)

| Kebutuhan | Pemanggilan |
|---|---|
| Pairing | `AdbConnectionManager.getInstance().pair(host, port, code)` |
| Connect + temukan port otomatis | `connectTls(context, timeout)` |
| Jalankan perintah | `openStream("shell:")` → `AdbStream.openInputStream()/openOutputStream()` |

`connectTls` **menemukan host dan port sendiri**, jadi kita tidak perlu
mengimplementasikan mDNS (`NsdManager`). Bila penemuan gagal (sering dibatasi
OEM), APK menyediakan input manual `host:port`.

## 5. Perbandingan dengan pendekatan yang DITOLAK

| Pendekatan | Kenapa ditolak |
|---|---|
| Bundle biner `adb` + `exec()` dari data dir | Diblokir sejak Android 10 (`execve` pada berkas home dir aplikasi) |
| Bundle biner `adb` di `jniLibs` (cara LADB) | Android 15+ mewajibkan native lib **selaras halaman 16 KB**; biner pra-bangun umumnya belum → rusak di perangkat baru |
| Bundel APK Shizuku sebagai aset | **Dilarang lisensi Shizuku** (distribusi APK hasil kompilasi sendiri tidak diizinkan) |
| `rhythmcache/adb-kt` | Lisensi benar (Apache-2.0), tetapi 0 bintang/0 fork, usia < 2 bulan — risiko rantai pasok produksi |
| **`libadb-android` (DIPILIH)** | Apache-2.0, 407 bintang, 5 tahun, dipakai App Manager |

## 6. Peringatan yang harus dinyatakan jujur

| Peringatan | Sumber | Dampak |
|---|---|---|
| Belum diaudit keamanan | README library | Risiko diterima: berjalan lokal di HP pengguna, bukan di server |
| Dependensi LGPL (SPAKE2) | README library | Aman untuk distribusi APK; cukup atribusi |
| Pairing hilang setiap reboot | Batas Android 11+ | Status harus jujur; Tier 2 tetap jalan |
| Tidak kompatibel dengan Shizuku terpasang | README LADB | Tidak masalah: v0.9.0 tidak memakai Shizuku. Bila user masih punya Shizuku, minta uninstall |
| mDNS bisa dibatasi MIUI/HyperOS | Pengalaman OEM | Sediakan input manual `host:port` sebagai jalur kedua |

## 7. Cara memverifikasi (untuk operator)

```bash
# 1. Pastikan aplikasi Shizuku benar-benar sudah tidak ada di APK
grep -ri shizuku app/src/          # harus 0 hasil

# 2. Pastikan dependency Shizuku sudah hilang
grep -ri shizuku app/build.gradle.kts   # harus 0 hasil

# 3. Cek capabilities dari HP (via server setelah agent tersambung)
#    adb_paired / adb_connected / can_shell
```

## 8. Rujukan

- [F0-LIBRARY-VALIDATION.md](F0-LIBRARY-VALIDATION.md) — bukti pemilihan library
- [AGENT-COMMAND-CONTRACT.md](AGENT-COMMAND-CONTRACT.md) — kontrak command, kode alasan, capabilities
- [SETUP-3-LANGKAH.md](SETUP-3-LANGKAH.md) — panduan untuk pemilik HP
- [libadb-android](https://github.com/MuntashirAkon/libadb-android)
- [LADB (pendahulu ide ini)](https://github.com/tytydraco/ladb)
