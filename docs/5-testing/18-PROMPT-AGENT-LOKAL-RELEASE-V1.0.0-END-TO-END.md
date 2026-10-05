# Prompt Agent Lokal — GoSosmed Mobile Agent v1.0.0 End-to-End

> **Gunakan dokumen ini sebagai task contract lengkap untuk agent coding lokal.**
>
> **Repository:** `C:\Users\dedy\Documents\gososmed-mobile-agent`
> **Target release:** `v1.0.0` stable
> **Target perangkat:** Redmi Note 13 Pro 5G, serial ADB `2c76b8a3`
> **Tujuan:** menghasilkan APK Protocol v2 yang bersih, deterministic, teruji nyata, ditandatangani GitHub Actions, dan layak dipakai bersama GoSosmed DEV.

---

## A. Peran dan Goal

Anda adalah coding agent utama untuk repository Android `gososmed-mobile-agent`.

Tugas Anda bukan sekadar membuat build hijau. Tugas Anda membuktikan bahwa APK benar-benar:

1. membaca layar Android nyata;
2. memilih node secara deterministik;
3. menolak node ambigu atau snapshot usang;
4. mengeksekusi aksi native dan memverifikasi perubahan layar;
5. dapat dibatalkan ketika operasi masih berjalan;
6. mengirim debug frame biner yang benar-benar diterima;
7. tidak menghasilkan false success;
8. tidak memblokir main looper;
9. tidak membocorkan password atau data sensitif;
10. tidak memiliki duplicate implementation, dead code, atau scaffolding produksi yang tidak terpakai;
11. dapat dibangun menjadi `v1.0.0` melalui GitHub Release dengan signing resmi.

Selesai berarti source, test, perangkat fisik, dokumentasi, commit, CI, APK release bertanda tangan, dan smoke test artifact release semuanya konsisten.

---

## B. Aturan Keras

1. Baca `AGENTS.md` dan `docs/TOOLCHAIN-LOKAL.md` sebelum mengubah apa pun.
2. Jangan mengedit repository backend `C:\Users\dedy\Documents\Go-sosmed` dari sesi ini.
3. Jangan menambah framework atau dependency jika Android SDK, Kotlin coroutine, OkHttp, atau kelas yang sudah ada mencukupi.
4. Jangan membuat class kedua yang menduplikasi collector, resolver, registry, executor, verifier, queue, atau transport yang sudah ada.
5. Jangan mempertahankan dummy success, placeholder runtime, atau simulated implementation di call path produksi.
6. Fixture simulasi boleh hidup hanya di source set test atau sebagai pure model yang benar-benar dipakai test.
7. Jangan memakai `Thread.sleep` pada runtime Protocol v2. Gunakan coroutine `delay`, accessibility event, deadline, dan cancellation.
8. Jangan menyatakan sukses hanya karena `performAction()` dipanggil. Sukses memerlukan postcondition atau perubahan snapshot yang terbukti.
9. Gesture coordinate fallback harus eksplisit, terukur, memiliki valid bounds, dan tidak menjadi default untuk node ambigu.
10. Jangan menyimpan debug frame ke disk. Frame harus berada di memory dan dikirim sebagai binary WebSocket.
11. Jangan commit APK, keystore, build output, `.gradle/`, atau `.kotlin/`.
12. Jangan melemahkan signing checks pada `.github/workflows/release.yml`.
13. Jangan membuat tag stable jika satu gate nyata masih gagal atau belum diuji.
14. Semua klaim wajib mempunyai command, output, payload, screenshot, frame counter, logcat, atau artifact hash yang diamati.
15. Signature mismatch dengan APK lama adalah expected karena signing key lama sudah dimatikan. Gunakan uninstall-install ketika berpindah antara debug dan GitHub-signed APK. Jangan menyebutnya bug update.

---

## C. Kondisi Awal yang Harus Diverifikasi

Jalankan dan catat:

```bat
git status --short --branch
git diff --check
adb devices -l
adb -s 2c76b8a3 shell getprop ro.product.model
adb -s 2c76b8a3 shell getprop ro.build.version.release
adb -s 2c76b8a3 shell getprop ro.build.version.sdk
adb -s 2c76b8a3 shell settings get secure accessibility_enabled
adb -s 2c76b8a3 shell settings get secure enabled_accessibility_services
```

Expected:

- branch `main`;
- device `2c76b8a3` berstatus `device`;
- AccessibilityService GoSosmed aktif;
- working tree hanya berisi perubahan task yang diketahui;
- tidak ada agent kedua yang menulis repository ini.

