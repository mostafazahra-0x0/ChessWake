package com.mostafazahra.chesswake.alarm

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mostafazahra.chesswake.alarm.ui.AlarmEvent
import com.mostafazahra.chesswake.alarm.ui.AlarmScreen
import com.mostafazahra.chesswake.alarm.ui.AlarmViewModel
import com.mostafazahra.chesswake.settings.domain.ThemeMode
import com.mostafazahra.chesswake.ui.theme.ChessWakeTheme
import com.mostafazahra.chesswake.ui.theme.LocalAlarmMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The full-screen alarm: shown over the lock screen when an alarm fires.
 *
 * Deliberately *not* the app's main activity, and deliberately *not* themed by the
 * user's settings. A dark, high-contrast, non-scrolling screen is the right thing
 * at 6am whatever the daytime theme is, and the puzzle — not the keyguard — is
 * what stands between the user and a silent phone.
 *
 * Lifecycle notes:
 *  - The back button is swallowed. A hardware/gesture back must never silence an
 *    alarm; only solving, snoozing or the system's own timeout may.
 *  - Sound is stopped here, on the way out, because the ViewModel has no Context.
 *  - A second alarm firing while this screen is up rebuilds it around the new
 *    alarm instead of leaving a stale board on screen.
 */
@AndroidEntryPoint
class AlarmActivity : ComponentActivity() {

    /** Hilt field injection: the activity needs notifications to cancel them on exit. */
    @Inject
    lateinit var notifications: AlarmNotifications

    private val viewModel: AlarmViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Dark, transparent system bars: the alarm owns the whole screen.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        showOverLockScreen()

        // Swallow back so it cannot be used to silence the alarm.
        onBackPressedDispatcher.addCallback(this) { /* intentionally empty */ }

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(Unit) {
                viewModel.events.collect { event ->
                    when (event) {
                        AlarmEvent.Dismissed, AlarmEvent.Snoozed -> {
                            stopRinging()
                            finishAndRemoveTask()
                        }
                    }
                }
            }

            ChessWakeTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                CompositionLocalProvider(LocalAlarmMode provides true) {
                    AlarmScreen(
                        state = state,
                        onSquareTap = viewModel::onSquareTap,
                        onDismiss = viewModel::dismiss,
                        onSnooze = viewModel::snooze,
                        onShowHint = viewModel::showHint,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val incoming = intent.getLongExtra(AlarmContract.EXTRA_ALARM_ID, RingingAlarmState.NO_ALARM)
        val current = viewModel.uiState.value.alarmId
        if (incoming != RingingAlarmState.NO_ALARM && incoming != current) {
            // A different alarm is now ringing: rebuild around it rather than
            // leaving the previous puzzle on screen.
            recreate()
        }
    }

    override fun onDestroy() {
        // If the system tears this activity down (low memory, a forced stop of the
        // task), the sound service must not be left looping forever. The service
        // also has its own two-hour safety stop; this is the polite path.
        if (isFinishing) stopRinging()
        super.onDestroy()
    }

    /**
     * Makes the activity appear over the keyguard and wake the display.
     *
     * API 27+ has first-class setters; API 26 needs the legacy window flags. The
     * keyguard is *not* dismissed — on a secure device the phone stays locked and
     * the puzzle is the only way to reach the dismiss button.
     */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    private fun stopRinging() {
        AlarmSoundService.stop(this)
        runCatching { notifications.cancelAllRinging() }
    }

    companion object {
        /**
         * Builds the launch intent used both by [AlarmReceiver] and by the
         * full-screen notification.
         *
         * `NEW_TASK` is required because the receiver has no activity context, and
         * `CLEAR_TOP` keeps a single alarm screen alive when several triggers
         * arrive in quick succession.
         */
        fun intent(
            context: Context,
            alarmId: Long,
            label: String,
            timeLabel: String,
            requirePuzzle: Boolean,
        ): Intent = Intent(context, AlarmActivity::class.java).apply {
            action = AlarmContract.ACTION_ALARM
            putExtra(AlarmContract.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmContract.EXTRA_LABEL, label)
            putExtra(AlarmContract.EXTRA_TIME_LABEL, timeLabel)
            putExtra(AlarmContract.EXTRA_REQUIRE_PUZZLE, requirePuzzle)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
        }
    }
}
