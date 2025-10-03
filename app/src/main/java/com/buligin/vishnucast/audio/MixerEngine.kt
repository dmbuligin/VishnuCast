package com.buligin.vishnucast.audio

import com.buligin.vishnucast.WebRtcCore   // для доступа к флагу MIX20_ENABLED

/**
 * Смешивание микрофона (@48k mono, 10мс = 480 сэмплов) с плеером (@48k mono).
 * Без HPF, без нелинейной компрессии — линейная сумма с адаптивным стадированием.
 *
 * ВАЖНО:
 *  - Если из кольца плеера текущий фрейм недоступен, используем ПОСЛЕДНИЙ валидный фрейм (hold-last),
 *    а не нули — это предотвращает щелчки.
 */
object MixerEngine {

    // Кэш последнего валидного окна плеера, чтобы не «проваливаться» в нули
    private var lastPlayerFrame: FloatArray? = null

    fun mixMicWithPlayer48kMono(
        mic10ms: ShortArray,
        alpha01: Float
    ): ShortArray {
        val n = mic10ms.size
        if (n == 0) return mic10ms

        val a = alpha01.coerceIn(0f, 1f)
        val b = 1f - a
        // -3 dB только когда участвуют оба потока, на краях 0 dB
        val gain = if (a == 0f || b == 0f) 1f else 0.70710678f

        // 1) СЛЕДУЮЩИЕ n сэмплов из плеера (строго последовательное окно).
        val playerNow: FloatArray? = PlayerPcmBus.take48kMono(n, 200L)
        val player: FloatArray = when {
            playerNow != null -> { lastPlayerFrame = playerNow; playerNow }
            lastPlayerFrame != null && lastPlayerFrame!!.size == n -> lastPlayerFrame!!
            else -> FloatArray(n) // впервые после старта — тишина
        }

        // 2) mic short->float [-1..1]
        val mic = FloatArray(n)
        var i = 0
        while (i < n) {
            mic[i] = mic10ms[i] / 32768f
            i++
        }

        // 3) Линейная сумма, безопасный клип и конверсия → PCM16
        val out = ShortArray(n)
        i = 0
        while (i < n) {
            var x = (mic[i] * b + player[i] * a) * gain
            if (x > 1f) x = 1f else if (x < -1f) x = -1f
            out[i] = (x * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            i++
        }

        // 4) Публикация 10-мс кадра в шину для ADM — НИКАК не влияет на текущий звук.
        if (WebRtcCore.MIX20_ENABLED) {
            MixBus48k.push10ms(out)
        }

        return out
    }
}
