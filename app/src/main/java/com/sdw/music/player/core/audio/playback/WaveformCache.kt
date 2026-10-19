package com.sdw.music.player.core.audio.playback
import com.sdw.music.player.core.audio.analysis.AudioAnalyzer
import com.sdw.music.player.core.audio.analysis.MediaProbe
import com.sdw.music.player.core.audio.analysis.SongStructureAnalyzer
import com.sdw.music.player.core.audio.playback.WaveformCache

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 【V8.372】整曲波形包络 —— djay 那条彩色波形背后的数据。
 *
 * ## 为什么需要它
 * 之前 DJ 界面用的是 [com.sdw.music.player.ui.screens.EnergyRibbon]，那是
 * **最近 N 秒的实时能量**，滚动的、只看得到过去。djay 的波形是**整首歌的静态包络**，
 * 播放头从左往右扫过，你能预判副歌在第几分秒——这是能量带给不了的。
 *
 * ## 数据来源
 * 走 MediaExtractor 自己解一遍文件（不是 Oboe 链，不碰实时音频线程）。
 * 这和 AudioAnalyzer.detectBPM / detectKey 是同一条路子，那两个已经证明可行。
 *
 * 用 Android 的 Visualizer 拿不到：Oboe 直写没有 audio session，
 * VisualizerManager 已经因此 permanently give up（见该文件注释）。
 *
 * ## 性能与缓存
 * - 每首歌解一遍完整文件，耗时与曲长成正比（4 分钟歌约 0.3~1 秒）。
 * - 结果按 path 缓存进 [cache]，切歌回退时直接命中，不再重扫。
 * - 只保留峰值（每 bucket 一个 float），2000 点约 8KB，一首歌可忽略。
 * - 绝不在音频线程调用；UI 侧只在 IO 协程里用。
 */
object WaveformCache {

    private const val TAG = "WaveformCache"

    /** 归一化桶数。djay 在手机上也就这个量级，再多肉眼分辨不出。 */
    private const val BUCKETS = 1400

    /**
     * 每桶样本数的下限/上限。
     *
     * 【V8.374 修正】原来这里是固定 1024 —— 1400 桶 x 1024 帧只覆盖约 32 秒，
     * 5 分钟的歌只能画出前 1/4，后面全是空白（用户截图确认了这个现象）。
     * 现在改为**按歌长动态算**：总帧数 / 桶数，再夹到 [512, 16384]。
     * 512 帧（约 11ms）保证短片段的瞬态不被漏掉；16384（约 370ms）防止超长歌
     * 分配过大的 ByteBuffer。
     */
    private const val MIN_BUCKET_SAMPLES = 512
    private const val MAX_BUCKET_SAMPLES = 16384

    /**
     * 【V8.453】MediaExtractor.readSampleData 能否直接当 16bit PCM 读。
     *
     * 这个前提**只对未压缩/PCM 类格式成立**：
     *   - MP3 / AAC / Opus / FLAC / ALAC：readSampleData 返回的是**压缩帧**，
     *     按 16bit 采样解读出来的是比特流而非音频波形
     *   - WAV（PCM）/ raw：才是真正的采样点
     *
     * 但实测（V8.451 真机日志）FLAC 与 MP3 都画出了合理的动态
     * （p05=0.057 p50=0.173 p98=0.504），说明 Android 的 extractor 在这两条路径上
     * **确实做了解码**（依赖厂商 extractor 实现）。
     *
     * 风险在于 Opus/M4A(AAC in MP4)：容器与编码组合更复杂，部分实现会返回原始压缩帧。
     * 一旦如此，症状是：波形看起来"有起伏但不对"（全是比特流的随机起伏），
     * 或者全程一条平线 —— 段���检测同时失效，但不会有任何报错。
     *
     * 所以这里：压缩格式**照常扫**（万一 extractor 能解码，结果就是对的），
     * 但记录首桶的可读性指标；全曲若呈现"无真实动态"的特征，就标记为不可信，
     * 由调用方回落单色显示，而不是画一条假波形。
     *
     * 判断"假波形"用 RMS 峰谷比：真实音乐 RMS 动态通常 > 1.8；
     * 压缩比特流按 16bit 解读时动态很小（各帧长度相近 → 能量近似恒定）。
     */
    // 【V8.461】1.35 → 0.62。实测 MP3 只有 1.037，原值是照FLAC 定的。
    // 必须与 zeroCrossRate 联合判定，单靠包络动态会误杀 MP3。
    private const val FAKE_WAVE_DYN_MAX = 0.62f
    // 零交叉率上限：真实音乐 44.1kHz 下 ZCR 约 0.02~0.20，未解码比特流接近 0.5。
    private const val BITSTREAM_ZCR_MIN = 0.30f
    // 【V8.438】三段配平增益（按听感，抵消低频 vs 高频的能量级差）。
    // 各自独立归一化前先乘这个，否则 low 恒 dominate -> 整首全红。
    private const val BAND_MID_GAIN = 2.0f    // +6 dB
    private const val BAND_HIGH_GAIN = 4.0f   // +12 dB

    private const val MAX_CACHE_ENTRIES = 6

    private val cache = ConcurrentHashMap<String, FloatArray>()

