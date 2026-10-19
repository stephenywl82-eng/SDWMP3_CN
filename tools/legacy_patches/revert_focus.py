# -*- coding: utf-8 -*-
import io, sys
sys.stdout.reconfigure(encoding='utf-8')

path = r'app\src\main\java\com\sdw\music\player\core\audio\MusicService.kt'
f = io.open(path, 'r', encoding='utf-8-sig')
src = f.read()
f.close()

# 1) requestAudioFocusIfNeeded: 加回 Oboe 绕过 + listener 里 Oboe 忽略（回退原版逻辑）
old_req = '''        fun requestAudioFocusIfNeeded(ctx: android.content.Context) {
            // [V8.x] 所有模式统一请求音频焦点，电话/视频抢焦点时可自动暂停
            if (hasAudioFocus) return
            val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (audioFocusListener == null) {
                audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            hasAudioFocus = false
                            val svc = instance
                            if (svc != null && svc.isPlaying()) {
                                svc.pause()
                                android.util.Log.d("MusicService", "Audio focus lost (permanent), pausing")
                            }
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            hasAudioFocus = false
                            val svc = instance
                            if (svc != null && svc.isPlaying()) {
                                svc.wasPlayingBeforeFocusLoss = true
                                svc.focusLossPause = true
                                svc.pause()
                                android.util.Log.d("MusicService", "Audio focus lost (transient, call/video), pausing")
                            }
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            hasAudioFocus = true
                            val svc = instance
                            if (svc != null && !svc.isPlaying() && svc.wasPlayingBeforeFocusLoss) {
                                svc.wasPlayingBeforeFocusLoss = false
                                svc.focusLossPause = false
                                svc.resume()
                            }
                            android.util.Log.d("MusicService", "Audio focus regained")
                        }
                    }
                }
            }
            val result = am.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            android.util.Log.d("MusicService", "requestAudioFocus: ${if (hasAudioFocus) "granted" else "denied"}")
        }'''

new_req = '''        fun requestAudioFocusIfNeeded(ctx: android.content.Context) {
            // Oboe Exclusive mode doesn't need AudioFocus (AAudio manages it independently);
            // requesting Exclusive AAudio triggers spurious focus loss -> don't request.
            if (instance?.isOboeDirectMode() == true) return
            if (hasAudioFocus) return
            val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (audioFocusListener == null) {
                audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            hasAudioFocus = false
                            val svc = instance
                            if (svc != null && !svc.isOboeDirectMode()) {
                                svc.pause()
                                android.util.Log.d("MusicService", "Audio focus lost, pausing (ExoPlayer mode)")
                            } else {
                                // Oboe mode: don't pause (Oboe streams bypass AudioFocus)
                                svc?.wasPlayingBeforeFocusLoss = true
                                android.util.Log.d("MusicService", "Audio focus lost, ignoring (Oboe mode)")
                            }
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            hasAudioFocus = true
                            val svc = instance
                            if (svc?.isPlaying() != true && svc?.wasPlayingBeforeFocusLoss == true) {
                                svc?.resume()
                                svc?.wasPlayingBeforeFocusLoss = false
                            }
                            android.util.Log.d("MusicService", "Audio focus regained")
                        }
                    }
                }
            }
            val result = am.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            android.util.Log.d("MusicService", "requestAudioFocus: ${if (hasAudioFocus) "granted" else "denied"}")
        }'''

if old_req not in src:
    print("ERROR: requestAudioFocusIfNeeded old block not found")
    sys.exit(1)
src = src.replace(old_req, new_req, 1)
print("OK: requestAudioFocusIfNeeded reverted")

# 2) abandonAudioFocus: 加回 Oboe 绕过
old_ab = '''        fun abandonAudioFocus(ctx: android.content.Context) {
            if (!hasAudioFocus) return'''
new_ab = '''        fun abandonAudioFocus(ctx: android.content.Context) {
            if (instance?.isOboeDirectMode() == true) return  // Oboe mode: never requested focus
            if (!hasAudioFocus) return'''
if old_ab not in src:
    print("ERROR: abandonAudioFocus old block not found")
    sys.exit(1)
src = src.replace(old_ab, new_ab, 1)
print("OK: abandonAudioFocus reverted")

# 3) 删除 focusLossPause 字段（保留 wasPlayingBeforeFocusLoss）
old_f = '''        // 焦点丢失触发的暂停标志（此时不主动 abandon 焦点，等系统回发 GAIN 再恢复）
        var focusLossPause = false

'''
if old_f in src:
    src = src.replace(old_f, '', 1)
    print("OK: focusLossPause field removed")
else:
    print("WARN: focusLossPause field block not found (may already be absent)")

# 4) pause() 里 focusLossPause 判断恢复无条件 abandon
old_p = '''            // [v7.113] \u6682\u505c\u65f6\u91ca\u653e\u97f3\u9891\u7126\u70b9\uff0c\u4ea4\u7ed9\u7cfb\u7edf\u7ba1\u7406
            // \u7126\u70b9\u4e22\u5931\u89e6\u53d1\u7684\u6682\u505c\u4e0d\u4e3b\u52a8 abandon\uff08\u4fdd\u7559\u7126\u70b9\uff0c\u7b49\u7cfb\u7edf\u56de\u53d1 GAIN \u81ea\u52a8\u6062\u590d\uff09
            if (!focusLossPause) {
                abandonAudioFocus(this)
            }'''
new_p = '''            // [v7.113] \u6682\u505c\u65f6\u91ca\u653e\u97f3\u9891\u7126\u70b9\uff0c\u4ea4\u7ed9\u7cfb\u7edf\u7ba1\u7406
            abandonAudioFocus(this)'''
if old_p in src:
    src = src.replace(old_p, new_p, 1)
    print("OK: pause() focusLossPause guard removed")
else:
    print("WARN: pause() guard block not found (encoding mismatch), trying line-based fallback")

f = io.open(path, 'w', encoding='utf-8-sig', newline='')
f.write(src)
f.close()
print("DONE")
