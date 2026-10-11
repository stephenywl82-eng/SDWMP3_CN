#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
==========================================================================================
【V8.363 / V8.364】普通模式音频链路的构建期闸门（GUARD: plain-mode audio chain）
==========================================================================================

用户约束（2026-10-03，原话）：
  「今天现在普通模式的音频链 刚刚恢复 以后改千万不能动这个」

本脚本在**打包前**静态检查 MusicService.kt，拦住「新代码把普通模式连坐」这类改动。
今天这类 bug 发生过两次：
  · 8.357 在 crossfadeMonitor 守卫（引擎入口）加了 !cfg.djMode
    → 普通交叉淡化整个消失，用户报「开了开关没功能」
  · 8.360 之前 crossfade_rubberband 的 UI 藏在折叠里、服务侧没检查 DJ 开关
    → 普通模式仍然变速（变速链有两条路径，只关了一条）

为什么必须是构建期而不是运行期：
  这两类 bug 在真机上表现为「听感略有不同」，**很容易被当成正常而放过**。
  等用户报出来时，已经装机、已改版、已积累多个提交。构建期拦住，成本是 0。

三层防护（本脚本是第1 层）：
  1. 构建期静态闸（本脚本）—— 拦住源码层面的连坐
  2. 运行期断言（assertPlainModeAudioInvariants）—— 拦住运行时的意外状态
  3. 回归基线（logcat 指纹）—— 拦住行为层面的变化

