package com.buligin.vishnucast.audio

import android.content.Context
import android.media.MediaRecorder
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * 4.3-A: пока возвращаем «золотой» Java ADM.
 * ВАЖНО: SamplesReadyCallback задаётся на BUILDER, а не на инстанс ADM.
 */
object NativeAdm {

    private fun defaultJavaAdm(
        context: Context,
        samplesCb: JavaAudioDeviceModule.SamplesReadyCallback?
    ): AudioDeviceModule {
        val builder = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setUseStereoInput(false)
            .setUseStereoOutput(false)

        if (samplesCb != null) {
            builder.setSamplesReadyCallback(samplesCb)
        }

        return builder.createAudioDeviceModule()
    }

    /**
     * На 4.3-A игнорируем useNative и возвращаем JADM с навешанным samplesCb.
     * На 4.3-B здесь появится загрузка libvishnuadm и возврат нативного ADM.
     */
    fun create(
        context: Context,
        useNative: Boolean,
        samplesCb: JavaAudioDeviceModule.SamplesReadyCallback?
    ): AudioDeviceModule {
        return defaultJavaAdm(context, samplesCb)
    }
}
