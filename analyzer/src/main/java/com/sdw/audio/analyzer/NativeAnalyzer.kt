package com.sdw.audio.analyzer

/**
 * JNI bridge to native FFT + metric computation.
 * All float[]-based, no allocations in native hot path.
 */
object NativeAnalyzer {
    init {
        System.loadLibrary("audio_analyzer")
    }

    /** Run FFT on PCM float[], return magnitude spectrum (half of fftSize). */
    external fun computeSpectrum(pcmData: FloatArray, fftSize: Int): FloatArray?

    /** Find -3dB cutoff frequency in Hz. binResolutionHz = sampleRate / fftSize. */
    external fun findCutoffFrequency(magnitudeBins: FloatArray, startBin: Int, binResolutionHz: Float): Float

    /** Return float[3]: [rms, peak, dcOffset]. */
    external fun computeRmsPeak(pcmData: FloatArray): FloatArray

    /** Spectral flatness: geometric mean / arithmetic mean. 0..1, higher = flatter = more compressed. */
    external fun computeSpectralFlatness(magnitudeBins: FloatArray): Float

    /** Count frames with ≥3 consecutive samples exceeding threshold (default 0.999f). */
    external fun countClippedFrames(pcmData: FloatArray, frameSize: Int, threshold: Float): Int
}
