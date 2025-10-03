package com.buligin.vishnucast.audio

import kotlin.math.tanh

/**
 * Смешивание микрофона (@48k mono, 10мс окно) с плеером (@48k mono).
 * Ключ: используем СТРОГО последовательную выборку из PlayerPcmBus.take48kMono(n),
 * чтобы окно плеера точно соответствовало текущему 10мс-срезу микрофона.
 *
 * Дополнительно:
 *  - лёгкий high-pass (20 Гц, 1-го порядка) на плеере для среза DC/денормалов;
 *  - мягкий лимитер (tanh) и -6 dB gain staging, чтобы избежать клиппинга на сумме.
 */
object MixerEngine {

    // ===== HPF(20 Hz) state for player (per-process, cheap) =====
    // y[n] = x[n] - x[n-1] + a * y[n-1], a = e^{-2π f_c / Fs}
    private const val HPF_A: Float = 0.99738544f // f_c≈20 Hz при Fs=48000
    private var hpfPrevX: Float = 0f
    private var hpfPrevY: Float = 0f

    fun mixMicWithPlayer48kMono(
        mic10ms: ShortArray,
        alpha01: Float
    ): ShortArray {
        val n = mic10ms.size
        if (n == 0) return mic10ms

        val a = alpha01.coerceIn(0f, 1f)
        val b = 1f - a

        // 1) Берём СЛЕДУЮЩИЕ n сэмплов плеера (строго последовательное окно).
        // Если данных пока не хватает — тишина, чтобы сохранить фазу и курсор.
        val player: FloatArray = PlayerPcmBus.take48kMono(n, 300L) ?: FloatArray(n)

        // 2) HPF(20 Hz) на плеере.
        hpf(player)

        // 3) mic short->float [-1..1]
        val mic = FloatArray(n)
        var i = 0
        while (i < n) {
            mic[i] = mic10ms[i] / 32768f
            i++
        }

        // 4) Сумма + -6 dB staging + мягкий лимитер
        val outF = FloatArray(n)
        i = 0
        while (i < n) {
            val x = mic[i] * b + player[i] * a
            outF[i] = tanh(x * 0.5f) // 0.5 ≈ -6 dB
            i++
        }

        // 5) float -> PCM16
        val outS = ShortArray(n)
        i = 0
        while (i < n) {
            val v = (outF[i] * 32767f).toInt()
            outS[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            i++
        }
        return outS
    }

    private fun hpf(buf: FloatArray) {
        var px = hpfPrevX
        var py = hpfPrevY
        var i = 0
        while (i < buf.size) {
            val x = buf[i]
            val y = (x - px) + HPF_A * py
            buf[i] = y
            px = x
            py = y
            i++
        }
        hpfPrevX = px
        hpfPrevY = py
    }
}
