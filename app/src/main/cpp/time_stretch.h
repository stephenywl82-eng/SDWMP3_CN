// ============================================================================
// time_stretch.h —— WSOLA 变速不变调引擎【M2 beatmatch】
//
// 干什么：把一段 PCM 在时间轴上拉长/压缩，保持音高不变。DJ beatmatch 靠它把
// incoming 轨的 tempo 对齐到 outgoing 轨 —— 只改速度、不动音高。
//
// 为什么自己写：项目侧 build 用 -fno-exceptions -fno-rtti + C++17，第三方库
// （RubberBand/SoundTouch）要么依赖重、要么需要放开这两个 flag，且网络受限拿不到
// 源码。这里的实现和项目里其它 DSP（LookAheadLimiter / MultiBandLimiter /
// FDN 混响）一致：自研、无依赖、全预分配。
//
// 算法：WSOLA（Waveform Similarity Overlap-Add）
//   1) 分析窗 SequenceWin(S=2048 帧)，合成跳距 Hop(1024 帧 = S/2)；
//      S/2 跳距 + Hann 窗 → 相邻窗叠加恒为 1（COLA），所以不拉伸时是严格重建。
//   2) 每次迭代的分析跳距 Ha = Hop / ratio（ratio = 输出长度/输入长度）。
//      ratio > 1 = 放慢听感（拖长），ratio < 1 = 加快。
//   3) 相似度搜索：候选窗在 ±SearchWin(192 帧) 内以步长 2 滑动，取与「上一已接受窗
//      的尾部」互相关最大的偏移；这一步是 WSOLA 相对朴素 OLA 的关键——它让被移动的
//      窗始终对齐到波形的相似位置，避免周期性音（phasing/金属声）。
//   4) 立体声：互相关在 (L+R)/2 的 mid 上算，找到的偏移同时应用到两个声道，
//      相位关系保持不变（不会塌陷成单声道，也不产生伪立体声）。
//
// 实时安全：构造/ reset 时一次性预分配，之后 render()/feed()/pull() 全程无 malloc、
// 无锁、无系统调用、无浮点异常依赖。概览：(一)~(四) 见下。
// ============================================================================
#ifndef TIME_STRETCH_H
#define TIME_STRETCH_H

#include <cmath>
#include <cstring>
#include <vector>
#include <algorithm>

class WsolaStretch {
public:
    // ---- 常量：全部 2 的幂，方便位取模（未来若要优化可以把 % 换 &）----
    static constexpr int   kSeqFrames    = 2048;   // 分析/合成窗长 ≈ 46ms @44.1k
    static constexpr int   kHopFrames    = 1024;   // 合成跳距 = S/2 → Hann COLA
    static constexpr int   kSearchFrames = 192;    // 相似度搜索范围 ±192 帧
    static constexpr int   kSearchStep   = 2;      // 搜索步长（牺牲一点精度换算力）
    static constexpr int   kInCapFrames  = 16384;  // 输入线性缓冲 ≈ 371ms @44.1k
    static constexpr int   kOutCapFrames = 8192;   // 输出环形缓冲 ≈ 186ms

    // ------------------------------------------------------------------ 配置
    // 只允许在非实时线程调用（会分配内存）
    void reset(int sampleRate, int channels) {
        if (sampleRate <= 0 || channels <= 0 || channels > 8) return;
        sampleRate_ = sampleRate;
        channels_   = channels;
        if (hann_.empty()) {
            hann_.resize(kSeqFrames);
            for (int i = 0; i < kSeqFrames; i++)
                hann_[i] = 0.5f * (1.0f - cosf(2.0f * 3.14159265f * (float)i / (float)kSeqFrames));
        }
        if ((int)ibuf_.size() != kInCapFrames * channels)    ibuf_.assign(kInCapFrames * channels, 0.0f);
        if ((int)oring_.size() != kOutCapFrames * channels)  oring_.assign(kOutCapFrames * channels, 0.0f);
        prevWinMono_.assign(kSeqFrames, 0.0f);
        clear();
    }

    // 实时线程安全：只写标量
    void setRatio(float r) {
        // 限定在 0.5x~2.0x，超过这个范围 OBJECTIVE 音质崩坏，宁可不处理
        if (r < 0.5f) r = 0.5f;
        if (r > 2.0f) r = 2.0f;
        ratio_ = r;
    }

    float ratio() const { return ratio_; }

    // ratio 在 ±0.2% 内视为无需处理（此时 Ha 与 Hop 几乎相等，纯白白烧 CPU）
    bool meaningful() const { return ratio_ > 1.002f || ratio_ < 0.998f; }

    void clear() {
        std::memset(oring_.data(), 0, oring_.size() * sizeof(float));
        std::memset(prevWinMono_.data(), 0, prevWinMono_.size() * sizeof(float));
        inFrames_   = 0;
        nominal_    = 0.0;
        curAbs_     = 0;      // 下一合成窗的绝对起始帧
        pulledAbs_  = 0;      // 已被取走的绝对帧数
        havePrev_   = false;
        ratio_      = 1.0f;
    }

    // ------------------------------------------------------------------ 输入
    // 追加交错 PCM（帧 = 1 个采样点 × 声道数）
    void feed(const float* inter, int frames) {
        if (!inter || frames <= 0 || channels_ <= 0) return;
        if (inFrames_ + frames > kInCapFrames) {
            // 极端情况（消费端慢）：丢掉最老的一段，保证不越界
            int drop = inFrames_ + frames - kInCapFrames;
            dropFromInput(drop);
        }
        const int n = (frames > kInCapFrames) ? kInCapFrames : frames;
        std::memcpy(ibuf_.data() + inFrames_ * channels_, inter, (size_t)n * channels_ * sizeof(float));
        inFrames_ += n;
    }

