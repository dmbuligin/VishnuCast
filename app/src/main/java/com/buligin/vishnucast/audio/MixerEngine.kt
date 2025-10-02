package com.buligin.vishnucast.audio

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MixerEngine — подготовка к реальному миксу Mic+Player в одну дорожку для WebRTC.
 * Шаг 4.0: только утилиты преобразований + микширование; в тракте WebRTC пока не используется.
 *
 * Входы:
 *  - mic10ms:   short[] моно 48 kHz, окно 10 ms (480 сэмплов) — как даёт WebRTC AudioRecord
 *  - PlayerPcmBus.latest(): float[] interleaved, ch = 1|2, sampleRate = произвольный (обычно 48 kHz)
 *
 * Выход:
 *  - short[] моно 48 kHz такой же длины, как у mic10ms, со смесью:
 *      out = (1 - alpha) * mic + alpha * player
 *
 * Безопасность: «софтклиппер» при сумме > 32767 или < -32768.
 */
object MixerEngine {

    fun mixMicWithPlayer48kMono(
        mic10ms: ShortArray,
        alpha01: Float
    ): ShortArray {
        val a = alpha01.coerceIn(0f, 1f)
        if (mic10ms.isEmpty()) return mic10ms

        // Берём только свежий буфер плеера (иначе — деградация в Mic)
        val src = PlayerPcmBus.latestFresh(200L)
        if (src == null) {
            return mic10ms.copyOf()
        }

        // Вырезаем "последние 10 мс" на исходной ЧД, приводим к mono
        val player10msMono = tail10msMono(src, PlayerPcmBus.channels, PlayerPcmBus.sampleRate)

        // Ресемплим 10мс от ЧД плеера → 48 кГц и приводим длину к mic10ms.size (обычно 480)
        val player48 = resampleTo48kAndFit(player10msMono, PlayerPcmBus.sampleRate, mic10ms.size)

        // Микширование
        val out = ShortArray(mic10ms.size)
        val inv = 1f - a
        var i = 0
        while (i < out.size) {
            val micS = mic10ms[i].toInt()
            val plS = player48[i].toInt()
            var mix = (inv * micS + a * plS).toInt()
            if (mix > Short.MAX_VALUE.toInt()) mix = Short.MAX_VALUE.toInt()
            else if (mix < Short.MIN_VALUE.toInt()) mix = Short.MIN_VALUE.toInt()
            out[i] = mix.toShort()
            i++
        }
        return out
    }

    /** Вырезать последние ~10мс (frames = sr*0.01) и downmix→mono (float[-1..1]). */
    private fun tail10msMono(srcInterleaved: FloatArray, ch: Int, sr: Int): FloatArray {
        val channels = ch.coerceAtLeast(1)
        val frames = srcInterleaved.size / channels
        if (frames <= 0 || sr <= 0) return FloatArray(0)

        val needFrames = (sr * 10) / 1000  // ≈ 10мс
        val fromFrame = (frames - needFrames).coerceAtLeast(0)
        val take = (frames - fromFrame).coerceAtLeast(0)

        if (take == 0) return FloatArray(0)

        val mono = FloatArray(take)
        var si = fromFrame * channels
        var i = 0
        while (i < take) {
            var acc = 0f
            var c = 0
            while (c < channels) { acc += srcInterleaved[si + c]; c++ }
            mono[i] = acc / channels
            si += channels
            i++
        }
        return mono
    }

    /** Ресемпл 10мс-массива до 48кГц и подгон по длине (обычно 480) с линейной интерполяцией. */
    private fun resampleTo48kAndFit(srcMono: FloatArray, srcRate: Int, dstLen: Int): ShortArray {
        if (dstLen <= 0) return ShortArray(0)
        if (srcMono.isEmpty() || srcRate <= 0) return ShortArray(dstLen) // тишина

        // Шаг 1: если sr != 48k — интерполируем к 10мс @ 48k.
        val needFrames48 = 480  // 10мс при 48кГц
        val srcFrames10ms = srcMono.size
        val targetFrames = if (srcRate == 48000) srcFrames10ms else ((srcFrames10ms * 48000.0) / srcRate.toDouble()).toInt().coerceAtLeast(1)

        val tmp48 = FloatArray(targetFrames)
        val ratio = (srcFrames10ms - 1).toDouble() / (targetFrames - 1).coerceAtLeast(1)
        var j = 0
        while (j < targetFrames) {
            val srcPos = j * ratio
            val i0 = srcPos.toInt().coerceIn(0, srcFrames10ms - 1)
            val i1 = kotlin.math.min(i0 + 1, srcFrames10ms - 1)
            val frac = (srcPos - i0)
            val s = srcMono[i0] * (1.0 - frac) + srcMono[i1] * frac
            tmp48[j] = s.toFloat()
            j++
        }

        // Шаг 2: подгоняем точную длину под dstLen (обычно 480)
        val out = ShortArray(dstLen)
        val ratio2 = (tmp48.size - 1).toDouble() / (dstLen - 1).coerceAtLeast(1)
        var k = 0
        while (k < dstLen) {
            val p = k * ratio2
            val i0 = p.toInt().coerceIn(0, tmp48.size - 1)
            val i1 = kotlin.math.min(i0 + 1, tmp48.size - 1)
            val frac = (p - i0)
            val s = tmp48[i0] * (1.0 - frac) + tmp48[i1] * frac
            val v = (s * 32767.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[k] = v.toShort()
            k++
        }
        return out
    }
}

