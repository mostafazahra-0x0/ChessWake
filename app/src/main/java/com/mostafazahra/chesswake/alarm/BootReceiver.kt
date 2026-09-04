package com.mostafazahra.chesswake.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mostafazahra.chesswake.alarm.data.AlarmRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Re-books every enabled alarm after something invalidates the system's schedule.
 *
 * `AlarmManager` bookings do not survive a reboot, and they can drift when the
 * user changes the clock or flies into another timezone. Without this receiver an
 * alarm clock app silently stops working after every restart — the single most
 * common reason "my alarm never rang" bug reports exist.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var alarmRepository: AlarmRepository

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val count = alarmRepository.rescheduleAll()
                Log.i(TAG, "Rescheduled $count alarms after $action")
            } catch (throwable: Throwable) {
                Log.e(TAG, "Failed to reschedule alarms after $action", throwable)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"

        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            // Some OEMs deliver their own "package restarted" broadcast.
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
