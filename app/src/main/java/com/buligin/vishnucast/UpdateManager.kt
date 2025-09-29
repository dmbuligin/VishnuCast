package com.buligin.vishnucast.update

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.buligin.vishnucast.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

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

    private const val TAG = "Updater"

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var job: Job? = null
    private var downloadId: Long = -1L

    /**
     * Начать скачивание APK.
     * @param url прямая ссылка на .apk
     * @param fileName имя файла (напр., "VishnuCast-1.6.apk")
     */
    fun startDownload(ctx: Context, url: String, fileName: String) {
        cancel() // на всякий
        _state.value = State.Idle

        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        // Каталог внутри "Downloads" нашего приложения (стабильно для FileProvider)
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
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)

        // Старт
        downloadId = dm.enqueue(req)

        // Периодический опрос прогресса
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                var lastEmit = -1
                val q = DownloadManager.Query().setFilterById(downloadId)
                while (isActive) {
                    delay(400)
                    val c = dm.query(q) ?: break
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
                                // === ВАЛИДАЦИЯ ФАЙЛА ПЕРЕД УСТАНОВКОЙ ===
                                if (!validateApk(ctx, outFile)) {
                                    // validateApk выставит Failed(..) с причиной
                                    return@launch
                                }
                                _state.value = State.Completed(outFile)
                                return@launch
                            }
                            DownloadManager.STATUS_FAILED -> {
                                val reason = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                                fail("Download failed (code=$reason)")
                                return@launch
                            }
                            DownloadManager.STATUS_PAUSED,
                            DownloadManager.STATUS_PENDING -> {
                                _state.value = State.Running(pct.coerceIn(0, 100), soFar, total)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                fail(t.message ?: "Download error")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = State.Idle
    }

    /**
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
            // ВАЖНО: authority должен совпадать с манифестом (у тебя ".provider")
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
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    // ==================== ВАЛИДАЦИЯ APK ====================

    private fun validateApk(ctx: Context, file: File): Boolean {
        // 1) ZIP магик (PK\x03\x04)
        FileInputStream(file).use { fis ->
            val hdr = ByteArray(4)
            val n = fis.read(hdr)
            if (n != 4 || hdr[0].toInt() != 0x50 || hdr[1].toInt() != 0x4B) {
                return fail("Downloaded file is not a valid APK (no ZIP header)")
            }
        }

        // 2) Мини-осмотр содержимого: есть ли AndroidManifest.xml / .dex
        try {
            ZipFile(file).use { zip ->
                val hasManifest = zip.getEntry("AndroidManifest.xml") != null
                val hasDex = zip.entries().asSequence().any { it.name.endsWith(".dex", true) }
                if (!hasManifest || !hasDex) {
                    return fail("APK content looks invalid (manifest/dex missing)")
                }
            }
        } catch (_: Throwable) {
            return fail("Not a valid APK (zip read error)")
        }

        // 3) Читаем PackageInfo из архива
        val pi = parsePackage(ctx, file) ?: return fail("Not a valid APK (parse error)")

        // 4) packageName совпадает?
        val expectedPkg = BuildConfig.APPLICATION_ID
        if (pi.packageName != expectedPkg) {
            return fail("Wrong package: ${pi.packageName} (expected $expectedPkg)")
        }

        // 5) versionCode должен быть > установленного
        val installed = try { ctx.packageManager.getPackageInfo(expectedPkg, 0) } catch (_: Throwable) { null }
        val newVc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        val oldVc = if (installed != null) {
            if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else @Suppress("DEPRECATION") installed.versionCode.toLong()
        } else -1L
        if (installed != null && newVc <= oldVc) {
            return fail("Version code not higher: new=$newVc ≤ installed=$oldVc")
        }

        // 6) ABI совместимость (если в APK есть native-библиотеки)
        val apkAbis = readApkAbis(file)
        val deviceAbis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        if (apkAbis.isNotEmpty()) {
            val ok = apkAbis.any { a -> deviceAbis.any { it.equals(a, true) } }
            if (!ok) return fail("No matching ABIs. apk=$apkAbis device=$deviceAbis")
        }

        // 7) Подписи: если установлен уже есть — подписи должны совпасть
        if (installed != null) {
            val newSigs = getArchiveSignatures(ctx, file)
            val oldSigs = getInstalledSignatures(ctx, expectedPkg)
            if (newSigs.isNotEmpty() && oldSigs.isNotEmpty() && newSigs.none { it in oldSigs }) {
                return fail("Signature mismatch with installed app (debug vs release?). Uninstall current app first or sign with the same keystore.")
            }
        }

        // 8) Для диагностики — размер/sha256
        runCatching {
            Log.d(TAG, "APK ok: ${file.name} size=${file.length()} sha256=${sha256(file)} vc=$newVc abis=$apkAbis")
        }

        return true
    }

    private fun parsePackage(ctx: Context, file: File): PackageInfo? {
        return try {
            val pm = ctx.packageManager
            val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SIGNING_CERTIFICATES
            val pi: PackageInfo? = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(file.absolutePath, flags)
            }
            // applicationInfo может быть null — укажем пути аккуратно
            pi?.applicationInfo?.let { ai ->
                ai.sourceDir = file.absolutePath
                ai.publicSourceDir = file.absolutePath
            }
            pi
        } catch (_: Throwable) {
            null
        }
    }

    private fun readApkAbis(file: File): List<String> {
        return try {
            ZipFile(file).use { zip ->
                val set = mutableSetOf<String>()
                val e = zip.entries()
                while (e.hasMoreElements()) {
                    val z = e.nextElement()
                    if (z.name.startsWith("lib/") && z.name.count { it == '/' } >= 2) {
                        // lib/<abi>/xxx.so
                        val parts = z.name.split('/')
                        if (parts.size >= 3) set += parts[1]
                    }
                }
                set.toList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun getInstalledSignatures(ctx: Context, pkg: String): List<String> {
        return try {
            val pm = ctx.packageManager
            val pi = if (Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            }
            if (Build.VERSION.SDK_INT >= 28) {
                pi.signingInfo?.apkContentsSigners?.map { it.toCharsString() } ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                pi.signatures?.map { it.toCharsString() } ?: emptyList()
            }
        } catch (_: Throwable) { emptyList() }
    }

    private fun getArchiveSignatures(ctx: Context, file: File): List<String> {
        return try {
            val pm = ctx.packageManager
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                ))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            }
            if (Build.VERSION.SDK_INT >= 28) {
                pi?.signingInfo?.apkContentsSigners?.map { it.toCharsString() } ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                pi?.signatures?.map { it.toCharsString() } ?: emptyList()
            }
        } catch (_: Throwable) { emptyList() }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fail(msg: String): Boolean {
        Log.e(TAG, msg)
        _state.value = State.Failed(msg)
        return false
    }
}
