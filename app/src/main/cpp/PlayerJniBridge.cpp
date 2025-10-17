#include <jni.h>
#include <android/log.h>

#define LOG_TAG "VishnuJNI"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Флаг компиляции: включить/выключить нативный WebRTC-источник.
// Задаётся из CMake: -DVISHNU_ENABLE_NATIVE_SOURCE=0|1
#ifndef VISHNU_ENABLE_NATIVE_SOURCE
#define VISHNU_ENABLE_NATIVE_SOURCE 0
#endif

// ==== Всегда используемая часть: движок отправки в старой схеме ====
#include "PlayerSendEngine.h"

static JavaVM* g_vm = nullptr;

extern "C" jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

// --------------------------------------------------------------------------------------
//                                  Engine (старый путь)
// --------------------------------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_com_buligin_vishnucast_player_1jni_PlayerJni_createEngine(JNIEnv*, jobject) {
    auto* eng = new PlayerSendEngine();
    return reinterpret_cast<jlong>(eng);
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_1jni_PlayerJni_destroyEngine(JNIEnv*, jobject, jlong ptr) {
    auto* eng = reinterpret_cast<PlayerSendEngine*>(ptr);
    delete eng;
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_1jni_PlayerJni_setMuted(JNIEnv*, jobject, jlong ptr, jboolean muted) {
    auto* eng = reinterpret_cast<PlayerSendEngine*>(ptr);
    if (!eng) return;
    eng->setMuted(muted == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_1jni_PlayerJni_pushPcm48kMono(JNIEnv* env, jobject, jlong ptr, jshortArray pcm, jint samples) {
    auto* eng = reinterpret_cast<PlayerSendEngine*>(ptr);
    if (!eng || !pcm) return;

    jboolean isCopy = JNI_FALSE;
    jshort* data = env->GetShortArrayElements(pcm, &isCopy);
    if (!data) return;

    eng->pushPcm48kMono(reinterpret_cast<int16_t*>(data), samples);
    env->ReleaseShortArrayElements(pcm, data, JNI_ABORT);
}

// --------------------------------------------------------------------------------------
//                        Нативный источник WebRTC: ON/OFF по флагу
// --------------------------------------------------------------------------------------

#if VISHNU_ENABLE_NATIVE_SOURCE

// ===== Реальная реализация (вариант A) =====
#include <unordered_map>
#include <mutex>

#include "NativePlayerSource.h"
#include "NativePlayerSourceWebRtc.h"

#include "api/peer_connection_interface.h" // webrtc::PeerConnectionFactoryInterface
#include "api/scoped_refptr.h"             // webrtc::scoped_refptr
#include "rtc_base/ref_counted_object.h"   // webrtc::RefCountedObject

// Глобальная карта: NativePlayerSource* -> NativePlayerSourceWebRtc*
static std::unordered_map<NativePlayerSource*, webrtc::scoped_refptr<NativePlayerSourceWebRtc>> g_srcMap;
static std::mutex g_mapMtx;

extern "C" JNIEXPORT jlong JNICALL
Java_com_buligin_vishnucast_player_1jni_PlayerJni_createSource(JNIEnv*, jobject) {
    auto* src = new NativePlayerSource(48000, 1);
    ALOGD("NativePlayerSource created %p", src);
    return reinterpret_cast<jlong>(src);
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_1jni_PlayerJni_destroySource(JNIEnv*, jobject, jlong ptr) {
    auto* src = reinterpret_cast<NativePlayerSource*>(ptr);
    if (!src) return;

    {
        std::lock_guard<std::mutex> lk(g_mapMtx);
        g_srcMap.erase(src);
    }
    delete src;
    ALOGD("NativePlayerSource destroyed %p", src);
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_1jni_PlayerJni_sourceSetMuted(JNIEnv*, jobject, jlong /*ptr*/, jboolean /*muted*/) {
    // Mute в C++ не используем; логика на уровне WebRtcCore/enc.active
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_1jni_PlayerJni_sourcePushPcm48kMono(JNIEnv* env, jobject, jlong ptr, jshortArray pcm, jint samples) {
    auto* src = reinterpret_cast<NativePlayerSource*>(ptr);
    if (!src || !pcm) return;

    jboolean isCopy = JNI_FALSE;
    jshort* data = env->GetShortArrayElements(pcm, &isCopy);
    if (!data) return;

    src->pushPcm48kMono(reinterpret_cast<int16_t*>(data), samples);

    {
        std::lock_guard<std::mutex> lk(g_mapMtx);
        auto it = g_srcMap.find(src);
        if (it != g_srcMap.end() && it->second) {
            it->second->Push10ms(reinterpret_cast<int16_t*>(data), samples);
        }
    }

    env->ReleaseShortArrayElements(pcm, data, JNI_ABORT);
}

// Создание WebRTC-трека из нашего источника
extern "C" JNIEXPORT jlong JNICALL
Java_com_buligin_vishnucast_player_1jni_PlayerJni_createWebRtcPlayerTrack(
        JNIEnv* env, jobject /*thiz*/, jobject jFactory, jlong nativeSrcPtr) {

    if (!jFactory || nativeSrcPtr == 0) {
        ALOGE("createWebRtcPlayerTrack: null args");
        return 0;
    }

    jclass cls = env->GetObjectClass(jFactory);
    if (!cls) { ALOGE("createWebRtcPlayerTrack: no class"); return 0; }

    jfieldID fid = env->GetFieldID(cls, "nativeFactory", "J");
    if (!fid) { ALOGE("createWebRtcPlayerTrack: field nativeFactory not found"); return 0; }

    jlong nativeFactoryPtr = env->GetLongField(jFactory, fid);
    if (!nativeFactoryPtr) { ALOGE("createWebRtcPlayerTrack: nativeFactory is 0"); return 0; }

    auto* factory = reinterpret_cast<webrtc::PeerConnectionFactoryInterface*>(nativeFactoryPtr);
    auto* src     = reinterpret_cast<NativePlayerSource*>(nativeSrcPtr);

    webrtc::scoped_refptr<NativePlayerSourceWebRtc> nativeSrc =
            webrtc::make_ref_counted<NativePlayerSourceWebRtc>(src);

    {
        std::lock_guard<std::mutex> lk(g_mapMtx);
        g_srcMap[src] = nativeSrc;
    }

    webrtc::scoped_refptr<webrtc::AudioTrackInterface> track =
            factory->CreateAudioTrack("VC_PLAYER_NATIVE", nativeSrc.get());

    ALOGD("createWebRtcPlayerTrack: created track=%p (factory=%p, src=%p)", track.get(), factory, src);
    return reinterpret_cast<jlong>(track.release()); // владелец → Java org.webrtc.AudioTrack
}

#else  // =========================== STUBS (вариант B, 2PC) ===============================

// ===== Заглушки тех же JNI-сигнатур, чтобы приложение собиралось/работало без нативного источника =====

extern "C" JNIEXPORT jlong JNICALL
Java_com_buligin_vishnucast_player_1jni_PlayerJni_createSource(JNIEnv*, jobject) {
    ALOGD("createSource(): stub (VISHNU_ENABLE_NATIVE_SOURCE=0) -> return 0");
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_1jni_PlayerJni_destroySource(JNIEnv*, jobject, jlong /*ptr*/) {
    ALOGD("destroySource(): stub (no-op)");
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_1jni_PlayerJni_sourceSetMuted(JNIEnv*, jobject, jlong /*ptr*/, jboolean /*muted*/) {
    ALOGD("sourceSetMuted(): stub (no-op)");
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_1jni_PlayerJni_sourcePushPcm48kMono(JNIEnv*, jobject, jlong /*ptr*/, jshortArray /*pcm*/, jint /*samples*/) {
    // no-op
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_buligin_vishnucast_player_1jni_PlayerJni_createWebRtcPlayerTrack(
        JNIEnv*, jobject /*thiz*/, jobject /*jFactory*/, jlong /*nativeSrcPtr*/) {
    ALOGD("createWebRtcPlayerTrack(): stub (VISHNU_ENABLE_NATIVE_SOURCE=0) -> return 0");
    return 0;
}

#endif  // VISHNU_ENABLE_NATIVE_SOURCE
