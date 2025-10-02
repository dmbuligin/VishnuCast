package com.buligin.vishnucast.audio

import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.ArrayDeque

/**
 * Pass-through tee AudioProcessor:
 * - Всегда пропускает PCM дальше без изменений (pass-through).
 * - Параллельно копирует кадр в PlayerPcmBus как float[].
 * - Корректно буферизует множественные queueInput(...) до getOutput().
 */
class ExoTeeAudioProcessor : AudioProcessor {

    private var inputFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var active = false
    private var ended = false

    // Очередь выходных буферов (каждый queueInput добавляет один элемент)
    private val outQueue: ArrayDeque<ByteBuffer> = ArrayDeque()

    // Для диагностики формата
    private var lastLoggedFmt: AudioProcessor.AudioFormat? = null

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputFormat = inputAudioFormat
        active = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        // лог один раз на изменение
        if (lastLoggedFmt?.let { it != inputAudioFormat } != false) {
            Log.i("VC/TeeProc", "fmt: enc=${inputAudioFormat.encoding} ch=${inputAudioFormat.channelCount} sr=${inputAudioFormat.sampleRate}")
            lastLoggedFmt = inputAudioFormat
        }
        // pass-through формат
        return inputAudioFormat
    }

    override fun isActive(): Boolean = active

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        // 1) tee → PlayerPcmBus (не трогаем позицию входа)
        try {
            val ch = inputFormat.channelCount
            val sr = inputFormat.sampleRate
            when (inputFormat.encoding) {
                C.ENCODING_PCM_16BIT -> {
                    val len = inputBuffer.remaining()
                    val bb = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    val shorts = ShortArray(len / 2)
                    bb.asShortBuffer().get(shorts)
                    val floats = FloatArray(shorts.size)
                    var i = 0
                    while (i < shorts.size) {
                        floats[i] = shorts[i] / 32768f
                        i++
                    }
                    PlayerPcmBus.push(floats, ch, sr)
                }
                C.ENCODING_PCM_FLOAT -> {
                    val bb = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    val fb: FloatBuffer = bb.asFloatBuffer()
                    val floats = FloatArray(fb.remaining())
                    fb.get(floats)
                    PlayerPcmBus.push(floats, ch, sr)
                }
                else -> { /* ignore */ }
            }
        } catch (t: Throwable) {
            Log.w("VC/TeeProc", "tap error: ${t.message}")
        }

        // 2) pass-through: копируем вход в независимый direct-буфер и ставим в очередь
        val len = inputBuffer.remaining()
        val out = ByteBuffer.allocateDirect(len).order(ByteOrder.nativeOrder())
        val dup = inputBuffer.duplicate()
        out.put(dup)
        out.flip()
        outQueue.add(out)

        // 3) обязательно помечаем вход как потреблённый
        inputBuffer.position(inputBuffer.limit())
    }

    override fun getOutput(): ByteBuffer {
        // Отдаём по одному буферу за вызов; если пусто — EMPTY
        return if (outQueue.isEmpty()) {
            AudioProcessor.EMPTY_BUFFER
        } else {
            outQueue.removeFirst()
        }
    }

    override fun isEnded(): Boolean = ended && outQueue.isEmpty()

    override fun queueEndOfStream() {
        ended = true
    }

    override fun flush() {
        outQueue.clear()
        ended = false
    }

    override fun reset() {
        flush()
        inputFormat = AudioProcessor.AudioFormat.NOT_SET
        active = false
        lastLoggedFmt = null
    }
}