Jika device unauthorized, offline, lockscreen aktif, atau AccessibilityService mati, hentikan test dan perbaiki precondition. Jangan mengubah kegagalan precondition menjadi keberhasilan aplikasi.

---

## D. Versi Rilis yang Dikunci

Jangan membuat rangkaian `0.9.11`, `0.9.12`, atau `0.9.13` baru.

Setelah seluruh gate lulus, gunakan:

```kotlin
versionCode = 30
versionName = "1.0.0"
```

Perbarui komentar versi agar tidak lagi menyebut versionCode lama.

Changelog harus memiliki heading exact:

```markdown
## [1.0.0] - 2026-10-05
```

Isi release notes wajib memisahkan:

- perubahan arsitektur;
- perilaku yang diuji nyata;
- perangkat dan Android version;
- hal yang belum diuji;
- batasan signing dan reinstall;
- kompatibilitas Protocol v1 dan v2.

Jangan menghapus histori `0.9.11-dev.1`. Jadikan itu prerelease sebelumnya.

---

## E. Audit Konsistensi Sebelum Coding

Audit file berikut dan buat matriks `kelas → caller produksi → test → status`:

- `AgentAccessibilityService.kt`
- `AgentCommand.kt`
- `AgentWsClient.kt`
- `WindowRootCollector.kt`
- `SnapshotCollector.kt`
- `SnapshotRegistry.kt`
- `ActionableNodeRegistry.kt`
- `DeterministicResolver.kt`
- `NodeActionExecutor.kt`
- `ScreenTransitionVerifier.kt`
- `CommandCancellationRegistry.kt`
- `MutationGuard.kt`
- `DebugFrameEncoder.kt`
- `ProtocolV2.kt`
- `SnapshotModels.kt`

Untuk setiap public class dan function baru, buktikan salah satu:

1. dipanggil oleh runtime produksi;
2. dipakai sebagai kontrak domain yang diperlukan;
3. hanya fixture test dan ditempatkan secara tepat;
4. dihapus karena dead code.

Cari dan hilangkan:

```text
TODO/FIXME yang memengaruhi runtime
unconditional ok=true
return true tanpa aksi nyata
hard-coded display/package produksi
Thread.sleep pada Protocol v2
Simulated* yang dipanggil source produksi
duplicate serializer/collector/resolver
unused registry atau queue
catch Throwable yang menelan kegagalan tanpa log/reason_code
```

Jangan melakukan refactor kosmetik di luar scope.

---

## F. Perbaikan Dokumentasi Audit yang Wajib

`docs/5-testing/17-AUDIT-KOTLIN-PROTOCOL-V2-2026-10-05.md` saat ini tidak konsisten. Header menyatakan resolved, tetapi bagian awal masih menyatakan dummy runtime, Gradle tidak tersedia, dan real-device test belum dilakukan.

Rapikan menjadi satu dokumen kronologis yang jujur:

1. **Baseline sebelum perbaikan** berisi temuan lama dan diberi label historis.
2. **Perubahan yang diterapkan** memetakan setiap temuan ke file dan test.
3. **Bukti sesudah perbaikan** berisi output nyata.
4. **Remaining limitations** tidak boleh kosong jika ada bagian yang belum diuji.
5. **Final verdict** hanya PASSED jika semua gate di dokumen ini lulus.

Jangan hanya mengganti header. Jangan membiarkan dua kesimpulan yang bertentangan dalam satu dokumen.

Perbaiki dua bukti yang saat ini belum cukup:

- `cancelAction` dengan `cancelled:false` bukan bukti cancellation berhasil;
- respons `startDebugFrames` dan `stopDebugFrames` saja bukan bukti binary frame pernah terkirim.

---

## G. Quality Gate Implementasi

### Gate 1 — Runtime Observe Nyata

Buktikan `observe` menghasilkan:

- display aktual;
- rotation aktual;
- foreground package aktual;
- window ID aktual;
- hierarchy dari `service.windows` atau fallback yang jujur;
- snapshot ID baru;
- tree hash;
- screen fingerprint;
- quality status;
- node count masuk akal.

Tree kosong tidak boleh dianggap valid kecuali alasan kanonikal menjelaskan layar memang tidak dapat dibaca.

Password node wajib menghasilkan `[REDACTED]` sebelum serialization, hashing, fingerprinting, logging, dan inspector.

### Gate 2 — Snapshot Scope dan Resolver

Buktikan:

