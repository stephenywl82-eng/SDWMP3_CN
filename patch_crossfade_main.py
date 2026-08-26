# -*- coding: utf-8 -*-
# Crossfade 双轨移植：SDWMP3_CN 主项目 oboe_bridge.cpp（方案 B：先 mix 再走 DSP）
# 行尾 CRLF 保留（读写 newline 归一化后写回 \r\n）
import io, sys

F = r"E:\SDWMP3_CN\app\src\main\cpp\oboe_bridge.cpp"

with io.open(F, "r", encoding="utf-8", newline="") as f:
    text = f.read()

# 校验原始换行：统一为 \n 处理，写回时转 \r\n
text = text.replace("\r\n", "\n")

repls = []

# ============ R1: 前置声明区（onAudioReady 之前，line ~418）============
old1 = """static std::mutex g_streamMutex;
static PCMRingBuffer *g_ringBuffer = nullptr;
static oboe::ManagedStream g_outputStream;"""
new1 = """static std::mutex g_streamMutex;
static PCMRingBuffer *g_ringBuffer = nullptr;
// 【Crossfade】前置声明：onAudioReady 需引用（B 轨 + crossfade 状态原子），
// 原 decoder 全局区的 g_decoderStopRequested/g_decoderCv 定义上移至此。
static std::atomic<bool> g_decoderStopRequested{false};
static std::condition_variable g_decoderCv;
static std::atomic<bool> g_decoderStopRequestedB{false};
static std::condition_variable g_decoderCvB;
static PCMRingBuffer *g_ringBufferB = nullptr;   // 【Crossfade】B 轨 ring
static std::atomic<bool> g_activeIsB{false};     // 【Crossfade】active 轨是否为 B
static std::atomic<bool> g_crossfadeActive{false}; // 【Crossfade】正在交叉淡化
static std::atomic<float> g_crossfadePos{0.0f};    // 【Crossfade】进度 0..1
static std::atomic<float> g_crossfadeStep{0.0f};   // 【Crossfade】每帧增量
static float g_xfadeBufA[16384];   // 【Crossfade】A 轨临时缓冲（实时线程安全 static）
static float g_xfadeBufB[16384];   // 【Crossfade】B 轨临时缓冲
static oboe::ManagedStream g_outputStream;"""
repls.append((old1, new1))

# ============ R2: decoder 全局区去掉重复的 stopRequested/Cv，加 B 轨全局 ============
old2 = """static std::atomic<bool> g_decoderRunning{false};
static std::atomic<bool> g_decoderPaused{false};
static std::atomic<bool> g_decoderStopRequested{false};
static std::atomic<bool> g_decoderEos{false};
static std::atomic<int64_t> g_decoderPositionUs{0};
static std::thread g_decoderThread;
static std::mutex g_decoderMutex;
static std::condition_variable g_decoderCv;"""
new2 = """static std::atomic<bool> g_decoderRunning{false};
static std::atomic<bool> g_decoderPaused{false};
// 【Crossfade】g_decoderStopRequested / g_decoderCv 已前置声明到 onAudioReady 之前
static std::atomic<bool> g_decoderEos{false};
static std::atomic<int64_t> g_decoderPositionUs{0};
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
static std::atomic<int> g_decoderFramesOutputB{0};"""
repls.append((old2, new2))

# ============ R3: onAudioReady 读 ring 块 → crossfade 感知 ============
old3 = """        // Read from ring buffer
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
        }"""
new3 = """        // Read from ring buffer（方案 B：crossfade 先 mix 进 output，再统一走下方 RMS/频谱/DSP）
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
                float gTo = sinf(pos * HP);
                float gFrom = cosf(pos * HP);
                for (int c = 0; c < channels; c++) {
                    int idx = f * channels + c;
                    float from = idx < toReadFrom ? g_xfadeBufA[idx] : 0.0f;
                    float to = idx < toReadTo ? g_xfadeBufB[idx] : 0.0f;
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
        }"""
repls.append((old3, new3))

# ============ R4: ndkDecodeLoop 之后插入 ndkDecodeLoopB ============
old4 = """    g_decoderRunning.store(false);
    g_decoderThreadRunning.store(false);  // 【V7.16】
    LOGI("NDK decode loop ended");
}"""
new4 = """    g_decoderRunning.store(false);
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
                // 【V7.39】写入 RingBuffer：等待空间而非丢数据
                if (g_ringBufferB) {
                    int written = 0;
                    int remain = numSamples;
                    const float* src2 = g_convertBufferB.data();
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
                        LOGW("RingBuffer write: dropped %d/%d samples after %d retries", remain, numSamples, retryCount);
                    }
                }
                g_decoderFramesOutputB.fetch_add(numSamples / g_channelCount.load());  // 【V7.16】

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
}"""
repls.append((old4, new4))

