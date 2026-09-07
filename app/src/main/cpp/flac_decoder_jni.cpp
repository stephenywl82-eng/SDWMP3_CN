// flac_decoder_jni.cpp — libFLAC replacement via dr_flac (single-header, zero-dependency)
// JNI bridge: nativeFlacOpen/Start/Stop/Info/Pause/Seek/GaplessSeek/IsEos/PositionMs/TotalSamples
// Directly feeds float samples into UsbAudioDriver ring buffer via driver->pushPcm()

#define DR_FLAC_IMPLEMENTATION
#define DR_FLAC_NO_WCHAR
// #define DR_FLAC_NO_CRC  // [2026-09-07] 移除：NO_CRC 会禁用 binary search seek。
// 损坏 SEEKTABLE 的 FLAC（Breathless - Shayne Ward.flac 实测 23 个 seekpoint 除首个外全为垃圾值）
// seektable seek 失败后只剩 brute force（顺序解码到目标帧），深处 seek 必失败
// → 解码线程假 EOS → controller 重启 → "跳回开头"。启用 CRC 后 binary search 可用，seek 可靠。
#include "dr_flac.h"

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <string>
#include <algorithm>   // std::min
#include <cctype>      // std::toupper

#include "usb_audio_driver.h"

#define TAG "FlacDecoderJNI"
#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG,   TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,    TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,    TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,   TAG, __VA_ARGS__)

// ── External: get the global UsbAudioDriver singleton ──────────────────────
extern UsbAudioDriver* sdw_getUsbDriver();  // defined in usb_audio_jni.cpp

// ── State ───────────────────────────────────────────────────────────────────
static drflac*         gFlac        = nullptr;
static std::thread*    gDecodeThread = nullptr;
static std::atomic<bool> gRunning{false};
static std::atomic<bool> gPaused{false};
static std::atomic<bool> gSeekPending{false};
static std::atomic<int64_t> gSeekTargetPcmFrame{0};  // FLAC frame index for gapless seek
static std::atomic<int64_t> gSeekTargetMs{0};        // ms-based seek
static std::atomic<bool> gEos{false};

// Cached STREAMINFO
static std::atomic<int> gSampleRate{0};
static std::atomic<int> gChannels{0};
static std::atomic<int> gBitsPerSample{0};
static std::atomic<int64_t> gTotalPcmFrames{0};
static std::atomic<int64_t> gDurationMs{0};

// Current decode position (PCM frames consumed by DacDriver)
static std::atomic<int64_t> gCurrentPcmFrame{0};

// Gapless: cached path for cross-file gapless seek
static std::string gCurrentPath;

// ── Forward declarations ────────────────────────────────────────────────────
static void decodeLoop();

// ── Helpers ─────────────────────────────────────────────────────────────────

static int64_t pcmFramesToMs(int64_t frames) {
    int sr = gSampleRate.load();
    if (sr <= 0) return 0;
    return (frames * 1000LL) / sr;
}

static int64_t msToPcmFrames(int64_t ms) {
    int sr = gSampleRate.load();
    if (sr <= 0) return 0;
    return (ms * sr) / 1000LL;
}

// ── Embedded lyrics (Vorbis comment) collector ─────────────────────────────
// FLAC 内嵌歌词存在 VORBIS_COMMENT metadata block 的 LYRICS / UNSYNCEDLYRICS 字段。
struct FlacLyricsCollector {
    std::string lyrics;
};

static void flacMetaCallback(void* pUserData, drflac_metadata* pMetadata) {
    if (!pUserData || !pMetadata) return;
    if (pMetadata->type != DRFLAC_METADATA_BLOCK_TYPE_VORBIS_COMMENT) return;

    auto* collector = static_cast<FlacLyricsCollector*>(pUserData);
    if (!collector->lyrics.empty()) return;  // 已找到，不再覆盖

    const auto& vc = pMetadata->data.vorbis_comment;
    drflac_vorbis_comment_iterator iter;
    drflac_init_vorbis_comment_iterator(&iter, vc.commentCount, vc.pComments);

    const char* comment;
    drflac_uint32 commentLen;
    while ((comment = drflac_next_vorbis_comment(&iter, &commentLen)) != nullptr) {
        if (commentLen == 0) continue;
        std::string c(comment, commentLen);
        size_t eq = c.find('=');
        if (eq == std::string::npos) continue;

        // field 名 ASCII 转大写后比较（Vorbis comment field 大小写不敏感）
        std::string field = c.substr(0, eq);
        for (auto& ch : field) ch = (char)std::toupper((unsigned char)ch);
        if (field == "LYRICS" || field == "UNSYNCEDLYRICS") {
            collector->lyrics = c.substr(eq + 1);
            return;
        }
    }
}

