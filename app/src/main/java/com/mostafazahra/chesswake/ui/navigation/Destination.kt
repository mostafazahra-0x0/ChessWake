package com.mostafazahra.chesswake.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.ui.graphics.vector.ImageVector
import com.mostafazahra.chesswake.R

/**
 * Route strings for the app's navigation graph.
 *
 * Kept as plain strings rather than the type-safe `@Serializable` route API: the
 * graph has five destinations and one argument, and pulling in the Kotlin
 * serialization plugin for that is not a good trade.
 */
object Routes {
    const val ALARMS = "alarms"
    const val PRACTICE = "practice"
    const val STATS = "stats"
    const val SETTINGS = "settings"

    /** Alarm editor. `alarmId` is [NEW_ALARM_ID] when creating. */
    const val ALARM_EDIT = "alarm_edit/{$ARG_ALARM_ID}"
    const val ARG_ALARM_ID = "alarmId"

    /** Sentinel meaning "create a new alarm instead of loading one". */
    const val NEW_ALARM_ID = -1L

    fun alarmEdit(alarmId: Long = NEW_ALARM_ID): String = "alarm_edit/$alarmId"
}

/**
 * The four tabs shown in the bottom navigation bar.
 *
 * Alarms first: this is an alarm clock, and the puzzle practice is the secondary
 * feature people use to get better at dismissing it.
 */
enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    ALARMS(Routes.ALARMS, R.string.tab_alarms, Icons.Outlined.Alarm),
    PRACTICE(Routes.PRACTICE, R.string.tab_practice, Icons.Outlined.Extension),
    STATS(Routes.STATS, R.string.tab_stats, Icons.Outlined.BarChart),
    SETTINGS(Routes.SETTINGS, R.string.tab_settings, Icons.Outlined.Settings),
    ;

    companion object {
        fun fromRoute(route: String?): TopLevelDestination? = entries.firstOrNull { it.route == route }
    }
}
