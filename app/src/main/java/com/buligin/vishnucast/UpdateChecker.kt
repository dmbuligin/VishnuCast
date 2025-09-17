package com.buligin.vishnucast

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/**
 * Проверка последнего стабильного релиза на GitHub и сравнение с текущей версией приложения.
 * Источник: https://api.github.com/repos/dmbuligin/VishnuCast/releases/latest
 */
object UpdateChecker {

    data class ReleaseInfo(
        val tag: String,
        val versionName: String,
        val isPrerelease: Boolean,
        val body: String,
        val htmlUrl: String,
        val assetName: String?,
        val downloadUrl: String?,
        val sizeBytes: Long
    )

    private const val TAG = "UpdateChecker"
    private const val API_LATEST = "https://api.github.com/repos/dmbuligin/VishnuCast/releases/latest"

    fun checkLatest(callback: (Result<ReleaseInfo?>) -> Unit) {
        Thread {
            try {
                val json = httpGet(API_LATEST)
                val info = parseLatest(JSONObject(json))
                // Если удалённая версия не новее — вернём null
                val remote = normalizeVersion(info.versionName)
                val local = normalizeVersion(BuildConfig.VERSION_NAME)
                val newer = compareVersions(remote, local) > 0
                Handler(Looper.getMainLooper()).post {
                    callback(Result.success(if (newer) info else null))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "checkLatest failed", t)
                Handler(Looper.getMainLooper()).post {
                    callback(Result.failure(t))
                }
            }
        }.start()
    }

    // --- helpers ---

    private fun httpGet(urlStr: String): String {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "VishnuCast/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})")
        }
        conn.inputStream.use { ins ->
            BufferedReader(InputStreamReader(ins)).use { br ->
                val sb = StringBuilder()
                var line: String?
                while (br.readLine().also { line = it } != null) sb.append(line).append('\n')
                return sb.toString()
            }
        }
    }

    private fun parseLatest(obj: JSONObject): ReleaseInfo {
        val tag = obj.optString("tag_name").ifBlank { obj.optString("name") }
        val isPre = obj.optBoolean("prerelease", false)
        val body = obj.optString("body", "")
        val htmlUrl = obj.optString("html_url", "https://github.com/dmbuligin/VishnuCast/releases")
        val assets = obj.optJSONArray("assets") ?: JSONArray()

        // Выбираем APK-ассет: приоритет release > debug > любой .apk
        var bestName: String? = null
        var bestUrl: String? = null
        var bestSize = 0L
        fun consider(name: String, url: String, size: Long, score: Int): Pair<Int, Boolean> {
            // score: выше — лучше
            return Pair(score, true)
        }

        var bestScore = -1
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            val size = a.optLong("size", 0L)
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val score = when {
                name.contains("release", ignoreCase = true) -> 3
                name.contains("debug", ignoreCase = true) -> 1
                else -> 2
            }
            if (score > bestScore) {
                bestScore = score
                bestName = name
                bestUrl = url
                bestSize = size
            }
        }

        val ver = normalizeVersion(tag.removePrefix("v"))
        return ReleaseInfo(
            tag = tag,
            versionName = ver,
            isPrerelease = isPre,
            body = body,
            htmlUrl = htmlUrl,
            assetName = bestName,
            downloadUrl = bestUrl,
            sizeBytes = bestSize
        )
    }

    /** Оставляем только числа и точки (семвер-подобное сравнение) */
    private fun normalizeVersion(raw: String): String {
        val cleaned = raw.lowercase()
            .replace(Regex("[^0-9\\.]"), "")
            .trim('.')
        return if (cleaned.isBlank()) "0" else cleaned
    }

    /** Сравнение X.Y.Z */
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
}
