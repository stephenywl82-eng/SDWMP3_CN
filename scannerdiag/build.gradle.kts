plugins {
    // [M3 Expressive · Path A 2026-10-27] gradle.properties 已关 android.builtInKotlin/android.newDsl，
    // 回退外部 Kotlin 插件模型，故恢复 org.jetbrains.kotlin.android。
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sdw.scannerdiag"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sdw.scannerdiag"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 诊断包用 debug 签名即可安装（与主程序 applicationId 不同，不会冲突）
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
