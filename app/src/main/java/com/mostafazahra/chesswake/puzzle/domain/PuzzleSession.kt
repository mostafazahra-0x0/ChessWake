package com.mostafazahra.chesswake.puzzle.domain

import com.mostafazahra.chesswake.chess.Game
import com.mostafazahra.chesswake.chess.GameStatus
import com.mostafazahra.chesswake.chess.Material
import com.mostafazahra.chesswake.chess.Move
import com.mostafazahra.chesswake.chess.MoveGenerator
import com.mostafazahra.chesswake.chess.MoveNotation
import com.mostafazahra.chesswake.chess.Position
import com.mostafazahra.chesswake.chess.Square

/** Where a puzzle currently stands. */
enum class PuzzlePhase {
    /** Waiting for the solver to play. */
    PLAYER_TO_MOVE,

    /** The puzzle is finished and the goal was reached. */
    SOLVED,

    /**
     * The puzzle could not be started — the bundled FEN or solution line did not
     * parse. Should never happen (the generator verifies both), but an alarm must
     * never dead-end, so the session degrades to this instead of crashing.
     */
    UNPLAYABLE,
}

/** The result of a tap on the board. */
sealed interface PlayOutcome {

    /**
     * The move was right.
     *
     * [opponentReply] is the move the engine auto-played in response, when the
     * puzzle continues; [solved] is true when the goal has now been reached.
     */
    data class Accepted(
        val move: Move,
        val san: String,
        val opponentReply: Move?,
        val opponentSan: String?,
        val solved: Boolean,
    ) : PlayOutcome

    /** A legal chess move, but not the one this puzzle is asking for. */
    data class Wrong(val move: Move, val san: String, val wrongAttempts: Int, val message: String) : PlayOutcome

    /** Not a legal chess move at all, or the puzzle is already over. */
    data class Illegal(val message: String) : PlayOutcome
}

/**
 * Plays a [Puzzle] forward, enforcing the rules with the real chess engine.
 *
 * Flow
 * ----
 * The solver's moves are checked against the recorded line, and the opponent's
 * replies are auto-played so the solver never has to think for the other side.
 * Two forms of generosity are built in, because this runs at 6am:
 *
 *  1. Any move that delivers checkmate solves a mate puzzle, even if it is not
 *     the recorded move — the engine settles it, so it cannot be wrong.
 *  2. Any move in [Puzzle.alternativeSolutions] is accepted.
 *
 * When the recorded opponent reply is somehow illegal (only possible with
 * hand-edited data), the engine plays a legal reply instead so the puzzle can
 * never get stuck waiting for a move that does not exist.
 *
 * Not thread-safe: owned by one ViewModel and only touched on the main thread.
 */
class PuzzleSession(val puzzle: Puzzle) {

    private val game: Game? = puzzle.initialPosition?.takeIf { puzzle.isPlayable }?.let { Game(it) }

    /** Current phase of the puzzle. */
    var phase: PuzzlePhase = if (game == null) PuzzlePhase.UNPLAYABLE else PuzzlePhase.PLAYER_TO_MOVE
        private set

    /** How many times the solver played a legal-but-wrong move. */
    var wrongAttempts: Int = 0
        private set

    /** How many of the solver's own moves have been accepted. */
    var acceptedSolverMoves: Int = 0
        private set

    /** The position on the board right now. */
    val position: Position
        get() = game?.position ?: puzzle.initialPosition ?: Position.start()

    /** Every move played so far, in SAN — shown as the move list. */
    val playedSans: List<String>
        get() = game?.sanMoves ?: emptyList()

    /** True when the solver is on move (rather than watching the opponent reply). */
    val isPlayerToMove: Boolean
        get() = phase == PuzzlePhase.PLAYER_TO_MOVE && position.sideToMove == puzzle.solverColor

    /** Which colour the solver plays, for board orientation. */
    val solverColor get() = puzzle.solverColor

    /** Legal moves right now; the UI uses this to highlight a tapped piece's targets. */
    fun legalMoves(): List<Move> = game?.legalMoves() ?: emptyList()

    /** Legal destinations from [from], used to draw move dots on the board. */
    fun legalDestinationsFrom(from: Square): List<Move> =
        legalMoves().filter { it.from == from }

    /** The most recent move by either side, so the UI can tint from/to squares. */
    fun lastMove(): Move? = game?.moves?.lastOrNull()

    /** True when the side to move is in check. */
    val isInCheck: Boolean get() = game?.isInCheck() ?: false

