package com.mostafazahra.chesswake.puzzle.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mostafazahra.chesswake.chess.Move
import com.mostafazahra.chesswake.chess.MoveGenerator
import com.mostafazahra.chesswake.chess.PieceColor
import com.mostafazahra.chesswake.chess.Position
import com.mostafazahra.chesswake.chess.Square
import com.mostafazahra.chesswake.di.ApplicationScope
import com.mostafazahra.chesswake.puzzle.data.AttemptContext
import com.mostafazahra.chesswake.puzzle.data.PuzzleRepository
import com.mostafazahra.chesswake.puzzle.domain.PlayOutcome
import com.mostafazahra.chesswake.puzzle.domain.Puzzle
import com.mostafazahra.chesswake.puzzle.domain.PuzzleGoal
import com.mostafazahra.chesswake.puzzle.domain.PuzzleTheme
import com.mostafazahra.chesswake.puzzle.domain.PuzzlePhase
import com.mostafazahra.chesswake.puzzle.domain.PuzzleSession
import com.mostafazahra.chesswake.puzzle.domain.PuzzleTap
import com.mostafazahra.chesswake.puzzle.domain.RejectReason
import com.mostafazahra.chesswake.puzzle.domain.TapResult
import com.mostafazahra.chesswake.settings.data.SettingsRepository
import com.mostafazahra.chesswake.stats.data.StatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the practice screen renders. */
data class PracticeUiState(
    val loading: Boolean = true,
    val puzzle: Puzzle? = null,
    val position: Position = Position.start(),
    val solverColor: PieceColor = PieceColor.WHITE,
    val phase: PuzzlePhase = PuzzlePhase.PLAYER_TO_MOVE,
    val highlights: BoardHighlights = BoardHighlights.NONE,
    val showCoordinates: Boolean = true,
    /** `Mate in one · difficulty 2`. */
    val title: String = "",
    /** `White to move — mate in 1`. */
    val goalLine: String = "",
    val message: String = "",
    val messageIsError: Boolean = false,
    val wrongAttempts: Int = 0,
    val hintUnlocked: Boolean = false,
    val hintVisible: Boolean = false,
    val hint: String = "",
    val playedSans: List<String> = emptyList(),
    /** Active filters for the next puzzle. */
    val themeFilter: PuzzleTheme? = null,
    val maxDifficulty: Int = MAX_DIFFICULTY,
    val availableCount: Int = 0,
    val solvedThisSession: Int = 0,
    val attemptedThisSession: Int = 0,
    val feedbackTick: Int = 0,
    val feedbackKind: PuzzleFeedback = PuzzleFeedback.CORRECT,
)

/**
 * Backs the practice screen: the same puzzles as the alarm, without the pressure.
 *
 * Practice exists because getting faster at mate-in-one is what makes the alarm
 * humane. It shares [PuzzleSession] and [PuzzleTap] with the alarm screen, so a
 * move that is accepted here is accepted at 6am too — the only differences are
 * that practice lets you restart, filters by theme, and never blocks anything.
 */
