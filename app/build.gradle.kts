import java.util.Properties

plugins {
    // [M3 Expressive · Path A 2026-10-27] gradle.properties 已关 android.builtInKotlin/android.newDsl，
    // 回退外部 Kotlin 插件模型（Kotlin 2.4.20 暂无对应 KSP，且 kapt 与内置 Kotlin 不兼容），故恢复该插件。
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("kotlin-parcelize")
    // [K2 migration 2026-10-24] Compose K2 编译器插件（替代旧 composeOptions 方式）
    id("org.jetbrains.kotlin.plugin.compose")
    // [Phase 3 回退] KSP 在 K2/Hilt 2.52 下存在非确定性 flake（Hilt 多轮生成只跑首轮即中止，
    // 报 Expected @AndroidEntryPoint）。切回稳定的 kapt 处理 Hilt 注解，彻底绕开该 flake。
    id("org.jetbrains.kotlin.kapt")
}

// Load keystore properties
val keystoreProps = Properties()
val keystorePropsFile = rootProject.file("keystore.properties")
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { stream -> keystoreProps.load(stream) }
}

android {
    namespace = "com.sdw.music.player"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sdw.music.player.cn"
        minSdk = 24
        targetSdk = 35
        versionCode = 937
        versionName = "9.21"   // 【V9.21】3D 封面流（Cover Flow）+ 播放时常亮 + 交叉淡化落点台阶修复：B 轨预增益(preGain∈[0.6,1.0])原在落点硬阶跃回 1.0（最大+4.4dB，听感"音量一下子大一下"）。改为落点后 250ms 内由 1:1 直读出口把 output 按 preGain→1.0 线性爬坡，台阶摊平为无感斜坡。

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
                    // [M3 Expressive · Path A 2026-10-27] AGP 9.x 不再支持 proguard-android.txt（含 -dontoptimize，
                    // 会阻止 R8 优化）。改用 proguard-android-optimize.txt；若 R8 激进优化导致运行时问题，
                    // 可在 proguard-rules.pro 临时加 -dontoptimize 兜底。
                    getDefaultProguardFile("proguard-android-optimize.txt"),
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

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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

    /* [K2 migration 2026-10-24] composeOptions 已移除：
       Compose 编译器改由 org.jetbrains.kotlin.plugin.compose 插件提供（版本与 Kotlin 对齐）。
       原配置：composeOptions { kotlinCompilerExtensionVersion = "1.5.15" } */

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
    // [M3 Expressive · Path A 2026.10.27] compose-bom 2025.10.00 (Compose 1.9.x) 与 material3 1.5.x 不兼容
    //（1.5.x 要求 Compose 1.12.x）。升到 2026.09.00（Compose 1.12.1）对齐，否则 BOM 版本错位。
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    // Compose Core
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // [M3 Expressive · Path A 2026-10-27] material3 1.5.0-alpha22：Expressive API
    //（MaterialExpressiveTheme / expressive*ColorScheme / MotionScheme）在此版本公开（@OptIn(ExperimentalMaterial3ExpressiveApi)）。
    // 注：用户提出的 1.5.0-beta02 不在本地缓存且可能要求不同 BOM；alpha22 与 beta02 API 面一致且已验证依赖可解，先用它点亮官方 Expressive。
    implementation("androidx.compose.material3:material3:1.5.0-alpha22")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.8.0")
    // [V8.97] navigation-compose 2.8.0 璧疯繍琛屾湡渚濊禆 kotlinx-serialization-core锛?    // 鏄惧紡閿佺増鏈紝閬垮厤浼犻€掍緷璧栨紓绉诲埌涓?Kotlin 1.9.25 涓嶅尮閰嶇殑鐗堟湰銆?    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.6.3")

    // Hilt（注解处理切回 kapt 以绕开 KSP 2.x + Hilt 多轮生成 flake；2.60.1 以匹配 AGP 9.x
    // 内置 Kotlin 并去掉对 AGP 已删除 BaseExtension 的硬依赖）
    implementation("com.google.dagger:hilt-android:2.60.1")
    kapt("com.google.dagger:hilt-android-compiler:2.60.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Media3 (Phase C: media3-exoplayer removed; session+common kept for MediaSession)
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media:media:1.7.0")

    // Coil (Compose image loading)
    implementation("io.coil-kt:coil-compose:2.7.0")

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
