package com.buligin.vishnucast

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import com.buligin.vishnucast.audio.PlayerCore
import com.buligin.vishnucast.audio.PlaylistStore
import com.buligin.vishnucast.ui.PlaylistActivity

class MainActivity : AppCompatActivity() {

    // --- существующие поля/инициализация 1.7 оставляем без изменений ---
    // ... (твои поля, permissions, старт/стоп сервиса и т.п.)

    // --- Player ---
    private var playerUiBinder: com.buligin.vishnucast.ui.PlayerUiBinder? = null

    private lateinit var player: PlayerCore
    private lateinit var playlistStore: PlaylistStore

    private lateinit var btnPrev: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var tvNow: TextView
    private lateinit var tvDur: TextView
    private lateinit var tvTitle: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            player.tick()
            uiHandler.postDelayed(this, 500L)
        }
    }

    private val openPlaylist = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val startIndex = res.data?.getIntExtra("startIndex", -1) ?: -1
            if (startIndex >= 0) {
                val list = playlistStore.load()
                player.setPlaylist(list, startIndex)
                player.play()
            } else {
                // просто обновили список без запуска
                val list = playlistStore.load()
                player.setPlaylist(list)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // --- существующий код инициализации из 1.7 ---
        // ...

        playlistStore = PlaylistStore(this)
        player = PlayerCore(this).also {
            it.setPlaylist(playlistStore.load())
        }

        bindPlayerViews()
        observePlayer()

        uiHandler.post(ticker)
        playerUiBinder = com.buligin.vishnucast.ui.PlayerUiBinder(this).attach()



    }

    private fun bindPlayerViews() {
        tvTitle = findViewById(R.id.playerTitle)
        btnPrev = findViewById(R.id.playerPrev)
        btnPlayPause = findViewById(R.id.playerPlayPause)
        btnNext = findViewById(R.id.playerNext)
        seek = findViewById(R.id.playerSeek)
        tvNow = findViewById(R.id.playerNow)
        tvDur = findViewById(R.id.playerDur)

        btnPrev.setOnClickListener { player.previous() }
        btnNext.setOnClickListener { player.next() }
        btnPlayPause.setOnClickListener { player.toggle() }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) player.seekTo(progress.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun observePlayer() {
        player.title.observe(this, Observer { title ->
            tvTitle.text = if (title.isNullOrBlank()) getString(R.string.cast_player_title) else title
        })
        player.isPlaying.observe(this, Observer { playing ->
            btnPlayPause.setImageResource(if (playing == true) R.drawable.ic_pause_24 else R.drawable.ic_play_24)
        })
        player.positionMs.observe(this, Observer { pos ->
            seek.progress = pos?.toInt() ?: 0
            tvNow.text = formatMs(pos ?: 0L)
        })
        player.durationMs.observe(this, Observer { dur ->
            val d = (dur ?: 0L).coerceAtLeast(0L)
            seek.max = d.toInt()
            tvDur.text = formatMs(d)
        })
    }

    private fun formatMs(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(ticker)
        // Не освобождаем плеер, если он должен жить в сервисе — на Этапе B перенесём в CastService.
        player.release()

        playerUiBinder?.release()
        playerUiBinder = null

    }

    // --- Меню ---
    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_open_playlist -> {
                openPlaylist.launch(Intent(this, PlaylistActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
