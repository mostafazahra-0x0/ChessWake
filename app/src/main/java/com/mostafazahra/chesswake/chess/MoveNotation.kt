package com.mostafazahra.chesswake.chess

/**
 * Conversion between moves and the notations humans read.
 *
 * Two forms are used in the app:
 *  - **UCI / long algebraic** (`e2e4`, `e7e8q`) — unambiguous, used for bundled
 *    puzzle data and for anything persisted.
 *  - **SAN / standard algebraic** (`Nf3`, `exd5`, `O-O`, `Qh7#`) — shown in the
 *    puzzle UI and in the move history.
 */
object MoveNotation {

    private const val KING_SIDE_CASTLING = "O-O"
    private const val QUEEN_SIDE_CASTLING = "O-O-O"

    /**
     * Standard algebraic notation for [move] in [position].
     *
     * Handles disambiguation (two knights that can both reach d4), pawn-capture
     * file prefixes, promotion suffixes, and the trailing `+` / `#`.
     *
     * Returns the UCI string as a fallback if the move is not legal in this
     * position, so a caller never crashes on bad data.
     */
    fun san(position: Position, move: Move): String {
        if (!MoveGenerator.isLegal(position, move)) return move.uci

        val piece = position.pieceAt(move.from) ?: return move.uci

        if (MoveGenerator.isCastling(position, move)) {
            return withSuffix(position, move, if (move.to.file == 6) KING_SIDE_CASTLING else QUEEN_SIDE_CASTLING)
        }

        val isCapture = MoveGenerator.isCapture(position, move)
        val body = if (piece.type.isPawn) {
            buildString {
                if (isCapture) {
                    append('a' + move.from.file)
                    append('x')
                }
                append(move.to.name)
                move.promotion?.let { append('='); append(it.fenChar.uppercaseChar()) }
            }
        } else {
            buildString {
                append(piece.type.fenChar.uppercaseChar())
                append(disambiguation(position, move, piece))
                if (isCapture) append('x')
                append(move.to.name)
            }
        }

        return withSuffix(position, move, body)
    }

    /**
     * SAN for a whole list of moves played from [start], e.g. a puzzle solution line.
     *
     * Returns null if any move in the line is illegal, so malformed puzzle data is
     * surfaced instead of being silently half-translated.
     */
    fun sanLine(start: Position, moves: List<Move>): List<String>? {
        val result = ArrayList<String>(moves.size)
        var position = start
        for (move in moves) {
            if (!MoveGenerator.isLegal(position, move)) return null
            result += san(position, move)
            position = MoveGenerator.applyMove(position, move)
        }
        return result
    }

    /**
     * Minimal disambiguation, following the standard rules:
     * prefer the file, fall back to the rank, and use the full square only when
     * neither on its own is enough (three knights, say).
     */
    private fun disambiguation(position: Position, move: Move, piece: Piece): String {
        val rivals = MoveGenerator.legalMoves(position).filter {
            it.to == move.to && it.from != move.from && position.pieceAt(it.from) == piece
        }
        if (rivals.isEmpty()) return ""

        val sharesFile = rivals.any { it.from.file == move.from.file }
        val sharesRank = rivals.any { it.from.rank == move.from.rank }

        return when {
            !sharesFile -> ('a' + move.from.file).toString()
            !sharesRank -> (move.from.rank + 1).toString()
            else -> move.from.name
        }
    }

    /** Appends `+` for check and `#` for checkmate. */
    private fun withSuffix(position: Position, move: Move, body: String): String {
        val next = MoveGenerator.applyMove(position, move)
        val status = MoveGenerator.status(next)
        return when {
            status == GameStatus.CHECKMATE -> "$body#"
            MoveGenerator.isInCheck(next) -> "$body+"
            else -> body
        }
    }

    /**
     * Renders a full game as PGN move text: `1. e4 c5 2. Nf3 d6 ...`.
     *
     * [sans] must alternate starting from the side to move in the initial
     * position, and [initialFullmoveNumber] is normally 1.
     */
    fun pgnMoveText(
        sans: List<String>,
        startsWithWhite: Boolean = true,
        initialFullmoveNumber: Int = 1,
    ): String = buildString {
        sans.forEachIndexed { index, san ->
            val plyIsWhite = startsWithWhite == (index % 2 == 0)
            if (plyIsWhite) {
                if (isNotEmpty()) append(' ')
                append(initialFullmoveNumber + index / 2)
                append(". ")
            } else if (index == 0) {
                // A line that starts with Black's move needs the `1...` prefix.
                append("$initialFullmoveNumber... ")
            } else {
                append(' ')
            }
            append(san)
        }
    }
}
