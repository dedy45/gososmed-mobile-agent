# Analisis: Mirror + Kontrol Android via Browser Tanpa Install di Windows

> **Tanggal:** 2026-10-21
> **Konteks:** Alternatif WebMirror (`com.webmirror.lan`) dengan syarat keras: **kontrol penuh lewat browser, nol instalasi di Windows**.
> **Repo acuan:** `gososmed-mobile-agent` v1.2.2 (minSdk 26, targetSdk 34, Accessibility + ADB lokal UID 2000).
> **Status:** Analisis opsi. Belum ada perubahan kode.

---

## 1. Kesimpulan di atas

Batasan yang diminta membentuk **trilemma** yang tidak bisa dipatahkan dengan software apa pun:

```
        WIRELESS (tanpa kabel)
                 /\
                /  \
               /    \
   ZERO HOST  /______\  KONTROL (tap/ketik)
   (tak ada server)
```

Pilih dua, jangan tiga:

| Kalau mau… | Konsekuensi | Opsi |
|---|---|---|
| Wireless + kontrol + tanpa host | Harus ada **app di HP yang menyajikan HTTP sendiri** | WebMirror, atau tambah server inbound ke APK GoSosmed (**melanggar A2**) |
| Kontrol + tanpa host + nol install Windows | **Wajib kabel USB** | Tango Web (WebUSB) |
| Wireless + kontrol + nol install Windows | **Wajib host lain** (PC/Ubuntu/backend) | Backend WSS GoSosmed (opsi B), ws-scrcpy di host lain |

**Sebab teknisnya:** browser tidak bisa membuka TCP socket ke `adbd` di HP. Tidak ada API browser untuk itu (WebUSB hanya USB, WebSocket butuh server, WebRTC butuh signaling). Jadi "nol install di Windows" selalu berarti *ada pihak lain yang menyajikan HTTP/WS* — entah HP-nya sendiri, backend cloud, atau mesin lain di LAN.

**Rekomendasi:**

1. **Harian, cepat, benar-benar nol install → Tango Web + kabel USB.** Paling ringan dan paling cepat yang tersedia hari ini tanpa menulis kode.
2. **Operasional 24/7 tanpa kabel → web viewer di atas WSS GoSosmed yang sudah ada (opsi B).** Ini satu-satunya jalur yang sesuai keputusan arsitektur A1/A2 dan tidak menambah hop baru. Butuh kerja lintas repo (APK + backend Go), bukan repo ini saja.
3. **Jangan** jadikan ws-scrcpy jalur produksi (tanpa auth, tanpa TLS, listen semua interface).

---

## 2. Fakta teknis yang menentukan

### 2.1 Browser tidak bisa bicara ADB langsung

| Transport | Bisa di browser? | Catatan |
|---|---|---|
| WebUSB | ✅ Chrome/Edge desktop saja | Butuh kabel. Firefox/Safari tidak. |
| WebSocket ke `adbd` | ❌ | `adbd` bicara protokol biner di TCP, bukan WS |
| ADB over Wi-Fi dari browser | ❌ | Docs Tango eksplisit: "Web + ADB over Wi-Fi = requires a native bridge program" |
| WebRTC | ⚠️ | Butuh signaling server + STUN/TURN |

Artinya: **wireless + browser selalu butuh server di suatu tempat.**

### 2.2 Throttle `AccessibilityService.takeScreenshot()`

APK sudah memakai `takeScreenshot` (API 30+) di `AgentAccessibilityService.kt:727` dan `:869`, dan sudah memetakan error `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` (kode 3) di baris 752 dan 894. Kode itu berarti **permintaan terlalu rapat** — sistem menolak frame yang datang terlalu cepat.

Konsekuensi untuk streaming: jalur ini **bukan** untuk 30 fps. Praktis aman di **5–10 fps** dengan jeda 100–200 ms antar frame. Kalau butuh 30 fps, satu-satunya jalan adalah MediaProjection + MediaCodec (M8 di plan), yang butuh consent ulang per sesi.

Tambahan: `minSdk = 26` sementara `takeScreenshot` butuh API 30. Di Android 8–10 harus fallback ke `screencap` lewat ADB lokal UID 2000 (3–6 fps).