@HiltViewModel
class PuzzleViewModel @Inject constructor(
    private val puzzleRepository: PuzzleRepository,
    private val statsRepository: StatsRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PracticeUiState())
    val uiState: StateFlow<PracticeUiState> = _uiState.asStateFlow()

    private var session: PuzzleSession? = null
    private var startedAtMillis: Long = 0L

    /** One stats row per puzzle shown, whether it was solved or abandoned. */
    private var attemptRecorded = false

    init {
        viewModelScope.launch {
            val settings = runCatching { settingsRepository.settings.first() }.getOrNull()
            _uiState.update { it.copy(showCoordinates = settings?.showCoordinates ?: true) }
            newPuzzle()
            refreshAvailableCount()
        }
    }

    /** Loads a fresh puzzle honouring the current filters. */
    fun newPuzzle() {
        viewModelScope.launch {
            recordAbandoned()
            _uiState.update { it.copy(loading = true, hintVisible = false) }
            val state = _uiState.value
            val puzzle = runCatching {
                puzzleRepository.pickPuzzle(
                    theme = state.themeFilter,
                    maxDifficulty = state.maxDifficulty,
                    avoidRecent = 1,
                )
            }.getOrNull()
            install(puzzle)
        }
    }

    /** Restarts the current puzzle from its initial position. */
    fun restart() {
        val current = session ?: return
        attemptRecorded = false
        current.reset()
        startedAtMillis = System.currentTimeMillis()
        publish(message = "", isError = false)
    }

    /** Reveals the hint, which unlocks after two wrong moves. */
    fun showHint() {
        _uiState.update { if (it.hintUnlocked) it.copy(hintVisible = true) else it }
    }

    fun setThemeFilter(theme: PuzzleTheme?) {
        _uiState.update { it.copy(themeFilter = theme) }
        refreshAvailableCount()
    }

    fun setMaxDifficulty(level: Int) {
        _uiState.update { it.copy(maxDifficulty = level.coerceIn(1, MAX_DIFFICULTY)) }
        refreshAvailableCount()
    }

    fun onSquareTap(square: Square) {
        val current = session ?: return
        val state = _uiState.value
        if (state.loading) return

        when (val tap = PuzzleTap.resolve(current, state.highlights.selected, square)) {
            is TapResult.Select -> select(tap.square, current)
            TapResult.Deselect -> clearSelection()
            is TapResult.Play -> play(current, tap.move)
            is TapResult.Reject -> onRejected(current, tap.reason)
        }
        clearRejectedLater()
    }

    // ------------------------------------------------------------- internals

    private fun install(puzzle: Puzzle?) {
        val newSession = puzzle?.let { PuzzleSession(it) }
        session = newSession
        startedAtMillis = System.currentTimeMillis()
        attemptRecorded = false
        _uiState.update {
            it.copy(
                loading = false,
                puzzle = puzzle,
                position = newSession?.position ?: Position.start(),
                solverColor = newSession?.solverColor ?: PieceColor.WHITE,
                phase = newSession?.phase ?: PuzzlePhase.UNPLAYABLE,
                title = puzzle?.summary.orEmpty(),
                goalLine = puzzle?.let(::goalLine).orEmpty(),
                message = if (puzzle == null) "No puzzles available." else "",
                messageIsError = puzzle == null,
                wrongAttempts = 0,
                hint = puzzle?.hint.orEmpty(),
                hintUnlocked = false,
                hintVisible = false,
                playedSans = emptyList(),
                highlights = BoardHighlights.NONE,
                attemptedThisSession = it.attemptedThisSession + 1,
            )
        }
    }

    private fun select(square: Square, session: PuzzleSession) {
        val targets = session.legalDestinationsFrom(square).map { it.to }.toSet()
        _uiState.update {
            it.copy(
                highlights = it.highlights.copy(selected = square, legalTargets = targets, rejected = null),
                message = "",
                messageIsError = false,
            )
        }
    }

    private fun clearSelection() {
        _uiState.update {
            it.copy(highlights = it.highlights.copy(selected = null, legalTargets = emptySet(), rejected = null))
        }
    }

    private fun play(session: PuzzleSession, move: Move) {
        when (val outcome = session.play(move)) {
            is PlayOutcome.Accepted -> onAccepted(session, outcome)
            is PlayOutcome.Wrong -> onWrong(session, outcome)
            is PlayOutcome.Illegal -> showMessage(outcome.message, isError = true, feedback = PuzzleFeedback.WRONG)
        }
    }

    private fun onAccepted(session: PuzzleSession, outcome: PlayOutcome.Accepted) {
        if (outcome.solved) {
            recordAttempt(session, solved = true)
            _uiState.update { it.copy(solvedThisSession = it.solvedThisSession + 1) }
            publish(
                message = "Solved in ${session.acceptedSolverMoves} move" +
                    (if (session.acceptedSolverMoves == 1) "" else "s") +
                    (if (session.wrongAttempts == 0) ", first try." else "."),
                isError = false,
                feedback = PuzzleFeedback.SOLVED,
            )
        } else {
            val reply = outcome.opponentSan?.let { "  ·  $it" }.orEmpty()
            publish(
                message = "Good move — ${outcome.san}.$reply",
                isError = false,
                feedback = PuzzleFeedback.CORRECT,
            )
        }
    }

    private fun onWrong(session: PuzzleSession, outcome: PlayOutcome.Wrong) {
        _uiState.update {
            it.copy(
                highlights = it.highlights.copy(
                    selected = null,
                    legalTargets = emptySet(),
                    rejected = outcome.move,
                ),
            )
        }
        publish(message = outcome.message, isError = true, feedback = PuzzleFeedback.WRONG)
    }

    private fun onRejected(session: PuzzleSession, reason: RejectReason) {
        when (reason) {
            RejectReason.EMPTY_SQUARE -> Unit
            RejectReason.OPPONENT_PIECE -> showMessage(
                "You are playing ${session.solverColor.displayName}.",
                isError = true,
                feedback = PuzzleFeedback.WRONG,
            )

            RejectReason.ILLEGAL_DESTINATION -> {
                clearSelection()
                showMessage("That is not a legal move.", isError = true, feedback = PuzzleFeedback.WRONG)
            }

            RejectReason.NOT_YOUR_TURN -> Unit
        }
    }

    /** Copies the live session into the UI state. */
    private fun publish(message: String? = null, isError: Boolean = false, feedback: PuzzleFeedback? = null) {
        val current = session
        _uiState.update { state ->
            val position = current?.position ?: state.position
            val phase = current?.phase ?: state.phase
            val checkedKing = if (MoveGenerator.isInCheck(position)) {
                position.kingSquare(position.sideToMove)
            } else {
                null
            }
            state.copy(
                position = position,
                phase = phase,
                playedSans = current?.playedSans ?: emptyList(),
                wrongAttempts = current?.wrongAttempts ?: state.wrongAttempts,
                hintUnlocked = current?.hintUnlocked ?: state.hintUnlocked,
                highlights = state.highlights.copy(
                    selected = null,
                    legalTargets = emptySet(),
                    lastMove = current?.lastMove(),
                    checkedKing = checkedKing,
                    rejected = state.highlights.rejected,
                ),
                goalLine = current?.puzzle?.let(::goalLine) ?: state.goalLine,
                message = message ?: state.message,
                messageIsError = isError,
                feedbackTick = if (feedback != null) state.feedbackTick + 1 else state.feedbackTick,
                feedbackKind = feedback ?: state.feedbackKind,
            )
        }
    }

    private fun goalLine(puzzle: Puzzle): String {
        val side = puzzle.solverColor.displayName
        return when (puzzle.goal) {
            PuzzleGoal.CHECKMATE -> "$side to move — mate in ${puzzle.matesIn.coerceAtLeast(1)}"
            PuzzleGoal.WIN_MATERIAL -> "$side to move — win material"
        }
    }

    private fun showMessage(message: String, isError: Boolean, feedback: PuzzleFeedback? = null) {
        _uiState.update {
            it.copy(
                message = message,
                messageIsError = isError,
                feedbackTick = if (feedback != null) it.feedbackTick + 1 else it.feedbackTick,
                feedbackKind = feedback ?: it.feedbackKind,
            )
        }
    }

    private fun clearRejectedLater() {
        if (_uiState.value.highlights.rejected == null) return
        viewModelScope.launch {
            delay(REJECTED_TINT_MILLIS)
            _uiState.update { it.copy(highlights = it.highlights.copy(rejected = null)) }
        }
    }

    /** Writes the attempt for a puzzle the user is leaving without solving. */
    private fun recordAbandoned() {
        val current = session ?: return
        if (current.phase == PuzzlePhase.SOLVED) return
        // Only count it if the user actually engaged with the board.
        if (current.wrongAttempts == 0 && current.playedSans.isEmpty()) return
        recordAttempt(current, solved = false)
    }

    private fun recordAttempt(session: PuzzleSession, solved: Boolean) {
        if (attemptRecorded) return
        attemptRecorded = true

        val puzzle = session.puzzle
        val startedAt = startedAtMillis
        val duration = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        val wrongAttempts = session.wrongAttempts
        applicationScope.launch {
            runCatching {
                statsRepository.recordAttempt(
                    puzzle = puzzle,
                    context = AttemptContext.PRACTICE,
                    solved = solved,
                    wrongAttempts = wrongAttempts,
                    durationMillis = duration,
                    startedAt = startedAt,
                )
            }
        }
    }

    private fun refreshAvailableCount() {
        viewModelScope.launch {
            val state = _uiState.value
            val count = runCatching {
                puzzleRepository.matching(state.themeFilter, state.maxDifficulty).size
            }.getOrDefault(0)
            _uiState.update { it.copy(availableCount = count) }
        }
    }

    private companion object {
        const val REJECTED_TINT_MILLIS = 700L
    }
}

/** Puzzles are generated at levels 1..5; the practice filter tops out there. */
private const val MAX_DIFFICULTY = 5
