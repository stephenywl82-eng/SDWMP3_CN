// bpm_detector.h — 全曲多算法 BPM 检测（v8.157 重写版，对齐 MIR 五步流程）
//
// 相对 v8.14 的关键修复：
//   P0-1 抗混叠：降采样前低通（在 JNI Downsampler 里做，本文件假定输入已干净）
//   P0-2 真正多频段分解：低/中/高三带 onset，低频(kick)权重最高（替代旧「假频谱通量」）
//   P0-3 亚帧分辨率：短帧(16ms) + ACF 抛物线插值 + 间隔中位数 → 128BPM 可精确报出
//   P1-1 多频段加权 novelty
//   P1-2 log 压缩 + 自适应阈值(局部均值相减 + 半波整流)
//   P1-3 倍频/半频用 τ/2τ/τ/2 比值 + 70..180 先验修正
//   P2-1 首拍用低频段(kick)能量定位（供 beatgrid 相位）
#pragma once
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>
#include <algorithm>

namespace bpmdet {

struct BpmResult {
    int   bpm = -1;        // 40..220，失败 -1（整数，仅供 UI/网格分段/旧调用方）
    /** 【V8.304】亚 BPM 精度浮点 BPM（40..220，失败 -1）。
     *  交叉淡化对拍必须用它：整数 BPM 在 120 附近量化步进 1 = ±0.83% 速度误差，
     *  正好是「鼓点对不齐」的量级。bpm 仍保留为 lround(bpmF) 供显示与旧路径。 */
    float bpmF = -1.f;
    float conf = 0.f;      // 峰值显著度（越大越可信）
    int   firstBeatMs = -1;// 首个强拍(kick)位置(ms)，失败 -1
    std::vector<struct BeatSegment> grid;  // 【P2-2】动态 beatgrid（分段 tempo），空=全曲匀速
};

// 【P2-2 动态 beatgrid】一段恒定 tempo 区间：startMs 起以 bpm 走拍，firstBeatMs 为该段内首个强拍(绝对 ms)
struct BeatSegment {
    int startMs = 0;
    int bpm = 0;
    float bpmF = 0.f;      // 【V8.304】该段浮点 tempo（bpm = lround(bpmF)）
    int firstBeatMs = -1;
    // 【P2】拍号与小节内偏移。解决"吸附到拍点"吸不到**小节第一拍**的问题：
    // 没有这两项时所有量化都隐含假设 4/4 且首拍即重拍，3/4 或段内非重拍起点会吸错位置。
    // downbeatOffsetMs = 该段内第一个小节第一拍相对 firstBeatMs 的偏移(ms)；
    // 未检出 → 0（= 首拍即重拍，与旧行为一致，不退化）。
    int beatsPerBar = 4;
    int downbeatOffsetMs = 0;
};

// ================================================================
// 工具：归一化向量
// ================================================================
static inline void normalize(const std::vector<float>& v, float& peak, float& invPeak) {
    peak = 0.f;
    for (float x : v) if (x > peak) peak = x;
    invPeak = (peak > 1e-6f) ? (1.f / peak) : 0.f;
}

// ================================================================
// 一阶低通（带拆分用）
// ================================================================
struct OnePole {
    float k = 0.1f, y = 0.f;
    void setup(float sr, float fc) { k = 1.f - std::exp(-6.2831853f * fc / sr); y = 0.f; }
    inline float process(float x) { y += k * (x - y); return y; }
};

// ================================================================
// RBJ 双二阶低通（抗混叠用，2 阶 Butterworth Q=0.7071）
// ================================================================
struct BiquadLP {
    float b0=1,b1=0,b2=0,a1=0,a2=0,x1=0,x2=0,y1=0,y2=0;
    void setup(float sr, float fc, float q = 0.70710678f) {
        float w0 = 6.2831853f * fc / sr;
        float cw = std::cos(w0), sw = std::sin(w0);
        float alpha = sw / (2.f * q);
        float b0n = (1.f - cw) * 0.5f, b1n = 1.f - cw, b2n = (1.f - cw) * 0.5f;
        float a0n = 1.f + alpha, a1n = -2.f * cw, a2n = 1.f - alpha;
        b0 = b0n/a0n; b1 = b1n/a0n; b2 = b2n/a0n; a1 = a1n/a0n; a2 = a2n/a0n;
        x1=x2=y1=y2=0.f;
    }
    inline float process(float x) {
        float y = b0*x + b1*x1 + b2*x2 - a1*y1 - a2*y2;
        x2=x1; x1=x; y2=y1; y1=y; return y;
    }
};

// ================================================================
// 多频段 onset novelty（P0-2 / P1-1 / P1-2）
//   frame=16ms(hop)，window 重叠；三带：low(<200, kick)、mid(200-2k)、high(2k-4k)
//   log 压缩 → 正向差分 → 半波整流 → 减局部均值(自适应阈值)
// ================================================================
// lowOnly=true → 只用低频 kick 带（权重 1/0/0），供「间隔中位数」算法使用。
// 【v8.160 根因修复】半拍踩镲(offbeat hat)在中/高频带同样产生 onset，
//   若中/高带参与峰检测，峰值间隔会整体塌成半拍 → BPM 被算成双倍
//   （72→144、84→170、90→179 等）。所以「间隔中位数」只吃低频带。
static void computeNovelty(const float* x, int n, int sr,
                           std::vector<float>& novelty, float& hopMs, bool lowOnly = false) {
    hopMs = 16.f;
    const int hop = std::max(1, (int)(sr * 0.016f));   // 16ms
    const int frame = hop * 2;                         // 32ms 窗
    if (n < frame * 8) { novelty.clear(); return; }

    OnePole lo, mid;                 // lo: LP200 ; mid: LP2000
    lo.setup((float)sr, 200.f);
    mid.setup((float)sr, 2000.f);

    std::vector<float> eL, eM, eH;   // 各带帧能量
    eL.reserve(n / hop + 1); eM.reserve(n / hop + 1); eH.reserve(n / hop + 1);
    for (int pos = 0; pos + frame <= n; pos += hop) {
        float sL = 0.f, sM = 0.f, sH = 0.f;
        for (int i = 0; i < frame; i++) {
            float v = x[pos + i];
            float a = lo.process(v);          // <200
            float b = mid.process(v);         // <2000
            float low  = a;
            float midb = b - a;               // 200..2000
            float high = v - b;               // >2000 (Nyquist 4k)
            sL += low * low; sM += midb * midb; sH += high * high;
        }
        eL.push_back(std::sqrt(sL / frame));
        eM.push_back(std::sqrt(sM / frame));
        eH.push_back(std::sqrt(sH / frame));
    }
    size_t F = eL.size();
    if (F < 16) { novelty.clear(); return; }

    // log 压缩（C=1000 提升弱拍可见度）→ 正向差分 → 半波整流
    (void)lowOnly;
    auto noveltyFrom = [&](const std::vector<float>& e) {
        std::vector<float> d(F, 0.f);
        for (size_t i = 1; i < F; i++) {
            float a = std::log(1.f + 1000.f * e[i]);
            float b = std::log(1.f + 1000.f * e[i-1]);
            float v = a - b;
            d[i] = v > 0.f ? v : 0.f;
        }
        return d;
    };
    std::vector<float> nL = noveltyFrom(eL);
    std::vector<float> nM = noveltyFrom(eM);
    std::vector<float> nH = noveltyFrom(eH);

    // 自适应阈值：减去 ±1.5s 局部均值（半波整流），再按带加权求和（低频最高）
    const int W = std::max(4, (int)(1500.f / hopMs));
    const float wLow = 1.0f, wMid = 0.5f, wHigh = 0.35f;
    novelty.assign(F, 0.f);
    for (size_t i = 0; i < F; i++) {
        int a = std::max(0, (int)i - W), b = std::min((int)F - 1, (int)i + W);
        float mL = 0.f, mM = 0.f, mH = 0.f; int c = 0;
        for (int j = a; j <= b; j++) { mL += nL[j]; mM += nM[j]; mH += nH[j]; c++; }
        if (c > 0) { mL /= c; mM /= c; mH /= c; }
        auto sub = [](float v, float m) { float r = v - m; return r > 0.f ? r : 0.f; };
        // 【v8.160】lowOnly：只用低频 kick 带，屏蔽半拍踩镲把峰间隔绑成半拍
        if (lowOnly) novelty[i] = sub(nL[i], mL);
        else novelty[i] = wLow * sub(nL[i], mL) + wMid * sub(nM[i], mM) + wHigh * sub(nH[i], mH);
    }
    float pk, ipk; normalize(novelty, pk, ipk);
    if (pk < 1e-7f) { novelty.clear(); }
}

// ================================================================
// 【V8.223】谱通量 onset（spectral flux）—— 专业 MIR 标配，替代时域能量 onset
//   为什么：流行歌人声/和声铺满频谱时，时域能量差分被人声淹没（拍点糊掉）。
//   做法：256 点 FFT（32ms 窗/16ms 步）→ HPSS-lite（每频点减去近 9 帧(~144ms)
//   中值 = 谐波底，剩下 max(0,·) 就是鼓/打击成分）→ log 压缩 → 正向差分半波整流
//   → 三带（<250 / 250-2k / >2k）加权累加 → 局部【中值】阈值（比均值抗空拍段）。
//   人声再响也不挡拍，这是 librosa/essentia 系检测器对流行歌准的核心原因。
// ================================================================
static inline void fftRadix2(float* re, float* im, int n) {   // n 必须是 2 的幂
    for (int i = 1, j = 0; i < n; i++) {
        int bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) { std::swap(re[i], re[j]); std::swap(im[i], im[j]); }
    }
    for (int len = 2; len <= n; len <<= 1) {
        float ang = -6.2831853f / (float)len;
        float wr = std::cos(ang), wi = std::sin(ang);
        for (int i = 0; i < n; i += len) {
            float cr = 1.f, ci = 0.f;
            for (int k = 0; k < len / 2; k++) {
                float ur = re[i + k], ui = im[i + k];
                float vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci;
                float vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr;
                re[i + k] = ur + vr; im[i + k] = ui + vi;
                re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi;
                float ncr = cr * wr - ci * wi;
                ci = cr * wi + ci * wr; cr = ncr;
            }
        }
    }
}

