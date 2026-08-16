/**
 * fft_analyzer.h — In-place Radix-2 FFT + spectrum utilities.
 * Alloc-free: works on caller-provided buffers.
 */

#ifndef AUDIO_FFT_H
#define AUDIO_FFT_H

#include <cstdint>
#include <cmath>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * In-place complex FFT (radix-2, DIT).
 * @param real  Real part array (size = fftSize)
 * @param imag  Imag part array (size = fftSize)
 * @param n     FFT size (must be power of 2)
 * @param inverse  true for IFFT
 */
void fft(float *real, float *imag, int n, bool inverse);

/**
 * Compute magnitude spectrum from interleaved real/imag FFT output.
 * @param fftOut    Interleaved real,imag pairs (size = fftSize * 2)
 * @param mag       Output magnitude array (size = fftSize / 2)
 * @param fftSize   FFT size
 */
void magnitude_spectrum(const float *fftOut, float *mag, int fftSize);

/**
 * Find -3dB cutoff frequency: highest bin where magnitude drops
 * 3dB below peak in [startBin, fftSize/2).
 * @return bin index, or -1
 */
int find_cutoff_bin(const float *mag, int fftSize, int startBin);

/**
 * Zero-pad cross-correlation for precise frequency estimation.
 * Returns refined Hz from a given bin index.
 */
float refine_frequency(const float *real, const float *imag, int n, int bin, float sampleRate);

#ifdef __cplusplus
}
#endif

#endif // AUDIO_FFT_H
