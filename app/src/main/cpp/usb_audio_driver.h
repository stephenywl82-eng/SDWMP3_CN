#pragma once

#include <atomic>
#include <cstdint>
#include <string>
#include <thread>
#include <vector>
#include <mutex>
#include <linux/usbdevice_fs.h>
#include <linux/usb/ch9.h>
#include "biquad_filter.h"
#include "loudness_comp.h"

// [V8.119] Defined in usb_audio_driver.cpp (non-static so the inline setters below link).
extern std::atomic<bool> g_dacWideHeadroom;
extern std::atomic<bool> g_dacDirectPassthrough;
// [V9.01] 引擎 DSP 主开关（DAC 模块"启用引擎 DSP"），与 g_dacDirectPassthrough 正交。
extern std::atomic<bool> g_engineDspEnabled;

/**
 * Native USB Audio Class driver for direct DAC streaming.
 *
 * Uses Linux USB device file ioctls (USBDEVFS_SUBMITURB) over the fd
 * obtained from Android's UsbDeviceConnection.getFileDescriptor().
 *
 * Flow:
 *   Java UsbDacManager 锟?JNI (usb_audio_jni.cpp) 锟?UsbAudioDriver
 *
 * Ring buffer: lock-free SPSC, 32768 frames (~740ms buffer at 44.1kHz stereo float).
 * Streaming: dedicated thread with isochronous URB submission (64 packets per URB).
 * Sample rate: synchronous mode 锟?clock derived from USB SOF, no control transfer needed.
 *
 * Logging: All operations are logged to both Android logcat and an in-memory ring buffer
 * accessible via getNativeDebugLog() / JNI nativeGetDebugLog() for in-app display.
 * Log format: HH:MM:SS.mmm TAG message (Salt Player style).
 */

/**
 * An audio-streaming alternate setting candidate discovered from USB descriptor.
 */
struct DacAltCandidate {
    int ifaceNum = 0;    // bInterfaceNumber (for SETINTERFACE)
    int alt = 0;         // bAlternateSetting
    int epAddr = 0;      // endpoint address (with direction bit)
    int maxPkt = 0;      // wMaxPacketSize
    int bInterval = 1;   // bInterval (1 = HS 125us, 4 = FS 1ms)
    int channels = 2;    // number of channels
    int subslot = 2;     // bytes-per-sample: 2=S16, 3=S24_3LE, 4=S32_LE
    int res = 16;        // actual bit resolution
    int clockId = -1;    // associated clock source entity ID
    int sampleRate = 0;  // from format descriptor tSamFreq (0=multi-rate)
};

/**
 * A clock-source frequency sub-range read from GET_RANGE control transfer.
 */
struct ClockRange {
    int min = 0;         // minimum sample rate
    int max = 0;         // maximum sample rate
    int res = 0;         // 0 = fully continuous within [min,max]
    int clockId = -1;    // clock entity ID
};

/**
 * Streaming transmission statistics.
 */
struct UsbDacStats {
    uint64_t urbSubmitted   = 0;
    uint64_t urbCompleted   = 0;
    uint64_t urbErrors      = 0;
    uint64_t totalSamplesOut = 0;
    int32_t  ringReadPos    = 0;
    int32_t  ringWritePos   = 0;
    int32_t  ringAvailFrames = 0;
    int32_t  bufferWatermarkMs = 0;
    int32_t  targetWatermarkMs  = 200;
    const char* healthState = "idle";
};

class UsbAudioDriver {
public:
    static constexpr int kRingFrames    = 32768;       // ~740ms buffer at 44.1kHz (was 131072/3s, cut gap between songs)
    static constexpr int kMaxUrbCount   = 16;          // in-flight URBs
    static constexpr int kPacketsPerUrbMax = 64;       // max ISO packets per URB (buffer capacity)
    static constexpr int kPacketsPerUrb = 64;          // Salt-style: 64 ISO packets per URB (default; FS 96k drops to 16)
    static constexpr int kMaxPacketSize = 576;         // max bytes per ISO packet (384 mps + headroom)
    static constexpr int kMaxUrbBuffer  = kPacketsPerUrb * kMaxPacketSize;
    static constexpr int kFeedbackIntervalUs = 200000;

