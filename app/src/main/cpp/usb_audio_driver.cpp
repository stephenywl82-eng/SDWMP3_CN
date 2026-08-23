#include "usb_audio_driver.h"
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <unistd.h>
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
        size_t urbSize = sizeof(usbdevfs_urb) + kPacketsPerUrb * sizeof(usbdevfs_iso_packet_desc);
        urbSlots_[i].urb = static_cast<usbdevfs_urb*>(calloc(1, urbSize));
        if (!urbSlots_[i].urb) {
            LOGE("Failed to allocate URB slot %d", i);
        }
    }
    LOGI("UsbAudioDriver created, ring=%p, %d frames", ringBuffer_, kRingFrames);
}

UsbAudioDriver::~UsbAudioDriver() {
    stop();
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

    char buf[256];
    snprintf(buf, sizeof(buf), "Open: fd=%d ep=0x%02X mps=%d int=%d uac2=%d vid=%04X pid=%04X",
             fd_, epAddress_, maxPacketSize_, interval_, isUac2_, vid_, pid_);
    detailedInfo_ = buf;
    LOGI("%s", buf);

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
    LOGI("DISCONNECT kernel driver iface=0: ret=%d errno=%d (%s)",
         discRet, errno, strerror(errno));

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

    // Claim the audio streaming interface (alt=1 activates ISO OUT)
    if (!claimInterface(1)) {
        LOGE("Failed to claim interface");
        return false;
    }
    currentAlt_ = 1;

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

    // Find feedback endpoint for async DACs
    int fbEp = findFeedbackEndpoint();
    if (fbEp > 0) {
        char tmp[64];
        snprintf(tmp, sizeof(tmp), ", fbEp=0x%02X", fbEp);
        detailedInfo_ += tmp;
        LOGI("Feedback endpoint: 0x%02X", fbEp);
    }

    // Parse Feature Unit for hardware volume control
    parseFeatureUnit();

    return true;
}

// 鈹€鈹€ start 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

