#include <oboe/Oboe.h>
#include <android/log.h>
#include <jni.h>
#include <cmath>
#include <cstring>
#include <algorithm>  // std::min/max for clamp
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <vector>
#include <thread>
#include <random>
#include <chrono>   // 【V7.27】速率诊断时间测量
#include <time.h>   // clock_gettime / struct timespec
// NDK Media headers for direct decoding
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>
#include <unistd.h>
#include <sys/stat.h>
#define LOG_TAG "OboeBridge"
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
    int write(const float *data, int length) {
        int r = read_pos_.load(std::memory_order_acquire);  // 消费者位置
        int w = write_pos_.load(std::memory_order_relaxed);
        int freeSlots = capacity_ - (w - r);  // 利用 unsigned wrap-around 特性
        // 等价于: capacity_ - ((w - r + capacity_) % capacity_)
        // 但因为 capacity_ 是 2 的幂且用 mask_，更简单：
        int used = (w - r + capacity_) & mask_;
        // 实际上 used 可能等于 capacity_，这时满
        // 修正：用模运算
        used = (w - r + capacity_) % capacity_;
        freeSlots = capacity_ - used - 1;  // 留 1 个槽位区分满/空
        int toWrite = (length < freeSlots) ? length : freeSlots;
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

private:
    int capacity_;
    int mask_;  // capacity_ - 1, 用于快速取模
    std::vector<float> buffer_;
    std::atomic<int> write_pos_;
    std::atomic<int> read_pos_;
};
// ============================================================================
// Biquad Filter — RBJ Cookbook implementation (double-precision coefficients + states)
// Supports: High-Shelf, Peaking, Low-Shelf, High-Pass, Low-Pass
// 【V7.200】State registers (x1/x2/y1/y2) and coefficient storage use double to eliminate
// 24-bit mantissa quantization noise accumulation across cascaded IIR stages.
// process() multiply-add core stays float (ARM NEON 4×f32 SIMD), with float↔double
// conversion at state read/write boundaries — zero throughput penalty on ARMv8-A.
// Denormal protection is hardware FTZ (FPCR FZ+DN), enabled once in nativeOpen.
// ==============================================================================
class BiquadFilter {
public:
    enum FilterType {
        FLAT = 0,
        HIGH_SHELF = 1,
        PEAKING = 2,
        LOW_SHELF = 3,
        HIGH_PASS = 4,
        LOW_PASS = 5
    };
    void setHighShelf(float sampleRate, float cutoffHz, float dbGain, float Q) {
        double A = pow(10.0, (double)dbGain / 40.0);
        double w0 = 2.0 * M_PI * cutoffHz / sampleRate;
        double cosw0 = cos(w0);
        double sinw0 = sin(w0);
        double alpha = sinw0 / (2.0 * Q);
        double twoSqrtAalpha = 2.0 * sqrt(A) * alpha;
        double b0 = A * ((A + 1.0) + (A - 1.0) * cosw0 + twoSqrtAalpha);
        double b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosw0);
        double b2 = A * ((A + 1.0) + (A - 1.0) * cosw0 - twoSqrtAalpha);
        double a0 = (A + 1.0) - (A - 1.0) * cosw0 + twoSqrtAalpha;
        double a1 =  2.0 * ((A - 1.0) - (A + 1.0) * cosw0);
        double a2 = (A + 1.0) - (A - 1.0) * cosw0 - twoSqrtAalpha;
        setCoefficients(b0, b1, b2, a0, a1, a2);
    }
    void setPeaking(float sampleRate, float centerHz, float dbGain, float Q) {
        double A = pow(10.0, (double)dbGain / 40.0);
        double w0 = 2.0 * M_PI * centerHz / sampleRate;
        double cosw0 = cos(w0);
        double sinw0 = sin(w0);
        double alpha = sinw0 / (2.0 * Q);
        double b0 = 1.0 + alpha * A;
        double b1 = -2.0 * cosw0;
        double b2 = 1.0 - alpha * A;
        double a0 = 1.0 + alpha / A;
        double a1 = -2.0 * cosw0;
        double a2 = 1.0 - alpha / A;
        setCoefficients(b0, b1, b2, a0, a1, a2);
    }
    void setLowShelf(float sampleRate, float cutoffHz, float dbGain, float Q) {
        double A = pow(10.0, (double)dbGain / 40.0);
        double w0 = 2.0 * M_PI * cutoffHz / sampleRate;
        double cosw0 = cos(w0);
        double sinw0 = sin(w0);
        double alpha = sinw0 / (2.0 * Q);
        double twoSqrtAalpha = 2.0 * sqrt(A) * alpha;
        double b0 = A * ((A + 1.0) - (A - 1.0) * cosw0 + twoSqrtAalpha);
        double b1 =  2.0 * A * ((A - 1.0) - (A + 1.0) * cosw0);
        double b2 = A * ((A + 1.0) - (A - 1.0) * cosw0 - twoSqrtAalpha);
        double a0 = (A + 1.0) + (A - 1.0) * cosw0 + twoSqrtAalpha;
        double a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosw0);
        double a2 = (A + 1.0) + (A - 1.0) * cosw0 - twoSqrtAalpha;
        setCoefficients(b0, b1, b2, a0, a1, a2);
    }
    void setBandPass(float sampleRate, float centerHz, float Q) {
        double w0 = 2.0 * M_PI * centerHz / sampleRate;
        double cosw0 = cos(w0);
        double sinw0 = sin(w0);
        double alpha = sinw0 / (2.0 * Q);
        double b0 = alpha;
        double b1 = 0.0;
        double b2 = -alpha;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * cosw0;
        double a2 = 1.0 - alpha;
        setCoefficients(b0, b1, b2, a0, a1, a2);
    }
    void setLowPass(float sampleRate, float cutoffHz, float Q) {
        double w0 = 2.0 * M_PI * cutoffHz / sampleRate;
        double cosw0 = cos(w0);
        double sinw0 = sin(w0);
        double alpha = sinw0 / (2.0 * Q);
        double b0 = (1.0 - cosw0) * 0.5;
        double b1 = 1.0 - cosw0;
        double b2 = (1.0 - cosw0) * 0.5;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * cosw0;
        double a2 = 1.0 - alpha;
        setCoefficients(b0, b1, b2, a0, a1, a2);
    }
    void setFlat() {
        norm_b0_ = 1.0; norm_b1_ = 0.0; norm_b2_ = 0.0;
        norm_a1_ = 0.0; norm_a2_ = 0.0;
        cur_b0_ = 1.0; cur_b1_ = 0.0; cur_b2_ = 0.0;
        cur_a1_ = 0.0; cur_a2_ = 0.0;
        reset();
    }
    // Process single sample — float multiply-add core (NEON 4×f32 SIMD),
    // double state registers for quantization-noise-free IIR feedback
    float process(float input) {
        const float SMOOTH = 0.05f;
        cur_b0_ += (norm_b0_ - cur_b0_) * (double)SMOOTH;
        cur_b1_ += (norm_b1_ - cur_b1_) * (double)SMOOTH;
        cur_b2_ += (norm_b2_ - cur_b2_) * (double)SMOOTH;
        cur_a1_ += (norm_a1_ - cur_a1_) * (double)SMOOTH;
        cur_a2_ += (norm_a2_ - cur_a2_) * (double)SMOOTH;

        // Cast coefficients to float for the 5× FMADD pipeline (NEON fmla v0.4s)
        float b0f = (float)cur_b0_, b1f = (float)cur_b1_, b2f = (float)cur_b2_;
        float a1f = (float)cur_a1_, a2f = (float)cur_a2_;

        // State read: double → float
        float x1f = (float)x1_, x2f = (float)x2_;
        float y1f = (float)y1_, y2f = (float)y2_;

        float output = b0f * input + b1f * x1f + b2f * x2f
                     - a1f * y1f - a2f * y2f;

        // Clamp to prevent NaN/Inf
        output = fmaxf(-1.0f, fminf(1.0f, output));

        // State write-back: float → double (preserves full IIR precision)
        x2_ = (double)x1f;
        x1_ = (double)input;
        y2_ = (double)y1f;
        y1_ = (double)output;

        return output;
    }
    void reset() {
        x1_ = x2_ = y1_ = y2_ = 0.0;
    }
    // Coefficients — double storage for 53-bit mantissa precision
    double norm_b0_ = 1.0, norm_b1_ = 0.0, norm_b2_ = 0.0;
    double norm_a1_ = 0.0, norm_a2_ = 0.0;
    double cur_b0_ = 1.0, cur_b1_ = 0.0, cur_b2_ = 0.0;
    double cur_a1_ = 0.0, cur_a2_ = 0.0;
    // State — double to eliminate 24-bit mantissa quantization in IIR feedback
    double x1_ = 0.0, x2_ = 0.0;
    double y1_ = 0.0, y2_ = 0.0;
