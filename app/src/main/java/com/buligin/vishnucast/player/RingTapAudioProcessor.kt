package com.buligin.vishnucast.player

import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.audio.AudioProcessor
import com.google.android.exoplayer2.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Снимает PCM из Exo, даунмиксит в МОНО и ресемплит в 48 кГц,
 * отдаёт в PlayerPcmRing строго 10мс пачками (480 сэмплов).
 *
 * Локальный вывод управляется флагом passThrough:
 *   true  -> отдаём исходный PCM дальше (динамик звучит),
 *   false -> затираем выход нулями (динамик молчит), тайминг сохраняем.
 */
class RingTapAudioProcessor : BaseAudioProcessor() {

    companion object {
        @Volatile private var passThrough: Boolean = false // по умолчанию false — динамик молчит

        /** Вкл/выкл локальный вывод (true = слышно в динамик, false = тихо). */
        fun setPassThrough(enabled: Boolean) {
            passThrough = enabled
            Log.d("VishnuMix", "RingTapAudioProcessor.passThrough=$enabled")
        }
        fun isPassThrough(): Boolean = passThrough
    }

    // Текущий входной формат
    private var inEncoding: Int = C.ENCODING_INVALID
    private var inSampleRate: Int = 0
    private var inChannelCount: Int = 0

    // Буферы для даунмикса/ресэмпла
    private var tmpMonoShorts: ShortArray = ShortArray(0)
    private var resOutShorts: ShortArray = ShortArray(0)

    // Аккумулятор выходных 48кГц моно сэмплов, чтобы выдавать 480 сэмплов (10мс) стабильно
    private var acc: ShortArray = ShortArray(4800)
    private var accLen: Int = 0

    // Состояние ресэмплера (линейная интерполяция до 48 кГц)
    private var resPos: Double = 0.0
    private var prevTail: Short = 0
    private val TARGET_HZ = 48_000
    private val FRAME_10MS = 480

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inEncoding = inputAudioFormat.encoding
        inSampleRate = inputAudioFormat.sampleRate
        inChannelCount = inputAudioFormat.channelCount
        return inputAudioFormat // формат цепочки не меняем
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()


   //     if (remaining > 0) {
   //         Log.d("VishnuMix", "RTA queue enc=$inEncoding sr=$inSampleRate ch=$inChannelCount pass=$passThrough len=$remaining")
   //     }


        val out = replaceOutputBuffer(remaining)

