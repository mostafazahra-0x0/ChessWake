package com.mostafazahra.chesswake.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.mostafazahra.chesswake.alarm.data.AlarmRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Entry point for a ringing alarm.
 *
 * Order of operations matters, and it is deliberately pessimistic about what the
 * OS will allow:
 *
 *  1. Take a short wake lock so the CPU stays up while we get the noise going.
 *  2. Start [AlarmSoundService] as a foreground service. Receiving an exact alarm
 *     is one of the few cases where Android 12+ permits starting a foreground
 *     service from the background, so this is done first and synchronously.
 *  3. Post the full-screen-intent notification. This is the *supported* way to
 *     show an activity over the lock screen from Android 10 onwards.
 *  4. Attempt a direct `startActivity` as well. It is rejected on stock Android 10+
 *     but still works on many OEM builds and on the notification-less path, and
 *     when it is rejected step 3 covers us. Doing both is the reason this alarm
 *     wakes the screen on as many devices as possible.
 *  5. In the background (`goAsync`), book the next occurrence and switch off
 *     one-shot alarms.
 */
@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var alarmRepository: AlarmRepository
    @Inject lateinit var notifications: AlarmNotifications
    @Inject lateinit var ringingState: RingingAlarmState
    @Inject lateinit var scheduler: AlarmScheduler

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmContract.ACTION_SNOOZE_NOW -> handleSnooze(context, intent)
            else -> handleAlarm(context, intent)
        }
    }

    private fun handleAlarm(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(AlarmContract.EXTRA_ALARM_ID, RingingAlarmState.NO_ALARM)
        val label = intent.getStringExtra(AlarmContract.EXTRA_LABEL).orEmpty()
        val timeLabel = intent.getStringExtra(AlarmContract.EXTRA_TIME_LABEL).orEmpty()
        val requirePuzzle = intent.getBooleanExtra(AlarmContract.EXTRA_REQUIRE_PUZZLE, true)
        val snoozeMinutes = intent.getIntExtra(
            AlarmContract.EXTRA_SNOOZE_MINUTES,
            AlarmContract.FALLBACK_SNOOZE_MINUTES,
        )
        val maxSnoozes = intent.getIntExtra(
            AlarmContract.EXTRA_MAX_SNOOZES,
            AlarmContract.FALLBACK_MAX_SNOOZES,
        )
        val isSnooze = intent.getBooleanExtra(AlarmContract.EXTRA_IS_SNOOZE, false)

        val wakeLock = acquireWakeLock(context)

        // 1. Remember what is ringing, so the service and the screen agree.
        ringingState.beginRinging(alarmId, label, timeLabel, requirePuzzle)
        if (isSnooze) ringingState.noteSnooze()

        // 2. Start the noise. This must not depend on the database or the network.
        runCatching {
            ContextCompat.startForegroundService(
                context,
                AlarmSoundService.startIntent(context, intent, alarmId),
            )
        }.onFailure { Log.e(TAG, "Could not start the alarm sound service", it) }

        // 3. Full-screen-intent notification: the supported path onto a locked screen.
        notifications.showRinging(
            alarmId = alarmId,
            label = label,
            timeLabel = timeLabel,
            requirePuzzle = requirePuzzle,
            snoozeAvailable = ringingState.canSnooze(maxSnoozes),
            snoozeMinutes = snoozeMinutes,
        )

        // 4. Best-effort direct launch. Blocked on stock Android 10+, harmless to try.
        runCatching {
            context.startActivity(
                AlarmActivity.intent(context, alarmId, label, timeLabel, requirePuzzle),
            )
        }.onFailure { Log.w(TAG, "Direct startActivity rejected; relying on the full-screen intent", it) }

        // 5. Book the next occurrence in the background, then release the wake lock.
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                if (alarmId != RingingAlarmState.NO_ALARM) {
                    alarmRepository.onAlarmFired(alarmId)
                }
            } catch (throwable: Throwable) {
                Log.e(TAG, "Failed to reschedule after firing", throwable)
            } finally {
                pendingResult.finish()
                releaseWakeLock(wakeLock)
            }
        }
    }

    /**
     * Snooze pressed from the ringing notification.
     *
     * Stops the noise immediately, then books the same alarm a few minutes out.
     * Snoozing from here does not solve the puzzle — that is the point of the
     * snooze limit in settings.
     */
    private fun handleSnooze(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(AlarmContract.EXTRA_ALARM_ID, RingingAlarmState.NO_ALARM)
        val minutes = intent.getIntExtra(
            AlarmContract.EXTRA_SNOOZE_MINUTES,
            AlarmContract.FALLBACK_SNOOZE_MINUTES,
        )
        val wakeLock = acquireWakeLock(context)

        AlarmSoundService.stop(context)
        notifications.cancelRinging(alarmId)
        ringingState.noteSnooze()

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                alarmRepository.snooze(alarmId, minutes)
            } catch (throwable: Throwable) {
                Log.e(TAG, "Failed to snooze alarm $alarmId", throwable)
            } finally {
                pendingResult.finish()
                releaseWakeLock(wakeLock)
            }
        }
    }

    /**
     * Holds the CPU awake just long enough to start the service and the activity.
     *
     * Failures are swallowed: a wake lock is an optimisation here, not a
     * requirement, and `acquire` can throw if the lock has already been released.
     */
    private fun acquireWakeLock(context: Context): PowerManager.WakeLock? = runCatching {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MILLIS)
        }
    }.getOrNull()

    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
        runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
    }

    private companion object {
        const val TAG = "AlarmReceiver"
        const val WAKE_LOCK_TAG = "chesswake:alarm-trigger"
        const val WAKE_LOCK_TIMEOUT_MILLIS = 15_000L
    }
}
