package com.buligin.vishnucast.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import com.buligin.vishnucast.R
import com.buligin.vishnucast.audio.PlayerCore
import com.buligin.vishnucast.audio.PlaylistStore
import com.buligin.vishnucast.audio.MixerState

/**
 * Ненавязчиво встраивает PlayerCore в существующий экран.
 * НИКАК не трогает остальной код MainActivity.
 */
class PlayerUiBinder(private val activity: AppCompatActivity) : LifecycleEventObserver {

    private val app get() = activity.applicationContext

    private lateinit var player: PlayerCore
    private lateinit var playlistStore: PlaylistStore

    private var btnPrev: ImageButton? = null
    private var btnPlayPause: ImageButton? = null
    private var btnNext: ImageButton? = null
    private var seek: SeekBar? = null
    private var tvNow: TextView? = null
    private var tvDur: TextView? = null
    private var tvTitle: TextView? = null

    // MIXER: crossfader
    private var cross: SeekBar? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            player.tick()
            uiHandler.postDelayed(this, 500L)
        }
    }

    /** Перечитать playlist из хранилища и (опц.) начать воспроизведение с индекса */
    fun reloadPlaylistAndPlay(startIndex: Int?) {
        if (!this::player.isInitialized) return
        val list = playlistStore.load()
        val enabled = list.isNotEmpty()
        setControlsEnabled(enabled)

        if (!enabled) {
            player.setPlaylist(list)
            resetUiToDefaults()
            return
        }

        if (startIndex != null && startIndex in list.indices) {
            player.setPlaylist(list, startIndex)
            player.pause()
            player.seekTo(0L) // остаёмся на паузе в начале трека
        } else {
            player.setPlaylist(list, player.currentIndex().coerceAtLeast(0))
        }



    }

    fun attach(root: View = activity.findViewById(android.R.id.content)): PlayerUiBinder {
        // Инициализация core
        playlistStore = PlaylistStore(app)
        player = PlayerCore(app).also {
            it.setPlaylist(playlistStore.load())
        }

        // Привязка вьюх (если панель присутствует в разметке)
        tvTitle = root.findViewById(R.id.playerTitle)
        btnPrev = root.findViewById(R.id.playerPrev)
        btnPlayPause = root.findViewById(R.id.playerPlayPause)
        btnNext = root.findViewById(R.id.playerNext)
        seek = root.findViewById(R.id.playerSeek)
        tvNow = root.findViewById(R.id.playerNow)
        tvDur = root.findViewById(R.id.playerDur)
        cross = root.findViewById(R.id.mixCrossfader) // MIXER

        // Если панель отсутствует — выходим тихо, не мешаем экрану
        if (btnPlayPause == null || seek == null) return this

        // Начальное состояние enable/disable
        setControlsEnabled(playlistStore.load().isNotEmpty())

        btnPrev?.setOnClickListener {
            if (playlistStore.load().isNotEmpty()) {
                player.previous()
                // оставляем на паузе — единообразно с тапом по элементу
                player.pause(); player.seekTo(0L)
            }
        }
        btnNext?.setOnClickListener {
            if (playlistStore.load().isNotEmpty()) {
                player.next()
                player.play()
            }
        }

        btnPlayPause?.setOnClickListener { player.toggle() }

        /* Долгий тап = STOP */
        btnPlayPause?.setOnLongClickListener {
            player.pause()
            player.seekTo(0L)
            true
        }

        seek?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) player.seekTo(p.toLong()) }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // MIXER: кросс-фейдер — публикуем α и регулируем громкость плеера прямо сейчас (B1)
        cross?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val a = (progress / 100f).coerceIn(0f, 1f)
                MixerState.alpha01.postValue(a)
                player.setVolume01(a) // временно как громкость плеера
                // микрофон сейчас идёт в WebRTC как прежде; на B2 α пойдёт в реальный микшер
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Подпишем α на случай внешних изменений (например, mute логика)
        MixerState.alpha01.observe(activity, Observer { a ->
            cross?.progress = (a * 100).toInt()
            player.setVolume01(a)
        })

        // Наблюдатели плеера
        player.title.observe(activity, Observer { title ->
            tvTitle?.text = if (title.isNullOrBlank()) activity.getString(R.string.cast_player_title) else title
        })
        player.isPlaying.observe(activity, Observer { playing ->
            btnPlayPause?.setImageResource(if (playing == true) R.drawable.ic_pause_24 else R.drawable.ic_play_24)
        })
        player.positionMs.observe(activity, Observer { pos ->
            seek?.progress = (pos ?: 0L).toInt()
            tvNow?.text = formatMs(pos ?: 0L)
        })
        player.durationMs.observe(activity, Observer { dur ->
            val d = (dur ?: 0L).coerceAtLeast(0L)
            seek?.max = d.toInt()
            tvDur?.text = formatMs(d)
        })

        // Авто-тикер
        uiHandler.post(ticker)

        // Следим за жизненным циклом, чтобы вовремя освободиться
        activity.lifecycle.addObserver(this)
        return this
    }

    fun release() {
        uiHandler.removeCallbacksAndMessages(null)
        if (this::player.isInitialized) player.release()
        activity.lifecycle.removeObserver(this)
    }

    override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_DESTROY) {
            release()
        }
    }

    // --- helpers ---

    private fun resetUiToDefaults() {
        tvTitle?.text = activity.getString(R.string.cast_player_title)
        tvNow?.text = "0:00"
        tvDur?.text = "0:00"
        seek?.max = 0
        seek?.progress = 0
        btnPlayPause?.setImageResource(R.drawable.ic_play_24)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        btnPrev?.isEnabled = enabled
        btnNext?.isEnabled = enabled
        seek?.isEnabled = enabled
    }

    private fun formatMs(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }
}