    UsbAudioDriver();
    ~UsbAudioDriver();

    // 鈹€鈹€ Lifecycle 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
    bool open(int fd, int epAddress, int maxPacketSize, int interval,
              bool isUac2, int vid, int pid, int ifaceNum);
    bool start(int sampleRate, int channels, int bitsPerSample);
    void stop();
    void stopThreadOnly();
    void resetRingBuffer();  // 銆怴3.2.7銆戞殏鍋滃悗鎭㈠鏃舵竻绌?ring buffer锛岄伩鍏嶆棫鏁版嵁韪╄笍瀵艰嚧鍣煶
    void forceReset();            // emergency: close fd to cancel stuck URBs (recovery from wedge)
    int64_t lastUrbCompletionTimeMs() const { return lastUrbCompletionTimeMs_.load(); }
    int pushPcm(const float* data, int frameCount);
    int getRingFillFrames();  // 銆怴3.2.7銆慐OS 鎺掔┖鐢?
    int clockRate_ = 0;       // 銆怴3.2.7銆慏AC 鏃堕挓褰撳墠 SET_CUR 閫熺巼
    int currentAlt_ = 0;      // 銆怴3.2.7銆戝綋鍓?alt 璁剧疆锛?=16bit 2=24bit 3=32bit锛?

    // 鈹€鈹€ USB control 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
    bool claimInterface(int desiredAlt);
    void releaseInterface();
    bool setInterfaceAlt(int alt);
    int setSampleRate(int rate);  // public: safe only when stream inactive
    int trySetSampleRate(int rate); // internal: unconditionally sends SET_CUR
    const char* getSupportedRates();

    // 鈹€鈹€ State 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
    bool isClaimed() const      { return claimed_.load(std::memory_order_acquire); }
    bool isStreaming() const    { return streaming_.load(std::memory_order_acquire); }
    int  getSampleRate() const  { return sampleRate_; }
    int  getBitsPerSample() const { return bitsPerSample_; }
    int  getUnderrunCount() const { return underrunCount_.load(std::memory_order_acquire); }
    void setVolume(float v)      {
        if (hardwareVolumeReady_) {
            // 硬件 Feature Unit 负责音量 → 软件乘因子固定 1.0,避免双重衰减,
            // 并保留 bit-depth 动态范围(不在 PCM 域做数字增益)
            volume_.store(1.0f, std::memory_order_relaxed);
            setHardwareVolume(v);
        } else {
            // [V8.131] atomic store: written from the UI/JNI thread, read by the
            // real-time streamLoop packer. A plain float made the packer's read
            // racy (no visibility guarantee) — volume jumps could take a URB or two
            // to land and step the level audibly.
            volume_.store(v, std::memory_order_relaxed);
        }
    }
    float getVolume() const      { return volume_.load(std::memory_order_relaxed); }
    void setDitherEnabled(bool en) { ditherEnabled_.store(en, std::memory_order_release); }
    // [V8.119] Headroom profile: true = WIDE (0.92 knee), false = LEGACY (2/3)
    void setWideHeadroom(bool en) { g_dacWideHeadroom.store(en, std::memory_order_release); }
    // [V8.119] 原声直通：true = DAC 链整条 DSP 旁路
    void setDirectPassthrough(bool en) { g_dacDirectPassthrough.store(en, std::memory_order_release); }
    // [V9.01] 引擎 DSP 主开关：true = 创意 DSP 生效；false = 整链旁路（bit-perfect 直通）
    void setEngineDspEnabled(bool en) { g_engineDspEnabled.store(en, std::memory_order_release); }
    bool isEngineDspEnabled() const   { return g_engineDspEnabled.load(std::memory_order_acquire); }
    // [V9.01] DAC 模块子开关：直接翻转各创意 DSP 的使能标志（手动总闸，作为曲线载入的"上层覆盖"）。
    // 无曲线时对应 Biquad 默认 identity，翻转这些标志也是透明直通，不会引入杂音。
    void setMsebEnabled(bool en)  { msebSubEnabled_.store(en, std::memory_order_release); }
    void setAutoEqEnabled(bool en){ autoEqSubEnabled_.store(en, std::memory_order_release); }
    void setDtsEnabled(bool en)   { dtsSubEnabled_.store(en, std::memory_order_release); }
    // [2026-09-10 soft-start] Mask DAC PLL re-lock transient on (cross-rate) stream start.
    // OFF by default: a per-sample gain ramp is a mathematical intervention on the samples,
    // so bit-perfect output is preserved unless the user explicitly opts in.
    static constexpr int kSoftStartFadeMs = 150;
    void setSoftStartEnabled(bool en) { softStartEnabled_.store(en, std::memory_order_release); }
    bool isSoftStartEnabled() const   { return softStartEnabled_.load(std::memory_order_acquire); }
    bool isDitherEnabled() const { return ditherEnabled_.load(std::memory_order_acquire); }

