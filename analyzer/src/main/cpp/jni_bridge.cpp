/**
 * jni_bridge.cpp — JNI entry points for AudioQualityAnalyzer.
 *
 * Exposes:
 *   - computeSpectrum: run FFT on float[] PCM, return magnitude bins
 *   - findFrequencyCutoff: -3dB cutoff from magnitude bins
 *   - computeRmsPeak: RMS + Peak + DC offset in one pass
 */

#include <jni.h>
#include <android/log.h>
#include "fft_analyzer.h"
#include <cstring>
#include <algorithm>
#include <cmath>
#include <vector>

#define TAG "AudioAnalyzerJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jfloatArray JNICALL
Java_com_sdw_audio_analyzer_NativeAnalyzer_computeSpectrum(
    JNIEnv *env, jclass /*clazz*/,
    jfloatArray pcmData, jint fftSize
) {
    jsize len = env->GetArrayLength(pcmData);
    if (len < fftSize) return nullptr;

    std::vector<float> real(fftSize);
    std::vector<float> imag(fftSize, 0.0f);

    jfloat *pcm = env->GetFloatArrayElements(pcmData, nullptr);
    for (int i = 0; i < fftSize; i++) {
        // Hann window
        float w = 0.5f * (1.0f - std::cos(2.0f * 3.14159265358979323846f * i / (fftSize - 1)));
        real[i] = pcm[i] * w;
    }
    env->ReleaseFloatArrayElements(pcmData, pcm, JNI_ABORT);

    fft(real.data(), imag.data(), fftSize, false);

    // Pack interleaved real/imag into output
    int half = fftSize / 2;
    std::vector<float> mag(half);
    magnitude_spectrum(real.data(), mag.data(), fftSize);

    jfloatArray result = env->NewFloatArray(half);
    env->SetFloatArrayRegion(result, 0, half, mag.data());
    return result;
}

JNIEXPORT jfloat JNICALL
Java_com_sdw_audio_analyzer_NativeAnalyzer_findCutoffFrequency(
    JNIEnv *env, jclass /*clazz*/,
    jfloatArray magnitudeBins, jint startBin, jfloat binResolutionHz
) {
    jsize len = env->GetArrayLength(magnitudeBins);
    jfloat *mag = env->GetFloatArrayElements(magnitudeBins, nullptr);

    int cutoffBin = find_cutoff_bin(mag, len * 2 /* fftSize */, startBin);
    float result = (cutoffBin >= 0) ? cutoffBin * binResolutionHz : 0.0f;

    env->ReleaseFloatArrayElements(magnitudeBins, mag, JNI_ABORT);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_sdw_audio_analyzer_NativeAnalyzer_computeRmsPeak(
    JNIEnv *env, jclass /*clazz*/,
    jfloatArray pcmData
) {
    jsize len = env->GetArrayLength(pcmData);
    jfloat *pcm = env->GetFloatArrayElements(pcmData, nullptr);

    double sumSq = 0.0;
    float peak = 0.0f;
    double dcSum = 0.0;

    for (jsize i = 0; i < len; i++) {
        float v = pcm[i];
        sumSq += (double)v * v;
        float absV = std::fabs(v);
        if (absV > peak) peak = absV;
        dcSum += v;
    }

    env->ReleaseFloatArrayElements(pcmData, pcm, JNI_ABORT);

    float rms = std::sqrt(sumSq / len);
    float dc = dcSum / len;

    jfloatArray result = env->NewFloatArray(3);
    float values[3] = { rms, peak, dc };
    env->SetFloatArrayRegion(result, 0, 3, values);
    return result;
}

JNIEXPORT jfloat JNICALL
Java_com_sdw_audio_analyzer_NativeAnalyzer_computeSpectralFlatness(
    JNIEnv *env, jclass /*clazz*/,
    jfloatArray magnitudeBins
) {
    jsize len = env->GetArrayLength(magnitudeBins);
    jfloat *mag = env->GetFloatArrayElements(magnitudeBins, nullptr);

    double sumLog = 0.0, sumLin = 0.0;
    int count = 0;
    for (jsize i = 0; i < len; i++) {
        if (mag[i] > 1e-9f) {
            sumLog += std::log(mag[i]);
            sumLin += mag[i];
            count++;
        }
    }

    env->ReleaseFloatArrayElements(magnitudeBins, mag, JNI_ABORT);

    if (count == 0 || sumLin < 1e-9f) return 0.0f;

    double geoMean = std::exp(sumLog / count);
    double arithMean = sumLin / count;
    return (arithMean > 0.0) ? (geoMean / arithMean) : 0.0f;
}

JNIEXPORT jint JNICALL
Java_com_sdw_audio_analyzer_NativeAnalyzer_countClippedFrames(
    JNIEnv *env, jclass /*clazz*/,
    jfloatArray pcmData, jint frameSize, jfloat threshold
) {
    jsize len = env->GetArrayLength(pcmData);
    jfloat *pcm = env->GetFloatArrayElements(pcmData, nullptr);

    int clippedFrames = 0;
    int totalFrames = len / frameSize;

    for (int f = 0; f < totalFrames; f++) {
        int consecutiveClip = 0;
        bool frameClipped = false;
        for (int s = 0; s < frameSize; s++) {
            float v = pcm[f * frameSize + s];
            if (std::fabs(v) >= threshold) {
                consecutiveClip++;
                if (consecutiveClip >= 3) {
                    frameClipped = true;
                    break;
                }
            } else {
                consecutiveClip = 0;
            }
        }
        if (frameClipped) clippedFrames++;
    }

    env->ReleaseFloatArrayElements(pcmData, pcm, JNI_ABORT);
    return clippedFrames;
}

} // extern "C"
