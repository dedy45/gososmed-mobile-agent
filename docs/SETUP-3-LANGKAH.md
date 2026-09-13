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

### Cara melakukan pairing

1. **Pengaturan > Sistem > Opsi Pengembang > Debug nirkabel** → **AKTIFKAN**.
   Setujui peringatan.
2. Di layar yang sama, tekan **"Pair perangkat dengan kode pairing"**.
   Android menampilkan tiga hal: **alamat IP**, **port**, dan **kode 6 angka**.
3. Biarkan layar itu terbuka. Buka **GoSosmed Agent** (bisa lewat layar
   terbagi / split screen bila HP Anda mendukung).
4. Di tab **Setup**, tekan **Pairing**, lalu masukkan kode 6 angka.
   Bila port tidak terdeteksi otomatis, masukkan juga alamat IP dan port.
5. Setelah berhasil, status menjadi **Tersambung**.

> **Kode pairing hanya berlaku 10 menit.** Bila kedaluwarsa, buat kode baru
> dari layar Debug nirkabel.

### Setelah HP reboot

Ulangi langkah 3 saja (nyalakan Debug nirkabel lalu pairing). Langkah 1 dan 2
tidak perlu diulang.

---

## Kalau ada masalah

| Gejala | Penyebab paling mungkin | Yang harus dilakukan |
|---|---|---|
| Status aksesibilitas tidak mau AKTIF | Android mematikan layanan | Buka lagi Layanan Aksesibilitas, aktifkan ulang |
| Aplikasi sosial tidak mau terbuka | Izin overlay belum aktif | Ulangi Langkah 2 |
| Muncul "Adb not paired" | Debug nirkabel mati (biasanya setelah reboot) | Ulangi Langkah 3 |
| Muncul "Kode pairing salah/kedaluwarsa" | Kode sudah lewat 10 menit | Buat kode baru di layar Debug nirkabel |
| Muncul "Koneksi otomasi terputus" | Debug nirkabel dimatikan sistem | Nyalakan lagi lalu pairing |
| Otomasi berhenti sendiri setelah beberapa menit | Penghemat baterai mematikan aplikasi | Atur "Tanpa batasan" (lihat catatan MIUI) |
| Semua berhasil tapi profil terbaca salah | Aplikasi sosial login sebagai akun berbeda | Periksa akun mana yang login di aplikasi itu |

---

## Yang TIDAK perlu Anda lakukan lagi

- ❌ Memasang aplikasi **Shizuku** — sudah tidak dipakai sejak 0.9.0.
- ❌ Memasang aplikasi tambahan apa pun. Satu APK saja.
- ❌ Menghubungkan HP ke komputer.
- ❌ Mengetik alamat server.