    // ── DAC 链路 MSEB / 图形 EQ（5 段 Biquad，与 Oboe 共用同一套算法）──
    void setDspEnabled(bool en) {
        dspEqEnabled_.store(en, std::memory_order_release);
        // 【V8.8】DSP 总开关关闭 → 耳机校准（AutoEQ）一并关闭，与 Oboe 路径行为一致
        if (!en) {
            autoEqEnabled_.store(false, std::memory_order_release);
            autoEqPreGain_ = 1.0f;
        }
    }
    bool isDspEnabled() const { return dspEqEnabled_.load(std::memory_order_acquire); }
    void setDspEq5Band(const float* gainsDb, const float* freqsHz, int len);
    void resetDspEq5Band();
    // 【V8.2】MSEB 10-band subjective EQ（独立实例，与 Oboe 共享 Biquad + crossfade）
    void setMseb10Band(const float* gainsDb, const float* freqsHz, const float* qs, int len);
    void resetMseb10Band();
    // 【V8.3】AutoEQ 10-band 耳机修正（任意频点 + PK/HS/LS 类型），与 MSEB 并存叠加（打底）。
    void setAutoEq10Band(const float* gainsDb, const float* freqsHz, const float* qs,
                         const int* types, int len, float preampDb);
    void resetAutoEq();
    // 【V8.3】M/S 声场（跨声道矩阵）
    void setMsStage(float soundstage, float imaging);
    void resetMsStage();
    // 【V8.3】瞬态整形（impulseResponse 维度映射，-1..+1）
    void setTransient(float amount);
    void resetTransient();
    // 【V8.3】动态压缩（master bus 向下压缩器，stereo-linked）
    void setCompressorEnabled(bool en);
    void setCompressorParams(float thresholdDb, float ratio, float attackMs, float releaseMs, float makeupDb);
    void resetCompressor();
    // 【V8.3】等响补偿（ISO 226，低音量时低频/高频自动提升）
    void setLoudnessEnabled(bool en);
    void setLoudnessIntensity(float intensity);
    void setLoudnessOutGain(float gain);
    // 【V8.170】响度统一管理：元数据（ReplayGain / LUFS）折算出的整枚增益，dB
    void setNormalizationGain(float db);
    // Kotlin 层注入 Salt-verified 的 FU 参数（通用探测失败时兜底，quirk 表已上移到 DacProfile.kt）
    void setFeatureUnitOverride(int unitId, int channel, int channels,
                                float minDb, float maxDb, float resDb);
    // [2026-09-10 multi-DAC quirk] Kotlin pushes per-device overrides post-claim.
    void setQuirkOverride(int urbPackets, int ctlMsgDelayUs, int ifaceDelayUs,
                          bool forceIfaceReset, bool forceUac1, bool skipSetCur);
    const char* getDacName() const   { return dacName_.c_str(); }
    const char* getDetailedInfo() const { return detailedInfo_.c_str(); }
    void getStats(UsbDacStats& out) const;

    // 鈹€鈹€ Debug log ring buffer (Salt-style in-app log) 鈹€鈹€鈹€鈹€鈹€
    const char* getNativeDebugLog() const { return nativeLogBuf_; }
    void clearNativeDebugLog() { nativeLogWrite_ = 0; nativeLogBuf_[0] = '\0'; }

