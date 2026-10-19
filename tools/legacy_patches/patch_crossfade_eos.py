# -*- coding: utf-8 -*-
# 修复：crossfade 进行中 nativeIsEos 返回 false，防止旧 A 轨 EOS 触发 onCompletion→playNext 硬切打断 crossfade
import io, sys

F = r"E:\SDWMP3_CN\app\src\main\cpp\oboe_bridge.cpp"
with io.open(F, "r", encoding="utf-8", newline="") as f:
    text = f.read()
text = text.replace("\r\n", "\n")

old = """JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeIsEos(JNIEnv *env, jobject thiz) {
    // Must wait for both: decoder received EOS AND drain loop exited
    // (RingBuffer empty). Otherwise completion fires while audio still playing.
    // 【Crossfade】B 接管后读 B 轨 EOS 状态
    if (g_activeIsB.load()) return g_decoderEosB.load() && !g_decoderRunningB.load();
    return g_decoderEos.load() && !g_decoderRunning.load();
}"""

new = """JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_OboeDirectPlayer_nativeIsEos(JNIEnv *env, jobject thiz) {
    // Must wait for both: decoder received EOS AND drain loop exited
    // (RingBuffer empty). Otherwise completion fires while audio still playing.
    // 【Crossfade】crossfade 进行中不算播完：旧 A 轨已 EOS 但 B 轨仍在淡入接管，
    // 此时若返回 true 会触发 onCompletion→playNext 硬切，打断 crossfade 后半段。
    if (g_crossfadeActive.load()) return false;
    // 【Crossfade】B 接管后读 B 轨 EOS 状态
    if (g_activeIsB.load()) return g_decoderEosB.load() && !g_decoderRunningB.load();
    return g_decoderEos.load() && !g_decoderRunning.load();
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
