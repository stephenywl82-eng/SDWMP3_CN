# -*- coding: utf-8 -*-
# 修复：nativeOpenFd 未缓存 duration → getDurationMs 返回 0 → crossfade monitor 永不触发
import io, sys

F = r"E:\SDWMP3_CN\app\src\main\cpp\oboe_bridge.cpp"
with io.open(F, "r", encoding="utf-8", newline="") as f:
    text = f.read()
text = text.replace("\r\n", "\n")

# nativeOpenFd 的 configureFormat 段（特征：后面跟 copyCsdBuffers(configureFormat, format)，
# 与 nativeOpen 的 configureFormat 段相同，但 nativeOpen 已经缓存过 duration，需用行上下文区分）
# 用「g_nativeOpenStep.store(4); 后创建 decoder」的上文不可靠，改用「这段前面有 format2 重试」。
# 实际上 nativeOpen（path 版）也走 configureFormat，且 2009 行已缓存。两处 configureFormat 段的
# 差异：nativeOpenFd 的 configureFormat 段后面紧跟的是「if (status != AMEDIA_OK) { AMediaFormat* format2 ...」重试块。
# 我们采用更简单策略：找出所有未缓存 duration 的 configureFormat 段（即「getInt64 DURATION」后直接接 sr2 setInt32 的），
# 且该段属于 nativeOpenFd。用唯一锚点：nativeOpenFd 里有 LOGI("FD Audio track ...") 且 configureFormat 段。

# 精确锚点：nativeOpenFd 中 configureFormat 段，前面是 g_nativeOpenStep.store(4) + createDecoderByType 返回判断。
# 直接用计数法：全局「getInt64 DURATION + sr2 setInt32」模式出现次数
old = """                AMediaFormat_getInt64(format, AMEDIAFORMAT_KEY_DURATION, &dur);
                if (sr2 > 0) AMediaFormat_setInt32(configureFormat, AMEDIAFORMAT_KEY_SAMPLE_RATE, sr2);
                if (ch2 > 0) AMediaFormat_setInt32(configureFormat, AMEDIAFORMAT_KEY_CHANNEL_COUNT, ch2);
                if (dur > 0) AMediaFormat_setInt64(configureFormat, AMEDIAFORMAT_KEY_DURATION, dur);
            }
            copyCsdBuffers(configureFormat, format);"""

new = """                AMediaFormat_getInt64(format, AMEDIAFORMAT_KEY_DURATION, &dur);
                if (dur > 0) g_cachedDurationUs.store(dur);  // 【Crossfade】FD open 也缓存时长，否则 getDurationMs=0 导致 monitor 永不触发
                if (sr2 > 0) AMediaFormat_setInt32(configureFormat, AMEDIAFORMAT_KEY_SAMPLE_RATE, sr2);
                if (ch2 > 0) AMediaFormat_setInt32(configureFormat, AMEDIAFORMAT_KEY_CHANNEL_COUNT, ch2);
                if (dur > 0) AMediaFormat_setInt64(configureFormat, AMEDIAFORMAT_KEY_DURATION, dur);
            }
            copyCsdBuffers(configureFormat, format);"""

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
