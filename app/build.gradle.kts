plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gososmed.agent"
    compileSdk = 34

    // P0-1 (Plan 07): signing material TIDAK lagi tinggal di repo. Keystore
    // lama (keystore/gososmed-release.jks, password pernah in-repo) PERNYAH
    // PUBLIK → dinyatakan MATI dan tidak dipakai lagi; APK lama tidak bisa
    // upgrade ke APK baru (signature beda) → uninstall-install ulang.
    // Release kini ditandatangani dari environment:
    //   GOSOSMED_KEYSTORE_FILE, GOSOSMED_STORE_PASSWORD,
    //   GOSOSMED_KEY_ALIAS,   GOSOSMED_KEY_PASSWORD
    // — diisi workflow release dari GitHub Secrets. Build lokal tanpa env
    // tersebut menghasilkan APK release TIDAK bertanda tangan (unsigned);
    // workflow menolaknya lewat `apksigner verify` sebelum rilis.
    signingConfigs {
        val envKsPath = System.getenv("GOSOSMED_KEYSTORE_FILE")
        val envStorePass = System.getenv("GOSOSMED_STORE_PASSWORD")
        val envKeyPass = System.getenv("GOSOSMED_KEY_PASSWORD")
        val envKeyAlias = System.getenv("GOSOSMED_KEY_ALIAS") ?: "gososmed"
        if (envKsPath != null && envStorePass != null && envKeyPass != null) {
            create("gososmed") {
                storeFile = file(envKsPath)
                storePassword = envStorePass
                keyAlias = envKeyAlias
                keyPassword = envKeyPass
            }
        }
    }

    defaultConfig {
        applicationId = "com.gososmed.agent"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "0.7.1"
        // URL agenthub produksi sebagai default — user TIDAK perlu mengetik
        // URL server. Bisa dioverride di mode debug. Deep link
        // gososmed://pair?ws=... tetap bisa membawa URL lain (dev/LAN).
        buildConfigField("String", "DEFAULT_WS_URL", "\"wss://api.bamsbung.id/v1/agent/ws\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("gososmed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    // OkHttp for outbound WebSocket to the GoSosmed agenthub.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // P1-1 (Plan 07): unit test JVM pertama (HierarchySerializer/AgentCommand
    // jalur service-null) — dijalankan CI step testDebugUnitTest.
    testImplementation("junit:junit:4.13.2")
    // org.json di android.jar adalah stub yang melempar "not mocked" di JVM
    // unit test; artifact asli menggantikan stub agar JSONObject bekerja.
    testImplementation("org.json:json:20240303")
}