    // 【V8.436】kick 包络缓存（200Hz 低通能量），段落检测专用。
    // 与 cache 分开存：cache 命中时不想强制再做一次 kick 扫描（成本一样，但更灵活）。
    private val kickCache = ConcurrentHashMap<String, FloatArray>()
    // 【V8.437】mid / high 包络缓存（djay 式频谱着色用）
    private val midCache = ConcurrentHashMap<String, FloatArray>()
    private val highCache = ConcurrentHashMap<String, FloatArray>()
    // 【V8.472】谐波度包络缓存（人声入场判定用）。见 harmonicity() 注释。
    private val harmCache = ConcurrentHashMap<String, FloatArray>()

    /** 【V8.472】只查谐波度缓存，不触发扫描。 */
    fun harmOf(path: String): FloatArray? {
        if (path.isBlank()) return null
        return harmCache[path]
    }

    /**
     * 取整曲包络。（V8.372）
     *
     * @param buckets 桶数；传0 用默认 [BUCKETS]
     * @return 长度 = buckets 的归一化数组（0..1），失败返回 null
     */
    suspend fun envelope(
        context: Context,
        path: String,
        uri: Uri?,
        buckets: Int = 0,
        // 【V8.436】是否同时产出 kick 包络（段落检测用，见 envelopeKick）
        wantKick: Boolean = false
    ): FloatArray? = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext null
        val n = if (buckets > 0) buckets else BUCKETS

        // 缓存命中：桶数相同才复用，否则重算
        cache[path]?.let { if (it.size == n) return@withContext it }

