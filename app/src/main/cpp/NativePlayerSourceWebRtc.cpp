#include "NativePlayerSourceWebRtc.h"
#include "NativePlayerSource.h"
#include <android/log.h>
#include <algorithm>
#include "absl/types/optional.h" // уже добавляли для OnData

#define LOG_TAG "VishnuJNI"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

using webrtc::AudioTrackSinkInterface;

NativePlayerSourceWebRtc::NativePlayerSourceWebRtc(NativePlayerSource* src)
        : source_(src) {
    ALOGD("NativePlayerSourceWebRtc ctor %p (src=%p)", this, src);
}

NativePlayerSourceWebRtc::~NativePlayerSourceWebRtc() {
    ALOGD("NativePlayerSourceWebRtc dtor %p", this);
}

// === MediaSourceInterface ===
webrtc::MediaSourceInterface::SourceState NativePlayerSourceWebRtc::state() const {
    return state_; // kLive
}

bool NativePlayerSourceWebRtc::remote() const {
    return remote_; // false (локальный)
}

// === AudioSourceInterface ===
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
                /*audio_data=*/data,
                /*bits_per_sample=*/16,
                /*sample_rate=*/48000,
                /*number_of_channels=*/1,
                /*number_of_frames=*/static_cast<size_t>(samples),
                /*absolute_capture_timestamp_ms=*/absl::nullopt);
    }
}
