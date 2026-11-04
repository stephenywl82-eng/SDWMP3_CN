package com.sdw.music.player.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import com.sdw.music.player.R
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.sdw.music.player.Song
import com.sdw.music.player.SongRepository
import com.sdw.music.player.ui.components.DefaultCoverImage
import com.sdw.music.player.ui.components.SDWTopBar
import com.sdw.music.player.ui.components.rememberSharedCoverPainter
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.cos

/**
 * 独立「封面流」浏览页：把专辑排成经典 Cover Flow（iPad 音乐 App 那种 3D CD 盒轮播）。
 *
 * 纯 Compose 实现，不引入额外依赖：每个专辑是一个有厚度的 CD 盒——正面封面 + 左右两条
 * 侧面书脊（书脊上竖印专辑名、带圆柱状明暗渐变），无论盒子往哪边转都能看到朝向观众的那条
 * 书脊，立体感明显。父容器按「距中心步数」用 graphicsLayer 的 rotationY / cameraDistance /
 * scale / alpha 做 3D 透视：中心几乎正对、越往两边越转向、后退、缩小、变暗，并轻微上抬，
 * 形成「走廊式」纵深。背景取当前居中专辑封面放大重模糊 + 暗色遮罩，随滑动实时变色。
 *
 * 布局：竖屏（及一切窄高屏）重心偏中间、上下留白大，所以本页采用「三段式」——
 *   顶部：当前居中专辑详情（缩略图 + 专辑名 + 歌手 + 曲目数）
 *   中部：封面流轮播（占满剩余空间）
 *   底部：迷你播放器（当前歌曲 + 上/播放暂停/下一首 + 细进度条，点按进播放页）
 *
 * 数据口径与 AlbumListScreen 完全一致：按专辑分组，取每专辑首曲 albumArtUri 作代表封面，
 * 复用 rememberSharedCoverPainter(big=true) 以共享播放器封面的内存缓存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverFlowScreen(
    songs: List<Song>,
    onNavigateBack: () -> Unit,
    onAlbumClick: (String) -> Unit,
    // —— 顶部专辑详情区的「播放整张专辑」按钮 ——
    onPlayAlbum: (List<Song>) -> Unit = {},
    // —— 迷你播放器所需的播放状态（由导航层从 vm / state 注入）——
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    positionMs: State<Long> = mutableStateOf(0L),
    durationMs: State<Long> = mutableStateOf(0L),
    onPlayPause: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onNavigateToPlayer: () -> Unit = {},
    // —— 封面流滚动位置：由导航根保活，离开/返回不丢位置 ——
    pos: MutableState<Float> = remember { mutableStateOf(0f) }
) {
    val albums = remember(songs) {
        songs
            .filter { it.album.isNotBlank() && !SongRepository.isPlaceholderAlbum(it.album) }
            .groupBy { it.album }
            .toList()
            .sortedBy { it.first }
    }
    // 搜索：按专辑名或歌手过滤（封面流内实时筛选，不离开本页）
    var query by remember { mutableStateOf("") }
    val displayAlbums = remember(albums, query) {
        if (query.isBlank()) albums
        else albums.filter { (name, grp) ->
            name.contains(query, ignoreCase = true) ||
                grp.any { it.artist.contains(query, ignoreCase = true) }
        }
    }

    Scaffold(
        topBar = {
            SDWTopBar(
                title = { Text(stringResource(R.string.cover_flow), color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onNavigateBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        },
        containerColor = Color.Black
    ) { padding ->
        if (albums.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_albums_to_display), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val density = LocalDensity.current
            val cfContext = LocalContext.current
            // 调试开关：adb shell settings put global sdw_cf_tilt 1 后整体错半步，方便截图确认 3D（正常用户无感）
            val dbgTilt = remember {
                try { android.provider.Settings.Global.getInt(cfContext.contentResolver, "sdw_cf_tilt", 0) == 1 } catch (_: Exception) { false }
            }
            val dbgShift = if (dbgTilt) 0.5f else 0f
            // —— 观感参数（按可用空间自适应，方便后续微调）——
            val shape = RoundedCornerShape(6.dp)
            // 封面尺寸随中部可用区域自适应：高取约 38%（明显缩小，避免过大），宽接近方形
            val coverH = (maxHeight * 0.40f).coerceIn(140.dp, 220.dp)
            val coverW = coverH * 0.94f
            val spacing = coverW * 0.85f          // 相邻封面中心间距：拉开避免中心大封面与侧封交叠，并留出走廊纵深
            val thicknessDp = (coverW * 0.11f).coerceIn(12.dp, 22.dp)  // CD 盒厚度：加厚让立体转角更明显
            // 侧封旋转角度：每跨一步转 STEP_ANGLE，最多 MAX_ANGLE；右封取正角露左书脊、左封对称，形成经典 Cover Flow 走廊
            val STEP_ANGLE = 70f
            val MAX_ANGLE = 78f
            val coverWpx = with(density) { coverW.toPx() }
            val coverHpx = with(density) { coverH.toPx() }
            val spacingPx = with(density) { spacing.toPx() }
            // 相机距离必须 > 侧封绕边翻转后的最大纵深(≈封面宽×sin(角度)≈0.82W)，否则远端越过相机
            // 平面会投影错乱、看起来像一块平板。取约 1.5 倍封面宽：透视明显（侧封是清晰平行四边形），
            // 又不会因过近而把侧封压成细条或越界。
            val camPx = (coverWpx * 1.5f).coerceAtLeast(400f)
            // 只渲染距中心 WINDOW 步以内的封面，离屏远的跳过 —— 大幅减少每帧重算的 graphicsLayer 数，帧率更稳。
            // 取 5 步：屏上约可见 5~7 张，更远的根本看不见；配合远端淡出，既不露破绽又省算力。
            val WINDOW = 5f

            val last = (displayAlbums.size - 1).toFloat().let { if (it < 0f) 0f else it }
            // 搜索过滤后列表变短，把已保活的滚动位置重映射到新范围，避免越界或定位错位
            LaunchedEffect(query) {
                if (displayAlbums.isNotEmpty()) {
                    pos.value = pos.value.coerceIn(0f, (displayAlbums.size - 1).toFloat())
                }
            }
            val scope = rememberCoroutineScope()
            val flingJob = remember { mutableStateOf<Job?>(null) }

            // 背景：跟随「居中专辑」的封面取色 —— 放大重模糊 + 暗色遮罩，随滑动实时变色。
            // 用 derivedStateOf：只有「居中专辑」整数索引变化时（每跨过一张专辑一次）才重算重模糊，
            // 滑动过程中不每帧重 compose 整页（这是滑动卡顿的主因之一）。
            val centeredIndex by remember(displayAlbums) {
                derivedStateOf {
                    if (displayAlbums.isEmpty()) 0 else pos.value.roundToInt().coerceIn(0, displayAlbums.lastIndex)
                }
            }
            val bgCoverUri = displayAlbums.getOrNull(centeredIndex)?.second?.firstOrNull()?.albumArtUri.orEmpty()
            val selected = displayAlbums.getOrNull(centeredIndex)

            Box(Modifier.fillMaxSize()) {
                // 背景层：近黑沉浸式（参考 iPad 封面流的极简暗底）。仅用极低透明度的居中封面做色彩点缀，
                // 整体压到接近纯黑以突出彩色封面。big=false 用小图解码 + 60dp 模糊足以抹平、省算力、不露像素。
                if (bgCoverUri.isNotBlank()) {
                    Image(
                        painter = rememberSharedCoverPainter(bgCoverUri, big = false),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().alpha(0.5f).blur(42.dp),
                        contentScale = ContentScale.Crop
                    )
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.74f),
                            0.5f to Color.Black.copy(alpha = 0.86f),
                            1f to Color.Black.copy(alpha = 0.97f)
                        )
                    )
                )
                // 内容层（三段式）
                Column(Modifier.fillMaxSize()) {
                    // —— 搜索栏（M3 Expressive：大圆角 + 色调容器 + 搜索/清除按钮）——
                    CoverFlowSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    if (displayAlbums.isEmpty()) {
                        // 搜索无匹配：给出空提示，不渲染封面流与详情
                        Box(
                            Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.cover_flow_no_results),
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 15.sp
                            )
                        }
                    } else {
                    // —— 顶部：当前居中专辑详情 ——
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val selCover = selected?.second?.firstOrNull()?.albumArtUri.orEmpty()
                        val selShape = RoundedCornerShape(20.dp)
                        Box(
                            Modifier
                                .size(64.dp)
                                .clip(selShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            DefaultCoverImage(
                                songTitle = selected?.first ?: "",
                                songArtist = "",
                                modifier = Modifier.fillMaxSize(),
                                shape = selShape
                            )
                            if (SongRepository.hasRealCover(selCover)) {
                                Image(
                                    painter = rememberSharedCoverPainter(selCover, big = false),
                                    contentDescription = selected?.first,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = selected?.first ?: "—",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = selected?.second?.firstOrNull()?.artist ?: "",
                                color = Color.White.copy(alpha = 0.82f),
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.album_track_count, selected?.second?.size ?: 0),
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        // 专辑「播放」按钮：直接播放整张居中专辑，而非跳转到专辑页
                        val albumSongs = selected?.second ?: emptyList()
                        FilledTonalIconButton(
                            onClick = { if (albumSongs.isNotEmpty()) onPlayAlbum(albumSongs) },
                            enabled = albumSongs.isNotEmpty(),
                            modifier = Modifier.size(56.dp),
                            shapes = IconButtonDefaults.shapes()
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                stringResource(R.string.play_this_album),
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    // —— 中部：封面流轮播 ——
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .pointerInput(displayAlbums) {
                                // 速度估算（索引单位/毫秒）：在拖拽回调里累计，松手后用于惯性甩动
                                var vEst = 0f
                                var lastT = 0L
                                var lastPos = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        flingJob.value?.cancel()
                                        lastT = System.currentTimeMillis()
                                        lastPos = pos.value
                                        vEst = 0f
                                    },
                                    onDragEnd = {
                                        // 惯性 + 吸附：松手后按甩动速度投影到目标专辑（滑得越快飞越远），再用弹簧平滑滑到该整数位。
                                        // 关键修复「停下后中间封面往左跳一下」：弹簧【不】传 initialVelocity。
                                        //   之前把松手速度既用来投影目标、又作为弹簧初速度，等于重复计入动量；过阻尼弹簧在大初速下仍会
                                        //   先越过目标再回弹，表现为「停后往回/往左抖一下」。去掉初速度后弹簧从静止平滑滑到目标，零过冲。
                                        // 弹簧用临界阻尼(dampingRatio=1f)+中等刚度，落点干脆又不生硬，滑动整体更顺。
                                        val vPerSec = (vEst * 1000f).coerceIn(-30f, 30f)
                                        flingJob.value = scope.launch {
                                            val a = Animatable(pos.value)
                                            val target = (a.value + vPerSec * 0.20f)
                                                .roundToInt().coerceIn(0, last.toInt()).toFloat()
                                            a.animateTo(
                                                target,
                                                spring(stiffness = 200f, dampingRatio = 1f)
                                            ) {
                                                pos.value = value.coerceIn(0f, last)
                                            }
                                        }
                                    }
                                ) { _, dragAmount ->
                                    val now = System.currentTimeMillis()
                                    val dt = (now - lastT).coerceAtLeast(1L)
                                    // 跟手拖拽：手指向右(拖回上一张) → pos 减小 → 封面右移露出左侧；向右滑即「回上一张」(经典 Cover Flow)。
                                    // 之前是 pos += dragAmount，向右拖反而前进到下一张，方向反了。翻成减号即跟手。
                                    // vEst 由 np 推导，符号随之翻转，松手惯性方向自动保持与拖拽一致。
                                    val np = (pos.value - dragAmount / spacingPx).coerceIn(0f, last)
                                    val dv = (np - lastPos) / dt
                                    vEst = vEst * 0.65f + dv * 0.35f   // 轻度平滑，去掉抖动又保留甩动手感
                                    lastT = now
                                    lastPos = np
                                    pos.value = np
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                // 只组合距中心 ±WINDOW 步内的封面。窗口外的封面在 graphicsLayer 里 alpha 恒为 0、永不可见，
                // 组合它们纯属浪费；大曲库（上千专辑）下一次性组合全部封面会让主线程长时间阻塞、掉数百帧，
                // 表现为「封面临时卡死」。用 derivedStateOf 的居中整数索引（仅在跨过专辑时变化）圈定可见窗口，
                // 进入/退出窗口的封面在边界处 alpha 已为 0，不会闪现。
                val wSteps = WINDOW.toInt()
                val firstIdx = (centeredIndex - wSteps).coerceAtLeast(0)
                val lastIdx = (centeredIndex + wSteps).coerceAtMost(displayAlbums.lastIndex)
                for (i in firstIdx..lastIdx) {
                    val (name, grp) = displayAlbums[i]
                    val coverUri = grp.firstOrNull()?.albumArtUri.orEmpty()
                    // offset>0 表示封面在中心右侧。这个布尔只在「跨过中心」时翻转，用 derivedStateOf 低频重组，
                    // 不读 pos 的逐帧值，封面图/书脊只构建一遍；高频的 3D 变换都在 graphicsLayer 的绘制期 lambda 内读 pos。
                    // 注意：必须带 i 作为 remember 的 key！for 循环体内按组合位置复用状态，
                    // 窗口滑动后位置不变但 i 变了，若不带 i 会复用 stale 状态 → zRank/onRightNow 全错位（右侧遮挡、书脊乱跑的根因）。
                    val onRightNow by remember(i) { derivedStateOf { (i - pos.value) > 0f } }
                    // 层级(z 顺序)：越靠近中心 z 越高、两侧递减。用「距中心连续距离」量化成离散档位(roundToInt)，
                    // 经 derivedStateOf 只在跨半步边界时低频重组，避免每帧重组；且不再有"无封面精确居中→全 0 退化"空档，
                    // 因此中间封面永远压在两侧之上（两侧朝中心转、应在其后），不会再被右侧盖住。
                    val zRank by remember(i) { derivedStateOf { (WINDOW - abs(i - pos.value)).roundToInt().toFloat() } }

                    // 单封面 = 真 3D 盒子：正面封面 + 外侧厚度书脊，二者共用同一条「靠中心边」并绕同一点旋转，
                    // 正面转 angle、侧面再折 90°，恰好拼成实体转角（经典 Cover Flow 的立体 CD 盒）。所有透视按距中心
                    // offset 推导，变换在 graphicsLayer 绘制期读 pos，不触发 layout/measure，滑动只重绘帧率稳。
                    Box(
                        Modifier
                            .size(coverW, coverH)
                            .zIndex(zRank)
                    ) {
                        // 厚度侧面（CD 盒边）：与正面同父框、靠同一条边对齐、绕同一点再折 90°，立起后正好与正面拼成转角。
                        // 正对(absO<0.5)时 alpha=0 隐去，只在侧立时露出真实书脊；书脊竖排文字随侧面一起成竖字。
                        Box(
                            Modifier
                                .width(thicknessDp).height(coverH)
                                .align(if (onRightNow) Alignment.CenterStart else Alignment.CenterEnd)
                                .graphicsLayer {
                                    val offset = (i - (pos.value + dbgShift))
                                    val onRight = offset > 0f
                                    val absO = abs(offset)
                                    // 与正面共用同一条水平位移：书脊必须随整张封面一起平移，否则会脱离封面堆在屏幕中央
                                    // （之前就是缺这一行 → 书脊变成中央一道道竖线）。平移后再绕靠中心边折 ±90° 拼出 90° 夹角。
                                    translationX = offset * spacingPx
                                    rotationY = (offset * STEP_ANGLE).coerceIn(-MAX_ANGLE, MAX_ANGLE) + if (onRight) -90f else 90f
                                    transformOrigin = if (onRight) TransformOrigin(0f, 0.5f) else TransformOrigin(1f, 0.5f)
                                    cameraDistance = camPx
                                    alpha = if (absO > 0.5f) 1f else 0f
                                    if (absO > WINDOW) alpha = 0f
                                }
                                .clip(shape)
                                .background(cdSpineBrush(name, onRightNow))
                        ) {
                            SpineLabel(name, 90f)
                        }
                        // 正面：透视翻转 + 靠中心边转轴 + 水平排列 + 中心放大；所有 transform 在 graphicsLayer 绘制期读 pos
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val offset = (i - (pos.value + dbgShift))
                                    val onRight = offset > 0f
                                    val absO = abs(offset)
                                    // 旋转：中心 0，每跨一步转约 STEP_ANGLE，clamp 到最大 MAX_ANGLE。
                                    // 右封(offset>0)取正角 → 右边缘后退、左边缘前突，露出「左侧书脊」朝向中心；左封对称。
                                    val angle = (offset * STEP_ANGLE).coerceIn(-MAX_ANGLE, MAX_ANGLE)
                                    rotationY = angle
                                    // 水平排列：右侧右移、左侧左移（此前的位移符号写反，导致整列镜像错乱）
                                    translationX = offset * spacingPx
                                    // 转轴取「靠中心一侧」的边缘：右封绕左边缘、左封绕右边缘
                                    transformOrigin = if (onRight) TransformOrigin(0f, 0.5f) else TransformOrigin(1f, 0.5f)
                                    cameraDistance = camPx
                                    // 缩放：中心放大弹出，侧封随距离渐缩
                                    val s = if (absO < 0.5f) (1.18f - 0.18f * (absO / 0.5f))
                                            else (1f - (absO - 0.5f).coerceAtLeast(0f) * 0.14f).coerceAtLeast(0.5f)
                                    scaleX = s
                                    scaleY = s
                                    // 透明：远处渐隐
                                    alpha = if (absO < 0.5f) 1f
                                            else (1f - (absO - 0.5f).coerceAtLeast(0f) * 0.28f).coerceIn(0.06f, 1f)
                                    if (absO > WINDOW) alpha = 0f
                                }
                                .clickable(
                                    interactionSource = remember(i) { MutableInteractionSource() },
                                    indication = null
                                ) { if (abs(pos.value - i) < 0.5f) onAlbumClick(name) }
                        ) {
                            CoverArtSurface(coverUri, name, shape)
                            // 纵深压暗：内缘（靠中心一侧）偏暗，强化「向中心汇聚」的走廊感；方向低频翻转
                            val dimAlpha = 0.30f
                            Box(Modifier.fillMaxSize().background(
                                if (onRightNow)
                                    Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = dimAlpha))
                                else
                                    Brush.horizontalGradient(0f to Color.Black.copy(alpha = dimAlpha), 1f to Color.Transparent)
                            ))
                        }
                    }
                }
                    }
                    // —— 底部：迷你播放器 ——
                    CoverFlowMiniPlayer(
                        song = currentSong,
                        isPlaying = isPlaying,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        onPlayPause = onPlayPause,
                        onPrevious = onPrevious,
                        onNext = onNext,
                        onTap = onNavigateToPlayer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    }
                }
            }
        }
    }
}

/**
 * 封面流底部的精简版迷你播放器：当前歌曲封面/标题/歌手 + 上/播放暂停/下一首 + 细进度条。
 * 与主页 MiniPlayer 解耦，只接必要播放状态，避免引入玻璃模糊/共享元素那一套重型机械。
 */