    // 鈹€鈹€ Conversion helpers 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
    int framesToBytes(int frames) const { return frames * bytesPerFrame_; }
    int packetSizeFrames() const;
    int packetSizeBytes() const;

private:
    void streamLoop();
    void feedbackLoop();
    static void* streamThreadEntry(void* arg);
    static void* feedbackThreadEntry(void* arg);

    int  findClockSourceId();
    int  findFeedbackEndpoint();
    void parseSupportedRates();
    void parseAltCandidates();       // scan all AS alt settings 鈫?altCandidates_
    void parseClockRanges();         // read GET_RANGE clock sub-ranges 鈫?clockRanges_
    int  selectAltForRate(int targetRate, int targetBits, int targetChannels); // pick best alt from candidates
    int readConfigDescriptor(uint8_t interfaceNum, uint8_t* buf, size_t maxLen);
    int controlTransfer(uint8_t bmRequestType, uint8_t bRequest,
                        uint16_t wValue, uint16_t wIndex,
                        void* data, uint16_t wLength, unsigned timeoutMs = 100);

    struct UrbSlot {
        usbdevfs_urb* urb = nullptr;
        uint8_t buffer[kMaxUrbBuffer];
    };
    UrbSlot urbSlots_[kMaxUrbCount];
    // [2026-09-10 soft-start] Linear fade-in gain for a sample index inside the current URB.
    // Returns 1.0f immediately when the feature is off (zero cost / bit-perfect path).
    inline float softStartGain(int sampleIdxInUrb) const {
        if (!softStartEnabled_.load(std::memory_order_relaxed)) return 1.0f;
        if (softStartFrameBase_ >= softStartTotalFrames_) return 1.0f;
        const int64_t frame = softStartFrameBase_ + (sampleIdxInUrb / channels_);
        if (frame >= softStartTotalFrames_) return 1.0f;
        return (float)frame / (float)softStartTotalFrames_;
    }

    bool submitUrb(int slot, int numBytes);
    bool submitUrbRaw(int slot);
    void reapCompletedUrbs();

    // 鈹€鈹€ Native log ring (written by C++, read by Kotlin via JNI) 鈹€鈹€
    static constexpr int kNativeLogSize = 32768;
    mutable char nativeLogBuf_[kNativeLogSize] = {};
    mutable int nativeLogWrite_ = 0;
    void nativeLog(const char* tag, const char* fmt, ...)
        __attribute__((format(printf, 3, 4)));

    // 鈹€鈹€ State 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
    int fd_ = -1;
    int epAddress_ = 0;
    int maxPacketSize_ = 0;
    int interval_ = 1;
    bool isUac2_ = false;
    bool highSpeed_ = true;   // real USB bus speed (high=8000 uframes/s, full=1000 frames/s). Default high; corrected in open() via USBDEVFS_GET_SPEED.
    int vid_ = 0;
    int pid_ = 0;
    int ifaceNum_ = 0;
    int urbPackets_ = kPacketsPerUrb;   // [2026-09-10 quirk] actual pkts/URB; full-speed (Qudelix 96k) drops to 16 to stay under usbfs memory limits
    int ctlMsgDelayUs_ = 0;              // [2026-09-10 quirk] delay after SET_CUR (us). Linux CTL_MSG_DELAY: MOONDROP/iBasso DC use 1000-5000us. Default 0 = unchanged behavior.
    // [2026-09-10 multi-DAC quirk] Kotlin DacProfile overrides (JNI push after claim).
    // Non-default only; built-in open() quirk init stays as fallback for unknown DACs.
    bool forceIfaceReset_ = false;      // teardown+reactivate endpoint even same-alt
    bool forceUac1_ = false;            // parse descriptors as UAC1 (firmware lies about UAC2)
    bool skipSetCurQuirk_ = false;      // never send SET_CUR (HIFI_A001 locks at 384k)
    int ifaceDelayUs_ = 0;              // delay between setInterfaceAlt ops
    bool quirkOverrideApplied_ = false; // true after Kotlin pushed overrides
    // [2026-09-10 soft-start] armed per stream start in start(); advanced by streamLoop only.
    std::atomic<bool> softStartEnabled_{false};
    int     softStartTotalFrames_ = 0;   // 0 = no ramp (feature off / already done)
    int64_t softStartFrameBase_   = 0;   // frames output since this stream start


