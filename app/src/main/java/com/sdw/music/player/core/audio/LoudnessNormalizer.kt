package com.sdw.music.player.core.audio
import com.sdw.music.player.core.util.floatValue

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * 【V8.170】响度统一管理 —— 读元数据里的 ReplayGain / LUFS，折算成一枚播放增益下发。
 *
 * 语义对齐 ReplayGain 标准：TRACK_GAIN 是「相对参考点（89 dB SPL ≈ -18 LUFS）的修正量」，
 * 要让这首歌回到参考点，增益就是 -TRACK_GAIN。用户再挑一个目标档位做整体偏移。
 *
 * 三档目标（相对参考点）：
 *   0      —— 标准 ReplayGain 行为，只消除曲目间差异
 *  -1.5    —— 给多段限幅留一点余量，避免叠加时压得频繁
 *  -3      —— 保守档，追求 Album-level 一致性时更稳
 *
 * 没标签的曲子返回 0 dB：宁可不一致，也不要乱猜一个响度把人声推爆。
 */
object LoudnessNormalizer {

    private const val PREF = "loudness_norm"
    private const val K_ENABLED = "enabled"
    private const val K_MODE = "mode"        // 0=逐曲 1=整 album 优先 album 2=自动
    private const val K_TARGET = "target_db"
    private const val K_TARGET_LUFS = "target_lufs"

    /** 【V8.238】ReplayGain 参考点（89 dB SPL 等价）。标签是相对它给的修正量。 */
    private const val BASE_LUFS = -18f

    /** 目标哨兵：自动 = 以「曲库自身平均响度」为目标（平均增益≈0，整体听感不变）。 */
    const val TARGET_AUTO = 0f
    /** 没有曲库统计时的自动兜底 / 也是默认档：流媒体通行标准 -14 LUFS。 */
    const val TARGET_DEFAULT = -14f
    /** 真峰值上限：-1.0 dBTP（= 0.891 线性），给下游限幅/重采样留够余量。 */
    private const val TP_CEILING_DBTP = -1.0f

    /** 依据：逐曲 / 整张专辑 / 自动（优先 album，缺则回落 track）。 */
    enum class Mode(val key: Int) { TRACK(0), ALBUM(1), AUTO(2) }

    data class Config(
        val enabled: Boolean,
        val mode: Mode,
        /** 目标响度（LUFS）。TARGET_AUTO(0f) = 跟随曲库平均。 */
        val targetLufs: Float
    ) {
        companion object {
            // 【V8.515-P0a】默认开启响度归一：消除曲目间音量跳变。
            // 用户若显式关过，pref 里会存 enabled=false，load() 仍尊重之。
            fun default() = Config(true, Mode.AUTO, TARGET_DEFAULT)
        }
    }

    /**
     * 【V8.238】实际生效的目标响度。
     *
     * 旧版把目标钉死在 -18 LUFS（ReplayGain 经典参考点），而现代母带普遍 -9~-11 LUFS，
     * 于是几乎每首都要压掉 7~9 dB → 用户感知"开了之后全都变轻了"。
     * 响度统一的目的是消除**曲目之间**的差异，不是把整个库一起压小：
     * 自动档用曲库自身平均响度当目标，响的歌变小、轻的歌变大，平均增益≈0。
     */
    fun effectiveTarget(cfg: Config): Float {
        if (cfg.targetLufs != TARGET_AUTO) return cfg.targetLufs
        return LoudnessCache.averageLufs() ?: TARGET_DEFAULT
    }

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(context: Context): Config = runCatching {
        val p = prefs(context)
        val modeIdx = p.getInt(K_MODE, Mode.AUTO.key)
        // 【V8.238】旧 pref 是「相对 -18 的偏移 dB」，新 pref 是绝对 LUFS。
        // 迁移：旧默认值 0 dB（= -18 LUFS，正是"全都变轻"的元凶）→ 升级为自动档；
        // 用户显式调过的 -1.5 / -3 才保留成 -19.5 / -21，尊重原意。
        val target: Float
        if (p.contains(K_TARGET_LUFS)) {
            target = p.floatValue(K_TARGET_LUFS, TARGET_AUTO)
        } else {
            val oldDb = p.floatValue(K_TARGET, 0f)
            // 旧默认 0 dB = -18 LUFS（"全都变轻"的元凶）→ 一律升级到 -14 LUFS 档；
            // 用户显式调过的 -1.5/-3 保留为 -19.5/-21，尊重原意。
            target = if (oldDb == 0f) TARGET_DEFAULT else BASE_LUFS + oldDb
        }
        Config(
            // 【V8.515-P0a】首次使用（pref 无 K_ENABLED）默认开启；显式关过则尊重 false。
            enabled = p.getBoolean(K_ENABLED, true),
            mode = Mode.entries.firstOrNull { it.key == modeIdx } ?: Mode.AUTO,
            targetLufs = target
        )
    }.getOrDefault(Config.default())

    fun save(context: Context, c: Config) {
        prefs(context).edit()
            .putBoolean(K_ENABLED, c.enabled)
            .putInt(K_MODE, c.mode.key)
            .putFloat(K_TARGET_LUFS, c.targetLufs)
            .apply()
    }

    /**
     * 计算某首歌应有的归一化增益（dB）。
     *
     * 传 null 也行 —— ALBUM 模式的"整张专辑一个音量"靠文件里的 REPLAYGAIN_ALBUM_GAIN
     * 实现：同一张专辑的每首歌都带同一个 album gain，算出来自然一致，
     * 不需要把 albumId 打通到 MediaStore。
     */
    /** 这首曲子的响度来源：元数据标签 / 离线扫描 / 无。供设置页展示。 */
    enum class Source { TAG, SCAN, NONE }

