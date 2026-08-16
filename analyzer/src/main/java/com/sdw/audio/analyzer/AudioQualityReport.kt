package com.sdw.audio.analyzer

/**
 * Data class for a single track's audio quality analysis result.
 */
data class AudioQualityReport(
    val filePath: String,
    val fileName: String,
    val fileSize: Long,
    val durationSeconds: Float,
    val sampleRate: Int,
    val channelCount: Int,
    val bitDepth: Int,
    val codecName: String,

    // Spectrum metrics
    val frequencyCutoff: Float,       // Hz — -3dB cutoff above 1kHz
    val dynamicRangeDb: Float,        // dB
    val peakDb: Float,                // dBFS
    val rmsDb: Float,                 // dBFS
    val noiseFloorDb: Float,          // dB (estimated from quietest frames)

    // Distortion metrics
    val clippedFrameRatio: Float,     // 0..1 — frames with consecutive max samples
    val spectralFlatness: Float,      // 0..1 — higher = flatter = likely compressed
    val dcOffset: Float,              // normalized -1..1

    // Classification
    val qualityScore: Float,          // 0..100 weighted score
    val qualityLabel: String,         // "Excellent" / "Good" / "Fair" / "Poor"
    val suspectedSourceVariant: List<String>  // tags e.g. "Lossless", "MP3 Compressed", "Clipped"
) {
    fun toMarkdown(): String = buildString {
        appendLine("# $fileName")
        appendLine()
        appendLine("| Metric | Value |")
        appendLine("|--------|-------|")
        appendLine("| Duration | $durationSeconds s |")
        appendLine("| Sample Rate | $sampleRate Hz |")
        appendLine("| Channels | $channelCount |")
        appendLine("| Bit Depth | $bitDepth |")
        appendLine("| Codec | $codecName |")
        appendLine("| Frequency Cutoff (-3dB) | ${"%.1f".format(frequencyCutoff)} Hz |")
        appendLine("| Dynamic Range | ${"%.1f".format(dynamicRangeDb)} dB |")
        appendLine("| Peak | ${"%.2f".format(peakDb)} dBFS |")
        appendLine("| RMS | ${"%.2f".format(rmsDb)} dBFS |")
        appendLine("| Noise Floor | ${"%.1f".format(noiseFloorDb)} dB |")
        appendLine("| Clipped Frames | ${"%.1f".format(clippedFrameRatio * 100)}% |")
        appendLine("| Spectral Flatness | ${"%.3f".format(spectralFlatness)} |")
        appendLine("| DC Offset | ${"%.6f".format(dcOffset)} |")
        appendLine()
        appendLine("## Score: $qualityScore / 100 — $qualityLabel")
        if (suspectedSourceVariant.isNotEmpty()) {
            appendLine("### Tags: ${suspectedSourceVariant.joinToString(", ")}")
        }
    }
}
