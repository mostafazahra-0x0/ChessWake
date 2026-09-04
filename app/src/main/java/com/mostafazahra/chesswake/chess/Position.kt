package com.mostafazahra.chesswake.chess

/**
 * A complete chess position: the board plus the three pieces of state that are
 * not visible from the board alone — whose turn it is, which castling moves are
 * still legal, and where an en-passant capture may be played.
 *
 * Immutable: [MoveGenerator.applyMove] returns a new [Position] rather than
 * mutating this one, which is what makes undo/redo in the puzzle UI trivial.
 */
data class Position(
    val board: Board,
    val sideToMove: PieceColor = PieceColor.WHITE,
    val castlingRights: CastlingRights = CastlingRights.NONE,
    val enPassantTarget: Square? = null,
    val halfmoveClock: Int = 0,
    val fullmoveNumber: Int = 1,
) {

    fun pieceAt(square: Square): Piece? = board.pieceAt(square)

    /** Square of [color]'s king, or null if the position is malformed. */
    fun kingSquare(color: PieceColor): Square? = board.kingSquare(color)

    /** The square of the king belonging to whoever is about to move. */
    val ownKingSquare: Square? get() = board.kingSquare(sideToMove)

    /**
     * The key used for threefold-repetition detection.
     *
     * Only the pieces, the side to move and the *available* castling/en-passant
     * rights matter for repetition — the move counters do not.
     */
    val repetitionKey: String
        get() = "${board.toFenPlacement()} $sideToMove ${castlingRights.fen} ${enPassantTarget?.name ?: "-"}"

    /** Full FEN string, e.g. `8/8/8/3k4/8/8/8/R6K b - - 2 41`. */
    val fen: String
        get() = buildString {
            append(board.toFenPlacement())
            append(' ')
            append(sideToMove.fenChar)
            append(' ')
            append(castlingRights.fen)
            append(' ')
            append(enPassantTarget?.name ?: "-")
            append(' ')
            append(halfmoveClock)
            append(' ')
            append(fullmoveNumber)
        }

    /** True when both sides have exactly one king — the minimum for a legal position. */
    val hasExpectedKings: Boolean
        get() = board.kingSquare(PieceColor.WHITE) != null &&
            board.kingSquare(PieceColor.BLACK) != null

    override fun toString(): String = fen

    companion object {
        /** FEN of the standard starting position. */
        const val START_FEN =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        /** The standard starting position. */
        fun start(): Position = Position(
            board = Board.start(),
            sideToMove = PieceColor.WHITE,
            castlingRights = CastlingRights.START,
        )

        /**
         * Parses a FEN string.
         *
         * Tolerates a truncated FEN (placement only, or placement + side to move),
         * filling the remaining fields with sensible defaults. Returns null when
         * the placement is malformed or a side is missing its king, so a broken
         * bundled puzzle is reported instead of producing an unplayable board.
         */
        fun fromFen(fen: String): Position? {
            val fields = fen.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (fields.isEmpty()) return null

            val board = Board.fromFenPlacement(fields[0]) ?: return null
            if (board.kingSquare(PieceColor.WHITE) == null) return null
            if (board.kingSquare(PieceColor.BLACK) == null) return null

            val sideToMove = if (fields.size > 1) {
                PieceColor.fromFenChar(fields[1].firstOrNull() ?: 'w') ?: return null
            } else {
                PieceColor.WHITE
            }

            val castling = if (fields.size > 2) CastlingRights.parse(fields[2]) else CastlingRights.NONE

            val enPassant = if (fields.size > 3 && fields[3] != "-") {
                Square.parse(fields[3]) ?: return null
            } else {
                null
            }

            val halfmove = fields.getOrNull(4)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val fullmove = fields.getOrNull(5)?.toIntOrNull()?.coerceAtLeast(1) ?: 1

            return Position(
                board = board,
                sideToMove = sideToMove,
                castlingRights = castling,
                enPassantTarget = enPassant,
                halfmoveClock = halfmove,
                fullmoveNumber = fullmove,
            )
        }
    }
}
