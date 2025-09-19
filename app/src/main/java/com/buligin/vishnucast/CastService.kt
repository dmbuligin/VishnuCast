package com.buligin.vishnucast

import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.media.AudioDeviceInfo
import android.media.AudioManager
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket

class CastService : Service() {
    private var netMon: NetworkMonitor? = null
    private var server: VishnuServer? = null
    private var boundPort: Int = 8080

    override fun onCreate() {
        super.onCreate()

        // Переводим сервис в foreground со «стикерным» уведомлением
        createNotificationChannelIfNeeded()
        startForeground(NOTIF_ID, buildRunningNotification())

        netMon = NetworkMonitor(this) {
            try { WebRtcCoreHolder.closeAndClear() } catch (_: Throwable) {}
        }.also { it.start() }


        // Выбираем доступный порт, начиная с 8080
        val port = pickAvailablePort(8080, maxTries = 50)
            ?: run {
                Logger.e("CastService", "No free port in 8080..8129")
                safeNotify(NOTIF_ID, buildErrorNotification("No free port (8080..8129)"))
                stopSelf()
                return
            }

        try {
            boundPort = port
            // Сохраним выбранный порт для UI (MainActivity прочитает из prefs)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_PORT, boundPort).apply()

            // Стартуем VishnuServer (WS + HTTP) на выбранном порту
            server = VishnuServer(applicationContext, boundPort)
            server?.launch(300_000, /*daemon=*/false)
            Logger.i("CastService", "VishnuServer started on :$boundPort")
        } catch (e: Throwable) {
            Logger.e("CastService", "Failed to start server on :$boundPort", e)
            safeNotify(NOTIF_ID, buildErrorNotification("Server error: ${e.message ?: "unknown"}"))
            stopSelf()
            return
        }

        // Локальная индикация уровня
        MicLevelProbe.start(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Фикс состояния при пересоздании Activity (поворот экрана и т.п.)
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            MicLevelProbe.stop()
        } catch (_: Throwable) { }

        try {
            server?.shutdown()
            Logger.i("CastService", "VishnuServer stopped")
        } catch (_: Throwable) { }

        // Сервис реально остановлен — сбрасываем флаг
        isRunning = false

        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        netMon?.stop()
        netMon = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------- Port picking --------------------

    private fun pickAvailablePort(start: Int, maxTries: Int): Int? {
        for (p in start until (start + maxTries)) {
            if (isTcpPortFree(p)) return p
        }
        return null
    }

    private fun isTcpPortFree(port: Int): Boolean {
        var sock: ServerSocket? = null
        return try {
            sock = ServerSocket()
            sock.reuseAddress = true
            sock.bind(InetSocketAddress("0.0.0.0", port))
            true
        } catch (_: IOException) {
            false
        } finally {
            try { sock?.close() } catch (_: Throwable) { }
        }
    }

    // -------------------- Notifications --------------------

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val existing = mgr.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "VishnuCast status"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun buildRunningNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // --- Выбираем иконку по текущему источнику (USB ↔ встроенный) ---
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val usb = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
        val smallIconRes = if (usb) R.drawable.ic_headset_mic_24 else R.drawable.ic_mic_24
        // ---------------------------------------------------------------

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIconRes)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.status_running))
            .setContentIntent(pi)
            .setOngoing(true) // липкое уведомление
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(
                if (Build.VERSION.SDK_INT >= 31)
                    NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
                else NotificationCompat.FOREGROUND_SERVICE_DEFAULT
            )
            .build()
    }

    private fun buildErrorNotification(msg: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(msg)
            .setContentIntent(pi)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .build()
    }

    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun safeNotify(id: Int, notif: Notification) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Logger.w("CastService", "POST_NOTIFICATIONS not granted on 33+; skip notify")
                return
            }
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id, notif)
    }

    companion object {
        @Volatile var isRunning: Boolean = false

        private const val CHANNEL_ID = "vishnucast_running"
        private const val NOTIF_ID = 1001

        private const val PREFS = "vishnucast"
        const val KEY_PORT = "server_port"
    }
}
