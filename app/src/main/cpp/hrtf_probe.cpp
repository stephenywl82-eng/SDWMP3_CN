// 【生产版·双耳渲染探针】分区 FFT 卷积真双耳渲染。
// 实现见 hrtf_probe.h。HRIR 数据集来自 hrtf_data.h（gen_hrtf_data.py 生成）。
// 纯频域实现，无外部依赖、无异常/RTTI，可在项目既有 -fno-exceptions 链路下编译。
#include "hrtf_probe.h"
#include "hrtf_data.h"

#include <cmath>
#include <algorithm>

static constexpr float PI = 3.14159265358979323846f;

// 方向 -> (方位角 az, 仰角 el)，单位度。约定：az 0=正前、+90=右、-90=左、±180=后；el 0=水平、+90=正上。
static void dirToAng(int d, float& az, float& el) {
    switch (d) {
        case HrtfProbe::BACK:  az = 180.0f; el = 0.0f;   break;
        case HrtfProbe::LEFT:  az = -90.0f; el = 0.0f;   break;
        case HrtfProbe::RIGHT: az = 90.0f;  el = 0.0f;   break;
        case HrtfProbe::UP:    az = 0.0f;   el = 90.0f;  break;
        case HrtfProbe::FRONT:
        default:               az = 0.0f;   el = 0.0f;   break;
    }
}

