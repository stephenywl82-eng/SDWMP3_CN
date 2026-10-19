// 【生产版·双耳环绕渲染】真实测量 HRIR 驱动的多虚拟音箱双耳 upmix（替换 DTS）。
// 实现见 hrtf_surround.h。HRIR 数据集来自 hrtf_data.h（gen_hrtf_data.py 生成）。
// 纯频域实现，无外部依赖、无异常/RTTI，可在项目既有链路下编译。
#include "hrtf_surround.h"
#include "hrtf_data.h"

#include <cmath>
#include <algorithm>

static constexpr float PI = 3.14159265358979323846f;

// 距离 / 近场线索（V8.9xx）：给虚拟音箱环加"远近"维度。
// 参考距离 = HRIR 远场有效区（此距离以上只做能量衰减，不再加近场染色）；
// 最近距离 = 近场染色最强处。near 因子在二者间 0→1 线性插值，驱动 ILD 与低频提升。
static constexpr float DIST_REF       = 1.5f;   // 远场参考距离（米）
static constexpr float DIST_NEAR      = 0.3f;   // 最近距离（米），此处近场染色最强
// 距离增益动态范围进一步收窄（1.0/0.75，约 1.33x）：近场不再额外加响（仅保留 ILD 染色），
// 把省下的余量全部让给单路 L1 目标，使参考距离 5 路求和峰值逼近直通(~0.95)以彻底解决"偏轻"；
// 近场最坏(5 路对齐+单侧 ILD)峰值经核算≈1.05，落在 softClip 膝点(0.95)之上的"温和软限幅"区，不破音。
static constexpr float DIST_GAIN_MAX  = 1.0f;   // 距离衰减上限（近场不再额外加响，仅 ILD 染色；余量让给响度）
static constexpr float DIST_GAIN_MIN  = 0.75f;  // 距离衰减下限（远处不至于太轻）
static constexpr float NEAR_ILD       = 0.25f;  // 近场双边声压差强度（收窄防爆音，仍明显）
static constexpr float NEAR_BASS      = 0.15f;  // 近场低频（近讲）提升量（收窄以免近场峰值顶破 softClip）
// 参数平滑 / 重配置阈值（防拖动爆音、降 CPU）：朝向按块滑向目标，移动超阈值才重算 HRIR，
// 且重配时不再清零卷积状态（累加器/延迟线保留），彻底消除拖动时的 zipper 噪音。
static constexpr float PARAM_SMOOTH   = 0.20f;  // 每 128 样本朝目标滑进比例（时间常数 ~13ms）
static constexpr float GAIN_SMOOTH    = 0.25f;  // 增益 / 近场因子平滑比例
static constexpr float RECFG_YAW      = 1.0f;   // 偏航变化超此(度)才重配 HRIR（降重配频率，省 CPU）
static constexpr float RECFG_PITCH    = 1.0f;   // 俯仰变化超此(度)才重配 HRIR
static constexpr float RECFG_DIST     = 0.03f;  // 距离变化超此(米)才重配 HRIR
static constexpr float MASTER_GAIN    = 0.90f;  // 整体主增益。配合干/湿混合：干声(0.6)直接注入原始信号保 loudness/punch，湿声退为空间氛围层(L2_TARGET=0.55)，二者相加最坏峰值≈0.82(<softClip 膝点 0.95)留 ~2dB 余量不破音；整体 RMS 仍≈直通不轻。
static inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

// 虚拟 5.1 音箱布局（耳机双耳渲染）。方位约定与数据集一致：
// az 0=正前、+90=右、-90=左、±180=后；el 0=水平、+90=正上。
struct SpkLayout { float az, el, gain; };
static const SpkLayout kLayout[] = {
    { -30.0f, 0.0f, 0.95f },  // FL 前左
    {  30.0f, 0.0f, 0.95f },  // FR 前右
    {   0.0f, 0.0f, 0.60f },  // C  中置（补前场结像，不过推）
    { -110.0f, 0.0f, 0.55f }, // SL 侧左环绕
    { 110.0f, 0.0f, 0.55f },  // SR 侧右环绕
};
// kNumSpk 见 hrtf_surround.h（与 kLayout 项数一致，固定为 5）。

// 原地迭代 radix-2 FFT（复数据用分离的 re/im 数组）。n 必须为 2 的幂。
void HrtfSurround::fft(float* re, float* im, int n, bool inverse) {
    for (int i = 1, j = 0; i < n; ++i) {
        int bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) {
            std::swap(re[i], re[j]);
            std::swap(im[i], im[j]);
        }
    }
    for (int len = 2; len <= n; len <<= 1) {
        float ang = (inverse ? 2.0f : -2.0f) * PI / (float)len;
        float wr = std::cos(ang), wi = std::sin(ang);
        int half = len >> 1;
        for (int i = 0; i < n; i += len) {
            float cr = 1.0f, ci = 0.0f;
            for (int k = 0; k < half; ++k) {
                float ar = re[i + k + half], ai = im[i + k + half];
                float tr = cr * ar - ci * ai;
                float ti = cr * ai + ci * ar;
                re[i + k + half] = re[i + k] - tr;
                im[i + k + half] = im[i + k] - ti;
                re[i + k] = re[i + k] + tr;
                im[i + k] = im[i + k] + ti;
                float ncr = cr * wr - ci * wi;
                ci = cr * wi + ci * wr;
                cr = ncr;
            }
        }
    }
    if (inverse) {
        for (int i = 0; i < n; ++i) { re[i] /= (float)n; im[i] /= (float)n; }
    }
}

