#include "PlayerSendEngine.h"
// Заглушки: настоящую интеграцию с webrtc добавим после включения CMake

PlayerSendEngine::PlayerSendEngine() {}
PlayerSendEngine::~PlayerSendEngine() {}

webrtc::AudioTrackSourceInterface* PlayerSendEngine::createSource() {
    // TODO: вернуть реальный webrtc::AudioTrackSourceInterface
    return nullptr;
}

void PlayerSendEngine::pushPcm48kMono(const int16_t* /*data*/, int /*samples*/) {
    if (muted_) return;
    // TODO: положить 10мс фрейм в транспорт webrtc
}

void PlayerSendEngine::setMuted(bool m) {
    muted_.store(m);
}
