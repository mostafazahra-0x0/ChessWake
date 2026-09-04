package com.mostafazahra.chesswake.puzzle.domain

/** What kind of idea a puzzle is testing. Used for filtering and for stats. */
enum class PuzzleTheme(val displayName: String) {
    MATE_IN_ONE("Mate in one"),
    MATE_IN_TWO("Mate in two"),
    WIN_MATERIAL("Win material"),
    ;

    companion object {
        /** Parses a stored name, defaulting to the easiest theme. */
        fun fromName(name: String?): PuzzleTheme =
            entries.firstOrNull { it.name == name } ?: MATE_IN_ONE
    }
}

/**
 * What the solver is being asked to achieve.
 *
 * The session uses this to decide when a puzzle is finished: [CHECKMATE] is
 * satisfied the moment the opponent is mated (by *any* move, not just the
 * recorded one), while [WIN_MATERIAL] needs the recorded material gain to have
 * actually appeared on the board.
 */
enum class PuzzleGoal(val displayName: String) {
    CHECKMATE("Checkmate"),
    WIN_MATERIAL("Win material"),
    ;

    companion object {
        fun fromName(name: String?): PuzzleGoal =
            entries.firstOrNull { it.name == name } ?: CHECKMATE
    }
}
