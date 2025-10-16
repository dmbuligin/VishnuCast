#pragma once
#include <cstdint>
#include <vector>
#include <atomic>
#include <mutex>

/**
 * NativePlayerSource — заготовка нативного аудио-источника под WebRTC.
 * Принимает 10мс фреймы PCM 48k mono s16 через pushPcm48kMono(...).
 * На этом шаге хранит последние фреймы в кольцевом буфере (без webrtc::...).
 * Следующим шагом будет интеграция с libwebrtc (AudioSourceInterface).
 */
class NativePlayerSource {
public:
    explicit NativePlayerSource(int sampleRate = 48000, int channels = 1);
    ~NativePlayerSource();

    void pushPcm48kMono(const int16_t* data, int samples);
    void setMuted(bool muted);

    // Вспомогательное API на будущее: получить копию последнего 10мс фрейма
    bool readLastFrame(std::vector<int16_t>& out);

private:
    const int sr_;
    const int ch_;
    const int frame_samples_; // 10мс @ 48k = 480

    std::atomic<bool> muted_{false};
    std::mutex mtx_;
    std::vector<int16_t> last_frame_;
    bool has_frame_{false};
};
