# Panduan Memasang GoSosmed Agent — 3 Langkah

> Untuk pemilik HP. Ditulis tanpa istilah teknis.
> Berlaku untuk aplikasi versi **0.9.0 ke atas**.
> Di versi lama (0.8.0 ke bawah) ada langkah tambahan memasang aplikasi
> **Shizuku** — versi 0.9.0 **tidak lagi** memerlukannya.

---

## Ringkasan

| Langkah | Wajib? | Perlu diulang setelah HP reboot? |
|---|---|---|
| 1. Layanan Aksesibilitas | **Wajib** | Tidak |
| 2. Izin "tampil di atas aplikasi lain" | **Wajib** | Tidak |
| 3. Otomasi lanjutan (ADB) | Opsional | **Ya** |

Langkah 1 dan 2 sudah cukup untuk mulai. Langkah 3 hanya menambah kestabilan
pada aplikasi yang sulit dibuka otomatis (terutama TikTok).

---

## Langkah 1 — Layanan Aksesibilitas (WAJIB)

Ini yang membuat aplikasi bisa "membaca layar" dan menekan tombol.

1. Buka **GoSosmed Agent**.
2. Masuk tab **Setup**.
3. Pada kartu "Akses otomatisasi (Aksesibilitas)":
   - Jika tombol bertuliskan **Aktifkan**, tekan tombol tersebut. Android membuka halaman Aksesibilitas. Cari **Agent GoSosmed**, lalu aktifkan.
   - Jika tombol bertuliskan **Cara Mengizinkan** (atau muncul status **DIBLOKIR ANDROID ⚠️**): ikuti panduan khusus Android 13/14 di bawah ini.
4. Android menampilkan konfirmasi keamanan. Tekan **Izinkan / OK**.
5. Kembali ke aplikasi. Statusnya sekarang harus **AKTIF ✓**.

### Khusus Android 13 & 14+: Cara Mengatasi "Setelan dibatasi" (Restricted Settings)

Android 13 dan 14 secara otomatis mengunci perizinan tingkat tinggi untuk aplikasi yang dipasang di luar Google Play Store (sideload). Saat Anda mencoba mengaktifkan Aksesibilitas, sakelar akan berwarna abu-abu atau muncul dialog:
> *"Setelan dibatasi: Demi keamanan Anda, setelan ini saat ini tidak tersedia"* (*Restricted setting: For your security, this setting is currently unavailable*).

**Langkah membuka kuncinya:**
1. Di tab **Setup** aplikasi GoSosmed Agent, ketuk tombol **"Cara Mengizinkan"** pada kartu Langkah 1.
2. Baca peringatan yang muncul, lalu tekan **"Buka Info Aplikasi"**. HP akan membuka halaman rincian aplikasi di Pengaturan sistem Android.
3. Di pojok kanan atas layar Info Aplikasi, ketuk ikon **titik tiga (⋮)**.
4. Pilih **"Izinkan setelan yang dibatasi" (Allow restricted settings)**.
5. Masukkan PIN, pola, atau sidik jari Anda untuk konfirmasi.
6. ⚠️ **PERINGATAN SANGAT PENTING:** Menu titik tiga (⋮) ini **HANYA BISA DIBUKA SEKALI** oleh sistem Android. Jangan tutup layar atau menekan tombol kembali sebelum memilih menu tersebut! Jika terlanjur tertutup tanpa dipilih, opsi tersebut akan hilang.
7. Kembali ke aplikasi GoSosmed Agent. Kartu Langkah 1 akan berubah status menjadi **BELUM AKTIF** dengan tombol **"Aktifkan"**.
8. Ketuk **"Aktifkan"**, cari **Agent GoSosmed**, lalu aktifkan sakelarnya.

## Langkah 2 — Izin "tampil di atas aplikasi lain" (WAJIB)

Ini yang membuat aplikasi bisa membuka aplikasi lain dari latar belakang.
Sejak Android 10, tanpa izin ini Android **diam-diam menolak** pembukaan
aplikasi — tanpa pesan error apa pun.

1. Masih di tab **Setup**, cari kartu izin tampilan.
2. Tekan **Aktifkan**.
3. Android membuka daftar aplikasi. Cari **GoSosmed Agent**.
4. Aktifkan tombolnya. Android menampilkan peringatan — tekan **Izinkan**.
5. Kembali ke aplikasi. Statusnya sekarang harus **AKTIF**.

> **Khusus HP Xiaomi / Redmi / POCO (MIUI / HyperOS):** ada satu izin lagi.
> Buka **Pengaturan > Aplikasi > GoSosmed Agent**, lalu:
> - **Izin lainnya** → aktifkan **"Tampilkan jendela pop-up saat berjalan di latar belakang"**
> - **Autostart** → aktifkan
> - **Penghemat baterai** → pilih **Tanpa batasan**
>
> Tanpa ketiga ini, MIUI akan mematikan aplikasi saat layar mati dan otomasi
> berhenti tanpa pemberitahuan.