### 2.3 Efisiensi bandwidth: JPEG per-frame vs H.264

Hitungan dari spesifikasi device acuan (1220×2712, scale 0.5 → 610×1356, JPEG q70):

| Metrik | JPEG per frame (jalur APK sekarang) | H.264 (scrcpy/WebRTC) |
|---|---|---|
| Ukuran 1 frame | 60–120 KB | — |
| 10 fps | 0,6–1,2 MB/s (**4,8–9,6 Mbps**) | — |
| 30 fps | tidak realistis (throttle) | 4–8 Mbps total |
| Byte per detik gerakan | ~10× lebih boros | baseline |

Kesimpulan: **JPEG per frame 5–10× lebih boros** untuk tingkat gerakan yang sama. Untuk inspector/debug masih wajar; untuk "mirror yang enak dilihat" tidak.

---

## 3. Peta opsi

Semua angka performa di bawah adalah **estimasi arsitektural, belum diukur di device ini**. Kolom "Diukur?" sengaja ada supaya tidak diklaim sebagai hasil uji.

| # | Opsi | Install di Windows | Host tambahan | Kabel | Kontrol | FPS | Latensi LAN | Bandwidth | Kematangan | Diukur? |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | **Tango Web** (app.tangoapp.dev) | 0 | tidak | **ya** | ✅ tap/swipe/teks | 30–60 | 50–90 ms | 2–6 Mbps | beta v3.0.0-beta.4, 3.253★, MIT | ❌ |
| 2 | **Web viewer di atas WSS GoSosmed** | 0 | backend yang sudah ada | tidak | ✅ via command v2 | 5–12 (JPEG) / 25–30 (WebRTC, M8) | 120–300 ms (cloud) / 80–150 ms (LAN) | 3–9 Mbps JPEG | kode sendiri, sesuai A1/A2 | ❌ |
| 3 | **ws-scrcpy di host lain** (mis. Ubuntu 192.168.1.77) | 0 | Node + adb di host | tidak | ✅ | 30–60 | 60–120 ms | 2–8 Mbps | prototype, 2.582★, MIT | ❌ |
| 4 | **Termux + ws-scrcpy di HP** | 0 | tidak (tapi berat di HP) | tidak | ✅ | 15–30 | 50–100 ms | 2–8 Mbps | eksperimental, rapuh | ❌ |
| 5 | **AirDroid Cast Web** | 0 | cloud pihak ketiga | tidak | terbatas | 15–30 | 150–400 ms | 2–5 Mbps | komersial produksi, freemium | ❌ |
| 6 | **ScreenStream** (dkrivoruchko) | 0 | tidak | tidak | ❌ **view-only** | 10–25 (MJPEG) | 100–200 ms | 3–10 Mbps | produksi, 2.562★, MIT | ❌ |
| 7 | **Tambah HTTP server ke APK GoSosmed** | 0 | tidak | tidak | ✅ | 5–12 | 30–60 ms | 3–9 Mbps | **melanggar A2 & §16** | ❌ |

Nomor 7 sengaja dimasukkan supaya perbandingannya jujur: secara performa dia **paling rendah latensi** (tidak ada hop), tapi keputusan arsitektur repo ini sudah menolaknya secara eksplisit — `PLAN-DETERMINISTIC-ANDROID-PORTAL.md` A2: *"Tidak membuka HTTP/TCP server inbound pada HP"*, dan §16: *"Jangan membuka port inbound"*. Menambahkannya berarti membatalkan keputusan itu, bukan sekadar menambah fitur.

---

## 4. Analisis per opsi

### 4.1 Tango Web — paling ringan & cepat yang bisa dipakai hari ini

**Cara kerja:** Tango mengimplementasi ulang ADB client di TypeScript. Di browser dia memakai **WebUSB** → bicara langsung ke `adbd` di HP → push `scrcpy-server` → terima H.264 → decode di browser via **WebCodecs**.

