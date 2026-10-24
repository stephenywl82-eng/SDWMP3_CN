plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false
    // [Phase 3 2026-10-24] kapt → KSP（K2 原生注解处理；kapt 在 K2 下 fallback 1.9 语言模式）
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    // [K2 migration 2026-10-24] Compose 编译器改为 K2 插件（与 Kotlin 版本对齐），
    // 替代旧的 composeOptions.kotlinCompilerExtensionVersion 方式
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
