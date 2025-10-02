package com.buligin.vishnucast.audio

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Кольцевой буфер плеера: всегда MONO 48 kHz, float [-1..1].
 * На вход принимает interleaved float[] (ch=1..N) при любом sample rate.
 * Внутри: downmix→mono, при необходимости resample→48k, запись в ring.
 */
object PlayerPcmBus {

    // 1 сек. буфер на 48k — с запасом для стабильности (низкие задержки, без разрывов)
    private const val RING_CAP = 48_000

    // Кольцо и индексы
    private val ring = FloatArray(RING_CAP)
    @Volatile private var writeIdx = 0
    @Volatile private var filled = 0

    // Свежесть потока (для mute/паузы)
    @Volatile private var lastWriteAtMs: Long = 0L

    /** Сброс буфера (на паузе/ошибке/стопе) */
    fun clear() {
        writeIdx = 0
        filled = 0
        lastWriteAtMs = 0L
        // Нули в массиве не обязательны — filled=0 достаточно
    }

    /**
     * Принять interleaved float[], downmix→mono, resample→48k (если нужно) и записать в кольцо.
     * @param src interleaved [-1..1]
     * @param ch  количество каналов в src (>=1)
     * @param sr  sample rate в src (Гц)
     */
    fun push(src: FloatArray, ch: Int, sr: Int) {
        if (src.isEmpty() || ch <= 0 || sr <= 0) return

        // Downmix → mono
        val frames = src.size / ch
        if (frames <= 0) return
        val mono = FloatArray(frames)
        var si = 0
        var f = 0
        if (ch == 1) {
            // Быстрый путь
            while (f < frames) {
                mono[f] = src[si]
                si += 1
                f++
            }
        } else {
            while (f < frames) {
                var acc = 0f
                var c = 0
                while (c < ch) { acc += src[si + c]; c++ }
                mono[f] = acc / ch
                si += ch
                f++
            }
        }

        // Resample → 48k, если нужно
        val mono48: FloatArray = if (sr == 48_000) mono else resampleLinear(mono, sr, 48_000)

        // Запись в кольцо
        writeContinuous(mono48)

        lastWriteAtMs = System.currentTimeMillis()
    }

    /** Вернуть непрерывный хвост длиной tailSamples (обычно 480), если поток свежий. */
    fun tail48kMono(tailSamples: Int, maxAgeMs: Long = 300L): FloatArray? {
        if (tailSamples <= 0) return null
        val age = System.currentTimeMillis() - lastWriteAtMs
        if (age > maxAgeMs || filled == 0) return null

        val n = min(tailSamples, min(filled, RING_CAP))
        val out = FloatArray(n)

        val end = writeIdx // позиция следующей записи = «конец»
        var start = end - n
        if (start < 0) start += RING_CAP

        if (start + n <= RING_CAP) {
            // без обёртки
            System.arraycopy(ring, start, out, 0, n)
        } else {
            // с обёрткой
            val first = RING_CAP - start
            System.arraycopy(ring, start, out, 0, first)
            System.arraycopy(ring, 0, out, first, n - first)
        }

        return out
    }

    // ===== helpers =====

    private fun writeContinuous(mono48: FloatArray) {
        var srcIdx = 0
        val n = mono48.size
        if (n == 0) return

        while (srcIdx < n) {
            val toEnd = RING_CAP - writeIdx
            val run = min(toEnd, n - srcIdx)
            System.arraycopy(mono48, srcIdx, ring, writeIdx, run)
            writeIdx = (writeIdx + run) % RING_CAP
            srcIdx += run
            filled = min(RING_CAP, filled + run)
        }
    }

    private fun resampleLinear(src: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (src.isEmpty()) return src
        val ratio = dstRate.toDouble() / srcRate.toDouble()
        val outLen = max(1, (src.size * ratio).toInt())
        val out = FloatArray(outLen)

        val maxIdx = src.size - 1
        var i = 0
        while (i < outLen) {
            val pos = i / ratio
            val i0 = floor(pos).toInt().coerceIn(0, maxIdx)
            val i1 = min(i0 + 1, maxIdx)
            val frac = (pos - i0)
            val s = src[i0] * (1.0 - frac) + src[i1] * frac
            out[i] = s.toFloat()
            i++
        }
        return out
    }
}
