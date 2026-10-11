// loudness_meter.h —— 【V8.233】ITU-R BS.1770-4 积分响度（LUFS）+ 近似真峰值
//
// 用途：曲库离线扫描。文件里没有 ReplayGain / LUFS 标签时，用扫描值代替，
// 让「响度统一管理」对无标签的曲子也生效（而不是一律 0 dB 听天由命）。
//
// 处理流程（对齐 BS.1770-4）：
//   1) 每声道过 K-weighting 两级滤波（high-shelf ~1.68kHz +3.99dB → high-pass ~38Hz）
//   2) 400ms 块 / 100ms 步进（75% 重叠）求每块均方 MS
//   3) 块响度 L = -0.691 + 10*log10(Σ G_ch * MS_ch)，立体声 G=1.0
//   4) 双门限：绝对门 -70 LUFS，再相对门（绝对门后均值的 -10 LU）
//   5) 对过门块的 MS 取平均 → 积分响度
// 真峰值这里用「样本峰值」近似（真正的 True Peak 要 4x 过采样插值），
// 但它只用于 cap() 防削波，样本峰值偏保守，够用。
#pragma once
#include <cmath>
#include <cstddef>
#include <vector>

class Bs1770Meter {
public:
    void setup(int sampleRate, int channels) {
        sr = sampleRate;
        ch = channels < 1 ? 1 : (channels > 8 ? 8 : channels);
        if (sr <= 0) sr = 48000;
        for (int c = 0; c < ch; c++) { z1s[c][0] = z1s[c][1] = 0.f; z2s[c][0] = z2s[c][1] = 0.f; }
        // --- K-weighting ---
        // Stage 1: high shelf f0=1681.97Hz, S=1, +3.99dB
        const double A = std::pow(10.0, 3.99 / 40.0);
        designHighShelf(1681.97, 1.0, A, sr, b1c, a1c);
        // Stage 2: high pass f0=38.13Hz, Q=0.5
        designHighPass(38.13, 0.5, sr, b2c, a2c);

        subSamples = (int)(sr * 0.1); if (subSamples < 1) subSamples = 1;
        subSum.assign(ch, 0.0);
        subCount = 0;
        blocks.clear();
        peak = 0.f;
        valid = true;
    }

    /** 喂交错 PCM（float，范围 -1..1）。numSamples = frames*ch。 */
    void feed(const float* data, int numSamples) {
        if (!valid || !data || ch <= 0) return;
        const int frames = numSamples / ch;
        for (int f = 0; f < frames; f++) {
            double ms = 0.0;
            for (int c = 0; c < ch; c++) {
                float v = data[f * ch + c];
                float a = std::fabs(v);
                if (a > peak) peak = a;
                float y = biquad(z1s[c], b1c, a1c, v);
                y = biquad(z2s[c], b2c, a2c, y);
                ms += (double)y * (double)y;   // 立体声 G=1.0（无中置/环绕声道）
            }
            subSum[0] += ms;                   // 各声道已按 G 求和
            subCount++;
            if (subCount >= subSamples) {
                pushSub(subSum[0], subCount);
                subSum[0] = 0.0;
                subCount = 0;
            }
        }
    }

    /** 积分响度（LUFS）。数据不足返回 0（调用方按无效处理）。 */
    float loudnessLufs() const {
        if (!valid || blocks.empty()) return 0.f;
        // 绝对门 -70 LUFS
        std::vector<double> kept;
        for (const auto& b : blocks) if (b.lufs > -70.0) kept.push_back(b.ms);
        if (kept.empty()) return 0.f;
        // 相对门：绝对门后功率均值 → -10 LU
        double s1 = 0.0;
        for (double m : kept) s1 += m;
        double relRef = -0.691 + 10.0 * std::log10(s1 / (double)kept.size()) - 10.0;
        std::vector<double> gated;
        for (const auto& b : blocks) if (b.lufs > -70.0 && b.lufs > relRef) gated.push_back(b.ms);
        if (gated.empty()) gated = kept;
        double s2 = 0.0;
        for (double m : gated) s2 += m;
        return (float)(-0.691 + 10.0 * std::log10(s2 / (double)gated.size()));
    }

    float peakLinear() const { return peak; }
    bool isValid() const { return valid; }

private:
    struct Blk { double ms; double lufs; };

    void pushSub(double ms, int count) {
        if (count <= 0) return;
        double meanSq = ms / (double)count;
        subHist.push_back(meanSq);
        if ((int)subHist.size() > 4) subHist.erase(subHist.begin());
        if ((int)subHist.size() < 4) return;      // 不足 400ms
        double s = 0.0;
        for (double m : subHist) s += m;
        double msBlock = s / 4.0;
        double l = -0.691 + 10.0 * std::log10(msBlock > 1e-12 ? msBlock : 1e-12);
        blocks.push_back(Blk{msBlock, l});
    }

    static float biquad(float z[2], const double b[3], const double a[3], float x) {
        float y = (float)(b[0] * x + z[0]);
        z[0] = (float)(b[1] * x - a[1] * y + z[1]);
        z[1] = (float)(b[2] * x - a[2] * y);
        return y;
    }

    static void designHighShelf(double f0, double S, double A, int sr, double b[3], double a[3]) {
        const double w0 = 2.0 * M_PI * f0 / (double)sr;
        const double cs = std::cos(w0);
        const double alpha = std::sin(w0) / 2.0 * std::sqrt((A + 1.0 / A) * (1.0 / S - 1.0) + 2.0);
        const double sq = 2.0 * std::sqrt(A) * alpha;
        double a0 = (A + 1.0) - (A - 1.0) * cs + sq;
        b[0] = (A * ((A + 1.0) + (A - 1.0) * cs + sq)) / a0;
        b[1] = (-2.0 * A * ((A - 1.0) + (A + 1.0) * cs)) / a0;
        b[2] = (A * ((A + 1.0) + (A - 1.0) * cs - sq)) / a0;
        a[1] = (2.0 * ((A - 1.0) - (A + 1.0) * cs)) / a0;
        a[2] = ((A + 1.0) - (A - 1.0) * cs - sq) / a0;
    }

    static void designHighPass(double f0, double Q, int sr, double b[3], double a[3]) {
        const double w0 = 2.0 * M_PI * f0 / (double)sr;
        const double cs = std::cos(w0);
        const double sn = std::sin(w0);
        const double alpha = sn / (2.0 * Q);
        double a0 = 1.0 + alpha;
        b[0] = ((1.0 + cs) / 2.0) / a0;
        b[1] = (-(1.0 + cs)) / a0;
        b[2] = ((1.0 + cs) / 2.0) / a0;
        a[1] = (-2.0 * cs) / a0;
        a[2] = (1.0 - alpha) / a0;
    }

    int sr = 48000, ch = 2, subSamples = 4800, subCount = 0;
    double b1c[3] = {0}, a1c[3] = {0}, b2c[3] = {0}, a2c[3] = {0};
    float z1s[8][2] = {{0}}, z2s[8][2] = {{0}};
    std::vector<double> subSum;
    std::vector<double> subHist;
    std::vector<Blk> blocks;
    float peak = 0.f;
    bool valid = false;
};