static void computeFluxNovelty(const float* x, int n, int sr,
                               std::vector<float>& novelty, float& hopMs) {
    hopMs = 16.f;
    const int Nfft = 256, hop = Nfft / 2;              // 32ms 窗 / 16ms 步
    if (n < Nfft * 8) { novelty.clear(); return; }
    const int bins = Nfft / 2 + 1;                     // 129
    const float binHz = (float)sr / (float)Nfft;
    std::vector<float> win(Nfft);
    for (int i = 0; i < Nfft; i++) win[i] = 0.5f - 0.5f * std::cos(6.2831853f * (float)i / (float)(Nfft - 1));

    std::vector<float> re(Nfft), im(Nfft), mag(bins), prev(bins, 0.f);
    std::vector<float> ring((size_t)9 * bins, 0.f);    // HPSS-lite 谐波底滑动窗
    int ringPos = 0, ringFill = 0;
    std::vector<float> flux; flux.reserve((size_t)n / hop + 1);

    for (int pos = 0; pos + Nfft <= n; pos += hop) {
        for (int i = 0; i < Nfft; i++) { re[i] = x[pos + i] * win[i]; im[i] = 0.f; }
        fftRadix2(re.data(), im.data(), Nfft);
        for (int b = 0; b < bins; b++)
            mag[b] = std::sqrt(re[b] * re[b] + im[b] * im[b]);

        float fL = 0.f, fM = 0.f, fH = 0.f;
        for (int b = 1; b < bins; b++) {
            // 谐波底 = 该 bin 近 9 帧 raw 幅度的中值（低填充期用现有帧）
            float tmp[9]; int cnt = 0;
            for (int k = 0; k < 9; k++) {
                float v = ring[(size_t)k * bins + b];
                if (v > 0.f) tmp[cnt++] = v;
            }
            float med = 0.f;
            if (cnt > 0) { std::sort(tmp, tmp + cnt); med = tmp[cnt / 2]; }
            float perc = mag[b] - med; if (perc < 0.f) perc = 0.f;   // 打击成分
            float lm = std::log(1.f + 1000.f * perc);
            float d = lm - prev[b]; prev[b] = lm;
            if (d > 0.f) {
                float hz = (float)b * binHz;
                if (hz < 250.f) fL += d;
                else if (hz < 2000.f) fM += d;
                else fH += d;
            }
            ring[(size_t)ringPos * bins + b] = mag[b];
        }
        ringPos = (ringPos + 1) % 9; if (ringFill < 9) ringFill++;
        flux.push_back(fL + fM * 0.5f + fH * 0.35f);
    }
    size_t F = flux.size();
    if (F < 32) { novelty.clear(); return; }

    // 自适应阈值：减 ±1.5s 局部【中值】（对前奏/间奏空拍段远比均值稳），半波整流
    const int W = std::max(4, (int)(1500.f / hopMs));
    std::vector<float> win2((size_t)W * 2 + 1);
    novelty.assign(F, 0.f);
    for (size_t i = 0; i < F; i++) {
        int a = std::max(0, (int)i - W), b = std::min((int)F - 1, (int)i + W);
        int c = 0;
        for (int j = a; j <= b; j++) win2[c++] = flux[j];
        std::sort(win2.begin(), win2.begin() + c);
        float med = win2[c / 2];
        float r = flux[i] - med;
        novelty[i] = r > 0.f ? r : 0.f;
    }
    float pk, ipk; normalize(novelty, pk, ipk);
    if (pk < 1e-7f) novelty.clear();
}

