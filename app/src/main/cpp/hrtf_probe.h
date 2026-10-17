#pragma once
#include <vector>
#include <cstddef>

// 【生产版·双耳渲染探针】Resonance 风格的分区 FFT 卷积（与 PartitionedFftFilter 同构）
// + OpenAL Soft getCoeffs 风格的方向→HRIR 逆距离加权插值，接 hrtf_data.h 中的 HRIR 数据集。
//
// 与旧朴素 FIR 版的区别：
//   * 频域分区重叠相加卷积（FFT size = 2*block），长 HRIR 也不爆算力；
//   * HRIR 来自数据集（hrtf_data.h），由 gen_hrtf_data.py 从真实测量 / 物理模型生成；
//   * 任意方向用最近邻逆距离加权（IDW）插值（getCoeffs 风格），5 点探针锁定 / 轮播复用同一通路。
//
// 接口与 oboe_bridge 完全一致：setSampleRate / setDirection / reset / process。
class HrtfProbe {
public:
    enum Dir : int { FRONT = 0, BACK = 1, LEFT = 2, RIGHT = 3, UP = 4, COUNT = 5 };

    HrtfProbe();
    void setSampleRate(float sr);
    void setDirection(int dir);  // -1 = 自动轮播；0..4 = 锁定方向
    void reset();
    void process(float inL, float inR, float& outL, float& outR);

private:
    static constexpr int FFT_SIZE = 256;       // 频域大小（2 的幂）
    static constexpr int BLOCK = FFT_SIZE / 2; // 分区 / 块长 = 128

    static void fft(float* re, float* im, int n, bool inverse);

    // 把 (azDeg, elDeg) 处的双耳 HRIR 取出 / 插值到 irL/irR（长度 = HRTF_IR_LEN，采样率 = HRTF_RATE）。
    void getMeasuredIR(float azDeg, float elDeg, std::vector<float>& irL, std::vector<float>& irR) const;

    // 用数据集 IR（HRTF_RATE 下、长度 irLen）配置卷积器（重采样到当前播放率并分区 FFT）。
    void setIRs(const std::vector<float>& irL, const std::vector<float>& irR, int irLen);

    // 处理一个完整输入块（BLOCK 样本），输出 BLOCK 样本到 yL/yR。
    void blockConvolve(const float* x, float* yL, float* yR);

    // 单耳：Z = Σ_p Yin[p] * H[p][ear]，IFFT，重叠相加写入 y / acc。
    void convolveEar(float* y, std::vector<float>& acc,
                     const std::vector<std::vector<float>>& Hre,
                     const std::vector<std::vector<float>>& Him);

    // 用方向 d 的数据集 IR 重新配置卷积器。
    void configureForDir(int d);

    int   sr_ = 48000;
    int   dir_ = FRONT;
    bool  autoCycle_ = true;
    long long sampleCount_ = 0;
    int   cycleIdx_ = FRONT;
    long long nextCycleSample_ = 0;

    // 分区卷积状态
    int numPartitions_ = 0;
    std::vector<float> re_, im_;                        // FFT 草稿
    std::vector<std::vector<float>> Yin_re_, Yin_im_;   // 输入延迟线频谱 [P][FFT_SIZE]
    std::vector<std::vector<float>> Hre_L_, Him_L_;     // 左耳滤波频谱 [P][FFT_SIZE]
    std::vector<std::vector<float>> Hre_R_, Him_R_;     // 右耳滤波频谱 [P][FFT_SIZE]
    std::vector<float> accL_, accR_;                   // 重叠相加累加器 [FFT_SIZE]
    std::vector<float> blk_;                           // 输入块缓冲 [BLOCK]
    int blkPos_ = 0;
    std::vector<float> outL_, outR_;                   // 输出队列 [BLOCK]
    int outRead_ = 0, outAvail_ = 0;
};