@Composable
private fun CoverFlowMiniPlayer(
    song: Song?,
    isPlaying: Boolean,
    positionMs: State<Long>,
    durationMs: State<Long>,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (song == null) return
    val pos = positionMs.value
    val dur = durationMs.value
    val fraction = if (dur > 0L) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val coverShape = RoundedCornerShape(16.dp)

    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable { onTap() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 当前歌曲封面
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(coverShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    DefaultCoverImage(
                        songTitle = song.title,
                        songArtist = song.artist,
                        modifier = Modifier.fillMaxSize(),
                        shape = coverShape
                    )
                    if (SongRepository.hasRealCover(song.albumArtUri)) {
                        Image(
                            painter = rememberSharedCoverPainter(song.albumArtUri, big = false),
                            contentDescription = song.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = song.artist,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Filled.SkipPrevious, stringResource(R.string.previous_track), tint = Color.White)
                }
                FilledTonalIconButton(onClick = onPlayPause, shapes = IconButtonDefaults.shapes()) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (isPlaying) stringResource(R.string.pause_action) else stringResource(R.string.play_action),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Filled.SkipNext, stringResource(R.string.next_track), tint = Color.White)
                }
            }
            Spacer(Modifier.height(8.dp))
            // 细进度条（用纯 Compose 绘制，规避不同 Compose 版本 LinearProgressIndicator 签名差异）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(2.dp))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

