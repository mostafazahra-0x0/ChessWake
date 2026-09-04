package com.mostafazahra.chesswake.alarm.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mostafazahra.chesswake.R
import com.mostafazahra.chesswake.alarm.domain.Alarm

/**
 * The alarm list: the first thing the app shows.
 *
 * Beyond listing alarms it surfaces the two system settings that silently break
 * alarm apps — the exact-alarm permission and notifications — because an alarm
 * that rings late or inaudibly is worse than no alarm at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmsScreen(
    onCreateAlarm: () -> Unit,
    onEditAlarm: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlarmsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var alarmPendingDelete by remember { mutableStateOf<Alarm?>(null) }

    // Permissions can be revoked from the system settings while we are in the
    // background, so re-read them every time the list becomes visible.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.alarms_title)) })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateAlarm) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.alarms_new),
                )
            }
        },
    ) { innerPadding ->
        when {
            state.loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            state.alarms.isEmpty() && !state.hasPermissionProblem -> EmptyAlarms(
                onCreateAlarm = onCreateAlarm,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!state.permissions.exactAlarmsAllowed) {
                    item(key = "permission_exact") {
                        WarningCard(
                            icon = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
                            title = stringResource(R.string.alarms_exact_title),
                            body = stringResource(R.string.alarms_exact_body),
                            actionLabel = stringResource(R.string.action_allow),
                            onAction = viewModel::openExactAlarmSettings,
                        )
                    }
                }

                if (!state.permissions.notificationsEnabled) {
                    item(key = "permission_notifications") {
                        WarningCard(
                            icon = { Icon(Icons.Outlined.NotificationsActive, contentDescription = null) },
                            title = stringResource(R.string.alarms_notifications_title),
                            body = stringResource(R.string.alarms_notifications_body),
                            actionLabel = stringResource(R.string.action_allow),
                            onAction = viewModel::openNotificationSettings,
                        )
                    }
                }

                if (state.showReliabilityTip) {
                    item(key = "reliability") {
                        WarningCard(
                            icon = { Icon(Icons.Outlined.Alarm, contentDescription = null) },
                            title = stringResource(R.string.alarms_reliability_title),
                            body = stringResource(R.string.alarms_reliability_body),
                            actionLabel = stringResource(R.string.settings_battery),
                            onAction = viewModel::openBatterySettings,
                            onDismiss = viewModel::dismissReliabilityTip,
                        )
                    }
                }

                if (state.alarms.isEmpty()) {
                    item(key = "empty") {
                        EmptyAlarms(onCreateAlarm = onCreateAlarm, modifier = Modifier.fillParentMaxHeight(0.6f))
                    }
                }

                items(state.alarms, key = { it.alarm.id }) { summary ->
                    AlarmCard(
                        summary = summary,
                        onToggle = { enabled -> viewModel.setEnabled(summary.alarm.id, enabled) },
                        onClick = { onEditAlarm(summary.alarm.id) },
                        onDelete = { alarmPendingDelete = summary.alarm },
                    )
                }
            }
        }
    }

    alarmPendingDelete?.let { alarm ->
        AlertDialog(
            onDismissRequest = { alarmPendingDelete = null },
            title = { Text(stringResource(R.string.alarms_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.alarms_delete_body,
                        alarm.label.ifBlank { alarm.timeLabel },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(alarm.id)
                        alarmPendingDelete = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { alarmPendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** True when a permission banner should be shown even with no alarms at all. */
private val AlarmsUiState.hasPermissionProblem: Boolean
    get() = !permissions.exactAlarmsAllowed || !permissions.notificationsEnabled

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmCard(
    summary: AlarmSummary,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val alarm = summary.alarm
    val toggleDescription = stringResource(R.string.alarms_toggle)

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (alarm.enabled) colors.surface else colors.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = alarm.timeLabel,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (alarm.enabled) colors.onSurface else colors.onSurfaceVariant,
                    )
                    if (alarm.label.isNotBlank()) {
                        Text(
                            text = alarm.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onSurface,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = alarm.repeatLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }

                Switch(
                    checked = alarm.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics { contentDescription = toggleDescription },
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Extension,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (alarm.requirePuzzle) colors.tertiary else colors.onSurfaceVariant,
                )
                Text(
                    text = summary.puzzleLine,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                )
                if (summary.countdown.isNotBlank()) {
                    Text(
                        text = summary.countdown,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primary,
                        maxLines = 1,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.alarms_disabled),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun WarningCard(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.tertiaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(modifier = Modifier.padding(top = 2.dp, end = 12.dp)) { icon() }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.onTertiaryContainer,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onTertiaryContainer,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    if (onDismiss != null) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.action_dismiss))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    TextButton(onClick = onAction) { Text(actionLabel) }
                }
            }
        }
    }
}

@Composable
private fun EmptyAlarms(onCreateAlarm: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Alarm,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = colors.primary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.alarms_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.alarms_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        TextButton(onClick = onCreateAlarm) {
            Text(stringResource(R.string.alarms_new))
        }
    }
}
