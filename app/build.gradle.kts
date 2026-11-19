import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

// version: app/src/main/python/nexusvpn/paths.py; build number: build_info.py (GitHub Actions run number)
val py = File(projectDir, "src/main/python/nexusvpn")
val appVersion = Regex("APP_VERSION = \"([\\d.]+)\"").find(File(py, "paths.py").readText())!!.groupValues[1]
val buildNumber = Regex("BUILD = (\\d+)").find(File(py, "build_info.py").readText())?.groupValues?.get(1)?.toInt() ?: 0

android {
    namespace = "pro.nexusvpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "pro.nexusvpn.mobile"
        minSdk = 24
        targetSdk = 36
        versionCode = maxOf(1, buildNumber)
        versionName = "$appVersion.$buildNumber"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            // GitHub secrets ANDROID_KEYSTORE_*; otherwise the key in the repository (same key = updates install over)
            storeFile = file(System.getenv("NEXUS_KEYSTORE") ?: "${rootDir}/nexusvpn.keystore")
            storePassword = System.getenv("NEXUS_KEYSTORE_PASSWORD") ?: "nexusvpn"
            keyAlias = System.getenv("NEXUS_KEY_ALIAS") ?: "nexusvpn"
            keyPassword = System.getenv("NEXUS_KEY_PASSWORD") ?: "nexusvpn"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        pip {
            install("certifi")
        }
    }
}

dependencies {
    implementation(files("libs/libbox.aar"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
