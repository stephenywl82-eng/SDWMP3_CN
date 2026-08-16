package com.sdw.music.player

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 【Steven】音频分析工具
 * - BPM 检测（基于能量峰值分析）
 * - 流派识别（基于音频特征）
 */
object AudioAnalyzer {

    /**
     * 【Steven】检测歌曲 BPM
     * 使用音频能量峰值算法估算节拍速度
     * @param context Android 上下文
     * @param uri 音频文件 URI
     * @return BPM 值（60-200 范围内），失败Back 0
     */
    suspend fun detectBPM(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)

            // 找到音频轨道
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex < 0 || format == null) {
                extractor.release()
                return@withContext 0
            }

            extractor.selectTrack(audioTrackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val duration = extractor.sampleTime

            // 多点采样：On头 + 1/3 + 中间，每段 2 秒
            val samplePositions = listOf(0L, duration / 3, duration / 2).distinct()
            val buffer = ByteBuffer.allocate(sampleRate * 2 * 2) // 2秒音频
            val allSamples = mutableListOf<Short>()

            for (pos in samplePositions) {
                try {
                    extractor.seekTo(pos.coerceAtLeast(0), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    val readBytes = extractor.readSampleData(buffer, 0)
                    if (readBytes > 0) {
                        buffer.rewind()
                        val chunk = ShortArray(readBytes / 2)
                        buffer.asShortBuffer().get(chunk)
                        allSamples.addAll(chunk.toList())
                    }
                } catch (_: Exception) { /* skip unreadable section */ }
            }
            extractor.release()

            if (allSamples.size < sampleRate / 2) return@withContext 0  // 至少 0.5 秒数据

            val samples = allSamples.toShortArray()
            val bpm = estimateBPM(samples, sampleRate)
            bpm
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 【Steven】估算 BPM
     * 基于音频能量包络分析
     */
    private fun estimateBPM(samples: ShortArray, sampleRate: Int): Int {
        // 归一化样本到 [-1, 1]
        val normalized = samples.map { it.toDouble() / Short.MAX_VALUE }.toDoubleArray()
        if (normalized.size < sampleRate) return 0  // 至少 1 秒数据

        // 【方法 1】自相关法 — 更稳定
        val bpmAutoCorr = detectBPMByAutocorrelation(normalized, sampleRate)

        // 【方法 2】能量峰值法 — 作为备选
        val bpmPeaks = detectBPMByEnergyPeaks(normalized, sampleRate)

        // 两种方法取较合理的结果
        return when {
            bpmAutoCorr > 0 && bpmPeaks > 0 -> {
                // 如果两者接近，取平均；否则取自相关结果
                if (kotlin.math.abs(bpmAutoCorr - bpmPeaks) <= 10) {
                    (bpmAutoCorr + bpmPeaks) / 2
                } else bpmAutoCorr
            }
            bpmAutoCorr > 0 -> bpmAutoCorr
            bpmPeaks > 0 -> bpmPeaks
            else -> 0
        }
    }

    /**
     * 自相关 BPM 检测
     * 在 BPM 60-200 对应的 lag 范围内Search自相关峰值
     */
    private fun detectBPMByAutocorrelation(samples: DoubleArray, sampleRate: Int): Int {
        // 降采样到 ~11025Hz 减少计算量
        val downsampleFactor = (sampleRate / 11025).coerceAtLeast(1)
        val downsampled = DoubleArray(samples.size / downsampleFactor) { i ->
            samples[i * downsampleFactor]
        }
        val effectiveRate = sampleRate / downsampleFactor

        // BPM 60-200 对应的 lag 范围
        val minLag = (effectiveRate * 60.0 / 200).toInt()  // ~33 samples at 11025Hz
        val maxLag = (effectiveRate * 60.0 / 60).toInt()   // ~110 samples at 11025Hz

        if (downsampled.size < maxLag * 2) return 0

        // 计算自相关
        val windowSize = minOf(downsampled.size, effectiveRate * 4)  // 4秒窗口
        var bestLag = minLag
        var bestCorr = Double.NEGATIVE_INFINITY

        for (lag in minLag..maxLag) {
            var sum = 0.0
            var count = 0
            for (i in 0 until windowSize - lag) {
                sum += downsampled[i] * downsampled[i + lag]
                count++
            }
            val corr = if (count > 0) sum / count else 0.0
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }

        val bpm = (effectiveRate * 60.0 / bestLag).toInt()
        return when {
            bpm in 60..200 -> bpm
            else -> 0
        }
    }

    /**
     * 能量峰值法 BPM 检测（改进版）
     */
    private fun detectBPMByEnergyPeaks(samples: DoubleArray, sampleRate: Int): Int {
        // 计算能量包络
        val windowSize = sampleRate / 10 // 100ms 窗口
        val energies = mutableListOf<Double>()

        for (i in samples.indices step windowSize) {
            var sum = 0.0
            val count = minOf(windowSize, samples.size - i)
            for (j in 0 until count) {
                sum += samples[i + j] * samples[i + j]
            }
            energies.add(sqrt(sum / count))
        }

        if (energies.size < 10) return 0

        // 找到能量峰值
        val peaks = mutableListOf<Int>()
        val threshold = energies.average() * 1.3  // 降低阈值

        for (i in 1 until energies.size - 1) {
            if (energies[i] > threshold &&
                energies[i] > energies[i - 1] &&
                energies[i] > energies[i + 1]) {
                peaks.add(i)
            }
        }

        if (peaks.size < 2) return 0

        // 计算峰值间隔的中位数（比平均值更稳定）
        val intervals = mutableListOf<Int>()
        for (i in 1 until peaks.size) {
            intervals.add(peaks[i] - peaks[i - 1])
        }
        intervals.sort()
        val medianInterval = intervals[intervals.size / 2]

        if (medianInterval <= 0) return 0

        val bpm = (60.0 / (medianInterval * 0.1)).toInt()
        return when {
            bpm in 60..200 -> bpm
            else -> 0
        }
    }

    /**
     * 【Steven】根据音频特征估算流派
     * @param bpm 歌曲 BPM
     * @param duration 歌曲时长（毫秒）
     * @param format 音频格式
     * @return 估算的流派
     */
    fun estimateGenre(bpm: Int, duration: Long, format: String, title: String = "", filePath: String = "", artist: String = ""): String {
        // 其他标题关键词
        
        if (bpm <= 0) return "Unknown"

        return when {
            // 慢速歌曲 (< 90 BPM)
            bpm < 90 -> when {
                duration > 300000 -> "Ballad"      // 慢歌 > 5分钟
                format in listOf("FLAC", "WAV", "AIFF") -> "Classical"
                else -> "R&B"
            }
            // 中速歌曲 (90-120 BPM)
            bpm in 90..120 -> when {
                format in listOf("FLAC", "WAV", "AIFF") -> "Jazz"
                else -> "Pop"
            }
            // 快节奏歌曲 (120-150 BPM)
            bpm in 120..150 -> when {
                format in listOf("FLAC", "WAV") -> "Electronic"
                else -> "Dance"
            }
            // 很快节奏 (> 150 BPM)
            else -> "EDM"
        }
    }

    /**
     * 【Steven】检测歌曲调性 (Key)
     * 优先从音频元数据读取，回退到色度特征估算
     */
    suspend fun detectKey(context: Context, uri: Uri, filePath: String): String = withContext(Dispatchers.IO) {
        try {
            // 优先从元数据读取
            val metadataKey = readKeyFromMetadata(context, uri)
            if (metadataKey.isNotEmpty()) return@withContext toCamelot(metadataKey)

            // 回退：从文件路径尝试用 MediaMetadataRetriever
            val fileKey = readKeyFromFile(filePath)
            if (fileKey.isNotEmpty()) return@withContext toCamelot(fileKey)

            // 最终回退：色度特征估算
            val estimated = estimateKeyFromAudio(context, uri)
            if (estimated.isNotEmpty()) toCamelot(estimated) else ""
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    /**
     * 从 MediaMetadataRetriever 读取 TKEY 元数据
     */
    private fun readKeyFromMetadata(context: Context, uri: Uri): String {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val key = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
            retriever.release()
            // METADATA_KEY 不直接支持 TKEY，需要用原始方式
            ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 从文件路径读取 TKEY
     */
    private fun readKeyFromFile(filePath: String): String {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(filePath)
            // 尝试读取 key (METADATA_KEY = 18 是自定义 key，部分设备支持)
            val key = retriever.extractMetadata(18) // 尝试各个可能的 key index
            retriever.release()
            key?.takeIf { it.isNotBlank() && isValidKey(it) } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 基于色度特征的调性估算（Krumhansl-Schmuckler 算法简化版）
     */
    private fun estimateKeyFromAudio(context: Context, uri: Uri): String {
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)

            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex < 0 || format == null) {
                extractor.release()
                return ""
            }

            extractor.selectTrack(audioTrackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

            // 读取约 10 秒音频进行分析
            val buffer = ByteBuffer.allocate(sampleRate * 2 * 10)
            val readBytes = extractor.readSampleData(buffer, 0)
            extractor.release()

            if (readBytes <= 0) return ""

            buffer.rewind()
            val samples = ShortArray(readBytes / 2)
            buffer.asShortBuffer().get(samples)

            // 计算色度向量（12 个音级的能量分布）
            val chroma = computeChroma(samples, sampleRate)

            // Krumhansl-Schmuckler 密钥检测
            return krumhanslSchmuckler(chroma)
        } catch (e: Exception) {
            return ""
        }
    }

    /**
     * 计算色度向量
     */
    private fun computeChroma(samples: ShortArray, sampleRate: Int): FloatArray {
        val chroma = FloatArray(12)
        val windowSize = 4096
        val hopSize = 2048

        for (start in 0 until (samples.size - windowSize) step hopSize) {
            for (bin in 0 until 12) {
                var energy = 0.0f
                // 简化：对每个色度 bin 计算相关频率范围的能量
                for (octave in 1..6) {
                    val freq = 440.0 * Math.pow(2.0, (bin - 9 + octave * 12) / 12.0)
                    val binIndex = (freq * windowSize / sampleRate).toInt()
                    if (binIndex > 0 && binIndex < windowSize / 2) {
                        // 简化的 DFT：只计算目标频率的幅度
                        var re = 0.0
                        var im = 0.0
                        for (i in 0 until windowSize) {
                            if (start + i >= samples.size) break
                            val sample = samples[start + i].toFloat() / 32768.0f
                            val angle = 2.0 * Math.PI * binIndex * i / windowSize
                            re += sample * Math.cos(angle)
                            im += sample * Math.sin(angle)
                        }
                        energy += Math.sqrt(re * re + im * im).toFloat()
                    }
                }
                chroma[bin] += energy
            }
        }

        // 归一化
        val max = chroma.maxOrNull() ?: 1f
        if (max > 0) {
            for (i in chroma.indices) chroma[i] /= max
        }

        return chroma
    }

    /**
     * Krumhansl-Schmuckler 调性检测
     */
    private fun krumhanslSchmuckler(chroma: FloatArray): String {
        // 大调 profile (C major)
        val majorProfile = floatArrayOf(6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f)
        // 小调 profile (C minor)
        val minorProfile = floatArrayOf(6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f)

        val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        var bestKey = ""
        var bestCorrelation = -1.0

        // 遍历 24 个调性（12 大调 + 12 小调）
        for (shift in 0 until 12) {
            val rotatedChroma = rotateArray(chroma, shift)

            // 大调相关性
            val majorCorr = pearsonCorrelation(rotatedChroma, majorProfile)
            if (majorCorr > bestCorrelation) {
                bestCorrelation = majorCorr
                bestKey = noteNames[shift]  // 大调不加 m
            }

            // 小调相关性
            val minorCorr = pearsonCorrelation(rotatedChroma, minorProfile)
            if (minorCorr > bestCorrelation) {
                bestCorrelation = minorCorr
                bestKey = noteNames[shift] + "m"  // 小调加 m
            }
        }

        return bestKey
    }

    private fun rotateArray(arr: FloatArray, shift: Int): FloatArray {
        val result = FloatArray(arr.size)
        for (i in arr.indices) {
            result[i] = arr[(i + shift) % arr.size]
        }
        return result
    }

    private fun pearsonCorrelation(x: FloatArray, y: FloatArray): Double {
        val n = x.size
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumX2 = 0.0
        var sumY2 = 0.0

        for (i in 0 until n) {
            sumX += x[i]
            sumY += y[i]
            sumXY += x[i] * y[i]
            sumX2 += x[i] * x[i]
            sumY2 += y[i] * y[i]
        }

        val denom = Math.sqrt(sumX2 - sumX * sumX / n) * Math.sqrt(sumY2 - sumY * sumY / n)
        if (denom == 0.0) return 0.0
        return (sumXY - sumX * sumY / n) / denom
    }

    /**
     * 验证 Key 格式是否合法
     */
    private fun isValidKey(key: String): Boolean {
        val trimmed = key.trim()
        // 支持 Camelot 格式 (1A-12A, 1B-12B)
        if (trimmed.matches(Regex("^(1[0-2]|[1-9])[AB]$"))) return true
        val validKeys = setOf(
            "C", "C#", "Db", "D", "D#", "Eb", "E", "F", "F#", "Gb", "G", "G#", "Ab", "A", "A#", "Bb", "B",
            "Cm", "C#m", "Dbm", "Dm", "D#m", "Ebm", "Em", "Fm", "F#m", "Gbm", "Gm", "G#m", "Abm", "Am", "A#m", "Bbm", "Bm"
        )
        return trimmed in validKeys
    }

    /**
     * 将传统调性记号转换为 Camelot Wheel 记谱法
     * 大调 → B（如 C=8B, G=3B）
     * 小调 → A（如 Am=8A, Em=3A）
     *
     * Camelot Wheel 映射（按半音位置 0-11）:
     * 0=C, 1=C#/Db, 2=D, 3=D#/Eb, 4=E, 5=F,
     * 6=F#/Gb, 7=G, 8=G#/Ab, 9=A, 10=A#/Bb, 11=B
     *
     * Camelot 编号：
     * 大调 B: C=8, C#/Db=3, D=10, D#/Eb=5, E=12, F=7,
     *         F#/Gb=2, G=9, G#/Ab=4, A=11, A#/Bb=6, B=1
     * 小调 A: Cm=5, C#m/Dbm=12, Dm=7, D#m/Ebm=2, Em=9, Fm=4,
     *         F#m/Gbm=11, Gm=6, G#m/Abm=1, Am=8, A#m/Bbm=3, Bm=10
     */
    fun toCamelot(traditionalKey: String): String {
        val trimmed = traditionalKey.trim()

        // 已经是 Camelot 格式，直接Back
        if (trimmed.matches(Regex("^(1[0-2]|[1-9])[AB]$"))) return trimmed

        val isMinor = trimmed.endsWith("m", ignoreCase = true)
        val root = if (isMinor) trimmed.dropLast(1) else trimmed

        // 半音位置映射
        val semitoneMap = mapOf(
            "C" to 0, "C#" to 1, "Db" to 1,
            "D" to 2, "D#" to 3, "Eb" to 3,
            "E" to 4, "F" to 5,
            "F#" to 6, "Gb" to 6,
            "G" to 7, "G#" to 8, "Ab" to 8,
            "A" to 9, "A#" to 10, "Bb" to 10,
            "B" to 11
        )

        val semitone = semitoneMap[root] ?: return trimmed // 无法识别则原样Back

        // Camelot 编号映射
        val majorCamelot = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)
        val minorCamelot = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)

        val number = if (isMinor) minorCamelot[semitone] else majorCamelot[semitone]
        val letter = if (isMinor) "A" else "B"

        return "$number$letter"
    }

    /**
     * 【Steven】批量扫描 BPM、Key 和流派
     * 在后台线程运行
     */
    suspend fun scanAudioFeatures(
        context: Context,
        songs: List<Song>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<Song> = withContext(Dispatchers.IO) {
        songs.mapIndexed { index, song ->
            onProgress(index, songs.size)

            // 检测 BPM
            val bpm = if (song.bpm > 0) {
                song.bpm
            } else {
                detectBPM(context, Uri.parse(song.path))
            }

            // 如果已经有流派信息，跳过
            val genre = if (song.genre.isNotBlank()) {
                song.genre
            } else {
                // 估算流派
                estimateGenre(bpm, song.duration, song.format, song.title, song.filePath, song.artist)
            }

            // 检测调性 Key
            val key = if (song.key.isNotBlank()) {
                song.key
            } else {
                detectKey(context, Uri.parse(song.path), song.filePath)
            }

            song.copy(bpm = bpm, genre = genre, key = key)
        }
    }
}

