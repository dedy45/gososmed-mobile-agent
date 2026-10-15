# Panduan Toolchain & Kompilasi APK

Dokumentasi ini menjelaskan konfigurasi toolchain lokal yang selaras dengan Continuous Integration (CI) GitHub Actions repository ini (`.github/workflows/build.yml` dan `.github/workflows/release.yml`).

---

## 1. Spesifikasi Toolchain Standar

Untuk memastikan hasil kompilasi lokal konsisten dan 100% identik (*byte-level parity*) dengan pipeline GitHub Actions, gunakan kombinasi versi berikut:

| Komponen | Versi Rekomendasi | Keterangan |
|---|---|---|
| **Java Development Kit (JDK)** | **Temurin JDK 17** (17.0.x) | Sesuai `actions/setup-java@v4` di CI |
| **Gradle** | **Gradle 8.9** | Versi resmi yang divalidasi dengan Android Gradle Plugin (AGP) 8.5.2 |
| **Android SDK Platform** | **Android 14 (API level 34)** | `compileSdk = 34`, `targetSdk = 34` |
| **Android Build-Tools** | **34.0.0** | Menyediakan `aapt2`, `zipalign`, dan `apksigner` |
| **Android Platform-Tools** | Versi terbaru | Menyediakan `adb` |

---

## 2. Konfigurasi Environment Variable

Repository ini **tidak menyertakan Gradle Wrapper binary (`gradlew`)** demi keamanan repositori publik. Gunakan instalasi Gradle sistem atau lokal setelah menyetel variabel lingkungan.

### A. Windows (Command Prompt / Batch)

```bat
set JAVA_HOME=C:\path\to\jdk-17
set ANDROID_HOME=C:\path\to\android-sdk
set ANDROID_SDK_ROOT=%ANDROID_HOME%
set PATH=%JAVA_HOME%\bin;C:\path\to\gradle-8.9\bin;%ANDROID_HOME%\platform-tools;%PATH%
```

### B. Windows (PowerShell)

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-17"
$env:ANDROID_HOME = "C:\path\to\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:JAVA_HOME\bin;C:\path\to\gradle-8.9\bin;$env:ANDROID_HOME\platform-tools;" + $env:Path
```

### C. Linux / macOS / Bash

```bash
export JAVA_HOME="/path/to/jdk-17"
export ANDROID_HOME="/path/to/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:/path/to/gradle-8.9/bin:$ANDROID_HOME/platform-tools:$PATH"
```

---

## 3. Verifikasi Kesiapan Toolchain

Jalankan perintah berikut pada terminal:

```bash
java -version
# Output harus menunjukkan: openjdk version "17.0.x"

gradle --version
# Output harus menunjukkan: Gradle 8.9, JVM 17.0.x
```

---

## 4. Perintah Kompilasi & Pengujian

### A. Menjalankan Seluruh Unit Test (JVM)
```bash
gradle testDebugUnitTest --no-daemon
```

### B. Menjalankan Unit Test Spesifik
```bash
gradle testDebugUnitTest --tests "*ProtocolV2IntegrationTest*" --no-daemon
```

### C. Kompilasi APK Debug
```bash
gradle assembleDebug --no-daemon
```
Artefak keluaran: `app/build/outputs/apk/debug/app-debug.apk`

### D. Kompilasi APK Release (Memerlukan Konfigurasi Keystore)
```bash
gradle assembleRelease --no-daemon
```
Artefak keluaran: `app/build/outputs/apk/release/app-release.apk`

### E. Verifikasi Tanda Tangan APK (Signature Check)
```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

---

## 5. Tips Kestabilan Kompilasi

1. **Proxy Jaringan:** Jika proses kompilasi atau resolusi Maven menggantung (*hang*) saat mengunduh dependensi, pastikan variabel proxy seperti `HTTP_PROXY`, `HTTPS_PROXY` dinonaktifkan atau dikonfigurasi secara tepat.
2. **Flag `--no-daemon`:** Selalu gunakan opsi `--no-daemon` pada mesin CI atau saat menjalankan skrip otomatisasi untuk mencegah penguncian berkas (*file locking*) pada sistem operasi Windows.
3. **Pembersihan Cache:** Jika terjadi inkonsistensi task Gradle, jalankan perintah pembersihan:
   ```bash
   gradle clean --no-daemon
   ```
