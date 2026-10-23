# Audit: Codec HEVC/H.265 di Aplikasi Produksi

**Tanggal:** 2026-10-23
**Tujuan:** Mencari bukti nyata (bukan asumsi) apakah aplikasi produksi di
lingkungan referensi benar-benar memakai HEVC/H.265 untuk streaming layar.

---

## Ringkasan Temuan

| Aplikasi | HEVC di kode | HEVC untuk **encode** layar? | Bukti |
|---|---|---|---|
| DeskIn / ToDesk | Ya (`video/hevc`, `video/av01`) | **Tidak** — hanya decode capability | `classes5.dex` |
| iMyFone MirrorTo | Ya (`video/hevc`, `video/av01`) | **Tidak** — hanya decode capability | `classes2.dex` |
| WebMirror LAN | Ya (`video/hevc`, `hvc1`) | **Tidak** — memaksa **H.264** | `assets/index.html` |

**Kesimpulan: tidak ada satu pun aplikasi produksi di lingkungan referensi
yang meng-encode layar dengan HEVC/H.265.**

---

## Bukti per Aplikasi

### 1. WebMirror (`com.webmirror.lan.apk`)

Paling jelas. APK mengandung string codec HEVC, tetapi kode web-nya **secara
eksplisit memaksa H.264**:

```js
// assets/index.html — preferHardwareFriendlyVideoCodec()
const leftH264  = left.codec.mimeType.toLowerCase() === 'video/h264';
const rightH264 = right.codec.mimeType.toLowerCase() === 'video/h264';
return Number(rightH264) - Number(leftH264) || left.index - right.index;
...
transceiver.setCodecPreferences(codecs);
```

Analisis di `ANALISIS-BEDAH-WEBMIRROR.md` menyatakan alasannya:

> *"Encoder software VP8/VP9 di Android membuat CPU panas dan boros baterai
> pada 1080p60. WebMirror memaksa browser meminta H.264 yang 100% didukung
> chip Qualcomm/MediaTek."*

String `video/hevc`/`hvc1` di `classes.dex` berasal dari **decoder** ExoPlayer
yang dibundel (komponen pemutaran media), bukan dari encoder layar.

**WebMirror tidak memakai WebCodecs sama sekali** — 0 kemunculan `VideoDecoder`.
Mereka memakai WebRTC native `<video>`.

### 2. DeskIn / ToDesk (`com.zuler.deskin`)

String HEVC yang ditemukan, semuanya bernuansa **decoder**:

```
Disallowing HEVC on software decoder:
decoderSupportsRefFrameInvalidationHevc
Allowing HEVC based on media performance class
Allowing HEVC based on FEATURE_LowLatency support
Enabling HEVC RFI based on low latency option support
OMX.qcom.video.decoder.hevcswvdec
c2.mtk.hevc.decoder
```

Tidak ada `MIMETYPE_VIDEO_HEVC` sebagai target encoder, tidak ada `hvcC`/`hvc1`
sebagai sample entry di jalur capture.

### 3. iMyFone MirrorTo (`app-MirrorTo.apk`)

`video/hevc` dan `video/av01` ada di `classes2.dex`, tetapi:

- `assets/js/adapter-latest.js` (satu-satunya aset web yang menyentuh MSE)
  memiliki **0 kemunculan** `avcC`, `hvcC`, `av01`, `addSourceBuffer`, atau
  `video/mp4`.
- Artinya tidak ada muxer fMP4 di sisi web. Stream mereka lewat WebRTC
  (`libjingle_peerconnection_so.so`), dan WebRTC memakai H.264 secara default.

---

## Implikasi untuk GoSosmed

### Mengapa HEVC/AV1 tidak bisa langsung dipakai

Muxer fMP4 di `stream.js` membangun sample entry `avc1` + box `avcC` dari
**SPS (NALU tipe 7)** dan **PPS (tipe 8)**.

- **AV1** tidak memakai SPS/PPS — ia memakai OBU sequence header.
- **HEVC** memakai VPS/SPS/PPS (tipe 32/33/34) dengan sample entry `hvc1`
  dan config box `hvcC`.

Karena `onConfig()` mencari tipe 7/8 dan `return` bila tidak menemukan,
mengirim AV1/HEVC ke browser menghasilkan **layar kosong**.

Ini terverifikasi di perangkat: negosiasi memilih `video/av01`, tetapi viewer
tidak menampilkan apa pun sampai `preferredCodec` dikembalikan ke AVC.

### Keputusan yang diambil

`CaptureService.preferredCodec` di-set default ke **AVC**. Negosiasi tetap
berjalan dan dicatat di log untuk diagnostik, tetapi pipeline tidak beralih
sampai muxer mendukung codec tersebut.

### Yang dibutuhkan bila HEVC benar-benar diinginkan

| Codec | Sample entry | Config box | Parameter set |
|---|---|---|---|
| AVC (sekarang) | `avc1` | `avcC` | SPS (7) + PPS (8) |
| HEVC | `hvc1` | `hvcC` | VPS (32) + SPS (33) + PPS (34) |
| AV1 | `av01` | `av1C` | OBU sequence header |

Pekerjaan: parser NALU per-codec, pembangun `initSeg()` per-codec, penyesuaian
flag sample di `fragment()`. Ini perubahan besar dan berisiko tinggi, dan
**tidak ada contoh produksi yang bisa ditiru** di lingkungan referensi.

---

## Rekomendasi

> **KOREKSI (2026-10-23, setelah implementasi).** Bagian ini awalnya
> menyimpulkan "tetap pakai AVC" karena keliru menganggap GoSosmed hanya punya
> jalur fMP4/MSE. Faktanya viewer **WebCodecs-first** (`stream.js` Mode A,
> port 8766/HTTPS) dan menerima Annex-B mentah tanpa muxer — sama seperti
> Tango. HEVC karena itu **bisa dan sudah** diaktifkan di jalur tersebut.
> Lihat `CHANGELOG.md` [Unreleased].

1. **HEVC aktif di WebCodecs (:8766), AVC di MSE (:8765).** Bukan saling
   menggantikan: HEVC butuh secure context, MSE fMP4 tetap H.264-only.
2. **Jangan bangun muxer `hvcC`.** Untuk HEVC, WebCodecs menerima Annex-B
   dengan parameter set in-band dan `description` kosong. Ini menghapus
   seluruh kebutuhan muxer HEVC.
3. **AV1 belum didukung.** Butuh parser OBU sequence header + codec string
   `av01.…`; belum ada kebutuhan mendesak karena HEVC sudah menghemat
   bandwidth setara di perangkat yang mendukungnya.
