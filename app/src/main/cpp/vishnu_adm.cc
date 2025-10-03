// STUB: vishnu_adm.cc — без зависимостей от WebRTC headers.
// Позволяет собрать проект и работать в MIC-режиме, пока не подключим SDK заголовки.

#include <jni.h>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "VishnuADM", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  "VishnuADM", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "VishnuADM", __VA_ARGS__)

// Делаем минимальный JNI_OnLoad, чтобы so корректно грузилась.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    LOGI("vishnuadm STUB loaded (no WebRTC headers; returning null ADM)");
    return JNI_VERSION_1_6;
}

// Фабрика нативного ADM: в стубе всегда возвращаем null.
// Java-сторона увидит null и уйдёт в фоллбэк (Java ADM / MIC).
extern "C" JNIEXPORT jobject JNICALL
Java_com_buligin_vishnucast_audio_NativeAdm_nativeCreatePlayerAdm(
        JNIEnv* env, jobject /*thiz*/, jobject /*appContext*/) {
    LOGW("nativeCreatePlayerAdm(): STUB returns null — native ADM unavailable");
    return nullptr;
}
