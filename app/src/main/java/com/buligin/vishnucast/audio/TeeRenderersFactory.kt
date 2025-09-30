package com.buligin.vishnucast.audio

import android.content.Context
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.audio.AudioProcessor
import com.google.android.exoplayer2.audio.DefaultAudioSink
import com.google.android.exoplayer2.audio.AudioSink

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
        // Собираем DefaultAudioSink с дополнительными процессорами.
        val builder = DefaultAudioSink.Builder()
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)

            .setOffloadMode(
                if (enableOffload) DefaultAudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED
                else DefaultAudioSink.OFFLOAD_MODE_DISABLED
            )

        if (extraProcessors.isNotEmpty()) {
            builder.setAudioProcessors(extraProcessors)
        }

        return builder.build()
    }
}
