package com.sdw.music.player.ui.screens
import com.sdw.music.player.core.audio.dsp.ParametricEqManager
import com.sdw.music.player.core.audio.dsp.AutoEqPresetManager
import com.sdw.music.player.core.audio.dsp.EqualizerManager
import com.sdw.music.player.core.audio.playback.MusicService
import com.sdw.music.player.core.audio.engine.OboeDirectPlayer

import androidx.compose.foundation.clickable
import com.sdw.music.player.ui.components.ExpressiveSwitch
import com.sdw.music.player.ui.components.SDWTopBar
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sdw.music.player.ui.theme.SDWShapes
import androidx.compose.ui.res.stringResource
import com.sdw.music.player.R
import com.sdw.music.player.core.audio.dsp.AutoEqPreset
import com.sdw.music.player.ui.components.glassSurface
import com.sdw.music.player.ui.components.springPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize

// Map EqPreset id to localized description string resource
@Composable
private fun presetDescription(presetId: String): String {
    val context = LocalContext.current
    val resId = context.resources.getIdentifier("eq_desc_$presetId", "string", context.packageName)
    return if (resId != 0) stringResource(resId) else presetId
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    onBack: () -> Unit,
    onNavigateToMseb: () -> Unit = {},
    onNavigateToParametricEq: () -> Unit = {}
) {
    val context = LocalContext.current
    val isOboeMode = EqualizerManager.isOboeMode(context)

    if (isOboeMode) {
        OboeEqFullScreen(onBack, onNavigateToMseb, onNavigateToParametricEq)
    } else {
        EqualizerPresetsScreen(onBack)
    }
}

