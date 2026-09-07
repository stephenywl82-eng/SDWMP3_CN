package com.sdw.music.player.util

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * 【V8.8】蓝牙输出设备信息
 * 方案 A：BLUETOOTH_CONNECT 权限可用时返回真实耳机型号（如 "moto buds+"）
 * 方案 C 兜底：无权限/异常时返回 "Bluetooth" 泛称，绝不弹窗、不影响播放
 */
object BluetoothOutput {

    /** 当前是否有蓝牙 A2DP 输出（音频实际路由到蓝牙） */
    fun isBluetoothActive(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            val devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            android.util.Log.d("BluetoothOutput", "output devices: " + devs.joinToString { d ->
                "type=${d.type} sink=${d.isSink} src=${d.isSource} name=${d.productName}"
            })
            devs.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
        } catch (_: Throwable) { false }
    }

    /**
     * 当前蓝牙输出设备显示名
     * 主路径：AudioDeviceInfo.productName（实测直接返回 "moto buds+"，零权限依赖）
     * 兜底：BluetoothManager 拿 name；都拿不到则 "Bluetooth"
     */
    fun getActiveBluetoothName(context: Context): String {
        // 主路径：从输出设备列表拿 A2DP 设备的 productName
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                val btDev = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                }
                if (btDev != null) {
                    val n = btDev.productName?.toString()?.trim()
                    if (!n.isNullOrBlank() && n != "Bluetooth") return n
                }
            }
        } catch (_: Throwable) {
        }
        // 兜底：BluetoothManager 拿 name
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val devs = bm?.getConnectedDevices(android.bluetooth.BluetoothProfile.A2DP)
                if (!devs.isNullOrEmpty()) {
                    val name = devs.firstOrNull()?.name
                    if (!name.isNullOrBlank()) return name
                }
            } else {
                @Suppress("DEPRECATION")
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter != null &&
                    adapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.A2DP) ==
                    android.bluetooth.BluetoothProfile.STATE_CONNECTED
                ) {
                    @Suppress("DEPRECATION")
                    val n = adapter.name
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (_: Throwable) {
        }
        return "Bluetooth"
    }
}