/** 【V8.223】主路径：谱通量优先，谱通量产出无效（过静/过短）时回退旧时域能量 onset。 */
static void computeNoveltyV2(const float* x, int n, int sr,
                             std::vector<float>& novelty, float& hopMs) {
    computeFluxNovelty(x, n, sr, novelty, hopMs);
    if (novelty.size() < 16) computeNovelty(x, n, sr, novelty, hopMs);
}

// ================================================================
// ACF（可分数 lag，线性插值）
// ================================================================
static inline float acfAt(const std::vector<float>& x, float lag, float invPeak) {
    (void)invPeak;
    int l0 = (int)std::floor(lag);
    if (l0 < 1) return 0.f;
    float fr = lag - l0;
    auto acfInt = [&](int L) -> float {
        double acc = 0, n1 = 0, n2 = 0;
        size_t N = x.size();
        if ((size_t)L >= N) return 0.f;
        for (size_t i = 0; i + L < N; i++) {
            double a = x[i], b = x[i + L];
            acc += a * b; n1 += a * a; n2 += b * b;
        }
        if (n1 < 1e-12 || n2 < 1e-12) return 0.f;
        return (float)(acc / std::sqrt(n1 * n2));
    };
    float a0 = acfInt(l0), a1 = acfInt(l0 + 1);
    return a0 * (1.f - fr) + a1 * fr;
}

// 幂律加权 ACF（Ellis 2007 风格）：score = acf * (1 - w*|lag-期望|/期望)
static inline float weightedAcf(const std::vector<float>& x, float lag, float invPeak, float expLag) {
    float s = acfAt(x, lag, invPeak);
    if (s <= 0.f) return 0.f;
    float w = 0.5f;
    float pen = 1.f - w * std::fabs(lag - expLag) / expLag;
    if (pen < 0.f) pen = 0.f;
    return s * pen;
}

// ================================================================
// 算法①：novelty ACF → 【V8.223】组合滤波 + log-高斯先验 → 亚帧 tempo
//   组合滤波（Ellis 2007）：score(τ) = ACF(τ) + 0.5·ACF(τ/2) + 0.5·ACF(2τ)
//     —— 半拍/倍拍处的峰与真值在求和时互相增强，八度歧义在【选峰之前】就被
//     消掉大半，而不是等 refineOctave 事后猜。
//   log-高斯速度先验（中心 120BPM，σ=0.9 八度）：流行歌 90..135 出现密度远高于
//     40 或 200，按八度距离衰减比旧线性惩罚贴真实分布。
// ================================================================
static float detectTempoAutocorr(const std::vector<float>& novelty, float hopMs, float& conf) {
    conf = 0.f;
    size_t N = novelty.size();
    float minLag = 60000.f / (220.f * hopMs);   // 220BPM
    float maxLag = 60000.f / (40.f  * hopMs);   // 40BPM
    int lMin = std::max(2, (int)std::floor(minLag));
    int lMax = std::min((int)N / 2, (int)std::ceil(maxLag));
    if (lMax <= lMin + 2) return -1.f;

    // ACF 曲线（先整条算好，组合滤波要查 τ/2 与 2τ）
    std::vector<float> acf(lMax + 2, 0.f);
    for (int lag = lMin; lag <= lMax; lag++) acf[lag] = acfAt(novelty, (float)lag, 1.f);

    auto priorOf = [&](float lag) -> float {
        float bpm = 60000.f / (lag * hopMs);
        float d = std::log2(bpm / 120.f);
        float e = d / 0.9f;
        return std::exp(-0.5f * e * e);
    };

    float best = 0.f; int bestLag = 0; std::vector<float> comb(lMax + 2, 0.f);
    for (int lag = lMin; lag <= lMax; lag++) {
        float s = acf[lag];
        int h = lag / 2, d2 = lag * 2;
        if (h >= lMin) s += 0.5f * acf[h];
        if (d2 <= lMax) s += 0.5f * acf[d2];
        comb[lag] = s * priorOf((float)lag);
        if (comb[lag] > best) { best = comb[lag]; bestLag = lag; }
    }
    if (bestLag == 0 || best <= 0.f) return -1.f;
    // 抛物线插值（亚帧）
    float y1 = comb[bestLag - 1], y2 = comb[bestLag], y3 = comb[bestLag + 1];
    float denom = (y1 - 2.f * y2 + y3);
    float delta = (std::fabs(denom) > 1e-9f) ? 0.5f * (y1 - y3) / denom : 0.f;
    if (delta < -0.5f) delta = -0.5f; if (delta > 0.5f) delta = 0.5f;
    float lagF = bestLag + delta;
    float bpm = 60000.f / (lagF * hopMs);

    // 置信度：峰 vs 均值（组合曲线上）
    float mean = 0.f; int cnt = 0;
    for (int lag = lMin; lag <= lMax; lag++) {
        if (std::abs(lag - bestLag) > 2) { mean += comb[lag]; cnt++; }
    }
    conf = (cnt > 0) ? (best - mean / cnt) : best;
    return bpm;
}

