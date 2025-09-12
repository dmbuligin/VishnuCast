package com.buligin.vishnucast

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Пробник уровня микрофона для индикации 0..100.
 * Источник: MIC, при неудаче — VOICE_COMMUNICATION (fallback).
 * НЕ хранит Context, чтобы избежать утечек.
 */
object MicLevelProbe {
    private const val SR = 48_000
    private const val CH = AudioFormat.CHANNEL_IN_MONO
    private const val ENC = AudioFormat.ENCODING_PCM_16BIT

    @Volatile private var record: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    private fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Запускает измерение уровня. Требует RECORD_AUDIO (проверяется перед запуском),
     * дополнительно защищено try/catch на SecurityException.
     */
    @SuppressLint("MissingPermission") // мы явно проверяем разрешение и ловим SecurityException
    fun start(ctx: Context) {
        if (running.get()) return
        val app = ctx.applicationContext
        if (!hasPermission(app)) return

        val minBuf = AudioRecord.getMinBufferSize(SR, CH, ENC)
        if (minBuf <= 0) return

        // 1) MIC → 2) VOICE_COMMUNICATION
        val rec = newRecorder(MediaRecorder.AudioSource.MIC, minBuf)
            ?: newRecorder(MediaRecorder.AudioSource.VOICE_COMMUNICATION, minBuf)
            ?: return

        record = rec
        running.set(true)
        try {
            rec.startRecording()
        } catch (_: SecurityException) {
            stop()
            return
        }

        thread = Thread({
            val buf = ShortArray(minBuf / 2)
            while (running.get()) {
                val n = try {
                    // Чтение PCM; если во время работы разрешение отзовут — словим SecurityException
                    rec.read(buf, 0, buf.size)
                } catch (_: SecurityException) {
                    -1
                } catch (_: Throwable) {
                    -1
                }

                if (n > 0) {
                    var max = 0
                    for (i in 0 until n) {
                        val a = abs(buf[i].toInt())
                        if (a > max) max = a
                    }
                    val level = ((max * 100.0) / 32767.0).coerceAtMost(100.0).toInt()
                    SignalLevel.post(level)
                }

                try { Thread.sleep(20) } catch (_: InterruptedException) {}
            }
        }, "MicLevelProbe").also { it.start() }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun newRecorder(source: Int, minBuf: Int): AudioRecord? = try {
        val rec = AudioRecord(source, SR, CH, ENC, minBuf * 2)
        if (rec.state == AudioRecord.STATE_INITIALIZED) rec else { rec.release(); null }
    } catch (_: Throwable) { null }

    fun stop() {
        running.set(false)
        try { thread?.interrupt() } catch (_: Throwable) {}
        thread = null
        record?.let {
            try { it.stop() } catch (_: Throwable) {}
            it.release()
        }
        record = null
    }
}