// ================ Oboe 独占模式：DSP + Equalizer预设合并 ================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OboeEqFullScreen(
    onBack: () -> Unit,
    onNavigateToMseb: () -> Unit,
    onNavigateToParametricEq: () -> Unit = {}
) {
    val context = LocalContext.current
    val presets = remember { EqualizerManager.PRESETS }

    // AutoEQ 状态
    val autoEqTopBrands = remember { AutoEqPresetManager.topBrands(24) }
    var autoEqQuery by remember { mutableStateOf("") }
    val autoEqResults = remember(autoEqQuery) {
        if (autoEqQuery.isBlank()) emptyList() else AutoEqPresetManager.search(autoEqQuery, 300)
    }
    var currentAutoEqPreset by remember { mutableStateOf(EqualizerManager.getAutoEqPreset(context)) }

    val eqEnabled by EqualizerManager.enabledFlow.collectAsState()
    var currentPresetId by remember { mutableStateOf(EqualizerManager.getCurrentPresetId(context)) }

    LaunchedEffect(Unit) {
        EqualizerManager.restoreSettings(context)
        // 【V9.05】从参数 EQ 界面返回时，EqualizerScreen 会重新组合并触发上面的 restoreSettings，
        // 可能把共用的 AutoEQ 通道上的 PEQ 覆盖掉。这里紧接着把 PEQ 重新下发一遍，确保返回后效果保留。
        com.sdw.music.player.core.audio.dsp.ParametricEqManager.restoreSettings(context)
        currentPresetId = EqualizerManager.getCurrentPresetId(context)
    }

    Scaffold(
        topBar = {
            SDWTopBar(
                title = { Text(stringResource(R.string.eq_dsp_equalizer)) },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    Text(
                        // [V9.20v] 原为硬编码的 "On"/"Off"（无本地化，中文界面也显示英文）。
                        // 这里改成「均衡器 · 开 / 均衡器 · 关」——开关只管 5 段图形 EQ + AutoEQ，
                        // 光秃秃的 On/Off 看不出管的是什么。
                        text = stringResource(if (eqEnabled) R.string.eq_master_on else R.string.eq_master_off),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    ExpressiveSwitch(
                        checked = eqEnabled,
                        onCheckedChange = { on ->
                            EqualizerManager.setEnabled(on, context)
                            if (on && currentPresetId == "flat") {
                                presets.find { it.id != "flat" }?.let {
                                    currentPresetId = it.id
                                    EqualizerManager.applyPreset(it.id, context)
                                }
                            }
                        },
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // === MSEB 入口（Expressive 带色按钮）===
            item {
                FilledTonalButton(
                    onClick = onNavigateToMseb,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = MaterialTheme.shapes.large,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.eq_mseb_psychoacoustic),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Start
                    )
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }

            // === 手动参数 EQ（发烧级）入口 ===
            item {
                FilledTonalButton(
                    onClick = onNavigateToParametricEq,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = MaterialTheme.shapes.large,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.eq_peq_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Start
                    )
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }

            // === 5 段品牌 / 曲风预设已迁移至参数均衡器模板 ===
            item {
                Text(
                    text = stringResource(R.string.eq_presets_migrated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }

            // === AutoEQ Headphone Correction（带色父容器）===
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Headphones,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.eq_autoeq_calibration),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "10-band Precise EQ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                        )
                    }
                }
            }

            // AutoEQ 预设列表（按品牌分组）

            // AutoEQ 全量预设库（6033 款 / 652 品牌）—— 品牌折叠列表在这个量级不可用，改搜索
            item {
                // [P2 · M3 Expressive] 描边式 → 容器式（半透明玻璃），与主界面搜索框统一。
                // 顺带把清空键的裸 IconButton 换成 tonal 容器键（全站顶栏/行内键已统一）。
                TextField(
                    value = autoEqQuery,
                    onValueChange = { autoEqQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text(stringResource(R.string.eq_autoeq_search), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (autoEqQuery.isNotEmpty()) {
                            FilledTonalIconButton(
                                onClick = { autoEqQuery = "" },
                                shapes = IconButtonDefaults.shapes()
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.action_cancel)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
            }

            if (autoEqQuery.isBlank()) {
                item {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(autoEqTopBrands) { brand ->
                            FilterChip(
                                selected = false,
                                onClick = { autoEqQuery = brand },
                                label = { Text(brand) }
                            )
                        }
                    }
                }
                item {
                    Text(
                        text = stringResource(R.string.eq_autoeq_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                }
            } else {
                item {
                    Text(
                        text = stringResource(R.string.eq_autoeq_results, autoEqResults.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                }
                items(autoEqResults, key = { "autoeq_" + it.brand + "_" + it.name }) { aPreset ->
                    AutoEqPresetRow(
                        preset = aPreset,
                        isActive = currentAutoEqPreset == aPreset.name,
                        onClick = {
                            if (currentAutoEqPreset == aPreset.name) {
                                EqualizerManager.clearAutoEq(context)
                                currentAutoEqPreset = null
                            } else {
                                EqualizerManager.applyAutoEqPreset(aPreset.name, context)
                                currentAutoEqPreset = aPreset.name
                            }
                        }
                    )
                }
                if (autoEqResults.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.eq_autoeq_no_result),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }
                }
            }

            // AutoEQ 说明
            item {
                Text(
                    text = stringResource(R.string.eq_autoeq_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            // Oboe 模式说明
            item {
                Text(
                    text = stringResource(R.string.eq_oboe_dsp_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                )
            }
        }
    }
}

// ================ 非 Oboe 模式：Android Equalizer 预设 ================
@Composable
private fun AutoEqPresetRow(
    preset: AutoEqPreset,
    isActive: Boolean,
    onClick: () -> Unit
) {
    // [V8.115] 统一玻璃语言。
    val rowInteraction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .glassSurface(shape = SDWShapes.Row, selected = isActive, fillBoost = 0f)
            .springPress(rowInteraction, pressedScale = 0.975f)
            .clickable(interactionSource = rowInteraction, indication = null, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = preset.brand + " \u00b7 " + preset.type + " \u00b7 Preamp " +
                        preset.preamp + "dB \u00b7 " + preset.filters.size + " bands",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            if (isActive) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.eq_activated),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EqualizerPresetsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val presets = remember { EqualizerManager.PRESETS }
    val eqAvailable by EqualizerManager.initialized.collectAsState()
    val enabled by EqualizerManager.enabledFlow.collectAsState()
    var currentPresetId by remember { mutableStateOf(EqualizerManager.getCurrentPresetId(context)) }

    // 【V7.XX】打OnEqualizer页面时主动初始化 EQ（onAudioSessionIdChanged 不可靠）
    LaunchedEffect(Unit) {
        if (!EqualizerManager.isInitialized() && !EqualizerManager.isOboeMode(context)) {
            var attempts = 0
            while (attempts < 10 && !EqualizerManager.isInitialized()) {
                val sessionId = MusicService.instance?.getAudioSessionId() ?: 0
                if (sessionId != 0) {
                    EqualizerManager.init(sessionId)
                    EqualizerManager.restoreSettings(context)
                    currentPresetId = EqualizerManager.getCurrentPresetId(context)
                }
                kotlinx.coroutines.delay(500)
                attempts++
            }
        }
    }

    Scaffold(
        topBar = {
            SDWTopBar(
                title = { Text(stringResource(R.string.eq_title)) },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    Text(
                        text = if (enabled) "On" else "Off",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    ExpressiveSwitch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            EqualizerManager.setEnabled(on, context)
                            if (on && currentPresetId == "flat") {
                                val first = presets.find { it.id != "flat" }
                                first?.let {
                                    currentPresetId = it.id
                                    EqualizerManager.applyPreset(it.id, context)
                                }
                            }
                        },
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 5 段品牌 / 曲风预设已迁移至参数均衡器模板（Oboe / DAC 输出模式下可用）
            item {
                Text(
                    text = stringResource(R.string.eq_presets_migrated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }

            // EQ Unavailable时提示
            if (!eqAvailable) {
                item {
                    Text(
                        text = stringResource(R.string.eq_android_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}


