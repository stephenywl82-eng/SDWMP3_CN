package com.sdw.music.player.core.util

import android.content.SharedPreferences
import android.util.Log

/**
 * 【V8.211】SharedPreferences 容错读取 + 自愈写入。
 *
 * 为什么需要这一层：
 * 备份恢复走的是「JSON 文本」这条中间表示，而 org.json 的 numberToString() 会把
 * 整数值的浮点数（0.0 / 1.0 / -18.0）的小数部分削掉写成 0 / 1 / -18，
 * 解析回来就退化成 Integer —— 于是原本 putFloat() 写进去的值，恢复后被写成了 int。
 * 之后再有任何 `getFloat()` 读它，SharedPreferences 直接抛
 * `ClassCastException: java.lang.Integer cannot be cast to java.lang.Float`，
 * 表现就是「载入备份后一点某个页面就闪退」。
 *
 * 真机实测（ZY22JVFH7C）已被污染的键：
 *   settings.crossfade_dj_depth / loudness_norm.target_db
 *   mseb: temperature sibilance subBass thickness vocalForward air bassTexture
 *         femaleOvertones sibilanceLf sibilanceHf impulseResponse soundstage imaging
 *   compressor: threshold makeup
 * 全都是 int，而代码里全用 getFloat() 读。
 *
 * 这里做的事就两件：
 *  1. 读到类型不符时不再抛，按 Number 语义把值还原出来返回；
 *  2. 顺手把该键按正确类型重写回去 —— 设备上的脏数据只会被治一次，之后就是干净的。
 */
private const val TAG = "SDW_PrefsSafe"

/**
 * 按 Float 读取；键被写成 int/long/double 时不崩，并且就地改写成 float。
 * 字符串类型的脏值无法安全解释，直接回落到 default 并保留原值不动。
 */
fun SharedPreferences.floatValue(key: String, default: Float): Float {
    return try {
        getFloat(key, default)
    } catch (e: ClassCastException) {
        val raw = all[key]
        val n = raw as? Number
        if (n == null) {
            Log.w(TAG, "floatValue: $key 类型不符且无法还原（${raw?.javaClass?.simpleName}），用默认值 $default")
            default
        } else {
            val v = n.toFloat()
            Log.w(TAG, "floatValue: $key 存成了 ${raw.javaClass.simpleName}，按 $v 还原并改写为 float")
            edit().remove(key).putFloat(key, v).apply()
            v
        }
    }
}

/** 按 Long 读取并自愈；JSON 往返同样会把 long 削成 int。 */
fun SharedPreferences.longValue(key: String, default: Long): Long {
    return try {
        getLong(key, default)
    } catch (e: ClassCastException) {
        val raw = all[key]
        val n = raw as? Number
        if (n == null) {
            Log.w(TAG, "longValue: $key 类型不符且无法还原，用默认值 $default")
            default
        } else {
            val v = n.toLong()
            Log.w(TAG, "longValue: $key 存成了 ${raw.javaClass.simpleName}，按 $v 还原并改写为 long")
            edit().remove(key).putLong(key, v).apply()
            v
        }
    }
}

/** 按 Int 读取并自愈。 */
fun SharedPreferences.intValue(key: String, default: Int): Int {
    return try {
        getInt(key, default)
    } catch (e: ClassCastException) {
        val raw = all[key]
        val n = raw as? Number
        if (n == null) {
            Log.w(TAG, "intValue: $key 类型不符且无法还原，用默认值 $default")
            default
        } else {
            val v = n.toInt()
            Log.w(TAG, "intValue: $key 存成了 ${raw.javaClass.simpleName}，按 $v 还原并改写为 int")
            edit().remove(key).putInt(key, v).apply()
            v
        }
    }
}