// Schroeder 全通：y[n] = -g*x[n] + x[n-D] + g*y[n-D]，用于环境声去相关。
static float allpass1(float x, std::vector<float>& xb, std::vector<float>& yb, int& idx, int D, float g) {
    const int N = (int)xb.size();
    int di = idx - D; if (di < 0) di += N;
    float y = -g * x + xb[di] + g * yb[di];
    xb[idx] = x; yb[idx] = y; idx = (idx + 1) % N;
    return y;
}

// 软限幅：5 路求和 + 增益提升后用来防削波。阈值处斜率=1，平滑收敛到 ±1，避免硬削波的咔哒声。
static inline float softClip(float x) {
    const float t = 0.95f;
    if (x > t)  return t + (1.0f - t) * std::tanh((x - t) / (1.0f - t));
    if (x < -t) return -t + (1.0f - t) * std::tanh((-x - t) / (1.0f - t));
    return x;
}

// 【V8.620】软膝限幅器（替代"内部 tanh 砸平"与"硬钳 ±3"两个极端）：
// 干+湿混合后峰值全面超 1（离线实测 bass60 1.22 / high8k 1.12 / 白噪 1.90 / 近场 2.06），
// 而 RMS 仅 ~0.48 —— 是波峰因数问题，不是电平问题。此前三版都不对：
//   ① 内部 tanh softClip(膝点0.95)：把 1.2~2.0 峰值提前砸平 → flat-top 破音；
//   ② 完全交给下游 look-ahead 限幅器：要压 ~6.4dB，60Hz 周期 16.7ms >> 5ms 前瞻窗，
//      低频段压不住 → 用户实测"还是破了一点"；
//   ③ 软限天花板 0.98：峰值仍略高于下游天花板 0.95，下游还得持续压 0.26dB，
//      仍要赌下游 gain envelope 无 overshoot。
// 本函数在源头把波峰平滑收进天花板：阈值 T 以下增益恒=1（RMS/响度完全不受影响，
// 音乐正常动态以内原样通过）；超过 T 后增益按 tanh 平滑下降，且在 T 处一阶连续
// （斜率无缝衔接，无拐角、无 flat-top 突变）；最终收敛到 C。C 取 0.90 是刻意低于末端
// TerminalLimiterNode 内部 softClip 拐点（宽裕档 0.92 / 传统档 0.667）与下游天花板，
// 使末端各级结构上零动作。T=0.65（V8.640 调）：方位角扫描实测非 0 度时 ITD/ILD 使
// 湿声偏侧集中，进入压缩区的样本量翻倍（白噪 ±90° 达 10-11% vs 0° 4%），T 降到 0.65
// 让膝区更宽、单样本压缩量更小，听感从"压硬"回到"受控的响"。
static inline float softLimit(float x) {
    const float T = 0.65f;   // 阈值：以下完全线性，保响度
    const float C = 0.90f;   // 天花板：峰值收敛到此（刻意低于末端 softClip 拐点 0.92）
    const float a = std::fabs(x);
    if (a <= T) return x;
    const float room = C - T;
    const float y = T + room * std::tanh((a - T) / room);
    return x >= 0.0f ? y : -y;
}

void HrtfReverb::init(int sampleRate) {
    sr = sampleRate > 0 ? sampleRate : 48000;
    preLen = (int)(0.05f * (float)sr) + 4;
    predelayLen = (int)(0.018f * (float)sr);
    if (predelayLen < 1) predelayLen = 1;
    pre.assign(preLen, 0.0f); preIdx = 0;

    // 早期反射抽头（秒, 增益）：几个不整除的延时 -> 自然房间早期回声
    const float tdef[][2] = {
        {0.021f,0.33f},{0.031f,0.26f},{0.044f,0.20f},
        {0.059f,0.15f},{0.073f,0.11f},{0.091f,0.08f}
    };
    const int nt = (int)(sizeof(tdef)/sizeof(tdef[0]));
    taps.resize(nt);
    int maxTap = 0;
    for (int i = 0; i < nt; ++i) {
        taps[i].d = (int)(tdef[i][0] * (float)sr);
        taps[i].g = tdef[i][1];
        if (taps[i].d > maxTap) maxTap = taps[i].d;
    }
    early.assign(maxTap + 2, 0.0f); earlyIdx = 0;

    // 4 个梳状滤波器（并行）-> 扩散尾音
    const float cdef[] = {0.0297f,0.0371f,0.0411f,0.0437f};
    const int nc = (int)(sizeof(cdef)/sizeof(cdef[0]));
    comb.resize(nc); combIdx.assign(nc, 0); combFb.assign(nc, 0.82f);
    for (int i = 0; i < nc; ++i) {
        int L = (int)(cdef[i] * (float)sr); if (L < 2) L = 2;
        comb[i].assign(L, 0.0f);
    }

    // 2 个全通（串联）-> 进一步扩散，去"金属感"
    const float adef[] = {0.0098f,0.0123f};
    const int na = (int)(sizeof(adef)/sizeof(adef[0]));
    ap.resize(na); apIdx.assign(na, 0); apG.assign(na, 0.70f);
    for (int i = 0; i < na; ++i) {
        int L = (int)(adef[i] * (float)sr); if (L < 2) L = 2;
        ap[i].assign(L, 0.0f);
    }
}

