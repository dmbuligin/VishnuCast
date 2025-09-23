package com.buligin.vishnucast

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class CastService : Service() {

    private var server: VishnuServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannelIfNeeded()
        startForeground(NOTIF_ID, buildRunningNotification())

        // 1) Поднять HTTP/WS сервер немедленно
        startHttpWsIfNeeded()

        // 2) Гарантировать инициализацию WebRTC core и включить "тишину"
        val core = WebRtcCoreHolder.get(applicationContext)
        try { core.setMuted(true) } catch (_: Throwable) {}

        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // уже поднято в onCreate; оставляем для явного старта
                startHttpWsIfNeeded()
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MUTE -> {
                try { WebRtcCoreHolder.get(applicationContext).setMuted(true) } catch (_: Throwable) {}
                updateNotification()
            }
            ACTION_UNMUTE -> {
                try { WebRtcCoreHolder.get(applicationContext).setMuted(false) } catch (_: Throwable) {}
                updateNotification()
            }
        }
        // НЕ «липкий» сервис: живём только вместе с задачей приложения
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Если пользователь смахнул приложение — сервис сворачиваем
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        try { server?.shutdown() } catch (_: Throwable) {}
        server = null
        try { WebRtcCoreHolder.closeAndClear() } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- HTTP/WS ---

    private fun startHttpWsIfNeeded() {
        if (server != null) return
        val port = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PORT, 8080).coerceIn(1, 65535)
        server = VishnuServer(applicationContext, port).also {
            // Таймаут чтения сокета — как в текущей логике; daemon=false
            it.launch(120_000, false)
        }
        updateNotification()
    }

    // --- Notification ---

    private fun buildRunningNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val piOpen = PendingIntent.getActivity(this, 0, openIntent, pendingFlags())

        val stopIntent = Intent(this, CastService::class.java).setAction(ACTION_STOP)
        val piStop = PendingIntent.getService(this, 1, stopIntent, pendingFlags())

        return NotificationCompat.Builder(this, CHANNEL_ID)
            // ИСПРАВЛЕНО: используем существующую иконку из mipmap
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.cast_running))
            .setContentIntent(piOpen)
            .setOngoing(true)
            .addAction(0, getString(R.string.action_stop), piStop)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildRunningNotification())
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "VishnuCast", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "VishnuCast status"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
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
        @Volatile var isRunning: Boolean = false

        const val ACTION_START = "com.buligin.vishnucast.action.START"
        const val ACTION_STOP  = "com.buligin.vishnucast.action.STOP"
        const val ACTION_MUTE  = "com.buligin.vishnucast.action.MUTE"
        const val ACTION_UNMUTE = "com.buligin.vishnucast.action.UNMUTE"

        private const val CHANNEL_ID = "vishnucast_running"
        private const val NOTIF_ID = 1001

        private const val PREFS = "vishnucast"
        const val KEY_PORT = "server_port"

        fun ensureStarted(ctx: Context) {
            val i = Intent(ctx, CastService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, i)
        }
    }
}
