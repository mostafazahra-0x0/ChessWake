package com.mostafazahra.chesswake.puzzle.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mostafazahra.chesswake.R
import com.mostafazahra.chesswake.chess.Square
import com.mostafazahra.chesswake.puzzle.domain.PuzzlePhase
import com.mostafazahra.chesswake.puzzle.domain.PuzzleTheme
import kotlin.math.roundToInt

/**
 * Free-play puzzle training.
 *
 * Same board, same engine, same tap rules as the alarm — the difference is that
 * nothing is blocked here: you can restart, ask for the hint as soon as it
 * unlocks, filter by theme, and skip to another puzzle at any time. Attempts are
 * recorded so the stats screen can tell you whether mate-in-ones are getting
 * faster.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(
    modifier: Modifier = Modifier,
    viewModel: PuzzleViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    var showFilters by remember { mutableStateOf(false) }

    LaunchedEffect(state.feedbackTick) {
        if (state.feedbackTick == 0) return@LaunchedEffect
        when (state.feedbackKind) {
            // Compose 1.7 exposes only LongPress and TextHandleMove; Confirm,
            // Reject and VirtualKey arrived in 1.8. A wrong move and a solved
            // puzzle share the strong buzz - the screen says which is which.
            PuzzleFeedback.WRONG -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            PuzzleFeedback.CORRECT -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            PuzzleFeedback.SOLVED -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.practice_title)) },
                actions = {
                    IconButton(onClick = { showFilters = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = stringResource(R.string.practice_filter_theme),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.onBackground,
                        maxLines = 1,
                    )
                    Text(
                        text = state.goalLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.stats_solved_fraction,
                        state.solvedThisSession,
                        state.attemptedThisSession,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (state.loading || state.puzzle == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.puzzle == null && !state.loading) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.practice_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                BoardArea(
                    state = state,
                    onSquareTap = viewModel::onSquareTap,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (state.phase == PuzzlePhase.SOLVED) {
                SolvedBanner(onNext = viewModel::newPuzzle)
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = when {
                    state.message.isBlank() -> colors.onSurfaceVariant
                    state.messageIsError -> colors.error
                    else -> colors.primary
                },
                maxLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )

            if (state.hintVisible && state.hint.isNotBlank()) {
                Text(
                    text = stringResource(R.string.practice_hint_line, state.hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.tertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (state.playedSans.isNotEmpty()) {
                Text(
                    text = formatMoveList(state.playedSans),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 4.dp),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = viewModel::restart,
                    enabled = state.puzzle != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(stringResource(R.string.practice_restart))
                }

                OutlinedButton(
                    onClick = viewModel::showHint,
                    enabled = state.hintUnlocked && !state.hintVisible,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Lightbulb, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(stringResource(R.string.practice_hint))
                }

                Button(onClick = viewModel::newPuzzle, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(stringResource(R.string.practice_new_puzzle))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showFilters) {
        FilterSheet(
            state = state,
            onDismiss = { showFilters = false },
            onThemeChange = viewModel::setThemeFilter,
            onDifficultyChange = viewModel::setMaxDifficulty,
            onApply = {
                showFilters = false
                viewModel.newPuzzle()
            },
        )
    }
}

@Composable
private fun BoardArea(
    state: PracticeUiState,
    onSquareTap: (Square) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The board is a square, so it takes the smaller of the two dimensions the
    // remaining layout leaves it and centres itself in the other.
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val boardSize = max(min(maxWidth, maxHeight), MIN_BOARD_SIZE)
        ChessBoard(
            position = state.position,
            orientation = state.solverColor,
            highlights = state.highlights,
            showCoordinates = state.showCoordinates,
            onSquareTap = onSquareTap,
            modifier = Modifier.size(boardSize),
        )
    }
}

@Composable
private fun SolvedBanner(onNext: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = colors.onPrimaryContainer,
            )
            Text(
                text = stringResource(R.string.practice_solved),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onPrimaryContainer,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            Button(onClick = onNext) {
                Text(stringResource(R.string.practice_next))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    state: PracticeUiState,
    onDismiss: () -> Unit,
    onThemeChange: (PuzzleTheme?) -> Unit,
    onDifficultyChange: (Int) -> Unit,
    onApply: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(
                text = stringResource(R.string.practice_filter_theme),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = colors.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.themeFilter == null,
                    onClick = { onThemeChange(null) },
                    label = { Text(stringResource(R.string.alarm_edit_theme_any)) },
                )
                PuzzleTheme.entries.forEach { theme ->
                    FilterChip(
                        selected = state.themeFilter == theme,
                        onClick = { onThemeChange(theme) },
                        label = { Text(theme.displayName) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.practice_filter_difficulty),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.format_level, state.maxDifficulty),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Slider(
                value = state.maxDifficulty.toFloat(),
                onValueChange = { onDifficultyChange(it.roundToInt()) },
                valueRange = 1f..5f,
                steps = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = stringResource(R.string.practice_puzzle_count, state.availableCount),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onApply,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(stringResource(R.string.practice_new_puzzle))
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Renders SAN moves as a score sheet: `1. e4 e5  2. Nf3 Nc6`.
 *
 * Internal rather than private so the unit tests can pin the numbering.
 */
internal fun formatMoveList(sans: List<String>): String = buildString {
    sans.forEachIndexed { index, san ->
        if (index % 2 == 0) {
            if (isNotEmpty()) append("  ")
            append((index / 2) + 1).append(". ")
        } else {
            append(' ')
        }
        append(san)
    }
}

private val MIN_BOARD_SIZE = 220.dp
