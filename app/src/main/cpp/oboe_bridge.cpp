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
#include "biquad_filter.h"
#include "loudness_comp.h"
#include "alac/ALACDecoder.h"
#include "alac/ALACBitUtilities.h"
#include <cstdio>
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
static std::atomic<bool> g_decoderStopRequestedB{false};
static std::condition_variable g_decoderCvB;
static PCMRingBuffer *g_ringBufferB = nullptr;   // 【Crossfade】B 轨 ring
static std::atomic<bool> g_activeIsB{false};     // 【Crossfade】active 轨是否为 B
static std::atomic<bool> g_crossfadeActive{false}; // 【Crossfade】正在交叉淡化
static std::atomic<float> g_crossfadePos{0.0f};    // 【Crossfade】进度 0..1
static std::atomic<float> g_crossfadeStep{0.0f};   // 【Crossfade】每帧增量
static std::atomic<int> g_crossfadeDurationMs{0};  // 【Crossfade】时长（完成后重置播放位置用）
// 【V8.15】B 轨起点(ms)：crossfade 完成时位置 = bStartMs + 已淡入时长（进度条正确显示）
static std::atomic<int64_t> g_xfadeBStartMs{0};
static float g_xfadeBufA[16384];   // 【Crossfade】A 轨临时缓冲（实时线程安全 static）
static float g_xfadeBufB[16384];   // 【Crossfade】B 轨临时缓冲
static float g_xfadeHpfPrevB[8] = {0};  // 【Crossfade 低频防浑浊】B 轨 1 阶 HPF 每声道前一采样状态
static float g_xfadeLpfPrevA[8] = {0};  // 【V8.23 A 轨对称低切】A 轨 1 阶 LPF 每声道前一采样状态
// 【V8.24 B 音量预匹配】B 副歌段参考 RMS（preScanChorusMs 写入，crossfade 启动时读）与 B 轨预增益
static std::atomic<float> g_xfadeChorusRms{0.0f};
static std::atomic<float> g_xfadeBPreGain{1.0f};
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
// DtsSurround — 【V8.19】DTS 虚拟环绕渲染（方案 A 延迟反馈 + 方案 B 低音分频）
// 在 M/S 中侧分解基础上增强：
//   A) 延迟反馈网络：side 延迟 10ms + 低通 2.5kHz（后墙反射）反相回灌 L/R，
//      营造环绕包围感（量随 width 增强而增加，width=1 时无环绕）；
//   B) 低音分频：side 先过 180Hz 高通，低频只保留在 mid（单声道低音），
//      避免虚拟环绕把低频也扩宽导致低音发虚/漂移。
// 纯 Oboe 路径（DAC bit-perfect 直通不受影响）。
// ============================================================================
class DtsSurround {
public:
    static constexpr int MAX_DELAY = 4096;             // 48k ~85ms 余量
    static constexpr float SIDE_DELAY_S = 0.010f;      // 10ms 侧向环绕延迟
    static constexpr float SIDE_LP_HZ = 2500.0f;       // 环绕高频吸收（后墙）
    static constexpr float SIDE_HP_HZ = 180.0f;        // 低音分频点（方案 B）
    static constexpr float HAAS_DELAY_S = 0.0008f;     // 0.8ms 中置 HAAS 强化

    float histS[MAX_DELAY] = {0};
    float histM[MAX_DELAY] = {0};
    int writePos = 0;

    int delayS = 480;      // 10ms @48k
    int delayM = 38;       // 0.8ms @48k
    float lpCoeff = 0.0f;  // side 低通（环绕吸收）
    float lpState = 0.0f;
    float hpCoeff = 0.0f;  // side 高通（分频）
    float hpState = 0.0f;
    float haasLp = 0.0f;   // HAAS 低通态（M 延迟后微低通防相位梳状）

    void setSampleRate(float sr) {
        delayS = (int)(SIDE_DELAY_S * sr + 0.5f);
        if (delayS < 1) delayS = 1;
        if (delayS >= MAX_DELAY) delayS = MAX_DELAY - 1;
        delayM = (int)(HAAS_DELAY_S * sr + 0.5f);
        if (delayM < 1) delayM = 1;
        if (delayM >= MAX_DELAY) delayM = MAX_DELAY - 1;
        lpCoeff = 1.0f - expf(-2.0f * (float)M_PI * SIDE_LP_HZ / sr);
        hpCoeff = 1.0f - expf(-2.0f * (float)M_PI * SIDE_HP_HZ / sr);
    }

    void reset() {
        memset(histS, 0, sizeof(histS));
        memset(histM, 0, sizeof(histM));
        writePos = 0;
        lpState = 0.0f;
        hpState = 0.0f;
        haasLp = 0.0f;
    }

    // 处理一对样本。mid/side 为输入分解，w = S 增益(≥1 增宽)，c = M 增益。
    // 输出 outL/outR 为渲染结果（含方案 A+B）。
    void process(float mid, float side, float w, float c, float &outL, float &outR) {
        // ---- 方案 B：低音分频 — side 高通 180Hz，低频只留 mid（单声道低音）----
        float hpIn = side;
        hpState += hpCoeff * (hpIn - hpState);
        float sideHp = hpIn - hpState;   // 一阶 HPF（180Hz 以上侧向）

        // ---- 方案 A：延迟反馈网络 ----
        histS[writePos] = sideHp;
        histM[writePos] = mid;
        int rpS = writePos - delayS;
        if (rpS < 0) rpS += MAX_DELAY;
        int rpM = writePos - delayM;
        if (rpM < 0) rpM += MAX_DELAY;

        // 环绕：side 延迟 + 低通（后墙反射吸收高频），反相回灌
        lpState += lpCoeff * (histS[rpS] - lpState);
        // 环绕强度随增宽量调制：w=1 → 0，w=1.8 → ~0.4
        float amb = (w - 1.0f) * 0.5f;
        if (amb < 0.0f) amb = 0.0f;
        if (amb > 0.5f) amb = 0.5f;
        float rear = amb * lpState;

        // HAAS 中置强化：mid 延迟 0.8ms（微低通防梳状）作为中置额外聚焦
        haasLp += 0.2f * (histM[rpM] - haasLp);   // ~2kHz 平滑
        float focus = (c - 1.0f) * 0.5f;          // c=1 → 0，c=1.6 → 0.3
        if (focus < 0.0f) focus = 0.0f;
        if (focus > 0.5f) focus = 0.5f;

        // ---- 合成 ----
        float sW = sideHp * w;
        outL = mid * c + sW + rear + haasLp * focus;
        outR = mid * c - sW - rear + haasLp * focus;

        writePos = (writePos + 1) % MAX_DELAY;
    }
};
static DtsSurround g_dts;

