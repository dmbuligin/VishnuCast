package com.buligin.vishnucast.audio

import android.content.Context
import org.webrtc.audio.AudioDeviceModule

object NativeAdm {
    init {
        try { System.loadLibrary("vishnuadm") } catch (_: Throwable) { /* будет понятная ошибка при вызове */ }
    }

    /**
     * Создаёт нативный ADM, который каждые 10мс отдаёт в WebRTC кадры из PlayerPcmBus.
     * Отдаёт null, если либу не удалось загрузить.
     */
    fun createPlayerOnlyAdm(context: Context): AudioDeviceModule? {
        return nativeCreatePlayerAdm(context.applicationContext)
    }

    // JNI
    private external fun nativeCreatePlayerAdm(appContext: Any): AudioDeviceModule?
}