# ============ R5: nativeOpen 复位 crossfade + 停 B 轨 ============
old5 = """    LOGI("OboeDirectPlayer: opening %s", path);

    g_decoderStopRequested.store(true);"""
new5 = """    LOGI("OboeDirectPlayer: opening %s", path);

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

    g_decoderStopRequested.store(true);"""
repls.append((old5, new5))

# ============ R6: nativeOpenFd 复位 crossfade + 停 B 轨 ============
old6 = """    LOGI("OboeDirectPlayer: opening FD %d offset=%lld length=%lld", fd, (long long)offset, (long long)length);

    g_decoderStopRequested.store(true);"""
new6 = """    LOGI("OboeDirectPlayer: opening FD %d offset=%lld length=%lld", fd, (long long)offset, (long long)length);

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

    g_decoderStopRequested.store(true);"""
repls.append((old6, new6))

# ============ R7: nativeStop 复位 crossfade + 停 B 轨 ============
old7 = """    LOGI("OboeDirectPlayer: stopping");
    g_decoderStopRequested.store(true);"""
new7 = """    LOGI("OboeDirectPlayer: stopping");
    // 【Crossfade】停止时复位 crossfade 状态并停 B 轨
    g_crossfadeActive.store(false);
    g_activeIsB.store(false);
    g_crossfadePos.store(0.0f);
    g_decoderStopRequestedB.store(true);
    g_decoderCvB.notify_all();
    if (g_decoderThreadB.joinable()) g_decoderThreadB.join();
    if (g_decoderCodecB) { AMediaCodec_stop(g_decoderCodecB); AMediaCodec_delete(g_decoderCodecB); g_decoderCodecB = nullptr; }
    if (g_decoderExtractorB) { AMediaExtractor_delete(g_decoderExtractorB); g_decoderExtractorB = nullptr; }
    if (g_ringBufferB) g_ringBufferB->clear();
    g_decoderStopRequested.store(true);"""
repls.append((old7, new7))

