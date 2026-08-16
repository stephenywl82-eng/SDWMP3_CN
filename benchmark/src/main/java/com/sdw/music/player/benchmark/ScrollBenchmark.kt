package com.sdw.music.player.benchmark

import android.content.Intent
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrollSongList() = benchmarkRule.measureRepeated(
        packageName = "com.sdw.music.player",
        metrics = listOf(FrameTimingMetric()),
        iterations = 5
    ) {
        pressHome()
        startActivityAndWait(
            Intent().setClassName(
                "com.sdw.music.player",
                "com.sdw.music.player.MainActivity"
            )
        )
        device.waitForIdle()

        val w = device.displayWidth
        val h = device.displayHeight
        repeat(5) {
            device.swipe(
                w / 2, (h * 0.7).toInt(),
                w / 2, (h * 0.3).toInt(),
                20
            )
            device.waitForIdle()
        }
    }
}