void HrtfReverb::reset() {
    std::fill(pre.begin(), pre.end(), 0.0f); preIdx = 0;
    std::fill(early.begin(), early.end(), 0.0f); earlyIdx = 0;
    for (auto& c : comb) std::fill(c.begin(), c.end(), 0.0f);
    std::fill(combIdx.begin(), combIdx.end(), 0);
    for (auto& a : ap) std::fill(a.begin(), a.end(), 0.0f);
    std::fill(apIdx.begin(), apIdx.end(), 0);
}

float HrtfReverb::process(float x, float wet) {
    if (pre.empty() || early.empty()) return x;   // 未初始化保护
    // 预延迟
    pre[preIdx] = x;
    preIdx = (preIdx + 1) % (int)pre.size();
    int rp = preIdx - predelayLen; if (rp < 0) rp += (int)pre.size();
    const float xp = pre[rp];

    // 早期反射（FIR 抽头）
    early[earlyIdx] = xp;
    const int ei = earlyIdx;
    earlyIdx = (earlyIdx + 1) % (int)early.size();
    float er = 0.0f;
    for (size_t t = 0; t < taps.size(); ++t) {
        int r = ei - taps[t].d; if (r < 0) r += (int)early.size();
        er += early[r] * taps[t].g;
    }

    // 4 梳状（并行）-> 求和
    float cs = 0.0f;
    for (size_t c = 0; c < comb.size(); ++c) {
        const float y = comb[c][combIdx[c]];
        comb[c][combIdx[c]] = er + combFb[c] * y;
        combIdx[c] = (combIdx[c] + 1) % (int)comb[c].size();
        cs += y;
    }
    cs /= (float)comb.size();

    // 2 全通（串联）
    float a = cs;
    for (size_t p = 0; p < ap.size(); ++p) {
        const float y = ap[p][apIdx[p]];
        ap[p][apIdx[p]] = a + apG[p] * y;
        a = y - apG[p] * a;
        apIdx[p] = (apIdx[p] + 1) % (int)ap[p].size();
    }

    const float wetOut = a + er * 0.5f;   // 加一点离散早期反射，更像真实房间
    return x * (1.0f - wet) + wetOut * wet;
}

HrtfSurround::HrtfSurround() {
    re_.resize(FFT_SIZE);
    im_.resize(FFT_SIZE);
    reC_.resize(FFT_SIZE);
    imC_.resize(FFT_SIZE);
    tmpL_.resize(BLOCK);
    tmpR_.resize(BLOCK);
    outL_.resize(BLOCK);
    outR_.resize(BLOCK);
    mixL_.resize(BLOCK);
    mixR_.resize(BLOCK);
    apX_L_.assign(AP_DL + 1, 0.0f); apY_L_.assign(AP_DL + 1, 0.0f);
    apX_R_.assign(AP_DR + 1, 0.0f); apY_R_.assign(AP_DR + 1, 0.0f);
    irL_.assign(HRTF_IR_LEN, 0.0f); irR_.assign(HRTF_IR_LEN, 0.0f);  // HRIR 复用缓冲，仅此一次分配
    for (auto& s : spk_) {
        s.Yin_re.assign(MAX_PART, std::vector<float>(FFT_SIZE, 0.0f));
        s.Yin_im.assign(MAX_PART, std::vector<float>(FFT_SIZE, 0.0f));
        s.accL.assign(FFT_SIZE, 0.0f);
        s.accR.assign(FFT_SIZE, 0.0f);
        s.blk.assign(BLOCK, 0.0f);
        s.hActive_.store(nullptr);
        s.hPrev_ = nullptr;
    }
    setSampleRate(48000.0f);
}

void HrtfSurround::setSampleRate(float sr) {
    sr_ = (sr > 0) ? (int)(sr + 0.5f) : 48000;
    reverbL_.init(sr_);
    reverbR_.init(sr_);
    configureAll();
}

