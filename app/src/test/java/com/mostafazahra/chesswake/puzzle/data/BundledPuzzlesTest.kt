package com.mostafazahra.chesswake.puzzle.data

import com.mostafazahra.chesswake.chess.Game
import com.mostafazahra.chesswake.chess.GameStatus
import com.mostafazahra.chesswake.chess.Material
import com.mostafazahra.chesswake.chess.Move
import com.mostafazahra.chesswake.chess.MoveGenerator
import com.mostafazahra.chesswake.chess.PieceColor
import com.mostafazahra.chesswake.chess.Position
import com.mostafazahra.chesswake.puzzle.domain.Puzzle
import com.mostafazahra.chesswake.puzzle.domain.PuzzleGoal
import com.mostafazahra.chesswake.puzzle.domain.PuzzleTheme
import com.mostafazahra.chesswake.testutil.TestVectors
import com.mostafazahra.chesswake.testutil.int
import com.mostafazahra.chesswake.testutil.str
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled puzzle set is data, and data rots: a FEN gets hand-edited, a
 * solution ply is retyped, a revision is bumped without regenerating. Every
 * assertion here is about the *shipped* set rather than about one example, so a
 * single bad puzzle fails the build instead of ringing at somebody's bedside.
 *
 * Expectations come from `chess/puzzle_checks.json`, which
 * `tools/generate_test_vectors.py` produces with python-chess — an independent
 * engine — and from `puzzles/bundled_puzzles.json`, the generator's own output.
 */
class BundledPuzzlesTest {

    private val checks: Map<String, JsonObject> =
        TestVectors.section(TestVectors.load("chess/puzzle_checks.json"), "puzzles")

    private val generated: JsonObject = TestVectors.load("puzzles/bundled_puzzles.json")

    private val generatedPuzzles: List<JsonObject> = TestVectors.entries(generated, "puzzles")

    private val all: List<Puzzle> get() = BundledPuzzles.ALL

    @Test
    fun `the shipped set is the set the generator produced`() {
        assertEquals(
            "BundledPuzzles.REVISION does not match the generated resource; regenerate and re-embed",
            generated.getValue("revision").int,
            BundledPuzzles.REVISION,
        )
        assertEquals(generatedPuzzles.size, all.size)
        assertEquals(generatedPuzzles.map { it.getValue("id").str }, all.map { it.id })
    }

    @Test
    fun `every puzzle matches the python-chess expectations for it`() {
        assertEquals("each generated puzzle must have a matching vector", all.size, checks.size)
        for (puzzle in all) {
            // JUnit 4's assertNotNull returns void, so unwrap with requireNotNull.
            val expected = requireNotNull(checks[puzzle.id]) { "no vector for ${puzzle.id}" }
            assertEquals("${puzzle.id} fen", expected.getValue("fen").str, puzzle.fen)
            assertEquals("${puzzle.id} goal", expected.getValue("goal").str, puzzle.goal.name)
            assertEquals("${puzzle.id} matesIn", expected.getValue("matesIn").int, puzzle.matesIn)
            assertEquals(
                "${puzzle.id} side to move",
                expected.getValue("sideToMove").str,
                puzzle.solverColor.name,
            )
            assertEquals(
                "${puzzle.id} solution",
                TestVectors.entries(expected, "solution").map { it.getValue("uci").str },
                puzzle.solution,
            )
            assertEquals(
                "${puzzle.id} alternatives",
                TestVectors.strings(expected, "alternatives").toSet(),
                puzzle.alternativeSolutions,
            )
        }
    }

