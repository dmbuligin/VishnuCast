package com.buligin.vishnucast

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.util.Log
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

class CastService : Service() {

    private lateinit var core: WebRtcCore

    private var _mixerObserver: androidx.lifecycle.Observer<Float>? = null

    private var server: VishnuServer? = null
    private var isFgShown: Boolean = false // флаг: уведомление реально запущено через startForeground

    override fun onCreate() {
        super.onCreate()
        createNotificationChannelIfNeeded()

        // На старте сервиса — FGS с типом DATA_SYNC (Android 14 требует тип)
        startAsForeground(withMicType = false)

        // Поднять HTTP/WS сразу
        startHttpWsIfNeeded()

        // Mute по умолчанию
        applyMute(true)

        // === MIX bridge: наблюдаем alpha и шлём в браузер ===
        val mixObserver = androidx.lifecycle.Observer<Float> { a ->
            val muted = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MUTED, true)
            try {
                val alpha = (a ?: 0f).coerceIn(0f, 1f)
                android.util.Log.d("VishnuMix", "CastService.observe alpha=$alpha micMuted=$muted")
                SignalingSocket.broadcastMix(alpha, muted)
                WebRtcCoreHolder.get(applicationContext).trySetActiveByAlpha(alpha)
                WebRtcCoreHolder.get(applicationContext).setForceProbeByAlpha(alpha, muted)
                android.util.Log.d("VishnuMix", "CastService.broadcastMix sent")
            } catch (_: Throwable) { }
        }
        // гарантируем, что PeerManager-ы есть
        PeerManagers.ensure(applicationContext)


        _mixerObserver = mixObserver
        com.buligin.vishnucast.player.MixerState.alpha01.observeForever(mixObserver)


        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startHttpWsIfNeeded()

            ACTION_STOP -> {
                performExit()
                return START_NOT_STICKY
            }

            ACTION_MUTE -> applyMute(true)

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
        isRunning = false
        try { server?.shutdown() } catch (_: Throwable) {}
        server = null

        // Закрыть обе PC и освободить нативный источник
        try {
            val c = WebRtcCoreHolder.peek()
            c?.close(WebRtcCore.PcKind.MIC)
            c?.close(WebRtcCore.PcKind.PLAYER)
            c?.dispose()
            Log.d("CastService", "WebRtcCore closed (MIC/PLAYER) and disposed")
        } catch (_: Throwable) {}

        try {
            WebRtcCoreHolder.closeAndClear()
        } catch (_: Throwable) {}

        super.onDestroy()

        try {
            _mixerObserver?.let { com.buligin.vishnucast.player.MixerState.alpha01.removeObserver(it) }
        } catch (_: Throwable) { }
        _mixerObserver = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- FGS helper ---
    private fun startAsForeground(withMicType: Boolean) {
        val notif = buildRunningNotification()
        val fgsTypes =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                (if (withMicType) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, fgsTypes)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        isFgShown = true
    }

    // --- HTTP/WS ---
    private fun startHttpWsIfNeeded() {
        if (server != null) return
        val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val port = sp.getInt(KEY_PORT, 8080).coerceIn(1, 65535)
        server = VishnuServer(applicationContext, port).also {
            it.launch(120_000, false)
        }
        updateNotification()
    }

    // --- Mute state ---
    private fun applyMute(muted: Boolean) {
        try { WebRtcCoreHolder.get(applicationContext).setMuted(muted) } catch (_: Throwable) {}
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MUTED, muted).apply()
        updateNotification()
    }

    // ========= 2PC SIGNALING HELPERS (вызываются из WS-роутеров) =========

    /** Создать PC по типу и вернуть ссылку (если нужно держать у себя). */
    fun createPc(kind: WebRtcCore.PcKind, onIce: (IceCandidate) -> Unit): PeerConnection? {
        return WebRtcCoreHolder.get(applicationContext).createPeerConnection(kind, onIce)
    }

    /** Установить удалённый SDP (offer) и получить локальный (answer) через callback. */
    fun setRemoteOffer(kind: WebRtcCore.PcKind, remoteSdp: String, onLocalAnswer: (SessionDescription) -> Unit) {
        WebRtcCoreHolder.get(applicationContext).setRemoteSdp(kind, remoteSdp, onLocalAnswer)
    }

    /** Добавить ICE-кандидата для конкретной PC. */
    fun addIce(kind: WebRtcCore.PcKind, cand: IceCandidate) {
        WebRtcCoreHolder.get(applicationContext).addIceCandidate(kind, cand)
    }

    /** Закрыть конкретную PC. */
    fun closePc(kind: WebRtcCore.PcKind) {
        WebRtcCoreHolder.get(applicationContext).close(kind)
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
