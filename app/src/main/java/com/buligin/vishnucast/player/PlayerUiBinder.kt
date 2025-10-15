package com.buligin.vishnucast.player

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import com.buligin.vishnucast.R
import com.buligin.vishnucast.player.capture.PlayerCapture
import com.buligin.vishnucast.player.capture.PlayerSystemCapture
import android.os.Build





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

    // MediaProjection launcher
    private var projectionLauncher: ActivityResultLauncher<Intent>? = null
    private var onProjectionGranted: (() -> Unit)? = null

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

        // Регистрация лаунчера системного запроса MediaProjection
        projectionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { res ->
            PlayerCapture.onProjectionResult(res.resultCode, res.data)
            val action = onProjectionGranted
            onProjectionGranted = null
            if (PlayerCapture.isGranted()) {
                action?.invoke()
            }
        }

        btnPrev?.setOnClickListener {
            if (playlistStore.load().isNotEmpty()) {
                player.previous()
                // оставляем на паузе — единообразно с тапом по элементу
                player.pause(); player.seekTo(0L)
                stopSystemCaptureIfQ()
            }
        }

        btnNext?.setOnClickListener {
            if (playlistStore.load().isNotEmpty()) {
                player.next()
                maybeStartWithProjection {
                    player.play()
                    startSystemCaptureIfQ()
                }
            }
        }

        btnPlayPause?.setOnClickListener {
            if (player.isPlaying.value == true) {
                player.pause()
                stopSystemCaptureIfQ()
            } else {
                maybeStartWithProjection {
                    player.play()
                    startSystemCaptureIfQ()
                }
            }
        }

        /* Долгий тап = STOP */
        btnPlayPause?.setOnLongClickListener {
            player.pause()
            player.seekTo(0L)
            stopSystemCaptureIfQ()
            true
        }

        seek?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) player.seekTo(p.toLong()) }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // MIXER: кросс-фейдер — публикуем α
        cross?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val a = (progress / 100f).coerceIn(0f, 1f)
                MixerState.alpha01.postValue(a)
                android.util.Log.d("VishnuMix", "UI alpha01=$a")
                // player.setVolume01(a) // при необходимости — как «грубой» громкости плеера
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
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

    private fun releaseSystemCaptureIfQ() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { PlayerSystemCapture.release() } catch (_: Throwable) {}
        }
    }


    override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_DESTROY -> {
                // Гасим только при реальном закрытии экрана (не при поворотах/навигации)
                val finishing = activity.isFinishing || (!activity.isChangingConfigurations)
                if (finishing) {
                    if (this::player.isInitialized && player.isPlaying.value == true) {
                        player.pause()
                    }
                    releaseSystemCaptureIfQ() // включает stop() + release()
                }
            }
            else -> { /* no-op */ }
        }
    }



    // --- helpers ---

    private fun startSystemCaptureIfQ() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { PlayerSystemCapture.start(activity) } catch (_: Throwable) {}
        }
    }
    private fun stopSystemCaptureIfQ() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { PlayerSystemCapture.stop() } catch (_: Throwable) {}
        }
    }




    private fun maybeStartWithProjection(action: () -> Unit) {
        if (PlayerCapture.isGranted()) {
            action()
            return
        }
        onProjectionGranted = action
        try {
            val intent = PlayerCapture.createRequestIntent(activity)
            projectionLauncher?.launch(intent)
        } catch (_: Throwable) {
            // тихо игнорируем, UI не падает
        }
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
    }

    private fun formatMs(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }
}