Setelah langkah 1 dan 2, Anda **sudah bisa memakai aplikasi**. Lanjutkan ke
langkah 3 hanya bila ada aplikasi yang gagal dibuka otomatis.

---

## Langkah 3 — Otomasi Lanjutan / ADB (OPSIONAL)

### Kenapa ini ada

Beberapa aplikasi (terutama TikTok) menolak dibuka dengan cara biasa. Untuk
membukanya dengan andal, GoSosmed perlu hak khusus yang setara dengan perintah
`adb shell` — hak yang sama yang dipakai alat pengembang Android.

Hak itu hanya bisa didapat lewat **Debug nirkabel** bawaan Android. Tidak ada
cara lain, dan ini bukan kekurangan aplikasi kita.

### Yang perlu Anda tahu dulu

- Ini **koneksi di dalam HP Anda sendiri**, bukan ke server GoSosmed.
  Tidak ada data yang dikirim ke luar karena langkah ini.
- **Harus diulang setiap HP reboot.** Android otomatis mematikan Debug
  nirkabel saat HP dinyalakan ulang. Ini perilaku Android, bukan aplikasi kita.
- Bila tidak dilakukan, aplikasi tetap berfungsi — hanya dengan kemampuan
  terbatas, dan UI akan mengatakannya secara jujur.

### Cara mengaktifkan Opsi Pengembang (sekali saja)

Jika menu **Opsi Pengembang** belum muncul di Pengaturan HP Anda, aktifkan dengan mengetuk **Nomor Build** sebanyak 7 kali berturut-turut. Letak menu berbeda tergantung merek HP Anda:

* **Xiaomi / Redmi / POCO (MIUI / HyperOS):**
  1. Buka **Setelan > Tentang telepon** (About phone).
  2. Ketuk baris **"Versi OS"** (HyperOS) atau **"Versi MIUI"** (MIUI) sebanyak **7 kali** dengan cepat hingga muncul pesan *"Sekarang Anda adalah seorang developer!"*.
  3. Lokasi menu: Kembali ke Setelan utama → **Setelan tambahan > Opsi pengembang**.
  4. *Catatan penting Xiaomi:* Di dalam Opsi Pengembang, selain mengaktifkan **Debug nirkabel**, pastikan juga mengaktifkan **"Debugging USB (Setelan keamanan)"** jika perangkat memintanya.

* **Samsung (One UI):**
  1. Buka **Pengaturan > Tentang ponsel > Informasi perangkat lunak** (About phone > Software information).
  2. Ketuk baris **"Nomor versi"** (Build number) sebanyak **7 kali**. Masukkan PIN/pola kunci layar jika diminta.
  3. Lokasi menu: Kembali ke Pengaturan utama → geser ke paling bawah → **Pilihan pengembang** (Developer options).

* **OPPO / Realme (ColorOS / Realme UI):**
  1. Buka **Pengaturan > Tentang perangkat > Versi** (About device > Version).
  2. Ketuk **"Nomor kompilasi"** atau **"Nomor versi"** sebanyak **7 kali**.
  3. Lokasi menu: Kembali ke Pengaturan utama → **Pengaturan tambahan** (atau **Setelan sistem**) → **Opsi pengembang**.

* **Vivo / iQOO (Funtouch OS / OriginOS):**
  1. Buka **Pengaturan > Tentang ponsel > Informasi perangkat lunak**.
  2. Ketuk **"Nomor versi"** (Build number) sebanyak **7 kali**.
  3. Lokasi menu: Kembali ke Pengaturan utama → **Sistem > Opsi developer**.

* **Google Pixel / Motorola / Android Murni (AOSP):**
  1. Buka **Setelan > Tentang ponsel**.
  2. Geser ke bagian paling bawah, ketuk **"Nomor build"** (Build number) sebanyak **7 kali**.
  3. Lokasi menu: Kembali ke Setelan utama → **Sistem > Opsi developer**.

### Cara melakukan pairing (v0.9.6 — cara yang benar)

> **Kunci suksesnya:** JANGAN menutup layar kode pairing. Kode 6 angka itu
> sekali pakai dan akan berganti begitu layar itu ditutup. Karena itu cara di
> bawah ini memakai **baris notifikasi** untuk mengetik kodenya, sehingga
> layar kode tetap terbuka dan kodenya tidak berubah.

1. Di app GoSosmed Agent, tab **Setup** → tekan **Hubungkan** pada kartu
   Otomasi Lanjutan (ADB).
