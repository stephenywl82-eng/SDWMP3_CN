package com.sdw.music.player.util

/**
 * 机型代号 → 市场名称映射表
 * 将 Motorola 机型代号（如 XT2401-2）转换为市场名称（如 ThinkPhone 25）
 */
object DeviceMapper {

    private val deviceMap = mapOf(
        // === 2026 ===
        "XT2653" to "Razr 70 Ultra",
        "XT2651" to "Razr 70",
        "XT2651-4" to "Razr fold",
        "XT2601" to "Edge 70 Ultra",
        "XT2601-1" to "Edge 70",
        "XT2637" to "Edge 70 Pro",
        "XT2607" to "Edge 70 Pro",
        "motorola edge 70 pro+" to "Edge 70 Pro+",
        "XT2603" to "Signature",

        // === 2025 ===
        "XT2553" to "Razr Ultra 2025",
        "XT2553-1" to "Razr Ultra 2025",
        "XT2553-2" to "Razr Ultra 2025",
        "XT2553-3" to "Razr Ultra 2025",
        "XT2551" to "Razr 60",
        "XT2551-3" to "Razr 60 Ultra",
        "XT2501" to "Edge 60 Ultra",
        "XT2501-5" to "Edge 60 Ultra",
        "XT2507-5" to "Edge 60 Pro",
        "XT2503" to "ThinkPhone 25 (2025)",
        "XT2533" to "G67 Power",
        "XT2533-4" to "G67 Power",
        "XT2537" to "Edge 50 Neo",
        "XT2511" to "Moto G Stylus 5G",

        // === 2024 ===
        "XT2451" to "Razr 50 Ultra",
        "XT2453" to "Razr 50",
        "XT2401" to "Edge 50 Ultra",
        "XT2403" to "Edge 50 Pro",
        "XT2437" to "Edge 50 Fusion",
        "XT2409" to "Edge 50 Neo",
        "XT2411" to "Moto G Stylus 5G",
        "XT2415" to "Moto G85",
        "XT2431" to "Moto G64",
        "XT2427" to "Moto G55",
        "XT2423" to "Moto G24",
        "XT2421" to "Moto G04",

        // === 2023 ===
        "XT2321" to "Razr 40 Ultra",
        "XT2323" to "Razr 40",
        "XT2301" to "Edge 40 Pro",
        "XT2303" to "Edge 40",
        "XT2307" to "Edge 40 Neo",
        "XT2309" to "ThinkPhone (2023)",
        "XT2347" to "Moto G84 5G",
        "XT2343" to "Moto G54 5G",
        "XT2315" to "Moto G Stylus 5G",

        // === 2022 ===
        "XT2251" to "Razr 2022",
        "XT2241" to "Edge 30 Ultra",
        "XT2201" to "Edge 30 Pro",
        "XT2243" to "Edge 30 Fusion",
        "XT2203" to "Edge 30",
        "XT2245" to "Edge 30 Neo",
        "XT2225" to "Moto G82 5G",
        "XT2255" to "Moto G72",
        "XT2221" to "Moto G52",

        // === 2021 ===
        "XT2153" to "Edge 20 Pro",
        "XT2143" to "Edge 20",
        "XT2139" to "Edge 20 Lite",
        "XT2115" to "Moto G100",
        "XT2175" to "Moto G200 5G",
        "XT2135" to "Moto G60",
        "XT2137" to "Moto G50",

        // === 2020 ===
        "XT2071" to "Razr 5G",
        "XT2000" to "Razr",
        "XT2061" to "Edge+",
        "XT2063" to "Edge",
        "XT2075" to "Moto G 5G Plus",
        "XT2087" to "Moto G9 Plus",
        "XT2083" to "Moto G9 Play",
        "XT2041" to "Moto G8 Power",
        "XT2043" to "Moto G Stylus",

        // === 完整型号名匹配 ===
        "motorola edge 50 neo" to "Edge 50 Neo",
        "edge 50 neo" to "Edge 50 Neo",
        "razr ultra 2025" to "Razr Ultra 2025",
        "motorola razr 60 ultra" to "Razr Ultra 2025",
        "razr 60 ultra" to "Razr Ultra 2025",
        "motorola edge 60 ultra" to "Edge 60 Ultra",
        "edge 60 ultra" to "Edge 60 Ultra",

        // === 旧型号兼容 ===
        "XT2401-2" to "Edge 50 Ultra",  // 国行
        "XT2401-1" to "Edge 50 Ultra",  // 全球
        "XT2401-3" to "Edge 50 Ultra",  // Verizon
        "XT2409-3" to "Edge 50 Neo",
        "XT2409-5" to "Edge 50 Neo",    // 国行变体
        "XT2451-1" to "Razr 50 Ultra",  // 全球/北美
        "XT2451-3" to "Razr 50 Ultra",  // Verizon
        "XT2451-4" to "Razr 50 Ultra",  // 其他地区
        "XT2453-1" to "Razr 50",        // 全球
        "XT2453-2" to "Razr 50",        // 中国
        "XT2401" to "Edge 50 Ultra",
        "XT2301-1" to "ThinkPhone",
        "XT2301-2" to "ThinkPhone",
        "XT2301-3" to "Edge 40 Pro",
        "XT2301-4" to "Edge 40",
        "XT2301-5" to "Edge 40 Pro",
        "XT2301-6" to "Edge 40 Fusion",
        "XT2201-1" to "Edge 30 Pro",
        "XT2201-2" to "Edge 30",
        "XT2201-3" to "Edge 30 Pro",
        "XT2201-4" to "Edge 30 Fusion",
        "XT2061-1" to "Edge 20 Pro",
        "XT2061-2" to "Edge",
        "XT2061-3" to "Edge",
        "XT2321-1" to "Razr 40 Ultra",
        "XT2321-2" to "Razr 40 Ultra",
        "XT2323-1" to "Razr 40",
        "XT2323-2" to "Razr 40",
        "XT2343-1" to "Moto G84 5G",
        "XT2343-2" to "Moto G84 5G",
        "XT2345" to "Moto G54 5G",
    )

