package com.mostafazahra.chesswake.puzzle.data

import com.mostafazahra.chesswake.di.IoDispatcher
import com.mostafazahra.chesswake.puzzle.domain.Puzzle
import com.mostafazahra.chesswake.puzzle.domain.PuzzleTheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the puzzle set and keeps the local table seeded from [BundledPuzzles].
 *
 * Seeding happens on first launch and again whenever [BundledPuzzles.REVISION]
 * changes, which is how a new app version can ship extra puzzles without a
 * migration and without ever touching the network.
 */
@Singleton
class PuzzleRepository @Inject constructor(
    private val puzzleDao: PuzzleDao,
    private val attemptDao: AttemptDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** Every puzzle, easiest first. */
    val puzzles: Flow<List<Puzzle>> = puzzleDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** How many puzzles are available, as a Flow for the settings screen. */
    val puzzleCount: Flow<Int> = puzzles.map { it.size }

    suspend fun byId(id: String): Puzzle? = withContext(ioDispatcher) {
        puzzleDao.findById(id)?.toDomain()
    }

    suspend fun count(): Int = withContext(ioDispatcher) { puzzleDao.count() }

    /**
     * Seeds or re-seeds the puzzle table.
     *
     * Safe to call on every launch: it does nothing when the stored revision
     * already matches the bundled one.
     *
     * @return the number of puzzles available afterwards.
     */
    suspend fun ensureSeeded(): Int = withContext(ioDispatcher) {
        val stored = runCatching { puzzleDao.storedRevision() }.getOrNull()
        val existing = runCatching { puzzleDao.count() }.getOrDefault(0)
        if (existing > 0 && stored == BundledPuzzles.REVISION) return@withContext existing

        // A different revision means the shipped set changed; start clean so that
        // puzzles removed from the bundle do not linger.
        if (stored != null && stored != BundledPuzzles.REVISION) {
            puzzleDao.deleteAll()
        }
        puzzleDao.upsertAll(BundledPuzzles.ALL.map { it.toEntity(BundledPuzzles.REVISION) })
        puzzleDao.count()
    }

    /** Drops any local edits and restores the bundled set. */
    suspend fun reseedFromBundle(): Int = withContext(ioDispatcher) {
        puzzleDao.deleteAll()
        puzzleDao.upsertAll(BundledPuzzles.ALL.map { it.toEntity(BundledPuzzles.REVISION) })
        puzzleDao.count()
    }

    /**
     * Picks a puzzle to show, honouring the alarm's theme and difficulty filters.
     *
     * Falls back progressively — filtered query, unfiltered query, bundled list,
     * and finally a single hardcoded position — because an alarm must always have
     * a solvable puzzle attached to it, even if the database is empty or corrupt.
     *
     * @param avoidRecent how many recently attempted puzzles to exclude, so
     *   consecutive mornings do not show the same position.
     */
    suspend fun pickPuzzle(
        theme: PuzzleTheme? = null,
        maxDifficulty: Int = 5,
        avoidRecent: Int = 3,
    ): Puzzle = withContext(ioDispatcher) {
        val difficulty = maxDifficulty.coerceIn(1, 5)
        val themeName = theme?.name

        val recent = if (avoidRecent > 0) {
            runCatching { attemptDao.recentPuzzleIds(avoidRecent) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val fromFiltered = if (recent.isEmpty()) {
            puzzleDao.randomMatching(themeName, difficulty)
        } else {
            puzzleDao.randomMatchingExcluding(themeName, difficulty, recent)
                // Everything matching was recently seen; relax that constraint.
                ?: puzzleDao.randomMatching(themeName, difficulty)
        }
        fromFiltered?.toDomain()?.let { return@withContext it }

        puzzleDao.randomMatching(null, difficulty)?.toDomain()?.let { return@withContext it }
        puzzleDao.random(1).firstOrNull()?.toDomain()?.let { return@withContext it }

        BundledPuzzles.upToDifficulty(difficulty).randomOrNull()
            ?: BundledPuzzles.ALL.randomOrNull()
            ?: Puzzle.FALLBACK
    }

    /** A specific puzzle for the practice screen, or a fresh pick when [id] is null. */
    suspend fun practicePuzzle(id: String? = null): Puzzle = withContext(ioDispatcher) {
        id?.let { byId(it) } ?: pickPuzzle(avoidRecent = 1)
    }

    /** Everything matching a filter, for the practice screen's puzzle browser. */
    suspend fun matching(theme: PuzzleTheme?, maxDifficulty: Int): List<Puzzle> =
        withContext(ioDispatcher) {
            val rows = puzzleDao.observeAllSnapshot()
            rows.map { it.toDomain() }
                .filter { theme == null || it.theme == theme }
                .filter { it.difficulty <= maxDifficulty.coerceIn(1, 5) }
                .sortedWith(compareBy({ it.difficulty }, { it.name }))
        }
}
