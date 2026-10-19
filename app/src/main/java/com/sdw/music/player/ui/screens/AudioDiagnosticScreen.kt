package com.sdw.music.player.ui.screens
import com.sdw.music.player.core.audio.dsp.LoudnessNormalizer
import com.sdw.music.player.core.audio.analysis.MediaProbe
import com.sdw.music.player.core.audio.playback.MusicService
import com.sdw.music.player.core.audio.engine.OboeDirectPlayer
import com.sdw.music.player.core.audio.engine.UsbDacManager

import android.media.AudioDeviceInfo
import com.sdw.music.player.ui.components.SDWTopBar
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaExtractor
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sdw.music.player.ui.theme.SDWShapes
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.sdw.music.player.R
import com.sdw.music.player.ui.theme.*
import com.sdw.music.player.ui.components.glassSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioDiagnosticScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val service = remember { MusicService.instance }
    val oboePlayer = remember { service?.getOboePlayer() }

    // Device native: AudioManager
    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager }
    val deviceNativeRate = remember {
        audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
    }
    val framesPerBuffer = remember {
        audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
    }

    // Output device info (snapshot, updated live below)
    val outputDeviceInfoSnapshot = remember {
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull()
    }
    val outputDeviceLabelSnapshot = remember(outputDeviceInfoSnapshot) {
        getDeviceLabel(outputDeviceInfoSnapshot)
    }

    // Source rate: Oboe opens at source → getSampleRate(), or extract from file
    // V3.3.4: live-updated in the polling loop below (song can change while screen is open)
    var sourceRate by remember { mutableStateOf(0) }
    var sourcePathTracked by remember { mutableStateOf<String?>(null) }

    val streamRate = remember { oboePlayer?.getStreamSampleRate() ?: 0 }
    val isOboeMode = remember { service?.isOboeDirectMode() == true }

    // V3.3.4: USB DAC exclusive mode live state
    var isUsbDacMode by remember { mutableStateOf(false) }
    var dacStreamRate by remember { mutableStateOf(0) }
    var dacStreamBits by remember { mutableStateOf(0) }
    var dacName by remember { mutableStateOf("") }
    var dacStats by remember { mutableStateOf("") }
    var dacSupportedRates by remember { mutableStateOf("") }

    // 【V8.209】Signal Path 与输出电平的遥测全部下沉到叶子组件（SignalPathCard /
    // FreqBars / LevelMonitorCard 等）。原来这些值挂在页面级 State 上，一次刷新就把整页
    // LazyColumn 重组一遍 —— 12.5Hz 的频响条看着「卡卡的」就是这么来的。现在：
    //   · 波形/条形：逐帧从 native 读（进 TwCanvas 内部数组，不进 State），0 重组、60fps；
    //   · 数字读数：单独 10Hz 更新，只有那一行 Text 重组；
    //   · DSP 点阵 / Clip：250ms，变化本来就慢。

    // ── Live SharingMode + Output Device polling ──
    // Initial null → LaunchedEffect fills immediately on first poll
    var isExclusiveLive by remember { mutableStateOf<Boolean?>(null) }
    // [V8.132] Physical route: is the active output Bluetooth (A2DP/SCO)?
    // The sharing-mode label below only knows about the AAudio stream; BT also runs
    // through the Oboe direct path and AAudio happily grants MMAP Exclusive there, so
    // the page used to claim "Oboe Exclusive" while the audio was BT-re-encoded.
    // BT can never be bit-perfect, so the route gates every "exclusive" label.
    var btActive by remember { mutableStateOf(false) }
    var liveOutputDeviceInfo by remember { mutableStateOf<AudioDeviceInfo?>(null) }
    var liveOutputDeviceLabel by remember { mutableStateOf("") }
    var trackFlagsHex by remember { mutableStateOf("—") }
    var trackFlagsLabel by remember { mutableStateOf("") }

    // ── AudioFlinger Track Flags (dumpsys parse) ──

    // ── Audio Focus event log ──
    val focusLog = remember { mutableStateListOf<FocusEvent>() }

    // ── AudioFocus listener: piggyback on MusicService's existing focus handling ──
    // We do NOT request focus ourselves (would fight with the player).
    // Instead, monitor via AudioManager.isMusicActive() and log changes.
    // Poll using fresh references each iteration — NOT stale remember captures.
    LaunchedEffect(Unit) {
        var wasMusicActive = audioManager?.isMusicActive ?: false
        var tick = 0
        while (true) {
            // Fresh reference every poll — avoids stale oboePlayer after re-entry
            val liveOboe = MusicService.instance?.getOboePlayer()
            isExclusiveLive = liveOboe?.isExclusiveMode()

            // V3.3.4: source rate follows current song (always update in DAC mode)
            val curPath = MusicService.currentSong?.path
            // DAC mode: read from controller (true source rate from STREAMINFO/RIFF)
            if (isUsbDacMode) {
                val controller = MusicService.instance?.getUsbDacController()
                val dacSrc = controller?.sourceSampleRate ?: 0
                android.util.Log.d("AudioDiagnostic", "isUsbDacMode=$isUsbDacMode controller=$controller dacSrc=$dacSrc sourceRate=$sourceRate")
                if (dacSrc > 0 && dacSrc != sourceRate) {
                    sourceRate = dacSrc
                    sourcePathTracked = curPath
                    android.util.Log.d("AudioDiagnostic", "Updated sourceRate from DAC: $dacSrc Hz")
                }
            } else if (curPath != sourcePathTracked) {
                sourcePathTracked = curPath
                val oboeSrc = liveOboe?.getSampleRate() ?: 0
                sourceRate = if (oboeSrc > 0) oboeSrc
                else if (!curPath.isNullOrEmpty()) withContext(Dispatchers.IO) { extractSampleRateFromFile(curPath) }
                else 0
            }
            val outDevs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val dev = outDevs?.firstOrNull()
            liveOutputDeviceInfo = dev
            liveOutputDeviceLabel = getDeviceLabel(dev)
            // [V8.132] scan the full output list (firstOrNull can be the builtin speaker
            // while BT is the real route); any A2DP/SCO sink means audio goes over BT.
            btActive = outDevs?.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } == true

            // V3.3.4: USB DAC exclusive live params
            isUsbDacMode = try { com.sdw.music.player.core.audio.engine.UsbDacManager.isClaimed() } catch (_: Throwable) { false }
            if (isUsbDacMode) {
                dacStreamRate = try { com.sdw.music.player.core.audio.engine.UsbDacManager.queryActiveSampleRate() } catch (_: Throwable) { 0 }
                dacStreamBits = try { com.sdw.music.player.core.audio.engine.UsbDacManager.queryActiveBits() } catch (_: Throwable) { 0 }
                dacName = try { com.sdw.music.player.core.audio.engine.UsbDacManager.getDacDisplayName() } catch (_: Throwable) { "USB DAC" }
                dacStats = try { com.sdw.music.player.core.audio.engine.UsbDacManager.getStats() ?: "" } catch (_: Throwable) { "" }
                dacSupportedRates = try { com.sdw.music.player.core.audio.engine.UsbDacManager.getSupportedRates() } catch (_: Throwable) { "" }
            }

            // Track audio focus changes without requesting focus
            val nowActive = audioManager?.isMusicActive ?: false
            if (nowActive != wasMusicActive) {
                wasMusicActive = nowActive
                val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val event = if (nowActive) "GAIN" else "LOSS"
                focusLog.add(0, FocusEvent(now, event))
                if (focusLog.size > 20) focusLog.removeAt(focusLog.size - 1)
            }

            // dumpsys is expensive — only poll every 10s
            tick++
            if (tick % 10 == 0) {
                if (isUsbDacMode) {
                    // V3.3.4: USB direct write never touches AudioFlinger
                    trackFlagsHex = "N/A"
                    trackFlagsLabel = context.getString(R.string.diag_usb_direct_write)
                } else {
                    // 【V8.209】dumpsys 是几十~几百毫秒的阻塞 IO，原来直接在 LaunchedEffect
                    // （= 主线程）里跑，每 10s 卡一帧 —— 「界面卡卡的」有一半是它。挪到 IO 线程。
                    val flags = withContext(Dispatchers.IO) { fetchTrackFlags(liveOboe) }
                    trackFlagsHex = flags.first
                    trackFlagsLabel = flags.second
                }
            }

            delay(1000L)
        }
    }

    // 【V8.209】上面那条 1000ms 主循环保留（.async 检测类），但频响/电平不再由它或任何
    // 页面级 State 驱动 —— 见下方 「Signal Path」注释：整页重组的代价远大于读数本身。

    // Resampling status (uses live Exclusive state; null → assume non-exclusive)
    val effectiveDeviceRate = when {
        isUsbDacMode && dacStreamRate > 0 -> dacStreamRate
        isExclusiveLive == true && streamRate > 0 -> streamRate
        deviceNativeRate > 0 -> deviceNativeRate
        streamRate > 0 -> streamRate
        else -> 0
    }
    // 【修正】Bit-Perfect 仅 USB DAC Exclusive 直写配此标；Oboe Exclusive(MMAP) 不标
    val bitPerfect = isUsbDacMode && sourceRate > 0 && dacStreamRate > 0 && sourceRate == dacStreamRate

    Scaffold(
        topBar = {
            SDWTopBar(
                title = { Text(stringResource(R.string.title_audio_diagnostic), color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── Status banner ──
            item {
                val (statusText, statusColor, statusIcon) = when {
                    isUsbDacMode && bitPerfect -> Triple("Bit-Perfect · USB DAC Exclusive", AccentGreen, Icons.Default.CheckCircle)
                    isUsbDacMode -> Triple("USB DAC Exclusive · Direct Write", AccentGreen, Icons.Default.Usb)
                    !isOboeMode -> Triple(stringResource(R.string.diag_engine_idle), Color(0xFFFF9800), Icons.Default.MusicNote)
                    // [V8.132] BT route wins over the sharing-mode badge: A2DP re-encodes,
                    // so an MMAP exclusive lock must never be shown as bit-perfect/exclusive.
                    btActive -> Triple("Bluetooth · A2DP Re-encode", Color(0xFFFF9800), Icons.Default.Bluetooth)
                    isExclusiveLive == true -> Triple("Oboe Exclusive · MMAP Direct", AccentGreen, Icons.Default.CheckCircle)
                    isOboeMode -> Triple("AAudio Shared · System Mixer In Path", Color(0xFFFF9800), Icons.Default.Warning)
                    else -> Triple(stringResource(R.string.diag_detecting), MaterialTheme.colorScheme.onSurfaceVariant, Icons.Default.Info)
                }
                // [V8.115] 统一玻璃语言：状态卡用 status 色做 accent 洗底（保留原语义色）。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(shape = SDWShapes.Row, accent = statusColor, selected = true, fillBoost = 0f)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(statusText, color = statusColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }

            // ── Source Rate ──
            item {
                DiagnosticRow(
                    icon = Icons.Default.AudioFile,
                    label = stringResource(R.string.diag_source_rate),
                    value = if (sourceRate > 0) "${sourceRate / 1000.0} kHz" else "—",
                    sublabel = stringResource(R.string.diag_source_rate_desc),
                    accent = MaterialTheme.colorScheme.primary
                )
            }

            // ── DAC Stream (USB direct actual) ──
            if (isUsbDacMode && dacStreamRate > 0) {
                item {
                    val matchesSource = sourceRate > 0 && sourceRate == dacStreamRate
                    DiagnosticRow(
                        icon = Icons.Default.Usb,
                        label = "DAC Stream",
                        value = "${dacStreamRate / 1000.0} kHz / ${dacStreamBits}bit",
                        sublabel = stringResource(R.string.diag_dac_stream_desc, dacName),
                        accent = if (matchesSource) AccentGreen else Color(0xFFFF9800)
                    )
                }
            }

            // ── Stream Rate (Oboe actual) ──
            if (isOboeMode && streamRate > 0) {
                item {
                    val matchesSource = sourceRate > 0 && sourceRate == streamRate
                    DiagnosticRow(
                        icon = Icons.Default.Stream,
                        label = stringResource(R.string.diag_stream_rate),
                        value = "${streamRate / 1000.0} kHz",
                        sublabel = stringResource(R.string.diag_stream_rate_desc),
                        accent = if (matchesSource) AccentGreen else Color(0xFFFF9800)
                    )
                }
            }

            // ── Device Native ──
            item {
                DiagnosticRow(
                    icon = Icons.Default.PhoneAndroid,
                    label = stringResource(R.string.diag_device_native),
                    value = if (deviceNativeRate > 0) "${deviceNativeRate / 1000.0} kHz" else "—",
                    sublabel = stringResource(R.string.diag_device_native_desc),
                    accent = MaterialTheme.colorScheme.primary
                )
            }

            // ── Buffer Size ──
            if (framesPerBuffer > 0) {
                item {
                    DiagnosticRow(
                        icon = Icons.Default.Tune,
                        label = "Buffer Size",
                        value = "$framesPerBuffer frames",
                        sublabel = "PROPERTY_OUTPUT_FRAMES_PER_BUFFER",
                        accent = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── Output Device (live) ──
            item {
                val devInfo = liveOutputDeviceInfo
                val devIcon = when (devInfo?.type) {
                    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> Icons.Default.Usb
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> Icons.Default.Bluetooth
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> Icons.Default.Headphones
                    else -> Icons.Default.Smartphone
                }
                val devAccent = when (devInfo?.type) {
                    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> AccentGreen
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.primary
                }
                DiagnosticRow(
                    icon = devIcon,
                    label = stringResource(R.string.diag_output_device),
                    value = liveOutputDeviceLabel,
                    sublabel = if (devInfo != null)
                        stringResource(R.string.diag_output_device_desc, devInfo.productName?.toString() ?: stringResource(R.string.diag_builtin_output))
                    else stringResource(R.string.diag_no_device_info),
                    accent = devAccent
                )
            }

            // ── DAC Capabilities ──
            item {
                val devInfo = liveOutputDeviceInfo
                if (devInfo != null) {
                    val rates = devInfo.sampleRates.joinToString(", ") { "${it / 1000.0}kHz" }.ifEmpty { "—" }
                    val encs = devInfo.encodings.joinToString(", ") { encodingName(it) }.ifEmpty { "—" }
                    val chsCounts = devInfo.channelCounts.joinToString(", ") { "$it ch" }.ifEmpty { "—" }
                    // channelMasks is more reliable than channelCounts on some HALs
                    val chsMasks = devInfo.channelMasks.joinToString(", ") { decodeChannelMask(it) }
                        .ifEmpty { "—" }
                    Column(
                        modifier = Modifier.fillMaxWidth().glassSurface(shape = SDWShapes.Row)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.diag_dac_capabilities), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            Spacer(Modifier.height(6.dp))
                            CapRow("Sample Rates", rates)
                            CapRow("Encodings", encs)
                            CapRow("Channels", chsCounts)
                            CapRow("Chan Masks", chsMasks)
                        }
                    }
                }
            }

            // ── USB Exclusive Transport (Salt-style) ──
            if (isUsbDacMode) {
                item { Spacer(Modifier.height(4.dp)) }
                item {
                    SectionTitle(stringResource(R.string.diag_usb_exclusive_transport))
                }
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().glassSurface(shape = SDWShapes.Row)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Usb, null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.diag_usb_host_exclusive), color = AccentGreen, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.weight(1f))
                                Text(dacName, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            }
                            // 传输状态行（解析 native getStats 字符串）
                            if (dacStats.isNotEmpty()) {
                                val health = when {
                                    dacStats.contains("Health=stable") -> stringResource(R.string.diag_health_stable)
                                    dacStats.contains("Health=fluctuating") -> stringResource(R.string.diag_health_fluctuating)
                                    dacStats.contains("Health=critical") -> stringResource(R.string.diag_health_critical)
                                    dacStats.contains("Health=wedge") -> stringResource(R.string.diag_health_wedge)
                                    dacStats.contains("Health=idle") -> stringResource(R.string.diag_health_idle)
                                    else -> "—"
                                }
                                val healthColor = when (health) {
                                    stringResource(R.string.diag_health_stable) -> AccentGreen
                                    stringResource(R.string.diag_health_fluctuating) -> Color(0xFFFF9800)
                                    stringResource(R.string.diag_health_critical) -> AccentRed
                                    stringResource(R.string.diag_health_wedge) -> AccentRed
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                CapRow(stringResource(R.string.diag_transport_status), health)
                                Text(
                                    dacStats,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 14.sp
                                )
                            }
                            if (dacSupportedRates.isNotEmpty()) {
                                CapRow(stringResource(R.string.diag_supported_rates), dacSupportedRates)
                            }
                            CapRow(stringResource(R.string.diag_bit_depth), if (dacStreamBits > 0) "${dacStreamBits}bit" else "—")
                            CapRow(stringResource(R.string.diag_sample_rate), if (dacStreamRate > 0) "${dacStreamRate / 1000.0}kHz" else "—")
                        }
                    }
                }
            }

            // ── Live SharingMode Monitor ──
            item {
                val dotColor by animateColorAsState(
                    targetValue = when {
                        isUsbDacMode -> AccentGreen
                        !isOboeMode -> MaterialTheme.colorScheme.onSurfaceVariant
                        btActive -> MaterialTheme.colorScheme.primary   // [V8.132] BT route
                        isExclusiveLive == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        isExclusiveLive == true -> AccentGreen
                        else -> Color(0xFFFF9800)
                    },
                    animationSpec = tween(400)
                )
                val dotLabel = when {
                    isUsbDacMode -> "CLAIMED"
                    !isOboeMode -> "Inactive"
                    btActive -> "A2DP"          // [V8.132] not "EXCLUSIVE" — BT re-encodes
                    isExclusiveLive == null -> "—"
                    isExclusiveLive == true -> "EXCLUSIVE"
                    else -> "SHARED"
                }
                Column(
                    modifier = Modifier.fillMaxWidth().glassSurface(shape = SDWShapes.Row)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(SDWShapes.Thumb)
                                .drawWithContent {
                                    drawCircle(dotColor)
                                }
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.diag_live_monitor), color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                stringResource(R.string.diag_live_monitor_desc),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                        Text(
                            dotLabel,
                            color = dotColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // ── Output Mode ──
            item {
                val modeLabel = when {
                    isUsbDacMode -> "USB DAC Direct"
                    // [V8.132] route-first: BT is its own label; never "Oboe Exclusive".
                    btActive && isOboeMode -> "Bluetooth A2DP"
                    isExclusiveLive == true -> "Oboe Exclusive"
                    isExclusiveLive == null -> stringResource(R.string.diag_detecting)
                    isOboeMode -> "AAudio Shared"
                    else -> stringResource(R.string.diag_engine_idle)
                }
                val modeColor = when {
                    isUsbDacMode -> AccentGreen
                    btActive && isOboeMode -> MaterialTheme.colorScheme.primary
                    isExclusiveLive == true -> AccentGreen
                    isExclusiveLive == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    isOboeMode -> MaterialTheme.colorScheme.primary
                    else -> Color(0xFFFF9800)
                }
                DiagnosticRow(
                    icon = Icons.Default.Speaker,
                    label = stringResource(R.string.diag_output_mode),
                    value = modeLabel,
                    sublabel = when {
                        isUsbDacMode -> stringResource(R.string.diag_usb_direct_write_desc)
                        btActive && isOboeMode -> "Audio routed over Bluetooth (A2DP) · re-encoded by BT codec, not bit-perfect"
                        isExclusiveLive == true -> stringResource(R.string.diag_exclusive_desc)
                        isExclusiveLive == null -> stringResource(R.string.diag_polling)
                        else -> stringResource(R.string.diag_standard_pipeline)
                    },
                    accent = modeColor
                )
            }

            // ── Signal Path [V8.124] ──
            item { Spacer(Modifier.height(4.dp)) }
            item { SectionTitle("Signal Path") }
            item { SignalPathCard(oboePlayer) }

            // ── 【V8.210】响度归一化 ──
            item { Spacer(Modifier.height(4.dp)) }
            item { SectionTitle(stringResource(R.string.diag_loudness_section)) }
            item { LoudnessNormCard() }

            // ── 【V8.209】输出电平实时监控 ──
            item { Spacer(Modifier.height(4.dp)) }
            item { SectionTitle(stringResource(R.string.diag_level_section)) }
            item { LevelMonitorCard() }

            // ── AudioFlinger Track Flags ──
            item {
                val flagAccent = when {
                    trackFlagsHex.contains("0x004") || trackFlagsHex.contains("0x100") -> AccentGreen
                    trackFlagsHex == "—" -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> Color(0xFFFF9800)
                }
                DiagnosticRow(
                    icon = Icons.Default.Memory,
                    label = "AF Track Flags",
                    value = trackFlagsHex,
                    sublabel = if (trackFlagsLabel.isNotEmpty()) trackFlagsLabel
                    else stringResource(R.string.diag_af_track_flags_desc),
                    accent = flagAccent
                )
            }

            // ── Audio Focus Events ──
            if (focusLog.isNotEmpty()) {
                item { Spacer(Modifier.height(4.dp)) }
                item {
                    SectionTitle("Audio Focus Events")
                }
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().glassSurface(shape = SDWShapes.Row)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            focusLog.take(8).forEach { event ->
                                val (icon, color) = when (event.event) {
                                    "GAIN" -> Icons.Default.CheckCircle to AccentGreen
                                    "LOSS" -> Icons.Default.Cancel to AccentRed
                                    "LOSS_TRANSIENT" -> Icons.Default.Warning to Color(0xFFFF9800)
                                    "LOSS_TRANSIENT_CAN_DUCK" -> Icons.Default.Remove to MaterialTheme.colorScheme.primary
                                    else -> Icons.Default.Info to MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Row(
                                    modifier = Modifier.padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(event.time, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    Spacer(Modifier.width(8.dp))
                                    Text(event.event, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }

            // ── Lib Status ──
            item { Spacer(Modifier.height(4.dp)) }
            item {
                val libLoaded = remember { OboeDirectPlayer.nativeLibLoaded }
                DiagnosticRow(
                    icon = Icons.Default.Code,
                    label = stringResource(R.string.diag_native_lib),
                    value = if (libLoaded) "Loaded ✓" else "Not Loaded",
                    sublabel = "OboeDirectPlayer JNI · liboboeaudio",
                    accent = if (libLoaded) AccentGreen else AccentRed
                )
            }

            // ── Resampling Analysis ──
            if (sourceRate > 0 && effectiveDeviceRate > 0) {
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    SectionTitle("Resampling Analysis")
                }
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().glassSurface(shape = SDWShapes.Row)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            if (bitPerfect) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, null, tint = AccentGreen, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(R.string.diag_usb_direct_eq),
                                        color = AccentGreen,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.diag_usb_direct_eq_desc, sourceRate, dacStreamRate, dacStreamBits),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            } else if (btActive && isOboeMode) {
                                // [V8.132] BT: the Oboe stream can hold MMAP Exclusive, but the
                                // signal is re-encoded by the BT codec downstream of the app.
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Bluetooth, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Bluetooth A2DP · Codec Re-encode",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Source ($sourceRate Hz) → Oboe stream → Bluetooth A2DP codec → Device. The Oboe stream may still hold an MMAP exclusive lock, but the BT codec re-encodes the signal — not bit-perfect.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            } else if (isExclusiveLive == true && isOboeMode) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, null, tint = AccentGreen, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(R.string.diag_oboe_exclusive_mmap),
                                        color = AccentGreen,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.diag_oboe_exclusive_desc, sourceRate),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            } else if (isExclusiveLive == false && isOboeMode) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, null, tint = Color(0xFFFF9800), modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(R.string.diag_aaudio_shared),
                                        color = Color(0xFFFF9800),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Source ($sourceRate Hz) → App Stream ($streamRate Hz) → System Mixer → Device ($deviceNativeRate Hz)",
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.diag_aaudio_shared_desc),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, null, tint = Color(0xFFFF9800), modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(R.string.diag_app_resampling),
                                        color = Color(0xFFFF9800),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Source ($sourceRate Hz) → Device ($effectiveDeviceRate Hz)",
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.diag_app_resampling_desc),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }
            }

            // ── Explanation ──
            item { Spacer(Modifier.height(8.dp)) }
            item {
                SectionTitle(stringResource(R.string.diag_about_page))
            }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().glassSurface(shape = SDWShapes.Row)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "This page shows real-time audio path information.",
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.diag_explanation),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ── Data class ──

private data class FocusEvent(val time: String, val event: String)

// ── Private utilities ──

// ═══════════════════════════════════════════════════════════════════
// 【V8.208】输出电平监控
//
// 线性幅度（0..1 FS）→ dBFS 的三件套。之所以要在 UI 侧换算而不是让 native 直接给 dB：
// dB 是对数，0 附近会炸到 -inf，所以 native 只留线性真值，显示上的取舍交给这里。
// ── 区间 ── -60…0 dBFS 映射到条形 0…1：低于 -60dB 的部分压到看不见，因为那是底噪，
// 放进去只会让"有声/无声"的边界看起来糊。
// ═══════════════════════════════════════════════════════════════════
private fun dbfsOf(v: Float): Float =
    if (v > 1e-6f) 20f * kotlin.math.log10(v) else Float.NEGATIVE_INFINITY

private fun fmtDb(v: Float): String =
    if (v <= 1e-6f) "-∞" else String.format(Locale.US, "%.1f", dbfsOf(v))

private fun dbFrac(v: Float): Float =
    if (v <= 1e-6f) 0f else ((dbfsOf(v) + 60f) / 60f).coerceIn(0f, 1f)

private fun levelColor(v: Float): Color = when {
    v >= 0.999f -> AccentRed      // 已经到顶，随时会削波
    v >= 0.70f -> Color(0xFFFFB300)
    else -> AccentGreen
}


// ═══════════════════════════════════════════════════════════════════
// 【V8.209】Signal Path 卡片：自成叶子的重组单元
//
// 卡内的三类内容更新频率差两个数量级，原来共用一个 State 只能用最慢的那个（80ms）
// 或者最快的那个（把整页拖垮）。现在按「谁读谁重组」拆开：
//   · DSP 点阵 / Clip 计数 → 250ms，变化本来就慢；
//   · 频响条 / 电平条 → 逐帧（见 FreqBars、LevelBarLine），走 Canvas 内部的普通数组，
//     完全不进快照，既没有重组也没有 delay(80) 那种台阶感。
// ═══════════════════════════════════════════════════════════════════
@Composable
private fun SignalPathCard(oboe: OboeDirectPlayer?) {
    var dspChain by remember { mutableStateOf(IntArray(16)) }
    var clipCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            val svc = MusicService.instance
            if (svc?.isDacActive() == true) {
                // 【V9.01】USB DAC 独占路径：Oboe 为 null，改读 UsbDacManager 的链状态，
                // 否则 dspChain 全 0 会被误判为「原声直通」。
                dspChain = UsbDacManager.getDspChainState()
                clipCount = 0
            } else {
                val liveOboe = svc?.getOboePlayer()
                dspChain = liveOboe?.getDspChainState() ?: IntArray(16)
                clipCount = liveOboe?.getClipCount() ?: 0
            }
            delay(250L)
        }
    }
    Column(modifier = Modifier.fillMaxWidth().glassSurface(shape = SDWShapes.Row)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.diag_dsp_chain), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(10.dp))
            // [V8.129] 只列出本 App 真正具备、且有开关的 DSP 模块（PreGain/DC/Master/
            // Limiter/SoftClip/SinkEQ 是固定内部级，不算「功能」，不放这里）。
            // 【V8.176】AGC 在第 6 位，是活的 —— 之前注释说它是死代码是错的。
            val stages = listOf(
                "AutoEQ" to 2, "MSEB" to 3, "EQ5" to 4, "AGC" to 6, "Loud" to 7,
                "M/S" to 8, "Xfeed" to 9, "Trans" to 10, "Comp" to 11, "Dither" to 14
            )
            for (chunk in stages.chunked(5)) {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    for ((name, idx) in chunk) {
                        val on = dspChain.getOrElse(idx) { 0 } == 1
                        val dotColor = if (on) AccentGreen else MaterialTheme.colorScheme.onSurfaceVariant
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(7.dp).clip(SDWShapes.Badge)
                                    .drawWithContent { drawCircle(dotColor) }
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                name,
                                color = if (on) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1
                            )
                        }
                    }
                }
            }
            if (dspChain.all { it == 0 }) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.diag_raw_passthrough), color = Color(0xFFFF9800), fontSize = 11.sp)
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ShowChart, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.diag_realtime_response), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
            FreqBars()
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (clipCount > 0) Icons.Default.Warning else Icons.Default.CheckCircle,
                    null,
                    tint = if (clipCount > 0) AccentRed else AccentGreen,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.diag_clip_detection), color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(
                    "$clipCount",
                    color = if (clipCount > 0) AccentRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
                if (clipCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { oboe?.resetClipStats(); clipCount = 0 }) {
                        Text(stringResource(R.string.diag_action_reset), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * 逐帧频响条。
 *
 * 关键点：数据是从 native **直接读进普通 FloatArray** 的（bulk 接口，一次 JNI、不分配），
 * 既不进 Compose 快照也不触发重组 —— 重绘只由 `tick` 这个时间戳驱动，读它的地方只有
 * 这一个小组件。旧实现是 State + delay(80)：12.5Hz 的台阶 + 整页重组，看着就「卡卡的」。
 */
@Composable
private fun FreqBars() {
    val buf = remember { FloatArray(25) }
    val smoothed = remember { FloatArray(8) }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) { withFrameMillis { tick = it } }
    }
    @Suppress("UNUSED_VARIABLE") val frame = tick     // 读进组合作用域 → 每帧重组这一个 Canvas
    Canvas(modifier = Modifier.fillMaxWidth().height(64.dp)) {
        MusicService.instance?.getOboePlayer()?.readBandLevels(buf)
        val gap = size.width / 8f
        for (i in 0 until 8) {
            // 每段取 L/R 中较大的那一条：这是「有多少内容」而不是「偏左还是偏右」
            val target = maxOf(buf[i], buf[8 + i]).coerceIn(0f, 1f)
            // 起快落慢的一阶平滑：60fps 下既跟得上鼓点，又不会因为帧间抖动而刺眼
            val s = smoothed[i]
            val k = if (target > s) 0.45f else 0.16f
            smoothed[i] = s + (target - s) * k
            val barH = (size.height * smoothed[i].coerceIn(0f, 1f)).coerceAtLeast(2f)
            drawRoundRect(
                color = AccentGreen,
                topLeft = Offset(i * gap + gap * 0.2f, size.height - barH),
                size = Size(gap * 0.6f, barH),
                cornerRadius = CornerRadius(2f, 2f)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// 【V8.210】响度归一化参数
//
// 这套参数有四个值，分开列是故意的 —— 它们本来就该相等，不等就说明某一环断了：
//   元数据  = 文件里读到的 ReplayGain / LUFS（读不到就是「无元数据」，增益恒 0）
//   计算值  = LoudnessNormalizer.gainFor() 按目标档位折算出来的 dB
//   下发值  = MusicService 实际推给链路的那个 dB
//   生效值  = native 里 g_normGain 的真实读数（Oboe 播放器重建后会回到 1.0，
//             只有 refreshNormalization() 补发后才对得上 —— 这个差就是「切歌后音量不对」的根）
// ═══════════════════════════════════════════════════════════════════
private data class NormInfo(
    val title: String,
    val enabled: Boolean,
    val mode: String,
    val targetLufs: Float,
    val tagText: String,
    val computedDb: Float,
    val appliedDb: Float,
    val activeDb: Float,
    val link: String,
    /** 【V8.239】false = 当前链路无法回读生效值（DAC 独占走 UsbAudioDriver，没有回读 JNI），
     *  此时不该拿 activeDb 参与"三值对账"，否则是一个永远亮着的假告警。 */
    val activeKnown: Boolean = true
)

private fun readNormInfo(context: android.content.Context, path: String?): NormInfo {
    val cfg = com.sdw.music.player.core.audio.dsp.LoudnessNormalizer.load(context)
    if (path.isNullOrBlank()) {
        return NormInfo("—", cfg.enabled, cfg.mode.name, cfg.targetLufs, context.getString(R.string.diag_norm_no_track), 0f, 0f, 0f, "—")
    }
    val tag = com.sdw.music.player.core.audio.dsp.LoudnessNormalizer.debugTag(path)
    val bits = mutableListOf<String>()
    tag?.trackGainDb?.let { bits.add("track ${"%.1f".format(it)}dB") }
    tag?.albumGainDb?.let { bits.add("album ${"%.1f".format(it)}dB") }
    tag?.trackPeak?.let { bits.add("peak ${"%.3f".format(it)}") }
    tag?.lufs?.let { bits.add("LUFS ${"%.1f".format(it)}") }
    val computed = com.sdw.music.player.core.audio.dsp.LoudnessNormalizer.gainFor(path, cfg)
    val svc = com.sdw.music.player.core.audio.playback.MusicService.instance
    val dac = try { com.sdw.music.player.core.audio.engine.UsbDacManager.isClaimed() } catch (_: Throwable) { false }
    return NormInfo(
        title = com.sdw.music.player.core.audio.playback.MusicService.currentSong?.title ?: path.substringAfterLast('/'),
        enabled = cfg.enabled,
        mode = when (cfg.mode) {
            com.sdw.music.player.core.audio.dsp.LoudnessNormalizer.Mode.TRACK -> context.getString(R.string.settings_loudness_mode_track)
            com.sdw.music.player.core.audio.dsp.LoudnessNormalizer.Mode.ALBUM -> context.getString(R.string.settings_loudness_mode_album)
            else -> context.getString(R.string.settings_loudness_mode_auto)
        },
        targetLufs = cfg.targetLufs,
        tagText = if (bits.isEmpty()) context.getString(R.string.diag_norm_no_metadata) else bits.joinToString(" · "),
        computedDb = computed,
        appliedDb = svc?.currentNormalizationDb() ?: 0f,
        activeDb = svc?.getOboePlayer()?.getNormalizationGainDb() ?: 0f,
        link = if (dac) context.getString(R.string.diag_norm_link_dac) else "Oboe",
        activeKnown = !dac    // DAC 独占：增益走 UsbAudioDriver，Oboe 侧读不到 → 不参与对账
    )
}

@Composable
private fun LoudnessNormCard() {
    val context = LocalContext.current
    var info by remember { mutableStateOf<NormInfo?>(null) }
    LaunchedEffect(Unit) {
        var lastPath: String? = null
        var tick = 0
        while (true) {
            val song = MusicService.currentSong
            val path = song?.filePath?.takeIf { it.isNotBlank() }
                ?: song?.path?.takeIf { it.startsWith("/") }
            val dac = try { com.sdw.music.player.core.audio.engine.UsbDacManager.isClaimed() } catch (_: Throwable) { false }
            if (path != lastPath) {
                lastPath = path
                tick = 0
                // 读文件标签是 IO —— 挪到 IO 线程
                info = withContext(Dispatchers.IO) { readNormInfo(context, path) }
            } else if (tick++ % 2 == 0) {
                // 【V8.239】同一首歌也要周期性重算「计算增益」—— 之前只在换歌时算一次，
                // 于是：① 后台扫描给当前曲补上响度 → refreshNormalization 重发，applied 变了
                // 而 computed 停在旧值（往往是 0.00）→ 三值长期不一致且不自愈；
                // ② 用户在设置页改目标档位/开关 → 同理。读标签仍在 IO 线程，2 秒一次可忽略。
                val fresh = withContext(Dispatchers.IO) { readNormInfo(context, path) }
                info = info?.copy(
                    enabled = fresh.enabled,
                    mode = fresh.mode,
                    targetLufs = fresh.targetLufs,
                    tagText = fresh.tagText,
                    computedDb = fresh.computedDb,
                    appliedDb = MusicService.instance?.currentNormalizationDb() ?: 0f,
                    activeDb = if (dac) 0f else (MusicService.instance?.getOboePlayer()?.getNormalizationGainDb() ?: 0f),
                    activeKnown = !dac
                )
            } else {
                // 同一首歌也要对账：播放器重建会把 native 增益打回 1.0，补发后就又对上了
                info = info?.copy(
                    appliedDb = MusicService.instance?.currentNormalizationDb() ?: 0f,
                    activeDb = if (dac) 0f else (MusicService.instance?.getOboePlayer()?.getNormalizationGainDb() ?: 0f),
                    activeKnown = !dac
                )
            }
            // 【V8.239】不一致时留一条日志：比让用户截图念数字更容易定位是哪一环断的
            info?.let { s ->
                if (s.enabled && s.activeKnown &&
                    (kotlin.math.abs(s.computedDb - s.appliedDb) > 0.01f ||
                        kotlin.math.abs(s.appliedDb - s.activeDb) > 0.01f)
                ) {
                    android.util.Log.w("NormDiag",
                        "mismatch computed=%.2f applied=%.2f active=%.2f (%s)".format(
                            s.computedDb, s.appliedDb, s.activeDb, s.title))
                }
            }
            delay(1000L)
        }
    }
    val i = info
    Column(modifier = Modifier.fillMaxWidth().glassSurface(shape = SDWShapes.Row)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VolumeUp, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_loudness_norm), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(
                    if (i?.enabled == true) stringResource(R.string.diag_norm_on) else stringResource(R.string.diag_norm_off),
                    color = if (i?.enabled == true) AccentGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(10.dp))
            if (i == null) {
                Text(stringResource(R.string.diag_norm_reading), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            } else {
                CapRow(stringResource(R.string.diag_norm_cur_track), i.title)
                CapRow(stringResource(R.string.settings_loudness_mode), i.mode)
                CapRow(stringResource(R.string.settings_loudness_target),
                    if (i.targetLufs == com.sdw.music.player.core.audio.dsp.LoudnessNormalizer.TARGET_AUTO)
                        stringResource(R.string.diag_auto_lufs).format(
                            com.sdw.music.player.core.audio.dsp.LoudnessNormalizer.effectiveTarget(
                                com.sdw.music.player.core.audio.dsp.LoudnessNormalizer.load(context)))
                    else "%.1f LUFS".format(i.targetLufs))
                CapRow(stringResource(R.string.diag_norm_metadata), i.tagText)
                CapRow(stringResource(R.string.diag_norm_computed), "${"%.2f".format(i.computedDb)} dB")
                CapRow(stringResource(R.string.diag_norm_applied), "${"%.2f".format(i.appliedDb)} dB")
                CapRow(stringResource(R.string.diag_norm_active),
                    if (i.activeKnown) "${"%.2f".format(i.activeDb)} dB"
                    else stringResource(R.string.diag_norm_na))
                CapRow(stringResource(R.string.diag_norm_link), i.link)
                // 对账提示：三个数不一致 = 某一环没接上，比让用户自己比数字有用。
                // 【V8.239】DAC 独占读不到生效值，只对比「计算 vs 已下发」，不再假告警。
                val mismatch = kotlin.math.abs(i.computedDb - i.appliedDb) > 0.01f ||
                    (i.activeKnown && kotlin.math.abs(i.appliedDb - i.activeDb) > 0.01f)
                if (i.enabled && mismatch) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.diag_norm_mismatch),
                        color = Color(0xFFFF9800), fontSize = 11.sp
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// 【V8.208 / V8.209】输出电平监控
// ═══════════════════════════════════════════════════════════════════
@Composable
private fun LevelMonitorCard() {
    Column(modifier = Modifier.fillMaxWidth().glassSurface(shape = SDWShapes.Row)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Speed, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.diag_level_realtime), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(10.dp))
            LevelBarLine("L", 0)
            Spacer(Modifier.height(6.dp))
            LevelBarLine("R", 1)
            Spacer(Modifier.height(8.dp))
            LevelReadout()
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.diag_level_history),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(4.dp))
            LevelHistory()
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.diag_level_tap_point),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp
            )
        }
    }
}

