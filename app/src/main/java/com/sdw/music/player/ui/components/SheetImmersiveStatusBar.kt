package com.sdw.music.player.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.WeakHashMap

// [V8.133] Immersive bar ownership must be ref-counted per window.
//
// Bug this fixes: "全屏歌词界面我要隐藏手机状态栏啊" — the bar came back on the lyrics page.
//
// Two live owners share the MAIN window:
//   · PlayerScreen          : hideNavigationBar = false  (hides status bar only)
//   · LyricFullscreenScreen : hideNavigationBar = true   (hides status + navigation)
//
// Navigating Player -> Lyrics runs in this order:
//   1. Lyrics' effect  -> hide(statusBars + navBars)
//   2. Player's exit transition completes -> Player's DisposableEffect disposes
//                       -> show(statusBars)   <-- stomps the hide from step 1
// Net: the lyrics page gets its status bar back (the nav bar stays hidden because
// Player never hid it — which is why only the status bar leaked). Same shape in the
// pop direction, just with the roles swapped.
//
// The old code restored blindly on dispose. Now every hide() is a counted acquisition
// and bars are only restored when the LAST owner for that window goes away, so the
// ordering of enter/exit effects no longer matters.
private object ImmersiveBarRefCounter {
    private class Counts {
        var status = 0
        var nav = 0
    }

    private val map = WeakHashMap<Window, Counts>()

    @Synchronized
    fun acquire(window: Window, hideNavigationBar: Boolean) {
        val c = map.getOrPut(window) { Counts() }
        c.status++
        if (hideNavigationBar) c.nav++
    }

    /** @return (showStatusBars, showNavigationBars) — true only for the last owner. */
    @Synchronized
    fun release(window: Window, hideNavigationBar: Boolean): Pair<Boolean, Boolean> {
        val c = map[window] ?: return Pair(false, false)
        c.status = (c.status - 1).coerceAtLeast(0)
        if (hideNavigationBar) c.nav = (c.nav - 1).coerceAtLeast(0)
        val showStatus = c.status == 0
        // Only the owner that actually hid the nav bar may restore it.
        val showNav = hideNavigationBar && c.nav == 0
        if (c.status == 0 && c.nav == 0) map.remove(window)
        return Pair(showStatus, showNav)
    }

    @Synchronized
    fun statusOwners(window: Window): Int = map[window]?.status ?: 0
}

/**
 * 【V8.117】 让 ModalBottomSheet 也进入沉浸式。
 *
 * ModalBottomSheet 在 M3 里是**独立 Dialog 窗口**（ModalBottomSheetDialogWrapper），
 * 播放页在 Activity 窗口上做的 hide(statusBars) 对它无效 -> 面板一弹出来系统状态栏就"回来"，
 * 在面板顶上表现为一条横线。这里从 LocalView 向上找到 DialogWindowProvider 拿到面板自己的
 * Window，单独隐藏状态栏，离开时恢复。下滑/点遮罩关闭不受影响。
 *
 * V8.133 走与 ImmersiveSystemBars 同一套按窗口引用计数：面板常叠在播放页/歌词页之上，
 * 若各自盲 show() 又会互相踩掉（同 1 的根因）。
 */
@Composable
fun SheetImmersiveStatusBar(hideNavigationBar: Boolean = false) {
    val view = LocalView.current
    DisposableEffect(view) {
        val window: Window? = findDialogWindow(view)
            ?: view.context.findHostActivity()?.window
        if (window == null) return@DisposableEffect onDispose {}
        val controller = WindowInsetsControllerCompat(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ImmersiveBarRefCounter.acquire(window, hideNavigationBar)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        if (hideNavigationBar) controller.hide(WindowInsetsCompat.Type.navigationBars())
        onDispose {
            runCatching {
                val (showStatus, showNav) = ImmersiveBarRefCounter.release(window, hideNavigationBar)
                if (showStatus) controller.show(WindowInsetsCompat.Type.statusBars())
                if (showNav) controller.show(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }
}

// [V8.125] ContextWrapper-safe Activity lookup.
// LocalView's context can be a ContextThemeWrapper / ContextWrapper chain, so a bare
// `context as? Activity` cast silently returns null and the caller no-ops.
fun Context.findHostActivity(): Activity? {
    var ctx: Context? = this
    while (ctx != null) {
        if (ctx is Activity) return ctx
        ctx = (ctx as? ContextWrapper)?.baseContext
    }
    return null
}

// [V8.125] Hide the system bars for full-screen Compose pages that live in the MAIN window
// (now-playing, full-screen lyrics).
//
// Why this exists: the old player code built its controller from the *Compose view*
// (WindowInsetsControllerCompat(window, view)). That controller is null until the view is
// attached, so hide() could no-op forever, and it resolved the window with a bare
// (view.context as? Activity) cast that fails on a ContextWrapper. Both failures are silent
// (no crash, no log) - the status bar simply stays visible, which is the reported bug.
//
// This version uses the WINDOW's decor view (valid before attachment), resolves the Activity
// through the ContextWrapper chain, and re-applies the hide on window focus gain so a
// dismissed dialog / bottom sheet cannot leave the bar showing. It does NOT touch
// setDecorFitsSystemWindows, so the app-wide edge-to-edge setup is preserved.
//
// [V8.133] hide() is now a ref-counted acquisition (see ImmersiveBarRefCounter above) so that
// a page leaving the screen mid-navigation cannot restore the bars the incoming page just hid.
@Composable
fun ImmersiveSystemBars(hideNavigationBar: Boolean = false) {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findHostActivity()?.window
            ?: return@DisposableEffect onDispose {}
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val types = if (hideNavigationBar) WindowInsetsCompat.Type.systemBars()
                    else WindowInsetsCompat.Type.statusBars()
        ImmersiveBarRefCounter.acquire(window, hideNavigationBar)
        controller.hide(types)

        val observer = window.decorView.viewTreeObserver
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            // [V8.133] Re-hide only while this page is still an owner — otherwise a stale
            // listener could fight a later page that has already restored the bars.
            if (hasFocus && ImmersiveBarRefCounter.statusOwners(window) > 0) {
                controller.hide(types)
            }
        }
        observer.addOnWindowFocusChangeListener(focusListener)

        onDispose {
            runCatching { observer.removeOnWindowFocusChangeListener(focusListener) }
            runCatching {
                val (showStatus, showNav) = ImmersiveBarRefCounter.release(window, hideNavigationBar)
                if (showStatus) controller.show(WindowInsetsCompat.Type.statusBars())
                if (showNav) controller.show(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }
}

private fun findDialogWindow(view: View): Window? {
    var p: ViewParent? = view.parent
    while (p != null) {
        if (p is DialogWindowProvider) return p.window
        p = p.parent
    }
    return null
}
