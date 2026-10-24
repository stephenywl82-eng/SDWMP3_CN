#include "usb_audio_driver.h"
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sched.h>
#include <pthread.h>
#include <linux/usbdevice_fs.h>
#include <time.h>
#include <linux/usb/ch9.h>
#include <errno.h>
#include <cstdarg>
#include <cstdlib>
#include "loudness_comp.h"
#include <chrono>
#include <thread>

#define TAG "UsbAudioDriver"

// Salt-style: log to both Android logcat AND in-memory ring buffer for in-app display
#define LOGI(fmt, ...)  do { \
    __android_log_print(ANDROID_LOG_INFO, TAG, fmt, ##__VA_ARGS__); \
    nativeLog(TAG, fmt, ##__VA_ARGS__); \
} while(0)
#define LOGW(fmt, ...)  do { \
    __android_log_print(ANDROID_LOG_WARN, TAG, fmt, ##__VA_ARGS__); \
    nativeLog(TAG "!WARN", fmt, ##__VA_ARGS__); \
} while(0)
#define LOGE(fmt, ...)  do { \
    __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##__VA_ARGS__); \
    nativeLog(TAG "!ERR", fmt, ##__VA_ARGS__); \
} while(0)

// 鈹€鈹€ nativeLog ring buffer implementation 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

// ============================================================================
// [V8.118] DAC-path safety soft-clip shaper.
// The DAC streaming chain (decode thread -> ring -> URB) had NO look-ahead
// limiter, unlike the Oboe path. EQ / compressor / loudness boosts were
// therefore hard-clipped by the plain `if (s>1) s=1` guards at PACK time,
// which is square-wave distortion — heard as a buzz/crackle on drums & bass.
// This shaper (same cubic sigmoid as the Oboe softClip, MAKEUP=1.0) keeps the
// signal below the packer's clamp. Bit-perfect path (no DSP) is untouched.
// ============================================================================
// [V8.119] NOTE: these must NOT be `static` -- usb_audio_driver.h setters reference
// them from an inline member function, which lives in every TU that includes the header.
std::atomic<bool> g_dacWideHeadroom{true};          // DAC headroom profile
std::atomic<bool> g_dacDirectPassthrough{false};    // DAC 原声直通（整链旁路）

// 【V9.01】引擎 DSP 主开关（DAC 模块"启用引擎 DSP"）。false = 创意 DSP 整链旁路，
// 与 g_dacDirectPassthrough 正交：两者任一为真都进入 bit-perfect 直通。
std::atomic<bool> g_engineDspEnabled{true};          // 引擎 DSP 主开关

static inline float dacSoftClip(float x) {
    // [V8.119] knee follows the headroom profile (same rationale as the Oboe
    // softClip): 0.92 stays linear up to -0.7 dBFS instead of wave-shaping
    // every drum/vocal peak from -3.5 dBFS.
    const float T = g_dacWideHeadroom.load() ? 0.92f : (2.0f / 3.0f);
    const float RANGE = 1.0f - T;
    float ax = fabsf(x);
    if (ax <= T) return x;
    float s = (x < 0.0f) ? -1.0f : 1.0f;
    float norm = (ax - T) / RANGE;
    if (norm >= 1.0f) return s * (T + (2.0f / 3.0f) * RANGE);   // ceiling ~0.8889
    float ct = norm - (1.0f / 3.0f) * norm * norm * norm;
    return s * (T + ct * RANGE);
}
static std::atomic<int64_t> g_dacClipCount{0};   // [V8.118] samples the packer had to clamp

void UsbAudioDriver::nativeLog(const char* tag, const char* fmt, ...) {
    if (!fmt) return;
    char line[384];
    // Timestamp: since we can't use clock_gettime easily, use a monotonic counter
    static std::atomic<int64_t> seq{0};
    int64_t s = seq.fetch_add(1);
    int sec = (int)(s / 1000);
    int ms  = (int)(s % 1000);
    int off = snprintf(line, 32, "[%d.%03d] ", sec, ms);

    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line + off, sizeof(line) - off - 1, fmt, ap);
    va_end(ap);
    size_t len = strlen(line);
    if (len > 0 && line[len-1] != '\n') { line[len] = '\n'; line[len+1] = 0; }

    size_t remain = kNativeLogSize - nativeLogWrite_ - 1;
    if (strlen(line) < remain) {
        strcpy(nativeLogBuf_ + nativeLogWrite_, line);
        nativeLogWrite_ += strlen(line);
    } else {
        // Buffer full: wrap around
        nativeLogWrite_ = 0;
        strcpy(nativeLogBuf_, line);
        nativeLogWrite_ = strlen(line);
    }
}

// ============================================================================
// USB control request constants
// ============================================================================
static constexpr uint8_t REQ_GET_CUR = 0x81;  // device-to-host, class, interface
static constexpr uint8_t REQ_GET_MIN = 0x81;
static constexpr uint8_t REQ_GET_MAX = 0x81;
static constexpr uint8_t REQ_SET_CUR = 0x21;  // host-to-device, class, interface

static constexpr uint8_t UAC_SET_CUR = 0x01;
static constexpr uint8_t UAC_GET_CUR = 0x81;

static constexpr uint8_t CS_SAMPLING_FREQ_CONTROL = 0x01;

// UAC 1.0 standard request codes for class-specific interface
static constexpr uint8_t CUR_ATTR   = 0x01; // SET_CUR / GET_CUR
static constexpr uint16_t SAMPLING_FREQ_CONTROL = 0x0100; // CS = 1 (sampling freq), CN = 0

// UAC 2.0: Clock Source descriptor subtype = 0x0A, Clock Selector = 0x0B
static constexpr uint8_t UAC2_CS_CLOCK_SOURCE   = 0x0A;
static constexpr uint8_t UAC2_CS_CLOCK_SELECTOR = 0x0B;
static constexpr uint8_t UAC2_CS_INTERFACE       = 0x24;
static constexpr uint8_t UAC2_CS_ENDPOINT        = 0x25;

// ============================================================================
// UsbAudioDriver implementation
// ============================================================================

UsbAudioDriver::UsbAudioDriver() {
    ringBuffer_ = static_cast<float*>(calloc(kRingFrames * 2, sizeof(float))); // stereo interleaved
    if (!ringBuffer_) {
        LOGE("Failed to allocate ring buffer");
    }
    // Allocate variable-length usbdevfs_urb for each slot
    for (int i = 0; i < kMaxUrbCount; i++) {
        size_t urbSize = sizeof(usbdevfs_urb) + urbPackets_ * sizeof(usbdevfs_iso_packet_desc);
        urbSlots_[i].urb = static_cast<usbdevfs_urb*>(calloc(1, urbSize));
        if (!urbSlots_[i].urb) {
            LOGE("Failed to allocate URB slot %d", i);
        }
    }
    LOGI("UsbAudioDriver created, ring=%p, %d frames", ringBuffer_, kRingFrames);
}

UsbAudioDriver::~UsbAudioDriver() {
    stop();
    stopFeedbackUrb();   // defensive: free feedback URB if still allocated
    if (ringBuffer_) {
        free(ringBuffer_);
        ringBuffer_ = nullptr;
    }
    for (int i = 0; i < kMaxUrbCount; i++) {
        if (urbSlots_[i].urb) {
            free(urbSlots_[i].urb);
            urbSlots_[i].urb = nullptr;
        }
    }
    LOGI("UsbAudioDriver destroyed");
}

// 鈹€鈹€ open 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

bool UsbAudioDriver::open(int fd, int epAddress, int maxPacketSize, int interval,
                          bool isUac2, int vid, int pid, int ifaceNum) {
    fd_ = fd;
    epAddress_ = epAddress;
    maxPacketSize_ = maxPacketSize;
    interval_ = (interval > 0) ? interval : 1;
    isUac2_ = isUac2;
    vid_ = vid;
    pid_ = pid;
    ifaceNum_ = ifaceNum;
    // [2026-10-22] 新设备/新 claim：作废上一个设备的已验证时钟实体。
    verifiedClockId_ = -1;

    // [V9.20x hotplug fallback] A fresh open means the device is back: clear any
    // vanish latch left over from the previous session so pushPcm isn't rejected.
    consecutiveEnodev_.store(0, std::memory_order_release);
    deviceVanished_.store(false, std::memory_order_release);
    // 【V9.20x】每开一次设备清掉上一段会话的诊断计数，保证指标按会话对齐
    resetMetrics();

    char buf[256];
    snprintf(buf, sizeof(buf), "Open: fd=%d ep=0x%02X mps=%d int=%d uac2=%d vid=%04X pid=%04X iface=%d",
             fd_, epAddress_, maxPacketSize_, interval_, isUac2_, vid_, pid_, ifaceNum_);
    detailedInfo_ = buf;
    LOGI("%s", buf);

    // [align Salt] Read the REAL USB bus speed. UAC2 protocol (isUac2_) does NOT
    // imply high-speed: full-speed (12Mbps) UAC2 devices are common (TTGK Note
    // 3302:201D is one — its 3 alts all carry 49 samples/frame = 44.1/48kHz at
    // 1000 frames/sec). Treating isUac2_ as high-speed made streamLoop send
    // sampleRate/8000 frames per packet (~5.5 @44.1k) instead of sampleRate/1000
    // (~44 @44.1k) — 8x wrong, which stalls the DAC mid-song.
    // USBDEVFS_GET_SPEED returns usb_device_speed: 2=FULL, 3=HIGH.
    int speed = ioctl(fd_, USBDEVFS_GET_SPEED, nullptr);
    highSpeed_ = (speed == 3 /*USB_SPEED_HIGH*/);
    LOGI("Open: USB bus speed=%d (%s)", speed, highSpeed_ ? "HIGH" : (speed == 2 ? "FULL" : "other"));

    // [2026-09-10 quirk init] Per-device behavior, seeded from Linux snd-usb-audio
    // quirks.c as *candidates* — each entry must be confirmed by our own capture
    // logs before trusting it (principle: kernel entry = clue, not gospel).
    urbPackets_ = kPacketsPerUrb;
    ctlMsgDelayUs_ = 0;
    if (vid_ == 0x3302) {
        // TTGK Technology (kernel: DEVICE_FLG 3302:17c2 FORCE_IFACE_RESET|IFACE_DELAY).
        // FORCE_IFACE_RESET is already our teardown path (setInterfaceAlt(0) when alt
        // changes); IFACE_DELAY = 5ms between interface ops.
        ctlMsgDelayUs_ = 2000;   // small settle after SET_CUR
        LOGI("quirk[TTGK 3302]: iface-delay 5ms on alt switch, ctl-delay 2ms");
    } else if (vid_ == 0x0A12 && pid_ == 0x4007) {
        // Qudelix-5K: UAC1 full-speed pushing 96k/24bit. 64 pkts * 576B = 36.8KB/URB
        // x 12 in-flight = 442KB -> usbfs ENOMEM on submit #13. Drop to 16 pkts
        // (9.2KB/URB, ~110KB in flight) to stay under the kernel limit.
        ctlMsgDelayUs_ = 2000;   // ES9219 needs a moment to lock the PLL after SET_CUR
    }
    if (!highSpeed_) {
        // Generic full-speed safety: 64*576B*12 = 442KB in flight exceeds usbfs
        // limits even at 48k/24bit (288B*64*12=221KB borderline). 16 pkts is safe.
        urbPackets_ = 16;
        LOGI("quirk: full-speed bus -> urbPackets_=%d (was 64)", urbPackets_);
    }
    if (urbPackets_ != kPacketsPerUrb || ctlMsgDelayUs_ != 0) {
        LOGI("quirk[vid=%04X pid=%04X hs=%d]: urbPackets_=%d ctlMsgDelayUs_=%d",
             vid_, pid_, highSpeed_ ? 1 : 0, urbPackets_, ctlMsgDelayUs_);
    }

    // 鈺愨晲锟?DISCONNECT kernel audio driver FIRST 鈺愨晲锟?
    // Android kernel already has the USB Audio Class driver (snd_usb_audio)
    // attached to this device. That driver claims the interfaces and owns the
    // clock, causing EBUSY on any SET_CUR control transfer.
    // USBDEVFS_DISCONNECT tells the kernel driver to release its claim so we
    // can talk directly to the hardware. This is what AAudio/HAL exclusive mode
    // implicitly does via AudioFlinger.
    // Interface number 0 = the AudioControl interface; we must disconnect
    // the kernel driver before claiming our own.
    // USBDEVFS_DISCONNECT (0x5502): force kernel audio driver off the device.
    // May not be available on all Android kernels. We continue regardless.
    struct usbdevfs_ioctl disconnect = {};
    disconnect.ifno = 0;
    disconnect.ioctl_code = 0x5502;  // USBDEVFS_DISCONNECT = _IO('U', 2)
    disconnect.data = nullptr;
    int discRet = ioctl(fd_, USBDEVFS_IOCTL, &disconnect);
    if (discRet == 0) {
        LOGI("DISCONNECT kernel driver iface=0: OK (in-kernel audio driver released)");
    } else if (errno == ENOTTY) {
        // This kernel does not implement USBDEVFS_DISCONNECT. Benign ONLY when no
        // in-kernel audio driver (snd_usb_audio) is bound to the device. Our TTGK
        // 33C0 test DAC works this way, so downgrade from a hard error to a warning.
        LOGW("DISCONNECT kernel driver iface=0: ENOTTY (unsupported on this kernel; only "
             "safe because no in-kernel audio driver is bound)");
    } else if (errno == EBUSY) {
        // A kernel audio driver IS bound and refused to release -> claim() below will
        // likely EBUSY and playback will wedge. Surface this loudly instead of silently.
        LOGE("DISCONNECT kernel driver iface=0: EBUSY (in-kernel audio driver bound and "
             "would not release; expect claim conflict / wedged playback)");
    } else {
        LOGW("DISCONNECT kernel driver iface=0: ret=%d errno=%d (%s)",
             discRet, errno, strerror(errno));
    }

    // [v6.x adaptive] REMOVED hardcoded SET_CUR(44100) from open().
    // Problem: on 48kHz-only DACs (Realtek 4BA6), setting 44100 before knowing the actual
    // song rate can put the DAC into a broken state if the DAC firmware fakes success.
    // The rate switch now happens exclusively in start(), where we know the actual sample rate.
    // We still need a sensible default so clockRate_ isn't garbage — assume 0 (unknown).
    // TTGK 33C0 is the only DAC that matters here and it worked with SET_CUR anyway.
    clockRate_ = 0;  // unknown until first start()
    LOGI("open: clock deferred to start() — no hardcoded SET_CUR");

    // 鈹€鈹€ DEBUG: 璇婃柇鏃堕挓鏄惁鐪熸鍒囨崲 鈹€鈹€
    {
        // 鏂瑰紡1: GET_CUR CS_SAM_FREQ_CONTROL via AC iface (鏍囧噯璇绘硶)
        for (int tryCid = 1; tryCid <= 10; tryCid++) {
            uint32_t curRate = 0;
            struct usbdevfs_ctrltransfer ct = {};
            ct.bRequestType = 0xA1; ct.bRequest = 0x01; // GET_CUR
            ct.wValue = 0x0100;  // CS_SAM_FREQ_CONTROL << 8
            ct.wIndex = (uint16_t)((tryCid & 0xFF) | 0x0000); // clockId + AC iface=0
            ct.wLength = 4; ct.timeout = 500; ct.data = &curRate;
            int r = ioctl(fd_, USBDEVFS_CONTROL, &ct);
            if (r == 4 && curRate > 0) {
                LOGI("DEBUG GET_CUR OK: clockId=%d rate=%u (ret=%d)", tryCid, curRate, r);
                clockRate_ = (int)curRate;
            }
        }
        // 鏂瑰紡2: GET_CUR via streaming iface (ifaceNum_)
        {
            uint32_t curRate = 0;
            struct usbdevfs_ctrltransfer ct = {};
            ct.bRequestType = 0xA1; ct.bRequest = 0x01;
            ct.wValue = 0x0100;
            ct.wIndex = (uint16_t)ifaceNum_;
            ct.wLength = 4; ct.timeout = 500; ct.data = &curRate;
            int r = ioctl(fd_, USBDEVFS_CONTROL, &ct);
            LOGI("DEBUG GET_CUR via stream iface=%d: rate=%u (ret=%d)", ifaceNum_, curRate, r);
        }
    }
    // 鈹€鈹€ END DEBUG 鈹€鈹€

    // [fix BTR5 2026-08-24] Do NOT setInterfaceAlt here. Multi-alt DACs pick the
    // wrong alt when hardcoded to alt=1 (BTR5: alt=1 = 32-bit mps=392, alt=2 =
    // 16-bit mps=196). Early setInterfaceAlt(1) made BTR5 timeout (errno=110) and
    // poisoned all later descriptor reads (errno=71 EPROTO). start() selects the
    // correct alt via selectAltForRate() after descriptor parsing. Just mark
    // claimed here; the endpoint gets activated in start().
    currentAlt_ = 0;  // not activated yet; claimed_ flips true only AFTER full parse

    // 銆愯嚜閫傚簲DAC銆戣В鏋愬叏閮ˋudioStreaming alt setting鍊欓€夎〃
    parseAltCandidates();
    LOGI("Alt candidates: %zu found", altCandidates_.size());
    for (size_t i = 0; i < altCandidates_.size(); ++i) {
        auto& c = altCandidates_[i];
        LOGI("  #%zu: iface=%d alt=%d ep=0x%02X mps=%d ch=%d subslot=%d res=%d clkId=%d rate=%d",
             i, c.ifaceNum, c.alt, c.epAddr, c.maxPkt, c.channels, c.subslot, c.res, c.clockId, c.sampleRate);
    }

    // 銆愯嚜閫傚簲DAC銆戣clock sub-range (GET_RANGE) 锟?楠岃瘉姣忎釜rate鏄惁琚獶AC纭欢鏀寔
    // NOTE: parseSupportedRates() MUST run first — parseClockRanges()'s UAC1
    // tSamFreq fallback reads supportedRates_ as its source.
    parseSupportedRates();
    LOGI("Supported sample rates: %s", supportedRates_.c_str());
    parseClockRanges();
    LOGI("Clock ranges: %zu found", clockRanges_.size());
    for (size_t i = 0; i < clockRanges_.size(); ++i) {
        auto& cr = clockRanges_[i];
        LOGI("  #%zu: clkId=%d min=%d max=%d res=%d", i, cr.clockId, cr.min, cr.max, cr.res);
    }

    // Detect UAC 1.0 / 2.0 clock source
    clockSourceId_ = findClockSourceId();
    LOGI("Clock source ID: %d", clockSourceId_);

    // [P3] 实测码率探测：对一批候选码率逐个 SET_CUR+GET_CUR，用设备真实回响
    // 构建"已验证支持码率表"，替代/补全纯描述符声明（yuHIFI 风格：不盲信描述符）。
    // 必须在时钟源解析之后调用（SET_CUR 需要 clockId）。
    probeSampleRates();
    LOGI("Verified sample rates: %s", verifiedRates_.c_str());

    // Find feedback endpoint for async DACs
    feedbackEp_ = findFeedbackEndpoint();
    if (feedbackEp_ > 0) {
        char tmp[64];
        snprintf(tmp, sizeof(tmp), ", fbEp=0x%02X", feedbackEp_);
        detailedInfo_ += tmp;
        LOGI("Feedback endpoint: 0x%02X", feedbackEp_);
    }

    // Parse Feature Unit for hardware volume control
    parseFeatureUnit();

    // [V9.20k] claimed_ flips true ONLY here, after every parse* has run. It used to
    // be set BEFORE parseAltCandidates/probeSampleRates (hundreds of ms of control
    // transfers), so a concurrent isClaimed()->start() from another thread (health
    // watchdog / concurrent playSong) saw altCandidates_ empty -> "48000 Hz outside
    // all clock ranges" -> track refused.
    claimed_.store(true, std::memory_order_release);
    return true;
}

// 鈹€鈹€ start 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

bool UsbAudioDriver::start(int sampleRate, int channels, int bitsPerSample) {
    if (fd_ < 0) {
        LOGE("start: device not open");
        return false;
    }

    // [Stage0 2026-10-23] Recovery latch: if a previous start() failed on an unresponsive
    // device, fail fast for a short cooldown instead of re-parsing descriptors (each read
    // costs a 1s control timeout) on every retry. See startFailureUntilMs_ in the header.
    if (startFailureUntilMs_ > 0) {
        struct timespec tsF;
        clock_gettime(CLOCK_MONOTONIC, &tsF);
        const int64_t nowF = (int64_t)tsF.tv_sec * 1000 + tsF.tv_nsec / 1000000;
        if (nowF < startFailureUntilMs_) {
            LOGW("start: recovery latch armed (%lldms left) - fail fast instead of hammering an unresponsive device",
                 (long long)(startFailureUntilMs_ - nowF));
            return false;
        }
        startFailureUntilMs_ = 0;
    }

    // [Stage0] Arm the recovery latch just before returning false on a device-level failure.
    auto latchStartFailure = [&](const char* why) {
        struct timespec tsL;
        clock_gettime(CLOCK_MONOTONIC, &tsL);
        startFailureUntilMs_ = (int64_t)tsL.tv_sec * 1000 + tsL.tv_nsec / 1000000 + kStartFailureCooldownMs;
        LOGW("start: arming recovery latch for %dms (reason: %s)", kStartFailureCooldownMs, why);
    };

    // [V9.20k] Serialize concurrent start() calls. Two play paths (coalesced
    // pending replay + playSong) can call start within milliseconds of each
    // other; interleaved, the second one clears streamStarted_ (line 548) while
    // the first one polls it -> false "stream did not come up within 800ms" ->
    // Kotlin retries -> forceReset -> multi-second freeze (the "MP3 track
    // switch stuck" symptom). With the lock, the second caller sees
    // same-rate keep-stream below and returns immediately.
    std::lock_guard<std::mutex> startLock(startMutex_);

    // If already streaming, stop old thread cleanly and restart
    // 銆怴3.2.7 淇銆戝繀椤昏蛋 stopThreadOnly()锛氬畠锟?DISCARD 鍦ㄩ URB锟?
    // 涔嬪墠鍙疆 flag+join锟?2 涓湪锟?URB 娌″洖鏀讹紝鍚庣画 setInterfaceAlt(0) EBUSY 澶辫触锟?
    // DAC 鐣欏湪 alt=1(16bit/44.1k)锛宎pp 锟?24bit@48k 锟?宸﹀０閬撳櫔锟?鍙冲０閬撳揩杩涳拷?
    // Same-rate keep-stream: if already streaming with identical params, do NOT restart.
    // The stream thread keeps consuming the ring seamlessly across same-rate track switch
    // (v6.0.11: only stopDecode, never release/restart). Restarting caused a per-track gap.
    if (streaming_.load(std::memory_order_acquire) &&
        sampleRate_ == sampleRate && channels_ == channels && bitsPerSample_ == bitsPerSample) {
        LOGI("start: same-rate keep-stream (sr=%d ch=%d bits=%d), no restart",
             sampleRate, channels, bitsPerSample);
        // [keep-alive] leave idle mode: the live stream goes straight back to playing
        // whatever the decoder pushed. The DAC never lost its clock in between.
        if (idleMode_.exchange(false, std::memory_order_acq_rel)) {
            LOGI("start: resuming from idle-keepalive - DAC never lost lock, no gap");
        }
        return true;
    }

    if (streaming_.load(std::memory_order_acquire)) {
        LOGW("start: restarting stream (old sr=%d ch=%d bits=%d) -> (sr=%d ch=%d bits=%d)",
             sampleRate_, channels_, bitsPerSample_, sampleRate, channels, bitsPerSample);
        stopThreadOnly(true);   // [keep-alive] format really changes -> must reconfigure, no idle shortcut
    }

    sampleRate_ = sampleRate;
    channels_ = channels;
    bitsPerSample_ = bitsPerSample;
    bytesPerFrame_ = channels_ * (bitsPerSample_ / 8);
    loudness_.setSampleRate((float)sampleRate);

    LOGI("start: sr=%d ch=%d bits=%d bytesPerFrame=%d",
         sampleRate_, channels_, bitsPerSample_, bytesPerFrame_);

    // 銆愯嚜閫傚簲DAC銆戜粠descriptor鍊欓€夎〃閫夋渶浣砤lt + 楠岃瘉clock range
    // [V9.20k] Race guard: if open() raced with start() (claimed_ flipped while
    // descriptors were still being parsed), parse them NOW. All parse* are
    // idempotent (cached descriptor reads, zero extra ioctls) and side-effect free
    // (no SET_CUR), so this cannot disturb an in-flight open().
    if (altCandidates_.empty()) {
        LOGW("start: alt candidates missing (open() raced?) — parsing descriptors now");
        parseAltCandidates();
        parseClockRanges();
        if (clockSourceId_ <= 0) clockSourceId_ = findClockSourceId();
    }
    int targetAlt = selectAltForRate(sampleRate_, bitsPerSample_, channels_);
    LOGI("selectAltForRate(r=%d bits=%d ch=%d) -> alt=%d (candidates=%zu, ranges=%zu)",
         sampleRate_, bitsPerSample_, channels_, targetAlt, altCandidates_.size(), clockRanges_.size());

    // Fallback: if adaptive match failed, use old hardcoded logic
    // BUT only if the sample rate is within a supported clock range
    if (targetAlt < 0) {
        bool rateSupported = false;
        for (auto& r : clockRanges_) {
            if (sampleRate_ >= r.min && sampleRate_ <= r.max) { rateSupported = true; break; }
        }
        if (!rateSupported) {
            LOGE("start: %d Hz outside all clock ranges — refuse to force. Use ExoPlayer/Oboe instead.", sampleRate_);
            latchStartFailure("no supported rate / no alt candidates");
            return false;
        }
        LOGW("start: adaptive match failed, falling back to hardcoded alt");
        targetAlt = (bitsPerSample_ == 24) ? 2 : (bitsPerSample_ == 32) ? 3 : 1;
        if (sampleRate_ != clockRate_) {
            int scRet = trySetSampleRate(sampleRate_);
            if (scRet >= 0) clockRate_ = sampleRate_;
            else LOGW("start: fallback SET_CUR(%d) failed", sampleRate_);
        }
        // Fallback: update maxPacketSize_/bytesPerFrame_ from candidates if available
        if (!altCandidates_.empty()) {
            for (auto& c : altCandidates_) {
                if (c.alt == targetAlt) {
                    maxPacketSize_ = c.maxPkt;
                    interval_ = c.bInterval;
                    // Prefer actual bit depth over candidate subslot (which may default to 2)
                    int subslotFromBits = (bitsPerSample_ + 7) / 8;
                    bytesPerFrame_ = c.channels * subslotFromBits;
                    LOGI("start: fallback: mps=%d bpf=%d (alt=%d, bits=%d)", maxPacketSize_, bytesPerFrame_, targetAlt, bitsPerSample_);
                    break;
                }
            }
        }
    }

    if (sampleRate_ != clockRate_ || targetAlt != currentAlt_) {
        // [2026-09-09 v2] Same-alt restart (endpoint still active after keep-claim
        // stop) must NOT tear down the endpoint: Qudelix ES9219 loses lock on
        // teardown+reactivate -> noise at song start. Only teardown when the alt
        // really changes; same-alt just re-runs SET_CUR to re-lock the clock.
        bool altChanged = (targetAlt != currentAlt_) || forceIfaceReset_;
        if (altChanged) {
            setInterfaceAlt(0);
            if (ifaceDelayUs_ > 0) usleep(ifaceDelayUs_);  // [quirk] settle after teardown
        }
        int scRet = clockRate_;
        if (sampleRate_ != clockRate_) {
            // [v6.0.14] Skip SET_CUR for known buggy DACs; rely on alt-switch implicit lock.
            // NOTE: Realtek 4BA6 is NOT buggy — Salt verified it accepts SET_CUR
            // (setCurAttempts=[clock=30@0/ret=4/current=44100]). Skipping SET_CUR left
            // its clock stuck at 48k, so 44.1k tracks played back fast/warped.
            // [2026-08-24] Salt also verified BTR5 (2972:0047) sends SET_CUR to clock=41
            // (ret=4) and TTGK Note (3302:201D) sends SET_CUR to clock=6 (ret=4). Both
            // were wrongly listed here as skipSetCur. Only HIFI_A001 (2D13:A001) truly
            // breaks on SET_CUR (locks clock at 384k).
            bool buggyDac = (vid_ == 0x2D13 && pid_ == 0xA001) || skipSetCurQuirk_;
            scRet = buggyDac ? sampleRate_ : trySetSampleRate(sampleRate_);
            if (buggyDac) { LOGI("start: skipping SET_CUR for pid=%04X (implicit alt-switch lock)", pid_); clockRate_ = sampleRate_; }
            else if (scRet >= 0) clockRate_ = sampleRate_;
            else LOGW("start: SET_CUR(%d) failed, clock stays at %d", sampleRate_, clockRate_);
        }
        bool altOk = true;
        if (altChanged) {
            if (ifaceDelayUs_ > 0) usleep(ifaceDelayUs_);  // [quirk] settle before reactivate
            altOk = setInterfaceAlt(targetAlt);
            if (!altOk) {
                // Retry once on EBUSY: give the kernel a window to reap URBs.
                usleep(ifaceDelayUs_ > 0 ? ifaceDelayUs_ : 20000);
                altOk = setInterfaceAlt(targetAlt);
            }
        }
        if (altOk) {
            currentAlt_ = targetAlt;
            // 【Qudelix-5K 0A12:4007】UAC1 设备必须在 alt=1 激活（ISO 端点可用）后才能 SET_CUR 采样率：
            // 之前先 SET_CUR 再 setInterfaceAlt，Qudelix 返回 EPIPE(errno=32)，时钟停在 0，
            // 96k 数据按 48k 播放（或无声），Kotlin 反复 start 重试耗尽 URB → submitUrbRaw ENOMEM。
            // UAC2 设备（BTR5/TTGK）仍需先 SET_CUR 再 alt，此处仅对 UAC1 后置。
            if (!isUac2_ && sampleRate_ != clockRate_) {
                int scRet2 = trySetSampleRate(sampleRate_);
                if (scRet2 >= 0) { clockRate_ = sampleRate_; scRet = scRet2; }
                else LOGE("start: UAC1 post-alt SET_CUR(%d) failed again: %s (errno=%d)",
                          sampleRate_, strerror(errno), errno);
            }
            // 浠庨€変腑鐨勫€欓€夋洿鏂板疄闄呭弬锟?
            for (auto& c : altCandidates_) {
                if (c.alt == targetAlt) {
                    maxPacketSize_ = c.maxPkt;
                    bytesPerFrame_ = c.channels * ((bitsPerSample_ + 7) / 8);  // Use actual stream bits, not DAC max subslot
                    interval_ = c.bInterval;
                    break;
                }
            }
        }
        else LOGE("start: setInterfaceAlt(%d) FAILED twice - DAC stuck at alt=%d, ABORT", targetAlt, currentAlt_);
        LOGI("start: switch clock->%d (ret=%d) alt->%d ok=%d (bits=%d mps=%d bpf=%d)",
             clockRate_, scRet, targetAlt, altOk ? 1 : 0, bitsPerSample_, maxPacketSize_, bytesPerFrame_);
        if (!altOk) { latchStartFailure("alt switch failed twice"); return false; }
    }

    // 銆愯瘖鏂€戣锟?DAC 鐪熷疄鐘舵€侊細SETINTERFACE 杩斿洖鎴愬姛 锟?璁惧鐪熷垏杩囧幓
    {
        uint8_t curAlt = 255;
        struct usbdevfs_ctrltransfer ct = {};
        ct.bRequestType = 0x81; ct.bRequest = 0x0A; // GET_INTERFACE
        ct.wValue = 0; ct.wIndex = (uint16_t)ifaceNum_;
        ct.wLength = 1; ct.timeout = 1000; ct.data = &curAlt;
        int r1 = ioctl(fd_, USBDEVFS_CONTROL, &ct);
        uint32_t curRate = 0;
        struct usbdevfs_ctrltransfer ct2 = {};
        ct2.bRequestType = 0xA1; ct2.bRequest = 0x01; // CUR
        ct2.wValue = 0x0100;  // CS_SAM_FREQ_CONTROL
        ct2.wIndex = (uint16_t)(((clockSourceId_ > 0 ? clockSourceId_ : 9) << 8) | (acIface_ & 0xFF));
        ct2.wLength = 4; ct2.timeout = 1000; ct2.data = &curRate;
        int r2 = ioctl(fd_, USBDEVFS_CONTROL, &ct2);
        LOGI("start: VERIFY GET_INTERFACE=%d (ret=%d) GET_CUR rate=%u (ret=%d) [want alt=%d rate=%d]",
             curAlt, r1, curRate, r2, currentAlt_, sampleRate_);
    }

    // [P6 fix] Activate the feedback endpoint's interface (when separate from playback)
    // BEFORE the stream thread submits the feedback URB. Realtek 0BDA:4BA6: feedback
    // 0x81 on iface 1 alt 1 vs playback 0x07 on iface 2 alt 3 — the Java side only
    // claims the OUT streaming iface, so the feedback iface stays at alt 0 (endpoint
    // inactive) and SUBMITURB fails with EBUSY (errno=16) -> silent open-loop.
    feedbackLogCount_ = 0;   // re-log the first reads of each stream (diagnostics)
    feedbackBadCount_ = 0;
    breakMuted_ = false; breakMutePermanent_ = false;   // [safety] re-evaluate break-mute fresh each stream
    activateFeedbackInterface();

    // [V3.3.4] Do NOT reset ring positions here: Kotlin prebuffers ~500ms into the
    // ring BEFORE calling start(); zeroing writePos_/readPos_ discarded that prebuffer
    // (silent gap + first ~500ms of every song lost). Callers (play/resume/seek paths)
    // call resetRingBuffer() explicitly before pushing fresh data.
    underrunCount_.store(0, std::memory_order_release);

    // [2026-09-10 soft-start] Arm the fade-in ramp for this stream start. No-op (0 frames)
    // when the user has not enabled it -> bit-perfect path is completely untouched.
    softStartFrameBase_   = 0;
    softStartTotalFrames_ = softStartEnabled_.load(std::memory_order_relaxed)
        ? (int)((int64_t)sampleRate_ * kSoftStartFadeMs / 1000) : 0;
    if (softStartTotalFrames_ > 0) {
        LOGI("start: soft-start armed %dms (%d frames @%dHz)",
             kSoftStartFadeMs, softStartTotalFrames_, sampleRate_);
    }

    // [safety] Always arm the stall-recovery fade (independent of the soft-start user toggle).
    // A transient decode stall drains the ring; on resume the native side would step from
    // silence straight to full-scale audio = the "explosion" pop. This ramps that step over
    // kRecoverFadeMs so recovery is a gentle fade, never a discontinuity.
    recoverTotal_      = (int)((int64_t)sampleRate_ * kRecoverFadeMs / 1000);
    recoverFramesLeft_ = 0;
    recoverGain_       = 1.0f;
    inUnderrun_        = false;
    // [safety-fix 2026-10-22] Every stream now STARTS SILENT and fades the mute gain in
    // over kStartupMuteMs. This is independent of the hearing-protection break-mute: it exists
    // to swallow any garbage the decoder/resampler can emit in the FIRST block of a song
    // (observed: full-scale white noise when the sample rate changes between tracks — see
    // oboe_bridge.cpp:30215 "首块产出垃圾出白噪"). Previously the stream began fully audible,
    // so a glitched first block blasted straight to the DAC at full scale. Starting muted means
    // the first ~150ms can never be loud, then it fades in cleanly. A break-mute during this
    // window simply overrides the ramp (safety takes priority) and releases back to audible.
    muteTotal_      = (sampleRate_ > 0) ? std::max(1, (int)((int64_t)sampleRate_ * kStartupMuteMs / 1000)) : 192;
    muteFramesLeft_ = muteTotal_;
    muteTarget_     = 1.0f;   // ramp the mute gain UP to audible over the startup window
    muteGain_       = 0.0f;   // [fix] begin silent — was 1.0f (audible from frame 0)

    // Start stream thread
    streamStarted_.store(false, std::memory_order_release);
    streaming_.store(true, std::memory_order_release);
    // 【V9.02 修复】std::thread 构造在 pthread_create 资源紧张 / 旧线程未完全回收时可能抛
    // std::system_error（前期走 alt 切换的重启路径尤其易触发）；若 move-assign 时
    // streamThread_ 仍 joinable 也会触发 std::terminate。这里兜底：先确保非 joinable，
    // 再 try 创建；失败则回退 streaming_ 标志并返回 false，绝不让异常冒泡整进程崩溃。
    if (streamThread_.joinable()) {
        try { streamThread_.join(); } catch (...) {}
    }
    try {
        streamThread_ = std::thread(streamThreadEntry, this);
    } catch (...) {
        streaming_.store(false, std::memory_order_release);
        LOGE("start: std::thread spawn FAILED (sr=%d ch=%d bits=%d) -> return false",
             sampleRate_, channels_, bitsPerSample_);
        return false;
    }

    // [D] Report the REAL stream state. The stream thread brings the isochronous pipe
    // up by pre-queuing URBs; once all are accepted it sets streamStarted_. If it
    // aborts (e.g. submitUrbRaw ENOENT because the endpoint never activated), start()
    // must return false so Kotlin never believes playback is live while frozen.
    constexpr int kStartPollMs = 800;
    for (int i = 0; i < kStartPollMs / 20 && !streamStarted_.load(std::memory_order_acquire); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
    if (!streamStarted_.load(std::memory_order_acquire)) {
        LOGE("start: stream did not come up within %dms -> return false", kStartPollMs);
        latchStartFailure("stream did not come up");
        streaming_.store(false, std::memory_order_release);
        return false;
    }
    startFailureUntilMs_ = 0;   // [Stage0] real start succeeded -> clear any stale latch
    return true;
}

// 鈹€鈹€ stop 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

void UsbAudioDriver::stop() {
    breakMuted_ = false; breakMutePermanent_ = false;   // [safety] drop any hearing-protection mute
    stopThreadOnly(true);   // [keep-alive] really done with the device -> full teardown
    releaseInterface();
    LOGI("stop: done (interface released)");
}

void UsbAudioDriver::stopThreadOnly(bool forceRealTeardown) {
    // [keep-alive 2026-10-23] A plain stop request is the Kotlin track-switch path: do NOT
    // tear the stream down, or URB submission stops, the DAC-side sample clock drops and the
    // ISO endpoint goes idle -> the DAC free-runs -> seconds of full-scale noise at the next
    // song start. Keep the thread alive and keep clocking the endpoint with silent URBs for
    // up to kIdleGraceMs instead. start() returning with identical params inside the window
    // takes the same-rate keep-stream path (no restart, no gap); if nothing comes back we
    // fall through to a real teardown below.
    if (!forceRealTeardown && fd_ >= 0 && sampleRate_ > 0 &&
        streaming_.load(std::memory_order_acquire) &&
        !deviceVanished_.load(std::memory_order_acquire)) {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        idleDeadlineMs_ = (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000 + kIdleGraceMs;
        idleMode_.store(true, std::memory_order_release);
        LOGI("stopThreadOnly: idle-keepalive armed (%dms) - ISO pipe stays clocked, DAC keeps lock", kIdleGraceMs);
        return;
    }
    idleMode_.store(false, std::memory_order_release);
    streaming_.store(false, std::memory_order_release);

    // 【修复】无条件 DISCARD 所有 in-flight URB。
    // 旧判断 `u->status == 0` 是错的：in-flight URB 的 status 是 -EINPROGRESS(-115),
    // 不是 0,导致一个 URB 都没 discard,旧 stream 线程卡在 REAPURB 上永远停不掉。
    if (fd_ >= 0) {
        for (int i = 0; i < kMaxUrbCount; ++i) {
            struct usbdevfs_urb* u = urbSlots_[i].urb;
            if (u) {
                ioctl(fd_, USBDEVFS_DISCARDURB, u);  // EINVAL/ENODEV 无害,忽略
            }
        }
        if (fbUrb_) {
            ioctl(fd_, USBDEVFS_DISCARDURB, fbUrb_);  // [P6] discard feedback URB too
        }
    }

    // 【修复】同步 join 替换异步 detach。DISCARDURB 后 REAPURB 会立即返回
    // (被 discard 的 URB 进 completion queue),stream 线程在 streaming_==false
    // 时退出 while 循环。旧 detach 方案会泄漏线程,与新线程并发访问
    // urbSlots_/ringBuffer_/fd_ 导致数据竞争 → 声音不干净。
    // 若设备拔出导致 REAPURB 永久阻塞,由看门狗 forceReset()(close fd) 兜底。
    if (streamThread_.joinable()) {
        streamThread_.join();
        LOGI("stopThreadOnly: streamThread joined cleanly");
    }
    feedbackActive_.store(false, std::memory_order_release);
    // [2026-09-09 Qudelix-5K fix v2] Stream stop drops the DAC-side sample clock,
    // but the endpoint alt setting SURVIVES (setInterfaceAlt state persists after
    // stream stop). v1 cleared currentAlt_ too, forcing setInterfaceAlt(0)+reactivate
    // on every track switch -> Qudelix ES9219 loses lock -> 3-5s noise/static at
    // song start. v2 only forgets clockRate_ (forces SET_CUR re-lock) and retains
    // currentAlt_: same-alt restart skips endpoint teardown, no unlock glitch.
    clockRate_ = 0;
    LOGI("stopThreadOnly: threads stopped, USB claim kept (clock forgotten, alt=%d retained)", currentAlt_);
}

void UsbAudioDriver::resetRingBuffer() {
    // 銆怴3.2.7銆戞殏鍋滃悗鎭㈠鏃跺繀椤绘竻 ring buffer銆俿topThreadOnly 鍙仠浜嗙嚎绋嬶紝
    // ring 鏁版嵁杩樺湪锛寃ritePos_/readPos_ 涓嶅榻愩€傜洿鎺ュ紑娴佷細瀵艰嚧鏂版棫鏁版嵁浜掔浉韪╄笍锟?
    // 宸﹀０閬撳櫔锟?/ 閿欎贡锟?
    // 銆怴3.3.21 淇銆戜笉浠呰閲嶇疆鎸囬拡锛岃繕瑕佹竻闆跺疄闄呮暟鎹€傚惁鍒欐棫姝屾畫锟?PCM 琚€佸幓 DAC锟?
    if (ringBuffer_) {
        memset(ringBuffer_, 0, kRingFrames * 2 * sizeof(float));
    }
    writePos_.store(0, std::memory_order_release);
    readPos_.store(0, std::memory_order_release);
    LOGI("resetRingBuffer: cleared (memset %d frames)", kRingFrames);
}

// ============================================================================
// DAC-path 5-band EQ (MSEB / graphic EQ) — same Biquad algo & coefficients as Oboe.
// ============================================================================
static const float kDacEq5BandDefaultFreqs[5] = {60.0f, 230.0f, 910.0f, 3600.0f, 14000.0f};

void UsbAudioDriver::setDspEq5Band(const float* gainsDb, const float* freqsHz, int len) {
    if (!gainsDb || len <= 0) return;
    std::lock_guard<std::mutex> eqLock(dspEqMutex_);
    float sr = static_cast<float>(sampleRate_ > 0 ? sampleRate_ : 48000);

    BiquadFilter* bandsL[5] = { &dspEqBand1L_, &dspEqBand2L_, &dspEqBand3L_, &dspEqBand4L_, &dspEqBand5L_ };
    BiquadFilter* bandsR[5] = { &dspEqBand1R_, &dspEqBand2R_, &dspEqBand3R_, &dspEqBand4R_, &dspEqBand5R_ };

    for (int i = 0; i < 5; i++) {
        float gainDb = (i < len) ? gainsDb[i] : 0.0f;
        float freqHz = (freqsHz && i < len) ? freqsHz[i] : kDacEq5BandDefaultFreqs[i];
        float Q = 1.4f;
        // 【V8.2】no reset() on live gain change — process() SMOOTH ramp handles the
        // transition; clearing IIR state causes a click (哗哗声 on slider drag).
        if (fabsf(gainDb) < 0.1f) {
            bandsL[i]->setFlat(); bandsR[i]->setFlat();
        } else {
            bandsL[i]->setPeaking(sr, freqHz, gainDb, Q);
            bandsR[i]->setPeaking(sr, freqHz, gainDb, Q);
        }
    }

    float maxGain = 0.0f;
    for (int i = 0; i < len && i < 5; i++) if (gainsDb[i] > maxGain) maxGain = gainsDb[i];
    dspEqPreGain_ = powf(10.0f, (-maxGain) / 20.0f);

    dspEqEnabled_.store(true, std::memory_order_release);
    LOGI("DAC EQ 5-band applied: [%.1f %.1f %.1f %.1f %.1f] dB preGain=%.3f",
         (len > 0 ? gainsDb[0] : 0.0f), (len > 1 ? gainsDb[1] : 0.0f),
         (len > 2 ? gainsDb[2] : 0.0f), (len > 3 ? gainsDb[3] : 0.0f),
         (len > 4 ? gainsDb[4] : 0.0f), dspEqPreGain_);
}

void UsbAudioDriver::resetDspEq5Band() {
    std::lock_guard<std::mutex> eqLock(dspEqMutex_);
    BiquadFilter* bandsL[5] = { &dspEqBand1L_, &dspEqBand2L_, &dspEqBand3L_, &dspEqBand4L_, &dspEqBand5L_ };
    BiquadFilter* bandsR[5] = { &dspEqBand1R_, &dspEqBand2R_, &dspEqBand3R_, &dspEqBand4R_, &dspEqBand5R_ };
    for (int i = 0; i < 5; i++) { bandsL[i]->setFlat(); bandsR[i]->setFlat(); }
    dspEqPreGain_ = 1.0f;
    dspEqEnabled_.store(false, std::memory_order_release);
    LOGI("DAC EQ 5-band reset (flat)");
}

// 【V8.2】MSEB 10-band subjective EQ — independent instances, crossfade on coeff change.
void UsbAudioDriver::setMseb10Band(const float* gainsDb, const float* freqsHz, const float* qs, int len) {
    if (!gainsDb || len <= 0) return;
    std::lock_guard<std::mutex> eqLock(dspEqMutex_);
    float sr = static_cast<float>(sampleRate_ > 0 ? sampleRate_ : 48000);

    BiquadFilter* bandsL[10] = { &msebBand1L_, &msebBand2L_, &msebBand3L_, &msebBand4L_, &msebBand5L_,
                                 &msebBand6L_, &msebBand7L_, &msebBand8L_, &msebBand9L_, &msebBand10L_ };
    BiquadFilter* bandsR[10] = { &msebBand1R_, &msebBand2R_, &msebBand3R_, &msebBand4R_, &msebBand5R_,
                                 &msebBand6R_, &msebBand7R_, &msebBand8R_, &msebBand9R_, &msebBand10R_ };

    for (int i = 0; i < 10; i++) {
        float gainDb = (i < len) ? gainsDb[i] : 0.0f;
        float freqHz = (freqsHz && i < len && freqsHz[i] > 0.0f) ? freqsHz[i] : 1000.0f;
        float Q = (qs && i < len && qs[i] > 0.0f) ? qs[i] : 1.0f;
        // 【V8.3】只在增益真正变化时更新 + crossfade；未变 band 跳过。
        if (fabsf(gainDb - lastMsebGains_[i]) < 0.01f) continue;
        lastMsebGains_[i] = gainDb;
        if (fabsf(gainDb) < 0.1f) {
            bandsL[i]->setFlat(); bandsR[i]->setFlat();
        } else if (freqHz < 250.0f) {
            // 【V8.3 ringing 根治】低频段用 low-shelf（无共振峰、无 ringing）。
            bandsL[i]->setLowShelf(sr, freqHz, gainDb, 0.707f);
            bandsR[i]->setLowShelf(sr, freqHz, gainDb, 0.707f);
        } else {
            bandsL[i]->setPeaking(sr, freqHz, gainDb, Q);
            bandsR[i]->setPeaking(sr, freqHz, gainDb, Q);
        }
    }

    // 【V8.3 preGain 方案 B】只补低频 3 段（<250Hz）正增益总和（与 Oboe 侧一致）。
    // 低频 low-shelf 在 30Hz 以下叠加会级联削波，按“和”补偿；中高频 peaking 频点分离
    // +2~3dB 几乎不削波，交给 limiter 兔底，避免“开 MSEB 变轻”。
    float sumPosGain = 0.0f;
    for (int i = 0; i < len && i < 3; i++) if (gainsDb[i] > 0.0f) sumPosGain += gainsDb[i];
    msebPreGain_ = powf(10.0f, (-sumPosGain) / 20.0f);

    mseb10Enabled_.store(true, std::memory_order_release);
    LOGI("DAC MSEB 10-band applied: [%.1f %.1f %.1f %.1f %.1f %.1f %.1f %.1f %.1f %.1f] dB preGain=%.3f",
         (len > 0 ? gainsDb[0] : 0.0f), (len > 1 ? gainsDb[1] : 0.0f),
         (len > 2 ? gainsDb[2] : 0.0f), (len > 3 ? gainsDb[3] : 0.0f),
         (len > 4 ? gainsDb[4] : 0.0f), (len > 5 ? gainsDb[5] : 0.0f),
         (len > 6 ? gainsDb[6] : 0.0f), (len > 7 ? gainsDb[7] : 0.0f),
         (len > 8 ? gainsDb[8] : 0.0f), (len > 9 ? gainsDb[9] : 0.0f), msebPreGain_);
}

void UsbAudioDriver::resetMseb10Band() {
    std::lock_guard<std::mutex> eqLock(dspEqMutex_);
    BiquadFilter* bandsL[10] = { &msebBand1L_, &msebBand2L_, &msebBand3L_, &msebBand4L_, &msebBand5L_,
                                 &msebBand6L_, &msebBand7L_, &msebBand8L_, &msebBand9L_, &msebBand10L_ };
    BiquadFilter* bandsR[10] = { &msebBand1R_, &msebBand2R_, &msebBand3R_, &msebBand4R_, &msebBand5R_,
                                 &msebBand6R_, &msebBand7R_, &msebBand8R_, &msebBand9R_, &msebBand10R_ };
    for (int i = 0; i < 10; i++) { bandsL[i]->reset(); bandsR[i]->reset(); bandsL[i]->setFlat(); bandsR[i]->setFlat(); }
    for (int i = 0; i < 10; i++) lastMsebGains_[i] = 999.0f;  // 强制下次重新应用
    mseb10Enabled_.store(false, std::memory_order_release);
    msebPreGain_ = 1.0f;
    LOGI("DAC MSEB 10-band reset (flat)");
}

// 【V8.3】AutoEQ 10-band 耳机修正 — 任意频点 + PK/HS/LS 类型，与 MSEB 并存叠加（打底）。
void UsbAudioDriver::setAutoEq10Band(const float* gainsDb, const float* freqsHz, const float* qs,
                                     const int* types, int len, float preampDb) {
    if (!gainsDb || len <= 0) return;
    std::lock_guard<std::mutex> eqLock(dspEqMutex_);
    float sr = static_cast<float>(sampleRate_ > 0 ? sampleRate_ : 48000);

    BiquadFilter* bandsL[64] = { &autoEqBand1L_, &autoEqBand2L_, &autoEqBand3L_, &autoEqBand4L_, &autoEqBand5L_,
                                 &autoEqBand6L_, &autoEqBand7L_, &autoEqBand8L_, &autoEqBand9L_, &autoEqBand10L_,
                                 &autoEqBand11L_, &autoEqBand12L_, &autoEqBand13L_, &autoEqBand14L_, &autoEqBand15L_,
                                 &autoEqBand16L_, &autoEqBand17L_, &autoEqBand18L_, &autoEqBand19L_, &autoEqBand20L_,
                                 &autoEqBand21L_, &autoEqBand22L_, &autoEqBand23L_, &autoEqBand24L_, &autoEqBand25L_,
                                 &autoEqBand26L_, &autoEqBand27L_, &autoEqBand28L_, &autoEqBand29L_, &autoEqBand30L_,
                                 &autoEqBand31L_,
                                 &autoEqBand32L_,
                                 &autoEqBand33L_,
                                 &autoEqBand34L_,
                                 &autoEqBand35L_,
                                 &autoEqBand36L_,
                                 &autoEqBand37L_,
                                 &autoEqBand38L_,
                                 &autoEqBand39L_,
                                 &autoEqBand40L_,
                                 &autoEqBand41L_,
                                 &autoEqBand42L_,
                                 &autoEqBand43L_,
                                 &autoEqBand44L_,
                                 &autoEqBand45L_,
                                 &autoEqBand46L_,
                                 &autoEqBand47L_,
                                 &autoEqBand48L_,
                                 &autoEqBand49L_,
                                 &autoEqBand50L_,
                                 &autoEqBand51L_,
                                 &autoEqBand52L_,
                                 &autoEqBand53L_,
                                 &autoEqBand54L_,
                                 &autoEqBand55L_,
                                 &autoEqBand56L_,
                                 &autoEqBand57L_,
                                 &autoEqBand58L_,
                                 &autoEqBand59L_,
                                 &autoEqBand60L_,
                                 &autoEqBand61L_,
                                 &autoEqBand62L_,
                                 &autoEqBand63L_,
                                 &autoEqBand64L_,
                                 };
    BiquadFilter* bandsR[64] = { &autoEqBand1R_, &autoEqBand2R_, &autoEqBand3R_, &autoEqBand4R_, &autoEqBand5R_,
                                 &autoEqBand6R_, &autoEqBand7R_, &autoEqBand8R_, &autoEqBand9R_, &autoEqBand10R_,
                                 &autoEqBand11R_, &autoEqBand12R_, &autoEqBand13R_, &autoEqBand14R_, &autoEqBand15R_,
                                 &autoEqBand16R_, &autoEqBand17R_, &autoEqBand18R_, &autoEqBand19R_, &autoEqBand20R_,
                                 &autoEqBand21R_, &autoEqBand22R_, &autoEqBand23R_, &autoEqBand24R_, &autoEqBand25R_,
                                 &autoEqBand26R_, &autoEqBand27R_, &autoEqBand28R_, &autoEqBand29R_, &autoEqBand30R_,
                                 &autoEqBand31R_,
                                 &autoEqBand32R_,
                                 &autoEqBand33R_,
                                 &autoEqBand34R_,
                                 &autoEqBand35R_,
                                 &autoEqBand36R_,
                                 &autoEqBand37R_,
                                 &autoEqBand38R_,
                                 &autoEqBand39R_,
                                 &autoEqBand40R_,
                                 &autoEqBand41R_,
                                 &autoEqBand42R_,
                                 &autoEqBand43R_,
                                 &autoEqBand44R_,
                                 &autoEqBand45R_,
                                 &autoEqBand46R_,
                                 &autoEqBand47R_,
                                 &autoEqBand48R_,
                                 &autoEqBand49R_,
                                 &autoEqBand50R_,
                                 &autoEqBand51R_,
                                 &autoEqBand52R_,
                                 &autoEqBand53R_,
                                 &autoEqBand54R_,
                                 &autoEqBand55R_,
                                 &autoEqBand56R_,
                                 &autoEqBand57R_,
                                 &autoEqBand58R_,
                                 &autoEqBand59R_,
                                 &autoEqBand60R_,
                                 &autoEqBand61R_,
                                 &autoEqBand62R_,
                                 &autoEqBand63R_,
                                 &autoEqBand64R_,
                                 };

    for (int i = 0; i < 64; i++) {
        float gainDb = (i < len) ? gainsDb[i] : 0.0f;
        float freqHz = (freqsHz && i < len && freqsHz[i] > 0.0f) ? freqsHz[i] : 1000.0f;
        float Q = (qs && i < len && qs[i] > 0.0f) ? qs[i] : 1.0f;
        // 注意：AutoEQ 是任意频点，不能像 MSEB 那样只按增益去重（同增益不同频率会被误跳）。
        // AutoEQ 切换预设是低频操作（非实时拖滑块），直接全量设置，与 Oboe 侧一致。
        if (fabsf(gainDb) < 0.01f) {
            bandsL[i]->setFlat(); bandsR[i]->setFlat();
            continue;
        }
        int type = (types && i < len) ? types[i] : 0;
        if (type == 1) {  // HighShelf
            bandsL[i]->setHighShelf(sr, freqHz, gainDb, Q);
            bandsR[i]->setHighShelf(sr, freqHz, gainDb, Q);
        } else if (type == 2) {  // LowShelf
            bandsL[i]->setLowShelf(sr, freqHz, gainDb, Q);
            bandsR[i]->setLowShelf(sr, freqHz, gainDb, Q);
        } else {  // Peaking
            if (freqHz < 250.0f) {
                // 【V8.3 ringing 根治】低频 Peaking 切 low-shelf（无共振峰、无 ringing）。
                bandsL[i]->setLowShelf(sr, freqHz, gainDb, 0.707f);
                bandsR[i]->setLowShelf(sr, freqHz, gainDb, 0.707f);
            } else {
                bandsL[i]->setPeaking(sr, freqHz, gainDb, Q);
                bandsR[i]->setPeaking(sr, freqHz, gainDb, Q);
            }
        }
    }

    autoEqPreGain_ = powf(10.0f, preampDb / 20.0f);
    autoEqEnabled_.store(true, std::memory_order_release);
    LOGI("DAC AutoEQ 64-band applied: preamp=%.1fdB", preampDb);
}

void UsbAudioDriver::resetAutoEq() {
    std::lock_guard<std::mutex> eqLock(dspEqMutex_);
    BiquadFilter* bandsL[64] = { &autoEqBand1L_, &autoEqBand2L_, &autoEqBand3L_, &autoEqBand4L_, &autoEqBand5L_,
                                 &autoEqBand6L_, &autoEqBand7L_, &autoEqBand8L_, &autoEqBand9L_, &autoEqBand10L_,
                                 &autoEqBand11L_, &autoEqBand12L_, &autoEqBand13L_, &autoEqBand14L_, &autoEqBand15L_,
                                 &autoEqBand16L_, &autoEqBand17L_, &autoEqBand18L_, &autoEqBand19L_, &autoEqBand20L_,
                                 &autoEqBand21L_, &autoEqBand22L_, &autoEqBand23L_, &autoEqBand24L_, &autoEqBand25L_,
                                 &autoEqBand26L_, &autoEqBand27L_, &autoEqBand28L_, &autoEqBand29L_, &autoEqBand30L_,
                                 &autoEqBand31L_,
                                 &autoEqBand32L_,
                                 &autoEqBand33L_,
                                 &autoEqBand34L_,
                                 &autoEqBand35L_,
                                 &autoEqBand36L_,
                                 &autoEqBand37L_,
                                 &autoEqBand38L_,
                                 &autoEqBand39L_,
                                 &autoEqBand40L_,
                                 &autoEqBand41L_,
                                 &autoEqBand42L_,
                                 &autoEqBand43L_,
                                 &autoEqBand44L_,
                                 &autoEqBand45L_,
                                 &autoEqBand46L_,
                                 &autoEqBand47L_,
                                 &autoEqBand48L_,
                                 &autoEqBand49L_,
                                 &autoEqBand50L_,
                                 &autoEqBand51L_,
                                 &autoEqBand52L_,
                                 &autoEqBand53L_,
                                 &autoEqBand54L_,
                                 &autoEqBand55L_,
                                 &autoEqBand56L_,
                                 &autoEqBand57L_,
                                 &autoEqBand58L_,
                                 &autoEqBand59L_,
                                 &autoEqBand60L_,
                                 &autoEqBand61L_,
                                 &autoEqBand62L_,
                                 &autoEqBand63L_,
                                 &autoEqBand64L_,
                                 };
    BiquadFilter* bandsR[64] = { &autoEqBand1R_, &autoEqBand2R_, &autoEqBand3R_, &autoEqBand4R_, &autoEqBand5R_,
                                 &autoEqBand6R_, &autoEqBand7R_, &autoEqBand8R_, &autoEqBand9R_, &autoEqBand10R_,
                                 &autoEqBand11R_, &autoEqBand12R_, &autoEqBand13R_, &autoEqBand14R_, &autoEqBand15R_,
                                 &autoEqBand16R_, &autoEqBand17R_, &autoEqBand18R_, &autoEqBand19R_, &autoEqBand20R_,
                                 &autoEqBand21R_, &autoEqBand22R_, &autoEqBand23R_, &autoEqBand24R_, &autoEqBand25R_,
                                 &autoEqBand26R_, &autoEqBand27R_, &autoEqBand28R_, &autoEqBand29R_, &autoEqBand30R_,
                                 &autoEqBand31R_,
                                 &autoEqBand32R_,
                                 &autoEqBand33R_,
                                 &autoEqBand34R_,
                                 &autoEqBand35R_,
                                 &autoEqBand36R_,
                                 &autoEqBand37R_,
                                 &autoEqBand38R_,
                                 &autoEqBand39R_,
                                 &autoEqBand40R_,
                                 &autoEqBand41R_,
                                 &autoEqBand42R_,
                                 &autoEqBand43R_,
                                 &autoEqBand44R_,
                                 &autoEqBand45R_,
                                 &autoEqBand46R_,
                                 &autoEqBand47R_,
                                 &autoEqBand48R_,
                                 &autoEqBand49R_,
                                 &autoEqBand50R_,
                                 &autoEqBand51R_,
                                 &autoEqBand52R_,
                                 &autoEqBand53R_,
                                 &autoEqBand54R_,
                                 &autoEqBand55R_,
                                 &autoEqBand56R_,
                                 &autoEqBand57R_,
                                 &autoEqBand58R_,
                                 &autoEqBand59R_,
                                 &autoEqBand60R_,
                                 &autoEqBand61R_,
                                 &autoEqBand62R_,
                                 &autoEqBand63R_,
                                 &autoEqBand64R_,
                                 };
    for (int i = 0; i < 64; i++) { bandsL[i]->reset(); bandsR[i]->reset(); bandsL[i]->setFlat(); bandsR[i]->setFlat(); }
    for (int i = 0; i < 64; i++) lastAutoEqGains_[i] = 999.0f;  // 强制下次重新应用
    autoEqEnabled_.store(false, std::memory_order_release);
    autoEqPreGain_ = 1.0f;
    LOGI("DAC AutoEQ 64-band reset (flat)");
}

// 【V8.3】M/S 声场 — 跨声道矩阵（soundstage -> S 宽度，imaging -> M 中心）。
void UsbAudioDriver::setMsStage(float soundstage, float imaging) {
    float width = 1.0f + soundstage * 0.08f;
    float center = 1.0f + imaging * 0.06f;
    if (width < 0.1f) width = 0.1f;
    if (center < 0.1f) center = 0.1f;
    msWidth_.store(width, std::memory_order_release);
    msCenter_.store(center, std::memory_order_release);
    bool active = (fabsf(soundstage) > 0.01f || fabsf(imaging) > 0.01f);
    msEnabled_.store(active, std::memory_order_release);
    LOGI("DAC M/S stage: width=%.3f center=%.3f enabled=%s", width, center, active ? "YES" : "NO");
}

void UsbAudioDriver::resetMsStage() {
    msWidth_.store(1.0f, std::memory_order_release);
    msCenter_.store(1.0f, std::memory_order_release);
    msEnabled_.store(false, std::memory_order_release);
    LOGI("DAC M/S stage reset (unity)");
}

// 【V8.3】瞬态整形——impulseResponse 维度映射（amount=-1..+1）。
void UsbAudioDriver::setTransient(float amount) {
    float a = amount;
    if (a > 1.0f) a = 1.0f;
    if (a < -1.0f) a = -1.0f;
    transientAmount_.store(a, std::memory_order_release);
    bool active = fabsf(a) > 0.001f;
    transientEnabled_.store(active, std::memory_order_release);
    if (!active) { tsFastEnvL_ = tsSlowEnvL_ = tsFastEnvR_ = tsSlowEnvR_ = 0.0f; }
    LOGI("DAC transient shaper: amount=%.3f enabled=%s", a, active ? "YES" : "NO");
}

void UsbAudioDriver::resetTransient() {
    transientAmount_.store(0.0f, std::memory_order_release);
    transientEnabled_.store(false, std::memory_order_release);
    tsFastEnvL_ = tsSlowEnvL_ = tsFastEnvR_ = tsSlowEnvR_ = 0.0f;
    LOGI("DAC transient shaper reset");
}

// 【V8.3】动态压缩——master bus 向下压缩器（stereo-linked，dB 域峰值检测 + 软拐点）。
void UsbAudioDriver::setCompressorEnabled(bool en) {
    compressorEnabled_.store(en, std::memory_order_release);
    if (!en) resetCompressor();
    LOGI("DAC compressor %s", en ? "enabled" : "disabled");
}

void UsbAudioDriver::setCompressorParams(float thresholdDb, float ratio, float attackMs, float releaseMs, float makeupDb) {
    compThresholdDb_ = thresholdDb;
    compRatio_ = ratio < 1.0f ? 1.0f : ratio;
    compAttackMs_ = attackMs < 0.1f ? 0.1f : attackMs;
    compReleaseMs_ = releaseMs < 1.0f ? 1.0f : releaseMs;
    compMakeupDb_ = makeupDb;
    float sr = (float)(sampleRate_ > 0 ? sampleRate_ : 48000);
    compAttackCoeff_ = expf(-1.0f / (sr * compAttackMs_ / 1000.0f));
    compReleaseCoeff_ = expf(-1.0f / (sr * compReleaseMs_ / 1000.0f));
    compMakeupLinear_ = powf(10.0f, compMakeupDb_ / 20.0f);
    LOGI("DAC compressor params: thr=%.1f ratio=%.1f atk=%.1f rel=%.1f makeup=%.1f",
         thresholdDb, ratio, attackMs, releaseMs, makeupDb);
}

void UsbAudioDriver::resetCompressor() {
    compEnvDb_ = -120.0f;
    compGrDb_ = 0.0f;
    LOGI("DAC compressor reset");
}

// 【V8.3】等响补偿（ISO 226）——低音量时低频/高频自动提升。stereo-linked。
void UsbAudioDriver::setLoudnessEnabled(bool en) {
    loudness_.setEnabled(en);
    LOGI("DAC loudness %s", en ? "enabled" : "disabled");
}

void UsbAudioDriver::setLoudnessIntensity(float intensity) {
    loudness_.setIntensity(intensity);
    LOGI("DAC loudness intensity=%.2f", intensity);
}

void UsbAudioDriver::setLoudnessOutGain(float gain) {
    loudness_.setOutGain(gain);
}

// 【V8.170】响度统一管理：dB -> 线性系数，交给 preGain 平滑施加。
void UsbAudioDriver::setNormalizationGain(float db) {
    float d = db < -24.0f ? -24.0f : (db > 24.0f ? 24.0f : db);
    float lin = powf(10.0f, d / 20.0f);
    normGainLin_.store(lin, std::memory_order_relaxed);
    LOGI("DAC normGain %.2f dB (%.4f)", d, lin);
}

// 鈹€鈹€ pushPcm 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

int UsbAudioDriver::pushPcm(const float* data, int frameCount) {
    // [V9.20n] Single-producer guarantee for the ring buffer. Track switch can
    // briefly leave TWO producers alive: the old session's FLAC/ALAC native decode
    // thread (stopped via flacStop/alacStop inside releaseResources) and the new
    // Kotlin decodeLoop. Unsynchronized pushPcm from both corrupts wp/rp bookkeeping
    // (overlapping writes, interleaved writePos_ stores) -> fill frozen at ~32414,
    // DIAG reads all-zero, phantom stalls -> forceReset storm -> "MP3 switch stuck".
    // Holding the lock across the stall sleep is intended: while one producer waits
    // for ring space, no other producer may overwrite the state underneath it.
    std::lock_guard<std::mutex> pushLock(pushMutex_);

    if (!ringBuffer_ || frameCount <= 0) return -1;

    // [V9.20x hotplug fallback] Fast-fail path: once URB submission has declared the
    // device gone, stop feeding the ring. Every format (FLAC/ALAC native decode
    // threads and the Kotlin MP3/WAV decodeLoop) funnels through pushPcm, so -3 is
    // the one return code that reaches all of them without a polling tick.
    // -3 is distinct from -2 (bounded ring stall) so the caller knows this is a
    // surprise removal, not a transient stall it should try to self-heal.
    if (deviceVanished_.load(std::memory_order_acquire)) return -3;

    // 銆怴3.2.7銆戣儗鍘嬶細娴佽繍琛屾椂闃诲绛夊緟绌洪棿锛岃В鐮佺嚎绋嬭闄愬埗鍒板疄鏃堕€熷害锟?
    // 鏈紑娴侊紙棰勭紦鍐查樁娈碉級鍐欏灏戠畻澶氬皯锛屼笉闃诲锟?
    int totalWritten = 0;
    const float* src = data;
    int remaining = frameCount;

    // 【V8.3】瞬态整形包络系数（预计算，基于当前采样率）。
    const float tsSr = (float)sampleRate_;
    const float tsAtkFast = 1.0f - expf(-1.0f / (tsSr * 0.001f));   // 1ms
    const float tsRelFast = 1.0f - expf(-1.0f / (tsSr * 0.015f));   // 15ms
    const float tsAtkSlow = 1.0f - expf(-1.0f / (tsSr * 0.015f));   // 15ms
    const float tsRelSlow = 1.0f - expf(-1.0f / (tsSr * 0.080f));   // 80ms

    // [V9.20] pushPcm bounded-stall bookkeeping (see the avail<=0 branch below)
    constexpr int64_t kPushStallMs = 2000;  // ring full this long with no consumer -> give up
    int64_t stallSinceMs = 0;

    while (remaining > 0) {
        int wp = writePos_.load(std::memory_order_acquire);
        int rp = readPos_.load(std::memory_order_acquire);
        // 锟?1 甯ч棿闅欏尯鍒嗘弧/锟?
        int avail = kRingFrames - 1 - ((wp - rp + kRingFrames) % kRingFrames);

        if (avail <= 0) {
            // [V9.20x] Device vanished while we were waiting for ring space: bail with
            // -3 at once instead of sitting out the remaining stall budget.
            if (deviceVanished_.load(std::memory_order_acquire)) {
                finalizeBackpressure(stallSinceMs);
                mVanishEvents_.fetch_add(1, std::memory_order_relaxed);
                LOGW("pushPcm: device vanished while stalled -> return -3 (detach path)");
                return -3;
            }
            if (!streaming_.load(std::memory_order_acquire)) {
                finalizeBackpressure(stallSinceMs);
                break;  // 棰勭紦鍐叉弧浜嗙洿鎺ヨ繑锟?
            }
            // [V9.20] Bounded stall wait: the old usleep(2000) loop spun here FOREVER when
            // the consumer never drained the ring (wedged USB endpoint, or streamLoop died
            // without clearing streaming_) -> decode thread frozen -> cannot switch songs
            // (the MP3 symptom). Return -2 after kPushStallMs so callers can self-heal
            // (forceReset) or stop cleanly instead of hanging forever.
            // 【V9.20x】背压统计：每次进入等待记一次事件，期间持续累计 current，超时退出时
            // 把本段时长并入 total/max，并记一次 stallBreak + 丢弃字节（解码侧据此自愈）。
            {
                struct timespec ts;
                clock_gettime(CLOCK_MONOTONIC, &ts);
                int64_t nowMs = (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
                if (stallSinceMs == 0) {
                    stallSinceMs = nowMs;
                    mBackpressureEvents_.fetch_add(1, std::memory_order_relaxed);
                }
                int64_t cur = nowMs - stallSinceMs;
                mBackpressureCurMs_.store(cur, std::memory_order_relaxed);
                if (cur >= kPushStallMs) {
                    mBackpressureTotalMs_.fetch_add((uint64_t)cur, std::memory_order_relaxed);
                    auto mx = mBackpressureMaxMs_.load(std::memory_order_relaxed);
                    while (cur > mx && !mBackpressureMaxMs_.compare_exchange_weak(
                               mx, cur, std::memory_order_relaxed, std::memory_order_relaxed)) {}
                    mBackpressureCurMs_.store(0, std::memory_order_relaxed);
                    mDroppedBytes_.fetch_add((uint64_t)remaining * 2 * (uint64_t)sizeof(float),
                                             std::memory_order_relaxed);
                    mStallBreaks_.fetch_add(1, std::memory_order_relaxed);
                    LOGW("pushPcm: ring full for %lldms, no consumer progress -> return -2 (decode self-heal)",
                         (long long)cur);
                    return -2;
                }
            }
            usleep(2000);  // 绛夋秷璐圭嚎绋嬭吘绌洪棿锛垀88锟?2ms @44.1k锟?
            continue;
        }

        // got ring space: finalize any just-ended backpressure interval
        if (stallSinceMs != 0) {
            finalizeBackpressure(stallSinceMs);
        }
        stallSinceMs = 0;  // [V9.20] consumer is draining; reset the stall timer

        int chunk = remaining < avail ? remaining : avail;
        const int mask = kRingFrames * 2 - 1; // power-of-2 assumption

        // DAC-path EQ（AutoEQ 打底 + MSEB 叠加 / 5段图形），same Biquad algo as Oboe。
        // Disabled => pure pass-through (bit-perfect preserved)。
        // [V8.119] 原声直通：DAC 链所有 DSP 模块强制关闭，ring 写原始 PCM。
        // 【V9.01】引擎 DSP 主开关：与 g_dacDirectPassthrough 正交，任一为真即进入 bit-perfect 直通。
        const bool dacBypass = g_dacDirectPassthrough.load(std::memory_order_acquire)
                              || !g_engineDspEnabled.load(std::memory_order_acquire);
        bool autoEqOn = !dacBypass && autoEqEnabled_.load(std::memory_order_acquire) && autoEqSubEnabled_.load(std::memory_order_acquire);
        bool msebOn = !dacBypass && mseb10Enabled_.load(std::memory_order_acquire) && msebSubEnabled_.load(std::memory_order_acquire);
        bool dspOn = !dacBypass && dspEqEnabled_.load(std::memory_order_acquire);
        // 【V8.170】归一化是源增益：先于所有 EQ/限幅，后面的模块看到的都是已归一化的电平。
        // 原声直通（dacBypass）时整枚旁路，保持 bit-perfect。
        float targetPreGain = dacBypass ? 1.0f : normGainLin_.load(std::memory_order_relaxed);
        if (autoEqOn || msebOn || dspOn) {
            // 【V8.2 hiby】blocking lock: setters hold dspEqMutex_ only for
            // microseconds of pure math, so the decode thread can safely wait for
            // coeff commit instead of dropping EQ for a whole chunk (which caused
            // a processed<->pass-through timbre step = audible crackle).
            std::lock_guard<std::mutex> eqLock(dspEqMutex_);
            // 各开模块的 preGain 相乘（都是 ≤1 衰减系数，叠加后一起补偿）。
            // 基准已由上面的归一化增益给出，这里只叠加 EQ 自身的补偿。
            if (autoEqOn) targetPreGain *= autoEqPreGain_;
            if (msebOn)   targetPreGain *= msebPreGain_;
            if (dspOn && !msebOn) targetPreGain *= dspEqPreGain_;
        }

        // [zipper-noise fix] fade pre-gain toward target per-sample (same SMOOTH as
        // BiquadFilter). MSEB slider changes (preGain jump) no longer step the
        // output level instantly -> eliminates the crackle/hiss heard while adjusting live.
        const float PREGAIN_SMOOTH = 0.05f;
        float preGain = curDspPreGain_;
        bool msOn = !dacBypass && msEnabled_.load(std::memory_order_acquire) && dtsSubEnabled_.load(std::memory_order_acquire);
        float msW = curMsWidth_, msC = curMsCenter_;
        float msTargetW = msWidth_.load(std::memory_order_acquire);
        float msTargetC = msCenter_.load(std::memory_order_acquire);
        // 【V8.3】等响补偿：实际输出增益 = 软件音量（立方 taper 后的 volume_，即真正送 DAC 的响度）。
        if (!dacBypass && loudness_.enabled_) loudness_.setOutGain(volume_.load(std::memory_order_relaxed));
        // [V8.118] any DSP module active? -> enable the safety shaper for this chunk.
        bool dacAnyDsp = !dacBypass && (autoEqOn || msebOn || dspOn || msOn
                       || transientEnabled_.load(std::memory_order_acquire)
                       || compressorEnabled_.load(std::memory_order_acquire)
                       || loudness_.enabled_);

        for (int f = 0; f < chunk; ++f) {
            preGain += (targetPreGain - preGain) * PREGAIN_SMOOTH;
            float sL = src[f * 2] * preGain;
            float sR = src[f * 2 + 1] * preGain;

            // AutoEQ 打底（耳机修正，任意频点），可与其他并存叠加。
            if (autoEqOn) {
                sL = autoEqBand1L_.process(sL); sL = autoEqBand2L_.process(sL); sL = autoEqBand3L_.process(sL); sL = autoEqBand4L_.process(sL); sL = autoEqBand5L_.process(sL);
                sL = autoEqBand6L_.process(sL); sL = autoEqBand7L_.process(sL); sL = autoEqBand8L_.process(sL); sL = autoEqBand9L_.process(sL); sL = autoEqBand10L_.process(sL);
                sL = autoEqBand11L_.process(sL);
                sL = autoEqBand12L_.process(sL);
                sL = autoEqBand13L_.process(sL);
                sL = autoEqBand14L_.process(sL);
                sL = autoEqBand15L_.process(sL);
                sL = autoEqBand16L_.process(sL);
                sL = autoEqBand17L_.process(sL);
                sL = autoEqBand18L_.process(sL);
                sL = autoEqBand19L_.process(sL);
                sL = autoEqBand20L_.process(sL);
                sL = autoEqBand21L_.process(sL);
                sL = autoEqBand22L_.process(sL);
                sL = autoEqBand23L_.process(sL);
                sL = autoEqBand24L_.process(sL);
                sL = autoEqBand25L_.process(sL);
                sL = autoEqBand26L_.process(sL);
                sL = autoEqBand27L_.process(sL);
                sL = autoEqBand28L_.process(sL);
                sL = autoEqBand29L_.process(sL);
                sL = autoEqBand30L_.process(sL);
                sL = autoEqBand31L_.process(sL);
                sL = autoEqBand32L_.process(sL);
                sL = autoEqBand33L_.process(sL);
                sL = autoEqBand34L_.process(sL);
                sL = autoEqBand35L_.process(sL);
                sL = autoEqBand36L_.process(sL);
                sL = autoEqBand37L_.process(sL);
                sL = autoEqBand38L_.process(sL);
                sL = autoEqBand39L_.process(sL);
                sL = autoEqBand40L_.process(sL);
                sL = autoEqBand41L_.process(sL);
                sL = autoEqBand42L_.process(sL);
                sL = autoEqBand43L_.process(sL);
                sL = autoEqBand44L_.process(sL);
                sL = autoEqBand45L_.process(sL);
                sL = autoEqBand46L_.process(sL);
                sL = autoEqBand47L_.process(sL);
                sL = autoEqBand48L_.process(sL);
                sL = autoEqBand49L_.process(sL);
                sL = autoEqBand50L_.process(sL);
                sL = autoEqBand51L_.process(sL);
                sL = autoEqBand52L_.process(sL);
                sL = autoEqBand53L_.process(sL);
                sL = autoEqBand54L_.process(sL);
                sL = autoEqBand55L_.process(sL);
                sL = autoEqBand56L_.process(sL);
                sL = autoEqBand57L_.process(sL);
                sL = autoEqBand58L_.process(sL);
                sL = autoEqBand59L_.process(sL);
                sL = autoEqBand60L_.process(sL);
                sL = autoEqBand61L_.process(sL);
                sL = autoEqBand62L_.process(sL);
                sL = autoEqBand63L_.process(sL);
                sL = autoEqBand64L_.process(sL);

                sR = autoEqBand1R_.process(sR); sR = autoEqBand2R_.process(sR); sR = autoEqBand3R_.process(sR); sR = autoEqBand4R_.process(sR); sR = autoEqBand5R_.process(sR);
                sR = autoEqBand6R_.process(sR); sR = autoEqBand7R_.process(sR); sR = autoEqBand8R_.process(sR); sR = autoEqBand9R_.process(sR); sR = autoEqBand10R_.process(sR);
                sR = autoEqBand11R_.process(sR);
                sR = autoEqBand12R_.process(sR);
                sR = autoEqBand13R_.process(sR);
                sR = autoEqBand14R_.process(sR);
                sR = autoEqBand15R_.process(sR);
                sR = autoEqBand16R_.process(sR);
                sR = autoEqBand17R_.process(sR);
                sR = autoEqBand18R_.process(sR);
                sR = autoEqBand19R_.process(sR);
                sR = autoEqBand20R_.process(sR);
                sR = autoEqBand21R_.process(sR);
                sR = autoEqBand22R_.process(sR);
                sR = autoEqBand23R_.process(sR);
                sR = autoEqBand24R_.process(sR);
                sR = autoEqBand25R_.process(sR);
                sR = autoEqBand26R_.process(sR);
                sR = autoEqBand27R_.process(sR);
                sR = autoEqBand28R_.process(sR);
                sR = autoEqBand29R_.process(sR);
                sR = autoEqBand30R_.process(sR);
                sR = autoEqBand31R_.process(sR);
                sR = autoEqBand32R_.process(sR);
                sR = autoEqBand33R_.process(sR);
                sR = autoEqBand34R_.process(sR);
                sR = autoEqBand35R_.process(sR);
                sR = autoEqBand36R_.process(sR);
                sR = autoEqBand37R_.process(sR);
                sR = autoEqBand38R_.process(sR);
                sR = autoEqBand39R_.process(sR);
                sR = autoEqBand40R_.process(sR);
                sR = autoEqBand41R_.process(sR);
                sR = autoEqBand42R_.process(sR);
                sR = autoEqBand43R_.process(sR);
                sR = autoEqBand44R_.process(sR);
                sR = autoEqBand45R_.process(sR);
                sR = autoEqBand46R_.process(sR);
                sR = autoEqBand47R_.process(sR);
                sR = autoEqBand48R_.process(sR);
                sR = autoEqBand49R_.process(sR);
                sR = autoEqBand50R_.process(sR);
                sR = autoEqBand51R_.process(sR);
                sR = autoEqBand52R_.process(sR);
                sR = autoEqBand53R_.process(sR);
                sR = autoEqBand54R_.process(sR);
                sR = autoEqBand55R_.process(sR);
                sR = autoEqBand56R_.process(sR);
                sR = autoEqBand57R_.process(sR);
                sR = autoEqBand58R_.process(sR);
                sR = autoEqBand59R_.process(sR);
                sR = autoEqBand60R_.process(sR);
                sR = autoEqBand61R_.process(sR);
                sR = autoEqBand62R_.process(sR);
                sR = autoEqBand63R_.process(sR);
                sR = autoEqBand64R_.process(sR);

            }
            // MSEB 叠加（主观调音）——优先于 5段图形 EQ。
            if (msebOn) {
                sL = msebBand1L_.process(sL);
                sL = msebBand2L_.process(sL); sL = msebBand3L_.process(sL); sL = msebBand4L_.process(sL); sL = msebBand5L_.process(sL);
                sL = msebBand6L_.process(sL); sL = msebBand7L_.process(sL); sL = msebBand8L_.process(sL); sL = msebBand9L_.process(sL); sL = msebBand10L_.process(sL);
                sR = msebBand1R_.process(sR);
                sR = msebBand2R_.process(sR); sR = msebBand3R_.process(sR); sR = msebBand4R_.process(sR); sR = msebBand5R_.process(sR);
                sR = msebBand6R_.process(sR); sR = msebBand7R_.process(sR); sR = msebBand8R_.process(sR); sR = msebBand9R_.process(sR); sR = msebBand10R_.process(sR);
            } else if (dspOn) {
                sL = dspEqBand5L_.process(dspEqBand4L_.process(dspEqBand3L_.process(dspEqBand2L_.process(dspEqBand1L_.process(sL)))));
                sR = dspEqBand5R_.process(dspEqBand4R_.process(dspEqBand3R_.process(dspEqBand2R_.process(dspEqBand1R_.process(sR)))));
            }

            // 【V8.3】等响补偿（ISO 226）—— EQ 之后、M/S 之前，stereo-linked。
            if (!dacBypass && loudness_.enabled_) {
                loudness_.process(sL, sR);
            }

            // 【V8.3】M/S 声场（跨声道矩阵，仅当开启）——独立于 EQ，可单独开关。
            if (msOn) {
                msW += (msTargetW - msW) * PREGAIN_SMOOTH;
                msC += (msTargetC - msC) * PREGAIN_SMOOTH;
                float mid = (sL + sR) * 0.5f;
                float side = (sL - sR) * 0.5f;
                sL = mid * msC + side * msW;
                sR = mid * msC - side * msW;
            }

            // 【V8.3】瞬态整形（时域，双时间常数包络跟随器）——M/S 之后、写入 ring 之前。
            if (!dacBypass && transientEnabled_.load(std::memory_order_acquire)) {
                curTransientAmount_ += (transientAmount_.load(std::memory_order_acquire) - curTransientAmount_) * PREGAIN_SMOOTH;
                float amt = curTransientAmount_;
                if (fabsf(amt) > 0.001f) {
                    float aL = fabsf(sL);
                    tsFastEnvL_ += (aL - tsFastEnvL_) * (aL > tsFastEnvL_ ? tsAtkFast : tsRelFast);
                    tsSlowEnvL_ += (aL - tsSlowEnvL_) * (aL > tsSlowEnvL_ ? tsAtkSlow : tsRelSlow);
                    float transL = tsFastEnvL_ - tsSlowEnvL_;
                    // [V8.118] 4.0->1.5 + LF protection (1ms probe AM-modulated the bass)
                    float tsDriveL = (tsSlowEnvL_ > 0.20f && transL < 0.12f) ? 0.0f : transL;
                    float gainL = 1.0f + amt * tsDriveL * 1.5f;
                    if (gainL < 0.05f) gainL = 0.05f;
                    if (gainL > 1.5f) gainL = 1.5f;
                    sL *= gainL;
                    float aR = fabsf(sR);
                    tsFastEnvR_ += (aR - tsFastEnvR_) * (aR > tsFastEnvR_ ? tsAtkFast : tsRelFast);
                    tsSlowEnvR_ += (aR - tsSlowEnvR_) * (aR > tsSlowEnvR_ ? tsAtkSlow : tsRelSlow);
                    float transR = tsFastEnvR_ - tsSlowEnvR_;
                    // [V8.118] 4.0->1.5 + LF protection (same as L)
                    float tsDriveR = (tsSlowEnvR_ > 0.20f && transR < 0.12f) ? 0.0f : transR;
                    float gainR = 1.0f + amt * tsDriveR * 1.5f;
                    if (gainR < 0.05f) gainR = 0.05f;
                    if (gainR > 1.5f) gainR = 1.5f;
                    sR *= gainR;
                }
            }

            // 【V8.3】动态压缩（master bus，stereo-linked）——瞬态整形之后、写 ring 之前。
            if (!dacBypass && compressorEnabled_.load(std::memory_order_acquire)) {
                float peak = fmaxf(fabsf(sL), fabsf(sR));
                float peakDb = 20.0f * log10f(peak + 1e-12f);
                if (peakDb > compEnvDb_) {
                    compEnvDb_ += (1.0f - compAttackCoeff_) * (peakDb - compEnvDb_);
                } else {
                    compEnvDb_ += (1.0f - compReleaseCoeff_) * (peakDb - compEnvDb_);
                }
                float over = compEnvDb_ - compThresholdDb_;
                float halfKnee = 3.0f; // knee=6dB
                float slope = 1.0f - 1.0f / compRatio_;
                float targetGr;
                if (over <= -halfKnee) targetGr = 0.0f;
                else if (over >= halfKnee) targetGr = over * slope;
                else { float t = over + halfKnee; targetGr = (t * t) / 12.0f * slope; }
                if (targetGr < compGrDb_) compGrDb_ += (1.0f - compAttackCoeff_) * (targetGr - compGrDb_);
                else compGrDb_ += (1.0f - compReleaseCoeff_) * (targetGr - compGrDb_);
                float g = powf(10.0f, -compGrDb_ / 20.0f) * compMakeupLinear_;
                sL *= g;
                sR *= g;
            }

            // [V8.118] Safety shaper: prevents the pack-time hard clamp from ever
            // engaging on DSP-processed content (bit-perfect / no-DSP untouched).
            if (dacAnyDsp) {
                sL = dacSoftClip(sL);
                sR = dacSoftClip(sR);
                if (sL > 0.9999f || sL < -0.9999f || sR > 0.9999f || sR < -0.9999f)
                    g_dacClipCount.fetch_add(1, std::memory_order_relaxed);
            }

            int base = (wp * 2) + f * 2;
            // 【V9.20x】逐声道峰值(写入 ring 处 = EQ/软限幅之后、音量之前)，供诊断与电平告警用
            {
                float aL = sL < 0.0f ? -sL : sL;
                float aR = sR < 0.0f ? -sR : sR;
                if (aL > mChannel0Peak_.load(std::memory_order_relaxed)) mChannel0Peak_.store(aL, std::memory_order_relaxed);
                if (aR > mChannel1Peak_.load(std::memory_order_relaxed)) mChannel1Peak_.store(aR, std::memory_order_relaxed);
            }
            ringBuffer_[base & mask] = sL;
            ringBuffer_[(base + 1) & mask] = sR;
        }
        curDspPreGain_ = preGain;
        curMsWidth_ = msW;
        curMsCenter_ = msC;

        writePos_.store((wp + chunk) % kRingFrames, std::memory_order_release);
        src += chunk * 2;
        remaining -= chunk;
        totalWritten += chunk;
    }

    return totalWritten;
}

// ── 【V9.20x】运行时诊断（借鉴 NeriPlayer RuntimeMetrics）───────────────────

void UsbAudioDriver::finalizeBackpressure(int64_t startMs) {
    if (startMs != 0) {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        int64_t dur = (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000 - startMs;
        if (dur > 0) {
            mBackpressureTotalMs_.fetch_add((uint64_t)dur, std::memory_order_relaxed);
            auto mx = mBackpressureMaxMs_.load(std::memory_order_relaxed);
            while (dur > mx && !mBackpressureMaxMs_.compare_exchange_weak(
                       mx, dur, std::memory_order_relaxed, std::memory_order_relaxed)) {}
        }
    }
    mBackpressureCurMs_.store(0, std::memory_order_relaxed);
}

void UsbAudioDriver::resetMetrics() {
    mBackpressureEvents_.store(0, std::memory_order_relaxed);
    mBackpressureTotalMs_.store(0, std::memory_order_relaxed);
    mBackpressureMaxMs_.store(0, std::memory_order_relaxed);
    mBackpressureCurMs_.store(0, std::memory_order_relaxed);
    mZeroFillBytes_.store(0, std::memory_order_relaxed);
    mSilentFrames_.store(0, std::memory_order_relaxed);
    mSignalFrames_.store(0, std::memory_order_relaxed);
    mChannel0Peak_.store(0, std::memory_order_relaxed);
    mChannel1Peak_.store(0, std::memory_order_relaxed);
    mDroppedBytes_.store(0, std::memory_order_relaxed);
    mStallBreaks_.store(0, std::memory_order_relaxed);
    mVanishEvents_.store(0, std::memory_order_relaxed);
    urbSubmitted_.store(0, std::memory_order_relaxed);
    urbCompleted_.store(0, std::memory_order_relaxed);
    urbErrors_.store(0, std::memory_order_relaxed);
    totalSamplesOut_.store(0, std::memory_order_relaxed);
    underrunCount_.store(0, std::memory_order_relaxed);
    bufferWatermarkMs_.store(0, std::memory_order_relaxed);
}

int UsbAudioDriver::fillMetrics(int64_t* out, int n) const {
    const int m = kMetricsCount < n ? kMetricsCount : n;
#define PUT(i, v) if ((i) < m) out[i] = (int64_t)(v)
    PUT(0,  urbSubmitted_.load(std::memory_order_relaxed));
    PUT(1,  urbCompleted_.load(std::memory_order_relaxed));
    PUT(2,  urbErrors_.load(std::memory_order_relaxed));
    PUT(3,  totalSamplesOut_.load(std::memory_order_relaxed));
    PUT(4,  underrunCount_.load(std::memory_order_relaxed));
    PUT(5,  bufferWatermarkMs_.load(std::memory_order_relaxed));
    PUT(6,  mBackpressureEvents_.load(std::memory_order_relaxed));
    PUT(7,  mBackpressureTotalMs_.load(std::memory_order_relaxed));
    PUT(8,  mBackpressureMaxMs_.load(std::memory_order_relaxed));
    PUT(9,  mBackpressureCurMs_.load(std::memory_order_relaxed));
    PUT(10, mZeroFillBytes_.load(std::memory_order_relaxed));
    PUT(11, mSilentFrames_.load(std::memory_order_relaxed));
    PUT(12, mSignalFrames_.load(std::memory_order_relaxed));
    PUT(13, (int64_t)(mChannel0Peak_.load(std::memory_order_relaxed) * 1e5));
    PUT(14, (int64_t)(mChannel1Peak_.load(std::memory_order_relaxed) * 1e5));
    PUT(15, mDroppedBytes_.load(std::memory_order_relaxed));
    PUT(16, mStallBreaks_.load(std::memory_order_relaxed));
    PUT(17, mVanishEvents_.load(std::memory_order_relaxed));
#undef PUT
    return m;
}

int UsbAudioDriver::getRingFillFrames() {
    int wp = writePos_.load(std::memory_order_acquire);
    int rp = readPos_.load(std::memory_order_acquire);
    return (wp - rp + kRingFrames) % kRingFrames;
}

// claimInterface 锟?set the exact alternate setting chosen by Kotlin

bool UsbAudioDriver::claimInterface(int desiredAlt) {
    if (fd_ < 0) return false;

    // This mirrors the Java-side conn.claimInterface() 锟?the fd already has
    // the interface claimed. We just set our claimed flag.
    claimed_.store(true, std::memory_order_release);

    // Set the exact alt chosen by getEndpointInfo (Salt Player approach: no looping)
    // NOTE: applies to BOTH UAC1 (full-speed, e.g. TTGK) and UAC2. The alt
    // setting is what activates the ISO OUT endpoint; skipping it for UAC1
    // leaves the endpoint inactive -> submitUrbRaw returns ENOENT.
    if (desiredAlt > 0) {
        if (setInterfaceAlt(desiredAlt)) {
            LOGI("Set interface alt setting %d", desiredAlt);
        } else {
            LOGW("Failed to set interface alt %d, continuing anyway", desiredAlt);
        }
    }

    // [P6 fix] The implicit-feedback IN endpoint (0x81) is frequently on a DIFFERENT
    // interface than the playback OUT endpoint (e.g. the capture/sync interface on a
    // UAC2 DAC). That interface is never claimed/activated, so submitting a URB to it
    // returns EBUSY (-16) and the async feedback loop silently falls back to open-loop
    // (the long-playback noise returns). Claim + activate the feedback interface here.
    activateFeedbackInterface();

    return claimed_.load(std::memory_order_acquire);
}

// [P6 fix] Claim + activate the interface that owns the feedback IN endpoint when it
// is NOT the playback interface. Realtek 0BDA:4BA6 puts feedback EP 0x81 on iface 1
// alt 1 while playback OUT 0x07 lives on iface 2 alt 3; the Java side only claims the
// AudioControl iface and the OUT streaming iface (IN ifaces are skipped on purpose),
// so the feedback interface must be claimed/activated HERE — otherwise the kernel
// rejects the feedback URB with EBUSY (errno=16).
// NOTE: claimInterface() is dead code (never called); the live path is start(), which
// calls this BEFORE the stream thread submits the feedback URB.
// [V9.20r] Human-readable names for the eligibility reason codes (see
// FeedbackIneligibleReason in the header) — kept next to the state machine that sets them.
static const char* feedbackReasonName(int r) {
    switch (r) {
        case kFeedbackOk:             return "ok";
        case kFeedbackNoEndpoint:     return "no_endpoint";
        case kFeedbackOnAcInterface:  return "ac_interface";
        case kFeedbackAltConflict:    return "alt_conflict";
        case kFeedbackClaimBusy:      return "claim_busy";
        case kFeedbackSetIfaceFailed: return "setinterface_failed";
        case kFeedbackBadData:        return "bad_data";
        default:                      return "unknown";
    }
}

bool UsbAudioDriver::activateFeedbackInterface() {
    if (fd_ < 0 || feedbackEp_ < 0 || feedbackEpIface_ < 0) return false;
    if (feedbackIfaceActive_.load(std::memory_order_acquire)) return true;  // already done this session
    // [V9.20r] Eligibility gate (NeriPlayer feedback.eligible pre-check): once a reason is
    // set, the plan is proven impossible for this session — skip the ioctls entirely
    // instead of re-running a losing probe on every start() (which only spams errno=16).
    const int reason = feedbackIneligibleReason_.load(std::memory_order_acquire);
    if (reason != kFeedbackOk) {
        if (!feedbackIneligibleLogged_) {
            feedbackIneligibleLogged_ = true;
            LOGW("activateFeedbackInterface: closed-loop disabled (reason=%s iface=%d) — staying open-loop",
                 feedbackReasonName(reason), feedbackEpIface_);
        }
        // [safety 2026-10-22] A DAC that HAS a feedback EP but we couldn't activate it will
        // run open-loop and drift -> max-scale pops. Mute output (permanent until stop) so a
        // break can never blast the ears. Inherently-open-loop DACs (no endpoint / EP on AC
        // iface) are excluded — they play fine without a feedback loop. A loop that armed but
        // then reported garbage (kFeedbackBadData) also degrades gracefully to open-loop and
        // must NOT break-mute, or it would itself cause stutter ("卡卡").
        if (feedbackEp_ >= 0 && reason != kFeedbackNoEndpoint && reason != kFeedbackOnAcInterface
            && reason != kFeedbackBadData) {
            armBreakMute("feedback-loop-failed", /*permanent=*/true);
        }
        return false;
    }
    if (feedbackEpIface_ == ifaceNum_) {
        // [V9.20r] Pairwise (iface, alt) conflict check — minimal form of NeriPlayer's alt
        // matrix: one interface can only be in ONE alt setting at a time. If the feedback
        // EP needs a different alt than the playback stream just activated, the claim plan
        // is self-contradictory; reject it instead of arming a URB the kernel refuses.
        if (feedbackEpAlt_ >= 0 && feedbackEpAlt_ != currentAlt_) {
            feedbackIneligibleReason_.store(kFeedbackAltConflict, std::memory_order_release);
            LOGW("feedback EP 0x%02X needs iface %d alt %d but playback uses alt %d — cannot co-activate (reason=alt_conflict)",
                 feedbackEp_, ifaceNum_, feedbackEpAlt_, currentAlt_);
            return false;
        }
        // Shares the playback interface AND its alt: that alt setting already activated
        // the endpoint — no extra claim needed.
        LOGI("feedback EP 0x%02X shares playback iface %d alt %d (co-activated)",
             feedbackEp_, ifaceNum_, feedbackEpAlt_);
        feedbackIfaceActive_.store(true, std::memory_order_release);
        return true;
    }
    unsigned int fbIface = (unsigned int)feedbackEpIface_;
    // [feedback] The feedback IN interface (Realtek 0BDA:4BA6 iface 1 / EP 0x81) is held by
    // the kernel snd-usb-audio driver by default; our USBDEVFS_DISCONNECT is a no-op on this
    // kernel (ENOTTY). So the Java side now claims+owns this iface via the Android USB API
    // (which detaches the kernel driver cleanly) BEFORE native open. Here we tolerate
    // CLAIMINTERFACE returning EBUSY (= already owned by our own fd via Java) and proceed to
    // SETINTERFACE; if that succeeds we own the iface and the async loop can arm. Retry the
    // whole sequence a few times to absorb any transient EBUSY from the kernel reaping URBs.
    const int kFbAttempts = 3;
    bool fbActivated = false;
    for (int attempt = 0; attempt < kFbAttempts && !fbActivated; ++attempt) {
        if (attempt > 0) usleep(50000);
        // Disconnect any kernel driver bound to this interface (no-op if already detached by
        // the Java claim / ENOTTY on this kernel).
        struct usbdevfs_ioctl disc = {};
        disc.ifno = (uint8_t)fbIface;
        disc.ioctl_code = 0x5502;  // USBDEVFS_DISCONNECT
        disc.data = nullptr;
        int dr = ioctl(fd_, USBDEVFS_IOCTL, &disc);
        if (dr == 0) {
            LOGI("DISCONNECT kernel driver iface=%d: OK (attempt %d)", feedbackEpIface_, attempt + 1);
        } else if (errno != ENOTTY) {
            LOGW("DISCONNECT kernel driver iface=%d: errno=%d (%s) (attempt %d)",
                 feedbackEpIface_, errno, strerror(errno), attempt + 1);
        }
        bool owned = false;
        int cr = ioctl(fd_, USBDEVFS_CLAIMINTERFACE, &fbIface);
        if (cr == 0) {
            LOGI("claimed feedback interface %d (attempt %d)", feedbackEpIface_, attempt + 1);
            owned = true;
        } else if (errno == EBUSY) {
            // Already owned on this fd (Java-side UsbDeviceConnection.claimInterface for the
            // feedback iface detached the kernel driver and owns it here) -> treat as ours.
            LOGI("feedback iface %d already owned (EBUSY) — proceeding (attempt %d)", feedbackEpIface_, attempt + 1);
            owned = true;
        } else {
            LOGW("claim feedback iface %d failed: %s (errno=%d) (attempt %d)",
                 feedbackEpIface_, strerror(errno), errno, attempt + 1);
        }
        if (owned) {
            struct usbdevfs_setinterface setif = {};
            setif.interface = (uint8_t)feedbackEpIface_;
            setif.altsetting = (uint8_t)((feedbackEpAlt_ > 0) ? feedbackEpAlt_ : 1);
            if (ioctl(fd_, USBDEVFS_SETINTERFACE, &setif) == 0) {
                LOGI("set feedback iface %d -> alt %d (activates feedback EP 0x%02X) (attempt %d)",
                     feedbackEpIface_, setif.altsetting, feedbackEp_, attempt + 1);
                feedbackIfaceActive_.store(true, std::memory_order_release);
                fbActivated = true;
            } else {
                LOGW("set feedback iface %d alt failed: %s (errno=%d) (attempt %d)",
                     feedbackEpIface_, strerror(errno), errno, attempt + 1);
                // Only release if WE claimed it this attempt (not if Java already owns it).
                if (cr == 0) ioctl(fd_, USBDEVFS_RELEASEINTERFACE, &fbIface);
            }
        }
    }
    if (fbActivated) return true;
    // All attempts failed: record the reason so no later start() re-runs this losing
    // probe (it would just re-spam errno=16), and stay open-loop.
    feedbackIneligibleReason_.store(kFeedbackClaimBusy, std::memory_order_release);
    feedbackIneligibleLogged_ = true;
    LOGW("activateFeedbackInterface: claim/activate failed after %d attempts — closed-loop disabled (reason=claim_busy iface=%d), staying open-loop",
         kFbAttempts, feedbackEpIface_);
    return false;
}

void UsbAudioDriver::releaseInterface() {
    claimed_.store(false, std::memory_order_release);
    feedbackIfaceActive_.store(false, std::memory_order_release);  // [P6] fd close drops all interface claims
    feedbackIneligibleReason_.store(kFeedbackOk, std::memory_order_release);  // [V9.20r] fresh fd = fresh device state
    feedbackIneligibleLogged_ = false;
    if (fd_ >= 0) {
        close(fd_);
        fd_ = -1;
    }
}

bool UsbAudioDriver::setInterfaceAlt(int alt) {
    if (fd_ < 0) return false;

    struct usbdevfs_setinterface setif = {};
    setif.interface = ifaceNum_;  // streaming interface number from descriptor
    setif.altsetting = alt;

    int ret = ioctl(fd_, USBDEVFS_SETINTERFACE, &setif);
    if (ret < 0) {
        LOGW("setInterfaceAlt(%d) failed: %s (errno=%d)", alt, strerror(errno), errno);
        return false;
    }
    if (vid_ == 0x3302 && alt != 0) {
        // [quirk TTGK IFACE_DELAY] kernel lists 3302:17c2 with FORCE_IFACE_RESET|IFACE_DELAY.
        // After activating a non-zero alt, let the DAC firmware settle before any
        // control transfer (SET_CUR) or URB submission.
        usleep(5000);
    }
    return true;
}

// 鈹€鈹€ setSampleRate 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

// 鈹€鈹€ Internal helpers 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

// Unconditionally send the sample rate control transfer.
// Caller guarantees: no ISO URB in flight (call from open() before alt=1).
// Uses the first UAC2 Clock Source entity found in the descriptor.
int UsbAudioDriver::trySetSampleRate(int rate) {
    uint8_t data[4];
    struct usbdevfs_ctrltransfer ctrl = {};
    int ret = -1;

    if (isUac2_ && !forceUac1_) {
        // [align Salt] Use the same full chain as findClockSourceId():
        // AS General bTerminalLink -> Input Terminal bTerminalID -> bCSourceID.
        // [2026-10-22] 本会话已验证过的时钟实体优先（4BA6：30 能用、27 一 SET 就 EPIPE）。
        int clockId = (verifiedClockId_ >= 0) ? verifiedClockId_ : findClockSourceId();
        if (clockId < 0) clockId = 3;  // default from OT descriptor
        LOGI("trySetSampleRate: clockId=%d (via full terminal-link chain%s)", clockId,
             (verifiedClockId_ >= 0) ? ", cached-verified" : "");

        // UAC2: SET_CUR on the clock entity via AC interface (ifaceNum_).
        data[0] = (uint8_t)(rate & 0xFF);
        data[1] = (uint8_t)((rate >> 8) & 0xFF);
        data[2] = (uint8_t)((rate >> 16) & 0xFF);
        data[3] = (uint8_t)((rate >> 24) & 0xFF);

        // [V8.124] SET_CUR 后 GET_CUR 读回校验（Aurora 移植），
        // 防"SET_CUR 返回成功但 DAC 时钟实际没锁到目标率"导致的隐性爆音/变速。
        // [2026-10-22] 单次尝试：0=SET+GET 均验证通过；1=SET 成功但读不回/对不上
        // （个别设备 GET_CUR 不实现，保底接受）；-1=SET 本身失败（死时钟实体）。
        auto attemptClock = [&](int cid) -> int {
            struct usbdevfs_ctrltransfer c = {};
            c.bRequestType = 0x21;                          // host->device | class | interface
            c.bRequest     = 0x01;                          // SET_CUR
            c.wValue       = 0x0100;                        // CS_SAM_FREQ_CONTROL << 8
            c.wIndex       = (uint16_t)((cid << 8) | 0x00); // clock entity | AC iface
            c.wLength      = 4;
            c.timeout      = 200;
            c.data         = data;
            int r = ioctl(fd_, USBDEVFS_CONTROL, &c);
            LOGI("trySetSampleRate(UAC2): clockId=%d rate=%d wIndex=0x%04X ret=%d",
                 cid, rate, c.wIndex, r);
            if (r < 0) return -1;
            // [Stage0 2026-10-23] Readback with settle — aligned with Linux snd-usb-audio
            // (same policy as NeriPlayer's usb_sample_rate_readback). Plenty of DACs keep
            // reporting the PREVIOUS rate while the clock is still locking, so a single
            // read right after SET_CUR misreports "not locked"; the old code then accepted
            // immediately and we streamed at a rate the DAC had not switched to yet — the
            // multi-second full-scale blast at track start. Re-read a few times with a
            // short increasing settle delay, and only give up (accept unverified) if the
            // clock never reports the target. readBack == 0 means GET_CUR is not
            // implemented on this device -> accept at once, nothing to wait for.
            constexpr int kRateSettleReads = 4;
            for (int attempt = 0; attempt < kRateSettleReads; ++attempt) {
                uint32_t readBack = 0;
                struct usbdevfs_ctrltransfer rb = {};
                rb.bRequestType = 0xA1; rb.bRequest = 0x01; // GET_CUR
                rb.wValue = 0x0100;                          // CS_SAM_FREQ_CONTROL
                rb.wIndex = (uint16_t)((cid << 8) | 0x00);
                rb.wLength = 4; rb.timeout = 200; rb.data = &readBack;
                int rr = ioctl(fd_, USBDEVFS_CONTROL, &rb);
                if (rr == 4 && readBack == (uint32_t)rate) {
                    LOGI("trySetSampleRate: GET_CUR readback OK rate=%u (clock %d, read %d)",
                         readBack, cid, attempt + 1);
                    return 0;
                }
                if (rr == 4 && readBack == 0) {
                    LOGW("trySetSampleRate: GET_CUR unsupported (readback 0) on clock %d -> accept unverified", cid);
                    return 1;
                }
                if (attempt + 1 < kRateSettleReads) {
                    const int settleMs = 2 << attempt;   // 2, 4, 8 ms
                    usleep(settleMs * 1000);
                }
            }
            LOGW("trySetSampleRate: GET_CUR readback never settled to %d on clock %d after %d reads "
                 "(clock may not be locked) -> accept unverified",
                 rate, cid, kRateSettleReads);
            return 1;
        };

        ret = attemptClock(clockId);
        if (ret == 0) {
            verifiedClockId_ = clockId;
        } else if (ret < 0) {
            // [2026-10-22] 解析选中的时钟实体不接受 SET_CUR（4BA6 clock 27 EPIPE）。
            // 描述符里每条音频路各有时钟实体，只有播放路那个接受 SAMPLING_FREQ SET_CUR。
            // 逐个重试 + GET_CUR 验证，选中即缓存，后续 start 直接走它。
            for (int cid : clockEntityIds_) {
                if (cid == clockId) continue;
                LOGW("trySetSampleRate: clock %d SET_CUR failed, retrying clock %d", clockId, cid);
                int r2 = attemptClock(cid);
                if (r2 == 0) { clockId = cid; ret = 0; verifiedClockId_ = cid; break; }
                if (r2 > 0 && ret < 0) { clockId = cid; ret = r2; }  // SET 成功但读不回：保底
            }
            if (ret < 0) {
                LOGW("trySetSampleRate(%d) failed: no clock entity accepted SET_CUR (%zu candidates)",
                     rate, clockEntityIds_.size());
            }
        }
    } else {
        // UAC1: SET_CUR SAMPLING_FREQ_CONTROL on the ISO OUT endpoint.
        data[0] = (uint8_t)(rate & 0xFF);
        data[1] = (uint8_t)((rate >> 8) & 0xFF);
        data[2] = (uint8_t)((rate >> 16) & 0xFF);
        ctrl.bRequestType = 0x22;                          // host鈫抎evice | class | endpoint
        ctrl.bRequest     = 0x01;                          // SET_CUR
        ctrl.wValue       = 0x0100;                        // SAMPLING_FREQ_CONTROL
        ctrl.wIndex       = (uint16_t)epAddress_;          // ISO OUT endpoint address
        ctrl.wLength      = 3;
        ctrl.timeout      = 200;
        ctrl.data         = data;
        ret = ioctl(fd_, USBDEVFS_CONTROL, &ctrl);
        LOGI("trySetSampleRate(UAC1): ep=0x%02X rate=%d ret=%d", epAddress_, rate, ret);
    }

    if (ret < 0) {
        LOGW("trySetSampleRate(%d) failed: %s (errno=%d)", rate, strerror(errno), errno);
        return -1;
    }
    if (ctlMsgDelayUs_ > 0) {
        usleep(ctlMsgDelayUs_);  // [quirk] let DAC PLL/clock settle after SET_CUR (TTGK/Qudelix)
    }
    sampleRate_ = rate;
    return rate;
}

// Public setSampleRate: only safe when stream is NOT active.
// Since we now call trySetSampleRate from open() (before alt=1),
// this is mostly a no-op during normal start().
int UsbAudioDriver::setSampleRate(int rate) {
    if (fd_ < 0) return -1;
    if (streaming_.load(std::memory_order_acquire)) {
        LOGW("setSampleRate(%d): skipped - stream active", rate);
        return sampleRate_;
    }
    return trySetSampleRate(rate);
}

// [P3] Probe a candidate list of sample rates via SET_CUR + GET_CUR readback,
// exactly like the verification trySetSampleRate() does at play time — but run
// proactively for EVERY candidate so we KNOW the real supported set instead of
// trusting the descriptor (which some firmware lie about). A rate is "verified"
// only when SET_CUR succeeds AND GET_CUR echoes the requested value back.
// Does NOT mutate sampleRate_/clockRate_ (restored to defaults at the end;
// start() will re-SET_CUR the actual playback rate anyway).
void UsbAudioDriver::probeSampleRates() {
    verifiedRates_.clear();
    if (fd_ < 0) return;
    if (skipSetCurQuirk_) {  // HIFI_A001 locks clock on any SET_CUR — never probe
        LOGI("probeSampleRates: skipSetCurQuirk set, skipping probe");
        return;
    }
    // Candidate rates (typical USB DAC rates, low→high so we find the floor first).
    static const int kCandidates[] = {
        44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000
    };
    char buf[512] = {0};
    int n = 0;
    for (size_t i = 0; i < sizeof(kCandidates) / sizeof(kCandidates[0]); ++i) {
        int rate = kCandidates[i];
        uint8_t data[4];
        struct usbdevfs_ctrltransfer ctrl = {};
        int ret = -1;
        int clockId = findClockSourceId();
        if (clockId < 0) clockId = 3;
        if (isUac2_ && !forceUac1_) {
            data[0] = (uint8_t)(rate & 0xFF); data[1] = (uint8_t)((rate >> 8) & 0xFF);
            data[2] = (uint8_t)((rate >> 16) & 0xFF); data[3] = (uint8_t)((rate >> 24) & 0xFF);
            ctrl.bRequestType = 0x21; ctrl.bRequest = 0x01; ctrl.wValue = 0x0100; // SET_CUR
            ctrl.wIndex = (uint16_t)((clockId << 8) | 0x00); ctrl.wLength = 4;
            ctrl.timeout = 200; ctrl.data = data;
            ret = ioctl(fd_, USBDEVFS_CONTROL, &ctrl);
        } else {
            // UAC1: SET_CUR SAMPLING_FREQ_CONTROL on the ISO OUT endpoint.
            data[0] = (uint8_t)(rate & 0xFF); data[1] = (uint8_t)((rate >> 8) & 0xFF);
            data[2] = (uint8_t)((rate >> 16) & 0xFF);
            ctrl.bRequestType = 0x22; ctrl.bRequest = 0x01; ctrl.wValue = 0x0100;
            ctrl.wIndex = (uint16_t)epAddress_; ctrl.wLength = 3;
            ctrl.timeout = 200; ctrl.data = data;
            ret = ioctl(fd_, USBDEVFS_CONTROL, &ctrl);
        }
        bool ok = (ret >= 0);
        if (ok) {
            // GET_CUR readback — must echo the requested rate exactly.
            uint32_t readBack = 0;
            struct usbdevfs_ctrltransfer rb = {};
            rb.bRequestType = 0xA1; rb.bRequest = 0x01; rb.wValue = 0x0100; // GET_CUR
            rb.wIndex = (isUac2_ && !forceUac1_)
                ? (uint16_t)((clockId << 8) | 0x00) : (uint16_t)epAddress_;
            rb.wLength = (isUac2_ && !forceUac1_) ? 4 : 3;
            rb.timeout = 200; rb.data = &readBack;
            int rr = ioctl(fd_, USBDEVFS_CONTROL, &rb);
            if (rr != (int)rb.wLength || (int)readBack != rate) {
                LOGW("probeSampleRates: rate=%d SET_CUR ok but GET_CUR mismatch (ret=%d got=%u) -> not verified",
                     rate, rr, readBack);
                ok = false;
            }
        }
        if (ok) {
            LOGI("probeSampleRates: %d Hz VERIFIED", rate);
            n += snprintf(buf + n, sizeof(buf) - n, "%s%d", (n > 0 ? " " : ""), rate);
        } else {
            LOGI("probeSampleRates: %d Hz NOT supported", rate);
        }
        if (ctlMsgDelayUs_ > 0) usleep(ctlMsgDelayUs_);  // let PLL settle between probes
    }
    verifiedRates_ = buf;
    // Restore internal state; start() re-SET_CURs the real playback rate.
    sampleRate_ = 48000;
    clockRate_ = 0;
}

// 鈹€鈹€ streamLoop 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€




// ┌── forceReset ─

void UsbAudioDriver::forceReset() {
    LOGI("forceReset: emergency wedge recovery triggered");
    streaming_.store(false, std::memory_order_release);
    wedgeCount_.fetch_add(1, std::memory_order_relaxed);
    // Close fd FIRST -- kernel cancels all pending URBs on close,
    // unblocking REAPURB so streamThread can exit naturally.
    if (fd_ >= 0) {
        close(fd_);
        fd_ = -1;
        claimed_.store(false, std::memory_order_release);
        feedbackIfaceActive_.store(false, std::memory_order_release);
        feedbackIneligibleReason_.store(kFeedbackOk, std::memory_order_release);  // [V9.20r] next open() re-evaluates
        feedbackIneligibleLogged_ = false;
        LOGI("forceReset: fd closed, kernel will cancel pending URBs");
    }
    // Give streamThread 500ms to exit after REAPURB fails
    if (streamThread_.joinable()) {
        std::thread waiter([this, t = std::move(streamThread_)]() mutable {
            // Native thread join with timeout via detach
            // We can't join-with-timeout in C++11, so detach and let it die
            t.detach();
            LOGI("forceReset: old streamThread detached (will exit on REAPURB failure)");
        });
        waiter.detach();
    }
    // Reset stats for clean slate
    urbSubmitted_.store(0);
    urbCompleted_.store(0);
    urbErrors_.store(0);
    underrunCount_.store(0);
    lastUrbCompletionTimeMs_.store(0);
    LOGI("forceReset: done, ready for fresh open()");
}

void UsbAudioDriver::streamLoop() {
    // 銆怴3.2.7銆戦煶棰戝疄鏃朵紭鍏堢骇锟?19 = ANDROID URGENT_AUDIO锛夛紝闄嶄綆琚皟搴﹀櫒棰勫崰瀵艰嚧锟?DAC 鏂伯
    setpriority(PRIO_PROCESS, 0, -19);
    // [P6] Capture feedback enablement once for this stream (avoids mid-stream toggle races).
    const bool fbEnabled = feedbackEnabled_.load(std::memory_order_relaxed) && feedbackEp_ >= 0;
    const int pktMaxFrames = packetSizeFrames();
    const int pktMaxBytes  = pktMaxFrames * bytesPerFrame_;
    const int urbBytes     = pktMaxBytes * urbPackets_;
    const double samplesPerMicroframe = sampleRate_ / (highSpeed_ ? 8000.0 : 1000.0);

    LOGI("streamLoop: maxFrames=%d urbBytes=%d pkts/urb=%d hs=%d rate/mf=%.4f",
         pktMaxFrames, urbBytes, urbPackets_, highSpeed_ ? 1 : 0, samplesPerMicroframe);

    // 銆怴3.2.7銆慞re-queue 12 URBs锛堝師4锛夛細纭欢鍦ㄩ闃熷垪 32ms锟?6ms锟?
    // 鎶楄皟搴︽姈鍔ㄢ€斺€旈暱鎾伓鍙戝崱椤挎牴鍥狅細stream 绾跨▼琚锟?>32ms 鍗虫柇锟?
    constexpr int kPreQueue = 12;
    for (int s = 0; s < kPreQueue; s++) {
        memset(urbSlots_[s].buffer, 0, urbBytes);
        urbSlots_[s].urb->number_of_packets = urbPackets_;
        urbSlots_[s].urb->buffer_length = urbBytes;
        for (int p = 0; p < urbPackets_; ++p) {
            urbSlots_[s].urb->iso_frame_desc[p].length = pktMaxBytes;
        }
        if (!submitUrbRaw(s)) {
            LOGE("streamLoop: pre-queue URB %d failed, aborting", s);
            streaming_.store(false, std::memory_order_release);
            return;
        }
    }
    // [D] Isochronous pipe is up: all pre-queue URBs accepted. Signal real start status
    // so start() (which polls this flag) returns true instead of a false "OK".
    streamStarted_.store(true, std::memory_order_release);
    // [P6] Single-reaper feedback: arm the feedback IN URB on the SAME fd_ as the
    // stream URBs; its completions are handled inside the reaper loop. Replaces the
    // old openat()-based feedbackLoop() thread (blocked by Android -> EACCES ->
    // never ran -> long-playback clock drift -> periodic noise).
    if (fbEnabled) {
        if (submitFeedbackUrb()) {
            feedbackActive_.store(true, std::memory_order_release);
            LOGI("streamLoop: feedback URB armed (async closed-loop ON)");
        } else {
            LOGW("streamLoop: feedback URB submit failed -> open-loop fallback");
        }
    }

    int slot = kPreQueue;

    int urbCompletions = 0;
    double phaseAccum = 0.0;
    int64_t framesEmitted = 0;   // [2026-09-10 soft-start] frames placed into URBs this stream

    while (streaming_.load(std::memory_order_acquire)) {
        struct usbdevfs_urb* done = nullptr;
        int ret = ioctl(fd_, USBDEVFS_REAPURB, &done);
        if (ret < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            LOGE("REAPURB #%d failed: %s (errno=%d)", urbCompletions + 1, strerror(errno), errno);
            break;
        }
        urbCompletions++;
        urbCompleted_.fetch_add(1, std::memory_order_relaxed);

        // [P6] Single-reaper feedback: a feedback IN URB completion is handled here
        // and must NOT advance the stream slot ring. Re-arm it while still streaming.
        if (done == fbUrb_) {
            processFeedbackUrb();
            if (streaming_.load(std::memory_order_acquire) && fbEnabled) {
                submitFeedbackUrb();
            }
            continue;
        }
        // Stamp wall-clock for health monitoring (wedge detection)
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        lastUrbCompletionTimeMs_.store(static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000, std::memory_order_release);
        if (urbCompletions <= 4 || urbCompletions % 100 == 0) {
            LOGI("REAPURB #%d OK", urbCompletions);
        }

        // DIAG: dump first 8 interleaved samples once, to spot mono-vs-stereo / byte-order bugs
        if (urbCompletions == 1) {
            int rpD = readPos_.load(std::memory_order_acquire);
            const int mD = kRingFrames * 2 - 1;
            float* rb = ringBuffer_;
            LOGI("streamLoop DIAG: ch=%d bits=%d bpf=%d sr=%d ring[0..7]=%.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f",
                 channels_, bitsPerSample_, bytesPerFrame_, sampleRate_,
                 rb[(rpD*2+0)&mD], rb[(rpD*2+1)&mD], rb[(rpD*2+2)&mD], rb[(rpD*2+3)&mD],
                 rb[(rpD*2+4)&mD], rb[(rpD*2+5)&mD], rb[(rpD*2+6)&mD], rb[(rpD*2+7)&mD]);
        }

        // [keep-alive 2026-10-23] Idle-keepalive: keep clocking the endpoint with silence so
        // the DAC never loses its sample clock between tracks. We behave exactly like a
        // sustained underrun (the tested path below) which zero-fills the URB and arms the
        // safety mute; armBreakMute() is idempotent so this logs at most once.
        if (idleMode_.load(std::memory_order_acquire)) {
            struct timespec tsIdle;
            clock_gettime(CLOCK_MONOTONIC, &tsIdle);
            int64_t nowIdle = (int64_t)tsIdle.tv_sec * 1000 + tsIdle.tv_nsec / 1000000;
            if (nowIdle >= idleDeadlineMs_) {
                LOGI("streamLoop: idle-keepalive window expired -> real stop");
                idleMode_.store(false, std::memory_order_release);
                streaming_.store(false, std::memory_order_release);
                break;
            }
        }

        int wp = writePos_.load(std::memory_order_acquire);
        int rp = readPos_.load(std::memory_order_acquire);
        int availFrames = (wp - rp + kRingFrames) % kRingFrames;
        // [keep-alive] while idle, report no data so every packet below takes the zero-fill
        // (silence) branch: the endpoint keeps its clock, the output stays silent.
        if (idleMode_.load(std::memory_order_acquire)) availFrames = 0;

        uint8_t* buf = urbSlots_[slot].buffer;
        const int ringMask = kRingFrames * 2 - 1;
        int sampleOffset = 0;   // 字节偏移：urb 缓冲写入终点
        int sampleCursor = 0;   // 样本游标：本 URB 已输出样本总数(与字节偏移隔离，避免 /bytesPerSample 反推)
        int totalFramesNeeded = 0;

        // Phase accumulator: alternate 5/6 frames per microframe for exact 44100 Hz
        for (int p = 0; p < urbPackets_; ++p) {
            // [P6] feedback loop nudges the average frames/microframe to match the DAC
            // crystal (rateCorrection_ is 0.0 when the loop is off -> bit-perfect open-loop).
            phaseAccum += samplesPerMicroframe * (1.0 + rateCorrection_);
            int nFrames = (int)phaseAccum;
            phaseAccum -= nFrames;
            // [soft-start] this packet's fade base = frames already output + frames of prior packets
            softStartFrameBase_ = framesEmitted + totalFramesNeeded;
            totalFramesNeeded += nFrames;
            int nBytes = nFrames * bytesPerFrame_;
            urbSlots_[slot].urb->iso_frame_desc[p].length = nBytes;

            if (availFrames >= totalFramesNeeded) {
                // [safety] stall-recovery fade: re-arm when transitioning out of silence.
                if (inUnderrun_) {
                    releaseBreakMute();                   // [safety] restore gain (unless permanent feedback-fail mute)
                    recoverFramesLeft_ = recoverTotal_;   // ramp restarts from 0
                    inUnderrun_ = false;
                }
                if (recoverFramesLeft_ > 0) {
                    recoverGain_ = 1.0f - (float)recoverFramesLeft_ / (float)recoverTotal_;
                    recoverFramesLeft_ = (recoverFramesLeft_ > nFrames) ? (recoverFramesLeft_ - nFrames) : 0;
                } else {
                    recoverGain_ = 1.0f;
                }
                // [fix] hearing-protection mute ramp: smoothly move muteGain_ toward
                // muteTarget_ instead of hard-stepping the gain. A hard gain step is a
                // discontinuity = an audible click/pop, which is exactly what the hearing
                // protection was meant to prevent. A few-ms fade still silences the output
                // fast enough to be safe (no audible blast) but stays click-free.
                if (muteFramesLeft_ > 0) {
                    const float mstep = 1.0f / (float)muteTotal_;
                    if (muteTarget_ < muteGain_) muteGain_ = std::max(muteTarget_, muteGain_ - mstep * nFrames);
                    else                        muteGain_ = std::min(muteTarget_, muteGain_ + mstep * nFrames);
                    muteFramesLeft_ = (muteFramesLeft_ > nFrames) ? (muteFramesLeft_ - nFrames) : 0;
                } else {
                    muteGain_ = muteTarget_;
                }
                int nSamples = nFrames * channels_;
                float vol = volume_.load(std::memory_order_relaxed);
                switch (bitsPerSample_) {
                case 16: {
                    auto* out = reinterpret_cast<int16_t*>(buf + sampleOffset);
                    const bool dither = ditherEnabled_.load(std::memory_order_relaxed);
                    // [V8.124] noise-shaped dither (Aurora 移植): 一阶误差反馈 NTF=1-z^-1,
                    // 44.1kHz+ 用噪声整形，低于回退 TPDF。每声道独立误差状态。
                    const bool shaped = dither && sampleRate_ >= 44100;
                    auto tpdf = [&]() -> float {
                        uint32_t u = ditherState_;
                        u = u * 1664525u + 1013904223u;   // Numerical Recipes LCG
                        ditherState_ = u;
                        uint32_t u2 = u * 1664525u + 1013904223u;
                        float t1 = ((u  >> 8) & 0xFFFF) / 65535.0f - 0.5f;
                        float t2 = ((u2 >> 8) & 0xFFFF) / 65535.0f - 0.5f;
                        return t1 + t2;   // ±1 LSB TPDF
                    };
                    const float scale = 32767.0f;
                    for (int j = 0; j < nSamples; ++j) {
                        int idx = ((rp * 2) + sampleCursor + j) & ringMask;
                        // [safety-fix 2026-10-22] clamp the SOURCE sample to [-1,1] BEFORE applying
                        // volume. The decoder/resampler can emit garbage (e.g. raw-int-as-float,
                        // magnitude ~1e3-3e4) in a glitched first block; the old code multiplied by
                        // volume THEN clamped, so that garbage saturated to full scale regardless of
                        // the user's volume = the "white noise at max volume" blast. Clamping the
                        // source first turns that garbage into a quiet (volume-level) signal. Valid
                        // PCM is already within [-1,1], so this changes nothing for real audio.
                        float r = ringBuffer_[idx];
                        if (r >  1.0f) r =  1.0f;
                        else if (r < -1.0f) r = -1.0f;
                        float s = r * vol * softStartGain(j);
                        if (s >  1.0f) s =  1.0f;
                        if (s < -1.0f) s = -1.0f;
                        if (shaped) {
                            float& err = (j & 1) ? nsErrorR_ : nsErrorL_;
                            float source = s * scale;
                            if (source <= -scale || source >= scale - 1.0f) {
                                err = 0.0f;   // 硬削波边界清误差
                                out[j] = static_cast<int16_t>(std::lrintf(source));
                            } else {
                                float input = source - err;
                                float rounded = std::floor(input + tpdf() + 0.5f);
                                float limited = rounded < -scale ? -scale
                                              : (rounded > scale - 1.0f ? scale - 1.0f : rounded);
                                err = (rounded == limited)
                                      ? std::max(-1.5f, std::min(1.5f, rounded - input)) : 0.0f;
                                out[j] = static_cast<int16_t>(limited);
                            }
                        } else {
                            // [V8.131] round-half-even instead of truncation: the plain
                            // C cast truncates toward zero, which turns quantization
                            // error into signal-correlated distortion (audible as sand /
                            // grain on tails and fades = the "electronic hiss"). 24-bit
                            // and 32-bit paths already use std::lrintf (V8.123); 16-bit
                            // was the only one left truncating.
                            float dithered = s * scale;
                            if (dither) dithered += tpdf();
                            // s is pre-clamped to [-1,1], so dithered fits int16 except
                            // for the ±1 LSB dither excursion at the rails; clamp exactly.
                            float rounded = std::lrintf(dithered);
                            if (rounded >  32767.0f) rounded =  32767.0f;
                            if (rounded < -32768.0f) rounded = -32768.0f;
                            out[j] = static_cast<int16_t>(rounded);
                        }
                    }
                    break;
                }
                case 24: {
                    // 銆怴3.2.7锟? 瀛楄妭 LE 鎵撳寘锛坅lt2 subslot=3锟?
                    uint8_t* out = buf + sampleOffset;
                    for (int j = 0; j < nSamples; ++j) {
                        int idx = ((rp * 2) + sampleCursor + j) & ringMask;
                        // [safety-fix 2026-10-22] clamp source to [-1,1] before volume (see 16-bit path):
                        // stops a glitched first block from saturating to full-scale white noise.
                        float r = ringBuffer_[idx];
                        if (r >  1.0f) r =  1.0f;
                        else if (r < -1.0f) r = -1.0f;
                        float s = r * vol * softStartGain(j);
                        if (s >  1.0f) s =  1.0f;
                        if (s < -1.0f) s = -1.0f;
                        int32_t v = std::lrintf(s * 8388607.0f);  // 无偏舍入(round-half-to-even)替代截断，消除负样本朝零截断的 -0.5LSB 直流偏置
                        out[j * 3]     = static_cast<uint8_t>(v & 0xFF);
                        out[j * 3 + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
                        out[j * 3 + 2] = static_cast<uint8_t>((v >> 16) & 0xFF);
                    }
                    break;
                }
                case 32: {
                    auto* out = reinterpret_cast<int32_t*>(buf + sampleOffset);
                    for (int j = 0; j < nSamples; ++j) {
                        int idx = ((rp * 2) + sampleCursor + j) & ringMask;
                        // [safety-fix 2026-10-22] clamp source to [-1,1] before volume (see 16-bit path):
                        // stops a glitched first block from saturating to full-scale white noise.
                        double r = static_cast<double>(ringBuffer_[idx]);
                        if (r >  1.0) r =  1.0;
                        else if (r < -1.0) r = -1.0;
                        double s = r * vol * softStartGain(j);
                        if (s >  1.0) s =  1.0;
                        if (s < -1.0) s = -1.0;
                        out[j] = static_cast<int32_t>(s * 2147483647.0);
                    }
                    break;
                }
                default:
                    memset(buf + sampleOffset, 0, nBytes);
                    break;
                }
            } else {
                // underrun for this packet: fill silence, don't play stale buffer data
                inUnderrun_ = true;
                armBreakMute("underrun", /*permanent=*/false);  // [safety] no data -> mute so the DAC can't free-run loud
                memset(buf + sampleOffset, 0, nBytes);
                // 【V9.20x】欠载补零统计：静音字节 + 静音帧
                mZeroFillBytes_.fetch_add((uint64_t)nBytes, std::memory_order_relaxed);
                mSilentFrames_.fetch_add((uint64_t)totalFramesNeeded, std::memory_order_relaxed);
            }
            sampleOffset += nBytes;
            sampleCursor += nFrames * channels_;
        }

        framesEmitted += totalFramesNeeded;   // [2026-09-10 soft-start]
        if (availFrames >= totalFramesNeeded) {
            readPos_.store((rp + totalFramesNeeded) % kRingFrames, std::memory_order_release);
            totalSamplesOut_.fetch_add(totalFramesNeeded, std::memory_order_relaxed);
            mSignalFrames_.fetch_add((uint64_t)totalFramesNeeded, std::memory_order_relaxed);
        } else {
            underrunCount_.fetch_add(1, std::memory_order_relaxed);
        }

        urbSlots_[slot].urb->buffer_length = sampleOffset;
        bufferWatermarkMs_.store(availFrames * 1000 / sampleRate_, std::memory_order_relaxed);
        if (!submitUrbRaw(slot)) {
            LOGE("streamLoop: submitUrbRaw[%d] failed, breaking", slot);
            break;
        }
        slot = (slot + 1) % kMaxUrbCount;
    }

    stopFeedbackUrb();   // discard + free feedback URB if armed
    streaming_.store(false, std::memory_order_release);
    LOGI("streamLoop: exit (%d URB completions)", urbCompletions);
}

bool UsbAudioDriver::submitFeedbackUrb() {
    // [P6] Allocate (once) and submit the feedback IN URB on the shared fd_.
    // Reuses the same usbdevfs_urb across re-arms (valid while streaming_ runs).
    // Using fd_ directly (instead of a re-opened node) is what makes this work on
    // Android: the app uid cannot openat() the usbfs node, so the old separate-fd
    // design always failed with EACCES and the loop never corrected the clock.
    if (fd_ < 0 || feedbackEp_ < 0) return false;
    // [V9.20r] NeriPlayer-style eligibility gate: never SUBMITURB into an endpoint whose
    // interface we failed to claim/activate — the kernel answers EBUSY (errno=16) and the
    // log spam buys nothing. The reason was logged once by activateFeedbackInterface().
    if (feedbackIneligibleReason_.load(std::memory_order_acquire) != kFeedbackOk ||
        !feedbackIfaceActive_.load(std::memory_order_acquire)) {
        // [DIAG] Surface the previously-silent early-return so a failed arming is never
        // invisible: reason tells us WHY (no_endpoint / ac_interface / claim_busy / ...),
        // active tells us whether activateFeedbackInterface() succeeded.
        LOGW("submitFeedbackUrb: skipped (reason=%s active=%d ep=0x%02X iface=%d) -> open-loop",
             feedbackReasonName(feedbackIneligibleReason_.load(std::memory_order_acquire)),
             feedbackIfaceActive_.load(std::memory_order_acquire) ? 1 : 0,
             feedbackEp_, feedbackEpIface_);
        return false;
    }
    if (!fbUrb_) {
        fbUrb_ = static_cast<usbdevfs_urb*>(
            calloc(1, sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc)));
        if (!fbUrb_) { LOGE("submitFeedbackUrb: urb alloc failed"); return false; }
        fbUrb_->buffer = calloc(1, 8);
        if (!fbUrb_->buffer) {
            LOGE("submitFeedbackUrb: buf alloc failed");
            free(fbUrb_); fbUrb_ = nullptr; return false;
        }
        fbUrb_->type = (feedbackEpAttr_ & 0x03) == 0x03
                           ? USBDEVFS_URB_TYPE_INTERRUPT : USBDEVFS_URB_TYPE_ISO;
        fbUrb_->endpoint = (uint8_t)(feedbackEp_ | 0x80);   // ensure IN direction
        fbUrb_->buffer_length = 8;
        fbUrb_->number_of_packets = 1;
        fbUrb_->iso_frame_desc[0].length = 8;
    }
    // [P6 fix] Mirror the proven audio OUT URB (submitUrbRaw): ISO_ASAP lets the HCD
    // pick the start frame automatically. Without it an explicit start_frame of 0 on a
    // HIGH-speed ISO endpoint is rejected by the kernel -> SUBMITURB returns EBUSY and
    // the loop silently falls back to open-loop (the long-playback noise returns).
    // Reset status to 0 so a reaped/re-submitted URB reads as idle (matches audio URB).
    fbUrb_->flags = USBDEVFS_URB_ISO_ASAP;
    fbUrb_->status = 0;
    int ret = ioctl(fd_, USBDEVFS_SUBMITURB, fbUrb_);
    if (ret < 0) {
        LOGW("submitFeedbackUrb: SUBMITURB failed (errno=%d type=%d ep=0x%02X flags=0x%x np=%d len=%d) -> open-loop fallback",
             errno, fbUrb_->type, fbUrb_->endpoint, fbUrb_->flags, fbUrb_->number_of_packets, fbUrb_->buffer_length);
        return false;
    }
    return true;
}

void UsbAudioDriver::processFeedbackUrb() {
    // [P6] Decode the just-completed feedback URB and update the rate correction that
    // streamLoop applies to samplesPerMicroframe (line ~1658). Closed-loop on a real
    // async DAC removes the long-playback clock drift that otherwise noises the output.
    int got = fbUrb_->iso_frame_desc[0].actual_length;
    if (got >= 3) {
        const uint8_t* b = static_cast<const uint8_t*>(fbUrb_->buffer);
        uint32_t raw = (uint32_t)b[0] | ((uint32_t)b[1] << 8) | ((uint32_t)b[2] << 16);
        if (feedbackEpBitRes_ >= 16 && got >= 4) raw |= ((uint32_t)b[3] << 24);
        // feedback value = samples the DAC wants per (micro)frame
        double perFrame = (feedbackEpBitRes_ >= 16) ? (raw / 65536.0) : (raw / 16384.0);
        double measured = perFrame * (highSpeed_ ? 8000.0 : 1000.0);
        // [P6][FIX] Plausibility guard: a genuine async feedback EP reports a value within
        // a fraction of a percent of the nominal rate — it only corrects tiny clock drift
        // (real async feedback stays within ~0.1% of nominal). Tight window: reject
        // anything outside +/-5% as garbage. The old 0.5x-2.0x window admitted TTGK's ~2x
        // capture bytes, and the interim +/-15% window still let 0.9x/1.45x garbage slip
        // through and warp the clock. A wrong endpoint keeps reporting garbage, so after 16
        // consecutive bad reads we disable the loop and run clean open-loop (no mute).
        double expect = (double)(sampleRate_ > 0 ? sampleRate_ : 48000);
        if (measured < expect * 0.95 || measured > expect * 1.05) {
            feedbackBadCount_++;
            if (feedbackLogCount_ < 5 || feedbackBadCount_ <= 8) {
                LOGW("feedback read implausible: raw=0x%06X measured=%.0fHz (want ~%.0fHz) bad=%d -> ignored",
                     raw, measured, expect, feedbackBadCount_);
            }
            if (feedbackBadCount_ >= 16) {
                LOGW("feedback endpoint 0x%02X consistently implausible (%d reads) -> disabling closed-loop (open-loop)",
                     feedbackEp_, feedbackBadCount_);
                feedbackIneligibleReason_.store(kFeedbackBadData, std::memory_order_release);
                feedbackActive_.store(false, std::memory_order_release);
            }
            return;
        }
        feedbackBadCount_ = 0;
        double ppm = (measured / expect - 1.0) * 1e6;
        // low-pass + clamp to +-3000 ppm to avoid runaway / oscillation
        double target = std::max(-3000.0, std::min(3000.0, ppm)) / 1e6;
        rateCorrection_ = 0.98 * rateCorrection_ + 0.02 * target;
        feedbackMeasuredRate_.store(measured);
        feedbackCorrectionPpm_.store(rateCorrection_ * 1e6);
        if (feedbackLogCount_ < 5) {
            feedbackLogCount_++;
            LOGI("feedback read: raw=0x%06X measured=%.1fHz ppm=%.1f -> correction=%.1fppm",
                 raw, measured, ppm, rateCorrection_ * 1e6);
        }
    }
}

void UsbAudioDriver::stopFeedbackUrb() {
    // [P6] Discard any in-flight feedback URB and free its resources. Called from
    // streamLoop exit and from the destructor for a clean teardown.
    if (fbUrb_) {
        if (fd_ >= 0) ioctl(fd_, USBDEVFS_DISCARDURB, fbUrb_);  // EINVAL/ENODEV harmless
        free(fbUrb_->buffer);
        free(fbUrb_);
        fbUrb_ = nullptr;
    }
    feedbackActive_.store(false, std::memory_order_release);
}

// 鈹€鈹€ findClockSourceId 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

int UsbAudioDriver::findClockSourceId() {
    if (!isUac2_) return -1;

    // Read the raw configuration descriptor
    uint8_t desc[4096];
    int len = readConfigDescriptor(0, desc, sizeof(desc));
    if (len < 0) return -1;

    // [align Salt] Full chain: AS General (streaming iface) bTerminalLink
    // -> Input Terminal bTerminalID -> Input Terminal bCSourceID (= clock source).
    // Salt reports terminalLink=14 -> clockSource=30 on Realtek 4BA6. There are
    // THREE Input Terminals (bCSourceID 27/27/30); picking the first is wrong.

    // Pass 1: build map bTerminalID -> bCSourceID from Input Terminals.
    int termToClock[64];
    for (int i = 0; i < 64; i++) termToClock[i] = -1;
    int pos = 0;
    while (pos + 2 < len) {
        uint8_t dLen  = desc[pos];
        uint8_t dType = (pos + 1 < len) ? desc[pos + 1] : 0;
        uint8_t dSub  = (pos + 2 < len) ? desc[pos + 2] : 0;
        if (dType == UAC2_CS_INTERFACE && dSub == 0x02 /*INPUT_TERMINAL*/ && dLen >= 8) {
            int termId = desc[pos + 3];  // bTerminalID
            int csId   = desc[pos + 7];  // bCSourceID
            if (termId >= 0 && termId < 64) termToClock[termId] = csId;
            LOGI("InputTerminal: bTerminalID=%d bCSourceID=%d", termId, csId);
        }
        pos += (dLen > 0) ? dLen : 1;
        if (dLen == 0) break;
    }

    // Pass 1.5: build map clockEntityId -> {subtype, resolvedSourceId}.
    // [2026-10-22] Also collect every Clock Source entity id into clockEntityIds_
    // (cleared here, refilled every call) as SET_CUR retry candidates.
    clockEntityIds_.clear();
    // BTR5 (2972:0047) uses a TWO-level clock: Input Terminal -> bCSourceID=40
    // is a Clock Selector (subtype 0x0B), whose baCSourceID[0]=41 is the real
    // Clock Source. SET_CUR must go to the SOURCE (41), never the selector (40)
    // — sending SET_CUR to a selector returns EPIPE (errno=32) and stalls the
    // whole control pipe (the exact BTR5 failure: SET_CUR ret=-1 EPIPE, then
    // setInterfaceAlt errno=110/71). Salt reports selectedClockSources=[41@0].
    int clockSubtype[64];
    int clockSrcPin[64];
    for (int i = 0; i < 64; i++) { clockSubtype[i] = -1; clockSrcPin[i] = -1; }
    pos = 0;
    while (pos + 2 < len) {
        uint8_t dLen  = desc[pos];
        uint8_t dType = (pos + 1 < len) ? desc[pos + 1] : 0;
        uint8_t dSub  = (pos + 2 < len) ? desc[pos + 2] : 0;
        if (dType == UAC2_CS_INTERFACE && dLen >= 6) {
            if (dSub == UAC2_CS_CLOCK_SOURCE && dLen >= 8) {
                int id = desc[pos + 3];
                if (id >= 0 && id < 64) { clockSubtype[id] = 0x0A; clockSrcPin[id] = id; }
                clockEntityIds_.push_back(id);   // [2026-10-22] SET_CUR retry candidate
                LOGI("ClockEntity: id=%d subtype=SOURCE", id);
            } else if (dSub == UAC2_CS_CLOCK_SELECTOR) {
                int id     = desc[pos + 3];   // bClockID
                int nrPins = desc[pos + 4];   // bNrInPins
                int pin0   = (nrPins > 0 && dLen >= 6) ? desc[pos + 5] : -1;  // baCSourceID[0]
                if (id >= 0 && id < 64) { clockSubtype[id] = 0x0B; clockSrcPin[id] = pin0; }
                LOGI("ClockEntity: id=%d subtype=SELECTOR pins=%d pin0=%d", id, nrPins, pin0);
            } else if (dSub == 0x0C /*CLOCK_MULTIPLIER*/) {
                int id  = desc[pos + 3];
                int src = desc[pos + 4];       // bCSourceID
                if (id >= 0 && id < 64) { clockSubtype[id] = 0x0C; clockSrcPin[id] = src; }
                LOGI("ClockEntity: id=%d subtype=MULTIPLIER src=%d", id, src);
            }
        }
        pos += (dLen > 0) ? dLen : 1;
        if (dLen == 0) break;
    }

    // Resolve a clock entity id to its terminal Clock Source, following
    // Selector/Multiplier chains (up to 8 hops, cycle-guarded).
    auto resolveClock = [&](int id) -> int {
        int cur = id;
        for (int hop = 0; hop < 8; ++hop) {
            if (cur < 0 || cur >= 64) break;
            int st = clockSubtype[cur];
            if (st == 0x0A) return cur;                       // Clock Source
            if (st == 0x0B || st == 0x0C) {
                int nxt = clockSrcPin[cur];
                if (nxt > 0 && nxt != cur) { cur = nxt; continue; }
                return cur;                                   // unresolvable selector
            }
            break;                                            // unknown entity
        }
        return cur;
    };

    // Pass 2: find AS General bTerminalLink, prefer the one on OUR streaming iface.
    // [2026-10-22 修复] Realtek 4BA6 keep-claim 重启时解析掉进「第一个 Clock Source
    // 实体」兜底（选中 clock 27——另一条音频路的时钟），SET_CUR 全部 EPIPE，DAC 时钟
    // 永远没设上 → 变调播放 + ring 打满 → STALL 看门狗杀歌。旧逻辑只认 ifaceNum_ 匹配
    // 的那一条 link，匹配不上就直接实体兜底；而 ifaceNum_ 在重启路径上可能对不上
    // （枚举竞态/接口号缓存）。现在：先试我们的 link，再试描述符里每一条 link，
    // 全部解析失败才允许实体兜底。link 是「路径感知」的，实体顺序不是。
    int ourLink = -1;
    int otherLinks[24];
    int nOther = 0;
    int curIface = -1;
    pos = 0;
    while (pos + 2 < len) {
        uint8_t dLen  = desc[pos];
        uint8_t dType = (pos + 1 < len) ? desc[pos + 1] : 0;
        uint8_t dSub  = (pos + 2 < len) ? desc[pos + 2] : 0;
        if (dType == USB_DT_INTERFACE && dLen >= 9) {
            curIface = desc[pos + 2];
        }
        if (dType == UAC2_CS_INTERFACE && dSub == 0x01 /*AS_GENERAL*/ && dLen >= 4) {
            int link = desc[pos + 3];  // bTerminalLink
            LOGI("AS General: iface=%d bTerminalLink=%d (ourIfaceNum=%d)", curIface, link, ifaceNum_);
            if (curIface == ifaceNum_) {
                ourLink = link;   // 多个 alt 同 link，最后一个即最终值
            } else {
                bool dup = false;
                for (int k = 0; k < nOther; ++k) if (otherLinks[k] == link) { dup = true; break; }
                if (!dup && nOther < 24) otherLinks[nOther++] = link;
            }
        }
        pos += (dLen > 0) ? dLen : 1;
        if (dLen == 0) break;
    }

    auto tryLink = [&](int link) -> int {
        if (link >= 0 && link < 64 && termToClock[link] >= 0) {
            int resolved = resolveClock(termToClock[link]);
            LOGI("Clock source via terminalLink=%d -> bCSourceID=%d -> resolved=%d", link, termToClock[link], resolved);
            return resolved;
        }
        return -1;
    };
    if (ourLink >= 0) {
        int r = tryLink(ourLink);
        if (r >= 0) return r;
        LOGW("findClockSourceId: our link=%d did not resolve (termToClock=%d) — trying other links",
             ourLink, (ourLink >= 0 && ourLink < 64) ? termToClock[ourLink] : -1);
    } else {
        LOGW("findClockSourceId: no AS General on our iface=%d — trying all links", ifaceNum_);
    }
    for (int k = 0; k < nOther; ++k) {
        int r = tryLink(otherLinks[k]);
        if (r >= 0) return r;
    }

    // Fallback: first CS_CLOCK_SOURCE descriptor
    pos = 0;
    while (pos + 2 < len) {
        uint8_t dLen  = desc[pos];
        uint8_t dType = (pos + 1 < len) ? desc[pos + 1] : 0;
        uint8_t dSub  = (pos + 2 < len) ? desc[pos + 2] : 0;
        if (dType == UAC2_CS_INTERFACE && dSub == UAC2_CS_CLOCK_SOURCE && dLen >= 8) {
            int clockId = desc[pos + 3];
            LOGI("Found UAC2 Clock Source: id=%d, len=%d", clockId, dLen);
            return clockId;
        }
        pos += (dLen > 0) ? dLen : 1;
        if (dLen == 0) break;
    }

    LOGW("No UAC2 Clock Source descriptor found");
    return -1;
}

// 鈹€鈹€ findFeedbackEndpoint 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

int UsbAudioDriver::findFeedbackEndpoint() {
    // [V9.20r] Pessimistic default: every early return below leaves "no endpoint", which
    // is the correct reason when the descriptor walk cannot even run.
    feedbackIneligibleReason_.store(kFeedbackNoEndpoint, std::memory_order_release);
    if (fd_ < 0) return -1;

    // [FIX] TTGK Audio 3302:33C0 (the user's DAC) exposes only ONE IN isochronous
    // endpoint — the capture/recording EP 0x81 on iface 2. It has NO genuine playback
    // async-feedback endpoint. Treating its capture EP as feedback fed capture audio
    // bytes in as a ~2x bogus frequency and warped the playback clock -> pops AND
    // stutter ("卡卡"). This DAC is synchronous/adaptive: the correct mode is open-loop
    // (host clock == DAC clock via USB SOF), which the soft-start already handles.
    // Skip the feedback search entirely and run open-loop: no closed loop, no break-mute.
    if (vid_ == 0x3302 && pid_ == 0x33C0) {
        feedbackIneligibleReason_.store(kFeedbackNoEndpoint, std::memory_order_release);
        LOGI("feedback: TTGK 3302:33C0 has no usable playback feedback EP (only capture IN 0x81) -> open-loop");
        return -1;
    }

    uint8_t desc[4096];
    int len = readConfigDescriptor(0, desc, sizeof(desc));
    if (len < 0) return -1;

    int pos = 0;
    int curIface = -1, curAlt = -1;
    while (pos < len) {
        uint8_t dLen  = desc[pos];
        uint8_t dType = (pos + 1 < len) ? desc[pos + 1] : 0;
        uint8_t dSub  = (pos + 2 < len) ? desc[pos + 2] : 0;

        // Track which interface/alt we are currently inside, so we can record the
        // owner of the feedback endpoint (often a DIFFERENT interface than the OUT EP).
        if (dType == USB_DT_INTERFACE && dLen >= USB_DT_INTERFACE_SIZE) {
            curIface = desc[pos + 2];  // bInterfaceNumber
            curAlt   = desc[pos + 3];  // bAlternateSetting
        }

        // UAC 2.0: AS Isochronous Data Endpoint descriptor -> bmAttributes can signal
        // explicit feedback. Per USB spec bmAttributes bits 4-5 are the ENDPOINT USAGE
        // TYPE: 0x00 = data endpoint, 0x10 = feedback endpoint, 0x20 = implicit-feedback
        // data endpoint. Only an IN iso endpoint explicitly marked as feedback (0x10) is a
        // valid async-rate source for the byte-decode in processFeedbackUrb(); implicit
        // feedback (0x20) carries audio, not a rate value, and a plain data endpoint
        // (0x00) is the capture/recording stream. The old "any IN iso != OUT" heuristic
        // grabbed the capture IN endpoint (e.g. TTGK 3302:33C0 iface 2 EP 0x81) and fed
        // its audio bytes into the rate controller as a bogus ~2x frequency -> clock warp
        // -> the exact noise we were trying to fix.
        if (dType == USB_DT_ENDPOINT && dLen >= 7) {
            uint8_t epAddr = desc[pos + 2];
            uint8_t epAttr = desc[pos + 3];
            uint8_t epUsage = epAttr & 0x30;
            // IN, isochronous, different from our OUT endpoint, AND explicitly a feedback EP
            if ((epAddr & 0x80) && (epAttr & 0x03) == 0x01 && epAddr != epAddress_) {
                // IN isochronous endpoint that isn't our OUT EP. Accept it ONLY when it is
                // explicitly a feedback endpoint (usage bits 0x10). Otherwise it is a data
                // or implicit-feedback endpoint — i.e. the capture/recording IN stream —
                // and must be skipped: feeding its bytes into the rate controller as a
                // "frequency" warps the playback clock (the TTGK 3302:33C0 iface2 EP 0x81
                // case that reintroduced the noise).
                if (epUsage == 0x10) {
                LOGI("Found explicit feedback endpoint: 0x%02X (usage=feedback)", epAddr);
                // [fix] Keep the FULL 8-bit bEndpointAddress (incl. bit7 direction).
                // Stripping to & 0x7F turns 0x81 -> 0x01 (OUT), which the kernel
                // rejects with -EINVAL/-EPIPE when we later submit an IN URB on it.
                // [P6] Capture EP attributes so the feedback thread can read it back.
                feedbackEpAttr_ = epAttr;
                feedbackEpInterval_ = desc[pos + 6];
                feedbackEpMaxPacket_ = desc[pos + 4] | (desc[pos + 5] << 8);
                // 16.16 (4-byte) feedback when the EP can carry >=4 bytes, else 10.14 (3-byte).
                feedbackEpBitRes_ = (feedbackEpMaxPacket_ >= 4) ? 16 : 14;
                feedbackEpIface_ = curIface;
                feedbackEpAlt_   = curAlt;
                LOGI("feedback EP 0x%02X: attr=0x%02X interval=%d maxPacket=%d bitRes=%d iface=%d alt=%d",
                     epAddr, feedbackEpAttr_, feedbackEpInterval_, feedbackEpMaxPacket_, feedbackEpBitRes_, curIface, curAlt);
                // [V9.20r] Static eligibility: a real async feedback EP was found, so clear
                // the pessimistic kFeedbackNoEndpoint default set at function entry and let
                // the closed loop arm. Inherently-open-loop DACs (none found here) keep
                // kFeedbackNoEndpoint and never arm -> no clock drift because no warp is applied.
                feedbackIneligibleReason_.store(kFeedbackOk, std::memory_order_release);
                if (curIface == 0) {
                    // The AudioControl interface carries no streaming endpoints; a
                    // "feedback EP" reported there is a descriptor quirk — never arm it.
                    feedbackIneligibleReason_.store(kFeedbackOnAcInterface, std::memory_order_release);
                    LOGW("feedback EP 0x%02X reported on AC iface 0 — closed-loop disabled (reason=ac_interface)", epAddr);
                }
                return epAddr;
                }
                LOGI("skip EP 0x%02X as feedback: usage=0x%02X (not explicit feedback 0x10) iface=%d alt=%d",
                     epAddr, epUsage, curIface, curAlt);
            }
        }

        pos += (dLen > 0) ? dLen : 1;
        if (dLen == 0) break;
    }

    // [V9.20r] Walk completed without a feedback endpoint: the reason set at function
    // entry (kFeedbackNoEndpoint) stands, so the closed loop is never armed.
    return -1;
}

// 鈹€鈹€ parseSupportedRates 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

void UsbAudioDriver::parseSupportedRates() {
    supportedRates_.clear();

    uint8_t desc[4096];
    int len = readConfigDescriptor(0, desc, sizeof(desc));
    if (len < 0) {
        supportedRates_ = "unknown (cannot read descriptor)";
        return;
    }

    // Scan for Type I Format descriptors in both UAC 1.0 and UAC 2.0
    int pos = 0;
    while (pos < len) {
        uint8_t dLen  = desc[pos];
        uint8_t dType = (pos + 1 < len) ? desc[pos + 1] : 0;
        uint8_t dSub  = (pos + 2 < len) ? desc[pos + 2] : 0;

        // FORMAT_TYPE descriptor (subtype 0x02) exists in BOTH UAC1 and UAC2.
        // UAC 1.0: +3=bFormatType +4=bNrChannels +5=bSubframeSize +6=bBitResolution
        //          +7=bSamFreqType +8=tSamFreq[N] (3 bytes each)
        // UAC 2.0: +3=bFormatType +4=bSubslotSize +5=bBitResolution
        //          (rates live in Clock Source, NOT here — parseClockRanges handles them)
        bool isFormatDesc = (dType == UAC2_CS_INTERFACE) && (dSub == 0x02);

        if (isFormatDesc && dLen >= 8) {
            if (!isUac2_) {
                // UAC1: read bSamFreqType then N × 3-byte tSamFreq entries.
                uint8_t nRates = desc[pos + 7];
                int base = pos + 8;
                for (int i = 0; i < nRates && base + i * 3 + 2 < len; ++i) {
                    uint32_t rate = desc[base + i * 3]
                                  | (desc[base + i * 3 + 1] << 8)
                                  | (desc[base + i * 3 + 2] << 16);
                    // sanity: valid audio sample rate range
                    if (rate >= 8000 && rate <= 768000) {
                        char buf[32];
                        snprintf(buf, sizeof(buf), "%s%u",
                                 supportedRates_.empty() ? "" : " ", rate);
                        supportedRates_ += buf;
                    }
                }
            }
            // UAC2: nothing here — rates come from clock source ranges.
        }

        pos += (dLen > 0) ? dLen : 1;
        if (dLen == 0) break;
    }

    if (supportedRates_.empty()) {
        // UAC2: rates live in Clock Source (not FORMAT_TYPE). If firmware doesn't
        // expose GET_RANGE, we can't read the real list — be honest instead of
        // pretending "only 48000" (which misled audiophiles into thinking the
        // DAC only does 48k). Actual rate switching still works via SET_CUR.
        supportedRates_ = isUac2_ ? "unknown (clock ranges not exposed)" : "only 48000";
    }

    LOGI("Supported sample rates: %s", supportedRates_.c_str());
}

// 鈹€鈹€ parseAltCandidates 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
// Scan all AudioStreaming alternate settings and build a candidate table.
// Each candidate has ifaceNum (bInterfaceNumber for SETINTERFACE), alt,
// endpoint address, maxPacketSize, channels, subslot (bytes-per-sample),
// bit resolution, associated clock source ID, and sample rate.
//
// This replaces the old hardcoded alt1=16bit/alt2=24bit/alt3=32bit assumption
// with real descriptor-driven discovery that works with any UAC-compliant DAC.
// 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

void UsbAudioDriver::parseAltCandidates() {
    altCandidates_.clear();

    uint8_t desc[4096];
    // Two-step: read header (9 bytes) first to get wTotalLength, then full read
    int hdrLen = readConfigDescriptor(0, desc, 9);
    if (hdrLen < 9) {
        LOGW("parseAltCandidates: cannot read config header (got %d bytes)", hdrLen);
        return;
    }
    uint16_t wTotalLen = desc[2] | (desc[3] << 8);
    LOGI("parseAltCandidates: wTotalLength=%u", wTotalLen);
    int len = readConfigDescriptor(0, desc, wTotalLen > sizeof(desc) ? sizeof(desc) : wTotalLen);
    if (len < 0) {
        LOGW("parseAltCandidates: cannot read config descriptor");
        return;
    }

    // 鈹€鈹€ First pass: build map of UAC2 clock source entities 鈹€鈹€
    // clockId 锟?{ offset in desc for later sampling-frequency range read }
    struct ClkInfo { int id; int offset; };
    std::vector<ClkInfo> clocks;
    if (isUac2_) {
        int pos = 0;
        while (pos + 3 < len) {
            uint8_t dLen = desc[pos];
            uint8_t dType = (pos + 1 < len) ? desc[pos + 1] : 0;
            uint8_t dSub = (pos + 2 < len) ? desc[pos + 2] : 0;
            if (dType == UAC2_CS_INTERFACE && dSub == UAC2_CS_CLOCK_SOURCE && dLen >= 8) {
                clocks.push_back({desc[pos + 3], pos});
            }
            pos += (dLen > 0) ? dLen : 1;
            if (dLen == 0) break;
        }
    }

    // 鈹€鈹€ Second pass: walk to find AudioStreaming interface descriptors 鈹€鈹€
    // We look for USB_DT_INTERFACE with bInterfaceClass=AUDIO, bInterfaceSubclass=STREAMING,
    // bAlternateSetting > 0 (skip alt=0 idle). Then inside each AS interface we capture:
    //   - Clock Source linkage (bTerminalLink in input terminal, or CS_CLOCK_SOURCE in UAC2)
    //   - Endpoint descriptor with bmAttributes=ISOCHRONOUS, direction=OUT
    //   - Format descriptor (CS_INTERFACE, subtype FORMAT_TYPE / FORMAT_TYPE_I)
    int pos = 0;
    int curIfaceNum = -1, curIfaceClass = -1, curIfaceSubclass = -1, curAlt = -1;
    int curClockId = -1;
    int curEpAddr = -1, curMaxPkt = 0, curBInterval = 1;
    int curChannels = 2, curSubslot = 0, curRes = 0, curRate = 0;
    bool inStreamingIface = false, inEndpoint = false, inFormatDesc = false;

    auto resetAltState = [&]() {
        curEpAddr = -1; curMaxPkt = 0; curBInterval = 1;
        curChannels = 2; curSubslot = 0; curRes = 0; curRate = 0;
        inEndpoint = false; inFormatDesc = false;
    };

    auto flushCandidate = [&]() {
        if (inStreamingIface && curAlt > 0 && curEpAddr > 0 && curMaxPkt > 0) {
            // Don't require format descriptor 锟?some DACs only declare rate via clock source,
            // which we read in parseClockRanges(). Mark rate=0 as multi-rate.
            altCandidates_.push_back({
                curIfaceNum, curAlt, curEpAddr, curMaxPkt, curBInterval,
                curChannels, curSubslot, curRes, curClockId, curRate
            });
        }
        curEpAddr = -1; curMaxPkt = 0; curBInterval = 1;
        curChannels = 2; curSubslot = 2; curRes = 16; curRate = 0;
        inEndpoint = false; inFormatDesc = false;
    };

    LOGI("parseAlt: desc total len=%zu", (size_t)len);
    while (pos + 2 < len) {
        uint8_t dLen = desc[pos];
        uint8_t dType = (pos + 1 < len) ? desc[pos + 1] : 0;
        LOGI("parseAlt: pos=%d dType=0x%02x dLen=%d", (int)pos, dType, (int)dLen);
        if (dLen == 0) { LOGI("parseAlt: BREAK dLen=0 at pos=%d", (int)pos); break; }
        if (dLen < 2 || pos + dLen > len) { LOGI("parseAlt: BREAK dLen=%d pos+dLen=%zu > len=%zu", (int)dLen, (size_t)(pos+dLen), (size_t)len); break; }

        switch (dType) {
        case USB_DT_INTERFACE:  // 0x04
            if (dLen >= 9) {
                flushCandidate();
                curIfaceNum   = desc[pos + 2];  // bInterfaceNumber
                curAlt        = desc[pos + 3];  // bAlternateSetting
                curIfaceClass = desc[pos + 5];
                curIfaceSubclass = desc[pos + 6];
                // 1=IAD, 3=HID descriptor follow, skip non-audio
                LOGI("parseAlt: iface=%d alt=%d class=%d subclass=%d", curIfaceNum, curAlt, curIfaceClass, curIfaceSubclass);
                inStreamingIface = (curIfaceClass == 1/*AUDIO*/ && curIfaceSubclass == 2/*STREAMING*/ && curAlt > 0);
                if (!inStreamingIface) {
                    // Reset clockId on any interface boundary for safety
                    curClockId = -1;
                }
            }
            break;

        case USB_DT_ENDPOINT:  // 0x05
            if (inStreamingIface && dLen >= 7) {
                uint8_t epAddr = desc[pos + 2];
                uint8_t epAttr = desc[pos + 3];
                bool isIsoOut = ((epAttr & 0x03) == 0x01/*ISO*/) && ((epAddr & 0x80) == 0/*OUT*/);
                if (isIsoOut) {
                    curEpAddr   = epAddr;
                    curMaxPkt   = desc[pos + 4] | (desc[pos + 5] << 8);
                    curBInterval = desc[pos + 6];
                    inEndpoint  = true;
                }
            }
            break;

        case UAC2_CS_INTERFACE:  // 0x24 锟?class-specific interface descriptor
            if (inStreamingIface && dLen >= 3) {
                uint8_t dSub = desc[pos + 2];
                // FORMAT_TYPE = subtype 0x02 in BOTH UAC1 and UAC2.
                // subtype 0x01 inside AudioStreaming is AS_GENERAL (different layout!).
                if (dSub == 0x02 && dLen >= 6) {
                    inFormatDesc = true;
                    if (isUac2_) {
                        // UAC2 FORMAT_TYPE: +3=bFormatType +4=bSubslotSize +5=bBitResolution
                        curSubslot = desc[pos + 4];
                        curRes = desc[pos + 5];
                    } else {
                        // UAC1 FORMAT_TYPE: +4=bNrChannels +5=bSubframeSize(subslot)
                        //                  +6=bBitResolution +7=bSamFreqType +8=tSamFreq[N] (3B each)
                        curChannels = desc[pos + 4];
                        curSubslot = desc[pos + 5];
                        curRes = desc[pos + 6];
                        uint8_t nRates = desc[pos + 7];
                        // multi-rate alt (bSamFreqType>1): leave rate=0 → selectAltForRate
                        // won't filter by rate; actual rate switched via SET_CUR (UAC1 has
                        // no clock entity, so no GET_MIN/GET_MAX ranges exist).
                        if (nRates == 1 && dLen >= 11) {
                            curRate = desc[pos + 8] | (desc[pos + 9] << 8) | (desc[pos + 10] << 16);
                        }
                    }
                }
            }
            break;
        }

        pos += dLen;
    }
    flushCandidate();  // last one

    // 鈹€鈹€ Post-process: for UAC2, try to link candidates to clock sources 鈹€鈹€
    // via the CS interface descriptor chain (AS general has bTerminalLink).
    // We re-walk to find input terminal 锟?clock association.
    if (isUac2_ && !clocks.empty()) {
        pos = 0;
        curClockId = clocks[0].id;  // default: first clock
        while (pos + 2 < len) {
            uint8_t dLen = desc[pos];
            uint8_t dType = (pos + 1 < len) ? desc[pos + 1] : 0;
            uint8_t dSub = (pos + 2 < len) ? desc[pos + 2] : 0;
            if (dLen < 2 || pos + dLen > len) break;

            if (dType == USB_DT_INTERFACE && dLen >= 9) {
                // Flush previous alt candidate before switching
                flushCandidate();
                curIfaceNum = desc[pos + 2];
                curAlt = desc[pos + 3];
                curIfaceClass = desc[pos + 5];
                curIfaceSubclass = desc[pos + 6];
                inStreamingIface = (curIfaceClass == 1 && curIfaceSubclass == 2);
                resetAltState();
            }

            // UAC2 Input Terminal descriptor: subtype 0x02, has wTerminalType + bCSourceID
            if (dType == UAC2_CS_INTERFACE && dSub == 0x02 && dLen >= 8) {
                int terminalClockId = desc[pos + 7];  // bCSourceID (clock source ID)
                // Input Terminal is on AudioControl interface, its clock applies to ALL AudioStreaming candidates
                LOGI("parseAlt: InputTerminal clockId=%d on iface=%d", terminalClockId, curIfaceNum);
                for (auto& c : altCandidates_) {
                    if (c.ifaceNum == curIfaceNum && c.alt > 0 && c.clockId < 0) {
                        c.clockId = terminalClockId;
                    }
                }
                // If no candidate on this iface, set on ALL unassociated candidates
                bool any = false;
                for (auto& c : altCandidates_) { if (c.clockId == terminalClockId) any = true; }
                if (!any) {
                    for (auto& c : altCandidates_) {
                        if (c.clockId < 0) c.clockId = terminalClockId;
                    }
                }
            }

            pos += dLen;
        }
    }

    // 鈹€鈹€ Also capture acIface_ (AudioControl interface number) for clock GET_RANGE 鈹€鈹€
    acIface_ = 0;  // default
    pos = 0;
    while (pos + 9 < len) {
        uint8_t dLen = desc[pos];
        if (dLen < 9) { pos += (dLen > 0) ? dLen : 1; continue; }
        uint8_t dType = desc[pos + 1];
        if (dType == USB_DT_INTERFACE) {
            if (desc[pos + 5] == 1/*AUDIO*/ && desc[pos + 6] == 1/*AUDIOCONTROL*/) {
                acIface_ = desc[pos + 2];  // bInterfaceNumber
                break;
            }
        }
        pos += dLen;
    }

    LOGI("parseAltCandidates: %zu candidates, acIface=%d", altCandidates_.size(), acIface_);
}


// ===========================================================================
// parseClockRanges: read clock sub-ranges via UAC2 GET_RANGE
// ===========================================================================

void UsbAudioDriver::parseClockRanges() {
    clockRanges_.clear();
    if (fd_ < 0) return;

    // UAC1: no clock source entity — sample rates live in FORMAT_TYPE tSamFreq,
    // already parsed into supportedRates_ by parseSupportedRates(). Build
    // single-point ranges from them (exclude placeholder strings).
    if (!isUac2_) {
        std::string rates = supportedRates_;
        if (!rates.empty() && rates != "unknown (cannot read descriptor)" && rates != "only 48000") {
            std::string token;
            for (size_t i = 0; i <= rates.size(); ++i) {
                if (i == rates.size() || rates[i] == ' ') {
                    if (!token.empty()) {
                        int rate = atoi(token.c_str());
                        if (rate > 0) clockRanges_.push_back({rate, rate, 0, 0});
                        token.clear();
                    }
                } else if (rates[i] >= '0' && rates[i] <= '9') {
                    token += rates[i];
                }
            }
        }
        LOGI("parseClockRanges: %zu ranges (UAC1 tSamFreq)", clockRanges_.size());
        return;
    }

    // Gather unique clock IDs from candidates
    std::vector<int> clockIds;
    for (auto& c : altCandidates_) {
        if (c.clockId > 0) {
            bool found = false;
            for (int id : clockIds) if (id == c.clockId) { found = true; break; }
            if (!found) clockIds.push_back(c.clockId);
        }
    }

    if (clockIds.empty()) {
        int cid = findClockSourceId();
        if (cid > 0) clockIds.push_back(cid);
    }
    if (clockIds.empty()) {
        for (int cid = 3; cid <= 10; ++cid) clockIds.push_back(cid);
    }

    for (int cid : clockIds) {
        // [v6.x] UAC2 GET_MIN/GET_MAX/GET_RES — three separate 4-byte requests.
        // Old single GET_RANGE(12 bytes) returned garbage on TTGK/Realtek:
        // they return only 4 bytes, the rest is uninitialized stack data.
        uint8_t minData[4] = {}, maxData[4] = {}, resData[4] = {};
        auto read4 = [&](uint8_t req, uint8_t* buf) -> int {
            struct usbdevfs_ctrltransfer ct = {};
            ct.bRequestType = 0xA1;
            ct.bRequest     = req;
            ct.wValue       = 0x0100;  // CS_SAM_FREQ_CONTROL
            ct.wIndex       = (uint16_t)((cid << 8) | (acIface_ & 0xFF));
            ct.wLength      = 4;
            ct.timeout      = 200;
            ct.data         = buf;
            int r = ioctl(fd_, USBDEVFS_CONTROL, &ct);
            if (r < 4) {
                LOGW("getMin/Max/Res clkId=%d req=0x%02X failed ret=%d", cid, req, r);
                return -1;
            }
            return 0;
        };

        if (read4(0x82, minData) == 0 && read4(0x83, maxData) == 0) {
            uint32_t rMin = minData[0] | (minData[1]<<8) | (minData[2]<<16) | (minData[3]<<24);
            uint32_t rMax = maxData[0] | (maxData[1]<<8) | (maxData[2]<<16) | (maxData[3]<<24);
            int rRes = 0;
            if (read4(0x84, resData) == 0) {
                rRes = (int)(resData[0] | (resData[1]<<8) | (resData[2]<<16) | (resData[3]<<24));
            }
            clockRanges_.push_back({(int)rMin, (int)rMax, rRes, cid});
        }
    }

    // UAC2: no tSamFreq fallback. If GET_RANGE fails (firmware like TTGK that
    // doesn't implement GET_MIN/GET_MAX), leave clockRanges_ empty so
    // selectAltForRate skips the rate gate and picks alt by subslot; the rate
    // is then switched via SET_CUR in start().
    LOGI("parseClockRanges: %zu ranges (UAC2 clock source)", clockRanges_.size());
}

// ===========================================================================
// selectAltForRate: pick best alt setting from parsed candidates
// ===========================================================================

int UsbAudioDriver::selectAltForRate(int targetRate, int targetBits, int targetChannels) {
    // Step 1: clock range check
    if (!clockRanges_.empty()) {
        bool rateOk = false;
        for (auto& cr : clockRanges_) {
            if (targetRate >= cr.min && targetRate <= cr.max) {
                rateOk = true;
                break;
            }
        }
        if (!rateOk) {
            LOGE("selectAltForRate: %d Hz outside all clock ranges", targetRate);
            return -1;
        }
    }

    if (altCandidates_.empty()) {
        LOGE("selectAltForRate: no candidates");
        return -1;
    }

    // Step 2: filter + score
    struct Match { DacAltCandidate cand; int score = 0; };
    std::vector<Match> matches;
    int reqSubslot = (targetBits + 7) / 8;

    for (auto& c : altCandidates_) {
        if (c.channels != targetChannels && c.channels != 0) continue;
        if (c.sampleRate != 0 && c.sampleRate != targetRate) continue;
        if (c.subslot < reqSubslot) continue;

        int score = 0;
        if (c.sampleRate == targetRate) score += 1000;
        if (c.subslot == reqSubslot) score += 200;
        else if (c.subslot == reqSubslot + 1) score += 100;
        score += c.res;
        score += c.maxPkt / 16;

        matches.push_back({c, score});
    }

    if (matches.empty()) {
        for (auto& c : altCandidates_) {
            if ((c.channels == targetChannels || c.channels == 0) && c.subslot >= reqSubslot) {
                matches.push_back({c, c.res + c.maxPkt / 32});
            }
        }
    }

    if (matches.empty()) {
        LOGE("selectAltForRate: no candidate r=%d bits=%d ch=%d", targetRate, targetBits, targetChannels);
        return -1;
    }

    std::sort(matches.begin(), matches.end(),
              [](const Match& a, const Match& b) { return a.score > b.score; });

    auto& best = matches[0];
    LOGI("selectAltForRate: alt=%d r=%d sub=%d ch=%d res=%d score=%d / %zu",
         best.cand.alt, best.cand.sampleRate, best.cand.subslot,
         best.cand.channels, best.cand.res, best.score, matches.size());

    return best.cand.alt;
}
// 鈹€鈹€ readConfigDescriptor 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

int UsbAudioDriver::readConfigDescriptor(uint8_t interfaceNum, uint8_t* buf, size_t maxLen) {
    if (fd_ < 0) return 0;

    // Descriptor cache: immutable within one open() session. Once loaded, all
    // parse* callers (6 sites) hit this cache and issue zero extra ioctls —
    // eliminating the post-setInterfaceAlt "busy window" race (errno=110).
    if (configDescLen_ > 0 && !configDesc_.empty()) {
        int n = (configDescLen_ < (int)maxLen) ? configDescLen_ : (int)maxLen;
        memcpy(buf, configDesc_.data(), n);
        return n;
    }

    // Two-step: read the 9-byte header first to get wTotalLength, then read
    // exactly that many bytes (never request more than the device declares).
    // Requesting more than wTotalLength causes some host controllers to hang
    // until timeout (errno=110) instead of returning a short read.
    uint8_t hdr[9];
    struct usbdevfs_ctrltransfer ctrl = {};
    ctrl.bRequestType = 0x80;
    ctrl.bRequest     = USB_REQ_GET_DESCRIPTOR;
    ctrl.wValue       = (USB_DT_CONFIG << 8) | 0;
    ctrl.wIndex       = 0;
    ctrl.wLength      = 9;
    ctrl.timeout      = 1000;
    ctrl.data         = hdr;
    int hdrRet = ioctl(fd_, USBDEVFS_CONTROL, &ctrl);
    if (hdrRet < 9) {
        LOGE("readConfigDescriptor header failed: %s (errno=%d, got %d)", strerror(errno), errno, hdrRet);
        return -1;
    }
    uint16_t wTotal = hdr[2] | (hdr[3] << 8);
    if (wTotal == 0 || wTotal > 4096) wTotal = 4096;  // read FULL descriptor, cache it

    // Retry with increasing backoff on timeout/stall (errno=110 ETIMEDOUT).
    // After setInterfaceAlt() activates the ISO endpoint, some DACs (Realtek
    // 4BA6) enter a "busy" window where control transfers time out; a short
    // fixed backoff isn't enough. Escalate 50→150→300→500ms over 5 attempts.
    const int kBackoffUs[5] = { 50000, 150000, 300000, 500000, 500000 };
    for (int attempt = 0; attempt < 5; ++attempt) {
        configDesc_.resize(wTotal);
        struct usbdevfs_ctrltransfer c2 = {};
        c2.bRequestType = 0x80;
        c2.bRequest     = USB_REQ_GET_DESCRIPTOR;
        c2.wValue       = (USB_DT_CONFIG << 8) | 0;
        c2.wIndex       = 0;
        c2.wLength      = wTotal;
        c2.timeout      = 1500;
        c2.data         = configDesc_.data();
        int ret = ioctl(fd_, USBDEVFS_CONTROL, &c2);
        if (ret >= 0) {
            configDescLen_ = ret;
            int n = (ret < (int)maxLen) ? ret : (int)maxLen;
            memcpy(buf, configDesc_.data(), n);
            return n;
        }
        LOGE("readConfigDescriptor attempt %d failed: %s (errno=%d)", attempt + 1, strerror(errno), errno);
        usleep(kBackoffUs[attempt]);  // increasing backoff
    }

    return -1;
}

// 鈹€鈹€ controlTransfer 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

int UsbAudioDriver::controlTransfer(uint8_t bmRequestType, uint8_t bRequest,
                                    uint16_t wValue, uint16_t wIndex,
                                    void* data, uint16_t wLength, unsigned timeoutMs) {
    if (fd_ < 0) return -1;

    // For class-specific requests targeting an interface, map wIndex to interface number
    struct usbdevfs_ctrltransfer ctrl = {};
    ctrl.bRequestType = bmRequestType;
    ctrl.bRequest     = bRequest;
    ctrl.wValue       = wValue;
    ctrl.wIndex       = wIndex;
    ctrl.wLength      = wLength;
    ctrl.timeout      = timeoutMs;
    ctrl.data         = data;

    int ret = ioctl(fd_, USBDEVFS_CONTROL, &ctrl);
    if (ret < 0) {
        return -1;
    }
    return ret; // bytes transferred
}

// 鈹€鈹€ submitUrb 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

// ---- Hardware Volume: Feature Unit parse & SET_CUR ----------------------

// [2026-10-22] Readback verification constants. A live FU echoes the written
// dB within quantization (1/256 dB units); 1 dB tolerance covers rounding and
// coarse-resolution devices. Two consecutive bad rounds (dead unit, or a floor
// too high for the requested level) trip the software-gain fallback.
static constexpr float kVolVerifyTolDb    = 1.0f;
static constexpr int   kVolVerifyStrikes  = 2;

// [2026-10-22] SET_CUR one FU channel to an absolute dB.
// UAC Volume = signed int16 in 1/256 dB units, wLength=2 (both UAC1 & UAC2).
bool UsbAudioDriver::writeHardwareVolumeDb(int unitId, int ch, float db) {
    if (fd_ < 0 || unitId <= 0) return false;
    int16_t dBval = static_cast<int16_t>(db * 256.0f);
    uint16_t wValue = (0x02 << 8) | (ch & 0xFF);   // VOLUME_CONTROL, channel ch
    uint16_t wIndex = (unitId << 8) | acIface_;
    int r = controlTransfer(0x21, 0x01, wValue, wIndex, &dBval, 2, 50);
    return r >= 2;
}

// [2026-10-22] GET_CUR one FU channel -> dB. false = transfer failed (cannot verify).
bool UsbAudioDriver::readHardwareVolumeDb(int unitId, int ch, float& outDb) {
    if (fd_ < 0 || unitId <= 0) return false;
    int16_t raw = 0;
    uint16_t wValue = (0x02 << 8) | (ch & 0xFF);
    uint16_t wIndex = (unitId << 8) | acIface_;
    int r = controlTransfer(0xA1, 0x81, wValue, wIndex, &raw, 2, 100);
    if (r < 2) return false;
    outDb = raw / 256.0f;
    return true;
}

// [2026-10-22] Functional liveness probe: SET a mid-level dB, GET it back.
// A USB-level ACK only proves the control pipe works — 31B2:1112 ACKs every
// SET_CUR yet ignores them (user hears full scale at any slider position).
// Only a matching round-trip proves the FU actually drives the level.
// This is also the ONLY way to discover a FU on UAC2 devices that STALL the
// optional GET_MIN/GET_MAX requests (TTGK 3302:33C0 among them).
bool UsbAudioDriver::probeFeatureUnitFunctional(int unitId, int ch, float testDb, float& readbackDb) {
    if (!writeHardwareVolumeDb(unitId, ch, testDb)) return false;
    if (!readHardwareVolumeDb(unitId, ch, readbackDb)) return false;
    return std::fabs(readbackDb - testDb) <= kVolVerifyTolDb;
}

// [2026-10-22] Runtime safety net: the hardware FU went deaf (readback mismatch,
// or the slider wants below the hardware floor) -> hand the level to the software
// gain path (the streamLoop packer multiplies volume_ into the PCM).
void UsbAudioDriver::fallbackToSoftwareVolume(const char* reason, float cubic) {
    hardwareVolumeReady_ = false;
    volume_.store(cubic, std::memory_order_relaxed);
    LOGI("Hardware volume DISABLED at runtime (%s) -> software gain %.6f (%.1f dB)",
         reason, cubic, 20.0f * log10f(cubic > 1e-9f ? cubic : 1e-9f));
    nativeLog("VOL", "hardware volume dead at runtime, switched to software gain");
}

// [2026-10-22] 设备 quirk 强制软件音量：31B2:1112 的 FU 有应答、SET_CUR 全 ACK，
// 但固件从不真正衰减（用户实测滑块不变声、peak 恒 1.00）。claim 期回环闸门抓不到
// 「存值但不应用」型固件，唯一可靠的衰减通路是 packer 的软件增益。幂等可重入。
void UsbAudioDriver::forceSoftwareVolume() {
    int prevFu = featureUnitId_;
    if (!hardwareVolumeReady_ && prevFu == 0) return;   // 已是软件音量
    hardwareVolumeReady_ = false;
    featureUnitId_ = 0;
    volumeVerifyStrikes_ = 0;
    LOGI("Hardware volume: FORCED OFF by device quirk (was FU id=%d) -> software gain", prevFu);
    nativeLog("VOL", "hardware volume force-disabled by device quirk, software gain");
}

void UsbAudioDriver::parseFeatureUnit() {
    hardwareVolumeReady_ = false;
    featureUnitId_ = 0;
    featureUnitChannel_ = 0;
    volumeVerifyStrikes_ = 0;

    uint8_t desc[4096];
    int len = readConfigDescriptor(0, desc, sizeof(desc));
    if (len <= 0) return;

    // [2026-10-22] 路径感知：UAC2 一条音频路 = Input Terminal -> Unit(s) -> Output
    // Terminal，单元的 bSourceID 指向链上前一级。只有坐在「我们的播放路」上的 FU
    // 才控制我们在放的音。Realtek 4BA6：播放路 iface2 -> AS General bTerminalLink=14
    // -> FU16(src=14)；描述符顺序第一的 FU3(src=1) 在另一条路上，SET_CUR 全 ACK 但
    // 完全不衰减——用户实测"滑块不变声"（硬件音量一启用软件增益就被钉 1.0，双重失灵）。
    // 与 findClockSourceId 同源：先找 ifaceNum_ 上的 AS General link，再收集所有
    // streaming link 作为候选路径（接口号竞态时兜底，与时钟修复一致）。
    int ourTerminal = -1;
    int pathLinks[24];
    int nPathLinks = 0;
    {
        int curIface = -1, p = 0;
        while (p < len && p + 4 < (int)sizeof(desc)) {
            uint8_t dLen  = desc[p];
            uint8_t dType = desc[p + 1];
            if (dType == USB_DT_INTERFACE && dLen >= 9) curIface = desc[p + 2];
            if (dType == UAC2_CS_INTERFACE && dLen >= 4 && desc[p + 2] == 0x01 /*AS_GENERAL*/) {
                int link = desc[p + 3];   // bTerminalID
                if (curIface == ifaceNum_) ourTerminal = link;
                bool dup = false;
                for (int k = 0; k < nPathLinks; ++k) if (pathLinks[k] == link) { dup = true; break; }
                if (!dup && nPathLinks < 24) pathLinks[nPathLinks++] = link;
            }
            p += (dLen > 0) ? dLen : 1;
            if (dLen == 0) break;
        }
        LOGI("parseFeatureUnit: streaming links=%d (ourTerminal=%d, ifaceNum_=%d)",
             nPathLinks, ourTerminal, ifaceNum_);
    }

    // 收集 descriptor 里所有 Feature Unit 候选（不按 VID/PID 硬编码），含 bSourceID。
    struct FuCand { int id; int chCount; int srcId; };
    FuCand cands[8];
    int nCand = 0;
    int pos = 0;
    while (pos < len && pos + 6 < (int)sizeof(desc)) {
        uint8_t dLen  = desc[pos];
        uint8_t dType = desc[pos + 1];
        if (dType == UAC2_CS_INTERFACE && dLen >= 7 && desc[pos + 2] == 0x06 && nCand < 8) {
            uint8_t unitId       = desc[pos + 3];
            uint8_t bSourceId    = desc[pos + 4];
            uint8_t bControlSize = desc[pos + 5];
            int chCount = 0;
            if (bControlSize >= 1 && bControlSize <= 4 && dLen >= 8) {
                chCount = (dLen - 8) / bControlSize;
            }
            if (chCount < 0) chCount = 0;
            cands[nCand].id = unitId;
            cands[nCand].chCount = chCount;
            cands[nCand].srcId = bSourceId;
            ++nCand;
        }
        pos += (dLen > 0) ? dLen : 1;
        if (dLen == 0) break;
    }

    // 路径优先重排：ourTerminal 命中的 FU 最前 -> 其它 streaming link 命中的次之 ->
    // 不在任何音频路上的垫底。只要存在「在路上」的 FU，Phase 1/2 就只准在前 nAccept
    // 个里选——选了路外 FU 就是滑块不变声，比没有硬件音量更坏。
    int nAccept = nCand;
    if (nPathLinks > 0 && nCand > 1) {
        FuCand ordered[8];
        int nOrd = 0;
        for (int i = 0; i < nCand; ++i)
            if (cands[i].srcId == ourTerminal) ordered[nOrd++] = cands[i];
        for (int k = 0; k < nPathLinks; ++k) {
            if (pathLinks[k] == ourTerminal) continue;
            for (int i = 0; i < nCand; ++i)
                if (cands[i].srcId == pathLinks[k]) ordered[nOrd++] = cands[i];
        }
        int nPath = nOrd;
        for (int i = 0; i < nCand; ++i) {
            bool onPath = false;
            for (int k = 0; k < nPathLinks; ++k) if (cands[i].srcId == pathLinks[k]) { onPath = true; break; }
            if (!onPath) ordered[nOrd++] = cands[i];
        }
        for (int i = 0; i < nOrd; ++i) cands[i] = ordered[i];
        if (nPath > 0) {
            nAccept = nPath;
            LOGI("parseFeatureUnit: %d FU candidate(s), %d on audio path(s) — path-only selection", nCand, nPath);
        }
    }

    // Phase 1: UAC1 式范围探测（GET_MIN/GET_MAX，wLength 固定 2 = 16.16）。
    // UAC1 和规矩的 UAC2 能拿到真实量程；GET_MAX 合理即采用。
    // [2026-10-22] 只遍历前 nAccept 个（路上 FU；无路上 FU 时才退回全部）。
    for (int i = 0; i < nAccept && !hardwareVolumeReady_; ++i) {
        uint16_t wIndex = (cands[i].id << 8) | acIface_;
        for (int ch = 0; ch <= 1; ++ch) {
            uint16_t wValue = (0x02 << 8) | ch;
            int16_t minRaw = 0, maxRaw = 0;
            int rMin = controlTransfer(0xA1, 0x82, wValue, wIndex, &minRaw, 2, 100); // GET_MIN
            int rMax = controlTransfer(0xA1, 0x83, wValue, wIndex, &maxRaw, 2, 100); // GET_MAX
            float minDb = minRaw / 256.0f;
            float maxDb = maxRaw / 256.0f;
            LOGI("parseFeatureUnit: FU id=%d ch=%d GET_MIN(ret=%d,%.1f dB) GET_MAX(ret=%d,%.1f dB)",
                 cands[i].id, ch, rMin, minDb, rMax, maxDb);
            if (rMax >= 2 && maxDb <= 0.0f && maxDb > -128.0f) {
                featureUnitMaxDb_ = maxDb;
                featureUnitMinDb_ = (rMin >= 2 && minDb < maxDb && minDb > -128.0f) ? minDb : -127.0f;
                featureUnitId_ = cands[i].id;
                featureUnitChannel_ = ch;
                featureUnitChannels_ = (cands[i].chCount > 0) ? cands[i].chCount : 2;
                featureUnitControlSize_ = 2;
                hardwareVolumeReady_ = true;
                LOGI("Hardware volume: FU id=%d ch=%d(chUsed) chCount=%d, range=[%.1f, %.1f] dB",
                     featureUnitId_, featureUnitChannel_, featureUnitChannels_,
                     featureUnitMinDb_, featureUnitMaxDb_);
                break;
            }
        }
    }

    // Phase 2 [2026-10-22 撤回自动启用]：曾按"SET 中间档 + GET 回读对得上 = 活的"
    // 自动启用硬件音量。实测证据表明该判据**零鉴别力**：
    //   - Realtek 4BA6：FU3（错路）与 FU16（播放路）回环全过，两台都不衰减（用户两次实锤）
    //   - 31B2:1112：回环全过，音量全程死
    //   - 获救记录：0 台（TTGK 的 FU 回环本就不过）
    // 即"寄存器文件活着"推不出"音频通路接着它"——廉价芯片画个控制块不接线的常规操作。
    // 且它还制造了回归：Realtek 在 10:41 前一直靠软件增益正常工作。
    // 现在 GET_MIN/GET_MAX 有应答（Phase 1）是唯一启用信号，再过 Phase 3 回环闸门；
    // STALL 的设备一律软件增益（TTGK / Realtek 一贯的正常工作状态）。

    if (hardwareVolumeReady_) {
        // Phase 3: 总闸。GET_MIN/MAX 应过答也只证明 descriptor 层活着——
        // 31B2:1112 就是"range 探测全 OK、SET_CUR 全 ACK、电平一动不动"。
        // 回环不过 → 判死，禁用硬件音量（软件增益接管，滑块恢复可用）。
        float rb = 0.0f;
        if (!probeFeatureUnitFunctional(featureUnitId_, featureUnitChannel_, -12.0f, rb)) {
            LOGI("Hardware volume: FU id=%d advertised but SET/GET_CUR round-trip FAILED (readback=%.1f dB) -> DISABLED, software gain",
                 featureUnitId_, rb);
            nativeLog("VOL", "FU advertised but round-trip failed, hardware volume disabled");
            hardwareVolumeReady_ = false;
            featureUnitId_ = 0;
        } else {
            // 收窄"说谎的下限"：往声称下限以下打一枪，读回值就是设备真实地板。
            // 运行期按真实地板钳位，滑块低档位才不会误撞回读校验。
            float lo = 0.0f;
            if (writeHardwareVolumeDb(featureUnitId_, featureUnitChannel_, featureUnitMinDb_ - 20.0f) &&
                readHardwareVolumeDb(featureUnitId_, featureUnitChannel_, lo) &&
                lo > featureUnitMinDb_ + 0.5f && lo <= featureUnitMaxDb_) {
                LOGI("Hardware volume: real floor %.1f dB (GET_MIN claimed %.1f dB) -> adopted",
                     lo, featureUnitMinDb_);
                featureUnitMinDb_ = lo;
            }
            writeHardwareVolumeDb(featureUnitId_, featureUnitChannel_, 0.0f);  // 复原到 unity
        }
    }

    if (!hardwareVolumeReady_) {
        featureUnitMinDb_ = -127.0f;
        featureUnitMaxDb_ = 0.0f;
        if (nCand == 0) {
            nativeLog("VOL", "no Feature Unit in descriptor, hardware volume disabled");
        } else if (nPathLinks > 0 && nAccept < nCand) {
            // 播放路上有 FU 但 GET_MIN/GET_MAX 不应答——按 2026-10-22 的结论走软件增益：
            // 功能探测回环对死 FU 也过（Realtek 4BA6 FU16 / 31B2:1112 双双实证），不足为据。
            LOGI("Hardware volume: %d on-path FU(s) present but GET_MIN/GET_MAX unsupported -> DISABLED, software gain (functional probe not trusted: dead FUs pass it too)", nAccept);
            nativeLog("VOL", "on-path FU without GET_MIN/MAX support, hardware volume disabled (software gain)");
        } else {
            nativeLog("VOL", "no working Feature Unit volume found, hardware volume disabled");
        }
    }
}

// Kotlin 层注入 Salt-verified 的 FU 参数（通用探测失败时兜底）。
// quirk 表已上移到 DacProfile.kt，这里只接收 Kotlin 传来的覆盖值。
void UsbAudioDriver::setFeatureUnitOverride(int unitId, int channel, int channels,
                                            float minDb, float maxDb, float resDb) {
    if (unitId <= 0) return;
    featureUnitId_ = unitId;
    featureUnitChannel_ = channel;
    featureUnitChannels_ = channels > 0 ? channels : 2;
    featureUnitControlSize_ = 2;
    featureUnitMinDb_ = minDb;
    featureUnitMaxDb_ = maxDb;
    featureUnitResDb_ = resDb;
    hardwareVolumeReady_ = true;
    LOGI("Hardware volume: FU id=%d ch=%d chCount=%d, range=[%.1f, %.1f] dB (from Kotlin profile)",
         featureUnitId_, featureUnitChannel_, featureUnitChannels_,
         featureUnitMinDb_, featureUnitMaxDb_);
}

// [2026-09-10 multi-DAC quirk] Kotlin DacProfile overrides (single source of truth).
// Called right after nativeClaim succeeds. Only non-default values are pushed;
// open() built-in quirk init has already run, so this *overrides* the built-in
// defaults for known devices. Zero urbPackets/ctlMsg/ifaceDelay means "keep native default".
void UsbAudioDriver::setQuirkOverride(int urbPackets, int ctlMsgDelayUs, int ifaceDelayUs,
                                      bool forceIfaceReset, bool forceUac1, bool skipSetCur) {
    if (urbPackets > 0 && urbPackets <= kPacketsPerUrb) {
        urbPackets_ = urbPackets;
    }
    if (ctlMsgDelayUs > 0) ctlMsgDelayUs_ = ctlMsgDelayUs;
    if (ifaceDelayUs > 0)  ifaceDelayUs_  = ifaceDelayUs;
    forceIfaceReset_ = forceIfaceReset;
    forceUac1_       = forceUac1;
    skipSetCurQuirk_ = skipSetCur;
    quirkOverrideApplied_ = true;
    LOGI("quirk override: urbPackets_=%d ctlDelay=%d ifaceDelay=%d forceReset=%d forceUac1=%d skipSetCur=%d",
         urbPackets_, ctlMsgDelayUs_, ifaceDelayUs_,
         forceIfaceReset ? 1 : 0, forceUac1 ? 1 : 0, skipSetCur ? 1 : 0);
}


bool UsbAudioDriver::setHardwareVolume(float pct) {
    if (!hardwareVolumeReady_ || featureUnitId_ <= 0 || fd_ < 0) return false;
    // [通用化] pct 是 cubic 后的值（pct^3）。软件 cubic taper 的等效 dB =
    // 20*log10(cubic)；硬件 FU 在芯片内做同等衰减，不损 SNR（软件 PCM 乘法会损 bit-depth）。
    float db = (pct > 1e-6f) ? 20.0f * log10f(pct) : featureUnitMinDb_;
    if (pct > 1e-6f) lastHwVolPct_ = pct;   // [safety] remember user level for break-mute restore
    bool clamped = false;
    if (db > 0.0f) db = 0.0f;
    if (db < featureUnitMinDb_) { db = featureUnitMinDb_; clamped = true; }
    // [V8.x] dedup: playSong + startStreaming each call setVolume once with the
    // same cubic value; skip redundant SET_CUR when dB unchanged (less bus traffic).
    if (lastVolumeSet_ && db == lastVolumeDb_) return true;
    lastVolumeSet_ = true;
    lastVolumeDb_ = db;
    int16_t dBval = static_cast<int16_t>(db * 256.0f);
    uint16_t wIndex = (featureUnitId_ << 8) | acIface_;

    // [通用化] 探测到的 master 通道：chUsed==0 有 master → 只发 ch0；
    // chUsed==1 无 master → 逐通道发 ch1..chN。宽度固定 2 字节(16.16)。
    int chStart = featureUnitChannel_;
    int chEnd   = (featureUnitChannel_ == 0) ? 0 : featureUnitChannels_;
    int cSize   = 2;   // UAC2 Volume 标准 16.16 宽度

    int ok = 0;
    for (int ch = chStart; ch <= chEnd; ++ch) {
        uint16_t wValue = (0x02 << 8) | ch;   // Volume Control, channel ch
        int r = controlTransfer(0x21, 0x01, wValue, wIndex, &dBval, cSize, 50);
        if (r >= 0) { ok++; LOGI("SET_CUR ch%d vol=%.0f%% (%.1f dB) ok", ch, pct * 100, db); }
        else LOGW("SET_CUR ch%d FAIL ret=%d", ch, r);
    }
    if (ok == 0) return false;

    // [2026-10-22] Verify the write actually landed. ACK != honored: 31B2:1112
    // ACKs every SET_CUR yet the level never moves (user stuck at full scale).
    // Also catch "slider wants below the hardware floor" — the packer's software
    // gain covers the rest of the cubic taper that hardware cannot reach.
    float rb = 0.0f;
    bool verified = readHardwareVolumeDb(featureUnitId_, featureUnitChannel_, rb)
                    && std::fabs(rb - db) <= kVolVerifyTolDb;
    if (!verified) {
        ++volumeVerifyStrikes_;
        LOGW("setHardwareVolume: readback verify FAILED (want %.1f dB, got %.1f dB, strike %d/%d)",
             db, rb, volumeVerifyStrikes_, kVolVerifyStrikes);
        if (volumeVerifyStrikes_ >= kVolVerifyStrikes) {
            fallbackToSoftwareVolume("SET/GET_CUR readback mismatch", pct);
        }
    } else if (clamped) {
        ++volumeVerifyStrikes_;
        LOGW("setHardwareVolume: request below hardware floor %.1f dB (strike %d/%d)",
             featureUnitMinDb_, volumeVerifyStrikes_, kVolVerifyStrikes);
        if (volumeVerifyStrikes_ >= kVolVerifyStrikes) {
            fallbackToSoftwareVolume("hardware floor too high for requested level", pct);
        }
    } else {
        volumeVerifyStrikes_ = 0;
    }
    return true;
}

// [safety 2026-10-22] Hearing-protection mute. When the stream breaks (no data to send,
// or the async feedback closed-loop failed so the DAC free-runs at full hardware gain),
// a discontinuity can blast a max-scale pop into the ears. Force the output gain to 0 so
// the break is silent, never loud. Restored on clean recovery or on stream stop().
void UsbAudioDriver::armBreakMute(const char* why, bool permanent) {
    if (breakMuted_) { breakMutePermanent_ = breakMutePermanent_ || permanent; return; }
    savedVolume_ = volume_.load(std::memory_order_relaxed);
    // [fix] ramp the mute gain down to 0 over a few ms instead of hard-stepping volume_
    // to 0. A hard gain step is a discontinuity = an audible click/pop — exactly what the
    // hearing protection was meant to prevent. A short fade still silences the output fast
    // enough to be safe (no audible blast) but stays click-free.
    muteTotal_      = (sampleRate_ > 0) ? std::max(1, (int)((int64_t)sampleRate_ * kMuteFadeMs / 1000)) : 192;
    muteFramesLeft_ = muteTotal_;
    muteTarget_     = 0.0f;
    if (hardwareVolumeReady_) setHardwareVolume(0.0f); // also pull DAC FU to floor (rare hw-vol path)
    breakMuted_ = true;
    breakMutePermanent_ = permanent;
    LOGW("break-protect: muting (ramp -> 0) for hearing safety — reason=%s permanent=%d", why, permanent ? 1 : 0);
    nativeLog("SAFE", "output muting on break (hearing protection)");
}

void UsbAudioDriver::releaseBreakMute() {
    if (!breakMuted_) return;
    if (breakMutePermanent_) return;   // feedback-failure mute stays until stop()
    // [fix] ramp the mute gain back up to 1 (smooth, no click). volume_ was never
    // hard-stepped, so the live gain simply fades back in at whatever volume_ holds.
    muteTotal_      = (sampleRate_ > 0) ? std::max(1, (int)((int64_t)sampleRate_ * kMuteFadeMs / 1000)) : 192;
    muteFramesLeft_ = muteTotal_;
    muteTarget_     = 1.0f;
    if (hardwareVolumeReady_) setHardwareVolume(lastHwVolPct_);
    breakMuted_ = false;
    LOGI("break-protect: unmuting (ramp -> 1) gain=%.6f", savedVolume_);
    nativeLog("SAFE", "output unmuting after break recovered");
}

bool UsbAudioDriver::submitUrb(int slot, int numBytes) {
    if (slot < 0 || slot >= kMaxUrbCount) return false;

    struct usbdevfs_urb* urb = urbSlots_[slot].urb;
    urb->type            = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint        = epAddress_;
    urb->buffer          = urbSlots_[slot].buffer;
    urb->buffer_length   = numBytes;
    urb->number_of_packets = 1;
    urb->flags           = USBDEVFS_URB_ISO_ASAP;
    urb->iso_frame_desc[0].length = numBytes;

    int ret = ioctl(fd_, USBDEVFS_SUBMITURB, urb);
    if (ret < 0 && errno != EAGAIN) {
        LOGE("submitUrb[%d]: %s (errno=%d)", slot, strerror(errno), errno);
        return false;
    }
    return true;
}

// 鈹€鈹€ submitUrbRaw (multi-packet URB already set up) 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

bool UsbAudioDriver::submitUrbRaw(int slot) {
    if (slot < 0 || slot >= kMaxUrbCount) return false;

    struct usbdevfs_urb* urb = urbSlots_[slot].urb;
    urb->type            = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint        = epAddress_;
    urb->buffer          = urbSlots_[slot].buffer;
    urb->status          = 0;
    urb->flags           = USBDEVFS_URB_ISO_ASAP;
    urb->number_of_packets = urbPackets_;

    int ret = ioctl(fd_, USBDEVFS_SUBMITURB, urb);
    if (ret < 0 && errno != EAGAIN) {
        const int err = errno;
        LOGE("submitUrbRaw[%d]: %s (errno=%d)", slot, strerror(err), err);
        urbErrors_.fetch_add(1, std::memory_order_relaxed);
        // [V9.20x hotplug fallback] ENODEV means the device is gone from the bus.
        // This is the only signal we get when ACTION_USB_DEVICE_DETACHED is late or
        // never arrives (OTG adapters/hubs). Latch it once so the Kotlin side can run
        // the real detach path instead of retrying a dead endpoint.
        if (err == ENODEV) {
            int streak = consecutiveEnodev_.fetch_add(1, std::memory_order_acq_rel) + 1;
            if (streak >= kEnodevVanishThreshold &&
                !deviceVanished_.exchange(true, std::memory_order_acq_rel)) {
                LOGE("device vanished: %d consecutive ENODEV on URB submit -> latch detach",
                     streak);
            }
        }
        return false;
    }
    consecutiveEnodev_.store(0, std::memory_order_release);
    urbSubmitted_.fetch_add(1, std::memory_order_relaxed);
    return true;
}

// [V9.20x hotplug fallback] One-shot consumption of the vanish latch.
bool UsbAudioDriver::consumeDeviceVanished() {
    return deviceVanished_.exchange(false, std::memory_order_acq_rel);
}

// 鈹€鈹€ packet size helpers 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

int UsbAudioDriver::packetSizeFrames() const {
    // High-speed: 8000 microframes/sec. Full-speed: 1000 frames/sec.
    // Use REAL bus speed (highSpeed_), NOT isUac2_ — UAC2 protocol does not imply high-speed.
    int tps = highSpeed_ ? 8000 : 1000;
    return (sampleRate_ + tps - 1) / tps;  // ceil(sampleRate / transfers_per_sec)
}

int UsbAudioDriver::packetSizeBytes() const {
    return packetSizeFrames() * bytesPerFrame_;
}

const char* UsbAudioDriver::getSupportedRates() {
    return supportedRates_.c_str();
}

// 鈹€鈹€ getDescriptorProfile (P1 adaptive-DAC) 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
// Pack the already-parsed descriptor capabilities so the Kotlin layer no longer
// has to hand-write per-model values. Everything here is derived in open()
// (parseAltCandidates / parseClockRanges / parseFeatureUnit / findFeedbackEndpoint).
// Kotlin parses this via DacDescriptor.parse(). Format is "k=v;k=v;...".
//   wb    = wire bits (max alt subslot * 8; 16 default)
//   ssMax = max bytes-per-sample subslot across alts
//   ssMin = min subslot across alts (0 = not parsed)
//   uac   = 1 (UAC1) or 2 (UAC2)
//   hs    = 1 if real USB bus is high-speed (8000 uframes/s)
//   fb    = 1 if an implicit-feedback IN iso endpoint was found
//   fuId/fuCh/fuChs = auto-detected hardware-volume Feature Unit (0 = none)
//   fuMin/fuMax/fuRes = FU dB range * 1000 (int16.16 scaled to avoid float parse loss)
//   k44   = 1 if descriptor exposes any 44.1k-family (44.1/88.2/176.4/352.8k) clock range
//   alts  = number of AudioStreaming alt candidates discovered
const char* UsbAudioDriver::getDescriptorProfile() const {
    int ssMax = 0, ssMin = 0, wireBits = 16, alts = (int)altCandidates_.size();
    for (const auto& c : altCandidates_) {
        if (c.subslot <= 0) continue;
        if (ssMax == 0 || c.subslot > ssMax) ssMax = c.subslot;
        if (ssMin == 0 || c.subslot < ssMin) ssMin = c.subslot;
    }
    if (ssMax > 0) wireBits = ssMax * 8;

    int uac = isUac2_ ? 2 : 1;
    int hs  = highSpeed_ ? 1 : 0;
    int fb  = (feedbackEp_ > 0) ? 1 : 0;

    int fuId  = featureUnitId_;
    int fuCh  = featureUnitChannel_;
    int fuChs = featureUnitChannels_;
    int fuMin = (int)(featureUnitMinDb_ * 1000.0f);
    int fuMax = (int)(featureUnitMaxDb_ * 1000.0f);
    int fuRes = (int)(featureUnitResDb_ * 1000.0f);

    // 44.1k family present? Scan clock sub-ranges for any coverage of the
    // 44.1/88.2/176.4/352.8 kHz rates.
    int k44 = 0;
    int ci  = (int)clockRanges_.size();   // clock-info present? (probe succeeded)
    for (const auto& cr : clockRanges_) {
        if ((cr.min <= 44100   && 44100   <= cr.max) ||
            (cr.min <= 88200   && 88200   <= cr.max) ||
            (cr.min <= 176400  && 176400  <= cr.max) ||
            (cr.min <= 352800  && 352800  <= cr.max)) {
            k44 = 1; break;
        }
    }

    snprintf(descriptorProfileBuf_, sizeof(descriptorProfileBuf_),
        "wb=%d;ssMax=%d;ssMin=%d;uac=%d;hs=%d;fb=%d;"
        "fuId=%d;fuCh=%d;fuChs=%d;fuMin=%d;fuMax=%d;fuRes=%d;k44=%d;ci=%d;alts=%d;vr=%s",
        wireBits, ssMax, ssMin, uac, hs, fb,
        fuId, fuCh, fuChs, fuMin, fuMax, fuRes, k44, ci, alts, verifiedRates_.c_str());
    return descriptorProfileBuf_;
}

// 鈹€鈹€ static thread wrappers 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

void* UsbAudioDriver::streamThreadEntry(void* arg) {
    auto* self = static_cast<UsbAudioDriver*>(arg);
    // 銆怴3.2.7銆慡CHED_FIFO 瀹炴椂璋冨害锛圓NDROID URGENT_AUDIO 鍚岀骇锛夛紝setpriority 涓嶄繚璇佸疄鏃讹拷?
    sched_param sp = { .sched_priority = 2 };
    pthread_setschedparam(pthread_self(), SCHED_FIFO, &sp);
    self->streamLoop();
    return nullptr;
}

void UsbAudioDriver::getStats(UsbDacStats& out) const {
    out.urbSubmitted = urbSubmitted_.load();
    out.urbCompleted = urbCompleted_.load();
    out.urbErrors    = urbErrors_.load();
    out.totalSamplesOut = totalSamplesOut_.load();
    out.ringReadPos  = readPos_.load();
    out.ringWritePos = writePos_.load();
    int wp = out.ringWritePos;
    int rp = out.ringReadPos;
    out.ringAvailFrames = (wp - rp + kRingFrames) % kRingFrames;
    out.bufferWatermarkMs = bufferWatermarkMs_.load();
    out.targetWatermarkMs = 200;
    int wm = out.bufferWatermarkMs;
    // Wedge detection: streaming but no URB completion for >3s = stalled
    int64_t nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    int64_t lastMs = lastUrbCompletionTimeMs_.load();
    if (streaming_.load(std::memory_order_acquire) && lastMs > 0 && (nowMs - lastMs) > 3000) {
        out.healthState = "wedge";
    } else if (wm <= 0) {
        out.healthState = "idle";
    } else if (wm >= 160 && wm <= 240) {
        out.healthState = "stable";
    } else if (wm >= 80) {
        out.healthState = "fluctuating";
    } else {
        out.healthState = "critical";
    }
}

// [P6] feedbackThreadEntry removed: feedback now runs inside streamLoop's single reaper.