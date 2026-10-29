plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.efe.ekrancevir"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.efe.ekrancevir"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        // Sadece ARM işlemciler (tabletler): APK çok daha küçük olur
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        getByName("debug") {
            // Huawei/HarmonyOS kurulum aracı için eski (v1) imzayı da ekle
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Cihaz içi (bundled) yazı okuma: Google Play Hizmetleri gerektirmez
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
