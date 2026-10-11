package com.sdw.music.player.ui.screens

import androidx.compose.foundation.clickable
import com.sdw.music.player.ui.components.SDWDivider
import com.sdw.music.player.ui.theme.sectionTitleStyle
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sdw.music.player.ui.theme.SDWShapes
import androidx.compose.ui.res.stringResource
import com.sdw.music.player.EqualizerManager
import com.sdw.music.player.MusicService
import com.sdw.music.player.OboeDirectPlayer
import com.sdw.music.player.R
import com.sdw.music.player.core.audio.AutoEqPreset
import com.sdw.music.player.core.audio.AutoEqPresetManager
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
    onNavigateToMseb: () -> Unit = {}
) {
    val context = LocalContext.current
    val isOboeMode = EqualizerManager.isOboeMode(context)

    if (isOboeMode) {
        OboeEqFullScreen(onBack, onNavigateToMseb)
    } else {
        EqualizerPresetsScreen(onBack)
    }
}

// ================ Oboe 独占模式：DSP + Equalizer预设合并 ================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OboeEqFullScreen(onBack: () -> Unit, onNavigateToMseb: () -> Unit) {
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
        currentPresetId = EqualizerManager.getCurrentPresetId(context)
    }

    Scaffold(
        topBar = {
            SDWTopBar(
                title = { Text(stringResource(R.string.eq_dsp_equalizer)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    Text(
                        text = if (eqEnabled) "On" else "Off",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Switch(
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
            // === MSEB 入口 ===
            item {
                OutlinedButton(
                    onClick = onNavigateToMseb,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(stringResource(R.string.eq_mseb_psychoacoustic), style = MaterialTheme.typography.titleSmall)
                }
            }

            // === 分隔 ===
            item {
                SDWDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 1f)
                )
            }

            // === 28 种品牌Equalizer预设 ===
            item {
                Text(
                    text = stringResource(R.string.eq_brand_presets),
                    style = sectionTitleStyle(),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            items(presets) { preset ->
                val isSelected = currentPresetId == preset.id && eqEnabled
                // [V8.115] 统一玻璃语言：Surface -> glassSurface（选中走 accent 洗底 + 发丝描边，
                // 与主界面「正在播放行」同形），加弹簧按压。
                val eqInteraction = remember { MutableInteractionSource() }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                        .glassSurface(shape = SDWShapes.Row, selected = isSelected, fillBoost = 0f)
                        .springPress(eqInteraction, pressedScale = 0.975f)
                        .clickable(
                            interactionSource = eqInteraction,
                            indication = null
                        ) {
                            currentPresetId = preset.id
                            EqualizerManager.applyPreset(preset.id, context)
                        }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = preset.name,
                            style = sectionTitleStyle(),
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = presetDescription(preset.id),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // === AutoEQ Headphone Correction ===
            item {
                SDWDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 1f)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.eq_autoeq_calibration),
                        style = sectionTitleStyle(),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "10-band Precise EQ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // AutoEQ 预设列表（按品牌分组）

            // AutoEQ 全量预设库（6033 款 / 652 品牌）—— 品牌折叠列表在这个量级不可用，改搜索
            item {
                OutlinedTextField(
                    value = autoEqQuery,
                    onValueChange = { autoEqQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text(stringResource(R.string.eq_autoeq_search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (autoEqQuery.isNotEmpty()) {
                            IconButton(onClick = { autoEqQuery = "" }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.action_cancel)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    Text(
                        text = if (enabled) "On" else "Off",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Switch(
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
            items(presets) { preset ->
                val isSelected = currentPresetId == preset.id && enabled
                // [V8.115] 统一玻璃语言：Surface -> glassSurface（选中走 accent 洗底 + 发丝描边，
                // 与主界面「正在播放行」同形），加弹簧按压。
                val eqInteraction = remember { MutableInteractionSource() }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                        .glassSurface(shape = SDWShapes.Row, selected = isSelected, fillBoost = 0f)
                        .springPress(eqInteraction, pressedScale = 0.975f)
                        .clickable(
                            interactionSource = eqInteraction,
                            indication = null
                        ) {
                            currentPresetId = preset.id
                            EqualizerManager.applyPreset(preset.id, context)
                        }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = preset.name,
                            style = sectionTitleStyle(),
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = presetDescription(preset.id),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
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


