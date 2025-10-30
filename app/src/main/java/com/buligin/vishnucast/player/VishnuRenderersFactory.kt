// app/src/main/java/com/buligin/vishnucast/player/VishnuRenderersFactory.kt
package com.buligin.vishnucast.player

import android.content.Context
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.audio.AudioProcessor
import com.google.android.exoplayer2.audio.AudioSink
import com.google.android.exoplayer2.audio.DefaultAudioSink

/**
 * Рендерер-фабрика, которая подсовывает DefaultAudioSink с нашим RingTapAudioProcessor.
 * Остальная логика/декодеры остаются «как у Exo».
 */
class VishnuRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
        enableOffload: Boolean
    ): AudioSink {
        val processors: Array<AudioProcessor> = arrayOf(RingTapAudioProcessor())
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)                 // <-- ВСЕГДА 16-бит PCM
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(processors)
            .build()
    }
}

