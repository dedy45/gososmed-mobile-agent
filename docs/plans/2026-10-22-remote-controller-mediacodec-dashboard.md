# Remote Controller & Live Streaming H.264 MediaCodec Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Menambahkan menu kartu Remote Controller di dashboard APK Android (`activity_main.xml` & `MainActivity.kt`) dengan status **wajib default false (OFF)**, proteksi prasyarat ganda (Aksesibilitas + Wireless Debugging UID 2000 wajib aktif), engine hardware `MediaCodec` H.264 zero-copy (`CaptureService.kt` + `SpsFix.kt`), serta dual-mode streaming (Cloud WSS relay dan Direct Local Browser port `8765`).

**Architecture:** Mengadopsi arsitektur zero-copy hardware encoder dari `vitRemote` dan `scrcpy` tanpa sebaris pun biner native `.so`. Layanan `CaptureService` berjalan sebagai foreground service bertipe `mediaProjection` (patuh Android 14/15) yang mengalirkan H.264 Annex-B NALU dengan VUI SPS rewrite (`max_num_reorder_frames = 0`) untuk mematikan reorder delay 250ms di browser WebCodecs. Injeksi kontrol realtime menggunakan akselerasi shell ADB (UID 2000) dengan fallback `dispatchGesture` aksesibilitas.

**Tech Stack:** Kotlin 1.9+, Android SDK 34/35, `android.media.MediaCodec`, `android.media.projection.MediaProjection`, `android.hardware.display.VirtualDisplay`, Android Accessibility API, NanoHTTPD / Embedded WebSocket Server, WebCodecs API di browser viewer.

---

## 1. Analisis Arsitektur: Dua Opsi Kecepatan Remote

### Opsi A: Lewat Cloud WSS / API Relay (Bawaan GoSosmed)
- **Topologi**: HP menghubungi keluar (*outbound*) ke `wss://<GOSOSMED_AGENT_HOST>/v1/agent/ws`. Operator web membuka dashboard GoSosmed dan menerima stream lewat multiplexer backend.
- **Karakteristik**: Tembus NAT/CGNAT seluler (4G/5G), tidak membutuhkan Wi-Fi yang sama, nol port forwarding pada router.
- **Latensi**: 80–180 ms.

### Opsi B: Direct Local Browser Port (High-Speed LAN via Port 8765)
- **Topologi**: HP membuka port lokal `http://<ip-hp>:8765` HANYA saat:
  1. Toggle Remote Controller diaktifkan secara sadar oleh pengguna.
  2. Aksesibilitas AKTIF.
  3. Wireless Debugging AKTIF (UID 2000 terverifikasi).
- **Karakteristik**: Latensi ultra-rendah (10–30 ms) setara koneksi kabel USB, 30–60 FPS Full HD, nol penggunaan kuota server/cloud. Operator cukup membuka browser di PC/laptop ke IP HP atau lewat port forward ADB (`adb forward tcp:8765 tcp:8765` ➜ `http://localhost:8765`).

**Keputusan Arsitektur**: Mengimplementasikan **Hybrid Dual-Mode** sehingga kedua opsi aktif sinergis saat toggle Remote Controller dinyalakan.

---

## 2. Struktur Modul dan File

| Modul | File Target | Tanggung Jawab |
|---|---|---|
| **UI Beranda** | `app/src/main/res/layout/activity_main.xml`<br>`app/src/main/java/com/gososmed/agent/MainActivity.kt` | Kartu UI Remote Controller, MaterialSwitch **default false**, pengecekan status Aksesibilitas + Wireless Debugging, dialog sistem `MediaProjectionManager`. |
| **Encoder Hardware** | `app/src/main/java/com/gososmed/agent/capture/CaptureService.kt`<br>`app/src/main/java/com/gososmed/agent/capture/SpsFix.kt` | Foreground Service bertipe `mediaProjection`, MediaCodec Surface H.264 zero-copy, eliminasi reorder latency 250ms via bitstream restriction. |
| **Embedded Web Server** | `app/src/main/java/com/gososmed/agent/capture/LocalWebServer.kt` | HTTP & WebSocket server lokal ringan pada port `8765`, menyajikan viewer HTML dan streaming video lokal. |
| **Engine Kontrol** | `app/src/main/java/com/gososmed/agent/control/RemoteControlManager.kt` | Parser perintah sentuh (`tap`, `swipe`, `d:x,y`, `m:x,y`, `u:x,y`) terakselerasi shell ADB UID 2000 + fallback `dispatchGesture`. |
| **Unit Tests** | `app/src/test/java/com/gososmed/agent/capture/SpsFixTest.kt`<br>`app/src/test/java/com/gososmed/agent/control/RemoteControlManagerTest.kt` | Verifikasi NALU rewrite VUI SPS dan parsing koordinat sentuh. |

---

## 3. Rincian Task Implementasi (Bite-Sized)

### Task 1: `SpsFix.kt` & Unit Test
- **File**: `app/src/main/java/com/gososmed/agent/capture/SpsFix.kt`
- **Test**: `app/src/test/java/com/gososmed/agent/capture/SpsFixTest.kt`
- **Fungsi**: Membaca NALU SPS Annex-B dan menambahkan sintaks VUI `max_num_reorder_frames = 0` sehingga browser me-render frame secara instan tanpa antrean.

### Task 2: `RemoteControlManager.kt` & Unit Test
- **File**: `app/src/main/java/com/gososmed/agent/control/RemoteControlManager.kt`
- **Test**: `app/src/test/java/com/gososmed/agent/control/RemoteControlManagerTest.kt`
- **Fungsi**: Menerima perintah teks cepat (`tap`, `swipe`, `back`, `home`, `recents`, `wake`, `setText`) dan mengeksekusinya via shell UID 2000 atau gesture aksesibilitas.

### Task 3: `CaptureService.kt` (MediaCodec Hardware H.264 Zero-Copy)
- **File**: `app/src/main/java/com/gososmed/agent/capture/CaptureService.kt`
- **Manifest**: Deklarasi service dengan `android:foregroundServiceType="mediaProjection"`.
- **Fungsi**: Mengelola siklus hidup `MediaProjection`, `VirtualDisplay`, dan `MediaCodec` Surface encoder.

### Task 4: `LocalWebServer.kt` (Direct Local Browser Port 8765)
- **File**: `app/src/main/java/com/gososmed/agent/capture/LocalWebServer.kt`
- **Fungsi**: Server HTTP & WebSocket embedded pada port 8765 yang melayani browser lokal saat Wireless Debugging aktif.

### Task 5: UI Kartu Remote Controller di `activity_main.xml` & `MainActivity.kt`
- **File**: `app/src/main/res/layout/activity_main.xml`, `app/src/main/java/com/gososmed/agent/MainActivity.kt`
- **Fungsi**: Kartu MaterialSwitch (default false), verifikasi Aksesibilitas + Wireless Debugging, pemicu `registerForActivityResult` MediaProjection.

### Task 6: Kompilasi, Unit Test Suite & Validasi Nyata di HP Fisik
- **Target**: `gradle testDebugUnitTest assembleDebug`
- **Pengujian**: Pemasangan ke perangkat `2c76b8a3` (Android 15), uji coba toggle ON, verifikasi port 8765 di browser dan logcat MediaCodec.
