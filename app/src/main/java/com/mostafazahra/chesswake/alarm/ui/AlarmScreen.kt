package com.mostafazahra.chesswake.alarm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.keepScreenOn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.mostafazahra.chesswake.R
import com.mostafazahra.chesswake.chess.Square
import com.mostafazahra.chesswake.puzzle.ui.ChessBoard
import com.mostafazahra.chesswake.puzzle.ui.PuzzleFeedback

/**
 * The screen someone sees when they are asleep.
 *
 * Design rules that shaped it:
 *
 *  - **Nothing scrolls.** The board is sized from the space left over by the
 *    header and the buttons, so the two controls that matter are always on screen
 *    without hunting for them.
 *  - **Type is huge.** The clock is 76sp and the buttons are 72dp tall; a
 *    half-awake user should not have to aim.
 *  - **The dismiss button is visible but disabled** until the puzzle is solved.
 *    Hiding it would leave no affordance at all; enabling it would defeat the app.
 *  - **Snooze is the only escape hatch**, and only while the alarm has snoozes
 *    left, which is what stops the "snooze until noon" failure mode.
 *  - **Feedback is announced, not just shown**: the message is a polite live
 *    region, so TalkBack reads out each wrong move.
 */
@Composable
fun AlarmScreen(
    state: AlarmUiState,
    onSquareTap: (Square) -> Unit,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    onShowHint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val boardDescription = stringResource(R.string.alarm_screen_board_description)

    // One haptic per feedback event, driven by the tick rather than by the state
    // itself so a recomposition cannot fire it twice.
    LaunchedEffect(state.feedbackTick) {
        if (state.feedbackTick == 0) return@LaunchedEffect
        when (state.feedbackKind) {
            PuzzleFeedback.WRONG -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
            PuzzleFeedback.CORRECT -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            PuzzleFeedback.SOLVED -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .then(if (state.keepScreenOn) Modifier.keepScreenOn() else Modifier)
            .background(colors.background)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AlarmHeader(state = state)

        if (state.requirePuzzle && state.puzzle != null) {
            Text(
                text = state.caption,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )

            // The board takes whatever vertical space is left, and never more.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                val boardSize = max(min(maxWidth, maxHeight - BOARD_VERTICAL_SLACK), MIN_BOARD_SIZE)
                ChessBoard(
                    position = state.position,
                    orientation = state.solverColor,
                    highlights = state.highlights,
                    showCoordinates = state.showCoordinates,
                    onSquareTap = onSquareTap,
                    modifier = Modifier
                        .size(boardSize)
                        .semantics { contentDescription = boardDescription },
                )
            }

            AlarmHintRow(state = state, onShowHint = onShowHint)
        } else {
            // No puzzle required, or none could be loaded: a plain alarm clock.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.message.ifBlank { stringResource(R.string.alarm_screen_turn_off) },
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (state.requirePuzzle && state.puzzle != null) {
            AlarmMessage(state = state)
        } else {
            Spacer(modifier = Modifier.height(MESSAGE_ROW_HEIGHT))
        }

        AlarmButtons(state = state, onDismiss = onDismiss, onSnooze = onSnooze)

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun AlarmHeader(state: AlarmUiState) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.timeLabel.ifBlank { FALLBACK_CLOCK },
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = CLOCK_FONT_SIZE,
                fontWeight = FontWeight.Black,
                letterSpacing = (-2).sp,
            ),
            color = colors.onBackground,
            maxLines = 1,
        )

        if (state.label.isNotBlank()) {
            Text(
                text = state.label,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onBackground,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }

        Text(
            text = stringResource(R.string.alarm_screen_awake_for, formatClockDuration(state.elapsedSeconds)),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun AlarmMessage(state: AlarmUiState) {
    val colors = MaterialTheme.colorScheme
    val messageColor = when {
        state.message.isBlank() -> colors.onSurfaceVariant
        state.messageIsError -> colors.error
        else -> colors.primary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MESSAGE_ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = messageColor,
            textAlign = TextAlign.Start,
            maxLines = 2,
            modifier = Modifier
                .weight(1f)
                // Polite live region: screen readers announce move feedback.
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (state.wrongAttempts > 0) {
            Text(
                text = stringResource(R.string.alarm_screen_misses, state.wrongAttempts),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AlarmHintRow(state: AlarmUiState, onShowHint: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    when {
        state.hintVisible && state.hint.isNotBlank() -> Text(
            text = stringResource(R.string.alarm_screen_hint_label, state.hint),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.tertiary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
        )

        state.hintUnlocked && state.hint.isNotBlank() -> TextButton(
            onClick = onShowHint,
            modifier = Modifier.widthIn(max = 260.dp),
        ) {
            Text(stringResource(R.string.alarm_screen_show_hint))
        }

        // Reserving nothing here would make the board jump when the hint unlocks,
        // but reserving a full row would waste space on every clean solve.
        else -> Unit
    }
}

@Composable
private fun AlarmButtons(state: AlarmUiState, onDismiss: () -> Unit, onSnooze: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dismissLabel = when {
        !state.requirePuzzle -> stringResource(R.string.alarm_screen_turn_off)
        state.canDismiss -> stringResource(R.string.alarm_screen_wake_up)
        else -> stringResource(R.string.alarm_screen_solve_to_dismiss)
    }
    val snoozeLabel = if (state.canSnooze) {
        stringResource(R.string.alarm_screen_snooze, state.snoozeMinutes)
    } else {
        stringResource(R.string.alarm_screen_no_snooze)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Snooze stays visible even once it is spent, so the user learns why the
        // button does nothing rather than wondering where it went.
        FilledTonalButton(
            onClick = onSnooze,
            enabled = state.canSnooze,
            modifier = Modifier
                .weight(1f)
                .height(BUTTON_HEIGHT),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Text(
                text = snoozeLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }

        Button(
            onClick = onDismiss,
            enabled = state.canDismiss,
            modifier = Modifier
                .weight(1.3f)
                .height(BUTTON_HEIGHT),
            shape = MaterialTheme.shapes.extraLarge,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary,
                disabledContainerColor = colors.surfaceVariant,
                disabledContentColor = colors.onSurfaceVariant,
            ),
        ) {
            Text(
                text = dismissLabel,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                maxLines = 1,
            )
        }
    }
}

/** `m:ss` for the "awake for" line. */
internal fun formatClockDuration(totalSeconds: Long): String {
    val bounded = totalSeconds.coerceAtLeast(0L)
    val minutes = bounded / 60
    val seconds = bounded % 60
    return "%d:%02d".format(minutes, seconds)
}

private const val FALLBACK_CLOCK = "--:--"
private val CLOCK_FONT_SIZE = 76.sp
private val BUTTON_HEIGHT = 72.dp
private val MESSAGE_ROW_HEIGHT = 56.dp
private val BOARD_VERTICAL_SLACK = 8.dp
private val MIN_BOARD_SIZE = 200.dp
