# 节拍网格 / DJ 混音：现状盘点与落地方案

> 目的：为交叉淡化与 DJ 混音的下一阶段提供依据。
> 方法：逐条核对本项目代码，并与 Mixxx（开源 DJ 标杆）的 Beats 数据模型做对照。
> 结论先行：**本项目并不缺「节拍网格」，缺的是「小节内位置（downbeat）」和「量化时是否走动态 grid」这两处精度问题。**

---

## 0. 对上一轮判断的更正

先前依据"DJ 相关文件都是 10/12 快照才入库"推断"缺少 beat grid"，**这个结论是错的**。
核实后：native 侧早已产出动态节拍网格，`MusicService` 也已经在用它算触发延迟。本轮重新盘点如下。

---

## 1. 现在已有什么（附代码位置）

| 能力 | 位置 | 现状 |
|------|------|------|
| BPM / 首拍 / 置信度 / 副歌 / 调性 | `nativeDetectBpmFromFdEx` → `int[]{bpm, firstBeatMs, confPct, chorusMs, keyCode}` | ✅ 已有，`keyCode` 已转 Camelot(`8A`/`8B`) |
| **动态 beatgrid（变速）** | `BpmScanner` 解析"段数 N + N 组 `{bpm, firstBeatMs, startMs}`"，存 `BpmKeyCache.grid` | ✅ 已有（等价于 Mixxx 的 BeatMap） |
| 浮点 BPM | `BpmKeyCache.Entry.bpmF`（`bpmM = bpm*1000` 持久化） | ✅ 已有，避免整数 BPM 长曲漂移 |
| 曲式结构 / 分段 | `BpmKeyCache.getStructure()` → `sections` | ✅ 已有 |
| 波形缓存 | `WaveformCache` | ✅ 已有 |
| **A 轨触发延迟用动态 grid** | `MusicService` 约 1068 行：`gridBeatAt(path, pos)` 取 pos 所在段 → 该段 BPM + 绝对相位 | ✅ 已用 |
| B 轨入场点优先级 | `MusicService.resolveBEntryMs()`：人声第一拍(V8.472) → cue 首段 → drop/副歌/首拍 | ✅ 已用，且手动 ⏭ 与自动过渡口径已统一 |
| 过渡效果选择 | `TemplatePicker`：按 BPM 差 / 时长 / 调性 / 能量选，取代八选一随机 | ✅ 已用 |
| 时间拉伸 | Rubber Band（`crossfade_rubberband`） | ✅ 已有 |
| Master Sync | `MasterSyncShadow` | ⚠️ **仅影子模式**（只观测解算，不改播放行为） |

---

## 2. 与 Mixxx 的数据模型对照

Mixxx `src/track/beats.h` 的模型：

- 结构是 **0~N 个 `BeatMarker` + 恰好 1 个 tempo marker（最后一个）**
- 每个 `BeatMarker` = `{ position（位置）, beatsTillNextMarker（到下一个 marker 还有几拍） }`
- **恒定速度**：0 个 marker，最后一个 marker 落在第一拍（downbeat）上，BPM 恒定
- 版本宏：`BeatGrid-1.0` / `BeatGrid-2.0` / `BeatMap-1.0`

本项目的 grid = **N 段 `{bpm, firstBeatMs, startMs}`**，也就是"若干恒定速度段落的拼接"，与 Mixxx 的 BeatMap 思路一致。

**关键差异**：Mixxx 每个 marker 额外存了 `beatsTillNextMarker`（到下一 marker 的拍数）。
有了它才能知道"某一拍在这一小节的第几拍"，从而**准确判断 downbeat（小节第一拍）**。
本项目只存了"段起点绝对时间 + 该段 BPM"，**没有拍号/小节信息** —— 于是所有"吸附到小节"的计算都隐含假设 **4/4 拍**。

---

## 3. 真正的缺口（按优先级）

### G1（中）量化与入场点吸附仍按「恒定 BPM」算

