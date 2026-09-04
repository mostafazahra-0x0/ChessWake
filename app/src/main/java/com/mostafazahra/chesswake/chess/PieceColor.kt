package com.mostafazahra.chesswake.chess

/**
 * Which side a piece belongs to.
 *
 * Kept as its own enum (rather than a Boolean) so call sites such as
 * `isSquareAttacked(board, square, byColor = PieceColor.BLACK)` read clearly.
 */
enum class PieceColor {
    WHITE,
    BLACK,
    ;

    val opposite: PieceColor
        get() = if (this == WHITE) BLACK else WHITE

    /** Rank delta a pawn of this color advances by (White moves up, Black moves down). */
    val pawnDirection: Int
        get() = if (this == WHITE) 1 else -1

    /** 0-based rank on which this color's pawns start. */
    val pawnStartRank: Int
        get() = if (this == WHITE) 1 else 6

    /** 0-based rank at which this color's pawns promote. */
    val promotionRank: Int
        get() = if (this == WHITE) 7 else 0

    /** 0-based rank holding this color's back rank (king and rooks at game start). */
    val backRank: Int
        get() = if (this == WHITE) 0 else 7

    /** Character used for this color in the "active color" field of a FEN string. */
    val fenChar: Char
        get() = if (this == WHITE) 'w' else 'b'

    /** Human readable name, used in UI strings such as "White to move". */
    val displayName: String
        get() = if (this == WHITE) "White" else "Black"

    companion object {
        fun fromFenChar(char: Char): PieceColor? = when (char) {
            'w', 'W' -> WHITE
            'b', 'B' -> BLACK
            else -> null
        }
    }
}
