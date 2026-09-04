package com.mostafazahra.chesswake.settings.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mostafazahra.chesswake.puzzle.domain.PuzzleTheme
import com.mostafazahra.chesswake.settings.domain.AppSettings
import com.mostafazahra.chesswake.settings.domain.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Single process-wide DataStore for preferences. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "chesswake-settings",
)

/**
 * Reads and writes [AppSettings].
 *
 * Exposed as a [Flow] so that a theme change recolours the running UI without a
 * restart, and so the alarm screen can read the sound and vibration preferences
 * at the moment it actually rings.
 *
 * A corrupt or unreadable preferences file degrades to [AppSettings.DEFAULT]
 * rather than crashing: an alarm app must not fail to start because a settings
 * file went bad.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { throwable ->
            // DataStore throws IOException when the file has never been written or
            // cannot be read. Falling back to defaults keeps the app usable.
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { preferences ->
            preferences.applySettings(transform(preferences.toSettings()))
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    suspend fun setDynamicColor(enabled: Boolean) = update { it.copy(dynamicColor = enabled) }

    suspend fun setDefaultMaxDifficulty(level: Int) =
        update { it.copy(defaultMaxDifficulty = level.coerceIn(1, 5)) }

    suspend fun setPuzzleTheme(theme: PuzzleTheme?) = update { it.copy(puzzleTheme = theme) }

    suspend fun setSoundUri(uri: String?) = update { it.copy(soundUri = uri) }

    suspend fun setVibrate(enabled: Boolean) = update { it.copy(vibrate = enabled) }

    suspend fun setVolume(volume: Float) = update { it.copy(volume = volume.coerceIn(0f, 1f)) }

    suspend fun setCrescendoSeconds(seconds: Int) =
        update { it.copy(crescendoSeconds = seconds.coerceIn(0, 120)) }

    suspend fun setSnoozeMinutes(minutes: Int) =
        update { it.copy(snoozeMinutes = minutes.coerceIn(1, 30)) }

    suspend fun setMaxSnoozes(count: Int) = update { it.copy(maxSnoozes = count.coerceIn(0, 10)) }

    suspend fun setSleepAsAndroidEnabled(enabled: Boolean) =
        update { it.copy(sleepAsAndroidEnabled = enabled) }

    suspend fun setKeepScreenOn(enabled: Boolean) = update { it.copy(keepScreenOnDuringPuzzle = enabled) }

    suspend fun setConfirmBeforeDismiss(enabled: Boolean) = update { it.copy(confirmBeforeDismiss = enabled) }

    suspend fun setShowCoordinates(enabled: Boolean) = update { it.copy(showCoordinates = enabled) }

    suspend fun setBoardColors(light: Long, dark: Long) =
        update { it.copy(boardLightColor = light, boardDarkColor = dark) }

    suspend fun dismissReliabilityTips() = update { it.copy(reliabilityTipsDismissed = true) }

    suspend fun completeOnboarding() = update { it.copy(onboardingComplete = true) }

    /** Clears every preference, restoring factory defaults. */
    suspend fun reset() {
        context.settingsDataStore.edit { it.clear() }
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        themeMode = ThemeMode.fromName(this[Keys.THEME_MODE]),
        dynamicColor = this[Keys.DYNAMIC_COLOR] ?: true,
        defaultMaxDifficulty = this[Keys.DEFAULT_MAX_DIFFICULTY] ?: 2,
        puzzleTheme = this[Keys.PUZZLE_THEME]?.let { name ->
            PuzzleTheme.entries.firstOrNull { it.name == name }
        } ?: PuzzleTheme.MATE_IN_ONE,
        soundUri = this[Keys.SOUND_URI],
        vibrate = this[Keys.VIBRATE] ?: true,
        volume = this[Keys.VOLUME] ?: 1.0f,
        crescendoSeconds = this[Keys.CRESCENDO_SECONDS] ?: 20,
        snoozeMinutes = this[Keys.SNOOZE_MINUTES] ?: 5,
        maxSnoozes = this[Keys.MAX_SNOOZES] ?: 3,
        sleepAsAndroidEnabled = this[Keys.SLEEP_AS_ANDROID] ?: false,
        keepScreenOnDuringPuzzle = this[Keys.KEEP_SCREEN_ON] ?: true,
        confirmBeforeDismiss = this[Keys.CONFIRM_BEFORE_DISMISS] ?: false,
        showCoordinates = this[Keys.SHOW_COORDINATES] ?: true,
        boardLightColor = this[Keys.BOARD_LIGHT] ?: AppSettings.DEFAULT_LIGHT_SQUARE,
        boardDarkColor = this[Keys.BOARD_DARK] ?: AppSettings.DEFAULT_DARK_SQUARE,
        reliabilityTipsDismissed = this[Keys.RELIABILITY_TIPS_DISMISSED] ?: false,
        onboardingComplete = this[Keys.ONBOARDING_COMPLETE] ?: false,
    )

    private fun Preferences.MutablePreferences.applySettings(settings: AppSettings) {
        this[Keys.THEME_MODE] = settings.themeMode.name
        this[Keys.DYNAMIC_COLOR] = settings.dynamicColor
        this[Keys.DEFAULT_MAX_DIFFICULTY] = settings.defaultMaxDifficulty
        // A null theme means "any puzzle", stored as an empty string.
        this[Keys.PUZZLE_THEME] = settings.puzzleTheme?.name ?: ""
        settings.soundUri?.let { this[Keys.SOUND_URI] = it } ?: remove(Keys.SOUND_URI)
        this[Keys.VIBRATE] = settings.vibrate
        this[Keys.VOLUME] = settings.volume
        this[Keys.CRESCENDO_SECONDS] = settings.crescendoSeconds
        this[Keys.SNOOZE_MINUTES] = settings.snoozeMinutes
        this[Keys.MAX_SNOOZES] = settings.maxSnoozes
        this[Keys.SLEEP_AS_ANDROID] = settings.sleepAsAndroidEnabled
        this[Keys.KEEP_SCREEN_ON] = settings.keepScreenOnDuringPuzzle
        this[Keys.CONFIRM_BEFORE_DISMISS] = settings.confirmBeforeDismiss
        this[Keys.SHOW_COORDINATES] = settings.showCoordinates
        this[Keys.BOARD_LIGHT] = settings.boardLightColor
        this[Keys.BOARD_DARK] = settings.boardDarkColor
        this[Keys.RELIABILITY_TIPS_DISMISSED] = settings.reliabilityTipsDismissed
        this[Keys.ONBOARDING_COMPLETE] = settings.onboardingComplete
    }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DEFAULT_MAX_DIFFICULTY = intPreferencesKey("default_max_difficulty")
        val PUZZLE_THEME = stringPreferencesKey("puzzle_theme")
        val SOUND_URI = stringPreferencesKey("sound_uri")
        val VIBRATE = booleanPreferencesKey("vibrate")
        val VOLUME = floatPreferencesKey("volume")
        val CRESCENDO_SECONDS = intPreferencesKey("crescendo_seconds")
        val SNOOZE_MINUTES = intPreferencesKey("snooze_minutes")
        val MAX_SNOOZES = intPreferencesKey("max_snoozes")
        val SLEEP_AS_ANDROID = booleanPreferencesKey("sleep_as_android")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val CONFIRM_BEFORE_DISMISS = booleanPreferencesKey("confirm_before_dismiss")
        val SHOW_COORDINATES = booleanPreferencesKey("show_coordinates")
        val BOARD_LIGHT = longPreferencesKey("board_light")
        val BOARD_DARK = longPreferencesKey("board_dark")
        val RELIABILITY_TIPS_DISMISSED = booleanPreferencesKey("reliability_tips_dismissed")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    }
}
