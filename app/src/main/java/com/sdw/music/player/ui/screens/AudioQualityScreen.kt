package com.sdw.music.player.ui.screens

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.sdw.audio.analyzer.*
import com.sdw.music.player.Song
import com.sdw.music.player.R
import com.sdw.music.player.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class QualityUiState(
    val phase: String = "idle",
    val statusText: String = "",
    val reports: List<AudioQualityReport> = emptyList(),
    val cachedCount: Int = 0,
    val filterLabel: String? = null,
    val sortMode: SortMode = SortMode.ByScore
)

enum class SortMode { ByScore, ByScoreAsc, ByCutoff, ByDynamicRange }

private data class CachedReport(
    val filePath: String, val fileName: String, val fileSize: Long,
    val durationSeconds: Float, val sampleRate: Int, val channelCount: Int,
    val bitDepth: Int, val codecName: String,
    val frequencyCutoff: Float, val dynamicRangeDb: Float,
    val peakDb: Float, val rmsDb: Float, val noiseFloorDb: Float,
    val clippedFrameRatio: Float, val spectralFlatness: Float,
    val dcOffset: Float, val qualityScore: Float, val qualityLabel: String,
    val suspectedSourceVariant: List<String>
) {
    fun toReport() = AudioQualityReport(
        filePath = filePath, fileName = fileName, fileSize = fileSize,
        durationSeconds = durationSeconds, sampleRate = sampleRate, channelCount = channelCount,
        bitDepth = bitDepth, codecName = codecName,
        frequencyCutoff = frequencyCutoff, dynamicRangeDb = dynamicRangeDb,
        peakDb = peakDb, rmsDb = rmsDb, noiseFloorDb = noiseFloorDb,
        clippedFrameRatio = clippedFrameRatio, spectralFlatness = spectralFlatness,
        dcOffset = dcOffset, qualityScore = qualityScore, qualityLabel = qualityLabel,
        suspectedSourceVariant = suspectedSourceVariant
    )
}

private fun AudioQualityReport.toCached() = CachedReport(
    filePath = filePath, fileName = fileName, fileSize = fileSize,
    durationSeconds = durationSeconds, sampleRate = sampleRate, channelCount = channelCount,
    bitDepth = bitDepth, codecName = codecName,
    frequencyCutoff = frequencyCutoff, dynamicRangeDb = dynamicRangeDb,
    peakDb = peakDb, rmsDb = rmsDb, noiseFloorDb = noiseFloorDb,
    clippedFrameRatio = clippedFrameRatio, spectralFlatness = spectralFlatness,
    dcOffset = dcOffset, qualityScore = qualityScore, qualityLabel = qualityLabel,
    suspectedSourceVariant = suspectedSourceVariant
)

private fun loadCache(context: Context): List<AudioQualityReport> = try {
    val f = java.io.File(context.filesDir, "quality_cache.json")
    if (!f.exists()) emptyList()
    else {
        val arr = JSONArray(f.readText())
        val result = mutableListOf<AudioQualityReport>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            result.add(CachedReport(
                filePath = o.getString("filePath"),
                fileName = o.getString("fileName"),
                fileSize = o.getLong("fileSize"),
                durationSeconds = o.optDouble("durationSeconds", 0.0).toFloat(),
                sampleRate = o.getInt("sampleRate"),
                channelCount = o.optInt("channelCount", 2),
                bitDepth = o.optInt("bitDepth", 16),
                codecName = o.optString("codecName", "Unknown"),
                qualityScore = o.optDouble("qualityScore", 0.0).toFloat(),
                qualityLabel = o.optString("qualityLabel", ""),
                frequencyCutoff = o.optDouble("frequencyCutoff", 0.0).toFloat(),
                dynamicRangeDb = o.optDouble("dynamicRangeDb", 0.0).toFloat(),
                peakDb = o.optDouble("peakDb", 0.0).toFloat(),
                rmsDb = o.optDouble("rmsDb", 0.0).toFloat(),
                noiseFloorDb = o.optDouble("noiseFloorDb", 0.0).toFloat(),
                clippedFrameRatio = o.optDouble("clippedFrameRatio", 0.0).toFloat(),
                spectralFlatness = o.optDouble("spectralFlatness", 0.0).toFloat(),
                dcOffset = o.optDouble("dcOffset", 0.0).toFloat(),
                suspectedSourceVariant = (0 until (o.optJSONArray("suspectedSourceVariant")?.length() ?: 0))
                    .map { j -> o.getJSONArray("suspectedSourceVariant").getString(j) }
            ).toReport())
        }
        result
    }
} catch (_: Exception) { emptyList() }

