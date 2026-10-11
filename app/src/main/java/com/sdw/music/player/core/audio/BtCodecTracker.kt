package com.sdw.music.player.core.audio

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager

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

    /** 已经弹过授权框（避免 LaunchedEffect 每帧重复弹窗）。 */
    @Volatile
    private var permissionAsked = false

    // ── 【V8.216】后台检测线程池 + 并发守卫 ──
    // 存在的唯一理由：detect() 会绑 A2DP proxy，最坏要等 8 秒（见 getA2dpProxy）。
    // 这种量级的等待绝不能发生在调用线程上（调用线程是主线程），所以一律走这里。
    private val bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "bt-codec-detect").apply { isDaemon = true }
    }
    private val refreshInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pendingCallbacks = mutableListOf<(Float) -> Unit>()

    /** 当前是否已具备查询 A2DP codec 的权限。 */
    fun hasPermission(context: Context): Boolean = hasConnectPermission(context)

    /**
     * 缺权限就把系统授权框拉起来（只弹一次）。只在「确实有蓝牙输出」时调用——
     * 有线/扬声器场景下根本不需要 BLUETOOTH_CONNECT，别无端骚扰用户。
     */
    fun requestPermissionIfNeeded(context: Context): Boolean {
        if (hasConnectPermission(context)) { permissionAsked = false; return true }
        if (permissionAsked) return false
        // Compose 的 LocalContext 往往是 ContextThemeWrapper，得沿着 baseContext 剥到 Activity
        var act: android.app.Activity? = null
        var ctx: Context? = context
        while (ctx != null && act == null) {
            act = ctx as? android.app.Activity
            ctx = (ctx as? android.content.ContextWrapper)?.baseContext
        }
        if (act == null) return false
        permissionAsked = true
        try {
            act.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 8821)
        } catch (_: Throwable) { }
        return false
    }

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

    /**
     * 【V8.216】后台刷新检测 —— **绝不阻塞调用线程**。
     *
     * 旧实现是 `pool.submit { detect() }.get(12s)`：注释写着"扔到独立线程，
     * 不能在调用线程上裸等"，但紧跟着的 `.get()` 恰恰把调用线程死等最多 12 秒。
     * 独立线程只是让"绑 A2DP proxy"不在调用线程上执行，等待仍然同步。
     *
     * 而 detect() → queryCodecApi31() → getA2dpProxy() 里 latch.await(8s)
     * + Thread.sleep(300)，于是真实等待约 8 秒。调用链是：
     *   AudioDeviceCallback.onAudioDevicesAdded（**主线程**）
     *     → refreshBtPreEmphasis() → applyBtPreEmphasis() → refresh()
     * 表现就是「一连蓝牙就卡死」—— logcat 上那帧 Davey! duration=8066ms 正是它。
     *
     * 现在：立即返回上次已知 dB（首次为 0 = 旁路，蓝牙未知时也不会污染听感），
     * 检测整体丢到后台线程，出结果后通过 onResult 回调（调用方自行切线程）。
     */
    fun refreshAsync(context: Context, onResult: ((Float) -> Unit)? = null): Float {
        val appCtx = context.applicationContext
        // 已有刷新在跑：只挂回调，不重复起线程（蓝牙反复插拔时不雪崩）
        if (!refreshInFlight.compareAndSet(false, true)) {
            synchronized(pendingCallbacks) {
                onResult?.let { pendingCallbacks += it }
            }
            return currentCodecDb
        }
        synchronized(pendingCallbacks) {
            onResult?.let { pendingCallbacks += it }
        }
        bgExecutor.execute {
            val db = try {
                val d = detect(appCtx)
                currentCodecDb = d
                d
            } catch (_: Throwable) {
                // 【V8.178】超时不把名字写成"有线"：detect() 可能根本没跑完，
                // 此时 isBluetoothOutput=false 只是"未知"，不是"确认有线"。
                if (isBluetoothOutput) DB_AAC else DB_NONE
            }
            val cbs = synchronized(pendingCallbacks) {
                val c = pendingCallbacks.toList()
                pendingCallbacks.clear()
                c
            }
            refreshInFlight.set(false)
            for (cb in cbs) runCatching { cb.invoke(db) }
        }
        return currentCodecDb
    }

    /**
     * @deprecated 会阻塞调用线程（旧实现），保留仅为兼容老调用点。
     * 现在立即返回上次已知值，并在后台刷新；需要结果请用 [refreshAsync]。
     */
    @Deprecated("阻塞调用线程，改用 refreshAsync")
    fun refresh(context: Context): Float = refreshAsync(context, null)

    private fun detect(context: Context): Float {
        // 1) 当前音频输出是否为蓝牙
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return DB_NONE
        val btOut = try {
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            // 【V8.178】加 TYPE_BLE_HEADSET/TYPE_BLE_SPEAKER：Android 14+ 走 LE Audio
            // 的耳机不再报 A2DP（TYPE_BLUETOOTH_A2DP），只报 BLE 设备类型 —— 不加这条
            // 蓝牙耳机会被判成"有线"（isBluetoothOutput=false → DtsLinkRow 显示 Wired）。
            devices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            }
        } catch (_: Throwable) { false }
        isBluetoothOutput = btOut
        if (!btOut) {
            currentCodecName = "有线"
            return DB_NONE
        }

        // 2) 【V8.174】免权限主力路径：AudioDeviceInfo.getEncodedFormats()。
        //    不需要 BLUETOOTH_CONNECT、不需要 A2DP proxy、不受 CDM 限制。
        runCatching { queryEncodedFormats(context) }.getOrNull()?.let {
            currentCodecDb = it
            return it
        }

        // 3) 有权限就走精确栈查询，能拿到 "LHDC V5" 这种带版本号的写法
        if (Build.VERSION.SDK_INT >= 31 && hasConnectPermission(context)) {
            val precise = runCatching { queryCodecApi31(context) }.getOrNull()
            if (precise != null) {
                currentCodecDb = precise
                return precise
            }
            // queryCodecApi31 内部可能已经把名字摸出来了（只是拿不到最终 db），别覆盖掉
            if (currentCodecName != "—") return currentCodecDb
        }

        // 4) 读不到编码 —— 不编造名字（旧版硬写 "SBC?"，把 LHDC v5 用户当成低码率链路），
        //    也不往界面塞"未知编码"这种废话，保持 "—" 让 UI 自己回落成 "Bluetooth"。
        requestPermissionIfNeeded(context)
        // 【V8.175】保守档从 2.0dB 降到 0.8dB：Android 16 上问不出 codec 的场景几乎
        // 全是高码率链路（LHDC/LDAC 被 CDM association 挡住），还按 SBC 打 2dB 高频
        // 补偿会把 EDM 的镲片和人声齿音直接推爆；0.8dB 对真 SBC 也有帮助、对高清链路无感。
        return DB_AAC
    }

    /**
     * 【V8.174】读蓝牙编码的"免权限"路径。
     *
     * Android 16 上 BluetoothA2dp.getCodecStatus() 被系统用 CDM(Companion Device
     * Manager) association 卡死，普通应用一律抛 SecurityException，这条路基本废了。
     * 但 A2DP 的 AudioDeviceInfo 上一直挂着 getEncodedFormats()（Android 13+ 公开 API），
     * `dumpsys media.audio_policy` 里那行 "Encoded formats: AUDIO_FORMAT_LHDC"
     * 正是从这里出来的 —— 只要能枚举输出设备就能读到，权限与 service 绑定一概不需要。
     *
     * AudioFormat 里那一堆 ENCODING_* 常量值厂商各版本可能变，所以这里不写死数字，
     * 而是反射把 AudioFormat 的静态 int 字段抓回来做反查表。
     */
    private fun queryEncodedFormats(context: Context): Float? {
        if (Build.VERSION.SDK_INT < 33) return null
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val allDevs = try { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } catch (_: Throwable) { return null }
        // 【V8.178】LE Audio 耳机走 TYPE_BLE_HEADSET，与上面 btOut 判定保持同一口径
        val dev = allDevs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        } ?: return null

        val fmts = try {
            dev.javaClass.getMethod("getEncodedFormats").invoke(dev) as? IntArray
                ?: return null
        } catch (e: Throwable) {
            return null
        }
        if (fmts.isEmpty()) return null

        // ENCODING_* 常量值反查表：value -> 常量名（去掉 ENCODING_ 前缀）
        val nameByValue = HashMap<Int, String>()
        for (f in AudioFormat::class.java.declaredFields) {
            if (!f.name.startsWith("ENCODING_")) continue
            if (f.type != Int::class.javaPrimitiveType) continue
            runCatching {
                f.isAccessible = true
                val v = f.get(null) as? Int ?: return@runCatching
                if (v != 0 && !nameByValue.contains(v)) nameByValue[v] = f.name.removePrefix("ENCODING_")
            }
        }
        if (nameByValue.isEmpty()) return null

        // 一个设备可能同时报多个格式（含 PCM 主混音），挑最有信息量的那个
        val rank = mapOf(
            "LHDC" to 60, "LHDC_LLAC" to 61, "LHDC_V5" to 62, "LDAC" to 55,
            "APTX" to 50, "APT_X" to 50, "VORBIS" to 45, "OPUS" to 45,
            "AAC_HE_V2" to 40, "AAC_HE_V1" to 40, "AAC_ELD" to 40, "AAC" to 40,
            "DOLBY_MAT" to 35, "E_AC3" to 30, "AC3" to 30, "DTS_UHD_PCM" to 25,
            "DTS_UHD" to 25, "DTS" to 25, "SBC" to 10
        )
        var bestName: String? = null
        var bestRank = -1
        for (f in fmts) {
            val n = nameByValue[f] ?: continue
            if (n.startsWith("PCM")) continue
            val r = rank[n] ?: 5
            if (r > bestRank) { bestRank = r; bestName = n }
        }
        val name = bestName ?: return null

        // AudioDeviceInfo 只有 getSampleRates()（复数，该设备支持的全部），取最大的那个
        val srHz = runCatching {
            dev.javaClass.getMethod("getSampleRates").invoke(dev) as? IntArray
        }.getOrNull()?.maxOrNull()?.toLong() ?: 0L
        val db = when (name) {
            "SBC" -> { currentCodecName = "SBC"; DB_SBC }
            "AAC" -> { currentCodecName = "AAC"; DB_AAC }
            else -> {
                currentCodecName = hiresLabel(name, srHz)
                DB_HIRES
            }
        }
        return db
    }

    /** 高码率编码的显示名：带上采样率，比光秃秃一个 "LHDC" 有信息量。 */
    private fun hiresLabel(name: String, srHz: Long): String {
        val pretty = when (name) {
            "LHDC_LLAC" -> "LHDC LLAC"
            "LHDC_V5" -> "LHDC V5"
            "LDAC" -> "LDAC"
            "APTX" -> "aptX"
            else -> name.replace('_', ' ')
        }
        return if (srHz > 0) "$pretty ${srHz / 1000}k" else pretty
    }


    /**
     * 老 API getProfileProxy 同步取 A2DP profile（API 11+，兼容 minSdk 24）。
     * 【V8.174】注意：不再在 finally 里 closeProfileProxy —— close 会立刻触发
     * onServiceDisconnected、把 proxy 内部的 mService 清成 null，调用方随后每次
     * invoke 都会撞上 "Proxy not attached to service"。这层旧 bug 把所有查询
     * 闷死在了起跑线上。关闭职责移交给调用方查完再关。
     */
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
            // 【V8.174】2s 在部分 ROM 上不够（系统服务忙时要 3~6s 才回调），放到 8s。
            latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
            proxy as? BluetoothA2dp
        } catch (_: Throwable) {
            null
        }
    }

    /** API 31+ 反射查询 A2DP codec。返回 null 表示无法获取（走保守档）。 */
    private fun queryCodecApi31(context: Context): Float? {
        var a2dp: BluetoothA2dp? = null
        return try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
            val adapter = bm.adapter ?: return null
            if (!adapter.isEnabled) return null
            // 【V8.174】优先从 AudioManager 拿活跃蓝牙地址（免 proxy、稳定），
            // getConnectedDevices 在部分 Android 16 ROM 上 proxy 绑了也返回空。
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
            val addr = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }?.address
            a2dp = getA2dpProxy(adapter, context) ?: return null
            Thread.sleep(300)   // onServiceConnected 回来后 binder 偶尔还要半拍才真正就绪
            val remote = addr?.let { runCatching { adapter.getRemoteDevice(it) }.getOrNull() }

            // 【V8.174】getCodecStatus 自 Android 13 起改成 getCodecStatus(BluetoothDevice)，
            // Android 16 上无参版本已经从 framework 删掉 —— 写死无参 getMethod 必抛
            // NoSuchMethodException（这正是 LHDC v5 被显示成"未知编码"的直接原因）。
            // 改成按方法表找同名方法、按参数个数适配。
            val csMethod = BluetoothA2dp::class.java.methods
                .firstOrNull { it.name == "getCodecStatus" } ?: return null
            val status = try {
                when {
                    csMethod.parameterCount == 1 && remote != null -> csMethod.invoke(a2dp, remote)
                    csMethod.parameterCount == 0 -> csMethod.invoke(a2dp)
                    else -> null   // 带参版但拿不到地址
                }
            } catch (_: Throwable) {
                return null   // Android 16 上多半是 CDM association 挡着，静默降级
            } ?: return null

            val (name, sr) = codecNameFrom(status) ?: return null
            // 【V8.174】栈里报上来的名字五花八门（"LHDCV5"/"LHDC 5.0"/"aptX-Adaptive"…
            // AOSP 只认 SBC/AAC/LDAC/aptX 系，其余原样透出），这里统一成常见写法。
            val db = if (name.isNullOrBlank()) {
                if (sr > 48000) { currentCodecName = "HiRes(${sr / 1000}k)"; DB_HIRES }
                else { currentCodecName = "蓝牙"; DB_SBC }
            } else {
                dbFor(name!!, sr)
            }
            currentCodecDb = db
            db
        } catch (_: Throwable) {
            null
        } finally {
            // 查完再关（close 会把 proxy 的 mService 清掉，提前关 = 查询必败）
            a2dp?.let { p ->
                try {
                    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                        ?.adapter?.closeProfileProxy(BluetoothProfile.A2DP, p)
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * 从 CodecStatus 里挖出 (编码名, 采样率)。
     * 新栈是 status.getCodecType() 返回带 getName() 的对象；老栈是
     * status.getCodecConfig().getCodecType() 返回 int。两种都兜住。
     */
    private fun codecNameFrom(status: Any): Pair<String?, Long>? {
        // 采样率优先从 CodecConfig 拿（CodecStatus 上没有这个方法，getCodecSpecific1 是厂商私域字段）
        val sr = runCatching {
            val cfg = status.javaClass.getMethod("getCodecConfig").invoke(status)
            (cfg?.javaClass?.getMethod("getSampleRate")?.invoke(cfg) as? Number)?.toLong() ?: 0L
        }.getOrDefault(0L)
        val ct = runCatching {
            status.javaClass.getMethod("getCodecType").invoke(status)
        }.getOrNull() ?: runCatching {
            val cfg = status.javaClass.getMethod("getCodecConfig").invoke(status)
            cfg?.javaClass?.getMethod("getCodecType")?.invoke(cfg)
        }.getOrNull() ?: return null

        return when (ct) {
            is String -> ct to sr
            is Number -> legacyCodecName(ct.toInt()) to sr
            else -> runCatching { ct.javaClass.getMethod("getName").invoke(ct) as? String }
                .getOrNull()?.let { it to sr }
        }
    }

    /** 老栈的 codec type int（BluetoothCodecConfig：SBC=0 AAC=1 APTX=2 APTX_HD=3 LDAC=4）。 */
    private fun legacyCodecName(id: Int): String? = when (id) {
        0 -> "SBC"; 1 -> "AAC"; 2 -> "aptX"; 3 -> "aptX HD"; 4 -> "LDAC"
        else -> null
    }

    /** 栈名 → 显示名 + 预补偿增益。认不出来的厂商私有编码原样透出，不当成 SBC。 */
    private fun dbFor(rawName: String, srHz: Long): Float {
        val n = rawName.uppercase()
        return when {
            n.contains("SBC") -> { currentCodecName = "SBC"; DB_SBC }
            n.contains("AAC") -> { currentCodecName = "AAC"; DB_AAC }
            n.contains("LDAC") -> { currentCodecName = "LDAC"; DB_HIRES }
            n.contains("LHDC") || n.contains("LLAC") -> {
                currentCodecName = if (srHz >= 90000) "LHDC 5" else "LHDC"
                DB_HIRES
            }
            n.contains("APTX") -> {
                currentCodecName = when {
                    n.contains("ADAPTIVE") -> "aptX Adaptive"
                    n.contains("TWS") -> "aptX TWS"
                    n.contains("LOW LATENCY") || n.contains("LL") -> "aptX LL"
                    n.contains("HD") -> "aptX HD"
                    else -> "aptX"
                }
                DB_HIRES
            }
            else -> { currentCodecName = rawName; DB_HIRES }
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
