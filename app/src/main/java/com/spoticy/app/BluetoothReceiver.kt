package com.spoticy.app

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Receiver v6.1
 * Gère connexion/déconnexion Bluetooth -> Play/Pause
 */
class BluetoothReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BluetoothReceiver"
        
        // Actions envoyées au service
        const val ACTION_CMD_PLAY = "com.spoticy.app.CMD_PLAY"
        const val ACTION_CMD_PAUSE = "com.spoticy.app.CMD_PAUSE"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED,
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED" -> {
                // Vérification A2DP
                if (intent.action == "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED") {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    if (state != BluetoothProfile.STATE_CONNECTED) return
                }
                
                Log.d(TAG, "Bluetooth connecté -> Envoi commande PLAY")
                sendCommand(context, ACTION_CMD_PLAY)
            }
            
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.d(TAG, "Bluetooth déconnecté -> Envoi commande PAUSE")
                sendCommand(context, ACTION_CMD_PAUSE)
            }
        }
    }

    private fun sendCommand(context: Context, action: String) {
        try {
            val serviceIntent = Intent(context, MusicPlayerService::class.java).apply {
                this.action = action
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur envoi commande: ${e.message}")
        }
    }
}
