// bpm_detector_jni.cpp — BPM 检测 JNI 入口（v8.157 精度重写版）
// Kotlin: com.sdw.music.player.core.audio.BpmScanner
//   external fun nativeDetectBpm(samples: FloatArray, sampleRate: Int): Int
//   external fun nativeDetectBpmFromFile(path: String): Int
//   external fun nativeDetectBpmFromFd(path: String?, fd: Int, offset: Long, length: Long): Int
//   external fun nativeDetectBpmFromFdEx(...): IntArray   // [bpm, firstBeatMs, confPct]
//   external fun nativeDetectBpmFromFileEx(path): IntArray
//
// nativeDetectBpmFromFd 内部：
//   .flac → dr_flac 直解（取全曲，截断 90s）
//   其他  → NDKDecoder 同步解码（AMediaCodec → float，取全曲，截断 90s）
// 抗混叠：降采样 8kHz 前先 2 阶 Butterworth 低通(3.4kHz)，避免 >4k 混叠折回低频污染 kick/bass
// 统一下采样 8kHz 单声道 → bpmdet::detectBpmEx（多频段 novelty + 亚帧 tempo）
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
#include "loudness_meter.h"
#include "dr_flac.h"
#include "ndk_decoder.h"

#define BPM_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "BpmJni", __VA_ARGS__)
#define BPM_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "BpmJni", __VA_ARGS__)

namespace {

// 【P2-2】把 BpmResult 打包成 int[]：
//   [0]=bpm  [1]=firstBeatMs  [2]=confPct  [3]=gridSegCount  [4..]=每段{bpm, firstBeatMs, startMs}
//   【v8.166】网格之后追加 [w]=chorusMs  [w+1]=keyCode（Camelot：小调 1..12 / 大调 101..112 / -1 未知）
// 【V8.304】再追加 [x]=bpmMilli（浮点 BPM × 1000，0 = 无/未算）。
//   整数 BPM 量化步进 1 在 120 附近 = ±0.83% 速度误差，交叉淡化对拍必须用浮点值。
jintArray makeResultArray(JNIEnv* env, const bpmdet::BpmResult& r, int bpm, int chorusMs, int keyCode,
                          int lufs100 = 0, int peak1000 = 0) {
    const int nseg = (int)r.grid.size();
    jint vals[5 + 3 * 32 + 2 + 2];
    vals[0] = (jint)bpm;
    vals[1] = (jint)r.firstBeatMs;
    vals[2] = (jint)std::lround(r.conf * 1000.f);
    vals[3] = (jint)nseg;
    int w = 4;
    for (int i = 0; i < nseg && i < 32; i++) {
        vals[w++] = (jint)r.grid[i].bpm;
        vals[w++] = (jint)r.grid[i].firstBeatMs;
        vals[w++] = (jint)r.grid[i].startMs;
    }
    vals[w++] = (jint)chorusMs;
    vals[w++] = (jint)keyCode;
    // 【V8.233】尾部追加响度：lufs*100（如 -1234 = -12.34 LUFS）、peak*1000。
    // 0 = 本次没算出（Kotlin 侧按未知处理，不写库）。
    vals[w++] = (jint)lufs100;
    vals[w++] = (jint)peak1000;
    // 【V8.304】浮点 BPM ×1000。bpmF<=0（异常）时写 0，Kotlin 侧回退用整数 bpm。
    vals[w++] = (r.bpmF > 0.f) ? (jint)std::lround(r.bpmF * 1000.f) : 0;
    jintArray arr = env->NewIntArray(w);
    if (arr) env->SetIntArrayRegion(arr, 0, w, vals);
    return arr;
}

constexpr int kMaxDecodeSeconds = 90;   // 【v8.157】45→90s：滑窗投票数更多，提升稳定性
constexpr int kTargetRate = 8000;

// 收集 float PCM（交错）→ 单声道同源采样率；降采样时做抗混叠低通
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
        int limit = srcRate * kMaxDecodeSeconds;
        if ((int)mono.size() > limit) mono.resize(limit);
    }
    // 【v8.157】抗混叠低通 + 线性下采样到 8k
    int downsample(std::vector<float>& out) const {
        if (srcRate <= 0 || mono.empty()) return 0;
        int targetN = (int)((int64_t)mono.size() * kTargetRate / srcRate);
        if (targetN < 1) return 0;

        std::vector<float> filtered;
        const float* src = mono.data();
        if (srcRate > kTargetRate) {
            // 2 阶 Butterworth 低通 @3.4kHz（< 4k 奈奎斯特，留过渡带）
            bpmdet::BiquadLP lp;
            lp.setup((float)srcRate, 3400.f, 0.70710678f);
            filtered.resize(mono.size());
            for (size_t i = 0; i < mono.size(); i++) filtered[i] = lp.process(mono[i]);
            src = filtered.data();
        }
        out.resize(targetN);
        for (int i = 0; i < targetN; i++) {
            float pos = (float)i * srcRate / kTargetRate;
            int idx = (int)pos;
            if (idx + 1 < (int)mono.size()) {
                float frac = pos - idx;
                out[i] = src[idx] * (1.f - frac) + src[idx + 1] * frac;
            } else {
                out[i] = src[idx];
            }
        }
        return targetN;
    }
};

