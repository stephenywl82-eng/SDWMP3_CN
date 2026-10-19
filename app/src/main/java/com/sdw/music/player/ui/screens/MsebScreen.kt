package com.sdw.music.player.ui.screens
import com.sdw.music.player.core.audio.dsp.DeviceDspAdvice
import com.sdw.music.player.core.audio.dsp.DspSnapshot
import com.sdw.music.player.core.audio.dsp.MsebParams
import com.sdw.music.player.core.audio.dsp.MsebPreset
import com.sdw.music.player.core.audio.engine.BtCodecTracker
import com.sdw.music.player.core.audio.dsp.MsebCalculator
import com.sdw.music.player.core.audio.dsp.MsebDeviceAdapt
import com.sdw.music.player.core.audio.dsp.MsebPresets
import com.sdw.music.player.core.audio.playback.MusicService
import com.sdw.music.player.core.audio.engine.OboeDirectPlayer

import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import com.sdw.music.player.R
import com.sdw.music.player.ui.components.ExpressiveSwitch
import com.sdw.music.player.ui.components.SDWDivider
import com.sdw.music.player.ui.components.glassSurface
import kotlinx.coroutines.delay
import java.util.Timer
import java.util.TimerTask

/**
 * 【真·双耳环绕】声场拖动手柄：水平拖动 = 方位旋转(±180°)，垂直拖动 = 俯仰(±90°)。
 * 拖动时把归一化坐标换算成 yaw/pitch 回调出去，native 据此旋转整圈虚拟音箱。
 */
