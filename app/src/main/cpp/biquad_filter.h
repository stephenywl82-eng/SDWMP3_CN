#pragma once
#include <cmath>

// =============================================================================
// Biquad Filter — RBJ Cookbook implementation (double-precision coefficients + states)
// Shared between Oboe DSP path (oboe_bridge.cpp) and USB DAC path (usb_audio_driver.cpp).
//
// 【V8.3 根治 — 分治转换 + 全程 double】
// 根因：低频 peaking（32/60/120Hz）深负增益时，极点 margin 只有 ~2·(1-cos(w0))≈2e-5，
// 极逼近单位圆（RBJ 在极低频的固有特性）。两种转换方式各有利弊，必须按频率分治：
//   - 系数 SMOOTH（线性插值系数）：中间态极点可能越界 → 低频大信号激发持续振荡（哗哗）
//   - 输出域 crossfade（两个稳定滤波器并行混合）：永远稳定，但高频增益跨 0 时新旧滤波器
//     相位相反、混合相消 → 齿音(5k/8k)拖动的"啵/哗"
// 分治：centerHz < 250 → crossfade（低频稳定）；>= 250 → 系数 SMOOTH（高频无相位相消）。
// 状态与系数全程 double（53-bit 尾数），消除 float 舍入极限环。
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
    // crossfade 时长（~5.8ms@44.1k，~2.7ms@96k）：足够平滑低频系数切换，又察觉不到"拖沓"。
    static constexpr int kCrossfadeSamples = 256;

    void setHighShelf(float sampleRate, float cutoffHz, float dbGain, float Q) {
        double A = pow(10.0, (double)dbGain / 40.0);
        double w0 = 2.0 * M_PI * cutoffHz / sampleRate;
        double cosw0 = cos(w0), sinw0 = sin(w0);
        double alpha = sinw0 / (2.0 * Q);
        double twoSqrtAalpha = 2.0 * sqrt(A) * alpha;
        double b0 = A * ((A + 1.0) + (A - 1.0) * cosw0 + twoSqrtAalpha);
        double b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosw0);
        double b2 = A * ((A + 1.0) + (A - 1.0) * cosw0 - twoSqrtAalpha);
        double a0 = (A + 1.0) - (A - 1.0) * cosw0 + twoSqrtAalpha;
        double a1 =  2.0 * ((A - 1.0) - (A + 1.0) * cosw0);
        double a2 = (A + 1.0) - (A - 1.0) * cosw0 - twoSqrtAalpha;
        commit(b0, b1, b2, a0, a1, a2, cutoffHz);
    }
    void setPeaking(float sampleRate, float centerHz, float dbGain, float Q) {
        double A = pow(10.0, (double)dbGain / 40.0);
        double w0 = 2.0 * M_PI * centerHz / sampleRate;
        double cosw0 = cos(w0), sinw0 = sin(w0);
        double alpha = sinw0 / (2.0 * Q);
        double b0 = 1.0 + alpha * A;
        double b1 = -2.0 * cosw0;
        double b2 = 1.0 - alpha * A;
        double a0 = 1.0 + alpha / A;
        double a1 = -2.0 * cosw0;
        double a2 = 1.0 - alpha / A;
        commit(b0, b1, b2, a0, a1, a2, centerHz);
    }
    void setLowShelf(float sampleRate, float cutoffHz, float dbGain, float Q) {
        double A = pow(10.0, (double)dbGain / 40.0);
        double w0 = 2.0 * M_PI * cutoffHz / sampleRate;
        double cosw0 = cos(w0), sinw0 = sin(w0);
        double alpha = sinw0 / (2.0 * Q);
        double twoSqrtAalpha = 2.0 * sqrt(A) * alpha;
        double b0 = A * ((A + 1.0) - (A - 1.0) * cosw0 + twoSqrtAalpha);
        double b1 =  2.0 * A * ((A - 1.0) - (A + 1.0) * cosw0);
        double b2 = A * ((A + 1.0) - (A - 1.0) * cosw0 - twoSqrtAalpha);
        double a0 = (A + 1.0) + (A - 1.0) * cosw0 + twoSqrtAalpha;
        double a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosw0);
        double a2 = (A + 1.0) + (A - 1.0) * cosw0 - twoSqrtAalpha;
        commit(b0, b1, b2, a0, a1, a2, cutoffHz);
    }
    void setBandPass(float sampleRate, float centerHz, float Q) {
        double w0 = 2.0 * M_PI * centerHz / sampleRate;
        double cosw0 = cos(w0), sinw0 = sin(w0);
        double alpha = sinw0 / (2.0 * Q);
        double b0 = alpha;
        double b1 = 0.0;
        double b2 = -alpha;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * cosw0;
        double a2 = 1.0 - alpha;
        commit(b0, b1, b2, a0, a1, a2, centerHz);
    }
    void setLowPass(float sampleRate, float cutoffHz, float Q) {
        double w0 = 2.0 * M_PI * cutoffHz / sampleRate;
        double cosw0 = cos(w0), sinw0 = sin(w0);
        double alpha = sinw0 / (2.0 * Q);
        double b0 = (1.0 - cosw0) * 0.5;
        double b1 = 1.0 - cosw0;
        double b2 = (1.0 - cosw0) * 0.5;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * cosw0;
        double a2 = 1.0 - alpha;
        commit(b0, b1, b2, a0, a1, a2, cutoffHz);
    }
    // 软 flat：目标 unity，process() 系数 SMOOTH ramp 过去（IIR 状态连续，无 click）。
    void setFlat() {
        norm_b0_ = 1.0; norm_b1_ = 0.0; norm_b2_ = 0.0;
        norm_a1_ = 0.0; norm_a2_ = 0.0;
        crossRemaining_ = 0;  // 取消进行中的 crossfade，改走 SMOOTH（flat 是稳定态，安全）
    }
    // 单样本处理：全程 double；低频在 crossfade 期跑双滤波混合，其余 SMOOTH 系数 ramp。
    float process(float input) {
        if (crossRemaining_ > 0) {
            double t = 1.0 - (double)crossRemaining_ / (double)kCrossfadeSamples;
            crossRemaining_--;
            double oldOut = processWith(cross_b0_, cross_b1_, cross_b2_, cross_a1_, cross_a2_,
                                        cross_x1_, cross_x2_, cross_y1_, cross_y2_, input);
            double newOut = processWith(cur_b0_, cur_b1_, cur_b2_, cur_a1_, cur_a2_,
                                        x1_, x2_, y1_, y2_, input);
            return (float)(oldOut * (1.0 - t) + newOut * t);
        }
        const double SMOOTH = 0.05;
        cur_b0_ += (norm_b0_ - cur_b0_) * SMOOTH;
        cur_b1_ += (norm_b1_ - cur_b1_) * SMOOTH;
        cur_b2_ += (norm_b2_ - cur_b2_) * SMOOTH;
        cur_a1_ += (norm_a1_ - cur_a1_) * SMOOTH;
        cur_a2_ += (norm_a2_ - cur_a2_) * SMOOTH;
        double out = cur_b0_ * (double)input + cur_b1_ * x1_ + cur_b2_ * x2_
                   - cur_a1_ * y1_ - cur_a2_ * y2_;
        // 【V8.3 去中间限幅】级联 EQ 的中间段不做 hard clip —— 中间限幅会把多段
        // 低频提升叠加的峰值削平，产生谐波失真（密集鼓声的哗哗/沙沙）。
        // double 状态可安全容纳 >1.0 的中间值，最终输出由 streamLoop 打包/Oboe limiter 限幅。
        x2_ = x1_; x1_ = (double)input;
        y2_ = y1_; y1_ = out;
        return (float)out;
    }
    void reset() {
        x1_ = x2_ = y1_ = y2_ = 0.0;
        crossRemaining_ = 0;
        cur_b0_ = norm_b0_; cur_b1_ = norm_b1_; cur_b2_ = norm_b2_;
        cur_a1_ = norm_a1_; cur_a2_ = norm_a2_;
    }
    // Coefficients — double storage (public: 日志直接访问 norm_b0_ 等)
    double norm_b0_ = 1.0, norm_b1_ = 0.0, norm_b2_ = 0.0;
    double norm_a1_ = 0.0, norm_a2_ = 0.0;
    double cur_b0_ = 1.0, cur_b1_ = 0.0, cur_b2_ = 0.0;
    double cur_a1_ = 0.0, cur_a2_ = 0.0;
    // State — double
    double x1_ = 0.0, x2_ = 0.0;
    double y1_ = 0.0, y2_ = 0.0;
