#include <jni.h>
#include <android/log.h>

#define LOG_TAG "VCMIX/JNI"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_audio_VcMix_nativeInit(JNIEnv* env, jclass clazz) {
ALOGI("nativeInit: vcmix JNI skeleton loaded");
}

extern "C" JNIEXPORT jboolean JNICALL
        Java_com_buligin_vishnucast_audio_VcMix_nativeIsReady(JNIEnv* env, jclass clazz) {
// Пока возвращаем true — библиотека подгрузилась.
return JNI_TRUE;
}
