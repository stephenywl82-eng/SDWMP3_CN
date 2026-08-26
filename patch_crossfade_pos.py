# -*- coding: utf-8 -*-
# 补充：crossfade 完成时重置播放位置（B 已播 crossfade 时长）
import io, sys

F = r"E:\SDWMP3_CN\app\src\main\cpp\oboe_bridge.cpp"
with io.open(F, "r", encoding="utf-8", newline="") as f:
    text = f.read()
text = text.replace("\r\n", "\n")

repls = []

# R1: 前置声明加 g_crossfadeDurationMs
old1 = """static std::atomic<float> g_crossfadePos{0.0f};    // 【Crossfade】进度 0..1
static std::atomic<float> g_crossfadeStep{0.0f};   // 【Crossfade】每帧增量"""
new1 = """static std::atomic<float> g_crossfadePos{0.0f};    // 【Crossfade】进度 0..1
static std::atomic<float> g_crossfadeStep{0.0f};   // 【Crossfade】每帧增量
static std::atomic<int> g_crossfadeDurationMs{0};  // 【Crossfade】时长（完成后重置播放位置用）"""
repls.append((old1, new1))

# R2: crossfade complete 分支重置位置
old2 = """            if (pos >= 1.0f) {
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
            }"""
new2 = """            if (pos >= 1.0f) {
                // crossfade 完成：翻转 active 槽，停旧 active（无阻塞，join 延后到下次 open/stop）
                g_crossfadeActive.store(false);
                g_activeIsB.store(!fromB);
                // 【Crossfade】新 active 轨已播 crossfade 时长，重置播放位置（否则延续旧轨 position）
                g_playbackPositionUs.store((int64_t)g_crossfadeDurationMs.load() * 1000LL);
                g_decoderPositionUs.store((int64_t)g_crossfadeDurationMs.load() * 1000LL);
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
            }"""
repls.append((old2, new2))

# R3: startCrossfade 存 durationMs
old3 = """    g_crossfadeStep.store(1.0f / totalFrames);
    g_crossfadePos.store(0.0f);"""
new3 = """    g_crossfadeStep.store(1.0f / totalFrames);
    g_crossfadePos.store(0.0f);
    g_crossfadeDurationMs.store(durationMs);"""
repls.append((old3, new3))

ok = True
for i, (o, n) in enumerate(repls, 1):
    c = text.count(o)
    if c != 1:
        print("R%d MATCH=%d (期望1)" % (i, c))
        ok = False
        continue
    text = text.replace(o, n, 1)
    print("R%d OK" % i)

if not ok:
    sys.exit(1)

with io.open(F, "w", encoding="utf-8", newline="\r\n") as f:
    f.write(text)
print("DONE, size=%d" % len(text.encode("utf-8")))
