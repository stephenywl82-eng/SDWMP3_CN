// bpm_detector_jni.cpp — BPM 检测 JNI 入口（v8.14 全曲扫描版）
// Kotlin: com.sdw.music.player.core.audio.BpmScanner
//   external fun nativeDetectBpm(samples: FloatArray, sampleRate: Int): Int
//   external fun nativeDetectBpmFromFile(path: String): Int
//
// nativeDetectBpmFromFile 内部：
//   .flac → dr_flac 直解（取全曲，截断 180s）
//   其他  → NDKDecoder 同步解码（AMediaCodec → float，取全曲，截断 180s）
// 统一下采样 8kHz 单声道 → detectBpm（滑动窗口三算法投票）
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cmath>
#include <cstring>
#include <thread>
#include <chrono>
#include <functional>
#include "bpm_detector.h"
#include "dr_flac.h"
#include "ndk_decoder.h"

#define BPM_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "BpmJni", __VA_ARGS__)
#define BPM_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "BpmJni", __VA_ARGS__)

namespace {

constexpr int kMaxDecodeSeconds = 45;   // 最多解 45s（BPM 检测 30s 窗口滑动足够，加速扫描）
constexpr int kTargetRate = 8000;

// 收集 float PCM（交错）→ 下采样单声道 8k
struct Downsampler {
    std::vector<float> mono;
    int srcRate = 0;
    int ch = 0;

    void init(int sr, int channels) {
        srcRate = sr; ch = channels;
        mono.clear();
        mono.reserve((size_t)sr * kMaxDecodeSeconds);
    }
    void feed(const float* data, int numSamples) {
        if (ch <= 0 || srcRate <= 0) return;
        int frames = numSamples / ch;
        for (int f = 0; f < frames; f++) {
            float acc = 0.f;
            for (int c = 0; c < ch; c++) acc += data[f * ch + c];
            mono.push_back(acc / (float)ch);
        }
        // 超长截断（全曲限 180s）
        int limit = srcRate * kMaxDecodeSeconds;
        if ((int)mono.size() > limit) mono.resize(limit);
    }
    // 线性下采样到 8k
    int downsample(std::vector<float>& out) const {
        if (srcRate <= 0 || mono.empty()) return 0;
        int targetN = (int)((int64_t)mono.size() * kTargetRate / srcRate);
        if (targetN < 1) return 0;
        out.resize(targetN);
        for (int i = 0; i < targetN; i++) {
            float pos = (float)i * srcRate / kTargetRate;
            int idx = (int)pos;
            if (idx + 1 < (int)mono.size()) {
                float frac = pos - idx;
                out[i] = mono[idx] * (1.f - frac) + mono[idx + 1] * frac;
            } else {
                out[i] = mono[idx];
            }
        }
        return targetN;
    }
};

int detectFromFileFd(const char* path, int fd, int64_t offset, int64_t length);

int detectFromFile(const char* path) {
    return detectFromFileFd(path, -1, 0, 0);
}

int detectFromFileFd(const char* path, int fd, int64_t offset, int64_t length) {
    Downsampler ds;
    bool isFlac = (path != nullptr) && ((strstr(path, ".flac") != nullptr) || (strstr(path, ".FLAC") != nullptr));

    if (isFlac) {
        drflac* flac = drflac_open_file(path, nullptr);
        if (!flac) { BPM_LOGW("FLAC open failed: %s", path); return -1; }
        int sr = (int)flac->sampleRate;
        int chs = (int)flac->channels;
        ds.init(sr, chs);
        const int kChunk = 4096;
        std::vector<float> buf(kChunk * chs);
        drflac_uint64 totalToRead = (drflac_uint64)sr * kMaxDecodeSeconds;
        drflac_uint64 read = 0;
        while (read < totalToRead) {
            drflac_uint64 want = kChunk;
            if (want > totalToRead - read) want = totalToRead - read;
            drflac_uint64 got = drflac_read_pcm_frames_f32(flac, want, buf.data());
            if (got == 0) break;
            ds.feed(buf.data(), (int)(got * chs));
            read += got;
        }
        drflac_close(flac);
    } else {
        NDKDecoder dec;
        bool ok = false;
        if (fd >= 0) {
            ok = dec.openFd(fd, offset, length);
        } else if (path) {
            ok = dec.open(path);
        }
        if (!ok) { BPM_LOGW("NDK open failed: %s (fd=%d)", path ? path : "", fd); return -1; }
        auto info = dec.getInfo();
        if (info.sampleRate <= 0) { BPM_LOGW("NDK no sampleRate: %s", path); return -1; }
        ds.init(info.sampleRate, info.channelCount);
        bool enough = false;
        dec.startDecode([&](const float* data, int numSamples) {
            ds.feed(data, numSamples);
            if (ds.mono.size() >= (size_t)info.sampleRate * kMaxDecodeSeconds) {
                enough = true;
            }
        });
        int waited = 0;
        while (dec.isDecoding() && waited < 60000) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            waited += 50;
            if (enough) break;
        }
        dec.stop();
        if (waited >= 60000) BPM_LOGW("NDK decode timeout: %s (mono=%zu)", path, ds.mono.size());
        else if (ds.mono.size() < (size_t)info.sampleRate * 4) BPM_LOGW("NDK too little data: %s (mono=%zu sr=%d)", path, ds.mono.size(), info.sampleRate);
    }

