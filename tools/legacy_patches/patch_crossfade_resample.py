# -*- coding: utf-8 -*-
# 方案C：crossfade incoming 轨重采样到 stream 采样率，修复"播放好快/变调"
# 根因：A 轨(48k) crossfade 到 B 轨(44.1k) 时，B 轨数据未重采样被 48k stream 快放 → 1.088 倍变调
import io, sys

F = r"E:\SDWMP3_CN\app\src\main\cpp\oboe_bridge.cpp"
with io.open(F, "r", encoding="utf-8", newline="") as f:
    text = f.read()
text = text.replace("\r\n", "\n")

repls = []

# R1: 新增 B 轨重采样全局状态 + 线性插值重采样函数（插在 g_decoderFramesOutputB 定义后）
repls.append((
"""static std::atomic<int> g_decoderFramesOutputB{0};""",
"""static std::atomic<int> g_decoderFramesOutputB{0};
// 【Crossfade 重采样】B 槽 incoming 采样率 + 线性重采样到 stream 采样率的状态
// 目的：A 轨(48k) crossfade 到 B 轨(44.1k) 时，B 轨数据若不重采样会被 48k stream 快放 -> 变调
static std::atomic<int> g_sampleRateB{0};          // B 轨 decoder 输出采样率（0=未定）
static std::vector<float> g_resampleOutB;          // B 轨重采样输出缓冲
static double g_resamplePhaseB = 0.0;              // 输入帧域相位残差
static float g_resamplePrevB[8] = {0.0f};          // 上一块尾帧（每声道，最多 8 声道）
static bool g_resampleHasPrevB = false;
static bool g_resampleLoggedB = false;
static void resetResamplerB() { g_resamplePhaseB = 0.0; g_resampleHasPrevB = false; for (int i = 0; i < 8; i++) g_resamplePrevB[i] = 0.0f; }
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
}"""))

# R2: ndkDecodeLoopB 写 ring 前加重采样（B 槽独有锚点 if (g_ringBufferB) + g_convertBufferB）
repls.append((
"""                // 【V7.39】写入 RingBuffer：等待空间而非丢数据
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
                g_decoderFramesOutputB.fetch_add(numSamples / g_channelCount.load());  // 【V7.16】""",
"""                // 【Crossfade 重采样】B 轨采样率 != stream 采样率时，线性重采样到 stream 率（否则变调）
                const float* writeSrc = g_convertBufferB.data();
                int writeSamples = numSamples;
                int streamRate = g_sampleRate.load();
                int bRate = g_sampleRateB.load();
                int chB = g_channelCount.load();
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
                g_decoderFramesOutputB.fetch_add(writeSamples / g_channelCount.load());  // 【V7.16】"""))

# R3: ndkDecodeLoopB FORMAT_CHANGED 读采样率存 g_sampleRateB
repls.append((
"""                // 【Crossfade】B 轨不写共享 g_channelCount/g_sampleRate、不 reopen stream
                AMediaFormat_delete(format);""",
"""                int32_t srB = 0;
                if (AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &srB) && srB > 0) {
                    g_sampleRateB.store(srB);  // 【Crossfade 重采样】记录 B 轨解码采样率
                }
                // 【Crossfade】B 轨不写共享 g_channelCount/g_sampleRate、不 reopen stream
                AMediaFormat_delete(format);"""))

# R4: nativeStartCrossfade — A 槽 incoming 采样率 != stream 率时拒绝（走硬切防变调）
repls.append((
"""    bool toA = g_activeIsB.load();
    if (toA) {
        if (!g_decoderCodec || !g_decoderExtractor) { LOGE("StartCrossfade: incoming(A) not opened"); return false; }
    } else {
        if (!g_decoderCodecB || !g_decoderExtractorB) { LOGE("StartCrossfade: incoming(B) not opened"); return false; }
    }""",
"""    bool toA = g_activeIsB.load();
    if (toA) {
        if (!g_decoderCodec || !g_decoderExtractor) { LOGE("StartCrossfade: incoming(A) not opened"); return false; }
        // 【Crossfade 重采样】A 槽 incoming 采样率 != stream 率时拒绝（本版只重采样 B 槽），走硬切防变调
        int aRate = g_fileSampleRate.load();
        int sRate = g_sampleRate.load();
        if (aRate > 0 && sRate > 0 && aRate != sRate) {
            LOGE("StartCrossfade: incoming(A) rate %d != stream %d, refuse (A slot resampler not implemented)", aRate, sRate);
            return false;
        }
    } else {
        if (!g_decoderCodecB || !g_decoderExtractorB) { LOGE("StartCrossfade: incoming(B) not opened"); return false; }
    }"""))

# R5: openIncomingToA_internal 记录 A 槽采样率（供 R4 守卫判断）
repls.append((
"""                if (dur > 0) g_cachedDurationUs.store(dur);
                if (sr2>0) AMediaFormat_setInt32(cfg, AMEDIAFORMAT_KEY_SAMPLE_RATE, sr2);""",
"""                if (dur > 0) g_cachedDurationUs.store(dur);
                if (sr2 > 0) { g_fileSampleRate.store(sr2); }  // 【Crossfade 重采样】记录 A 槽采样率（incoming 守卫用）
                if (sr2>0) AMediaFormat_setInt32(cfg, AMEDIAFORMAT_KEY_SAMPLE_RATE, sr2);"""))

# R6: openIncomingToB_internal 重置 B 轨采样率与重采样状态
repls.append((
"""    if (g_decoderTrackIndexB < 0) { LOGE("Incoming(B): no audio track"); return false; }
    LOGI("Incoming(B) decoder ready (FD)");
    return true;
}""",
"""    if (g_decoderTrackIndexB < 0) { LOGE("Incoming(B): no audio track"); return false; }
    // 【Crossfade 重采样】新歌进 B 槽，重置采样率与重采样状态
    g_sampleRateB.store(0);
    resetResamplerB();
    g_resampleLoggedB = false;
    LOGI("Incoming(B) decoder ready (FD)");
    return true;
}"""))

# R7: nativeSeekTo seekB 分支重置重采样状态
repls.append((
"""    if (seekB) {
        g_decoderPausedB.store(true);
        if (g_ringBufferB) g_ringBufferB->clear();
        if (g_decoderCodecB) AMediaCodec_flush(g_decoderCodecB);""",
"""    if (seekB) {
        g_decoderPausedB.store(true);
        if (g_ringBufferB) g_ringBufferB->clear();
        resetResamplerB();  // 【Crossfade 重采样】seek 后清重采样相位/prev
        if (g_decoderCodecB) AMediaCodec_flush(g_decoderCodecB);"""))

# 执行替换，每个必须唯一匹配
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
