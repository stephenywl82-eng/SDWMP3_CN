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
        versionCode = 919
        versionName = "9.19"    // 【V9.19】修扫描「永远卡在一处」：① readFlacVorbisComments 加退化护栏（blockType=0&dataLength=0&非末块→break，防损坏/全零 FLAC 死循环）；② probeDurationMs 改用独立线程+8s 硬超时（MediaMetadataRetriever.setDataSource 对特殊字符路径/损坏文件会原生挂死、无取消手段，旧实现走 MediaProbe 单线程会拖垮整轮扫描）；③ 扫描循环对 probeDurationMs 与 readBpmAndKey 各加 8s withTimeoutOrNull 兜底+记日志。纯 Kotlin 改动，不碰 .so；蓝牙/环绕修复与 V9.18 引擎不变。

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
            // Same fallback keystore for debug 锟?debug APKs install -r over the
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
            // 【V9.01】关闭资源收缩：R8 收缩器在激进优化下会把部分只被 Compose stringResource 引用的
            // 多语言字符串（如 settings_engine_dsp / settings_dac_mseb 的 values-zh 变体）误判为不可达而裁掉，
            // 仅保留英文默认，导致中文缺失。关掉资源收缩可保住全部 locale 字符串（仅 APK 略增，对本播放器无碍）。
            isShrinkResources = false
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
        aidl = true
        // prefab disabled: oboe is linked via the prebuilt liboboe.so in jniLibs
        // (the published oboe 1.8.0 prefab only carries NDK r21 artifacts and
        // rejects newer NDKs). Keep the oboe dependency for completeness but do
        // not process its prefab.
        prefab = false
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
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
    // Compose BOM 锟?unified versions
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
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
    implementation("androidx.navigation:navigation-compose:2.8.0")
    // [V8.97] navigation-compose 2.8.0 璧疯繍琛屾湡渚濊禆 kotlinx-serialization-core锛?    // 鏄惧紡閿佺増鏈紝閬垮厤浼犻€掍緷璧栨紓绉诲埌涓?Kotlin 1.9.25 涓嶅尮閰嶇殑鐗堟湰銆?    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.6.3")

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
