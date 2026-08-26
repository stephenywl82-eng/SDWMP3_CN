# -*- coding: utf-8 -*-
# 方案C 对称补 A 槽重采样：A 槽作为 incoming（g_activeIsB=true）时，解码输出重采样到 stream 率，
# 且 FORMAT_CHANGED 不 reopen stream（避免打断正在播的 B 轨）。双向跨采样率 crossfade 都能淡化。
import io, sys

F = r"E:\SDWMP3_CN\app\src\main\cpp\oboe_bridge.cpp"
with io.open(F, "r", encoding="utf-8", newline="") as f:
    text = f.read()
text = text.replace("\r\n", "\n")

repls = []

# R1: 新增 A 槽重采样状态 + resetResamplerA（插在 B 槽 resetResamplerB 后）
repls.append((
"""static bool g_resampleLoggedB = false;
static void resetResamplerB() { g_resamplePhaseB = 0.0; g_resampleHasPrevB = false; for (int i = 0; i < 8; i++) g_resamplePrevB[i] = 0.0f; }""",
"""static bool g_resampleLoggedB = false;
static void resetResamplerB() { g_resamplePhaseB = 0.0; g_resampleHasPrevB = false; for (int i = 0; i < 8; i++) g_resamplePrevB[i] = 0.0f; }
// 【Crossfade 重采样】A 槽对称重采样状态（A 槽作为 incoming 时，解码输出重采样到 stream 率）
static std::atomic<int> g_sampleRateA{0};          // A 轨 decoder 输出采样率（0=未定）
static std::vector<float> g_resampleOutA;          // A 轨重采样输出缓冲
static double g_resamplePhaseA = 0.0;
static float g_resamplePrevA[8] = {0.0f};
static bool g_resampleHasPrevA = false;
static bool g_resampleLoggedA = false;
static void resetResamplerA() { g_resamplePhaseA = 0.0; g_resampleHasPrevA = false; for (int i = 0; i < 8; i++) g_resamplePrevA[i] = 0.0f; }"""))

# R2: ndkDecodeLoop A 槽写 ring 前加重采样（A 槽 incoming 时重采样到 stream 率）
repls.append((
"""                // 【V7.39】写入 RingBuffer：等待空间而非丢数据
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
                g_decoderFramesOutput.fetch_add(numSamples / g_channelCount.load());  // 【V7.16】""",
"""                // 【Crossfade 重采样】A 槽作为 incoming（g_activeIsB）时，重采样到 stream 率（否则变调）
                const float* writeSrc = g_convertBuffer.data();
                int writeSamples = numSamples;
                if (g_activeIsB.load()) {
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
                g_decoderFramesOutput.fetch_add(writeSamples / g_channelCount.load());  // 【V7.16】"""))

# R3: ndkDecodeLoop FORMAT_CHANGED 加 g_sampleRateA 记录 + A incoming 跳过 reopen stream
repls.append((
"""                if (AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr) && sr > 0) {
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
                }""",
"""                if (AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr) && sr > 0) {
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
                }"""))

# R4: nativeStartCrossfade 删除 A 槽拒绝（A 槽已有重采样器）
repls.append((
"""    if (toA) {
        if (!g_decoderCodec || !g_decoderExtractor) { LOGE("StartCrossfade: incoming(A) not opened"); return false; }
        // 【Crossfade 重采样】A 槽 incoming 采样率 != stream 率时拒绝（本版只重采样 B 槽），走硬切防变调
        int aRate = g_fileSampleRate.load();
        int sRate = g_sampleRate.load();
        if (aRate > 0 && sRate > 0 && aRate != sRate) {
            LOGE("StartCrossfade: incoming(A) rate %d != stream %d, refuse (A slot resampler not implemented)", aRate, sRate);
            return false;
        }
    } else {""",
"""    if (toA) {
        if (!g_decoderCodec || !g_decoderExtractor) { LOGE("StartCrossfade: incoming(A) not opened"); return false; }
        // 【Crossfade 重采样】A 槽 incoming 采样率 != stream 率时，写 ring 时重采样（A 槽已有重采样器）
    } else {"""))

# R5: openIncomingToA_internal 重置 A 槽采样率与重采样状态
repls.append((
"""    if (g_decoderTrackIndex < 0) { LOGE("Incoming(A): no audio track"); return false; }
    LOGI("Incoming(A) decoder ready (FD)");
    return true;
}""",
"""    if (g_decoderTrackIndex < 0) { LOGE("Incoming(A): no audio track"); return false; }
    // 【Crossfade 重采样】新歌进 A 槽，重置采样率与重采样状态
    g_sampleRateA.store(0);
    resetResamplerA();
    g_resampleLoggedA = false;
    LOGI("Incoming(A) decoder ready (FD)");
    return true;
}"""))

all_ok = True
for idx, (old, new) in enumerate(repls, 1):
    c = text.count(old)
    print(f"R{idx}: MATCH count = {c}")
    if c == 1:
        text = text.replace(old, new, 1)
    else:
        print(f"R{idx}: SKIP (count={c}), need manual inspection")
        all_ok = False

if all_ok:
    with io.open(F, "w", encoding="utf-8", newline="") as f:
        f.write(text.replace("\n", "\r\n"))
    print("ALL DONE, size =", len(text.encode("utf-8")))
else:
    print("SOME BLOCKS SKIPPED, no write performed")
    sys.exit(2)
