#!/usr/bin/env bash
# ===========================================================================================
# 【V8.364】普通模式音频链路· 真机回归基线检查
# ===========================================================================================
# 用法： bash tools/check_plain_mode.sh
#
# 前提：手机连着 adb，且当前设置 = DJ 切歌模式【关】+ 交叉淡化总开关【开】。
#       先播一首歌，让它自然走完一次自动交叉淡化（或手动按下一首几次）。
#
# 做什么：把真机 logcat 里的关键指标与 8.362 基线逐条对照。
#基线是用户确认「普通模式音频链刚刚恢复」那一刻的实测值。
# ===========================================================================================
set -u
export PATH="/e/Android/Sdk/platform-tools:$PATH"

LOG=$(mktemp)
adb logcat -d -v time > "$LOG" 2>/dev/null

PASS=0
FAIL=0
ok()   { echo "    OK   $1"; PASS=$((PASS+1)); }
bad()  { echo "    FAIL $1"; FAIL=$((FAIL+1)); }

echo "==> [REGRESSION] 普通模式音频链路基线对照（基线 = 8.362）"

# ---- 1. 引擎侧必须全部关闭 -------------------------------------------------------
for k in "Beatmatch set: enabled=0" "LiveFx enabled=0" "Crossfade tpl=0"; do
  N=$(grep -c "$k" "$LOG" 2>/dev/null | head -1 | tr -d " \r"); N=${N:-0}
  if [ "$N" -gt 0 ]; then
    if grep "$k" "$LOG" | grep -qE "enabled=[1-9]|tpl=[1-9]"; then
      bad "$k   —— 检出 DJ 能力被启用（应全部为 0）"
    else
      ok "$k  ($N 条)"
    fi
  else
    echo "    --   $k  (本轮日志无此记录，可能还没触发过渡)"
  fi
done

# ---- 2. 运行期断言必须没有报警 ---------------------------------------------------
N=$(grep -c "PLAIN-MODE VIOLATION" "$LOG" 2>/dev/null | head -1 | tr -d " \r"); N=${N:-0}
if [ "$N" -gt 0 ]; then
  bad "PLAIN-MODE VIOLATION x$N —— 普通模式被污染"
  grep "PLAIN-MODE VIOLATION" "$LOG" | tail -3 | sed 's/^/         /'
else
  ok "无 PLAIN-MODE VIOLATION（V8.364 运行期断言干净）"
fi

# ---- 3. 落点后的位置必须用算术值，不得采纳解码器位置 ------------------------------
N=$(grep -c "adopt real decoder" "$LOG" 2>/dev/null | head -1 | tr -d " \r"); N=${N:-0}
if [ "$N" -gt 0 ]; then
  bad "adopt real decoder x$N —— 8.362 已删除该逻辑，出现即回退"
else
  ok "无 adopt real decoder（位置只用算术值）"
fi

# ---- 4. 音频健康度 ---------------------------------------------------------------
UNDER=$(grep -oE "underrun=[0-9]+" "$LOG" 2>/dev/null | cut -d= -f2 | sort -n | tail -1)
SHORTA=$(grep -oE "shortA=[0-9]+" "$LOG" 2>/dev/null | cut -d= -f2 | sort -n | tail -1)
SHORTB=$(grep -oE "shortB=[0-9]+" "$LOG" 2>/dev/null | cut -d= -f2 | sort -n | tail -1)
echo "    --   underrun 最大值=${UNDER:-N/A}  shortA=${SHORTA:-N/A}  shortB=${SHORTB:-N/A}"
if [ -n "${SHORTB:-}" ] && [ "$SHORTB" -gt 0 ]; then
  bad "shortB > 0 —— B 轨有缺样"
else
  ok "B 轨无缺样"
fi

# ---- 5. 硬切耗时（V8.355 埋的逐项计时）-------------------------------------------
SLOW=$(grep -oE "hard cut \[[0-9]+ms\]" "$LOG" 2>/dev/null | grep -oE "[0-9]+" | sort -n | tail -1)
if [ -n "${SLOW:-}" ]; then
  if [ "$SLOW" -gt 200 ]; then
    bad "硬切最慢一步 ${SLOW}ms（基线 0~3ms）"
  else
    ok "硬切最慢一步 ${SLOW}ms（基线 0~3ms）"
  fi
else
  echo "    --   无硬切记录（本轮没切歌）"
fi

# ---- 6. 蓝牙（今天发现的干扰源）--------------------------------------------------
BT=$(adb logcat -d 2>/dev/null | grep "OutputDeviceBar" | tail -1 | grep -oE "btActive=[a-z]+" | cut -d= -f2)
if [ "${BT:-false}" = "true" ]; then
  bad "蓝牙处于活动状态 btActive=true —— 今天验证过蓝牙会让 underrun/缺样暴涨，请断开蓝牙后重测"
else
  ok "蓝牙未连接（btActive=${BT:-false}）"
fi

rm -f "$LOG" 2>/dev/null
echo ""
echo "    通过 $PASS 项，失败 $FAIL 项"
[ "$FAIL" -eq 0 ] || exit 1
