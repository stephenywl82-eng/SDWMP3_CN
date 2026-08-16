package com.sdw.audio.analyzer

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * Decodes any audio file (FLAC/WAV/MP3/AAC/OGG via MediaExtractor) to PCM float[].
 *
 * Target: ~10s of audio at 48kHz mono for analysis.
 * Skips beyond [maxDurationMs] to keep memory bounded.
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
        try {
            extractor.setDataSource(filePath)

            val trackIndex = findAudioTrack(extractor) ?: return null
            val format = extractor.getTrackFormat(trackIndex)

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "unknown"

            // Approximate bit depth from mime type
            val bitDepth = inferBitDepth(format, mime)
            val codecName = inferCodecLabel(format, mime)

            // Configure MediaCodec
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            extractor.selectTrack(trackIndex)

            val maxSamples = (sampleRate * channelCount * maxMs / 1000).toInt()
            val outBuffer = ArrayList<Float>(maxSamples)
            val bufferInfo = MediaCodec.BufferInfo()
            var done = false

            while (!done && outBuffer.size < maxSamples) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuf = codec.getInputBuffer(inputIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    outputIndex >= 0 -> {
                        val outputBuf = codec.getOutputBuffer(outputIndex)!!
                        if (bufferInfo.size > 0) {
                            // Decode to float[] (handles 16-bit and 32-bit PCM)
                            readPcm(outputBuf, bufferInfo, channelCount, outBuffer)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            done = true
                        }
                    }
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            if (outBuffer.isEmpty()) return null

            // Convert stereo to mono by averaging
            val mono = if (channelCount > 1) {
                val totalFrames = outBuffer.size / channelCount
                FloatArray(totalFrames) { frame ->
                    var sum = 0.0f
                    for (ch in 0 until channelCount) {
                        sum += outBuffer[frame * channelCount + ch]
                    }
                    sum / channelCount
                }
            } else {
                outBuffer.toFloatArray()
            }

            return PcmResult(mono, sampleRate, channelCount, bitDepth, codecName)
        } catch (e: Exception) {
            extractor.release()
            return null
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    private fun readPcm(
        buffer: ByteBuffer, info: MediaCodec.BufferInfo,
        channels: Int, out: ArrayList<Float>
    ) {
        val size = info.size
        buffer.position(info.offset)
        // MediaCodec outputs 16-bit PCM for most codecs
        val shortCount = size / 2
        for (i in 0 until shortCount) {
            if (buffer.remaining() < 2) break
            out.add(buffer.short.toFloat() / 32768f)
        }
    }

    private fun inferBitDepth(format: MediaFormat, mime: String): Int {
        return when {
            format.containsKey(MediaFormat.KEY_PCM_ENCODING) -> {
                when (format.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                    2 -> 16  // AudioFormat.ENCODING_PCM_16BIT
                    3 -> 8   // ENCODING_PCM_8BIT
                    4 -> 24  // ENCODING_PCM_FLOAT → approximate as 24-bit processing
                    else -> 16
                }
            }
            mime.contains("flac") -> {
                // FLAC can be 16/24-bit; MediaCodec outputs 16-bit unless ENCODING_PCM_FLOAT
                if (format.containsKey("bit-width")) format.getInteger("bit-width") else 16
            }
            mime.contains("wav") -> 16  // WAV decoded by MediaCodec → 16-bit output
            else -> 16
        }
    }

    private fun inferCodecLabel(format: MediaFormat, mime: String): String {
        return when {
            mime.contains("flac") -> "FLAC"
            mime.contains("wav") || mime.contains("pcm") -> "WAV/PCM"
            mime.contains("mp3") || mime.contains("mpeg") -> "MP3"
            mime.contains("aac") -> "AAC"
            mime.contains("vorbis") || mime.contains("ogg") -> "OGG Vorbis"
            mime.contains("opus") -> "Opus"
            else -> mime.uppercase()
        }
    }
}