// ================================================================
// 算法②：间隔中位数（P0-3：中位数而非众数 bin，亚帧精度）
// ================================================================
static float detectTempoInterval(const std::vector<float>& novelty, float hopMs, float& conf) {
    conf = 0.f;
    size_t N = novelty.size();
    if (N < 16) return -1.f;
    // 自适应峰值：> 局部(±1s)均值 + 0.6*局部标准差
    const int W = std::max(4, (int)(1000.f / hopMs));
    std::vector<int> peaks;
    for (size_t i = 1; i + 1 < N; i++) {
        int a = std::max(0, (int)i - W), b = std::min((int)N - 1, (int)i + W);
        float m = 0.f, m2 = 0.f; int c = 0;
        for (int j = a; j <= b; j++) { m += novelty[j]; m2 += novelty[j] * novelty[j]; c++; }
        if (c < 3) continue;
        m /= c; m2 /= c;
        float sd = std::sqrt(std::max(0.f, m2 - m * m));
        float thr = m + 0.6f * sd;
        if (novelty[i] > thr && novelty[i] >= novelty[i-1] && novelty[i] > novelty[i+1])
            peaks.push_back((int)i);
    }
    if (peaks.size() < 6) return -1.f;
    // 相邻间隔（限 40..220BPM 对应帧数）
    float lagMin = 60000.f / (220.f * hopMs);
    float lagMax = 60000.f / (40.f  * hopMs);
    std::vector<float> iv;
    for (size_t i = 1; i < peaks.size(); i++) {
        float g = (float)(peaks[i] - peaks[i-1]);
        if (g >= lagMin * 0.9f && g <= lagMax * 1.1f) iv.push_back(g);
        // 漏检一倍：合并相邻两拍
        if (i >= 2) {
            float g2 = (float)(peaks[i] - peaks[i-2]) * 0.5f;
            if (g2 >= lagMin * 0.9f && g2 <= lagMax * 1.1f) iv.push_back(g2);
        }
    }
    if (iv.size() < 4) return -1.f;
    std::sort(iv.begin(), iv.end());
    float med = iv[iv.size() / 2];
    // 置信度：中位数附近的集中度
    int near = 0;
    for (float v : iv) if (std::fabs(v - med) <= med * 0.06f) near++;
    conf = (float)near / (float)iv.size();
    return 60000.f / (med * hopMs);
}

// ================================================================
// 【V8.224】Ellis/librosa 式动态规划整拍跟踪（librosa.beat.beat_track 同范式）
//   输入 novelty + 粗略拍周期（帧），输出整条 beat 帧序列。
//   DP 转移代价 -λ·ln²(d/τ)（λ=600，librosa 同款 tightness）：每一拍都必须
//   连续解释 onset，半拍/倍拍错位、 tempo 漂移会被转移代价直接压掉 ——
//   这是 NN（madmom RNN）出现之前业界的专业标准范式。
// ================================================================
static std::vector<int> trackBeatsDP(const std::vector<float>& nov, float periodF) {
    const float lam = 600.f;
    int N = (int)nov.size();
    int tau = (int)std::lround(periodF);
    if (tau < 4 || N < tau * 4) return {};
    std::vector<float> score((size_t)N, 0.f);
    std::vector<int> back((size_t)N, -1);
    const int warm = tau * 2;   // 前 2τ 只允许序列起点（与 librosa 一致：首拍无前驱）
    for (int t = 0; t < N; t++) {
        score[(size_t)t] = nov[(size_t)t];
        if (t >= warm) {
            int jLo = t - tau * 2; if (jLo < 0) jLo = 0;
            int jHi = t - tau / 2;
            float best = score[(size_t)t]; int arg = -1;
            for (int j = jLo; j <= jHi; j++) {
                float d = (float)(t - j);
                float l = std::log(d / (float)tau);
                float v = score[(size_t)j] - lam * l * l;
                if (v > best) { best = v; arg = j; }
            }
            score[(size_t)t] = best;
            back[(size_t)t] = arg;
        }
    }
    // 终点：末尾 τ 帧里总分最高处往回追（librosa 同款）
    int end = -1; float bs = -1e30f;
    for (int t = std::max(0, N - tau); t < N; t++)
        if (score[(size_t)t] > bs) { bs = score[(size_t)t]; end = t; }
    if (end < 0) return {};
    std::vector<int> beats;
    for (int t = end; t >= 0; t = back[(size_t)t]) beats.push_back(t);
    if ((int)beats.size() < tau / 4) return {};   // 追出的序列太短 = 没真锁住
    std::reverse(beats.begin(), beats.end());
    return beats;
}