void HrtfSurround::setFieldOrientation(float yawDeg, float pitchDeg) {
    yawTarget_ = yawDeg;
    pitchTarget_ = pitchDeg;
    // 重算 HRIR 搬到「非音频线程」（此处跑在 UI/绑定线程），按阈值节流，
    // 绝不在音频回调里做插值/FFT，从根上消除拖动时的欠载爆音。
    if (std::fabs(yawTarget_ - cfgYaw_) > RECFG_YAW ||
        std::fabs(pitchTarget_ - cfgPitch_) > RECFG_PITCH) {
        configureAll();
        cfgYaw_ = yawTarget_; cfgPitch_ = pitchTarget_; cfgDist_ = distTarget_;
    }
}

void HrtfSurround::setReverbEnabled(bool on) {
    reverbEnabled_ = on;
}

void HrtfSurround::setFieldDistance(float d) {
    distTarget_ = clampf(d, 0.1f, 20.0f);
    if (std::fabs(distTarget_ - cfgDist_) > RECFG_DIST) {
        configureAll();
        cfgYaw_ = yawTarget_; cfgPitch_ = pitchTarget_; cfgDist_ = distTarget_;
    }
}

void HrtfSurround::reset() {
    configureAll();
    for (auto& s : spk_) {
        std::fill(s.accL.begin(), s.accL.end(), 0.0f);
        std::fill(s.accR.begin(), s.accR.end(), 0.0f);
        std::fill(s.blk.begin(), s.blk.end(), 0.0f);
        for (auto& y : s.Yin_re) std::fill(y.begin(), y.end(), 0.0f);
        for (auto& y : s.Yin_im) std::fill(y.begin(), y.end(), 0.0f);
        HrSnapshot* h = s.hActive_.load(std::memory_order_acquire);
        const float dgL = h ? h->distGainL : 1.0f;
        const float dgR = h ? h->distGainR : 1.0f;
        const float nr  = h ? h->near : 0.0f;
        s.curGL = s.gain * dgL;
        s.curGR = s.gain * dgR;
        s.curNear = nr;
        s.lpL = 0.0f; s.lpR = 0.0f;
    }
    blkPos_ = 0; outRead_ = 0; outAvail_ = 0;
    std::fill(apX_L_.begin(), apX_L_.end(), 0.0f);
    std::fill(apY_L_.begin(), apY_L_.end(), 0.0f);
    std::fill(apX_R_.begin(), apX_R_.end(), 0.0f);
    std::fill(apY_R_.begin(), apY_R_.end(), 0.0f);
    apIdxL_ = 0; apIdxR_ = 0;
    reverbL_.reset();
    reverbR_.reset();
}

// getCoeffs 风格：在数据集各测量点之间按角距离逆距离加权插值出 (az,el) 处的双耳 IR。
// 结果写入复用缓冲 irL_/irR_，避免在音频线程（拖动重配时）分配内存导致欠载爆音。
void HrtfSurround::getMeasuredIR(float azDeg, float elDeg) {
    std::fill(irL_.begin(), irL_.end(), 0.0f);
    std::fill(irR_.begin(), irR_.end(), 0.0f);
    const float az = azDeg * PI / 180.0f, el = elDeg * PI / 180.0f;
    const float tx = std::cos(el) * std::cos(az);  // 目标方向单位向量 (前, 右, 上)
    const float ty = std::cos(el) * std::sin(az);
    const float tz = std::sin(el);
    float wsum = 0.0f;
    for (int i = 0; i < HRTF_NUM_PTS; ++i) {
        const float a = HRTF_AZ[i] * PI / 180.0f, e = HRTF_EL[i] * PI / 180.0f;
        const float vx = std::cos(e) * std::cos(a);
        const float vy = std::cos(e) * std::sin(a);
        const float vz = std::sin(e);
        float dot = tx * vx + ty * vy + tz * vz;
        if (dot > 1.0f) dot = 1.0f;
        if (dot < -1.0f) dot = -1.0f;
        const float d = std::acos(dot);          // 角距离
        const float w = 1.0f / (1e-4f + d * d); // 逆距离平方加权
        wsum += w;
        for (int k = 0; k < HRTF_IR_LEN; ++k) {
            irL_[k] += w * HRTF_IR_L[i][k];
            irR_[k] += w * HRTF_IR_R[i][k];
        }
    }
    if (wsum > 0.0f) {
        for (int k = 0; k < HRTF_IR_LEN; ++k) { irL_[k] /= wsum; irR_[k] /= wsum; }
    }
}