2. Baca panduan 3 langkah yang muncul, lalu tekan **"Mengerti, buka Setelan"**.
   HP otomatis membuka layar **Debug nirkabel**, dan sebuah **notifikasi**
   muncul di panel notifikasi.
3. Di layar Debug nirkabel: **AKTIFKAN** Debug nirkabel, lalu tekan
   **"Pasangkan perangkat dengan kode pairing"**. Layar kode 6 angka muncul —
   **BIARKAN TERBUKA**.
4. **Tarik panel notifikasi** dari atas layar (layar kode tetap terbuka di
   belakangnya). Pada notifikasi **"⚡ Pairing ADB GoSosmed"**, ketuk baris
   **"Ketik Kode Pairing"** dan masukkan 6 angka tadi.
5. Notifikasi akan berubah menjadi **"✓ Selesai"**. Pairing berhasil dan
   penyambungan berjalan otomatis di belakang.

**Tentang IP dan port:** Anda **TIDAK perlu** mengetik alamat IP. Pairing ini
memasangkan HP dengan dirinya sendiri, jadi alamatnya selalu `127.0.0.1`
(loopback) — itu memang benar dan disengaja. **Port** ditemukan otomatis lewat
penemuan mDNS. Setelah pairing, penyambungan juga memakai mDNS, sehingga IP
Wi-Fi tidak perlu diketahui sama sekali.

> Ada kotak melayang (floating window) yang muncul sebagai jalur alternatif
> bila izin overlay Anda aktif. Itu **bonus** — jalur notifikasi di atas selalu
> bisa dipakai walau kotak melayang diblokir oleh MIUI/HyperOS.

> **Kode pairing hanya berlaku 10 menit.** Bila kedaluwarsa, buat kode baru
> dari layar Debug nirkabel.

### Setelah HP reboot

Ulangi langkah 3 saja (nyalakan Debug nirkabel lalu pairing). Langkah 1 dan 2
tidak perlu diulang. Bila status kartu ADB mengatakan **"terputus setelah HP
restart"**, itu memang keadaannya — cukup hubungkan ulang dengan cara di atas.

---

## Kalau ada masalah

| Gejala | Penyebab paling mungkin | Yang harus dilakukan |
|---|---|---|
| Status aksesibilitas "DIBLOKIR ANDROID ⚠️" atau abu-abu | Android 13/14 memblokir aplikasi sideload (Restricted Settings) | Di tab Setup, tekan **"Cara Mengizinkan"** → ketuk menu **⋮** di Info Aplikasi → pilih **"Izinkan setelan yang dibatasi"** |
| Status aksesibilitas tidak mau AKTIF | Android mematikan layanan | Buka lagi Layanan Aksesibilitas, aktifkan ulang |
| Status aksesibilitas "BELUM AKTIF" padahal sudah diaktifkan | *Bug v0.9.5, sudah diperbaiki di v0.9.6.* Bila masih terjadi, tutup lalu buka lagi app | Perbarui ke v0.9.6 atau lebih baru |
| Aplikasi sosial tidak mau terbuka | Izin overlay belum aktif | Ulangi Langkah 2 |
| Muncul "Adb not paired" | Debug nirkabel mati (biasanya setelah reboot) | Ulangi Langkah 3 |
| Notifikasi pairing tidak muncul | Izin Notifikasi belum diberikan | Tab Setup → baris Notifikasi → **Izinkan** |
| Muncul "Kode pairing salah/kedaluwarsa" | Kode sudah lewat 10 menit, atau layar kode sempat ditutup | Buat kode baru, lalu ketik lewat notifikasi **tanpa** menutup layar kode |
| Muncul "Port pairing belum terdeteksi" | Debug nirkabel belum aktif saat kode diketik | Aktifkan Debug nirkabel dulu, lalu coba lagi |
| Kotak melayang tidak muncul | MIUI/HyperOS memblokir jendela latar belakang | Tidak masalah — pakai notifikasi; atau aktifkan "Tampilkan jendela pop-up saat berjalan di latar belakang" |
| Muncul "Koneksi otomasi terputus" | Debug nirkabel dimatikan sistem | Nyalakan lagi lalu pairing |
| Otomasi berhenti sendiri setelah beberapa menit | Penghemat baterai mematikan aplikasi | Atur "Tanpa batasan" (lihat catatan MIUI) |
| Semua berhasil tapi profil terbaca salah | Aplikasi sosial login sebagai akun berbeda | Periksa akun mana yang login di aplikasi itu |

---

## Yang TIDAK perlu Anda lakukan lagi

- ❌ Memasang aplikasi **Shizuku** — sudah tidak dipakai sejak 0.9.0.
- ❌ Memasang aplikasi tambahan apa pun. Satu APK saja.
- ❌ Menghubungkan HP ke komputer.
- ❌ Mengetik alamat server.
