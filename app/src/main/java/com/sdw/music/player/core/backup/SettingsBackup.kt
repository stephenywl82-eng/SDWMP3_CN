package com.sdw.music.player.core.backup
import com.sdw.music.player.core.util.floatValue

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 【V8.195】设置备份 / 恢复（导出为 JSON，另一台设备上导入）
 *
 * 为什么是 JSON 而不是别的形式：
 *  - 项目已在用 org.json（MSEB 预设、mseb_presets 都是这个套路），零新增依赖；
 *  - 纯文本：能直接丢微信 / 网盘，能手改，出问题能 diff，不加密所以排障不用另配工具；
 *  - 黑白名单策略 —— 导出走「全量 + 黑名单」，而不是硬编码白名单，
 *    这样以后新增设置项会自动被带上，不用记得回来改导出清单。
 *
 * 【V8.201 严格两组】范围收敛为「音频 + 界面偏好」：收藏与歌单属于本机数据而非配置，
 * 其中的 id（歌单 id 是本机时间戳、收藏是纯 media id）换机后必然失效，导出只会制造垃圾。
 * 两个 scope 之外的 prefs 文件一律不导出，EXCLUDED 里的在导入侧也一并跳过。
 *
 * JSON 结构：
 * {
 *   "format": "sdwmp3-settings", "version": 1,
 *   "appVersion": "8.195", "device": "?", "exportedAt": "2026-09-28T10:40:00+0800",
 *   "files": { "<prefsName>": { "<key>": <原生类型值>, ... }, ... },
 *   "types": { "<prefsName>": { "<key>": "b|i|l|f|s|S", ... }, ... }
 * }
 *
 * 【V8.211】为什么非要这个 types 段：org.json 的 numberToString() 会把整数值的浮点数
 * 削掉小数部分 —— 0.0 / 1.0 / -18.0 写进 JSON 变成 0 / 1 / -18，解析回来就退化成 Integer。
 * 于是原本 putFloat() 存的值跨设备恢复后被写成 int，之后再 getFloat() 读就抛
 * ClassCastException，表现就是「载入备份后一点某个页面就闪退」。真实污染已在
 * ZY22JVFH7C 上确认：mseb 的 12 个参数、compressor 的 threshold/makeup、
 * settings.crossfade_dj_depth、loudness_norm.target_db 全变成了 int。
 * 纯靠 JSON 文本无法区分 1 是 int 还是 float，所以类型必须由导出侧显式带出去。
 *
 * 【V8.212】但对「整段缺 types 的老备份」，导入侧另做了兜底：已知 float 键白名单优先于
 * 本机既有类型与 JSON 字面类型，强制按 float 写回，从写入侧杜绝脏 int 落盘（见 FLOAT_KEYS / inferType）。
 */
object SettingsBackup {

    private const val TAG = "SDW_Backup"
    const val FORMAT_TAG = "sdwmp3-settings"
    const val FORMAT_VERSION = 1

    /** 导出范围。只有两组，且刻意不收「收藏 / 歌单」——那属于本机数据不是配置。 */
    enum class Scope(val prefs: List<String>) {
        /**
         * 音频与播放：交叉淡化 / MSEB / 各效果模块 / 响度归一化。
         * 【V8.202】原先列的 "dsp_eq" 已摘掉 —— MusicService.setCustomDspEq() 从未被任何
         * UI 调用，所以这个文件在任何机器上都不会被写入（本机也没有），导出它永远是空。
         * 界面上真正能调的 DSP EQ 走的是 equalizer_prefs，那条链路是正常的。
         */
        AUDIO(listOf(
            "settings", "mseb", "mseb_presets",
            "compressor", "crossfeed", "loudness", "loudness_norm", "equalizer_prefs"
        )),
        /**
         * 界面偏好：语言 / VU / 沉浸 / 小组件外观
         * 【V8.216】"lyric_prefs" 已摘掉 —— 歌词是本机/曲目相关数据不是配置，
         * 换机后歌曲 id 与文件路径必然对不上，带过去只是垃圾（用户明确要求不备份歌词）。
         */
        UI(listOf(
            "sdw_music_prefs", "app_language_prefs", "widget_prefs"
        ))
    }