void HrtfSurround::configureSpeaker(Speaker& s, float azDeg, float elDeg, HrSnapshot* snap) {
    getMeasuredIR(azDeg, elDeg);                  // 结果写入复用缓冲 irL_/irR_（仅重算线程访问）
    const int irLen = (int)irL_.size();
    const int L = (int)(0.5f + (float)irLen * (float)sr_ / (float)HRTF_RATE);
    const int n = (L > 1) ? L : 1;
    rL_.resize(n); rR_.resize(n);                 // 复用缓冲（n 仅随采样率变，常态不分配）
    for (int j = 0; j < n; ++j) {                 // 线性重采样到播放率
        const float pos = (float)j * (float)HRTF_RATE / (float)sr_;
        const int i0 = (int)pos;
        const float frac = pos - (float)i0;
        const int i1 = i0 + 1;
        const float aL = (i0 >= 0 && i0 < irLen) ? irL_[i0] : 0.0f;
        const float bL = (i1 >= 0 && i1 < irLen) ? irL_[i1] : 0.0f;
        rL_[j] = aL + (bL - aL) * frac;
        const float aR = (i0 >= 0 && i0 < irLen) ? irR_[i0] : 0.0f;
        const float bR = (i1 >= 0 && i1 < irLen) ? irR_[i1] : 0.0f;
        rR_[j] = aR + (bR - aR) * frac;
    }

    int P = (n + BLOCK - 1) / BLOCK;
    if (P < 1) P = 1;
    if (P > MAX_PART) P = MAX_PART;
    // 距离衰减（能量）：近处更响、远处更轻；near 因子驱动近场 ILD / 低频提升。
    // 这些派生量随距离变化，一并写进快照，音频线程只读快照即可（无需实时计算）。
    const float distGain = clampf(DIST_REF / std::max(distTarget_, 0.1f), DIST_GAIN_MIN, DIST_GAIN_MAX);
    const float near = clampf((DIST_REF - distTarget_) / (DIST_REF - DIST_NEAR), 0.0f, 1.0f);
    const float sn = std::sin(azDeg * PI / 180.0f);   // 声源所在侧耳朵近（az∈[-90,90] 决定哪侧近）
    snap->numPartitions = P;
    snap->near = near;
    snap->distGainL = distGain * (1.0f + near * NEAR_ILD * std::max(0.0f, -sn));
    snap->distGainR = distGain * (1.0f + near * NEAR_ILD * std::max(0.0f,  sn));
    // 滤波谱写入快照（重算线程持有，音频线程只原子读，故此处分配安全）。
    snap->Hre_L.assign(P, std::vector<float>(FFT_SIZE, 0.0f));
    snap->Him_L.assign(P, std::vector<float>(FFT_SIZE, 0.0f));
    snap->Hre_R.assign(P, std::vector<float>(FFT_SIZE, 0.0f));
    snap->Him_R.assign(P, std::vector<float>(FFT_SIZE, 0.0f));
    // 按 L1 范数(sum|h|)归一：FIR 卷积输出峰值上界 = L1(h)·peak(x)，这才是决定是否会
    // 削波的量。峰值归一(L∞)约束不住长 FIR 的卷积幅度（L1 >> 峰值），能量归一(L2=1)则把
    // L1 顶到很大；二者都会使 5 路求和 + 近场增益后远超 softClip 阈值(0.95)，被 tanh 饱和成
    // 平顶方波 => 破音。故统一按 L1 归一到固定目标，使最坏情况（5 路对齐 + 近场 + 低频提升）
    // 的求和峰值≈0.95，刚好贴着膝点而不破。
    // 按 L2 范数(能量)归一：FIR 卷积对宽带信号(白噪/音乐)的输出 RMS ≈ ||h||_2 · RMS(x)，
    // 故每耳 IR 归一到固定 L2 目标 → 5 路求和响度可控。
    // 【V8.600 干湿混合后回调】此前纯湿声为对抗"轻"把 L2_TARGET 提到 0.90（5 路 RMS≈1.35/1.47，比直通
    // 响 ~+3dB）。但改为干/湿混合后，0.6 的干声叠加在已经很火的湿声上，相加峰值轻松越过 softClip 膝点
    // (0.95) → 被 tanh 压平 → 用户实测"开 HRIR 破音"。干/湿混合里 loudness/punch 由干声(原始 L/R)承载，
    // 湿声只需提供空间包围，故把 L2_TARGET 从 0.90 回调到 0.55：湿声退为"氛围层"，
    // 0.6*干 + 0.9*湿 相加最坏峰值≈0.82(<0.95)，留 ~2dB 余量不触顶；整体 RMS 仍≈直通，不轻。
    // 若实测偏轻可回调到 0.65（相加最坏峰值≈0.87，仍在膝点下、安全）。
    float l2L = 0.0f, l2R = 0.0f;
    for (int j = 0; j < n; ++j) { l2L += rL_[j] * rL_[j]; l2R += rR_[j] * rR_[j]; }
    const float L2_TARGET = 0.55f;
    if (l2L > 1e-9f) { const float sL = L2_TARGET / std::sqrt(l2L); for (int j = 0; j < n; ++j) rL_[j] *= sL; }
    if (l2R > 1e-9f) { const float sR = L2_TARGET / std::sqrt(l2R); for (int j = 0; j < n; ++j) rR_[j] *= sR; }
    for (int p = 0; p < P; ++p) {
        // 左耳（用重算专属草稿 reC_/imC_，与音频线程 re_/im_ 隔离）
        std::fill(reC_.begin(), reC_.end(), 0.0f);
        std::fill(imC_.begin(), imC_.end(), 0.0f);
        for (int k = 0; k < BLOCK; ++k) {
            const int idx = p * BLOCK + k;
            if (idx < n) reC_[k] = rL_[idx];
        }
        fft(reC_.data(), imC_.data(), FFT_SIZE, false);
        std::copy(reC_.begin(), reC_.end(), snap->Hre_L[p].begin());
        std::copy(imC_.begin(), imC_.end(), snap->Him_L[p].begin());
        // 右耳
        std::fill(reC_.begin(), reC_.end(), 0.0f);
        std::fill(imC_.begin(), imC_.end(), 0.0f);
        for (int k = 0; k < BLOCK; ++k) {
            const int idx = p * BLOCK + k;
            if (idx < n) reC_[k] = rR_[idx];
        }
        fft(reC_.data(), imC_.data(), FFT_SIZE, false);
        std::copy(reC_.begin(), reC_.end(), snap->Hre_R[p].begin());
        std::copy(imC_.begin(), imC_.end(), snap->Him_R[p].begin());
    }
}