/**
 * 单声道电平条：填充 = 瞬时 RMS，游标 = native 给的 1.5s 衰减峰值。
 * 和 [FreqBars] 同样的套路 —— 数据不进 State，因此这组条形的动态更新不引起任何重组。
 */
@Composable
private fun LevelBarLine(label: String, channel: Int) {
    val buf = remember { FloatArray(25) }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) { withFrameMillis { tick = it } }
    }
    @Suppress("UNUSED_VARIABLE") val frame = tick
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.width(14.dp)
        )
        Canvas(modifier = Modifier.weight(1f).height(9.dp)) {
            MusicService.instance?.getOboePlayer()?.readBandLevels(buf)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.07f), topLeft = Offset.Zero,
                size = size, cornerRadius = CornerRadius(3f, 3f)
            )
            val rms = buf[21 + channel]
            val w = size.width * dbFrac(rms)
            if (w > 1f) {
                drawRoundRect(
                    color = levelColor(rms), topLeft = Offset.Zero,
                    size = Size(w, size.height), cornerRadius = CornerRadius(3f, 3f)
                )
            }
            val px = size.width * dbFrac(buf[23 + channel])
            if (px > 1f) {
                drawRoundRect(
                    color = Color(0xFFFF3D3D), topLeft = Offset((px - 1.5f).coerceAtLeast(0f), 0f),
                    size = Size(3f, size.height), cornerRadius = CornerRadius(1.5f, 1.5f)
                )
            }
        }
    }
}

