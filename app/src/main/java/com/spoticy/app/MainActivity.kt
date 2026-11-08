package com.spoticy.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

@SuppressLint("NotifyDataSetChanged")
class MainActivity : AppCompatActivity() {

    private val prefsName = "MusicPrefs"
    private val keyFolderURI = "folderUri"
    private val keyLastMusicUri = "lastMusicUri"

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
    private var isSeeking = false
    private var autoPlayOnServiceConnect = true

    private lateinit var musicAdapter: MusicAdapter
    private val handler = Handler(Looper.getMainLooper())

    private var musicService: MusicPlayerService? = null
    private var isServiceBound = false
    private var serviceRequested = false

    private var permissionRequestInFlight = false
    private var pendingAfterPermissions: (() -> Unit)? = null

    private val seekBarUpdater = object : Runnable {
        override fun run() {
            val service = musicService
            if (service != null && !isSeeking) {
                seekBar.progress = service.position()
                currentTime.text = formatTime(service.position())
            }
            if (service?.isPlaying() == true) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionRequestInFlight = false
        val action = pendingAfterPermissions
        pendingAfterPermissions = null
        action?.invoke()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicPlayerService.MusicBinder
            musicService = binder.getService()
            isServiceBound = true
            serviceRequested = true
            attachServiceCallbacks()
            musicService?.setPlaylist(musicList, currentPosition, autoPlayOnServiceConnect)
            autoPlayOnServiceConnect = false
            syncUiFromService()
            setControlsEnabled(musicList.isNotEmpty())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            detachServiceCallbacks()
            musicService = null
            isServiceBound = false
            serviceRequested = false
            setControlsEnabled(false)
        }
    }

    private val pickFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult

            autoPlayOnServiceConnect = false
            val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
            prefs.edit { putString(keyFolderURI, uri.toString()) }

            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                Toast.makeText(this, R.string.folder_access_error, Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }

            loadMusicFromUri(uri)
            trackTitle.text = getString(
                if (musicList.isEmpty()) R.string.no_music_found else R.string.select_track
            )

