package com.buligin.vishnucast.audio

/**
 * Микшер Mic + Player для 10-мс окна @48kHz mono.
 * Mic приходит как PCM16 (short[]), Player берём готовым хвостом из PlayerPcmBus (float @48k mono).
 */
object MixerEngine {

    // ~1.3 ms кросс-фейд на стыке 10мс окон (48 kHz → 64 сэмпла)
    private const val CF_SAMPLES = 64
    @Volatile private var prevPlayerTail: ShortArray? = null

    /**
     * @param mic10ms 10-мс окно микрофона @48k mono, PCM16
     * @param alpha01 доля плеера (0..1)
     */
    fun mixMicWithPlayer48kMono(
        mic10ms: ShortArray,
        alpha01: Float
    ): ShortArray {
        val a = alpha01.coerceIn(0f, 1f)
        val n = mic10ms.size
        if (n == 0) return mic10ms

        // Забираем непрерывный хвост N сэмплов @48k mono от плеера
        val playerMono48 = PlayerPcmBus.tail48kMono(n, maxAgeMs = 300L)

        // Если нет свежих данных плеера — чисто Mic (и обнуляем хвост кросс-фейда)
        if (playerMono48 == null) {
            prevPlayerTail = null
            return mic10ms.copyOf()
        }

        // Player: float→short
        val playerShort = FloatArrayToPcm16(playerMono48)

        // Плавный стык с предыдущим окном плеера, чтобы убрать фон на границах
        prevPlayerTail?.let { tail ->
            val c = minOf(CF_SAMPLES, n, tail.size)
            var i = 0
            while (i < c) {
                val w = (i + 1).toFloat() / c.toFloat()   // 0→1
                val old = tail[i].toInt()
                val cur = playerShort[i].toInt()
                val blended = ((1f - w) * old + w * cur).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                playerShort[i] = blended.toShort()
                i++
            }
        }
        // Сохраняем хвост текущего блока для следующей стыковки
        run {
            val c = minOf(CF_SAMPLES, n)
            if (c > 0) {
                val tail = ShortArray(c)
                System.arraycopy(playerShort, n - c, tail, 0, c)
                prevPlayerTail = tail
            } else prevPlayerTail = null
        }

        // Микс Mic ↔ Player
        val out = ShortArray(n)
        val inv = 1f - a
        var i = 0
        while (i < n) {
            val micS = mic10ms[i].toInt()
            val plS  = playerShort[i].toInt()
            var mix = (inv * micS + a * plS).toInt()
            if (mix > Short.MAX_VALUE.toInt()) mix = Short.MAX_VALUE.toInt()
            else if (mix < Short.MIN_VALUE.toInt()) mix = Short.MIN_VALUE.toInt()
            out[i] = mix.toShort()
            i++
        }
        return out
    }

    // ===== helpers =====

    private fun FloatArrayToPcm16(src: FloatArray): ShortArray {
        val out = ShortArray(src.size)
        var i = 0
        while (i < src.size) {
            val v = (src[i] * 32767.0f).toInt()
            out[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            i++
        }
        return out
    }
}
