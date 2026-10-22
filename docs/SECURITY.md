# Model Keamanan & Batas Kepercayaan (Threat Model)

Dokumen ini menjelaskan batas keamanan untuk **GoSosmed Mobile Agent (BYOD)**,
khususnya server kontrol lokal (port 8765/8766) dan interaksi browser.

---

## 1. Prinsip Dasar: Kerckhoffs's Principle

> *Sebuah sistem kriptografi/keamanan harus tetap aman meskipun seluruh cara
> kerjanya diketahui oleh publik — satu-satunya yang dirahasiakan adalah kunci.*

Repositori ini **publik** karena meminta **AccessibilityService** — izin
terkuat di Android yang bisa membaca layar mana pun dan bertindak atas nama
pengguna. Meminta kepercayaan sebesar itu dengan binary tertutup tidak dapat
diterima (`AGENTS.md` §1).

Konsekuensi dari kode publik:
- **HTML, JavaScript, CSS, dan endpoint URL TIDAK BOLEH dianggap rahasia.**
- Siapa pun di internet bisa membaca kode `LocalWebServer.kt`, `index.html`,
  `control.js`, dan tahu persis perintah apa saja yang diterima (`t:`, `s:`,
  `key:`, `text:`, dll.).
- **Mengaburkan (obfuscate) atau meminify JavaScript BUKAN solusi keamanan.**
  Penyerang di jaringan yang sama cukup membaca lalu lintas jaringan atau
  mengunduh berkas `.js` yang disajikan oleh HP.
- **Keamanan HARUS ditegakkan di sisi SERVER (HP Android)**, bukan di sisi klien.

---

## 2. Arsitektur Komparasi: uiauto.dev vs GoSosmed

| Dimensi | `uiauto2.devsleep.com/demo/12345` | GoSosmed Mobile Agent |
|---|---|---|
| **Penyedia UI** | Server publik (Cloudflare/CDN) | HP Android sendiri (port 8765) |
| **Penyedia Layanan (Backend)** | Client desktop lokal (`pip install uiautodev`) di `127.0.0.1:20242` | `LocalWebServer.kt` di HP Android |
| **Model Jaringan** | Browser membuka halaman publik → JS menyambung ke `localhost:20242` | Browser PC membuka langsung IP HP di LAN Wi-Fi |
| **Keterbukaan Kode** | Bundle JS publik di web, backend Python open-source di PyPI | Full repo open-source di GitHub |
| **Batas Rahasia** | Client lokal default hanya mendengar di `127.0.0.1` | PIN 6 digit per-sesi di layar HP |

`uiauto.dev` aman karena backend-nya hanya mendengar di `127.0.0.1` komputer pengguna,
sehingga perangkat lain di Wi-Fi tidak bisa mengaksesnya. Halaman publiknya
hanyalah cangkang UI statis tanpa rahasia.

---

## 3. Pertahanan Berlapis GoSosmed (LocalWebServer Hardening)

Mulai versi `1.2.3-dev.1`, server lokal HP dilindungi 5 lapis:

```
[ Jaringan Wi-Fi ]
       │
       ▼
 [1] Bind ke Interface Wi-Fi Saja (bukan 0.0.0.0 — tolak mobile/VPN)
       │
       ▼
 [2] Gate Aktivasi: Server HANYA menyala saat switch Remote Controller ON
       │
       ▼
 [3] Autentikasi PIN 6 Digit (SecureRandom per-sesi)
     - Salah 8× berturut-turut → lockout HTTP 429
     - Benar → Token sesi 192-bit acak via HttpOnly Cookie (12 jam)
       │
       ▼
 [4] Validasi Origin pada WebSocket (Cegah serangan DNS Rebinding)
       │
       ▼
 [5] Batas Maksimal 4 Klien Bersamaan (Cegah DoS memori/CPU)
```

### 3.1 Mengapa PIN 6 Digit Aman Meskipun Kode Publik?
- PIN diacak setiap kali Remote Controller dinyalakan (`SecureRandom.nextInt(1_000_000)`).
- PIN **hanya tampil di layar fisik HP**, tidak pernah dikirim lewat jaringan
  sebelum pengguna mengetikkannya.
- Penyerang yang membaca kode publik di GitHub tetap menghadapi ruang kombinasi
  1.000.000 kemungkinan dengan batas maksimal 8 percobaan sebelum lockout.
- Token sesi yang diterbitkan berukuran 24 byte (192-bit entropi) bertipe `SameSite=Strict; HttpOnly`.

### 3.2 Mengapa `Origin` Harus Divalidasi?
Serangan *DNS Rebinding / Cross-Site WebSocket Hijacking*: korban membuka situs
web berbahaya di PC, situs tersebut menjalankan JavaScript yang mencoba
menyambung ke `ws://192.168.1.125:8765/ws`. Karena browser menyertakan header
`Origin: https://evil.com`, server langsung menolak dengan `403 Forbidden`.

---

## 4. Cara Aman Menggunakan Mode Publik

1. **Gunakan di Wi-Fi Tepercaya**: Hindari menyalakan Remote Controller di Wi-Fi
   publik (kafe, bandara, hotel). Bila terpaksa, gunakan USB tethering atau hotspot pribadi.
2. **Nyalakan Hanya Saat Dibutuhkan**: Matikan switch Remote Controller setelah
   selesai dipakai. Server akan berhenti seketika.
3. **Jangan Bagikan PIN**: PIN di layar HP adalah kunci kendali penuh atas perangkat.
4. **Periksa Notifikasi Berjalan**: Saat streaming aktif, Android menampilkan
   notifikasi persisten `GoSosmed Remote Stream` di bar status.
