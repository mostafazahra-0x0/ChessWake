package com.mostafazahra.chesswake.chess

/**
 * The 64 squares and what stands on them, with no knowledge of whose turn it is.
 *
 * Instances are treated as immutable by everything outside [MoveGenerator],
 * which uses [copy] + [set] to produce a successor board. [set] is `internal`
 * so that accidental in-place mutation from the UI layer is a compile error
 * rather than a subtle bug.
 */
class Board internal constructor(internal val squares: Array<Piece?>) {

    /** The piece standing on [square], or null when the square is empty. */
    fun pieceAt(square: Square): Piece? = squares[square.index]

    fun isEmpty(square: Square): Boolean = squares[square.index] == null

    fun isOccupied(square: Square): Boolean = squares[square.index] != null

    /** True when [square] holds a piece owned by [color]. */
    fun isOwnedBy(square: Square, color: PieceColor): Boolean =
        squares[square.index]?.color == color

    /** All squares holding a specific piece of a specific colour. */
    fun squaresOf(color: PieceColor, type: PieceType): List<Square> {
        val target = Piece.of(type, color)
        val result = ArrayList<Square>(10)
        for (index in 0 until 64) {
            if (squares[index] == target) result += Square(index)
        }
        return result
    }

    /** The square of [color]'s king, or null if it has been (impossibly) removed. */
    fun kingSquare(color: PieceColor): Square? {
        val target = Piece.of(PieceType.KING, color)
        for (index in 0 until 64) {
            if (squares[index] == target) return Square(index)
        }
        return null
    }

    /** Every square holding a piece owned by [color]. */
    fun occupiedBy(color: PieceColor): List<Square> {
        val result = ArrayList<Square>(16)
        for (index in 0 until 64) {
            if (squares[index]?.color == color) result += Square(index)
        }
        return result
    }

    /** Total centipawn value of everything [color] owns, used by the stats screen. */
    fun materialCount(color: PieceColor): Int {
        var total = 0
        for (index in 0 until 64) {
            val piece = squares[index]
            if (piece != null && piece.color == color) total += piece.type.centipawns
        }
        return total
    }

    /** Counts pieces, ignoring colour. Useful for insufficient-material detection. */
    fun countOf(type: PieceType): Int {
        var count = 0
        for (index in 0 until 64) {
            if (squares[index]?.type == type) count++
        }
        return count
    }

    /** Places or removes a piece. Internal: only the move generator mutates boards. */
    internal fun set(square: Square, piece: Piece?) {
        squares[square.index] = piece
    }

    /** An independent deep-enough copy; [Piece] instances are shared singletons. */
    fun copy(): Board = Board(squares.copyOf())

    /**
     * FEN piece-placement field, e.g. `rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR`.
     *
     * Written out from rank 8 down to rank 1, matching the FEN specification.
     */
    fun toFenPlacement(): String = buildString {
        for (rank in 7 downTo 0) {
            var emptyRun = 0
            for (file in 0..7) {
                val piece = squares[(rank shl 3) or file]
                if (piece == null) {
                    emptyRun++
                } else {
                    if (emptyRun > 0) append(emptyRun)
                    emptyRun = 0
                    append(piece.fenChar)
                }
            }
            if (emptyRun > 0) append(emptyRun)
            if (rank > 0) append('/')
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Board && squares.contentEquals(other.squares))

    override fun hashCode(): Int = squares.contentHashCode()

    override fun toString(): String = buildString {
        for (rank in 7 downTo 0) {
            append("${rank + 1} ")
            for (file in 0..7) {
                append(squares[(rank shl 3) or file]?.fenChar ?: '.')
                append(' ')
            }
            append('\n')
        }
        append("  a b c d e f g h")
    }

    companion object {
        /** A board with nothing on it. */
        fun empty(): Board = Board(arrayOfNulls(64))

        /**
         * Parses the piece-placement field of a FEN string.
         *
         * Returns null when the field is malformed: wrong number of ranks, a rank
         * that does not total 8 squares, or an unrecognised piece character.
         */
        fun fromFenPlacement(placement: String): Board? {
            val board = empty()
            val ranks = placement.split('/')
            if (ranks.size != 8) return null

            // FEN lists rank 8 first, which is rank index 7.
            ranks.forEachIndexed { offset, rankText ->
                val rank = 7 - offset
                var file = 0
                for (char in rankText) {
                    if (char.isDigit()) {
                        val emptyCount = char - '0'
                        // '0' is not legal FEN and would silently misalign the rank.
                        if (emptyCount !in 1..8) return null
                        file += emptyCount
                    } else {
                        val piece = Piece.fromFenChar(char) ?: return null
                        if (file > 7) return null
                        board.set(Square.of(file, rank), piece)
                        file++
                    }
                }
                if (file != 8) return null
            }
            return board
        }

        /** The board of the standard starting position. */
        fun start(): Board {
            val board = empty()
            val backRank = listOf(
                PieceType.ROOK, PieceType.KNIGHT, PieceType.BISHOP, PieceType.QUEEN,
                PieceType.KING, PieceType.BISHOP, PieceType.KNIGHT, PieceType.ROOK,
            )
            backRank.forEachIndexed { file, type ->
                board.set(Square.of(file, 0), Piece.of(type, PieceColor.WHITE))
                board.set(Square.of(file, 7), Piece.of(type, PieceColor.BLACK))
            }
            for (file in 0..7) {
                board.set(Square.of(file, 1), Piece.WHITE_PAWN)
                board.set(Square.of(file, 6), Piece.BLACK_PAWN)
            }
            return board
        }
    }
}
