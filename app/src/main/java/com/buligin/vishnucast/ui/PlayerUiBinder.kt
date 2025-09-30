package com.buligin.vishnucast.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import com.buligin.vishnucast.CastService
import com.buligin.vishnucast.R
import com.buligin.vishnucast.audio.PlayerCore
import com.buligin.vishnucast.audio.PlaylistStore
import com.buligin.vishnucast.service.MixerState

class PlayerUiBinder(private val activity: AppCompatActivity) : LifecycleEventObserver {

    private val app get() = activity.applicationContext

    private var service: CastService? = null
    private var player: PlayerCore? = null
    private lateinit var playlistStore: PlaylistStore

    private var btnPrev: ImageButton? = null
    private var btnPlayPause: ImageButton? = null
    private var btnNext: ImageButton? = null
    private var seek: SeekBar? = null
    private var tvNow: TextView? = null
    private var tvDur: TextView? = null
    private var tvTitle: TextView? = null
    private var cross: SeekBar? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            player?.tick()
            uiHandler.postDelayed(this, 500L)
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as? CastService.LocalBinder ?: return
            service = b.service
            player = service?.playerCore()
            hookPlayerObservers()
            syncControlsEnabled()
            // подтянуть текущий плейлист (на случай, если обновился пока были в фоне)
            player?.let { it.setPlaylist(playlistStore.load(), it.currentIndex().coerceAtLeast(0)) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            unhookPlayerObservers()
            service = null
            player = null
            syncControlsEnabled()
        }
    }

    fun reloadPlaylistAndPlay(startIndex: Int?) {
        val p = player ?: return
        val list = playlistStore.load()
        val enabled = list.isNotEmpty()
        setControlsEnabled(enabled)

        if (!enabled) {
            p.setPlaylist(list)
            resetUiToDefaults()
            return
        }

        if (startIndex != null && startIndex in list.indices) {
            p.setPlaylist(list, startIndex)
            p.pause()
            p.seekTo(0L) // остаёмся на паузе
        } else {
            p.setPlaylist(list, p.currentIndex().coerceAtLeast(0))
        }
    }

    fun attach(root: View = activity.findViewById(android.R.id.content)): PlayerUiBinder {
        playlistStore = PlaylistStore(app)

        tvTitle = root.findViewById(R.id.playerTitle)
        btnPrev = root.findViewById(R.id.playerPrev)
        btnPlayPause = root.findViewById(R.id.playerPlayPause)
        btnNext = root.findViewById(R.id.playerNext)
        seek = root.findViewById(R.id.playerSeek)
        tvNow = root.findViewById(R.id.playerNow)
        tvDur = root.findViewById(R.id.playerDur)
        cross = root.findViewById(R.id.mixCrossfader)

        if (btnPlayPause == null || seek == null) return this

        setControlsEnabled(false) // до подключения к сервису

        btnPrev?.setOnClickListener {
            player?.let {
                if (playlistStore.load().isNotEmpty()) {
                    it.previous(); it.pause(); it.seekTo(0L)
                }
            }
        }
        btnNext?.setOnClickListener {
            player?.let {
                if (playlistStore.load().isNotEmpty()) {
                    it.next(); it.pause(); it.seekTo(0L)
                }
            }
        }

        btnPlayPause?.setOnClickListener { player?.toggle() }
        btnPlayPause?.setOnLongClickListener { player?.pause(); player?.seekTo(0L); true }

        seek?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) player?.seekTo(p.toLong()) }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        cross?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val a = (progress / 100f).coerceIn(0f, 1f)
                MixerState.alpha01.postValue(a)
                player?.setVolume01(a) // временно: громкость плеера
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        MixerState.alpha01.observe(activity, Observer { a ->
            cross?.progress = ((a ?: 0f) * 100).toInt()
            player?.setVolume01(a ?: 0f)
        })

        // Подключаемся к сервису
        val i = Intent(app, CastService::class.java)
        app.bindService(i, conn, Context.BIND_AUTO_CREATE)

        uiHandler.post(ticker)
        activity.lifecycle.addObserver(this)
        return this
    }

    fun release() {
        uiHandler.removeCallbacksAndMessages(null)
        unhookPlayerObservers()
        try { app.unbindService(conn) } catch (_: Throwable) {}
        activity.lifecycle.removeObserver(this)
    }

    override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_DESTROY) release()
    }

    private fun hookPlayerObservers() {
        val p = player ?: return
        p.title.observe(activity, Observer { title ->
            tvTitle?.text = if (title.isNullOrBlank()) activity.getString(R.string.cast_player_title) else title
        })
        p.isPlaying.observe(activity, Observer { playing ->
            btnPlayPause?.setImageResource(if (playing == true) R.drawable.ic_pause_24 else R.drawable.ic_play_24)
        })
        p.positionMs.observe(activity, Observer { pos ->
            seek?.progress = (pos ?: 0L).toInt()
            tvNow?.text = formatMs(pos ?: 0L)
        })
        p.durationMs.observe(activity, Observer { dur ->
            val d = (dur ?: 0L).coerceAtLeast(0L)
            seek?.max = d.toInt()
            tvDur?.text = formatMs(d)
        })
    }

    private fun unhookPlayerObservers() {
        // Жизненный цикл LiveData привязан к Activity, поэтому отписка не критична,
        // но на всякий случай очистим локальные ссылки — и контролы заблокируем.
        setControlsEnabled(false)
    }

    private fun syncControlsEnabled() {
        setControlsEnabled(player != null && playlistStore.load().isNotEmpty())
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
