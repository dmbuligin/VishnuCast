package com.buligin.vishnucast.player.capture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.buligin.vishnucast.player.jni.PlayerJni
import kotlin.concurrent.thread
import android.media.AudioAttributes





/**
 * Захват системного аудио (Android 10+) и подача в JNI 10мс фреймами (48k mono, s16).
 * НИЧЕГО не делает ниже Android Q.
 *
 * Логика старта:
 *  - PlayerUiBinder показывает системный диалог (MediaProjection) и заполняет PlayerCapture.{resultCode,resultData}
 *  - После grant вызывает PlayerSystemCapture.start(activity)
 */
@RequiresApi(Build.VERSION_CODES.Q)
object PlayerSystemCapture {
    private const val TAG = "VishnuCapture"

    private const val SR = 48_000
    private const val CH = AudioFormat.CHANNEL_IN_MONO
    private const val EN = AudioFormat.ENCODING_PCM_16BIT
    private const val FRAME_SAMPLES = 480 // 10 мс @ 48k

    @Volatile private var enginePtr: Long = 0L

    @Volatile private var nativeSourcePtr: Long = 0L

    @Volatile private var projection: MediaProjection? = null
    @Volatile private var record: AudioRecord? = null
    @Volatile private var recordingThread: Thread? = null


    fun setNativeSourceHandle(ptr: Long) {
        nativeSourcePtr = ptr
    }

    /** Быстрая проверка для вызвавшего кода (без крашей на старых API). */
    fun isRunning(): Boolean = recordingThread?.isAlive == true

    /** Старт захвата. Все ранние выходы логируются. */
    fun start(activity: Activity) {
        Log.d(TAG, "start() requested")

        // полезно оставить след кто нас позвал
        Log.d(TAG, "reason=start_by_alpha") // раскомментируй, если хочешь видеть источник


        // Защита от повторного старта
        if (recordingThread != null) {
            Log.d(TAG, "already running; skip")
            return
        }

        // 1) Проверка MediaProjection grant
        val mpm = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val data = PlayerCapture.resultData
        val code = PlayerCapture.resultCode

        if (data == null) {
            Log.w(TAG, "projection data is null; did user grant permission?")
            return
        }
        if (code != Activity.RESULT_OK) {
            Log.w(TAG, "projection resultCode != OK ($code)")
            return
        }

        // 2) Получаем MediaProjection
        Log.d(TAG, "calling getMediaProjection(...)")
        val mp = try {
            mpm.getMediaProjection(code, data)
        } catch (t: Throwable) {
            Log.e(TAG, "getMediaProjection threw", t)
            null
        }
        if (mp == null) {
            Log.e(TAG, "getMediaProjection returned null")
            return
        }
        projection = mp
        Log.d(TAG, "MediaProjection acquired")

        // 3) RECORD_AUDIO permission
        val hasRecordAudio = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasRecordAudio) {
            Log.w(TAG, "RECORD_AUDIO not granted; skip start()")
            safeStopAndReleaseProjection()
            return
        }
        Log.d(TAG, "RECORD_AUDIO granted")

