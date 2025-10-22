#pragma once

#include <cstdint>
#include <atomic>
#include <vector>
#include <mutex>

/** Очень тонкий источник PCM: хранит последние пришедшие 10-мс кадры.
 *  На этом шаге это просто безопасная заглушка под JNI.
 *  Формат: 48kHz, mono, s16.
 */
struct NativePcmSource {
    std::atomic<bool> muted { false };

    // Простейший ring-буфер по кадрам (каждый кадр = 480 сэмплов).
    static constexpr int kFrameSamples = 480;
    static constexpr int kMaxFrames = 128; // ~1.28 сек буфера — с запасом

    std::vector<int16_t> data; // размер = kFrameSamples * kMaxFrames
    size_t writeIndex = 0;     // в кадрах
    size_t sizeFrames = 0;     // фактическое число записанных кадров (<= kMaxFrames)
    std::mutex mtx;

    NativePcmSource() {
        data.resize(kFrameSamples * kMaxFrames);
    }

    void clear() {
        std::lock_guard<std::mutex> lg(mtx);
        writeIndex = 0;
        sizeFrames = 0;
        std::fill(data.begin(), data.end(), 0);
    }

    // Пишем кратно 10-мс, можно пачкой (несколько кадров подряд)
    void pushFrames(const int16_t* pcm, int samples) {
        if (!pcm || samples <= 0) return;
        const int frames = samples / kFrameSamples;
        if (frames <= 0) return;

        std::lock_guard<std::mutex> lg(mtx);
        for (int f = 0; f < frames; ++f) {
            const int16_t* src = pcm + f * kFrameSamples;
            int16_t* dst = &data[(writeIndex % kMaxFrames) * kFrameSamples];
            std::memcpy(dst, src, sizeof(int16_t) * kFrameSamples);
            writeIndex = (writeIndex + 1) % kMaxFrames;
            if (sizeFrames < kMaxFrames) sizeFrames++;
        }
    }
};
