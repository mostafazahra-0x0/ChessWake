package com.mostafazahra.chesswake.alarm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mostafazahra.chesswake.alarm.RingingAlarmState
import com.mostafazahra.chesswake.alarm.data.AlarmRepository
import com.mostafazahra.chesswake.alarm.domain.Alarm
import com.mostafazahra.chesswake.chess.Move
import com.mostafazahra.chesswake.chess.MoveGenerator
import com.mostafazahra.chesswake.chess.PieceColor
import com.mostafazahra.chesswake.chess.Position
import com.mostafazahra.chesswake.chess.Square
import com.mostafazahra.chesswake.di.ApplicationScope
import com.mostafazahra.chesswake.puzzle.data.PuzzleRepository
import com.mostafazahra.chesswake.puzzle.domain.PlayOutcome
import com.mostafazahra.chesswake.puzzle.domain.Puzzle
import com.mostafazahra.chesswake.puzzle.domain.PuzzleGoal
import com.mostafazahra.chesswake.puzzle.domain.PuzzlePhase
import com.mostafazahra.chesswake.puzzle.domain.PuzzleTap
import com.mostafazahra.chesswake.puzzle.domain.RejectReason
import com.mostafazahra.chesswake.puzzle.domain.TapResult
import com.mostafazahra.chesswake.puzzle.domain.PuzzleSession
import com.mostafazahra.chesswake.puzzle.ui.BoardHighlights
import com.mostafazahra.chesswake.puzzle.ui.PuzzleFeedback
import com.mostafazahra.chesswake.settings.data.SettingsRepository
import com.mostafazahra.chesswake.settings.domain.AppSettings
import com.mostafazahra.chesswake.sleepasandroid.SleepAsAndroidBridge
import com.mostafazahra.chesswake.stats.data.StatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One-shot events the activity reacts to. */
sealed interface AlarmEvent {
    /** The alarm is over; the activity should finish. */
    data object Dismissed : AlarmEvent

    /** Snoozed; the activity should finish quietly. */
    data object Snoozed : AlarmEvent
}

/** Everything the full-screen alarm renders. */
data class AlarmUiState(
    val loading: Boolean = true,
    val alarmId: Long = RingingAlarmState.NO_ALARM,
    val timeLabel: String = "",
    val label: String = "",
    val ringingSinceMillis: Long = 0L,
    val elapsedSeconds: Long = 0L,
    /** False when the alarm was created without a puzzle requirement, or when the puzzle failed to load. */
    val requirePuzzle: Boolean = true,
    val puzzle: Puzzle? = null,
    val position: Position = Position.start(),
    val solverColor: PieceColor = PieceColor.WHITE,
    val phase: PuzzlePhase = PuzzlePhase.PLAYER_TO_MOVE,
    val highlights: BoardHighlights = BoardHighlights.NONE,
    val showCoordinates: Boolean = true,
    val caption: String = "",
    val message: String = "",
    val messageIsError: Boolean = false,
    val wrongAttempts: Int = 0,
    val hintUnlocked: Boolean = false,
    val hintVisible: Boolean = false,
    val hint: String = "",
    val playedSans: List<String> = emptyList(),
    val snoozeCount: Int = 0,
    val snoozeMinutes: Int = AppSettings.DEFAULT.snoozeMinutes,
    val canSnooze: Boolean = false,
    val confirmBeforeDismiss: Boolean = false,
    /** Set when the puzzle is solved but the user opted into an explicit confirm tap. */
    val awaitingConfirm: Boolean = false,
    /** True when the WAKE UP button may be pressed. */
    val canDismiss: Boolean = false,
    val keepScreenOn: Boolean = true,
    val feedbackTick: Int = 0,
    val feedbackKind: PuzzleFeedback = PuzzleFeedback.CORRECT,
)

/**
 * Drives the full-screen ringing alarm.
 *
 * Responsibilities, in the order they matter at 6am:
 *
 *  1. **Always land on a solvable board.** The puzzle comes from the ringing
 *     state, then the alarm's own theme/difficulty, then app defaults, with
 *     [PuzzleRepository.pickPuzzle] falling back through the database to the
 *     bundled list. If nothing can be parsed the state degrades to
 *     [PuzzlePhase.UNPLAYABLE] and dismissal is allowed: an alarm that cannot be
 *     turned off is worse than one that can be skipped.
 *  2. **Validate with the engine, never by string comparison.** Every tap goes
 *     through [PuzzleSession], which uses the full legal-move generator.
 *  3. **Keep the ringing state authoritative.** Snooze and dismiss always clear
 *     [RingingAlarmState], so the sound service and notification cannot outlive
 *     the screen.
 *  4. **Write history off the ViewModel scope.** Stats writes and snooze
 *     scheduling run on the application scope because the activity finishes the
 *     moment dismissal is emitted, which would cancel a `viewModelScope` job.
 */
