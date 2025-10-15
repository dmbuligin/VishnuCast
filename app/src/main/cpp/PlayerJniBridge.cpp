#include <jni.h>
#include <android/log.h>
#include "PlayerSendEngine.h"

#define LOG_TAG "VishnuJNI"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static JavaVM* g_vm = nullptr;

extern "C" jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_createEngine(JNIEnv*, jobject) {
    auto* eng = new PlayerSendEngine();
    return reinterpret_cast<jlong>(eng);
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_destroyEngine(JNIEnv*, jobject, jlong ptr) {
auto* eng = reinterpret_cast<PlayerSendEngine*>(ptr);
delete eng;
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_setMuted(JNIEnv*, jobject, jlong ptr, jboolean muted) {
auto* eng = reinterpret_cast<PlayerSendEngine*>(ptr);
if (!eng) return;
eng->setMuted(muted == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_pushPcm48kMono(JNIEnv* env, jobject, jlong ptr, jshortArray pcm, jint samples) {
auto* eng = reinterpret_cast<PlayerSendEngine*>(ptr);
if (!eng) return;
if (!pcm) return;

jboolean isCopy = JNI_FALSE;
jshort* data = env->GetShortArrayElements(pcm, &isCopy);
if (!data) return;

eng->pushPcm48kMono(reinterpret_cast<int16_t*>(data), samples);

env->ReleaseShortArrayElements(pcm, data, JNI_ABORT);
}
