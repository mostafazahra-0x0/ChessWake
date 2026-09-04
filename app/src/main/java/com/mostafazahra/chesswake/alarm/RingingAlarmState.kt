package com.mostafazahra.chesswake.alarm

import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory record of the alarm that is currently ringing.
 *
 * Exists because the ringing flow spans three components that cannot easily talk
 * to each other: `AlarmReceiver` (which knows the alarm id), `AlarmSoundService`
 * (which makes the noise) and `AlarmActivity` (which shows the puzzle). Keeping
 * one shared object avoids passing state through three Intents and makes
 * configuration changes harmless — the puzzle chosen for a ringing alarm stays
 * the same after a rotation.
 *
 * This is deliberately *not* persisted. If the process is killed mid-ring, the
 * alarm id still arrives via the Intent extras, so [AlarmActivity] can recover;
 * the only loss is which puzzle had been picked, and a fresh one is chosen.
 */
@Singleton
class RingingAlarmState @Inject constructor() {

    @Volatile
    var alarmId: Long = NO_ALARM
        private set

    /** Id of the puzzle currently on screen, or null until one has been chosen. */
    @Volatile
    var puzzleId: String? = null
        private set

    /** Epoch millis at which the alarm started ringing, for the elapsed timer. */
    @Volatile
    var ringingSinceMillis: Long = 0L
        private set

    /** How many times the user has snoozed this ringing, across restarts of the alarm. */
    @Volatile
    var snoozeCount: Int = 0
        private set

    @Volatile
    var label: String = ""
        private set

    @Volatile
    var timeLabel: String = ""
        private set

    @Volatile
    var requirePuzzle: Boolean = true
        private set

    /** True while an alarm is ringing and has not been solved or snoozed. */
    val isRinging: Boolean
        get() = alarmId != NO_ALARM

    /** True when the ringing alarm can still be snoozed. */
    fun canSnooze(maxSnoozes: Int): Boolean = maxSnoozes > 0 && snoozeCount < maxSnoozes

    /**
     * Marks [id] as ringing.
     *
     * A repeated call for the alarm that is already ringing keeps the existing
     * puzzle and start time, so a second delivery (broadcast plus full-screen
     * intent) cannot reshuffle the board under the user's finger.
     */
    fun beginRinging(
        id: Long,
        alarmLabel: String,
        timeLabel: String,
        puzzleRequired: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (id == alarmId && isRinging) {
            label = alarmLabel
            this.timeLabel = timeLabel
            return
        }
        alarmId = id
        label = alarmLabel
        this.timeLabel = timeLabel
        requirePuzzle = puzzleRequired
        ringingSinceMillis = nowMillis
        snoozeCount = 0
        puzzleId = null
    }

    /**
     * Remembers the puzzle chosen for this ringing.
     *
     * @return the id now in use — the one passed in, or the already-chosen one if
     *   a puzzle had been picked earlier.
     */
    fun assignPuzzle(id: String): String {
        val existing = puzzleId
        if (existing != null) return existing
        puzzleId = id
        return id
    }

    /** Records a snooze, keeping the same alarm but clearing the puzzle. */
    fun noteSnooze() {
        snoozeCount++
        puzzleId = null
        ringingSinceMillis = System.currentTimeMillis()
    }

    /** Clears everything once the alarm has been dismissed. */
    fun endRinging() {
        alarmId = NO_ALARM
        puzzleId = null
        ringingSinceMillis = 0L
        snoozeCount = 0
        label = ""
        timeLabel = ""
        requirePuzzle = true
    }

    companion object {
        const val NO_ALARM = -1L
    }
}