    /**
     * Plays [move] for the solver.
     *
     * Always returns a [PlayOutcome]; it never throws on a bad move, because the
     * caller is a half-awake person tapping a phone.
     */
    fun play(move: Move): PlayOutcome {
        val currentGame = game ?: return PlayOutcome.Illegal("This puzzle could not be loaded.")
        if (phase != PuzzlePhase.PLAYER_TO_MOVE) {
            return PlayOutcome.Illegal("This puzzle is already finished.")
        }
        if (position.sideToMove != puzzle.solverColor) {
            return PlayOutcome.Illegal("It is not your move yet.")
        }
        if (!MoveGenerator.isLegal(position, move)) {
            return PlayOutcome.Illegal("${move.from.name} to ${move.to.name} is not a legal move.")
        }

        val expected = puzzle.solutionMoves.getOrNull(acceptedSolverMoves * 2)
        val isRecordedMove = move == expected
        val isListedAlternative = move.uci in puzzle.alternativeSolutions
        val deliversMate = puzzle.goal == PuzzleGoal.CHECKMATE &&
            MoveGenerator.status(MoveGenerator.applyMove(position, move)) == GameStatus.CHECKMATE

        if (!isRecordedMove && !isListedAlternative && !deliversMate) {
            wrongAttempts++
            val san = MoveNotation.san(position, move)
            return PlayOutcome.Wrong(move, san, wrongAttempts, wrongMessage(wrongAttempts))
        }

        val san = MoveNotation.san(position, move)
        currentGame.makeMove(move)
        acceptedSolverMoves++

        // Goal reached by this move, or the recorded line ran out.
        if (goalReached(currentGame.position) || nextPlyIndex() >= puzzle.solutionMoves.size) {
            phase = PuzzlePhase.SOLVED
            return PlayOutcome.Accepted(move, san, opponentReply = null, opponentSan = null, solved = true)
        }

        // Auto-play the opponent's reply so the solver only ever thinks for one side.
        val recordedReply = puzzle.solutionMoves.getOrNull(nextPlyIndex())
        val reply = when {
            recordedReply != null && MoveGenerator.isLegal(currentGame.position, recordedReply) -> recordedReply
            else -> currentGame.legalMoves().firstOrNull()
        }
        if (reply == null) {
            // The opponent has no moves and is not mated: stalemate. Treat the
            // line as complete rather than leaving the board frozen.
            phase = PuzzlePhase.SOLVED
            return PlayOutcome.Accepted(move, san, opponentReply = null, opponentSan = null, solved = true)
        }

        val replySan = MoveNotation.san(currentGame.position, reply)
        currentGame.makeMove(reply)

        if (goalReached(currentGame.position) || nextPlyIndex() >= puzzle.solutionMoves.size) {
            phase = PuzzlePhase.SOLVED
            return PlayOutcome.Accepted(move, san, reply, replySan, solved = true)
        }

        return PlayOutcome.Accepted(move, san, reply, replySan, solved = false)
    }

    /** Restarts the puzzle from its initial position, clearing the attempt count. */
    fun reset() {
        game?.reset()
        wrongAttempts = 0
        acceptedSolverMoves = 0
        phase = if (game == null) PuzzlePhase.UNPLAYABLE else PuzzlePhase.PLAYER_TO_MOVE
    }

    /** True when the solver may now see [Puzzle.hint]; revealed after two misses. */
    val hintUnlocked: Boolean
        get() = wrongAttempts >= HINT_AFTER_WRONG_ATTEMPTS

    /**
     * Index of the next ply in [Puzzle.solutionMoves].
     *
     * Solver plies are even, opponent plies odd, so after `n` accepted solver
     * moves and their replies the cursor sits at `n * 2`.
     */
    private fun nextPlyIndex(): Int = (game?.plyCount ?: 0)

    /** Whether the puzzle's stated goal is now true on the board. */
    private fun goalReached(current: Position): Boolean {
        val start = puzzle.initialPosition ?: return false
        return when (puzzle.goal) {
            PuzzleGoal.CHECKMATE -> MoveGenerator.status(current) == GameStatus.CHECKMATE
            PuzzleGoal.WIN_MATERIAL -> {
                val gained = Material.balance(current.board, puzzle.solverColor) -
                    Material.balance(start.board, puzzle.solverColor)
                gained >= puzzle.materialGain
            }
        }
    }

    /** Feedback that gets firmer, then helpful, as misses pile up. */
    private fun wrongMessage(attempt: Int): String = when {
        attempt >= HINT_AFTER_WRONG_ATTEMPTS && puzzle.hint.isNotBlank() ->
            "Not that one. Hint: ${puzzle.hint}"

        attempt >= 3 -> "Take your time — look at the ${describeTarget()}."
        attempt == 2 -> "Close, but no. Try a different piece."
        else -> "Not quite. Have another look."
    }

    private fun describeTarget(): String = when (puzzle.goal) {
        PuzzleGoal.CHECKMATE -> "squares around the enemy king"
        PuzzleGoal.WIN_MATERIAL -> "undefended enemy pieces"
    }

    companion object {
        /** Misses before the bundled hint is offered. */
        const val HINT_AFTER_WRONG_ATTEMPTS = 2
    }
}