- **Nol instalasi di Windows.** Tidak ada ADB, tidak ada Node, tidak ada driver khusus (Win10/11 umumnya sudah punya WCID descriptor untuk ADB interface; kalau device tidak punya, WebUSB tidak akan menampilkan device).
- **Nol app di HP.** Cukup aktifkan Developer options → USB debugging.
- **Paling cepat** karena video dikompres H.264 dan di-decode hardware di browser, bukan JPEG per frame.
- **Risiko:** label beta, API publik belum stabil ("The public API is not stable"), hanya Chromium desktop (WebUSB tidak ada di Firefox/Safari). Kontrol lewat scrcpy-server artinya bergantung pada versi server scrcpy yang didukung (repo baru menambah dukungan server 4.0/4.1).
- **Cocok untuk:** demo, debug cepat, kerja harian di meja dengan kabel.
- **Tidak cocok untuk:** operasional jarak jauh, multi-device, atau apa pun yang harus jalan tanpa disentuh.

### 4.2 Web viewer di atas WSS GoSosmed — jalur yang benar secara arsitektur

Ini bukan tool pihak ketiga; ini memakai pipa yang sudah ada.

**Yang sudah tersedia di repo (terverifikasi):**

| Komponen | File | Status |
|---|---|---|
| Screenshot + scale + JPEG quality | `AgentAccessibilityService.kt:727`, `:869` | ✅ ada |
| Binary frame encoder + bounded queue + drop-oldest | `DebugFrameEncoder.kt` (193 baris) | ✅ ada |
| Metadata/frame correlation + ACK | `AgentWsClient.kt:109–120`, `:251`, `:284` | ✅ ada |
| Command `tap`, `observe`, `actNode`, `actAndVerify` | `AgentCommand.kt` | ✅ ada |
| Frame stream kontinu (`startDebugFrames` loop) | — | ❌ **belum**; sekarang `frameSeq = 1L` (one-shot per command) |
| Endpoint browser + halaman viewer | — | ❌ di luar scope repo ini (plan §2: "Svelte Web UI Inspector" excluded) |

**Yang perlu ditambah:**

1. **APK:** loop frame kontinu di `AgentWsClient` — `frameSeq++`, jeda 120–250 ms (hormati throttle), drop-oldest saat antrean penuh (encoder sudah mendukung), auto-stop bila subscriber hilang atau `onPause`.
2. **Backend (repo lain):** endpoint WS `/portal/view` yang mem-fan-out frame binary + metadata ke browser, dan meneruskan input balik sebagai command v2 (`tap` dengan koordinat device, bukan koordinat frame).
3. **Browser:** ±200 baris. `Blob` → `createImageBitmap` → `canvas.drawImage`. Input: klik → `{cmd:"tap", x: x_frame * (deviceW/frameW), y: ...}`. Scroll → `swipe`. Teks → `replaceText` (sudah ada di APK).
4. **Guard:** satu sesi view per device, capability `view` dan `control` dipisah (sudah jadi keputusan §16), token per sesi yang bisa dicabut, stream mati saat sesi berakhir.

**Kenapa ini yang direkomendasikan untuk produksi:** tidak ada hop baru, tidak ada port inbound, tidak ada tool pihak ketiga, dan kontrol memakai jalur `actAndVerify` yang sudah punya jaminan "nol false success". Kelemahannya jujur: **5–12 fps**, bukan 30. Kalau 30 fps wajib, itu M8 (MediaProjection + WebRTC) dan biayanya jauh lebih besar.

### 4.3 ws-scrcpy di host lain — cepat, tapi jangan untuk produksi

Kalau yang dimaksud "host lain" adalah mesin yang sudah menyala 24/7 (mis. Ubuntu `192.168.1.77`), maka Windows tetap nol instalasi: cukup buka `http://192.168.1.77:8000`.

Tapi ada tiga hal yang harus disadari:

1. **Tanpa autentikasi, tanpa TLS, tanpa enkripsi.** README-nya sendiri: *"No authorization at any level"*, *"The scrcpy server listens on all network interfaces"*. Siapa pun di LAN bisa melihat dan mengendalikan HP.
2. **Butuh akses ADB ke HP dari host itu.** ADB lokal di APK GoSosmed **hanya loopback** (`AdbLocalShell.kt:130` `LOOPBACK_HOST = "127.0.0.1"`, `AdbPairingService.kt:57`), jadi host tidak bisa memakainya. Harus aktifkan Wireless debugging dan pairing dari host — artinya membuka `adbd` ke LAN, yang justru memperbesar attack surface.
3. **Server scrcpy tetap hidup setelah klien terputus** — sumber daya HP terpakai diam-diam.

