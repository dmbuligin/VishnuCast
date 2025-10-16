#pragma once
#include <cstdint>
#include <vector>
#include <atomic>
#include <mutex>

/**
 * NativePlayerSource — буфер 10мс @ 48k mono s16.
 * Сейчас просто хранит последний фрейм — для отладки и подготовки.
 * Следующим шагом подключим его к libwebrtc как AudioSource.
 */
class NativePlayerSource {
public:
    explicit NativePlayerSource(int sampleRate = 48000, int channels = 1);
    ~NativePlayerSource();

    void pushPcm48kMono(const int16_t* data, int samples);
    void setMuted(bool muted);

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
