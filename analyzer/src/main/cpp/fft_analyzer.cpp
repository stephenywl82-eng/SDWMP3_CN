/**
 * fft_analyzer.cpp — In-place Radix-2 DIT FFT + spectrum utilities.
 * Single-file, no external deps. Optimised for audio (real input).
 */

#include "fft_analyzer.h"
#include <cstring>
#include <algorithm>

static constexpr float PI = 3.14159265358979323846f;

void fft(float *real, float *imag, int n, bool inverse) {
    // Bit-reversal permutation
    int j = 0;
    for (int i = 0; i < n; i++) {
        if (i < j) {
            std::swap(real[i], real[j]);
            std::swap(imag[i], imag[j]);
        }
        int m = n >> 1;
        while (m >= 1 && j >= m) {
            j -= m;
            m >>= 1;
        }
        j += m;
    }

    // Cooley-Tukey DIT
    float sign = inverse ? 1.0f : -1.0f;
    for (int step = 1; step < n; step <<= 1) {
        float angle = PI / step;
        float wReal = std::cos(angle);
        float wImag = sign * std::sin(angle);

        for (int group = 0; group < n; group += (step << 1)) {
            float twReal = 1.0f, twImag = 0.0f;
            for (int pair = 0; pair < step; pair++) {
                int even = group + pair;
                int odd  = even + step;

                float tReal = twReal * real[odd] - twImag * imag[odd];
                float tImag = twReal * imag[odd] + twImag * real[odd];

                real[odd] = real[even] - tReal;
                imag[odd] = imag[even] - tImag;
                real[even] += tReal;
                imag[even] += tImag;

                // Update twiddle
                float nextTwReal = twReal * wReal - twImag * wImag;
                float nextTwImag = twReal * wImag + twImag * wReal;
                twReal = nextTwReal;
                twImag = nextTwImag;
            }
        }
    }

    if (inverse) {
        for (int i = 0; i < n; i++) {
            real[i] /= n;
            imag[i] /= n;
        }
    }
}

void magnitude_spectrum(const float *fftOut, float *mag, int fftSize) {
    int half = fftSize / 2;
    for (int i = 0; i < half; i++) {
        float re = fftOut[2 * i];
        float im = fftOut[2 * i + 1];
        mag[i] = std::sqrt(re * re + im * im);
    }
}

int find_cutoff_bin(const float *mag, int fftSize, int startBin) {
    int half = fftSize / 2;
    if (startBin >= half) return -1;

    // Find peak in [startBin, half)
    float peak = 0.0f;
    int peakBin = startBin;
    for (int i = startBin; i < half; i++) {
        if (mag[i] > peak) {
            peak = mag[i];
            peakBin = i;
        }
    }
    if (peak <= 0.0f) return -1;

    float threshold = peak * 0.7071f; // -3dB
    // Scan downward from half-1 to find last bin above -3dB
    for (int i = half - 1; i > peakBin; i--) {
        if (mag[i] >= threshold) return i;
    }
    return peakBin;
}

float refine_frequency(const float *real, const float *imag, int n, int bin, float sampleRate) {
    if (bin <= 0 || bin >= n / 2 - 1) return bin * sampleRate / n;

    // Parabolic interpolation on magnitude
    float m0 = std::sqrt(real[bin - 1] * real[bin - 1] + imag[bin - 1] * imag[bin - 1]);
    float m1 = std::sqrt(real[bin] * real[bin] + imag[bin] * imag[bin]);
    float m2 = std::sqrt(real[bin + 1] * real[bin + 1] + imag[bin + 1] * imag[bin + 1]);

    float delta = 0.5f * (m0 - m2) / (m0 - 2.0f * m1 + m2 + 1e-9f);
    return (bin + delta) * sampleRate / n;
}