private fun saveCache(context: Context, reports: List<AudioQualityReport>) = try {
    val arr = JSONArray()
    for (r in reports) {
        val o = org.json.JSONObject()
        o.put("filePath", r.filePath)
        o.put("fileName", r.fileName)
        o.put("fileSize", r.fileSize)
        o.put("durationSeconds", r.durationSeconds.toDouble())
        o.put("sampleRate", r.sampleRate)
        o.put("channelCount", r.channelCount)
        o.put("bitDepth", r.bitDepth)
        o.put("codecName", r.codecName)
        o.put("qualityScore", r.qualityScore.toDouble())
        o.put("qualityLabel", r.qualityLabel)
        o.put("frequencyCutoff", r.frequencyCutoff.toDouble())
        o.put("dynamicRangeDb", r.dynamicRangeDb.toDouble())
        o.put("peakDb", r.peakDb.toDouble())
        o.put("rmsDb", r.rmsDb.toDouble())
        o.put("noiseFloorDb", r.noiseFloorDb.toDouble())
        o.put("clippedFrameRatio", r.clippedFrameRatio.toDouble())
        o.put("spectralFlatness", r.spectralFlatness.toDouble())
        o.put("dcOffset", r.dcOffset.toDouble())
        val ta = JSONArray()
        for (t in r.suspectedSourceVariant) ta.put(t)
        o.put("suspectedSourceVariant", ta)
        arr.put(o)
    }
    val tmp = java.io.File(context.filesDir, "quality_cache.tmp")
    tmp.writeText(arr.toString())
    tmp.renameTo(java.io.File(context.filesDir, "quality_cache.json"))
} catch (_: Exception) {}

