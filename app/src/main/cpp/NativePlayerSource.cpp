#include "NativePlayerSource.h"
#include <algorithm>

NativePlayerSource::NativePlayerSource(int sampleRate, int channels)
        : sr_(sampleRate), ch_(channels), frame_samples_(480), last_frame_(480, 0) {
}

NativePlayerSource::~NativePlayerSource() = default;

void NativePlayerSource::pushPcm48kMono(const int16_t* data, int samples) {
    if (!data) return;
    if (muted_.load()) return;
    if (samples < frame_samples_) return;

    std::lock_guard<std::mutex> lock(mtx_);
    std::copy(data, data + frame_samples_, last_frame_.begin());
    has_frame_ = true;
}

void NativePlayerSource::setMuted(bool muted) {
    muted_.store(muted);
}

bool NativePlayerSource::readLastFrame(std::vector<int16_t>& out) {
    std::lock_guard<std::mutex> lock(mtx_);
    if (!has_frame_) return false;
    out = last_frame_;
    return true;
}