        if (remaining > 0) {
            // 1) Копируем исходные байты в выход — только это гарантирует корректный локальный проход
            out.put(inputBuffer.duplicate())

            // 2) Снимаем PCM -> mono -> 48к и накапливаем
            when (inEncoding) {
                C.ENCODING_PCM_16BIT -> {
                    processPcm16ToMono48k(inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN))
                }
                C.ENCODING_PCM_FLOAT -> {
                    processPcmFloatToMono48k(inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN))
                }
                else -> {
                    // неизвестный формат: не снимаем, только сквозной проход
                }
            }

            // продвинуть вход
            inputBuffer.position(inputBuffer.position() + remaining)
        }

        // 3) flip обязателен всегда
        out.flip()

        // 4) Глушим динамик при необходимости
        if (!passThrough) {
            for (i in 0 until out.limit()) out.put(i, 0.toByte())
        }

        // 5) Выдать в кольцо все полные 10мс пачки
        flushAccToRingInFrames()
    }

    override fun isActive(): Boolean = true

    override fun onFlush() {
        // Сохраняем resPos/prevTail, чтобы не было щелчков на границах,
        // но опустошим аккумулятор, чтобы не распухал.
        if (accLen >= FRAME_10MS) flushAccToRingInFrames()
    }

    override fun onReset() {
        inEncoding = C.ENCODING_INVALID
        inSampleRate = 0
        inChannelCount = 0
        tmpMonoShorts = ShortArray(0)
        resOutShorts = ShortArray(0)
        // аккум/состояние оставим «по нулям»
        accLen = 0
        resPos = 0.0
        prevTail = 0
    }

    // === Внутренняя обработка ===

    private fun processPcm16ToMono48k(buf: ByteBuffer) {
        // даунмикс в моно
        val inSamples = buf.remaining() / 2
        val frames = if (inChannelCount <= 0) inSamples else inSamples / inChannelCount
        ensureTmpMono(frames)

        when (inChannelCount) {
            1 -> {
                var i = 0
                while (i < frames && buf.remaining() >= 2) {
                    tmpMonoShorts[i] = buf.short
                    i++
                }
                resampleAppend(tmpMonoShorts, i, inSampleRate)
            }
            2 -> {
                var i = 0
                while (i < frames && buf.remaining() >= 4) {
                    val l = buf.short.toInt()
                    val r = buf.short.toInt()
                    tmpMonoShorts[i] = (((l + r) / 2).coerceIn(-32768, 32767)).toShort()
                    i++
                }
                resampleAppend(tmpMonoShorts, i, inSampleRate)
            }
            else -> {
                var i = 0
                while (i < frames && buf.remaining() >= inChannelCount * 2) {
                    val first = buf.short // ch0
                    for (c in 1 until inChannelCount) { if (buf.remaining() >= 2) buf.short else break }
                    tmpMonoShorts[i++] = first
                }
                resampleAppend(tmpMonoShorts, i, inSampleRate)
            }
        }
    }

    private fun processPcmFloatToMono48k(buf: ByteBuffer) {
        val floats = buf.remaining() / 4
        val frames = if (inChannelCount <= 0) floats else floats / inChannelCount
        ensureTmpMono(frames)

        when (inChannelCount) {
            1 -> {
                var i = 0
                while (i < frames && buf.remaining() >= 4) {
                    val f = buf.float.coerceIn(-1f, 1f)
                    tmpMonoShorts[i++] = (f * 32767.0f).roundToInt().coerceIn(-32768, 32767).toShort()
                }
                resampleAppend(tmpMonoShorts, i, inSampleRate)
            }
            2 -> {
                var i = 0
                while (i < frames && buf.remaining() >= 8) {
                    val l = buf.float.coerceIn(-1f, 1f)
                    val r = buf.float.coerceIn(-1f, 1f)
                    val m = ((l + r) * 0.5f).coerceIn(-1f, 1f)
                    tmpMonoShorts[i++] = (m * 32767.0f).roundToInt().coerceIn(-32768, 32767).toShort()
                }
                resampleAppend(tmpMonoShorts, i, inSampleRate)
            }
            else -> {
                var i = 0
                while (i < frames && buf.remaining() >= 4 * inChannelCount) {
                    val first = buf.float.coerceIn(-1f, 1f)
                    for (c in 1 until inChannelCount) { if (buf.remaining() >= 4) buf.float else break }
                    tmpMonoShorts[i++] = (first * 32767.0f).roundToInt().coerceIn(-32768, 32767).toShort()
                }
                resampleAppend(tmpMonoShorts, i, inSampleRate)
            }
        }
    }

    private fun resampleAppend(mono: ShortArray, monoCount: Int, srcRate: Int) {
        if (monoCount <= 0 || srcRate <= 0) return

        if (srcRate == TARGET_HZ) {
            // без ресэмплинга — складываем в аккумулятор как есть
            appendToAcc(mono, monoCount)
            return
        }

        val ratio = srcRate.toDouble() / TARGET_HZ.toDouble() // вход/выход
        val estOut = floor((monoCount + 1) / ratio + 2.0).toInt()
        ensureResOut(estOut)

        var outIdx = 0
        var pos = resPos // 0..1.. продолжение от прошлого буфера

        while (true) {
            val i = floor(pos).toInt()
            val ip1 = i + 1
            if (ip1 > monoCount) break

            val s0: Int = if (i == 0) prevTail.toInt() else mono[i - 1].toInt()
            val s1: Int = mono[i.coerceAtMost(monoCount - 1)].toInt()
            val frac = pos - i
            val sample = (s0 * (1.0 - frac) + s1 * frac).toInt().coerceIn(-32768, 32767)
            resOutShorts[outIdx++] = sample.toShort()

            pos += ratio
        }

        // обновим состояние
        resPos = pos - monoCount.toDouble()
        prevTail = mono[monoCount - 1]

        if (outIdx > 0) {
            appendToAcc(resOutShorts, outIdx)
        }
    }

    // ==== аккумулятор -> выдача ровно по 480 сэмплов ====

    private fun appendToAcc(src: ShortArray, count: Int) {
        if (count <= 0) return
        ensureAccCapacity(accLen + count)
        System.arraycopy(src, 0, acc, accLen, count)
        accLen += count
    }

    private fun flushAccToRingInFrames() {
        var offset = 0
        while (accLen - offset >= FRAME_10MS) {
            PlayerPcmRing.push(acc, FRAME_10MS, offset) // используем перегрузку с offset, если она есть
            offset += FRAME_10MS
        }
        if (offset > 0) {
            // сдвигаем остаток в начало
            val rest = accLen - offset
            if (rest > 0) System.arraycopy(acc, offset, acc, 0, rest)
            accLen = rest
        }
    }

    private fun ensureTmpMono(minFrames: Int) {
        if (tmpMonoShorts.size < minFrames) {
            tmpMonoShorts = ShortArray(minFrames)
        }
    }

    private fun ensureResOut(minFrames: Int) {
        if (resOutShorts.size < minFrames) {
            resOutShorts = ShortArray(minFrames)
        }
    }

    private fun ensureAccCapacity(minFrames: Int) {
        if (acc.size < minFrames) {
            var newCap = acc.size.coerceAtLeast(FRAME_10MS * 4)
            while (newCap < minFrames) newCap *= 2
            val n = ShortArray(newCap)
            if (accLen > 0) System.arraycopy(acc, 0, n, 0, accLen)
            acc = n
        }
    }
}
