package com.mostafazahra.chesswake.settings.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.net.Uri
import com.mostafazahra.chesswake.BuildConfig
import com.mostafazahra.chesswake.R
import com.mostafazahra.chesswake.puzzle.domain.PuzzleTheme
import com.mostafazahra.chesswake.settings.domain.AppSettings
import com.mostafazahra.chesswake.settings.domain.ThemeMode
import com.mostafazahra.chesswake.ui.components.ActionRow
import com.mostafazahra.chesswake.ui.components.FlowChipRow
import com.mostafazahra.chesswake.ui.components.LabeledSwitch
import com.mostafazahra.chesswake.ui.components.PresetChip
import com.mostafazahra.chesswake.ui.components.SectionCard
import com.mostafazahra.chesswake.ui.components.SliderRow
import kotlin.math.roundToInt

/**
 * Preferences, grouped by the question each one answers.
 *
 * The groups are ordered by how often they matter: appearance first because it is
 * what people tweak immediately, alarm defaults next because they change what
 * every new alarm does, and the integration and about sections last because they
 * are read once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val exactAlarmsAllowed by viewModel.exactAlarms.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var showResetConfirm by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The confirm dialog owns the back button while it is open.
    BackHandler(enabled = showResetConfirm) { showResetConfirm = false }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "appearance") { AppearanceSection(settings = settings, viewModel = viewModel) }
            item(key = "alarmDefaults") { AlarmDefaultsSection(settings = settings, viewModel = viewModel) }
            item(key = "sound") { SoundSection(settings = settings, viewModel = viewModel) }
            item(key = "reliability") {
                ReliabilitySection(exactAlarmsAllowed = exactAlarmsAllowed, viewModel = viewModel)
            }
            item(key = "integrations") { IntegrationsSection(settings = settings, viewModel = viewModel) }
            item(key = "about") { AboutSection(onReset = { showResetConfirm = true }) }
            item(key = "spacer") { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.settings_reset_title)) },
            text = { Text(stringResource(R.string.settings_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    viewModel.resetAll()
                }) { Text(stringResource(R.string.settings_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun AppearanceSection(settings: AppSettings, viewModel: SettingsViewModel) {
    SectionCard(title = stringResource(R.string.settings_section_appearance)) {
        Text(
            text = stringResource(R.string.settings_theme),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowChipRow {
            ThemeMode.entries.forEach { mode ->
                PresetChip(
                    label = themeModeLabel(mode),
                    selected = settings.themeMode == mode,
                    onClick = { viewModel.setThemeMode(mode) },
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        LabeledSwitch(
            title = stringResource(R.string.settings_dynamic_color),
            subtitle = stringResource(R.string.settings_dynamic_color_summary),
            checked = settings.dynamicColor,
            onCheckedChange = viewModel::setDynamicColor,
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.settings_board),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowChipRow {
            BOARD_PALETTES.forEach { palette ->
                FilterChip(
                    selected = settings.boardLightColor == palette.light &&
                        settings.boardDarkColor == palette.dark,
                    onClick = { viewModel.setBoardColors(palette.light, palette.dark) },
                    label = { Text(stringResource(palette.nameRes)) },
                    leadingIcon = { PaletteSwatch(light = palette.light, dark = palette.dark) },
                )
            }
        }
        TextButton(onClick = viewModel::resetBoardColors) {
            Text(stringResource(R.string.settings_board_reset))
        }

        Spacer(modifier = Modifier.height(4.dp))
        LabeledSwitch(
            title = stringResource(R.string.settings_show_coordinates),
            subtitle = null,
            checked = settings.showCoordinates,
            onCheckedChange = viewModel::setShowCoordinates,
        )
    }
}

@Composable
private fun AlarmDefaultsSection(settings: AppSettings, viewModel: SettingsViewModel) {
    SectionCard(title = stringResource(R.string.settings_section_alarms)) {
        Text(
            text = stringResource(R.string.settings_default_theme),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowChipRow {
            PresetChip(
                label = stringResource(R.string.alarm_edit_theme_any),
                selected = settings.puzzleTheme == null,
                onClick = { viewModel.setPuzzleTheme(null) },
            )
            PuzzleTheme.entries.forEach { theme ->
                PresetChip(
                    label = theme.displayName,
                    selected = settings.puzzleTheme == theme,
                    onClick = { viewModel.setPuzzleTheme(theme) },
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SliderRow(
            title = stringResource(R.string.settings_default_difficulty),
            value = settings.defaultMaxDifficulty.toFloat(),
            valueRange = 1f..5f,
            steps = 3,
            valueLabel = stringResource(R.string.format_level, settings.defaultMaxDifficulty),
            onValueChange = { viewModel.setDefaultMaxDifficulty(it.roundToInt()) },
        )
        SliderRow(
            title = stringResource(R.string.settings_default_snooze),
            value = settings.snoozeMinutes.toFloat(),
            valueRange = 1f..30f,
            steps = 28,
            valueLabel = stringResource(R.string.format_minutes, settings.snoozeMinutes),
            onValueChange = { viewModel.setSnoozeMinutes(it.roundToInt()) },
        )
        SliderRow(
            title = stringResource(R.string.settings_default_max_snoozes),
            value = settings.maxSnoozes.toFloat(),
            valueRange = 0f..10f,
            steps = 9,
            valueLabel = if (settings.maxSnoozes == 0) {
                stringResource(R.string.off)
            } else {
                settings.maxSnoozes.toString()
            },
            onValueChange = { viewModel.setMaxSnoozes(it.roundToInt()) },
        )

        Spacer(modifier = Modifier.height(8.dp))
        LabeledSwitch(
            title = stringResource(R.string.settings_confirm_dismiss),
            subtitle = stringResource(R.string.settings_confirm_dismiss_summary),
            checked = settings.confirmBeforeDismiss,
            onCheckedChange = viewModel::setConfirmBeforeDismiss,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LabeledSwitch(
            title = stringResource(R.string.settings_keep_screen_on),
            subtitle = null,
            checked = settings.keepScreenOnDuringPuzzle,
            onCheckedChange = viewModel::setKeepScreenOn,
        )
    }
}

@Composable
private fun SoundSection(settings: AppSettings, viewModel: SettingsViewModel) {
    SectionCard(title = stringResource(R.string.alarm_edit_section_sound)) {
        SliderRow(
            title = stringResource(R.string.alarm_edit_volume),
            value = settings.volume,
            valueRange = 0f..1f,
            steps = 0,
            valueLabel = stringResource(R.string.format_percent, (settings.volume * 100).roundToInt()),
            onValueChange = viewModel::setVolume,
        )
        SliderRow(
            title = stringResource(R.string.alarm_edit_crescendo),
            value = settings.crescendoSeconds.toFloat(),
            valueRange = 0f..60f,
            steps = 11,
            valueLabel = if (settings.crescendoSeconds == 0) {
                stringResource(R.string.off)
            } else {
                stringResource(R.string.format_seconds, settings.crescendoSeconds)
            },
            onValueChange = { viewModel.setCrescendoSeconds(it.roundToInt()) },
        )
        Text(
            text = stringResource(R.string.alarm_edit_crescendo_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LabeledSwitch(
            title = stringResource(R.string.alarm_edit_vibrate),
            subtitle = null,
            checked = settings.vibrate,
            onCheckedChange = viewModel::setVibrate,
        )
    }
}

@Composable
private fun ReliabilitySection(exactAlarmsAllowed: Boolean, viewModel: SettingsViewModel) {
    val colors = MaterialTheme.colorScheme
    SectionCard(title = stringResource(R.string.alarms_reliability_title)) {
        if (!exactAlarmsAllowed) {
            Text(
                text = stringResource(R.string.alarms_exact_body),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.error,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        ActionRow(
            title = stringResource(R.string.alarms_exact_title),
            subtitle = stringResource(R.string.alarms_exact_body),
            onClick = viewModel::openExactAlarmSettings,
        )
        ActionRow(
            title = stringResource(R.string.settings_battery),
            subtitle = stringResource(R.string.settings_battery_summary),
            onClick = viewModel::openBatterySettings,
        )
    }
}

@Composable
private fun IntegrationsSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val installed = viewModel.sleepAsAndroidInstalled
    SectionCard(title = stringResource(R.string.settings_section_integrations)) {
        LabeledSwitch(
            title = stringResource(R.string.settings_sleep_as_android),
            subtitle = stringResource(R.string.settings_sleep_as_android_summary),
            checked = settings.sleepAsAndroidEnabled && installed,
            onCheckedChange = viewModel::setSleepAsAndroidEnabled,
            enabled = installed,
        )
        if (!installed) {
            Text(
                text = stringResource(R.string.settings_sleep_as_android_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun AboutSection(onReset: () -> Unit) {
    val context = LocalContext.current
    val sourceUrl = stringResource(R.string.settings_source_url)

    SectionCard(title = stringResource(R.string.settings_section_about)) {
        Text(
            text = stringResource(R.string.settings_privacy_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        ActionRow(
            title = stringResource(R.string.settings_source),
            subtitle = sourceUrl,
            onClick = {
                // Opens in the browser; ChessWake itself never touches the network.
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sourceUrl)))
                }
            },
        )
        Text(
            text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        Button(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(imageVector = Icons.Outlined.RestartAlt, contentDescription = null)
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.settings_reset))
        }
    }
}

/** Two circles showing a board palette's light and dark squares. */
@Composable
private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
    ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
    ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
}

@Composable
private fun PaletteSwatch(light: Long, dark: Long) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(light)),
        )
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(dark)),
        )
    }
}

private data class BoardPalette(val nameRes: Int, val light: Long, val dark: Long)

/**
 * Board palettes rather than a colour picker.
 *
 * A picker invites combinations that fail at 6am (a pale piece on a pale square);
 * four hand-checked pairs keep every piece readable while still letting the board
 * feel personal.
 */
private val BOARD_PALETTES = listOf(
    BoardPalette(R.string.settings_board_classic, 0xFFF0D9B5, 0xFFB58863),
    BoardPalette(R.string.settings_board_sage, 0xFFEFF2E6, 0xFF7A9A7E),
    BoardPalette(R.string.settings_board_slate, 0xFFDEE3E6, 0xFF78899A),
    BoardPalette(R.string.settings_board_midnight, 0xFFC9D1D9, 0xFF3B4252),
)