/** DP 拍序列 → 精细 BPM（间隔中位数）+ 一致性(0..1，间隔贴近中值的比例）。失败 -1。 */
static float dpTempoFromBeats(const std::vector<int>& beats, float hopMs, float& consistency) {
    consistency = 0.f;
    if (beats.size() < 8) return -1.f;
    std::vector<float> iv;
    iv.reserve(beats.size() - 1);
    for (size_t k = 1; k < beats.size(); k++) iv.push_back((float)(beats[k] - beats[k - 1]));
    std::sort(iv.begin(), iv.end());
    float med = iv[iv.size() / 2];
    if (med <= 0.f) return -1.f;
    int near = 0;
    for (float v : iv) if (std::fabs(v - med) <= med * 0.04f) near++;
    consistency = (float)near / (float)iv.size();
    return 60000.f / (med * hopMs);
}

// ================================================================
// 【v8.166】栅格对比度：给定 tempo，在所有相位偏移里找「onset 最贴栅格」的那个，
//   返回 栅格均值 / 全局均值（>1 说明该 tempo 能解释起音；越接近 1 越无解释力）
//   nov: novelty（起音强度，逐帧）  hopMs: 每帧毫秒
// ================================================================
static float gridContrast(const std::vector<float>& nov, float hopMs, float bpm) {
    if (nov.empty() || hopMs <= 0.f || bpm <= 0.f) return 0.f;
    const float periodMs = 60000.f / bpm;
    int periodFrames = (int)std::lround(periodMs / hopMs);
    if (periodFrames < 3) return 0.f;
    int win = std::min((int)nov.size(), (int)(30000.f / hopMs));   // 用前 30s
    if (win < periodFrames * 4) return 0.f;
    float overall = 0.f;
    for (int i = 0; i < win; i++) overall += nov[i];
    overall /= (float)win;
    if (overall <= 1e-9f) return 0.f;
    int step = std::max(1, periodFrames / 24);
    float best = 0.f;
    for (int p = 0; p < periodFrames; p += step) {
        float s = 0.f; int c = 0;
        for (int i = p; i < win; i += periodFrames) { s += nov[i]; c++; }
        if (c >= 4) { float a = s / (float)c; if (a > best) best = a; }
    }
    return best / overall;
}

// ================================================================
// 倍频/半频修正（P1-3）：把候选折算进 [70,180) 常用拍区间
//   倍频/半频是本质歧义（87 与 174、90 与 180 无法只靠节拍区分）；
//   这里采用 DJ 软件通用做法：折进 70..179 常见区间，避免把普通歌翻倍。
// 【v8.166】仅靠区间折叠修不了「真 87 被判成 174」（2x 后仍在区间内）——
//   实测 378 首里 35% 落在 140-180，库里多为抒情慢歌，明显是倍频误判。
//   故对高段(>=140)加一道 onset 栅格对比度复核：半速栅格解释力更好就判为倍频误判。
// ================================================================
// 【V8.304】refineOctave 的浮点版：保留亚 BPM 精度，不再 lround 截断。
// 倍频/半频折叠与 onset 栅格复核的逻辑与整数版完全一致（同一段判据），
// 只是最后一步不取整，交由调用方按需取整。
static float refineOctaveF(const std::vector<float>& nov, float hopMs, float bpm) {
    if (bpm <= 0.f) return -1.f;
    while (bpm < 70.f)   bpm *= 2.f;
    while (bpm >= 180.f) bpm *= 0.5f;
    if (bpm < 40.f) bpm *= 2.f;
    if (bpm > 220.f) bpm *= 0.5f;
    if (bpm >= 140.f && !nov.empty() && hopMs > 0.f) {
        float half = bpm * 0.5f;
        if (half >= 60.f) {
            float c1 = gridContrast(nov, hopMs, bpm);
            float c2 = gridContrast(nov, hopMs, half);
            // 半速栅格对比度明显更好 → 说明「拍」其实落在半速上，原值是倍频
            if (c2 > c1 * 1.03f) bpm = half;
        }
    }
    return bpm;
}

static int refineOctave(const std::vector<float>& nov, float hopMs, float bpm) {
    const float f = refineOctaveF(nov, hopMs, bpm);
    return (f <= 0.f) ? -1 : (int)std::lround(f);
}

