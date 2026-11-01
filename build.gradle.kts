plugins {
    // [M3 Expressive · Path A 2026-10-27] 因 Kotlin 2.4.20 暂无对应 KSP，且 kapt 与 AGP 9.x 内置 Kotlin
    // 不兼容，故在 gradle.properties 关闭 android.builtInKotlin / android.newDsl，回退外部 Kotlin 插件模型。
    // Kotlin 版本由本处 version 锁定 2.4.20。
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    // [M3 Expressive · Path A 2026-10-27] Hilt 2.56.2 的 Gradle 插件硬引用 AGP 已删除的
    // BaseExtension，与 AGP 9.x 不兼容（报 "Android BaseExtension not found"）。升到 2.60.1，
    // 该版本改用 AndroidComponentsExtension，支持 AGP 9.x。
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
    // [Phase 3 2026-10-24] kapt → KSP（K2 原生注解处理；kapt 在 K2 下 fallback 1.9 语言模式）
    id("com.google.devtools.ksp") version "2.1.21-2.0.2" apply false
    // [K2 migration 2026-10-24] Compose 编译器改为 K2 插件（与 Kotlin 版本对齐），
    // 替代旧的 composeOptions.kotlinCompilerExtensionVersion 方式
    // [M3 Expressive · Path A 2026-10-27] Kotlin 2.1.21 → 2.4.20：material3 1.5.x（官方 Expressive）基于 Compose 1.12.x，需 Kotlin 2.4.x 编译器
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
