package com.mostafazahra.chesswake.alarm.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mostafazahra.chesswake.alarm.data.AlarmRepository
import com.mostafazahra.chesswake.alarm.domain.Alarm
import com.mostafazahra.chesswake.alarm.domain.AlarmTimes
import com.mostafazahra.chesswake.puzzle.domain.PuzzleTheme
import com.mostafazahra.chesswake.settings.data.SettingsRepository
import com.mostafazahra.chesswake.settings.domain.AppSettings
import com.mostafazahra.chesswake.sleepasandroid.SleepAsAndroidBridge
import com.mostafazahra.chesswake.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.DayOfWeek
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Emitted once when the screen should close. */
sealed interface AlarmEditEvent {
    data object Done : AlarmEditEvent
}

/** Editable form state for one alarm. */
data class AlarmEditUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val alarmId: Long = 0L,
    val hour: Int = 7,
    val minute: Int = 0,
    val label: String = "",
    val repeatDays: Set<DayOfWeek> = emptySet(),
    val enabled: Boolean = true,
    val requirePuzzle: Boolean = true,
    val puzzleTheme: PuzzleTheme? = PuzzleTheme.MATE_IN_ONE,
    val maxDifficulty: Int = 2,
    val vibrate: Boolean = true,
    val volume: Float = 1f,
    val snoozeMinutes: Int = 5,
    val maxSnoozes: Int = 3,
    val mirrorToSleepAsAndroid: Boolean = false,
    val sleepAsAndroidInstalled: Boolean = false,
    /** True once any field differs from what was loaded. */
    val dirty: Boolean = false,
    /** `Rings in 7 h 12 min`, or empty for a disabled alarm. */
    val nextTriggerText: String = "",
) {
    val timeLabel: String get() = "%02d:%02d".format(hour, minute)

    val repeatLabel: String
        get() = when {
            repeatDays.isEmpty() -> "Once"
            repeatDays.size == Alarm.DAY_ORDER.size -> "Every day"
            repeatDays == Alarm.WEEKDAYS -> "Weekdays"
            repeatDays == Alarm.WEEKEND -> "Weekend"
            else -> Alarm.DAY_ORDER.filter { it in repeatDays }
                .joinToString(" ") { it.name.take(1) }
        }
}

/**
 * Backs the alarm editor for both "new" and "edit".
 *
 * Which one it is comes from the navigation argument in [SavedStateHandle]:
 * [Routes.NEW_ALARM_ID] means create, anything else loads that row. New alarms are
 * pre-filled from the app defaults in settings, so a user who always wants
 * mate-in-one puzzles never has to touch the puzzle section.
 */
