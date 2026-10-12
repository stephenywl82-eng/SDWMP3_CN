#include <oboe/Oboe.h>



#include <android/log.h>



#include <jni.h>



#include <cmath>



#include <cstring>



#include <algorithm>  // std::min/max for clamp



#include <mutex>



#include <condition_variable>



#include <atomic>



#include <string>   // 【V8.172】蓝牙 codec 名解析



#include <vector>



#include <thread>



#include <random>



#include <chrono>   // 【V7.27】速率诊断时间测量



#include <time.h>   // clock_gettime / struct timespec



#include "time_stretch.h"   // 【M2】WSOLA 变速不变调（beatmatch）



// NDK Media headers for direct decoding



#include <media/NdkMediaCodec.h>



#include <media/NdkMediaExtractor.h>



#include <media/NdkMediaFormat.h>



#include <unistd.h>



#include "biquad_filter.h"



#include "loudness_comp.h"



#include "alac/ALACDecoder.h"



#include "alac/ALACBitUtilities.h"



#include <cstdio>



#include <sys/stat.h>



#define LOG_TAG "OboeBridge"



// ============================================================================
// [V8.344 / perf] per-frame diagnostic logging switches
//
// Background: several logs in this file run ON THE AUDIO CALLBACK THREAD and
// print EVERY callback frame (XF-CLICK during the whole transition window,
// XFADE-POST inside the post-flip window, XF-DBG for the first 400 frames of
// each transition). A 16s transition at 48kHz is ~3750 __android_log_print
// calls, each with floating point formatting. logcat writes from the realtime
// thread are a real underrun source and can mask the very glitch we hunt.
//
// Tiers:
//   LOGI/LOGE/LOGW -> business critical (landing, open failure, route changes).
//                     ALWAYS ON: they are the lifeline for "cut failed" /
//                     "explosion" triage. Do not remove.
//   LOGD           -> high-frequency diagnostics, fully removed at compile
//                     time when SDW_VERBOSE_DIAG=0 -- arguments are not even
//                     evaluated (macro expands to a no-op, compiler drops it).
//   LOGD_GATED     -> for logs inside the audio callback: compile-time removal
//                     PLUS a runtime gate, so one can turn them on when
//                     debugging a live issue and pay zero cost normally.
//
// [Why not just filter logcat] logcat filtering discards the OUTPUT but not
// the __android_log_print call nor the argument formatting -- that is the
// actual cost on the realtime thread.
// ============================================================================

#ifndef SDW_VERBOSE_DIAG
#define SDW_VERBOSE_DIAG 0
#endif

// Runtime master gate for high-frequency diagnostics inside the audio callback.
static std::atomic<bool> g_diagVerbose{false};


// 【V8.448诊断】过渡窗口内的逐帧诊断。
// 目的：定位「切歌落点那几秒的噪音」到底发生在哪个环节。
//
// 为什么不复用 g_diagVerbose：那是全时段开关，打开会让整个播放过程
// 每帧打一条 logcat（10ms 一条），功耗和日志量都不可接受。
// 这里改成**只在过渡窗口内自动生效**：crossfade开始后 WIN 毫秒内为真，
// 落点后再持续 WIN 毫秒。稳态播放（不切歌）完全不受影响。
static std::atomic<bool>     g_xfadeDiagActive{false};
static std::atomic<int64_t>  g_xfadeDiagUntilUs{0};

// 过渡窗口诊断是否开启（在音频回调里调用，必须无锁+无分配）
static inline bool xfadeDiagOn() {
    if (!g_xfadeDiagActive.load(std::memory_order_relaxed)) return false;
    struct timespec _t;
    clock_gettime(CLOCK_MONOTONIC, &_t);
    const int64_t nowUs = (int64_t)_t.tv_sec * 1000000LL + _t.tv_nsec / 1000LL;
    return nowUs < g_xfadeDiagUntilUs.load(std::memory_order_relaxed);
}

// 开启过渡窗口诊断（窗口 = pre + post 毫秒）
static void armXfadeDiag(int64_t preMs, int64_t postMs) {
    struct timespec _t;
    clock_gettime(CLOCK_MONOTONIC, &_t);
    const int64_t nowUs = (int64_t)_t.tv_sec * 1000000LL + _t.tv_nsec / 1000LL;
    g_xfadeDiagUntilUs.store(nowUs + (preMs + postMs) * 1000LL, std::memory_order_relaxed);
    g_xfadeDiagActive.store(true, std::memory_order_relaxed);
}

static inline bool gateDiagLog() {
    return g_diagVerbose.load(std::memory_order_relaxed);
}

// LOGD: high-frequency diagnostics, compiled out by default (args not evaluated).
#if SDW_VERBOSE_DIAG
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) do {} while (0)
#endif

// For use inside the audio callback: compile-time removal + runtime control.
#define LOGD_GATED(...) \
    do { if (gateDiagLog()) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); } while (0)

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)



#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)



#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)







// 【V7.200】Hardware FTZ — eliminate IIR denormal stall with no per-sample branch.



// AArch64 FPCR FZ (bit 24) flushes subnormals to zero in hardware.



// Called once in nativeOpen/nativeOpenFd, zero runtime cost thereafter.



static void enableHardwareFtz() {



#if defined(__aarch64__)



    uint64_t fpcr;



    __asm__ volatile("mrs %0, fpcr" : "=r"(fpcr));



    fpcr |= (1ULL << 24);  // FZ (Flush-to-Zero)



    __asm__ volatile("msr fpcr, %0" :: "r"(fpcr));



    LOGI("Hardware FTZ enabled (FPCR FZ bit 24)");



#endif



}



// ============================================================================



// Thread-safe ring buffer for PCM float data



// ============================================================================



// ============================================================================



// PCMRingBuffer — Lock-Free SPSC (Single-Producer Single-Consumer)



// V7.25: 彻底重写，无锁设计



//



// 生产者：解码器线程 (ndkDecodeLoop) → write()



// 消费者：Oboe 实时回调 (onAudioReady) → read()



//



// 关键原则：



// 1. 无 mutex — Oboe 回调永远不阻塞



// 2. acquire/release 语义保证 ARM 多核可见性



// 3. write() 返回实际写入数，调用者负责重试（不丢数据）



// 4. read() 返回实际读取数，不足部分由调用者填零



// ============================================================================



class PCMRingBuffer {



public:



    // capacity 必须是 2 的幂，方便用 & 代替 %



    explicit PCMRingBuffer(int capacity) {



        // 向上取整到 2 的幂



        int pow2 = 1;



        while (pow2 < capacity) pow2 <<= 1;



        capacity_ = pow2;



        mask_ = pow2 - 1;



        buffer_.resize(pow2, 0.0f);



        write_pos_.store(0, std::memory_order_relaxed);



        read_pos_.store(0, std::memory_order_relaxed);



    }







    // 生产者调用：写入数据，返回实际写入数量（可能 < length）



    // 不会阻塞，调用者需重试未写入部分



    int write(const float *data, int length, int reserve = 0) {



        int r = read_pos_.load(std::memory_order_acquire);  // 消费者位置



        int w = write_pos_.load(std::memory_order_relaxed);



        int freeSlots = capacity_ - (w - r);  // 利用 unsigned wrap-around 特性



        // 等价于: capacity_ - ((w - r + capacity_) % capacity_)



        // 但因为 capacity_ 是 2 的幂且用 mask_，更简单：



        int used = (w - r + capacity_) & mask_;



        // 实际上 used 可能等于 capacity_，这时满



        // 修正：用模运算



        used = (w - r + capacity_) % capacity_;



        freeSlots = capacity_ - used - 1;


        // 【V8.444 落点预留区】ring->rewind() 的回退量以**空闲空间**为上限
        // （V8.417 加的钳位，为了不越界破坏环形账本）。而落点补偿要退的是
        // bmLat * chN（1024 帧 x 2ch = 2048 采样 ≈ 23ms）。真机日志显示：
        //   XFADE-LANDCOMP why=ramp-done didRewind=1 req=2048 applied=0 free=0
        // 环被解码器灌满 -> free=0 -> 回退被夹成 0 -> 补偿路径整个没走，
        // 引擎那 1024 帧固有延迟造成的错位直接跨过去，听感就是"落点那一下"
        // 一记咔声带怪声。
        //
        // 修法不是改 rewind（越界会破坏账本，V8.417 已踩过），而是让环
        // 永远留出落点补偿需要的空隙：写入时扣掉 reserve。代价是可用缓冲
        // 少 8192 采样（约 0.19s），相对 1M 容量（12s）可忽略。
        if (reserve > 0) {
            freeSlots -= reserve;
            if (freeSlots < 0) freeSlots = 0;
        }  // 留 1 个槽位区分满/空



        int toWrite = (length < freeSlots) ? length : freeSlots;

        if (toWrite <= 0) return 0;



        for (int i = 0; i < toWrite; i++) {



            buffer_[(w + i) & mask_] = data[i];



        }



        // release: 确保上面的写入在 write_pos_ 更新前对所有核可见



        write_pos_.store((w + toWrite) & mask_, std::memory_order_release);



        return toWrite;



    }







    // 消费者调用：读取数据，返回实际读取数量（可能 < length）



    // 永远不阻塞，无锁，Oboe 实时回调安全



    int read(float *data, int length) {



        int w = write_pos_.load(std::memory_order_acquire);  // 生产者位置



        int r = read_pos_.load(std::memory_order_relaxed);



        int available = (w - r + capacity_) % capacity_;



        int toRead = (length < available) ? length : available;



        for (int i = 0; i < toRead; i++) {



            data[i] = buffer_[(r + i) & mask_];



        }



        // release: 确保读取完成后再更新 read_pos_



        read_pos_.store((r + toRead) & mask_, std::memory_order_release);



        return toRead;



    }







    // 查询可用数据量（任何线程可调用）



    int available() const {



        int w = write_pos_.load(std::memory_order_acquire);



        int r = read_pos_.load(std::memory_order_acquire);



        return (w - r + capacity_) % capacity_;



    }







    // 查询剩余空间（生产者调用）



    int freeSpace() const {



        int w = write_pos_.load(std::memory_order_relaxed);



        int r = read_pos_.load(std::memory_order_acquire);



        int used = (w - r + capacity_) % capacity_;



        return capacity_ - used - 1;



    }







    void clear() {



        write_pos_.store(0, std::memory_order_relaxed);



        read_pos_.store(0, std::memory_order_relaxed);



    }







    // 【V8.296】消费者游标回退（跨线程安全）。用于 crossfade 落点消除变速对齐引擎



    // 算法延迟造成的内容跳变：把读游标往回挪，让 1:1 直读从引擎最后输出处接续。



    // 回退落点 [r-smp, r) 是引擎本回调刚消费、但解码器尚未覆盖（写游标远在前方）的样本，



    // 物理仍在环形缓冲里，回退重读安全。仅做容量夹取，杜绝非法回退。



    // 【V8.302】单位：参数是「交织采样点」（interleaved sample，与 write/read 的 length



    // 同单位），**不是每声道帧**。传帧数会在立体声下少退一半（历史 bug 根源）。



    // 【V8.417】返回值改为「实际回退量」：调用方可记录被环空闲空间夹取后的真实值。
    int rewind(int smp) {



        if (smp <= 0) return 0;
        int r = read_pos_.load(std::memory_order_relaxed);
        int wNow = write_pos_.load(std::memory_order_acquire);
        // 【V8.417 落地回退越界修复】只夹容量还不够：环在预解码下常为满态
        // （available=capacity-1），回退会把 used 推过 capacity-1 的账本边界，
        // mod 回绕后 available() 突变 2047（真机取证值），且被回退区间物理上
        // 早已被生产者覆盖——消费者读到的是「环前沿错位音频」（听感跳动），
        // 随后账本归零误判空环（underrun 尖峰/卡顿）。回退量必须以空闲空间为
        // 上限：free=0 时回退 0（23ms 内容微跳，由落地 de-click 包络掩蔽），
        // 绝不越界破坏环形账本。
        const int usedNow = (wNow - r + capacity_) % capacity_;
        const int canBack = capacity_ - 1 - usedNow;
        int back = smp;
        if (back > capacity_ - 1) back = capacity_ - 1;
        if (back > canBack) back = canBack;
        if (back <= 0) return 0;



        int nr = (r - back) % capacity_;



        if (nr < 0) nr += capacity_;



        // release：确保本线程对读游标的回退在后续 read() 前对所有核可见



        read_pos_.store(nr, std::memory_order_release);
        return back;
    }







private:



    int capacity_;



    int mask_;  // capacity_ - 1, 用于快速取模



    std::vector<float> buffer_;



    std::atomic<int> write_pos_;



    std::atomic<int> read_pos_;



};







// ============================================================================



// DSP EQ state — 3-band cascade for Steven's Special + Cat Mode



// Band 1: High-Shelf 8kHz / -6dB / Q=0.707 (smooth rolloff)



// Band 2: Peaking 12kHz / -4dB / Q=2.0 (de-essing, narrow notch)



// Band 3: Peaking 250Hz / +3dB / Q=0.5  (Cat Mode bass boost)



//         High-Shelf 15kHz / -2dB / Q=1.0 (Cat Mode ultra-high protection)



// ============================================================================



static constexpr int kRingBufferCapacity = 1048576;  // 【V8.444】落点补偿预留：ring->rewind() 只能退到空闲空间为止，落点要退
  // bmLat(1024 帧) * ch(2) = 2048 采样。留4 倍余量，写入时扣掉这段。
  static constexpr int kLandingReserve = 8192;  // 【V7.40】1M样本≈12秒@44.1kHz立体声，彻底消除underrun







// ============================================================



// OboeAudioSink — Media3 AudioSink 实现（V7.46 新增）



// ExoPlayer 解码后 PCM → JNI → Oboe → AAudio 独占输出



// 独立于 OboeDirectPlayer 的 RingBuffer 和 Stream



// ============================================================



static PCMRingBuffer* g_sinkRingBuffer = nullptr;



static std::atomic<bool> g_sinkInitialized{false};



static std::atomic<int32_t> g_sinkSampleRate{44100};



static std::atomic<int32_t> g_sinkChannels{2};



static std::atomic<bool> g_sinkStarted{false};



static std::atomic<int64_t> g_sinkFramesWritten{0};



static int64_t g_sinkStartTimeNs = 0;  // monotonic ns when first write happened



static int64_t g_sinkStartFrames = 0;  // frames at start time







// ============================================================



// Sink Path EQ — 5-Band Biquad per stereo channel



// Matches Android Equalizer bands: 60Hz / 230Hz / 910Hz / 3.6kHz / 14kHz



// Must be declared BEFORE SinkAudioCallback (used in onAudioReady)



// ============================================================



static BiquadFilter g_sinkEqB1L, g_sinkEqB1R, g_sinkEqB2L, g_sinkEqB2R;



static BiquadFilter g_sinkEqB3L, g_sinkEqB3R, g_sinkEqB4L, g_sinkEqB4R;



static BiquadFilter g_sinkEqB5L, g_sinkEqB5R;



static std::atomic<bool> g_sinkEqEnabled{false};







// [V8.119] Headroom profile switch.



// 0 = LEGACY (bit-for-bit old behaviour): softClip knee 2/3 (-3.5 dBFS),



//     look-ahead limiter ceiling 0.85.



// 1 = WIDE (EDM / Hi-Res friendly): softClip knee 0.92 (-0.7 dBFS),



//     limiter ceiling 0.95. The old knee started wave-shaping every drum hit



//     and dense vocal peak, which read as "drum crackle" + "electric/robotic



//     vocals" + "dry/flat dynamics". WIDE keeps the region below 0.92 dBFS



//     fully linear and only tames genuine overshoot.



// Default 1 (WIDE) because the legacy setting is a fidelity bug, not a taste.



static std::atomic<bool> g_wideHeadroom{true};







// [V8.119] 原声直通（Direct passthrough）。开启时 Oboe/蓝牙链整条 DSP 全部旁路：



// EQ/MSEB/DTS/压缩/等响/瞬态/crossfeed/AGC/Limiter/softClip/dither/SinkEQ 一律不参与，



// ring 里的解码 PCM 原样出。关闭（默认）= 走上面的宽裕档（limiter 0.95 / knee 0.92）。



static std::atomic<bool> g_directPassthrough{false};



static std::mutex g_sinkEqMutex;







// [V8.118] forward declaration: softClip() is defined further below but the



// SinkAudioCallback guard (and the main callback) use it.



static inline float softClip(float x);







// 【V8.313】左右声道平衡（Balance / Pan）。
// 【V8.427·改语义】现在是**纯声道音量**调节（线性律），不再是恒功率声像、也不再做 M/S：
//   b = -1（全左）→ L=1.0, R=0.0
//   b =  0（居中）→ L=1.0, R=1.0  ← 零衰减，整体音量不变
//   b = +1（全右）→ L=0.0, R=1.0
// 往哪边推，就把**对侧**声道线性压小；推到底该侧完全静音。这就是"左右声音大小能调"。
// （旧行为：V8.313 恒功率 cos/sin + V8.335 的 M/S 只推侧向 —— 那是搬声像/声场，
//   居中还白白 -3dB，与用户"调左右音量"的诉求不符，故整条链路改为直接 L/R 增益。）
// 存的是 -1..+1 的 float，原子读写，横跨设置线程与音频回调；居中走早退零开销。



static std::atomic<float> g_stereoBalance{0.0f};







// 恒功率增益表：bal 从 -1 到 +1 共 33 点（步进 0.0625），运行时线性插值。



// 表里存的是 (L 增益, R 增益) 的 cos/sin 对。居中点索引 16 → (0.7071, 0.7071)。



struct BalGainPair { float l, r; };



// 【V8.427】恒功率表已停用（改为线性纯音量律），保留作备查/回滚用。
[[maybe_unused]] static const BalGainPair kBalTable[33] = {



    {1.0000f, 0.0000f}, {0.9988f, 0.0491f}, {0.9952f, 0.0980f}, {0.9892f, 0.1467f},



    {0.9808f, 0.1951f}, {0.9700f, 0.2430f}, {0.9569f, 0.2903f}, {0.9415f, 0.3369f},



    {0.9239f, 0.3827f}, {0.9040f, 0.4276f}, {0.8819f, 0.4714f}, {0.8577f, 0.5141f},



    {0.8315f, 0.5556f}, {0.8032f, 0.5957f}, {0.7730f, 0.6344f}, {0.7410f, 0.6716f},



    {0.7071f, 0.7071f}, {0.6716f, 0.7410f}, {0.6344f, 0.7730f}, {0.5957f, 0.8032f},



    {0.5556f, 0.8315f}, {0.5141f, 0.8577f}, {0.4714f, 0.8819f}, {0.4276f, 0.9040f},



    {0.3827f, 0.9239f}, {0.3369f, 0.9415f}, {0.2903f, 0.9569f}, {0.2430f, 0.9700f},



    {0.1951f, 0.9808f}, {0.1467f, 0.9892f}, {0.0980f, 0.9952f}, {0.0491f, 0.9988f},



    {0.0000f, 1.0000f}



};







/** 【V8.313】把当前 balance 换算成 (L,R) 增益。bal<0 会被夹到 -1。 */



static inline void stereoBalanceGains(float& lg, float& rg) {



    float b = g_stereoBalance.load(std::memory_order_relaxed);



    if (b > 1.0f) b = 1.0f; else if (b < -1.0f) b = -1.0f;



    // 【V8.427】线性律（纯声道音量）：



    // 【V8.427】改用**线性律（纯音量）**，不再用恒功率 cos/sin 表：
    //   居中 b=0  → L=1.0, R=1.0（零衰减，整体音量不变）
    //   往左 b<0  → L=1.0, R=1+b（右声道线性变小，拉到底静音）
    //   往右 b>0  → L=1-b, R=1.0（左声道线性变小，拉到底静音）
    // 恒功率版居中也要 -3dB（L=R=0.707），且只能搬声像、不能单独压一边音量 ——
    // 那不是用户要的"左右声音大小能调"。
    if (b >= 0.0f) { lg = 1.0f - b; rg = 1.0f;     }
    else           { lg = 1.0f;     rg = 1.0f + b; }
    // （恒功率查表插值实现已随 V8.427 移除，不再需要 trig 表）



}







/** 【V8.313】对一个立体声帧施加平衡。居中时零开销直接返回。



 *



 *  【V8.335·中置成分处理·解决"幅度太低/听起来不明显"】



 *  原来的实现对 L、R 直接乘增益，数学上是对的（恒功率，拉到底能到 -inf），



 *  但**听感上幅度不足**，原因是主流流音乐的内容分布：



 *    · 主唱、贝斯、底鼓、军鼓、钢琴 —— 这些**中置内容**在 L/R 里几乎等量存在；



 *    · 平衡只衰减一侧时，中置内容只是**等比例变小**，不会跑到另一侧；



 *    · 于是听感是「整首变闷/变小声」，而不是「声音偏到左边」。



 *  真实 DJ 设备/监听控制器做Balance 时用的是 **M/S（mid/side）+ 中置分离**：



 *    1) 先按 M/S 分解：mid = (L+R)/2（中置），side = (L-R)/2（侧向）；



 *    2) 平衡只作用在 **side** 上，把侧向成分往目标侧推/拉；



 *    3) **mid 保持原样**（可按需整体微调增益补偿能量）；



 *    4) 重组 L = mid + side*R侧、R = mid + side*L侧。



 *  这样中置的人声/鼓组保留在中间，侧向的吉他/镲片/混响明确偏向一侧，



 *  听感幅度立刻变大，且不损失中置清晰度 —— 这正是监听台Balance 的做法。



 *



 *  代价：每帧多 4 次乘加。相比恒功率版多一点点运算，实时线程可接受



 *  （已在 M/S 声场处理处做过同类分解，见 g_msStage）。



 */



static inline void applyStereoBalance(float& left, float& right) {



    float b = g_stereoBalance.load(std::memory_order_relaxed);



    if (b > -0.001f && b < 0.001f) return;   // 居中：绝大多数播放时间，零乘算



    float lg, rg;



    stereoBalanceGains(lg, rg);







    // （V8.335 的 M/S 分解与恒功率归一化已随 V8.427 停用，历史说明见文件头 V8.427 条目。）




    // 【V8.427】纯声道音量：直接对 L / R 各乘自己的增益，不再做 M/S 分解。
    // 用户要的是"左右声音大小能调"，不是声像/声场位移 —— M/S 版会把中置的人声/鼓组
    // 死死钉在中间、只推两侧，听感是"声场偏了"而不是"这边变小声了"。（居中已由上面早退。）



    left  *= lg;



    right *= rg;



}







// 【V7.xx】应用 Sink EQ 到单个立体声帧



static void applySinkEqStereoFrame(float& left, float& right) {



    if (!g_sinkEqEnabled.load(std::memory_order_acquire)) return;



    if (g_directPassthrough.load(std::memory_order_acquire)) return;  // [V8.119] 原声直通



    left  = g_sinkEqB5L.process(g_sinkEqB4L.process(g_sinkEqB3L.process(g_sinkEqB2L.process(g_sinkEqB1L.process(left)))));



    right = g_sinkEqB5R.process(g_sinkEqB4R.process(g_sinkEqB3R.process(g_sinkEqB2R.process(g_sinkEqB1R.process(right)))));



}







class SinkAudioCallback : public oboe::AudioStreamCallback {



    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override {



        // DIAG: unconditional rate-limited log



        static int sinkDiagCounter = 0;



        if ((sinkDiagCounter += numFrames) >= 44100) {



            LOGD_GATED("[SINK_CB] alive: eq=%d, rb=%p, frames=%d",



                 g_sinkEqEnabled.load(std::memory_order_acquire),



                 g_sinkRingBuffer, numFrames);



            sinkDiagCounter = 0;



        }



        if (!g_sinkRingBuffer) {



            memset(audioData, 0, numFrames * 2 * sizeof(float));



            return oboe::DataCallbackResult::Continue;



        }



        float* out = static_cast<float*>(audioData);



        int32_t ch = g_sinkChannels.load();



        int32_t framesRead = g_sinkRingBuffer->read(out, numFrames);



        g_sinkFramesWritten.fetch_add(framesRead);



        // 静音填充剩余帧



        if (framesRead < numFrames) {



            memset(out + framesRead * ch, 0, (numFrames - framesRead) * ch * sizeof(float));



        }



        // 【DIAG】rate-limited log every ~1s



        static int diagFrames = 0;



        if ((diagFrames += numFrames) >= 48000) {



            LOGD_GATED("Sink callback alive: eq=%d, frames=%lld",



                 g_sinkEqEnabled.load(std::memory_order_acquire),



                 (long long)g_sinkFramesWritten.load());



            diagFrames = 0;



        }



        // 【V7.xx】应用 Sink EQ 5 段均衡



        // [V8.118] Sink EQ applies after the limiter stage, so guard its own boost



        // against re-clipping (same as the main callback path).



            // 【V8.429】同主回调：Sink EQ 与声道平衡解耦（原来平衡被包在 EQ 条件里，
            // EQ 关 → 平衡一次都不施加）。EQ 按原条件，平衡无条件。
            const bool _balOn = !(g_stereoBalance.load(std::memory_order_relaxed) > -0.001f
                              && g_stereoBalance.load(std::memory_order_relaxed) <  0.001f);
            if (g_sinkEqEnabled.load(std::memory_order_acquire)) {

                std::unique_lock<std::mutex> lock(g_sinkEqMutex, std::try_to_lock);
if (lock.owns_lock()) {

            for (int i = 0; i < numFrames; i++) {

                float& L2 = out[i * 2];

                float& R2 = out[i * 2 + 1];

                applySinkEqStereoFrame(L2, R2);

                // 【V8.313】Sink 路径同样施加声道平衡，与主回调保持一致

                // （两条输出路径并存，只改一处会导致切到 Sink 时平衡失效）。

                applyStereoBalance(L2, R2);

                if (L2 > 1.0f || L2 < -1.0f) { L2 = softClip(L2); if (L2 > 1.0f) L2 = 1.0f; else if (L2 < -1.0f) L2 = -1.0f; }

                if (R2 > 1.0f || R2 < -1.0f) { R2 = softClip(R2); if (R2 > 1.0f) R2 = 1.0f; else if (R2 < -1.0f) R2 = -1.0f; }
            }

        }}

            else if (_balOn) {
                for (int i = 0; i < numFrames; i++) {
                    float& L2 = out[i * 2];
                    float& R2 = out[i * 2 + 1];
                    applyStereoBalance(L2, R2);
                    if (L2 > 1.0f || L2 < -1.0f) { L2 = softClip(L2); if (L2 > 1.0f) L2 = 1.0f; else if (L2 < -1.0f) L2 = -1.0f; }
                    if (R2 > 1.0f || R2 < -1.0f) { R2 = softClip(R2); if (R2 > 1.0f) R2 = 1.0f; else if (R2 < -1.0f) R2 = -1.0f; }
                }
            }



        return oboe::DataCallbackResult::Continue;



    }



    void onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error) override {



        LOGE("SinkStream error: %s", oboe::convertToText(error));



    }



    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override {



        LOGE("SinkStream closed on error: %s", oboe::convertToText(error));



    }



};



static SinkAudioCallback g_sinkCallback;



static oboe::ManagedStream g_sinkStream;







// 【V7.xx】初始化单声道 Sink EQ 5-band flat



static void initSinkEqChannel(BiquadFilter* bands, float sampleRate) {



    bands[0].setFlat(); bands[1].setFlat(); bands[2].setFlat();



    bands[3].setFlat(); bands[4].setFlat();



}







// 【V7.xx】设置 Sink EQ 单频段（peaking filter）



static void setSinkEqBand(BiquadFilter* band, float sampleRate, float freqHz, float gainDb, float Q) {



    band->reset();



    if (fabsf(gainDb) < 0.01f) { band->setFlat(); return; }



    band->setPeaking(sampleRate, freqHz, gainDb, Q);



}







static void startDecoderWatchdog();  // [V8.410] forward decl; defined after codecCallTimed/joinDecoderTimed

JNIEXPORT jlong JNICALL Java_com_sdw_music_player_OboeAudioSink_nativeInit(JNIEnv *env, jobject thiz) {



    LOGI("OboeAudioSink: nativeInit()");



    if (!g_sinkRingBuffer) g_sinkRingBuffer = new PCMRingBuffer(kRingBufferCapacity);



    g_sinkRingBuffer->clear();



    g_sinkInitialized.store(true);



    g_sinkFramesWritten.store(0);



    g_sinkStartTimeNs = 0;



    g_sinkStartFrames = 0;



    startDecoderWatchdog();  // [V8.410] launch the independent decoder-open watchdog

    return 1L;  // non-zero handle = initialized



}







JNIEXPORT jboolean JNICALL Java_com_sdw_music_player_OboeAudioSink_nativeOpenStream(JNIEnv *env, jobject thiz, jint sampleRate, jint channels) {



    // 【V8.486 抗混叠】输出流按激活轨源采样率开流（sampleRate 由调用方传入源采样率）。
    // 稳态下源率=流率→直通（不重采样，最省电）；仅交叉淡化/混采样率时由抗混叠重采样器处理（已加理想低通，不再混叠白噪音）。
    LOGI("OboeAudioSink: nativeOpenStream %dHz/%dch", sampleRate, channels);



    if (g_sinkStream) { g_sinkStream->close(); g_sinkStream.reset(); }



    g_sinkSampleRate.store(sampleRate);



    g_sinkChannels.store(channels);



    if (g_sinkRingBuffer) g_sinkRingBuffer->clear();







    // g_sampleRateNative/g_preferSharedMode declared later in file



    // AudioSink always tries Exclusive (matching original behavior), hardcode true



    int nativeRate = 48000;  // Android default native rate



    bool forceExclusive = true;







    oboe::AudioStreamBuilder builder;



    // [v8.165 省电] LowLatency → PowerSaving。



    // LowLatency 会把回调 buffer 压到最小 burst（约 96~192 帧），回调每 2~4ms 触发一次，



    // 实测 native 输出线程（AudioTrack）常驻 16.5% 单核、占进程 CPU 31.4%。



    // 音乐播放不需要低延迟（低延迟只对实时监听/游戏有意义），改 PowerSaving 后 AAudio



    // 会用更大的 buffer（capacity 9600 帧 ≈ 200ms），回调频率下降一个数量级。



    // 逐采样的 EQ/交叉淡化增益仍在回调内按样点计算，音质与过渡平滑度不受影响。



    builder.setDirection(oboe::Direction::Output)



        ->setPerformanceMode(oboe::PerformanceMode::PowerSaving)



        ->setFormat(oboe::AudioFormat::Float)



        ->setChannelCount(oboe::ChannelCount::Stereo)



        ->setCallback(&g_sinkCallback);







    bool opened = false;







    if (forceExclusive && nativeRate > 0) {



        // 策略1：AAudio + 原生率 + Exclusive



        builder.setAudioApi(oboe::AudioApi::AAudio);



        builder.setSampleRate(nativeRate);



        builder.setSharingMode(oboe::SharingMode::Exclusive);



        oboe::Result r = builder.openManagedStream(g_sinkStream);



        if (r == oboe::Result::OK && g_sinkStream->getSharingMode() == oboe::SharingMode::Exclusive) {



            LOGI("OboeAudioSink: Exclusive SUCCESS (native %d Hz)", nativeRate);



            opened = true;



        } else {



            if (g_sinkStream) { g_sinkStream->close(); g_sinkStream.reset(); }



            LOGW("OboeAudioSink: Exclusive failed: %s — trying Shared", r == oboe::Result::OK ? "got Shared" : oboe::convertToText(r));



        }



    }







    if (!opened) {



        // 策略2：AAudio + 文件率 + Shared



        builder.setAudioApi(oboe::AudioApi::AAudio);



        builder.setSampleRate(sampleRate);



        builder.setSharingMode(oboe::SharingMode::Shared);



        oboe::Result r = builder.openManagedStream(g_sinkStream);



        if (r == oboe::Result::OK) {



            const char* modeStr = g_sinkStream->getSharingMode() == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared";



            LOGI("OboeAudioSink: AAudio %s (fallback): %dHz", modeStr, g_sinkStream->getSampleRate());



            opened = true;



        } else {



            if (g_sinkStream) { g_sinkStream->close(); g_sinkStream.reset(); }



            LOGW("OboeAudioSink: AAudio Shared failed: %s — trying OpenSL", oboe::convertToText(r));



        }



    }







    if (!opened) {



        // 策略3：OpenSL fallback



        builder.setAudioApi(oboe::AudioApi::Unspecified);



        builder.setSampleRate(sampleRate);



        oboe::Result r = builder.openManagedStream(g_sinkStream);



        if (r == oboe::Result::OK) {



            LOGI("OboeAudioSink: OpenSL fallback: %dHz", g_sinkStream->getSampleRate());



            opened = true;



        } else {



            LOGE("OboeAudioSink: All APIs failed: %s", oboe::convertToText(r));



            return false;



        }



    }







    g_sinkSampleRate.store(g_sinkStream->getSampleRate());



    g_sinkChannels.store(g_sinkStream->getChannelCount());



    const char* modeStr = g_sinkStream->getSharingMode() == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared";



    LOGI("OboeAudioSink: opened %dHz/%dch, mode=%s",



         g_sinkSampleRate.load(), g_sinkChannels.load(), modeStr);



    return true;



}







JNIEXPORT jboolean JNICALL Java_com_sdw_music_player_OboeAudioSink_nativeStart(JNIEnv *env, jobject thiz) {



    if (!g_sinkStream) return false;



    oboe::Result r = g_sinkStream->requestStart();



    if (r != oboe::Result::OK) { LOGE("OboeAudioSink: start failed: %s", oboe::convertToText(r)); return false; }



    g_sinkStarted.store(true);



    LOGI("OboeAudioSink: started");



    return true;



}







JNIEXPORT void JNICALL Java_com_sdw_music_player_OboeAudioSink_nativePause(JNIEnv *env, jobject thiz) {



    if (g_sinkStream) g_sinkStream->requestPause();



    g_sinkStarted.store(false);



}







JNIEXPORT void JNICALL Java_com_sdw_music_player_OboeAudioSink_nativeFlush(JNIEnv *env, jobject thiz) {



    if (g_sinkRingBuffer) g_sinkRingBuffer->clear();



    g_sinkFramesWritten.store(0);



    g_sinkStartTimeNs = 0;



}







JNIEXPORT void JNICALL Java_com_sdw_music_player_OboeAudioSink_nativeClose(JNIEnv *env, jobject thiz) {



    if (g_sinkStream) { g_sinkStream->close(); g_sinkStream.reset(); }



    g_sinkStarted.store(false);



    g_sinkInitialized.store(false);



}







JNIEXPORT void JNICALL Java_com_sdw_music_player_OboeAudioSink_nativeDestroy(JNIEnv *env, jobject thiz) {



    if (g_sinkRingBuffer) { delete g_sinkRingBuffer; g_sinkRingBuffer = nullptr; }



    g_sinkStarted.store(false);



}







JNIEXPORT jint JNICALL Java_com_sdw_music_player_OboeAudioSink_nativeWriteFloat(JNIEnv *env, jobject thiz, jfloatArray data, jint offset, jint length) {



    if (!g_sinkRingBuffer || !g_sinkStarted.load()) return length;



    jfloat* arr = env->GetFloatArrayElements(data, nullptr);



    int ch = g_sinkChannels.load();



    int frames = length / ch;



    int written = g_sinkRingBuffer->write(arr + offset * ch, frames) * ch;



    env->ReleaseFloatArrayElements(data, arr, 0);



    if (g_sinkStartTimeNs == 0 && written > 0) {



        struct timespec ts;



        clock_gettime(CLOCK_MONOTONIC, &ts);



        g_sinkStartTimeNs = ts.tv_sec * 1000000000LL + ts.tv_nsec;



        g_sinkStartFrames = g_sinkFramesWritten.load();



    }



    return written;



}







JNIEXPORT jint JNICALL Java_com_sdw_music_player_OboeAudioSink_nativeWriteShort(JNIEnv *env, jobject thiz, jshortArray data, jint offset, jint length) {



    if (!g_sinkRingBuffer || !g_sinkStarted.load()) return length;



    jshort* arr = env->GetShortArrayElements(data, nullptr);



    int ch = g_sinkChannels.load();



    int frames = length / ch;



    static thread_local std::vector<float> conv(8192);



    if ((int)conv.size() < frames * ch) conv.resize(frames * ch);



    for (int i = 0; i < frames * ch; i++) conv[i] = static_cast<float>(arr[offset * ch + i]) / 32768.0f;



    int written = g_sinkRingBuffer->write(conv.data(), frames) * ch;



    env->ReleaseShortArrayElements(data, arr, 0);



    if (g_sinkStartTimeNs == 0 && written > 0) {



        struct timespec ts;



        clock_gettime(CLOCK_MONOTONIC, &ts);



        g_sinkStartTimeNs = ts.tv_sec * 1000000000LL + ts.tv_nsec;



        g_sinkStartFrames = g_sinkFramesWritten.load();



    }



    return written;



}







JNIEXPORT jint JNICALL Java_com_sdw_music_player_OboeAudioSink_nativeGetSampleRate(JNIEnv *env, jobject thiz) { return g_sinkSampleRate.load(); }



JNIEXPORT jint JNICALL Java_com_sdw_music_player_OboeAudioSink_nativeGetChannelCount(JNIEnv *env, jobject thiz) { return g_sinkChannels.load(); }







JNIEXPORT jboolean JNICALL Java_com_sdw_music_player_OboeAudioSink_nativeIsExclusive(JNIEnv *env, jobject thiz) {



    return g_sinkStream && g_sinkStream->getSharingMode() == oboe::SharingMode::Exclusive;



}







JNIEXPORT jlong JNICALL Java_com_sdw_music_player_OboeAudioSink_nativeGetFramesWritten(JNIEnv *env, jobject thiz) {



    return g_sinkFramesWritten.load();



}







JNIEXPORT jint JNICALL Java_com_sdw_music_player_OboeAudioSink_nativeGetBufferAvailable(JNIEnv *env, jobject thiz) {



    return g_sinkRingBuffer ? g_sinkRingBuffer->available() : 0;



}







JNIEXPORT jlong JNICALL Java_com_sdw_music_player_OboeAudioSink_nativeGetPresentationTimeUs(JNIEnv *env, jobject thiz) {



    if (g_sinkStartTimeNs == 0) return 0;



    int32_t sr = g_sinkSampleRate.load();



    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);



    int64_t nowNs = (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;



    int64_t elapsedNs = nowNs - g_sinkStartTimeNs;



    int64_t framesElapsed = (elapsedNs * sr) / 1000000000LL;



    return ((g_sinkStartFrames + framesElapsed) * 1000000LL) / sr;



}











// ============================================================================



// AlacDirect — 【2026-09-02】ALAC 无损直解引擎（Oboe A/B 双槽共用）



// Android MediaCodec 无 audio/alac 解码器 → 普通模式播 ALAC m4a 卡死。



// 复用 alac/ 目录 Apple 官方解码器 + 手写 MP4 box 解析（与 USB DAC 路径同源，



// 但独立实例化，A/B 槽各持一个，互不干扰）。



// 用法：openFd/openPath 成功后由解码循环驱动 decodeNext() 写调用方 ring buffer。



// ============================================================================



class AlacDirect {



public:



    struct Sample { uint64_t offset; uint32_t size; uint32_t duration; };







    ALACDecoder* dec = nullptr;



    FILE* file = nullptr;



    std::vector<Sample> samples;



    std::vector<uint8_t> cookie;



    std::atomic<int> sampleRate{0}, channels{0}, bits{0}, frameLen{4096};



    std::atomic<int64_t> totalFrames{0}, durationMs{0};



    int64_t sampleIdx = 0;      // 下一个待解 sample



    int64_t framePos = 0;       // 已喂 PCM 帧数



    bool eos = false;



    bool opened = false;







    // ── 字节序工具（MP4 big-endian）────────────────────────



    static inline uint16_t be16(const uint8_t* p) { return (uint16_t)((p[0]<<8)|p[1]); }



    static inline uint32_t be24(const uint8_t* p) { return ((uint32_t)p[0]<<16)|((uint32_t)p[1]<<8)|p[2]; }



    static inline uint32_t be32(const uint8_t* p) { return ((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|p[3]; }



    static inline uint64_t be64(const uint8_t* p) { return ((uint64_t)be32(p)<<32)|be32(p+4); }



    static uint32_t fourcc(const char* s) {



        return ((uint32_t)(uint8_t)s[0]<<24)|((uint32_t)(uint8_t)s[1]<<16)|((uint32_t)(uint8_t)s[2]<<8)|((uint32_t)(uint8_t)s[3]);



    }







    struct Box { uint64_t offset; uint64_t size; uint32_t type; };







    std::vector<Box> listBoxes(uint64_t start, uint64_t end) {



        std::vector<Box> boxes;



        uint64_t pos = start;



        while (pos + 8 <= end) {



            uint8_t hdr[16];



            if (fseek(file, (long)pos, SEEK_SET) != 0) break;



            if (fread(hdr, 1, 16, file) < 8) break;



            uint64_t size = be32(hdr);



            uint32_t type = be32(hdr + 4);



            uint64_t hdrSize = 8;



            if (size == 1) { size = be64(hdr + 8); hdrSize = 16; }



            else if (size == 0) { size = end - pos; }



            if (size < hdrSize) break;



            boxes.push_back({pos, size, type});



            pos += size;



        }



        return boxes;



    }







    Box findBox(uint32_t type, uint64_t start, uint64_t end) {



        for (auto& b : listBoxes(start, end)) if (b.type == type) return b;



        return {0,0,0};



    }







    // stts → per-sample duration



    bool parseStts(uint64_t off, uint64_t size, std::vector<uint32_t>& out) {



        uint64_t payload = off + 8, end = off + size;



        if (payload + 8 > end) return false;



        uint8_t hdr[8];



        fseek(file, (long)payload, SEEK_SET);



        if (fread(hdr, 1, 8, file) != 8) return false;



        uint32_t count = be32(hdr + 4);



        uint64_t pos = payload + 8;



        for (uint32_t i = 0; i < count; i++) {



            uint8_t e[8];



            if (pos + 8 > end) break;



            fseek(file, (long)pos, SEEK_SET);



            if (fread(e, 1, 8, file) != 8) break;



            uint32_t n = be32(e), d = be32(e+4);



            for (uint32_t j = 0; j < n; j++) out.push_back(d);



            pos += 8;



        }



        return !out.empty();



    }







    // stsz → per-sample size



    bool parseStsz(uint64_t off, uint64_t size, uint32_t sampleCount, std::vector<uint32_t>& out) {



        uint64_t payload = off + 8, end = off + size;



        if (payload + 12 > end) return false;



        uint8_t hdr[12];



        fseek(file, (long)payload, SEEK_SET);



        if (fread(hdr, 1, 12, file) != 12) return false;



        uint32_t sampleSize = be32(hdr + 4), count = be32(hdr + 8);



        out.clear();



        if (sampleSize != 0) { out.assign(sampleCount > 0 ? sampleCount : count, sampleSize); return true; }



        uint64_t pos = payload + 12;



        for (uint32_t i = 0; i < count && i < sampleCount; i++) {



            uint8_t e[4];



            if (pos + 4 > end) break;



            fseek(file, (long)pos, SEEK_SET);



            if (fread(e, 1, 4, file) != 4) break;



            out.push_back(be32(e));



            pos += 4;



        }



        return !out.empty();



    }







    // stco/co64 → chunk offsets



    bool parseChunkOffsets(uint32_t type, uint64_t off, uint64_t size, std::vector<uint64_t>& out) {



        uint64_t payload = off + 8, end = off + size;



        if (payload + 8 > end) return false;



        uint8_t hdr[8];



        fseek(file, (long)payload, SEEK_SET);



        if (fread(hdr, 1, 8, file) != 8) return false;



        uint32_t count = be32(hdr + 4);



        uint64_t pos = payload + 8;



        for (uint32_t i = 0; i < count; i++) {



            if (type == fourcc("co64")) {



                uint8_t e[8];



                if (pos + 8 > end) break;



                fseek(file, (long)pos, SEEK_SET);



                if (fread(e, 1, 8, file) != 8) break;



                out.push_back(be64(e)); pos += 8;



            } else {



                uint8_t e[4];



                if (pos + 4 > end) break;



                fseek(file, (long)pos, SEEK_SET);



                if (fread(e, 1, 4, file) != 4) break;



                out.push_back(be32(e)); pos += 4;



            }



        }



        return !out.empty();



    }







    // stsc → samples-per-chunk



    bool parseStsc(uint64_t off, uint64_t size, uint32_t chunkCount, std::vector<uint32_t>& out) {



        uint64_t payload = off + 8, end = off + size;



        if (payload + 8 > end) return false;



        uint8_t hdr[8];



        fseek(file, (long)payload, SEEK_SET);



        if (fread(hdr, 1, 8, file) != 8) return false;



        uint32_t count = be32(hdr + 4);



        struct E { uint32_t first, spc, desc; };



        std::vector<E> entries;



        uint64_t pos = payload + 8;



        for (uint32_t i = 0; i < count; i++) {



            uint8_t e[12];



            if (pos + 12 > end) break;



            fseek(file, (long)pos, SEEK_SET);



            if (fread(e, 1, 12, file) != 12) break;



            entries.push_back({be32(e), be32(e+4), be32(e+8)});



            pos += 12;



        }



        if (entries.empty()) return false;



        out.assign(chunkCount, 0);



        for (size_t i = 0; i < entries.size(); i++) {



            uint32_t from = entries[i].first;



            uint32_t to = (i + 1 < entries.size()) ? (entries[i+1].first - 1) : chunkCount;



            if (from < 1) from = 1;



            for (uint32_t c = from; c <= to && c <= chunkCount; c++) out[c-1] = entries[i].spc;



        }



        return true;



    }







    // stsd → ALAC cookie



    bool parseStsdCookie(uint64_t off, uint64_t size, std::vector<uint8_t>& outCookie) {



        uint64_t payload = off + 8, end = off + size;



        if (payload + 8 > end) return false;



        uint8_t hdr[8];



        fseek(file, (long)payload, SEEK_SET);



        if (fread(hdr, 1, 8, file) != 8) return false;



        uint64_t entryOffset = payload + 8;



        if (entryOffset + 8 > end) return false;



        uint8_t ehdr[8];



        fseek(file, (long)entryOffset, SEEK_SET);



        if (fread(ehdr, 1, 8, file) != 8) return false;



        uint64_t entrySize = be32(ehdr);



        uint32_t entryType = be32(ehdr + 4);



        if (entryType != fourcc("alac")) return false;   // AAC mp4a → 非 ALAC，拒绝



        uint64_t entryStart = entryOffset + 8;



        uint64_t entryEnd = entryOffset + entrySize;



        if (entryEnd > end) entryEnd = end;



        const uint64_t audioHdr = 28;



        uint64_t subStart = entryStart + audioHdr;



        for (auto& sub : listBoxes(subStart, entryEnd)) {



            if (sub.type == fourcc("alac")) {



                uint64_t p = sub.offset + 8, pEnd = sub.offset + sub.size;



                uint64_t readFrom = (pEnd - p >= 4 + 24) ? (p + 4) : p;



                if (pEnd - p < 24) return false;



                outCookie.resize(24);



                fseek(file, (long)readFrom, SEEK_SET);



                if (fread(outCookie.data(), 1, 24, file) != 24) return false;



                return true;



            } else if (sub.type == fourcc("wave")) {



                for (auto& w : listBoxes(sub.offset + 8, sub.offset + sub.size)) {



                    if (w.type == fourcc("alac")) {



                        uint64_t p = w.offset + 8, pEnd = w.offset + w.size;



                        uint64_t readFrom = (pEnd - p >= 4 + 24) ? (p + 4) : p;



                        if (pEnd - p < 24) return false;



                        outCookie.resize(24);



                        fseek(file, (long)readFrom, SEEK_SET);



                        if (fread(outCookie.data(), 1, 24, file) != 24) return false;



                        return true;



                    }



                }



            }



        }



        return false;



    }







    // ── 构建完整 sample table ─────────────────────────────



    bool buildTable() {



        if (!file) return false;



        fseek(file, 0, SEEK_END);



        long fileSize = ftell(file);



        fseek(file, 0, SEEK_SET);



        if (fileSize <= 0) return false;







        Box moov = findBox(fourcc("moov"), 0, (uint64_t)fileSize);



        if (moov.type == 0) { LOGE("AlacDirect: moov not found"); return false; }







        Box audioTrak{0,0,0};



        for (auto& trak : listBoxes(moov.offset + 8, moov.offset + moov.size)) {



            if (trak.type != fourcc("trak")) continue;



            Box mdia = findBox(fourcc("mdia"), trak.offset + 8, trak.offset + trak.size);



            if (mdia.type == 0) continue;



            Box hdlr = findBox(fourcc("hdlr"), mdia.offset + 8, mdia.offset + mdia.size);



            if (hdlr.type == 0) continue;



            uint8_t hb[12];



            fseek(file, (long)(hdlr.offset + 8), SEEK_SET);



            if (fread(hb, 1, 12, file) != 12) continue;



            if (be32(hb + 8) == fourcc("soun")) { audioTrak = trak; break; }



        }



        if (audioTrak.type == 0) { LOGE("AlacDirect: no soun trak"); return false; }







        Box mdia = findBox(fourcc("mdia"), audioTrak.offset + 8, audioTrak.offset + audioTrak.size);



        if (mdia.type == 0) return false;



        Box minf = findBox(fourcc("minf"), mdia.offset + 8, mdia.offset + mdia.size);



        if (minf.type == 0) return false;



        Box stbl = findBox(fourcc("stbl"), minf.offset + 8, minf.offset + minf.size);



        if (stbl.type == 0) return false;







        Box stsd{0,0,0}, stts{0,0,0}, stsc{0,0,0}, stsz{0,0,0}, stco{0,0,0}, co64{0,0,0};



        for (auto& b : listBoxes(stbl.offset + 8, stbl.offset + stbl.size)) {



            if (b.type == fourcc("stsd")) stsd = b;



            else if (b.type == fourcc("stts")) stts = b;



            else if (b.type == fourcc("stsc")) stsc = b;



            else if (b.type == fourcc("stsz")) stsz = b;



            else if (b.type == fourcc("stco")) stco = b;



            else if (b.type == fourcc("co64")) co64 = b;



        }



        if (stsd.type == 0 || stts.type == 0 || stsz.type == 0) {



            LOGE("AlacDirect: missing stsd/stts/stsz"); return false;



        }







        if (!parseStsdCookie(stsd.offset, stsd.size, cookie)) {



            LOGE("AlacDirect: cookie extraction failed (not ALAC?)"); return false;



        }







        dec = new ALACDecoder();



        if (dec->Init(cookie.data(), (uint32_t)cookie.size()) != 0) {



            LOGE("AlacDirect: ALACDecoder::Init failed");



            delete dec; dec = nullptr;



            return false;



        }



        sampleRate = dec->mConfig.sampleRate;



        channels   = dec->mConfig.numChannels;



        bits       = dec->mConfig.bitDepth;



        frameLen   = dec->mConfig.frameLength > 0 ? dec->mConfig.frameLength : 4096;







        std::vector<uint32_t> durations;



        if (!parseStts(stts.offset, stts.size, durations)) { LOGE("AlacDirect: stts failed"); return false; }



        uint32_t sampleCount = (uint32_t)durations.size();







        std::vector<uint32_t> sizes;



        if (!parseStsz(stsz.offset, stsz.size, sampleCount, sizes)) { LOGE("AlacDirect: stsz failed"); return false; }



        if (sizes.size() < sampleCount) sampleCount = (uint32_t)sizes.size();







        std::vector<uint64_t> chunkOffsets;



        Box coBox = (co64.type != 0) ? co64 : stco;



        if (coBox.type == 0 || !parseChunkOffsets(coBox.type, coBox.offset, coBox.size, chunkOffsets)) {



            LOGE("AlacDirect: no stco/co64"); return false;



        }



        uint32_t chunkCount = (uint32_t)chunkOffsets.size();







        std::vector<uint32_t> spc;



        if (stsc.type != 0) { if (!parseStsc(stsc.offset, stsc.size, chunkCount, spc)) { LOGE("AlacDirect: stsc failed"); return false; } }



        else spc.assign(chunkCount, 1);







        samples.clear();



        uint32_t sIdx = 0;



        for (uint32_t c = 0; c < chunkCount && sIdx < sampleCount; c++) {



            uint64_t dataOff = chunkOffsets[c];



            uint32_t n = spc[c];



            for (uint32_t s = 0; s < n && sIdx < sampleCount; s++) {



                Sample smp; smp.offset = dataOff; smp.size = sizes[sIdx]; smp.duration = durations[sIdx];



                samples.push_back(smp);



                dataOff += sizes[sIdx];



                sIdx++;



            }



        }



        if (samples.empty()) { LOGE("AlacDirect: zero samples"); return false; }







        int64_t total = 0;



        for (auto& s : samples) total += s.duration;



        totalFrames = total;



        durationMs = (total * 1000LL) / (sampleRate.load() > 0 ? sampleRate.load() : 1);







        LOGI("AlacDirect: OK sr=%d ch=%d bits=%d samples=%zu dur=%lldms",



             sampleRate.load(), channels.load(), bits.load(), samples.size(), (long long)durationMs.load());



        opened = true;



        return true;



    }







    // ── open by fd (dup 成 FILE*) ─────────────────────────



    bool openFd(int fd, int64_t offset, int64_t length) {



        close();



        int dupFd = dup(fd);



        if (dupFd < 0) { LOGE("AlacDirect: dup fd failed"); return false; }



        if (offset > 0) {



            if (lseek(dupFd, (off_t)offset, SEEK_SET) < 0) { LOGE("AlacDirect: lseek failed"); ::close(dupFd); return false; }



        }



        file = fdopen(dupFd, "rb");



        if (!file) { LOGE("AlacDirect: fdopen failed"); ::close(dupFd); return false; }



        if (!buildTable()) { close(); return false; }



        return true;



    }







    // ── open by path ───────────────────────────────────────



    bool openPath(const char* path) {



        close();



        file = fopen(path, "rb");



        if (!file) { LOGE("AlacDirect: fopen failed"); return false; }



        if (!buildTable()) { close(); return false; }



        return true;



    }







    void close() {



        if (dec) { delete dec; dec = nullptr; }



        if (file) { fclose(file); file = nullptr; }



        samples.clear(); cookie.clear();



        sampleIdx = 0; framePos = 0;



        eos = false; opened = false;



        sampleRate = 0; channels = 0; bits = 0; frameLen = 4096;



        totalFrames = 0; durationMs = 0;



    }







    // ── 同步解码一帧：返回帧数，0 = 无数据/错误，-1 = EOS ──



    // out 需容纳 frameLen*channels float



    int decodeNext(float* out) {



        if (!dec || !file || !opened) return 0;



        if (eos) return -1;



        if (sampleIdx >= (int64_t)samples.size()) {



            eos = true;



            return -1;



        }



        const int ch = channels.load();



        const int sr = sampleRate.load();



        const int fl = frameLen.load();



        if (ch < 1 || ch > 8 || sr < 1 || fl < 1) { eos = true; return -1; }







        const Sample& smp = samples[sampleIdx];



        if (smp.size == 0 || smp.size > 1024 * 1024) { sampleIdx++; return 0; }  // 坏包跳过







        // packet 缓冲（零初始化防越界读）



        std::vector<uint8_t> packet(smp.size + 16, 0);



        fseek(file, (long)smp.offset, SEEK_SET);



        size_t got = fread(packet.data(), 1, smp.size, file);



        if (got != smp.size) { sampleIdx++; return 0; }







        std::vector<uint8_t> intPcm((size_t)fl * ch * 4, 0);



        BitBuffer bitBuf;



        BitBufferInit(&bitBuf, packet.data(), smp.size);



        uint32_t outFrames = 0;



        int32_t status = dec->Decode(&bitBuf, intPcm.data(), (uint32_t)fl, (uint32_t)ch, &outFrames);



        if (status != 0 || outFrames == 0) { sampleIdx++; return 0; }







        // int → float



        const int bitsPS = bits.load();



        int total = (int)outFrames * ch;



        if (bitsPS == 16) {



            for (int i = 0; i < total; i++) {



                int16_t v; memcpy(&v, intPcm.data() + i*2, 2);



                out[i] = (float)v / 32768.0f;



            }



        } else if (bitsPS == 24 || bitsPS == 20) {



            const uint8_t* b = intPcm.data();



            for (int i = 0; i < total; i++) {



                int32_t v = (int32_t)(b[0] | (b[1] << 8) | (b[2] << 16));



                if (v & 0x800000) v |= (int32_t)0xFF000000;



                out[i] = (float)v / 8388608.0f;



                b += 3;



            }



        } else {



            for (int i = 0; i < total; i++) {



                int32_t v; memcpy(&v, intPcm.data() + i*4, 4);



                out[i] = (float)v / 2147483648.0f;



            }



        }



        framePos += (int64_t)outFrames;



        sampleIdx++;



        return (int)outFrames;



    }







    // ── seek（按 ms，下一帧从目标位置起）───────────────────



    void seekMs(int64_t ms) {



        int sr = sampleRate.load();



        if (sr <= 0) return;



        int64_t targetFrame = (ms * sr) / 1000;



        if (targetFrame < 0) targetFrame = 0;



        if (samples.empty()) return;



        int64_t total = 0;



        for (auto& s : samples) total += s.duration;



        if (targetFrame >= total) targetFrame = total - 1;



        int64_t acc = 0;



        int64_t idx = 0;



        for (int64_t i = 0; i < (int64_t)samples.size(); i++) {



            if (acc + (int64_t)samples[i].duration > targetFrame) { idx = i; break; }



            acc += samples[i].duration;



            idx = i;



        }



        sampleIdx = idx;



        framePos = acc;



        eos = false;



    }







    int64_t positionMs() {



        int sr = sampleRate.load();



        if (sr <= 0) return 0;



        return (framePos * 1000LL) / sr;



    }



};







// A/B 槽 ALAC 实例（仅在检测到 audio/alac 时使用；其余文件走 AMediaCodec）



static std::unique_ptr<AlacDirect> g_alacA;   // A 槽（active）



static std::unique_ptr<AlacDirect> g_alacB;   // B 槽（incoming）



static std::atomic<bool> g_alacModeA{false};  // A 槽当前是否 ALAC 直解



static std::atomic<bool> g_alacModeB{false};  // B 槽当前是否 ALAC 直解

// 【V8.471】nativeSeekIncomingMs 的前向声明在这里落地（见 35164 附近的声明）。
static bool seekIncomingSlotSeek(bool toA, int64_t ms) {
    if (toA) {
        if (g_alacModeA.load() && g_alacA) { g_alacA->seekMs(ms); return true; }
        return false;
    }
    if (g_alacModeB.load() && g_alacB) { g_alacB->seekMs(ms); return true; }
    return false;
}











// ============================================================



// OboeDirectPlayer — NDK MediaCodec 解码 + Oboe 输出



// ============================================================



static std::mutex g_streamMutex;



static PCMRingBuffer *g_ringBuffer = nullptr;



// 【Crossfade】前置声明：onAudioReady 需引用（B 轨 + crossfade 状态原子），



// 原 decoder 全局区的 g_decoderStopRequested/g_decoderCv 定义上移至此。



static std::atomic<bool> g_decoderStopRequested{false};



static std::condition_variable g_decoderCv;



static std::atomic<int64_t> g_decoderPositionUs{0};  // 【Crossfade】前置声明（onAudioReady 完成分支引用）



// 【V8.333】B 轨解码位置同样需要前置声明：crossfade 落点块（~3914 行）要用它作为



// 「新 active 轨的真实播放位置」，而它的定义在 decoder 全局区（~5008 行），会编译失败。



// 下方原定义处已去掉 initializer，避免重复定义。



static std::atomic<int64_t> g_decoderPositionB{0};



static std::atomic<bool> g_decoderStopRequestedB{false};



static std::condition_variable g_decoderCvB;



static PCMRingBuffer *g_ringBufferB = nullptr;   // 【Crossfade】B 轨 ring



static std::atomic<bool> g_activeIsB{false};     // 【Crossfade】active 轨是否为 B



static std::atomic<bool> g_crossfadeActive{false}; // 【Crossfade】正在交叉淡化
static bool xfadeOutgoingEos();   // 【V8.504】出轨(active)真 EOS 判定（前向声明；定义见解码器 EOS 原子量之后）



static std::atomic<float> g_crossfadePos{0.0f};    // 【Crossfade】进度 0..1



static std::atomic<float> g_crossfadeStep{0.0f};   // 【Crossfade】每帧增量



static std::atomic<int> g_crossfadeDurationMs{0};  // 【Crossfade】时长（完成后重置播放位置用）



// 【V8.204 诊断】crossfade 完成后的「翻转观察窗」（单调 us）。翻转那一帧起，后续若干



// callback 把 normal path 的 available/toRead 全打出来 —— 用来定位「自动切歌闪一下」。



static std::atomic<int64_t> g_xfadePostUntilUs{0};



// 【V8.227】切歌收尾 de-click：过渡末的引擎尾音截断（keylock）/滤波器历史态/ring 位置



// 微跳都可能让「混合末样本 → 新轨原始样本」出现一次波形阶跃（听感「哒」一声）。



// 翻转时记录末样本，后续 ~6ms 给输出叠加一个线性衰减的修正量，把阶跃摊平。



// 只在音频回调线程读写，无锁。



static float g_declickLast[8] = {0};



static float g_declickDelta[8] = {0};



static int   g_declickRemain = 0;



static int   g_declickTotal  = 0;



static bool  g_declickPrimed = false;



// 【V8.229】上膛标志：翻转发生在混音段（DSP 链之前），而末样本必须在回调最末



// （全部 DSP 之后）采集才是用户真正听到的值 —— 否则归一化增益/限幅一介入基准就错位。



static bool  g_declickArmPending = false;



// 【V8.231】A/B 轨断流软着陆：过渡中某轨提前 EOS（元数据时长不准/供数不足）时，



// 增益还很大就硬跳 0 = 「哒」。缺口帧改用「最后有效样本 × 5ms 线性衰减」。



// 仅音频回调线程读写。



static float g_xfadeALast[8] = {0};



static float g_xfadeBLast[8] = {0};
static float g_xfadeADecay[8] = {1.0f};   // 【V8.510】出轨 A 缺口持续衰减系数（跨回调保持，恢复供数才复位）
static float g_xfadeBDecay[8] = {1.0f};   // 【V8.510】入轨 B 缺口持续衰减系数



static int   g_xfadeAGapRun = 0;



static int   g_xfadeBGapRun = 0;

/**
 * 【V8.429】欠载淡出曲线表（gapIdx -> 衰减系数），A/B 各一份。
 * 之所以预计算成表而不是在实时线程里 powf()：欠载期间要逐采样查表，
 * 48kHz x 2ch = 96k 次/秒，powf 在音频实时线程里是硬伤（会引入不可预期的抖动）。
 * STEP=0.96 → 240 点后钳到 0，总时长 240/48000 = 5ms，与原设计的 5ms 一致；
 * 但曲线是几何下滑而不是线性，听感是自然消失而非方波咔哒。
 * 用 g_xfadeAttReady 而不是直接在实时线程初始化，避免首帧竞争。
 */
#define XFADE_GAP_FADE_N 240
#define XFADE_GAP_FADE_STEP 0.96f
static float g_xfadeAttA[XFADE_GAP_FADE_N + 1];
static float g_xfadeAttB[XFADE_GAP_FADE_N + 1];
static std::atomic<bool> g_xfadeAttReady{false};

static void buildXfadeGapFadeTable() {
    for (int i = 0; i <= XFADE_GAP_FADE_N; i++) {
        const float a = (i >= XFADE_GAP_FADE_N) ? 0.0f : powf(XFADE_GAP_FADE_STEP, (float)i);
        g_xfadeAttA[i] = a;
        g_xfadeAttB[i] = a;
    }
    g_xfadeAttReady.store(true, std::memory_order_release);
}



// 【V8.204 诊断】过渡次数，用来让每次过渡的诊断日志自动从头数（不被上一次污染）。



static std::atomic<int> g_crossfadeSeq{0};



// 【V8.205 点击检测】跨 callback 的样本跃变取证。「自动切歌闪一下」只有两种可能：



// ①翻转瞬间电平阶跃（咔哒）；②过渡中某几帧输出塌陷。前者会在「同一位置的相邻两个



// callback」之间露出样本跳变，这里逐 callback 比对并把峰值一起打出来 —— 一次说清。



static std::atomic<int64_t> g_xfadeClickUntilUs{0};  // 开启窗口（单调 us）



static std::atomic<int>    g_xfadeClickN{0};         // 窗口内 callback 计数（每次过渡清零）



static float g_xfadeLast[2] = {0.0f, 0.0f};          // 上一个 callback 末样本（L/R）

// 【V8.473 落点接缝平滑】过渡结束那一帧，输出源从"交叉淡化/引擎"切到"1:1 直读"，
// 两条路径的电平与相位不可能天然对齐 → 接缝处留一个阶跃。
// 真机（V8.472，14:38:26）落点后第一帧 edge=0.1130（peak 仅 0.27），
// 而随后 17 帧 edge 只有 0.002~0.038 —— 就是这一个孤立阶跃 = 用户听到的"闪一下"。
// 这里用一个极短的线性插值把阶跃摊到 SEAM_FRAMES 帧内。
// 【V8.497 加宽】原 256(≈5.8ms) 对 0.15~0.40 的落点阶跃仍能被听成"哒一下"；
// 摊到 1024(≈23ms @44.1k / ≈21ms @48k) 后斜率远低于人耳"咔哒"分辨阈值，且仍不改变音频内容。
static const int SEAM_FRAMES = 1024;
static std::atomic<int> g_seamLeft{0};
// [V8.502 seam probe] capture xRun + timestamp trajectory for ~6s after each seam
static std::atomic<int64_t> g_lastSeamUs{0};       // most recent seam monotonic time (us)
static std::atomic<int>     g_seamSeq{0};          // seam sequence (incremented at XFADE-DONE)
static std::atomic<int64_t> g_seamProbeUntilUs{0}; // probe window end (us); 0 = no window

// 【V8.475】ramp 期间引擎连续供不上的计数（见 16729 附近 RAMPSTARVE 兜底）。
static std::atomic<int> g_rampStarve{0};


// 【V8.456 诊断】tpl8 A/B 轨电平曲线统计（64 帧一块）
static float   g_tpl8DbgPos = 0.0f;
static double  g_tpl8DbgAccA = 0.0;
static double  g_tpl8DbgAccB = 0.0;
static int32_t g_tpl8DbgN = 0;
// 【V8.464】ramp 接管前是否已 reset 过引擎（每轮 ramp 只做一次）
static std::atomic<bool> g_rampPrimed{false};

static float   g_tpl8DbgBCut = 0.0f;   // tpl8 段1 包络影子
static float   g_tpl8DbgAHpf = 0.0f;   // tpl8 段2 包络影子
static float   g_tpl8DbgEcho = 0.0f;   // tpl8 段3 包络影子



// 【V8.15】B 轨起点(ms)：crossfade 完成时位置 = bStartMs + 已淡入时长（进度条正确显示）



static std::atomic<int64_t> g_xfadeBStartMs{0};



// 【V8.309】本次过渡开始时 outgoing 轨的播放位置（us）。落点时新 active 轨的绝对位置



// = 本值 + 过渡时长。用于修正旧式 `g_xfadeBStartMs + dur` 在 bStartMs=0（手动触发）时



// 把位置写成一个很小的值、导致进度倒退的问题。



static std::atomic<int64_t> g_xfadeAPosAtStartUs{0};



// 【V8.212 incoming 预填充】nativeStartCrossfade 在「起解码线程」到「武装 crossfade」之间



// 有一段空窗期，此时 g_crossfadeActive 仍是 false，重入守卫会放行第二次触发



// （两个解码线程抢同一个槽 = 杂音/崩溃）。用「截止时刻」而不是 bool 占位：



// 即便中途被 stop 打断，时间一到自动失效，不会留下永久卡死的标记。



static std::atomic<int64_t> g_xfadeArmUntilUs{0};



// 【V8.212】incoming 预填充目标/上限（毫秒）：解码器冷启动通常 100~300ms 才出第一帧，



// 过渡一开始就淡入会先淡出 A 轨而 B 轨还没货 —— 也就是听感上「音量突掉一下」。



#define XF_PREFILL_MS  120



#define XF_PREFILL_MAX_WAIT_MS  800   // 【V8.243】给 B 轨入场前攒够「整段过渡」的 PCM（根除 FLOOR 兜底压短）



static float g_xfadeBufA[16384];   // 【Crossfade】A 轨临时缓冲（实时线程安全 static）



static float g_xfadeBufB[16384];   // 【Crossfade】B 轨临时缓冲



// ============================================================================



// 【M2】Beatmatch 变速不变调（WSOLA）



//



// 交叉淡化期间把 incoming 轨整块 PCM 过一遍 WSOLA：tempo 拉齐到 outgoing 轨，



// 音高保持不动 —— 这才是真 DJ beatmatch，而不是靠调过渡时长规避漂移。



// 挂在「整块 PCM 刚读出」这一步（g_xfadeBufB 之后、逐采样混音之前），



// 算法细节见 time_stretch.h 头注释。



// ============================================================================



#include "rubber_band_stretch.h"              // 【V8.222】Rubber Band keylock 引擎（同 WSOLA 接口）



static WsolaStretch g_stretch;                      // WSOLA 引擎（reset 时预分配，之后无 malloc）



static RubberBandStretch g_rbStretch;               // 【V8.222】Rubber Band 引擎（高质量档）



static std::atomic<bool>  g_bmUseRb{false};         // 【V8.222】变速引擎选择：false=WSOLA，true=Rubber Band



static std::atomic<bool>  g_bmEngineArmed{false};   // 【V8.229】武装时锁定的引擎：过渡中途改开关不换引擎（防数据流错位）



static float g_xfadeBufC[16384];                    // WSOLA 输出缓冲（混音从它读 incoming）



static float g_bmScratch[8192];                     // 从 incoming ring 取输入的暂存



static std::atomic<bool>  g_beatmatchEnabled{false}; // 总开关（设置项）


// 【V8.444】解码写入时预留给落点补偿的空隙。
// 仅在 beatmatch 开启时预留 —— 落点补偿（rewind bmLat*ch）只在引擎退役时发生，
// 没开 beatmatch 就没有落点，没必要白白少 8192 采样（0.19s）的缓冲深度。
static inline int ringLandingReserve() {
    return g_beatmatchEnabled.load(std::memory_order_relaxed) ? kLandingReserve : 0;
}



static std::atomic<float> g_beatmatchRatio{1.0f};    // 目标拉伸比（>1 = incoming 放慢）



static std::atomic<bool>  g_beatmatchLive{false};    // 本轮 crossfade 是否真在用



static std::mutex         g_beatmatchMutex;          // crossfade 起止的非 RT 点取锁；RT 音频线程对引擎



                                                       // feed/render/pull 也用 try_lock 短暂取锁（争用即丢一帧），



                                                       // 二者同锁 → 引擎访问互斥，杜绝 reset 与 pull 并发堆破坏



// ============================================================================



// 【V8.243】Rubber Band keylock 持续相位锁定（连续 PLL）



// 触发时记下 A/B 轨 BPM 与初始拍相位；crossfade 期间按「两轨拍相位误差」微调拉伸比，



// 让 B 轨拍点持续贴齐 A 轨——修正一次性固定比率在 BPM 误差/相位错位下累积的漂移。



// 修正量上限 ±3%（落在 ±8% 高质量区内，听感不可闻），每 ~30ms 微调一次。



// ============================================================================



static std::atomic<bool>  g_bmPhaseLock{true};       // 开关（crossfade_rubberband_phaselock，默认开）
static std::atomic<bool>  g_bmEngage{false};         // 【V8.423】双侧 BPM 置信→武装引擎+PLL（djay 式全程跟速，见 setBeatmatch）



static std::atomic<float> g_bmPlA0{0.0f};            // A 轨初始拍相位（单位：拍，0..1）



static std::atomic<float> g_bmPlB0{0.0f};            // B 轨初始拍相位（单位：拍，0..1）



static std::atomic<float> g_bmPlR0{1.0f};            // 基准拉伸比（= BPM_A / BPM_B）



static std::atomic<float> g_bmPlAfr{120.0f};         // A 轨 BPM（拍相位积分用）



static std::atomic<float> g_bmPlBfr{120.0f};         // B 轨 BPM



static float g_bmPlMA = 0.0f;                        // A 轨已累计拍数（仅音频线程写）



static float g_bmPlMB = 0.0f;                        // B 轨已累计拍数（按当前比率推进）



/**



 * 【V8.322】B 轨初始拍相位（0..1，小节内位置）。



 *



 * 原来这里硬写 `g_bmPlMB = 0.0f`，即**假定 B 一定从 downbeat 起播**。



 * 但入场点是 `snapBToBarGrid()` 吸附出来的，只保证落在**某拍**上（可能是第2/3/4 拍），



 * 不保证落在 downbeat → 初始假定与实际不符，PLL 要花好几秒才追回来，



 * 表现为过渡开头「鼓点对不齐」。



 *



 * 现在由 Kotlin 侧把真实的「B 入场点在小节内的拍位」算出来传下来



 * （见 nativeSetPlInitPhase），PLL 从第一帧就是对的。



 * -1 = 未知 → 退回旧行为（假定 0），保证向后兼容。



 */



static std::atomic<float> g_bmPlBInitPhase{-1.0f};

/**
 * 【V8.503·③】A 轨初始拍相位（0..1，小节内位置），与 B 侧 g_bmPlBInitPhase 对称。
 * V8.322 只给 B 加了"真实入场拍相位"下发，A 侧一直硬编码假定 downbeat@t=0
 * （见 beatmatch-set 里 g_bmPlMA 的计算）。现在由 Kotlin 用 currentBeatInBar
 * 以 xfadeABeatPhaseMs 为 anchor 解算 A 当前小节内拍位后下发，使 A/B 初值同口径，
 * PLL 误差 err=(MB-MA) 不再含幽灵相位差。-1 = 未知 → 退回旧行为（假定 downbeat@t=0）。
 */
static std::atomic<float> g_bmPlAInitPhase{-1.0f};







/**



 * 【V8.322 PLL 参数可调 + 自适应增益】



 *



 * 背景：8.304 把BPM 改成浮点后，检测精度提升约 100 倍，但 PLL 的三个参数



 * 还是按**整数 BPM 时代**的误差量级调的 —— 尤其死区 0.001 相当于



 * 0.1% 的速度盲区，而人耳对速度差~0.5% 就很敏感 → 死区可能刚好卡住残余误差。



 *



 * 改成可配置（Kotlin 下发），这样不必等装机就能试不同档位；



 * 并把固定增益 0.18 改成**自适应**：误差大时猛一点快速收敛，接近 0 时轻一点防振荡。



 * 这是 PLL 的标准做法（比例增益随误差分段）。



 */



static std::atomic<float> g_bmPlDeadzone{0.0002f};   // 死区（默认 0.02%，比旧值细 5 倍）



static std::atomic<float> g_bmPlGainLo{0.10f};// 接近同步时的小增益（防振荡）



static std::atomic<float> g_bmPlGainHi{0.30f};        // 误差大时的大增益（快速收敛）



static float g_bmPlRcur = 1.0f;                      // 当前已下发到引擎的拉伸比



static int64_t g_bmPlLastUs = 0;                     // 上次微调比率的时间戳



static bool g_bmPlActive = false;                     // 本轮 crossfade 是否启用 PLL



// 【V8.284】djay Tempo Blend（Crossfader FX 预设之一）：推子推进过程中把 incoming 的速度



// 从它自己的 BPM 平滑拉到 A 轨 BPM。与现有 beatmatch 的区别：现有实现是「过渡开始前就锁死



// 到 A 的速度」，Tempo Blend 是「边推边对拍」——这正是 djay 用推子做跨 BPM 过渡的手感。



static std::atomic<bool>   g_bmBlendActive{false};



static std::atomic<int64_t> g_bmBlendStartUs{0};



static std::atomic<int64_t> g_bmBlendDurUs{0};



static std::atomic<float>  g_bmBlendTo{1.0f};



// 【V8.301·#3】Tempo Blend 方向翻转：true=引擎吃 outgoing(active=A) 轨、incoming 轨 1:1 自然播放



// （djay 原版「拉 A 配 B」，B 不拉伸、音质最好）；false=原行为（拉伸 incoming 轨去配 A）。



// 【V8.425】tpl==5 一律翻转（不再排除 stem 模式）：stem 分支配套改动见 ~12607 ——
// 引擎吃 A 的全轨环，B 的 stem 加权和 1:1 播放。



static std::atomic<bool>   g_bmEngSrcActive{false};



// 【V8.301】Pitch Drop（tpl==9，变调不变速）：引擎对 incoming 轨做音高下扫，timeRatio 恒 1.0。



static std::atomic<bool>   g_bmPitchActive{false};



static std::atomic<int64_t> g_bmPitchStartUs{0};



static std::atomic<int64_t> g_bmPitchDurUs{0};



static std::atomic<float>  g_bmPitchTo{0.5f};   // 落点前最低音高（0.5 = 下八度）


// 【V8.427】「渐变拉速」全局开关（crossfade_tempo_ramp，默认开）。
// 语义：过渡期的变速不要再绑某一个模板 —— 它是**速度地基**，所有模板共用。
//   开：无论当前是 echo / brake / dissolve / roll / phrase / eq 哪一个， outgoing(A) 轨
//       都从自己的原速起步，在整段推子里被平滑拉到 B 的速度（原 Tempo Blend tpl==5 的行为）。
//   关：起步即锁死目标比率（一次性到位），速度在过渡开头就跳变。
// 与 tpl==5 的关系：Tempo Blend 恒走渐变（它本身就是这个效果），其余模板看本开关。
// Pitch Drop（tpl==9）是变调不变速，恒不参与。
static std::atomic<bool>   g_tempoRamp{true};



// 【V8.310】Keylock（调性锁定）：变速时锁住音高。



// Rubber Band 的 setTimeRatio(r) 是「速度×r、音高×r」耦合的；DJ 要的是「速度变了、



// 调不变」。做法：setRatio(1/r) + setPitchScale(r) —— 时间拉伸走 1/r 把速度掰回来，



// 音高再用 pitchScale 补偿掉 r 倍，于是净效果 = 速度 r、音高 1.0。



// 只在 RB 引擎下有意义（WSOLA 无音高轴），故 arming 时会强制 useRb。



static std::atomic<bool>   g_bmKeyLock{false};

// 【V8.500 ④】自动 Camelot 调性匹配：出轨(A) 音高整体平移的半音数（仅 keylock 分支生效）。
// 0 = 不变调；非 0 = 把 A 调性搬到与入轨(B) 兼容的落点。Kotlin 每次过渡前按 Camelot 计算下发。
static std::atomic<int>    g_keyMatchSemi{0};



// 【V8.310】Pitch/Bend 量化档位（半音）：0=关闭（连续扫）；否则 pitchDrop 的落点音高



// 取「离目标最近的整半音档」。semitone 为负=降调。表在 pitchQuantize()。



static std::atomic<int>    g_bmPitchSemi{0};



// 【V8.310】Phrase 方案可配（tpl==8）：小节数（4/8/16/32）。0 = 用默认 8。



// 【V8.310】Cut & Echo 自动化：tpl==2 时回声按拍自动重触发（djay 形态）而非固定单抽头。



static std::atomic<int>    g_tplEchoAuto{1};



// 【V8.310】Riser 重做：V8.297 因「纯白噪音升扫太难听」删掉了整个 riser。



// 现在以**滤波扫频 riser** 回归：噪声先过带通且中心频率逐段上移、带宽逐渐收窄，



// 听起来是「音高上冲」而不是「撒盐」。仍不进 random 池默认项，由用户显式选择。



static std::atomic<int>    g_tplRiserMode{0};



static std::atomic<int64_t> g_xfadePhraseBars{0};



// 【V8.310】Auto-solo 频段（过渡期对 outgoing 轨做频段隔离）：0=关 1=低 2=中 3=高。



static std::atomic<int>    g_xfadeSoloBand{0};



// 【V8.253】过渡完成后变速比平滑回落：600ms 内把 keylock 比率滑回 1.0 再退役引擎。



// 原来在 DONE 瞬间 clear 引擎 + 弹回 1x，拉伸管线残留整段丢弃 + 速率阶跃 → 完成点「停顿一下」。



static std::atomic<bool> g_bmRampActive{false};



/**



 * 【V8.321 变速恢复顿挫】落点前速度收敛。



 *



 * 问题：V8.292 起「落点即退役引擎 + 新轨 1:1 直读」——落点那一瞬引擎还带着 PLL 调过的



 * 比率 r（PLL ±3% 窗口内），引擎被 clear()、新轨直接 1:1 播 → **速度从 r 瞬间跳到 1.0**。



 * V8.292 的注释说「≤3% 的细微tempo 阶跃由 g_declickArmPending 包络兜底」，但



 * **de-click 只修电平台阶，修不了节奏阶跃**（相位/时间轴的连续性），所以仍可闻顿挫。



 *



 * 修法：过渡最后 12%（约 1~2s，bars=8 时）把比率按 smoothstep 平滑收敛到 1.0，



 * 让落点时速度已是自然值 → 无阶跃。不用复活已废除的 runEngineRamp（那条路径有



 * 「回落期吸干-回填抖动」的历史问题），只是在下发比率上做最后一段收尾。



 */



static std::atomic<bool> g_bmConvergeActive{false};   // 落点前收敛是否已启动



static std::atomic<int64_t> g_bmRampStartUs{0};



static std::atomic<float> g_bmRampFrom{1.0f};



static std::atomic<int64_t> g_bmRampLastSetUs{0};   // 【V8.266】setRatio 节流网格







// ============================================================================



// 【V8.310】Keylock / Pitch 量化 / Phrase 档位 / Auto-solo 的公共小工具



// ============================================================================







/** 【V8.310 Keylock】把「期望速度比 r」换算成 (timeRatio, pitchScale) 送进引擎。



 *  RB 的 setTimeRatio 是速度/音高耦合的，所以：



 *    - 普通变速（keylock 关）：timeRatio=r, pitch=1.0 → 速度 r、音高也 r（传统 pitch bend）



 *    - Keylock 开           ：timeRatio=1/r, pitch=r → 速度 r、音高 1.0（调性锁定）



 *  两者净速度都是 r，区别只在音高——这正是 DJ 想要的「变速度不变调」。



 *  仅在 RB 下有意义；WSOLA 无音高轴，返回的 pitchScale 会被忽略。 */



struct EngineRatio { float timeRatio; float pitchScale; };



static EngineRatio resolveEngineRatio(float speedRatio, bool keylock) {



    if (speedRatio <= 0.0f) speedRatio = 1.0f;



    if (!keylock) return { speedRatio, 1.0f };



    // 变速 ±6% 以内 1/r 与 r 极接近，数值稳定；再离谱也不该到这里（beatmatch 已夹 ±max%）



    const float inv = 1.0f / speedRatio;



    // 【V8.512 ramp 期不动调】过渡进行中（g_crossfadeActive 为真）强制 key match semi=0 → 音高绝对
    // 不动（纯变速不变调）；落点后 g_crossfadeActive 置 false（引擎仍在 1.2s 速度回落窗口吃出轨 A），
    // 才施加调性平移，出轨 A 在淡出途中平滑贴向 B 调。既消除「变速时调也变」(现象2)，又保留落点后
    // 的调性匹配收益（djay 式平滑转调）。
    const float kmSemi = g_crossfadeActive.load(std::memory_order_relaxed)
        ? 0.0f : (float)g_keyMatchSemi.load(std::memory_order_relaxed);
    return { inv, speedRatio * std::powf(2.0f, kmSemi / 12.0f) };



}







// 【V8.310 Keylock】变速下发的**唯一入口**：所有过渡期的 setRatio 都走这里，



// 保证 Keylock 的 (timeRatio, pitchScale) 配对不会被任何一条路径漏掉。



// 直接调 g_rbStretch.setRatio() 会绕过 keylock → 音高跟着速度变（传统 bend）。



// 必须在持有 g_beatmatchMutex 时调用。



static void applyEngineRatio(bool rbArmed, float speedRatio) {



    const EngineRatio er = resolveEngineRatio(speedRatio, g_bmKeyLock.load(std::memory_order_relaxed));



    if (rbArmed) {



        g_rbStretch.setRatio(er.timeRatio);



        // WSOLA 无音高轴；keylock 只在 RB 下有意义（arming 已强制 useRb）



        if (g_bmKeyLock.load(std::memory_order_relaxed))



            g_rbStretch.setPitchScale((double)er.pitchScale);



    } else {



        g_stretch.setRatio(er.timeRatio);



    }



}







/** 【V8.310】Pitch 量化：把「落点音高」落到最近的整半音档。 */



static float pitchQuantize(float scale, int semi) {



    if (semi == 0 || scale <= 0.0f) return (scale > 0.0f) ? scale : 1.0f;



    const double k = 1.0594630943590953;   // 2^(1/12)



    // 目标半音数（可含小数）→ 就近取整到整半音



    const double semis = (double)std::log((double)scale) / std::log(k);



    int qq = (int)std::lround(semis);



    // 边界夹取：避免极端档位产生过大的 pitchScale



    if (qq > 24) qq = 24; if (qq < -24) qq = -24;



    return (float)std::pow(k, (double)qq);



}







// 【V8.301·#3 架构】统一的「落点延迟补偿 / 变速引擎退役」入口（single source of truth）。



// 背景：这段逻辑原先散在交叉淡化落点块里（引 bmLat → clear → rewind → 复位一堆 flag），



// 而 PitchDrop（tpl==9，Rubber Band setPitchScale 变调器）这类「更重的引擎」固有延迟更大，



// 落点补偿/标志复位极易漏改某处 → 落点噪音/抖动复发。收口到这里：任何「本轮变速引擎要



// 退役、并把新 active 轨的 1:1 读游标与引擎最后输出对齐」的场景都只走这一个函数，



// 延迟查询 / ring 对齐 / 标志复位只此一份。调用方只负责传参。



//   newActiveRing     : 落点后即将 1:1 直读的新 active 轨 ring（可为 nullptr = 无需对齐）。



//   newActiveIsEngSrc : 该 ring 是否就是本轮引擎消费的那一环：



//                       true  → 引擎输出滞后输入 L 帧，需把读游标回退 L 帧无缝接续；



//                       false → 新 active 是另一环的 1:1 自然播放（#3 翻转：引擎在即将退役的



//                                outgoing 轨上），无引擎延迟，无需 rewind。



//   channels          : 流声道数。**必需**：ring 的 read_pos_/write_pos_ 单位是「交织采样点」



//                       （interleaved sample），而 latencyFrames() 返回的是「每声道帧」。



//                       立体声下两者差 ch 倍——不乘 ch 就是回退量少一半，落点残留内容跳变



//                       （听感「A 轨退出后 B 顿一下」）。V8.302 修。



// 必须在 clear()/reset() 之前取引擎固有延迟（那之后信息被丢弃、归零）。



static void retireTimeEngineAtLanding(const char* why,



                                      PCMRingBuffer* newActiveRing,



                                      bool newActiveIsEngSrc,



                                      int channels) {



    int bmLat = 0;



    if (g_beatmatchMutex.try_lock()) {



        // 必须在 clear 前取引擎固有算法延迟：clear/reset 会丢弃该信息（getLatency 归零）。



        // RubberBand 实时模式延迟=一段窗口帧数；WSOLA 延迟恒为 0（time_stretch.h 直接 return 0）。



        bmLat = g_bmEngineArmed.load(std::memory_order_relaxed)



                    ? g_rbStretch.latencyFrames()



                    : g_stretch.latencyFrames();



        g_stretch.clear();



        g_rbStretch.clear();



        g_beatmatchMutex.unlock();



    }



    // 落点延迟补偿：引擎最后混出的样本对应 ring 输入位置 R-L（输出滞后输入 L 帧）。落点后新



    // active 按读游标 R 1:1 直读会跨过 [R-L,R) 这 L 帧 → 内容跳变（听感"停顿/咔一下"）。把读



    // 游标回退 L 帧，让 1:1 从引擎最后输出处无缝接续。仅当新 active 即引擎源那一环时补偿；



    // L=0（WSOLA）时 rewind 为 no-op，零影响。



    // 【V8.302 单位修正】bmLat 是「每声道帧」，ring 游标是「交织采样点」→ 必须 × ch。



    // 修正前立体声只回退了一半（1024 采样点 = 512 帧），落点恒定残留 ~11.6ms 内容跳变。



    const int chN = (channels > 0) ? ((channels > 8) ? 8 : channels) : 1;



    // 【V8.449 修正·落点内容错位】
    //
    // 真机取证（V8.449，设备 ZY22KG2WMQ）：
    //   10:43:29.627  XFADE-DONE ... newActiveIsEngSrc=0 didRewind=0 applied=0
    //   10:43:29.647  NDK decode loop B ended
    //   10:43:30.196  XF-CLICK jump=0.7510 peak=1.0000
    //   10:43:30.411  XF-CLICK jump=1.0120 peak=0.9901
    // 落点后 800ms 内 20 次样本跃变，最大 1.0120（满幅级）—— 这就是用户听到的
    // 「没解码出来的噪音」。数据是对的（无 ZEROFILL / 无 RAMPHOLE），
    // 是**内容不连续**。
    //
    // 根因：`crossfade-end` 这条路径下 newActiveIsEngSrc=0（引擎吃 A 淡出典，
    // 落点后新 active 是 incoming），旧判定认为"异源无需补偿"就跳过了。
    // 但**引擎那1024 帧延迟造成的读游标错位依然存在**：
    // 引擎最后吐的样本对应引擎源环的 R-L，而那个环此刻仍在被 1:1 直读消费，
    // 读到的是 R 位置 → 跨过 [R-L, R) → 满幅级内容跳变。
    //
    // 修法：异源时**改为补偿引擎源环**（不是新 active 环）。
    // 异源情况下引擎源环就是淡出那条A 轨，落点后它马上要被弃用，
    // 在弃用前把它对齐到引擎最后的输出位置，接缝就平了。
    PCMRingBuffer* rewindTarget= newActiveRing;
    if (!newActiveIsEngSrc) {
        const bool engIsA = g_bmEngSrcActive.load(std::memory_order_relaxed);
        PCMRingBuffer* engRing = engIsA ? g_ringBuffer : g_ringBufferB;
        if (engRing != nullptr) rewindTarget = engRing;
        LOGI("XFADE-LANDCOMP cross-source: rewind engine-source ring instead (engIsA=%d sameAsNewActive=%d)",
             (int)engIsA, (int)(engRing == newActiveRing));
    }

    const bool didRewind = (bmLat > 0 && rewindTarget != nullptr);



    const int rewindSmp = didRewind ? (bmLat * chN) : 0;
    // 【V8.417】rewind 可能被环空闲空间夹取（满环 → 0）。记录实际回退量，
    // 便于区分"补偿完成 / 被夹取跳过"两种落点。
    int rewindDone = 0;
    if (didRewind) rewindDone = rewindTarget->rewind(rewindSmp);
    if (didRewind && rewindDone != rewindSmp) {
        LOGI("landing rewind clamped by ring space: req=%d applied=%d smp (avail=%d free=%d)",
             rewindSmp, rewindDone, newActiveRing->available(), newActiveRing->freeSpace());
    }

    //【V8.441 落点补偿诊断】把「补偿有没有生效」的所有判定量打成一。
    //
    // 为什么加：用户报「过渡刚结束那一下有没解码出来的噪音」，真机日志只看到
    //   Beatmatch engine retired: latency-comp L=1024fr -> rewind=0smp ring-rewind=no
    // 而 ring-rewind=no 是**推论**不是证据 —— 要确认它是不是每次落点都发生，
    // 必须把 flip / newActiveIsEngSrc / bmLat / 实际回退量一起打出来，
    // 才能判断是「补偿被跳过」还是「补偿生效但量不够」。
    //
    // 已知可疑点（待验证，不先改）：落点调用处传的是 `!flip`，而 V8.426 把
    // bmFlip 焊死为 true（引擎恒吃 A 淡出典）。若新 active 是 incoming(B)，
    // 则新 active **不是**引擎源，此时跳过补偿在语义上是正确的；
    // 但引擎那1024 帧延迟造成的读游标错位并没有被任何机制补偿。
    // 这行日志就是用来验证「是否恒为 ring-rewind=no」的那个证据。
    LOGI("XFADE-LANDCOMP why=%s bmLat=%d ch=%d flip=%d newActiveIsEngSrc=%d didRewind=%d req=%d applied=%d "
         "newAvail=%d free=%d",
         why, bmLat, chN, (int)g_bmEngSrcActive.load(std::memory_order_relaxed),
         (int)newActiveIsEngSrc, (int)didRewind, rewindSmp, rewindDone,
         newActiveRing ? newActiveRing->available() : -1,
         newActiveRing ? newActiveRing->freeSpace() : -1);



    // 复位全部引擎相关状态（唯一一份）：翻转 / 回落 / Blend / Pitch / PLL / live / ratio。



    g_bmEngSrcActive.store(false, std::memory_order_relaxed);   // 【V8.301·#3】翻转标志



    g_bmRampActive.store(false, std::memory_order_relaxed);



    g_bmConvergeActive.store(false, std::memory_order_relaxed);   // 【V8.321】收敛已随落点完成



    g_bmRampFrom.store(1.0f, std::memory_order_relaxed);



    g_bmBlendActive.store(false, std::memory_order_relaxed);



    g_bmPitchActive.store(false, std::memory_order_relaxed);



    // 【V8.310】Keylock 退役：引擎已退役，音高补偿不该再挂着（下次武装会重设）。



    g_bmKeyLock.store(false, std::memory_order_relaxed);



    g_bmPitchSemi.store(0, std::memory_order_relaxed);



    g_beatmatchLive.store(false, std::memory_order_relaxed);



    g_bmPlActive = false;



    g_bmPlRcur = 1.0f;



    g_beatmatchRatio.store(1.0f, std::memory_order_relaxed);



    // 【V8.459 诊断】记录退役时刻的完整状态。
    // 要回答的问题：同一次落点里是否"退役 + ramp 接管"先后发生？
    LOGI("RETIRE-STATE why=%s armed=%d engSrcIsA=%d activeIsB=%d rampActive=%d plRatio=%.6f",
         why,
         (int)g_bmEngineArmed.load(std::memory_order_relaxed),
         (int)g_bmEngSrcActive.load(std::memory_order_relaxed),
         (int)g_activeIsB.load(std::memory_order_relaxed),
         (int)g_bmRampActive.load(std::memory_order_relaxed),
         (double)g_bmPlRcur);

    LOGI("Beatmatch engine retired (%s): latency-comp L=%dfr -> rewind=%dsmp(ch=%d) ring-rewind=%s",



         why, bmLat, rewindDone, chN, rewindDone > 0 ? "yes" : "no");



}







// 【V8.263】NeuralMix stem 总线：4 轨环（vocals/drums/bass/other），Kotlin feeder 推送。



// 过渡期间 B 轨贡献 = Σ stem × 各自错峰曲线增益（bass 提前 45%、vocals 推迟 30%）。



static std::atomic<bool> g_stemMode{false};

// 【Phase1·跨轨智能避让】A(外轨) 人声/低频能量包络跟随：轻量带能量 IIR（非神经网络分离，开销极低），
// 用于驱动 B(内轨) 分轨 ducking（B.voc *= 1 - A.vocEnv；B.bass 反向淡入避让）。计算点在 onAudioReady 内。
static std::atomic<bool>  g_crossDuckEnabled{true};    // 总开关（A/B 对照用，可由 Kotlin 关）
static std::atomic<float> g_crossDuckVoc{4.0f};        // 人声避让灵敏度
static std::atomic<float> g_crossDuckBass{3.5f};       // 低频避让灵敏度
static std::atomic<float> g_crossDuckFloor{0.10f};     // duck 下限（保留自然 bleeding，不打满）
static float g_aVocEnv  = 0.0f;   // 人声带幅度包络（persistent，单 RT 线程写）
static float g_aBassEnv = 0.0f;   // 低频幅度包络
static float g_aVocHP = 0.0f, g_aVocHPx = 0.0f;   // 人声带 HP(DC-blocker) 状态
static float g_aVocLP = 0.0f;                          // 人声带 LP 状态
static float g_aBassLP = 0.0f;                        // 低频 LP 状态



static PCMRingBuffer* g_stemRing[4] = {nullptr, nullptr, nullptr, nullptr};



static float* g_stemScratch = nullptr;          // 单轨读取缓冲



static size_t g_stemScratchSmp = 0;



// 【V8.283】stem 源采样率（插件恒输出 44.1k）与逐轨重采样状态。



// 旧实现要求 host 流也必须是 44.1k，否则 beginStemMode 直接失败 ——



// 48k 音源（实测本机就有 native=48000Hz）等于分轨总线永久关闭。



// 环是按流采样率消费的，直接把 44.1k 推进 48k 的环会被读快 → 变调 + 早早耗尽。



static std::atomic<int> g_stemSrcRate{44100};



static std::vector<float> g_stemResOut[4];



static double g_stemResPhase[4] = {0.0, 0.0, 0.0, 0.0};



static float  g_stemResPrev[4][8 * 64] = {{0}};
static int    g_stemResHistLen[4] = {0};






static void resetStemResampler() {



    for (int s = 0; s < 4; s++) {



        g_stemResOut[s].clear();



        g_stemResPhase[s] = 0.0;



        memset(g_stemResPrev[s], 0, sizeof(g_stemResPrev[s])); g_stemResHistLen[s] = 0;






    }



}



// 【V8.285】djay Neural Mix 分轨音效路由（Stem-Specific FX）：把过渡效果只挂到某一个分轨上，



// 而不是整曲。djay 的核心玩法：



//   · Drums HPF —— 只切掉旧歌的鼓/贝斯，保留人声与旋律的清晰度，再把新歌的鼓放进来 = Mashup



//   · Vocal Echo Out —— 回声只挂人声，静音人声后鼓和乐器立刻消失、人声带回声散去



// target: 0=关（整曲） 1=人声(stem0) 2=鼓组(stem1) 3=旋律/伴奏(stem2+3)



// mode:   0=无 1=HPF 扫频（filter/dissolve） 2=Echo（echo 模板）



static std::atomic<int> g_stemFxTarget{0};



static std::atomic<int> g_stemFxMode{0};



static float g_stemFxHp[8]  = {0};



static float g_stemFxHpX[8] = {0};



static float g_stemFxLp[8]  = {0};   // 【V8.301】mode3 低通一阶状态（y 历史）



static std::atomic<float> g_stemFxGain{1.8f};   // 【V8.301】mode4 分轨增益（突出选中的分轨）



static const int STEMFX_ECHO_MAX = 96000;   // 与 XFADE_DELAY_MAX 同规格（2s @48k）



static float g_stemFxEcho[8][STEMFX_ECHO_MAX];



static int   g_stemFxEchoWr = 0;



static void resetStemFx() {



    memset(g_stemFxHp, 0, sizeof(g_stemFxHp));



    memset(g_stemFxHpX, 0, sizeof(g_stemFxHpX));



    memset(g_stemFxLp, 0, sizeof(g_stemFxLp));



    memset(g_stemFxEcho, 0, sizeof(g_stemFxEcho));



    g_stemFxEchoWr = 0;



}



/** 判断某个 stem 是否是当前分轨音效的路由目标。 */



static inline bool stemFxAppliesTo(int stemId, int target) {



    if (target <= 0) return false;



    if (target == 1) return stemId == 0;          // 人声



    if (target == 2) return stemId == 1;          // 鼓组



    // 【V8.314】target 3/4 拆开：原来 3 = "旋律/伴奏(bass+other)" **一次处理两轨**，



    // 而 mode4 的默认增益是 1.8 —— 两轨各乘 1.8 再叠加极易削顶，听起来反而不像"有效果"。



    // 拆成 3= bass / 4 = other，各自单轨，粒度也和1=人声 / 2=鼓组一致。



    if (target == 3) return stemId == 2;          // 低音（bass）



    if (target == 4) return stemId == 3;          // 其他（other / 旋律垫）



    return false;



}



// applyStemFx() 定义在 g_sampleRate 之后（它需要读流采样率），见下方 V8.285 段。



// 【V8.243】混音母线真峰安全限幅：等功率让 RMS 恒定，但两轨瞬态叠加峰可能 >1.0。



// 在交叉淡化混音输出后加一段零延迟宽频限幅，把两轨叠加峰钳到 ~0.99，消除偶发削顶。



static float g_mixLimEnv = 0.0f;                      // 峰值包络（泄漏最大值）



static float g_mixLimGain = 1.0f;                     // 当前生效增益



static bool g_mixLimWasActive = false;



static float g_xfadeHpfPrevB[8] = {0};  // 【Crossfade 低频防浑浊】B 轨 1 阶 HPF 每声道前一采样状态



static float g_xfadeLpfPrevA[8] = {0};  // 【V8.23 A 轨对称低切】A 轨 1 阶 LPF 每声道前一采样状态



// 【V8.246 三段频交接插件】低(<150Hz)/中/高(>4kHz)各自独立交接曲线，错峰防浑浊：



// 低频走 bass-swap 式快速交接（35% 内完成）、中频标准等功率、高频 65% 后才交



// （A 高频多留一段、B 高频晚进）。默认关 = 与现有链路逐样本一致。



// A 轨低频复用 g_xfadeLpfPrevA（threeBand 开时旧低切路径被旁路，状态空闲）。



static std::atomic<bool> g_xfadeThreeBand{false};



static float g_xfadeLpAHi[8] = {0};     // A 轨 4kHz LP 状态（x - 状态 = 高频）



static float g_xfadeLpBLow[8] = {0};    // B 轨 150Hz LP 状态（= 低频）



static float g_xfadeLpBHi[8] = {0};     // B 轨 4kHz LP 状态（x - 状态 = 高频）



// 【V8.24 B 音量预匹配】B 副歌段参考 RMS（preScanChorusMs 写入，crossfade 启动时读）与 B 轨预增益



static std::atomic<float> g_xfadeChorusRms{0.0f};



static std::atomic<float> g_xfadeBPreGain{1.0f};



// 【V8.142 智能对齐】A 轨慢速 RMS（EMA），crossfade 预增益用它而非瞬时值，避免 B 音量忽大忽小



static std::atomic<float> g_xfadeARmsSlow{0.0f};



// 【V8.183 A/B 电平分离】crossfade 逐采样循环里分别累计的 A / B 轨能量平方，



// 每帧开头清零、帧末开方成两条单轨慢速 RMS。实时线程专用，不做原子。



static float g_xfadeSumA = 0.0f;



static float g_xfadeSumB = 0.0f;



// 【V8.145 DJ 切歌效果】过渡效果模式：0=off(旧行为) 1=filter 2=eq 3=echo 4=dissolve 5=tremolo 6=lunar_echo



static std::atomic<int> g_xfadeEffect{0};



// 【V8.179】seek 淡入余量（采样数）：seek 会 clear ring + flush 解码器 + 复位 EQ 状态，



// 输出必然出现阶跃（听感「啪」）。回调按剩余采样逐点乘 0→1 斜坡，把阶跃摊成斜坡。



static std::atomic<int> g_seekFadeSamples{0};



// 【V8.180 等功率交叉】gFrom=cos(θ)、gTo=sin(θ)（θ=pos·π/2），恒有 gFrom²+gTo²=1。



// 旧曲线 gFrom=(1-pos)² / gTo=1-(1-pos)² 只满足「幅度相加=1」，两轨不相关时实际输出功率是



// gFrom²+gTo² = (1-pos)⁴+((1-(1-pos)²)²，中点 0.625、pos=0.25 处约 0.51 → 中间段听感凹 0.5~3dB。



// 换成 cos/sin 后中间响度恒定，且形状几乎不变（pos=0.3 时 0.454 vs 旧 0.51，pos=0.5 时



// 0.707 vs 旧 0.75），「B 轨快速进入」的手感保留。



#define XFADE_CURVE_N 8192



static float g_xfadeCurveFrom[XFADE_CURVE_N];   // cos 表（A 轨淡出）



static float g_xfadeCurveTo[XFADE_CURVE_N];     // sin 表（B 轨淡入）



static bool g_xfadeCurveBuilt = false;



static void buildXfadeCurve() {



    if (g_xfadeCurveBuilt) return;



    const float HALF_PI = 1.5707963f;



    for (int i = 0; i < XFADE_CURVE_N; i++) {



        float th = ((float)i / (float)(XFADE_CURVE_N - 1)) * HALF_PI;



        g_xfadeCurveTo[i]    = sinf(th);



        g_xfadeCurveFrom[i] = cosf(th);



    }



    g_xfadeCurveBuilt = true;



}



// 【V8.190 原地搬移】refreshXfadeFxParams() 原来写在 944 行，但用到的



// g_xfadeBeatMs / g_xfadeTremHz / g_xfadeFiltStartHz / g_sampleRate 都在



// 960~992 行才声明 —— 8.182 起这份 cpp 就编译不过，所有 8.182~8.189 的 APK



// 都在跑 Sep 27 的旧 so，native 侧的修复（VU 三路独立/平滑系数/8.183 的



// crossfade 进度条冻结修复）从未真正生效。函数定义挪到声明之后。



// 【V8.182】DJ 效果参数派生：任何影响 BPM/采样率的入口都调它，保证 tremolo 频率与



// 扫频起点始终是「当下」的值（BPM 曲子切换时不需要 Java 侧重复下发）。



static std::atomic<bool>  g_liveFxEnabled{false};



static std::atomic<float> g_liveFxDepth{1.0f};



static float g_lfxLp[2]  = {0};   // filter 总线 LP 状态



static float g_lfxLow[2] = {0};   // eq 总线 低频状态



static float g_lfxWet = 0.0f;     // 【V8.151】总线包络：仅交叉淡化窗口内为 1



static bool  g_lfxWasActive = false;



static std::atomic<float> g_xfadeBeatMs{500.0f};    // 【V8.145】节拍同步（echo/lunar 延迟时长按 1/2 拍）



static std::atomic<bool> g_xfadeBassSwap{false};    // 【V8.220】低频交接插件（默认关）：只改交叉淡化的交接曲线，关闭时链路逐字节同旧版



static const int XFADE_DELAY_MAX = 96000;           // 2s @48k 上限



static std::atomic<int> g_xfadeDelayLen{24000};     // 延迟线长度（采样数）



static float g_xfadeDelayL[XFADE_DELAY_MAX];



static float g_xfadeDelayR[XFADE_DELAY_MAX];



static int g_xfadeDelayWr = 0;



static float g_xfadeFxLpA[8] = {0};   // filter/eq A 轨 LP 状态



static float g_xfadeFxLpB[8] = {0};   // eq B 轨 LP 状态



static float g_xfadeFxHp[8]  = {0};   // filter B 轨 HP 状态



static float g_xfadeFxHpX[8] = {0};   // filter B 轨 HP 前值



static double g_xfadeModPhase = 0.0;  // tremolo 相位



// 【V8.182 DJ 效果质感 ①】tremolo 频率由「写死 9Hz」改成按 BPM 同步。



// 9Hz 与曲目 BPM 无关，120BPM 曲里等于 9/2=4.5 个颤音/拍，节奏完全对不上拍，



// 听感像故障闪烁而不是律动。改成八分音符（2 拍/秒 即 2×beatHz），随曲目 BPM 走。



// ============================================================================



// 【V8.281】djay Crossfader FX 模板（轨级、按 lpos 调制）



// 与旧的「总线 Live FX」二选一：模板生效时 Kotlin 侧会把总线类型置 0，避免双重处理。



//   0 关   1 Filter Fade（A 加 HPF 往上扫 / B 加 LPF 往下扫，中点完成低频交接）



//   2 Echo Fade（A 进 3/4 拍延迟，湿量随推子上升，A 通道快速收掉）



//   3 Brake Drop（末段 A 做 tape stop：读指针速率 1→0，音高一起掉）



//   4 Dissolve Wash（A 低切上扫 + 多抽头 wash + 中心噪声，djay 的 Reverb Wash 近似）



static std::atomic<int>   g_xfadeTpl{0};



static std::atomic<int>   g_tplEchoLen{0};      // 3/4 拍延迟长度（样本），0=用默认



static float g_tplHpA[8]  = {0};                // A 轨 HPF 状态



static float g_tplHpAX[8] = {0};                // A 轨 HPF 上一输入



static float g_tplLpB[8]  = {0};
/* [V8.402] incoming-track LPF state for the sweep-up (1.2kHz -> 20kHz). */
static float g_tplLpA[8]  = {0};                // B 轨 LPF 状态

// 【V8.505】always-on 过渡 sweeteners（与 djay tpl 正交，默认开）
//   ① 扫频：出轨 A 的 LPF 从 nyquist 扫到 ~250Hz（渐闷离场），入轨 B 的 LPF 从 ~300Hz 扫到 nyquist（由闷转清）。
//   ② vocal-recede：出轨 A 中频(人声)带随 pos 渐压，模拟 Neural-Mix 淡出上一首人声（无需真实 stem 分轨）。
static BiquadFilter g_sweepLpA[8];   // 出轨 A 扫频 LPF 状态（8 声道独立状态）
static BiquadFilter g_sweepLpB[8];   // 入轨 B 扫频 LPF 状态（8 声道独立状态）
static float g_recedeLpA[8] = {0};   // 出轨 A 低频(<150Hz)一分频状态
static float g_recedeHpA[8] = {0};   // 出轨 A 高频(>4kHz)一分频状态
static std::atomic<bool> g_sweepReset{false};  // 过渡开始由控制线程置位、音频线程消费并复位滤波状态



static float g_tplEcho[8][XFADE_DELAY_MAX];



static int   g_tplEchoWr = 0;



#define TPL_BRAKE_MAX 16384                     // ~0.37s @44.1k，够一次刹车拖尾



static float g_tplBrake[8][TPL_BRAKE_MAX];



static double g_tplBrakeWrAbs = 0.0;            // 绝对写位置



static double g_tplBrakeRd    = 0.0;            // 绝对读位置（落后写位置 = 音高下降）



static unsigned g_tplNoise = 0x1234567u;        // dissolve / riser 共用噪声 LCG



static std::atomic<int> g_tplRevNeedCfg{1};     // dissolve wash 混响需要按当前采样率重建



// 【V8.286】Beat Roll / Gate（tpl==6）：把 A 当前 1/2 或 1/4 拍切片循环（stutter roll），



// 切片内带门限包络（attack/release = chopped gate），落点随推子由 gFrom 收掉。



// 这是 Hip-Hop / Trap 过渡的主力技巧。环形缓冲复用写指针 Wr + 读起点 Start，



// 每 sliceLen 个样本把 Start 重锚到「上一拍窗口」（Wr - sliceLen），实现滚动循环。



static const int TPL_ROLL_MAX = 65536;          // 覆盖 1/2 拍（60BPM@48k≈24000，2× 留余量）



static float g_tplRoll[8][TPL_ROLL_MAX];



static int   g_tplRollWr = 0;       // 已写入样本数（环形下标）



static int   g_tplRollStart = 0;    // 当前循环切片起点（ring index）



static int   g_tplRollCnt = 0;      // 当前循环已输出样本数（0..sliceLen-1）



// 【V8.286】Noise Sweep / Riser（tpl==7）：白噪声经扫频带通（HP 80→1.5k、LP 300→18k，



// 中心频率随推子上行 = whoosh），能量包络 pos<0.85 升到峰、落点(1.0)切断 = EDM 过渡标准件。



// V8.156 把 riser 删了，这里重新引入为独立模板（与原 Dissolve 中心噪声钟形不同，是整段扫频）。



static float g_tplNsLp[8] = {0};    // 噪声 LP 状态（整形用）



static float g_tplNsHp[8] = {0};    // 噪声 HP 状态



static float g_tplNsHpX[8] = {0};   // 噪声 HP 前值



static float g_tplNsBp[8] = {0};    // 【V8.310】带通 LP 腿状态（滤波扫频 riser 用）



// 【V8.287】Phrase 分段式 FX（tpl==8）B 轨低频切 HPF 状态：前 1/2 过渡把 B 低频切掉



// （B 先进场的薄），中段再还原 —— 就是 djay「B 低频先切后还原」的招牌动作。



static float g_tplPhBhp[8] = {0};



static float g_tplPhBhpX[8] = {0};



// 【V8.310 Auto-solo 频段】outgoing 轨的频段隔离滤波状态（HPF/LPF 各一组，够画三种频段）



static float g_tplSoloHp[8]  = {0};



static float g_tplSoloHpX[8] = {0};



static float g_tplSoloLp[8]  = {0};







static void tplReset() {



    memset(g_tplHpA, 0, sizeof(g_tplHpA));



    memset(g_tplHpAX, 0, sizeof(g_tplHpAX));



    memset(g_tplLpB, 0, sizeof(g_tplLpB));
    memset(g_tplLpA, 0, sizeof(g_tplLpA));   // [V8.402] incoming sweep-up state



    memset(g_tplEcho, 0, sizeof(g_tplEcho));



    memset(g_tplBrake, 0, sizeof(g_tplBrake));



    memset(g_tplRoll, 0, sizeof(g_tplRoll));



    g_tplRollWr = 0;



    g_tplRollStart = 0;



    g_tplRollCnt = 0;



    memset(g_tplNsLp, 0, sizeof(g_tplNsLp));



    memset(g_tplNsHp, 0, sizeof(g_tplNsHp));



    memset(g_tplNsHpX, 0, sizeof(g_tplNsHpX));



    memset(g_tplNsBp, 0, sizeof(g_tplNsBp));   // 【V8.310】带通腿



    memset(g_tplPhBhp, 0, sizeof(g_tplPhBhp));



    memset(g_tplPhBhpX, 0, sizeof(g_tplPhBhpX));



    memset(g_tplSoloHp, 0, sizeof(g_tplSoloHp));



    memset(g_tplSoloHpX, 0, sizeof(g_tplSoloHpX));



    memset(g_tplSoloLp, 0, sizeof(g_tplSoloLp));



    g_tplEchoWr = 0;



    g_tplBrakeWrAbs = 0.0;



    g_tplBrakeRd = 0.0;



    g_tplNoise = 0x1234567u;



    g_tplRevNeedCfg.store(1, std::memory_order_relaxed);   // 上一轮混响尾巴不能带进新过渡



}



static std::atomic<float> g_xfadeTremHz{9.0f};



// 【V8.182 DJ 效果质感 ②】filter 扫频起点按采样率自适应。



// 旧值硬编码 20000Hz：44.1k 下是 0.454×Nyquist（合理），48k 下是 0.417×Nyquist 但一阶



// LP 在 20k/24k 处相位 already 极浅 → 过渡起步「几乎没效果、忽然才闷下去」。



// 改成 min(20k, 0.62×Nyquist)，各采样率下起步都有同等可听的滤波量。



static std::atomic<float> g_xfadeFiltStartHz{20000.0f};



// 【V8.182 DJ 效果质感 ③】B 轨慢速 RMS（与 A 轨 g_xfadeARmsSlow 对称）：



// 供预增益升级为「A/B 双向匹配」，以及过渡可视化取两轨电平。



static std::atomic<float> g_xfadeBRmsSlow{0.0f};



static oboe::ManagedStream g_outputStream;



static std::atomic<bool> g_isPlaying{false};



/**



 * 【V8.317】"曾播过"标志（latch）。



 *



 * 背景：蓝牙断连卡死的根因。蓝牙断连时 Android 蓝牙栈会**暂停媒体** →



 * g_isPlaying 被置false → 而所有恢复路径（reopen / 看门狗）都以 g_isPlaying 为前提



 * → 「想恢复要求正在播，正在播已经被断连破坏」= 自锁，没人拉得回来。



 *



 * 这个 latch 在 nativeStart(true) 置位、nativeStop 才清 —— 它表达的是



 * "**本次会话曾经开始过播放**"，而不是"此刻正在播"。



 * 于是断连后的恢复不再因为 g_isPlaying==false 而提前退出。



 * 真正「用户主动停止」仍然会把 latch 清掉，不会浪费电重建音频流。



 */



static std::atomic<bool> g_playSessionLatched{false};



static std::atomic<int> g_sampleRate{44100};



/**



 * 【V8.285】就地处理一个分轨的样本块（交织 PCM）。放在这里是因为它要读 g_sampleRate。



 * 只做两件事：HPF 扫频（把该分轨的低频让出去）和 3/4 拍 Echo（让该分轨拖着尾音散开）。



 */



static void applyStemFx(float* buf, int samples, int channels, float pos, int mode, int delaySamples) {



    if (buf == nullptr || samples <= 0 || channels <= 0 || mode <= 0) return;



    const int sr = g_sampleRate.load();



    if (sr <= 0) return;



    if (mode == 1) {



        // HPF 20Hz → 2kHz（对数扫，与 Filter Fade 的 A 轨同款）



        const float fc = 20.0f * powf(100.0f, pos);



        const float k = 1.0f - expf(-6.2831853f * fc / (float)sr);



        for (int i = 0; i < samples; i++) {



            const int c = i % channels;



            if (c >= 8) continue;



            const float y = (1.0f - k) * (g_stemFxHp[c] + buf[i] - g_stemFxHpX[c]);



            g_stemFxHpX[c] = buf[i];



            g_stemFxHp[c] = y;



            buf[i] = y;



        }



    } else if (mode == 2) {



        int dl = delaySamples;



        if (dl < 1 || dl >= STEMFX_ECHO_MAX) dl = (int)(0.75f * (float)sr);   // 默认 3/4 拍 @120BPM



        for (int i = 0; i < samples; i++) {



            const int c = i % channels;



            if (c >= 8) continue;



            int rd = g_stemFxEchoWr - dl;



            if (rd < 0) rd += STEMFX_ECHO_MAX;



            const float d = g_stemFxEcho[c][rd];



            const float x = buf[i];



            g_stemFxEcho[c][g_stemFxEchoWr] = x + d * 0.35f;



            buf[i] = x + d * 0.55f * pos;



            if (c == channels - 1) {



                g_stemFxEchoWr++;



                if (g_stemFxEchoWr >= STEMFX_ECHO_MAX) g_stemFxEchoWr = 0;



            }



        }



    } else if (mode == 3) {



        // 【V8.301】低通扫频：与 mode1 HPF 配对，把选中分轨的高频收掉（muffle）。20k→500Hz 对数扫。



        const float fc = 20000.0f * powf(0.025f, pos);



        const float k = 1.0f - expf(-6.2831853f * fc / (float)sr);



        for (int i = 0; i < samples; i++) {



            const int c = i % channels;



            if (c >= 8) continue;



            const float x = buf[i];



            const float y = k * x + (1.0f - k) * g_stemFxLp[c];



            g_stemFxLp[c] = y;



            buf[i] = y;



        }



    } else if (mode == 4) {



        // 【V8.301】分轨增益（Stem 级 EQ / 突出某轨）：把选中分轨整体抬增益，混合里更突出。



        const float g = g_stemFxGain.load(std::memory_order_relaxed);



        // 【V8.416】软限幅：1.8×（+5.1dB）抬增益后与其余分轨求和，峰值可以轻松超过 1.0，
        // 硬削波听感就是"破音/白噪音"（用户对 gain 模式的实际反馈）。0.8 以下保持完全线性
        // （安静段零影响），以上用 tanh 渐近到 1.0，消除硬拐点，混音里不再有爆音边缘。
        for (int i = 0; i < samples; i++) {
            float v = buf[i] * g;
            if (v > 0.8f)       v =  0.8f + 0.2f * tanhf(v - 0.8f);
            else if (v < -0.8f) v = -0.8f + 0.2f * tanhf(v + 0.8f);
            buf[i] = v;
        }



    }



}



static std::atomic<int> g_channelCount{2};



// 【V8.190】refreshXfadeFxParams() 从 944 行搬到这（用到上面才声明的 4 个 atomic）。



// 【V8.182】DJ 效果参数派生：任何影响 BPM/采样率的入口都调它，保证 tremolo 频率与



// 扫频起点始终是「当下」的值（BPM 曲子切换时不需要 Java 侧重复下发）。



static void refreshXfadeFxParams() {



    int sr = g_sampleRate.load(); if (sr <= 0) sr = 44100;



    float beatMs = g_xfadeBeatMs.load();



    if (beatMs < 40.0f) beatMs = 40.0f;



    if (beatMs > 5000.0f) beatMs = 5000.0f;



    // 八分音符颤音 = 2 拍/秒



    float hz = 2000.0f / beatMs;



    if (hz < 0.5f) hz = 0.5f;



    if (hz > 32.0f) hz = 32.0f;



    g_xfadeTremHz.store(hz);



    float nyq = (float)sr * 0.5f;



    float start = 20000.0f;



    if (start > nyq * 0.62f) start = nyq * 0.62f;



    if (start < 8000.0f) start = 8000.0f;



    g_xfadeFiltStartHz.store(start);



}



static std::atomic<int64_t> g_framesWritten{0};



static std::atomic<bool> g_flushRequested{false};



// Peak detection debug



static std::atomic<int64_t> g_clipSampleCount{0};  // [V8.118] 真正被硬钳位的采样点数



static std::atomic<float> g_clipPeak{0.0f};        // [V8.118] 最后的峰值(|x|)



static std::atomic<int64_t> g_totalSampleCount{0}; // 总采样点数



static std::atomic<int> g_sampleRateNative{48000}; // Android 系统原生采样率



static std::atomic<bool> g_preferSharedMode{false}; // Dolby开启时强制Shared



static std::atomic<int> g_outputDeviceId{0};       // 【V3.2.7】目标输出设备 Port ID（0=系统默认，Kotlin侧动态传入USB DAC id）



static std::atomic<int> g_streamDeviceId{-1};      // 【V8.198】当前输出流实际绑定的设备 id（-1=未建流），供切歌时判定能否复用



// 【V8.202】切歌软停止时，跳过「MediaCodec delete 后等 looper 异步释放」的 300ms。



// 那 300ms 是给真正退出用的保险。切歌是 stop → open 两步，旧实现两头各睡一次等于



// 白等 600ms，这里把 stop 侧那一次省掉。注意 open 侧那一次绝不能省，原因见



// nativeOpenFd 里的同名注释。exchange 取用即清零，避免标志残留。



static std::atomic<bool> g_skipCodecFreeSleep{false};







// DSP EQ — Biquad cascade (independent per stereo channel)



// L/R channels MUST have independent Biquad instances to avoid phase crossover



// 【V7.34】扩展为5段EQ，自定义品牌预设已移除，请使用 DSP 模式选择器



// Android 5段EQ标准频率：60Hz / 230Hz / 910Hz / 3.6kHz / 14kHz



static BiquadFilter g_eqBand1L;  // Band0: 60Hz (低频)



static BiquadFilter g_eqBand1R;



static BiquadFilter g_eqBand2L;  // Band1: 230Hz (中低频)



static BiquadFilter g_eqBand2R;



static BiquadFilter g_eqBand3L;  // Band2: 910Hz (中频)



static BiquadFilter g_eqBand3R;



static BiquadFilter g_eqBand4L;  // Band3: 3.6kHz (中高频)



static BiquadFilter g_eqBand4R;



static BiquadFilter g_eqBand5L;  // Band4: 14kHz (高频)



static BiquadFilter g_eqBand5R;



// 【V7.86】AutoEQ 10-band filters (bands 6-10; 1-5 reuse g_eqBand1-5)



static BiquadFilter g_autoEqBand6L, g_autoEqBand6R;



static BiquadFilter g_autoEqBand7L, g_autoEqBand7R;



static BiquadFilter g_autoEqBand8L, g_autoEqBand8R;



static BiquadFilter g_autoEqBand9L, g_autoEqBand9R;



static BiquadFilter g_autoEqBand10L, g_autoEqBand10R;



static std::atomic<bool> g_autoEqEnabled{false};



static std::atomic<float> g_autoEqPreGain{1.0f};



// 【V8.2】MSEB 10-band filters — independent instances (MSEB = subjective-dimension



// mapping engine, 11 dims → 10 biquads). Kept separate from AutoEQ (bands 1-10)



// and 5-band graphic EQ (bands 1-5) so the three modes never clobber each other.



static BiquadFilter g_msebBand1L, g_msebBand1R;



static BiquadFilter g_msebBand2L, g_msebBand2R;



static BiquadFilter g_msebBand3L, g_msebBand3R;



static BiquadFilter g_msebBand4L, g_msebBand4R;



static BiquadFilter g_msebBand5L, g_msebBand5R;



static BiquadFilter g_msebBand6L, g_msebBand6R;



static BiquadFilter g_msebBand7L, g_msebBand7R;



static BiquadFilter g_msebBand8L, g_msebBand8R;



static BiquadFilter g_msebBand9L, g_msebBand9R;



static BiquadFilter g_msebBand10L, g_msebBand10R;



static std::atomic<bool> g_mseb10Enabled{false};



static std::atomic<float> g_msebPreGain{1.0f};



// 【V8.3】上次提交的 10 段增益，用于跳过未变化 band 的 crossfade（拖一个滑块



// 不应让全部 10 个 band 同时双滤波并行，否则处理量翻倍 + 低频相位不对齐 -> 哗哗声）。



static float g_lastMsebGains[10] = { 999.0f, 999.0f, 999.0f, 999.0f, 999.0f,



                                     999.0f, 999.0f, 999.0f, 999.0f, 999.0f };



// 【V8.3】M/S 声场 — 跨声道处理（非 EQ）：声场宽度 + 结像。



// M=(L+R)/2 (mid/center), S=(L-R)/2 (side/stereo width).



// soundstage 映射 S 增益 (width)，imaging 映射 M 增益 (center focus)。



static std::atomic<float> g_msWidth{1.0f};      // S 增益，默认 1.0（原声场）



static std::atomic<float> g_msCenter{1.0f};     // M 增益，默认 1.0（原结像）



static std::atomic<bool> g_msEnabled{false};



static float g_curMsWidth = 1.0f;   // smoothed width (zipper-noise fix)



static float g_curMsCenter = 1.0f;  // smoothed center



// 【V8.3】Crossfeed — 消除头中效应（仅 Oboe 路径，DAC 保持 bit-perfect 直通）。



static std::atomic<float> g_crossfeedAmount{0.0f};  // 0~1 混入强度



static std::atomic<bool> g_crossfeedEnabled{false};



static float g_curCrossfeedAmount = 0.0f;  // smoothed



// 【V8.3】瞬态整形 — 双时间常数包络跟随器（时域，非 EQ）。



// fastEnv 跟踪瞬态峰值，slowEnv 跟踪持续电平，transient = fastEnv - slowEnv。



// amount>0 增强 attack，amount<0 柔化。impulseResponse 维度映射。



static std::atomic<float> g_transientAmount{0.0f};  // -1..+1（-10..+10 维度 / 10）



static std::atomic<bool> g_transientEnabled{false};



static float g_curTransientAmount = 0.0f;  // smoothed（zipper-noise fix）



// 每声道独立包络状态（L/R）



static float g_tsFastEnvL = 0.0f, g_tsSlowEnvL = 0.0f;



static float g_tsFastEnvR = 0.0f, g_tsSlowEnvR = 0.0f;



static std::atomic<bool> g_dspEqEnabled{false};



static std::atomic<bool> g_debugSilenceTest{false};  // 【V7.08】强制静音测试标志



static std::atomic<int64_t> g_playbackPositionUs{0};  // 【V7.67】实际播放位置（微秒），用于nativeGetPositionMs



// 【V8.323】曲目时长缓存的早期引用。nativeGetDurationMs 在 active 翻转后读B 轨时长，



// 而 crossfade 落点块（行~3880）用它给新位置做上界钳制，两者定义相隔一千多行，



// 故在此前向声明，实际定义见下方 open/FD 打开处。



static std::atomic<int64_t> g_cachedDurationUs{0};  // cached at open; avoids extractor race in getDurationMs



static std::atomic<int64_t> g_cachedDurationB{0};



static std::atomic<float> g_rmsLevel{0.0f};  // 【V7.xx】实时RMS振幅 0~1，Oboe回调写入，UI轮询



// 8-band spectrum: cascaded LP-diff for CDJ/DJM style



// band0 = LP(60)            band1 = LP(120)-LP(60)     band2 = LP(250)-LP(120)



// band3 = LP(500)-LP(250)   band4 = LP(2000)-LP(500)   band5 = LP(6000)-LP(2000)



// band6 = LP(12000)-LP(6000) band7 = full-LP(12000)



static std::atomic<float> g_band0{0.0f}; // 0-60Hz



static std::atomic<float> g_band1{0.0f}; // 60-120Hz



static std::atomic<float> g_band2{0.0f}; // 120-250Hz



static std::atomic<float> g_band3{0.0f}; // 250-500Hz



static std::atomic<float> g_band4{0.0f}; // 500-2000Hz



static std::atomic<float> g_band5{0.0f}; // 2000-6000Hz



static std::atomic<float> g_band6{0.0f}; // 6000-12000Hz



static std::atomic<float> g_band7{0.0f}; // 12000-20000Hz



static BiquadFilter g_lp60L, g_lp60R, g_lp120L, g_lp120R;



static BiquadFilter g_lp250L, g_lp250R, g_lp500L, g_lp500R;



static BiquadFilter g_lp2000L, g_lp2000R, g_lp6000L, g_lp6000R;



static BiquadFilter g_lp12000L, g_lp12000R;


// 【V8.370 DJ B轨频谱】A 轨的 g_band0..7 是在混音之后算的（音频回调末尾，

// 输入是 output[]，即 A+B 淡化后的和），所以交叉淡化期间它显示的是混合能量，

// 不能代表 B 轨。B 轨没有音频回调消费方（它只是被混进去），因此在 B 轨解码

// 线程里、写 ring 之前就地算一套独立级联，不碰实时音频线程，不影响普通模式。
static std::atomic<float> g_bandB0{0.0f};
static std::atomic<float> g_bandB1{0.0f};
static std::atomic<float> g_bandB2{0.0f};
static std::atomic<float> g_bandB3{0.0f};
static std::atomic<float> g_bandB4{0.0f};
static std::atomic<float> g_bandB5{0.0f};
static std::atomic<float> g_bandB6{0.0f};
static std::atomic<float> g_bandB7{0.0f};

// B 轨专用级联（不与 A 轨的 g_lpXXL/R 共用实例，避免互相推进状态）
static BiquadFilter g_lpB60, g_lpB120, g_lpB250, g_lpB500;
static BiquadFilter g_lpB2000, g_lpB6000, g_lpB12000;
static std::atomic<bool> g_bandBFiltersInited{false};



// 【V8.190】VU 专用级联（L/R 各 7 档）。旧版 bandSquare 复用上面 mono 级联的同一组



// BiquadFilter：mono 版先推进一遍，bandSquare 再推进一遍 —— 同一滤波器状态被多路



// 轮流推进，差分边界错位、低频段能量严重失真，低频柱常态爆顶 → 满格红冲掉封面色



// + 顶到 1.0 被 coerce 削平（柱子跳不动）。



static BiquadFilter g_vuLp60L, g_vuLp60R, g_vuLp120L, g_vuLp120R;



static BiquadFilter g_vuLp250L, g_vuLp250R, g_vuLp500L, g_vuLp500R;



static BiquadFilter g_vuLp2000L, g_vuLp2000R, g_vuLp6000L, g_vuLp6000R;



static BiquadFilter g_vuLp12000L, g_vuLp12000R;



static std::atomic<bool> g_vuFiltersInited{false};



// 【V8.174】Mixer VU 表要画真正的左右两列：旧接口只有 (L+R)/2 混音后的 8 频段，



// 所以右边(高频)那列只能是左边的固定比例——看着像"右边的永远低"。



// 这里把左右声道各自的 8 频段分别存下来（band 定义与上面 g_band0..7 完全一致）。



// 存的是「未压到 1.0 的原始值 * 8」，UI 侧再做自动增益，给我们留出动态余量。



static std::atomic<float> g_bandL[8] = {0.f};



static std::atomic<float> g_bandR[8] = {0.f};



// 【V8.208】真值电平（诊断页「输出电平监控」用）。



// 取点和上面的频段完全一致：mix + 响度归一化之后、EQ / masterGain / AGC / 压缩之前，



// 也就是说它量的是「节目电平」，不是最终听进去的电平 —— 别用它去验音量。



// rmsL/R 是每个 callback 内的真 RMS（ITU 之外的选择：这里要的是和 FS 可直接比对的值），



// peakL/R 带 τ≈1.5s 的衰减保持，松了会自己落回去，否则数字会永久顶住最近的最大值。



static std::atomic<float> g_rmsL{0.0f};



static std::atomic<float> g_rmsR{0.0f};



static std::atomic<float> g_peakL{0.0f};



static std::atomic<float> g_peakR{0.0f};



// 【V8.189】VU 诊断计数器：只打前 20 个 callback（奇数号回落），供 logcat -s SDW_VU 排查「柱子不跳」



static std::atomic<unsigned> g_diagVuTicks{0};



// 【V8.192 音频卡顿诊断】「声音断 / 有卡顿跳跃」只能靠这三个计数器定性：



//   g_diagUnder  = 周期内 ring buffer 欠载次数（欠载会 memset 静音 → 就是听感上的"断"）



//   g_diagWorst  = 单次欠载里最缺多少采样（判断 Producer 供给是否跟不上 Consumer）



//   g_diagWorstCbUs = 单个 callback 内部最长时间（判断是否回调线程被主线程/GC 拖住）



static std::atomic<uint64_t> g_diagUnder{0};



static std::atomic<int> g_diagWorst{0};



static std::atomic<uint64_t> g_diagWorstCbUs{0};



static std::atomic<uint64_t> g_diagCbN{0};



static std::atomic<int64_t> g_dspDisabledSampleCount{0};  // 【V7.08】DSP 关闭时的采样计数



static std::atomic<float> g_dspEqPreGain{1.0f};   // 0dB (Steven: EQ是boost不需要pre-atten)



static float g_curDspPreGain = 1.0f;  // [zipper-noise fix] smoothed pre-gain (mirror DAC path), callback-thread only



// [V8.174] 0.89f -> 1.0f。原来这 -1dB 是三层保护里最没必要的那一层：



//   softClip(MAKEUP=1.0) 的输出恒 ≤ 1.0，look-ahead limiter 的 ceiling 也落在 1.0 以内，



//   所以它在下游完全冗余 —— 它唯一的作用是在 limiter 之前先自废 1dB 响度。



// 保留 1.0 后保护只负责「防过载」这一件事，不再顺手分走响度。



// 注：本变量只在 DSP 链内生效（原声直通 / DSP 全关时整块跳过），所以 DSP 开启时的实际



// 电平相对直通高了约 1dB，这正是「开了直通反而更响」的主要来源。



static std::atomic<float> g_masterGain{1.0f};



static std::atomic<bool> g_eq5BandEnabled{false};  // 【V7.80】5段图形均衡器预设模式



static std::atomic<bool> g_msebActive{false};         // 【V7.200】MSEB 激活时保护5段EQ不被任何reset/dspMode清零



static std::atomic<float> g_normGain{1.0f};           // 【V8.170】响度归一化线性增益（1.0 = 旁路）



static float g_curNormGain = 1.0f;                    // 【V8.24x】平滑后的归一化增益（逐样本逼近目标，避免切歌换增益时电平阶跃=啪）



static std::atomic<bool> g_nightMode{false};         // 夜间模式：softClip 阈值降低



static std::atomic<bool> g_ditherEnabled{true};      // TPDF Dither 默认开启



static std::atomic<bool> g_dcBlockEnabled{true};     // DC Blocker 默认开启



static std::atomic<bool> g_sineTestEnabled{false};    // 【V7.09】正弦波自检（测试 Oboe 是否工作）



static std::atomic<float> g_sinePhase{0.0f};          // 正弦波相位（0~2π）



static std::atomic<int64_t> g_callbackCount{0};      // 【V7.10】回调计数（诊断 Oboe 是否工作）



static int64_t g_firstCallbackTimeNs = 0;  // 【V7.27】首次回调时间（速率诊断）



static std::atomic<int32_t> g_fileSampleRate{0};  // 【V7.27】文件真实采样率（from extractor format）



static std::atomic<int> g_streamError{0};          // 【V7.13】流错误码



static std::atomic<bool> g_decoderThreadRunning{false};  // 【V7.16】解码线程是否运行



static std::atomic<int> g_decoderFramesOutput{0};        // 【V7.16】解码器输出帧数



static std::atomic<int> g_ringBufferFill{0};             // 【V7.16】ring buffer 填充量



static std::atomic<int> g_nativeOpenStep{0};            // 【V7.18】nativeOpen 执行步骤



static std::atomic<int> g_nativeOpenErrorCode{0};       // 【V7.18】nativeOpen 错误码



static std::atomic<int64_t> g_underrunCount{0};        // 【V7.39】RingBuffer underrun 次数（回调读不够数据）



static std::mutex g_eqMutex;







// ============================================================================



// Look-Ahead Limiter — 5ms peak prediction



// Prevents intersample peaks from exceeding threshold



// ============================================================================



class LookAheadLimiter {



public:



    static constexpr int MAX_SAMPLES = 480;



    static constexpr float LOOKAHEAD_MS = 5.0f;



    float delayBufferL[MAX_SAMPLES] = {0};



    float delayBufferR[MAX_SAMPLES] = {0};



    int writePos = 0;



    int delayLen = 240;







    float gr_L = 1.0f;



    float gr_R = 1.0f;







    // [V8.119] ceiling follows headroom profile: 0.95 (wide) / 0.85 (legacy).



    // [V8.131] was a plain float initialised once at static-construction time; it



    // therefore never followed a later nativeSetWideHeadroom() toggle and



    // setSampleRate() did not recompute it -> limiter could stay on the old



    // ceiling while softClip already used the new knee (mismatched pair).



    // Now atomic + re-evaluated in setSampleRate() and on every profile change.



    std::atomic<float> threshold{0.95f};



    void refreshThreshold() {



        threshold.store(g_wideHeadroom.load() ? 0.95f : 0.85f, std::memory_order_relaxed);



    }



    float releaseMs = 100.0f;



    float attackCoeff = 0.0f;



    float releaseCoeff = 0.0f;



    bool enabled = true;







    void setSampleRate(int32_t sampleRate) {



        delayLen = static_cast<int>(LOOKAHEAD_MS * sampleRate / 1000.0f);



        if (delayLen > MAX_SAMPLES) delayLen = MAX_SAMPLES;



        if (delayLen < 1) delayLen = 1;



        attackCoeff = 1.0f - 1.0f / (0.001f * delayLen + 1.0f);



        releaseCoeff = 1.0f - 1.0f / (releaseMs * sampleRate / 1000.0f + 1.0f);



        refreshThreshold();   // [V8.131] keep ceiling in sync with the headroom profile



    }







    void write(float sL, float sR) {



        delayBufferL[writePos] = sL;



        delayBufferR[writePos] = sR;



        writePos = (writePos + 1) % MAX_SAMPLES;



    }







    float readDelayed(bool rightChannel) {



        int readPos = writePos - delayLen;



        if (readPos < 0) readPos += MAX_SAMPLES;



        return rightChannel ? delayBufferR[readPos] : delayBufferL[readPos];



    }







    inline float process(float sample, bool rightChannel) {



        if (!enabled) return 1.0f;



        float &gr = rightChannel ? gr_R : gr_L;



        float peak = fabsf(sample);



        const float thr = threshold.load(std::memory_order_relaxed);



        float desiredGR = (peak > thr) ? thr / peak : 1.0f;



        if (desiredGR < gr) {



            gr = attackCoeff * gr + (1.0f - attackCoeff) * desiredGR;



        } else {



            gr = releaseCoeff * gr + (1.0f - releaseCoeff) * desiredGR;



        }



        return gr;



    }







    void reset() {



        memset(delayBufferL, 0, sizeof(delayBufferL));



        memset(delayBufferR, 0, sizeof(delayBufferR));



        gr_L = 1.0f; gr_R = 1.0f; writePos = 0;



    }



};



static LookAheadLimiter g_limiter;







// ============================================================================



// ============================================================================



// DtsVirtualSurround — 【V8.172】DTS 虚拟环绕渲染（升级版）



//



// 【V8.19】旧版 DtsSurround 在蓝牙耳机上"听着一般"，能算出三个可量化原因：



//   1) 环绕量算错了量级：amb = (w-1)*0.5，w=1.4（soundstage 默认 5）→ amb=0.2；



//      再乘 lpState（本来就很小的 side 分量， routinely 0.05~0.2），



//      真正回灌进 L/R 的能量只有 0.01~0.04 —— 低于可辨阈值，等于没有环绕。



//   2) 只有"整体加宽"，没有 HRTF：耳机是双耳直达，M/S 加宽只能把声源往两侧推，



//      推不出前后/上下。耳机上要方位感必须做双耳渲染（ITD + 头影 ILD + 耳廓谱）。



//   3) 不分频段：整条声场一起宽，低频也被扩宽 → 低音发虚、听感糊，



//      得到的是"远"而不是"包围"。官方 DTS/Dolby 的声场控制都是分频段的。



//



// 新版信号流（自上而下）：



//   A) 分段宽度：side 拆成 bass(<250Hz) / mid(250-3.5k) / high(>3.5k)，各自独立增益。



//      低频不参与环绕（保持单声道、不虚），高频给最宽（空气感）。



//   B) 四抽头早期反射：7 / 11 / 17 / 26 ms 不等间距（等间距听起来像梳状滤波器），



//      增益递减、越晚越闷 —— 模拟真实房间的第一次反射群，空间感的主要来源。



//   C) HRTF 双耳渲染：环绕能量经 ITD（左右耳差 ~0.85ms）+ 头影低通（对侧高频衰减）



//      + 耳廓共振 peaking 分别投到左右耳。ITD / ILD / 谱差三样必须齐全才听得出方位，



//      缺一样就只能"变宽"不能"定位"。不用完整 HRIR 卷积（太贵），三阶近似已够。



//   D) 环绕强度独立成参数：旧版把环绕量绑死在宽度上（宽度归零就没环绕了），



//      现在"宽度"与"包围"可以分开调 —— 这是专业机与"一键特效"的分水岭。



//



// 纯 Oboe 路径（USB DAC 保持 bit-perfect 直通，不受影响）。



// ============================================================================



struct DtsStageCfg {



    float widthBass = 1.0f;   // 低频宽度增益



    float widthMid  = 1.0f;   // 中频宽度增益



    float widthHigh = 1.0f;   // 高频宽度增益



    float surround  = 0.35f;  // 环绕/包围强度 0..0.8（不再被宽度绑死）



    float height    = 0.50f;  // 高度感（耳廓共振 + 双耳权重）0..1



    float focus     = 1.0f;   // 中置聚焦（M 增益）



    float hfTilt    = 1.0f;   // 高频带宽系数：蓝牙 SBC/AAC 的高频已被编码砍掉，



                              // 环绕线索再往上撑只会变脏，按 codec 收敛高频反射量。



};







class DtsVirtualSurround {



public:



    static constexpr int MAX_DELAY = 4096;   // 48k 下 85ms：够 26ms 抽头 + ITD







    static constexpr float X_LO_HZ   = 250.0f;    // bass | mid 分频



    static constexpr float X_HI_HZ   = 3500.0f;   // mid | high 分频







    // 早期反射抽头：不等间距 + 增益递减 + 越晚越闷。



    // 四个抽头之和刻意压在 ~1.3（≈ -1.6dB）：真实房间的后墙反射比直达声低 10~20dB，



    // 之和给到 2.34 的话，乘上环绕强度后反射会盖过直达，听感变成"加了个混响"而不是



    // "被包围"，而且很容易顶到后面那个 look-ahead limiter，反而把动态吃掉。



    static constexpr float TAP_MS[4]    = { 7.0f, 11.0f, 17.0f, 26.0f };



    static constexpr float TAP_GAIN[4]  = { 0.55f, 0.38f, 0.24f, 0.15f };



    static constexpr float TAP_LP_HZ[4] = { 9000.0f, 6200.0f, 4000.0f, 2600.0f };







    static constexpr float ITD_MS      = 0.85f;   // 左右耳时间差（侧方/后方线索）



    static constexpr float SHADOW_HZ   = 3500.0f; // 头影：对侧耳高频衰减点



    static constexpr float PINNA_DB    = 5.0f;    // 耳廓共振深度



    static constexpr float PINNA_HZ_L  = 4200.0f; // 左右耳廓略不对称，本身就是线索



    static constexpr float PINNA_HZ_R  = 4900.0f;



    static constexpr float HAAS_MS     = 0.80f;   // 中置 HAAS 延迟







    float bufW[MAX_DELAY] = {0};   // 分段宽度后的 side（参与"宽度"）



    float bufA[MAX_DELAY] = {0};   // 环绕专用 side（已去低频）



    float bufM[MAX_DELAY] = {0};   // mid（给 HAAS 中置强化）



    int writePos = 0;







    int itdSamples = 40;



    int haasSamples = 38;



    int tapDelay[4] = { -1, -1, -1, -1 };







    // 分频状态（LP/HP 共用同一个 state，保证 LP + HP 恒等于原信号）



    float loState = 0.0f, hiState = 0.0f, aState = 0.0f, haasState = 0.0f;



    float loCoeff = 0.0f, hiCoeff = 0.0f, aCoeff = 0.0f, haasCoeff = 0.0f;



    // 头影（ILD）：一阶低通，对侧耳高频衰减



    float shLState = 0.0f, shRState = 0.0f, shCoeff = 0.0f;







    BiquadFilter tapLp[4];      // 各抽头的环绕吸收



    BiquadFilter pinnaL, pinnaR; // 耳廓共振（高度感）







    /** 一阶低通系数：y += c*(x-y)，c = tan(pi*fc/sr)/(1+tan(pi*fc/sr)) */



    static float onePoleCoeff(float sr, float fc) {



        double w = 2.0 * M_PI * (double)fc / (double)sr;



        double t = tan(w * 0.5);



        return (float)(t / (1.0 + t));



    }







    void setSampleRate(float sr) {



        loCoeff   = onePoleCoeff(sr, X_LO_HZ);



        hiCoeff   = onePoleCoeff(sr, X_HI_HZ);



        aCoeff    = onePoleCoeff(sr, X_HI_HZ);



        shCoeff   = onePoleCoeff(sr, SHADOW_HZ);



        haasCoeff = onePoleCoeff(sr, 2000.0f);   // HAAS 后微低通，防梳状



        for (int k = 0; k < 4; k++) {



            tapDelay[k] = (int)(TAP_MS[k] * 0.001f * sr + 0.5f);



            if (tapDelay[k] < 1) tapDelay[k] = 1;



            if (tapDelay[k] >= MAX_DELAY) tapDelay[k] = MAX_DELAY - 1;



            tapLp[k].setLowPass(sr, TAP_LP_HZ[k], 0.707f);



        }



        itdSamples = (int)(ITD_MS * 0.001f * sr + 0.5f);



        if (itdSamples < 1) itdSamples = 1;



        if (itdSamples >= MAX_DELAY) itdSamples = MAX_DELAY - 1;



        haasSamples = (int)(HAAS_MS * 0.001f * sr + 0.5f);



        if (haasSamples < 1) haasSamples = 1;



        if (haasSamples >= MAX_DELAY) haasSamples = MAX_DELAY - 1;



        pinnaL.setPeaking(sr, PINNA_HZ_L, PINNA_DB, 1.1f);



        pinnaR.setPeaking(sr, PINNA_HZ_R, PINNA_DB, 1.1f);



    }







    void reset() {



        memset(bufW, 0, sizeof(bufW));



        memset(bufA, 0, sizeof(bufA));



        memset(bufM, 0, sizeof(bufM));



        writePos = 0;



        loState = hiState = aState = haasState = 0.0f;



        shLState = shRState = 0.0f;



        for (int k = 0; k < 4; k++) tapLp[k].reset();



        pinnaL.reset(); pinnaR.reset();



    }







    inline float readBuf(const float* buf, int back) const {



        int p = writePos - back;



        if (p < 0) p += MAX_DELAY;



        return buf[p];



    }







    // mid/side 为输入分解；cfg 为当前渲染参数；outL/outR 为渲染结果。



    void process(float mid, float side, const DtsStageCfg& cfg, float& outL, float& outR) {



        // ── A) 分段宽度：三个频段各乘各的增益 ──



        float lo = loState + loCoeff * (side - loState);



        loState = lo;                                   // <250Hz



        float hiLpV = hiState + hiCoeff * (side - hiState);



        hiState = hiLpV;



        float hi = side - hiLpV;                        // >3.5kHz



        float midBand = side - lo - hi;                 // 250Hz ~ 3.5kHz



        float sideW = lo * cfg.widthBass + midBand * cfg.widthMid + hi * cfg.widthHigh;







        // ── 环绕专用流：先去掉低频（虚拟环绕扩宽低频会让低音发虚/漂移）──



        float aLp = aState + aCoeff * (side - aState);



        aState = aLp;



        float ambSrc = side - aLp;







        // ── 环形写入 ──



        bufW[writePos] = sideW;



        bufA[writePos] = ambSrc;



        bufM[writePos] = mid;



        writePos = (writePos + 1) % MAX_DELAY;







        // ── B) 四抽头早期反射（后墙）：不等间距 ≠ 梳状滤波器 ──



        //      后两个抽头落在 4k/2.6k 以下，是最容易被蓝牙 codec 削掉的那部分，



        //      所以按 hfTilt 收敛；codec 好（LDAC/LHDC）时 hfTilt=1，全量保留。



        float early = 0.0f;



        for (int k = 0; k < 4; k++) {



            float gk = TAP_GAIN[k] * (k >= 2 ? cfg.hfTilt : 1.0f);



            early += tapLp[k].process(readBuf(bufA, tapDelay[k])) * gk;



        }







        // ── C) HRTF 双耳渲染 ──



        //  右耳：对侧路径，经头影低通（ILD，高频被脑袋挡掉）后再过右耳廓共振



        float rawR = readBuf(bufA, itdSamples);



        shRState += shCoeff * (rawR - shRState);



        float earR = pinnaR.process(readBuf(bufA, itdSamples) - shRState);



        //  左耳：同侧路径，晚 itdSamples 到（ITD），过左耳廓共振后同样收头影



        float rawL = readBuf(bufA, 0);



        shLState += shCoeff * (rawL - shLState);



        float earL = pinnaL.process(readBuf(bufA, 0) - shLState);







        // 双耳权重随 height 变化：height=0 退化成单声道后墙（只有包络没有定位），



        // height=1 时两耳只拿 65% 的对侧能量，ILD 更明显 → 方位感更强。



        float wDirect = 0.35f + 0.65f * cfg.height;



        float wOppo   = 0.65f - 0.35f * cfg.height;







        // ── D) 合成：mid（含 HAAS 中置）+ 宽度 side + 双耳环绕 ──



        float haasRaw = readBuf(bufM, haasSamples);



        haasState += haasCoeff * (haasRaw - haasState);



        float focus = (cfg.focus - 1.0f) * 0.5f;



        if (focus < 0.0f) focus = 0.0f;



        if (focus > 0.5f) focus = 0.5f;







        float m = mid * cfg.focus;



        float rearL = early + earL * wDirect + earR * wOppo;



        float rearR = early + earR * wDirect + earL * wOppo;



        float su = cfg.surround;



        outL = m + sideW + rearL * su + haasState * focus;



        outR = m - sideW - rearR * su + haasState * focus;



        // 【V8.175】环绕/宽度回灌会让峰值抬 1~3dB，以前全部丢给后面的限幅器，



        // 大动态素材（EDM）上就是持续的 gain reduction → 鼓点一记一记被压。



        // 这里按环绕强度给一个固定 trim，把"环绕自己加进来的能量"扣回去，



        // 让整段 M/S 渲染近似透明：开环绕不再等于凭空变响。



        if (su > 0.0f) {



            const float trim = 1.0f / (1.0f + 0.35f * su);



            outL *= trim;



            outR *= trim;



        }



    }



};



static DtsVirtualSurround g_dts;



// 【V8.172】环绕强度 / 高度感 / 蓝牙高频带宽系数三个独立旋钮，



// 以及一份每帧复用、避免热路径构造的 cfg 快照。



static std::atomic<float> g_dtsSurround{0.35f};   // 环绕包围强度 0..0.8



static std::atomic<float> g_dtsHeight{0.50f};     // 高度/耳廓感 0..1



static std::atomic<float> g_dtsHfTilt{1.00f};     // 蓝牙 codec 高频带宽系数 0.4..1.0



static DtsStageCfg g_dtsCfg;







// ============================================================================



// Crossfeed — 真算法（Bauer 式，仅 Oboe 路径，DAC 保持 bit-perfect 直通）



// 对侧串扰：L 混入少量经"延迟 + 低通"的 R（模拟头部遮挡与耳间路径差 ITD），



// 反之亦然。一阶低通 ~700Hz（头部遮挡高频）、ITD ~300μs（头宽路径差）。



// 效果 = 声像收拢、变窄、久听不累（消除头中效应）。amount 0~1 映射串扰 0.2~0.5。



// ============================================================================



class Crossfeed {



public:



    static constexpr int MAX_DELAY = 512;        // 48kHz 下 512 samples ≈ 10.7ms



    static constexpr float ITD_S = 0.0003f;      // 耳间时间差 300μs



    static constexpr float LP_HZ = 700.0f;       // 头部遮挡低通



    static constexpr float XTALK_MIN = 0.2f;     // amount=0 时串扰量



    static constexpr float XTALK_MAX = 0.5f;     // amount=1 时串扰量







    float histL[MAX_DELAY] = {0};   // 左声道历史（供右耳取对侧）



    float histR[MAX_DELAY] = {0};   // 右声道历史



    int writePos = 0;



    int delayItd = 14;              // 300μs @48k



    float lpCoeff = 0.09f;



    float lpL = 0, lpR = 0;         // 串扰低通状态







    void setSampleRate(float sr) {



        delayItd = (int)(ITD_S * sr + 0.5f);



        if (delayItd < 1) delayItd = 1;



        if (delayItd > MAX_DELAY) delayItd = MAX_DELAY;



        lpCoeff = 1.0f - expf(-2.0f * 3.14159265f * LP_HZ / sr);



    }







    void reset() {



        memset(histL, 0, sizeof(histL));



        memset(histR, 0, sizeof(histR));



        writePos = 0;



        lpL = 0; lpR = 0;



    }







    // 处理一对样本，amount 0~1（串扰强度）。本侧直通 + 对侧(延迟+低通)×xtalk，



    // 再除以 (1+xtalk) 做电平归一化，避免整体变响。



    void process(float &sL, float &sR, float amount) {



        float inL = sL, inR = sR;







        histL[writePos] = inL;



        histR[writePos] = inR;



        int rp = writePos - delayItd;



        if (rp < 0) rp += MAX_DELAY;







        // 对侧信号经一阶低通（头部高频遮挡）



        lpL += lpCoeff * (histL[rp] - lpL);



        lpR += lpCoeff * (histR[rp] - lpR);







        float xtalk = XTALK_MIN + amount * (XTALK_MAX - XTALK_MIN);







        // 左耳 = 直通 L + 串入的 R；右耳对称



        sL = (inL + lpR * xtalk) / (1.0f + xtalk);



        sR = (inR + lpL * xtalk) / (1.0f + xtalk);







        writePos = (writePos + 1) % MAX_DELAY;



    }



};



static Crossfeed g_crossfeed;







// ============================================================================



// Headroom AGC — Automatic Gain Control



// ============================================================================



class HeadroomAGC {



public:



    static constexpr int RMS_HISTORY_SIZE = 480;



    float rmsHistory[RMS_HISTORY_SIZE] = {0};



    int rmsWritePos = 0;



    float currentRms = 0.0f;



    // 【V8.213 AGC 音乐化】targetRms 是均方（mean-square）量纲，targetDb=-16 经 pow(10,db/10)



    // 得到 0.025。−16 dBFS 是音乐 RMS 的中枢，gain 中枢≈1.0 —— 不再把整首歌恒定往下压（旧值 0.05 太低，



    // 等于语音 −13 dBFS 标准，套在音乐上必然整体压低 + 动态被压扁 = 闷）。



    float targetRms = 0.025f;



    float agcGain = 1.0f;



    // release τ≈3.6s（旧 0.9995≈21s 太慢，弱拍段增益缓慢爬升 = “一会响”的拖尾）；



    // attack τ≈0.75s（旧 0.995≈2s），强拍来时压峰更跟手。



    float releaseCoeff = 0.997f;



    float attackCoeff = 0.99f;



    bool enabled = false;







    void setSampleRate(int32_t) {



        rmsWritePos = 0;



        currentRms = 0.0f;



        agcGain = 1.0f;



    }







    inline float updateAndGetGainFromEnergy(float frameEnergy, int samples) {



        if (!enabled || samples == 0) return 1.0f;



        float frameRms = frameEnergy / std::max(samples, 1);



        currentRms = currentRms * 0.99f + frameRms * 0.01f;



        if (currentRms < 1e-8f) currentRms = 1e-8f;



        float desiredGain = std::sqrt(targetRms / currentRms);



        // 【V8.213】增益范围收紧到 ±1.5 dB。旧 [0.5, 2.0] 是 ±6 dB，强拍被压、弱拍被抬高到 2× = 经典 pump。



        // 收紧后 AGC 只制服极端峰值、保留音乐动态，不再“一会响一会轻”。



        desiredGain = std::min(1.18f, std::max(0.85f, desiredGain));



        if (desiredGain < agcGain) {



            agcGain = agcGain * attackCoeff + desiredGain * (1.0f - attackCoeff);



        } else {



            agcGain = agcGain * releaseCoeff + desiredGain * (1.0f - releaseCoeff);



        }



        return agcGain;



    }







    // 【V8.212 AGC 修正②】用「整帧功率」更新，一个 callback 内测量值恒定。



    // 原写法是边累加边用：本 callback 第 1 个样本只拿 1~2 个样本估能量，



    // 增益从一个乱跳的值一路爬到正确值 —— 等于每 5~10ms 被强塞一条增益斜坡（呼吸感/音染）。



    // 这里仍然逐样本调用，0.99/0.01 的平滑时间常数与原来完全一致，只是测量值不再半生不熟。



    inline float updateAndGetGainFromPower(float framePower) {



        if (!enabled) return 1.0f;



        return updateAndGetGainFromEnergy(framePower, 1);



    }







    void reset() {



        memset(rmsHistory, 0, sizeof(rmsHistory));



        rmsWritePos = 0;



        currentRms = 0.0f;



        agcGain = 1.0f;



    }



};



static HeadroomAGC g_agc;



static std::atomic<bool> g_agcEnabled{false};



// 【V8.213】与 targetRms=0.025 对应的 −16 dBFS 保持一致（nativeSetAgcTarget 目前未被 Java 调用，



// 此值仅供诊断页显示，旧的 −26 会误导）。



static std::atomic<float> g_agcTargetDb{-16.0f};



// 【V8.212 AGC 修正②】上一个 callback 的完整帧功率（AGC 测量用，实时线程独占）。



// -1 = 还没攒出来（首个 callback 用本帧运行均值兜底）。



static float g_agcPrevFramePower = -1.0f;



// 【V8.213 诊断】audio callback 计数器，每 200 个采样一次 AGC 增益（≈2s/次）。



static std::atomic<int> g_agcSampleCounter{0};







// ============================================================================



// Master Bus Compressor — 动态压缩（向下压缩，stereo-linked，dB 域峰值检测 + 软拐点）



// 独立全局模块，作用在瞬态整形之后、Look-Ahead Limiter 之前。



// stereo-linked：用 L/R 较大峰值驱动同一个增益衰减，保持立体声场不漂移。



// ============================================================================



class MasterCompressor {



public:



    bool enabled = false;



    float thresholdDb = -18.0f;   // 阈值（dBFS）



    float ratio = 2.0f;           // 压缩比（1=直通，越大压越狠）



    float attackMs = 10.0f;       // 启动时间



    float releaseMs = 120.0f;     // 释放时间



    float makeupDb = 0.0f;        // 补偿增益



    float kneeDb = 6.0f;          // 软拐点宽度







    float envDb = -120.0f;        // 平滑峰值包络（dB）



    float grDb = 0.0f;            // 当前增益衰减（dB，平滑用）



    float grLinear = 1.0f;        // 平滑后的线性增益



    float attackCoeff = 0.0f;     // exp 平滑系数



    float releaseCoeff = 0.0f;



    float makeupLinear = 1.0f;







    void setSampleRate(int32_t sr) {



        attackCoeff = expf(-1.0f / (std::max(sr, 1) * attackMs / 1000.0f));



        releaseCoeff = expf(-1.0f / (std::max(sr, 1) * releaseMs / 1000.0f));



        makeupLinear = powf(10.0f, makeupDb / 20.0f);



    }







    void configure(float thrDb, float rat, float atkMs, float relMs, float mkDb) {



        thresholdDb = thrDb;



        ratio = rat < 1.0f ? 1.0f : rat;



        attackMs = atkMs < 0.1f ? 0.1f : atkMs;



        releaseMs = relMs < 1.0f ? 1.0f : relMs;



        makeupDb = mkDb;



        makeupLinear = powf(10.0f, makeupDb / 20.0f);



    }







    // 平滑增益衰减（dB 域一阶低通）



    // 注意：grDb 为负值（压缩=负增益）。targetGr 更负（压更多）→ attack 快；



    // targetGr 更接近 0（松）→ release 慢。此前判断写反导致启动慢/释放快（pumping）。



    inline float smoothGr(float targetGr) {



        if (targetGr < grDb) {



            // 压更多 → attack 快



            grDb = grDb + (1.0f - attackCoeff) * (targetGr - grDb);



        } else {



            // 松 → release 慢



            grDb = grDb + (1.0f - releaseCoeff) * (targetGr - grDb);



        }



        return grDb;



    }







    inline void process(float &sL, float &sR) {



        if (!enabled) return;



        // stereo-linked 峰值检测



        float peak = fmaxf(fabsf(sL), fabsf(sR));



        float peakDb = 20.0f * log10f(peak + 1e-12f);



        // 峰值包络跟随



        if (peakDb > envDb) {



            envDb = envDb + (1.0f - attackCoeff) * (peakDb - envDb);



        } else {



            envDb = envDb + (1.0f - releaseCoeff) * (peakDb - envDb);



        }



        // 计算目标增益衰减（软拐点）



        float over = envDb - thresholdDb;



        float targetGr;



        float halfKnee = kneeDb * 0.5f;



        float slope = 1.0f - 1.0f / ratio;



        if (over <= -halfKnee) {



            targetGr = 0.0f;



        } else if (over >= halfKnee) {



            targetGr = over * slope;



        } else {



            // 二次软拐点：在 [-knee/2, +knee/2] 平滑过渡



            float t = over + halfKnee;



            targetGr = (t * t) / (2.0f * kneeDb) * slope;



        }



        float gr = smoothGr(targetGr);



        grLinear = powf(10.0f, -gr / 20.0f);



        float g = grLinear * makeupLinear;



        sL *= g;



        sR *= g;



    }







    void reset() {



        envDb = -120.0f;



        grDb = 0.0f;



        grLinear = 1.0f;



    }



};



static MasterCompressor g_compressor;



static std::atomic<bool> g_compressorEnabled{false};



static std::atomic<float> g_compressorThresholdDb{-18.0f};



static std::atomic<float> g_compressorRatio{2.0f};



static std::atomic<float> g_compressorAttackMs{10.0f};



static std::atomic<float> g_compressorReleaseMs{120.0f};



static std::atomic<float> g_compressorMakeupDb{0.0f};







// ============================================================================



// 【V8.170】ProceduralSpaceReverb — 程序化空间混响（Schroeder/FDN 混合）



//



// 动机：MSEB 的 "Orchestral Wide" / "Live Soundstage" 两个预设过去只能靠



// 14.6 kHz 的增益去"糊"出开阔感，并没有真正的后期混响。这里补一条真实的



// 空间混响分支：6 条梳状延迟（离散早期反射）+ 阻尼低通 + 4 段全通（扩散尾部），



// L/R 用互质延迟长度做去相关，得到立体声宽度而非单声道假宽。



//



// roomSize : 0..1 → RT60 0.4s(近场小房间) .. 3.2s(教堂)



// damping  : 0..1 → 高频吸收（越大越暗、越"闷"的小房间）



// wet      : 0..1 → 干湿比（wet=0 完全旁路，与原声逐样本一致）



//



// 采用 feedback-delay 结构而非 FFT 分区卷积：CPU 恒定、无工期风险、无



// partitioned-FFT 的内存分配。听感上属于"真实房间尾音"，不是人工混响器味。



// ============================================================================



class ProceduralSpaceReverb {



public:



    static constexpr int MAX_COMB = 6;



    static constexpr int MAX_AP   = 4;



    static constexpr int MAX_D    = 2048;   // 42.6ms @48k，覆盖最长延迟线







    float comb[MAX_COMB][MAX_D];



    int   combLen[MAX_COMB];



    float dampState[MAX_COMB][2];



    float apBuf[MAX_AP][MAX_D];



    int   apLen[MAX_AP];







    int   pos[MAX_COMB][2];   // 环形写指针 [comb][channel]



    int   apPos[MAX_AP][2];



    float apG = 0.7f;







    bool  configured = false;



    int   chOff = 11;              // L/R 去相关偏移（采样）



    float sr = 48000.0f;



    float roomSize = 0.5f;



    float damping = 0.5f;



    float wet = 0.0f;







    void configure(float sampleRate, float room, float damp) {



        sr = sampleRate > 0 ? sampleRate : 48000.0f;



        roomSize = room < 0.0f ? 0.0f : (room > 1.0f ? 1.0f : room);



        damping  = damp < 0.0f ? 0.0f : (damp > 1.0f ? 1.0f : damp);



        if (!configured) {



            memset(comb, 0, sizeof(comb));



            memset(dampState, 0, sizeof(dampState));



            memset(apBuf, 0, sizeof(apBuf));



            for (int i = 0; i < MAX_COMB; i++) pos[i][0] = pos[i][1] = 0;



            for (int i = 0; i < MAX_AP; i++)   apPos[i][0] = apPos[i][1] = 0;



            configured = true;



        }



        // 基准延迟（毫秒，互质避免梳状共振叠加成金属味）



        static const float baseMs[MAX_COMB] = { 29.7f, 37.1f, 41.1f, 43.7f, 53.1f, 61.7f };



        // RT60：房间尺寸主导衰减时间



        float rt60 = 0.4f + 2.8f * roomSize;



        // 阻尼 → 梳状反馈环内一阶低通系数（a 越小越暗）



        // 【V8.180 修复】a 原先只算给局部变量，成员 a_coef 永远停在构造初值 0.5，



        // 于是 UI 上的「混响阻尼/亮度」滑块拖了完全没反应。这里真正写回成员。



        float a = 0.15f + 0.80f * (1.0f - damping);



        a_coef = a;



        for (int i = 0; i < MAX_COMB; i++) {



            float ms = baseMs[i] * (0.45f + 0.55f * roomSize);



            int n = (int)(ms * 0.001f * sr);



            if (n < 40) n = 40;



            if (n > MAX_D - 2) n = MAX_D - 2;



            combLen[i] = n;



            // 反馈量：g = 10^(-3 * T_delay / RT60)（RT60 定义：衰减 60dB）



            float g = powf(10.0f, -3.0f * ((float)n / sr) / rt60);



            if (g > 0.985f) g = 0.985f;



            combG[i] = g;



        }



        float apMs = 5.0f + 6.0f * roomSize;



        int apn = (int)(apMs * 0.001f * sr);



        if (apn < 20) apn = 20;



        if (apn > MAX_D - 2) apn = MAX_D - 2;



        for (int i = 0; i < MAX_AP; i++) apLen[i] = apn + i * 3;



        apG = 0.62f + 0.24f * (1.0f - damping);



        chOff = (int)(0.0013f * sr);   // ~1.3ms 右声道偏移 → L/R 去相关



        if (chOff < 1) chOff = 1;



        if (chOff > 63) chOff = 63;



    }







    float combG[MAX_COMB];







    /** 梳状延迟一步：读 → 阻尼低通 → 写回（带反馈）。返回该梳的输出（阻尼后）。



     *  右声道读取位置偏移 chOff 采样，使 L/R 走不同的早期反射序列 → 真去相关。 */



    inline float combStep(int i, float in, int ch) {



        const int len = combLen[i];



        int p = pos[i][ch];



        int rp = (p + (ch ? chOff : 0)) % len;



        float y = line0(i, rp);



        float d = dampState[i][ch];



        d += a_coef * (y - d);



        dampState[i][ch] = d;



        wline(i, rp, in + combG[i] * d);



        int np = p + 1;



        if (np >= len) np = 0;



        pos[i][ch] = np;



        return d;



    }



    inline float line0(int i, int p) { return comb[i][p]; }



    inline void  wline(int i, int p, float v) { comb[i][p] = v; }







    inline float apStep(int i, float x, int ch) {



        int p = apPos[i][ch];



        int rp = (p + (ch ? chOff : 0)) % apLen[i];



        float bufv = apBuf[i][rp];



        float y = -apG * x + bufv;



        apBuf[i][rp] = x + apG * y;



        int np = p + 1;



        if (np >= apLen[i]) np = 0;



        apPos[i][ch] = np;



        return y;



    }







    float a_coef = 0.5f;







    void process(float& inL, float& inR, float wetAmount) {



        wet = wetAmount < 0.0f ? 0.0f : (wetAmount > 1.0f ? 1.0f : wetAmount);



        if (wet <= 0.0005f) return;



        if (!configured) configure(48000.0f, 0.5f, 0.5f);



        // 预延时 10ms：补偿梳状群延迟，保证 wet=1 时瞬态不被抹平



        static float pdL[1024], pdR[1024];



        static int pdPos = 0;



        int dn = (int)(0.010f * sr);



        if (dn < 2) dn = 2;



        if (dn > 1023) dn = 1023;



        pdL[pdPos] = inL; pdR[pdPos] = inR;



        pdPos = (pdPos + 1) % dn;



        float xL = pdL[pdPos], xR = pdR[pdPos];







        float accL = 0.f, accR = 0.f;



        for (int i = 0; i < MAX_COMB; i++) {



            // [V8.173] 右声道同一条梳用不同插入点做去相关，避免单声道假宽



            accL += combStep(i, xL, 0);



            accR += combStep(i, xR, 1);



        }



        float w1L = accL * (1.0f / MAX_COMB), w1R = accR * (1.0f / MAX_COMB);



        for (int i = 0; i < MAX_AP; i++) {



            w1L = apStep(i, w1L, 0);



            w1R = apStep(i, w1R, 1);



        }



        // 立体声宽度不随湿声比例塌陷：湿声分量做 ±3dB 的 L/R 反相微扰



        float wetL = (w1L + w1R) * 0.5f + (w1L - w1R) * 0.15f;



        float wetR = (w1L + w1R) * 0.5f - (w1L - w1R) * 0.15f;



        inL = inL * (1.0f - wet) + wetL * wet;



        inR = inR * (1.0f - wet) + wetR * wet;



    }







    void reset() {



        memset(comb, 0, sizeof(comb));



        memset(dampState, 0, sizeof(dampState));



        memset(apBuf, 0, sizeof(apBuf));



        for (int i = 0; i < MAX_COMB; i++) pos[i][0] = pos[i][1] = 0;



        for (int i = 0; i < MAX_AP; i++) apPos[i][0] = apPos[i][1] = 0;



        configured = false;



    }



};



static ProceduralSpaceReverb g_spaceReverb;



// 【V8.281】过渡模板专用混响实例：与总线那套完全分离，



// 否则 dissolve wash 会和常驻混响抢同一组梳状/全通状态，两边都变味。



static ProceduralSpaceReverb g_tplReverb;



static std::atomic<bool> g_spaceReverbEnabled{false};



static std::atomic<float> g_spaceReverbWet{0.0f};



static std::atomic<float> g_spaceReverbRoom{0.5f};



static std::atomic<float> g_spaceReverbDamp{0.5f};







// ============================================================================



// 【V8.170】DynamicEq — 动态 EQ（逐频段压缩，解决"整条压缩把低频嗡和人声一起压"）



//



// 4 频段（低 <150Hz / 低中 <2kHz / 高中 <6kHz / 高 ≥6kHz），LR4 交叉（每频点



// 串两个 Butterworth 二阶 = -6dB 落点）。每频段独立包络跟随 → 阈值之上按



// depth 比例衰减 → 求和。无过冲时所有 g=1，输出与输入逐样本相同（真旁路）。



// ============================================================================



class DynamicEq {



public:



    static constexpr int NB = 4;   // 0~150 / 150~2k / 2k~6k / 6k~



    BiquadFilter lpL1, lpL2, bpL1, bpL2, hpL1, hpL2;



    BiquadFilter lpR1, lpR2, bpR1, bpR2, hpR1, hpR2;



    float env[NB][2];



    float atk = 0.02f, rel = 0.25f;



    float depth = 0.0f;



    float thresh = 0.25f;



    float sr = 48000;







    void setSampleRate(int32_t sampleRate) {



        sr = sampleRate > 0 ? (float)sampleRate : 48000.0f;



        lpL1.setLowPass(sr, 150.0f, 0.7071f);   lpL2.setLowPass(sr, 150.0f, 0.7071f);



        bpL1.setLowPass(sr, 2000.0f, 0.7071f);  bpL2.setLowPass(sr, 2000.0f, 0.7071f);



        hpL1.setHighPass(sr, 6000.0f); hpL2.setHighPass(sr, 6000.0f);



        lpR1.setLowPass(sr, 150.0f, 0.7071f);   lpR2.setLowPass(sr, 150.0f, 0.7071f);



        bpR1.setLowPass(sr, 2000.0f, 0.7071f);  bpR2.setLowPass(sr, 2000.0f, 0.7071f);



        hpR1.setHighPass(sr, 6000.0f); hpR2.setHighPass(sr, 6000.0f);



        const float ms = 1000.0f / sr;



        atk = 1.0f - expf(-ms / 0.020f);   // 20ms



        rel = 1.0f - expf(-ms / 0.250f);   // 250ms



    }







    inline void process(float& inL, float& inR, float depthAmount) {



        depth = depthAmount;



        if (depth <= 0.001f) return;







        float xL = inL, xR = inR;



        float loL = lpL2.process(lpL1.process(xL));



        float hiL = bpL2.process(bpL1.process(xL));



        float hmL = xL - loL - hiL;



        float vL  = hpL2.process(hpL1.process(xL));



        float loR = lpR2.process(lpR1.process(xR));



        float hiR = bpR2.process(bpR1.process(xR));



        float hmR = xR - loR - hiR;



        float vR  = hpR2.process(hpR1.process(xR));







        float xsL[4] = { loL, hiL, hmL, vL };



        float xsR[4] = { loR, hiR, hmR, vR };



        float gL = 1.0f, gR = 1.0f;



        for (int b = 0; b < NB; b++) {



            float aL = fabsf(xsL[b]);



            env[b][0] += (aL > env[b][0]) ? atk * (aL - env[b][0]) : rel * (aL - env[b][0]);



            float aR = fabsf(xsR[b]);



            env[b][1] += (aR > env[b][1]) ? atk * (aR - env[b][1]) : rel * (aR - env[b][1]);



            float oL = env[b][0] - thresh;



            float oR = env[b][1] - thresh;



            float rL = oL > 0.0f ? (oL < (thresh + 0.35f) ? oL / (thresh + 0.35f) : 1.0f) : 0.0f;



            float rR = oR > 0.0f ? (oR < (thresh + 0.35f) ? oR / (thresh + 0.35f) : 1.0f) : 0.0f;



            gL *= 1.0f - depth * rL;



            gR *= 1.0f - depth * rR;



        }



        inL = xL * gL;



        inR = xR * gR;



    }



    void reset() { for (int i = 0; i < NB; i++) { env[i][0] = 0; env[i][1] = 0; } }



};



static DynamicEq g_dynEq;



static std::atomic<bool> g_dynEqEnabled{false};



static std::atomic<float> g_dynEqDepth{0.0f};



static std::atomic<float> g_dynEqThresh{0.25f};







// ============================================================================



// 【V8.170】MultiBandLimiter — 多频段限幅（替代/前置宽带 look-ahead limiter）



//



// 宽带限幅的问题：低频被抬 +8dB 时，整条链的峰值已经顶到 ceiling，limiter 会把



// 中高频一起压下去 —— 听感是"低频一下子、人声跟着变小"。这里按 3 段



// （低 <200Hz / 中 <2.5kHz / 高）分别限幅，互不影响。



// 复用 look-ahead 延迟线结构，接口与 LookAheadLimiter 对齐。



// ============================================================================



// 【V8.175】前置声明：processChannel 里要按这个开关决定是否施加逐频段增益



static std::atomic<bool> g_multiBandLimiterEnabled{true};







class MultiBandLimiter {



public:



    static constexpr int MAX_SAMPLES = 480;    static constexpr float LOOKAHEAD_MS = 5.0f;



    static constexpr int NB = 3;   // 低 <200Hz / 中 <2.5kHz / 高



    float delayBufferL[MAX_SAMPLES] = {0};



    float delayBufferR[MAX_SAMPLES] = {0};



    int writePos = 0;



    int delayLen = 240;



    float grL[NB], grR[NB];   // 每频段独立增益衰减



    BiquadFilter bLowL, bLowR;          // 200 Hz 低通（分出低频）



    BiquadFilter bMidL, bMidR;          // 2.5 kHz 低通



    BiquadFilter bHiL,  bHiR;           // 2.5 kHz 高通



    float attackCoeff[NB]  = {0};       // 【V8.175】每频段独立时间常数



    float releaseCoeff[NB] = {0};



    float threshold = 0.92f;



    float sr = 48000;







    void setSampleRate(int32_t sampleRate) {



        sr = sampleRate > 0 ? (float)sampleRate : 48000.0f;



        delayLen = (int)(LOOKAHEAD_MS * sr / 1000.0f);



        if (delayLen > MAX_SAMPLES) delayLen = MAX_SAMPLES;



        if (delayLen < 1) delayLen = 1;



        // 【V8.175】老版是致命的两连错：



        //   1) release 写成 1 - 1/(0.100f*delayLen+1) —— 把"100ms"当成了"100*delayLen



        //      个采样"。48k 下 delayLen=240 → 释放时间常数 ≈ 24 个采样（0.5ms），本意



        //      是 100ms，差了 200 倍；



        //   2) 三个频段共用同一组（超快的）攻击/释放。



        //      结果：低频段（<200Hz，半个周期 2.5ms）的增益在一个鼓点波形内部就完成



        //      一整次"压下-弹回"，等于对 kick 做了幅度调制 —— EDM 上听感即



        //      "鼓点破音 + 毛刺"，人声里频段间的 GR 互调就是"电音感/杂音"。



        //      直通一开（整块 DSP 旁路）立刻干净，正好对上用户描述。



        // 每频段的攻击/释放都必须慢于该频段最低频率的半个周期：



        //   低(<200Hz)  半周期 2.5ms → 攻击 4ms / 释放 150ms



        //   中(<2.5k)   半周期 0.2ms → 攻击 1ms / 释放 80ms



        //   高(>2.5k)   周期很短     → 攻击 0.3ms / 释放 40ms



        // （攻击上限受 5ms look-ahead 约束，不能超过 delayLen，否则过冲。）



        const float atkMs[NB] = { 4.0f, 1.0f, 0.3f };



        const float relMs[NB] = { 150.0f, 80.0f, 40.0f };



        for (int b = 0; b < NB; b++) {



            attackCoeff[b]  = 1.0f - 1.0f / (atkMs[b] * 0.001f * sr + 1.0f);



            releaseCoeff[b] = 1.0f - 1.0f / (relMs[b] * 0.001f * sr + 1.0f);



        }



        bLowL.setLowPass(sr, 200.0f, 0.7071f);   bLowR.setLowPass(sr, 200.0f, 0.7071f);



        bMidL.setLowPass(sr, 2500.0f, 0.7071f);  bMidR.setLowPass(sr, 2500.0f, 0.7071f);



        bHiL.setHighPass(sr, 2500.0f);  bHiR.setHighPass(sr, 2500.0f);



    }







    void write(float sL, float sR) {



        delayBufferL[writePos] = sL;



        delayBufferR[writePos] = sR;



        writePos = (writePos + 1) % MAX_SAMPLES;



    }



    float readDelayed(bool rightChannel) {



        int rp = writePos - delayLen;



        if (rp < 0) rp += MAX_SAMPLES;



        return rightChannel ? delayBufferR[rp] : delayBufferL[rp];



    }







    /** 单声道、单帧：分 3 段各自限幅后合成。 */



    float processChannel(float x, bool right) {



        // 【V8.175】开关现在真的关得掉了。老版 g_mbLimiterEnabled 是个死变量：



        // 三个频段的增益衰减无条件乘上去，"多频段限幅"关了照样压 —— 用户以为



        // 全关了，链路里却常驻一个失控的逐频段压缩器。



        if (!g_multiBandLimiterEnabled.load(std::memory_order_relaxed)) return x;



        BiquadFilter& lowF  = right ? bLowR : bLowL;



        BiquadFilter& midF  = right ? bMidR : bMidL;



        BiquadFilter& hiF   = right ? bHiR  : bHiL;



        float* gr = right ? grR : grL;



        float low  = lowF.process(x);



        float mid  = midF.process(hiF.process(x)) - low;



        float high = x - low - mid;



        float xs[3] = { low, mid, high };



        for (int b = 0; b < NB; b++) {



            float peak = fabsf(xs[b]);



            float desired = (peak > threshold) ? threshold / peak : 1.0f;



            if (desired < gr[b]) gr[b] = attackCoeff[b]  * gr[b] + (1.0f - attackCoeff[b])  * desired;



            else                 gr[b] = releaseCoeff[b] * gr[b] + (1.0f - releaseCoeff[b]) * desired;



        }



        return x * (gr[0] * gr[1] * gr[2]);



    }







    void reset() {



        memset(delayBufferL, 0, sizeof(delayBufferL));



        memset(delayBufferR, 0, sizeof(delayBufferR));



        for (int i = 0; i < NB; i++) { grL[i] = 1.0f; grR[i] = 1.0f; }



        writePos = 0;



    }



};



static MultiBandLimiter g_mbLimiter;







// AutoEQ 修正强度混合（0..1，1=完整修正）。实现为"把修正量按强度缩放"，



// 曲线形状不变，等价于 AutoEQ 官方 half-shelf 混合的简化版。



static std::atomic<float> g_autoEqMix{1.0f};







// ============================================================================



// Loudness Comp — ISO 226 等响补偿（低音量时低频/高频自动提升）



// 作用在 EQ/masterGain 之后、M/S 之前（共享 phon 估算，stereo-linked）



// ============================================================================



static LoudnessComp g_loudness;



static std::atomic<bool> g_loudnessEnabled{false};



static std::atomic<float> g_loudnessIntensity{1.0f};







// ============================================================================



// Soft Clip — cubic sigmoid waveshaper



// Night mode: lower threshold for softer clipping



// ============================================================================



static inline float softClip(float x) {



    // 【V7.05】夜间模式：阈值从 2/3 降到 0.5



    // [V8.119] 宽裕模式把拐点从 2/3(-3.5dBFS) 提到 0.92(-0.7dBFS)：旧拐点会把



    // 每一记鼓点、每一个人声峰值都波形整形 —— 听感即「鼓点破音 + 人声电音感 + 发干」。



    float T = g_nightMode.load() ? 0.5f



            : (g_wideHeadroom.load() ? 0.92f : (2.0f / 3.0f));



    float RANGE = 1.0f - T;



    float MAKEUP = 1.0f;  // [V8.118] was 1.2 -> made softClip clip HARD after limiter (buzz on drums/bass)



    float ax = fabsf(x);



    if (ax <= T) {



        return x * MAKEUP;



    }



    float s = copysignf(1.0f, x);



    float over = ax - T;



    float norm = over / RANGE;



    if (norm >= 1.0f) {



        return s * MAKEUP;



    }



    float t = norm;



    float ct = t - (1.0f / 3.0f) * t * t * t;



    float out = (T + ct * RANGE) * MAKEUP;



    return s * out;



}







// ============================================================================



// DC Blocker — 5Hz high-pass filter (removes subsonic DC offset)



// ============================================================================



static inline float dcBlock(float input, float &x1, float &y1) {



    const float R = 0.995f;  // ~5Hz at 44.1kHz



    float output = input - x1 + R * y1;



    x1 = input;



    y1 = output;



    return output;



}



static float g_dcX1L = 0, g_dcY1L = 0;



static float g_dcX1R = 0, g_dcY1R = 0;







// ============================================================================



// 【2026-09-07】A2DP 编码前预补偿（Pre-emphasis for Bluetooth lossy codecs）



// 目标：SBC/AAC 有损编码对高频瞬态边缘的挤压。仅在蓝牙低码率编码时启用，



// 有线/DAC/LHDC 高码率路径旁路（codecDb=0）。



// 算法：5kHz 高通（一阶）提取瞬态成分 → 包络跟随器（attack≈2ms/release≈50ms）



//       → 瞬态处叠加 hp*env*boost。boost 由 codec 决定（SBC 2dB/AAC 0.8dB）。



// ============================================================================



static std::atomic<bool> g_btPreEnabled{false};      // 总开关（设置页）



static std::atomic<float> g_btPreCodecDb{0.0f};      // 当前 codec 增益 dB（0=旁路）



// 每声道瞬态包络状态（callback 线程专用，无需 atomic）



static float g_btPreEnvL = 0.0f, g_btPreEnvR = 0.0f;



static float g_btPreHpX1L = 0.0f, g_btPreHpX1R = 0.0f;



static float g_btPreHpY1L = 0.0f, g_btPreHpY1R = 0.0f;



static float g_btPreSampleRate = 48000.0f;  // 默认；open 时更新











static float g_btPreHpK = 0.0f;         // 缓存 HPF 系数



static float g_btPreAtk = 0.0f;         // 缓存 attack 系数



static float g_btPreRel = 0.0f;         // 缓存 release 系数



static void btPreUpdateCoeffs(float sr) {



    g_btPreSampleRate = sr;



    const float fc = 5000.0f;



    const float w = 2.0f * (float)M_PI * fc / sr;



    g_btPreHpK = w / (1.0f + w);



    g_btPreAtk = 1.0f - expf(-1.0f / (0.002f * sr));



    g_btPreRel = 1.0f - expf(-1.0f / (0.050f * sr));



}







// 每帧处理：返回 true 时 out 已被补偿



static inline bool btPreEmphasisFrame(float &l, float &r, bool stereo) {



    const float db = g_btPreCodecDb.load();



    if (!g_btPreEnabled.load() || db <= 0.0f) return false;



    float srNow = (float)g_sampleRate.load();



    if (srNow != g_btPreSampleRate) btPreUpdateCoeffs(srNow);



    // 5kHz 一阶高通系数 + 包络时间常数（缓存，采样率变化时由 open 更新）



    const float k = g_btPreHpK;



    const float atk = g_btPreAtk;



    const float rel = g_btPreRel;



    const float boost = powf(10.0f, db / 20.0f) - 1.0f;     // dB -> 线性增量







    // L



    float hpL = k * (l - g_btPreHpX1L) + (1.0f - k) * g_btPreHpY1L;



    g_btPreHpX1L = l; g_btPreHpY1L = hpL;



    float envL = fabsf(hpL) > g_btPreEnvL ? g_btPreEnvL + atk * (fabsf(hpL) - g_btPreEnvL)



                                          : g_btPreEnvL + rel * (fabsf(hpL) - g_btPreEnvL);



    g_btPreEnvL = envL;



    // [V8.118] bound the additive correction (was unbounded; extreme HF transients



    // could add far more than the nominal codec dB and pump the limiter)



    float corrL = hpL * envL * boost;



    if (corrL >  0.30f) corrL =  0.30f;



    if (corrL < -0.30f) corrL = -0.30f;



    l += corrL;







    if (stereo) {



        float hpR = k * (r - g_btPreHpX1R) + (1.0f - k) * g_btPreHpY1R;



        g_btPreHpX1R = r; g_btPreHpY1R = hpR;



        float envR = fabsf(hpR) > g_btPreEnvR ? g_btPreEnvR + atk * (fabsf(hpR) - g_btPreEnvR)



                                              : g_btPreEnvR + rel * (fabsf(hpR) - g_btPreEnvR);



        g_btPreEnvR = envR;



        // [V8.118] same bound as L



        float corrR = hpR * envR * boost;



        if (corrR >  0.30f) corrR =  0.30f;



        if (corrR < -0.30f) corrR = -0.30f;



        r += corrR;



    }



    return true;



}











// ============================================================================



// TPDF Dither — Triangular Probability Density Function dither



// Adds 2 LSB of triangular noise before quantization



// ============================================================================



static std::mt19937 g_ditherRng{42};



static std::uniform_real_distribution<float> g_ditherDist(-1.0f, 1.0f);







static inline float tpdfDither() {



    return (g_ditherDist(g_ditherRng) + g_ditherDist(g_ditherRng)) * (1.0f / 65536.0f);



}







// ============================================================================



// 【M3.2】DJ 音效包播放器（SFX Player）



// Kotlin 侧管框架（SfxPackManager + 设置页），native 侧负责：



//   1) 解码 wav（PCM16 / IEEE-float）成 float 缓冲，按触发点归类；



//   2) 触发瞬间（切歌开始/结束）把采样放进 voice 池；



//   3) 每个 callback 把激活 voice 渲染成「与 output 同布局」的混合层 g_sfxMix；



//   4) 该层在下方 DSP 链（含多段限幅）之前叠入，由下游限幅统一防爆音。



// 采样率任意：渲染时按 outRate/sampleRate 线性重采样，适配 44.1k/48k/...



// 线程模型：load 只在 JNI 线程；trigger 在 JNI 线程(切歌开始)或音频线程(切歌结束)；



// render 只在音频线程。voice 通过 active 原子 + 写序（先写样本/增益/位置，最后置 active）



// 实现无锁安全读取，避免音频线程取锁。



// ============================================================================



struct SfxLoaded {



    std::vector<float> data;   // 交织 float，channels 路



    int channels;              // 1 或 2



    int rate;                 // wav 采样率



    float gain;               // manifest 增益



};







// 【V8.245 SFX 语义修正】触发点拆分：



//   START = riser（仅「B 能量 > A」的爬升型过渡触发）



//   SWEEP = 白噪 sweep（仅 |ΔLUFS| ≤ 1.5dB 的平滑 blend 触发）



//   END   = vinyl brake（黑胶刹车：**只在手动切歌**触发，自动过渡完成不再出声）



//   MANUAL= 诊断/试听



enum SfxTrigId { SFX_T_START = 0, SFX_T_END = 1, SFX_T_MANUAL = 2, SFX_T_SWEEP = 3, SFX_T_COUNT = 4 };







static std::mutex g_sfxMutex;                                // 保护 g_sfxByTrig 的写（load/trigger）



static std::vector<SfxLoaded*> g_sfxByTrig[SFX_T_COUNT];     // 触发点 -> 已解码采样



static std::vector<SfxLoaded*> g_sfxGraveyard;               // 上一轮换包的旧采样：延迟到下一轮 load 才释放，



                                                             // 避免音频线程还有 voice 在渲染时 delete 造成 UAF



static std::atomic<bool> g_sfxHasAny{false};



static std::atomic<bool> g_sfxEnabled{false};   // 【V8.216】DJ 音效包总开关（设置页可控；默认关）







struct SfxVoice {



    SfxLoaded* sample;             // 指向已解码采样（load 后不可变）



    double pos;                    // 源采样浮点位置（重采样用）



    double rateMul{1.0};           // 【V8.245】额外播放速率倍率（小节量化对齐用），1.0=原速



    float gain;                    // voice 增益（= sample->gain）



    std::atomic<int> active{0};



    int fadeFrames;                // 已输出帧数（用于淡入防爆音）



};



static const int SFX_VOICE_N = 8;



static SfxVoice g_sfxVoice[SFX_VOICE_N];



static int g_sfxVoiceRR = 0;



// 【V8.217】每个触发点的变体轮换游标：切歌时按它挑一个变体，避免"永远只有一种声音"。



static int g_sfxVariantRR[SFX_T_COUNT] = { 0, 0, 0, 0 };



static float* g_sfxMix = nullptr;  // 每个 callback 的混合层（交织）



static int g_sfxMixCap = 0;        // 容量（样本数）







static int sfxTrigIdFromString(const char* name) {



    if (!name) return SFX_T_MANUAL;



    if (strcmp(name, "xfade_start") == 0) return SFX_T_START;



    if (strcmp(name, "riser") == 0)       return SFX_T_START;



    if (strcmp(name, "xfade_end") == 0)   return SFX_T_END;



    if (strcmp(name, "brake") == 0)       return SFX_T_END;



    if (strcmp(name, "sweep") == 0)       return SFX_T_SWEEP;



    return SFX_T_MANUAL;



}







// 极简 WAV 解码：支持 PCM16 / IEEE-float，多声道下混为单声道。返回 false = 失败。



static bool sfxDecodeWav(const char* path, SfxLoaded& out) {



    FILE* f = fopen(path, "rb");



    if (!f) return false;



    unsigned char hdr[12];



    if (fread(hdr, 1, 12, f) != 12 || memcmp(hdr, "RIFF", 4) != 0 || memcmp(hdr + 8, "WAVE", 4) != 0) {



        fclose(f); return false;



    }



    int fmtRate = 44100, fmtCh = 1, fmtBits = 16, audioFmt = 1;



    bool gotFmt = false;



    std::vector<unsigned char> dataBytes;



    for (;;) {



        unsigned char ck[8];



        if (fread(ck, 1, 8, f) != 8) break;



        unsigned int sz = (unsigned int)ck[4] | ((unsigned int)ck[5] << 8) |



                          ((unsigned int)ck[6] << 16) | ((unsigned int)ck[7] << 24);



        if (memcmp(ck, "fmt ", 4) == 0) {



            unsigned char fbuf[16];



            unsigned int n = sz < 16 ? sz : 16;



            if (fread(fbuf, 1, n, f) != (size_t)n) { fclose(f); return false; }



            audioFmt = (unsigned int)fbuf[0] | ((unsigned int)fbuf[1] << 8);



            fmtCh    = (unsigned int)fbuf[2] | ((unsigned int)fbuf[3] << 8);



            fmtRate  = (unsigned int)fbuf[4] | ((unsigned int)fbuf[5] << 8) |



                       ((unsigned int)fbuf[6] << 16) | ((unsigned int)fbuf[7] << 24);



            fmtBits  = (unsigned int)fbuf[14] | ((unsigned int)fbuf[15] << 8);



            if (sz > 16) fseek(f, (long)(sz - 16), SEEK_CUR);



            gotFmt = true;



        } else if (memcmp(ck, "data", 4) == 0) {



            dataBytes.resize(sz);



            if (sz > 0 && fread(dataBytes.data(), 1, sz, f) != (size_t)sz) { fclose(f); return false; }



            break;  // 取第一个 data 块即可



        } else {



            fseek(f, (long)sz, SEEK_CUR);



        }



    }



    fclose(f);



    if (!gotFmt || dataBytes.empty()) return false;







    const int inCh = fmtCh;



    const int outCh = (inCh == 2) ? 2 : 1;   // 双声道保留，其余下混单声道



    long totalInSamples = 0;



    if (audioFmt == 3) totalInSamples = (long)dataBytes.size() / (4 * inCh);   // IEEE float 32



    else               totalInSamples = (long)dataBytes.size() / (2 * inCh);   // PCM16（默认）



    if (totalInSamples <= 0) return false;







    out.data.resize((size_t)totalInSamples * outCh);



    out.channels = outCh;



    out.rate = fmtRate;



    out.gain = 1.0f;







    const unsigned char* p = dataBytes.data();



    for (long s = 0; s < totalInSamples; s++) {



        float l = 0.0f, r = 0.0f;



        if (audioFmt == 3) {



            const float* fp = (const float*)p;



            l = fp[0];



            if (inCh >= 2) r = fp[1];



            p += 4 * inCh;



        } else {



            const short* sp = (const short*)p;



            l = (float)sp[0] / 32768.0f;



            if (inCh >= 2) r = (float)sp[1] / 32768.0f;



            p += 2 * inCh;



        }



        if (outCh == 2) { out.data[(size_t)s * 2] = l; out.data[(size_t)s * 2 + 1] = r; }



        else { float m = (inCh >= 2) ? (l + r) * 0.5f : l; out.data[(size_t)s] = m; }  // 下混



    }



    return true;



}







// 装载一个包：清空旧采样，解码所有 wav 并按触发点归类。



// 全程持锁：与音频线程的 trigger（try_lock，不等待）和 JNI 手动触发互斥。



static void sfxLoadPack(JNIEnv* env, jobjectArray paths, jobjectArray triggers, jfloatArray gains) {



    std::lock_guard<std::mutex> lk(g_sfxMutex);



    // 1) 先停掉所有 voice（渲染线程走完当前块后即不再触碰），旧采样进墓地延迟释放：



    //    音频线程可能正拿着某个 SfxLoaded* 渲染到一半，立刻 delete 会 UAF。



    for (int v = 0; v < SFX_VOICE_N; v++) g_sfxVoice[v].active.store(0, std::memory_order_release);



    for (int t = 0; t < SFX_T_COUNT; t++) {



        for (auto* s : g_sfxByTrig[t]) g_sfxGraveyard.push_back(s);



        g_sfxByTrig[t].clear();



    }



    // 2) 释放「上一轮」墓地的采样：此时距它们被弃用至少隔了一整轮 load，



    //    对应 voice（0.5~1.2s 短音）早已渲染完毕。



    std::vector<SfxLoaded*> grave;



    grave.swap(g_sfxGraveyard);



    for (auto* s : grave) delete s;



    g_sfxHasAny.store(false, std::memory_order_release);







    bool any = false;



    if (paths && triggers) {



        jsize n = env->GetArrayLength(paths);



        jsize ng = gains ? env->GetArrayLength(gains) : 0;



        jfloat* gArr = gains ? env->GetFloatArrayElements(gains, nullptr) : nullptr;



        for (jsize i = 0; i < n; i++) {



            jstring jp = (jstring)env->GetObjectArrayElement(paths, i);



            jstring jt = (jstring)env->GetObjectArrayElement(triggers, i);



            if (!jp) { if (jt) env->DeleteLocalRef(jt); continue; }



            const char* cp = env->GetStringUTFChars(jp, nullptr);



            SfxLoaded* s = new SfxLoaded();



            if (sfxDecodeWav(cp, *s)) {



                s->gain = (i < ng && gArr) ? gArr[i] : 1.0f;



                int tid = SFX_T_MANUAL;



                if (jt) {



                    const char* ct = env->GetStringUTFChars(jt, nullptr);



                    tid = sfxTrigIdFromString(ct);



                    env->ReleaseStringUTFChars(jt, ct);



                }



                g_sfxByTrig[tid].push_back(s);



                any = true;



            } else {



                delete s;



                LOGW("SFX: failed to decode %s", cp ? cp : "(null)");



            }



            if (cp) env->ReleaseStringUTFChars(jp, cp);



            env->DeleteLocalRef(jp);



            if (jt) env->DeleteLocalRef(jt);



        }



        if (gArr) env->ReleaseFloatArrayElements(gains, gArr, JNI_ABORT);



    }



    // 换包后停掉所有正在播放的 voice，避免残留上一包的声音（上面已停，此处兜底置标志）



    g_sfxHasAny.store(any, std::memory_order_release);



    LOGI("SFX: loaded pack any=%d (riser=%d brake=%d sweep=%d manual=%d)", any,



         (int)g_sfxByTrig[SFX_T_START].size(),



         (int)g_sfxByTrig[SFX_T_END].size(),



         (int)g_sfxByTrig[SFX_T_SWEEP].size(),



         (int)g_sfxByTrig[SFX_T_MANUAL].size());



}







// 触发某触发点的所有采样（放进 voice 池，最多 SFX_VOICE_N 个并发）。



// 可能从音频线程调用（xfade_end 在 onAudioReady 内）——用 try_lock，



// load 进行中宁可丢弃这一次音效也绝不阻塞音频回调。



static void sfxTriggerByName(const char* name, double targetMs = 0.0) {



    if (!g_sfxEnabled.load(std::memory_order_relaxed)) return;  // 【V8.216】总开关关 → 不触发任何音效



    int tid = sfxTrigIdFromString(name);



    std::unique_lock<std::mutex> lk(g_sfxMutex, std::try_to_lock);



    if (!lk.owns_lock()) return;



    auto& list = g_sfxByTrig[tid];



    if (list.empty()) return;







    // 【V8.217】只取**一个**变体，且每次轮换。



    // 旧逻辑把该触发点下的采样**全部同时**播放（manifest 里 start 挂了 scratch+sweep



    // 两个），叠加起来正是"浑浊、像警报"的主因；而列表写死不变，也就是"永远只有一种



    // 声音"的来源。现在每次挑一个、按顺序轮换，切歌听感次次不同。



    int pick = g_sfxVariantRR[tid];



    if (pick < 0 || pick >= (int)list.size()) pick = 0;



    g_sfxVariantRR[tid] = (pick + 1) % (int)list.size();



    SfxLoaded* s = list[pick];



    if (!s) return;







    // 【V8.245 节奏量化】targetMs > 0 时把采样时长缩放到目标时长（= 过渡中点对齐到



    // A 轨 4/8 小节）：rateMul = 采样自身时长 / 目标时长，钳 [0.6, 1.6] 防音高畸变。



    // riser/sweep 是无音高语义的扫频，变速只轻微移调，可接受；brake 不传 targetMs（原速）。



    double rateMul = 1.0;



    if (targetMs > 50.0) {



        int ch = (s->channels == 2) ? 2 : 1;



        double srcMs = (double)(s->data.size() / ch) / (double)s->rate * 1000.0;



        if (srcMs > 50.0) {



            rateMul = srcMs / targetMs;



            if (rateMul < 0.6) rateMul = 0.6;



            if (rateMul > 1.6) rateMul = 1.6;



        }



    }







    int slot = -1;



    for (int k = 0; k < SFX_VOICE_N; k++) {



        int idx = (g_sfxVoiceRR + k) % SFX_VOICE_N;



        if (g_sfxVoice[idx].active.load(std::memory_order_relaxed) == 0) { slot = idx; break; }



    }



    if (slot < 0) slot = g_sfxVoiceRR;  // 全忙则覆盖最旧的



    g_sfxVoiceRR = (slot + 1) % SFX_VOICE_N;



    SfxVoice& v = g_sfxVoice[slot];



    // 先写样本/增益/位置/淡入计数，最后置 active=1（音频线程看到 active 后再读这些字段，无锁安全）



    v.sample = s;



    v.gain = s->gain;



    v.pos = 0.0;



    v.rateMul = rateMul;



    v.fadeFrames = 0;



    v.active.store(1, std::memory_order_release);



}







// 【V8.245 节奏量化】riser/sweep 的目标时长 = 过渡中点，再对齐到 A 轨 4 或 8 小节



// （1 小节 = 4 拍，取与中点更近者）。aBpm 无效（0 或越出 40..220）返回 0，



// sfxTriggerByName 只在 targetMs > 50 时才启用 rateMul，即无效 BPM → 原速播放。



static double sfxQuantTargetMs(int durationMs, float aBpm) {



    if (durationMs <= 0) return 0.0;



    if (aBpm < 40.0f || aBpm > 220.0f) return 0.0;



    const double barMs = 4.0 * 60000.0 / (double)aBpm;   // 1 小节 = 4 拍



    const double half = (double)durationMs * 0.5;



    const double t4 = 4.0 * barMs;



    const double t8 = 8.0 * barMs;



    return (std::fabs(half - t4) <= std::fabs(half - t8)) ? t4 : t8;



}







// 渲染一个 callback 的 SFX 混合层到 g_sfxMix（交织，totalSamples 个样本）。



// 返回本帧是否实际产出了声音（用于决定是否叠入，省去静音帧的加法）。



// 音频线程调用，不取锁（voice 通过 active 原子 + 写序保证安全）。



static bool sfxRenderBlock(int numFrames, int channels, int outRate) {



    const int totalSamples = numFrames * channels;



    if (totalSamples > g_sfxMixCap) {



        delete[] g_sfxMix;



        g_sfxMixCap = totalSamples > 16384 ? totalSamples : 16384;  // 极少扩容，之后稳定



        g_sfxMix = new float[g_sfxMixCap];



    }



    if (!g_sfxMix) return false;



    for (int i = 0; i < totalSamples; i++) g_sfxMix[i] = 0.0f;







    bool produced = false;



    const int fadeOutFrames = (int)(0.005 * (double)outRate);  // 5ms 淡出防爆音



    const int fadeInFrames = (int)(0.005 * (double)outRate);   // 5ms 淡入防爆音



    for (int v = 0; v < SFX_VOICE_N; v++) {



        SfxVoice& vc = g_sfxVoice[v];



        if (vc.active.load(std::memory_order_acquire) == 0) continue;



        SfxLoaded* s = vc.sample;



        if (!s || s->data.empty()) { vc.active.store(0, std::memory_order_relaxed); continue; }



        const double ratio = (double)outRate / (double)s->rate;



        const int ch = (s->channels == 2) ? 2 : 1;



        const long lenSamples = (long)(s->data.size() / ch);



        for (int f = 0; f < numFrames; f++) {



            double pos = vc.pos;



            if (pos >= (double)lenSamples) { vc.active.store(0, std::memory_order_relaxed); break; }



            long i0 = (long)pos;



            long i1 = i0 + 1; if (i1 >= lenSamples) i1 = lenSamples - 1;



            double frac = pos - (double)i0;



            float l0 = s->data[(size_t)i0 * ch];



            float l1 = s->data[(size_t)i1 * ch];



            float sl = l0 + (l1 - l0) * (float)frac;



            float sr = sl;



            if (ch == 2) {



                float r0 = s->data[(size_t)i0 * ch + 1];



                float r1 = s->data[(size_t)i1 * ch + 1];



                sr = r0 + (r1 - r0) * (float)frac;



            }



            // 淡入（前 5ms）与淡出（最后 5ms）防爆音



            float env = 1.0f;



            int ff = vc.fadeFrames;



            if (ff < fadeInFrames) env *= (float)ff / (float)fadeInFrames;



            long remain = lenSamples - i0;



            if (remain < fadeOutFrames) env *= (float)remain / (float)fadeOutFrames;



            if (env < 0.0f) env = 0.0f;



            float g = s->gain * env;



            int base = f * channels;



            g_sfxMix[base]     += sl * g;



            if (channels >= 2) g_sfxMix[base + 1] += sr * g;



            vc.pos += ratio * vc.rateMul;   // 【V8.245】叠加小节量化倍率



            vc.fadeFrames++;



            produced = true;



        }



    }



    return produced;



}







// ============================================================================







// ============================================================================



// OboeAudioCallback — Real-time audio output callback



// Reads from ring buffer → DSP processing → output



// ============================================================================







// 前向声明：onErrorAfterClose（类方法，见下）需要在本类定义之前引用此自由函数



static void startRouteRebindWatchdog();







// ============================================================================
// [V8.514] DSP node scaffolding — 从单体 DSP 大块抽出，提升可维护性，
// 对齐 RawS-Music 的 DspChain + 每节点 isActive() 短路思路。
// Phase 1 仅把两块最易错的逻辑抽成具名节点：
//   • MsebPreGainNode       — autoEq × mseb × dspEq 预增益（double 累积，防多 stage 单精度误差）
//   • TerminalLimiterNode   — 链路末端宽带限幅 + softClip + TPDF dither（唯一安全网）
// 其余 per-sample 逻辑暂保持 inline；后续阶段逐步节点化。数学行为 1:1 保持。
// ============================================================================
struct DspFrame {
    double L = 0.0;
    double R = 0.0;
    bool   stereo = false;
    int    sampleRate = 48000;
};

// ---- MsebPreGainNode ----
// 合并 autoEq / mseb / dspEq 预增益。每次 callback 只算一次，用 DOUBLE 累积，
// 避免多 stage 连乘在 float23 尾数下的误差（对应 V8.513 的 ×0.5 预留比例在此生效）。
struct MsebPreGainNode {
    static double computeTargetPreGain() {
        double g = 1.0;
        if (g_autoEqEnabled.load(std::memory_order_relaxed))    g *= (double)g_autoEqPreGain.load(std::memory_order_relaxed);
        if (g_mseb10Enabled.load(std::memory_order_relaxed))    g *= (double)g_msebPreGain.load(std::memory_order_relaxed);   // 含 V8.513 ×0.5 预留比例
        if (!g_mseb10Enabled.load(std::memory_order_relaxed)
            && !g_autoEqEnabled.load(std::memory_order_relaxed)
            && g_dspEqEnabled.load(std::memory_order_relaxed))  g *= (double)g_dspEqPreGain.load(std::memory_order_relaxed);
        return g;
    }
    static bool isActive() {
        return g_autoEqEnabled.load(std::memory_order_relaxed)
            || g_mseb10Enabled.load(std::memory_order_relaxed)
            || g_dspEqEnabled.load(std::memory_order_relaxed);
    }
};

// ---- TerminalLimiterNode ----
// 链路末端安全网：宽带 look-ahead 限幅 + softClip + TPDF dither。
// 对应 RawS "auto peak limiter 是链末端唯一 compressor"。
// 注意：多段 look-ahead(g_mbLimiter) 作为中段处理器保留 inline 在前（自身有 per-sample 读/写状态），
// 本节点只接管其后的末端限幅 + 软钳 + 抖动。
struct TerminalLimiterNode {
    static void process(float& outL, float& outR, bool stereo, bool ditherOn) {
        const float peakToLimit = std::fmaxf(std::fabsf(outL), std::fabsf(outR));
        const float gr = g_limiter.process(peakToLimit, true);
        outL *= gr;
        outR = stereo ? outR * gr : 0.0f;
        outL = softClip(outL);
        if (stereo) outR = softClip(outR);
        if (ditherOn) {
            outL += tpdfDither();
            if (stereo) outR += tpdfDither();
        }
    }
    static bool isActive() { return true; }   // 链路末端安全网，恒活动
};

// 细粒度 hasActiveEffects 短路（对齐 RawS）：任一 DSP 节点 active 才进块。
// 后续各特性节点化后可由此统一短路；当前与下方入口门控等价。
static inline bool dspHasActiveEffects() {
    return MsebPreGainNode::isActive()
        || g_loudnessEnabled.load(std::memory_order_relaxed)
        || g_msEnabled.load(std::memory_order_relaxed)
        || g_crossfeedEnabled.load(std::memory_order_relaxed)
        || g_transientEnabled.load(std::memory_order_relaxed)
        || g_compressorEnabled.load(std::memory_order_relaxed)
        || g_agcEnabled.load(std::memory_order_relaxed);
}


class OboeAudioCallback : public oboe::AudioStreamCallback {



public:



    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override {



        // 【V8.192】callback 内部计时起点（提前 return 的 flush/silence 分支不计入）



        struct timespec _cbT0; clock_gettime(CLOCK_MONOTONIC, &_cbT0);



        float *output = static_cast<float *>(audioData);



        int channels = stream->getChannelCount();



        int totalSamples = numFrames * channels;



        g_callbackCount.fetch_add(1, std::memory_order_relaxed);
        // [V8.502 seam probe] sample output stream xRun + timestamp for ~6s after a seam
        {
            static int64_t s_seamSeqSeen = -1;
            static int64_t s_lastXrun = -1;
            static int64_t s_lastFpos = -1;
            static int64_t s_lastTsUs = 0;
            static int s_skip = 0;
            const int64_t _nowUs = (int64_t)_cbT0.tv_sec * 1000000LL + (int64_t)_cbT0.tv_nsec / 1000LL;
            const int64_t _seq = g_seamSeq.load(std::memory_order_relaxed);
            const int64_t _winEnd = g_seamProbeUntilUs.load(std::memory_order_relaxed);
            if (_seq != s_seamSeqSeen) {
                s_seamSeqSeen = _seq;
                if (_winEnd > 0) {
                    LOGI("SEAMPROBE arm seamSeq=%lld winEnd=%lld", (long long)_seq, (long long)_winEnd);
                    s_lastXrun = -1; s_lastFpos = -1; s_skip = 0;
                }
            }
            if (_nowUs < _winEnd) {
                // 【降频】原先是每 4 个回调采一次（约 21 次/秒），而这个 block 在**音频回调
                // 线程**里做 getTimestamp() + LOGI，6 秒窗口内持续占用音频线程 —— 它本身
                // 就是 stall/xrun 的来源之一，与它要测的问题同源。降到每 32 个回调一次
                // （约 0.2 次/秒）：6 秒窗口仍有 5~6 个采样点，足够看趋势，开销降到 1/8。
                if ((s_skip++ & 31) == 0) {
                    const auto _xrR = stream->getXRunCount();
                    const int32_t _xr = _xrR ? (int32_t)_xrR.value() : 0;
                    const int32_t _dxr = (s_lastXrun >= 0) ? (_xr - (int32_t)s_lastXrun) : 0;
                    int64_t _fpos = -1, _fns = 0;
                    const oboe::Result _tr = stream->getTimestamp(CLOCK_MONOTONIC, &_fpos, &_fns);
                    const int64_t _dfpos = (s_lastFpos >= 0) ? (_fpos - s_lastFpos) : 0;
                    const int64_t _dus = _nowUs - s_lastTsUs;
                    const int32_t _sr = stream->getSampleRate();
                    const int64_t _exp = (_dus > 0 && _sr > 0) ? (_dus * (int64_t)_sr) / 1000000LL : 0;
                    const int64_t _stallMs = (_dfpos >= 0 && _exp > 0 && _sr > 0)
                        ? ((_exp - _dfpos) * 1000LL / (int64_t)_sr) : 0;
                    LOGI("SEAMPROBE t+%lldms xrun=%d dxrun=%d fpos=%lld dfpos=%lld exp=%lld stallMs=%lld tsOK=%d",
                         (long long)(_nowUs - g_lastSeamUs.load()), (int)_xr, (int)_dxr,
                         (long long)_fpos, (long long)_dfpos, (long long)_exp, (long long)_stallMs,
                         (int)(_tr == oboe::Result::OK));
                    s_lastXrun = _xr; s_lastFpos = _fpos; s_lastTsUs = _nowUs;
                }
            }
        }
  // 【V7.10】







        // 【V7.08】静音测试：全零输出



        if (g_debugSilenceTest.load()) {



            memset(output, 0, totalSamples * sizeof(float));



            return oboe::DataCallbackResult::Continue;



        }







        // Flush request: output silence and clear



        if (g_flushRequested.load()) {



            memset(output, 0, totalSamples * sizeof(float));



            g_flushRequested.store(false);



            return oboe::DataCallbackResult::Continue;



        }







        // Read from ring buffer（方案 B：crossfade 先 mix 进 output，再统一走下方 RMS/频谱/DSP）



        int toRead = 0;



        if (g_crossfadeActive.load()) {



            // 双轨交叉淡化：from(active)=cos 淡出, to(incoming)=sin 淡入 等功率（无锁无分配）



            bool fromB = g_activeIsB.load();



            PCMRingBuffer* fromRing = fromB ? g_ringBufferB : g_ringBuffer;



            PCMRingBuffer* toRing   = fromB ? g_ringBuffer : g_ringBufferB;



            int availFrom = fromRing ? fromRing->available() : 0;



            int availTo   = toRing ? toRing->available() : 0;



            g_ringBufferFill.store(availFrom, std::memory_order_relaxed);



            int toReadFrom = std::min(availFrom, totalSamples);



            int toReadTo   = std::min(availTo, totalSamples);



            toRead = toReadFrom;



            // 【V8.301·#3】Tempo Blend 翻转：引擎吃 active(A) 轨 → A 的 1:1 直读让位给引擎，



            // incoming(B) 轨改为 1:1 直读（不拉伸，保留原始音质）；否则 active(A) 轨 1:1 直读。



            const bool bmFlip = g_bmEngSrcActive.load(std::memory_order_relaxed);



            // 【V8.425】stemMode 提前读：翻转时 stem 模式不走 B 全轨环 1:1 读
            // （B 的样本由下方 stem 环加权和填进 g_xfadeBufB，读 toRing 只会白消费）。
            const bool stemMode = g_stemMode.load(std::memory_order_relaxed);



            if (bmFlip) {



                // 【V8.429】接实际返回值，否则读到 g_xfadeBufB 的跨歌残留（static 从不清零）
                if (!stemMode && toReadTo > 0) {
                    const int rdF = toRing->read(g_xfadeBufB, toReadTo);
                    toReadTo = (rdF > 0) ? rdF : 0;
                } else if (!stemMode) {
                    toReadTo = 0;
                }



            } else {



                // 【V8.429 修"过渡白噪音"】必须接住 read 的**实际**返回值。
                // 旧代码丢弃了返回值，而 toReadFrom 是按 available() 快照算的乐观预计值；
                // 实际可读量可能更少（解码线程并发写入、ring 在别处被抢读）。于是逐采样循环里
                // `idx < toReadFrom` 对"本帧没填过的那段"也成立 → 读到 g_xfadeBufA 里的
                // **上一首歌残留 PCM**（static 数组，跨歌从不清零）→ 过渡中段沙沙白噪音。
                // 回写真实读取量后，缺口交给下游「最后有效样本 x 5ms 衰减」断流保护处理。
                if (toReadFrom > 0) {
                    const int rdA = fromRing->read(g_xfadeBufA, toReadFrom);
                    toReadFrom = (rdA > 0) ? rdA : 0;
                } else {
                    toReadFrom = 0;
                }



            }



            // 【V8.263 NeuralMix】stem 模式：B 轨贡献来自四轨环加权和（bass 提前 45% 进场、



            // vocals 推迟 30%），人声/鼓/贝斯错峰交接 —— 穷版 NeuralMix 的真身。



            const float* toSamples = g_xfadeBufB;



            if (stemMode) {



                const float posNow = g_crossfadePos.load(std::memory_order_relaxed);



                auto stemCurve = [](float p) -> float {



                    if (p <= 0.0f) return 0.0f;



                    if (p >= 1.0f) return 1.0f;



                    float f2 = p * (float)(XFADE_CURVE_N - 1);



                    int i2 = (int)f2; if (i2 > XFADE_CURVE_N - 2) i2 = XFADE_CURVE_N - 2;



                    float c2 = f2 - (float)i2;



                    return g_xfadeCurveTo[i2] + (g_xfadeCurveTo[i2 + 1] - g_xfadeCurveTo[i2]) * c2;



                };



                const float gVoc = stemCurve(posNow < 0.30f ? 0.0f : (posNow - 0.30f) / 0.70f);



                const float gBa  = stemCurve(posNow * 1.45f > 1.0f ? 1.0f : posNow * 1.45f);



                const float gToS = stemCurve(posNow);



                const float gains[4] = { gVoc, gToS, gBa, gToS };   // 0=voc 1=drums 2=bass 3=other

                // [EMPIRICAL TRACE] 限频打印 djay 式错峰交接实际增益曲线 + 跨轨避让 duck。
                // 仅在 stemMode 期间、每 50 个音频块打印一次，产出可对照的 pos→各轨增益轨迹。
                {
                    static int stemLogCounter = 0;
                    if ((++stemLogCounter % 50) == 0) {
                        float dVoc = 1.0f, dBass = 1.0f;
                        if (g_crossDuckEnabled.load(std::memory_order_relaxed)) {
                            const float flr = g_crossDuckFloor.load(std::memory_order_relaxed);
                            dVoc  = 1.0f - g_aVocEnv  * g_crossDuckVoc.load(std::memory_order_relaxed);
                            dBass = 1.0f - g_aBassEnv * g_crossDuckBass.load(std::memory_order_relaxed);
                            if (dVoc  < flr) dVoc  = flr;
                            if (dBass < flr) dBass = flr;
                        }
                        LOGI("[STEMGAIN] pos=%.3f voc=%.3f drums=%.3f bass=%.3f other=%.3f | Aenv(voc=%.3f bass=%.3f) duckVoc=%.3f duckBass=%.3f",
                             posNow, gVoc, gToS, gBa, gToS, g_aVocEnv, g_aBassEnv, dVoc, dBass);
                    }
                }




                memset(g_xfadeBufB, 0, totalSamples * sizeof(float));



                int maxGot = 0;



                // 【V8.285】分轨音效路由：效果只挂在被选中的那一轨上，其余分轨原样通过



                const int sfxTarget = g_stemFxTarget.load(std::memory_order_relaxed);



                const int sfxMode = (sfxTarget > 0) ? g_stemFxMode.load(std::memory_order_relaxed) : 0;



                const int sfxDelay = g_tplEchoLen.load(std::memory_order_relaxed);



                for (int s = 0; s < 4; s++) {



                    if (!g_stemRing[s] || !g_stemScratch) continue;



                    const int av = g_stemRing[s]->available();



                    const int rd = std::min(av, totalSamples);



                    if (rd <= 0) continue;



                    const int got = g_stemRing[s]->read(g_stemScratch, rd);



                    if (got > maxGot) maxGot = got;



                    if (sfxMode > 0 && stemFxAppliesTo(s, sfxTarget)) {



                        applyStemFx(g_stemScratch, got, channels, posNow, sfxMode, sfxDelay);



                    }



                    // 【Phase1·跨轨智能避让】B(内轨) 分轨 ducking：人声/低频随 A(外轨) 对应包络避让。
                    // B.voc *= (1 - A.vocEnv)；B.bass *= (1 - A.bassEnv)（反向淡入：A 低频退、B 低频进）。
                    // 帧级常量 duck（包络本身已 50~100ms 平滑），floor 保留自然 bleeding。
                    float duck = 1.0f;
                    if (g_crossDuckEnabled.load(std::memory_order_relaxed)) {
                        if (s == 0)      duck = 1.0f - g_aVocEnv  * g_crossDuckVoc.load(std::memory_order_relaxed);
                        else if (s == 2) duck = 1.0f - g_aBassEnv * g_crossDuckBass.load(std::memory_order_relaxed);
                        const float flr = g_crossDuckFloor.load(std::memory_order_relaxed);
                        if (duck < flr) duck = flr;
                    }
                    for (int i = 0; i < got; i++) g_xfadeBufB[i] += g_stemScratch[i] * gains[s] * duck;



                }



                toReadTo = maxGot;



                toSamples = g_xfadeBufB;



                if (g_beatmatchLive.load(std::memory_order_relaxed) && channels > 0) {



                    // 【V8.425】Tempo Blend 翻转 × stem：引擎吃 active(A) 全轨环（engSrc=fromRing、
                    // engDst=g_xfadeBufA、engLen=&toReadFrom），B 的 stem 加权和保持 1:1 自然播放。
                    // 旧逻辑无条件把 stem 和喂进引擎拉伸（老方向）→ B 卡顿/快进。
                    // 争用兜底：A 已由上方 1:1 直读（bmFlip && !stemMode 不成立时 fromRing 直读仍在），
                    // 本帧丢一次变速对齐，不影响音质。

                    if (bmFlip) {

                        auto runEngineA = [&](auto& eng) {
                            if (!g_beatmatchMutex.try_lock()) {
                                // 争用兜底：A 1:1 直读（翻转模式 A 无预读，不兜就是旧缓冲杂音）
                            // 【V8.429】接实际返回值：这里的乐观预计值会让逐采样循环
                            // 读到 g_xfadeBufA 的跨歌残留（static 数组从不清零）→ 白噪音。
                            if (toReadFrom > 0 && fromRing != nullptr) {
                                const int rdG = fromRing->read(g_xfadeBufA, toReadFrom);
                                toReadFrom = (rdG > 0) ? rdG : 0;
                            } else {
                                toReadFrom = 0;
                            }
                                return;
                            }
                            if (toReadFrom > 0 && fromRing != nullptr) {
                                const int rd = fromRing->read(g_xfadeBufA, toReadFrom);
                                if (rd > 0) eng.feed(g_xfadeBufA, rd / channels);
                            }
                            int guard = 0;
                            while (eng.availableFrames() < numFrames && guard++ < 8) {
                                const int needFrames = eng.needsInputFrames(numFrames - eng.availableFrames());
                                const int ringAvailSmp = fromRing ? fromRing->available() : 0;
                                if (ringAvailSmp <= 0) break;
                                int want = needFrames < (ringAvailSmp / channels) ? needFrames : (ringAvailSmp / channels);
                                if (want > 8192) want = 8192;
                                if (want <= 0) break;
                                const int gotSmp = fromRing->read(g_bmScratch, want * channels);
                                if (gotSmp <= 0) break;
                                eng.feed(g_bmScratch, gotSmp / channels);
                                eng.render();
                            }
                            const int gotFrames = eng.pull(g_xfadeBufA, numFrames);
                            toReadFrom = gotFrames * channels;
                            g_beatmatchMutex.unlock();
                        };
                        if (g_bmEngineArmed.load(std::memory_order_relaxed)) runEngineA(g_rbStretch);
                        else runEngineA(g_stretch);

                    } else {

                    // beatmatch 仍生效：把加权和喂进引擎（与常规路径同构，只是源是 stem 和）



                    auto runEngineStem = [&](auto& eng) {



                        // 【V8.290 卡进程修复·引擎互斥】引擎对象（g_rbStretch/g_stretch）与触发线程



                        // 的 reset()/prime() 必须互斥：触发线程持 g_beatmatchMutex 做 reset（重分配内部



                        // 缓冲），若 RT 线程同时 pull/render 同一实例即触发堆破坏（use-after-free）→



                        // 累积几轮过渡后 pull() 死锁 = 卡进程 + g_crossfadeActive 卡 true = "未触发"。



                        // 此处 try_lock：争用（仅「上一轮回落窗口内又起新一轮」的重叠窗命中）本帧跳过引擎。



                        if (!g_beatmatchMutex.try_lock()) return;



                        eng.feed(g_xfadeBufB, totalSamples / channels);



                        eng.render();



                        const int gotFrames = eng.pull(g_xfadeBufC, numFrames);



                        toReadTo = gotFrames * channels;



                        toSamples = g_xfadeBufC;



                        g_beatmatchMutex.unlock();



                    };



                    if (g_bmEngineArmed.load(std::memory_order_relaxed)) runEngineStem(g_rbStretch);



                    else runEngineStem(g_stretch);



                    }   // 【V8.425】end of else (non-flip: stretch the stem mix)



                }



            } else



            if (g_beatmatchLive.load(std::memory_order_relaxed) && channels > 0) {



                const int chN = channels;



                // 【V8.222】引擎二选一：Rubber Band（高质量 keylock）/ WSOLA（旧档，保留）。



                // 两者接口同构，泛型 lambda 复用同一段 push/pull 调度。



                auto runEngine = [&](auto& eng) {



                    // 【V8.290 卡进程修复·引擎互斥】与触发线程 reset/prime 互斥（见 runEngineStem 注释）。



                    // 过渡进行中触发线程不会持锁（reset 在武装阶段、早于 g_crossfadeActive），正常不争用；



                    // 仅「上一轮比率回落 4s 窗口内又起新一轮」的重叠窗会命中，此时本帧 1:1 直读兜底



                    // （只丢一帧变速对齐，不影响音质）。



                    // 【V8.301·#3】翻转：引擎源=active(A) 环、输出进 g_xfadeBufA（from 缓冲）；



                    // 常规：源=incoming(B) 环、输出进 g_xfadeBufC。engLen 指向对应帧数变量。



                    PCMRingBuffer* engSrc = bmFlip ? fromRing : toRing;



                    float*           engDst = bmFlip ? g_xfadeBufA : g_xfadeBufC;



                    int*             engLen = bmFlip ? &toReadFrom : &toReadTo;



                    if (!g_beatmatchMutex.try_lock()) {



                        // 争用兜底：非引擎轨 1:1 直读（翻转=incoming(B)；常规=active(A)）



                        if (bmFlip) {



                            // 【V8.429】接实际返回值：这里的乐观预计值会让逐采样循环
                            // 读到 g_xfadeBufB 的跨歌残留（static 数组从不清零）→ 白噪音。
                            if (toReadTo > 0 && toRing != nullptr) {
                                const int rdG = toRing->read(g_xfadeBufB, toReadTo);
                                toReadTo = (rdG > 0) ? rdG : 0;
                            } else {
                                toReadTo = 0;
                            }



                            toSamples = g_xfadeBufB;



                        } else {



                            // 【V8.429】接实际返回值：这里的乐观预计值会让逐采样循环
                            // 读到 g_xfadeBufA 的跨歌残留（static 数组从不清零）→ 白噪音。
                            if (toReadFrom > 0 && fromRing != nullptr) {
                                const int rdG = fromRing->read(g_xfadeBufA, toReadFrom);
                                toReadFrom = (rdG > 0) ? rdG : 0;
                            } else {
                                toReadFrom = 0;
                            }



                        }



                        return;



                    }



                    // 【V8.301】翻转：incoming(B) 已由上方 1:1 直读，toSamples 指向 g_xfadeBufB；



                    // active(A) 轨交给引擎拉伸。常规：先 1:1 从 toRing 读一块进 g_xfadeBufB 再喂引擎。



                    if (bmFlip) {



                        toSamples = g_xfadeBufB;



                    } else if (toReadTo > 0 && toRing != nullptr) {



                        const int rd = toRing->read(g_xfadeBufB, toReadTo);



                        if (rd > 0) eng.feed(g_xfadeBufB, rd / chN);



                    }



                    // 【V8.226 修复"哒哒哒"】翻转：从 engSrc(=fromRing) 预喂；常规：toRing。



                    // 预喂块进 engDst（翻转=g_xfadeBufA / 常规=g_xfadeBufC），喂引擎消费后再 pull 覆盖。



                    if (*engLen > 0 && engSrc != nullptr) {



                        const int rd = engSrc->read(engDst, *engLen);



                        if (rd > 0) eng.feed(engDst, rd / chN);



                    }



                    int guard = 0;



                    while (eng.availableFrames() < numFrames && guard++ < 8) {



                        const int needFrames = eng.needsInputFrames(numFrames - eng.availableFrames());



                        const int ringAvailSmp = engSrc ? engSrc->available() : 0;



                        if (ringAvailSmp <= 0) break;



                        int want = needFrames < (ringAvailSmp / chN) ? needFrames : (ringAvailSmp / chN);



                        if (want > 8192) want = 8192;



                        if (want <= 0) break;



                        const int gotSmp = engSrc->read(g_bmScratch, want * chN);



                        if (gotSmp <= 0) break;



                        eng.feed(g_bmScratch, gotSmp / chN);



                        eng.render();



                    }



                    const int gotFrames = eng.pull(engDst, numFrames);



                    *engLen = gotFrames * chN;    // 让下方欠载统计/诊断拿到真实到货量



                    if (!bmFlip) toSamples = engDst;



                    g_beatmatchMutex.unlock();



                };



                if (g_bmEngineArmed.load(std::memory_order_relaxed)) runEngine(g_rbStretch);



                else runEngine(g_stretch);



            } else if (toReadTo > 0) {



                // 【V8.429 修"过渡白噪音"】同 A 轨：接住实际返回值，否则会读到
                // g_xfadeBufB 里上一帧/上一首歌的残留（static 数组，跨歌从不清零）。
                const int rdB = toRing->read(g_xfadeBufB, toReadTo);
                toReadTo = (rdB > 0) ? rdB : 0;



            }



            float pos = g_crossfadePos.load();



            float step = g_crossfadeStep.load();



            // 【V8.287】Phrase 分段式 FX（tpl==8）框架级分段边界：每帧算一次（整段过渡恒定），



            // 把 djay 的 8 小节方案自适应到本次过渡实际小节数。逐样本再算 barIdx/segEnv。



            float phrDur = (float)g_crossfadeDurationMs.load();



            float phrBeat = g_xfadeBeatMs.load();



            if (phrBeat < 40.0f) phrBeat = 40.0f; if (phrBeat > 5000.0f) phrBeat = 5000.0f;



            float phrBar = phrBeat * 4.0f;



            int phrTotalBars = 8, phrBCutEnd = 4, phrAHpfEnd = 6;



            {



                // 【V8.310 Phrase 方案可配】用户可在设置里选 4/8/16/32 小节方案。



                // 原实现固定用「本次过渡实际小节数」(nb)，等价于 8 小节固定；



                // 现在取「用户设定值」与「实际小节数」的**较小者**——设定值是上限，



                // 不能让分段边界落到过渡时长之外（那样第 2/3 段永远进不去，FX 等于没开）。



                int want = g_xfadePhraseBars.load(std::memory_order_relaxed);



                if (want != 4 && want != 8 && want != 16 && want != 32) want = 8;



                int nb = (int)(phrDur / phrBar + 0.5f); if (nb < 1) nb = 1;



                if (nb > want) nb = want;



                phrTotalBars = nb;



                phrBCutEnd = nb / 2; if (phrBCutEnd < 1) phrBCutEnd = 1;



                phrAHpfEnd = (nb * 3) / 4; if (phrAHpfEnd <= phrBCutEnd) phrAHpfEnd = phrBCutEnd + 1;



            }



            const float tplPosBase = pos;   // 【V8.281】wash 湿量包络的起点（pos 会在帧循环里推进）



            const float HP = 1.5707963f;  // PI/2



            // 【V8.142】滤波系数随采样率缩放（原固定 0.35 只按 44.1k 调参，高采样率下截止频率漂移）



            int cfSr = g_sampleRate.load(); if (cfSr <= 0) cfSr = 44100;



            float hpfBaseK = 0.35f * (44100.0f / (float)cfSr);



            // 【V8.150】DJ 效果已移到「混音后总线」（见下方 Live FX Bus 块）。



            // 混音此处保持等功率互补；效果不再挂 A 轨，避免被 gFrom=(1-pos)^2 二次衰减。



            // 【V8.153】filter 回到「轨级」：A 轨 LP 扫出 20k→300Hz + B 轨 HP 扫入 20k→20Hz，



            // 这是标准 DJ filter fade；总线级 LP 会把已进场的 B 一起闷掉（且过渡末才解闷）。



            int fx = (g_liveFxEnabled.load(std::memory_order_relaxed)



                      && g_xfadeEffect.load(std::memory_order_relaxed) == 1) ? 1 : 0;



            const float TWO_PI = 6.2831853f;



            // 【V8.246 三段频交接】分频系数帧循环外算一次（150Hz / 4kHz 一阶）



            const bool threeBand = (g_xfadeThreeBand.load(std::memory_order_relaxed)
                || g_xfadeTpl.load(std::memory_order_relaxed) == 10) && !stemMode;   // 【V8.421】tpl10=EQ 三段交叉



            const float kLo = threeBand ? 1.0f - expf(-TWO_PI * 150.0f / (float)cfSr) : 0.0f;



            const float kHi = threeBand ? 1.0f - expf(-TWO_PI * 4000.0f / (float)cfSr) : 0.0f;
            // 【V8.505 ②】vocal-recede 分频（独立于 threeBand，始终按 150Hz/4kHz 取中频=人声带）
            const float kRecL = 1.0f - expf(-TWO_PI * 150.0f / (float)cfSr);
            const float kRecH = 1.0f - expf(-TWO_PI * 4000.0f / (float)cfSr);



            g_xfadeSumA = 0.0f;   // 【V8.183】本帧 A/B 单轨能量平方，帧末换成两条慢速 RMS



            g_xfadeSumB = 0.0f;

            // 【Phase1·A 包络】外轨(g_xfadeBufA) 人声/低频能量包络：50~100ms 窗 + 非对称快攻慢放 IIR。
            // 仅 stemMode 下驱动 B 分轨 ducking；开销极低（每帧几次 expf + 样本内 +/*）。
            // 此处 g_xfadeBufA 在 bmFlip+stem 由引擎、非 bmFlip 由 1:1 直读已填充，必为外轨当帧内容。
            if (stemMode && g_crossDuckEnabled.load(std::memory_order_relaxed) && toReadFrom > 0 && channels > 0) {
                const float fs = (g_sampleRate.load() > 0) ? (float)g_sampleRate.load() : 44100.0f;
                const float hpVocC  = 1.0f - 3.14159265f * 300.0f  / fs;   // ~300Hz HP（DC-blocker 式）
                const float lpVocC  = 1.0f - expf(-6.2831853f * 3000.0f / fs); // ~3kHz LP
                const float lpBassC = 1.0f - expf(-6.2831853f * 150.0f  / fs); // ~150Hz LP
                const float kA  = 1.0f - expf(-6.2831853f / (0.015f * fs));   // 快攻 ~15ms
                const float kRv = 1.0f - expf(-6.2831853f / (0.400f * fs));   // 人声慢放 ~400ms
                const float kRb = 1.0f - expf(-6.2831853f / (0.200f * fs));   // bass 慢放 ~200ms
                const int n = toReadFrom;
                for (int i = 0; i < n; i += channels) {
                    float m = 0.0f; for (int c = 0; c < channels; c++) m += g_xfadeBufA[i + c];
                    m /= (float)channels;
                    const float hp = (m - g_aVocHPx) + hpVocC * g_aVocHP; g_aVocHPx = m; g_aVocHP = hp;
                    const float vl = g_aVocLP + lpVocC * (hp - g_aVocLP);     g_aVocLP = vl;
                    const float bl = g_aBassLP + lpBassC * (m - g_aBassLP);   g_aBassLP = bl;
                    const float pVoc = vl * vl, pBass = bl * bl;
                    g_aVocEnv  += (pVoc  > g_aVocEnv)  ? (pVoc  - g_aVocEnv)  * kA  : (pVoc  - g_aVocEnv)  * kRv;
                    g_aBassEnv += (pBass > g_aBassEnv) ? (pBass - g_aBassEnv) * kA  : (pBass - g_aBassEnv) * kRb;
                }
                g_aVocEnv = sqrtf(g_aVocEnv); g_aBassEnv = sqrtf(g_aBassEnv);
            }

            bool sweepOnFrame = false;       // 【V8.505】本帧是否启用过渡扫频（每帧 f==0 刷新）
            float recedeDuckFrame = 0.0f;    // 【V8.505】本帧人声淡出量 0..0.6



            for (int f = 0; f < numFrames; f++) {



                // 【V8.180】查 cos/sin 等功率表（端点仍为 0/1 完全淡出淡入，语义不变）。



                // 每采样只做一次线性插值，实时线程零分配；表只在首次进入 crossfade 时建一次。



                buildXfadeCurve();



                float fp = pos * (float)(XFADE_CURVE_N - 1);



                int fi = (int)fp; if (fi < 0) fi = 0; if (fi > XFADE_CURVE_N - 2) fi = XFADE_CURVE_N - 2;



                float fc = fp - (float)fi;



                float gTo    = g_xfadeCurveTo[fi]    + (g_xfadeCurveTo[fi + 1]    - g_xfadeCurveTo[fi])    * fc;



                float gFrom = g_xfadeCurveFrom[fi] + (g_xfadeCurveFrom[fi + 1] - g_xfadeCurveFrom[fi]) * fc;



                // 【V8.246 三段频】各频段增益曲线：低=35% 内 swap，中=等功率(gFrom/gTo)，高=65% 后交接



                float gLA = 1.0f, gLB = 0.0f, gHA = 1.0f, gHB = 0.0f;



                if (threeBand) {



                    // 【联合 Filter Sweep 的硬低频交接】tpl==1 时低频频段（<150Hz）不做原
                    // 0→0.35 线性平滑，而在节奏契合的换头瞬间（拍网格靠中点）硬抽 A、硬放 B ——
                    // 听感即专业夜店调音台切频的 snap。非扫频过渡保持原线性曲线兼容。
                    const int tplSweep = g_xfadeTpl.load(std::memory_order_relaxed);
                    float thL;
                    if (tplSweep == 1 && !stemMode) {
                        const float beatMs = g_xfadeBeatMs.load();
                        float swapPos = 0.5f;
                        if (beatMs > 40.0f && phrDur > 1.0f) {
                            const int nBeats = (int)(phrDur / beatMs + 0.5f);
                            if (nBeats >= 2) {
                                const int k = (int)(0.5f * (float)nBeats + 0.5f);
                                swapPos = (float)k / (float)nBeats;
                            }
                        }
                        float w = (beatMs > 40.0f && phrDur > 1.0f)
                            ? (0.12f * beatMs / phrDur) : 0.015f;   // ~1/8 拍软化窗，防 zipper
                        if (w < 0.005f) w = 0.005f; if (w > 0.25f) w = 0.25f;
                        thL = (pos < swapPos - w * 0.5f) ? 0.0f
                            : (pos > swapPos + w * 0.5f) ? 1.0f
                            : (pos - (swapPos - w * 0.5f)) / w;
                    } else {
                        thL = pos < 0.35f ? pos / 0.35f : 1.0f;
                    }



                    gLA = 1.0f - thL; gLB = thL;



                    const float thH = pos < 0.65f ? 0.0f : (pos - 0.65f) / 0.35f;



                    gHA = 1.0f - thH; gHB = thH;

                    // 【V8.421·EQ 三段交叉】经典 DJ EQ 过渡：低频在过渡中点交换，
                    // B 低音前半段压住(=0)，[0.5,0.75] 渐入，A 低音同步退出；
                    // 中高频随主推子等功率交叉 —— 只有低频做 EQ 交换。覆盖上面默认曲线。
                    if (g_xfadeTpl.load(std::memory_order_relaxed) == 10) {
                        float thL = pos < 0.5f ? 0.0f : (pos - 0.5f) / 0.25f;
                        thL = thL > 1.0f ? 1.0f : (thL < 0.0f ? 0.0f : thL);
                        gLA = 1.0f - thL; gLB = thL;
                        gHA = gFrom; gHB = gTo;
                    }



                }



                // 【方案3 低频防浑浊】B 轨淡入时低频与 A 轨叠加易浑浊：随 pos 线性加 HPF。



                // 【V8.220 低频交接插件】默认关 = 原曲线（B 低频 60% 处进满、A 低频 40% 后渐切）；



                // 开 = 专业 DJ 的 bass swap：A 低频从头就切、35% 切干净，B 低频同步顶上，



                // 低频无重叠期 → 过渡更干净。关闭时两条曲线与旧版逐值相同，零影响。



                const bool bassSwap = g_xfadeBassSwap.load(std::memory_order_relaxed);



                float theta = bassSwap ? (pos < 0.35f ? pos / 0.35f : 1.0f)



                                       : (pos < 0.6f ? pos / 0.6f : 1.0f);   // 0..1



                float hpfMix = 1.0f - theta;                     // 1 -> 0



                float hpfK = hpfBaseK * hpfMix;



                // 【V8.145】行级调制量



                float modGain = 1.0f;   // tremolo 幅度调制



                if (fx == 5) {



                    // 【V8.182】频率取自 g_xfadeTremHz（随 BPM 同步，缺省回落 9Hz）



                    modGain = 1.0f - 0.70f * (0.5f + 0.5f * sinf((float)g_xfadeModPhase));



                    g_xfadeModPhase += (double)(TWO_PI * g_xfadeTremHz.load(std::memory_order_relaxed) / (float)cfSr);



                    if (g_xfadeModPhase > (double)TWO_PI) g_xfadeModPhase -= (double)TWO_PI;



                }



                // 【V8.281】模板滤波系数按帧算一次（8 声道共用，省掉 per-channel powf）。



                // 扫频走对数：滤波器听感是对数的，线性 Hz 扫会让变化全挤在前 10%。



                const int tplNow = g_xfadeTpl.load(std::memory_order_relaxed);

                // 【V8.505】过渡 sweeteners 每帧刷新一次（f==0）：复位滤波状态 + 算扫频截止 + 算人声淡出量。
                if (f == 0) {
                    if (g_sweepReset.load(std::memory_order_acquire)) {   // 新过渡起点：清掉上一段残留滤波状态
                        for (int c = 0; c < 8; c++) { g_sweepLpA[c].reset(); g_sweepLpB[c].reset(); g_recedeLpA[c] = 0.0f; g_recedeHpA[c] = 0.0f; }
                        g_sweepReset.store(false, std::memory_order_release);
                    }
                    const bool xfActive = g_crossfadeActive.load(std::memory_order_relaxed);
                    // 扫频跳过 tpl==1(Filter Fade 已做滤波) 与 fx==1(旧总线滤波)，避免双重滤波；stem 模式跳过
                    sweepOnFrame = xfActive && pos > 0.0f && pos < 1.0f && tplNow != 1 && fx != 1 && !stemMode;
                    recedeDuckFrame = (xfActive && pos > 0.0f && pos < 1.0f && !stemMode) ? (0.6f * pos) : 0.0f;
                    if (sweepOnFrame) {
                        const float nyq = 0.5f * (float)cfSr;
                        const float fcA = nyq * powf(250.0f / nyq, pos);     // nyquist -> 250Hz（渐闷离场）
                        const float fcB = 300.0f * powf(nyq / 300.0f, pos);  // 300Hz -> nyquist（由闷转清）
                        for (int c = 0; c < 8; c++) {
                            g_sweepLpA[c].setLowPass((float)cfSr, fcA, 0.707f);
                            g_sweepLpB[c].setLowPass((float)cfSr, fcB, 0.707f);
                        }
                    }
                }



                float tplKA = 0.0f, tplKB = 0.0f, tplKUp = 0.0f;



                if (tplNow == 1 && !stemMode) {



                    // 【V8.288】djay「反向扫频」Filter：A 高通往上扫、B 低通往下扫，两轨滤波



                    // 类型相反、截止频率反向，听感就是一条从中段扫过的 reverse sweep。



                    // A HPF：cutoff = 20 + 2480·sin(π·pos) —— pos=0/1 回平(20Hz)、pos≈0.5 最薄



                    //   (2.5kHz)。落点回平保证新歌低音不丢（不会在交接瞬间被掐掉）。



                    const float fcA = 20.0f + 2480.0f * sinf(3.14159265f * pos);   // 20Hz→2.5kHz(中)→20Hz



                    // B LPF：cutoff = 20kHz · 0.0125^pos —— 20kHz→250Hz 单调往下扫（离场渐闷）。



                    const float fcB = 20000.0f * powf(0.0125f, pos);               // 20kHz → 250Hz



                    tplKA = 1.0f - expf(-TWO_PI * fcA / (float)cfSr);



                    tplKB = 1.0f - expf(-TWO_PI * fcB / (float)cfSr);
                    /* [V8.402] incoming LPF opens UP: 1.2kHz -> 20kHz (muffled -> clear).
                       Start is 1.2kHz on purpose: A's HPF peaks near 2.5kHz mid-transition,
                       so a low start would hollow out the middle of the mix. */
                    const float fcUp = 1200.0f * powf(20000.0f / 1200.0f, pos);
                    tplKUp = 1.0f - expf(-TWO_PI * fcUp / (float)cfSr);



                } else if ((tplNow == 2 || tplNow == 4) && !stemMode) {



                    // tpl2 = djay 的「HPF + Echo」组合：先把 A 的低频切掉给 B 让位，



                    //   回声才有空间（不切的话低频叠低频，回声一出来就糊成一团）。



                    // tpl4 = Wash Out：低切抬得更高（到 1.4k），配合大湿量混响做成音墙。



                    const float fcEnd = (tplNow == 4) ? 1400.0f : 500.0f;



                    const float fcA = 20.0f * powf(fcEnd / 20.0f, pos);



                    tplKA = 1.0f - expf(-TWO_PI * fcA / (float)cfSr);



                }



                // 【V8.145】A 轨对称低切（原逻辑；filter/eq 有自己的滤波，跳过）



                // 【V8.220】交接模式：从头就切、35% 切干净（与 B 低频进场严格同步）



                float lpfTheta = bassSwap ? (pos / 0.35f)



                                          : (pos < 0.4f ? 0.0f : (pos - 0.4f) / 0.6f);  // 0..1



                float lpfMix = lpfTheta < 1.0f ? lpfTheta : 1.0f;         // 0 -> 1



                // 【V8.231】断流检测：本帧起 A/B 任一侧缺数据 → 缺口帧用「最后有效样本



                // × 线性衰减（5ms 归零）」，消除硬跳 0 的阶跃（「哒」）；供数恢复则重置。



                const bool aGap = (f * channels) >= toReadFrom;



                const bool bGap = (f * channels) >= toReadTo;



                if (!aGap) g_xfadeAGapRun = 0; else if (g_xfadeAGapRun < 1000000) g_xfadeAGapRun++;



                if (!bGap) g_xfadeBGapRun = 0; else if (g_xfadeBGapRun < 1000000) g_xfadeBGapRun++;



                // 【V8.429 修"切歌后卡顿电子噪音"】原来是
                //   aFade = 1 - min(gapRun,240)/240，然后
                //   from = g_xfadeALast[c] * aFade
                // 即把**最后一个采样点的瞬时值**重复 240 次再淡出。g_xfadeALast[c] 是一个
                // 采样点（不是 0.1ms 周期的波形值），连续重复它 = 方波 → 听感是"咔哒/电子噪音"。
                //
                // 为什么会现在才暴露：V8.429 之前 toReadFrom 是被available() 快照高估的乐观值，
                // 所以 aGap 几乎从不成立、这段保护形同虚设。改成回写真实读取量后欠载被如实上报，
                // 这段保护第一次真正生效 —— 于是把它自身的缺陷暴露了出来。
                //
                // 现在改成**指数淡出到零**：每采样乘 GAP_FADE_STEP（≈0.96@48k≈0.35ms 起始斜率），
                // 起点仍是最后有效值，但变成单调下滑的平滑曲线，听感是自然消失而非方波。
                // 上限 240 次后钳到 0，保证一定归零（不会一直挂着一个 DC 值）。



                // 【V8.429】首次进入时建一次衰减表（幂等；非音频线程做重活，实时线程只等一次 acquire）
                if (!g_xfadeAttReady.load(std::memory_order_acquire)) {
                    buildXfadeGapFadeTable();
                }
                for (int c = 0; c < channels; c++) {



                    int idx = f * channels + c;



                    float from;



                    if (idx < toReadFrom) { from = g_xfadeBufA[idx]; if (c < 8) { g_xfadeALast[c] = from; g_xfadeADecay[c] = 1.0f; } }

                    else {
                        // 【V8.510】断流缺口改用「逐采样持续衰减」：每轨持有一个衰减系数，缺口期
                        // 逐采样乘 STEP，恢复供数才复位。原实现按缺口内序号查「衰减表」、每回调从 0 重算 →
                        // 持续断流（出轨真 EOS、环空）时每回调把末样本×衰减重放一遍 = 周期性咔哒/白噪。
                        // 现在单调滑到 0 后保持 0、不再回升，彻底消除该周期噪声（真 EOS 仍走 V8.504 静音）。
                        if (c < 8) {
                            g_xfadeADecay[c] *= XFADE_GAP_FADE_STEP;
                            if (g_xfadeADecay[c] < 1e-4f) g_xfadeADecay[c] = 0.0f;
                            from = (xfadeOutgoingEos() && !fromB) ? 0.0f : (g_xfadeALast[c] * g_xfadeADecay[c]);
                        } else from = 0.0f;
                    }



                    float to;



                    if (idx < toReadTo) { to = toSamples[idx]; if (c < 8) { g_xfadeBLast[c] = to; g_xfadeBDecay[c] = 1.0f; } }

                    else {
                        // 【V8.510】同 from 侧：持续衰减替代每回调重置的衰减表。
                        if (c < 8) {
                            g_xfadeBDecay[c] *= XFADE_GAP_FADE_STEP;
                            if (g_xfadeBDecay[c] < 1e-4f) g_xfadeBDecay[c] = 0.0f;
                            to = (xfadeOutgoingEos() && fromB) ? 0.0f : (g_xfadeBLast[c] * g_xfadeBDecay[c]);
                        } else to = 0.0f;
                    }



                    // 【V8.24 B 音量预匹配】B 轨预增益（仅 crossfade 激活期间生效）



                    if (g_crossfadeActive.load()) to *= g_xfadeBPreGain.load(std::memory_order_relaxed);



                    // 【V8.183 A/B 电平分离】趁此时两轨各自的样本都还是原始电平（还没过



                    // 防浑浊 HPF / 对称低切 / DJ 效果），各记一份能量平方。这里取 B 时用



                    // 了带上预增益的 to —— 那才是 B 真正参与混合的电平，预增益才算得准。



                    if (c == 0) { g_xfadeSumA += from * from; g_xfadeSumB += to * to; }



                    // B 轨低频防浑浊：1 阶 high-pass（filter/eq 有自己的滤波，跳过；threeBand 时由三段频接管）



                    if (hpfK > 0.0f && c < 8 && fx != 1 && fx != 2 && !threeBand && !stemMode && tplNow != 1 && tplNow != 8) {



                        float y = hpfK * (to - g_xfadeHpfPrevB[c]) + (1.0f - hpfK) * g_xfadeHpfPrevB[c];



                        to = y * hpfMix + to * (1.0f - hpfMix);



                        g_xfadeHpfPrevB[c] = y;



                    }



                    // A 轨对称低切（原逻辑；filter/eq 跳过；threeBand 时由三段频接管）



                    // 【V8.281】tpl==1 时跳过：模板自己就是一整套低切/低通交接，再叠这段



                    // 旧滤波会把 A 压成窄带（高通 + 低通夹击），交接反而听不清。



                    if (lpfMix > 0.0f && c < 8 && fx != 1 && fx != 2 && !threeBand && tplNow != 1 && tplNow != 6 && tplNow != 8) {



                        float lpfK = hpfBaseK * lpfMix;



                        float yl = from * (1.0f - lpfK) + g_xfadeLpfPrevA[c] * lpfK;



                        from = yl * lpfMix + from * (1.0f - lpfMix);



                        g_xfadeLpfPrevA[c] = yl;



                    }



                    // 【V8.246 三段频交接】低/中/高各自独立增益（subtraction 法一阶分频）：



                    // low 走 bass-swap 快交、mid 走标准等功率、high 65% 后交接 —— 错峰防浑浊。



                    if (threeBand && c < 8 && tplNow != 8) {



                        float lpAL = g_xfadeLpfPrevA[c]; lpAL += kLo * (from - lpAL); g_xfadeLpfPrevA[c] = lpAL;



                        float lpAH = g_xfadeLpAHi[c];    lpAH += kHi * (from - lpAH); g_xfadeLpAHi[c] = lpAH;



                        const float aLow = lpAL, aHigh = from - lpAH, aMid = from - aLow - aHigh;



                        from = aLow * gLA + aMid * gFrom + aHigh * gHA;



                        float lpBL = g_xfadeLpBLow[c];   lpBL += kLo * (to - lpBL); g_xfadeLpBLow[c] = lpBL;



                        float lpBH = g_xfadeLpBHi[c];    lpBH += kHi * (to - lpBH); g_xfadeLpBHi[c] = lpBH;



                        const float bLow = lpBL, bHigh = to - lpBH, bMid = to - bLow - bHigh;



                        to = bLow * gLB + bMid * gTo + bHigh * gHB;



                    }



                    float fA = from, fB = to;   // 效果处理后两轨



                    float tplRiser = 0.0f;       // 【V8.286】Noise Sweep/Riser 叠加层（仅 tpl==7 非零）



                    // 【V8.281】djay Crossfader FX 模板：轨级处理，全部按 lpos 调制



                    const int tpl = tplNow;



                    if (tpl > 0 && c < 8 && !stemMode) {



                        int dl = g_tplEchoLen.load(std::memory_order_relaxed);



                        if (dl < 1 || dl >= XFADE_DELAY_MAX) dl = (int)(0.375f * (float)cfSr);   // 缺省 3/8 拍



                        // 【V8.286】Beat Roll 切片长度（跨 tpl==6 分支与 per-sample 写指针推进共享）



                        int rollSlice = dl;



                        if (rollSlice < 1) rollSlice = (int)(0.5f * (float)cfSr);          // 兜底 1/2 拍



                        if (rollSlice > TPL_ROLL_MAX / 2 - 1) rollSlice = TPL_ROLL_MAX / 2 - 1;



                        if (tpl == 1) {



                            // 【V8.288】反向扫频 Filter：A 高通往上扫（低频让位、中段最薄、落点回平），



                            // B 低通往下扫（高频渐隐、离场渐闷）。两者截止频率反向，构成 reverse sweep。



                            // 注：B 这里「往下扫（渐闷）」与旧版「张开到 20k」相反——旧版会让离场曲在



                            // 过渡结束瞬间突然变亮，与 B 已淡出不符；往下扫才对。



                            float hp  = (1.0f - tplKA) * (g_tplHpA[c] + fA - g_tplHpAX[c]);



                            g_tplHpAX[c] = fA; g_tplHpA[c] = hp;



                            fA = hp;

                            /* [V8.402] then open the incoming track up (muffled -> clear). */

                            g_tplLpA[c] += tplKUp * (fA - g_tplLpA[c]);

                            fA = g_tplLpA[c];



                            g_tplLpB[c] += tplKB * (fB - g_tplLpB[c]);



                            fB = g_tplLpB[c];



                        } else if (tpl == 2) {



                            // Echo Fade（djay 的 HPF + Echo）：A 先低切再进 3/4 拍延迟线，



                            // 湿量随推子上升。低切是这套组合的关键 —— 低频让给 B，回声才不糊。



                            float hp = (1.0f - tplKA) * (g_tplHpA[c] + fA - g_tplHpAX[c]);



                            g_tplHpAX[c] = fA; g_tplHpA[c] = hp;



                            int rd = g_tplEchoWr - dl; if (rd < 0) rd += XFADE_DELAY_MAX;



                            float d = g_tplEcho[c][rd];



                            // 【V8.310 Cut & Echo 自动化】djay 形态：cut 之后回声**按拍自动重触发**，



                            // 而不是一条固定延迟线。原实现每次回调都读同一抽头位置 → 等价于



                            // 「一条固定回声」，没有 cut&echo 的节奏感。



                            // 做法：按 elapsed/beat 算出当前应命中的抽头（拍点网格），



                            // 每跨一个拍点就把上一拍的尾巴叠进来（feedback 0.55，保持不糊）。



                            float echoMix = 0.0f;



                            const int autoMode = g_tplEchoAuto.load(std::memory_order_relaxed);



                            if (autoMode && g_xfadeBeatMs > 20.0f) {



                                const float beatSmp = g_xfadeBeatMs * (float)cfSr / 1000.0f;



                                // 当前拍相位与拍内位置



                                const float beatPosF = pos * phrDur * (float)cfSr;



                                const int beatIdx = (int)(beatPosF / beatSmp);



                                // 取本拍 + 前两拍的抽头（tap 越多越厚，这里给 3 抽头，落在拍上）



                                float acc = 0.0f;



                                for (int k = 0; k < 3; k++) {



                                    int tr = g_tplEchoWr - (int)((beatIdx - k) * beatSmp);



                                    tr %= XFADE_DELAY_MAX; if (tr < 0) tr += XFADE_DELAY_MAX;



                                    acc += g_tplEcho[c][tr];



                                }



                                acc *= 0.3333f;



                                // 【V8.488】延迟线写入乘 gFrom（同 tpl8）：捕获与反馈都随干声淡出，
                                // 否则读出的满能量旧样本叠加回来会把 A 轨「复活」（响→轻→又响）。
                                g_tplEcho[c][g_tplEchoWr] = hp * gFrom + acc * 0.55f * gFrom;



                                // 【V8.452】乘 gFrom：回声必须随干声一起淡出。
                                // pos 是0→1 上升包络，gFrom 是 1→0 淡出，
                                // 只用 pos 会在 A 退出前把回声顶回来（用户报的"又响一下"）。
                                echoMix = acc * 0.55f * pos * gFrom;



                            } else {



                                // 【V8.488】延迟线写入乘 gFrom（同 tpl8）：避免满能量旧样本复活 A 轨。
                                g_tplEcho[c][g_tplEchoWr] = hp * gFrom + d * 0.35f * gFrom;



                                echoMix = d * 0.40f * pos * gFrom;   // 【V8.452】同 tpl==1：随干声淡出



                            }



                            fA = hp + echoMix;



                        } else if (tpl == 3) {



                            // Brake Drop：pos→0.55 起读指针减速（磁带停转）。减速量按刹车段



                            // 总帧数反推：累计滞后 = k*N/2，必须落在环形缓冲内，否则被钳成



                            // 固定延迟（只剩回声、没有刹车感）。长过渡 → 刹车更缓，短过渡 → 更狠。



                            float bp = (pos - 0.55f) / 0.45f;



                            if (bp < 0.0f) bp = 0.0f; if (bp > 1.0f) bp = 1.0f;



                            float kBrake = 0.97f;



                            if (step > 0.0f) {



                                const float nEst = 0.45f / step;                 // 刹车段总帧数



                                float kMax = (float)(TPL_BRAKE_MAX - 64) / (0.5f * nEst);



                                if (kMax < kBrake) kBrake = kMax;



                                if (kBrake < 0.05f) kBrake = 0.05f;



                            }



                            g_tplBrake[c][(int)g_tplBrakeWrAbs % TPL_BRAKE_MAX] = fA;



                            double rd = g_tplBrakeRd;



                            if (g_tplBrakeWrAbs - rd > (double)(TPL_BRAKE_MAX - 64))



                                rd = g_tplBrakeWrAbs - (double)(TPL_BRAKE_MAX - 64);



                            int i0 = (int)std::floor(rd); double fr = rd - i0;



                            int i0m = ((i0 % TPL_BRAKE_MAX) + TPL_BRAKE_MAX) % TPL_BRAKE_MAX;



                            int i1m = (i0m + 1) % TPL_BRAKE_MAX;



                            float s0 = g_tplBrake[c][i0m], s1 = g_tplBrake[c][i1m];



                            fA = (float)(s0 + (s1 - s0) * fr);



                            if (c == channels - 1) {



                                g_tplBrakeRd = rd + (double)(1.0f - kBrake * bp);



                                g_tplBrakeWrAbs += 1.0;



                            }



                        } else if (tpl == 4) {



                            // Dissolve Wash：A 低切上扫 + 双抽头 wash



                            float hp  = (1.0f - tplKA) * (g_tplHpA[c] + fA - g_tplHpAX[c]);



                            g_tplHpAX[c] = fA; g_tplHpA[c] = hp;



                            int rd = g_tplEchoWr - dl; if (rd < 0) rd += XFADE_DELAY_MAX;



                            int rd2 = g_tplEchoWr - dl * 2; if (rd2 < 0) rd2 += XFADE_DELAY_MAX;



                            float d1 = g_tplEcho[c][rd], d2 = g_tplEcho[c][rd2];



                            // 【V8.488】延迟线写入乘 gFrom（同 tpl8/tpl2）：避免满能量旧样本复活 A 轨。
                            g_tplEcho[c][g_tplEchoWr] = hp * gFrom;



                            float wash = d1 * 0.55f + d2 * 0.32f;



                            // 【V8.297】白噪音删除：原 Dissolve 在 pos≈0.5 处叠了一段中心白噪声扫频



                            // （用户反馈"太难听"），已移除。djay Dissolve 的听感核心是下方整段混音



                            // 沉进 g_tplReverb 的 Wash（混响），白噪音不是必需成分。



                            fA = hp * (1.0f - 0.85f * pos) + wash * pos;



                        } else if (tpl == 6) {



                            // 【V8.286】Beat Roll / Gate：把 A 当前切片循环（stutter roll）。



                            // 切片长度 = rollSlice（Kotlin 按下 1/2 或 1/4 拍下发，已做兜底/钳制）。



                            // 切片内做门限包络（attack 12% / release 35%）= chopped gate；过渡前 12%



                            // 保持 A 原样、12%~24% 交叉切入循环，落点由 gFrom 收掉。



                            float origA = fA;   // 进模板前的 A（写进循环缓冲，循环的是干净音频）



                            int ridx = g_tplRollStart + g_tplRollCnt;



                            if (ridx >= TPL_ROLL_MAX) ridx -= TPL_ROLL_MAX;



                            float s = g_tplRoll[c][ridx];



                            float frac = (float)g_tplRollCnt / (float)rollSlice;



                            float gate = 1.0f;



                            if (frac < 0.12f) gate = frac / 0.12f;          // attack



                            else if (frac > 0.65f) gate = (1.0f - frac) / 0.35f;   // release



                            if (gate < 0.0f) gate = 0.0f; if (gate > 1.0f) gate = 1.0f;



                            float amt = (pos < 0.12f) ? 0.0f



                                         : (pos < 0.24f ? (pos - 0.12f) / 0.12f : 1.0f);



                            fA = (amt <= 0.0f) ? origA : (s * gate * amt);



                            g_tplRoll[c][g_tplRollWr] = origA;   // 捕获原始 A（每个样本写一次）



                        } else if (tpl == 7) {



                            // 【V8.310 Riser 重做】**滤波扫频 riser**（不再是纯白噪音升扫）。



                            // V8.297 用户反馈「白噪音升扫太难听」→ 整个 riser 被删。根因：白噪是



                            // 全频带能量、没有音高感，纯像「撒盐」。这里改成：



                            //   ① 噪声先过低通（去刺耳高频）再过随 pos 上移的带通；



                            //   ② 带通中心频率 300Hz→6kHz 对数上扫，Q 随 pos 收窄（越到顶越"尖"）；



                            //   ③ 包络平方上升、末尾 18% 快速收掉（原来 0.85 才开始收，拖尾太长）。



                            // 听感从"噪声"变成"上冲的音"，这才是 DJ riser 的语义。



                            float nz = 0.0f;



                            g_tplNoise = g_tplNoise * 1664525u + 1013904223u;



                            nz = ((int)(g_tplNoise >> 9) / 8388607.5f) - 1.0f;



                            // ① 噪声整形：固定 2kHz 低通去掉高频毛刺



                            const float nkLp = 1.0f - expf(-TWO_PI * 2000.0f / (float)cfSr);



                            g_tplNsLp[c] += nkLp * (nz - g_tplNsLp[c]);



                            const float shaped = g_tplNsLp[c];



                            // ② 带通：中心 300Hz→6kHz 对数上扫，Q 0.7→4.7



                            const float bpF = 300.0f * (float)std::pow(20.0f, pos);



                            const float bpQ = 0.7f + 4.0f * pos;



                            const float kk = 1.0f - expf(-TWO_PI * bpF / (float)cfSr);



                            g_tplNsHpX[c] = (1.0f - kk) * (g_tplNsHpX[c] + shaped - g_tplNsHp[c]);



                            g_tplNsHp[c] = g_tplNsHpX[c];



                            g_tplNsBp[c] += kk * (g_tplNsHpX[c] - g_tplNsBp[c]);



                            // ③ 包络：平方上升 + 末尾快速收



                            float env = (pos < 0.82f) ? (pos / 0.82f) * (pos / 0.82f)



                                                      : (1.0f - (pos - 0.82f) / 0.18f);



                            if (env < 0.0f) env = 0.0f; if (env > 1.0f) env = 1.0f;



                            tplRiser = g_tplNsBp[c] * bpQ * env * 0.55f;



                        } else if (tpl == 8) {



                            // 【V8.287】Phrase 分段式 FX：按【小节头】离散分段（djay 8 小节方案，



                            // 自适应到本次过渡实际小节数）。与「按 pos 连续调制」的根本区别：FX 在



                            // 小节头切换，且 B 低频「先切后还原」。段1(前1/2)切 B 低频 → 段2(中1/4)



                            // A 加 HPF → 段3(后1/4) A 加 Echo → 末小节第4拍拉掉 A。



                            float elapsed = pos * phrDur;



                            int barIdx = (int)(elapsed / phrBar);



                            int beatInBar = (int)((elapsed - (float)barIdx * phrBar) / phrBeat);



                            if (beatInBar < 0) beatInBar = 0; if (beatInBar > 3) beatInBar = 3;



                            float segB = (phrBCutEnd > 0) ? (elapsed / (phrBar * (float)phrBCutEnd)) : 2.0f;



                            float segA = (elapsed - phrBar * (float)phrBCutEnd)



                                         / (phrBar * (float)(phrAHpfEnd - phrBCutEnd));



                            float segE = (elapsed - phrBar * (float)phrAHpfEnd)



                                         / (phrBar * (float)(phrTotalBars - phrAHpfEnd));



                            // 段1 包络：窗口内 0→1→0（前 1/4 切入、后 1/4 切出 = 先切后还原）



                            float bCutEnv = 0.0f;



                            if (segB > 0.0f && segB < 1.0f) {



                                float e = (segB < 0.25f) ? (segB / 0.25f)



                                                         : (segB > 0.75f ? (1.0f - segB) / 0.25f : 1.0f);



                                bCutEnv = e * e * (3.0f - 2.0f * e);



                            }



                            // 段2/段3：平滑 0→1（smoothstep）



                            float aHpfEnv = (segA < 0.0f) ? 0.0f : (segA > 1.0f ? 1.0f : segA);



                            aHpfEnv = aHpfEnv * aHpfEnv * (3.0f - 2.0f * aHpfEnv);



                            float aEchoEnv = (segE < 0.0f) ? 0.0f : (segE > 1.0f ? 1.0f : segE);



                            aEchoEnv = aEchoEnv * aEchoEnv * (3.0f - 2.0f * aEchoEnv);



                            // 段1：B 低频切（cutoff 随 bCutEnv 0→150Hz）—— B 先进场薄，中段还原



                            if (bCutEnv > 0.001f) {



                                float bcf = 150.0f * bCutEnv;



                                float bk = 1.0f - expf(-TWO_PI * bcf / (float)cfSr);



                                float hp = (1.0f - bk) * (g_tplPhBhp[c] + fB - g_tplPhBhpX[c]);



                                g_tplPhBhpX[c] = fB; g_tplPhBhp[c] = hp;



                                fB = hp;



                            }



                            // 段2：A HPF（fc 20→2k 随 aHpfEnv 对数扫，与 Filter Fade 同款）



                            // 【V8.457 修"A 轨退出前会又响起来一秒"】
                            //
                            // 真机曲线数据（TPL8-CURVE，V8.456，8 小节 tpl8）：
                            //   pos=0.50 gFrom=0.709 aHpfEnv=0.00 aRms=0.237
                            //   pos=0.65 gFrom=0.491 aHpfEnv=0.62 aRms=0.042
                            //   pos=0.75 gFrom=0.351 aHpfEnv=0.96 aRms=0.009← HPF 全开
                            //   pos=0.80 gFrom=0.317 aHpfEnv=0.00 aRms=0.011 ← HPF 撤销
                            //
                            // **aHpfEnv 在 0.75 冲到 0.96，0.80 突然归 0。**
                            // 根因：原来的门是 `if (aHpfEnv > 0.001f)` ——
                            // 段2 走完（aHpfEnv 变0）后，整个 HPF 块被**绕过**，
                            // 不是平滑撤销。于是段2 切掉的那 2kHz 以下中低频
                            // 在段3 开始的一瞬间**全部回来**，而此时 gFrom 还有 0.32。
                            //
                            // 听感就是"A 轨快要走了，声音突然变响一下" ——
                            // 而且因为切回来的正是被HPF 强调过的高频（2k 附近），
                            // 人耳对这段最敏感，响度感知被放大。
                            //
                            // 修法：HPF **一旦开启就保持到底**（不绕过），
                            // fc 继续按 aHpfEnv 上扫到最高（20*100^1 = 2kHz）后维持。
                            // 这样 A 轨的频谱在整个后半段单调收窄 → 主观音量单调下降。
                            // 末小节第 4 拍已有 `fA = 0.0f` 硬拉兜底，不会拖尾。
                            {
                                const float hpfAmt = (aHpfEnv > 0.001f || aEchoEnv > 0.001f)
                                    ? ((aHpfEnv > aEchoEnv) ? aHpfEnv : 1.0f)
                                    : 0.0f;
                                if (hpfAmt > 0.0f) {
                                    float acf = 20.0f * powf(100.0f, hpfAmt);
                                    float ak = 1.0f - expf(-TWO_PI * acf / (float)cfSr);
                                    float hp = (1.0f - ak) * (g_tplHpA[c] + fA - g_tplHpAX[c]);
                                    g_tplHpAX[c] = fA; g_tplHpA[c] = hp;
                                    fA = hp;
                                }
                            }



                            // 段3：A Echo（湿量随 aEchoEnv，最多 0.7）；延迟线始终填充（feedback）



                            {



                                int dl = g_tplEchoLen.load();



                                if (dl < 1 || dl >= XFADE_DELAY_MAX) dl = (int)(0.75f * (float)cfSr);



                                int rd = g_tplEchoWr - dl; if (rd < 0) rd += XFADE_DELAY_MAX;



                                float d = g_tplEcho[c][rd];



                                // 【V8.488 根治"A 轨退出前又响一下 / 被听成白噪音"】
                                // 旧：延迟线写入 `fA + d*0.35f` —— 捕获的是**满能量** A（段2 A 还响时
                                // 就已积压进延迟线）。段3 读出这个高能量旧样本 d，再乘 aEchoEnv*gFrom
                                // 叠加回 fA，足以把已淡出的 A「复活」→ 用户听到响→轻→又响。
                                // 修法：写入延迟线的内容整体乘 gFrom（捕获与反馈都乘）。这样延迟线里
                                // 存的就是「已随干声淡出的 A 拷贝」，读出叠加后绝不会超过干声包络，
                                // 主客观音量单调下降；最终混音再乘一次 gFrom 自然归零，接缝干净。
                                g_tplEcho[c][g_tplEchoWr] = fA * gFrom + d * 0.35f * gFrom;



                                // 【V8.452 修"A 轨退出前又响一下"】
                                //
                                // 原来：`fA = fA + d * 0.7f * aEchoEnv`
                                //aEchoEnv 是段3 包络（0→1 单调**上升**，见 14392）。
                                // 而 A 轨干声的淡出是 gFrom = cos(pos*pi/2)（1→0）。
                                // 两者方向相反 → 干声淡到 0 时，回声湿量仍在峰值，
                                // 把已经淡出的A 又"加回来" → 用户听到
                                // 「A 轨退出前会突然响一下」。
                                //
                                // 回声本质是 A 的延迟拷贝，**它的淡出曲线必须跟干声一致**。
                                // 修法：湿量再乘 gFrom（干声此刻的增益）。
                                // 这样干声归零时回声也归零，接缝干净。
                                // 同时把 0.7 降到 0.45：湿量峰值 0.7 时回声比干声还响，
                                // 即使曲线对齐也容易糊在一起。
                                const float echoWet = aEchoEnv * gFrom;
                                if (echoWet > 0.001f) fA = fA + d * 0.45f * echoWet;



                            }



                            // 末小节第 4 拍：拉掉 A（落到新歌的干净落点）



                            g_tpl8DbgBCut = bCutEnv;
                    g_tpl8DbgAHpf = aHpfEnv;
                    g_tpl8DbgEcho = aEchoEnv;



                    if (barIdx >= phrTotalBars - 1 && beatInBar >= 3) fA = 0.0f;



                        }



                        if (c == channels - 1) {



                            if (tpl == 2 || tpl == 4) {



                                g_tplEchoWr = (g_tplEchoWr + 1) % XFADE_DELAY_MAX;



                            } else if (tpl == 8) {



                                // 段3 延迟线始终推进（逐样本）



                                g_tplEchoWr = (g_tplEchoWr + 1) % XFADE_DELAY_MAX;



                            } else if (tpl == 6) {



                                // 写指针每样本前进一次；满 rollSlice 把读起点重锚到上一拍窗口



                                g_tplRollWr++;



                                if (g_tplRollWr >= TPL_ROLL_MAX) g_tplRollWr = 0;



                                g_tplRollCnt++;



                                if (g_tplRollCnt >= rollSlice) {



                                    g_tplRollCnt = 0;



                                    g_tplRollStart = g_tplRollWr - rollSlice;



                                    if (g_tplRollStart < 0) g_tplRollStart += TPL_ROLL_MAX;



                                }



                            }



                        }



                    }



                    // 【V8.310 Auto-solo 频段】对 outgoing(A) 做频段隔离：把非 solo 频段压掉，



                    // 过渡中只留一个频段「独奏」出来（djay 的 Auto Solo）。



                    // 与 stem 分轨路由不同：那是按**声部**分轨，这是按**频率**隔离。



                    // 只在过渡期生效、且不吃掉原模板（叠加在 fA 之后）。



                    if (c < 8 && g_xfadeSoloBand.load(std::memory_order_relaxed) > 0



                        && !stemMode) {



                        const int sb = g_xfadeSoloBand.load(std::memory_order_relaxed);



                        // 进出各留 15% 软过渡，避免中途硬切频段也"哒"一下



                        float sEnv = pos < 0.15f ? (pos / 0.15f)



                                : (pos > 0.85f ? (1.0f - pos) / 0.15f : 1.0f);



                        if (sEnv < 0.0f) sEnv = 0.0f; if (sEnv > 1.0f) sEnv = 1.0f;



                        // 目标频段在 pos=0.5 时最深，两头恢复原样



                        const float cut = 1.0f - sEnv * 0.92f;



                        if (cut > 0.001f) {



                            if (sb == 1) {          // 低频 solo：HPF 抬到 200Hz（压掉中高）



                                const float k = 1.0f - expf(-TWO_PI * 200.0f * cut / (float)cfSr);



                                g_tplSoloHp[c] = (1.0f - k) * (g_tplSoloHp[c] + fA - g_tplSoloHpX[c]);



                                g_tplSoloHpX[c] = fA; fA = g_tplSoloHp[c];



                            } else if (sb == 2) {   // 中频 solo：BPF ~200..3000Hz（HPF→LPF 级联）



                                const float k1 = 1.0f - expf(-TWO_PI * 200.0f * cut / (float)cfSr);



                                const float k2 = 1.0f - expf(-TWO_PI * 3000.0f * cut / (float)cfSr);



                                // 先高通去低频



                                g_tplSoloHpX[c] = (1.0f - k1) * (g_tplSoloHpX[c] + fA - g_tplSoloHp[c]);



                                g_tplSoloHp[c] = g_tplSoloHpX[c];



                                // 再低通去高频 → 得到带通



                                g_tplSoloLp[c] += k2 * (g_tplSoloHpX[c] - g_tplSoloLp[c]);



                                fA = g_tplSoloLp[c];



                            } else {                // 高频 solo：LPF 压到 4kHz（压掉低中）



                                const float k = 1.0f - expf(-TWO_PI * 4000.0f * cut / (float)cfSr);



                                g_tplSoloLp[c] += k * (fA - g_tplSoloLp[c]);



                                fA = g_tplSoloLp[c];



                            }



                        }



                    }



                    if (c < 8) {



                        if (fx == 1) {



                            // Filter：A 低通 FS→300Hz 扫出；B 高通 FS→20Hz 扫入



                            // 【V8.182】FS = 自适应起点（44.1k→20k 不变，48k→~14.9k 起步可听）



                            float fsStart = g_xfadeFiltStartHz.load(std::memory_order_relaxed);



                            float fcA = fsStart + (300.0f - fsStart) * pos;



                            float kA = 1.0f - expf(-TWO_PI * fcA / (float)cfSr);



                            g_xfadeFxLpA[c] += kA * (from - g_xfadeFxLpA[c]);



                            fA = g_xfadeFxLpA[c];



                            // 【V8.145 修正】B 轨 HPF 必须从 20kHz 降到 20Hz：起点仅高于 20k 通过（近静音），



                            // 终点全开。先前写成 300Hz→20kHz 方向相反，过渡结束时 B 被完全滤掉＝淡出成静音。



                            float fcB = fsStart + (20.0f - fsStart) * pos;



                            float kB = 1.0f - expf(-TWO_PI * fcB / (float)cfSr);



                            float hp = (1.0f - kB) * (g_xfadeFxHp[c] + to - g_xfadeFxHpX[c]);



                            g_xfadeFxHpX[c] = to; g_xfadeFxHp[c] = hp;



                            fB = hp;



                        } else if (fx == 2) {



                            // Eq：bass swap（A 低频渐出 / B 低频渐入），中高频等功率交叉



                            float kL = 1.0f - expf(-TWO_PI * 200.0f / (float)cfSr);



                            g_xfadeFxLpA[c] += kL * (from - g_xfadeFxLpA[c]);



                            g_xfadeFxLpB[c] += kL * (to - g_xfadeFxLpB[c]);



                            float aLow = g_xfadeFxLpA[c], aHigh = from - aLow;



                            float bLow = g_xfadeFxLpB[c], bHigh = to - bLow;



                            fA = aLow * gFrom * (1.0f - pos) + aHigh * gFrom;



                            fB = bLow * gTo * pos + bHigh * gTo;



                        } else if (fx == 3 || fx == 6) {



                            // Echo / Lunar Echo：A 轨按拍长进延迟线（feedback 不同）



                            int dl = g_xfadeDelayLen.load(std::memory_order_relaxed);



                            if (dl < 1) dl = 1; if (dl >= XFADE_DELAY_MAX) dl = XFADE_DELAY_MAX - 1;



                            int rd = g_xfadeDelayWr - dl; if (rd < 0) rd += XFADE_DELAY_MAX;



                            float* dlyBuf = (c == 0) ? g_xfadeDelayL : g_xfadeDelayR;



                            float d = dlyBuf[rd];



                            float fb = (fx == 6) ? 0.62f : 0.45f;



                            dlyBuf[g_xfadeDelayWr] = from + d * fb;



                            if (fx == 6) {



                                // 【V8.145】Lunar Echo：多抽头（1/2 拍 + 1 拍）制造宽广长尾消散



                                float tap2 = 0.0f;



                                int dl2 = dl * 2;



                                if (dl2 < XFADE_DELAY_MAX) {



                                    int rd2 = g_xfadeDelayWr - dl2; if (rd2 < 0) rd2 += XFADE_DELAY_MAX;



                                    tap2 = dlyBuf[rd2] * 0.40f;



                                }



                                fA = from + d * 0.75f + tap2;



                            } else {



                                fA = from + d * 0.55f;



                            }



                        }



                    }



                    // 【V8.505】always-on 过渡 sweeteners（与 djay tpl 正交，默认开）：
                    //   ② vocal-recede：出轨 A 中频(人声)随 pos 渐压，模拟 Neural-Mix 淡出上一首人声；
                    //   ① 扫频：出轨 A 渐闷(nyquist->250Hz)、入轨 B 由闷转清(300Hz->nyquist)。
                    if (c < 8) {
                        if (recedeDuckFrame > 0.0f) {
                            float lp = g_recedeLpA[c] + kRecL * (fA - g_recedeLpA[c]); g_recedeLpA[c] = lp;   // <150Hz
                            float hp = g_recedeHpA[c] + kRecH * (fA - g_recedeHpA[c]); g_recedeHpA[c] = hp;   // >4kHz
                            float mid = fA - lp - hp;
                            fA = lp + mid * (1.0f - recedeDuckFrame) + hp;
                        }
                        if (sweepOnFrame) {
                            fA = g_sweepLpA[c].process(fA);
                            fB = g_sweepLpB[c].process(fB);
                            if (fx != 1 && fx != 2) to = fB;   // 默认 fx==0/3/6 路径 outSample 用 to 而非 fB，须镜像
                        }
                    }

                    float outSample;



                    if (fx == 1) outSample = fA * gFrom + fB * gTo;



                    else if (fx == 2) outSample = fA + fB;                        // eq 已含增益



                    else if (fx == 3 || fx == 6) outSample = fA * gFrom + to * gTo; // A 含回声尾巴



                    else outSample = fA * gFrom * modGain + to * gTo;



                    // 【V8.456 诊断】量化 A 轨的**实际输出电平**随进度的曲线。
                    //
                    // 目的：定位「A 轨退出前会又响起来一秒」到底发生在哪一段。
                    // 之前 V8.452 改的是 tpl 1/2/4，但日志显示实际跑 tpl=8
                    // （`Crossfade tpl=8 echoSamples=...`），**tpl8 从没被看过**。
                    // 这次不猜，直接把逐帧 RMS 和各段包络打出来，看曲线形状。
                    //
                    // 判读方式：
                    //   pos 0.5→1.0 期间 aRms 应该是**单调下降**。
                    //   若出现 aRms 局部上升（尤其 0.75~0.95），那就是"又响起来"。
                    //   对着aEchoEnv / bCutEnv / aHpfEnv 一起看，可定位是哪一段效果。
                    if (xfadeDiagOn()) {
                        // 【V8.457 复查发现·分轨模式下 tpl8 整段被旁路】
                        // 日志：`template tpl=8 track-level bypassed (stem mode) -> routed to stem`
                        // → V8.456/457 改的 HPF/回声段**根本没执行**。
                        // 分轨模式下 A 轨是 1:1 直读（不经 fA），
                        // 所以要量的是 **ring 直读后的真实 A 输出**，
                        // 否则量到的 fA*gFrom 是"根本没走这条路"的假数据。
                        const float aOut = stemMode ? from * modGain : fA * gFrom * modGain;
                        const float bOut = stemMode ? to: to * gTo;
                        const float aE = aOut * aOut;
                        const float bE = bOut * bOut;
                        // 64 帧一块统计，避免逐帧 logcat
                        if ((f & 63) == 0) {
                            g_tpl8DbgPos   = pos + step * (float)f;
                            g_tpl8DbgAccA += aE;
                            g_tpl8DbgAccB += bE;
                            g_tpl8DbgN++;
                        }
                        if ((f & 63) == 63 && g_tpl8DbgN > 0) {
                            LOGI("TPL8-CURVE pos=%.4f aRms=%.5f bRms=%.5f gFrom=%.4f gTo=%.4f "
                                 "bCutEnv=%.3f aHpfEnv=%.3f aEchoEnv=%.3f",
                                 (double)g_tpl8DbgPos,
                                 (double)sqrtf(g_tpl8DbgAccA / g_tpl8DbgN),
                                 (double)sqrtf(g_tpl8DbgAccB / g_tpl8DbgN),
                                 (double)gFrom, (double)gTo,
                                 (double)g_tpl8DbgBCut, (double)g_tpl8DbgAHpf, (double)g_tpl8DbgEcho);
                            g_tpl8DbgPos = 0.0f;
                            g_tpl8DbgAccA = 0.0f;
                            g_tpl8DbgAccB = 0.0f;
                            g_tpl8DbgN = 0;
                        }
                    }



                    output[idx] = outSample + tplRiser;   // 【V8.286】Noise Sweep/Riser 叠加层（tpl!=7 时恒 0）



                }



                // 【V8.145】延迟线写指针每帧前进一次（所有声道写完后）



                if (fx == 3 || fx == 6) { g_xfadeDelayWr++; if (g_xfadeDelayWr >= XFADE_DELAY_MAX) g_xfadeDelayWr = 0; }



                pos += step;



            }



            // 【V8.281】Dissolve Wash 的湿声插入：整段混音在过渡中段沉进程序化混响，



            // 两轨的交接点被 wash 盖住（djay Dissolve 的听感核心）。湿量走 sin(π·pos)



            // 包络 —— 首尾严格 0，过渡结束那一刻不会留一条突然被截断的尾巴。



            if (g_xfadeTpl.load(std::memory_order_relaxed) == 4 && !stemMode) {



                if (g_tplRevNeedCfg.load(std::memory_order_relaxed)) {



                    const int srT = g_sampleRate.load();



                    g_tplReverb.reset();



                    g_tplReverb.configure(srT > 0 ? (float)srT : 44100.0f, 0.88f, 0.32f);



                    g_tplRevNeedCfg.store(0, std::memory_order_relaxed);



                }



                for (int f = 0; f < numFrames; f++) {



                    float p = tplPosBase + step * (float)f;



                    if (p < 0.0f) p = 0.0f; if (p > 1.0f) p = 1.0f;



                    // 【V8.284】djay 的 Wash Out 是「Reverb 100% Wet + Low Cut」：



                    // 湿量拉到接近全湿（0.62 只是加了一点混响，出不来音墙的虚化感）。



                    const float wet = 0.90f * sinf(3.14159265f * p);



                    if (wet <= 0.0005f) continue;



                    if (channels >= 2) {



                        float l = output[f * channels], r = output[f * channels + 1];



                        g_tplReverb.process(l, r, wet);



                        output[f * channels] = l; output[f * channels + 1] = r;



                    } else {



                        float a = output[f], b = output[f];



                        g_tplReverb.process(a, b, wet);



                        output[f] = (a + b) * 0.5f;



                    }



                }



            }



            // 【V8.243】混音母线真峰安全限幅 + Rubber Band keylock 连续相位锁定



            {



                const int sr0 = g_sampleRate.load(); const float fs = sr0 > 0 ? (float)sr0 : 44100.0f;



                const float dt = (float)numFrames / fs;   // 本回调时长（秒）



                // —— 真峰限幅：等功率让 RMS 恒定，但两轨瞬态叠加峰可能 >1.0，钳到 ~0.99 ——



                if (g_crossfadeActive.load()) {



                    if (!g_mixLimWasActive) { g_mixLimEnv = 0.0f; g_mixLimGain = 1.0f; g_mixLimWasActive = true; }



                    const float LIM_CEIL = 0.99f;          // -0.087 dBTP



                    const float LIM_ATT  = 0.5f;           // 增益收敛（快起）



                    const float LIM_REL  = 0.9992f;        // 峰值包络泄漏（慢落）



                    for (int i = 0; i < totalSamples; i++) {



                        const float x = output[i];



                        const float a = x < 0.0f ? -x : x;



                        g_mixLimEnv = (a > g_mixLimEnv) ? a : (g_mixLimEnv * LIM_REL + a * (1.0f - LIM_REL));



                        const float tgt = (g_mixLimEnv > LIM_CEIL) ? (LIM_CEIL / g_mixLimEnv) : 1.0f;



                        g_mixLimGain += (tgt - g_mixLimGain) * LIM_ATT;



                        output[i] = x * g_mixLimGain;



                    }



                } else {



                    g_mixLimWasActive = false;



                }



                // —— 【V8.284】Tempo Blend：推子推进期间把比率从 1.0 平滑拉到目标 ——



                // 期间不做 PLL 相位修正：速度本身在变，拍相位误差是预期内的，



                // 硬锁会跟推子的速度变化打架（表现为周期性的忽快忽慢）。



                if (g_bmBlendActive.load(std::memory_order_relaxed)) {



                    struct timespec _tbl; clock_gettime(CLOCK_MONOTONIC, &_tbl);



                    const int64_t nUs = (int64_t)_tbl.tv_sec * 1000000LL + _tbl.tv_nsec / 1000LL;



                                        // 【V8.508】ramp 进度跟随出轨 A 亮度包络：A 越淡、拉得越快。
                    // 出轨 A 增益 gFrom=cos(pos*PI/2)；令变速速率 ∝ A 安静度(1-gFrom)，
                    // 积分得进度 tb=(p-(2/PI)*sin(p*PI/2))/(1-2/PI)：
                    // 起点 A 满响 -> tb=0(不动)，尾段 A 静 -> tb=1(集中拉完)，变速藏进 A 最安静处。
                    const float blendPos = g_crossfadePos.load(std::memory_order_relaxed);
                    const float HALF_PI = 1.5707963f;
                    const float TWO_OVER_PI = 0.6366198f;   // 2/PI
                    float tb = (blendPos - TWO_OVER_PI * sinf(blendPos * HALF_PI)) / (1.0f - TWO_OVER_PI);
                    if (tb < 0.0f) tb = 0.0f;
                    if (blendPos >= 0.999f || tb >= 1.0f) {
                        tb = 1.0f;
                        g_bmBlendActive.store(false, std::memory_order_relaxed);
                        LOGI("TempoBlend done (ratio -> %.4f)", (double)g_bmBlendTo.load(std::memory_order_relaxed));
                    }
                    // tb 已是 0->1 凸曲线（起点缓、尾段陡），直接作 ramp 进度，不再套 smoothstep
                    const float to = g_bmBlendTo.load(std::memory_order_relaxed);
                    const float r = 1.0f + (to - 1.0f) * tb;



                    if (nUs - g_bmRampLastSetUs.load(std::memory_order_relaxed) >= 160000LL) {



                        g_bmRampLastSetUs.store(nUs, std::memory_order_relaxed);



                        if (g_beatmatchMutex.try_lock()) {



                            applyEngineRatio(g_bmEngineArmed.load(std::memory_order_relaxed), r);



                            g_beatmatchMutex.unlock();



                        }



                    }



                    g_bmPlRcur = r;



                    g_beatmatchRatio.store(r, std::memory_order_relaxed);



                }



                // —— 【V8.301】Pitch Drop：incoming 轨音高下扫（timeRatio 恒 1.0，变调不变速）——



                if (g_bmPitchActive.load(std::memory_order_relaxed)) {



                    struct timespec _tpd; clock_gettime(CLOCK_MONOTONIC, &_tpd);



                    const int64_t nUs = (int64_t)_tpd.tv_sec * 1000000LL + _tpd.tv_nsec / 1000LL;



                    const int64_t dur = g_bmPitchDurUs.load(std::memory_order_relaxed);



                    float tb = (dur > 0)



                        ? (float)((double)(nUs - g_bmPitchStartUs.load(std::memory_order_relaxed)) / (double)dur)



                        : 1.0f;



                    if (tb < 0.0f) tb = 0.0f;



                    if (tb >= 1.0f) {



                        tb = 1.0f;



                        g_bmPitchActive.store(false, std::memory_order_relaxed);



                        LOGI("PitchDrop done (pitch -> 1.0)");



                    }



                    // 下扫再回弹：中点下探到 g_bmPitchTo（默认下八度），落点回到 1.0



                    const float to = g_bmPitchTo.load(std::memory_order_relaxed);



                    float s = 1.0f - (1.0f - to) * sinf(3.14159265f * tb);



                    // 【V8.310 Pitch 量化档位】semi!=0 时不走连续扫，而是**按档位跳变**



                    // （djay 的 pitch bend 是离散的几个台阶，不是平滑滑音）：



                    // 在 4 个等分点上落到 -0/-semi/-2*semi 等档，每档保持 1/4 段。



                    const int semi = g_bmPitchSemi.load(std::memory_order_relaxed);



                    if (semi != 0) {



                        // 档位阶梯：1.0 → 目标档 → 目标档 → 1.0（分 4 段，段内保持不变）



                        const float tgt = (float)std::pow(1.0594630943590953, (double)semi);



                        float lvl;



                        if (tb < 0.25f)      lvl = 1.0f + (tgt - 1.0f) * (tb / 0.25f);



                        else if (tb < 0.50f) lvl = tgt;



                        else if (tb < 0.75f) lvl = tgt;



                        else                 lvl = tgt + (1.0f - tgt) * ((tb - 0.75f) / 0.25f);



                        s = lvl;



                    }



                    if (nUs - g_bmRampLastSetUs.load(std::memory_order_relaxed) >= 160000LL) {



                        g_bmRampLastSetUs.store(nUs, std::memory_order_relaxed);



                        if (g_beatmatchMutex.try_lock()) {



                            if (g_bmEngineArmed.load(std::memory_order_relaxed)) g_rbStretch.setPitchScale((double)s);



                            g_beatmatchMutex.unlock();



                        }



                    }



                }



                // —— keylock 连续相位锁定：按两轨拍相位误差微调拉伸比 ——



                // 【V8.289 修复】PLL 只在过渡进行中生效。过渡一结束 B 轨已停（无音频），若还按 B 的



                // 残影拍相位去追拉伸比，会让「仅剩的 A（新歌）」比率被 phantom B 以 ~30ms 网格来回拨动



                // → 落点后约 4s（比率回落窗口）持续「声音抖动」(wow/flutter)。加 g_crossfadeActive 闸门，



                // 落点后立即停 PLL，比率只由下面 160ms 网格的平滑回落（V8.266）接管，不再有高频扰动。



                // 【V8.321】收敛期 PLL 让位：两者都想改比率，会互相打架产生阶跃



                if (g_bmPlActive && !g_bmBlendActive.load(std::memory_order_relaxed) && !g_bmPitchActive.load(std::memory_order_relaxed)



                    && !g_bmConvergeActive.load(std::memory_order_relaxed) && g_crossfadeActive.load()) {



                    const float aFr = g_bmPlAfr.load(std::memory_order_relaxed);



                    const float bFr = g_bmPlBfr.load(std::memory_order_relaxed);



                    // 【V8.301·#3】翻转：引擎吃 A → A 有效节奏 = aFr/r（1/r 因子挪到 MA）；



                    // B 自然 = bFr。常规：引擎吃 B → B 有效节奏 = bFr/r（1/r 因子在 MB）。



                    const bool plFlip = g_bmEngSrcActive.load(std::memory_order_relaxed);



                    if (plFlip) {



                        g_bmPlMA += (aFr / 60.0f) * (1.0f / g_bmPlRcur) * dt;



                        g_bmPlMB += (bFr / 60.0f) * dt;



                    } else {



                        g_bmPlMA += (aFr / 60.0f) * dt;



                        g_bmPlMB += (bFr / 60.0f) * (1.0f / g_bmPlRcur) * dt;



                    }



                    struct timespec _tp; clock_gettime(CLOCK_MONOTONIC, &_tp);



                    const int64_t _now = (int64_t)_tp.tv_sec * 1000000LL + (int64_t)_tp.tv_nsec / 1000LL;



                    if (_now - g_bmPlLastUs >= 30000LL || g_bmPlLastUs == 0) {



                        g_bmPlLastUs = _now;



                        // 拍相位误差（拍）：B 相对 A，取小数部中心化到 [-0.5,0.5]



                        float err = (g_bmPlMB - g_bmPlMA);



                        err = err - floorf(err + 0.5f);



                        const float r0 = g_bmPlR0.load(std::memory_order_relaxed);



                        // err>0 → B 偏前 → 放慢 B（r 增大）令其回退



                        // 【V8.322 自适应增益】|err| 大 → 用大增益快速追上；接近 0 → 用小增益



                        // 避免在同步点两侧来回振荡。线性插值让中段平滑过渡（无增益突跳）。



                        const float aerr = fabsf(err);



                        const float gLo = g_bmPlGainLo.load(std::memory_order_relaxed);



                        const float gHi = g_bmPlGainHi.load(std::memory_order_relaxed);



                        const float gErr = (aerr > 0.25f) ? 1.0f



                                         : (aerr < 0.05f ? 0.0f : (aerr - 0.05f) / 0.20f);



                        const float gain = gLo + (gHi - gLo) * gErr;



                        float r = r0 * (1.0f + gain * err);



                        const float lo = r0 * 0.97f, hi = r0 * 1.03f;   // ±3% 上限（落在 ±8% 高质量区）



                        if (r < lo) r = lo; if (r > hi) r = hi;



                        // 【V8.322】死区可配。旧值 0.001 对应 0.1% 速度盲区，浮点 BPM 时代太粗。



                        if (fabsf(r - g_bmPlRcur) > g_bmPlDeadzone.load(std::memory_order_relaxed)) {



                            g_bmPlRcur = r;



                            g_beatmatchRatio.store(r, std::memory_order_relaxed);



                            if (g_beatmatchMutex.try_lock()) {



                                applyEngineRatio(g_bmEngineArmed.load(std::memory_order_relaxed), r);



                                g_beatmatchMutex.unlock();



                            }



                        }



                    }



                }



            }



            // —— 【V8.321 变速恢复顿挫】落点前速度收敛 ——



            // 最后 12% 区间把比率按 smoothstep 拉回 1.0，让落点时无速度阶跃。



            // 只在「引擎活着 + 非blend/pitch」时做（blend/pitch 各自有收尾逻辑，别抢）。



            if (g_beatmatchLive.load(std::memory_order_relaxed)



                && !g_bmBlendActive.load(std::memory_order_relaxed)



                && !g_bmPitchActive.load(std::memory_order_relaxed)



                && pos >= 0.45f) {


                g_bmConvergeActive.store(true, std::memory_order_relaxed);


                // tc: 0（起收敛）→ 1（落点）。用 pos 而非墙钟，保证与推子严格同步。


                // 【V8.340 速度恢复顿挫修复】三点改动同向：


                //   a) 收敛起点 0.88 -> 0.55：原窗口只占最后 12%（16s 过渡≈2s），ratio 来不及拉平；


                //   b) 160ms 网格 -> 每帧：160ms 的阶跃本身就是「卡」的来源；


                //   c) smoothstep -> smootherstep (6t^5-15t^4+10t^3)：多一阶，


                //      两端一阶/二阶导数为 0，起步与收尾都无加速度突变。


                // 根因：落点时 retireTimeEngineAtLanding 一刀切退役引擎、新轨改 1:1 直读，


                //      速率映射**突然换轨**。若此时 ratio 仍差残值，就是听感「顿一下」。


                //      （8.333 修好落点位置回退后这处顿挫反而更凸显——两症状此消彼长。）


                // 【V8.421】恢复窗口提前到「落点前 N 拍」完成：N 拍占整段过渡比例 = N*beatMs/durMs。
                // 这样过渡尾巴的 N 拍里 B 已是原速，落点无缝，不再靠 V8.351 的「落点后 4s 缓降」兜底
                // （那才是用户听到的"生硬"）。覆盖 Tempo Blend：blend 把 B 拉到 rt 后，原地让 rt→1.0
                // 在尾巴完成。
                const float beatMs = g_xfadeBeatMs.load();
                const int xfDur = g_crossfadeDurationMs.load();
                const float totalBeats = (beatMs > 20.0f && xfDur > 0) ? (float)xfDur / beatMs : 32.0f;
                const float restoreBeats = (totalBeats > 8.0f) ? 4.0f : (totalBeats * 0.25f);
                float endPos = 1.0f - restoreBeats / totalBeats;
                if (endPos < 0.35f) endPos = 0.35f;
                if (endPos > 0.92f) endPos = 0.92f;
                constexpr float kConvergeStart = 0.45f;


                const float tcRaw = (pos - kConvergeStart) / (endPos - kConvergeStart);


                const float tc = tcRaw > 1.0f ? 1.0f : (tcRaw < 0.0f ? 0.0f : tcRaw);


                const float ce = tc * tc * tc * (tc * (tc * 6.0f - 15.0f) + 10.0f);


                // 目标必须是 **1.0** 而不是 g_bmPlR0（beatmatch 目标比率，如 0.9995）：


                // 落点后新轨是 1:1 直读（速度=1.0），收到 plR0 反而会在落点留一个残差阶跃。


                constexpr float kConvergeTarget = 1.0f;


                const float r = g_bmPlRcur + (kConvergeTarget - g_bmPlRcur) * ce;


                // 每帧更新；try_lock 保护：拿不到锁就跳过本帧，不排队不阻塞。


                if (g_beatmatchMutex.try_lock()) {


                    applyEngineRatio(g_bmEngineArmed.load(std::memory_order_relaxed), r);


                    g_beatmatchMutex.unlock();


                }


                g_bmPlRcur = r;


            }


            if (toReadFrom < totalSamples || toReadTo < totalSamples) {



                g_underrunCount.fetch_add(1, std::memory_order_relaxed);



            }



            if (pos >= 1.0f) {



                //【V8.340 诊断】落点时的速度收敛残差 = 听感「顿一下」的量级。
                // 频繁打出 >0.005 残差就说明收敛来不及拉平，retireTimeEngineAtLanding
                // 的「退役引擎 + 改 1:1 直读」造成速率映射突然换轨。
                {
                    const float plEnd = g_bmPlRcur;
                    if (fabsf(plEnd - 1.0f) > 0.0005f) {
                        LOGI("XFADE-SPEED landing residual=%.5f (tempo %.3f%%)",
                             (double)plEnd, (double)((plEnd - 1.0f) * 100.0));
                    }
                }
                // crossfade 完成：翻转 active 槽，停旧 active（无阻塞，join 延后到下次 open/stop）



                g_crossfadeActive.store(false);



                // 【V8.245】自动过渡完成不再出声（旧 xfade_end 音效删除：vinyl brake 的



                // "刹车"语义只在手动切歌成立，B 已完整在放后才刹车是反的）。



                // 【V8.227 de-click】上膛；末样本延到本回调最末（DSP 后）采集。



                // 顺带清零防浑浊滤波器历史态（上一首过渡的残留，同类阶跃源）。



                g_declickArmPending = true;



                for (int c = 0; c < 8; c++) {



                    g_xfadeHpfPrevB[c] = 0.0f; g_xfadeLpfPrevA[c] = 0.0f;



                    g_xfadeLpAHi[c] = 0.0f; g_xfadeLpBLow[c] = 0.0f; g_xfadeLpBHi[c] = 0.0f;   // 【V8.246】三段频状态



                }



                // 【M2】过渡结束，WSOLA 退役：新 active 轨恢复正常速率播放，



                // 残留的输出样本直接丢弃（下一轮 crossfade 会重新 reset）。



                // 【V8.253】原本过渡完成后启动 4s 比率回落：新活跃轨继续过拉伸引擎、比率滑回



                // 1.0 再退役。但回落路径下同一引擎实例仍按 incoming 轨的比率/相位状态在跑，



                // 落点后把新 active 轨（与 toRing 同环）继续喂进该引擎并逐帧改比率 → 变速对齐



                // ON 时严重噪音（Msg 4）。【V8.292 落点后噪音修复·方案1】改为落点即退役引擎 +



                // 关 beatmatch，正常路径按 activeRing 1:1 直读（与引擎消费同环同游标，无位置



                // 错位/无停顿），落点处 ≤3% 的细微 tempo 阶跃由 g_declickArmPending 包络兜底。



                if (g_beatmatchLive.load(std::memory_order_relaxed)) {



                    // 【V8.301·#3 架构】落点延迟补偿 + 引擎退役统一走 retireTimeEngineAtLanding。



                    // 落点后新 active = incoming 环。非翻转：引擎源就是它 → 需 ring 对齐(rewind L)；



                    // 翻转：引擎源是即将退役的 outgoing(active) 环，新 active 是 1:1 → 不对齐。



                    const bool flip = g_bmEngSrcActive.load(std::memory_order_relaxed);
                    PCMRingBuffer* nr = (!fromB) ? g_ringBufferB : g_ringBuffer;   // 落点后新 active 环
                    // V8.351: SMOOTH THE LANDING SPEED instead of snapping it.
                    // Symptom: right after the transition the audio "fast-forwards a bit"
                    // / hitches. Cause: the PLL had been pulling the ratio to 1.0 (V8.341
                    // logs residual < 0.05% so the speed itself converged), but the
                    // ACTIVE ratio at that instant was still whatever the beatmatch
                    // blend left it at (e.g. 1.03). retireTimeEngineAtLanding then
                    // switches to 1:1 direct read in one frame -> an audible rate step.
                    //
                    // A ramp-back mechanism already exists (g_bmRampActive / g_bmRampFrom
                    // / g_bmRampStartUs, consumed at ~16229 with a4s smoothstep), but
                    // NOTHING EVER SET g_bmRampActive to true -- the three stores were
                    // all `store(false)`. So it was dead code and the step was unavoidable.
                    //
                    // Fix: when the residual rate is still meaningfully away from 1.0,
                    // hand over to that ramp instead of retiring outright. It will call
                    // retireTimeEngineAtLanding("ramp-done", ...) itself once settled.
                    const float endRatio = g_bmPlRcur;   // plain float (4525), not an atomic
                    // 【V8.462】启动 ramp 前先确认引擎真的能接管输出。
                    //
                    // 实测（V8.460，12:09:25那轮）：
                    //   RAMPSYNC engSrc -> B  ← 我改了引擎源
                    //   RAMPOUT=0← 但引擎一次都没接管输出（4 秒后才有 ramp-done）
                    //   落点后 140ms 起 inner 暴涨到 0.18~0.40← 高频噪声
                    //
                    // `inner` 是 buffer **内部**相邻样本的自然跃变，它飙到 0.4
                    // 意味着信号本身成了高频噪声 —— 不是接缝错位（那由 edge 体现）。
                    // 成因：引擎被 arm、源已切到新轨，但输出走的是 1:1 直读，
                    // 引擎的 1024 帧缓冲被持续喂入却不被消费，
                    // 状态与实际输出源不一致。
                    //
                    // 也就是说：ramp 机制是**半死不活**的
                    //（V8.292 注释说它已废除，V8.441 复活了但没让它真正工作）。
                    // 拿不到互斥锁就静默退回 1:1，却把 rampActive 留在 true。
                    //
                    // 这里加一道前置检查：拿不到锁就不启动 ramp，
                    // 直接走退役 —— 宁可让残留速率硬切（V8.262 说的"咔一下"），
                    // 也不要进入"引擎空转 + 输出走另一条路"的状态产生持续噪声。
                    // 【V8.475 修"B 轨过渡完成后几秒都是噪音"· 真正的机制】
                    //
                    // 真机取证（15:01:56.640 落点，设备 31978）：
                    //   XFADE-DONE ... underrun=72 pos=1.0014
                    //   XFADE-SPEED ramp-down from 1.01309 over 4s
                    //   15:01:56.718 XFADE-RAMPOUT takeover / RAMPOUTLVL got=246
                    //   15:01:57.059 ... got=95    15:01:57.358 ... got=57
                    //   15:01:57.727 ... got=244   15:01:58.016 ... got=174
                    //   同期 XF-CLICK: n=5 edge=0.3890 inner=0.0355
                    //                 n=7 edge=0.2238 inner=0.0298
                    //                 n=12 edge=0.2047 inner=0.0155
                    //   **edge 是 inner 的 6~13 倍** → 真断流，不是音乐本身的高频。
                    //
                    // 机制：落点后引擎接管输出 4 秒做速率回落（1.013 → 1.0），
                    // 但它**每次只能吐 57~246 帧，而 callback 要 3200 帧**，
                    // 剩下 95% 全靠 XFADE-RAMPPAD 从 ring 直接补。
                    // 引擎吐的是**变速过**的样本，ring 补的是**原速**样本，
                    // 两者在每个 callback 内部拼在一起 —— 拼接处就是持续的阶跃。
                    // 用户听到的"过渡后几秒都是噪音"就是这 4 秒。
                    //
                    // 值不值？endRatio=1.01309 → 只有 **1.3%** 的音高差，
                    // 为这 1.3% 付 4 秒的持续阶跃，完全不划算。
                    //
                    // 修法：
                    //   a) 门槛 0.4% → **2%**：速率差不到 2% 直接 snap 到 1.0，
                    //      根本不进 ramp（2% 的音高差人耳几乎不可辨）。
                    //   b) 时长 4s → **1.2s**：真需要 ramp 时也尽快收敛。
                    //   c) 再加健康度兜底（见 RAMPSTARVE）：引擎连续供不上就立刻退出。
                    // 【V8.479 彻底关掉落点后的 ramp-down】
                    //
                    // V8.478 把 BPM 选曲改成"方向化"后，相邻两首的 BPM 差明显变大
                    // → 变速比 endRatio 更大 → 落点后更容易进 ramp
                    // → 用户直接反馈"切歌后**都是**噪音"。
                    //
                    // ramp 的代价从未被真正解决：引擎每帧只能吐 57~246 帧
                    //（需求 3200），剩余 95% 由 XFADE-RAMPPAD 从 ring 补**原速**
                    // 样本，变速样本与原速样本在同一 callback 内拼接 → 持续阶跃。
                    // V8.475 调门槛/调时长只是把窗口缩小，根子还在。
                    //
                    // 而 ramp 想消掉的只是"速率硬切"这一点点瑕疵 ——
                    // V8.473 的接缝平滑（SEAM_FRAMES=256 / 约 5.8ms 线性插值）
                    // 本来就是为硬切准备的，实测已把落点 edge 从 0.1130 压到 0.0004。
                    // 也就是说：**不 ramp、直接退役引擎 + 接缝平滑** 既干净又简单。
                    //
                    // 结论：ramp 这条路收益 < 代价，整体关掉，走 V8.292 的
                    // "落点即退役引擎、新活跃轨 1:1 直读"。
                    bool canRamp = false;
                    if (canRamp) {
                        struct timespec _tr; clock_gettime(CLOCK_MONOTONIC, &_tr);
                        // 【V8.459 修"切歌落点那一下的噪音"——真根因】
                        //
                        // 真机取证（V8.459，设备 11310，edge/inner 检测器首次抓到真断流）：
                        //   12:00:04.802 XFADE-DONE fromB=0 activeB=1 pos=1.0951
                        //   12:00:04.802 Crossfade complete: B is now active← 播放已切到 B
                        //   12:00:04.865 XFADE-RAMPOUT engSrcIsA=1 activeIsB=1 ← 引擎还吃 A！
                        //   12:00:04.869 XF-CLICK n=0 edge=0.7194 inner=0.0367  ← 20倍跃变
                        //
                        // **引擎源没跟着 active 一起翻转。**
                        // 落点时 `g_activeIsB.store(!fromB)`（16012）把播放切到新轨，
                        // 但 ramp 分支在此之前（15985）就启动了，
                        // 而 `g_bmEngSrcActive` 还是旧值 —— 于是 ramp 期间
                        // 引擎从**旧轨的环**读数据往输出里送，
                        // 播放指针却已在**新轨**上。内容错位 → 满幅级不连续。
                        //
                        // 之前一直没抓到，是因为 V8.449 之前那个检测器
                        // 把相隔 72ms 的两个采样点相减，"跃变"全是假的。
                        //
                        // 修法：ramp 启动时把引擎源同步到即将成为 active 的那一轨。
                        // 此时 fromB 还没翻转，所以新 active = !fromB，
                        // 引擎源应当与之对齐（g_bmEngSrcActive = !fromB）。
                        g_rampPrimed.store(false, std::memory_order_relaxed);
                        g_bmEngSrcActive.store(!fromB, std::memory_order_relaxed);
                        LOGI("XFADE-RAMPSYNC engSrc -> %s (fromB=%d) so ramp reads the track that becomes active",
                             (!fromB) ? "B" : "A", (int)fromB);
                        g_bmRampFrom.store(endRatio, std::memory_order_relaxed);
                        g_bmRampStartUs.store((int64_t)_tr.tv_sec * 1000000LL + (int64_t)_tr.tv_nsec / 1000LL,
                                              std::memory_order_relaxed);
                        g_bmRampActive.store(true, std::memory_order_relaxed);
                        g_rampStarve.store(0, std::memory_order_relaxed);   // 【V8.475】
                        LOGI("XFADE-SPEED ramp-down from %.5f over 4s (was snapping to 1.0)",
                             (double)endRatio);
                    } else {
                        // 【V8.441 诊断】落点两侧的环归属打成一行。
                        // 关键是要看到「引擎源环」和「落点后新 active 环」是不是同一个：
                        // 同源才可能做 ring rewind 补偿，异源则补偿物理上不成立。
                        LOGI("XFADE-LANDMAP fromB=%d flip=%d engSrcIsA=%d newActiveIsB=%d sameRing=%d "
                             "ringA_avail=%d ringB_avail=%d",
                             (int)fromB, (int)flip,
                             (int)(flip ? !fromB : fromB),
                             (int)(!fromB),
                             (int)((flip ? !fromB : fromB) == (!fromB)),
                             g_ringBuffer ? g_ringBuffer->available() : -1,
                             g_ringBufferB ? g_ringBufferB->available() : -1);
                        retireTimeEngineAtLanding("crossfade-end", nr, /*newActiveIsEngSrc=*/!flip, channels);
                    }
                }



                g_activeIsB.store(!fromB);

                // 【V8.512】落点不再清零 key match semi：保留到下方 1.2s 速度回落窗口，由 resolveEngineRatio
                // 的「ramp 期不动调」闸门在落点后施加调性平移（出轨 A 淡出途中贴 B 调）。下一轮过渡由 Kotlin
                // 在 arming 时重新下发（nativeSetDjExtras 里先清零再覆写），不会跨轮残留。



                // 【V8.204 诊断】翻转瞬间取证：新 active 轨 ring 里到底还有多少数据、



                // 过渡末两条轨各自缺了多少帧（0 = 没缺），以及累计 underrun 数。



                // 若这里 shortA/shortB 有值，说明过渡期间就有人在供不上，不是翻转本身的问题。



                {



                    PCMRingBuffer* newActive = g_activeIsB.load() ? g_ringBufferB : g_ringBuffer;



                    struct timespec _tc; clock_gettime(CLOCK_MONOTONIC, &_tc);



                    int64_t _us = (int64_t)_tc.tv_sec * 1000000LL + (int64_t)_tc.tv_nsec / 1000LL;



                    g_xfadePostUntilUs.store(_us + 900000LL, std::memory_order_relaxed);
                    // [V8.502] seam probe: mark seam time + open ~6s capture window
                    g_lastSeamUs.store(_us, std::memory_order_relaxed);
                    g_seamSeq.fetch_add(1, std::memory_order_relaxed);
                    g_seamProbeUntilUs.store(_us + 6000000LL, std::memory_order_relaxed);



                    int na = newActive ? newActive->available() : -1;



                    LOGI("XFADE-DONE fromB=%d activeB=%d newAvail=%d shortA=%d shortB=%d underrun=%d pos=%.4f",



                         (int)fromB, (int)g_activeIsB.load(), na,



                         totalSamples - toReadFrom, totalSamples - toReadTo,



                         (int)g_underrunCount.load(std::memory_order_relaxed),



                         (double)pos);



                }



                // 【Crossfade】新 active 轨已播 crossfade 时长，重置播放位置（否则延续旧轨 position）



                // 【V8.183 修正】公式原来不分方向一律 bStartMs + 过渡时长，但只有 B→A 的



                // 对面方向才对：B→A（fromB=true）时 g_xfadeBStartMs 存的是【上一轮】B 轨的



                // 副歌起点，与新 active 的 A 毫无关系，照抄会让进度条先猛跳到「上一首副歌



                // 位置 + 过渡时长」再一路回退。A 从 0 起播，位置就是已经播过的过渡时长。



                // 【V8.309 修正·进度倒退】上面那条对 A→B 仍不成立：手动触发传 bStartMs=0



                //   （MusicService:1179），于是 newPosUs = 0 + 过渡时长（本例 20s），而真实



                //   播放位置是好几分钟 → 落点瞬间进度条/歌词**往回退**（用户观感「回退半秒」）。



                //   且该位置会被下一轮 snapBToBarGrid / currentBeatInBar 读去做拍位对齐，



                //   连带污染下一次切歌的对拍。



                //【V8.323 时长/位置脱钩修复】本块原先取「旧轨位置 + 过渡时长」，目的是消除



                // 落点瞬间进度条倒退。但那个值属于**旧轨的时间轴**，而同一函数



                // nativeGetDurationMs() 在 active 翻转后已经改读 g_cachedDurationB（新轨时长）——



                // 位置与分母来自两首歌，remain = dur - pos 恒为大负数，



                // 于是 crossfadeMonitor 每 tick 都命中 remain<=dur 反复触发窗口判定、



                // UI 进度条长期 >100%、时长显示被污染（用户报「时长一直停在3:24」）。



                // 正确口径：新 active 轨的位置必须落在**新轨自己的时间轴**上 =



                //   B 入场点(bStartMs) + 本次过渡时长（B 从入场点起播，混音整段后正好到这里）。



                // bStartMs=0（手动触发）时退化为 0 + 过渡时长，同样在 B 轴上，正确。



                // 这样位置与 durationB 恒同轴，remain 恢复真实剩余时长。



                const int64_t xfDurUs = (int64_t)g_crossfadeDurationMs.load() * 1000LL;



                const int64_t bStartUs = (int64_t)g_xfadeBStartMs.load(std::memory_order_relaxed) * 1000LL;



                const bool   toB   = !fromB;   // 本次过渡的入场轨是 B（fromB=false 表示 B→A）



                // toB 时新 active 是 B → 用 B 的入场点；否则新 active 是 A（A 一直从 0 起播）



                const int64_t newBaseUs = toB ? (bStartUs > 0 ? bStartUs : 0LL) : 0LL;



                int64_t newPosUs = newBaseUs + xfDurUs;



                // 【V8.333 落点回退修复·用户报"过渡后会回退好几秒"】



                // 上面那个算术值（入场点 + 过渡时长）只在「B 以 1.0 倍速整段播放」时成立。



                // 但过渡期间 B 是**被变速引擎拉伸消费**的：keylock ratio（日志实测 1.01434）



                // 加上 PLL 每帧微调，引擎从 ring 里取走的源素材总时长 > 实际播放时长。



                // 落点后引擎退役、B 改为 1:1 直读，读游标停在**真实消耗处**，



                // 而计数器被上面那行拽回算术值 → 两者差出一个累积量，



                // 听感就是过渡结束后「回退好几秒」（本例 ratio 1.0143×14.3s ≈ 0.2s，



                // PLL 收敛期间偏差更大；ratio 更大或过渡更长时可达数秒）。



                //



                // 正解：位置必须取**新 active 轨解码器的真实时间戳**，它才是落点后



                // 1:1 直读将要继续的那个点，天然与实际音频同轴、无累积误差。



                // 算术值只作为解码器时间戳不可用时的兜底。



                // [V8.344 FIX] Decoder timestamp must be measured FROM THE ENTRY POINT,
                // not from when the decoder was opened.
                //
                // Bug (found on device, V8.343): g_decoderPositionB keeps advancing while B
                // merely sits in the incoming slot preloading -- it is a running total since
                // openIncoming(), not "how long B has been audible". Landing therefore read
                // a value inflated by the whole preload window:
                //   realPos=31300ms arith=9696ms drift=21604ms  (3.2x the transition)
                //   realPos=29071ms arith=8727ms  drift=20344ms
                // repeated on all 5 landings -> the position jumped ~20s forward right after
                // the transition, audible as a seek.
                //
                // Fix: require the decoder timestamp to be ahead of the entry point; that
                // makes it "elapsed since B became audible", i.e. exactly the point the 1:1
                // direct read continues from -- same timeline as newDur. If it is still
                // behind bStart (decoder was rewound by seekIncoming and has not caught up),
                // fall back to the arithmetic value.
                // V8.362: DO NOT adopt the decoder position here. Removed outright.
                //
                // History (three attempts, each wrong in a different way):
                //   V8.333 adopted it whenever drift > 400ms -> the progress bar jumped FORWARD
                //           by ~20s. g_decoderPositionB is an open-loop counter that has been
                //           accumulating since openIncoming, i.e. it includes the whole preload
                //           window, not just "how long B has been audible".
                //   V8.345 guarded it with (rawPosUs > newBaseUs) -> useless when bStart == 0,
                //           because the counter is then trivially > 0, so the bad value sailed
                //           straight through. Measured on a plain crossfade with the time-stretch
                //           engine OFF: pos=1.0018 (0.18% off) yet drift was 14606ms.
                //   V8.362 removed it. The arithmetic value (entry point + transition duration) is
                //           the only correct answer, and it is right in BOTH cases:
                //             bStart == 0 -> dur
                //             bStart  > 0 -> bStart + dur
                //
                // The decoder counter is NOT "how far B has played" -- it is "how far B's decoder
                // has read", which keeps advancing while B sits idle in the incoming slot.
                // Using it as a playback position mixes two different time axes.
                //
                // Kept as a diagnostic only: log the mismatch, never act on it.
                {
                    const int64_t rawPosUs = toB ? g_decoderPositionB.load(std::memory_order_relaxed)
                                                 : g_decoderPositionUs.load(std::memory_order_relaxed);
                    const int64_t driftMs = (rawPosUs - newPosUs) / 1000LL;
                    if (rawPosUs > 0 && (driftMs > 3000 || driftMs < -3000)) {
                        LOGI("XFADE-POS diag: decoder pos=%lldms vs arith=%lldms (drift=%lldms)"
                             " - preload-inclusive counter, NOT adopted",
                             (long long)(rawPosUs / 1000), (long long)(newPosUs / 1000),
                             (long long)driftMs);
                    }
                }



                // 兜底：新轨时长已知时位置不得超过它（避免入场点+dur 越界造成分母倒挂）



                const int64_t newDurUs = toB ? g_cachedDurationB.load(std::memory_order_relaxed)



                                             : g_cachedDurationUs.load(std::memory_order_relaxed);



                if (newDurUs > 0 && newPosUs > newDurUs) newPosUs = newDurUs;



                if (newPosUs < 0) newPosUs = 0;



                g_playbackPositionUs.store(newPosUs);



                // 【V8.333】只在新 active 确实是 A 槽时才同步 A 的解码位置。



                // 原代码无条件写 g_decoderPositionUs：toB=1（B 上位）时会把 A 槽的



                // 解码位置改成 B 的值，而 A 槽解码线程还在跑、很快又写回自己的值——



                // 两个值来回抢，同轴兜底与位置查询都可能读到错的数。



                if (!toB) g_decoderPositionUs.store(newPosUs);



                LOGI("XFADE-POS new=%lldms (own-axis base=%lldms + dur=%lldms, bStart=%lldms, newDur=%lldms, toB=%d)",



                     (long long)(newPosUs / 1000), (long long)(newBaseUs / 1000),



                     (long long)(xfDurUs / 1000), (long long)(g_xfadeBStartMs.load()),



                     (long long)(newDurUs / 1000), toB ? 1 : 0);



                g_xfadeBPreGain.store(1.0f, std::memory_order_relaxed);  // 【V8.24】B 上位后恢复全音量



                if (fromB) {



                    g_decoderStopRequestedB.store(true);



                    g_decoderCvB.notify_all();



                    LOGI("Crossfade complete: A is now active");
                    g_seamLeft.store(SEAM_FRAMES, std::memory_order_relaxed);   // 【V8.473】



                } else {



                    g_decoderStopRequested.store(true);



                    g_decoderCv.notify_all();



                    LOGI("Crossfade complete: B is now active");
                    g_seamLeft.store(SEAM_FRAMES, std::memory_order_relaxed);   // 【V8.473】



                }



            } else {



                g_crossfadePos.store(pos);



            }



            // 【V8.204 诊断】过渡全程输出电平曲线 + 欠载取证。



            // 「自动切歌闪一下」只能靠逐 callback 的 RMS 曲线定位：若某几帧 RMS 明显



            // 低于相邻帧，那就是 audible gap（混音里有一条轨供不上，另一条还在半淡入淡出）。



            {



                float xfRms = 0.0f;



                if (totalSamples > 0) {



                    float s2 = 0.0f;



                    for (int i = 0; i < totalSamples; i++) s2 += output[i] * output[i];



                    xfRms = sqrtf(s2 / (float)totalSamples);



                }



                static int dbgSeq = -1;



                static std::atomic<int> dbgN{0};



                const int seq = g_crossfadeSeq.load(std::memory_order_relaxed);



                if (seq != dbgSeq) { dbgSeq = seq; dbgN.store(0, std::memory_order_relaxed); }



                if (dbgN.fetch_add(1, std::memory_order_relaxed) < 400) {



                    // 【V8.448诊断】原用 LOGD_GATED —— 编译期裁掉，
                    // 这条"过渡期间逐帧取证"从来没输出过。
                    // 改用 LOGI + 窗口门；400 帧上限保留（过渡约9s ≈ 900帧，取前400帧足够）。
                    LOGI("XF-DBG i=%d pos=%.4f rms=%.5f aF=%d aT=%d shortA=%d shortB=%d "
                         "fL=%.6f fR=%.6f lL=%.6f lR=%.6f",



                         (int)dbgN.load(std::memory_order_relaxed), (double)pos, xfRms,



                         availFrom, availTo, totalSamples - toReadFrom, totalSamples - toReadTo,



                         (double)output[0],



                         (double)(channels > 1 ? output[1] : output[0]),



                         (double)output[totalSamples - channels],



                         (double)output[totalSamples - 1]);



                }



            }



            // 【V8.183 进度条冻结修复】crossfade 期间 g_playbackPositionUs 必须在走。



            // 原实现只在下面 else 的「正常路径」累加它，于是过渡的这几秒（默认 2~8s）



            // 里 nativeGetPositionMs() 一直返回过渡开始时的固定值 —— 进度条整段冻住，



            // 这正是「交叉切歌后会卡住」的直接观感来源。此时两轨都还在出声，按帧累加上去。



            {



                int cfPosSr = g_sampleRate.load(); if (cfPosSr <= 0) cfPosSr = 44100;



                g_playbackPositionUs.fetch_add((int64_t)numFrames * 1000000LL / cfPosSr,



                                               std::memory_order_relaxed);



            }



            // 【V8.183 A/B 电平分离·续】把上面逐采样循环里分别累计的单轨能量开方成



            // 慢速 RMS 写回两条 atomic，供过渡可视化的 A/B 电平条与 B 预增益使用。



            if (toRead > 0) {



                int rn = toRead < 4096 ? toRead : 4096;



                if (rn < 1) rn = 1;



                const float rA = g_xfadeSumA > 0.0f ? sqrtf(g_xfadeSumA / (float)rn) : 0.0f;



                const float rB = g_xfadeSumB > 0.0f ? sqrtf(g_xfadeSumB / (float)rn) : 0.0f;



                float pa = g_xfadeARmsSlow.load(std::memory_order_relaxed);



                g_xfadeARmsSlow.store(pa * 0.98f + rA * 0.02f, std::memory_order_relaxed);



                float pb = g_xfadeBRmsSlow.load(std::memory_order_relaxed);



                g_xfadeBRmsSlow.store(pb * 0.98f + rB * 0.02f, std::memory_order_relaxed);



            }



            // 方案 B：不 return，继续走 RMS/频谱/DSP（DSP 只对混合后音频跑一次）



        } else {



            // 正常路径：读 active ring（crossfade 完成后 active 可能 = B）



            PCMRingBuffer* activeRing = g_activeIsB.load() ? g_ringBufferB : g_ringBuffer;



            if (!activeRing) {



                memset(output, 0, totalSamples * sizeof(float));



                return oboe::DataCallbackResult::Continue;



            }



            // 【V8.292】落点后 4s 比率回落（runEngineRamp）已废除：V8.292 改为落点即退役引擎、



            // 新活跃轨 1:1 直读（见上方完成块）。此处 g_bmRampActive 恒为 false，整段 runEngineRamp



            // 不再执行，下面一律走 1:1 直读分支（rampHandled 恒 false → else 分支直接读 activeRing）。



            bool rampHandled = false;

            // 【V8.469】进过渡/直读分支前先清空整个 output。
            //
            // 真机根因（V8.468，设备 27602，13:24:08）：
            //   XFADE-RAMPOUTLVL got=147/230/17 ratio=1.004（引擎只吐 17~306 帧）
            //   XF-FEEDLOOP 0 条（补喂循环 `more<=0` 立刻 break）
            //   XFADE-RAMPHOLE 0 条（补零也没执行）
            // 而 callback 要 3200 帧 —— **剩下 2900+ 帧从未被写入**。
            //
            // `output[]` 是 Oboe 复用的缓冲，里面还留着**上一个 callback 的音频**。
            // 未写入 = 把上一帧的内容播了出去 → 这就是"切歌后一闪而过的噪音"。
            //
            // 这条路径（1:1 直读 / 过渡混音）同样可能写不满
            //（`toRead < totalSamples` 时尾部帧没人碰），所以在分支之前统一清一次，
            // 两条路径都覆盖。代价：每帧一次 memset（13KB@3200帧立体声，约 590KB/s）。
            memset(output, 0, (size_t)totalSamples * sizeof(float));



            int available = -1;



            // 【V8.475 兜底】引擎连续供不上就立刻放弃 ramp，转 1:1 直读。
            // 判据见 16027 附近注释：引擎每帧只吐几十帧（需求 3200），
            // 剩余靠 ring 原速补，变速/原速样本拼接 = 持续阶跃。
            if (g_bmRampActive.load(std::memory_order_relaxed)
                && g_rampStarve.load(std::memory_order_relaxed) >= 3) {
                LOGI("XFADE-RAMPSTARVE 引擎连续供不上 -> 放弃 ramp，转 1:1 直读 (starve=%d)",
                     (int)g_rampStarve.load(std::memory_order_relaxed));
                g_bmRampActive.store(false, std::memory_order_relaxed);
                g_rampStarve.store(0, std::memory_order_relaxed);
                PCMRingBuffer* ars = g_activeIsB.load(std::memory_order_relaxed) ? g_ringBufferB : g_ringBuffer;
                retireTimeEngineAtLanding("ramp-starve", ars, /*newActiveIsEngSrc=*/true, channels);
            }

            if (g_bmRampActive.load(std::memory_order_relaxed)) {



                struct timespec _trr; clock_gettime(CLOCK_MONOTONIC, &_trr);



                const int64_t nowUs = (int64_t)_trr.tv_sec * 1000000LL + _trr.tv_nsec / 1000LL;



                // 【V8.262】变速比回落：600ms → 4s + smoothstep 缓动（±12% 大变速下 600ms 仍可闻）。



                // 4s 线性即 3%/s 的速率变化；smoothstep 让起止两端斜率为零，无任何拐点感。
// 【V8.475】改为 1.2s，详见上面 16027 附近「B 轨过渡后几秒噪音」的注释。



                const float tRaw = (float)((double)(nowUs - g_bmRampStartUs.load(std::memory_order_relaxed)) / 1200000.0);   // 【V8.475】4s -> 1.2s：ramp 期间引擎供不上，靠 ring 原速补，
                                    // 变速样本与原速样本拼接产生持续阶跃（几秒噪音），越久越糟



                const float t = tRaw >= 1.0f ? 1.0f : tRaw * tRaw * (3.0f - 2.0f * tRaw);



                if (t >= 1.0f) {



                    g_bmRampActive.store(false, std::memory_order_relaxed);



                    // 【V8.301·#3 架构】回落路径同样走统一退役入口：引擎一直在 active 环上，



                    // 落定后新 active 即引擎源 → 仍做 ring 对齐（rewind L）。原先此处只 clear



                    // + 复位两个 flag、且不做延迟补偿，是与落点块分叉的第二份「退役逻辑」。



                    PCMRingBuffer* ar = g_activeIsB.load(std::memory_order_relaxed) ? g_ringBufferB : g_ringBuffer;



                    retireTimeEngineAtLanding("ramp-done", ar, /*newActiveIsEngSrc=*/true, channels);



                } else {



                    const float r = g_bmRampFrom.load(std::memory_order_relaxed)



                                  + (1.0f - g_bmRampFrom.load(std::memory_order_relaxed)) * t;



                    // 【V8.266 修复抖动】RubberBand RT 模式对每回调（10ms）改比例敏感——内部相位



                    // 重排产生可闻抖动。改为 160ms 网格下发 setRatio（4s 回落 = 25 次渐变）。



                    if (nowUs - g_bmRampLastSetUs.load(std::memory_order_relaxed) >= 160000LL) {



                        g_bmRampLastSetUs.store(nowUs, std::memory_order_relaxed);



                        if (g_beatmatchMutex.try_lock()) {



                            applyEngineRatio(g_bmEngineArmed.load(std::memory_order_relaxed), r);



                            g_beatmatchMutex.unlock();



                        }



                    }



                    g_bmPlRcur = r;



                    // 【V8.459 诊断】引擎接管ramp 的那一帧。
                    //
                    // 真机取证（V8.458，设备 9420）：
                    //   11:54:49.205 XFADE-DONE ... underrun=3 pos=1.0019
                    //   11:54:49.205 XFADE-SPEED ramp-down from 0.93366 over 4s
                    //   11:54:49.221 NDK decode loop B ended
                    //   11:54:49.260 XF-CLICK n=0 edge=0.3572 inner=0.0182← 真断流
                    //   11:54:49.477 XF-CLICK n=6 edge=0.4618 inner=0.0466
                    //
                    // edge 是 inner 的 10~20 倍 → **确实有边界不连续**（不是误报）。
                    // RAMPHOLE=0（没补零）、LANDCOMP applied=2048（补偿正常），
                    // 所以既不是欠载也不是补偿失效。
                    //
                    // 剩下的唯一解释：**落点时已完成一次退役 + rewind 补偿，
                    // 紧接着 ramp 又让引擎接管**。也就是同一次落点里
                    // 有两套退役逻辑先后生效（V8.292 退役 / V8.441 ramp），
                    // 第二次接管时读游标已经被 rewind 移过一次 →
                    // 引擎内部状态与ring 读游标错位 → 输出与上一帧不连续。
                    // 【V8.464 修"ramp 接管那一帧的爆音"——真断流的唯一来源】
                    //
                    // 真机取证（V8.463，设备 28079，12:44:47）——
                    // **两条日志在同一个毫秒**：
                    //   12:44:47.505 XFADE-RAMPSYNC engSrc -> B / XFADE-DONE / Crossfade complete
                    //   12:44:47.576 XFADE-RAMPOUT takeover ratio=0.9454← 引擎接管
                    //   12:44:47.576 XF-CLICK n=0 edge=0.2634 inner=0.0551  ← 断流，同一毫秒
                    //   12:44:48.143 XF-CLICK n=16 edge=0.6185 inner=0.0413
                    //
                    // V8.460 之后 `engSrcIsA` 与 `activeIsB` 已一致（RAMPSYNC 生效），
                    // 环也是满的（ringAvail=1040383），但**接管的第一帧仍然爆**。
                    // 原因：引擎的 1024 帧内部环形缓冲里装的是**过渡期**的音频
                    // （它整段都在吃 A 轨），而输出此刻要的是 B 轨的内容。
                    // 读游标对齐了，但引擎**内部状态**没对齐 ——
                    // 第一次 pull 吐出来的是过渡尾部的样本，与前后 1:1 直读的内容错位。
                    //
                    // 修法与实现在下方 runEngineRamp 内部（拿到互斥锁之后），
                    // 因为那里才拿得到 eng 引用。


                    // 【V8.463 诊断修正】原来用 xfadeDiagOn()，但那个窗口是
                    // armXfadeDiag(100, 6000) —— 过渡开始前 100ms + 后 6000ms。
                    // ramp 从落点起持续 **4000ms**，且 armXfadeDiag 在**每次** startCrossfade
                    // 都会重置（armXfadeDiagUntilUs = now + 7000ms），
                    // 落点时刚好用掉 6000ms 里的一小段……
                    // 实测 RAMPOUT=0 而 ramp-done 确实在 4 秒后出现，
                    // 说明这一行**根本没被执行到**，即 ramp 分支未进入。
                    // 为此单独开一个不受窗口限制的限流日志。
                    {
                        static int64_t _lastRampLogUs = 0;
                        struct timespec _trl; clock_gettime(CLOCK_MONOTONIC, &_trl);
                        const int64_t _nowUs = (int64_t)_trl.tv_sec * 1000000LL + _trl.tv_nsec / 1000LL;
                        if (_nowUs - _lastRampLogUs > 300000LL) {   // 300ms 一条
                            _lastRampLogUs = _nowUs;
                        LOGI("XFADE-RAMPOUT takeover ratio=%.6f ringAvail=%d armed=%d engSrcIsA=%d activeIsB=%d",
                             (double)r,
                             activeRing ? activeRing->available() : -1,
                             (int)g_bmEngineArmed.load(std::memory_order_relaxed),
                             (int)g_bmEngSrcActive.load(std::memory_order_relaxed),
                             (int)g_activeIsB.load(std::memory_order_relaxed));
                        }
                    }



                    rampHandled = true;



                    // 新活跃轨：读 activeRing → feed 引擎 → pull 到 output（与过渡分支同构）



                    // 【V8.270 修复回落期抖动】RB RT 模式 available() 恒为 -1（未知）→ 包装层按 0



                    // 处理，旧循环条件 availableFrames()<numFrames 永真 → 每回调空喂 8 轮（上万帧）



                    // → 12s 环不到 1s 被吸干 → 解码回填窗口期输出零填充 → 整个回落期「吸干-回填」



                    // 循环抖动。改为按「实际输出缺口」驱动：pull 不够 → 按 needsInputFrames 补喂 →



                    // 再拉，喂入量恒等于真实消耗，RB 内部队列也不再膨胀。



                    const int chN = channels;



                    auto runEngineRamp = [&](auto& eng) {



                        // 【V8.290 卡进程修复·引擎互斥】与触发线程 reset 互斥（见 runEngineStem 注释）。



                        // 争用（上一轮回落窗内又起新一轮）则退回 1:1 直读（rampHandled=false）。



                        if (!g_beatmatchMutex.try_lock()) {
                        // 【V8.462 诊断】ramp 拿不到互斥 → 退回 1:1 直读。
                        // 但 g_bmRampActive 仍是 true，落点已把引擎源改到新轨，
                        // 于是出现「引擎被喂数据却不产出、输出走 1:1」的空转状态。
                        // 实测（V8.460，12:09:25）：RAMPOUT=0 而 4 秒后才有
                        // ramp-done —— 整个回落期引擎完全没参与输出。
                        if (xfadeDiagOn()) {
                            LOGI("XFADE-RAMPBUSY lock unavailable -> fallback to 1:1, "
                                 "ratio=%.6f armed=%d engSrcIsA=%d activeIsB=%d",
                                 (double)g_bmPlRcur,
                                 (int)g_bmEngineArmed.load(std::memory_order_relaxed),
                                 (int)g_bmEngSrcActive.load(std::memory_order_relaxed),
                                 (int)g_activeIsB.load(std::memory_order_relaxed));
                        }
                        // 【V8.462 关键修复】拿不到锁就**必须清掉 rampActive**。
                        // 原来只置 rampHandled=false 却留着 rampActive=true，
                        // 于是下一帧又进这个分支、又拿不到锁……
                        // 而引擎那边已经被喂了一堆数据却没人消费，
                        // 它的环形缓冲越积越多，最终输出成高频噪声。
                        // 清掉后走 1:1 直读，落点残留速率硬切（V8.262 的"咔一下"），
                        // 那是 200ms 级的一次性瑕疵，比 4 秒持续噪声好得多。
                        g_bmRampActive.store(false, std::memory_order_relaxed);
                        g_bmRampFrom.store(1.0f, std::memory_order_relaxed);
                        rampHandled = false;
                        return;
                    }



                        // 【V8.464 修"ramp 接管那一帧的爆音"——真断流的唯一来源】
                        //
                        // 真机取证（V8.463，设备 28079，12:44:47）——**两条日志同一毫秒**：
                        //   12:44:47.505 RAMPSYNC / XFADE-DONE / Crossfade complete
                        //   12:44:47.576 XFADE-RAMPOUT takeover ratio=0.9454 ← 引擎接管
                        //   12:44:47.576 XF-CLICK n=0 edge=0.2634 inner=0.0551 ← 断流
                        //   12:44:48.143 XF-CLICK n=16 edge=0.6185 inner=0.0413
                        //
                        // V8.460 之后 engSrcIsA 与 activeIsB 已一致（RAMPSYNC 生效），
                        // 环也是满的，但**接管的第一帧仍然爆**。
                        // 原因：引擎的内部缓冲（1024 帧）里装的是**过渡期**的音频
                        //（它整段都在吃 A 轨），而输出此刻要的是 B 轨内容。
                        // 读游标对齐了，但引擎**内部状态**没对齐 ——
                        // 第一次 pull 吐出的是过渡尾部样本，与前后 1:1 直读的内容错位。
                        //
                        // 修法：接管前 reset() 一次把内部缓冲排空。
                        // 代价：reset 后第一次 pull 可能返回 0，
                        // 下面的 `while (got < numFrames)` 补喂循环会自动填上。
                        if (!g_rampPrimed.load(std::memory_order_relaxed)) {
                            g_rampPrimed.store(true, std::memory_order_relaxed);
                            // 只 pull 一次把内部缓冲排空，**不reset()**：
                            // reset(sr, ch) 会重建整个 RubberBand/Wsola 实例
                            // （重新分配、比率重置），开销大且会丢掉已设好的ratio。
                            // 这里连续 pull 直到空，把过渡期残留的样本丢弃。
                            // 固定 512 帧（8 声道 = 4096 float = 16KB，栈上安全）
                            float drain[512 * 8];
                            int drained = 0;
                            for (int i = 0; i < 12 && drained < 512; i++) {
                                const int d = eng.pull(drain, 128);
                                if (d <= 0) break;
                                drained += d;
                            }
                            LOGI("XFADE-RAMPPRIME drained %d frames of crossfade-era buffer", drained);
                        }



                        // 【V8.469 修"切歌后一闪而过的噪音"★ 真根因】
                        //
                        // 真机取证（V8.468，设备 27602，13:24:08）：
                        //   XFADE-RAMPOUTLVL got=147 ratio=1.004108
                        //   XFADE-RAMPOUTLVL got=230 ratio=1.003992
                        //   XFADE-RAMPOUTLVL got= 17 ratio=1.003461
                        //   XF-FEEDLOOP 一条都没有← 补喂循环每次都走 `more<=0` 立刻 break
                        //   XFADE-RAMPHOLE 计数 = 0← 补零也没执行
                        //
                        // **引擎只吐出 17~306 帧，而 callback 要3200 帧。**
                        // 剩下 2900+ 帧从未被写入 —— 而 output[] 是上一次 callback
                        // 用过的缓冲，里面是**上一个 callback 的音频内容**。
                        //
                        // 为什么 RAMPHOLE 计数是 0：那段补零的判据是
                        // `if (got < numFrames)`，而实测 got 远小于 numFrames
                        // 却没打日志 —— 说明这段代码**根本没走到**
                        // （rampHandled 被中途 break 后跳过了）。
                        // 无论哪种情况，「未写入的残留」都是同一个 bug：
                        // **引擎路径没有在入口清空 output**。
                        //
                        // 修法：进引擎路径先把整个 output 清零。
                        // 代价：每帧多一次 memset（约13KB@3200帧立体声），
                        // 在 44.1kHz 下 45 次/秒 = 590KB/s，可忽略。
                        // 收益：无论引擎吐多少、是否补零、是否中途 break，
                        // **都不可能再播出上一个 callback 的残留**。
                        memset(output, 0, (size_t)numFrames * (size_t)channels * sizeof(float));

                        int got = eng.pull(output, numFrames);
                        // 【V8.465 诊断】量 ramp 期间引擎输出的电平与相邻跃变。
                        //
                        // 实测（V8.464，12:51:31 那轮）：排空生效了（drained 512），
                        // 但 ramp 期间 `edge=0.9758` 而 **`inner=0.2285`**。
                        // inner 是 buffer **内部**相邻样本跃变 —— 飙到 0.23 意味着
                        // 引擎吐出来的**本身就是高频噪声**，不是接缝错位。
                        //
                        // 环是满的（ringAvail=1038258），排空也执行了。
                        // 剩下两个怀疑：
                        //  a) RubberBand 在 ratio 突变时内部相位重排（落点那刻从
                        //     混音比切到 ramp 比，ratio 跳变）；
                        //  b) 补喂循环 `eng.feed(g_bmScratch, gotSmp/chN)` 喂的是
                        //     ring 里的原始样本，量级/格式与引擎内部状态不匹配。
                        // 需要看 pull 出来的 RMS 到底是正常量级还是噪声量级。
                        if (got > 1) {
                            static int32_t _rampDbgN = 0;
                            if ((_rampDbgN++ % 8) == 0) {
                                double acc = 0.0, dmax = 0.0;
                                for (int i = channels; i < got * channels; i += channels) {
                                    const float v = output[i];
                                    acc += (double)v * v;
                                    const float d = v - output[i - channels];
                                    const float ad = d < 0.0f ? -d : d;
                                    if (ad > dmax) dmax = ad;
                                }
                                const double rms = __builtin_sqrt(acc / (double)(got - 1));
                                LOGI("XFADE-RAMPOUTLVL rms=%.5f innerMax=%.4f got=%d ratio=%.6f",
                                     rms, (double)dmax, got, (double)g_bmPlRcur);
                            }
                        }



                        int guard = 0;



                        while (got < numFrames && guard++ < 12) {



                            const int ringAvailSmp = activeRing ? activeRing->available() : 0;



                            if (ringAvailSmp <= 0) break;



                            int want = eng.needsInputFrames(numFrames - got);



                            if (want <= 0) want = numFrames - got;



                            if (want > ringAvailSmp / chN) want = ringAvailSmp / chN;



                            if (want > 8192) want = 8192;



                            if (want <= 0) break;



                            const int gotSmp = activeRing->read(g_bmScratch, want * chN);



                            if (gotSmp <= 0) break;



                            eng.feed(g_bmScratch, gotSmp / chN);



                            eng.render();



                            const int more = eng.pull(output + (size_t)got * chN, numFrames - got);



                            if (more <= 0) break;



                            // 【V8.468 诊断】补喂循环的记账。
                            //
                            // 实测（V8.467，设备 14862，13:17:40）：
                            //   XFADE-RAMPOUTLVL got=31 / 5 / 134 / 156
                            // 引擎一次只能吐几帧到几十帧，而 callback 要 3200 帧
                            // → **99% 以上的输出是补喂循环填的**。
                            // 而同一时刻 callback 侧 inner=0.15，引擎侧 inner=0.048
                            // → 差异就出在补喂填的那部分。
                            //
                            // 要查清：`needsInputFrames` 要多少、实际喂了多少、
                            // pull 出来多少。三者不一致就是记账错误。
                            if (xfadeDiagOn()) {
                                static int32_t _feedDbg = 0;
                                if ((_feedDbg++ % 16) == 0) {
                                    LOGI("XF-FEEDLOOP want=%d fed=%d more=%d got=%d need=%d "
                                         "ratio=%.6f ringAvail=%d",
                                         want, gotSmp / chN, more, got,
                                         numFrames - got,
                                         (double)g_bmPlRcur,
                                         activeRing ? activeRing->available() : -1);
                                }
                            }

                            got += more;



                        }



                        if (got < numFrames) {


                            // 【V8.448诊断】引擎供不上时**这里是补零**——
                            // 零填充正是"咔哒 / 没解码出来的噪音"的直接来源。
                            // 落点前后这 4s 是引擎供不上的高发期，必须拿到证据：
                            // 每帧差多少？是持续欠载还是偶发？
                            if (xfadeDiagOn()) {
                                LOGI("XFADE-RAMPHOLE want=%d got=%d short=%d ringAvail=%d ratio=%.6f armed=%d",
                                     numFrames, got, numFrames - got,
                                     activeRing ? activeRing->available() : -1,
                                     (double)g_bmPlRcur,
                                     (int)g_bmEngineArmed.load(std::memory_order_relaxed));
                            }


                            // 【V8.470 修"切歌后一闪而过的噪音"· 补零 → 补真实音频】
                            //
                            // V8.469 已在入口 memset 整个 output（防上一个callback 的残留），
                            // 但**这里的补零本身又造成一次"闪一下"**：
                            // 引擎只吐 17 帧时，后面 3183 帧被填成**静音**，
                            // 音乐在第 17 帧处硬切到 0 —— 这就是听到的"咔/闪"。
                            //
                            // 实测（V8.468，13:24:08）：引擎 got=17~306而需 3200，
                            // 即 **90% 以上的输出是补零**，听感就是"响一下就没了"。
                            //
                            // 修法：补零改为**从 activeRing 补真实音频**。
                            // 引擎只负责变速，尾部直接用原始样本衔接；
                            // ratio≈1.0 时音高几乎无差，听感是自然延续而不是静音。
                            {
                                const int needSmp  = (numFrames - got) * chN;
                                const int ringAvail = activeRing ? activeRing->available() : 0;
                                const int canRead   = (needSmp < ringAvail) ? needSmp : ringAvail;
                                if (canRead > 0 && activeRing) {
                                    const int gotSmp = activeRing->read(
                                            output + (size_t)got * chN, canRead);
                                    const int filled = (gotSmp > 0) ? gotSmp : 0;
                                    const int restSmp = needSmp - filled;
                                    if (restSmp > 0) {
                                        memset(output + (size_t)got * chN + filled, 0,
                                               (size_t)restSmp * sizeof(float));
                                    }
                                    // 【V8.475】引擎吐的量不足需求的 1/8 → 记一次饥饿。
                                    // 连续 3 次就说明这台引擎根本喂不饱 callback，
                                    // 下一帧由 RAMPSTARVE 兜底转 1:1 直读。
                                    if (got * 8 < numFrames) {
                                        g_rampStarve.fetch_add(1, std::memory_order_relaxed);
                                    } else {
                                        g_rampStarve.store(0, std::memory_order_relaxed);
                                    }
                                    LOGI("XFADE-RAMPPAD got=%d need=%d filledFromRing=%d restZero=%d",
                                         got, numFrames - got,
                                         filled / chN, restSmp / chN);
                                } else {
                                    memset(output + (size_t)got * chN, 0,
                                           (size_t)needSmp * sizeof(float));
                                }
                            }



                        }



                        g_beatmatchMutex.unlock();



                    };



                    if (g_bmEngineArmed.load(std::memory_order_relaxed)) runEngineRamp(g_rbStretch);



                    else runEngineRamp(g_stretch);



                }



            }



            if (rampHandled) {



                toRead = totalSamples;   // 引擎已填满 output（不足部分已补零），不做欠载统计



                available = activeRing ? activeRing->available() : 0;



                g_ringBufferFill.store(available, std::memory_order_relaxed);



            } else {



            available = activeRing->available();



            g_ringBufferFill.store(available, std::memory_order_relaxed);  // 【V7.16】



            toRead = std::min(available, totalSamples);



            if (toRead > 0) {



                // 【V8.429 修"播放态残留杂音"】接实际返回值：toRead 是按available() 快照算的
                // 乐观预计值，实际可能更少；丢弃返回值会让下游按偏大的 toRead 处理 output，
                // 读到 Oboe 复用缓冲里上一 callback 的残留 → 沙沙声。
                const int rdOut = activeRing->read(output, toRead);
                toRead = (rdOut > 0) ? rdOut : 0;



            }



            // 【V8.204 诊断】翻转后的观察窗内逐 callback 打印，看输出到底有没有断。



            {



                struct timespec _t2; clock_gettime(CLOCK_MONOTONIC, &_t2);



                int64_t _u2 = (int64_t)_t2.tv_sec * 1000000LL + (int64_t)_t2.tv_nsec / 1000LL;



                if (xfadeDiagOn() || _u2 < g_xfadePostUntilUs.load(std::memory_order_relaxed)) {



                    // 【V8.448】原用 LOGD_GATED —— 它在 SDW_VERBOSE_DIAG 关闭时被
                    // **编译期裁掉**，也就是说这条"落点后逐帧取证"从来没真正输出过。
                    // 我前几轮一直以为它在日志里，实际它根本不存在。
                    // 改用 LOGI + 过渡窗口门：只在切歌窗口内逐帧打印。
                    LOGI("XFADE-POST activeB=%d avail=%d toRead=%d need=%d fill=%d underrun=%d",



                         (int)g_activeIsB.load(), available, toRead, totalSamples,



                         g_ringBufferFill.load(std::memory_order_relaxed),



                         (int)g_underrunCount.load(std::memory_order_relaxed));



                }



            }



            if (toRead < totalSamples) {



                // 【V8.454 诊断】欠载发生时的现场。
                //
                // 真机取证显示 underrun 有两个量级：3~4（稳态）vs 32（异常轮）。
                // 异常轮同时出现 ringA_avail=782738（正常是 1034k+）——
                // 说明**解码器供不上，环被抽干**。这才是噪音的根因，
                // 补零/方波只是症状。
                //
                // 需要区分的两种情况：
                //   a) 解码器根本没在跑（EOS/stop）→ 水位只降不升
                //   b) 解码器在跑但产出速度跟不上消费→ 水位缓慢下降
                // 两者修法完全不同，必须用日志分开。
                {
                    static int32_t _lastUs = -1;
                    struct timespec _tu; clock_gettime(CLOCK_MONOTONIC, &_tu);
                    const int32_t _nowUs = (int32_t)((_tu.tv_sec - 1100000000LL) & 0x7fffffff);
                    // 限流：同一秒内最多打一条，避免刷爆 logcat
                    if (_lastUs < 0 || (_nowUs - _lastUs) > 1000) {
                        _lastUs = _nowUs;
                        PCMRingBuffer* _ca = g_activeIsB.load(std::memory_order_relaxed) ? g_ringBufferB : g_ringBuffer;
                        PCMRingBuffer* _ce = g_bmEngSrcActive.load(std::memory_order_relaxed) ? g_ringBuffer : g_ringBufferB;
                        LOGI("XFADE-UNDERRUN n=%d activeAvail=%d engRingAvail=%d otherAvail=%d "
                             "xf=%d armed=%d ramp=%d stem=%d",
                             (int)g_underrunCount.load(std::memory_order_relaxed),
                             _ca ? _ca->available() : -1,
                             _ce ? _ce->available() : -1,
                             (g_activeIsB.load(std::memory_order_relaxed) ? g_ringBuffer : g_ringBufferB)
                                 ? (g_activeIsB.load(std::memory_order_relaxed) ? g_ringBuffer : g_ringBufferB)->available() : -1,
                             (int)g_crossfadeActive.load(std::memory_order_relaxed),
                             (int)g_bmEngineArmed.load(std::memory_order_relaxed),
                             (int)g_bmRampActive.load(std::memory_order_relaxed),
                             (int)g_stemMode.load(std::memory_order_relaxed));
                    }
                }

                g_underrunCount.fetch_add(1, std::memory_order_relaxed);  // 【V7.39】



                // 【V8.192】欠载统计：连续断音的根因判定全靠它



                g_diagUnder.fetch_add(1, std::memory_order_relaxed);



                const int shortfall = totalSamples - toRead;



                int w = g_diagWorst.load(std::memory_order_relaxed);



                while (shortfall > w && !g_diagWorst.compare_exchange_weak(w, shortfall)) {}



                // 【V8.315 欠载改为「保持末样本」而非补零】



                // 旧行为：memset 补零 → 输出电平从当前值**瞬间掉到 0**，是一个阶跃。



                //听感就是「过渡后停顿一下」—— 注意不是听不到，是听到一段**突变静音**，



                // 所以比"缺料"更刺耳、更容易被当成 bug。



                // 新行为：保持 toRead 处的末样本值（电平连续，无阶跃），并做极短淡出



                // （约 3ms）避免直流台阶。一两帧的欠载几乎听不出来，且不会掩盖诊断计数



                // （g_underrunCount / g_diagUnder / g_diagWorst 照旧累加）。



                if (toRead > 0 && toRead < totalSamples) {



                    // 【V8.454 修"没解码出来的噪音"· 持续欠载段】



                    //



                    // 真机取证（V8.450，设备 ZY22KG2WMQ）：



                    //   XFADE-DONE ... underrun=32（其他轮次只有 3~4 → 本轮新增 28 次）



                    //   XF-CLICK n=1 jump=0.4221 peak=1.0000



                    //   XF-CLICK n=2 jump=0.9554 peak=1.0000



                    // 欠载暴增的那三轮噪音最响。且 ZEROFILL=0 / RAMPHOLE=0，



                    // 说明走的是本分支（V8.315 的"保持末样本"）。



                    //



                    // 原实现的问题：取**一帧**的样本值（lastL/lastR），把它重复几十帧



                    // 再淡出 —— 一个采样点的电平被拉成**方波**，听感是低频"嘟嘟"声，


                                       // 正是用户说的"像没解码出来的噪音"。



                    // 单次欠载（一两帧）听不出来；持续欠载（几十帧）非常明显。



                    //



                    // 修法：按**帧号回放已有的有效内容**（循环播放 toRead 之前的音频），



                    // 再叠加淡出。补出来的仍是音乐而不是方波。



                    const int chN2 = channels > 0 ? channels : 1;



                    const int need = totalSamples - toRead;      // 缺的采样点数



                    // 回放窗口：toRead 之前最多 2048 帧（46ms @44.1k）。



                    // 足够长到听不出重复，又不至于相位差变得可闻。



                    const int lookbackFrames = (toRead / chN2) < 2048 ? (toRead / chN2) : 2048;



                    const int srcStart = toRead - lookbackFrames * chN2;



                    const int srcLen = lookbackFrames * chN2;



                    // 淡出窗：~12ms（@44.1k 约 530 帧），比原来 3ms 长。



                    // 3ms 只够磨直流台阶；欠载持续几十毫秒时，3ms 之后就是一段



                    // 突变静音，听感同样是"断了一下"。



                    const int fadeFrames = (int)((float)g_sampleRate.load() * 0.012f);



                    const int fadeSmp = (fadeFrames * chN2 < need) ? fadeFrames * chN2 : need;



                    if (srcLen > 0) {



                        for (int i = 0; i < need; i += chN2) {



                            const int off = (i % srcLen);



                            const float sL = output[srcStart + off];



                            const float sR = (chN2 >= 2 && off + 1 < srcLen) ? output[srcStart + off + 1] : sL;



                            const float w2 = (i < fadeSmp) ? (1.0f - (float)i / (float)fadeSmp) : 0.0f;



                            output[toRead + i] = sL * w2;



                            if (chN2 >= 2 && toRead + i + 1 < totalSamples) output[toRead + i + 1] = sR * w2;



                        }



                    } else {



                        memset(output + toRead, 0, (size_t)need * sizeof(float));



                    }



                } else {


                    // 【V8.448诊断】toRead == 0（环完全空）走**纯补零**——
                    // 与上面"保持末样本 + 3ms 淡出"是两种完全不同的听感：
                    // 这里是硬静音阶跃，最刺耳。逐帧取证它到底发不发生。
                    if (xfadeDiagOn()) {
                        PCMRingBuffer* _z = g_activeIsB.load(std::memory_order_relaxed) ? g_ringBufferB : g_ringBuffer;
                        LOGI("XFADE-ZEROFILL need=%d ringAvail=%d activeB=%d armed=%d ramp=%d",
                             totalSamples - toRead, _z ? _z->available() : -1,
                             (int)g_activeIsB.load(std::memory_order_relaxed),
                             (int)g_bmEngineArmed.load(std::memory_order_relaxed),
                             (int)g_bmRampActive.load(std::memory_order_relaxed));
                    }

                    memset(output + toRead, 0, (totalSamples - toRead) * sizeof(float));



                }



            }



            }   // else (rampHandled)：回落期间走引擎路径，不做 1:1 直读



            // 【V8.205 点击检测】跨 callback 比对同一位置的样本跃变 + 本 callback 峰值。



            // 取 output 的最后一个完整帧的 L/R 与上一 callback 同位置相减：翻转处的电平阶跃、



            // 断流补零、权重突变都会在这里现形；peak 用来判断 jump 是「相对大」还是「本来就小」。



            // 只在 crossfade 全程 + 结束后的窗口内跑（平时整段跳过，零开销）。



            {



                struct timespec _t3; clock_gettime(CLOCK_MONOTONIC, &_t3);



                int64_t _u3 = (int64_t)_t3.tv_sec * 1000000LL + (int64_t)_t3.tv_nsec / 1000LL;



                if (_u3 < g_xfadeClickUntilUs.load(std::memory_order_relaxed)) {



                    float pk = 0.0f;



                    for (int i = 0; i < totalSamples; i++) {



                        const float a = output[i] < 0.0f ? -output[i] : output[i];



                        if (a > pk) pk = a;



                    }



                    // 【V8.455 修"点击检测器本身测错了东西"】
                    //
                    // 原实现：jump = |output[本callback 末样本] - g_xfadeLast[上一 callback 末样本]|
                    //
                    // 错误在于：相邻 callback 的**末样本**之间隔着整整一个 buffer 的时间
                    // （@44.1kHz、3200 帧 ≈ 72ms）。也就是说它在比较音乐里相隔 72ms
                    // 的两个采样点 —— 测到的"跃变"完全是**正常的波形差异**，
                    // 与是否断流/翻转毫无关系。
                    //
                    // 后果：它输出 jump=0.9~1.0 peak=0.98 时看起来像"满幅级内容跳变"，
                    // 我据此判定"落点异源错位"并改了 core 路径（回退引擎源环）。
                    // 那个结论建立在错误测量上，不成立。
                    //
                    // 正确做法：跨 callback 真正相邻的样本是
                    //   上一 callback 的**最后**一个样本 ↔ 本 callback 的**第一个**样本。
                    // 同时算一个对照组：buffer 内部相邻样本的最大跃变。
                    // 音乐在 44.1kHz 下相邻样本差 0.3~0.6 是常态（尤其高频），
                    // 边界跃变明显超过内部跃变，才说明真的有断流/阶跃。
                    // 【V8.473 落点接缝平滑】见 g_seamLeft 声明处的注释。
                    // 在点击检测**之前**做，这样检测器量到的就是抹平后的真实结果。
                    {
                        int sl = g_seamLeft.load(std::memory_order_relaxed);
                        if (sl > 0) {
                            const int nf = (sl < numFrames) ? sl : numFrames;
                            const int done = SEAM_FRAMES - sl;   // 已完成帧数
                            for (int f = 0; f < nf; f++) {
                                // t: 0 → 1，越靠后越接近真实内容
                                const float t = (float)(done + f + 1) / (float)SEAM_FRAMES;
                                for (int c = 0; c < channels; c++) {
                                    const size_t ix = (size_t)f * (size_t)channels + (size_t)c;
                                    const float from = (c < 2) ? g_xfadeLast[c] : g_xfadeLast[0];
                                    output[ix] = from + (output[ix] - from) * t;
                                }
                            }
                            g_seamLeft.store(sl - nf, std::memory_order_relaxed);
                        }
                    }

                    float jump = 0.0f;
                    float jumpIn = 0.0f;   // buffer 内部相邻样本最大跃变（对照）



                    if (totalSamples >= channels) {



                        int cN = channels > 2 ? 2 : channels;



                        for (int c = 0; c < cN; c++) {



                            // 真正的边界不连续
                            const float dEdge = output[c] - g_xfadeLast[c];



                            const float adEdge = dEdge < 0.0f ? -dEdge : dEdge;



                            if (adEdge > jump) jump = adEdge;



                            for (int i = channels; i < totalSamples; i += channels) {



                                const float dd = output[i] - output[i - channels];



                                const float ad = dd < 0.0f ? -dd : dd;



                                if (ad > jumpIn) jumpIn = ad;



                            }



                            g_xfadeLast[c] = output[totalSamples - channels + c];



                        }



                    }



                    // 【V8.467诊断】落点那几帧的输出来源切换。
                    //
                    // 真机取证（V8.465，设备 11504，13:01:58）—— **"一闪而过"的特征**：
                    //   13:01:58.440 落点（RETIRE-STATE armed=1 / Crossfade complete: A）
                    //   13:01:58.492 XF-CLICK n=0 edge=0.3135 inner=0.1135 peak=0.5095 frames=986
                    //   13:01:58.503 XF-CLICK n=1 edge=0.0363← 立刻恢复
                    //   之后 7 帧 edge 全部 < 0.04
                    //
                    // **单帧脉冲，52ms 后即消失** —— 与之前几轮的"持续噪声"是不同问题。
                    // 落点那一帧 `armed` 仍是 1（引擎刚退役），输出源从引擎切到 1:1 直读。
                    // 怀疑：切换那一帧两条路径都写了 output（引擎 pull 的残留 + 直读），
                    // 或者 g_xfadeLast 记的是**引擎路径**的末样本，
                    // 与本帧 1:1 直读的内容相比就成了假edge。
                    //
                    // 要分清「真有噪声」还是「检测器参考点错位」：
                    // 打出落点窗口内每帧的实际首样本值 + 来源状态。
                    if (xfadeDiagOn() && g_bmRampActive.load(std::memory_order_relaxed) == false) {
                        LOGI("XF-SRCFRAME armed=%d engSrcIsA=%d activeIsB=%d first=%.5f,%.5f last=%.5f,%.5f peak=%.4f",
                             (int)g_bmEngineArmed.load(std::memory_order_relaxed),
                             (int)g_bmEngSrcActive.load(std::memory_order_relaxed),
                             (int)g_activeIsB.load(std::memory_order_relaxed),
                             (double)output[0],
                             (channels > 1 ? (double)output[1] : 0.0),
                             (double)output[totalSamples - channels],
                             (channels > 1 ? (double)output[totalSamples - 1] : 0.0),
                             (double)pk);
                    }

                    const int n = g_xfadeClickN.fetch_add(1, std::memory_order_relaxed);



                    // 【V8.448诊断】原用 LOGD_GATED —— 被 SDW_VERBOSE_DIAG 编译期裁掉，
                    // 这条"跨callback 样本跃变"取证**从来没输出过**。
                    // 它恰恰是「自动切歌闪一下」最直接的证据，必须真跑起来。
                    LOGI("XF-CLICK n=%d edge=%.4f inner=%.4f peak=%.4f frames=%d xf=%d toRead=%d",



                         n, (double)jump, (double)jumpIn, (double)pk, (int)numFrames,



                         (int)g_crossfadeActive.load(std::memory_order_relaxed), toRead);



                }

            // 【修复"B 轨进场哒一下】g_xfadeLast 必须在每个 callback 都更新，不能只在
            // 点击检测窗口内更新。原来它唯一的赋值点在窗口块内部
            // (if (_u3 < g_xfadeClickUntilUs))，而正常播放时窗口是关闭的 ——
            // 于是交叉淡化起点的接缝平滑（V8.473）拿到的是上一个过渡的残留末样本
            // （首次更是初始值 0），把 A 轨满音量音频插值成"从 0 爬升"的 23ms 斜坡。
            //
            // 真机取证（XT2401_2 / 22:52:51，过渡 dur=11034ms）：
            //   首个回调 fL=0.000000（双声道精确为 0）
            //   aRms 0.00000 → 0.00171 → 0.02754 → 0.13870 → 0.28279（约 23ms 才恢复）
            //   B 进场区跨回调阶跃均值 0.1005 = 过渡中段 0.0218 的 4.6 倍
            //   A 退场区仅 0.0167（落点侧本来就是干净的）
            // 即：用户听到的两声"哒"里，"A 退场"那声不在落点样本级，
            // 而"B 进场"这声就是这 23ms 斜坡。
            if (totalSamples >= channels && channels > 0) {
                const int _cN = (channels > 2) ? 2 : channels;
                for (int c = 0; c < _cN; c++) {
                    g_xfadeLast[c] = output[(size_t)(totalSamples - channels) + (size_t)c];
                }
            }



            }



            // 【V8.192】每 240 个 callback（192fr/48k ≈ 5s）打一次汇总，定位「声音断/卡顿」



            {



                struct timespec _t1; clock_gettime(CLOCK_MONOTONIC, &_t1);



                long long _us = (long long)(_t1.tv_sec - _cbT0.tv_sec) * 1000000LL +



                                ((long long)_t1.tv_nsec - (long long)_cbT0.tv_nsec) / 1000LL;



                if (_us < 0) _us = 0;



                uint64_t _worst = g_diagWorstCbUs.load(std::memory_order_relaxed);



                while ((uint64_t)_us > _worst && !g_diagWorstCbUs.compare_exchange_weak(_worst, (uint64_t)_us)) {}



                const uint64_t _n = g_diagCbN.fetch_add(1, std::memory_order_relaxed) + 1;



                if (_n % 240u == 0u) {   // 默认每 240 次回调(~23s)打印一次诊断；路由切换测量期间曾临时改为 20



                    const uint64_t _u0 = g_diagUnder.exchange(0, std::memory_order_relaxed);



                    const int _w0 = g_diagWorst.exchange(0, std::memory_order_relaxed);



                    const uint64_t _c0 = g_diagWorstCbUs.exchange(0, std::memory_order_relaxed);



                    LOGD_GATED("SDW_AUDG cb=%llu frames=%d underrun=%llu worstShort=%d worstCbUs=%llu avail=%d fill=%d",



                         (unsigned long long)_n, numFrames,



                         (unsigned long long)_u0, _w0,



                         (unsigned long long)_c0,



                         available, g_ringBufferFill.load(std::memory_order_relaxed));



                }



            }



        }







        // 【V8.179】seek 淡入：ring 清空 + MediaCodec flush + EQ 状态复位都会让输出出现



        // 阶跃（听感「啪」）。在此把本 callback 开头这段逐采样乘 0→1 斜坡，把阶跃摊平；



        // 斜坡未用完的部分留给下一 callback 接着淡入（跨 callback 连续）。



        {



            // 【V8.180 复核】上一版先把 fadeTotal 钳到 totalSamples 再算剩余，



            // 于是剩余恒 <= 0、每 callback 都把余量清零 → 跨 callback 续淡入从未生效。



            // 这里按「剩余量」递减，未用完的余量留到下一 callback 接着淡入。



            int fadeLeft = g_seekFadeSamples.load(std::memory_order_relaxed);



            if (fadeLeft > 0) {



                const int fadeTotal = fadeLeft < totalSamples ? fadeLeft : totalSamples;



                const float inv = 1.0f / (float)fadeTotal;



                for (int i = 0; i < fadeTotal; i++) output[i] *= ((float)i + 1.0f) * inv;



                fadeLeft -= fadeTotal;



                g_seekFadeSamples.store(fadeLeft > 0 ? fadeLeft : 0, std::memory_order_relaxed);



            }



        }







        // ====================================================================



        // 【V8.170 响度统一管理】归一化增益挂在 DSP 链最前：ring/crossfade 混合结果刚出、



        // 还没进任何 EQ / 限幅，这样后面所有模块看到的都是已归一化的电平，



        // 多段限幅也不会被一枚大增益反复触发。



        // ====================================================================



        {



            // 【V8.24x】目标增益按整枚下发（nativeSetNormalizationGain 是瞬态 store）。



            // 切歌时新歌的归一化增益会与上一首不同，若直接当阶跃乘上去，回调边界处就是一次



            // 电平跳变 = "过渡完后啪一下"。改为逐样本平滑逼近，~5ms 内收敛，人耳听不出阶跃。



            const float ngTarget = g_normGain.load(std::memory_order_relaxed);



            float cur = g_curNormGain;



            const float NG_SMOOTH = 0.02f;   // 约 4ms 时间常数（48k），足够消除换增益爆音



            float prePeak = 0.0f, postPeak = 0.0f;



            for (int i = 0; i < totalSamples; i++) {



                cur += (ngTarget - cur) * NG_SMOOTH;



                const float v = output[i];



                const float a = v < 0.0f ? -v : v;



                if (a > prePeak) prePeak = a;



                const float o = v * cur;



                output[i] = o;



                const float b = o < 0.0f ? -o : o;



                if (b > postPeak) postPeak = b;



            }



            g_curNormGain = cur;



        }







        // ====================================================================



        // 【V8.150 Live FX 总线】切歌效果改为「混音后总线处理」



        // 旧实现 outSample = fA*gFrom*modGain + ...，gFrom=(1-pos)^2 使效果素材在



        // pos0.5/0.75 已 -12/-24dB → 听感退化为普通淡出（故效果改挂 mix 后总线）。



        // 现在：湿信号作用于满电平 master，可随时开关；类型/深度即时生效。



        // ====================================================================



        // 关键修复(V8.151)：旧版总线「常开」且空闲时 lpos 恒为 1.0 →



        // filter/eq 变成永久极端处理、echo/lunar 全程拖尾 = 听感「卡顿 + 有延迟」。



        // 现在用包络 g_lfxWet 只在「交叉淡化窗口 + 短暂释放」内工作，其余时间整段旁路。



        if (g_liveFxEnabled.load(std::memory_order_relaxed) && toRead >= 1) {



            const int lfx = g_xfadeEffect.load(std::memory_order_relaxed);



            const float lfxDepth = g_liveFxDepth.load(std::memory_order_relaxed);



            const bool lfxRun = (lfx >= 2 && lfx <= 6 && lfxDepth > 0.001f);  // 【V8.153】filter 走轨级，不上总线



            // 包络：淡化中→1；结束后→0（快起慢落，避免残留）



            // 【V8.153 修正2】效果窗口 = 整个过渡重叠期。



            // 用户要求：A->B 时「最后几秒进效果 -> A/B 重叠期间一直有效果 -> A 退出 B 进入后效果退出」。



            // 即：交叉淡化进行中 wet=1（全程有效果）；过渡一完成（g_crossfadeActive=false）



            // 立刻快速释放（~30ms，无咔哒）并整段旁路 -> 成功过渡后所有效果退出，B 原声正常播放。



            float wetTarget = (lfxRun && g_crossfadeActive.load(std::memory_order_relaxed)) ? 1.0f : 0.0f;



            // 【V8.182】包络改为按时间常数（旧系数 0.0025/0.0050 是「每 callback 帧」的增量，



            // 帧率一变就漂；且释放 0.005/帧 ≈ 3.3s 时间常数，降到旁路阈值要 ~24s →



            // 过渡早结束，效果状态却还挂着空转，下一次触发带出上一段的延迟线残留）。



            // 现在 attack 10ms / release 80ms，与帧率解耦。



            if (wetTarget > g_lfxWet) {



                g_lfxWet += (1.0f - g_lfxWet) * 0.16f;      // ≈10ms 建起



            } else if (g_lfxWet > 0.0f) {



                g_lfxWet -= g_lfxWet * 0.20f;                // ≈80ms 退出，无咔哒



                if (g_lfxWet < 0.0015f) g_lfxWet = 0.0f;



            }



            bool lfxActive = lfxRun && g_lfxWet > 0.0008f;



            // 空闲时清一次状态，防止下次触发带出陈旧延迟/滤波残留



            if (!lfxActive) {



                if (g_lfxWasActive) {



                    memset(g_lfxLp, 0, sizeof(g_lfxLp));



                    memset(g_lfxLow, 0, sizeof(g_lfxLow));



                    memset(g_xfadeDelayL, 0, sizeof(g_xfadeDelayL));



                    memset(g_xfadeDelayR, 0, sizeof(g_xfadeDelayR));



                    g_xfadeDelayWr = 0;



                    g_xfadeModPhase = 0.0;



                    g_lfxWasActive = false;



                }



            } else {



                g_lfxWasActive = true;



                int bsr = g_sampleRate.load(); if (bsr <= 0) bsr = 44100;



                const float BTP = 6.2831853f;



                float lpos = g_crossfadeActive.load() ? g_crossfadePos.load() : 1.0f;



                if (lpos < 0.0f) lpos = 0.0f;



                if (lpos > 1.0f) lpos = 1.0f;



                int bl = g_xfadeDelayLen.load(std::memory_order_relaxed);



                if (bl < 1) bl = 1;



                if (bl >= XFADE_DELAY_MAX) bl = XFADE_DELAY_MAX - 1;



                int chn = stream->getChannelCount();



                if (chn < 1) chn = 1;



                const float wetAmt = lfxDepth * g_lfxWet;



                for (int i = 0; i + chn - 1 < toRead; i += chn) {



                    float L = output[i];



                    float R = (chn >= 2) ? output[i + 1] : L;



                    float wl = L, wr = R;



                    if (lfx == 1) {



                        // Filter：master 低通 FS→300Hz（切歌收尾闷掉再交给 B）



                        // 【V8.182】起点与轨级 filter 用同一自适应值，两路扫频一致



                        float fsStart = g_xfadeFiltStartHz.load(std::memory_order_relaxed);



                        float fc = fsStart + (300.0f - fsStart) * lpos;



                        float k = 1.0f - expf(-BTP * fc / (float)bsr);



                        g_lfxLp[0] += k * (L - g_lfxLp[0]);



                        g_lfxLp[1] += k * (R - g_lfxLp[1]);



                        wl = g_lfxLp[0]; wr = g_lfxLp[1];



                    } else if (lfx == 2) {



                        // Eq：master 低频先挖后还原（bass swap）



                        float kl = 1.0f - expf(-BTP * 120.0f / (float)bsr);



                        g_lfxLow[0] += kl * (L - g_lfxLow[0]);



                        g_lfxLow[1] += kl * (R - g_lfxLow[1]);



                        float lowG = 1.0f - lpos;



                        wl = L - g_lfxLow[0] * (1.0f - lowG);



                        wr = R - g_lfxLow[1] * (1.0f - lowG);



                    } else if (lfx == 3 || lfx == 6) {



                        // Echo(3) / Lunar Echo(6)：master 延迟，拍长同步



                        int rd = g_xfadeDelayWr - bl;



                        if (rd < 0) rd += XFADE_DELAY_MAX;



                        float dL = g_xfadeDelayL[rd], dR = g_xfadeDelayR[rd];



                        float fb = (lfx == 6) ? 0.62f : 0.45f;



                        g_xfadeDelayL[g_xfadeDelayWr] = L + dL * fb;



                        g_xfadeDelayR[g_xfadeDelayWr] = R + dR * fb;



                        // 【V8.182 回声峰值归一】反馈环 x→L+x·fb 稳态 x≈L/(1-fb)=2.63L，



                        // 再叠 0.75 抽头后湿信号峰值约 2.2~2.5×L；depth=1 时输出直接等于湿信号



                        // → 过渡中点整条链过载「变吵发紧」。这里给湿信号一个归一输出增益，



                        // 让 depth=1 的峰值回到 ~1.0×L 量级（响度不变，只是不再叠加式变响）。



                        if (lfx == 6) {



                            int dl2 = bl * 2;



                            int rd2 = g_xfadeDelayWr - dl2;



                            if (rd2 < 0) rd2 += XFADE_DELAY_MAX;



                            wl = (L + dL * 0.75f + g_xfadeDelayL[rd2] * 0.40f) * 0.38f;



                            wr = (R + dR * 0.75f + g_xfadeDelayR[rd2] * 0.40f) * 0.38f;



                        } else {



                            wl = (L + dL * 0.55f) * 0.46f;



                            wr = (R + dR * 0.55f) * 0.46f;



                        }



                        g_xfadeDelayWr++;



                        if (g_xfadeDelayWr >= XFADE_DELAY_MAX) g_xfadeDelayWr = 0;



                    } else if (lfx == 5) {



                        // 【V8.182】Tremolo：按 BPM 同步的八分音符幅度调制（原写死 9Hz 与拍不符）



                        float mg = 1.0f - 0.70f * (0.5f + 0.5f * sinf((float)g_xfadeModPhase));



                        g_xfadeModPhase += (double)(BTP * g_xfadeTremHz.load(std::memory_order_relaxed) / (float)bsr);



                        if (g_xfadeModPhase > (double)BTP) g_xfadeModPhase -= (double)BTP;



                        wl = L * mg; wr = R * mg;



                    }



                    output[i] = L + (wl - L) * wetAmt;



                    if (chn >= 2) output[i + 1] = R + (wr - R) * wetAmt;



                }



            }



        }







        // 【V7.xx】Compute RMS amplitude for beat-reactive visuals (pre-DSP raw audio)



        if (toRead > 0) {



            float sumSq = 0.0f;



            int readSamples = std::min(toRead, 4096);  // cap at ~50ms to avoid spikes from huge buffers



            for (int i = 0; i < readSamples; i++) sumSq += output[i] * output[i];



            float rmsNow = std::sqrt(sumSq / readSamples);



            g_rmsLevel.store(rmsNow, std::memory_order_relaxed);



            // 【V8.183】crossfade 期间两条慢速 RMS 由实时线程在逐采样循环里直接写入



            // （见上方可「A/B 电平分离」处），那边拿到的才是各轨自己的电平；这里的 rmsNow



            // 是 A*cos+B*sin 的【混合输出】，两轨都吃它会让过渡可视化画出同一条曲线



            // （B 轨电平条会随 A 的淡出一路「涨」），预增益的分子分母也都不是单轨电平。



            // 非 crossfade 时没有逐采样循环，就在这里按当前 active 轨同步一条、另一条淡出。



            if (!g_crossfadeActive.load(std::memory_order_relaxed)) {



                float pa = g_xfadeARmsSlow.load(std::memory_order_relaxed);



                float pb = g_xfadeBRmsSlow.load(std::memory_order_relaxed);



                if (g_activeIsB.load(std::memory_order_relaxed)) {



                    pb = pb * 0.98f + rmsNow * 0.02f;  pa *= 0.98f;   // B 在播



                } else {



                    pa = pa * 0.98f + rmsNow * 0.02f;  pb *= 0.98f;   // A 在播



                }



                g_xfadeARmsSlow.store(pa, std::memory_order_relaxed);



                g_xfadeBRmsSlow.store(pb, std::memory_order_relaxed);



            }







            // 【V7.85→8-band】8-band spectrum via cascaded LP-diff (CDJ/DJM style)



            // band[0-7] = LP-diff cascade; all bands sum to full signal



            int sr = g_sampleRate.load();



            if (!g_vuFiltersInited.load()) {



                g_lp60L.setLowPass(sr, 60.0f, 0.707f);     g_lp60R.setLowPass(sr, 60.0f, 0.707f);



                g_lp120L.setLowPass(sr, 120.0f, 0.707f);    g_lp120R.setLowPass(sr, 120.0f, 0.707f);



                g_lp250L.setLowPass(sr, 250.0f, 0.707f);    g_lp250R.setLowPass(sr, 250.0f, 0.707f);



                g_lp500L.setLowPass(sr, 500.0f, 0.707f);    g_lp500R.setLowPass(sr, 500.0f, 0.707f);



                g_lp2000L.setLowPass(sr, 2000.0f, 0.707f);  g_lp2000R.setLowPass(sr, 2000.0f, 0.707f);



                g_lp6000L.setLowPass(sr, 6000.0f, 0.707f);  g_lp6000R.setLowPass(sr, 6000.0f, 0.707f);



                g_lp12000L.setLowPass(sr, 12000.0f, 0.707f); g_lp12000R.setLowPass(sr, 12000.0f, 0.707f);



                g_vuLp60L.setLowPass(sr, 60.0f, 0.707f);     g_vuLp60R.setLowPass(sr, 60.0f, 0.707f);



                g_vuLp120L.setLowPass(sr, 120.0f, 0.707f);   g_vuLp120R.setLowPass(sr, 120.0f, 0.707f);



                g_vuLp250L.setLowPass(sr, 250.0f, 0.707f);   g_vuLp250R.setLowPass(sr, 250.0f, 0.707f);



                g_vuLp500L.setLowPass(sr, 500.0f, 0.707f);   g_vuLp500R.setLowPass(sr, 500.0f, 0.707f);



                g_vuLp2000L.setLowPass(sr, 2000.0f, 0.707f); g_vuLp2000R.setLowPass(sr, 2000.0f, 0.707f);



                g_vuLp6000L.setLowPass(sr, 6000.0f, 0.707f); g_vuLp6000R.setLowPass(sr, 6000.0f, 0.707f);



                g_vuLp12000L.setLowPass(sr, 12000.0f, 0.707f); g_vuLp12000R.setLowPass(sr, 12000.0f, 0.707f);



                g_vuFiltersInited.store(true);



            }



            float subE = 0, b1E = 0, b2E = 0, b3E = 0, b4E = 0, b5E = 0, b6E = 0, b7E = 0;



            // 【V8.208】原始 L/R 平方和与瞬时峰值（真值电平，诊断页用）



            float sqL = 0.0f, sqR = 0.0f, pkLnow = 0.0f, pkRnow = 0.0f;



            int ch = stream->getChannelCount();



            // 【V8.174】同一条级联再跑一遍，这次按声道分开累计能量，供 Mixer VU 表



            // 画 L/R 两列。lambda 里 7 个 LP 共用一个 filter 实例、顺序推进，



            // 与上面混音版走的是两个实例，滤波器状态略有差异但频带边界一致。



            float eL[8] = {0}, eR[8] = {0};



            // 【V8.190】L/R 各走自己的 VU 专用级联，与 mono 版完全隔离。



            // 旧版一个 bandSquare 复用 mono 的 g_lpXXL 实例，且 R 声道也走 L 后缀实例。



            auto bandSquareL = [&](float in, float* e) {



                float a0 = g_vuLp60L.process(in),    a1 = g_vuLp120L.process(in);



                float a2 = g_vuLp250L.process(in),   a3 = g_vuLp500L.process(in);



                float a4 = g_vuLp2000L.process(in),  a5 = g_vuLp6000L.process(in);



                float a6 = g_vuLp12000L.process(in);



                float v[8] = {a0, a1-a0, a2-a1, a3-a2, a4-a3, a5-a4, a6-a5, in-a6};



                for (int k = 0; k < 8; k++) e[k] += v[k]*v[k];



            };



            auto bandSquareR = [&](float in, float* e) {



                float a0 = g_vuLp60R.process(in),    a1 = g_vuLp120R.process(in);



                float a2 = g_vuLp250R.process(in),   a3 = g_vuLp500R.process(in);



                float a4 = g_vuLp2000R.process(in),  a5 = g_vuLp6000R.process(in);



                float a6 = g_vuLp12000R.process(in);



                float v[8] = {a0, a1-a0, a2-a1, a3-a2, a4-a3, a5-a4, a6-a5, in-a6};



                for (int k = 0; k < 8; k++) e[k] += v[k]*v[k];



            };



            for (int i = 0; i < readSamples; i += ch) {



                float sl = output[i], sr2 = (ch >= 2 && i + 1 < readSamples) ? output[i + 1] : sl;



                float mono = (sl + sr2) * 0.5f;



                float l60   = (g_lp60L.process(sl)   + g_lp60R.process(sr2))   * 0.5f;



                float l120  = (g_lp120L.process(sl)  + g_lp120R.process(sr2))  * 0.5f;



                float l250  = (g_lp250L.process(sl)  + g_lp250R.process(sr2))  * 0.5f;



                float l500  = (g_lp500L.process(sl)  + g_lp500R.process(sr2))  * 0.5f;



                float l2000 = (g_lp2000L.process(sl) + g_lp2000R.process(sr2)) * 0.5f;



                float l6000 = (g_lp6000L.process(sl) + g_lp6000R.process(sr2)) * 0.5f;



                float l12000 = (g_lp12000L.process(sl) + g_lp12000R.process(sr2)) * 0.5f;



                float b0 = l60;



                float b1 = l120 - l60;



                float b2 = l250 - l120;



                float b3 = l500 - l250;



                float b4 = l2000 - l500;



                float b5 = l6000 - l2000;



                float b6 = l12000 - l6000;



                float b7 = mono - l12000;



                subE += b0*b0; b1E += b1*b1; b2E += b2*b2; b3E += b3*b3;



                b4E += b4*b4; b5E += b5*b5; b6E += b6*b6; b7E += b7*b7;



                float al = sl < 0.0f ? -sl : sl; if (al > pkLnow) pkLnow = al;



                float ar = sr2 < 0.0f ? -sr2 : sr2; if (ar > pkRnow) pkRnow = ar;



                sqL += sl * sl; sqR += sr2 * sr2;



                bandSquareL(sl, eL);



                bandSquareR(sr2, eR);



            }



            int frames = readSamples / ch;



            if (frames > 0) {



                // 【V8.208】上面的逐采样循环顺手把原始 L/R 平方和与瞬时峰值累出来了，



                // 这里开方成可用于 dBFS 换算的真值（这段循环本来就在走，零额外扫描）。



                float rmsLnow = std::sqrt(sqL / frames);



                float rmsRnow = std::sqrt(sqR / frames);



                g_rmsL.store(rmsLnow, std::memory_order_relaxed);



                g_rmsR.store(rmsRnow, std::memory_order_relaxed);



                // 峰值：只上升或有衰减地回落，保证下一次冲击能被抓住而不是被历史压住。



                const float pDecay = std::exp(-((float)frames / (float)sr) / 1.5f);



                const float newPL = std::max(pkLnow, g_peakL.load(std::memory_order_relaxed) * pDecay);



                const float newPR = std::max(pkRnow, g_peakR.load(std::memory_order_relaxed) * pDecay);



                // 静音时指数衰减永远到不了 0（-inf 会挂在 -300dB 上），给个地板直接归零



                g_peakL.store(newPL < 1e-5f ? 0.0f : newPL, std::memory_order_relaxed);



                g_peakR.store(newPR < 1e-5f ? 0.0f : newPR, std::memory_order_relaxed);



                float r0 = std::sqrt(subE / frames);



                float r1 = std::sqrt(b1E / frames); float r2 = std::sqrt(b2E / frames);



                float r3 = std::sqrt(b3E / frames); float r4 = std::sqrt(b4E / frames);



                float r5 = std::sqrt(b5E / frames); float r6 = std::sqrt(b6E / frames);



                float r7 = std::sqrt(b7E / frames);



                // 【V8.189】平滑系数回到「短、对称」，只做抗单点毛刺的轻平滑。



                //



                // 【V8.188 回放】上一版把 release 时间常数定成 140ms，试图让柱子



                // 「回落压住毛刺」。但这里再慢，就是在 UI 侧 VuMixer 自己的弹道



                // （kAttack 12.5ms / kRelease 170ms）之外又叠了一层慢包络，两层快



                // 慢叠加的结果是瞬态被吃掉：鼓点冲上去，140ms 的尾巴又把它拉平，



                // 真机观感就是「VU 不怎么跳动」。



                // 【V8.188 保留】三份独立 EMA 状态（mono / L / R）必须留着 —— 旧版



                // 一个 static ema[8] 先喂 mono，随后 L/R 块在同一数组上当场覆写两



                // 回，L 的读数里总掺着 R 的历史。



                // 系数仍然按 callback 时长换算（与 buffer 大小 / 采样率无关），



                // 但起落同常数：τ≈25ms，只滤掉单个 callback 的尖刺。



                static float ema[8] = {}, emaL[8] = {}, emaR[8] = {};



                const float gain = 6.0f;



                const float cbSec = (float)frames / (float)sr;



                const float kSm = 1.0f - std::exp(-cbSec / 0.025f);



                auto smooth = [](float& s, float x, float k) {



                    s += (x - s) * k;



                    return s;



                };



                const float monoRms[8] = { r0, r1, r2, r3, r4, r5, r6, r7 };



                auto cl = [](float x) { return x > 1.0f ? 1.0f : x; };



                float mNew[8];



                for (int k = 0; k < 8; k++) {



                    mNew[k] = cl(smooth(ema[k], monoRms[k], kSm) * gain);



                    // 左右声道各自独立状态：输出不压到 1.0，上限 4.0 只为防异常



                    // 输入把 UI 的自动增益带跑。



                    g_bandL[k].store(std::min(4.0f, smooth(emaL[k], std::sqrt(eL[k] / (float)frames), kSm) * gain), std::memory_order_relaxed);



                    g_bandR[k].store(std::min(4.0f, smooth(emaR[k], std::sqrt(eR[k] / (float)frames), kSm) * gain), std::memory_order_relaxed);



                }



                // 【V8.189 诊断】VU 不跳时先看这几行：mono 总能量和 L 段 0/5 的滚动



                // 值若长时间贴着 0，是 native 侧没算起来；若本身有动态而 UI 不动，



                // 问题在 VuMixer 的 AGC 分母。



                if (g_diagVuTicks < 20u) {



                    g_diagVuTicks++;



                    if ((g_diagVuTicks & 1u) == 0u) {



                        LOGD_GATED("SDW_VU mono0=%.4f band0L=%.4f band5L=%.4f mono7=%.4f",



                             mNew[0], g_bandL[0].load(std::memory_order_relaxed),



                             g_bandL[5].load(std::memory_order_relaxed), mNew[7]);



                    }



                }



                g_band0.store(mNew[0]); g_band1.store(mNew[1]);



                g_band2.store(mNew[2]); g_band3.store(mNew[3]);



                g_band4.store(mNew[4]); g_band5.store(mNew[5]);



                g_band6.store(mNew[6]); g_band7.store(mNew[7]);



            }



        }







        // 【V7.09】正弦波自检：注入 440Hz 测试音



        if (g_sineTestEnabled.load()) {



            bool stereo = (channels == 2);



            for (int i = 0; i < totalSamples; i += channels) {



                float sine = 0.3f * sinf(g_sinePhase.load());



                output[i] = sine;



                if (stereo && i + 1 < totalSamples) output[i + 1] = sine;



                g_sinePhase.store(g_sinePhase.load() + 2.0f * (float)M_PI * 440.0f / stream->getSampleRate());



            }



            g_totalSampleCount.fetch_add(totalSamples, std::memory_order_relaxed);



            g_framesWritten.fetch_add(numFrames);



            return oboe::DataCallbackResult::Continue;



        }







        // DSP processing



        // 【V8.3 修复】外层条件必须包含所有独立模块：EQ（MSEB/AutoEQ/图形）之外，



        // M/S 声场、环绕（crossfeed）、瞬态、压缩、AGC 各自独立工作，不能因 EQ 全关而失效。



        // 【M3.2】DJ SFX 一层：把当前激活的音效采样渲染成与 output 同布局的混合层 g_sfxMix，



        // 随后在 DSP 链（含多段限幅）之前叠入，由下游限幅统一防爆音。



        bool sfxLayerActive = false;



        if (g_sfxHasAny.load(std::memory_order_relaxed)) {



            sfxLayerActive = sfxRenderBlock(numFrames, channels, stream->getSampleRate());



        }







        // [V8.119] 原声直通时整块 DSP 跳过（含 limiter/softClip/dither）。



        if (!g_directPassthrough.load() && dspHasActiveEffects()) {   // [V8.514] 细粒度 hasActiveEffects 短路



            // [zipper-noise fix] fade pre-gain toward target per-frame (same SMOOTH as DAC path).



            // MSEB slider changes (g_dspEqPreGain jump) no longer step output level instantly.



            // [V8.514] preGain 计算抽到 MsebPreGainNode（double 累积，防多 stage 单精度误差）
            float targetPreGain = (float)MsebPreGainNode::computeTargetPreGain();



            float preGain = g_curDspPreGain;



            const float PREGAIN_SMOOTH = 0.05f;



            float masterGain = g_masterGain.load();



            bool agcOn = g_agcEnabled.load();



            bool ditherOn = g_ditherEnabled.load();    // 【V7.05】



            bool dcBlockOn = g_dcBlockEnabled.load();   // 【V7.05】



            // 【V8.2 hiby】blocking lock (not try_lock): JNI now holds g_eqMutex



            // only for microseconds of pure math (arrays copied outside the lock),



            // so the callback can safely wait instead of dropping EQ for a frame.



            // The old try_lock + early-return caused processed-frame <-> pass-through



            // frame switching = level/timbre step = audible crackle.



            std::lock_guard<std::mutex> eqLock(g_eqMutex);







            float frameEnergyAccum = 0.0f;



            int samplesInFrame = 0;



            // 【V8.3】瞬态整形包络系数（预计算，避免每样本 expf）。



            int sr = stream->getSampleRate();



            const float tsAtkFast = 1.0f - expf(-1.0f / (sr * 0.001f));   // 1ms



            const float tsRelFast = 1.0f - expf(-1.0f / (sr * 0.015f));   // 15ms



            const float tsAtkSlow = 1.0f - expf(-1.0f / (sr * 0.015f));   // 15ms



            const float tsRelSlow = 1.0f - expf(-1.0f / (sr * 0.080f));   // 80ms



            for (int i = 0; i < totalSamples; i += channels) {



                bool stereo = (channels == 2);







                preGain += (targetPreGain - preGain) * PREGAIN_SMOOTH;



                float sL = output[i] * preGain;







                // 【V7.05】DC Blocker



                if (dcBlockOn) sL = dcBlock(sL, g_dcX1L, g_dcY1L);







                // EQ 链：AutoEQ 打底（耳机修正）→ MSEB 叠加（主观调音）→ 5段图形EQ/默认模式



                // （三者实例独立，AutoEQ 与 MSEB 可并存叠加，不再互斥）



                if (g_autoEqEnabled.load()) {



                    sL = g_eqBand1L.process(sL);



                    sL = g_eqBand2L.process(sL);



                    sL = g_eqBand3L.process(sL);



                    sL = g_eqBand4L.process(sL);



                    sL = g_eqBand5L.process(sL);



                    sL = g_autoEqBand6L.process(sL);



                    sL = g_autoEqBand7L.process(sL);



                    sL = g_autoEqBand8L.process(sL);



                    sL = g_autoEqBand9L.process(sL);



                    sL = g_autoEqBand10L.process(sL);



                }



                if (g_mseb10Enabled.load()) {



                    sL = g_msebBand1L.process(sL);



                    sL = g_msebBand2L.process(sL);



                    sL = g_msebBand3L.process(sL);



                    sL = g_msebBand4L.process(sL);



                    sL = g_msebBand5L.process(sL);



                    sL = g_msebBand6L.process(sL);



                    sL = g_msebBand7L.process(sL);



                    sL = g_msebBand8L.process(sL);



                    sL = g_msebBand9L.process(sL);



                    sL = g_msebBand10L.process(sL);



                } else if (!g_autoEqEnabled.load()) {



                    if (g_eq5BandEnabled.load()) {



                        sL = g_eqBand1L.process(sL);



                        sL = g_eqBand2L.process(sL);



                        sL = g_eqBand3L.process(sL);



                        sL = g_eqBand4L.process(sL);



                        sL = g_eqBand5L.process(sL);



                    } else {



                        sL = g_eqBand1L.process(sL);



                        sL = g_eqBand2L.process(sL);



                    }



                }







                sL *= masterGain;







                float rawR = stereo ? output[i + 1] * preGain : sL;



                frameEnergyAccum += sL * sL;



                if (stereo) frameEnergyAccum += rawR * rawR;



                samplesInFrame += stereo ? 2 : 1;







                float sR = 0.0f;



                if (stereo) {



                    sR = rawR;



                    if (dcBlockOn) sR = dcBlock(sR, g_dcX1R, g_dcY1R);



                    // EQ 链：AutoEQ 打底 → MSEB 叠加 → 5段图形EQ/默认（与 L 链对称）



                    if (g_autoEqEnabled.load()) {



                        sR = g_eqBand1R.process(sR);



                        sR = g_eqBand2R.process(sR);



                        sR = g_eqBand3R.process(sR);



                        sR = g_eqBand4R.process(sR);



                        sR = g_eqBand5R.process(sR);



                        sR = g_autoEqBand6R.process(sR);



                        sR = g_autoEqBand7R.process(sR);



                        sR = g_autoEqBand8R.process(sR);



                        sR = g_autoEqBand9R.process(sR);



                        sR = g_autoEqBand10R.process(sR);



                    }



                    if (g_mseb10Enabled.load()) {



                        sR = g_msebBand1R.process(sR);



                        sR = g_msebBand2R.process(sR);



                        sR = g_msebBand3R.process(sR);



                        sR = g_msebBand4R.process(sR);



                        sR = g_msebBand5R.process(sR);



                        sR = g_msebBand6R.process(sR);



                        sR = g_msebBand7R.process(sR);



                        sR = g_msebBand8R.process(sR);



                        sR = g_msebBand9R.process(sR);



                        sR = g_msebBand10R.process(sR);



                    } else if (!g_autoEqEnabled.load()) {



                        if (g_eq5BandEnabled.load()) {



                            sR = g_eqBand1R.process(sR);



                            sR = g_eqBand2R.process(sR);



                            sR = g_eqBand3R.process(sR);



                            sR = g_eqBand4R.process(sR);



                            sR = g_eqBand5R.process(sR);



                        } else {



                            sR = g_eqBand1R.process(sR);



                            sR = g_eqBand2R.process(sR);



                        }



                    }



                    sR *= masterGain;



                }







                // AGC



                float agcGainL = 1.0f, agcGainR = 1.0f;



                if (agcOn) {



                    // 【V8.212】测量值 = 上一个 callback 的完整帧功率；首个 callback 还没攒出来，



                    // 退回本帧运行均值（旧行为），之后一直用整帧值 —— 增益在一个 callback 内不再爬升。



                    float measPower = (g_agcPrevFramePower > 0.0f)



                        ? g_agcPrevFramePower



                        : (frameEnergyAccum / (float)std::max(samplesInFrame, 1));



                    float agcGain = g_agc.updateAndGetGainFromPower(measPower);



                    sL *= agcGain;



                    if (stereo) sR *= agcGain;



                    // 【V8.213 诊断】每 200 个 callback（≈2s）采样一次增益，确认收紧后稳在 ~1.0、波动 ≤ ±1.5dB。



                    if ((g_agcSampleCounter.fetch_add(1) % 200) == 0) {



                        LOGD_GATED("AGC diag gain=%.3f rms=%.4f tgt=%.4f", agcGain, g_agc.currentRms, g_agc.targetRms);



                    }



                }







                // 【V8.3】等响补偿（ISO 226）—— EQ/masterGain/AGC 之后、M/S 之前。



                // stereo-linked：L/R 共享 phon 估算与补偿系数，各保独立滤波状态。



                if (g_loudnessEnabled.load()) {



                    g_loudness.setOutGain(masterGain);



                    g_loudness.process(sL, stereo ? sR : sL);



                    if (!stereo) sR = sL;



                }







                // 【V8.3】【V8.19】【V8.172】M/S 声场处理 — DTS 虚拟环绕渲染（仅 stereo）。



                // M=(L+R)/2 结像中心，S=(L-R)/2 声场宽度。



                // V8.172 升级：三段独立宽度 + 四抽头早期反射 + HRTF 双耳渲染 +



                // 环绕强度独立成参。兼容：宽度=1、focus=1、surround=0 时与原声一致。



                // 平滑系数（zipper-noise fix），作用在 EQ/masterGain 之后、limiter 之前。



                if (g_msEnabled.load() && stereo) {



                    float w = g_curMsWidth;



                    float c = g_curMsCenter;



                    float tw = g_msWidth.load();



                    float tc = g_msCenter.load();



                    w += (tw - w) * PREGAIN_SMOOTH;



                    c += (tc - c) * PREGAIN_SMOOTH;



                    g_curMsWidth = w;



                    g_curMsCenter = c;







                    // 三段宽度：以总宽度 w 为基准，按频段分别给不同的扩展力度。



                    //   bass 最多收缩不扩展 —— 虚拟环绕扩宽低频会让低音发虚、漂移；



                    //   high 给 1.35 次方 —— 环绕线索（扩散/空气）基本都在这一带，



                    //   多给一点是"效果明显"和"效果糊掉"的分界线；



                    //   mid 折中，保证人声不散。



                    float wb = (w < 1.0f) ? w : 1.0f;



                    g_dtsCfg.widthBass = wb;



                    g_dtsCfg.widthMid  = w;



                    g_dtsCfg.widthHigh = powf(w, 1.35f);



                    g_dtsCfg.focus     = c;



                    g_dtsCfg.surround  = g_dtsSurround.load(std::memory_order_relaxed);



                    g_dtsCfg.height    = g_dtsHeight.load(std::memory_order_relaxed);



                    g_dtsCfg.hfTilt    = g_dtsHfTilt.load(std::memory_order_relaxed);







                    float mid = (sL + sR) * 0.5f;



                    float side = (sL - sR) * 0.5f;



                    float outL, outR;



                    g_dts.process(mid, side, g_dtsCfg, outL, outR);



                    sL = outL;



                    sR = outR;



                }







                // 【V8.3】Crossfeed — 消除头中效应（仅 Oboe，stereo）。



                // 左耳混入对侧低通延迟信号，模拟头部遮挡，缓解长时间听歌疲劳。



                // 作用在 M/S 后、瞬态整形前（线性时不变处理，保持立体声关系）。



                if (g_crossfeedEnabled.load() && stereo) {



                    g_curCrossfeedAmount += (g_crossfeedAmount.load() - g_curCrossfeedAmount) * PREGAIN_SMOOTH;



                    if (g_curCrossfeedAmount > 0.001f) {



                        g_crossfeed.process(sL, sR, g_curCrossfeedAmount);



                    }



                }







                // 【V8.3】瞬态整形 — 双时间常数包络跟随器（时域）。



                // fastEnv（attack ~1ms / release ~15ms）跟踪瞬态，slowEnv（attack ~15ms / release ~80ms）跟踪持续电平。



                // transient = fastEnv - slowEnv；gain = 1 + amount*transient*K（amount>0 增强 attack，<0 柔化）。



                // 作用在 M/S 后、limiter 前（线性处理后，非线性时变，保持立体声关系）。



                if (g_transientEnabled.load()) {



                    g_curTransientAmount += (g_transientAmount.load() - g_curTransientAmount) * PREGAIN_SMOOTH;



                    float amt = g_curTransientAmount;



                    if (fabsf(amt) > 0.001f) {



                        float aL = fabsf(sL);



                        g_tsFastEnvL += (aL - g_tsFastEnvL) * (aL > g_tsFastEnvL ? tsAtkFast : tsRelFast);



                        g_tsSlowEnvL += (aL - g_tsSlowEnvL) * (aL > g_tsSlowEnvL ? tsAtkSlow : tsRelSlow);



                        float transL = g_tsFastEnvL - g_tsSlowEnvL;



                        // [V8.118] 4.0->1.5: 1ms probe on <60Hz AM-modulated the bass itself



                        // (audible buzz on kick). Track slowEnv so low freq is not modulated,



                        // and cap the boost so it can no longer slam the limiter.



                        float tsDriveL = (g_tsSlowEnvL > 0.20f && transL < 0.12f) ? 0.0f : transL;



                        float gainL = 1.0f + amt * tsDriveL * 1.5f;



                        if (gainL < 0.05f) gainL = 0.05f;



                        if (gainL > 1.5f) gainL = 1.5f;



                        sL *= gainL;



                        if (stereo) {



                            float aR = fabsf(sR);



                            g_tsFastEnvR += (aR - g_tsFastEnvR) * (aR > g_tsFastEnvR ? tsAtkFast : tsRelFast);



                            g_tsSlowEnvR += (aR - g_tsSlowEnvR) * (aR > g_tsSlowEnvR ? tsAtkSlow : tsRelSlow);



                            float transR = g_tsFastEnvR - g_tsSlowEnvR;



                            // [V8.118] same as L: LF protection + 1.5x cap



                            float tsDriveR = (g_tsSlowEnvR > 0.20f && transR < 0.12f) ? 0.0f : transR;



                            float gainR = 1.0f + amt * tsDriveR * 1.5f;



                            if (gainR < 0.05f) gainR = 0.05f;



                            if (gainR > 1.5f) gainR = 1.5f;



                            sR *= gainR;



                        }



                    }



                }







                // 【V8.3】动态压缩 — master bus 向下压缩器（stereo-linked）。



                // 作用在瞬态整形之后、Look-Ahead Limiter 之前。



                if (g_compressorEnabled.load()) {



                    g_compressor.process(sL, sR);



                }







                // 【V8.170】程序化空间混响 — 湿声干湿比由 UI 给，room/damping 已预置。



                // 放在压缩之后、限幅之前：混响尾音也要被 limiter 管住，否则尾音把



                // 下一记鼓点的 headroom 吃掉（"混响吃鼓"的老毛病）。



                if (g_spaceReverbEnabled.load()) {



                    float wetAmt = g_spaceReverbWet.load();



                    if (wetAmt > 0.0005f) {



                        if (!g_spaceReverb.configured) {



                            g_spaceReverb.configure((float)sr, g_spaceReverbRoom.load(), g_spaceReverbDamp.load());



                        }



                        g_spaceReverb.process(sL, stereo ? sR : sL, wetAmt);



                        if (!stereo) sR = sL;



                    }



                }







                // 【V8.170】动态 EQ（逐频段压缩）— 解决"低频嗡 + 人声挤在一起被同一条



                // 压缩器处理"。无过冲时 g≡1，输出与输入逐样本一致。



                if (g_dynEqEnabled.load()) {



                    g_dynEq.process(sL, stereo ? sR : sL, g_dynEqDepth.load());



                }







                // 【2026-09-07】A2DP 编码前预补偿（蓝牙低码率编码专用，codecDb=0 时旁路）



                // [V8.118] MOVED here (was after the limiter). It is a nonlinear,



                // time-varying 5kHz+ transient boost; running it post-limiter meant its



                // boost was never tamed and got square-clipped by softClip -> the



                // "mid-high buzz/crackle on Bluetooth" symptom. Pre-limiter, the



                // look-ahead limiter now catches its peaks properly.



                btPreEmphasisFrame(sL, sR, stereo);







                // 【V8.170】Multi-Band Limiter — 3 段各自限幅后合成（低 <200Hz / 中 <2.5k / 高）。



                // 宽带限幅会在低频抬升时把中高频一起压下去；分段后互不牵连。



                // 【V8.175】分段限幅的开关移进了 processChannel（关=纯延迟直通）。



                // 宽带 look-ahead limiter 改为常开 —— 它是整条链的最终安全网，



                // 职责是防冲顶而不是音色，几 dB 的持续 GR 也比硬削波好得多。



                // 【M3.2】叠入 DJ SFX 混合层（已在 g_sfxMix 渲染），由多段限幅统一防爆音。



                if (sfxLayerActive) {



                    sL += g_sfxMix[i];



                    if (stereo) sR += g_sfxMix[i + 1];



                }



                g_mbLimiter.write(sL, stereo ? sR : sL);



                float dL = g_mbLimiter.readDelayed(false);



                float dR = g_mbLimiter.readDelayed(true);



                float outL = g_mbLimiter.processChannel(dL, false);



                float outR = stereo ? g_mbLimiter.processChannel(dR, true) : 0.0f;



                // [V8.514] 链路末端安全网（limiter + softClip + dither）抽到 TerminalLimiterNode
                TerminalLimiterNode::process(outL, outR, stereo, ditherOn);







                // [V8.118] Precise clip telemetry: count only samples that the FINAL



                // clamp actually had to squeeze (i.e. real hard clipping), and track peak.



                float aL2 = std::fabsf(outL);



                float aR2 = stereo ? std::fabsf(outR) : 0.0f;



                float pk = stereo ? std::fmaxf(aL2, aR2) : aL2;



                if (pk > 0.999f) {



                    g_clipSampleCount.fetch_add(1, std::memory_order_relaxed);



                    if (pk > g_clipPeak.load(std::memory_order_relaxed))



                        g_clipPeak.store(pk, std::memory_order_relaxed);



                }



                if (aL2 > 1.0f) outL = outL > 0 ? 1.0f : -1.0f;



                if (stereo && aR2 > 1.0f) outR = outR > 0 ? 1.0f : -1.0f;







                output[i] = std::min(1.0f, std::max(-1.0f, outL));



                if (stereo) output[i + 1] = std::min(1.0f, std::max(-1.0f, outR));



            }



            g_curDspPreGain = preGain;



            // 【V8.212】把本 callback 的完整帧功率留给下一个 callback 当 AGC 测量值



            if (samplesInFrame > 0) g_agcPrevFramePower = frameEnergyAccum / (float)samplesInFrame;



            g_totalSampleCount.fetch_add(totalSamples, std::memory_order_relaxed);



        } else {



            // DSP disabled — just count samples for diagnostics



            // 【M3.2】原声直通档：SFX 仍需叠加并软钳，避免切歌音效在直通下爆音



            if (sfxLayerActive) {



                for (int i = 0; i < totalSamples; i++) {



                    output[i] = softClip(output[i] + g_sfxMix[i]);



                }



            }



            g_dspDisabledSampleCount.fetch_add(totalSamples, std::memory_order_relaxed);



        }







        // 【V7.xx】Sink EQ 5-band graphic EQ (applied to main Oboe output)



        // [V8.118] Sink EQ runs AFTER limiter/softClip, so its own boost could



        // re-introduce hard clipping at the very end. Guard with a soft safety



        // shaper so a Sink-EQ boost cannot produce square-wave distortion.



        // 【V8.198】原实现无条件按 output[i*2] / output[i*2+1] 成对读写，



        // 单声道输出流（部分蓝牙/USB 单声道链路）下 R2 会写到音频缓冲之外 —— 越界写。



        // 单声道时 Sink EQ 也没有立体声意义，直接跳过。



        bool sinkEqOn = g_sinkEqEnabled.load(std::memory_order_acquire)



                        && !g_directPassthrough.load() && channels >= 2;



            // 【V8.429 修"声道平衡完全没效果"】原来整段（含 applyStereoBalance）被包在
            // if (sinkEqOn) 里 → **Sink EQ 没开时声道平衡一次都不施加**，滑块怎么拖都没反应。
            // 现在拆成两段：Sink EQ 仍按原条件走，声道平衡 + softClip 无条件走。
            bool balNeeded = !(g_stereoBalance.load(std::memory_order_relaxed) > -0.001f
                           && g_stereoBalance.load(std::memory_order_relaxed) <  0.001f);
            if (sinkEqOn) {

                std::unique_lock<std::mutex> lock(g_sinkEqMutex, std::try_to_lock);
if (lock.owns_lock()) {

            for (int i = 0; i < numFrames; i++) {

                float& L2 = output[i * 2];

                float& R2 = output[i * 2 + 1];

                applySinkEqStereoFrame(L2, R2);

                // 【V8.313】声道平衡：放在 EQ 之后、softClip 之前 —— 之后则不受 EQ 影响，

                // 之前则偏移后的电平还能被限幅兜住（不会因为偏到一边而额外削顶）。

                applyStereoBalance(L2, R2);

                if (L2 > 1.0f || L2 < -1.0f || R2 > 1.0f || R2 < -1.0f) {

                    L2 = softClip(L2);
                    R2 = softClip(R2);
                    if (L2 > 1.0f) L2 = 1.0f; else if (L2 < -1.0f) L2 = -1.0f;
                    if (R2 > 1.0f) R2 = 1.0f; else if (R2 < -1.0f) R2 = -1.0f;
                    g_clipSampleCount.fetch_add(1, std::memory_order_relaxed);
                }
            }

        }}

            // 【V8.429】无Sink EQ 时的平衡 + 限幅路径（原来完全缺失）。
            // 单声道链路没有左右可分，跳过。
            else if (balNeeded && channels >= 2) {
                for (int i = 0; i < numFrames; i++) {
                    float& L2 = output[i * 2];
                    float& R2 = output[i * 2 + 1];
                    applyStereoBalance(L2, R2);
                    if (L2 > 1.0f || L2 < -1.0f || R2 > 1.0f || R2 < -1.0f) {
                        L2 = softClip(L2);
                        R2 = softClip(R2);
                        if (L2 > 1.0f) L2 = 1.0f; else if (L2 < -1.0f) L2 = -1.0f;
                        if (R2 > 1.0f) R2 = 1.0f; else if (R2 < -1.0f) R2 = -1.0f;
                        g_clipSampleCount.fetch_add(1, std::memory_order_relaxed);
                    }
                }
            }







        g_framesWritten.fetch_add(numFrames);



        // 【V8.227/229 de-click】切歌翻转后的短修正窗：out = raw + (lastOut - raw0) * (1 - t)，



        // 首样本被钉在翻转前的末样本上（波形严格连续），~6ms 内线性收敛回原始信号。



        // 末样本在本回调最末（全部 DSP 之后）采集，与修正应用点同一基准。



        if (g_declickArmPending) {



            g_declickArmPending = false;



            if (channels > 0) {



                const int cN = channels > 8 ? 8 : channels;



                for (int c = 0; c < cN; c++)



                    g_declickLast[c] = output[(numFrames - 1) * channels + c];



                g_declickRemain = 2048;   // 【V8.497 加宽】原 512(≈10.6ms@48k) 对 0.15~0.40 阶跃仍听成"哒一下"；摊到 2048(≈43ms@48k) 斜率远低于"咔哒"阈值，仅确有阶跃时生效、零副作用



                g_declickTotal  = 2048;



                g_declickPrimed = false;



            }



        } else if (g_declickRemain > 0) {



            if (!g_declickPrimed) {



                // 【V8.24x 健壮性】翻转换轨瞬间新 active 环若欠载，本帧首样本来自补零而非真实



                // 信号；若据此锁定 delta 会把基准锚到 0，待 B 数据回来时整段真实信号被叠加一个



                // 恒量 → 电平突变 = 啪一下。因此只在确认本帧确有真实数据时才上膛，否则留到下一



                // 帧有数据时再锁（remain 不动，窗口不推进）。



                PCMRingBuffer* ar = g_activeIsB.load(std::memory_order_relaxed) ? g_ringBufferB : g_ringBuffer;



                bool have = (ar != nullptr) && (ar->available() >= channels);



                if (have) {



                    const int cN = channels > 8 ? 8 : channels;



                    for (int c = 0; c < cN; c++) g_declickDelta[c] = g_declickLast[c] - output[c];



                    g_declickPrimed = true;



                }



            }



            if (g_declickPrimed) {



                int left = g_declickRemain;



                for (int f = 0; f < numFrames && left > 0; f++, left--) {



                    const float t = (float)left / (float)g_declickTotal;



                    const float w = sinf(1.5707963f * t);   // 等功率软着陆（首尾比线性更平滑，几乎无感）



                    const int cN = channels > 8 ? 8 : channels;



                    for (int c = 0; c < cN; c++)



                        output[f * channels + c] += g_declickDelta[c] * w;



                }



                g_declickRemain = left;



            }



        }



        // 【V7.67】更新实际播放位置（微秒）



        int32_t sr = stream->getSampleRate();



        if (sr <= 0) sr = 44100;



        g_playbackPositionUs.fetch_add((int64_t)numFrames * 1000000LL / sr);



        return oboe::DataCallbackResult::Continue;



    }







    void onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error) override {



        LOGE("Oboe stream error before close: %s", oboe::convertToText(error));



    }







    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override {



        int prevRate = stream ? stream->getSampleRate() : 0;



        const char* prevMode = stream && stream->getSharingMode() == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared";



        LOGE("Oboe stream error after close: %s | was %s@%dHz — attempting restart", oboe::convertToText(error), prevMode, prevRate);



        std::lock_guard<std::mutex> lock(g_streamMutex);



        int sr = g_sampleRate.load();



        int ch = g_channelCount.load();







        oboe::AudioStreamBuilder builder;



        builder.setDirection(oboe::Direction::Output);



        builder.setPerformanceMode(oboe::PerformanceMode::PowerSaving);



        builder.setSharingMode(g_preferSharedMode.load() ? oboe::SharingMode::Shared : oboe::SharingMode::Exclusive);



        builder.setFormat(oboe::AudioFormat::Float);



        builder.setSampleRate(sr);



        builder.setChannelCount(ch);



        builder.setCallback(this);



        builder.setBufferCapacityInFrames(9600);



        oboe::Result result = builder.openManagedStream(g_outputStream);



        if (result != oboe::Result::OK) {



            LOGW("Exclusive restart failed, trying Shared: %s", oboe::convertToText(result));



            builder.setSharingMode(oboe::SharingMode::Shared);



            result = builder.openManagedStream(g_outputStream);



        }



        if (result == oboe::Result::OK) {



            g_outputStream->requestStart();



            pthread_t tid = pthread_self();



            struct sched_param sp;



            sp.sched_priority = sched_get_priority_max(SCHED_FIFO) - 5;



            pthread_setschedparam(tid, SCHED_FIFO, &sp);



            g_sampleRate.store(g_outputStream->getSampleRate());



            g_channelCount.store(g_outputStream->getChannelCount());



            const char* restartedMode = g_outputStream->getSharingMode() == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared";



            LOGI("Oboe stream restarted: rate=%d mode=%s (was %s) ⚠ mode change detected", g_outputStream->getSampleRate(), restartedMode, prevMode);



            // 【V8.294】路由切换（蓝牙断开/连接）后端点可能尚未就绪，AAudio 客户端流要等数秒才绑定到端点



            // → 静音「好几秒」。启动看门狗：若回调未在短时间内恢复，则按当前默认设备重试重建，直到回调恢复。



            startRouteRebindWatchdog();



        } else {



            LOGE("Oboe stream restart failed: %s", oboe::convertToText(result));



        }



    }



};



static OboeAudioCallback g_audioCallback;







// 前向声明：看门狗（定义于下方）可能早于其定义调用 reopenOutputStream



static bool reopenOutputStream(int newRate);







// 【V8.294】路由切换（蓝牙断开/连接）后输出流静音看门狗。



// 背景：onErrorAfterClose 立即重建流，但端点（尤其 A2DP）往往数秒后才就绪，



// AAudio 客户端流要等很久才绑定到端点 → 「连/断蓝牙静音好几秒」。



// 这里周期性检查回调是否恢复；未恢复则按当前默认设备重试重建，直到回调恢复或达上限。



// 该机制与具体广播无关，任何设备/路由均生效。



static std::atomic<bool> g_rebindWatchdogRunning{false};



static void startRouteRebindWatchdog() {



    bool expected = false;



    if (!g_rebindWatchdogRunning.compare_exchange_strong(expected, true)) return;  // 已有看门狗在跑



    std::thread([]() {



        const int maxTries = 24;   // 最多 ~20s（每次 500ms + 350ms 检测）



        for (int i = 0; i < maxTries; i++) {



            std::this_thread::sleep_for(std::chrono::milliseconds(500));



            // 【V8.317】同上：改用 latch 判定「是否还在播放会话内」。



            // 蓝牙断连造成的 pause 不算会话结束，仍需继续观察路由恢复。



            if (!g_playSessionLatched.load()) break;          // 会话已真正结束，退出



            if (g_outputDeviceId.load() != 0) break;         // 显式设备（USB DAC 独占）不介入



            int64_t before = g_callbackCount.load(std::memory_order_relaxed);



            // 检测窗 > 最大回调间隔（bufferCapacity 9600 / 44100 ≈ 217ms），避免误判健康流为停滞



            std::this_thread::sleep_for(std::chrono::milliseconds(350));



            int64_t after = g_callbackCount.load(std::memory_order_relaxed);



            if (after != before) break;                      // 回调已在流动 → 端点就绪，退出



            LOGI("RouteRebind watchdog: callback stalled, reopening output (try %d, latched=%d isPlaying=%d)",



                 i + 1, (int)g_playSessionLatched.load(), (int)g_isPlaying.load());



            const bool rbOk = reopenOutputStream(g_sampleRate.load());



            LOGI("RouteRebind watchdog: reopen %s", rbOk ? "OK" : "FAILED");



        }



        g_rebindWatchdogRunning.store(false);



    }).detach();



}







// ============================================================================



// NDK Decoder — globals and decode loop



// ============================================================================



static AMediaExtractor* g_decoderExtractor = nullptr;



// 【V8.323】g_cachedDurationUs / g_cachedDurationB 的定义已前移到行~1638



// （crossfade 落点块要用它们给新位置做上界钳制，那儿在原定义位置之前）。



static AMediaCodec* g_decoderCodec = nullptr;
// [V8.392 decoder residency] mime of the current codec.
// Reuse only when mime is identical: FLAC and MP3 map to DIFFERENT codecs
// (c2.android.flac.decoder vs c2.android.mp3.decoder), so a format
// change must fully recreate; only the same-mime case can stop+flush.
static char g_decoderMime[64] = {0};



static int g_decoderTrackIndex = -1;



static std::atomic<bool> g_decoderRunning{false};



static std::atomic<bool> g_decoderPaused{false};



// 【Crossfade】g_decoderStopRequested / g_decoderCv 已前置声明到 onAudioReady 之前



static std::atomic<bool> g_decoderEos{false};



// 【Crossfade】g_decoderPositionUs 已前置声明到 onAudioReady 之前



static std::thread g_decoderThread;



static std::mutex g_decoderMutex;



// 【Crossfade】B 轨独立 decoder（incoming）



static AMediaExtractor* g_decoderExtractorB = nullptr;



static AMediaCodec* g_decoderCodecB = nullptr;
// [V8.394 prescan residency] a single long-lived codec for ALL preScanPath() calls.
// Safe ONLY because V8.393 made prescan concurrency == 1 (prescanExecutor).
// Note: preScanPath(mode 0/1/2/3/4) used to create+delete a throwaway AMediaCodec
// per call -- that was the main MediaCodec churn source.
static AMediaCodec* g_scanCodec = nullptr;
static char g_scanMime[64] = {0};
static char g_decoderMimeB[64] = {0};



static int g_decoderTrackIndexB = -1;



static std::atomic<bool> g_decoderRunningB{false};



static std::atomic<bool> g_decoderPausedB{false};



static std::atomic<bool> g_decoderEosB{false};
// 【V8.504】出轨（当前 active = 正在被交叉淡出的那首）是否真 EOS：解码器到尾且线程已退出。
// 与瞬时欠载区分（瞬时欠载时 g_decoderRunning 仍 true，会很快恢复供数）。
// 用于交叉淡化进行中、出轨已放完但过渡还没收尾时，立即收敛包络 pos→1.0 干净接管，
// 杜绝 "歌停了过渡还在跑" 的过挂空读噪音（每帧一次末样本×衰减 blip）。
static bool xfadeOutgoingEos() {
    return g_activeIsB.load() ? (g_decoderEosB.load() && !g_decoderRunningB.load())
                              : (g_decoderEos.load()  && !g_decoderRunning.load());
}



static std::thread g_decoderThreadB;



static std::mutex g_decoderMutexB;



static std::vector<float> g_convertBufferB;



static std::atomic<int> g_decoderOutputEncodingB{2};



static std::atomic<bool> g_decoderIsFloatB{false};   // 【V8.239】B 轨 float 标志（与 A 轨 g_decoderIsFloat 对称）



// 【V8.323】g_cachedDurationB 定义已前移到行~1638







// 【V8.251 修复·闪退根因】解码线程 join 的异常安全 + 幂等封装。



// joinable() 检查与 join() 之间存在竞态（另一线程可能已 join 同一对象），第二个



// joiner 会抛 std::system_error(EINVAL "Invalid argument") 且 JNI 线程无人捕获



// → SIGABRT 闪退（用户表现：「DJ 点了没反应，应用自己重启，不能再切歌」）。



// 8.247 的「解码不再丢样本」让旧线程存活时间从毫秒级变为最长 60s，放大了此竞态。



static void joinDecoderA() {



    std::thread* t = &g_decoderThread;



    try {



        if (t->joinable()) t->join();



    } catch (const std::exception& e) {



        LOGW("join decoderA failed: %s (force reset)", e.what());



        try { if (t->joinable()) t->detach(); } catch (...) {}



        *t = std::thread();



    }



}



static void joinDecoderB() {



    std::thread* t = &g_decoderThreadB;



    try {



        if (t->joinable()) t->join();



    } catch (const std::exception& e) {



        LOGW("join decoderB failed: %s (force reset)", e.what());



        try { if (t->joinable()) t->detach(); } catch (...) {}



        *t = std::thread();



    }



}



static std::atomic<bool> g_decoderThreadRunningB{false};



static std::atomic<int> g_decoderFramesOutputB{0};



// 【Crossfade 重采样】B 槽 incoming 采样率 + 线性重采样到 stream 采样率的状态



// 目的：A 轨(48k) crossfade 到 B 轨(44.1k) 时，B 轨数据若不重采样会被 48k stream 快放 -> 变调



static std::atomic<int> g_sampleRateB{0};          // B 轨 decoder 输出采样率（0=未定）



static std::atomic<int> g_channelCountB{2};        // 【V8.3】B 轨 decoder 输出声道数（重采样用，不能共享 A 轨的）



static std::vector<float> g_resampleOutB;          // B 轨重采样输出缓冲



static double g_resamplePhaseB = 0.0;              // 输入帧域相位残差



static float g_resamplePrevB[8 * 64] = {0.0f};     // 跨块历史缓冲（窗化 sinc 抗混叠用，最多 8 声道 × 64 帧）
static int   g_resampleHistLenB = 0;               // 当前有效历史样本数（交错，= 帧数 × 声道数）






static bool g_resampleLoggedB = false;



static void resetResamplerB() { g_resamplePhaseB = 0.0; g_resampleHistLenB = 0; memset(g_resamplePrevB, 0, sizeof(g_resamplePrevB)); }



// 【Crossfade 重采样】A 槽对称重采样状态（A 槽作为 incoming 时，解码输出重采样到 stream 率）



static std::atomic<int> g_sampleRateA{0};          // A 轨 decoder 输出采样率（0=未定）



static std::vector<float> g_resampleOutA;          // A 轨重采样输出缓冲



static double g_resamplePhaseA = 0.0;



static float g_resamplePrevA[8 * 64] = {0.0f};     // 跨块历史缓冲（窗化 sinc 抗混叠用，最多 8 声道 × 64 帧）
static int   g_resampleHistLenA = 0;               // 当前有效历史样本数（交错，= 帧数 × 声道数）






static bool g_resampleLoggedA = false;



static void resetResamplerA() { g_resamplePhaseA = 0.0; g_resampleHistLenA = 0; memset(g_resamplePrevA, 0, sizeof(g_resamplePrevA)); }



// 线性插值重采样（interleaved PCM -> interleaved PCM），跨块连续（phase/prev 状态由调用方持有）



static const int RS_L = 16;        // 核半长（抽头 = 2*RS_L+1 = 33）
static const int RS_GRID = 256;    // 窗化 sinc 连续查表网格
static const double RS_PI = 3.14159265358979323846;

// 窗化 sinc 查表缓存：按 (inRate,outRate) 组合缓存，最多 4 组，避免每块重算整张表
struct RS_TabCache { int inRate = 0; int outRate = 0; std::vector<float> tab; };
static RS_TabCache g_rsTabCache[4];
static int g_rsTabCacheIdx = 0;
static const float* getRsTab(int inRate, int outRate, int NTAP, int GRID, int L, double fc) {
    for (int i = 0; i < 4; i++) {
        if (g_rsTabCache[i].inRate == inRate && g_rsTabCache[i].outRate == outRate && !g_rsTabCache[i].tab.empty())
            return g_rsTabCache[i].tab.data();
    }
    RS_TabCache& c = g_rsTabCache[g_rsTabCacheIdx];
    g_rsTabCacheIdx = (g_rsTabCacheIdx + 1) % 4;
    c.inRate = inRate; c.outRate = outRate;
    c.tab.assign((size_t)NTAP * (size_t)GRID, 0.0f);
    float* t = c.tab.data();
    for (int n = -L; n <= L; n++) {
        for (int g = 0; g < GRID; g++) {
            double tt = (double)n + (double)g / (double)GRID;
            double sinc = (tt == 0.0) ? (2.0 * fc) : (sin(2.0 * RS_PI * fc * tt) / (RS_PI * tt));
            double w = 0.42 - 0.50 * cos(2.0 * RS_PI * ((double)tt + (double)L) / (2.0 * (double)L))
                            + 0.08 * cos(4.0 * RS_PI * ((double)tt + (double)L) / (2.0 * (double)L));
            t[(n + L) * GRID + g] = (float)(sinc * w);
        }
    }
    double sum = 0.0;
    for (int n = -L; n <= L; n++) sum += t[(n + L) * GRID];
    if (sum > 1e-9) for (size_t i = 0; i < c.tab.size(); i++) t[i] = (float)((double)t[i] / sum);
    return t;
}

static int resampleLinearToStream(const float* in, int inSamples, int channels,
                                  int inRate, int outRate,
                                  std::vector<float>& out,
                                  double& phase, float* hist, int& histLen, int histCap) {
    out.clear();
    if (inSamples <= 0 || channels <= 0 || inRate <= 0 || outRate <= 0 || inRate == outRate) return 0;

    const int L = RS_L;
    const int NTAP = 2 * L + 1;
    const int GRID = RS_GRID;
    const int ch = channels;
    const int inFrames = inSamples / ch;
    if (inFrames <= 0) return 0;

    // 截止频率（归一化到输入采样率）= 两侧奈奎斯特较小者：下采样时核即理想低通
    const double fmin = (double)inRate < (double)outRate ? (double)inRate : (double)outRate;
    const double fc = 0.5 * fmin / (double)inRate;

    // 窗化 sinc 连续函数 g(t)（t∈[-L,L]）查表（线性插值取值），g(t)=2*fc*sinc(2*fc*t)*Blackman(t/L)。
    // 按 (inRate,outRate) 组合缓存，避免每块重算（混合采样率曲库持续上采样时显著省电）。
    const float* tab = getRsTab(inRate, outRate, NTAP, GRID, L, fc);

    const int M = histLen + inSamples;
    const int Mf = M / ch;
    std::vector<float> src((size_t)M);
    if (histLen > 0) memcpy(src.data(), hist, (size_t)histLen * sizeof(float));
    memcpy(src.data() + histLen, in, (size_t)inSamples * sizeof(float));

    double ratio = (double)inRate / (double)outRate;
    out.reserve((size_t)((double)inFrames * (double)outRate / (double)inRate) + (size_t)channels * 16);

    if ((double)Mf < (double)L * 2.0 + 1.0) { out.clear(); return 0; }

    double pos = phase;
    if (pos < (double)L) pos = (double)L;

    int produced = 0;
    while (pos < (double)Mf - (double)L) {
        int center = (int)floor(pos);
        double frac = pos - (double)center;
        for (int c = 0; c < ch; c++) {
            float acc = 0.0f;
            for (int k = -L; k <= L; k++) {
                int idx = center + k;
                double t = (double)k - frac;
                double ft = (t + (double)L) * (double)GRID;
                int gi0 = (int)ft; double fr = ft - (double)gi0;
                if (gi0 < 0) { gi0 = 0; fr = 0.0; }
                int gi1 = gi0 + 1; if (gi1 >= NTAP * GRID) gi1 = NTAP * GRID - 1;
                float w = tab[gi0] + (tab[gi1] - tab[gi0]) * (float)fr;
                acc += src[idx * ch + c] * w;
            }
            out.push_back(acc);
        }
        produced++;
        pos += ratio;
    }

    int keepFrames = (Mf < 2 * L) ? Mf : (2 * L);
    int keepSamples = keepFrames * ch;
    if (keepSamples > histCap) keepSamples = histCap - (histCap % ch);
    if (keepSamples > 0) {
        int srcOff = M - keepSamples; if (srcOff < 0) srcOff = 0;
        memcpy(hist, src.data() + srcOff, (size_t)keepSamples * sizeof(float));
    }
    histLen = keepSamples;
    phase = pos - ((double)Mf - (double)keepFrames);

    return produced;
}



// 【V8.3】decoder 生命周期锁：串行化 nativeOpen/nativeOpenFd/nativeStop/nativePlay/nativeSeekTo



// 对 g_decoderExtractor/g_decoderCodec/g_decoderThread 全局单例指针的 join/delete/create 操作。



// 根因：快速切歌时「切歌后台线程」与「主线程 fallback stop 回调」并发 join 同一 std::thread、



// delete 同一 codec/extractor，导致 use-after-free(SIGSEGV) + 重复 configure/start(SIGABRT)。



// 此锁不与 g_streamMutex 反序嵌套（所有持锁顺序恒为 lifecycle → stream）。



static std::timed_mutex g_decoderLifecycleMutex;
/* [V8.395] Lock-owner diagnostics. A previous open that blocks forever keeps
   this mutex; later opens fail at try_lock_for and print which stage the stuck
   owner is in. Stages: 1=joinDecoder 2=stop 3=extractor 4=codec 5=configure
   6=start 7=finishing. */
static std::atomic<int>  g_decoderOpStage{0};
static std::atomic<long> g_decoderOpSinceMs{0};
static std::atomic<long> g_decoderOpTid{0};
static long decoderNowMs() {
    return (long)std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
/* [V8.395] Bounded join: std::thread has no timed join. Hand the thread off to a
   waiter, wait with timeout. On timeout we return (so the caller releases the
   lifecycle lock) and abandon the waiter; the stuck thread is never touched again.
   Returns true if the thread exited. */
struct DecoderJoinState { std::mutex m; std::condition_variable cv; bool done = false; };
/* [V8.399] Sentinel returned when a MediaCodec call exceeds its timeout.
   The worker may still be inside the call, so callers must NOT release anything
   the worker could still touch (AMediaFormat, codec) -- leak instead. */
static const media_status_t kCodecTimeout = (media_status_t)-999;
struct CodecCallState { std::mutex m; std::condition_variable cv; bool done = false;
                        media_status_t res = (media_status_t)0; };
template <typename F>
static media_status_t codecCallTimed(const char* tag, int timeoutMs, F fn) {
    CodecCallState* st = new CodecCallState();
    std::thread th([st, fn]() {
        media_status_t r;
        try { r = fn(); } catch (...) { r = (media_status_t)-998; }
        { std::lock_guard<std::mutex> lk(st->m); st->res = r; st->done = true; }
        st->cv.notify_all();
    });
    bool ok = false;
    {
        std::unique_lock<std::mutex> lk(st->m);
        ok = st->cv.wait_for(lk, std::chrono::milliseconds(timeoutMs),
                             [st]() { return st->done; });
    }
    if (ok) {
        th.join();
        media_status_t r = st->res;
        delete st;
        return r;
    }
    LOGW("%s: call timed out (%dms) -> abandoned, codec state unknown", tag, timeoutMs);
    th.detach();   /* st is freed by the worker if it ever finishes */
    return kCodecTimeout;
}
static bool joinDecoderTimed(std::thread& t, const char* tag, int timeoutMs) {
    if (!t.joinable()) return true;
    std::thread* jt = new std::thread(std::move(t));   // t becomes empty, safe to reuse
    DecoderJoinState* st = new DecoderJoinState();
    std::thread waiter([jt, st]() {
        try { jt->join(); } catch (...) {}
        delete jt;
        { std::lock_guard<std::mutex> lk(st->m); st->done = true; }
        st->cv.notify_all();
    });
    bool ok = false;
    {
        std::unique_lock<std::mutex> lk(st->m);
        ok = st->cv.wait_for(lk, std::chrono::milliseconds(timeoutMs),
                             [st]() { return st->done; });
    }
    if (ok) {
        waiter.join();
        delete st;
        t = std::thread();
        return true;
    }
    LOGW("%s: decoder join timed out (%dms) -> thread abandoned (lock released)",
         tag, timeoutMs);
    waiter.detach();   // st/jt are freed by the waiter if it ever finishes
    t = std::thread();
    return false;
}



/* [V8.410] Void-returning timed NDK call (mirror of codecCallTimed for stop/flush/delete/
   setDataSourceFd/extractor_delete). Every blocking call inside openIncoming is now bounded so
   g_decoderLifecycleMutex can NEVER be held forever by a wedged codec — this alone kills the
   "连切三四首后彻底切不动" permanent wedge. Returns true if the call completed in time. */
template <typename F>
static bool codecCallVoid(const char* tag, int timeoutMs, F fn) {
    CodecCallState* st = new CodecCallState();
    std::thread th([st, fn]() {
        try { fn(); } catch (...) {}
        { std::lock_guard<std::mutex> lk(st->m); st->done = true; }
        st->cv.notify_all();
    });
    bool ok = false;
    {
        std::unique_lock<std::mutex> lk(st->m);
        ok = st->cv.wait_for(lk, std::chrono::milliseconds(timeoutMs), [st]() { return st->done; });
    }
    if (ok) { th.join(); delete st; return true; }
    LOGW("%s: call timed out (%dms) -> abandoned, codec state unknown", tag, timeoutMs);
    th.detach();
    return false;
}

/* [V8.410] Time-driven, INDEPENDENT decoder-open watchdog. The old recovery relied on the next
   cut "hitting the stall-check branch" to give up a round — it never healed a wedged codec on
   its own, so a stuck open could leave the stage register stale and bias every later cut. This
   thread proactively inspects the single open-operation stage register every 500ms; if an open
   overruns its budget it (a) POISONS the deck's codec so the next open recreates it fresh instead
   of poking a wedged instance, and (b) CLEARS the stale stage flag so future cuts aren't judged
   "previous open stuck". It does NOT delete the codec (a detached worker may still be inside it);
   recreation is delegated to the next open. */
static std::atomic<bool> g_decoderCodecPoisoned{false};    // A deck codec is wedged -> recreate
static std::atomic<bool> g_decoderCodecPoisonedB{false};   // B deck
static std::atomic<int>  g_decoderOpDeck{0};               // 0=none, 1=A, 2=B (which deck an open targets)
static std::atomic<bool> g_watchdogStarted{false};
static std::atomic<bool> g_watchdogStop{false};
static const long kDecoderWatchdogBudgetMs = 20000;        // > worst-case bounded-open window

static void decoderWatchdogLoop() {
    while (!g_watchdogStop.load()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
        int stage = g_decoderOpStage.load();
        if (stage == 0) continue;
        long held = decoderNowMs() - g_decoderOpSinceMs.load();
        if (held <= kDecoderWatchdogBudgetMs) continue;
        int deck = g_decoderOpDeck.load();
        LOGW("DecoderWatchdog: deck %c open overdue stage=%d held=%ldms -> force-abandon (poison codec, clear stage)",
             deck == 1 ? 'A' : (deck == 2 ? 'B' : '?'), stage, held);
        if (deck == 1) g_decoderCodecPoisoned.store(true);
        else if (deck == 2) g_decoderCodecPoisonedB.store(true);
        g_decoderOpStage.store(0);  // clear so future cuts aren't judged "previous open stuck"
    }
}
static void startDecoderWatchdog() {
    if (g_watchdogStarted.exchange(true)) return;
    std::thread th(decoderWatchdogLoop);
    th.detach();
    LOGI("DecoderWatchdog: started (budget=%ldms)", kDecoderWatchdogBudgetMs);
}

static std::vector<float> g_convertBuffer;



static std::atomic<bool> g_decoderIsFloat{false};  // 【V7.16】decoder output format



// 【V7.x】解码器实际输出编码（MediaFormat PCM_ENCODING）。AAC/M4A 的硬件解码器



// 即使 configure 请求 PCM16 也可能实际输出 PCM_FLOAT，必须按真实字节宽解析，



// 否则 float 字节被当 int16 错位读取 → 噪音/啸叫。



// 2=PCM_16BIT, 3=PCM_8BIT, 4=PCM_FLOAT, 21=PCM_24BIT_PACKED, 22=PCM_32BIT



static std::atomic<int> g_decoderOutputEncoding{2};



// 【V8.239 WAV 杂音根治】源文件真实 PCM 编码（来自 extractor 轨道格式，对 raw/WAV 可靠）。



// 与「按帧长反推」的启发式不同：16bit(2B) 与 32bit(4B) 的样本帧对 2ch 都是 8 字节、都能被



// 2·ch 整除，启发式无法区分二者，导致 32bit WAV 永远触发不了纠错、被当 16bit 读 → 持续「哒哒哒」。



// 从 extractor 直接拿真实位深，从第一帧就按正确字节宽读，并让解码循环优先信任它。



static std::atomic<int>  g_decoderSourceEnc{0};        // 0 = 未知（走启发式兜底，AAC float 等）



static std::atomic<bool> g_decoderHasSourceEnc{false};



static std::atomic<int>  g_decoderSourceEncB{0};



static std::atomic<bool> g_decoderHasSourceEncB{false};







// 从 extractor 轨道格式读源 PCM 编码（WAV/raw 可靠；AAC 等压缩格式无此字段 → 返回 0）



static int readSourcePcmEncoding(AMediaFormat* fmt) {



    int32_t e = 0;



    if (fmt && AMediaFormat_getInt32(fmt, "pcm-encoding", &e) && e > 0) return (int)e;



    return 0;



}







// Copy Codec Specific Data buffers (csd-0/csd-1) from source to dest format.



// Essential for AAC (M4A) codec initialization — AAC decoder cannot configure



// its stream parameters without AudioSpecificConfig from csd-0.



static void copyCsdBuffers(AMediaFormat* dest, AMediaFormat* src) {



    void* data = nullptr;



    size_t size = 0;



    // csd-0: decoder-specific configuration (AAC AudioSpecificConfig, Opus OpusHead, etc.)



    if (AMediaFormat_getBuffer(src, "csd-0", &data, &size) && data && size > 0) {



        AMediaFormat_setBuffer(dest, "csd-0", data, size);



        LOGI("Copied csd-0 (%zu bytes) from source format", size);



    }



    // csd-1: required for Opus (OpusTags header) and some Vorbis/MPEG-4 variants



    data = nullptr; size = 0;



    if (AMediaFormat_getBuffer(src, "csd-1", &data, &size) && data && size > 0) {



        AMediaFormat_setBuffer(dest, "csd-1", data, size);



        LOGI("Copied csd-1 (%zu bytes) from source format", size);



    }



    // csd-2: optional extra config (CodecDelay/SeekPreRoll for Opus in MP4, etc.)



    data = nullptr; size = 0;



    if (AMediaFormat_getBuffer(src, "csd-2", &data, &size) && data && size > 0) {



        AMediaFormat_setBuffer(dest, "csd-2", data, size);



        LOGI("Copied csd-2 (%zu bytes) from source format", size);



    }



    // max-input-size: important for some codecs (Opus, Vorbis)



    int32_t maxInputSize = 0;



    if (AMediaFormat_getInt32(src, AMEDIAFORMAT_KEY_MAX_INPUT_SIZE, &maxInputSize) && maxInputSize > 0) {



        AMediaFormat_setInt32(dest, AMEDIAFORMAT_KEY_MAX_INPUT_SIZE, maxInputSize);



        LOGI("Copied max-input-size: %d", maxInputSize);



    }



}







// 【V8.x】HE-AAC SBR 修复：解码器输出采样率变化时重开 Oboe stream



// 场景：HE-AAC(SBR) extractor 报核心采样率(如 22.05k)，解码器实际输出倍频(44.1k)



// stream 若按核心率打开，数据以错误速率消费 → 变速/啸叫



static bool reopenOutputStream(int newRate) {



    std::lock_guard<std::mutex> lock(g_streamMutex);



    int ch = g_channelCount.load();



    int devId = g_outputDeviceId.load();



    // 【2026-09-02 fix】swap 语义：先开新 stream，成功才关旧的。



    // 原实现先 close 旧 stream —— 失败时 g_outputStream=null → Oboe 回调停 → HE-AAC SBR m4a 无声。



    // 失败保留旧 stream + 1686-1705 重采样逻辑（aRate != streamRate）自动兜底。



    oboe::ManagedStream newStream;  // unique_ptr, 先开新 stream 成功才关旧



    oboe::AudioStreamBuilder builder;



    builder.setDirection(oboe::Direction::Output);



    builder.setPerformanceMode(oboe::PerformanceMode::PowerSaving);



    builder.setFormat(oboe::AudioFormat::Float);



    builder.setChannelCount(ch);



    builder.setCallback(&g_audioCallback);



    builder.setBufferCapacityInFrames(9600);



    if (devId > 0) builder.setDeviceId(devId);



    builder.setAudioApi(oboe::AudioApi::AAudio);



    builder.setSampleRate(newRate);



    builder.setSharingMode(oboe::SharingMode::Shared);



    oboe::Result result = builder.openManagedStream(newStream);



    if (result != oboe::Result::OK) {



        builder.setAudioApi(oboe::AudioApi::Unspecified);



        result = builder.openManagedStream(newStream);



    }



    if (result != oboe::Result::OK) {



        LOGE("reopenOutputStream failed: %s (keeping old stream, will resample)", oboe::convertToText(result));



        return false;



    }



    // 新 stream 开成功，替换旧的



    if (g_outputStream) { g_outputStream->close(); g_outputStream.reset(); }



    g_outputStream = std::move(newStream);



    g_outputStream->requestStart();



    int actualRate = g_outputStream->getSampleRate();



    int actualCh = g_outputStream->getChannelCount();



    g_sampleRate.store(actualRate);



    g_fileSampleRate.store(actualRate);



    g_channelCount.store(actualCh);



    {



        std::lock_guard<std::mutex> eqLock(g_eqMutex);



        g_eqBand3L.setHighShelf(actualRate, 15000.0f, -2.0f, 1.0f);



        g_eqBand3R.setHighShelf(actualRate, 15000.0f, -2.0f, 1.0f);



        g_eqBand3L.reset();



        g_eqBand3R.reset();



        g_limiter.setSampleRate(actualRate);



        g_limiter.reset();



        // 【V8.170】新模块同步采样率并清状态，避免流重建后残留旧系数的瞬态爆音



        g_mbLimiter.setSampleRate(actualRate);



        g_mbLimiter.reset();



        g_dynEq.setSampleRate(actualRate);



        g_dynEq.reset();



        g_spaceReverb.reset();



        g_agc.setSampleRate(actualRate);



        g_compressor.setSampleRate(actualRate);



        g_loudness.setSampleRate((float)actualRate);



        g_crossfeed.setSampleRate((float)actualRate);



        g_dts.setSampleRate((float)actualRate);



    }



    LOGI("reopenOutputStream: reopened at %d Hz / %d ch", actualRate, actualCh);



    return true;



}







// 【2026-09-02 ALAC】A 槽 ALAC 直解循环：同步 decodeNext → 重采样 → 写 g_ringBuffer



static void alacDecodeLoopA() {



    LOGI("ALAC decode loop A started");



    AlacDirect* al = g_alacA.get();



    if (!al) { LOGE("ALAC A: no engine"); g_decoderEos.store(true); g_decoderRunning.store(false); g_decoderThreadRunning.store(false); return; }



    const int ch = al->channels.load();



    const int fl = al->frameLen.load();



    const int srcRate = al->sampleRate.load();



    std::vector<float> frameBuf((size_t)fl * (ch > 0 ? ch : 2));



    // 重采样输出缓冲（复用共享的 g_resampleOutA）



    if (g_resampleOutA.size() < (size_t)fl * 8 + 1024) g_resampleOutA.resize((size_t)fl * 8 + 1024);







    while (!g_decoderStopRequested.load()) {



        if (g_decoderPaused.load()) {



            std::unique_lock<std::mutex> lock(g_decoderMutex);



            g_decoderCv.wait_for(lock, std::chrono::milliseconds(50), []() {



                return !g_decoderPaused.load() || g_decoderStopRequested.load();



            });



            continue;



        }



        int got = al->decodeNext(frameBuf.data());



        if (got < 0) {  // EOS



            g_decoderEos.store(true);



            LOGI("ALAC A: EOS, draining ring");



            while (!g_decoderStopRequested.load()) {



                std::this_thread::sleep_for(std::chrono::milliseconds(300));



                int avail = g_ringBuffer ? g_ringBuffer->available() : 0;



                if (avail <= 0) break;



                if (g_decoderPaused.load()) std::this_thread::sleep_for(std::chrono::milliseconds(50));



            }



            break;



        }



        if (got <= 0) {  // 坏包跳过或背压



            std::this_thread::sleep_for(std::chrono::milliseconds(2));



            continue;



        }



        int numSamples = got * ch;



        g_decoderPositionUs.store((al->framePos - got) * 1000000LL / (srcRate > 0 ? srcRate : 44100));







        // 重采样（与 ndkDecodeLoop 输出段一致）



        const float* writeSrc = frameBuf.data();



        int writeSamples = numSamples;



        {



            int streamRate = g_sampleRate.load();



            int aRate = srcRate;



            if (aRate > 0 && streamRate > 0 && aRate != streamRate && ch >= 1 && ch <= 8) {



                int outFrames = resampleLinearToStream(frameBuf.data(), numSamples, ch, aRate, streamRate,



                                                       g_resampleOutA, g_resamplePhaseA, g_resamplePrevA, g_resampleHistLenA, 8 * 64);



                if (outFrames > 0) {



                    writeSrc = g_resampleOutA.data();



                    writeSamples = outFrames * ch;



                    if (!g_resampleLoggedA) { LOGI("ALAC A: resampling %dHz -> %dHz", aRate, streamRate); g_resampleLoggedA = true; }



                }



            }



        }



        if (g_ringBuffer) {



            int written = 0, remain = writeSamples;



            const float* srcp = writeSrc;



            int retryCount = 0;



            while (remain > 0 && retryCount < 100) {



                int n = g_ringBuffer->write(srcp + written, remain, ringLandingReserve());



                written += n; remain -= n;



                if (remain > 0) { usleep(2000); retryCount++; }



            }



        }



        g_decoderFramesOutput.fetch_add(writeSamples / (ch > 0 ? ch : 1));



        if (g_ringBuffer && g_ringBuffer->available() > kRingBufferCapacity * 3 / 4) {



            usleep(5000);



        }



    }



    g_decoderRunning.store(false);



    g_decoderThreadRunning.store(false);



    LOGI("ALAC decode loop A ended");



}







// 【2026-09-02 ALAC】B 槽 ALAC 直解循环（incoming）



static void alacDecodeLoopB() {



    LOGI("ALAC decode loop B started");



    AlacDirect* al = g_alacB.get();



    if (!al) { LOGE("ALAC B: no engine"); g_decoderEosB.store(true); g_decoderRunningB.store(false); return; }



    const int ch = al->channels.load();



    const int fl = al->frameLen.load();



    const int srcRate = al->sampleRate.load();



    std::vector<float> frameBuf((size_t)fl * (ch > 0 ? ch : 2));



    if (g_resampleOutB.size() < (size_t)fl * 8 + 1024) g_resampleOutB.resize((size_t)fl * 8 + 1024);







    while (!g_decoderStopRequestedB.load()) {



        if (g_decoderPausedB.load()) {



            std::unique_lock<std::mutex> lock(g_decoderMutexB);



            g_decoderCvB.wait_for(lock, std::chrono::milliseconds(50), []() {



                return !g_decoderPausedB.load() || g_decoderStopRequestedB.load();



            });



            continue;



        }



        int got = al->decodeNext(frameBuf.data());



        if (got < 0) {



            g_decoderEosB.store(true);



            LOGI("ALAC B: EOS, draining ring");



            while (!g_decoderStopRequestedB.load()) {



                std::this_thread::sleep_for(std::chrono::milliseconds(300));



                int avail = g_ringBufferB ? g_ringBufferB->available() : 0;



                if (avail <= 0) break;



                if (g_decoderPausedB.load()) std::this_thread::sleep_for(std::chrono::milliseconds(50));



            }



            break;



        }



        if (got <= 0) { std::this_thread::sleep_for(std::chrono::milliseconds(2)); continue; }



        int numSamples = got * ch;



        g_decoderPositionB.store((al->framePos - got) * 1000000LL / (srcRate > 0 ? srcRate : 44100));



        const float* writeSrc = frameBuf.data();



        int writeSamples = numSamples;



        {



            int streamRate = g_sampleRate.load();



            int bRate = srcRate;



            if (bRate > 0 && streamRate > 0 && bRate != streamRate && ch >= 1 && ch <= 8) {



                int outFrames = resampleLinearToStream(frameBuf.data(), numSamples, ch, bRate, streamRate,



                                                       g_resampleOutB, g_resamplePhaseB, g_resamplePrevB, g_resampleHistLenB, 8 * 64);



                if (outFrames > 0) {



                    writeSrc = g_resampleOutB.data();



                    writeSamples = outFrames * ch;



                    if (!g_resampleLoggedB) { LOGI("ALAC B: resampling %dHz -> %dHz", bRate, streamRate); g_resampleLoggedB = true; }



                }



            }



        }



// 【V8.370】B 轨 8 段频谱：就地级联，跑在解码线程（非实时），与 A 轨完全隔离。

// writeSrc 已是重采样后的数据，正是即将进 ring 的内容。
{

    const int bch = (ch >= 2) ? 2 : 1;

    const int bframes = writeSamples / bch;

    if (bframes > 0) {

        if (!g_bandBFiltersInited.load(std::memory_order_relaxed)) {

            int bsr = g_sampleRate.load(); if (bsr <= 0) bsr = 44100;

            g_lpB60.setLowPass(bsr, 60.0f, 0.707f);

            g_lpB120.setLowPass(bsr, 120.0f, 0.707f);

            g_lpB250.setLowPass(bsr, 250.0f, 0.707f);

            g_lpB500.setLowPass(bsr, 500.0f, 0.707f);

            g_lpB2000.setLowPass(bsr, 2000.0f, 0.707f);

            g_lpB6000.setLowPass(bsr, 6000.0f, 0.707f);

            g_lpB12000.setLowPass(bsr, 12000.0f, 0.707f);

            g_bandBFiltersInited.store(true, std::memory_order_relaxed);

        }

        double e0=0,e1=0,e2=0,e3=0,e4=0,e5=0,e6=0,e7=0;

        for (int bk = 0; bk < bframes; bk++) {

            float bs = writeSrc[bk * bch];

            if (bch >= 2 && bk * bch + 1 < writeSamples)

                bs = (bs + writeSrc[bk * bch + 1]) * 0.5f;

            const float p60 = g_lpB60.process(bs);

            const float p120 = g_lpB120.process(bs);

            const float p250 = g_lpB250.process(bs);

            const float p500 = g_lpB500.process(bs);

            const float p2000 = g_lpB2000.process(bs);

            const float p6000 = g_lpB6000.process(bs);

            const float p12000 = g_lpB12000.process(bs);

            const float d0 = p60,            d1 = p120 - p60,   d2 = p250 - p120;

            const float d3 = p500 - p250,    d4 = p2000 - p500, d5 = p6000 - p2000;

            const float d6 = p12000 - p6000, d7 = bs - p12000;

            e0 += (double)d0*d0; e1 += (double)d1*d1; e2 += (double)d2*d2;

            e3 += (double)d3*d3; e4 += (double)d4*d4; e5 += (double)d5*d5;

            e6 += (double)d6*d6; e7 += (double)d7*d7;

        }

        const double binv = 1.0 / (double)bframes;

        auto bnorm = [&](double v) -> float {

            float x = (float)std::sqrt(v * binv);

            return x > 1.0f ? 1.0f : (x < 0.0f ? 0.0f : x);

        };

        g_bandB0.store(bnorm(e0), std::memory_order_relaxed);

        g_bandB1.store(bnorm(e1), std::memory_order_relaxed);

        g_bandB2.store(bnorm(e2), std::memory_order_relaxed);

        g_bandB3.store(bnorm(e3), std::memory_order_relaxed);

        g_bandB4.store(bnorm(e4), std::memory_order_relaxed);

        g_bandB5.store(bnorm(e5), std::memory_order_relaxed);

        g_bandB6.store(bnorm(e6), std::memory_order_relaxed);

        g_bandB7.store(bnorm(e7), std::memory_order_relaxed);

    }

}

        if (g_ringBufferB) {



            int written = 0, remain = writeSamples;



            const float* srcp = writeSrc;



            int retryCount = 0;



            while (remain > 0 && retryCount < 100) {



                int n = g_ringBufferB->write(srcp + written, remain, ringLandingReserve());



                written += n; remain -= n;



                if (remain > 0) { usleep(2000); retryCount++; }



            }



        }



        if (g_ringBufferB && g_ringBufferB->available() > kRingBufferCapacity * 3 / 4) {



            usleep(5000);



        }



    }



    g_decoderRunningB.store(false);



    LOGI("ALAC decode loop B ended");



}







static void ndkDecodeLoop() {



    LOGI("NDK decode loop started");



    int inputEos = 0;







    while (!g_decoderStopRequested.load()) {



        if (g_decoderPaused.load()) {



            std::unique_lock<std::mutex> lock(g_decoderMutex);



            auto pred = [&]() -> bool {



                return !g_decoderPaused.load() || g_decoderStopRequested.load();



            };



            g_decoderCv.wait_for(lock, std::chrono::milliseconds(50), pred);



            continue;



        }







        bool hadWork = false;







        // Feed input



        // 【V8.202 诊断】thread_local 保证每条解码线程各打一次，不会刷屏也不会漏看



        static thread_local bool dbgInLogged = false;



        static thread_local bool dbgOutLogged = false;



        if (!inputEos && g_decoderCodec != nullptr) {



            ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(g_decoderCodec, 5000);



            if (inputIndex < 0 && !dbgInLogged) {



                dbgInLogged = true;



                LOGI("NDK Decoder: dequeueInput FAILED first time ret=%zd", inputIndex);



            }



            if (inputIndex >= 0) {



                hadWork = true;



                size_t inputSize = 0;



                uint8_t* inputBuf = AMediaCodec_getInputBuffer(g_decoderCodec, inputIndex, &inputSize);



                if (inputBuf) {



                    ssize_t sampleSize = AMediaExtractor_readSampleData(g_decoderExtractor, inputBuf, inputSize);



                    if (sampleSize <= 0) {



                        AMediaCodec_queueInputBuffer(g_decoderCodec, inputIndex, 0, 0, 0,



                                                     AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);



                        inputEos = 1;



                        LOGI("NDK Decoder: input EOS");



                    } else {



                        int64_t timeUs = AMediaExtractor_getSampleTime(g_decoderExtractor);



                        g_decoderPositionUs.store(timeUs);



                        AMediaCodec_queueInputBuffer(g_decoderCodec, inputIndex, 0, sampleSize, timeUs, 0);



                        AMediaExtractor_advance(g_decoderExtractor);



                    }



                }



            }



        }







        // Drain output



        if (!g_decoderCodec) continue;  // safety: codec was deleted by stop/open



        AMediaCodecBufferInfo info;



        ssize_t outputIndex = AMediaCodec_dequeueOutputBuffer(g_decoderCodec, &info, 5000);



        if (outputIndex < 0 && !dbgOutLogged) {



            dbgOutLogged = true;



            LOGI("NDK Decoder: dequeueOutput FAILED first time ret=%zd", outputIndex);



        }



        if (outputIndex >= 0) {



            hadWork = true;



            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {



                // 【V7.46 关键修复】Decoder输出EOS后不要立即break！



                // 必须等RingBuffer被Oboe回调全部消费完，否则歌曲"提早结束"



                // EOS=输出端空了，但RingBuffer里可能还有几秒数据



                AMediaCodec_releaseOutputBuffer(g_decoderCodec, outputIndex, false);



                g_decoderEos.store(true);



                LOGI("NDK Decoder: output EOS → entering drain phase (waiting for RingBuffer empty)");



                // Drain阶段：等g_ringBuffer彻底空了，Oboe回调会把它消费完



                while (!g_decoderStopRequested.load()) {



                    std::this_thread::sleep_for(std::chrono::milliseconds(300));



                    int avail = g_ringBuffer ? g_ringBuffer->available() : 0;



                    if (avail <= 0) {



                        LOGI("NDK Decoder: drain complete, RingBuffer empty, exiting loop");



                        break;



                    }



                    if (g_decoderPaused.load()) {



                        std::this_thread::sleep_for(std::chrono::milliseconds(50));



                    }



                }



                break;



            }



            size_t outputSize = 0;



            uint8_t* outputBuf = AMediaCodec_getOutputBuffer(g_decoderCodec, outputIndex, &outputSize);



            if (outputBuf && info.size > 0) {



                uint8_t* src = outputBuf + info.offset;



                int enc = g_decoderOutputEncoding.load();



                // 【V8.x】支持全部 PCM 编码：4=FLOAT(4B) 2=PCM16(2B) 21=24bit packed(3B) 22=32bit int(4B)



                // 【V8.207 WAV 杂音修复】解码器实际输出的编码不一定是我们 configure 时请求的那个：



                // Android 的 raw(WAV) decoder 常按源文件位深直出（24bit WAV → 24bit packed），



                // 且不保证发 OUTPUT_FORMAT_CHANGED。于是我们一直按默认的 PCM16 去切帧，



                // 3 字节的样本被当成 2 字节读 —— 听感就是持续的「哒哒哒」电子杂音。



                // 判据很硬：MediaCodec 输出的必定是整数帧，若按当前假设的位深除不尽，就是假设错了。



                {



                    int ch = g_channelCount.load();



                    int newEnc = enc;



                    if (g_decoderHasSourceEnc.load()) {



                        // 【V8.239】源编码（来自 extractor，raw/WAV 可靠）优先：即便被探测覆盖回 2，



                        // 只要帧长能被源字节宽整除就立即用源编码。这彻底解决了 16bit 与 32bit 帧长



                        // 同为 2·ch 的整数倍、启发式无法区分、导致 32bit WAV 被当 16bit 读的「哒哒哒」。



                        int se = g_decoderSourceEnc.load();



                        int sbps = (se == 4 || se == 22) ? 4 : (se == 21 ? 3 : 2);



                        if (se != enc && sbps > 0 && ch > 0 && (info.size % (sbps * ch)) == 0) {



                            newEnc = se;



                        }



                    } else if (ch > 0) {



                        // 【V8.207】无源编码时（AAC float 等）按帧长反推：解码器输出必为整数帧，



                        // 若按当前假设的位深除不尽就是假设错了。



                        int bpsNow = (enc == 4 || enc == 22) ? 4 : (enc == 21 ? 3 : 2);



                        if (bpsNow > 0 && (info.size % (bpsNow * ch)) != 0) {



                            // 先问 codec 自己要一次真实输出格式



                            AMediaFormat* of = AMediaCodec_getOutputFormat(g_decoderCodec);



                            if (of) {



                                int32_t e = 0;



                                if (AMediaFormat_getInt32(of, "pcm-encoding", &e) && e > 0) newEnc = e;



                                AMediaFormat_delete(of);



                            }



                            int nb = (newEnc == 4 || newEnc == 22) ? 4 : (newEnc == 21 ? 3 : 2);



                            if ((info.size % (nb * ch)) != 0) {



                                // 格式里没有 pcm-encoding：按帧长反推（高码率 WAV 最常见 24bit packed）



                                if ((info.size % (3 * ch)) == 0) newEnc = 21;



                                else if ((info.size % (4 * ch)) == 0) newEnc = 22;



                            }



                        }



                    }



                    if (newEnc != enc) {



                        LOGI("NDK Decoder A: PCM encoding corrected %d -> %d (info.size=%d ch=%d)",



                             enc, newEnc, info.size, ch);



                        g_decoderOutputEncoding.store(newEnc);



                        g_decoderIsFloat.store(newEnc == 4 || newEnc == 22);



                        enc = newEnc;



                    }



                }



                int bytesPerSample = (enc == 4 || enc == 22) ? 4 : (enc == 21 ? 3 : 2);



                int numSamples = (int)(info.size / bytesPerSample);



                // 【V8.207】首帧诊断：杂音排查时看这一行就知道解码器到底吐的是什么



                {



                    static thread_local bool dbgEncLogged = false;



                    if (!dbgEncLogged) {



                        dbgEncLogged = true;



                        LOGI("NDK Decoder A: first output size=%d ch=%d enc=%d bps=%d samples=%d",



                             info.size, g_channelCount.load(), enc, bytesPerSample, numSamples);



                    }



                }







                // 【V7.27】记录文件真实采样率



                if (g_fileSampleRate.load() == 0) {



                    AMediaFormat* outFormat = AMediaCodec_getOutputFormat(g_decoderCodec);



                    if (outFormat) {



                        int32_t outSr = 0;



                        AMediaFormat_getInt32(outFormat, AMEDIAFORMAT_KEY_SAMPLE_RATE, &outSr);



                        if (outSr > 0) g_fileSampleRate.store(outSr);



                        AMediaFormat_delete(outFormat);



                    }



                }







                if (g_convertBuffer.size() < (size_t)numSamples) {



                    g_convertBuffer.resize(numSamples + 1024);



                }



                if (enc == 4) {



                    // PCM_FLOAT：直接拷贝，无需缩放



                    const float* f32 = reinterpret_cast<const float*>(src);



                    for (int i = 0; i < numSamples; i++) g_convertBuffer[i] = f32[i];



                } else if (enc == 21) {



                    // 【V8.x】24-bit packed：3 字节/样本，little-endian，符号扩展后归一化



                    const uint8_t* b = src;



                    for (int i = 0; i < numSamples; i++) {



                        int32_t v = (int32_t)b[0] | ((int32_t)b[1] << 8) | ((int32_t)b[2] << 16);



                        if (v & 0x800000) v |= 0xFF000000;  // 符号扩展 24→32



                        g_convertBuffer[i] = static_cast<float>(v) / 8388608.0f;  // 2^23



                        b += 3;



                    }



                } else if (enc == 22) {



                    // 【V8.x】32-bit 整型 PCM：4 字节/样本，归一化到 2^31



                    const int32_t* pcm32 = reinterpret_cast<const int32_t*>(src);



                    for (int i = 0; i < numSamples; i++) {



                        g_convertBuffer[i] = static_cast<float>(pcm32[i]) / 2147483648.0f;



                    }



                } else {



                    // PCM_16BIT（enc==2 或未知整数编码，按 16bit 读）



                    const int16_t* pcm16 = reinterpret_cast<const int16_t*>(src);



                    for (int i = 0; i < numSamples; i++) {



                        g_convertBuffer[i] = static_cast<float>(pcm16[i]) / 32768.0f;



                    }



                }



                // 【Crossfade 重采样】A 轨采样率 != stream 率时重采样（incoming 和 active 都适用）。



                // 不能用 g_activeIsB 当守卫：crossfade 完成后 A 轨变 active 但 decode 循环继续跑，



                // 此时若不再重采样，48k 数据被 44.1k stream 快放 → 变调（V8.3 修复）。



                const float* writeSrc = g_convertBuffer.data();



                int writeSamples = numSamples;



                {



                    int streamRate = g_sampleRate.load();



                    int aRate = g_sampleRateA.load();



                    int chA = g_channelCount.load();



                    if (aRate > 0 && streamRate > 0 && aRate != streamRate && chA >= 1 && chA <= 8) {



                        int outFrames = resampleLinearToStream(g_convertBuffer.data(), numSamples, chA,



                                                               aRate, streamRate,



                                                               g_resampleOutA,



                                                               g_resamplePhaseA, g_resamplePrevA, g_resampleHistLenA, 8 * 64);



                        if (outFrames > 0) {



                            writeSrc = g_resampleOutA.data();



                            writeSamples = outFrames * chA;



                            if (!g_resampleLoggedA) { LOGI("NDK Decoder A: resampling %dHz -> %dHz", aRate, streamRate); g_resampleLoggedA = true; }



                        }



                    }



                }



                // 【V8.247 修复·哒哒哒根因】写入 RingBuffer：活跃轨绝不丢数据。



                // 本槽位是活跃轨（A 上位）或交叉淡化进行中时，缓冲满 = 解码领先于播放，



                // 属正常背压，实时消费必然腾出空间 → 无限等待（60s 硬上限兜底防死等）。



                // 旧逻辑等 100 次（200ms）就丢弃 → 活跃轨缓冲一满就周期性丢真实播放数据



                // （每次 ~27ms），听感即「哒哒」声；只有 DJ 过渡上位的歌中招（A 环非过渡路径不触发）。



                // 槽位未被消费（预加载的候选曲作废）时才维持旧的放弃行为。



                if (g_ringBuffer) {



                    int written = 0;



                    int remain = writeSamples;



                    const float* src = writeSrc;



                    int retryCount = 0;



                    const bool aMustDeliver = !g_activeIsB.load(std::memory_order_relaxed) || g_crossfadeActive.load(std::memory_order_relaxed);
                    // [djay-style·V8.402b] 解码器永不丢已解出 PCM：ring 满时阻塞等待回调消费，
                    // 仅在明确要求停止时退出。落点后出歌环不再被消费 -> 解码器在此休眠（不丢样本），
                    // 待 g_decoderStopRequested 置位即干净退出，不挂死 join。
                    while (remain > 0 && retryCount < 30000) {
                        if (g_decoderStopRequested.load(std::memory_order_relaxed)) break;
                        int n = g_ringBuffer->write(src + written, remain, ringLandingReserve());
                        written += n;
                        remain -= n;
                        if (remain > 0) usleep(2000);  // 2ms — Oboe 消费约 176 samples
                        retryCount++;
                    }
                    if (remain > 0) {
                        LOGW("RingBuffer write: aborted %d/%d samples (stop requested)", remain, writeSamples);
                    }



                }



                g_decoderFramesOutput.fetch_add(writeSamples / g_channelCount.load());  // 【V7.16】







                // Back-pressure: sleep if buffer is mostly full



                if (g_ringBuffer && g_ringBuffer->available() > kRingBufferCapacity * 3 / 4) {



                    usleep(20000);  // 【V8.247】5ms→20ms：少贴满沿空转



                }



            }



            AMediaCodec_releaseOutputBuffer(g_decoderCodec, outputIndex, false);



        } else if (outputIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {



            AMediaFormat* format = AMediaCodec_getOutputFormat(g_decoderCodec);



            const char* str = AMediaFormat_toString(format);



            LOGI("NDK Decoder: output format changed: %s", str ? str : "null");



            // 【V7.x】读取解码器实际输出编码 + 声道数 + 采样率



            if (format) {



                int32_t enc = 0;



                if (AMediaFormat_getInt32(format, "pcm-encoding", &enc) && enc > 0) {



                    g_decoderOutputEncoding.store(enc);



                    g_decoderIsFloat.store(enc == 4 || enc == 22);



                    LOGI("NDK Decoder: actual PCM encoding=%d (%s)", enc,



                         enc == 4 ? "FLOAT" : enc == 2 ? "16BIT" : enc == 21 ? "24BIT_PACKED" : "OTHER");



                }



                int32_t ch = 0, sr = 0;



                if (AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch) && ch > 0) {



                    g_channelCount.store(ch);



                }



                if (AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr) && sr > 0) {



                    g_sampleRateA.store(sr);  // 【Crossfade 重采样】记录 A 槽解码采样率



                    if (g_activeIsB.load()) {



                        // 【Crossfade 重采样】A 槽作为 incoming：不 reopen stream（会打断 active 轨），



                        // 写 ring 时重采样到 stream 率即可



                    } else {



                        int currentStreamRate = 0;



                        {



                            std::lock_guard<std::mutex> lock(g_streamMutex);



                            if (g_outputStream) currentStreamRate = g_outputStream->getSampleRate();



                        }



                        // 【V8.486 抗混叠】仅当新轨采样率更高且 A 为稳定活跃轨（非 crossfade 中的 incoming）时上调流率；
                        // 否则保持当前流率，本轨由重采样器上采样到流率（上采样无混叠，根除下采样白噪音）。
                        int curStream = (currentStreamRate > 0) ? currentStreamRate : 48000;
                        bool canRatchet = !g_crossfadeActive.load(std::memory_order_relaxed)
                                                 && !g_activeIsB.load(std::memory_order_relaxed);
                        if (currentStreamRate > 0 && sr > currentStreamRate && canRatchet) {



                            LOGI("NDK Decoder: rate UP %d -> %d, reopen stream (ratchet up)", currentStreamRate, sr);



                            if (g_ringBuffer) g_ringBuffer->clear();



                            reopenOutputStream(sr);



                        } else {



                            g_fileSampleRate.store(sr);



                            g_sampleRate.store(curStream);



                        }



                    }



                }



                AMediaFormat_delete(format);



            }



        }



        // Adaptive sleep: skip when busy, rest longer when idle (V7.48 CPU optimization)



        if (!hadWork) {



            usleep(5000);  // 5ms idle — saves CPU vs old 500μs



        }



    }



    g_decoderRunning.store(false);



    g_decoderThreadRunning.store(false);  // 【V7.16】



    LOGI("NDK decode loop ended");



}







// 【Crossfade】B 轨解码循环（incoming，与 ndkDecodeLoop 镜像，但不碰共享采样率/声道/stream）



static void ndkDecodeLoopB() {



    LOGI("NDK decode loop B started");



    int inputEos = 0;







    while (!g_decoderStopRequestedB.load()) {



        if (g_decoderPausedB.load()) {



            std::unique_lock<std::mutex> lock(g_decoderMutexB);



            auto pred = [&]() -> bool {



                return !g_decoderPausedB.load() || g_decoderStopRequestedB.load();



            };



            g_decoderCvB.wait_for(lock, std::chrono::milliseconds(50), pred);



            continue;



        }







        bool hadWork = false;







        // Feed input



        if (!inputEos && g_decoderCodecB != nullptr) {



            ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(g_decoderCodecB, 5000);



            if (inputIndex >= 0) {



                hadWork = true;



                size_t inputSize = 0;



                uint8_t* inputBuf = AMediaCodec_getInputBuffer(g_decoderCodecB, inputIndex, &inputSize);



                if (inputBuf) {



                    ssize_t sampleSize = AMediaExtractor_readSampleData(g_decoderExtractorB, inputBuf, inputSize);



                    if (sampleSize <= 0) {



                        AMediaCodec_queueInputBuffer(g_decoderCodecB, inputIndex, 0, 0, 0,



                                                     AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);



                        inputEos = 1;



                        LOGI("NDK Decoder: input EOS");



                    } else {



                        int64_t timeUs = AMediaExtractor_getSampleTime(g_decoderExtractorB);



                        g_decoderPositionB.store(timeUs);



                        AMediaCodec_queueInputBuffer(g_decoderCodecB, inputIndex, 0, sampleSize, timeUs, 0);



                        AMediaExtractor_advance(g_decoderExtractorB);



                    }



                }



            }



        }







        // Drain output



        if (!g_decoderCodecB) continue;  // safety: codec was deleted by stop/open



        AMediaCodecBufferInfo info;



        ssize_t outputIndex = AMediaCodec_dequeueOutputBuffer(g_decoderCodecB, &info, 5000);



        if (outputIndex >= 0) {



            hadWork = true;



            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {



                // 【V7.46 关键修复】Decoder输出EOS后不要立即break！



                // 必须等RingBuffer被Oboe回调全部消费完，否则歌曲"提早结束"



                AMediaCodec_releaseOutputBuffer(g_decoderCodecB, outputIndex, false);



                g_decoderEosB.store(true);



                LOGI("NDK Decoder: output EOS → entering drain phase (waiting for RingBuffer empty)");



                while (!g_decoderStopRequestedB.load()) {



                    std::this_thread::sleep_for(std::chrono::milliseconds(300));



                    int avail = g_ringBufferB ? g_ringBufferB->available() : 0;



                    if (avail <= 0) {



                        LOGI("NDK Decoder: drain complete, RingBuffer empty, exiting loop");



                        break;



                    }



                    if (g_decoderPausedB.load()) {



                        std::this_thread::sleep_for(std::chrono::milliseconds(50));



                    }



                }



                break;



            }



            size_t outputSize = 0;



            uint8_t* outputBuf = AMediaCodec_getOutputBuffer(g_decoderCodecB, outputIndex, &outputSize);



            if (outputBuf && info.size > 0) {



                uint8_t* src = outputBuf + info.offset;



                int enc = g_decoderOutputEncodingB.load();



                // 【V8.207 WAV 杂音修复】与 A 轨同样的编码自纠；【V8.239】优先信任 extractor 源编码，



                // 根绝 16bit/32bit 帧长同为 2·ch 整数倍、启发式无法区分、32bit WAV 被当 16bit 读的「哒哒哒」。



                {



                    int ch = g_channelCount.load();



                    int newEnc = enc;



                    if (g_decoderHasSourceEncB.load()) {



                        int se = g_decoderSourceEncB.load();



                        int sbps = (se == 4 || se == 22) ? 4 : (se == 21 ? 3 : 2);



                        if (se != enc && sbps > 0 && ch > 0 && (info.size % (sbps * ch)) == 0) {



                            newEnc = se;



                        }



                    } else if (ch > 0) {



                        int bpsNow = (enc == 4 || enc == 22) ? 4 : (enc == 21 ? 3 : 2);



                        if (bpsNow > 0 && (info.size % (bpsNow * ch)) != 0) {



                            AMediaFormat* of = AMediaCodec_getOutputFormat(g_decoderCodecB);



                            if (of) {



                                int32_t e = 0;



                                if (AMediaFormat_getInt32(of, "pcm-encoding", &e) && e > 0) newEnc = e;



                                AMediaFormat_delete(of);



                            }



                            int nb = (newEnc == 4 || newEnc == 22) ? 4 : (newEnc == 21 ? 3 : 2);



                            if ((info.size % (nb * ch)) != 0) {



                                if ((info.size % (3 * ch)) == 0) newEnc = 21;



                                else if ((info.size % (4 * ch)) == 0) newEnc = 22;



                            }



                        }



                    }



                    if (newEnc != enc) {



                        LOGI("NDK Decoder B: PCM encoding corrected %d -> %d (info.size=%d ch=%d)",



                             enc, newEnc, info.size, ch);



                        g_decoderOutputEncodingB.store(newEnc);



                        g_decoderIsFloatB.store(newEnc == 4 || newEnc == 22);



                        enc = newEnc;



                    }



                }



                // 【V8.x】支持全部 PCM 编码：4=FLOAT(4B) 2=PCM16(2B) 21=24bit packed(3B) 22=32bit int(4B)



                int bytesPerSample = (enc == 4 || enc == 22) ? 4 : (enc == 21 ? 3 : 2);



                int numSamples = (int)(info.size / bytesPerSample);



                {



                    static thread_local bool dbgEncLoggedB = false;



                    if (!dbgEncLoggedB) {



                        dbgEncLoggedB = true;



                        LOGI("NDK Decoder B: first output size=%d ch=%d enc=%d bps=%d samples=%d",



                             info.size, g_channelCount.load(), enc, bytesPerSample, numSamples);



                    }



                }







                // 【Crossfade】B 轨不写共享 g_fileSampleRate（避免污染 A 轨）







                if (g_convertBufferB.size() < (size_t)numSamples) {



                    g_convertBufferB.resize(numSamples + 1024);



                }



                if (enc == 4) {



                    const float* f32 = reinterpret_cast<const float*>(src);



                    for (int i = 0; i < numSamples; i++) g_convertBufferB[i] = f32[i];



                } else if (enc == 21) {



                    const uint8_t* b = src;



                    for (int i = 0; i < numSamples; i++) {



                        int32_t v = (int32_t)b[0] | ((int32_t)b[1] << 8) | ((int32_t)b[2] << 16);



                        if (v & 0x800000) v |= 0xFF000000;  // 符号扩展 24→32



                        g_convertBufferB[i] = static_cast<float>(v) / 8388608.0f;  // 2^23



                        b += 3;



                    }



                } else if (enc == 22) {



                    const int32_t* pcm32 = reinterpret_cast<const int32_t*>(src);



                    for (int i = 0; i < numSamples; i++) {



                        g_convertBufferB[i] = static_cast<float>(pcm32[i]) / 2147483648.0f;



                    }



                } else {



                    const int16_t* pcm16 = reinterpret_cast<const int16_t*>(src);



                    for (int i = 0; i < numSamples; i++) {



                        g_convertBufferB[i] = static_cast<float>(pcm16[i]) / 32768.0f;



                    }



                }



                // 【Crossfade 重采样】B 轨采样率 != stream 采样率时，线性重采样到 stream 率（否则变调）



                const float* writeSrc = g_convertBufferB.data();



                int writeSamples = numSamples;



                int streamRate = g_sampleRate.load();



                int bRate = g_sampleRateB.load();



                int chB = g_channelCountB.load();



                if (bRate > 0 && streamRate > 0 && bRate != streamRate && chB >= 1 && chB <= 8) {



                    int outFrames = resampleLinearToStream(g_convertBufferB.data(), numSamples, chB,



                                                           bRate, streamRate,



                                                           g_resampleOutB,



                                                           g_resamplePhaseB, g_resamplePrevB, g_resampleHistLenB, 8 * 64);



                    if (outFrames > 0) {



                        writeSrc = g_resampleOutB.data();



                        writeSamples = outFrames * chB;



                        if (!g_resampleLoggedB) { LOGI("NDK Decoder B: resampling %dHz -> %dHz", bRate, streamRate); g_resampleLoggedB = true; }



                    }



                }



                // 【V8.247 修复·哒哒哒根因】同 A 环：B 轨上位（crossfade 完成后即为活跃轨）



                // 或过渡中时绝不丢样本，无限等待（60s 兜底）；仅放弃的预加载候选允许丢弃。



                if (g_ringBufferB) {



                    int written = 0;



                    int remain = writeSamples;



                    const float* src2 = writeSrc;



                    int retryCount = 0;



                    const bool bMustDeliver = g_activeIsB.load(std::memory_order_relaxed) || g_crossfadeActive.load(std::memory_order_relaxed);
                    // [djay-style·V8.402b] 进歌前预载窗口里 incoming 环不被消费 -> 解码器在此休眠
                    // （不丢样本、也不抢跑文件读指针）；武装/落点后回调开始消费即自动续写，B 进歌
                    // 起点恒为 bStartMs，消除过渡开头的跳帧/断层。stop 置位即退出。
                    while (remain > 0 && retryCount < 30000) {
                        if (g_decoderStopRequestedB.load(std::memory_order_relaxed)) break;
                        int n = g_ringBufferB->write(src2 + written, remain, ringLandingReserve());
                        written += n;
                        remain -= n;
                        if (remain > 0) usleep(2000);
                        retryCount++;
                    }
                    if (remain > 0) {
                        LOGW("RingBuffer write: aborted %d/%d samples (stop requested)", remain, writeSamples);
                    }



                }



                g_decoderFramesOutputB.fetch_add(writeSamples / g_channelCount.load());  // 【V7.16】







                if (g_ringBufferB && g_ringBufferB->available() > kRingBufferCapacity * 3 / 4) {



                    usleep(20000);  // 【V8.247】5ms→20ms：少贴满沿空转



                }



            }



            AMediaCodec_releaseOutputBuffer(g_decoderCodecB, outputIndex, false);



        } else if (outputIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {



            AMediaFormat* format = AMediaCodec_getOutputFormat(g_decoderCodecB);



            const char* str = AMediaFormat_toString(format);



            LOGI("NDK Decoder: output format changed: %s", str ? str : "null");



            if (format) {



                int32_t enc = 0;



                if (AMediaFormat_getInt32(format, "pcm-encoding", &enc) && enc > 0) {



                    g_decoderOutputEncodingB.store(enc);



                    LOGI("NDK Decoder: actual PCM encoding=%d (%s)", enc,



                         enc == 4 ? "FLOAT" : enc == 2 ? "16BIT" : enc == 21 ? "24BIT_PACKED" : "OTHER");



                }



                int32_t srB = 0, chB2 = 0;



                if (AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &srB) && srB > 0) {



                    g_sampleRateB.store(srB);  // 【Crossfade 重采样】记录 B 轨解码采样率



                }



                if (AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &chB2) && chB2 > 0) {



                    g_channelCountB.store(chB2);  // 【V8.3】B 轨解码声道数（重采样必须用 B 自己的）



                }



                // 【Crossfade】B 轨不写共享 g_channelCount/g_sampleRate、不 reopen stream



                AMediaFormat_delete(format);



            }



        }



        if (!hadWork) {



            usleep(5000);



        }



    }



    g_decoderRunningB.store(false);



    g_decoderThreadRunningB.store(false);  // 【V7.16】



    LOGI("NDK decode loop B ended");



}







// ============================================================================



// JNI Bridge Functions — extern "C"



// ============================================================================



extern "C" {







// ============================================================



// Sink EQ JNI — 5-band equalizer control (OboeAudioSink)



// Band mapping matches Android Equalizer: 60Hz/230Hz/910Hz/3.6kHz/14kHz



// ============================================================







JNIEXPORT void JNICALL Java_com_sdw_music_player_OboeAudioSink_nativeSetSinkEqEnabled(JNIEnv *env, jclass clazz, jboolean enabled) {



    std::lock_guard<std::mutex> lock(g_sinkEqMutex);



    g_sinkEqEnabled.store(enabled, std::memory_order_release);



    LOGI("Sink EQ: %s", enabled ? "ENABLED" : "DISABLED");



}







JNIEXPORT jboolean JNICALL Java_com_sdw_music_player_OboeAudioSink_nativeIsSinkEqEnabled(JNIEnv *env, jclass clazz) {



    return g_sinkEqEnabled.load(std::memory_order_acquire);



}







JNIEXPORT void JNICALL Java_com_sdw_music_player_OboeAudioSink_nativeSetSinkEqBand(JNIEnv *env, jclass clazz,



        jint bandIndex, jfloat freqHz, jfloat gainDb, jfloat q) {



    std::lock_guard<std::mutex> lock(g_sinkEqMutex);



    float sr = static_cast<float>(g_sinkSampleRate.load());



    if (sr <= 0) return;



    BiquadFilter* bandL = nullptr;



    BiquadFilter* bandR = nullptr;



    switch (bandIndex) {



        case 0: bandL = &g_sinkEqB1L; bandR = &g_sinkEqB1R; break;



        case 1: bandL = &g_sinkEqB2L; bandR = &g_sinkEqB2R; break;



        case 2: bandL = &g_sinkEqB3L; bandR = &g_sinkEqB3R; break;



        case 3: bandL = &g_sinkEqB4L; bandR = &g_sinkEqB4R; break;



        case 4: bandL = &g_sinkEqB5L; bandR = &g_sinkEqB5R; break;



        default: return;



    }



    bandL->reset(); bandR->reset();



    if (fabsf(gainDb) < 0.1f) { bandL->setFlat(); bandR->setFlat(); return; }



    bandL->setPeaking(sr, freqHz, gainDb, q);



    bandR->setPeaking(sr, freqHz, gainDb, q);



    LOGI("Sink EQ Band %d: %.0fHz %.1fdB Q=%.2f", bandIndex, freqHz, gainDb, q);



}







JNIEXPORT void JNICALL Java_com_sdw_music_player_OboeAudioSink_nativeSetSinkEqAllBands(JNIEnv *env, jclass clazz,



        jfloatArray gainsDb, jfloatArray freqsHz) {



    std::lock_guard<std::mutex> lock(g_sinkEqMutex);



    float sr = static_cast<float>(g_sinkSampleRate.load());



    if (sr <= 0) return;



    jfloat* gains = env->GetFloatArrayElements(gainsDb, nullptr);



    jfloat* freqs = freqsHz ? env->GetFloatArrayElements(freqsHz, nullptr) : nullptr;



    jsize len = env->GetArrayLength(gainsDb);



    const float defaultFreqs[5] = {60.0f, 230.0f, 910.0f, 3600.0f, 14000.0f};



    struct { BiquadFilter* L; BiquadFilter* R; } bands[5] = {



        {&g_sinkEqB1L, &g_sinkEqB1R}, {&g_sinkEqB2L, &g_sinkEqB2R},



        {&g_sinkEqB3L, &g_sinkEqB3R}, {&g_sinkEqB4L, &g_sinkEqB4R},



        {&g_sinkEqB5L, &g_sinkEqB5R}



    };



    for (int i = 0; i < len && i < 5; i++) {



        float gainDb = gains[i];  // already in dB (Kotlin side converts millibels→dB)



        float freqHz = freqs ? freqs[i] : defaultFreqs[i];



        float Q = 1.0f;



        LOGI("Sink EQ AllBands[%d]: %.0fHz gain=%.1fdB sr=%.0f", i, freqHz, gainDb, sr);  // Standard graphic EQ Q



        bands[i].L->reset(); bands[i].R->reset();



        if (fabsf(gainDb) < 0.1f) { bands[i].L->setFlat(); bands[i].R->setFlat(); }



        else { bands[i].L->setPeaking(sr, freqHz, gainDb, Q); bands[i].R->setPeaking(sr, freqHz, gainDb, Q); }



    }



    env->ReleaseFloatArrayElements(gainsDb, gains, 0);



    if (freqs) env->ReleaseFloatArrayElements(freqsHz, freqs, 0);



    LOGI("Sink EQ: all 5 bands updated");



}







// --- nativeOpen (path-based) ---



JNIEXPORT jboolean JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeOpen(JNIEnv *env, jobject thiz,



                                                   jstring filePath) {



    std::lock_guard<std::timed_mutex> lifecycleLock(g_decoderLifecycleMutex);



    const char *path = env->GetStringUTFChars(filePath, nullptr);



    if (!path) return false;



    LOGI("OboeDirectPlayer: opening %s", path);







    // 【Crossfade】开新曲（A 轨硬加载）时复位 crossfade 状态并停 B 轨



    g_crossfadeActive.store(false);



    g_activeIsB.store(false);



    g_crossfadePos.store(0.0f);



    g_decoderStopRequestedB.store(true);



    g_decoderCvB.notify_all();



    /* [V8.398] bounded join only (no pre-stop):
       an unbounded join here holds the lifecycle lock forever. Pre-stopping the codec
       is NOT done: some of these sites are on the playback path, and stopping the
       codec there interrupts the audible track (measured: songs changed 16 -> 7).
       A bounded join alone is enough: the caller returns and releases the lock. */
    joinDecoderTimed(g_decoderThreadB, "V8.398 B decoder", 2500);



    if (g_decoderCodecB) { codecCallTimed("Incoming(B) stop(old)", 2500, []() { return AMediaCodec_stop(g_decoderCodecB); }); } /* V8.392 keep codec for reuse */



    if (g_decoderExtractorB) { codecCallVoid("Incoming(B) extractor_delete", 2000, []() { AMediaExtractor_delete(g_decoderExtractorB); }); g_decoderExtractorB = nullptr; }



    if (g_ringBufferB) g_ringBufferB->clear();







    g_decoderStopRequested.store(true);



    g_decoderPaused.store(false);



    g_decoderCv.notify_all();



    // 【V8.3】先 join 解码线程再 stop codec。旧顺序"先 stop 再 join"会让正在



    // readSampleData 的线程的 codec input buffer 被 stop 释放 → use-after-free SIGSEGV。



    // 解码线程 dequeue 超时仅 5ms，先 join 能快速返回，且此时 codec/extractor 仍存活。



    joinDecoderA();



    if (g_decoderCodec) {

        // 【V8.411】bound teardown stop so a wedged codec can NEVER hold g_decoderLifecycleMutex forever.
        // On timeout the worker is detached (still inside stop); leak the codec and poison it so the
        // next open recreates a fresh instance instead of poking a wedged one. Nulling here also makes
        // the following delete block a no-op, avoiding a second wedge on the same mutex.
        media_status_t st = codecCallTimed("ActiveOpen(A) stop(old)", 2500, []() {
            return AMediaCodec_stop(g_decoderCodec);
        });
        if (st == kCodecTimeout) {
            LOGE("ActiveOpen(A): stop(old) timed out -> poison + skip delete");
            g_decoderCodecPoisoned.store(true);
            g_decoderCodec = nullptr;
        }

    }



    std::lock_guard<std::mutex> lock(g_streamMutex);







    if (g_decoderCodec) {



        { bool del_ok = codecCallVoid("ActiveOpen(A) delete(old)", 2500, []() {
            AMediaCodec_delete(g_decoderCodec);
        }); if (!del_ok) { LOGE("ActiveOpen(A): delete(old) timed out -> poison"); g_decoderCodecPoisoned.store(true); } }



        g_decoderCodec = nullptr;



        // MediaCodec looper destruction is async — let it release mActivityNotify



        std::this_thread::sleep_for(std::chrono::milliseconds(300));



    }



    if (g_decoderExtractor) {



        AMediaExtractor_delete(g_decoderExtractor);



        g_decoderExtractor = nullptr;



    }



    if (!g_ringBuffer) {



        g_ringBuffer = new PCMRingBuffer(kRingBufferCapacity);



    } else {



        g_ringBuffer->clear();



    }



    g_decoderPositionUs.store(0);   // 【V7.44】切歌后位置归零，避免进度条从上次位置开始跳



    g_ringBufferFill.store(0);     // 【V7.47】RingBuffer填充量归零，避免进度条跳变



    g_playbackPositionUs.store(0); // 【V7.67】重置实际播放位置



    g_decoderOutputEncoding.store(2);  // 【V7.x】重置输出编码为 PCM16，待 FORMAT_CHANGED 再更新



    g_decoderIsFloat.store(false);



    g_decoderHasSourceEnc.store(false);   // 【V8.239】源编码标志随曲目重置







    g_decoderExtractor = AMediaExtractor_new();



    if (!g_decoderExtractor) {



        LOGE("Failed to create AMediaExtractor");



        env->ReleaseStringUTFChars(filePath, path);



        g_nativeOpenStep.store(1);



        g_nativeOpenErrorCode.store(-1);



        return false;



    }







    int result = AMediaExtractor_setDataSource(g_decoderExtractor, path);



    env->ReleaseStringUTFChars(filePath, path);



    if (result != AMEDIA_OK) {



        LOGE("Failed to set data source (error %d)", result);



        AMediaExtractor_delete(g_decoderExtractor);



        g_decoderExtractor = nullptr;



        g_nativeOpenStep.store(2);



        g_nativeOpenErrorCode.store(result);



        return false;



    }



    g_nativeOpenStep.store(3);







    size_t numTracks = AMediaExtractor_getTrackCount(g_decoderExtractor);



    g_decoderTrackIndex = -1;



    int sampleRate = 44100, channelCount = 2;



    for (size_t i = 0; i < numTracks; i++) {



        AMediaFormat* format = AMediaExtractor_getTrackFormat(g_decoderExtractor, i);



        if (!format) continue;



        const char* mime = nullptr;



        AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime);



        if (mime && strncmp(mime, "audio/", 6) == 0) {



            g_decoderTrackIndex = (int)i;



            int32_t sr = 0, ch = 0;



            AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr);



            AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);



            if (sr > 0) sampleRate = sr;



            if (ch > 0) channelCount = ch;



            LOGI("Audio track %zu: mime=%s, rate=%d, ch=%d", i, mime, sampleRate, channelCount);



            // 【V8.239 WAV 杂音根治】从 extractor 轨道格式读真实源 PCM 编码（仅 raw/WAV 可靠）。



            // 只有 raw/WAV 解码器忽略我们请求的 PCM16、直出源位深；FLAC/AAC 的解码器会按请求



            // 输出 PCM16（或 AAC float），此时 extractor 报的源位深不可信，必须走默认 2 + 启发式兜底。



            {



                int srcEnc = (mime && strcmp(mime, "audio/raw") == 0) ? readSourcePcmEncoding(format) : 0;



                if (srcEnc) {



                    g_decoderSourceEnc.store(srcEnc);



                    g_decoderHasSourceEnc.store(true);



                    g_decoderOutputEncoding.store(srcEnc);



                    g_decoderIsFloat.store(srcEnc == 4 || srcEnc == 22);



                    LOGI("NDK Decoder A: source pcm-encoding=%d (from extractor, raw/WAV)", srcEnc);



                } else {



                    g_decoderHasSourceEnc.store(false);



                }



            }



            AMediaExtractor_selectTrack(g_decoderExtractor, g_decoderTrackIndex);







            /* [V8.392] the deck pool is permanent: same mime -> stop+flush and reuse; different mime -> full recreate.
               [V8.410] reuse is skipped when the codec is poisoned (wedge suspected) -> recreate fresh. */
            if (g_decoderCodec && !g_decoderCodecPoisoned.load() && strncmp(g_decoderMime, mime, 63) == 0) {
                codecCallTimed("Incoming(A) stop(reuse)", 2500, []() { return AMediaCodec_stop(g_decoderCodec); });
                codecCallTimed("Incoming(A) flush(reuse)", 2500, []() { return AMediaCodec_flush(g_decoderCodec); });
            } else {
                if (g_decoderCodec) {
                    codecCallTimed("Incoming(A) stop(old)", 2500, []() { return AMediaCodec_stop(g_decoderCodec); });
                    codecCallVoid("Incoming(A) delete(old)", 2500, []() { AMediaCodec_delete(g_decoderCodec); });
                    g_decoderCodec = nullptr;
                }
                g_decoderCodec = AMediaCodec_createDecoderByType(mime);  // raw create (allocation; low-risk)
                strncpy(g_decoderMime, mime, 63); g_decoderMime[63] = 0;
            }



            if (!g_decoderCodec) {



                LOGE("Failed to create decoder for %s", mime);



                AMediaFormat_delete(format);



                g_nativeOpenStep.store(4);



                return false;



            }







            // Configure with float output request first



            AMediaFormat* configureFormat = AMediaFormat_new();



            {



                const char* m = nullptr;



                AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &m);



                if (m) AMediaFormat_setString(configureFormat, AMEDIAFORMAT_KEY_MIME, m);



                int32_t sr2 = 0, ch2 = 0; int64_t dur = 0;



                AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr2);



                AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch2);



                AMediaFormat_getInt64(format, AMEDIAFORMAT_KEY_DURATION, &dur);



                if (sr2 > 0) AMediaFormat_setInt32(configureFormat, AMEDIAFORMAT_KEY_SAMPLE_RATE, sr2);



                if (ch2 > 0) AMediaFormat_setInt32(configureFormat, AMEDIAFORMAT_KEY_CHANNEL_COUNT, ch2);



                if (dur > 0) AMediaFormat_setInt64(configureFormat, AMEDIAFORMAT_KEY_DURATION, dur);



                if (dur > 0) g_cachedDurationUs.store(dur);



            }



            copyCsdBuffers(configureFormat, format);



            AMediaFormat_setInt32(configureFormat, "pcm-encoding", 2);  // PCM16



            media_status_t status = codecCallTimed("ActiveOpen(A) configure", 2500, [configureFormat]() {
                return AMediaCodec_configure(g_decoderCodec, configureFormat, nullptr, nullptr, 0);
            });
            if (status == kCodecTimeout) { LOGE("ActiveOpen(A): configure timed out -> abandon open"); g_decoderCodecPoisoned.store(true); AMediaFormat_delete(configureFormat); AMediaFormat_delete(format); return false; }



            AMediaFormat_delete(configureFormat);



            if (status != AMEDIA_OK) {



                LOGW("PCM16 configure failed (%d), trying default", status);



                AMediaFormat* format2 = AMediaFormat_new();



                {



                    const char* m2 = nullptr;



                    AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &m2);



                    if (m2) AMediaFormat_setString(format2, AMEDIAFORMAT_KEY_MIME, m2);



                    int32_t sr2 = 0, ch2 = 0; int64_t dur2 = 0;



                    AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr2);



                    AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch2);



                    AMediaFormat_getInt64(format, AMEDIAFORMAT_KEY_DURATION, &dur2);



                    if (sr2 > 0) AMediaFormat_setInt32(format2, AMEDIAFORMAT_KEY_SAMPLE_RATE, sr2);



                    if (ch2 > 0) AMediaFormat_setInt32(format2, AMEDIAFORMAT_KEY_CHANNEL_COUNT, ch2);



                    if (dur2 > 0) AMediaFormat_setInt64(format2, AMEDIAFORMAT_KEY_DURATION, dur2);



                }



                copyCsdBuffers(format2, format);



                status = codecCallTimed("ActiveOpen(A) configure-retry", 2500, [format2]() {
                    return AMediaCodec_configure(g_decoderCodec, format2, nullptr, nullptr, 0);
                });
                if (status == kCodecTimeout) { LOGE("ActiveOpen(A): configure(retry) timed out -> abandon open"); g_decoderCodecPoisoned.store(true); AMediaFormat_delete(format2); AMediaFormat_delete(format); return false; }



                AMediaFormat_delete(format2);



                if (status != AMEDIA_OK) {



                    LOGE("Failed to configure decoder: %d", status);



                    AMediaFormat_delete(format);



                    g_nativeOpenStep.store(5);



                    g_nativeOpenErrorCode.store(status);



                    return false;



                }



            }



            status = codecCallTimed("ActiveOpen(A) start", 2500, []() {
                return AMediaCodec_start(g_decoderCodec);
            });
            if (status == kCodecTimeout) { LOGE("ActiveOpen(A): start timed out -> abandon open"); g_decoderCodecPoisoned.store(true); AMediaFormat_delete(format); return false; }



            if (status != AMEDIA_OK) {



                LOGE("Failed to start decoder: %d", status);



                AMediaFormat_delete(format);



                g_nativeOpenStep.store(6);



                g_nativeOpenErrorCode.store(status);



                return false;



            }



            // 【V8.207】start 后先要一次真实输出格式。raw(WAV) decoder 常常既不接受我们



            // 请求的 PCM16、也不发 OUTPUT_FORMAT_CHANGED，编码就一直停在默认的 2 上，



            // 于是 24bit 数据被当成 16bit 切 —— 就是那阵「哒哒哒」。这里尽早对齐一次，



            // 解码循环里还有帧长校验兜底。



            {



                // 【V8.239】有 extractor 源编码时不再用输出格式覆盖——raw/WAV 的 getOutputFormat



                // 常回的是 configure 时请求的 PCM16(=2)，会把下面的真实源编码(24/32bit)改回 2，



                // 重新引入「哒哒哒」。无源编码时(AAC float 等)才走输出格式探测兜底。



                if (!g_decoderHasSourceEnc.load()) {



                    AMediaFormat* of = AMediaCodec_getOutputFormat(g_decoderCodec);



                    if (of) {



                        int32_t e = 0;



                        if (AMediaFormat_getInt32(of, "pcm-encoding", &e) && e > 0) {



                            g_decoderOutputEncoding.store(e);



                            g_decoderIsFloat.store(e == 4 || e == 22);



                            LOGI("NDK Decoder: output pcm-encoding=%d (requested 2)", e);



                        }



                        AMediaFormat_delete(of);



                    }



                }



            }



            AMediaFormat_delete(format);



            break;



        }



        AMediaFormat_delete(format);



    }



    if (g_decoderTrackIndex < 0) {



        LOGE("No audio track found");



        AMediaExtractor_delete(g_decoderExtractor);



        g_decoderExtractor = nullptr;



        g_nativeOpenStep.store(7);



        return false;



    }



    g_nativeOpenStep.store(8);







    // Sample rate selection



    // 【V7.38】优先使用文件原始采样率(Bit-Perfect)



    int nativeRate = g_sampleRateNative.load();



    // 强制独占模式：先用原生率尝试 Exclusive，失败则降级 Shared，不影响后续比特完美判读



    bool forceExclusive = !g_preferSharedMode.load();



    int preferredRate = sampleRate;  // 文件原始采样率（用于比特完美判读）







    if (nativeRate > 0 && nativeRate == sampleRate) {



        LOGI("Bit-Perfect: file=%d Hz matches native=%d Hz", sampleRate, nativeRate);



    } else if (nativeRate > 0 && nativeRate != sampleRate) {



        LOGI("Rate mismatch: file=%d Hz, native=%d Hz → skip Exclusive, use Shared at file rate", sampleRate, nativeRate);



    }







    // Open Oboe output stream



    if (g_outputStream) {



        g_outputStream->close();



        g_outputStream.reset();



    }



    g_sampleRate.store(preferredRate);



    g_channelCount.store(channelCount);



    g_framesWritten.store(0);



    g_fileSampleRate.store(sampleRate);  // 【V7.27】







    oboe::AudioStreamBuilder builder;



    builder.setDirection(oboe::Direction::Output);



    builder.setPerformanceMode(oboe::PerformanceMode::PowerSaving);



    builder.setFormat(oboe::AudioFormat::Float);



    builder.setChannelCount(channelCount);



    builder.setCallback(&g_audioCallback);



    builder.setBufferCapacityInFrames(9600);



    // 【V7.200】Hardware FTZ — eliminate IIR denormal stalls at zero CPU cost



    enableHardwareFtz();



    {   // 【V3.2.7】动态路由到 USB DAC（Kotlin 侧传入当前 Port ID，0=系统默认）



        int devId = g_outputDeviceId.load();



        if (devId > 0) {



            builder.setDeviceId(devId);



            LOGI("nativeOpen: routing to deviceId=%d", devId);



        }



    }







    oboe::Result oboeResult;



    bool opened = false;







    // === 强制独占模式降级链 ===



    // 策略1：AAudio + 原生采样率 + Exclusive（仅当文件率==原生率，Bit-Perfect）



    // 【V7.93】文件率≠原生率时不走 Exclusive，避免采样率不匹配导致变速变调



    if (forceExclusive) {



        if (nativeRate > 0 && nativeRate == sampleRate) {



            builder.setAudioApi(oboe::AudioApi::AAudio);



            builder.setSampleRate(nativeRate);



            builder.setSharingMode(oboe::SharingMode::Exclusive);



            oboeResult = builder.openManagedStream(g_outputStream);



            if (oboeResult == oboe::Result::OK) {



                // Exclusive 成功，检查实际模式



                if (g_outputStream->getSharingMode() == oboe::SharingMode::Exclusive) {



                    LOGI("Oboe Exclusive SUCCESS (native rate %d Hz)", nativeRate);



                    opened = true;



                } else {



                    LOGW("Exclusive requested but got Shared — retrying with native rate");



                    g_outputStream->close();



                    g_outputStream.reset();



                    builder.setSharingMode(oboe::SharingMode::Exclusive);



                    oboeResult = builder.openManagedStream(g_outputStream);



                    if (oboeResult == oboe::Result::OK && g_outputStream->getSharingMode() == oboe::SharingMode::Exclusive) {



                        LOGI("Oboe Exclusive SUCCESS on retry (native rate %d Hz)", nativeRate);



                        opened = true;



                    } else {



                        g_outputStream->close();



                        g_outputStream.reset();



                    }



                }



            } else {



                LOGW("AAudio Exclusive (native rate %d Hz) failed: %s", nativeRate, oboe::convertToText(oboeResult));



                g_outputStream.reset();



            }



        } else if (nativeRate > 0) {



            // 【V7.93】文件率≠原生率：尝试 Exclusive at 文件率（部分设备支持非原生 Exclusive）



            builder.setAudioApi(oboe::AudioApi::AAudio);



            builder.setSampleRate(preferredRate);



            builder.setSharingMode(oboe::SharingMode::Exclusive);



            oboeResult = builder.openManagedStream(g_outputStream);



            if (oboeResult == oboe::Result::OK && g_outputStream->getSharingMode() == oboe::SharingMode::Exclusive) {



                LOGI("Oboe Exclusive SUCCESS at file rate %d Hz", preferredRate);



                opened = true;



            } else {



                if (oboeResult == oboe::Result::OK) {



                    g_outputStream->close();



                }



                g_outputStream.reset();



                LOGW("AAudio Exclusive at file rate %d Hz failed: %s", preferredRate, oboe::convertToText(oboeResult));



            }



        }







        // 策略2：AAudio + 文件率 + Shared（速率不匹配时的正确路径，AAudio HAL 自动重采样）



        if (!opened) {



            builder.setAudioApi(oboe::AudioApi::AAudio);



            builder.setSampleRate(preferredRate);



            builder.setSharingMode(oboe::SharingMode::Shared);



            oboeResult = builder.openManagedStream(g_outputStream);



            if (oboeResult == oboe::Result::OK) {



                LOGI("Oboe AAudio Shared (file rate %d Hz): rate=%d", preferredRate, g_outputStream->getSampleRate());



                opened = true;



            } else {



                LOGW("AAudio Shared (file rate) failed: %s", oboe::convertToText(oboeResult));



                g_outputStream.reset();



            }



        }







        // 策略3：Unspecified (OpenSL fallback)



        if (!opened) {



            builder.setAudioApi(oboe::AudioApi::Unspecified);



            builder.setSampleRate(preferredRate);



            oboeResult = builder.openManagedStream(g_outputStream);



            if (oboeResult == oboe::Result::OK) {



                LOGI("Oboe OpenSL fallback: rate=%d", g_outputStream->getSampleRate());



                opened = true;



            } else {



                LOGE("All audio APIs failed: %s", oboe::convertToText(oboeResult));



                g_streamError.store(static_cast<int>(oboeResult));



                return false;



            }



        }



    } else {



        // 原逻辑：优先共享模式



        builder.setAudioApi(oboe::AudioApi::AAudio);



        builder.setSampleRate(preferredRate);



        builder.setSharingMode(oboe::SharingMode::Shared);



        oboeResult = builder.openManagedStream(g_outputStream);







        if (oboeResult != oboe::Result::OK) {



            LOGW("AAudio Shared failed: %s — trying Unspecified (OpenSL)", oboe::convertToText(oboeResult));



            builder.setAudioApi(oboe::AudioApi::Unspecified);



            oboeResult = builder.openManagedStream(g_outputStream);



            if (oboeResult != oboe::Result::OK) {



                LOGE("All audio APIs failed: %s", oboe::convertToText(oboeResult));



                g_streamError.store(static_cast<int>(oboeResult));



                return false;



            }



        }



    }







    // Initialize DSP with stream sample rate



    {



        std::lock_guard<std::mutex> eqLock(g_eqMutex);



        int sr = g_outputStream->getSampleRate();



        g_eqBand3L.setHighShelf(sr, 15000.0f, -2.0f, 1.0f);



        g_eqBand3R.setHighShelf(sr, 15000.0f, -2.0f, 1.0f);



        g_eqBand3L.reset();



        g_eqBand3R.reset();



        g_limiter.setSampleRate(sr);



        g_limiter.reset();



        g_mbLimiter.setSampleRate(sr);



        g_mbLimiter.reset();



        g_dynEq.setSampleRate(sr);



        g_dynEq.reset();



        // 【V8.180】这四个原先只 setSampleRate、从不 reset：流重建后仍带着上一状态



        //（crossfeed 的 LP+延迟量、DTS 的双耳 HRTF、AGC/响度/压缩器包络），



        //换流头半秒的声场宽度与增益是旧值 → 补 reset；顺手删掉上面重复的两组。



        g_agc.setSampleRate(sr);



        g_agc.reset();



        g_compressor.setSampleRate(sr);



        g_compressor.reset();



        g_loudness.setSampleRate((float)sr);



        g_loudness.reset();



        g_crossfeed.setSampleRate((float)sr);



        g_crossfeed.reset();



        g_dts.setSampleRate((float)sr);



        g_dts.reset();



    }



    g_sampleRate.store(g_outputStream->getSampleRate());



    g_channelCount.store(g_outputStream->getChannelCount());



    g_nativeOpenStep.store(10);



    const char* modeStr = g_outputStream->getSharingMode() == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared";



    bool isBitPerfect = (g_outputStream->getSampleRate() == nativeRate);



    LOGI("OboeDirectPlayer ready: rate=%d, ch=%d, mode=%s, bitPerfect=%s",



         g_outputStream->getSampleRate(), g_outputStream->getChannelCount(),



         modeStr, isBitPerfect ? "YES" : "NO");



    return true;



}







// --- nativeOpenFd (V7.20: FD-based open for Scoped Storage) ---



JNIEXPORT jboolean JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeOpenFd(JNIEnv *env, jobject thiz,



                                                      jint fd, jlong offset, jlong length) {



    std::lock_guard<std::timed_mutex> lifecycleLock(g_decoderLifecycleMutex);



    LOGI("OboeDirectPlayer: opening FD %d offset=%lld length=%lld", fd, (long long)offset, (long long)length);







    // 【Crossfade】开新曲（A 轨硬加载）时复位 crossfade 状态并停 B 轨



    g_crossfadeActive.store(false);



    g_activeIsB.store(false);



    g_crossfadePos.store(0.0f);



    g_decoderStopRequestedB.store(true);



    g_decoderCvB.notify_all();



    /* [V8.398] bounded join only (no pre-stop):
       an unbounded join here holds the lifecycle lock forever. Pre-stopping the codec
       is NOT done: some of these sites are on the playback path, and stopping the
       codec there interrupts the audible track (measured: songs changed 16 -> 7).
       A bounded join alone is enough: the caller returns and releases the lock. */
    joinDecoderTimed(g_decoderThreadB, "V8.398 B decoder", 2500);



    if (g_decoderCodecB) { codecCallTimed("Incoming(B) stop(old)", 2500, []() { return AMediaCodec_stop(g_decoderCodecB); }); } /* V8.392 keep codec for reuse */



    if (g_decoderExtractorB) { codecCallVoid("Incoming(B) extractor_delete", 2000, []() { AMediaExtractor_delete(g_decoderExtractorB); }); g_decoderExtractorB = nullptr; }



    if (g_ringBufferB) g_ringBufferB->clear();







    g_decoderStopRequested.store(true);



    g_decoderPaused.store(false);



    g_decoderCv.notify_all();



    // 【V8.3】先 join 解码线程再 stop codec（修复 use-after-free SIGSEGV）



    joinDecoderA();



    if (g_decoderCodec) {

        // 【V8.411】bound teardown stop so a wedged codec can NEVER hold g_decoderLifecycleMutex forever.
        // On timeout the worker is detached (still inside stop); leak the codec and poison it so the
        // next open recreates a fresh instance instead of poking a wedged one. Nulling here also makes
        // the following delete block a no-op, avoiding a second wedge on the same mutex.
        media_status_t st = codecCallTimed("ActiveOpen(A) stop(old)", 2500, []() {
            return AMediaCodec_stop(g_decoderCodec);
        });
        if (st == kCodecTimeout) {
            LOGE("ActiveOpen(A): stop(old) timed out -> poison + skip delete");
            g_decoderCodecPoisoned.store(true);
            g_decoderCodec = nullptr;
        }

    }



    std::lock_guard<std::mutex> lock(g_streamMutex);







    // 【V7.28】进度追踪变量清零（修复每曲累积偏移 bug）



    g_framesWritten.store(0);



    g_playbackPositionUs.store(0);



    g_totalSampleCount.store(0);



    g_clipSampleCount.store(0);



    g_clipPeak.store(0.0f);



    g_decoderOutputEncoding.store(2);  // 【V7.x】重置输出编码为 PCM16



    g_decoderIsFloat.store(false);



    g_decoderHasSourceEnc.store(false);   // 【V8.239】源编码标志随曲目重置







    if (g_decoderCodec) {



        { bool del_ok = codecCallVoid("ActiveOpen(A) delete(old)", 2500, []() {
            AMediaCodec_delete(g_decoderCodec);
        }); if (!del_ok) { LOGE("ActiveOpen(A): delete(old) timed out -> poison"); g_decoderCodecPoisoned.store(true); } }



        g_decoderCodec = nullptr;



        // MediaCodec looper destruction is async — let it release mActivityNotify。



        // 【V8.202】这一处不能省。实测试过跳过：省掉这 300ms 后，新解码线程反而要卡



        // 150ms+ 才吐得出第一帧（旧 codec 的 looper 还没放手，新 codec 初始化被堵住），



        // 等于把「空睡」换成了「真卡」，切歌反而更慢。要省就省 stop 侧那一次。



        std::this_thread::sleep_for(std::chrono::milliseconds(300));



    }



    if (g_decoderExtractor) {



        AMediaExtractor_delete(g_decoderExtractor);



        g_decoderExtractor = nullptr;



    }



    if (!g_ringBuffer) {



        g_ringBuffer = new PCMRingBuffer(kRingBufferCapacity);



    } else {



        g_ringBuffer->clear();



    }







    g_decoderExtractor = AMediaExtractor_new();



    if (!g_decoderExtractor) {



        LOGE("Failed to create AMediaExtractor for FD");



        g_nativeOpenStep.store(1);



        g_nativeOpenErrorCode.store(-1);



        return false;



    }







    media_status_t result = codecCallTimed("ActiveOpen(A) setDataSourceFd", 3000, [fd, offset, length]() {
        return AMediaExtractor_setDataSourceFd(g_decoderExtractor, fd, offset, length);
    });
    if (result == kCodecTimeout) { LOGE("ActiveOpen(A): setDataSourceFd timed out -> abandon open"); g_decoderCodecPoisoned.store(true); return false; }



    if (result != AMEDIA_OK) {



        LOGE("Failed to set FD data source (error %d)", result);



        AMediaExtractor_delete(g_decoderExtractor);



        g_decoderExtractor = nullptr;



        g_nativeOpenStep.store(2);



        g_nativeOpenErrorCode.store(result);



        return false;



    }



    g_nativeOpenStep.store(3);







    // Same track finding + decoder setup as nativeOpen



    size_t numTracks = AMediaExtractor_getTrackCount(g_decoderExtractor);



    g_decoderTrackIndex = -1;



    int sampleRate = 44100, channelCount = 2;



    for (size_t i = 0; i < numTracks; i++) {



        AMediaFormat* format = AMediaExtractor_getTrackFormat(g_decoderExtractor, i);



        if (!format) continue;



        const char* mime = nullptr;



        AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime);



        if (mime && strncmp(mime, "audio/", 6) == 0) {



            g_decoderTrackIndex = (int)i;



            int32_t sr = 0, ch = 0;



            AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr);



            AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);



            if (sr > 0) sampleRate = sr;



            if (ch > 0) channelCount = ch;



            LOGI("FD Audio track %zu: mime=%s, rate=%d, ch=%d", i, mime, sampleRate, channelCount);



            // 【V8.239 WAV 杂音根治】从 extractor 轨道格式读真实源 PCM 编码（WAV/raw 可靠）。



            {



                int srcEnc = (mime && strcmp(mime, "audio/raw") == 0) ? readSourcePcmEncoding(format) : 0;



                if (srcEnc) {



                    g_decoderSourceEnc.store(srcEnc);



                    g_decoderHasSourceEnc.store(true);



                    g_decoderOutputEncoding.store(srcEnc);



                    g_decoderIsFloat.store(srcEnc == 4 || srcEnc == 22);



                    LOGI("NDK Decoder A(FD): source pcm-encoding=%d (from extractor, raw/WAV)", srcEnc);



                } else {



                    g_decoderHasSourceEnc.store(false);



                }



            }



            // 【2026-09-02 ALAC】audio/alac：MediaCodec 无 ALAC 解码器 → ALAC 直解引擎



            if (mime && strcmp(mime, "audio/alac") == 0) {



                g_alacModeA.store(true);



                AMediaFormat_delete(format);



                LOGI("FD: ALAC detected, using AlacDirect engine");



                break;



            }



            AMediaExtractor_selectTrack(g_decoderExtractor, g_decoderTrackIndex);







            g_nativeOpenStep.store(4);



            /* [V8.392] the deck pool is permanent: same mime -> stop+flush and reuse; different mime -> full recreate.
               [V8.410] reuse is skipped when the codec is poisoned (wedge suspected) -> recreate fresh. */
            if (g_decoderCodec && !g_decoderCodecPoisoned.load() && strncmp(g_decoderMime, mime, 63) == 0) {
                codecCallTimed("Incoming(A) stop(reuse)", 2500, []() { return AMediaCodec_stop(g_decoderCodec); });
                codecCallTimed("Incoming(A) flush(reuse)", 2500, []() { return AMediaCodec_flush(g_decoderCodec); });
            } else {
                if (g_decoderCodec) {
                    codecCallTimed("Incoming(A) stop(old)", 2500, []() { return AMediaCodec_stop(g_decoderCodec); });
                    codecCallVoid("Incoming(A) delete(old)", 2500, []() { AMediaCodec_delete(g_decoderCodec); });
                    g_decoderCodec = nullptr;
                }
                g_decoderCodec = AMediaCodec_createDecoderByType(mime);  // raw create (allocation; low-risk)
                strncpy(g_decoderMime, mime, 63); g_decoderMime[63] = 0;
            }



            if (!g_decoderCodec) {



                LOGE("Failed to create decoder for %s", mime);



                AMediaFormat_delete(format);



                return false;



            }







            AMediaFormat* configureFormat = AMediaFormat_new();



            {



                const char* m = nullptr;



                AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &m);



                if (m) AMediaFormat_setString(configureFormat, AMEDIAFORMAT_KEY_MIME, m);



                int32_t sr2 = 0, ch2 = 0; int64_t dur = 0;



                AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr2);



                AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch2);



                AMediaFormat_getInt64(format, AMEDIAFORMAT_KEY_DURATION, &dur);



                if (dur > 0) g_cachedDurationUs.store(dur);  // 【Crossfade】FD open 也缓存时长，否则 getDurationMs=0 导致 monitor 永不触发



                if (sr2 > 0) AMediaFormat_setInt32(configureFormat, AMEDIAFORMAT_KEY_SAMPLE_RATE, sr2);



                if (ch2 > 0) AMediaFormat_setInt32(configureFormat, AMEDIAFORMAT_KEY_CHANNEL_COUNT, ch2);



                if (dur > 0) AMediaFormat_setInt64(configureFormat, AMEDIAFORMAT_KEY_DURATION, dur);



            }



            copyCsdBuffers(configureFormat, format);



            AMediaFormat_setInt32(configureFormat, "pcm-encoding", 2);



            g_nativeOpenStep.store(5);



            media_status_t status = codecCallTimed("ActiveOpen(A) configure", 2500, [configureFormat]() {
                return AMediaCodec_configure(g_decoderCodec, configureFormat, nullptr, nullptr, 0);
            });
            if (status == kCodecTimeout) { LOGE("ActiveOpen(A): configure timed out -> abandon open"); g_decoderCodecPoisoned.store(true); AMediaFormat_delete(configureFormat); AMediaFormat_delete(format); return false; }



            AMediaFormat_delete(configureFormat);



            if (status != AMEDIA_OK) {



                AMediaFormat* format2 = AMediaFormat_new();



                {



                    const char* m2 = nullptr;



                    AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &m2);



                    if (m2) AMediaFormat_setString(format2, AMEDIAFORMAT_KEY_MIME, m2);



                    int32_t sr2 = 0, ch2 = 0; int64_t dur2 = 0;



                    AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr2);



                    AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch2);



                    AMediaFormat_getInt64(format, AMEDIAFORMAT_KEY_DURATION, &dur2);



                    if (sr2 > 0) AMediaFormat_setInt32(format2, AMEDIAFORMAT_KEY_SAMPLE_RATE, sr2);



                    if (ch2 > 0) AMediaFormat_setInt32(format2, AMEDIAFORMAT_KEY_CHANNEL_COUNT, ch2);



                    if (dur2 > 0) AMediaFormat_setInt64(format2, AMEDIAFORMAT_KEY_DURATION, dur2);



                }



                copyCsdBuffers(format2, format);



                status = codecCallTimed("ActiveOpen(A) configure-retry", 2500, [format2]() {
                    return AMediaCodec_configure(g_decoderCodec, format2, nullptr, nullptr, 0);
                });
                if (status == kCodecTimeout) { LOGE("ActiveOpen(A): configure(retry) timed out -> abandon open"); g_decoderCodecPoisoned.store(true); AMediaFormat_delete(format2); AMediaFormat_delete(format); return false; }



                AMediaFormat_delete(format2);



                if (status != AMEDIA_OK) {



                    LOGE("FD: Failed to configure decoder: %d", status);



                    AMediaFormat_delete(format);



                    return false;



                }



            }



            g_nativeOpenStep.store(6);



            status = codecCallTimed("ActiveOpen(A) start", 2500, []() {
                return AMediaCodec_start(g_decoderCodec);
            });
            if (status == kCodecTimeout) { LOGE("ActiveOpen(A): start timed out -> abandon open"); g_decoderCodecPoisoned.store(true); AMediaFormat_delete(format); return false; }



            if (status != AMEDIA_OK) {



                LOGE("FD: Failed to start decoder: %d", status);



                AMediaFormat_delete(format);



                return false;



            }



            AMediaFormat_delete(format);



            break;



        }



        AMediaFormat_delete(format);



    }



    g_nativeOpenStep.store(7);



    if (g_alacModeA.load()) {



        // ALAC 直解：extractor 已不需要（AlacDirect 自己解析 MP4 box）



        AMediaExtractor_delete(g_decoderExtractor);



        g_decoderExtractor = nullptr;



        g_alacA = std::make_unique<AlacDirect>();



        if (!g_alacA->openFd(fd, offset, length)) {



            LOGE("FD: AlacDirect openFd failed");



            g_alacA.reset();



            g_alacModeA.store(false);



            g_nativeOpenErrorCode.store(-3);



            return false;



        }



        sampleRate = g_alacA->sampleRate.load();



        channelCount = g_alacA->channels.load();



        int64_t durUs = g_alacA->durationMs.load() * 1000LL;



        if (durUs > 0) g_cachedDurationUs.store(durUs);



        LOGI("FD: AlacDirect ready sr=%d ch=%d dur=%lldms",



             sampleRate, channelCount, (long long)g_alacA->durationMs.load());



    } else if (g_decoderTrackIndex < 0) {



        LOGE("FD: No audio track found");



        AMediaExtractor_delete(g_decoderExtractor);



        g_decoderExtractor = nullptr;



        return false;



    }







    // 强制独占模式：AAudio Exclusive 必须用原生采样率，否则静默降级 Shared



    int nativeRate = g_sampleRateNative.load();



    bool forceExclusive = !g_preferSharedMode.load();



    int preferredRate = sampleRate;



    if (nativeRate > 0 && nativeRate == sampleRate) {



        LOGI("Bit-Perfect: file=%d Hz matches native=%d Hz", sampleRate, nativeRate);



    } else if (nativeRate > 0 && nativeRate != sampleRate) {



        LOGI("Rate mismatch: file=%d Hz, native=%d Hz → skip Exclusive, use Shared at file rate", sampleRate, nativeRate);



    }



    // ====================================================================



    // 【V8.198】只有「流不存在 / 采样率或声道不符 / 输出设备变了」才重建 stream。



    // 旧实现每次切歌都 close + openManagedStream，等于每换一首歌就把音频输出流



    // 拆一次装一次：回调线程停的那一小段就是切歌时听到的「断一下」，而且新流的



    // buffer 大小不保证和旧的一致（真机实测 numFrames 在 704 / 1280 / 4260 之间乱



    // 跳，正是这个副作用，之前一直没定位到）。文件采样率与声道都相同时直接复用。



    // ====================================================================



    // 【V8.198】注意：本函数早在开头就持有了 g_streamMutex（外层 lock_guard），



    // 这里绝不能再加一次 lock_guard —— std::mutex 不是递归锁，会把自己锁死，



    // 表现就是「open 之后再也打不出日志、切歌卡住」。外层已经保证了安全。



    int curSr = 0, curCh = 0;



    if (g_outputStream) {



        curSr = g_outputStream->getSampleRate();



        curCh = g_outputStream->getChannelCount();



    }



    const bool streamReusable = g_outputStream != nullptr



                                && curSr > 0 && curSr == preferredRate



                                && curCh == channelCount



                                && g_streamDeviceId.load() == g_outputDeviceId.load();



    if (!streamReusable) {



        if (g_outputStream) {



            g_outputStream->close();



            g_outputStream.reset();



        }



        LOGI("nativeOpenFd: rebuild stream (%dHz/%dch -> %dHz/%dch, dev %d->%d)",



             curSr, curCh, preferredRate, channelCount,



             g_streamDeviceId.load(), g_outputDeviceId.load());



    } else {



        LOGI("nativeOpenFd: reuse stream (%dHz/%dch)", curSr, curCh);



    }



    g_sampleRate.store(g_outputStream ? g_outputStream->getSampleRate() : preferredRate);



    g_channelCount.store(channelCount);



    g_framesWritten.store(0);



    g_fileSampleRate.store(sampleRate);



    g_nativeOpenStep.store(8);







    oboe::AudioStreamBuilder builder;



    builder.setDirection(oboe::Direction::Output);



    builder.setPerformanceMode(oboe::PerformanceMode::PowerSaving);



    builder.setFormat(oboe::AudioFormat::Float);



    builder.setChannelCount(channelCount);



    builder.setCallback(&g_audioCallback);



    builder.setBufferCapacityInFrames(9600);



    // 【V7.200】Hardware FTZ — eliminate IIR denormal stalls at zero CPU cost



    enableHardwareFtz();



    {   // 【V3.2.7】动态路由到 USB DAC（Kotlin 侧传入当前 Port ID，0=系统默认）



        int devId = g_outputDeviceId.load();



        if (devId > 0) {



            builder.setDeviceId(devId);



            LOGI("nativeOpenFd: routing to deviceId=%d", devId);



        }



    }







    oboe::Result oboeResult;



    bool opened = false;







    // 【V8.198】流可复用时整段跳过：openManagedStream() 传一个非空的 ManagedStream 时



    // 行为不可控（旧代码是先 close+reset 再传，等于强制重建）。既然已经确认现成的流



    // 就是对的，就一步都别碰它，直接沿用。



    if (streamReusable) {



        LOGI("nativeOpenFd: reuse existing stream (%dHz/%dch, dev %d) — no rebuild",



             curSr, curCh, g_streamDeviceId.load());



    } else if (forceExclusive) {



        // 策略1：AAudio + 原生率 + Exclusive（仅当文件率==原生率，Bit-Perfect）



        // 【V7.93】文件率≠原生率时不走 Exclusive，避免采样率不匹配导致变速变调



        if (nativeRate > 0 && nativeRate == sampleRate) {



            builder.setAudioApi(oboe::AudioApi::AAudio);



            builder.setSampleRate(nativeRate);



            builder.setSharingMode(oboe::SharingMode::Exclusive);



            oboeResult = builder.openManagedStream(g_outputStream);



            if (oboeResult == oboe::Result::OK) {



                if (g_outputStream->getSharingMode() == oboe::SharingMode::Exclusive) {



                    LOGI("FD Oboe Exclusive SUCCESS (native %d Hz)", nativeRate);



                    opened = true;



                } else {



                    LOGW("FD Exclusive requested but got Shared — retrying");



                    g_outputStream->close();



                    g_outputStream.reset();



                    builder.setSharingMode(oboe::SharingMode::Exclusive);



                    oboeResult = builder.openManagedStream(g_outputStream);



                    if (oboeResult == oboe::Result::OK && g_outputStream->getSharingMode() == oboe::SharingMode::Exclusive) {



                        LOGI("FD Oboe Exclusive SUCCESS on retry (native %d Hz)", nativeRate);



                        opened = true;



                    } else {



                        g_outputStream->close();



                        g_outputStream.reset();



                    }



                }



            } else {



                LOGW("FD AAudio Exclusive (native %d Hz) failed: %s", nativeRate, oboe::convertToText(oboeResult));



                g_outputStream.reset();



            }



        } else if (nativeRate > 0) {



            // 【V7.93】文件率≠原生率：尝试 Exclusive at 文件率（部分设备支持非原生 Exclusive）



            builder.setAudioApi(oboe::AudioApi::AAudio);



            builder.setSampleRate(preferredRate);



            builder.setSharingMode(oboe::SharingMode::Exclusive);



            oboeResult = builder.openManagedStream(g_outputStream);



            if (oboeResult == oboe::Result::OK && g_outputStream->getSharingMode() == oboe::SharingMode::Exclusive) {



                LOGI("FD Oboe Exclusive SUCCESS at file rate %d Hz", preferredRate);



                opened = true;



            } else {



                if (oboeResult == oboe::Result::OK) {



                    g_outputStream->close();



                }



                g_outputStream.reset();



                LOGW("FD AAudio Exclusive at file rate %d Hz failed: %s", preferredRate, oboe::convertToText(oboeResult));



            }



        }







        // 策略2：AAudio + 文件率 + Shared（速率不匹配时的正确路径，AAudio HAL 自动重采样）



        if (!opened) {



            builder.setAudioApi(oboe::AudioApi::AAudio);



            builder.setSampleRate(preferredRate);



            builder.setSharingMode(oboe::SharingMode::Shared);



            oboeResult = builder.openManagedStream(g_outputStream);



            if (oboeResult == oboe::Result::OK) {



                LOGI("FD Oboe AAudio Shared (file rate %d Hz): rate=%d", preferredRate, g_outputStream->getSampleRate());



                opened = true;



            } else {



                LOGW("FD AAudio Shared (file rate) failed: %s", oboe::convertToText(oboeResult));



                g_outputStream.reset();



            }



        }







        // 策略3：OpenSL fallback



        if (!opened) {



            builder.setAudioApi(oboe::AudioApi::Unspecified);



            builder.setSampleRate(preferredRate);



            oboeResult = builder.openManagedStream(g_outputStream);



            if (oboeResult == oboe::Result::OK) {



                LOGI("FD Oboe OpenSL fallback: rate=%d", g_outputStream->getSampleRate());



                opened = true;



            } else {



                LOGE("FD All audio APIs failed: %s", oboe::convertToText(oboeResult));



                g_streamError.store(static_cast<int>(oboeResult));



                return false;



            }



        }



    } else if (!streamReusable) {



        // 原逻辑：共享优先



        builder.setAudioApi(oboe::AudioApi::AAudio);



        builder.setSampleRate(preferredRate);



        builder.setSharingMode(oboe::SharingMode::Shared);



        oboeResult = builder.openManagedStream(g_outputStream);



        if (oboeResult != oboe::Result::OK) {



            LOGW("FD AAudio Shared failed: %s — trying OpenSL", oboe::convertToText(oboeResult));



            builder.setAudioApi(oboe::AudioApi::Unspecified);



            oboeResult = builder.openManagedStream(g_outputStream);



            if (oboeResult != oboe::Result::OK) {



                LOGE("FD All audio APIs failed: %s", oboe::convertToText(oboeResult));



                g_streamError.store(static_cast<int>(oboeResult));



                return false;



            }



        }



    }



    {



        std::lock_guard<std::mutex> eqLock(g_eqMutex);



        int sr = g_outputStream->getSampleRate();



        g_eqBand3L.setHighShelf(sr, 15000.0f, -2.0f, 1.0f);



        g_eqBand3R.setHighShelf(sr, 15000.0f, -2.0f, 1.0f);



        g_eqBand3L.reset();



        g_eqBand3R.reset();



        g_limiter.setSampleRate(sr);



        g_limiter.reset();



        g_mbLimiter.setSampleRate(sr);



        g_mbLimiter.reset();



        g_dynEq.setSampleRate(sr);



        g_dynEq.reset();



        // 【V8.180】这四个原先只 setSampleRate、从不 reset：流重建后仍带着上一状态



        //（crossfeed 的 LP+延迟量、DTS 的双耳 HRTF、AGC/响度/压缩器包络），



        //换流头半秒的声场宽度与增益是旧值 → 补 reset；顺手删掉上面重复的两组。



        g_agc.setSampleRate(sr);



        g_agc.reset();



        g_compressor.setSampleRate(sr);



        g_compressor.reset();



        g_loudness.setSampleRate((float)sr);



        g_loudness.reset();



        g_crossfeed.setSampleRate((float)sr);



        g_crossfeed.reset();



        g_dts.setSampleRate((float)sr);



        g_dts.reset();



    }



    g_sampleRate.store(g_outputStream->getSampleRate());



    g_channelCount.store(g_outputStream->getChannelCount());



    const char* fdModeStr = g_outputStream->getSharingMode() == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared";



    LOGI("FD OboeDirectPlayer ready: rate=%d, ch=%d, mode=%s",



         g_outputStream->getSampleRate(), g_outputStream->getChannelCount(),



         fdModeStr);



    // 【V8.198】记录流当前绑定的设备，下次切歌据此判断是否还能复用



    g_streamDeviceId.store(g_outputDeviceId.load());



    g_nativeOpenStep.store(10);







    // 【V8.198 切歌静音】提前把解码线程拉起来：下面这些收尾动作（stream 复用判定、



    // 300ms MediaCodec 延迟释放、状态复位）本来就占时间，让解码线程并行去填 ring，



    // play() 回来时回调第一帧就有数据，而不是空着等解码。



    if (!g_decoderThreadRunning.load()) {



        g_decoderStopRequested.store(false);



        g_decoderPaused.store(false);



        g_decoderEos.store(false);



        g_decoderRunning.store(true);



        g_decoderThreadRunning.store(true);



        if (g_alacModeA.load()) {



            g_decoderThread = std::thread(alacDecodeLoopA);



        } else {



            g_decoderThread = std::thread(ndkDecodeLoop);



        }



        // 【V8.202】曾想在这里同步等 ring 攒够再返回（让 play() 一启动回调就有货），



        // 真机实测证伪：切歌窗口内 ring 恒 0、dFrames 恒 0、posUs 恒 0，等 600ms 也等不到，



        // 白拖 600ms 切歌延迟。MediaCodec 在这条路径上要更久才吐得出第一帧，跟等待无关。



        // 所以这里只把线程拉起来（保留 V8.198 的做法），不阻塞。诊断日志留着备查。



        const long long framesAtStart = g_decoderFramesOutput.load();



        LOGI("nativeOpenFd: decoder pre-started, dFrames-at-open=%lld (prefetch SYNCHRONOUS WAIT REMOVED: "



             "measured 0 frames even after 600ms)",



             (long long)(g_decoderFramesOutput.load() - framesAtStart));



    }



    return true;



}







// --- Playback control ---



JNIEXPORT jboolean JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativePlay(JNIEnv *env, jobject thiz) {



    std::lock_guard<std::timed_mutex> lifecycleLock(g_decoderLifecycleMutex);



    std::lock_guard<std::mutex> lock(g_streamMutex);



    if (!g_outputStream || (!g_alacModeA.load() && (!g_decoderCodec || !g_decoderExtractor))) {



        LOGE("nativePlay: not opened");



        return false;



    }



    oboe::Result result = g_outputStream->requestStart();



    if (result != oboe::Result::OK) {



        LOGE("Failed to start Oboe: %s", oboe::convertToText(result));



        return false;



    }



    pthread_t tid = pthread_self();



    struct sched_param sp;



    sp.sched_priority = sched_get_priority_max(SCHED_FIFO) - 5;



    pthread_setschedparam(tid, SCHED_FIFO, &sp);



    g_isPlaying.store(true);



    // 【V8.317】置「本次会话曾播过」latch：真正的 nativeStop 才清它，



    // pause（蓝牙断连时系统就是走这条）**不清** —— 否则恢复路径的前置条件又没了。



    g_playSessionLatched.store(true);



    g_decoderStopRequested.store(false);



    g_decoderPaused.store(false);



    g_decoderEos.store(false);



    g_decoderRunning.store(true);



    // 【V8.198】exchange 取旧值：解码线程已在 open 阶段拉起过（ring 预填）就别重复起 ——



    // 重复起会先 join 掉正在填 ring 的那条，等于把预填的进度全丢掉。



    const bool decoderNeedStart = !g_decoderThreadRunning.exchange(true);  // 【V7.16】



    if (decoderNeedStart) {



        joinDecoderA();



        if (g_alacModeA.load()) {



            g_decoderThread = std::thread(alacDecodeLoopA);



            LOGI("OboeDirectPlayer: playback started (ALAC direct)");



        } else {



            g_decoderThread = std::thread(ndkDecodeLoop);



            LOGI("OboeDirectPlayer: playback started");



        }



    } else {



        LOGI("OboeDirectPlayer: playback started (decoder already running from open)");



    }



    return true;



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativePause(JNIEnv *env, jobject thiz) {



    g_decoderPaused.store(true);



    g_decoderCv.notify_all();



    std::lock_guard<std::mutex> lock(g_streamMutex);



    if (g_outputStream) {



        g_outputStream->requestPause();



        g_isPlaying.store(false);



    }



    LOGI("OboeDirectPlayer: paused");



}







JNIEXPORT jboolean JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeResume(JNIEnv *env, jobject thiz) {



    g_decoderPaused.store(false);



    g_decoderCv.notify_all();



    std::lock_guard<std::mutex> lock(g_streamMutex);



    if (g_outputStream) {



        oboe::Result result = g_outputStream->requestStart();



        if (result == oboe::Result::OK) {



            g_isPlaying.store(true);



            return true;



        }



    }



    return false;



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSeekTo(JNIEnv *env, jobject thiz,



                                                     jlong positionUs) {



    std::lock_guard<std::timed_mutex> lifecycleLock(g_decoderLifecycleMutex);



    LOGI("OboeDirectPlayer: seekTo %lld us", (long long)positionUs);



    // 【Crossfade】按 active 轨分流：B 接管后 seek 的是 B 轨，否则 seek 退役 A 轨会导致进度条无效



    bool seekB = g_activeIsB.load();



    // 【V8.179】不再置 g_flushRequested：那一拍会把输出硬切成全零（全幅阶跃 = 爆音），



    // 改由下面的淡入斜坡接管。ring 已清空，解码线程重启后会重新填满。



    // 共享的 Oboe 输出侧状态（两轨共用同一输出流）



    g_framesWritten.store(0);



    g_playbackPositionUs.store(positionUs);  // 【V7.82】同步实际播放位置到 seek 目标，防止进度条回跳



    {



        int fadeSr = g_sampleRate.load(); if (fadeSr <= 0) fadeSr = 44100;



        int fade = (int)((float)fadeSr * 0.020f);   // 20ms 淡入



        if (fade < 256) fade = 256;



        if (fade > 16384) fade = 16384;



        g_seekFadeSamples.store(fade, std::memory_order_relaxed);



    }



    if (seekB) {



        g_decoderPausedB.store(true);



        if (g_ringBufferB) g_ringBufferB->clear();



        resetResamplerB();  // 【Crossfade 重采样】seek 后清重采样相位/prev



        if (g_alacModeB.load()) {



            if (g_alacB) g_alacB->seekMs(positionUs / 1000);



            g_decoderPositionB.store(positionUs);



            g_decoderEosB.store(false);



            g_decoderPausedB.store(false);



            g_decoderCvB.notify_all();



        } else {



            if (g_decoderCodecB) AMediaCodec_flush(g_decoderCodecB);



            if (g_decoderExtractorB) AMediaExtractor_seekTo(g_decoderExtractorB, positionUs, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);



            g_decoderPositionB.store(positionUs);



            g_decoderEosB.store(false);



            g_decoderPausedB.store(false);



            g_decoderCvB.notify_all();



        }



    } else {



        g_decoderPaused.store(true);



        if (g_ringBuffer) g_ringBuffer->clear();



        resetResamplerA();  // 【Crossfade 重采样】seek 后清 A 轨重采样相位/prev（与 B 分支对称，防相位错乱变调）



        if (g_alacModeA.load()) {



            if (g_alacA) g_alacA->seekMs(positionUs / 1000);



            g_decoderPositionUs.store(positionUs);



            g_decoderEos.store(false);



            g_decoderPaused.store(false);



            g_decoderCv.notify_all();



        } else {



            if (g_decoderCodec) AMediaCodec_flush(g_decoderCodec);



            if (g_decoderExtractor) AMediaExtractor_seekTo(g_decoderExtractor, positionUs, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);



            g_decoderPositionUs.store(positionUs);



            g_decoderEos.store(false);



            g_decoderPaused.store(false);



            g_decoderCv.notify_all();



        }



    }



    // 【V8.179】退役轨也要停 + 清 ring：crossfade 完成后另一条轨的解码线程还在跑，



    // 它继续往 ring 写「旧位置」的 PCM。下次 crossfade 一旦开混，两条错位轨会叠在一起



    // （错位的双轨 + 相位跳变 = 拖动播放条时的「啪啪啪」乱响）。



    // crossfade 进行中不动另一轨：那条正是正在淡入的 to 轨，清掉它过渡会直接塌成静音。



    if (!g_crossfadeActive.load()) {



        if (seekB) {



            g_decoderStopRequested.store(true);



            g_decoderCv.notify_all();



            if (g_ringBuffer) g_ringBuffer->clear();



            resetResamplerA();



        } else {



            g_decoderStopRequestedB.store(true);



            g_decoderCvB.notify_all();



            if (g_ringBufferB) g_ringBufferB->clear();



            resetResamplerB();



        }



    }



}







// ============================================================================



// 【V8.202】停止实现，抽出来给 nativeStop 与 nativeStopSoft 共用。



//   keepStream=true 时输出流只 requestStop() 不销毁。为什么重要：切歌是「stop → open



//   下一首」两步，旧实现在这里 close+reset 掉流，nativeOpenFd 的 streamReusable 判定



//   就必然为 false（日志里的 `rebuild stream (0Hz/0ch -> ...)` 就是铁证），每次切歌都



//   要重建一次流 —— 真机实测每次 125~238ms 静音空窗。保留流对象、只停解码 + 清 ring，



//   下一首采样率一致时直接复用，零重建。



//   keepStream=true 时置位 g_skipCodecFreeSleep，省掉本函数里那一次 300ms 等待。



//   注意这只是省掉 stop 侧那一半：open 侧那次不能省（见 nativeOpenFd 注释）。



// ============================================================================



static void stopInternal(bool keepStream) {



    std::lock_guard<std::timed_mutex> lifecycleLock(g_decoderLifecycleMutex);



    LOGI("OboeDirectPlayer: stopping (keepStream=%d)", (int)keepStream);



    // 【硬切 de-click】keepStream=true 正是切歌的软停止路径（stop → open 下一首），
    // 而它原先**没有任何 click 防护**：A 轨被 requestStop 直接断、B 轨从 seek 点硬起
    // （seek 点常为首拍，能量最大），用户听感就是「切换前后两声哒」。
    // 交叉淡化完成时早已上膛同一套机制（见翻转处的 g_declickArmPending），这里补上：
    // 末样本与 B 轨首样本的阶跃会被摊到后续约 43ms（g_declickRemain=2048）。
    // 安全性：delta = 末样本 − 新首样本，阶跃为 0 时修正量也趋 0，正常音乐无副作用；
    // 且上膛只在音频回调里消费，stop 后若不再有回调，残留标志在下次 open 的首次回调
    // 触发时 delta≈0，同样无害。
    if (keepStream) {
        g_declickArmPending = true;
        // 便于真机确认这条路径真的上膛了（历史上硬切 click 修过一次没生效，查不出原因）
        LOGI("de-click armed (hard-cut path, remain=%d frames)", g_declickRemain);
    }



    // 【V8.202】真退出路径一律恢复 sleep 保险，避免 soft-stop 未配对 open 时标志残留



    if (!keepStream) g_skipCodecFreeSleep.store(false);



    // 【Crossfade】停止时复位 crossfade 状态并停 B 轨



    g_crossfadeActive.store(false);



    g_activeIsB.store(false);



    g_crossfadePos.store(0.0f);



    g_sampleRateA.store(0);



    g_sampleRateB.store(0);



    g_channelCountB.store(2);



    resetResamplerA();



    resetResamplerB();



    g_decoderStopRequestedB.store(true);



    g_decoderCvB.notify_all();



    /* [V8.398] bounded join only (no pre-stop):
       an unbounded join here holds the lifecycle lock forever. Pre-stopping the codec
       is NOT done: some of these sites are on the playback path, and stopping the
       codec there interrupts the audible track (measured: songs changed 16 -> 7).
       A bounded join alone is enough: the caller returns and releases the lock. */
    joinDecoderTimed(g_decoderThreadB, "V8.398 B decoder", 2500);



    if (g_decoderCodecB) { codecCallTimed("Incoming(B) stop(old)", 2500, []() { return AMediaCodec_stop(g_decoderCodecB); }); } /* V8.392 keep codec for reuse */



    if (g_decoderExtractorB) { codecCallVoid("Incoming(B) extractor_delete", 2000, []() { AMediaExtractor_delete(g_decoderExtractorB); }); g_decoderExtractorB = nullptr; }



    if (g_ringBufferB) g_ringBufferB->clear();



    g_decoderStopRequested.store(true);



    g_decoderPaused.store(false);



    g_decoderCv.notify_all();



    // 【V8.3】先 join 解码线程再 stop codec（修复 use-after-free SIGSEGV）



    joinDecoderA();



    if (g_decoderCodec) {

        // 【V8.411】bound teardown stop so a wedged codec can NEVER hold g_decoderLifecycleMutex forever.
        // On timeout the worker is detached (still inside stop); leak the codec and poison it so the
        // next open recreates a fresh instance instead of poking a wedged one. Nulling here also makes
        // the following delete block a no-op, avoiding a second wedge on the same mutex.
        media_status_t st = codecCallTimed("ActiveOpen(A) stop(old)", 2500, []() {
            return AMediaCodec_stop(g_decoderCodec);
        });
        if (st == kCodecTimeout) {
            LOGE("ActiveOpen(A): stop(old) timed out -> poison + skip delete");
            g_decoderCodecPoisoned.store(true);
            g_decoderCodec = nullptr;
        }

    }



    if (keepStream) g_skipCodecFreeSleep.store(true);  // 【V8.202】下一首会用到，先打开



    std::lock_guard<std::mutex> lock(g_streamMutex);



    if (g_outputStream) {



        g_outputStream->requestStop();



        if (!keepStream) {



            g_outputStream->close();



            g_outputStream.reset();



        } else {



            // 只停不停销：保留流对象，下一首若采样率一致（44100↔44100）就原样复用



            LOGI("nativeStop: stream kept alive (%dHz/%dch) for reuse",



                 g_outputStream->getSampleRate(), g_outputStream->getChannelCount());



        }



    }



    g_isPlaying.store(false);



    // 【V8.317】只有真正的 stop 才清「曾播过」latch —— 用户主动停了就没必要恢复；



    // pause 不清（见 nativeStart/nativePause 注释）。



    g_playSessionLatched.store(false);



    if (g_decoderCodec) {



        { bool del_ok = codecCallVoid("ActiveOpen(A) delete(old)", 2500, []() {
            AMediaCodec_delete(g_decoderCodec);
        }); if (!del_ok) { LOGE("ActiveOpen(A): delete(old) timed out -> poison"); g_decoderCodecPoisoned.store(true); } }



        g_decoderCodec = nullptr;



        // 切歌软停止时 g_skipCodecFreeSleep 已被置位，这里 exchange 取走 → 跳过等待



        if (!g_skipCodecFreeSleep.exchange(false)) {



            std::this_thread::sleep_for(std::chrono::milliseconds(300));



        }



    }



    if (g_decoderExtractor) {



        AMediaExtractor_delete(g_decoderExtractor);



        g_decoderExtractor = nullptr;



    }



    if (g_ringBuffer) g_ringBuffer->clear();



    // 【ALAC】停止时释放直解引擎



    g_alacModeA.store(false);



    g_alacModeB.store(false);



    if (g_alacA) { g_alacA->close(); g_alacA.reset(); }



    if (g_alacB) { g_alacB->close(); g_alacB.reset(); }



    g_decoderRunning.store(false);



    g_decoderThreadRunning.store(false);



    g_decoderEos.store(false);



    g_decoderTrackIndex = -1;



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeStop(JNIEnv *env, jobject thiz) {



    stopInternal(false);



}







// 【V8.202】切歌专用软停止：解码全停、ring 清空，但输出流留着给下一首复用。



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeStopSoft(JNIEnv *env, jobject thiz) {



    stopInternal(true);



}







// ============================================================================



// Crossfade 双轨 — incoming 打开 / 触发 / 释放（方案 B：先 mix 再走 DSP）



// ============================================================================







// 【Crossfade】incoming 打开分发：B active → 进 A 槽，否则进 B 槽



static bool openIncomingToA_internal(int fd, int64_t offset, int64_t length);



static bool openIncomingToB_internal(int fd, int64_t offset, int64_t length);







JNIEXPORT jboolean JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeOpenIncomingFd(JNIEnv *env, jobject thiz,



                                                               jint fd, jlong offset, jlong length) {



    startDecoderWatchdog();  // [V8.410] ensure decoder-open watchdog is live before any blocking MediaCodec call
    if (g_activeIsB.load()) {



        return openIncomingToA_internal(fd, offset, length);



    }



    return openIncomingToB_internal(fd, offset, length);



}







static bool openIncomingToA_internal(int fd, int64_t offset, int64_t length) {



    // 【V8.385】有界加锁（防「一次卡死拖死全部」）：
    // MediaCodec 实例耗尽时 AMediaCodec_start / AMediaCodec_configure 会**永久阻塞**，
    // 而那一刻正持有 g_decoderLifecycleMutex → 之后每一次 openIncoming 都在这把锁上
    // 无限期排队，一个日志都不打。这就是「连切三四首后彻底切不动」的真凶。
    // 改为限时获取：拿不到锁立刻返回 false，让上层走硬切兜底，不再堵死后续切歌。
    std::unique_lock<std::timed_mutex> lifecycleLock(g_decoderLifecycleMutex, std::defer_lock);
    if (!lifecycleLock.try_lock_for(std::chrono::milliseconds(1500))) {
        LOGE("Incoming(A): lifecycle lock busy (previous open stuck) -> give up this round"
             " [owner tid=%ld stage=%d held=%ldms]",
             g_decoderOpTid.load(), g_decoderOpStage.load(),
             (long)(decoderNowMs() - g_decoderOpSinceMs.load()));
        return false;
    }
    g_decoderOpTid.store((long)std::hash<std::thread::id>{}(std::this_thread::get_id()));
    g_decoderOpSinceMs.store(decoderNowMs());
    g_decoderOpStage.store(1);
    g_decoderOpDeck.store(1);  // [V8.410] this open targets deck A



    g_decoderStopRequested.store(true);



    g_decoderCv.notify_all();



    /* [V8.395] stop MUST precede join: the decoder thread may be parked inside
       AMediaCodec_dequeueOutputBuffer(), and only AMediaCodec_stop() can wake it.
       The old order (join then stop) deadlocked whenever that happened, while
       holding the lifecycle lock -> every later open piled up on the mutex. */
    g_decoderOpStage.store(2);
    if (g_decoderCodec) { codecCallTimed("Incoming(A) stop(old)", 2500, []() { return AMediaCodec_stop(g_decoderCodec); }); }
    g_decoderOpStage.store(1);
    if (!joinDecoderTimed(g_decoderThread, "Incoming(A) decoder", 2500)) {
        /* The decoder thread is unkillable and the codec state is untrustworthy.
           Continuing would only produce a very late, stale crossfade; and because
           this thread never finishes, Kotlin would keep judging every later cut as
           "previous open stuck" and hard-cut them all. Abort now so the thread
           ends cleanly and the next cut gets a real chance. */
        LOGE("Incoming(A): decoder thread abandoned -> abort this open");
        g_decoderOpStage.store(0);
        return false;
    }



    if (g_decoderCodec) { codecCallTimed("Incoming(A) stop(reuse)", 2500, []() { return AMediaCodec_stop(g_decoderCodec); }); } /* V8.392 keep codec for reuse */



    if (g_decoderExtractor) { codecCallVoid("Incoming(A) extractor_delete", 2000, []() { AMediaExtractor_delete(g_decoderExtractor); }); g_decoderExtractor = nullptr; }



    if (!g_ringBuffer) g_ringBuffer = new PCMRingBuffer(kRingBufferCapacity);



    else g_ringBuffer->clear();

    resetResamplerA();  // 【Crossfade 重采样】新歌解码开始清零重采样相位/历史（与 seek 对称）：防不同采样率曲目交叉淡化时旧状态错位、首块产出垃圾出白噪








    g_decoderExtractor = AMediaExtractor_new();



    if (!g_decoderExtractor) { LOGE("Incoming(A): extractor new failed"); return false; }



    media_status_t r = codecCallTimed("Incoming(A) setDataSourceFd", 3000, [fd, offset, length]() {
        return AMediaExtractor_setDataSourceFd(g_decoderExtractor, fd, offset, length);
    });



    if (r == kCodecTimeout) { LOGE("Incoming(A): setDataSourceFd timed out -> abandon open"); g_decoderCodecPoisoned.store(true); return false; }
    if (r != AMEDIA_OK) { LOGE("Incoming(A): setDataSourceFd failed %d", r); AMediaExtractor_delete(g_decoderExtractor); g_decoderExtractor = nullptr; return false; }







    size_t numTracks = AMediaExtractor_getTrackCount(g_decoderExtractor);



    g_decoderTrackIndex = -1;



    for (size_t i = 0; i < numTracks; i++) {



        AMediaFormat* format = AMediaExtractor_getTrackFormat(g_decoderExtractor, i);



        if (!format) continue;



        const char* mime = nullptr;



        AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime);



        if (mime && strncmp(mime, "audio/", 6) == 0) {



            g_decoderTrackIndex = (int)i;



            // 【ALAC】MediaCodec 无 ALAC 解码器 → 直解引擎



            if (mime && strcmp(mime, "audio/alac") == 0) {



                g_alacModeA.store(true);



                AMediaFormat_delete(format);



                LOGI("Incoming(A): ALAC detected, using AlacDirect");



                break;



            }



            AMediaExtractor_selectTrack(g_decoderExtractor, g_decoderTrackIndex);



            /* [V8.392] the deck pool is permanent: same mime -> stop+flush and reuse; different mime -> full recreate.
               [V8.410] reuse is skipped when the codec is poisoned (wedge suspected) -> recreate fresh. */
            if (g_decoderCodec && !g_decoderCodecPoisoned.load() && strncmp(g_decoderMime, mime, 63) == 0) {
                codecCallTimed("Incoming(A) stop(reuse)", 2500, []() { return AMediaCodec_stop(g_decoderCodec); });
                codecCallTimed("Incoming(A) flush(reuse)", 2500, []() { return AMediaCodec_flush(g_decoderCodec); });
            } else {
                if (g_decoderCodec) {
                    codecCallTimed("Incoming(A) stop(old)", 2500, []() { return AMediaCodec_stop(g_decoderCodec); });
                    codecCallVoid("Incoming(A) delete(old)", 2500, []() { AMediaCodec_delete(g_decoderCodec); });
                    g_decoderCodec = nullptr;
                }
                g_decoderCodec = AMediaCodec_createDecoderByType(mime);  // raw create (allocation; low-risk)
                strncpy(g_decoderMime, mime, 63); g_decoderMime[63] = 0;
            }



            if (!g_decoderCodec) { AMediaFormat_delete(format); LOGE("Incoming(A): createDecoder failed"); return false; }



            AMediaFormat* cfg = AMediaFormat_new();



            {



                const char* m = nullptr;



                AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &m);



                if (m) AMediaFormat_setString(cfg, AMEDIAFORMAT_KEY_MIME, m);



                int32_t sr2=0, ch2=0; int64_t dur=0;



                AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr2);



                AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch2);



                AMediaFormat_getInt64(format, AMEDIAFORMAT_KEY_DURATION, &dur);



                if (dur > 0) g_cachedDurationUs.store(dur);



                if (sr2 > 0) { g_fileSampleRate.store(sr2); }  // 【Crossfade 重采样】记录 A 槽采样率（incoming 守卫用）



                if (sr2>0) AMediaFormat_setInt32(cfg, AMEDIAFORMAT_KEY_SAMPLE_RATE, sr2);



                if (ch2>0) AMediaFormat_setInt32(cfg, AMEDIAFORMAT_KEY_CHANNEL_COUNT, ch2);



                if (dur>0) AMediaFormat_setInt64(cfg, AMEDIAFORMAT_KEY_DURATION, dur);



            }



            // 【V8.239 WAV 杂音根治】incoming A 也读 extractor 源编码（同 A 轨主路径）



            {



                int srcEnc = (mime && strcmp(mime, "audio/raw") == 0) ? readSourcePcmEncoding(format) : 0;



                if (srcEnc) {



                    g_decoderSourceEnc.store(srcEnc);



                    g_decoderHasSourceEnc.store(true);



                    g_decoderOutputEncoding.store(srcEnc);



                    g_decoderIsFloat.store(srcEnc == 4 || srcEnc == 22);



                    LOGI("NDK Decoder A(incoming): source pcm-encoding=%d (from extractor, raw/WAV)", srcEnc);



                } else {



                    g_decoderHasSourceEnc.store(false);



                }



            }



            copyCsdBuffers(cfg, format);



            // 【2026-09-02 fix】A 轨与 B 轨对称：pcm-encoding=2 被部分 AAC 解码器拒绝时



            // 去掉 pcm-encoding 重试（此前 A 轨缺失兜底 → crossfade 预加载 AAC m4a 失败）



            AMediaFormat_setInt32(cfg, "pcm-encoding", 2);



            media_status_t s = codecCallTimed("Incoming(A) configure", 2500, [cfg]() {
                return AMediaCodec_configure(g_decoderCodec, cfg, nullptr, nullptr, 0);
            });



            /* [V8.400] timeout: worker still holds cfg/format -> must NOT delete. */
            if (s == kCodecTimeout) { LOGE("Incoming(A): configure timed out -> abandon open"); g_decoderCodecPoisoned.store(true); return false; }
            if (s != AMEDIA_OK) {



                LOGE("Incoming(A): configure(pcm16) failed %d, retry without pcm-encoding", s);



                AMediaFormat* cfg2 = AMediaFormat_new();



                const char* m2 = nullptr;



                AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &m2);



                if (m2) AMediaFormat_setString(cfg2, AMEDIAFORMAT_KEY_MIME, m2);



                int32_t sr2a = 0, ch2a = 0; int64_t dur2a = 0;



                AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr2a);



                AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch2a);



                AMediaFormat_getInt64(format, AMEDIAFORMAT_KEY_DURATION, &dur2a);



                if (sr2a > 0) AMediaFormat_setInt32(cfg2, AMEDIAFORMAT_KEY_SAMPLE_RATE, sr2a);



                if (ch2a > 0) AMediaFormat_setInt32(cfg2, AMEDIAFORMAT_KEY_CHANNEL_COUNT, ch2a);



                if (dur2a > 0) AMediaFormat_setInt64(cfg2, AMEDIAFORMAT_KEY_DURATION, dur2a);



                copyCsdBuffers(cfg2, format);



                g_decoderOpStage.store(5);
                s = codecCallTimed("Incoming(A) configure-retry", 2500, [cfg2]() { return AMediaCodec_configure(g_decoderCodec, cfg2, nullptr, nullptr, 0); });



                AMediaFormat_delete(cfg2);



                if (s == kCodecTimeout) { LOGE("Incoming(A): configure(retry) timed out -> abandon open"); g_decoderCodecPoisoned.store(true); AMediaFormat_delete(cfg); AMediaFormat_delete(format); return false; }
                if (s != AMEDIA_OK) { LOGE("Incoming(A): configure(retry) failed %d", s); AMediaFormat_delete(cfg); AMediaFormat_delete(format); return false; }



            }



            AMediaFormat_delete(cfg);



            g_decoderOpStage.store(6);
            s = codecCallTimed("Incoming(A) start", 2500, []() {
                return AMediaCodec_start(g_decoderCodec);
            });



            /* [V8.399] timeout: the worker still holds `format`, so do NOT delete it. */
            if (s == kCodecTimeout) { LOGE("Incoming(A): start timed out -> abandon open"); g_decoderCodecPoisoned.store(true); return false; }
            if (s != AMEDIA_OK) { LOGE("Incoming(A): start failed %d", s); AMediaFormat_delete(format); return false; }



            AMediaFormat_delete(format);



            break;



        }



        AMediaFormat_delete(format);



    }



    if (g_alacModeA.load()) {



        AMediaExtractor_delete(g_decoderExtractor);



        g_decoderExtractor = nullptr;



        g_alacA = std::make_unique<AlacDirect>();



        if (!g_alacA->openFd(fd, offset, length)) {



            LOGE("Incoming(A): AlacDirect openFd failed");



            g_alacA.reset(); g_alacModeA.store(false);



            return false;



        }



        int64_t durUs = g_alacA->durationMs.load() * 1000LL;



        if (durUs > 0) g_cachedDurationUs.store(durUs);



        LOGI("Incoming(A): AlacDirect ready sr=%d ch=%d", g_alacA->sampleRate.load(), g_alacA->channels.load());



    } else if (g_decoderTrackIndex < 0) { LOGE("Incoming(A): no audio track"); return false; }



    // 【Crossfade 重采样】新歌进 A 槽，重置采样率与重采样状态



    g_sampleRateA.store(0);



    resetResamplerA();



    g_resampleLoggedA = false;



    // 【V8.293 解码器竞态修复】建 codec 与起解码线程必须在同一把 g_decoderLifecycleMutex 下原子完成



    // （本函数入口已持锁）：否则武装段在锁外起线程时，并发的 nativeStopIncoming/nativeOpen（持锁）



    // 会把刚建好的 codec 删掉，新线程在已释放对象上跑 → SIGSEGV（见 tombstone_31）。起线程放在此处



    // （锁内），与 openIncomingToB_internal 对称；武装段不再起线程。incoming 解码线程随 codec 一同



    // 启动并预填 ring（比原「武装段才起」更早有货，过渡头不空窗）。



    g_decoderStopRequested.store(false);



    g_decoderPaused.store(false);



    g_decoderEos.store(false);



    g_decoderRunning.store(true);



    if (g_alacModeA.load()) g_decoderThread = std::thread(alacDecodeLoopA);



    else g_decoderThread = std::thread(ndkDecodeLoop);



    LOGI("Incoming(A) decoder ready (FD), thread started");
    g_decoderOpStage.store(0);             // [V8.410] clear stage so watchdog won't see a stale open
    g_decoderCodecPoisoned.store(false);   // [V8.410] healthy codec after a clean open



    return true;



}







static bool openIncomingToB_internal(int fd, int64_t offset, int64_t length) {



    // 【V8.385】有界加锁（防「一次卡死拖死全部」）：
    // MediaCodec 实例耗尽时 AMediaCodec_start / AMediaCodec_configure 会**永久阻塞**，
    // 而那一刻正持有 g_decoderLifecycleMutex → 之后每一次 openIncoming 都在这把锁上
    // 无限期排队，一个日志都不打。这就是「连切三四首后彻底切不动」的真凶。
    // 改为限时获取：拿不到锁立刻返回 false，让上层走硬切兜底，不再堵死后续切歌。
    std::unique_lock<std::timed_mutex> lifecycleLock(g_decoderLifecycleMutex, std::defer_lock);
    if (!lifecycleLock.try_lock_for(std::chrono::milliseconds(1500))) {
        LOGE("Incoming(B): lifecycle lock busy (previous open stuck) -> give up this round"
             " [owner tid=%ld stage=%d held=%ldms]",
             g_decoderOpTid.load(), g_decoderOpStage.load(),
             (long)(decoderNowMs() - g_decoderOpSinceMs.load()));
        return false;
    }
    g_decoderOpTid.store((long)std::hash<std::thread::id>{}(std::this_thread::get_id()));
    g_decoderOpSinceMs.store(decoderNowMs());
    g_decoderOpStage.store(1);
    g_decoderOpDeck.store(2);  // [V8.410] this open targets deck B



    g_decoderStopRequestedB.store(true);



    g_decoderCvB.notify_all();



    /* [V8.395] stop MUST precede join: the decoder thread may be parked inside
       AMediaCodec_dequeueOutputBuffer(), and only AMediaCodec_stop() can wake it.
       The old order (join then stop) deadlocked whenever that happened, while
       holding the lifecycle lock -> every later open piled up on the mutex. */
    g_decoderOpStage.store(2);
    if (g_decoderCodecB) { codecCallTimed("Incoming(B) stop(old)", 2500, []() { return AMediaCodec_stop(g_decoderCodecB); }); }
    g_decoderOpStage.store(1);
    if (!joinDecoderTimed(g_decoderThreadB, "Incoming(B) decoder", 2500)) {
        /* The decoder thread is unkillable and the codec state is untrustworthy.
           Continuing would only produce a very late, stale crossfade; and because
           this thread never finishes, Kotlin would keep judging every later cut as
           "previous open stuck" and hard-cut them all. Abort now so the thread
           ends cleanly and the next cut gets a real chance. */
        LOGE("Incoming(B): decoder thread abandoned -> abort this open");
        g_decoderOpStage.store(0);
        return false;
    }



    if (g_decoderCodecB) { codecCallTimed("Incoming(B) stop(old)", 2500, []() { return AMediaCodec_stop(g_decoderCodecB); }); } /* V8.392 keep codec for reuse */



    if (g_decoderExtractorB) { codecCallVoid("Incoming(B) extractor_delete", 2000, []() { AMediaExtractor_delete(g_decoderExtractorB); }); g_decoderExtractorB = nullptr; }



    if (!g_ringBufferB) g_ringBufferB = new PCMRingBuffer(kRingBufferCapacity);



    else g_ringBufferB->clear();







    g_decoderHasSourceEncB.store(false);   // 【V8.239】B 轨源编码标志随曲目重置



    g_decoderIsFloatB.store(false);

    resetResamplerB();  // 【Crossfade 重采样】新歌解码开始清零重采样相位/历史（与 seek 对称）：防不同采样率曲目交叉淡化时旧状态错位、首块产出垃圾出白噪








    g_decoderExtractorB = AMediaExtractor_new();



    if (!g_decoderExtractorB) { LOGE("Incoming(B): extractor new failed"); return false; }



    media_status_t r = codecCallTimed("Incoming(B) setDataSourceFd", 3000, [fd, offset, length]() {
        return AMediaExtractor_setDataSourceFd(g_decoderExtractorB, fd, offset, length);
    });



    if (r == kCodecTimeout) { LOGE("Incoming(B): setDataSourceFd timed out -> abandon open"); g_decoderCodecPoisonedB.store(true); return false; }
    if (r != AMEDIA_OK) { LOGE("Incoming(B): setDataSourceFd failed %d", r); AMediaExtractor_delete(g_decoderExtractorB); g_decoderExtractorB = nullptr; return false; }







    size_t numTracks = AMediaExtractor_getTrackCount(g_decoderExtractorB);



    g_decoderTrackIndexB = -1;



    for (size_t i = 0; i < numTracks; i++) {



        AMediaFormat* format = AMediaExtractor_getTrackFormat(g_decoderExtractorB, i);



        if (!format) continue;



        const char* mime = nullptr;



        AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime);



        if (mime && strncmp(mime, "audio/", 6) == 0) {



            g_decoderTrackIndexB = (int)i;



            // 【ALAC】MediaCodec 无 ALAC 解码器 → 直解引擎



            if (mime && strcmp(mime, "audio/alac") == 0) {



                g_alacModeB.store(true);



                AMediaFormat_delete(format);



                LOGI("Incoming(B): ALAC detected, using AlacDirect");



                break;



            }



            AMediaExtractor_selectTrack(g_decoderExtractorB, g_decoderTrackIndexB);



            if (g_decoderCodecPoisonedB.load()) {
                codecCallVoid("Incoming(B) delete(old)", 2500, []() { AMediaCodec_delete(g_decoderCodecB); });
                g_decoderCodecB = nullptr;
                g_decoderCodecPoisonedB.store(false);
            }
            g_decoderCodecB = AMediaCodec_createDecoderByType(mime);  // raw create (allocation; low-risk)



            if (!g_decoderCodecB) { AMediaFormat_delete(format); LOGE("Incoming(B): createDecoder failed"); return false; }



            AMediaFormat* cfg = AMediaFormat_new();



            {



                const char* m = nullptr;



                AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &m);



                if (m) AMediaFormat_setString(cfg, AMEDIAFORMAT_KEY_MIME, m);



                int32_t sr2=0, ch2=0; int64_t dur=0;



                AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr2);



                AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch2);



                AMediaFormat_getInt64(format, AMEDIAFORMAT_KEY_DURATION, &dur);



                if (dur > 0) g_cachedDurationB.store(dur);



                if (sr2>0) AMediaFormat_setInt32(cfg, AMEDIAFORMAT_KEY_SAMPLE_RATE, sr2);



                if (ch2>0) AMediaFormat_setInt32(cfg, AMEDIAFORMAT_KEY_CHANNEL_COUNT, ch2);



                if (dur>0) AMediaFormat_setInt64(cfg, AMEDIAFORMAT_KEY_DURATION, dur);



            }



            // 【V8.239 WAV 杂音根治】incoming B 读 extractor 源编码（同 B 轨解码循环）



            {



                int srcEnc = (mime && strcmp(mime, "audio/raw") == 0) ? readSourcePcmEncoding(format) : 0;



                if (srcEnc) {



                    g_decoderSourceEncB.store(srcEnc);



                    g_decoderHasSourceEncB.store(true);



                    g_decoderOutputEncodingB.store(srcEnc);



                    g_decoderIsFloatB.store(srcEnc == 4 || srcEnc == 22);



                    LOGI("NDK Decoder B(incoming): source pcm-encoding=%d (from extractor, raw/WAV)", srcEnc);



                } else {



                    g_decoderHasSourceEncB.store(false);



                }



            }



            copyCsdBuffers(cfg, format);



            // 【2026-09-02 fix】B 轨与 A 轨对称：pcm-encoding=2 被部分 AAC 解码器拒绝时



            // 去掉 pcm-encoding 重试（A 轨 nativeOpenFd 已有 format2 兜底，B 轨此前缺失



            // → crossfade 切到 AAC m4a 时 B 轨 configure 失败 → 切歌无声/失败）



            AMediaFormat_setInt32(cfg, "pcm-encoding", 2);



            media_status_t s = codecCallTimed("Incoming(B) configure", 2500, [cfg]() {
                return AMediaCodec_configure(g_decoderCodecB, cfg, nullptr, nullptr, 0);
            });



            /* [V8.400] timeout: worker still holds cfg/format -> must NOT delete. */
            if (s == kCodecTimeout) { LOGE("Incoming(B): configure timed out -> abandon open"); g_decoderCodecPoisonedB.store(true); return false; }
            if (s != AMEDIA_OK) {



                LOGE("Incoming(B): configure(pcm16) failed %d, retry without pcm-encoding", s);



                AMediaFormat* cfg2 = AMediaFormat_new();



                const char* m2 = nullptr;



                AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &m2);



                if (m2) AMediaFormat_setString(cfg2, AMEDIAFORMAT_KEY_MIME, m2);



                int32_t sr2b = 0, ch2b = 0; int64_t dur2b = 0;



                AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr2b);



                AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch2b);



                AMediaFormat_getInt64(format, AMEDIAFORMAT_KEY_DURATION, &dur2b);



                if (sr2b > 0) AMediaFormat_setInt32(cfg2, AMEDIAFORMAT_KEY_SAMPLE_RATE, sr2b);



                if (ch2b > 0) AMediaFormat_setInt32(cfg2, AMEDIAFORMAT_KEY_CHANNEL_COUNT, ch2b);



                if (dur2b > 0) AMediaFormat_setInt64(cfg2, AMEDIAFORMAT_KEY_DURATION, dur2b);



                copyCsdBuffers(cfg2, format);



                g_decoderOpStage.store(5);
                s = codecCallTimed("Incoming(B) configure-retry", 2500, [cfg2]() { return AMediaCodec_configure(g_decoderCodecB, cfg2, nullptr, nullptr, 0); });



                AMediaFormat_delete(cfg2);



                if (s == kCodecTimeout) { LOGE("Incoming(B): configure(retry) timed out -> abandon open"); g_decoderCodecPoisonedB.store(true); AMediaFormat_delete(cfg); AMediaFormat_delete(format); return false; }
                if (s != AMEDIA_OK) { LOGE("Incoming(B): configure(retry) failed %d", s); AMediaFormat_delete(cfg); AMediaFormat_delete(format); return false; }



            }



            AMediaFormat_delete(cfg);



            g_decoderOpStage.store(6);
            s = codecCallTimed("Incoming(B) start", 2500, []() {
                return AMediaCodec_start(g_decoderCodecB);
            });



            /* [V8.399] timeout: the worker still holds `format`, so do NOT delete it. */
            if (s == kCodecTimeout) { LOGE("Incoming(B): start timed out -> abandon open"); g_decoderCodecPoisonedB.store(true); return false; }
            if (s != AMEDIA_OK) { LOGE("Incoming(B): start failed %d", s); AMediaFormat_delete(format); return false; }



            AMediaFormat_delete(format);



            break;



        }



        AMediaFormat_delete(format);



    }



    if (g_alacModeB.load()) {



        AMediaExtractor_delete(g_decoderExtractorB);



        g_decoderExtractorB = nullptr;



        g_alacB = std::make_unique<AlacDirect>();



        if (!g_alacB->openFd(fd, offset, length)) {



            LOGE("Incoming(B): AlacDirect openFd failed");



            g_alacB.reset(); g_alacModeB.store(false);



            return false;



        }



        int64_t durUs = g_alacB->durationMs.load() * 1000LL;



        if (durUs > 0) g_cachedDurationB.store(durUs);



        LOGI("Incoming(B): AlacDirect ready sr=%d ch=%d", g_alacB->sampleRate.load(), g_alacB->channels.load());



    } else if (g_decoderTrackIndexB < 0) { LOGE("Incoming(B): no audio track"); return false; }



    // 【Crossfade 重采样】新歌进 B 槽，重置采样率与重采样状态



    g_sampleRateB.store(0);



    g_channelCountB.store(2);



    resetResamplerB();



    g_resampleLoggedB = false;



    // 【V8.292 解码器竞态修复】建 codec 与起解码线程必须在同一把 g_decoderLifecycleMutex 下原子完成



    // （本函数入口已持锁）：否则武装段在锁外起线程时，并发的 nativeStopIncoming/nativeOpen（持锁）



    // 会把刚建好的 codec 删掉，新线程在已释放对象上跑 → SIGSEGV（见 tombstone_31）。起线程放在此处



    // （锁内），与 openIncomingToA_internal 对称；武装段不再起线程。incoming 解码线程随 codec 一同



    // 启动并预填 ring（比原「武装段才起」更早有货，过渡头不空窗）。



    g_decoderStopRequestedB.store(false);



    g_decoderPausedB.store(false);



    g_decoderEosB.store(false);



    g_decoderRunningB.store(true);



    if (g_alacModeB.load()) g_decoderThreadB = std::thread(alacDecodeLoopB);



    else g_decoderThreadB = std::thread(ndkDecodeLoopB);



    LOGI("Incoming(B) decoder ready (FD), thread started");
    g_decoderOpStage.store(0);             // [V8.410] clear stage so watchdog won't see a stale open
    g_decoderCodecPoisonedB.store(false);  // [V8.410] healthy codec after a clean open



    return true;



}







// 【V8.9 Crossfade 优化①】预扫描：轻量解码 B 轨尾部，找最长低能量段作为精确交叉点



// 独立 extractor/codec（不复用 B 槽解码器），扫完即弃；返回 B 轨尾部长低能量段起点(ms)，找不到返回 -1



static int preScanTailQuietMs(int fd, int64_t length, int windowMs, int64_t seekFromMs) {



    if (fd < 0) return -1;



    AMediaExtractor* ex = AMediaExtractor_new();



    if (!ex) return -1;



    media_status_t r = AMediaExtractor_setDataSourceFd(ex, fd, 0, length);



    if (r != AMEDIA_OK) { AMediaExtractor_delete(ex); return -1; }



    int trackIdx = -1;



    int32_t sr = 44100, ch = 2; int64_t dur = 0;



    size_t n = AMediaExtractor_getTrackCount(ex);



    for (size_t i = 0; i < n; i++) {



        AMediaFormat* f = AMediaExtractor_getTrackFormat(ex, (size_t)i);



        if (!f) continue;



        const char* mime = nullptr;



        AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &mime);



        if (mime && strncmp(mime, "audio/", 6) == 0) {



            trackIdx = (int)i;



            AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr);



            AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);



            AMediaFormat_getInt64(f, AMEDIAFORMAT_KEY_DURATION, &dur);



            AMediaFormat_delete(f);



            break;



        }



        AMediaFormat_delete(f);



    }



    if (trackIdx < 0) { AMediaExtractor_delete(ex); return -1; }



    AMediaExtractor_selectTrack(ex, trackIdx);



    AMediaCodec* codec = g_scanCodec;   /* V8.394 reuse the shared scan codec */



    {



        AMediaFormat* f = AMediaExtractor_getTrackFormat(ex, trackIdx);



        const char* mime = nullptr;



        AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &mime);



        /* [V8.394] same mime -> stop+flush and reuse; different mime -> full recreate. */
        if (mime) {
            if (codec && strncmp(g_scanMime, mime, 63) == 0) {
                AMediaCodec_stop(codec);
                AMediaCodec_flush(codec);
            } else {
                if (codec) { AMediaCodec_stop(codec); AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ codec = nullptr; }
                codec = AMediaCodec_createDecoderByType(mime);
                if (codec) { strncpy(g_scanMime, mime, 63); g_scanMime[63] = 0; g_scanCodec = codec; }
            }
        }



        AMediaFormat_delete(f);



    }



    if (!codec) { AMediaExtractor_delete(ex); return -1; }



    AMediaFormat* cfg = AMediaFormat_new();



    if (sr > 0) AMediaFormat_setInt32(cfg, AMEDIAFORMAT_KEY_SAMPLE_RATE, sr);



    if (ch > 0) AMediaFormat_setInt32(cfg, AMEDIAFORMAT_KEY_CHANNEL_COUNT, ch);



    AMediaFormat_setInt32(cfg, "pcm-encoding", 2);



    if (AMediaCodec_configure(codec, cfg, nullptr, nullptr, 0) != AMEDIA_OK) {



        AMediaFormat_delete(cfg); AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ AMediaExtractor_delete(ex); return -1;



    }



    AMediaFormat_delete(cfg);



    if (AMediaCodec_start(codec) != AMEDIA_OK) {



        AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ AMediaExtractor_delete(ex); return -1;



    }







    // 跳到扫描起点：A 轨从 seekFromMs（当前进度）开始，B 轨尾部从 totalMs-SCAN_MS



    // 扫描窗口固定 SCAN_MS=12000，从起点到结尾（或起点+SCAN_MS）



    const int64_t SCAN_MS = 12000;



    int64_t totalMs = dur / 1000;



    int64_t scanStartMs;



    if (seekFromMs > 0) {



        scanStartMs = seekFromMs;  // A 轨：当前进度起扫到结尾



    } else {



        scanStartMs = totalMs > SCAN_MS ? totalMs - SCAN_MS : 0;  // 原逻辑（B 尾部模式）



    }



    if (scanStartMs >= totalMs) scanStartMs = totalMs > 100 ? totalMs - 100 : 0;



    AMediaExtractor_seekTo(ex, scanStartMs * 1000, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);







    const int wFrames = std::max(1, (sr * windowMs) / 1000);



    const int maxBuckets = 4096;



    float rmsEnv[maxBuckets];



    int envCount = 0;



    int64_t firstTs = -1, lastTs = -1;



    bool inputEos = false;



    // 解码尾部



    for (int guard = 0; guard < 200000; guard++) {



        if (!inputEos) {



            ssize_t inIdx = AMediaCodec_dequeueInputBuffer(codec, 2000);



            if (inIdx >= 0) {



                size_t inSize = 0;



                uint8_t* inBuf = AMediaCodec_getInputBuffer(codec, inIdx, &inSize);



                if (inBuf && inSize > 0) {



                    ssize_t sz = AMediaExtractor_readSampleData(ex, inBuf, inSize);



                    if (sz < 0) {



                        AMediaCodec_queueInputBuffer(codec, inIdx, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);



                        inputEos = true;



                    } else {



                        int64_t t = AMediaExtractor_getSampleTime(ex);



                        if (firstTs < 0) firstTs = t;



                        AMediaCodec_queueInputBuffer(codec, inIdx, 0, (size_t)sz, t, 0);



                        AMediaExtractor_advance(ex);



                    }



                } else {



                    AMediaCodec_queueInputBuffer(codec, inIdx, 0, 0, 0, 0);



                }



            }



        }



        AMediaCodecBufferInfo info;



        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec, &info, 2000);



        if (outIdx >= 0) {



            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {



                AMediaCodec_releaseOutputBuffer(codec, outIdx, false);



                break;



            }



            size_t outSz = 0;



            uint8_t* outBuf = AMediaCodec_getOutputBuffer(codec, (size_t)outIdx, &outSz);



            if (outBuf && outSz > 0) {



                int samples = info.size / (int)sizeof(int16_t);



                float sum = 0.0f;



                int cnt = 0;



                for (int s = 0; s + 1 < samples; s += 2) {



                    int16_t v = (int16_t)((outBuf[s] & 0xff) | (outBuf[s+1] << 8));



                    float fv = v / 32768.0f;



                    sum += fv * fv; cnt++;



                }



                if (cnt > 0) {



                    float rms = std::sqrt(sum / cnt);



                    if (envCount < maxBuckets) rmsEnv[envCount++] = rms;



                }



                lastTs = info.presentationTimeUs;



            }



            AMediaCodec_releaseOutputBuffer(codec, outIdx, false);



        } else if (outIdx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {



            if (inputEos) break;



        }



    }



    AMediaCodec_stop(codec); AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ AMediaExtractor_delete(ex);



    if (envCount < 4) return -1;







    // 找最长连续低能量段：RMS < 局部峰值的 35%



    float peak = 0.0f;



    for (int i = 0; i < envCount; i++) peak = std::max(peak, rmsEnv[i]);



    if (peak < 1e-4f) return -1;



    int bestStart = -1, bestLen = 0, curStart = -1, curLen = 0;



    for (int i = 0; i < envCount; i++) {



        if (rmsEnv[i] < peak * 0.35f) {



            if (curStart < 0) curStart = i;



            curLen++;



            if (curLen > bestLen) { bestLen = curLen; bestStart = curStart; }



        } else { curStart = -1; curLen = 0; }



    }



    if (bestStart < 0 || bestLen < 2) return -1;



    // 段起点映射到 B 轨时间戳(ms)



    int64_t bucketMs = (int64_t)bestStart * windowMs;



    int64_t quietStartMs = scanStartMs + bucketMs;



    return (int)(quietStartMs > 0 ? quietStartMs : 0);



}







// 【V8.9 双层预扫描②】B 轨开头前奏低能量段：扫文件开头，返回前奏静音段长度(ms)



// 语义：新歌 fade in 时若前奏安静，交叉窗口可从容对齐；返回 -1=无静音前奏



static int preScanIntroQuietMs(int fd, int64_t length, int windowMs) {



    if (fd < 0) return -1;



    AMediaExtractor* ex = AMediaExtractor_new();



    if (!ex) return -1;



    media_status_t r = AMediaExtractor_setDataSourceFd(ex, fd, 0, length);



    if (r != AMEDIA_OK) { AMediaExtractor_delete(ex); return -1; }



    int trackIdx = -1;



    int32_t sr = 44100, ch = 2;



    size_t n = AMediaExtractor_getTrackCount(ex);



    for (size_t i = 0; i < n; i++) {



        AMediaFormat* f = AMediaExtractor_getTrackFormat(ex, (size_t)i);



        if (!f) continue;



        const char* mime = nullptr;



        AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &mime);



        if (mime && strncmp(mime, "audio/", 6) == 0) {



            trackIdx = (int)i;



            AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr);



            AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);



            AMediaFormat_delete(f);



            break;



        }



        AMediaFormat_delete(f);



    }



    if (trackIdx < 0) { AMediaExtractor_delete(ex); return -1; }



    AMediaExtractor_selectTrack(ex, trackIdx);



    AMediaCodec* codec = g_scanCodec;   /* V8.394 reuse the shared scan codec */



    {



        AMediaFormat* f = AMediaExtractor_getTrackFormat(ex, trackIdx);



        const char* mime = nullptr;



        AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &mime);



        /* [V8.394] same mime -> stop+flush and reuse; different mime -> full recreate. */
        if (mime) {
            if (codec && strncmp(g_scanMime, mime, 63) == 0) {
                AMediaCodec_stop(codec);
                AMediaCodec_flush(codec);
            } else {
                if (codec) { AMediaCodec_stop(codec); AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ codec = nullptr; }
                codec = AMediaCodec_createDecoderByType(mime);
                if (codec) { strncpy(g_scanMime, mime, 63); g_scanMime[63] = 0; g_scanCodec = codec; }
            }
        }



        AMediaFormat_delete(f);



    }



    if (!codec) { AMediaExtractor_delete(ex); return -1; }



    AMediaFormat* cfg = AMediaFormat_new();



    if (sr > 0) AMediaFormat_setInt32(cfg, AMEDIAFORMAT_KEY_SAMPLE_RATE, sr);



    if (ch > 0) AMediaFormat_setInt32(cfg, AMEDIAFORMAT_KEY_CHANNEL_COUNT, ch);



    AMediaFormat_setInt32(cfg, "pcm-encoding", 2);



    if (AMediaCodec_configure(codec, cfg, nullptr, nullptr, 0) != AMEDIA_OK) {



        AMediaFormat_delete(cfg); AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ AMediaExtractor_delete(ex); return -1;



    }



    AMediaFormat_delete(cfg);



    if (AMediaCodec_start(codec) != AMEDIA_OK) {



        AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ AMediaExtractor_delete(ex); return -1;



    }



    // 只扫开头 INTRO_MS=6000（前奏区），找最长连续低能量段



    const int64_t INTRO_MS = 6000;



    const int wFrames = std::max(1, (sr * windowMs) / 1000);



    const int maxBuckets = 512;



    float rmsEnv[maxBuckets];



    int envCount = 0;



    int64_t lastTs = -1;



    bool inputEos = false;



    for (int guard = 0; guard < 50000; guard++) {



        if (!inputEos) {



            ssize_t inIdx = AMediaCodec_dequeueInputBuffer(codec, 2000);



            if (inIdx >= 0) {



                size_t inSize = 0;



                uint8_t* inBuf = AMediaCodec_getInputBuffer(codec, inIdx, &inSize);



                if (inBuf && inSize > 0) {



                    ssize_t sz = AMediaExtractor_readSampleData(ex, inBuf, inSize);



                    if (sz < 0) {



                        AMediaCodec_queueInputBuffer(codec, inIdx, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);



                        inputEos = true;



                    } else {



                        int64_t t = AMediaExtractor_getSampleTime(ex);



                        AMediaCodec_queueInputBuffer(codec, inIdx, 0, (size_t)sz, t, 0);



                        AMediaExtractor_advance(ex);



                    }



                } else {



                    AMediaCodec_queueInputBuffer(codec, inIdx, 0, 0, 0, 0);



                }



            }



        }



        AMediaCodecBufferInfo info;



        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec, &info, 2000);



        if (outIdx >= 0) {



            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {



                AMediaCodec_releaseOutputBuffer(codec, outIdx, false);



                break;



            }



            size_t outSz = 0;



            uint8_t* outBuf = AMediaCodec_getOutputBuffer(codec, (size_t)outIdx, &outSz);



            if (outBuf && outSz > 0) {



                int samples = info.size / (int)sizeof(int16_t);



                float sum = 0.0f;



                int cnt = 0;



                for (int s = 0; s + 1 < samples; s += 2) {



                    int16_t v = (int16_t)((outBuf[s] & 0xff) | (outBuf[s+1] << 8));



                    float fv = v / 32768.0f;



                    sum += fv * fv; cnt++;



                }



                if (cnt > 0) {



                    float rms = std::sqrt(sum / cnt);



                    if (envCount < maxBuckets) rmsEnv[envCount++] = rms;



                }



                lastTs = info.presentationTimeUs;



            }



            AMediaCodec_releaseOutputBuffer(codec, outIdx, false);



        } else if (outIdx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {



            if (inputEos) break;



        }



        // 已扫够 INTRO_MS 就停



        if (lastTs > 0 && (lastTs / 1000) >= INTRO_MS) break;



    }



    AMediaCodec_stop(codec); AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ AMediaExtractor_delete(ex);



    if (envCount < 2) return -1;



    // 找开头连续低能量段（从前到后，第一个低于峰值的连续段）



    float peak = 0.0f;



    for (int i = 0; i < envCount; i++) peak = std::max(peak, rmsEnv[i]);



    if (peak < 1e-4f) return -1;



    int introMs = 0;



    for (int i = 0; i < envCount; i++) {



        if (rmsEnv[i] < peak * 0.35f) {



            introMs += windowMs;



        } else {



            break;  // 一旦强音进入，前奏结束



        }



    }



    return introMs >= windowMs * 2 ? introMs : -1;



}







// 【V8.15】预扫描③：B 轨副歌起点



// 扫文件 15~120s 区间（跳过前奏），找能量最高的 10s 窗口，返回其起点(ms)



// 语义：crossfade 时 B 从副歌直接进（跳过前奏），一进来就是高潮；找不到返回 -1



static int preScanChorusMs(int fd, int64_t length, int windowMs, int bpm) {



    if (fd < 0) return -1;



    AMediaExtractor* ex = AMediaExtractor_new();



    if (!ex) return -1;



    media_status_t r = AMediaExtractor_setDataSourceFd(ex, fd, 0, length);



    



    if (r != AMEDIA_OK) { AMediaExtractor_delete(ex); return -1; }



    int trackIdx = -1;



    int32_t sr = 44100, ch = 2; int64_t dur = 0;



    size_t n = AMediaExtractor_getTrackCount(ex);



    for (size_t i = 0; i < n; i++) {



        AMediaFormat* f = AMediaExtractor_getTrackFormat(ex, (size_t)i);



        if (!f) continue;



        const char* mime = nullptr;



        AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &mime);



        if (mime && strncmp(mime, "audio/", 6) == 0) {



            trackIdx = (int)i;



            AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr);



            AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);



            AMediaFormat_getInt64(f, AMEDIAFORMAT_KEY_DURATION, &dur);



            AMediaFormat_delete(f);



            break;



        }



        AMediaFormat_delete(f);



    }



    if (trackIdx < 0) {  AMediaExtractor_delete(ex); return -1; }



    



    AMediaExtractor_selectTrack(ex, trackIdx);



    AMediaCodec* codec = g_scanCodec;   /* V8.394 reuse the shared scan codec */



    {



        AMediaFormat* f = AMediaExtractor_getTrackFormat(ex, trackIdx);



        const char* mime = nullptr;



        AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &mime);



        /* [V8.394] same mime -> stop+flush and reuse; different mime -> full recreate. */
        if (mime) {
            if (codec && strncmp(g_scanMime, mime, 63) == 0) {
                AMediaCodec_stop(codec);
                AMediaCodec_flush(codec);
            } else {
                if (codec) { AMediaCodec_stop(codec); AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ codec = nullptr; }
                codec = AMediaCodec_createDecoderByType(mime);
                if (codec) { strncpy(g_scanMime, mime, 63); g_scanMime[63] = 0; g_scanCodec = codec; }
            }
        }



        AMediaFormat_delete(f);



    }



    if (!codec) {  AMediaExtractor_delete(ex); return -1; }



    AMediaFormat* cfg = AMediaFormat_new();



    {



        AMediaFormat* f = AMediaExtractor_getTrackFormat(ex, trackIdx);



        const char* m = nullptr;



        AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &m);



        if (m) AMediaFormat_setString(cfg, AMEDIAFORMAT_KEY_MIME, m);



        int32_t sr2 = 0, ch2 = 0;



        AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr2);



        AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch2);



        if (sr2 > 0) AMediaFormat_setInt32(cfg, AMEDIAFORMAT_KEY_SAMPLE_RATE, sr2);



        if (ch2 > 0) AMediaFormat_setInt32(cfg, AMEDIAFORMAT_KEY_CHANNEL_COUNT, ch2);



        copyCsdBuffers(cfg, f);



        AMediaFormat_delete(f);



    }



    AMediaFormat_setInt32(cfg, "pcm-encoding", 2);



    media_status_t cfgR = AMediaCodec_configure(codec, cfg, nullptr, nullptr, 0);



    



    if (cfgR != AMEDIA_OK) {



        AMediaFormat_delete(cfg); AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ AMediaExtractor_delete(ex); return -1;



    }



    AMediaFormat_delete(cfg);



    if (AMediaCodec_start(codec) != AMEDIA_OK) {



        



        AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ AMediaExtractor_delete(ex); return -1;



    }







    // 【V8.312·B】扫描窗跟曲长自适应。



    // 旧代码上限硬编码 120s：>4 分钟的长曲第一个副歌常在 150~240s，落在窗外 →



    // 检测器被迫在窗里挑"最像副歌"的点，而那通常是中后段的能量回升处 → B 被接到快结尾处。



    // 新规则：扫 [15s, min(曲长×60%, 300s)]。60% 留足剩余内容（DJ 切歌至少要还有



    // 一段可播），300s 封顶避免超长曲（interlude 巨长）扫太久。



    int64_t totalMs = dur / 1000;



    int64_t scanFromMs = 15000;



    int64_t scanCapMs = totalMs * 3 / 5;                 // 曲长的 60%



    if (scanCapMs > 300000) scanCapMs = 300000;         // 封顶 300s



    int64_t scanToMs = totalMs > scanCapMs ? scanCapMs : totalMs;



    if (scanToMs > scanFromMs + 5000 && scanToMs < totalMs) {



        LOGI("Chorus scan window: %lldms..%lldms (song=%lldms)",



             (long long)scanFromMs, (long long)scanToMs, (long long)totalMs);



    }



    



    if (scanToMs <= scanFromMs + 5000) {  AMediaCodec_stop(codec); AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ AMediaExtractor_delete(ex); return -1; }



    media_status_t seekR = AMediaExtractor_seekTo(ex, scanFromMs * 1000, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);







    const int wFrames = std::max(1, (sr * windowMs) / 1000);



    const int maxBuckets = 4096;



    float rmsEnv[maxBuckets];



    int envCount = 0;



    bool inputEos = false;



    for (int guard = 0; guard < 200000; guard++) {



        if (!inputEos) {



            ssize_t inIdx = AMediaCodec_dequeueInputBuffer(codec, 2000);



            if (inIdx >= 0) {



                size_t inSize = 0;



                uint8_t* inBuf = AMediaCodec_getInputBuffer(codec, inIdx, &inSize);



                if (inBuf && inSize > 0) {



                    ssize_t sz = AMediaExtractor_readSampleData(ex, inBuf, inSize);



                    if (sz < 0) {



                        AMediaCodec_queueInputBuffer(codec, inIdx, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);



                        inputEos = true;



                    } else {



                        int64_t t = AMediaExtractor_getSampleTime(ex);



                        AMediaCodec_queueInputBuffer(codec, inIdx, 0, (size_t)sz, t, 0);



                        AMediaExtractor_advance(ex);



                    }



                } else {



                    AMediaCodec_queueInputBuffer(codec, inIdx, 0, 0, 0, 0);



                }



            }



        }



        AMediaCodecBufferInfo info;



        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec, &info, 2000);



        if (outIdx >= 0) {



            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {



                AMediaCodec_releaseOutputBuffer(codec, outIdx, false);



                break;



            }



            size_t outSz = 0;



            uint8_t* outBuf = AMediaCodec_getOutputBuffer(codec, (size_t)outIdx, &outSz);



            if (outBuf && outSz > 0) {



                int samples = info.size / (int)sizeof(int16_t);



                float sum = 0.0f; int cnt = 0;



                for (int s = 0; s + 1 < samples; s += 2) {



                    int16_t v = (int16_t)((outBuf[s] & 0xff) | (outBuf[s+1] << 8));



                    float fv = v / 32768.0f;



                    sum += fv * fv; cnt++;



                }



                if (cnt > 0) {



                    float rms = std::sqrt(sum / cnt);



                    if (envCount < maxBuckets) rmsEnv[envCount++] = rms;



                }



            }



            AMediaCodec_releaseOutputBuffer(codec, outIdx, false);



        } else if (outIdx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {



            if (inputEos) break;



        }



    }



    AMediaCodec_stop(codec); AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ AMediaExtractor_delete(ex);



    



    if (envCount < 10) return -1;







    // 【V8.16 修正】找「第一个显著高能量段」（第一副歌），限制在 15s ~ 45% 歌长处



    // v8.15 用 60% 仍会落在后半（B 从 60% 进，播几十秒就切）；45% 保证 B 从前中段进



    const int kChorusWindows = std::max(1, 10000 / windowMs);  // 10s / 窗口



    // 【V8.17】limitBucket 必须减 scanFromMs 偏移（envCount 从 15s 起计数），否则 45% 实际变成 45%+15s



    // 且对 >2.6 分钟的歌 limitBucket 会被 envCount顶掉 → 全范围扫描 → B 轨进后半



    // 【V8.22 旧】选位范围：min(45% 歌长, 90s)。90s 绝对上限对**长曲**是灾难：



    //   5 分钟的歌只扫了前 30%，而长曲副歌常在 2~3 分钟处 → 扫不到真副歌，



    //   只能退而选「能量跃升点」或「最高段」，于是B 从 3 分钟处进（用户报"接歌接到下一曲3分钟以后"）。



    // 【V8.334 改为跟曲长自适应·治本】min(曲长 * 75%, 300s)：



    //   · 永远扫全曲的前 75%，长曲的副歌就落进窗内了；



    //   · 绝对上限放到 300s（5 分钟），避免超长曲（DJ 混音set 10分钟）真的从头扫到尾；



    //   · 下限仍保 10s 扫描窗，短歌行为不变。



    //   安全性：真正的「太靠后」由 Kotlin 侧 guardBEntryPoint 兜（45%比例 + 90s 绝对上限



    //   + 剩余量守卫），这里放宽扫描范围不会让 B 从 3 分钟进——只是让检测**看得见**真副歌。



    int64_t chorusLimitMs = totalMs * 75 / 100;



    if (chorusLimitMs > 300000) chorusLimitMs = 300000;



    if (chorusLimitMs < scanFromMs + 10000) chorusLimitMs = scanFromMs + 10000;  // 至少留 10s 扫描窗



    int limitBucket = (int)((chorusLimitMs - scanFromMs) / windowMs);



    if (limitBucket > envCount) limitBucket = envCount;



    if (limitBucket - kChorusWindows <= 0) limitBucket = std::min(envCount, 30);  // 极端短歌限 30 窗（~3s）



    // 【V8.22】局部能量跃升检测：找第一个「能量突升点」（副歌进入特征）



    // 之前用全局均值阈值（mean*1.3）对渐进式/全程响亮的歌无效 → 回退能量最高段（偏后）



    // 现在：窗口能量比前一个窗口高 25% 且后续 2 窗口不回落 → 视为副歌起点



    // 窗口先做 3 窗口滑动平均，抑制单窗口毛刺



    std::vector<float> winAvg;



    winAvg.reserve(envCount);



    for (int i = 0; i < envCount; i++) {



        float sum = 0.0f; int cnt = 0;



        for (int j = std::max(0, i - 1); j <= std::min(envCount - 1, i + 1); j++) { sum += rmsEnv[j]; cnt++; }



        winAvg.push_back(sum / cnt);



    }



    int bestStart = -1;



    // 找第一个能量跃升：avg[i] > avg[i-1] * 1.25 且后续 2 窗口维持高位



    for (int i = 1; i + kChorusWindows <= limitBucket; i++) {



        if (winAvg[i] > winAvg[i - 1] * 1.25f) {



            bool sustained = true;



            for (int j = 1; j <= 2 && i + j < (int)winAvg.size(); j++) {



                if (winAvg[i + j] < winAvg[i - 1] * 1.05f) { sustained = false; break; }



            }



            if (sustained) { bestStart = i; break; }



        }



    }



    // 没找到跃升 → 退回前 45% 内能量最高段（仍靠前）



    if (bestStart < 0) {



        float fallback = 0.0f;



        for (int i = 0; i + kChorusWindows <= limitBucket; i++) {



            float sum = 0.0f;



            for (int j = 0; j < kChorusWindows; j++) sum += rmsEnv[i + j];



            float avg = sum / kChorusWindows;



            if (avg > fallback) { fallback = avg; bestStart = i; }



        }



    }



    if (bestStart < 0) { LOGI("preScanChorus: no best window"); return -1; }



    int64_t chorusMs = scanFromMs + (int64_t)bestStart * windowMs;



    // 【V8.23 B 拍点对齐】chorusMs 附近 ±1 拍（按 ~120BPM ≈ 500ms，即 ±5 窗口@100ms）找 RMS 局部峰（kick）



    // 让 B 从副歌的强拍（通常是 kick 进入）起播，节拍衔接更自然



    {



        // 【V8.142】beat 窗口按真实 BPM（原写死 120）；BPM 未知回退 120



        int useBpm = (bpm >= 40 && bpm <= 220) ? bpm : 120;



        int beatWin = std::max(2, (60000 / useBpm) / windowMs);



        int center = bestStart;



        int lo = std::max(0, center - beatWin);



        int hi = std::min(envCount - 1, center + beatWin);



        int peakIdx = center;



        float peakVal = rmsEnv[center];



        for (int k = lo; k <= hi; k++) {



            if (rmsEnv[k] > peakVal) { peakVal = rmsEnv[k]; peakIdx = k; }



        }



        if (peakIdx != center) {



            chorusMs = scanFromMs + (int64_t)peakIdx * windowMs;



            LOGI("preScanChorus: beatAlign %d->%d chorusMs=%lld (peak=%.4f)", center, peakIdx, (long long)chorusMs, peakVal);



        }



    }



    // 【V8.24 B 音量预匹配】统计副歌段（bestStart 起 10 窗口）平均 RMS，供 crossfade 启动时 B 增益匹配



    {



        float sum = 0.0f; int cnt = 0;



        for (int j = 0; j < kChorusWindows && bestStart + j < envCount; j++) {



            sum += rmsEnv[bestStart + j]; cnt++;



        }



        float chorusRms = cnt > 0 ? sum / cnt : 0.0f;



        g_xfadeChorusRms.store(chorusRms, std::memory_order_relaxed);



        LOGI("preScanChorus: chorusRms=%.4f", chorusRms);



    }



    LOGI("preScanChorus: riseStart=%d chorusMs=%lld", bestStart, (long long)chorusMs);



    return (int)(chorusMs > 0 ? chorusMs : 0);



}







// 【V8.142 智能对齐】预扫描④：首个强拍(kick)位置(ms) —— 拍相位基准



// 语义：从 seekFromMs 起扫约 20s 包络，取首个达到峰值 60% 的窗口时间戳，视作该曲首拍。



//   A 轨用它把 crossfade 触发延迟到「真实拍点」而非 t=0 栅格；



//   B 轨用它把 seek 点对齐到自身拍栅格，实现 A/B 拍相位互锁。找不到返回 -1



static int preScanFirstBeatMs(int fd, int64_t length, int windowMs, int64_t seekFromMs) {



    if (fd < 0) return -1;



    AMediaExtractor* ex = AMediaExtractor_new();



    if (!ex) return -1;



    media_status_t r = AMediaExtractor_setDataSourceFd(ex, fd, 0, length);



    if (r != AMEDIA_OK) { AMediaExtractor_delete(ex); return -1; }



    int trackIdx = -1;



    int32_t sr = 44100, ch = 2; int64_t dur = 0;



    size_t n = AMediaExtractor_getTrackCount(ex);



    for (size_t i = 0; i < n; i++) {



        AMediaFormat* f = AMediaExtractor_getTrackFormat(ex, (size_t)i);



        if (!f) continue;



        const char* mime = nullptr;



        AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &mime);



        if (mime && strncmp(mime, "audio/", 6) == 0) {



            trackIdx = (int)i;



            AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr);



            AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);



            AMediaFormat_getInt64(f, AMEDIAFORMAT_KEY_DURATION, &dur);



            AMediaFormat_delete(f);



            break;



        }



        AMediaFormat_delete(f);



    }



    if (trackIdx < 0) { AMediaExtractor_delete(ex); return -1; }



    AMediaExtractor_selectTrack(ex, trackIdx);



    AMediaCodec* codec = g_scanCodec;   /* V8.394 reuse the shared scan codec */



    {



        AMediaFormat* f = AMediaExtractor_getTrackFormat(ex, trackIdx);



        const char* mime = nullptr;



        AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &mime);



        /* [V8.394] same mime -> stop+flush and reuse; different mime -> full recreate. */
        if (mime) {
            if (codec && strncmp(g_scanMime, mime, 63) == 0) {
                AMediaCodec_stop(codec);
                AMediaCodec_flush(codec);
            } else {
                if (codec) { AMediaCodec_stop(codec); AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ codec = nullptr; }
                codec = AMediaCodec_createDecoderByType(mime);
                if (codec) { strncpy(g_scanMime, mime, 63); g_scanMime[63] = 0; g_scanCodec = codec; }
            }
        }



        AMediaFormat_delete(f);



    }



    if (!codec) { AMediaExtractor_delete(ex); return -1; }



    AMediaFormat* cfg = AMediaFormat_new();



    {



        AMediaFormat* f = AMediaExtractor_getTrackFormat(ex, trackIdx);



        const char* m = nullptr;



        AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &m);



        if (m) AMediaFormat_setString(cfg, AMEDIAFORMAT_KEY_MIME, m);



        int32_t sr2 = 0, ch2 = 0;



        AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr2);



        AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch2);



        if (sr2 > 0) AMediaFormat_setInt32(cfg, AMEDIAFORMAT_KEY_SAMPLE_RATE, sr2);



        if (ch2 > 0) AMediaFormat_setInt32(cfg, AMEDIAFORMAT_KEY_CHANNEL_COUNT, ch2);



        copyCsdBuffers(cfg, f);



        AMediaFormat_delete(f);



    }



    AMediaFormat_setInt32(cfg, "pcm-encoding", 2);



    if (AMediaCodec_configure(codec, cfg, nullptr, nullptr, 0) != AMEDIA_OK) {



        AMediaFormat_delete(cfg); AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ AMediaExtractor_delete(ex); return -1;



    }



    AMediaFormat_delete(cfg);



    if (AMediaCodec_start(codec) != AMEDIA_OK) {



        AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ AMediaExtractor_delete(ex); return -1;



    }



    int64_t totalMs = dur / 1000;



    int64_t scanFromMs = seekFromMs > 0 ? seekFromMs : 0;



    int64_t scanToMs = scanFromMs + 20000;   // 扫 20s 足够覆盖前奏+第一段



    if (totalMs > 0 && scanToMs > totalMs) scanToMs = totalMs;



    if (scanToMs - scanFromMs < 2000) {



        AMediaCodec_stop(codec); AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ AMediaExtractor_delete(ex); return -1;



    }



    if (scanFromMs > 0) AMediaExtractor_seekTo(ex, scanFromMs * 1000, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);



    const int maxBuckets = 4096;



    std::vector<float> beatEnv;



    beatEnv.reserve(maxBuckets);



    bool inputEos = false;



    int64_t lastTs = -1;



    // 【V8.157】首拍定位改用低频段(kick)能量：先 200Hz 低通再算能量，



    // 避免把踩镲/高频弱拍误判为第一拍（MIR downbeat 用低频重音更稳）



    float fbLpPrev = 0.f;



    const float fbLpk = 1.0f - std::exp(-6.2831853f * 200.0f / (float)(sr > 0 ? sr : 44100));



    for (int guard = 0; guard < 200000; guard++) {



        if (!inputEos) {



            ssize_t inIdx = AMediaCodec_dequeueInputBuffer(codec, 2000);



            if (inIdx >= 0) {



                size_t inSize = 0;



                uint8_t* inBuf = AMediaCodec_getInputBuffer(codec, inIdx, &inSize);



                if (inBuf && inSize > 0) {



                    ssize_t sz = AMediaExtractor_readSampleData(ex, inBuf, inSize);



                    if (sz < 0) {



                        AMediaCodec_queueInputBuffer(codec, inIdx, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);



                        inputEos = true;



                    } else {



                        int64_t t = AMediaExtractor_getSampleTime(ex);



                        AMediaCodec_queueInputBuffer(codec, inIdx, 0, (size_t)sz, t, 0);



                        AMediaExtractor_advance(ex);



                    }



                } else {



                    AMediaCodec_queueInputBuffer(codec, inIdx, 0, 0, 0, 0);



                }



            }



        }



        AMediaCodecBufferInfo info;



        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec, &info, 2000);



        if (outIdx >= 0) {



            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {



                AMediaCodec_releaseOutputBuffer(codec, outIdx, false);



                break;



            }



            size_t outSz = 0;



            uint8_t* outBuf = AMediaCodec_getOutputBuffer(codec, (size_t)outIdx, &outSz);



            if (outBuf && outSz > 0) {



                int samples = info.size / (int)sizeof(int16_t);



                float sum = 0.0f; int cnt = 0;



                for (int s = 0; s + 1 < samples; s += 2) {



                    int16_t v = (int16_t)((outBuf[s] & 0xff) | (outBuf[s+1] << 8));



                    float fv = v / 32768.0f;



                    // 【V8.157】200Hz 低通，突出 kick 低频重音



                    fbLpPrev += fbLpk * (fv - fbLpPrev);



                    sum += fbLpPrev * fbLpPrev; cnt++;



                }



                if (cnt > 0 && (int)beatEnv.size() < maxBuckets) beatEnv.push_back(std::sqrt(sum / cnt));



                lastTs = info.presentationTimeUs;



            }



            AMediaCodec_releaseOutputBuffer(codec, outIdx, false);



        } else if (outIdx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {



            if (inputEos) break;



        }



        if (lastTs > 0 && (lastTs / 1000) >= scanToMs) break;



    }



    AMediaCodec_stop(codec); AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ AMediaExtractor_delete(ex);



    int envCount = (int)beatEnv.size();



    if (envCount < 4) return -1;



    float peak = 0.0f;



    for (int i = 0; i < envCount; i++) peak = std::max(peak, beatEnv[i]);



    if (peak < 1e-4f) return -1;



    int firstIdx = -1;



    for (int i = 0; i < envCount; i++) {



        if (beatEnv[i] >= peak * 0.6f) { firstIdx = i; break; }



    }



    if (firstIdx < 0) return -1;



    int64_t beatMs = scanFromMs + (int64_t)firstIdx * windowMs;



    LOGI("preScanFirstBeat: beatMs=%lld (peak=%.4f idx=%d/%d)", (long long)beatMs, peak, firstIdx, envCount);



    return (int)(beatMs > 0 ? beatMs : 0);



}







// 【V8.15 A 轨副歌出】扫 A 轨从 seekFromMs 到结尾，找最后一个高能量段（副歌）的结束位置



// 语义：crossfade 在「副歌唱完」后触发（留尾奏过渡），返回副歌结束位置(ms)；找不到返回 -1



static int preScanLastChorusEndMs(int fd, int64_t length, int windowMs, int64_t seekFromMs) {



    if (fd < 0) return -1;



    AMediaExtractor* ex = AMediaExtractor_new();



    if (!ex) return -1;



    media_status_t r = AMediaExtractor_setDataSourceFd(ex, fd, 0, length);



    



    if (r != AMEDIA_OK) { AMediaExtractor_delete(ex); return -1; }



    int trackIdx = -1;



    int32_t sr = 44100, ch = 2; int64_t dur = 0;



    size_t n = AMediaExtractor_getTrackCount(ex);



    for (size_t i = 0; i < n; i++) {



        AMediaFormat* f = AMediaExtractor_getTrackFormat(ex, (size_t)i);



        if (!f) continue;



        const char* mime = nullptr;



        AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &mime);



        if (mime && strncmp(mime, "audio/", 6) == 0) {



            trackIdx = (int)i;



            AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr);



            AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);



            AMediaFormat_getInt64(f, AMEDIAFORMAT_KEY_DURATION, &dur);



            AMediaFormat_delete(f);



            break;



        }



        AMediaFormat_delete(f);



    }



    if (trackIdx < 0) {  AMediaExtractor_delete(ex); return -1; }



    AMediaExtractor_selectTrack(ex, trackIdx);



    AMediaCodec* codec = g_scanCodec;   /* V8.394 reuse the shared scan codec */



    {



        AMediaFormat* f = AMediaExtractor_getTrackFormat(ex, trackIdx);



        const char* mime = nullptr;



        AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &mime);



        /* [V8.394] same mime -> stop+flush and reuse; different mime -> full recreate. */
        if (mime) {
            if (codec && strncmp(g_scanMime, mime, 63) == 0) {
                AMediaCodec_stop(codec);
                AMediaCodec_flush(codec);
            } else {
                if (codec) { AMediaCodec_stop(codec); AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ codec = nullptr; }
                codec = AMediaCodec_createDecoderByType(mime);
                if (codec) { strncpy(g_scanMime, mime, 63); g_scanMime[63] = 0; g_scanCodec = codec; }
            }
        }



        AMediaFormat_delete(f);



    }



    if (!codec) {  AMediaExtractor_delete(ex); return -1; }



    AMediaFormat* cfg = AMediaFormat_new();



    {



        AMediaFormat* f = AMediaExtractor_getTrackFormat(ex, trackIdx);



        const char* m = nullptr;



        AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &m);



        if (m) AMediaFormat_setString(cfg, AMEDIAFORMAT_KEY_MIME, m);



        int32_t sr2 = 0, ch2 = 0;



        AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr2);



        AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch2);



        if (sr2 > 0) AMediaFormat_setInt32(cfg, AMEDIAFORMAT_KEY_SAMPLE_RATE, sr2);



        if (ch2 > 0) AMediaFormat_setInt32(cfg, AMEDIAFORMAT_KEY_CHANNEL_COUNT, ch2);



        copyCsdBuffers(cfg, f);



        AMediaFormat_delete(f);



    }



    AMediaFormat_setInt32(cfg, "pcm-encoding", 2);



    media_status_t cfgR = AMediaCodec_configure(codec, cfg, nullptr, nullptr, 0);



    



    if (cfgR != AMEDIA_OK) {



        AMediaFormat_delete(cfg); AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ AMediaExtractor_delete(ex); return -1;



    }



    AMediaFormat_delete(cfg);



    if (AMediaCodec_start(codec) != AMEDIA_OK) {



        



        AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ AMediaExtractor_delete(ex); return -1;



    }







    // 从 seekFromMs 扫到结尾



    int64_t totalMs = dur / 1000;



    int64_t scanFromMs = seekFromMs > 0 ? seekFromMs : 0;



    if (totalMs - scanFromMs < 20000) {  AMediaCodec_stop(codec); AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ AMediaExtractor_delete(ex); return -1; }



    media_status_t seekR = AMediaExtractor_seekTo(ex, scanFromMs * 1000, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);







    const int wFrames = std::max(1, (sr * windowMs) / 1000);



    const int maxBuckets = 4096;



    float rmsEnv[maxBuckets];



    int envCount = 0;



    bool inputEos = false;



    for (int guard = 0; guard < 200000; guard++) {



        if (!inputEos) {



            ssize_t inIdx = AMediaCodec_dequeueInputBuffer(codec, 2000);



            if (inIdx >= 0) {



                size_t inSize = 0;



                uint8_t* inBuf = AMediaCodec_getInputBuffer(codec, inIdx, &inSize);



                if (inBuf && inSize > 0) {



                    ssize_t sz = AMediaExtractor_readSampleData(ex, inBuf, inSize);



                    if (sz < 0) {



                        AMediaCodec_queueInputBuffer(codec, inIdx, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);



                        inputEos = true;



                    } else {



                        int64_t ts = AMediaExtractor_getSampleTime(ex);



                        AMediaCodec_queueInputBuffer(codec, inIdx, 0, (size_t)sz, ts, 0);



                        AMediaExtractor_advance(ex);



                    }



                } else {



                    AMediaCodec_queueInputBuffer(codec, inIdx, 0, 0, 0, 0);



                }



            }



        }



        AMediaCodecBufferInfo info;



        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec, &info, 2000);



        if (outIdx >= 0) {



            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {



                AMediaCodec_releaseOutputBuffer(codec, outIdx, false);



                break;



            }



            size_t outSz = 0;



            uint8_t* outBuf = AMediaCodec_getOutputBuffer(codec, (size_t)outIdx, &outSz);



            if (outBuf && outSz > 0) {



                int samples = info.size / (int)sizeof(int16_t);



                float sum = 0.0f; int cnt = 0;



                for (int s = 0; s + 1 < samples; s += 2) {



                    int16_t v = (int16_t)((outBuf[s] & 0xff) | (outBuf[s+1] << 8));



                    float fv = v / 32768.0f;



                    sum += fv * fv; cnt++;



                }



                if (cnt > 0) {



                    float rms = std::sqrt(sum / cnt);



                    if (envCount < maxBuckets) rmsEnv[envCount++] = rms;



                }



            }



            AMediaCodec_releaseOutputBuffer(codec, outIdx, false);



        } else if (outIdx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {



            if (inputEos) break;



        }



    }



    AMediaCodec_stop(codec); AMediaCodec_stop(codec);  /* V8.394 keep shared codec alive */ AMediaExtractor_delete(ex);



    



    if (envCount < 10) return -1;







    // 全局均值 + 峰值：副歌 = 高能量段（> 均值 1.4 倍）



    float sumAll = 0.0f; float peak = 0.0f;



    for (int i = 0; i < envCount; i++) { sumAll += rmsEnv[i]; if (rmsEnv[i] > peak) peak = rmsEnv[i]; }



    float mean = sumAll / envCount;



    float thresh = (mean + peak) * 0.5f;



    if (thresh < 0.05f) thresh = 0.05f;







    // 【V8.17 修正】找最后一个连续高能量段（≥3 窗口 = 300ms）的结束位置，



    // 但限制在歌长前 70% 内 —— 原逻辑找全曲最后一个（85-92%），A 轨基本到结尾才切；



    // 现在取最后一个 ≤70% 的高能段结束（通常是倒数第二个副歌，50-65% 处），副歌唱完即切



    int limitEndBucket = (int)((totalMs * 70 / 100 - scanFromMs) / windowMs);



    if (limitEndBucket > envCount) limitEndBucket = envCount;



    if (limitEndBucket < 0) limitEndBucket = 0;



    int lastEnd = -1;



    int runStart = -1;



    for (int i = 0; i <= envCount; i++) {



        bool hot = (i < envCount) && (rmsEnv[i] > thresh);



        if (hot && runStart < 0) runStart = i;



        if (!hot && runStart >= 0) {



            if (i - runStart >= 3 && i <= limitEndBucket) lastEnd = i;  // 记录 ≤70% 高能段的结束



            runStart = -1;



        }



    }



    if (runStart >= 0 && envCount - runStart >= 3 && envCount <= limitEndBucket) lastEnd = envCount;  // 扫到结尾仍热



    if (lastEnd < 0) {  return -1; }



    int64_t chorusEndMs = scanFromMs + (int64_t)lastEnd * windowMs;



    LOGI("preScanAChorus: chorusEndMs=%lld (limit70pct bucket=%d)", (long long)chorusEndMs, limitEndBucket);



    return (int)(chorusEndMs > 0 ? chorusEndMs : 0);



}







extern "C"



JNIEXPORT jint JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativePreScanPath(JNIEnv *env, jobject thiz,



                                                             jint fd, jlong length, jint windowMs, jint mode, jlong seekFromMs, jint bpm) {



    int ret = -1;



    if (mode == 1) {



        ret = preScanIntroQuietMs(fd, length, windowMs > 0 ? windowMs : 100);



    } else if (mode == 2) {



        ret = preScanChorusMs(fd, length, windowMs > 0 ? windowMs : 100, bpm);



    } else if (mode == 3) {



        ret = preScanLastChorusEndMs(fd, length, windowMs > 0 ? windowMs : 100, seekFromMs);



    } else if (mode == 4) {



        ret = preScanFirstBeatMs(fd, length, windowMs > 0 ? windowMs : 100, seekFromMs);



    } else {



        ret = preScanTailQuietMs(fd, length, windowMs > 0 ? windowMs : 100, seekFromMs);



    }



    return ret;



}







extern "C"



JNIEXPORT jboolean JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeStartCrossfade(JNIEnv *env, jobject thiz, jint durationMs, jint bStartMs,



        jfloat aLufs, jfloat bLufs, jint sfxMode, jfloat aBpm) {



    // 判断空闲槽（incoming）：B active 时新歌进 A 槽，否则进 B 槽



    bool toA = g_activeIsB.load();



    if (toA) {



        if (!g_alacModeA.load() && (!g_decoderCodec || !g_decoderExtractor)) { LOGE("StartCrossfade: incoming(A) not opened"); return false; }



        // 【Crossfade 重采样】A 槽 incoming 采样率 != stream 率时，写 ring 时重采样（A 槽已有重采样器）



    } else {



        if (!g_alacModeB.load() && (!g_decoderCodecB || !g_decoderExtractorB)) { LOGE("StartCrossfade: incoming(B) not opened"); return false; }



    }



    // 【ANR 修复·重入守卫】crossfade 进行中拒绝重复触发；



    // 【V8.212】预填充空窗期（g_xfadeArmUntilUs 未到期）同样拒绝，否则第二次触发会另起一个



    // 解码线程抢同一个槽。



    {



        struct timespec _ta; clock_gettime(CLOCK_MONOTONIC, &_ta);



        int64_t _ua = (int64_t)_ta.tv_sec * 1000000LL + (int64_t)_ta.tv_nsec / 1000LL;



        if (g_crossfadeActive.load() || _ua < g_xfadeArmUntilUs.load(std::memory_order_relaxed)) {



            LOGE("StartCrossfade: already active, ignored");



            return false;



        }



        // 预占：预填充最多 XF_PREFILL_MAX_WAIT_MS，留 200ms 余量



        g_xfadeArmUntilUs.store(_ua + (int64_t)(XF_PREFILL_MAX_WAIT_MS + 200) * 1000LL,



                                std::memory_order_relaxed);



    }



    g_crossfadeSeq.fetch_add(1, std::memory_order_relaxed);   // 【V8.204 诊断】本次过渡序号



    int sr = g_sampleRate.load(); if (sr <= 0) sr = 44100;



    float totalFrames = (float)sr * ((float)durationMs / 1000.0f);



    if (totalFrames < 1.0f) totalFrames = 1.0f;



    g_crossfadeStep.store(1.0f / totalFrames);



    g_crossfadePos.store(0.0f);



    g_crossfadeDurationMs.store(durationMs);



    g_xfadeBStartMs.store(bStartMs > 0 ? bStartMs : 0);  // 【V8.15】B 轨起点，complete 时用于位置计算



    // 【V8.309】记下过渡**开始时**旧 active 轨（outgoing）的播放位置。



    // 落点时新 active 轨（B）的正确绝对位置 = 本过渡开始时 A 的位置 + 本次过渡时长



    // （B 从入场点起播，经过了整段过渡时长后正好播到 BStart + dur）。



    // 旧代码落点处用 `g_xfadeBStartMs + dur` 重算，但手动触发传 bStartMs=0



    // （见 MusicService:1179），于是位置被写成 0+dur（本例 20s），



    // 而真实位置是几分钟 → **进度条/歌词/下一轮拍位计算全部倒退**（用户观感「往回退半秒」）。



    // 这里存起始基准，落点时用「基准 + dur」，与 bStartMs 是否为 0 无关，恒定正确。



    g_xfadeAPosAtStartUs.store(g_playbackPositionUs.load(std::memory_order_relaxed),



                               std::memory_order_relaxed);



    // 【V8.24/V8.243 B 音量预匹配】优先用两轨 Integrated LUFS 预匹配（感知响度对齐），



    // 无效回退 RMS。只压不抬（B 比 A 响才衰减），防 B 进场突兀也不抬噪。



    {



        // 【V8.142】优先用慢速 EMA（稳）；无效回退瞬时值



        float rmsA = g_xfadeARmsSlow.load(std::memory_order_relaxed);



        if (rmsA <= 0.001f) rmsA = g_rmsLevel.load(std::memory_order_relaxed);



        float rmsB = g_xfadeChorusRms.load(std::memory_order_relaxed);



        float gain = 1.0f;



        if (aLufs > -60.0f && bLufs > -60.0f) {



            // LUFS 预匹配：B 比 A 响（bLufs 数值更大）时才衰减，不抬噪



            if (bLufs > aLufs) {



                float g = powf(10.0f, (aLufs - bLufs) / 20.0f);



                gain = g < 0.6f ? 0.6f : (g > 1.0f ? 1.0f : g);



            }



            LOGI("Crossfade: B pre-gain(LUFS) %.2f (lufsA=%.1f lufsB=%.1f)", gain, (double)aLufs, (double)bLufs);



        } else if (rmsB > 0.001f && rmsA > 0.001f) {



            float g = rmsA / rmsB;



            gain = g < 0.6f ? 0.6f : (g > 1.0f ? 1.0f : g);



            LOGI("Crossfade: B pre-gain %.2f (rmsA=%.3f rmsB=%.3f)", gain, rmsA, rmsB);



        }



        g_xfadeBPreGain.store(gain, std::memory_order_relaxed);



    }



    // 【V8.182】每次过渡都用「当下」的 BPM/采样率重算 tremolo 频率与扫频起点，



    // 以及清掉上一轮留下的 B 轨 RMS（否则可视化与预增益会串到上一次过渡的去值）



    refreshXfadeFxParams();



    g_xfadeBRmsSlow.store(0.0f, std::memory_order_relaxed);



    memset(g_xfadeHpfPrevB, 0, sizeof(g_xfadeHpfPrevB));  // 【方案3】重置低频防浑浊 HPF 状态



    memset(g_xfadeLpfPrevA, 0, sizeof(g_xfadeLpfPrevA));  // 【V8.23】重置 A 轨对称低切 LPF 状态



    // 【V8.145】重置 DJ 效果状态（延迟线在触发线程清，避免实时线程大 memset 抖动）



    memset(g_xfadeDelayL, 0, sizeof(g_xfadeDelayL));



    memset(g_xfadeDelayR, 0, sizeof(g_xfadeDelayR));



    g_xfadeDelayWr = 0;



    memset(g_xfadeFxLpA, 0, sizeof(g_xfadeFxLpA));



    memset(g_xfadeFxLpB, 0, sizeof(g_xfadeFxLpB));



    memset(g_xfadeFxHp,  0, sizeof(g_xfadeFxHp));



    memset(g_xfadeFxHpX, 0, sizeof(g_xfadeFxHpX));



    g_xfadeModPhase = 0.0;



    tplReset();   // 【V8.281】djay 模板状态（HPF / 延迟线 / brake 缓冲）每轮过渡重建



    // 【V8.293 解码器竞态修复】incoming 解码线程已在 openIncomingTo*_internal（持有 g_decoderLifecycleMutex）



    // 里随 codec 一同原子启动并预填 ring，故此处不再起线程。原「武装段持锁外起线程」的写法会与并发的



    // nativeStopIncoming/nativeOpen（持锁）竞争，把刚建好的 codec 删掉、新线程在已释放对象上跑



    // → SIGSEGV（见 tombstone_31）。现在线程更早随 openIncoming 启动，prefill 等待通常直接命中满 ring。







    // 【V8.212/V8.243 incoming 预填充】线程已起，在触发线程里等 incoming ring 攒够料再开淡入。



    // 【V8.243】等待目标从 120ms 提升到「整段过渡时长」（至多 6s、受 ring 容量约束），



    // 让 B 轨入场前就把重叠区的 PCM 全攒进 ring —— 解码线程此前已在候选窗口就启动，



    // 此刻通常已填满；此举根除「B 没及时就绪 → FLOOR 兜底把过渡压短」的极端情况。



    // 空窗期最多 XF_PREFILL_MAX_WAIT_MS，解码异常也不会把切歌卡死。



    {



        PCMRingBuffer* incRing = toA ? g_ringBuffer : g_ringBufferB;



        int chN = g_channelCount.load(); if (chN <= 0) chN = 2;



        int baseSamples = (int)((float)sr * (XF_PREFILL_MS / 1000.0f)) * chN;



        // 【V8.243】过渡时长（秒）对应的样本数，封顶 6s（ring 容量约 12s，留余量），



        // 且不超过 ring 容量 - 0.5s，避免无谓死等。



        float cfDurSec = (float)durationMs / 1000.0f;



        if (cfDurSec < 0.5f) cfDurSec = 0.5f;



        if (cfDurSec > 6.0f) cfDurSec = 6.0f;



        long long wantDur = (long long)((float)sr * cfDurSec) * (long long)chN;



        long long cap = (long long)kRingBufferCapacity - (long long)((float)sr * 0.5f) * (long long)chN;



        if (cap < 1) cap = wantDur;



        if (wantDur > cap) wantDur = cap;



        int needSamples = (int)wantDur;



        if (needSamples < baseSamples) needSamples = baseSamples;



        int waitMs = 0;



        while (incRing && waitMs < XF_PREFILL_MAX_WAIT_MS) {



            if (incRing->available() >= needSamples) break;



            std::this_thread::sleep_for(std::chrono::milliseconds(5));



            waitMs += 5;



        }



        int got = incRing ? incRing->available() : -1;



        LOGI("Crossfade prefill: incoming(toA=%d) avail=%d need=%d waited=%dms (cfDur=%dms)",



             toA ? 1 : 0, got, needSamples, waitMs, durationMs);



    }







    // 武装：到此 incoming 已有货（或已放弃等待），等功率曲线两端才都有声



    // 【M2 beatmatch】武装 WSOLA：从此刻起 incoming 轨按比率变速。



    // 配置只在非实时线程一次性定好，之后音频回调只做 push/pull，不再改这些量。



    {



        const bool  en = g_beatmatchEnabled.load(std::memory_order_relaxed);



        const float rt = g_beatmatchRatio.load(std::memory_order_relaxed);



        g_beatmatchLive.store(false, std::memory_order_relaxed);



        g_bmRampActive.store(false, std::memory_order_relaxed);   // 【V8.253】作废在飞的比率回落



        g_bmBlendActive.store(false, std::memory_order_relaxed);  // 【V8.284】作废上一轮的 Tempo Blend



        g_bmPitchActive.store(false, std::memory_order_relaxed);  // 【V8.301】作废上一轮 Pitch Drop



        const bool pitchDrop = (g_xfadeTpl.load(std::memory_order_relaxed) == 9);



        // 【V8.310 Keylock】用户开了调性锁定 → 变速时锁音高。Keylock 需要音高轴，



        // 只 RB 有，所以这里把 useRb 强制拉起来（即便 crossfade_rubberband 关着）。



        const bool keylock = g_bmKeyLock.load(std::memory_order_relaxed) && !pitchDrop;



        // 【V8.423】武装条件：双侧 BPM 置信（g_bmEngage，来自 beatmatchParams）即武装引擎——
        // 即便 ratio≈1.0 也跟速（纠检测残差漂移），不再看 |ratio|>0.2%。pitchDrop 恒武装。
        if ((en && g_bmEngage.load(std::memory_order_relaxed)) || pitchDrop) {



            std::lock_guard<std::mutex> lk(g_beatmatchMutex);



            const int sr = g_sampleRate.load();



            const int ch = g_channelCount.load();



            const bool useRb = g_bmUseRb.load(std::memory_order_relaxed) || pitchDrop || keylock;



            g_bmEngineArmed.store(useRb, std::memory_order_relaxed);   // 【V8.229】锁定本次过渡的引擎



            // 【V8.284】Tempo Blend：引擎从 1.0（B 自己的速度）起步，推子推进过程中再拉到 rt。



            const bool tempoBlend = (g_xfadeTpl.load(std::memory_order_relaxed) == 5);
            // 【V8.427】渐变拉速解耦：这条路不再只属于 Tempo Blend。
            //   tpl==5 恒走（它本身就是"把速度拉过去"这个效果）；
            //   其余模板（echo/brake/dissolve/roll/phrase/eq）由全局开关 g_tempoRamp 决定 ——
            //   开着就等于"任何过渡都先做速度渐变，再把模板的音色效果叠在上面"。
            //   Pitch Drop（tpl==9）变调不变速，恒不参与。
            const bool rampPull = tempoBlend
                                  || (g_tempoRamp.load(std::memory_order_relaxed) && !pitchDrop);



            // 【V8.301·#3】Tempo Blend 翻转：引擎吃 active(A) 轨去配 B（djay 原版，B 不拉伸音质最好）。



            // 【V8.425】stem 模式也允许翻转：翻转只需要 A 的全轨环（A 不需要分轨），
            // B 的 stem 加权和保持 1:1 自然播放（RT 侧 stem 分支见 V8.425 配套改动）。
            // 旧条件 `&& !stemModeOn` 会让分轨模式退回"引擎吃 B"的老方向 → B 被拉伸
            // 卡顿/快进（用户实测：分轨下速度融合"对不上鼓点、B 轨快进"）。



            // 【V8.426】地基解耦：翻转不再绑 tpl==5。
            // DJ 的正确顺序是"先对拍（速度地基），再叠效果"，而不是二选一。
            // 引擎恒吃 active(A) 淡出轨，incoming(B) 恒走 1:1 原速 → B 永不被拉伸，
            // 修掉用户实测的"非 tempo 模板下 B 轨卡顿/快进、对不上鼓点"。
            // 模板从此只负责叠在地基之上的效果层（filter/echo/brake/eq/roll/phrase/pitch）。
            // 能进到这个分支说明引擎已武装（外层 if），故地基恒翻转。
            const bool bmFlip = true;



            g_bmEngSrcActive.store(bmFlip, std::memory_order_relaxed);



            // 【V8.426】翻转后引擎吃 A，锁同步的比率要取 **1/rt**（rt 是"拉伸 B"的比率）。
            // 原来非 tempo 模板用 rt，配翻转会把 A 往反方向拉（错拍加倍），必须跟着取反。
            const float r0 = pitchDrop ? 1.0f : (rampPull ? 1.0f : (1.0f / rt));   // 【V8.301】pitch 不变速 / 【V8.427】渐变拉速：从原速起步



            if (useRb) {



                // 【V8.222】高质量档：Rubber Band 频域相位声码器（Mixxx 同款），±8% 几乎无痕



                g_rbStretch.reset(sr > 0 ? sr : 44100, ch > 0 ? ch : 2);



                g_rbStretch.setRatio(r0);



                // 【V8.310 Keylock】起步就把音高补偿挂上（否则 reset 后前几帧音高不对）。



                // tempoBlend/pitchDrop 起步比率为 1.0，keylock 对它们无意义（上面已排除）。



                if (keylock) {



                    const EngineRatio er = resolveEngineRatio(r0, true);



                    g_rbStretch.setRatio(er.timeRatio);



                    g_rbStretch.setPitchScale((double)er.pitchScale);



                }



                if (pitchDrop) g_rbStretch.setPitchScale(1.0);   // 【V8.301】起步音高 1.0，循环里下扫



            } else {



                g_stretch.reset(sr > 0 ? sr : 44100, ch > 0 ? ch : 2);



                g_stretch.setRatio(r0);



            }



            LOGI("Beatmatch armed: keylock=%d (net speed %.4f, pitch locked)",



                 (int)keylock, (double)r0);



            g_beatmatchLive.store(true, std::memory_order_relaxed);



            g_bmRampActive.store(false, std::memory_order_relaxed);   // 【V8.253】新一轮过渡作废旧回落

            // 【V8.426】PLL 基准比率：翻转恒开 → 恒取 1/rt（A 侧比率，与 tempoBlend 分支一致）。
            // 原来只有 tpl==5 在这里设基准；现在所有模板都翻转，若沿用 setBeatmatch 写入的
            // B 侧比率 rt，PLL 会围着反方向做 fine-tune，把 A 越拉越偏（错拍不收敛）。
            g_bmPlR0.store(1.0f / rt, std::memory_order_relaxed);

            // 【V8.426】PLL 当前比率同步跟上：引擎此刻已锁在 1/rt，PLL 不能还拿着上一轮
            // setBeatmatch 写入的 B 侧比率 rt（否则首拍相位误差被放大，fine-tune 从错的地方起跑）。
            // tempoBlend / pitchDrop 下面会各自重置为 1.0（它们从原速起步），这里先给通用值。
            g_bmPlRcur = 1.0f / rt;



            if (rampPull) {   // 【V8.427】全模板共用：非 tempo 模板也走这条速度渐变排程



                // 【V8.301·#3】翻转：引擎吃 A，目标 = 1/rt（把 A 拉到 B 节奏）；常规：引擎吃 B，目标 = rt。



                g_bmBlendTo.store(bmFlip ? (1.0f / rt) : rt, std::memory_order_relaxed);



                g_bmBlendDurUs.store((int64_t)durationMs * 1000LL, std::memory_order_relaxed);



                struct timespec _tb0; clock_gettime(CLOCK_MONOTONIC, &_tb0);



                g_bmBlendStartUs.store((int64_t)_tb0.tv_sec * 1000000LL + _tb0.tv_nsec / 1000LL,



                                       std::memory_order_relaxed);



                g_bmBlendActive.store(true, std::memory_order_relaxed);



                g_bmPlRcur = 1.0f;



                // 【V8.301·#3】PLL 基准比率随翻转取反：翻转=1/rt，常规=rt（与引擎目标一致，fine-tune 围绕它）



                g_bmPlR0.store(bmFlip ? (1.0f / rt) : rt, std::memory_order_relaxed);



            }



            if (pitchDrop) {



                // 【V8.301】变调不变速：timeRatio 已 1.0，这里只排程音高下扫（落点前回到 1.0）。



                g_bmPitchTo.store(0.5f, std::memory_order_relaxed);



                g_bmPitchDurUs.store((int64_t)durationMs * 1000LL, std::memory_order_relaxed);



                struct timespec _tp0; clock_gettime(CLOCK_MONOTONIC, &_tp0);



                g_bmPitchStartUs.store((int64_t)_tp0.tv_sec * 1000000LL + _tp0.tv_nsec / 1000LL,



                                       std::memory_order_relaxed);



                g_bmPitchActive.store(true, std::memory_order_relaxed);



                g_bmPlRcur = 1.0f;



            }



            LOGI("Beatmatch armed: engine=%s ratio=%.4f (tempo %+.2f%%) sr=%d ch=%d%s",



                 useRb ? "rubberband" : "wsola",



                 (double)rt, (double)(rt - 1.0f) * 100.0, sr, ch,



                 rampPull ? " [Ramp-pull: pull-A, start 1.0 -> 1/rt]"
                        : (pitchDrop ? " [PitchDrop]" : " [Lock: 1/rt]"));



        }



    }



    // 【V8.226 逻辑审查】keylock 引擎延迟补偿：R2 RealTime 有固有算法延迟（getLatency，



    // 100ms 量级）。不补偿的话过渡开始后 B 轨头 ~latency 无输出 → 拍点/乐句对齐被吃掉。



    // 此处在触发线程（非实时回调）预喂 latency 帧把引擎缓冲冲满，首个回调即有满额输出；



    // 预喂的帧从 incoming ring 真实扣减，引擎状态与 ring 推进保持一致，不会重复消费。



    if (g_beatmatchLive.load(std::memory_order_relaxed)) {



        PCMRingBuffer* incRing = toA ? g_ringBuffer : g_ringBufferB;



        // 【V8.301·#3】翻转：keylock 延迟预喂应吃 active(A) 环（引擎源），而非 incoming 环。



        const bool bmFlip = g_bmEngSrcActive.load(std::memory_order_relaxed);



        PCMRingBuffer* primeRing = bmFlip ? (toA ? g_ringBufferB : g_ringBuffer) : incRing;



        int chN = g_channelCount.load(); if (chN <= 0) chN = 2;



        std::lock_guard<std::mutex> lk(g_beatmatchMutex);



        auto prime = [&](auto& eng) {



            const int lat = eng.latencyFrames();



            if (lat <= 0 || !primeRing) return;



            int want = lat + (int)((float)g_sampleRate.load() * 0.02f);  // latency + 20ms 余量



            int fedFrames = 0;



            while (fedFrames < want) {



                const int avail = primeRing->available();



                if (avail <= 0) break;



                int chunk = std::min(avail, (want - fedFrames) * chN);



                if (chunk > 8192 * chN) chunk = 8192 * chN;



                const int rd = primeRing->read(g_bmScratch, chunk);



                if (rd <= 0) break;



                eng.feed(g_bmScratch, rd / chN);



                eng.render();



                fedFrames += rd / chN;



            }



            LOGI("Crossfade keylock prime: latency=%d fed=%dms ringLeft=%d",



                 lat, (int)((float)fedFrames / g_sampleRate.load() * 1000.0f), primeRing->available());



        };



        if (g_bmEngineArmed.load(std::memory_order_relaxed)) prime(g_rbStretch);



        else prime(g_stretch);



    }



    g_crossfadeActive.store(true);
    g_sweepReset.store(true, std::memory_order_release);   // 【V8.505】新过渡：音频线程将复位扫频/人声淡出滤波状态

    // 【V8.473·起点对称】手动/自动切歌起手：A 外轨从「1:1 直读」切到「引擎合成 / 分轨接管」，
    // 这个切源瞬间与落点同理会有样本阶跃 → 硬咔哒。对称地在起点也武装一道接缝平滑，
    // 把边界阶跃摊到 SEAM_FRAMES(256≈5.8ms) 帧内，低于人耳对"咔哒"的分辨阈值且不改音频内容。
    // 无切源（普通淡入）时插值的是零阶跃 → 零副作用；有切源时把硬咔哒摊平。低风险。
    g_seamLeft.store(SEAM_FRAMES, std::memory_order_relaxed);



    // 【V8.245 SFX 语义修正】采样音效与切歌逻辑对齐：



    //   sfxMode 1 = 手动切歌 → 黑胶刹车（"停下当前这张唱片"的语义），不出 riser/sweep；



    //   sfxMode 0 = 自动过渡 → 按两轨能量差选：B 比 A 响 1.5dB 以上 = 爬升型 → riser；



    //               |Δ| ≤ 1.5dB = 平滑 blend → sweep；能量下降 → 静默（什么都不加）。



    //   riser/sweep 时长按 A 轨 BPM 量化到 4 或 8 小节、尾点对齐过渡中点（targetMs 交给



    //   trigger 内部算 rateMul）；无 LUFS 数据或 aBpm 无效时宁可静默，绝不盲放。



    if (sfxMode == 1) {



        sfxTriggerByName("brake");



    } else if (sfxMode == 0) {



        if (aLufs > -60.0f && bLufs > -60.0f) {



            float dLufs = bLufs - aLufs;



            if (dLufs > 1.5f || dLufs < -1.5f) {



                // 爬升型 → riser；能量差过大但下降 → 依然静默（riser 只服务 build-up）



                if (dLufs > 1.5f) {



                    double targetMs = sfxQuantTargetMs(durationMs, aBpm);



                    if (targetMs > 50.0) sfxTriggerByName("riser", targetMs);



                }



            } else {



                // 【V8.297】白噪音删除：原平滑 blend 触发的 sweep 采样（noise_sweep.wav）是用户



                // 反馈"太难听"的白噪音升扫，已删除。此处不再触发任何 sweep，平滑 blend 直接静默。



            }



        }



    }   // sfxMode 2 = 完全静默（保留给未来）



    // 【V8.205 点击检测】窗口 = 过渡全程 + 结束后 0.8s，翻转前后的样本跃变都收进来。



    {



        struct timespec _tc0; clock_gettime(CLOCK_MONOTONIC, &_tc0);



        int64_t _u0 = (int64_t)_tc0.tv_sec * 1000000LL + (int64_t)_tc0.tv_nsec / 1000LL;



        g_xfadeClickUntilUs.store(_u0 + ((int64_t)durationMs + 800LL) * 1000LL, std::memory_order_relaxed);



        g_xfadeClickN.store(0, std::memory_order_relaxed);



    }



    g_xfadeArmUntilUs.store(0, std::memory_order_relaxed);   // 已武装，占位到期失效



    // 【V8.448】过渡开始即打开逐帧诊断窗口：覆盖整个过渡 + 落点后 6s
    // （落点后还有 4s 速度回落期，是引擎供不上的高发段）。
    armXfadeDiag(100, 6000);

    LOGI("Crossfade started: %dms, step=%.6f (toA=%d)", durationMs, g_crossfadeStep.load(), toA ? 1 : 0);



    return true;



}







// 【V8.310】DJ 扩展配置一次性下发（keylock / pitch 档位 / phrase 小节数 / auto-solo 频段）。



// 合成一个 JNI 入口而不是四个：这些值都在「过渡开始前」定好，实时线程只读 atomic；



// 分成四个 JNI 既慢又容易漏设 —— V8.308 的 tpl 钳位事故就是「同一语义散处两地」的同类问题。



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetDjExtras(JNIEnv* env, jobject thiz,



        jboolean keylock, jint pitchSemi, jint phraseBars, jint soloBand) {



    g_bmKeyLock.store(keylock == JNI_TRUE, std::memory_order_relaxed);
    // 【V8.512】arming 起点先清空调性平移（下行业 setKeyMatchSemi 立刻覆写为本轮真实值），
    // 避免上一轮残留 semi 漏进本轮；过渡中由 resolveEngineRatio 闸门压制、落点后施加。
    g_keyMatchSemi.store(0, std::memory_order_relaxed);



    // pitch 档位夹取 [-24, 24]；0 = 关闭量化（走连续扫）



    int semi = (int)pitchSemi;



    if (semi > 24) semi = 24;



    if (semi < -24) semi = -24;



    g_bmPitchSemi.store(semi, std::memory_order_relaxed);



    // phrase 小节数只接受 {4,8,16,32}，其余（含 0）→ 8



    int bars = (int)phraseBars;



    if (bars != 4 && bars != 8 && bars != 16 && bars != 32) bars = 8;



    g_xfadePhraseBars.store(bars, std::memory_order_relaxed);



    int sb = (int)soloBand;



    if (sb < 0 || sb > 3) sb = 0;



    g_xfadeSoloBand.store(sb, std::memory_order_relaxed);



    LOGI("DjExtras: keylock=%d pitchSemi=%d phraseBars=%d soloBand=%d",



         (int)(keylock == JNI_TRUE), semi, bars, sb);



}







// 【V8.500 ④】自动 Camelot 调性匹配：下发出轨(A) 音高平移半音数（keylock 分支生效）。
// 范围夹 [-12, 12]；0 = 不变调。与 nativeSetDjExtras 分开，避免和手动 pitch 档位互相覆盖。
JNIEXPORT void JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeSetKeyMatchSemi(JNIEnv* env, jobject thiz,
        jint semi) {
    int s = (int)semi;
    if (s > 12) s = 12;
    if (s < -12) s = -12;
    g_keyMatchSemi.store(s, std::memory_order_relaxed);
    LOGI("KeyMatch: semi=%d", s);
}

// 【V8.15】B 轨从副歌起点播：crossfade 前 seek 空闲槽 extractor 到指定 ms



// 仅当 incoming 已 open 且 crossfade 未激活时有效






// 【V8.471】ALAC 直解槽的 seek。g_alacA/g_alacModeA 等声明在本文件更靠后的位置
// （3934 附近），而 nativeSeekIncomingMs 在其之前，故用前向声明 + 后置定义转发。
// 返回 true 表示已由 ALAC 处理（调用方不要再 flush MediaCodec）。
static bool seekIncomingSlotSeek(bool toA, int64_t ms);

JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeSeekIncomingMs(JNIEnv *env, jobject thiz, jint ms) {



    std::lock_guard<std::timed_mutex> lifecycleLock(g_decoderLifecycleMutex);



    if (g_crossfadeActive.load()) return false;



    bool toA = g_activeIsB.load();



    AMediaExtractor* ex = toA ? g_decoderExtractor : g_decoderExtractorB;



    if (!ex) return false;



    // 【V8.471 修"切歌后一闪而过的噪音"· 真正的根因】
    //
    // 真机取证（V8.470，设备 3443，14:01:5x 那轮自动切歌）：
    //   14:01:55.224 SeekIncoming: incoming seek to 21100ms (toA=0), ring cleared
    //   14:01:55.271 Crossfade prefill: incoming(toA=0) avail=146021 need=132300
    //   14:01:56.787 Crossfade complete: B is now active
    //   14:01:56.847 XF-CLICK n=0 edge=0.7192 inner=0.4216 peak=0.8787  ← 爆
    //   14:01:56.851 ~ 14:01:57.505 连续 18 个 callback edge=0.01~0.35   ← 持续抖动
    //
    // 也就是说：**落点后读到的 ring 内容本身是坏的**，不是接缝、不是补零、
    // 不是 Oboe 残留 —— 前面几轮修的都只是症状。
    //
    // 坏数据从哪来？看这个函数原来做了什么：
    //   1) AMediaExtractor_seekTo(...)          ← 只搬了 extractor 的读指针
    //   2) pause / sleep 60ms / ring.clear() / unpause
    //
    // 缺了三件事，而本文件里**其它每一条 seek 路径都做了**（见 28900~29000）：
    //   a) **没有 AMediaCodec_flush** —— MediaCodec 里还压着 seek 之前入队的
    //      输入缓冲和解出来的输出缓冲。extractor 已经跳到 21100ms，codec 却还
    //      在按旧时间线解码，吐出来的是"旧位置残帧 + 新位置数据"的拼接物，
    //      听感就是几秒没解码干净的沙沙/白噪。
    //   b) **没有 resetResampler*** —— 线性重采样器还握着 seek 之前的
    //      相位残差和上一块尾帧，新数据接上去相位是断的。
    //   c) **EOS 标志清错了轨** —— 原来无条件清 g_decoderEos（A 轨），
    //      但本函数 toA=false 时 seek 的是 **B 轨**，该清的是 g_decoderEosB。
    //      B 若带着 true 进下一轮，会直接走 drain 分支退出，环抽干。
    //
    // 另外顺序也错了：原来先 seek extractor 才 pause，解码线程此刻正在从
    // extractor 取数据，等于"边读边把读指针抽走"。必须先停解码线程。

    PCMRingBuffer* target    = toA ? g_ringBuffer      : g_ringBufferB;
    std::atomic<bool>& paused = toA ? g_decoderPaused  : g_decoderPausedB;
    std::atomic<bool>& eos    = toA ? g_decoderEos     : g_decoderEosB;
    auto& cv                  = toA ? g_decoderCv      : g_decoderCvB;

    // 1) 先停解码线程，再动 extractor / codec
    paused.store(true, std::memory_order_relaxed);
    cv.notify_all();
    std::this_thread::sleep_for(std::chrono::milliseconds(60));

    // 2) 搬 extractor 读指针
    media_status_t s = AMediaExtractor_seekTo(ex, (int64_t)ms * 1000, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
    if (s != AMEDIA_OK) {
        LOGE("SeekIncoming: seekTo %dms failed %d", ms, s);
        paused.store(false, std::memory_order_relaxed);
        cv.notify_all();
        return false;
    }

    // 3) flush codec（关键）：丢掉 seek 之前所有在途输入/输出缓冲
    //    ALAC 直解槽没有 MediaCodec，走 seekIncomingSlotSeek() 转发。
    if (!seekIncomingSlotSeek(toA, (int64_t)ms)) {
        AMediaCodec* codec = toA ? g_decoderCodec : g_decoderCodecB;
        if (codec) AMediaCodec_flush(codec);
    }
    if (toA) resetResamplerA(); else resetResamplerB();

    const int dropped = target ? target->available() : 0;
    if (target) target->clear();

    // 4) 清**本轨**的 EOS（原来无条件清 A 轨，seek B 时清错了）
    eos.store(false, std::memory_order_relaxed);

    paused.store(false, std::memory_order_relaxed);
    cv.notify_all();

    LOGI("SeekIncoming: incoming seek to %dms (toA=%d), codec flushed, ring cleared "
         "(dropped %d smp of stale data)",
         ms, toA ? 1 : 0, dropped);
    return true;
}








JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeStopIncoming(JNIEnv *env, jobject thiz) {



    std::lock_guard<std::timed_mutex> lifecycleLock(g_decoderLifecycleMutex);



    g_crossfadeActive.store(false);



    // 【V8.183】放弃 incoming 轨 = 回到 A 轨在播，必须把 g_activeIsB 一起复位。



    // 少了这一句（nativeStop() 里却有），过渡被打断后 g_activeIsB 会永久停在 true：



    // nativeGetDurationMs() 去读 B 轨缓存时长（B 只是在预加载，时长更是上一轮的）、



    // nativeIsEos() 去读 B 轨 EOS，而下一轮 nativeStartCrossfade 的 `toA = g_activeIsB`



    // 也会一直把新歌开进「正在播的那条轨」的槽位。



    g_activeIsB.store(false);



    g_crossfadePos.store(0.0f);



    g_decoderStopRequestedB.store(true);



    g_decoderCvB.notify_all();



    /* [V8.398] bounded join only (no pre-stop):
       an unbounded join here holds the lifecycle lock forever. Pre-stopping the codec
       is NOT done: some of these sites are on the playback path, and stopping the
       codec there interrupts the audible track (measured: songs changed 16 -> 7).
       A bounded join alone is enough: the caller returns and releases the lock. */
    joinDecoderTimed(g_decoderThreadB, "V8.398 B decoder", 2500);



    if (g_decoderCodecB) { codecCallTimed("Incoming(B) stop(old)", 2500, []() { return AMediaCodec_stop(g_decoderCodecB); }); } /* V8.392 keep codec for reuse */



    if (g_decoderExtractorB) { codecCallVoid("Incoming(B) extractor_delete", 2000, []() { AMediaExtractor_delete(g_decoderExtractorB); }); g_decoderExtractorB = nullptr; }



    if (g_ringBufferB) g_ringBufferB->clear();



    // 【ALAC】释放 B 槽直解引擎



    g_alacModeB.store(false);



    if (g_alacB) { g_alacB->close(); g_alacB.reset(); }



    LOGI("Incoming stopped");



}







extern "C"



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetCrossfadeEffect(JNIEnv *env, jobject thiz,



                                                                    jint effect, jfloat beatMs) {



    int e = effect;



    if (e < 0) e = 0;



    if (e > 6) e = 6;



    g_xfadeEffect.store(e);



    if (beatMs > 40.0f && beatMs < 5000.0f) g_xfadeBeatMs.store(beatMs);



    // delay 长度 = 1/2 拍（采样数），夹取到 [1, XFADE_DELAY_MAX]



    int sr = g_sampleRate.load(); if (sr <= 0) sr = 44100;



    int len = (int)((float)sr * (g_xfadeBeatMs.load() / 1000.0f) * 0.5f);



    if (len < 1) len = 1;



    if (len > XFADE_DELAY_MAX) len = XFADE_DELAY_MAX;



    g_xfadeDelayLen.store(len);



    refreshXfadeFxParams();   // 【V8.182】tremolo 频率 / 扫频起点随 BPM 立即重算



    LOGI("Crossfade effect=%d beatMs=%.1f delayLen=%d tremHz=%.2f filtStart=%.0f",



         e, g_xfadeBeatMs.load(), len, g_xfadeTremHz.load(), g_xfadeFiltStartHz.load());



}







extern "C"



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetLiveFx(JNIEnv *env, jobject thiz,



                                                           jboolean enabled, jint type, jfloat depth) {



    // 【V8.150 Live FX 总线】开关 + 类型 + 深度；可随时下发，即时生效



    g_liveFxEnabled.store(enabled ? true : false);



    int e = type; if (e < 0) e = 0; if (e > 6) e = 6;



    g_xfadeEffect.store(e);



    float d = depth; if (d < 0.0f) d = 0.0f; if (d > 1.0f) d = 1.0f;



    g_liveFxDepth.store(d);



    LOGI("LiveFx enabled=%d type=%d depth=%.2f", enabled ? 1 : 0, e, d);



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetXfadeTemplate(



        JNIEnv *env, jobject thiz, jint tpl, jint echoSamples) {



    int t = (int)tpl;



    // 【V8.308 修复·Pitch Drop 一直是死的】上限原来写死 8，而 Kotlin 侧



    // resolveXfadeTemplate 对 "pitch" 返回 **9**（V8.301 加的 Pitch Drop），



    // 于是 9 被静默夹成 8 → native 走的是 Phrase 分支，音高下扫从未发生。



    // 真机日志佐证：只见 `Crossfade tpl=8`，`PitchDrop` 日志 0 条。



    // 上限放到 9（当前最大模板号），与 Kotlin 的模板表保持一致。



    if (t < 0) t = 0; if (t > 10) t = 10;   // roll(6)/riser(7, 已下线)/phrase(8)/pitch(9)/eq(10)



    g_xfadeTpl.store(t, std::memory_order_relaxed);



    int dl = (int)echoSamples;



    if (dl < 0) dl = 0;



    if (dl >= XFADE_DELAY_MAX) dl = XFADE_DELAY_MAX - 1;



    g_tplEchoLen.store(dl, std::memory_order_relaxed);



    tplReset();



    LOGI("Crossfade tpl=%d echoSamples=%d", t, dl);



}







JNIEXPORT jboolean JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeIsActiveB(JNIEnv *env, jobject thiz) {



    return g_activeIsB.load();



}







JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeIsCrossfading(JNIEnv *env, jobject thiz) {
    return g_crossfadeActive.load();
}

// 【V8.182 过渡可视化】返回 3 个 float：[0]=crossfade 进度 0..1，[1]=A 轨慢速 RMS，[2]=B 轨慢速 RMS。



// 用 GetFloatArrayRegion 直接写预分配输出，实时线程零分配。非 crossfade 期 pos 恒 0、rms 各自保留。



extern "C"



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetXfadeLevel(JNIEnv *env, jobject thiz,



                                                               jfloatArray out) {



    if (out == nullptr) return;



    jsize n = env->GetArrayLength(out);



    if (n < 3) return;



    float v[3];



    v[0] = g_crossfadeActive.load(std::memory_order_relaxed) ? g_crossfadePos.load(std::memory_order_relaxed) : 0.0f;



    v[1] = g_xfadeARmsSlow.load(std::memory_order_relaxed);



    v[2] = g_xfadeBRmsSlow.load(std::memory_order_relaxed);



    env->SetFloatArrayRegion(out, 0, 3, v);



}







// 【V8.307 手动抢占】把进行中的 crossfade 压缩到 targetMs 内结束。



// 做法：只放大 g_crossfadeStep（每回调 pos 增量），不改 pos、不碰 A/B 轨、不动引擎。



// 落点判定是 `pos >= 1.0f`，因此加速后走的是**与正常过渡完全相同**的收尾路径



// （XFADE-DONE → retireTimeEngineAtLanding → de-click → 元数据/位置推进），



// 不会绕过任何落点逻辑，也就不会引入新的拼接/增益问题。



// 语义：手动「立即切歌」= 我现在就要切，不该干等完 15s（用户 bars=8）。



extern "C"



JNIEXPORT jboolean JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeCutCrossfadeShort(JNIEnv* env, jobject thiz,



                                                                    jint targetMs) {



    if (!g_crossfadeActive.load(std::memory_order_relaxed)) return false;



    if (targetMs <= 0) targetMs = 200;



    // 过短会让等功率曲线在一两帧内走完 → 听感像瞬切（爆音）。给 150ms 下限。



    if (targetMs < 150) targetMs = 150;



    const float pos = g_crossfadePos.load(std::memory_order_relaxed);



    const float remain = 1.0f - pos;



    if (remain <= 0.02f) return false;          // 几乎已落点，交给正常路径收尾



    int sr = g_sampleRate.load(); if (sr <= 0) sr = 44100;



    // 剩余帧数（按当前采样率）→ 让 step 恰好在 targetMs 内把 remain 走完



    const float framesLeft = (float)sr * ((float)targetMs / 1000.0f);



    if (framesLeft < 1.0f) return false;



    const float newStep = remain / framesLeft;



    const float oldStep = g_crossfadeStep.load(std::memory_order_relaxed);



    // 只允许加速（step 变大）。若 newStep 更小说明原时长已短于目标，保留原时长 ——



    // 绝不因为抢占反而把过渡拉长。



    if (newStep <= oldStep) return false;



    g_crossfadeStep.store(newStep, std::memory_order_relaxed);



    LOGI("CutCrossfadeShort: pos=%.3f step %.6f -> %.6f (target %dms)",



         (double)pos, (double)oldStep, (double)newStep, (int)targetMs);



    return true;



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeReleaseInactive(JNIEnv *env, jobject thiz) {



    // 释放非活动轨：B 在播 → 释放旧 A；A 在播 → 释放 B（incoming）



    if (g_crossfadeActive.load()) { LOGE("ReleaseInactive: crossfade in progress, skip"); return; }



    std::lock_guard<std::timed_mutex> lifecycleLock(g_decoderLifecycleMutex);



    if (g_activeIsB.load()) {



        // B active → 释放旧 A 轨（线程已 stopRequested，这里 join 收尾 + 删 codec/extractor）



        g_decoderStopRequested.store(true);



        g_decoderCv.notify_all();



        /* [V8.398] bounded join only (no pre-stop):
           an unbounded join here holds the lifecycle lock forever. Pre-stopping the codec
           is NOT done: some of these sites are on the playback path, and stopping the
           codec there interrupts the audible track (measured: songs changed 16 -> 7).
           A bounded join alone is enough: the caller returns and releases the lock. */
        joinDecoderTimed(g_decoderThread, "V8.398 A decoder", 2500);



        if (g_decoderCodec) {
            // 【V8.411】bound stop+delete so a wedged inactive codec can't hold g_decoderLifecycleMutex.
            media_status_t st = codecCallTimed("ReleaseInactive(A) stop", 2500, []() {
                return AMediaCodec_stop(g_decoderCodec);
            });
            if (st != kCodecTimeout) {
                bool del_ok = codecCallVoid("ReleaseInactive(A) delete", 2500, []() {
                    AMediaCodec_delete(g_decoderCodec);
                });
                if (!del_ok) { LOGE("ReleaseInactive(A): delete timed out -> poison"); g_decoderCodecPoisoned.store(true); }
            } else {
                LOGE("ReleaseInactive(A): stop timed out -> poison + skip delete");
                g_decoderCodecPoisoned.store(true);
            }
            g_decoderCodec = nullptr;
            std::this_thread::sleep_for(std::chrono::milliseconds(300));
        }



        if (g_decoderExtractor) { codecCallVoid("Incoming(A) extractor_delete", 2000, []() { AMediaExtractor_delete(g_decoderExtractor); }); g_decoderExtractor = nullptr; }



        if (g_ringBuffer) g_ringBuffer->clear();



        // 【ALAC】释放 A 槽直解引擎



        g_alacModeA.store(false);



        if (g_alacA) { g_alacA->close(); g_alacA.reset(); }



        g_decoderRunning.store(false);



        g_decoderThreadRunning.store(false);



        g_decoderEos.store(false);



        LOGI("Inactive track A released");



    } else {



        // A active → 释放 B（incoming）



        g_decoderStopRequestedB.store(true);



        g_decoderCvB.notify_all();



        joinDecoderB();        if (g_decoderCodecB) { codecCallTimed("Incoming(B) stop(old)", 2500, []() { return AMediaCodec_stop(g_decoderCodecB); }); } /* V8.392 keep codec for reuse */



        if (g_decoderExtractorB) { codecCallVoid("Incoming(B) extractor_delete", 2000, []() { AMediaExtractor_delete(g_decoderExtractorB); }); g_decoderExtractorB = nullptr; }



        if (g_ringBufferB) g_ringBufferB->clear();



        // 【ALAC】释放 B 槽直解引擎



        g_alacModeB.store(false);



        if (g_alacB) { g_alacB->close(); g_alacB.reset(); }



        LOGI("Inactive track B released");



    }



}







// --- Position / Duration / State queries ---



JNIEXPORT jlong JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetPositionMs(JNIEnv *env, jobject thiz) {



    // 【V7.46】修复歌词提前问题



    // g_decoderPositionUs 是解码器时间戳（比实际播放提前）



    // g_ringBufferFill 是缓冲帧数，g_sampleRate 是采样率



    // 实际播放位置 = 解码时间 - 缓冲延迟



    int64_t decoderPosUs = g_decoderPositionUs.load();



    int fillFrames = g_ringBufferFill.load();



    int sr = g_sampleRate.load();



    if (sr <= 0) sr = 44100;



    int64_t bufferDelayUs = (int64_t)fillFrames * 1000000LL / sr;



    int64_t playbackPosUs = g_playbackPositionUs.load();



    if (playbackPosUs < 0) playbackPosUs = 0;



    // 【V8.323同轴兜底】位置与时长必须来自同一条时间轴，否则 UI 进度 >100%、



    // 且 MusicService 侧 remain=dur-pos 恒为负 → crossfadeMonitor 每 tick 误命中窗口。



    // 正常路径两者同轴，这里只是防止任何异常累积把位置推出曲长。



    const int64_t cachedDurUs = g_activeIsB.load() ? g_cachedDurationB.load()



                                                    : g_cachedDurationUs.load();



    if (cachedDurUs > 0 && playbackPosUs > cachedDurUs) playbackPosUs = cachedDurUs;



    return playbackPosUs / 1000LL;  // 【V7.67】使用实际播放位置（微秒→毫秒）



}







JNIEXPORT jlong JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetDurationMs(JNIEnv *env, jobject thiz) {



    // Return cached duration to avoid AMediaExtractor_getTrackFormat race



    // (extractor may be torn down concurrently during track switch / release)



    // 【Crossfade】B 接管后读 B 轨时长



    if (g_activeIsB.load()) return g_cachedDurationB.load() / 1000;



    return g_cachedDurationUs.load() / 1000;



}







JNIEXPORT jboolean JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeIsEos(JNIEnv *env, jobject thiz) {



    // Must wait for both: decoder received EOS AND drain loop exited



    // (RingBuffer empty). Otherwise completion fires while audio still playing.



    // 【Crossfade】crossfade 进行中不算播完：旧 A 轨已 EOS 但 B 轨仍在淡入接管，



    // 此时若返回 true 会触发 onCompletion→playNext 硬切，打断 crossfade 后半段。



    if (g_crossfadeActive.load()) return false;



    // 【Crossfade】B 接管后读 B 轨 EOS 状态



    if (g_activeIsB.load()) return g_decoderEosB.load() && !g_decoderRunningB.load();



    return g_decoderEos.load() && !g_decoderRunning.load();



}







JNIEXPORT jint JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetSampleRate(JNIEnv *env, jobject thiz) {



    if (g_outputStream) return g_outputStream->getSampleRate();



    return g_sampleRate.load();



}







JNIEXPORT jint JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetChannelCount(JNIEnv *env, jobject thiz) {



    if (g_outputStream) return g_outputStream->getChannelCount();



    return g_channelCount.load();



}







JNIEXPORT jboolean JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeIsExclusive(JNIEnv *env, jobject thiz) {



    if (g_outputStream) return g_outputStream->getSharingMode() == oboe::SharingMode::Exclusive;



    return false;



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetPreferSharedMode(JNIEnv *env, jobject thiz, jboolean prefer) {



    g_preferSharedMode.store(prefer);



    LOGI("PreferSharedMode = %d", prefer);



}







JNIEXPORT jboolean JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeIsSharedMode(JNIEnv *env, jobject thiz) {



    if (g_outputStream) return g_outputStream->getSharingMode() == oboe::SharingMode::Shared;



    return false;



}







// --- DSP EQ control ---



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetDspEq(JNIEnv *env, jobject thiz,



                                                       jboolean enabled,



                                                       jfloat highShelfFreq, jfloat highShelfDb, jfloat highShelfQ,



                                                       jfloat peakingFreq, jfloat peakingDb, jfloat peakingQ,



                                                       jfloat preGainDb) {



    std::lock_guard<std::mutex> eqLock(g_eqMutex);



    int sr = g_sampleRate.load();



    if (enabled) {



        g_eqBand1L.setHighShelf(sr, highShelfFreq, highShelfDb, highShelfQ);



        g_eqBand1R.setHighShelf(sr, highShelfFreq, highShelfDb, highShelfQ);



        g_eqBand2L.setPeaking(sr, peakingFreq, peakingDb, peakingQ);



        g_eqBand2R.setPeaking(sr, peakingFreq, peakingDb, peakingQ);



        g_dspEqPreGain.store(powf(10.0f, preGainDb / 20.0f));



        g_dspEqEnabled.store(true);



        LOGI("DSP EQ enabled: HS=%.0fHz/%.1fdB/Q%.2f + Peak=%.0fHz/%.1fdB/Q%.1f + preGain=%.1fdB",



             highShelfFreq, highShelfDb, highShelfQ, peakingFreq, peakingDb, peakingQ, preGainDb);



    } else {



        g_eqBand1L.setFlat(); g_eqBand1R.setFlat();



        g_eqBand2L.setFlat(); g_eqBand2R.setFlat();



        g_dspEqPreGain.store(1.0f);



        g_dspEqEnabled.store(false);



        LOGI("DSP EQ disabled");



    }



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeResetDspEq(JNIEnv *env, jobject thiz) {



    std::lock_guard<std::mutex> eqLock(g_eqMutex);



    g_eqBand1L.reset(); g_eqBand1R.reset();



    g_eqBand2L.reset(); g_eqBand2R.reset();



    LOGI("DSP EQ state reset");



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetDspEnabled(JNIEnv *env, jobject thiz,



                                                            jboolean enabled) {



    g_dspEqEnabled.store(enabled);



    if (!enabled) {



        std::lock_guard<std::mutex> eqLock(g_eqMutex);



        g_eqBand1L.reset(); g_eqBand1R.reset();



        g_eqBand2L.reset(); g_eqBand2R.reset();



        g_eqBand3L.reset(); g_eqBand3R.reset();



        g_eqBand4L.reset(); g_eqBand4R.reset();



        g_eqBand5L.reset(); g_eqBand5R.reset();



        g_autoEqBand6L.reset(); g_autoEqBand6R.reset();



        g_autoEqBand7L.reset(); g_autoEqBand7R.reset();



        g_autoEqBand8L.reset(); g_autoEqBand8R.reset();



        g_autoEqBand9L.reset(); g_autoEqBand9R.reset();



        g_autoEqBand10L.reset(); g_autoEqBand10R.reset();



        g_eq5BandEnabled.store(false);  // 【V7.80】



        g_autoEqEnabled.store(false);



        g_autoEqPreGain = 1.0f;



    }



    LOGI("DSP processing %s", enabled ? "ENABLED" : "DISABLED");



}







JNIEXPORT jboolean JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeIsDspEnabled(JNIEnv *env, jobject thiz) {



    return g_dspEqEnabled.load();



}







// --- Sample rate native ---



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetSampleRateNative(JNIEnv *env, jobject thiz,



                                                                  jint nativeSampleRate) {



    g_sampleRateNative.store(nativeSampleRate);



    LOGI("System native sample rate set to: %d Hz", nativeSampleRate);



}







JNIEXPORT jint JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetSampleRateNative(JNIEnv *env, jobject thiz) {



    return g_sampleRateNative.load();



}







// --- 【V3.2.7】输出设备路由（USB DAC Port ID，0=系统默认）---



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetOutputDeviceId(JNIEnv *env, jobject thiz,



                                                                jint deviceId) {



    g_outputDeviceId.store(deviceId);



    // 【V8.198】设备变了则标记流需重建（切歌时比对 g_streamDeviceId）



    if (g_streamDeviceId.load() != deviceId) g_streamDeviceId.store(-2);



    LOGI("Output device id set to: %d", deviceId);



}







// --- Clip stats ---



JNIEXPORT jfloat JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetClipRatio(JNIEnv *env, jobject thiz) {



    int64_t clipped = g_clipSampleCount.load(std::memory_order_relaxed);



    int64_t total = g_totalSampleCount.load(std::memory_order_relaxed);



    if (total == 0) return 0.0f;



    return static_cast<float>(clipped) / static_cast<float>(total);



}







JNIEXPORT jint JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetClipCount(JNIEnv *env, jobject thiz) {



    return static_cast<jint>(g_clipSampleCount.load(std::memory_order_relaxed));



}







JNIEXPORT jfloat JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetTotalSamples(JNIEnv *env, jobject thiz) {



    return static_cast<jfloat>(g_totalSampleCount.load(std::memory_order_relaxed));



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeResetClipStats(JNIEnv *env, jobject thiz) {



    g_clipSampleCount.store(0, std::memory_order_relaxed);



    g_totalSampleCount.store(0, std::memory_order_relaxed);



    LOGI("Clip stats reset");



}







// --- RMS amplitude for beat-reactive visuals ---



JNIEXPORT jfloat JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetRmsLevel(JNIEnv *env, jobject thiz) {



    return g_rmsLevel.load(std::memory_order_relaxed);



}







JNIEXPORT jfloat JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetBandSub(JNIEnv *env, jobject thiz) {



    return g_band0.load(std::memory_order_relaxed);



}



JNIEXPORT jfloat JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetBandBass(JNIEnv *env, jobject thiz) {



    return (g_band1.load(std::memory_order_relaxed) + g_band2.load(std::memory_order_relaxed)) * 0.5f;



}



JNIEXPORT jfloat JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetBandMid(JNIEnv *env, jobject thiz) {



    return (g_band3.load(std::memory_order_relaxed) + g_band4.load(std::memory_order_relaxed)) * 0.5f;



}



JNIEXPORT jfloat JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetBandHigh(JNIEnv *env, jobject thiz) {



    return (g_band5.load(std::memory_order_relaxed) + g_band6.load(std::memory_order_relaxed) + g_band7.load(std::memory_order_relaxed)) * 0.33f;



}



// 8-band raw access for FFT display



JNIEXPORT jfloat JNICALL


Java_com_sdw_music_player_OboeDirectPlayer_nativeGetBand0(JNIEnv *env, jobject thiz) {



    return g_band0.load(std::memory_order_relaxed);



}
// 【V8.370】一次取回 B 轨 8 段，避免 UI 侧做 8 次 JNI 往返。

// 返回写入段数（0 = B 轨尚未开始解码）。

JNIEXPORT jint JNICALL

Java_com_sdw_music_player_OboeDirectPlayer_nativeGetBandsB(JNIEnv *env, jobject thiz, jfloatArray out) {



    if (!out) return 0;



    jfloat tmp[8];

    tmp[0] = g_bandB0.load(std::memory_order_relaxed);

    tmp[1] = g_bandB1.load(std::memory_order_relaxed);

    tmp[2] = g_bandB2.load(std::memory_order_relaxed);

    tmp[3] = g_bandB3.load(std::memory_order_relaxed);

    tmp[4] = g_bandB4.load(std::memory_order_relaxed);

    tmp[5] = g_bandB5.load(std::memory_order_relaxed);

    tmp[6] = g_bandB6.load(std::memory_order_relaxed);

    tmp[7] = g_bandB7.load(std::memory_order_relaxed);

    env->SetFloatArrayRegion(out, 0, 8, tmp);

    return 8;



}





JNIEXPORT jfloat JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetBand1(JNIEnv *env, jobject thiz) {



    return g_band1.load(std::memory_order_relaxed);



}



JNIEXPORT jfloat JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetBand2(JNIEnv *env, jobject thiz) {



    return g_band2.load(std::memory_order_relaxed);



}



JNIEXPORT jfloat JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetBand3(JNIEnv *env, jobject thiz) {



    return g_band3.load(std::memory_order_relaxed);



}



JNIEXPORT jfloat JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetBand4(JNIEnv *env, jobject thiz) {



    return g_band4.load(std::memory_order_relaxed);



}



JNIEXPORT jfloat JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetBand5(JNIEnv *env, jobject thiz) {



    return g_band5.load(std::memory_order_relaxed);



}



JNIEXPORT jfloat JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetBand6(JNIEnv *env, jobject thiz) {



    return g_band6.load(std::memory_order_relaxed);



}



JNIEXPORT jfloat JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetBand7(JNIEnv *env, jobject thiz) {



    return g_band7.load(std::memory_order_relaxed);



}



// 【V8.174】左右声道各自的 8 频段，一次取回。Mixer VU 表的 L/R 两列用真数据画，



// 不再拿 "左边 * 固定比例" 冒充右声道。



JNIEXPORT jfloatArray JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetSpectrumL(JNIEnv *env, jobject thiz) {



    jfloat v[8];



    for (int k = 0; k < 8; k++) v[k] = g_bandL[k].load(std::memory_order_relaxed);



    jfloatArray arr = env->NewFloatArray(8);



    if (arr != NULL) env->SetFloatArrayRegion(arr, 0, 8, v);



    return arr;



}



JNIEXPORT jfloatArray JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetSpectrumR(JNIEnv *env, jobject thiz) {



    jfloat v[8];



    for (int k = 0; k < 8; k++) v[k] = g_bandR[k].load(std::memory_order_relaxed);



    jfloatArray arr = env->NewFloatArray(8);



    if (arr != NULL) env->SetFloatArrayRegion(arr, 0, 8, v);



    return arr;



}







// 【V8.207】Mixer VU 表「一次取全」。



// 旧用法是每 80ms 打 7 次零散 JNI（getBandSub/Bass/Mid/High/RmsLevel + getSpectrumL/R），



// 其中两个还每次 new 一个 jfloatArray。native 其实每个 callback（4~10ms）就已经把



// g_bandL/R 算好了，UI 却要等最长 80ms 才来取，拿到的是几十毫秒前的旧值 ——



// 「VU 有延迟」的主因就在这儿。现在一次 JNI 把 21 个数全推进调用方自己的数组：



// 不分配、可逐帧 60fps 取。



//   [0..7]   L 声道 8 段   [8..15]  R 声道 8 段



//   [16] sub [17] bass [18] mid [19] high [20] rms



// 【V8.208】追加真值电平：[21] rmsL [22] rmsR [23] peakL [24] peakR



JNIEXPORT jint JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeReadBandLevels(JNIEnv *env, jobject thiz,



                                                                jfloatArray out) {



    if (out == NULL) return -1;



    const jsize kNeed = 25;



    if (env->GetArrayLength(out) < kNeed) return -1;



    float v[kNeed];



    for (int k = 0; k < 8; k++) {



        v[k]     = g_bandL[k].load(std::memory_order_relaxed);



        v[8 + k] = g_bandR[k].load(std::memory_order_relaxed);



    }



    v[16] = g_band0.load(std::memory_order_relaxed);



    v[17] = (g_band1.load(std::memory_order_relaxed) + g_band2.load(std::memory_order_relaxed)) * 0.5f;



    v[18] = (g_band3.load(std::memory_order_relaxed) + g_band4.load(std::memory_order_relaxed)) * 0.5f;



    v[19] = (g_band5.load(std::memory_order_relaxed) + g_band6.load(std::memory_order_relaxed) +



             g_band7.load(std::memory_order_relaxed)) * 0.33f;



    v[20] = g_rmsLevel.load(std::memory_order_relaxed);



    v[21] = g_rmsL.load(std::memory_order_relaxed);



    v[22] = g_rmsR.load(std::memory_order_relaxed);



    v[23] = g_peakL.load(std::memory_order_relaxed);



    v[24] = g_peakR.load(std::memory_order_relaxed);



    env->SetFloatArrayRegion(out, 0, kNeed, v);



    return kNeed;



}







// --- 【M3.2】DJ 音效包 JNI 桥接 ---



extern "C"



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSfxSetPack(JNIEnv *env, jobject thiz,



        jobjectArray paths, jobjectArray triggers, jfloatArray gains) {



    sfxLoadPack(env, paths, triggers, gains);



}







extern "C"



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSfxTrigger(JNIEnv *env, jobject thiz, jstring trigger) {



    if (!trigger) { sfxTriggerByName("manual"); return; }



    const char* c = env->GetStringUTFChars(trigger, nullptr);



    sfxTriggerByName(c);



    env->ReleaseStringUTFChars(trigger, c);



}







// --- 【V8.216】DJ 音效包总开关（设置页可控；默认关，切歌不再强制播放 scratch/sweep/drop）---



extern "C"



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSfxSetEnabled(JNIEnv *env, jobject thiz, jboolean enabled) {



    // 启用即只翻转总开关：实际音效采样由 Kotlin 侧 applySfxPack() 通过



    // nativeSfxSetPack 下发的「内置高质量采样包」（riser / noise_sweep / vinyl_brake）



    // 提供，不再走合成器（合成器已在 V8.244 移除，改为采样音效包）。



    g_sfxEnabled.store(enabled == JNI_TRUE, std::memory_order_relaxed);



    LOGI("SFX pack enabled=%d", enabled ? 1 : 0);



}







// --- 【V8.220】低频交接插件开关（默认关；只影响交叉淡化期间的交接曲线，见混音段注释）---



extern "C"



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetXfadeBassSwap(JNIEnv *env, jobject thiz, jboolean enabled) {



    g_xfadeBassSwap.store(enabled == JNI_TRUE, std::memory_order_relaxed);



    LOGI("XfadeBassSwap %d", enabled ? 1 : 0);



}







// --- 【V8.246】三段频交接插件开关（默认关；低/中/高错峰交接，见混音段注释）---



extern "C"



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetXfadeThreeBand(JNIEnv *env, jobject thiz, jboolean enabled) {



    g_xfadeThreeBand.store(enabled == JNI_TRUE, std::memory_order_relaxed);



    LOGI("XfadeThreeBand %d", enabled ? 1 : 0);



}







// --- 【V8.263】NeuralMix stem 总线 ---



// Kotlin feeder 从插件 stem 流同步读 f32le 立体声块，逐轨 push 进环；



// 过渡混合时 B 轨贡献 = Σ stem × 错峰曲线（bass 提前 / vocals 推迟）。



extern "C"



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeStemSetMode(JNIEnv *env, jobject thiz, jboolean enabled) {



    if (enabled == JNI_TRUE) {



        if (!g_stemScratch) {



            g_stemScratchSmp = (size_t)kRingBufferCapacity * 2;



            g_stemScratch = (float*)malloc(g_stemScratchSmp * sizeof(float));



        }



        for (int s = 0; s < 4; s++) {



            delete g_stemRing[s];



            g_stemRing[s] = new PCMRingBuffer(kRingBufferCapacity);



        }



        resetStemResampler();   // 【V8.283】每轮过渡重建重采样状态



        resetStemFx();          // 【V8.285】分轨音效状态同样每轮重建



        g_stemMode.store(true, std::memory_order_relaxed);



        LOGI("StemMode ON (rings reset, stem src=%dHz, stream=%dHz)",



             g_stemSrcRate.load(std::memory_order_relaxed), g_sampleRate.load());



    } else {



        g_stemMode.store(false, std::memory_order_relaxed);



        LOGI("StemMode OFF");



    }



}







extern "C"



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeStemReset(JNIEnv *env, jobject thiz) {



    for (int s = 0; s < 4; s++) {



        delete g_stemRing[s];



        g_stemRing[s] = nullptr;



    }



    resetStemResampler();



}







/** 【V8.285】djay Neural Mix 分轨音效路由。target: 0=关 1=人声 2=鼓组 3=旋律/伴奏；



 *  mode: 0=无 1=HPF 扫频 2=Echo 3=LPF 扫频 4=分轨增益。仅在分轨总线真正点亮（有缓存）时才会听到。 */



extern "C"



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetStemFxTarget(JNIEnv *env, jobject thiz,



                                                                 jint target, jint mode) {



    const int t = (int)target; const int m = (int)mode;



    g_stemFxTarget.store((t >= 0 && t <= 4) ? t : 0, std::memory_order_relaxed);  // 【V8.314】3/4 拆分



    g_stemFxMode.store((m >= 0 && m <= 4) ? m : 0, std::memory_order_relaxed);



    resetStemFx();



    LOGI("StemFx target=%d mode=%d", g_stemFxTarget.load(std::memory_order_relaxed),



         g_stemFxMode.load(std::memory_order_relaxed));



}







/** 【V8.283】告诉 native 分轨文件的采样率（插件恒 44.1k）；与流率不同就在 push 时重采样。 */



extern "C"



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeStemSetSrcRate(JNIEnv *env, jobject thiz, jint rate) {



    const int r = (int)rate;



    if (r <= 0) return;



    g_stemSrcRate.store(r, std::memory_order_relaxed);



    resetStemResampler();



    LOGI("StemSrcRate %d (stream=%d)", r, g_sampleRate.load());



}







extern "C"



JNIEXPORT jint JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeStemPush(JNIEnv *env, jobject thiz,



                                                          jint stemId, jfloatArray data, jint samples) {



    if (stemId < 0 || stemId >= 4 || !g_stemRing[stemId] || samples <= 0) return 0;



    jfloat *p = env->GetFloatArrayElements(data, nullptr);



    const int streamRate = g_sampleRate.load();



    const int srcRate = g_stemSrcRate.load(std::memory_order_relaxed);



    int written = 0;



    if (srcRate > 0 && streamRate > 0 && srcRate != streamRate) {



        // 【V8.283】分轨文件率 ≠ 流率：先重采样再入环（环是按流率消费的）



        const int outFrames = resampleLinearToStream(p, samples, 2, srcRate, streamRate,



                                                     g_stemResOut[stemId], g_stemResPhase[stemId],



                                                     g_stemResPrev[stemId], g_stemResHistLen[stemId], 8 * 64);



        if (outFrames > 0) written = g_stemRing[stemId]->write(g_stemResOut[stemId].data(), outFrames * 2);



    } else {



        written = g_stemRing[stemId]->write(p, samples);



    }



    env->ReleaseFloatArrayElements(data, p, JNI_ABORT);



    return written;



}







// --- 【M2】beatmatch 变速不变调 ---



// enabled: 总开关；ratio = 输出长度/输入长度（>1 = incoming 放慢，<1 = 加快）。



// 比率在下一次 nativeStartCrossfade 时才真正生效（那时才能确认采样率/声道数）。



extern "C"



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetBeatmatch(JNIEnv *env, jobject thiz,



        jboolean enabled, jfloat ratio, jfloat aBpm, jfloat bBpm, jboolean phaseLock) {



    float r = (float)ratio;



    if (r < 0.5f) r = 0.5f;



    if (r > 2.0f) r = 2.0f;



    g_beatmatchEnabled.store(enabled == JNI_TRUE, std::memory_order_relaxed);



    g_beatmatchRatio.store(r, std::memory_order_relaxed);



    g_bmPhaseLock.store(phaseLock == JNI_TRUE, std::memory_order_relaxed);   // 【V8.243】相位锁定开关
    g_bmEngage.store(enabled == JNI_TRUE, std::memory_order_relaxed);         // 【V8.423】engage = 双侧 BPM 置信（Kotlin 传入）



    if (enabled != JNI_TRUE) {



        g_beatmatchLive.store(false, std::memory_order_relaxed);



        g_bmPlActive = false;



        LOGI("Beatmatch set: enabled=0 (phaselock=%d)", (int)(phaseLock == JNI_TRUE));



        return;



    }



    // 【V8.243】初始化连续相位锁定状态：记 A/B BPM、基准比率，并按当前播放位置算出 A 轨初始拍相位。



    // B 轨假定已吸附到 A 的 downbeat（snapBToBarGrid 已对齐），其初始相位记为 0。



    const float aB = (aBpm >= 40.0f && aBpm <= 220.0f) ? aBpm : 120.0f;



    const float bB = (bBpm >= 40.0f && bBpm <= 220.0f) ? bBpm : 120.0f;



    g_bmPlAfr.store(aB, std::memory_order_relaxed);



    g_bmPlBfr.store(bB, std::memory_order_relaxed);



    g_bmPlR0.store(r, std::memory_order_relaxed);



    g_bmPlRcur = r;



    float aPosSec = (float)(g_playbackPositionUs.load() / 1000000.0);



    if (aPosSec < 0.0f) aPosSec = 0.0f;



    float phiA = aPosSec * aB / 60.0f;



    phiA = phiA - floorf(phiA);                 // 0..1



    // 【V8.503·③】A 初始拍相位优先用 Kotlin 下发的真实值（与 B 侧 V8.322 对称）：
    // Kotlin 以 xfadeABeatPhaseMs 为 anchor 算 A 当前小节内拍位、归一化 beatInBar/4 下发；
    // 未提供（<0）退回旧行为（phiA = aPosSec*aB/60 的 beat-phase，即假定 downbeat@t=0）。
    const float aInit = g_bmPlAInitPhase.load(std::memory_order_relaxed);
    g_bmPlMA = (aInit >= 0.0f && aInit < 1.0f) ? aInit : phiA;   // A 初始拍相位



    // 【V8.322】B 初始拍相位改用 Kotlin 侧算出的真实值；未提供（<0）才退回"假定 downbeat"。



    const float bInit = g_bmPlBInitPhase.load(std::memory_order_relaxed);



    g_bmPlMB = (bInit >= 0.0f && bInit < 1.0f) ? bInit : 0.0f;



    g_bmPlLastUs = 0;



    // 【V8.423】PLL 启用条件：相位锁定开 + 双侧 BPM 置信（g_bmEngage）。不再门槛 |ratio|>0.2%，
    // 否则近拍 pair（检测报相等、实际有残差）不锁相 → 过渡期鼓点漂移（分裂感）。djay 即全程跟速。
    g_bmPlActive = (g_bmPhaseLock.load() && g_bmEngage.load(std::memory_order_relaxed));



    LOGI("Beatmatch set: enabled=1 ratio=%.4f aBpm=%.1f bBpm=%.1f phaselock=%d plActive=%d phiA=%.3f",



         (double)r, (double)aB, (double)bB, (int)(phaseLock == JNI_TRUE), (int)g_bmPlActive, (double)phiA);



}







/**



 * 【V8.322】下发 PLL 整定参数：死区 / 小增益 / 大增益。



 * 让这三个数不必等装机就能调 —— 它们是 PLL 控制器整定的核心，改动风险小、影响可测。



 */



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetPlTuning(JNIEnv* env, jobject thiz,



        jfloat deadzone, jfloat gainLo, jfloat gainHi) {



    float dz = (float)deadzone;



    if (dz < 0.00001f) dz = 0.00001f;      // 下限：太细则会永远下发微小抖动



    if (dz > 0.02f) dz = 0.02f;



    g_bmPlDeadzone.store(dz, std::memory_order_relaxed);



    float gl = (float)gainLo;  if (gl < 0.0f) gl = 0.0f;  if (gl > 1.0f) gl = 1.0f;



    float gh = (float)gainHi;  if (gh < 0.0f) gh = 0.0f;  if (gh > 2.0f) gh = 2.0f;



    if (gh < gl) gh = gl;



    g_bmPlGainLo.store(gl, std::memory_order_relaxed);



    g_bmPlGainHi.store(gh, std::memory_order_relaxed);



    LOGI("PL tuning: deadzone=%.5f gainLo=%.2f gainHi=%.2f", (double)dz, (double)gl, (double)gh);



}







/**



 * 【V8.322】下发 B 轨入场点的真实拍相位（0..1，小节内）。



 * 由 MusicService 在 snapBToBarGrid 之后调用，把「入场点落在小节第几拍」告诉 native，



 * 让 PLL 初始相位不再是硬假定的 0。phase01 < 0 表示未知（退回旧行为）。



 */



JNIEXPORT void JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeSetPlAInitPhase(JNIEnv* env, jobject thiz, jfloat phase01) {
    const float p = (float)phase01;
    g_bmPlAInitPhase.store((p >= 0.0f && p < 1.0f) ? p : -1.0f, std::memory_order_relaxed);
    LOGI("PL init phase: A=%.3f (%s)", (double)p, (p >= 0.0f && p < 1.0f) ? "from beatgrid" : "unknown->assume downbeat");
}

JNIEXPORT void JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeSetPlInitPhase(JNIEnv* env, jobject thiz, jfloat phase01) {



    const float p = (float)phase01;



    g_bmPlBInitPhase.store((p >= 0.0f && p < 1.0f) ? p : -1.0f, std::memory_order_relaxed);



    LOGI("PL init phase: B=%.3f (%s)", (double)p, (p >= 0.0f && p < 1.0f) ? "from beatgrid" : "unknown->assume downbeat");



}







// --- 【V8.222】变速引擎选择：true=Rubber Band（高质量），false=WSOLA（旧引擎，保留）。



// 【V8.229】只存偏好，下次武装生效：中途强退当前拉伸（旧写法 live=false）会让混音循环



// 在过渡中途回退直读 ring —— 而读位置已被引擎消费过，必然跳帧出杂音。---



extern "C"
JNIEXPORT void JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeSetDiagVerbose(JNIEnv *env, jobject thiz, jboolean on) {
    // [V8.344] Runtime gate for the per-frame audio-callback diagnostics.
    // Off by default: those logs fire every callback inside their window and
    // __android_log_print on the realtime thread is an underrun source.
    // Enable only when triaging a live audio glitch, then disable again.
    g_diagVerbose.store(on == JNI_TRUE, std::memory_order_relaxed);
    LOGI("diag verbose = %s", on == JNI_TRUE ? "ON (per-frame logs enabled)" : "off");
}

extern "C"



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetBeatmatchEngine(JNIEnv *env, jobject thiz, jboolean rb) {



    g_bmUseRb.store(rb == JNI_TRUE, std::memory_order_relaxed);



    LOGI("Beatmatch engine: %s (takes effect next crossfade)", rb == JNI_TRUE ? "rubberband" : "wsola");

}

// 【V8.427】渐变拉速总开关（全模板共用的速度地基）。
// on=true：任何模板的过渡都从 A 的原速起步、在推子推进中平滑拉到 B 的速度；
// on=false：起步即锁定目标比率（过渡开头速度一次到位）。
// 下次 crossfade arming 时读取，运行中的过渡不受影响。
JNIEXPORT void JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeSetTempoRamp(JNIEnv *env, jobject thiz,
                                                              jboolean on) {
    (void)env; (void)thiz;
    g_tempoRamp.store(on == JNI_TRUE, std::memory_order_relaxed);
    LOGI("TempoRamp: %d (all templates share the gradual pull)", (int)(on == JNI_TRUE));
}







// --- 【V7.80】5段图形均衡器预设 ---



// 将28个品牌预设映射到DSP 5-band peaking EQ



// 标准频率: 60Hz / 230Hz / 910Hz / 3.6kHz / 14kHz



static const float kEq5BandDefaultFreqs[5] = {60.0f, 230.0f, 910.0f, 3600.0f, 14000.0f};







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetDspEq5Band(JNIEnv *env, jobject thiz,



                                                               jfloatArray gainsDb, jfloatArray freqsHz) {



    // 【V8.2 hiby】copy JNI arrays OUTSIDE the lock: Get/ReleaseFloatArrayElements



    // can pin/unpin memory and are not cheap. Holding g_eqMutex across them widens



    // the window where the audio callback's try_lock fails -> drop-out.



    jsize len = env->GetArrayLength(gainsDb);



    if (len <= 0) return;



    float gains[5] = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f};



    float freqs[5];



    for (int i = 0; i < 5; i++) freqs[i] = kEq5BandDefaultFreqs[i];



    {



        jfloat* g = env->GetFloatArrayElements(gainsDb, nullptr);



        for (int i = 0; i < len && i < 5; i++) gains[i] = g[i];



        env->ReleaseFloatArrayElements(gainsDb, g, JNI_ABORT);



        if (freqsHz) {



            jfloat* f = env->GetFloatArrayElements(freqsHz, nullptr);



            for (int i = 0; i < len && i < 5; i++) freqs[i] = f[i];



            env->ReleaseFloatArrayElements(freqsHz, f, JNI_ABORT);



        }



    }







    std::lock_guard<std::mutex> eqLock(g_eqMutex);



    float sr = static_cast<float>(g_sampleRate.load());



    if (sr <= 0) return;







    struct { BiquadFilter* L; BiquadFilter* R; } bands[5] = {



        {&g_eqBand1L, &g_eqBand1R}, {&g_eqBand2L, &g_eqBand2R},



        {&g_eqBand3L, &g_eqBand3R}, {&g_eqBand4L, &g_eqBand4R},



        {&g_eqBand5L, &g_eqBand5R}



    };







    for (int i = 0; i < len && i < 5; i++) {



        float gainDb = gains[i];



        float freqHz = freqs[i];



        float Q = 1.4f;  // Graphic EQ Q — narrower than default to reduce band overlap



        // 【V8.2】no reset() on live gain change — process() coefficient ramp



        // handles the transition; clearing IIR state causes an audible click.



        if (fabsf(gainDb) < 0.1f) {



            bands[i].L->setFlat(); bands[i].R->setFlat();



        } else {



            bands[i].L->setPeaking(sr, freqHz, gainDb, Q);



            bands[i].R->setPeaking(sr, freqHz, gainDb, Q);



        }



    }







    // 确保 bands 4-5 在 len<5 时也是 flat



    for (int i = len; i < 5; i++) {



        bands[i].L->setFlat(); bands[i].R->setFlat();



    }







    // Pre-gain headroom：防止正增益导致削波



    // 取所有频段最大正增益，折算为线性衰减系数



    float maxGain = 0.0f;



    for (int i = 0; i < 5; i++) {



        if (gains[i] > maxGain) maxGain = gains[i];



    }



    float preGainDb = -maxGain;  // 1:1 headroom — Look-Ahead Limiter handles cascade overshoot



    g_dspEqPreGain = powf(10.0f, preGainDb / 20.0f);







    g_eq5BandEnabled.store(true);



    g_dspEqEnabled.store(true);







    LOGI("DSP 5-band EQ preset applied: [%.1f, %.1f, %.1f, %.1f, %.1f] dB",



         gains[0], gains[1], gains[2], gains[3], gains[4]);



}







// 【V7.200】MSEB activation guard — protects 5-band EQ from being overwritten by DSP mode / reset



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetMsebActive(JNIEnv *env, jobject thiz, jboolean active) {



    g_msebActive.store(active);



    LOGI("MSEB active = %s", active ? "YES" : "NO");



}







// --- 【V8.2】MSEB 10-band subjective-dimension EQ ---



// 11 subjective dims -> 10 biquads (independent instances, crossfade on coeff change).



// Signature: (gainsDb: FloatArray, freqsHz: FloatArray, qValues: FloatArray)



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetMseb10Band(JNIEnv *env, jobject thiz,



                                                               jfloatArray gainsDb, jfloatArray freqsHz, jfloatArray qValues) {



    // Copy JNI arrays OUTSIDE the lock (V8.2 hiby: lock held only for pure math).



    jsize len = env->GetArrayLength(gainsDb);



    if (len <= 0) return;



    float gains[10] = {0}; float freqs[10] = {0}; float qs[10] = {0};



    {



        jfloat* g = env->GetFloatArrayElements(gainsDb, nullptr);



        for (int i = 0; i < len && i < 10; i++) gains[i] = g[i];



        env->ReleaseFloatArrayElements(gainsDb, g, JNI_ABORT);



        if (freqsHz) {



            jfloat* f = env->GetFloatArrayElements(freqsHz, nullptr);



            for (int i = 0; i < len && i < 10; i++) freqs[i] = f[i];



            env->ReleaseFloatArrayElements(freqsHz, f, JNI_ABORT);



        }



        if (qValues) {



            jfloat* q = env->GetFloatArrayElements(qValues, nullptr);



            for (int i = 0; i < len && i < 10; i++) qs[i] = q[i];



            env->ReleaseFloatArrayElements(qValues, q, JNI_ABORT);



        }



    }







    std::lock_guard<std::mutex> eqLock(g_eqMutex);



    float sr = static_cast<float>(g_sampleRate.load());



    if (sr <= 0) sr = 48000.0f;







    struct { BiquadFilter* L; BiquadFilter* R; } bands[10] = {



        {&g_msebBand1L, &g_msebBand1R}, {&g_msebBand2L, &g_msebBand2R},



        {&g_msebBand3L, &g_msebBand3R}, {&g_msebBand4L, &g_msebBand4R},



        {&g_msebBand5L, &g_msebBand5R}, {&g_msebBand6L, &g_msebBand6R},



        {&g_msebBand7L, &g_msebBand7R}, {&g_msebBand8L, &g_msebBand8R},



        {&g_msebBand9L, &g_msebBand9R}, {&g_msebBand10L, &g_msebBand10R}



    };







    for (int i = 0; i < 10; i++) {



        float gainDb = (i < len) ? gains[i] : 0.0f;



        float freqHz = (i < len && freqs[i] > 0.0f) ? freqs[i] : 1000.0f;



        float Q = (i < len && qs[i] > 0.0f) ? qs[i] : 1.0f;



        // 【V8.3】只在增益真正变化时更新 + crossfade；未变 band 跳过，



        // 避免拖动单个滑块时全部 10 个 band 同时双滤波并行（underrun + 低频相位差异 -> 哗哗声）。



        if (fabsf(gainDb - g_lastMsebGains[i]) < 0.01f) continue;



        g_lastMsebGains[i] = gainDb;



        // crossfade handles transition — no reset() on live change.



        if (fabsf(gainDb) < 0.1f) {



            bands[i].L->setFlat(); bands[i].R->setFlat();



        } else if (freqHz < 250.0f) {



            // 【V8.3 ringing 根治】低频段用 low-shelf（无共振峰、无 ringing），



            // 替代 peaking 的共振峰（32/60/120Hz ringing 长达 10ms/5ms，EDM 低音叠加成哗哗）。



            // Q=0.707（Butterworth 临界阻尼，无 overshoot）。



            bands[i].L->setLowShelf(sr, freqHz, gainDb, 0.707f);



            bands[i].R->setLowShelf(sr, freqHz, gainDb, 0.707f);



        } else {



            bands[i].L->setPeaking(sr, freqHz, gainDb, Q);



            bands[i].R->setPeaking(sr, freqHz, gainDb, Q);



        }



    }







    // 【V8.3 preGain 方案 B】只补低频 3 段（<250Hz）正增益总和。



    // 低频 low-shelf 在 30Hz 以下几乎全部叠加（会级联削波），需按“和”补偿；



    // 中高频 peaking 频点分离、峰值不同时出现，+2~3dB 几乎不削波，交给 limiter 兔底。



    // 按“全部正增益总和”补偿会把整体响度压掉一半（听感“开 MSEB 变轻”），故只补低频。



    float sumPosGain = 0.0f;



    for (int i = 0; i < 3; i++) if (gains[i] > 0.0f) sumPosGain += gains[i];



    // 【V8.513】音量修复：原按 sumPosGain 全量预留(-8.3dB)过度保守——低频 3 段峰值并非总同时到顶。
    //   只预留 kMsebHeadroomScale(0.5) 比例，其余由下游 limiter+softclip 兜极少数瞬态超峰（听感无碍），
    //   MSEB 调音一点不丢、整体音量直接回升。更激进可改用「衰减下限 -3dB」写法（见下注释）。
    const float kMsebHeadroomScale = 0.5f;
    float preGain = powf(10.0f, (-sumPosGain * kMsebHeadroomScale) / 20.0f);
    // 备选（更激进，衰减下限 -3dB）：const float kFloor = 0.708f; if (preGain < kFloor) preGain = kFloor;
    g_msebPreGain.store(preGain);







    g_mseb10Enabled.store(true);



    // 【V8.176】不再顺手把 5 段图形 EQ 也点亮。以前这里是 store(true)：



    // 只要应用过一次 MSEB，"EQ5" 那颗绿灯就再也灭不掉（nativeGetDspChainState



    // 第 4 位把两个标志 OR 在一起），用户关了 EQ 却看见灯还亮，像中了 bug。



    // 两条链本来就互斥，MSEB 生效时 5 段 EQ 必然不参与运算。



    g_eq5BandEnabled.store(false);







    LOGI("MSEB 10-band applied: [%.1f %.1f %.1f %.1f %.1f %.1f %.1f %.1f %.1f %.1f] dB preGain=%.3f",



         gains[0], gains[1], gains[2], gains[3], gains[4],



         gains[5], gains[6], gains[7], gains[8], gains[9], g_msebPreGain.load());



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeResetMseb10Band(JNIEnv *env, jobject thiz) {



    std::lock_guard<std::mutex> eqLock(g_eqMutex);



    struct { BiquadFilter* L; BiquadFilter* R; } bands[10] = {



        {&g_msebBand1L, &g_msebBand1R}, {&g_msebBand2L, &g_msebBand2R},



        {&g_msebBand3L, &g_msebBand3R}, {&g_msebBand4L, &g_msebBand4R},



        {&g_msebBand5L, &g_msebBand5R}, {&g_msebBand6L, &g_msebBand6R},



        {&g_msebBand7L, &g_msebBand7R}, {&g_msebBand8L, &g_msebBand8R},



        {&g_msebBand9L, &g_msebBand9R}, {&g_msebBand10L, &g_msebBand10R}



    };



    for (int i = 0; i < 10; i++) { bands[i].L->reset(); bands[i].R->reset(); bands[i].L->setFlat(); bands[i].R->setFlat(); }



    for (int i = 0; i < 10; i++) g_lastMsebGains[i] = 999.0f;  // 强制下次重新应用



    g_mseb10Enabled.store(false);



    g_msebPreGain.store(1.0f);



    LOGI("MSEB 10-band cleared");



}







// --- 【V8.3】M/S 声场（跨声道矩阵）---



// soundstage(-10..+10) -> S 增益 (width), imaging(-10..+10) -> M 增益 (center).



// 映射：width = 1 + soundstage*0.08 (0.2..1.8), center = 1 + imaging*0.06 (0.4..1.6)



// 【V8.172】只改宽度/结像仍走这里；新增的环绕强度、高度感走 nativeSetDtsStage。



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetMsStage(JNIEnv *env, jobject thiz,



                                                            jfloat soundstage, jfloat imaging) {



    float width = 1.0f + soundstage * 0.08f;



    float center = 1.0f + imaging * 0.06f;



    if (width < 0.1f) width = 0.1f;



    if (center < 0.1f) center = 0.1f;



    g_msWidth.store(width);



    g_msCenter.store(center);



    bool active = (fabsf(soundstage) > 0.01f || fabsf(imaging) > 0.01f);



    g_msEnabled.store(active);



    if (!active) { g_dts.reset(); g_curMsWidth = 1.0f; g_curMsCenter = 1.0f; }



    LOGI("M/S stage: width=%.3f center=%.3f enabled=%s", width, center, active ? "YES" : "NO");



}







// 【V8.172】DTS 高级参数：环绕强度、高度感。



// surround 0..10 → 0..0.8（旧版环绕量被宽度绑死，实际只有 0.04，这里纯控制量 5~20 倍）；



// height 0..10 → 0..1（耳廓共振深度 + 双耳权重差，决定"只在两侧变宽"还是"真的有前后"）。



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetDtsStage(JNIEnv *env, jobject thiz,



                                                             jfloat surround, jfloat height) {



    float su = surround * 0.08f;



    float ht = height * 0.10f;



    if (su < 0.0f) su = 0.0f;



    if (su > 0.8f) su = 0.8f;



    if (ht < 0.0f) ht = 0.0f;



    if (ht > 1.0f) ht = 1.0f;



    g_dtsSurround.store(su);



    g_dtsHeight.store(ht);



    LOGI("DTS stage: surround=%.3f height=%.3f", su, ht);



}







// 【V8.172】蓝牙链路自适应：按 A2DP codec 收敛环绕的高频反射量。



// SBC 的高频被砍到 ~16kHz 且带噪底，环绕线索再往上撑只会变脏；



// LDAC/LHDC/aptX Adaptive 近 transparent，全量保留。传 NULL 表示非蓝牙输出。



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetDtsBtProfile(JNIEnv *env, jobject thiz,



                                                                 jstring codec) {



    float tilt = 1.0f;



    const char* cs = (codec != NULL) ? env->GetStringUTFChars(codec, NULL) : NULL;



    if (cs != NULL) {



        // 先拷进 std::string 再释放 jstring：cs 一旦 Release 就悬空，不能在日志里再用。



        std::string c(cs);



        env->ReleaseStringUTFChars(codec, cs);



        if (c.find("SBC") != std::string::npos)          tilt = 0.40f;



        else if (c.find("AAC") != std::string::npos)     tilt = 0.70f;



        else if (c.find("LDAC") != std::string::npos ||



                 c.find("LHDC") != std::string::npos)    tilt = 1.00f;



        else if (c.find("aptX") != std::string::npos)    tilt = 0.90f;   // Adaptive 近透明，略收



        else if (!c.empty())                             tilt = 0.80f;   // 未知 codec，保守



        g_dtsHfTilt.store(tilt);



        LOGI("DTS bt profile tilt=%.2f (codec=%s)", tilt, c.c_str());



        return;



    }



    g_dtsHfTilt.store(1.0f);   // 非蓝牙输出（有线 / USB DAC）：全量保留



    LOGI("DTS bt profile tilt=1.00 (no bluetooth)");



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeResetMsStage(JNIEnv *env, jobject thiz) {



    g_msWidth.store(1.0f);



    g_msCenter.store(1.0f);



    g_msEnabled.store(false);



    g_dtsSurround.store(0.35f);



    g_dtsHeight.store(0.50f);



    g_dts.reset();



    g_curMsWidth = 1.0f;



    g_curMsCenter = 1.0f;



    LOGI("M/S stage reset (unity)");



}







// 【V8.3】Crossfeed JNI — 消除头中效应（仅 Oboe）。amount 0~1。



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetCrossfeed(JNIEnv *env, jobject thiz, jfloat amount) {



    float a = amount;



    if (a > 1.0f) a = 1.0f;



    if (a < 0.0f) a = 0.0f;



    g_crossfeedAmount.store(a);



    bool active = a > 0.001f;



    g_crossfeedEnabled.store(active);



    if (!active) { g_crossfeed.reset(); g_curCrossfeedAmount = 0.0f; }



    LOGI("Crossfeed: amount=%.3f enabled=%s", a, active ? "YES" : "NO");



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeResetCrossfeed(JNIEnv *env, jobject thiz) {



    g_crossfeedAmount.store(0.0f);



    g_crossfeedEnabled.store(false);



    g_crossfeed.reset();



    g_curCrossfeedAmount = 0.0f;



    LOGI("Crossfeed reset");



}







// 【V8.3】瞬态整形 JNI — impulseResponse 维度映射。



// amount = impulseResponse / 10（-1..+1），正增强 attack、负柔化。



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetTransient(JNIEnv *env, jobject thiz, jfloat amount) {



    float a = amount;



    if (a > 1.0f) a = 1.0f;



    if (a < -1.0f) a = -1.0f;



    g_transientAmount.store(a);



    bool active = fabsf(a) > 0.001f;



    g_transientEnabled.store(active);



    if (!active) { g_tsFastEnvL = g_tsSlowEnvL = g_tsFastEnvR = g_tsSlowEnvR = 0.0f; }



    LOGI("Transient shaper: amount=%.3f enabled=%s", a, active ? "YES" : "NO");



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeResetTransient(JNIEnv *env, jobject thiz) {



    g_transientAmount.store(0.0f);



    g_transientEnabled.store(false);



    g_tsFastEnvL = g_tsSlowEnvL = g_tsFastEnvR = g_tsSlowEnvR = 0.0f;



    LOGI("Transient shaper reset");



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeResetDspEq5Band(JNIEnv *env, jobject thiz) {



    // 【V7.200】MSEB active → refuse reset, MSEB owns the 5-band EQ pipeline



    if (g_msebActive.load()) {



        LOGI("nativeResetDspEq5Band: refused — MSEB active");



        return;



    }



    // 【V8.3】AutoEQ active → refuse reset，g_eqBand1-5 正承载 AutoEQ 前 5 段，



    // 重置会清掉耳机修正（AutoEQ 与 5 段图形 EQ 共享 g_eqBand1-5 实例）。



    if (g_autoEqEnabled.load()) {



        LOGI("nativeResetDspEq5Band: refused — AutoEQ active");



        return;



    }



    std::lock_guard<std::mutex> eqLock(g_eqMutex);



    struct { BiquadFilter* L; BiquadFilter* R; } bands[5] = {



        {&g_eqBand1L, &g_eqBand1R}, {&g_eqBand2L, &g_eqBand2R},



        {&g_eqBand3L, &g_eqBand3R}, {&g_eqBand4L, &g_eqBand4R},



        {&g_eqBand5L, &g_eqBand5R}



    };



    for (int i = 0; i < 5; i++) {



        bands[i].L->reset(); bands[i].R->reset();



        bands[i].L->setFlat(); bands[i].R->setFlat();



    }



    g_eq5BandEnabled.store(false);



    g_dspEqPreGain = 1.0f;  // 重置 pre-gain 为 0dB



    LOGI("DSP 5-band EQ preset cleared");



}







// --- AutoEQ 10-band ---



// filterType: 0=Peaking, 1=HighShelf, 2=LowShelf



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetAutoEq10Band(JNIEnv *env, jobject thiz,



    jfloatArray gainsDb, jfloatArray freqsHz, jfloatArray qValues, jintArray filterTypes, jfloat preampDb) {



    std::lock_guard<std::mutex> eqLock(g_eqMutex);



    int sr = g_sampleRate.load();



    if (sr <= 0) sr = 48000;







    // 【V8.180】下=int i<10 无条件读 gains[i]/freqs[i]/qs[i]/types[i]，此前没有长度校验：



    // Java 侧频段数一旦小于 10 就是越界读 JNI 基址缓冲（可崩/系数被随机值污染→一声巨响）。



    const jsize nGains = env->GetArrayLength(gainsDb);



    const jsize nFreqs = env->GetArrayLength(freqsHz);



    const jsize nQs    = env->GetArrayLength(qValues);



    const jsize nTypes = env->GetArrayLength(filterTypes);



    const int bandCount = std::min(std::min(nGains, nFreqs), std::min(nQs, nTypes));



    if (bandCount < 1) {



        LOGE("nativeSetAutoEq10Band: empty arrays, ignored");



        return;



    }



    jfloat* gains = env->GetFloatArrayElements(gainsDb, nullptr);



    jfloat* freqs = env->GetFloatArrayElements(freqsHz, nullptr);



    jfloat* qs    = env->GetFloatArrayElements(qValues, nullptr);



    jint*   types = env->GetIntArrayElements(filterTypes, nullptr);







    struct { BiquadFilter* L; BiquadFilter* R; } bands[10] = {



        {&g_eqBand1L, &g_eqBand1R}, {&g_eqBand2L, &g_eqBand2R},



        {&g_eqBand3L, &g_eqBand3R}, {&g_eqBand4L, &g_eqBand4R},



        {&g_eqBand5L, &g_eqBand5R}, {&g_autoEqBand6L, &g_autoEqBand6R},



        {&g_autoEqBand7L, &g_autoEqBand7R}, {&g_autoEqBand8L, &g_autoEqBand8R},



        {&g_autoEqBand9L, &g_autoEqBand9R}, {&g_autoEqBand10L, &g_autoEqBand10R}



    };







    // 【V8.170】修正强度混合：把每条频段的修正量按 mix 缩放（曲线形状不变）。



    // 曲线形状保持不变 = 削峰量和补偿量同步缩小，等价于官方 half-shelf 混合的



    // 简化实现；preamp 也按同比例回退，避免 mix 变小时仍留着全量 headroom 浪费。



    const float mix = g_autoEqMix.load(std::memory_order_relaxed);



    float effPreamp = preampDb;







    for (int i = 0; i < 10 && i < bandCount; i++) {



        float gain = gains[i] * mix;



        if (fabsf(gains[i]) < 0.01f || fabsf(gain) < 0.01f) {



            bands[i].L->setFlat(); bands[i].R->setFlat();



            continue;



        }



        int type = types[i];



        if (type == 1) {  // HighShelf



            bands[i].L->setHighShelf(sr, freqs[i], gain, qs[i]);



            bands[i].R->setHighShelf(sr, freqs[i], gain, qs[i]);



        } else if (type == 2) {  // LowShelf



            bands[i].L->setLowShelf(sr, freqs[i], gain, qs[i]);



            bands[i].R->setLowShelf(sr, freqs[i], gain, qs[i]);



        } else {  // Peaking (default)



            if (freqs[i] < 250.0f) {



                // 【V8.3 ringing 根治】低频 Peaking 切 low-shelf（无共振峰、无 ringing）。



                bands[i].L->setLowShelf(sr, freqs[i], gain, 0.707f);



                bands[i].R->setLowShelf(sr, freqs[i], gain, 0.707f);



            } else {



                bands[i].L->setPeaking(sr, freqs[i], gain, qs[i]);



                bands[i].R->setPeaking(sr, freqs[i], gain, qs[i]);



            }



        }



    }







    // preamp 为负（衰减 headroom）。混强度低时不需要.full 的 headroom 余量：



    // 只保留 60% 的补偿量，剩余增益还给音量。



    if (mix < 0.999f && preampDb < 0.0f) effPreamp = preampDb * (0.4f + 0.6f * mix);



    g_autoEqPreGain = powf(10.0f, effPreamp / 20.0f);



    g_autoEqEnabled.store(true);



    g_eq5BandEnabled.store(false);  // 互斥



    g_dspEqEnabled.store(true);







    env->ReleaseFloatArrayElements(gainsDb, gains, 0);



    env->ReleaseFloatArrayElements(freqsHz, freqs, 0);



    env->ReleaseFloatArrayElements(qValues, qs, 0);



    env->ReleaseIntArrayElements(filterTypes, types, 0);







    LOGI("AutoEQ 10-band applied: preamp=%.1fdB mix=%.2f", effPreamp, mix);



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeResetAutoEq(JNIEnv *env, jobject thiz) {



    std::lock_guard<std::mutex> eqLock(g_eqMutex);



    // 重置全部 10 段：前 5 段复用 g_eqBand1-5，后 5 段 g_autoEqBand6-10



    struct { BiquadFilter* L; BiquadFilter* R; } bands[10] = {



        {&g_eqBand1L, &g_eqBand1R}, {&g_eqBand2L, &g_eqBand2R},



        {&g_eqBand3L, &g_eqBand3R}, {&g_eqBand4L, &g_eqBand4R},



        {&g_eqBand5L, &g_eqBand5R}, {&g_autoEqBand6L, &g_autoEqBand6R},



        {&g_autoEqBand7L, &g_autoEqBand7R}, {&g_autoEqBand8L, &g_autoEqBand8R},



        {&g_autoEqBand9L, &g_autoEqBand9R}, {&g_autoEqBand10L, &g_autoEqBand10R}



    };



    for (int i = 0; i < 10; i++) {



        bands[i].L->reset(); bands[i].L->setFlat();



        bands[i].R->reset(); bands[i].R->setFlat();



    }



    g_autoEqEnabled.store(false);



    g_autoEqPreGain = 1.0f;



    LOGI("AutoEQ 10-band cleared");



}







// --- AGC ---



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetAgcEnabled(JNIEnv *env, jobject thiz, jboolean enabled) {



    g_agcEnabled.store(enabled != 0);



    // 【V8.212 AGC 修正①】原来只置了原子开关、没置 HeadroomAGC::enabled，



    // 而 updateAndGetGainFromEnergy() 开头 `if (!enabled) return 1.0f` ——



    // 也就是说开关打开后代码路径照跑、增益却恒为 1.0，AGC 从来没真正起过作用。



    g_agc.enabled = enabled != 0;



    if (!g_agcEnabled.load()) { g_agc.reset(); g_agcPrevFramePower = -1.0f; }



    LOGI("AGC %s", enabled ? "enabled" : "disabled");



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetAgcTarget(JNIEnv *env, jobject thiz, jfloat targetDb) {



    float linearTarget = std::pow(10.0f, targetDb / 10.0f);



    g_agc.targetRms = std::max(1e-6f, linearTarget);



    g_agcTargetDb.store(targetDb);



    LOGI("AGC target: %.1f dBFS (linear=%.4f)", targetDb, g_agc.targetRms);



}







// --- Night mode ---



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetNightMode(JNIEnv *env, jobject thiz, jboolean enabled) {



    g_nightMode.store(enabled != 0);



    LOGI("Night mode %s", enabled ? "ON" : "OFF");



}







JNIEXPORT jboolean JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeIsNightMode(JNIEnv *env, jobject thiz) {



    return g_nightMode.load();



}







// [V8.119] 宽裕余量档：true = WIDE (0.95/0.92)，false = LEGACY (0.85/2/3)



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetWideHeadroom(JNIEnv *env, jobject thiz, jboolean enabled) {



    g_wideHeadroom.store(enabled != 0);



    g_limiter.refreshThreshold();   // [V8.131] apply the new ceiling immediately



    LOGI("WideHeadroom %s (limiter=%.2f softClipKnee=%.2f)", enabled ? "ON" : "OFF",



         enabled ? 0.95f : 0.85f, enabled ? 0.92f : (2.0f / 3.0f));



}







// [V8.119] 原声直通开关：true = 整条 DSP 旁路，原始 PCM 直出



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetDirectPassthrough(JNIEnv *env, jobject thiz, jboolean enabled) {



    g_directPassthrough.store(enabled != 0);



    LOGI("DirectPassthrough %s", enabled ? "ON (raw PCM, all DSP bypassed)" : "OFF (wide headroom 0.95/0.92)");



}







// 【V8.313】左右声道平衡。bal: -1=全左 / 0=居中 / +1=全右。



// 存float，消费端查表 + 线性插值取恒功率增益（见 applyStereoBalance）。



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetStereoBalance(JNIEnv *env, jobject thiz, jfloat bal) {



    float b = bal;



    if (b > 1.0f) b = 1.0f; else if (b < -1.0f) b = -1.0f;



    g_stereoBalance.store(b);



    float lg, rg;



    stereoBalanceGains(lg, rg);



    LOGI("StereoBalance %.3f -> L=%.3f R=%.3f (linear channel volume)", (double)b, (double)lg, (double)rg);



}







// --- 动态压缩（Master Bus Compressor）---



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetCompressorEnabled(JNIEnv *env, jobject thiz, jboolean enabled) {



    g_compressorEnabled.store(enabled != 0);



    g_compressor.enabled = (enabled != 0);   // 关键：同步对象内 enabled，否则 process() 直接 return



    if (!enabled) g_compressor.reset();



    LOGI("Compressor %s", enabled ? "enabled" : "disabled");



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetCompressorParams(JNIEnv *env, jobject thiz,



        jfloat thresholdDb, jfloat ratio, jfloat attackMs, jfloat releaseMs, jfloat makeupDb) {



    g_compressorThresholdDb.store(thresholdDb);



    g_compressorRatio.store(ratio);



    g_compressorAttackMs.store(attackMs);



    g_compressorReleaseMs.store(releaseMs);



    g_compressorMakeupDb.store(makeupDb);



    g_compressor.configure(thresholdDb, ratio, attackMs, releaseMs, makeupDb);



    LOGI("Compressor params: thr=%.1f ratio=%.1f atk=%.1f rel=%.1f makeup=%.1f",



         thresholdDb, ratio, attackMs, releaseMs, makeupDb);



}







// ============================================================================



// 【V8.170】新 DSP 模块的控制入口



//   空间混响 / 动态 EQ / 多频段限幅 / AutoEQ 修正强度



// 说明：wet、depth 这类连续量走 atomic，回调每帧读；room、damping 这种



// 需要重算 IR 的只在参数真正变化时重配置，避免拖动滑块时反复重建尾音。



// ============================================================================







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetSpaceReverbEnabled(JNIEnv *env, jobject thiz,



        jboolean enabled) {



    g_spaceReverbEnabled.store(enabled != 0);



    if (!enabled) g_spaceReverb.reset();



    LOGI("SpaceReverb %s", enabled ? "enabled" : "disabled");



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetSpaceReverbWet(JNIEnv *env, jobject thiz, jfloat wet) {



    g_spaceReverbWet.store(wet < 0.0f ? 0.0f : (wet > 1.0f ? 1.0f : wet));



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetSpaceReverbShape(JNIEnv *env, jobject thiz,



        jfloat roomSize, jfloat damping) {



    int sr = g_sampleRate.load();



    if (sr <= 0) sr = 48000;



    g_spaceReverbRoom.store(roomSize);



    g_spaceReverbDamp.store(damping);



    g_spaceReverb.configure((float)sr, roomSize, damping);



    LOGI("SpaceReverb shape: room=%.2f damping=%.2f", roomSize, damping);



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetDynamicEqEnabled(JNIEnv *env, jobject thiz,



        jboolean enabled) {



    g_dynEqEnabled.store(enabled != 0);



    if (!enabled) { g_dynEqDepth.store(0.0f); g_dynEq.reset(); }



    LOGI("DynamicEQ %s", enabled ? "enabled" : "disabled");



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetDynamicEqDepth(JNIEnv *env, jobject thiz, jfloat depth) {



    float d = depth < 0.0f ? 0.0f : (depth > 1.0f ? 1.0f : depth);



    g_dynEqDepth.store(d);



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetDynamicEqThreshold(JNIEnv *env, jobject thiz, jfloat thr) {



    float t = thr < 0.05f ? 0.05f : (thr > 1.0f ? 1.0f : thr);



    g_dynEq.thresh = t;



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetMultiBandLimiterEnabled(JNIEnv *env, jobject thiz,



        jboolean enabled) {



    g_multiBandLimiterEnabled.store(enabled != 0);



    g_mbLimiter.reset();



    LOGI("MultiBandLimiter %s", enabled ? "enabled" : "disabled");



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetAutoEqMix(JNIEnv *env, jobject thiz, jfloat mix) {



    float m = mix < 0.0f ? 0.0f : (mix > 1.0f ? 1.0f : mix);



    g_autoEqMix.store(m);



    LOGI("AutoEQ mix=%.2f", m);



}







// --- 【V8.170】响度统一管理：按元数据 ReplayGain/LUFS 折算的整枚增益 ---



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetNormalizationGain(JNIEnv *env, jobject thiz, jfloat db) {



    float d = db < -24.0f ? -24.0f : (db > 24.0f ? 24.0f : db);



    float lin = powf(10.0f, d / 20.0f);



    g_normGain.store(lin, std::memory_order_relaxed);



    LOGI("NormGain %.2f dB (%.4f)", d, lin);



}







// 【V8.210】诊断用回读口：native 里**真正挂着**的那枚归一化增益（dB）。



// 下发值和生效值不是一回事 —— Oboe 播放器重建后 native 的 g_normGain 是全新的 1.0，



// 只回读 Kotlin 侧「上次下发的值」会骗人，诊断页要拿这个来对账。



JNIEXPORT jfloat JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetNormalizationGain(JNIEnv *env, jobject thiz) {



    const float g = g_normGain.load(std::memory_order_relaxed);



    return 20.0f * log10f(g > 1e-6f ? g : 1e-6f);



}







// --- Loudness Comp (ISO 226 等响补偿) ---



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetLoudnessEnabled(JNIEnv *env, jobject thiz, jboolean enabled) {



    g_loudnessEnabled.store(enabled != 0);



    g_loudness.setEnabled(enabled != 0);



    LOGI("Loudness %s", enabled ? "enabled" : "disabled");



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetLoudnessIntensity(JNIEnv *env, jobject thiz, jfloat intensity) {



    g_loudnessIntensity.store(intensity);



    g_loudness.setIntensity(intensity);



    LOGI("Loudness intensity=%.2f", intensity);



}







// --- Dither ---



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetDitherEnabled(JNIEnv *env, jobject thiz, jboolean enabled) {



    g_ditherEnabled.store(enabled != 0);



    LOGI("TPDF Dither %s", enabled ? "ON" : "OFF");



}







// --- 【2026-09-07】A2DP 编码前预补偿 ---



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetBtPreEmphasis(JNIEnv *env, jobject thiz, jboolean enabled, jfloat codecDb) {



    g_btPreEnabled.store(enabled != 0);



    g_btPreCodecDb.store(codecDb);



    LOGI("A2DP PreEmphasis %s codecDb=%.1fdB", enabled ? "ON" : "OFF", codecDb);



}







// 【V8.295】主动按默认路由重建输出流（由 Java 侧 AudioDeviceCallback 在蓝牙断开时调用）。



// 与 onErrorAfterClose 内的看门狗互补：看门狗只在「回调停滞」时重建，



// 而蓝牙断开时回调仍会跑（只是写进已死的 A2DP 端点），看门狗捕不到 → 这里主动重建到默认（扬声器）路由。



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeReopenOutputOnRouteReady(JNIEnv *env, jobject thiz) {



    // 【V8.317】守卫从 g_isPlaying 改成「本次会话曾播过」的 latch。



    // 蓝牙断连时 Android 会暂停媒体（走 nativePause → g_isPlaying=false），



    // 旧守卫在这里直接 return → 重建音频流永远不发生 → 卡死。



    if (!g_playSessionLatched.load()) return;



    LOGI("nativeReopenOutputOnRouteReady: reopen on default route (rate=%d, isPlaying=%d)",



         g_sampleRate.load(), (int)g_isPlaying.load());



    reopenOutputStream(g_sampleRate.load());



}







JNIEXPORT jboolean JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeIsBtPreEmphasisEnabled(JNIEnv *env, jobject thiz) {



    return g_btPreEnabled.load();



}







JNIEXPORT jboolean JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeIsDitherEnabled(JNIEnv *env, jobject thiz) {



    return g_ditherEnabled.load();



}







// --- DC Blocker ---



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetDcBlockEnabled(JNIEnv *env, jobject thiz, jboolean enabled) {



    g_dcBlockEnabled.store(enabled != 0);



    LOGI("DC Blocker %s", enabled ? "ON" : "OFF");



}







JNIEXPORT jboolean JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeIsDcBlockEnabled(JNIEnv *env, jobject thiz) {



    return g_dcBlockEnabled.load();



}







// --- V7.08: Silence test / DSP disabled samples ---



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetSilenceTest(JNIEnv *env, jobject thiz, jboolean enabled) {



    g_debugSilenceTest.store(enabled != 0);



    LOGI("Silence test %s", enabled ? "ON" : "OFF");



}







JNIEXPORT jlong JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetDspDisabledSamples(JNIEnv *env, jobject thiz) {



    return g_dspDisabledSampleCount.load(std::memory_order_relaxed);



}







// --- V7.09: Sine wave self-test ---



JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeSetSineTest(JNIEnv *env, jobject thiz, jboolean enabled) {



    g_sineTestEnabled.store(enabled != 0);



    g_sinePhase.store(0.0f);



    LOGI("Sine test (440Hz) %s", enabled ? "ON" : "OFF");



}







JNIEXPORT jboolean JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeIsSineTestRunning(JNIEnv *env, jobject thiz) {



    return g_sineTestEnabled.load();



}







// --- V7.10/V7.16: Diagnostic counters ---



JNIEXPORT jlong JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetCallbackCount(JNIEnv *env, jobject thiz) {



    return g_callbackCount.load(std::memory_order_relaxed);



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeResetCallbackCount(JNIEnv *env, jobject thiz) {



    g_callbackCount.store(0, std::memory_order_relaxed);



}







JNIEXPORT jint JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetStreamError(JNIEnv *env, jobject thiz) {



    return g_streamError.load();



}







JNIEXPORT jboolean JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeIsDecoderThreadRunning(JNIEnv *env, jobject thiz) {



    return g_decoderThreadRunning.load();



}







JNIEXPORT jint JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetDecoderFramesOutput(JNIEnv *env, jobject thiz) {



    return g_decoderFramesOutput.load();



}







JNIEXPORT jint JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetRingBufferFill(JNIEnv *env, jobject thiz) {



    return g_ringBufferFill.load();



}







JNIEXPORT jboolean JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeIsDecoderFloat(JNIEnv *env, jobject thiz) {



    return g_decoderIsFloat.load();



}







JNIEXPORT jint JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetStreamSampleRate(JNIEnv *env, jobject thiz) {



    if (g_outputStream) return g_outputStream->getSampleRate();



    return g_sampleRate.load();



}







// --- V7.27: Speed diagnostic ---



JNIEXPORT jstring JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetSpeedDiag(JNIEnv *env, jobject thiz) {



    int fileSr = g_fileSampleRate.load();



    int reportedSr = g_sampleRate.load();



    int actualSr = g_outputStream ? g_outputStream->getSampleRate() : 0;



    char buf[256];



    snprintf(buf, sizeof(buf), "file=%d reported=%d actual=%d diff=%.1f%%",



             fileSr, reportedSr, actualSr,



             (fileSr > 0) ? fabsf((float)(actualSr - fileSr) / fileSr * 100.0f) : 0.0f);



    return env->NewStringUTF(buf);



}







// --- V7.18: nativeOpen step diagnostics ---



JNIEXPORT jint JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetOpenStep(JNIEnv *env, jobject thiz) {



    return g_nativeOpenStep.load();



}







JNIEXPORT jint JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetOpenErrorCode(JNIEnv *env, jobject thiz) {



    return g_nativeOpenErrorCode.load();



}







// 【V7.39】Underrun counter



JNIEXPORT jlong JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetUnderrunCount(JNIEnv *env, jobject thiz) {



    return g_underrunCount.load(std::memory_order_relaxed);



}







JNIEXPORT void JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeResetUnderrunCount(JNIEnv *env, jobject thiz) {



    g_underrunCount.store(0, std::memory_order_relaxed);



}







// --- [V8.124] Signal Path 诊断：DSP 链 enable 状态 bitmask（一次拿全，避免 16 次 JNI）---



// bit 顺序 = 真实链序：



//  0 preGain(恒在) 1 dcBlock 2 AutoEQ 3 MSEB 4 5段EQ 5 masterGain(恒在) 6 AGC 7 Loudness



//  8 M/S 9 Crossfeed 10 瞬态 11 Compressor 12 Limiter(恒在) 13 softClip(恒在) 14 dither 15 SinkEQ



// [V8.119] 原声直通时整条链旁路 → 全 0。



JNIEXPORT jintArray JNICALL



Java_com_sdw_music_player_OboeDirectPlayer_nativeGetDspChainState(JNIEnv *env, jobject thiz) {



    jint state[16];



    if (g_directPassthrough.load()) {



        for (int i = 0; i < 16; ++i) state[i] = 0;



    } else {



        state[0]  = 1;  // preGain 恒在



        state[1]  = g_dcBlockEnabled.load() ? 1 : 0;



        state[2]  = g_autoEqEnabled.load() ? 1 : 0;



        state[3]  = g_mseb10Enabled.load() ? 1 : 0;



        state[4]  = (g_dspEqEnabled.load() || g_eq5BandEnabled.load()) ? 1 : 0;



        state[5]  = 1;  // masterGain 恒在



        state[6]  = g_agcEnabled.load() ? 1 : 0;



        state[7]  = g_loudnessEnabled.load() ? 1 : 0;



        state[8]  = g_msEnabled.load() ? 1 : 0;



        state[9]  = g_crossfeedEnabled.load() ? 1 : 0;



        state[10] = g_transientEnabled.load() ? 1 : 0;



        state[11] = g_compressorEnabled.load() ? 1 : 0;



        state[12] = 1;  // Limiter 恒在



        state[13] = 1;  // softClip 恒在



        state[14] = g_ditherEnabled.load() ? 1 : 0;



        state[15] = g_sinkEqEnabled.load() ? 1 : 0;



    }



    jintArray out = env->NewIntArray(16);



    if (out) env->SetIntArrayRegion(out, 0, 16, state);



    return out;



}







} // extern "C"