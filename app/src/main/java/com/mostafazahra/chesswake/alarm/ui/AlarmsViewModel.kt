package com.mostafazahra.chesswake.alarm.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mostafazahra.chesswake.alarm.data.AlarmRepository
import com.mostafazahra.chesswake.alarm.domain.Alarm
import com.mostafazahra.chesswake.alarm.domain.AlarmTimes
import com.mostafazahra.chesswake.R
import com.mostafazahra.chesswake.settings.data.SettingsRepository
import com.mostafazahra.chesswake.settings.domain.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row in the alarm list. */
data class AlarmSummary(
    val alarm: Alarm,
    /** `in 7 h 12 min`, recomputed every minute while the list is visible. */
    val countdown: String,
    /** `Mate in one · level 2`, or a note that no puzzle is required. */
    val puzzleLine: String,
)

/** System permissions an alarm app depends on, checked every time the list resumes. */
data class AlarmPermissions(
    val exactAlarmsAllowed: Boolean = true,
    val notificationsEnabled: Boolean = true,
)

data class AlarmsUiState(
    val loading: Boolean = true,
    val alarms: List<AlarmSummary> = emptyList(),
    val nextRingLabel: String? = null,
    val permissions: AlarmPermissions = AlarmPermissions(),
    val showReliabilityTip: Boolean = false,
)

/**
 * Backs the alarm list.
 *
 * The list is a pure projection of the database plus two things that are *not*
 * flows: the countdown, which is recomputed from a minute ticker so it never says
 * "in 0 min" for an hour, and the system permissions, which can be revoked from
 * the settings app at any time and are therefore re-read on every resume.
 */
@HiltViewModel
class AlarmsViewModel @Inject constructor(
    private val alarmRepository: AlarmRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) : ViewModel() {

    private val permissions = MutableStateFlow(AlarmPermissions())

    val uiState: StateFlow<AlarmsUiState> = combine(
        alarmRepository.alarms,
        settingsRepository.settings,
        permissions,
        minuteTicker(),
    ) { alarms, settings, granted, now ->
        val zonedNow = ZonedDateTime.now(clock)
        val summaries = alarms.map { alarm ->
            AlarmSummary(
                alarm = alarm,
                countdown = if (alarm.enabled) {
                    AlarmTimes.relativeLabel(zonedNow, AlarmTimes.nextTrigger(alarm, zonedNow))
                } else {
                    ""
                },
                puzzleLine = puzzleLine(alarm, settings),
            )
        }
        val nextEnabled = summaries.filter { it.alarm.enabled }
            .minByOrNull { AlarmTimes.nextTriggerMillis(it.alarm, zonedNow) }

        AlarmsUiState(
            loading = false,
            alarms = summaries,
            nextRingLabel = nextEnabled?.let { summary ->
                val label = summary.alarm.label
                if (label.isBlank()) summary.countdown else "$label ${summary.countdown}"
            },
            permissions = granted,
            showReliabilityTip = !settings.reliabilityTipsDismissed && alarms.isNotEmpty(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = AlarmsUiState(),
    )

    init {
        refreshPermissions()
    }

    /** Re-reads the exact-alarm and notification permissions. Called on resume. */
    fun refreshPermissions() {
        permissions.value = AlarmPermissions(
            exactAlarmsAllowed = runCatching { alarmRepository.canScheduleExactAlarms() }.getOrDefault(true),
            notificationsEnabled = runCatching {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }.getOrDefault(true),
        )
    }

    fun setEnabled(alarmId: Long, enabled: Boolean) {
        viewModelScope.launch { runCatching { alarmRepository.setEnabled(alarmId, enabled) } }
    }

    fun delete(alarmId: Long) {
        viewModelScope.launch { runCatching { alarmRepository.delete(alarmId) } }
    }

    fun dismissReliabilityTip() {
        viewModelScope.launch { runCatching { settingsRepository.dismissReliabilityTips() } }
    }

    fun openExactAlarmSettings() {
        runCatching { alarmRepository.openExactAlarmSettings() }
    }

    fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openBatterySettings() {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                // Some OEM builds hide that screen; fall back to the app details page.
                val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(details) }
            }
    }

    /** Emits immediately, then once a minute, while something is collecting. */
    private fun minuteTicker(): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(MINUTE_MILLIS)
        }
    }

    /** Human line describing what this alarm will ask for. Localised via the app context. */
    private fun puzzleLine(alarm: Alarm, settings: AppSettings): String {
        if (!alarm.requirePuzzle) return context.getString(R.string.alarms_no_puzzle)
        return when (val theme = alarm.puzzleTheme) {
            null -> context.getString(R.string.alarms_any_puzzle, alarm.maxDifficulty)
            else -> context.getString(R.string.alarms_puzzle_line, theme.displayName, alarm.maxDifficulty)
        }
    }

    private companion object {
        const val MINUTE_MILLIS = 60_000L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
