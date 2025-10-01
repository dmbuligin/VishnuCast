package com.buligin.vishnucast.audio

import android.content.Context
import android.media.MediaRecorder
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * MixedAudioDeviceModule — каркас под Mix 2.0.
 *
 * Сейчас это аккуратная обёртка, полностью делегирующая в JavaAudioDeviceModule.
 * Позже здесь будет подключена JNI-реализация подмены входного буфера (Mic→Mix),
 * без смены публичного API и без правок в остальных классах.
 */
class MixedAudioDeviceModule private constructor(
    private val delegate: JavaAudioDeviceModule
) {
    fun asJavaModule(): JavaAudioDeviceModule = delegate

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

        /** Колбэк оставляем — он полезен для валидации/диагностик. */
        fun setSamplesReadyCallback(cb: JavaAudioDeviceModule.SamplesReadyCallback?) = apply { samplesReadyCallback = cb }

        /**
         * Текущая реализация — чистый делегат на JavaAudioDeviceModule.
         * Позже внутри этого метода добавим JNI-вставку с подменой буфера.
         */
        fun createAudioDeviceModule(): MixedAudioDeviceModule {
            val j = JavaAudioDeviceModule.builder(appContext)
                .setUseHardwareAcousticEchoCanceler(useHwAec)
                .setUseHardwareNoiseSuppressor(useHwNs)
                .setAudioSource(audioSource)
                .setUseStereoInput(stereoIn)
                .setUseStereoOutput(stereoOut)
                .setSamplesReadyCallback(samplesReadyCallback)
                .createAudioDeviceModule()
            return MixedAudioDeviceModule(j)
        }
    }

    companion object {
        fun builder(appContext: Context): Builder = Builder(appContext.applicationContext)
    }
}
