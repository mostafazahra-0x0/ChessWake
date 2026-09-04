package com.mostafazahra.chesswake.puzzle.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.mostafazahra.chesswake.puzzle.domain.Puzzle
import com.mostafazahra.chesswake.puzzle.domain.PuzzleGoal
import com.mostafazahra.chesswake.puzzle.domain.PuzzleTheme
import kotlinx.coroutines.flow.Flow

/** Persisted form of [Puzzle], seeded from [BundledPuzzles]. */
@Entity(tableName = "puzzles", indices = [Index("theme"), Index("difficulty")])
data class PuzzleEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "theme") val theme: String,
    @ColumnInfo(name = "goal") val goal: String,
    @ColumnInfo(name = "difficulty") val difficulty: Int,
    @ColumnInfo(name = "fen") val fen: String,
    @ColumnInfo(name = "solution") val solution: List<String>,
    @ColumnInfo(name = "alternativeSolutions") val alternativeSolutions: Set<String>,
    @ColumnInfo(name = "matesIn") val matesIn: Int,
    @ColumnInfo(name = "materialGain") val materialGain: Int,
    @ColumnInfo(name = "hint") val hint: String,
    @ColumnInfo(name = "description") val description: String,
    @ColumnInfo(name = "source") val source: String,

    /** Which revision of the bundled set produced this row; drives re-seeding. */
    @ColumnInfo(name = "revision") val revision: Int,
)

fun PuzzleEntity.toDomain(): Puzzle = Puzzle(
    id = id,
    name = name,
    theme = PuzzleTheme.fromName(theme),
    goal = PuzzleGoal.fromName(goal),
    difficulty = difficulty,
    fen = fen,
    solution = solution,
    alternativeSolutions = alternativeSolutions,
    matesIn = matesIn,
    materialGain = materialGain,
    hint = hint,
    description = description,
    source = source,
)

fun Puzzle.toEntity(revision: Int): PuzzleEntity = PuzzleEntity(
    id = id,
    name = name,
    theme = theme.name,
    goal = goal.name,
    difficulty = difficulty,
    fen = fen,
    solution = solution,
    alternativeSolutions = alternativeSolutions,
    matesIn = matesIn,
    materialGain = materialGain,
    hint = hint,
    description = description,
    source = source,
    revision = revision,
)

@Dao
interface PuzzleDao {

    @Query("SELECT * FROM puzzles ORDER BY difficulty ASC, id ASC")
    fun observeAll(): Flow<List<PuzzleEntity>>

    @Query("SELECT * FROM puzzles WHERE id = :id")
    suspend fun findById(id: String): PuzzleEntity?

    @Query("SELECT * FROM puzzles WHERE id = :id")
    fun observeById(id: String): Flow<PuzzleEntity?>

    @Query("SELECT COUNT(*) FROM puzzles")
    suspend fun count(): Int

    /**
     * The revision of the rows currently stored.
     *
     * Compared against [BundledPuzzles.REVISION] on launch: a mismatch means the
     * app ships new or fixed puzzles and the table should be re-seeded.
     */
    @Query("SELECT revision FROM puzzles LIMIT 1")
    suspend fun storedRevision(): Int?

    /**
     * A random puzzle matching the alarm's filters.
     *
     * `ORDER BY RANDOM()` is fine here: the table holds tens of rows, and it gives
     * a genuinely different puzzle every morning without any bookkeeping.
     */
    @Query(
        """
        SELECT * FROM puzzles
        WHERE difficulty <= :maxDifficulty
          AND (:theme IS NULL OR theme = :theme)
        ORDER BY RANDOM()
        LIMIT 1
        """,
    )
    suspend fun randomMatching(theme: String?, maxDifficulty: Int): PuzzleEntity?

    /**
     * A random puzzle the solver has not seen recently.
     *
     * [excludeIds] holds the last few puzzle ids so that practice mode does not
     * serve the same position twice in a row.
     */
    @Query(
        """
        SELECT * FROM puzzles
        WHERE difficulty <= :maxDifficulty
          AND (:theme IS NULL OR theme = :theme)
          AND id NOT IN (:excludeIds)
        ORDER BY RANDOM()
        LIMIT 1
        """,
    )
    suspend fun randomMatchingExcluding(
        theme: String?,
        maxDifficulty: Int,
        excludeIds: List<String>,
    ): PuzzleEntity?

    @Query("SELECT * FROM puzzles WHERE difficulty <= :maxDifficulty ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomUpToDifficulty(maxDifficulty: Int, limit: Int): List<PuzzleEntity>

    /** One-shot snapshot of the whole table, for filtering in memory. */
    @Query("SELECT * FROM puzzles ORDER BY difficulty ASC, name ASC")
    suspend fun observeAllSnapshot(): List<PuzzleEntity>

    @Query("SELECT * FROM puzzles ORDER BY RANDOM() LIMIT :limit")
    suspend fun random(limit: Int): List<PuzzleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(puzzles: List<PuzzleEntity>)

    @Query("DELETE FROM puzzles")
    suspend fun deleteAll()
}
