// 【生产版·双耳环绕渲染】真实测量 HRIR 驱动的多虚拟音箱双耳 upmix（替换 DTS）。
// 实现见 hrtf_surround.h。HRIR 数据集来自 hrtf_data.h（gen_hrtf_data.py 生成）。
// 纯频域实现，无外部依赖、无异常/RTTI，可在项目既有链路下编译。
#include "hrtf_surround.h"
#include "hrtf_data.h"

#include <cmath>
#include <algorithm>

static constexpr float PI = 3.14159265358979323846f;

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
static const int kNumSpk = (int)(sizeof(kLayout) / sizeof(kLayout[0]));

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

HrtfSurround::HrtfSurround() {
    re_.resize(FFT_SIZE);
    im_.resize(FFT_SIZE);
    tmpL_.resize(BLOCK);
    tmpR_.resize(BLOCK);
    outL_.resize(BLOCK);
    outR_.resize(BLOCK);
    apX_L_.assign(AP_DL + 1, 0.0f); apY_L_.assign(AP_DL + 1, 0.0f);
    apX_R_.assign(AP_DR + 1, 0.0f); apY_R_.assign(AP_DR + 1, 0.0f);
    spk_.resize(kNumSpk);
    setSampleRate(48000.0f);
}

void HrtfSurround::setSampleRate(float sr) {
    sr_ = (sr > 0) ? (int)(sr + 0.5f) : 48000;
    configureAll();
}

void HrtfSurround::setFieldOrientation(float yawDeg, float pitchDeg) {
    yaw_ = yawDeg;
    pitch_ = pitchDeg;
    configureAll();
}

void HrtfSurround::reset() {
    configureAll();
    for (auto& s : spk_) {
        std::fill(s.accL.begin(), s.accL.end(), 0.0f);
        std::fill(s.accR.begin(), s.accR.end(), 0.0f);
        std::fill(s.blk.begin(), s.blk.end(), 0.0f);
    }
    blkPos_ = 0; outRead_ = 0; outAvail_ = 0;
    std::fill(apX_L_.begin(), apX_L_.end(), 0.0f);
    std::fill(apY_L_.begin(), apY_L_.end(), 0.0f);
    std::fill(apX_R_.begin(), apX_R_.end(), 0.0f);
    std::fill(apY_R_.begin(), apY_R_.end(), 0.0f);
    apIdxL_ = 0; apIdxR_ = 0;
}

// getCoeffs 风格：在数据集各测量点之间按角距离逆距离加权插值出 (az,el) 处的双耳 IR。
void HrtfSurround::getMeasuredIR(float azDeg, float elDeg, std::vector<float>& irL, std::vector<float>& irR) const {
    irL.assign(HRTF_IR_LEN, 0.0f);
    irR.assign(HRTF_IR_LEN, 0.0f);
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
            irL[k] += w * HRTF_IR_L[i][k];
            irR[k] += w * HRTF_IR_R[i][k];
        }
    }
    if (wsum > 0.0f) {
        for (int k = 0; k < HRTF_IR_LEN; ++k) { irL[k] /= wsum; irR[k] /= wsum; }
    }
}