    // 想再产出 wantFrames 帧，还大约需要喂多少输入帧（给调用方决定从 ring 读多少）
    int needsInputFrames(int wantFrames) const {
        if (ratio_ <= 0.0f) return wantFrames;
        return (int)std::ceil((double)wantFrames / (double)ratio_) + kSeqFrames / 2;
    }

    // ------------------------------------------------------------------ 处理
    // 只要输入够就持续做 WSOLA 迭代；产出帧进环形缓冲等待 pull
    void render() {
        if (channels_ <= 0 || (int)oring_.size() == 0) return;
        const double Ha = (double)kHopFrames / (double)ratio_;
        int guard = 0;
        while (guard++ < 64) {
            const int startNom = (int)llround(nominal_);
            // 需要的输入：候选窗要能覆盖 [0, startNom + search + S)
            const int needFrames = startNom + kSeqFrames + (havePrev_ ? kSearchFrames : 0);
            if (inFrames_ < needFrames) break;
            // 输出背压：环形缓冲里还没被取走的数据不能被将来的窗覆盖
            if ((curAbs_ + kSeqFrames) - pulledAbs_ > kOutCapFrames) break;

            int best = 0;
            if (havePrev_) {
                // 与上一窗重叠的部分用来比对：上一窗 [cmpOff, S) ↔ 候选窗 [0, S-cmpOff)
                int cmpOff = (int)llround(Ha);
                if (cmpOff < 1) cmpOff = 1;
                if (cmpOff > kSeqFrames - 128) cmpOff = kSeqFrames - 128;
                const int cmpLen = kSeqFrames - cmpOff;
                float bestC = -1.0e30f;
                for (int d = -kSearchFrames; d <= kSearchFrames; d += kSearchStep) {
                    const int s = startNom + d;
                    if (s < 0) continue;
                    float c = 0.0f;
                    const float* prev = prevWinMono_.data();
                    for (int i = 0; i < cmpLen; i++) c += prev[cmpOff + i] * monoIn(s + i);
                    if (c > bestC) { bestC = c; best = d; }
                }
            }
            int s0 = startNom + best;
            if (s0 < 0) s0 = 0;
            writeWindow(s0);
            nominal_ += Ha;
            if (s0 > 0) { dropFromInput(s0); nominal_ -= (double)s0; }
        }
    }

    int availableFrames() const {
        long long a = curAbs_ - pulledAbs_;
        return (int)(a > 0 ? a : 0);
    }

    /** 【V8.226】引擎固有算法延迟（帧）。WSOLA 输入即出，恒 0；与 RubberBandStretch 同接口。 */
    int latencyFrames() const { return 0; }

    // 取走最多 wantFrames 帧，返回实际帧数（不足时调用方补 0）
    int pull(float* out, int wantFrames) {
        const int avail = availableFrames();
        int n = (avail < wantFrames) ? avail : wantFrames;
        if (n <= 0 || !out) return 0;
        for (int i = 0; i < n; i++) {
            const int ri = (int)((pulledAbs_ + i) % kOutCapFrames) * channels_;
            for (int c = 0; c < channels_; c++) {
                out[i * channels_ + c] = oring_[ri + c];
                oring_[ri + c] = 0.0f;
            }
        }
        pulledAbs_ += n;
        return n;
    }

private:
    // 输入第 idx 帧的 mid 值
    inline float monoIn(int idx) const {
        const float* p = ibuf_.data() + idx * channels_;
        float s = 0.0f;
        for (int c = 0; c < channels_; c++) s += p[c];
        return s / (float)channels_;
    }

    inline void dropFromInput(int frames) {
        if (frames <= 0) return;
        if (frames >= inFrames_) { inFrames_ = 0; return; }
        const int n = inFrames_ - frames;
        std::memmove(ibuf_.data(), ibuf_.data() + frames * channels_,
                     (size_t)n * channels_ * sizeof(float));
        inFrames_ = n;
    }

    // 把一个 Hann 窗叠加进输出环形缓冲
    inline void writeWindow(int s0) {
        const int base = curAbs_;
        for (int i = 0; i < kSeqFrames; i++) {
            const float w = hann_[i];
            const int   ri = (int)((base + i) % kOutCapFrames) * channels_;
            const int   si = (s0 + i) * channels_;
            for (int c = 0; c < channels_; c++) oring_[ri + c] += ibuf_[si + c] * w;
        }
        for (int i = 0; i < kSeqFrames; i++) prevWinMono_[i] = monoIn(s0 + i);
        havePrev_ = true;
        curAbs_ += kHopFrames;
    }

    int              sampleRate_ = 0;
    int              channels_   = 0;
    float            ratio_      = 1.0f;
    std::vector<float> hann_;
    std::vector<float> ibuf_;          // 输入线性缓冲
    std::vector<float> oring_;         // 输出环形缓冲
    std::vector<float> prevWinMono_;   // 上一已接受窗的 mid，用于相似度比对
    int              inFrames_  = 0;
    double           nominal_   = 0.0; // 下一窗在输入里的标称起点（相对 ibuf_ 头）
    long long        curAbs_    = 0;   // 下一合成窗绝对位置
    long long        pulledAbs_ = 0;   // 已取走的绝对帧
    bool             havePrev_  = false;
};

#endif // TIME_STRETCH_H
