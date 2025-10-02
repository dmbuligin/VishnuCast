package com.buligin.vishnucast.audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.MediaMetadata
//import com.buligin.vishnucast.audio.TeeRenderersFactory
//import com.google.android.exoplayer2.RenderersFactory
import com.buligin.vishnucast.audio.PlayerPcmBus


/**
 * Минимальный рабочий плеер без Tee/RenderersFactory.
 * Живёт в UI (см. PlayerUiBinder).
 * Шаг 1: добавлены AudioAttributes(MEDIA/MUSIC) + handleAudioFocus + becomingNoisy.
 */
class PlayerCore(context: Context) {

    private val app: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isPlaying = MutableLiveData(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _positionMs = MutableLiveData(0L)
    val positionMs: LiveData<Long> = _positionMs

    private val _durationMs = MutableLiveData(0L)
    val durationMs: LiveData<Long> = _durationMs

    private val _title = MutableLiveData("")
    val title: LiveData<String> = _title

    private var tickerPosted = false

    // Tee: подключаем процессор съёма PCM в AudioSink
    private val exo: ExoPlayer = ExoPlayer.Builder(
        app,
        TeeRenderersFactory(app, arrayOf(ExoTeeAudioProcessor()))
    ).build().apply {
        repeatMode = Player.REPEAT_MODE_OFF

        // AudioAttributes: MEDIA/MUSIC + захват аудиофокуса и реакция на "becoming noisy"
        val aa: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        setAudioAttributes(aa, /* handleAudioFocus = */ true)
        setHandleAudioBecomingNoisy(true)



    }






    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            updateIsPlaying()
            if (playbackState == Player.STATE_READY) {
                _durationMs.postValue(exo.duration.coerceAtLeast(0L))
            }
            // 👉 если плеер не в активном воспроизведении — чистим шину
            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                PlayerPcmBus.clear()
            }
            scheduleTicker()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.postValue(isPlaying)
            // 👉 при переходе в паузу мгновенно чистим шину
            if (!isPlaying) {
                PlayerPcmBus.clear()
                PlayerPcmBus.resetSequential()
            }
            scheduleTicker()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _durationMs.postValue(exo.duration.coerceAtLeast(0L))
            _positionMs.postValue(exo.currentPosition.coerceAtLeast(0L))
            _title.postValue(mediaItem?.mediaMetadata?.title?.toString().orEmpty())
        }

        override fun onPlayerError(error: PlaybackException) {
            // Без падений: просто остановим воспроизведение.
            _isPlaying.postValue(false)
            PlayerPcmBus.clear() // 👉 на ошибке тоже чистим
            PlayerPcmBus.resetSequential()
            scheduleTicker()
        }
    }





    private var playlist: MutableList<PlaylistItem> = mutableListOf()
    private var currentIndex: Int = -1

    init {
        exo.addListener(listener)
    }

    /** Установить новый плейлист (без автозапуска). */
    fun setPlaylist(items: List<PlaylistItem>) {
        playlist = items.toMutableList()
        currentIndex = if (playlist.isNotEmpty()) 0 else -1

        exo.stop()
        exo.clearMediaItems()

        // Не автопускаем — PlayerUiBinder сам решает, что делать дальше.
        if (currentIndex >= 0) {
            exo.setMediaItems(playlist.map { it.toMediaItem() }, /* resetPosition= */ true)
            exo.prepare()
            // Обновим метаданные для UI
            val mi = exo.currentMediaItem
            _title.postValue(mi?.mediaMetadata?.title?.toString().orEmpty())
            _durationMs.postValue(exo.duration.coerceAtLeast(0L))
            _positionMs.postValue(0L)
        } else {
            _title.postValue("")
            _durationMs.postValue(0L)
            _positionMs.postValue(0L)
        }
    }

    /** Проиграть трек по индексу. */
    fun playIndex(index: Int) {
        if (index !in playlist.indices) return
        currentIndex = index
        exo.seekTo(index, /* positionMs= */ 0L)
        exo.playWhenReady = true
        exo.prepare()
    }

    fun play() {
        exo.playWhenReady = true
        exo.prepare()
    }

    fun pause() {
        exo.playWhenReady = false
    }

    fun toggle() {
        if (exo.isPlaying) pause() else play()
    }

    fun next() {
        if (exo.hasNextMediaItem()) {
            exo.seekToNextMediaItem()
            exo.playWhenReady = true
        }
    }

    fun previous() {
        if (exo.hasPreviousMediaItem()) {
            exo.seekToPreviousMediaItem()
            exo.playWhenReady = true
        } else {
            // если нет предыдущего — в начало текущего
            exo.seekTo(0)
        }
    }

    fun seekTo(positionMs: Long) {
        exo.seekTo(positionMs.coerceAtLeast(0L))
    }

    /** Громкость для кросс-фейдера (0f..1f). */
    fun setVolume(volume: Float) {
        exo.volume = volume.coerceIn(0f, 1f)
    }

    fun getVolume(): Float = exo.volume

    fun current(): PlaylistItem? = playlist.getOrNull(exo.currentMediaItemIndex)

    fun release() {
        exo.removeListener(listener)
        exo.release()
        mainHandler.removeCallbacksAndMessages(null)
        tickerPosted = false
    }

    // --- Совместимость с текущим PlayerUiBinder ---
// Ручной тикер для UI (обновляет позицию/длительность раз в 500 мс)
    fun tick() {
        _positionMs.postValue(exo.currentPosition.coerceAtLeast(0L))
        _durationMs.postValue(exo.duration.coerceAtLeast(0L))
    }

    // Перегрузка setPlaylist с указанием стартового индекса (без автопуска)
    fun setPlaylist(items: List<PlaylistItem>, startIndex: Int) {
        setPlaylist(items) // базовая логика подготовки и prepare()
        if (startIndex in items.indices) {
            currentIndex = startIndex
            exo.seekTo(startIndex, /* positionMs = */ 0L)
            exo.playWhenReady = false // остаёмся на паузе в начале
        }
    }

    // Текущий индекс для UI-биндера
    fun currentIndex(): Int = exo.currentMediaItemIndex

    // Громкость 0..1 (алиас для setVolume)
    fun setVolume01(a01: Float) {
        setVolume(a01)
    }





    // ---- internal ----

    private fun scheduleTicker() {
        if (!tickerPosted) {
            tickerPosted = true
            mainHandler.post(tickRunnable)
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            tickerPosted = false
            // Позиция/длительность доступны в main потоке.
            _positionMs.postValue(exo.currentPosition.coerceAtLeast(0L))
            _durationMs.postValue(exo.duration.coerceAtLeast(0L))
            if (exo.isPlaying) {
                // Обновляемся ~2 раза в секунду, если играет.
                mainHandler.postDelayed(this, 500L)
                tickerPosted = true
            }
        }
    }

    private fun updateIsPlaying() {
        _isPlaying.postValue(exo.isPlaying)
    }

    // Mapping PlaylistItem -> MediaItem
    private fun PlaylistItem.toMediaItem(): MediaItem {
        return MediaItem.Builder()
            .setUri(Uri.parse(uri))
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .build()
            )
            .build()
    }
}
