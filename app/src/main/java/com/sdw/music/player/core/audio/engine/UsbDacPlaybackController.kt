package com.sdw.music.player.core.audio.engine
import com.sdw.music.player.core.audio.DebugLog
import com.sdw.music.player.core.audio.analysis.MediaProbe
import com.sdw.music.player.core.audio.engine.UsbDacManager
import com.sdw.music.player.core.audio.engine.UsbDacPlaybackController

import android.content.Context
import com.sdw.music.player.utils.CueParser
import com.sdw.music.player.utils.CueParser.CueTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer

class UsbDacPlaybackController(
    private val onCompletion: () -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "UsbDacPlayback"
        const val DEFAULT_SAMPLE_RATE = 48000
        const val DEFAULT_CHANNELS = 2
        const val DEFAULT_BITS = 16  // TTGK DAC output 16-bit (Salt verified)
        private const val TIMEOUT_US = 10000L
        const val PREBUFFER_TARGET_MS = 200L  // 【V3.3.21】从 500ms 降到 200ms，减少切歌延迟
        // [V3.3.4] streamLoop only consumes whole URBs (44.1k≈353 / 48k=384 / 96k=768 frames);
        // an EOS tail smaller than one URB can never drain to 0 and was stalling auto-next
        // for the full 10s timeout. Anything below this is unplayable residue — treat as drained.
        private const val EOS_DRAIN_RESIDUE_FRAMES = 1024
        // [2026-09-10 C] Cross-rate reclaim deep prebuffer. A cross-rate switch does a full
        // release + reclaim, so the supply gap right after startStreaming is wider than a
        // same-rate keep-claim switch. 500ms of ring absorbs it (was the V3.3.21 value).
        const val CROSS_RATE_PREBUFFER_MS = 500L
        /** [2026-09-10 C] Normal (same-rate / first-claim) prebuffer target. */
        const val DEFAULT_PREBUFFER_MS = PREBUFFER_TARGET_MS

        // [V9.20 ②] Stall self-heal tuning (hoisted from the monitor loops so both
        // alac/flac/onPushStall share one source of truth).
        // 经验教训（9.20ad 实测）：STALL_STATS_ONLY=true 把 forceReset 自愈全关后，stall 只统计、
        // 到 STALL_GIVEUP_AT 直接 onError 跳下一首 —— 开环漂移本就容易触发 stall，等于撤掉唯一能
        // 救活的手段，表现为「歌放不出/跳到下一首」的功能性回退。因此改回有界自愈：stall 到阈值
        // 仍 forceReset（取消卡住的 URB），最多 STALL_MAX_RECOVER 次，仍保留 STALL_GIVEUP_AT 硬止损。
        const val STALL_RECOVER_AT = 40      // 连续 stall ~2s(50ms/poll) 触发一次自愈
        const val STALL_GIVEUP_AT  = 120     // 连续 stall ~6s → 干净停止(报错而非永久冻结)
        const val STALL_MAX_RECOVER = 3      // 自愈最大尝试次数（超限后不再 forceReset，等硬止损）
        const val STALL_STATS_ONLY = false   // [V9.20 ②] false=保留有界自愈（恢复"以前能放"的行为）
    }


    // ============================================================
    // 【方向1】DAC 会话状态机（2026-09-07 一期）
    // 权威状态：所有控制源（UI/解码线程/看门狗/热插拔）只能通过
    // requestState() 请求迁移，禁止直接改布尔标志。
    // 非法迁移 → 拒绝 + 日志（不产生未定义行为）。
    // 资源顺序编进迁移动作（resetRingBuffer 只许流停时执行、
    // nativeRelease 前强制 join+flacStop），从注释约定变结构强制。
    // ============================================================
    enum class DacState {
        IDLE,          // 无 claim、无流、无解码线程
        OPEN,          // claim 已建立，未开始预缓冲
        PREBUFFER,     // 解码线程预缓冲中（native 流未启）
        STREAMING,     // native 流已启，播放中
        EOS_HOLD,      // 自然播完，流停、claim 保留，等 playNext
        PAUSED,        // 用户暂停，流停、claim 保留
        RELEASING,     // 停止中（join 解码线程 + 释放 native）
        ERROR          // 解码/开流失败（不自动切歌，产品决策）
    }

    // 允许的迁移表：from -> set(to)
    private val ALLOWED: Map<DacState, Set<DacState>> = mapOf(
        DacState.IDLE to setOf(DacState.OPEN, DacState.ERROR),
        DacState.OPEN to setOf(DacState.PREBUFFER, DacState.IDLE, DacState.ERROR),
        DacState.PREBUFFER to setOf(DacState.STREAMING, DacState.PAUSED, DacState.IDLE, DacState.RELEASING, DacState.ERROR, DacState.EOS_HOLD),
        DacState.STREAMING to setOf(DacState.PAUSED, DacState.IDLE, DacState.RELEASING, DacState.EOS_HOLD, DacState.ERROR, DacState.PREBUFFER),
        DacState.EOS_HOLD to setOf(DacState.PREBUFFER, DacState.IDLE, DacState.RELEASING, DacState.ERROR),
        DacState.PAUSED to setOf(DacState.PREBUFFER, DacState.IDLE, DacState.RELEASING, DacState.ERROR),
        DacState.RELEASING to setOf(DacState.IDLE, DacState.ERROR),
        DacState.ERROR to setOf(DacState.OPEN, DacState.IDLE, DacState.PREBUFFER)
    )

    @Volatile var isPlaying = false; private set
    @Volatile var positionMs = 0L; private set
    val durationMs get() = extractorDurationMs
    @Volatile var sourceSampleRate = 0; private set
    @Volatile var sourceBits = 16; private set
    @Volatile var sourceChannelCount = 0; private set
    /** [2026-09-10 C] Effective ring prebuffer target for this controller instance. */
    @Volatile var prebufferTargetMs: Long = PREBUFFER_TARGET_MS
    @Volatile private var currentFilePath = ""

    private var decodeThread: Thread? = null
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var extractorDurationMs = 0L
    private var audioTrackIndex = -1
    private var dacSampleRate = DEFAULT_SAMPLE_RATE
    private var dacChannels = DEFAULT_CHANNELS


    // 【方向1】权威会话状态（唯一真源，禁止手改）
    @Volatile private var state = DacState.IDLE
    val sessionState: DacState get() = state
    @Volatile private var stateGen = 0  // 每次成功迁移自增，供 generation 守卫

    // 迁移唯一入口：非法迁移拒绝 + 日志
    private fun requestState(to: DacState, reason: String): Boolean {
        val from = state
        if (from == to) return true  // 幂等
        val allowed = ALLOWED[from] ?: emptySet()
        if (to !in allowed) {
            DebugLog.add(TAG, "[state] ILLEGAL ${from} -> ${to} (${reason}) — REJECTED")
            return false
        }
        state = to
        stateGen++
        DebugLog.v(TAG, "[state] ${from} -> ${to} (${reason}) gen=$stateGen")
        return true
    }
    // 语义断言：resetRingBuffer 只许在流停状态调用
    private fun assertRingResetSafe(reason: String) {
        val s = state
        if (s == DacState.STREAMING) {
            DebugLog.add(TAG, "[state] VIOLATION resetRingBuffer during $s ($reason) — 必须流停后调用")
        }
    }

    private val pauseLock = Object()
    @Volatile private var paused = false
    @Volatile private var shouldStop = false
    // [V9.20n] Session generation: stamped from UsbDacManager.beginSession() in play().
    // Any self-heal path (STALL recovery / pushPcm stall) checks it first: a stale
    // session must exit quietly instead of forceReset-ing the CURRENT session's stream.
    @Volatile private var myGen = 0
    @Volatile private var outputPcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT

    // 【V3.2.7】WAV 直读路径：Android raw decoder 会把 24bit 转成 16bit，
    // 真 Bit-Perfect 必须自己解 RIFF 头直读 PCM，绕开 MediaCodec
    // 【V3.3.0】FLAC 直解：native libFLAC 硬解直入 ring，绕开 Moto MediaCodec 降级
    @Volatile private var flacDirect = false

    // 【2026-08-25】ALAC 直解：native Apple codec + MP4 box 解析直入 ring，绕开 MediaCodec
    @Volatile private var alacDirect = false

    // 【V9.20af】对外标记：本 controller 当前是否持有进程级 native FLAC/ALAC 单例解码器
    // （gFlac/gAlac）。open() 已把该单例换成本曲文件，而 detachForReuse() 内的
    // flacStop()/alacStop() 会顺手关掉这个全局解码器并把 gEos 置真，导致新曲立即 EOS 跳歌。
    // 因此该类格式在 MusicService 的 ⑤ 会话复用判定中必须被排除，退化到完整重开路径。
    val isNativeSingletonDecode: Boolean
        get() = flacDirect || alacDirect

    // 【V3.3.0】CUE 整轨模式：同 FLAC 相邻轨 gapless 切换（不重建 decoder）
    @Volatile private var cueDirect = false

    // 【V3.2.7】WAV 直读路径
    @Volatile private var wavDirect = false
    private var currentCueTrackIndex = -1
    private var cueTrackList: List<CueParser.CueTrack> = emptyList()
    private var wavFile: java.io.RandomAccessFile? = null
    private var wavDataStart = 0L
    private var wavDataSize = 0L
    private var wavBlockAlign = 0
    private var wavFmtCode = 1      // 1=PCM int, 3=IEEE float
    @Volatile private var wavReadBytes = 0L

    /** 解析 RIFF/WAVE 头，成功返回 true 并填充 wav* 字段与 source* 属性 */
    private fun parseWavHeader(filePath: String): Boolean {
        return try {
            val raf = java.io.RandomAccessFile(filePath, "r")
            val hdr = ByteArray(12)
            raf.readFully(hdr)
            if (String(hdr, 0, 4) != "RIFF" || String(hdr, 8, 4) != "WAVE") { raf.close(); return false }
            var fmtFound = false; var dataFound = false
            val chunkHdr = ByteArray(8)
            while (!(fmtFound && dataFound)) {
                if (raf.filePointer + 8 > raf.length()) break
                raf.readFully(chunkHdr)
                val cid = String(chunkHdr, 0, 4)
                val csize = ((chunkHdr[4].toInt() and 0xFF) or ((chunkHdr[5].toInt() and 0xFF) shl 8) or
                        ((chunkHdr[6].toInt() and 0xFF) shl 16) or ((chunkHdr[7].toInt() and 0xFF) shl 24)).toLong() and 0xFFFFFFFFL
                when (cid) {
                    "fmt " -> {
                        val fmt = ByteArray(csize.toInt().coerceAtMost(40))
                        raf.readFully(fmt)
                        if (csize > fmt.size) raf.skipBytes((csize - fmt.size).toInt())
                        // 【V8.207】chunk 一律按偶数字节对齐（RIFF 规定奇数大小的 chunk 后面补 1 字节）。
                        // 漏掉这一字节会让后面所有 chunk 的 id 都错位，fmt/data 直接找不到。
                        if (csize and 1L == 1L) raf.skipBytes(1)
                        fun u16(o: Int) = (fmt[o].toInt() and 0xFF) or ((fmt[o + 1].toInt() and 0xFF) shl 8)
                        fun u32(o: Int) = (fmt[o].toInt() and 0xFF) or ((fmt[o + 1].toInt() and 0xFF) shl 8) or
                                ((fmt[o + 2].toInt() and 0xFF) shl 16) or ((fmt[o + 3].toInt() and 0xFF) shl 24)
                        var code = u16(0)
                        sourceChannelCount = u16(2)
                        sourceSampleRate = u32(4)
                        wavBlockAlign = u16(12)
                        sourceBits = u16(14)
                        if (code == 0xFFFE && fmt.size >= 26) code = u16(24)  // WAVE_FORMAT_EXTENSIBLE
                        wavFmtCode = code
                        fmtFound = true
                    }
                    "data" -> {
                        wavDataStart = raf.filePointer
                        wavDataSize = if (csize <= 0L || raf.filePointer + csize > raf.length()) raf.length() - raf.filePointer else csize
                        dataFound = true
                        if (!fmtFound) raf.skipBytes(wavDataSize.toInt().coerceAtMost(Int.MAX_VALUE))
                    }
                    else -> raf.skipBytes(csize.toInt() + (csize.toInt() and 1))  // 奇数对齐
                }
            }
            if (!fmtFound || !dataFound || wavBlockAlign <= 0 || sourceSampleRate <= 0 ||
                (wavFmtCode != 1 && wavFmtCode != 3)) { raf.close(); return false }
            // 【V8.207】blockAlign 与「声道×位深」对不上时记一笔 —— 这种文件正是以前会读错位的那类
            if (wavBlockAlign != sourceChannelCount * (sourceBits / 8)) {
                DebugLog.add(TAG, "wav: blockAlign=$wavBlockAlign != ch*bits/8=${sourceChannelCount * (sourceBits / 8)} — trust bits=$sourceBits")
            }
            raf.seek(wavDataStart)
            wavFile = raf; wavReadBytes = 0L
            extractorDurationMs = wavDataSize * 1000L /
                (sourceSampleRate.toLong() * sourceChannelCount * (sourceBits / 8).coerceAtLeast(1))
            true
        } catch (e: Exception) {
            DebugLog.add(TAG, "parseWavHeader fail: ${e.message}")
            try { wavFile?.close() } catch (_: Exception) {}
            wavFile = null; false
        }
    }

    /** 直读一块 PCM 并转 float；返回空数组表示 EOF */
    private fun wavReadChunk(maxFrames: Int): FloatArray {
        val raf = wavFile ?: return FloatArray(0)
        val remaining = wavDataSize - wavReadBytes
        if (remaining <= 0) return FloatArray(0)
        // 【V8.207】位深以 fmt 的 wBitsPerSample 为准，不再用 blockAlign/channels 反推。
        // 有些 WAV（转码器/录音笔写的）blockAlign 字段填错、或与位深对不上，反推出的
        // 每样本字节数就会错位 —— 24bit 数据被当 16bit 读，听感就是持续的电子杂音。
        // 帧长同样按位深算（blockAlign 只用来做一次合理性校验），读多少字节也跟着它走。
        val bytesPerSample = when (sourceBits) {
            8 -> 1
            16 -> 2
            24 -> 3
            32 -> 4
            else -> (wavBlockAlign / sourceChannelCount).coerceAtLeast(1)
        }
        val frameBytes = (bytesPerSample * sourceChannelCount).coerceAtLeast(1)
        val bytesWanted = (maxFrames.toLong() * frameBytes).coerceAtMost(remaining).toInt()
        val alignedBytes = bytesWanted - (bytesWanted % frameBytes)
        if (alignedBytes <= 0) return FloatArray(0)
        val bytes = ByteArray(alignedBytes)
        return try {
            raf.readFully(bytes)
            wavReadBytes += alignedBytes
            val sampleCount = alignedBytes / bytesPerSample
            when {
                // 【V8.207】8bit WAV 是 unsigned offset binary（128 = 0），按有符号读会整体偏移半幅
                bytesPerSample == 1 -> FloatArray(sampleCount) { i ->
                    ((bytes[i].toInt() and 0xFF) - 128).toFloat() / 128.0f
                }
                wavFmtCode == 3 && bytesPerSample == 4 -> FloatArray(sampleCount) { i ->
                    java.lang.Float.intBitsToFloat(
                        (bytes[i * 4].toInt() and 0xFF) or ((bytes[i * 4 + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[i * 4 + 2].toInt() and 0xFF) shl 16) or (bytes[i * 4 + 3].toInt() shl 24))
                }
                bytesPerSample == 3 -> FloatArray(sampleCount) { i ->
                    val v = (bytes[i * 3].toInt() and 0xFF) or ((bytes[i * 3 + 1].toInt() and 0xFF) shl 8) or
                            (bytes[i * 3 + 2].toInt() shl 16)
                    v.toFloat() / 8388608.0f
                }
                bytesPerSample == 4 -> FloatArray(sampleCount) { i ->
                    val v = (bytes[i * 4].toInt() and 0xFF) or ((bytes[i * 4 + 1].toInt() and 0xFF) shl 8) or
                            ((bytes[i * 4 + 2].toInt() and 0xFF) shl 16) or (bytes[i * 4 + 3].toInt() shl 24)
                    v.toFloat() / 2147483648.0f
                }
                else -> FloatArray(sampleCount) { i ->
                    val v = ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8))
                    v.toFloat() / 32768.0f
                }
            }
        } catch (e: Exception) {
            DebugLog.add(TAG, "wavReadChunk err: ${e.message}"); FloatArray(0)
        }
    }

    fun open(
        filePath: String,
        dacSampleRate: Int = DEFAULT_SAMPLE_RATE,
        dacChannels: Int = DEFAULT_CHANNELS
    ): Boolean {
        // 【方向1】open 前必须先释放旧会话（幂等：已在非 IDLE 则先回 IDLE）
        if (state != DacState.IDLE && state != DacState.ERROR) {
            DebugLog.add(TAG, "[state] open() during $state — force teardown first")
            forceTeardown("open: re-session")
        }
        releaseResources()
        this.dacSampleRate = dacSampleRate
        this.dacChannels = dacChannels
        this.currentFilePath = filePath
        requestState(DacState.OPEN, "open()")

        // 【V3.2.7】WAV 直读：绕开 MediaCodec（raw decoder 会把 24bit 降成 16bit）
        wavDirect = false
        if (filePath.endsWith(".wav", ignoreCase = true) && parseWavHeader(filePath)) {
            wavDirect = true
            this.dacSampleRate = sourceSampleRate  // [V3.3.4] true rate from RIFF header
            positionMs = 0
            DebugLog.add(TAG, "open(wavDirect): sr=$sourceSampleRate ch=$sourceChannelCount bits=$sourceBits fmt=$wavFmtCode blockAlign=$wavBlockAlign dataSize=$wavDataSize dur=${extractorDurationMs}ms")
            return true
        }

        // 【V3.3.0】FLAC 直解：libFLAC 吐真实位深样点（24bit FLAC → bpf=6.00 → alt=2）
        if (filePath.endsWith(".flac", ignoreCase = true) && UsbDacManager.flacOpen(filePath)) {
            val info = UsbDacManager.flacInfo()
            if (info[0] > 0) {
                flacDirect = true
                sourceSampleRate = info[0]
                this.dacSampleRate = info[0]  // [V3.3.4] true rate from STREAMINFO
                sourceChannelCount = info[1]
                sourceBits = info[2]
                extractorDurationMs = info[3].toLong()
                positionMs = 0
                DebugLog.add(TAG, "open(flacDirect): sr=$sourceSampleRate ch=$sourceChannelCount bits=$sourceBits (TRUE ${sourceBits}bit via libFLAC) dur=${extractorDurationMs}ms")
                // 【V3.3.0】尝试加载同名 CUE：同目录同名 .cue 文件
                val cuePath = filePath.substringBeforeLast('.') + ".cue"
                val cue = CueParser.parse(cuePath)
                if (cue != null && cue.filePath == filePath && cue.tracks.isNotEmpty()) {
                    cueDirect = true
                    DebugLog.add(TAG, "open: CUE loaded ${cue.tracks.size} tracks: ${cue.tracks.joinToString { it.title ?: "Track ${it.number}" }}")
                    cueTrackList = cue.tracks
                    currentCueTrackIndex = 0
                }
                return true
            }
            DebugLog.add(TAG, "open(flacDirect): flacOpen OK but no STREAMINFO, fallback MediaCodec")
        }

        // 【2026-08-25】ALAC 直解：Apple codec + 手搓 MP4 box 解析（Bit-Perfect，绕开 MediaCodec）
        // 【2026-09-02 fix】m4a 无法播放根因：m4a 是容器格式，可能是 AAC/ALAC/其他编码。
        // 原来对 .m4a 一律先试 alacOpen() —— AAC m4a 会白复制大文件（主线程 ANR 风险）
        // 且 stsd 解析失败后才 fallback，慢且偶发失败。现在先快速读 mime（毫秒级），
        // 只有真 ALAC（audio/alac）才走直解，其他编码直接走 MediaCodec。
        if ((filePath.endsWith(".m4a", ignoreCase = true) || filePath.endsWith(".alac", ignoreCase = true) ||
             filePath.endsWith(".mp4", ignoreCase = true))) {
            // 【V8.429】短命mime 探测收敛到单线程闸门（native abort 详见 MediaProbe 类注释）。
            // 注意只包这一段：下面那个长命 extractor 整个播放会话都持有，不能进闸门。
            val isAlacMime = try {
                MediaProbe.onProbeThread {
                    val ex = MediaExtractor()
                    try {
                        ex.setDataSource(filePath)
                        var m = false
                        for (i in 0 until ex.trackCount) {
                            val fmt = ex.getTrackFormat(i)
                            if (fmt.getString(MediaFormat.KEY_MIME) == "audio/alac") { m = true; break }
                        }
                        m
                    } finally { runCatching { ex.release() } }
                }
            } catch (_: Exception) { false }
            DebugLog.v(TAG, "open: m4a container, mime=audio/alac? $isAlacMime")
            if (isAlacMime && UsbDacManager.alacOpen(filePath)) {
                val info = UsbDacManager.alacInfo()
                if (info[0] > 0) {
                    alacDirect = true
                    sourceSampleRate = info[0]
                    this.dacSampleRate = info[0]
                    sourceChannelCount = info[1]
                    sourceBits = info[2]
                    extractorDurationMs = info[3].toLong()
                    positionMs = 0
                    DebugLog.add(TAG, "open(alacDirect): sr=$sourceSampleRate ch=$sourceChannelCount bits=$sourceBits dur=${extractorDurationMs}ms")
                    return true
                }
                DebugLog.add(TAG, "open(alacDirect): alacOpen OK but no config, fallback MediaCodec")
            }
        }

        return try {
            DebugLog.add(TAG, "open: $filePath")
            val ex = MediaExtractor()
            ex.setDataSource(filePath)
            extractor = ex

            audioTrackIndex = -1
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { audioTrackIndex = i; ex.selectTrack(i); break }
            }
            if (audioTrackIndex < 0) { requestState(DacState.ERROR, "open: no audio track"); onError("No audio track"); releaseResources(); return false }

            val fmt = ex.getTrackFormat(audioTrackIndex)
            sourceSampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            this.dacSampleRate = sourceSampleRate  // [V3.3.4] true rate from extractor
            sourceChannelCount = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"
            // 【修复】KEY_DURATION 单位是微秒，必须 /1000 转毫秒，否则时长显示大 1000 倍
            extractorDurationMs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1000L else 0L

            // 【V3.2.7】源位深检测：FLAC/WAV 高解析源 → 24bit 输出（alt2）
            sourceBits = when {
                fmt.containsKey("bits-per-sample") -> fmt.getInteger("bits-per-sample")
                fmt.containsKey(MediaFormat.KEY_PCM_ENCODING) -> when (fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                    android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                    android.media.AudioFormat.ENCODING_PCM_32BIT -> 32
                    android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
                    else -> 16
                }
                else -> 16
            }

            DebugLog.add(TAG, "open: mime=$mime srcSr=$sourceSampleRate srcCh=$sourceChannelCount srcBits=$sourceBits dacSr=$dacSampleRate dacCh=$dacChannels dur=${extractorDurationMs}ms")

        val codec = MediaCodec.createDecoderByType(mime)
        // 【多声道修复】DAC 只有 2ch alt；源 >2ch(5.1/7.1 的 AAC/Opus/FLAC，如电影音轨 m4a)时
        // 必须让 decoder 输出 2ch，否则 decodeLoop 的 fc=f.size/dacChannels(2) 会把 6ch 数据
        // 当 2ch 解释 → 帧数错乱、播放速度/内容全乱。Android decoder 支持按 KEY_CHANNEL_COUNT
        // 请求 downmix(AAC/Opus/FLAC 均支持 5.1→2.0)。
        val decoderChannels = if (sourceChannelCount > 2) 2 else sourceChannelCount
        val cfgFmt = MediaFormat.createAudioFormat(mime, sourceSampleRate, decoderChannels)
            val isRawPassthrough = mime == "audio/raw"
            // 【V3.2.9】FLAC 24bit 解码：MediaExtractor/MediaCodec 可能把 24bit 降成 16bit
            // 但 outputFormat 打假标签 enc=21，需要实测 bpf (bytes-per-frame) 判断真实位深
            // 不再降级 ExoPlayer，直接实测+强制 override
            if (sourceBits > 16 && !isRawPassthrough) {
                // 压缩格式高解析源：要求解码器输出 float，保留 >16bit 精度
                // audio/raw 是直通“解码器”，不做格式转换，请求 float 会被无视→按源格式解析
                cfgFmt.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_FLOAT)
            }
            // 【修复 M4A/AAC 无声】audio/mp4a-latm 的 M4A 内是 raw AAC 帧（无 ADTS 头），
            // 解码器必须靠 csd-0(AudioSpecificConfig) 才知道对象类型/采样率/声道。
            // 缺 csd-0 -> dequeueOutputBuffer 永远 TRY_AGAIN_LATER -> prebuffer 0 帧无声。
            // MP3 帧自同步不需要，无 csd 键所以不受影响。
            if (fmt.containsKey("csd-0")) cfgFmt.setByteBuffer("csd-0", fmt.getByteBuffer("csd-0"))
            if (fmt.containsKey("csd-1")) cfgFmt.setByteBuffer("csd-1", fmt.getByteBuffer("csd-1"))
            // 【P7-OPUS 修复】完整拷贝 codec specific data（对齐 Oboe 路径 copyCsdBuffers）：
            // csd-2 = CodecDelay/SeekPreRoll（Opus in MP4 等），max-input-size 尤其关键——
            // Opus/Vorbis 解码器需要按包大小预留输入缓冲，缺 max-input-size → 缓冲过小 →
            // "work failed to complete: 14"（无法处理包）→ 落点咔哒/无声。正常(Oboe)路径有此拷贝，
            // 故 OPUS 仅在 DAC(直解 MediaCodec)路径失败。
            if (fmt.containsKey("csd-2")) cfgFmt.setByteBuffer("csd-2", fmt.getByteBuffer("csd-2"))
            // 【P8-OPUS 关键修复】OPUS/Vorbis/AAC 等压缩格式必须显式设 KEY_MAX_INPUT_SIZE。
            // 根因：MediaExtractor 的 track format 通常【不】带 KEY_MAX_INPUT_SIZE（Oboe 路径
            // copyCsdBuffers 也只条件拷贝、同样依赖源自带），若不兜底显式设置，解码器输入缓冲
            // 被默认成极小 → 首包解码失败(work failed:14)→ 解码器进入 ERROR 自释放 → 之后
            // dequeueOutputBuffer 抛 "Pending dequeue output buffer request cancelled" /
            // "Invalid to call at Released state"。源自带且为正则优先用，否则给安全兜底值。
            val maxInFromSrc = runCatching { fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrNull()
            val maxInDefault = when {
                mime == "audio/opus" || mime == "audio/vorbis" -> 256 * 1024
                mime == "audio/mp4a-latm" || mime == "audio/aac" -> 64 * 1024
                mime == "audio/mpeg" -> 64 * 1024
                else -> 64 * 1024
            }
            if (maxInFromSrc != null && maxInFromSrc > 0) {
                cfgFmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInFromSrc)
                DebugLog.v(TAG, "open: max-input-size from extractor = $maxInFromSrc ($mime)")
            } else {
                cfgFmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInDefault)
                DebugLog.v(TAG, "open: max-input-size not in extractor format for $mime — using safe default $maxInDefault")
            }
            codec.configure(cfgFmt, null, null, 0)
            codec.start()
            outputPcmEncoding = if (isRawPassthrough) {
                // 直通路径：输出就是源格式，按源位深解析，不信 outputFormat
                when (sourceBits) {
                    24 -> android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED
                    32 -> android.media.AudioFormat.ENCODING_PCM_32BIT
                    else -> android.media.AudioFormat.ENCODING_PCM_16BIT
                }
            } else try {
                codec.outputFormat.takeIf { it.containsKey(MediaFormat.KEY_PCM_ENCODING) }
                    ?.getInteger(MediaFormat.KEY_PCM_ENCODING) ?: android.media.AudioFormat.ENCODING_PCM_16BIT
            } catch (_: Exception) { android.media.AudioFormat.ENCODING_PCM_16BIT }
            this.codec = codec
            DebugLog.v(TAG, "open: codec started OK, outEnc=$outputPcmEncoding raw=$isRawPassthrough")
            true
        } catch (e: Exception) {
            DebugLog.add(TAG, "open FAIL: ${e.message}")
            requestState(DacState.ERROR, "open FAIL: ${e.message}")
            onError("Failed to open: ${e.message}")
            releaseResources()
            false
        }
    }

    /**
     * Gapless 播放：CUE 整轨时 targetSample 指定从第几帧开始（0=从头）
     * 同文件相邻轨：flacGaplessSeek 内部只做 sample seek（不重建 decoder），无断流
     * 跨文件：flacGaplessSeek 内部 close+open+seek（比 Kotlin 层 open 快）
     */
    fun play(targetSample: Long = 0L, streamAlreadyRunning: Boolean = false) {
        // 【方向1】从以下状态可进入 PREBUFFER（幂等：已在 PREBUFFER/STREAMING 直接忽略）
        val from = state
        if (from == DacState.PREBUFFER || from == DacState.STREAMING) return
        if (!requestState(DacState.PREBUFFER, "play($from)")) return
        if (!wavDirect && !flacDirect && !alacDirect && (codec == null || extractor == null)) {
            requestState(DacState.ERROR, "play: not ready")
            onError("Not ready"); return
        }
        // [V9.20n] Stamp a fresh session generation BEFORE any decode/monitor thread
        // starts. Older sessions' threads fail the gen check and exit quietly — they
        // must never forceReset (that kills the current session's live stream).
        myGen = UsbDacManager.beginSession()
        // [V9.20p] 上报源位深/源率：DAC 信息条的音质标识按源算（MP3=16bit 不亮 Hi-Res），
        // 而不是线位深（32bit alt 打包会把有损显示成 Hi-Res）。
        UsbDacManager.activeSourceBits = sourceBits
        UsbDacManager.activeSourceRate = sourceSampleRate
        shouldStop = false; paused = false; isPlaying = true

        decodeThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            // [v6.0.13] keep-claim: skip reset when stream alive, avoid glitch from mid-stream memset
            if (!streamAlreadyRunning) UsbDacManager.resetRingBuffer()

            // 【2026-08-25】ALAC 直解：native 解码线程自行填 ring，Kotlin 只做预缓冲等待+监控（同 flac）
            if (alacDirect) {
                // [NeriPlayer 引擎] pull 模式：无 native 解码线程，Kotlin 拉块喂 session pipeline
                if (UsbDacManager.useNeriEngine()) {
                    val alacOutBits = if (sourceBits > 16) 24 else playbackWireBits
                    if (!streamAlreadyRunning && !UsbDacManager.startStreaming(dacSampleRate, dacChannels, alacOutBits)) {
                        requestState(DacState.ERROR, "play(alac/neri): startStreaming FAIL")
                        onError("startStreaming FAIL"); return@Thread
                    }
                    requestState(DacState.STREAMING, "play(alac/neri): pull loop")
                    neriPullDecodeLoop(isFlac = false)
                    DebugLog.v(TAG, "play(alac/neri): neriPullDecodeLoop RETURNED")
                    return@Thread
                }
                UsbDacManager.alacStart()
                val ringTarget = ((dacSampleRate * prebufferTargetMs) / 1000L).toInt()
                val t0 = System.currentTimeMillis()
                // 【V9.20p】3s→1.5s，同 flac 路径：切歌体感优先，不等完美预缓冲
                while (!shouldStop && UsbDacManager.getRingFill() < ringTarget &&
                    !UsbDacManager.alacIsEos() && System.currentTimeMillis() - t0 < 1500) {
                    try { Thread.sleep(10) } catch (_: InterruptedException) { return@Thread }
                }
                DebugLog.v(TAG, "play(alac): prebuffer ${UsbDacManager.getRingFill()} frames in ${System.currentTimeMillis() - t0}ms")
                // 【V8.11 fix】ALAC 直解位深必须用 wireBits（DAC 硬件 alt 位深），
                // 不能用源位深——单 alt 24bit DAC（如 Qudelix-5K）收 16bit 打包会字节错位爆音
                val alacOutBits = if (sourceBits > 16) 24 else playbackWireBits
                // [V9.20 ⑤] streamAlreadyRunning=true → 复用已打开的 native 流（⑤ 会话复用），跳过 startStreaming 重开
                if (!streamAlreadyRunning && !UsbDacManager.startStreaming(dacSampleRate, dacChannels, alacOutBits)) {
                    requestState(DacState.ERROR, "play(alac): startStreaming FAIL")
                    onError("startStreaming FAIL"); return@Thread
                }
                requestState(DacState.STREAMING, "play(alac): stream started")
                DebugLog.v(TAG, "play(alac): DAC stream started, bits=$alacOutBits")
                alacMonitorLoop()
                DebugLog.v(TAG, "play(alac): alacMonitorLoop RETURNED")
                return@Thread
            }

            // 【V3.3.0】FLAC 直解：native 解码线程自行填 ring，Kotlin 只做预缓冲等待+监控
            if (flacDirect) {
                // [NeriPlayer 引擎] pull 模式：无 native 解码线程，Kotlin 拉块喂 session pipeline
                if (UsbDacManager.useNeriEngine()) {
                    if (targetSample > 0) {
                        // 同文件相邻轨：只设 pending seek，pull 循环首次读取时消费
                        if (!UsbDacManager.flacGaplessSeek(currentFilePath, targetSample)) {
                            DebugLog.add(TAG, "play(flacGapless/neri): seek FAIL")
                        }
                    }
                    val flacOutBits = if (sourceBits > 16) 24 else playbackWireBits
                    if (!streamAlreadyRunning && !UsbDacManager.startStreaming(dacSampleRate, dacChannels, flacOutBits)) {
                        requestState(DacState.ERROR, "play(flac/neri): startStreaming FAIL")
                        onError("startStreaming FAIL"); return@Thread
                    }
                    requestState(DacState.STREAMING, "play(flac/neri): pull loop")
                    neriPullDecodeLoop(isFlac = true)
                    DebugLog.v(TAG, "play(flac/neri): neriPullDecodeLoop RETURNED")
                    return@Thread
                }
                // Gapless seek：同文件相邻轨不重建 decoder，跨文件比 Kotlin open 更快
                if (targetSample > 0) {
                    DebugLog.v(TAG, "play(flacGapless): path=${currentFilePath.take(60)} sample=$targetSample")
                    // 更新当前轨索引（CUE 手动选曲）
                    if (cueDirect) {
                        val idx = cueTrackList.indexOfFirst { track ->
                            val trackStart = CueParser.cdFramesToSamples(track.index01Samples, sourceSampleRate)
                            kotlin.math.abs(trackStart - targetSample) < sourceSampleRate * 2 // ±2s 容差
                        }
                        if (idx >= 0) currentCueTrackIndex = idx
                    }
                    if (!UsbDacManager.flacGaplessSeek(currentFilePath, targetSample)) {
                        DebugLog.add(TAG, "play(flacGapless): seek FAIL, falling back to flacStart")
                        UsbDacManager.flacStop()
                        UsbDacManager.flacStart()
                    }
                } else {
                    UsbDacManager.flacStart()
                }

                val ringTarget = ((dacSampleRate * prebufferTargetMs) / 1000L).toInt()
                val t0 = System.currentTimeMillis()
                // 【V9.20p】3s→1.5s：预缓冲目标是 ~200ms 数据，等 3 秒纯属过度设计。
                // 起播不等完美预缓冲——soft-start 150ms 淡入 + 边播边填兜底，切歌体感优先。
                while (!shouldStop && UsbDacManager.getRingFill() < ringTarget &&
                    !UsbDacManager.flacIsEos() && System.currentTimeMillis() - t0 < 1500) {
                    try { Thread.sleep(10) } catch (_: InterruptedException) { return@Thread }
                }
                DebugLog.v(TAG, "play(flac): prebuffer ${UsbDacManager.getRingFill()} frames in ${System.currentTimeMillis() - t0}ms")
                // [对齐 Salt] 硬件音量已恢复（FU id=16 ch1/ch2 在 DAC 芯片内衰减，不损 SNR）。
                // 16bit 源走 alt=1/16bit 直出，不做软件衰减则无量化噪声；24bit 源才走 alt=2。
                // 【V8.11 fix】FLAC 直解位深必须用 wireBits（DAC 硬件 alt 位深），
                // 不能用源位深——单 alt 24bit DAC（如 Qudelix-5K）收 16bit 打包会字节错位爆音
                val flacOutBits = if (sourceBits > 16) 24 else playbackWireBits
                // [V9.20 ⑤] streamAlreadyRunning=true → 复用已打开的 native 流，跳过 startStreaming 重开
                if (!streamAlreadyRunning && !UsbDacManager.startStreaming(dacSampleRate, dacChannels, flacOutBits)) {
                    requestState(DacState.ERROR, "play(flac): startStreaming FAIL")
                    onError("startStreaming FAIL"); return@Thread
                }
                requestState(DacState.STREAMING, "play(flac): stream started")
                DebugLog.v(TAG, "play(flac): DAC stream started, bits=$flacOutBits (TRUE bit-depth) nativeInfo=${UsbDacManager.getDetailedDacInfo()?.take(120)}")
                DebugLog.v(TAG, "play(flac): entering flacMonitorLoop...")
                flacMonitorLoop()
                DebugLog.v(TAG, "play(flac): flacMonitorLoop RETURNED (shouldStop=$shouldStop shouldRestart=${!shouldStop && !paused})")
                return@Thread
            }

            // Start DAC stream FIRST, then pre-buffer.
            // 【fix14】反序(preBuffer→startStreaming)是 WAV/MP3 切歌 6 秒不出声的根因：
            // 流未启动时 native writePlayerPcm 立即拒写(source!=PlayerPCM 返回 0)，
            // 而 NeriUsbSession.writeFloats 把"拒写"当"ring 满背压"每 2ms 重试、
            // 死等 STALL_TIMEOUT(2s)/chunk 才放弃 → 200ms 预缓冲(≈2 chunk)白等 4-6s。
            // 先起流后写入：soft-start 150ms 淡入 + 边播边填兜底(与 FLAC pull 路径同一策略)。
            // 流认 float 输入(prepare 声明 ENCODING_PCM_FLOAT)，位深只选 DAC alt，
            // 故起流先后不影响"声明位深 vs 喂入数据"一致性；enc16Forced 实测仍有效。
            // 【V3.2.9】Fail-Fast 位深联动：decoder 实出 16bit（enc16Forced）→ 诚实走 alt=1/16bit
            val outBits = if (sourceBits > 16 && !enc16Forced) 24 else 16
            if (outBits > playbackWireBits) playbackWireBits = outBits  // [v6.0.14] but never downgrade S32_LE
            if (sourceBits > 16 && enc16Forced) {
                DebugLog.add(TAG, "FAIL-FAST: source ${sourceBits}bit but platform decoder outputs 16bit — " +
                    "DAC honest mode alt=1/16bit (true 24bit needs native FLAC decode)")
            }
            val started = if (streamAlreadyRunning) true else UsbDacManager.startStreaming(dacSampleRate, dacChannels, playbackWireBits)
            if (!started) {
                requestState(DacState.ERROR, "play: startStreaming FAIL")
                onError("startStreaming FAIL"); return@Thread
            }
            requestState(DacState.STREAMING, "play: stream started (wavDirect=$wavDirect)")
            DebugLog.v(TAG, "play: DAC stream started, entering prebuffer+decode loop (wavDirect=$wavDirect)")

            // Pre-buffer AFTER stream start: writes now land in the live ring immediately (no stall).
            val targetFrames = ((dacSampleRate * prebufferTargetMs) / 1000L).toInt() * dacChannels
            val preBufFrames = preBuffer(targetFrames)
            DebugLog.v(TAG, "play: prebuffer done, $preBufFrames frames")
            if (wavDirect) wavDecodeLoop() else decodeLoop()
        }, "UsbDacDecode").apply { isDaemon = true; start() }
    }

    // 【V3.2.7】可听位置 = 解码位置 - ring 缓冲量（解码领先播放 ~3s，进度条必须用这个）
    @Volatile private var pausedAtMs = -1L
    val audiblePositionMs: Long
        get() {
            if (pausedAtMs >= 0) return pausedAtMs
            if (dacSampleRate <= 0) return positionMs
            val ringMs = UsbDacManager.getRingFill() * 1000L / dacSampleRate
            return (positionMs - ringMs).coerceAtLeast(0L)
        }

    fun pause() {
        // 【方向1】只允许从 PREBUFFER/STREAMING 暂停
        val from = state
        if (from != DacState.PREBUFFER && from != DacState.STREAMING) return
        paused = true; isPlaying = false
        // 先算可听位置再停流；只停 streamLoop，保留 USB claim（stopAndRelease 会丢 claim）
        val ringMs = if (dacSampleRate > 0) UsbDacManager.getRingFill() * 1000L / dacSampleRate else 0L
        pausedAtMs = (positionMs - ringMs).coerceAtLeast(0L)
        if (flacDirect) UsbDacManager.flacPause(true)  // 【V3.3.0】挂起 native 解码线程
        if (alacDirect) UsbDacManager.alacPause(true)  // 【2026-08-25】挂起 native 解码线程
        UsbDacManager.pauseStream()
        requestState(DacState.PAUSED, "pause($from)")
        DebugLog.add(TAG, "pause at ${pausedAtMs}ms (decodePos=${positionMs}ms ring=${ringMs}ms)")
    }

    fun resume() {
        // 【方向1】只允许从 PAUSED 恢复
        val from = state
        if (from != DacState.PAUSED) return
        if (!requestState(DacState.PREBUFFER, "resume($from)")) return
        // 【V3.2.7】暂停后恢复必须先清 ring buffer，再开流。
        // stopThreadOnly 不清 ring 数据，直接开流新旧数据踩踏导致左声道噪音
        assertRingResetSafe("resume")
        UsbDacManager.resetRingBuffer()
        // 回到可听位置重解（解码线程此时在 pauseLock.wait() 中挂起，直接 flush 安全）
        pendingSeekMs = -1L
        if (pausedAtMs >= 0) performSeek(pausedAtMs)

        // [爆音修复] 先唤醒解码线程预缓冲（streamLoop 仍停，resetRingBuffer 无竞争）
        paused = false; shouldStop = false
        if (flacDirect) UsbDacManager.flacPause(false)
        synchronized(pauseLock) { (pauseLock as java.lang.Object).notifyAll() }

        // 等 ring 预缓冲 ~200ms，避免开流瞬间 underrun + 数据跳变爆音
        val ringTarget = ((dacSampleRate * prebufferTargetMs) / 1000L).toInt()
        val t0 = System.currentTimeMillis()
        // 【V9.20p】2s→1s：resume 同样优先体感，soft-start 兜底
        while (!shouldStop && UsbDacManager.getRingFill() < ringTarget && System.currentTimeMillis() - t0 < 1000) {
            try { Thread.sleep(10) } catch (_: InterruptedException) { break }
        }

        // 【V8.11 fix】resume 位深同样用 wireBits（DAC 硬件 alt 位深）
        val outBits = if (sourceBits > 16) 24 else playbackWireBits
        if (!UsbDacManager.startStreaming(dacSampleRate, dacChannels, outBits)) {
            requestState(DacState.ERROR, "resume: startStreaming FAIL")
            DebugLog.add(TAG, "resume: startStreaming FAIL"); onError("resume FAIL"); return
        }
        requestState(DacState.STREAMING, "resume: stream started")
        pausedAtMs = -1L
        isPlaying = true
        DebugLog.add(TAG, "resume from ${positionMs}ms (bits=$outBits)")
    }

    fun stop() {
        // 【方向1】任何状态都可 stop（幂等；IDLE 已无资源可停）
        if (state == DacState.IDLE) return
        requestState(DacState.RELEASING, "stop(${state})")
        shouldStop = true; paused = false; isPlaying = false
        synchronized(pauseLock) { (pauseLock as java.lang.Object).notifyAll() }
        // 【V9.20】先停流放行堵在 pushPcm 的解码线程，再 join + 释放，避免 codec 竞争冻死
        UsbDacManager.pauseStream()
        decodeThread?.interrupt(); decodeThread?.join(2000); decodeThread = null
        // 【方向1】RELEASING 动作内强制顺序：先 join 解码线程（上面）→ flacStop（releaseResources 内）→ stopAndRelease
        releaseResources()  // flacStop/alacStop join native 解码线程 + codec/extractor 释放（在 stopAndRelease 前！）
        UsbDacManager.stopAndRelease()
        requestState(DacState.IDLE, "stop done")
        DebugLog.v(TAG, "stop")
    }

    fun stopDecode() {
        // 【方向1】语义对齐：releaseUsbDacController 调用点 → 保留 claim 的停止 = EOS_HOLD
        // 允许从 PREBUFFER/STREAMING/PAUSED/ERROR 进入（已停则幂等）
        if (state == DacState.IDLE || state == DacState.OPEN) return
        if (state == DacState.PAUSED || state == DacState.EOS_HOLD) return  // 已是保留态，幂等
        requestState(DacState.EOS_HOLD, "stopDecode(${state})")
        shouldStop = true; paused = false; isPlaying = false
        synchronized(pauseLock) { (pauseLock as java.lang.Object).notifyAll() }
        // 【V9.20】先停流（放行堵在 pushPcm 的解码线程）再 join，避免 codec 被从活跃线程脚下抽走
        UsbDacManager.pauseStream()
        decodeThread?.interrupt(); decodeThread?.join(600); decodeThread = null  // [V3.3.3] shorter join, avoid main-thread ANR
        assertRingResetSafe("stopDecode")
        UsbDacManager.resetRingBuffer()  // 【V3.3.16】清空残留数据，避免切歌噪音
        releaseResources()
        DebugLog.v(TAG, "stopDecode (DAC stream paused, ring cleared) state=$state")
    }

    // [v6.0.15] Same-rate keep-claim: kill old monitor + release old decoder, new controller takes over.
    // 【修复 2026-08-25】两首歌一起放根因：旧版只 interrupt Kotlin monitor 线程，
    // 但 monitor 的 finally 块因 shouldStop=true 跳过 releaseResources()，
    // 导致旧格式的 native 解码线程（flacStop/alacStop）没被停 → 继续 push 旧歌 PCM 与新歌交织。
    // 必须：停 monitor → 停 streamLoop（pauseStream，保留 claim）→ 清 ring → 停旧 native 解码线程。
    // 顺序不能乱：resetRingBuffer 必须在 streamLoop 停了之后，否则 mid-stream memset 与读并发爆音。
    // 新歌 play(streamAlreadyRunning=false) 会重新 startStreaming（same-rate keep-stream 幂等重启）。
    fun stopMonitor() {
        // 【方向1】同 stopDecode 语义：保留 claim 停止（keep-claim 切歌专用）
        if (state == DacState.IDLE || state == DacState.OPEN) return
        if (state == DacState.PAUSED || state == DacState.EOS_HOLD) return
        // [V9.20n] Invalidate this session's generation IMMEDIATELY: from this point
        // any still-alive decode/monitor thread of ours fails the gen check in its
        // self-heal paths and exits quietly — it can no longer forceReset the stream
        // that the next song is about to start (that race was the switch freeze).
        myGen = -1
        requestState(DacState.EOS_HOLD, "stopMonitor(${state})")
        shouldStop = true; paused = false; isPlaying = false
        synchronized(pauseLock) { (pauseLock as java.lang.Object).notifyAll() }
        // 【V9.20 切歌冻结修复】MP3/AAC/WAV 走 Kotlin decodeLoop，旧解码线程很可能正堵在
        // native pushPcm（ring 满，usleep 等 USB 消费）。必须先 pauseStream（native
        // streaming_=false）把它放出来，再有界 join，最后才 releaseResources——
        // 老顺序是"不 join 直接抽 codec"，活跃解码线程脚下的 MediaCodec/Extractor
        // 被 release → 竞争冻死（用户观感：MP3 无法切歌）。
        UsbDacManager.pauseStream()  // stop streamLoop (keep USB claim); unblocks pushPcm waiters
        decodeThread?.interrupt()
        // [V9.20 v2] 500ms was too tight: MP3 decodeLoop can be inside a MediaCodec
        // dequeue (up to 10s timeout) when we land here; a timed-out join leaves the
        // thread alive holding the codec -> releaseResources() below then races it.
        // 1500ms covers the dequeue timeout; on true timeout log it (daemon thread
        // still dies with the process, and codec ops are guarded below).
        val joinStart = System.currentTimeMillis()
        decodeThread?.join(1500)
        if (decodeThread != null && decodeThread!!.isAlive) {
            DebugLog.add(TAG, "stopMonitor: decode thread still alive after 1500ms join (state=${decodeThread?.state}) — proceeding with guarded release")
        }
        decodeThread = null
        assertRingResetSafe("stopMonitor")
        UsbDacManager.resetRingBuffer()  // 清旧歌残留 PCM（streamLoop 已停，安全）
        releaseResources()  // 停旧格式 native 解码线程（flacStop/alacStop/codec.stop）+ 释放 extractor
        DebugLog.v(TAG, "stopMonitor (monitor killed, stream paused, ring cleared, decoder released, claim kept) state=$state")
    }

    // [V9.20 ⑤] Detach this controller from the live native stream for SESSION REUSE:
    // stop the decode thread + release per-controller decode resources, but DO NOT
    // pauseStream / resetRing / stop the shared native streaming thread. The next
    // controller reuses that still-running stream via play(streamAlreadyRunning=true),
    // eliminating the reopen latency on a same-format track switch (vs stopMonitor's
    // full pauseStream+resetRing+reclaim path).
    fun detachForReuse() {
        if (state == DacState.IDLE || state == DacState.OPEN) return
        if (state != DacState.EOS_HOLD) requestState(DacState.EOS_HOLD, "detachForReuse(${state})")
        // [V9.20n] invalidate generation so any still-alive decode thread exits quietly
        // (no forceReset on the stream we're about to hand to the next controller)
        myGen = -1
        shouldStop = true; paused = false; isPlaying = false
        synchronized(pauseLock) { (pauseLock as java.lang.Object).notifyAll() }
        decodeThread?.interrupt()
        decodeThread?.join(1500)
        if (decodeThread != null && decodeThread!!.isAlive) {
            DebugLog.add(TAG, "detachForReuse: decode thread still alive after 1500ms join — proceeding with guarded release")
        }
        decodeThread = null
        // releaseResources() 停旧格式 native 解码线程(flacStop/alacStop/codec.stop)+释放 extractor，
        // 但不碰 streaming 线程 / 不清 ring / 不 pauseStream —— 活动流保留给下一首复用。
        releaseResources()
        DebugLog.v(TAG, "detachForReuse: decode thread stopped, native stream KEPT running for reuse (claim kept)")
    }

    // 【V3.2.8】seek 改为挂起请求：主线程直接 codec.flush() 会和解码线程的
    // dequeueOutputBuffer 撞车 → IllegalStateException → finally onCompletion → 误切下一曲
    @Volatile private var pendingSeekMs = -1L

    fun seekTo(timeMs: Long) {
        // 【2026-09-07】入口 clamp：拖过结尾的 seek 目标（如 UI 拖到 63min）直接钳到
        // 时长内，避免 native drflac_seek_to_pcm_frame 对越界/末尾帧 seek 失败
        // → 旧行为失败跳回 0（"跳回开头"惊吓）。留 1s 余量避开精确末尾帧。
        val dur = extractorDurationMs
        val clamped = if (dur > 2000L) timeMs.coerceIn(0L, dur - 1000L) else timeMs.coerceAtLeast(0L)
        if (clamped != timeMs) DebugLog.v(TAG, "seekTo ${timeMs}ms clamped to ${clamped}ms (dur=${dur}ms)")
        positionMs = clamped
        if (pausedAtMs >= 0) { pausedAtMs = clamped; return }  // 暂停中：resume 时统一执行
        if (flacDirect) { UsbDacManager.flacSeek(clamped); return }  // 【V3.3.0】native 线程内安全执行
        if (alacDirect) { UsbDacManager.alacSeek(clamped); return }  // 【2026-08-25】native 线程内安全执行
        pendingSeekMs = clamped  // 播放中：解码线程在循环顶部安全执行
        DebugLog.v(TAG, "seekTo request ${clamped}ms (deferred to decode thread)")
    }

    private fun performSeek(timeMs: Long) {
        if (flacDirect) {
            // 【V3.3.0】seek 提交给 native 解码线程（线程内安全执行 seek_absolute + ring 重置）
            UsbDacManager.flacSeek(timeMs)
            positionMs = timeMs
            DebugLog.v(TAG, "seekTo(flac) ${timeMs}ms → native pending")
            return
        }
        if (wavDirect) {
            val raf = wavFile ?: return
            val targetBytes = (timeMs * sourceSampleRate.toLong() * wavBlockAlign / 1000L)
                .coerceIn(0L, wavDataSize).let { it - it % wavBlockAlign }
            try {
                raf.seek(wavDataStart + targetBytes)
                wavReadBytes = targetBytes
                positionMs = targetBytes * 1000L / (sourceSampleRate.toLong() * wavBlockAlign)
                DebugLog.v(TAG, "seekTo(wav) ${timeMs}ms → ${positionMs}ms")
            } catch (e: Exception) { DebugLog.add(TAG, "seekTo(wav) err: ${e.message}") }
            return
        }
        val ex = extractor ?: return; val cd = codec ?: return
        carry24 = ByteArray(0)  // seek 后数据不连续，残留字节作废
        cd.flush()
        ex.seekTo(timeMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        positionMs = ex.sampleTime / 1000L
        DebugLog.v(TAG, "performSeek ${timeMs}ms → actual ${positionMs}ms")
    }

    // ============================================================
    // Internal
    // ============================================================

    // 【方向1】强制拆除：open 遇已存在会话时用（不等解码线程自然退出，立即停）
    private fun forceTeardown(reason: String) {
        if (state == DacState.IDLE) return
        requestState(DacState.RELEASING, reason)
        shouldStop = true; paused = false; isPlaying = false
        synchronized(pauseLock) { (pauseLock as java.lang.Object).notifyAll() }
        // 【V9.20】先停流放行堵在 pushPcm 的解码线程，再 join + 释放，避免 codec 竞争冻死
        UsbDacManager.pauseStream()
        decodeThread?.interrupt(); decodeThread?.join(1000); decodeThread = null
        releaseResources()
        UsbDacManager.stopAndRelease()
        requestState(DacState.IDLE, "$reason done")
    }

    private fun releaseResources() {
        if (flacDirect) { UsbDacManager.flacStop(); flacDirect = false; cueDirect = false; currentCueTrackIndex = -1; cueTrackList = emptyList() }  // 【V3.3.0】join native 解码线程，清理 CUE 状态
        if (alacDirect) { UsbDacManager.alacStop(); alacDirect = false }  // 【2026-08-25】join native 解码线程
        // [V9.20 v2] Guard every codec op: if the decode thread somehow survived the
        // join above and is inside a codec call, stop()/release() throw
        // IllegalStateException — which used to propagate up and freeze the switch.
        try { codec?.stop() } catch (e: Exception) { DebugLog.add(TAG, "releaseResources: codec.stop() guarded: ${e.javaClass.simpleName}") }
        try { codec?.release() } catch (e: Exception) { DebugLog.add(TAG, "releaseResources: codec.release() guarded: ${e.javaClass.simpleName}") }
        codec = null
        try { extractor?.release() } catch (e: Exception) { DebugLog.add(TAG, "releaseResources: extractor.release() guarded: ${e.javaClass.simpleName}") }
        extractor = null
        try { wavFile?.close() } catch (_: Exception) {}
        wavFile = null; wavDirect = false; wavReadBytes = 0L
        audioTrackIndex = -1; extractorDurationMs = 0L
        carry24 = ByteArray(0)
        enc16Forced = false; _bufDiagCount = 0; _bufDiagLastPts = -1L; _hexDumpDone = 0
    }

    // 【2026-08-25】ALAC 直解监控循环：native 线程自行解码+推流，Kotlin 只同步进度/检测 EOS 排空（同 flac）
    private fun alacMonitorLoop() {
        var lastPosMs = 0L; var stallCount = 0; var stallTotal = 0; var lastDebug = 0L; var lastMetricsLog = 0L
        val prebufferGraceMs = 3000L
        // 【V9.20 A/卡死自愈】STALL 有界自愈：playback 位长时间不动 = USB OUT 端点 stall / DAC 不消费。
        // 原监控只报不救 → 永久卡死 + 无限刷 STALL 日志。这里加有界自愈 + 硬上限止损。
        // [V9.20 ②] 自愈常量已上提到 companion object；STALL_STATS_ONLY=true 时只统计不 forceReset。
        var recoveryAttempts = 0
        var prebufferEndTime = System.currentTimeMillis() + prebufferGraceMs
        try {
            var drainWaitMs = 0
            while (!shouldStop) {
                if (paused) {
                    synchronized(pauseLock) { while (paused && !shouldStop) (pauseLock as java.lang.Object).wait() }
                }
                if (shouldStop) break
                // [V9.20x] Surprise removal: native URB submission saw consecutive ENODEV and
                // latched "device vanished". Not a stall — forceReset would hammer a dead
                // endpoint. Run the detach path so the position is remembered for auto-resume.
                if (UsbDacManager.pollDeviceVanished()) {
                    DebugLog.add(TAG, "alacMonitor: USB device vanished (ENODEV) — detach path, no self-heal")
                    releaseResources(); isPlaying = false
                    UsbDacManager.pauseStream()
                    requestState(DacState.ERROR, "usb device removed")
                    onError("USB DAC removed")
                    shouldStop = true
                    break
                }
                val pos = UsbDacManager.alacPositionMs()
                positionMs = pos
                if (pos == lastPosMs && !paused) {
                    if (System.currentTimeMillis() < prebufferEndTime) {
                        Thread.sleep(50)
                        continue
                    }
                    stallCount++; stallTotal++
                    if (stallCount >= 4) DebugLog.add(TAG, "alacMonitor: STALL pos=$pos stallCount=$stallCount ringFill=${UsbDacManager.getRingFill()} alacEos=${UsbDacManager.alacIsEos()}")
                    // [V9.20n] Stale-session gate (see flacMonitorLoop): an old session's
                    // forceReset would kill the current session's live stream.
                    if (myGen != UsbDacManager.sessionGen) {
                        DebugLog.add(TAG, "alacMonitor: stale session (gen=$myGen cur=${UsbDacManager.sessionGen}) — exiting quietly")
                        shouldStop = true
                        break
                    }
                    // 【V9.20 A】有界自愈：到阈值先 forceReset(取消卡住的 URB，native 设计的总线复位兜底)，
                    // 给一个观察窗口；耗尽次数仍不前进则硬止损(干净停止+报错，不再无限卡死)。
                    // [V9.20 ②] STALL_STATS_ONLY=true 时只统计不自愈：记录一次 stall 自愈被跳过，不动流。
                    if (stallCount == STALL_RECOVER_AT && recoveryAttempts < STALL_MAX_RECOVER) {
                        recoveryAttempts++
                        if (STALL_STATS_ONLY) {
                            DebugLog.add(TAG, "alacMonitor: STALL recovery SKIPPED (stats-only; forceReset disabled) attempt #$recoveryAttempts")
                        } else {
                            DebugLog.add(TAG, "alacMonitor: STALL recovery attempt #$recoveryAttempts (forceReset cancels wedged URBs)")
                            UsbDacManager.forceReset()
                            stallCount = 0
                        }
                        Thread.sleep(50); continue
                    }
                    if (stallCount >= STALL_GIVEUP_AT) {
                        DebugLog.add(TAG, "alacMonitor: STALL unrecoverable after $recoveryAttempts recovery attempt(s); stopping playback")
                        releaseResources(); isPlaying = false
                        UsbDacManager.pauseStream()
                        requestState(DacState.ERROR, "playback stalled (USB endpoint not consuming)")
                        onError("USB stall: DAC stopped consuming audio")
                        shouldStop = true
                        break
                    }
                } else {
                    stallCount = 0
                    if (pos != 0L) prebufferEndTime = 0
                }
                lastPosMs = pos
                if (UsbDacManager.alacIsEos()) {
                    if (UsbDacManager.getRingFill() <= EOS_DRAIN_RESIDUE_FRAMES) {
                        DebugLog.add(TAG, "alacMonitor: EOS drain complete"); break
                    }
                    drainWaitMs += 50
                    if (drainWaitMs > 10_000) { DebugLog.add(TAG, "alacMonitor: drain timeout"); break }
                }
                val now = System.currentTimeMillis()
                if (now - lastDebug > 10_000) { DebugLog.v(TAG, "alacMonitor: pos=${pos}ms ring=${UsbDacManager.getRingFill()} eos=${UsbDacManager.alacIsEos()}"); lastDebug = now }
                // 【V9.20x】每 5s 打印一次运行时诊断指标（借鉴 NeriPlayer RuntimeMetrics），便于排查爆音/卡顿/无声
                if (now - lastMetricsLog > 5000) {
                    lastMetricsLog = now
                    val m = UsbDacManager.getRuntimeMetrics()
                    val peakLin = if (m.channel0Peak >= m.channel1Peak) m.channel0Peak else m.channel1Peak
                    UsbDacManager.checkLoudPlayback(peakLin)
                    DebugLog.add(TAG, "metrics: underrun=${m.underrunCount} bpEv=${m.backpressureEvents} bpMax=${m.backpressureMaxMs}ms zeroFill=${m.zeroFillBytes}B sig=${m.signalFrames} sil=${m.silentFrames} peakL=${"%.2f".format(m.channel0Peak)} peakR=${"%.2f".format(m.channel1Peak)} drop=${m.droppedBytes}B stallBrk=${m.stallBreaks} stallTot=$stallTotal vanish=${m.vanishEvents}")
                }
                Thread.sleep(50)
            }
        } catch (_: InterruptedException) {
            DebugLog.v(TAG, "alacMonitor: interrupted (shouldStop=$shouldStop)")
        } catch (e: Exception) {
            if (!shouldStop) { requestState(DacState.ERROR, "alacMonitor: ${e.message}"); DebugLog.add(TAG, "alacMonitor err: ${e.message}"); onError("Decode: ${e.message}") }
        } finally {
            if (!shouldStop) {
                releaseResources(); isPlaying = false
                UsbDacManager.pauseStream()
                requestState(DacState.EOS_HOLD, "alacMonitor: EOS")
                DebugLog.v(TAG, "alacMonitorLoop done, calling onCompletion")
                onCompletion()
            } else {
                // 被 stop/stopDecode 中止：状态由调用方迁移（stop→RELEASING→IDLE / stopDecode→EOS_HOLD）
                DebugLog.v(TAG, "alacMonitorLoop aborted (shouldStop=true) state=$state")
            }
        }
    }

    // 【V3.3.0】FLAC 直解监控循环：native 线程自行解码+推流，Kotlin 只同步进度/检测 EOS 排空
    /**
     * [NeriPlayer 引擎] FLAC/ALAC pull 模式主循环。
     * native 单例解码 → 拉块 → pushPcm（neri session pipeline：背压/重采样/位深转换）。
     * 与 flacMonitorLoop/alacMonitorLoop 二选一；EOS 排空后同样走
     * EOS_HOLD + onCompletion 收尾，会话保留给下一曲（零间隙切歌的关键）。
     */
    private fun neriPullDecodeLoop(isFlac: Boolean) {
        val codec = if (isFlac) "flac" else "alac"
        // FLAC chunk 上限 4096 帧；ALAC packet ≤ frameLen（通常 4096）。按 8 声道上限分配。
        val buf = FloatArray(4096 * 8)
        var lastPosMs = -1L
        var stallMs = 0L
        var lastMetricsLog = 0L
        try {
            while (!shouldStop) {
                if (paused) {
                    synchronized(pauseLock) { while (paused && !shouldStop) (pauseLock as java.lang.Object).wait() }
                }
                if (shouldStop) break

                // 设备掉线检测（neri：runtimeReport 的 deviceOnline / terminalFailure）
                if (UsbDacManager.neriDeviceOffline()) {
                    DebugLog.add(TAG, "neriPull($codec): USB device offline — detach path")
                    releaseResources(); isPlaying = false
                    UsbDacManager.pauseStream()
                    requestState(DacState.ERROR, "usb device removed")
                    onError("USB DAC removed")
                    shouldStop = true
                    break
                }

                val n = if (isFlac) UsbDacManager.flacReadChunk(buf, 4096)
                        else UsbDacManager.alacReadChunk(buf, 4096)
                if (n < 0) { try { Thread.sleep(30) } catch (_: InterruptedException) { break }; continue }  // 暂停中
                if (n == 0) {
                    // EOS：重采样排空 + 等环形缓冲排空
                    UsbDacManager.neriDrain()
                    val t0 = System.currentTimeMillis()
                    while (!shouldStop && UsbDacManager.getRingFill() > EOS_DRAIN_RESIDUE_FRAMES &&
                        System.currentTimeMillis() - t0 < 10_000) {
                        try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                    }
                    // CUE gapless：同文件下一轨（pending seek，下次 read 消费）
                    if (cueDirect && currentCueTrackIndex >= 0 && isFlac) {
                        val cueTracks = cueTrackList
                        if (currentCueTrackIndex < cueTracks.size - 1) {
                            currentCueTrackIndex++
                            val nextTrack = cueTracks[currentCueTrackIndex]
                            val startSample = CueParser.cdFramesToSamples(
                                nextTrack.index00Samples ?: nextTrack.index01Samples, sourceSampleRate)
                            if (UsbDacManager.flacGaplessSeek(currentFilePath, startSample)) continue
                            DebugLog.add(TAG, "neriPull(flac): CUE gapless seek FAIL, ending")
                        }
                    }
                    DebugLog.add(TAG, "neriPull($codec): EOS drain complete")
                    break
                }

                val pushed = UsbDacManager.pushPcm(buf, n)
                if (pushed < 0) {
                    // 【fix15】切歌停流中(shouldStop)：push 失败是预期(stopMonitor 只 join 600ms，
                    // pushPcm stall 超时 2s 期间新 controller 已接管会话)——静默退出，
                    // 不进 ERROR/onError，避免旧线程毒化新曲的全局播放状态。
                    if (shouldStop) {
                        DebugLog.v(TAG, "neriPull($codec): pushPcm=$pushed during stop — silent exit")
                        break
                    }
                    DebugLog.add(TAG, "neriPull($codec): pushPcm=$pushed — session lost")
                    releaseResources(); isPlaying = false
                    UsbDacManager.pauseStream()
                    requestState(DacState.ERROR, "pcm push failed")
                    onError("PCM push failed")
                    shouldStop = true
                    break
                }

                val pos = if (isFlac) UsbDacManager.flacPositionMs() else UsbDacManager.alacPositionMs()
                positionMs = pos
                if (pos == lastPosMs) stallMs += 50 else { stallMs = 0; lastPosMs = pos }
                // stall 仅在原生明确 terminal failure 时止损——neri 自带恢复 latch，不 forceReset
                if (stallMs >= 10_000 && UsbDacManager.neriTerminalFailed()) {
                    DebugLog.add(TAG, "neriPull($codec): terminal failure after ${stallMs}ms stall — stopping")
                    releaseResources(); isPlaying = false
                    UsbDacManager.pauseStream()
                    requestState(DacState.ERROR, "playback stalled (transport failed)")
                    onError("USB transport failed")
                    shouldStop = true
                    break
                }

                val now = System.currentTimeMillis()
                if (now - lastMetricsLog > 5000) {
                    lastMetricsLog = now
                    val peak = UsbDacManager.neriOutputPeak()
                    UsbDacManager.checkLoudPlayback(peak)
                    DebugLog.add(TAG, "neriPull($codec): pos=${pos}ms queued=${UsbDacManager.getRingFill()} " +
                        "eos=${if (isFlac) UsbDacManager.flacIsEos() else UsbDacManager.alacIsEos()} peak=${"%.2f".format(peak)}")
                }
            }
        } catch (_: InterruptedException) {
            DebugLog.v(TAG, "neriPull($codec): interrupted (shouldStop=$shouldStop)")
        } catch (e: Exception) {
            if (!shouldStop) {
                requestState(DacState.ERROR, "neriPull: ${e.message}")
                DebugLog.add(TAG, "neriPull($codec) err: ${e.message}")
                onError("Decode: ${e.message}")
            }
        } finally {
            if (!shouldStop) {
                releaseResources(); isPlaying = false
                UsbDacManager.pauseStream()  // EOS keep-claim：会话保留，下一曲零间隙
                requestState(DacState.EOS_HOLD, "neriPull: EOS")
                DebugLog.v(TAG, "neriPull($codec) done, calling onCompletion")
                onCompletion()
            } else {
                DebugLog.v(TAG, "neriPull($codec) aborted (shouldStop=true) state=$state")
            }
        }
    }

    private fun flacMonitorLoop() {
        var lastPosMs = 0L; var stallCount = 0; var stallTotal = 0; var lastDebug = 0L; var lastMetricsLog = 0L
        val prebufferGraceMs = 3000L  // [v6.0.7] first 3s pos stays 0 during native prebuffer, skip stall detection
        // 【V9.20 A/卡死自愈】STALL 有界自愈：playback 位长时间不动 = USB OUT 端点 stall / DAC 不消费。
        // 原监控只报不救 → 永久卡死 + 无限刷 STALL 日志。这里加有界自愈 + 硬上限止损。
        // [V9.20 ②] 自愈常量已上提到 companion object；STALL_STATS_ONLY=true 时只统计不 forceReset。
        var recoveryAttempts = 0
        var prebufferEndTime = System.currentTimeMillis() + prebufferGraceMs
        try {
            var drainWaitMs = 0
            while (!shouldStop) {
                if (paused) {
                    synchronized(pauseLock) { while (paused && !shouldStop) (pauseLock as java.lang.Object).wait() }
                }
                if (shouldStop) break
                // [V9.20x] Surprise removal: native URB submission saw consecutive ENODEV and
                // latched "device vanished". Not a stall — forceReset would hammer a dead
                // endpoint. Run the detach path so the position is remembered for auto-resume.
                if (UsbDacManager.pollDeviceVanished()) {
                    DebugLog.add(TAG, "flacMonitor: USB device vanished (ENODEV) — detach path, no self-heal")
                    releaseResources(); isPlaying = false
                    UsbDacManager.pauseStream()
                    requestState(DacState.ERROR, "usb device removed")
                    onError("USB DAC removed")
                    shouldStop = true
                    break
                }
                val pos = UsbDacManager.flacPositionMs()
                positionMs = pos
                if (pos == lastPosMs && !paused) {
                    if (System.currentTimeMillis() < prebufferEndTime) {
                        // still in prebuffer grace window, pos=0 is normal
                        Thread.sleep(50)
                        continue
                    }
                    stallCount++; stallTotal++
                    if (stallCount >= 4) DebugLog.add(TAG, "flacMonitor: STALL pos=$pos stallCount=$stallCount ringFill=${UsbDacManager.getRingFill()} flacEos=${UsbDacManager.flacIsEos()}")
                    // 【V9.20 A】有界自愈：到阈值先 forceReset(取消卡住的 URB，native 设计的总线复位兜底)，
                    // 给一个观察窗口；耗尽次数仍不前进则硬止损(干净停止+报错，不再无限卡死)。
                    // [V9.20n] Stale-session gate FIRST: if this monitor belongs to a session the
                    // user already switched away from, its forceReset would kill the CURRENT
                    // session's live stream (that ping-pong was the multi-second freeze).
                    // Exit quietly instead — the current session owns recovery from now on.
                    if (myGen != UsbDacManager.sessionGen) {
                        DebugLog.add(TAG, "flacMonitor: stale session (gen=$myGen cur=${UsbDacManager.sessionGen}) — exiting quietly, current session owns recovery")
                        shouldStop = true
                        break
                    }
                    if (stallCount == STALL_RECOVER_AT && recoveryAttempts < STALL_MAX_RECOVER) {
                        recoveryAttempts++
                        if (STALL_STATS_ONLY) {
                            DebugLog.add(TAG, "flacMonitor: STALL recovery SKIPPED (stats-only; forceReset disabled) attempt #$recoveryAttempts")
                        } else {
                            DebugLog.add(TAG, "flacMonitor: STALL recovery attempt #$recoveryAttempts (forceReset cancels wedged URBs)")
                            UsbDacManager.forceReset()
                            stallCount = 0
                        }
                        Thread.sleep(50); continue
                    }
                    if (stallCount >= STALL_GIVEUP_AT) {
                        DebugLog.add(TAG, "flacMonitor: STALL unrecoverable after $recoveryAttempts recovery attempt(s); stopping playback")
                        releaseResources(); isPlaying = false
                        UsbDacManager.pauseStream()
                        requestState(DacState.ERROR, "playback stalled (USB endpoint not consuming)")
                        onError("USB stall: DAC stopped consuming audio")
                        shouldStop = true
                        break
                    }
                } else {
                    stallCount = 0
                    if (pos != 0L) prebufferEndTime = 0  // prebuffer done, disable grace window permanently
                }
                lastPosMs = pos
                if (UsbDacManager.flacIsEos()) {
                    // [V3.3.4] streamLoop consumes whole URBs only; a sub-URB tail residue can
                    // NEVER drain to 0 → stalled here for the full 10s timeout before auto-next.
                    if (UsbDacManager.getRingFill() <= EOS_DRAIN_RESIDUE_FRAMES) {
                        // 【V3.3.0】CUE Gapless：EOS 后检查是否还有下一轨
                        if (cueDirect && currentCueTrackIndex >= 0) {
                            val cueTracks = cueTrackList  // 需在 open() 时填充
                            if (currentCueTrackIndex < cueTracks.size - 1) {
                                currentCueTrackIndex++
                                val nextTrack = cueTracks[currentCueTrackIndex]
                                // 找下一轨 FLAC 起点（INDEX 00 优先 pregap，否则 INDEX 01）
                                val startSample = CueParser.cdFramesToSamples(nextTrack.index00Samples ?: nextTrack.index01Samples, sourceSampleRate)
                                DebugLog.add(TAG, "flacMonitor(CUE Gapless): track ${nextTrack.number} " +
                                    "'${nextTrack.title ?: "Track ${nextTrack.number}"}' " +
                                    "sample=$startSample from=${nextTrack.index01Samples}cd")
                                if (UsbDacManager.flacGaplessSeek(currentFilePath, startSample)) {
                                    drainWaitMs = 0
                                    continue  // 继续监控下一轨
                                }
                                DebugLog.add(TAG, "flacMonitor(CUE Gapless): seek FAIL, ending playback")
                            }
                        }
                        DebugLog.add(TAG, "flacMonitor: EOS drain complete"); break
                    }
                    drainWaitMs += 50
                    if (drainWaitMs > 10_000) { DebugLog.add(TAG, "flacMonitor: drain timeout"); break }
                }
                // 每 10s 一次心跳
                val now = System.currentTimeMillis()
                if (now - lastDebug > 10_000) { DebugLog.v(TAG, "flacMonitor: pos=${pos}ms ring=${UsbDacManager.getRingFill()} eos=${UsbDacManager.flacIsEos()}"); lastDebug = now }
                // 【V9.20x】每 5s 打印一次运行时诊断指标（借鉴 NeriPlayer RuntimeMetrics），便于排查爆音/卡顿/无声
                if (now - lastMetricsLog > 5000) {
                    lastMetricsLog = now
                    val m = UsbDacManager.getRuntimeMetrics()
                    val peakLin = if (m.channel0Peak >= m.channel1Peak) m.channel0Peak else m.channel1Peak
                    UsbDacManager.checkLoudPlayback(peakLin)
                    DebugLog.add(TAG, "metrics: underrun=${m.underrunCount} bpEv=${m.backpressureEvents} bpMax=${m.backpressureMaxMs}ms zeroFill=${m.zeroFillBytes}B sig=${m.signalFrames} sil=${m.silentFrames} peakL=${"%.2f".format(m.channel0Peak)} peakR=${"%.2f".format(m.channel1Peak)} drop=${m.droppedBytes}B stallBrk=${m.stallBreaks} stallTot=$stallTotal vanish=${m.vanishEvents}")
                }
                Thread.sleep(50)
            }
        } catch (_: InterruptedException) {
            DebugLog.v(TAG, "flacMonitor: interrupted (shouldStop=$shouldStop)")
        } catch (e: Exception) {
            if (!shouldStop) { requestState(DacState.ERROR, "flacMonitor: ${e.message}"); DebugLog.add(TAG, "flacMonitor err: ${e.message}"); onError("Decode: ${e.message}") }
        } finally {
            if (!shouldStop) {
                releaseResources(); isPlaying = false
                UsbDacManager.pauseStream()  // [V3.3.3] EOS keep USB claim (Salt pattern) - instant next song
                requestState(DacState.EOS_HOLD, "flacMonitor: EOS")
                DebugLog.v(TAG, "flacMonitorLoop done, calling onCompletion")
                onCompletion()
            } else {
                DebugLog.v(TAG, "flacMonitorLoop aborted (shouldStop=true) state=$state")
            }
        }
    }

    // 【V3.2.7】WAV 直读主循环：读文件→float→pushPcm（背压限速），EOS 排空后 onCompletion
    private fun wavDecodeLoop() {
        var normalEos = false
        var pushErrors = 0
        try {
            while (!shouldStop) {
                if (paused) synchronized(pauseLock) { while (paused && !shouldStop) (pauseLock as java.lang.Object).wait() }
                if (shouldStop) break
                // 【V3.2.8】解码线程内安全执行挂起的 seek
                val ps = pendingSeekMs
                if (ps >= 0) { pendingSeekMs = -1L; performSeek(ps) }
                val f = wavReadChunk(8192)
                if (f.isEmpty()) break  // EOF
                val fc = f.size / sourceChannelCount
                // [V9.20] 同 decodeLoop：pushPcm 停顿有界自愈，不再永久冻死
                val pushed = UsbDacManager.pushPcm(f, fc)
                if (pushed < 0) { pushErrors = onPushStall(pushErrors); if (shouldStop) break }
                positionMs = wavReadBytes * 1000L / (sourceSampleRate.toLong() * wavBlockAlign)
            }
            normalEos = true  // 循环自然结束（EOF）＝正常播完
        } catch (e: Exception) {
            // 【修复】异常只报错，不触发 onCompletion；否则 catch+finally 双回调→级联切歌
            if (!shouldStop) { requestState(DacState.ERROR, "wavDecodeLoop: ${e.message}"); DebugLog.add(TAG, "wavDecodeLoop err: ${e.message}"); onError("Decode: ${e.message}") }
        } finally {
            if (normalEos && !shouldStop) {
                var drainWaitMs = 0
                while (!shouldStop && UsbDacManager.getRingFill() > EOS_DRAIN_RESIDUE_FRAMES && drainWaitMs < 10_000) {
                    // 【修复】interrupt 时不能让 InterruptedException 从 finally 抛出杀死进程
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                    drainWaitMs += 50
                }
                DebugLog.v(TAG, "wavDecodeLoop: ring drained in ${drainWaitMs}ms")
                releaseResources(); isPlaying = false
                UsbDacManager.pauseStream()  // [V3.3.3] EOS keep USB claim
                requestState(DacState.EOS_HOLD, "wavDecodeLoop: EOS")
                DebugLog.v(TAG, "wavDecodeLoop done, calling onCompletion")
                onCompletion()
            } else if (!normalEos && !shouldStop) {
                // 异常/被停止：只释放资源，不切歌、不自动下一曲
                releaseResources(); isPlaying = false
                requestState(DacState.ERROR, "wavDecodeLoop aborted")
                DebugLog.v(TAG, "wavDecodeLoop aborted (normalEos=false), no onCompletion")
            }
        }
    }

    private fun preBuffer(targetFrames: Int): Int {
        // 【V3.2.7】WAV 直读预缓冲
        if (wavDirect) {
            var pushed = 0
            while (!shouldStop && pushed < targetFrames) {
                val f = wavReadChunk(8192)
                if (f.isEmpty()) break
                val fc = f.size / sourceChannelCount
                UsbDacManager.pushPcm(f, fc)
                pushed += fc
            }
            return pushed
        }
        val ex = extractor ?: return 0; val cd = codec ?: return 0
        val info = MediaCodec.BufferInfo()
        var pushed = 0; var eos = false
        // 【V3.2.8】decoder 的 enc 标签不可信（FLAC 24bit 标 21 实出 16bit）：
        // 用前两个 buffer 的 pts 差实测每帧字节数，实测 4 字节/帧就强制 16bit 解析
        var diagFirstBytes: ByteArray? = null
        var diagFirstPts = -1L
        var diagDone = sourceBits <= 16
        try {
            while (!shouldStop && pushed < targetFrames && !eos) {
                val inIdx = cd.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = cd.getInputBuffer(inIdx) ?: continue
                    val sz = ex.readSampleData(buf, 0)
                    if (sz < 0) { cd.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                    else { cd.queueInputBuffer(inIdx, 0, sz, ex.sampleTime, 0); ex.advance() }
                }
                val outIdx = cd.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx >= 0) {
                    val outBuf = cd.getOutputBuffer(outIdx) ?: continue
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { cd.releaseOutputBuffer(outIdx, false); break }
                    if (info.size > 0) {
                        if (!diagDone) {
                            if (diagFirstBytes == null) {
                                // 第一个 buffer：先存不推，等实测结果再解析
                                diagFirstBytes = ByteArray(info.size).also { outBuf.position(0); outBuf.get(it) }
                                diagFirstPts = info.presentationTimeUs
                                cd.releaseOutputBuffer(outIdx, false)
                                continue
                            }
                            val dUs = info.presentationTimeUs - diagFirstPts
                            val frames = if (dUs > 0) dUs * sourceSampleRate / 1_000_000L else 0L
                            val bpf = if (frames > 0) diagFirstBytes!!.size.toDouble() / frames else -1.0
                            if (bpf in 3.0..4.9) {
                                outputPcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT
                                enc16Forced = true
                                DebugLog.v("bufdiag", "measured bpf=%.2f → decoder实出16bit，强制override enc".format(bpf))
                            } else {
                                DebugLog.v("bufdiag", "measured bpf=%.2f keep enc=$outputPcmEncoding".format(bpf))
                            }
                            diagDone = true
                            val fb = decodeToFloat(java.nio.ByteBuffer.wrap(diagFirstBytes!!), diagFirstBytes!!.size)
                            if (fb.isNotEmpty()) { UsbDacManager.pushPcm(fb, fb.size / dacChannels); pushed += fb.size / dacChannels }
                            diagFirstBytes = null
                        }
                        val f = decodeToFloat(outBuf, info.size)
                        if (f.isNotEmpty()) {
                            UsbDacManager.pushPcm(f, f.size / dacChannels)
                            pushed += f.size / dacChannels
                        }
                    }
                    cd.releaseOutputBuffer(outIdx, false)
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // [V3.3.x HE-AAC SBR fix] extractor 报的是核心采样率(SBR前)，outputFormat 才是真实输出采样率(SBR后双倍)
                    val of = cd.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        val realSr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (realSr > 0 && realSr != dacSampleRate) {
                            DebugLog.add(TAG, "SR override: $sourceSampleRate -> $realSr (HE-AAC SBR?)")
                            sourceSampleRate = realSr
                            dacSampleRate = realSr
                        }
                    }
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) outputPcmEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    continue
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) continue
                else break
            }
        } catch (e: Exception) { DebugLog.add(TAG, "preBuffer err: ${e.message}") }
        return pushed
    }

    // [V9.20] pushPcm 停顿有界自愈（MP3/AAC/WAV 切歌冻结修复）：
    // native pushPcm 在 ring 满 2s 无人消费时返回 -2（旧实现 usleep 死等 → 解码线程永久冻死）。
    // 同 flacMonitorLoop 的有界自愈节奏：先 forceReset 取消卡死的 URB 给窗口；
    // 耗尽则干净报错停止（可重新点歌恢复），绝不再永久冻结。
    private fun onPushStall(errors: Int): Int {
        // [V9.20x] Surprise removal first: native URB submission saw consecutive ENODEV
        // and latched "device vanished". This is NOT a stall — forceReset would just
        // hammer a dead endpoint and burn the recovery budget. Run the detach path so the
        // playback position is remembered and auto-resume works when the DAC comes back.
        if (UsbDacManager.pollDeviceVanished()) {
            DebugLog.add(TAG, "decodeLoop: USB device vanished (ENODEV) — detach path, no self-heal")
            requestState(DacState.ERROR, "usb device removed")
            onError("USB DAC removed")
            shouldStop = true
            return errors + 1
        }
        val n = errors + 1
        // [V9.20n] Stale-session gate: a decode thread from a session the user already
        // switched away from must NOT forceReset — that tears down the CURRENT
        // session's stream (forceReset ping-pong = the multi-second switch freeze).
        // Exit quietly; the current session's own stall handling owns recovery.
        if (myGen != UsbDacManager.sessionGen) {
            DebugLog.add(TAG, "decodeLoop: pushPcm stalled in stale session (gen=$myGen cur=${UsbDacManager.sessionGen}) — exiting quietly")
            shouldStop = true
            return n
        }
        DebugLog.add(TAG, "decodeLoop: pushPcm stalled (consumer not draining) x$n ring=${UsbDacManager.getRingFill()}")
        if (n == 2) {
            if (STALL_STATS_ONLY) {
                DebugLog.add(TAG, "decodeLoop: STALL recovery SKIPPED (stats-only; forceReset disabled)")
            } else {
                DebugLog.add(TAG, "decodeLoop: STALL recovery (forceReset cancels wedged URBs)")
                UsbDacManager.forceReset()
            }
        }
        if (n >= 6) {
            DebugLog.add(TAG, "decodeLoop: STALL unrecoverable after forceReset; stopping playback")
            requestState(DacState.ERROR, "playback stalled (USB endpoint not consuming)")
            onError("USB stall: DAC stopped consuming audio")
            shouldStop = true
        }
        return n
    }

    private fun decodeLoop() {
        val ex = extractor ?: return; val cd = codec ?: return
        val info = MediaCodec.BufferInfo()
        var eos = false; val sampleCount = dacChannels
        var pushErrors = 0
        var normalEos = false

        try {
            while (!shouldStop) {
                if (paused) synchronized(pauseLock) { while (paused && !shouldStop) (pauseLock as java.lang.Object).wait() }
                if (shouldStop) break

                // 【V3.2.8】在解码线程内安全执行挂起的 seek（无 codec 并发）
                val ps = pendingSeekMs
                if (ps >= 0) {
                    pendingSeekMs = -1L
                    UsbDacManager.resetRingBuffer()  // 丢弃预解码的旧位置音频，seek 响应提速
                    performSeek(ps)
                    eos = false  // seek 回去后输入侧要继续喂数据
                }

                if (!eos) {
                    val inIdx = cd.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = cd.getInputBuffer(inIdx) ?: continue
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) { cd.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                        else { cd.queueInputBuffer(inIdx, 0, sz, ex.sampleTime, 0); ex.advance() }
                    }
                }

                val outIdx = cd.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx >= 0) {
                    val outBuf = cd.getOutputBuffer(outIdx) ?: continue
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { cd.releaseOutputBuffer(outIdx, false); break }
                    if (info.size > 0) {
                        // 【V3.2.9】实测 decoder 真实字节/帧：标签 enc=21 不可信，用 pts 反推
                        if (_bufDiagCount < 3 && sourceBits > 16) {
                            if (_bufDiagLastPts >= 0) {
                                val dUs = info.presentationTimeUs - _bufDiagLastPts
                                val framesExp = if (dUs > 0) dUs * sourceSampleRate / 1_000_000L else 0L
                                val bpf = if (framesExp > 0) _bufDiagLastSize.toDouble() / framesExp else -1.0
                                DebugLog.v("bufdiag", "size=${_bufDiagLastSize} ptsDelta=${dUs}us framesExp=$framesExp actualBytesPerFrame=%.2f".format(bpf))
                                // 4.0 bytes/frame = 2ch × 16bit = 实际 16bit 输出
                                if (bpf in 3.0..4.9) {
                                    if (!enc16Forced) {
                                        enc16Forced = true
                                        outputPcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT
                                        DebugLog.v("bufdiag", "DETECTED: decoder outputs 16bit (bpf=%.2f), forcing enc=16".format(bpf))
                                    }
                                }
                            }
                            _bufDiagLastPts = info.presentationTimeUs; _bufDiagLastSize = info.size
                            _bufDiagCount++
                        }
                        val f = decodeToFloat(outBuf, info.size)
                        if (f.isNotEmpty()) {
                            val fc = f.size / sampleCount
                            // [V9.20] pushPcm < 0 = 停顿（-2）或错误：有界自愈，不再永久冻死
                            val pushed = UsbDacManager.pushPcm(f, fc)
                            pushErrors = if (pushed < 0) onPushStall(pushErrors) else 0
                            if (shouldStop) break
                        }
                    }
                    cd.releaseOutputBuffer(outIdx, false)
                    if (info.presentationTimeUs > 0) positionMs = info.presentationTimeUs / 1000L
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = cd.outputFormat
                    if (nf.containsKey(MediaFormat.KEY_PCM_ENCODING)) outputPcmEncoding = nf.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    DebugLog.add(TAG, "fmt changed: sr=${nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)} ch=${nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)} enc=$outputPcmEncoding")
                }
            }
            normalEos = true  // 循环自然结束（EOS）＝正常播完
        } catch (e: Exception) {
            // 【修复】异常只报错，不触发 onCompletion；否则 catch+finally 双回调→级联切歌
            if (!shouldStop) { requestState(DacState.ERROR, "decodeLoop: ${e.message}"); DebugLog.add(TAG, "decodeLoop err: ${e.message}"); onError("Decode: ${e.message}") }
        } finally {
            if (normalEos && !shouldStop) {
                // 【V3.2.7】EOS 排空：等 DAC 把 ring buffer 里剩余音频播完再切歌，
                // 否则最后 ~3 秒被截断。最多等 10s 防死循环。
                var drainWaitMs = 0
                while (!shouldStop && UsbDacManager.getRingFill() > EOS_DRAIN_RESIDUE_FRAMES && drainWaitMs < 10_000) {
                    // 【修复】interrupt 时不能让 InterruptedException 从 finally 抛出杀死进程
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                    drainWaitMs += 50
                }
                DebugLog.v(TAG, "decodeLoop: ring drained in ${drainWaitMs}ms")
                try { cd.stop(); cd.release() } catch (_: Exception) {}
                try { ex.release() } catch (_: Exception) {}
                codec = null; extractor = null; isPlaying = false
                UsbDacManager.pauseStream()  // [V3.3.3] EOS keep USB claim
                requestState(DacState.EOS_HOLD, "decodeLoop: EOS")
                DebugLog.v(TAG, "decodeLoop done, calling onCompletion")
                onCompletion()
            } else if (!normalEos && !shouldStop) {
                // 异常/被停止：只释放资源，不切歌、不自动下一曲
                try { cd.stop(); cd.release() } catch (_: Exception) {}
                try { ex.release() } catch (_: Exception) {}
                codec = null; extractor = null; isPlaying = false
                // 【P7-OPUS 过热修复】解码线程退出后 native USB 流仍是 STARTED 但无 PCM 喂入 →
                // 底层等时 OUT 传输循环在空流上死转（CPU 空转 → 手机发烫）。先停流循环（保留 claim），
                // 等下次 playSong 的 cross-rate reclaim 或 keep-claim 复用。
                if (UsbDacManager.isStreaming()) {
                    UsbDacManager.pauseStream()
                    DebugLog.add(TAG, "decodeLoop aborted: paused starved USB stream (anti-overheat)")
                }
                requestState(DacState.ERROR, "decodeLoop aborted")
                DebugLog.v(TAG, "decodeLoop aborted (normalEos=false), no onCompletion")
            }
        }
    }

    private var _decodeLogCount = 0
    // 【修复】MediaCodec buffer 边界不按 3 字节样本对齐（16384%3=1）：
    // 上个 buffer 尾部残缺样本字节必须结转到下个 buffer，否则全程错位=全噪音
    private var _bufDiagCount = 0
    private var _bufDiagLastPts = -1L
    private var _bufDiagLastSize = 0
    private var _hexDumpDone = 0
    @Volatile var playbackWireBits = 16  // [v6.0.14] S32_LE DACs override default 16-bit wire format
    @Volatile private var enc16Forced = false
    private var carry24 = ByteArray(0)
    private fun decodeToFloat(buffer: ByteBuffer, size: Int): FloatArray {
        if (_hexDumpDone < 1 && size >= 24) {
            val hx = ByteArray(24); buffer.position(0); buffer.get(hx); buffer.position(0)
            DebugLog.v("bufdiag", "hex[0..23]=" + hx.joinToString("") { "%02X".format(it) })
            _hexDumpDone++
        }
        if (_decodeLogCount < 3) {
            DebugLog.v("dec", "size=$size enc=$outputPcmEncoding limit=${buffer.limit()} cap=${buffer.capacity()}")
            _decodeLogCount++
        }
        // 【V3.2.7 修复】INFO_OUTPUT_FORMAT_CHANGED 后第一帧可能仍是旧格式 data，
        // 导致 decoder 输出 24bit packed 却打 ENCODING_PCM_16BIT 标签。
        // 防御：sourceBits>16 但 encoding=16bit 时，检查 size 是否能被 3 整除，
        // 能整除说明实际是 24bit packed，按 24bit 解析避免左右声道字节错位。
        val actualEncoding = if (!enc16Forced && outputPcmEncoding == android.media.AudioFormat.ENCODING_PCM_16BIT
                && sourceBits > 16 && size % 3 == 0) {
            DebugLog.add("decode24", "auto-switch 16bit→24bit packed (size=$size srcBits=$sourceBits)")
            android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED
        } else {
            outputPcmEncoding
        }
        // （已由 carry24 处理跨 buffer 对齐，不再每帧刷日志——日志 I/O 会拖垮解码线程导致 underrun）
        when (actualEncoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> {
                val floatCount = size / 4
                if (floatCount <= 0) return FloatArray(0)
                val out = FloatArray(floatCount)
                buffer.position(0)
                buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out, 0, floatCount)
                return out
            }
            android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                // 拼接上个 buffer 的残留字节；【关键】按整帧对齐（3字节×声道数），
                // 只按 3 字节对齐会产生奇数样本 → 半帧入 ring → 左右声道互换
                val frameBytes = 3 * sourceChannelCount.coerceAtLeast(1)
                val raw = ByteArray(size)
                buffer.position(0); buffer.get(raw)
                val bytes = if (carry24.isEmpty()) raw else carry24 + raw
                val total = bytes.size
                val rem = total % frameBytes
                val usable = total - rem
                val sampleCount = usable / 3
                carry24 = if (rem > 0) bytes.copyOfRange(usable, total) else ByteArray(0)
                if (sampleCount <= 0) return FloatArray(0)
                return FloatArray(sampleCount) { i ->
                    val v = (bytes[i * 3].toInt() and 0xFF) or
                            ((bytes[i * 3 + 1].toInt() and 0xFF) shl 8) or
                            (bytes[i * 3 + 2].toInt() shl 16)  // 符号位自然扩展
                    v.toFloat() / 8388608.0f
                }
            }
            android.media.AudioFormat.ENCODING_PCM_32BIT -> {
                val sampleCount = size / 4
                if (sampleCount <= 0) return FloatArray(0)
                val out = FloatArray(sampleCount)
                buffer.position(0)
                val ib = buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                for (i in 0 until sampleCount) out[i] = ib.get(i).toFloat() / 2147483648.0f
                return out
            }
        }
        val shortCount = size / 2
        if (shortCount <= 0) return FloatArray(0)
        val tmp = ShortArray(shortCount)
        if (buffer.isDirect) {
            // [zipper/byte-order fix] MediaCodec outputs little-endian 16-bit PCM;
            // ByteBuffer defaults to BIG_ENDIAN — reading .short without setting LITTLE_ENDIAN
            // swaps every sample's hi/lo byte -> severe distortion (hiss/crackle) on AAC/MP3.
            buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buffer.position(0)
            for (i in 0 until shortCount) tmp[i] = buffer.short
        } else {
            val bytes = ByteArray(size)
            buffer.position(0); buffer.get(bytes)
            for (i in 0 until shortCount) {
                tmp[i] = (((bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 0xFF))).toShort()
            }
        }
        return FloatArray(shortCount) { tmp[it].toFloat() / Short.MAX_VALUE.toFloat() }
    }
}
