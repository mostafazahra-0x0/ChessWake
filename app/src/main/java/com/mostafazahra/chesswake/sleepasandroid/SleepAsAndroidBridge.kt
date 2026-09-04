package com.mostafazahra.chesswake.sleepasandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.AlarmClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.mostafazahra.chesswake.R
import com.mostafazahra.chesswake.alarm.AlarmActivity
import com.mostafazahra.chesswake.alarm.AlarmNotifications
import com.mostafazahra.chesswake.alarm.AlarmSoundService
import com.mostafazahra.chesswake.alarm.RingingAlarmState
import com.mostafazahra.chesswake.alarm.domain.Alarm
import com.mostafazahra.chesswake.settings.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional integration with Sleep as Android.
 *
 * Two directions, both off by default:
 *
 *  **ChessWake → Sleep as Android.** When an alarm is solved we broadcast
 *  `STOP_SLEEP_TRACK`, so sleep tracking ends at the moment you actually got up.
 *  When an alarm has mirroring enabled we also create a matching alarm in Sleep as
 *  Android via the standard [AlarmClock.ACTION_SET_ALARM] intent.
 *
 *  **Sleep as Android → ChessWake.** This is the interesting direction: Sleep as
 *  Android watches your sleep cycles and fires its alarm during a light phase.
 *  By listening for `ALARM_ALERT_START_AUTO` we can put the chess puzzle on screen
 *  at *that* moment instead of at a fixed time — smart wake-up timing plus a
 *  wakefulness check.
 *
 * Implementation notes taken from the Sleep as Android intent API docs:
 *  - every action intent must carry the `com.urbandroid.sleep` package, because
 *    implicit broadcasts have not worked since Android 8;
 *  - events emitted *by* Sleep are suffixed `_AUTO` and can only be received by
 *    runtime-registered receivers, never by manifest-declared ones;
 *  - a runtime receiver registered on Android 14+ must state whether it is
 *    exported, hence [ContextCompat.RECEIVER_EXPORTED].
 *
 * Everything here is best-effort and wrapped in `runCatching`: if Sleep as Android
 * is absent, disabled, or changes its API, ChessWake must keep working normally.
 */
