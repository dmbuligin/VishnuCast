package com.buligin.vishnucast.audio

import java.util.concurrent.atomic.AtomicReference

/**
 * Хранит "последнее окно" PCM плеера (float, interleaved) + метку времени.
 * Микшер читает только "свежие" данные; при простое — микс деградирует в Mic.
 */
object PlayerPcmBus {
    private val last = AtomicReference<FloatArray?>(null)

    @Volatile var channels: Int = 2
        private set
    @Volatile var sampleRate: Int = 48000
        private set
    @Volatile private var lastAtMs: Long = 0L

    fun push(samples: FloatArray, ch: Int, sr: Int) {
        channels = ch
        sampleRate = sr
        val dst = if (last.get()?.size == samples.size) last.get()!! else FloatArray(samples.size)
        System.arraycopy(samples, 0, dst, 0, samples.size)
        last.set(dst)
        lastAtMs = System.currentTimeMillis()
    }

    /** Последний буфер, если он свежий (не старше maxAgeMs). Иначе null. */
    fun latestFresh(maxAgeMs: Long = 200L): FloatArray? {
        val buf = last.get() ?: return null
        val age = System.currentTimeMillis() - lastAtMs
        return if (age <= maxAgeMs) buf else null
    }

    /** Явный сброс – вызываем, когда плеер остановлен/пауза. */
    fun clear() {
        last.set(null)
        lastAtMs = 0L
    }
}
