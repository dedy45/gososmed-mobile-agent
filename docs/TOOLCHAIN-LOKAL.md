# Toolchain Build APK — Lokal (Windows)

> **Baca ini sebelum mencari JDK/Gradle/SDK sendiri.**
> Semua alat build APK **sudah terpasang** di mesin ini. Jangan unduh ulang —
> cukup setel environment, lalu panggil `gradle`.
>
> Diperbarui: 2026-09-14 · Berlaku untuk repo `gososmed-mobile-agent`.

---

## 1. Satu tempat, satu versi

Toolchain dikonsolidasi di **`C:\Users\dedy\tools`**. Ini satu-satunya lokasi
yang benar.

| Komponen | Versi | Lokasi |
|---|---|---|
| **JDK** | Temurin **17.0.20.1+1** | `C:\Users\dedy\tools\jdk\jdk-17.0.20.1+1` |
| **Gradle** | **8.9** | `C:\Users\dedy\tools\gradle-8.9` |
| **Android SDK** | Platform **34**, Build-tools **34.0.0** | `C:\Users\dedy\tools\sdk` |
| **Kotlin compiler** | (kotlinc) | `C:\Users\dedy\tools\kotlin\kotlinc` |

Versi ini **sengaja disamakan dengan CI** (`.github/workflows/build.yml`:
JDK 17 + Gradle 8.9). Build lokal dan CI karena itu menghasilkan APK yang
sebanding — beda versi toolchain = beda perilaku build yang membingungkan.

### Isi Android SDK

```
sdk/
├── build-tools/34.0.0     ← apksigner ada di sini
├── platforms/android-34   ← compileSdk 34 (sesuai build.gradle.kts)
├── platform-tools/        ← adb.exe
├── cmdline-tools/latest/  ← sdkmanager
└── licenses/              ← SUDAH diterima (7 file)
```

Lisensi SDK **sudah diterima**, jadi `sdkmanager` tidak akan meminta persetujuan
ulang. Jangan hapus folder `licenses/`.

---

## 2. Cara pakai — setel environment dulu

Repo ini **TIDAK punya Gradle wrapper** (`gradlew`/`gradlew.bat` tidak ada).
Karena itu `./gradlew` yang tertulis di banyak README lama **akan gagal**.
Pakai `gradle` dari `tools/` setelah environment disetel.

### Skrip siap pakai

File **`gradle-run.sh`** di `C:\Users\dedy\gradle-run.sh` sudah melakukan semua
pengaturan di bawah. Pakai itu kalau tidak ingin mengingat variabelnya:

```bash
bash /c/Users/dedy/gradle-run.sh testDebugUnitTest assembleDebug
```

Skrip itu otomatis: setel `JAVA_HOME`/`ANDROID_HOME`, menambahkan Gradle ke
`PATH`, mematikan proxy yang mengganggu unduhan dependensi, `cd` ke repo, dan
menjalankan `gradle` dengan `--no-daemon`.

### Setel manual (kalau perlu)

```bash
export JAVA_HOME="C:/Users/dedy/tools/jdk/jdk-17.0.20.1+1"
export PATH="$JAVA_HOME/bin:/c/Users/dedy/tools/gradle-8.9/bin:$PATH"
export ANDROID_HOME="C:/Users/dedy/tools/sdk"
export ANDROID_SDK_ROOT="C:/Users/dedy/tools/sdk"

cd /c/Users/dedy/Documents/gososmed-mobile-agent
gradle testDebugUnitTest assembleDebug --no-daemon --console=plain
```

### Kenapa proxy dimatikan

`gradle-run.sh` men-unset `http_proxy`/`https_proxy` dan
`CODEBUDDY_SERVICE_PROXY_URL`. Proxy aktif membuat unduhan dependensi Gradle
menggantung atau gagal. Kalau build macet di tahap "Download ...", cek
variabel ini dulu sebelum menyalahkan kode.