// ================================================================
// 【v8.166】调性检测：Goertzel 色度向量 + Krumhansl-Schmuckler 轮廓相关
//   输入 8k 单声道，取 5s~45s（跳过前奏，避开纯鼓段）；返回 Camelot 编码：
//   小调 = 1..12，大调 = 101..112（+100），失败 -1
// ================================================================
static float goertzelMag(const float* x, int n, int sr, float freq) {
    const float w = 6.2831853f * freq / (float)sr;
    const float coeff = 2.f * std::cos(w);
    float s1 = 0.f, s2 = 0.f;
    for (int i = 0; i < n; i++) { float s = x[i] + coeff * s1 - s2; s2 = s1; s1 = s; }
    float p = s1 * s1 + s2 * s2 - coeff * s1 * s2;
    return (p > 0.f) ? (std::sqrt(p) / (float)n) : 0.f;
}

static int detectKeyCode8k(const float* x, int n) {
    if (!x || n < 8000 * 6) return -1;
    const int sr = kTargetRate;                 // 8000
    int from = 5 * sr;                          // 跳过前 5s
    int len = 40 * sr;
    if (from + len > n) len = n - from;
    if (len < sr * 6) { from = 0; len = std::min(n, sr * 40); }
    if (len < sr * 6) return -1;
    const float* p = x + from;
    // MIDI 48(C3) .. 79(B5)：12 音级 × 近 3 个八度
    float chroma[12];
    for (int c = 0; c < 12; c++) {
        float acc = 0.f;
        for (int midi = 48 + c; midi <= 79; midi += 12) {
            float f = 440.f * std::pow(2.f, (midi - 69) / 12.f);
            if (f > sr * 0.45f) continue;       // 超奈奎斯特跳过
            float m = goertzelMag(p, len, sr, f);
            acc += m * m;
        }
        chroma[c] = acc;
    }
    float mx = 0.f;
    for (int c = 0; c < 12; c++) mx = std::max(mx, chroma[c]);
    if (mx <= 1e-12f) return -1;
    for (int c = 0; c < 12; c++) chroma[c] /= mx;

    // Krumhansl-Schmuckler 轮廓
    static const float kMajor[12] = {6.35f,2.23f,3.48f,2.33f,4.38f,4.09f,2.52f,5.19f,2.39f,3.66f,2.29f,2.88f};
    static const float kMinor[12] = {6.33f,2.68f,3.52f,5.38f,2.60f,3.53f,2.54f,4.75f,3.98f,2.69f,3.34f,3.17f};
    auto corr = [&](const float* prof, int shift) {
        float a = 0.f, b = 0.f;
        for (int i = 0; i < 12; i++) { a += chroma[(i + shift) % 12]; b += prof[i]; }
        a /= 12.f; b /= 12.f;
        float num = 0.f, da = 0.f, db = 0.f;
        for (int i = 0; i < 12; i++) {
            float u = chroma[(i + shift) % 12] - a, v = prof[i] - b;
            num += u * v; da += u * u; db += v * v;
        }
        return (da > 1e-9f && db > 1e-9f) ? (num / std::sqrt(da * db)) : 0.f;
    };
    float bestScore = -2.f; int bestShift = 0; bool bestMinor = false;
    for (int s = 0; s < 12; s++) {
        float cm = corr(kMajor, s);
        if (cm > bestScore) { bestScore = cm; bestShift = s; bestMinor = false; }
        float cn = corr(kMinor, s);
        if (cn > bestScore) { bestScore = cn; bestShift = s; bestMinor = true; }
    }
    if (bestScore <= 0.f) return -1;
    // 与 Java AudioAnalyzer.toCamelot 一致的映射（索引 = 半音，0=C）
    static const int kMajorCamelot[12] = {8,3,10,5,12,7,2,9,4,11,6,1};
    static const int kMinorCamelot[12] = {5,12,7,2,9,4,11,6,1,8,3,10};
    int number = bestMinor ? kMinorCamelot[bestShift] : kMajorCamelot[bestShift];
    return bestMinor ? number : (number + 100);
}

