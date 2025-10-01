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
import android.util.Log
import com.google.android.exoplayer2.analytics.AnalyticsListener





class PlayerCore(context: Context) {


    private val TAG = "VC/PlayerCore"


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
                /* handleAudioFocus = */ true
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
            override fun onPlaybackStateChanged(state: Int) {
                val name = when (state) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> state.toString()
                }
                Log.d(TAG, "onPlaybackStateChanged: $name isPlaying=${exo.isPlaying}")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "onIsPlayingChanged: $isPlaying pos=${exo.currentPosition} dur=${exo.duration}")
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "onPlayerError: ${error.errorCodeName} msg=${error.message}", error)
                _isPlaying.postValue(false)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                _isPlaying.postValue(player.isPlaying)
                _durationMs.postValue(if (player.duration > 0) player.duration else 0L)
                _positionMs.postValue(player.currentPosition)
                val t = player.currentMediaItem?.mediaMetadata?.title?.toString() ?: ""
                if (t != _title.value) Log.d(TAG, "onEvents: title='$t'")
                _title.postValue(t)
            }
        })

// Полезные события аудио-тракта
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
                Log.d(TAG, "AudioSessionId=$audioSessionId")
            }
            override fun onAudioEnabled(eventTime: AnalyticsListener.EventTime, counters: com.google.android.exoplayer2.decoder.DecoderCounters) {
                Log.d(TAG, "AudioEnabled")
            }
            override fun onAudioDisabled(eventTime: AnalyticsListener.EventTime, counters: com.google.android.exoplayer2.decoder.DecoderCounters) {
                Log.d(TAG, "AudioDisabled")
            }
            override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: java.lang.Exception) {
                Log.e(TAG, "AudioCodecError: ${audioCodecError.message}", audioCodecError)
            }
        })


    }

    fun setPlaylist(items: List<PlaylistItem>, startIndex: Int = 0) {

        Log.d(TAG, "setPlaylist: items=${items.size} startIndex=$startIndex")

        exo.stop()
        exo.clearMediaItems()
        val mediaItems: List<MediaItem> = items.sortedBy { it.sort }.map { it.toMediaItem() }
        val start = if (mediaItems.isNotEmpty()) startIndex.coerceIn(0, mediaItems.lastIndex) else 0
        exo.setMediaItems(mediaItems, start, 0L)

        val mi = exo.currentMediaItem
        Log.d(TAG, "prepared mediaItem index=${exo.currentMediaItemIndex} uri=${mi?.localConfiguration?.uri}")

        exo.prepare()



    }

    fun play() { Log.d(TAG, "play()"); exo.play() }
    fun pause() { Log.d(TAG, "pause()"); exo.pause() }
    fun toggle() { Log.d(TAG, "toggle() from isPlaying=${exo.isPlaying}"); if (exo.isPlaying) pause() else play() }
    fun seekTo(ms: Long) { Log.d(TAG, "seekTo($ms)"); exo.seekTo(ms.coerceAtLeast(0L)) }
    fun next() { Log.d(TAG, "next()"); exo.seekToNextMediaItem() }
    fun previous() { Log.d(TAG, "previous()"); exo.seekToPreviousMediaItem() }


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