        // 4) Готовим конфигурацию AudioPlaybackCapture
        val config = AudioPlaybackCaptureConfiguration.Builder(mp)
            // Минимум одно правило обязательно, иначе IllegalArgumentException.
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)               // медиа-воспроизведение (ExoPlayer)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)                // на всякий случай (если поменяешь атрибуты)
            .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION) // системные «бипы» внутри твоего процесса
            .addMatchingUid(activity.applicationInfo.uid)                // гарантируем захват СВОЕГО плеера
            .build()


        // 5) Создаём JNI-движок один раз
        if (enginePtr == 0L) {
            enginePtr = try {
                PlayerJni.createEngine().also {
                    Log.d(TAG, "JNI engine created: ptr=$it")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "JNI createEngine failed", t)
                safeStopAndReleaseProjection()
                return
            }
        }

        // 6) Строим AudioRecord
        val minBuf = AudioRecord.getMinBufferSize(SR, CH, EN).coerceAtLeast(FRAME_SAMPLES * 4)
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
            safeStopAndReleaseProjection()
            return
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord build failed", t)
            safeStopAndReleaseProjection()
            return
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (state=${rec.state})")
            try { rec.release() } catch (_: Throwable) {}
            safeStopAndReleaseProjection()
            return
        }
        record = rec
        Log.d(TAG, "AudioRecord built (minBuf=$minBuf)")

        // 7) Старт записи
        try {
            rec.startRecording()
            Log.d(TAG, "AudioRecord.startRecording OK")
        } catch (se: SecurityException) {
            Log.e(TAG, "startRecording SecurityException", se)
            try { rec.release() } catch (_: Throwable) {}
            record = null
            safeStopAndReleaseProjection()
            return
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            try { rec.release() } catch (_: Throwable) {}
            record = null
            safeStopAndReleaseProjection()
            return
        }

        // 8) Поток чтения по 10мс фреймам
        val th = thread(name = "vc-player-capture", isDaemon = true) {
            Log.d(TAG, "capture thread started (10ms frames)")
            val frame = ShortArray(FRAME_SAMPLES)
            val temp = ShortArray(FRAME_SAMPLES * 10)
            var tempLen = 0

            while (!Thread.currentThread().isInterrupted) {
                val r = try {
                    rec.read(temp, tempLen, temp.size - tempLen)
                } catch (t: Throwable) {
                    Log.e(TAG, "AudioRecord.read failed", t)
                    break
                }
                if (r <= 0) continue
                tempLen += r

                var off = 0
                while (tempLen - off >= FRAME_SAMPLES) {
                    System.arraycopy(temp, off, frame, 0, FRAME_SAMPLES)
                    off += FRAME_SAMPLES
                    try {
                        PlayerJni.pushPcm48kMono(enginePtr, frame, FRAME_SAMPLES)

                        val src = nativeSourcePtr
                        if (src != 0L) {
                            try {
                                PlayerJni.sourcePushPcm48kMono(src, frame, FRAME_SAMPLES)
                            } catch (t: Throwable) {
                                Log.e(TAG, "JNI sourcePushPcm failed", t)
                            }
                        }


                    } catch (t: Throwable) {
                        Log.e(TAG, "JNI pushPcm failed", t)
                        // продолжаем пытаться
                    }
                }
                // перенос остатка в начало
                if (off > 0) {
                    val remain = tempLen - off
                    if (remain > 0) System.arraycopy(temp, off, temp, 0, remain)
                    tempLen = remain
                }
            }
            Log.d(TAG, "capture thread exiting")
        }
        recordingThread = th
    }

    /** Остановить чтение/запись и остановить MediaProjection (без уничтожения JNI). */
    fun stop() {
        Log.d(TAG, "stop() requested")
        try {
            recordingThread?.interrupt()
        } catch (_: Throwable) {}
        try {
            recordingThread?.join(200)
        } catch (_: Throwable) {}
        recordingThread = null

        try {
            record?.stop()
        } catch (_: Throwable) {}
        try {
            record?.release()
        } catch (_: Throwable) {}
        record = null

        try {
            projection?.stop()
        } catch (_: Throwable) {}
        projection = null

        nativeSourcePtr = 0L


        Log.d(TAG, "AudioRecord released")
        Log.d(TAG, "MediaProjection stopped")
    }

    /** Полный релиз JNI-движка (после stop). */
    fun release() {
        Log.d(TAG, "release() requested")
        stop()
        val ptr = enginePtr
        if (ptr != 0L) {
            try {
                PlayerJni.destroyEngine(ptr)
                Log.d(TAG, "JNI engine destroyed")
            } catch (_: Throwable) {}
        }
        enginePtr = 0L

        nativeSourcePtr = 0L


    }

    /** Установить mute в JNI (без остановки захвата). */
    fun setMuted(muted: Boolean) {
        val ptr = enginePtr
        if (ptr != 0L) {
            try { PlayerJni.setMuted(ptr, muted) } catch (_: Throwable) {}
        }
    }

    // ——— helpers ———

    private fun safeStopAndReleaseProjection() {
        try { record?.release() } catch (_: Throwable) {}
        record = null
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
    }
}
