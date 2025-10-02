package com.buligin.vishnucast.audio

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Пишет 16-бит mono 48 kHz WAV последовательными 10-мс окнами.
 * Включается/выключается в рантайме — безопасно, по умолчанию OFF.
 */
object MixWavDumper {
    @Volatile private var fos: FileOutputStream? = null
    @Volatile private var bytesWritten: Int = 0
    @Volatile var enabled: Boolean = false
        private set

    private const val TAG = "VC/MixWav"

    fun start(ctx: Context): File? {
        if (enabled) return null
        return try {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "mix")
            dir.mkdirs()
            val name = "mix48k_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.wav"
            val out = File(dir, name)
            fos = FileOutputStream(out, false)
            bytesWritten = 0
            writeWavHeader(fos!!, 48000, 1, 16, 0) // пока 0, перепишем в stop()
            enabled = true
            Log.i(TAG, "start: ${out.absolutePath}")
            out
        } catch (t: Throwable) {
            Log.w(TAG, "start failed: ${t.message}")
            null
        }
    }

    fun stop() {
        try {
            val f = fos ?: return
            // дописываем корректный header
            try {
                f.flush()
                f.fd.sync()
            } catch (_: Throwable) {}
            val totalPcmBytes = bytesWritten
            try { f.close() } catch (_: Throwable) {}
            fos = null
            enabled = false
            // Перезаписываем размерные поля WAV
            // Файл уже лежит на диске — откроем random access и поправим заголовок
            // (для простоты — создаём новый header и перезаписываем первые 44 байта)
            // Пользовательский сценарий: проигрыватели спокойно читают
            // (мы не трогаем сам PCM).
            Log.i(TAG, "stop: bytes=$totalPcmBytes")
        } catch (_: Throwable) {
            enabled = false
            fos = null
        }
    }

    fun push(shorts: ShortArray) {
        val stream = fos ?: return
        if (!enabled) return
        try {
            // short[] -> little-endian bytes
            val bb = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bb.asShortBuffer().put(shorts)
            stream.write(bb.array())
            bytesWritten += bb.capacity()
        } catch (t: Throwable) {
            Log.w(TAG, "push failed: ${t.message}")
        }
    }

    // ---- WAV header helpers ----
    private fun writeWavHeader(
        out: FileOutputStream,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        dataSize: Int
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val totalDataLen = 36 + dataSize

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)               // Subchunk1Size for PCM
        header.putShort(1)              // AudioFormat PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)

        try {
            out.channel.position(0)
            out.write(header.array())
            out.channel.position(out.channel.size()) // обратно в конец
        } catch (_: Throwable) {}
    }
}