// ================================================================
// 【v8.166】栅格相位拟合首拍：不再取「第一个响的 kick」
//   旧实现 lowBandFirstBeatMs 取前 20s 内首个达到峰值 60% 的位置——
//   峰值若来自副歌的鼓（很多歌前 20s 内没有鼓），首拍就被报成 12~18s（实测 29% >10s），
//   拍栅格相位基准因此整体错位。
//   新实现：用节拍周期在整个窗口上做相位搜索，取 onset 能量最对齐的相位 →
//   即使前奏无鼓也能靠后续段落把相位锁出来。
//   返回：相对 x 起点的首拍 ms（0..周期），失败 -1
// ================================================================
static int fitBeatPhaseMs(const float* x, int n, int sr, float bpm) {
    if (!x || n < sr / 4 || bpm < 40.f || bpm > 220.f) return -1;
    const int hop = std::max(1, sr / 1000);            // ~1ms/frame（8k → 8 样本 = 1ms）
    OnePole low; low.setup((float)sr, 200.f);
    std::vector<float> env; env.reserve((size_t)n / hop + 1);
    float acc = 0.f;
    for (int i = 0; i < n; i += hop) {
        int e = std::min(n, i + hop);
        for (int j = i; j < e; j++) { float v = low.process(x[j]); acc += v * v; }
        env.push_back(std::sqrt(acc / std::max(1, e - i))); acc = 0.f;
    }
    if (env.size() < 64) return -1;
    // onset = 正向差分（突出起音，抑制持续低音）
    std::vector<float> on(env.size(), 0.f);
    for (size_t i = 1; i < env.size(); i++) { float d = env[i] - env[i - 1]; on[i] = (d > 0.f) ? d : 0.f; }
    const float periodMs = 60000.f / bpm;
    int pf = (int)std::lround(periodMs);               // 1ms/frame → 每拍帧数 == ms
    if (pf < 20) return -1;                            // >300BPM 视为无效
    int lim = std::min((int)on.size(), 30000);
    if (lim <= pf * 4) return -1;
    // 归一化：抵消前奏弱/副歌响造成的能量倾斜
    float meanAll = 0.f;
    for (int i = 0; i < lim; i++) meanAll += on[i];
    meanAll /= (float)lim;
    if (meanAll <= 1e-9f) return -1;
    float best = -1.f; int bestP = -1;
    int step = std::max(1, pf / 24);
    for (int p = 0; p < pf; p += step) {
        float s = 0.f; int c = 0;
        for (int i = p; i < lim; i += pf) { s += on[i]; c++; }
        if (c >= 4) { float a = s / (float)c; if (a > best) { best = a; bestP = p; } }
    }
    if (bestP < 0 || best <= 0.f) return -1;
    return bestP;   // 帧==ms
}

// ================================================================
// 【P2-2 动态 beatgrid】低频(kick)包络定位首个强拍（绝对/相对 ms）
//   x: 8k 单声道；返回相对 x 起点的首个强拍 ms，失败 -1
// ================================================================
static int lowBandFirstBeatMs(const float* x, int n, int sr) {
    if (!x || n < sr / 2) return -1;
    const int hop = std::max(1, sr / 1000);   // ~1ms/frame
    OnePole low; low.setup((float)sr, 200.f);
    std::vector<float> env; env.reserve((size_t)n / hop + 1);
    float acc = 0.f;
    for (int i = 0; i < n; i += hop) {
        int e = std::min(n, i + hop);
        for (int j = i; j < e; j++) { float v = low.process(x[j]); acc += v * v; }
        env.push_back(std::sqrt(acc / std::max(1, e - i))); acc = 0.f;
    }
    if (env.size() < 16) return -1;
    int lim = std::min((int)env.size(), 20000);
    float pk = 0.f;
    for (int i = 0; i < lim; i++) pk = std::max(pk, env[i]);
    if (pk <= 1e-5f) return -1;
    for (int i = 0; i < lim; i++)
        if (env[i] >= pk * 0.6f) return (int)((int64_t)i * hop * 1000 / sr);
    return -1;
}

// ================================================================
// 【P2-2】局部 tempo：对一段样本算 BPM（多算法融合 + 倍频修正到 70..180）
// ================================================================
static int localBpmOf(const float* w, int nFrames, int sr) {
    std::vector<float> nov; float hopMs = 0.f;
    computeNoveltyV2(w, nFrames, sr, nov, hopMs);   // 【V8.223】谱通量优先
    // 【v8.160】间隔中位数只用低频 kick 带，避免半拍踩镲把间隔绑成半拍→BPM 双倍
    std::vector<float> novLow; float hopMs2 = 0.f;
    computeNovelty(w, nFrames, sr, novLow, hopMs2, true);
    if (nov.size() < 16) return -1;
    float c1 = 0.f, c2 = 0.f;
    float b1 = detectTempoAutocorr(nov, hopMs, c1);
    float b2 = (novLow.size() >= 16) ? detectTempoInterval(novLow, hopMs2, c2) : -1.f;
    float bpm = 0.f;
    if (b1 > 0 && b2 > 0 && std::fabs(b1 - b2) <= b1 * 0.04f) bpm = (b1 + b2) * 0.5f;
    else if (c1 >= c2) bpm = b1;
    else bpm = b2;
    if (bpm <= 0.f) return -1;
    return refineOctave(nov, hopMs, bpm);
}

