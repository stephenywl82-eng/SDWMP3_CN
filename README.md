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


---

## 通俗解读（给非技术朋友看）

## 一句话定位

**普通播放器，是「把你手机里的歌，经过系统加工后放出来」；这个播放器，是「帮你把手伸进 USB 耳放，把音乐原封不动地喂进去」。**

它不是「音乐 App」，是「USB DAC 的驱动 + 解码 + 调音」一体化的发烧工具。牺牲了曲库和在线功能，换来更接近录音室原声的干净声音。

---

## 一、音质：为什么它放出来「更接近原声」

**核心矛盾就一个：手机默认会「擅自加工」你的音乐。**

你手机里的 FLAC 无损，假设是 44.1kHz 采样率（CD 标准）。但手机系统内部的音频引擎，默认只认 48kHz，于是它会偷偷把 44.1k 的音频「换算」成 48k 再放出来。

打个比方：

> 你拍了一张 4K 高清照片，系统为了省事，先把它压缩成 1080p，再拉伸回 4K 给你看。分辨率数字没变，但细节已经丢了。

这个「换算」过程叫 **重采样（SRC）**，是所有普通播放器（包括 QQ 音乐、网易云）都在干的事。它引入的失真虽然小，但对发烧友来说，就是「数码味」「不够干净」的来源。

**这个播放器做的事，就是绕过这个加工环节。**

它自己写了一套 USB 驱动，直接跟你的 USB 解码耳放（DAC）对话，音乐数据一个比特都不改，原样送出去。这叫 **Bit-Perfect（位完美）**：44.1k 就按 44.1k 出，96k 就按 96k 出，16bit/24bit/32bit 各是各的，绝不动一个比特。

**一句话：普通播放器是「翻译」你的音乐，它是在「原样搬运」你的音乐。**

### 音质上几个「较真」的细节

- **舍入误差**：24bit 打包时，普通做法「直接砍小数」会让负半周波形整体偏一点点（-0.5 LSB 直流偏置），长期听会发死。它用银行家舍入（lrintf），四舍六入五取偶，消除偏置。
- **抖动（Dither）**：24bit 塞进 16bit 时，硬砍会出「量化毛刺」，它加极微小的随机噪声（TPDF 抖动）把毛刺抹匀，听感更顺滑。而且只在「降位」时才加，16bit 转 16bit 绝不画蛇添足。
- **硬件音量**：普通播放器调音量是在软件里「削」数字信号，损失动态范围；它发现 DAC 自带音量芯片（Feature Unit）时，直接调 DAC 的硬件音量，信号全程不衰减。

---

## 二、播放架构：一条「不排队、不卡顿」的流水线

普通播放器的数据要经过好几层（App → 系统混音器 → 声卡驱动 → 耳机），每一层都可能插一脚。这个播放器把关键链路收拢成自己的一条流水线：

```
音乐文件 → 解码 → 环形缓冲区 → 播放核心 → USB DAC（耳机）
```

**环形缓冲区（Ring Buffer）** 是整个架构的「蓄水池」：

> 想象一个 740 毫秒的「水库」。上游解码器往里面注水，下游 DAC 匀速放水。就算解码偶尔快一下慢一下，水库里有存货，耳机这边就永远不断流、不卡顿。

而且这个水库是 **无锁（lock-free）** 的——上游注水、下游放水互不干扰，不需要排队等锁，延迟极低。

**精确时钟**：44.1kHz 意味着每秒要精确输出 44100 个采样点。它用「相位累加器」做小数帧分配（一会儿 5 帧、一会儿 6 帧交替），长期平均精确锁死在 44100Hz，不会越播越快或越慢。

**实时优先级**：播放线程优先级调到系统最高（-19），别的 App 再卡，音乐这条线也优先跑，减少爆音。

### 三层「路由」，按 DAC 聪明选择

它不搞一招鲜，而是看设备是什么 DAC，走不同的路：

1. **已知 DAC（TTGK、Realtek）** → 走自己手写的 USB 独占通道（Bit-Perfect，最强）
2. **未知 DAC** → 走系统 Oboe/AAudio 直连（兜底，也能绕过部分重采样）
3. **兜底** → 只做系统媒体卡片的「外壳」，不实际发声

**ExoPlayer 被彻底删了**——主流播放器用 ExoPlayer 当播放核心，它已弃用，因为这层东西跟 Bit-Perfect 的目标冲突。

---

## 三、解码特点：格式怎么「解」出来的

| 格式 | 解码方式 | 为什么 |
|------|---------|--------|
| FLAC | libFLAC 硬解（写进 .so，C++ 层） | 不经过系统解码器，避免系统把 24bit 擅自转成 16bit 造成字节错位、噪音 |
| WAV | dr_wav 直读 | 原生无损，直接读 |
| MP3 / AAC / M4A / Opus / OGG | MediaCodec 系统解码 | 有损格式，解出来就是有损，无所谓 |
| DSD | ❌ 不支持播放 | 只识别格式标签，解码层没有 DSD 解码器 |

**FLAC 硬解是解码上最关键的一步。** 系统默认的 MediaCodec 解码器，会把 24bit FLAC 强行转成 16bit，这中间「三个字节装进两个字节」的错位，正是很多人用普通播放器放 24bit 无损时听到「左声道杂音、右声道跑调」的元凶。它用 libFLAC 在 native 层直接解，从源头避开这个坑。

---

## 四、32 位浮点全链路：从头到尾「不落地」

这是专业录音软件（DAW）和顶级 HiFi 播放器的标配架构。整条链路从解码到输出前，数据始终保持在 32 位浮点域，绝不中途落地成整数：

```
解码 → float 环形缓冲 → float 域 EQ/音量 → 最后一刻才量化成 16/24/32bit 送 DAC
```

好处：中间每一步都不丢精度、不产生累积误差、不会中途削顶；只有最后交给 DAC 那一刻才做一次「浮点 → 整数」转换。与 Salt Player 的「32 位浮点引擎」同一档架构。

---

## 附加亮点

- **MSEB 心理声学 EQ**：不是调频率增益，而是调「温度 / 厚度 / 人声远近 / 空气感 / 瞬态」等主观听感维度，11 维映射到 5 段 native Biquad。
- **9 种语言**：简体 / 繁体 / English / Español / 日本語 / 한국어 / Français / Português / हिन्दी，点一下循环切换，无需重启。
- **封面系统**：iTunes + MusicBrainz 双源下载，FLAC 写 PICTURE block、MP3 写 ID3v2 APIC 帧嵌入。
- **歌词系统**：.lrc → FLAC 内嵌 → 在线 LRCLIB 四级优先级，支持编辑并内嵌回音频。
- **无缝切歌 / 睡眠定时 / 灭屏播放 / 音频焦点 / 系统级删除**。

---

## 短板（客观说明）

- 纯本地播放器，无曲库、无在线功能，跟流媒体体验完全不同。
- DAC 兼容性靠「磨型号」，目前调通的已知 DAC 型号有限。
- 不支持 DSD 播放（仅识别标签）。
- 异步 DAC 的 feedback 闭环尚未接入（目前纯开环扛着，真·XMOS/Amanero 异步 DAC 才需要）。
- 单机自研，成熟度不如商业产品，音质由用户耳朵验收。

---

*对标 Salt Player / USB Audio Player Pro 这一类专业 HiFi 播放器，而非 QQ 音乐 / 网易云。*
