#pragma once

#include <oboe/Oboe.h>
#include <vector>
#include <memory>
#include <cstdint>
#include <mutex>
#include <atomic>

class OboeAudioEngine : public oboe::AudioStreamCallback {
public:
    OboeAudioEngine();
    ~OboeAudioEngine();

    bool start(int sampleRate, int channelCount);
    void stop();
    bool restartWithSharingMode(oboe::SharingMode mode);
    bool isRunning() const { return stream_ && stream_->getState() == oboe::StreamState::StreamOpen; }

    // Write PCM data (16-bit signed interleaved) to the stream
    size_t writeData(const int16_t* data, int numSamples);

    // Oboe callback
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* audioStream,
        void* audioData,
        int32_t numFrames) override;

    void onErrorBeforeClose(oboe::AudioStream* audioStream, oboe::Result error) override;
    void onErrorAfterClose(oboe::AudioStream* audioStream, oboe::Result error) override;

    bool startWithMode(int sampleRate, int channelCount, oboe::SharingMode mode);

    // RMS amplitude (0.0~1.0) — written in Oboe callback, polled from JNI
    float getLatestRms() const { return latestRms_; }

private:
    oboe::ManagedStream stream_;
    oboe::SharingMode sharingMode_ = oboe::SharingMode::Exclusive;
    std::mutex mutex_;
    std::vector<int16_t> buffer_;
    std::vector<int16_t> inputBuffer_;
    int sampleRate_ = 44100;
    int channelCount_ = 2;
    std::atomic<bool> running_{false};
    float latestRms_ = 0.0f;
};
