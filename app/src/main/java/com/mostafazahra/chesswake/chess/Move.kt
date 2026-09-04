package com.mostafazahra.chesswake.chess

/**
 * A move expressed as origin, destination and optional promotion piece.
 *
 * Everything else about a move — whether it is a capture, which rook moves when
 * castling, whether it is an en-passant capture — is derived from the [Position]
 * the move is played in, and deliberately *not* stored here. Storing it would
 * create two sources of truth that can disagree.
 */
data class Move(
    val from: Square,
    val to: Square,
    val promotion: PieceType? = null,
) {

    /**
     * Long algebraic / UCI notation: `e2e4`, `g1f3`, `e7e8q`.
     *
     * This is the wire format used by bundled puzzle data because it is
     * unambiguous (unlike SAN) and trivial to parse.
     */
    val uci: String
        get() = from.name + to.name + (promotion?.fenChar ?: "")

    /** True when a pawn reaches the last rank and becomes something else. */
    val isPromotion: Boolean get() = promotion != null

    override fun toString(): String = uci

    companion object {
        /**
         * Parses UCI/LAN notation such as `e2e4` or `e7e8q`.
         *
         * Returns null for malformed input, and rejects promotion to a pawn or a
         * king, which are illegal in chess but easy to write by accident in data.
         */
        fun fromUci(uci: String): Move? {
            val text = uci.trim().lowercase()
            if (text.length != 4 && text.length != 5) return null
            val from = Square.parse(text.substring(0, 2)) ?: return null
            val to = Square.parse(text.substring(2, 4)) ?: return null
            if (from == to) return null
            val promotion = if (text.length == 5) {
                val type = PieceType.fromFenChar(text[4]) ?: return null
                if (type == PieceType.PAWN || type == PieceType.KING) return null
                type
            } else {
                null
            }
            return Move(from, to, promotion)
        }

        /**
         * Parses a list of UCI moves, e.g. a puzzle solution line.
         *
         * Returns null if *any* entry is malformed, so a partially broken puzzle
         * never reaches the board.
         */
        fun parseUciLine(line: List<String>): List<Move>? = line.map { fromUci(it) ?: return null }
    }
}
