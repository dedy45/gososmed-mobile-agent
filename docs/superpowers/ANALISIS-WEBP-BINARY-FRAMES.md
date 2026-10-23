# Analisis Awal: Frame WebP/JPEG untuk GoSosmed Agent

> Status: **DRAFT analisis**, belum disetujui dan belum diimplementasikan.
> Tanggal: 2026-10-21. Repo: `gososmed-mobile-agent`. Tidak ada kode, tag, atau versi yang diubah.

## Kesimpulan singkat

Ide **WebP sebagai opsi hemat bandwidth** layak dicoba, tetapi **belum terbukti ringan** di HP maupun browser. JPEG dan WebP hanya bisa dipilih dengan benar setelah diukur di perangkat nyata dan browser target. Lebih penting lagi, fitur ini tidak boleh dipasang di atas perilaku stream yang sekarang karena stream dapat melaporkan sukses, tetap mengirim frame, dan menerima ACK yang tidak terikat pada sesi.

Angka lampiran, seperti 5–12 ms untuk JPEG, 25–45 ms untuk WebP, 15–55 KB per frame, dan klaim penghematan sampai 90%, adalah **hipotesis**, bukan hasil uji repo ini.

## Fakta repo saat analisis

- HEAD adalah `cf6dba82440838ec4c854fb0fa5f3ca03c276fad` pada branch `dev`, sama dengan `origin/dev` dan tag `v1.2.2`.
- `versionCode = 36` dan `versionName = "1.2.2"`. `minSdk = 26`, `targetSdk = 34`, `compileSdk = 34`.
- Tag `v1.2.3` **tidak ditemukan** di lokal maupun remote. Tag terbaru adalah `v1.2.2`, sebelumnya `v1.2.2-dev.2`.
- Build `1.2.3-dev` yang dimaksud tidak dapat dibuktikan berasal dari HEAD ini. Bisa jadi berasal dari mesin atau working tree lain yang belum dipush.
- Tidak ada perubahan file tracked. Yang ada hanya dokumen analisis dan plan yang belum dikomit.

## Apa yang sudah ada di kode

| Area | Fakta kode | Dampak |
|---|---|---|
| Capture | `takeScreenshotBase64` dan `takeScreenshotRawBytes` adalah dua pipeline terpisah | Perbaikan dapat hanya mengenai satu jalur dan menghasilkan perilaku berbeda |
| Format | Format selain JPEG ditulis sebagai PNG | Permintaan WebP atau format salah ketik dapat menghasilkan PNG tanpa peringatan |
| Resize | Ada jalur downscale dan salinan bitmap | Biaya salinan ARGB penuh perlu diukur sebelum mengklaim efisiensi |
| OBSERVE | Gambar masuk antrean debug global | Respons satu kali bercampur dengan stream; capture gagal dapat tetap mendaftarkan snapshot |
| Stream | Coroutine dengan `delay(1000L / fps)` dan hasil `send` tidak diperiksa | Tidak ada jadwal absolut atau berhenti jujur saat kirim gagal |
| ACK | ACK diterima lewat `type` atau `cmd` dan berdasarkan ID saja | ACK dari sesi atau frame lain tidak terikat dengan benar |
| Handshake | `register()` belum mengirim objek `capabilities` | Server tidak dapat mendeteksi dukungan binary dari handshake saat ini |
| Kontrak | Dokumentasi dan fixture belum konsisten untuk START/ACK/statistik | Implementasi dan pengujian bisa memakai asumsi berbeda |

Semua temuan ini berada di jalur screenshot/stream. Fitur WebP tidak boleh memperluas problem ini dengan menambahkan jalur baru yang terpisah.

## Rekomendasi teknis

Urutan yang aman adalah **backend-first**, bukan langsung mengganti encoder:

