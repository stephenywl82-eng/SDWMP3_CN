package com.sdw.music.player.core.audio

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log

/**
 * 【2026-09-07】A2DP 编码前预补偿 — 蓝牙 codec 检测器
 *
 * 职责：感知当前音频输出是否为蓝牙 A2DP，若是则查询当前编码格式
 * （SBC/AAC/LHDC/LDAC…），映射到 native 预补偿增益 dB：
 *   - SBC（低码率，高频噪底+pre-echo 明显）→ 2.0 dB
 *   - AAC（256k 接近透明，仅瞬态提亮）     → 0.8 dB
 *   - LHDC/LDAC/aptX 高码率（近透明）       → 0（旁路）
 *   - 有线 / 扬声器 / USB DAC               → 0（旁路）
 *
 * 权限：BLUETOOTH_CONNECT（Android 12+ 运行时）。无权限时安全降级为
 * "按输出设备类型粗判"（AudioManager 蓝牙输出=真但无法细分 codec → SBC 保守档），
 * 若连设备类型都拿不到则完全旁路（0 dB，绝不污染有线听感）。
 */
object BtCodecTracker {
    private const val TAG = "BtCodecTracker"

    // codec -> dB 映射（native 侧增益；0 = 旁路）
    const val DB_NONE = 0.0f
    const val DB_SBC = 2.0f
    const val DB_AAC = 0.8f
    const val DB_HIRES = 0.0f   // LHDC/LDAC/aptX HD 等近透明

    /** 供 native 侧参考的当前生效增益（调试 UI 用） */
    @Volatile
    var currentCodecDb: Float = DB_NONE
        private set

    @Volatile
    var currentCodecName: String = "—"
        private set

    @Volatile
    var isBluetoothOutput: Boolean = false
        private set

    /** 当前应下发给 native 的（enabled, codecDb）。enabled 由设置页总开关决定。 */
    fun resolveDb(): Float {
        return currentCodecDb
    }

    /** 刷新检测。在设置页开关变化、音频路由变化时调用。返回当前 dB。 */
    fun refresh(context: Context): Float {
        val db = detect(context)
        currentCodecDb = db
        return db
    }

    private fun detect(context: Context): Float {
        // 1) 当前音频输出是否为蓝牙
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return DB_NONE
        val btOut = try {
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        } catch (_: Throwable) { false }
        isBluetoothOutput = btOut
        if (!btOut) {
            currentCodecName = "有线"
            return DB_NONE
        }

        // 2) 蓝牙已连接：尝试精确 codec（需 API 31+ 且 BLUETOOTH_CONNECT 权限；反射兼容 minSdk 24）
        if (Build.VERSION.SDK_INT >= 31 && hasConnectPermission(context)) {
            val db = queryCodecApi31(context)
            if (db != null) return db
        }
        // API<31 或无权限：无法细分 → 保守 SBC 档（高码率耳机也只会轻微补偿，安全）
        currentCodecName = "SBC?"
        return DB_SBC
    }


    /** 老 API getProfileProxy 同步取 A2DP profile（API 11+，兼容 minSdk 24）。 */
    private fun getA2dpProxy(adapter: android.bluetooth.BluetoothAdapter, context: Context): BluetoothA2dp? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var proxy: BluetoothProfile? = null
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, pxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) { proxy = pxy }
                latch.countDown()
            }
            override fun onServiceDisconnected(profile: Int) { latch.countDown() }
        }
        return try {
            if (!adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)) return null
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            proxy as? BluetoothA2dp
        } catch (_: Throwable) {
            null
        } finally {
            try { adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy) } catch (_: Throwable) {}
        }
    }

    /** API 31+ 反射查询 A2DP codec。返回 null 表示无法获取（走保守档）。 */
    private fun queryCodecApi31(context: Context): Float? {
        return try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
            val adapter = bm.adapter ?: return null
            if (!adapter.isEnabled) return null
            val a2dp = getA2dpProxy(adapter, context) ?: return null

            // 反射调 codecStatus 避免 API31 编译依赖
            val csMethod = BluetoothA2dp::class.java.getMethod("getCodecStatus")
            val status = csMethod.invoke(a2dp) ?: return null
            val csClass = status.javaClass
            val ctField = csClass.getMethod("getCodecType").invoke(status)
            val name = if (ctField != null) ctField.javaClass.getMethod("getName").invoke(ctField) as? String else null
            val sr = csClass.getMethod("getCodecSpecific1").invoke(status) as? Long ?: 0L

            val db = when {
                name == "SBC" -> { currentCodecName = "SBC"; DB_SBC }
                name == "AAC" -> { currentCodecName = "AAC"; DB_AAC }
                name == "LDAC" -> { currentCodecName = "LDAC"; DB_HIRES }
                name != null -> { currentCodecName = "HiRes($name)"; DB_HIRES }  // LHDC/aptX 系
                sr > 48000 -> { currentCodecName = "HiRes(${sr / 1000}k)"; DB_HIRES }
                else -> { currentCodecName = "SBC?"; DB_SBC }
            }
            Log.i(TAG, "A2DP codec=$name sr=$sr -> ${db}dB")
            db
        } catch (e: Throwable) {
            Log.e(TAG, "codec query error: ${e.message}")
            null
        }
    }

    private fun hasConnectPermission(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                val granted = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                granted == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        } catch (_: Throwable) {
            false
        }
    }
}
