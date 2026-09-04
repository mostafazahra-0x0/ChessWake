package com.mostafazahra.chesswake.chess

/** Where a position stands, from the point of view of the side to move. */
enum class GameStatus {
    /** The side to move has legal moves and is not in check. */
    ONGOING,

    /** The side to move is in check but can still defend. */
    CHECK,

    /** The side to move is in check and has no legal reply. Terminal, decisive. */
    CHECKMATE,

    /** The side to move is not in check and has no legal reply. Terminal, draw. */
    STALEMATE,

    /** Neither side has enough material to mate. Terminal, draw. */
    INSUFFICIENT_MATERIAL,

    /** 50 moves (100 plies) without a pawn move or a capture. Terminal, draw. */
    FIFTY_MOVE_RULE,
    ;

    /** True when no further move can be played. */
    val isTerminal: Boolean
        get() = this == CHECKMATE || this == STALEMATE ||
            this == INSUFFICIENT_MATERIAL || this == FIFTY_MOVE_RULE

    /** True for the terminal states that are not a win for either side. */
    val isDraw: Boolean
        get() = this == STALEMATE || this == INSUFFICIENT_MATERIAL || this == FIFTY_MOVE_RULE

    /** True when the side to move has just been checkmated. */
    val isCheckmate: Boolean
        get() = this == CHECKMATE

    /** True for the two states where the king is under attack. */
    val isCheck: Boolean
        get() = this == CHECK || this == CHECKMATE
}
