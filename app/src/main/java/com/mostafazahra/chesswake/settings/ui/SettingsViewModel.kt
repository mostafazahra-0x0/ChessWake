package com.mostafazahra.chesswake.settings.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mostafazahra.chesswake.alarm.data.AlarmRepository
import com.mostafazahra.chesswake.puzzle.domain.PuzzleTheme
import com.mostafazahra.chesswake.settings.data.SettingsRepository
import com.mostafazahra.chesswake.settings.domain.AppSettings
import com.mostafazahra.chesswake.settings.domain.ThemeMode
import com.mostafazahra.chesswake.sleepasandroid.SleepAsAndroidBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Settings screen *and* the app-wide theme.
 *
 * The theme has to react to DataStore before any screen exists, so this
 * ViewModel is also fetched at the activity level (see `ChessWakeApp`) where its
 * `settings` flow drives `ChessWakeTheme`.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val sleepBridge: SleepAsAndroidBridge,
    private val alarmRepository: AlarmRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /**
     * Whether Sleep as Android is on the device.
     *
     * Read once: package installation cannot change while this ViewModel is alive
     * without the process being killed, and reading it is a binder call.
     */
    val sleepAsAndroidInstalled: Boolean = runCatching { sleepBridge.isInstalled }.getOrDefault(false)

    private val exactAlarmsAllowed = MutableStateFlow(true)

    /**
     * Whether the system will let ChessWake book exact alarms.
     *
     * Re-read on resume: Android 12+ lets the user revoke this at any time, and a
     * deferred alarm is the one failure mode this app cannot tolerate.
     */
    val exactAlarms: StateFlow<Boolean> = exactAlarmsAllowed.asStateFlow()

    fun refreshPermissions() {
        exactAlarmsAllowed.value =
            runCatching { alarmRepository.canScheduleExactAlarms() }.getOrDefault(true)
    }

    fun openExactAlarmSettings() {
        runCatching { alarmRepository.openExactAlarmSettings() }
    }

    /** Opens the battery-optimisation screen, falling back to app details. */
    fun openBatterySettings() {
        val battery = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(battery) }.onFailure {
            val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(details) }
        }
    }

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(
            scope = viewModelScope,
            // WhileSubscribed keeps the DataStore reader idle in the background but
            // survives a rotation without a flash of default values.
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = AppSettings.DEFAULT,
        )

    fun setThemeMode(mode: ThemeMode) = update { repository.setThemeMode(mode) }

    fun setDynamicColor(enabled: Boolean) = update { repository.setDynamicColor(enabled) }

    fun setVibrate(enabled: Boolean) = update { repository.setVibrate(enabled) }

    fun setVolume(volume: Float) = update { repository.setVolume(volume) }

    fun setCrescendoSeconds(seconds: Int) = update { repository.setCrescendoSeconds(seconds) }

    fun setSnoozeMinutes(minutes: Int) = update { repository.setSnoozeMinutes(minutes) }

    fun setMaxSnoozes(count: Int) = update { repository.setMaxSnoozes(count) }

    fun setDefaultMaxDifficulty(level: Int) = update { repository.setDefaultMaxDifficulty(level) }

    fun setPuzzleTheme(theme: PuzzleTheme?) = update { repository.setPuzzleTheme(theme) }

    fun setShowCoordinates(enabled: Boolean) = update { repository.setShowCoordinates(enabled) }

    fun setBoardColors(light: Long, dark: Long) = update { repository.setBoardColors(light, dark) }

    fun resetBoardColors() = update {
        repository.setBoardColors(AppSettings.DEFAULT_LIGHT_SQUARE, AppSettings.DEFAULT_DARK_SQUARE)
    }

    fun setKeepScreenOn(enabled: Boolean) = update { repository.setKeepScreenOn(enabled) }

    fun setConfirmBeforeDismiss(enabled: Boolean) = update { repository.setConfirmBeforeDismiss(enabled) }

    /**
     * Toggles the integration and starts or stops the event listener right away, so
     * enabling it does not require an app restart to take effect.
     */
    fun setSleepAsAndroidEnabled(enabled: Boolean) = update {
        repository.setSleepAsAndroidEnabled(enabled)
        if (enabled) sleepBridge.startPuzzleListening() else sleepBridge.stopListening()
    }

    fun dismissReliabilityTips() = update { repository.dismissReliabilityTips() }

    fun completeOnboarding() = update { repository.completeOnboarding() }

    fun resetAll() = update { repository.reset() }

    /** Runs a settings write off the main thread, swallowing DataStore IO errors. */
    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() } }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
