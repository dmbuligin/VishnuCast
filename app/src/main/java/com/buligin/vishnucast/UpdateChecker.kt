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
 * Проверка последнего релиза и сравнение с текущей версией приложения.
 *
 * Поддерживаются два формата ответа:
 *  1) GitHub Releases API: https://api.github.com/repos/dmbuligin/VishnuCast/releases/latest
 *     поля: tag_name, prerelease, body, html_url, assets[].name, assets[].browser_download_url, assets[].size
 *  2) Упрощённый мок-JSON (наш локальный сервер):
 *     поля: versionName, body, htmlUrl, downloadUrl, assetName
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

    // === выбери нужный источник: ===
    // private const val API_LATEST = "https://api.github.com/repos/dmbuligin/VishnuCast/releases/latest"
    private const val API_LATEST = "http://192.168.24.1:8000/releases/latest.json" // мок-сервер

    fun checkLatest(callback: (Result<ReleaseInfo?>) -> Unit) {
        Thread {
            try {
                val json = httpGet(API_LATEST)
                val info = parseLatest(JSONObject(json))

                val remote = normalizeVersion(info.versionName)
                val local  = normalizeVersion(BuildConfig.VERSION_NAME)
                val newer = compareVersions(remote, local) > 0

                Log.d(TAG, "remote=$remote local=$local newer=$newer url=${info.downloadUrl}")

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
            instanceFollowRedirects = true
            connectTimeout = 10000
            readTimeout = 15000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
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
        // --- Ветка упрощённого мок-JSON ---
        if (obj.has("versionName") || obj.has("downloadUrl") || obj.has("assetName")) {
            val versionRaw = obj.optString("versionName").ifBlank {
                // fallback на "tag_name"/"name", если вдруг есть
                obj.optString("tag_name").ifBlank { obj.optString("name") }
            }
            val body    = obj.optString("body", "")
            val htmlUrl = obj.optString("htmlUrl", "https://github.com/dmbuligin/VishnuCast/releases")
            val asset   = obj.optString("assetName").takeIf { it.isNotBlank() }
            val dlUrl   = obj.optString("downloadUrl").takeIf { it.isNotBlank() }
            val verNorm = normalizeVersion(versionRaw.removePrefix("v"))

            return ReleaseInfo(
                tag = versionRaw.ifBlank { verNorm },
                versionName = verNorm,
                isPrerelease = false,
                body = body,
                htmlUrl = htmlUrl,
                assetName = asset,
                downloadUrl = dlUrl,
                sizeBytes = 0L
            )
        }

        // --- Ветка GitHub Releases JSON ---
        val tag = obj.optString("tag_name").ifBlank { obj.optString("name") }
        val isPre = obj.optBoolean("prerelease", false)
        val body = obj.optString("body", "")
        val htmlUrl = obj.optString("html_url", "https://github.com/dmbuligin/VishnuCast/releases")
        val assets = obj.optJSONArray("assets") ?: JSONArray()

        var bestName: String? = null
        var bestUrl: String? = null
        var bestSize = 0L
        var bestScore = -1

        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            val size = a.optLong("size", 0L)
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val score = when {
                name.contains("release", ignoreCase = true) -> 3
                name.contains("debug",   ignoreCase = true) -> 1
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

    /**
     * Нормализация версии:
     * берем все числовые группы и склеиваем через точку.
     * Примеры:
     *  "1.6(383829)" -> "1.6.383829"
     *  "v1.70000-mock" -> "1.70000"
     */
    private fun normalizeVersion(raw: String): String {
        val parts = Regex("\\d+").findAll(raw).map { it.value }.toList()
        return if (parts.isEmpty()) "0" else parts.joinToString(".")
    }

    /** Сравнение X.Y.Z (разной длины — с нулями справа) */
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
