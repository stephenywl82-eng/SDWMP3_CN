package com.sdw.audio.analyzer

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes any audio file (FLAC/WAV/MP3/AAC/OGG via MediaExtractor) to PCM float[].
 *
 * Target: ~10s of audio at 48kHz mono for analysis.
 * Skips beyond [maxDurationMs] to keep memory bounded.
 *
 * V8.120 Hardened against malformed / exotic containers (e.g. m4a with ALAC or
 * unusual AAC variants) that can make MediaCodec stall without emitting EOS:
 *  - decode loop now has iteration + wall-clock caps (was: unbounded, could hang)
 *  - sample accumulation uses a growable primitive FloatArray (was: ArrayList<Float>
 *    boxing every sample -> OutOfMemoryError, which is an Error and escaped the
 *    catch(Exception) guards in the caller)
 *  - PCM output is decoded per MediaFormat.KEY_PCM_ENCODING (was: always 16-bit)
 *  - null buffers are handled instead of the not-null assertion operator
 */
class AudioDecoder {

    data class PcmResult(
        val samples: FloatArray,     // mono, float [-1..1]
        val sampleRate: Int,
        val channelCount: Int,
        val bitDepth: Int,
        val codecName: String
    )

    /**
     * Decode up to [maxMs] of audio from [filePath] into mono float[].
     * Returns null on failure.
     */
    fun decode(filePath: String, maxMs: Long = 10_000): PcmResult? {
        // Fast path: WAV direct read (bypass MediaCodec)
        if (filePath.lowercase().endsWith(".wav")) {
            val wav = WaveReader.read(filePath, maxSamples = (48000 * maxMs / 1000).toInt())
            if (wav != null) {
                return PcmResult(wav.samples, wav.sampleRate, wav.channelCount, wav.bitDepth, "WAV/PCM")
            }
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(filePath)

            val trackIndex = findAudioTrack(extractor) ?: return null
            var format = extractor.getTrackFormat(trackIndex)

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "unknown"

            // [V8.120] Guard against nonsense values (malformed containers).
            if (sampleRate <= 0 || channelCount <= 0) return null

            // Approximate bit depth from mime type
            val bitDepth = inferBitDepth(format, mime)
            val codecName = inferCodecLabel(format, mime)

            // Configure MediaCodec
            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            extractor.selectTrack(trackIndex)

            val maxSamples = ((sampleRate.toLong() * channelCount.toLong() * maxMs / 1000L)
                .coerceAtLeast(1L)
                .coerceAtMost(MAX_TOTAL_SAMPLES.toLong())).toInt()

            val sink = FloatSink(maxSamples)
            val bufferInfo = MediaCodec.BufferInfo()
            var done = false
            var encoding = pcmEncodingOf(format)

            // [V8.120] Hard caps so a stalled codec cannot hang the scan forever.
            val deadlineNs = System.nanoTime() + MAX_WALL_NS
            var iterations = 0

            while (!done && sink.size < maxSamples &&
                iterations < MAX_ITERATIONS && System.nanoTime() < deadlineNs
            ) {
                iterations++
                val inputIndex = dec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuf = dec.getInputBuffer(inputIndex)
                    if (inputBuf == null) {
                        dec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            dec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            dec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = dec.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Authoritative PCM encoding / channel layout lives here.
                        val outFmt = runCatching { dec.outputFormat }.getOrNull()
                        if (outFmt != null) {
                            encoding = pcmEncodingOf(outFmt)
                            format = outFmt
                        }
                        continue
                    }
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    outputIndex >= 0 -> {
                        val outputBuf = dec.getOutputBuffer(outputIndex)
                        if (outputBuf != null && bufferInfo.size > 0) {
                            readPcm(outputBuf, bufferInfo, encoding, sink)
                        }
                        dec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            done = true
                        }
                    }
                }
            }

            try { dec.stop() } catch (_: Throwable) {}
            try { dec.release() } catch (_: Throwable) {}
            codec = null
            try { extractor.release() } catch (_: Throwable) {}

            if (sink.isEmpty()) return null

            val all = sink.toArray()
            // Convert interleaved -> mono by averaging channels
            val mono = if (channelCount > 1) {
                val totalFrames = all.size / channelCount
                if (totalFrames <= 0) return null
                FloatArray(totalFrames) { frame ->
                    var sum = 0f
                    val base = frame * channelCount
                    for (ch in 0 until channelCount) sum += all[base + ch]
                    sum / channelCount
                }
            } else {
                all
            }

