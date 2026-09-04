package com.mostafazahra.chesswake.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.mostafazahra.chesswake.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service that makes the noise until the puzzle is solved.
 *
 * Why a service and not just the activity
 * ---------------------------------------
 * The alarm must keep ringing while the screen is off, while the user has
 * backgrounded the puzzle to check the time, and while the lock screen is up.
 * A `mediaPlayback` foreground service is the only construct that reliably does
 * all three.
 *
 * Robustness choices worth knowing about:
 *  - Audio attributes are `USAGE_ALARM`, so the tone uses the alarm stream and
 *    ignores the media volume the user left turned down.
 *  - If the configured sound URI cannot be played, the service falls back to the
 *    bundled tone rather than ringing silently.
 *  - A crescendo ramps the volume up over a configurable window, which is much
 *    less brutal than full volume at the first millisecond.
 *  - A hard stop after [MAX_RING_MILLIS] prevents a forgotten alarm from draining
 *    the battery for a whole day.
 */
@AndroidEntryPoint
class AlarmSoundService : Service() {

    @Inject lateinit var notifications: AlarmNotifications

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val handler = Handler(Looper.getMainLooper())
    private var crescendoRunnable: Runnable? = null
    private var safetyStopRunnable: Runnable? = null

    private var targetVolume = 1.0f
    private var crescendoSeconds = DEFAULT_CRESCENDO_SECONDS
    private var crescendoStartedAt = 0L

    override fun onCreate() {
        super.onCreate()
        notifications.createChannels()
        vibrator = resolveVibrator()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(AlarmContract.EXTRA_LABEL).orEmpty()
        val timeLabel = intent?.getStringExtra(AlarmContract.EXTRA_TIME_LABEL).orEmpty()
        val soundUri = intent?.getStringExtra(AlarmContract.EXTRA_SOUND_URI)
        val shouldVibrate = intent?.getBooleanExtra(AlarmContract.EXTRA_VIBRATE, true) ?: true
        targetVolume = (intent?.getFloatExtra(AlarmContract.EXTRA_VOLUME, 1.0f) ?: 1.0f)
            .coerceIn(0.05f, 1.0f)
        crescendoSeconds = (intent?.getIntExtra(EXTRA_CRESCENDO_SECONDS, DEFAULT_CRESCENDO_SECONDS)
            ?: DEFAULT_CRESCENDO_SECONDS).coerceIn(0, 120)

        // Go foreground *before* doing any slow work: Android 12+ kills services
        // that take too long to call startForeground.
        promoteToForeground(label, timeLabel)
        acquireWakeLock()
        startSound(soundUri)
        if (shouldVibrate) startVibration()
        scheduleSafetyStop()

        return START_NOT_STICKY
    }

