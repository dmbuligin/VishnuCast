#pragma once
#include "api/media_stream_interface.h"         // AudioSourceInterface, AudioTrackSinkInterface
#include "rtc_base/ref_counted_object.h"        // rtc::RefCountedObject
#include "rtc_base/synchronization/mutex.h"     // webrtc::Mutex, webrtc::MutexLock
#include <vector>
#include <cstdint>

class NativePlayerSource;

/**
 * Реальный WebRTC-аудиоисточник (mono 48k, s16).
 * На каждый 10мс кадр вызывает OnData(...) у всех подписчиков.
 */
class NativePlayerSourceWebRtc : public rtc::RefCountedObject<webrtc::AudioSourceInterface> {
public:
    explicit NativePlayerSourceWebRtc(NativePlayerSource* src);
    ~NativePlayerSourceWebRtc() override;

    // webrtc::MediaSourceInterface / AudioSourceInterface
    void AddSink(webrtc::AudioTrackSinkInterface* sink) override;
    void RemoveSink(webrtc::AudioTrackSinkInterface* sink) override;

    // Не используем, но обязаны реализовать интерфейс:
    void RegisterObserver(webrtc::ObserverInterface*) override {}
    void UnregisterObserver(webrtc::ObserverInterface*) override {}
    void SetState(webrtc::MediaSourceInterface::SourceState) override {}
    void SetVolume(double) override {}

    // Кормим 10мс PCM (480 сэмплов) в источник.
    void Push10ms(const int16_t* data, int samples);

private:
    NativePlayerSource* source_;                 // не владеем
    webrtc::Mutex sinks_mutex_;
    std::vector<webrtc::AudioTrackSinkInterface*> sinks_;
};
