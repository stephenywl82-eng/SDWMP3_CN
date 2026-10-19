#pragma once
#include <vector>
#include <algorithm>
#include <cstddef>
#include <atomic>
#include <mutex>
#include <array>

// 轻量房间混响（Schroeder：预延迟 + 早期反射 + 梳状/全通扩散尾音），给双耳信号加"空气感"。
// 早期反射抽头 + 4 梳状（尾音扩散）+ 2 全通；wet<1 时与干声混合，默认很轻（仅添空气感）。
struct HrtfReverb {
    int   sr = 48000;
    std::vector<float> pre; int preIdx = 0; int preLen = 1; int predelayLen = 1;
    struct Tap { int d; float g; };
    std::vector<Tap> taps;
    std::vector<float> early; int earlyIdx = 0;
    std::vector<std::vector<float>> comb; std::vector<int> combIdx; std::vector<float> combFb;
    std::vector<std::vector<float>> ap;   std::vector<int> apIdx;   std::vector<float> apG;

    void init(int sampleRate);
    void reset();
    float process(float x, float wet);
};

// HRIR 配置快照：某次重算产出的滤波谱 + 距离增益 + 近场因子。
// 重算在「非音频线程」完成并原子发布，音频线程只原子读取，彻底避免实时线程做 FFT/分配导致欠载爆音。
struct HrSnapshot {
    int numPartitions = 0;
    std::vector<std::vector<float>> Hre_L, Him_L;  // 左耳滤波频谱 [P][FFT_SIZE]
    std::vector<std::vector<float>> Hre_R, Him_R;  // 右耳滤波频谱 [P][FFT_SIZE]
    float distGainL = 1.0f, distGainR = 1.0f;      // 距离衰减 + 近场 ILD（左右耳）
    float near = 0.0f;                              // 近场强度 0..1（越近越大）
};

// 【V8.660】快照集合：5 个音箱的快照打包成整体，单指针原子发布。
// 旧代码逐个 speaker exchange hActive_，而音频线程一个 block 内也是逐个 load——
// 重算循环走到一半时，音频线程会读到"前几个新、后几个旧"的混搭组合，5 路虚拟音箱
// 声场瞬间错位 → 每拖动 1 度(RECFG_YAW)就"啪"一声（用户实测"调哪里都破、0 度不破"，
// 因为 0 度是初始位置从不触发重配）。打包后音频线程一次 load 拿到要么全新要么全旧的
// 一致集合，从根上消除混搭爆音。
struct HrSnapshotSet {
    std::array<HrSnapshot, 5> snaps;  // 与 kNumSpk 一致；5 是编译期常量
};

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
    void setReverbEnabled(bool on);                          // 房间混响开关（HRIR 求和之后加空气感）
    void setFieldDistance(float d);                          // 声场距离（米）：近场增强 + 距离衰减
    void reset();
    void process(float inL, float inR, float& outL, float& outR);

    static constexpr int FFT_SIZE = 256;       // 频域大小（2 的幂）
    static constexpr int BLOCK = FFT_SIZE / 2;  // 分区 / 块长 = 128
    static constexpr int MAX_PART = 16;         // 延迟线/累加器最大分区数（实际 P≈2，留足余量，避免采样率切换时重分配）
    static constexpr int kNumSpk  = 5;          // 虚拟 5.1 音箱数（与 kLayout 一致，固定，故用 array 避开 vector 对 atomic 成员的移动要求）

