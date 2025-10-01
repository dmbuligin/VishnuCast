package com.buligin.vishnucast

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Binder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.buligin.vishnucast.audio.PlayerCore
import com.buligin.vishnucast.audio.PlaylistStore

class CastService : Service() {

    private var server: VishnuServer? = null
    private var isFgShown: Boolean = false

    // --- Player в сервисе ---
    //private lateinit var player: PlayerCore
    //private lateinit var playlistStore: PlaylistStore

    // --- Binder для UI ---
    inner class LocalBinder : Binder() {
        val service: CastService get() = this@CastService
    }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannelIfNeeded()
        startAsForeground(withMicType = false)
        startHttpWsIfNeeded()

        //playlistStore = PlaylistStore(this)
        //player = PlayerCore(this).also { it.setPlaylist(playlistStore.load()) }

        applyMute(true)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startHttpWsIfNeeded()
            ACTION_STOP  -> { performExit(); return START_NOT_STICKY }
            ACTION_MUTE  -> applyMute(true)
            ACTION_UNMUTE -> { startAsForeground(withMicType = true); applyMute(false) }
            ACTION_EXIT  -> { performExit(); return START_NOT_STICKY }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

   // fun playerCore(): PlayerCore? = if (this::player.isInitialized) player else null

    private fun performExit() {
        applyMute(true)
        try { stopForeground(true) } catch (_: Throwable) {}
        isFgShown = false
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        try { server?.shutdown() } catch (_: Throwable) {}
        server = null
       // runCatching { if (this::player.isInitialized) player.release() }
        try { WebRtcCoreHolder.closeAndClear() } catch (_: Throwable) {}
        super.onDestroy()
    }

    // --- FGS/HTTP/WS/Notification ниже без изменений ---
    private fun startAsForeground(withMicType: Boolean) {
        val notif = buildRunningNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (withMicType)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(NOTIF_ID, notif, type)
        } else startForeground(NOTIF_ID, notif)
        isFgShown = true
    }

    private fun startHttpWsIfNeeded() {
        if (server != null) return
        val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val port = sp.getInt(KEY_PORT, 8080).coerceIn(1, 65535)
        server = VishnuServer(applicationContext, port).also { it.launch(120_000, false) }
        updateNotification()
    }

    private fun applyMute(muted: Boolean) {
        try { WebRtcCoreHolder.get(applicationContext).setMuted(muted) } catch (_: Throwable) {}
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_MUTED, muted).apply()
        updateNotification()
    }

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
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

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
