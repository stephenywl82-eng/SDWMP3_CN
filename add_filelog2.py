# -*- coding: utf-8 -*-
import io, sys
sys.stdout.reconfigure(encoding='utf-8')

def patch(fp, pairs):
    t = io.open(fp, 'r', encoding='utf-8', newline='').read()
    for old, new in pairs:
        if old in t:
            t = t.replace(old, new)
            print('OK  %s' % fp.split('\\')[-1])
        else:
            print('MISS %s' % fp.split('\\')[-1])
    io.open(fp, 'w', encoding='utf-8', newline='').write(t)

# 1. 删掉 getCurrentPosition 每秒刷屏埋点（tick 埋点已够）
patch(r'E:\SDWMP3_CN\app\src\main\java\com\sdw\music\player\core\audio\MusicService.kt', [
    ("""    fun getCurrentPosition(): Long {
        com.sdw.music.player.FileLog.log("SVC", "getCurrentPosition: oboePrepared=${oboeDirectPlayer?.isPrepared} isOboe=${isOboeDirectMode()}")
        // [V8.x] USB DAC: audible position = decode position - ring buffer depth""",
     """    fun getCurrentPosition(): Long {
        // [V8.x] USB DAC: audible position = decode position - ring buffer depth"""),
])

# 2. PlayerViewModel positionMs/durationMs collect 埋点（仅前3次，防刷屏）
patch(r'E:\SDWMP3_CN\app\src\main\java\com\sdw\music\player\ui\viewmodel\PlayerViewModel.kt', [
    ("""        viewModelScope.launch {
            try {
                connection.currentPositionMs.collect { pos ->
                    _positionMs.value = pos
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "currentPositionMs collect failed", e)
            }
        }
        viewModelScope.launch {
            try {
                connection.durationMs.collect { dur ->
                    _durationMs.value = dur
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "durationMs collect failed", e)
            }
        }""",
     """        viewModelScope.launch {
            try {
                var n = 0
                connection.currentPositionMs.collect { pos ->
                    if (n < 5) { com.sdw.music.player.FileLog.log("VM", "positionMs=$pos"); n++ }
                    _positionMs.value = pos
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "currentPositionMs collect failed", e)
            }
        }
        viewModelScope.launch {
            try {
                var n = 0
                connection.durationMs.collect { dur ->
                    if (n < 5) { com.sdw.music.player.FileLog.log("VM", "durationMs=$dur"); n++ }
                    _durationMs.value = dur
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "durationMs collect failed", e)
            }
        }"""),
])
