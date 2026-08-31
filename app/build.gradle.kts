plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gososmed.agent"
    compileSdk = 34

    // Stable signature across builds: the keystore is committed to the repo
    // so the same APK signature is always produced. This removes
    // INSTALL_FAILED_UPDATE_INCOMPATIBLE and the need to uninstall before
    // installing a new build on a device. NOTE: this is an INTERNAL dev
    // keystore (password in-repo) for the P0 agent only — a production build
    // still needs Play App Signing + a secret-held signing key (§7.5.2).
    signingConfigs {
        create("gososmed") {
            storeFile = rootProject.file("keystore/gososmed-release.jks")
            storePassword = "gososmed123"
            keyAlias = "gososmed"
            keyPassword = "gososmed123"
        }
    }

    defaultConfig {
        applicationId = "com.gososmed.agent"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-p0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("gososmed")
        }
        debug {
            signingConfig = signingConfigs.getByName("gososmed")
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
}
