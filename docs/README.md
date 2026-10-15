# Dokumentasi Teknis GoSosmed Mobile Agent

Selamat datang di indeks dokumentasi teknis **GoSosmed Mobile Agent** (*BYOD Local Device Bridge*). Dokumen-dokumen di bawah ini mencakup spesifikasi arsitektur, kontrak protokol komunikasi, panduan pengaturan pengguna, panduan kompilasi toolchain, serta catatan pengujian perangkat fisik.

---

## 🗺️ Peta Dokumentasi

| Dokumen | Kategori | Deskripsi |
|---|---|---|
| **[SETUP-3-LANGKAH.md](SETUP-3-LANGKAH.md)** | Panduan Pengguna | Panduan praktis 3 langkah untuk mengaktifkan izin Aksesibilitas, Overlay, dan pairing ADB pada HP. |
| **[TOOLCHAIN-LOKAL.md](TOOLCHAIN-LOKAL.md)** | Panduan Pengembang | Spesifikasi JDK 17, Gradle 8.9, Android SDK 34, perintah unit test, dan kompilasi lokal yang identik dengan CI. |
| **[AGENT-COMMAND-CONTRACT.md](AGENT-COMMAND-CONTRACT.md)** | Spesifikasi Protokol | Kontrak komunikasi WebSocket antara Mobile Agent dan Backend (amplop JSON, daftar perintah, payload, dan respon). |
| **[PAIRING-FLOW.md](PAIRING-FLOW.md)** | Keamanan & Koneksi | Alur pairing perangkat (1-tap deep link `gososmed://pair` dan kode manual 8-karakter) serta siklus WebSocket. |
| **[DB-AND-API-MAP.md](DB-AND-API-MAP.md)** | Arsitektur Sistem | Pemetaan empat lapisan: Database ↔ Backend API ↔ Command Agent ↔ Frontend Web. |
| **[PLAN-DETERMINISTIC-ANDROID-PORTAL.md](PLAN-DETERMINISTIC-ANDROID-PORTAL.md)** | Desain Arsitektur | Rancang bangun kendali deterministik Android Protocol v2, pohon elemen UI, dan penolakan snapshot usang. |
| **[KOTLIN-PERFORMANCE-AND-REMOTE-CONTROL-ARCHITECTURE.md](KOTLIN-PERFORMANCE-AND-REMOTE-CONTROL-ARCHITECTURE.md)** | Desain Arsitektur | Optimalisasi performa: Set-of-Marks visual vision, deteksi mutasi layar, in-memory zero-leak canvas, dan buffer stream. |
| **[TRANSPORT-ADB-LOKAL.md](TRANSPORT-ADB-LOKAL.md)** | Transport Layer | Implementasi ADB client lokal tertanam (UID 2000 / `shell`) via Wireless Debugging tanpa ketergantungan tool eksternal. |
| **[F0-LIBRARY-VALIDATION.md](F0-LIBRARY-VALIDATION.md)** | Keputusan Arsitektur | Catatan evaluasi dan validasi pemilihan pustaka ADB (`libadb-android` vs alternatif). |
| **[SHIZUKU-TRANSPORT.md](SHIZUKU-TRANSPORT.md)** | Arsip Historis | Dokumentasi historis transport Shizuku (v0.8.0) sebelum digantikan oleh ADB lokal mandiri pada v0.9.0. |

---

## 📂 Direktori Khusus

### 1. `contracts/`
Berisi berkas *fixture* JSON referensi yang digunakan oleh unit test untuk memastikan kepatuhan protokol:
- `capability-fixture.json`: Snapshot kapabilitas agen saat ini (`protocol_versions: [1, 2]`).
- `v1-fixtures.json`: Fixture pesan protokol v1 (legacy).
- `v2-fixtures.json`: Fixture pesan protokol v2 (deterministik).

### 2. `5-testing/`
Berisi catatan pengujian fungsional dan laporan verifikasi perangkat fisik:
- `17-AUDIT-KOTLIN-PROTOCOL-V2-2026-10-05.md`: Laporan audit kelulusan 7 Quality Gates pada implementasi Protocol v2.
- `TASK-ANNOTATED-SCREENSHOT-STABILIZATION.md`: Catatan stabilisasi penanganan error native Android screenshot dan alokasi memori in-mutable (v1.2.1).

---

## 🔒 Kebijakan Keamanan & Transparansi Publik

Repository ini bersifat publik dan berlisensi **Apache-2.0** agar setiap pengguna dapat mengaudit seluruh kode yang meminta izin tingkat tinggi di Android (`AccessibilityService` dan `android.permission.INTERNET`).

- Repositori ini **hanya** berisi kode klien Android Kotlin. Backend, antarmuka web, dan basis data dikelola terpisah.
- Tidak ada kredensial, token privat, berkas keystore rahasia, atau data pribadi yang disimpan di dalam repositori ini.