@HiltViewModel
class AlarmViewModel @Inject constructor(
    private val ringingState: RingingAlarmState,
    private val puzzleRepository: PuzzleRepository,
    private val alarmRepository: AlarmRepository,
    private val statsRepository: StatsRepository,
    private val settingsRepository: SettingsRepository,
    private val sleepBridge: SleepAsAndroidBridge,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlarmUiState())
    val uiState: StateFlow<AlarmUiState> = _uiState.asStateFlow()

    private val _events = Channel<AlarmEvent>(Channel.BUFFERED)
    val events: Flow<AlarmEvent> = _events.receiveAsFlow()

    private var session: PuzzleSession? = null
    private var startedAtMillis: Long = 0L

    /** Guards against recording two rows for one ringing (solve *and* dismiss). */
    private var attemptRecorded = false

    init {
        viewModelScope.launch { start() }
        viewModelScope.launch { tickElapsed() }
    }

    /** Loads settings, the alarm row and a puzzle, then publishes the first board. */
    private suspend fun start() {
        val settings = runCatching { settingsRepository.settings.first() }
            .getOrDefault(AppSettings.DEFAULT)

        val alarmId = ringingState.alarmId
        val loadedAlarm = if (alarmId != RingingAlarmState.NO_ALARM) {
            runCatching { alarmRepository.byId(alarmId) }.getOrNull()
        } else {
            null
        }

        val puzzle = runCatching { resolvePuzzle(settings, loadedAlarm) }.getOrNull()
        val newSession = puzzle?.let { PuzzleSession(it) }
        session = newSession
        startedAtMillis = System.currentTimeMillis()

        // A puzzle that cannot be parsed must not trap the user behind a dead board.
        val puzzleRequired = ringingState.requirePuzzle &&
            (loadedAlarm?.requirePuzzle ?: true) &&
            newSession?.phase != PuzzlePhase.UNPLAYABLE

        _uiState.update {
            it.copy(
                alarmId = alarmId,
                timeLabel = ringingState.timeLabel.ifBlank { loadedAlarm?.timeLabel.orEmpty() },
                label = ringingState.label.ifBlank { loadedAlarm?.label.orEmpty() },
                ringingSinceMillis = ringingState.ringingSinceMillis,
                requirePuzzle = puzzleRequired,
                puzzle = puzzle,
                position = newSession?.position ?: Position.start(),
                solverColor = newSession?.solverColor ?: PieceColor.WHITE,
                phase = newSession?.phase ?: PuzzlePhase.UNPLAYABLE,
                showCoordinates = settings.showCoordinates,
                snoozeCount = ringingState.snoozeCount,
                snoozeMinutes = loadedAlarm?.snoozeMinutes ?: settings.snoozeMinutes,
                canSnooze = ringingState.canSnooze(loadedAlarm?.maxSnoozes ?: settings.maxSnoozes),
                confirmBeforeDismiss = settings.confirmBeforeDismiss,
                keepScreenOn = settings.keepScreenOnDuringPuzzle,
                hint = puzzle?.hint.orEmpty(),
                hintUnlocked = newSession?.hintUnlocked ?: false,
            )
        }

        publishBoardState(
            message = when {
                !puzzleRequired -> ""
                newSession == null || puzzle == null -> LOAD_FAILURE_MESSAGE
                newSession.phase == PuzzlePhase.UNPLAYABLE -> LOAD_FAILURE_MESSAGE
                else -> ""
            },
            isError = newSession == null || newSession.phase == PuzzlePhase.UNPLAYABLE,
        )
    }

    /**
     * Chooses the puzzle for this ringing.
     *
     * The id already stored in [RingingAlarmState] wins, so a second delivery of
     * the same alarm (broadcast *and* full-screen intent both arriving) cannot
     * swap the board while the user is mid-move.
     */
    private suspend fun resolvePuzzle(settings: AppSettings, alarm: Alarm?): Puzzle {
        ringingState.puzzleId?.let { id -> puzzleRepository.byId(id)?.let { return it } }
        val puzzle = puzzleRepository.pickPuzzle(
            theme = alarm?.puzzleTheme ?: settings.puzzleTheme,
            maxDifficulty = alarm?.maxDifficulty ?: settings.defaultMaxDifficulty,
        )
        ringingState.assignPuzzle(puzzle.id)
        return puzzle
    }

    /** Counts up while the alarm rings, so the user can see how long they have been awake. */
    private suspend fun tickElapsed() {
        while (currentCoroutineContext().isActive) {
            delay(1_000)
            val since = ringingState.ringingSinceMillis
            val elapsed = if (since > 0L) {
                ((System.currentTimeMillis() - since) / 1_000L).coerceAtLeast(0L)
            } else {
                0L
            }
            _uiState.update { it.copy(elapsedSeconds = elapsed) }
        }
    }

    // ---------------------------------------------------------------- moves

    /** Handles a tap on [square]: select a piece, re-select, or play a move. */
    fun onSquareTap(square: Square) {
        val current = session ?: return
        val state = _uiState.value
        if (state.loading || state.awaitingConfirm) return

        // The tap-to-move rules live in PuzzleTap so the alarm and the practice
        // screen cannot drift apart.
        when (val tap = PuzzleTap.resolve(current, state.highlights.selected, square)) {
            is TapResult.Select -> select(tap.square, current)
            TapResult.Deselect -> clearSelection()
            is TapResult.Play -> play(tap.move)
            is TapResult.Reject -> onRejected(current, tap.reason)
        }
    }

    private fun onRejected(session: PuzzleSession, reason: RejectReason) {
        when (reason) {
            // Tapping an empty square with nothing selected is a fumble, not an
            // error: saying so every time would be noise.
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

            RejectReason.NOT_YOUR_TURN -> showMessage(
                "It is not your move yet.",
                isError = true,
                feedback = PuzzleFeedback.WRONG,
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

    /** Feeds a move to the session and reflects the outcome in the UI state. */
    private fun play(move: Move) {
        val current = session ?: return
        when (val outcome = current.play(move)) {
            is PlayOutcome.Accepted -> onAccepted(current, outcome)
            is PlayOutcome.Wrong -> onWrong(current, outcome)
            is PlayOutcome.Illegal -> showMessage(outcome.message, isError = true, feedback = PuzzleFeedback.WRONG)
        }
        clearRejectedLater()
    }

    private fun onAccepted(session: PuzzleSession, outcome: PlayOutcome.Accepted) {
        if (outcome.solved) {
            publishBoardState(
                message = "Solved — ${outcome.san}",
                isError = false,
                feedback = PuzzleFeedback.SOLVED,
            )
            onSolved(session)
        } else {
            val reply = outcome.opponentSan?.let { "  ·  opponent replied $it" }.orEmpty()
            publishBoardState(
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
        publishBoardState(
            message = outcome.message,
            isError = true,
            feedback = PuzzleFeedback.WRONG,
        )
    }

    private fun onSolved(session: PuzzleSession) {
        recordAttempt(session, solved = true)
        if (_uiState.value.confirmBeforeDismiss) {
            _uiState.update {
                it.copy(
                    awaitingConfirm = true,
                    canDismiss = true,
                    message = "Puzzle solved. Tap WAKE UP to turn off the alarm.",
                    messageIsError = false,
                )
            }
        } else {
            finishDismissal()
        }
    }

    // ------------------------------------------------------- alarm controls

    /**
     * Turns the alarm off for good.
     *
     * Blocked until the puzzle is solved unless the alarm does not require one.
     */
    fun dismiss() {
        val state = _uiState.value
        if (!state.canDismiss) {
            showMessage("Solve the puzzle to turn off the alarm.", isError = true, feedback = PuzzleFeedback.WRONG)
            return
        }
        session?.let { recordAttempt(it, solved = it.phase == PuzzlePhase.SOLVED) }
        finishDismissal()
    }

    private fun finishDismissal() {
        ringingState.endRinging()
        // Sleep as Android, when installed and enabled, wants to know we woke up
        // so it can close its own sleep-tracking session.
        applicationScope.launch { runCatching { sleepBridge.notifyWokeUp() } }
        _uiState.update { it.copy(awaitingConfirm = false, canDismiss = true, message = "", hintVisible = false) }
        viewModelScope.launch { _events.send(AlarmEvent.Dismissed) }
    }

    /** Snoozes, if the alarm still has snoozes left. */
    fun snooze() {
        val state = _uiState.value
        if (!state.canSnooze) {
            showMessage("No snoozes left for this alarm.", isError = true, feedback = PuzzleFeedback.WRONG)
            return
        }
        val alarmId = state.alarmId
        val minutes = state.snoozeMinutes
        session?.let { recordAttempt(it, solved = false) }

        // Application scope: the activity finishes right after this and would
        // otherwise cancel the rescheduling coroutine mid-write.
        applicationScope.launch {
            val scheduled = runCatching { alarmRepository.snooze(alarmId, minutes) }.getOrDefault(false)
            if (!scheduled) {
                // The exact-alarm permission can be revoked at any time; rebook
                // everything rather than silently dropping the snooze.
                runCatching { alarmRepository.rescheduleAll() }
            }
        }
        ringingState.noteSnooze()
        _uiState.update { it.copy(message = "", hintVisible = false) }
        viewModelScope.launch { _events.send(AlarmEvent.Snoozed) }
    }

    /** Reveals the bundled hint, which unlocks after two wrong moves. */
    fun showHint() {
        _uiState.update { if (it.hintUnlocked) it.copy(hintVisible = true) else it }
    }

    // ------------------------------------------------------------- internals

    /** Records one attempt, at most once per ringing. */
    private fun recordAttempt(session: PuzzleSession, solved: Boolean) {
        if (attemptRecorded) return
        attemptRecorded = true

        val puzzle = session.puzzle
        val alarmId = _uiState.value.alarmId.takeIf { it != RingingAlarmState.NO_ALARM }
        val startedAt = startedAtMillis
        val duration = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        val wrongAttempts = session.wrongAttempts
        applicationScope.launch {
            runCatching {
                statsRepository.recordAlarmAttempt(
                    puzzle = puzzle,
                    alarmId = alarmId,
                    solved = solved,
                    wrongAttempts = wrongAttempts,
                    durationMillis = duration,
                    startedAt = startedAt,
                )
            }
        }
    }

    /** Copies the live session into the UI state. */
    private fun publishBoardState(message: String? = null, isError: Boolean = false, feedback: PuzzleFeedback? = null) {
        val current = session
        _uiState.update { state ->
            val position = current?.position ?: state.position
            val phase = current?.phase ?: state.phase
            val puzzle = current?.puzzle
            val checkedKing = if (MoveGenerator.isInCheck(position)) {
                position.kingSquare(position.sideToMove)
            } else {
                null
            }
            val caption = when {
                puzzle == null -> ""
                phase == PuzzlePhase.SOLVED ->
                    if (puzzle.goal == PuzzleGoal.CHECKMATE) "Checkmate. Well played." else "Goal reached. Well played."

                phase == PuzzlePhase.UNPLAYABLE -> "Puzzle unavailable"
                else -> goalLine(puzzle)
            }
            state.copy(
                loading = false,
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
                caption = caption,
                message = message ?: state.message,
                messageIsError = isError,
                canDismiss = !state.requirePuzzle || phase == PuzzlePhase.SOLVED || phase == PuzzlePhase.UNPLAYABLE,
                feedbackTick = if (feedback != null) state.feedbackTick + 1 else state.feedbackTick,
                feedbackKind = feedback ?: state.feedbackKind,
            )
        }
    }

    private fun goalLine(puzzle: Puzzle): String {
        val side = puzzle.solverColor.displayName
        return when (puzzle.goal) {
            PuzzleGoal.CHECKMATE ->
                "$side to move — mate in ${puzzle.matesIn.coerceAtLeast(1)}"

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

    /** Clears the red "rejected" tint a beat after a wrong move. */
    private fun clearRejectedLater() {
        if (_uiState.value.highlights.rejected == null) return
        viewModelScope.launch {
            delay(REJECTED_TINT_MILLIS)
            _uiState.update { it.copy(highlights = it.highlights.copy(rejected = null)) }
        }
    }

    private companion object {
        const val REJECTED_TINT_MILLIS = 700L
        const val LOAD_FAILURE_MESSAGE = "This puzzle could not be loaded — dismiss to turn off the alarm."
    }
}
