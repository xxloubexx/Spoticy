package com.spoticy.app

import android.annotation.SuppressLint
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MusicAdapter(
    private val originalList: List<Music>,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<MusicAdapter.MusicViewHolder>() {

    private var filteredList: List<Music> = originalList
    private var currentQuery: String = ""
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    var playingUri: Uri? = null

    inner class MusicViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val titleTextView: TextView = itemView.findViewById(R.id.title)
        val dateTextView: TextView = itemView.findViewById(R.id.musicDate)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(position)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MusicViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.music_item, parent, false)
        return MusicViewHolder(view)
    }

    override fun getItemCount(): Int = filteredList.size

    override fun onBindViewHolder(holder: MusicViewHolder, position: Int) {
        val music = filteredList[position]
        holder.titleTextView.text = music.title

        val textColor = if (music.uri == playingUri) {
            R.color.accent_green
        } else {
            R.color.text_primary
        }
        holder.titleTextView.setTextColor(
            ContextCompat.getColor(holder.itemView.context, textColor)
        )

        holder.dateTextView.text = dateFormat.format(Date(music.lastModified))
    }

    fun filter(query: String) {
        currentQuery = query
        applyFilter()
    }

    fun refresh() {
        applyFilter()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun applyFilter() {
        filteredList = if (currentQuery.isEmpty()) {
            originalList
        } else {
            originalList.filter { it.title.contains(currentQuery, ignoreCase = true) }
        }
        notifyDataSetChanged()
    }

    fun getMusicAt(position: Int): Music = filteredList[position]
}
