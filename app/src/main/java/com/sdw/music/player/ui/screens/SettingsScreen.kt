package com.sdw.music.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdw.music.player.R
import com.sdw.music.player.AppLanguageManager
import com.sdw.music.player.EqualizerManager
import com.sdw.music.player.core.audio.UsbDacManager
import com.sdw.music.player.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sdw.music.player.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit, onNavigateToAudioDiagnostic: (() -> Unit)? = null, onNavigateToAudioQuality: (() -> Unit)? = null, onNavigateToCoverEmbed: (() -> Unit)? = null) {
    val context = LocalContext.current
    var refreshTrigger by remember { mutableStateOf(0) }

    // Preferences state
    var minDuration by remember(refreshTrigger) {
        mutableStateOf(
            context.getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE)
                .getInt("min_duration", 150)
        )
    }
    var usbExclusiveEnabled by remember(refreshTrigger) {
        mutableStateOf(
            context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                .getBoolean("usb_exclusive", false)
        )
    }
    var usbAvailable by remember { mutableStateOf(false) }
    var usbDacName by remember { mutableStateOf("") }
    var usbActive by remember { mutableStateOf(false) }
    val eqPresetId by remember(refreshTrigger) {
        mutableStateOf(EqualizerManager.getCurrentPresetId(context))
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_settings), color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        // V3.3.10: 提升 DebugLog 读取到 LazyColumn 外部，避免滚动时重组
        val ktLog = remember { com.sdw.music.player.core.audio.DebugLog.get() }
        val nativeLog = remember { UsbDacManager.getNativeDebugLog() ?: "" }
        val fullLog = if (nativeLog.isNotEmpty()) "=== NATIVE (C++) ===\n$nativeLog\n=== KOTLIN ===\n$ktLog" else ktLog
        
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item { Spacer(Modifier.height(8.dp)) }

            // === 扫描Settings ===
            item {
                SettingsSectionTitle(stringResource(R.string.settings_scan))
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Timer,
                    title = stringResource(R.string.settings_min_duration),
                    subtitle = stringResource(R.string.settings_min_duration_desc),
                    onClick = { }
                )
                // Duration options
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(10, 30, 60, 120, 150, 300).forEach { sec ->
                        val selected = minDuration == sec
                        FilterChip(
                            selected = selected,
                            onClick = {
                                minDuration = sec
                                context.getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE)
                                    .edit().putInt("min_duration", sec).apply()
                                refreshTrigger++
                            },
                            label = { Text("${sec}s", color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onBackground) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            item { SettingsDivider() }

            item { SettingsDivider() }
            // === Hardware Exclusive Mode (Bypass Android Mixer) ===
            item {
                SettingsSectionTitle(stringResource(R.string.settings_usb_dac))
            }
            item {
                val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                LaunchedEffect(usbExclusiveEnabled, refreshTrigger) {
                    if (usbExclusiveEnabled) {
                        withContext(Dispatchers.IO) {
                            try {
                                UsbDacManager.init(context)
                                val dacs = UsbDacManager.findDacs()
                                usbAvailable = dacs.isNotEmpty()
                                usbDacName = dacs.firstOrNull()?.name ?: ""
                            } catch (_: Exception) { /* USB unavailable */ }
                        }
                    } else {
                        usbAvailable = false
                        usbDacName = ""
                    }
                    usbActive = usbExclusiveEnabled && UsbDacManager.isStreaming()
                }

                SettingsItem(
                    icon = Icons.Default.Usb,
                    title = stringResource(R.string.settings_hardware_exclusive),
                    subtitle = when {
                        usbActive -> stringResource(R.string.settings_active_dac, usbDacName)
                        usbExclusiveEnabled && usbAvailable -> stringResource(R.string.settings_dac_connected, usbDacName)
                        usbExclusiveEnabled -> stringResource(R.string.settings_waiting_dac)
                        else -> stringResource(R.string.settings_bypass_mixer)
                    }
                ) {}

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Switch(
                        checked = usbExclusiveEnabled,
                        onCheckedChange = { enabled ->
                            usbExclusiveEnabled = enabled
                            prefs.edit().putBoolean("usb_exclusive", enabled).apply()
                            refreshTrigger++
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFF3A3A3E)
                        )
                    )
                    Text(
                        if (usbExclusiveEnabled) stringResource(R.string.settings_on) else stringResource(R.string.settings_off),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }

                if (usbExclusiveEnabled && usbAvailable) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("✓", color = Color(0xFF4CAF50), fontSize = 14.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("$usbDacName detected", color = Color(0xFF4CAF50), fontSize = 13.sp)
                    }
                }

                // TPDF 抖动开关：降位到 16bit 输出时消除量化失真
                val ditherPref = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                var ditherEnabled by remember(refreshTrigger) {
                    mutableStateOf(ditherPref.getBoolean("tpdf_dither", true))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_tpdf_dither), color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
                        Text(
                            stringResource(R.string.settings_tpdf_dither_desc),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = ditherEnabled,
                        onCheckedChange = { enabled ->
                            ditherEnabled = enabled
                            ditherPref.edit().putBoolean("tpdf_dither", enabled).apply()
                            UsbDacManager.setDitherEnabled(enabled)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFF3A3A3E)
                        )
                    )
                }

                Spacer(Modifier.height(8.dp))
            }
            item {
                val vuPref = context.getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE)
                var vuEnabled by remember(refreshTrigger) {
                    mutableStateOf(vuPref.getBoolean("vu_meter_enabled", true))
                }
                SettingsSwitchItem(
                    icon = Icons.Default.GraphicEq,
                    title = stringResource(R.string.settings_vu_meter),
                    subtitle = if (vuEnabled) stringResource(R.string.settings_vu_on)
                    else stringResource(R.string.settings_off),
                    checked = vuEnabled,
                    onCheckedChange = { enabled ->
                        vuEnabled = enabled
                        vuPref.edit().putBoolean("vu_meter_enabled", enabled).apply()
                        refreshTrigger++
                    }
                )

                // VU Style picker (when VU Meter is enabled)
                if (vuEnabled) {
                    Spacer(Modifier.height(4.dp))
                    val vuStylePref = context.getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE)
                    val currentStyleIdx = vuStylePref.getInt("vu_meter_style", 1)
                    val styleNames = com.sdw.music.player.ui.components.VuMeterStyle.entries.map { it.label }
                    var selectedStyle by remember(refreshTrigger) { mutableIntStateOf(currentStyleIdx) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        styleNames.forEachIndexed { idx, name ->
                            val isSelected = idx == selectedStyle
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedStyle = idx
                                    vuStylePref.edit().putInt("vu_meter_style", idx).apply()
                                    refreshTrigger++
                                },
                                label = { Text(name, fontSize = 11.sp, maxLines = 1) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                    selectedLabelColor = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.height(32.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
            
            // [V3.3.6] DebugLog 开关（默认关闭以优化性能）
            item {
                val debugPref = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                var debugEnabled by remember(refreshTrigger) {
                    mutableStateOf(debugPref.getBoolean("debug_log_enabled", false))
                }
                SettingsSwitchItem(
                    icon = Icons.Default.BugReport,
                    title = stringResource(R.string.settings_debug_log),
                    subtitle = if (debugEnabled) stringResource(R.string.settings_debug_on) else stringResource(R.string.settings_debug_off),
                    checked = debugEnabled,
                    onCheckedChange = { enabled ->
                        debugEnabled = enabled
                        debugPref.edit().putBoolean("debug_log_enabled", enabled).apply()
                        // 同步到 DebugLog 单例
                        com.sdw.music.player.core.audio.DebugLog.enabled = enabled
                        refreshTrigger++
                    }
                )
            }
            
            item { SettingsDivider() }
            item {
                val bgPref = context.getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE)
                var coverColorBg by remember(refreshTrigger) {
                    mutableStateOf(bgPref.getBoolean("cover_color_bg", true))
                }
                SettingsSwitchItem(
                    icon = Icons.Default.Palette,
                    title = stringResource(R.string.settings_cover_bg),
                    subtitle = if (coverColorBg) stringResource(R.string.settings_cover_bg_on)
                    else stringResource(R.string.settings_off),
                    checked = coverColorBg,
                    onCheckedChange = { enabled ->
                        coverColorBg = enabled
                        bgPref.edit().putBoolean("cover_color_bg", enabled).apply()
                        refreshTrigger++
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
            item { SettingsDivider() }
            item {
                val edgeLightPref = context.getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE)
                var edgeLightEnabled by remember(refreshTrigger) {
                    mutableStateOf(edgeLightPref.getBoolean("moto_edge_light", true))
                }
                SettingsSwitchItem(
                    icon = Icons.Default.LightMode,
                    title = stringResource(R.string.settings_edge_light),
                    subtitle = if (edgeLightEnabled) stringResource(R.string.settings_edge_on)
                    else stringResource(R.string.settings_off),
                    checked = edgeLightEnabled,
                    onCheckedChange = { enabled ->
                        edgeLightEnabled = enabled
                        edgeLightPref.edit().putBoolean("moto_edge_light", enabled).apply()
                        refreshTrigger++
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
            item { SettingsDivider() }
            // === Auto-Play ===
            item {
                val autoPlayPref = context.getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE)
                var autoPlayEnabled by remember(refreshTrigger) {
                    mutableStateOf(autoPlayPref.getBoolean("auto_play_on_launch", false))
                }
                SettingsSwitchItem(
                    icon = Icons.Default.PlayArrow,
                    title = stringResource(R.string.settings_auto_play),
                    subtitle = if (autoPlayEnabled) stringResource(R.string.settings_auto_on) else stringResource(R.string.settings_auto_off),
                    checked = autoPlayEnabled,
                    onCheckedChange = { enabled ->
                        autoPlayEnabled = enabled
                        autoPlayPref.edit().putBoolean("auto_play_on_launch", enabled).apply()
                        refreshTrigger++
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
            // === Widget ===
            item {
                SettingsSectionTitle(stringResource(R.string.settings_widget))
            }
            item {
                val widgetPref = context.getSharedPreferences("widget_prefs", android.content.Context.MODE_PRIVATE)
                var transparentBg by remember(refreshTrigger) {
                    mutableStateOf(widgetPref.getBoolean("transparent_bg", false))
                }
                SettingsSwitchItem(
                    icon = Icons.Default.BlurOn,
                    title = stringResource(R.string.settings_transparent_widget),
                    subtitle = if (transparentBg) stringResource(R.string.settings_widget_transparent) else stringResource(R.string.settings_widget_gradient),
                    checked = transparentBg,
                    onCheckedChange = { enabled ->
                        transparentBg = enabled
                        widgetPref.edit().putBoolean("transparent_bg", enabled).apply()
                        refreshTrigger++
                        // 立即刷新所有 widget
                        try {
                            com.sdw.music.player.widget.MusicWidgetProvider.updateAllWidgets(context)
                        } catch (_: Exception) {}
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
            item { SettingsDivider() }

            // === Standby (Idle) ===
            item {
                SettingsSectionTitle("Standby (Idle)")
            }
            item {
                val idlePref = context.getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE)
                var idleLevel by remember(refreshTrigger) {
                    mutableStateOf(idlePref.getString("idle_level", "偶尔") ?: "偶尔")
                }
                val idleLabels = mapOf(
                    "常用" to stringResource(R.string.settings_idle_working),
                    "频繁" to stringResource(R.string.settings_idle_frequent),
                    "偶尔" to stringResource(R.string.settings_idle_rare),
                    "受限" to stringResource(R.string.settings_idle_restricted)
                )
                val idleShortLabels = mapOf(
                    "常用" to stringResource(R.string.idle_short_working),
                    "频繁" to stringResource(R.string.idle_short_frequent),
                    "偶尔" to stringResource(R.string.idle_short_rare),
                    "受限" to stringResource(R.string.idle_short_restricted)
                )
                SettingsItem(
                    icon = Icons.Default.Schedule,
                    title = stringResource(R.string.settings_idle),
                    subtitle = idleLabels[idleLevel] ?: stringResource(R.string.settings_idle_rare)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    idleLabels.keys.toList().forEach { level ->
                        val selected = idleLevel == level
                        FilterChip(
                            selected = selected,
                            onClick = {
                                idleLevel = level
                                idlePref.edit().putString("idle_level", level).apply()
                                refreshTrigger++
                            },
                            label = { Text(idleShortLabels[level] ?: level, color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onBackground, fontSize = 11.sp, maxLines = 1) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            item {
                // [v7.122] Show hint when system auto-syncs idle_level
                val sysBucket = remember {
                    try {
                        val ctx = context
                        val usm = ctx.getSystemService(android.content.Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
                        usm?.appStandbyBucket
                    } catch (_: Exception) { null }
                }
                if (sysBucket != null && sysBucket != android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE) {
                    val bucketName = when (sysBucket) {
                        android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "常用"
                        android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "频繁"
                        android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE -> "偶尔"
                        android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "受限"
                        else -> "偶尔"
                    }
                    Text(
                        "Auto-sync: system standby = $bucketName → idle_level auto-mapped",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
                    )
                }
            }
            item { SettingsDivider() }

            // === Equalizer预设（隐藏，避免滚动卡顿） ===

            // === Audio Diagnostic ===
            if (onNavigateToAudioDiagnostic != null) {
                item { SettingsDivider() }
                item {
                    SettingsItem(
                        icon = Icons.Default.QueryStats,
                        title = stringResource(R.string.settings_audio_diag),
                        subtitle = stringResource(R.string.settings_audio_diag_desc),
                        onClick = onNavigateToAudioDiagnostic
                    )
                }
            }
            // Audio Quality Analyzer
            if (onNavigateToAudioQuality != null) {
                item { SettingsDivider() }
                item {
                    SettingsItem(
                        icon = Icons.Default.GraphicEq,
                        title = stringResource(R.string.settings_audio_quality),
                        subtitle = stringResource(R.string.settings_audio_quality_desc),
                        onClick = onNavigateToAudioQuality
                    )
                }
            }

            // === USB DAC Debug Log — Salt-style: Kotlin + Native (C++) + Audio Info ===
            // V3.3.10: 仅在 DebugLog 开启时显示，避免滚动卡顿
            if (com.sdw.music.player.core.audio.DebugLog.enabled) {
                item {
                    SettingsSectionTitle(stringResource(R.string.settings_usb_log))
                }
                item {
                    val scrollState = rememberScrollState()
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.heightIn(max = 240.dp)) {
                            Text(
                                text = fullLog.ifEmpty { "No logs yet. Enable USB DAC Exclusive and play a song." },
                                color = Color(0xFF00FF88),
                                fontSize = 9.sp,
                                lineHeight = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp).verticalScroll(scrollState)
                            )
                        }
                    Row(modifier = Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.End) {
                        val clipboardManager = LocalClipboardManager.current
                        TextButton(onClick = {
                            (fullLog + "\n\n=== AUDIO INFO ===\n" + (UsbDacManager.getDetailedDacInfo() ?: "") +
                             "\nStream: ${if (UsbDacManager.isStreaming()) "ON" else "OFF"}" +
                             "\nUnderruns: ${UsbDacManager.getUnderrunCount()}"
                            ).also { clipboardManager.setText(AnnotatedString(it)) }
                        }) { Text(stringResource(R.string.action_copy), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp) }
                        TextButton(onClick = { com.sdw.music.player.core.audio.DebugLog.clear(); refreshTrigger++ }) {
                            Text(stringResource(R.string.action_clear), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                        TextButton(onClick = { refreshTrigger++ }) {
                            Text(stringResource(R.string.action_refresh), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
                        }
                    }
                }
                }
            }

            // === Audio Info Panel — Salt-style real-time monitor ===
            // V3.3.10: 使用 remember 缓存所有值，避免滚动时重复调用 native
            item {
                SettingsSectionTitle(stringResource(R.string.settings_audio_info))
            }
            item {
                val stats = remember(refreshTrigger) { UsbDacManager.getDetailedDacInfo() ?: "DAC not connected" }
                val underruns = remember(refreshTrigger) { UsbDacManager.getUnderrunCount() }
                val streaming = remember(refreshTrigger) { UsbDacManager.isStreaming() }
                val nativeLogLen = remember(refreshTrigger) { (UsbDacManager.getNativeDebugLog() ?: "").length }
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            InfoChip("Stream", if (streaming) "ON" else "OFF", if (streaming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            InfoChip("Underrun", "$underruns", if (underruns > 0) Color(0xFFFF6B6B) else Color(0xFF00FF88))
                            InfoChip("Native Log", "${nativeLogLen}B", MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stats,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            lineHeight = 14.sp
                        )
                    }
                }
            }

            // === Cover Embed ===
            item {
                SettingsSectionTitle(stringResource(R.string.settings_cover_download))
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Image,
                    title = stringResource(R.string.settings_cover_tool),
                    subtitle = stringResource(R.string.settings_cover_tool_desc),
                    onClick = {
                        if (onNavigateToCoverEmbed != null) {
                            onNavigateToCoverEmbed()
                        } else {
                            // 兜底：无导航回调时尝试跳转独立 CoverEmbed 应用
                            try {
                                val intent = context.packageManager.getLaunchIntentForPackage("com.coverembed")
                                if (intent != null) {
                                    context.startActivity(intent)
                                } else {
                                    android.widget.Toast.makeText(context, R.string.settings_cover_not_installed, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, context.getString(R.string.settings_open_failed, e.message), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }

            // === Language ===
            item {
                SettingsSectionTitle(stringResource(R.string.language))
            }
            item {
                val currentLang = AppLanguageManager.getCurrent(context)
                val langLabel = when (currentLang) {
                    "zh" -> stringResource(R.string.language_chinese)
                    "en" -> stringResource(R.string.language_english)
                    "es" -> stringResource(R.string.language_spanish)
                    "ja" -> stringResource(R.string.language_japanese)
                    "ko" -> stringResource(R.string.language_korean)
                    "fr" -> stringResource(R.string.language_french)
                    "zh-TW" -> stringResource(R.string.language_traditional_chinese)
                    "pt" -> stringResource(R.string.language_portuguese)
                    "hi" -> stringResource(R.string.language_hindi)
                    else -> stringResource(R.string.language_system)
                }
                SettingsItem(
                    icon = Icons.Default.Translate,
                    title = stringResource(R.string.language),
                    subtitle = langLabel,
                    onClick = {
                        // 循环切换：跟随系统 → 中文 → English → Español → 跟随系统
                        val supported = AppLanguageManager.SUPPORTED
                        val curIdx = supported.indexOf(currentLang).coerceAtLeast(0)
                        val next = supported[(curIdx + 1) % supported.size]
                        AppLanguageManager.set(context, next)
                        refreshTrigger++
                    }
                )
            }

            // === Appearance ===
            item {
                SettingsSectionTitle(stringResource(R.string.settings_appearance))
            }
            item {
                SettingsSwitchItem(
                    icon = Icons.Default.LightMode,
                    title = stringResource(R.string.settings_light_mode),
                    subtitle = if (com.sdw.music.player.ThemeManager.lightMode.value)
                        stringResource(R.string.settings_light_mode_on)
                    else stringResource(R.string.settings_light_mode_off),
                    checked = com.sdw.music.player.ThemeManager.lightMode.value,
                    onCheckedChange = { enabled ->
                        com.sdw.music.player.ThemeManager.setLightMode(context, enabled)
                        refreshTrigger++
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
            item { SettingsDivider() }

            // === Lyrics Display ===
            item {
                SettingsSectionTitle(stringResource(R.string.settings_lyrics_display))
            }
            item {
                val lyricPref = context.getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE)
                var lyricFontSize by remember(refreshTrigger) {
                    mutableStateOf(lyricPref.getInt("lyric_font_size", 28))
                }
                SettingsItem(
                    icon = Icons.Default.FormatSize,
                    title = stringResource(R.string.settings_lyric_font_size),
                    subtitle = "${lyricFontSize}sp"
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(24, 28, 32).forEach { size ->
                        val selected = lyricFontSize == size
                        FilterChip(
                            selected = selected,
                            onClick = {
                                lyricFontSize = size
                                lyricPref.edit().putInt("lyric_font_size", size).apply()
                                refreshTrigger++
                            },
                            label = { Text("${size}sp", color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onBackground, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                var lyricVisibleLines by remember(refreshTrigger) {
                    mutableStateOf(lyricPref.getInt("lyric_visible_lines", 7))
                }
                SettingsItem(
                    icon = Icons.AutoMirrored.Filled.ViewList,
                    title = stringResource(R.string.settings_lyric_visible_lines),
                    subtitle = "$lyricVisibleLines"
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(5, 7, 9).forEach { n ->
                        val selected = lyricVisibleLines == n
                        FilterChip(
                            selected = selected,
                            onClick = {
                                lyricVisibleLines = n
                                lyricPref.edit().putInt("lyric_visible_lines", n).apply()
                                refreshTrigger++
                            },
                            label = { Text("$n", color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onBackground, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            item { SettingsDivider() }

            // === About ===
            item {
                SettingsSectionTitle(stringResource(R.string.settings_about))
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.settings_about_name),
                    subtitle = "v${BuildConfig.VERSION_NAME} | Developed by Stephen Yu"
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Forum,
                    title = stringResource(R.string.settings_dev_group),
                    subtitle = stringResource(R.string.settings_dev_group_desc),
                    onClick = {
                        try {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://t.me/motomuiscplayer")
                            )
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.util.Log.e("Settings", "open tg failed", e)
                        }
                    }
                )
            }
            item {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        title,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingsSwitchItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFF3A3A3E)
            )
        )
    }
}

@Composable
private fun SettingsDivider() {
    Spacer(Modifier.height(12.dp))
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun InfoChip(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        Text(value, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}