    /**
     * 永不导出的名字及原因：
     *  - bpm_key_cache / palette_cache / folder_list_cache：分析扫描缓存，体积大且随本机音乐库变化
     *  - playback_state：上次的歌和播放位置，属于本机状态不是配置
     *  - permission_flags：权限询问标记，导入会替别人"已确认"
     *  - MusicPlayer：8.194 起 shuffle_mode 已收敛到 settings 里的三态字符串，
     *    这个文件是废弃的第二份副本，带过去只会制造 8.194 刚修掉的那类不一致
     *  - sdw_favorites / sdw_playlists：【V8.201】本机数据，详见 EXCLUDED 里的行内说明
     */
    private val EXCLUDED = setOf(
        // 【V8.216】"lyric_prefs" 移入黑名单：歌词不属于"设置"，与下面的本机数据同类。
        // 导出侧已从 UI scope 摘掉，这里再拦一层，导入侧遇到老备份文件（含 lyric_prefs）同样跳过。
        "lyric_prefs",
        // 【V8.216】"folder_list_cache" = 歌曲目录清单（folders_json 里全是本机绝对路径），
        // 换机后路径必然失效，属于典型的"设备型号/目录"类无关数据，永不导出导入。
        "bpm_key_cache", "palette_cache", "folder_list_cache", "playback_state",
        "permission_flags", "MusicPlayer",
        // 【V8.201 严格两组】收藏与歌单是【本机数据】不是配置，跨设备没有意义：
        //   歌单 id 是本机时间戳（实测 {id:1790448177770,...}），收藏存的是纯 media id，
        //   换机或重建媒体库后必然对不上，导入只会留下一批指向不存在歌曲的空壳条目。
        //   导出与导入两侧都拦，老备份文件（8.200 及更早导出、含 LIBRARY 段）同样不会被写回。
        "sdw_favorites", "sdw_playlists"
    )

    /**
     * 【V8.212】已知应以 Float 存储的键（按 prefs 文件分组）。
     * 这些键在代码里全部用 floatValue / getFloat 读取，一旦被退化成 int（老备份场景）
     * 再 getFloat 读就抛 ClassCastException。导入侧据此在「无 types 段的旧备份」上强制按
     * float 写回，从写入侧杜绝脏 int 落盘。新增 float 设置项时务必同步维护这里。
     */
    private val FLOAT_KEYS = setOf(
        // mseb（含 DTS 环绕 / 高度）
        "mseb" to "temperature", "mseb" to "sibilance", "mseb" to "subBass",
        "mseb" to "thickness", "mseb" to "vocalForward", "mseb" to "air",
        "mseb" to "bassTexture", "mseb" to "femaleOvertones", "mseb" to "sibilanceLf",
        "mseb" to "sibilanceHf", "mseb" to "impulseResponse", "mseb" to "soundstage",
        "mseb" to "imaging", "mseb" to "dts_surround", "mseb" to "dts_height",
        // compressor
        "compressor" to "threshold", "compressor" to "ratio", "compressor" to "attack",
        "compressor" to "release", "compressor" to "makeup",
        // loudness / loudness_norm
        "loudness" to "intensity", "loudness_norm" to "target_db",
        // crossfeed
        "crossfeed" to "amount",
        // settings
        "settings" to "crossfade_dj_depth",
        // dsp_eq（已移出导出 scope，但老备份可能仍含，防御性纳入）
        "dsp_eq" to "hs_freq", "dsp_eq" to "hs_db", "dsp_eq" to "hs_q",
        "dsp_eq" to "pk_freq", "dsp_eq" to "pk_db", "dsp_eq" to "pk_q",
        "dsp_eq" to "pre_gain_db"
    )

    data class Report(
        val files: Int = 0,
        val keys: Int = 0,
        val skipped: List<String> = emptyList()
    )

    /** 生成备份 JSON 文本。scopes 为空 / null 视为全选（黑名单仍然生效）。 */
    fun buildJson(context: Context, scopes: List<Scope>? = null): String {
        val wanted: Set<String>? = scopes?.takeIf { it.isNotEmpty() }
            ?.flatMap { it.prefs }
            ?.toSet()

        // 【V8.211】导出侧记下每个键的真实类型，供导入侧还原（详见类注释）。
        val typesRoot = JSONObject()
        val root = JSONObject().apply {
            put("format", FORMAT_TAG)
            put("version", FORMAT_VERSION)
            put("appVersion", safeVersionName(context))
            // 【V8.216】去掉 "device" 段：设备型号（Build.MANUFACTURER + MODEL）属于
            // 本机标识而非配置，写进备份没有任何还原价值，还会误导"这份备份来自哪台机器"
            // 的判断（用户明确要求不备份设备型号）。
            put("exportedAt", stamp())
            put("files", JSONObject().apply {
                for (name in allPrefsNames(context)) {
                    if (name in EXCLUDED) continue
                    if (wanted != null && name !in wanted) continue
                    val src = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                    val map = JSONObject()
                    val tmap = JSONObject()
                    for ((k, v) in src.all) {
                        // 类型标记先算出来：Float 走这里也会被记成 "f"，
                        // 但它在 JSON 文本里可能已经变成整数样子了，所以标记必须单独存。
                        val t = typeTagOf(v) ?: continue
                        tmap.put(k, t)
                        when (v) {
                            is Boolean -> map.put(k, v)
                            is Int -> map.put(k, v)
                            is Long -> map.put(k, v)
                            is Float -> map.put(k, v)
                            is String -> map.put(k, v)
                            is Set<*> -> map.put(k, JSONArray(v.filterIsInstance<String>().toTypedArray()))
                            else -> Unit
                        }
                    }
                    if (map.length() > 0) {
                        put(name, map)
                        typesRoot.put(name, tmap)
                    }
                }
            })
            put("types", typesRoot)
        }
        return root.toString(2)
    }