1. **Simpan kompatibilitas.** JPEG tetap default, PNG tetap tersedia, WebP lossy hanya opsi eksplisit pada API 30+.
2. **Validasi format secara ketat.** Format tak dikenal harus menghasilkan `invalid_image_arg`, bukan diam-diam menjadi PNG.
3. **Satu pipeline capture.** Latch, buffer, downscale, compress, dan validasi header harus berada di satu fungsi dan diuji di perangkat.
4. **Balas OBSERVE secara utuh.** Urutannya envelope, `frame_meta`, lalu binary. Jangan mengambil dari antrean live dan jangan membuat ACK lokal.
5. **Stream jujur.** Satu sesi, FPS 1–3, satu frame in-flight, ACK 2 detik, tanpa retry. START baru saat sesi aktif harus ditolak.
6. **Metadata jujur.** Format, quality, scale, dimensi sumber dan output harus sama dengan bytes yang benar-benar dikirim.
7. **Rollout.** Backend harus mengenali respons baru dan tetap bisa membaca respons lama sebelum APK dev dipasang. Handshake lama tanpa `capabilities` mengharuskan uji kompatibilitas dua arah.

Event-driven capture, debounce, frame hash, atau penghentian capture saat layar diam **tidak termasuk siklus ini**. Fitur itu bisa ditambahkan setelah cara capture sekarang diukur dan terbukti stabil. Klaim penghematan besar hanya boleh dipakai setelah ada data.

## Gate sebelum dan sesudah implementasi

| Gate | Bukti minimal |
|---|---|
| Versi | Sumber build `1.2.3-dev` dikonfirmasi atau mulai eksplisit dari `v1.2.2` |
| Baseline | `testDebugUnitTest` dan `assembleDebug` berhasil pada commit awal |
| Kontrak | Spesifikasi dan fixture disetujui bersama pemilik backend |
| Unit test | Validasi, metadata, state machine ACK, dan regression test legacy lulus |
| HP nyata | Minimal dua API/OEM, decode independen, p95 CPU, PSS/RSS, thermal, dan rate capture |
| Gateway | WebSocket TLS sungguhan, ACK normal/timeout/salah sesi, disconnect, dan rekoneksi |
| Browser | Chromium dan WebKit, satu bitmap aktif, `close()` berhasil, heap stabil 5 menit |
| Rilis | CI hijau, `apksigner verify` lulus, rollback diuji, `CHANGELOG.md` menyebut bukti dan batas |

Ambang angka harus ditetapkan sebelum benchmark, bukan sesudah melihat hasil. Tanpa ambang dan dua bukti device/browser, label yang boleh dipakai hanyalah `-dev`, bukan stabil.

## Status verifikasi saat ini

Perintah test dijalankan dengan JDK dan Gradle lokal, tetapi **gagal sebelum kompilasi**:

```text
java.io.IOException: Unable to establish loopback connection
```

Kegagalan terjadi pada koneksi loopback daemon maupun single-use daemon JVM. Ini adalah masalah lingkungan tooling, bukan bukti bahwa kode sudah benar. Unit test dan build harus dijalankan ulang di sesi yang mengizinkan loopback sebelum status G2 dicatat lulus.

Belum ada uji HP, gateway, atau browser. Karena itu belum dapat diklaim bahwa WebP/JPEG ringan pada HP, hemat di jaringan, atau ringan pada browser.

## Dokumen rinci

- [`specs/2026-10-21-webp-binary-frames-design.md`](specs/2026-10-21-webp-binary-frames-design.md): kontrak, risiko, kompatibilitas, dan keputusan desain.
- [`plans/2026-10-21-webp-binary-frames.md`](plans/2026-10-21-webp-binary-frames.md): urutan implementasi, test, benchmark, dan gate rilis.

## Keputusan yang dibutuhkan

1. Sumber build `1.2.3-dev` yang dimaksud: berikan commit SHA atau salinannya, atau setujui mulai dari `v1.2.2`.
2. Persetujuan cakupan Level 1 pada spesifikasi, termasuk JPEG default, WebP opt-in, dan ACK 2 detik.
3. Kesediaan pemilik backend privat untuk rollout backend-first dan uji dua arah. Repo ini tidak mengedit backend.
4. Target HP dan browser untuk benchmark, beserta ambang p95 CPU, PSS/RSS, heap browser, dan latency render.

Setelah empat hal ini jelas, implementasi dapat dimulai dengan test lebih dahulu, tanpa menyentuh monorepo privat.
