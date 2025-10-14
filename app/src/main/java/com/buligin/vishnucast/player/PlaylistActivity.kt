package com.buligin.vishnucast.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.buligin.vishnucast.R
import com.buligin.vishnucast.player.PlaylistItem
import com.buligin.vishnucast.player.PlaylistStore
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import java.util.Collections
import android.view.View





class PlaylistActivity : AppCompatActivity() {

    private lateinit var list: MutableList<PlaylistItem>
    private lateinit var store: PlaylistStore
    private lateinit var adapter: PlaylistAdapter

    private val pickAudio = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uris.first(), Intent.FLAG_GRANT_READ_URI_PERMISSION
        ) // Android требует хотя бы один явный take в Activity
        list = store.addUris(uris).toMutableList()
        adapter.submit(list)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.cast_playlist_title)

        store = PlaylistStore(this)
        list = store.load()

        val rv = findViewById<RecyclerView>(R.id.playlistRecycler)

        adapter = PlaylistAdapter(
            onRemove = { id ->
                list = store.remove(id).toMutableList()
                adapter.submit(list)
            },
            onClick = { index ->
                val data = android.content.Intent().putExtra("startIndex", index)
                setResult(android.app.Activity.RESULT_OK, data)
                finish()
            }
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        adapter.submit(list)

        val fab = findViewById<FloatingActionButton>(R.id.playlistAddFab)
        fab.setOnClickListener {
            pickAudio.launch(arrayOf(
                "audio/*",
                "audio/mpeg", "audio/mp3", "audio/x-mpeg",
                "audio/aac",
                "audio/flac",
                "audio/wav", "audio/x-wav",
                "audio/ogg", "application/ogg",
                "audio/webm",
                "audio/mp4",
                "audio/3gpp"
            ))
        }

        // Drag & drop reorder
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.LEFT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition

                adapter.move(from, to)
                list = adapter.current().toMutableList()


                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val idx = viewHolder.bindingAdapterPosition

                val id = adapter.current()[idx].id

                list = store.remove(id).toMutableList()
                adapter.submit(list)
                Snackbar.make(findViewById<View>(R.id.playlistRoot), R.string.cast_removed, Snackbar.LENGTH_SHORT).show()
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)

                // сохранить порядок
                store.reorder(adapter.current().map { it.id })
            }
        })
        touchHelper.attachToRecyclerView(rv)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_playlist, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> { finish(); return true }
            R.id.action_clear -> {
                list.clear()
                store.save(list)
                adapter.submit(list)
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
