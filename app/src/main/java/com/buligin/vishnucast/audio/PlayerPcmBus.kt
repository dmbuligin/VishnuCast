package com.buligin.vishnucast.audio

import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference

/**
 * Хранит "последнее окно" PCM плеера (float, interleaved).
 * На B2 это окно будет считываться микшером вместе с микрофоном.
 */
object PlayerPcmBus {
    // последний буфер и его валидная длина сэмплов
    private val last = AtomicReference<FloatArray?>(null)
    @Volatile var channels: Int = 2
    @Volatile var sampleRate: Int = 48000

    fun push(samples: FloatArray, ch: Int, sr: Int) {
        channels = ch
        sampleRate = sr
        // сохраняем копию, чтобы жизненный цикл исходного массива не влиял
        val dst = if (last.get()?.size == samples.size) last.get()!! else FloatArray(samples.size)
        System.arraycopy(samples, 0, dst, 0, samples.size)
        last.set(dst)
    }

    fun latest(): FloatArray? = last.get()
}
