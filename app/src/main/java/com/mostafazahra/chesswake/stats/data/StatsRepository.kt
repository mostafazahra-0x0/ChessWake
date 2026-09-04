package com.mostafazahra.chesswake.stats.data

import com.mostafazahra.chesswake.di.IoDispatcher
import com.mostafazahra.chesswake.puzzle.data.AttemptContext
import com.mostafazahra.chesswake.puzzle.data.AttemptDao
import com.mostafazahra.chesswake.puzzle.data.AttemptEntity
import com.mostafazahra.chesswake.puzzle.data.ThemeStat
import com.mostafazahra.chesswake.puzzle.data.Totals
import com.mostafazahra.chesswake.puzzle.domain.Puzzle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** How many consecutive mornings the alarm was solved, and the best run so far. */
data class Streak(
    val currentDays: Int,
    val longestDays: Int,
    val lastSolvedAt: Long?,
) {
    companion object {
        val EMPTY = Streak(0, 0, null)
    }
}

/**
 * Writes attempt history and aggregates it for the stats screen.
 *
 * The streak is computed in Kotlin from epoch millis rather than in SQL, so it
 * follows the user's own timezone and calendar rather than SQLite's, and so the
 * maths can be unit-tested without a database.
 */
@Singleton
class StatsRepository @Inject constructor(
    private val attemptDao: AttemptDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock,
) {

    val totals: Flow<Totals> = attemptDao.observeTotals()

    val themeStats: Flow<List<ThemeStat>> = attemptDao.observeThemeStats()

    fun recentAttempts(limit: Int = 20): Flow<List<AttemptEntity>> = attemptDao.observeRecent(limit)

    /** Consecutive-day streak over solved *alarm* attempts only. */
    val streak: Flow<Streak> = attemptDao.observeSolvedAlarmTimestamps().map { timestamps ->
        computeStreak(timestamps, LocalDate.now(clock), ZoneId.of(clock.zone.id))
    }

    /**
     * Records one attempt.
     *
     * Called for every puzzle shown, including ones that were never solved, so
     * the accuracy figure is honest rather than survivorship-biased.
     */
    suspend fun recordAttempt(
        puzzle: Puzzle,
        context: String,
        solved: Boolean,
        wrongAttempts: Int,
        durationMillis: Long,
        alarmId: Long? = null,
        startedAt: Long = System.currentTimeMillis(),
    ) = withContext(ioDispatcher) {
        attemptDao.insert(
            AttemptEntity(
                puzzleId = puzzle.id,
                puzzleName = puzzle.name,
                theme = puzzle.theme.name,
                difficulty = puzzle.difficulty,
                context = context,
                alarmId = alarmId,
                solved = solved,
                wrongAttempts = wrongAttempts,
                durationMillis = durationMillis.coerceAtLeast(0),
                startedAt = startedAt,
            ),
        )
    }

    /** Convenience for the alarm screen, which always records an alarm attempt. */
    suspend fun recordAlarmAttempt(
        puzzle: Puzzle,
        alarmId: Long?,
        solved: Boolean,
        wrongAttempts: Int,
        durationMillis: Long,
        startedAt: Long,
    ) = recordAttempt(
        puzzle = puzzle,
        context = AttemptContext.ALARM,
        solved = solved,
        wrongAttempts = wrongAttempts,
        durationMillis = durationMillis,
        alarmId = alarmId,
        startedAt = startedAt,
    )

    /**
     * How many alarms were turned off by solving a puzzle.
     *
     * One-shot rather than a Flow: it is only refreshed when the totals change,
     * which is the only moment it can change.
     */
    suspend fun alarmWakeups(): Int = withContext(ioDispatcher) {
        attemptDao.solvedAlarmTimestamps().size
    }

    suspend fun clearHistory() = withContext(ioDispatcher) { attemptDao.deleteAll() }

    suspend fun attemptCount(): Int = withContext(ioDispatcher) { attemptDao.count() }

    companion object {
        /**
         * Pure streak maths, exposed so it can be unit-tested without a database.
         *
         * The current streak counts back from [today]; a solve from yesterday still
         * keeps the streak alive until the day is over, which is what a person
         * expects from a "morning streak".
         *
         * @param zone the user's zone, because "which day was that" is a local
         *   question and SQLite has no idea which timezone a row was written in.
         */
        internal fun computeStreak(
            solvedTimestamps: List<Long>,
            today: LocalDate,
            zone: ZoneId,
        ): Streak {
            if (solvedTimestamps.isEmpty()) return Streak.EMPTY

            val days = solvedTimestamps
                .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                .distinct()
                .sorted()

            // Longest run of consecutive days anywhere in the history.
            var longest = 1
            var run = 1
            for (index in 1 until days.size) {
                if (days[index] == days[index - 1].plusDays(1)) {
                    run++
                    if (run > longest) longest = run
                } else {
                    run = 1
                }
            }

            // Current run: anchored on today if it was solved, otherwise yesterday.
            val anchor = when {
                today in days -> today
                today.minusDays(1) in days -> today.minusDays(1)
                else -> return Streak(0, longest, solvedTimestamps.max())
            }
            var current = 1
            var cursor = anchor.minusDays(1)
            while (cursor in days) {
                current++
                cursor = cursor.minusDays(1)
            }

            return Streak(current, maxOf(longest, current), solvedTimestamps.max())
        }
    }
}
