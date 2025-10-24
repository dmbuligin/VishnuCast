package com.buligin.vishnucast

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.buligin.vishnucast.player.MixerState

class CastService : Service() {

    private var server: VishnuServer? = null
    private var isFgShown: Boolean = false // флаг: уведомление реально запущено через startForeground

    // NEW: observer кроссфейдера
    private var mixObserver: Observer<Float>? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannelIfNeeded()

        // На старте сервиса — FGS с типом DATA_SYNC (Android 14 требует тип)
        startAsForeground(withMicType = false)

        // Поднять HTTP/WS сразу
        startHttpWsIfNeeded()

        // Mute по умолчанию
        applyMute(true)

        // Сообщаем захватчику аудио (Android 10+) хэндл нативного источника
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val h = WebRtcCoreHolder.get(applicationContext).getNativeSourceHandle()
                com.buligin.vishnucast.player.capture.PlayerSystemCapture.setNativeSourceHandle(h)
                android.util.Log.d("VishnuJNI", "CastService: native source handle propagated: 0x${java.lang.Long.toHexString(h)}")
            }
        } catch (_: Throwable) { }

        // NEW: подписка на кроссфейдер из UI → реакция сервера (экономия трафика по sender.active)
        mixObserver = Observer { alpha ->
            val a = (alpha ?: 0f).coerceIn(0f, 1f)
            android.util.Log.d("VishnuMix", "CastService.observe alpha=$a muted=${getSavedMute(applicationContext)}")
            try {
                WebRtcCoreHolder.get(applicationContext).setCrossfadeAlpha(a)
            } catch (_: Throwable) { }
        }
        MixerState.alpha01.observeForever(mixObserver!!)

        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startHttpWsIfNeeded()

            ACTION_STOP -> {
                performExit()
                return START_NOT_STICKY
            }

            ACTION_MUTE -> {
                applyMute(true)
            }

            ACTION_UNMUTE -> {
                // Перед открытием микрофона — перевзводим FGS с типом MICROPHONE
                startAsForeground(withMicType = true)
                applyMute(false)
            }

            ACTION_EXIT -> {
                performExit()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun performExit() {
        // 1) Глушим микрофон
        applyMute(true)

        // 2) Снимаем FGS-уведомление и останавливаем сервис
        try { stopForeground(true) } catch (_: Throwable) {}
        isFgShown = false
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // NEW: снять подписку, чтобы не было утечек
        try {
            mixObserver?.let { MixerState.alpha01.removeObserver(it) }
        } catch (_: Throwable) { }
        mixObserver = null

        isRunning = false
        try { server?.shutdown() } catch (_: Throwable) {}
        server = null
        try { WebRtcCoreHolder.closeAndClear() } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- FGS helper ---
    private fun startAsForeground(withMicType: Boolean) {
        val notif = buildRunningNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (withMicType) {
                // ВАЖНО: для AudioPlaybackCapture/MediaProjection требуем оба типа
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        isFgShown = true
    }





    // --- HTTP/WS ---
    private fun startHttpWsIfNeeded() {
        if (server != null) return

        val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var basePort = sp.getInt(KEY_PORT, 8080).coerceIn(1024, 65535)

        var lastError: Throwable? = null
        for (i in 0..9) {
            val tryPort = (basePort + i).coerceIn(1024, 65535)
            try {
                val s = VishnuServer(applicationContext, tryPort)
                s.launch(120_000, false)
                server = s
                sp.edit().putInt(KEY_PORT, tryPort).apply()
                android.util.Log.i("VishnuWS", "HTTP/WS server started on :$tryPort")
                updateNotification()
                return
            } catch (t: Throwable) {
                lastError = t
                android.util.Log.w("VishnuWS", "Port bind failed on :$tryPort — ${t.message}")
            }
        }

        android.util.Log.e("VishnuWS", "Failed to start HTTP/WS server on ports $basePort..${basePort+9}", lastError)
    }

    // --- Mute state ---
    private fun applyMute(muted: Boolean) {
        try { WebRtcCoreHolder.get(applicationContext).setMuted(muted) } catch (_: Throwable) {}
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MUTED, muted).apply()
        updateNotification()
    }

    // --- Notification ---
    private fun buildRunningNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val piOpen = PendingIntent.getActivity(this, 0, openIntent, pendingFlags())

        val muted = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MUTED, true)
        val text = if (muted) getString(R.string.cast_stopped) else getString(R.string.cast_running)

        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(piOpen)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_SERVICE)

        b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        val notif = b.build()
        notif.flags = notif.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
        return notif
    }

    private fun updateNotification() {
        if (!isFgShown) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildRunningNotification())
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "VishnuCast", NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "VishnuCast status"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun pendingFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return flags
    }

    companion object {
        const val ACTION_EXIT_NOW = "com.buligin.vishnucast.action.EXIT_NOW"
        const val ACTION_EXIT = "com.buligin.vishnucast.action.EXIT"

        @Volatile var isRunning: Boolean = false

        const val ACTION_START = "com.buligin.vishnucast.action.START"
        const val ACTION_STOP  = "com.buligin.vishnucast.action.STOP"
        const val ACTION_MUTE  = "com.buligin.vishnucast.action.MUTE"
        const val ACTION_UNMUTE = "com.buligin.vishnucast.action.UNMUTE"

        private const val CHANNEL_ID = "vishnucast_running"
        private const val NOTIF_ID = 1001

        private const val PREFS = "vishnucast"
        const val KEY_PORT = "server_port"
        const val KEY_MUTED = "is_muted"

        fun getSavedMute(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MUTED, true)

        fun ensureStarted(ctx: Context) {
            val i = Intent(ctx, CastService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, i)
        }
    }
}