    /**
     * 把 JSON 文本写进目标 uri（SAF 导出 / 分享前的落盘都走这里）。
     *
     * 【V8.196】返回 String?：null = 写成功，否则是给人看的原因。
     * 旧实现传的 mode 是 "wct"，这不是 ContentResolver 认的值（只认 r / w / rw / rwt），
     * 部分 ROM 会直接抛 IllegalArgumentException，于是无论存哪儿都报"写入失败"。
     * 现在按 无 mode → "rwt" 的顺序试，任一成功即算写入成功。
     */
    fun writeTo(context: Context, uri: android.net.Uri, json: String): String? {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val modes = listOf(null, "rwt")
        var lastErr: String? = null
        for (mode in modes) {
            try {
                val out = if (mode == null) {
                    @Suppress("MissingPermission")
                    context.contentResolver.openOutputStream(uri)
                } else {
                    context.contentResolver.openOutputStream(uri, mode)
                }
                if (out == null) {
                    lastErr = "拿不到写入句柄"
                    continue
                }
                out.use { it.write(bytes) }
                out.flush()
                return null
            } catch (e: Exception) {
                lastErr = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
                Log.w(TAG, "writeTo(mode=$mode) failed: $lastErr", e)
            }
        }
        Log.e(TAG, "writeTo failed, uri=$uri, reason=$lastErr")
        return lastErr ?: "未知错误"
    }

    /** 从 uri 读备份文本。 */
    fun readFrom(context: Context, uri: android.net.Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        Log.e(TAG, "readFrom failed", e)
        null
    }

    enum class Mode { MERGE, REPLACE }

    /**
     * 写回本地 SharedPreferences。
     *  - MERGE：只写文件里存在的 key，本机其它设置保留（推荐，误操作可逆）
     *  - REPLACE：先清空同名 prefs 再整个写回（严格还原导出那一刻的状态）
     * 返回值里带了没写进去的 key，方便给出"没生效的项"报告。
     */
    fun restore(context: Context, json: String, mode: Mode): Report {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.e(TAG, "restore: bad json", e)
            return Report(skipped = listOf("JSON 解析失败"))
        }
        if (root.optString("format", "") != FORMAT_TAG) {
            return Report(skipped = listOf("不是 SDWMP3 的设置备份文件"))
        }

        val files = root.optJSONObject("files") ?: return Report(skipped = listOf("缺少 files 段"))
        // 【V8.211】老备份（8.210 及更早导出）没有这段，typesRoot 为 null，
        // 落到「按本机类型 / 按 JSON 字面类型」的旧行为上 —— 向后兼容，只是不再自愈。
        val typesRoot = root.optJSONObject("types")
        var keys = 0
        var fileCount = 0
        val skipped = mutableListOf<String>()

