#include <jni.h>
#include <android/log.h>

#define LOG_TAG "VCMIX/STUB"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// В будущем здесь появятся JNI-методы, которые будут принимать/возвращать 10мс буферы.
// Пока — пусто. Оставлено намеренно.
