package com.mostafazahra.chesswake.settings.domain

import com.mostafazahra.chesswake.puzzle.domain.PuzzleTheme

/** Which theme the app should follow. */
enum class ThemeMode(val displayName: String) {
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark"),
    ;

    companion object {
        fun fromName(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * User preferences.
 *
 * Stored in Jetpack DataStore (see `SettingsRepository`) rather than in Room,
 * because these are single-row key/value settings that the UI reads reactively.
 *
 * @property dynamicColor Material You: take the palette from the wallpaper on
 *   Android 12+. Ignored on older versions.
 * @property defaultMaxDifficulty cap applied to newly created alarms.
 * @property puzzleTheme default puzzle filter for newly created alarms.
 * @property sleepAsAndroidEnabled mirror alarms to Sleep as Android and let its
 *   smart wake-up window launch the ChessWake puzzle. See `SleepAsAndroidBridge`.
 * @property keepScreenOnDuringPuzzle stops the display sleeping mid-puzzle, which
 *   matters because the alarm screen is shown over the lock screen.
 * @property confirmBeforeDismiss requires a deliberate confirm tap after solving,
 *   for people who solve on autopilot and want a second beat of wakefulness.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val defaultMaxDifficulty: Int = 2,
    val puzzleTheme: PuzzleTheme? = PuzzleTheme.MATE_IN_ONE,
    val soundUri: String? = null,
    val vibrate: Boolean = true,
    val volume: Float = 1.0f,
    val crescendoSeconds: Int = 20,
    val snoozeMinutes: Int = 5,
    val maxSnoozes: Int = 3,
    val sleepAsAndroidEnabled: Boolean = false,
    val keepScreenOnDuringPuzzle: Boolean = true,
    val confirmBeforeDismiss: Boolean = false,
    val showCoordinates: Boolean = true,
    val boardLightColor: Long = DEFAULT_LIGHT_SQUARE,
    val boardDarkColor: Long = DEFAULT_DARK_SQUARE,
    /** True once the app has shown the "battery optimisation" guidance card. */
    val reliabilityTipsDismissed: Boolean = false,
    /** True once a first-run alarm has been offered. */
    val onboardingComplete: Boolean = false,
) {
    companion object {
        /** Lichess-style board colours, which read well in both light and dark UI. */
        const val DEFAULT_LIGHT_SQUARE: Long = 0xFFF0D9B5
        const val DEFAULT_DARK_SQUARE: Long = 0xFFB58863

        val DEFAULT = AppSettings()
    }
}