private fun findSong(songs: List<Song>, report: AudioQualityReport): Song? {
    return songs.firstOrNull { it.filePath == report.filePath || it.path == report.filePath }
        ?: songs.firstOrNull { it.title == report.fileName.substringBeforeLast(".") }
        ?: songs.firstOrNull { (it.filePath ?: "").contains(report.fileName, ignoreCase = true) }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AudioQualityScreen(
    onNavigateBack: () -> Unit,
    songs: List<Song> = emptyList(),
    onPlaySong: (Song) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf(QualityUiState()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val cached = loadCache(context)
            if (cached.isNotEmpty()) {
                uiState = uiState.copy(
                    phase = "done",
                    statusText = context.getString(R.string.quality_loaded_cache, cached.size),
                    reports = cached,
                    cachedCount = cached.size
                )
            }
        }
    }

    suspend fun doScan() = withContext(Dispatchers.IO) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "AQ:scan"
        )
        wl.acquire(30 * 60 * 1000L)
        try {
            uiState = uiState.copy(phase = "scanning", statusText = context.getString(R.string.quality_scanning))
            val files = AudioScanner(context.contentResolver).scan()
            if (files.isEmpty()) {
                uiState = uiState.copy(phase = "done", statusText = context.getString(R.string.quality_no_audio))
                return@withContext
            }
            uiState = uiState.copy(statusText = context.getString(R.string.quality_scan_status, files.size))
            val cacheMap = uiState.reports.associateBy { it.filePath }
            val pending = files.filter { f ->
                val cr = cacheMap[f.filePath]
                cr == null || cr.fileSize != f.fileSize
            }
            if (pending.isEmpty()) {
                uiState = uiState.copy(phase = "done", statusText = context.getString(R.string.quality_all_cached, files.size))
                return@withContext
            }
            val merged = uiState.reports.map { it.toCached() }.toMutableList()
            val decoder = AudioDecoder()
            val analyzer = QualityAnalyzer()
            for ((idx, file) in pending.withIndex()) {
                val pcm = try { decoder.decode(file.filePath, 10_000) } catch (_: Exception) { null }
                if (pcm == null) {
                    uiState = uiState.copy(statusText = context.getString(R.string.quality_decode_failed, file.fileName, idx + 1, pending.size))
                    continue
                }
                val report = try { analyzer.analyze(pcm, file) } catch (_: Exception) { null }
                if (report != null) {
                    merged.removeAll { it.filePath == report.filePath }
                    merged.add(report.toCached())
                    if ((idx + 1) % 5 == 0 || idx == pending.lastIndex) {
                        val tmp = merged.map { it.toReport() }
                        uiState = uiState.copy(
                            statusText = context.getString(R.string.quality_songs_progress, idx + 1, pending.size, merged.size),
                            reports = tmp.sortedByDescending { it.qualityScore },
                            cachedCount = merged.size
                        )
                        saveCache(context, tmp)
                    }
                }
            }
            val final = merged.map { it.toReport() }
            saveCache(context, final)
            uiState = uiState.copy(
                phase = "done",
                statusText = context.getString(R.string.quality_done_songs, final.size),
                reports = final.sortedByDescending { it.qualityScore },
                cachedCount = final.size
            )
        } catch (e: Exception) {
            uiState = uiState.copy(phase = "done", statusText = "Error: ${e.message}")
        } finally {
            wl.release()
        }
    }

    val reports = uiState.reports
    val filtered = remember(reports, uiState.filterLabel) {
        if (uiState.filterLabel == null) reports
        else reports.filter { it.qualityLabel == uiState.filterLabel }
    }
    val sorted = remember(filtered, uiState.sortMode) {
        when (uiState.sortMode) {
            SortMode.ByScore -> filtered.sortedByDescending { it.qualityScore }
            SortMode.ByScoreAsc -> filtered.sortedBy { it.qualityScore }
            SortMode.ByCutoff -> filtered.sortedByDescending { it.frequencyCutoff }
            SortMode.ByDynamicRange -> filtered.sortedByDescending { it.dynamicRangeDb }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_audio_quality), color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    if (uiState.phase == "scanning") {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).padding(end = 12.dp),
                            strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    uiState.statusText.ifEmpty {
                        if (uiState.reports.isEmpty()) stringResource(R.string.quality_tap_scan)
                        else context.getString(R.string.quality_songs_count, uiState.reports.size)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    onClick = { scope.launch { if (uiState.phase != "scanning") doScan() } },
                    enabled = uiState.phase != "scanning",
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (uiState.reports.isEmpty()) stringResource(R.string.action_scan) else stringResource(R.string.action_rescan), fontSize = 13.sp)
                }
            }

            if (uiState.reports.isNotEmpty()) {
                val exc = uiState.reports.count { it.qualityLabel == "Excellent" }
                val good = uiState.reports.count { it.qualityLabel == "Good" }
                val fair = uiState.reports.count { it.qualityLabel == "Fair" }
                val poor = uiState.reports.count { it.qualityLabel == "Poor" }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = uiState.filterLabel == "Excellent",
                        onClick = { uiState = uiState.copy(filterLabel = if (uiState.filterLabel == "Excellent") null else "Excellent") },
                        label = { Text(stringResource(R.string.quality_excellent, exc), fontSize = 12.sp) },
                        modifier = Modifier.height(28.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                    )
                    FilterChip(
                        selected = uiState.filterLabel == "Good",
                        onClick = { uiState = uiState.copy(filterLabel = if (uiState.filterLabel == "Good") null else "Good") },
                        label = { Text(stringResource(R.string.quality_good, good), fontSize = 12.sp) },
                        modifier = Modifier.height(28.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                    )
                    FilterChip(
                        selected = uiState.filterLabel == "Fair",
                        onClick = { uiState = uiState.copy(filterLabel = if (uiState.filterLabel == "Fair") null else "Fair") },
                        label = { Text(stringResource(R.string.quality_fair, fair), fontSize = 12.sp) },
                        modifier = Modifier.height(28.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f))
                    )
                    FilterChip(
                        selected = uiState.filterLabel == "Poor",
                        onClick = { uiState = uiState.copy(filterLabel = if (uiState.filterLabel == "Poor") null else "Poor") },
                        label = { Text(stringResource(R.string.quality_poor, poor), fontSize = 12.sp) },
                        modifier = Modifier.height(28.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentRed.copy(alpha = 0.2f))
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = uiState.sortMode == SortMode.ByScore,
                        onClick = { uiState = uiState.copy(sortMode = SortMode.ByScore) },
                        label = { Text(stringResource(R.string.quality_sort_score_desc), fontSize = 12.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                    FilterChip(
                        selected = uiState.sortMode == SortMode.ByScoreAsc,
                        onClick = { uiState = uiState.copy(sortMode = SortMode.ByScoreAsc) },
                        label = { Text(stringResource(R.string.quality_sort_score_asc), fontSize = 12.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                    FilterChip(
                        selected = uiState.sortMode == SortMode.ByCutoff,
                        onClick = { uiState = uiState.copy(sortMode = SortMode.ByCutoff) },
                        label = { Text(stringResource(R.string.quality_cutoff), fontSize = 12.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                    FilterChip(
                        selected = uiState.sortMode == SortMode.ByDynamicRange,
                        onClick = { uiState = uiState.copy(sortMode = SortMode.ByDynamicRange) },
                        label = { Text(stringResource(R.string.quality_dynamic), fontSize = 12.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                }
                HorizontalDivider(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }

            if (sorted.isEmpty() && uiState.phase == "done") {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (uiState.reports.isEmpty()) stringResource(R.string.quality_tap_scan) else stringResource(R.string.quality_no_matching),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(sorted, key = { _, r -> r.filePath }) { _, report ->
                        ReportCard(report = report, songs = songs, onPlaySong = onPlaySong)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportCard(
    report: AudioQualityReport,
    songs: List<Song>,
    onPlaySong: (Song) -> Unit
) {
    val context = LocalContext.current
    val scoreColor = when {
        report.qualityScore >= 82f -> Color(0xFF4CAF50)
        report.qualityScore >= 65f -> MaterialTheme.colorScheme.primary
        report.qualityScore >= 40f -> MaterialTheme.colorScheme.secondary
        else -> AccentRed
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(scoreColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${report.qualityScore.toInt()}",
                        color = scoreColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        report.fileName,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${report.qualityLabel} · ${report.sampleRate / 1000}kHz · ${report.bitDepth}bit · ${report.codecName}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }

                val matched = remember(report, songs) { findSong(songs, report) }
                if (matched != null) {
                    IconButton(
                        onClick = { onPlaySong(matched) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, stringResource(R.string.action_play), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Metric chips — same as standalone app
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricChip(stringResource(R.string.quality_cutoff), "${"%.0f".format(report.frequencyCutoff)}Hz")
                MetricChip("DR", "${"%.1f".format(report.dynamicRangeDb)}dB")
                MetricChip("Clip", "${"%.2f".format(report.clippedFrameRatio * 100)}%")
                MetricChip("Flat", "${"%.2f".format(report.spectralFlatness)}")
            }

            if (report.suspectedSourceVariant.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (tag in report.suspectedSourceVariant) {
                        val c = when {
                            tag.contains("fake", ignoreCase = true) || tag.contains("lossy", ignoreCase = true) -> AccentRed
                            tag.contains("Lossless", ignoreCase = true) || tag.contains("Hi-Res", ignoreCase = true) -> MaterialTheme.colorScheme.primary
                            tag.contains("Full", ignoreCase = true) || tag.contains("High Cutoff", ignoreCase = true) -> Color(0xFF4CAF50)
                            tag.contains("Compressed", ignoreCase = true) || tag.contains("Clip", ignoreCase = true) || tag.contains("MP3", ignoreCase = true) -> MaterialTheme.colorScheme.secondary
                            tag.contains("Spectrally Flat", ignoreCase = true) -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.primary
                        }
                        Surface(color = c.copy(alpha = 0.12f), shape = RoundedCornerShape(4.dp)) {
                            Text(tag, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = c)
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = {
                        val text = buildString {
                            appendLine(report.fileName)
                            appendLine("Score: ${report.qualityScore.toInt()} / ${report.qualityLabel}")
                            appendLine(
                                "Cutoff: ${"%.0f".format(report.frequencyCutoff)}Hz | " +
                                "DR: ${"%.1f".format(report.dynamicRangeDb)}dB | " +
                                "Clip: ${"%.2f".format(report.clippedFrameRatio * 100)}%"
                            )
                            appendLine("${report.sampleRate / 1000}kHz / ${report.bitDepth}bit · ${report.codecName}")
                            if (report.suspectedSourceVariant.isNotEmpty()) {
                                appendLine("Tags: ${report.suspectedSourceVariant.joinToString(" · ")}")
                            }
                        }
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)))
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Share, null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun MetricChip(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.width(4.dp))
        Text(value, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
    }
}
