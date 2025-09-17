package com.buligin.vishnucast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.random.Random

/**
 * Periodic update checker (GitHub Releases).
 * Default: once per DAY. Change PERIOD_DAYS to 7 for weekly.
 */
class UpdateCheckWorker(appContext: Context, params: WorkerParameters) :
    Worker(appContext, params) {

    override fun doWork(): Result {
        return try {
            val ctx = applicationContext
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            val etagKey = "gh_etag_latest"
            val lastEtag = prefs.getString(etagKey, null)

            val resp = httpGetLatest(lastEtag)
            if (resp.code == 304) return Result.success() // Not modified

            if (resp.code !in 200..299 || resp.body == null) return Result.retry()

            // Save new ETag (if any)
            resp.etag?.let { prefs.edit().putString(etagKey, it).apply() }

            val info = parseLatest(JSONObject(resp.body))
            // Compare versions
            val remote = normalizeVersion(info.versionName)
            val local = normalizeVersion(BuildConfig.VERSION_NAME)
            val newer = compareVersions(remote, local) > 0

            if (newer && info.downloadUrl != null && info.assetName != null) {
                showUpdateNotification(ctx, info)
            }

            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    // --- Notification ---

    private fun showUpdateNotification(ctx: Context, info: ReleaseInfo) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(UPD_CHANNEL, ctx.getString(R.string.upd_chan_name),
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                description = "GitHub releases"
            }
            nm.createNotificationChannel(ch)
        }

        // Тап по уведомлению откроет MainActivity и сразу запустит скачивание
        val intent = Intent(ctx, MainActivity::class.java).apply {
            action = UpdateProtocol.ACTION_DOWNLOAD_UPDATE
            putExtra(UpdateProtocol.EXTRA_UPDATE_URL, info.downloadUrl)
            putExtra(UpdateProtocol.EXTRA_UPDATE_NAME, info.assetName)
            putExtra(UpdateProtocol.EXTRA_UPDATE_VER, info.versionName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(ctx, 2002, intent, flags)

        val title = ctx.getString(R.string.upd_title, info.versionName)
        val text  = ctx.getString(R.string.upd_text)

        val smallIcon = R.drawable.ic_mic_24
        val notif = NotificationCompat.Builder(ctx, UPD_CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(smallIcon)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        nm.notify(NOTIF_ID_UPDATE, notif)
    }

    // --- HTTP & parsing ---

    private data class HttpResp(val code: Int, val body: String?, val etag: String?)

    private fun httpGetLatest(ifNoneMatch: String?): HttpResp {
        val url = URL(API_LATEST)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "VishnuCast/${BuildConfig.VERSION_NAME} (Android)")
            ifNoneMatch?.let { setRequestProperty("If-None-Match", it) }
        }
        val code = conn.responseCode
        val etag = conn.getHeaderField("ETag")
        if (code == 304) return HttpResp(code, null, etag)

        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.use { ins ->
            BufferedReader(InputStreamReader(ins)).use { br ->
                val sb = StringBuilder()
                var line: String?
                while (br.readLine().also { line = it } != null) sb.append(line).append('\n')
                sb.toString()
            }
        }
        return HttpResp(code, body, etag)
    }

    private data class ReleaseInfo(
        val versionName: String,
        val isPrerelease: Boolean,
        val htmlUrl: String,
        val assetName: String?,
        val downloadUrl: String?
    )

    private fun parseLatest(obj: JSONObject): ReleaseInfo {
        val tag = obj.optString("tag_name").ifBlank { obj.optString("name") }
        val isPre = obj.optBoolean("prerelease", false)
        val htmlUrl = obj.optString("html_url", "https://github.com/dmbuligin/VishnuCast/releases")
        val assets = obj.optJSONArray("assets") ?: JSONArray()

        var bestName: String? = null
        var bestUrl: String? = null
        var bestScore = -1
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (!name.endsWith(".apk", true)) continue
            val score = when {
                name.contains("release", true) -> 3
                name.contains("debug", true)   -> 1
                else                           -> 2
            }
            if (score > bestScore) {
                bestScore = score
                bestName = name
                bestUrl = url
            }
        }

        val ver = normalizeVersion(tag.removePrefix("v"))
        return ReleaseInfo(
            versionName = ver,
            isPrerelease = isPre,
            htmlUrl = htmlUrl,
            assetName = bestName,
            downloadUrl = bestUrl
        )
    }

    private fun normalizeVersion(raw: String): String {
        val cleaned = raw.lowercase().replace(Regex("[^0-9\\.]"), "").trim('.')
        return if (cleaned.isBlank()) "0" else cleaned
    }

    private fun compareVersions(a: String, b: String): Int {
        val aspl = a.split('.')
        val bspl = b.split('.')
        val n = max(aspl.size, bspl.size)
        for (i in 0 until n) {
            val ai = aspl.getOrNull(i)?.toIntOrNull() ?: 0
            val bi = bspl.getOrNull(i)?.toIntOrNull() ?: 0
            if (ai != bi) return ai - bi
        }
        return 0
    }

    companion object {
        private const val API_LATEST = "https://api.github.com/repos/dmbuligin/VishnuCast/releases/latest"
        private const val PREFS = "vishnucast"
        private const val UPD_CHANNEL = "updates"
        private const val NOTIF_ID_UPDATE = 2003

        private const val PERIOD_DAYS = 7L      // <-- меняй на 7 для еженедельной проверки
        private const val FLEX_HOURS  = 6L      // окно гибкости (джиттер) для рассинхронизации
        private const val JITTER_MIN_MAX = 90L  // случайная стартовая задержка (минут)

        /** Вызывать один раз (например, в MainActivity.onCreate). Повторы игнорируются. */
        fun ensureScheduled(ctx: Context) {
            val jitterMin = Random.nextLong(0, JITTER_MIN_MAX)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val req = PeriodicWorkRequestBuilder<UpdateCheckWorker>(PERIOD_DAYS, TimeUnit.DAYS, FLEX_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(jitterMin, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("vishnucast_update_checker", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