- `snapshot_id` wajib untuk resolve berbasis snapshot dan seluruh mutation;
- snapshot tidak ada atau expired menghasilkan `stale_snapshot`;
- registry memiliki TTL dan bounded capacity;
- node ID stabil terhadap jitter kecil;
- wrong package ditolak;
- ambiguous selector ditolak tanpa tap;
- node non-clickable dapat memakai clickable ancestor yang benar;
- node non-actionable tanpa ancestor ditolak.

### Gate 3 — Action dan Verification

Buktikan `actNode` serta `actAndVerify`:

- memakai `AccessibilityNodeInfo.performAction` terlebih dahulu;
- menemukan kembali node dari snapshot tanpa menggunakan node acak;
- memeriksa package sebelum aksi;
- memeriksa bounds;
- membedakan `action_performed` dan `verified`;
- menunggu perubahan menggunakan suspend pipeline;
- menghasilkan failure jika postcondition tidak tercapai;
- tidak mengubah timeout menjadi success.

### Gate 4 — Cancellation Nyata

Jalankan operasi tunggu panjang yang benar-benar aktif. Kirim `cancelAction` dari command kedua sebelum timeout.

Expected:

- target request berhenti sebelum timeout normal;
- target response `reason_code=cancelled`;
- cancellation response menunjukkan target ditemukan dan cancellation diterapkan;
- tidak ada aksi lanjutan setelah cancellation;
- heartbeat WebSocket tetap hidup;
- main looper responsif.

Membatalkan ID dummy dan menerima `cancelled:false` adalah negative test, bukan gate keberhasilan.

### Gate 5 — Durable Operation Barrier Lokal

Buktikan dua request berbeda dengan `operation_id` yang sama tidak dapat melakukan submit dua kali.

Catat dengan jujur bahwa barrier APK hanya pertahanan lokal dengan TTL dan hilang saat process death. Backend tetap menjadi sumber idempotensi durable lintas restart.

Jangan menyebut map in-memory sebagai durable storage.

### Gate 6 — Debug Frame Binary Nyata

`startDebugFrames` harus membuktikan lebih dari response status.

Wajib merekam:

- session ID;
- requested dan effective FPS;
- sequence frame meningkat;
- metadata dan binary payload berkorelasi;
- byte length lebih dari nol;
- JPEG/WebP dapat didekode;
- width dan height sesuai scale;
- queue bounded;
- drop-oldest berjalan saat consumer lambat;
- ACK timeout membersihkan in-flight frame;
- stop menghentikan producer;
- disconnect membersihkan job dan queue;
- tidak ada file frame dibuat di storage APK atau server lokal.

Setelah `stopDebugFrames`, observasi minimal dua interval frame dan buktikan counter tidak bertambah.

### Gate 7 — Bloat dan Stability Guard

Uji hierarchy besar, WebView, keyboard, system dialog, orientation change, dan multi-window.

Buktikan:

- depth dibatasi;
- jumlah node dibatasi;
- recursion berhenti sebelum membangun tree tanpa batas;
- tidak terjadi StackOverflowError atau OutOfMemoryError;
- AccessibilityNodeInfo yang perlu dilepas tidak ditahan lintas snapshot;
- registry dan frame queue tidak tumbuh tanpa batas;
- service tetap hidup setelah test berulang.

---

## H. Unit Test dan Static Gate

Gunakan toolchain resmi. Jangan memakai Gradle Wrapper.

```bat
set JAVA_HOME=C:\Users\dedy\tools\jdk\jdk-17.0.20.1+1
set ANDROID_HOME=C:\Users\dedy\tools\sdk
set ANDROID_SDK_ROOT=C:\Users\dedy\tools\sdk
call C:\Users\dedy\tools\gradle-8.9\bin\gradle.bat testDebugUnitTest lintDebug assembleDebug --rerun-tasks --no-daemon --console=plain
```

Wajib:

- seluruh unit test lulus;
- test benar-benar dieksekusi, bukan hanya `UP-TO-DATE`;
- lint tidak memiliki error release-blocking;
- debug APK terbentuk;
- `git diff --check` bersih.

Tambahkan regression test untuk:

- empty request ID;
- duplicate request ID;
- invalid deadline dan overflow;
- stale snapshot;
- wrong package;
- ambiguous selector;
- password redaction;
- empty hierarchy invalid;
- hierarchy node/depth budget;
- cancellation target aktif;
- operation barrier dengan request ID berbeda;
- debug queue capacity nol atau negatif;
- ACK expiration;
- streaming cleanup ketika disconnect;
- Protocol v1 backward compatibility.

