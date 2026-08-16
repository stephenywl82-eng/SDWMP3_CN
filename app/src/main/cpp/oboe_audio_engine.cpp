#include "oboe_audio_engine.h"
#include <android/log.h>
#include <cstring>
#include <cmath>
#include <mutex>

#define LOG_TAG "OboeAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

constexpr int kBufferCapacityFrames = 96000; // ~2 seconds at 48kHz

OboeAudioEngine::OboeAudioEngine() {
    buffer_.reserve(kBufferCapacityFrames * 2);
    inputBuffer_.reserve(kBufferCapacityFrames * 2);
}

OboeAudioEngine::~OboeAudioEngine() {
    stop();
}

bool OboeAudioEngine::start(int sampleRate, int channelCount) {
    return startWithMode(sampleRate, channelCount, sharingMode_);
}

bool OboeAudioEngine::startWithMode(int sampleRate, int channelCount, oboe::SharingMode mode) {
    // 如果已运行且 mode 相同，跳过
    if (running_.load() && sharingMode_ == mode) {
        LOGI("OboeAudioEngine already running in %s mode",
             mode == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared");
        return true;
    }

    // mode 变了 → 先停掉再重建
    if (running_.load()) {
        LOGI("Restarting stream for mode switch → %s",
             mode == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared");
        stop();
    }

    sharingMode_ = mode;
    sampleRate_ = sampleRate;
    channelCount_ = channelCount;

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(mode)
           ->setFormat(oboe::AudioFormat::I16)
           ->setChannelCount(channelCount)
           ->setSampleRate(sampleRate)
           ->setCallback(this);

    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open stream: %s", oboe::convertToText(result));
        return false;
    }

    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start stream: %s", oboe::convertToText(result));
        stream_->close();
        stream_.reset();
        return false;
    }

    running_.store(true);
    LOGI("Oboe stream %s: %d Hz, %d ch",
         mode == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared",
         sampleRate, channelCount);
    return true;
}

bool OboeAudioEngine::restartWithSharingMode(oboe::SharingMode mode) {
    if (!running_.load()) {
        LOGW("restartWithSharingMode: not running, ignored");
        return false;
    }
    return startWithMode(sampleRate_, channelCount_, mode);
}

void OboeAudioEngine::stop() {
    if (!running_.load()) return;

    running_.store(false);

    if (stream_) {
        stream_->requestStop();
        stream_->close();
        stream_.reset();
    }

    buffer_.clear();
    LOGI("Oboe stream stopped");
}

size_t OboeAudioEngine::writeData(const int16_t* data, int numSamples) {
    if (!running_.load()) return 0;

    const int totalSamples = numSamples * channelCount_;
    std::lock_guard<std::mutex> lock(mutex_);
    size_t written = 0;
    for (int i = 0; i < totalSamples; i++) {
        buffer_.push_back(data[i]);
        written++;
    }

    // Limit buffer size to avoid memory bloat
    const size_t maxSamples = static_cast<size_t>(kBufferCapacityFrames) * channelCount_;
    if (buffer_.size() > maxSamples) {
        buffer_.erase(buffer_.begin(), buffer_.end() - maxSamples);
    }

    return written;
}

oboe::DataCallbackResult OboeAudioEngine::onAudioReady(
        oboe::AudioStream* audioStream,
        void* audioData,
        int32_t numFrames) {

    auto* output = static_cast<int16_t*>(audioData);
    const int samplesNeeded = numFrames * channelCount_;

    std::lock_guard<std::mutex> lock(mutex_);

    if (buffer_.size() >= static_cast<size_t>(samplesNeeded)) {
        // We have enough data — copy and remove
        memcpy(output, buffer_.data(), samplesNeeded * sizeof(int16_t));
        buffer_.erase(buffer_.begin(), buffer_.begin() + samplesNeeded);

        // Compute RMS amplitude for beat-reactive visuals
        float sumSq = 0.0f;
        for (int i = 0; i < samplesNeeded; i++) {
            float s = output[i] / 32768.0f;
            sumSq += s * s;
        }
        latestRms_ = std::sqrt(sumSq / samplesNeeded);

        return oboe::DataCallbackResult::Continue;
    } else {
        // Underrun — fill with silence
        memset(output, 0, samplesNeeded * sizeof(int16_t));

        if (!buffer_.empty()) {
            size_t available = buffer_.size();
            memcpy(output, buffer_.data(), available * sizeof(int16_t));
            // Zero out the rest
            memset(output + available, 0, (samplesNeeded - available) * sizeof(int16_t));
            buffer_.clear();
        }

        return oboe::DataCallbackResult::Continue;
    }
}

void OboeAudioEngine::onErrorBeforeClose(oboe::AudioStream* audioStream, oboe::Result error) {
    LOGE("Oboe error before close: %s", oboe::convertToText(error));
}

void OboeAudioEngine::onErrorAfterClose(oboe::AudioStream* audioStream, oboe::Result error) {
    LOGE("Oboe error after close: %s", oboe::convertToText(error));
    running_.store(false);
    buffer_.clear();
}
