package com.spoticy.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.content.pm.ServiceInfo

class MusicPlayerService : Service() {

    companion object {
        const val CHANNEL_ID = "spoticy_channel"
        const val NOTIFICATION_ID = 1
        
        // Commandes Bluetooth
        const val ACTION_CMD_PLAY = "com.spoticy.app.CMD_PLAY"
        const val ACTION_CMD_PAUSE = "com.spoticy.app.CMD_PAUSE"
    }

    private val binder = MusicBinder()
    var mediaPlayer: MediaPlayer? = null
    
    private lateinit var audioManager: AudioManager
    private lateinit var audioFocusRequest: AudioFocusRequest
    private var wasPlayingBeforeFocusLoss = false
    
    private var currentTitle: String = "Spoticy"
    
    // Callbacks simples pour l'UI
    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null
    var onNextTrack: (() -> Unit)? = null

    inner class MusicBinder : Binder() {
        fun getService(): MusicPlayerService = this@MusicPlayerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupAudioFocus()
        createNotificationChannel()
        
        startForeground(
            NOTIFICATION_ID,
            buildSimpleNotification("Spoticy", "Prêt à jouer"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
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
                        // Reprend la lecture si elle était en pause temporaire
                        if (wasPlayingBeforeFocusLoss) {
                            resume()
                            wasPlayingBeforeFocusLoss = false
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        // Pause temporaire (appel, etc.)
                        if (isPlaying()) {
                            wasPlayingBeforeFocusLoss = true
                            pause()
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        // Perte définitive
                        wasPlayingBeforeFocusLoss = false
                        pause()
                    }
                }
            }
            .build()
    }

    private fun requestAudioFocus(): Boolean {
        val result = audioManager.requestAudioFocus(audioFocusRequest)
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            BluetoothReceiver.ACTION_CMD_PLAY -> {
                if (mediaPlayer != null && !isPlaying()) {
                    resume()
                }
            }
            BluetoothReceiver.ACTION_CMD_PAUSE -> {
                if (isPlaying()) {
                    pause()
                }
            }
        }
        return START_NOT_STICKY
    }

    fun play(uri: Uri, title: String = "Titre inconnu", artist: String = "") {
        if (!requestAudioFocus()) return
        
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(this, uri)
        mediaPlayer?.start()
        
        currentTitle = title
        
        updateNotification(true)
        onPlaybackStateChanged?.invoke(true)
        
        mediaPlayer?.setOnCompletionListener {
            onNextTrack?.invoke()
        }
    }

    fun pause() {
        mediaPlayer?.pause()
        updateNotification(false)
        onPlaybackStateChanged?.invoke(false)
    }

    fun resume() {
        if (!requestAudioFocus()) return
        
        mediaPlayer?.start()
        updateNotification(true)
        onPlaybackStateChanged?.invoke(true)
    }

    fun stop() {
        mediaPlayer?.release()
        mediaPlayer = null
        abandonAudioFocus()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    private fun updateNotification(isPlaying: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        val text = if (isPlaying) "Lecture en cours" else "En pause"
        manager.notify(NOTIFICATION_ID, buildSimpleNotification(currentTitle, text))
    }

    private fun buildSimpleNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Spoticy Player",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification de lecture"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        abandonAudioFocus()
    }
}