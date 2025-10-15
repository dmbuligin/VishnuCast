#include "PlayerSendEngine.h"
#include <android/log.h>

#define LOG_TAG "VishnuJNI"

PlayerSendEngine::PlayerSendEngine() {}
PlayerSendEngine::~PlayerSendEngine() {}

void PlayerSendEngine::pushPcm48kMono(const int16_t* /*data*/, int /*samples*/) {
    if (muted_) return;
    // Скелет: позже здесь положим фрейм в аудио-транспорт WebRTC.
    // Сейчас — no-op.
}

void PlayerSendEngine::setMuted(bool muted) {
    muted_.store(muted);
}