Laporkan jumlah test dari XML result, bukan perkiraan.

---

## I. Real-Device Test Matrix

Gunakan APK debug dari source commit kandidat yang sama.

Karena signature debug berbeda dari GitHub-signed release, lakukan uninstall-install secara sadar:

```bat
adb -s 2c76b8a3 uninstall com.gososmed.agent
adb -s 2c76b8a3 install -r app\build\outputs\apk\debug\app-debug.apk
```

Setelah reinstall:

1. aktifkan kembali AccessibilityService jika Android menonaktifkannya;
2. pairing ke GoSosmed DEV;
3. pastikan foreground service hidup;
4. pastikan screen unlocked;
5. jangan menjalankan uiautomator2 atau inspector pihak ketiga bersamaan.

### Matrix wajib

| Vektor | Bukti lulus |
|---|---|
| Observe Settings | package, display, window, nodes, hash nyata |
| Password redaction | tidak ada teks rahasia pada payload/log/hash source |
| Resolve exact | satu candidate dan ancestor benar |
| Ambiguous selector | gagal tanpa tap |
| Stale snapshot | gagal `stale_snapshot` |
| Wrong package | gagal `wrong_package` |
| Native click | `used_gesture_fallback=false` dan layar berubah |
| Gesture fallback | hanya pada kasus yang sengaja diizinkan |
| Set text Unicode | teks Unicode masuk dan terbaca kembali |
| Wait success | node nyata muncul sebelum deadline |
| Wait timeout | timeout jujur |
| Cancellation aktif | operasi target benar-benar berhenti |
| Submit barrier | operation ID sama ditolak pada request kedua |
| Debug frame | beberapa binary frame diterima dan didekode |
| Stop frames | tidak ada frame setelah stop |
| Orientation | snapshot lama menjadi stale |
| Keyboard/system dialog | window lain terbaca tanpa salah klik |
| Process restart | reconnect terjadi dan state volatile dilaporkan jujur |
| Repetition | siklus observe-resolve-act minimal 100 kali tanpa service mati atau memory growth abnormal |

Ambil logcat terfilter selama test:

```bat
adb -s 2c76b8a3 logcat -c
adb -s 2c76b8a3 logcat -v threadtime GoAgentWS:V GoAgent:V AndroidRuntime:E *:S
```

Tidak boleh ada ANR, uncaught exception, process death, atau AccessibilityService restart yang tidak dijelaskan.

---

## J. Integrasi dengan GoSosmed DEV

Repository backend tidak boleh diedit dari sesi ini. Gunakan hanya environment DEV yang sudah disiapkan tim backend.

Validasi:

- APK melapor `protocol_versions: [1,2]`;
- backend DEV mengenali versi APK `1.0.0` setelah konfigurasi latest version diperbarui oleh agent backend;
- device online di DEV;
- observe dan debug frames tampil pada inspector DEV;
- action trace memiliki request ID, snapshot ID, node ID, before/after fingerprint, reason code, dan timing;
- tidak ada frame debug masuk ke database atau filesystem permanen;
- satu device hanya menjalankan satu mutation aktif.

### Publish test terkendali

Jangan langsung menjalankan lima platform paralel.

Urutan:

1. satu post baru;
2. satu target Threads;
3. buktikan remote receipt, target, job, dan aggregate post konsisten;
4. pastikan replay operation yang sama tidak membuat publikasi kedua;
5. lanjutkan platform lain secara berurutan;
6. hentikan bila outcome menjadi unknown dan lakukan reconciliation, bukan blind retry.

Jangan memakai data produksi dan jangan mengubah backend PROD.

---

## K. Anti-Bloat dan Dead-Code Gate

Sebelum commit:

1. Bandingkan seluruh file baru dengan fungsi yang sudah ada.
2. Hapus duplicate abstraction dan wrapper satu-baris yang tidak memberi boundary nyata.
3. Pastikan tidak ada dua sumber kebenaran untuk snapshot, cancellation, mutation guard, atau frame queue.
4. Pastikan collection mempunyai capacity dan TTL.
5. Pastikan coroutine job dibatalkan saat disconnect, stop service, dan destroy.
6. Pastikan bitmap, byte array, dan AccessibilityNodeInfo tidak dipertahankan lebih lama dari kebutuhan.
7. Pastikan komentar menjelaskan invariant, bukan mengulang kode.
8. Pastikan test helper tidak masuk APK produksi bila tidak diperlukan.
9. Jalankan pencarian unused imports, unreachable branches, obsolete version comments, dan old dummy code.
10. Laporkan file yang dihapus atau digabung beserta alasan.

