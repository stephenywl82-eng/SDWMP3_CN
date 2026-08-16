package com.sdw.audio.analyzer

/**
 * Runs quality analysis on decoded PCM data using native FFT + metrics.
 */
class QualityAnalyzer {

    companion object {
        const val FFT_SIZE = 8192
        const val FRAME_SIZE = 1024  // samples per clipping detection frame
        const val CLIP_THRESHOLD = 0.999f
        const val HIGH_FREQ_START_BIN = 40  // skip bass region for cutoff detection
    }

    fun analyze(pcm: AudioDecoder.PcmResult, fileInfo: AudioFileInfo): AudioQualityReport {
        val sr = pcm.sampleRate
        val binRes = sr.toFloat() / FFT_SIZE

        // 1. RMS, Peak, DC offset (native, one-pass)
        val rpDc = NativeAnalyzer.computeRmsPeak(pcm.samples)
        val rms = rpDc[0]
        val peak = rpDc[1]
        val dc = rpDc[2]

        // 2. Spectrum of a representative segment
        val segment = extractSegment(pcm.samples, sr, 5, 3) // 5s in, 3s window
        val magBins = NativeAnalyzer.computeSpectrum(segment, FFT_SIZE) ?: FloatArray(FFT_SIZE / 2)

        // 3. Frequency cutoff (-3dB)
        val startBin = if (sr >= 44100) HIGH_FREQ_START_BIN else 20
        val cutoffHz = NativeAnalyzer.findCutoffFrequency(magBins, startBin, binRes)

        // 4. Spectral flatness (compression detection)
        val flatness = NativeAnalyzer.computeSpectralFlatness(magBins)

        // 5. Clipped frames
        val totalFrames = pcm.samples.size / FRAME_SIZE
        val clippedCount = NativeAnalyzer.countClippedFrames(pcm.samples, FRAME_SIZE, CLIP_THRESHOLD)
        val clippedRatio = if (totalFrames > 0) clippedCount.toFloat() / totalFrames else 0f

        // 6. Noise floor: RMS of quietest 5% of frames
        val noiseFloor = estimateNoiseFloor(pcm.samples, FRAME_SIZE)

        // 7. Dynamic range
        val peakDb = if (peak > 0f) 20f * Math.log10(peak.toDouble()).toFloat() else -96f
        val rmsDb = if (rms > 0f) 20f * Math.log10(rms.toDouble()).toFloat() else -96f
        val dynamicRange = (peakDb - rmsDb).coerceAtLeast(0f)  // crest factor: true musical dynamic range

        // 8. Scoring
        val score = computeScore(cutoffHz, dynamicRange, clippedRatio, flatness, noiseFloor, pcm.bitDepth)
        val label = qualityLabel(score)
        val tags = buildTags(cutoffHz, dynamicRange, clippedRatio, flatness, fileInfo, pcm)

        return AudioQualityReport(
            filePath = fileInfo.filePath,
            fileName = fileInfo.fileName,
            fileSize = fileInfo.fileSize,
            durationSeconds = pcm.samples.size.toFloat() / pcm.sampleRate,
            sampleRate = pcm.sampleRate,
            channelCount = pcm.channelCount,
            bitDepth = pcm.bitDepth,
            codecName = pcm.codecName,
            frequencyCutoff = cutoffHz,
            dynamicRangeDb = dynamicRange,
            peakDb = peakDb,
            rmsDb = rmsDb,
            noiseFloorDb = noiseFloor,
            clippedFrameRatio = clippedRatio,
            spectralFlatness = flatness,
            dcOffset = dc,
            qualityScore = score,
            qualityLabel = label,
            suspectedSourceVariant = tags
        )
    }

    /**
     * Pick a window of audio for spectrum analysis.
     * Returns [durationSec] seconds of mono float[], starting at [startSec].
     */
    private fun extractSegment(samples: FloatArray, sampleRate: Int, startSec: Int, durationSec: Int): FloatArray {
        val start = (startSec * sampleRate).coerceAtMost(samples.size - FFT_SIZE)
        val end = (start + durationSec * sampleRate).coerceAtMost(samples.size)
        val len = end - start
        if (len < FFT_SIZE) {
            // fallback: take from beginning
            return samples.copyOfRange(0, samples.size.coerceAtMost(FFT_SIZE * 4))
        }
        return samples.copyOfRange(start, end)
    }

