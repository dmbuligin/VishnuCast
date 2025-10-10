package com.buligin.vishnucast.audio

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class PlaylistStore(private val context: Context) {

    private val file: File by lazy { File(context.filesDir, "playlist.json") }

    @Synchronized
    fun load(): MutableList<PlaylistItem> {
        if (!file.exists()) return mutableListOf()
        val text = runCatching { file.readText() }.getOrElse { "" }
        if (text.isBlank()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(text)
            val out = ArrayList<PlaylistItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    PlaylistItem(
                        id = o.optString("id"),
                        uri = o.optString("uri"),
                        title = o.optString("title"),
                        durationMs = o.optLong("durationMs", 0L),
                        sort = o.optInt("sort", i)
                    )
                )
            }
            out.sortedBy { it.sort }.toMutableList()
        }.getOrElse { mutableListOf() }
    }

    @Synchronized
    fun save(list: List<PlaylistItem>) {
        val arr = JSONArray()
        list.sortedBy { it.sort }.forEach { item ->
            val o = JSONObject()
                .put("id", item.id)
                .put("uri", item.uri)
                .put("title", item.title)
                .put("durationMs", item.durationMs)
                .put("sort", item.sort)
            arr.put(o)
        }
        file.writeText(arr.toString(2))
    }

    fun addUris(uris: List<Uri>): List<PlaylistItem> {
        val current = load()
        val baseSort = (current.maxOfOrNull { it.sort } ?: -1) + 1
        val cr = context.contentResolver
        val added = uris.mapIndexed { idx, uri ->
            takePersistablePermissionIfPossible(uri)
            val (title, _) = resolveMeta(cr, uri)
            PlaylistItem(
                id = UUID.randomUUID().toString(),
                uri = uri.toString(),
                title = title,
                durationMs = 0L, // уточним позже в PlayerCore при необходимости
                sort = baseSort + idx
            )
        }
        val merged = (current + added).sortedBy { it.sort }
        save(merged)
        return merged
    }

    fun remove(id: String): List<PlaylistItem> {
        val out = load().filterNot { it.id == id }.sortedBy { it.sort }
        save(out)
        return out
    }

    fun reorder(idsInOrder: List<String>): List<PlaylistItem> {
        val map = load().associateBy { it.id }
        val reordered = idsInOrder.mapIndexedNotNull { index, id ->
            map[id]?.copy(sort = index)
        }
        save(reordered)
        return reordered
    }

    private fun resolveMeta(cr: ContentResolver, uri: Uri): Pair<String, Long> {
        return runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0) ?: uri.lastPathSegment ?: "audio"
                    val size = if (!c.isNull(1)) c.getLong(1) else 0L
                    name to size
                } else uri.lastPathSegment.orEmpty() to 0L
            } ?: (uri.lastPathSegment.orEmpty() to 0L)
        }.getOrElse { (uri.lastPathSegment.orEmpty() to 0L) }
    }

    private fun takePersistablePermissionIfPossible(uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }
    }
}
