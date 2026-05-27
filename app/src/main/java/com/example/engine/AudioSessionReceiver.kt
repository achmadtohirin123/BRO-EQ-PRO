package com.example.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.util.Log

class AudioSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null || context == null) return

        val action = intent.action
        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
        val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)

        Log.d("AudioSessionReceiver", "Received Broadcast Action: $action, Session: $sessionId from $packageName")

        if (sessionId != AudioEffect.ERROR) {
            // Register session ID dynamically if received
            try {
                if (action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) {
                    // Open and trigger DSP on this external session if possible
                    Log.d("AudioSessionReceiver", "Targeting Master effects on session: $sessionId")
                }
            } catch (e: Exception) {
                Log.e("AudioSessionReceiver", "Error binding dynamic session: ${e.message}")
            }
        }
    }
}
