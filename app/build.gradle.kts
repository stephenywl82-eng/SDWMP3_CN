import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.dagger.hilt.android")
    id("kotlin-parcelize")
}

// Load keystore properties
val keystoreProps = Properties()
val keystorePropsFile = rootProject.file("keystore.properties")
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { stream -> keystoreProps.load(stream) }
}

android {
    namespace = "com.sdw.music.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sdw.music.player.cn"
        minSdk = 24
        targetSdk = 35
        versionCode = 51
        versionName = "8.36"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // Signing config from keystore.properties
        val ksStoreFile = keystoreProps.getProperty("storeFile")
        val ksStorePassword = keystoreProps.getProperty("storePassword")
        val ksKeyAlias = keystoreProps.getProperty("keyAlias")
        val ksKeyPassword = keystoreProps.getProperty("keyPassword")
        signingConfigs {
            create("release") {
                if (ksStoreFile != null && file(ksStoreFile).exists()) {
                    storeFile = file(ksStoreFile)
                    storePassword = ksStorePassword ?: ""
                    keyAlias = ksKeyAlias ?: ""
                    keyPassword = ksKeyPassword ?: ""
                } else {
                    // Fallback for CI / missing keystore.properties
                    storeFile = file("debug.keystore")
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
            // Same fallback keystore for debug �?debug APKs install -r over the
            // release build (shared signing) without an uninstall.
            getByName("debug") {
                storeFile = file("debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
        }
        create("benchmark") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // prefab disabled: oboe is linked via the prebuilt liboboe.so in jniLibs
        // (the published oboe 1.8.0 prefab only carries NDK r21 artifacts and
        // rejects newer NDKs). Keep the oboe dependency for completeness but do
        // not process its prefab.
        prefab = false
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    /* CMake disabled in Gradle to avoid OOM on this 8GB box. liboboe_bridge.so
       is compiled STANDALONE via NDK r25b (E:\SDWMP3\cpp_build) from the current
       cpp sources (16 URBs, kPacketsPerUrb=64, claimInterface(1) forces alt=1 on
       the OUT interface) and shipped via jniLibs. Rebuild: cmake -G Ninja with
       the NDK android.toolchain.cmake, then llvm-strip, then copy into jniLibs. */
    /*
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    */
}

dependencies {
    // Compose BOM �?unified versions
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)

    // Compose Core
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-android-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Media3 (Phase C: media3-exoplayer removed; session+common kept for MediaSession)
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media:media:1.7.0")

    // Coil (Compose image loading)
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Palette (dynamic color from album art)
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Oboe (via prefab)
    implementation("com.google.oboe:oboe:1.8.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Glide (for some bitmap ops)
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Audio Quality Analyzer
    implementation(project(":analyzer"))
}
