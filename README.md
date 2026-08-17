# Moto Music Premium（中文版 / 多语言）

> Android 高保真本地无损音乐播放器，主打 **USB DAC 独占直通** 的 Bit-Perfect 输出。

## 定位

面向发烧友的本地音乐播放器，核心是绕过 Android 系统 SRC 重采样，把手写 USB Host API 直连 DAC，实现无损、逐比特的输出。非 DAC 场景走 Oboe/AAudio 直连，全程不经过系统混音器。

## 主要功能

- **USB DAC 独占输出**：手写 USB Host API（USBDEVFS_CLAIM + ISO URB），44.1k/48k 双时钟切换，支持 16/24/32-bit
- **Oboe / AAudio 直连**：未知 DAC 的默认路径，由内核 USB 驱动处理 quirks
- **多语言**：9 种语言运行时切换（简中 / 繁中 / English / Español / 日本語 / 한국어 / Français / Português / हिन्दी）
- **MSEB 心理声学 EQ**：10 维主观参数 → 5 段 native Biquad，A/B 对比
- **FLAC 内嵌歌词**：读写 Vorbis Comment `LYRICS` 字段，支持 Embedded 歌词来源
- **封面嵌入工具**：iTunes API 优先 + MusicBrainz Cover Art Archive 兜底，FLAC PICTURE block / MP3 ID3v2 APIC 帧写入
- **无缝切歌（Gapless）**、睡眠定时器、A-Z 快速索引、折叠屏分屏布局

## 技术架构

```
文件 → 解码 → Ring Buffer(32768 帧 lock-free) → 播放核心 → 输出
```

### 输出路径（三层）

| 路径 | 说明 |
|------|------|
| Path 1 | USB Host Exclusive Bit-Perfect（已知 DAC：TTGK 33C0 / Realtek 4BA6） |
| Path 2 | Oboe System Route（未知 DAC 默认，内核 USB 驱动处理 quirks） |
| Path 3 | SimpleBasePlayer 兜底（仅做 MediaSession 外壳） |

### 解码层

- **FLAC** — libFLAC 硬解（编入 `.so`）
- **WAV** — dr_wav
- **MP3 / AAC / M4A / Opus / OGG** — MediaCodec

### Native 层（liboboe_bridge.so）

- `usb_audio_driver.cpp` — USB 音频驱动：12 URB 预队列 + phase accumulator（5/6 帧交替精确 44100Hz），16-bit TPDF 抖动
- `biquad_filter.h` — 5 段 double 系数均衡器
- `flac_decoder_jni.cpp` — FLAC 硬解 + 内嵌歌词读取

### 架构模式

MVI + Hilt DI + StateFlow + Media3 MediaSession。

## 构建

```bash
# Debug（快速验证）
./gradlew assembleDebug

# Release（R8 混淆，约 20-25 分钟）
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

> 注意：C++ 改动后 CMake 被注释，需手动编译脚本重新生成 `liboboe_bridge.so`。

## 版本

- `versionName`：8.0
- `versionCode`：16
- `applicationId`：`com.sdw.music.player.cn`

## 许可

自用 / 本地分发。含 `MANAGE_EXTERNAL_STORAGE` 权限（用于歌词/封面写入共享存储），若上架 Google Play 需评估审核风险。
