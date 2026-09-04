package com.mostafazahra.chesswake.puzzle.domain

import com.mostafazahra.chesswake.chess.Move
import com.mostafazahra.chesswake.chess.PieceType
import com.mostafazahra.chesswake.chess.Square

/** What a tap on the board means, decided by the engine rather than by the UI. */
sealed interface TapResult {

    /** Select this square's piece; its legal destinations should be highlighted. */
    data class Select(val square: Square) : TapResult

    /** Put the currently selected piece down without moving it. */
    data object Deselect : TapResult

    /** Play this legal move. */
    data class Play(val move: Move) : TapResult

    /** The tap cannot be acted on. */
    data class Reject(val reason: RejectReason) : TapResult
}

/** Why a tap was rejected. The UI decides how loudly to complain about each. */
enum class RejectReason {
    /** The puzzle is over, or it is the opponent's turn. */
    NOT_YOUR_TURN,

    /** A legal-looking destination that the engine says is not reachable. */
    ILLEGAL_DESTINATION,

    /** The tapped piece belongs to the other side. */
    OPPONENT_PIECE,

    /** An empty square was tapped with nothing selected. Usually ignored silently. */
    EMPTY_SQUARE,
}

/**
 * Turns board taps into moves.
 *
 * Shared by the alarm and practice screens so that both behave identically, and
 * kept free of Android types so the rules it encodes can be unit-tested on the
 * JVM:
 *
 *  - tapping a selected square deselects it;
 *  - tapping another of your own pieces switches the selection instead of
 *    reporting an illegal move, which is what every chess UI does;
 *  - promotion is auto-queened, because a promotion picker at 6am is cruelty and
 *    no puzzle in the bundled set needs an underpromotion.
 */
object PuzzleTap {

    fun resolve(session: PuzzleSession, selected: Square?, tapped: Square): TapResult {
        if (session.phase != PuzzlePhase.PLAYER_TO_MOVE) {
            return TapResult.Reject(RejectReason.NOT_YOUR_TURN)
        }

        val solver = session.solverColor
        val piece = session.position.pieceAt(tapped)

        return when {
            selected == tapped -> TapResult.Deselect

            selected != null -> {
                val move = pickMove(session, selected, tapped)
                when {
                    move != null -> TapResult.Play(move)
                    piece != null && piece.color == solver -> TapResult.Select(tapped)
                    else -> TapResult.Reject(RejectReason.ILLEGAL_DESTINATION)
                }
            }

            piece == null -> TapResult.Reject(RejectReason.EMPTY_SQUARE)
            piece.color != solver -> TapResult.Reject(RejectReason.OPPONENT_PIECE)
            else -> TapResult.Select(tapped)
        }
    }

    /**
     * The move from [from] to [to], or null when no legal move connects them.
     *
     * Only promotions produce more than one candidate for the same pair of
     * squares; the queen is chosen automatically.
     */
    fun pickMove(session: PuzzleSession, from: Square, to: Square): Move? {
        val candidates = session.legalDestinationsFrom(from).filter { it.to == to }
        return candidates.firstOrNull { it.promotion == PieceType.QUEEN } ?: candidates.firstOrNull()
    }
}
