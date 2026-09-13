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
3. Pada kartu "Akses otomatisasi (Aksesibilitas)", tekan **Aktifkan**.
4. Android membuka halaman Layanan Aksesibilitas. Cari **GoSosmed Agent
   Controller**, lalu aktifkan.
5. Android menampilkan peringatan. Tekan **Izinkan / OK**.
6. Kembali ke aplikasi. Statusnya sekarang harus **AKTIF**.

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

Kalau menu **Opsi Pengembang** belum ada di HP Anda:

1. **Pengaturan > Tentang HP**
2. Cari **Nomor build** (atau "Versi MIUI" di Xiaomi).
3. **Ketuk 7 kali.** Android berkata "Anda sekarang adalah pengembang".
4. Kembali ke Pengaturan utama. Menu **Opsi Pengembang** sekarang ada
   (biasanya di **Pengaturan > Sistem**, atau **Pengaturan > Setelan tambahan**).

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
