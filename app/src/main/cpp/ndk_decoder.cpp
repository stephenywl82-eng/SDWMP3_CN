#include "ndk_decoder.h"
#include <unistd.h>
#include <cstring>
#include <algorithm>

// Minimum API for NDK MediaCodec is 21 (Android 5.0)
// We target minSdk 24 so we're good.

NDKDecoder::NDKDecoder() {}

NDKDecoder::~NDKDecoder() {
    stop();
}

bool NDKDecoder::open(const char* filePath) {
    // Stop any running decode thread first (joins thread, safely deletes codec)
    stop();

    std::lock_guard<std::mutex> lock(mutex_);

    eos_.store(false);
    currentPositionUs_.store(0);
    trackIndex_ = -1;

    // Create extractor
    extractor_ = AMediaExtractor_new();
    if (!extractor_) {
        LOGE("Failed to create AMediaExtractor");
        return false;
    }

    // Set file source
    // For content URIs, we'd need different handling, but for file paths this works
    int result = AMediaExtractor_setDataSource(extractor_, filePath);
    if (result != AMEDIA_OK) {
        LOGE("Failed to set data source: %s (error %d)", filePath, result);
        AMediaExtractor_delete(extractor_);
        extractor_ = nullptr;
        return false;
    }

    // Find audio track
    size_t numTracks = AMediaExtractor_getTrackCount(extractor_);
    LOGI("File has %zu tracks", numTracks);

    for (size_t i = 0; i < numTracks; i++) {
        AMediaFormat* format = AMediaExtractor_getTrackFormat(extractor_, i);
        if (!format) continue;

        const char* mime = nullptr;
        AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime);

        if (mime && strncmp(mime, "audio/", 6) == 0) {
            trackIndex_ = (int)i;
            info_.mime = mime;

            int32_t sampleRate = 0, channelCount = 0;
            int64_t duration = 0;
            AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sampleRate);
            AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &channelCount);
            AMediaFormat_getInt64(format, AMEDIAFORMAT_KEY_DURATION, &duration);

            info_.sampleRate = sampleRate;
            info_.channelCount = channelCount;
            info_.durationUs = duration;

            LOGI("Audio track %d: mime=%s, rate=%d, ch=%d, duration=%lldus",
                 trackIndex_, mime, sampleRate, channelCount, (long long)duration);

            // Select this track
            AMediaExtractor_selectTrack(extractor_, trackIndex_);

            // Create decoder
            codec_ = AMediaCodec_createDecoderByType(mime);
            if (!codec_) {
                LOGE("Failed to create decoder for mime: %s", mime);
                AMediaFormat_delete(format);
                return false;
            }

            // Configure decoder — request float output
            // AMediaFormat_dup not in NDK r25; re-get and manually copy
            AMediaFormat* configureFormat = AMediaFormat_new();
            {
                const char* cm = nullptr;
                AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &cm);
                if (cm) AMediaFormat_setString(configureFormat, AMEDIAFORMAT_KEY_MIME, cm);
                int32_t sr = 0, ch = 0; int64_t dur = 0;
                AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr);
                AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);
                AMediaFormat_getInt64(format, AMEDIAFORMAT_KEY_DURATION, &dur);
                if (sr > 0) AMediaFormat_setInt32(configureFormat, AMEDIAFORMAT_KEY_SAMPLE_RATE, sr);
                if (ch > 0) AMediaFormat_setInt32(configureFormat, AMEDIAFORMAT_KEY_CHANNEL_COUNT, ch);
                if (dur > 0) AMediaFormat_setInt64(configureFormat, AMEDIAFORMAT_KEY_DURATION, dur);
            }
            // Request float PCM output
            AMediaFormat_setInt32(configureFormat, "pcm-encoding", 0x4); // ENCODING_PCM_FLOAT = 4
            // Don't specify output sample rate — decoder outputs at source rate

            media_status_t status = AMediaCodec_configure(codec_, configureFormat, nullptr, nullptr, 0);
            AMediaFormat_delete(configureFormat);
            AMediaFormat_delete(format);

            if (status != AMEDIA_OK) {
                LOGE("Failed to configure decoder: %d", status);
                // Try without float request
                AMediaFormat* format2 = AMediaExtractor_getTrackFormat(extractor_, i);
                status = AMediaCodec_configure(codec_, format2, nullptr, nullptr, 0);
                AMediaFormat_delete(format2);
                if (status != AMEDIA_OK) {
                    LOGE("Failed to configure decoder (fallback): %d", status);
                    return false;
                }
            }

            status = AMediaCodec_start(codec_);
            if (status != AMEDIA_OK) {
                LOGE("Failed to start decoder: %d", status);
                return false;
            }

            LOGI("Decoder started for %s", mime);
            return true;
        }

        AMediaFormat_delete(format);
    }

    LOGE("No audio track found in file");
    AMediaExtractor_delete(extractor_);
    extractor_ = nullptr;
    return false;
}

bool NDKDecoder::startDecode(PCMCallback callback) {
    if (!codec_ || !extractor_ || trackIndex_ < 0) {
        LOGE("Cannot start decode — not opened");
        return false;
    }

    stop(); // Stop any previous decode

    decoding_.store(true);
    eos_.store(false);
    stopRequested_.store(false);
    paused_.store(false);

    decodeThread_ = std::thread(&NDKDecoder::decodeLoop, this, callback);
    return true;
}

