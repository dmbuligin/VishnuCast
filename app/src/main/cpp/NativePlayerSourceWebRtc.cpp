#include "NativePlayerSourceWebRtc.h"
#include "NativePlayerSource.h"
#include <android/log.h>
#include <algorithm>

#define LOG_TAG "VishnuJNI"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

using webrtc::AudioTrackSinkInterface;

NativePlayerSourceWebRtc::NativePlayerSourceWebRtc(NativePlayerSource* src)
        : source_(src) {
    ALOGD("NativePlayerSourceWebRtc ctor %p (src=%p)", this, src);
}

NativePlayerSourceWebRtc::~NativePlayerSourceWebRtc() {
    ALOGD("NativePlayerSourceWebRtс dtor %p", this);
}

void NativePlayerSourceWebRtc::AddSink(AudioTrackSinkInterface* sink) {
    webrtc::MutexLock lock(&sinks_mutex_);
    sinks_.push_back(sink);
}

void NativePlayerSourceWebRtc::RemoveSink(AudioTrackSinkInterface* sink) {
    webrtc::MutexLock lock(&sinks_mutex_);
    sinks_.erase(std::remove(sinks_.begin(), sinks_.end(), sink), sinks_.end());
}

void NativePlayerSourceWebRtc::Push10ms(const int16_t* data, int samples) {
    if (!data || samples <= 0) return;
    webrtc::MutexLock lock(&sinks_mutex_);
    for (auto* s : sinks_) {
        s->OnData(
                data,
                samples,                   // 480
                48000,                     // sample rate
                1,                         // channels mono
                samples * sizeof(int16_t), // bytes per 10ms
                AudioTrackSinkInterface::kInt16,
                /*absolute_capture_timestamp_ms=*/0);
    }
}