@HiltViewModel
class AlarmEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val alarmRepository: AlarmRepository,
    private val settingsRepository: SettingsRepository,
    private val sleepBridge: SleepAsAndroidBridge,
    private val clock: Clock,
) : ViewModel() {

    private val requestedId: Long =
        savedStateHandle.get<Long>(Routes.ARG_ALARM_ID) ?: Routes.NEW_ALARM_ID

    private val _uiState = MutableStateFlow(AlarmEditUiState())
    val uiState: StateFlow<AlarmEditUiState> = _uiState.asStateFlow()

    private val _events = Channel<AlarmEditEvent>(Channel.BUFFERED)
    val events: Flow<AlarmEditEvent> = _events.receiveAsFlow()

    /** The alarm as loaded, so `dirty` can be computed without a second copy. */
    private var original: Alarm? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val settings = runCatching { settingsRepository.settings.first() }.getOrDefault(AppSettings.DEFAULT)
        val existing = if (requestedId != Routes.NEW_ALARM_ID) {
            runCatching { alarmRepository.byId(requestedId) }.getOrNull()
        } else {
            null
        }
        original = existing

        val installed = runCatching { sleepBridge.isInstalled }.getOrDefault(false)

        val state = existing?.let { alarm ->
            AlarmEditUiState(
                loading = false,
                isNew = false,
                alarmId = alarm.id,
                hour = alarm.hour,
                minute = alarm.minute,
                label = alarm.label,
                repeatDays = alarm.repeatDays,
                enabled = alarm.enabled,
                requirePuzzle = alarm.requirePuzzle,
                puzzleTheme = alarm.puzzleTheme,
                maxDifficulty = alarm.maxDifficulty,
                vibrate = alarm.vibrate,
                volume = alarm.volume,
                snoozeMinutes = alarm.snoozeMinutes,
                maxSnoozes = alarm.maxSnoozes,
                mirrorToSleepAsAndroid = alarm.mirrorToSleepAsAndroid,
                sleepAsAndroidInstalled = installed,
            )
        } ?: AlarmEditUiState(
            loading = false,
            isNew = true,
            alarmId = 0L,
            hour = DEFAULT_HOUR,
            minute = 0,
            label = "",
            // A first alarm that never repeats surprises nobody; defaulting to
            // weekdays matches what most people actually want from an alarm clock.
            repeatDays = Alarm.WEEKDAYS,
            enabled = true,
            requirePuzzle = true,
            puzzleTheme = settings.puzzleTheme,
            maxDifficulty = settings.defaultMaxDifficulty,
            vibrate = settings.vibrate,
            volume = settings.volume,
            snoozeMinutes = settings.snoozeMinutes,
            maxSnoozes = settings.maxSnoozes,
            mirrorToSleepAsAndroid = settings.sleepAsAndroidEnabled,
            sleepAsAndroidInstalled = installed,
        )

        _uiState.value = withNextTrigger(state)
    }

    fun setTime(hour: Int, minute: Int) = mutate {
        withNextTrigger(it.copy(hour = hour.coerceIn(0, 23), minute = minute.coerceIn(0, 59)))
    }

    fun setLabel(label: String) = mutate { it.copy(label = label.take(MAX_LABEL_LENGTH)) }

    fun setEnabled(enabled: Boolean) = mutate { withNextTrigger(it.copy(enabled = enabled)) }

    fun toggleDay(day: DayOfWeek) = mutate {
        val days = if (day in it.repeatDays) it.repeatDays - day else it.repeatDays + day
        withNextTrigger(it.copy(repeatDays = days))
    }

    fun setRepeatDays(days: Set<DayOfWeek>) = mutate { withNextTrigger(it.copy(repeatDays = days)) }

    fun setRequirePuzzle(required: Boolean) = mutate { it.copy(requirePuzzle = required) }

    fun setPuzzleTheme(theme: PuzzleTheme?) = mutate { withNextTrigger(it.copy(puzzleTheme = theme)) }

    fun setMaxDifficulty(level: Int) = mutate { it.copy(maxDifficulty = level.coerceIn(1, 5)) }

    fun setVibrate(vibrate: Boolean) = mutate { it.copy(vibrate = vibrate) }

    fun setVolume(volume: Float) = mutate { it.copy(volume = volume.coerceIn(0f, 1f)) }

    fun setSnoozeMinutes(minutes: Int) = mutate { it.copy(snoozeMinutes = minutes.coerceIn(1, 60)) }

    fun setMaxSnoozes(count: Int) = mutate { it.copy(maxSnoozes = count.coerceIn(0, 10)) }

    fun setMirrorToSleepAsAndroid(enabled: Boolean) = mutate {
        it.copy(mirrorToSleepAsAndroid = enabled && it.sleepAsAndroidInstalled)
    }

    /** Persists the form and asks the screen to close. */
    fun save() {
        val state = _uiState.value
        if (state.loading) return
        viewModelScope.launch {
            val saved = runCatching {
                alarmRepository.save(
                    Alarm(
                        id = if (state.isNew) 0L else state.alarmId,
                        hour = state.hour,
                        minute = state.minute,
                        repeatDays = state.repeatDays,
                        enabled = state.enabled,
                        label = state.label.trim(),
                        puzzleTheme = state.puzzleTheme,
                        maxDifficulty = state.maxDifficulty,
                        vibrate = state.vibrate,
                        soundUri = null,
                        volume = state.volume,
                        snoozeMinutes = state.snoozeMinutes,
                        maxSnoozes = state.maxSnoozes,
                        requirePuzzle = state.requirePuzzle,
                        mirrorToSleepAsAndroid = state.mirrorToSleepAsAndroid,
                    ),
                )
            }.isSuccess
            if (saved) _events.send(AlarmEditEvent.Done)
        }
    }

    /** Deletes the alarm being edited and closes the screen. */
    fun delete() {
        val state = _uiState.value
        if (state.isNew) {
            viewModelScope.launch { _events.send(AlarmEditEvent.Done) }
            return
        }
        viewModelScope.launch {
            runCatching { alarmRepository.delete(state.alarmId) }
            _events.send(AlarmEditEvent.Done)
        }
    }

    private fun mutate(transform: (AlarmEditUiState) -> AlarmEditUiState) {
        _uiState.update { current ->
            if (current.loading) return@update current
            transform(current).copy(dirty = true)
        }
    }

    /** Recomputes the "rings in …" line whenever something time-related changes. */
    private fun withNextTrigger(state: AlarmEditUiState): AlarmEditUiState {
        if (!state.enabled) return state.copy(nextTriggerText = "")
        val draft = Alarm(
            hour = state.hour,
            minute = state.minute,
            repeatDays = state.repeatDays,
            enabled = true,
            puzzleTheme = state.puzzleTheme,
            maxDifficulty = state.maxDifficulty,
            snoozeMinutes = state.snoozeMinutes,
            maxSnoozes = state.maxSnoozes,
            requirePuzzle = state.requirePuzzle,
        )
        val now = ZonedDateTime.now(clock)
        return state.copy(nextTriggerText = AlarmTimes.relativeLabel(now, AlarmTimes.nextTrigger(draft, now)))
    }

    private companion object {
        const val DEFAULT_HOUR = 7
        const val MAX_LABEL_LENGTH = 60
    }
}
