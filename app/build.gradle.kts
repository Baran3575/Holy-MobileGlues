plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.holy.mobileglues"
    compileSdk = 36
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.holy.mobileglues"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0-holy"

        // Sadece arm64-v8a — kullanıcının isteği, APK boyutu ve build süresi düşer
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug imzalama otomatik — CI'da keystore yoksa unsigned değil, debug ile imzalanır
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }

    // Sadece arm64 — bundle/split gerekmez, tek APK
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    val bom = platform(libs.androidx.compose.bom)
    implementation(bom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
}

// Sadece arm64-v8a — abiFilters ndk'da, ek filtre gerekmez

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