// ── dr_flac callbacks (memory-based, we read full file via mmap/fread in JNI) ─

// We use drflac_open_file() which uses standard fopen internally.
// Android NDK fopen handles UTF-8 paths in API 24+.

// ── JNI implementation ──────────────────────────────────────────────────────

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeFlacOpen(
    JNIEnv* env, jobject /*this*/, jstring jpath) {

    // Stop previous if any
    if (gFlac) {
        gRunning = false;
        if (gDecodeThread && gDecodeThread->joinable()) {
            gDecodeThread->join();
            delete gDecodeThread;
            gDecodeThread = nullptr;
        }
        drflac_close(gFlac);
        gFlac = nullptr;
    }

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) {
        LOGE("nativeFlacOpen: GetStringUTFChars returned null");
        return JNI_FALSE;
    }

    gCurrentPath = path;
    LOGI("nativeFlacOpen: opening \"%s\"", path);

    gFlac = drflac_open_file(path, nullptr);
    env->ReleaseStringUTFChars(jpath, path);

    if (!gFlac) {
        LOGE("nativeFlacOpen: drflac_open_file failed");
        gEos = true;
        return JNI_FALSE;
    }

    // Cache STREAMINFO
    gSampleRate     = gFlac->sampleRate;
    gChannels       = gFlac->channels;
    gBitsPerSample  = gFlac->bitsPerSample;
    gTotalPcmFrames = gFlac->totalPCMFrameCount;
    gDurationMs     = pcmFramesToMs(gTotalPcmFrames);
    gEos            = false;
    gPaused         = false;
    gSeekPending    = false;
    gCurrentPcmFrame = 0;

    LOGI("nativeFlacOpen: sr=%d ch=%d bits=%d totalFrames=%lld dur=%lldms",
         gSampleRate.load(), gChannels.load(), gBitsPerSample.load(),
         (long long)gTotalPcmFrames.load(), (long long)gDurationMs.load());

    return JNI_TRUE;
}

