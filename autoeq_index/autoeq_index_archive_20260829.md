# AutoEQ 完整索引存档 — 2026-08-29

## 已保存（下次扩展预设直接用）
1. **`C:\Users\Administrator\.qclaw\workspace-agent-aa5cbafd\autoeq_all_paths.json`**
   - 8827 个 `ParametricEQ.txt` 完整路径（相对仓库根，如 `results/oratory1990/over-ear/...`）
   - 来自 git ls-tree HEAD（真实存在，非猜测）

2. **`C:\Users\Administrator\.qclaw\workspace-agent-aa5cbafd\autoeq_models_bestpath.json`**
   - 6015 个型号名 → 该型号所有路径列表（按源优先级排序：oratory1990 > crinacle > Rtings > 其他）
   - 下次加预设：按型号名查此文件拿最佳路径 → git show 取内容

3. **数据缓存** `autoeq_data\`：54 个已下载 txt（含 44 款已替换 + 重复）
4. **git 仓库** `autoeq_repo\`：sparse clone（blob:none），`git -C autoeq_repo show HEAD:<path>` 可随时取任何文件内容，不受 API 限流/被墙影响

## 源分布（23 个测量源）
crinacle 2031 / Innerfidelity 933 / Super Review 884 / Rtings 792 / oratory1990 734 / ToneDeafMonk 431 / Jaytiss 376 / Headphone.com Legacy 329 / Hi End Portable 301 / Kuulokenurkka 261 / HypetheSonics 228 / RikudouGoku 200 / Regan Cipher 193 / Harpo 184 / Filk 177 / Fahryst 173 / kr0mka 154 / Kazi 142 / DHRME 99 / Auriculares Argentina 85 / freeryder05 65 / Bakkwatan 40 / Ted's Squig Hoard 15

## 下载方法经验（重要）
- raw.githubusercontent.com：**被墙**（Python 卡 TCP 连接 → SIGKILL）
- GitHub API contents：未认证限流 60 次/小时，树遍历很快用光；等 reset 或要认证 token
- jsdelivr CDN（cdn.jsdelivr.net/gh/...）：**可直连**，但路径必须精确（Bose 在 Filk 源不是 oratory1990，容易 404）
- **最可靠：git sparse clone（--depth 1 --filter=blob:none --sparse）→ `git ls-tree -r --name-only HEAD` 拿全量真实路径 → `git show HEAD:<path>` 取内容**，git 协议不走 API 配额

## 当前 AutoEQ 预设状态
- AutoEqPresetManager.kt：46 款预设，44 款官方实测 + Marshall Monitor III / OnePlus Buds Pro 3 手写
- 编译装机完成（BUILD SUCCESSFUL 3m2s，装 Neo ZY22KG2WMQ v8.7）
- 用户验收听感中，通过后出 R8 release