void NDKDecoder::decodeLoop(PCMCallback callback) {
    LOGI("Decode loop started");

    int64_t presentationTimeUs = 0;
    int inputEos = 0;

    while (!stopRequested_.load()) {
        // Check pause
        if (paused_.load()) {
            std::unique_lock<std::mutex> lock(mutex_);
            cv_.wait_for(lock, std::chrono::milliseconds(50), [this] {
                return !paused_.load() || stopRequested_.load();
            });
            continue;
        }

        // Feed input
        if (!inputEos) {
            if (!enqueueInputBuffer(codec_, extractor_, &presentationTimeUs)) {
                inputEos = 1;
            }
        }

        // Drain output
        if (!dequeueOutputBuffer(codec_, callback, &presentationTimeUs)) {
            // EOS or error
            break;
        }

        // Small sleep to avoid busy-looping when no buffers available
        usleep(1000); // 1ms
    }

    decoding_.store(false);
    LOGI("Decode loop ended");
}

bool NDKDecoder::enqueueInputBuffer(AMediaCodec* codec, AMediaExtractor* extractor, int64_t* presentationTimeUs) {
    ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(codec, 5000); // 5ms timeout
    if (inputIndex < 0) {
        // No input buffer available right now — not an error
        return true;
    }

    size_t inputSize = 0;
    uint8_t* inputBuf = AMediaCodec_getInputBuffer(codec, inputIndex, &inputSize);
    if (!inputBuf) {
        LOGE("Failed to get input buffer");
        return false;
    }

    // Read sample data from extractor
    ssize_t sampleSize = AMediaExtractor_readSampleData(extractor, inputBuf, inputSize);
    if (sampleSize <= 0) {
        // End of input
        AMediaCodec_queueInputBuffer(codec, inputIndex, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
        LOGI("Input EOS reached");
        return false;
    }

    int64_t timeUs = AMediaExtractor_getSampleTime(extractor);
    *presentationTimeUs = timeUs;
    currentPositionUs_.store(timeUs);

    uint32_t flags = 0;
    AMediaCodec_queueInputBuffer(codec, inputIndex, 0, sampleSize, timeUs, flags);

    // Advance to next sample
    AMediaExtractor_advance(extractor);

    return true;
}

bool NDKDecoder::dequeueOutputBuffer(AMediaCodec* codec, PCMCallback callback, int64_t* presentationTimeUs) {
    AMediaCodecBufferInfo info;
    ssize_t outputIndex = AMediaCodec_dequeueOutputBuffer(codec, &info, 5000); // 5ms timeout

    if (outputIndex >= 0) {
        if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
            AMediaCodec_releaseOutputBuffer(codec, outputIndex, false);
            eos_.store(true);
            LOGI("Output EOS");
            return false;
        }

        size_t outputSize = 0;
        uint8_t* outputBuf = AMediaCodec_getOutputBuffer(codec, outputIndex, &outputSize);
        if (outputBuf && info.size > 0) {
            // Check if output is float or 16-bit PCM
            // Default MediaCodec output is 16-bit PCM unless we requested float
            int numSamples = info.size / sizeof(int16_t); // assume 16-bit
            int bytesPerSample = 2;

            // Convert 16-bit PCM to float
            int16_t* pcm16 = reinterpret_cast<int16_t*>(outputBuf + info.offset);

            // Allocate float buffer
            if (resampleBuffer_.size() < (size_t)numSamples) {
                resampleBuffer_.resize(numSamples + 1024);
            }

            for (int i = 0; i < numSamples; i++) {
                resampleBuffer_[i] = static_cast<float>(pcm16[i]) / 32768.0f;
            }

            // Deliver via callback
            callback(resampleBuffer_.data(), numSamples);
        }

        AMediaCodec_releaseOutputBuffer(codec, outputIndex, false);
        return true;

    } else if (outputIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
        AMediaFormat* format = AMediaCodec_getOutputFormat(codec);
        const char* str = AMediaFormat_toString(format);
        LOGI("Output format changed: %s", str ? str : "null");
        AMediaFormat_delete(format);
        return true;

    } else if (outputIndex == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
        // No output available yet — normal
        return true;

    } else if (outputIndex == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
        // Ignore — handled by getOutputBuffer
        return true;
    }

    return true;
}

bool NDKDecoder::seekTo(int64_t positionUs) {
    if (!extractor_ || trackIndex_ < 0) return false;

    std::lock_guard<std::mutex> lock(mutex_);

    // Flush decoder
    if (codec_) {
        AMediaCodec_flush(codec_);
    }

    // Seek extractor
    AMediaExtractor_seekTo(extractor_, positionUs, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);

    currentPositionUs_.store(positionUs);
    eos_.store(false);

    LOGI("Seeked to %lld us", (long long)positionUs);
    return true;
}

void NDKDecoder::pause() {
    paused_.store(true);
    LOGI("Decoder paused");
}

void NDKDecoder::resume() {
    paused_.store(false);
    cv_.notify_all();
    LOGI("Decoder resumed");
}

void NDKDecoder::stop() {
    stopRequested_.store(true);
    paused_.store(false);
    cv_.notify_all();

    if (decodeThread_.joinable()) {
        decodeThread_.join();
    }

    std::lock_guard<std::mutex> lock(mutex_);
    if (codec_) {
        AMediaCodec_stop(codec_);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
    }
    if (extractor_) {
        AMediaExtractor_delete(extractor_);
        extractor_ = nullptr;
    }

    decoding_.store(false);
    eos_.store(false);
    trackIndex_ = -1;
}

int64_t NDKDecoder::getCurrentPositionUs() const {
    return currentPositionUs_.load();
}
