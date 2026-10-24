package com.sdw.music.player.core.audio.engine
import com.sdw.music.player.core.audio.DebugLog
import com.sdw.music.player.core.audio.engine.DacProfile
import com.sdw.music.player.core.audio.analysis.MediaProbe
import com.sdw.music.player.core.audio.engine.OboeDirectPlayer
import com.sdw.music.player.core.audio.engine.UsbDacManager

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.sdw.music.player.Song
import java.util.concurrent.ConcurrentHashMap

data class UsbDacDevice(val name: String, val vid: Int, val pid: Int)
data class UsbEndpointInfo(val address: Int, val maxPacketSize: Int, val interval: Int, val isUac2: Boolean)

object UsbDacManager {
    private const val TAG = "UsbDacManager"
    private const val USB_CLASS_AUDIO = 1
    private const val SUBCLASS_AUDIOCONTROL = 1
    private const val SUBCLASS_AUDIOSTREAMING = 2
    private const val PROTOCOL_UAC2 = 0x20
    private const val ACTION_USB_PERMISSION = "com.sdw.music.player.USB_PERMISSION"
    private const val FIND_DACS_COOLDOWN_MS = 5000L

    @Volatile private var isNativeLoaded = false
    @Volatile private var contextRef: Context? = null
    @Volatile private var permissionReceiver: BroadcastReceiver? = null
    @Volatile private var hotplugReceiver: BroadcastReceiver? = null
    // [Hotplug] Fired after a USB DAC attaches (cache already invalidated). MusicService
    // registers this to (re)claim the DAC and auto-resume playback, so plugging in a DAC
    // no longer requires an app restart to start playing.
    var onDacAttached: (() -> Unit)? = null
    // [Hotplug] Fired when a USB DAC is detached. MusicService uses this to remember whether
    // playback was active so it can auto-resume only when the DAC was genuinely playing.
    var onDacDetached: (() -> Unit)? = null
    @Volatile private var initAttempted = false
    @Volatile private var cachedUsbDevice: UsbDevice? = null
    private var lastFindDacsTime = 0L
    @Volatile private var dumpedDeviceId = -1

    // [P1 adaptive-DAC] descriptor-derived capability snapshot for the currently
    // claimed DAC. Filled right after nativeClaim succeeds, consumed by
    // DacProfile.find() so per-model hand-writing becomes unnecessary.
    @Volatile var activeDescriptor: DacDescriptor? = null

    /** path → 源位深 缓存（claim 阶段探测，避免切歌重复读文件头） */
    private val sourceBitDepthCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // ============================================================
    // [NeriPlayer 引擎] USB 独占输出引擎切换
    //   "neri"   = NeriPlayer 移植引擎（默认）：libusb 会话 + PcmPipeline +
    //              显式 feedback 闭环 + 恢复 latch，切歌只 flush 不拆流。
    //   "legacy" = 旧 usb_audio_driver（逃生通道）。
    // 注意：DSP 链（MSEB/EQ5/DTS/限幅等）目前只在 legacy 路径生效；
    // neri 路径下这些设置为空操作（日志会说明），播放稳定性优先。
    // ============================================================
    private const val PREF_USB_ENGINE = "usb_engine"
    @Volatile private var neriEngine = true
    fun useNeriEngine(): Boolean = neriEngine
    fun getUsbEngine(): String = if (neriEngine) "neri" else "legacy"
    fun setUsbEngine(engine: String) {
        neriEngine = (engine != "legacy")
        setFlacPullMode(neriEngine)
        try {
            contextRef?.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                ?.edit()?.putString(PREF_USB_ENGINE, if (neriEngine) "neri" else "legacy")?.apply()
        } catch (_: Exception) {}
        DebugLog.add(TAG, "engine → ${getUsbEngine()}")
    }

    /** NeriPlayer 引擎会话（同一时刻只有一个活跃会话） */
    val neriSession = NeriUsbSession()
    @Volatile private var neriStreaming = false
    @Volatile private var neriDeviceName: String = ""
    private var lastNeriStreamCheckMs = 0L

    // [P2] Session-level auto-probe override: an unknown DAC that the silence
    // watchdog judged dead/silent is remembered here so the rest of the session
    // routes straight to Oboe (no repeated Host-Exclusive attempts every track).
    private val forcedOboeDacs = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Int, Int>>()
    fun forceOboeFor(vid: Int, pid: Int) { forcedOboeDacs.add(vid to pid) }
    fun isForcedOboe(vid: Int, pid: Int): Boolean = forcedOboeDacs.contains(vid to pid)
    fun clearForcedOboe() { forcedOboeDacs.clear() }

    private data class PendingClaim(
        val device: UsbDevice,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val ifaceNum: Int
    )
    @Volatile private var pendingClaim: PendingClaim? = null
    // [Debounce] deviceId for which a USB permission request is currently outstanding.
    // Prevents the same DAC (which re-enumerates on plug-in) from popping the system
    // permission dialog 2-3 times in a row.
    @Volatile private var permissionRequestingDeviceId: Int = -1

    // ============================================================
    // [V9.20x] Hotplug gate — event generations + open cooling window
    //
    // A miniature of NeriPlayer's UsbExclusiveSessionOpenGate. A DAC re-enumerates
    // on plug-in (2-3 ATTACHED broadcasts), and a flaky OTG contact emits
    // DETACHED/ATTACHED pairs we must not chase. Three rules:
    //   1. an ATTACH is honoured only if a DETACH actually preceded it
    //      (lastDetachGen > lastAttachGen) — otherwise it is a duplicate;
    //   2. a DETACH arms a cooling window; claimAndStart() refuses to open into a
    //      bus that is still settling;
    //   3. re-attaching clears any block derived from the detach, so the cooling
    //      window can never keep a freshly returned device locked out.
    // ============================================================
    private val hotplugLock = Any()
    private var deviceEventGen = 0L
    private var lastDetachGen = 0L
    // -1 (not 0) so the very first attach of the process is honoured: it must satisfy
    // lastDetachGen(0) > lastAttachGen(-1). Starting both at 0 would make the first
    // plug-in look like a duplicate and silently skip the initial auto-claim.
    private var lastAttachGen = -1L
    @Volatile private var openBlockedUntilMs = 0L
    @Volatile private var openBlockReason = ""

    /** Bus settling time after a detach, before we allow a fresh open. */
    private const val DETACH_COOLDOWN_MS = 1_500L

    /** Returns a non-null reason when the gate forbids opening right now. */
    private fun hotplugGateError(): String? {
        val remaining = openBlockedUntilMs - System.currentTimeMillis()
        if (remaining > 0L) return "$openBlockReason remainingMs=$remaining"
        return null
    }

    /**
     * Shared detach bookkeeping for BOTH entry points: the system
     * ACTION_USB_DEVICE_DETACHED broadcast and native surprise-removal detection.
     * Everything that must happen exactly once per removal lives here so the two
     * paths cannot drift apart.
     */
    private fun handleDeviceDetached(reason: String) {
        synchronized(hotplugLock) {
            deviceEventGen += 1L
            lastDetachGen = deviceEventGen
            val until = System.currentTimeMillis() + DETACH_COOLDOWN_MS
            if (until > openBlockedUntilMs) {
                openBlockedUntilMs = until
                openBlockReason = "usb_device_detached:$reason"
            }
        }
        cachedUsbDevice = null
        lastFindDacsTime = 0L
        clearForcedOboe()   // [P2] drop any session Oboe override; next device starts fresh
        permissionRequestingDeviceId = -1   // [Debounce] allow a fresh request on next attach
        DebugLog.add(TAG, "hotplug: device detached (reason=$reason)")
        onDacDetached?.invoke()
    }

    /**
     * Shared attach bookkeeping. Invalidates the cached device (so findDacs() rescans)
     * on every attach, but only fires [onDacAttached] for an attach that follows a real
     * detach — spurious re-enumeration broadcasts must not trigger a reclaim storm.
     */
    private fun handleDeviceAttached(reason: String) {
        val accepted = synchronized(hotplugLock) {
            if (lastDetachGen <= lastAttachGen) {
                DebugLog.v(TAG, "hotplug: ignore ATTACHED ($reason) — no detach since last attach")
                false
            } else {
                deviceEventGen += 1L
                lastAttachGen = deviceEventGen
                // Device is back: drop the cooling window derived from the detach.
                if (openBlockReason.startsWith("usb_device_detached")) {
                    openBlockedUntilMs = 0L
                    openBlockReason = ""
                }
                true
            }
        }
        cachedUsbDevice = null
        lastFindDacsTime = 0L
        if (!accepted) return
        DebugLog.add(TAG, "hotplug: device attached (reason=$reason)")
        if (!UsbExclusivePrefs.read(contextRef).respondToAttach) {
            DebugLog.add(TAG, "hotplug: respond-to-attach disabled, ignoring attach")
            return
        }
        onDacAttached?.invoke()
    }

    /**
     * 【V9.20x】Native surprise-removal fallback. Some OTG adapters/hubs drop the bus
     * without a timely (or any) ACTION_USB_DEVICE_DETACHED, and URB submission is the
     * only thing that notices — it starts returning ENODEV. Returns true at most once
     * per vanish event; on true the full detach path has already run, so the caller
     * must not also treat the current pushPcm failure as a transient stall.
     */
    fun pollDeviceVanished(): Boolean {
        if (!isNativeLoaded) return false
        if (!nativeConsumeDeviceVanished()) return false
        handleDeviceDetached("enodev")
        return true
    }

    // ============================================================
    // Public API
    // ============================================================

    fun init(context: Context) {
        if (isNativeLoaded || initAttempted) return
        initAttempted = true
        val appCtx = context.applicationContext
        contextRef = appCtx

        DebugLog.v(TAG, "init: loading native lib oboe_bridge...")
        try {
            System.loadLibrary("oboe_bridge")
            isNativeLoaded = true
            DebugLog.add(TAG, "init: oboe_bridge loaded OK")
        } catch (e: UnsatisfiedLinkError) {
            isNativeLoaded = false
            DebugLog.add(TAG, "init: oboe_bridge FAIL ${e.message}")
        }

        registerPermissionReceiver(appCtx)
        registerHotplugReceiver(appCtx)

        // [NeriPlayer 引擎] 读取引擎选择（默认 neri）
        try {
            val eng = appCtx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                .getString(PREF_USB_ENGINE, "neri")
            neriEngine = (eng != "legacy")
            } catch (_: Exception) { neriEngine = true }
        setFlacPullMode(neriEngine)

        DebugLog.v(TAG, "init done, nativeLoaded=$isNativeLoaded engine=${getUsbEngine()}")
    }

    fun findDacs(): List<UsbDacDevice> {
        val now = System.currentTimeMillis()
        if (cachedUsbDevice != null && (now - lastFindDacsTime) < FIND_DACS_COOLDOWN_MS) {
            DebugLog.v(TAG, "findDacs: cached → ${cachedUsbDevice!!.productName}")
            return listOf(UsbDacDevice(
                name = cachedUsbDevice!!.productName ?: "USB DAC",
                vid = cachedUsbDevice!!.vendorId,
                pid = cachedUsbDevice!!.productId
            ))
        }

        val ctx = contextRef
        if (ctx == null) { DebugLog.add(TAG, "findDacs: contextRef=null"); return emptyList() }
        val usbMgr = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbMgr == null) { DebugLog.add(TAG, "findDacs: usbManager=null"); return emptyList() }

