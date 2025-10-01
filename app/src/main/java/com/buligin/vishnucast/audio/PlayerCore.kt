package com.buligin.vishnucast.audio

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes


class PlayerCore(context: Context) {

    private val app: Context = context.applicationContext
    private val tee = ExoTeeAudioProcessor()

    private val exo: ExoPlayer = ExoPlayer.Builder(app)
        .setRenderersFactory(TeeRenderersFactory(app, arrayOf(tee)))
        .build()
        .apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }
        .also { player ->
            // Явные атрибуты звука (MEDIA/MUSIC), чтобы воспроизведение стартовало корректно на всех OEM
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(com.google.android.exoplayer2.C.USAGE_MEDIA)
                    .setContentType(com.google.android.exoplayer2.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ false
            )

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
                _isPlaying.postValue(false)
            }
        })
    }

    fun setPlaylist(items: List<PlaylistItem>, startIndex: Int = 0) {
        exo.stop()
        exo.clearMediaItems()
        val mediaItems: List<MediaItem> = items.sortedBy { it.sort }.map { it.toMediaItem() }
        val start = if (mediaItems.isNotEmpty()) startIndex.coerceIn(0, mediaItems.lastIndex) else 0
        exo.setMediaItems(mediaItems, start, 0L)
        exo.prepare()
    }

    fun play() = exo.play()
    fun pause() = exo.pause()
    fun toggle() { if (exo.isPlaying) pause() else play() }
    fun seekTo(ms: Long) = exo.seekTo(ms.coerceAtLeast(0L))
    fun next() = exo.seekToNextMediaItem()
    fun previous() = exo.seekToPreviousMediaItem()
    fun currentIndex(): Int = exo.currentMediaItemIndex
    fun setVolume01(v: Float) { exo.volume = v.coerceIn(0f, 1f) }
    fun release() { exo.release() }

    fun tick() {
        _positionMs.postValue(exo.currentPosition)
        _durationMs.postValue(if (exo.duration > 0) exo.duration else 0L)
    }

    private fun PlaylistItem.toMediaItem(): MediaItem {
        return MediaItem.Builder()
            .setUri(Uri.parse(uri))
            .setMediaId(id)
            .setMediaMetadata(
                com.google.android.exoplayer2.MediaMetadata.Builder()
                    .setTitle(title)
                    .build()
            )
            .build()
    }
}
