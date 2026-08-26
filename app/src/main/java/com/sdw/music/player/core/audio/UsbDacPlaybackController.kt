package com.sdw.music.player.core.audio

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
        private const val PREBUFFER_TARGET_MS = 200L  // 【V3.3.21】从 500ms 降到 200ms，减少切歌延迟
        // [V3.3.4] streamLoop only consumes whole URBs (44.1k≈353 / 48k=384 / 96k=768 frames);
        // an EOS tail smaller than one URB can never drain to 0 and was stalling auto-next
        // for the full 10s timeout. Anything below this is unplayable residue — treat as drained.
        private const val EOS_DRAIN_RESIDUE_FRAMES = 1024
    }

    @Volatile var isPlaying = false; private set
    @Volatile var positionMs = 0L; private set
    val durationMs get() = extractorDurationMs
    @Volatile var sourceSampleRate = 0; private set
    @Volatile var sourceBits = 16; private set
    @Volatile var sourceChannelCount = 0; private set
    @Volatile private var currentFilePath = ""

    private var decodeThread: Thread? = null
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var extractorDurationMs = 0L
    private var audioTrackIndex = -1
    private var dacSampleRate = DEFAULT_SAMPLE_RATE
    private var dacChannels = DEFAULT_CHANNELS

    private val pauseLock = Object()
    @Volatile private var paused = false
    @Volatile private var shouldStop = false
    @Volatile private var outputPcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT

    // 【V3.2.7】WAV 直读路径：Android raw decoder 会把 24bit 转成 16bit，
    // 真 Bit-Perfect 必须自己解 RIFF 头直读 PCM，绕开 MediaCodec
    // 【V3.3.0】FLAC 直解：native libFLAC 硬解直入 ring，绕开 Moto MediaCodec 降级
    @Volatile private var flacDirect = false

    // 【2026-08-25】ALAC 直解：native Apple codec + MP4 box 解析直入 ring，绕开 MediaCodec
    @Volatile private var alacDirect = false

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
            raf.seek(wavDataStart)
            wavFile = raf; wavReadBytes = 0L
            extractorDurationMs = wavDataSize * 1000L / (sourceSampleRate.toLong() * wavBlockAlign)
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
        val bytesWanted = (maxFrames.toLong() * wavBlockAlign).coerceAtMost(remaining).toInt()
        val alignedBytes = bytesWanted - (bytesWanted % wavBlockAlign)
        if (alignedBytes <= 0) return FloatArray(0)
        val bytes = ByteArray(alignedBytes)
        return try {
            raf.readFully(bytes)
            wavReadBytes += alignedBytes
            val bytesPerSample = wavBlockAlign / sourceChannelCount
            val sampleCount = alignedBytes / bytesPerSample
            when {
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
        this.dacSampleRate = dacSampleRate
        this.dacChannels = dacChannels
        this.currentFilePath = filePath
        releaseResources()

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
        if ((filePath.endsWith(".m4a", ignoreCase = true) || filePath.endsWith(".alac", ignoreCase = true) ||
             filePath.endsWith(".mp4", ignoreCase = true)) && UsbDacManager.alacOpen(filePath)) {
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
            if (audioTrackIndex < 0) { onError("No audio track"); releaseResources(); return false }

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
            val cfgFmt = MediaFormat.createAudioFormat(mime, sourceSampleRate, sourceChannelCount)
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
        if (isPlaying) return
        if (!wavDirect && !flacDirect && !alacDirect && (codec == null || extractor == null)) { onError("Not ready"); return }
        shouldStop = false; paused = false; isPlaying = true

        decodeThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            // [v6.0.13] keep-claim: skip reset when stream alive, avoid glitch from mid-stream memset
            if (!streamAlreadyRunning) UsbDacManager.resetRingBuffer()

            // 【2026-08-25】ALAC 直解：native 解码线程自行填 ring，Kotlin 只做预缓冲等待+监控（同 flac）
            if (alacDirect) {
                UsbDacManager.alacStart()
                val ringTarget = ((dacSampleRate * PREBUFFER_TARGET_MS) / 1000L).toInt()
                val t0 = System.currentTimeMillis()
                while (!shouldStop && UsbDacManager.getRingFill() < ringTarget &&
                    !UsbDacManager.alacIsEos() && System.currentTimeMillis() - t0 < 3000) {
                    try { Thread.sleep(10) } catch (_: InterruptedException) { return@Thread }
                }
                DebugLog.v(TAG, "play(alac): prebuffer ${UsbDacManager.getRingFill()} frames in ${System.currentTimeMillis() - t0}ms")
                val alacOutBits = if (sourceBits > 16) 24 else 16
                if (!UsbDacManager.startStreaming(dacSampleRate, dacChannels, alacOutBits)) {
                    onError("startStreaming FAIL"); return@Thread
                }
                DebugLog.v(TAG, "play(alac): DAC stream started, bits=$alacOutBits")
                alacMonitorLoop()
                DebugLog.v(TAG, "play(alac): alacMonitorLoop RETURNED")
                return@Thread
            }

            // 【V3.3.0】FLAC 直解：native 解码线程自行填 ring，Kotlin 只做预缓冲等待+监控
            if (flacDirect) {
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

                val ringTarget = ((dacSampleRate * PREBUFFER_TARGET_MS) / 1000L).toInt()
                val t0 = System.currentTimeMillis()
                while (!shouldStop && UsbDacManager.getRingFill() < ringTarget &&
                    !UsbDacManager.flacIsEos() && System.currentTimeMillis() - t0 < 3000) {
                    try { Thread.sleep(10) } catch (_: InterruptedException) { return@Thread }
                }
                DebugLog.v(TAG, "play(flac): prebuffer ${UsbDacManager.getRingFill()} frames in ${System.currentTimeMillis() - t0}ms")
                // [对齐 Salt] 硬件音量已恢复（FU id=16 ch1/ch2 在 DAC 芯片内衰减，不损 SNR）。
                // 16bit 源走 alt=1/16bit 直出，不做软件衰减则无量化噪声；24bit 源才走 alt=2。
                val flacOutBits = if (sourceBits > 16) 24 else 16
                if (!UsbDacManager.startStreaming(dacSampleRate, dacChannels, flacOutBits)) {
                    onError("startStreaming FAIL"); return@Thread
                }
                DebugLog.v(TAG, "play(flac): DAC stream started, bits=$flacOutBits (TRUE bit-depth) nativeInfo=${UsbDacManager.getDetailedDacInfo()?.take(120)}")
                DebugLog.v(TAG, "play(flac): entering flacMonitorLoop...")
                flacMonitorLoop()
                DebugLog.v(TAG, "play(flac): flacMonitorLoop RETURNED (shouldStop=$shouldStop shouldRestart=${!shouldStop && !paused})")
                return@Thread
            }

            // Pre-buffer before starting DAC stream (Salt pattern: fill ring, then open tap)
            val targetFrames = ((dacSampleRate * PREBUFFER_TARGET_MS) / 1000L).toInt() * dacChannels
            val preBufFrames = preBuffer(targetFrames)
            DebugLog.v(TAG, "play: prebuffer done, $preBufFrames frames")

            // Start DAC stream NOW — ring buffer is full, no underruns
            // 【V3.2.9】Fail-Fast 位深联动：preBuffer 已实测 decoder 真实输出（bpf），
            // 声明位深必须跟实测一致：
            //   真 24bit（WAV 直读 / decoder 实出 24bit）→ alt=2 / MPS=288 / 6B帧
            //   decoder 降级 16bit（enc16Forced）→ 诚实走 alt=1 / MPS=192 / 4B帧，显式告知
            // 严禁声明 24bit 却喂 16bit 精度数据（隐式降级）
            val outBits = if (sourceBits > 16 && !enc16Forced) 24 else 16
            if (outBits > playbackWireBits) playbackWireBits = outBits  // [v6.0.14] but never downgrade S32_LE
            if (sourceBits > 16 && enc16Forced) {
                DebugLog.add(TAG, "FAIL-FAST: source ${sourceBits}bit but platform decoder outputs 16bit — " +
                    "DAC honest mode alt=1/16bit (true 24bit needs native FLAC decode)")
            }
            val started = if (streamAlreadyRunning) true else UsbDacManager.startStreaming(dacSampleRate, dacChannels, playbackWireBits)
            if (!started) { onError("startStreaming FAIL"); return@Thread }
            DebugLog.v(TAG, "play: DAC stream started after prebuffer, entering decode loop (wavDirect=$wavDirect)")
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
        if (!isPlaying) return
        paused = true; isPlaying = false
        // 先算可听位置再停流；只停 streamLoop，保留 USB claim（stopAndRelease 会丢 claim）
        val ringMs = if (dacSampleRate > 0) UsbDacManager.getRingFill() * 1000L / dacSampleRate else 0L
        pausedAtMs = (positionMs - ringMs).coerceAtLeast(0L)
        if (flacDirect) UsbDacManager.flacPause(true)  // 【V3.3.0】挂起 native 解码线程
        if (alacDirect) UsbDacManager.alacPause(true)  // 【2026-08-25】挂起 native 解码线程
        UsbDacManager.pauseStream()
        DebugLog.add(TAG, "pause at ${pausedAtMs}ms (decodePos=${positionMs}ms ring=${ringMs}ms)")
    }

    fun resume() {
        if (isPlaying) return
        // 【V3.2.7】暂停后恢复必须先清 ring buffer，再开流。
        // stopThreadOnly 不清 ring 数据，直接开流新旧数据踩踏导致左声道噪音
        UsbDacManager.resetRingBuffer()
        // 回到可听位置重解（解码线程此时在 pauseLock.wait() 中挂起，直接 flush 安全）
        pendingSeekMs = -1L
        if (pausedAtMs >= 0) performSeek(pausedAtMs)

        // [爆音修复] 先唤醒解码线程预缓冲（streamLoop 仍停，resetRingBuffer 无竞争）
        paused = false; shouldStop = false
        if (flacDirect) UsbDacManager.flacPause(false)
        synchronized(pauseLock) { (pauseLock as java.lang.Object).notifyAll() }

        // 等 ring 预缓冲 ~200ms，避免开流瞬间 underrun + 数据跳变爆音
        val ringTarget = ((dacSampleRate * PREBUFFER_TARGET_MS) / 1000L).toInt()
        val t0 = System.currentTimeMillis()
        while (!shouldStop && UsbDacManager.getRingFill() < ringTarget && System.currentTimeMillis() - t0 < 2000) {
            try { Thread.sleep(10) } catch (_: InterruptedException) { break }
        }

        val outBits = if (sourceBits > 16) 24 else 16
        if (!UsbDacManager.startStreaming(dacSampleRate, dacChannels, outBits)) {
            DebugLog.add(TAG, "resume: startStreaming FAIL"); onError("resume FAIL"); return
        }
        pausedAtMs = -1L
        isPlaying = true
        DebugLog.add(TAG, "resume from ${positionMs}ms (bits=$outBits)")
    }

    fun stop() {
        shouldStop = true; paused = false; isPlaying = false
        synchronized(pauseLock) { (pauseLock as java.lang.Object).notifyAll() }
        decodeThread?.interrupt(); decodeThread?.join(2000); decodeThread = null
        UsbDacManager.stopAndRelease()
        releaseResources()
        DebugLog.v(TAG, "stop")
    }

    fun stopDecode() {
        shouldStop = true; paused = false; isPlaying = false
        synchronized(pauseLock) { (pauseLock as java.lang.Object).notifyAll() }
        decodeThread?.interrupt(); decodeThread?.join(600); decodeThread = null  // [V3.3.3] shorter join, avoid main-thread ANR
        UsbDacManager.pauseStream()  // stop streamLoop, prevent underruns during silence
        UsbDacManager.resetRingBuffer()  // 【V3.3.16】清空残留数据，避免切歌噪音
        releaseResources()
        DebugLog.v(TAG, "stopDecode (DAC stream paused, ring cleared)")
    }

    // [v6.0.15] Same-rate keep-claim: kill old monitor + release old decoder, new controller takes over.
    // 【修复 2026-08-25】两首歌一起放根因：旧版只 interrupt Kotlin monitor 线程，
    // 但 monitor 的 finally 块因 shouldStop=true 跳过 releaseResources()，
    // 导致旧格式的 native 解码线程（flacStop/alacStop）没被停 → 继续 push 旧歌 PCM 与新歌交织。
    // 必须：停 monitor → 停 streamLoop（pauseStream，保留 claim）→ 清 ring → 停旧 native 解码线程。
    // 顺序不能乱：resetRingBuffer 必须在 streamLoop 停了之后，否则 mid-stream memset 与读并发爆音。
    // 新歌 play(streamAlreadyRunning=false) 会重新 startStreaming（same-rate keep-stream 幂等重启）。
    fun stopMonitor() {
        shouldStop = true; paused = false; isPlaying = false
        synchronized(pauseLock) { (pauseLock as java.lang.Object).notifyAll() }
        decodeThread?.interrupt()  // wake monitor from sleep, don't block on join
        decodeThread = null
        UsbDacManager.pauseStream()  // stop streamLoop (keep USB claim)
        UsbDacManager.resetRingBuffer()  // 清旧歌残留 PCM（streamLoop 已停，安全）
        releaseResources()  // 停旧格式 native 解码线程（flacStop/alacStop/codec.stop）+ 释放 extractor
        DebugLog.v(TAG, "stopMonitor (monitor killed, stream paused, ring cleared, decoder released, claim kept)")
    }

    // 【V3.2.8】seek 改为挂起请求：主线程直接 codec.flush() 会和解码线程的
    // dequeueOutputBuffer 撞车 → IllegalStateException → finally onCompletion → 误切下一曲
    @Volatile private var pendingSeekMs = -1L

    fun seekTo(timeMs: Long) {
        positionMs = timeMs
        if (pausedAtMs >= 0) { pausedAtMs = timeMs; return }  // 暂停中：resume 时统一执行
        if (flacDirect) { UsbDacManager.flacSeek(timeMs); return }  // 【V3.3.0】native 线程内安全执行
        if (alacDirect) { UsbDacManager.alacSeek(timeMs); return }  // 【2026-08-25】native 线程内安全执行
        pendingSeekMs = timeMs  // 播放中：解码线程在循环顶部安全执行
        DebugLog.v(TAG, "seekTo request ${timeMs}ms (deferred to decode thread)")
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

    private fun releaseResources() {
        if (flacDirect) { UsbDacManager.flacStop(); flacDirect = false; cueDirect = false; currentCueTrackIndex = -1; cueTrackList = emptyList() }  // 【V3.3.0】join native 解码线程，清理 CUE 状态
        if (alacDirect) { UsbDacManager.alacStop(); alacDirect = false }  // 【2026-08-25】join native 解码线程
        codec?.stop(); codec?.release(); codec = null
        extractor?.release(); extractor = null
        try { wavFile?.close() } catch (_: Exception) {}
        wavFile = null; wavDirect = false; wavReadBytes = 0L
        audioTrackIndex = -1; extractorDurationMs = 0L
        carry24 = ByteArray(0)
        enc16Forced = false; _bufDiagCount = 0; _bufDiagLastPts = -1L; _hexDumpDone = 0
    }

    // 【2026-08-25】ALAC 直解监控循环：native 线程自行解码+推流，Kotlin 只同步进度/检测 EOS 排空（同 flac）
    private fun alacMonitorLoop() {
        var lastPosMs = 0L; var stallCount = 0; var lastDebug = 0L
        val prebufferGraceMs = 3000L
        var prebufferEndTime = System.currentTimeMillis() + prebufferGraceMs
        try {
            var drainWaitMs = 0
            while (!shouldStop) {
                if (paused) {
                    synchronized(pauseLock) { while (paused && !shouldStop) (pauseLock as java.lang.Object).wait() }
                }
                if (shouldStop) break
                val pos = UsbDacManager.alacPositionMs()
                positionMs = pos
                if (pos == lastPosMs && !paused) {
                    if (System.currentTimeMillis() < prebufferEndTime) {
                        Thread.sleep(50)
                        continue
                    }
                    stallCount++
                    if (stallCount >= 4) DebugLog.add(TAG, "alacMonitor: STALL pos=$pos stallCount=$stallCount ringFill=${UsbDacManager.getRingFill()} alacEos=${UsbDacManager.alacIsEos()}")
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
                Thread.sleep(50)
            }
        } catch (_: InterruptedException) {
            DebugLog.v(TAG, "alacMonitor: interrupted (shouldStop=$shouldStop)")
        } catch (e: Exception) {
            if (!shouldStop) { DebugLog.add(TAG, "alacMonitor err: ${e.message}"); onError("Decode: ${e.message}") }
        } finally {
            if (!shouldStop) {
                releaseResources(); isPlaying = false
                UsbDacManager.pauseStream()
                DebugLog.v(TAG, "alacMonitorLoop done, calling onCompletion")
                onCompletion()
            }
        }
    }

    // 【V3.3.0】FLAC 直解监控循环：native 线程自行解码+推流，Kotlin 只同步进度/检测 EOS 排空
    private fun flacMonitorLoop() {
        var lastPosMs = 0L; var stallCount = 0; var lastDebug = 0L
        val prebufferGraceMs = 3000L  // [v6.0.7] first 3s pos stays 0 during native prebuffer, skip stall detection
        var prebufferEndTime = System.currentTimeMillis() + prebufferGraceMs
        try {
            var drainWaitMs = 0
            while (!shouldStop) {
                if (paused) {
                    synchronized(pauseLock) { while (paused && !shouldStop) (pauseLock as java.lang.Object).wait() }
                }
                if (shouldStop) break
                val pos = UsbDacManager.flacPositionMs()
                positionMs = pos
                if (pos == lastPosMs && !paused) {
                    if (System.currentTimeMillis() < prebufferEndTime) {
                        // still in prebuffer grace window, pos=0 is normal
                        Thread.sleep(50)
                        continue
                    }
                    stallCount++
                    if (stallCount >= 4) DebugLog.add(TAG, "flacMonitor: STALL pos=$pos stallCount=$stallCount ringFill=${UsbDacManager.getRingFill()} flacEos=${UsbDacManager.flacIsEos()}")
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
                Thread.sleep(50)
            }
        } catch (_: InterruptedException) {
            DebugLog.v(TAG, "flacMonitor: interrupted (shouldStop=$shouldStop)")
        } catch (e: Exception) {
            if (!shouldStop) { DebugLog.add(TAG, "flacMonitor err: ${e.message}"); onError("Decode: ${e.message}") }
        } finally {
            if (!shouldStop) {
                releaseResources(); isPlaying = false
                UsbDacManager.pauseStream()  // [V3.3.3] EOS keep USB claim (Salt pattern) - instant next song
                DebugLog.v(TAG, "flacMonitorLoop done, calling onCompletion")
                onCompletion()
            }
        }
    }

    // 【V3.2.7】WAV 直读主循环：读文件→float→pushPcm（背压限速），EOS 排空后 onCompletion
    private fun wavDecodeLoop() {
        var normalEos = false
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
                UsbDacManager.pushPcm(f, fc)
                positionMs = wavReadBytes * 1000L / (sourceSampleRate.toLong() * wavBlockAlign)
            }
            normalEos = true  // 循环自然结束（EOF）＝正常播完
        } catch (e: Exception) {
            // 【修复】异常只报错，不触发 onCompletion；否则 catch+finally 双回调→级联切歌
            if (!shouldStop) { DebugLog.add(TAG, "wavDecodeLoop err: ${e.message}"); onError("Decode: ${e.message}") }
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
                DebugLog.v(TAG, "wavDecodeLoop done, calling onCompletion")
                onCompletion()
            } else if (!normalEos && !shouldStop) {
                // 异常/被停止：只释放资源，不切歌、不自动下一曲
                releaseResources(); isPlaying = false
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
                            if (UsbDacManager.pushPcm(f, fc) < 0) {
                                pushErrors++
                                if (pushErrors > 100) { DebugLog.add(TAG, "pushPcm: too many errors, stopping"); break }
                            } else pushErrors = 0
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
            if (!shouldStop) { DebugLog.add(TAG, "decodeLoop err: ${e.message}"); onError("Decode: ${e.message}") }
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
                DebugLog.v(TAG, "decodeLoop done, calling onCompletion")
                onCompletion()
            } else if (!normalEos && !shouldStop) {
                // 异常/被停止：只释放资源，不切歌、不自动下一曲
                try { cd.stop(); cd.release() } catch (_: Exception) {}
                try { ex.release() } catch (_: Exception) {}
                codec = null; extractor = null; isPlaying = false
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
