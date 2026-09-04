package com.mostafazahra.chesswake.chess

import kotlin.math.abs

/**
 * One of the 64 squares, stored as a single index so it can be used directly to
 * address the backing array of a [Board].
 *
 * Index layout: `index = rank * 8 + file`, where file 0 is the `a`-file and
 * rank 0 is White's back rank (the `1`-rank). So `a1 == 0`, `h1 == 7`,
 * `a8 == 56` and `h8 == 63`.
 *
 * A `value class` is used so that move generation never boxes a square on the
 * hot path while still giving call sites a type-safe parameter.
 */
@JvmInline
value class Square(val index: Int) {

    /** 0 (`a`) .. 7 (`h`). */
    val file: Int get() = index and 7

    /** 0 (`1`) .. 7 (`8`). */
    val rank: Int get() = index ushr 3

    /** Algebraic name, e.g. `e4`. */
    val name: String get() = "${'a' + file}${'1' + rank}"

    /**
     * True for the light squares. `a1` is dark, so light squares are the ones
     * where file + rank is odd. The board renderer uses this for colouring.
     */
    val isLight: Boolean get() = ((file + rank) and 1) == 1

    /**
     * Returns the square reached by shifting [fileDelta] files and [rankDelta]
     * ranks, or null when that would leave the board.
     *
     * Using this instead of raw index arithmetic is what keeps knight and sliding
     * generation free of the classic "wraps from h-file to a-file" bug.
     */
    fun offset(fileDelta: Int, rankDelta: Int): Square? {
        val targetFile = file + fileDelta
        val targetRank = rank + rankDelta
        if (targetFile !in 0..7 || targetRank !in 0..7) return null
        return of(targetFile, targetRank)
    }

    /** Chebyshev distance (king steps) to [other]. */
    fun distanceTo(other: Square): Int = maxOf(abs(file - other.file), abs(rank - other.rank))

    override fun toString(): String = name

    companion object {
        /** Builds a square from 0-based file and rank. */
        fun of(file: Int, rank: Int): Square = Square((rank shl 3) or file)

        /**
         * Parses an algebraic square name such as `"e4"`.
         *
         * Accepts upper case (`"E4"`) and rejects anything malformed rather than
         * throwing, because puzzle data is read from bundled resources.
         */
        fun parse(name: String): Square? {
            val trimmed = name.trim().lowercase()
            if (trimmed.length != 2) return null
            val file = trimmed[0] - 'a'
            val rank = trimmed[1] - '1'
            if (file !in 0..7 || rank !in 0..7) return null
            return of(file, rank)
        }

        /** All 64 squares in index order (a1 .. h8). */
        val ALL: List<Square> = List(64) { Square(it) }

        // Named squares referenced by the castling rules.
        val A1: Square = Square(0)
        val B1: Square = Square(1)
        val C1: Square = Square(2)
        val D1: Square = Square(3)
        val E1: Square = Square(4)
        val F1: Square = Square(5)
        val G1: Square = Square(6)
        val H1: Square = Square(7)
        val A8: Square = Square(56)
        val B8: Square = Square(57)
        val C8: Square = Square(58)
        val D8: Square = Square(59)
        val E8: Square = Square(60)
        val F8: Square = Square(61)
        val G8: Square = Square(62)
        val H8: Square = Square(63)
    }
}