Kesimpulan: layak sebagai alat debug di rumah, tidak layak jadi jalur operasional.

### 4.4 Termux + ws-scrcpy di HP — menarik, tapi rapuh

Secara teori ini memenuhi semuanya: Node jalan di HP, browser di Windows buka `http://ip-hp:8000`, nol instalasi di Windows, nol host.

Kenyataannya:
- Perlu install Termux + `nodejs-lts` + `android-tools` (~150–250 MB) dan build native (`node-gyp`) di HP.
- Butuh koneksi ADB dari Termux ke `adbd`. Port wireless debugging **dinamis**, dan kunci pairing disimpan di app GoSosmed (`AdbKeyStore.kt`), bukan di `~/.android/adbkey` milik Termux → harus pairing ulang manual.
- Baterai dan thermal naik signifikan karena encode + server jalan di HP yang sama yang sedang di-mirror.
- Tidak ada auto-start yang andal setelah reboot.

Untuk eksperimen boleh; untuk 24/7 tidak.

### 4.5 AirDroid Cast Web — produksi, tapi bukan "ringan"

Benar-benar nol instalasi di Windows (buka `webcast.airdroid.com`, masukkan kode). Tapi trafik lewat cloud pihak ketiga, ada iklan/batas free tier, dan latensinya paling buruk di daftar ini. Kalau syaratnya "terpercaya dan produksi" dan bukan "ringan", ini jawabannya. Kalau syaratnya "ringan dan cepat", bukan.

### 4.6 ScreenStream — paling ringan, tapi tidak bisa kontrol

`dkrivoruchko/ScreenStream` (MIT, 2.562★, commit 18 Okt 2026): app Android dengan HTTP server bawaan, mode MJPEG lokal, PIN 4–6 digit opsional, tanpa root, tanpa iklan di versi F-Droid.

Satu masalah fatal untuk kebutuhan ini: **view-only**. Tidak ada injeksi sentuh. Jadi hanya cocok kalau yang dibutuhkan sekadar "melihat layar dari browser".

---

## 5. Perbandingan langsung dengan WebMirror

| Kriteria | WebMirror | Tango Web | Opsi B (WSS sendiri) | ws-scrcpy | ScreenStream |
|---|---|---|---|---|---|
| Install di Windows | 0 | 0 | 0 | 0 | 0 |
| App di HP | ya | tidak | sudah ada | tidak | ya |
| Kabel | tidak | **ya** | tidak | tidak | tidak |
| Kontrol | ✅ | ✅ | ✅ | ✅ | ❌ |
| Batas gratis | HD 60 detik | tidak ada | tidak ada | tidak ada | tidak ada |
| Data keluar dari LAN | tidak | tidak | ya (cloud) atau tidak (LAN) | tidak | tidak |
| FPS realistis | 15–30 | 30–60 | 5–12 | 30–60 | 10–25 |
| Risiko keamanan | rendah (pairing 6 digit) | rendah (kabel) | sedang (token sesi) | **tinggi** (tanpa auth) | rendah |

---

## 6. Rekomendasi akhir

**Kalau tujuan Anda "hari ini bisa dipakai, tanpa install apa pun di Windows":**
→ Buka `https://app.tangoapp.dev` di Chrome, colok kabel, aktifkan USB debugging. Selesai. Ini yang paling ringan (nol proses di Windows) dan paling cepat (H.264 + WebCodecs).

**Kalau tujuan Anda "operasional GoSosmed bisa dipantau dan dikendalikan operator dari browser, tanpa kabel, 24/7":**
→ Opsi B. Dan itu berarti menerima 5–12 fps, atau menaikkan ke M8 (MediaProjection + WebRTC) kalau 30 fps benar-benar syarat produk.

**Yang sebaiknya dihindari:** ws-scrcpy sebagai jalur produksi (tanpa auth), dan menambah HTTP server inbound ke APK (membatalkan A2 dan menambah attack surface permanen).