    /**
     * 获取显示名称
     * @param model 机型代号（如 "XT2401-2"）或完整型号名（如 "motorola razr ultra 2025"）
     * @return 市场名称（如 "ThinkPhone 25"），若不在映射表则返回清理后的代号
     */
    fun getDisplayName(model: String?): String {
        if (model.isNullOrBlank()) return "motorola"

        // 清理输入：转小写，去除首尾空格
        val cleanModel = model.lowercase().trim()

        // 直接命中映射表
        deviceMap[model]?.let { return it }
        deviceMap[cleanModel]?.let { return it }

        // 尝试去除后缀匹配（如 XT2401-2 → XT2401）
        val baseModel = model.substringBefore("-")
        deviceMap[baseModel]?.let { return it }
        deviceMap[baseModel.lowercase()]?.let { return it }

        // 处理完整型号名匹配（如 "motorola razr ultra 2025" / "motorola edge 50 neo"）
        // 提取关键识别词
        val nameKeywords = listOf(
            "razr ultra 2025" to "Razr Ultra 2025",
            "razr 60 ultra" to "Razr Ultra 2025",
            "razr 50 ultra" to "Razr 50 Ultra",
            "razr 40 ultra" to "Razr 40 Ultra",
            "razr ultra" to "Razr Ultra",
            "edge 60 ultra" to "Edge 60 Ultra",
            "edge 50 ultra" to "Edge 50 Ultra",
            "edge 40 pro" to "Edge 40 Pro",
            "edge 30 ultra" to "Edge 30 Ultra",
            "edge 50 neo" to "Edge 50 Neo",
            "thinkphone 25" to "ThinkPhone 25 (2025)",
            "thinkphone" to "ThinkPhone",
        )
        
        for ((keyword, displayName) in nameKeywords) {
            if (cleanModel.contains(keyword)) {
                return displayName
            }
        }

        // 不在映射表，返回清理后的代号（去除特殊字符）
        return model
            .replace(Regex("[^a-zA-Z0-9]"), " ")
            .trim()
            .ifEmpty { "motorola" }
    }

    /**
     * 检查是否为 Motorola 设备
     */
    fun isMotorolaDevice(model: String?): Boolean {
        if (model.isNullOrBlank()) return false
        return model.startsWith("XT", ignoreCase = true) ||
                deviceMap.containsKey(model) ||
                deviceMap.containsKey(model.substringBefore("-"))
    }
}
