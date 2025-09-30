package com.buligin.vishnucast.audio

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

class PlaylistStore(private val context: Context) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file: File by lazy { File(context.filesDir, "playlist.json") }

    @Synchronized
    fun load(): MutableList<PlaylistItem> {
        if (!file.exists()) return mutableListOf()
        return runCatching {
            json.decodeFromString<List<PlaylistItem>>(file.readText()).toMutableList()
        }.getOrElse { mutableListOf() }
    }

    @Synchronized
    fun save(list: List<PlaylistItem>) {
        file.writeText(json.encodeToString(list))
    }

    fun addUris(uris: List<Uri>): List<PlaylistItem> {
        val current = load()
        val baseSort = (current.maxOfOrNull { it.sort } ?: -1) + 1
        val cr = context.contentResolver
        val added = uris.mapIndexed { idx, uri ->
            takePersistablePermissionIfPossible(uri)
            val meta = resolveMeta(cr, uri)
            PlaylistItem(
                id = UUID.randomUUID().toString(),
                uri = uri.toString(),
                title = meta.first,
                durationMs = 0L,     // уточним позже при загрузке в PlayerCore
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
        val map = load().associateBy { it.id }.toMutableMap()
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
                    val size = c.getLong(1)
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