    int sampleRate_  = 48000;
    int channels_    = 2;
    int bitsPerSample_ = 24;
    int bytesPerFrame_ = 6;

    std::atomic<bool> streaming_{false};
    std::atomic<bool> claimed_{false};
    std::atomic<int>  underrunCount_{0};
    mutable std::atomic<uint64_t> urbSubmitted_{0};
    mutable std::atomic<uint64_t> urbCompleted_{0};
    mutable std::atomic<uint64_t> urbErrors_{0};
    mutable std::atomic<uint64_t> totalSamplesOut_{0};
    mutable std::atomic<int32_t>  bufferWatermarkMs_{0};

    float* ringBuffer_ = nullptr;
    std::atomic<float> volume_{1.0f};   // [V8.131] was plain float (UI-write / RT-read race)
    std::atomic<bool> ditherEnabled_{false};  // Reverted to OFF (original default). With the
                                              // V8.131 rounding fix the 16-bit packer no longer
                                              // truncates, so the signal-correlated grit is gone;
                                              // keeping dither off avoids ADDING TPDF noise to a
                                              // 16-bit source played to a 16-bit DAC. Available as a
                                              // manual toggle (Settings) for 24-bit-source -> 16-bit
                                              // DAC downconversion, where dither does help.
    uint32_t ditherState_ = 0x12345678u;       // LCG state for TPDF dither

    // [V8.124] noise-shaped dither 一阶误差反馈状态（每声道独立，Aurora 移植）
    float nsErrorL_ = 0.0f;
    float nsErrorR_ = 0.0f;

