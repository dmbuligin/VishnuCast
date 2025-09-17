package com.buligin.vishnucast

import android.app.DownloadManager
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Скачивает APK через DownloadManager в app-specific ExternalFiles/Download и запускает установку.
 */
object ApkDownloader {

    private var lastDownloadId: Long = -1L
    private var receiver: BroadcastReceiver? = null

    fun downloadAndInstall(ctx: Context, url: String, fileName: String) {
        // Android 8+: проверим право на установку из неизвестных источников
        if (Build.VERSION.SDK_INT >= 26) {
            val pm = ctx.packageManager
            if (!pm.canRequestPackageInstalls()) {
                Toast.makeText(ctx, R.string.update_error, Toast.LENGTH_LONG).show()
                // Открыть настройки: дать пользователю выдать право, затем повторить операцию
                try {
                    val i = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                } catch (_: Throwable) {}
                return
            }
        }

        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription(ctx.getString(R.string.app_name))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverRoaming(true)
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, fileName)

        try {
            lastDownloadId = dm.enqueue(req)
        } catch (t: Throwable) {
            Toast.makeText(ctx, ctx.getString(R.string.update_error) + ": " + (t.message ?: "Download failed"), Toast.LENGTH_LONG).show()
            return
        }

        // Подпишемся на завершение
        val appCtx = ctx.applicationContext
        if (receiver == null) {
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (id != lastDownloadId) return
                    try { appCtx.unregisterReceiver(this) } catch (_: Throwable) {}
                    receiver = null
                    // Готово — ставим
                    installDownloadedApk(appCtx, fileName)
                }
            }
            appCtx.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
        Toast.makeText(ctx, R.string.update_checking, Toast.LENGTH_SHORT).show()
    }

    private fun installDownloadedApk(ctx: Context, fileName: String) {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val apk = File(dir, fileName)
        if (!apk.exists()) {
            Toast.makeText(ctx, ctx.getString(R.string.update_error) + ": file missing", Toast.LENGTH_LONG).show()
            return
        }

        val uri: Uri = try {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", apk)
        } catch (_: Throwable) {
            Uri.fromFile(apk) // fallback для старых устройств
        }

        val install = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        try {
            ctx.startActivity(install)
        } catch (t: Throwable) {
            Toast.makeText(ctx, ctx.getString(R.string.update_error) + ": " + (t.message ?: "Install failed"), Toast.LENGTH_LONG).show()
        }
    }
}
