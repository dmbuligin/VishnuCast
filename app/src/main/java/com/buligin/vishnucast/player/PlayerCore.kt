package com.buligin.vishnucast.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes

class PlayerCore(context: Context) {

    private val TAG = "VishnuExo"
    private val app: Context = context.applicationContext

    private val exo: ExoPlayer = ExoPlayer.Builder(app).build().apply {
        repeatMode = Player.REPEAT_MODE_OFF
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC) // не трогаем ваш тип
                .build(),
            /* handleAudioFocus = */ false                 // <-- отключаем авто-паузу по фокусу
        )
        setHandleAudioBecomingNoisy(false)                 // <-- не ставить паузу при "noisy"
        setWakeMode(C.WAKE_MODE_LOCAL)                     // <-- держать wake при погашенном экране
    }



    private val _isPlaying = MutableLiveData(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _positionMs = MutableLiveData(0L)
    val positionMs: LiveData<Long> = _positionMs

    private val _durationMs = MutableLiveData(0L)
    val durationMs: LiveData<Long> = _durationMs

    private val _title = MutableLiveData("")
    val title: LiveData<String> = _title

    init {
        exo.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                _isPlaying.postValue(player.isPlaying)
                _durationMs.postValue(if (player.duration > 0) player.duration else 0L)
                _positionMs.postValue(player.currentPosition)
                _title.postValue(player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "")
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Player error: ${error.errorCodeName} ${error.message}")
                _isPlaying.postValue(false)
            }
        })
    }

    fun setPlaylist(items: List<PlaylistItem>, startIndex: Int = 0) {
        exo.stop()
        exo.clearMediaItems()

        val mediaItems: List<MediaItem> = items
            .sortedBy { it.sort }
            .mapNotNull { it.safeToMediaItem(app, TAG) }

        if (mediaItems.isEmpty()) {
            Log.w(TAG, "No playable items (filtered)")
            return
        }

        val clampedIndex = startIndex.coerceIn(0, mediaItems.lastIndex)
        exo.setMediaItems(mediaItems, clampedIndex, 0L)
        exo.prepare()
    }

    fun play() {
        Log.w(TAG, "play() called", Throwable("who-called-play"))    // ВРЕМЕННО
        exo.play()
    }

    fun pause() {
        Log.w(TAG, "pause() called", Throwable("who-called-pause"))  // ВРЕМЕННО: покажет стек вызова
        exo.pause()
    }
    fun toggle() { if (exo.isPlaying) pause() else play() }
    fun seekTo(ms: Long) = exo.seekTo(ms.coerceAtLeast(0L))
    fun next() = exo.seekToNextMediaItem()
    fun previous() = exo.seekToPreviousMediaItem()

    fun currentIndex(): Int = exo.currentMediaItemIndex
    fun setVolume() { /* keep for compat */ }
    fun setVolume01(v: Float) { exo.volume = v.coerceIn(0f, 1f) }

    fun release() { exo.release() }

    fun tick() {
        _positionMs.postValue(exo.currentPosition)
        _durationMs.postValue(if (exo.duration > 0) exo.duration else 0L)
    }
}

data class PlaylistItem(
    val id: String,
    val uri: String,
    val title: String,
    val sort: Int = 0
)

private fun PlaylistItem.safeToMediaItem(app: Context, tag: String): MediaItem? {
    val u = try { Uri.parse(uri) } catch (t: Throwable) {
        Log.w(tag, "Bad URI: $uri (${t.message})"); return null
    }
    val scheme = (u.scheme ?: "").lowercase()

    if (scheme == "http" || scheme == "https" || scheme == "file") {
        return baseMediaItem(u)
    }

    if (scheme == "content") {
        // Если нет persistent-разрешения, пробуем просто открыть FD на чтение (API 19+).
        if (!hasPersistedRead(app, u) && !canOpenForRead(app, u)) {
            Log.w(tag, "No permission to read $u — skipping")
            return null
        }
        return baseMediaItem(u)
    }

    Log.w(tag, "Unsupported URI scheme '$scheme' for $uri — skipped")
    return null
}

private fun PlaylistItem.baseMediaItem(u: Uri): MediaItem {
    return MediaItem.Builder()
        .setUri(u)
        .setMediaId(id)
        .setMediaMetadata(
            com.google.android.exoplayer2.MediaMetadata.Builder()
                .setTitle(title)
                .build()
        )
        .build()
}

private fun hasPersistedRead(app: Context, uri: Uri): Boolean {
    return try {
        app.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri == uri }
    } catch (_: Throwable) { false }
}

private fun canOpenForRead(app: Context, uri: Uri): Boolean {
    return try {
        // Доступно с API 19; не требует CancellationSignal
        app.contentResolver.openFileDescriptor(uri, "r")?.use { }
        true
    } catch (_: SecurityException) {
        false
    } catch (_: Throwable) {
        false
    }
}
