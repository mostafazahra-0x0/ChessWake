package com.mostafazahra.chesswake.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Schedules and cancels a single exact alarm using [AlarmManager.setExactAndAllowWhileIdle].
 *
 * Per the MVP plan this fires reliably even when the screen is off, the phone is in
 * Doze mode, and the app has been swiped away before the alarm time.
 */
object AlarmScheduler {

    private const val REQUEST_CODE = 1001

    /** Schedules the [AlarmReceiver] to fire at [triggerAtMillis]. */
    fun schedule(context: Context, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pendingIntent(context)
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            pendingIntent,
        )
    }

    /** Cancels the scheduled alarm. Safe to call even if none is scheduled. */
    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pendingIntent(context)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