/**
 * 封面流搜索栏（M3 Expressive）：大圆角色调容器 + 搜索图标 + 透明 TextField + 清除按钮。
 * 输入即过滤，不离开封面流页面。
 */
@Composable
private fun CoverFlowSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Search,
            stringResource(R.string.action_search),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp).size(22.dp)
        )
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    stringResource(R.string.songlist_search_placeholder),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 16.sp
                )
            },
            textStyle = LocalTextStyle.current.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp
            ),
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary
            )
        )
        if (query.isNotEmpty()) {
            FilledTonalIconButton(
                onClick = { onQueryChange("") },
                modifier = Modifier.size(40.dp),
                shapes = IconButtonDefaults.shapes()
            ) {
                Icon(
                    Icons.Filled.Close,
                    stringResource(R.string.action_clear),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** 封面表面：封面图（占位/真实）+ 顶部高光，作为正面与倒影的复用单元。 */
@Composable
private fun CoverArtSurface(coverUri: String, albumName: String, shape: RoundedCornerShape) {
    val hasCover = SongRepository.hasRealCover(coverUri)
    Box(Modifier.fillMaxSize().clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        DefaultCoverImage(
            songTitle = albumName,
            songArtist = "",
            modifier = Modifier.fillMaxSize(),
            shape = shape
        )
        if (hasCover) {
            Image(
                painter = rememberSharedCoverPainter(coverUri, big = false),
                contentDescription = albumName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        // 顶部高光（斜向反光，实体面受光感）
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.16f),
                    0.4f to Color.Transparent
                )
            )
        )
    }
}

/**
 * 书脊渐变（CD 盒厚度侧面）：模拟实体侧边塑料受光——靠正面一侧亮、外侧暗。
 * 真正的「立体转角」由封面正面 + 这条侧面共同折 90° 拼出，正对时覆盖层不绘制，自动隐去。
 */
private fun cdSpineBrush(albumName: String, onRight: Boolean): Brush {
    val hue = (albumName.hashCode().rem(360)).toFloat().let { if (it < 0f) it + 360f else it }
    val light = Color.hsv(hue, 0.42f, 0.88f)
    val dark = Color.hsv(hue, 0.70f, 0.22f)
    return if (onRight) Brush.horizontalGradient(0f to light, 1f to dark)
                      else Brush.horizontalGradient(0f to dark, 1f to light)
}

/** 书脊上的竖排专辑名：先给足宽度让文字水平排布，再整体旋转 90° 成竖排。 */
@Composable
private fun SpineLabel(name: String, rot: Float) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = name,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .graphicsLayer { rotationZ = rot }
                .width(200.dp)
                .wrapContentHeight()
        )
    }
}