void HrtfSurround::configureAll() {
    // 重算仅在「非音频线程」触发（setFieldOrientation/setFieldDistance/构造/采样率切换/reset）。
    // 全程加锁仅重算线程会进，音频线程只做原子读，绝不阻塞。
    std::lock_guard<std::mutex> lk(cfgMutex_);
    // 【V8.660】整体构建新快照集，全部写完再单指针原子发布：音频线程要么拿到全新集合、
    // 要么拿到全旧集合，绝不会出现"前几个新、后几个旧"的混搭。旧代码逐个 speaker
    // exchange hActive_，而音频线程一个 block 内也是逐个 load——重算循环走到一半时
    // 音频线程读到新旧混搭，5 路虚拟音箱声场瞬间错位，每拖动 1 度(RECFG_YAW)就"啪"一声
    // （用户实测"调哪里都破、唯独 0 度不破"，因为 0 度是初始位置从不触发重配）。
    HrSnapshotSet* ns = new HrSnapshotSet();
    for (int i = 0; i < kNumSpk; ++i) {
        float az = kLayout[i].az + yawTarget_;
        float el = kLayout[i].el + pitchTarget_;
        if (el > 90.0f) el = 90.0f;
        if (el < -90.0f) el = -90.0f;
        while (az > 180.0f) az -= 360.0f;
        while (az <= -180.0f) az += 360.0f;
        spk_[i].gain = kLayout[i].gain;
        configureSpeaker(spk_[i], az, el, &ns->snaps[i]);  // 写入新集合
    }
    HrSnapshotSet* old = hSet_.exchange(ns, std::memory_order_release);  // 原子发布整组
    HrSnapshotSet* del = hSetPrev_;         // 延迟一拍释放，避免与音频线程读竞争
    hSetPrev_ = old;
    if (del) delete del;
}

void HrtfSurround::convolveEar(float* y, std::vector<float>& acc,
                               const std::vector<std::vector<float>>& Yin_re,
                               const std::vector<std::vector<float>>& Yin_im,
                               const std::vector<std::vector<float>>& Hre,
                               const std::vector<std::vector<float>>& Him) {
    const int P = (int)Hre.size();
    std::fill(re_.begin(), re_.end(), 0.0f);
    std::fill(im_.begin(), im_.end(), 0.0f);
    for (int p = 0; p < P; ++p) {
        const float* Yr = Yin_re[p].data();
        const float* Yi = Yin_im[p].data();
        const float* Hr = Hre[p].data();
        const float* Hi = Him[p].data();
        for (int k = 0; k < FFT_SIZE; ++k) {
            re_[k] += Yr[k] * Hr[k] - Yi[k] * Hi[k];
            im_[k] += Yr[k] * Hi[k] + Yi[k] * Hr[k];
        }
    }
    fft(re_.data(), im_.data(), FFT_SIZE, true);  // 逆变换 -> 时域块 [0..FFT_SIZE)
    for (int k = 0; k < BLOCK; ++k) y[k] = acc[k] + re_[k];            // 重叠相加取前 BLOCK
    for (int k = 0; k < FFT_SIZE - BLOCK; ++k) acc[k] = re_[k + BLOCK]; // 余下作为下一帧重叠
    for (int k = FFT_SIZE - BLOCK; k < FFT_SIZE; ++k) acc[k] = 0.0f;
}