Jangan menambah kode untuk kemungkinan hipotetis. Pertahankan implementasi minimum yang memenuhi kontrak dan test nyata.

---

## L. Dokumentasi Rilis

Perbarui secara konsisten:

- `CHANGELOG.md`
- `README.md` jika menyebut versi
- `docs/AGENT-COMMAND-CONTRACT.md`
- `docs/5-testing/17-AUDIT-KOTLIN-PROTOCOL-V2-2026-10-05.md`
- fixture di `docs/contracts/`

Kontrak harus membedakan:

- capability tersedia;
- capability diuji JVM;
- capability diuji perangkat;
- capability diuji end-to-end melalui backend DEV;
- limitation yang masih ada.

Jangan menaruh klaim “100% production ready” tanpa bukti signed release artifact dan smoke test.

---

## M. Commit dan GitHub Release v1.0.0

Sebelum staging:

```bat
git status --short
git diff --check
git diff --stat
git diff
```

Stage file secara eksplisit. Jangan `git add .`.

Buat tepat satu commit terfokus setelah seluruh gate lulus:

```text
feat(protocol-v2): release deterministic Android control plane v1.0.0
```

Push `main`, lalu pastikan CI push hijau.

Buat tag stable hanya jika:

- local build hijau;
- unit dan lint hijau;
- real-device matrix lulus;
- test DEV lulus;
- changelog menyebut bukti serta limitation;
- tidak ada blocker tersisa.

Tag:

```bat
git tag v1.0.0
git push origin v1.0.0
```

Monitor workflow `release.yml` sampai sukses. Wajib buktikan:

- release bukan prerelease;
- APK signed;
- `apksigner verify --print-certs` lulus;
- `SHA256SUMS.txt` tersedia;
- checksum APK sesuai;
- release notes berasal dari heading `1.0.0`.

Jangan mengunggah APK lokal secara manual ke GitHub Release.

---

## N. Smoke Test Artifact GitHub-Signed

Setelah GitHub Release `v1.0.0` selesai:

1. unduh APK dari release resmi;
2. verifikasi SHA-256;
3. verifikasi signature;
4. uninstall APK debug karena signature berbeda;
5. install APK release resmi;
6. aktifkan AccessibilityService kembali jika perlu;
7. pairing ulang ke DEV;
8. jalankan smoke test observe, actAndVerify, cancellation aktif, dan debug binary frames;
9. pastikan dumpsys menunjukkan `versionCode=30` dan `versionName=1.0.0`;
10. pastikan tidak ada crash pada logcat.

Release belum dianggap selesai hanya karena workflow hijau. Signed artifact harus diuji pada perangkat.

---

## O. Stop Conditions

Hentikan release dan laporkan sebagai BLOCKED jika salah satu terjadi:

- unit test atau lint gagal;
- binary frame tidak terbukti diterima;
- cancellation hanya menghasilkan `cancelled:false`;
- false success ditemukan;
- service crash atau ANR;
- snapshot password tidak teredaksi;
- node ambigu tetap diketuk;
- operation yang sama dapat submit dua kali;
- memory atau queue tumbuh tanpa batas;
- CI gagal;
- APK tidak signed;
- version metadata bukan `1.0.0` dan `30`;
- signed artifact tidak dapat dipasang atau gagal smoke test.

Jangan menutupi blocker dengan dokumentasi.

---

## P. Format Laporan Akhir Agent

Laporkan dengan format berikut:

```markdown
# Hasil Release v1.0.0

## Outcome
- Status: PASSED / BLOCKED
- Commit:
- Tag:
- GitHub Release:
- APK SHA-256:
- Signing certificate:

## Perubahan
- File dan invariant utama.

## Anti-Bloat
- Kode duplikat/dead yang dihapus.
- Alasan setiap file baru tetap diperlukan.

## Quality Gates
| Gate | Command/Test | Hasil | Evidence |

## Real Device
- Device dan Android version.
- APK version dari dumpsys.
- Ringkasan setiap vektor.

## DEV End-to-End
- Device online.
- Observe/action/frame evidence.
- Publish test dan receipt.
- Idempotency result.

## Signed Artifact Smoke Test
- Install result.
- Signature result.
- Runtime smoke result.

## Remaining Limitations
- Fakta yang belum diuji.
```

Jika ada satu langkah tidak dijalankan, tulis jelas `NOT VERIFIED`. Jangan menggantinya dengan asumsi.