    @Test
    fun `every puzzle carries the generator's metadata unchanged`() {
        val byId = generatedPuzzles.associateBy { it.getValue("id").str }
        for (puzzle in all) {
            val expected = requireNotNull(byId[puzzle.id]) { "no generated entry for ${puzzle.id}" }
            assertEquals("${puzzle.id} name", expected.getValue("name").str, puzzle.name)
            // The generator writes themes in snake_case; the enum names are SCREAMING_CASE.
            assertEquals(
                "${puzzle.id} theme",
                expected.getValue("theme").str.uppercase(),
                puzzle.theme.name,
            )
            assertEquals("${puzzle.id} difficulty", expected.getValue("difficulty").int, puzzle.difficulty)
            assertEquals(
                "${puzzle.id} materialGain",
                expected.getValue("material_gain").int,
                puzzle.materialGain,
            )
            assertEquals("${puzzle.id} hint", expected.getValue("hint").str, puzzle.hint)
            assertEquals("${puzzle.id} description", expected.getValue("description").str, puzzle.description)
            assertEquals("${puzzle.id} source", expected.getValue("source").str, puzzle.source)
        }
    }

    @Test
    fun `every puzzle parses and is playable`() {
        for (puzzle in all) {
            assertNotNull("${puzzle.id}: FEN did not parse: ${puzzle.fen}", puzzle.initialPosition)
            assertTrue(
                "${puzzle.id}: solution line did not parse (${puzzle.solution})",
                puzzle.isPlayable,
            )
            assertEquals(
                "${puzzle.id}: parsed moves must match the recorded plies",
                puzzle.solution.size,
                puzzle.solutionMoves.size,
            )
        }
    }

    @Test
    fun `every solution replays legally and reaches the stated goal`() {
        for (puzzle in all) {
            val start = requireNotNull(puzzle.initialPosition)
            val game = Game(start)
            val played = game.playLine(puzzle.solution)
            assertEquals("${puzzle.id}: stopped after $played of ${puzzle.solution.size} plies", puzzle.solution.size, played)

            val expectedStatus = GameStatus.valueOf(checks.getValue(puzzle.id).getValue("statusAfterSolution").str)
            assertEquals("${puzzle.id}: status after the line", expectedStatus, game.status())

            when (puzzle.goal) {
                PuzzleGoal.CHECKMATE -> assertEquals(
                    "${puzzle.id}: the recorded line must end in mate",
                    GameStatus.CHECKMATE,
                    game.status(),
                )

                PuzzleGoal.WIN_MATERIAL -> {
                    // The session judges this goal on the swing in material
                    // *balance*, not on what the solver happens to own: a fork
                    // that trades a knight for a rook leaves the solver's own
                    // material unchanged while winning the exchange.
                    val gained = Material.balance(game.position.board, puzzle.solverColor) -
                        Material.balance(start.board, puzzle.solverColor)
                    assertTrue(
                        "${puzzle.id}: gained $gained centipawns, needs ${puzzle.materialGain}",
                        gained >= puzzle.materialGain,
                    )
                    assertEquals(
                        "${puzzle.id}: recorded gain must be exactly what the line wins",
                        puzzle.materialGain,
                        gained,
                    )
                }
            }
        }
    }

    @Test
    fun `the engine writes the same SAN for every ply as python-chess`() {
        for (puzzle in all) {
            val expected = TestVectors.entries(checks.getValue(puzzle.id), "solution")
                .map { it.getValue("san").str }
            val game = Game(requireNotNull(puzzle.initialPosition))
            game.playLine(puzzle.solution)
            assertEquals("${puzzle.id}: score sheet", expected, game.sanMoves)
        }
    }

    @Test
    fun `every alternative is legal and really achieves the goal`() {
        var alternativesChecked = 0
        for (puzzle in all) {
            val start = requireNotNull(puzzle.initialPosition)
            for (uci in puzzle.alternativeSolutions) {
                alternativesChecked++
                val move = requireNotNull(Move.fromUci(uci)) { "${puzzle.id}: malformed alternative $uci" }
                assertTrue(
                    "${puzzle.id}: alternative $uci is not a legal move from ${puzzle.fen}",
                    MoveGenerator.isLegal(start, move),
                )
                when {
                    puzzle.matesIn <= 1 -> assertEquals(
                        "${puzzle.id}: alternative $uci must mate immediately",
                        GameStatus.CHECKMATE,
                        MoveGenerator.status(MoveGenerator.applyMove(start, move)),
                    )

                    else -> assertTrue(
                        "${puzzle.id}: alternative $uci must still force mate in ${puzzle.matesIn}",
                        forcesMateIn(start, move, puzzle.matesIn),
                    )
                }
            }
        }
        assertTrue("the bundled set should offer alternatives; checked none", alternativesChecked > 0)
    }

