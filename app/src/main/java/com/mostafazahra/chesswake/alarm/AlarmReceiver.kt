package com.mostafazahra.chesswake.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Receives the exact-alarm broadcast and:
 *  1. Starts the foreground service that loops the sound/vibration.
 *  2. Launches the full-screen alarm activity on top of the lock screen.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, AlarmSoundService::class.java),
        )

        val alarmIntent = Intent(context, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(alarmIntent)
    }
}
