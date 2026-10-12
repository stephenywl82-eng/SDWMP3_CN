package com.sdw.music.player.ui.screens
import com.sdw.music.player.core.util.floatValue

import androidx.activity.compose.rememberLauncherForActivityResult
import com.sdw.music.player.ui.components.SDWDivider
import com.sdw.music.player.ui.components.SDWTopBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sdw.music.player.ui.theme.SDWShapes
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.sdw.music.player.core.backup.SettingsBackup
import com.sdw.music.player.R
import com.sdw.music.player.AppLanguageManager
import com.sdw.music.player.WallpaperManager
import com.sdw.music.player.core.audio.LoudnessNormalizer
import com.sdw.music.player.core.audio.UsbDacManager
// 【V8.219】SfxPackManager import 已随"DJ 音效插件"区块移除
import com.sdw.music.player.MusicService
import com.sdw.music.player.ui.theme.*
import com.sdw.music.player.ui.components.glassSurface
import com.sdw.music.player.ui.components.springPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sdw.music.player.BuildConfig

// [V8.164] 设置界面布局整理：
// 1) 所有条目归入统一分组玻璃卡（SettingsCard），组内用发丝分隔线，不再一堆孤立开关 + 散落 Spacer；
// 2) 所有"标题 + 一排 chip"改为 Column 内 FlowRow（SettingsOptionRow），窄屏自动换行，不再挤爆一行；
// 3) 开关/滑块/chip 配色与按压反馈统一（SettingsPrefSwitch / SettingsSliderRow）；
// 4) 界面内硬编码文案全部走 stringResource，补齐 9 种语言。
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit, onNavigateToAudioDiagnostic: (() -> Unit)? = null, onNavigateToAudioQuality: (() -> Unit)? = null, onNavigateToCoverEmbed: (() -> Unit)? = null, onBpmScanned: () -> Unit = {}) {
    val context = LocalContext.current
    var refreshTrigger by remember { mutableStateOf(0) }
    // 显式声明 () -> Unit：避免 Kotlin 把 { refreshTrigger++ } 推断成 () -> Int
    val bump: () -> Unit = { refreshTrigger += 1 }

    // [V8.110] L1 浅色切换液态漫射：扩散中心 = 浅色开关本身。记下开关在 root 里的中点，
    // 换色板之前交给 ThemeFlip，漫射就从你点的地方长出来，而不是从屏幕正中。
    var themeSwitchX by remember { mutableStateOf(0f) }
    var themeSwitchY by remember { mutableStateOf(0f) }
    // [V8.111] 跟随系统开关的扩散中心（复用同一套液态漫射）
    var followSwitchX by remember { mutableStateOf(0f) }
    var followSwitchY by remember { mutableStateOf(0f) }

    val settingsPrefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    val uiPrefs = context.getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE)

    // 【V8.311 分区折叠】设置项已达 17 个分区 / 60+ 条目，全展开要滚很久。
    // 记住每个分区的折叠态（key=分区标题），默认展开；最长的两节给 collapsible=true。
    //
    // 【V8.430 修"切语言后所有分区被折叠"】原来是纯内存的 remember{}，
    // 而语言切换走 AppCompatDelegate.setApplicationLocales → **Activity 重建** →
    // sectionOpen 整个丢光 → 所有分区回落默认值（只有"播放与过渡"展开），
    // 看着就像"手动折叠的全被吞了"。改为写入 SharedPreferences，重建后读回。
    val sectionKeys = remember { listOf("cat_playback", "cat_output", "cat_library", "cat_ui", "cat_system") }
    val sectionOpen = remember {
        val m = mutableStateMapOf<String, Boolean>()
        for (k in sectionKeys) {
            // 没存过 = 首次进入，用默认（播放与过渡展开，其余收起）
            if (uiPrefs.contains("section_open_$k")) m[k] = uiPrefs.getBoolean("section_open_$k", false)
        }
        m
    }
    fun isSectionOpen(key: String, defaultOpen: Boolean = (key == "cat_playback")): Boolean =
        sectionOpen[key] ?: defaultOpen
    fun toggleSection(key: String) {
        val cur = sectionOpen[key] ?: true
        sectionOpen[key] = !cur
        uiPrefs.edit().putBoolean("section_open_$key", !cur).apply()
    }
    // 一键全开/全收：对**全部已知分区**赋值，而不是只遍历 sectionOpen.keys ——
    // 后者在刚进页面（map 为空）或只点过两三个分区时是残缺的，
    // 表现为"点了展开全部却毫无反应"。
    fun setAllSections(open: Boolean) {
        for (k in sectionKeys) {
            sectionOpen[k] = open
            uiPrefs.edit().putBoolean("section_open_$k", open).apply()
        }
    }

    // 【V8.311】吸顶分区头：LazyColumn 的 item{} 不回传 index，逐个登记锚点极易漏改
    // （漏一处就跳错位置）。改用 stickyHeader —— 分区标题滚动时钉在顶部，
    // 天然提供"当前在哪一节"的指示，且零索引依赖，比锚点跳转稳。
    val listState = rememberLazyListState()
    var minDuration by remember(refreshTrigger) {
        mutableStateOf(uiPrefs.getInt("min_duration", 150))
    }
    var usbExclusiveEnabled by remember(refreshTrigger) {
        mutableStateOf(settingsPrefs.getBoolean("usb_exclusive", false))
    }
    var usbAvailable by remember { mutableStateOf(false) }
    var usbDacName by remember { mutableStateOf("") }
    var usbActive by remember { mutableStateOf(false) }
    val offLabel = stringResource(R.string.settings_off)

    // 【V8.195】备份 / 恢复。范围与恢复方式都放在这里（而不是 item{} 里 remember），
    // 否则卡片之间共享不到，每次滚动都会丢状态。
    var backupScopes by remember { mutableStateOf(setOf(SettingsBackup.Scope.AUDIO)) }
    var backupMode by remember { mutableStateOf(SettingsBackup.Mode.MERGE) }
    var backupMsg by remember { mutableStateOf<String?>(null) }
    var backupMsgOk by remember { mutableStateOf(true) }

    fun showBackupMsg(ok: Boolean, text: String) {
        backupMsgOk = ok
        backupMsg = text
        // 【V8.196】DebugLog 默认关闭（enabled=false）会静默丢掉排障信息，
        // 所以备份结果额外走一条不受开关影响的 Log；失败还落一份文件，
        // 这样后续即使问不到用户，也能直接从设备里读出当时那句提示。
        android.util.Log.i("SDW_BackupUI", (if (ok) "OK  " else "ERR ") + text)
        com.sdw.music.player.core.audio.DebugLog.add("Backup", text)
        if (!ok) recordBackupError(context, text)
    }

    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val json = SettingsBackup.buildJson(context, backupScopes.toList())
        val err = SettingsBackup.writeTo(context, uri, json)
        if (err == null) {
            val kb = (json.toByteArray(Charsets.UTF_8).size / 1024)
            showBackupMsg(true, context.getString(R.string.settings_backup_msg_exported, kb))
        } else {
            // 【V8.196】写到 SAF 位置失败（部分文件管理器不给持久写权限）不该就此作罢：
            // 退一步存到 App 私有目录再直接调起分享，用户照样能拿到文件并传走。
            val saved = saveJsonToInternal(context, json)
            if (saved != null) {
                shareFile(context, saved)
                showBackupMsg(
                    false,
                    context.getString(R.string.settings_backup_msg_export_fallback, err)
                )
            } else {
                showBackupMsg(false, context.getString(R.string.settings_backup_msg_export_fail, err))
            }
        }
    }

    val backupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val json = SettingsBackup.readFrom(context, uri)
        if (json.isNullOrBlank()) {
            showBackupMsg(false, context.getString(R.string.settings_backup_msg_read_fail))
            return@rememberLauncherForActivityResult
        }
        val report = SettingsBackup.restore(context, json, backupMode)
        if (report.keys == 0) {
            showBackupMsg(false, context.getString(R.string.settings_backup_msg_nothing))
            return@rememberLauncherForActivityResult
        }
        // 写进 prefs 只是"存下来了"，真正听到还得把参数下发给 native，否则界面是新的、声音是旧的
        val applied = SettingsBackup.applyToEngine(context)
        Toast.makeText(
            context,
            context.getString(R.string.settings_backup_msg_imported, report.keys) + " · " + applied.joinToString(" / "),
            Toast.LENGTH_LONG
        ).show()
        showBackupMsg(
            true,
            context.getString(R.string.settings_backup_msg_imported, report.keys) +
                if (report.skipped.isNotEmpty())
                    context.getString(R.string.settings_backup_skipped, report.skipped.size)
                else ""
        )
        bump()
    }

    Scaffold(
        topBar = {
            SDWTopBar(
                title = { Text(stringResource(R.string.action_settings), color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        // V3.3.10: 提升 DebugLog 读取到 LazyColumn 外部，避免滚动时重组
        val ktLog = remember(refreshTrigger) { com.sdw.music.player.core.audio.DebugLog.get() }
        val nativeLog = remember(refreshTrigger) { UsbDacManager.getNativeDebugLog() ?: "" }
        val fullLog = if (nativeLog.isNotEmpty()) "=== NATIVE (C++) ===\n$nativeLog\n=== KOTLIN ===\n$ktLog" else ktLog
        val logEmpty = stringResource(R.string.settings_log_empty)
        val logAudioInfo = stringResource(R.string.settings_log_audio_info)
        val logStream = stringResource(R.string.settings_log_stream)
        val logUnderruns = stringResource(R.string.settings_log_underruns)
        val statusOn = stringResource(R.string.settings_status_on)
        val statusOff = stringResource(R.string.settings_status_off)
        val noDacText = stringResource(R.string.settings_dac_not_connected)
        val dacDetected = stringResource(R.string.settings_dac_detected, usbDacName)
        val bpmScanningStart = stringResource(R.string.settings_bpm_scanning_start)

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), state = listState) {
            item { Spacer(Modifier.height(8.dp)) }

            // 【V8.311】折叠概览条：设置已有 17 个分区 / 60+ 条目，进来先看到一堵墙。
            // 这里给一行「展开全部 / 收起全部」+ 常用分区直达：
            // 点分区名= 展开该分区；用户想快速找某个开关时先收起整页，再逐节点开。
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 全部收起 / 全部展开：一次点击把全部五个分区同时收起或展开，
                    // 快速定位（想找某个开关时先整页收起，再逐节点开）。
                    val allCollapsed = sectionKeys.all { isSectionOpen(it, defaultOpen = false) }
                    Surface(
                        shape = SDWShapes.Badge,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        modifier = Modifier.clickable {
                            setAllSections(!allCollapsed)   // 【V8.430】走完整分区列表，见 setAllSections 注释
                        }
                    ) {
                        Text(
                            stringResource(
                                if (allCollapsed) R.string.settings_expand_all
                                else R.string.settings_collapse_all
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    listOf(
                        "cat_playback" to stringResource(R.string.settings_cat_playback),
                        "cat_output" to stringResource(R.string.settings_cat_audio_output),
                        "cat_library" to stringResource(R.string.settings_cat_library),
                        "cat_ui" to stringResource(R.string.settings_cat_interface),
                        "cat_system" to stringResource(R.string.settings_cat_system)
                    ).forEach { (key, label) ->
                        val open = isSectionOpen(key)
                        Surface(
                            shape = SDWShapes.Badge,
                            color = if (open) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f),
                            modifier = Modifier.clickable { toggleSection(key) }
                        ) {
                            Text(
                                label,
                                color = if (open) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            item {
                SettingsSectionTitle(
                    stringResource(R.string.settings_cat_playback),
                    collapsible = true,
                    expanded = isSectionOpen("cat_playback"),
                    itemCount = 45,   // 【V8.429】-1：声道平衡移至「音频输出」
                    onToggle = { toggleSection("cat_playback") }
                )
            }
            if (isSectionOpen("cat_playback")) {
                // 【DJ 单开关整合】隐藏的高级开关：默认收起，仅调试时可展开其余 DJ 子项
                // 注意：此处位于 LazyListScope（非 @Composable 上下文），不能用 remember；
                // 用普通 val 直接读 pref，切换后由 bump()/refreshTrigger 触发重组成效。
                val devShowDjAdvanced = settingsPrefs.getBoolean("dev_show_dj_advanced", false)

                item { SettingsGroupLabel(stringResource(R.string.settings_section_playback)) }
                if (devShowDjAdvanced) {
                item {
                    var cfEnabled by remember(refreshTrigger) {
                        mutableStateOf(settingsPrefs.getBoolean("crossfade_enabled", true))
                    }
                    // 【V8.379】DJ 总开关状态。过渡参数（小节数/时长/间隔/风格/落点/对齐）
                    // 原本露在折叠外，用户反馈「太复杂」，现全部收进 DJ 折叠。
                    var djEnabledForCf by remember(refreshTrigger) {
                        mutableStateOf(settingsPrefs.getBoolean("crossfade_dj_enabled", true))
                    }
                    SettingsCard {
                        SettingsSwitchItem(
                            icon = Icons.Default.SwapHoriz,
                            title = stringResource(R.string.settings_crossfade),
                            subtitle = if (cfEnabled) stringResource(R.string.settings_crossfade_on) + " · " + stringResource(R.string.settings_crossfade_hint)
                            else offLabel,
                            checked = cfEnabled,
                            inCard = true,
                            onCheckedChange = { enabled ->
                                cfEnabled = enabled
                                settingsPrefs.edit().putBoolean("crossfade_enabled", enabled).apply()
                                MusicService.instance?.setCrossfadeEnabled(enabled)
                            }
                        )

                        // 【V8.194】交叉淡化关掉 ≠ DJ 效果下线 —— 它挂在混音后的总线上，
                        // 会继续影响普通播放。静默留着会变成"莫名发闷"，这里给一句说明 + 就地关掉的入口。
                        if (!cfEnabled && settingsPrefs.getBoolean("crossfade_dj_enabled", true)) {
                            SDWDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        settingsPrefs.edit().putBoolean("crossfade_dj_enabled", false).apply()
                                        MusicService.instance?.applyLiveFx()
                                        bump()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.settings_dj_still_on),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Text(
                                    stringResource(R.string.settings_dj_turn_off_action),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }

                        // 【V8.152/V8.415】过渡长度：**只按小节**（DJ 式 2/4/8/16 小节，用 A 轨 BPM 折算）。
                        // 秒数档 5/10/15s 已按用户要求删除 —— 落点对不齐乐句，且历史上多次被残留值盖掉小节。
                        if (cfEnabled && djEnabledForCf) {
                            // 读时归一：老配置里可能存着 0(Auto) 或 6 之类的值，这里吸附到合法档位
                            val rawBars = settingsPrefs.getInt("crossfade_bars", 4)
                            val curBars = if (rawBars in setOf(2, 4, 8, 16)) rawBars else 4
                            // 【V8.425·#3】吸附后回写，避免"界面显示 4 小节、服务端按原值(如 6)算时长"的错位
                            if (curBars != rawBars) {
                                settingsPrefs.edit().putInt("crossfade_bars", curBars).apply()
                            }
                            SDWDivider()
                            SettingsOptionRow(
                                title = stringResource(R.string.settings_crossfade_bars),
                                options = listOf("2", "4", "8", "16").map { bars ->
                                    bars to stringResource(R.string.settings_bars_value, bars)
                                },
                                selectedKey = curBars.toString(),
                                onSelect = { key ->
                                    val b = key.toInt()
                                    settingsPrefs.edit().putInt("crossfade_bars", b).apply()
                                    MusicService.instance?.setCrossfadeBars(b)
                                    // 小节是唯一模式：秒数档一律归零，防止设备上残留的
                                    // crossfade_duration_ms 反过来盖掉小节数（V8.413 的根因）。
                                    settingsPrefs.edit().putInt("crossfade_duration_ms", 0).apply()
                                    MusicService.instance?.setCrossfadeDurationMs(0)
                                    bump()
                                }
                            )
                        }

                        // 【P3】Master Sync 真实同步。默认关：关闭时 MasterSyncShadow 仍是纯观测，
                        // 不改任何播放行为；打开后过渡时长会被微调（±1 拍内）以对齐 master 小节线。
                        if (cfEnabled && djEnabledForCf) {
                            var masterSync by remember(refreshTrigger) {
                                mutableStateOf(settingsPrefs.getBoolean("crossfade_master_sync", false))
                            }
                            SDWDivider()
                            SettingsSwitchItem(
                                icon = Icons.Default.GraphicEq,
                                title = stringResource(R.string.settings_master_sync),
                                subtitle = stringResource(R.string.settings_master_sync_sub),
                                checked = masterSync,
                                inCard = true,
                                onCheckedChange = { on ->
                                    masterSync = on
                                    settingsPrefs.edit().putBoolean("crossfade_master_sync", on).apply()
                                    // 立刻生效，不等服务重启
                                    com.sdw.music.player.MasterSyncShadow.enabled = on
                                    bump()
                                }
                            )
                        }

                        // 【V8.250】过渡方式：淡化（交叉）/ 硬切（djay CUT，在对齐点直接切歌）
                        if (cfEnabled && djEnabledForCf) {
                            // 【V8.312·A】自动切歌最小间隔：两次自动过渡之间的冷却时间。
                            // 治「一直自己不停切歌」。0 = 不限（回到旧的紧追行为）。
                            SDWDivider()
                            SettingsChipGroup(
                                label = stringResource(R.string.settings_min_gap),
                                options = listOf(
                                    "0" to stringResource(R.string.settings_min_gap_off),
                                    "10" to stringResource(R.string.settings_min_gap_10),
                                    "25" to stringResource(R.string.settings_min_gap_25),
                                    "40" to stringResource(R.string.settings_min_gap_40),
                                    "60" to stringResource(R.string.settings_min_gap_60)
                                ),
                                selectedKey = settingsPrefs.getInt("crossfade_min_gap_sec", 25).toString(),
                                onSelect = { key ->
                                    settingsPrefs.edit().putInt("crossfade_min_gap_sec", key.toIntOrNull() ?: 25).apply()
                                    bump()
                                }
                            )
                            // 【V8.322】对拍锁相整定：把「跟随手感」交给用户可调，不必等我们盲调。
                            // 存int（微单位/千分位）—— SharedPreferences 无原生 Float XML 类型。
                            SettingsChipGroup(
                                label = stringResource(R.string.pll_deadzone),
                                options = listOf(
                                    "1000" to stringResource(R.string.pll_deadzone_1000),
                                    "500" to stringResource(R.string.pll_deadzone_500),
                                    "200" to stringResource(R.string.pll_deadzone_200),
                                    "50" to stringResource(R.string.pll_deadzone_50)
                                ),
                                selectedKey = settingsPrefs.getInt("pll_deadzone_micro", 200).toString(),
                                onSelect = { key ->
                                    settingsPrefs.edit().putInt("pll_deadzone_micro", key.toIntOrNull() ?: 200).apply()
                                    bump()
                                }
                            )
                            SettingsChipGroup(
                                label = stringResource(R.string.pll_gain),
                                options = listOf(
                                    "15" to stringResource(R.string.pll_gain_soft),
                                    "30" to stringResource(R.string.pll_gain_mid),
                                    "50" to stringResource(R.string.pll_gain_strong)
                                ),
                                selectedKey = settingsPrefs.getInt("pll_gain_hi_pct", 30).toString(),
                                onSelect = { key ->
                                    settingsPrefs.edit().putInt("pll_gain_hi_pct", key.toIntOrNull() ?: 30).apply()
                                    // 小增益跟着大增益走，保持"误差大→强、接近→弱"的单调关系
                                    val hi = key.toIntOrNull() ?: 30
                                    val lo = (hi * 0.33f).toInt().coerceAtLeast(5)
                                    settingsPrefs.edit().putInt("pll_gain_lo_pct", lo).apply()
                                    bump()
                                }
                            )
                            SettingsNoteRow(stringResource(R.string.settings_pll_tuning_hint))
                            SDWDivider()
                            SettingsOptionRow(
                                title = stringResource(R.string.settings_crossfade_style),
                                options = listOf(
                                    "fade" to stringResource(R.string.settings_crossfade_style_fade),
                                    "cut" to stringResource(R.string.settings_crossfade_style_cut)
                                ),
                                selectedKey = settingsPrefs.getString("crossfade_style", "fade") ?: "fade",
                                onSelect = { key ->
                                    settingsPrefs.edit().putString("crossfade_style", key).apply()
                                    bump()
                                }
                            )
                        }

                        // 【V8.194】交叉淡化总开关关着时，下面两项都不会生效，直接收起来
                        // （原来就地显示但点了不生效，看着像有 bug）。
                        if (cfEnabled && djEnabledForCf) {
                            SDWDivider()
                            // 【V8.248】落点模式（合并原 智能对齐/落点偏好/Automix 乐句切入 三个键，
                            // 首次读取自动迁移老配置）。标准=小节线进场；拍点=智能对齐选更优落点；
                            // 乐句=djay Automix 式 8 小节乐句切入。
                            val landingMode = settingsPrefs.getString("crossfade_landing_mode", null) ?: run {
                                val m = when {
                                    settingsPrefs.getBoolean("crossfade_automix", false) -> "phrase"
                                    settingsPrefs.getBoolean("crossfade_smart_align", true) -> "beat"
                                    else -> "standard"
                                }
                                settingsPrefs.edit().putString("crossfade_landing_mode", m).apply()
                                m
                            }
                            SettingsOptionRow(
                                title = stringResource(R.string.settings_landing_mode),
                                options = listOf(
                                    "standard" to stringResource(R.string.settings_landing_mode_standard),
                                    "beat" to stringResource(R.string.settings_landing_mode_beat),
                                    "phrase" to stringResource(R.string.settings_landing_mode_phrase)
                                ),
                                selectedKey = landingMode,
                                onSelect = { key ->
                                    settingsPrefs.edit().putString("crossfade_landing_mode", key).apply()
                                    bump()
                                }
                            )
                            // 落点偏好（仅拍点模式）：静音段优先(稳) / 拍点优先(DJ 拍上交接)
                            if (landingMode == "beat") {
                                SDWDivider()
                                SettingsOptionRow(
                                    title = stringResource(R.string.settings_crossfade_align_prefer),
                                    options = listOf(
                                        "quiet" to stringResource(R.string.settings_crossfade_align_quiet),
                                        "beat" to stringResource(R.string.settings_crossfade_align_beat)
                                    ),
                                    selectedKey = settingsPrefs.getString("crossfade_align_prefer", "quiet") ?: "quiet",
                                    onSelect = { key ->
                                        settingsPrefs.edit().putString("crossfade_align_prefer", key).apply()
                                        bump()
                                    }
                                )
                            }
                        }
                    }
                }
                }

                // 【V8.150 Live FX 总线】切歌效果：作用于混音后总线、可随时开关、即时生效
                item {
                    val context = LocalContext.current
                    var djEnabled by remember(refreshTrigger) {
                        mutableStateOf(settingsPrefs.getBoolean("crossfade_dj_enabled", true))
                    }
                    // 【DJ 单开关整合】主控=唯一真相源：首次进入把交叉淡化总闸对齐到主控，
                    // 自愈历史残留（crossfade_enabled 旧默认 false 与 crossfade_dj_enabled 不一致）。
                    LaunchedEffect(Unit) {
                        val cur = settingsPrefs.getBoolean("crossfade_enabled", false)
                        if (!settingsPrefs.contains("crossfade_enabled") || cur != djEnabled) {
                            settingsPrefs.edit().putBoolean("crossfade_enabled", djEnabled).apply()
                        }
                    }
                    SettingsCard {
                        // 【V8.427】长按本行（调试）切换 dev_show_dj_advanced，展开/收起下方高级 DJ 开关。
                        // 副标题「调试可长按展开」此前名存实亡——该 pref 只有读、没有写入口，长按无效，现已补上。
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onLongPress = {
                                            if (djEnabled) {
                                                val next = !settingsPrefs.getBoolean("dev_show_dj_advanced", false)
                                                settingsPrefs.edit().putBoolean("dev_show_dj_advanced", next).apply()
                                                bump()
                                            }
                                        }
                                    )
                                }
                        ) {
                            SettingsSwitchItem(
                            icon = Icons.Default.GraphicEq,
                            title = stringResource(R.string.settings_dj_effect),
                            // 【DJ 单开关整合】唯一主控「DJ 切歌模式」：开=全栈 djay Automix，关=纯播放硬切
                            subtitle = if (djEnabled) stringResource(R.string.settings_dj_master_on)
                                       else offLabel,
                            checked = djEnabled,
                            inCard = true,
                            onCheckedChange = { enabled ->
                                djEnabled = enabled
                                // 【DJ 单开关整合】唯一主控：开=全栈 djay Automix（含交叉淡化总开关），
                                // 关=纯播放硬切（关掉交叉淡化总开关）。普通模式音频链路代码不动，只驱动既有总闸。
                                settingsPrefs.edit().putBoolean("crossfade_dj_enabled", enabled).apply()
                                settingsPrefs.edit().putBoolean("crossfade_enabled", enabled).apply()
                                MusicService.instance?.setCrossfadeEnabled(enabled)
                                MusicService.instance?.applyLiveFx()
                                // 【待办#2/#3 单开关归总】主控即唯一真相源：切换时立即把后台 DJ 音频栈
                                // 重发（低频交换 / 三段频 / 变速）。ON→按 pref+gating 满血激活；
                                // OFF→activeDjAudioConfig 返 NONE，三项全关。普通模式音频链路零改动。
                                MusicService.instance?.refreshXfadeBassSwap()
                                MusicService.instance?.refreshXfadeThreeBand()
                                MusicService.instance?.applyBeatmatchEngine()
                                bump()
                            }
                        )
                        }
                        // 【V8.271】仅过渡时生效（默认开）：效果只在过渡窗口挂上，普通播放保持干净音质
                        if (djEnabled && devShowDjAdvanced) {
                            SDWDivider()
                            var fxTransitionOnly by remember(refreshTrigger) {
                                mutableStateOf(settingsPrefs.getBoolean("crossfade_dj_transition_only", true))
                            }
                            SettingsSwitchItem(
                                icon = Icons.Default.Timelapse,
                                title = stringResource(R.string.settings_dj_transition_only),
                                subtitle = if (fxTransitionOnly) stringResource(R.string.settings_dj_transition_only_on)
                                else stringResource(R.string.settings_dj_transition_only_off),
                                checked = fxTransitionOnly,
                                inCard = true,
                                onCheckedChange = { enabled ->
                                    fxTransitionOnly = enabled
                                    settingsPrefs.edit().putBoolean("crossfade_dj_transition_only", enabled).apply()
                                    MusicService.instance?.applyLiveFx()
                                    bump()
                                }
                            )
                        }

                        // ======================================================================
                        // V8.366: DJ fold start.
                        //
                        // 用户要求（2026-10-04）：「我只需要一个开关就可以了 其他都不要显示」
                        // —— 很多人看不懂这么多开关的意思。这一段原本露在折叠外：
                        //   threeband（三段频）/ 「进阶设置」折叠按钮（智能选曲已移出到播放分区）
                        //   / 低频交换 / 能量曲线策略 / rubberband（变速对齐）+ phaselock
                        //   + 变速范围 / 采样音效包 / 全库分析
                        //
                        // 现在全部收进 DJ 总开关折叠。
                        // **UI 折叠只是好看，服务侧门控才是真的** —— 见 MusicService.kt
                        // 顶部 V8.363 警戒线：UI 隐藏 != 服务生效。
                        //
                        // 【V8.379 更正 V8.366】V8.366 曾把「交叉淡化总开关 / 时长 / 落点模式」
                        // 有意留在折叠外，理由是"它们是过渡本身，不算 DJ 能力"。
                        // 用户随后反馈「交叉淡化下面开关太复杂」，现已把过渡参数
                        // （小节数 / 时长 / 间隔 / 风格 / 落点模式 / 对齐偏好）一并收进 DJ 折叠。
                        // 折叠外只留「交叉淡化总开关」—— 那是普通模式也要用的基础开关。
                        // ======================================================================
                        if (djEnabled && devShowDjAdvanced) {
                        var threeBandOn by remember(refreshTrigger) {
                            mutableStateOf(settingsPrefs.getBoolean("crossfade_threeband", true))
                        }
                        // 【V8.248】原「Automix 智能衔接」独立开关已并入上面的「落点模式→乐句」，
                        // 旧键 crossfade_automix 由 MusicService.landingModeOf 一次性迁移，此处不再展示。
                        // 【V8.245→V8.427】「智能选曲」UI 开关已移至播放分区（随机播放旁），此处仅保留可读态，
                        // 供下方「能量曲线策略」联动（仅智能选曲开启时显示）。普通模式也可直接开关。
                        var smartPickOn by remember(refreshTrigger) {
                            mutableStateOf(settingsPrefs.getBoolean("crossfade_smartpick", true))
                        }
                        // 【V8.311 分组】
                            SettingsGroupLabel(stringResource(R.string.settings_grp_select))
                        // 【V8.276】进阶设置折叠：DJ 区开关一度堆到 11 个，用户反馈"不知道哪些会互相覆盖"。
                        // 默认只露出 5 个主开关（实时效果 / 仅过渡时生效 / 智能选曲 / FX 类型 / 深度），
                        // 低频交接、三段频、变速、采样音效、全库分析收进这里。
                        var djAdvanced by remember(refreshTrigger) {
                            mutableStateOf(settingsPrefs.getBoolean("settings_dj_advanced", false))
                        }
                        SDWDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    djAdvanced = !djAdvanced
                                    settingsPrefs.edit().putBoolean("settings_dj_advanced", djAdvanced).apply()
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (djAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                stringResource(R.string.settings_dj_advanced),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.settings_dj_advanced),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    if (djAdvanced) stringResource(R.string.settings_dj_advanced_sub)
                                    else stringResource(R.string.settings_dj_advanced_count, 6),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        if (djAdvanced && devShowDjAdvanced) {
                            // 【V8.220】低频交接（插件式可选，默认关）：专业 DJ 的 bass swap ——
                            // 过渡时 A 轨低频提前切出、B 轨低频同步顶上，低频无重叠更干净。
                            // 关 = 默认防浑浊曲线，音频链路零改动。不依赖上面的 DJ 效果开关。
                            // 【V8.247 审查】三段频开启时本曲线被完全旁路 → 隐藏（防"开了没反应"）。
                            if (!threeBandOn) {
                                var bassSwapOn by remember(refreshTrigger) {
                                    mutableStateOf(settingsPrefs.getBoolean("crossfade_bass_swap", true))
                                }
                                SettingsSwitchItem(
                                    icon = Icons.Default.GraphicEq,
                                    title = stringResource(R.string.settings_crossfade_bass_swap),
                                    subtitle = if (bassSwapOn) stringResource(R.string.settings_crossfade_bass_swap_on)
                                    else stringResource(R.string.settings_crossfade_bass_swap_off),
                                    checked = bassSwapOn,
                                    inCard = true,
                                    onCheckedChange = { enabled ->
                                        bassSwapOn = enabled
                                        settingsPrefs.edit().putBoolean("crossfade_bass_swap", enabled).apply()
                                        MusicService.instance?.applyXfadeBassSwap(enabled)
                                        bump()
                                    }
                                )
                            }
                            // 【V8.246】三段频交接（插件式可选，默认关）：过渡时低/中/高频各自独立曲线
                            // 错峰交接（低 35% 内快交、中标准等功率、高 65% 后进），等效"穷版 NeuralMix"，
                            // 两轨全频带不再硬叠，浑浊感显著下降。开时替代上面的防浑浊/bass swap 曲线。
                            SettingsSwitchItem(
                                icon = Icons.Default.Equalizer,
                                title = stringResource(R.string.settings_crossfade_threeband),
                                subtitle = if (threeBandOn) stringResource(R.string.settings_crossfade_threeband_on)
                                else stringResource(R.string.settings_crossfade_threeband_off),
                                checked = threeBandOn,
                                inCard = true,
                                onCheckedChange = { enabled ->
                                    threeBandOn = enabled
                                    settingsPrefs.edit().putBoolean("crossfade_threeband", enabled).apply()
                                    MusicService.instance?.applyXfadeThreeBand(enabled)
                                    bump()
                                }
                            )
                            // 【V8.246】能量曲线策略（djay Automix 式，仅智能选曲开启时显示）：
                            // 平滑 = 下一首能量最接近；爬升 = 只挑更响的；降温 = 只挑更轻的。
                            if (smartPickOn) {
                                SettingsChipGroup(
                                    options = listOf(
                                        "smooth" to stringResource(R.string.settings_energy_strategy_smooth),
                                        "build" to stringResource(R.string.settings_energy_strategy_build),
                                        "wind" to stringResource(R.string.settings_energy_strategy_wind)
                                    ),
                                    selectedKey = settingsPrefs.getString("automix_energy_strategy", "build") ?: "build",
                                    onSelect = { key ->
                                        settingsPrefs.edit().putString("automix_energy_strategy", key).apply()
                                        bump()
                                    }

                                )
                            }
                            // 【Phase1】Tempo adjust 四档（对齐 djay 标准命名）：Off / Sync / Sync+Tempo Blend / Automatic。
                            // off = 完全不对拍；sync = 对拍（用所选过渡模板）；tempoblend = 对拍且强制 Tempo Blend 过渡；
                            // auto = 对拍 + 智能选模板（当前默认）。四档统一驱动底层变速总开关。
                            var tempoAdjust by remember(refreshTrigger) {
                                mutableStateOf(settingsPrefs.getString("crossfade_tempo_adjust", "auto") ?: "auto")
                            }
                            SettingsChipGroup(
                                label = stringResource(R.string.settings_tempo_adjust),
                                options = listOf(
                                    "off" to stringResource(R.string.tempo_adjust_off),
                                    "sync" to stringResource(R.string.tempo_adjust_sync),
                                    "tempoblend" to stringResource(R.string.tempo_adjust_tempoblend),
                                    "auto" to stringResource(R.string.tempo_adjust_auto)
                                ),
                                selectedKey = tempoAdjust,
                                onSelect = { key ->
                                    tempoAdjust = key
                                    settingsPrefs.edit().putString("crossfade_tempo_adjust", key).apply()
                                    // 四档对齐底层变速总开关：off 关对拍，其余开对拍（与既有「变速对齐」开关同源）
                                    settingsPrefs.edit().putBoolean("crossfade_rubberband", key != "off").apply()
                                    MusicService.instance?.applyBeatmatchEngine()
                                    bump()
                                }
                            )
                            // 【V8.222】变速对齐（Rubber Band 高质量 keylock，插件式默认关）：
                            // 开 = BPM 匹配随机模式下，B 轨用 Rubber Band 拉伸到 A 轨速度（音高不变，
                            // ±8% 内几乎无痕）；关 = 不变速，音频链零接触。引擎在 native 走独立插槽。
                            var rbKeylockOn by remember(refreshTrigger) {
                                mutableStateOf(settingsPrefs.getBoolean("crossfade_rubberband", true))
                            }
                            SettingsSwitchItem(
                                icon = Icons.Default.Speed,
                                title = stringResource(R.string.settings_crossfade_rubberband),
                                subtitle = if (rbKeylockOn) stringResource(R.string.settings_crossfade_rubberband_on)
                                else stringResource(R.string.settings_crossfade_rubberband_off),
                                checked = rbKeylockOn,
                                inCard = true,
                                onCheckedChange = { enabled ->
                                    rbKeylockOn = enabled
                                    settingsPrefs.edit().putBoolean("crossfade_rubberband", enabled).apply()
                                    MusicService.instance?.applyBeatmatchEngine()
                                    bump()
                                }
                            )
                            // 【V8.243】Rubber Band keylock 持续相位锁定（依赖上面的变速开关，默认开）。
                            // 开 = 过渡期间按两轨拍相位误差实时微调拉伸比，B 轨拍点持续贴齐 A 轨；
                            // 关 = 回到一次性固定比率（短过渡够用，但长过渡会累积漂移）。
                            // 【V8.247 审查】keylock 关着时本开关无效 → 仅在 keylock 开时显示（防"点了没反应"）。
                            if (rbKeylockOn) {
                                var rbPhaselockOn by remember(refreshTrigger) {
                                    mutableStateOf(settingsPrefs.getBoolean("crossfade_rubberband_phaselock", true))
                                }
                                SettingsSwitchItem(
                                    icon = Icons.Default.Tune,
                                    title = stringResource(R.string.settings_crossfade_phaselock),
                                    subtitle = if (rbPhaselockOn) stringResource(R.string.settings_crossfade_phaselock_on)
                                    else stringResource(R.string.settings_crossfade_phaselock_off),
                                    checked = rbPhaselockOn,
                                    inCard = true,
                                    onCheckedChange = { enabled ->
                                        rbPhaselockOn = enabled
                                        settingsPrefs.edit().putBoolean("crossfade_rubberband_phaselock", enabled).apply()
                                        bump()
                                    }
                                )
                                // 【V8.427】渐变拉速：全模板共用的速度地基。
                                // 以前只有 Tempo Blend（变速模板）会「推子推进途中把 A 拉向 B 的速度」，
                                // 其余模板起步就锁死目标比率 → 过渡开头速度一次性跳变。
                                // 现在提成全局开关：任何模板都先做速度渐变，再叠自己的音色层。
                                var tempoRampOn by remember(refreshTrigger) {
                                    mutableStateOf(settingsPrefs.getBoolean("crossfade_tempo_ramp", true))
                                }
                                SettingsSwitchItem(
                                    icon = Icons.Default.Speed,
                                    title = stringResource(R.string.settings_crossfade_tempo_ramp),
                                    subtitle = if (tempoRampOn) stringResource(R.string.settings_crossfade_tempo_ramp_on)
                                    else stringResource(R.string.settings_crossfade_tempo_ramp_off),
                                    checked = tempoRampOn,
                                    inCard = true,
                                    onCheckedChange = { enabled ->
                                        tempoRampOn = enabled
                                        settingsPrefs.edit().putBoolean("crossfade_tempo_ramp", enabled).apply()
                                        MusicService.instance?.applyBeatmatchEngine()   // 重发变速档 + 渐变拉速档
                                        bump()
                                    }
                                )
                                // 【V8.248】变速范围（原 crossfade_rubberband_max 只有代码读取没有 UI，转正）
                                SettingsOptionRow(
                                    title = stringResource(R.string.settings_rubberband_range),
                                    options = listOf(
                                        "4" to "±4%", "6" to "±6%", "8" to "±8%", "12" to "±12%"
                                    ),
                                    selectedKey = settingsPrefs.getInt("crossfade_rubberband_max", 8).toString(),
                                    onSelect = { key ->
                                        settingsPrefs.edit().putInt("crossfade_rubberband_max", key.toInt()).apply()
                                        bump()
                                    }
                                )
                            }
                            // 【V8.424】过渡采样音效设置项已随功能删除（过渡完全静默）。
                            SDWDivider()
                            // 【V8.264】立即全库分析：手动全速补扫 BPM/调性/段落/响度（Automix 数据基础）。
                            // 自动模式只在熄屏+充电时进行；此开关旁路熄屏门控（播放中仍自动暂停），随时可停。
                            val analysisRunning by com.sdw.music.player.core.AnalysisProgress.running.collectAsState()
                            val analysisStale by com.sdw.music.player.core.AnalysisProgress.stale.collectAsState()
                            val analysisTotal by com.sdw.music.player.core.AnalysisProgress.total.collectAsState()
                            SettingsSwitchItem(
                                icon = Icons.Default.Memory,
                                title = stringResource(R.string.settings_full_scan),
                                subtitle = if (analysisRunning) {
                                    stringResource(R.string.settings_full_scan_running, analysisStale)
                                } else {
                                    val done = if (analysisTotal > 0) analysisTotal - analysisStale.coerceAtLeast(0) else 0
                                    if (analysisTotal > 0 && analysisStale in 0 until analysisTotal)
                                        stringResource(R.string.settings_full_scan_progress, done, analysisTotal)
                                    else
                                        stringResource(R.string.settings_full_scan_sub)
                                },
                                checked = analysisRunning,
                                inCard = true,
                                onCheckedChange = { enabled ->
                                    com.sdw.music.player.MusicService.instance?.setBackfillForce(enabled)
                                }
                            )
                        }
                        }   // end of DJ fold (V8.366)
                        // 【V8.219】DJ 切歌音效功能整体移除（用户评价"乱七八糟"）：
                        // 设置项删除，MusicService 侧 setSfxEnabled 强制关闭，切歌恢复完全静默。
                        if (djEnabled && devShowDjAdvanced) {
                            // 【V8.318】分轨模式当前是否开启（native 轨级模板在分轨模式下整体跳过）。
                            // 用 refreshTrigger 做依赖：每次进设置页重新读一次即可，不需要实时订阅。
                            val stemModeNow = remember(refreshTrigger) {
                                MusicService.instance?.isStemModeActive() == true
                            }
                            // 【V8.281】djay Crossfader FX 模板：轨级处理、按推子位置调制，
                            // 与下面的「效果 + 深度」互斥（模板生效时旧效果整段让位）。
                            var tplKey by remember(refreshTrigger) {
                                mutableStateOf(settingsPrefs.getString("crossfade_dj_template", "phrase") ?: "phrase")
                            }
                            // 【V8.311 分组】
                            SettingsGroupLabel(stringResource(R.string.settings_grp_template))
                            SDWDivider()
                            SettingsChipGroup(
                                label = stringResource(R.string.settings_dj_template),
                                options = listOf(
                                    "off" to stringResource(R.string.dj_tpl_off),
                                    // 【V8.414】按用户反馈移除三个刺耳模板：滤波交接/变调下滑/噪声升扫
                                    // 实测听感是「一片白噪音」，太难听。保留回声/刹车/溶解/速度/连打/乐句。
                                    // 映射见 MusicService.applyXfadeTemplate（filter=1 / pitch=9 / riser=7）。
                                    "echo" to stringResource(R.string.dj_tpl_echo),
                                    "brake" to stringResource(R.string.dj_tpl_brake),
                                    "dissolve" to stringResource(R.string.dj_tpl_dissolve),
                                    "tempo" to stringResource(R.string.dj_tpl_tempo),
                                    "roll" to stringResource(R.string.dj_tpl_roll),
                                    "phrase" to stringResource(R.string.dj_tpl_phrase),
                                    "eq" to stringResource(R.string.dj_tpl_eq),
                                    "stem" to stringResource(R.string.dj_tpl_stem),
                                    "random" to stringResource(R.string.dj_tpl_random)
                                ),
                                selectedKey = tplKey,
                                // 【V8.318】分轨模式下 native 的轨级模板块整体跳过 → 置灰并说明原因，
                                // 免得"设了没反应"看起来像 bug。
                                // V8.349: stem rounds now DO get an effect -- native still skips
                                // the track-level template, but MusicService.applyXfadeTemplate
                                // derives a stem route (stemFxTarget(tpl)) so the round is not
                                // effectless. Keep the selector ENABLED; only warn, otherwise the
                                // user cannot pick a template for stem rounds at all.
                                enabled = true,
                                disabledHint = null,
                                onSelect = { key ->
                                    tplKey = key
                                    settingsPrefs.edit().putString("crossfade_dj_template", key).apply()
                                    // 让旧效果/总线立刻让位（或模板关掉后立刻恢复）
                                    MusicService.instance?.applyLiveFx()
                                    bump()
                                }
                            )
                            Text(
                                text = stringResource(R.string.djay_crossfader_hint),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp)
                            )
                            if (tplKey == "off") {
                            SDWDivider()
                            SettingsChipGroup(
                                options = listOf(
                                    "filter" to stringResource(R.string.dj_fx_filter),
                                    "eq" to stringResource(R.string.dj_fx_eq),
                                    "echo" to stringResource(R.string.dj_fx_echo),
                                    "dissolve" to stringResource(R.string.dj_fx_dissolve),
                                    "tremolo" to stringResource(R.string.dj_fx_tremolo),
                                    "lunar_echo" to stringResource(R.string.dj_fx_lunar),
                                    "random" to stringResource(R.string.dj_fx_random)
                                ),
                                selectedKey = settingsPrefs.getString("crossfade_dj_effect", "random") ?: "random",
                                onSelect = { key ->
                                    settingsPrefs.edit().putString("crossfade_dj_effect", key).apply()
                                    MusicService.instance?.applyLiveFx()
                                    bump()
                                }
                            )
                            SDWDivider()
                            // 【V8.150】效果深度（wet/dry 混合比例）
                            var djDepth by remember(refreshTrigger) {
                                mutableStateOf(settingsPrefs.floatValue("crossfade_dj_depth", 1.0f).coerceIn(0f, 1f))
                            }
                            SettingsSliderRow(
                                title = stringResource(R.string.settings_dj_depth),
                                value = djDepth,
                                valueRange = 0.2f..1f,
                                valueText = "${(djDepth * 100).toInt()}%",
                                onValueChange = { djDepth = it },
                                onValueChangeFinished = {
                                    settingsPrefs.edit().putFloat("crossfade_dj_depth", djDepth).apply()
                                    MusicService.instance?.applyLiveFx()
                                }
                            )
                            } else if (tplKey == "roll") {
                                // 【V8.286】Beat Roll 切片长度：1/2 或 1/4 拍（Hip-Hop / Trap）
                                SDWDivider()
                                SettingsChipGroup(
                                    label = stringResource(R.string.settings_roll_rate),
                                    options = listOf(
                                        "half" to stringResource(R.string.roll_rate_half),
                                        "quarter" to stringResource(R.string.roll_rate_quarter)
                                    ),
                                    selectedKey = settingsPrefs.getString("crossfade_roll_rate", "half") ?: "half",
                                    onSelect = { key ->
                                        settingsPrefs.edit().putString("crossfade_roll_rate", key).apply()
                                        bump()
                                    }
                                )
                                Text(
                                    stringResource(R.string.settings_roll_rate_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)

                                )
                            } else {
                                SDWDivider()
                                Text(
                                    stringResource(R.string.settings_dj_template_sub),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                )
                                // 【V8.285】djay Neural Mix 分轨音效路由：效果只挂到某一条分轨上。
                                // 只有过渡模板在生效时才有意义（FX 类型是从模板推出来的）。
                                // 【V8.425·#4】Tempo Blend 保持两轨原速原调，引擎端 stemFxTarget 推导为 0，
                                // 分轨路由对它有意为空 → 这里直接收起路由 chips，免得"设了没反应"。
                                // （Brake Drop 等其它模板仍会自动把分轨效果挂到鼓组，因此照常显示。）
                                if (tplKey == "tempo") {
                                    Text(
                                        stringResource(R.string.settings_stem_fx_tempo_skip),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                    )
                                } else {
                                SettingsChipGroup(
                                    label = stringResource(R.string.settings_stem_fx_target),
                                    options = listOf(
                                        "auto" to stringResource(R.string.stem_fx_auto),
                                        "off" to stringResource(R.string.stem_fx_off),
                                        "vocal" to stringResource(R.string.stem_fx_vocal),
                                        "drums" to stringResource(R.string.stem_fx_drums),
                                        "bass" to stringResource(R.string.stem_fx_bass),
                                        "other" to stringResource(R.string.stem_fx_other)
                                    ),
    // V8.354: default "auto" to match MusicService.stemFxTarget(tpl). The two sides had
    // different defaults (UI "off" vs service "auto"), so the chip showed "Whole track"
    // while the engine was already deriving a stem route.
                                    selectedKey = settingsPrefs.getString("crossfade_stem_fx_target", "vocal") ?: "vocal",
                                    onSelect = { key ->
                                        settingsPrefs.edit().putString("crossfade_stem_fx_target", key).apply()
                                        bump()
                                    }
                                )
                                Text(
                                    stringResource(R.string.settings_stem_fx_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                                SDWDivider()
                                SettingsChipGroup(
                                    label = stringResource(R.string.settings_stem_fx_mode),
                                    options = listOf(
                                        "auto" to stringResource(R.string.stem_fx_mode_auto),
                                        "hpf" to stringResource(R.string.stem_fx_mode_hpf),
                                        "echo" to stringResource(R.string.stem_fx_mode_echo),
                                        "lpf" to stringResource(R.string.stem_fx_mode_lpf),
                                        "gain" to stringResource(R.string.stem_fx_mode_gain)
                                    ),
                                    selectedKey = settingsPrefs.getString("crossfade_stem_fx_mode", "echo") ?: "echo",
                                    onSelect = { key ->
                                        settingsPrefs.edit().putString("crossfade_stem_fx_mode", key).apply()
                                        bump()
                                    }
                                )
                                Text(
                                    stringResource(R.string.settings_stem_fx_mode_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                                }
                            }

                            // 【V8.311 分组】
                            SettingsGroupLabel(stringResource(R.string.settings_grp_stem))
                            // ================= 【V8.310】DJ 扩展：Keylock / Pitch 档位 / Phrase / Auto-solo =================
                            SDWDivider()
                            // 【V8.310】Keylock：变速时锁住音高（传统 pitch bend 是速度音高一起变）
                            var djKeyLock by remember(refreshTrigger) {
                                mutableStateOf(settingsPrefs.getBoolean("crossfade_keylock", true))
                            }
                            SettingsSwitchItem(
                                icon = Icons.Default.MusicNote,
                                title = stringResource(R.string.settings_dj_keylock),
                                subtitle = stringResource(R.string.settings_dj_keylock_hint),
                                checked = djKeyLock,
                                inCard = true,
                                onCheckedChange = { v ->
                                    djKeyLock = v
                                    settingsPrefs.edit().putBoolean("crossfade_keylock", v).apply()
                                    bump()
                                }
                            )
                            // 【V8.500 ④】自动 Camelot 调性匹配：过渡期把出轨调性搬到与入轨兼容
                            var djMatchKey by remember(refreshTrigger) {
                                mutableStateOf(settingsPrefs.getBoolean("crossfade_match_key", true))
                            }
                            SettingsSwitchItem(
                                icon = Icons.Default.MusicNote,
                                title = stringResource(R.string.settings_dj_match_key),
                                subtitle = stringResource(R.string.settings_dj_match_key_hint),
                                checked = djMatchKey,
                                inCard = true,
                                onCheckedChange = { v ->
                                    djMatchKey = v
                                    settingsPrefs.edit().putBoolean("crossfade_match_key", v).apply()
                                    bump()
                                }
                            )
                            // 【V8.310】Pitch/Bend 量化档位（半音）。djay 是离散台阶，不是平滑滑音。
                            SettingsChipGroup(
                                label = stringResource(R.string.settings_dj_pitch_semi),
                                options = listOf(
                                    "0" to stringResource(R.string.dj_semi_off),
                                    "-2" to stringResource(R.string.dj_semi_m2),
                                    "-1" to stringResource(R.string.dj_semi_m1),
                                    "1" to stringResource(R.string.dj_semi_p1),
                                    "2" to stringResource(R.string.dj_semi_p2),
                                    "-12" to stringResource(R.string.dj_semi_m12),
                                    "12" to stringResource(R.string.dj_semi_p12)
                                ),
                                selectedKey = settingsPrefs.getInt("crossfade_pitch_semi", 0).toString(),
                                onSelect = { key ->
                                    settingsPrefs.edit().putInt("crossfade_pitch_semi", key.toIntOrNull() ?: 0).apply()
                                    bump()
                                }
                            )
                            // 【V8.310】Phrase 分段方案小节数
                            SettingsChipGroup(
                                label = stringResource(R.string.settings_dj_phrase_bars),
                                options = listOf(
                                    "4" to stringResource(R.string.dj_bars_4),
                                    "8" to stringResource(R.string.dj_bars_8),
                                    "16" to stringResource(R.string.dj_bars_16),
                                    "32" to stringResource(R.string.dj_bars_32)
                                ),
                                selectedKey = settingsPrefs.getInt("crossfade_phrase_bars", 4).toString(),
                                onSelect = { key ->
                                    settingsPrefs.edit().putInt("crossfade_phrase_bars", key.toIntOrNull() ?: 4).apply()
                                    bump()
                                }
                            )
                            // 【V8.310】Auto-solo 频段（过渡期把某个频段「独奏」出来）
                            SettingsChipGroup(
                                label = stringResource(R.string.settings_dj_solo_band),
                                options = listOf(
                                    "off" to stringResource(R.string.dj_solo_off),
                                    "low" to stringResource(R.string.dj_solo_low),
                                    "mid" to stringResource(R.string.dj_solo_mid),
                                    "high" to stringResource(R.string.dj_solo_high)
                                ),
                                selectedKey = settingsPrefs.getString("crossfade_solo_band", "off") ?: "off",
                                onSelect = { key ->
                                    settingsPrefs.edit().putString("crossfade_solo_band", key).apply()
                                    bump()
                                }
                            )
                            // 【V8.182】手动触发：不等候选窗口/拍点对齐，当下就切一首，方便现场比对效果
                            SDWDivider()
                            // stringResource 只能在 @Composable 上下文里取，click{} 里用预取好的字符串
                            val blockedMsg = stringResource(R.string.settings_dj_manual_trigger_blocked)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        // 【V8.194】手动触发可能被挡掉（交叉淡化总开关关着 / 正在过渡 /
                                        // DAC 独占 / 未在播放 / 单曲循环）。旧代码把 Boolean 返回值丢掉，
                                        // 点了没反应也看不出原因 —— 现在弹一句 Toast 说明原因，
                                        // 同时落到调试日志页方便定位。
                                        // 【V8.271】失败时把具体原因一起显示（旧版只有一句「未触发」）
                                        val reason = MusicService.instance?.triggerCrossfadeNowDetailed()
                                            ?: context.getString(R.string.dj_reason_service_not_connected)
                                        if (reason.isNotEmpty()) {
                                            // 【V8.304】「已排队」用肯定口吻单独一句，别套
                                            // 「未触发 —— 检查：…」故障模板（与 reason 自相矛盾）。
                                            val queued = MusicService.instance?.lastManualTriggerQueued == true
                                            Toast.makeText(
                                                context,
                                                if (queued) context.getString(R.string.dj_queued_toast)
                                                else context.getString(R.string.dj_blocked_reason_fmt, blockedMsg, reason),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            com.sdw.music.player.core.audio.DebugLog.add(
                                                "Settings",
                                                context.getString(
                                                    if (queued) R.string.dj_manual_queued else R.string.dj_manual_ignored,
                                                    reason
                                                )
                                            )
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Spacer(Modifier.width(4.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.settings_dj_manual_trigger),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        stringResource(R.string.settings_dj_manual_trigger_sub),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
                // 【V8.219】节拍同步（beatmatch 变速不变调）功能整体移除：
                // WSOLA 拉伸听感差（用户评价"像个半成品"），设置项删除，
                // MusicService.beatmatchRatio() 恒返回 1.0，变速链路彻底不再触发。
                // 【v8.13】随机播放模式：顺序 / A 纯随机 / B BPM 匹配
                item {
                    SettingsCard {
                        SettingsOptionRow(
                            title = stringResource(R.string.settings_shuffle_mode),
                            options = listOf(
                                "off" to stringResource(R.string.settings_shuffle_mode_off),
                                "random" to stringResource(R.string.settings_shuffle_mode_random),
                                "bpm" to stringResource(R.string.settings_shuffle_mode_bpm)
                            ),
                            // 【V8.194】shuffle_mode 收敛成 settings 里的三态字符串
                            // （off / random / bpm）。遗留值：缺失或 "random" = 纯随机，
                            // "true" = bpm，"false" = off。点一下就写回，不再出现"显示成纯随机
                            // 但服务里其实是顺序播放"的错位。
                            selectedKey = when (settingsPrefs.getString("shuffle_mode", null)) {
                                "bpm", "true" -> "bpm"
                                "random", "" -> "random"
                                "off", "false" -> "off"
                                else -> "random"
                            },
                            onSelect = { key ->
                                settingsPrefs.edit().putString("shuffle_mode", key).apply()
                                bump()
                            }
                        )
                        SDWDivider()
                        SettingsNoteRow(text = stringResource(R.string.settings_shuffle_mode_sub))
                        SDWDivider()
                        // 【V8.427】「智能选曲（和声+能量）」从 DJ 折叠移出：随机/列表播放挑下一首，
                        // 与 DJ 模式解耦，普通模式也可见可关（不破铁律：普通模式音频链路不受 DJ 栈影响）。
                        var smartPickPlayback by remember(refreshTrigger) {
                            mutableStateOf(settingsPrefs.getBoolean("crossfade_smartpick", true))
                        }
                        SettingsSwitchItem(
                            icon = Icons.Default.Insights,
                            title = stringResource(R.string.settings_smartpick),
                            subtitle = if (smartPickPlayback) stringResource(R.string.settings_smartpick_on)
                            else stringResource(R.string.settings_smartpick_off),
                            checked = smartPickPlayback,
                            inCard = true,
                            onCheckedChange = { enabled ->
                                smartPickPlayback = enabled
                                settingsPrefs.edit().putBoolean("crossfade_smartpick", enabled).apply()
                                bump()
                            }
                        )
                        // 【#6】cue 点感知入场开关（djay 式：B 从检测到的前奏/乐句头进）
                        var cueEntryOn by remember(refreshTrigger) {
                            mutableStateOf(settingsPrefs.getBoolean("crossfade_cue_entry", true))
                        }
                        SettingsSwitchItem(
                            icon = Icons.Default.PlayArrow,
                            title = stringResource(R.string.settings_cue_entry),
                            subtitle = if (cueEntryOn) stringResource(R.string.settings_cue_entry_on)
                                       else stringResource(R.string.settings_cue_entry_off),
                            checked = cueEntryOn,
                            inCard = true,
                            onCheckedChange = { enabled ->
                                cueEntryOn = enabled
                                settingsPrefs.edit().putBoolean("crossfade_cue_entry", enabled).apply()
                                bump()
                            }
                        )
                    }
                }

                // 【整理】音频焦点注册：原误放 USB DAC 下（注释自述"非 DAC 路径"），实为通用播放音频策略，移入播放与过渡。
                item {
                    SettingsCard {
                        SettingsPrefSwitch(
                            refreshKey = refreshTrigger, bump = bump, prefs = settingsPrefs, key = "audio_focus_register",
                            defaultValue = true, icon = Icons.Default.Hearing,
                            title = stringResource(R.string.settings_audio_focus),
                            subtitleOn = stringResource(R.string.settings_audio_focus_on),
                            subtitleOff = stringResource(R.string.settings_audio_focus_off),
                            onChange = { enabled -> MusicService.setAudioFocusRegister(context.applicationContext, enabled) }
                        )
                    }
                }
                // 【整理】原声直通：全局 DSP 主旁路（EQ/MSEB/DTS/压缩/等响/瞬态/环绕/AGC/限幅/softClip/dither 全跳），
                // 与 DAC 硬件无关，按与 AGC 同样的逻辑移出 DAC，归入播放与过渡。
                item {
                    SettingsCard {
                        SettingsPrefSwitch(
                            refreshKey = refreshTrigger, bump = bump, prefs = settingsPrefs, key = "direct_passthrough",
                            icon = Icons.Default.SurroundSound,
                            title = stringResource(R.string.settings_direct_passthrough),
                            subtitleOn = stringResource(R.string.settings_direct_passthrough_on),
                            subtitleOff = stringResource(R.string.settings_direct_passthrough_off),
                            note = stringResource(R.string.settings_direct_passthrough_note),
                            onChange = { enabled -> MusicService.instance?.applyDirectPassthrough(enabled) }
                        )
                    }
                }
                // 【整理】自动播放：启动即播是播放行为，从「显示与界面」移入此处。
                item {
                    SettingsCard {
                        SettingsPrefSwitch(
                            refreshKey = refreshTrigger, bump = bump, prefs = uiPrefs, key = "auto_play_on_launch",
                            icon = Icons.Default.PlayArrow,
                            title = stringResource(R.string.settings_auto_play),
                            subtitleOn = stringResource(R.string.settings_auto_on),
                            subtitleOff = stringResource(R.string.settings_auto_off)
                        )
                    }
                }

                item { SettingsGroupLabel(stringResource(R.string.settings_loudness_norm)) }
                item {
                    val normCfg = remember(refreshTrigger) { LoudnessNormalizer.load(context) }
                    // 【V8.236】这一行原来只在 refreshTrigger 变化时重算：切歌、以及后台扫描
                    // 补上响度的那一刻，页面都还停在「打开设置页时的旧值」→ 看着像"正在播的
                    // 这首歌没响度参数"。改成 1.5s 后台对账（读标签走 IO 线程，不堵主线程）。
                    var normTick by remember { mutableLongStateOf(0L) }
                    LaunchedEffect(Unit) {
                        while (true) { normTick++; delay(1500L) }
                    }
                    val normState by remember(normTick) {
                        derivedStateOf {
                            val s = MusicService.currentSong
                            val p = s?.filePath?.takeIf { it.isNotBlank() }
                                ?: s?.path?.takeIf { it.startsWith("/") }
                            Pair(s, p)
                        }
                    }
                    val song = normState.first
                    val songPath = normState.second
                    var loudPreview by remember { mutableStateOf<Pair<Float?, LoudnessNormalizer.Source?>?>(null) }
                    LaunchedEffect(songPath, normTick, normCfg.enabled, normCfg.mode, normCfg.targetLufs) {
                        if (songPath.isNullOrBlank()) { loudPreview = null; return@LaunchedEffect }
                        loudPreview = withContext(Dispatchers.IO) {
                            runCatching {
                                Pair(LoudnessNormalizer.gainFor(songPath, normCfg), LoudnessNormalizer.source(songPath))
                            }.getOrNull()
                        }
                    }
                    SettingsCard {
                        SettingsSwitchItem(
                            icon = Icons.Default.GraphicEq,
                            title = stringResource(R.string.settings_loudness_norm),
                            subtitle = if (normCfg.enabled)
                                stringResource(R.string.settings_loudness_norm_on)
                            else stringResource(R.string.settings_loudness_norm_off),
                            checked = normCfg.enabled,
                            inCard = true,
                            onCheckedChange = { en ->
                                pushNormalization(context, songPath, normCfg.copy(enabled = en))
                                bump()
                            }
                        )
                        if (normCfg.enabled) {
                            SDWDivider()
                            SettingsOptionRow(
                                title = stringResource(R.string.settings_loudness_mode),
                                options = listOf(
                                    "0" to stringResource(R.string.settings_loudness_mode_track),
                                    "1" to stringResource(R.string.settings_loudness_mode_album),
                                    "2" to stringResource(R.string.settings_loudness_mode_auto)
                                ),
                                selectedKey = normCfg.mode.key.toString(),
                                onSelect = { key ->
                                    val mode = LoudnessNormalizer.Mode.entries.firstOrNull { it.key.toString() == key }
                                        ?: LoudnessNormalizer.Mode.AUTO
                                    pushNormalization(context, songPath, normCfg.copy(mode = mode))
                                    bump()
                                }
                            )
                            SDWDivider()
                            SettingsOptionRow(
                                title = stringResource(R.string.settings_loudness_target),
                                // 【V8.238】目标改成绝对 LUFS：自动（曲库平均）/ -14 / -16 / -18。
                                // -14 是流媒体通行标准（Spotify/Apple Music），也是默认档；
                                // 真峰值统一限制在 -1.0 dBTP（见 LoudnessNormalizer.cap）。
                                options = listOf(
                                    "auto" to stringResource(R.string.settings_loudness_target_auto),
                                    LoudnessNormalizer.TARGET_DEFAULT.toString() to stringResource(R.string.settings_loudness_target_14),
                                    (-16f).toString() to stringResource(R.string.settings_loudness_target_16),
                                    (-18f).toString() to stringResource(R.string.settings_loudness_target_18)
                                ),
                                selectedKey = if (normCfg.targetLufs == LoudnessNormalizer.TARGET_AUTO) "auto"
                                else normCfg.targetLufs.toString(),
                                onSelect = { key ->
                                    val target = if (key == "auto") LoudnessNormalizer.TARGET_AUTO
                                    else key.toFloatOrNull() ?: LoudnessNormalizer.TARGET_DEFAULT
                                    pushNormalization(context, songPath, normCfg.copy(targetLufs = target))
                                    bump()
                                }
                            )
                            // 当前曲目实际拿到的增益：让用户确认"到底生效了没有"
                            // 【V8.236】改读后台对账出来的 loudPreview（随切歌/扫描自动更新）
                            val preview = loudPreview?.first
                            // 【V8.233】区分来源：文件标签 / 离线扫描 / 还没扫到，
                            // 免得"没参数"时分不清是这首歌真的没元数据还是后台还没扫到。
                            val src = loudPreview?.second
                            val sub = when {
                                preview == null || src == null -> stringResource(R.string.settings_loudness_pending)
                                src == LoudnessNormalizer.Source.NONE -> stringResource(R.string.settings_loudness_no_meta)
                                src == LoudnessNormalizer.Source.TAG ->
                                    "%.2f dB · %s".format(preview, stringResource(R.string.settings_loudness_src_tag))
                                else ->
                                    "%.2f dB · %s".format(preview, stringResource(R.string.settings_loudness_src_scan))
                            }
                            SDWDivider()
                            SettingsItem(
                                icon = Icons.Default.Info,
                                title = stringResource(R.string.settings_loudness_current),
                                subtitle = sub,
                                inCard = true
                            )
                        }
                    }
                }

                item { SettingsGroupLabel(stringResource(R.string.settings_agc)) }
                item {
                    SettingsCard {
                        SettingsPrefSwitch(
                            refreshKey = refreshTrigger, bump = bump, prefs = settingsPrefs, key = "agc_enabled",
                            defaultValue = false, icon = Icons.Default.GraphicEq,
                            title = stringResource(R.string.settings_agc),
                            subtitleOn = stringResource(R.string.settings_agc_on),
                            subtitleOff = stringResource(R.string.settings_agc_off),
                            note = stringResource(R.string.settings_agc_note),
                            onChange = { enabled -> MusicService.instance?.applyAgc(enabled) }
                        )
                    }
                }

            }

            item {
                SettingsSectionTitle(
                    stringResource(R.string.settings_cat_audio_output),
                    collapsible = true,
                    expanded = isSectionOpen("cat_output"),
                    itemCount = 7,   // 【V8.429】+1：声道平衡移入本分组
                    onToggle = { toggleSection("cat_output") }
                )
            }
            if (isSectionOpen("cat_output")) {
                item {
                    // 【V8.429 移位】声道平衡原本被塞在「DJ 切歌」分组里，但它和 DJ 毫无关系
                    // —— 就是最普通的立体声左右音量调节。移到「音频输出」分组。
                    // ========== 【V8.313】声道平衡（Balance）==========
                    // chip 一键复位 + 滑块连续微调。裸滑块居中容易漂移、难以回到正中间，
                    // 所以配一组「偏左/居中/偏右」的粗调 chip（djay / foobar2000 同款做法）。
                    // 存 int（-100..100）而不是 Float：SharedPreferences 没有原生
                    // Float XML 存储类型，用 Int 避免精度/兼容坑。
                    val balPct = settingsPrefs.getInt("stereo_balance", 0)
                    val balChipKey = when {
                    balPct < -8 -> "left"
                    balPct > 8 -> "right"
                    else -> "center"
                    }
                    SettingsChipGroup(
                    label = stringResource(R.string.settings_balance_label),
                    options = listOf(
                    "left" to stringResource(R.string.settings_balance_left),
                    "center" to stringResource(R.string.settings_balance_center),
                    "right" to stringResource(R.string.settings_balance_right)
                    ),
                    selectedKey = balChipKey,
                    onSelect = { key ->
                    // 【V8.427】chip 是「偏」左/右，不该把对侧直接静音（旧值 ±100 会把
                    // 另一声道打到 0）。改成 ±40：对侧剩 60%（约 -4.4dB），明显但不过头；
                    // 要彻底单声道化就用下面的滑块拉到底。
                    val target = when (key) {
                    "left" -> -40
                    "right" -> 40
                    else -> 0
                    }
                    settingsPrefs.edit().putInt("stereo_balance", target).apply()
                    MusicService.instance?.applyStereoBalance(target / 100f)
                    bump()
                    }
                    )
                    SDWDivider()
                    // 滑块 -100..100，实时下发（拖动即生效，不用等松手）。
                    // valueText 显示百分比 + 「偏左/居中/偏右」，比裸数字可读。
                    val balLabel = when {
                    balPct < -8 -> stringResource(R.string.settings_balance_left)
                    balPct > 8 -> stringResource(R.string.settings_balance_right)
                    else -> stringResource(R.string.settings_balance_center)
                    }
                    var balDraft by remember(refreshTrigger) { mutableFloatStateOf(balPct.toFloat()) }
                    SettingsSliderRow(
                    title = stringResource(R.string.settings_balance_slider),
                    value = balDraft,
                    valueRange = -100f..100f,
                    valueText = "${balLabel}  ${balDraft.toInt()}%",
                    onValueChange = { v ->
                    balDraft = v
                    val pct = v.toInt()
                    settingsPrefs.edit().putInt("stereo_balance", pct).apply()
                    MusicService.instance?.applyStereoBalance(pct / 100f)
                    },
                    onValueChangeFinished = { bump() }
                    )
                    SDWDivider()
                    SettingsNoteRow(stringResource(R.string.settings_balance_hint))
                    SDWDivider()
                }
                item { SettingsGroupLabel(stringResource(R.string.settings_usb_dac)) }
                item {
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

                    ThemeStagger(index = 3) {
                        SettingsCard {
                            SettingsSwitchItem(
                                icon = Icons.Default.Usb,
                                title = stringResource(R.string.settings_hardware_exclusive),
                                subtitle = when {
                                    usbActive -> stringResource(R.string.settings_active_dac, usbDacName)
                                    usbExclusiveEnabled && usbAvailable -> stringResource(R.string.settings_dac_connected, usbDacName)
                                    usbExclusiveEnabled -> stringResource(R.string.settings_waiting_dac)
                                    else -> stringResource(R.string.settings_bypass_mixer)
                                },
                                checked = usbExclusiveEnabled,
                                inCard = true,
                                onCheckedChange = { enabled ->
                                    usbExclusiveEnabled = enabled
                                    settingsPrefs.edit().putBoolean("usb_exclusive", enabled).apply()
                                    bump()
                                }
                            )
                            if (usbExclusiveEnabled && usbAvailable) {
                                SettingsNoteRow(text = dacDetected, color = Color(0xFF4CAF50))
                            }

                            SDWDivider()
                            // TPDF 抖动开关：降位到 16bit 输出时消除量化失真
                            SettingsPrefSwitch(
                                refreshKey = refreshTrigger, bump = bump, prefs = settingsPrefs, key = "tpdf_dither",
                                defaultValue = true, icon = Icons.Default.GraphicEq,
                                title = stringResource(R.string.settings_tpdf_dither),
                                subtitleOn = stringResource(R.string.settings_tpdf_dither_desc),
                                subtitleOff = stringResource(R.string.settings_tpdf_dither_desc),
                                onChange = { enabled ->
                                    // 【2026-09-08】双路下发：DAC 独占走 UsbDacManager(driver)，Oboe 外放/蓝牙走 OboeDirectPlayer
                                    // 历史只调 DAC 侧导致外放路径 dither 恒开无法关闭（底噪来源之一）
                                    UsbDacManager.setDitherEnabled(enabled)
                                    MusicService.instance?.oboeDirectPlayer?.setDitherEnabled(enabled)
                                }
                            )

                            SDWDivider()
                            // [2026-09-10 soft-start] Mask DAC PLL re-lock pops on stream start.
                            // Default OFF on purpose: the fade-in ramp modifies samples, so
                            // bit-perfect output is only traded away when the user opts in.
                            ThemeStagger(index = 4) {
                                SettingsPrefSwitch(
                                    refreshKey = refreshTrigger, bump = bump, prefs = settingsPrefs, key = "dac_soft_start",
                                    icon = Icons.Default.GraphicEq,
                                    title = stringResource(R.string.settings_dac_soft_start),
                                    subtitleOn = stringResource(R.string.settings_dac_soft_start_on),
                                    subtitleOff = stringResource(R.string.settings_dac_soft_start_off),
                                    onChange = { enabled -> UsbDacManager.setSoftStartEnabled(enabled) }
                                )
                            }

                        }
                    }
                }

                item { SettingsGroupLabel(stringResource(R.string.settings_bt_output)) }
                item {
                    SettingsCard {
                        SettingsPrefSwitch(
                            refreshKey = refreshTrigger, bump = bump, prefs = settingsPrefs, key = "bt_pre_emphasis",
                            defaultValue = true, icon = Icons.Default.Bluetooth,
                            title = stringResource(R.string.settings_bt_pre),
                            subtitleOn = stringResource(R.string.settings_bt_pre_desc),
                            subtitleOff = stringResource(R.string.settings_bt_pre_desc),
                            onChange = { enabled -> MusicService.instance?.applyBtPreEmphasis(enabled) }
                        )
                    }
                }

            }

            item {
                SettingsSectionTitle(
                    stringResource(R.string.settings_cat_library),
                    collapsible = true,
                    expanded = isSectionOpen("cat_library"),
                    itemCount = 30,
                    onToggle = { toggleSection("cat_library") }
                )
            }
            if (isSectionOpen("cat_library")) {
                item { SettingsGroupLabel(stringResource(R.string.settings_scan)) }
                item {
                    ThemeStagger(index = 1) {
                        SettingsCard {
                            SettingsItem(
                                icon = Icons.Default.Timer,
                                title = stringResource(R.string.settings_min_duration),
                                subtitle = stringResource(R.string.settings_min_duration_desc),
                                inCard = true
                            )
                            SDWDivider()
                            SettingsChipGroup(
                                options = listOf(10, 30, 60, 120, 150, 300).map { it.toString() to "${it}s" },
                                selectedKey = minDuration.toString(),
                                onSelect = { key ->
                                    minDuration = key.toInt()
                                    uiPrefs.edit().putInt("min_duration", key.toInt()).apply()
                                    bump()
                                }
                            )
                        }
                    }
                }

                item { SettingsGroupLabel(stringResource(R.string.settings_section_library)) }
                item {
                    val ctx = context
                    var scanning by remember(refreshTrigger) { mutableStateOf(false) }
                    var scanDone by remember(refreshTrigger) { mutableStateOf(0) }
                    var scanTotal by remember(refreshTrigger) { mutableStateOf(0) }
                    // [V8.101] 媒体库修复中状态（与 BPM 扫描分开，避免复用 scanning 导致提示文案串味）
                    var repairing by remember(refreshTrigger) { mutableStateOf(false) }
                    // [V8.102] 「修复媒体库」专属进度（不分阶段时不会再和列表扫描互顶）
                    val rp by com.sdw.music.player.SongRepository.repairProgress.collectAsState()

                    SettingsCard {
                        if (scanning) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(22.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = if (scanTotal > 0) stringResource(R.string.settings_bpm_scanning, scanDone, scanTotal) else bpmScanningStart,
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
                                inCard = true,
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
                                            bump()
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

                        SDWDivider()
                        // 【v8.22】强制重扫全部：全量重测覆盖旧缓存（清半速错值/过期数据）
                        SettingsActionRow(
                            title = stringResource(R.string.settings_force_rescan_hint),
                            actionLabel = stringResource(R.string.settings_force_rescan),
                            onClick = {
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
                                        bump()
                                        android.widget.Toast.makeText(
                                            appCtx,
                                            appCtx.getString(R.string.settings_rescan_done, detected),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        )

                        SDWDivider()
                        // [V8.101] 修复媒体库：遍历本地文件夹 → 通知媒体库重建索引 → 重扫 → 兜底合并
                        // 用法：嵌入封面/歌词后歌曲从列表消失时点它。不依赖 MediaStore 能否自己反应过来。
                        val rpNow = rp
                        SettingsActionRow(
                            title = stringResource(R.string.settings_repair_mediastore_hint),
                            actionLabel = stringResource(
                                if (repairing) R.string.settings_repair_mediastore_running
                                else R.string.settings_repair_mediastore
                            ),
                            enabled = !repairing,
                            onClick = {
                                repairing = true
                                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val appCtx = ctx.applicationContext
                                    val found = try {
                                        com.sdw.music.player.SongRepository.repairMediaStore(appCtx)
                                    } catch (e: Exception) {
                                        android.util.Log.e("SettingsScreen", "repairMediaStore failed", e)
                                        0
                                    }
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        onBpmScanned()
                                        repairing = false
                                        bump()
                                        android.widget.Toast.makeText(
                                            appCtx,
                                            appCtx.getString(R.string.settings_repair_mediastore_done, found),
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        ) {
                            if (repairing && rpNow != null) {
                                Spacer(Modifier.height(8.dp))
                                val phaseName = when (rpNow.phase) {
                                    0 -> stringResource(R.string.settings_repair_phase_walk)
                                    1 -> stringResource(R.string.settings_repair_phase_media)
                                    2 -> stringResource(R.string.settings_repair_phase_scan)
                                    else -> stringResource(R.string.settings_repair_phase_merge)
                                }
                                if (rpNow.total > 0) {
                                    LinearProgressIndicator(
                                        progress = { (rpNow.done.toFloat() / rpNow.total).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        phaseName + " · " + stringResource(
                                            R.string.settings_progress_ratio,
                                            rpNow.done, rpNow.total, rpNow.done * 100 / rpNow.total
                                        ),
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        phaseName + " · " + rpNow.done,
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }

                item { SettingsGroupLabel(stringResource(R.string.settings_backup)) }
                item {
                    SettingsCard {
                        SettingsNoteRow(stringResource(R.string.settings_backup_sub))

                        SDWDivider()
                        // 导出范围：默认只带音频设置 —— 换设备最常见就是只想把调好的声音搬过去
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                stringResource(R.string.settings_backup_scope),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(8.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SettingsBackup.Scope.entries.forEach { scope ->
                                    val on = backupScopes.contains(scope)
                                    FilterChip(
                                        selected = on,
                                        onClick = { backupScopes = if (on) backupScopes - scope else backupScopes + scope },
                                        label = {
                                            Text(
                                                stringResource(
                                                    when (scope) {
                                                        SettingsBackup.Scope.AUDIO -> R.string.settings_backup_scope_audio
                                                        SettingsBackup.Scope.UI -> R.string.settings_backup_scope_ui
                                                    }
                                                ),
                                                fontSize = 12.sp
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                        )
                                    )
                                }
                            }
                        }

                        SDWDivider()
                        // 恢复方式：合并 = 只覆盖文件里有的项（推荐）；覆盖 = 清空同名配置再整体写回
                        SettingsChipGroup(
                            options = listOf(
                                SettingsBackup.Mode.MERGE.name to stringResource(R.string.settings_backup_mode_merge),
                                SettingsBackup.Mode.REPLACE.name to stringResource(R.string.settings_backup_mode_replace)
                            ),
                            selectedKey = backupMode.name,
                            onSelect = { backupMode = SettingsBackup.Mode.valueOf(it) }
                        )

                        SDWDivider()
                        SettingsItem(
                            icon = Icons.Default.Backup,
                            title = stringResource(R.string.settings_backup_export),
                            subtitle = stringResource(R.string.settings_backup_export_sub),
                            inCard = true,
                            onClick = {
                                val stamp = java.text.SimpleDateFormat(
                                    "yyyyMMdd-HHmm", java.util.Locale.CHINA
                                ).format(java.util.Date())
                                backupExportLauncher.launch("SDWMP3-settings-$stamp.json")
                            }
                        )
                        SDWDivider()
                        SettingsItem(
                            icon = Icons.Default.Share,
                            title = stringResource(R.string.settings_backup_share),
                            subtitle = stringResource(R.string.settings_backup_share_sub),
                            inCard = true,
                            onClick = {
                                backupMsg = shareBackupJson(context, SettingsBackup.buildJson(context, backupScopes.toList()))
                            }
                        )

                        SDWDivider()
                        SettingsItem(
                            icon = Icons.Default.CloudUpload,
                            title = stringResource(R.string.settings_backup_import),
                            subtitle = stringResource(
                                R.string.settings_backup_import_sub,
                                stringResource(
                                    if (backupMode == SettingsBackup.Mode.REPLACE) {
                                        R.string.settings_backup_mode_replace
                                    } else {
                                        R.string.settings_backup_mode_merge
                                    }
                                )
                            ),
                            inCard = true,
                            onClick = { backupImportLauncher.launch(arrayOf("application/json", "text/plain")) }
                        )

                        backupMsg?.let { msg ->
                            SDWDivider()
                            SettingsNoteRow(msg, if (backupMsgOk) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)
                        }
                    }
                }

            }

            item {
                SettingsSectionTitle(
                    stringResource(R.string.settings_cat_interface),
                    collapsible = true,
                    expanded = isSectionOpen("cat_ui"),
                    itemCount = 42,
                    onToggle = { toggleSection("cat_ui") }
                )
            }
            if (isSectionOpen("cat_ui")) {
                item { SettingsGroupLabel(stringResource(R.string.settings_section_display)) }
                item {
                    // 【V8.439】进度条上方显示什么：四选一（VU 表 / 整曲波形 / 两者 / 都不显示）。
                    // 原来是单一开关，只能"有VU 表 / 没 VU 表"。波形（djay 着色 + 进拍/出拍横线）
                    // 一直写好了但没有入口，所以用户从来看不到它—— 现在给它一个常驻开关。
                    var visualMode by remember(refreshTrigger) {
                        mutableStateOf(com.sdw.music.player.ui.components.PlayerVisualMode.read(uiPrefs))
                    }
                    val vuOnlyLabel = stringResource(R.string.pv_mode_vu)
                    val noneLabel = stringResource(R.string.pv_mode_none)
                    SettingsCard {
                        SettingsChipGroup(
                            options = listOf(
                                "0" to vuOnlyLabel,
                                "3" to noneLabel
                            ),
                            selectedKey = visualMode.key.toString(),
                            label = stringResource(R.string.pv_above_progress),
                            onSelect = { key ->
                                val m = com.sdw.music.player.ui.components.PlayerVisualMode.fromKey(key.toInt())
                                visualMode = m
                                com.sdw.music.player.ui.components.PlayerVisualMode.write(uiPrefs, m)
                                bump()
                            }
                        )
                        // VU 表样式只在"会显示 VU 表"时才有意义
                        if (visualMode.showVu) {
                            SDWDivider()
                            val analogLabel = stringResource(R.string.vu_style_analog)
                            val mixerLabel = stringResource(R.string.vu_style_mixer)
                            SettingsChipGroup(
                                options = com.sdw.music.player.ui.components.VuMeterStyle.entries.mapIndexed { idx, style ->
                                    idx.toString() to when (style) {
                                        com.sdw.music.player.ui.components.VuMeterStyle.ANALOG_NEEDLE -> analogLabel
                                        com.sdw.music.player.ui.components.VuMeterStyle.MIXER -> mixerLabel
                                    }
                                },
                                selectedKey = uiPrefs.getInt("vu_meter_style", 1).toString(),
                                onSelect = { key ->
                                    uiPrefs.edit().putInt("vu_meter_style", key.toInt()).apply()
                                    bump()
                                }
                            )
                        }
                    }
                }
                item {
                    SettingsCard {
                        SettingsPrefSwitch(
                            refreshKey = refreshTrigger, bump = bump, prefs = uiPrefs, key = "cover_color_bg",
                            defaultValue = true, icon = Icons.Default.Palette,
                            title = stringResource(R.string.settings_cover_bg),
                            subtitleOn = stringResource(R.string.settings_cover_bg_on),
                            subtitleOff = offLabel
                        )
                        SDWDivider()
                        SettingsPrefSwitch(
                            refreshKey = refreshTrigger, bump = bump, prefs = uiPrefs, key = "moto_edge_light",
                            defaultValue = true, icon = Icons.Default.LightMode,
                            title = stringResource(R.string.settings_edge_light),
                            subtitleOn = stringResource(R.string.settings_edge_on),
                            subtitleOff = offLabel
                        )
                    }
                }
                // === Immersive player [V8.71] ===
                item {
                    var immersivePlayer by remember(refreshTrigger) {
                        mutableStateOf(uiPrefs.getBoolean("immersive_player", false))
                    }
                    SettingsCard {
                        SettingsSwitchItem(
                            icon = Icons.Default.Fullscreen,
                            title = stringResource(R.string.settings_immersive_player),
                            subtitle = if (immersivePlayer) stringResource(R.string.settings_immersive_on) else offLabel,
                            checked = immersivePlayer,
                            inCard = true,
                            onCheckedChange = { enabled ->
                                immersivePlayer = enabled
                                uiPrefs.edit().putBoolean("immersive_player", enabled).apply()
                                bump()
                            }
                        )
                        if (immersivePlayer) {
                            SDWDivider()
                            SettingsPrefSwitch(
                                refreshKey = refreshTrigger, bump = bump, prefs = uiPrefs, key = "hide_song_info",
                                icon = Icons.Default.SubtitlesOff,
                                title = stringResource(R.string.settings_hide_song_info),
                                subtitleOn = stringResource(R.string.settings_immersive_on),
                                subtitleOff = offLabel
                            )
                            SDWDivider()
                            SettingsPrefSwitch(
                                refreshKey = refreshTrigger, bump = bump, prefs = uiPrefs, key = "hide_spec",
                                icon = Icons.Default.Info,
                                title = stringResource(R.string.settings_hide_spec),
                                subtitleOn = stringResource(R.string.settings_immersive_on),
                                subtitleOff = offLabel
                            )
                            SDWDivider()
                            SettingsPrefSwitch(
                                refreshKey = refreshTrigger, bump = bump, prefs = uiPrefs, key = "hide_bottom",
                                icon = Icons.Default.MoreHoriz,
                                title = stringResource(R.string.settings_hide_bottom),
                                subtitleOn = stringResource(R.string.settings_immersive_on),
                                subtitleOff = offLabel
                            )
                        }
                    }
                }
                // === 播放页底部按键自动隐藏 ===
                item {
                    SettingsCard {
                        SettingsPrefSwitch(
                            refreshKey = refreshTrigger, bump = bump, prefs = uiPrefs,
                            key = "auto_hide_bottom", defaultValue = true,
                            icon = Icons.Default.VisibilityOff,
                            title = stringResource(R.string.settings_auto_hide_bottom),
                            subtitleOn = stringResource(R.string.settings_auto_on),
                            subtitleOff = stringResource(R.string.settings_auto_off)
                        )
                    }
                }

                item { SettingsGroupLabel(stringResource(R.string.settings_widget)) }
                item {
                    val widgetPref = context.getSharedPreferences("widget_prefs", android.content.Context.MODE_PRIVATE)
                    SettingsCard {
                        SettingsPrefSwitch(
                            refreshKey = refreshTrigger, bump = bump, prefs = widgetPref, key = "transparent_bg",
                            icon = Icons.Default.BlurOn,
                            title = stringResource(R.string.settings_transparent_widget),
                            subtitleOn = stringResource(R.string.settings_widget_transparent),
                            subtitleOff = stringResource(R.string.settings_widget_gradient),
                            onChange = {
                                // 立即刷新所有 widget
                                try {
                                    com.sdw.music.player.widget.MusicWidgetProvider.updateAllWidgets(context)
                                } catch (_: Exception) {}
                            }
                        )
                    }
                }

                item { SettingsGroupLabel(stringResource(R.string.settings_appearance)) }
                item {
                    ThemeStagger(index = 6) {
                        Box(modifier = Modifier.onGloballyPositioned { coords ->
                            // [V8.110] 开关在行尾：取行的右侧 82% 处、垂直居中作为扩散中心
                            val pos = coords.positionInRoot()
                            val size = coords.size
                            themeSwitchX = pos.x + size.width * 0.82f
                            themeSwitchY = pos.y + size.height * 0.5f
                        }) {
                            SettingsCard {
                                SettingsSwitchItem(
                                    icon = Icons.Default.LightMode,
                                    title = stringResource(R.string.settings_light_mode),
                                    subtitle = if (com.sdw.music.player.ThemeManager.lightMode.value)
                                        stringResource(R.string.settings_light_mode_on)
                                    else stringResource(R.string.settings_light_mode_off),
                                    checked = com.sdw.music.player.ThemeManager.lightMode.value,
                                    inCard = true,
                                    onCheckedChange = { enabled ->
                                        // [V8.110] 先交扩散中心，再换色板（顺序不能反：换了色板这一帧
                                        // MaterialTheme 已经重组，背景取到的就是新值了）
                                        ThemeFlip.mark(Offset(themeSwitchX, themeSwitchY))
                                        com.sdw.music.player.ThemeManager.setLightMode(context, enabled)
                                        bump()
                                    }
                                )
                            }
                        }
                    }
                }
                // [V8.111] 浅色模式开关正下方：跟随系统深/浅色主题
                item {
                    ThemeStagger(index = 7) {
                        Box(modifier = Modifier.onGloballyPositioned { coords ->
                            val pos = coords.positionInRoot()
                            val size = coords.size
                            followSwitchX = pos.x + size.width * 0.82f
                            followSwitchY = pos.y + size.height * 0.5f
                        }) {
                            SettingsCard {
                                SettingsSwitchItem(
                                    icon = Icons.Default.BrightnessAuto,
                                    title = stringResource(R.string.settings_follow_system),
                                    subtitle = if (com.sdw.music.player.ThemeManager.followSystem.value)
                                        stringResource(R.string.settings_follow_system_on)
                                    else stringResource(R.string.settings_follow_system_off),
                                    checked = com.sdw.music.player.ThemeManager.followSystem.value,
                                    inCard = true,
                                    onCheckedChange = { enabled ->
                                        ThemeFlip.mark(Offset(followSwitchX, followSwitchY))
                                        com.sdw.music.player.ThemeManager.setFollowSystem(context, enabled)
                                        bump()
                                    }
                                )
                            }
                        }
                    }
                }

                item { SettingsGroupLabel(stringResource(R.string.settings_wallpaper)) }
                item {
                    val imagePicker = rememberLauncherForActivityResult(
                        ActivityResultContracts.GetContent()
                    ) { uri ->
                        if (uri != null) {
                            com.sdw.music.player.WallpaperManager.setWallpaper(context, uri)
                            bump()
                        }
                    }
                    SettingsCard {
                        SettingsItem(
                            icon = Icons.Default.Wallpaper,
                            title = stringResource(R.string.settings_wallpaper_pick),
                            subtitle = if (com.sdw.music.player.WallpaperManager.hasWallpaper())
                                stringResource(R.string.settings_wallpaper_set)
                            else stringResource(R.string.settings_wallpaper_none),
                            inCard = true,
                            onClick = { imagePicker.launch("image/*") }
                        )
                        if (com.sdw.music.player.WallpaperManager.hasWallpaper()) {
                            SDWDivider()
                            // 取色感知开关
                            SettingsSwitchItem(
                                icon = Icons.Default.Palette,
                                title = stringResource(R.string.settings_wallpaper_color_aware),
                                subtitle = stringResource(R.string.settings_wallpaper_color_aware_desc),
                                checked = com.sdw.music.player.WallpaperManager.colorAwareEnabled.value,
                                inCard = true,
                                onCheckedChange = { enabled ->
                                    com.sdw.music.player.WallpaperManager.setColorAware(context, enabled)
                                    bump()
                                }
                            )
                            SDWDivider()
                            SettingsSliderRow(
                                title = stringResource(R.string.settings_wallpaper_blur),
                                value = com.sdw.music.player.WallpaperManager.blurAmount.value,
                                valueRange = 0f..40f,
                                valueText = com.sdw.music.player.WallpaperManager.blurAmount.value.toInt().toString(),
                                onValueChange = { com.sdw.music.player.WallpaperManager.setBlur(context, it) }
                            )
                            SDWDivider()
                            SettingsSliderRow(
                                title = stringResource(R.string.settings_wallpaper_scrim),
                                value = com.sdw.music.player.WallpaperManager.scrimAmount.value,
                                valueRange = 0f..0.85f,
                                valueText = "${(com.sdw.music.player.WallpaperManager.scrimAmount.value * 100).toInt()}%",
                                onValueChange = { com.sdw.music.player.WallpaperManager.setScrim(context, it) }
                            )
                            SDWDivider()
                            SettingsItem(
                                icon = Icons.Default.Delete,
                                title = stringResource(R.string.settings_wallpaper_clear),
                                subtitle = stringResource(R.string.settings_wallpaper_clear_desc),
                                inCard = true,
                                onClick = {
                                    com.sdw.music.player.WallpaperManager.clearWallpaper(context)
                                    bump()
                                }
                            )
                        }
                    }
                }

                item { SettingsGroupLabel(stringResource(R.string.settings_lyrics_display)) }
                item {
                    var lyricFontSize by remember(refreshTrigger) {
                        mutableStateOf(uiPrefs.getInt("lyric_font_size", 28))
                    }
                    var lyricVisibleLines by remember(refreshTrigger) {
                        mutableStateOf(uiPrefs.getInt("lyric_visible_lines", 7))
                    }
                    SettingsCard {
                        SettingsItem(
                            icon = Icons.Default.FormatSize,
                            title = stringResource(R.string.settings_lyric_font_size),
                            subtitle = stringResource(R.string.settings_font_size_value, lyricFontSize),
                            inCard = true
                        )
                        SDWDivider()
                        // [fix] 字号选项显示「小/中/大」而非具体 sp 数值
                        SettingsChipGroup(
                            options = listOf(
                                "24" to stringResource(R.string.size_small),
                                "28" to stringResource(R.string.size_medium),
                                "32" to stringResource(R.string.size_large)
                            ),
                            selectedKey = lyricFontSize.toString(),
                            onSelect = { key ->
                                lyricFontSize = key.toInt()
                                uiPrefs.edit().putInt("lyric_font_size", key.toInt()).apply()
                                bump()
                            }
                        )
                        SDWDivider()
                        SettingsItem(
                            icon = Icons.AutoMirrored.Filled.ViewList,
                            title = stringResource(R.string.settings_lyric_visible_lines),
                            subtitle = stringResource(R.string.settings_lines_value, lyricVisibleLines),
                            inCard = true
                        )
                        SDWDivider()
                        SettingsChipGroup(
                            options = listOf("5", "7", "9").map { it to it },
                            selectedKey = lyricVisibleLines.toString(),
                            onSelect = { key ->
                                lyricVisibleLines = key.toInt()
                                uiPrefs.edit().putInt("lyric_visible_lines", key.toInt()).apply()
                                bump()
                            }
                        )
                        SDWDivider()
                        // 【V8.428】全屏歌词页 3 秒无操作后隐藏顶部操作栏 + 底部切歌按钮。
                        // 存 uiPrefs（sdw_music_prefs），与同一分组里的字号/行数放一起。
                        var lyricAutoHide by remember(refreshTrigger) {
                            mutableStateOf(uiPrefs.getBoolean("lyric_controls_auto_hide", true))
                        }
                        SettingsSwitchItem(
                            icon = Icons.Default.VisibilityOff,
                            title = stringResource(R.string.settings_lyric_auto_hide),
                            subtitle = stringResource(R.string.settings_lyric_auto_hide_desc),
                            checked = lyricAutoHide,
                            inCard = true,
                            onCheckedChange = { enabled ->
                                lyricAutoHide = enabled
                                uiPrefs.edit().putBoolean("lyric_controls_auto_hide", enabled).apply()
                                bump()
                            }
                        )
                    }
                }

            }

            item {
                SettingsSectionTitle(
                    stringResource(R.string.settings_cat_system),
                    collapsible = true,
                    expanded = isSectionOpen("cat_system"),
                    itemCount = 15,
                    onToggle = { toggleSection("cat_system") }
                )
            }
            if (isSectionOpen("cat_system")) {
                item { SettingsGroupLabel(stringResource(R.string.settings_tools)) }
                item {
                    SettingsCard {
                        SettingsItem(
                            icon = Icons.Default.Image,
                            title = stringResource(R.string.settings_cover_tool),
                            subtitle = stringResource(R.string.settings_cover_tool_desc),
                            inCard = true,
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
                }

                item { SettingsGroupLabel(stringResource(R.string.settings_standby)) }
                item {
                    // [UI-TIDY] 档位键改成稳定 ASCII：中文只作为展示文案（走 stringResource），
                    // 不再当键值 —— 之前键值和界面文案同一份中文，改文案就会误伤已存偏好。
                    val idleKeys = listOf("working", "frequent", "rare", "restricted")
                    val legacyIdleKeys = mapOf("常用" to "working", "频繁" to "frequent", "受限" to "restricted")
                    var idleLevel by remember(refreshTrigger) {
                        val raw = uiPrefs.getString("idle_level", "rare") ?: "rare"
                        mutableStateOf(legacyIdleKeys[raw] ?: raw.takeIf { it in idleKeys } ?: "rare")
                    }
                    SettingsCard {
                        SettingsItem(
                            icon = Icons.Default.Schedule,
                            title = stringResource(R.string.settings_idle),
                            subtitle = when (idleLevel) {
                                "working" -> stringResource(R.string.settings_idle_working)
                                "frequent" -> stringResource(R.string.settings_idle_frequent)
                                "restricted" -> stringResource(R.string.settings_idle_restricted)
                                else -> stringResource(R.string.settings_idle_rare)
                            },
                            inCard = true
                        )
                        SDWDivider()
                        SettingsChipGroup(
                            options = listOf(
                                "working" to stringResource(R.string.idle_short_working),
                                "frequent" to stringResource(R.string.idle_short_frequent),
                                "rare" to stringResource(R.string.idle_short_rare),
                                "restricted" to stringResource(R.string.idle_short_restricted)
                            ),
                            selectedKey = idleLevel,
                            onSelect = { key ->
                                idleLevel = key
                                uiPrefs.edit().putString("idle_level", key).apply()
                                bump()
                            }
                        )
                    }
                }

                item { SettingsGroupLabel(stringResource(R.string.settings_section_system)) }
                item {
                    SettingsCard {
                        if (onNavigateToAudioDiagnostic != null) {
                            SettingsItem(
                                icon = Icons.Default.QueryStats,
                                title = stringResource(R.string.settings_audio_diag),
                                subtitle = stringResource(R.string.settings_audio_diag_desc),
                                inCard = true,
                                onClick = onNavigateToAudioDiagnostic
                            )
                        }
                        if (onNavigateToAudioQuality != null) {
                            if (onNavigateToAudioDiagnostic != null) SDWDivider()
                            SettingsItem(
                                icon = Icons.Default.GraphicEq,
                                title = stringResource(R.string.settings_audio_quality),
                                subtitle = stringResource(R.string.settings_audio_quality_desc),
                                inCard = true,
                                onClick = onNavigateToAudioQuality
                            )
                        }
                        SDWDivider()
                        // [V3.3.6] DebugLog 开关（默认关闭以优化性能）
                        SettingsPrefSwitch(
                            refreshKey = refreshTrigger, bump = bump, prefs = settingsPrefs, key = "debug_log_enabled",
                            icon = Icons.Default.BugReport,
                            title = stringResource(R.string.settings_debug_log),
                            subtitleOn = stringResource(R.string.settings_debug_on),
                            subtitleOff = stringResource(R.string.settings_debug_off),
                            onChange = { enabled ->
                                // 同步到 DebugLog 单例
                                com.sdw.music.player.core.audio.DebugLog.enabled = enabled
                            }
                        )
                    }
                }

                // === USB DAC Debug Log — Salt-style: Kotlin + Native (C++) + Audio Info ===
                // V3.3.10: 仅在 DebugLog 开启时显示，避免滚动卡顿
                if (com.sdw.music.player.core.audio.DebugLog.enabled) {
                    item {
                        val scrollState = rememberScrollState()
                        // [V8.115] USB 日志框统一玻璃语言。
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                                .glassSurface(shape = SDWShapes.Row, fillBoost = 0f)
                                .padding(vertical = 6.dp)
                        ) {
                            Column(modifier = Modifier.heightIn(max = 240.dp)) {
                                Text(
                                    text = fullLog.ifEmpty { logEmpty },
                                    color = Color(0xFF00FF88),
                                    fontSize = 9.sp,
                                    lineHeight = 12.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    modifier = Modifier.padding(8.dp).verticalScroll(scrollState)
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End) {
                                val clipboardManager = LocalClipboardManager.current
                                TextButton(onClick = {
                                    (fullLog + "\n\n" + logAudioInfo + "\n" + (UsbDacManager.getDetailedDacInfo() ?: "") +
                                     "\n" + logStream.format(if (UsbDacManager.isStreaming()) statusOn else statusOff) +
                                     "\n" + logUnderruns.format(UsbDacManager.getUnderrunCount())
                                    ).also { clipboardManager.setText(AnnotatedString(it)) }
                                }) { Text(stringResource(R.string.action_copy), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp) }
                                TextButton(onClick = { com.sdw.music.player.core.audio.DebugLog.clear(); UsbDacManager.clearNativeDebugLog(); bump() }) {
                                    Text(stringResource(R.string.action_clear), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                }
                                TextButton(onClick = { bump() }) {
                                    Text(stringResource(R.string.action_refresh), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                // === Audio Info Panel — Salt-style real-time monitor ===
                item {
                    val stats = remember(refreshTrigger) { UsbDacManager.getDetailedDacInfo() ?: noDacText }
                    val underruns = remember(refreshTrigger) { UsbDacManager.getUnderrunCount() }
                    val streaming = remember(refreshTrigger) { UsbDacManager.isStreaming() }
                    val nativeLogLen = remember(refreshTrigger) { (UsbDacManager.getNativeDebugLog() ?: "").length }
                    // [V8.115] 音频信息卡统一玻璃语言。
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                            .glassSurface(shape = SDWShapes.Row, fillBoost = 0f)
                            .padding(12.dp)
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            InfoChip(stringResource(R.string.settings_info_stream), if (streaming) statusOn else statusOff,
                                if (streaming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            InfoChip(stringResource(R.string.settings_info_underrun), "$underruns",
                                if (underruns > 0) Color(0xFFFF6B6B) else Color(0xFF00FF88))
                            InfoChip(stringResource(R.string.settings_info_native_log), "${nativeLogLen}B", MaterialTheme.colorScheme.onSurfaceVariant)
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

                item { SettingsGroupLabel(stringResource(R.string.language)) }
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
                    SettingsCard {
                        SettingsItem(
                            icon = Icons.Default.Translate,
                            title = stringResource(R.string.language),
                            subtitle = langLabel,
                            inCard = true,
                            onClick = {
                                // 循环切换：跟随系统 → 中文 → English → Español → 跟随系统
                                val supported = AppLanguageManager.SUPPORTED
                                val curIdx = supported.indexOf(currentLang).coerceAtLeast(0)
                                val next = supported[(curIdx + 1) % supported.size]
                                AppLanguageManager.set(context, next)
                                bump()
                            }
                        )
                    }
                }

                item { SettingsGroupLabel(stringResource(R.string.settings_about)) }
                item {
                    SettingsCard {
                        SettingsItem(
                            icon = Icons.Default.Info,
                            title = stringResource(R.string.settings_about_name),
                            subtitle = stringResource(
                                R.string.settings_about_sub,
                                BuildConfig.VERSION_NAME,
                                stringResource(R.string.settings_about_author)
                            ),
                            inCard = true
                        )
                        SDWDivider()
                        SettingsItem(
                            icon = Icons.Default.Forum,
                            title = stringResource(R.string.settings_dev_group),
                            subtitle = stringResource(R.string.settings_dev_group_desc),
                            inCard = true,
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
                }

            }

            item {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(
    title: String,
    collapsible: Boolean = false,
    expanded: Boolean = true,
    itemCount: Int = 0,
    onToggle: (() -> Unit)? = null
) {
    // [V8.115] 分组头统一到主界面语言（V8.83/V8.115）：accent 3dp 竖条 + accent 文字，
    // 与专辑/歌手列表的分组头、队列当前曲标识同源。
    // 【V8.311】加折叠能力：设置项已 17 个分区 / 60+ 条目，全展开是一堵墙。
    // 长分区（条目多）默认折叠并显示条目数，点标题即展开/收起。
    val rot by animateFloatAsState(if (expanded) 0f else -90f, label = "sectionArrow")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 16.dp, top = 22.dp, bottom = 8.dp)
            .then(if (collapsible && onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .background(MaterialTheme.colorScheme.primary, SDWShapes.Badge)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (itemCount > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                itemCount.toString(),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelSmall
            )
        }
        if (collapsible && onToggle != null) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = if (expanded) stringResource(R.string.settings_collapse) else stringResource(R.string.settings_expand),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp).rotate(rot)
            )
        }
    }
}

/**
 * 【V8.311】卡内二级分组标题。用于一张卡里塞了多组不同语义条目的情况
 * （典型是「DJ 效果」那张卡：效果总线 / 频段/变速/模板 / 分轨 混在一起），
 * 给每组一个视觉锚点，避免用户在一张长卡里迷失。
 */
@Composable
private fun SettingsGroupLabel(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp)
    )
}

/**
 * V8.164 分组玻璃卡：一组相关条目共用一块玻璃面，组内用发丝线分隔。
 * 之前每个条目各自一小块玻璃 + 一堆 Spacer/Divider，滚动时看起来是"碎片列表"。
 */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .glassSurface(shape = SDWShapes.Row, fillBoost = 0f)
            .padding(vertical = 6.dp),
        content = content
    )
}

/** 卡片内的条目外壳：inCard=true 时只做内缩，外层卡片已经提供玻璃面。 */
@Composable
private fun Modifier.settingsShell(inCard: Boolean): Modifier =
    if (inCard) Modifier.fillMaxWidth().padding(horizontal = 8.dp)
    else Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 3.dp)
        .glassSurface(shape = SDWShapes.Row, fillBoost = 0f)

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    inCard: Boolean = false
) {
    // [V8.115] 设置项统一到主界面玻璃语言：可点击项走弹簧按压，与主界面列表行同形。
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .settingsShell(inCard)
            .then(
                if (onClick != null)
                    Modifier.springPress(interaction, pressedScale = 0.975f)
                        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = if (inCard) 10.dp else 12.dp),
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
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    inCard: Boolean = false
) {
    Row(
        modifier = Modifier
            .settingsShell(inCard)
            .padding(horizontal = 16.dp, vertical = if (inCard) 8.dp else 10.dp),
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

/**
 * 【V8.170】响度统一管理：落偏好 + 立刻按当前曲目重算增益并下发，
 * 这样在设置里拨开关/切档位当场就能听到效果，不用等下一首。
 */
private fun pushNormalization(
    context: android.content.Context,
    songPath: String?,
    cfg: LoudnessNormalizer.Config
) {
    LoudnessNormalizer.save(context, cfg)
    val db = if (songPath.isNullOrBlank()) 0f else LoudnessNormalizer.gainFor(songPath, cfg)
    MusicService.instance?.applyNormalization(db)
}

/**
 * V8.164 「SharedPreferences 布尔值 → 开关」一行封装：读写 pref、副标题随状态切换、
 * 刷新界面、可选副作用回调。设置页里十几处重复代码收敛到这里。
 */
@Composable
private fun SettingsPrefSwitch(
    refreshKey: Int,
    bump: () -> Unit,
    prefs: android.content.SharedPreferences,
    key: String,
    icon: ImageVector,
    title: String,
    subtitleOn: String,
    subtitleOff: String,
    /**
     * 固定在标题下方的说明行，不随开关状态切换。
     * 用于「开了会绕过某层保护」这类必须提前讲清的副作用 —— 只写进 _on/_off 两句里，
     * 用户永远看不到，当初「开了直通反而更响」就是因为副标题没说清限幅被旁路了。
     */
    note: String? = null,
    defaultValue: Boolean = false,
    onChange: ((Boolean) -> Unit)? = null
) {
    var checked by remember(refreshKey) { mutableStateOf(prefs.getBoolean(key, defaultValue)) }
    Column {
        SettingsSwitchItem(
            icon = icon,
            title = title,
            subtitle = if (checked) subtitleOn else subtitleOff,
            checked = checked,
            inCard = true,
            onCheckedChange = { enabled ->
                checked = enabled
                prefs.edit().putBoolean(key, enabled).apply()
                onChange?.invoke(enabled)
                bump()
            }
        )
        if (note != null) {
            Text(
                note,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 54.dp, vertical = 4.dp)
            )
        }
    }
}

/** V8.164 卡片内的选项组：标题 + 自动换行的 chip 组（窄屏不再挤成一行溢出）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsOptionRow(
    title: String,
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            title,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(Modifier.height(8.dp))
        SettingsChipFlow(options, selectedKey, onSelect)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsChipGroup(
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    label: String? = null,     // 【V8.281】可选分组标题（同一节里有多组 chip 时用它区分）
    /** 【V8.318】置灰：当前条件下这组选项不生效（如分轨模式下 DJ 轨级模板被跳过）。
     *  置灰 + 在下方说明原因，比"可选但静默失效"好得多。 */
    enabled: Boolean = true,
    disabledHint: String? = null
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        if (!label.isNullOrEmpty()) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        SettingsChipFlow(options, selectedKey, onSelect, enabled)
        if (!enabled && !disabledHint.isNullOrEmpty()) {
            Text(
                disabledHint!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsChipFlow(
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    enabled: Boolean = true
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (optKey, label) ->
            val selected = optKey == selectedKey
            FilterChip(
                selected = selected,
                enabled = enabled,          // 【V8.318】置灰（分轨模式下模板不生效）
                onClick = { onSelect(optKey) },
                label = {
                    Text(
                        label,
                        color = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                else if (selected) MaterialTheme.colorScheme.onBackground
                                else MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                    selectedLabelColor = MaterialTheme.colorScheme.onBackground,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    labelColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.height(32.dp)
            )
        }
    }
}

/** V8.164 卡片内的滑块行：标题 + 数值 + 滑块，数值右对齐不再被滑块顶走。 */
@Composable
private fun SettingsSliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                valueText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange
        )
    }
}

/** V8.164 卡片内的「说明 + 右侧按钮」行（强制重扫 / 修复媒体库），可选 extra 放进度条。 */
@Composable
private fun SettingsActionRow(
    title: String,
    actionLabel: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    extra: (@Composable ColumnScope.() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            if (extra != null) extra()
        }
        Spacer(Modifier.width(8.dp))
        TextButton(enabled = enabled, onClick = onClick) {
            Text(actionLabel, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * 【V8.195】把备份 JSON 通过系统分享面板发出去（微信 / QQ / 网盘都吃这条路）。
 * 先落到 filesDir 再交 FileProvider —— file_paths.xml 里已经开放 files-path，
 * 所以不需要额外改权限配置，也不用往共享目录写垃圾文件。
 */
/** 把最近一次失败的提示原样存到 files/，方便直接从设备上取回排障。 */
private fun recordBackupError(context: android.content.Context, text: String) {
    try {
        java.io.File(context.filesDir, "backup_last_error.txt").writeText(text)
    } catch (e: Exception) {
        android.util.Log.w("SDW_BackupUI", "record error file failed", e)
    }
}

/** 把备份暂存到 App 私有目录（SAF 写不进去时的兜底，也是分享前的落盘点）。 */
private fun saveJsonToInternal(context: android.content.Context, json: String): java.io.File? {
    return try {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.CHINA).format(java.util.Date())
        val file = java.io.File(context.filesDir, "sdwmp3-settings-$stamp.json")
        file.writeText(json)
        file
    } catch (e: Exception) {
        android.util.Log.e("Settings", "save to internal failed", e)
        null
    }
}

/** 调起系统分享面板，把 file 作为 json 附件发出去。 */
private fun shareFile(context: android.content.Context, file: java.io.File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", file)
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        android.content.Intent.createChooser(
            intent,
            context.getString(R.string.settings_backup_chooser)
        )
    )
}

private fun shareBackupJson(context: android.content.Context, json: String): String {
    return try {
        val file = saveJsonToInternal(context, json)
            ?: return context.getString(R.string.settings_backup_share_tmp_failed)
        shareFile(context, file)
        context.getString(R.string.settings_backup_share_ok)
    } catch (e: Exception) {
        android.util.Log.e("Settings", "share backup failed", e)
        context.getString(R.string.settings_backup_share_failed, e.message ?: "")
    }
}

/** V8.164 卡片内的小字提示行（DAC 已连接、随机模式说明等）。 */
@Composable
private fun SettingsNoteRow(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = color, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun InfoChip(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.background(color.copy(alpha = 0.12f), SDWShapes.Thumb).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        Text(value, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
