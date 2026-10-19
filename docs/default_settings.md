# 播放器默认设置记录 (Default Settings Record)

> 来源：从 `SettingsScreen.kt`、`MsebCalculator.kt`、`EqualizerManager.kt`、`LoudnessNormalizer.kt` 源码提取的 **代码默认值**——即全新安装 APK 后、用户未改任何项时，各设置项的初始值。

> 生成时间：2026-10-18。**2026-10-19 已按用户设备导出 (`sdwmp3-settings-20261019-0836.json`，appVersion 9.19) 同步为全新安装默认**——下列「默认值」列即用户当前设备设置。
> SharedPreferences 存储名：`settings` / `sdw_music_prefs` / `mseb` / `equalizer_prefs` / `loudness_norm` / `widget_prefs`。

## 一、设置界面 (SettingsScreen)

### 存储：`sdw_music_prefs`

| Key | 默认值 |
|-----|--------|
| `artist_avatar_enabled` | true |
| `artist_avatar_source` | "auto" |
| `idle_level` | "rare" |
| `immersive_player` | false |
| `lastfm_api_key` | "" |
| `lyric_controls_auto_hide` | true |
| `lyric_font_size` | 28 |
| `lyric_visible_lines` | 7 |
| `min_duration` | 150 |
| `moto_edge_light` | true |
| `section_open_$k` | false |
| `transparent_bg` | true |
| `vu_meter_style` | 1 |

### 存储：`settings`

| Key | 默认值 |
|-----|--------|
| `agc_enabled` | false |
| `auto_play_on_launch` | true |
| `automix_energy_strategy` | "smooth" |
| `bt_pre_emphasis` | false |
| `crossfade_align_prefer` | "quiet" |
| `crossfade_automix` | true |
| `crossfade_bars` | 4 |
| `crossfade_bass_swap` | true |
| `crossfade_cue_entry` | true |
| `crossfade_dj_effect` | "random" |
| `crossfade_dj_enabled` | true |
| `crossfade_dj_template` | "phrase" |
| `crossfade_dj_transition_only` | true |
| `crossfade_enabled` | true |
| `crossfade_keylock` | true |
| `crossfade_landing_mode` | "phrase" (首装经迁移：crossfade_automix 默认 true → phrase) |
| `crossfade_master_sync` | true |
| `crossfade_match_key` | true |
| `crossfade_min_gap_sec` | 25 |
| `crossfade_phrase_bars` | 4 |
| `crossfade_pitch_semi` | 0 |
| `crossfade_roll_rate` | "half" |
| `crossfade_rubberband` | true |
| `crossfade_rubberband_max` | 8 |
| `crossfade_rubberband_phaselock` | true |
| `crossfade_smart_align` | true |
| `crossfade_smartpick` | true |
| `crossfade_solo_band` | "off" |
| `crossfade_stem_fx_mode` | "echo" |
| `crossfade_stem_fx_target` | "vocal" |
| `crossfade_style` | "fade" |
| `crossfade_tempo_adjust` | "tempoblend" |
| `crossfade_tempo_ramp` | true |
| `crossfade_threeband` | false |
| `dac_soft_start` | false |
| `debug_log_enabled` | false |
| `dev_show_dj_advanced` | true |
| `direct_passthrough` | false |
| `engine_dsp_enabled` | true |
| `pll_deadzone_micro` | 200 |
| `pll_gain_hi_pct` | 30 |
| `settings_dj_advanced` | true |
| `shuffle_mode` | (unset → system/none) |
| `stereo_balance` | 0 |
| `tpdf_dither` | true |
| `usb_exclusive` | false |


## 二、MSEB / 双耳空间 (MsebCalculator → `mseb` 存储)

| Key | 默认值 |
|-----|--------|
| `enabled (MSEB 总开关)` | false |
| `temperature` | 0 (flat) |
| `sibilance` | 0 |
| `subBass` | 0 |
| `thickness` | 0 |
| `vocalForward` | 0 |
| `air` | 0 |
| `bassTexture` | 0 |
| `femaleOvertones` | 0 |
| `sibilanceLf` | 0 |
| `sibilanceHf` | 0 |
| `impulseResponse` | 0 |
| `soundstage` | 0 |
| `imaging` | 0 |
| `dts_enabled (DTS 环绕)` | true |
| `dts_surround (环绕强度)` | 7 |
| `dts_height (高度/耳廓感)` | 6 |
| `hrtf_probe_enabled (双耳探针)` | false |
| `hrtf_probe_dir (探针方向)` | -1 (自动轮播) |
| `hrtf_spatial_enabled (真·双耳环绕/HRIR)` | false |
| `hrtf_field_yaw (声场水平)` | 0 |
| `hrtf_field_pitch (声场俯仰)` | 0 |
| `hrtf_field_distance (声场距离 m)` | 5.664635 |
| `hrtf_reverb_enabled (房间混响)` | false |
| `preset_name (预设名)` | "" (手动) |


## 三、均衡器 (EqualizerManager → `equalizer_prefs` 存储)

| Key | 默认值 |
|-----|--------|
| `eq_enabled` | true |
| `eq_preset` | "steven_special" |


## 四、响度归一 (LoudnessNormalizer → `loudness_norm` 存储)

| Key | 默认值 |
|-----|--------|
| `mode` | 2 (AUTO：自动，跟随曲库平均响度) |
| `target_lufs` | -14 |
| `enabled` | false |


---

说明：

- 以上为**代码默认值**。若需让全新安装的 APK 以「你当前设备上的实际设置」为默认，需把对应 key 的默认值改成你的实际值（修改 `SettingsScreen.kt` 中 `defaultValue=` / `getXxx(key, default)`、`MsebCalculator.kt` 默认常量、`EqualizerManager.kt`、`LoudnessNormalizer.kt`）。

- 2026-10-19 已按设备导出 JSON 同步：改动集中在 `settings` 存储（落点模式短语切入、淡入淡出/主同步/三频段等）、`mseb` 存储（`dts_enabled` 开、`hrtf_field_distance`）、`equalizer_prefs`（`eq_enabled` 开、预设 `steven_special`）、`loudness_norm`（`enabled` 关）。`crossfade_landing_mode` 在代码里首装为 null 经老键 `crossfade_automix` 迁移得到 `"phrase"`，故其默认等效为 phrase。

- 取设备真实当前值的最准方式：连接手机后由 `/data/data/com.sdw.music.player.cn/shared_prefs/*.xml` 读取，或使用应用内「设置备份/导出」功能导出后导入。