    if (ds.mono.empty()) { BPM_LOGW("no pcm: %s", path); return -1; }
    std::vector<float> out;
    int n = ds.downsample(out);
    if (n < kTargetRate * 4) { BPM_LOGW("too short: %s n=%d", path, n); return -1; }  // 至少 4 秒
    int bpm = bpmdet::detectBpm(out.data(), n);
    if (bpm <= 0) BPM_LOGW("no beat: %s (decoded %.1fs)", path, (double)ds.mono.size() / ds.srcRate);
    else BPM_LOGI("bpm=%d %s", bpm, path);
    return bpm;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_sdw_music_player_core_audio_BpmScanner_nativeDetectBpm(
        JNIEnv* env, jclass clazz, jfloatArray jSamples, jint sampleRate) {
    if (!jSamples || sampleRate <= 0) return -1;
    jsize n = env->GetArrayLength(jSamples);
    if (n < sampleRate * 4) return -1;
    jfloat* buf = env->GetFloatArrayElements(jSamples, nullptr);
    if (!buf) return -1;
    // 注意：调用方传的是任意采样率，需要先下采样到 8k
    // 简单处理：直接用（调用方通常传 8k）；否则做线性下采样
    int bpm = -1;
    if (sampleRate == kTargetRate) {
        bpm = bpmdet::detectBpm(buf, (int)n);
    } else {
        // 下采样到 8k
        int targetN = (int)((int64_t)n * kTargetRate / sampleRate);
        if (targetN >= kTargetRate * 4) {
            std::vector<float> resampled(targetN);
            for (int i = 0; i < targetN; i++) {
                float pos = (float)i * sampleRate / kTargetRate;
                int idx = (int)pos;
                if (idx + 1 < n) {
                    float frac = pos - idx;
                    resampled[i] = buf[idx] * (1.f - frac) + buf[idx + 1] * frac;
                } else {
                    resampled[i] = buf[idx];
                }
            }
            bpm = bpmdet::detectBpm(resampled.data(), targetN);
        }
    }
    env->ReleaseFloatArrayElements(jSamples, buf, JNI_ABORT);
    return bpm;
}

JNIEXPORT jint JNICALL
Java_com_sdw_music_player_core_audio_BpmScanner_nativeDetectBpmFromFd(
        JNIEnv* env, jclass clazz, jstring jPath, jint fd, jlong offset, jlong length) {
    const char* path = nullptr;
    if (jPath) path = env->GetStringUTFChars(jPath, nullptr);
    int bpm = detectFromFileFd(path, (int)fd, (int64_t)offset, (int64_t)length);
    if (jPath) env->ReleaseStringUTFChars(jPath, path);
    return bpm;
}

JNIEXPORT jint JNICALL
Java_com_sdw_music_player_core_audio_BpmScanner_nativeDetectBpmFromFile(
        JNIEnv* env, jclass clazz, jstring jPath) {
    if (!jPath) return -1;
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    if (!path) return -1;
    int bpm = detectFromFile(path);
    env->ReleaseStringUTFChars(jPath, path);
    return bpm;
}

} // extern "C"