    @Test
    fun `the recorded solution is always among the accepted alternatives`() {
        for (puzzle in all) {
            if (puzzle.goal != PuzzleGoal.CHECKMATE) {
                assertTrue(
                    "${puzzle.id}: material puzzles are judged by the engine, so they need no alternatives",
                    puzzle.alternativeSolutions.isEmpty(),
                )
                continue
            }
            assertTrue(
                "${puzzle.id}: first ply ${puzzle.solution.first()} missing from alternatives",
                puzzle.solution.first() in puzzle.alternativeSolutions,
            )
        }
    }

    @Test
    fun `theme, goal and line length agree with each other`() {
        for (puzzle in all) {
            val solverPlies = (puzzle.solution.size + 1) / 2
            when (puzzle.theme) {
                PuzzleTheme.MATE_IN_ONE -> {
                    assertEquals("${puzzle.id}: goal", PuzzleGoal.CHECKMATE, puzzle.goal)
                    assertEquals("${puzzle.id}: matesIn", 1, puzzle.matesIn)
                    assertEquals("${puzzle.id}: plies", 1, puzzle.solution.size)
                    assertEquals("${puzzle.id}: solver moves", 1, solverPlies)
                    assertFalse("${puzzle.id}: a mate in one is never multi-move", puzzle.isMultiMove)
                }

                PuzzleTheme.MATE_IN_TWO -> {
                    assertEquals("${puzzle.id}: goal", PuzzleGoal.CHECKMATE, puzzle.goal)
                    assertEquals("${puzzle.id}: matesIn", 2, puzzle.matesIn)
                    assertEquals("${puzzle.id}: plies (solver, reply, solver)", 3, puzzle.solution.size)
                    assertEquals("${puzzle.id}: solver moves", 2, solverPlies)
                    assertTrue("${puzzle.id}: should be multi-move", puzzle.isMultiMove)
                }

                PuzzleTheme.WIN_MATERIAL -> {
                    assertEquals("${puzzle.id}: goal", PuzzleGoal.WIN_MATERIAL, puzzle.goal)
                    assertEquals("${puzzle.id}: matesIn", 0, puzzle.matesIn)
                    assertTrue(
                        "${puzzle.id}: materialGain must be positive, was ${puzzle.materialGain}",
                        puzzle.materialGain > 0,
                    )
                }
            }
            assertEquals("${puzzle.id}: solverMoves property", solverPlies, puzzle.solverMoves)
        }
    }

    @Test
    fun `identifiers, prose and difficulty are sane`() {
        val ids = all.map { it.id }
        assertEquals("duplicate puzzle ids", ids.size, ids.distinct().size)
        for (puzzle in all) {
            assertTrue("${puzzle.id}: blank id", puzzle.id.isNotBlank())
            assertTrue("${puzzle.id}: blank name", puzzle.name.isNotBlank())
            assertTrue("${puzzle.id}: blank hint", puzzle.hint.isNotBlank())
            assertTrue("${puzzle.id}: blank description", puzzle.description.isNotBlank())
            assertTrue("${puzzle.id}: unknown source '${puzzle.source}'", puzzle.source.isNotBlank())
            assertTrue(
                "${puzzle.id}: difficulty ${puzzle.difficulty} outside 1..5",
                puzzle.difficulty in 1..5,
            )
        }
    }