# ============ R8: nativeStop 之后插入 5 个新 JNI + 内部函数 ============
old8 = """    g_decoderTrackIndex = -1;
}

// --- Position / Duration / State queries ---"""
new8 = """    g_decoderTrackIndex = -1;
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
                if (sr2>0) AMediaFormat_setInt32(cfg, AMEDIAFORMAT_KEY_SAMPLE_RATE, sr2);
                if (ch2>0) AMediaFormat_setInt32(cfg, AMEDIAFORMAT_KEY_CHANNEL_COUNT, ch2);
                if (dur>0) AMediaFormat_setInt64(cfg, AMEDIAFORMAT_KEY_DURATION, dur);
            }
            copyCsdBuffers(cfg, format);
            AMediaFormat_setInt32(cfg, "pcm-encoding", 2);
            media_status_t s = AMediaCodec_configure(g_decoderCodec, cfg, nullptr, nullptr, 0);
            AMediaFormat_delete(cfg);
            if (s != AMEDIA_OK) { LOGE("Incoming(A): configure failed %d", s); AMediaFormat_delete(format); return false; }
            s = AMediaCodec_start(g_decoderCodec);
            if (s != AMEDIA_OK) { LOGE("Incoming(A): start failed %d", s); AMediaFormat_delete(format); return false; }
            AMediaFormat_delete(format);
            break;
        }
        AMediaFormat_delete(format);
    }
    if (g_decoderTrackIndex < 0) { LOGE("Incoming(A): no audio track"); return false; }
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
            AMediaFormat_setInt32(cfg, "pcm-encoding", 2);
            media_status_t s = AMediaCodec_configure(g_decoderCodecB, cfg, nullptr, nullptr, 0);
            AMediaFormat_delete(cfg);
            if (s != AMEDIA_OK) { LOGE("Incoming(B): configure failed %d", s); AMediaFormat_delete(format); return false; }
            s = AMediaCodec_start(g_decoderCodecB);
            if (s != AMEDIA_OK) { LOGE("Incoming(B): start failed %d", s); AMediaFormat_delete(format); return false; }
            AMediaFormat_delete(format);
            break;
        }
        AMediaFormat_delete(format);
    }
    if (g_decoderTrackIndexB < 0) { LOGE("Incoming(B): no audio track"); return false; }
    LOGI("Incoming(B) decoder ready (FD)");
    return true;
}

JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeStartCrossfade(JNIEnv *env, jobject thiz, jint durationMs) {
    // 判断空闲槽（incoming）：B active 时新歌进 A 槽，否则进 B 槽
    bool toA = g_activeIsB.load();
    if (toA) {
        if (!g_decoderCodec || !g_decoderExtractor) { LOGE("StartCrossfade: incoming(A) not opened"); return false; }
    } else {
        if (!g_decoderCodecB || !g_decoderExtractorB) { LOGE("StartCrossfade: incoming(B) not opened"); return false; }
    }
    // 【ANR 修复·重入守卫】crossfade 进行中拒绝重复触发
    if (g_crossfadeActive.load()) { LOGE("StartCrossfade: already active, ignored"); return false; }
    g_crossfadeActive.store(true);
    int sr = g_sampleRate.load(); if (sr <= 0) sr = 44100;
    float totalFrames = (float)sr * ((float)durationMs / 1000.0f);
    if (totalFrames < 1.0f) totalFrames = 1.0f;
    g_crossfadeStep.store(1.0f / totalFrames);
    g_crossfadePos.store(0.0f);
    if (toA) {
        g_decoderStopRequested.store(true);
        g_decoderCv.notify_all();
        if (g_decoderThread.joinable()) g_decoderThread.join();
        g_decoderStopRequested.store(false);
        g_decoderPaused.store(false);
        g_decoderEos.store(false);
        g_decoderRunning.store(true);
        g_decoderThread = std::thread(ndkDecodeLoop);
    } else {
        g_decoderStopRequestedB.store(true);
        g_decoderCvB.notify_all();
        if (g_decoderThreadB.joinable()) g_decoderThreadB.join();
        g_decoderStopRequestedB.store(false);
        g_decoderPausedB.store(false);
        g_decoderEosB.store(false);
        g_decoderRunningB.store(true);
        g_decoderThreadB = std::thread(ndkDecodeLoopB);
    }
    LOGI("Crossfade started: %dms, step=%.6f (toA=%d)", durationMs, g_crossfadeStep.load(), toA ? 1 : 0);
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
        LOGI("Inactive track B released");
    }
}

// --- Position / Duration / State queries ---"""
repls.append((old8, new8))

# ============ R9: getDurationMs 感知 active 轨 ============
old9 = """    // Return cached duration to avoid AMediaExtractor_getTrackFormat race
    // (extractor may be torn down concurrently during track switch / release)
    return g_cachedDurationUs.load() / 1000;
}"""
new9 = """    // Return cached duration to avoid AMediaExtractor_getTrackFormat race
    // (extractor may be torn down concurrently during track switch / release)
    // 【Crossfade】B 接管后读 B 轨时长
    if (g_activeIsB.load()) return g_cachedDurationB.load() / 1000;
    return g_cachedDurationUs.load() / 1000;
}"""
repls.append((old9, new9))

# ============ R10: isEos 感知 active 轨 ============
old10 = """    // Must wait for both: decoder received EOS AND drain loop exited
    // (RingBuffer empty). Otherwise completion fires while audio still playing.
    return g_decoderEos.load() && !g_decoderRunning.load();
}"""
new10 = """    // Must wait for both: decoder received EOS AND drain loop exited
    // (RingBuffer empty). Otherwise completion fires while audio still playing.
    // 【Crossfade】B 接管后读 B 轨 EOS 状态
    if (g_activeIsB.load()) return g_decoderEosB.load() && !g_decoderRunningB.load();
    return g_decoderEos.load() && !g_decoderRunning.load();
}"""
repls.append((old10, new10))

# 执行替换，每个 old 必须恰好出现一次
ok_all = True
for i, (old, new) in enumerate(repls, 1):
    n = text.count(old)
    if n != 1:
        print("R%d: MATCH COUNT = %d (期望 1)  —— 检查锚点" % (i, n))
        ok_all = False
        continue
    text = text.replace(old, new, 1)
    print("R%d: OK" % i)

if not ok_all:
    print("存在未匹配锚点，中止写回。")
    sys.exit(1)

# 写回 CRLF
with io.open(F, "w", encoding="utf-8", newline="\r\n") as f:
    f.write(text)

print("写入完成，文件大小 = %d bytes" % len(text.encode("utf-8")))
