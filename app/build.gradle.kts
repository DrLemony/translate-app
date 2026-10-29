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
        versionCode = 1
        versionName = "1.0"
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
