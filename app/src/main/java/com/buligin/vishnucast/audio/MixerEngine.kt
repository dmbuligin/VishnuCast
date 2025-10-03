package com.buligin.vishnucast.audio

/**
 * Смешивание микрофона (@48k mono, 10мс = 480 сэмплов) с плеером (@48k mono).
 * Без HPF, без нелинейной компрессии — чистая линейная сумма со стадированием -6 dB.
 *
 * ВАЖНО:
 *  - Если из кольца плеера текущий фрейм недоступен, используем ПОСЛЕДНИЙ валидный фрейм (hold-last-frame),
 *    а не нули. Это предотвращает «щелчки/хрюки» от ступенчатых провалов.
 */
object MixerEngine {

    // Кэш последнего валидного окна плеера, чтобы не «проваливаться» в нули
    private var lastPlayerFrame: FloatArray? = null

  //  @Volatile private var playerUnders: Long = 0

    fun mixMicWithPlayer48kMono(
        mic10ms: ShortArray,
        alpha01: Float
    ): ShortArray {
        val n = mic10ms.size
        if (n == 0) return mic10ms

        val a = alpha01.coerceIn(0f, 1f)
        val b = 1f - a
        val gain = 0.5f // -6 dB стадирование суммы

        // 1) Берём СЛЕДУЮЩИЕ n сэмплов из плеера (строгое последовательное окно).
        // Если недоступно — держим последний валидный кадр (без сдвига курсора в кольце).
        val playerNow: FloatArray? = PlayerPcmBus.take48kMono(n, 200L)
        val player: FloatArray = when {
            playerNow != null -> {
                lastPlayerFrame = playerNow
                playerNow
            }
            lastPlayerFrame != null && lastPlayerFrame!!.size == n -> lastPlayerFrame!!
            else -> FloatArray(n) // впервые после старта — тишина, это нормально
        }

        // 2) mic short->float [-1..1]
        val mic = FloatArray(n)
        var i = 0
        while (i < n) {
            mic[i] = mic10ms[i] / 32768f
            i++
        }

        // 3) Линейная сумма со стадированием, затем безопасный клип в [-1..1]
        val out = ShortArray(n)
        i = 0
        while (i < n) {
            var x = (mic[i] * b + player[i] * a) * gain
            if (x > 1f) x = 1f else if (x < -1f) x = -1f
            out[i] = (x * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            i++
        }
        return out
    }
}
