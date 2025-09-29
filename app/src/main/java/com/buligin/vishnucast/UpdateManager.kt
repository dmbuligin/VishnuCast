package com.buligin.vishnucast.update

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Менеджер скачивания и установки APK с контролем прогресса.
 * Использует DownloadManager + FileProvider.
 *
 * Поток состояний: state: Idle → Running(p%) → Completed(file) | Failed(reason)
 */
object UpdateManager {

    sealed class State {
        object Idle : State()
        data class Running(val progress: Int, val bytes: Long, val total: Long) : State()
        data class Completed(val file: File) : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var job: Job? = null
    private var downloadId: Long = -1L

    /**
     * Начать скачивание APK.
     * @param url прямая ссылка на .apk (например, ассет GitHub Releases)
     * @param fileName имя файла для сохранения (напр., "VishnuCast-1.6.apk")
     */
    fun startDownload(ctx: Context, url: String, fileName: String) {
        cancel() // на всякий
        _state.value = State.Idle

        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        // Каталог внутри "Downloads" для нашего приложения, чтобы FileProvider работал стабильно
        val destDir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        destDir.mkdirs()
        val outFile = File(destDir, fileName)
        if (outFile.exists()) outFile.delete()

        val uri = Uri.parse(url)
        val req = DownloadManager.Request(uri)
            .setMimeType("application/vnd.android.package-archive")
            .setTitle(fileName)
            .setDescription("VishnuCast — загрузка обновления")
            .setDestinationUri(Uri.fromFile(outFile))
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE) // покажем системный прогресс
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)

        // Старт
        downloadId = dm.enqueue(req)

        // Периодический опрос прогресса
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                var lastEmit = 0
                val q = DownloadManager.Query().setFilterById(downloadId)
                while (isActive) {
                    delay(400)
                    val c: Cursor = dm.query(q) ?: break
                    c.use { cur ->
                        if (!cur.moveToFirst()) return@use
                        val status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val soFar = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val pct = if (total > 0) ((soFar * 100L) / total).toInt() else 0

                        when (status) {
                            DownloadManager.STATUS_RUNNING -> {
                                if (pct != lastEmit) {
                                    lastEmit = pct
                                    _state.value = State.Running(pct.coerceIn(0, 100), soFar, total)
                                }
                            }
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                _state.value = State.Completed(outFile)
                                return@launch
                            }
                            DownloadManager.STATUS_FAILED -> {
                                val reason = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                                _state.value = State.Failed("Download failed (code=$reason)")
                                return@launch
                            }
                            DownloadManager.STATUS_PAUSED -> {
                                _state.value = State.Running(pct.coerceIn(0, 100), soFar, total)
                            }
                            DownloadManager.STATUS_PENDING -> {
                                _state.value = State.Running(0, soFar, total)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                _state.value = State.Failed(t.message ?: "Download error")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = State.Idle
    }

    /**
     * Проверка, можем ли мы запускать установку APK из нашего приложения.
     * Для Android O+ (26+) нужно разрешение "Install unknown apps" для нашего пакета.
     */
    fun canRequestInstall(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.packageManager.canRequestPackageInstalls()
        } else true
    }

    /**
     * Открыть системную страницу "Разрешить установку неизвестных приложений" для нашего пакета (O+).
     */
    fun openUnknownSourcesSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${ctx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        }
    }

    /**
     * Запустить установку APK через системный установщик.
     */
    fun installApk(ctx: Context, file: File) : Boolean {
        return try {
            val apkUri = FileProvider.getUriForFile(
                ctx,
                ctx.packageName + ".provider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (t: Throwable) {
            false
        }
    }
}