### Kenapa `CODEBUDDY_SAFE_DELETE_*` di-unset

Shim hapus-aman WorkBuddy dapat menimbulkan deadlock di
`java.io.WinNTFileSystem.delete0` saat Gradle membersihkan direktori build.
Ini bukan masalah kode APK — matikan shim-nya untuk build.

---

## 3. Verifikasi toolchain masih sehat

```bash
export JAVA_HOME="C:/Users/dedy/tools/jdk/jdk-17.0.20.1+1"
export PATH="$JAVA_HOME/bin:/c/Users/dedy/tools/gradle-8.9/bin:$PATH"

java -version    # openjdk version "17.0.20.1"
gradle --version # Gradle 8.9, Launcher JVM 17.0.20.1
```

Kalau dua perintah itu menjawab dengan versi di atas, toolchain siap.

---

## 4. Perintah yang benar-benar dipakai

| Maksud | Perintah |
|---|---|
| Unit test saja | `gradle testDebugUnitTest --no-daemon` |
| Test satu kelas | `gradle testDebugUnitTest --tests "*AdbTlsProviderTest*" --no-daemon` |
| Debug APK | `gradle assembleDebug --no-daemon` |
| Release APK (butuh env keystore) | `gradle assembleRelease --no-daemon` |
| Verifikasi tanda tangan | `"$ANDROID_HOME"/build-tools/34.0.0/apksigner verify --print-certs app/build/outputs/apk/release/*.apk` |

> Build pertama kali memakan waktu lama (mengunduh dependensi + komponen SDK).
> Build berikutnya jauh lebih cepat.

---

## 5. Sisa unduhan yang boleh dibersihkan

Arsip pemasang sudah diekstrak dan **tidak dipakai lagi**. Total ±546 MB yang
aman dihapus bila disk perlu:

| Arsip | Ukuran |
|---|---|
| `C:\Users\dedy\tools\jdk\jdk.tar.gz` | 182 MB |
| `C:\Users\dedy\tools\cmdline.zip` | 147 MB |
| `C:\Users\dedy\tools\gradle.zip` | 130 MB |
| `C:\Users\dedy\tools\kotlin\kotlin.zip` | 87 MB |

Folder kosong yang tidak terpakai: `tools\jdk\x`, `tools\syntaxcheck`.

> **Jangan hapus** `tools\gradle-8.9`, `tools\jdk\jdk-17.0.20.1+1`,
> `tools\sdk`, atau `tools\kotlin\kotlinc` — itu toolchain yang hidup.

---

## 6. Tempat lain yang *bukan* toolchain

Supaya tidak bingung saat mencari:

| Lokasi | Isi sebenarnya |
|---|---|
| `C:\Users\dedy\adb` | Salinan platform-tools lama (Juli 2024). Duplikat `sdk\platform-tools` — bukan toolchain build. |
| `C:\Users\dedy\jtest` | Coretan uji Java sekali pakai. Sampah. |
| `C:\Users\dedy\Documents\android` | Firmware/ROM perangkat + scrcpy. **Tidak ada hubungannya** dengan build APK. |
| `C:\Users\dedy\miniconda3` | Python, bukan toolchain Android. |
| `C:\Users\dedy\.workbuddy-ai\binaries` | Node/Python/Git milik WorkBuddy. Bukan toolchain Android. |

---

## 7. Aturan untuk agent

1. **Jangan mengunduh** JDK/Gradle/SDK baru — semua sudah ada di `C:\Users\dedy\tools`.
2. **Jangan menjalankan `./gradlew`** — wrapper tidak ada di repo ini.
3. **Setel environment dulu** (pakai `gradle-run.sh` paling aman) sebelum `gradle`.
4. **Jangan hapus** isi `tools\` kecuali arsip di §5.
5. Build APK yang menyentuh `tools\` **tidak perlu CI** — bisa diverifikasi
   lokal. Ini menghemat antrian GitHub Actions.
