plugins {
    // [M3 Expressive · Path A 2026-10-27] gradle.properties 已关 android.builtInKotlin/android.newDsl，
    // 回退外部 Kotlin 插件模型，故恢复 org.jetbrains.kotlin.android。
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sdw.audio.analyzer"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    /* CMake disabled to avoid OOM on 8GB box — libaudio_analyzer.so is shipped
       via jniLibs (prebuilt stripped .so). Rebuild native manually if fft_analyzer
       sources change. */
    /*
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    */
    ndkVersion = "25.1.8937393"
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}

