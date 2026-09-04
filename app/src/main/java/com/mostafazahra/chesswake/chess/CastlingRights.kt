package com.mostafazahra.chesswake.chess

/**
 * Which castling moves are still available.
 *
 * Modelled as four explicit flags rather than a bitmask so that the FEN
 * round-trip and the `rights.copy(...)` updates in [MoveGenerator] stay
 * obviously correct and readable.
 */
data class CastlingRights(
    val whiteKingSide: Boolean = false,
    val whiteQueenSide: Boolean = false,
    val blackKingSide: Boolean = false,
    val blackQueenSide: Boolean = false,
) {

    fun canCastle(color: PieceColor, kingSide: Boolean): Boolean = when {
        color == PieceColor.WHITE && kingSide -> whiteKingSide
        color == PieceColor.WHITE && !kingSide -> whiteQueenSide
        color == PieceColor.BLACK && kingSide -> blackKingSide
        else -> blackQueenSide
    }

    /** Returns these rights with one specific option removed. */
    fun without(color: PieceColor, kingSide: Boolean): CastlingRights = when {
        color == PieceColor.WHITE && kingSide -> copy(whiteKingSide = false)
        color == PieceColor.WHITE -> copy(whiteQueenSide = false)
        color == PieceColor.BLACK && kingSide -> copy(blackKingSide = false)
        else -> copy(blackQueenSide = false)
    }

    /** Returns these rights with both options for [color] removed (the king moved). */
    fun withoutColor(color: PieceColor): CastlingRights =
        if (color == PieceColor.WHITE) copy(whiteKingSide = false, whiteQueenSide = false)
        else copy(blackKingSide = false, blackQueenSide = false)

    /** True when neither side can castle any more. */
    val isEmpty: Boolean
        get() = !whiteKingSide && !whiteQueenSide && !blackKingSide && !blackQueenSide

    /** Serialises to the third FEN field, using `-` when nothing is left. */
    val fen: String
        get() = buildString {
            if (whiteKingSide) append('K')
            if (whiteQueenSide) append('Q')
            if (blackKingSide) append('k')
            if (blackQueenSide) append('q')
        }.ifEmpty { NONE_FEN }

    companion object {
        const val NONE_FEN = "-"

        /** Nobody can castle — the correct default for puzzle positions. */
        val NONE = CastlingRights()

        /** The full `KQkq` rights of the standard starting position. */
        val START = CastlingRights(
            whiteKingSide = true,
            whiteQueenSide = true,
            blackKingSide = true,
            blackQueenSide = true,
        )

        /**
         * Parses the castling field of a FEN string.
         *
         * Unknown characters are ignored rather than rejected, which keeps the
         * parser tolerant of Chess960-style `AHah` notation without crashing.
         */
        fun parse(fen: String): CastlingRights {
            if (fen == NONE_FEN) return NONE
            return CastlingRights(
                whiteKingSide = 'K' in fen,
                whiteQueenSide = 'Q' in fen,
                blackKingSide = 'k' in fen,
                blackQueenSide = 'q' in fen,
            )
        }
    }
}
