package com.mostafazahra.chesswake.puzzle

/**
 * A square on the board. [file] is 0..7 (a..h, left to right) and [rank] is 0..7 (1..8,
 * bottom to top). So a1 == Square(0, 0), h8 == Square(7, 7).
 */
data class Square(val file: Int, val rank: Int) {
    val algebraic: String
        get() = "${'a' + file}${rank + 1}"

    override fun toString(): String = algebraic
}

/** A move from one square to another. */
data class Move(val from: Square, val to: Square) {
    val algebraic: String
        get() = "$from-$to"
}

/** An 8x8 board parsed from the piece-placement portion of a FEN string. */
class Board private constructor(private val squares: Array<Char?>) {

    /** Returns the piece at [square] (FEN piece char, e.g. 'K' or 'p'), or null if empty. */
    fun pieceAt(square: Square): Char? = squares[square.rank * 8 + square.file]

    companion object {
        fun fromFen(fen: String): Board {
            val placement = fen.trim().substringBefore(' ')
            val squares = arrayOfNulls<Char?>(64)
            var file = 0
            var rank = 7
            for (ch in placement) {
                when {
                    ch == '/' -> {
                        file = 0
                        rank--
                    }
                    ch.isDigit() -> file += ch - '0'
                    else -> {
                        squares[rank * 8 + file] = ch
                        file++
                    }
                }
            }
            return Board(squares)
        }
    }
}

/** A chess puzzle: a position and its single hardcoded correct answer. */
data class Puzzle(val fen: String, val solution: Move, val description: String)

/** A single hardcoded mate-in-1 puzzle (per the MVP plan, no puzzle database). */
object Puzzles {
    val perfectMateInOne = Puzzle(
        fen = "k7/8/8/8/8/8/8/R6K w - - 0 1",
        solution = Move(Square(0, 0), Square(0, 7)),
        description = "White to move. Find the mate in one!",
    )
}

/** Unicode chess piece glyphs (filled style renders consistently across Android versions). */
fun pieceGlyph(piece: Char): String = when (piece) {
    'K' -> "♔"; 'Q' -> "♕"; 'R' -> "♖"; 'B' -> "♗"; 'N' -> "♘"; 'P' -> "♙"
    'k' -> "♚"; 'q' -> "♛"; 'r' -> "♜"; 'b' -> "♝"; 'n' -> "♞"; 'p' -> "♟"
    else -> ""
}
