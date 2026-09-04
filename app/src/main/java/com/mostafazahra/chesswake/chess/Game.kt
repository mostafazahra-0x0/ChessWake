package com.mostafazahra.chesswake.chess

/**
 * A position plus the moves played from it, with undo.
 *
 * The puzzle screen drives one of these: the user's moves alternate with the
 * auto-played opponent replies that make up a puzzle's solution line.
 *
 * Not thread-safe by design — it is owned by a single ViewModel and only ever
 * touched from the main dispatcher.
 */
class Game(val initialPosition: Position) {

    /** The position to move in right now. */
    var position: Position = initialPosition
        private set

    private val playedMoves = mutableListOf<Move>()
    private val playedSans = mutableListOf<String>()

    /** Every position reached so far, starting with [initialPosition]. Used for repetition detection. */
    private val positionHistory = mutableListOf(initialPosition)

    /** Number of moves played so far (each move is one ply). */
    val plyCount: Int get() = playedMoves.size

    val moves: List<Move> get() = playedMoves.toList()
    val sanMoves: List<String> get() = playedSans.toList()
    val uciMoves: List<String> get() = playedMoves.map { it.uci }

    /** Move numbers as shown in a score sheet: `1. e4 c5`. */
    val scoreSheet: String
        get() = MoveNotation.pgnMoveText(
            sans = playedSans,
            startsWithWhite = initialPosition.sideToMove == PieceColor.WHITE,
            initialFullmoveNumber = initialPosition.fullmoveNumber,
        )

    /** Legal moves in the current position. */
    fun legalMoves(): List<Move> = MoveGenerator.legalMoves(position)

    /** Current game state (ongoing, check, mate, stalemate, ...). */
    fun status(): GameStatus = MoveGenerator.status(position)

    fun isInCheck(): Boolean = MoveGenerator.isInCheck(position)

    /** Whose turn it is right now. */
    val sideToMove: PieceColor get() = position.sideToMove

    /**
     * Plays [move] if it is legal, recording its SAN *before* the position changes.
     *
     * @return true when the move was played.
     */
    fun makeMove(move: Move): Boolean {
        if (!MoveGenerator.isLegal(position, move)) return false
        playedSans += MoveNotation.san(position, move)
        playedMoves += move
        position = MoveGenerator.applyMove(position, move)
        positionHistory += position
        return true
    }

    /** Convenience overload for solution data, which is stored as UCI strings. */
    fun makeMove(uci: String): Boolean {
        val move = Move.fromUci(uci) ?: return false
        return makeMove(move)
    }

    /**
     * Plays every move of a solution line in order.
     *
     * @return the number of plies actually played; anything less than
     *   `line.size` means the line stopped being legal part way through.
     */
    fun playLine(line: List<String>): Int {
        var played = 0
        for (uci in line) {
            if (!makeMove(uci)) break
            played++
        }
        return played
    }

    /** Reverts the most recent move, or null when the game is already at its start. */
    fun undo(): Move? {
        val move = playedMoves.removeLastOrNull() ?: return null
        playedSans.removeAt(playedSans.lastIndex)
        positionHistory.removeAt(positionHistory.lastIndex)
        position = replayFromScratch()
        return move
    }

    /** Reverts the last [count] moves. */
    fun undo(count: Int) {
        repeat(count) { undo() }
    }

    /**
     * Restarts the game at [initialPosition].
     *
     * Cheap enough for a puzzle (a handful of plies) and it guarantees the board
     * cannot drift from the recorded move list.
     */
    fun reset() {
        playedMoves.clear()
        playedSans.clear()
        positionHistory.clear()
        positionHistory += initialPosition
        position = initialPosition
    }

    /** How many times the current position has occurred, including now. */
    fun repetitionCount(): Int {
        val key = position.repetitionKey
        return positionHistory.count { it.repetitionKey == key }
    }

    /** True when the current position has occurred three times (threefold repetition). */
    fun isThreefoldRepetition(): Boolean = repetitionCount() >= 3

    /**
     * Rebuilds [position] by replaying [playedMoves] from [initialPosition].
     *
     * Replaying rather than storing snapshots keeps memory flat and removes any
     * chance of the "current position" disagreeing with the move list.
     */
    private fun replayFromScratch(): Position {
        var replayed = initialPosition
        for (move in playedMoves) replayed = MoveGenerator.applyMove(replayed, move)
        return replayed
    }

    override fun toString(): String = "Game(${position.fen}, plies=$plyCount)"
}
