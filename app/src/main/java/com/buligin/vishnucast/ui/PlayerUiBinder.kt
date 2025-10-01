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
import com.buligin.vishnucast.CastService
import com.buligin.vishnucast.audio.PlayerCore
import com.buligin.vishnucast.service.MixerState

/**
 * Шаг 3.1: плеер берём из CastService через биндинг.
 * UI НЕ создаёт PlayerCore и НЕ вызывает setPlaylist напрямую — это делает сервис.
 */
class PlayerUiBinder(private val activity: AppCompatActivity) : LifecycleEventObserver {

    private lateinit var player: PlayerCore
    private var service: CastService? = null

    private var btnPrev: ImageButton? = null
    private var btnPlayPause: ImageButton? = null
    private var btnNext: ImageButton? = null
    private var seek: SeekBar? = null
    private var tvNow: TextView? = null
    private var tvDur: TextView? = null
    private var tvTitle: TextView? = null

    // B1: кросс-фейдер управляет громкостью плеера (0..1)
    private var cross: SeekBar? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (this@PlayerUiBinder::player.isInitialized) {
                player.tick() // совместимостьный ручной тик
            }
            uiHandler.postDelayed(this, 500L)
        }
    }

    /**
     * Вызывается из Activity при подключении к CastService (см. onServiceConnected в Activity).
     * Берём плеер из сервиса и ОДИН раз инициализируем плейлист (без автоплея).
     */
    fun onServiceConnected(svc: CastService, startIndex: Int? = null) {
        service = svc
        player = svc.playerCore()

        // Однократная инициализация плейлиста в сервисе (без автозапуска)
        if (startIndex != null) {
            svc.ensurePlaylistInitialized(startIndex)
            player.pause()
            player.seekTo(0L)
        } else {
            svc.ensurePlaylistInitialized()
        }

        wirePlayerObservers()
        setControlsEnabled(true)
        refreshUiFromPlayer()

// Синхронизируем стартовую громкость плеера с текущим alpha01 (без ожидания LiveData-обновления)
        val a = (MixerState.alpha01.value ?: 0f).coerceIn(0f, 1f)
        player.setVolume01(a)
        cross?.let {
            val p = (a * 100).toInt()
            if (it.progress != p) it.progress = p
        }
    }

    /**
     * Перечитать плейлист в СЕРВИСЕ и (опц.) перейти на индекс — без setPlaylist в UI.
     * Используй в сценарии «тап по треку».
     */
    fun reloadPlaylistAndPlay(startIndex: Int?) {
        val svc = service ?: return
        if (!this::player.isInitialized) return

        if (startIndex != null) {
            // «Тап по треку»: синхронизируем с хранилищем и переходим на трек (без автоплея)
            svc.refreshPlaylistFromStore(playIndex = startIndex, autoPlay = false)
            player.pause()
            player.seekTo(0L)
        } else {
            // Без конкретного трека — убеждаемся, что плейлист инициализирован
            svc.ensurePlaylistInitialized()
        }

        setControlsEnabled(true)
        refreshUiFromPlayer()
    }

    fun attach(root: View = activity.findViewById(android.R.id.content)): PlayerUiBinder {
        // Привязка вьюх
        tvTitle = root.findViewById(R.id.playerTitle)
        btnPrev = root.findViewById(R.id.playerPrev)
        btnPlayPause = root.findViewById(R.id.playerPlayPause)
        btnNext = root.findViewById(R.id.playerNext)
        seek = root.findViewById(R.id.playerSeek)
        tvNow = root.findViewById(R.id.playerNow)
        tvDur = root.findViewById(R.id.playerDur)
        cross = root.findViewById(R.id.mixCrossfader)

        // Пока сервис не подключён — контролы выключены
        setControlsEnabled(false)
        resetUiToDefaults()

        // Кнопки управления — работаем только когда player инициализирован
        btnPrev?.setOnClickListener {
            if (this::player.isInitialized) {
                player.previous()
                player.pause(); player.seekTo(0L)
            }
        }
        btnNext?.setOnClickListener {
            if (this::player.isInitialized) {
                player.next()
                player.pause(); player.seekTo(0L)
            }
        }
        btnPlayPause?.setOnClickListener {
            if (this::player.isInitialized) player.toggle()
        }
        btnPlayPause?.setOnLongClickListener {
            if (this::player.isInitialized) { player.pause(); player.seekTo(0L) }
            true
        }

        seek?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && this@PlayerUiBinder::player.isInitialized) {
                    player.seekTo(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // B1: кросс-фейдер управляет громкостью плеера; α публикуем в MixerState для совместимости
        cross?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val a = (progress / 100f).coerceIn(0f, 1f)
                MixerState.alpha01.postValue(a)
                if (this@PlayerUiBinder::player.isInitialized) {
                    player.setVolume01(a)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        MixerState.alpha01.observe(activity, Observer { a ->
            val val01 = (a ?: 0f).coerceIn(0f, 1f)
            cross?.let { if (it.progress != (val01 * 100).toInt()) it.progress = (val01 * 100).toInt() }
            if (this::player.isInitialized) {
                player.setVolume01(val01)
            }
        })

        uiHandler.post(ticker)
        activity.lifecycle.addObserver(this)
        return this
    }

    fun release() {
        uiHandler.removeCallbacksAndMessages(null)
        // Player освобождает сервис — здесь player.release() НЕ вызываем
        activity.lifecycle.removeObserver(this)
    }

    override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_DESTROY) release()
    }

    // --- internal ---

    private fun wirePlayerObservers() {
        // Заголовок
        player.title.observe(activity, Observer { title ->
            tvTitle?.text = if (title.isNullOrBlank()) activity.getString(R.string.cast_player_title) else title
        })
        // Статус ▶/⏸
        player.isPlaying.observe(activity, Observer { playing ->
            btnPlayPause?.setImageResource(if (playing == true) R.drawable.ic_pause_24 else R.drawable.ic_play_24)
        })
        // Позиция
        player.positionMs.observe(activity, Observer { pos ->
            val p = (pos ?: 0L).coerceAtLeast(0L)
            seek?.progress = p.toInt()
            tvNow?.text = formatMs(p)
        })
        // Длительность
        player.durationMs.observe(activity, Observer { dur ->
            val d = (dur ?: 0L).coerceAtLeast(0L)
            seek?.max = d.toInt()
            tvDur?.text = formatMs(d)
        })
    }

    private fun refreshUiFromPlayer() {
        if (!this::player.isInitialized) return
        tvTitle?.text = player.current()?.title ?: activity.getString(R.string.cast_player_title)
        tvDur?.text = formatMs(player.durationMs.value ?: 0L)
        tvNow?.text = formatMs(player.positionMs.value ?: 0L)
        btnPlayPause?.setImageResource(if (player.isPlaying.value == true) R.drawable.ic_pause_24 else R.drawable.ic_play_24)
        seek?.max = (player.durationMs.value ?: 0L).toInt()
        seek?.progress = (player.positionMs.value ?: 0L).toInt()
    }

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
        btnPlayPause?.isEnabled = enabled
        cross?.isEnabled = enabled
    }

    private fun formatMs(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }
}