void HrtfSurround::blockConvolve(Speaker& s, HrSnapshot* h) {
    if (!h || h->numPartitions <= 0) {
        std::fill(tmpL_.begin(), tmpL_.end(), 0.0f);
        std::fill(tmpR_.begin(), tmpR_.end(), 0.0f);
        return;
    }
    const int P = h->numPartitions;
    // 1) 输入块 FFT（零填充到 FFT_SIZE）
    std::fill(re_.begin(), re_.end(), 0.0f);
    std::fill(im_.begin(), im_.end(), 0.0f);
    for (int k = 0; k < BLOCK; ++k) re_[k] = s.blk[k];
    fft(re_.data(), im_.data(), FFT_SIZE, false);
    // 延迟线移位（频率域）；Yin 预分配 MAX_PART 个分区，P 必 <= MAX_PART
    for (int p = P - 1; p >= 1; --p) {
        s.Yin_re[p] = s.Yin_re[p - 1];
        s.Yin_im[p] = s.Yin_im[p - 1];
    }
    s.Yin_re[0] = re_;
    s.Yin_im[0] = im_;
    // 2) 每耳卷积 + 重叠相加（H 取自当前生效快照，音频线程只原子读）
    convolveEar(tmpL_.data(), s.accL, s.Yin_re, s.Yin_im, h->Hre_L, h->Him_L);
    convolveEar(tmpR_.data(), s.accR, s.Yin_re, s.Yin_im, h->Hre_R, h->Him_R);
    // 3) 该音箱增益：基础相对增益 × 距离衰减/近场 ILD；近场再叠加低频(近讲)提升。
    //    目标增益做一阶平滑（GAIN_SMOOTH），避免拖动时增益阶跃产生 zipper 噪音。
    const float tgtL = s.gain * h->distGainL;
    const float tgtR = s.gain * h->distGainR;
    s.curGL += (tgtL - s.curGL) * GAIN_SMOOTH;
    s.curGR += (tgtR - s.curGR) * GAIN_SMOOTH;
    s.curNear += (h->near - s.curNear) * GAIN_SMOOTH;
    const float gL = s.curGL;
    const float gR = s.curGR;
    if (s.curNear > 0.001f) {
        const float a = 0.10f;                 // 一阶低通系数（~数百 Hz 截止）
        const float boost = NEAR_BASS * s.curNear;
        for (int k = 0; k < BLOCK; ++k) {
            s.lpL += a * (tmpL_[k] - s.lpL); tmpL_[k] = tmpL_[k] * gL + boost * s.lpL;
            s.lpR += a * (tmpR_[k] - s.lpR); tmpR_[k] = tmpR_[k] * gR + boost * s.lpR;
        }
    } else {
        for (int k = 0; k < BLOCK; ++k) { tmpL_[k] *= gL; tmpR_[k] *= gR; }
    }
}