// 原地迭代 radix-2 FFT（复数据用分离的 re/im 数组）。n 必须为 2 的幂。
void HrtfProbe::fft(float* re, float* im, int n, bool inverse) {
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

HrtfProbe::HrtfProbe() {
    re_.resize(FFT_SIZE);
    im_.resize(FFT_SIZE);
    blk_.resize(BLOCK);
    outL_.resize(BLOCK);
    outR_.resize(BLOCK);
    setSampleRate(48000.0f);
}

void HrtfProbe::setSampleRate(float sr) {
    sr_ = (sr > 0) ? (int)(sr + 0.5f) : 48000;
    configureForDir(dir_);  // 用当前方向重新配置（尊重已选方向）
}

void HrtfProbe::setDirection(int dir) {
    if (dir < 0) {
        autoCycle_ = true;
    } else {
        autoCycle_ = false;
        dir_ = (dir % COUNT + COUNT) % COUNT;
    }
    configureForDir(dir_);
}

void HrtfProbe::reset() {
    configureForDir(dir_);
}

void HrtfProbe::configureForDir(int d) {
    float az, el;
    dirToAng(d, az, el);
    std::vector<float> irL, irR;
    getMeasuredIR(az, el, irL, irR);
    setIRs(irL, irR, (int)irL.size());
}

// getCoeffs 风格：在数据集各测量点之间按角距离逆距离加权插值出 (az,el) 处的双耳 IR。
void HrtfProbe::getMeasuredIR(float azDeg, float elDeg, std::vector<float>& irL, std::vector<float>& irR) const {
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

// 把数据集 IR（HRTF_RATE 下、长度 irLen）重采样到当前播放率，分区并做 FFT 存入滤波频谱。
void HrtfProbe::setIRs(const std::vector<float>& irL, const std::vector<float>& irR, int irLen) {
    const int L = (int)(0.5f + (float)irLen * (float)sr_ / (float)HRTF_RATE);
    const int n = (L > 1) ? L : 1;
    std::vector<float> rL(n), rR(n);
    for (int j = 0; j < n; ++j) {
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

    numPartitions_ = (n + BLOCK - 1) / BLOCK;
    if (numPartitions_ < 1) numPartitions_ = 1;

    Yin_re_.assign(numPartitions_, std::vector<float>(FFT_SIZE, 0.0f));
    Yin_im_.assign(numPartitions_, std::vector<float>(FFT_SIZE, 0.0f));
    Hre_L_.assign(numPartitions_, std::vector<float>(FFT_SIZE, 0.0f));
    Him_L_.assign(numPartitions_, std::vector<float>(FFT_SIZE, 0.0f));
    Hre_R_.assign(numPartitions_, std::vector<float>(FFT_SIZE, 0.0f));
    Him_R_.assign(numPartitions_, std::vector<float>(FFT_SIZE, 0.0f));
    accL_.assign(FFT_SIZE, 0.0f);
    accR_.assign(FFT_SIZE, 0.0f);

    for (int p = 0; p < numPartitions_; ++p) {
        // 左耳
        std::fill(re_.begin(), re_.end(), 0.0f);
        std::fill(im_.begin(), im_.end(), 0.0f);
        for (int k = 0; k < BLOCK; ++k) {
            const int idx = p * BLOCK + k;
            if (idx < n) re_[k] = rL[idx];
        }
        fft(re_.data(), im_.data(), FFT_SIZE, false);
        Hre_L_[p] = re_; Him_L_[p] = im_;
        // 右耳
        std::fill(re_.begin(), re_.end(), 0.0f);
        std::fill(im_.begin(), im_.end(), 0.0f);
        for (int k = 0; k < BLOCK; ++k) {
            const int idx = p * BLOCK + k;
            if (idx < n) re_[k] = rR[idx];
        }
        fft(re_.data(), im_.data(), FFT_SIZE, false);
        Hre_R_[p] = re_; Him_R_[p] = im_;
    }

    blkPos_ = 0;
    outRead_ = 0;
    outAvail_ = 0;
}

void HrtfProbe::convolveEar(float* y, std::vector<float>& acc,
                            const std::vector<std::vector<float>>& Hre,
                            const std::vector<std::vector<float>>& Him) {
    std::fill(re_.begin(), re_.end(), 0.0f);
    std::fill(im_.begin(), im_.end(), 0.0f);
    for (int p = 0; p < numPartitions_; ++p) {
        const float* Yr = Yin_re_[p].data();
        const float* Yi = Yin_im_[p].data();
        const float* Hr = Hre[p].data();
        const float* Hi = Him[p].data();
        for (int k = 0; k < FFT_SIZE; ++k) {
            re_[k] += Yr[k] * Hr[k] - Yi[k] * Hi[k];
            im_[k] += Yr[k] * Hi[k] + Yi[k] * Hr[k];
        }
    }
    fft(re_.data(), im_.data(), FFT_SIZE, true);  // 逆变换 -> 时域块 [0..FFT_SIZE)
    for (int k = 0; k < BLOCK; ++k) y[k] = acc[k] + re_[k];          // 重叠相加取前 BLOCK
    for (int k = 0; k < FFT_SIZE - BLOCK; ++k) acc[k] = re_[k + BLOCK]; // 余下作为下一帧重叠
    for (int k = FFT_SIZE - BLOCK; k < FFT_SIZE; ++k) acc[k] = 0.0f;
}

void HrtfProbe::blockConvolve(const float* x, float* yL, float* yR) {
    if (numPartitions_ <= 0) {
        std::fill(yL, yL + BLOCK, 0.0f);
        std::fill(yR, yR + BLOCK, 0.0f);
        return;
    }
    // 1) 输入块 FFT（零填充到 FFT_SIZE）
    std::fill(re_.begin(), re_.end(), 0.0f);
    std::fill(im_.begin(), im_.end(), 0.0f);
    for (int k = 0; k < BLOCK; ++k) re_[k] = x[k];
    fft(re_.data(), im_.data(), FFT_SIZE, false);
    // 延迟线移位（频率域）
    for (int p = numPartitions_ - 1; p >= 1; --p) {
        Yin_re_[p] = Yin_re_[p - 1];
        Yin_im_[p] = Yin_im_[p - 1];
    }
    Yin_re_[0] = re_;
    Yin_im_[0] = im_;
    // 2) 每耳卷积 + 重叠相加
    convolveEar(yL, accL_, Hre_L_, Him_L_);
    convolveEar(yR, accR_, Hre_R_, Him_R_);
}

void HrtfProbe::process(float inL, float inR, float& outL, float& outR) {
    // 自动轮播：每 ~4.5s 推进一个方向
    if (autoCycle_ && sampleCount_ >= nextCycleSample_) {
        cycleIdx_ = (cycleIdx_ + 1) % COUNT;
        nextCycleSample_ = sampleCount_ + (long long)((float)sr_ * 4.5f);
        configureForDir(cycleIdx_);
    }
    if (autoCycle_) dir_ = cycleIdx_;

    // 单声求和后做双耳渲染（方位线索最明确）
    const float m = 0.5f * (inL + inR);

    // 入块缓冲
    blk_[blkPos_++] = m;
    if (blkPos_ >= BLOCK) {
        blockConvolve(blk_.data(), outL_.data(), outR_.data());
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
    ++sampleCount_;
}
