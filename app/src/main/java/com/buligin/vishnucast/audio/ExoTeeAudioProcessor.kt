package com.buligin.vishnucast.audio

import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Pass-through tee AudioProcessor:
 * - Всегда пропускает PCM дальше без изменений.
 * - Параллельно копирует кадр в PlayerPcmBus как float[].
 */
class ExoTeeAudioProcessor : AudioProcessor {

    private var inputFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var active: Boolean = false
    private var ended = false

    private var scratch: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var output: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputFormat = inputAudioFormat
        active = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        // pass-through
        return inputAudioFormat
    }

    override fun isActive(): Boolean = active

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        // 1) pass-through copy → output
        val len = inputBuffer.remaining()
        var out = scratch
        if (out.capacity() < len) {
            out = ByteBuffer.allocateDirect(len).order(ByteOrder.nativeOrder())
            scratch = out
        } else {
            out.clear()
        }
        val dupForCopy = inputBuffer.duplicate()
        out.put(dupForCopy)
        out.flip()
        output = out

        // 2) tee → PlayerPcmBus
        try {
            val ch = inputFormat.channelCount
            val sr = inputFormat.sampleRate
            when (inputFormat.encoding) {
                C.ENCODING_PCM_16BIT -> {
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
    }

    override fun getOutput(): ByteBuffer {
        val out = output
        output = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = ended

    override fun queueEndOfStream() {
        ended = true
    }

    override fun flush() {
        output = AudioProcessor.EMPTY_BUFFER
        ended = false
    }

    override fun reset() {
        flush()
        inputFormat = AudioProcessor.AudioFormat.NOT_SET
        active = false
        scratch = AudioProcessor.EMPTY_BUFFER
    }
}
