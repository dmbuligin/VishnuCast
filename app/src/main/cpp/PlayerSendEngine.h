#pragma once
#include <stdint.h>
#include <atomic>
#include <thread>
#include <vector>

// Вперёд-объявления из webrtc
namespace webrtc {
    class AudioTrackSourceInterface;
    class AudioTransport;
}

class PlayerSendEngine {
public:
    PlayerSendEngine();
    ~PlayerSendEngine();

    // Создаёт webrtc source, к которому мы позже привяжем AudioTrack
    // Возвращает сырую ptr (жизненный цикл управляем здесь)
    webrtc::AudioTrackSourceInterface* createSource();

    // Кормить PCM (48k, mono/16-bit) из AudioPlaybackCapture (или любого источника)
    void pushPcm48kMono(const int16_t* data, int samples); // samples = 480 на 10мс

    // Вкл/выкл (мьют) подачи
    void setMuted(bool muted);

private:
    std::atomic<bool> muted_{false};
    // Буферизация/джиттер — добавим позже, по факту
};
