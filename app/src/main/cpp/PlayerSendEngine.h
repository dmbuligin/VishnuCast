#pragma once
#include <atomic>
#include <cstdint>
#include <vector>

class PlayerSendEngine {
public:
    PlayerSendEngine();
    ~PlayerSendEngine();

    // Пока без реального webrtc::AudioTrackSourceInterface — добавим позже.
    // Эти методы — API для JNI.
    void pushPcm48kMono(const int16_t* data, int samples); // 10мс = 480 сэмплов
    void setMuted(bool muted);

private:
    std::atomic<bool> muted_{false};
};
