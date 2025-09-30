package com.buligin.vishnucast.audio

import com.google.android.exoplayer2.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Tee-процессор: копирует PCM в PlayerPcmBus.
 * Требует формат PCM_FLOAT 32bit, interleaved.
 */
class ExoTeeAudioProcessor : AudioProcessor {

    private var sampleRate = 48000
    private var channelCount = 2
    private var inputEnded = false

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // ожидаем float PCM
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun isActive(): Boolean = true
    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        // Снимаем копию в float[]
        val floatCount = inputBuffer.remaining() / 4
        val floats = FloatArray(floatCount)
        val dup = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        dup.get(floats)
        PlayerPcmBus.push(floats, channelCount, sampleRate)
        // Пропускаем дальше как есть
        outputBuffer = inputBuffer
    }

    private var outputBuffer: ByteBuffer? = null
    override fun queueEndOfStream() { inputEnded = true }
    override fun getOutput(): ByteBuffer = (outputBuffer ?: AudioProcessor.EMPTY_BUFFER).also { outputBuffer = null }
    override fun isEnded(): Boolean = inputEnded
    override fun flush() { inputEnded = false; outputBuffer = null }
    override fun reset() { flush() }
}