// ============================================================================
// Crossfeed — 消除头中效应（耳机虚拟声场第一步）
// 左耳混入少量经过延迟 + 低通衰减的右声道（模拟头部遮挡与耳间路径差 ITD），
// 反之亦然。一阶低通 ~700Hz（头部遮挡高频），ITD ~300μs（头宽路径差）。
// ============================================================================
// 5.1 虚拟环绕上混（DTS 风格）：立体声 → 中侧分解 → 增宽 + 环绕反射 → 渲染回耳机。
// 中侧分解：M=(L+R)/2（单声道成分）、S=(L-R)/2（侧向成分）。
// 增宽 = 放大 S（width>1）；环绕 = S 延迟 + 低通后反相回灌（后墙反射包围感）。
class Crossfeed {
public:
    static constexpr int MAX_DELAY = 512;  // 48kHz 下 512 samples ≈ 10.7ms，容纳后墙反射
    static constexpr float REAR_DELAY_S = 0.012f;   // 12ms 后墙反射（≈4m 声程差）
    static constexpr float REAR_LP_HZ = 2500.0f;    // 后墙反射高频吸收
    static constexpr float REAR_GAIN = 0.5f;        // 环绕反射强度
    static constexpr float WIDTH_BOOST = 0.6f;      // amount=1 时 S 放大到 1.6x（增宽）

    float histS[MAX_DELAY] = {0};   // 侧向(S)历史，做环绕延迟
    int writePos = 0;

    int delayRear = 48;
    float lpRearCoeff = 0.28f;
    float lpRearS = 0;

    void setSampleRate(float sr) {
        delayRear = (int)(REAR_DELAY_S * sr + 0.5f);
        if (delayRear < 1) delayRear = 1;
        if (delayRear > MAX_DELAY) delayRear = MAX_DELAY;
        lpRearCoeff = 1.0f - expf(-2.0f * 3.14159265f * REAR_LP_HZ / sr);
    }

    void reset() {
        memset(histS, 0, sizeof(histS));
        writePos = 0;
        lpRearS = 0;
    }

    // 处理一对样本，amount 0~1（环绕强度）。返回前完成 L/R 虚拟化渲染。
    void process(float &sL, float &sR, float amount) {
        float inL = sL, inR = sR;

        // 中侧分解
        float mid = (inL + inR) * 0.5f;
        float side = (inL - inR) * 0.5f;

        // 增宽：放大 side 成分（amount 越大越宽）
        float width = 1.0f + amount * WIDTH_BOOST;

        // 环绕：side 延迟 + 低通（后墙反射），反相分左右
        histS[writePos] = side;
        int rpRear = writePos - delayRear;
        if (rpRear < 0) rpRear += MAX_DELAY;
        lpRearS += lpRearCoeff * (histS[rpRear] - lpRearS);
        float rear = REAR_GAIN * amount * lpRearS;

        // 合成：M + S*width ± 环绕
        sL = mid + side * width + rear;
        sR = mid - side * width - rear;

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
    l += hpL * envL * boost;

    if (stereo) {
        float hpR = k * (r - g_btPreHpX1R) + (1.0f - k) * g_btPreHpY1R;
        g_btPreHpX1R = r; g_btPreHpY1R = hpR;
        float envR = fabsf(hpR) > g_btPreEnvR ? g_btPreEnvR + atk * (fabsf(hpR) - g_btPreEnvR)
                                              : g_btPreEnvR + rel * (fabsf(hpR) - g_btPreEnvR);
        g_btPreEnvR = envR;
        r += hpR * envR * boost;
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
            if (toReadFrom > 0) fromRing->read(g_xfadeBufA, toReadFrom);
            if (toReadTo > 0) toRing->read(g_xfadeBufB, toReadTo);
            float pos = g_crossfadePos.load();
            float step = g_crossfadeStep.load();
            const float HP = 1.5707963f;  // PI/2
            for (int f = 0; f < numFrames; f++) {
                // 【方案2 等响度】smoothstep S 曲线替代等功率 cos/sin：中间段两轨都更响，补偿等功率 dip
                // 端点处 gTo/gFrom 仍是 0/1 完全淡出淡入，语义不变
                // 【V8.15】ease-out 互补曲线：B 轨快速进入（pos=0.3 时已有 51% 音量），A 轨快速淡出
                // 总响度恒 1（等功率互补）：gFrom=(1-pos)^2, gTo=1-(1-pos)^2
                float om = 1.0f - pos;
                float gTo = 1.0f - om * om;   // B 轨：ease-out，前段快速上升
                float gFrom = om * om;        // A 轨：互补快速淡出
                // 【方案3 低频防浑浊】B 轨淡入时低频与 A 轨叠加易浑浊：
                // 随 pos 从 0 到 0.6 线性衰减的高通（θ=pos/0.6），滤除 B 轨低频；
                // θ>=1（pos>=0.6）后直通，不改变最终听感（A 轨此时已基本淡出）
                float theta = pos < 0.6f ? pos / 0.6f : 1.0f;   // 0..1
                float hpfMix = 1.0f - theta;                     // 1 -> 0 线性衰减
                float hpfK = 0.35f * hpfMix;                     // 1 阶 HPF 系数（~85Hz@44.1k）
                for (int c = 0; c < channels; c++) {
                    int idx = f * channels + c;
                    float from = idx < toReadFrom ? g_xfadeBufA[idx] : 0.0f;
                    float to = idx < toReadTo ? g_xfadeBufB[idx] : 0.0f;
                    // 【V8.24 B 音量预匹配】B 轨预增益（仅 crossfade 激活期间生效；complete 后 B 上位成新 A，
                    //  必须恢复 1.0，否则整首歌持续低音量 —— 2026-09-01 音量忽大忽小根因）
                    if (g_crossfadeActive.load()) to *= g_xfadeBPreGain.load(std::memory_order_relaxed);
                    // B 轨低频防浑浊：1 阶 high-pass（y[n] = k*(x[n]-x[n-1]) + (1-k)*y[n-1]）
                    if (hpfK > 0.0f && c < 8) {
                        float y = hpfK * (to - g_xfadeHpfPrevB[c]) + (1.0f - hpfK) * g_xfadeHpfPrevB[c];
                        // 低频滤除+原信号混合：hp = y（纯 HPF 输出），随 hpfMix 线性 blend 回原信号
                        float hp = y;
                        to = hp * hpfMix + to * (1.0f - hpfMix);
                        g_xfadeHpfPrevB[c] = y;
                    }
                    // 【V8.23 A 轨对称低切】A 轨退场时低频渐进衰减（1 阶 low-pass），与 B 进场 HPF 对称
                    // pos 0.4→1.0 期间 lpfMix 0→1：A 低频渐出，让出低频空间给 B；端点语义不变
                    float lpfTheta = pos < 0.4f ? 0.0f : (pos - 0.4f) / 0.6f;  // 0..1
                    float lpfMix = lpfTheta < 1.0f ? lpfTheta : 1.0f;         // 0 -> 1
                    if (lpfMix > 0.0f && c < 8) {
                        float lpfK = 0.35f * lpfMix;
                        float yl = from * (1.0f - lpfK) + g_xfadeLpfPrevA[c] * lpfK;  // 1 阶 low-pass
                        from = yl * lpfMix + from * (1.0f - lpfMix);                 // 低切 blend 回原信号
                        g_xfadeLpfPrevA[c] = yl;
                    }
                    output[idx] = from * gFrom + to * gTo;
                }
                pos += step;
            }
            if (toReadFrom < totalSamples || toReadTo < totalSamples) {
                g_underrunCount.fetch_add(1, std::memory_order_relaxed);
            }
            if (pos >= 1.0f) {
                // crossfade 完成：翻转 active 槽，停旧 active（无阻塞，join 延后到下次 open/stop）
                g_crossfadeActive.store(false);
                g_activeIsB.store(!fromB);
                // 【Crossfade】新 active 轨已播 crossfade 时长，重置播放位置（否则延续旧轨 position）
                // 【V8.15】B 从副歌(bStartMs)进：位置 = bStartMs + 已淡入时长（而非从 0/15s 算）
                g_playbackPositionUs.store((g_xfadeBStartMs.load() + (int64_t)g_crossfadeDurationMs.load()) * 1000LL);
                g_decoderPositionUs.store((g_xfadeBStartMs.load() + (int64_t)g_crossfadeDurationMs.load()) * 1000LL);
                g_xfadeBPreGain.store(1.0f, std::memory_order_relaxed);  // 【V8.24】B 上位后恢复全音量
                if (fromB) {
                    g_decoderStopRequestedB.store(true);
                    g_decoderCvB.notify_all();
                    LOGI("Crossfade complete: A is now active");
                } else {
                    g_decoderStopRequested.store(true);
                    g_decoderCv.notify_all();
                    LOGI("Crossfade complete: B is now active");
                }
            } else {
                g_crossfadePos.store(pos);
            }
            // 方案 B：不 return，继续走 RMS/频谱/DSP（DSP 只对混合后音频跑一次）
        } else {
            // 正常路径：读 active ring（crossfade 完成后 active 可能 = B）
            PCMRingBuffer* activeRing = g_activeIsB.load() ? g_ringBufferB : g_ringBuffer;
            if (!activeRing) {
                memset(output, 0, totalSamples * sizeof(float));
                return oboe::DataCallbackResult::Continue;
            }
            int available = activeRing->available();
            g_ringBufferFill.store(available, std::memory_order_relaxed);  // 【V7.16】
            toRead = std::min(available, totalSamples);
            if (toRead > 0) {
                activeRing->read(output, toRead);
            }
            if (toRead < totalSamples) {
                g_underrunCount.fetch_add(1, std::memory_order_relaxed);  // 【V7.39】
                memset(output + toRead, 0, (totalSamples - toRead) * sizeof(float));
            }
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
        // 【V8.3 修复】外层条件必须包含所有独立模块：EQ（MSEB/AutoEQ/图形）之外，
        // M/S 声场、环绕（crossfeed）、瞬态、压缩、AGC 各自独立工作，不能因 EQ 全关而失效。
        if (g_mseb10Enabled.load() || g_autoEqEnabled.load() || g_dspEqEnabled.load() || g_loudnessEnabled.load()
            || g_msEnabled.load() || g_crossfeedEnabled.load() || g_transientEnabled.load() || g_compressorEnabled.load()
            || g_agcEnabled.load()) {
            // [zipper-noise fix] fade pre-gain toward target per-frame (same SMOOTH as DAC path).
            // MSEB slider changes (g_dspEqPreGain jump) no longer step output level instantly.
            float targetPreGain = 1.0f;
            if (g_autoEqEnabled.load()) targetPreGain *= g_autoEqPreGain.load();
            if (g_mseb10Enabled.load()) targetPreGain *= g_msebPreGain.load();
            if (!g_mseb10Enabled.load() && !g_autoEqEnabled.load() && g_dspEqEnabled.load()) targetPreGain *= g_dspEqPreGain.load();
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
                    float agcGain = g_agc.updateAndGetGainFromEnergy(frameEnergyAccum, samplesInFrame);
                    sL *= agcGain;
                    if (stereo) sR *= agcGain;
                }

                // 【V8.3】等响补偿（ISO 226）—— EQ/masterGain/AGC 之后、M/S 之前。
                // stereo-linked：L/R 共享 phon 估算与补偿系数，各保独立滤波状态。
                if (g_loudnessEnabled.load()) {
                    g_loudness.setOutGain(masterGain);
                    g_loudness.process(sL, stereo ? sR : sL);
                    if (!stereo) sR = sL;
                }

                // 【V8.3】【V8.19】M/S 声场处理 — DTS 虚拟环绕渲染（仅 stereo）。
                // M=(L+R)/2 结像中心，S=(L-R)/2 声场宽度。
                // V8.19 升级：方案 A 延迟反馈（side 10ms 环绕回灌 + mid HAAS 0.8ms 中置强化）
                // + 方案 B 低音分频（side 180Hz 高通，低频单声道防虚）。
                // 兼容：width=center=1 时矩阵恒等（amb/focus=0），完全旁路原声。
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
                    float mid = (sL + sR) * 0.5f;
                    float side = (sL - sR) * 0.5f;
                    float outL, outR;
                    g_dts.process(mid, side, w, c, outL, outR);
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
                        float gainL = 1.0f + amt * transL * 4.0f;
                        if (gainL < 0.05f) gainL = 0.05f;
                        if (gainL > 4.0f) gainL = 4.0f;
                        sL *= gainL;
                        if (stereo) {
                            float aR = fabsf(sR);
                            g_tsFastEnvR += (aR - g_tsFastEnvR) * (aR > g_tsFastEnvR ? tsAtkFast : tsRelFast);
                            g_tsSlowEnvR += (aR - g_tsSlowEnvR) * (aR > g_tsSlowEnvR ? tsAtkSlow : tsRelSlow);
                            float transR = g_tsFastEnvR - g_tsSlowEnvR;
                            float gainR = 1.0f + amt * transR * 4.0f;
                            if (gainR < 0.05f) gainR = 0.05f;
                            if (gainR > 4.0f) gainR = 4.0f;
                            sR *= gainR;
                        }
                    }
                }

