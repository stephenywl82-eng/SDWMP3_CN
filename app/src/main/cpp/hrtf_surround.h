#pragma once
#include <vector>
#include <cstddef>

// 【生产版·双耳环绕渲染】真实测量 HRIR 数据集驱动的多虚拟音箱双耳 upmix，
// 用来替换 DTS 头戴环绕。
//
// 做法：把虚拟 5.1 音箱环（FL/FR/C/SL/SR）每个音箱各用其方位的 HRIR 做一套
// 分区 FFT 卷积（与 HrtfProbe / Resonance PartitionedFftFilter 同构），再把各音箱的
// 双耳输出求和得到最终 outL/outR。源信号分配保留立体声场：
//   FL<-L  FR<-R  C<-mono（中置补前场）  SL/SR<-去相关环境声（制造包围感），
// 不会像单点探针那样把信号压成 mono。
//
// 声场朝向 (yaw,pitch) 由拖动手柄控制：旋转/俯仰整圈虚拟音箱，实现"拖动声场"。
class HrtfSurround {
public:
    HrtfSurround();
    void setSampleRate(float sr);
    void setFieldOrientation(float yawDeg, float pitchDeg);  // 拖动手柄：整圈声场旋转/俯仰
    void reset();
    void process(float inL, float inR, float& outL, float& outR);

    static constexpr int FFT_SIZE = 256;       // 频域大小（2 的幂）
    static constexpr int BLOCK = FFT_SIZE / 2;  // 分区 / 块长 = 128

private:
    struct Speaker {
        int numPartitions = 0;
        std::vector<std::vector<float>> Yin_re, Yin_im;  // 输入延迟线频谱 [P][FFT_SIZE]
        std::vector<std::vector<float>> Hre_L, Him_L;    // 左耳滤波频谱 [P][FFT_SIZE]
        std::vector<std::vector<float>> Hre_R, Him_R;    // 右耳滤波频谱 [P][FFT_SIZE]
        std::vector<float> accL, accR;                   // 重叠相加累加器 [FFT_SIZE]
        std::vector<float> blk;                           // 输入块缓冲 [BLOCK]
        float gain = 1.0f;                                // 该音箱相对增益
    };

    static void fft(float* re, float* im, int n, bool inverse);

    // 把 (azDeg, elDeg) 处的双耳 HRIR 取出 / 逆距离加权插值到 irL/irR。
    void getMeasuredIR(float azDeg, float elDeg, std::vector<float>& irL, std::vector<float>& irR) const;

    // 用数据集 IR 配置某音箱卷积器（重采样到播放率并分区 FFT）。
    void configureSpeaker(Speaker& s, float azDeg, float elDeg);

    // 用当前朝向重配所有音箱。
    void configureAll();

    // 处理一个完整输入块：该音箱输入块 -> 双耳输出块（写入 tmpL_/tmpR_）。
    void blockConvolve(Speaker& s);

    // 单耳：Z = Σ_p Yin[p] * H[p][ear]，IFFT，重叠相加写入 y / acc。
    void convolveEar(float* y, std::vector<float>& acc,
                     const std::vector<std::vector<float>>& Yin_re,
                     const std::vector<std::vector<float>>& Yin_im,
                     const std::vector<std::vector<float>>& Hre,
                     const std::vector<std::vector<float>>& Him);

    int   sr_ = 48000;
    float yaw_ = 0.0f, pitch_ = 0.0f;     // 声场朝向（拖动手柄）
    std::vector<Speaker> spk_;
    std::vector<float> re_, im_;          // FFT 草稿（顺序复用）
    std::vector<float> tmpL_, tmpR_;      // 单音箱输出块缓冲
    std::vector<float> outL_, outR_;      // 多音箱求和输出队列 [BLOCK]
    int   blkPos_ = 0;
    int   outRead_ = 0, outAvail_ = 0;

    // 环境声去相关（Schroeder 全通），让 SL/SR 互不相干，制造包围感
    std::vector<float> apX_L_, apY_L_, apX_R_, apY_R_;
    int apIdxL_ = 0, apIdxR_ = 0;
    static constexpr int AP_DL = 23, AP_DR = 29;
    static constexpr float AP_G = 0.6f;
};
