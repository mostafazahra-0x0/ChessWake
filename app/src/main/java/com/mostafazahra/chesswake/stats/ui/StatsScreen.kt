package com.mostafazahra.chesswake.stats.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mostafazahra.chesswake.R

/**
 * Everything the app knows about the user, on one screen.
 *
 * The streak is the headline because it is the only number that reflects the
 * thing ChessWake is actually for: waking up and solving, day after day. The rest
 * is supporting detail, and the whole screen is derived from a local table — the
 * privacy note at the bottom says so explicitly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    modifier: Modifier = Modifier,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    var showClearConfirm by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                actions = {
                    if (!state.isEmpty) {
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = stringResource(R.string.stats_clear),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.isEmpty -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Insights,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = colors.primary,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.stats_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "streak") { StreakCard(state = state) }

                item(key = "grid") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatCard(
                                label = stringResource(R.string.stats_attempts),
                                value = state.attempts.toString(),
                                modifier = Modifier.weight(1f),
                            )
                            StatCard(
                                label = stringResource(R.string.stats_solved),
                                value = state.solved.toString(),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatCard(
                                label = stringResource(R.string.stats_accuracy),
                                value = stringResource(R.string.format_percent, state.accuracyPercent),
                                modifier = Modifier.weight(1f),
                            )
                            StatCard(
                                label = stringResource(R.string.stats_average_time),
                                value = formatDuration(state.averageMillis),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatCard(
                                label = stringResource(R.string.stats_alarm_wakeups),
                                value = state.alarmWakeups.toString(),
                                icon = { Icon(Icons.Outlined.Alarm, contentDescription = null) },
                                modifier = Modifier.weight(1f),
                            )
                            StatCard(
                                label = stringResource(R.string.stats_best_time),
                                value = if (state.hasBest) formatDuration(state.bestMillis) else "—",
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                if (state.themes.isNotEmpty()) {
                    item(key = "themes_header") {
                        SectionHeader(stringResource(R.string.stats_by_theme))
                    }
                    items(state.themes, key = { it.themeName }) { row -> ThemeStatRow(row = row) }
                }

                if (state.recent.isNotEmpty()) {
                    item(key = "recent_header") {
                        SectionHeader(stringResource(R.string.stats_recent))
                    }
                    items(state.recent) { row -> RecentAttemptRow(row = row) }
                }

                item(key = "privacy") { PrivacyNote() }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.stats_clear_title)) },
            text = { Text(stringResource(R.string.stats_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clearHistory()
                }) { Text(stringResource(R.string.stats_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun StreakCard(state: StatsUiState) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.LocalFireDepartment,
                contentDescription = null,
                tint = colors.onPrimaryContainer,
                modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.stats_current_streak),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onPrimaryContainer,
                )
                Text(
                    text = stringResource(R.string.format_days, state.currentStreakDays),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = colors.onPrimaryContainer,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(R.string.stats_longest_streak),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onPrimaryContainer,
                    textAlign = TextAlign.End,
                )
                Text(
                    text = stringResource(R.string.format_days, state.longestStreakDays),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Card(modifier = modifier, shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) { icon() }
                    Spacer(modifier = Modifier.size(6.dp))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ThemeStatRow(row: ThemeRow) {
    val colors = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.themeName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                )
                Text(
                    text = stringResource(R.string.stats_solved_fraction, row.solved, row.total) +
                        "  ·  " + stringResource(R.string.format_percent, row.accuracyPercent),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Text(
                text = formatDuration(row.averageMillis),
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
            )
        }
    }
}

@Composable
private fun RecentAttemptRow(row: RecentRow) {
    val colors = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (row.fromAlarm) Icons.Outlined.Alarm else Icons.Outlined.Insights,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (row.solved) colors.primary else colors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.puzzleName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = listOfNotNull(
                        row.whenText,
                        if (row.solved) {
                            formatDuration(row.durationMillis)
                        } else {
                            stringResource(R.string.stats_unsolved)
                        },
                        if (row.wrongAttempts > 0) {
                            stringResource(R.string.stats_misses, row.wrongAttempts)
                        } else {
                            null
                        },
                    ).joinToString(SEPARATOR),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private const val SEPARATOR = "  ·  "

@Composable
private fun PrivacyNote() {
    val colors = MaterialTheme.colorScheme
    Text(
        text = stringResource(R.string.settings_privacy_summary),
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
    )
}