@Singleton
class SleepAsAndroidBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val notifications: AlarmNotifications,
    private val ringingAlarmState: RingingAlarmState,
) {

    private var eventReceiver: BroadcastReceiver? = null

    /**
     * True when Sleep as Android is installed on this device.
     *
     * Note this only works because the manifest declares a `<queries>` entry for
     * the package: since Android 11, package visibility is filtered and an
     * undeclared lookup throws `NameNotFoundException` even when the app exists.
     */
    val isInstalled: Boolean
        get() = runCatching { context.packageManager.getPackageInfo(SLEEP_PACKAGE, 0) }.isSuccess

    /** True when the user has switched the integration on *and* the app is present. */
    suspend fun isEnabled(): Boolean = isInstalled && settingsRepository.settings.first().sleepAsAndroidEnabled

    // ---------------------------------------------------------------------
    // ChessWake -> Sleep as Android
    // ---------------------------------------------------------------------

    /**
     * Tells Sleep as Android that the user is up, so sleep tracking stops.
     *
     * Called the moment a puzzle is solved.
     */
    fun notifyWokeUp() {
        send(STOP_SLEEP_TRACK)
    }

    /** Starts sleep tracking, for the "going to bed" shortcut in settings. */
    fun startSleepTracking() {
        send(START_SLEEP_TRACK)
    }

    fun stopSleepTracking() {
        send(STOP_SLEEP_TRACK)
    }

    /** Asks Sleep as Android to snooze its own alarm, if it has one ringing. */
    fun snooze(minutes: Int) {
        send(ALARM_SNOOZE) { putExtra(EXTRA_SNOOZE_TIME, minutes.coerceIn(1, 60)) }
    }

    /**
     * Creates or refreshes a matching alarm inside Sleep as Android.
     *
     * Uses the platform [AlarmClock.ACTION_SET_ALARM] contract, which Sleep as
     * Android documents as its "adding alarm" entry point, so no private API is
     * involved. Only runs when mirroring is enabled both globally and per alarm.
     */
    suspend fun mirrorAlarm(alarm: Alarm) {
        if (!alarm.mirrorToSleepAsAndroid || !isEnabled()) return
        runCatching {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                setPackage(SLEEP_PACKAGE)
                putExtra(AlarmClock.EXTRA_HOUR, alarm.hour)
                putExtra(AlarmClock.EXTRA_MINUTES, alarm.minute)
                putExtra(
                    AlarmClock.EXTRA_MESSAGE,
                    alarm.label.ifBlank { "ChessWake" },
                )
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                putExtra(AlarmClock.EXTRA_VIBRATE, alarm.vibrate)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // SET_ALARM is an activity contract; broadcasting it does nothing.
            // `EXTRA_SKIP_UI` keeps it invisible to the user.
            context.startActivity(intent)
        }.onFailure { Log.w(TAG, "Could not mirror alarm to Sleep as Android", it) }
    }

    /** Enables or disables the mirrored alarm inside Sleep as Android, by label. */
    fun setMirroredAlarmEnabled(label: String, enabled: Boolean) {
        send(ALARM_STATE_CHANGE) {
            putExtra(EXTRA_ALARM_LABEL, label)
            putExtra(EXTRA_ALARM_ENABLED, enabled)
        }
    }

    // ---------------------------------------------------------------------
    // Sleep as Android -> ChessWake
    // ---------------------------------------------------------------------

    /**
     * Starts listening for Sleep as Android's alarm events.
     *
     * @param onAlarmTriggered called on the main thread when Sleep as Android
     *   decides it is time to wake up; the caller shows the puzzle.
     * @param onAlarmDismissed called when Sleep as Android's own alarm is dismissed.
     */
    fun startListening(
        onAlarmTriggered: () -> Unit,
        onAlarmDismissed: () -> Unit = {},
    ) {
        if (eventReceiver != null) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    ALARM_ALERT_START -> {
                        Log.i(TAG, "Sleep as Android alarm triggered; showing the puzzle")
                        notifications.cancelAllRinging()
                        onAlarmTriggered()
                    }

                    ALARM_ALERT_DISMISS -> {
                        Log.i(TAG, "Sleep as Android alarm dismissed")
                        onAlarmDismissed()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(ALARM_ALERT_START)
            addAction(ALARM_ALERT_DISMISS)
        }

        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                // The broadcasts come from another app, so the receiver must be exported.
                ContextCompat.RECEIVER_EXPORTED,
            )
            eventReceiver = receiver
        }.onFailure { Log.w(TAG, "Could not register the Sleep as Android receiver", it) }
    }

    /**
     * Starts listening and opens the ChessWake puzzle when Sleep as Android says it
     * is time to wake up.
     *
     * This is the interesting half of the integration: Sleep as Android picks the
     * moment inside its smart wake-up window, and ChessWake supplies the puzzle
     * that makes sure the user is actually awake. The alarm is tracked under
     * [EXTERNAL_ALARM_ID], which is not a row in the database — snoozing it
     * therefore falls back to re-booking ChessWake's own alarms, which is harmless.
     */
    fun startPuzzleListening() {
        if (!isInstalled) return
        startListening(
            onAlarmTriggered = { showPuzzleForExternalAlarm() },
            onAlarmDismissed = { AlarmSoundService.stop(context) },
        )
    }

    private fun showPuzzleForExternalAlarm() {
        val now = java.time.LocalTime.now()
        val timeLabel = "%02d:%02d".format(now.hour, now.minute)
        ringingAlarmState.beginRinging(
            id = EXTERNAL_ALARM_ID,
            alarmLabel = context.getString(R.string.alarm_default_label),
            timeLabel = timeLabel,
            puzzleRequired = true,
        )
        runCatching {
            context.startActivity(
                AlarmActivity.intent(
                    context = context,
                    alarmId = EXTERNAL_ALARM_ID,
                    label = "",
                    timeLabel = timeLabel,
                    requirePuzzle = true,
                ),
            )
        }.onFailure { Log.w(TAG, "Could not open the puzzle from a Sleep as Android event", it) }
    }

    /** Stops listening. Safe to call when nothing is registered. */
    fun stopListening() {
        val receiver = eventReceiver ?: return
        runCatching { context.unregisterReceiver(receiver) }
        eventReceiver = null
    }

    // ---------------------------------------------------------------------

    /** Sends one action broadcast to Sleep as Android, if it is installed. */
    private fun send(action: String, extras: (Intent.() -> Unit)? = null) {
        if (!isInstalled) return
        runCatching {
            val intent = Intent(action).apply {
                // Mandatory: without the package this is an implicit broadcast,
                // which Android 8+ silently drops.
                setPackage(SLEEP_PACKAGE)
                extras?.invoke(this)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(intent)
        }.onFailure { Log.w(TAG, "Broadcast $action to Sleep as Android failed", it) }
    }

    companion object {
        private const val TAG = "SleepAsAndroid"

        const val SLEEP_PACKAGE = "com.urbandroid.sleep"

        /**
         * Alarm id used when the wake-up comes from Sleep as Android rather than
         * from a ChessWake row. Negative, so it can never collide with a Room id.
         */
        const val EXTERNAL_ALARM_ID = -2L

        // Actions ChessWake sends.
        const val START_SLEEP_TRACK = "com.urbandroid.sleep.alarmclock.START_SLEEP_TRACK"
        const val STOP_SLEEP_TRACK = "com.urbandroid.sleep.alarmclock.STOP_SLEEP_TRACK"
        const val ALARM_SNOOZE = "com.urbandroid.sleep.alarmclock.ALARM_SNOOZE"
        const val ALARM_DISMISS_CAPTCHA = "com.urbandroid.sleep.alarmclock.ALARM_DISMISS_CAPTCHA"
        const val ALARM_STATE_CHANGE = "com.urbandroid.sleep.alarmclock.ALARM_STATE_CHANGE"

        // Extras for the actions above.
        const val EXTRA_SNOOZE_TIME = "extra_snooze_time"
        const val EXTRA_ALARM_LABEL = "alarm_label"
        const val EXTRA_ALARM_ENABLED = "alarm_enabled"

        /**
         * Events Sleep as Android emits.
         *
         * The `_AUTO` suffix is required: the docs state that direct receipt of the
         * unsuffixed actions stopped working in 2018 due to Android's implicit
         * broadcast restrictions.
         */
        const val ALARM_ALERT_START = "com.urbandroid.sleep.alarmclock.ALARM_ALERT_START_AUTO"
        const val ALARM_ALERT_DISMISS = "com.urbandroid.sleep.alarmclock.ALARM_ALERT_DISMISS_AUTO"

        /** Content provider exposing Sleep as Android's alarm list. */
        const val ALARM_PROVIDER_URI = "content://com.urbandroid.sleep.alarmclock/alarm"
    }
}
