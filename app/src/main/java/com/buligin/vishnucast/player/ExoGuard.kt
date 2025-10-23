package com.buligin.vishnucast.player

import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import android.util.Log
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem

object ExoGuard {
    private const val TAG = "VishnuExoGuard"

    /** Есть ли устойчивый (persisted) доступ к данному URI через SAF? */
    private fun hasPersistedRead(ctx: Context, uri: Uri): Boolean {
        val perms: List<UriPermission> = ctx.contentResolver.persistedUriPermissions
        return perms.any { it.isReadPermission && it.uri == uri }
    }

    /** Попытаться получить и сохранить доступ, если интент с флагами нам передали. */
    fun tryPersistFromResultIntent(ctx: Context, resultData: Intent?) {
        if (resultData == null) return
        val uri = resultData.data ?: return
        val flags = resultData.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, flags)
            Log.d(TAG, "persisted read for $uri")
        } catch (t: Throwable) {
            Log.w(TAG, "takePersistableUriPermission failed for $uri: ${t.message}")
        }
    }

    /**
     * Безопасно задать источник плееру.
     * Если нет прав — корректно отлоггировать и вернуть false (без исключений).
     */
    fun safeSetMediaItem(player: ExoPlayer, ctx: Context, uri: Uri): Boolean {
        return try {
            if (!hasPersistedRead(ctx, uri)) {
                Log.w(TAG, "No SAF read permission for $uri — skip setMediaItem to avoid crash")
                false
            } else {
                player.setMediaItem(MediaItem.fromUri(uri))
                player.prepare()
                true
            }
        } catch (t: SecurityException) {
            Log.e(TAG, "SecurityException opening $uri", t)
            false
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected error opening $uri", t)
            false
        }
    }
}