        val dacs = mutableListOf<UsbDacDevice>()
        DebugLog.v(TAG, "findDacs: scanning ${usbMgr.deviceList.size} USB devices...")
        for ((_, dev) in usbMgr.deviceList) {
            val audio = isAudioDevice(dev)
            DebugLog.v(TAG, "findDacs: vid=${dev.vendorId.toString(16)} pid=${dev.productId.toString(16)} name=${dev.productName} ifaceCount=${dev.interfaceCount} audio=$audio")
            if (audio) {
                cachedUsbDevice = dev
                maybeDumpStructure(dev)
                dacs.add(UsbDacDevice(
                    name = dev.productName ?: "USB DAC (${dev.vendorId}:${dev.productId})",
                    vid = dev.vendorId, pid = dev.productId
                ))
            }
        }
        lastFindDacsTime = now
        // 【修复】设备消失时清除幽灵缓存：否则 getDacDevice() 一直返回已断开的 stale 对象，
        // playSong 拿旧设备反复 claim 失败（openDevice FAIL / no permission）死循环。
        if (dacs.isEmpty()) cachedUsbDevice = null
        DebugLog.add(TAG, "findDacs: result=${dacs.size} DAC(s) found")
        return dacs
    }

    fun getDacDevice(): UsbDevice? = cachedUsbDevice

    fun requestPermission(device: UsbDevice, context: Context) {
        val ctx = context.applicationContext
        val usbMgr = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        if (usbMgr.hasPermission(device)) {
            DebugLog.v(TAG, "perm: already have for ${device.productName}")
            return
        }
        // [Debounce] if a request for this device is already outstanding, don't pop the
        // system dialog again (DACs often re-enumerate → multiple ATTACHED broadcasts).
        if (permissionRequestingDeviceId == device.deviceId) {
            DebugLog.v(TAG, "perm: request already pending for ${device.productName}, skip")
            return
        }
        permissionRequestingDeviceId = device.deviceId
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        usbMgr.requestPermission(device, pi)
        DebugLog.add(TAG, "perm: requesting for ${device.productName}")
    }

    /** [NeriPlayer 设备卡] 当前已连接 DAC 是否持有 USB 权限。 */
    fun hasUsbPermission(): Boolean {
        val dev = cachedUsbDevice ?: return false
        val ctx = contextRef ?: return false
        val usbMgr = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return usbMgr.hasPermission(dev)
    }

    /** [NeriPlayer 设备卡 "USB 授权"] 为当前已连接 DAC 发起权限请求。 */
    fun requestUsbPermission(context: Context) {
        val dev = cachedUsbDevice ?: run {
            DebugLog.add(TAG, "perm: no attached DAC to request for")
            return
        }
        requestPermission(dev, context)
    }

    fun claimAndStart(
        device: UsbDevice, sampleRate: Int, channels: Int, bitsPerSample: Int, ifaceNum: Int = -1
    ): Boolean {
        val ctx = contextRef
        if (ctx == null) { DebugLog.add(TAG, "claim: contextRef=null"); return false }
        val usbMgr = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbMgr == null) { DebugLog.add(TAG, "claim: usbManager=null"); return false }

        // [V9.20x] Hotplug gate: LOG ONLY — deliberately does not fail the claim.
        // Returning false here would be harmful: MusicService treats a failed claim as
        // "this DAC cannot do host-exclusive" and permanently downgrades the session to
        // Oboe (forceOboeFor + rewrites the audio_output preference). A bus-settle
        // window must never cost the user their USB-exclusive playback.
        // The real defence is the generation gate in handleDeviceAttached(), which drops
        // the duplicate ATTACHED broadcasts a re-enumerating DAC emits — that is where
        // the reclaim storm actually came from. Unlike NeriPlayer we have no async
        // native close in flight to wait for, so there is nothing to block on here.
        hotplugGateError()?.let { DebugLog.v(TAG, "claim: inside hotplug cooldown -> $it") }

        if (!usbMgr.hasPermission(device)) {
            pendingClaim = PendingClaim(device, sampleRate, channels, bitsPerSample, ifaceNum)
            DebugLog.add(TAG, "claim: no permission, queued")
            requestPermission(device, ctx)
            return false
        }

        if (!isNativeLoaded) { DebugLog.add(TAG, "claim: native not loaded"); return false }

        // ── [NeriPlayer 引擎] 路径 ──────────────────────────────────────────
        // 原生侧自行 claim 接口 / 协商 UAC 格式 / 选 feedback 端点，
        // Kotlin 不再做 claimInterface 舞蹈；只要 permission + fd。
        if (useNeriEngine()) {
            neriDeviceName = device.productName ?: "USB DAC"
            val prefs = UsbExclusivePrefs.read(ctx)
            // 请求格式：follow_source=按源；固定模式用模式值（原生选最接近的支持值）
            val reqRate = prefs.sampleRateMode.requestedSampleRateHz(sampleRate) ?: sampleRate
            val reqBits = prefs.bitDepthMode.requestedBitDepth(bitsPerSample) ?: 0  // 0=自动
            // BUSY 退避重试：快速切歌时上一首的接口释放/隔离排干可能仍在进行（nativeOpen 内部
            // 1500ms 冷却期未过），立即 claim 会吃 LIBUSB_ERROR_BUSY → 静默失败（实测快速连切时
            // 当前首哑掉、十几秒后才恢复）。这里用指数退避等接口释放：160ms+attempt*40ms × 12 次
            // ≈ 5s，覆盖冷却期仍有余量。格式类失败仍走降位深重试（24→16 由管线完成）。
            var ok = false
            var formatDowngraded = false
            var effectiveReqBits = reqBits
            for (attempt in 0 until 12) {
                ok = neriSession.open(usbMgr, device, reqRate, channels, effectiveReqBits)
                if (ok) break
                val err = try { NeriUsbNativeBridge.lastOpenError() } catch (_: Throwable) { "" }
                val isFormatError = err.contains("invalid_output_format") || err.contains("no_matching_alt") ||
                        err.contains("unsupported") || err.contains("format")
                if (isFormatError && !formatDowngraded && effectiveReqBits > 16) {
                    formatDowngraded = true
                    effectiveReqBits = 16
                    DebugLog.add(TAG, "claim(neri): DAC rejects ${reqBits}bit ($err) → downgrade to 16bit")
                    continue
                }
                val retryable = err.contains("BUSY") || err.contains("claim_interface_failed") ||
                        err.contains("wrap_sys_device_failed") || err.contains("NO_DEVICE")
                if (!retryable) {
                    DebugLog.add(TAG, "claim(neri): open FAIL non-retryable: $err")
                    break
                }
                DebugLog.add(TAG, "claim(neri): open busy, retry $attempt ($err)")
                try { Thread.sleep(160L + attempt * 40L) } catch (_: InterruptedException) { return false }
            }
            if (ok) {
                // 偏好下发：比特完美 + 环形缓冲时长（前后台较大者，原生按需调整）
                neriSession.setBitPerfect(prefs.bitPerfect)
                neriSession.configureBufferDuration(prefs.reservedBufferDurationMs())
                activeSampleRate = neriSession.outputSampleRate
                activeChannels = neriSession.outputChannels
                activeBits = neriSession.outputBits
                activeDescriptor = null  // neri 原生自解析描述符，Kotlin quirk 表不参与
                DebugLog.add(TAG, "claim(neri): ok → out ${activeSampleRate}Hz/${activeChannels}ch/${activeBits}bit " +
                    "(req ${reqRate}Hz/${reqBits.takeIf { it > 0 } ?: "auto"}bit, bitPerfect=${prefs.bitPerfect})")
            } else {
                DebugLog.add(TAG, "claim(neri): FAIL → ${NeriUsbNativeBridge.lastOpenError()}")
            }
            return ok
        }

        val conn = usbMgr.openDevice(device)
        if (conn == null) { DebugLog.add(TAG, "claim: openDevice FAIL"); return false }
        maybeDumpRaw(conn, device.deviceId)  // raw config + tSamFreq (before any claim/SETINTERFACE)

        val fd = conn.fileDescriptor

        // Dump all interfaces for debug
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            DebugLog.v(TAG, "claim: iface[$i] id=${iface.id} class=${iface.interfaceClass} subclass=${iface.interfaceSubclass} proto=${iface.interfaceProtocol} eps=${iface.endpointCount}")
        }

        // Find and claim audio streaming interfaces
        var targetIfaceNum = ifaceNum   // loop INDEX, used by getEndpointInfo
        var targetIfaceId = -1          // bInterfaceNumber, used by native USBDEVFS_SETINTERFACE
        var claimed = false
        // 【V3.2.7】先强制 claim AudioControl 接口(subclass=1)--SET_CUR 时钟命令的目标是
        // 控制接口上的 clock entity,不踢掉内核驱动对控制接口的占用,SET_CUR 永远 EBUSY。
        // Salt Player (libsaltusbaudio.so) 同款做法:全部强制 claim,系统 audio patch 会切回 SPEAKER。
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_AUDIO && iface.interfaceSubclass == SUBCLASS_AUDIOCONTROL) {
                val ok = conn.claimInterface(iface, true)
                DebugLog.v(TAG, "claim: force-claimed AudioControl iface id=${iface.id} → $ok")
            }
        }
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_AUDIO && iface.interfaceSubclass == SUBCLASS_AUDIOSTREAMING) {
                val hasOutEp = (0 until iface.endpointCount).any {
                    iface.getEndpoint(it).direction == android.hardware.usb.UsbConstants.USB_DIR_OUT
                }
                val hasInIsoEp = (0 until iface.endpointCount).any {
                    val ep = iface.getEndpoint(it)
                    ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN &&
                    (ep.attributes and 0x03) == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_ISOC
                }
                DebugLog.v(TAG, "claim: iface[$i] id=${iface.id} epCount=${iface.endpointCount} hasOutEp=$hasOutEp hasInIsoEp=$hasInIsoEp")
                if (hasOutEp) {
                    // Only the OUT (playback) streaming interface drives targetIfaceId/targetIfaceNum.
                    if (targetIfaceNum < 0) targetIfaceNum = i
                    if (targetIfaceId < 0) {
                        targetIfaceId = iface.id   // first OUT iface's bInterfaceNumber - never overwrite
                        conn.claimInterface(iface, true)
                        DebugLog.add(TAG, "claim: claimed OUT iface id=${iface.id} (index=$i)")
                        claimed = true
                    }
                } else if (hasInIsoEp) {
                    // [feedback] Claim the implicit-feedback / capture IN iface (e.g. Realtek
                    // 0BDA:4BA6 iface 1 alt 1, EP 0x81). By default the kernel snd-usb-audio
                    // driver holds it, so the native feedback loop's CLAIMINTERFACE gets EBUSY
                    // and the async closed-loop never arms -> open-loop clock drift -> "噼里啪啦".
                    // Android's claimInterface() detaches the kernel driver cleanly (the native
                    // USBDEVFS_DISCONNECT is a no-op / ENOTTY on this kernel), letting the
                    // native side own the iface and submit the feedback URB. We do NOT touch
                    // targetIfaceId/targetIfaceNum — those stay on the OUT iface.
                    val ok = conn.claimInterface(iface, true)
                    DebugLog.add(TAG, "claim: claimed feedback IN iface id=${iface.id} (index=$i) → $ok")
                }
            }
        }

        if (!claimed) { DebugLog.add(TAG, "claim: no audio streaming iface"); conn.close(); return false }

        val epInfo = getEndpointInfo(device, targetIfaceNum)
        if (epInfo == null) { DebugLog.add(TAG, "claim: no ISO OUT endpoint"); conn.close(); return false }

        val result = nativeClaim(
            device.vendorId, device.productId, fd,
            epInfo.address, epInfo.maxPacketSize, epInfo.interval,
            epInfo.isUac2, targetIfaceId
        )
        DebugLog.add(TAG, "claim: nativeClaim vid=${device.vendorId.toString(16)} pid=${device.productId.toString(16)} ep=0x${epInfo.address.toString(16)} mps=${epInfo.maxPacketSize} uac2=${epInfo.isUac2} ifaceId=$targetIfaceId idx=$targetIfaceNum → $result")
        if (result) {
            // 【P1 自适应 DAC】claim 后 native 已完成 descriptor 解析，把自动探测结果取回，
            // 后续 DacProfile.find() 用它合并出 wireBits / 硬件音量 FU / 44.1k 支持，
            // 标准兼容 DAC 无需手写任何条目即可「插上就用」。
            try {
                activeDescriptor = nativeGetDescriptorProfile()?.let { DacDescriptor.parse(it) }
                DebugLog.add(TAG, "claim: descriptor profile ${activeDescriptor ?: "(none)"}")
            } catch (e: Throwable) {
                DebugLog.add(TAG, "claim: descriptor parse failed: ${e.message}")
                activeDescriptor = null
            }

            // 【FU 覆盖】Kotlin 层 DacProfile 是单一数据源；固件不实现 GET_MIN/GET_MAX 的怪 DAC
            // 在 native 通用探测失败后，由这里注入 Salt-verified 的 Feature Unit 参数。
            val profile = DacProfile.find(device.vendorId, device.productId)
            // [2026-10-22] 用户按设备强制软件音量开关（dac_force_sw_vol_<vid>_<pid>）：
            // 硬件音量滑块不变声时的自助逃生通道，打开立即生效，无需重插。
            val forceSwKey = "dac_force_sw_vol_%04X_%04X".format(device.vendorId, device.productId)
            val userForceSw = try {
                ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                    .getBoolean(forceSwKey, false)
            } catch (_: Exception) { false }
            when {
                profile.forceSoftwareVolume -> {
                    // 设备 quirk：硬件 FU 已实证不衰减（SET 全 ACK 但音量不变）。
                    nativeForceSoftwareVolume()
                    DebugLog.add(TAG, "claim: hardware volume OFF (device quirk: FU verified dead) — software gain")
                }
                userForceSw -> {
                    nativeForceSoftwareVolume()
                    DebugLog.add(TAG, "claim: hardware volume OFF (user override for this device) — software gain")
                }
                profile.featureUnitId > 0 -> {
                    nativeSetFeatureUnitOverride(
                        profile.featureUnitId, profile.featureUnitChannel,
                        profile.featureUnitChannels,
                        profile.featureUnitMinDb, profile.featureUnitMaxDb, profile.featureUnitResDb
                    )
                }
            }
            // 【2026-09-10 multi-DAC quirk】Kotlin DacProfile = single source of truth.
            // Push per-device quirk overrides to native (non-default values only);
            // native keeps built-in table as fallback for unknown DACs.
            if (profile.urbPacketsOverride > 0 || profile.ctlMsgDelayUs > 0 ||
                profile.ifaceDelayUs > 0 || profile.forceIfaceReset || profile.forceUac1 ||
                profile.skipSetCurQuirk) {
                nativeSetQuirkOverride(
                    profile.urbPacketsOverride, profile.ctlMsgDelayUs, profile.ifaceDelayUs,
                    profile.forceIfaceReset, profile.forceUac1, profile.skipSetCurQuirk
                )
                DebugLog.add(TAG, "claim: quirk override vid=${device.vendorId.toString(16)} pid=${device.productId.toString(16)} urbPkts=${profile.urbPacketsOverride} ctlDelay=${profile.ctlMsgDelayUs} ifaceDelay=${profile.ifaceDelayUs} forceReset=${profile.forceIfaceReset} uac1=${profile.forceUac1} skipSetCur=${profile.skipSetCurQuirk}")
            }
        }
        if (!result) conn.close()
        return result
    }

    // V3.3.4: actual open-stream params for player-screen DAC info bar
    @JvmStatic @Volatile var activeSampleRate: Int = 0
    @JvmStatic @Volatile var activeChannels: Int = 0
    @JvmStatic @Volatile var activeBits: Int = 0

    // [V9.20 ⑤] Read-only snapshot of the currently running native stream config.
    // Used by MusicService keep-claim to decide whether the next track can reuse the
    // already-open USB stream (same format → skip reopen) instead of full stopMonitor+reclaim.
    fun getActiveStreamSampleRate(): Int = activeSampleRate
    fun getActiveStreamChannels(): Int = activeChannels
    fun getActiveStreamBits(): Int = activeBits
    /**
     * [V9.20 ⑤] True iff a USB stream is currently live AND its format matches the
     * requested (sr, ch, bits) — i.e. the caller may keep the existing stream open and
     * just repoint the decoder, avoiding the reopen latency on a same-format switch.
     */
    fun canReuseSession(sampleRate: Int, channels: Int, bits: Int): Boolean {
        if (!isStreaming()) return false
        return activeSampleRate == sampleRate && activeChannels == channels && activeBits == bits
    }

    /**
     * [fix19] startStreaming 带一次自动重试的包装。
     * 快速切歌时接口释放/隔离排干可能仍在进行（nativeOpen 1500ms 冷却期内），首次 start 会吃
     * LIBUSB_ERROR_BUSY → startStreaming FAIL → 当前首哑掉、十几秒后才恢复（实测 logcat）。
     * 300ms 后重试一次（冷却期内接口必释放），用户完全无感。
     */
    fun startStreaming(sampleRate: Int, channels: Int, bitsPerSample: Int): Boolean {
        var ok = startStreamingOnce(sampleRate, channels, bitsPerSample)
        if (!ok) {
            DebugLog.add(TAG, "start: first attempt FAIL → retry once after 300ms (cooldown race)")
            try { Thread.sleep(300) } catch (_: InterruptedException) { return false }
            ok = startStreamingOnce(sampleRate, channels, bitsPerSample)
            if (!ok) DebugLog.add(TAG, "start: retry also FAIL")
        }
        return ok
    }

    private fun startStreamingOnce(sampleRate: Int, channels: Int, bitsPerSample: Int): Boolean {
        if (useNeriEngine()) {
            if (!neriSession.isOpen) { DebugLog.add(TAG, "start(neri): session not open"); return false }
            val ctx = contextRef ?: run { DebugLog.add(TAG, "start(neri): contextRef=null"); return false }
            val prefs = UsbExclusivePrefs.read(ctx)
            // 入参 sampleRate 是「输入(解码源)采样率」，bitsPerSample 是期望输出位深；
            // 真正落到 DAC 的输出格式由偏好决定(固定/跟源)并由原生协商，与每轨输入解耦。
            // 旧实现把输入率当输出率去重协商，固定码率/比特完美模式下会把输出偷偷改成源率 → 破完美。
            val desiredOutRate = prefs.sampleRateMode.requestedSampleRateHz(sampleRate) ?: sampleRate
            // AUTO 位深：按源位深请求（对齐 NeriPlayer AUTO 语义）——24bit 源请求 24，
            // DAC 支持就 negotiated=24；DAC 真不支持时由 reopenNeriSession 的降级兜底
            // 按 DAC 实际能力重开（24→16 由 PcmPipeline 完成），绝不静默失败。
            // 曾经的错误实现：AUTO 取 neriSession.outputBits（当前会话协商值）——一旦某曲
            // 协商成 16bit，后续 24bit 曲全被锁死 16bit（实测：24bit FLAC 永远转 16bit）。
            val desiredOutBits = prefs.bitDepthMode.requestedBitDepth(bitsPerSample) ?: 0

            // 仅在「偏好驱动的输出格式」与已协商输出不一致时才需重协商。重协商要求会话处于 idle
            // (!running)，故仅在分支内同步停流再 reconfigure；固定/比特完美模式输出恒定 → 跨轨不重协商。
            // 关键修复：绝不能在这里「无条件 stop」——原生 nativeStop 是异步(requestDeviceStop + 中断
            // 事件线程)，而 preparePlayerPcm 对已 running 的会话会自行同步 stopStreamingInternal、
            // startPlayerPcm 对已 running/paused 也会优雅继续；外部无条件 stop() 会与 prepare 内部的
            // 同步排干竞争 → 切歌静默失败(首曲可播、切歌全挂，见 build_fix4 回归)。
            val outMismatch = neriSession.outputSampleRate != desiredOutRate ||
                    neriSession.outputChannels != channels ||
                    (desiredOutBits != 0 && neriSession.outputBits != desiredOutBits)
            if (outMismatch) {
                // 格式变化：直接做「干净重开」(等价于 NeriPlayer closePreviousAndOpenPlayerPcmLocked)。
                // 不再走 in-place reconfigureOutput——本机原生对占用/刚重开态的 handle 调 reconfigure
                // 会返回 reconfigure_requires_idle_handle，且重开后常落在 source=tone 态，使后续
                // preparePlayerPcm 失败 → 切歌静默(首曲可播、切不同格式全挂，见实测 logcat)。干净重开
                // 后走与首曲完全相同的 open→preparePlayerPcm→startPlayerPcm 流程，翻到 source=player_pcm。
                val cfgBits = if (desiredOutBits != 0) desiredOutBits else neriSession.outputBits
                DebugLog.add(TAG, "start(neri): fmt mismatch (out=${neriSession.outputSampleRate}/${neriSession.outputChannels}ch/${neriSession.outputBits}bit vs want ${desiredOutRate}/${channels}ch/${cfgBits}bit) → clean reopen")
                if (!reopenNeriSession(desiredOutRate, channels, cfgBits)) {
                    DebugLog.add(TAG, "start(neri): reopen FAIL")
                    return false
                }
            }
            // 声明输入 PCM 格式(解码源)：float 交织，与原生 PcmPipeline 一致。
            // 格式切换走干净重开后，首帧 prepare 偶发仍落在 source=tone 态而失败 → 自愈一次：
            // 再干净重开同一台 DAC 后重试 prepare+start（等价于 NeriPlayer 重试，避免切歌静默）。
            var prepared = neriSession.preparePlayerPcm(sampleRate, channels, android.media.AudioFormat.ENCODING_PCM_FLOAT)
            if (!prepared) {
                DebugLog.add(TAG, "start(neri): preparePlayerPcm FAIL → self-heal reopen")
                val cfgBits = if (desiredOutBits != 0) desiredOutBits else neriSession.outputBits
                if (reopenNeriSession(desiredOutRate, channels, cfgBits)) {
                    prepared = neriSession.preparePlayerPcm(sampleRate, channels, android.media.AudioFormat.ENCODING_PCM_FLOAT)
                }
            }
            if (!prepared) {
                DebugLog.add(TAG, "start(neri): preparePlayerPcm FAIL (after self-heal)")
                return false
            }
            // 比特完美跟随设置实时变化
            neriSession.setBitPerfect(prefs.bitPerfect)
            val result = neriSession.startPlayerPcm()
            neriStreaming = result
            if (result) {
                // activeSampleRate 跟踪「输入(源)率」，供 canReuseSession / rateChanged 判定；
                // activeBits 取原生真实协商位深(真相)，避免 DAC 降级(24→16)时信息栏虚报。
                activeSampleRate = sampleRate
                activeChannels = channels
                activeBits = neriSession.outputBits
                neriSession.setVolume(currentVolume)
            }
            DebugLog.add(TAG, "start(neri): in=$sampleRate/${channels}ch out→${desiredOutRate}/${activeBits}bit → $result")
            return result
        }
        if (!isNativeLoaded) { DebugLog.add(TAG, "start: native not loaded"); return false }
        if (!nativeIsClaimed()) { DebugLog.add(TAG, "start: not claimed"); return false }
        val result = nativeUsbStart(sampleRate, channels, bitsPerSample)
        // 【方向1 二期】native streaming_ 是权威状态, 不再维护 Kotlin 影子变量
        if (result) { activeSampleRate = sampleRate; activeChannels = channels; activeBits = bitsPerSample }
        // 【V3.2.7】重 claim 后 native 驱动 volume_ 重置为 1.0,每次开流重新应用当前音量
        if (result) nativeSetVolume(currentVolume)
        DebugLog.add(TAG, "start: sr=$sampleRate ch=$channels bits=$bitsPerSample → $result (vol=$currentVolume)")
        return result
    }

    /**
     * 忠实回退路径(NeriPlayer closePreviousAndOpenPlayerPcmLocked)：
     * 重协商失败或 DAC 要求重开时，停掉旧会话、关闭句柄、用偏好请求格式重新打开同一台 DAC。
     * 仅在 [startStreaming] 内部作为兜底调用，避免「重协商失败即静默无法播放」。
     */
    private fun reopenNeriSession(outRate: Int, channels: Int, outBits: Int): Boolean {
        val ctx = contextRef ?: run { DebugLog.add(TAG, "reopen(neri): contextRef=null"); return false }
        val usbMgr = try { ctx.getSystemService(Context.USB_SERVICE) as? UsbManager } catch (_: Throwable) { null }
            ?: run { DebugLog.add(TAG, "reopen(neri): usbManager=null"); return false }
        // 先停流再关句柄：nativeClose 内部 closeHandleInternal 会 cancel+drain 仍在飞的 USB 传输，
        // 排干后才真正 release_interface + libusb_close。若传输卡住走「隔离排干」分支，旧 devh 仍
        // 持有接口；此时立即重开会吃 LIBUSB_ERROR_BUSY → 切歌静默失败。下面用退避重试等其释放。
        try { neriSession.stop() } catch (_: Throwable) {}
        neriSession.close()
        val prefs = UsbExclusivePrefs.read(ctx)
        val reqRate = prefs.sampleRateMode.requestedSampleRateHz(outRate) ?: outRate
        val reqBits = prefs.bitDepthMode.requestedBitDepth(outBits) ?: outBits

        // 设备可达性探测：cachedUsbDevice 在切歌途中可能因系统广播而过时（openDevice 返回 null）。
        fun canReach(d: android.hardware.usb.UsbDevice): Boolean =
            usbMgr.hasPermission(d) && run { usbMgr.openDevice(d)?.let { it.close(); true } ?: false }
        var device = cachedUsbDevice ?: run { DebugLog.add(TAG, "reopen(neri): no cached device"); return false }
        if (!canReach(device)) {
            val refreshed = usbMgr.deviceList?.values?.firstOrNull { it.deviceName == device.deviceName }
            if (refreshed == null || !canReach(refreshed)) {
                DebugLog.add(TAG, "reopen(neri): device stale/unreachable name=${device.deviceName}")
                return false
            }
            device = refreshed
        }

        // 退避重试：仅当错误属于接口仍被旧会话占用(BUSY)/可达类时重试，等隔离排干完成(旧接口释放)
        // 后再 claim；其它错误直接放弃，避免无谓死等。上限 ~2s。
        // 格式类失败(invalid_output_format 等)：DAC 真不支持请求位深时，按实际能力降位深
        // 重试一次(24→16，PcmPipeline 完成转换)，绝不静默失败——对齐 NeriPlayer 降级语义。
        val maxAttempts = 12
        var formatDowngraded = false
        var effectiveReqBits = reqBits
        for (attempt in 0 until maxAttempts) {
            if (neriSession.open(usbMgr, device, reqRate, channels, effectiveReqBits)) {
                neriSession.setBitPerfect(prefs.bitPerfect)
                neriSession.configureBufferDuration(prefs.reservedBufferDurationMs())
                DebugLog.add(TAG, "reopen(neri): ok attempt=$attempt out ${neriSession.outputSampleRate}/${neriSession.outputChannels}ch/${neriSession.outputBits}bit")
                return true
            }
            val err = try { NeriUsbNativeBridge.lastOpenError() } catch (_: Throwable) { "" }
            val isFormatError = err.contains("invalid_output_format") || err.contains("no_matching_alt") ||
                    err.contains("unsupported") || err.contains("format")
            if (isFormatError && !formatDowngraded && effectiveReqBits > 16) {
                formatDowngraded = true
                effectiveReqBits = 16
                DebugLog.add(TAG, "reopen(neri): DAC rejects ${reqBits}bit ($err) → downgrade to 16bit retry")
                continue
            }
            val retryable = err.contains("BUSY") || err.contains("claim_interface_failed") ||
                    err.contains("wrap_sys_device_failed") || err.contains("NO_DEVICE")
            if (!retryable) {
                DebugLog.add(TAG, "reopen(neri): open FAIL non-retryable: $err")
                return false
            }
            DebugLog.add(TAG, "reopen(neri): open busy, retry $attempt ($err)")
            try { Thread.sleep(160L + attempt * 40L) } catch (_: InterruptedException) { return false }
        }
        DebugLog.add(TAG, "reopen(neri): exhausted $maxAttempts retries")
        return false
    }

    /**
     * 同步停流：原生 nativeStop 是异步(requestDeviceStop + 中断 USB 事件线程)，reconfigureOutput
     * 见到 running==true 会直接拒绝(reconfigure_requires_idle_handle)。这里 stop 后轮询 runtime report
     * 直到 idle(或超时)，保证重协商在干净态进行。超时不致命——preparePlayerPcm 内部还会再同步排干一次。
     * 仅用于输出格式需重协商的分支；同格式复用路径不走此函数，避免异步 stop 与 prepare 内部排干竞争。
     */
    private fun stopNeriSync(timeoutMs: Long = 400L): Boolean {
        if (!neriSession.isOpen) return false
        return try {
            neriSession.stop()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val r = try { neriSession.report() } catch (_: Throwable) { null }
                if (r == null || (!r.running && !r.paused)) break
                try { Thread.sleep(20) } catch (_: InterruptedException) { break }
            }
            true
        } catch (_: Throwable) { false }
    }

    fun pushPcm(data: FloatArray, frameCount: Int): Int {
        if (useNeriEngine()) {
            if (!neriSession.isOpen) return -1
            return neriSession.writeFloats(data, frameCount, currentVolume)
        }
        // 【V3.2.7】claim 后即可写入(预缓冲阶段 streaming 还是 false,之前直接 -1 导致预缓冲全被丢弃)
        if (!isNativeLoaded || !nativeIsClaimed()) return -1
        return nativePushPcm(data, frameCount)
    }

    /** 【V3.2.7】ring buffer 当前帧数(EOS 排空用) */
    fun getRingFill(): Int {
        if (useNeriEngine()) return neriSession.queuedFrames().toInt().coerceAtLeast(0)
        if (!isNativeLoaded) return 0
        return try { nativeGetRingFill() } catch (_: Throwable) { 0 }
    }

    fun stopAndRelease() {
        pendingClaim = null
        activeSampleRate = 0; activeBits = 0
        activeDescriptor = null
        if (useNeriEngine()) {
            // FLAC/ALAC 单例在 oboe_bridge 里，与引擎选择无关，必须先停解码再拆会话
            try { nativeFlacStop() } catch (_: Throwable) {}
            try { nativeAlacStop() } catch (_: Throwable) {}
            neriStreaming = false
            neriSession.stop()
            neriSession.close()
            DebugLog.add(TAG, "stopAndRelease(neri)")
            return
        }
        if (isNativeLoaded) {
            // [fix] use-after-free：FLAC 硬解线程 decodeLoop 可能仍持裸指针 drv 在 pushPcm，
            // 若直接 nativeRelease()（native 层 delete gUsbDriver）会导致 use-after-free 崩溃。
            // 必须先停 FLAC 解码线程，确保不再有线程触碰 driver 指针，再 release。
            try { nativeFlacStop() } catch (_: Throwable) {}
            nativeStop(); nativeRelease()
        }
        DebugLog.add(TAG, "stopAndRelease")
    }

    /** Pause DAC stream thread without releasing USB claim or ring buffer */
    fun pauseStream() {
        if (useNeriEngine()) {
            // neri：暂停 = 停止输出（原生 zero-fill 保时钟），会话与 claim 保留
            neriSession.pause()
            neriStreaming = false
            DebugLog.v(TAG, "pauseStream(neri) (session kept)")
            return
        }
        // 【方向1 二期】native streaming_ 权威; nativeStopThreadOnly 内部置 false
        if (isNativeLoaded) nativeStopThreadOnly()  // stop streamLoop, keep USB claim
        DebugLog.v(TAG, "pauseStream (threads stopped, USB claim kept)")
    }

    /** Reset ring buffer positions before resume streaming - avoids stale data corruption */
    fun resetRingBuffer() {
        if (useNeriEngine()) {
            // neri：startStreaming→preparePlayerPcm 会重建 pipeline 环形缓冲，无需额外清空
            DebugLog.v(TAG, "resetRingBuffer(neri): no-op (prepare rebuilds ring)")
            return
        }
        if (isNativeLoaded) nativeResetRingBuffer()
        DebugLog.v(TAG, "resetRingBuffer")
    }

    // 【V3.3.0】libFLAC 硬解封装:解码线程在 native,直入 ring buffer
    // 【V3.3.5】NDK fopen 不支持 UTF-8 路径（中文名→false），复制到 ASCII 临时路径绕过
    private var flacTempPath: String? = null
    private fun getFlacTempDir(): java.io.File {
        val ctx = contextRef ?: return java.io.File("/data/local/tmp")
        return java.io.File(ctx.cacheDir, "flac_temp").also { it.mkdirs() }
    }
    
    fun flacOpen(path: String): Boolean {
        if (!isNativeLoaded) { DebugLog.add(TAG, "flacOpen: native not loaded"); return false }
        return try {
            // 【V3.3.5】纯 ASCII 路径直接用；含非 ASCII 字符则复制到 cache/flac_temp/
            val openPath = if (path.all { it.code <= 0x7F }) {
                path
            } else {
                val tmp = java.io.File(getFlacTempDir(), "f_${path.hashCode().toUInt().toString(16)}.flac")
                if (!tmp.exists() || tmp.length() != java.io.File(path).length()) {
                    DebugLog.add(TAG, "flacOpen: copying UTF-8 file to ASCII temp \"${tmp.name}\"")
                    java.io.File(path).copyTo(tmp, overwrite = true)
                }
                flacTempPath = tmp.absolutePath
                tmp.absolutePath
            }
            val ok = nativeFlacOpen(openPath)
            DebugLog.add(TAG, "flacOpen(\"${path.takeLast(80)}\") = $ok")
            ok
        } catch (e: Throwable) {
            DebugLog.add(TAG, "flacOpen EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
    /** [sampleRate, channels, bits, durationMs] */
    fun flacInfo(): IntArray {
        if (!isNativeLoaded) return intArrayOf(0,0,0,0)
        return try {
            val info = nativeFlacInfo()
            DebugLog.add(TAG, "flacInfo: ${info.contentToString()}")
            info
        } catch (e: Throwable) {
            DebugLog.add(TAG, "flacInfo EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            intArrayOf(0,0,0,0)
        }
    }
    fun flacStart(): Boolean =
        isNativeLoaded && try { nativeFlacStart() } catch (_: Throwable) { false }
    fun flacPause(paused: Boolean) { if (isNativeLoaded) try { nativeFlacPause(paused) } catch (_: Throwable) {} }
    fun flacSeek(ms: Long) { if (isNativeLoaded) try { nativeFlacSeek(ms) } catch (_: Throwable) {} }
    fun flacStop() {
        if (isNativeLoaded) try { nativeFlacStop() } catch (_: Throwable) {}
        // 【V3.3.5】清理 ASCII 临时文件
        flacTempPath?.let { try { java.io.File(it).delete() } catch (_: Throwable) {} }; flacTempPath = null
    }
    fun flacIsEos(): Boolean = isNativeLoaded && try { nativeFlacIsEos() } catch (_: Throwable) { true }
    fun flacPositionMs(): Long = if (isNativeLoaded) try { nativeFlacPositionMs() } catch (_: Throwable) { 0L } else 0L
    /** Gapless 切曲:优先调 native flacGaplessSeek(支持跨文件);fallback 同文件内用 native flacSeek
     *  原理:同 FLAC 相邻轨共用一个 decoder,decoder 已在 running 状态,直接 seekSamples 即可 */
    fun flacGaplessSeek(path: String, targetSample: Long): Boolean {
        if (!isNativeLoaded) return false
        // 换算 sample → ms(近似,decoder 会自行 seek 精确位置)
        val info = flacInfo()
        val sr = info[0]
        if (sr <= 0) { try { nativeFlacGaplessSeek(path, targetSample) } catch (_: Throwable) { /* ignore */ }; return false }
        val targetMs = (targetSample * 1000L) / sr
        return try {
            // 同文件直接 seek(decoder 已 open 不关闭,seekSamples 会被 decode 线程消费)
            nativeFlacGaplessSeek(path, targetSample); true
        } catch (_: Throwable) {
            // fallback:强制 seek ms(decoder 已在 running,pendingSeekMs 会被处理)
            try { nativeFlacSeek(targetMs); true } catch (_: Throwable) { false }
        }
    }
    /** FLAC 总采样时长(ms) */
    fun flacTotalSamples(): Long {
        val info = flacInfo(); return if (info[3] > 0) info[3].toLong() else 0L
    }

    /**
     * 读 FLAC 内嵌歌词（Vorbis comment LYRICS / UNSYNCEDLYRICS 字段）。
     * 返回 UTF-8 歌词文本；无内嵌歌词或非 FLAC 返回 null。
     * 独立 open/close，不触碰解码状态，线程安全。
     * 懒加载：非 DAC 模式下 oboe_bridge 可能未加载（init 只在 USB 独占时调用），
     * 这里通过 init(context) 确保 native 库加载 + contextRef 设置，但不启动 USB 驱动。
     */
    fun flacReadLyrics(context: Context, path: String): String? {
        if (!isNativeLoaded) {
            init(context)
            if (!isNativeLoaded) return null
        }
        return try {
            // 【V3.3.5】纯 ASCII 路径直接用；含非 ASCII 字符则复制到 cache/flac_temp/（NDK fopen 不支持 UTF-8）
            val openPath = if (path.all { it.code <= 0x7F }) {
                path
            } else {
                val tmp = java.io.File(getFlacTempDir(), "lyrics_${path.hashCode().toUInt().toString(16)}.flac")
                try {
                    java.io.File(path).copyTo(tmp, overwrite = true)
                    tmp.absolutePath
                } catch (_: Throwable) {
                    android.util.Log.w("FlacLyrics", "copyTo tmp failed")
                    null
                }
            }
            if (openPath == null) return null
            android.util.Log.d("FlacLyrics", "flacReadLyrics: openPath=$openPath")
            val result = try { nativeFlacReadLyrics(openPath) } catch (e: Throwable) {
                android.util.Log.w("FlacLyrics", "nativeFlacReadLyrics throw: ${e.message}")
                null
            }
            android.util.Log.d("FlacLyrics", "flacReadLyrics: result=${result?.length ?: -1} chars")
            // 清理临时文件
            if (openPath != path) { try { java.io.File(openPath).delete() } catch (_: Throwable) {} }
            result
        } catch (_: Throwable) {
            null
        }
    }

    // ── ALAC 直解封装（镜像 flac 那套，非 ASCII 路径复制到 cache temp）──
    private fun getAlacTempDir(): java.io.File {
        val ctx = contextRef ?: return java.io.File("/data/local/tmp")
        return java.io.File(ctx.cacheDir, "alac_temp").also { it.mkdirs() }
    }
    private var alacTempPath: String? = null

    fun alacOpen(path: String): Boolean {
        if (!isNativeLoaded) { DebugLog.add(TAG, "alacOpen: native not loaded"); return false }
        return try {
            val openPath = if (path.all { it.code <= 0x7F }) {
                path
            } else {
                val tmp = java.io.File(getAlacTempDir(), "a_${path.hashCode().toUInt().toString(16)}.m4a")
                if (!tmp.exists() || tmp.length() != java.io.File(path).length()) {
                    DebugLog.add(TAG, "alacOpen: copying UTF-8 file to ASCII temp \"${tmp.name}\"")
                    java.io.File(path).copyTo(tmp, overwrite = true)
                }
                alacTempPath = tmp.absolutePath
                tmp.absolutePath
            }
            val ok = nativeAlacOpen(openPath)
            DebugLog.add(TAG, "alacOpen(\"${path.takeLast(80)}\") = $ok")
            ok
        } catch (e: Throwable) {
            DebugLog.add(TAG, "alacOpen EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
    /** [sampleRate, channels, bits, durationMs] */
    fun alacInfo(): IntArray {
        if (!isNativeLoaded) return intArrayOf(0,0,0,0)
        return try {
            val info = nativeAlacInfo()
            DebugLog.add(TAG, "alacInfo: ${info.contentToString()}")
            info
        } catch (e: Throwable) {
            DebugLog.add(TAG, "alacInfo EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            intArrayOf(0,0,0,0)
        }
    }
    fun alacStart(): Boolean = isNativeLoaded && try { nativeAlacStart() } catch (_: Throwable) { false }
    fun alacPause(paused: Boolean) { if (isNativeLoaded) try { nativeAlacPause(paused) } catch (_: Throwable) {} }
    fun alacSeek(ms: Long) { if (isNativeLoaded) try { nativeAlacSeek(ms) } catch (_: Throwable) {} }
    fun alacStop() {
        if (isNativeLoaded) try { nativeAlacStop() } catch (_: Throwable) {}
        alacTempPath?.let { try { java.io.File(it).delete() } catch (_: Throwable) {} }; alacTempPath = null
    }
    fun alacIsEos(): Boolean = isNativeLoaded && try { nativeAlacIsEos() } catch (_: Throwable) { true }
    fun alacPositionMs(): Long = if (isNativeLoaded) try { nativeAlacPositionMs() } catch (_: Throwable) { 0L } else 0L
    fun alacTotalSamples(): Long {
        val info = alacInfo(); return if (info[3] > 0) info[3].toLong() else 0L
    }

    fun isStreaming(): Boolean {
        if (useNeriEngine()) {
            if (!neriSession.isOpen) return false
            // 每 ~1s 与原生核对一次（runtimeReport），其余时间用 Kotlin 影子标志
            val now = System.currentTimeMillis()
            if (now - lastNeriStreamCheckMs > 1000) {
                lastNeriStreamCheckMs = now
                neriStreaming = neriSession.report().running
            }
            return neriStreaming
        }
        return isNativeLoaded && nativeIsClaimed() && nativeIsStreaming()
    }
    // V3.3.3: claim held (stream may be paused after EOS keep-claim)
    fun isClaimed(): Boolean {
        if (useNeriEngine()) return neriSession.isOpen
        return isNativeLoaded && nativeIsClaimed()
    }
    fun getUnderrunCount(): Int {
        if (useNeriEngine()) return if (neriSession.report().underrunBytes > 0) 1 else 0
        return if (isNativeLoaded) nativeGetUnderrunCount() else 0
    }

    // 【V9.20x】运行时诊断指标（借鉴 NeriPlayer RuntimeMetrics）。这是后续所有自愈 / 降功耗
    // 决策的地基：背压、零填充、信号 / 静音帧、逐声道峰值、丢弃字节、消失事件等。
    // 峰值按 1e5 缩放保存，读取时除以 1e5。
    data class DacRuntimeMetrics(
        val urbSubmitted: Long,
        val urbCompleted: Long,
        val urbErrors: Long,
        val totalSamplesOut: Long,
        val underrunCount: Int,
        val bufferWatermarkMs: Int,
        val backpressureEvents: Long,
        val backpressureTotalMs: Long,
        val backpressureMaxMs: Long,
        val backpressureCurrentMs: Long,
        val zeroFillBytes: Long,
        val silentFrames: Long,
        val signalFrames: Long,
        val channel0Peak: Float,
        val channel1Peak: Float,
        val droppedBytes: Long,
        val stallBreaks: Long,
        val vanishEvents: Long
    ) {
        companion object {
            // 必须与 native UsbAudioDriver::fillMetrics 的写入顺序严格一致
            const val M_URB_SUBMITTED = 0
            const val M_URB_COMPLETED = 1
            const val M_URB_ERRORS = 2
            const val M_TOTAL_SAMPLES_OUT = 3
            const val M_UNDERRUN_COUNT = 4
            const val M_BUFFER_WATERMARK_MS = 5
            const val M_BACKPRESSURE_EVENTS = 6
            const val M_BACKPRESSURE_TOTAL_MS = 7
            const val M_BACKPRESSURE_MAX_MS = 8
            const val M_BACKPRESSURE_CURRENT_MS = 9
            const val M_ZERO_FILL_BYTES = 10
            const val M_SILENT_FRAMES = 11
            const val M_SIGNAL_FRAMES = 12
            const val M_CHANNEL0_PEAK = 13
            const val M_CHANNEL1_PEAK = 14
            const val M_DROPPED_BYTES = 15
            const val M_STALL_BREAKS = 16
            const val M_VANISH_EVENTS = 17
            const val COUNT = 18
            const val PEAK_SCALE = 100000.0f
        }
    }

    fun getRuntimeMetrics(): DacRuntimeMetrics {
        if (useNeriEngine()) {
            if (!neriSession.isOpen) return DacRuntimeMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0)
            val r = neriSession.report()
            val peak0 = r.fields["channel0OutputPeak"]?.toFloatOrNull() ?: r.outputPeak
            val peak1 = r.fields["channel1OutputPeak"]?.toFloatOrNull() ?: r.outputPeak
            return DacRuntimeMetrics(
                urbSubmitted = r.completedAudioFrames,
                urbCompleted = r.completedAudioFrames,
                urbErrors = 0,
                totalSamplesOut = r.completedAudioFrames,
                underrunCount = if (r.underrunBytes > 0) 1 else 0,
                bufferWatermarkMs = r.bufferMs,
                backpressureEvents = r.backpressureEvents,
                backpressureTotalMs = 0,
                backpressureMaxMs = 0,
                backpressureCurrentMs = 0,
                zeroFillBytes = r.zeroFillBytes,
                silentFrames = r.fields["playerSilentFrames"]?.toLongOrNull() ?: 0L,
                signalFrames = r.fields["playerSignalFrames"]?.toLongOrNull() ?: 0L,
                channel0Peak = peak0,
                channel1Peak = peak1,
                droppedBytes = r.droppedBytes,
                stallBreaks = 0,
                vanishEvents = if (!r.deviceOnline) 1 else 0
            )
        }
        if (!isNativeLoaded) return DacRuntimeMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0)
        val a = nativeGetRuntimeMetrics()
        val safe = if (a.size >= DacRuntimeMetrics.COUNT) a else LongArray(DacRuntimeMetrics.COUNT)
        with(DacRuntimeMetrics.Companion) {
            return DacRuntimeMetrics(
                urbSubmitted = safe[M_URB_SUBMITTED],
                urbCompleted = safe[M_URB_COMPLETED],
                urbErrors = safe[M_URB_ERRORS],
                totalSamplesOut = safe[M_TOTAL_SAMPLES_OUT],
                underrunCount = safe[M_UNDERRUN_COUNT].toInt(),
                bufferWatermarkMs = safe[M_BUFFER_WATERMARK_MS].toInt(),
                backpressureEvents = safe[M_BACKPRESSURE_EVENTS],
                backpressureTotalMs = safe[M_BACKPRESSURE_TOTAL_MS],
                backpressureMaxMs = safe[M_BACKPRESSURE_MAX_MS],
                backpressureCurrentMs = safe[M_BACKPRESSURE_CURRENT_MS],
                zeroFillBytes = safe[M_ZERO_FILL_BYTES],
                silentFrames = safe[M_SILENT_FRAMES],
                signalFrames = safe[M_SIGNAL_FRAMES],
                channel0Peak = safe[M_CHANNEL0_PEAK] / DacRuntimeMetrics.Companion.PEAK_SCALE,
                channel1Peak = safe[M_CHANNEL1_PEAK] / DacRuntimeMetrics.Companion.PEAK_SCALE,
                droppedBytes = safe[M_DROPPED_BYTES],
                stallBreaks = safe[M_STALL_BREAKS],
                vanishEvents = safe[M_VANISH_EVENTS]
            )
        }
    }
    // ── [V9.20 ⑥] Loud-playback protection (pure-Kotlin, no native change) ──
    // Mirrors NeriPlayer's gain/peak ceiling logic: derive the effective output peak
    // (measured native peak × applied gain) and warn when it approaches clipping.
    // The warning is delivered via [loudWarningListener] (registered by MusicService,
    // which shows a foreground toast). Native driver is never touched here.
    object LoudPlaybackPolicy {
        const val NONE = 0
        const val HIGH = 1
        const val ELEVATED = 2
        const val CRITICAL = 3
        private const val CRITICAL_DBFS = -1.5f   // ≈ clipping
        private const val ELEVATED_DBFS = -10f    // UAC2 ≥96k / 24bit → more headroom needed
        private const val HIGH_DBFS = -6f

        /**
         * @param gain            applied stream gain (UsbDacManager.currentVolume, 1.0 = bit-perfect)
         * @param measuredPeakLin measured native peak (linear 0..~1, from RuntimeMetrics)
         * @param bitPerfect      true when gain≈1.0 (no software attenuation available)
         * @param rate            active DAC sample rate
         * @param bits            active DAC bits
         * @return one of NONE/HIGH/ELEVATED/CRITICAL
         */
        /** [Stage0 2026-10-23] Bit-perfect used to opt out of the loudness warning entirely.
         *  That silently disabled the ONLY protection for users running at gain 1.0 with a
         *  near-clipping peak (measured 0.99 ≈ -0.09 dBFS on the user's Realtek) — precisely
         *  the case that needs it. Make it a user choice instead of a hardcoded opt-out,
         *  defaulting to warn. NeriPlayer's volume-risk threshold likewise applies
         *  regardless of its bit-perfect toggle. */
        @Volatile
        var warnInBitPerfect: Boolean = true

        fun evaluate(gain: Float, measuredPeakLin: Float, bitPerfect: Boolean, rate: Int, bits: Int): Int {
            val eff = (measuredPeakLin * gain).coerceAtLeast(0f)
            val effDb = if (eff <= 1e-5f) -120f else (20f * kotlin.math.log10(eff.toDouble())).toFloat()
            // Bit-perfect intentionally preserves full dynamics, so we used to never nag.
            // It is now a user preference — see warnInBitPerfect.
            if (bitPerfect && !warnInBitPerfect) return NONE
            val elevated = rate >= 96000 || bits >= 24
            return when {
                effDb >= CRITICAL_DBFS -> CRITICAL
                elevated && effDb >= ELEVATED_DBFS -> ELEVATED
                effDb >= HIGH_DBFS -> HIGH
                else -> NONE
            }
        }

        fun describe(level: Int, effDb: Float): String = when (level) {
            CRITICAL -> "高音量风险：当前曲目峰值约 ${"%.1f".format(effDb)} dBFS，接近削波，建议降低音量"
            ELEVATED -> "音量提示：DAC 在高规格模式(≥96k/24bit)，峰值约 ${"%.1f".format(effDb)} dBFS，可适当降低音量"
            HIGH -> "音量提示：当前曲目峰值约 ${"%.1f".format(effDb)} dBFS，较大"
            else -> ""
        }
    }

    /** [V9.20 ⑥] Called by the playback monitor loops every metrics tick. Evaluates the
     *  loud-playback policy against the live stream and notifies [loudWarningListener]. */
    private var lastLoudWarnMs = 0L
    var loudWarningListener: ((level: Int, msg: String) -> Unit)? = null
    /** [fix10] 高音量提示总开关：默认开(保留安全提醒)，设置里可关掉彻底不弹。 */
    @Volatile var loudWarnEnabled: Boolean = true
    /** [fix10] 每首歌最多提示一次：一首歌只要提示过就不再重复弹(即使仍偏大)。 */
    private var warnedThisTrack = false
    fun resetLoudWarn() { warnedThisTrack = false; lastLoudWarnMs = 0L }
    fun checkLoudPlayback(measuredPeakLin: Float) {
        if (!isNativeLoaded) return
        val gain = currentVolume
        val bitPerfect = gain >= 0.999f
        val level = LoudPlaybackPolicy.evaluate(gain, measuredPeakLin, bitPerfect, activeSampleRate, activeBits)
        if (level == LoudPlaybackPolicy.NONE) return
        if (!loudWarnEnabled) return
        if (warnedThisTrack) return
        val eff = (measuredPeakLin * gain).coerceAtLeast(0f)
        val effDb = if (eff <= 1e-5f) -120f else (20f * kotlin.math.log10(eff.toDouble())).toFloat()
        DebugLog.add(TAG, "loud: level=$level effDb=${"%.2f".format(effDb)} gain=$gain peak=${"%.4f".format(measuredPeakLin)} rate=$activeSampleRate bits=$activeBits")
        val msg = LoudPlaybackPolicy.describe(level, effDb)
        if (msg.isNotEmpty()) {
            warnedThisTrack = true
            loudWarningListener?.invoke(level, msg)
        }
    }

    fun getDacName(): String? {
        if (useNeriEngine()) return neriDeviceName.ifEmpty { null }
        return if (isNativeLoaded) nativeGetDacName() else null
    }

    // [V3.3.4] robust display name: native dacName_ is often empty -> prefer UsbDevice.productName
    fun getDacDisplayName(): String {
        val prod = try { cachedUsbDevice?.productName } catch (_: Throwable) { null }
        if (!prod.isNullOrBlank()) return prod.trim()
        val n = try { getDacName() } catch (_: Throwable) { null }
        return if (n.isNullOrBlank() || n == "No DAC") "USB DAC" else n
    }

    // [V3.3.6] 直接从 native 读取,避免 activeSampleRate 缓存问题
    fun queryActiveSampleRate(): Int {
        if (useNeriEngine()) return neriSession.outputSampleRate
        return if (isNativeLoaded) try { nativeGetCurrentSampleRate() } catch (_: Throwable) { 0 } else 0
    }
    fun queryActiveBits(): Int {
        if (useNeriEngine()) return neriSession.outputBits
        return if (isNativeLoaded) try { nativeGetCurrentBits() } catch (_: Throwable) { 0 } else 0
    }

    /** 比特完美是否开启（设置项；UI 状态展示用）。 */
    fun isBitPerfectEnabled(): Boolean {
        val ctx = contextRef ?: return false
        return try { UsbExclusivePrefs.read(ctx).bitPerfect } catch (_: Throwable) { false }
    }

    // [V9.20p] 源位深/源率：UI 上的音质标识必须按"这首歌的源"算，不能按 DAC 的线位深。
    // 之前 DAC 信息条读 queryActiveBits()——MP3(16bit 源) 走 32bit alt 打包时显示
    // "44.1kHz / 32bit" 并亮 Hi-Res 徽标，有损标 Hi-Res 属实荒谬。controller play() 时上报。
    @JvmStatic @Volatile var activeSourceBits: Int = 0
    @JvmStatic @Volatile var activeSourceRate: Int = 0
    fun queryActiveSourceBits(): Int = if (activeSourceBits > 0) activeSourceBits else queryActiveBits()
    fun queryActiveSourceRate(): Int = if (activeSourceRate > 0) activeSourceRate else queryActiveSampleRate()
    fun getDetailedDacInfo(): String? {
        if (useNeriEngine()) {
            val r = neriSession.report()
            return "neri: uac=${r.uacVersion} out=${r.sampleRate}Hz/${r.channels}ch/${r.bits}bit " +
                "fb=${r.feedbackMode}/${r.feedbackState} ppm=${r.feedbackRatePpm} " +
                "fifo=${r.fifoMs}ms err=${r.errorCode}"
        }
        return if (isNativeLoaded) nativeGetDetailedInfo() else null
    }
    fun getSupportedRates(): String {
        if (useNeriEngine()) {
            val r = neriSession.report()
            val rates = r.fields["descriptorRates"] ?: ""
            return rates
        }
        return if (isNativeLoaded) (nativeGetSupportedRates() ?: "") else ""
    }
    /** [P3] Rate set the DAC actually accepted via SET_CUR+GET_CUR probe (empty = none). */
    fun getVerifiedRates(): List<Int> = activeDescriptor?.verifiedSampleRates ?: emptyList()
    fun getNativeDebugLog(): String? = if (isNativeLoaded) nativeGetDebugLog() else null
    fun clearNativeDebugLog() { if (isNativeLoaded) try { nativeClearDebugLog() } catch (_: Throwable) {} }
    fun getStats(): String? = if (isNativeLoaded) nativeGetStats() else null

    /**
     * [P2] Structured liveness snapshot parsed from [getStats] — used by the
     * silence watchdog to decide whether the Host Exclusive path is actually
     * draining data to the DAC.
     *  - [health]        native healthState ("stable" | "wedge" | "idle" | ...)
     *  - [urbCompleted]  monotonic count of completed isochronous URBs
     *  - [ringAvailFrames] current ring-buffer fill (frames)
     */
    data class DacLiveness(val health: String, val urbCompleted: Long, val ringAvailFrames: Int)
    fun getStreamLiveness(): DacLiveness? {
        if (useNeriEngine()) {
            if (!neriSession.isOpen) return null
            val r = neriSession.report()
            val health = when {
                r.terminalFailure || r.transportFailed -> "failed"
                !r.deviceOnline -> "detached"
                !r.running -> "idle"
                r.playbackReady -> "stable"
                else -> "waiting"
            }
            return DacLiveness(health, r.completedAudioFrames, r.queuedFrames.toInt())
        }
        if (!isNativeLoaded) return null
        val s = nativeGetStats() ?: return null
        val completed = runCatching {
            val i = s.indexOf("completed=")
            if (i < 0) 0L else s.substring(i + 10).takeWhile { it.isDigit() }.toLongOrNull() ?: 0L
        }.getOrDefault(0L)
        val health = runCatching {
            val i = s.indexOf("Health=")
            if (i < 0) "" else s.substring(i + 7).substringBefore(' ').substringBefore('|').trim()
        }.getOrDefault("")
        val ring = runCatching {
            val i = s.indexOf("Ring avail=")
            if (i < 0) -1 else s.substring(i + 11).takeWhile { it.isDigit() }.toIntOrNull() ?: -1
        }.getOrDefault(-1)
        return DacLiveness(health, completed, ring)
    }

    fun setVolume(v: Float) {
        // 【V3.2.8】立方 audio taper：线性 1/15 档 = -23dB 对灵敏耳机仍很响；
        // v3 后最小档 ≈ -70dB，音量曲线接近人耳感知
        val pct = v.coerceIn(0f, 1f)
        currentVolume = pct * pct * pct
        DebugLog.add(TAG, "setVolume: input=$v cubic=$currentVolume claimed=${isClaimed()}")
        if (useNeriEngine()) {
            neriSession.setVolume(currentVolume)
            return
        }
        if (isNativeLoaded) nativeSetVolume(currentVolume)
    }
    fun getVolume(): Float = currentVolume
    fun setDitherEnabled(enabled: Boolean) { if (useNeriEngine()) return; if (isNativeLoaded) nativeSetDitherEnabled(enabled) }
    // [V8.119] true=宽裕余量（knee 0.92），false=传统（knee 2/3）
    fun setWideHeadroom(enabled: Boolean) { if (useNeriEngine()) return; if (isNativeLoaded) try { nativeSetWideHeadroom(enabled) } catch (_: Throwable) {} }
    // [V8.119] true=原声直通（DAC 链整条 DSP 旁路）
    fun setDirectPassthrough(enabled: Boolean) { if (useNeriEngine()) return; if (isNativeLoaded) try { nativeSetDirectPassthrough(enabled) } catch (_: Throwable) {} }
    // 【V9.01】DAC 模块子开关 / 处理链状态缓存（供音频诊断 SignalPathCard 显示）。
    // 缓存写入点即唯一下发路径（setXxxEnabled），与 native 实际生效状态一致。
    private var usbEngineDspEnabled = true
    private var usbMsebEnabled = true
    private var usbAutoEqEnabled = true
    private var usbDtsEnabled = true
    private var usbEq5Enabled = false
    private var usbLimiterEnabled = false   // USB 引擎无独立限幅级，不计入链显示
    private var usbLoudnessEnabled = false
    private var usbTransientEnabled = false
    private var usbCompressorEnabled = false

    // 【V9.01】引擎 DSP 主开关（DAC 模块"启用引擎 DSP"）：false = 创意 DSP 整链旁路
    fun setEngineDspEnabled(enabled: Boolean) { if (useNeriEngine()) return; usbEngineDspEnabled = enabled; if (isNativeLoaded) try { nativeSetEngineDspEnabled(enabled) } catch (_: Throwable) {} }
    fun isEngineDspEnabled(): Boolean { if (useNeriEngine()) return false; return if (isNativeLoaded) try { nativeIsEngineDspEnabled() } catch (_: Throwable) { true } else true }
    // 【V9.01】DAC 模块子开关：MSEB 整链使能
    fun setMsebEnabled(enabled: Boolean) { if (useNeriEngine()) return; usbMsebEnabled = enabled; if (isNativeLoaded) try { nativeSetMsebEnabled(enabled) } catch (_: Throwable) {} }
    // 【V9.01】DAC 模块子开关：AutoEQ 耳机修正使能
    fun setAutoEqEnabled(enabled: Boolean) { if (useNeriEngine()) return; usbAutoEqEnabled = enabled; if (isNativeLoaded) try { nativeSetAutoEqEnabled(enabled) } catch (_: Throwable) {} }
    // 【V9.01】DAC 模块子开关：DTS 环绕（M/S 处理块）使能
    fun setDtsEnabled(enabled: Boolean) { if (useNeriEngine()) return; usbDtsEnabled = enabled; if (isNativeLoaded) try { nativeSetDtsEnabled(enabled) } catch (_: Throwable) {} }

    /**
     * 【V9.01】音频诊断：返回 USB DAC 引擎 DSP 链 16 级状态（0=旁路, 1=生效），
     * 与 OboeDirectPlayer.getDspChainState() 同布局（2=AutoEQ 3=MSEB 4=EQ5 6=AGC
     * 7=Loud 8=M/S 9=Xfeed 10=Trans 11=Comp 14=Dither），供 SignalPathCard 在 DAC 模式显示。
     */
    fun getDspChainState(): IntArray {
        val arr = IntArray(16)
        if (useNeriEngine()) return arr   // neri 引擎内无 MSEB/EQ5/DTS 链
        if (!usbEngineDspEnabled) return arr   // 主开关关 = 整链 bypass，对应「原声直通」
        if (usbAutoEqEnabled)    arr[2] = 1
        if (usbMsebEnabled)      arr[3] = 1
        if (usbEq5Enabled)       arr[4] = 1
        if (usbLoudnessEnabled)  arr[7] = 1
        if (usbDtsEnabled)       arr[8] = 1
        if (usbTransientEnabled) arr[10] = 1
        if (usbCompressorEnabled)arr[11] = 1
        if (usbLimiterEnabled)   arr[14] = 1   // 多段限幅作为收尾保护常驻
        return arr
    }

    /**
     * [2026-09-10 soft-start] Mask DAC PLL re-lock pops on stream start with a 150ms fade-in.
     * Default OFF - the gain ramp modifies samples, so bit-perfect output is only traded
     * away when the user explicitly turns this on in Settings.
     */
    fun setSoftStartEnabled(enabled: Boolean) { if (useNeriEngine()) return; if (isNativeLoaded) nativeSetSoftStartEnabled(enabled) }

    // ── [P6] Async feedback loop (closed-loop clock recovery) ──
    fun setFeedbackEnabled(enabled: Boolean) { if (useNeriEngine()) return; if (isNativeLoaded) nativeSetFeedbackEnabled(enabled) }
    fun isFeedbackActive(): Boolean {
        if (useNeriEngine()) { val m = neriSession.report().feedbackMode; return m == "explicit" || m == "implicit" }
        return isNativeLoaded && nativeIsFeedbackActive()
    }
    fun getFeedbackMeasuredRate(): Double {
        if (useNeriEngine()) return neriSession.report().feedbackRateHz
        return if (isNativeLoaded) nativeGetFeedbackMeasuredRate() else 0.0
    }

    // ── [2026-10-22] 强制软件音量（设置页按设备开关调用，立即生效）──
    // 白名单策略下默认已是软件增益；此入口用于白名单设备异常时的即时回退
    // （关闭开关则记录 pref，下次 claim 时按白名单结果重新决定）。
    fun forceSoftwareVolume() { if (useNeriEngine()) return; if (isNativeLoaded) nativeForceSoftwareVolume() }
    fun getFeedbackCorrectionPpm(): Double {
        if (useNeriEngine()) return neriSession.report().feedbackRatePpm.toDouble()
        return if (isNativeLoaded) nativeGetFeedbackCorrectionPpm() else 0.0
    }

    // ── DAC 链路 MSEB / 图形 EQ（与 Oboe 共用同一套 Biquad）──
    fun setDspEnabled(enabled: Boolean) { if (useNeriEngine()) return; if (isNativeLoaded) nativeSetDspEnabled(enabled) }
    fun setDspEq5Band(gainsDb: FloatArray, freqsHz: FloatArray?) {
        if (useNeriEngine()) return
        usbEq5Enabled = true
        if (isNativeLoaded) try { nativeSetDspEq5Band(gainsDb, freqsHz) } catch (_: Throwable) {}
    }
    fun resetDspEq5Band() { if (useNeriEngine()) return; usbEq5Enabled = false; if (isNativeLoaded) try { nativeResetDspEq5Band() } catch (_: Throwable) {} }

    // 【V8.2】MSEB 10-band subjective EQ
    fun setMseb10Band(gainsDb: FloatArray, freqsHz: FloatArray, qValues: FloatArray) {
        if (useNeriEngine()) return
        if (isNativeLoaded) try { nativeSetMseb10Band(gainsDb, freqsHz, qValues) } catch (_: Throwable) {}
    }
    fun resetMseb10Band() { if (useNeriEngine()) return; if (isNativeLoaded) try { nativeResetMseb10Band() } catch (_: Throwable) {} }

    // 【V8.3】AutoEQ 10-band 耳机修正（任意频点 + PK/HS/LS），与 MSEB 并存叠加。
    fun setAutoEq10Band(gainsDb: FloatArray, freqsHz: FloatArray, qValues: FloatArray, filterTypes: IntArray, preampDb: Float) {
        if (useNeriEngine()) return
        if (isNativeLoaded) try { nativeSetAutoEq10Band(gainsDb, freqsHz, qValues, filterTypes, preampDb) } catch (_: Throwable) {}
    }
    fun resetAutoEq() { if (useNeriEngine()) return; if (isNativeLoaded) try { nativeResetAutoEq() } catch (_: Throwable) {} }

    // 【V8.3】M/S 声场（跨声道矩阵）
    fun setMsStage(soundstage: Float, imaging: Float) {
        if (useNeriEngine()) return
        if (isNativeLoaded) try { nativeSetMsStage(soundstage, imaging) } catch (_: Throwable) {}
    }
    fun resetMsStage() { if (useNeriEngine()) return; if (isNativeLoaded) try { nativeResetMsStage() } catch (_: Throwable) {} }

    fun setTransient(amount: Float) { if (useNeriEngine()) return; usbTransientEnabled = amount > 0.0001f; if (isNativeLoaded) try { nativeSetTransient(amount) } catch (_: Throwable) {} }
    fun resetTransient() { if (useNeriEngine()) return; if (isNativeLoaded) try { nativeResetTransient() } catch (_: Throwable) {} }

    // 【V8.3】动态压缩（Master Bus Compressor，独立全局模块）
    fun setCompressorEnabled(enabled: Boolean) { if (useNeriEngine()) return; usbCompressorEnabled = enabled; if (isNativeLoaded) try { nativeSetCompressorEnabled(enabled) } catch (_: Throwable) {} }
    fun setCompressorParams(thresholdDb: Float, ratio: Float, attackMs: Float, releaseMs: Float, makeupDb: Float) {
        if (useNeriEngine()) return
        if (isNativeLoaded) try { nativeSetCompressorParams(thresholdDb, ratio, attackMs, releaseMs, makeupDb) } catch (_: Throwable) {}
    }

    // 【V8.3】等响补偿（ISO 226）—— DAC 独占路径同样生效
    fun setLoudnessEnabled(enabled: Boolean) { if (useNeriEngine()) return; usbLoudnessEnabled = enabled; if (isNativeLoaded) try { nativeSetLoudnessEnabled(enabled) } catch (_: Throwable) {} }
    fun setLoudnessIntensity(intensity: Float) { if (useNeriEngine()) return; if (isNativeLoaded) try { nativeSetLoudnessIntensity(intensity) } catch (_: Throwable) {} }
    fun setLoudnessOutGain(gain: Float) { if (useNeriEngine()) return; if (isNativeLoaded) try { nativeSetLoudnessOutGain(gain) } catch (_: Throwable) {} }

    /** 【V8.170】响度统一管理（DAC 独占路径）。 */
    fun setNormalizationGain(db: Float) { if (useNeriEngine()) return; if (isNativeLoaded) try { nativeSetNormalizationGain(db) } catch (_: Throwable) {} }

    @Volatile private var currentVolume = 0.7f
    fun getSafeDacInfo(): String {
        if (!isNativeLoaded) return "Native driver not loaded"
        return nativeGetDetailedInfo()?.lines()?.filter {
            !it.contains("/dev/") && !it.contains("fd=")
        }?.joinToString("\n") ?: "No DAC connected"
    }

    fun getNativeLog(): String {
        return if (isNativeLoaded) nativeGetDebugLog() ?: "" else "Native not loaded"
    }

    /**
     * Read source file sample rate via MediaExtractor (no decode).
     */
    fun getSourceSampleRate(song: Song): Int {
        val path = song.filePath.ifEmpty { song.path }
        if (path.isBlank()) return 48000
        // 【V8.429】收敛到单线程闸门（切歌时本函数被高频调用，是最易与其它探测
        // 并发拆卸内部 codec 的地方；native abort 详见 MediaProbe 类注释）。
        return try {
            MediaProbe.onProbeThread {
                val ex = android.media.MediaExtractor()
                try {
                    ex.setDataSource(path)
                    for (i in 0 until ex.trackCount) {
                        val fmt = ex.getTrackFormat(i)
                        if (fmt.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                            return@onProbeThread fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                        }
                    }
                    48000
                } finally { ex.release() }
            }
        } catch (_: Throwable) { 48000 }
    }

    /**
     * 源文件真实位深（claim 阶段就需要，不能等 controller.open）。
     * FLAC 读 STREAMINFO 的 bps 字段；WAV 读 fmt chunk 的 bitsPerSample；
     * MP3/AAC/其他压缩格式无固定源位深（平台解码输出），按 16bit 处理。
     * 结果按路径缓存，避免切歌高频重复读。
     */
    fun getSourceBitDepth(song: Song): Int {
        val path = song.filePath.ifEmpty { song.path }
        if (path.isBlank()) return 16
        sourceBitDepthCache[path]?.let { return it }
        val bits = try {
            when {
                path.endsWith(".flac", true) -> readFlacBitsPerSample(path)
                path.endsWith(".wav", true) -> readWavBitsPerSample(path)
                else -> 16
            }
        } catch (_: Throwable) { 16 }
        val result = if (bits in 16..32) bits else 16
        // 【fix15】非标位深归整到 DAC 标准 alt(16/24/32)：部分 FLAC 是 20/21bit 等合法但
        // 非标值，DAC alt 只有 16/24/32(subslot 2/3/4) 且原生精确匹配 → 21bit 请求被拒后
        // 虽走降级兜底重开 16bit，但每次多一次 1.5s cooldown 重开(切歌变慢/卡顿)。
        // 向上取到最近标准位深(保精度)，避免无谓重开。
        val normalized = when {
            result <= 16 -> 16
            result <= 24 -> 24
            else -> 32
        }
        if (sourceBitDepthCache.size > 256) sourceBitDepthCache.clear()
        sourceBitDepthCache[path] = normalized
        return normalized
    }

    /**
     * FLAC: "fLaC" + block header(4) + STREAMINFO(34B)。
     * STREAMINFO 布局：minBlock(16) maxBlock(16) minFrame(24) maxFrame(24)
     *   sr(20) ch(3) bps(5) totalSamples(36) MD5(128)
     * sr/ch/bps 紧跟 4 个块帧字段后（数据区 offset 10-13，即文件 offset 18-21）：
     *   sr = (b[18]<<12)|(b[19]<<4)|(b[20]>>4)；ch = ((b[20]&0xE)>>1)+1；
     *   bps = ((b[20]&0x01)<<4 | b[21]>>4) + 1
     * 注意：bps 在文件 offset 20/21，不是 28/29（后者是 totalSamples/MD5 区，
     * 曾因偏移错 8 字节把 24bit 读成 4~32 垃圾值，包括"21bit"幽灵）。
     */
    private fun readFlacBitsPerSample(path: String): Int {
        val buf = ByteArray(30)
        java.io.RandomAccessFile(path, "r").use { it.readFully(buf, 0, buf.size) }
        if (buf[0] != 'f'.code.toByte() || buf[1] != 'L'.code.toByte() ||
            buf[2] != 'a'.code.toByte() || buf[3] != 'C'.code.toByte()) return 16
        if (buf[4].toInt() and 0x7F != 0) return 16  // 第一个 metadata block 必须是 STREAMINFO(type 0)
        return (((buf[20].toInt() and 0x01) shl 4) or ((buf[21].toInt() and 0xF0) shr 4)) + 1
    }

    /** WAV: 扫 chunks 找 "fmt "；bitsPerSample 在 fmt 数据 offset 14 */
    private fun readWavBitsPerSample(path: String): Int {
        return java.io.RandomAccessFile(path, "r").use { raf ->
            val hdr = ByteArray(12)
            raf.readFully(hdr)
            if (String(hdr, 0, 4) != "RIFF" || String(hdr, 8, 4) != "WAVE") return 16
            repeat(16) {
                val ch = ByteArray(8)
                if (raf.read(ch) < 8) return 16
                val size = (ch[4].toInt() and 0xFF) or ((ch[5].toInt() and 0xFF) shl 8) or
                        ((ch[6].toInt() and 0xFF) shl 16) or ((ch[7].toInt() and 0xFF) shl 24)
                if (String(ch, 0, 4) == "fmt ") {
                    val fmt = ByteArray(16)
                    raf.readFully(fmt, 0, minOf(16, size))
                    return (fmt[14].toInt() and 0xFF) or ((fmt[15].toInt() and 0xFF) shl 8)
                }
                var skip = size.toLong()
                if (skip < 0) return 16
                if (skip % 2 == 1L) skip++
                raf.seek(raf.filePointer + skip)
            }
            16
        }
    }

    // ============================================================
    // Private helpers
    // ============================================================
    private fun maybeDumpStructure(device: UsbDevice) {
        val id = device.deviceId
        if (id == dumpedDeviceId) return
        dumpedDeviceId = id
        dumpStructure(device)
    }

    private fun dumpStructure(device: UsbDevice) {
        DebugLog.v(TAG, "=== USB DESCRIPTOR STRUCTURE vid=${device.vendorId.toString(16)} pid=${device.productId.toString(16)} name=${device.productName} ===")
        DebugLog.v(TAG, "bcdDevice=${device.version} devCls=${device.deviceClass} devSub=${device.deviceSubclass} devProto=${device.deviceProtocol} ifaceCount=${device.interfaceCount}")
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val sb = StringBuilder()
            sb.append("iface[$i] id=${iface.id} alt=${iface.alternateSetting} cls=${iface.interfaceClass} sub=${iface.interfaceSubclass} proto=${iface.interfaceProtocol} eps=${iface.endpointCount}")
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                sb.append(" | ep$j addr=0x${ep.address.toString(16)} type=${ep.type} dir=${ep.direction} mps=${ep.maxPacketSize} interval=${ep.interval}")
            }
            DebugLog.add(TAG, sb.toString())
        }
    }

    /** Full DAC diagnostic dump (DebugLog.add, visible in Settings panel) */
    fun dumpDacInfo(device: UsbDevice, audioManager: android.media.AudioManager? = null) {
        DebugLog.add(TAG, "======== DAC DIAGNOSTIC START ========")
        DebugLog.add(TAG, "USB: vid=0x${device.vendorId.toString(16)} pid=0x${device.productId.toString(16)} name=${device.productName}")
        DebugLog.add(TAG, "USB: version=${device.version} devClass=${device.deviceClass} devSub=${device.deviceSubclass} devProto=${device.deviceProtocol} ifaceCount=${device.interfaceCount}")
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val sb = StringBuilder()
            sb.append("iface[$i] id=${iface.id} alt=${iface.alternateSetting} cls=${iface.interfaceClass} sub=${iface.interfaceSubclass} proto=${iface.interfaceProtocol} eps=${iface.endpointCount}")
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                sb.append(" | ep$j addr=0x${ep.address.toString(16)} type=${ep.type} dir=${ep.direction} mps=${ep.maxPacketSize} interval=${ep.interval}")
            }
            DebugLog.add(TAG, sb.toString())
        }
        // Android AudioDeviceInfo
        if (audioManager != null) {
            val outDevices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            val usbOuts = outDevices.filter { it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET || it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE }
            if (usbOuts.isNotEmpty()) {
                for (d in usbOuts) {
                    DebugLog.add(TAG, "AudioDevice: id=${d.id} type=${d.type} name=${d.productName} addr=${d.address}")
                    DebugLog.add(TAG, "AudioDevice: sampleRates=${d.sampleRates?.toList()} channels=${d.channelCounts?.toList()} encodings=${d.encodings?.toList()}")
                }
            } else {
                DebugLog.add(TAG, "AudioDevice: no USB output device found (HAL may not expose)")
            }
        }
        DebugLog.add(TAG, "======== DAC DIAGNOSTIC END ========")
    }

    @Volatile private var dumpedRawId = -1
    private fun maybeDumpRaw(conn: UsbDeviceConnection, id: Int) {
        // V3.3.4: descriptor dump disabled - GET_DESCRIPTOR config transfer hung 2s (read=-1)
        // right before a device shutdown (suspected USB stack kernel crash). Diagnostic only, not needed.
        return
    }

    private fun dumpConfigDescriptor(conn: UsbDeviceConnection) {
        try {
            // Standard USB GET_DESCRIPTOR control request (bmRequestType=0x80 IN)
            val devBuf = ByteArray(18)
            val devLen = conn.controlTransfer(
                0x80, 0x06, 0x0100, 0,
                devBuf, devBuf.size, 1000
            )
            DebugLog.v(TAG, "GET_DESCRIPTOR device read=$devLen")
            hexDump(devBuf, devLen)
            // Config descriptor header (9 bytes) -> wTotalLength at bytes [2..3]
            val hdr = ByteArray(9)
            val hdrLen = conn.controlTransfer(
                0x80, 0x06, 0x0200, 0,
                hdr, hdr.size, 1000
            )
            if (hdrLen >= 9) {
                val total = ((hdr[3].toInt() and 0xFF) shl 8) or (hdr[2].toInt() and 0xFF)
                DebugLog.v(TAG, "config wTotalLength=$total")
                val cfgBuf = ByteArray(total.coerceAtMost(4096))
                val cfgLen = conn.controlTransfer(
                    0x80, 0x06, 0x0200, 0,
                    cfgBuf, cfgBuf.size, 2000
                )
                DebugLog.v(TAG, "GET_DESCRIPTOR config read=$cfgLen")
                hexDump(cfgBuf, cfgLen)
            } else {
                DebugLog.v(TAG, "config header read=$hdrLen (FAILED)")
            }
        } catch (e: Exception) {
            DebugLog.add(TAG, "dumpConfigDescriptor FAILED: ${e.message}")
        }
    }

    private fun hexDump(buf: ByteArray, len: Int) {
        val n = if (len < 0) buf.size else len.coerceAtMost(buf.size)
        val sb = StringBuilder()
        for (i in 0 until n) {
            sb.append(String.format("%02X ", buf[i].toInt() and 0xFF))
            if ((i + 1) % 16 == 0) { DebugLog.v(TAG, "RAW: $sb"); sb.setLength(0) }
        }
        if (sb.isNotEmpty()) DebugLog.v(TAG, "RAW: $sb")
    }

    private fun isAudioDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_AUDIO) return true
            if (iface.interfaceClass == 0x10 &&
                (iface.interfaceSubclass == SUBCLASS_AUDIOCONTROL ||
                 iface.interfaceSubclass == SUBCLASS_AUDIOSTREAMING)) return true
        }
        return false
    }

    private fun getEndpointInfo(device: UsbDevice, targetIfaceId: Int): UsbEndpointInfo? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (i != targetIfaceId) continue  // match by index, not UsbInterface.id (may be garbage)
            if (iface.interfaceClass == USB_CLASS_AUDIO && iface.interfaceSubclass == SUBCLASS_AUDIOSTREAMING) {
                val uac2 = iface.interfaceProtocol == PROTOCOL_UAC2
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && ep.direction == UsbConstants.USB_DIR_OUT) {
                        return UsbEndpointInfo(ep.address, ep.maxPacketSize, ep.interval, uac2 || ep.maxPacketSize > 1024 || ep.interval > 1)
                    }
                }
            }
        }
        return null
    }

    private fun registerHotplugReceiver(context: Context) {
        if (hotplugReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        // 设备拔除：只清幽灵缓存，不 stopAndRelease（FLAC 解码线程可能仍持有
                        // driver 指针，nativeRelease 会 use-after-free）。native 层会随设备拔除
                        // 自然失效；下次 findDacs 扫描 result=0 时也会清缓存。
                        // [V9.20x] 与 native ENODEV 兜底共用同一条路径（handleDeviceDetached）。
                        handleDeviceDetached("broadcast")
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        // [Hotplug] Notify the host (MusicService) so it can re-claim the DAC and
                        // auto-resume playback WITHOUT a manual app restart. The cache is
                        // invalidated inside so findDacs() rescans the freshly attached device.
                        // [V9.20x] 代数控：重复枚举的 ATTACHED 广播不会触发重复 reclaim。
                        handleDeviceAttached("broadcast")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnsafeRegisteredReceiver") context.registerReceiver(receiver, filter)
        hotplugReceiver = receiver
    }

    private fun registerPermissionReceiver(context: Context) {
        if (permissionReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                DebugLog.add(TAG, "perm result: granted=$granted device=${device?.productName}")
                if (granted && device != null) {
                    val claim = pendingClaim
                    if (claim != null && claim.device.deviceId == device.deviceId) {
                        pendingClaim = null
                        claimAndStart(device, claim.sampleRate, claim.channels, claim.bitsPerSample, claim.ifaceNum)
                    }
                } else { pendingClaim = null }
                permissionRequestingDeviceId = -1  // [Debounce] request resolved (granted or denied)
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnsafeRegisteredReceiver") context.registerReceiver(receiver, filter)
        permissionReceiver = receiver
    }

    // ============================================================
    // JNI externals
    // ============================================================

    private external fun nativeUsbAvailable(): Boolean
    private external fun nativeClaim(vid: Int, pid: Int, fd: Int, address: Int, maxPacketSize: Int, epInterval: Int, isUac2: Boolean, ifaceNum: Int): Boolean
    private external fun nativeUsbStart(sampleRate: Int, channels: Int, bitsPerSample: Int): Boolean
    private external fun nativePushPcm(data: FloatArray, frameCount: Int): Int
    private external fun nativeGetRingFill(): Int
    private external fun nativeStop()
    private external fun nativeResetRingBuffer()
    private external fun nativeStopThreadOnly()
    private external fun nativeRelease()
    private external fun nativeIsClaimed(): Boolean
    private external fun nativeIsStreaming(): Boolean
    private external fun nativeGetUnderrunCount(): Int
    // 【V9.20x】运行时诊断快照：定长 LongArray，索引见 DacRuntimeMetrics.Companion
    private external fun nativeGetRuntimeMetrics(): LongArray
    // [V9.20x] hotplug fallback: one-shot "device vanished" latch fed by consecutive
    // ENODEV results from native URB submission.
    private external fun nativeConsumeDeviceVanished(): Boolean
    private external fun nativeGetDacName(): String?
    private external fun nativeGetCurrentSampleRate(): Int
    private external fun nativeGetCurrentBits(): Int
    private external fun nativeGetDetailedInfo(): String?
    private external fun nativeGetSupportedRates(): String?
    private external fun nativeGetStats(): String?
    // [P1 adaptive-DAC] descriptor-derived capability snapshot (wireBits / FU / 44.1k)
    private external fun nativeGetDescriptorProfile(): String?
    private external fun nativeGetDebugLog(): String?
    private external fun nativeClearDebugLog()
    private external fun nativeSetVolume(volume: Float)
    private external fun nativeSetDitherEnabled(enabled: Boolean)
    // [V8.119] 宽裕余量档（USB DAC 链，无 limiter，仅 dacSoftClip 兜底）
    private external fun nativeSetWideHeadroom(enabled: Boolean)
    // [V8.119] 原声直通（USB DAC 链整条 DSP 旁路）
    private external fun nativeSetDirectPassthrough(enabled: Boolean)
    // 【V9.01】引擎 DSP 主开关（DAC 模块"启用引擎 DSP"）
    private external fun nativeSetEngineDspEnabled(enabled: Boolean)
    private external fun nativeIsEngineDspEnabled(): Boolean
    // 【V9.01】DAC 模块子开关：MSEB / AutoEQ / DTS 环绕 各自独立的使能翻转
    private external fun nativeSetMsebEnabled(enabled: Boolean)
    private external fun nativeSetAutoEqEnabled(enabled: Boolean)
    private external fun nativeSetDtsEnabled(enabled: Boolean)
    private external fun nativeSetSoftStartEnabled(enabled: Boolean)
    private external fun nativeSetFeedbackEnabled(enabled: Boolean)
    private external fun nativeIsFeedbackActive(): Boolean
    private external fun nativeGetFeedbackMeasuredRate(): Double
    private external fun nativeGetFeedbackCorrectionPpm(): Double
    private external fun nativeSetFeatureUnitOverride(unitId: Int, channel: Int, channels: Int, minDb: Float, maxDb: Float, resDb: Float)

    // [2026-10-22] 强制软件音量：已实证硬件 FU 不衰减的设备（31B2:1112）走软件增益，
    // 不再信任何硬件音量探测/覆盖。调用后 native 侧 hardwareVolumeReady_=false。
    private external fun nativeForceSoftwareVolume()
    private external fun nativeSetQuirkOverride(urbPackets: Int, ctlMsgDelayUs: Int, ifaceDelayUs: Int, forceIfaceReset: Boolean, forceUac1: Boolean, skipSetCur: Boolean)
    private external fun nativeSetDspEnabled(enabled: Boolean)
    private external fun nativeSetDspEq5Band(gainsDb: FloatArray, freqsHz: FloatArray?)
    private external fun nativeResetDspEq5Band()
    private external fun nativeSetMseb10Band(gainsDb: FloatArray, freqsHz: FloatArray, qValues: FloatArray)
    private external fun nativeResetMseb10Band()
    private external fun nativeSetAutoEq10Band(gainsDb: FloatArray, freqsHz: FloatArray, qValues: FloatArray, filterTypes: IntArray, preampDb: Float)
    private external fun nativeResetAutoEq()
    private external fun nativeSetMsStage(soundstage: Float, imaging: Float)
    private external fun nativeResetMsStage()
    private external fun nativeSetTransient(amount: Float)
    private external fun nativeResetTransient()
    private external fun nativeSetCompressorEnabled(enabled: Boolean)
    private external fun nativeSetCompressorParams(thresholdDb: Float, ratio: Float, attackMs: Float, releaseMs: Float, makeupDb: Float)
    private external fun nativeSetLoudnessEnabled(enabled: Boolean)
    private external fun nativeSetLoudnessIntensity(intensity: Float)
    private external fun nativeSetLoudnessOutGain(gain: Float)
    private external fun nativeSetNormalizationGain(db: Float)
    private external fun nativeForceReset()
    fun forceReset() { if (isNativeLoaded) nativeForceReset() }

    // [V9.20n] Session generation: every new UsbDacPlaybackController.play() calls
    // beginSession() which bumps this. A monitor/decode thread from an OLD session
    // (track switched away before its STALL self-heal fired) must NOT forceReset —
    // that kills the NEW session's stream -> forceReset ping-pong storm -> multi-
    // second freeze. Stale sessions detect myGen != sessionGen and exit quietly.
    @Volatile var sessionGen: Int = 0
    fun beginSession(): Int { sessionGen++; return sessionGen }

    // 【V3.3.0】native libFLAC 硬解(绕开 Moto MediaCodec 24bit→16bit 降级)
    private external fun nativeFlacOpen(path: String): Boolean
    private external fun nativeFlacInfo(): IntArray
    private external fun nativeFlacStart(): Boolean
    private external fun nativeFlacPause(paused: Boolean)
    private external fun nativeFlacSeek(ms: Long)
    private external fun nativeFlacStop()
    private external fun nativeFlacIsEos(): Boolean
    private external fun nativeFlacPositionMs(): Long
    private external fun nativeFlacGaplessSeek(path: String, targetSample: Long): Boolean
    private external fun nativeFlacTotalSamples(): Long
    private external fun nativeFlacReadLyrics(path: String): String?
    // [NeriPlayer 引擎] pull 模式：解码下一块到 out（float 交织），返回帧数；0=EOS；-1=暂停中
    private external fun nativeFlacReadChunk(out: FloatArray, maxFrames: Int): Int

    // ── ALAC 直解（native Apple codec + 手搓 MP4 box 解析）──
    private external fun nativeAlacOpen(path: String): Boolean
    private external fun nativeAlacInfo(): IntArray
    private external fun nativeAlacStart(): Boolean
    private external fun nativeAlacPause(paused: Boolean)
    private external fun nativeAlacSeek(ms: Long)
    private external fun nativeAlacStop()
    private external fun nativeAlacIsEos(): Boolean
    private external fun nativeAlacPositionMs(): Long
    private external fun nativeAlacTotalSamples(): Long
    // [NeriPlayer 引擎] pull 模式：解码下一块到 out（float 交织），返回帧数；0=EOS；-1=暂停中
    private external fun nativeAlacReadChunk(out: FloatArray, maxFrames: Int): Int

    /** [NeriPlayer 引擎] FLAC pull 读取（帧数；0=EOS；-1=暂停中）。缓冲区需 ≥ 4096*声道 float。 */
    fun flacReadChunk(out: FloatArray, maxFrames: Int): Int {
        if (!isNativeLoaded) return 0
        return try { nativeFlacReadChunk(out, maxFrames) } catch (e: Throwable) {
            DebugLog.add(TAG, "flacReadChunk EXCEPTION: ${e.message}"); 0
        }
    }

    /** [NeriPlayer 引擎] ALAC pull 读取（帧数；0=EOS；-1=暂停中）。缓冲区需 ≥ frameLen*声道 float。 */
    fun alacReadChunk(out: FloatArray, maxFrames: Int): Int {
        if (!isNativeLoaded) return 0
        return try { nativeAlacReadChunk(out, maxFrames) } catch (e: Throwable) {
            DebugLog.add(TAG, "alacReadChunk EXCEPTION: ${e.message}"); 0
        }
    }

    // ── [NeriPlayer 引擎] 供 UsbDacPlaybackController pull 循环使用的薄封装 ──
    private external fun nativeFlacSetPullMode(enabled: Boolean)

    /** pull 模式开关（切引擎时调用；影响 native FLAC gapless 跨文件是否起线程） */
    fun setFlacPullMode(enabled: Boolean) {
        if (isNativeLoaded) try { nativeFlacSetPullMode(enabled) } catch (_: Throwable) {}
    }

    fun neriDeviceOffline(): Boolean {
        if (!useNeriEngine() || !neriSession.isOpen) return false
        val r = neriSession.report()
        return !r.deviceOnline || r.terminalFailure || r.transportFailed
    }

    fun neriTerminalFailed(): Boolean {
        if (!useNeriEngine() || !neriSession.isOpen) return false
        return neriSession.report().terminalFailure
    }

    fun neriOutputPeak(): Float {
        if (!useNeriEngine() || !neriSession.isOpen) return 0f
        return neriSession.report().outputPeak
    }

    fun neriDrain(): Boolean {
        if (!useNeriEngine() || !neriSession.isOpen) return false
        return neriSession.drain()
    }

    /** [NeriPlayer 引擎] 会话运行时报告字符串（诊断 UI 用） */
    fun neriRuntimeReport(): String =
        if (useNeriEngine() && neriSession.isOpen) NeriUsbNativeBridge.runtimeReport(neriSession.handle) else ""
}