    @Test
    fun `the set exercises both colours, all themes and a range of difficulty`() {
        assertTrue(
            "at least one puzzle must have the solver playing Black, or the board-flip code is untested",
            all.any { it.solverColor == PieceColor.BLACK },
        )
        assertTrue(
            "at least one puzzle must have the solver playing White",
            all.any { it.solverColor == PieceColor.WHITE },
        )
        for (theme in PuzzleTheme.entries) {
            assertTrue("no bundled puzzle for theme $theme", BundledPuzzles.byTheme(theme).isNotEmpty())
        }
        assertEquals(PuzzleTheme.entries.size, all.map { it.theme }.distinct().size)
        val difficulties = all.map { it.difficulty }.distinct().sorted()
        assertEquals("expected difficulty 1 to be the floor", 1, difficulties.first())
        assertTrue("expected more than one difficulty level", difficulties.size > 1)
    }

    @Test
    fun `the lookup helpers filter as documented`() {
        assertEquals(all.first(), BundledPuzzles.byId(all.first().id))
        assertNull(BundledPuzzles.byId("no-such-puzzle"))

        val mates = BundledPuzzles.byTheme(PuzzleTheme.MATE_IN_ONE)
        assertTrue(mates.isNotEmpty())
        assertTrue(mates.all { it.theme == PuzzleTheme.MATE_IN_ONE })
        assertEquals(all.count { it.theme == PuzzleTheme.MATE_IN_ONE }, mates.size)

        val easiest = all.minOf { it.difficulty }
        val capped = BundledPuzzles.upToDifficulty(easiest)
        assertTrue(capped.isNotEmpty())
        assertTrue(capped.all { it.difficulty <= easiest })
        assertEquals(all.count { it.difficulty <= easiest }, capped.size)
        assertEquals(all.size, BundledPuzzles.upToDifficulty(5).size)
        assertTrue(BundledPuzzles.upToDifficulty(0).isEmpty())
    }

    @Test
    fun `the fallback puzzle is playable and mates, because the alarm must never dead-end`() {
        val fallback = Puzzle.FALLBACK
        assertNotNull("fallback FEN did not parse", fallback.initialPosition)
        assertTrue(fallback.isPlayable)
        assertEquals(PieceColor.WHITE, fallback.solverColor)

        val game = Game(requireNotNull(fallback.initialPosition))
        assertEquals(fallback.solution.size, game.playLine(fallback.solution))
        assertEquals(GameStatus.CHECKMATE, game.status())

        // Whatever the theme filter asks for, the repository always has something
        // it can hand back.
        for (theme in PuzzleTheme.entries) {
            assertTrue("no fallback for $theme", BundledPuzzles.byTheme(theme).isNotEmpty())
        }
        assertTrue(fallback.alternativeSolutions.contains(fallback.solution.first()))
    }

    /**
     * Whether [first] forces mate in [matesIn] solver moves: after every legal
     * opponent reply, the solver still has a move that finishes it.
     *
     * This is the definition the generator used when it recorded an alternative,
     * re-checked here with the app's own engine so a data edit cannot quietly
     * turn a "still wins" move into a move that merely looks nice.
     */
    private fun forcesMateIn(position: Position, first: Move, matesIn: Int): Boolean {
        require(matesIn >= 1)
        val afterFirst = MoveGenerator.applyMove(position, first)
        if (MoveGenerator.status(afterFirst) == GameStatus.CHECKMATE) return true
        if (matesIn == 1) return false

        for (reply in MoveGenerator.legalMoves(afterFirst)) {
            val afterReply = MoveGenerator.applyMove(afterFirst, reply)
            // The opponent moved, so a mate here would be *our* king mated.
            if (MoveGenerator.status(afterReply) == GameStatus.CHECKMATE) return false
            val finishes = MoveGenerator.legalMoves(afterReply).any { candidate ->
                forcesMateIn(afterReply, candidate, matesIn - 1)
            }
            if (!finishes) return false
        }
        return true
    }
}