void HrtfSurround::process(float inL, float inR, float& outL, float& outR) {
    const float mono = 0.5f * (inL + inR);
    // 环境声去相关（两路不同延时全通，互不相干）
    const float ambL = allpass1(mono, apX_L_, apY_L_, apIdxL_, AP_DL, AP_G);
    const float ambR = allpass1(mono, apX_R_, apY_R_, apIdxR_, AP_DR, AP_G);

    // 源信号分配（保留立体声场）
    spk_[0].blk[blkPos_] = inL;     // FL <- L
    spk_[1].blk[blkPos_] = inR;     // FR <- R
    spk_[2].blk[blkPos_] = mono;    // C  <- mono（中置）
    spk_[3].blk[blkPos_] = ambL;    // SL <- 去相关环境
    spk_[4].blk[blkPos_] = ambR;    // SR <- 去相关环境

    ++blkPos_;
    if (blkPos_ >= BLOCK) {
        // 每 128 样本（~2.7ms）只做卷积 + 增益平滑：零分配、零 FFT、零重算，
        // HRIR 重算已搬到非音频线程（setFieldOrientation/setFieldDistance 触发），此处只读生效快照。
        std::fill(outL_.begin(), outL_.end(), 0.0f);
        std::fill(outR_.begin(), outR_.end(), 0.0f);
        // 【V8.660】一次 load 拿到 5 路同代的一致快照集合，杜绝新旧混搭爆音
        HrSnapshotSet* set = hSet_.load(std::memory_order_acquire);
        if (set) {
            for (int i = 0; i < kNumSpk; ++i) {
                blockConvolve(spk_[i], &set->snaps[i]);
                for (int k = 0; k < BLOCK; ++k) { outL_[k] += tmpL_[k]; outR_[k] += tmpR_[k]; }
            }
        }
        // 房间混响（可选）：在 HRIR 求和之后给 L/R 加早期反射+扩散尾音，去除"干"感
        if (reverbEnabled_) {
            for (int k = 0; k < BLOCK; ++k) {
                outL_[k] = reverbL_.process(outL_[k], reverbWet_);
                outR_[k] = reverbR_.process(outR_[k], reverbWet_);
            }
        }
        // 整体主增益 + 干声保底混合：
        // HRIR 把原信号拆成 FL/FR/C/SL/SR 五音箱场再求和，若只留湿声，中置/人声会被"摊薄"
        // 成单薄感（听感"轻 / 压得低"）；而单纯再抬 L2_TARGET 只是整体加电平、仍补不回声场主体厚度。
        // 这里保留一份原始 L/R 干声（dryMix）与 HRIR 空间化湿声（wetMix）叠加 —— 既保住直达声的
        // 厚度与 punch，又让环绕/反射添上空间包围。这是 Dolby Atmos / 各类虚拟环绕的标准做法：
        // 直达声绝不被卷积吃掉，故"开 HRIR"不会再比直通轻。
        // 【V8.600 防破音】湿声 L2_TARGET 已从 0.90 回调到 0.55（否则干+湿相加越过 softClip 膝点破音）。
        // loudness/punch 由干声承载，湿声退为氛围层。
        const float master = MASTER_GAIN;
        const float dryMix = 0.7f;   // 干声（原始 L/R）保底比例：撑住声场主体厚度与响度
        const float wetMix = 0.75f;  // 湿声（HRIR 空间化）比例：提供包围感/方位
        // 【V8.640】wetMix 0.9→0.75：方位角扫描实测非 0° 时 ITD/ILD 使 5 路湿声偏侧集中，
        // 峰值堆积严重（±90° 进压缩区样本翻倍）。湿声降 0.15 直接削峰值堆积，干声补 0.1
        // 保响度；ILD/ITD 是相对量，整体降湿声不破坏定位。
        // 第一趟：干湿混合（含 master），写入 mixL_/mixR_ 并量本块 RMS
        float sumSq = 0.f;
        for (int k = 0; k < BLOCK; ++k) {
            const float dL = spk_[0].blk[k];  // FL 输入块即本帧原始 L
            const float dR = spk_[1].blk[k];  // FR 输入块即本帧原始 R
            const float yL = (dryMix * dL + wetMix * outL_[k]) * master;
            const float yR = (dryMix * dR + wetMix * outR_[k]) * master;
            mixL_[k] = yL;
            mixR_[k] = yR;
            sumSq += yL * yL + yR * yR;
        }
        // 【V8.670】自适应电平：把混合后 RMS 慢速归一到 targetRms。输入越热（音量推满 /
        // EQ boost）降得越多，非 0° ITD/ILD 峰值堆积也被同一机制收编——峰值结构性远离
        // 软限区，从根上消除"压缩发硬/破"（实测非0度+热输入 20-33% 样本进压缩区，
        // 纯调 softLimit 阈值只是重新分配压缩量）。平滑系数 0.03 ≈ 100ms 时间常数，
        // 只跟慢速电平、不伤瞬态；clamp 0.4~1.6 防极端。
        // 【V8.680 用户选"更激进的干净"】targetRms 0.32→0.26：HRIR 卷积固有波峰因数
        // cf≈4（长 FIR L2 归一但 L1 大，单声源模式实测 cf 3.5-4.3），peak≈4×RMS——
        // 0.32 时 peak≈1.28 仍有 3-5% 样本触发压缩；0.26 时 peak≈1.04，触发趋近零。
        // 代价：整体再降 ~2dB（用户已知情并选择）。
        {
            const float rms = std::sqrt(sumSq / (float)(2 * BLOCK));
            const float targetRms = 0.26f;
            float g = targetRms / (rms > 1e-5f ? rms : 1e-5f);
            if (g < 0.4f) g = 0.4f;
            if (g > 1.6f) g = 1.6f;
            adaptGain_ += (g - adaptGain_) * 0.03f;
        }
        // 第二趟：自适应增益 + 源头软膝限幅
        // 【V8.610 破音根因修复】离线实测（NDK arm64，去限幅副本）：干+湿混合的峰值全面
        // 超膝点——bass60 1.22 / high8k 1.12 / 白噪 1.90 / 近场白噪 2.06（宽带最坏 ~2.0），
        // 但 RMS 仅 ~0.48（波峰因数高）。关键教训：这既不是"内部 tanh 砸平"（→flat-top
        // 破音），也不是"完全甩给下游"（→6.4dB 全压给 5ms look-ahead，低频跟不上漏破音）。
        // 【V8.620】改用源头软膝限幅 softLimit：阈值以下增益=1 保响度，超阈值平滑收敛。
        for (int k = 0; k < BLOCK; ++k) {
            const float oL = softLimit(mixL_[k] * adaptGain_);
            const float oR = softLimit(mixR_[k] * adaptGain_);
            outL_[k] = oL;
            outR_[k] = oR;
        }
        blkPos_ = 0;
        outRead_ = 0;
        outAvail_ = BLOCK;
    }

    if (outAvail_ > 0) {
        outL = outL_[outRead_];
        outR = outR_[outRead_];
        ++outRead_;
        --outAvail_;
    } else {
        outL = 0.0f;
        outR = 0.0f;
    }
}
