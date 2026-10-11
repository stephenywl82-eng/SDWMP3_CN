package com.sdw.music.player.ui.screens

import android.util.Log
import com.sdw.music.player.ui.components.SDWDivider
import com.sdw.music.player.ui.components.SDWTopBar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sdw.music.player.ui.theme.SDWShapes
import com.sdw.music.player.LrcParser
import com.sdw.music.player.R
import com.sdw.music.player.lyric.LyricRepository
import com.sdw.music.player.lyric.LyricResult
import com.sdw.music.player.ui.theme.*
import com.sdw.music.player.ui.components.glassSurface
import com.sdw.music.player.ui.components.springPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricSearchScreen(
    songId: Long,
    songTitle: String,
    songArtist: String,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val decodedTitle = java.net.URLDecoder.decode(songTitle, "UTF-8")
    val decodedArtist = java.net.URLDecoder.decode(songArtist, "UTF-8")

    var searchQuery by remember { mutableStateOf("$decodedTitle $decodedArtist") }
    var results by remember { mutableStateOf<List<LyricResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedResult by remember { mutableStateOf<LyricResult?>(null) }
    var expandedLrc by remember { mutableStateOf<String?>(null) }

    // Auto-search on enter
    LaunchedEffect(songId) {
        if (decodedTitle.isNotBlank()) {
            val repo = LyricRepository.getInstance(context)
            try {
                results = repo.searchFromAllSources(searchQuery)
            } catch (e: Exception) {
                Log.e("LyricSearch", "Search failed", e)
            }
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            SDWTopBar(
                title = {
                    Text(stringResource(R.string.title_search_lyrics), color = MaterialTheme.colorScheme.onBackground)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Title + Artist", color = MaterialTheme.colorScheme.outlineVariant) },
                leadingIcon = { Icon(Icons.Default.Search, stringResource(R.string.action_search), tint = MaterialTheme.colorScheme.outlineVariant) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    isLoading = true
                                    val repo = LyricRepository.getInstance(context)
                                    try {
                                        results = repo.searchFromAllSources(searchQuery)
                                    } catch (_: Exception) { }
                                    isLoading = false
                                }
                            }
                        ) {
                            Text(stringResource(R.string.action_search), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                shape = SDWShapes.Media
            )

            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                selectedResult != null -> {
                    // Show expanded lyrics view
                    LyricDetailView(
                        result = selectedResult!!,
                        onBack = { selectedResult = null }
                    )
                }
                results.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.lyrics_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    // Results list
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        item { Spacer(Modifier.height(4.dp)) }
                        itemsIndexed(results, key = { _, r -> r.songId + r.source }) { _, result ->
                            val sourceColors = result.getSourceColor()
                            val syncBadge = if (result.hasSyncedLyrics()) "⏱" else ""
                            val transBadge = if (result.hasTranslation()) " 🌐" else ""

                            // [V8.115] 结果卡统一到主界面玻璃语言（glassSurface + 弹簧按压）。
                            val resInteraction = remember { MutableInteractionSource() }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 2.dp)
                                    .glassSurface(shape = SDWShapes.Row, fillBoost = 0f)
                                    .springPress(resInteraction, pressedScale = 0.975f)
                                    .clickable(interactionSource = resInteraction, indication = null) { selectedResult = result }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            result.title,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            result.artist,
                                            color = MaterialTheme.colorScheme.outlineVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        // Preview first 2 lines
                                        val preview = result.getBestLyrics()?.lines()?.take(2)?.joinToString("\n") ?: ""
                                        if (preview.isNotBlank()) {
                                            Text(
                                                preview,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    // Source badge
                                    Text(
                                        "${result.getSourceDisplayName()}$syncBadge$transBadge",
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier
                                            .padding(top = 3.dp)
                                            .background(
                                                Color(android.graphics.Color.parseColor(sourceColors)).copy(alpha = 0.15f),
                                                SDWShapes.Badge
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricDetailView(
    result: LyricResult,
    onBack: () -> Unit
) {
    var showSynced by remember { mutableStateOf(result.hasSyncedLyrics()) }
    val lrcText = if (showSynced) result.syncedLrc else result.plainLrc
    val displayText = if (showSynced) {
        // Parse synced LRC and strip timestamps for display
        try {
            LrcParser.parse(lrcText ?: "").joinToString("\n") { it.text }
        } catch (_: Exception) {
            lrcText ?: "No lyrics available"
        }
    } else {
        lrcText ?: "No lyrics available"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("< Back", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(result.title, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodyLarge)
                Text(" · ${result.getSourceDisplayName()}", color = MaterialTheme.colorScheme.outlineVariant, style = MaterialTheme.typography.labelSmall)
            }
            if (result.syncedLrc != null && result.plainLrc != null) {
                TextButton(onClick = { showSynced = !showSynced }) {
                    Text(
                        if (showSynced) stringResource(R.string.lyrics_plain_text) else stringResource(R.string.lyrics_synced),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
        SDWDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f))

        // Lyrics text
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text(
                displayText,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.6f
                )
            )
        }
    }
}
