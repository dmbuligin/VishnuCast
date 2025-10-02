package com.buligin.vishnucast.audio


object MixerEngine {

    fun mixMicWithPlayer48kMono(
        mic10ms: ShortArray,
        alpha01: Float
    ): ShortArray {
        val n = mic10ms.size
        if (n == 0) return mic10ms

        // Доля плеера
        val a = alpha01.coerceIn(0f, 1f)

        // Готовый непрерывный хвост плеера @48k mono (float)
        val playerMono48 = PlayerPcmBus.tail48kMono(n, maxAgeMs = 300L)

        // Если плеера нет — чистый микрофон
        if (playerMono48 == null) {
            return mic10ms.copyOf()
        }

        // Конвертируем player float → short
        val playerShort = ShortArray(n)
        var i = 0
        while (i < n) {
            val v = (playerMono48[i] * 32767.0f).toInt()
            playerShort[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            i++
        }

        // Небольшой запас по уровню, чтобы не клиппило при сумме
        val micGain = 0.85f
        val plGain  = 0.85f
        val inv = 1f - a

        // Смешиваем
        val out = ShortArray(n)
        i = 0
        while (i < n) {
            val micS = (mic10ms[i] * micGain).toInt()
            val plS  = (playerShort[i] * plGain).toInt()
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