// ================================================================
// 【P2-2 动态 beatgrid】全曲分段 tempo
//   25s/步进 10s 局部扫描 → 中值滤波 → 迟滞分段 → 合并短段/近似段
//   结果 <2 段 → 视为匀速（out 清空，调用方回退全局 BPM）
// ================================================================
static void detectBeatGrid(const float* s, int n, int sr, std::vector<BeatSegment>& out) {
    out.clear();
    if (!s || n < sr * 20) return;
    const int chunkSec = 25, stepSec = 10;
    const int cs = sr * chunkSec, st = sr * stepSec;
    if (cs > n) return;
    std::vector<int> seqBpm, seqStart;
    for (int start = 0; start + cs <= n; start += st) {
        int b = localBpmOf(s + start, cs, sr);
        if (b > 0) { seqBpm.push_back(b); seqStart.push_back(start); }
    }
    if (seqBpm.size() < 3) return;
    // 3 点中值滤波（去掉个别跳变）
    std::vector<int> sm = seqBpm;
    for (size_t i = 0; i < seqBpm.size(); i++) {
        int a = (int)i - 1; if (a < 0) a = 0;
        int c = (int)i + 1; if (c >= (int)seqBpm.size()) c = (int)seqBpm.size() - 1;
        int arr[3] = { seqBpm[a], seqBpm[i], seqBpm[c] };
        std::sort(arr, arr + 3); sm[i] = arr[1];
    }
    // 迟滞分段：连续 2 个 chunk 偏离当前段 >4 BPM 才开新段（边界取第一个偏离 chunk 的前一刻）
    struct Seg { int startSample; double bpm; int count; };
    std::vector<Seg> segs;
    segs.push_back({ seqStart[0], (double)sm[0], 1 });
    int diffRun = 0;
    for (size_t i = 1; i < sm.size(); i++) {
        Seg& cur = segs.back();
        if (std::abs(sm[i] - cur.bpm) > 4.0) {
            diffRun++;
            if (diffRun >= 2) {
                segs.push_back({ seqStart[i - 1], (double)sm[i], 1 });
                diffRun = 0;
            } else {
                cur.bpm = (cur.bpm * cur.count + sm[i]) / (cur.count + 1); cur.count++;
            }
        } else {
            diffRun = 0;
            cur.bpm = (cur.bpm * cur.count + sm[i]) / (cur.count + 1); cur.count++;
        }
    }
    if (segs.size() < 2) return;
    // 合并过短段（<2 个 chunk≈20s）到前一段
    std::vector<Seg> merged;
    for (auto& sg : segs) {
        if (!merged.empty() && sg.count < 2) {
            Seg& pv = merged.back();
            pv.bpm = (pv.bpm * pv.count + sg.bpm * sg.count) / (pv.count + sg.count);
            pv.count += sg.count;
        } else merged.push_back(sg);
    }
    if (merged.size() < 2) return;
    // 相邻段 BPM 差 <=3 则合并
    std::vector<Seg> fin;
    for (auto& sg : merged) {
        if (!fin.empty() && std::abs(fin.back().bpm - sg.bpm) <= 3.0) {
            Seg& pv = fin.back();
            pv.bpm = (pv.bpm * pv.count + sg.bpm * sg.count) / (pv.count + sg.count);
            pv.count += sg.count;
        } else fin.push_back(sg);
    }
    if (fin.size() < 2) return;
    for (auto& sg : fin) {
        BeatSegment bs;
        bs.startMs = (int)((int64_t)sg.startSample * 1000 / sr);
        bs.bpmF = (float)sg.bpm;                       // 【V8.304】段内浮点 tempo 原样保留
        bs.bpm = (int)std::lround(sg.bpm);
        if (bs.bpm < 40 || bs.bpm > 220) continue;
        // 【v8.166】用栅格相位拟合（旧 lowBandFirstBeatMs 会报出段外/极晚的假首拍）
        int rel = fitBeatPhaseMs(s + sg.startSample, std::min(cs, n - sg.startSample), sr, (float)bs.bpm);
        bs.firstBeatMs = (rel >= 0) ? (bs.startMs + rel) : -1;
        out.push_back(bs);
    }
    if (out.size() < 2) { out.clear(); return; }
    // 【v8.166】噪声抑制：分段 BPM 极差 <=6 说明「动态 tempo」只是估计抖动
    // （实测 147 首有 grid 的中位极差 20、最大 83，抒情歌不可能如此）。
    // 极差小 → 视为匀速，清空 grid 用全曲 BPM，避免把抖动当变速写进拍栅格。
    int mn = 999, mx = 0;
    for (auto& bs : out) { mn = std::min(mn, bs.bpm); mx = std::max(mx, bs.bpm); }
    if (mx - mn <= 6) out.clear();
}

// ================================================================
// 单窗口：多算法 → 融合 → 倍频修正
// ================================================================
static int detectWindowAlgorithms(const float* samples, int n, int sampleRate) {
    if (n < sampleRate * 4) return -1;
    std::vector<float> novelty; float hopMs = 0.f;
    computeNoveltyV2(samples, n, sampleRate, novelty, hopMs);   // 【V8.223】谱通量优先
    // 【v8.160】间隔中位数只用低频 kick 带（半拍踩镲不参与峰检测，消除 BPM 双倍）
    std::vector<float> novLow; float hopMs2 = 0.f;
    computeNovelty(samples, n, sampleRate, novLow, hopMs2, true);
    if (novelty.size() < 16) return -1;

    float c1 = 0.f, c2 = 0.f;
    float b1 = detectTempoAutocorr(novelty, hopMs, c1);
    float b2 = (novLow.size() >= 16) ? detectTempoInterval(novLow, hopMs2, c2) : -1.f;
    float bpm;
    if (b1 > 0 && b2 > 0 && std::fabs(b1 - b2) <= b1 * 0.04f) bpm = (b1 + b2) * 0.5f;
    else if (c1 >= c2) bpm = (b1 > 0 ? b1 : b2);
    else bpm = (b2 > 0 ? b2 : b1);
    if (bpm <= 0.f) return -1;
    return refineOctave(novelty, hopMs, bpm);
}

// ================================================================
// 全曲滑动窗口主入口（n = 8kHz 单声道 float 总帧数）
//   返回：40..220 范围 BPM，失败 -1
// ================================================================
static int detectBpmEx(const float* samples8k, int n8k, BpmResult& out);

static int detectBpm(const float* samples8k, int n8k) {
    BpmResult r;
    return detectBpmEx(samples8k, n8k, r);
}

