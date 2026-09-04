package com.mostafazahra.chesswake.alarm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.activity.compose.BackHandler
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mostafazahra.chesswake.R
import com.mostafazahra.chesswake.alarm.domain.Alarm
import com.mostafazahra.chesswake.puzzle.domain.PuzzleTheme
import com.mostafazahra.chesswake.ui.components.FlowChipRow
import com.mostafazahra.chesswake.ui.components.LabeledSwitch
import com.mostafazahra.chesswake.ui.components.PresetChip
import com.mostafazahra.chesswake.ui.components.SectionCard
import com.mostafazahra.chesswake.ui.components.SliderRow
import java.time.DayOfWeek
import kotlin.math.roundToInt

/**
 * Create or edit one alarm.
 *
 * The form is a single scrolling column of labelled cards rather than a wizard:
 * setting an alarm is something people do half-awake too, and every extra screen
 * is a chance to abandon it. New alarms come pre-filled from the app defaults, so
 * the common case is "tap the time, tap save".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(
    alarmId: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlarmEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showTimePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                AlarmEditEvent.Done -> onDone()
            }
        }
    }

    // A dirty form must not be lost to a stray back gesture.
    BackHandler(enabled = state.dirty) { showDiscardConfirm = true }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isNew) R.string.alarm_edit_title_new else R.string.alarm_edit_title_edit,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.dirty) showDiscardConfirm = true else onDone()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_cancel),
                        )
                    }
                },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                            )
                        }
                    }
                    IconButton(onClick = viewModel::save, enabled = !state.loading) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = stringResource(R.string.action_save),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TimeCard(state = state, onPickTime = { showTimePicker = true }, viewModel = viewModel)
                RepeatCard(state = state, viewModel = viewModel)
                LabelCard(state = state, viewModel = viewModel)
                PuzzleCard(state = state, viewModel = viewModel)
                SoundCard(state = state, viewModel = viewModel)
                SnoozeCard(state = state, viewModel = viewModel)
                IntegrationCard(state = state, viewModel = viewModel)

                Button(
                    onClick = viewModel::save,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(
                        text = stringResource(R.string.action_save),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showTimePicker) {
        AlarmTimePickerDialog(
            initialHour = state.hour,
            initialMinute = state.minute,
            onDismiss = { showTimePicker = false },
            onConfirm = { hour, minute ->
                viewModel.setTime(hour, minute)
                showTimePicker = false
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.alarms_delete_title)) },
            text = { Text(stringResource(R.string.alarms_delete_body, state.label.ifBlank { state.timeLabel })) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.delete()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.alarm_edit_discard_title)) },
            text = { Text(stringResource(R.string.alarm_edit_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    onDone()
                }) { Text(stringResource(R.string.alarm_edit_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun TimeCard(state: AlarmEditUiState, onPickTime: () -> Unit, viewModel: AlarmEditViewModel) {
    val colors = MaterialTheme.colorScheme
    SectionCard(title = stringResource(R.string.alarm_edit_section_time)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.timeLabel,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (state.enabled) colors.onSurface else colors.onSurfaceVariant,
                )
                TextButton(onClick = onPickTime) {
                    Text(stringResource(R.string.alarm_edit_change_time))
                }
                if (state.nextTriggerText.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.alarms_next_ring, state.nextTriggerText),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.primary,
                    )
                }
            }
            Switch(
                checked = state.enabled,
                onCheckedChange = viewModel::setEnabled,
            )
        }
    }
}

@Composable
private fun RepeatCard(state: AlarmEditUiState, viewModel: AlarmEditViewModel) {
    val colors = MaterialTheme.colorScheme
    SectionCard(title = stringResource(R.string.alarm_edit_section_repeat)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PresetChip(
                label = stringResource(R.string.alarm_edit_repeat_never),
                selected = state.repeatDays.isEmpty(),
                onClick = { viewModel.setRepeatDays(emptySet()) },
                modifier = Modifier.weight(1f),
            )
            PresetChip(
                label = stringResource(R.string.alarm_edit_repeat_weekdays),
                selected = state.repeatDays == Alarm.WEEKDAYS,
                onClick = { viewModel.setRepeatDays(Alarm.WEEKDAYS) },
                modifier = Modifier.weight(1f),
            )
            PresetChip(
                label = stringResource(R.string.alarm_edit_repeat_weekend),
                selected = state.repeatDays == Alarm.WEEKEND,
                onClick = { viewModel.setRepeatDays(Alarm.WEEKEND) },
                modifier = Modifier.weight(1f),
            )
            PresetChip(
                label = stringResource(R.string.alarm_edit_repeat_every_day),
                selected = state.repeatDays.size == Alarm.DAY_ORDER.size,
                onClick = { viewModel.setRepeatDays(Alarm.DAY_ORDER.toSet()) },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Alarm.DAY_ORDER.forEach { day ->
                DayToggle(
                    day = day,
                    selected = day in state.repeatDays,
                    onClick = { viewModel.toggleDay(day) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Text(
            text = state.repeatLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun DayToggle(day: DayOfWeek, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = day.name.take(1),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        },
        modifier = modifier.height(40.dp),
        shape = MaterialTheme.shapes.medium,
    )
}

@Composable
private fun LabelCard(state: AlarmEditUiState, viewModel: AlarmEditViewModel) {
    SectionCard(title = stringResource(R.string.alarm_edit_label)) {
        OutlinedTextField(
            value = state.label,
            onValueChange = viewModel::setLabel,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.alarm_edit_label_hint)) },
            shape = MaterialTheme.shapes.medium,
        )
    }
}

@Composable
private fun PuzzleCard(state: AlarmEditUiState, viewModel: AlarmEditViewModel) {
    SectionCard(title = stringResource(R.string.alarm_edit_section_puzzle)) {
        LabeledSwitch(
            title = stringResource(R.string.alarm_edit_require_puzzle),
            subtitle = stringResource(R.string.alarm_edit_require_puzzle_summary),
            checked = state.requirePuzzle,
            onCheckedChange = viewModel::setRequirePuzzle,
        )

        if (state.requirePuzzle) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.alarm_edit_theme),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            // "Any" plus one chip per theme: an explicit null is a real choice
            // here, because it widens the pool the alarm can draw from.
            FlowChipRow {
                FilterChip(
                    selected = state.puzzleTheme == null,
                    onClick = { viewModel.setPuzzleTheme(null) },
                    label = { Text(stringResource(R.string.alarm_edit_theme_any)) },
                )
                PuzzleTheme.entries.forEach { theme ->
                    FilterChip(
                        selected = state.puzzleTheme == theme,
                        onClick = { viewModel.setPuzzleTheme(theme) },
                        label = { Text(theme.displayName) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            SliderRow(
                title = stringResource(R.string.alarm_edit_difficulty),
                value = state.maxDifficulty.toFloat(),
                valueRange = 1f..5f,
                steps = 3,
                valueLabel = stringResource(R.string.format_level, state.maxDifficulty),
                onValueChange = { viewModel.setMaxDifficulty(it.roundToInt()) },
            )
        }
    }
}

@Composable
private fun SoundCard(state: AlarmEditUiState, viewModel: AlarmEditViewModel) {
    SectionCard(title = stringResource(R.string.alarm_edit_section_sound)) {
        SliderRow(
            title = stringResource(R.string.alarm_edit_volume),
            value = state.volume,
            valueRange = 0f..1f,
            steps = 0,
            valueLabel = stringResource(R.string.format_percent, (state.volume * 100).roundToInt()),
            onValueChange = viewModel::setVolume,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LabeledSwitch(
            title = stringResource(R.string.alarm_edit_vibrate),
            subtitle = null,
            checked = state.vibrate,
            onCheckedChange = viewModel::setVibrate,
        )
    }
}

@Composable
private fun SnoozeCard(state: AlarmEditUiState, viewModel: AlarmEditViewModel) {
    SectionCard(title = stringResource(R.string.alarm_edit_section_snooze)) {
        SliderRow(
            title = stringResource(R.string.alarm_edit_snooze_length),
            value = state.snoozeMinutes.toFloat(),
            valueRange = 1f..30f,
            steps = 28,
            valueLabel = stringResource(R.string.format_minutes, state.snoozeMinutes),
            onValueChange = { viewModel.setSnoozeMinutes(it.roundToInt()) },
        )
        Spacer(modifier = Modifier.height(8.dp))
        SliderRow(
            title = stringResource(R.string.alarm_edit_max_snoozes),
            value = state.maxSnoozes.toFloat(),
            valueRange = 0f..10f,
            steps = 9,
            valueLabel = if (state.maxSnoozes == 0) {
                stringResource(R.string.off)
            } else {
                state.maxSnoozes.toString()
            },
            onValueChange = { viewModel.setMaxSnoozes(it.roundToInt()) },
        )
        Text(
            text = stringResource(R.string.alarm_edit_max_snoozes_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IntegrationCard(state: AlarmEditUiState, viewModel: AlarmEditViewModel) {
    val colors = MaterialTheme.colorScheme
    SectionCard(title = stringResource(R.string.alarm_edit_section_advanced)) {
        LabeledSwitch(
            title = stringResource(R.string.alarm_edit_mirror_saa),
            subtitle = stringResource(R.string.alarm_edit_mirror_saa_summary),
            checked = state.mirrorToSleepAsAndroid,
            onCheckedChange = viewModel::setMirrorToSleepAsAndroid,
            enabled = state.sleepAsAndroidInstalled,
        )
        if (!state.sleepAsAndroidInstalled) {
            Text(
                text = stringResource(R.string.settings_sleep_as_android_missing),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    // `is24Hour = true`: alarm clocks are read at a glance, and a 12-hour dial
    // forces the user to also verify AM/PM while half asleep.
    val pickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(pickerState.hour, pickerState.minute) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        text = {
            Box(contentAlignment = Alignment.Center) {
                TimePicker(state = pickerState)
            }
        },
    )
}
