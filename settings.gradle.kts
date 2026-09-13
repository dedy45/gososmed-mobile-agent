pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // v0.9.0 — transport ADB lokal memakai `libadb-android`, yang hanya
        // diterbitkan lewat JitPack (TIDAK ada di Maven Central; terverifikasi:
        // kueri `libadb` mengembalikan numFound=0). WAJIB di sini, bukan di
        // app/build.gradle.kts, karena proyek memakai
        // RepositoriesMode.FAIL_ON_PROJECT_REPOS.
        // Bukti: docs/F0-LIBRARY-VALIDATION.md
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "gososmed-mobile-agent"
include(":app")
