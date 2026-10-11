# STORY.md — MSEB 自定义调节 · 图文攻略 PPT

## ① 用户意图对齐
- **目标受众**：SDW 播放器的普通用户 / 耳机发烧友，发在社群、官网 FAQ、应用商店说明里看。
- **核心目标**：让用户 3 分钟内看懂「MSEB 是什么、怎么打开、13 段滑块管什么、怎么调出自己想要的声音」，并愿意上手试。
- **PPT 长度**：10 页（Hero 配额 3：封面 / 核心思路 / 结束页）。
- **视觉调性**：简洁科技感 · 深色背景 · 蓝青强调色 · 抽象几何。
- **内容边界**：只讲 App 内真实存在的 MSEB 能力（对齐 mseb_* 字符串），不编造功能；不写工程内部实现。

## ② 页面布局骨架
- 总页 10。无正式章节扉页（攻略型 deck，用连续的「核心概念→滑块→模块→场景→排错」叙事流替代目录/扉页契约）。
- Hero：01 封面、03 核心思路、10 结束页（占比 30%，互不相邻）。
- 节奏：01 peak → 02 valley → 03 peak → 04–09 valley/supporting → 10 peak。
- 非对称版式占比：01/03/05/07 为非对称 + 02/06/09 左标题右内容 + 04/08 表格，满足 ≥40%。
- 对称版式（N卡片横排）全篇 0 页，规避上限。

## ③ 页面大纲
| # | title | type | role | rhythm | layout | visual | visual_role | density | anti_pattern |
|---|---|---|---|---|---|---|---|---|---|
| 01 | MSEB 自定义调节 | cover | hero | peak | 全幅渐变+骑线大标题 | L1 光斑+巨型13 | anchor | 30字/1图 | 禁止装饰小贴片 |
| 02 | 怎么打开 MSEB | content | supporting | valley | 左标题+右内容 | 3 步骤图标 | evidence | 160字/3图 | 禁止等宽卡片 |
| 03 | 它调的是「听感维度」 | content | hero | peak | 非对称双栏(60:40) | 巨型概念字 | anchor | 140字/1图 | 禁止四卡片预览 |
| 04 | 13 段滑块一览 | content | supporting | valley | 图表+洞察(表) | 13行表 | evidence | 320字/表 | 禁止正文堆叠 |
| 05 | 重点滑块怎么调 | content | supporting | valley | 非对称双栏(55:45) | 滑块详解卡 | anchor | 240字/1卡 | 禁止等分双栏 |
| 06 | 进阶模块 | content | supporting | valley | 左标题+右内容(列表) | 9 模块图标 | evidence | 300字/9图 | 禁止等宽卡片 |
| 07 | 预设与保存 | content | supporting | valley | 非对称双栏(50:50) | BitPerfect/命名 | evidence | 180字/2图 | 禁止等分上下 |
| 08 | 按场景抄作业 | content | supporting | valley | 图表+洞察(表) | 场景表 | evidence | 300字/表 | 禁止正文堆叠 |
| 09 | 排错 & 小贴士 | content | supporting | valley | 左标题+右内容 | 4 FAQ 图标 | evidence | 220字/4图 | 禁止等宽卡片 |
| 10 | 调出你的声音 | ending | hero | peak | 居中金句+光斑 | L1 光斑 | anchor | 30字/1图 | 禁止装饰小贴片 |

## 数据落点（关键页）
- 03：传统 EQ 是「100Hz +3dB」工程语言 → MSEB 改成「冷↔暖」听感词，不懂 Hz 也能调。
- 04：13 段滑块 = 13 个听感维度，每段两端是反义词（如 Cool↔Warm），推哪边声音就往那走。
- 08：7 类场景直接给「抄作业」配方，降低上手门槛（通勤/有线HiFi/睡前/齿音重/人声/现场/游戏）。