void HrtfSurround::configureSpeaker(Speaker& s, float azDeg, float elDeg) {
    std::vector<float> irL, irR;
    getMeasuredIR(azDeg, elDeg, irL, irR);
    const int irLen = (int)irL.size();
    const int L = (int)(0.5f + (float)irLen * (float)sr_ / (float)HRTF_RATE);
    const int n = (L > 1) ? L : 1;
    std::vector<float> rL(n), rR(n);
    for (int j = 0; j < n; ++j) {                 // 线性重采样到播放率
        const float pos = (float)j * (float)HRTF_RATE / (float)sr_;
        const int i0 = (int)pos;
        const float frac = pos - (float)i0;
        const int i1 = i0 + 1;
        const float aL = (i0 >= 0 && i0 < irLen) ? irL[i0] : 0.0f;
        const float bL = (i1 >= 0 && i1 < irLen) ? irL[i1] : 0.0f;
        rL[j] = aL + (bL - aL) * frac;
        const float aR = (i0 >= 0 && i0 < irLen) ? irR[i0] : 0.0f;
        const float bR = (i1 >= 0 && i1 < irLen) ? irR[i1] : 0.0f;
        rR[j] = aR + (bR - aR) * frac;
    }

    int P = (n + BLOCK - 1) / BLOCK;
    if (P < 1) P = 1;
    s.numPartitions = P;
    s.Yin_re.assign(P, std::vector<float>(FFT_SIZE, 0.0f));
    s.Yin_im.assign(P, std::vector<float>(FFT_SIZE, 0.0f));
    s.Hre_L.assign(P, std::vector<float>(FFT_SIZE, 0.0f));
    s.Him_L.assign(P, std::vector<float>(FFT_SIZE, 0.0f));
    s.Hre_R.assign(P, std::vector<float>(FFT_SIZE, 0.0f));
    s.Him_R.assign(P, std::vector<float>(FFT_SIZE, 0.0f));
    s.accL.assign(FFT_SIZE, 0.0f);
    s.accR.assign(FFT_SIZE, 0.0f);
    s.blk.assign(BLOCK, 0.0f);

    for (int p = 0; p < P; ++p) {
        // 左耳
        std::fill(re_.begin(), re_.end(), 0.0f);
        std::fill(im_.begin(), im_.end(), 0.0f);
        for (int k = 0; k < BLOCK; ++k) {
            const int idx = p * BLOCK + k;
            if (idx < n) re_[k] = rL[idx];
        }
        fft(re_.data(), im_.data(), FFT_SIZE, false);
        s.Hre_L[p] = re_; s.Him_L[p] = im_;
        // 右耳
        std::fill(re_.begin(), re_.end(), 0.0f);
        std::fill(im_.begin(), im_.end(), 0.0f);
        for (int k = 0; k < BLOCK; ++k) {
            const int idx = p * BLOCK + k;
            if (idx < n) re_[k] = rR[idx];
        }
        fft(re_.data(), im_.data(), FFT_SIZE, false);
        s.Hre_R[p] = re_; s.Him_R[p] = im_;
    }
}

void HrtfSurround::configureAll() {
    for (int i = 0; i < kNumSpk; ++i) {
        float az = kLayout[i].az + yaw_;
        float el = kLayout[i].el + pitch_;
        if (el > 90.0f) el = 90.0f;
        if (el < -90.0f) el = -90.0f;
        while (az > 180.0f) az -= 360.0f;
        while (az <= -180.0f) az += 360.0f;
        spk_[i].gain = kLayout[i].gain;
        configureSpeaker(spk_[i], az, el);
    }
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

void HrtfSurround::blockConvolve(Speaker& s) {
    if (s.numPartitions <= 0) {
        std::fill(tmpL_.begin(), tmpL_.end(), 0.0f);
        std::fill(tmpR_.begin(), tmpR_.end(), 0.0f);
        return;
    }
    // 1) 输入块 FFT（零填充到 FFT_SIZE）
    std::fill(re_.begin(), re_.end(), 0.0f);
    std::fill(im_.begin(), im_.end(), 0.0f);
    for (int k = 0; k < BLOCK; ++k) re_[k] = s.blk[k];
    fft(re_.data(), im_.data(), FFT_SIZE, false);
    // 延迟线移位（频率域）
    for (int p = s.numPartitions - 1; p >= 1; --p) {
        s.Yin_re[p] = s.Yin_re[p - 1];
        s.Yin_im[p] = s.Yin_im[p - 1];
    }
    s.Yin_re[0] = re_;
    s.Yin_im[0] = im_;
    // 2) 每耳卷积 + 重叠相加
    convolveEar(tmpL_.data(), s.accL, s.Yin_re, s.Yin_im, s.Hre_L, s.Him_L);
    convolveEar(tmpR_.data(), s.accR, s.Yin_re, s.Yin_im, s.Hre_R, s.Him_R);
    // 3) 该音箱相对增益
    for (int k = 0; k < BLOCK; ++k) { tmpL_[k] *= s.gain; tmpR_[k] *= s.gain; }
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
        std::fill(outL_.begin(), outL_.end(), 0.0f);
        std::fill(outR_.begin(), outR_.end(), 0.0f);
        for (auto& s : spk_) {
            blockConvolve(s);
            for (int k = 0; k < BLOCK; ++k) { outL_[k] += tmpL_[k]; outR_[k] += tmpR_[k]; }
        }
        // 整体主增益：5 路求和后留余量给下游 limiter（过载由 limiter 收）
        const float master = 0.6f;
        for (int k = 0; k < BLOCK; ++k) { outL_[k] *= master; outR_[k] *= master; }
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
