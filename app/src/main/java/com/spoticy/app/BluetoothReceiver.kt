package com.spoticy.app

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Met la lecture en pause quand un périphérique audio Bluetooth se déconnecte
 * et la reprend quand il se reconnecte.
 */
class BluetoothReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BluetoothReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (!MusicPlayerService.isRunning) return

        when (intent.action) {
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "Périphérique audio connecté -> PLAY")
                        sendCommand(context, MusicPlayerService.ACTION_CMD_PLAY)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Périphérique audio déconnecté -> PAUSE")
                        sendCommand(context, MusicPlayerService.ACTION_CMD_PAUSE)
                    }
                }
            }
        }
    }

    private fun sendCommand(context: Context, action: String) {
        try {
            val serviceIntent = Intent(context, MusicPlayerService::class.java).apply {
                this.action = action
            }
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur envoi commande : ${e.message}")
        }
    }
}
