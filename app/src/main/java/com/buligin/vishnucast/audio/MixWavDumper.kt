package com.buligin.vishnucast.audio

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Пишет 16-бит mono 48 kHz WAV 10мс-окнами.
 * API >= 29: Downloads/VishnuCast через MediaStore (без разрешений).
 * API < 29:  app-specific /Android/data/.../files/Download/VishnuCast.
 *
 * Внимание: Context НЕ хранится — передаётся только в start()/stop().
 */
object MixWavDumper {
    private const val TAG = "VC/MixWav"
    private const val SAMPLE_RATE = 48000
    private const val CHANNELS = 1
    private const val BITS = 16
    private const val WAV_HEADER_BYTES = 44

    @Volatile private var out: BufferedOutputStream? = null
    @Volatile private var fileUri: Uri? = null         // для API >= 29
    @Volatile private var filePath: File? = null       // для API < 29
    @Volatile private var bytesWritten = 0
    private val started = AtomicBoolean(false)
    private val tickCounter = AtomicInteger(0)

    @Volatile var enabled: Boolean = false
        private set

    /** Начать запись. Вернёт Uri (API29+)/File (<29) или null при ошибке. */
    fun start(ctx: Context): Any? {
        if (started.get()) return fileUri ?: filePath
        try {
            val name = "mix48k_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(System.currentTimeMillis()) + ".wav"

            if (Build.VERSION.SDK_INT >= 29) {
                // --- API 29+: MediaStore → Downloads/VishnuCast ---
                val relPath = Environment.DIRECTORY_DOWNLOADS + "/VishnuCast"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("resolver.insert returned null")
                val raw = ctx.contentResolver.openOutputStream(uri, "rw")
                    ?: throw IllegalStateException("openOutputStream null")

                val bos = BufferedOutputStream(raw, 64 * 1024)
                bos.write(makeWavHeader(0))
                bos.flush()

                out = bos
                fileUri = uri
                filePath = null
                bytesWritten = 0
                tickCounter.set(0)
                started.set(true)
                enabled = true

                Log.i(TAG, "start(API29+): $name → Downloads/VishnuCast uri=$uri")
                return uri
            } else {
                // --- API < 29: app-specific Downloads ---
                val base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
                val dir = File(base, "VishnuCast").apply { mkdirs() }
                val f = File(dir, name)

                val bos = BufferedOutputStream(FileOutputStream(f, false), 64 * 1024)
                bos.write(makeWavHeader(0))
                bos.flush()

                out = bos
                fileUri = null
                filePath = f
                bytesWritten = 0
                tickCounter.set(0)
                started.set(true)
                enabled = true

                Log.i(TAG, "start(<29): ${f.absolutePath}")
                return f
            }
        } catch (t: Throwable) {
            Log.w(TAG, "start failed: ${t.message}")
            stop(null) // безопасно
            return null
        }
    }

    /** Остановить запись и корректно переписать WAV-заголовок. */
    fun stop(ctx: Context?) {
        if (!started.getAndSet(false)) return
        enabled = false

        try { out?.flush() } catch (_: Throwable) {}
        try { out?.close() } catch (_: Throwable) {}
        out = null

        val dataBytes = bytesWritten

        if (Build.VERSION.SDK_INT >= 29) {
            val uri = fileUri
            if (uri != null && ctx != null) {
                // Переписываем заголовок и снимаем IS_PENDING
                try {
                    val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw")
                    pfd?.use { fd ->
                        val fos = java.io.FileOutputStream(fd.fileDescriptor)
                        fos.channel.position(0) // важный момент: пишем header в начало
                        fos.write(makeWavHeader(dataBytes))
                        fos.flush()
                        fos.channel.force(true)
                        fos.close()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "stop(header,29+): ${t.message}")
                }
                try {
                    val cv = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    ctx.contentResolver.update(uri, cv, null, null)
                } catch (_: Throwable) {}
                Log.i(TAG, "stop(API29+): bytes=$dataBytes ticks=${tickCounter.get()} uri=$uri")
            } else {
                Log.i(TAG, "stop(API29+): bytes=$dataBytes ticks=${tickCounter.get()} (no ctx/uri)")
            }
            fileUri = null
        } else {
            val f = filePath
            if (f != null) {
                // Правим первые 44 байта напрямую
                try {
                    RandomAccessFile(f, "rw").use { raf ->
                        raf.seek(0)
                        raf.write(makeWavHeader(dataBytes))
                    }
                    Log.i(TAG, "stop(<29): bytes=$dataBytes ticks=${tickCounter.get()} path=${f.absolutePath}")
                } catch (t: Throwable) {
                    Log.w(TAG, "stop(header,<29): ${t.message}")
                }
            }
            filePath = null
        }

        bytesWritten = 0
        tickCounter.set(0)
    }

    /** Кладём кусок PCM16 (LE). Вызывать из 10мс-тикера. */
    fun push(shorts: ShortArray) {
        val bos = out ?: return
        if (!enabled) return
        try {
            val bb = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bb.asShortBuffer().put(shorts)
            bos.write(bb.array())
            bytesWritten += bb.capacity()
            val ticks = tickCounter.incrementAndGet()
            if (ticks % 100 == 0) {
                Log.d(TAG, "push: bytes=$bytesWritten (~${bytesWritten / 2} samples)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "push failed: ${t.message}")
        }
    }

    private fun makeWavHeader(dataBytes: Int): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * (BITS / 8)
        val blockAlign = CHANNELS * (BITS / 8)
        val totalDataLen = 36 + dataBytes

        val h = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray(Charsets.US_ASCII))
        h.putInt(totalDataLen)
        h.put("WAVE".toByteArray(Charsets.US_ASCII))
        h.put("fmt ".toByteArray(Charsets.US_ASCII))
        h.putInt(16)                // PCM subchunk size
        h.putShort(1)               // PCM format
        h.putShort(CHANNELS.toShort())
        h.putInt(SAMPLE_RATE)
        h.putInt(byteRate)
        h.putShort(blockAlign.toShort())
        h.putShort(BITS.toShort())
        h.put("data".toByteArray(Charsets.US_ASCII))
        h.putInt(dataBytes)
        return h.array()
    }
}