private:
    void setCoefficients(double b0, double b1, double b2, double a0, double a1, double a2) {
        norm_b0_ = b0 / a0;
        norm_b1_ = b1 / a0;
        norm_b2_ = b2 / a0;
        norm_a1_ = a1 / a0;
        norm_a2_ = a2 / a0;
    }
};
// ============================================================================
// DSP EQ state — 3-band cascade for Steven's Special + Cat Mode
// Band 1: High-Shelf 8kHz / -6dB / Q=0.707 (smooth rolloff)
// Band 2: Peaking 12kHz / -4dB / Q=2.0 (de-essing, narrow notch)
// Band 3: Peaking 250Hz / +3dB / Q=0.5  (Cat Mode bass boost)
//         High-Shelf 15kHz / -2dB / Q=1.0 (Cat Mode ultra-high protection)
// ============================================================================
static constexpr int kRingBufferCapacity = 1048576;  // 【V7.40】1M样本≈12秒@44.1kHz立体声，彻底消除underrun

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
static std::mutex g_sinkEqMutex;

// 【V7.xx】应用 Sink EQ 到单个立体声帧
static void applySinkEqStereoFrame(float& left, float& right) {
    if (!g_sinkEqEnabled.load(std::memory_order_acquire)) return;
    left  = g_sinkEqB5L.process(g_sinkEqB4L.process(g_sinkEqB3L.process(g_sinkEqB2L.process(g_sinkEqB1L.process(left)))));
    right = g_sinkEqB5R.process(g_sinkEqB4R.process(g_sinkEqB3R.process(g_sinkEqB2R.process(g_sinkEqB1R.process(right)))));
}

