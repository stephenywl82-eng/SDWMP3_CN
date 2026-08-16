package com.sdw.music.player.benchmark

import android.content.Intent
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStart() = benchmarkRule.measureRepeated(
        packageName = "com.sdw.music.player",
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD
    ) {
        pressHome()
        startActivityAndWait(
            Intent().setClassName(
                "com.sdw.music.player",
                "com.sdw.music.player.MainActivity"
            )
        )
        device.waitForIdle()
    }

    @Test
    fun warmStart() = benchmarkRule.measureRepeated(
        packageName = "com.sdw.music.player",
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM
    ) {
        pressHome()
        startActivityAndWait(
            Intent().setClassName(
                "com.sdw.music.player",
                "com.sdw.music.player.MainActivity"
            )
        )
        device.waitForIdle()
    }
}