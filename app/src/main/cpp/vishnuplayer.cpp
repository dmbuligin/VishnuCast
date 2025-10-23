#include <jni.h>
#include <android/log.h>

#define LOG_TAG "VishnuJNI"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

// Простой handle-генератор
static jlong next_handle = 1;

extern "C" {

// long createSource()
JNIEXPORT jlong JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_createSource(JNIEnv* env, jclass) {
    jlong h = next_handle++;
    ALOGD("createSource() -> %lld", static_cast<long long>(h));
    return h; // просто non-zero handle
}

// void destroySource(long h)
JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_destroySource(JNIEnv* env, jclass, jlong h) {
    ALOGD("destroySource(%lld)", static_cast<long long>(h));
}

// void sourceSetMuted(long h, boolean muted)
JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_sourceSetMuted(JNIEnv* env, jclass, jlong h, jboolean muted) {
    ALOGD("sourceSetMuted(%lld, %s)", static_cast<long long>(h), (muted ? "true" : "false"));
}

// (резерв на будущее) push PCM 48k mono — пока no-op
JNIEXPORT void JNICALL
Java_com_buligin_vishnucast_player_jni_PlayerJni_sourcePushPcm48kMono(JNIEnv* env, jclass, jlong h, jshortArray pcm, jint samples) {
    // no-op stub; только проверка входа
    (void)h; (void)pcm; (void)samples;
}

} // extern "C"