        files.keys().forEach { name ->
            // 【V8.201】老备份文件（8.200 及更早导出，含 LIBRARY 段）里仍带着
            // sdw_favorites / sdw_playlists。导出侧改了还不够，导入侧必须一起拦，
            // 否则"改过的导出"碰上"旧备份文件"依旧会被污染，且是静默的。
            if (name in EXCLUDED) {
                skipped += "$name（本机数据，已跳过）"
                return@forEach
            }
            val obj = files.optJSONObject(name) ?: return@forEach
            val prefs = try {
                context.getSharedPreferences(name, Context.MODE_PRIVATE)
            } catch (e: Exception) {
                skipped += "$name（无法访问）"
                return@forEach
            }
            fileCount++
            if (mode == Mode.REPLACE) prefs.edit().clear().apply()
            val tmap = typesRoot?.optJSONObject(name)
            obj.keys().forEach { key ->
                val existing = prefs.all[key]
                // 【V8.211】备份里声明的类型优先于本机现有类型：本机那份很可能就是上一次
                // 有缺陷的导入写错的（int 冒充 float），按声明写才能顺带把它纠正回来。
                val declared = tmap?.optString(key).orEmpty()
                // 【V8.212】类型推断优先级：
                //  ① 备份声明的 types 段（最权威，新备份走这里）；
                //  ② 整段缺 types 的老备份 → inferType：已知 float 键白名单 > 本机既有类型 > JSON 字面类型，
                //     从写入侧杜绝「退化 int 落盘」（详见 inferType 注释）；
                //  ③ 新备份但个别 name 缺 types 条目 → 沿用本机既有类型（保持原行为）。
                val type = if (declared.isNotEmpty()) declared
                    else if (typesRoot == null) inferType(name, obj, key, existing)
                    else typeTagOf(existing).orEmpty()
                val ok = when (type) {
                    "b" -> commit(prefs) { putBoolean(key, obj.optBoolean(key)) }
                    "i" -> commit(prefs) { putInt(key, obj.optInt(key)) }
                    "l" -> commit(prefs) { putLong(key, obj.optLong(key)) }
                    // 注意用 optDouble 而不是 optFloat：JSON 里 float 已被削成整数样子，
                    // 值的精度靠文本，类型靠 tmap，两边合起来才是完整信息。
                    "f" -> commit(prefs) { putFloat(key, obj.optDouble(key).toFloat()) }
                    "s" -> commit(prefs) { putString(key, obj.optString(key)) }
                    "S" -> {
                        val arr = obj.optJSONArray(key)
                        val set = if (arr == null) emptySet()
                        else (0 until arr.length()).mapNotNull { arr.optString(it) }.toSet()
                        commit(prefs) { putStringSet(key, set) }
                    }
                    // 本机没这个 key、备份里也没带类型（老备份）：按 JSON 字面类型落盘
                    else -> commit(prefs) { putUntyped(this, key, obj, skipped) }
                }
                if (ok) keys++
            }
        }

