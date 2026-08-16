#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <unistd.h>

#include "usb_audio_driver.h"

#define TAG "UsbDacJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static UsbAudioDriver* gUsbDriver = nullptr;

static UsbAudioDriver* getDriver() {
    if (!gUsbDriver) {
        gUsbDriver = new UsbAudioDriver();
    }
    return gUsbDriver;
}

// flac_decoder.cpp 用：共享同一个 driver 单例（ring buffer 直入）
UsbAudioDriver* sdw_getUsbDriver() {
    return getDriver();
}

extern "C" {

// ── nativeUsbAvailable ───────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeUsbAvailable(JNIEnv*, jobject) {
    return JNI_TRUE;
}

// ── nativeClaim ──────────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeClaim(
    JNIEnv* env, jobject /* thiz */,
    jint vid, jint pid, jint fd, jint address, jint maxPacketSize,
    jint interval, jboolean isUac2, jint ifaceNum) {

    auto* driver = getDriver();
    if (!driver) return JNI_FALSE;

    int dupFd = dup(fd);
    if (dupFd < 0) {
        LOGE("nativeClaim: dup(fd=%d) failed", fd);
        return JNI_FALSE;
    }

    bool ok = driver->open(dupFd, address, maxPacketSize, interval,
                           isUac2 == JNI_TRUE, vid, pid, ifaceNum);
    LOGI("nativeClaim: vid=%04X pid=%04X fd=%d ep=0x%02X iface=%d -> %s",
         vid, pid, dupFd, address, ifaceNum, ok ? "OK" : "FAIL");
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ── nativeUsbStart ───────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeUsbStart(
    JNIEnv*, jobject, jint sampleRate, jint channels, jint bitsPerSample) {

    auto* driver = getDriver();
    if (!driver) return JNI_FALSE;

    bool ok = driver->start(sampleRate, channels, bitsPerSample);
    LOGI("nativeUsbStart: sr=%d ch=%d bits=%d -> %s",
         sampleRate, channels, bitsPerSample, ok ? "OK" : "FAIL");
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ── nativePushPcm ────────────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativePushPcm(
    JNIEnv* env, jobject, jfloatArray data, jint frameCount) {

    auto* driver = getDriver();
    if (!driver) return -1;

    jfloat* elements = env->GetFloatArrayElements(data, nullptr);
    if (!elements) return -1;

    int pushed = driver->pushPcm(elements, frameCount);
    env->ReleaseFloatArrayElements(data, elements, JNI_ABORT);
    return pushed;
}

// ── nativeGetRingFill ─────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeGetRingFill(JNIEnv*, jobject) {
    auto* driver = getDriver();
    return driver ? driver->getRingFillFrames() : 0;
}

// ── nativeStop ───────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeStop(JNIEnv*, jobject) {
    auto* driver = getDriver();
    if (driver) driver->stop();
    LOGI("nativeStop");
}

// ── nativeResetRingBuffer ───────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeResetRingBuffer(JNIEnv*, jobject) {
    auto* driver = getDriver();
    if (driver) driver->resetRingBuffer();
    LOGI("nativeResetRingBuffer");
}

// ── nativeStopThreadOnly ─────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeStopThreadOnly(JNIEnv*, jobject) {
    auto* driver = getDriver();
    if (driver) driver->stopThreadOnly();
    LOGI("nativeStopThreadOnly");
}

// ── nativeRelease ────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeRelease(JNIEnv*, jobject) {
    if (gUsbDriver) {
        delete gUsbDriver;
        gUsbDriver = nullptr;
    }
    LOGI("nativeRelease");
}

// ── nativeIsClaimed ──────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeIsClaimed(JNIEnv*, jobject) {
    auto* driver = getDriver();
    if (!driver) return JNI_FALSE;
    return driver->isClaimed() ? JNI_TRUE : JNI_FALSE;
}

// ── nativeGetUnderrunCount ───────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeGetUnderrunCount(JNIEnv*, jobject) {
    auto* driver = getDriver();
    if (!driver) return 0;
    return driver->getUnderrunCount();
}

// ── nativeGetCurrentSampleRate ───────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeGetCurrentSampleRate(JNIEnv*, jobject) {
    auto* driver = getDriver();
    if (!driver) return 0;
    return driver->getSampleRate();
}