            withRuntimePermissions {
                if (musicList.isNotEmpty()) startAndBindService()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

        musicAdapter = MusicAdapter(musicList) { position ->
            val music = musicAdapter.getMusicAt(position)
            val realPosition = musicList.indexOf(music)
            if (realPosition >= 0 && isServiceBound) {
                musicService?.playIndex(realPosition)
            }
        }
        musicRecyclerView.layoutManager = LinearLayoutManager(this)
        musicRecyclerView.adapter = musicAdapter

        searchBar.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                musicAdapter.filter(s.toString())
            }

            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) {
            }

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) {
            }
        })

        setControlsEnabled(false)

        selectFolderBtn.setOnClickListener {
            pickFolderLauncher.launch(null)
        }

        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val lastMusicUri = prefs.getString(keyLastMusicUri, null)
        autoPlayOnServiceConnect = savedInstanceState == null && lastMusicUri != null
        val folderUriString = prefs.getString(keyFolderURI, null)

        if (folderUriString != null) {
            try {
                val folderUri = folderUriString.toUri()
                contentResolver.takePersistableUriPermission(
                    folderUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                loadMusicFromUri(folderUri)
                withRuntimePermissions {
                    if (musicList.isNotEmpty()) startAndBindService()
                }
            } catch (e: Exception) {
                Toast.makeText(this, R.string.folder_access_error, Toast.LENGTH_LONG).show()
                prefs.edit { remove(keyFolderURI) }
                trackTitle.text = getString(R.string.no_folder_selected)
                setControlsEnabled(false)
            }
        } else {
            Toast.makeText(this, R.string.select_folder_first, Toast.LENGTH_LONG).show()
            trackTitle.text = getString(R.string.no_folder_selected)
            setControlsEnabled(false)
        }

        playPauseBtn.setOnClickListener {
            if (isServiceBound) musicService?.toggle()
        }
        nextBtn.setOnClickListener {
            if (isServiceBound) musicService?.next()
        }
        prevBtn.setOnClickListener {
            if (isServiceBound) musicService?.previous()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                if (fromUser) {
                    currentTime.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                musicService?.seekTo(seekBar?.progress ?: 0)
            }
        })
    }

    private fun withRuntimePermissions(block: () -> Unit) {
        val requested = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (requested.isEmpty()) {
            block()
            return
        }

        if (permissionRequestInFlight) {
            pendingAfterPermissions = block
            return
        }

        permissionRequestInFlight = true
        pendingAfterPermissions = block
        permissionLauncher.launch(requested.toTypedArray())
    }

    private fun startAndBindService() {
        if (isServiceBound || serviceRequested) return
        serviceRequested = true

        val intent = Intent(this, MusicPlayerService::class.java)
        try {
            startForegroundService(intent)
            if (!bindService(intent, serviceConnection, BIND_AUTO_CREATE)) {
                serviceRequested = false
                stopService(intent)
                Toast.makeText(this, R.string.service_start_error, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            serviceRequested = false
            Toast.makeText(this, R.string.service_start_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun loadMusicFromUri(uri: Uri) {
        musicList.clear()
        val pickedDir = DocumentFile.fromTreeUri(this, uri)
        pickedDir?.listFiles()?.forEach { file ->
            if (file.isFile) {
                val name = file.name ?: return@forEach
                val extension = name.substringAfterLast('.', "").lowercase()
                if (extension == "mp3" || extension == "wav") {
                    musicList.add(Music(name, file.uri, file.lastModified()))
                }
            }
        }
        musicList.sortByDescending { it.lastModified }

        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val lastMusicUri = prefs.getString(keyLastMusicUri, null)
        currentPosition = lastMusicUri
            ?.let { saved -> musicList.indexOfFirst { it.uri.toString() == saved } }
            ?.takeIf { it >= 0 }
            ?: 0

        musicAdapter.refresh()
        if (musicList.isNotEmpty()) {
            musicRecyclerView.scrollToPosition(currentPosition)
        } else {
            resetTrackUi()
        }

        if (isServiceBound) {
            musicService?.setPlaylist(musicList, currentPosition, autoPlay = false)
        }
        setControlsEnabled(musicList.isNotEmpty() && isServiceBound)
    }

    private fun attachServiceCallbacks() {
        musicService?.onTrackChanged = { music ->
            if (music != null) {
                trackTitle.text = music.title
                val durationMs = musicService?.duration() ?: 0
                seekBar.max = durationMs
                duration.text = formatTime(durationMs)
                val positionMs = musicService?.position() ?: 0
                seekBar.progress = positionMs
                currentTime.text = formatTime(positionMs)
                musicAdapter.playingUri = music.uri
                currentPosition = musicList.indexOfFirst { it.uri == music.uri }
                    .takeIf { it >= 0 } ?: currentPosition
            } else {
                resetTrackUi()
            }
            musicAdapter.notifyDataSetChanged()
        }

        musicService?.onPlaybackStateChanged = { playing ->
            setPlayPauseIcon(playing)
            if (playing) {
                startSeekBarUpdates()
            } else {
                stopSeekBarUpdates()
                musicService?.let {
                    seekBar.progress = it.position()
                    currentTime.text = formatTime(it.position())
                }
            }
        }

        musicService?.onPlaybackError = { message ->
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun detachServiceCallbacks() {
        musicService?.onTrackChanged = null
        musicService?.onPlaybackStateChanged = null
        musicService?.onPlaybackError = null
    }

    private fun syncUiFromService() {
        val service = musicService ?: return
        val music = service.currentMusic
        if (music != null) {
            trackTitle.text = music.title
            val durationMs = service.duration()
            seekBar.max = durationMs
            duration.text = formatTime(durationMs)
            val positionMs = service.position()
            seekBar.progress = positionMs
            currentTime.text = formatTime(positionMs)
            musicAdapter.playingUri = music.uri
            currentPosition = musicList.indexOfFirst { it.uri == music.uri }
                .takeIf { it >= 0 } ?: currentPosition
        } else {
            resetTrackUi()
        }

        setPlayPauseIcon(service.isPlaying())
        if (service.isPlaying()) startSeekBarUpdates() else stopSeekBarUpdates()
        musicAdapter.notifyDataSetChanged()
    }

    private fun resetTrackUi() {
        trackTitle.text = getString(
            if (musicList.isEmpty()) R.string.no_music_found else R.string.no_track_selected
        )
        seekBar.max = 0
        seekBar.progress = 0
        currentTime.text = formatTime(0)
        duration.text = formatTime(0)
        musicAdapter.playingUri = null
        setPlayPauseIcon(false)
        musicAdapter.notifyDataSetChanged()
    }

    private fun setPlayPauseIcon(playing: Boolean) {
        playPauseBtn.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
    }

    private fun startSeekBarUpdates() {
        handler.removeCallbacks(seekBarUpdater)
        handler.post(seekBarUpdater)
    }

    private fun stopSeekBarUpdates() {
        handler.removeCallbacks(seekBarUpdater)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        playPauseBtn.isEnabled = enabled
        nextBtn.isEnabled = enabled
        prevBtn.isEnabled = enabled
        seekBar.isEnabled = enabled
    }

    override fun onStart() {
        super.onStart()
        attachServiceCallbacks()
        syncUiFromService()
    }

    override fun onStop() {
        detachServiceCallbacks()
        stopSeekBarUpdates()
        super.onStop()
    }

    override fun onDestroy() {
        detachServiceCallbacks()
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: IllegalArgumentException) {
                // Le service s'est déjà déconnecté.
            }
            isServiceBound = false
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