/**
 * 数字读数单独 10Hz 更新 —— 文字按 60fps 刷是没法读的，而且每次都要重排一次文本。
 * 只有这一行会重组。
 */
@Composable
private fun LevelReadout() {
    var rmsL by remember { mutableFloatStateOf(0f) }
    var rmsR by remember { mutableFloatStateOf(0f) }
    var peakL by remember { mutableFloatStateOf(0f) }
    var peakR by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val b = FloatArray(25)
        while (true) {
            MusicService.instance?.getOboePlayer()?.readBandLevels(b)
            rmsL = b[21]; rmsR = b[22]; peakL = b[23]; peakR = b[24]
            delay(100L)
        }
    }
    val over = maxOf(peakL, peakR) >= 0.999f
    Text(
        stringResource(
            R.string.diag_level_readout,
            fmtDb(rmsL), fmtDb(peakL), fmtDb(rmsR), fmtDb(peakR)
        ),
        color = if (over) AccentRed else MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp
    )
}

/** 24 秒电平滚动历史：200ms 采样写环形数组，Canvas 逐帧重绘。 */
@Composable
private fun LevelHistory() {
    val hist = remember { FloatArray(120) }
    val head = remember { intArrayOf(0) }
    var tick by remember { mutableLongStateOf(0L) }
    // 采样：200ms 一点，写环形数组（普通数组，不触发重组）
    LaunchedEffect(Unit) {
        val b = FloatArray(25)
        while (true) {
            MusicService.instance?.getOboePlayer()?.readBandLevels(b)
            hist[head[0]] = maxOf(b[21], b[22])
            head[0] = (head[0] + 1) % hist.size
            delay(200L)
        }
    }
    LaunchedEffect(Unit) {
        while (true) { withFrameMillis { tick = it } }
    }
    @Suppress("UNUSED_VARIABLE") val frame = tick
    Canvas(modifier = Modifier.fillMaxWidth().height(44.dp)) {
        val bw = size.width / hist.size
        val refY = size.height * (1f - dbFrac(0.5f))       // -6 dBFS 参考线
        drawRoundRect(
            color = Color.White.copy(alpha = 0.18f), topLeft = Offset(0f, refY),
            size = Size(size.width, 1f), cornerRadius = CornerRadius(0.5f, 0.5f)
        )
        val start = head[0]
        for (i in 0 until hist.size) {
            val v = hist[(start + i) % hist.size]
            if (v <= 1e-6f) continue
            val hgt = (size.height * dbFrac(v)).coerceAtLeast(1f)
            drawRoundRect(
                color = levelColor(v),
                topLeft = Offset(i * bw, size.height - hgt),
                size = Size((bw * 0.7f).coerceAtLeast(1f), hgt),
                cornerRadius = CornerRadius(1f, 1f)
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
    )
}

@Composable
private fun CapRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.width(90.dp))
        Text(value, color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun DiagnosticRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    sublabel: String,
    accent: Color
) {
    Column(
        modifier = Modifier.fillMaxWidth().glassSurface(shape = SDWShapes.Row)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(sublabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Text(value, color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}

// ── Helpers ──

private fun getDeviceLabel(info: AudioDeviceInfo?): String {
    if (info == null) return "—"
    return when (info.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired HP"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB DAC"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT SCO"
        AudioDeviceInfo.TYPE_DOCK -> "Dock"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        else -> "Other (${info.type})"
    }
}

private fun decodeChannelMask(mask: Int): String {
    val ch = mutableListOf<String>()
    if ((mask and AudioFormat.CHANNEL_OUT_FRONT_LEFT) != 0) ch.add("L")
    if ((mask and AudioFormat.CHANNEL_OUT_FRONT_RIGHT) != 0) ch.add("R")
    if ((mask and AudioFormat.CHANNEL_OUT_FRONT_CENTER) != 0) ch.add("C")
    if ((mask and AudioFormat.CHANNEL_OUT_LOW_FREQUENCY) != 0) ch.add("LFE")
    if ((mask and AudioFormat.CHANNEL_OUT_BACK_LEFT) != 0) ch.add("SL")
    if ((mask and AudioFormat.CHANNEL_OUT_BACK_RIGHT) != 0) ch.add("SR")
    if ((mask and AudioFormat.CHANNEL_OUT_SIDE_LEFT) != 0) ch.add("SL")
    if ((mask and AudioFormat.CHANNEL_OUT_SIDE_RIGHT) != 0) ch.add("SR")
    // MONO bit is same as FRONT_LEFT (0x1) — if only that bit is set, it's mono
    if (mask == AudioFormat.CHANNEL_OUT_MONO) return "Mono"
    return ch.joinToString("+").ifEmpty { ("0x%X · %d ch").format(mask, Integer.bitCount(mask)) }
}

private fun encodingName(encoding: Int): String = when (encoding) {
    AudioFormat.ENCODING_PCM_8BIT -> "PCM_8"
    AudioFormat.ENCODING_PCM_16BIT -> "PCM_16"
    AudioFormat.ENCODING_PCM_FLOAT -> "FLOAT"
    AudioFormat.ENCODING_AC3 -> "AC3"
    AudioFormat.ENCODING_E_AC3 -> "EAC3"
    AudioFormat.ENCODING_DTS -> "DTS"
    AudioFormat.ENCODING_DTS_HD -> "DTS-HD"
    AudioFormat.ENCODING_DOLBY_TRUEHD -> "TrueHD"
    AudioFormat.ENCODING_IEC61937 -> "IEC61937"
    else -> "FMT_$encoding"
}

/**Parse dumpsys media.audio_flinger for our app's Active Track flags.
 * Track data lines embed the PID and flags inline:  ... PID/UID ... 0xHHHH ...
 * Returns Pair(hexFlags, description) or Pair("—", "") on failure.
 */
/**
 * Fetch AudioFlinger track flags for the current process.
 *
 * Android 13+ blocks non-system apps from running `dumpsys` on system services.
 * On those devices, we fall back to Oboe stream mode inference:
 *   - MMAP Exclusive  → 0x100 (MMAP_NOIRQ)
 *   - MMAP Shared    → 0x000 (Default Shared)
 *   - Legacy          → 0x000 + note
 */
private const val TAG = "AudioDiag"

private suspend fun fetchTrackFlags(oboePlayer: Any?): Pair<String, String> = withContext(Dispatchers.IO) {
    // ── Primary path: dumpsys media.audio_flinger ──
    try {
        val proc = Runtime.getRuntime().exec("dumpsys media.audio_flinger 2>/dev/null")
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        val pid = android.os.Process.myPid().toString()

        var flagHex: String? = null
        val hexPattern = Regex("""\b0x[0-9A-Fa-f]{3,8}\b""")
        var lineCount = 0

        reader.useLines { lines ->
            for (line in lines) {
                lineCount++
                val idx = line.indexOf(pid)
                if (idx < 0) continue
                // Flags column appears after the PID/UID segment; grab the first 0xHHHH after it
                val tail = line.substring(idx + pid.length)
                val match = hexPattern.find(tail)
                if (match != null) {
                    flagHex = match.value
                    break
                }
            }
        }
        val exitCode = proc.waitFor()
        Log.d(TAG, "dumpsys exitCode=$exitCode lineCount=$lineCount flagHex=$flagHex pid=$pid")

        // dumpsys blocked (Android 13+): empty output or non-zero exit
        if (lineCount == 0 || exitCode != 0) {
            Log.w(TAG, "dumpsys unavailable — falling back to Oboe mode inference")
            return@withContext inferFromOboeMode(oboePlayer)
        }

        val hex = flagHex
        if (hex == null) {
            Log.w(TAG, "PID $pid found in $lineCount lines but no flag match — falling back")
            return@withContext inferFromOboeMode(oboePlayer)
        }

        val flags = hex.removePrefix("0x").toLong(16)
        val parts = mutableListOf<String>()
        if ((flags and 0x001) != 0L) parts.add("FAST")
        if ((flags and 0x002) != 0L) parts.add("DEEP_BUFFER")
        if ((flags and 0x004) != 0L) parts.add("DIRECT")
        if ((flags and 0x008) != 0L) parts.add("RAW")
        if ((flags and 0x100) != 0L) parts.add("MMAP_NOIRQ")
        if ((flags and 0x200) != 0L) parts.add("NON_BLOCKING")
        if ((flags and 0x400) != 0L) parts.add("GAPLESS")
        if (parts.isEmpty()) parts.add("Default Shared")

        Log.d(TAG, "parsed flags: $hex → ${parts.joinToString(" | ")}")
        Pair(hex, "AF Flags: ${parts.joinToString(" | ")}")
    } catch (e: Exception) {
        Log.e(TAG, "dumpsys failed", e)
        inferFromOboeMode(oboePlayer)
    }
}

/**
 * Fallback: infer track flags from the Oboe stream mode.
 * Called when dumpsys is unavailable (Android 13+ permission restriction).
 */
private fun inferFromOboeMode(oboePlayer: Any?): Pair<String, String> {
    Log.d(TAG, "inferFromOboeMode: oboePlayer=$oboePlayer")
    if (oboePlayer == null) {
        Log.w(TAG, "oboePlayer is null — cannot infer flags")
        return Pair("—", "")
    }
    return try {
        val method = oboePlayer.javaClass.getMethod("isExclusiveMode")
        val isExclusive = method.invoke(oboePlayer) as? Boolean ?: false
        Log.d(TAG, "oboePlayer.isExclusiveMode() = $isExclusive")
        if (isExclusive) {
            Pair("0x100", "AF Flags: MMAP_NOIRQ (Oboe Exclusive)")
        } else {
            // Shared or Legacy — the actual flags depend on AudioFlinger policy
            Pair("0x000", "AF Flags: Shared (Oboe/Legacy)")
        }
    } catch (e: Exception) {
        Log.e(TAG, "inferFromOboeMode reflection failed", e)
        Pair("—", "")
    }
}

private fun extractSampleRateFromFile(path: String): Int {
    return try {
        // 【V8.429】收敛到单线程闸门（诊断页与播放页会同时探测；native abort 详见 MediaProbe）
        com.sdw.music.player.core.audio.analysis.MediaProbe.onProbeThread {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(path)
                var rate = 0
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        rate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                        android.util.Log.d("AudioDiagnostic", "extractSampleRate: path=${path.takeLast(30)} mime=$mime rate=$rate")
                        break
                    }
                }
                rate
            } finally {
                runCatching { extractor.release() }
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("AudioDiagnostic", "extractSampleRate failed: ${e.message}")
        0
    }
}
