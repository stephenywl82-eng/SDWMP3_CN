package com.sdw.music.player.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import android.content.Context
import com.sdw.music.player.MsebCalculator
import com.sdw.music.player.MsebParams
import com.sdw.music.player.MsebPreset
import com.sdw.music.player.MsebPresets
import com.sdw.music.player.MusicService
import com.sdw.music.player.R
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MsebScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var params by remember { mutableStateOf(MsebCalculator.load(context)) }
    var enabled by remember { mutableStateOf(MsebCalculator.isEnabled(context)) }
    var savedName by remember { mutableStateOf("") }

    // Preset list (built-in + user)
    val presets = remember { MsebPresets.getAll(context) }

    // Save dialog
    var showSaveDialog by remember { mutableStateOf(false) }

    // ── 【V8.3】动态压缩（独立全局模块，独立开关）──
    val compPrefs = remember { context.getSharedPreferences("compressor", Context.MODE_PRIVATE) }
    var compEnabled by remember { mutableStateOf(compPrefs.getBoolean("enabled", false)) }
    var compThreshold by remember { mutableStateOf(compPrefs.getFloat("threshold", -18f)) }
    var compRatio by remember { mutableStateOf(compPrefs.getFloat("ratio", 2f)) }
    var compAttack by remember { mutableStateOf(compPrefs.getFloat("attack", 10f)) }
    var compRelease by remember { mutableStateOf(compPrefs.getFloat("release", 120f)) }
    var compMakeup by remember { mutableStateOf(compPrefs.getFloat("makeup", 0f)) }

    // FFT band levels — 8 bands from native
    var bandLevels by remember { mutableStateOf(floatArrayOf(0f,0f,0f,0f,0f,0f,0f,0f)) }

    LaunchedEffect(Unit) {
        while (true) {
            val oboe = MusicService.instance?.oboeDirectPlayer ?: break
            bandLevels = oboe.getBands8()
            delay(100)
        }
    }

    LaunchedEffect(Unit) {
        if (enabled && !params.isFlat) applyMseb(params)
    }

    // 【V8.3】进入页面时恢复压缩器状态
    LaunchedEffect(Unit) {
        MusicService.instance?.applyCompressor(compEnabled, compThreshold, compRatio, compAttack, compRelease, compMakeup)
    }

    // 【V7.200】Arm MSEB guard in C++ layer — prevents nativeResetDspEq5Band
    // from clearing the 5-band EQ coefficients while MSEB screen is open.
    // Guard is released on dispose (navigate away).
    DisposableEffect(Unit) {
        val oboe = MusicService.instance?.oboeDirectPlayer
        oboe?.setMsebActive(true)
        onDispose {
            oboe?.setMsebActive(false)
        }
    }

    fun update(newParams: MsebParams) {
        params = newParams
        MsebCalculator.save(context, newParams)
        if (enabled) applyMseb(newParams)
    }

    fun toggleEnabled(on: Boolean) {
        enabled = on
        MsebCalculator.setEnabled(context, on)
        val svc = MusicService.instance
        if (on) applyMseb(params)
        else {
            svc?.setDspEqEnabled(false)  // Sync flag
            svc?.resetMsebEq()
            svc?.resetMsStage()
            svc?.resetTransient()
        }
    }

    // ── 【V8.3】动态压缩应用（独立于 MSEB 开关）──
    fun applyCompressor(en: Boolean, thr: Float, ratio: Float, atk: Float, rel: Float, makeup: Float) {
        MusicService.instance?.applyCompressor(en, thr, ratio, atk, rel, makeup)
        compPrefs.edit()
            .putBoolean("enabled", en)
            .putFloat("threshold", thr).putFloat("ratio", ratio)
            .putFloat("attack", atk).putFloat("release", rel).putFloat("makeup", makeup)
            .apply()
    }

    fun toggleCompressor(on: Boolean) {
        compEnabled = on
        applyCompressor(on, compThreshold, compRatio, compAttack, compRelease, compMakeup)
    }

    fun applyPreset(preset: MsebPreset) {
        update(preset.params)
        savedName = preset.name
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MSEB") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.action_back), color = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    if (!params.isFlat) {
                        TextButton(onClick = { savedName = ""; showSaveDialog = true }) {
                            Text(stringResource(R.string.action_save), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Preset Chips (horizontal scroll) ──
            Text(
                stringResource(R.string.mseb_presets),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(presets) { preset ->
                    val isActive = preset.params == params
                    AssistChip(
                        onClick = { applyPreset(preset) },
                        label = {
                            Text(
                                preset.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        colors = if (isActive) {
                            AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else AssistChipDefaults.assistChipColors()
                    )
                }
            }

            // ── A/B Bypass Toggle ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (enabled)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (enabled) stringResource(R.string.mseb_enabled_b) else stringResource(R.string.mseb_bitperfect),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (enabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            MsebCalculator.describe(params),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { toggleEnabled(it) })
                }
            }

            // ── 10 Sliders ──
            val alpha = if (enabled) 1f else 0.45f

            Column(modifier = Modifier.alpha(alpha)) {
                MsebSlider(stringResource(R.string.mseb_slider_temperature), "Cool", "Warm", stringResource(R.string.mseb_slider_temperature_desc), params.temperature) { update(params.copy(temperature = it)) }
                MsebSlider(stringResource(R.string.mseb_slider_thickness), "Thin", "Thick", stringResource(R.string.mseb_slider_thickness_desc), params.thickness) { update(params.copy(thickness = it)) }
                MsebSlider(stringResource(R.string.mseb_slider_vocal), "Distant", "Forward", stringResource(R.string.mseb_slider_vocal_desc), params.vocalForward) { update(params.copy(vocalForward = it)) }
                MsebSlider(stringResource(R.string.mseb_slider_subbass), "Lean", "Deep", stringResource(R.string.mseb_slider_subbass_desc), params.subBass) { update(params.copy(subBass = it)) }
                MsebSlider(stringResource(R.string.mseb_slider_basstexture), "Loose", "Tight", stringResource(R.string.mseb_slider_basstexture_desc), params.bassTexture) { update(params.copy(bassTexture = it)) }
                MsebSlider(stringResource(R.string.mseb_slider_sibilance), "Smooth", "Bright", stringResource(R.string.mseb_slider_sibilance_desc), params.sibilance) { update(params.copy(sibilance = it)) }

                Divider(modifier = Modifier.padding(vertical = 4.dp))

                MsebSlider(stringResource(R.string.mseb_slider_sibilance_lf), "Soft", "Crisp", stringResource(R.string.mseb_slider_sibilance_lf_desc), params.sibilanceLf) { update(params.copy(sibilanceLf = it)) }
                MsebSlider(stringResource(R.string.mseb_slider_sibilance_hf), "Soft", "Crisp", stringResource(R.string.mseb_slider_sibilance_hf_desc), params.sibilanceHf) { update(params.copy(sibilanceHf = it)) }
                MsebSlider(stringResource(R.string.mseb_slider_female_overtones), "Dry", "Sweet", stringResource(R.string.mseb_slider_female_overtones_desc), params.femaleOvertones) { update(params.copy(femaleOvertones = it)) }
                MsebSlider(stringResource(R.string.mseb_slider_air), "Dark", "Airy", stringResource(R.string.mseb_slider_air_desc), params.air) { update(params.copy(air = it)) }

                MsebSlider(stringResource(R.string.mseb_slider_transient), "Soft", "Fast", stringResource(R.string.mseb_slider_transient_desc), params.impulseResponse) { update(params.copy(impulseResponse = it)) }

                Divider(modifier = Modifier.padding(vertical = 4.dp))

                MsebSlider(stringResource(R.string.mseb_slider_soundstage), "Narrow", "Wide", stringResource(R.string.mseb_slider_soundstage_desc), params.soundstage) { update(params.copy(soundstage = it)) }
                MsebSlider(stringResource(R.string.mseb_slider_imaging), "Diffuse", "Focused", stringResource(R.string.mseb_slider_imaging_desc), params.imaging) { update(params.copy(imaging = it)) }
            }

            // ── 动态压缩（独立全局模块）──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (compEnabled)
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.mseb_compressor),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (compEnabled) MaterialTheme.colorScheme.tertiary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(R.string.mseb_compressor_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = compEnabled,
                            onCheckedChange = { on ->
                                compEnabled = on
                                compPrefs.edit().putBoolean("enabled", on).apply()
                                MusicService.instance?.applyCompressor(
                                    on, compThreshold, compRatio, compAttack, compRelease, compMakeup
                                )
                            }
                        )
                    }

                    val compAlpha = if (compEnabled) 1f else 0.45f
                    Column(modifier = Modifier.alpha(compAlpha)) {
                        MsebSlider(
                            stringResource(R.string.mseb_compressor_threshold), "-60 dB", "0 dB",
                            stringResource(R.string.mseb_compressor_threshold), compThreshold,
                            -60f..0f, 1
                        ) { v ->
                            compThreshold = v
                            compPrefs.edit().putFloat("threshold", v).apply()
                            MusicService.instance?.applyCompressor(compEnabled, v, compRatio, compAttack, compRelease, compMakeup)
                        }
                        MsebSlider(
                            stringResource(R.string.mseb_compressor_ratio), "1:1", "10:1",
                            stringResource(R.string.mseb_compressor_ratio), compRatio,
                            1f..10f, 1
                        ) { v ->
                            compRatio = v
                            compPrefs.edit().putFloat("ratio", v).apply()
                            MusicService.instance?.applyCompressor(compEnabled, compThreshold, v, compAttack, compRelease, compMakeup)
                        }
                        MsebSlider(
                            stringResource(R.string.mseb_compressor_attack), "1 ms", "100 ms",
                            stringResource(R.string.mseb_compressor_attack), compAttack,
                            1f..100f, 0
                        ) { v ->
                            compAttack = v
                            compPrefs.edit().putFloat("attack", v).apply()
                            MusicService.instance?.applyCompressor(compEnabled, compThreshold, compRatio, v, compRelease, compMakeup)
                        }
                        MsebSlider(
                            stringResource(R.string.mseb_compressor_release), "20 ms", "500 ms",
                            stringResource(R.string.mseb_compressor_release), compRelease,
                            20f..500f, 0
                        ) { v ->
                            compRelease = v
                            compPrefs.edit().putFloat("release", v).apply()
                            MusicService.instance?.applyCompressor(compEnabled, compThreshold, compRatio, compAttack, v, compMakeup)
                        }
                        MsebSlider(
                            stringResource(R.string.mseb_compressor_makeup), "0 dB", "12 dB",
                            stringResource(R.string.mseb_compressor_makeup), compMakeup,
                            0f..12f, 1
                        ) { v ->
                            compMakeup = v
                            compPrefs.edit().putFloat("makeup", v).apply()
                            MusicService.instance?.applyCompressor(compEnabled, compThreshold, compRatio, compAttack, compRelease, v)
                        }
                    }
                }
            }

            // ── Reset ──
            if (!params.isFlat) {
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { savedName = ""; update(MsebParams()) },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(stringResource(R.string.mseb_reset_flat))
                }
            }

            // ── 10-Band EQ Gains (debug) ──
            Spacer(modifier = Modifier.height(8.dp))
            MsebBandGainsDebug(MsebCalculator.calculateGains(params))

            // ── FFT Spectrum ──
            Spacer(modifier = Modifier.height(8.dp))
            MsebFftBars(bandLevels)
            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    // ── Save Preset Dialog ──
    if (showSaveDialog) {
        var nameText by remember { mutableStateOf(savedName) }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(R.string.mseb_save_preset)) },
            text = {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    label = { Text(stringResource(R.string.mseb_preset_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = nameText.trim()
                    if (name.isNotEmpty()) {
                        MsebPresets.save(context, name, params)
                        savedName = name
                        showSaveDialog = false
                    }
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

// ── FFT Bars ──

// 【V8.3】开发者调试视图：实时显示 10 段实际增益值（MSEB 映射表输出）。
// 展示每个主观滑块如何被映射到 10 个频点，直观看到映射表在跑。
@Composable
private fun MsebBandGainsDebug(gains: FloatArray) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.mseb_band_gains),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            // 增益条：以 0 为中心，上下对称（-6..+6 dB）
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (i in gains.indices) {
                    val g = gains[i].coerceIn(-6f, 6f)
                    // 归一化到 0..1，0.5 为中线
                    val normalized = (g / 6f) * 0.5f + 0.5f
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // 中心基准线
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        )
                        // 增益柱（向上=正增益，向下=负增益）
                        val barHeight = kotlin.math.abs(g) / 6f * 24f
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .height(barHeight.dp)
                                .background(
                                    if (g >= 0) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            // 频点标签 + 增益数值
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                gains.forEachIndexed { i, g ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            freqLabel(MsebCalculator.BAND_FREQS[i]),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Text(
                            String.format("%+.1f", g),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (g >= 0) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

private fun freqLabel(hz: Float): String = when {
    hz >= 1000f -> String.format("%.1fk", hz / 1000f)
    else -> String.format("%.0f", hz)
}

@Composable
private fun MsebFftBars(levels: FloatArray) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.mseb_realtime_spectrum),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            // FFT bars — fixed-height container, bars animate; labels stay still
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                for (i in levels.indices) {
                    val level = levels[i].coerceIn(0f, 1f)
                    val animatedLevel by animateFloatAsState(
                        targetValue = level.coerceIn(0.04f, 1f),
                        animationSpec = tween(120),
                        label = "fft_$i"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(animatedLevel)
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                    )
                                )
                            )
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            // Static frequency labels — hardcoded, zero recomposition
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Text("60 Hz",    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("120 Hz",   style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("250 Hz",   style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("500 Hz",   style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("2 kHz",    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("6 kHz",    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("12 kHz",   style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("20 kHz",   style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

// ── Slider ──

@Composable
private fun MsebSlider(
    label: String,
    leftLabel: String,
    rightLabel: String,
    description: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    MsebSlider(label, leftLabel, rightLabel, description, value, -10f..10f, 1, onValueChange)
}

@Composable
private fun MsebSlider(
    label: String,
    leftLabel: String,
    rightLabel: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    decimals: Int,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                String.format("%+.${decimals}f", value),
                style = MaterialTheme.typography.labelLarge,
                color = if (value == 0f) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
            )
        }
        Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, modifier = Modifier.fillMaxWidth())
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(leftLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(rightLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── DSP ──

private fun applyMseb(params: MsebParams) {
    val svc = MusicService.instance ?: return
    svc.applyMsebEq(
        MsebCalculator.calculateGains(params),
        MsebCalculator.BAND_FREQS,
        MsebCalculator.BAND_QS
    )
    // 【V8.3】M/S 声场（跨声道矩阵）独立应用
    svc.applyMsStage(params.soundstage, params.imaging)
    // 【V8.3】瞬态整形（时域，impulseResponse 维度映射，-1..+1）
    svc.applyTransient(params.impulseResponse / 10f)
}
