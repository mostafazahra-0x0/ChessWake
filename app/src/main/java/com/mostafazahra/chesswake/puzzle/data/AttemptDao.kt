package com.mostafazahra.chesswake.puzzle.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Where an attempt came from. Alarm attempts drive the streak; practice ones do not. */
object AttemptContext {
    const val ALARM = "ALARM"
    const val PRACTICE = "PRACTICE"
    const val SNOOZE = "SNOOZE"
    const val DISMISSED_WITHOUT_PUZZLE = "DISMISSED_WITHOUT_PUZZLE"
}

/**
 * One row per attempt at a puzzle.
 *
 * This is the only history ChessWake keeps: no accounts, no network, nothing
 * leaves the device. The stats screen is a pure aggregation over this table.
 */
@Entity(
    tableName = "attempts",
    indices = [Index("puzzleId"), Index("startedAt"), Index("context")],
)
data class AttemptEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "puzzleId") val puzzleId: String,
    @ColumnInfo(name = "puzzleName") val puzzleName: String,
    @ColumnInfo(name = "theme") val theme: String,
    @ColumnInfo(name = "difficulty") val difficulty: Int,

    /** One of [AttemptContext]. */
    @ColumnInfo(name = "context") val context: String,
    @ColumnInfo(name = "alarmId") val alarmId: Long?,

    @ColumnInfo(name = "solved") val solved: Boolean,
    @ColumnInfo(name = "wrongAttempts") val wrongAttempts: Int,
    @ColumnInfo(name = "durationMillis") val durationMillis: Long,

    /** Epoch millis, so the streak can be grouped by local calendar day. */
    @ColumnInfo(name = "startedAt") val startedAt: Long,
)

/** Per-theme rollup returned by [AttemptDao.themeStats]. */
data class ThemeStat(
    @ColumnInfo(name = "theme") val theme: String,
    @ColumnInfo(name = "total") val total: Int,
    /** Null until the table has rows; SQLite's SUM over zero rows is NULL. */
    @ColumnInfo(name = "solved") val solved: Int?,
    /** AVG is REAL in SQLite, hence Double rather than Long. */
    @ColumnInfo(name = "averageMillis") val averageMillis: Double?,
)

/** Headline numbers for the stats screen. */
data class Totals(
    @ColumnInfo(name = "attempts") val attempts: Int,
    /** Null until the table has rows; SQLite's SUM over zero rows is NULL. */
    @ColumnInfo(name = "solved") val solved: Int?,
    /** AVG is REAL in SQLite, hence Double rather than Long. */
    @ColumnInfo(name = "averageMillis") val averageMillis: Double?,
    @ColumnInfo(name = "bestMillis") val bestMillis: Long?,
    @ColumnInfo(name = "firstAttemptAt") val firstAttemptAt: Long?,
)

@Dao
interface AttemptDao {

    @Insert
    suspend fun insert(attempt: AttemptEntity): Long

    @Query(
        """
        SELECT COUNT(*) AS attempts,
               SUM(CASE WHEN solved = 1 THEN 1 ELSE 0 END) AS solved,
               AVG(CASE WHEN solved = 1 THEN durationMillis END) AS averageMillis,
               MIN(CASE WHEN solved = 1 THEN durationMillis END) AS bestMillis,
               MIN(startedAt) AS firstAttemptAt
        FROM attempts
        """,
    )
    fun observeTotals(): Flow<Totals>

    @Query(
        """
        SELECT theme AS theme,
               COUNT(*) AS total,
               SUM(CASE WHEN solved = 1 THEN 1 ELSE 0 END) AS solved,
               AVG(CASE WHEN solved = 1 THEN durationMillis END) AS averageMillis
        FROM attempts
        GROUP BY theme
        ORDER BY total DESC
        """,
    )
    fun observeThemeStats(): Flow<List<ThemeStat>>

    @Query("SELECT * FROM attempts ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AttemptEntity>>

    /**
     * Start times of every solved alarm attempt, newest first.
     *
     * Grouped into local calendar days in Kotlin rather than in SQL, so the streak
     * follows the user's timezone instead of SQLite's, and so it is unit-testable.
     */
    @Query("SELECT startedAt FROM attempts WHERE solved = 1 AND context = 'ALARM' ORDER BY startedAt DESC")
    fun observeSolvedAlarmTimestamps(): Flow<List<Long>>

    @Query("SELECT startedAt FROM attempts WHERE solved = 1 AND context = 'ALARM' ORDER BY startedAt DESC")
    suspend fun solvedAlarmTimestamps(): List<Long>

    @Query("SELECT puzzleId FROM attempts ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recentPuzzleIds(limit: Int): List<String>

    @Query("DELETE FROM attempts")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM attempts")
    suspend fun count(): Int
}
