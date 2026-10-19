package com.sdw.music.player.ui.screens
import com.sdw.music.player.core.audio.dsp.CustomPeqStore
import com.sdw.music.player.core.audio.dsp.EqualizerApoTxt
import com.sdw.music.player.core.audio.dsp.GenrePresets
import com.sdw.music.player.core.audio.dsp.ParametricEqManager
import com.sdw.music.player.core.audio.dsp.AutoEqPresetManager

import android.content.Context
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.sdw.music.player.R
import com.sdw.music.player.core.audio.dsp.ParametricEqManager.Band
import com.sdw.music.player.core.audio.dsp.ParametricEqManager.Preset
import com.sdw.music.player.core.audio.dsp.AutoEqPreset
import com.sdw.music.player.ui.components.ExpressiveSwitch
import com.sdw.music.player.ui.components.SDWDivider
import com.sdw.music.player.ui.components.SDWTopBar
import com.sdw.music.player.ui.components.glassSurface
import com.sdw.music.player.ui.theme.SDWShapes
import kotlin.math.*

private const val TAG = "ParametricEqScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParametricEqScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var preset by remember { mutableStateOf(ParametricEqManager.load(context)) }
    var enabled by remember {
        mutableStateOf(
            context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                .getBoolean("parametric_eq_enabled", ParametricEqManager.enabledFlow.value)
                || ParametricEqManager.load(context).bands.any { it.isActive() }
        )
    }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showPresetsDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    var customPresets by remember { mutableStateOf<List<CustomPeqStore.Entry>>(emptyList()) }
    var showAutoEqDialog by remember { mutableStateOf(false) }
    var showGenreDialog by remember { mutableStateOf(false) }
    var autoEqQuery by remember { mutableStateOf("") }
    var selectedAutoEq by remember { mutableStateOf("") }
    var autoEqBackup by remember { mutableStateOf<Preset?>(null) }
    var genreBackup by remember { mutableStateOf<Preset?>(null) }
    var selectedGenre by remember { mutableStateOf(0) }
    val supported = remember { ParametricEqManager.isSupported(context) }

    fun commit(next: Preset, apply: Boolean = true) {
        preset = next
        ParametricEqManager.save(context, next)
        // 有启用频段即视为「开」：自动下发到播放器并同步开关，避免「调了没效果 / 没保存」
        val hasActive = next.bands.any { it.isActive() }
        if (apply && hasActive) ParametricEqManager.applyPreset(context, next)
        if (hasActive && !enabled) {
            enabled = true
            context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("parametric_eq_enabled", true).apply()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return@rememberLauncherForActivityResult
            val parsed = EqualizerApoTxt.parse(text)
            if (parsed != null) {
                val fileName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    ?.substringBeforeLast(".", "") ?: parsed.name
                enabled = true
                context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("parametric_eq_enabled", true).apply()
                commit(parsed.toPreset().copy(name = fileName))
                Toast.makeText(context, "已导入 $fileName（${parsed.bands.size} 段）", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "未识别到有效的 EqualizerAPO 数据", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "EqualizerAPO import failed", e)
            Toast.makeText(context, "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(EqualizerApoTxt.toText(preset))
            }
            Toast.makeText(context, "已导出 ${preset.bands.size} 段", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "EqualizerAPO export failed", e)
            Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            SDWTopBar(
                title = { Text(stringResource(R.string.eq_peq_title)) },
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
                    ExpressiveSwitch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            enabled = on
                            ParametricEqManager.setEnabled(context, on)
                        },
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            )
        }
    ) { padding ->
        if (!supported) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.eq_peq_unsupported),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            }
            return@Scaffold
        }

        val listState = rememberLazyListState()
        val peqScrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

        Column(Modifier.fillMaxSize().padding(padding)) {
            // ── 固定顶部：频段数 + 实时曲线 + Preamp（曲线常驻；上滑看段时滑块折叠）──
            AnimatedVisibility(visible = !peqScrolled) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.eq_peq_band_count), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f))
                    listOf(10, 15, 30, 64).forEach { bc ->
                        FilterChip(
                            selected = preset.bands.size == bc,
                            onClick = {
                                val cur = preset.bands
                                val next = when {
                                    bc < cur.size -> cur.take(bc)
                                    bc > cur.size -> cur + ParametricEqManager.defaultBands(bc).drop(cur.size)
                                    else -> cur
                                }
                                commit(preset.copy(bands = next))
                            },
                            label = { Text(bc.toString()) },
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            }

            // ── 实时频响曲线（常驻，滚动时始终可见）──
            Text(stringResource(R.string.eq_peq_response), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            PeqResponseCurve(preset = preset, modifier = Modifier
                .fillMaxWidth().height(170.dp).padding(horizontal = 12.dp))

            AnimatedVisibility(visible = !peqScrolled) {
                Column {
                    SDWDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 1f))
                    LabeledSlider(
                        label = stringResource(R.string.eq_peq_preamp),
                        valueText = "%.1f dB".format(preset.preamp),
                        value = preset.preamp,
                        range = -12f..0f,
                        onValueChange = { commit(preset.copy(preamp = it)) }
                    )
                }
            }

            SDWDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 1f))

            // ── 逐段调节（可滚动）──
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                if (preset.bands.size > ParametricEqManager.NATIVE_MAX_BANDS) {
                    item {
                        Text(
                            stringResource(R.string.eq_peq_native_note,
                                preset.bands.size - ParametricEqManager.NATIVE_MAX_BANDS),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }

                itemsIndexed(preset.bands) { idx, band ->
                    BandCard(
                        index = idx,
                        band = band,
                        onChange = { newBand ->
                            val next = preset.bands.toMutableList().also { it[idx] = newBand }
                            commit(preset.copy(bands = next))
                        }
                    )
                }

            // ── 底部操作 ──
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { genreBackup = preset; selectedGenre = 0; showGenreDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.eq_peq_genre)) }
                    OutlinedButton(
                        onClick = {
                            autoEqQuery = ""
                            selectedAutoEq = ""
                            autoEqBackup = preset
                            showAutoEqDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.eq_peq_load_autoeq)) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("text/plain", "*/*")) },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.eq_peq_import)) }
                        OutlinedButton(
                            onClick = { exportLauncher.launch("peq_export.txt") },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.eq_peq_export)) }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                presetName = if (preset.name != "Custom PEQ") preset.name else ""
                                showSaveDialog = true
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.eq_peq_save)) }
                        OutlinedButton(
                            onClick = {
                                customPresets = CustomPeqStore.list(context)
                                showPresetsDialog = true
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.eq_peq_my_presets)) }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val flat = preset.bands.map { it.copy(gain = 0f) }
                                commit(preset.copy(bands = flat))
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.eq_peq_flat)) }
                        FilledTonalButton(
                            onClick = {
                                commit(ParametricEqManager.Preset(bands = ParametricEqManager.defaultBands(preset.bands.size)))
                                enabled = false
                                ParametricEqManager.clear(context)
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.eq_peq_reset)) }
                    }
                }
            }
            }
        }

        if (showSaveDialog) {
            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                title = { Text(stringResource(R.string.eq_peq_save)) },
                text = {
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it },
                        label = { Text(stringResource(R.string.eq_peq_preset_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val name = presetName.trim()
                        if (CustomPeqStore.save(context, name, preset)) {
                            Toast.makeText(context, "已保存「$name」", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "名称不能为空", Toast.LENGTH_SHORT).show()
                        }
                        showSaveDialog = false
                    }) { Text(stringResource(android.R.string.ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveDialog = false }) { Text(stringResource(android.R.string.cancel)) }
                }
            )
        }

        if (showPresetsDialog) {
            AlertDialog(
                onDismissRequest = { showPresetsDialog = false },
                title = { Text(stringResource(R.string.eq_peq_my_presets)) },
                text = {
                    if (customPresets.isEmpty()) {
                        Text(stringResource(R.string.eq_peq_no_presets), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                        ) {
                            customPresets.forEach { entry ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(entry.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    TextButton(onClick = {
                                        enabled = true
                                        commit(entry.toPreset())
                                        showPresetsDialog = false
                                        Toast.makeText(context, "已载入「${entry.name}」", Toast.LENGTH_SHORT).show()
                                    }) { Text(stringResource(R.string.eq_peq_load)) }
                                    TextButton(onClick = {
                                        CustomPeqStore.delete(context, entry.name)
                                        customPresets = CustomPeqStore.list(context)
                                    }) { Text(stringResource(R.string.eq_peq_delete)) }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPresetsDialog = false }) { Text(stringResource(android.R.string.ok)) }
                }
            )
        }
        if (showAutoEqDialog) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val results = remember(autoEqQuery) {
                if (autoEqQuery.isBlank()) {
                    AutoEqPresetManager.presets.take(60)
                } else {
                    AutoEqPresetManager.search(autoEqQuery, 200)
                }
            }
            ModalBottomSheet(
                onDismissRequest = { autoEqBackup?.let { commit(it) }; showAutoEqDialog = false },
                sheetState = sheetState,
                tonalElevation = 0.dp,
                dragHandle = { SDWDivider(Modifier.fillMaxWidth().padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant) }
            ) {
                Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Text(
                        stringResource(R.string.eq_peq_load_autoeq),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    OutlinedTextField(
                        value = autoEqQuery,
                        onValueChange = { autoEqQuery = it },
                        label = { Text(stringResource(R.string.eq_peq_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(results) { p ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    val bands = p.filters.map { f ->
                                        ParametricEqManager.Band(
                                            type = AutoEqPresetManager.filterTypeInt(f.type),
                                            freq = f.freq.coerceIn(20f, 20000f),
                                            gain = f.gain.coerceIn(-15f, 15f),
                                            q = f.q.coerceIn(0.1f, 18f),
                                            enabled = true
                                        )
                                    }
                                    enabled = true
                                    selectedAutoEq = p.name
                                    commit(ParametricEqManager.Preset(name = p.name, preamp = p.preamp, bands = bands))
                                }.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        p.name, style = MaterialTheme.typography.bodyMedium,
                                        color = if (selectedAutoEq == p.name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(p.brand, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (selectedAutoEq == p.name) {
                                    Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(stringResource(R.string.eq_peq_bands_count, p.filters.size), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { autoEqBackup?.let { commit(it) }; showAutoEqDialog = false },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.action_cancel)) }
                        Button(
                            onClick = { showAutoEqDialog = false },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(android.R.string.ok)) }
                    }
                }
            }
        }

        if (showGenreDialog) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showGenreDialog = false },
                sheetState = sheetState,
                tonalElevation = 0.dp,
                dragHandle = { SDWDivider(Modifier.fillMaxWidth().padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant) }
            ) {
                Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Text(
                        stringResource(R.string.eq_peq_genre),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    val genreTitle = stringResource(R.string.eq_peq_genre)
                    val brandTitle = stringResource(R.string.eq_brand_presets)
                    val toneTitle = stringResource(R.string.eq_peq_tone_presets)
                    val groups = listOf(
                        Triple(genreTitle, GenrePresets.presets, 0),
                        Triple(brandTitle, GenrePresets.brandPresets, 1),
                        Triple(toneTitle, GenrePresets.tonePresets, 2)
                    )
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        for (group in groups) {
                            val title = group.first
                            val list = group.second
                            item {
                                Text(
                                    title, style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            items(list) { g ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        enabled = true
                                        selectedGenre = g.nameRes
                                        commit(GenrePresets.toPreset(g, context))
                                    }.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            stringResource(g.nameRes), style = MaterialTheme.typography.bodyMedium,
                                            color = if (selectedGenre == g.nameRes) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(stringResource(g.descRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (selectedGenre == g.nameRes) {
                                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(stringResource(R.string.eq_peq_bands_count, g.bands.size), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { genreBackup?.let { commit(it) }; showGenreDialog = false },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.action_cancel)) }
                        Button(
                            onClick = { showGenreDialog = false },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(android.R.string.ok)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BandCard(index: Int, band: Band, onChange: (Band) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .glassSurface(shape = SDWShapes.Row, selected = band.isActive(), fillBoost = 0f)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExpressiveSwitch(checked = band.enabled, onCheckedChange = { onChange(band.copy(enabled = it)) })
            Text("Band ${index + 1}", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 4.dp).weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    ParametricEqManager.TYPE_PEAKING to stringResource(R.string.eq_peq_type_peaking),
                    ParametricEqManager.TYPE_LOW_SHELF to stringResource(R.string.eq_peq_type_lowshelf),
                    ParametricEqManager.TYPE_HIGH_SHELF to stringResource(R.string.eq_peq_type_highshelf)
                ).forEach { (t, label) ->
                    FilterChip(
                        selected = band.type == t,
                        onClick = { onChange(band.copy(type = t)) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
        // 频率（对数滑块）
        val logMin = ln(20.0); val logMax = ln(20000.0)
        val fpos = ((ln(band.freq.toDouble()) - logMin) / (logMax - logMin)).toFloat()
        LabeledSlider(
            label = stringResource(R.string.eq_peq_frequency),
            valueText = formatHz(band.freq),
            value = fpos,
            range = 0f..1f,
            onValueChange = { v ->
                val f = exp(logMin + v * (logMax - logMin)).toFloat().coerceIn(20f, 20000f)
                onChange(band.copy(freq = f))
            }
        )
        LabeledSlider(
            label = stringResource(R.string.eq_peq_gain),
            valueText = "%+.1f dB".format(band.gain),
            value = band.gain,
            range = -15f..15f,
            onValueChange = { onChange(band.copy(gain = it)) }
        )
        LabeledSlider(
            label = stringResource(R.string.eq_peq_q),
            valueText = "%.2f".format(band.q),
            value = band.q,
            range = 0.1f..18f,
            onValueChange = { onChange(band.copy(q = it)) }
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(76.dp))
        Slider(value = value, onValueChange = onValueChange, valueRange = range,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        Text(valueText, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(72.dp), textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PeqResponseCurve(preset: Preset, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val gridLine = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val gridZero = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    Canvas(modifier = modifier.background(surface, RoundedCornerShape(12.dp))) {
        val w = size.width; val h = size.height
        val fMin = 20.0; val fMax = 20000.0
        val gMin = -18.0; val gMax = 18.0
        val logMin = ln(fMin); val logMax = ln(fMax)
        // 左侧留白给 dB 刻度，底部留白给频率刻度
        val labelW = 22.dp.toPx()
        val labelH = 14.dp.toPx()
        val plotW = w - labelW
        val plotH = h - labelH
        fun fx(f: Double): Float = labelW + ((ln(f) - logMin) / (logMax - logMin)).toFloat() * plotW
        fun gy(g: Double): Float = (1.0 - (g - gMin) / (gMax - gMin)).toFloat() * plotH

        val labelPaint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = 9.dp.toPx()
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.LEFT
        }

        // 横向 dB 参考线 + 左侧 dB 标签
        listOf(-12.0, -6.0, 0.0, 6.0, 12.0).forEach { g ->
            val y = gy(g)
            drawLine(
                if (g == 0.0) gridZero else gridLine,
                Offset(labelW, y), Offset(w, y),
                strokeWidth = 1.dp.toPx()
            )
            val txt = when (g) {
                0.0 -> "0"
                12.0 -> "+12"
                6.0 -> "+6"
                -6.0 -> "-6"
                else -> "-12"
            }
            drawContext.canvas.nativeCanvas.drawText(txt, 2.dp.toPx(), y + 3.dp.toPx(), labelPaint)
        }

        // 纵向参数网格（标准 EQ 频率档位）+ 底部频率标签
        val freqGrid = listOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)
        freqGrid.forEachIndexed { idx, f ->
            val x = fx(f)
            drawLine(gridLine, Offset(x, 0f), Offset(x, plotH), strokeWidth = 1.dp.toPx())
            val txt = when (f) {
                1000.0 -> "1k"; 2000.0 -> "2k"; 5000.0 -> "5k"; 10000.0 -> "10k"; 20000.0 -> "20k"
                else -> f.toInt().toString()
            }
            labelPaint.textAlign = when (idx) {
                0 -> android.graphics.Paint.Align.LEFT
                freqGrid.lastIndex -> android.graphics.Paint.Align.RIGHT
                else -> android.graphics.Paint.Align.CENTER
            }
            val tx = when (idx) {
                0 -> labelW + 2.dp.toPx()
                freqGrid.lastIndex -> w - 2.dp.toPx()
                else -> x
            }
            drawContext.canvas.nativeCanvas.drawText(txt, tx, h - 2.dp.toPx(), labelPaint)
        }
        // 频响曲线（RBJ 级联幅频，preamp 计入）
        val pts = (0..140).map { i ->
            val f = exp(logMin + i / 140.0 * (logMax - logMin))
            val db = responseDb(preset, f.toFloat(), 48000f)
            Offset(fx(f), gy(db.coerceIn(gMin, gMax)))
        }
        val path = Path().apply { moveTo(pts.first().x, pts.first().y); pts.forEach { lineTo(it.x, it.y) } }
        drawPath(path, accent, style = Stroke(width = 2.dp.toPx()))
    }
}

// ── RBJ biquad 幅频（dB），用于曲线预览 ──
private fun biquadDb(type: Int, fc: Float, gainDb: Float, q: Float, f: Float, fs: Float): Double {
    if (gainDb == 0f) return 0.0
    val A = 10.0.pow(gainDb / 40.0)
    val w0 = 2.0 * PI * fc / fs
    val cw = cos(w0); val sw = sin(w0)
    val alpha = sw / 2.0 * sqrt(2.0)   // RBJ shelf, 斜率 S=1
    val c = when (type) {
        ParametricEqManager.TYPE_LOW_SHELF -> {
            val sa = sqrt(A) * alpha
            doubleArrayOf(
                A * ((A + 1.0) - (A - 1.0) * cw + 2.0 * sa),
                2.0 * A * ((A - 1.0) - (A + 1.0) * cw),
                A * ((A + 1.0) - (A - 1.0) * cw - 2.0 * sa),
                (A + 1.0) + (A - 1.0) * cw + 2.0 * sa,
                -2.0 * ((A - 1.0) + (A + 1.0) * cw),
                (A + 1.0) + (A - 1.0) * cw - 2.0 * sa
            )
        }
        ParametricEqManager.TYPE_HIGH_SHELF -> {
            val sa = sqrt(A) * alpha
            doubleArrayOf(
                A * ((A + 1.0) + (A - 1.0) * cw + 2.0 * sa),
                -2.0 * A * ((A - 1.0) + (A + 1.0) * cw),
                A * ((A + 1.0) + (A - 1.0) * cw - 2.0 * sa),
                (A + 1.0) - (A - 1.0) * cw + 2.0 * sa,
                2.0 * ((A - 1.0) - (A + 1.0) * cw),
                (A + 1.0) - (A - 1.0) * cw - 2.0 * sa
            )
        }
        else -> { // Peaking
            val alphaP = sw / (2.0 * q)
            doubleArrayOf(
                1.0 + alphaP * A, -2.0 * cw, 1.0 - alphaP * A,
                1.0 + alphaP / A, -2.0 * cw, 1.0 - alphaP / A
            )
        }
    }
    val b0 = c[0]; val b1 = c[1]; val b2 = c[2]; val a0 = c[3]; val a1 = c[4]; val a2 = c[5]
    val w = 2.0 * PI * f / fs
    val cwv = cos(w); val c2w = cos(2.0 * w)
    val num = b0 * b0 + b1 * b1 + b2 * b2 + 2.0 * (b0 * b1 + b1 * b2) * cwv + 2.0 * b0 * b2 * c2w
    val den = a0 * a0 + a1 * a1 + a2 * a2 + 2.0 * (a0 * a1 + a1 * a2) * cwv + 2.0 * a0 * a2 * c2w
    return 10.0 * log10(num / den)
}

private fun responseDb(preset: Preset, f: Float, fs: Float): Double {
    var db = preset.preamp.toDouble()
    for (b in preset.bands) if (b.enabled) db += biquadDb(b.type, b.freq, b.gain, b.q, f, fs)
    return db
}

private fun formatHz(hz: Float): String =
    if (hz >= 1000f) "%.2f kHz".format(hz / 1000f) else "%.0f Hz".format(hz)
