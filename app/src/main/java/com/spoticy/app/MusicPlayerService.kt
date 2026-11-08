package com.spoticy.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.media.app.NotificationCompat.MediaStyle

class MusicPlayerService : Service() {

    companion object {
        const val CHANNEL_ID = "spoticy_channel"
        const val NOTIFICATION_ID = 1

        const val ACTION_CMD_PLAY = "com.spoticy.app.CMD_PLAY"
        const val ACTION_CMD_PAUSE = "com.spoticy.app.CMD_PAUSE"
        const val ACTION_CMD_TOGGLE = "com.spoticy.app.CMD_TOGGLE"
        const val ACTION_CMD_NEXT = "com.spoticy.app.CMD_NEXT"
        const val ACTION_CMD_PREV = "com.spoticy.app.CMD_PREV"
        const val ACTION_CMD_STOP = "com.spoticy.app.CMD_STOP"

        private const val PREFS_NAME = "MusicPrefs"
        private const val KEY_LAST_MUSIC_URI = "lastMusicUri"
        private const val KEY_POSITION = "currentPosition"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val binder = MusicBinder()

    var mediaPlayer: MediaPlayer? = null
        private set
    var currentMusic: Music? = null
        private set

    private var playlist: List<Music> = emptyList()
    private var currentIndex = -1

    var onTrackChanged: ((Music?) -> Unit)? = null
    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null
    var onPlaybackError: ((String) -> Unit)? = null

    private lateinit var audioManager: AudioManager
    private lateinit var audioFocusRequest: AudioFocusRequest
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var prefs: SharedPreferences
    private var wasPlayingBeforeFocusLoss = false
    private var isForeground = false

    inner class MusicBinder : Binder() {
        fun getService(): MusicPlayerService = this@MusicPlayerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupAudioFocus()
        createNotificationChannel()
        setupMediaSession()
        ensureForeground()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "SpoticySession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
                override fun onStop() = stop()
            })
            setSessionActivity(contentPendingIntent())
            isActive = true
        }
    }

    private fun setupAudioFocus() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (wasPlayingBeforeFocusLoss) {
                            wasPlayingBeforeFocusLoss = false
                            resume()
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        if (isPlaying()) {
                            wasPlayingBeforeFocusLoss = true
                            pause()
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        wasPlayingBeforeFocusLoss = false
                        pause()
                    }
                }
            }
            .build()
    }

    private fun requestAudioFocus(): Boolean =
        audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun abandonAudioFocus() {
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CMD_PLAY -> resume()
            ACTION_CMD_PAUSE -> pause()
            ACTION_CMD_TOGGLE -> toggle()
            ACTION_CMD_NEXT -> next()
            ACTION_CMD_PREV -> previous()
            ACTION_CMD_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    /**
     * Remplace la file de lecture. Si le morceau en cours est toujours présent,
     * la lecture continue sans être interrompue (rotation, retour au premier plan).
     */
    fun setPlaylist(musics: List<Music>, startIndex: Int, autoPlay: Boolean) {
        playlist = musics.toList()

        if (playlist.isEmpty()) {
            clearPlayback()
            updateNotification(false)
            return
        }

        val existing = currentMusic
        if (existing != null) {
            val index = playlist.indexOfFirst { it.uri == existing.uri }
            if (index >= 0) {
                currentIndex = index
                mediaSession.isActive = true
                onTrackChanged?.invoke(existing)
                onPlaybackStateChanged?.invoke(isPlaying())
                updateMediaSession()
                updateNotification(isPlaying())
                return
            }
        }

        currentIndex = startIndex.coerceIn(0, playlist.lastIndex)
        if (autoPlay) {
            playIndex(currentIndex)
        } else {
            clearPlayback(keepPlaylist = true)
            mediaSession.isActive = true
            updateNotification(false)
        }
    }

    fun playIndex(index: Int) {
        if (playlist.isEmpty()) return

        val safeIndex = index.coerceIn(0, playlist.lastIndex)
        currentIndex = safeIndex
        val music = playlist[safeIndex]
        currentMusic = music
        mediaSession.isActive = true

        try {
            mediaPlayer?.release()
        } catch (e: Exception) {
            // Déjà libéré.
        }
        mediaPlayer = null

        val player = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(this@MusicPlayerService, music.uri)
                prepare()
            }
        } catch (e: Exception) {
            null
        }

        if (player == null) {
            currentMusic = null
            onPlaybackError?.invoke(getString(R.string.playback_error, music.title))
            onTrackChanged?.invoke(null)
            onPlaybackStateChanged?.invoke(false)
            updateNotification(false)
            return
        }

        mediaPlayer = player
        player.setOnCompletionListener { next() }
        player.setOnErrorListener { _, _, _ ->
            onPlaybackError?.invoke(getString(R.string.playback_error, music.title))
            clearPlayback(keepPlaylist = true)
            updateNotification(false)
            true
        }

        if (requestAudioFocus()) {
            try {
                player.start()
            } catch (e: Exception) {
                onPlaybackError?.invoke(getString(R.string.playback_error, music.title))
                clearPlayback(keepPlaylist = true)
                updateNotification(false)
                return
            }
        }

        savePlaybackState(music)
        updateMediaSession()
        ensureForeground()
        onTrackChanged?.invoke(music)
        onPlaybackStateChanged?.invoke(isPlaying())
    }

    fun pause() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (e: Exception) {
            // Le lecteur est déjà dans un état invalide : on ignore.
        }
        updateMediaSession()
        updateNotification(false)
        onPlaybackStateChanged?.invoke(false)
    }

    fun resume() {
        val player = mediaPlayer
        if (player == null) {
            val index = when {
                currentIndex >= 0 -> currentIndex
                playlist.isNotEmpty() -> 0
                else -> -1
            }
            if (index >= 0) {
                playIndex(index)
            }
            return
        }
        if (isPlaying()) return
        if (!requestAudioFocus()) return

        try {
            player.start()
        } catch (e: Exception) {
            onPlaybackError?.invoke(
                getString(R.string.playback_error, currentMusic?.title ?: "")
            )
            clearPlayback(keepPlaylist = true)
            updateNotification(false)
            return
        }

        mediaSession.isActive = true
        updateMediaSession()
        ensureForeground()
        onPlaybackStateChanged?.invoke(true)
    }

    fun toggle() {
        if (isPlaying()) pause() else resume()
    }

    fun next() {
        if (playlist.isEmpty()) return
        val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % playlist.size
        playIndex(nextIndex)
    }

    fun previous() {
        if (playlist.isEmpty()) return
        val previousIndex = if (currentIndex <= 0) playlist.lastIndex else currentIndex - 1
        playIndex(previousIndex)
    }

    fun seekTo(positionMs: Int) {
        val player = mediaPlayer ?: return
        val max = duration()
        try {
            player.seekTo(if (max > 0) positionMs.coerceIn(0, max) else 0)
            updateMediaSession()
        } catch (e: Exception) {
            // Ignore un seek sur un lecteur invalide.
        }
    }

    fun position(): Int = try {
        mediaPlayer?.currentPosition ?: 0
    } catch (e: Exception) {
        0
    }

    fun duration(): Int = try {
        mediaPlayer?.duration?.coerceAtLeast(0) ?: 0
    } catch (e: Exception) {
        0
    }

    fun isPlaying(): Boolean = try {
        mediaPlayer?.isPlaying == true
    } catch (e: Exception) {
        false
    }

    fun stop() {
        clearPlayback(keepPlaylist = true)
        abandonAudioFocus()
        mediaSession.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForeground = false
        stopSelf()
    }

    private fun clearPlayback(keepPlaylist: Boolean = false) {
        try {
            mediaPlayer?.release()
        } catch (e: Exception) {
            // Rien à faire : le lecteur est déjà libéré.
        }
        mediaPlayer = null
        currentMusic = null
        currentIndex = -1
        if (!keepPlaylist) {
            playlist = emptyList()
        }
        onTrackChanged?.invoke(null)
        onPlaybackStateChanged?.invoke(false)
        updateMediaSession()
    }

    private fun savePlaybackState(music: Music) {
        prefs.edit {
            putString(KEY_LAST_MUSIC_URI, music.uri.toString())
            putInt(KEY_POSITION, currentIndex)
        }
    }

    private fun updateMediaSession() {
        val music = currentMusic
        val metadata = MediaMetadataCompat.Builder().apply {
            if (music != null) {
                putString(MediaMetadataCompat.METADATA_KEY_TITLE, music.title)
                putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration().toLong())
            }
        }.build()
        mediaSession.setMetadata(metadata)

        val state = when {
            isPlaying() -> PlaybackStateCompat.STATE_PLAYING
            mediaPlayer != null -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_NONE
        }
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, position().toLong(), if (isPlaying()) 1.0f else 0.0f)
                .build()
        )
    }

    private fun ensureForeground() {
        val notification = buildNotification(isPlaying())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForeground = true
    }

    private fun updateNotification(isPlaying: Boolean) {
        if (!isForeground) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(isPlaying))
    }

    private fun buildNotification(playing: Boolean): Notification {
        val title = currentMusic?.title ?: getString(R.string.app_name)
        val text = when {
            currentMusic == null -> getString(R.string.notification_ready)
            playing -> getString(R.string.notification_playing)
            else -> getString(R.string.notification_paused)
        }
        val toggleIcon = if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow
        val toggleTitle = getString(
            if (playing) R.string.notification_pause else R.string.notification_play
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentPendingIntent())
            .setDeleteIntent(servicePendingIntent(ACTION_CMD_STOP, 5))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing)
            .addAction(
                R.drawable.ic_skip_previous,
                getString(R.string.notification_previous),
                servicePendingIntent(ACTION_CMD_PREV, 2)
            )
            .addAction(
                toggleIcon,
                toggleTitle,
                servicePendingIntent(ACTION_CMD_TOGGLE, 1)
            )
            .addAction(
                R.drawable.ic_skip_next,
                getString(R.string.notification_next),
                servicePendingIntent(ACTION_CMD_NEXT, 3)
            )
            .addAction(
                R.drawable.ic_stop,
                getString(R.string.notification_stop),
                servicePendingIntent(ACTION_CMD_STOP, 4)
            )
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun contentPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, MusicPlayerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // La musique continue quand l'utilisateur quitte la tâche, sauf si elle est en pause.
        if (!isPlaying()) {
            stop()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (currentMusic == null) {
            stop()
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isRunning = false
        try {
            mediaPlayer?.release()
        } catch (e: Exception) {
            // Déjà libéré.
        }
        mediaPlayer = null
        abandonAudioFocus()
        mediaSession.release()
        super.onDestroy()
    }
}
