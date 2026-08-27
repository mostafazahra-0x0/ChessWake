package com.mostafazahra.chesswake.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Foreground service that plays the alarm sound on a loop plus a vibration pattern.
 * Runs as a foreground service with mediaPlayback type so audio keeps playing when the
 * screen is off. Stopped (via [stop]) once the alarm is dismissed by a correct puzzle move.
 */
class AlarmSoundService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private lateinit var vibrator: Vibrator

    override fun onCreate() {
        super.onCreate()
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(), FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        startLoopingAlarm()
        return START_NOT_STICKY
    }

    private fun startLoopingAlarm() {
        if (mediaPlayer == null) {
            val tonePath = Uri.parse("android.resource://$packageName/${R.raw.alarm_tone}")
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@AlarmSoundService, tonePath)
                isLooping = true
                prepare()
                start()
            }
        }

        vibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 1000, 1000), 0),
        )
    }

    private fun buildNotification(): Notification {
        val dismissIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AlarmSoundService::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, AlarmActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("ChessWake Alarm")
            .setContentText("Solve the puzzle to dismiss")
            .setContentIntent(contentIntent)
            .addAction(0, "Stop", dismissIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Alarm",
            NotificationManager.IMPORTANCE_HIGH,
        )
        channel.setSound(null, null)
        channel.enableVibration(false)
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        mediaPlayer?.apply {
            stop()
            release()
        }
        mediaPlayer = null
        vibrator.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "chesswake_alarm"
        private const val NOTIFICATION_ID = 1

        /** Stops the looping sound/vibration. Called when the puzzle is solved. */
        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmSoundService::class.java))
        }
    }
}