**Satu keputusan yang perlu Anda ambil lebih dulu:** apakah "tanpa install di Windows" juga berarti "tanpa backend yang jalan"? Kalau backend cloud GoSosmed sudah menyala 24/7, opsi B gratis secara operasional. Kalau backend harus jalan di Windows yang sama, maka syarat "nol instalasi" sudah gugur di titik itu dan Tango+kabel jadi satu-satunya jawaban jujur.

---

## 7. Yang harus diukur sebelum memutuskan

Angka fps/latensi di dokumen ini estimasi. Sebelum commit ke salah satu opsi, ukur di device nyata:

1. **FPS nyata `takeScreenshot`**: loop 60 detik, catat berapa frame sukses vs `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`.
2. **Ukuran JPEG**: `takeScreenshotRawBytes(scale = 0.5f, format = "jpeg", quality = 70)` pada 3 layar berbeda (feed, form, galeri).
3. **RTT WSS**: dari `health` command yang sudah ada (sudah melaporkan RTT).
4. **CPU + suhu HP** saat stream 5 menit berturut-turut — pakai `DeviceTelemetryHelper` yang sudah ada.
5. **Latensi end-to-end** yang dirasakan: waktu dari klik di browser sampai frame berubah.

Tanpa lima angka itu, pilihan antara opsi B dan M8 hanya tebakan.

---

## 8. Syarat tambahan: server tidak menanggung beban decode (WebCodecs di browser)

### 8.1 Siapa menanggung apa

| Jalur | Encode | Relay | Decode | Server menanggung beban? |
|---|---|---|---|---|
| **Tango (WebUSB)** | MediaCodec di HP (scrcpy-server) | **tidak ada server sama sekali** | WebCodecs di browser | **Nol** — browser bicara langsung ke `adbd` |
| **WebMirror** | MediaCodec di HP | WebRTC langsung (LAN, tanpa relay) | pipeline native WebRTC di browser | **Nol** |
| **ws-scrcpy** | MediaCodec di HP | Node hanya proxy byte H.264 | MSE / WebCodecs / TinyH264 | Nol decode, tapi kena bandwidth + buffer RAM |
| **APK GoSosmed sekarang (JPEG)** | **CPU HP, JPEG q70** | WSS forward byte | `Image` decode biasa | Nol decode, tapi bandwidth 5–10× |
| **Opsi B + WebCodecs** | MediaProjection → MediaCodec H.264 | WSS forward byte (dumb pipe) | `VideoDecoder` di browser | **Nol** |

Kesimpulan: "server tidak menanggung beban" **bukan** soal WebCodecs vs WebRTC — itu soal **server tidak melakukan transcode**. Yang menentukan beban server cuma satu: apakah dia men-decode/re-encode atau hanya meneruskan byte.

### 8.2 Tango: ya, WebCodecs eksplisit

Bukti dari repo (`yume-chan/ya-webadb`, MIT):

- Paket `@yume-chan/scrcpy-decoder-webcodecs` v3.0.0-beta.4 — deskripsi resmi: *"Raw H.264 stream decoder and renderer using WebCodecs API (requires modern browser)."*
- Source `libraries/scrcpy-decoder-webcodecs/src/video/decoder.ts:179–180`: default `hardwareAcceleration = "no-preference"`, `optimizeForLatency = true`, lalu di baris 216–217 di-set ke chunk config `VideoDecoder`. Jadi latency mode memang dipakai, bukan cuma ada di teori.
- Fallback software: `@yume-chan/scrcpy-decoder-h264bsd` (WASM) — docs menyebut performanya *"much worse"*, jadi ini bukan jalur utama.
- Di mode WebUSB **tidak ada server**: browser memegang socket USB sendiri, push `scrcpy-server`, terima Annex-B, decode lokal. Ini pemenuhan paling literal dari "server tidak menanggung beban".

Catatan yang belum bisa saya buktikan: apakah `app.tangoapp.dev` memilih WebCodecs sebagai default di UI-nya. Source app web tidak ada di repo (`apps/` hanya berisi `cli`). Yang bisa dipastikan: paketnya ada, dan itu jalur yang direkomendasikan docs.

### 8.3 WebMirror: browser menanggung decode, tapi **bukan** WebCodecs

