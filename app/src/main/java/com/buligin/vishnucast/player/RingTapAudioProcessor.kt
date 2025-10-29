package com.buligin.vishnucast.player

import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.audio.AudioProcessor
import com.google.android.exoplayer2.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * AudioProcessor, который:
 * 1) снимает декодированный PCM и шлёт его в PlayerPcmRing;
 * 2) по флагу passThrough либо пропускает PCM дальше (слышно в динамик),
 *    либо глушит локальный вывод (динамик молчит), сохраняя тайминг.
 *
 * Важно: класс намеренно всегда "активен", чтобы стабильно снимать PCM.
 */
class RingTapAudioProcessor : BaseAudioProcessor() {

    companion object {
        @Volatile
        private var passThrough: Boolean = false // false = глушим локальный динамик по умолчанию

        /** Вкл/выкл локальный вывод в динамик (true = пропускать, false = глотать). */
        fun setPassThrough(enabled: Boolean) {
            passThrough = enabled
            Log.d("VishnuMix", "RingTapAudioProcessor.passThrough=$enabled")
        }

        fun isPassThrough(): Boolean = passThrough
    }

    private var inputEncoding: Int = C.ENCODING_INVALID
    private var tmpShorts: ShortArray = ShortArray(0)

    // === AudioProcessor overrides ===

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputEncoding = inputAudioFormat.encoding
        // Мы не меняем формат: сквозной формат на выход = входному.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val remaining = inputBuffer.remaining()
        // Готовим выходной буфер той же длины — чтобы не ломать тайминг в AudioSink.
        val out = replaceOutputBuffer(remaining)

        when (inputEncoding) {
            C.ENCODING_PCM_16BIT -> {
                // Скопировать во "вых" (пройдёт дальше, если passThrough=true).
                out.put(inputBuffer.duplicate())

                // Снять PCM в ShortArray и отправить в кольцо
                val samples = remaining / 2
                ensureTmp(samples)
                val dup = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                var i = 0
                while (i < samples) {
                    tmpShorts[i] = dup.short
                    i++
                }
                PlayerPcmRing.push(tmpShorts, samples)

                // Пометить вход как прочитанный
                inputBuffer.position(inputBuffer.position() + remaining)
            }

            C.ENCODING_PCM_FLOAT -> {
                // Конвертируем float [-1;1] -> short, пишем в "вых"
                val floats = remaining / 4
                ensureTmp(floats)
                val dup = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                var i = 0
                while (i < floats) {
                    val f = dup.float.coerceIn(-1f, 1f)
                    val s = (f * 32767.0f).roundToInt().coerceIn(-32768, 32767)
                    tmpShorts[i] = s.toShort()
                    i++
                }
                out.clear()
                i = 0
                while (i < floats) {
                    val s = tmpShorts[i].toInt()
                    out.put((s and 0xFF).toByte())
                    out.put(((s ushr 8) and 0xFF).toByte())
                    i++
                }
                inputBuffer.position(inputBuffer.position() + remaining)

                // И также — в кольцо
                PlayerPcmRing.push(tmpShorts, floats)
            }

            else -> {
                // Незнакомый формат — сквозной проход
                out.put(inputBuffer)
            }
        }

        // Если локальный динамик нужно "приглушить" — перезатрём выход нулями.
        if (!passThrough) {
            out.flip()
            for (i in 0 until out.limit()) {
                out.put(i, 0.toByte())
            }
        }
    }

    override fun isActive(): Boolean {
        // Активен всегда: нам нужно постоянно снимать PCM и при необходимости глушить локальный вывод.
        return true
    }

    override fun onFlush() {
        // no-op
    }

    override fun onReset() {
        inputEncoding = C.ENCODING_INVALID
        tmpShorts = ShortArray(0)
    }

    // === utils ===
    private fun ensureTmp(minSamples: Int) {
        if (tmpShorts.size < minSamples) {
            tmpShorts = ShortArray(minSamples)
        }
    }
}
