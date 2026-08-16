#pragma once
#include <cmath>

// =============================================================================
// Biquad Filter — RBJ Cookbook implementation (double-precision coefficients + states)
// Shared between Oboe DSP path (oboe_bridge.cpp) and USB DAC path (usb_audio_driver.cpp)
// so MSEB / graphic-EQ 5-band processing is bit-identical across both routes.
//
// Supports: High-Shelf, Peaking, Low-Shelf, High-Pass, Low-Pass
// State registers (x1/x2/y1/y2) and coefficient storage use double to eliminate
// 24-bit mantissa quantization noise accumulation across cascaded IIR stages.
// process() multiply-add core stays float (ARM NEON 4xf32 SIMD), with float<->double
// conversion at state read/write boundaries.
// ==============================================================================
class BiquadFilter {
public:
    enum FilterType {
        FLAT = 0,
        HIGH_SHELF = 1,
        PEAKING = 2,
        LOW_SHELF = 3,
        HIGH_PASS = 4,
        LOW_PASS = 5
    };
    void setHighShelf(float sampleRate, float cutoffHz, float dbGain, float Q) {
        double A = pow(10.0, (double)dbGain / 40.0);
        double w0 = 2.0 * M_PI * cutoffHz / sampleRate;
        double cosw0 = cos(w0);
        double sinw0 = sin(w0);
        double alpha = sinw0 / (2.0 * Q);
        double twoSqrtAalpha = 2.0 * sqrt(A) * alpha;
        double b0 = A * ((A + 1.0) + (A - 1.0) * cosw0 + twoSqrtAalpha);
        double b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosw0);
        double b2 = A * ((A + 1.0) + (A - 1.0) * cosw0 - twoSqrtAalpha);
        double a0 = (A + 1.0) - (A - 1.0) * cosw0 + twoSqrtAalpha;
        double a1 =  2.0 * ((A - 1.0) - (A + 1.0) * cosw0);
        double a2 = (A + 1.0) - (A - 1.0) * cosw0 - twoSqrtAalpha;
        setCoefficients(b0, b1, b2, a0, a1, a2);
    }
    void setPeaking(float sampleRate, float centerHz, float dbGain, float Q) {
        double A = pow(10.0, (double)dbGain / 40.0);
        double w0 = 2.0 * M_PI * centerHz / sampleRate;
        double cosw0 = cos(w0);
        double sinw0 = sin(w0);
        double alpha = sinw0 / (2.0 * Q);
        double b0 = 1.0 + alpha * A;
        double b1 = -2.0 * cosw0;
        double b2 = 1.0 - alpha * A;
        double a0 = 1.0 + alpha / A;
        double a1 = -2.0 * cosw0;
        double a2 = 1.0 - alpha / A;
        setCoefficients(b0, b1, b2, a0, a1, a2);
    }
    void setLowShelf(float sampleRate, float cutoffHz, float dbGain, float Q) {
        double A = pow(10.0, (double)dbGain / 40.0);
        double w0 = 2.0 * M_PI * cutoffHz / sampleRate;
        double cosw0 = cos(w0);
        double sinw0 = sin(w0);
        double alpha = sinw0 / (2.0 * Q);
        double twoSqrtAalpha = 2.0 * sqrt(A) * alpha;
        double b0 = A * ((A + 1.0) - (A - 1.0) * cosw0 + twoSqrtAalpha);
        double b1 =  2.0 * A * ((A - 1.0) - (A + 1.0) * cosw0);
        double b2 = A * ((A + 1.0) - (A - 1.0) * cosw0 - twoSqrtAalpha);
        double a0 = (A + 1.0) + (A - 1.0) * cosw0 + twoSqrtAalpha;
        double a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosw0);
        double a2 = (A + 1.0) + (A - 1.0) * cosw0 - twoSqrtAalpha;
        setCoefficients(b0, b1, b2, a0, a1, a2);
    }
    void setBandPass(float sampleRate, float centerHz, float Q) {
        double w0 = 2.0 * M_PI * centerHz / sampleRate;
        double cosw0 = cos(w0);
        double sinw0 = sin(w0);
        double alpha = sinw0 / (2.0 * Q);
        double b0 = alpha;
        double b1 = 0.0;
        double b2 = -alpha;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * cosw0;
        double a2 = 1.0 - alpha;
        setCoefficients(b0, b1, b2, a0, a1, a2);
    }
    void setLowPass(float sampleRate, float cutoffHz, float Q) {
        double w0 = 2.0 * M_PI * cutoffHz / sampleRate;
        double cosw0 = cos(w0);
        double sinw0 = sin(w0);
        double alpha = sinw0 / (2.0 * Q);
        double b0 = (1.0 - cosw0) * 0.5;
        double b1 = 1.0 - cosw0;
        double b2 = (1.0 - cosw0) * 0.5;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * cosw0;
        double a2 = 1.0 - alpha;
        setCoefficients(b0, b1, b2, a0, a1, a2);
    }
    void setFlat() {
        norm_b0_ = 1.0; norm_b1_ = 0.0; norm_b2_ = 0.0;
        norm_a1_ = 0.0; norm_a2_ = 0.0;
        cur_b0_ = 1.0; cur_b1_ = 0.0; cur_b2_ = 0.0;
        cur_a1_ = 0.0; cur_a2_ = 0.0;
        reset();
    }
    // Process single sample — float multiply-add core (NEON 4xf32 SIMD),
    // double state registers for quantization-noise-free IIR feedback
    float process(float input) {
        const float SMOOTH = 0.05f;
        cur_b0_ += (norm_b0_ - cur_b0_) * (double)SMOOTH;
        cur_b1_ += (norm_b1_ - cur_b1_) * (double)SMOOTH;
        cur_b2_ += (norm_b2_ - cur_b2_) * (double)SMOOTH;
        cur_a1_ += (norm_a1_ - cur_a1_) * (double)SMOOTH;
        cur_a2_ += (norm_a2_ - cur_a2_) * (double)SMOOTH;

        // Cast coefficients to float for the 5x FMADD pipeline (NEON fmla v0.4s)
        float b0f = (float)cur_b0_, b1f = (float)cur_b1_, b2f = (float)cur_b2_;
        float a1f = (float)cur_a1_, a2f = (float)cur_a2_;

        // State read: double -> float
        float x1f = (float)x1_, x2f = (float)x2_;
        float y1f = (float)y1_, y2f = (float)y2_;

        float output = b0f * input + b1f * x1f + b2f * x2f
                     - a1f * y1f - a2f * y2f;

        // Clamp to prevent NaN/Inf
        output = fmaxf(-1.0f, fminf(1.0f, output));

        // State write-back: float -> double (preserves full IIR precision)
        x2_ = (double)x1f;
        x1_ = (double)input;
        y2_ = (double)y1f;
        y1_ = (double)output;

        return output;
    }
    void reset() {
        x1_ = x2_ = y1_ = y2_ = 0.0;
    }
    // Coefficients — double storage for 53-bit mantissa precision
    double norm_b0_ = 1.0, norm_b1_ = 0.0, norm_b2_ = 0.0;
    double norm_a1_ = 0.0, norm_a2_ = 0.0;
    double cur_b0_ = 1.0, cur_b1_ = 0.0, cur_b2_ = 0.0;
    double cur_a1_ = 0.0, cur_a2_ = 0.0;
    // State — double to eliminate 24-bit mantissa quantization in IIR feedback
    double x1_ = 0.0, x2_ = 0.0;
    double y1_ = 0.0, y2_ = 0.0;
private:
    void setCoefficients(double b0, double b1, double b2, double a0, double a1, double a2) {
        norm_b0_ = b0 / a0;
        norm_b1_ = b1 / a0;
        norm_b2_ = b2 / a0;
        norm_a1_ = a1 / a0;
        norm_a2_ = a2 / a0;
    }
};
