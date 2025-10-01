package com.buligin.vishnucast.audio

import android.content.Context
import android.media.MediaRecorder
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * MixedAudioDeviceModule — каркас под Mix 2.0.
 *
 * Сейчас это делегат на JavaAudioDeviceModule с тем же Builder-API.
 * Позже внутри createAudioDeviceModule() будет вызов нативной (JNI) реализации,
 * но сигнатуры и точки подключения останутся прежними.
 */
object MixedAudioDeviceModule {

    class Builder internal constructor(private val appContext: Context) {
        private var useHwAec = false
        private var useHwNs = false
        private var audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
        private var stereoIn = false
        private var stereoOut = false
        private var samplesReadyCallback: JavaAudioDeviceModule.SamplesReadyCallback? = null

        fun setUseHardwareAcousticEchoCanceler(enable: Boolean) = apply { useHwAec = enable }
        fun setUseHardwareNoiseSuppressor(enable: Boolean) = apply { useHwNs = enable }
        fun setAudioSource(source: Int) = apply { audioSource = source }
        fun setUseStereoInput(enable: Boolean) = apply { stereoIn = enable }
        fun setUseStereoOutput(enable: Boolean) = apply { stereoOut = enable }
        fun setSamplesReadyCallback(cb: JavaAudioDeviceModule.SamplesReadyCallback?) = apply { samplesReadyCallback = cb }

        /** Возвращаем ИНТЕРФЕИС ADM — сейчас делегируем в JavaAudioDeviceModule. */
        fun createAudioDeviceModule(): AudioDeviceModule {
            return JavaAudioDeviceModule.builder(appContext)
                .setUseHardwareAcousticEchoCanceler(useHwAec)
                .setUseHardwareNoiseSuppressor(useHwNs)
                .setAudioSource(audioSource)
                .setUseStereoInput(stereoIn)
                .setUseStereoOutput(stereoOut)
                .setSamplesReadyCallback(samplesReadyCallback)
                .createAudioDeviceModule()
        }
    }

    fun builder(appContext: Context): Builder = Builder(appContext.applicationContext)
}
