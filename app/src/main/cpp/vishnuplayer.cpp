#include "vishnuplayer.h"

#include <jni.h>
#include <android/log.h>
#include <new>
#include <cstring>

#define LOG_TAG "VishnuJNI"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---- утилиты ----
template<typename T>
static inline jlong ptr_to_jlong(T* p) { return reinterpret_cast<jlong>(p); }
template<typename T>
static inline T* jlong_to_ptr(jlong v) { return reinterpret_cast<T*>(v); }

// ---- реализация «ядра» (общие функции, которые зовут JNI-обёртки) ----
static jlong core_createSource() {
    try {
        auto* src = new NativePcmSource();
        ALOGD("createSource() -> %p", (void*)src);
        return ptr_to_jlong(src);
    } catch (std::bad_alloc&) {
        ALOGE("createSource OOM");
        return 0;
    }
}

static void core_destroySource(jlong ptr) {
    auto* src = jlong_to_ptr<NativePcmSource>(ptr);
    ALOGD("destroySource(%p)", (void*)src);
    delete src;
}

static void core_sourceSetMuted(jlong ptr, jboolean muted) {
    auto* src = jlong_to_ptr<NativePcmSource>(ptr);
    if (!src) return;
    src->muted.store(muted == JNI_TRUE);
    ALOGD("sourceSetMuted = %s", src->muted.load() ? "true" : "false");
}

static void core_sourcePush(JNIEnv* env, jlong ptr, jshortArray pcm, jint samples) {
    auto* src = jlong_to_ptr<NativePcmSource>(ptr);
    if (!src || !pcm || samples <= 0) return;

    jboolean isCopy = JNI_FALSE;
    jshort* p = env->GetShortArrayElements(pcm, &isCopy);
    if (!p) return;

    const int frames = (samples / NativePcmSource::kFrameSamples);
    if (frames > 0) {
        src->pushFrames(reinterpret_cast<int16_t*>(p), frames * NativePcmSource::kFrameSamples);
    }
    env->ReleaseShortArrayElements(pcm, p, JNI_ABORT);
}

static jlong core_createEngine() {
    // безопасная заглушка «движка» — совместимость со старыми вызовами
    return core_createSource();
}

static void core_destroyEngine(jlong ptr) {
    core_destroySource(ptr);
}

static void core_engineSetMuted(jlong ptr, jboolean muted) {
    core_sourceSetMuted(ptr, muted);
}

static void core_enginePush(JNIEnv* env, jlong ptr, jshortArray pcm, jint samples) {
    core_sourcePush(env, ptr, pcm, samples);
}

static jlong core_createWebRtcPlayerTrack(JNIEnv* /*env*/, jobject /*factory*/, jlong /*srcPtr*/) {
    // Пока заглушка: вернём 0. Реальную интеграцию добавим после успешной сборки.
    ALOGW("createWebRtcPlayerTrack: stub (returns 0) — real track will be implemented next step");
    return 0;
}

// ---- JNI: для instance-методов (2-й параметр jobject) ----
extern "C" JNIEXPORT jlong JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_createSource(
        JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return core_createSource();
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_destroySource(
        JNIEnv* env, jobject thiz, jlong ptr) {
    (void)env; (void)thiz;
    core_destroySource(ptr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_sourceSetMuted(
        JNIEnv* env, jobject thiz, jlong ptr, jboolean muted) {
    (void)thiz;
    core_sourceSetMuted(ptr, muted);
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_sourcePushPcm48kMono(
        JNIEnv* env, jobject thiz, jlong ptr, jshortArray pcm, jint samples) {
    (void)thiz;
    core_sourcePush(env, ptr, pcm, samples);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_createEngine(
        JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return core_createEngine();
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_destroyEngine(
        JNIEnv* env, jobject thiz, jlong ptr) {
    (void)env; (void)thiz;
    core_destroyEngine(ptr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_setMuted(
        JNIEnv* env, jobject thiz, jlong ptr, jboolean muted) {
    (void)env; (void)thiz;
    core_engineSetMuted(ptr, muted);
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_pushPcm48kMono(
        JNIEnv* env, jobject thiz, jlong ptr, jshortArray pcm, jint samples) {
    (void)thiz;
    core_enginePush(env, ptr, pcm, samples);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_createWebRtcPlayerTrack(
        JNIEnv* env, jobject thiz, jobject factory, jlong nativeSrcPtr) {
    (void)thiz;
    return core_createWebRtcPlayerTrack(env, factory, nativeSrcPtr);
}

// ---- JNI: для static-методов (2-й параметр jclass) ----
extern "C" JNIEXPORT jlong JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_createSource__(
        JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return core_createSource();
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_destroySource__J(
        JNIEnv* env, jclass clazz, jlong ptr) {
    (void)env; (void)clazz;
    core_destroySource(ptr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_sourceSetMuted__JZ(
        JNIEnv* env, jclass clazz, jlong ptr, jboolean muted) {
    (void)clazz;
    core_sourceSetMuted(ptr, muted);
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_sourcePushPcm48kMono__J_3SI(
        JNIEnv* env, jclass clazz, jlong ptr, jshortArray pcm, jint samples) {
    (void)clazz;
    core_sourcePush(env, ptr, pcm, samples);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_createEngine__(
        JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return core_createEngine();
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_destroyEngine__J(
        JNIEnv* env, jclass clazz, jlong ptr) {
    (void)env; (void)clazz;
    core_destroyEngine(ptr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_setMuted__JZ(
        JNIEnv* env, jclass clazz, jlong ptr, jboolean muted) {
    (void)env; (void)clazz;
    core_engineSetMuted(ptr, muted);
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_pushPcm48kMono__J_3SI(
        JNIEnv* env, jclass clazz, jlong ptr, jshortArray pcm, jint samples) {
    (void)clazz;
    core_enginePush(env, ptr, pcm, samples);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_createWebRtcPlayerTrack__Ljava_lang_Object_2J(
        JNIEnv* env, jclass clazz, jobject factory, jlong nativeSrcPtr) {
    (void)clazz;
    return core_createWebRtcPlayerTrack(env, factory, nativeSrcPtr);
}
