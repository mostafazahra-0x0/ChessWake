package com.mostafazahra.chesswake.chess

/**
 * A piece kind plus the colour that owns it.
 *
 * There are only twelve possible values and they are all pre-created in
 * [ALL], so a board can be stored as `Array<Piece?>` without ever allocating
 * a new [Piece] during move generation.
 */
data class Piece(val type: PieceType, val color: PieceColor) {

    /** FEN character: upper case for White (`R`), lower case for Black (`r`). */
    val fenChar: Char
        get() = if (color == PieceColor.WHITE) type.fenChar.uppercaseChar() else type.fenChar

    val isWhite: Boolean get() = color == PieceColor.WHITE
    val isBlack: Boolean get() = color == PieceColor.BLACK

    /**
     * Dense integer encoding used inside [Board].
     *
     * `0` means "empty square"; White pieces occupy 1..6 and Black pieces 7..12.
     */
    val code: Int
        get() = color.ordinal * 6 + type.ordinal + 1

    override fun toString(): String = fenChar.toString()

    companion object {
        val WHITE_PAWN = Piece(PieceType.PAWN, PieceColor.WHITE)
        val WHITE_KNIGHT = Piece(PieceType.KNIGHT, PieceColor.WHITE)
        val WHITE_BISHOP = Piece(PieceType.BISHOP, PieceColor.WHITE)
        val WHITE_ROOK = Piece(PieceType.ROOK, PieceColor.WHITE)
        val WHITE_QUEEN = Piece(PieceType.QUEEN, PieceColor.WHITE)
        val WHITE_KING = Piece(PieceType.KING, PieceColor.WHITE)

        val BLACK_PAWN = Piece(PieceType.PAWN, PieceColor.BLACK)
        val BLACK_KNIGHT = Piece(PieceType.KNIGHT, PieceColor.BLACK)
        val BLACK_BISHOP = Piece(PieceType.BISHOP, PieceColor.BLACK)
        val BLACK_ROOK = Piece(PieceType.ROOK, PieceColor.BLACK)
        val BLACK_QUEEN = Piece(PieceType.QUEEN, PieceColor.BLACK)
        val BLACK_KING = Piece(PieceType.KING, PieceColor.BLACK)

        val ALL: List<Piece> = listOf(
            WHITE_PAWN, WHITE_KNIGHT, WHITE_BISHOP, WHITE_ROOK, WHITE_QUEEN, WHITE_KING,
            BLACK_PAWN, BLACK_KNIGHT, BLACK_BISHOP, BLACK_ROOK, BLACK_QUEEN, BLACK_KING,
        )

        private val BY_CODE: Array<Piece?> = arrayOfNulls<Piece>(13).also { table ->
            ALL.forEach { table[it.code] = it }
        }

        /** Inverse of [code]; returns null for 0 (empty) or an out-of-range value. */
        fun fromCode(code: Int): Piece? = BY_CODE.getOrNull(code)

        fun of(type: PieceType, color: PieceColor): Piece = when (color) {
            PieceColor.WHITE -> when (type) {
                PieceType.PAWN -> WHITE_PAWN
                PieceType.KNIGHT -> WHITE_KNIGHT
                PieceType.BISHOP -> WHITE_BISHOP
                PieceType.ROOK -> WHITE_ROOK
                PieceType.QUEEN -> WHITE_QUEEN
                PieceType.KING -> WHITE_KING
            }

            PieceColor.BLACK -> when (type) {
                PieceType.PAWN -> BLACK_PAWN
                PieceType.KNIGHT -> BLACK_KNIGHT
                PieceType.BISHOP -> BLACK_BISHOP
                PieceType.ROOK -> BLACK_ROOK
                PieceType.QUEEN -> BLACK_QUEEN
                PieceType.KING -> BLACK_KING
            }
        }

        /** Parses a single FEN placement character such as `Q` or `p`. */
        fun fromFenChar(char: Char): Piece? {
            val type = PieceType.fromFenChar(char) ?: return null
            val color = if (char.isUpperCase()) PieceColor.WHITE else PieceColor.BLACK
            return of(type, color)
        }
    }
}