JNIEXPORT jint JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeGetCurrentBits(JNIEnv*, jobject) {
    auto* driver = getDriver();
    if (!driver) return 0;
    return driver->getBitsPerSample();
}


// ── nativeGetDacName ─────────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeGetDacName(JNIEnv* env, jobject) {
    auto* driver = getDriver();
    if (!driver) return env->NewStringUTF("No DAC");
    return env->NewStringUTF(driver->getDacName());
}

// ── nativeGetDetailedInfo ────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeGetDetailedInfo(JNIEnv* env, jobject) {
    auto* driver = getDriver();
    if (!driver) return env->NewStringUTF("No DAC connected");
    return env->NewStringUTF(driver->getDetailedInfo());
}

// ── nativeGetSupportedRates ──────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeGetSupportedRates(JNIEnv* env, jobject) {
    auto* driver = getDriver();
    if (!driver) return env->NewStringUTF("48000");
    return env->NewStringUTF(driver->getSupportedRates());
}

// ── nativeGetStats ───────────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeGetStats(JNIEnv* env, jobject) {
    auto* driver = getDriver();
    if (!driver) return env->NewStringUTF("No DAC");

    UsbDacStats stats;
    driver->getStats(stats);

    char buf[512];
    snprintf(buf, sizeof(buf),
        "URB submitted=%llu completed=%llu errors=%llu | Ring avail=%d frames | Watermark=%d/%dms | Health=%s",
        (unsigned long long)stats.urbSubmitted,
        (unsigned long long)stats.urbCompleted,
        (unsigned long long)stats.urbErrors,
        stats.ringAvailFrames,
        stats.bufferWatermarkMs,
        stats.targetWatermarkMs,
        stats.healthState);
    return env->NewStringUTF(buf);
}

// ── nativeSetVolume ──────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeSetVolume(JNIEnv*, jobject, jfloat volume) {
    auto* driver = getDriver();
    if (driver) driver->setVolume(volume);
}

// ── nativeSetDitherEnabled ──────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeSetDitherEnabled(JNIEnv*, jobject, jboolean enabled) {
    auto* driver = getDriver();
    if (driver) driver->setDitherEnabled(enabled == JNI_TRUE);
}

// ── nativeSetFeatureUnitOverride ─────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeSetFeatureUnitOverride(
    JNIEnv*, jobject, jint unitId, jint channel, jint channels,
    jfloat minDb, jfloat maxDb, jfloat resDb) {
    auto* driver = getDriver();
    if (driver) driver->setFeatureUnitOverride(unitId, channel, channels, minDb, maxDb, resDb);
}

// ── nativeSetDspEnabled ──────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeSetDspEnabled(JNIEnv*, jobject, jboolean enabled) {
    auto* driver = getDriver();
    if (driver) driver->setDspEnabled(enabled == JNI_TRUE);
}

// ── nativeSetDspEq5Band ──────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeSetDspEq5Band(
    JNIEnv* env, jobject, jfloatArray gainsDb, jfloatArray freqsHz) {
    auto* driver = getDriver();
    if (!driver) return;
    jfloat* gains = env->GetFloatArrayElements(gainsDb, nullptr);
    if (!gains) return;
    jfloat* freqs = freqsHz ? env->GetFloatArrayElements(freqsHz, nullptr) : nullptr;
    jsize len = env->GetArrayLength(gainsDb);
    driver->setDspEq5Band(gains, freqs, len);
    env->ReleaseFloatArrayElements(gainsDb, gains, 0);
    if (freqs) env->ReleaseFloatArrayElements(freqsHz, freqs, 0);
}

// ── nativeResetDspEq5Band ────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeResetDspEq5Band(JNIEnv*, jobject) {
    auto* driver = getDriver();
    if (driver) driver->resetDspEq5Band();
}

// ── nativeGetDebugLog ────────────────────────────────────────────────────

// ── nativeForceReset ────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeForceReset(JNIEnv*, jobject) {
    auto* driver = getDriver();
    if (driver) driver->forceReset();
    LOGI("nativeForceReset");
}

// ── nativeGetDebugLog ────────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeGetDebugLog(JNIEnv* env, jobject) {
    auto* driver = getDriver();
    if (!driver) return env->NewStringUTF("Driver not initialized");
    return env->NewStringUTF(driver->getNativeDebugLog());
}

} // extern "C"