    private fun estimateNoiseFloor(samples: FloatArray, frameSize: Int): Float {
        val frames = samples.size / frameSize
        if (frames < 10) return -60f

        val rmsPerFrame = FloatArray(frames) { f ->
            var sumSq = 0.0
            val offset = f * frameSize
            for (i in 0 until frameSize) {
                val v = samples[offset + i].toDouble()
                sumSq += v * v
            }
            (sumSq / frameSize).toFloat()
        }
        rmsPerFrame.sort()
        // Quietest 10% average
        val quiet = rmsPerFrame.copyOfRange(0, (frames * 0.1).toInt().coerceAtLeast(1))
        val avgRms = quiet.average().toFloat()
        return if (avgRms > 0f) 20f * Math.log10(avgRms.toDouble()).toFloat() else -96f
    }

    private fun computeScore(
        cutoffHz: Float, dynamicDb: Float,
        clippedRatio: Float, flatness: Float,
        noiseFloorDb: Float, bitDepth: Int
    ): Float {
        // ── 1. Cutoff (0–30) — the #1 quality discriminator ──
        val cutoffScore = when {
            cutoffHz <= 0f -> 0f
            cutoffHz < 8000f -> 3f
            cutoffHz < 12000f -> 8f
            cutoffHz < 14000f -> 13f
            cutoffHz < 15000f -> 16f   // MP3 128kbps typical
            cutoffHz < 16000f -> 19f   // MP3 192kbps
            cutoffHz < 17000f -> 21f   // MP3 256kbps
            cutoffHz < 18500f -> 23f   // MP3 320kbps / borderline
            cutoffHz < 20000f -> 25f   // AAC 320 / older lossless
            cutoffHz < 22000f -> 27f   // modern CD-quality FLAC
            cutoffHz < 30000f -> 29f   // 48kHz master
            else -> 30f                // true hi-res
        }

        // ── 2. Spectral flatness (0–30) — lossy fingerprint, most discriminatory ──
        val flatScore = when {
            flatness < 0.03f -> 30f   // pristine lossless
            flatness < 0.06f -> 27f
            flatness < 0.10f -> 23f   // typical FLAC/WAV
            flatness < 0.15f -> 18f
            flatness < 0.20f -> 13f
            flatness < 0.30f -> 8f
            flatness < 0.40f -> 4f    // borderline lossy
            flatness < 0.55f -> 1f    // typical MP3/AAC
            flatness < 0.70f -> 0f    // low-bitrate lossy
            else -> 0f                // terrible
        }

        // ── 3. Dynamic range / crest factor (0–20) — measures loudness war damage ──
        val dr = dynamicDb.coerceIn(0f, 25f)
        val dynScore = when {
            dr >= 20f -> 20f   // audiophile / classical / jazz
            dr >= 16f -> 18f   // well-mastered
            dr >= 12f -> 14f   // decent modern
            dr >= 9f  -> 10f   // typical pop/rock
            dr >= 6f  -> 6f    // somewhat compressed
            dr >= 4f  -> 3f    // heavy loudness war
            dr >= 2f  -> 1f    // brickwalled
            else      -> 0f    // destroyed
        }

        // ── 4. Clipping (0–5) — bonus, most songs are clean ──
        val clipPercent = clippedRatio * 100f
        val clipScore = when {
            clipPercent < 0.001f -> 5f   // pristine
            clipPercent < 0.01f  -> 4f
            clipPercent < 0.1f   -> 3f
            clipPercent < 1f     -> 2f
            clipPercent < 5f     -> 1f
            else                -> 0f    // heavy clipping
        }

        // ── 5. Noise floor (0–5) — bonus ──
        val noise = noiseFloorDb.coerceIn(-96f, -30f)
        val noiseScore = ((noise + 96f) / 66f).coerceIn(0f, 1f) * 5f

        // ── 6. Bit depth (0–5) — audiophile bonus ──
        val bitScore = when {
            bitDepth >= 32 -> 5f
            bitDepth >= 24 -> 4f
            bitDepth >= 16 -> 2f
            else -> 0f
        }

        // ── 7. Hi-Res bonus (0–5) — real ultrasonic energy ──
        val hiResBonus = when {
            cutoffHz >= 40000f -> 5f
            cutoffHz >= 30000f -> 4f
            cutoffHz >= 25000f -> 3f
            cutoffHz >= 22000f -> 2f
            else -> 0f
        }

        var total = cutoffScore + flatScore + dynScore + clipScore + noiseScore + bitScore + hiResBonus

        // ── Penalties ──
        // Suspiciously high DR: likely silence gap at start/end
        if (dynamicDb > 30f) total -= 4f
        // Low cutoff on a lossless container = likely fake
        if (cutoffHz in 1f..14000f) total -= 10f
        // Flat spectrum + low cutoff = confirmed lossy transcode
        if (flatness > 0.45f && cutoffHz < 16000f) total -= 8f
        // Flat spectrum on high-resolution source = upscaled fake
        if (flatness > 0.25f && cutoffHz >= 20000f) total -= 4f

        return total.coerceIn(0f, 100f)
    }

