# -*- coding: utf-8 -*-
# 修复：g_decoderPositionUs 前置声明（onAudioReady crossfade 完成分支引用）
import io, sys

F = r"E:\SDWMP3_CN\app\src\main\cpp\oboe_bridge.cpp"
with io.open(F, "r", encoding="utf-8", newline="") as f:
    text = f.read()
text = text.replace("\r\n", "\n")

repls = []

# R1: 前置声明区加 g_decoderPositionUs
old1 = """static std::atomic<bool> g_decoderStopRequested{false};
static std::condition_variable g_decoderCv;"""
new1 = """static std::atomic<bool> g_decoderStopRequested{false};
static std::condition_variable g_decoderCv;
static std::atomic<int64_t> g_decoderPositionUs{0};  // 【Crossfade】前置声明（onAudioReady 完成分支引用）"""
repls.append((old1, new1))

# R2: decoder 全局区删除重复定义
old2 = """static std::atomic<bool> g_decoderEos{false};
static std::atomic<int64_t> g_decoderPositionUs{0};
static std::thread g_decoderThread;"""
new2 = """static std::atomic<bool> g_decoderEos{false};
// 【Crossfade】g_decoderPositionUs 已前置声明到 onAudioReady 之前
static std::thread g_decoderThread;"""
repls.append((old2, new2))

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
print("DONE")