@Composable
fun HrtfFieldPanner(yaw: Float, pitch: Float, onYawPitch: (Float, Float) -> Unit, enabled: Boolean = true) {
    val sizeDp = 180.dp
    val accent = MaterialTheme.colorScheme.tertiary
    val ring = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    Box(
        modifier = Modifier
            .size(sizeDp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(16.dp)
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitPointerEventScope {
                    fun report(p: Offset) {
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        val nx = (p.x / w).coerceIn(0f, 1f)
                        val ny = (p.y / h).coerceIn(0f, 1f)
                        val nyaw = (nx * 2f - 1f) * 180f
                        val npitch = (0.5f - ny) * 180f
                        onYawPitch(nyaw, npitch.coerceIn(-90f, 90f))
                    }
                    while (true) {
                        val down = awaitPointerEvent()
                        val d = down.changes.first()
                        if (!d.pressed) continue
                        var cur = d.position
                        d.consume()
                        report(cur)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.first()
                            if (!change.pressed) break
                            cur = change.position
                            change.consume()
                            report(cur)
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            drawCircle(
                color = ring,
                radius = (w.coerceAtMost(h) / 2f) - 6.dp.toPx(),
                style = Stroke(width = 1.5.dp.toPx())
            )
            drawLine(ring, Offset(cx, 6.dp.toPx()), Offset(cx, h - 6.dp.toPx()), strokeWidth = 1.dp.toPx())
            drawLine(ring, Offset(6.dp.toPx(), cy), Offset(w - 6.dp.toPx(), cy), strokeWidth = 1.dp.toPx())
            val nx = ((yaw / 180f) + 1f) / 2f
            val ny = 0.5f - pitch / 180f
            val kx = (nx * w).coerceIn(8f, w - 8f)
            val ky = (ny * h).coerceIn(8f, h - 8f)
            drawCircle(color = accent, radius = 9.dp.toPx(), center = Offset(kx, ky))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MsebScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var params by remember { mutableStateOf(MsebCalculator.load(context)) }
    var enabled by remember { mutableStateOf(MsebCalculator.isEnabled(context)) }
    var savedName by remember { mutableStateOf(MsebCalculator.getPresetName(context)) }
    // 【V8.184】给当前音色起一个自己的名字（只记名字，不存参数）——点了之后
    // 播放页胶囊和这里的副标题都换成这个名字，不用再看那一串英文特征词。
    var showNameDialog by remember { mutableStateOf(false) }

    // Preset list (built-in + user) — 可刷新，保存后更新
    var presets by remember { mutableStateOf(MsebPresets.getAll(context)) }

    // Save dialog
    var showSaveDialog by remember { mutableStateOf(false) }

    // 【V8.170】页面是否在前台 —— 门控 FFT / DAC 轮询，离开页面不再空转
    var uiActive by remember { mutableStateOf(false) }

    // 写盘防抖：Slider 拖动时 onValueChange 每秒几十次，逐次 apply() 会白烧 IO
    val writeDebouncer = remember { DebouncedWriter(200L) }

    DisposableEffect(Unit) {
        uiActive = true
        onDispose {
            uiActive = false
            writeDebouncer.cancel()
        }
    }

    // ── 【V8.3】动态压缩（独立全局模块，独立开关）──
    val compPrefs = remember { context.getSharedPreferences("compressor", Context.MODE_PRIVATE) }
    var compEnabled by remember { mutableStateOf(compPrefs.getBoolean("enabled", false)) }
    var compThreshold by remember { mutableStateOf(compPrefs.getFloat("threshold", -18f)) }
    var compRatio by remember { mutableStateOf(compPrefs.getFloat("ratio", 2f)) }
    var compAttack by remember { mutableStateOf(compPrefs.getFloat("attack", 10f)) }
    var compRelease by remember { mutableStateOf(compPrefs.getFloat("release", 120f)) }
    var compMakeup by remember { mutableStateOf(compPrefs.getFloat("makeup", 0f)) }

    // ── 【V8.3】等响补偿（独立全局模块，独立开关）──
    val loudPrefs = remember { context.getSharedPreferences("loudness", Context.MODE_PRIVATE) }
    var loudEnabled by remember { mutableStateOf(loudPrefs.getBoolean("enabled", false)) }
    var loudIntensity by remember { mutableStateOf(loudPrefs.getFloat("intensity", 1f)) }

    // ── 【V8.3】Crossfeed（消除头中效应，仅 Oboe）──
    val xfPrefs = remember { context.getSharedPreferences("crossfeed", Context.MODE_PRIVATE) }
    var xfEnabled by remember { mutableStateOf(xfPrefs.getBoolean("enabled", false)) }
    var xfAmount by remember { mutableStateOf(xfPrefs.getFloat("amount", 0.6f)) }

    // ── 【V8.170】DTS 环绕 —— 与播放页三点菜单 / 控制栏胶囊共用同一状态源 ──
    //
    // 【V8.171 修同步】这一份只是 SharedPreferences 里的**镜像**，不是数据源。
    // 以前它只在首次组合时读一次，之后没人再刷新：在播放页胶囊上拨了 DTS，回到这一屏
    // 时开关还停在旧值（表现为"播页面开着，MSEB 这里还是关"）。所以下面加了两条回路：
    // ① 进入页面时随 native 状态一起读回；② 前台期间每 500ms 对齐一次。
    // 前提是所有写入都要落盘（见 setDts / 预设 / 设备建议三处），否则镜像会被数据源打回。
    var dtsOn by remember { mutableStateOf(MsebCalculator.isDtsEnabled(context)) }

    // ── 【第2步·隔离探针】HRTF 双耳渲染探针（诊断用）：与 DTS 互斥、仅 Oboe 链。
    // 默认关；开了以后 native 用合成 HRIR 做 2×2 卷积，按 前/后/左/右/上 轮播或锁定单方向。
    var hrtfProbeOn by remember { mutableStateOf(MsebCalculator.isHrtfProbeEnabled(context)) }
    // 方向：-1=自动轮播；0=前 1=后 2=左 3=右 4=上（与 OboeDirectPlayer.setHrtfProbeDir 约定一致）
    var hrtfProbeDir by remember { mutableStateOf(MsebCalculator.getHrtfProbeDir(context)) }

    // ── 【真·双耳环绕】HRIR 多虚拟音箱 upmix（替换 DTS）──
    var hrtfSpatialOn by remember { mutableStateOf(MsebCalculator.isHrtfSpatialEnabled(context)) }
    var hrtfFieldYaw by remember { mutableStateOf(MsebCalculator.getHrtfFieldYaw(context)) }
    var hrtfFieldPitch by remember { mutableStateOf(MsebCalculator.getHrtfFieldPitch(context)) }
    // 【房间混响】HRIR 卷积之后叠加虚拟录音棚/音乐厅的早期反射+混响尾音，去干声。
    var hrtfReverbOn by remember { mutableStateOf(MsebCalculator.isHrtfReverbEnabled(context)) }
    // 【距离 / 近场线索】声场距离（米）：近场增强（强 ILD + 低频提升）+ 距离能量衰减。
    var hrtfFieldDistance by remember { mutableStateOf(MsebCalculator.getHrtfFieldDistance(context)) }

    // ── 【V8.172】DTS 虚拟环绕的两个高级参数（与宽度/结像正交）──
    // surround = 环绕包围强度，height = 高度/耳廓感。这两个值决定"两侧变宽"和
    // "真的听得出前后"的差别，是蓝牙耳机上效果明显与否的主旋钮。
    var dtsEx by remember { mutableStateOf(MsebCalculator.getDtsStageEx(context)) }

    // 【V8.172】蓝牙链路：codec 决定环绕的高频线索还剩多少（SBC 基本没有低频以下，
    // 高频被砍到 ~16kHz），native 侧按 codec 收敛高频反射量，UI 上把瓶颈摆出来。
    val btCodec = remember { mutableStateOf(BtCodecTracker.currentCodecName) }
    val btActive = remember { mutableStateOf(BtCodecTracker.isBluetoothOutput) }
    LaunchedEffect(Unit) {
        fun sync() {
            btCodec.value = BtCodecTracker.currentCodecName
            btActive.value = BtCodecTracker.isBluetoothOutput
            // 【V8.172】进入这一屏时把 codec 下发给 native，环绕的高频反射量立刻按链路收敛。
            MusicService.instance?.applyDtsBtProfile(
                BtCodecTracker.currentCodecName.takeIf { BtCodecTracker.isBluetoothOutput }
            )
        }
        // 【V9.16】detect() 在 BtCodecTracker 后台线程异步执行，必须用 onResult 回调在检测
        // 真正完成后才同步 UI——否则 sync() 会在 detect() 还没跑完时读到 stale 的
        // isBluetoothOutput=false，于是蓝牙已连却误显"有线"（旧实现 refresh() 后立刻
        // sync()，竞态必现）。onResult 同样不阻塞当前协程。
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { BtCodecTracker.refreshAsync(context) { sync() } }
        }
        // 开屏后蓝牙路由有时延迟上报，或用户中途才连上 BT；每 1.5s 复探一轮（同样走
        // onResult 回调，检测一完成即刷新），最多 ~6s 内持续收敛到正确状态。
        var tries = 0
        while (tries++ < 4) {
            kotlinx.coroutines.delay(1500)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { BtCodecTracker.refreshAsync(context) { sync() } }
            }
        }
    }

    // ── 【V8.170】新 DSP 模块状态（只在运行时，落盘随预设走 DspSnapshot）──
    // DAC 独占时这些模块不经 Oboe callback，直接给默认值
    val svc = MusicService.instance
    val dacPath = svc?.isDacActive() == true
    var srEnabled by remember { mutableStateOf(false) }
    var srWet by remember { mutableStateOf(0.22f) }
    var srRoom by remember { mutableStateOf(0.55f) }
    var srDamp by remember { mutableStateOf(0.5f) }
    var deEnabled by remember { mutableStateOf(false) }
    var deDepth by remember { mutableStateOf(0.5f) }
    var deThresh by remember { mutableStateOf(0.25f) }
    var mblEnabled by remember { mutableStateOf(true) }
    var autoEqMix by remember { mutableStateOf(1f) }

    // 进入页面时把 native 里仍然生效的值读回来（模块可能在别处被改过）
    LaunchedEffect(Unit) {
        svc?.syncNativeDspState(context)
        // 【V8.650】总开关也必须在进页面时读回：播放页 MSEB 胶囊/三点菜单可改它，
        // 而 enabled 原来只在首次组合时读一次——导航栈保留本屏组合时永远是旧值，
        // 表现为"播放页开了 MSEB，DSP 界面右上角总开关还显示关"。
        enabled = MsebCalculator.isEnabled(context)
        val n = DspSnapshot.captureNative(context)
        n.spaceReverbEnabled?.let { srEnabled = it }
        n.spaceReverbWet?.let { srWet = it }
        n.spaceReverbRoom?.let { srRoom = it }
        n.spaceReverbDamping?.let { srDamp = it }
        n.dynamicEqEnabled?.let { deEnabled = it }
        n.dynamicEqDepth?.let { deDepth = it }
        n.dynamicEqThreshold?.let { deThresh = it }
        n.multiBandLimiterEnabled?.let { mblEnabled = it }
        n.autoEqMix?.let { autoEqMix = it }
        dtsOn = MsebCalculator.isDtsEnabled(context)
        hrtfProbeOn = MsebCalculator.isHrtfProbeEnabled(context)
        hrtfProbeDir = MsebCalculator.getHrtfProbeDir(context)
        hrtfSpatialOn = MsebCalculator.isHrtfSpatialEnabled(context)
        hrtfFieldYaw = MsebCalculator.getHrtfFieldYaw(context)
        hrtfFieldPitch = MsebCalculator.getHrtfFieldPitch(context)
        hrtfReverbOn = MsebCalculator.isHrtfReverbEnabled(context)
        hrtfFieldDistance = MsebCalculator.getHrtfFieldDistance(context)
    }

    // 【V8.171 修同步】前台期间把镜像对齐回数据源：这一屏从回退栈回来时可能还留着
    // 旧的组合状态，而 DTS 可能在播放页胶囊 / 三点菜单里被改过，光靠首次组合读不到。
    // 【V8.650】扩展到 总开关/HRIR/探针：它们同样可在别处被改（播放页 MSEB 胶囊改
    // 总开关、DTS 与 HRIR 互斥时另一方被联动关闭），只对齐 dtsOn 会造成其余开关
    // 显示滞后（用户实测"dts 胶囊打开后 dsp 界面开关还显示关着"）。
    LaunchedEffect(Unit) {
        while (true) {
            if (uiActive) {
                val storedEnabled = MsebCalculator.isEnabled(context)
                if (enabled != storedEnabled) enabled = storedEnabled
                val stored = MsebCalculator.isDtsEnabled(context)
                if (dtsOn != stored) dtsOn = stored
                val storedSpatial = MsebCalculator.isHrtfSpatialEnabled(context)
                if (hrtfSpatialOn != storedSpatial) hrtfSpatialOn = storedSpatial
                val storedProbe = MsebCalculator.isHrtfProbeEnabled(context)
                if (hrtfProbeOn != storedProbe) hrtfProbeOn = storedProbe
            }
            delay(500)
        }
    }

    // 设备自适应建议
    var advice by remember { mutableStateOf<com.sdw.music.player.core.audio.dsp.DeviceDspAdvice?>(null) }
    LaunchedEffect(Unit) {
        advice = runCatching { MsebDeviceAdapt.detect(context) }.getOrNull()
    }

    // FFT band levels — 8 bands from native
    var bandLevels by remember { mutableStateOf(floatArrayOf(0f,0f,0f,0f,0f,0f,0f,0f)) }

    LaunchedEffect(Unit) {
        while (true) {
            if (uiActive) {
                val oboe = MusicService.instance?.oboeDirectPlayer
                if (oboe != null) {
                    runCatching { bandLevels = oboe.getBands8() }
                }
            }
            delay(100)
        }
    }

    LaunchedEffect(Unit) {
        if (enabled && !params.isFlat) applyMseb(context, params)
    }

    // 【V8.3】进入页面时恢复压缩器状态
    LaunchedEffect(Unit) {
        MusicService.instance?.applyCompressor(compEnabled, compThreshold, compRatio, compAttack, compRelease, compMakeup)
    }

    // 【V8.3】进入页面时恢复等响补偿状态
    LaunchedEffect(Unit) {
        MusicService.instance?.applyLoudness(loudEnabled, loudIntensity)
    }

    // 【V8.3】进入页面时恢复 Crossfeed 状态（仅 Oboe）
    LaunchedEffect(Unit) {
        MusicService.instance?.applyCrossfeed(if (xfEnabled) xfAmount else 0f)
    }

    // 【V8.170】多频段限幅默认开（防 EQ 叠加冲顶），其余新模块默认关
    LaunchedEffect(Unit) {
        MusicService.instance?.setMultiBandLimiterEnabled(mblEnabled)
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
        MsebCalculator.setPresetName(context, "")  // 手动调滑块/重置 → 脱离预设
        if (savedName.isNotEmpty()) savedName = ""  // 【V8.184】镜像同步，副标题别再挂着旧名字
        if (enabled) applyMseb(context, newParams)
    }

    // 主开关开启时，把每个独立效果的引擎状态恢复为用户各自保存的偏好（不动这些偏好位本身）。
    // 注：局部函数须先声明后调用（Kotlin 词法作用域），故放在 toggleEnabled 之前。
    fun syncEffectsToMaster() {
        val svc = MusicService.instance ?: return
        if (dtsOn) {
            svc.applyDtsEnabled(true)
            svc.applyMsStage(params.soundstage, params.imaging, dtsEx.surround, dtsEx.height)
        } else svc.applyDtsEnabled(false)
        if (hrtfProbeOn) {
            svc.applyHrtfProbeEnabled(true)
            svc.applyHrtfProbeDir(hrtfProbeDir)
        } else svc.applyHrtfProbeEnabled(false)
        if (hrtfSpatialOn) {
            svc.applyHrtfSpatialEnabled(true)
            svc.applyHrtfFieldOrientation(hrtfFieldYaw, hrtfFieldPitch)
            svc.applyHrtfFieldDistance(hrtfFieldDistance)
            svc.applyHrtfReverbEnabled(hrtfReverbOn)
        } else svc.applyHrtfSpatialEnabled(false)
    }

    // 主开关关闭时，把所有可被它接管的效果统一旁路（仅改引擎状态，保留各自的偏好位以便恢复）。
    fun forceAllEffectsOff() {
        val svc = MusicService.instance ?: return
        svc.applyDtsEnabled(false)
        svc.applyHrtfProbeEnabled(false)
        svc.applyHrtfSpatialEnabled(false)
    }

    // 主开关（右上角）= 真·总开关：关时把 DTS / HRIR(双耳环绕) / 隔离探针 全部统一旁路，
    // 做到名副其实的"直通 / 位完美 A/B"；开时按各自保存的偏好(dtsOn/hrtfSpatialOn/hrtfProbeOn)恢复。
    fun toggleEnabled(on: Boolean) {
        enabled = on
        MsebCalculator.setEnabled(context, on)
        val svcNow = MusicService.instance
        if (on) {
            applyMseb(context, params)
            syncEffectsToMaster()   // 恢复 DTS / HRIR / 探针各自的偏好状态
        } else {
            svcNow?.setDspEqEnabled(false)  // Sync flag
            svcNow?.resetMsebEq()
            svcNow?.resetMsStage()
            svcNow?.resetTransient()
            forceAllEffectsOff()    // 统一旁路 DTS / HRIR / 探针 → 真·直通
        }
    }

    // ── 【V8.170】DTS 同步开关：写同一份 SharedPreferences，与播放页天然同步 ──
    /** 【V8.170-TEST】把当前所有模块开关写进私有文件，供 adb 侧断言（不参与业务逻辑）。 */
    fun dumpDspState(
        context: Context,
        dts: Boolean, sr: Boolean, de: Boolean, mbl: Boolean,
        comp: Boolean, loud: Boolean, xf: Boolean,
        srWet: Float, deDepth: Float, autoEq: Float, xfAmt: Float
    ) {
        val j = org.json.JSONObject()
        j.put("dts", dts)
        j.put("sr", sr);   j.put("srWet", srWet)
        j.put("de", de);   j.put("deDepth", deDepth)
        j.put("mbl", mbl)
        j.put("comp", comp)
        j.put("loud", loud)
        j.put("xf", xf);   j.put("xfAmt", xfAmt)
        j.put("autoEq", autoEq)
        j.put("preset", MsebCalculator.getPresetName(context))
        j.put("ts", System.currentTimeMillis())
        try {
            context.openFileOutput("dsp_state.json", Context.MODE_PRIVATE).use { it.write(j.toString().toByteArray()) }
        } catch (e: Exception) { }
    }

    /** 【V8.172】DTS 专属旋钮：落盘 + 即时下发，不需要走 applyMseb 那整条 EQ 链。 */
    fun updateDtsEx(next: MsebCalculator.DtsStageEx) {
        dtsEx = next
        MsebCalculator.saveDtsStageEx(context, next)
        if (dtsOn) MusicService.instance?.applyMsStage(
            params.soundstage, params.imaging, next.surround, next.height
        )
    }

    /** 【V8.172】DTS 卡片副标题：说清当前到底渲染的是哪一档，避免"开了但不知道开的什么"。 */
    @Composable
    fun dtsSubtitle(): String {
        if (!dtsOn) return stringResource(R.string.mseb_dts_desc)
        val level = when {
            dtsEx.surround >= 7f -> stringResource(R.string.mseb_dts_level_theater)
            dtsEx.surround >= 3f -> stringResource(R.string.mseb_dts_level_immersive)
            else -> stringResource(R.string.mseb_dts_level_wide)
        }
        return if (dtsEx.height >= 6f) "$level ↑" else level
    }

    /** 【第2步·隔离探针】副标题：关时提示是诊断探针，开时显示当前渲染方向。 */
    @Composable
    fun hrtfProbeSubtitle(): String {
        if (!hrtfProbeOn) return stringResource(R.string.mseb_hrtf_probe_desc)
        return when (hrtfProbeDir) {
            -1   -> stringResource(R.string.mseb_hrtf_probe_dir_auto)
            0    -> stringResource(R.string.mseb_hrtf_probe_dir_front)
            1    -> stringResource(R.string.mseb_hrtf_probe_dir_back)
            2    -> stringResource(R.string.mseb_hrtf_probe_dir_left)
            3    -> stringResource(R.string.mseb_hrtf_probe_dir_right)
            4    -> stringResource(R.string.mseb_hrtf_probe_dir_up)
            else -> stringResource(R.string.mseb_hrtf_probe_dir_auto)
        }
    }

    fun toggleDts(on: Boolean) {
        dtsOn = on
        MsebCalculator.setDtsEnabled(context, on)
        // 用户可能刚连上蓝牙，codec 可能还没被刷新过，这里顺带下发一次
        MusicService.instance?.applyDtsBtProfile(
            BtCodecTracker.currentCodecName.takeIf { BtCodecTracker.isBluetoothOutput }
        )
        applyMseb(context, params, dtsEx)   // 重发 M/S，或 resetMsStage
        dumpDspState(context, on, srEnabled, deEnabled, mblEnabled,
            compEnabled, loudEnabled, xfEnabled, srWet, deDepth, autoEqMix, xfAmount)
    }

    // 【第2步·隔离探针】HRTF 双耳渲染探针开关：与 DTS/M-S 互斥，互不影响出声。
    fun toggleHrtfProbe(on: Boolean) {
        hrtfProbeOn = on
        MsebCalculator.setHrtfProbeEnabled(context, on)
        // 探针与 DTS 互斥——开探针时若 DTS 还亮着，先把它关掉，避免两段双耳处理叠加变浑浊。
        if (on && dtsOn) {
            dtsOn = false
            MsebCalculator.setDtsEnabled(context, false)
            MusicService.instance?.applyDtsEnabled(false)
        }
        MusicService.instance?.applyHrtfProbeEnabled(on)
        Log.i("MsebScreen", "HrtfProbe ${if (on) "ON" else "OFF"}")
    }

    // 锁定方向：-1=自动轮播；0=前 1=后 2=左 3=右 4=上
    fun selectHrtfProbeDir(dir: Int) {
        hrtfProbeDir = dir
        MsebCalculator.setHrtfProbeDir(context, dir)
        MusicService.instance?.applyHrtfProbeDir(dir)
    }

    // 【真·双耳环绕】HRIR 多虚拟音箱 upmix 开关：与 DTS/探针互斥——开它时关掉 DTS 与探针，
    // 实现"用真实 HRIR 环绕替换 DTS"。
    fun toggleHrtfSpatial(on: Boolean) {
        hrtfSpatialOn = on
        MsebCalculator.setHrtfSpatialEnabled(context, on)
        if (on && (dtsOn || hrtfProbeOn)) {
            dtsOn = false
            hrtfProbeOn = false
            MsebCalculator.setDtsEnabled(context, false)
            MsebCalculator.setHrtfProbeEnabled(context, false)
            MusicService.instance?.applyDtsEnabled(false)
            MusicService.instance?.applyHrtfProbeEnabled(false)
        }
        MusicService.instance?.applyHrtfSpatialEnabled(on)
        MusicService.instance?.applyHrtfFieldOrientation(hrtfFieldYaw, hrtfFieldPitch)
        Log.i("MsebScreen", "HrtfSpatial ${if (on) "ON" else "OFF"}")
    }

    // 声场朝向（拖动手柄）：yaw=水平旋转(±180°)、pitch=俯仰(±90°)。
    fun setHrtfFieldOrientation(yaw: Float, pitch: Float) {
        hrtfFieldYaw = yaw
        hrtfFieldPitch = pitch
        MsebCalculator.setHrtfFieldYaw(context, yaw)
        MsebCalculator.setHrtfFieldPitch(context, pitch)
        MusicService.instance?.applyHrtfFieldOrientation(yaw, pitch)
    }

    // 【房间混响】HRIR 多虚拟音箱 upmix 的二级开关：仅在"真·双耳环绕"开启时可用，
    // 在 HRIR 卷积之后叠加早期反射+混响尾音，给干声补回空间空气感。
    fun toggleHrtfReverb(on: Boolean) {
        hrtfReverbOn = on
        MsebCalculator.setHrtfReverbEnabled(context, on)
        // 若双耳环绕本身没开，先把主开关打开，保证链路在跑、混响才有意义。
        if (on && !hrtfSpatialOn) {
            toggleHrtfSpatial(true)
        }
        MusicService.instance?.applyHrtfReverbEnabled(on)
        Log.i("MsebScreen", "HrtfReverb ${if (on) "ON" else "OFF"}")
    }

    // 声场距离（米）：近场增强（强 ILD + 低频提升）+ 距离能量衰减。1.5=远场参考，越小越近场。
    fun setHrtfFieldDistance(meters: Float) {
        hrtfFieldDistance = meters
        MsebCalculator.setHrtfFieldDistance(context, meters)
        MusicService.instance?.applyHrtfFieldDistance(meters)
    }

    fun applyCompressorParams(en: Boolean, thr: Float, ratio: Float, atk: Float, rel: Float, makeup: Float) {
        MusicService.instance?.applyCompressor(en, thr, ratio, atk, rel, makeup)
        compThreshold = thr; compRatio = ratio; compAttack = atk; compRelease = rel; compMakeup = makeup
        writeDebouncer.post {
            compPrefs.edit()
                .putBoolean("enabled", en)
                .putFloat("threshold", thr).putFloat("ratio", ratio)
                .putFloat("attack", atk).putFloat("release", rel).putFloat("makeup", makeup)
        }
    }

    fun toggleCompressor(on: Boolean) {
        dumpDspState(context, dtsOn, srEnabled, deEnabled, mblEnabled, on, loudEnabled, xfEnabled, srWet, deDepth, autoEqMix, xfAmount)
        compEnabled = on
        applyCompressorParams(on, compThreshold, compRatio, compAttack, compRelease, compMakeup)
    }

    fun applyPreset(preset: MsebPreset) {
        update(preset.params)
        savedName = preset.name
        MsebCalculator.setPresetName(context, preset.name)
        // 【V8.170】预设带上模块快照：切预设时开关状态一起切，不用手动再拨一遍
        val s = preset.snapshot
        if (s != DspSnapshot()) {
            s.msebEnabled?.let { enabled = it; MsebCalculator.setEnabled(context, it) }
            s.dtsEnabled?.let { dtsOn = it; MsebCalculator.setDtsEnabled(context, it) }
            s.compressorEnabled?.let { compEnabled = it }
            s.compressorThreshold?.let { compThreshold = it }
            s.compressorRatio?.let { compRatio = it }
            s.compressorAttack?.let { compAttack = it }
            s.compressorRelease?.let { compRelease = it }
            s.compressorMakeup?.let { compMakeup = it }
            s.loudnessEnabled?.let { loudEnabled = it }
            s.loudnessIntensity?.let { loudIntensity = it }
            s.crossfeedEnabled?.let { xfEnabled = it }
            s.crossfeedAmount?.let { xfAmount = it }
            s.spaceReverbEnabled?.let { srEnabled = it }
            s.spaceReverbWet?.let { srWet = it }
            s.spaceReverbRoom?.let { srRoom = it }
            s.spaceReverbDamping?.let { srDamp = it }
            s.dynamicEqEnabled?.let { deEnabled = it }
            s.dynamicEqDepth?.let { deDepth = it }
            s.dynamicEqThreshold?.let { deThresh = it }
            s.multiBandLimiterEnabled?.let { mblEnabled = it }
            s.autoEqMix?.let { autoEqMix = it }
            if (enabled) applyMseb(context, params)
            dumpDspState(context, dtsOn, srEnabled, deEnabled, mblEnabled, compEnabled, loudEnabled, xfEnabled, srWet, deDepth, autoEqMix, xfAmount)
            MusicService.instance?.applyCompressor(compEnabled, compThreshold, compRatio, compAttack, compRelease, compMakeup)
            MusicService.instance?.applyLoudness(loudEnabled, loudIntensity)
            MusicService.instance?.applyCrossfeed(if (xfEnabled) xfAmount else 0f)
            MusicService.instance?.setSpaceReverbEnabled(srEnabled)
            MusicService.instance?.setSpaceReverbWet(srWet)
            MusicService.instance?.setSpaceReverbShape(srRoom, srDamp)
            MusicService.instance?.setDynamicEqEnabled(deEnabled)
            MusicService.instance?.setDynamicEqDepth(deDepth)
            MusicService.instance?.setDynamicEqThreshold(deThresh)
            MusicService.instance?.setMultiBandLimiterEnabled(mblEnabled)
            MusicService.instance?.setAutoEqMix(autoEqMix)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_mseb)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.action_back),
                            tint = MaterialTheme.colorScheme.onSurface)
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
            // ── Preset Chips (两行自动换行) ──
            Text(
                stringResource(R.string.mseb_presets),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    val isActive = preset.params == params
                    val isUser = preset.name in MsebPresets.getUserPresets(context).map { it.name }
                    if (isUser) {
                        // 用户自定义预设：带删除按钮（✕），点击 chip 应用，点 ✕ 删除
                        InputChip(
                            selected = isActive,
                            onClick = { applyPreset(preset) },
                            label = {
                                Text(
                                    preset.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.action_delete),
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable {
                                            MsebPresets.delete(context, preset.name)
                                            presets = MsebPresets.getAll(context)
                                        }
                                )
                            },
                            colors = if (isActive) {
                                InputChipDefaults.inputChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            } else InputChipDefaults.inputChipColors()
                        )
                    } else {
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
            }

            // ── A/B Bypass Toggle ──
            // [V8.115] 统一玻璃语言：模块卡从纯色 Card 换成 glassSurface，
            // 启用态借 selected 的 accent 洗底来表达（同主界面「正在播放行」）。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(shape = RoundedCornerShape(16.dp), selected = enabled, fillBoost = 0f)
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
                        // 【V8.184】已命名就只显示这个名字（播放页胶囊同步用它），未命名才退回特征描述。
                        // 特征串太啰嗦（"Loose bass · Thick · Warm · …"），一行根本放不下；
                        // 想少看乱七八糟的描述，点下面那行给它起个自己的名字即可。
                        Text(
                            if (savedName.isNotEmpty()) savedName else MsebCalculator.describe(params),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (savedName.isEmpty()) {
                            Text(
                                stringResource(R.string.mseb_name_tap_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clickable { showNameDialog = true }
                            )
                        }
                    }
                    ExpressiveSwitch(checked = enabled, onCheckedChange = { toggleEnabled(it) })
                }
            }

            // ── 【V8.170】DTS 环绕（与播放页三点菜单 / 控制栏胶囊同步）──
            DspModuleCard(
                title = stringResource(R.string.mseb_dts),
                subtitle = dtsSubtitle(),
                checked = enabled && dtsOn,
                onCheckedChange = { toggleDts(it) },
                accent = MaterialTheme.colorScheme.primary,
                dimmed = !enabled,   // 主开关关闭时整体接管、置灰并旁路
                dimmedNote = stringResource(R.string.mseb_master_bypassed)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 【V8.172】链路状态：环绕效果好不好，一半取决于链路剩下多少带宽。
                    // SBC/AAC 的高频被编码砍掉，环绕线索（扩散、空气、耳廓共振）就没了着落，
                    // native 侧会同步收敛高频反射量，这里把瓶颈直接摆给用户看。
                    DtsLinkRow(
                        bluetooth = btActive.value,
                        codec = btCodec.value
                    )

                    // 宽度/结像的滑块在主维度区（下面），这里只放 DTS 专属的两个旋钮。
                    // 分开放是有意的：这两个值与宽度正交，混在 13 个调音维度里会被当成"又一个 EQ"。
                    MsebSlider(
                        label = stringResource(R.string.mseb_dts_surround),
                        leftLabel = "0",
                        rightLabel = "10",
                        description = stringResource(R.string.mseb_dts_surround_desc),
                        value = dtsEx.surround,
                        enabled = enabled,
                        valueRange = 0f..10f,
                        decimals = 1,
                        onValueChange = { updateDtsEx(dtsEx.copy(surround = it)) }
                    )
                    MsebSlider(
                        label = stringResource(R.string.mseb_dts_height),
                        leftLabel = "0",
                        rightLabel = "10",
                        description = stringResource(R.string.mseb_dts_height_desc),
                        value = dtsEx.height,
                        enabled = enabled,
                        valueRange = 0f..10f,
                        decimals = 1,
                        onValueChange = { updateDtsEx(dtsEx.copy(height = it)) }
                    )
                }
            }

            // ── 【第2步·隔离探针】HRTF 双耳渲染探针（诊断用，与 DTS 互斥、仅 Oboe 链）──
            DspModuleCard(
                title = stringResource(R.string.mseb_hrtf_probe),
                subtitle = hrtfProbeSubtitle(),
                checked = enabled && hrtfProbeOn,
                onCheckedChange = { toggleHrtfProbe(it) },
                accent = MaterialTheme.colorScheme.tertiary,
                dimmed = !enabled,
                dimmedNote = stringResource(R.string.mseb_master_bypassed)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.mseb_hrtf_probe_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 方向锁定：自动轮播 / 前 / 后 / 左 / 右 / 上。
                    // 默认“自动”会每 ~4.5s 推进一个点，方便盲听分辨；锁定后停在单方向做对比。
                    val dirs = listOf(
                        -1 to stringResource(R.string.mseb_hrtf_probe_dir_auto),
                        0  to stringResource(R.string.mseb_hrtf_probe_dir_front),
                        1  to stringResource(R.string.mseb_hrtf_probe_dir_back),
                        2  to stringResource(R.string.mseb_hrtf_probe_dir_left),
                        3  to stringResource(R.string.mseb_hrtf_probe_dir_right),
                        4  to stringResource(R.string.mseb_hrtf_probe_dir_up)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start)
                    ) {
                        dirs.forEach { (dir, label) ->
                            FilterChip(
                                selected = hrtfProbeDir == dir,
                                enabled = enabled,
                                onClick = { selectHrtfProbeDir(dir) },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f)
                                )
                            )
                        }
                    }
                }
            }

            // ── 【真·双耳环绕】HRIR 多虚拟音箱 upmix（替换 DTS，仅 Oboe 链）──
            DspModuleCard(
                title = stringResource(R.string.mseb_hrtf_spatial),
                subtitle = stringResource(R.string.mseb_hrtf_spatial_desc),
                checked = enabled && hrtfSpatialOn,
                onCheckedChange = { toggleHrtfSpatial(it) },
                accent = MaterialTheme.colorScheme.tertiary,
                dimmed = !enabled,
                dimmedNote = stringResource(R.string.mseb_master_bypassed)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.mseb_hrtf_spatial_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 拖动声场：水平=方位旋转(±180°)，垂直=俯仰(±90°)
                    HrtfFieldPanner(
                        yaw = hrtfFieldYaw,
                        pitch = hrtfFieldPitch,
                        onYawPitch = { y, p -> setHrtfFieldOrientation(y, p) },
                        enabled = enabled
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(
                                R.string.mseb_hrtf_field_readout,
                                hrtfFieldYaw.toInt(),
                                hrtfFieldPitch.toInt()
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { setHrtfFieldOrientation(0f, 0f) }) {
                            Text(stringResource(R.string.mseb_hrtf_field_reset))
                        }
                    }
                    // ── 【房间混响】二级开关：给双耳环绕补空间回响（早期反射+混响尾音）──
                    SDWDivider(modifier = Modifier.padding(vertical = 2.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ExpressiveSwitch(
                            checked = hrtfReverbOn,
                            onCheckedChange = { toggleHrtfReverb(it) },
                            enabled = enabled && hrtfSpatialOn,
                            trackOnColor = MaterialTheme.colorScheme.tertiary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.mseb_hrtf_reverb),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                stringResource(R.string.mseb_hrtf_reverb_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // ── 【距离 / 近场线索】声场距离滑块：近场增强 + 距离衰减 ──
                    SDWDivider(modifier = Modifier.padding(vertical = 2.dp))
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                stringResource(R.string.mseb_hrtf_distance),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "%.1f m".format(hrtfFieldDistance),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            stringResource(R.string.mseb_hrtf_distance_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = hrtfFieldDistance,
                            onValueChange = { setHrtfFieldDistance(it) },
                            valueRange = 0.3f..6.0f,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.mseb_hrtf_distance_near), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.mseb_hrtf_distance_far), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // ── 【V8.170】设备自适应校准 ──
            val adviceNow = advice
            if (adviceNow != null && adviceNow.deviceName != "—") {
                // 设备名 / 理由按 type 本地化，避免 native 探测层的中文串直接上屏
                val btLabel = stringResource(R.string.mseb_dev_bluetooth)
                val wiredLabel = stringResource(R.string.mseb_dev_wired)
                val devText = when (adviceNow.type) {
                    "dac" -> adviceNow.deviceName
                    "wired" -> wiredLabel
                    else -> adviceNow.deviceName + " · " + btLabel
                }
                val reasonRes = when (adviceNow.type) {
                    "dac" -> R.string.mseb_dev_dac_reason
                    "sbc" -> R.string.mseb_dev_sbc_reason
                    "aac" -> R.string.mseb_dev_aac_reason
                    "hires" -> R.string.mseb_dev_hires_reason
                    else -> R.string.mseb_dev_unknown_reason
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(shape = RoundedCornerShape(16.dp), selected = false, fillBoost = 0f)
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
                                    stringResource(R.string.mseb_device_auto),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    devText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    stringResource(reasonRes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                val s = adviceNow.suggestion
                                if (s.msebEnabled != null) { enabled = s.msebEnabled!!; MsebCalculator.setEnabled(context, enabled) }
                                // 【V8.171】设备建议改了 DTS 也要落盘，否则上面的对齐回路会在 500ms 后把它打回旧值
                                s.dtsEnabled?.let { dtsOn = it; MsebCalculator.setDtsEnabled(context, it) }
                                if (s.compressorEnabled != null) { compEnabled = s.compressorEnabled!!; applyCompressorParams(compEnabled, s.compressorThreshold ?: compThreshold, s.compressorRatio ?: compRatio, s.compressorAttack ?: compAttack, s.compressorRelease ?: compRelease, s.compressorMakeup ?: compMakeup) }
                                if (s.loudnessEnabled != null) { loudEnabled = s.loudnessEnabled!!; MusicService.instance?.applyLoudness(loudEnabled, loudIntensity); writeDebouncer.post { loudPrefs.edit().putBoolean("enabled", loudEnabled).apply() } }
                                if (s.crossfeedEnabled != null) { xfEnabled = s.crossfeedEnabled!!; MusicService.instance?.applyCrossfeed(if (xfEnabled) xfAmount else 0f); writeDebouncer.post { xfPrefs.edit().putBoolean("enabled", xfEnabled).apply() } }
                                if (s.spaceReverbEnabled != null) { srEnabled = s.spaceReverbEnabled!!; MusicService.instance?.setSpaceReverbEnabled(srEnabled) }
                                if (s.spaceReverbWet != null) { srWet = s.spaceReverbWet!!; MusicService.instance?.setSpaceReverbWet(srWet) }
                                if (s.spaceReverbRoom != null || s.spaceReverbDamping != null) {
                                    srRoom = s.spaceReverbRoom ?: srRoom
                                    srDamp = s.spaceReverbDamping ?: srDamp
                                    MusicService.instance?.setSpaceReverbShape(srRoom, srDamp)
                                }
                                if (s.dynamicEqEnabled != null) { deEnabled = s.dynamicEqEnabled!!; MusicService.instance?.setDynamicEqEnabled(deEnabled) }
                                if (s.dynamicEqDepth != null) { deDepth = s.dynamicEqDepth!!; MusicService.instance?.setDynamicEqDepth(deDepth) }
                                if (s.dynamicEqThreshold != null) { deThresh = s.dynamicEqThreshold!!; MusicService.instance?.setDynamicEqThreshold(deThresh) }
                                if (s.multiBandLimiterEnabled != null) { mblEnabled = s.multiBandLimiterEnabled!!; MusicService.instance?.setMultiBandLimiterEnabled(mblEnabled) }
                                if (s.autoEqMix != null) { autoEqMix = s.autoEqMix!!; MusicService.instance?.setAutoEqMix(autoEqMix) }
                                if (enabled) applyMseb(context, params)
                                dumpDspState(context, dtsOn, srEnabled, deEnabled, mblEnabled, compEnabled, loudEnabled, xfEnabled, srWet, deDepth, autoEqMix, xfAmount)
                            }) {
                                Text(stringResource(R.string.mseb_apply_advice), color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (adviceNow.modulesInactive) {
                            Text(
                                stringResource(R.string.mseb_unavailable),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // ── ── 主维度滑块 ── ──
            val alpha = if (enabled) 1f else 0.45f

            Column(modifier = Modifier.alpha(alpha)) {
                MsebSlider(stringResource(R.string.mseb_slider_temperature), stringResource(R.string.mseb_end_cool), stringResource(R.string.mseb_end_warm), stringResource(R.string.mseb_slider_temperature_desc), params.temperature) { update(params.copy(temperature = it)) }
                MsebSlider(stringResource(R.string.mseb_slider_thickness), stringResource(R.string.mseb_end_thin), stringResource(R.string.mseb_end_thick), stringResource(R.string.mseb_slider_thickness_desc), params.thickness) { update(params.copy(thickness = it)) }
                MsebSlider(stringResource(R.string.mseb_slider_vocal), stringResource(R.string.mseb_end_distant), stringResource(R.string.mseb_end_forward), stringResource(R.string.mseb_slider_vocal_desc), params.vocalForward) { update(params.copy(vocalForward = it)) }
                MsebSlider(stringResource(R.string.mseb_slider_subbass), stringResource(R.string.mseb_end_lean), stringResource(R.string.mseb_end_deep), stringResource(R.string.mseb_slider_subbass_desc), params.subBass) { update(params.copy(subBass = it)) }
                MsebSlider(stringResource(R.string.mseb_slider_basstexture), stringResource(R.string.mseb_end_loose), stringResource(R.string.mseb_end_tight), stringResource(R.string.mseb_slider_basstexture_desc), params.bassTexture) { update(params.copy(bassTexture = it)) }
                MsebSlider(stringResource(R.string.mseb_slider_sibilance), stringResource(R.string.mseb_end_smooth), stringResource(R.string.mseb_end_bright), stringResource(R.string.mseb_slider_sibilance_desc), params.sibilance) { update(params.copy(sibilance = it)) }

                Divider(modifier = Modifier.padding(vertical = 4.dp))

                MsebSlider(stringResource(R.string.mseb_slider_sibilance_lf), stringResource(R.string.mseb_end_soft), stringResource(R.string.mseb_end_crisp), stringResource(R.string.mseb_slider_sibilance_lf_desc), params.sibilanceLf) { update(params.copy(sibilanceLf = it)) }
                MsebSlider(stringResource(R.string.mseb_slider_sibilance_hf), stringResource(R.string.mseb_end_soft), stringResource(R.string.mseb_end_crisp), stringResource(R.string.mseb_slider_sibilance_hf_desc), params.sibilanceHf) { update(params.copy(sibilanceHf = it)) }
                MsebSlider(stringResource(R.string.mseb_slider_female_overtones), stringResource(R.string.mseb_end_dry), stringResource(R.string.mseb_end_sweet), stringResource(R.string.mseb_slider_female_overtones_desc), params.femaleOvertones) { update(params.copy(femaleOvertones = it)) }
                MsebSlider(stringResource(R.string.mseb_slider_air), stringResource(R.string.mseb_end_dark), stringResource(R.string.mseb_end_airy), stringResource(R.string.mseb_slider_air_desc), params.air) { update(params.copy(air = it)) }

                MsebSlider(stringResource(R.string.mseb_slider_transient), stringResource(R.string.mseb_end_soft), stringResource(R.string.mseb_end_fast), stringResource(R.string.mseb_slider_transient_desc), params.impulseResponse) { update(params.copy(impulseResponse = it)) }

                Divider(modifier = Modifier.padding(vertical = 4.dp))

                MsebSlider(stringResource(R.string.mseb_slider_soundstage), stringResource(R.string.mseb_end_narrow), stringResource(R.string.mseb_end_wide), stringResource(R.string.mseb_slider_soundstage_desc), params.soundstage) { update(params.copy(soundstage = it)) }
                MsebSlider(stringResource(R.string.mseb_slider_imaging), stringResource(R.string.mseb_end_diffuse), stringResource(R.string.mseb_end_focused), stringResource(R.string.mseb_slider_imaging_desc), params.imaging) { update(params.copy(imaging = it)) }
            }

            // ── 【V8.170】动态压缩（独立全局模块）──
            DspModuleCard(
                title = stringResource(R.string.mseb_compressor),
                subtitle = stringResource(R.string.mseb_compressor_desc),
                checked = compEnabled,
                onCheckedChange = { toggleCompressor(it) },
                accent = MaterialTheme.colorScheme.tertiary
            ) {
                val compAlpha = if (compEnabled) 1f else 0.45f
                Column(modifier = Modifier.alpha(compAlpha)) {
                    MsebSlider(
                        stringResource(R.string.mseb_compressor_threshold), "-60 dB", "0 dB",
                        stringResource(R.string.mseb_compressor_threshold), compThreshold,
                        -60f..0f, 1
                    ) { v -> applyCompressorParams(compEnabled, v, compRatio, compAttack, compRelease, compMakeup) }
                    MsebSlider(
                        stringResource(R.string.mseb_compressor_ratio), "1:1", "10:1",
                        stringResource(R.string.mseb_compressor_ratio), compRatio,
                        1f..10f, 1
                    ) { v -> applyCompressorParams(compEnabled, compThreshold, v, compAttack, compRelease, compMakeup) }
                    MsebSlider(
                        stringResource(R.string.mseb_compressor_attack), "1 ms", "100 ms",
                        stringResource(R.string.mseb_compressor_attack), compAttack,
                        1f..100f, 0
                    ) { v -> applyCompressorParams(compEnabled, compThreshold, compRatio, v, compRelease, compMakeup) }
                    MsebSlider(
                        stringResource(R.string.mseb_compressor_release), "20 ms", "500 ms",
                        stringResource(R.string.mseb_compressor_release), compRelease,
                        20f..500f, 0
                    ) { v -> applyCompressorParams(compEnabled, compThreshold, compRatio, compAttack, v, compMakeup) }
                    MsebSlider(
                        stringResource(R.string.mseb_compressor_makeup), "0 dB", "12 dB",
                        stringResource(R.string.mseb_compressor_makeup), compMakeup,
                        0f..12f, 1
                    ) { v -> applyCompressorParams(compEnabled, compThreshold, compRatio, compAttack, compRelease, v) }
                }
            }

            // ── 【V8.170】空间混响 ──
            DspModuleCard(
                title = stringResource(R.string.mseb_space_reverb),
                subtitle = stringResource(R.string.mseb_space_reverb_desc),
                checked = srEnabled,
                onCheckedChange = { srEnabled = it; MusicService.instance?.setSpaceReverbEnabled(it); dumpDspState(context, dtsOn, srEnabled, deEnabled, mblEnabled, compEnabled, loudEnabled, xfEnabled, srWet, deDepth, autoEqMix, xfAmount) },
                accent = MaterialTheme.colorScheme.secondary,
                dimmed = dacPath,
                dimmedNote = stringResource(R.string.mseb_unavailable)
            ) {
                Column(modifier = Modifier.alpha(if (srEnabled) 1f else 0.45f)) {
                    MsebSlider(
                        stringResource(R.string.mseb_space_reverb_wet),
                        stringResource(R.string.mseb_end_none), stringResource(R.string.mseb_end_full),
                        stringResource(R.string.mseb_space_reverb_wet), srWet, 0f..1f, 2
                    ) { v -> srWet = v; MusicService.instance?.setSpaceReverbWet(v) }
                    MsebSlider(
                        stringResource(R.string.mseb_space_reverb_room),
                        stringResource(R.string.mseb_end_small), stringResource(R.string.mseb_end_large),
                        stringResource(R.string.mseb_space_reverb_room), srRoom, 0f..1f, 2
                    ) { v -> srRoom = v; MusicService.instance?.setSpaceReverbShape(srRoom, srDamp) }
                    MsebSlider(
                        stringResource(R.string.mseb_space_reverb_damping),
                        stringResource(R.string.mseb_end_lively), stringResource(R.string.mseb_end_dull),
                        stringResource(R.string.mseb_space_reverb_damping), srDamp, 0f..1f, 2
                    ) { v -> srDamp = v; MusicService.instance?.setSpaceReverbShape(srRoom, srDamp) }
                }
            }

            // ── 【V8.170】动态 EQ ──
            DspModuleCard(
                title = stringResource(R.string.mseb_dynamic_eq),
                subtitle = stringResource(R.string.mseb_dynamic_eq_desc),
                checked = deEnabled,
                onCheckedChange = { deEnabled = it; MusicService.instance?.setDynamicEqEnabled(it); dumpDspState(context, dtsOn, srEnabled, deEnabled, mblEnabled, compEnabled, loudEnabled, xfEnabled, srWet, deDepth, autoEqMix, xfAmount) },
                accent = MaterialTheme.colorScheme.secondary,
                dimmed = dacPath,
                dimmedNote = stringResource(R.string.mseb_unavailable)
            ) {
                Column(modifier = Modifier.alpha(if (deEnabled) 1f else 0.45f)) {
                    MsebSlider(
                        stringResource(R.string.mseb_dynamic_eq_threshold), "0%", "100%",
                        stringResource(R.string.mseb_dynamic_eq_threshold), deThresh, 0f..1f, 2
                    ) { v -> deThresh = v; MusicService.instance?.setDynamicEqThreshold(v) }
                    MsebSlider(
                        stringResource(R.string.mseb_dynamic_eq_depth),
                        stringResource(R.string.mseb_end_light), stringResource(R.string.mseb_end_heavy),
                        stringResource(R.string.mseb_dynamic_eq_depth), deDepth, 0f..1f, 2
                    ) { v -> deDepth = v; MusicService.instance?.setDynamicEqDepth(v) }
                }
            }

            // ── 【V8.170】AutoEQ 修正强度 ──
            DspModuleCard(
                title = stringResource(R.string.mseb_autoeq_mix),
                subtitle = stringResource(R.string.mseb_autoeq_mix_desc),
                checked = autoEqMix > 0.99f,
                onCheckedChange = { autoEqMix = if (it) 1f else 0.5f; MusicService.instance?.setAutoEqMix(autoEqMix) },
                accent = MaterialTheme.colorScheme.tertiary,
                dimmed = false,   // 【V9.01】USB DAC 引擎已支持 AutoEQ（setAutoEq10Band + g_autoEqEnabled），解除 DAC 模式锁定
                dimmedNote = stringResource(R.string.mseb_unavailable)
            ) {
                val a = if (autoEqMix > 0.02f) 1f else 0.45f
                Column(modifier = Modifier.alpha(a)) {
                    MsebSlider(
                        stringResource(R.string.mseb_autoeq_mix),
                        stringResource(R.string.mseb_end_neutral), stringResource(R.string.mseb_end_fullfix),
                        stringResource(R.string.mseb_autoeq_mix_desc), autoEqMix, 0f..1f, 2
                    ) { v -> autoEqMix = v; MusicService.instance?.setAutoEqMix(v) }
                }
            }

            // ── 【V8.170】多频段限幅 ──
            DspModuleCard(
                title = stringResource(R.string.mseb_mb_limiter),
                subtitle = stringResource(R.string.mseb_mb_limiter_desc),
                checked = mblEnabled,
                onCheckedChange = { mblEnabled = it; MusicService.instance?.setMultiBandLimiterEnabled(it); dumpDspState(context, dtsOn, srEnabled, deEnabled, mblEnabled, compEnabled, loudEnabled, xfEnabled, srWet, deDepth, autoEqMix, xfAmount) },
                accent = MaterialTheme.colorScheme.error,
                dimmed = dacPath,
                dimmedNote = stringResource(R.string.mseb_unavailable)
            ) {}

            // ── 等响补偿（独立全局模块）──
            DspModuleCard(
                title = stringResource(R.string.mseb_loudness),
                subtitle = stringResource(R.string.mseb_loudness_desc),
                checked = loudEnabled,
                onCheckedChange = { on ->
                    loudEnabled = on
                    MusicService.instance?.applyLoudness(on, loudIntensity)
                    writeDebouncer.post { loudPrefs.edit().putBoolean("enabled", on).apply() }
                },
                accent = MaterialTheme.colorScheme.tertiary
            ) {
                Column(modifier = Modifier.alpha(if (loudEnabled) 1f else 0.45f)) {
                    MsebSlider(
                        stringResource(R.string.mseb_loudness_intensity), "0%", "100%",
                        stringResource(R.string.mseb_loudness_intensity), loudIntensity,
                        0f..2f, 2
                    ) { v ->
                        loudIntensity = v
                        MusicService.instance?.applyLoudness(loudEnabled, v)
                        writeDebouncer.post { loudPrefs.edit().putFloat("intensity", v).apply() }
                    }
                }
            }

            // ── Crossfeed（消除头中效应，仅 Oboe 路径）──
            DspModuleCard(
                title = stringResource(R.string.mseb_crossfeed),
                subtitle = stringResource(R.string.mseb_crossfeed_desc),
                checked = xfEnabled,
                onCheckedChange = { on ->
                    xfEnabled = on
                    MusicService.instance?.applyCrossfeed(if (on) xfAmount else 0f)
                    writeDebouncer.post { xfPrefs.edit().putBoolean("enabled", on).apply() }
                },
                accent = MaterialTheme.colorScheme.secondary,
                dimmed = dacPath,
                dimmedNote = stringResource(R.string.mseb_unavailable)
            ) {
                Column(modifier = Modifier.alpha(if (xfEnabled) 1f else 0.45f)) {
                    MsebSlider(
                        stringResource(R.string.mseb_crossfeed_amount), "0%", "100%",
                        stringResource(R.string.mseb_crossfeed_amount), xfAmount,
                        0f..1f, 2
                    ) { v ->
                        xfAmount = v
                        MusicService.instance?.applyCrossfeed(if (xfEnabled) v else 0f)
                        writeDebouncer.post { xfPrefs.edit().putFloat("amount", v).apply() }
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

    // ── 【V8.184】给当前音色命名（只记名字，不存参数）──
    if (showNameDialog) {
        // 草稿直接以现有名字起底：改名时看得到旧名字，未命名时是空串
        var draft by remember { mutableStateOf(savedName) }
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text(stringResource(R.string.mseb_name_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text(stringResource(R.string.mseb_preset_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.mseb_name_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = draft.trim()
                    if (n.isNotEmpty()) {
                        MsebCalculator.setPresetName(context, n)
                        savedName = n
                        showNameDialog = false
                    }
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    // ── Save Preset Dialog ──
    if (showSaveDialog) {
        var nameText by remember { mutableStateOf(savedName) }
        var saveError by remember { mutableStateOf<String?>(null) }
        val limitMsg = stringResource(R.string.mseb_preset_limit)
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(R.string.mseb_save_preset)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = nameText,
                        onValueChange = { nameText = it; saveError = null },
                        label = { Text(stringResource(R.string.mseb_preset_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (saveError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            saveError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.mseb_preset_limit),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = nameText.trim()
                    if (name.isNotEmpty()) {
                        // 【V8.170】把当前所有模块开关一起存进预设
                        val snap = DspSnapshot.capture(context).copy(
                            spaceReverbEnabled = srEnabled,
                            spaceReverbWet = srWet,
                            spaceReverbRoom = srRoom,
                            spaceReverbDamping = srDamp,
                            dynamicEqEnabled = deEnabled,
                            dynamicEqDepth = deDepth,
                            dynamicEqThreshold = deThresh,
                            multiBandLimiterEnabled = mblEnabled,
                            autoEqMix = autoEqMix
                        )
                        val ok = MsebPresets.save(context, name, params, snap)
                        if (ok) {
                            savedName = name
                            MsebCalculator.setPresetName(context, name)
                            presets = MsebPresets.getAll(context)
                            showSaveDialog = false
                        } else {
                            saveError = limitMsg
                        }
                    }
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

/**
 * 【V8.172】DTS 链路状态条。
 *
 * 环绕效果好不好，一半不取决于算法，而取决于链路还剩多少带宽：
 * SBC 把高频砍到 ~16kHz 还带噪底，环绕线索（扩散、空气、耳廓共振）全无着落；
 * AAC 稍好；LDAC / LHDC / aptX Adaptive 才接近透明。native 侧会按 codec 收敛高频
 * 反射量，这里再用三格带宽指示把瓶颈摆出来 —— 用户一眼能看出"效果一般"是不是
 * 被蓝牙自己卡住了，而不是参数没调对。
 */
@Composable
private fun DtsLinkRow(bluetooth: Boolean, codec: String, modifier: Modifier = Modifier) {
    val raw = codec.trim()
    val name = when {
        !bluetooth -> stringResource(R.string.mseb_dts_link_wired)
        raw.isEmpty() || raw == "—" -> stringResource(R.string.mseb_dts_link_bt)
        else -> raw
    }
    // 三格 = 高频线索还剩多少：SBC 1 格、AAC 2 格、高码率 3 格、有线 3 格。
    val bars = when {
        !bluetooth -> 3
        raw.contains("LDAC", true) || raw.contains("LHDC", true) -> 3
        raw.contains("aptX", true) -> 3
        raw.contains("AAC", true) -> 2
        raw.contains("SBC", true) -> 1
        // 【V8.174】读不到编码（多半是缺权限）时不假装有数，按"未知"算中间档
        else -> 2
    }
    // 少一格就把瓶颈写在脸上：环绕的高频线索就是被链路砍掉的，不是参数没调对。
    val hint = when (bars) {
        3 -> null
        else -> stringResource(R.string.mseb_dts_link_hint)
    }
    val tone = when (bars) {
        3 -> MaterialTheme.colorScheme.primary
        2 -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            color = tone,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(3) { i ->
                Box(
                    modifier = Modifier
                        .size(width = 14.dp, height = 6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (i < bars) tone else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f))
                )
            }
        }
    }
    if (hint != null) {
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

// ── 通用 DSP 模块卡（开关 + 可选参数区）──

@Composable
private fun DspModuleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accent: Color,
    dimmed: Boolean = false,
    dimmedNote: String? = null,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape = RoundedCornerShape(16.dp), selected = checked && !dimmed, fillBoost = 0f)
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
                // 【V8.171】dimmed（DAC 独占）只锁操作，不再把开关强改成「关」。
                // 以前写的是 checked && !dimmed，USB DAC 插着时哪怕 DTS 真的开着也显示关，
                // 看上去就是「状态没同步」。现在照实显示真实状态，靠 enabled 锁住，
                // 再靠下面那行红色说明解释为什么点不动。
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (checked && !dimmed) accent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ExpressiveSwitch(
                    checked = checked,
                    enabled = !dimmed,
                    onCheckedChange = onCheckedChange
                )
            }
            if (dimmed && dimmedNote != null) {
                Text(
                    dimmedNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            content()
        }
    }
}

// ── 写盘防抖：Slider 拖动时合并成一次落盘 ──

private class DebouncedWriter(private val delayMs: Long) {
    private val timer = Timer(true)
    private var task: TimerTask? = null

    fun post(block: () -> Unit) {
        task?.cancel()
        task = object : TimerTask() {
            override fun run() {
                try { block() } catch (_: Throwable) {}
            }
        }
        timer.schedule(task, delayMs)
    }

    fun cancel() {
        task?.cancel()
        task = null
    }
}

// ── FFT Bars ──

// 【V8.3】开发者调试视图：实时显示 10 段实际增益值（MSEB 映射表输出）。
// 展示每个主观滑块如何被映射到 10 个频点，直观看到映射表在跑。
@Composable
private fun MsebBandGainsDebug(gains: FloatArray) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape = RoundedCornerShape(16.dp), fillBoost = 0f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.mseb_band_gains),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            // 增益条：以 0 dB 为中心，正增益向上、负增益向下（-6..+6 dB）
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (i in gains.indices) {
                    val g = gains[i].coerceIn(-6f, 6f)
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 【V8.170-BUGFIX】上/下半区对称：正增益从基线向上长，负增益向下长。
                        // 原实现 3 个 Box 挤在 Center 排列里，柱子全在基线下方。
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            if (g >= 0f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.6f)
                                        .height((kotlin.math.abs(g) / 6f * 24f).dp)
                                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                                )
                            }
                        }
                        // 0 dB 基线
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        )
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            if (g < 0f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.6f)
                                        .height((kotlin.math.abs(g) / 6f * 24f).dp)
                                        .background(
                                            MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                            RoundedCornerShape(2.dp)
                                        )
                                )
                            }
                        }
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape = RoundedCornerShape(16.dp), fillBoost = 0f)
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
            // Static frequency labels — 本地化，零 recomposition
            val labels = listOf("60", "120", "250", "500", "2k", "6k", "12k", "20k")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                labels.forEach { lb ->
                    Text(
                        stringResource(R.string.mseb_spectrum_hz, lb),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                }
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
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit
) {
    MsebSlider(label, leftLabel, rightLabel, description, value, -10f..10f, 1, enabled, onValueChange)
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
    enabled: Boolean = true,
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
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, enabled = enabled, modifier = Modifier.fillMaxWidth())
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(leftLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(rightLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── DSP ──

private fun applyMseb(
    context: Context,
    params: MsebParams,
    // 【V8.172】DTS 高级参数。默认取"标准档"，保证别处（预设/建议）调用时行为不变。
    dtsEx: MsebCalculator.DtsStageEx = MsebCalculator.DtsStageEx()
) {
    val svc = MusicService.instance ?: return
    svc.applyMsebEq(
        MsebCalculator.calculateGains(params),
        MsebCalculator.BAND_FREQS,
        MsebCalculator.BAND_QS
    )
    // 【V8.3】M/S 声场（跨声道矩阵）独立应用 — V8.19 受 DTS 快捷开关门控：
    // 播放页 DTS 开关关闭时，这里不应用声场（避免开 MSEB 总开关强开环绕）
    if (MsebCalculator.isDtsEnabled(context)) {
        svc.applyMsStage(params.soundstage, params.imaging, dtsEx.surround, dtsEx.height)
    } else {
        svc.resetMsStage()
    }
    // 【V8.3】瞬态整形（时域，impulseResponse 维度映射，-1..+1）
    svc.applyTransient(params.impulseResponse / 10f)
}
