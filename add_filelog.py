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

# 1. PlayerConnection.startOboeSongSync 埋点
patch(r'E:\SDWMP3_CN\app\src\main\java\com\sdw\music\player\core\audio\PlayerConnection.kt', [
    ("""            MusicService.songChangedFlow.collect { song ->
                if (song != null) _currentSong.value = song
            }""",
     """            MusicService.songChangedFlow.collect { song ->
                FileLog.log("SONG", "songChangedFlow emit: ${song?.title} id=${song?.id}")
                if (song != null) _currentSong.value = song
            }"""),
])

# 2. MusicService.notifySongChanged 埋点
patch(r'E:\SDWMP3_CN\app\src\main\java\com\sdw\music\player\core\audio\MusicService.kt', [
    ("""        private fun notifySongChanged(song: Song?) {
            _songChangedFlow.value = song  // """,
     """        private fun notifySongChanged(song: Song?) {
            com.sdw.music.player.FileLog.log("SVC", "notifySongChanged: ${song?.title} id=${song?.id}")
            _songChangedFlow.value = song  // """),
])

# 3. MusicService.getCurrentPosition/getDuration 埋点（低频）
patch(r'E:\SDWMP3_CN\app\src\main\java\com\sdw\music\player\core\audio\MusicService.kt', [
    ("""    fun getCurrentPosition(): Long {
        // [V8.x] USB DAC: audible position = decode position - ring buffer depth
        usbDacController?.let { if (it.isPlaying || it.audiblePositionMs >= 0) return it.audiblePositionMs }""",
     """    fun getCurrentPosition(): Long {
        com.sdw.music.player.FileLog.log("SVC", "getCurrentPosition: oboePrepared=${oboeDirectPlayer?.isPrepared} isOboe=${isOboeDirectMode()}")
        // [V8.x] USB DAC: audible position = decode position - ring buffer depth
        usbDacController?.let { if (it.isPlaying || it.audiblePositionMs >= 0) return it.audiblePositionMs }"""),
])