    fun source(filePath: String): Source =
        if (gainFromTags(filePath, Config(true, Mode.AUTO, TARGET_AUTO)) != null) Source.TAG
        else if (LoudnessCache.get(filePath)?.lufs != null) Source.SCAN
        else Source.NONE

    /**
     * @param targetOverride 【V8.239】指定目标响度（诊断对账用）。
     *   自动档的目标 = 曲库平均响度，会随后台扫描推进而缓慢漂移；若「计算值」按最新均值算、
     *   「已下发」按下发那一刻的均值算，两者就会持续对不上（假告警）。
     *   传入下发时锁定的目标，可让对账只反映「这首歌的响度数据是否变了」。
     */
    fun gainFor(filePath: String, cfg: Config, targetOverride: Float? = null): Float {
        if (!cfg.enabled) return 0f
        // 【V8.234】标签算不出来（没标签 **或标签不完整**）→ 回退离线扫描值。
        // 旧写法只看「有没有标签对象」，于是"有 tag 但只有 peak、没有 gain"的文件
        // 会走进标签分支又返回 0，扫描值永远用不上。
        return gainFromTags(filePath, cfg, targetOverride) ?: gainFromScan(filePath, cfg, targetOverride) ?: 0f
    }

    /** 按文件标签算增益；null = 标签不可用（无标签，或标签里没有可用增益值）。 */
    private fun gainFromTags(filePath: String, cfg: Config, targetOverride: Float? = null): Float? {
        // 【V8.515-P1】优先用落盘缓存的标签增益（跨会话持久，冷启动首播免再解析文件）；
        // 缓存未命中则现场读 LoudnessTags，并回写缓存供下次直接命中。
        val self = TagGainCache.get(filePath) ?: run {
            val t = LoudnessTags.read(filePath)
            if (t != null) { TagGainCache.put(filePath, t); TagGainCache.scheduleFlush() }
            t
        } ?: return null
        val target = targetOverride ?: effectiveTarget(cfg)

        // 容器直接给了 LUFS：绝对口径，比 ReplayGain 的相对 dB 更准，优先采用
        if (self.lufs != null) {
            return cap(self.trackPeak, target - self.lufs!!)
        }

        // 走 ReplayGain：ALBUM 模式下 album gain 优先，整张专辑共用一枚增益
        val picked: Pair<Float?, Float?> = if (cfg.mode != Mode.TRACK && self.albumGainDb != null) {
            self.albumGainDb to (self.albumPeak ?: self.trackPeak)
        } else {
            self.trackGainDb to self.trackPeak
        }

        // ReplayGain 语义：实际响度 ≈ 参考点(-18) − gain；
        // 要到 target，增益 = target − 实际响度 = target + 18 + gain。
        val trackGain = self.trackGainDb
        return when {
            // 专辑口径拿到了就用专辑增益，否则回落到本曲增益；上限仍按本曲 peak 兜
            picked.first != null -> cap(picked.second, target - BASE_LUFS + picked.first!!)
            trackGain != null -> cap(self.trackPeak, target - BASE_LUFS + trackGain)
            else -> null     // 标签里没有可用增益 → 交给扫描值
        }
    }

    /** 按离线扫描值（BS.1770 LUFS）算增益；null = 没扫过或扫出来无效。
     *  targetOverride 传入下发时锁定的目标，避免在自动档下 averageLufs() 漂移导致「计算值」自己动。 */
    private fun gainFromScan(filePath: String, cfg: Config, targetOverride: Float? = null): Float? {
        val scanned = LoudnessCache.get(filePath)
        val lufs = scanned?.lufs
        if (lufs == null || lufs <= -60f || lufs >= 5f) return null
        val targetLufs = targetOverride ?: effectiveTarget(cfg)
        return cap(scanned.peak, targetLufs - lufs)
    }

    /** 峰值保护 + 上下限夹取：放大后真峰值不得越过 -1.0 dBTP（增益另夹在 ±24 dB）。 */
    private fun cap(peak: Float?, raw: Float): Float {
        var g = raw.coerceIn(-24f, 24f)
        if (peak != null && peak > 1e-6f) {
            // peak * 10^(g/20) <= 10^(TP/20)  →  g <= TP - 20*log10(peak)
            g = minOf(g, TP_CEILING_DBTP - 20f * kotlin.math.log10(peak.toDouble()).toFloat())
        }
        return g
    }

    /** 给设置页用：这首曲子能拿到什么元数据，供副标题展示「已读/无元数据」。 */
    fun describe(filePath: String): String {
        val t = LoudnessTags.read(filePath) ?: return "no-metadata"
        val bits = mutableListOf<String>()
        t.trackGainDb?.let { bits.add("track ${"%.1f".format(it)}dB") }
        t.albumGainDb?.let { bits.add("album ${"%.1f".format(it)}dB") }
        t.trackPeak?.let { bits.add("peak ${"%.3f".format(it)}") }
        t.lufs?.let { bits.add("lufs ${"%.1f".format(it)}") }
        return if (bits.isEmpty()) "no-metadata" else bits.joinToString(" · ")
    }

    /** 调试：读一个文件的原始标签。 */
    fun debugTag(filePath: String): LoudnessTags.Tag? = LoudnessTags.read(filePath)
}
