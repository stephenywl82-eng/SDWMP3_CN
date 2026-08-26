# -*- coding: utf-8 -*-
# 修复：B 轨接管后（g_activeIsB=true）拖动进度条无效
# 原因：nativeSeekTo 只 seek A 轨，B 轨 active 时 seek 的是退役 A 轨
# 方案：nativeSeekTo 按 active 轨分流，B active 时 seek B 轨 codec/extractor/ring
import io, sys

F = r"E:\SDWMP3_CN\app\src\main\cpp\oboe_bridge.cpp"
with io.open(F, "r", encoding="utf-8", newline="") as f:
    text = f.read()
text = text.replace("\r\n", "\n")

old = """Java_com_sdw_music_player_OboeDirectPlayer_nativeSeekTo(JNIEnv *env, jobject thiz,
                                                     jlong positionUs) {
    std::lock_guard<std::mutex> lifecycleLock(g_decoderLifecycleMutex);
    LOGI("OboeDirectPlayer: seekTo %lld us", (long long)positionUs);
    g_decoderPaused.store(true);
    if (g_ringBuffer) g_ringBuffer->clear();
    g_framesWritten.store(0);
    g_playbackPositionUs.store(positionUs);  // 【V7.82】同步实际播放位置到 seek 目标，防止进度条回跳
    g_flushRequested.store(true);
    if (g_decoderCodec) AMediaCodec_flush(g_decoderCodec);
    if (g_decoderExtractor) AMediaExtractor_seekTo(g_decoderExtractor, positionUs, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
    g_decoderPositionUs.store(positionUs);
    g_decoderEos.store(false);
    g_decoderPaused.store(false);
    g_decoderCv.notify_all();
}"""

new = """Java_com_sdw_music_player_OboeDirectPlayer_nativeSeekTo(JNIEnv *env, jobject thiz,
                                                     jlong positionUs) {
    std::lock_guard<std::mutex> lifecycleLock(g_decoderLifecycleMutex);
    LOGI("OboeDirectPlayer: seekTo %lld us", (long long)positionUs);
    // 【Crossfade】按 active 轨分流：B 接管后 seek 的是 B 轨，否则 seek 退役 A 轨会导致进度条无效
    bool seekB = g_activeIsB.load();
    // 共享的 Oboe 输出侧状态（两轨共用同一输出流）
    g_framesWritten.store(0);
    g_playbackPositionUs.store(positionUs);  // 【V7.82】同步实际播放位置到 seek 目标，防止进度条回跳
    g_flushRequested.store(true);
    if (seekB) {
        g_decoderPausedB.store(true);
        if (g_ringBufferB) g_ringBufferB->clear();
        if (g_decoderCodecB) AMediaCodec_flush(g_decoderCodecB);
        if (g_decoderExtractorB) AMediaExtractor_seekTo(g_decoderExtractorB, positionUs, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
        g_decoderPositionB.store(positionUs);
        g_decoderEosB.store(false);
        g_decoderPausedB.store(false);
        g_decoderCvB.notify_all();
    } else {
        g_decoderPaused.store(true);
        if (g_ringBuffer) g_ringBuffer->clear();
        if (g_decoderCodec) AMediaCodec_flush(g_decoderCodec);
        if (g_decoderExtractor) AMediaExtractor_seekTo(g_decoderExtractor, positionUs, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
        g_decoderPositionUs.store(positionUs);
        g_decoderEos.store(false);
        g_decoderPaused.store(false);
        g_decoderCv.notify_all();
    }
}"""

c = text.count(old)
print("MATCH count =", c)
if c == 1:
    text = text.replace(old, new, 1)
    with io.open(F, "w", encoding="utf-8", newline="") as f:
        f.write(text.replace("\n", "\r\n"))
    print("DONE, size =", len(text.encode("utf-8")))
else:
    print("SKIP, need manual inspection")
    sys.exit(2)