                // 【V8.3】动态压缩 — master bus 向下压缩器（stereo-linked）。
                // 作用在瞬态整形之后、Look-Ahead Limiter 之前。
                if (g_compressorEnabled.load()) {
                    g_compressor.process(sL, sR);
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

                // 【2026-09-07】A2DP 编码前预补偿（蓝牙低码率编码专用，codecDb=0 时旁路）
                btPreEmphasisFrame(outL, outR, stereo);

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
// 【Crossfade】g_decoderStopRequested / g_decoderCv 已前置声明到 onAudioReady 之前
static std::atomic<bool> g_decoderEos{false};
// 【Crossfade】g_decoderPositionUs 已前置声明到 onAudioReady 之前
static std::thread g_decoderThread;
static std::mutex g_decoderMutex;
// 【Crossfade】B 轨独立 decoder（incoming）
static AMediaExtractor* g_decoderExtractorB = nullptr;
static AMediaCodec* g_decoderCodecB = nullptr;
static int g_decoderTrackIndexB = -1;
static std::atomic<bool> g_decoderRunningB{false};
static std::atomic<bool> g_decoderPausedB{false};
static std::atomic<bool> g_decoderEosB{false};
static std::atomic<int64_t> g_decoderPositionB{0};
static std::thread g_decoderThreadB;
static std::mutex g_decoderMutexB;
static std::vector<float> g_convertBufferB;
static std::atomic<int> g_decoderOutputEncodingB{2};
static std::atomic<int64_t> g_cachedDurationB{0};
static std::atomic<bool> g_decoderThreadRunningB{false};
static std::atomic<int> g_decoderFramesOutputB{0};
// 【Crossfade 重采样】B 槽 incoming 采样率 + 线性重采样到 stream 采样率的状态
// 目的：A 轨(48k) crossfade 到 B 轨(44.1k) 时，B 轨数据若不重采样会被 48k stream 快放 -> 变调
static std::atomic<int> g_sampleRateB{0};          // B 轨 decoder 输出采样率（0=未定）
static std::atomic<int> g_channelCountB{2};        // 【V8.3】B 轨 decoder 输出声道数（重采样用，不能共享 A 轨的）
static std::vector<float> g_resampleOutB;          // B 轨重采样输出缓冲
static double g_resamplePhaseB = 0.0;              // 输入帧域相位残差
static float g_resamplePrevB[8] = {0.0f};          // 上一块尾帧（每声道，最多 8 声道）
static bool g_resampleHasPrevB = false;
static bool g_resampleLoggedB = false;
static void resetResamplerB() { g_resamplePhaseB = 0.0; g_resampleHasPrevB = false; for (int i = 0; i < 8; i++) g_resamplePrevB[i] = 0.0f; }
// 【Crossfade 重采样】A 槽对称重采样状态（A 槽作为 incoming 时，解码输出重采样到 stream 率）
static std::atomic<int> g_sampleRateA{0};          // A 轨 decoder 输出采样率（0=未定）
static std::vector<float> g_resampleOutA;          // A 轨重采样输出缓冲
static double g_resamplePhaseA = 0.0;
static float g_resamplePrevA[8] = {0.0f};
static bool g_resampleHasPrevA = false;
static bool g_resampleLoggedA = false;
static void resetResamplerA() { g_resamplePhaseA = 0.0; g_resampleHasPrevA = false; for (int i = 0; i < 8; i++) g_resamplePrevA[i] = 0.0f; }
// 线性插值重采样（interleaved PCM -> interleaved PCM），跨块连续（phase/prev 状态由调用方持有）
static int resampleLinearToStream(const float* in, int inSamples, int channels,
                                  int inRate, int outRate,
                                  std::vector<float>& out,
                                  double& phase, float* prev, bool& hasPrev) {
    out.clear();
    if (inSamples <= 0 || channels <= 0 || inRate <= 0 || outRate <= 0 || inRate == outRate) return 0;
    int inFrames = inSamples / channels;
    if (inFrames <= 0) return 0;
    double ratio = (double)inRate / (double)outRate;
    out.reserve((size_t)((double)inFrames / ratio) * (size_t)channels + (size_t)channels * 8);
    double pos = phase;
    while (pos < (double)inFrames) {
        int i0 = (int)pos;
        double frac = pos - (double)i0;
        for (int c = 0; c < channels; c++) {
            float s0 = (i0 < 0) ? prev[c] : ((i0 < inFrames) ? in[i0 * channels + c] : prev[c]);
            int i1 = i0 + 1;
            float s1 = (i1 < 0) ? prev[c] : ((i1 < inFrames) ? in[i1 * channels + c] : s0);
            out.push_back((float)(s0 + (s1 - s0) * frac));
        }
        pos += ratio;
    }
    phase = pos - (double)inFrames;
    for (int c = 0; c < channels; c++) prev[c] = in[(inFrames - 1) * channels + c];
    hasPrev = true;
    return (int)(out.size() / channels);
}
// 【V8.3】decoder 生命周期锁：串行化 nativeOpen/nativeOpenFd/nativeStop/nativePlay/nativeSeekTo
// 对 g_decoderExtractor/g_decoderCodec/g_decoderThread 全局单例指针的 join/delete/create 操作。
// 根因：快速切歌时「切歌后台线程」与「主线程 fallback stop 回调」并发 join 同一 std::thread、
// delete 同一 codec/extractor，导致 use-after-free(SIGSEGV) + 重复 configure/start(SIGABRT)。
// 此锁不与 g_streamMutex 反序嵌套（所有持锁顺序恒为 lifecycle → stream）。
static std::mutex g_decoderLifecycleMutex;
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
    // 【2026-09-02 fix】swap 语义：先开新 stream，成功才关旧的。
    // 原实现先 close 旧 stream —— 失败时 g_outputStream=null → Oboe 回调停 → HE-AAC SBR m4a 无声。
    // 失败保留旧 stream + 1686-1705 重采样逻辑（aRate != streamRate）自动兜底。
    oboe::ManagedStream newStream;  // unique_ptr, 先开新 stream 成功才关旧
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
                                                       g_resampleOutA, g_resamplePhaseA, g_resamplePrevA, g_resampleHasPrevA);
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
                int n = g_ringBuffer->write(srcp + written, remain);
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
                                                       g_resampleOutB, g_resamplePhaseB, g_resamplePrevB, g_resampleHasPrevB);
                if (outFrames > 0) {
                    writeSrc = g_resampleOutB.data();
                    writeSamples = outFrames * ch;
                    if (!g_resampleLoggedB) { LOGI("ALAC B: resampling %dHz -> %dHz", bRate, streamRate); g_resampleLoggedB = true; }
                }
            }
        }
        if (g_ringBufferB) {
            int written = 0, remain = writeSamples;
            const float* srcp = writeSrc;
            int retryCount = 0;
            while (remain > 0 && retryCount < 100) {
                int n = g_ringBufferB->write(srcp + written, remain);
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
                                                               g_resamplePhaseA, g_resamplePrevA, g_resampleHasPrevA);
                        if (outFrames > 0) {
                            writeSrc = g_resampleOutA.data();
                            writeSamples = outFrames * chA;
                            if (!g_resampleLoggedA) { LOGI("NDK Decoder A: resampling %dHz -> %dHz", aRate, streamRate); g_resampleLoggedA = true; }
                        }
                    }
                }
                // 【V7.39】写入 RingBuffer：等待空间而非丢数据
                if (g_ringBuffer) {
                    int written = 0;
                    int remain = writeSamples;
                    const float* src = writeSrc;
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
                        LOGW("RingBuffer write: dropped %d/%d samples after %d retries", remain, writeSamples, retryCount);
                    }
                }
                g_decoderFramesOutput.fetch_add(writeSamples / g_channelCount.load());  // 【V7.16】

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
                        if (currentStreamRate > 0 && sr != currentStreamRate) {
                            LOGI("NDK Decoder: sample rate changed %d -> %d (HE-AAC SBR?), reopening stream", currentStreamRate, sr);
                            if (g_ringBuffer) g_ringBuffer->clear();
                            reopenOutputStream(sr);
                        } else {
                            g_fileSampleRate.store(sr);
                            g_sampleRate.store(sr);
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
                // 【V8.x】支持全部 PCM 编码：4=FLOAT(4B) 2=PCM16(2B) 21=24bit packed(3B) 22=32bit int(4B)
                int bytesPerSample = (enc == 4 || enc == 22) ? 4 : (enc == 21 ? 3 : 2);
                int numSamples = (int)(info.size / bytesPerSample);

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
                                                           g_resamplePhaseB, g_resamplePrevB, g_resampleHasPrevB);
                    if (outFrames > 0) {
                        writeSrc = g_resampleOutB.data();
                        writeSamples = outFrames * chB;
                        if (!g_resampleLoggedB) { LOGI("NDK Decoder B: resampling %dHz -> %dHz", bRate, streamRate); g_resampleLoggedB = true; }
                    }
                }
                // 【V7.39】写入 RingBuffer：等待空间而非丢数据
                if (g_ringBufferB) {
                    int written = 0;
                    int remain = writeSamples;
                    const float* src2 = writeSrc;
                    int retryCount = 0;
                    while (remain > 0 && retryCount < 100) {
                        int n = g_ringBufferB->write(src2 + written, remain);
                        written += n;
                        remain -= n;
                        if (remain > 0) {
                            usleep(2000);
                            retryCount++;
                        }
                    }
                    if (remain > 0) {
                        LOGW("RingBuffer write: dropped %d/%d samples after %d retries", remain, writeSamples, retryCount);
                    }
                }
                g_decoderFramesOutputB.fetch_add(writeSamples / g_channelCount.load());  // 【V7.16】

                if (g_ringBufferB && g_ringBufferB->available() > kRingBufferCapacity * 3 / 4) {
                    usleep(5000);
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
    std::lock_guard<std::mutex> lifecycleLock(g_decoderLifecycleMutex);
    const char *path = env->GetStringUTFChars(filePath, nullptr);
    if (!path) return false;
    LOGI("OboeDirectPlayer: opening %s", path);

    // 【Crossfade】开新曲（A 轨硬加载）时复位 crossfade 状态并停 B 轨
    g_crossfadeActive.store(false);
    g_activeIsB.store(false);
    g_crossfadePos.store(0.0f);
    g_decoderStopRequestedB.store(true);
    g_decoderCvB.notify_all();
    if (g_decoderThreadB.joinable()) g_decoderThreadB.join();
    if (g_decoderCodecB) { AMediaCodec_stop(g_decoderCodecB); AMediaCodec_delete(g_decoderCodecB); g_decoderCodecB = nullptr; }
    if (g_decoderExtractorB) { AMediaExtractor_delete(g_decoderExtractorB); g_decoderExtractorB = nullptr; }
    if (g_ringBufferB) g_ringBufferB->clear();

    g_decoderStopRequested.store(true);
    g_decoderPaused.store(false);
    g_decoderCv.notify_all();
    // 【V8.3】先 join 解码线程再 stop codec。旧顺序"先 stop 再 join"会让正在
    // readSampleData 的线程的 codec input buffer 被 stop 释放 → use-after-free SIGSEGV。
    // 解码线程 dequeue 超时仅 5ms，先 join 能快速返回，且此时 codec/extractor 仍存活。
    if (g_decoderThread.joinable()) g_decoderThread.join();
    if (g_decoderCodec) {
        AMediaCodec_stop(g_decoderCodec);
    }
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
        g_compressor.setSampleRate(sr);
        g_loudness.setSampleRate((float)sr);
        g_crossfeed.setSampleRate((float)sr);
        g_dts.setSampleRate((float)sr);
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
    std::lock_guard<std::mutex> lifecycleLock(g_decoderLifecycleMutex);
    LOGI("OboeDirectPlayer: opening FD %d offset=%lld length=%lld", fd, (long long)offset, (long long)length);

    // 【Crossfade】开新曲（A 轨硬加载）时复位 crossfade 状态并停 B 轨
    g_crossfadeActive.store(false);
    g_activeIsB.store(false);
    g_crossfadePos.store(0.0f);
    g_decoderStopRequestedB.store(true);
    g_decoderCvB.notify_all();
    if (g_decoderThreadB.joinable()) g_decoderThreadB.join();
    if (g_decoderCodecB) { AMediaCodec_stop(g_decoderCodecB); AMediaCodec_delete(g_decoderCodecB); g_decoderCodecB = nullptr; }
    if (g_decoderExtractorB) { AMediaExtractor_delete(g_decoderExtractorB); g_decoderExtractorB = nullptr; }
    if (g_ringBufferB) g_ringBufferB->clear();

    g_decoderStopRequested.store(true);
    g_decoderPaused.store(false);
    g_decoderCv.notify_all();
    // 【V8.3】先 join 解码线程再 stop codec（修复 use-after-free SIGSEGV）
    if (g_decoderThread.joinable()) g_decoderThread.join();
    if (g_decoderCodec) {
        AMediaCodec_stop(g_decoderCodec);
    }
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
            // 【2026-09-02 ALAC】audio/alac：MediaCodec 无 ALAC 解码器 → ALAC 直解引擎
            if (mime && strcmp(mime, "audio/alac") == 0) {
                g_alacModeA.store(true);
                AMediaFormat_delete(format);
                LOGI("FD: ALAC detected, using AlacDirect engine");
                break;
            }
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
                if (dur > 0) g_cachedDurationUs.store(dur);  // 【Crossfade】FD open 也缓存时长，否则 getDurationMs=0 导致 monitor 永不触发
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
        g_compressor.setSampleRate(sr);
        g_loudness.setSampleRate((float)sr);
        g_crossfeed.setSampleRate((float)sr);
        g_dts.setSampleRate((float)sr);
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
    std::lock_guard<std::mutex> lifecycleLock(g_decoderLifecycleMutex);
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
    g_decoderStopRequested.store(false);
    g_decoderPaused.store(false);
    g_decoderEos.store(false);
    g_decoderRunning.store(true);
    g_decoderThreadRunning.store(true);  // 【V7.16】
    if (g_decoderThread.joinable()) g_decoderThread.join();
    if (g_alacModeA.load()) {
        g_decoderThread = std::thread(alacDecodeLoopA);
        LOGI("OboeDirectPlayer: playback started (ALAC direct)");
    } else {
        g_decoderThread = std::thread(ndkDecodeLoop);
        LOGI("OboeDirectPlayer: playback started");
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
    std::lock_guard<std::mutex> lifecycleLock(g_decoderLifecycleMutex);
    LOGI("OboeDirectPlayer: seekTo %lld us", (long long)positionUs);
    // 【Crossfade】按 active 轨分流：B 接管后 seek 的是 B 轨，否则 seek 退役 A 轨会导致进度条无效
    bool seekB = g_activeIsB.load();
    // 共享的 Oboe 输出侧状态（两轨共用同一输出流）
    g_framesWritten.store(0);
    g_playbackPositionUs.store(positionUs);  // 【V7.82】同步实际播放位置到 seek 目标，防止进度条回跳
    g_flushRequested.store(true);
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
}

JNIEXPORT void JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeStop(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lifecycleLock(g_decoderLifecycleMutex);
    LOGI("OboeDirectPlayer: stopping");
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
    if (g_decoderThreadB.joinable()) g_decoderThreadB.join();
    if (g_decoderCodecB) { AMediaCodec_stop(g_decoderCodecB); AMediaCodec_delete(g_decoderCodecB); g_decoderCodecB = nullptr; }
    if (g_decoderExtractorB) { AMediaExtractor_delete(g_decoderExtractorB); g_decoderExtractorB = nullptr; }
    if (g_ringBufferB) g_ringBufferB->clear();
    g_decoderStopRequested.store(true);
    g_decoderPaused.store(false);
    g_decoderCv.notify_all();
    // 【V8.3】先 join 解码线程再 stop codec（修复 use-after-free SIGSEGV）
    if (g_decoderThread.joinable()) g_decoderThread.join();
    if (g_decoderCodec) {
        AMediaCodec_stop(g_decoderCodec);
    }
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

// ============================================================================
// Crossfade 双轨 — incoming 打开 / 触发 / 释放（方案 B：先 mix 再走 DSP）
// ============================================================================

// 【Crossfade】incoming 打开分发：B active → 进 A 槽，否则进 B 槽
static bool openIncomingToA_internal(int fd, int64_t offset, int64_t length);
static bool openIncomingToB_internal(int fd, int64_t offset, int64_t length);

JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeOpenIncomingFd(JNIEnv *env, jobject thiz,
                                                               jint fd, jlong offset, jlong length) {
    if (g_activeIsB.load()) {
        return openIncomingToA_internal(fd, offset, length);
    }
    return openIncomingToB_internal(fd, offset, length);
}

static bool openIncomingToA_internal(int fd, int64_t offset, int64_t length) {
    std::lock_guard<std::mutex> lifecycleLock(g_decoderLifecycleMutex);
    g_decoderStopRequested.store(true);
    g_decoderCv.notify_all();
    if (g_decoderThread.joinable()) g_decoderThread.join();
    if (g_decoderCodec) { AMediaCodec_stop(g_decoderCodec); AMediaCodec_delete(g_decoderCodec); g_decoderCodec = nullptr; }
    if (g_decoderExtractor) { AMediaExtractor_delete(g_decoderExtractor); g_decoderExtractor = nullptr; }
    if (!g_ringBuffer) g_ringBuffer = new PCMRingBuffer(kRingBufferCapacity);
    else g_ringBuffer->clear();

    g_decoderExtractor = AMediaExtractor_new();
    if (!g_decoderExtractor) { LOGE("Incoming(A): extractor new failed"); return false; }
    media_status_t r = AMediaExtractor_setDataSourceFd(g_decoderExtractor, fd, offset, length);
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
            g_decoderCodec = AMediaCodec_createDecoderByType(mime);
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
            copyCsdBuffers(cfg, format);
            // 【2026-09-02 fix】A 轨与 B 轨对称：pcm-encoding=2 被部分 AAC 解码器拒绝时
            // 去掉 pcm-encoding 重试（此前 A 轨缺失兜底 → crossfade 预加载 AAC m4a 失败）
            AMediaFormat_setInt32(cfg, "pcm-encoding", 2);
            media_status_t s = AMediaCodec_configure(g_decoderCodec, cfg, nullptr, nullptr, 0);
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
                s = AMediaCodec_configure(g_decoderCodec, cfg2, nullptr, nullptr, 0);
                AMediaFormat_delete(cfg2);
                if (s != AMEDIA_OK) { LOGE("Incoming(A): configure(retry) failed %d", s); AMediaFormat_delete(cfg); AMediaFormat_delete(format); return false; }
            }
            AMediaFormat_delete(cfg);
            s = AMediaCodec_start(g_decoderCodec);
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
    LOGI("Incoming(A) decoder ready (FD)");
    return true;
}

static bool openIncomingToB_internal(int fd, int64_t offset, int64_t length) {
    std::lock_guard<std::mutex> lifecycleLock(g_decoderLifecycleMutex);
    g_decoderStopRequestedB.store(true);
    g_decoderCvB.notify_all();
    if (g_decoderThreadB.joinable()) g_decoderThreadB.join();
    if (g_decoderCodecB) { AMediaCodec_stop(g_decoderCodecB); AMediaCodec_delete(g_decoderCodecB); g_decoderCodecB = nullptr; }
    if (g_decoderExtractorB) { AMediaExtractor_delete(g_decoderExtractorB); g_decoderExtractorB = nullptr; }
    if (!g_ringBufferB) g_ringBufferB = new PCMRingBuffer(kRingBufferCapacity);
    else g_ringBufferB->clear();

    g_decoderExtractorB = AMediaExtractor_new();
    if (!g_decoderExtractorB) { LOGE("Incoming(B): extractor new failed"); return false; }
    media_status_t r = AMediaExtractor_setDataSourceFd(g_decoderExtractorB, fd, offset, length);
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
            g_decoderCodecB = AMediaCodec_createDecoderByType(mime);
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
            copyCsdBuffers(cfg, format);
            // 【2026-09-02 fix】B 轨与 A 轨对称：pcm-encoding=2 被部分 AAC 解码器拒绝时
            // 去掉 pcm-encoding 重试（A 轨 nativeOpenFd 已有 format2 兜底，B 轨此前缺失
            // → crossfade 切到 AAC m4a 时 B 轨 configure 失败 → 切歌无声/失败）
            AMediaFormat_setInt32(cfg, "pcm-encoding", 2);
            media_status_t s = AMediaCodec_configure(g_decoderCodecB, cfg, nullptr, nullptr, 0);
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
                s = AMediaCodec_configure(g_decoderCodecB, cfg2, nullptr, nullptr, 0);
                AMediaFormat_delete(cfg2);
                if (s != AMEDIA_OK) { LOGE("Incoming(B): configure(retry) failed %d", s); AMediaFormat_delete(cfg); AMediaFormat_delete(format); return false; }
            }
            AMediaFormat_delete(cfg);
            s = AMediaCodec_start(g_decoderCodecB);
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
    LOGI("Incoming(B) decoder ready (FD)");
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
    AMediaCodec* codec = nullptr;
    {
        AMediaFormat* f = AMediaExtractor_getTrackFormat(ex, trackIdx);
        const char* mime = nullptr;
        AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &mime);
        if (mime) codec = AMediaCodec_createDecoderByType(mime);
        AMediaFormat_delete(f);
    }
    if (!codec) { AMediaExtractor_delete(ex); return -1; }
    AMediaFormat* cfg = AMediaFormat_new();
    if (sr > 0) AMediaFormat_setInt32(cfg, AMEDIAFORMAT_KEY_SAMPLE_RATE, sr);
    if (ch > 0) AMediaFormat_setInt32(cfg, AMEDIAFORMAT_KEY_CHANNEL_COUNT, ch);
    AMediaFormat_setInt32(cfg, "pcm-encoding", 2);
    if (AMediaCodec_configure(codec, cfg, nullptr, nullptr, 0) != AMEDIA_OK) {
        AMediaFormat_delete(cfg); AMediaCodec_delete(codec); AMediaExtractor_delete(ex); return -1;
    }
    AMediaFormat_delete(cfg);
    if (AMediaCodec_start(codec) != AMEDIA_OK) {
        AMediaCodec_delete(codec); AMediaExtractor_delete(ex); return -1;
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
    AMediaCodec_stop(codec); AMediaCodec_delete(codec); AMediaExtractor_delete(ex);
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
    AMediaCodec* codec = nullptr;
    {
        AMediaFormat* f = AMediaExtractor_getTrackFormat(ex, trackIdx);
        const char* mime = nullptr;
        AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &mime);
        if (mime) codec = AMediaCodec_createDecoderByType(mime);
        AMediaFormat_delete(f);
    }
    if (!codec) { AMediaExtractor_delete(ex); return -1; }
    AMediaFormat* cfg = AMediaFormat_new();
    if (sr > 0) AMediaFormat_setInt32(cfg, AMEDIAFORMAT_KEY_SAMPLE_RATE, sr);
    if (ch > 0) AMediaFormat_setInt32(cfg, AMEDIAFORMAT_KEY_CHANNEL_COUNT, ch);
    AMediaFormat_setInt32(cfg, "pcm-encoding", 2);
    if (AMediaCodec_configure(codec, cfg, nullptr, nullptr, 0) != AMEDIA_OK) {
        AMediaFormat_delete(cfg); AMediaCodec_delete(codec); AMediaExtractor_delete(ex); return -1;
    }
    AMediaFormat_delete(cfg);
    if (AMediaCodec_start(codec) != AMEDIA_OK) {
        AMediaCodec_delete(codec); AMediaExtractor_delete(ex); return -1;
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
    AMediaCodec_stop(codec); AMediaCodec_delete(codec); AMediaExtractor_delete(ex);
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
static int preScanChorusMs(int fd, int64_t length, int windowMs) {
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
    AMediaCodec* codec = nullptr;
    {
        AMediaFormat* f = AMediaExtractor_getTrackFormat(ex, trackIdx);
        const char* mime = nullptr;
        AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &mime);
        if (mime) codec = AMediaCodec_createDecoderByType(mime);
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
        AMediaFormat_delete(cfg); AMediaCodec_delete(codec); AMediaExtractor_delete(ex); return -1;
    }
    AMediaFormat_delete(cfg);
    if (AMediaCodec_start(codec) != AMEDIA_OK) {
        
        AMediaCodec_delete(codec); AMediaExtractor_delete(ex); return -1;
    }

    // 扫 15s ~ 120s（跳过前奏区；歌短则扫到结尾）
    int64_t totalMs = dur / 1000;
    int64_t scanFromMs = 15000;
    int64_t scanToMs = totalMs > 120000 ? 120000 : totalMs;
    
    if (scanToMs <= scanFromMs + 5000) {  AMediaCodec_stop(codec); AMediaCodec_delete(codec); AMediaExtractor_delete(ex); return -1; }
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
    AMediaCodec_stop(codec); AMediaCodec_delete(codec); AMediaExtractor_delete(ex);
    
    if (envCount < 10) return -1;

    // 【V8.16 修正】找「第一个显著高能量段」（第一副歌），限制在 15s ~ 45% 歌长处
    // v8.15 用 60% 仍会落在后半（B 从 60% 进，播几十秒就切）；45% 保证 B 从前中段进
    const int kChorusWindows = std::max(1, 10000 / windowMs);  // 10s / 窗口
    // 只在前 45% 范围内找（跳过结尾渐弱）
    // 【V8.17】limitBucket 必须减 scanFromMs 偏移（envCount 从 15s 起计数），否则 45% 实际变成 45%+15s
    // 且对 >2.6 分钟的歌 limitBucket 会被 envCount 顶掉 → 全范围扫描 → B 轨进后半
    // 【V8.22】选位范围：min(45% 歌长, 90s) 绝对上限；短歌也不放全范围（避免进后半）
    int64_t chorusLimitMs = totalMs * 45 / 100;
    if (chorusLimitMs > 90000) chorusLimitMs = 90000;
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
        int beatWin = std::max(2, 60000 / 120 / windowMs);  // ~500ms / 窗口粒度，至少 2 窗口
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
    AMediaCodec* codec = nullptr;
    {
        AMediaFormat* f = AMediaExtractor_getTrackFormat(ex, trackIdx);
        const char* mime = nullptr;
        AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &mime);
        if (mime) codec = AMediaCodec_createDecoderByType(mime);
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
        AMediaFormat_delete(cfg); AMediaCodec_delete(codec); AMediaExtractor_delete(ex); return -1;
    }
    AMediaFormat_delete(cfg);
    if (AMediaCodec_start(codec) != AMEDIA_OK) {
        
        AMediaCodec_delete(codec); AMediaExtractor_delete(ex); return -1;
    }

    // 从 seekFromMs 扫到结尾
    int64_t totalMs = dur / 1000;
    int64_t scanFromMs = seekFromMs > 0 ? seekFromMs : 0;
    if (totalMs - scanFromMs < 20000) {  AMediaCodec_stop(codec); AMediaCodec_delete(codec); AMediaExtractor_delete(ex); return -1; }
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
    AMediaCodec_stop(codec); AMediaCodec_delete(codec); AMediaExtractor_delete(ex);
    
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
                                                             jint fd, jlong length, jint windowMs, jint mode, jlong seekFromMs) {
    int ret = -1;
    if (mode == 1) {
        ret = preScanIntroQuietMs(fd, length, windowMs > 0 ? windowMs : 100);
    } else if (mode == 2) {
        ret = preScanChorusMs(fd, length, windowMs > 0 ? windowMs : 100);
    } else if (mode == 3) {
        ret = preScanLastChorusEndMs(fd, length, windowMs > 0 ? windowMs : 100, seekFromMs);
    } else {
        ret = preScanTailQuietMs(fd, length, windowMs > 0 ? windowMs : 100, seekFromMs);
    }
    return ret;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeStartCrossfade(JNIEnv *env, jobject thiz, jint durationMs, jint bStartMs) {
    // 判断空闲槽（incoming）：B active 时新歌进 A 槽，否则进 B 槽
    bool toA = g_activeIsB.load();
    if (toA) {
        if (!g_alacModeA.load() && (!g_decoderCodec || !g_decoderExtractor)) { LOGE("StartCrossfade: incoming(A) not opened"); return false; }
        // 【Crossfade 重采样】A 槽 incoming 采样率 != stream 率时，写 ring 时重采样（A 槽已有重采样器）
    } else {
        if (!g_alacModeB.load() && (!g_decoderCodecB || !g_decoderExtractorB)) { LOGE("StartCrossfade: incoming(B) not opened"); return false; }
    }
    // 【ANR 修复·重入守卫】crossfade 进行中拒绝重复触发
    if (g_crossfadeActive.load()) { LOGE("StartCrossfade: already active, ignored"); return false; }
    g_crossfadeActive.store(true);
    int sr = g_sampleRate.load(); if (sr <= 0) sr = 44100;
    float totalFrames = (float)sr * ((float)durationMs / 1000.0f);
    if (totalFrames < 1.0f) totalFrames = 1.0f;
    g_crossfadeStep.store(1.0f / totalFrames);
    g_crossfadePos.store(0.0f);
    g_crossfadeDurationMs.store(durationMs);
    g_xfadeBStartMs.store(bStartMs > 0 ? bStartMs : 0);  // 【V8.15】B 轨起点，complete 时用于位置计算
    // 【V8.24 B 音量预匹配】B 副歌段 RMS vs A 轨当前 RMS → B 预增益（只压不抬，防 B 进场突兀）
    // gain = clamp(rmsA / rmsB, 0.6, 1.0)；B 更响才压，A 更响不放大（避免抬噪）
    {
        float rmsA = g_rmsLevel.load(std::memory_order_relaxed);
        float rmsB = g_xfadeChorusRms.load(std::memory_order_relaxed);
        float gain = 1.0f;
        if (rmsB > 0.001f && rmsA > 0.001f) {
            float g = rmsA / rmsB;
            gain = g < 0.6f ? 0.6f : (g > 1.0f ? 1.0f : g);
        }
        g_xfadeBPreGain.store(gain, std::memory_order_relaxed);
        if (gain < 0.99f) LOGI("Crossfade: B pre-gain %.2f (rmsA=%.3f rmsB=%.3f)", gain, rmsA, rmsB);
    }
    memset(g_xfadeHpfPrevB, 0, sizeof(g_xfadeHpfPrevB));  // 【方案3】重置低频防浑浊 HPF 状态
    memset(g_xfadeLpfPrevA, 0, sizeof(g_xfadeLpfPrevA));  // 【V8.23】重置 A 轨对称低切 LPF 状态
    if (toA) {
        g_decoderStopRequested.store(true);
        g_decoderCv.notify_all();
        if (g_decoderThread.joinable()) g_decoderThread.join();
        g_decoderStopRequested.store(false);
        g_decoderPaused.store(false);
        g_decoderEos.store(false);
        g_decoderRunning.store(true);
        if (g_alacModeA.load()) g_decoderThread = std::thread(alacDecodeLoopA);
        else g_decoderThread = std::thread(ndkDecodeLoop);
    } else {
        g_decoderStopRequestedB.store(true);
        g_decoderCvB.notify_all();
        if (g_decoderThreadB.joinable()) g_decoderThreadB.join();
        g_decoderStopRequestedB.store(false);
        g_decoderPausedB.store(false);
        g_decoderEosB.store(false);
        g_decoderRunningB.store(true);
        if (g_alacModeB.load()) g_decoderThreadB = std::thread(alacDecodeLoopB);
        else g_decoderThreadB = std::thread(ndkDecodeLoopB);
    }
    LOGI("Crossfade started: %dms, step=%.6f (toA=%d)", durationMs, g_crossfadeStep.load(), toA ? 1 : 0);
    return true;
}

// 【V8.15】B 轨从副歌起点播：crossfade 前 seek 空闲槽 extractor 到指定 ms
// 仅当 incoming 已 open 且 crossfade 未激活时有效
JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeSeekIncomingMs(JNIEnv *env, jobject thiz, jint ms) {
    std::lock_guard<std::mutex> lifecycleLock(g_decoderLifecycleMutex);
    if (g_crossfadeActive.load()) return false;
    bool toA = g_activeIsB.load();
    AMediaExtractor* ex = toA ? g_decoderExtractor : g_decoderExtractorB;
    if (!ex) return false;
    media_status_t s = AMediaExtractor_seekTo(ex, (int64_t)ms * 1000, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
    if (s != AMEDIA_OK) { LOGE("SeekIncoming: seekTo %dms failed %d", ms, s); return false; }
    LOGI("SeekIncoming: incoming seek to %dms (toA=%d)", ms, toA ? 1 : 0);
    return true;
}

JNIEXPORT void JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeStopIncoming(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lifecycleLock(g_decoderLifecycleMutex);
    g_crossfadeActive.store(false);
    g_decoderStopRequestedB.store(true);
    g_decoderCvB.notify_all();
    if (g_decoderThreadB.joinable()) g_decoderThreadB.join();
    if (g_decoderCodecB) { AMediaCodec_stop(g_decoderCodecB); AMediaCodec_delete(g_decoderCodecB); g_decoderCodecB = nullptr; }
    if (g_decoderExtractorB) { AMediaExtractor_delete(g_decoderExtractorB); g_decoderExtractorB = nullptr; }
    if (g_ringBufferB) g_ringBufferB->clear();
    // 【ALAC】释放 B 槽直解引擎
    g_alacModeB.store(false);
    if (g_alacB) { g_alacB->close(); g_alacB.reset(); }
    LOGI("Incoming stopped");
}

JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeIsActiveB(JNIEnv *env, jobject thiz) {
    return g_activeIsB.load();
}

JNIEXPORT void JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeReleaseInactive(JNIEnv *env, jobject thiz) {
    // 释放非活动轨：B 在播 → 释放旧 A；A 在播 → 释放 B（incoming）
    if (g_crossfadeActive.load()) { LOGE("ReleaseInactive: crossfade in progress, skip"); return; }
    std::lock_guard<std::mutex> lifecycleLock(g_decoderLifecycleMutex);
    if (g_activeIsB.load()) {
        // B active → 释放旧 A 轨（线程已 stopRequested，这里 join 收尾 + 删 codec/extractor）
        g_decoderStopRequested.store(true);
        g_decoderCv.notify_all();
        if (g_decoderThread.joinable()) g_decoderThread.join();
        if (g_decoderCodec) { AMediaCodec_stop(g_decoderCodec); AMediaCodec_delete(g_decoderCodec); g_decoderCodec = nullptr; std::this_thread::sleep_for(std::chrono::milliseconds(300)); }
        if (g_decoderExtractor) { AMediaExtractor_delete(g_decoderExtractor); g_decoderExtractor = nullptr; }
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
        if (g_decoderThreadB.joinable()) g_decoderThreadB.join();
        if (g_decoderCodecB) { AMediaCodec_stop(g_decoderCodecB); AMediaCodec_delete(g_decoderCodecB); g_decoderCodecB = nullptr; }
        if (g_decoderExtractorB) { AMediaExtractor_delete(g_decoderExtractorB); g_decoderExtractorB = nullptr; }
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
    g_msebPreGain.store(powf(10.0f, (-sumPosGain) / 20.0f));

    g_mseb10Enabled.store(true);
    // MSEB 与 5段图形 EQ 互斥，但与 AutoEQ（耳机修正）并存叠加。
    g_eq5BandEnabled.store(false);
    g_dspEqEnabled.store(true);

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

JNIEXPORT void JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeResetMsStage(JNIEnv *env, jobject thiz) {
    g_msWidth.store(1.0f);
    g_msCenter.store(1.0f);
    g_msEnabled.store(false);
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
            if (freqs[i] < 250.0f) {
                // 【V8.3 ringing 根治】低频 Peaking 切 low-shelf（无共振峰、无 ringing）。
                bands[i].L->setLowShelf(sr, freqs[i], gains[i], 0.707f);
                bands[i].R->setLowShelf(sr, freqs[i], gains[i], 0.707f);
            } else {
                bands[i].L->setPeaking(sr, freqs[i], gains[i], qs[i]);
                bands[i].R->setPeaking(sr, freqs[i], gains[i], qs[i]);
            }
        }
    }

    g_autoEqPreGain = powf(10.0f, preampDb / 20.0f);
    g_autoEqEnabled.store(true);
    g_eq5BandEnabled.store(false);  // 互斥
    g_dspEqEnabled.store(true);

    env->ReleaseFloatArrayElements(gainsDb, gains, 0);
    env->ReleaseFloatArrayElements(freqsHz, freqs, 0);
    env->ReleaseFloatArrayElements(qValues, qs, 0);
    env->ReleaseIntArrayElements(filterTypes, types, 0);

    LOGI("AutoEQ 10-band applied: preamp=%.1fdB", preampDb);
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

} // extern "C"