            return PcmResult(mono, sampleRate, channelCount, bitDepth, codecName)
        } catch (t: Throwable) {
            // [V8.120] Catch Throwable (not just Exception): OOM/other Errors from a
            // single bad file must not take the whole scan / process down.
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
            return null
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val fmt = runCatching { extractor.getTrackFormat(i) }.getOrNull() ?: continue
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    /** V8.120 Read PCM honouring the actual sample encoding, not a fixed 16-bit assumption. */
    private fun readPcm(buffer: ByteBuffer, info: MediaCodec.BufferInfo, encoding: Int, out: FloatSink) {
        val bb = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val end = info.offset + info.size
        bb.position(info.offset)
        bb.limit(minOf(end, buffer.capacity()))

        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {          // 4
                while (bb.remaining() >= 4) out.add(bb.float)
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {   // 21
                while (bb.remaining() >= 3) {
                    val b0 = bb.get().toInt() and 0xFF
                    val b1 = bb.get().toInt() and 0xFF
                    val b2 = bb.get().toInt() and 0xFF
                    var v = (b2 shl 16) or (b1 shl 8) or b0
                    if (v >= 0x800000) v -= 0x1000000
                    out.add(v / 8388608f)
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {           // 3 (unsigned)
                while (bb.remaining() >= 1) {
                    out.add(((bb.get().toInt() and 0xFF) - 128) / 128f)
                }
            }
            else -> {                                    // 2 = 16-bit (default)
                while (bb.remaining() >= 2) out.add(bb.short / 32768f)
            }
        }
    }

    private fun pcmEncodingOf(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            runCatching { format.getInteger(MediaFormat.KEY_PCM_ENCODING) }
                .getOrDefault(AudioFormat.ENCODING_PCM_16BIT)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }

    private fun inferBitDepth(format: MediaFormat, mime: String): Int {
        return when {
            format.containsKey(MediaFormat.KEY_PCM_ENCODING) -> {
                when (format.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                    AudioFormat.ENCODING_PCM_8BIT -> 8
                    AudioFormat.ENCODING_PCM_FLOAT -> 32
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                    else -> 16
                }
            }
            mime.contains("flac") -> {
                if (format.containsKey("bit-width")) format.getInteger("bit-width") else 16
            }
            mime.contains("wav") -> 16
            else -> 16
        }
    }

    private fun inferCodecLabel(format: MediaFormat, mime: String): String {
        return when {
            mime.contains("flac") -> "FLAC"
            mime.contains("wav") || mime.contains("pcm") -> "WAV/PCM"
            mime.contains("mp3") || mime.contains("mpeg") -> "MP3"
            mime.contains("alac") -> "ALAC"
            mime.contains("aac") -> "AAC"
            mime.contains("vorbis") || mime.contains("ogg") -> "OGG Vorbis"
            mime.contains("opus") -> "Opus"
            else -> mime.uppercase()
        }
    }

    /** Growable primitive float array — avoids per-sample boxing of ArrayList<Float>. */
    private class FloatSink(initialCapacity: Int) {
        var size = 0
            private set
        private var array = FloatArray(initialCapacity.coerceIn(16, MAX_TOTAL_SAMPLES))

        fun add(v: Float) {
            if (size >= array.size) {
                val next = (array.size.toLong() * 2L).coerceAtMost(MAX_TOTAL_SAMPLES.toLong()).toInt()
                if (next <= array.size) return // hard cap reached, drop overflow
                array = array.copyOf(next)
            }
            array[size++] = v
        }

        fun isEmpty() = size == 0
        fun toArray(): FloatArray = array.copyOf(size)
    }

    private companion object {
        const val INPUT_TIMEOUT_US = 10_000L
        const val OUTPUT_TIMEOUT_US = 10_000L
        /** V8.120 ~30s of mono samples — absolute ceiling regardless of input. */
        const val MAX_TOTAL_SAMPLES = 48_000 * 30
        /** V8.120 Iteration cap: 10ms polling * 20k ≈ 200s worst case, unreachable in practice. */
        const val MAX_ITERATIONS = 20_000
        /** V8.120 Wall-clock cap for a single file decode. */
        const val MAX_WALL_NS = 20_000_000_000L
    }
}