- 证据：`SongStructureAnalyzer.quantizeTriggerUpToBar(idealStartMs, firstBeatMs, bpmF, maxSnapMs)` —— 形参就是**单一** `firstBeatMs` + 单一 `bpmF`，内部 `barMs = 4 * 60000 / bpmF`，再 `floor((ideal - firstBeat)/barMs)`。
- 而动态 grid **只**用于 A 轨触发延迟（`MusicService` 1068 行 `gridBeatAt`）。
- 后果：变速曲、或检测到的 BPM 有轻微误差时，过渡越长、累积错位越明显（30 秒过渡 + 0.5% BPM 误差 ≈ 150ms 漂移，听感上就是"没对上"）。

### G2（中）没有 downbeat / 拍号信息

- 证据：grid 每段只有 `{bpm, firstBeatMs, startMs}`，无 `beatsPerBar`、无 `downbeatOffsetMs`。
- 后果：「吸附到拍点」只能吸附到"某一拍"，无法保证是**小节第一拍**；3/4 拍或 6/8 拍的曲子会吸到错误的位置。Mixxx 靠 `beatsTillNextMarker` 解决这一点。

### G3（低优先级 / 长期）Master Sync 未落地

- `MasterSyncShadow` 自述"只做观测与解算，不改变任何播放行为"，用于验证"跨曲目 master 相位连续在数学上是否可行"。
- 这是有意的阶段性设计，不建议在 P1/P2 完成前动它。

### 已排除的项（不要重复做）

- BPM 检测、调性、副歌/能量点、cue 点、波形 —— 都已具备且已接入。
- **不建议引入 Essentia(AGPL-3.0) / aubio(GPL-3.0)**：自研检测已满足需求，引入会给"自用/本地分发 → 将来上架"带来许可证负担。

---

## 4. 分阶段落地方案

### P1：让量化与吸附统一走动态 grid（改动小、收益明确）

1. 新增 `gridQuantize(path, idealStartMs)`：先用 `gridBeatAt` 取 `idealStartMs` 所在段的 `{段 BPM, 段首拍}`，在该段内做 `floor` 量化（沿用现有的"只往更早取、绝不取更晚"约束）。
2. `quantizeTriggerUpToBar` 保留为"无 grid 时的退化路径"（grid 为空仍走原逻辑）。
3. B 轨入场点吸附同样改走 `gridBeatAt(B 路径, entryMs)`。
4. 观察日志：现有 `Crossfade: B starts at ${bEntryMode} ${ms}ms (floor, grid-snapped)` 增加段号与段 BPM 输出，便于核对。

### P2：补 downbeat / 拍号

1. native 侧每段输出扩展为 `{bpm, firstBeatMs, startMs, beatsPerBar, downbeatOffsetMs}`（先固定按 4/4 输出，为后续 3/4 留字段）。
2. `BpmKeyCache` 的 grid 序列化（现在是 `JSONArray` 每行 3 个 int）扩到 5 个，**并做版本兼容**：读到 3 个字段的旧数据时 `beatsPerBar` 默认 4。
3. `quantizeTriggerUpToBar` 增加"吸附到小节第一拍"模式（原来只能吸附到拍），配合 `crossfade_align_prefer=beat` 使用。

### P3：Master Sync 从影子转真实

- 先用 `MasterSyncShadow` 积累的数据判断可行性（相位误差是否落在可用范围），达标后再设计 native 落点交棒；在此之前**不改播放行为**。

---

## 5. 验收方法

- **变速曲用例**：找一首明显变速（或分段 BPM 不同）的曲子，对比改造前后 `Crossfade` 日志里的触发点与真实拍点偏差。
- **3/4 拍用例**：P2 完成后验证能否吸附到小节第一拍（而非任意拍）。
- **回归**：恒定速度曲（占绝大多数）行为必须与改造前完全一致 —— 量化结果不变，只看日志新增字段。

---

## 6. 参考

- Mixxx `src/track/beats.h`：`BeatMarker{position, beatsTillNextMarker}` + 一个 tempo marker；`BeatGrid-1.0/2.0`、`BeatMap-1.0`。
- Mixxx AutoDJ / key detection（Camelot）用于后续和声混音时再参考。
- 许可证提示：Essentia 为 AGPL-3.0（对闭源不友好），aubio / TarsosDSP 为 GPL-3.0；当前方案**不引入**它们。
</content>
