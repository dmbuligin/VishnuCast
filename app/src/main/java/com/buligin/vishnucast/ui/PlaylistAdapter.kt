package com.buligin.vishnucast.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.buligin.vishnucast.R
import com.buligin.vishnucast.audio.PlaylistItem

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.playlist_item, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = data[position]
        holder.title.text = item.title.ifBlank { holder.itemView.context.getString(R.string.cast_unknown_track) }
        holder.remove.setOnClickListener { onRemove(item.id) }


        holder.itemView.setOnClickListener { onClick(position) }




    }

    override fun getItemCount(): Int = data.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.playlistTitle)
        val remove: ImageButton = v.findViewById(R.id.playlistRemove)
    }
}
