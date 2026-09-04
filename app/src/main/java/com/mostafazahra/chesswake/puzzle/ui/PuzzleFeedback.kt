package com.mostafazahra.chesswake.puzzle.ui

/**
 * One-shot feedback a puzzle screen should express with haptics and colour.
 *
 * Lives in the puzzle UI layer rather than in either screen so the alarm and the
 * practice screen fire the same feedback for the same event.
 */
enum class PuzzleFeedback {
    /** A correct move that did not finish the puzzle. */
    CORRECT,

    /** A legal move the puzzle does not want, or an illegal tap. */
    WRONG,

    /** The puzzle is solved. */
    SOLVED,
}
