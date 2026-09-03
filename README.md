# GoSosmed Mobile Agent

**Agent Android untuk GoSosmed BYOD — HP Anda sendiri yang mengeksekusi otomasi.**

Aplikasi ini menghubungkan HP Android milik Anda ke server otomasi GoSosmed **tanpa PC di
tengah, tanpa `adb`, dan tanpa root**. Ia menggantikan model "sewa HP di data center" yang
biasa dipakai layanan sejenis.

[![Dokumentasi](https://img.shields.io/badge/docs-gososmed--docs.pages.dev-4f46e5)](https://gososmed-docs.pages.dev/agent/ikhtisar/)

---

## Kenapa repo ini terbuka

Agent ini berjalan di perangkat pribadi Anda dan meminta **Accessibility Service** — izin
paling kuat di Android, yang secara teknis mampu membaca isi layar aplikasi yang sedang aktif
dan bertindak atas nama Anda.

Meminta kepercayaan sebesar itu untuk sebuah biner yang tidak bisa Anda periksa adalah
permintaan yang tidak pantas. Karena itu **komponen yang menyentuh perangkat Anda dibuka**,
supaya Anda bisa memverifikasi sendiri:

- perintah apa saja yang bisa diterima agent,
- data apa yang dikirim ke server, dan seberapa sering,
- ke mana ia terhubung,
- apa yang disimpan permanen di perangkat Anda.

Dan kalau tetap tidak yakin — [bangun APK-nya sendiri](#membangun-dari-sumber) dari kode yang
Anda baca.

### Batas keterbukaan, apa adanya

| Komponen | Status | Lokasi |
|---|---|---|
| **Agent Android (repo ini)** | **Terbuka** | Repo ini |
| Dokumentasi publik | Terbuka | [gososmed-docs.pages.dev](https://gososmed-docs.pages.dev) |
| Server / API (Go) | Tertutup | Repo privat |
| Dasbor web (Svelte) | Tertutup | Repo privat |
| Adapter platform | Tertutup | Repo privat |

GoSosmed **bukan** proyek sepenuhnya terbuka, dan kami tidak akan menyebutnya begitu. Server
memuat logika bisnis dan model berlangganan — di situlah nilai produknya. Alasannya komersial,
bukan alasan keamanan: kalau sebuah sistem hanya aman karena kodenya tersembunyi, sistem itu
memang tidak aman.

Penjelasan lengkap: **[Apa yang Publik & Apa yang Tidak](https://gososmed-docs.pages.dev/referensi/keterbukaan/)**

---

## Cara kerja

```
HP Anda:  [Aplikasi sosial media]  +  [Agent ini]
              │
              │  koneksi KELUAR (WSS) · heartbeat · auto-reconnect
              ▼
Server:   GoSosmed  →  job publikasi  →  adapter platform
```

Tiga hal yang membedakan desain ini:

1. **Koneksinya keluar, bukan masuk.** Agent yang menghubungi server. HP Anda tidak perlu IP
   publik, tidak perlu port terbuka, dan tidak berada di jaringan yang sama dengan server.
   Tidak ada pintu masuk baru ke perangkat Anda.
2. **Tanpa root, tanpa PC perantara.** Semua eksekusi lewat Accessibility Service bawaan
   Android.
3. **Perintahnya tertutup, bukan remote-control bebas.** Daftar lengkapnya di
   [tabel di bawah](#perintah-yang-diterima).

### Kompatibilitas hierarki layar

Agent menghasilkan XML hierarki layar dengan format **sama persis** seperti keluaran
`uiautomator dump`. Ini keputusan desain yang diambil sengaja: sisi server dapat memakai
parser yang sudah ada dan teruji tanpa mengubah adapter login/verify/publish sama sekali.

> Kalau Anda memodifikasi serializer hierarki, **jaga format keluarannya**. Mengubahnya akan
> memutus sisi server.

### Perintah yang diterima

| Perintah | Fungsi |
|---|---|
| Baca struktur layar | Mengambil hierarki elemen layar aktif untuk menemukan target |
| Ketuk | Menekan elemen pada simpul tertentu |
| Tulis teks | Mengisi kolom teks, mis. judul atau caption |
| Buka aplikasi | Menjalankan aplikasi target |
| Tindakan global | Kembali, home, layar terakhir |
| Tangkapan layar | Bukti visual untuk audit dan diagnosis kegagalan |

Tidak ada perintah "kirim seluruh isi layar terus-menerus", dan tidak ada sesi remote-control
bebas. **Verifikasi klaim ini di kode**, jangan percaya tabel ini saja.

---

## Peringatan kepatuhan — baca sebelum distribusi

Agent ini memakai **Accessibility Service untuk otomasi**. Kebijakan **Google Play publik
melarang** kategori ini, dan aplikasi yang melanggarnya bisa di-*ban*.

| Jalur distribusi | Boleh? |
|---|---|
| Play Store — production track | **TIDAK.** Melanggar kebijakan. |
| Play Internal / Closed Testing (privat) | Ya |
| Side-load APK langsung | Ya |

Ini pembatasan kebijakan, bukan kekurangan teknis. Disebutkan terang-terangan supaya tidak ada
yang menunggu kehadiran di Play Store yang tidak akan datang.

---

## Membangun dari sumber

### Cara 1 — GitHub Actions (disarankan)

Tidak menuntut Android SDK di mesin Anda.

```bash
# Jalankan build
gh workflow run build-apk --repo dedy45/gososmed-mobile-agent

# Lihat status
gh run list --repo dedy45/gososmed-mobile-agent --limit 5

# Unduh hasilnya
gh run download <run-id> -n gososmed-agent-debug
```

Kalau Anda mem-*fork* repo ini, ganti `--repo` dengan akun Anda.

### Cara 2 — Gradle lokal

Butuh Android SDK dan JDK 17.

```bash
git clone https://github.com/dedy45/gososmed-mobile-agent.git
cd gososmed-mobile-agent

./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease
```

Di Windows pakai `gradlew.bat`.

> **Soal penandatanganan.** Build Anda memakai kunci debug Android atau kunci Anda sendiri —
> bukan kunci rilis kami, dan itu memang seharusnya begitu. Konsekuensi praktisnya: APK
> bangunan Anda tidak bisa menimpa (update) APK rilis kami, karena Android menolak update dari
> penanda tangan berbeda. Hapus versi lama sebelum memasang bangunan Anda.

---

## Memasang & memasangkan

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Atau pindahkan APK ke HP dan buka seperti biasa. Lalu:

1. **Setelan → Aksesibilitas → GoSosmed Agent** → aktifkan.
   Android sengaja mewajibkan aktivasi manual oleh pemilik perangkat; tidak ada aplikasi yang
   boleh mengaktifkannya sendiri.
2. Minta **kode pairing** dari dasbor GoSosmed, masukkan di aplikasi agent. Agent lalu
   menyimpan `device_id` permanen — tidak perlu dipasangkan ulang setiap kali.
3. **Kecualikan agent dari optimasi baterai.** Jangan lewati langkah ini.

> **Optimasi baterai adalah penyebab kegagalan nomor satu.** Xiaomi, Oppo, Vivo, Realme,
> Samsung, dan Huawei punya lapisan pembatas latar belakang di luar setelan standar Android.
> Panduan per merek:
> [Pemecahan Masalah](https://gososmed-docs.pages.dev/agent/pemecahan-masalah/)

Panduan lengkap: [Memasang & Memasangkan](https://gososmed-docs.pages.dev/agent/pasang/)

---

## Privasi

**Yang TIDAK dilakukan agent:**

- Tidak meminta kata sandi akun sosial media Anda — Anda login sendiri di aplikasi masing-masing.
- Tidak mengirim kredensial akun sosial media Anda ke server GoSosmed; sesi login tetap di HP Anda.
- Tidak membaca SMS, kontak, riwayat panggilan, atau berkas pribadi — izin itu tidak diminta.
- Tidak membuka port masuk di HP Anda.

**Yang dikirim ke server saat job berjalan:** identitas perangkat (`device_id`) dan heartbeat,
struktur elemen layar **aplikasi target**, serta hasil eksekusi termasuk tangkapan layar bila
diperlukan untuk audit.

Struktur layar aplikasi target dapat memuat teks yang tampil di layar itu. Kalau Anda
menjalankan job pada akun yang menampilkan informasi sensitif, informasi itu ikut terbaca dalam
konteks job tersebut. Ini konsekuensi wajar dari cara kerjanya, dan lebih baik Anda tahu
sekarang.

Rincian: [Izin & Privasi](https://gososmed-docs.pages.dev/agent/izin-privasi/)

### Mencabut izin

Matikan di **Setelan → Aksesibilitas**, hapus aplikasinya, atau cabut perangkat dari dasbor.
Agent langsung berhenti mengeksekusi apa pun. Tidak perlu izin dari kami.

---

## Struktur proyek

```
app/                    modul aplikasi (sumber + aturan ProGuard)
build.gradle.kts        konfigurasi build root
settings.gradle.kts     definisi modul
gradle.properties       properti build
.github/workflows/      pipeline build APK
```

### Titik yang paling layak diaudit

1. **Penangan perintah** — daftar lengkap perintah yang diterima. Bandingkan dengan
   [tabel di atas](#perintah-yang-diterima); jangan percaya tabel kami kalau kodenya berbeda.
2. **Klien WebSocket** — tujuan koneksi dan isi tiap heartbeat.
3. **Serializer hierarki layar** — apa yang diekstrak dari layar aplikasi target.
4. **Penyimpanan lokal** — apa yang disimpan permanen (`device_id`, konfigurasi pairing).

---

## Status

Fase P0 — fondasi berjalan, validasi perangkat nyata masih berlangsung.

- [x] Proyek Android + AccessibilityService
- [x] Serializer hierarki (XML kompatibel `uiautomator dump`)
- [x] Eksekusi tap / text / global tanpa root
- [x] Klien WebSocket outbound (auto-reconnect + heartbeat)
- [x] Pairing code + `device_id` persisten
- [x] `dumpWindows` (getWindows) + `takeScreenshot`
- [x] GitHub Actions build → APK artifact
- [ ] Validasi menyeluruh di perangkat nyata lintas merek
- [ ] Integrasi agenthub sisi server (P1)

**Jangan gambarkan ini lebih matang daripada kenyataannya.** Cakupan pengujian lintas merek
dan versi Android masih terbatas.

---

## Kontribusi

Issue dan pull request diterima. Yang paling berguna:

- **Laporan kompatibilitas perangkat** — terutama Xiaomi, Oppo, Vivo, Realme, dan Samsung
- Perbaikan keandalan reconnect pada jaringan tidak stabil
- Temuan keamanan

Saat membuka issue, sertakan merek/model HP, versi Android, versi agent, yang Anda harapkan,
dan yang sebenarnya terjadi.

> **Jangan sertakan** kode pairing, token, kredensial, atau tangkapan layar yang memuat isi
> akun pribadi. Issue di repo ini terbuka untuk umum.

Server dan dasbor GoSosmed berada di repo privat, jadi PR untuk sisi itu tidak bisa diterima
lewat repo ini.

### Melaporkan masalah keamanan

Temuan pada agent — komponen yang berjalan di perangkat Anda — silakan laporkan lewat issue.
Untuk temuan yang menyangkut sisi server, laporkan secara privat lebih dulu dan beri waktu
perbaikan sebelum dipublikasikan.

---

## Lisensi

**Belum ditetapkan.** Repo ini belum memuat berkas `LICENSE`, yang secara hukum berarti
*all rights reserved* — kode dapat dibaca dan diaudit, tetapi tidak ada izin eksplisit untuk
memakai, memodifikasi, atau mendistribusikan ulang.

Kalau tujuan repo ini adalah transparansi yang bisa diverifikasi, menambahkan lisensi eksplisit
adalah langkah berikutnya yang perlu diputuskan pemilik proyek.

---

## Tautan

| | |
|---|---|
| Dokumentasi publik | [gososmed-docs.pages.dev](https://gososmed-docs.pages.dev) |
| Apa itu agent | [/agent/ikhtisar/](https://gososmed-docs.pages.dev/agent/ikhtisar/) |
| Izin & privasi | [/agent/izin-privasi/](https://gososmed-docs.pages.dev/agent/izin-privasi/) |
| Pemecahan masalah | [/agent/pemecahan-masalah/](https://gososmed-docs.pages.dev/agent/pemecahan-masalah/) |
| Konteks untuk agent LLM | [/referensi/untuk-llm/](https://gososmed-docs.pages.dev/referensi/untuk-llm/) · [`llms.txt`](https://gososmed-docs.pages.dev/llms.txt) |
