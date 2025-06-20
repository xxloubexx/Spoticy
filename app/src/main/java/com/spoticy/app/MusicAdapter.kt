package com.spoticy.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MusicAdapter(
    private val musicList: List<Music>, // Utilisation directe de la data class Music
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<MusicAdapter.MusicViewHolder>() {

    var playingPosition: Int = -1

    inner class MusicViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val titleTextView: TextView = itemView.findViewById(R.id.title)
        val dateTextView: TextView = itemView.findViewById(R.id.musicDate)

        init {
            itemView.setOnClickListener {
                onItemClick(adapterPosition)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MusicViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.music_item, parent, false)
        return MusicViewHolder(view)
    }

    override fun onBindViewHolder(holder: MusicViewHolder, position: Int) {
        val music = musicList[position]
        holder.titleTextView.text = music.title

        holder.titleTextView.setTextColor(
            if (position == playingPosition) Color.GREEN else Color.WHITE
        )

        val date = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
            .format(java.util.Date(music.lastModified))
        holder.dateTextView.text = date
    }

    override fun getItemCount(): Int = musicList.size
}
