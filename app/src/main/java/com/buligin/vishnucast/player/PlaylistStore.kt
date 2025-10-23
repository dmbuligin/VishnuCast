package com.buligin.vishnucast.player

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PlaylistStore(private val context: Context) {

    private val TAG = "VishnuPlaylistStore"
    private val prefs = context.getSharedPreferences("vishnu_playlist", Context.MODE_PRIVATE)
    private val KEY_ITEMS = "items"

    /** Загрузить плейлист (отсортирован по полю sort) */
    fun load(): MutableList<PlaylistItem> {
        val raw = prefs.getString(KEY_ITEMS, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Throwable) { JSONArray() }
        val list = ArrayList<PlaylistItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val uri = o.optString("uri", "")
            val title = o.optString("title", "")
            val sort = o.optInt("sort", i)
            if (id.isNotEmpty() && uri.isNotEmpty()) {
                list.add(PlaylistItem(id = id, uri = uri, title = title, sort = sort))
            }
        }
        list.sortBy { it.sort }
        return list.toMutableList()
    }

    /** Полностью сохранить список как есть */
    fun save(list: List<PlaylistItem>) {
        val arr = JSONArray()
        list.forEach { item ->
            val o = JSONObject()
                .put("id", item.id)
                .put("uri", item.uri)
                .put("title", item.title)
                .put("sort", item.sort)
            arr.put(o)
        }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    /** Добавить URI-ы в конец плейлиста; вернуть актуальный список */
    fun addUris(uris: List<Uri>): List<PlaylistItem> {
        val cur = load().toMutableList()
        var nextSort = (cur.maxOfOrNull { it.sort } ?: -1) + 1
        uris.forEach { u ->
            val s = u.toString()
            val title = guessTitle(u)
            cur.add(
                PlaylistItem(
                    id = UUID.randomUUID().toString(),
                    uri = s,
                    title = title,
                    sort = nextSort++
                )
            )
        }
        save(cur)
        return cur
    }

    /** Удалить элемент по id; вернуть актуальный список */
    fun remove(id: String): List<PlaylistItem> {
        val cur = load().filter { it.id != id }
        // Сохраняем sort как есть (или можно пересчитать — по вкусу)
        save(cur)
        return cur
    }

    /** Переупорядочить по списку id (первый в списке получает sort=0 и т.д.) */
    fun reorder(orderIds: List<String>) {
        val map = load().associateBy { it.id }.toMutableMap()
        val res = ArrayList<PlaylistItem>(map.size)
        var s = 0
        orderIds.forEach { id ->
            val item = map.remove(id) ?: return@forEach
            res.add(item.copy(sort = s++))
        }
        // оставшиеся (если вдруг были) доклеим в конец, сохранив относительный порядок
        if (map.isNotEmpty()) {
            map.values.sortedBy { it.sort }.forEach { item ->
                res.add(item.copy(sort = s++))
            }
        }
        save(res)
    }

    private fun guessTitle(uri: Uri): String {
        // простая эвристика названия — последний сегмент пути без параметров
        val last = uri.lastPathSegment ?: uri.toString()
        return last.substringAfterLast('/').ifEmpty { uri.toString() }
    }
}
