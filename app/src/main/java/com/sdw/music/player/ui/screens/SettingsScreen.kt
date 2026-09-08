package com.sdw.music.player.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdw.music.player.R
import com.sdw.music.player.AppLanguageManager
import com.sdw.music.player.EqualizerManager
import com.sdw.music.player.WallpaperManager
import com.sdw.music.player.core.audio.UsbDacManager
import com.sdw.music.player.MusicService
import com.sdw.music.player.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sdw.music.player.BuildConfig

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit, onNavigateToAudioDiagnostic: (() -> Unit)? = null, onNavigateToAudioQuality: (() -> Unit)? = null, onNavigateToCoverEmbed: (() -> Unit)? = null, onBpmScanned: () -> Unit = {}) {
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
        val ktLog = remember(refreshTrigger) { com.sdw.music.player.core.audio.DebugLog.get() }
        val nativeLog = remember(refreshTrigger) { UsbDacManager.getNativeDebugLog() ?: "" }
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
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            label = { Text("${sec}s", color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center, maxLines = 1, softWrap = false, modifier = Modifier.fillMaxWidth()) },
                            modifier = Modifier.width(84.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                                selectedLabelColor = MaterialTheme.colorScheme.onBackground,
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

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
                            // 【2026-09-08】双路下发：DAC 独占走 UsbDacManager(driver)，Oboe 外放/蓝牙走 OboeDirectPlayer
                            // 历史只调 DAC 侧导致外放路径 dither 恒开无法关闭（底噪来源之一）
                            UsbDacManager.setDitherEnabled(enabled)
                            MusicService.instance?.oboeDirectPlayer?.setDitherEnabled(enabled)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFF3A3A3E)
                        )
                    )
                }

                // 【2026-09-07】A2DP 编码前预补偿（蓝牙 SBC/AAC 高频瞬态补偿）
                val btPref = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                var btPreEnabled by remember(refreshTrigger) {
                    mutableStateOf(btPref.getBoolean("bt_pre_emphasis", true))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_bt_pre), color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
                        Text(
                            stringResource(R.string.settings_bt_pre_desc),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = btPreEnabled,
                        onCheckedChange = { enabled ->
                            btPreEnabled = enabled
                            btPref.edit().putBoolean("bt_pre_emphasis", enabled).apply()
                            MusicService.instance?.applyBtPreEmphasis(enabled)
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
            // 【v8.13】BPM 扫描全库
            item {
                val ctx = context
                var scanning by remember(refreshTrigger) { mutableStateOf(false) }
                var scanDone by remember(refreshTrigger) { mutableStateOf(0) }
                var scanTotal by remember(refreshTrigger) { mutableStateOf(0) }
                if (scanning) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(22.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (scanTotal > 0) "BPM scan: $scanDone/$scanTotal" else "BPM scan...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    SettingsSwitchItem(
                        icon = Icons.Default.Speed,
                        title = stringResource(R.string.settings_scan_bpm),
                        subtitle = stringResource(R.string.settings_scan_bpm_sub),
                        checked = false,
                        onCheckedChange = { _ ->
                            scanning = true
                            scanDone = 0
                            scanTotal = 0
                            // 【v8.20】改用 GlobalScope：退出设置界面后扫描继续在后台跑
                            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val appCtx = ctx.applicationContext
                                com.sdw.music.player.BpmKeyCache.init(appCtx)
                                // 【v8.19】先刷新 MediaStore 歌曲列表，否则岸听等新目录文件永远不在扫描范围
                                com.sdw.music.player.SongRepository.rescanFromMediaStore(appCtx)
                                val all = com.sdw.music.player.SongRepository.getSongs()
                                // 【v8.21】默认非强制：跳过已缓存歌曲，扫一半中断下次接着扫
                                val detected = com.sdw.music.player.core.audio.BpmScanner.scanLibrary(appCtx, all, forceRescan = false) { done, total, _ ->
                                    // 回调在 IO 线程，直接写 Compose 状态（mutableStateOf 线程安全，自动调度重组）
                                    scanDone = done
                                    scanTotal = total
                                }
                                com.sdw.music.player.SongRepository.applyBpmCache(appCtx)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    onBpmScanned()
                                    scanning = false
                                    refreshTrigger++
                                    android.widget.Toast.makeText(
                                        appCtx,
                                        if (detected > 0) appCtx.getString(R.string.bpm_scan_done, detected) else appCtx.getString(R.string.bpm_scan_none),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )
                }
                // 【v8.22】强制重扫全部：全量重测覆盖旧缓存（清半速错值/过期数据）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_force_rescan_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        scanning = true
                        scanDone = 0
                        scanTotal = 0
                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val appCtx = ctx.applicationContext
                            com.sdw.music.player.BpmKeyCache.init(appCtx)
                            com.sdw.music.player.SongRepository.rescanFromMediaStore(appCtx)
                            val all = com.sdw.music.player.SongRepository.getSongs()
                            val detected = com.sdw.music.player.core.audio.BpmScanner.scanLibrary(appCtx, all, forceRescan = true) { done, total, _ ->
                                scanDone = done
                                scanTotal = total
                            }
                            com.sdw.music.player.SongRepository.applyBpmCache(appCtx)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                onBpmScanned()
                                scanning = false
                                refreshTrigger++
                                android.widget.Toast.makeText(
                                    appCtx,
                                    "Full rescan done: $detected detected",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }) {
                        Text(stringResource(R.string.settings_force_rescan), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item {
                val cfPref = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                var cfEnabled by remember(refreshTrigger) {
                    mutableStateOf(cfPref.getBoolean("crossfade_enabled", false))
                }
                SettingsSwitchItem(
                    icon = Icons.Default.SwapHoriz,
                    title = stringResource(R.string.settings_crossfade),
                    subtitle = if (cfEnabled) stringResource(R.string.settings_crossfade_on) + " · " + stringResource(R.string.settings_crossfade_hint)
                    else stringResource(R.string.settings_off),
                    checked = cfEnabled,
                    onCheckedChange = { enabled ->
                        cfEnabled = enabled
                        cfPref.edit().putBoolean("crossfade_enabled", enabled).apply()
                        MusicService.instance?.setCrossfadeEnabled(enabled)
                    }
                )

                // 时长选择（5s / 10s / 15s）
                if (cfEnabled) {
                    val curDur = cfPref.getInt("crossfade_duration_ms", 5000)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.settings_crossfade_duration),
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        listOf(5000 to "5s", 10000 to "10s", 15000 to "15s").forEach { (ms, label) ->
                            val selected = curDur == ms
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    cfPref.edit().putInt("crossfade_duration_ms", ms).apply()
                                    MusicService.instance?.setCrossfadeDurationMs(ms)
                                    refreshTrigger++
                                },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                    selectedLabelColor = MaterialTheme.colorScheme.primary
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                // 智能对齐（预扫描静音段，交叉更自然）
                var smartAlign by remember(refreshTrigger) {
                    mutableStateOf(cfPref.getBoolean("crossfade_smart_align", true))
                }
                SettingsSwitchItem(
                    icon = Icons.Default.AutoAwesome,
                    title = stringResource(R.string.settings_crossfade_smart_align),
                    subtitle = if (smartAlign) stringResource(R.string.settings_crossfade_smart_align_sub)
                    else stringResource(R.string.settings_off),
                    checked = smartAlign,
                    onCheckedChange = { enabled ->
                        smartAlign = enabled
                        cfPref.edit().putBoolean("crossfade_smart_align", enabled).apply()
                        refreshTrigger++
                    }
                )
            }
            // 【v8.13】随机播放模式：A 纯随机 / B BPM 匹配
            item {
                val sp = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                var shuffleMode by remember(refreshTrigger) {
                    // 兼容旧 boolean pref：true=bpm，false=random
                    mutableStateOf(
                        when (sp.getString("shuffle_mode", null)) {
                            "bpm" -> "bpm"
                            "random" -> "random"
                            else -> "random"  // 【2026-09-04】默认纯随机（pure random），旧 pref 不再影响
                        }
                    )
                }
                Text(
                    text = stringResource(R.string.settings_shuffle_mode),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("random" to stringResource(R.string.settings_shuffle_mode_random),
                           "bpm" to stringResource(R.string.settings_shuffle_mode_bpm)).forEach { (mode, label) ->
                        FilterChip(
                            selected = shuffleMode == mode,
                            onClick = {
                                shuffleMode = mode
                                sp.edit().putString("shuffle_mode", mode).apply()
                                refreshTrigger++
                            },
                            label = { Text(label, fontSize = 13.sp) }
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.settings_shuffle_mode_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
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
                            label = { Text(idleShortLabels[level] ?: level, color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp, maxLines = 1) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                                selectedLabelColor = MaterialTheme.colorScheme.onBackground,
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            item {
                // [fix] 移除误导性的 Auto-sync 提示：standby bucket 自动覆盖实为死代码，未真正运行
                Spacer(Modifier.height(4.dp))
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
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
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
                        TextButton(onClick = { com.sdw.music.player.core.audio.DebugLog.clear(); UsbDacManager.clearNativeDebugLog(); refreshTrigger++ }) {
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
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
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

            // === Custom Wallpaper ===
            item {
                SettingsSectionTitle(stringResource(R.string.settings_wallpaper))
            }
            item {
                val imagePicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri ->
                    if (uri != null) {
                        com.sdw.music.player.WallpaperManager.setWallpaper(context, uri)
                        refreshTrigger++
                    }
                }
                SettingsItem(
                    icon = Icons.Default.Wallpaper,
                    title = stringResource(R.string.settings_wallpaper_pick),
                    subtitle = if (com.sdw.music.player.WallpaperManager.hasWallpaper())
                        stringResource(R.string.settings_wallpaper_set)
                    else stringResource(R.string.settings_wallpaper_none),
                    onClick = { imagePicker.launch("image/*") }
                )
                Spacer(Modifier.height(8.dp))
            }
            if (com.sdw.music.player.WallpaperManager.hasWallpaper()) {
                item {
                    // 取色感知开关
                    SettingsSwitchItem(
                        icon = Icons.Default.Palette,
                        title = stringResource(R.string.settings_wallpaper_color_aware),
                        subtitle = stringResource(R.string.settings_wallpaper_color_aware_desc),
                        checked = com.sdw.music.player.WallpaperManager.colorAwareEnabled.value,
                        onCheckedChange = { enabled ->
                            com.sdw.music.player.WallpaperManager.setColorAware(context, enabled)
                            refreshTrigger++
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text(
                            stringResource(R.string.settings_wallpaper_blur),
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Slider(
                            value = com.sdw.music.player.WallpaperManager.blurAmount.value,
                            onValueChange = { com.sdw.music.player.WallpaperManager.setBlur(context, it) },
                            valueRange = 0f..40f
                        )
                        Text(
                            stringResource(R.string.settings_wallpaper_scrim),
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Slider(
                            value = com.sdw.music.player.WallpaperManager.scrimAmount.value,
                            onValueChange = { com.sdw.music.player.WallpaperManager.setScrim(context, it) },
                            valueRange = 0f..0.85f
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                item {
                    SettingsItem(
                        icon = Icons.Default.Delete,
                        title = stringResource(R.string.settings_wallpaper_clear),
                        subtitle = stringResource(R.string.settings_wallpaper_clear_desc),
                        onClick = {
                            com.sdw.music.player.WallpaperManager.clearWallpaper(context)
                            refreshTrigger++
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                }
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
                    // [fix] 字号选项显示「小/中/大」而非具体 sp 数值
                    listOf(
                        24 to stringResource(R.string.size_small),
                        28 to stringResource(R.string.size_medium),
                        32 to stringResource(R.string.size_large)
                    ).forEach { (size, label) ->
                        val selected = lyricFontSize == size
                        FilterChip(
                            selected = selected,
                            onClick = {
                                lyricFontSize = size
                                lyricPref.edit().putInt("lyric_font_size", size).apply()
                                refreshTrigger++
                            },
                            label = { Text(label, color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                                selectedLabelColor = MaterialTheme.colorScheme.onBackground,
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
                            label = { Text("$n", color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                                selectedLabelColor = MaterialTheme.colorScheme.onBackground,
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
        color = MaterialTheme.colorScheme.outlineVariant
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



