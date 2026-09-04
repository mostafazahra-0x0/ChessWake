package com.mostafazahra.chesswake.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.mostafazahra.chesswake.MainActivity
import com.mostafazahra.chesswake.alarm.domain.Alarm
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Books and cancels alarms with [AlarmManager].
 *
 * Why `setAlarmClock` rather than `setExactAndAllowWhileIdle`
 * -----------------------------------------------------------
 * `setAlarmClock` is the API intended for alarm clocks. It is exempt from Doze
 * and App Standby, it is exempt from the per-app "one exact alarm per 9 minutes"
 * throttle that applies to `setExactAndAllowWhileIdle`, and it puts the alarm
 * icon in the status bar so the user can see an alarm is set. It also survives
 * aggressive OEM task-killers better than a plain exact alarm, because the system
 * treats the app as an active alarm clock.
 *
 * If the exact-alarm permission has been revoked the scheduler degrades to
 * `setAndAllowWhileIdle`, which may fire minutes late, and [canScheduleExactAlarms]
 * lets the UI warn the user instead of silently failing.
 */
@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * True when the OS will let us book an exact alarm.
     *
     * Always true below Android 12, where the permission does not exist.
     */
    fun canScheduleExactAlarms(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

    /** Books [alarm] to ring at [triggerAtMillis]. */
    fun schedule(alarm: Alarm, triggerAtMillis: Long) {
        val pendingIntent = pendingIntent(
            alarmId = alarm.id,
            action = AlarmContract.ACTION_ALARM,
            extras = alarm.toIntentExtras(),
        )
        if (canScheduleExactAlarms()) {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent()),
                pendingIntent,
            )
        } else {
            // Late is better than never: still wake the device, just inexactly.
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        }
    }

    /** Books a snooze for the same alarm. Uses its own request code so it cannot clobber the main booking. */
    fun scheduleSnooze(alarm: Alarm, triggerAtMillis: Long) {
        val pendingIntent = pendingIntent(
            alarmId = alarm.id,
            action = AlarmContract.ACTION_ALARM,
            extras = alarm.toIntentExtras(),
            snooze = true,
        )
        if (canScheduleExactAlarms()) {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent()),
                pendingIntent,
            )
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    /** Cancels both the main booking and any pending snooze for [alarmId]. */
    fun cancel(alarmId: Long) {
        val manager = alarmManager
        manager.cancel(pendingIntent(alarmId, AlarmContract.ACTION_ALARM))
        manager.cancel(pendingIntent(alarmId, AlarmContract.ACTION_ALARM, snooze = true))
        pendingIntent(alarmId, AlarmContract.ACTION_ALARM).cancel()
        pendingIntent(alarmId, AlarmContract.ACTION_ALARM, snooze = true).cancel()
    }

    /** The trigger time the system currently has booked for this app, if any. */
    fun nextBookedTriggerMillis(): Long? = alarmManager.nextAlarmClock?.triggerTime

    /** Sends the user to the system screen where the exact-alarm permission is granted. */
    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    /**
     * Builds the broadcast intent for one alarm.
     *
     * The `alarm://id` data URI plus a distinct request code per alarm (and a
     * second offset for snoozes) is what keeps two alarms from overwriting each
     * other's [PendingIntent], which is otherwise a silent and very confusing bug.
     */
    private fun pendingIntent(
        alarmId: Long,
        action: String,
        extras: Map<String, Any?> = emptyMap(),
        snooze: Boolean = false,
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            this.action = action
            data = Uri.parse("alarm://$alarmId${if (snooze) "/snooze" else ""}")
            putExtra(AlarmContract.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmContract.EXTRA_IS_SNOOZE, snooze)
            extras.forEach { (key, value) ->
                when (value) {
                    is String -> putExtra(key, value)
                    is Boolean -> putExtra(key, value)
                    is Int -> putExtra(key, value)
                    is Long -> putExtra(key, value)
                    is Float -> putExtra(key, value)
                    null -> { /* absent optional extra */ }
                    else -> putExtra(key, value.toString())
                }
            }
        }
        val requestCode = (if (snooze) SNOOZE_REQUEST_OFFSET else ALARM_REQUEST_OFFSET) + alarmId.toInt()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Tapping the status-bar alarm icon opens the app rather than the ringing screen. */
    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        SHOW_REQUEST_CODE,
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Copies the fields the ringing service needs into the broadcast extras.
     *
     * These are frozen when the alarm is booked, but every edit re-books with
     * [PendingIntent.FLAG_UPDATE_CURRENT], so they can never go stale.
     */
    private fun Alarm.toIntentExtras(): Map<String, Any?> = mapOf(
        AlarmContract.EXTRA_LABEL to label,
        AlarmContract.EXTRA_TIME_LABEL to timeLabel,
        AlarmContract.EXTRA_VIBRATE to vibrate,
        AlarmContract.EXTRA_SOUND_URI to soundUri,
        AlarmContract.EXTRA_VOLUME to volume,
        AlarmContract.EXTRA_REQUIRE_PUZZLE to requirePuzzle,
        AlarmContract.EXTRA_SNOOZE_MINUTES to snoozeMinutes,
        AlarmContract.EXTRA_MAX_SNOOZES to maxSnoozes,
    )

    private companion object {
        const val ALARM_REQUEST_OFFSET = 10_000
        const val SNOOZE_REQUEST_OFFSET = 20_000
        const val SHOW_REQUEST_CODE = 1
    }
}
