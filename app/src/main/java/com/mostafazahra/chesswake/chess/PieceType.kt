package com.mostafazahra.chesswake.chess

/**
 * The six chess piece kinds, independent of colour.
 *
 * Declaration order is significant: it is used as the dense index in
 * [Piece.code], so never reorder or insert entries without bumping the
 * encoding version.
 */
enum class PieceType {
    PAWN,
    KNIGHT,
    BISHOP,
    ROOK,
    QUEEN,
    KING,
    ;

    /** Lower-case character used for this piece in a FEN placement string. */
    val fenChar: Char
        get() = when (this) {
            PAWN -> 'p'
            KNIGHT -> 'n'
            BISHOP -> 'b'
            ROOK -> 'r'
            QUEEN -> 'q'
            KING -> 'k'
        }

    /**
     * Filled ("black") Unicode glyph.
     *
     * The filled set is used for both colours — the outline glyphs (U+2654..) render
     * inconsistently across Android font stacks and often collapse to a hollow box,
     * whereas U+265A..U+265F are present in every shipped font. Colour is applied by
     * the board renderer instead.
     */
    val unicodeGlyph: String
        get() = when (this) {
            PAWN -> "\u265F"
            KNIGHT -> "\u265E"
            BISHOP -> "\u265D"
            ROOK -> "\u265C"
            QUEEN -> "\u265B"
            KING -> "\u265A"
        }

    /** Standard centipawn value; the king is priceless and scores 0. */
    val centipawns: Int
        get() = when (this) {
            PAWN -> 100
            KNIGHT -> 320
            BISHOP -> 330
            ROOK -> 500
            QUEEN -> 900
            KING -> 0
        }

    /** Bishops, rooks and queens move along rays; pawns, knights and kings do not. */
    val isSlider: Boolean
        get() = this == BISHOP || this == ROOK || this == QUEEN

    /** Only pawns promote and only pawns create an en-passant target. */
    val isPawn: Boolean
        get() = this == PAWN

    companion object {
        /** Parses the piece letter from a FEN string, accepting either case. */
        fun fromFenChar(char: Char): PieceType? = entries.firstOrNull { it.fenChar == char.lowercaseChar() }

        /** Piece types a pawn may under-promote to (king and pawn are illegal). */
        val promotionChoices: List<PieceType> = listOf(QUEEN, ROOK, BISHOP, KNIGHT)
    }
}