        Log.i(TAG, "restore: $fileCount file(s), $keys key(s), skipped=${skipped.size}")
        return Report(files = fileCount, keys = keys, skipped = skipped)
    }

    /** 真正的落盘动作：apply() 是异步的，这里只关心"写请求有没有发出去"。 */
    private fun commit(
        prefs: SharedPreferences, block: SharedPreferences.Editor.() -> Unit
    ): Boolean = try {
        prefs.edit().apply(block).apply()
        true
    } catch (e: Exception) {
        Log.w(TAG, "commit failed", e)
        false
    }

    /** 目标里没有同名 key（跨版本新增项）时，按 JSON 字面类型落盘。 */
    private fun putUntyped(
        e: SharedPreferences.Editor, key: String, obj: JSONObject, skipped: MutableList<String>
    ) {
        val raw = try {
            obj.get(key)
        } catch (err: Exception) {
            skipped += "$key（读取失败）"
            return
        }
        try {
            when (raw) {
                is Boolean -> e.putBoolean(key, raw)
                is Int -> e.putInt(key, raw)
                is Long -> e.putLong(key, raw)
                // 【坑】Kotlin 对 `is Double, is Float` 这种联合分支不做 smart cast 成员调用，
                // 必须显式落到 Number 上再转，否则编译器只找到 String.toFloat()。
                is Double, is Float -> e.putFloat(key, (raw as Number).toFloat())
                is String -> e.putString(key, raw)
                is JSONArray -> e.putStringSet(
                    key, buildSet { for (i in 0 until raw.length()) add(raw.optString(i)) }
                )
                else -> skipped += "$key（不支持的类型 ${raw.javaClass.simpleName}）"
            }
        } catch (err: Exception) {
            skipped += "$key（写入失败：${err.message}）"
        }
    }

    /**
     * 导入后的引擎同步 —— 不做这一步会出现"文件里是新值、但 native 还在按旧值发声"。
     * 只调用服务侧现成的 public setter，不碰内部字段，避免把刚修好的状态机又搞乱。
     */
    fun applyToEngine(context: Context): List<String> {
        val applied = mutableListOf<String>()
        val svc = com.sdw.music.player.MusicService.instance

        runCatching {
            val s = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            svc?.setCrossfadeEnabled(s.getBoolean("crossfade_enabled", false))
            svc?.setCrossfadeDurationMs(s.getInt("crossfade_duration_ms", 15000))
            svc?.setCrossfadeBars(s.getInt("crossfade_bars", 0))
            // 【P3】Master Sync 真实同步。它只在服务启动时读一次 prefs，备份恢复属于
            // "运行时改 prefs" 的场景，不在这里同步的话要等服务重启才生效。
            com.sdw.music.player.MasterSyncShadow.enabled =
                s.getBoolean("crossfade_master_sync", false)
            svc?.applyLiveFx()
            applied += "交叉淡化 / DJ 效果"
        }

        runCatching {
            val d = context.getSharedPreferences("dsp_eq", Context.MODE_PRIVATE)
            if (d.getBoolean("enabled", false)) {
                svc?.restoreCustomDspEq()
                applied += "DSP EQ"
            }
        }

        runCatching {
            val c = context.getSharedPreferences("compressor", Context.MODE_PRIVATE)
            svc?.applyCompressor(
                c.getBoolean("enabled", false),
                c.floatValue("threshold", 0f), c.floatValue("ratio", 4f),
                c.floatValue("attack", 30f), c.floatValue("release", 250f), c.floatValue("makeup", 0f)
            )
            val l = context.getSharedPreferences("loudness", Context.MODE_PRIVATE)
            svc?.applyLoudness(l.getBoolean("enabled", false), l.floatValue("intensity", 1f))
            val n = context.getSharedPreferences("loudness_norm", Context.MODE_PRIVATE)
            svc?.applyNormalization(n.floatValue("target_db", -3f))
            val x = context.getSharedPreferences("crossfeed", Context.MODE_PRIVATE)
            svc?.applyCrossfeed(if (x.getBoolean("enabled", false)) x.floatValue("amount", 0.5f) else 0f)
            applied += "压缩器 / 响度 / 交叉馈送"
        }

        runCatching {
            val mseb = com.sdw.music.player.MsebCalculator
            if (mseb.isEnabled(context)) {
                svc?.applyMsebEq(mseb.calculateGains(mseb.load(context)), null, null)
                applied += "MSEB"
            }
        }

        if (applied.isEmpty()) applied += "（无需要下发到播放引擎的项）"
        Log.i(TAG, "applyToEngine: $applied")
        return applied
    }

    // ── helpers ──

    /**
     * 【V8.212】老备份（整段缺 types 段）的兜底类型推断。
     * 老备份里整数值浮点会被 org.json 削成 Integer，解析回来是 int，直接 putInt 落盘就会污染
     * （之后 getFloat 读就抛 ClassCastException）。三重保险确定真实类型：
     *  1. 已知 float 键白名单（领域知识最权威，能纠正「本机已是脏 int」的情形）；
     *  2. 本机既有类型（本机已存在该 key，沿用本机类型，保持与现状一致）；
     *  3. JSON 字面类型（本机没有时只能按文本推断）。
     */
    private fun inferType(name: String, obj: JSONObject, key: String, existing: Any?): String {
        // 1) 已知 float 键：无论 JSON 退化成什么、本机是不是脏 int，强制按 float 写回
        if (Pair(name, key) in FLOAT_KEYS) return "f"
        // 2) 本机已有该 key：沿用本机既有类型（保持与现状一致）
        typeTagOf(existing)?.let { return it }
        // 3) 本机没有：按 JSON 字面类型推断
        return when (val raw = runCatching { obj.get(key) }.getOrNull()) {
            is Boolean -> "b"
            is Int -> "i"
            is Long -> "l"
            is Double, is Float -> "f"
            is String -> "s"
            is JSONArray -> "S"
            else -> ""
        }
    }

    /**
     * 值的类型标记。导出时写进 types 段，导入时据此还原 —— 单看 JSON 文本分不出
     * `1` 到底是 int 还是 float，这是整个备份功能最容易踩的一颗雷。
     */
    private fun typeTagOf(v: Any?): String? = when (v) {
        is Boolean -> "b"
        is Int -> "i"
        is Long -> "l"
        is Float -> "f"
        is String -> "s"
        is Set<*> -> "S"
        else -> null
    }

    /** SharedPreferences 没有公开枚举入口，最直接的办法就是看 shared_prefs 目录。 */
    private fun allPrefsNames(context: Context): List<String> = runCatching {
        val dir = File(context.filesDir.parentFile, "shared_prefs")
        dir.listFiles()
            ?.filter { it.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }
            ?.sorted()
            ?: emptyList()
    }.getOrDefault(emptyList())

    private fun safeVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun stamp(): String = runCatching {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.CHINA)
        f.timeZone = TimeZone.getDefault()
        f.format(Date())
    }.getOrDefault("?")
}
