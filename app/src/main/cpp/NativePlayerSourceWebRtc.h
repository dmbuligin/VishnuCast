#pragma once
#include "api/media_stream_interface.h"         // AudioSourceInterface, AudioTrackSinkInterface
#include "api/scoped_refptr.h"                  // webrtc::scoped_refptr
#include "rtc_base/ref_counted_object.h"        // webrtc::RefCountedObject
#include "rtc_base/synchronization/mutex.h"     // webrtc::Mutex, webrtc::MutexLock
#include <vector>
#include <cstdint>

class NativePlayerSource;

/**
 * Реальный WebRTC-аудиоисточник (mono 48k, s16).
 * На каждый 10мс кадр вызывает OnData(...) у всех подписчиков.
 */
class NativePlayerSourceWebRtc : public webrtc::AudioSourceInterface {
public:
    explicit NativePlayerSourceWebRtc(NativePlayerSource* src);
    ~NativePlayerSourceWebRtc() override;


    // === MediaSourceInterface обязательные методы ===
    webrtc::MediaSourceInterface::SourceState state() const override;
    bool remote() const override;

    // === AudioSourceInterface ===
    void AddSink(webrtc::AudioTrackSinkInterface* sink) override;
    void RemoveSink(webrtc::AudioTrackSinkInterface* sink) override;

    // Не используем, но обязаны реализовать интерфейс:
    void RegisterObserver(webrtc::ObserverInterface*) override {}
    void UnregisterObserver(webrtc::ObserverInterface*) override {}
    void SetVolume(double) override {}

    // Кормим 10мс PCM (480 сэмплов) в источник.
    void Push10ms(const int16_t* data, int samples);

private:
    NativePlayerSource* source_;                 // не владеем
    webrtc::Mutex sinks_mutex_;
    std::vector<webrtc::AudioTrackSinkInterface*> sinks_;

    // Локальные значения для обязательных геттеров
    webrtc::MediaSourceInterface::SourceState state_ = webrtc::MediaSourceInterface::kLive;
    bool remote_ = false; // локальный источник
};