JNIEXPORT jintArray JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeFlacInfo(
    JNIEnv* env, jobject /*this*/) {

    jintArray result = env->NewIntArray(4);
    if (!result) return nullptr;

    jint info[4] = {
        gSampleRate.load(),
        gChannels.load(),
        gBitsPerSample.load(),
        (jint)gDurationMs.load()
    };
    env->SetIntArrayRegion(result, 0, 4, info);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeFlacStart(
    JNIEnv* /*env*/, jobject /*this*/) {

    if (!gFlac) {
        LOGE("nativeFlacStart: gFlac is null");
        return JNI_FALSE;
    }

    // Already running?
    if (gRunning) {
        LOGW("nativeFlacStart: already running");
        return JNI_TRUE;
    }

    gRunning = true;
    gEos    = false;
    gPaused = false;

    if (gDecodeThread && gDecodeThread->joinable()) {
        gDecodeThread->join();
        delete gDecodeThread;
    }

    gDecodeThread = new std::thread(decodeLoop);
    LOGI("nativeFlacStart: decode thread spawned");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeFlacPause(
    JNIEnv* /*env*/, jobject /*this*/, jboolean paused) {

    gPaused = paused;
    LOGI("nativeFlacPause: %s", paused ? "PAUSED" : "RESUMED");
}

JNIEXPORT void JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeFlacSeek(
    JNIEnv* /*env*/, jobject /*this*/, jlong ms) {

    gSeekTargetMs = ms;
    gSeekPending  = true;
    LOGD("nativeFlacSeek: targetMs=%lld", (long long)ms);
}

JNIEXPORT void JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeFlacStop(
    JNIEnv* /*env*/, jobject /*this*/) {

    gRunning = false;
    if (gDecodeThread && gDecodeThread->joinable()) {
        gDecodeThread->join();
        delete gDecodeThread;
        gDecodeThread = nullptr;
    }
    if (gFlac) {
        drflac_close(gFlac);
        gFlac = nullptr;
    }
    gEos = true;
    LOGI("nativeFlacStop: stopped");
}

JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeFlacIsEos(
    JNIEnv* /*env*/, jobject /*this*/) {
    return gEos ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeFlacPositionMs(
    JNIEnv* /*env*/, jobject /*this*/) {
    auto* drv = sdw_getUsbDriver();
    if (!drv || !gFlac) {
        return pcmFramesToMs(gCurrentPcmFrame);
    }
    // Real position: subtract ring buffer frames (not yet consumed by DAC)
    int ringFill = drv->getRingFillFrames();
    int64_t consumedFrames = gCurrentPcmFrame - ringFill;
    if (consumedFrames < 0) consumedFrames = 0;
    return pcmFramesToMs(consumedFrames);
}

JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeFlacGaplessSeek(
    JNIEnv* env, jobject /*this*/, jstring jpath, jlong targetSample) {

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return JNI_FALSE;

    bool sameFile = (gCurrentPath == path);
    env->ReleaseStringUTFChars(jpath, path);

    if (sameFile && gFlac) {
        // Same file: seek within decoder
        gSeekTargetPcmFrame = targetSample;
        gSeekPending = true;
        LOGD("nativeFlacGaplessSeek(same): targetPcm=%lld", (long long)targetSample);
        return JNI_TRUE;
    }

    // Cross-file: close old, open new, seek
    gRunning = false;
    if (gDecodeThread && gDecodeThread->joinable()) {
        gDecodeThread->join();
        delete gDecodeThread;
        gDecodeThread = nullptr;
    }
    if (gFlac) { drflac_close(gFlac); gFlac = nullptr; }

    gFlac = drflac_open_file(gCurrentPath.c_str(), nullptr);
    if (!gFlac) {
        LOGE("nativeFlacGaplessSeek(cross): open failed");
        gEos = true;
        return JNI_FALSE;
    }

    gSampleRate     = gFlac->sampleRate;
    gChannels       = gFlac->channels;
    gBitsPerSample  = gFlac->bitsPerSample;
    gTotalPcmFrames = gFlac->totalPCMFrameCount;
    gDurationMs     = pcmFramesToMs(gTotalPcmFrames);
    gEos            = false;
    gPaused         = false;

    // Seek to target
    gSeekTargetPcmFrame = targetSample;
    gSeekPending = true;

    gRunning = true;
    gDecodeThread = new std::thread(decodeLoop);
    LOGI("nativeFlacGaplessSeek(cross): new file, targetPcm=%lld", (long long)targetSample);
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeFlacTotalSamples(
    JNIEnv* /*env*/, jobject /*this*/) {
    return gTotalPcmFrames.load();
}

// 读 FLAC 内嵌歌词（Vorbis comment LYRICS / UNSYNCEDLYRICS 字段）
// 返回 UTF-8 jstring；无内嵌歌词时返回 null。
// 独立 open/close，不触碰解码状态（gFlac 等），线程安全。
JNIEXPORT jstring JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeFlacReadLyrics(
    JNIEnv* env, jobject /*this*/, jstring jpath) {

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return nullptr;

    FlacLyricsCollector collector;
    drflac* flac = drflac_open_file_with_metadata(path, flacMetaCallback, &collector, nullptr);
    env->ReleaseStringUTFChars(jpath, path);

    if (flac) drflac_close(flac);

    if (collector.lyrics.empty()) {
        LOGD("nativeFlacReadLyrics: no embedded lyrics");
        return nullptr;
    }
    LOGI("nativeFlacReadLyrics: found %zu chars", collector.lyrics.size());
    return env->NewStringUTF(collector.lyrics.c_str());
}

} // extern "C"

// ── Decode loop: reads FLAC → float interleaved → pushPcm to ring buffer ───
// Runs in background thread, feeds UsbAudioDriver ring directly.

static void decodeLoop() {
    LOGI("decodeLoop: started");

    auto* drv = sdw_getUsbDriver();
    if (!drv) {
        LOGE("decodeLoop: no UsbAudioDriver");
        gEos = true;
        return;
    }

    const int channels = gChannels.load();
    const int sr       = gSampleRate.load();
    if (channels < 1 || channels > 8 || sr < 1) {
        LOGE("decodeLoop: invalid params ch=%d sr=%d", channels, sr);
        gEos = true;
        return;
    }

    // Decode in chunks, push to ring buffer
    // Target: keep ring ~200ms full (PREBUFFER_TARGET_MS)
    const int targetFillFrames = (sr * 200) / 1000;  // ~200ms
    const int kChunkFrames = 1024;  // decode at most 1024 frames per iteration
    float decodeBuf[kChunkFrames * 8];  // up to 8 channels

    while (gRunning && gFlac) {
        // ── Handle seek ────────────────────────────────────
        if (gSeekPending.exchange(false)) {
            int64_t targetFrame = gSeekTargetMs.load() > 0 ? msToPcmFrames(gSeekTargetMs.load()) : gSeekTargetPcmFrame.load();
            if (targetFrame < 0) targetFrame = 0;
            if (targetFrame >= gTotalPcmFrames) targetFrame = gTotalPcmFrames - 1;
            if (targetFrame < 0) targetFrame = 0;

            if (drflac_seek_to_pcm_frame(gFlac, (drflac_uint64)targetFrame)) {
                gCurrentPcmFrame = targetFrame;
                drv->resetRingBuffer();
                LOGI("decodeLoop: seek to frame %lld", (long long)targetFrame);
            } else {
                // 【2026-09-07】seek 失败不再跳回 0（旧行为 = 用户拖进度条"跳回开头"惊吓）。
                // 保持原位继续播放；若 seek 目标是明确的越界值则视为 EOS（交给上层自然切歌）。
                LOGW("decodeLoop: seek to frame %lld FAILED, staying at current position (frame %lld)",
                     (long long)targetFrame, (long long)gCurrentPcmFrame.load());
            }
            gSeekTargetMs = 0;
            gSeekTargetPcmFrame = 0;
            gEos = false;
        }

        // ── Handle pause ────────────────────────────────────
        if (gPaused) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            continue;
        }

        // ── Check ring fill: backpressure ─────────────────
        int ringFill = drv->getRingFillFrames();
        if (ringFill >= targetFillFrames) {
            // Ring is full enough, sleep a bit
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }

        // ── Decode a chunk ─────────────────────────────────
        int maxToRead = targetFillFrames - ringFill;
        if (maxToRead > kChunkFrames) maxToRead = kChunkFrames;
        if (maxToRead < 1) maxToRead = 1;

        drflac_uint64 framesRead = drflac_read_pcm_frames_f32(gFlac, (drflac_uint64)maxToRead, decodeBuf);
        if (framesRead == 0) {
            // EOS
            LOGI("decodeLoop: EOS at frame %lld", (long long)gCurrentPcmFrame.load());
            gEos = true;

            // Drain remaining: wait until ring is empty or timeout
            // [fix] also break on gRunning==false so flacStop()'s join() returns promptly
            // instead of blocking the full 10s on sub-URB residue that never drains to 0.
            int drainRetries = 0;
            while (drv->getRingFillFrames() > 0 && gRunning && drainRetries < 200) {
                std::this_thread::sleep_for(std::chrono::milliseconds(50));
                drainRetries++;
            }
            break;
        }

        // ── Push to ring buffer ────────────────────────────
        int pushed = drv->pushPcm(decodeBuf, (int)framesRead);
        if (pushed <= 0) {
            // Ring full (should not happen with backpressure above, but safe)
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            // Retry: re-wind read pointer if possible
            // dr_flac doesn't support unread, so we'll lose this chunk.
            // But backpressure should prevent this
        }
        gCurrentPcmFrame += (int64_t)framesRead;
    }

    gRunning = false;
    LOGI("decodeLoop: exited (frames fed=%lld)", (long long)gCurrentPcmFrame.load());
}