- Listing Play hanya menyebut **WebRTC** (2 kemunculan), tidak ada `WebCodecs`, `H.264`, atau `MSE`.
- Artinya decode dilakukan **pipeline native WebRTC** di browser, bukan API `VideoDecoder`. Secara hasil sama-sama "browser yang kerja", dan WebRTC biasanya malah lebih hemat daya karena frame langsung masuk compositor tanpa copy ke canvas.
- Saya **tidak bisa** memverifikasi isi APK-nya: mirror APK (APKPure/APKCombo) mengembalikan 403, dan Play tidak menyediakan unduhan langsung. Jadi klaim "WebMirror pakai WebCodecs" tidak bisa dibuktikan — dan berdasarkan deskripsinya kemungkinan besar **tidak**.

### 8.4 Jebakan yang membatalkan rencana "WebCodecs di LAN"

**WebCodecs butuh secure context.** `VideoDecoder` hanya ada di `https://` atau `localhost`. Membuka `http://192.168.1.x:8080` dari Windows → `VideoDecoder` **undefined**, dan viewer Anda mati total.

Tiga jalan keluar:

1. **HTTPS dengan sertifikat self-signed** + trust di Windows sekali. Paling bersih, tapi menambah langkah setup di klien.
2. **Chrome flag** `chrome://flags/#unsafely-treat-insecure-origin-as-secure` → tambahkan origin. Cepat, tapi per-browser dan tidak bisa dipakai di perangkat lain.
3. **Pakai WebRTC**, yang `RTCPeerConnection`-nya tetap tersedia di origin tidak aman (hanya `getUserMedia` yang butuh secure context). Ini alasan WebMirror dan ws-scrcpy nyaman di `http://` biasa.

Kalau syaratnya benar-benar "wajib WebCodecs", maka HTTPS bukan opsional — itu bagian dari desain, bukan detail deployment.

### 8.5 Kalau dibangun sendiri (opsi B): dumb pipe + WebCodecs

**Sisi HP** — ganti JPEG per frame dengan encoder hardware:

```kotlin
val format = MediaFormat.createVideoFormat("video/avc", w, h).apply {
    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)      // 1 dtk, bukan 10
    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
    setInteger(MediaFormat.KEY_LATENCY, 0)               // API 30+, wajib untuk realtime
    setInteger(MediaFormat.KEY_PRIORITY, 0)              // 0 = realtime
}
```

Baseline profile = tanpa B-frame = tanpa reorder delay. `KEY_LATENCY = 0` memaksa encoder tidak menahan frame. Tidak semua OEM menghormati `KEY_PROFILE` di encoder — verifikasi dari SPS output, jangan asumsikan.

**Sisi browser** — server cukup meneruskan byte:

```js
const dec = new VideoDecoder({
  output: f => { ctx.drawImage(f, 0, 0, canvas.width, canvas.height); f.close(); },
  error: e => console.error('decode', e),
});
dec.configure({
  codec: 'avc1.42E01E',            // Baseline 3.0 — ambil dari SPS, jangan hardcode
  optimizeForLatency: true,
  hardwareAcceleration: 'no-preference',
  // Annex-B: JANGAN set `description` (itu untuk avcC)
});
ws.onmessage = ev => {
  const { key, buf } = decodeEnvelope(ev.data);   // keyframe flag dari HP, atau parse NAL type 5
  dec.decode(new EncodedVideoChunk({
    type: key ? 'key' : 'delta',
    timestamp: performance.now() * 1000,
    data: buf,
  }));
};
```

Dua hal yang paling sering bikin gagal: (a) codec string tidak cocok dengan SPS → `configure()` reject; (b) mengirim avcC/length-prefixed tapi tidak set `description`, atau sebaliknya Annex-B tapi `description` di-set.

**Beban yang tersisa di server:** hanya `frame byte × jumlah viewer`. 4 Mbps × 3 viewer = 12 Mbps forwarding, nol CPU decode. Itu batas bawah yang tidak bisa dikurangi lagi tanpa mengorbankan kualitas.

### 8.6 Jawaban singkat

