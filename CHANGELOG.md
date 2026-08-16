# 更新日志 Changelog

## 8.0 (2026-08-16)

### 新增
- **多语言支持**：设置内新增语言切换，支持 6 种语言（中文 / English / Español / 日本語 / 한국어 / Français），点一下即切，后续语言可扩展
- **应用更名**：应用名由「Moto 音乐」升级为 **Moto Music Premium**

### 优化
- **USB DAC 硬件音量**：对齐 Salt Player 实测适配，硬件 Feature Unit 衰减走通（TTGK 33C0 / Realtek 4BA6），不损 bit-depth 动态范围
- **无缝切歌**：修复 48k 歌曲切换卡顿 + 电流音（EOS 排空循环加 gRunning 判断、claim vs stream 状态分支修正）
- **主界面标题自适应**：长标题自动省略号截断，不再被操作按钮遮挡

### 修复
- 切歌多次闪退（MediaCodec looper 冲突 / 解码线程未退出即删底层对象）
- 同采样率切歌杂音（stopDecode 后 native 流指针错位）
- 电话/视频中断后音乐自动恢复（统一请求音频焦点）
- 固定媒体卡片播放/暂停按钮失效（playerCommands 缺失 PLAY_PAUSE）

---

## 7.2 及更早

（历史版本从略）