        val result = scan(context, path, uri, n, wantKick)
        if (result != null) {
            if (cache.size >= MAX_CACHE_ENTRIES) {
                // 简单粗暴：清掉最早插入的一个。ConcurrentHashMap 无序，
                // 换个 LinkedHashMap 就要加锁，这里不值得。
                val oldest = cache.keys.firstOrNull()
                if (oldest != null) cache.remove(oldest)
            }
            cache[path] = result
        }
        result
    }

    /** 曲长变化时作废缓存（切歌复用同一 path 的极端情况）。 */
    fun invalidate(path: String) {
        cache.remove(path)
    }

    /**
     * 【V8.386】**同步**取已缓存的包络，未缓存返回 null。
     *
     * 供切歌热路径用：[SongStructureAnalyzer] 算尾奏/Drop 时需要立刻拿到包络，
     * 但那里不能挂起、也不能顺手触发一次整曲扫描（那是 IO 协程的活）。
     * 命中不了就让调用方退回原生的 preScan 兜底。
     */
    fun cached(path: String, buckets: Int = 0): FloatArray? {
        if (path.isBlank()) return null
        val n = if (buckets > 0) buckets else BUCKETS
        return cache[path]?.takeIf { it.size == n }
    }

    fun clear() = cache.clear()

    /**
     * 【V8.436】取 kick 包络（200Hz 低通能量）—— **段落检测专用**。
     *
     *## 为什么要单独一个通道
     * 实测根因：现代母带（Loudness War）把**整条频带**都推平了，
     *   宽带能量算出来的动态 dyn=0.015（= 一条直线），段落完全检不出；
     *   而 native 首拍检测用 200Hz 低通 + kick 能量就能稳定检出 firstBeat。
     * 原因是鼓组是节奏骨架，混音师不会把它压平（压掉整首歌就没节奏了）。
     *
     * @param force true = 没缓存就同步扫一次（后台线程用）；false = 只查缓存
     */
    suspend fun envelopeKick(
        context: Context,
        path: String,
        uri: Uri?,
        buckets: Int = 0,
        force: Boolean = true
    ): FloatArray? {
        if (path.isBlank()) return null
        kickCache[path]?.let { return it }
        if (!force) return null
        // 走一次完整扫描并顺带产出 kick（wantKick=true）
        envelope(context, path, uri, buckets, wantKick = true)
        return kickCache[path]
    }

    /** 【V8.436】只查 kick 缓存，不触发扫描（切歌热路径用）。 */
    fun cachedKick(path: String, buckets: Int = 0): FloatArray? {
        if (path.isBlank()) return null
        return kickCache[path]
    }

    /**
     * 【V8.437】取三段包络（low/mid/high），用于 djay 式波形着色。
     * 任何一段缺失都返回 null（调用方回退到宽带单色）。
     */
    fun bands3(path: String): Triple<FloatArray, FloatArray, FloatArray>? {
        if (path.isBlank()) return null
        val lo = kickCache[path] ?: return null
        val mid = midCache[path] ?: return null
        val hi = highCache[path] ?: return null
        if (lo.size != mid.size || mid.size != hi.size) return null
        return Triple(lo, mid, hi)
    }

    private fun scan(context: Context, path: String, uri: Uri?, buckets: Int, wantKick: Boolean = false): FloatArray? {
        // 【V8.429】整曲包络扫描是最"重"的一次探测（要 seek+read 整首），
        // 最容易与切歌时的短命探测交错拆卸内部 codec → native SIGABRT。
        // 整个 create→use→release 收敛到单线程闸门（详见 MediaProbe 类注释）。
        return MediaProbe.onProbeThread { scanOnProbeThread(context, path, uri, buckets, wantKick) }
    }

    private fun scanOnProbeThread(context: Context, path: String, uri: Uri?, buckets: Int, wantKick: Boolean = false): FloatArray? {
        val ex = MediaExtractor()
        try {
            val extractor = ex
            val ok = if (uri != null && !path.startsWith("content://")) {
                // 有真实文件时走文件路径，比 content:// 少一层 binder
                runCatching {
                    extractor.setDataSource(path)
                    true
                }.getOrElse {
                    runCatching { extractor.setDataSource(context, uri, null); true }
                        .getOrDefault(false)
                }
            } else if (uri != null) {
                runCatching { extractor.setDataSource(context, uri, null); true }
                    .getOrDefault(false)
            } else {
                runCatching { extractor.setDataSource(path); true }.getOrDefault(false)
            }
            if (!ok) {
                Log.w(TAG, "waveform: cannot open $path")
                return null
            }

            var trackIndex = -1
            var sampleRate = 44100
            var chCount = 2
            var durationUs = 0L
            var mime = ""            // 【V8.453】Opus/M4A 可信度校验要用
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (!m.startsWith("audio/")) continue
                trackIndex = i
                mime = m
                sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                chCount = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                durationUs = f.getLong(MediaFormat.KEY_DURATION)
                break
            }
            if (trackIndex < 0) {
                Log.w(TAG, "waveform: no audio track in $path")
                return null
            }
            extractor.selectTrack(trackIndex)

            val peaks = FloatArray(buckets)
            // 【V8.436】kick 包络（200Hz 低通能量），段落检测专用。与 peaks 独立归一化。
            // 【V8.437】扩成三段：low(<=200Hz) / mid(200~2k) / high(>=2k)。
            // low 就是原来的 kick，段落检测继续用它（鼓组不会被母带压平）；
            // mid/high 用于 djay 式波形着色（红/绿/蓝按频段）。
            val kickPks = if (wantKick) FloatArray(buckets) else null
            val midPks = if (wantKick) FloatArray(buckets) else null
            val highPks = if (wantKick) FloatArray(buckets) else null
            val harmPks = if (wantKick) FloatArray(buckets) else null
            // 一阶低通系数：k = 1 - exp(-2*pi*fc/sr)
            val kickK = (1.0 - kotlin.math.exp(-2.0 * Math.PI * 200.0 / (sampleRate.coerceAtLeast(1).toDouble()))).toFloat()
            var kickLpPrev = 0f
            // 2kHz 低通：mid = lp2k - lp200，high = broadband - lp2k
            val midK = (1.0 - kotlin.math.exp(-2.0 * Math.PI * 2000.0 / (sampleRate.coerceAtLeast(1).toDouble()))).toFloat()
            var midLpPrev = 0f
            // 按歌长摊到桶上：这样无论 30 秒的 interlude 还是 7 分钟的 live，
            // 都能正好铺满 buckets 个格子，不多不少。
            val totalFrames = if (durationUs > 0 && sampleRate > 0) {
                (durationUs.toLong() * sampleRate.toLong() / 1_000_000L).toInt()
            } else 0
            // 【V8.455 修"短文件（如 wav 测试片段）右边整条空白"】
            // 原来无条件套`coerceIn(MIN_BUCKET_SAMPLES, ...)`：
            // MIN=512 是为"正常歌曲"定的下限，但一个 3 秒的 WAV 只有 13.2 万帧，
            // 1400 个桶 → 每桶 94 帧，被抬到 512 后 1400*512 = 71.7 万帧 > 实际帧数，
            // 循环在第 258 个桶就 read<=0 退出，**后 1142 个桶全是 0**。
            // 症状与之前那次「右边一整块空白」完全一样。
            // 修法：文件短于 buckets*MIN 时按实际帧数摊，不再抬到下限。
            // 【V8.463修"MP3/长曲右边没波纹"—— 扫描覆盖率问题】
            //
            // 原来：`coerceIn(512, 16384)`。**上限 16384 会让长文件只扫一部分**：
            // 15 分钟 WAV = 3900 万帧，1400 桶 → 每桶需 28000 帧，
            // 被夹到 16384 后只能读到 16384*1400 = 2294 万帧 =全曲的 58%，
            // **后面 42% 永远是空白**（读不到就停在那个桶）。
            // MP3 长曲同理；短曲（<6 分钟）碰不到这个上限，所以之前只在长文件上暴露。
            //
            // 上限存在的唯一原因是 buffer 按`framesPerBucket * chCount * 2`
            // 分配（8 声道 16384 帧 = 256KB）。但**不需要每桶都读满上限**——
            // 波形只需���**采样**，不需要连续读。
            //
            // 修法：文件长到需要超过上限时，**按 stride 跳读**：
            // 每桶仍读 16384 帧（buffer 不变大），但桶内取样间隔
            // `stride = ceil(framesPerBucket / 16384)`，覆盖整曲。
            // 代价：长曲的桶内细节变稀（波形略糊），换来 100% 覆盖。
            // 短曲 stride=1，行为完全不变。
            val framesPerBucketRaw = if (totalFrames > 0) {
                val want = totalFrames / buckets
                if (want < MIN_BUCKET_SAMPLES) want.coerceAtLeast(1)
                else want.coerceIn(MIN_BUCKET_SAMPLES, MAX_BUCKET_SAMPLES)
            } else 2048
            // 实际每桶读多少帧（受 MAX_BUCKET_SAMPLES 限制）
            val framesPerBucket = if (totalFrames > 0) {
                val want = totalFrames / buckets
                want.coerceIn(MIN_BUCKET_SAMPLES, MAX_BUCKET_SAMPLES)
            } else 2048
            // 跳读步长：>1 表示文件长到一桶装不下，需要跨读
            val bucketStride = if (framesPerBucketRaw > framesPerBucket)
                ((framesPerBucketRaw + framesPerBucket - 1) / framesPerBucket)
            else 1
            val bytesPerBucket = framesPerBucket * chCount * 2
            val buffer = java.nio.ByteBuffer.allocate(bytesPerBucket * 4)
            val shorts = ShortArray(framesPerBucket * chCount)
            // 【V8.483】一个 sample 常常比一桶大得多（FLAC 实测约 2 桶，个别超 4 桶），
            // 缓冲区按 sample 尺寸预留，否则要么越界崩，要么丢数据。
            val stageFrames = framesPerBucket * 4
            val stage = ShortArray(stageFrames * chCount)
            var stageHave = 0   // stage 中已有帧数
            var stagePos = 0    // stage 中已发出的帧数
            // framesPerBucket 已被 clamp 到<=16384，chCount 正常 <=8，
            // 所以 buffer 最大约 256KB，不会 OOM。

            // 【V8.461】零交叉率统计：区分"未解码比特流"与"真实 PCM"。
            // 只累加符号变化次数与总样本数，O(1) 内存、不影响实时性。
            var zcrCross = 0L
            var zcrTotal = 0L
            var prevSign = 0

            // 【V8.476】桶级累加器：桶内可能要多次 readSampleData 才攒满
            //（MP3 一次 read 只有一个帧），能量跨 read 累积，攒满才 finalize。
            var bFrames = 0
            var bSumSq = 0.0
            var bPeakAbs = 0f
            var bKickSumSq = 0.0
            var bMidSumSq = 0.0
            // 桶级样本缓冲：harmonicity() 需要整桶样本做自相关，
            // 而 shorts 每次 read 都被覆盖，所以累积到独立数组。
            // 【V8.485】容量放大到 2 倍：bFrames 攒满一桶后可能再多读一个 stage 块
            // （最多约 2*framesPerBucket），给足容量 + 下方 cp 夹紧，彻底消除越界崩溃
            //（之前长曲报 ArrayIndexOutOfBounds -> "waveform failed"，人声检测跟着丢数据）。
            val accShorts = ShortArray(framesPerBucket * 2 * chCount)

            // 【V8.476 修"MP3 波形还是错的"· 真正的根因】
            //
            // MediaExtractor 对 **FLAC/WAV 输出 raw PCM（mime=audio/raw）**，
            // 但对 **MP3/AAC/Opus 输出的是压缩比特流**（audio/mpeg、audio/mp4a...）。
            // 原代码把 readSampleData 的字节**直接当 int16 PCM 读** ——
            // 对 MP3 来说读进来的根本不是音频，是编码比特，
            // 画出来自然是"完全不对"的波形。FLAC 一直正常就是因为它是 audio/raw。
            //
            // 修法：mime 不是 audio/raw 时，走 MediaCodec 解码，把解出的 PCM
            // 喂给同一个分桶累加逻辑。FLAC/WAV 路径行为完全不变。
            val pcmIsRaw = mime.equals("audio/raw", true) ||
                    mime.startsWith("audio/L16", true) || mime.contains("pcm", true)
            var scanCodec: MediaCodec? = null
            var scanInEos = false
            val scanInfo = MediaCodec.BufferInfo()
            if (!pcmIsRaw) {
                try {
                    val tf = extractor.getTrackFormat(trackIndex)
                    scanCodec = MediaCodec.createDecoderByType(mime)
                    scanCodec.configure(tf, null, null, 0)
                    scanCodec.start()
                    Log.i(TAG, "waveform: mime=$mime 需解码（原样读比特流会被误当 PCM）")
                } catch (t: Throwable) {
                    Log.w(TAG, "waveform: create decoder for $mime failed: ${t.message}")
                    scanCodec = null
                }
            }

            // 取一段 PCM 到 dst（最多 maxFrames 帧），返回帧数；<=0 表示没有更多数据。
            // 取一段 PCM 到 dst（最多 maxFrames 帧），返回帧数；<=0 表示没有更多数据。
            //
            // 【V8.483】两条读取路径统一经过 stage（桶外暂存）：
            // 每次拿到的原始数据块（MediaExtractor 的 sample / MediaCodec 的 output buffer）
            // 常常比一桶大得多（旧实现最多只接受 maxFrames 帧、剩余的直接丢）。
            // 实测 MP3 166s：只覆盖 1059/1400 桶、125s/166s(75%)，尾部空白；
            // raw 路径更极端时会写越界 -> "waveform failed"，整首没波形。
            // 现在整块进 stage，按 maxFrames 切给调用方，剩余的下次接着发。
            fun nextPcm(dst: ShortArray, maxFrames: Int): Int {
                if (stagePos >= stageHave) {
                    stageHave = 0
                    stagePos = 0
                    if (pcmIsRaw || scanCodec == null) {
                        buffer.clear()
                        val read = extractor.readSampleData(buffer, 0)
                        if (read <= 0) return -1
                        buffer.position(0)
                        buffer.limit(read)
                        // 【V8.386 修字节序】allocate() 默认 BIG_ENDIAN，必须设小端
                        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        val nFrames = read / (2 * chCount)
                        if (nFrames <= 0) return -1
                        val takeIn = minOf(nFrames, stageFrames)
                        buffer.asShortBuffer().get(stage, 0, takeIn * chCount)
                        stageHave = takeIn
                        if (takeIn < nFrames) {
                            Log.w(TAG, "waveform: raw sample($nFrames f) > stage($stageFrames f), tail dropped")
                        }
                        extractor.advance()
                    } else {
                        // —— 解码路径：喂输入直到拿到一块输出 ——
                        val c = scanCodec!!
                        var loops = 0
                        while (stageHave <= 0 && loops < 4096) {
                            loops++
                            if (!scanInEos) {
                                // 【V8.481】超时不能是 0：解码器刚 start 时队列尚未就绪，
                                // 0 会立刻返回 -1 -> 空转到放弃 -> 整首拿不到任何 PCM。
                                val inIdx = c.dequeueInputBuffer(10_000)
                                if (inIdx >= 0) {
                                    val ib = c.getInputBuffer(inIdx)
                                    if (ib != null) {
                                        ib.clear()
                                        val sz = extractor.readSampleData(ib, 0)
                                        if (sz < 0) {
                                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                            scanInEos = true
                                        } else {
                                            c.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                                            extractor.advance()
                                        }
                                    }
                                }
                            }
                            val outIdx = c.dequeueOutputBuffer(scanInfo, 20_000)
                            if (outIdx >= 0) {
                                val ob = c.getOutputBuffer(outIdx)
                                val nFrames = scanInfo.size / (2 * chCount)
                                if (ob != null && nFrames > 0) {
                                    ob.position(scanInfo.offset)
                                    ob.limit(scanInfo.offset + scanInfo.size)
                                    ob.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                    val takeIn = minOf(nFrames, stageFrames)
                                    ob.asShortBuffer().get(stage, 0, takeIn * chCount)
                                    stageHave = takeIn
                                    stagePos = 0
                                    if (takeIn < nFrames) {
                                        Log.w(TAG, "waveform: codec out($nFrames f) > stage($stageFrames f), tail dropped")
                                    }
                                }
                                val eof = scanInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                c.releaseOutputBuffer(outIdx, false)
                                if (eof) { scanInEos = true; break }
                            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                // 输出格式变化：再 dequeue 一次即可
                            } else if (scanInEos) {
                                break   // 输入已给完且暂无输出 -> 排空完成
                            }
                        }
                        if (stageHave <= 0) return -1
                    }
                }
                val avail = stageHave - stagePos
                val take = minOf(maxFrames, avail)
                if (take <= 0) return -1
                System.arraycopy(stage, stagePos * chCount, dst, 0, take * chCount)
                stagePos += take
                return take
            }

            var bucket = 0
            var guard = 0
            // guard 上限防死循环：正常曲长最多几千个 bucket，给 100 万余量
            while (bucket < buckets && guard < 1_000_000) {
                guard++
                // 【V8.463】长曲跳读：本次只读 framesPerBucket 帧，
                // 剩下 (stride-1) 段用 seek 跳过，保证 1400 个桶覆盖**整曲**。
                // stride=1（短曲）时行为与原来完全一致。
                // —— 桶开始：重置累加器（【V8.476】攒满一桶才推进）——
                bFrames = 0
                bSumSq = 0.0
                bPeakAbs = 0f
                bKickSumSq = 0.0
                bMidSumSq = 0.0
                kickLpPrev = 0f
                midLpPrev = 0f

                var bucketEof = false
                while (bFrames < framesPerBucket) {
                    guard++
                    if (guard >= 1_000_000) { bucketEof = true; break }
                    // 【V8.463】长曲跳读：只在**桶开始**时 seek 一次
                    // 【V8.481】跳读 seek 只在 raw 直读路径成立。解码路径若中途 seek，
                    // codec 没有 flush，会把新位置的输入喂进旧状态的解码器 → 输出错乱。
                    if (bFrames == 0 && bucketStride > 1 && pcmIsRaw) {
                        val skipUs = (totalFrames.toLong() * bucketStride * bucket / sampleRate) * 1000L
                        if (skipUs > 0) {
                            runCatching { extractor.seekTo(skipUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) }
                        }
                    }
                    // 【V8.476】统一走 nextPcm：raw 直接读，MP3/AAC/Opus 先解码
                    val nFrames = nextPcm(shorts, framesPerBucket)
                    if (nFrames <= 0) { bucketEof = true; break }

                    // 【V8.476】同时追加到桶级缓冲，harmonicity() 需要整桶样本做自相关
                    val dstOff = bFrames * chCount
                    var cp = nFrames * chCount
                    // 【V8.485】夹紧：bFrames 可能越过 fpb，dstOff+cpy 超过 accShorts 时
                    // 只拷贝到末尾（多读的那一小段样本丢弃，对自相关无影响），避免越界。
                    if (dstOff >= accShorts.size) cp = 0
                    else if (dstOff + cp > accShorts.size) cp = accShorts.size - dstOff
                    if (cp > 0) System.arraycopy(shorts, 0, accShorts, dstOff, cp)

                    // 能量累加（跨 read 累积到 b* 变量）
                    var i = 0
                    while (i < nFrames * chCount) {
                        val v = shorts[i].toFloat() / 32768f
                        val av = if (v < 0) -v else v
                        if (av > bPeakAbs) bPeakAbs = av
                        bSumSq += (v * v).toDouble()
                        // 【V8.461】零交叉：只看第一个声道
                        if (i % chCount == 0) {
                            val sg = if (v > 0.0008f) 1 else if (v < -0.0008f) -1 else 0
                            if (sg != 0) {
                                if (prevSign != 0 && sg != prevSign) zcrCross++
                                prevSign = sg
                            }
                            zcrTotal++
                        }
                        if (wantKick) {
                            kickLpPrev += kickK * (v - kickLpPrev)
                            bKickSumSq += (kickLpPrev * kickLpPrev).toDouble()
                            midLpPrev += midK * (v - midLpPrev)
                            bMidSumSq += (midLpPrev * midLpPrev).toDouble()
                        }
                        i += chCount
                    }
                    bFrames += nFrames

                    // 【V8.484】不能在这里再 advance()：nextPcm 内部整块进 stage 时
                    // 已经推进过一次，这里再来一次等于每块跳过一个 sample，
                    // 覆盖率直接砍半（实测长曲只扫到 38%~49%，尾部全空）。
                }

                if (bFrames <= 0) { if (bucketEof) break else continue }

                // —— finalize：这一桶攒满了（或 EOF 收尾）——
                val nFrames = bFrames
                val sumSq = bSumSq
                val peakAbs = bPeakAbs
                val rms = kotlin.math.sqrt(sumSq / (nFrames * chCount)).toFloat()
                // 轻度混合：RMS 为主，峰值少量参与，保留打击感又不会顶格
                peaks[bucket] = rms * 0.82f + peakAbs * 0.18f
                if (kickPks != null && nFrames > 0) {
                    kickPks[bucket] = kotlin.math.sqrt(bKickSumSq / nFrames).toFloat()
                    val lp2kSq = bMidSumSq / nFrames
                    val lp200Sq = bKickSumSq / nFrames
                    midPks?.let {
                        it[bucket] = kotlin.math.sqrt((lp2kSq - lp200Sq).coerceAtLeast(0.0)).toFloat() * BAND_MID_GAIN
                    }
                    highPks?.let {
                        // 【V8.451量纲修正】与 lp2kSq 同分母（nFrames），不再除 chCount。
                        val broadSq = sumSq / nFrames
                        it[bucket] = kotlin.math.sqrt((broadSq - lp2kSq).coerceAtLeast(0.0)).toFloat() * BAND_HIGH_GAIN
                    }
                    // 【V8.472 谐波度】用**整桶**样本做自相关（accShorts 已攒满）
                    harmPks?.let {
                        val hn = minOf(nFrames, accShorts.size / chCount)
                        it[bucket] = harmonicity(accShorts, hn, chCount, sampleRate)
                    }
                }
                bucket++

                if (bucketEof) break
            }
            extractor.release()
            runCatching { scanCodec?.stop(); scanCodec?.release() }

            if (bucket == 0) {
                Log.w(TAG, "waveform: no samples decoded from $path")
                return null
            }

            // 【V8.438 自适应频段配平】在归一化**之前**，按三段各自的 p98 计算增益，
            // 让三段归一化后量级可比。
            //
            // 为什么必须有这一步（真机截图暴露的 bug）：
            //   三段物理能量级差极大（低频比高频高 10~20 dB），各自独立归一化到 0~1 后，
            //   low 几乎处处≈1 而 high 处处≈0 —— 整首被染成红色，永远看不到 djay 的蓝色人声段。
            // 固定增益（BAND_*_GAIN）只是先验；不同曲目频段平衡差异很大，
            // 所以再加一层按本曲实际 p98 的自适应修正，两者相乘。
            if (kickPks != null && midPks != null && highPks != null && bucket > 32) {
                fun p98Of(a: FloatArray, n: Int): Float {
                    val c = a.copyOf(n); c.sort()
                    return c[((n * 98) / 100).coerceIn(0, n - 1)]
                }
                val pLo = p98Of(kickPks, bucket)
                val pMd = p98Of(midPks, bucket)
                val pHi = p98Of(highPks, bucket)
                // 【V8.451】退化通道保护：pHi 极小时说明这首歌几乎没有高频内容
                // （MP3 低码率、老录音、纯器乐）。此时**不能**给 200 倍增益 ——
                // 那会把量化噪声放大成主导色，整条波形被染成蓝色/紫色假象。
                // 真机日志曾出现 `pHi=0.0 -> high x200.0`：增益顶到上限，
                // 而 high 通道其实恒为 0（见下面量纲修正）。
                val hiUsable = pHi > 1e-5f
                // 目标：让三段的 p98 尽量一致（即量级对齐）。以 low 为基准算比值。
                val autoMd = if (pLo > 1e-6f) (pLo / pMd.coerceAtLeast(1e-6f)) else 1f
                val autoHi = if (hiUsable && pLo > 1e-6f) (pLo / pHi) else 1f
                // 限幅：自适应可能算出极端值（极安静的段落），钳在合理范围内。
                // 【V8.451】high 上限 200 → 8.0。8 倍约 +18dB，足够补偿真实的高频
                // 衰减，又不会把噪声放大成主色。mid 上限 40 → 6。
                val aMd = autoMd.coerceIn(1f, 6f)
                val aHi = autoHi.coerceIn(1f, 8f)
                for (k in 0 until bucket) {
                    midPks[k] *= aMd
                    highPks[k] *= aHi
                }
                Log.i(TAG, "waveform band balance: ${path.takeLast(24)} pLo=$pLo pMd=$pMd pHi=$pHi " +
                    "-> mid x$aMd high x$aHi (after fixed ${BAND_MID_GAIN}x/${BAND_HIGH_GAIN}x)")
            }
            val diag = normalize(peaks, bucket)

            // 【V8.453】Opus/M4A 可信度校验。
            //
            // readSampleData 的输出**不保证是 16bit PCM**。实测 MP3/FLAC 能拿到
            // 真实波形（Android extractor 在这两条路径上会解码），但 Opus 与
            // M4A(AAC-in-MP4) 的容器/编码组合更复杂，部分实现会直接返回压缩帧。
            // 按 16bit 解读压缩比特流的结果 = 随机起伏 —— 有"形状"但不是音乐，
            // 而且**不会报任何错**，是最难发现的一类假数据。
            //
            // 判据用**包络的峰谷比**（p98/p50）：
            //   真实音乐 RMS 动态p98/p50 通常 > 1.8（有强拍和弱段的对比）
            //   压缩比特流按采样解读时各帧长度相近→ 能量近似恒定 → 比值接近 1.0
            //
            // 不可信时返回 null：宁可画单色，也不画一条假波形。
            val sortedPeaks = peaks.copyOf(bucket).apply { sort() }
            val p50 = sortedPeaks[bucket / 2]
            val p98 = sortedPeaks[((bucket * 98) / 100).coerceIn(0, bucket - 1)]
            val dynRatio = if (p50 > 1e-6f) p98 / p50 else 0f
            val compressedFormat = mime.contains("opus", true) ||
                    mime.contains("mp4a", true) || mime.contains("aac", true) ||
                    mime.contains("m4a", true) || mime.contains("3gpp", true)
            // 【V8.455 修"WAV 没有波纹"】mime=audio/raw 就是**未压缩 PCM**（WAV/AIFF），
            // MediaExtractor 返回的一定是真采样点，**根本不需要可信度校验**。
            //
            // 真机日志（V8.455）证实这是误杀：
            //   waveform: UNTRUSTWORTHY envelope (p98/p50=1.2635 <1.35) mime=audio/raw
            // 一个 3 秒的测试片段（正弦/短采样）动态本来就低，被我按"比特流"处理丢掉了。
            // 顺带：连 audio/mpeg 也出现过 1.037 被杀 —— 短 MP3 同样会中招。
            //
            // 校验只对**压缩格式**有意义（那里才可能拿到未解码的比特流）。
            val isUncompressedPcm = mime.equals("audio/raw", true) ||
                    mime.startsWith("audio/L16", true) || mime.contains("pcm", true)
            // 【V8.461 修"MP3 没有波纹"】阈值从 1.35 降到 0.62，并加格式分级。
            //
            // 为什么 1.35 会误杀 MP3：p98/p50 是**每桶 RMS** 的比值，
            // 而 RMS 天然压缩动态（1400 个桶各自取平均），
            // 正常无损也只能到 1.4~2.0。MP3 更低 —— 编码器砍高频、抽瞬态、
            // 母带又被Loudness War 压过，实测 p98/p50 只有 **1.037**。
            // 这个值离 1.0 太近，"1.35" 完全是凭FLAC 样本定的，没有 MP3 样本。
            //
            // 真正能区分"比特流"和"PCM"的判据是**样本值分布**，不是包络动态：
            //   未解码的压缩比特流按 16bit 解读 → 样本近似均匀分布，
            //     直方图是平的，且相邻样本几乎不相关；
            //   真实音频（含 MP3 解码后）→ 钟形分布，低幅值样本远多于高幅值。
            // 用"零交叉率"这个O(1) 指标：真实音乐 44.1kHz 下ZCR 通常 0.02~0.20，
            // 随机比特流接近 0.5。这个判据与包络动态无关，不会误杀任何真实音频。
            //
            // 双条件才判不可信：包络动态极低 **且** 零交叉率高。
            // 单独的任一项都不足以定罪 —— 这是 V8.455 教训的直接应用。
            val zeroCrossRate = if (zcrTotal > 0) zcrCross.toFloat() / zcrTotal.toFloat() else 0f
            val isBitstream = !isUncompressedPcm &&
                             dynRatio < FAKE_WAVE_DYN_MAX &&
                             zeroCrossRate > BITSTREAM_ZCR_MIN
            if (isBitstream) {
                // 无论是不是压缩格式，动态这么低都不可信：
                // 真实音乐不可能p98/p50 < 1.35（哪怕是极平的老母带也远高于此）。
                Log.w(TAG, "waveform: UNTRUSTWORTHY envelope (p98/p50=$dynRatio <$FAKE_WAVE_DYN_MAX) " +
                        "zcr=${"%.3f".format(zeroCrossRate)}(>$BITSTREAM_ZCR_MIN) " +
                        "mime=$mime compressed=$compressedFormat -> discard (likely raw bitstream, not PCM)")
                return null
            }
            Log.i(TAG, "waveform fmt: mime=$mime sr=$sampleRate ch=$chCount dyn(p98/p50)=${"%.2f".format(dynRatio)} " +
                    "zcr=${"%.3f".format(zeroCrossRate)} compressed=$compressedFormat -> envelope OK")
            // 【V8.436/437】三段各自独立归一化（各自的 p98，互不干扰）
            val kDiag = if (kickPks != null) normalize(kickPks, bucket) else ""
            val mDiag = if (midPks != null) normalize(midPks, bucket) else ""
            val hDiag = if (highPks != null) normalize(highPks, bucket) else ""
            // 【V8.463】覆盖率诊断：bucket < buckets 就说明没扫完（尾部空白）。
            // stride>1 时 coveredSec 不再等于曲长，要按 stride 还原。
            val coveredSec = if (sampleRate > 0) (bucket * framesPerBucket) / sampleRate else 0
            val totalSec = if (durationUs > 0) (durationUs / 1000000L) else 0
            val coverPct = if (totalSec > 0) (coveredSec * 100) / totalSec else 100
            if (bucket < buckets || bucketStride > 1) {
                Log.w(TAG, "waveform: COVERAGE $bucket/$buckets buckets " +
                        "stride=$bucketStride fpb=$framesPerBucket need=$framesPerBucketRaw " +
                        "covered=${coveredSec}s/${totalSec}s ($coverPct%) " +
                        (if (bucket < buckets) "**INCOMPLETE -> tail will be blank**" else "ok"))
            }
            Log.i(TAG, "waveform: $path -> $bucket/$buckets buckets, sr=$sampleRate ch=$chCount " +
                    "durUs=$durationUs fpb=$framesPerBucket stride=$bucketStride " +
                    "coveredSec=$coveredSec/${totalSec}s ($coverPct%)")
            Log.i(TAG, "waveform dyn: $diag")
            // 【V8.436/437】三段结果存缓存（要在 return 之前！）
            if (kickPks != null && bucket > 0) {
                kickCache[path] = if (bucket == buckets) kickPks else kickPks.copyOf(bucket)
                Log.i(TAG, "waveform kick(low): $path -> $bucket buckets, $kDiag")
            }
            if (midPks != null && bucket > 0) {
                midCache[path] = if (bucket == buckets) midPks else midPks.copyOf(bucket)
                Log.i(TAG, "waveform mid: $path -> $bucket buckets, $mDiag")
            }
            if (highPks != null && bucket > 0) {
                highCache[path] = if (bucket == buckets) highPks else highPks.copyOf(bucket)
                Log.i(TAG, "waveform high: $path -> $bucket buckets, $hDiag")
            }
            if (harmPks != null && bucket > 0) {
                harmCache[path] = if (bucket == buckets) harmPks else harmPks.copyOf(bucket)
                Log.i(TAG, "waveform harm: $path -> $bucket buckets, ${normalize(harmPks, bucket)}")
            }
            return if (bucket == buckets) peaks else peaks.copyOf(bucket)

        } catch (e: Exception) {
            Log.w(TAG, "waveform failed for $path: ${e.message}")
            Log.w(TAG, "waveform failed stack: ${android.util.Log.getStackTraceString(e)}")
            return null
        } finally {
            runCatching { ex.release() }
        }
    }

    /**
     * 归一化到 0..1。
     * 用 98 百分位而不是绝对最大值：单个爆音会让整首歌的波形压扁成一条线。
     */
    /**
     * 【V8.472】桶内谐波度（归一化自相关峰值），0~1。
     *
     * 判的是"这一段里有没有**持续稳定**的周期波"。人声（尤其元音）自相关峰
     * 通常在 0.6~0.95；钢琴/吉他/pluck 是 attack-decay，整桶平均后多在 0.2~0.5；
     * 鼓与镲（噪声类）接近 0。
     *
     * 只关心基频 80~400Hz（人声/旋律乐器的音域），所以先抽取降采样到 ~8kHz，
     * 自相关 lag 只需扫 20~100，算力可控：约 1.2e5 次乘加/桶。
     */
    private fun harmonicity(shorts: ShortArray, nFrames: Int, chCount: Int, sampleRate: Int): Float {
        val step = (sampleRate / 8000).coerceAtLeast(1)
        val m = nFrames / step
        if (m < 256 || chCount < 1) return 0f
        val x = FloatArray(m)
        var mean = 0.0
        var i = 0
        while (i < m) {
            val v = shorts[i * step * chCount].toFloat() / 32768f
            x[i] = v
            mean += v.toDouble()
            i++
        }
        mean /= m
        var e0 = 0.0
        i = 0
        while (i < m) {
            val d = (x[i] - mean.toFloat())
            x[i] = d
            e0 += (d * d).toDouble()
            i++
        }
        if (e0 < 1e-9) return 0f
        val srD = sampleRate.toDouble() / step.toDouble()
        val lagMin = (srD / 400.0).toInt().coerceAtLeast(2)
        val lagMax = (srD / 80.0).toInt().coerceAtMost(m / 2 - 1)
        if (lagMax <= lagMin) return 0f
        var best = 0.0
        var lag = lagMin
        while (lag <= lagMax) {
            var s = 0.0
            val lim = m - lag
            var k = 0
            while (k < lim) { s += (x[k] * x[k + lag]).toDouble(); k++ }
            val r = s / e0
            if (r > best) best = r
            lag++
        }
        return best.toFloat().coerceIn(0f, 1f)
    }

    private fun normalize(peaks: FloatArray, n: Int): String {
        if (n <= 0) return "n=0"
        val sorted = peaks.copyOf(n).apply { sort() }
        val p98 = sorted[(n * 98 / 100).coerceIn(0, n - 1)]
        val p50 = sorted[(n * 50 / 100).coerceIn(0, n - 1)]
        val p05 = sorted[(n * 5 / 100).coerceIn(0, n - 1)]
        // 用 p98 而不是最大值做基准：单个爆音会把整首歌压成一条线。
        val scale = if (p98 > 0.0001f) 1f / p98 else 1f
        for (i in 0 until n) {
            val v = peaks[i] * scale
            peaks[i] = if (v > 1f) 1f else v
        }
        // 返回诊断串：一眼看出动态范围是否合理
        val mean = (0 until n).sumOf { k -> peaks[k].toDouble() } / n
        return "p05=%.3f p50=%.3f p98=%.3f scale=%.1f meanAfter=%.3f".format(
            p05, p50, p98, scale, mean
        )
    }
}
