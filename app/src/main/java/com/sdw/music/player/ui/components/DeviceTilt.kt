package com.sdw.music.player.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 设备倾斜状态（适配自 Miuix miuix-blur 的 DeviceTilt，Apache-2.0）。
 *
 * 用于驱动玻璃高光角度：手机倾斜时玻璃边缘的流光跟着流动（HyperOS 标志效果）。
 *
 * @param pitch 前后俯仰（弧度）
 * @param roll 左右翻滚（弧度）——高光角度直接由它驱动
 * @param gravityX/gravityY 设备坐标系下的重力分量（-1..1），可做视差
 */
data class DeviceTilt(
    val pitch: Float = 0f,
    val roll: Float = 0f,
    val gravityX: Float = 0f,
    val gravityY: Float = 0f
) {
    companion object {
        val Zero = DeviceTilt()
    }
}

/**
 *  remembers the device tilt from the rotation-vector sensor, smoothed over time.
 * 传感器仅在生命周期 >= STARTED 时注册，STOPPED 时注销（省电、无后台唤醒）。
 *
 * @param smoothing 平滑系数（0..1，越大越灵敏；0.12 左右读起来像"流光"而不是"跳变"）
 */
@Composable
fun rememberDeviceTilt(smoothing: Float = 0.12f): State<DeviceTilt> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val tilt = remember { mutableStateOf(DeviceTilt.Zero) }

    DisposableEffect(context, lifecycleOwner, smoothing) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensorManager == null || sensor == null) {
            return@DisposableEffect onDispose { }
        }

        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        var smoothPitch = 0f
        var smoothRoll = 0f
        var smoothGravityX = 0f
        var smoothGravityY = 0f
        var initialized = false
        var registered = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                // 设备坐标系下的重力方向 = R 的第三行取负（屏幕朝上时约 (0,0,-1)）
                val gravityX = -rotationMatrix[6]
                val gravityY = -rotationMatrix[7]
                if (!initialized) {
                    smoothPitch = orientation[1]
                    smoothRoll = orientation[2]
                    smoothGravityX = gravityX
                    smoothGravityY = gravityY
                    initialized = true
                } else {
                    smoothPitch += (orientation[1] - smoothPitch) * smoothing
                    smoothRoll += (orientation[2] - smoothRoll) * smoothing
                    smoothGravityX += (gravityX - smoothGravityX) * smoothing
                    smoothGravityY += (gravityY - smoothGravityY) * smoothing
                }
                tilt.value = DeviceTilt(
                    pitch = smoothPitch,
                    roll = smoothRoll,
                    gravityX = smoothGravityX,
                    gravityY = smoothGravityY
                )
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }

        fun register() {
            if (!registered) {
                sensorManager.registerListener(
                    listener,
                    sensor,
                    SensorManager.SENSOR_DELAY_GAME
                )
                registered = true
            }
        }

        fun unregister() {
            if (registered) {
                sensorManager.unregisterListener(listener)
                registered = false
            }
        }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> register()
                Lifecycle.Event.ON_STOP -> unregister()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            register()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            unregister()
        }
    }

    return tilt
}
