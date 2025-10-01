package com.buligin.vishnucast.audio

import com.google.android.exoplayer2.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Log


class ExoTeeAudioProcessor : AudioProcessor {

    private val TAG = "VC/TeeProc"
    private var loggedFormat = false


    private var sampleRate = 48000
    private var channelCount = 2
    private var inputEnded = false
    private var outputBuffer: ByteBuffer? = null

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        return inputAudioFormat

        if (!loggedFormat) {
            Log.d(TAG, "configure: sr=$sampleRate ch=$channelCount")
            loggedFormat = true
        }



    }

    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {


        if (inputBuffer.hasRemaining()) {
            Log.v(TAG, "queueInput: ${inputBuffer.remaining()} bytes")
        }



        if (!inputBuffer.hasRemaining()) return

        // Копия в float[] для шины
        val floatCount = inputBuffer.remaining() / 4
        val floats = FloatArray(floatCount)
        inputBuffer.duplicate()
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .get(floats)
        PlayerPcmBus.push(floats, channelCount, sampleRate)

        // Пробрасываем буфер дальше: отдаём как output и продвигаем position
        outputBuffer = inputBuffer
        inputBuffer.position(inputBuffer.limit())
    }

    override fun queueEndOfStream() { inputEnded = true }
    override fun getOutput(): ByteBuffer = (outputBuffer ?: AudioProcessor.EMPTY_BUFFER).also { outputBuffer = null }
    override fun isEnded(): Boolean = inputEnded
    override fun flush() { inputEnded = false; outputBuffer = null }
    override fun reset() { flush() }
}
