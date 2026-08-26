# -*- coding: utf-8 -*-
# 修复 usb_audio_driver.cpp 300 行 LOGI 中文乱码（锟斤拷 → 正常中文）
import io

F = r"E:\SDWMP3_CN\app\src\main\cpp\usb_audio_driver.cpp"
with io.open(F, "r", encoding="utf-8", newline="") as f:
    text = f.read()
text = text.replace("\r\n", "\n")

old = 'LOGI("selectAltForRate(r=%d bits=%d ch=%d) 锟斤拷 alt=%d (candidates=%zu, ranges=%zu)",'
new = 'LOGI("selectAltForRate(r=%d bits=%d ch=%d) -> alt=%d (candidates=%zu, ranges=%zu)",'
c = text.count(old)
print("match:", c)
if c == 1:
    text = text.replace(old, new, 1)
    with io.open(F, "w", encoding="utf-8", newline="") as f:
        f.write(text.replace("\n", "\r\n"))
    print("DONE")
else:
    print("SKIP")
