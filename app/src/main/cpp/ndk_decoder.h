#pragma once

#include <android/asset_manager.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>
#include <cstdint>
#include <vector>
#include <string>
#include <mutex>
#include <atomic>
#include <condition_variable>
#include <thread>

#define LOG_TAG "NDKDecoder"
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

/**
 * NDK MediaCodec decoder — decodes audio files to PCM float data.
 *
 * Uses AMediaExtractor for demuxing and AMediaCodec for hardware decoding.
 * Outputs interleaved float PCM via callback.
 */
class NDKDecoder {
public:
    struct Info {
        int sampleRate;
        int channelCount;
        int64_t durationUs;
        std::string mime;
    };

    // Callback: receives decoded float PCM samples
    using PCMCallback = std::function<void(const float* data, int numSamples)>;

    NDKDecoder();
    ~NDKDecoder();

    // Open a file for decoding. Returns true on success.
    bool open(const char* filePath);

    // Get audio info (valid after open())
    Info getInfo() const { return info_; }

    // Start decoding from the beginning or from a seek position
    // PCM data is delivered via callback
    bool startDecode(PCMCallback callback);

    // Seek to position in microseconds
    bool seekTo(int64_t positionUs);

    // Pause/resume decoding
    void pause();
    void resume();

    // Stop decoding and release resources
    void stop();

    // Get current position in microseconds
    int64_t getCurrentPositionUs() const;

    // Is currently decoding?
    bool isDecoding() const { return decoding_.load(); }

    // Has reached end of stream?
    bool isEOS() const { return eos_.load(); }

private:
    void decodeLoop(PCMCallback callback);
    bool enqueueInputBuffer(AMediaCodec* codec, AMediaExtractor* extractor, int64_t* presentationTimeUs);
    bool dequeueOutputBuffer(AMediaCodec* codec, PCMCallback callback, int64_t* presentationTimeUs);

    AMediaExtractor* extractor_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    Info info_ = {};
    int trackIndex_ = -1;

    std::atomic<bool> decoding_{false};
    std::atomic<bool> paused_{false};
    std::atomic<bool> eos_{false};
    std::atomic<bool> stopRequested_{false};
    std::atomic<int64_t> currentPositionUs_{0};

    std::thread decodeThread_;
    std::mutex mutex_;
    std::condition_variable cv_;

    // Resampling support
    std::vector<float> resampleBuffer_;
};
