package com.buligin.vishnucast.player.capture

import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.buligin.vishnucast.player.jni.PlayerJni
import kotlin.concurrent.thread
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat






@RequiresApi(Build.VERSION_CODES.Q)
object PlayerSystemCapture {

    private const val TAG = "VishnuCapture"
    private const val SR = 48000
    private const val CH = AudioFormat.CHANNEL_IN_MONO
    private const val EN = AudioFormat.ENCODING_PCM_16BIT

    @Volatile private var enginePtr: Long = 0L
    @Volatile private var recordingThread: Thread? = null
    @Volatile private var record: AudioRecord? = null
    @Volatile private var projection: MediaProjection? = null

    fun start(activity: Activity) {
        Log.d(TAG, "start() requested")

        if (enginePtr == 0L) enginePtr = PlayerJni.createEngine()
        if (recordingThread != null) {
            Log.d(TAG, "already running; skip")
            return
        }

        val mpm = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val data = PlayerCapture.resultData ?: return
        val code = PlayerCapture.resultCode

        if (data == null) {
            Log.w(TAG, "projection data is null; did user grant permission?")
            return
        }
        if (code != Activity.RESULT_OK) {
            Log.w(TAG, "projection resultCode != OK ($code)")
            return
        }

        Log.d(TAG, "calling getMediaProjection(...)")
        projection = mpm.getMediaProjection(code, data) ?: run {
            Log.e(TAG, "getMediaProjection returned null")
            return

        }
        Log.d(TAG, "MediaProjection acquired")


        val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
            // Можно сузить до USAGE_MEDIA/USAGE_GAME при желании:
            //.addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(SR, CH, EN).coerceAtLeast(480 * 4)
        // Lint-safe: убедимся, что RECORD_AUDIO выдан (его у тебя запрашивает основная активити)
        val hasRecordAudio = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasRecordAudio) {
            Log.w(TAG, "RECORD_AUDIO not granted; skip start()")
            return
        }
        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SR)
                        .setEncoding(EN)
                        .setChannelMask(CH)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (se: SecurityException) {
            Log.e(TAG, "AudioRecord build SecurityException", se)
            return
        }

        Log.d(TAG, "AudioRecord built (minBuf=$minBuf)")
        record = rec
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized (state=${rec.state})")
                return
            }
            rec.startRecording()
            Log.d(TAG, "AudioRecord.startRecording OK")
        } catch (se: SecurityException) {
            Log.e(TAG, "startRecording SecurityException", se)
            return
        }

        // Подаём в JNI кусками по 10мс (480 семплов)
        recordingThread = thread(name = "vc-player-capture", isDaemon = true) {
            val frame = ShortArray(480)
            val temp = ShortArray(4800) // буфер для донабора
            var tempLen = 0

            Log.d(TAG, "capture thread started (10ms frames)")

            while (!Thread.currentThread().isInterrupted) {
                val r = rec.read(temp, tempLen, temp.size - tempLen)
                if (r <= 0) {
                    // r может быть ERROR_INVALID_OPERATION/ERROR_BAD_VALUE/0 — просто пропускаем тик
                    continue
                }
                tempLen += r

                var off = 0
                while (tempLen - off >= 480) {
                    // копируем 480 семплов в frame
                    System.arraycopy(temp, off, frame, 0, 480)
                    off += 480
                    try {
                        PlayerJni.pushPcm48kMono(enginePtr, frame, 480)
                    } catch (_: Throwable) {}
                }

                if (off > 0) {
                    // сдвигаем остаток в начало
                    val remain = tempLen - off
                    if (remain > 0) {
                        System.arraycopy(temp, off, temp, 0, remain)
                    }
                    tempLen = remain
                }
            }
        }
    }

    fun stop() {
        Log.d(TAG, "stop() requested")

        try { recordingThread?.interrupt() } catch (_: Throwable) {}
        try { recordingThread?.join(200) } catch (_: Throwable) {}
        recordingThread = null

        try { record?.stop() } catch (_: Throwable) {}
        try { record?.release() } catch (_: Throwable) {}
        record = null
        Log.d(TAG, "AudioRecord released")

        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        Log.d(TAG, "MediaProjection stopped")

    }

    fun setMuted(muted: Boolean) {
        val ptr = enginePtr
        if (ptr != 0L) {
            try { PlayerJni.setMuted(ptr, muted) } catch (_: Throwable) {}
        }
    }

    fun release() {
        Log.d(TAG, "release() requested")

        stop()
        val ptr = enginePtr
        if (ptr != 0L) {
            try { PlayerJni.destroyEngine(ptr) } catch (_: Throwable) {}
        }
        enginePtr = 0L
        Log.d(TAG, "JNI engine destroyed")

    }
}
