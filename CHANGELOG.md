# Changelog

Semua perubahan penting pada GoSosmed Agent tercatat di sini.
Format mengikuti [Keep a Changelog](https://keepachangelog.com/id/1.1.0/)
dan versi mengikuti [SemVer](https://semver.org/lang/id/).

> **Kanal rilis:** rilis ber-label `vX.Y.Z` = **stabil** (dua angka terakhir
> naik saat fitur/fix). Rilis ber-label `vX.Y.Z-dev.N` = **dev** (build
> berkelanjutan dari `main`, belum diuji luas). Semua build ditandai jelas
> di GitHub Releases; APK dari CI `main` selalu berstatus **dev**.

## [Unreleased]

### Added

- `docs/TOOLCHAIN-LOKAL.md` — peta toolchain build APK lokal: JDK 17, Gradle 8.9,
  dan Android SDK 34 sudah terpasang, plus cara memanggilnya. Dokumen ini
  menuntaskan kebingungan "di mana JDK/Gradle/SDK" dan mencatat bahwa repo ini
  **tidak** memakai Gradle wrapper (`./gradlew` tidak ada).

### Fixed

- README "Cara 2 — Gradle lokal" menyuruh `./gradlew`, padahal wrapper tidak
  pernah di-commit → perintah itu selalu gagal. Sekarang memakai `gradle` dari
  instalasi lokal dan menautkan ke `docs/TOOLCHAIN-LOKAL.md`.

## [0.9.9] — 2026-09-14

### Fixed — kartu pairing bisa ditutup, dan statusnya tidak lagi berbohong

Laporan lapangan setelah v0.9.8: **kartu pairing tidak bisa ditutup**, menutupi
layar terus-menerus, dan menampilkan "Menghubungkan ke 127.0.0.1:39759" tanpa
pernah berubah — sementara notifikasi di saat yang sama bertuliskan
"terhubung". Keadaan yang saling bertentangan itu membuat wajar pertanyaan
"yang dipakai yang mana?".

#### 1. Kartu menolak ditutup (akar: referensi dibuang sebelum dipakai)

`hideOverlay()` dulu melakukan `overlay = null` **lebih dulu**, lalu melepas
kartu lewat `AgentAccessibilityService.instance?.detachAccessibilityOverlay(...)`.
Tanda tanya itu membuat kegagalan SENYAP: bila layanan aksesibilitas terputus
(`instance == null`), pelepasan batal — dan karena referensi `overlay` sudah
dibuang, tidak ada kesempatan mencoba lagi. Kartu menjadi jendela yatim.

Sekarang:
- referensi hanya dibuang **setelah** pelepasan terbukti berhasil, sehingga
  `onDestroy()` masih bisa mencoba lagi;
- jalur utama memakai `card.wm` — WindowManager yang **memasang** kartu —
  sehingga tetap berfungsi meski layanan aksesibilitas sudah mati;
- dua jalur cadangan bila jalur utama menolak.

`PairingOverlay.Card` kini menyimpan `wm` untuk keperluan ini.

#### 2. Kartu dan notifikasi bisa menampilkan keadaan yang berlawanan

Keduanya diperbarui sendiri-sendiri, dan jalur kode dari notifikasi
(`onCodeSubmitted`) tidak pernah menyentuh kartu sama sekali. Semua pembaruan
status kini lewat satu fungsi `publish()`, sehingga inti status **selalu
identik** di kedua permukaan. Notifikasi masih boleh menambahkan panduan di
bawahnya, tetapi tidak pernah bisa menampilkan keadaan yang bertentangan.

#### 3. Port tidak terisi otomatis

mDNS dimulai **sebelum** kartu dipasang, jadi port sering sudah ketemu saat
kartu muncul — tetapi `showOverlay()` tidak pernah menerapkannya, sehingga
pengguna harus mengetik port manual. Sekarang port yang tersimpan selalu
diterapkan begitu kartu terpasang (`onOverlayReady()`): pengguna cukup mengetik
6 angka kode.

#### 4. Kartu tidak tersedia → hanya masuk log

Bila aksesibilitas belum aktif **dan** izin "tampilkan di atas aplikasi lain"
belum diberikan, dulu kasus ini hanya dicatat di log; pengguna menunggu kartu
yang tidak pernah datang. Sekarang keadaannya diumumkan dan diarahkan ke baris
notifikasi, yang memang selalu tersedia.

#### 5. IP tidak lagi dicetak mentah

"127.0.0.1" memang **benar** untuk self-pairing (HP memasangkan dirinya
sendiri dengan `adbd` di HP yang sama — bukan IP Wi-Fi), tetapi mencetaknya
mentah di kartu membuat pengguna mengira IP-nya salah. Teks kini netral;
tujuan koneksi dipublikasikan oleh `AdbPairingService` pada saat yang tepat.

### Perbaikan lanjutan setelah uji `v0.9.9-dev.1`

Uji perangkat pada `v0.9.9-dev.1` menemukan tiga masalah yang masih tersisa:
kartu tidak merespons tutup, port tidak selalu terisi, dan kolom angka pada
notifikasi tidak bisa dipakai. Tiga akar berbeda ditemukan dan diperbaiki.

#### 6. Tombol ✕ kini berarti "sembunyikan kartu", bukan "hentikan sesi"

Dulu `onClose` memanggil `cleanupAndStop()`: satu ketukan pada ✕ mematikan
foreground service, notifikasi, dan mDNS sekaligus. Bila pelepasan jendela
gagal di tengah jalan, pengguna mendapat kombinasi terburuk — kartu masih
terlihat tetapi sesi cadangannya sudah mati.

Sekarang ✕ hanya memanggil `dismissOverlay()`; sesi tetap hidup dan input
pindah ke notifikasi. Aksi **Batal** di notifikasi adalah satu-satunya yang
menghentikan seluruh sesi. Pelepasan jendela memakai `removeViewImmediate()`
dengan dua fallback dan tombol ✕ diperbesar ke target sentuh ±48dp; tombol
BACK pada kartu juga menutup kartu.

#### 7. RemoteInput notifikasi ditulis ulang sebagai state machine

Akar "input angka tidak berfungsi" adalah notifikasi yang dibangun ulang pada
setiap perubahan status. Di banyak OEM, `notify()` dengan ID yang sama saat
kolom inline terbuka akan **menutup kolom dan menghapus ketikan**. Aksi input
juga dipasang sebelum port diketahui, sehingga PendingIntent bisa membawa
hint `-1`.

Mengikuti pola produksi AppManager:

- saat port belum ada: notifikasi hanya punya **Buka Debug Nirkabel** dan
  **Batal**;
- setelah port diketahui: notifikasi diganti SEKALI menjadi aksi
  **Ketik Kode Pairing** + **Batal** dengan port tertanam;
- saat kode dikirim: semua aksi langsung dibersihkan agar spinner inline tidak
  menggantung;
- selama `Stage.INPUT`, status kecil tidak lagi memanggil `notify()`;
- `setOnlyAlertOnce(true)` + `setSilent(true)` mencegah shade terlipat ulang.

#### 8. Discovery port dibuat tahan OEM

`libadb-android.AdbMdns` tidak memegang `WifiManager.MulticastLock`; pada
sebagian Xiaomi/Oppo/Vivo, paket mDNS dibuang kernel sampai lock itu dipegang.
Callback kegagalan NSD-nya juga diam, sehingga "belum ada dialog" dan
"discovery gagal" tidak bisa dibedakan.

`AdbPairingPortDiscovery` kini memakai `NsdManager` langsung dengan:
- izin `CHANGE_WIFI_MULTICAST_STATE` + `MulticastLock`;
- log untuk `onStartDiscoveryFailed` / `onResolveFailed`;
- retry dengan backoff selama sesi hidup;
- satu resolve aktif (menghindari `FAILURE_MAX_LIMIT`);
- filter alamat lokal + probe port loopback agar layanan pairing HP lain di
  LAN tidak dipakai keliru.

#### 9. Port+kode dapat terbaca otomatis dari dialog Setelan (best-effort)

Karena pengguna sudah memberi izin AccessibilityService, sesi pairing kini
mendaftarkan pemindai yang **hanya aktif selama sesi** dan **hanya membaca
window Setelan**. Parser mencari kombinasi `IPv4:port` + enam angka pada dialog
"Pasangkan perangkat dengan kode pairing". Nilai kode tidak pernah dicatat ke
log.

### 10. `v0.9.9-dev.3` — overlay pairing DIHAPUS

Uji perangkat pada `v0.9.9-dev.2` memberi hasil yang menentukan:

- **notifikasi sudah berhasil**: pairing connected dan port otomatis bekerja;
- **overlay tetap tidak bisa ditutup**, bahkan setelah dua strategi pelepasan
  window (`removeView`, lalu `removeViewImmediate` + fallback + retry).

Kesimpulan engineering-nya bukan menambah tambalan ketiga, tetapi menghapus
seluruh permukaan yang tidak stabil. `PairingOverlay.kt`, jalur
`TYPE_ACCESSIBILITY_OVERLAY`, jalur `TYPE_APPLICATION_OVERLAY`, tombol ✕, dan
semua status/fallback kartu dihapus. Pairing kembali ke bentuk yang dipakai
AppManager di produksi: **foreground service + notifikasi RemoteInput + mDNS**.

Pembacaan dialog Setelan tetap dipertahankan sebagai peningkatan UX: bila
AccessibilityService dapat membaca port+kode, pairing langsung dicoba tanpa
input. Bila tidak, jalur manual adalah satu jalur yang sudah terbukti bekerja
di HP pengguna: **Ketik Kode Pairing** di notifikasi.

`SYSTEM_ALERT_WINDOW` dan `AgentOverlay` 1x1 **tidak** dihapus karena itu fitur
terpisah untuk pengecualian Background Activity Launch saat membuka aplikasi
target; ia bukan bagian dari pairing.

### Batas jujur

`v0.9.9-dev.2` sudah diuji di perangkat: notifikasi+auto-port terbukti
berhasil, overlay terbukti tetap gagal ditutup. `v0.9.9-dev.3` menghapus
overlay berdasarkan bukti itu, tetapi tetap harus diuji ulang di perangkat
sebelum tag stabil `v0.9.9`: terutama tidak adanya kartu yatim lama, inline
reply notifikasi, mDNS dengan MulticastLock, dan auto-fetch dialog Setelan.
Unit test JVM membuktikan parser port/kode, bukan perilaku SystemUI.

## [0.9.8] — 2026-09-14

### Fixed — PAIRING AKHIRNYA BERFUNGSI: Conscrypt versi sendiri

Laporan lapangan setelah v0.9.7: overlay dan notifikasi **sudah muncul** (kedua
perbaikan UI bekerja), tetapi saat kode 6 angka dimasukkan:

```
✗ Gagal: adb_pair_failed: java.lang.NoSuchMethodException:
  com.android.org.conscrypt.Conscrypt.exportKeyingMaterial
  [class javax.net.ssl.SSLSocket, class java.lang.String, class [B, int]
```

#### Akar masalah

Penelusuran ke sumber `libadb-android`
(`libadb/src/main/java/io/github/muntashirakon/adb/PairingConnectionCtx.java:157-179`):

```java
if (SslUtils.isCustomConscrypt()) {
    conscryptClass = Class.forName("org.conscrypt.Conscrypt");
} else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
    throw new SSLException("TLSv1.3 isn't supported on your platform. ...");
} else {
    conscryptClass = Class.forName("com.android.org.conscrypt.Conscrypt");
}
Method exportKeyingMaterial = conscryptClass.getMethod(
        "exportKeyingMaterial", SSLSocket.class, String.class, byte[].class, int.class);
```

`com.android.org.conscrypt.Conscrypt.exportKeyingMaterial` adalah **API
tersembunyi** (`@UnsupportedAppUsage`) yang tidak dapat direfleksikan oleh
aplikasi dengan `targetSdk 34`. Karena itu `getMethod(...)` melempar
`NoSuchMethodException` — dan pairing mati **sebelum** sempat mengirim kode apa
pun ke `adbd`. Pesan "kode salah" tidak pernah benar di sini.

`SslUtils.getSslContext()` memilih jalur Conscrypt platform karena
`Class.forName("org.conscrypt.OpenSSLProvider")` gagal — kita memang tidak
membawa Conscrypt sendiri.

#### Akar kesalahannya: satu kalimat di komentar kita

`app/build.gradle.kts` sebelumnya berbunyi *"libadb sudah membawa
bcprov-jdk15to18 dan spake2-android sebagai dependensi runtime, **jadi
TLS/pairing tidak perlu ditambah manual**"*. Itu asumsi yang salah. README
`libadb-android` bagian *Adding Dependencies* mensyaratkan **salah satu** dari:

- `org.lsposed.hiddenapibypass:hiddenapibypass:6.1` — menembus API tersembunyi
  (trik rapuh, bisa patah di Android berikutnya), **atau**
- `org.conscrypt:conscrypt-android:2.5.3` — *"the recommended choice"* menurut
  README.

Kita tidak punya keduanya.

#### Perbaikan

Ditambahkan `org.conscrypt:conscrypt-android:2.5.3` (versi terbaru di Maven
Central, sama dengan yang direkomendasikan README). Dipilih jalur Conscrypt
sendiri, bukan bypass API tersembunyi — tanpa trik rapuh, dan tidak akan patah
saat Google memperketat kebijakan API lagi.

Efeknya: `SslUtils.getSslContext()` berhasil memuat
`org.conscrypt.OpenSSLProvider`, menandai `customConscrypt = true`, sehingga
`PairingConnectionCtx` memakai `org.conscrypt.Conscrypt` — API **publik** milik
library yang kita bawa — alih-alih Conscrypt platform yang tersembunyi.

#### Catatan

APK bertambah besar karena artefak ini membawa `.so` native untuk setiap ABI.
Itu konsekuensi yang diterima: pairing yang berfungsi jauh lebih penting
daripada APK yang ramping.

## [0.9.7] — 2026-09-14

### Fixed — v0.9.6 masih gagal di HP: overlay & pengisian kode tidak muncul, Langkah 1 mati

Laporan setelah v0.9.6 dipasang di HP: *"masih tidak bisa konek wireless debug,
tidak menampilkan overlay atau pengisian kode untuk pairing"* dan *"ketika saya
klik hubungkan langkah 3, langkah 1 mati / status belum aktif, padahal di
Setelan aksesibilitas sudah ON"*.

Akar masalah ditemukan dengan membandingkan kode kita terhadap **implementasi
produksi AppManager** (`io.github.muntashirakon.AppManager.adb.AdbPairingService`)
— aplikasi dari penulis `libadb-android`, library yang kita pakai juga.

#### 1. Overlay TIDAK PERNAH BISA muncul tanpa izin SYSTEM_ALERT_WINDOW

v0.9.4–v0.9.6 memasang satu-satunya overlay dengan `TYPE_APPLICATION_OVERLAY`.
Jendela jenis itu menuntut izin "Tampilkan di atas aplikasi lain" **dan** saklar
OEM MIUI/HyperOS yang terpisah. Bila salah satu belum aktif, kode langsung
`return` tanpa memberi tahu pengguna — kegagalan senyap, dan pengguna melihat
persis "tidak ada overlay apa pun".

**Fix:** overlay kini dipasang lewat `TYPE_ACCESSIBILITY_OVERLAY` oleh
`AgentAccessibilityService`. Jendela jenis ini **tidak memerlukan izin apa pun**
dan tidak terkena penjagaan pop-up latar belakang OEM, sebab jendelanya milik
layanan sistem, bukan "aplikasi yang menggambar di atas aplikasi lain".
`TYPE_APPLICATION_OVERLAY` tetap tersedia sebagai jalur cadangan, dan notifikasi
RemoteInput tetap jalur ketiga yang selalu ada.

#### 2. Kode dari notifikasi tidak pernah sampai ke service

v0.9.5/v0.9.6 mengirim hasil RemoteInput lewat `PendingIntent.getBroadcast()`
dan `BroadcastReceiver` yang didaftarkan dinamis dengan `RECEIVER_EXPORTED`.
Rantai itu punya banyak titik gagal senyap.

**Fix (mengikuti AppManager):** aksi notifikasi kini memakai
`PendingIntent.getForegroundService()` yang menunjuk ke `AdbPairingService`
sendiri dengan flag `FLAG_MUTABLE` (wajib — SystemUI menuliskan hasil ketikan ke
Intent itu), dan hasilnya dibaca di `onStartCommand()` lewat
`RemoteInput.getResultsFromIntent(intent)`. `BroadcastReceiver` dinamis dihapus
seluruhnya. `startForeground()` dipanggil lebih dulu di cabang itu, karena
PendingIntent bertipe foreground service menuntutnya dalam 5 detik.

#### 3. Klik "Hubungkan" mematikan Langkah 1 (Langkah 1 "mati")

`AdbPairingService`, `AgentForegroundService`, `AgentAccessibilityService` dan
`MainActivity` berada di **satu proses**. Satu exception yang lolos dari
`onStartCommand()` — mis. dari `buildNotification()` atau pembuatan view, yang
di v0.9.6 **tidak** dibungkus try/catch — mematikan seluruh proses, dan
`AccessibilityService` ikut mati bersamanya. Itulah sebab keluhan ini muncul
persis saat tombol Langkah 3 ditekan.

**Fix berlapis:**
- Seluruh `onCreate`/`onStartCommand` dan pembuatan notifikasi/kartu dibungkus
  try/catch. Jalur pairing tidak boleh bisa menjatuhkan proses.
- `AgentApp` (Application baru) memasang penangkap exception terakhir yang
  menuliskan sebab crash ke disk; `MainActivity` menampilkannya di tab Log pada
  peluncuran berikutnya. Kegagalan tidak lagi hilang bersama prosesnya.

#### 4. Status Langkah 1 dibaca dari API resmi, bukan dari flag memori

v0.9.6 menyimpulkan status dari flag `bound` + penguraian string
`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. String itu berbeda format
antar-OEM, dan flag `bound` hilang begitu OEM me-restart proses agent.

**Fix:** `AgentAccessibilityService.osEnabled()` memakai API resmi
`AccessibilityManager.getEnabledAccessibilityServiceList()` (mengembalikan
`ComponentName` yang sudah terurai — tidak ada penguraian string), dengan
`Settings.Secure` sebagai cadangan. Aturannya "salah satu menyebut aktif =
AKTIF", sebab menampilkan tombol "Aktifkan" untuk layanan yang sudah aktif
jauh lebih merugikan daripada kebalikannya. `isEnabled()` dan
`capabilitiesJson().a11y_enabled` keduanya memakai jalur baru ini.

#### 5. Kartu tidak bisa digeser (drag) — cacat yang belum pernah terlaporkan

`setOnTouchListener` dipasang pada LinearLayout kartu, padahal
`ViewGroup.dispatchTouchEvent` menyerahkan event ke anak yang menjadi sasaran
sentuhan. Akibatnya listener induk tidak pernah dipanggil saat jari menyentuh
EditText/tombol — praktis seluruh permukaan kartu.

**Fix:** listener dipasang pada TextView judul (view daun), sehingga geser
berfungsi dari baris judul tanpa menelan sentuhan EditText dan tombol.

#### Catatan
Kartu pairing dipindahkan ke berkas sendiri (`PairingOverlay.kt`) supaya satu
implementasi dipakai oleh kedua jenis jendela. `AdbPairingService` juga
mendapat batas umur sesi 10 menit (sama seperti AppManager) agar layanan tidak
menggantung selamanya bila pengguna meninggalkannya.

## [0.9.6] — 2026-09-13

### Fixed — Pairing ADB ala Shizuku + Stabilitas Langkah 1 (regresi v0.9.4/v0.9.5)

Perbaikan mendalam atas 3 keluhan lapangan + 1 regresi UX yang paling mengganggu.

#### 1. Overlay pairing TIDAK muncul → AKAR TEKNIS DITEMUKAN
- **`AdbPairingService` didaftarkan TANPA `android:foregroundServiceType`.** Dengan
  `targetSdk 34`, memanggil `startForeground()` pada service tanpa tipe yang
  dideklarasikan melempar `MissingForegroundServiceTypeException` — service MATI
  sebelum overlay maupun notifikasi sempat dibuat. Jadi bukan overlay-nya yang
  ditolak; service-nya crash lebih dulu. Kini dideklarasikan
  `foregroundServiceType="specialUse"` + property subtype.
- `startForeground()` dibungkus `promoteToForeground()` (overload bertipe API 34 →
  fallback overload lama), dan kegagalan FGS kini memberi pesan yang benar, bukan
  diam lalu mati senyap.
- Overlay dipasang **sinkron** pada main thread (v0.9.5 memakai `main.post{}` yang
  tiba setelah fokus OS sudah berpindah ke Setelan). Ditambah verifikasi
  `isAttachedToWindow` agar penolakan sistem tercatat di log.
- Deteksi izin OEM MIUI/HyperOS ("Tampilkan jendela sembulan saat berjalan di latar
  belakang") yang terpisah dari `Settings.canDrawOverlays()`.
- Bug tombol: `updateOverlayStatus()` memakai `findViewById<Button>(View.NO_ID)`
  yang selalu null → tombol "Hubungkan Sekarang" tak pernah aktif kembali setelah
  gagal. Kini dicari lewat tag.

#### 2. Kode pairing berubah saat pindah ke app GoSosmed → jalur Notifikasi RemoteInput
- Notifikasi interaktif (**jalan utama**, seperti Shizuku) diperbaiki total:
  `RECEIVER_EXPORTED` (v0.9.5 memakai `NOT_EXPORTED`, sehingga RemoteInput dari
  SystemUI DITOLAK senyap di Android 13+/14 — notifikasi terlihat tapi tak berfungsi),
  `BigTextStyle` berisi instruksi, aksi "Buka Debug Nirkabel" & "Batal", channel
  `IMPORTANCE_HIGH` + `VISIBILITY_PUBLIC`.
- Ditangani balapan: kode bisa diketik sebelum mDNS menemukan port. Kini menunggu
  hingga 6 detik, dan bila port tetap tak ada pesannya BENAR ("port belum
  terdeteksi"), bukan menyesatkan ("kode salah").
- `MainActivity` menampilkan dialog 3 langkah lebih dulu; service dinyalakan lalu
  navigasi ke Setelan diberi jeda 250 ms agar notifikasi benar-benar terpasang.
- Prasyarat izin `POST_NOTIFICATIONS` dicek & diminta lebih dulu — tanpa itu jalur
  utama tidak akan terlihat.

#### 3. "IP tidak 127.0.0.1" → klaim v0.9.4 berbasis premis SALAH
- **Fakta dari sumber library (libadb-android 3.1.1, `AndroidUtils.java:39-57`):**
  `AndroidUtils.getHostIpAddress()` mengembalikan `InetAddress.getLoopbackAddress()`
  = `127.0.0.1`, dan pada EMULATOR `10.0.2.2`. Ia TIDAK PERNAH mengembalikan IP Wi-Fi.
  Jadi "Dynamic IP Adapter" v0.9.4 tidak hanya tidak berguna — pada emulator ia
  MERUSAK pairing.
- **Pairing = SELF-PAIRING** (HP memasangkan dirinya dengan `adbd` di HP yang sama),
  sehingga host pairing WAJIB loopback. `AdbLocalShell` & `AdbPairingService` kini
  memakai konstanta eksplisit `LOOPBACK_HOST = "127.0.0.1"`; import `AndroidUtils`
  dihapus dari keduanya.
- Yang sebenarnya dibutuhkan pengguna adalah **PORT**, dan itu sudah dideteksi
  otomatis via mDNS `_adb-tls-pairing._tcp`. Untuk CONNECT setelah pairing, libadb
  memakai mDNS `_adb-tls-connect._tcp` dan **mengabaikan** `setHostAddress(...)`
  sepenuhnya. IP Wi-Fi hanya relevan pada jalur cadangan manual
  (`AdbPairingController.connectTo(host, port)`), yang tetap utuh.

#### 4. REGRESI: "Langkah 1 disuruh diaktifkan ulang setiap pindah tab"
- **Akar masalah:** `MainActivity.refreshPerms()` memakai
  `AgentAccessibilityService.instance?.isServiceReady()`, sedangkan
  `isServiceReady()` = `rootInActiveWindow != null`. Nilai itu SEMENTARA null saat
  berpindah activity, layar terkunci, atau app target `FLAG_SECURE` — kondisi NORMAL,
  bukan tanda layanan mati. Diperparah `onDestroy()` yang meng-null-kan `instance`
  (OEM agresif seperti MIUI rutin me-restart layanan accessibility).
- **Perbaikan:** dipisahkan tegas antara "layanan ter-BIND" dan "window siap dibaca".
  Ditambahkan `AgentAccessibilityService.bound` (di-set di `onServiceConnected`,
  di-reset di `onUnbind`/`onDestroy`) dan `isEnabled()` sebagai sumber kebenaran
  untuk status IZIN, diperkuat `reconcileFromSettings()` yang membaca
  `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. Kesiapan live
  (`isServiceReady()`) tetap dipakai internal oleh perintah dump/tap.
- Laporan `capabilities` kini memisahkan `a11y_enabled` (bind) dari `a11y_ready`
  (window live) — dua hal berbeda yang selama ini tertukar.

#### 5. Perbaikan build: error kompilasi yang menjatuhkan CI (commit 8ccce13)
- `AdbPairingService.codeReceiver` adalah `object : BroadcastReceiver()`; di dalam
  blok `main.post { ... }` pada cabang "port belum terdeteksi", pemanggilan
  `Toast.makeText(this, ...)` membuat `this` menunjuk ke **BroadcastReceiver**
  (bukan `Context`), sehingga Kotlin gagal mengompilasi:
  `None of the following candidates is applicable` + `Unresolved reference 'show'`.
  Ini persis penyebab `build-apk` (step *Unit tests*) dan `release`
  (step *Build release APK*) GAGAL pada commit `8ccce131`.
- Diperbaiki menjadi `this@AdbPairingService` (qualified `this`). Pemanggilan
  `Toast.makeText` lain di berkas yang sama sudah benar karena berada di dalam
  member function `Service` (bukan di dalam objek anonim).
- Diverifikasi lokal: `gradle testDebugUnitTest assembleDebug` → **BUILD SUCCESSFUL**.

### Notes
- `MainActivity` (Langkah 3) tetap membaca status ADB apa adanya; perubahan ini tidak
  menyentuh kontrak wire/command server.

## [0.9.5] — 2026-09-13

### Fixed & Enhanced — Foreground Pairing Service & Dual Input (Overlay + Notification)
Mengatasi masalah overlay tidak muncul di HyperOS / MIUI / Android 11+:
1. **`AdbPairingService` (Foreground Service):** Mengangkat konteks overlay ke tingkat Service sistem
   sehingga tidak di-kill atau ditahan oleh OS saat Activity kehilangan fokus saat membuka Pengaturan.
2. **Notification RemoteInput (Metode Shizuku Resmi):** Notifikasi prioritas tinggi dengan tombol
   "Ketik Kode Pairing" langsung di status bar Android! Pengguna bisa mengetik 6 digit dari tirai notifikasi
   tanpa perlu overlay jika perangkat membatasi float view.
3. **Draggable Floating Card:** Overlay window kini bisa digeser (touch & drag) jika menutupi angka pop-up sistem.

## [0.9.4] — 2026-09-13

### Added — Floating Overlay Window & Dynamic IP for Seamless ADB Pairing
Solusi tuntas untuk masalah Wireless Debugging Android 11+:
1. **Floating Window Overlay (`AdbPairingOverlay`):** Saat tombol "Hubungkan" ditekan,
   jendela input melayang di atas layar Pengaturan Wireless Debugging dan otomatis membuka
   menu Opsi Pengembang/Debug nirkabel. Pop-up kode 6-digit Android **TIDAK AKAN TERTUTUP**
   dan kodenya tidak berubah-ubah lagi!
2. **mDNS Auto-Discovery Listener:** Otomatis mendeteksi port pairing acak dari sistem via `_adb-tls-pairing._tcp`
   sehingga pengguna tidak perlu mengetik port secara manual jika mDNS aktif.
3. **Dynamic IP Adapter:** Tidak lagi memakai hardcoded `127.0.0.1`, melainkan membaca antarmuka
   Wi-Fi lokal (`AndroidUtils.getHostIpAddress`) sesuai yang ditampilkan pada layar Debug nirkabel.

## [0.9.3] — 2026-09-13

### Fixed — ANR pada tombol "Putuskan" (lanjutan temuan v0.9.2)
Menutup satu-satunya jalur yang masih memanggil metode library ber-kunci dari
**main thread**.

`disconnectNow()` memanggil `disconnect()` pada library, yang meminta
`synchronized (mLock)` — kunci yang bisa sedang ditahan penyambungan latar
sampai ~11 detik. Metode ini dipanggil dari main thread (tombol **Putuskan** di
tab Setup, dan `AdbPairingController.forget()`), sehingga menekan tombol itu
saat penyambungan berjalan bisa membekukan UI.

Sekarang status diturunkan **seketika** (UI langsung jujur bahwa sesi tidak lagi
dipakai), sedangkan penutupan socket sebenarnya dijadwalkan ke thread IO
sehingga tidak menahan pemanggil.

### Notes
- Setelah perbaikan ini, **tidak ada lagi** pemanggilan metode library yang
  menahan `mLock` dari main thread: `status()`/`exec()` hanya membaca field
  `@Volatile`; `connect*`, `disconnect`, `openStream` seluruhnya di thread IO.
- Yang MASIH berjalan di main thread dan dicatat sebagai pekerjaan lanjutan:
  pemanggilan shell dari command aksesibilitas (`tap`, `startApp`, `wakeScreen`,
  `killAppMode`) serta `awaitForeground()`. Ini diwarisi dari v0.8.0 dan
  perbaikannya menuntut pemindahan dispatch perintah secara keseluruhan —
  terlalu berisiko dilakukan tanpa pengujian perangkat.
- Belum diuji pada perangkat nyata (rencana fase F9).

## [0.9.2] — 2026-09-13

### Fixed — tiga cacat pada transport ADB, ditemukan lewat penelusuran ulang
Semuanya ditemukan dengan **membaca ulang kode library** (`AbsAdbConnectionManager`)
dan menelusuri ulang jalur eksekusi — **sebelum** pengujian di perangkat.
Dua di antaranya akan membuat pairing tampak gagal walaupun di HP berhasil.

1. **SELF-DEADLOCK: sesi setelah pairing tidak pernah bisa terbentuk.**
   `connectAsync()` mengirim tugas ke executor satu-thread, lalu memanggil
   `connectNow()` yang **menyerahkan tugas LAGI ke executor yang sama** dan
   menunggu hasilnya. Karena hanya ada satu thread dan thread itu sedang
   menunggu, tugas di dalamnya tidak akan pernah berjalan: hasilnya **selalu**
   timeout ~13 detik dan dilaporkan gagal.
   Sekarang bodi penyambungan ([doConnect]) dipanggil langsung di dalam tugas
   itu, tanpa penyerahan bersarang.

2. **ANR: `status()` bisa memblokir main thread sampai ~11 detik.**
   `isConnected()` pada library meminta `synchronized (mLock)` — kunci yang
   ditahan `autoConnect()` **selama seluruh** discovery + socket. Sejak
   penyambungan dipindahkan ke latar, `status()`/`capabilities` yang dipanggil
   dari main thread bisa menunggu kunci itu sampai 11 detik.
   Sekarang status koneksi disimpan di field `@Volatile` milik kami sendiri
   (`linkUp`) yang di-set thread IO; `status()` dan `exec()` hanya membaca field
   itu — tanpa kunci, tanpa IO.

3. **Risiko thread IO macet permanen oleh probe uid.**
   `openStream()` pada library tidak menerima timeout dan menahan kunci; bila
   `adbd` tidak menjawab, panggilan itu bisa menggantung dan menempati
   satu-satunya thread ADB selamanya. Probe dipindahkan keluar dari jalur
   connect, hanya dijalankan **setelah satu perintah benar-benar sukses**, dan
   stream-nya didaftarkan sebagai `activeStream` sehingga timeout perintah bisa
   menutupnya paksa. Bila tetap gagal, `adb_uid` tetap `-1` ("belum diketahui") —
   jujur, bukan tebakan. Field ini murni informasi; tidak ada logika backend
   atau frontend yang bercabang atas nilainya.

### Notes
- Belum diuji pada perangkat nyata (rencana fase F9). Perbaikan 1 dan 2 hanya
  bisa dibuktikan pada perangkat, jadi verifikasi yang tersedia saat ini adalah
  pembacaan kode + kompilasi CI.

## [0.9.1] — 2026-09-13

### Fixed — dua cacat pada alur pairing transport ADB
Keduanya ditemukan lewat penelusuran ulang alur pairing terhadap perilaku
library dan batas waktu server, **sebelum** pengujian di perangkat. Keduanya
akan membuat pairing tampak GAGAL di dasbor walaupun di HP sebenarnya berhasil.

1. **Anggaran waktu perangkap (batas luar lebih pendek dari bagian dalam).**
   `connectTls()` memakai timeout yang diberikan untuk penemuan mDNS **lalu
   menambah** timeout socket sendiri, sehingga durasi terburuknya lebih panjang
   daripada batas yang dipasang pemanggil. Akibatnya agen menyerah lebih dulu,
   dan — karena socket blocking tidak bisa diinterupsi — tugas itu tetap
   menempati satu-satunya thread ADB, sehingga perintah berikutnya ikut macet.
   Sekarang anggaran dipisah eksplisit (penemuan 5 dtk, socket 6 dtk) dan batas
   luar selalu ≥ durasi terburuk bagian dalam.

2. **Pairing melewati batas 30 detik command server.** Versi 0.9.0 menyambung
   secara sinkron setelah pairing, sehingga durasi terburuknya ~40 dtk —
   melewati `agenthub.DefaultTimeout` (30 dtk). Dasbor akan melaporkan gagal
   padahal kunci sudah tersimpan di HP. Sekarang pairing dikembalikan sebagai
   hasil (15 dtk + kelonggaran), dan penyambungan dijalankan di belakang.
   `capabilities` melaporkan keadaan sesi yang sebenarnya.

### Notes
- `adbPair` mengembalikan `ok = true` ketika **pairing** berhasil; sesi bisa
  masih `adb_connected = false` sesaat karena penyambungan berjalan di belakang.
  UI menampilkan "menyambung…" lalu diperbarui. Kontrak §3.2 sudah diperbarui.
- Belum diuji pada perangkat nyata (rencana fase F9).

## [0.9.0] — 2026-09-13

### Changed — BREAKING: Shizuku DIHAPUS TOTAL
Transport shell (hak uid 2000 / setara `adb shell`) kini memakai **klien ADB
milik aplikasi sendiri**, bukan aplikasi pihak ketiga Shizuku.
**Anda tidak perlu memasang Shizuku lagi** — cukup satu APK.

Yang dihapus:
- `ShizukuShell.kt`, izin `moe.shizuku.manager.permission.API_V23`, provider
  `rikka.shizuku.ShizukuProvider`, dependency `dev.rikka.shizuku:api` dan
  `:provider`, serta command `shizukuRequest`.

Yang menggantikannya (modul `privileged/`):
- **`AdbKeyStore`** — pasangan kunci RSA 2048 + sertifikat X.509 self-signed,
  digenerate sekali dan disimpan di penyimpanan privat aplikasi. Memakai
  BouncyCastle, **bukan** `sun-security-android` (pilihan itu butuh menembus
  API tersembunyi Android lewat `hiddenapibypass` — trik rapuh).
- **`AdbLocalShell`** — pairing, connect, dan eksekusi shell. Semua operasi
  berjalan di SATU thread IO (serialisasi mencegah dua stream merusak paket),
  setiap operasi dibatasi waktu, dan `close()` induk sengaja TIDAK dipanggil
  karena ia memusnahkan kunci privat sehingga koneksi berikutnya mustahil.
- **`AdbPairingController`** — menyiapkan transport, menyimpan status "pernah
  dipasangkan" sehingga UI bisa membedakan "belum pernah" dari "terputus
  setelah HP restart".
- **`AdbShellOutput`** — pengurai keluaran murni (teruji di JVM).

Nama transport berubah: `shell_shizuku` → **`shell_adb`**. Kapabilitas
`shizuku_*` diganti **`adb_paired` / `adb_connected` / `adb_uid` / `adb_error`**.
`can_shell` dipertahankan namanya. Kode alasan `shizuku_*` diganti enam kode
`adb_*`: `adb_not_paired`, `adb_pair_failed`, `adb_auth_failed`,
`adb_disconnected`, `adb_port_unknown`, `adb_disabled`.

### Added
- **Command `adbPair`** — memulai alur pairing transport ADB dari sisi server.
- **UI Setup 3 langkah berurut**: (1) Aksesibilitas — WAJIB, (2) izin
  "Tampilkan di atas aplikasi lain" — WAJIB, (3) Otomasi Lanjutan (ADB) —
  OPSIONAL dengan dialog pairing.
- **Kartu izin overlay di tab Setup.** Sebelumnya kartu ini TIDAK ADA sama
  sekali, padahal izinnya wajib sejak Android 10; tanpa itu Android membatalkan
  permintaan membuka aplikasi TANPA pesan error, sehingga pemilik HP bisa merasa
  "sudah mengaktifkan semuanya" sementara otomasi tetap gagal.
- Command `shell` dan `adbPair` kini dijalankan di thread IO, bukan main thread.
  Keduanya bisa memakan belasan detik; menjalankannya di main thread berisiko ANR.

### Fixed
- **Bug: tombol izin overlay tidak pernah di-`findViewById`** sehingga akan
  melempar `UninitializedPropertyAccessException` saat diketuk. Ditemukan lewat
  pemeriksaan silang otomatis antara id layout dan seluruh `lateinit var`.
- **Bug: error launch menyesatkan.** Versi lama membuang error percobaan
  pertama dan hanya melaporkan yang terakhir, sehingga kegagalan TikTok tampil
  sebagai masalah Shizuku padahal akar aslinya adalah launch aksesibilitas yang
  tidak muncul di foreground. Semua error percobaan kini dilaporkan berurutan.

### Notes — batas yang harus Anda tahu
- **Wireless Debugging tetap diperlukan** untuk fitur shell, dan pairing harus
  **diulang setiap HP selesai di-restart** (Android mematikan Debug nirkabel
  otomatis). Ini batas platform, bukan kekurangan aplikasi. Tanpa langkah ini
  aplikasi tetap berfungsi dengan kemampuan terbatas (aksesibilitas + overlay).
- Pairing terjadi antara HP dan **dirinya sendiri** lewat `127.0.0.1` — koneksi
  lokal, tidak menyentuh server GoSosmed.
- Teknik ini **tidak kompatibel** dengan Shizuku yang masih terpasang. Bila Anda
  pernah memasang Shizuku untuk versi lama, silakan hapus — tidak dipakai lagi.

## [0.8.0] — 2026-09-12

### Added
- **Transport Tier 1 — Shizuku (uid 2000 / shell).** Perintah kini bisa
  dijalankan dengan hak setara `adb shell` tanpa root dan tanpa PC:
  `am start` (lolos Background Activity Launch), `am force-stop` (reset
  layar deterministik), `input tap` (INJECT_EVENTS), `input keyevent 224`.
  Berkas baru `ShizukuShell.kt` — lihat `docs/SHIZUKU-TRANSPORT.md`.
- **Command `shell`**: eksekusi perintah dengan daftar putih biner
  (`am input monkey pm dumpsys wm settings cmd`). Di luar daftar ditolak
  dengan `reason=blocked`.
- **Command `shizukuRequest`**: server bisa memunculkan dialog izin Shizuku
  di HP.
- **Field `transport`** pada hasil `startApp`/`killApp`/`shell`
  (`shell_shizuku` = deterministik, `accessibility` = best-effort).
- **`capabilities` diperluas**: `transport_tier`, `last_launch_transport`,
  `shizuku_installed/running/permission/permission_denied_forever/uid/version`,
  `can_shell`, `can_inject_input`.
- **Dokumentasi kontrak** `docs/AGENT-COMMAND-CONTRACT.md`: daftar command,
  envelope JSON, seluruh kode `reason`, aturan preflight fail-closed untuk
  backend, dan copy remediasi untuk frontend `/accounts`.

### Changed
- `killApp` kini melaporkan `force_stop=true` **hanya** bila force-stop nyata
  terjadi (jalur Shizuku). Sebelumnya selalu `false`; server tidak boleh lagi
  mengasumsikan layar sudah bersih tanpa memeriksa field ini.
- `can_force_stop` dan `can_launch_app` dihitung dari kondisi nyata perangkat,
  bukan konstanta.
- `tap` dan `wake` memakai jalur shell bila tersedia, dengan fallback otomatis
  ke gesture/wakelock.

### Notes
- Tanpa Shizuku, agent **tetap berfungsi** di Tier 2 (accessibility + overlay
  v0.7.1) — degradasi mulus, bukan crash.
- `Shizuku.newProcess` privat sejak API 13; dipakai lewat refleksi dengan
  fallback aman. Risiko dan rencana migrasi ke `UserService` AIDL
  didokumentasikan di `docs/SHIZUKU-TRANSPORT.md`.
- Setelah HP reboot, Shizuku harus di-start ulang (pairing wireless
  debugging); selama itu transport turun ke Tier 2 secara jujur.

## [0.7.0] — 2026-09-11

### Added
- **Command `wake`** (blueprint Go-sosmed P0-4): server bisa menyalakan layar
  HP yang padam sebelum verify/harvest (wakelock ACQUIRE_CAUSES_WAKEUP).
  Tanpa ini dump UI mengembalikan null root saat layar mati. Keyguard
  PIN/pola tetap tidak bisa dibuka — kondisi itu dilaporkan jujur.
- **Sinkron versi / cek update**: kartu "Pembaruan aplikasi" baru di tab
  Setup — tombol **Cek Update** (menanyakan GitHub Releases) + tombol
  **Unduh APK** saat ada versi lebih baru. Otomatis: server menyertakan
  `latest_agent_version` + `apk_url` di `register_ack` setiap koneksi, jadi
  status update tampil tanpa cek manual. Cadangan: tahan teks versi di header.
- **`installed_platforms` di hello**: agent melaporkan platform sosial yang
  benar-benar terpasang di user primary (instagram/tiktok/youtube/facebook/
  threads) supaya dasbor hanya menawarkan app yang ada di HP ini.

### Fixed
- **Anti-chooser Dual Apps MIUI di sumbernya**: `startApp` kini memakai
  `LauncherApps.startMainActivity` dengan UserHandle primary (user 0) —
  resolver XSpace (com.miui.securitycore) tidak lagi muncul saat server
  meluncurkan TikTok/Facebook di HP yang punya app clone (insiden produksi
  2026-09-11: chooser menelan launch → verify "layar tidak dikenali").
  Clone berbagi nama package dengan app murni tetapi hidup di user lain
  (999); getActivityList(primary) tidak melihatnya, jadi yang diluncurkan
  selalu app murni. Jalur intent lama tetap ada sebagai fallback.

## [0.6.1] — 2026-09-04

### Added
- **agent_version di hello register**: agent kini melaporkan versinya
  (`BuildConfig.VERSION_NAME`) dalam `device_info` saat menyambung ke
  server. Dasbor BYOD memakainya untuk menampilkan badge
  "update tersedia" (server bandingkan dengan `GOSOSMED_AGENT_LATEST_VERSION`).
  Agent ≤0.6.0 tidak mengirim field ini → tanpa badge (degradasi jujur).

## [0.6.0] — 2026-09-04

> ⚠️ **WAJIB uninstall-install ulang**: keystore signing dirotasi (lihat
> Security di bawah) — APK lama TIDAK bisa di-upgrade ke 0.6.0
> (INSTALL_FAILED_UPDATE_INCOMPATIBLE). Ini perubahan sekali jalan demi
> keamanan distribusi.

### Security (Plan 07 Fase P0)
- **Rotasi keystore (P0-1)**: keystore lama yang pernah tersimpan di repo
  publik (password in-repo) dinyatakan MATI. Signing kini dari GitHub
  Secrets (`GOSOSMED_KEYSTORE_B64`/`STORE_PASSWORD`/`KEY_ALIAS`/
  `KEY_PASSWORD`); folder `keystore/` dihapus dari repo dan workflow
  `generate-keystore.yml` dihapus.
- **Receiver dikunci (P0-2)**: broadcast `com.gososmed.agent.CMD` (jalur adb)
  kini hanya aktif di build DEBUG — build release menolaknya, app lain di HP
  tidak bisa memicu dump/tap/setText.
- **Cleartext ditutup (P0-3)**: `usesCleartextTraffic` global diganti
  `networkSecurityConfig` — build release menolak semua trafik cleartext
  (wss:// saja); build debug tetap boleh ws:// LAN untuk pengembangan.

### Fixed
- **Race map pending (GAP-K12)**: `AgentWsClient.pending` kini
  `ConcurrentHashMap` + id atomik + `pairingCode` `@Volatile` — diakses
  bersamaan oleh thread reader OkHttp dan coroutine heartbeat/timeout.
- **Tidak ada sleep di main thread (K13)**: retry re-bind MIUI dipindah ke
  thread IO di `AgentWsClient`; `AgentCommand.execute` kini fail cepat dengan
  pesan jujur, bukan tidur ±2 detik menyumbat main looper.
- **Dump dibatasi (GAP-K6)**: `MAX_NODES` (2000) diterapkan sungguhan —
  dump layar berat berhenti tepat waktu dan ditandai
  `<hierarchy truncated="true">`; sebelumnya konstanta hanya dead code.

### Removed
- Jalur JSON dump tanpa pemanggil: `dumpJson()` /
  `HierarchySerializer.dumpToJson()` / `appendNodeJson()` (dead code).

## [0.5.1-dev.1] — 2026-09-04

### Changed
- **Log tenang**: sukses `screenshot` (dipoll dasbor tiap ~1,5 dtk saat viewer
  aktif) hanya dicatat maksimal tiap 30 detik; kegagalan selalu dicatat.
  Tab Log kini berisi kejadian penting, bukan spam polling.

## [0.5.0-dev.1] — 2026-09-04

> **Kanal DEV** — perubahan UI mayor; belum diuji lintas perangkat.

### Added
- **UI tab-based** (Beranda / Setup / Log): halaman TIDAK lagi scroll panjang
  tanpa henti; hanya konten tab yang scroll internal, banner status & tab
  selalu terlihat.
- **Panel Log profesional**: warna per jenis entri (sukses hijau, gagal merah,
  kejadian biru), tombol **Jeda/Lanjut**, **Salin** (clipboard), **Bersihkan**,
  dan counter baris + indikator "DIJEDA (ada entri baru)".
- **Klarifikasi data di log**: setiap command mencatat ke mana data pergi —
  screenshot kini menjelaskan "**gambar dikirim ke server (base64) — TIDAK
  disimpan di HP**"; dump/hierarki juga dijelaskan tujuannya.
- Dark mode penuh (Material 3 DayNight): palet semantik `values-night`,
  panel log konsol tetap gelap di kedua mode.
- Kartu izin dengan status NYATA (AKTIF ✓ / BELUM) + tombol yang otomatis
  nonaktif saat izin sudah diberikan; pemeriksaan baterai via
  `PowerManager.isIgnoringBatteryOptimizations`.

### Changed
- Tema naik ke `Theme.Material3.DayNight.NoActionBar` + header sendiri;
  kartu memakai latar membulat (`bg_card`), warna tidak lagi hex statis.
- Tombol debug (Dump/Package/Back/Home/Tap) dipindah ke kartu "Mode debug"
  tersembunyi (tap 7× versi) di tab Beranda — produksi bersih.

### Fixed
- Warna status sebelumnya hardcode abu terang `#EEF2F7`/`#F7F8FA` — tidak
  terbaca di dark mode; kini semua via resource tema.

## [0.4.1] — 2026-09-04

### Added
- Info perangkat (model, Android, layar) dikirim di hello `register` untuk
  kartu perangkat di dasbor.
- Log perintah dengan latensi (pemerintah v0.4.1 `AgentLog.add(cmd, ok, ms)`).

### Fixed
- Literal warna hex `Long` → `Int` pada `setTextColor` (crash build).

## [0.4.0] — 2026-09-04

### Added
- **Auto-pairing deep link** `gososmed://pair?ws=<url>&code=<KODE8>`:
  terbitkan kode di dasbor → buka tautan di HP → tersambung tanpa mengetik.
- Onboarding 3 langkah di UI produksi; input URL server dihilangkan dari
  jalur utama (default `wss://api.bamsbung.id`, override hanya mode debug).

## [0.3.1] — 2026-09-04

### Fixed
- **Flap reconnect** (register/putus tiap 2–8 s di perangkat nyata):
  reconnect loop kini dibatalkan saat koneksi sukses + single-flight guard
  mencegah dua socket paralel; backoff eksponensial + jitter.
  Ditemukan & diverifikasi lewat tes perangkat nyata (Xiaomi garnet).

## [0.3.0] — 2026-09-04

### Added
- **Pairing multi-user (auth server-issued)**: kode 8 karakter dari dasbor
  (sekali pakai, TTL 15 menit) mengikat HP ke akun pemiliknya — menggantikan
  kode 6 digit self-generated yang tidak aman.
- `register_ack`: pairing ditolak (kode salah/kedaluwarsa) → agent berhenti
  mencoba ulang dan menunggu kode baru (bukan loop tanpa akhir).
- **Stabilitas koneksi (M3)**: WS dimiliki foreground service (bertahan saat
  UI ditutup / proses dibunuh / reboot), `pingInterval` OkHttp 20 s,
  reconnect segera saat jaringan kembali (ConnectivityManager).
- Input kode pairing + tampilan status di UI; version bump APK.

### Changed
- **BREAKING**: enroll-first anonim dihapus — device lama tanpa pairing
  server-issued harus di-pair ulang dengan kode baru dari dasbor.

## [0.2.0] — 2026-09-02

### Added
- `dumpWindows` (getWindows) + `takeScreenshot` (AccessibilityService API 30+)
  — bukti visual untuk audit job.
- `startApp` / `killApp` / `hasPackage` / `listPackages` — agent bisa
  meluncurkan/menghentikan aplikasi tanpa root.

### Fixed
- Kompatibilitas MIUI (Xiaomi): service tidak hancur saat `startApp`
  (applicationContext + retry ketika instance aksesibilitas re-bind).

## [0.1.0] — 2026-08-31

### Added
- Fondasi agent: AccessibilityService, serializer hierarki kompatibel
  `uiautomator dump`, eksekusi tap/text/global tanpa root.
- Klien WebSocket outbound (auto-reconnect + heartbeat), pairing code +
  `device_id` persisten.
- CI GitHub Actions: build APK per push (artifact `gososmed-agent-debug`).

[Unreleased]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.7.0...HEAD
[0.7.0]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.6.1...v0.7.0
[0.6.1]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.6.0...v0.6.1
[0.6.0]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.5.0-dev.1...v0.6.0
[0.5.0-dev.1]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.4.1...v0.5.0-dev.1
[0.4.1]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.3.1...v0.4.0
[0.3.1]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/dedy45/gososmed-mobile-agent/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/dedy45/gososmed-mobile-agent/releases/tag/v0.1.0