| Pertanyaan | Jawaban |
|---|---|
| Tango pakai WebCodecs? | **Ya**, paket resmi + `optimizeForLatency: true` di source. Tanpa server sama sekali di mode WebUSB. |
| WebMirror pakai WebCodecs? | **Tidak (kemungkinan besar).** WebRTC — browser tetap yang decode, tapi lewat pipeline native, bukan `VideoDecoder`. Tidak terverifikasi karena APK tidak bisa diunduh. |
| Jalur APK GoSosmed sekarang? | Bukan WebCodecs. JPEG encode di CPU HP (terakselerasi via downscale proporsional), decode `createImageBitmap` di browser. Server tidak decode, 1-in-flight flow control. |
| Yang wajib diubah kalau mau WebCodecs? | Encode: MediaProjection + MediaCodec H.264. Transport: WS binary. Klien: HTTPS (secure context). Server: tetap dumb pipe. |

---

## 9. Studi Kasus & Audit Biner: iMyFone MirrorTo (`com.imyfone.mirrorto` v4.8.1)

Berdasarkan dekompilasi biner `com.imyfone.mirrorto` v4.8.1:

### 9.1 Arsitektur Internal iMyFone
1. **Dual Accessibility Injection**:
   - Memasang `com.genymobile.scrcpy.SupportControllerService` (scrcpy controller disematkan dalam AccessibilityService) dan `com.imyfone.mirrorto.web.MouseAccessibilityService`.
2. **Keyboard IME Kustom (`MirrorToIME`)**:
   - Meminta izin `android.permission.BIND_INPUT_METHOD` untuk menginjeksi ketukan keyboard dan teks tanpa jeda clipboard atau batasan aksesibilitas `SET_TEXT`.
3. **Inbound HTTP + WebRTC Server Lokal**:
   - Menjalankan `SocketCastScreenService` dan `WebRtcService` (`foregroundServiceType="mediaProjection"`).
   - Menyajikan web viewer langsung dari aset internal APK (`assets/html/index.html`, `assets/js/mouse.js`, `assets/js/main.js`).
   - Browser terhubung langsung ke IP lokal HP (`ws://<phone-ip>:<port>`), lalu melakukan negosiasi WebRTC PeerConnection via Google STUN (`stun:stun.l.google.com:19302`).

### 9.2 Mengapa Model iMyFone Tidak Layak untuk Enterprise / Cloud
1. **Gagal Total di Seluler (4G/5G) & CGNAT**:
   - Karena mengandalkan inbound HTTP server di HP, browser di luar jaringan Wi-Fi lokal tidak dapat mengakses HP. Pada jaringan seluler dengan Carrier-Grade NAT (CGNAT) atau Wi-Fi kantor dengan isolasi AP, koneksi gagal total.
2. **Pelanggaran Keamanan Port Terbuka**:
   - Membuka port HTTP/TCP publik pada HP pribadi di jaringan umum mengekspos perangkat ke pemindaian port lokal dan eksploitasi.
3. **Beban Biner & Baterai Raksasa (Bloatware)**:
   - APK membengkak hingga >40 MB karena menimbun library native (`libjingle_peerconnection_so.so`, `libyuv-decoder.so`, `libijkplayer.so`, `libijkffmpeg.so`, `libBugly.so`, `libmarsxlog.so`).
   - Menjalankan 14+ service latar belakang dan dijejali pelacak iklan komersial (Google AdMob, Firebase Measurement, AppsFlyer, Bugly).

### 9.3 Keunggulan Arsitektur GoSosmed Agent
1. **Outbound WSS (A1/A2)**:
   - HP selalu menghubungi backend secara outbound (`wss://...`). Bekerja di jaringan apa pun (Wi-Fi, 4G, 5G, VPN) tanpa perlu port forwarding.
2. **Ringan & Bersih**:
   - Ukuran APK hanya ~2.5 MB (16× lebih kecil dari iMyFone). Nol library C++ native (.so), nol iklan, nol pelacak pihak ketiga.
3. **Kontrol Interaktif Bidireksional Lengkap**:
   - Viewer web (`tools/browser-viewer/index.html`) mendukung interaksi klik/tap langsung pada kanvas, drag/swipe, navigasi hardware virtual (Back, Home, Recents, Wake), dan pengetikan teks.
