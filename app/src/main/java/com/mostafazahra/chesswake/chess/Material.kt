package com.mostafazahra.chesswake.chess

/**
 * Material accounting, used to judge "win material" puzzles and to show a
 * running material score in the practice screen.
 */
object Material {

    /** Total centipawn value of everything [color] owns. */
    fun total(board: Board, color: PieceColor): Int = board.materialCount(color)

    /**
     * Centipawns [color] is ahead by (negative when behind).
     *
     * The king is worth 0 so it never distorts the balance.
     */
    fun balance(board: Board, color: PieceColor): Int =
        board.materialCount(color) - board.materialCount(color.opposite)

    /** How many whole pawns' worth [color] is ahead by, for display ("+2"). */
    fun balanceInPawns(board: Board, color: PieceColor): Int = balance(board, color) / 100

    /**
     * Counts pieces per type per colour, e.g. for a captured-pieces tray.
     *
     * Returns the count of each type that [color] has *lost* relative to the
     * standard army, which is what a captured tray shows.
     */
    fun capturedPieces(board: Board, color: PieceColor): List<PieceType> {
        val startCounts = mapOf(
            PieceType.PAWN to 8,
            PieceType.KNIGHT to 2,
            PieceType.BISHOP to 2,
            PieceType.ROOK to 2,
            PieceType.QUEEN to 1,
            PieceType.KING to 0,
        )
        val captured = mutableListOf<PieceType>()
        for (type in PieceType.entries) {
            val remaining = board.squaresOf(color, type).size
            val missing = (startCounts[type] ?: 0) - remaining
            repeat(missing.coerceAtLeast(0)) { captured += type }
        }
        // Show the expensive pieces first; that is the order readers expect.
        return captured.sortedByDescending { it.centipawns }
    }
}
