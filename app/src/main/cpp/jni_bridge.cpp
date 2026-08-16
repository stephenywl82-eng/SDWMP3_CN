#include <jni.h>
#include <string>
#include <memory>
#include <mutex>
#include "oboe_audio_engine.h"

static std::unique_ptr<OboeAudioEngine> g_engine;
static std::mutex g_mutex;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_sdw_music_player_player_OboeAudioEngine_nativeCreate(JNIEnv* env, jclass clazz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_engine = std::make_unique<OboeAudioEngine>();
    return reinterpret_cast<jlong>(g_engine.get());
}

JNIEXPORT void JNICALL
Java_com_sdw_music_player_player_OboeAudioEngine_nativeDestroy(JNIEnv* env, jclass clazz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_engine.reset();
}

JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_player_OboeAudioEngine_nativeStart(JNIEnv* env, jclass clazz, jlong handle, jint sampleRate, jint channelCount) {
    OboeAudioEngine* engine = reinterpret_cast<OboeAudioEngine*>(handle);
    if (!engine) return JNI_FALSE;
    return engine->start(sampleRate, channelCount) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_sdw_music_player_player_OboeAudioEngine_nativeStop(JNIEnv* env, jclass clazz, jlong handle) {
    OboeAudioEngine* engine = reinterpret_cast<OboeAudioEngine*>(handle);
    if (engine) engine->stop();
}

JNIEXPORT jint JNICALL
Java_com_sdw_music_player_player_OboeAudioEngine_nativeWriteData(JNIEnv* env, jclass clazz, jlong handle, jshortArray jdata, jint numSamples) {
    OboeAudioEngine* engine = reinterpret_cast<OboeAudioEngine*>(handle);
    if (!engine || !jdata) return 0;

    jshort* data = env->GetShortArrayElements(jdata, nullptr);
    if (!data) return 0;

    jint written = static_cast<jint>(engine->writeData(data, numSamples));
    env->ReleaseShortArrayElements(jdata, data, JNI_ABORT);
    return written;
}

JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_player_OboeAudioEngine_nativeIsRunning(JNIEnv* env, jclass clazz, jlong handle) {
    OboeAudioEngine* engine = reinterpret_cast<OboeAudioEngine*>(handle);
    if (!engine) return JNI_FALSE;
    return engine->isRunning() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
