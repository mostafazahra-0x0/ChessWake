package com.mostafazahra.chesswake.puzzle.domain

import com.mostafazahra.chesswake.chess.Move
import com.mostafazahra.chesswake.chess.PieceColor
import com.mostafazahra.chesswake.chess.Position

/**
 * A chess puzzle: a position, a goal, and the line that achieves it.
 *
 * The [solution] is a list of UCI plies (`e2e4`) alternating between the solver
 * and the opponent, always starting with the solver. It is machine-generated and
 * machine-verified — see `tools/generate_puzzles.py`, which refuses to emit a
 * puzzle whose line does not actually achieve the stated goal.
 *
 * [alternativeSolutions] lists other first moves that also satisfy the goal. It
 * is only populated for mate puzzles, because those are judged by the engine
 * ("did it mate?") rather than by matching a recorded line, so any equivalent
 * move is safe to accept.
 */
data class Puzzle(
    val id: String,
    val name: String,
    val theme: PuzzleTheme,
    val goal: PuzzleGoal,
    /** 1 (obvious) to 5 (needs real thought). */
    val difficulty: Int,
    val fen: String,
    val solution: List<String>,
    val alternativeSolutions: Set<String> = emptySet(),
    /** How many of the solver's own moves the solution needs; 0 for non-mate goals. */
    val matesIn: Int = 0,
    /** Centipawns the solution wins, for [PuzzleGoal.WIN_MATERIAL] puzzles. */
    val materialGain: Int = 0,
    val hint: String = "",
    val description: String = "",
    /** Where the puzzle came from: `curated` or `generated`. */
    val source: String = "",
) {

    /** The starting position, or null if the bundled FEN is somehow unparseable. */
    val initialPosition: Position? by lazy(LazyThreadSafetyMode.NONE) { Position.fromFen(fen) }

    /** Whose move it is at the start — i.e. whose move the solver plays. */
    val solverColor: PieceColor
        get() = initialPosition?.sideToMove ?: PieceColor.WHITE

    /** The solution parsed into moves; empty if any ply is malformed. */
    val solutionMoves: List<Move> by lazy(LazyThreadSafetyMode.NONE) {
        Move.parseUciLine(solution) ?: emptyList()
    }

    /** True when the position and the whole solution line parsed cleanly. */
    val isPlayable: Boolean
        get() = initialPosition != null && solutionMoves.size == solution.size

    /** Number of plies the solver plays, i.e. the ceiling of half the line. */
    val solverMoves: Int
        get() = (solutionMoves.size + 1) / 2

    /** Puzzles that need more than one move from the solver. */
    val isMultiMove: Boolean
        get() = solverMoves > 1

    /** Short label for chips and lists, e.g. "Mate in one · 2". */
    val summary: String
        get() = "${theme.displayName} · difficulty $difficulty"

    companion object {
        /**
         * The fallback puzzle used if the bundled set somehow fails to load.
         *
         * A rook lift to a8 mates a king boxed in behind its own pawns. Keeping
         * one hardcoded position here means the alarm is never unsolvable.
         */
        val FALLBACK = Puzzle(
            id = "fallback",
            name = "Back-Rank Mate",
            theme = PuzzleTheme.MATE_IN_ONE,
            goal = PuzzleGoal.CHECKMATE,
            difficulty = 1,
            fen = "6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1",
            solution = listOf("a1a8"),
            alternativeSolutions = setOf("a1a8"),
            matesIn = 1,
            hint = "The rook belongs on the eighth rank.",
            description = "White to move. Find the mate in one.",
            source = "fallback",
        )
    }
}
