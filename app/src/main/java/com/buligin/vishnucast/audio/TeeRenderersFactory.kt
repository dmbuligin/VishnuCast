package com.buligin.vishnucast.audio

import android.content.Context
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.audio.AudioProcessor
import com.google.android.exoplayer2.audio.DefaultAudioSink
import com.google.android.exoplayer2.audio.AudioSink
import android.util.Log


/**
 * RenderersFactory, который добавляет наш Tee AudioProcessor в выходной AudioSink.
 */
class TeeRenderersFactory(
    private val appContext: Context,
    private val extraProcessors: Array<AudioProcessor> = arrayOf()
) : DefaultRenderersFactory(appContext) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
        enableOffload: Boolean
    ): AudioSink {
        val caps = com.google.android.exoplayer2.audio.AudioCapabilities.getCapabilities(context)

        val builder = DefaultAudioSink.Builder()
            .setAudioCapabilities(caps)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            // Процессоры (наш Tee) несовместимы с offload → принудительно выключаем
            .setOffloadMode(DefaultAudioSink.OFFLOAD_MODE_DISABLED)

        if (extraProcessors.isNotEmpty()) {
            builder.setAudioProcessors(extraProcessors)
        }

        Log.d("VC/TeeRF", "buildAudioSink: caps=$caps float=$enableFloatOutput offload=DISABLED extra=${extraProcessors.size}")
        return builder.build()
    }





}