    // ── DAC 链路 5 段 EQ（在 pushPcm 内做处理，独立于 Oboe 的实例，但算法/系数/Q 完全一致）──
    std::atomic<bool> dspEqEnabled_{false};
    std::mutex dspEqMutex_;          // 保护系数设置与 process 的并发（process 用 try_lock 不阻塞）
    // 【V8.170】响度归一化线性增益（1.0 = 旁路）。乘进 preGain，天然带平滑，不会咔哒。
    std::atomic<float> normGainLin_{1.0f};
    float dspEqPreGain_ = 1.0f;      // headroom 衰减系数（与 Oboe g_dspEqPreGain 对齐）
    float curDspPreGain_ = 1.0f;     // smoothed pre-gain (fades toward dspEqPreGain_ to kill zipper noise)
    BiquadFilter dspEqBand1L_, dspEqBand1R_;
    BiquadFilter dspEqBand2L_, dspEqBand2R_;
    BiquadFilter dspEqBand3L_, dspEqBand3R_;
    BiquadFilter dspEqBand4L_, dspEqBand4R_;
    BiquadFilter dspEqBand5L_, dspEqBand5R_;
    // 【V8.2】MSEB 10-band instances（独立于 5 段图形 EQ）
    BiquadFilter msebBand1L_, msebBand1R_;
    BiquadFilter msebBand2L_, msebBand2R_;
    BiquadFilter msebBand3L_, msebBand3R_;
    BiquadFilter msebBand4L_, msebBand4R_;
    BiquadFilter msebBand5L_, msebBand5R_;
    BiquadFilter msebBand6L_, msebBand6R_;
    BiquadFilter msebBand7L_, msebBand7R_;
    BiquadFilter msebBand8L_, msebBand8R_;
    BiquadFilter msebBand9L_, msebBand9R_;
    BiquadFilter msebBand10L_, msebBand10R_;
    std::atomic<bool> mseb10Enabled_{false};
    float msebPreGain_ = 1.0f;
    // 【V8.3】上次提交的 10 段增益，用于跳过未变化 band 的 crossfade。
    float lastMsebGains_[10] = { 999.0f, 999.0f, 999.0f, 999.0f, 999.0f,
                                 999.0f, 999.0f, 999.0f, 999.0f, 999.0f };
    // 【V8.3】AutoEQ 10-band instances（任意频点，独立于 MSEB / 5段图形 EQ）。
    BiquadFilter autoEqBand1L_, autoEqBand1R_;
    BiquadFilter autoEqBand2L_, autoEqBand2R_;
    BiquadFilter autoEqBand3L_, autoEqBand3R_;
    BiquadFilter autoEqBand4L_, autoEqBand4R_;
    BiquadFilter autoEqBand5L_, autoEqBand5R_;
    BiquadFilter autoEqBand6L_, autoEqBand6R_;
    BiquadFilter autoEqBand7L_, autoEqBand7R_;
    BiquadFilter autoEqBand8L_, autoEqBand8R_;
    BiquadFilter autoEqBand9L_, autoEqBand9R_;
    BiquadFilter autoEqBand10L_, autoEqBand10R_;
    // 【二期】手动参数 EQ 扩到 30 段：11-30 段专用实例（1-5 复用 dspEqBand，6-10 复用 autoEqBand6-10）。
    BiquadFilter autoEqBand11L_, autoEqBand11R_;
    BiquadFilter autoEqBand12L_, autoEqBand12R_;
    BiquadFilter autoEqBand13L_, autoEqBand13R_;
    BiquadFilter autoEqBand14L_, autoEqBand14R_;
    BiquadFilter autoEqBand15L_, autoEqBand15R_;
    BiquadFilter autoEqBand16L_, autoEqBand16R_;
    BiquadFilter autoEqBand17L_, autoEqBand17R_;
    BiquadFilter autoEqBand18L_, autoEqBand18R_;
    BiquadFilter autoEqBand19L_, autoEqBand19R_;
    BiquadFilter autoEqBand20L_, autoEqBand20R_;
    BiquadFilter autoEqBand21L_, autoEqBand21R_;
    BiquadFilter autoEqBand22L_, autoEqBand22R_;
    BiquadFilter autoEqBand23L_, autoEqBand23R_;
    BiquadFilter autoEqBand24L_, autoEqBand24R_;
    BiquadFilter autoEqBand25L_, autoEqBand25R_;
    BiquadFilter autoEqBand26L_, autoEqBand26R_;
    BiquadFilter autoEqBand27L_, autoEqBand27R_;
    BiquadFilter autoEqBand28L_, autoEqBand28R_;
    BiquadFilter autoEqBand29L_, autoEqBand29R_;
    BiquadFilter autoEqBand30L_, autoEqBand30R_;
    BiquadFilter autoEqBand31L_, autoEqBand31R_;
    BiquadFilter autoEqBand32L_, autoEqBand32R_;
    BiquadFilter autoEqBand33L_, autoEqBand33R_;
    BiquadFilter autoEqBand34L_, autoEqBand34R_;
    BiquadFilter autoEqBand35L_, autoEqBand35R_;
    BiquadFilter autoEqBand36L_, autoEqBand36R_;
    BiquadFilter autoEqBand37L_, autoEqBand37R_;
    BiquadFilter autoEqBand38L_, autoEqBand38R_;
    BiquadFilter autoEqBand39L_, autoEqBand39R_;
    BiquadFilter autoEqBand40L_, autoEqBand40R_;
    BiquadFilter autoEqBand41L_, autoEqBand41R_;
    BiquadFilter autoEqBand42L_, autoEqBand42R_;
    BiquadFilter autoEqBand43L_, autoEqBand43R_;
    BiquadFilter autoEqBand44L_, autoEqBand44R_;
    BiquadFilter autoEqBand45L_, autoEqBand45R_;
    BiquadFilter autoEqBand46L_, autoEqBand46R_;
    BiquadFilter autoEqBand47L_, autoEqBand47R_;
    BiquadFilter autoEqBand48L_, autoEqBand48R_;
    BiquadFilter autoEqBand49L_, autoEqBand49R_;
    BiquadFilter autoEqBand50L_, autoEqBand50R_;
    BiquadFilter autoEqBand51L_, autoEqBand51R_;
    BiquadFilter autoEqBand52L_, autoEqBand52R_;
    BiquadFilter autoEqBand53L_, autoEqBand53R_;
    BiquadFilter autoEqBand54L_, autoEqBand54R_;
    BiquadFilter autoEqBand55L_, autoEqBand55R_;
    BiquadFilter autoEqBand56L_, autoEqBand56R_;
    BiquadFilter autoEqBand57L_, autoEqBand57R_;
    BiquadFilter autoEqBand58L_, autoEqBand58R_;
    BiquadFilter autoEqBand59L_, autoEqBand59R_;
    BiquadFilter autoEqBand60L_, autoEqBand60R_;
    BiquadFilter autoEqBand61L_, autoEqBand61R_;
    BiquadFilter autoEqBand62L_, autoEqBand62R_;
    BiquadFilter autoEqBand63L_, autoEqBand63R_;
    BiquadFilter autoEqBand64L_, autoEqBand64R_;
    std::atomic<bool> autoEqEnabled_{false};
    float autoEqPreGain_ = 1.0f;
    float lastAutoEqGains_[64] = { 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f, 999.0f };
    // 【V8.3】M/S 声场（跨声道矩阵）
    std::atomic<bool> msEnabled_{false};
    // 【V9.01】DAC 模块子开关独立标志（与上面内容标志正交，曲线载入路径不碰）：
    // 子开关 OFF 时即使载入曲线也强制静默（与 Oboe g_*SubEnabled 语义一致）。
    std::atomic<bool> msebSubEnabled_{true};
    std::atomic<bool> autoEqSubEnabled_{true};
    std::atomic<bool> dtsSubEnabled_{true};
    std::atomic<float> msWidth_{1.0f};
    std::atomic<float> msCenter_{1.0f};
    float curMsWidth_ = 1.0f;
    float curMsCenter_ = 1.0f;
    // 【V8.3】瞬态整形（时域，非 EQ）——双时间常数包络跟随器。
    std::atomic<float> transientAmount_{0.0f};
    std::atomic<bool> transientEnabled_{false};
    float curTransientAmount_ = 0.0f;
    float tsFastEnvL_ = 0.0f, tsSlowEnvL_ = 0.0f;
    float tsFastEnvR_ = 0.0f, tsSlowEnvR_ = 0.0f;
    // 【V8.3】动态压缩（master bus 向下压缩器，stereo-linked）
    std::atomic<bool> compressorEnabled_{false};
    float compThresholdDb_ = -18.0f;
    float compRatio_ = 2.0f;
    float compAttackMs_ = 10.0f;
    float compReleaseMs_ = 120.0f;
    float compMakeupDb_ = 0.0f;
    float compAttackCoeff_ = 0.0f;
    float compReleaseCoeff_ = 0.0f;
    float compMakeupLinear_ = 1.0f;
    float compEnvDb_ = -120.0f;
    float compGrDb_ = 0.0f;
    // 【V8.3】等响补偿（ISO 226，低音量时低频/高频自动提升，stereo-linked）
    LoudnessComp loudness_;
    std::atomic<int> writePos_{0};
    std::atomic<int> readPos_{0};