private:
    // crossfade snapshot：旧系数 + 旧状态，与新系数并行跑，直到 crossRemaining_ 归零。
    double cross_b0_ = 1.0, cross_b1_ = 0.0, cross_b2_ = 0.0;
    double cross_a1_ = 0.0, cross_a2_ = 0.0;
    double cross_x1_ = 0.0, cross_x2_ = 0.0;
    double cross_y1_ = 0.0, cross_y2_ = 0.0;
    int crossRemaining_ = 0;

    // 分治提交：freq < 250Hz → crossfade（快照 cur→cross，cur 立即=new）；否则 SMOOTH（只改 norm）。
    void commit(double b0, double b1, double b2, double a0, double a1, double a2, float freq) {
        double nb0 = b0 / a0, nb1 = b1 / a0, nb2 = b2 / a0;
        double na1 = a1 / a0, na2 = a2 / a0;
        if (freq < 250.0f) {
            cross_b0_ = cur_b0_; cross_b1_ = cur_b1_; cross_b2_ = cur_b2_;
            cross_a1_ = cur_a1_; cross_a2_ = cur_a2_;
            cross_x1_ = x1_; cross_x2_ = x2_; cross_y1_ = y1_; cross_y2_ = y2_;
            crossRemaining_ = kCrossfadeSamples;
            norm_b0_ = nb0; norm_b1_ = nb1; norm_b2_ = nb2; norm_a1_ = na1; norm_a2_ = na2;
            cur_b0_ = nb0; cur_b1_ = nb1; cur_b2_ = nb2; cur_a1_ = na1; cur_a2_ = na2;
        } else {
            norm_b0_ = nb0; norm_b1_ = nb1; norm_b2_ = nb2; norm_a1_ = na1; norm_a2_ = na2;
        }
    }
    double processWith(double b0, double b1, double b2, double a1, double a2,
                       double& x1, double& x2, double& y1, double& y2, float input) {
        double out = b0 * (double)input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1; x1 = (double)input;
        y2 = y1; y1 = out;
        return out;
    }
};
