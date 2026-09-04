package com.mostafazahra.chesswake.stats.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mostafazahra.chesswake.puzzle.data.AttemptContext
import com.mostafazahra.chesswake.puzzle.domain.PuzzleTheme
import com.mostafazahra.chesswake.stats.data.StatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of the per-theme breakdown. */
data class ThemeRow(
    val themeName: String,
    val total: Int,
    val solved: Int,
    val accuracyPercent: Int,
    val averageMillis: Long,
)

/** One row of the recent-attempts list. */
data class RecentRow(
    val puzzleName: String,
    val themeName: String,
    val solved: Boolean,
    val wrongAttempts: Int,
    val durationMillis: Long,
    val fromAlarm: Boolean,
    /** `2 h ago`, resolved when the state is built. */
    val whenText: String,
)

data class StatsUiState(
    val loading: Boolean = true,
    val attempts: Int = 0,
    val solved: Int = 0,
    val accuracyPercent: Int = 0,
    val averageMillis: Long = 0L,
    val bestMillis: Long = 0L,
    val hasBest: Boolean = false,
    val alarmWakeups: Int = 0,
    val currentStreakDays: Int = 0,
    val longestStreakDays: Int = 0,
    val themes: List<ThemeRow> = emptyList(),
    val recent: List<RecentRow> = emptyList(),
) {
    val isEmpty: Boolean get() = attempts == 0
}

/**
 * Read-only aggregation over the local `attempts` table.
 *
 * Every number here is derived on device from rows the app wrote itself; there is
 * no analytics backend to reconcile with, which is the point of the stats screen
 * existing at all.
 */
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
) : ViewModel() {

    /** Re-read whenever totals change, because the DAO exposes it as a one-shot query. */
    private val alarmWakeups = MutableStateFlow(0)

    val uiState: StateFlow<StatsUiState> = combine(
        statsRepository.totals,
        statsRepository.themeStats,
        statsRepository.recentAttempts(RECENT_LIMIT),
        statsRepository.streak,
        alarmWakeups,
    ) { totals, themes, recent, streak, wakeups ->
        val solved = totals.solved ?: 0
        val now = System.currentTimeMillis()
        StatsUiState(
            loading = false,
            attempts = totals.attempts,
            solved = solved,
            accuracyPercent = percentOf(solved, totals.attempts),
            averageMillis = totals.averageMillis?.toLong() ?: 0L,
            bestMillis = totals.bestMillis ?: 0L,
            hasBest = totals.bestMillis != null,
            alarmWakeups = wakeups,
            currentStreakDays = streak.currentDays,
            longestStreakDays = streak.longestDays,
            themes = themes.map { row ->
                val rowSolved = row.solved ?: 0
                ThemeRow(
                    themeName = PuzzleTheme.fromName(row.theme).displayName,
                    total = row.total,
                    solved = rowSolved,
                    accuracyPercent = percentOf(rowSolved, row.total),
                    averageMillis = row.averageMillis?.toLong() ?: 0L,
                )
            },
            recent = recent.map { attempt ->
                RecentRow(
                    puzzleName = attempt.puzzleName,
                    themeName = PuzzleTheme.fromName(attempt.theme).displayName,
                    solved = attempt.solved,
                    wrongAttempts = attempt.wrongAttempts,
                    durationMillis = attempt.durationMillis,
                    fromAlarm = attempt.context == AttemptContext.ALARM,
                    whenText = formatRelativeTime(attempt.startedAt, now),
                )
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = StatsUiState(),
    )

    init {
        // The wake-up count comes from a one-shot query; refresh it whenever the
        // totals flow moves, which is exactly when it could have changed.
        viewModelScope.launch {
            statsRepository.totals.collect {
                alarmWakeups.value = runCatching { statsRepository.alarmWakeups() }.getOrDefault(0)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch { runCatching { statsRepository.clearHistory() } }
    }

    private companion object {
        const val RECENT_LIMIT = 12
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** Integer percentage, rounded down; 0 when there is nothing to divide by. */
internal fun percentOf(part: Int, whole: Int): Int =
    if (whole <= 0) 0 else ((part * 100L) / whole).toInt()

/** `1:04` for a minute-plus solve, `12s` for anything shorter. */
internal fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "%d:%02d".format(minutes, seconds) else "${seconds}s"
}

/** Coarse "time ago" text; deliberately imprecise so it never looks stale. */
internal fun formatRelativeTime(timestampMillis: Long, nowMillis: Long): String {
    val delta = (nowMillis - timestampMillis).coerceAtLeast(0L)
    val minutes = delta / 60_000L
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours h ago"
        days < 7 -> "$days d ago"
        else -> "${days / 7} wk ago"
    }
}
