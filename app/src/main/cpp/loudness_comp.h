#pragma once
#include <cmath>
#include <algorithm>
#include "biquad_filter.h"

// =============================================================================
// LoudnessComp — ISO 226:2003 等响补偿（低音量时低频/高频自动提升）
// =============================================================================
//
// 核心认知（与 Salt/发烧友共识一致）：
//   人耳在低响度下对低频（<200Hz）和高频（>8kHz）敏感度骤降，直接按音量旋钮
//   调低会让"低音消失、声音发干"。等响补偿按【实际输出响度 phon】自动抬升两端。
//
// 【关键坑】不能按音量旋钮补偿，必须按实际输出响度：
//   不同歌的响度差 10dB+（母带 loudness war / 平台响度归一），同一旋钮位置下
//   phon 可能差 10dB。所以 phon = f(内容 RMS × 输出增益)，而不是 f(旋钮)。
//
// 补偿曲线（相对 80 phon 参考，ISO 226:2003 精确计算后线性近似）：
//   phon  100Hz 补偿   10kHz 补偿
//   80    0 dB        0 dB
//   70   +3.1 dB     +0.7 dB
//   60   +6.2 dB     +1.4 dB
//   50   +9.1 dB     +2.0 dB
//   40  +11.9 dB     +2.6 dB
//   30  +14.3 dB     +2.8 dB
//   → 低频用 120Hz LowShelf 近似（0.30 dB/phon 斜率，clamp 0..12dB）；
//     高频补偿 <3dB 用轻量 8kHz HighShelf（0.06 dB/phon，clamp 0..3dB），
//     且仅在 phon<60 时启用，避免拖齿音/高频 ringing 回归。
//
// phon 估算：phon = 100 + 20*log10(rms * outGain)，clamp [30, 80]。
//   参考：0dBFS 满幅 ≈ 100 phon，典型音乐 RMS -20dBFS 满音量 ≈ 80 phon（无补偿）。
//
// stereo-linked：L/R 共享同一个 phon 估算与补偿系数，但各有独立滤波器状态，
// 避免左右声道补偿量不同导致声场漂移。
// 共享于 Oboe 路径（oboe_bridge.cpp）与 USB DAC 路径（usb_audio_driver.cpp），
// 两路径共用同一套 BiquadFilter（bit-identical）。
// ==============================================================================
class LoudnessComp {
public:
    bool enabled_ = false;
    float intensity_ = 1.0f;     // 用户补偿强度 0..1（默认 1.0）
    float sampleRate_ = 48000.0f;
    float outGain_ = 1.0f;       // 实际输出增益（DAC=volume_ cubic；Oboe=系统音量 pct）

    BiquadFilter lowShelfL_, lowShelfR_, highShelfL_, highShelfR_;
    float rmsSq_ = 0.0f;         // 慢包络均方值（L/R 平均能量，逐帧平滑）
    float curLowDb_ = 0.0f;      // 当前已应用的低频补偿 dB
    int updateCounter_ = 0;

    static constexpr int kUpdateEvery = 4096;   // ~93ms @44.1k 更新一次 phon/shelf

    void setSampleRate(float sr) {
        sampleRate_ = sr;
        lowShelfL_.setLowShelf(sr, 120.0f, 0.0f, 0.707f);
        lowShelfR_.setLowShelf(sr, 120.0f, 0.0f, 0.707f);
        highShelfL_.setHighShelf(sr, 8000.0f, 0.0f, 0.707f);
        highShelfR_.setHighShelf(sr, 8000.0f, 0.0f, 0.707f);
    }

    void setEnabled(bool e) {
        enabled_ = e;
        if (!e) {
            rmsSq_ = 0.0f;
            curLowDb_ = 0.0f;
            updateCounter_ = 0;
            lowShelfL_.setLowShelf(sampleRate_, 120.0f, 0.0f, 0.707f);
            lowShelfR_.setLowShelf(sampleRate_, 120.0f, 0.0f, 0.707f);
            highShelfL_.setHighShelf(sampleRate_, 8000.0f, 0.0f, 0.707f);
            highShelfR_.setHighShelf(sampleRate_, 8000.0f, 0.0f, 0.707f);
        }
    }

    void setIntensity(float i) { intensity_ = std::max(0.0f, std::min(1.0f, i)); }
    void setOutGain(float g) { outGain_ = std::max(0.0f, std::min(1.0f, g)); }
    void reset() {
        rmsSq_ = 0.0f; curLowDb_ = 0.0f; updateCounter_ = 0;
        lowShelfL_.reset(); lowShelfR_.reset(); highShelfL_.reset(); highShelfR_.reset();
    }

    // 逐帧处理（stereo-linked）：慢包络 RMS → 估算 phon → 更新 shelf 目标 → 滤波。
    inline void process(float& sL, float& sR) {
        if (!enabled_) return;

        // L/R 平均能量慢包络（时间常数 ~1.1s @44.1k，足够慢避免 phon 抖动）
        float energy = 0.5f * (sL * sL + sR * sR);
        rmsSq_ += (energy - rmsSq_) * 0.00002f;

        // 每 kUpdateEvery 帧重算一次 phon 与补偿目标（shelf 用 crossfade 过渡）
        if (++updateCounter_ >= kUpdateEvery) {
            updateCounter_ = 0;
            float rms = std::sqrt(std::max(rmsSq_, 1e-12f));
            float level = rms * outGain_;
            float phon = 100.0f + 20.0f * log10f(std::max(level, 1e-6f));
            if (phon > 80.0f) phon = 80.0f;
            if (phon < 30.0f) phon = 30.0f;

            float lowDb = (80.0f - phon) * 0.30f * intensity_;   // 40phon→12dB
            if (lowDb > 12.0f) lowDb = 12.0f;

            float highDb = 0.0f;
            if (phon < 60.0f) {
                highDb = (60.0f - phon) * 0.06f * intensity_;    // 40phon→1.2dB
                if (highDb > 3.0f) highDb = 3.0f;
            }

            // 变化 >0.4dB 才提交，避免频繁触发 crossfade
            if (std::fabsf(lowDb - curLowDb_) > 0.4f) {
                curLowDb_ = lowDb;
                lowShelfL_.setLowShelf(sampleRate_, 120.0f, lowDb, 0.707f);
                lowShelfR_.setLowShelf(sampleRate_, 120.0f, lowDb, 0.707f);
                highShelfL_.setHighShelf(sampleRate_, 8000.0f, highDb, 0.707f);
                highShelfR_.setHighShelf(sampleRate_, 8000.0f, highDb, 0.707f);
            }
        }

        sL = lowShelfL_.process(sL);
        sL = highShelfL_.process(sL);
        sR = lowShelfR_.process(sR);
        sR = highShelfR_.process(sR);
    }
};
