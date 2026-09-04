package com.mostafazahra.chesswake

import android.app.Application
import com.mostafazahra.chesswake.alarm.AlarmNotifications
import com.mostafazahra.chesswake.alarm.data.AlarmRepository
import com.mostafazahra.chesswake.di.ApplicationScope
import com.mostafazahra.chesswake.puzzle.data.PuzzleRepository
import com.mostafazahra.chesswake.settings.data.SettingsRepository
import com.mostafazahra.chesswake.sleepasandroid.SleepAsAndroidBridge
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Process-wide bootstrap.
 *
 * Everything here is idempotent and runs on the application scope, because the
 * process can be started by any of three things — the launcher icon, the alarm
 * broadcast, or a boot receiver — and each of them needs the same three guarantees:
 *
 *  1. **Notification channels exist.** Posting to a missing channel is silently
 *     dropped on Android 8+, which for an alarm app means not ringing.
 *  2. **The puzzle database is seeded** (and re-seeded when the bundled set's
 *     revision changes, so an app update can ship new puzzles).
 *  3. **Every enabled alarm is booked with the system.** `BOOT_COMPLETED` is not
 *     delivered reliably on every OEM build, and a user who force-stops the app
 *     loses all pending alarms; re-booking on every cold start is the cheap
 *     safety net that covers both.
 */
@HiltAndroidApp
class ChessWakeApplication : Application() {

    @Inject lateinit var notifications: AlarmNotifications

    @Inject lateinit var puzzleRepository: PuzzleRepository

    @Inject lateinit var alarmRepository: AlarmRepository

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var sleepBridge: SleepAsAndroidBridge

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()

        // Channels are cheap to (re)create and must exist before the first alarm.
        runCatching { notifications.createChannels() }

        applicationScope.launch { bootstrap() }
    }

    private suspend fun bootstrap() {
        runCatching { puzzleRepository.ensureSeeded() }
        runCatching { alarmRepository.rescheduleAll() }

        val sleepAsAndroidEnabled = runCatching { settingsRepository.settings.first() }
            .getOrNull()
            ?.sleepAsAndroidEnabled == true
        if (sleepAsAndroidEnabled) {
            runCatching { sleepBridge.startPuzzleListening() }
        }
    }
}
