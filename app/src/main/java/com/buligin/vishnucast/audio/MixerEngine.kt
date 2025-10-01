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

    /**
     * Смешать микрофон (моно 48kHz) и плеер (float interleaved, ch=1|2, sr=any) по коэффициенту alpha (0..1).
     * Если плеер недоступен/пуст — вернём копию микрофона (т.е. деградация без артефактов).
     */
    fun mixMicWithPlayer48kMono(
        mic10ms: ShortArray,
        alpha01: Float
    ): ShortArray {
        val a = alpha01.coerceIn(0f, 1f)
        if (mic10ms.isEmpty()) return mic10ms

        // Если нет данных плеера — просто копия микрофона
        val bus = PlayerPcmBus.latest()
        if (bus == null) {
            return mic10ms.copyOf()
        }

        // Приводим плеер к mono 48 kHz с той же длиной, что и mic10ms
        val playerMono48 = toMono48kShort(
            src = bus,
            srcChannels = PlayerPcmBus.channels,
            srcSampleRate = PlayerPcmBus.sampleRate,
            dstLen = mic10ms.size
        )

        // Микширование: out = (1-a)*mic + a*player
        val out = ShortArray(mic10ms.size)
        val inv = 1f - a
        var i = 0
        while (i < out.size) {
            val micS = mic10ms[i].toInt()
            val plS = playerMono48[i].toInt()
            var mix = (inv * micS + a * plS).roundToInt()

            // Софт-клип внутри 16-бит
            if (mix > Short.MAX_VALUE.toInt()) mix = Short.MAX_VALUE.toInt()
            else if (mix < Short.MIN_VALUE.toInt()) mix = Short.MIN_VALUE.toInt()

            out[i] = mix.toShort()
            i++
        }
        return out
    }

    /**
     * Преобразовать interleaved float PCM [-1..1] (ch=1|2, sr=any) в short[] mono 48 kHz фиксированной длины dstLen.
     * - Downmix в моно: усреднение каналов (L+R)/2;
     * - Resample: простая линейная интерполяция (достаточно для голоса/музыки на 10мс окнах).
     */
    fun toMono48kShort(
        src: FloatArray,
        srcChannels: Int,
        srcSampleRate: Int,
        dstLen: Int
    ): ShortArray {
        // Шаг 1: downmix в моно float[]
        val mono = when (srcChannels.coerceAtLeast(1)) {
            1 -> src.copyOf() // уже моно
            else -> {
                val frames = src.size / srcChannels
                val out = FloatArray(frames)
                var si = 0
                var i = 0
                while (i < frames) {
                    // усредняем все каналы (обычно 2)
                    var acc = 0f
                    var c = 0
                    while (c < srcChannels) {
                        acc += src[si + c]
                        c++
                    }
                    out[i] = acc / srcChannels
                    si += srcChannels
                    i++
                }
                out
            }
        }

        // Шаг 2: resample → 48k float[] (линейная интерполяция)
        val need48k = 48000
        val resampled = if (srcSampleRate == need48k) mono
        else {
            if (mono.isEmpty() || srcSampleRate <= 0) FloatArray(0) else {
                val ratio = need48k.toDouble() / srcSampleRate.toDouble()
                val outLen = (mono.size * ratio).roundToInt().coerceAtLeast(1)
                val out = FloatArray(outLen)

                var j = 0
                while (j < outLen) {
                    val srcPos = j / ratio
                    val i0 = srcPos.toInt().coerceIn(0, mono.size - 1)
                    val i1 = min(i0 + 1, mono.size - 1)
                    val frac = (srcPos - i0)
                    val s = mono[i0] * (1.0 - frac) + mono[i1] * frac
                    out[j] = s.toFloat()
                    j++
                }
                out
            }
        }

        // Шаг 3: подгоняем длину окна к dstLen (обычно 480 на 10мс)
        val dst = ShortArray(dstLen)
        if (resampled.isEmpty()) {
            // тишина
            return dst
        }

        // Если длина не совпадает — масштабируем (stretch/compress) простой интерполяцией
        val ratio = resampled.size.toDouble() / dstLen.toDouble()
        var k = 0
        while (k < dstLen) {
            val srcPos = k * ratio
            val i0 = srcPos.toInt().coerceIn(0, resampled.size - 1)
            val i1 = min(i0 + 1, resampled.size - 1)
            val frac = (srcPos - i0)
            val s = resampled[i0] * (1.0 - frac) + resampled[i1] * frac
            // float [-1..1] → short
            val v = (s * 32767.0).roundToInt()
            dst[k] = when {
                v > Short.MAX_VALUE.toInt() -> Short.MAX_VALUE
                v < Short.MIN_VALUE.toInt() -> Short.MIN_VALUE
                else -> v.toShort()
            }
            k++
        }
        return dst
    }
}
