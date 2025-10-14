package com.buligin.vishnucast.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.buligin.vishnucast.R
import com.buligin.vishnucast.player.PlaylistItem
import java.util.Collections

class PlaylistAdapter(
    private val onRemove: (id: String) -> Unit,
    private val onClick: (index: Int) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.VH>() {

    private val data = mutableListOf<PlaylistItem>()

    fun submit(list: List<PlaylistItem>) {
        data.clear()
        data.addAll(list.sortedBy { it.sort })
        notifyDataSetChanged()
    }

    fun move(from: Int, to: Int) {
        if (from in data.indices && to in data.indices) {
            Collections.swap(data, from, to)
            notifyItemMoved(from, to)
        }
    }

    fun current(): List<PlaylistItem> = data

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.playlist_item, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = data[position]
        holder.title.text = item.title.ifBlank {
            holder.itemView.context.getString(R.string.cast_unknown_track)
        }
        holder.remove.setOnClickListener { onRemove(item.id) }

        // ВАЖНО: использовать актуальную позицию
        holder.itemView.setOnClickListener {
            val idx = holder.bindingAdapterPosition
            if (idx != RecyclerView.NO_POSITION) onClick(idx)
        }
    }

    override fun getItemCount(): Int = data.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.playlistTitle)
        val remove: ImageButton = v.findViewById(R.id.playlistRemove)
    }
}