private:
    struct Speaker {
        std::atomic<HrSnapshot*> hActive_{nullptr};  // 音频线程只读：当前生效的 HRIR 快照
        HrSnapshot* hPrev_ = nullptr;                 // 重配线程延迟释放，避免与音频线程读竞争
        std::vector<std::vector<float>> Yin_re, Yin_im;  // 输入延迟线频谱 [MAX_PART][FFT_SIZE]（运行态，跨重配保留）
        std::vector<float> accL, accR;                   // 重叠相加累加器 [FFT_SIZE]（运行态）
        std::vector<float> blk;                           // 输入块缓冲 [BLOCK]（运行态）
        float gain = 1.0f;                                // 该音箱相对增益
        float lpL = 0.0f, lpR = 0.0f;  // 近场低频(近讲)提升一阶状态
        float curGL = 1.0f, curGR = 1.0f;  // 增益平滑态（防拖动 zipper 噪音）
        float curNear = 0.0f;             // 近场因子平滑态
    };

    static void fft(float* re, float* im, int n, bool inverse);

    // 把 (azDeg, elDeg) 处的双耳 HRIR 取出 / 逆距离加权插值，结果写入复用缓冲 irL_/irR_。
    void getMeasuredIR(float azDeg, float elDeg);

    // 用数据集 IR 配置某音箱卷积器（重采样到播放率并分区 FFT），结果写入 snap（重算线程持有）。
    void configureSpeaker(Speaker& s, float azDeg, float elDeg, HrSnapshot* snap);

    // 用当前朝向重配所有音箱（非音频线程调用）。
    void configureAll();

    // 处理一个完整输入块：该音箱输入块 -> 双耳输出块（写入 tmpL_/tmpR_）。h 为当前生效快照（音频线程只读）。
    void blockConvolve(Speaker& s, HrSnapshot* h);

    // 单耳：Z = Σ_p Yin[p] * H[p][ear]，IFFT，重叠相加写入 y / acc。
    void convolveEar(float* y, std::vector<float>& acc,
                     const std::vector<std::vector<float>>& Yin_re,
                     const std::vector<std::vector<float>>& Yin_im,
                     const std::vector<std::vector<float>>& Hre,
                     const std::vector<std::vector<float>>& Him);

    int   sr_ = 48000;
    float yawTarget_ = 0.0f, pitchTarget_ = 0.0f;  // 声场朝向目标（UI 拖动写入，重算线程据此重配 HRIR）
    float distTarget_ = 1.5f;              // 声场距离目标（UI 写入，米）：1.5=远场参考
    float cfgYaw_ = 0.0f, cfgPitch_ = 0.0f, cfgDist_ = 1.5f;  // 上次已配置值，变化超阈值才重算 HRIR
    std::array<Speaker, kNumSpk> spk_;
    std::vector<float> re_, im_;          // 音频线程 FFT 草稿（顺序复用）
    std::vector<float> tmpL_, tmpR_;      // 单音箱输出块缓冲
    std::vector<float> irL_, irR_, rL_, rR_;  // 重配 HRIR 复用草稿（仅重算线程用）
    std::vector<float> reC_, imC_;            // 重算线程 FFT 草稿（与音频线程 re_/im_ 分开，避免竞争）
    std::mutex cfgMutex_;                     // 保护 configureAll（仅重算线程持有，音频线程只做原子读，绝不阻塞）
    // 【V8.670】自适应电平：混合段按输出 RMS 慢速归一到 targetRms——输入越热(音量推满/EQ
    // boost)降得越多，且非 0 度 ITD/ILD 峰值堆积也被同一机制收编，让峰值结构性远离软限区
    // （实测非0度+热输入下 20-33% 样本进压缩区，纯调 softLimit 阈值只是重新分配压缩量）。
    // 平滑 ~100ms 只跟慢速电平、不伤瞬态。
    float adaptGain_ = 1.0f;
    std::vector<float> mixL_, mixR_;          // 混合段临时缓冲 [BLOCK]
    // 【V8.660】快照集合整体发布（替代逐 speaker 的 hActive_/hPrev_，消除混搭爆音）
    std::atomic<HrSnapshotSet*> hSet_{nullptr};   // 音频线程一次 load 拿到一致的 5 快照
    HrSnapshotSet* hSetPrev_ = nullptr;           // 延迟一拍释放，避免与音频线程读竞争
    std::vector<float> outL_, outR_;      // 多音箱求和输出队列 [BLOCK]
    int   blkPos_ = 0;
    int   outRead_ = 0, outAvail_ = 0;

    // 环境声去相关（Schroeder 全通），让 SL/SR 互不相干，制造包围感
    std::vector<float> apX_L_, apY_L_, apX_R_, apY_R_;
    int apIdxL_ = 0, apIdxR_ = 0;
    static constexpr int AP_DL = 23, AP_DR = 29;
    static constexpr float AP_G = 0.6f;

    // 房间混响（可选）：HRIR 求和之后给 L/R 加预延迟+早期反射+扩散尾音，去除"干"感
    HrtfReverb reverbL_, reverbR_;
    bool  reverbEnabled_ = false;
    float reverbWet_ = 0.18f;   // 混响湿度（干声为主，仅添空气感）
};
