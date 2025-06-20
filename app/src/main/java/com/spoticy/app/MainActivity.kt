package com.spoticy.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.*
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.net.toUri
import androidx.core.content.edit
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.MediaMetadataCompat
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText

class MainActivity : AppCompatActivity() {

    private val prefsName  = "MusicPrefs"
    private val keyFolderURI = "folderUri"
    private val keyPosition = "currentPosition"
    private val keyLastMusicUri = "lastMusicUri" // Ajoute cette ligne

    private lateinit var selectFolderBtn: Button
    private lateinit var musicRecyclerView: RecyclerView
    private lateinit var playPauseBtn: ImageButton
    private lateinit var nextBtn: ImageButton
    private lateinit var prevBtn: ImageButton
    private lateinit var trackTitle: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var currentTime: TextView
    private lateinit var duration: TextView
    private lateinit var searchBar: EditText

    private var musicList = mutableListOf<Music>()
    private var currentPosition = 0

    private lateinit var musicAdapter: MusicAdapter

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var mediaSession: MediaSessionCompat

    private var musicService: MusicPlayerService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicPlayerService.MusicBinder
            musicService = binder.getService()
            isServiceBound = true
            // Active les boutons ici
            setControlsEnabled(true)
            // Relance la musique si besoin
            if (musicList.isNotEmpty()) {
                playMusic(currentPosition, auto = true)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
            musicService = null
            setControlsEnabled(false)
        }
    }

    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
            prefs.edit().putString(keyFolderURI, uri.toString()).apply()
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            loadMusicFromUri(uri)
            if (isServiceBound) setControlsEnabled(true)
            trackTitle.text = "Sélectionnez un morceau"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialisation des vues
        selectFolderBtn = findViewById(R.id.selectFolderBtn)
        musicRecyclerView = findViewById(R.id.musicRecyclerView)
        playPauseBtn = findViewById(R.id.playPauseBtn)
        nextBtn = findViewById(R.id.nextBtn)
        prevBtn = findViewById(R.id.prevBtn)
        trackTitle = findViewById(R.id.trackTitle)
        seekBar = findViewById(R.id.seekBar)
        currentTime = findViewById(R.id.currentTime)
        duration = findViewById(R.id.duration)
        searchBar = findViewById(R.id.searchBar)

        // Adapter et RecyclerView
        musicAdapter = MusicAdapter(musicList) { position ->
            val music = musicAdapter.getMusicAt(position)
            val realPosition = musicList.indexOf(music)
            playMusic(realPosition)
        }
        musicRecyclerView.layoutManager = LinearLayoutManager(this)
        musicRecyclerView.adapter = musicAdapter

        searchBar.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                musicAdapter.filter(s.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Désactive les contrôles au démarrage
        setControlsEnabled(false)

        // MediaSession : doit être initialisé AVANT toute lecture
        mediaSession = MediaSessionCompat(this, "SpoticySession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { playPauseBtn.performClick() }
                override fun onPause() { playPauseBtn.performClick() }
                override fun onSkipToNext() { nextBtn.performClick() }
                override fun onSkipToPrevious() { prevBtn.performClick() }
            })
            setMediaButtonReceiver(
                PendingIntent.getBroadcast(
                    this@MainActivity,
                    0,
                    Intent(Intent.ACTION_MEDIA_BUTTON),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            isActive = true
        }
        updatePlaybackState(false)

        // Bouton de sélection de dossier
        selectFolderBtn.setOnClickListener {
            pickFolderLauncher.launch(null)
        }

        // Gestion du dossier et de la position
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val folderUriString = prefs.getString(keyFolderURI, null)
        currentPosition = prefs.getInt(keyPosition, 0)

        if (folderUriString != null) {
            try {
                val folderUri = folderUriString.toUri()
                contentResolver.takePersistableUriPermission(
                    folderUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                loadMusicFromUri(folderUri)
            } catch (e: Exception) {
                Toast.makeText(this, "Erreur d'accès au dossier. Veuillez le sélectionner à nouveau.", Toast.LENGTH_LONG).show()
                prefs.edit { remove(keyFolderURI) }
                setControlsEnabled(false)
                trackTitle.text = "Aucun dossier sélectionné"
            }
        } else {
            Toast.makeText(this, "Veuillez sélectionner un dossier pour commencer.", Toast.LENGTH_LONG).show()
            setControlsEnabled(false)
            trackTitle.text = "Aucun dossier sélectionné"
        }

        // Service de musique
        val intent = Intent(this, MusicPlayerService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)

        // Boutons de contrôle
        playPauseBtn.setOnClickListener {
            if (!isServiceBound) return@setOnClickListener
            val player = musicService?.mediaPlayer
            if (player?.isPlaying == true) {
                musicService?.pause()
                playPauseBtn.setImageResource(android.R.drawable.ic_media_play)
                updatePlaybackState(false)
            } else {
                musicService?.resume()
                playPauseBtn.setImageResource(android.R.drawable.ic_media_pause)
                updatePlaybackState(true)
            }
        }
        nextBtn.setOnClickListener {
            if (!isServiceBound) return@setOnClickListener
            if (musicList.isNotEmpty()) {
                val next = (currentPosition + 1) % musicList.size
                playMusic(next)
            }
        }
        prevBtn.setOnClickListener {
            if (!isServiceBound) return@setOnClickListener
            if (musicList.isNotEmpty()) {
                val prev = if (currentPosition - 1 < 0) musicList.size - 1 else currentPosition - 1
                playMusic(prev)
            }
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && isServiceBound) {
                    musicService?.mediaPlayer?.seekTo(progress)
                    currentTime.text = formatTime(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setControlsEnabled(enabled: Boolean) {
        playPauseBtn.isEnabled = enabled
        nextBtn.isEnabled = enabled
        prevBtn.isEnabled = enabled
        seekBar.isEnabled = enabled
    }

    @SuppressLint("SetTextI18n", "NotifyDataSetChanged")
    private fun loadMusicFromUri(uri: Uri) {
        musicList.clear()
        val pickedDir = DocumentFile.fromTreeUri(this, uri)
        pickedDir?.listFiles()?.forEach { file ->
            if (file.isFile && (file.name?.endsWith(".mp3") == true || file.name?.endsWith(".wav") == true)) {
                musicList.add(Music(file.name ?: "Unknown", file.uri, file.lastModified()))
            }
        }
        musicList.sortByDescending { it.lastModified }

        // Récupère l'URI de la dernière musique jouée
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val lastMusicUri = prefs.getString(keyLastMusicUri, null)
        currentPosition = if (lastMusicUri != null) {
            musicList.indexOfFirst { it.uri.toString() == lastMusicUri }.takeIf { it >= 0 } ?: 0
        } else 0

        musicAdapter.playingPosition = currentPosition
        musicAdapter.notifyDataSetChanged()
        musicRecyclerView.scrollToPosition(currentPosition) // Ajout : scroll automatique
        if (musicList.isNotEmpty()) {
            if (currentPosition >= musicList.size) currentPosition = 0
            // On ne lance pas la musique ici, on attend que le service soit prêt
        } else {
            trackTitle.text = "No music found"
            playPauseBtn.setImageResource(android.R.drawable.ic_media_play)
            seekBar.progress = 0
            seekBar.max = 0
            currentTime.text = formatTime(0)
            duration.text = formatTime(0)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun playMusic(position: Int, auto: Boolean = false) {
        if (!isServiceBound) return
        try {
            val music = musicList[position]
            currentPosition = position
            val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
            prefs.edit {
                putInt(keyPosition, currentPosition)
                putString(keyLastMusicUri, music.uri.toString()) // Sauvegarde l'URI
            }
            musicService?.play(music.uri)
            trackTitle.text = music.title
            playPauseBtn.setImageResource(android.R.drawable.ic_media_pause)
            seekBar.max = musicService?.mediaPlayer?.duration ?: 0
            duration.text = formatTime(musicService?.mediaPlayer?.duration ?: 0)
            currentTime.text = formatTime(0)
            seekBar.progress = 0
            updateSeekBar()
            musicAdapter.playingPosition = position
            musicAdapter.notifyDataSetChanged()
            mediaSession.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, music.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Unknown Artist")
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Spoticy")
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, musicService?.mediaPlayer?.duration?.toLong() ?: 0L)
                    .build()
            )
            updatePlaybackState(true)
            musicService?.mediaPlayer?.setOnCompletionListener {
                nextBtn.performClick()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur lors de la lecture : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateSeekBar() {
        musicService?.mediaPlayer?.let { player ->
            seekBar.progress = player.currentPosition
            currentTime.text = formatTime(player.currentPosition)
            if (player.isPlaying) {
                handler.postDelayed({ updateSeekBar() }, 1000)
            }
        }
    }

    private fun formatTime(milliseconds: Int): String {
        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun updatePlaybackState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val position = musicService?.mediaPlayer?.currentPosition?.toLong() ?: 0L
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(state, position, 1.0f)
                .build()
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        musicService?.stop()
        handler.removeCallbacksAndMessages(null)
        mediaSession.release()
    }
}