    private fun promoteToForeground(label: String, timeLabel: String) {
        val notification = notifications.buildServiceNotification(label, timeLabel)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    AlarmNotifications.SERVICE_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(AlarmNotifications.SERVICE_NOTIFICATION_ID, notification)
            }
        }.onFailure {
            Log.e(TAG, "startForeground failed; the alarm may be silenced", it)
        }
    }

    private fun startSound(requestedUri: String?) {
        if (mediaPlayer != null) return

        val uri = requestedUri?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: Uri.parse("android.resource://$packageName/${R.raw.alarm_tone}")

        mediaPlayer = createPlayer(uri) ?: createPlayer(bundledToneUri())
        crescendoStartedAt = System.currentTimeMillis()
        applyCrescendoVolume()
        startCrescendo()
    }

    private fun createPlayer(uri: Uri): MediaPlayer? = runCatching {
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            setDataSource(this@AlarmSoundService, uri)
            isLooping = true
            setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            prepare()
            setVolume(targetVolume, targetVolume)
            start()
        }
    }.onFailure {
        Log.e(TAG, "Could not play $uri", it)
    }.getOrNull()

    private fun bundledToneUri(): Uri = Uri.parse("android.resource://$packageName/${R.raw.alarm_tone}")

    /**
     * Ramps the volume from [CRESCENDO_START_FRACTION] to the user's target over
     * [crescendoSeconds]. With the crescendo disabled it jumps straight to target.
     */
    private fun startCrescendo() {
        crescendoRunnable?.let(handler::removeCallbacks)
        if (crescendoSeconds <= 0) {
            mediaPlayer?.setVolume(targetVolume, targetVolume)
            return
        }
        val runnable = object : Runnable {
            override fun run() {
                applyCrescendoVolume()
                if (System.currentTimeMillis() - crescendoStartedAt < crescendoSeconds * 1000L) {
                    handler.postDelayed(this, CRESCENDO_STEP_MILLIS)
                }
            }
        }
        crescendoRunnable = runnable
        handler.postDelayed(runnable, CRESCENDO_STEP_MILLIS)
    }

    private fun applyCrescendoVolume() {
        val player = mediaPlayer ?: return
        val progress = if (crescendoSeconds <= 0) {
            1f
        } else {
            val elapsed = System.currentTimeMillis() - crescendoStartedAt
            (elapsed.toFloat() / (crescendoSeconds * 1000f)).coerceIn(0f, 1f)
        }
        val volume = targetVolume * (CRESCENDO_START_FRACTION + (1f - CRESCENDO_START_FRACTION) * progress)
        val safeVolume = volume.coerceIn(0f, 1f)
        runCatching { player.setVolume(safeVolume, safeVolume) }
    }

    private fun startVibration() {
        val device = vibrator ?: return
        runCatching {
            val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, VIBRATION_REPEAT_INDEX)
            // vibrate(VibrationEffect, AudioAttributes) exists from API 21, so no
            // version branch is needed at minSdk 26.
            device.vibrate(
                effect,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }.onFailure { Log.w(TAG, "Vibration unavailable", it) }
    }

    /**
     * `VibratorManager` is the API 31+ way to reach the default vibrator; the
     * older `VIBRATOR_SERVICE` lookup is still correct below that.
     */
    private fun resolveVibrator(): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()?.takeIf { runCatching { it.hasVibrator() }.getOrDefault(false) }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = runCatching {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(MAX_RING_MILLIS)
            }
        }.getOrNull()
    }

    /** Stops the noise after [MAX_RING_MILLIS] so a forgotten alarm cannot run flat. */
    private fun scheduleSafetyStop() {
        safetyStopRunnable?.let(handler::removeCallbacks)
        val runnable = Runnable {
            Log.w(TAG, "Alarm hit the maximum ring time; stopping the sound")
            stopEverything()
        }
        safetyStopRunnable = runnable
        handler.postDelayed(runnable, MAX_RING_MILLIS)
    }

    /**
     * Keeps ringing even when the user swipes the app away from recents.
     *
     * Deliberately *not* calling `stopSelf()` here: an alarm that stops because the
     * user swiped the task away is exactly the failure mode ChessWake exists to fix.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "Task removed while ringing; continuing to play")
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun stopEverything() {
        crescendoRunnable?.let(handler::removeCallbacks)
        safetyStopRunnable?.let(handler::removeCallbacks)
        crescendoRunnable = null
        safetyStopRunnable = null

        mediaPlayer?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
        }
        mediaPlayer = null

        runCatching { vibrator?.cancel() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null

        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AlarmSoundService"
        private const val WAKE_LOCK_TAG = "chesswake:ringing"

        const val EXTRA_CRESCENDO_SECONDS = "extra_crescendo_seconds"
        const val DEFAULT_CRESCENDO_SECONDS = 20

        /** Start the crescendo at 30% volume; loud enough to be heard, not a shock. */
        private const val CRESCENDO_START_FRACTION = 0.30f
        private const val CRESCENDO_STEP_MILLIS = 500L

        /** Two hours. Long enough for any lie-in, short enough not to flatten a phone. */
        private const val MAX_RING_MILLIS = 2 * 60 * 60 * 1000L

        // Wait, vibrate, wait, vibrate. The leading 0 means "start immediately".
        private val VIBRATION_PATTERN = longArrayOf(0, 900, 700, 900, 1400)
        private const val VIBRATION_REPEAT_INDEX = 0

        /** Builds the start intent, carrying the audio configuration across. */
        fun startIntent(context: Context, source: Intent?, alarmId: Long): Intent =
            Intent(context, AlarmSoundService::class.java).apply {
                putExtra(AlarmContract.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmContract.EXTRA_LABEL, source?.getStringExtra(AlarmContract.EXTRA_LABEL).orEmpty())
                putExtra(
                    AlarmContract.EXTRA_TIME_LABEL,
                    source?.getStringExtra(AlarmContract.EXTRA_TIME_LABEL).orEmpty(),
                )
                putExtra(AlarmContract.EXTRA_VIBRATE, source?.getBooleanExtra(AlarmContract.EXTRA_VIBRATE, true) ?: true)
                putExtra(AlarmContract.EXTRA_SOUND_URI, source?.getStringExtra(AlarmContract.EXTRA_SOUND_URI))
                putExtra(AlarmContract.EXTRA_VOLUME, source?.getFloatExtra(AlarmContract.EXTRA_VOLUME, 1f) ?: 1f)
                putExtra(EXTRA_CRESCENDO_SECONDS, DEFAULT_CRESCENDO_SECONDS)
            }

        /** Stops the ringing sound. Called when the puzzle is solved or the alarm is snoozed. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AlarmSoundService::class.java)) }
        }

        /** True when the alarm stream would be audible, used to warn in the UI. */
        fun alarmStreamVolumeFraction(context: Context): Float = runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (max <= 0) 1f else audioManager.getStreamVolume(AudioManager.STREAM_ALARM) / max.toFloat()
        }.getOrDefault(1f)
    }
}