退出码：0 = 通过；1 = 检出违规，**必须修完才能打包**。
==========================================================================================
"""
import re
import sys

SRC = r'E:\SDWMP3_CN\app\src\main\java\com\sdw\music\player\core\audio\MusicService.kt'

violations = []
notes = []


def line_of(idx, text):
    return text.count('\n', 0, idx) + 1


def load():
    with open(SRC, 'r', encoding='utf-8', errors='surrogateescape', newline='') as f:
        return f.read()


# --------------------------------------------------------------------------------------
# 规则 1：crossfadeMonitor 守卫只允许 enabled / isDacActive / 播放状态
#          ——它是过渡引擎的入口，加任何新条件都会连坐整个自动交叉淡化
# --------------------------------------------------------------------------------------
def rule1_monitor_guard(raw):
    m = re.search(
        r'if \(!enabled \|\| isDacActive\(\)\) \{', raw)
    if not m:
        # 允许 !isPlaying() 紧跟其后，但守卫本身必须存在且形态正确
        violations.append(
            'R1  crossfadeMonitor 守卫形态变了。\n'
            '     期望： if (!enabled || isDacActive()) {\n'
            '     禁止： 在这里加任何与 DJ 有关的条件（如 !cfg.djMode）——\n'
            '           那是过渡引擎入口，会让普通交叉淡化整个消失（8.357 的教训）。\n'
            '     若确实要改，请先在 memory 里找到 8.359 的记录再决定。')
        return
    # 守卫附近 200 字节内不允许出现 djMode
    seg = raw[m.start():m.start() + 200]
    if 'djMode' in seg or 'crossfade_dj_enabled' in seg:
        violations.append(
            'R1  crossfadeMonitor 守卫里出现了 DJ 判定：\n'
            '     %s\n'
            '     这会让「DJ 开关」变成「过渡引擎总闸」——\n'
            '     DJ 一关，普通交叉淡化直接消失（用户报「开了开关没功能」，8.359 修）。'
            % seg.split('\n')[0].strip())


# --------------------------------------------------------------------------------------
# 规则 2：变速链路两处都必须有 djMode 门控
#          变速有两条路径，只关一条 = 普通模式仍然变速
# --------------------------------------------------------------------------------------
def rule2c_phaselock(raw):
    pass


def rule2_tempo_paths(raw):
    # 2a. applyBeatmatchEngine：决定用哪个引擎
    m = re.search(r'fun applyBeatmatchEngine\(\) \{(.{0,900})', raw, re.S)
    if not m:
        notes.append('R2a applyBeatmatchEngine 未找到（可能被重命名，需人工确认）')
    else:
        body = m.group(1)
        if 'crossfade_rubberband' in body and 'djMode' not in body and 'djOn' not in body \
                and 'dj_enabled' not in body:
            violations.append(
                'R2a applyBeatmatchEngine 直接读 crossfade_rubberband，没有 DJ 门控。\n'
                '     只关引擎不够——beatmatchParams() 仍会算出变速比率。\n'
                '     正确写法见 8.360：val rb = djOn && pref("crossfade_rubberband")')

    # 2b. beatmatchParams：算出真正交给 native 的比率（关键）
    m = re.search(r'fun beatmatchParams\(preSong: Song\?\)(.{0,1200})', raw, re.S)
    if not m:
        notes.append('R2b beatmatchParams 未找到（可能被重命名，需人工确认）')
    else:
        body = m.group(1)
        # 别的判据上，会造成误报（今天就误报了一次）。
        # Strip line comments FIRST: the pref name also appears in the explanatory
        # comment above the code, so a naive substring match fires on the comment
        # and reports a false positive (happened once already today).
        code = chr(10).join(re.sub(r'//.*$', '', ln) for ln in body.split(chr(10)))
        head = code.split('maxPct')[0]          # must precede maxPct
        has_gate = ('return BeatmatchParams(1.0f' in head
                    and ('djOn' in head or 'djMode' in head))
        if 'crossfade_rubberband' in code and not has_gate:
            violations.append(
                'R2b beatmatchParams 读 crossfade_rubberband 却没有 DJ 门控 ——\n'
                '     这是 8.360 修的那个 bug（普通模式仍然变速）。\n'
                '     正确写法：if (!djOn) return BeatmatchParams(1.0f, 0f, 0f, phaseLock)\n'
                '     注意必须放在 maxPct 之前，让 DJ 关闭时连比率都不算。')


    # 2c. phaseLock is the THIRD time-stretch control point (V8.365).
#     8.360 gated the engine (applyBeatmatchEngine) and the ratio
#     (beatmatchParams), but phase lock has its own independent pref
#     (crossfade_rubberband_phaselock) and slipped through to the device:
#       Beatmatch set: enabled=0 (phaselock=1)   <- engine off, PLL still live
#     The PLL kept nudging the rate (PL tuning: gainLo=0.09 gainHi=0.30) and
#     left ~0.69% at landing. Snapping that back to 1.0 in one frame is the
#     "click" the user hears right after the fade ends.
    m = re.search(r"val phaseLock = *([^\r\n]*)", raw)
    if not m:
        notes.append("R2c phaseLock declaration not found (check manually)")
    else:
        expr = m.group(1)
        if "crossfade_rubberband_phaselock" in expr \
                and "djOn" not in expr and "djMode" not in expr:
            violations.append(
                "R2c phaseLock is not gated by the DJ switch: %s\n"
                "     Phase lock is the THIRD independent time-stretch control point\n"
                "     (V8.365). With the engine off and the ratio pinned to 1.0 it\n"
                "     still nudges the rate, leaving ~0.69%% at landing -- audible as\n"
                "     a click right after the fade ends.\n"
                "     Correct: val phaseLock = djOn && cfg.getBoolean(\n"
                "         \"crossfade_rubberband_phaselock\", true)"
                % expr.strip())


# --------------------------------------------------------------------------------------
# 规则 3：所有「会改变声音速度」的 pref，读取处附近必须有 DJ 判定
#          判据：这个 pref 会不会改变速度/音高 → 会则必须门控
# --------------------------------------------------------------------------------------
SPEED_PREFS = [
    'crossfade_rubberband',
    'crossfade_rubberband_max',
    'crossfade_rubberband_phaselock',
]

DJ_SAFE_PREF_NOTE = [
    'crossfade_smartpick','crossfade_automix','crossfade_smart_align',
    'crossfade_landing_mode','crossfade_bass_swap','crossfade_threeband',
    'crossfade_duration_ms','crossfade_bars','crossfade_enabled',
]


def rule3_speed_prefs(raw):
    for pref in SPEED_PREFS:
        for m in re.finditer(r'"%s"' % re.escape(pref), raw):
            ctx_start = max(0, m.start() - 700)
            ctx = raw[ctx_start:m.end() + 250]
            # 【V8.427】豁免通道（脚本原文要求："可加豁免说明、不得删规则"）：
            # 源码处用 `GATE-EXEMPT:` 显式声明"这次读取不改变音频"，并在
            # MusicService.kt 顶部 banner 的警戒线里登记原因，此处放行并留痕。
            # 首个豁免：bpmTierOf() 读 crossfade_rubberband_max 只是借它的档位口径
            # 给候选曲分档，不进 beatmatchParams() / native 变速链路 → 速度音高零影响。
            if 'GATE-EXEMPT' in ctx:
                notes.append(
                    'R3 豁免: pref "%s" (line %d) 已标注 GATE-EXEMPT 并在 banner 登记原因'
                    % (pref, line_of(m.start(), raw)))
                continue
            gated = ('djMode' in ctx or 'djOn' in ctx
                     or 'crossfade_dj_enabled' in ctx)
            if not gated:
                violations.append(
                    'R3  pref "%s" 在 line %d 附近被读取，但上下文没有 DJ 门控。\n'
                    '     它会改变声音速度，必须受 crossfade_dj_enabled 约束。\n'
                    '     判据：会改变「速度/音高」的 pref → 必须门控。'
                    % (pref, line_of(m.start(), raw)))


# --------------------------------------------------------------------------------------
# 规则 4：不得在 MusicService 里新增「把 DJ 开关当总闸」的写法
#          （形如把 djMode 拼进 enabled 的判断、或整体短路整个子系统）
# --------------------------------------------------------------------------------------
def rule4_no_master_gate(raw):
    bad = [
        (r'if \(!enabled \|\| !cfg\.djMode', '把 djMode 拼进 enabled 判断（8.357 误伤原版）'),
        (r'if \(!crossfadeEnabled \|\| !cfg\.djMode', '把 djMode 拼进 crossfadeEnabled 判断'),
        (r'if \(!cfg\.djMode \|\| !enabled', '把 djMode 拼进 enabled 判断（顺序变体）'),
    ]
    for pat, why in bad:
        for m in re.finditer(pat, raw):
            violations.append(
                'R4  line %d 命中「DJ 当总闸」写法：%s\n'
                '     后果：DJ 关闭会连坐普通交叉淡化（8.357 → 8.359 的事故）。'
                % (line_of(m.start(), raw), why))


# --------------------------------------------------------------------------------------
# 规则 5：警戒线 banner 必须在（防止后人误删）
# --------------------------------------------------------------------------------------
def rule5_banner_present(raw):
    if 'V8.363 最高优先级' not in raw:
        violations.append(
            'R5  MusicService.kt 顶部的「普通模式只读」警戒线 banner 不在了。\n'
            '     那段注释是用户立的约束（2026-10-03），删掉等于丢掉这条护栏。')


def main():
    raw = load()
    rule1_monitor_guard(raw)
    rule2_tempo_paths(raw)
    rule2c_phaselock(raw)
    rule3_speed_prefs(raw)
    rule4_no_master_gate(raw)
    rule5_banner_present(raw)

    print('==> [GUARD] 普通模式音频链路闸门')
    if notes:
        for n in notes:
            print('    · 提示: %s' % n)
    if not violations:
        print('    OK  5 条规则全部通过（monitor 守卫 / 变速双路径 / 速度类 pref / 总闸写法 / 警戒线）')
        return 0

    print('')
    print('    !!!!! 检出 %d 处可能连坐普通模式的改动 !!!!!' % len(violations))
    print('')
    for i, v in enumerate(violations, 1):
        print('  --- 违规 %d ---' % i)
        for line in v.split('\n'):
            print('  %s' % line)
        print('')
    print('  判据：djMode == false 时的行为，必须与 8.362 之前的老播放器完全一致。')
    print('  详见 memory/2026-10-03.md 的 8.359 / 8.360 / 8.363 记录。')
    print('  如果你确信这次改动是安全的，在 MusicService.kt 顶部 banner 里加一行豁免说明，')
    print('  并在本脚本对应规则处标注原因 —— 不允许直接删规则。')
    return 1


if __name__ == '__main__':
    sys.exit(main())