    private fun qualityLabel(score: Float): String = when {
        score >= 82f -> "Excellent"
        score >= 65f -> "Good"
        score >= 40f -> "Fair"
        else -> "Poor"
    }

    /**
     * Multi-signal fake-lossless detection.
     * Returns true only when ≥2 of 3 signals agree:
     *  1. cutoff < 18kHz (steep rolloff typical of lossy encodes)
     *  2. flatness > 0.5 (spectral holes / MP3 block artifacts)
     *  3. codec is FLAC/WAV but content looks lossy-sourced
     * Single-signal matches (e.g. classical recording with low natural cutoff) are not flagged.
     */
    fun detectFakeLossless(
        cutoffHz: Float, flatness: Float, codecName: String,
        sampleRate: Int, fileExt: String
    ): Boolean {
        // Only applicable to formats that claim lossless
        val looksLossless = codecName == "FLAC" || codecName == "WAV/PCM" ||
            codecName == "ALAC" || codecName == "APE" || fileExt == "wav" || fileExt == "flac"
        if (!looksLossless) return false

        var signals = 0
        // Signal 1: sharp cutoff below 18kHz (MP3/AAC encode marker)
        if (cutoffHz > 0f && cutoffHz < 18000f) signals++
        // Signal 2: spectrally flat (lossy codecs drop high-freq detail → flat plateau)
        if (flatness > 0.5f) signals++
        // Signal 3: high-res claim but content is ≤44.1kHz (upsampled)
        if (sampleRate > 44100 && cutoffHz < 20000f) signals++

        return signals >= 2
    }

    private fun buildTags(
        cutoffHz: Float, dynamicDb: Float, clippedRatio: Float,
        flatness: Float, info: AudioFileInfo, pcm: AudioDecoder.PcmResult
    ): List<String> {
        val tags = mutableListOf<String>()
        if (info.isLossless) tags.add("Lossless")
        if (pcm.codecName == "FLAC" && pcm.bitDepth >= 24) tags.add("24bit Hi-Res")
        if (pcm.sampleRate >= 96000) tags.add("${pcm.sampleRate / 1000}kHz Hi-Res")
        if (cutoffHz >= 20000f) tags.add("Full Spectrum")
        else if (cutoffHz in 16000f..19999f) tags.add("Good HF")
        else if (cutoffHz > 0f && cutoffHz < 14000f) tags.add("HF Cutoff (<14kHz)")
        if (dynamicDb >= 15f) tags.add("High Dynamic")
        else if (dynamicDb < 6f) tags.add("Compressed")
        if (clippedRatio > 0.05f) tags.add("Heavy Clipping")
        else if (clippedRatio > 0.01f) tags.add("Light Clipping")
        if (flatness > 0.7f) tags.add("Flat Spectrum (Mp3?)")
        if (pcm.codecName == "MP3") tags.add("MP3 Encoded")
        if (pcm.codecName == "AAC") tags.add("AAC Encoded")
        // Composite fake-lossless detection (multi-signal, avoids false positives)
        val ext = info.filePath.substringAfterLast('.', "").lowercase()
        if (detectFakeLossless(cutoffHz, flatness, pcm.codecName, pcm.sampleRate, ext)) {
            tags.add(0, "⚠️ Suspect Fake Lossless") // prepend so it shows first
        }
        return tags
    }
}