    std::atomic<int64_t> lastUrbCompletionTimeMs_{0};  // wall-clock ms of last REAPURB success
    std::atomic<int32_t> wedgeCount_{0};                // forceReset trigger count
    std::thread streamThread_;
    std::thread feedbackThread_;

    std::string dacName_;
    std::string detailedInfo_;
    std::string supportedRates_;

    // 鈹€鈹€ Descriptor-driven DAC adaptation 鈹€鈹€
    std::vector<DacAltCandidate> altCandidates_;
    std::vector<ClockRange> clockRanges_;
    int  acIface_ = 0;     // AudioControl interface number (for clock control transfers)
    int  clockSourceId_ = -1;  // resolved UAC2 clock source entity id (from findClockSourceId)
    // Descriptor cache: read once (with retry) in open(), reused by all parse* callers.
    std::vector<uint8_t> configDesc_;
    int  configDescLen_ = 0;
    // Feature Unit hardware volume
    int  featureUnitId_ = 0;
    int  featureUnitChannels_ = 2;
    int  featureUnitControlSize_ = 2;
    int  featureUnitChannel_ = 0;
    float featureUnitMinDb_ = -127.0f;
    float featureUnitMaxDb_ = 0.0f;
    float featureUnitResDb_ = 1.0f;
    bool hardwareVolumeReady_ = false;
    float lastVolumeDb_ = -999.0f;
    bool lastVolumeSet_ = false;
    void parseFeatureUnit();
    bool setHardwareVolume(float pct);


};