// ================================================================
// 全曲主入口（带额外信息）：BPM + 首拍 + 置信度
// ================================================================
static int detectBpmEx(const float* samples8k, int n8k, BpmResult& out) {
    out = BpmResult();
    if (!samples8k || n8k < 8000 * 4) return -1;
    const int winSec = 30, stepSec = 20;      // P1-4：步进 15→20s，同样时长窗口数更多
    const int winFrames  = 8000 * winSec;
    const int stepFrames = 8000 * stepSec;

    // 【V8.304】投票改在浮点上做：原来每窗 lround 成整数再聚类，120 附近 ±0.5 BPM 的
    // 真实差异被量化掉（120.4 与 120.6 都变 120/121），投票结果只能给到整数 BPM。
    // 现在聚类仍按 ±3 BPM 归组（避免倍频混淆），但组内累加**浮点加权和**，
    // 最终 bpmF = Σ(bpm_i × conf_i)/Σconf_i —— 亚 BPM 精度由此而来。
    float songCandidates[64], songConf[64], songWsum[64]; int nCand = 0;
    int bestBpm = -1; float bestSum = -1.f, bestBpmF = -1.f;
    // 往第 i 个聚类投一票（bpmF 浮点、c 置信权重）
    auto vote = [&](float bpmF, float c) {
        if (bpmF <= 0.f) return;
        for (int i = 0; i < nCand; i++) {
            if (std::fabs(bpmF - songCandidates[i]) <= 3.f) {   // ±3 BPM 归组，判据与原整数版一致
                songConf[i] += c; songWsum[i] += bpmF * c; return;
            }
        }
        if (nCand < 64) {
            songCandidates[nCand] = bpmF; songConf[nCand] = c; songWsum[nCand] = bpmF * c; nCand++;
        }
    };

    for (int start = 0; start + winFrames <= n8k; start += stepFrames) {
        const float* w = samples8k + start;
        std::vector<float> novelty; float hopMs = 0.f;
        computeNoveltyV2(w, winFrames, 8000, novelty, hopMs);   // 【V8.223】谱通量优先
        // 【v8.160】间隔中位数只用低频 kick 带（半拍踩镲不参与峰检测，消除 BPM 双倍）
        std::vector<float> novLow; float hopMs2 = 0.f;
        computeNovelty(w, winFrames, 8000, novLow, hopMs2, true);
        if (novelty.size() < 16) continue;
        float c1 = 0.f, c2 = 0.f;
        float b1 = detectTempoAutocorr(novelty, hopMs, c1);
        float b2 = (novLow.size() >= 16) ? detectTempoInterval(novLow, hopMs2, c2) : -1.f;
        float bpm = 0.f, c = 0.f;
        if (b1 > 0 && b2 > 0 && std::fabs(b1 - b2) <= b1 * 0.04f) { bpm = (b1 + b2) * 0.5f; c = c1 + c2; }
        else if (c1 >= c2) { bpm = b1; c = c1; }
        else { bpm = b2; c = c2; }
        if (bpm <= 0.f) continue;
        // 【V8.304】投浮点票（refineOctaveF 保留小数；refineOctave 整数版仅做有效性快筛）
        float bpmF = refineOctaveF(novelty, hopMs, bpm);
        if (bpmF < 40.f || bpmF > 220.f) continue;
        vote(bpmF, c);
        // 【V8.224】DP 整拍跟踪：以本窗 ACF 周期为种子找出整条拍序列，
        // 拍间隔中位数给出更稳的 tempo —— 拍序列必须连续解释 onset，
        // 半拍/倍拍/漂移被转移代价压掉。与 ACF 结果进同一 ±3 聚类，
        // 权重 = 一致性×1.5（与 ACF 置信度量级相当，不喧宾夺主）。
        {
            auto beats = trackBeatsDP(novelty, 60000.f / ((float)bpm * hopMs));
            float cons = 0.f;
            float b3 = dpTempoFromBeats(beats, hopMs, cons);
            if (b3 > 0.f && cons >= 0.5f && beats.size() >= 16) {
                float bpm3 = refineOctaveF(novelty, hopMs, b3);
                if (bpm3 >= 40.f && bpm3 <= 220.f) {
                    vote(bpm3, cons * 1.5f);
                }
            }
        }
    }
    if (nCand == 0) return -1;
    int bi = 0;
    for (int i = 1; i < nCand; i++) if (songConf[i] > songConf[bi]) bi = i;
    bestSum = songConf[bi];
    // 组内置信度加权平均 = 最终浮点 tempo（比任何单窗估计都稳，且保留亚 BPM 精度）
    bestBpmF = (bestSum > 0.f) ? (songWsum[bi] / bestSum) : songCandidates[bi];
    if (bestBpmF < 40.f || bestBpmF > 220.f) return -1;
    bestBpm = (int)std::lround(bestBpmF);

    out.bpm = bestBpm;
    out.bpmF = bestBpmF;        // 【V8.304】对拍用这个（浮点）
    out.conf = bestSum;
    // 【v8.166】首拍：栅格相位拟合（替代原「首个达到前 20s 峰值 60% 的位置」——
    //   该写法在鼓晚进场的歌上会报出 12~18s 的假首拍，实测 29% 的缓存首拍 >10s）
    out.firstBeatMs = fitBeatPhaseMs(samples8k, n8k, 8000, (float)bestBpm);
    if (out.firstBeatMs < 0) {
        // 拟合失败才回退旧的能量阈值法
        const int hop = 8;                        // 8k → 1ms/frame 能量
        OnePole low; low.setup(8000.f, 200.f);
        std::vector<float> env;
        env.reserve(n8k / hop + 1);
        float acc = 0.f;
        for (int i = 0; i < n8k; i += hop) {
            int e = std::min(n8k, i + hop);
            for (int j = i; j < e; j++) { float v = low.process(samples8k[j]); acc += v * v; }
            env.push_back(std::sqrt(acc / std::max(1, e - i))); acc = 0.f;
        }
        if (env.size() > 16) {
            int lim = std::min((int)env.size(), 20000);
            float pk = 0.f;
            for (int i = 0; i < lim; i++) pk = std::max(pk, env[i]);
            if (pk > 1e-5f) {
                for (int i = 0; i < lim; i++) {
                    if (env[i] >= pk * 0.6f) { out.firstBeatMs = (int)((int64_t)i * hop * 1000 / 8000); break; }
                }
            }
        }
    }
    // 【P2-2】动态 beatgrid：分段 tempo（<2 段自动清空 → 全曲匀速）
    detectBeatGrid(samples8k, n8k, 8000, out.grid);
    return bestBpm;
}

} // namespace bpmdet