// ================================================================
// 【v8.166】副歌起点：100ms 能量包络上的「首个持续跃升点」
//   与 oboe_bridge preScanChorusMs 同一判据，但在扫描阶段用已解码的 PCM 直接算，
//   结果入缓存 → 切歌时不必再实时解码预扫 15~16s（那正是拍点对齐被破坏的原因）
// ================================================================
static int detectChorusFromMono(const std::vector<float>& mono, int sr) {
    if (sr <= 0 || mono.empty()) return -1;
    const int win = std::max(1, sr / 10);           // 100ms
    int count = (int)mono.size() / win;
    if (count < 20) return -1;
    std::vector<float> env(count, 0.f);
    for (int i = 0; i < count; i++) {
        double s = 0.0; int st = i * win;
        for (int j = 0; j < win; j++) { float v = mono[st + j]; s += (double)v * v; }
        env[i] = (float)std::sqrt(s / win);
    }
    std::vector<float> avg(count, 0.f);
    for (int i = 0; i < count; i++) {
        float s = 0.f; int c = 0;
        for (int j = std::max(0, i - 1); j <= std::min(count - 1, i + 1); j++) { s += env[j]; c++; }
        avg[i] = s / c;
    }
    int from = std::min(count - 2, 150);            // 15s 起
    int limit = std::min(count, 900);               // 至多 90s（解码上限）
    if (limit - from < 12) return -1;
    for (int i = from + 1; i < limit; i++) {
        if (avg[i] > avg[i - 1] * 1.25f) {
            bool sustained = true;
            for (int j = 1; j <= 2 && i + j < limit; j++) {
                if (avg[i + j] < avg[i - 1] * 1.05f) { sustained = false; break; }
            }
            if (sustained) return i * 100;
        }
    }
    // 无跃升 → 取该范围内能量最高窗口
    float best = -1.f; int bestI = -1;
    for (int i = from; i < limit; i++) if (avg[i] > best) { best = avg[i]; bestI = i; }
    return (bestI >= 0) ? bestI * 100 : -1;
}