class SinkAudioCallback : public oboe::AudioStreamCallback {
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override {
        // DIAG: unconditional rate-limited log
        static int sinkDiagCounter = 0;
        if ((sinkDiagCounter += numFrames) >= 44100) {
            LOGI("[SINK_CB] alive: eq=%d, rb=%p, frames=%d",
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
            LOGI("Sink callback alive: eq=%d, frames=%lld",
                 g_sinkEqEnabled.load(std::memory_order_acquire),
                 (long long)g_sinkFramesWritten.load());
            diagFrames = 0;
        }
        // 【V7.xx】应用 Sink EQ 5 段均衡
        if (g_sinkEqEnabled.load(std::memory_order_acquire)) {
            std::lock_guard<std::mutex> lock(g_sinkEqMutex);
            for (int i = 0; i < numFrames; i++) {
                applySinkEqStereoFrame(out[i * 2], out[i * 2 + 1]);
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

JNIEXPORT jlong JNICALL Java_com_sdw_music_player_OboeAudioSink_nativeInit(JNIEnv *env, jobject thiz) {
    LOGI("OboeAudioSink: nativeInit()");
    if (!g_sinkRingBuffer) g_sinkRingBuffer = new PCMRingBuffer(kRingBufferCapacity);
    g_sinkRingBuffer->clear();
    g_sinkInitialized.store(true);
    g_sinkFramesWritten.store(0);
    g_sinkStartTimeNs = 0;
    g_sinkStartFrames = 0;
    return 1L;  // non-zero handle = initialized
}

JNIEXPORT jboolean JNICALL Java_com_sdw_music_player_OboeAudioSink_nativeOpenStream(JNIEnv *env, jobject thiz, jint sampleRate, jint channels) {
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
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
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

// ============================================================
// OboeDirectPlayer — NDK MediaCodec 解码 + Oboe 输出
// ============================================================
static std::mutex g_streamMutex;
static PCMRingBuffer *g_ringBuffer = nullptr;
static oboe::ManagedStream g_outputStream;
static std::atomic<bool> g_isPlaying{false};
static std::atomic<int> g_sampleRate{44100};
static std::atomic<int> g_channelCount{2};
static std::atomic<int64_t> g_framesWritten{0};
static std::atomic<bool> g_flushRequested{false};
// Peak detection debug
static std::atomic<int64_t> g_clipSampleCount{0};  // 触发软削波的采样点数
static std::atomic<int64_t> g_totalSampleCount{0}; // 总采样点数
static std::atomic<int> g_sampleRateNative{48000}; // Android 系统原生采样率
static std::atomic<bool> g_preferSharedMode{false}; // Dolby开启时强制Shared
static std::atomic<int> g_outputDeviceId{0};       // 【V3.2.7】目标输出设备 Port ID（0=系统默认，Kotlin侧动态传入USB DAC id）

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
static std::atomic<bool> g_dspEqEnabled{false};
static std::atomic<bool> g_debugSilenceTest{false};  // 【V7.08】强制静音测试标志
static std::atomic<int64_t> g_playbackPositionUs{0};  // 【V7.67】实际播放位置（微秒），用于nativeGetPositionMs
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
static std::atomic<bool> g_vuFiltersInited{false};
static std::atomic<int64_t> g_dspDisabledSampleCount{0};  // 【V7.08】DSP 关闭时的采样计数
static std::atomic<float> g_dspEqPreGain{1.0f};   // 0dB (Steven: EQ是boost不需要pre-atten)
static float g_curDspPreGain = 1.0f;  // [zipper-noise fix] smoothed pre-gain (mirror DAC path), callback-thread only
static std::atomic<float> g_masterGain{0.89f};     // -1dB intersample peak protection
static std::atomic<int> g_dspMode{0};              // 0=Steven Special, 1=Cat Mode
static std::atomic<bool> g_eq5BandEnabled{false};  // 【V7.80】5段图形均衡器预设模式
static std::atomic<bool> g_msebActive{false};         // 【V7.200】MSEB 激活时保护5段EQ不被任何reset/dspMode清零
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

    float threshold = 0.98f;  // 【V7.29】0.95→0.98 减少过度压缩
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
        float desiredGR = (peak > threshold) ? threshold / peak : 1.0f;
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
// Headroom AGC — Automatic Gain Control
// ============================================================================
class HeadroomAGC {
public:
    static constexpr int RMS_HISTORY_SIZE = 480;
    float rmsHistory[RMS_HISTORY_SIZE] = {0};
    int rmsWritePos = 0;
    float currentRms = 0.0f;
    float targetRms = 0.05f;
    float agcGain = 1.0f;
    float releaseCoeff = 0.9995f;
    float attackCoeff = 0.995f;
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
        desiredGain = std::min(2.0f, std::max(0.5f, desiredGain));
        if (desiredGain < agcGain) {
            agcGain = agcGain * attackCoeff + desiredGain * (1.0f - attackCoeff);
        } else {
            agcGain = agcGain * releaseCoeff + desiredGain * (1.0f - releaseCoeff);
        }
        return agcGain;
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
static std::atomic<float> g_agcTargetDb{-26.0f};

// ============================================================================
// Soft Clip — cubic sigmoid waveshaper
// Night mode: lower threshold for softer clipping
// ============================================================================
static inline float softClip(float x) {
    // 【V7.05】夜间模式：阈值从 2/3 降到 0.5
    float T = g_nightMode.load() ? 0.5f : (2.0f / 3.0f);
    float RANGE = 1.0f - T;
    float MAKEUP = 1.2f;
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
// TPDF Dither — Triangular Probability Density Function dither
// Adds 2 LSB of triangular noise before quantization
// ============================================================================
static std::mt19937 g_ditherRng{42};
static std::uniform_real_distribution<float> g_ditherDist(-1.0f, 1.0f);

static inline float tpdfDither() {
    return (g_ditherDist(g_ditherRng) + g_ditherDist(g_ditherRng)) * (1.0f / 65536.0f);
}

// ============================================================================
// OboeAudioCallback — Real-time audio output callback
// Reads from ring buffer → DSP processing → output
// ============================================================================
class OboeAudioCallback : public oboe::AudioStreamCallback {
public:
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override {
        float *output = static_cast<float *>(audioData);
        int channels = stream->getChannelCount();
        int totalSamples = numFrames * channels;
        g_callbackCount.fetch_add(1, std::memory_order_relaxed);  // 【V7.10】

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

        // Read from ring buffer
        if (!g_ringBuffer) {
            memset(output, 0, totalSamples * sizeof(float));
            return oboe::DataCallbackResult::Continue;
        }
        int available = g_ringBuffer->available();
        g_ringBufferFill.store(available, std::memory_order_relaxed);  // 【V7.16】
        int toRead = std::min(available, totalSamples);

        if (toRead > 0) {
            g_ringBuffer->read(output, toRead);
        }
        if (toRead < totalSamples) {
            g_underrunCount.fetch_add(1, std::memory_order_relaxed);  // 【V7.39】
            memset(output + toRead, 0, (totalSamples - toRead) * sizeof(float));
        }

        // 【V7.xx】Compute RMS amplitude for beat-reactive visuals (pre-DSP raw audio)
        if (toRead > 0) {
            float sumSq = 0.0f;
            int readSamples = std::min(toRead, 4096);  // cap at ~50ms to avoid spikes from huge buffers
            for (int i = 0; i < readSamples; i++) sumSq += output[i] * output[i];
            g_rmsLevel.store(std::sqrt(sumSq / readSamples), std::memory_order_relaxed);


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
                g_vuFiltersInited.store(true);
            }
            float subE = 0, b1E = 0, b2E = 0, b3E = 0, b4E = 0, b5E = 0, b6E = 0, b7E = 0;
            int ch = stream->getChannelCount();
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
            }
            int frames = readSamples / ch;
            if (frames > 0) {
                float r0 = std::sqrt(subE / frames);
                float r1 = std::sqrt(b1E / frames); float r2 = std::sqrt(b2E / frames);
                float r3 = std::sqrt(b3E / frames); float r4 = std::sqrt(b4E / frames);
                float r5 = std::sqrt(b5E / frames); float r6 = std::sqrt(b6E / frames);
                float r7 = std::sqrt(b7E / frames);
                static float ema[8] = {};
                const float a = 0.2f, gain = 6.0f;
                ema[0] = ema[0]*(1-a) + r0*a;  ema[1] = ema[1]*(1-a) + r1*a;
                ema[2] = ema[2]*(1-a) + r2*a;  ema[3] = ema[3]*(1-a) + r3*a;
                ema[4] = ema[4]*(1-a) + r4*a;  ema[5] = ema[5]*(1-a) + r5*a;
                ema[6] = ema[6]*(1-a) + r6*a;  ema[7] = ema[7]*(1-a) + r7*a;
                auto cl = [](float x) { return x > 1.0f ? 1.0f : x; };
                g_band0.store(cl(ema[0]*gain)); g_band1.store(cl(ema[1]*gain));
                g_band2.store(cl(ema[2]*gain)); g_band3.store(cl(ema[3]*gain));
                g_band4.store(cl(ema[4]*gain)); g_band5.store(cl(ema[5]*gain));
                g_band6.store(cl(ema[6]*gain)); g_band7.store(cl(ema[7]*gain));
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
        if (g_autoEqEnabled.load() || g_dspEqEnabled.load()) {
            // [zipper-noise fix] fade pre-gain toward target per-frame (same SMOOTH as DAC path).
            // MSEB slider changes (g_dspEqPreGain jump) no longer step output level instantly.
            float targetPreGain = g_autoEqEnabled.load() ? g_autoEqPreGain.load() : g_dspEqPreGain.load();
            float preGain = g_curDspPreGain;
            const float PREGAIN_SMOOTH = 0.05f;
            float masterGain = g_masterGain.load();
            bool agcOn = g_agcEnabled.load();
            int mode = g_dspMode.load(std::memory_order_relaxed);
            bool ditherOn = g_ditherEnabled.load();    // 【V7.05】
            bool dcBlockOn = g_dcBlockEnabled.load();   // 【V7.05】
            // 【V7.39】try_lock 替代 lock_guard — 回调永远不阻塞
            // 如果 DSP 参数正在被更新，本帧跳过 EQ 但仍然输出音频
            std::unique_lock<std::mutex> eqLock(g_eqMutex, std::try_to_lock);
            if (!eqLock.owns_lock()) {
                // DSP 参数正在更新，本帧不处理 EQ，直接输出原始数据
                g_totalSampleCount.fetch_add(totalSamples, std::memory_order_relaxed);
                g_framesWritten.fetch_add(numFrames);
                return oboe::DataCallbackResult::Continue;
            }

            float frameEnergyAccum = 0.0f;
            int samplesInFrame = 0;
            for (int i = 0; i < totalSamples; i += channels) {
                bool stereo = (channels == 2);

                preGain += (targetPreGain - preGain) * PREGAIN_SMOOTH;
                float sL = output[i] * preGain;

                // 【V7.05】DC Blocker
                if (dcBlockOn) sL = dcBlock(sL, g_dcX1L, g_dcY1L);

                // EQ bands — AutoEQ 10段 > 5段图形均衡器 > DSP模式
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
                } else if (g_eq5BandEnabled.load()) {
                    sL = g_eqBand1L.process(sL);
                    sL = g_eqBand2L.process(sL);
                    sL = g_eqBand3L.process(sL);
                    sL = g_eqBand4L.process(sL);
                    sL = g_eqBand5L.process(sL);
                } else {
                    sL = g_eqBand1L.process(sL);
                    sL = g_eqBand2L.process(sL);
                    if (mode == 1) sL = g_eqBand3L.process(sL);  // Cat mode: bass boost
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
                    sR = g_eqBand1R.process(sR);
                    sR = g_eqBand2R.process(sR);
                    // AutoEQ 10段 > 5段图形均衡器 > DSP模式
                    if (g_autoEqEnabled.load()) {
                        sR = g_eqBand3R.process(sR);
                        sR = g_eqBand4R.process(sR);
                        sR = g_eqBand5R.process(sR);
                        sR = g_autoEqBand6R.process(sR);
                        sR = g_autoEqBand7R.process(sR);
                        sR = g_autoEqBand8R.process(sR);
                        sR = g_autoEqBand9R.process(sR);
                        sR = g_autoEqBand10R.process(sR);
                    } else if (g_eq5BandEnabled.load()) {
                        sR = g_eqBand3R.process(sR);
                        sR = g_eqBand4R.process(sR);
                        sR = g_eqBand5R.process(sR);
                    } else {
                        if (mode == 1) sR = g_eqBand3R.process(sR);
                    }
                    sR *= masterGain;
                }

                // AGC
                float agcGainL = 1.0f, agcGainR = 1.0f;
                if (agcOn) {
                    float agcGain = g_agc.updateAndGetGainFromEnergy(frameEnergyAccum, samplesInFrame);
                    sL *= agcGain;
                    if (stereo) sR *= agcGain;
                }

                // Look-Ahead Limiter
                g_limiter.write(sL, stereo ? sR : sL);
                float dL = g_limiter.readDelayed(false);
                float dR = g_limiter.readDelayed(true);
                float peakToLimit = std::fmaxf(std::fabsf(dL), std::fabsf(dR));
                float gr = g_limiter.process(peakToLimit, true);
                float outL = dL * gr;
                float outR = stereo ? dR * gr : 0.0f;

                // Clip detection
                const float CLIP_THRESHOLD = 0.90f;
                if (std::fabsf(outL) > CLIP_THRESHOLD) g_clipSampleCount.fetch_add(1, std::memory_order_relaxed);
                if (stereo && std::fabsf(outR) > CLIP_THRESHOLD) g_clipSampleCount.fetch_add(1, std::memory_order_relaxed);

                // Soft clip + TPDF Dither
                outL = softClip(outL);
                if (stereo) outR = softClip(outR);
                if (ditherOn) {
                    outL += tpdfDither();
                    if (stereo) outR += tpdfDither();
                }

                output[i] = std::min(1.0f, std::max(-1.0f, outL));
                if (stereo) output[i + 1] = std::min(1.0f, std::max(-1.0f, outR));
            }
            g_curDspPreGain = preGain;
            g_totalSampleCount.fetch_add(totalSamples, std::memory_order_relaxed);
        } else {
            // DSP disabled — just count samples for diagnostics
            g_dspDisabledSampleCount.fetch_add(totalSamples, std::memory_order_relaxed);
        }

        // 【V7.xx】Sink EQ 5-band graphic EQ (applied to main Oboe output)
        bool sinkEqOn = g_sinkEqEnabled.load(std::memory_order_acquire);
        if (sinkEqOn) {
            std::lock_guard<std::mutex> lock(g_sinkEqMutex);
            for (int i = 0; i < numFrames; i++) {
                applySinkEqStereoFrame(output[i * 2], output[i * 2 + 1]);
            }
        }

        g_framesWritten.fetch_add(numFrames);
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
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
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
        } else {
            LOGE("Oboe stream restart failed: %s", oboe::convertToText(result));
        }
    }
};
static OboeAudioCallback g_audioCallback;

// ============================================================================
// NDK Decoder — globals and decode loop
// ============================================================================
static AMediaExtractor* g_decoderExtractor = nullptr;
static std::atomic<int64_t> g_cachedDurationUs{0};  // cached at open; avoids extractor race in getDurationMs
static AMediaCodec* g_decoderCodec = nullptr;
static int g_decoderTrackIndex = -1;
static std::atomic<bool> g_decoderRunning{false};
static std::atomic<bool> g_decoderPaused{false};
static std::atomic<bool> g_decoderStopRequested{false};
static std::atomic<bool> g_decoderEos{false};
static std::atomic<int64_t> g_decoderPositionUs{0};
static std::thread g_decoderThread;
static std::mutex g_decoderMutex;
static std::condition_variable g_decoderCv;
static std::vector<float> g_convertBuffer;
static std::atomic<bool> g_decoderIsFloat{false};  // 【V7.16】decoder output format
// 【V7.x】解码器实际输出编码（MediaFormat PCM_ENCODING）。AAC/M4A 的硬件解码器
// 即使 configure 请求 PCM16 也可能实际输出 PCM_FLOAT，必须按真实字节宽解析，
// 否则 float 字节被当 int16 错位读取 → 噪音/啸叫。
// 2=PCM_16BIT, 3=PCM_8BIT, 4=PCM_FLOAT, 21=PCM_24BIT_PACKED, 22=PCM_32BIT
static std::atomic<int> g_decoderOutputEncoding{2};

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
    if (g_outputStream) { g_outputStream->close(); g_outputStream.reset(); }
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setFormat(oboe::AudioFormat::Float);
    builder.setChannelCount(ch);
    builder.setCallback(&g_audioCallback);
    builder.setBufferCapacityInFrames(9600);
    if (devId > 0) builder.setDeviceId(devId);
    builder.setAudioApi(oboe::AudioApi::AAudio);
    builder.setSampleRate(newRate);
    builder.setSharingMode(oboe::SharingMode::Shared);
    oboe::Result result = builder.openManagedStream(g_outputStream);
    if (result != oboe::Result::OK) {
        builder.setAudioApi(oboe::AudioApi::Unspecified);
        result = builder.openManagedStream(g_outputStream);
    }
    if (result != oboe::Result::OK) {
        LOGE("reopenOutputStream failed: %s", oboe::convertToText(result));
        g_outputStream.reset();
        return false;
    }
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
        g_agc.setSampleRate(actualRate);
    }
    LOGI("reopenOutputStream: reopened at %d Hz / %d ch", actualRate, actualCh);
    return true;
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
        if (!inputEos && g_decoderCodec != nullptr) {
            ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(g_decoderCodec, 5000);
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
                int bytesPerSample = (enc == 4 || enc == 22) ? 4 : (enc == 21 ? 3 : 2);
                int numSamples = (int)(info.size / bytesPerSample);

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
                // 【V7.39】写入 RingBuffer：等待空间而非丢数据
                if (g_ringBuffer) {
                    int written = 0;
                    int remain = numSamples;
                    const float* src = g_convertBuffer.data();
                    int retryCount = 0;
                    while (remain > 0 && retryCount < 100) {
                        int n = g_ringBuffer->write(src + written, remain);
                        written += n;
                        remain -= n;
                        if (remain > 0) {
                            // Buffer 满了，等 Oboe 消费一些数据
                            usleep(2000);  // 2ms — Oboe 消费约 176 samples
                            retryCount++;
                        }
                    }
                    if (remain > 0) {
                        LOGW("RingBuffer write: dropped %d/%d samples after %d retries", remain, numSamples, retryCount);
                    }
                }
                g_decoderFramesOutput.fetch_add(numSamples / g_channelCount.load());  // 【V7.16】

                // Back-pressure: sleep if buffer is mostly full
                if (g_ringBuffer && g_ringBuffer->available() > kRingBufferCapacity * 3 / 4) {
                    usleep(5000);
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
                    int currentStreamRate = 0;
                    {
                        std::lock_guard<std::mutex> lock(g_streamMutex);
                        if (g_outputStream) currentStreamRate = g_outputStream->getSampleRate();
                    }
                    if (currentStreamRate > 0 && sr != currentStreamRate) {
                        LOGI("NDK Decoder: sample rate changed %d -> %d (HE-AAC SBR?), reopening stream", currentStreamRate, sr);
                        if (g_ringBuffer) g_ringBuffer->clear();
                        reopenOutputStream(sr);
                    } else {
                        g_fileSampleRate.store(sr);
                        g_sampleRate.store(sr);
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
    const char *path = env->GetStringUTFChars(filePath, nullptr);
    if (!path) return false;
    LOGI("OboeDirectPlayer: opening %s", path);

    g_decoderStopRequested.store(true);
    g_decoderPaused.store(false);
    g_decoderCv.notify_all();
    // Stop codec FIRST to unblock pending dequeue calls, then join thread
    if (g_decoderCodec) {
        AMediaCodec_stop(g_decoderCodec);
    }
    if (g_decoderThread.joinable()) g_decoderThread.join();
    std::lock_guard<std::mutex> lock(g_streamMutex);

    if (g_decoderCodec) {
        AMediaCodec_delete(g_decoderCodec);
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
            AMediaExtractor_selectTrack(g_decoderExtractor, g_decoderTrackIndex);

            g_decoderCodec = AMediaCodec_createDecoderByType(mime);
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
            media_status_t status = AMediaCodec_configure(g_decoderCodec, configureFormat, nullptr, nullptr, 0);
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
                status = AMediaCodec_configure(g_decoderCodec, format2, nullptr, nullptr, 0);
                AMediaFormat_delete(format2);
                if (status != AMEDIA_OK) {
                    LOGE("Failed to configure decoder: %d", status);
                    AMediaFormat_delete(format);
                    g_nativeOpenStep.store(5);
                    g_nativeOpenErrorCode.store(status);
                    return false;
                }
            }
            status = AMediaCodec_start(g_decoderCodec);
            if (status != AMEDIA_OK) {
                LOGE("Failed to start decoder: %d", status);
                AMediaFormat_delete(format);
                g_nativeOpenStep.store(6);
                g_nativeOpenErrorCode.store(status);
                return false;
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
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
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
        g_agc.setSampleRate(sr);
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
    LOGI("OboeDirectPlayer: opening FD %d offset=%lld length=%lld", fd, (long long)offset, (long long)length);

    g_decoderStopRequested.store(true);
    g_decoderPaused.store(false);
    g_decoderCv.notify_all();
    // Stop codec FIRST to unblock pending dequeue calls, then join thread
    if (g_decoderCodec) {
        AMediaCodec_stop(g_decoderCodec);
    }
    if (g_decoderThread.joinable()) g_decoderThread.join();
    std::lock_guard<std::mutex> lock(g_streamMutex);
    
    // 【V7.28】进度追踪变量清零（修复每曲累积偏移 bug）
    g_framesWritten.store(0);
    g_playbackPositionUs.store(0);
    g_totalSampleCount.store(0);
    g_clipSampleCount.store(0);
    g_decoderOutputEncoding.store(2);  // 【V7.x】重置输出编码为 PCM16
    g_decoderIsFloat.store(false);

    if (g_decoderCodec) {
        AMediaCodec_delete(g_decoderCodec);
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

    g_decoderExtractor = AMediaExtractor_new();
    if (!g_decoderExtractor) {
        LOGE("Failed to create AMediaExtractor for FD");
        g_nativeOpenStep.store(1);
        g_nativeOpenErrorCode.store(-1);
        return false;
    }

    media_status_t result = AMediaExtractor_setDataSourceFd(g_decoderExtractor, fd, offset, length);
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
            AMediaExtractor_selectTrack(g_decoderExtractor, g_decoderTrackIndex);

            g_nativeOpenStep.store(4);
            g_decoderCodec = AMediaCodec_createDecoderByType(mime);
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
                if (sr2 > 0) AMediaFormat_setInt32(configureFormat, AMEDIAFORMAT_KEY_SAMPLE_RATE, sr2);
                if (ch2 > 0) AMediaFormat_setInt32(configureFormat, AMEDIAFORMAT_KEY_CHANNEL_COUNT, ch2);
                if (dur > 0) AMediaFormat_setInt64(configureFormat, AMEDIAFORMAT_KEY_DURATION, dur);
            }
            copyCsdBuffers(configureFormat, format);
            AMediaFormat_setInt32(configureFormat, "pcm-encoding", 2);
            g_nativeOpenStep.store(5);
            media_status_t status = AMediaCodec_configure(g_decoderCodec, configureFormat, nullptr, nullptr, 0);
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
                status = AMediaCodec_configure(g_decoderCodec, format2, nullptr, nullptr, 0);
                AMediaFormat_delete(format2);
                if (status != AMEDIA_OK) {
                    LOGE("FD: Failed to configure decoder: %d", status);
                    AMediaFormat_delete(format);
                    return false;
                }
            }
            g_nativeOpenStep.store(6);
            status = AMediaCodec_start(g_decoderCodec);
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
    if (g_decoderTrackIndex < 0) {
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
    if (g_outputStream) {
        g_outputStream->close();
        g_outputStream.reset();
    }
    g_sampleRate.store(preferredRate);
    g_channelCount.store(channelCount);
    g_framesWritten.store(0);
    g_fileSampleRate.store(sampleRate);
    g_nativeOpenStep.store(8);

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
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

    if (forceExclusive) {
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
    } else {
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
        g_agc.setSampleRate(sr);
    }
    g_sampleRate.store(g_outputStream->getSampleRate());
    g_channelCount.store(g_outputStream->getChannelCount());
    const char* fdModeStr = g_outputStream->getSharingMode() == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared";
    LOGI("FD OboeDirectPlayer ready: rate=%d, ch=%d, mode=%s",
         g_outputStream->getSampleRate(), g_outputStream->getChannelCount(),
         fdModeStr);
    g_nativeOpenStep.store(10);
    return true;
}

// --- Playback control ---
JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativePlay(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_streamMutex);
    if (!g_outputStream || !g_decoderCodec || !g_decoderExtractor) {
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
    g_decoderStopRequested.store(false);
    g_decoderPaused.store(false);
    g_decoderEos.store(false);
    g_decoderRunning.store(true);
    g_decoderThreadRunning.store(true);  // 【V7.16】
    if (g_decoderThread.joinable()) g_decoderThread.join();
    g_decoderThread = std::thread(ndkDecodeLoop);
    LOGI("OboeDirectPlayer: playback started");
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
    LOGI("OboeDirectPlayer: seekTo %lld us", (long long)positionUs);
    g_decoderPaused.store(true);
    if (g_ringBuffer) g_ringBuffer->clear();
    g_framesWritten.store(0);
    g_playbackPositionUs.store(positionUs);  // 【V7.82】同步实际播放位置到 seek 目标，防止进度条回跳
    g_flushRequested.store(true);
    if (g_decoderCodec) AMediaCodec_flush(g_decoderCodec);
    if (g_decoderExtractor) AMediaExtractor_seekTo(g_decoderExtractor, positionUs, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
    g_decoderPositionUs.store(positionUs);
    g_decoderEos.store(false);
    g_decoderPaused.store(false);
    g_decoderCv.notify_all();
}

JNIEXPORT void JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeStop(JNIEnv *env, jobject thiz) {
    LOGI("OboeDirectPlayer: stopping");
    g_decoderStopRequested.store(true);
    g_decoderPaused.store(false);
    g_decoderCv.notify_all();
    // Stop codec FIRST to unblock pending dequeueInputBuffer/OutputBuffer, then join
    if (g_decoderCodec) {
        AMediaCodec_stop(g_decoderCodec);
    }
    if (g_decoderThread.joinable()) g_decoderThread.join();
    std::lock_guard<std::mutex> lock(g_streamMutex);
    if (g_outputStream) {
        g_outputStream->requestStop();
        g_outputStream->close();
        g_outputStream.reset();
    }
    g_isPlaying.store(false);
    if (g_decoderCodec) {
        AMediaCodec_delete(g_decoderCodec);
        g_decoderCodec = nullptr;
        // MediaCodec looper destruction is async — let it release mActivityNotify
        std::this_thread::sleep_for(std::chrono::milliseconds(300));
    }
    if (g_decoderExtractor) {
        AMediaExtractor_delete(g_decoderExtractor);
        g_decoderExtractor = nullptr;
    }
    if (g_ringBuffer) g_ringBuffer->clear();
    g_decoderRunning.store(false);
    g_decoderThreadRunning.store(false);
    g_decoderEos.store(false);
    g_decoderTrackIndex = -1;
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
    return playbackPosUs / 1000LL;  // 【V7.67】使用实际播放位置（微秒→毫秒）
}

JNIEXPORT jlong JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeGetDurationMs(JNIEnv *env, jobject thiz) {
    // Return cached duration to avoid AMediaExtractor_getTrackFormat race
    // (extractor may be torn down concurrently during track switch / release)
    return g_cachedDurationUs.load() / 1000;
}

JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeIsEos(JNIEnv *env, jobject thiz) {
    // Must wait for both: decoder received EOS AND drain loop exited
    // (RingBuffer empty). Otherwise completion fires while audio still playing.
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

// --- DSP Mode ---
JNIEXPORT void JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeSetDspMode(JNIEnv *env, jobject thiz, jint mode) {
    std::lock_guard<std::mutex> eqLock(g_eqMutex);
    int sr = g_sampleRate.load();
    // 【V7.200】MSEB active → refuse to overwrite 5-band EQ with DSP mode
    if (g_msebActive.load()) {
        LOGI("nativeSetDspMode: refused (mode=%d) — MSEB active", mode);
        g_dspMode.store(mode);
        g_dspEqEnabled.store(mode >= 0);
        // Allow DSP mode flag change but don't touch 5-band EQ coefficients
        return;
    }
    g_dspMode.store(mode);
    g_eq5BandEnabled.store(false);  // 【V7.80】切换DSP模式→禁用5段预设
    g_autoEqEnabled.store(false);
    g_autoEqPreGain = 1.0f;
    // 【V7.38】模式切换时同步开关：OFF(-1)→关闭, 其他→开启
    g_dspEqEnabled.store(mode >= 0);
    LOGI("DSP Mode=%d, DSP Enabled=%s", mode, mode >= 0 ? "YES" : "NO");
    if (mode == 1) {
        // Cat Mode: 高音柔和 + 低频增强 + 极高频保护
        g_eqBand1L.setHighShelf(sr, 8000.0f, -4.0f, 0.707f);
        g_eqBand1R.setHighShelf(sr, 8000.0f, -4.0f, 0.707f);
        g_eqBand2L.setPeaking(sr, 12000.0f, -3.0f, 2.0f);
        g_eqBand2R.setPeaking(sr, 12000.0f, -3.0f, 2.0f);
        g_eqBand3L.setPeaking(sr, 250.0f, 3.0f, 0.5f);
        g_eqBand3R.setPeaking(sr, 250.0f, 3.0f, 0.5f);
        g_dspEqPreGain.store(powf(10.0f, -2.0f / 20.0f));  // -2dB headroom
        LOGI("DSP Mode: Cat Mode (HS -4dB@8kHz + Peak -3dB@12kHz + Bass +3dB@250Hz)");
        LOGI("CAT Band1L b0=%.4f b1=%.4f b2=%.4f a1=%.4f a2=%.4f",
             g_eqBand1L.norm_b0_, g_eqBand1L.norm_b1_, g_eqBand1L.norm_b2_,
             g_eqBand1L.norm_a1_, g_eqBand1L.norm_a2_);
    } else if (mode == 0) {
        // Steven Special: 高音大幅柔和 + 极高频保护
        g_eqBand1L.setHighShelf(sr, 8000.0f, -6.0f, 0.707f);
        g_eqBand1R.setHighShelf(sr, 8000.0f, -6.0f, 0.707f);
        g_eqBand2L.setPeaking(sr, 12000.0f, -4.0f, 2.0f);
        g_eqBand2R.setPeaking(sr, 12000.0f, -4.0f, 2.0f);
        g_eqBand3L.setHighShelf(sr, 15000.0f, -2.0f, 1.0f);
        g_eqBand3R.setHighShelf(sr, 15000.0f, -2.0f, 1.0f);
        g_dspEqPreGain.store(powf(10.0f, -3.0f / 20.0f));  // -3dB headroom
        LOGI("DSP Mode: Steven Special (HS -6dB@8kHz + Peak -4dB@12kHz + HS -2dB@15kHz)");
        LOGI("STEVEN Band1L b0=%.4f b1=%.4f b2=%.4f a1=%.4f a2=%.4f",
             g_eqBand1L.norm_b0_, g_eqBand1L.norm_b1_, g_eqBand1L.norm_b2_,
             g_eqBand1L.norm_a1_, g_eqBand1L.norm_a2_);
    } else {
        // OFF: flatten all bands
        g_eqBand1L.setFlat(); g_eqBand1R.setFlat();
        g_eqBand2L.setFlat(); g_eqBand2R.setFlat();
        g_eqBand3L.setFlat(); g_eqBand3R.setFlat();
        g_dspEqPreGain.store(1.0f);
        LOGI("DSP Mode: OFF (all bands flat)");
    }
    g_eqBand1L.reset(); g_eqBand1R.reset();
    g_eqBand2L.reset(); g_eqBand2R.reset();
    g_eqBand3L.reset(); g_eqBand3R.reset();
}

JNIEXPORT jint JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeGetDspMode(JNIEnv *env, jobject thiz) {
    return g_dspMode.load(std::memory_order_relaxed);
}

// --- 【V7.80】5段图形均衡器预设 ---
// 将28个品牌预设映射到DSP 5-band peaking EQ
// 标准频率: 60Hz / 230Hz / 910Hz / 3.6kHz / 14kHz
static const float kEq5BandDefaultFreqs[5] = {60.0f, 230.0f, 910.0f, 3600.0f, 14000.0f};

JNIEXPORT void JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeSetDspEq5Band(JNIEnv *env, jobject thiz,
                                                               jfloatArray gainsDb, jfloatArray freqsHz) {
    std::lock_guard<std::mutex> eqLock(g_eqMutex);
    float sr = static_cast<float>(g_sampleRate.load());
    if (sr <= 0) return;
    
    jfloat* gains = env->GetFloatArrayElements(gainsDb, nullptr);
    jfloat* freqs = freqsHz ? env->GetFloatArrayElements(freqsHz, nullptr) : nullptr;
    jsize len = env->GetArrayLength(gainsDb);
    
    struct { BiquadFilter* L; BiquadFilter* R; } bands[5] = {
        {&g_eqBand1L, &g_eqBand1R}, {&g_eqBand2L, &g_eqBand2R},
        {&g_eqBand3L, &g_eqBand3R}, {&g_eqBand4L, &g_eqBand4R},
        {&g_eqBand5L, &g_eqBand5R}
    };
    
    for (int i = 0; i < len && i < 5; i++) {
        float gainDb = gains[i];
        float freqHz = freqs ? freqs[i] : kEq5BandDefaultFreqs[i];
        float Q = 1.4f;  // Graphic EQ Q — narrower than default to reduce band overlap
        bands[i].L->reset(); bands[i].R->reset();
        if (fabsf(gainDb) < 0.1f) {
            bands[i].L->setFlat(); bands[i].R->setFlat();
        } else {
            bands[i].L->setPeaking(sr, freqHz, gainDb, Q);
            bands[i].R->setPeaking(sr, freqHz, gainDb, Q);
        }
    }
    
    // 确保 bands 4-5 在 len<5 时也是 flat
    for (int i = len; i < 5; i++) {
        bands[i].L->reset(); bands[i].R->reset();
        bands[i].L->setFlat(); bands[i].R->setFlat();
    }
    
    // Pre-gain headroom：防止正增益导致削波
    // 取所有频段最大正增益 + 3dB 安全余量，折算为线性衰减系数
    float maxGain = 0.0f;
    for (int i = 0; i < 5; i++) {
        if (gains[i] > maxGain) maxGain = gains[i];
    }
    float preGainDb = -maxGain;  // 1:1 headroom — Look-Ahead Limiter handles cascade overshoot
    g_dspEqPreGain = powf(10.0f, preGainDb / 20.0f);
    
    g_eq5BandEnabled.store(true);
    g_dspEqEnabled.store(true);
    g_dspMode.store(-1);  // 预设模式时禁用 DSP 模式
    
    env->ReleaseFloatArrayElements(gainsDb, gains, 0);
    if (freqs) env->ReleaseFloatArrayElements(freqsHz, freqs, 0);
    
    LOGI("DSP 5-band EQ preset applied: [%.1f, %.1f, %.1f, %.1f, %.1f] dB",
         gains[0], gains[1], gains[2], gains[3], gains[4]);
}

// 【V7.200】MSEB activation guard — protects 5-band EQ from being overwritten by DSP mode / reset
JNIEXPORT void JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeSetMsebActive(JNIEnv *env, jobject thiz, jboolean active) {
    g_msebActive.store(active);
    LOGI("MSEB active = %s", active ? "YES" : "NO");
}

JNIEXPORT void JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeResetDspEq5Band(JNIEnv *env, jobject thiz) {
    // 【V7.200】MSEB active → refuse reset, MSEB owns the 5-band EQ pipeline
    if (g_msebActive.load()) {
        LOGI("nativeResetDspEq5Band: refused — MSEB active");
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
    g_autoEqEnabled.store(false);
    g_autoEqPreGain = 1.0f;
    g_dspEqPreGain = 1.0f;  // 重置 pre-gain 为 0dB
    LOGI("DSP 5-band EQ preset cleared, switching back to DSP mode");
    // 恢复默认 Steven Special 模式
    g_dspMode.store(0);
    g_dspEqEnabled.store(true);
    int sr = g_sampleRate.load();
    g_eqBand3L.setHighShelf(sr, 15000.0f, -2.0f, 1.0f);
    g_eqBand3R.setHighShelf(sr, 15000.0f, -2.0f, 1.0f);
}

// --- AutoEQ 10-band ---
// filterType: 0=Peaking, 1=HighShelf, 2=LowShelf
JNIEXPORT void JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeSetAutoEq10Band(JNIEnv *env, jobject thiz,
    jfloatArray gainsDb, jfloatArray freqsHz, jfloatArray qValues, jintArray filterTypes, jfloat preampDb) {
    std::lock_guard<std::mutex> eqLock(g_eqMutex);
    int sr = g_sampleRate.load();
    if (sr <= 0) sr = 48000;

    jfloat* gains = env->GetFloatArrayElements(gainsDb, nullptr);
    jfloat* freqs = env->GetFloatArrayElements(freqsHz, nullptr);
    jfloat* qs = env->GetFloatArrayElements(qValues, nullptr);
    jint* types = env->GetIntArrayElements(filterTypes, nullptr);

    struct { BiquadFilter* L; BiquadFilter* R; } bands[10] = {
        {&g_eqBand1L, &g_eqBand1R}, {&g_eqBand2L, &g_eqBand2R},
        {&g_eqBand3L, &g_eqBand3R}, {&g_eqBand4L, &g_eqBand4R},
        {&g_eqBand5L, &g_eqBand5R}, {&g_autoEqBand6L, &g_autoEqBand6R},
        {&g_autoEqBand7L, &g_autoEqBand7R}, {&g_autoEqBand8L, &g_autoEqBand8R},
        {&g_autoEqBand9L, &g_autoEqBand9R}, {&g_autoEqBand10L, &g_autoEqBand10R}
    };

    for (int i = 0; i < 10; i++) {
        bands[i].L->reset(); bands[i].R->reset();
        if (fabsf(gains[i]) < 0.01f) {
            bands[i].L->setFlat(); bands[i].R->setFlat();
            continue;
        }
        int type = types[i];
        if (type == 1) {  // HighShelf
            bands[i].L->setHighShelf(sr, freqs[i], gains[i], qs[i]);
            bands[i].R->setHighShelf(sr, freqs[i], gains[i], qs[i]);
        } else if (type == 2) {  // LowShelf
            bands[i].L->setLowShelf(sr, freqs[i], gains[i], qs[i]);
            bands[i].R->setLowShelf(sr, freqs[i], gains[i], qs[i]);
        } else {  // Peaking (default)
            bands[i].L->setPeaking(sr, freqs[i], gains[i], qs[i]);
            bands[i].R->setPeaking(sr, freqs[i], gains[i], qs[i]);
        }
    }

    g_autoEqPreGain = powf(10.0f, preampDb / 20.0f);
    g_autoEqEnabled.store(true);
    g_eq5BandEnabled.store(false);  // 互斥
    g_dspEqEnabled.store(true);
    g_dspMode.store(-1);

    env->ReleaseFloatArrayElements(gainsDb, gains, 0);
    env->ReleaseFloatArrayElements(freqsHz, freqs, 0);
    env->ReleaseFloatArrayElements(qValues, qs, 0);
    env->ReleaseIntArrayElements(filterTypes, types, 0);

    LOGI("AutoEQ 10-band applied: preamp=%.1fdB", preampDb);
}

JNIEXPORT void JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeResetAutoEq(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> eqLock(g_eqMutex);
    // 重置 bands 6-10
    BiquadFilter* autoBands[10] = {
        &g_autoEqBand6L, &g_autoEqBand6R, &g_autoEqBand7L, &g_autoEqBand7R,
        &g_autoEqBand8L, &g_autoEqBand8R, &g_autoEqBand9L, &g_autoEqBand9R,
        &g_autoEqBand10L, &g_autoEqBand10R
    };
    for (int i = 0; i < 10; i++) {
        autoBands[i]->reset();
        autoBands[i]->setFlat();
    }
    g_autoEqEnabled.store(false);
    g_autoEqPreGain = 1.0f;
    LOGI("AutoEQ 10-band cleared");
    // 恢复默认 Steven Special 模式
    g_dspMode.store(0);
    g_dspEqEnabled.store(true);
    int sr = g_sampleRate.load();
    if (sr > 0) {
        g_eqBand3L.setHighShelf(sr, 15000.0f, -2.0f, 1.0f);
        g_eqBand3R.setHighShelf(sr, 15000.0f, -2.0f, 1.0f);
    }
}

// --- AGC ---
JNIEXPORT void JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeSetAgcEnabled(JNIEnv *env, jobject thiz, jboolean enabled) {
    g_agcEnabled.store(enabled != 0);
    if (!g_agcEnabled.load()) g_agc.reset();
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

// --- Dither ---
JNIEXPORT void JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeSetDitherEnabled(JNIEnv *env, jobject thiz, jboolean enabled) {
    g_ditherEnabled.store(enabled != 0);
    LOGI("TPDF Dither %s", enabled ? "ON" : "OFF");
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

} // extern "C"