bool UsbAudioDriver::start(int sampleRate, int channels, int bitsPerSample) {
    if (fd_ < 0) {
        LOGE("start: device not open");
        return false;
    }

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
        return true;
    }

    if (streaming_.load(std::memory_order_acquire)) {
        LOGW("start: restarting stream (old sr=%d ch=%d bits=%d) -> (sr=%d ch=%d bits=%d)",
             sampleRate_, channels_, bitsPerSample_, sampleRate, channels, bitsPerSample);
        stopThreadOnly();
    }

    sampleRate_ = sampleRate;
    channels_ = channels;
    bitsPerSample_ = bitsPerSample;
    bytesPerFrame_ = channels_ * (bitsPerSample_ / 8);

    LOGI("start: sr=%d ch=%d bits=%d bytesPerFrame=%d",
         sampleRate_, channels_, bitsPerSample_, bytesPerFrame_);

    // 銆愯嚜閫傚簲DAC銆戜粠descriptor鍊欓€夎〃閫夋渶浣砤lt + 楠岃瘉clock range
    int targetAlt = selectAltForRate(sampleRate_, bitsPerSample_, channels_);
    LOGI("selectAltForRate(r=%d bits=%d ch=%d) 锟斤拷 alt=%d (candidates=%zu, ranges=%zu)",
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
        setInterfaceAlt(0);
        int scRet = clockRate_;
        if (sampleRate_ != clockRate_) {
            // [v6.0.14] Skip SET_CUR for known buggy DACs; rely on alt-switch implicit lock.
            // NOTE: Realtek 4BA6 is NOT buggy — Salt verified it accepts SET_CUR
            // (setCurAttempts=[clock=30@0/ret=4/current=44100]). Skipping SET_CUR left
            // its clock stuck at 48k, so 44.1k tracks played back fast/warped.
            bool buggyDac = (vid_ == 0x2D13 && pid_ == 0xA001)
                || (vid_ == 0x2972 && pid_ == 0x0047)
                || (vid_ == 0x3302 && pid_ == 0x201D);  // TTGK Note: no clock source descriptor
            scRet = buggyDac ? sampleRate_ : trySetSampleRate(sampleRate_);
            if (buggyDac) { LOGI("start: skipping SET_CUR for pid=%04X (implicit alt-switch lock)", pid_); clockRate_ = sampleRate_; }
            else if (scRet >= 0) clockRate_ = sampleRate_;
            else LOGW("start: SET_CUR(%d) failed, clock stays at %d", sampleRate_, clockRate_);
        }
        bool altOk = setInterfaceAlt(targetAlt);
        if (!altOk) {
            // EBUSY 閲嶈瘯涓€娆★細缁欏唴鏍稿洖锟?URB 鐨勬椂闂寸獥锟?
            usleep(20000);
            altOk = setInterfaceAlt(targetAlt);
        }
        if (altOk) {
            currentAlt_ = targetAlt;
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
        if (!altOk) return false;
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

    // [V3.3.4] Do NOT reset ring positions here: Kotlin prebuffers ~500ms into the
    // ring BEFORE calling start(); zeroing writePos_/readPos_ discarded that prebuffer
    // (silent gap + first ~500ms of every song lost). Callers (play/resume/seek paths)
    // call resetRingBuffer() explicitly before pushing fresh data.
    underrunCount_.store(0, std::memory_order_release);

    // Start stream thread
    streaming_.store(true, std::memory_order_release);
    streamThread_ = std::thread(streamThreadEntry, this);
    return true;
}

// 鈹€鈹€ stop 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

void UsbAudioDriver::stop() {
    stopThreadOnly();
    releaseInterface();
    LOGI("stop: done (interface released)");
}

void UsbAudioDriver::stopThreadOnly() {
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
    if (feedbackThread_.joinable()) {
        feedbackThread_.join();
    }
    LOGI("stopThreadOnly: threads stopped, USB claim kept");
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

    // 【V8.3 去限幅后 preGain 裕度】低频 low-shelf（32/60/120Hz）在 30Hz 以下几乎全部叠加，
    // 只用最大单 band 增益补偿会低估级联总增益，密集鼓声满幅时叠加超 1.0 触发最终硬限幅。
    // 按正增益总和补偿（保守，避免级联削波）。
    float sumPosGain = 0.0f;
    for (int i = 0; i < len && i < 10; i++) if (gainsDb[i] > 0.0f) sumPosGain += gainsDb[i];
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

    BiquadFilter* bandsL[10] = { &autoEqBand1L_, &autoEqBand2L_, &autoEqBand3L_, &autoEqBand4L_, &autoEqBand5L_,
                                 &autoEqBand6L_, &autoEqBand7L_, &autoEqBand8L_, &autoEqBand9L_, &autoEqBand10L_ };
    BiquadFilter* bandsR[10] = { &autoEqBand1R_, &autoEqBand2R_, &autoEqBand3R_, &autoEqBand4R_, &autoEqBand5R_,
                                 &autoEqBand6R_, &autoEqBand7R_, &autoEqBand8R_, &autoEqBand9R_, &autoEqBand10R_ };

    for (int i = 0; i < 10; i++) {
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
    LOGI("DAC AutoEQ 10-band applied: preamp=%.1fdB", preampDb);
}

void UsbAudioDriver::resetAutoEq() {
    std::lock_guard<std::mutex> eqLock(dspEqMutex_);
    BiquadFilter* bandsL[10] = { &autoEqBand1L_, &autoEqBand2L_, &autoEqBand3L_, &autoEqBand4L_, &autoEqBand5L_,
                                 &autoEqBand6L_, &autoEqBand7L_, &autoEqBand8L_, &autoEqBand9L_, &autoEqBand10L_ };
    BiquadFilter* bandsR[10] = { &autoEqBand1R_, &autoEqBand2R_, &autoEqBand3R_, &autoEqBand4R_, &autoEqBand5R_,
                                 &autoEqBand6R_, &autoEqBand7R_, &autoEqBand8R_, &autoEqBand9R_, &autoEqBand10R_ };
    for (int i = 0; i < 10; i++) { bandsL[i]->reset(); bandsR[i]->reset(); bandsL[i]->setFlat(); bandsR[i]->setFlat(); }
    for (int i = 0; i < 10; i++) lastAutoEqGains_[i] = 999.0f;  // 强制下次重新应用
    autoEqEnabled_.store(false, std::memory_order_release);
    autoEqPreGain_ = 1.0f;
    curAutoEqPreGain_ = 1.0f;
    LOGI("DAC AutoEQ 10-band reset (flat)");
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

// 鈹€鈹€ pushPcm 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

int UsbAudioDriver::pushPcm(const float* data, int frameCount) {
    if (!ringBuffer_ || frameCount <= 0) return -1;

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

    while (remaining > 0) {
        int wp = writePos_.load(std::memory_order_acquire);
        int rp = readPos_.load(std::memory_order_acquire);
        // 锟?1 甯ч棿闅欏尯鍒嗘弧/锟?
        int avail = kRingFrames - 1 - ((wp - rp + kRingFrames) % kRingFrames);

        if (avail <= 0) {
            if (!streaming_.load(std::memory_order_acquire)) break;  // 棰勭紦鍐叉弧浜嗙洿鎺ヨ繑锟?
            usleep(2000);  // 绛夋秷璐圭嚎绋嬭吘绌洪棿锛垀88锟?2ms @44.1k锟?
            continue;
        }

        int chunk = remaining < avail ? remaining : avail;
        const int mask = kRingFrames * 2 - 1; // power-of-2 assumption

        // DAC-path EQ（AutoEQ 打底 + MSEB 叠加 / 5段图形），same Biquad algo as Oboe。
        // Disabled => pure pass-through (bit-perfect preserved)。
        bool autoEqOn = autoEqEnabled_.load(std::memory_order_acquire);
        bool msebOn = mseb10Enabled_.load(std::memory_order_acquire);
        bool dspOn = dspEqEnabled_.load(std::memory_order_acquire);
        float targetPreGain = 1.0f;
        if (autoEqOn || msebOn || dspOn) {
            // 【V8.2 hiby】blocking lock: setters hold dspEqMutex_ only for
            // microseconds of pure math, so the decode thread can safely wait for
            // coeff commit instead of dropping EQ for a whole chunk (which caused
            // a processed<->pass-through timbre step = audible crackle).
            std::lock_guard<std::mutex> eqLock(dspEqMutex_);
            // 各开模块的 preGain 相乘（都是 ≤1 衰减系数，叠加后一起补偿）。
            targetPreGain = 1.0f;
            if (autoEqOn) targetPreGain *= autoEqPreGain_;
            if (msebOn)   targetPreGain *= msebPreGain_;
            if (dspOn && !msebOn) targetPreGain *= dspEqPreGain_;
        }

        // [zipper-noise fix] fade pre-gain toward target per-sample (same SMOOTH as
        // BiquadFilter). MSEB slider changes (preGain jump) no longer step the
        // output level instantly -> eliminates the crackle/hiss heard while adjusting live.
        const float PREGAIN_SMOOTH = 0.05f;
        float preGain = curDspPreGain_;
        bool msOn = msEnabled_.load(std::memory_order_acquire);
        float msW = curMsWidth_, msC = curMsCenter_;
        float msTargetW = msWidth_.load(std::memory_order_acquire);
        float msTargetC = msCenter_.load(std::memory_order_acquire);
        for (int f = 0; f < chunk; ++f) {
            preGain += (targetPreGain - preGain) * PREGAIN_SMOOTH;
            float sL = src[f * 2] * preGain;
            float sR = src[f * 2 + 1] * preGain;

            // AutoEQ 打底（耳机修正，任意频点），可与其他并存叠加。
            if (autoEqOn) {
                sL = autoEqBand1L_.process(sL); sL = autoEqBand2L_.process(sL); sL = autoEqBand3L_.process(sL); sL = autoEqBand4L_.process(sL); sL = autoEqBand5L_.process(sL);
                sL = autoEqBand6L_.process(sL); sL = autoEqBand7L_.process(sL); sL = autoEqBand8L_.process(sL); sL = autoEqBand9L_.process(sL); sL = autoEqBand10L_.process(sL);
                sR = autoEqBand1R_.process(sR); sR = autoEqBand2R_.process(sR); sR = autoEqBand3R_.process(sR); sR = autoEqBand4R_.process(sR); sR = autoEqBand5R_.process(sR);
                sR = autoEqBand6R_.process(sR); sR = autoEqBand7R_.process(sR); sR = autoEqBand8R_.process(sR); sR = autoEqBand9R_.process(sR); sR = autoEqBand10R_.process(sR);
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
            if (transientEnabled_.load(std::memory_order_acquire)) {
                curTransientAmount_ += (transientAmount_.load(std::memory_order_acquire) - curTransientAmount_) * PREGAIN_SMOOTH;
                float amt = curTransientAmount_;
                if (fabsf(amt) > 0.001f) {
                    float aL = fabsf(sL);
                    tsFastEnvL_ += (aL - tsFastEnvL_) * (aL > tsFastEnvL_ ? tsAtkFast : tsRelFast);
                    tsSlowEnvL_ += (aL - tsSlowEnvL_) * (aL > tsSlowEnvL_ ? tsAtkSlow : tsRelSlow);
                    float transL = tsFastEnvL_ - tsSlowEnvL_;
                    float gainL = 1.0f + amt * transL * 4.0f;
                    if (gainL < 0.05f) gainL = 0.05f;
                    if (gainL > 4.0f) gainL = 4.0f;
                    sL *= gainL;
                    float aR = fabsf(sR);
                    tsFastEnvR_ += (aR - tsFastEnvR_) * (aR > tsFastEnvR_ ? tsAtkFast : tsRelFast);
                    tsSlowEnvR_ += (aR - tsSlowEnvR_) * (aR > tsSlowEnvR_ ? tsAtkSlow : tsRelSlow);
                    float transR = tsFastEnvR_ - tsSlowEnvR_;
                    float gainR = 1.0f + amt * transR * 4.0f;
                    if (gainR < 0.05f) gainR = 0.05f;
                    if (gainR > 4.0f) gainR = 4.0f;
                    sR *= gainR;
                }
            }

            // 【V8.3】动态压缩（master bus，stereo-linked）——瞬态整形之后、写 ring 之前。
            if (compressorEnabled_.load(std::memory_order_acquire)) {
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

            int base = (wp * 2) + f * 2;
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

    return claimed_.load(std::memory_order_acquire);
}

void UsbAudioDriver::releaseInterface() {
    claimed_.store(false, std::memory_order_release);
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

    if (isUac2_) {
        // [align Salt] Use the same full chain as findClockSourceId():
        // AS General bTerminalLink -> Input Terminal bTerminalID -> bCSourceID.
        int clockId = findClockSourceId();
        if (clockId < 0) clockId = 3;  // default from OT descriptor
        LOGI("trySetSampleRate: clockId=%d (via full terminal-link chain)", clockId);

        // UAC2: SET_CUR on the clock entity via AC interface (ifaceNum_).
        data[0] = (uint8_t)(rate & 0xFF);
        data[1] = (uint8_t)((rate >> 8) & 0xFF);
        data[2] = (uint8_t)((rate >> 16) & 0xFF);
        data[3] = (uint8_t)((rate >> 24) & 0xFF);
        ctrl.bRequestType = 0x21;                          // host鈫抎evice | class | interface
        ctrl.bRequest     = 0x01;                          // SET_CUR
        ctrl.wValue       = 0x0100;                        // CS_SAM_FREQ_CONTROL << 8
        // wIndex: (clockId << 8) | AC interface number (0 for TTGK)
        ctrl.wIndex       = (uint16_t)((clockId << 8) | 0x00);
        ctrl.wLength      = 4;
        ctrl.timeout      = 200;
        ctrl.data         = data;
        ret = ioctl(fd_, USBDEVFS_CONTROL, &ctrl);
        LOGI("trySetSampleRate(UAC2): clockId=%d rate=%d wIndex=0x%04X ret=%d",
             clockId, rate, ctrl.wIndex, ret);
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
    sampleRate_ = rate;
    return rate;
}

// Public setSampleRate: only safe when stream is NOT active.
// Since we now call trySetSampleRate from open() (before alt=1),
// this is mostly a no-op during normal start().
int UsbAudioDriver::setSampleRate(int rate) {
    if (fd_ < 0) return -1;
    if (streaming_.load(std::memory_order_acquire)) {
        LOGW("setSampleRate(%d): skipped 锟?stream active", rate);
        return sampleRate_;
    }
    return trySetSampleRate(rate);
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
    if (feedbackThread_.joinable()) {
        feedbackThread_.join();
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
    const int pktMaxFrames = packetSizeFrames();
    const int pktMaxBytes  = pktMaxFrames * bytesPerFrame_;
    const int urbBytes     = pktMaxBytes * kPacketsPerUrb;
    const double samplesPerMicroframe = sampleRate_ / (isUac2_ ? 8000.0 : 1000.0);

    LOGI("streamLoop: maxFrames=%d urbBytes=%d pkts/urb=%d hs=%d rate/mf=%.4f",
         pktMaxFrames, urbBytes, kPacketsPerUrb, isUac2_ ? 1 : 0, samplesPerMicroframe);

    // 銆怴3.2.7銆慞re-queue 12 URBs锛堝師4锛夛細纭欢鍦ㄩ闃熷垪 32ms锟?6ms锟?
    // 鎶楄皟搴︽姈鍔ㄢ€斺€旈暱鎾伓鍙戝崱椤挎牴鍥狅細stream 绾跨▼琚锟?>32ms 鍗虫柇锟?
    constexpr int kPreQueue = 12;
    for (int s = 0; s < kPreQueue; s++) {
        memset(urbSlots_[s].buffer, 0, urbBytes);
        urbSlots_[s].urb->number_of_packets = kPacketsPerUrb;
        urbSlots_[s].urb->buffer_length = urbBytes;
        for (int p = 0; p < kPacketsPerUrb; ++p) {
            urbSlots_[s].urb->iso_frame_desc[p].length = pktMaxBytes;
        }
        if (!submitUrbRaw(s)) {
            LOGE("streamLoop: pre-queue URB %d failed, aborting", s);
            streaming_.store(false, std::memory_order_release);
            return;
        }
    }
    int slot = kPreQueue;

    size_t reapUrbSize = sizeof(usbdevfs_urb) + kPacketsPerUrb * sizeof(usbdevfs_iso_packet_desc);
    auto* reapBuf = static_cast<uint8_t*>(calloc(1, reapUrbSize));
    if (!reapBuf) {
        LOGE("streamLoop: OOM for REAPURB buffer");
        streaming_.store(false, std::memory_order_release);
        return;
    }
    int urbCompletions = 0;
    double phaseAccum = 0.0;

    while (streaming_.load(std::memory_order_acquire)) {
        memset(reapBuf, 0, reapUrbSize);
        int ret = ioctl(fd_, USBDEVFS_REAPURB, reapBuf);
        if (ret < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            LOGE("REAPURB #%d failed: %s (errno=%d)", urbCompletions + 1, strerror(errno), errno);
            break;
        }
        urbCompletions++;
        urbCompleted_.fetch_add(1, std::memory_order_relaxed);
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

        int wp = writePos_.load(std::memory_order_acquire);
        int rp = readPos_.load(std::memory_order_acquire);
        int availFrames = (wp - rp + kRingFrames) % kRingFrames;

        uint8_t* buf = urbSlots_[slot].buffer;
        const int ringMask = kRingFrames * 2 - 1;
        int sampleOffset = 0;   // 字节偏移：urb 缓冲写入终点
        int sampleCursor = 0;   // 样本游标：本 URB 已输出样本总数(与字节偏移隔离，避免 /bytesPerSample 反推)
        int totalFramesNeeded = 0;

        // Phase accumulator: alternate 5/6 frames per microframe for exact 44100 Hz
        for (int p = 0; p < kPacketsPerUrb; ++p) {
            phaseAccum += samplesPerMicroframe;
            int nFrames = (int)phaseAccum;
            phaseAccum -= nFrames;
            totalFramesNeeded += nFrames;
            int nBytes = nFrames * bytesPerFrame_;
            urbSlots_[slot].urb->iso_frame_desc[p].length = nBytes;

            if (availFrames >= totalFramesNeeded) {
                int nSamples = nFrames * channels_;
                float vol = volume_;
                switch (bitsPerSample_) {
                case 16: {
                    auto* out = reinterpret_cast<int16_t*>(buf + sampleOffset);
                    const bool dither = ditherEnabled_.load(std::memory_order_relaxed);
                    for (int j = 0; j < nSamples; ++j) {
                        int idx = ((rp * 2) + sampleCursor + j) & ringMask;
                        float s = ringBuffer_[idx] * vol;
                        if (s >  1.0f) s =  1.0f;
                        if (s < -1.0f) s = -1.0f;
                        // TPDF dither: 两个独立均匀分布相减 = 三角分布,幅度 ±1 LSB,
                        // 消除量化失真(谐波)并去相关噪声。仅在降位到 16bit 时启用。
                        float dithered = s * 32767.0f;
                        if (dither) {
                            uint32_t u = ditherState_;
                            u = u * 1664525u + 1013904223u;   // Numerical Recipes LCG
                            ditherState_ = u;
                            uint32_t u2 = u * 1664525u + 1013904223u;
                            // two uniforms in [-0.5, 0.5], 叠加为三角分布
                            float t1 = ((u  >> 8) & 0xFFFF) / 65535.0f - 0.5f;
                            float t2 = ((u2 >> 8) & 0xFFFF) / 65535.0f - 0.5f;
                            dithered += (t1 + t2);   // ±1 LSB TPDF
                        }
                        out[j] = static_cast<int16_t>(dithered);
                    }
                    break;
                }
                case 24: {
                    // 銆怴3.2.7锟? 瀛楄妭 LE 鎵撳寘锛坅lt2 subslot=3锟?
                    uint8_t* out = buf + sampleOffset;
                    for (int j = 0; j < nSamples; ++j) {
                        int idx = ((rp * 2) + sampleCursor + j) & ringMask;
                        float s = ringBuffer_[idx] * vol;
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
                        double s = static_cast<double>(ringBuffer_[idx]) * vol;
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
                memset(buf + sampleOffset, 0, nBytes);
            }
            sampleOffset += nBytes;
            sampleCursor += nFrames * channels_;
        }

        if (availFrames >= totalFramesNeeded) {
            readPos_.store((rp + totalFramesNeeded) % kRingFrames, std::memory_order_release);
            totalSamplesOut_.fetch_add(totalFramesNeeded, std::memory_order_relaxed);
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

    free(reapBuf);
    streaming_.store(false, std::memory_order_release);
    LOGI("streamLoop: exit (%d URB completions)", urbCompletions);
}

void UsbAudioDriver::feedbackLoop() {
    // For async DACs with explicit feedback endpoint.
    // Reads the feedback value to adjust ring buffer pacing.
    // Simple implementation: poll feedback value, log deviation.
    LOGI("feedbackLoop: started");

    while (streaming_.load(std::memory_order_acquire)) {
        // If no feedback endpoint, just maintain timing
        std::this_thread::sleep_for(std::chrono::microseconds(kFeedbackIntervalUs));
    }

    LOGI("feedbackLoop: exit");
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

    // Pass 2: find AS General bTerminalLink, prefer the one on OUR streaming iface.
    int ourLink = -1, anyLink = -1, curIface = -1;
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
            if (anyLink < 0) anyLink = link;
            if (curIface == ifaceNum_) ourLink = link;
            LOGI("AS General: iface=%d bTerminalLink=%d", curIface, link);
        }
        pos += (dLen > 0) ? dLen : 1;
        if (dLen == 0) break;
    }

    int link = (ourLink >= 0) ? ourLink : anyLink;
    if (link >= 0 && link < 64 && termToClock[link] >= 0) {
        LOGI("Clock source via terminalLink=%d -> bCSourceID=%d", link, termToClock[link]);
        return termToClock[link];
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
    if (fd_ < 0) return -1;

    uint8_t desc[4096];
    int len = readConfigDescriptor(0, desc, sizeof(desc));
    if (len < 0) return -1;

    int pos = 0;
    while (pos < len) {
        uint8_t dLen  = desc[pos];
        uint8_t dType = (pos + 1 < len) ? desc[pos + 1] : 0;
        uint8_t dSub  = (pos + 2 < len) ? desc[pos + 2] : 0;

        // UAC 2.0: AS Isochronous Data Endpoint descriptor -> bmAttributes can signal
        // explicit feedback. But simpler: look for an IN isochronous endpoint paired
        // with the streaming interface.
        if (dType == USB_DT_ENDPOINT && dLen >= 7) {
            uint8_t epAddr = desc[pos + 2];
            uint8_t epAttr = desc[pos + 3];
            // IN, isochronous, different from our OUT endpoint
            if ((epAddr & 0x80) && (epAttr & 0x03) == 0x01 && epAddr != epAddress_) {
                // Check if it's part of an audio streaming interface
                // Simple heuristic: any IN isochronous endpoint that isn't our OUT
                LOGI("Found potential feedback endpoint: 0x%02X", epAddr);
                // [fix] Keep the FULL 8-bit bEndpointAddress (incl. bit7 direction).
                // Stripping to & 0x7F turns 0x81 -> 0x01 (OUT), which the kernel
                // rejects with -EINVAL/-EPIPE when we later submit an IN URB on it.
                return epAddr;
            }
        }

        pos += (dLen > 0) ? dLen : 1;
        if (dLen == 0) break;
    }

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

void UsbAudioDriver::parseFeatureUnit() {
    hardwareVolumeReady_ = false;
    featureUnitId_ = 0;
    featureUnitChannel_ = 0;

    // [通用化] 不按 VID/PID 硬编码，任何 DAC 都走同一套 UAC2 标准探测：
    // 遍历 AudioControl 段所有 Feature Unit(subtype 0x06)，对每个 FU 用
    // wLength=2 字节(16.16) 读 GET_MIN/GET_MAX，找到返回有效 dB 范围的即采用。
    // 关键：之前 STALL 是因为用了 descriptor 的 bControlSize 作为 wLength，
    // 而 UAC2 Volume control 标准宽度就是 2 字节（int16 dB*256），与 descriptor
    // 报的 bControlSize 常不一致 → 固定 2 字节探测即可。
    uint8_t desc[4096];
    int len = readConfigDescriptor(0, desc, sizeof(desc));
    if (len <= 0) return;

    int pos = 0;
    while (pos < len && pos + 6 < (int)sizeof(desc)) {
        uint8_t dLen  = desc[pos];
        uint8_t dType = desc[pos + 1];
        if (dType == UAC2_CS_INTERFACE && dLen >= 7) {
            uint8_t dSub = desc[pos + 2];
            if (dSub == 0x06) {   // FEATURE_UNIT
                uint8_t unitId       = desc[pos + 3];
                uint8_t sourceId     = desc[pos + 4];
                uint8_t bControlSize = desc[pos + 5];
                // FU channel count: bmaControls[] 每 channel 占 bControlSize 字节，
                // 头 7 字节 + 尾 iFeature 1 字节。channels = (dLen-8)/bControlSize
                // (含 master ch0)，若除不尽则用实际探测到的通道回退。
                int chCount = 0;
                if (bControlSize >= 1 && bControlSize <= 4 && dLen >= 8) {
                    chCount = (dLen - 8) / bControlSize;
                }
                if (chCount < 0) chCount = 0;
                uint16_t wIndex = (unitId << 8) | acIface_;
                int16_t minRaw = 0, maxRaw = 0;
                int rMin = -1, rMax = -1;
                int chUsed = 0;
                // 探测 master ch0 优先，失败则 ch1（无 master 的 FU）
                // wLength 固定 2 字节 = UAC2 Volume 标准 16.16 宽度
                for (int ch = 0; ch <= 1 && rMax < 2; ++ch) {
                    uint16_t wValue = (0x02 << 8) | ch;
                    minRaw = 0; maxRaw = 0;
                    rMin = controlTransfer(0xA1, 0x82, wValue, wIndex, &minRaw, 2, 100); // GET_MIN
                    rMax = controlTransfer(0xA1, 0x83, wValue, wIndex, &maxRaw, 2, 100); // GET_MAX
                    float minDb = minRaw / 256.0f;
                    float maxDb = maxRaw / 256.0f;
                    LOGI("parseFeatureUnit: FU id=%d src=%d ch=%d GET_MIN(ret=%d,%.1f dB) GET_MAX(ret=%d,%.1f dB)",
                         unitId, sourceId, ch, rMin, minDb, rMax, maxDb);
                    if (rMax >= 2 && maxDb <= 0.0f && maxDb > -128.0f) {
                        chUsed = ch;
                        featureUnitMaxDb_ = maxDb;
                        featureUnitMinDb_ = (rMin >= 2 && minDb < maxDb && minDb > -128.0f) ? minDb : -127.0f;
                        break;
                    }
                }
                if (rMax >= 2) {
                    featureUnitId_ = unitId;
                    featureUnitChannel_ = chUsed;
                    featureUnitChannels_ = (chCount > 0) ? chCount : 2;
                    featureUnitControlSize_ = 2;   // 标准 16.16，固定 2 字节
                    hardwareVolumeReady_ = true;
                    LOGI("Hardware volume: FU id=%d ch=%d(chUsed) chCount=%d, range=[%.1f, %.1f] dB",
                         featureUnitId_, featureUnitChannel_, featureUnitChannels_,
                         featureUnitMinDb_, featureUnitMaxDb_);
                    break;
                }
            }
        }
        pos += (dLen > 0) ? dLen : 1;
        if (dLen == 0) break;
    }

    if (!hardwareVolumeReady_) {
        featureUnitMinDb_ = -127.0f;
        featureUnitMaxDb_ = 0.0f;
        nativeLog("VOL", "no working Feature Unit volume found, hardware volume disabled");
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

bool UsbAudioDriver::setHardwareVolume(float pct) {
    if (!hardwareVolumeReady_ || featureUnitId_ <= 0 || fd_ < 0) return false;
    // [通用化] pct 是 cubic 后的值（pct^3）。软件 cubic taper 的等效 dB =
    // 20*log10(cubic)；硬件 FU 在芯片内做同等衰减，不损 SNR（软件 PCM 乘法会损 bit-depth）。
    float db = (pct > 1e-6f) ? 20.0f * log10f(pct) : featureUnitMinDb_;
    if (db > 0.0f) db = 0.0f;
    if (db < featureUnitMinDb_) db = featureUnitMinDb_;
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
    return ok > 0;
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
    urb->number_of_packets = kPacketsPerUrb;

    int ret = ioctl(fd_, USBDEVFS_SUBMITURB, urb);
    if (ret < 0 && errno != EAGAIN) {
        LOGE("submitUrbRaw[%d]: %s (errno=%d)", slot, strerror(errno), errno);
        urbErrors_.fetch_add(1, std::memory_order_relaxed);
        return false;
    }
    urbSubmitted_.fetch_add(1, std::memory_order_relaxed);
    return true;
}

// 鈹€鈹€ packet size helpers 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

int UsbAudioDriver::packetSizeFrames() const {
    // UAC2 high-speed: 8000 microframes/sec
    // Full-speed: 1000 frames/sec
    int tps = isUac2_ ? 8000 : 1000;
    return (sampleRate_ + tps - 1) / tps;  // ceil(sampleRate / transfers_per_sec)
}

int UsbAudioDriver::packetSizeBytes() const {
    return packetSizeFrames() * bytesPerFrame_;
}

const char* UsbAudioDriver::getSupportedRates() {
    return supportedRates_.c_str();
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

void* UsbAudioDriver::feedbackThreadEntry(void* arg) {
    auto* self = static_cast<UsbAudioDriver*>(arg);
    self->feedbackLoop();
    return nullptr;
}