int detectFromFileFd(const char* path, int fd, int64_t offset, int64_t length, bpmdet::BpmResult& out,
                     int* chorusMsOut = nullptr, int* keyCodeOut = nullptr,
                     Bs1770Meter* meterOut = nullptr) {
    Downsampler ds;
    bool isFlac = (path != nullptr) && ((strstr(path, ".flac") != nullptr) || (strstr(path, ".FLAC") != nullptr));

    if (isFlac) {
        drflac* flac = drflac_open_file(path, nullptr);
        if (!flac) { BPM_LOGW("FLAC open failed: %s", path); return -1; }
        int sr = (int)flac->sampleRate;
        int chs = (int)flac->channels;
        ds.init(sr, chs);
        if (meterOut) meterOut->setup(sr, chs);
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
            if (meterOut) meterOut->feed(buf.data(), (int)(got * chs));
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
        if (meterOut) meterOut->setup(info.sampleRate, info.channelCount);
        bool enough = false;
        dec.startDecode([&](const float* data, int numSamples) {
            ds.feed(data, numSamples);
            if (meterOut) meterOut->feed(data, numSamples);
            if (ds.mono.size() >= (size_t)info.sampleRate * kMaxDecodeSeconds) enough = true;
        });
        int waited = 0;
        while (dec.isDecoding() && waited < 90000) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            waited += 50;
            if (enough) break;
        }
        dec.stop();
        if (waited >= 90000) BPM_LOGW("NDK decode timeout: %s (mono=%zu)", path, ds.mono.size());
        else if (ds.mono.size() < (size_t)info.sampleRate * 4) BPM_LOGW("NDK too little data: %s (mono=%zu sr=%d)", path, ds.mono.size(), info.sampleRate);
    }

    if (ds.mono.empty()) { BPM_LOGW("no pcm: %s", path); return -1; }
    std::vector<float> outS;
    int n = ds.downsample(outS);
    if (n < kTargetRate * 4) { BPM_LOGW("too short: %s n=%d", path, n); return -1; }
    // 【v8.166】同一次解码顺带算「副歌起点」与「调性」，入缓存后切歌无需实时预扫
    if (chorusMsOut) *chorusMsOut = detectChorusFromMono(ds.mono, ds.srcRate);
    if (keyCodeOut)  *keyCodeOut  = detectKeyCode8k(outS.data(), n);
    int bpm = bpmdet::detectBpmEx(outS.data(), n, out);
    if (bpm <= 0) BPM_LOGW("no beat: %s (decoded %.1fs)", path, (double)ds.mono.size() / ds.srcRate);
    else BPM_LOGI("bpm=%d (f=%.3f) firstBeat=%dms conf=%.3f %s", bpm, out.bpmF, out.firstBeatMs, out.conf, path);
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
    int bpm = -1;
    bpmdet::BpmResult r;
    if (sampleRate == kTargetRate) {
        bpm = bpmdet::detectBpmEx(buf, (int)n, r);
    } else {
        int targetN = (int)((int64_t)n * kTargetRate / sampleRate);
        if (targetN >= kTargetRate * 4) {
            std::vector<float> resampled(targetN);
            bpmdet::BiquadLP lp;
            lp.setup((float)sampleRate, 3400.f, 0.70710678f);
            for (int i = 0; i < targetN; i++) {
                float pos = (float)i * sampleRate / kTargetRate;
                int idx = (int)pos;
                float a = buf[idx];
                float b = (idx + 1 < n) ? buf[idx + 1] : buf[idx];
                float frac = pos - idx;
                float v = a * (1.f - frac) + b * frac;
                resampled[i] = lp.process(v);
            }
            bpm = bpmdet::detectBpmEx(resampled.data(), targetN, r);
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
    bpmdet::BpmResult r;
    int bpm = detectFromFileFd(path, (int)fd, (int64_t)offset, (int64_t)length, r);
    if (jPath) env->ReleaseStringUTFChars(jPath, path);
    return bpm;
}

JNIEXPORT jint JNICALL
Java_com_sdw_music_player_core_audio_BpmScanner_nativeDetectBpmFromFile(
        JNIEnv* env, jclass clazz, jstring jPath) {
    if (!jPath) return -1;
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    if (!path) return -1;
    bpmdet::BpmResult r;
    int bpm = detectFromFileFd(path, -1, 0, 0, r);
    env->ReleaseStringUTFChars(jPath, path);
    return bpm;
}

// 【v8.157】扩展版：返回 int[]{bpm, firstBeatMs, confPct}
JNIEXPORT jintArray JNICALL
Java_com_sdw_music_player_core_audio_BpmScanner_nativeDetectBpmFromFdEx(
        JNIEnv* env, jclass clazz, jstring jPath, jint fd, jlong offset, jlong length) {
    const char* path = nullptr;
    if (jPath) path = env->GetStringUTFChars(jPath, nullptr);
    bpmdet::BpmResult r;
    int chorusMs = -1, keyCode = -1;
    // 【V8.233】同一次解码顺便测响度：不额外解码，成本几乎为零
    Bs1770Meter meter;
    int bpm = detectFromFileFd(path, (int)fd, (int64_t)offset, (int64_t)length, r, &chorusMs, &keyCode, &meter);
    if (jPath) env->ReleaseStringUTFChars(jPath, path);
    int lufs100 = 0, peak1000 = 0;
    if (meter.isValid()) {
        float lu = meter.loudnessLufs();
        if (lu < -60.f || lu > 5.f) lu = 0.f;          // 异常/静音 → 视为未知
        lufs100 = (int)std::lround(lu * 100.f);
        peak1000 = (int)std::lround(meter.peakLinear() * 1000.f);
    }
    return makeResultArray(env, r, bpm, chorusMs, keyCode, lufs100, peak1000);
}

JNIEXPORT jintArray JNICALL
Java_com_sdw_music_player_core_audio_BpmScanner_nativeDetectBpmFromFileEx(
        JNIEnv* env, jclass clazz, jstring jPath) {
    if (!jPath) return nullptr;
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    if (!path) return nullptr;
    bpmdet::BpmResult r;
    int chorusMs = -1, keyCode = -1;
    int bpm = detectFromFileFd(path, -1, 0, 0, r, &chorusMs, &keyCode);
    env->ReleaseStringUTFChars(jPath, path);
    return makeResultArray(env, r, bpm, chorusMs, keyCode);
}

} // extern "C"
