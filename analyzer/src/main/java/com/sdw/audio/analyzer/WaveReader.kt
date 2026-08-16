package com.sdw.audio.analyzer

import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Direct PCM reader for WAV files — bypasses MediaCodec, near-instant read.
 *
 * Reads only [maxSamples] mono frames from [startSec] position.
 * Handles 16-bit and 24-bit packed PCM.
 */
object WaveReader {

    data class PcmResult(
        val samples: FloatArray,
        val sampleRate: Int,
        val channelCount: Int,
        val bitDepth: Int
    )

    fun read(filePath: String, startSec: Int = 5, maxSamples: Int = 48000 * 8): PcmResult? {
        if (!filePath.lowercase().endsWith(".wav")) return null

        return try {
            FileInputStream(filePath).use { fis ->
                val header = ByteArray(44)
                if (fis.read(header) < 44) return null

                val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

                // "RIFF"
                if (buf.getInt(0) != 0x46464952) return null  // "RIFF"
                // "WAVE"
                if (buf.getInt(8) != 0x45564157) return null  // "WAVE"
                // "fmt "
                if (buf.getInt(12) != 0x20746D66) return null // "fmt "

                val audioFormat = buf.getShort(20).toInt() and 0xFFFF
                if (audioFormat != 1) return null // PCM only

                val channels = buf.getShort(22).toInt() and 0xFFFF
                val sampleRate = buf.getInt(24)
                val bitsPerSample = buf.getShort(34).toInt() and 0xFFFF
                val bytesPerSample = bitsPerSample / 8
                val frameSize = channels * bytesPerSample

                // Find "data" chunk (may not be at offset 36 due to extra chunks)
                var dataOffset = 36
                while (dataOffset < header.size - 8) {
                    val chunkId = buf.getInt(dataOffset)
                    val chunkSize = buf.getInt(dataOffset + 4)
                    if (chunkId == 0x61746164) { // "data"
                        dataOffset += 8
                        break
                    }
                    dataOffset += 8 + chunkSize
                }

                // Seek to target start position
                val skipBytes = (startSec.toLong() * sampleRate * frameSize).coerceAtMost(Int.MAX_VALUE.toLong())
                fis.skip(skipBytes)

                // Read PCM samples
                val totalSamples = maxSamples.coerceAtMost(
                    ((fis.available().toLong() / frameSize).coerceAtMost(maxSamples * channels.toLong())).toInt()
                )

                val raw = ByteArray(totalSamples * frameSize)
                val read = fis.read(raw)
                if (read < frameSize) return null

                val result = FloatArray(read / bytesPerSample) { i ->
                    when (bitsPerSample) {
                        16 -> {
                            val byteIdx = i * 2
                            val sample = ((raw[byteIdx + 1].toInt() shl 8) or (raw[byteIdx].toInt() and 0xFF)).toShort()
                            sample.toFloat() / 32768f
                        }
                        24 -> {
                            val byteIdx = i * 3
                            val sample = ((raw[byteIdx + 2].toInt() shl 16) or
                                    ((raw[byteIdx + 1].toInt() and 0xFF) shl 8) or
                                    (raw[byteIdx].toInt() and 0xFF))
                            val s24 = if (sample >= 0x800000) sample - 0x1000000 else sample
                            s24.toFloat() / 8388608f
                        }
                        32 -> {
                            val byteIdx = i * 4
                            val sample = ((raw[byteIdx + 3].toInt() shl 24) or
                                    ((raw[byteIdx + 2].toInt() and 0xFF) shl 16) or
                                    ((raw[byteIdx + 1].toInt() and 0xFF) shl 8) or
                                    (raw[byteIdx].toInt() and 0xFF))
                            sample.toFloat() / 2147483648f
                        }
                        else -> 0f
                    }
                }

                // Mix down to mono if multi-channel
                val mono = if (channels > 1) {
                    val frames = result.size / channels
                    FloatArray(frames) { f ->
                        var sum = 0.0f
                        for (ch in 0 until channels) sum += result[f * channels + ch]
                        sum / channels
                    }
                } else result

                PcmResult(mono, sampleRate, channels, bitsPerSample)
            }
        } catch (_: Exception) {
            null
        }
    }
}
