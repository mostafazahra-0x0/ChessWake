package com.mostafazahra.chesswake.alarm.domain

import com.mostafazahra.chesswake.puzzle.domain.PuzzleTheme
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * A user-defined alarm.
 *
 * Deliberately a plain data class with no Android types in it, so the scheduling
 * maths in [AlarmTimes] can be unit-tested on the JVM.
 *
 * @property repeatDays empty means "once" — it fires on the next occurrence of
 *   [hour]:[minute] and then switches itself off.
 * @property puzzleTheme restricts which puzzles this alarm may show; null means
 *   any theme.
 * @property maxDifficulty caps puzzle difficulty, so a gentle 6am alarm can stick
 *   to mate-in-ones.
 * @property requirePuzzle when false the alarm can be dismissed with a long press,
 *   which is the escape hatch for mornings when chess is genuinely the wrong ask.
 * @property soundUri null means the bundled alarm tone.
 */
data class Alarm(
    val id: Long = 0L,
    val hour: Int = 7,
    val minute: Int = 0,
    val repeatDays: Set<DayOfWeek> = emptySet(),
    val enabled: Boolean = true,
    val label: String = "",
    val puzzleTheme: PuzzleTheme? = null,
    val maxDifficulty: Int = 5,
    val vibrate: Boolean = true,
    val soundUri: String? = null,
    val volume: Float = 1.0f,
    val snoozeMinutes: Int = 5,
    val maxSnoozes: Int = 3,
    val requirePuzzle: Boolean = true,
    val mirrorToSleepAsAndroid: Boolean = false,
) {

    /** True when this alarm repeats on at least one weekday. */
    val isRepeating: Boolean get() = repeatDays.isNotEmpty()

    /** `07:30`, always two digits per field. */
    val timeLabel: String get() = "%02d:%02d".format(hour, minute)

    /** Human readable repeat rule for list rows. */
    val repeatLabel: String
        get() = when {
            repeatDays.isEmpty() -> "Once"
            repeatDays.size == 7 -> "Every day"
            repeatDays == WEEKDAYS -> "Weekdays"
            repeatDays == WEEKEND -> "Weekends"
            else -> DAY_ORDER.filter { it in repeatDays }.joinToString(", ") { it.shortName }
        }

    /** What the alarm row should say about its puzzle, e.g. "Mate in one · up to 2". */
    val puzzleLabel: String
        get() = buildString {
            append(puzzleTheme?.displayName ?: "Any puzzle")
            if (maxDifficulty < 5) append(" · up to difficulty $maxDifficulty")
            if (!requirePuzzle) append(" · dismissible")
        }

    companion object {
        val DAY_ORDER: List<DayOfWeek> = listOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
        )

        val WEEKDAYS: Set<DayOfWeek> = DAY_ORDER.take(5).toSet()
        val WEEKEND: Set<DayOfWeek> = DAY_ORDER.takeLast(2).toSet()

        /** A sensible default alarm: 07:00 on weekdays, easiest puzzles only. */
        val DEFAULT = Alarm(
            hour = 7,
            minute = 0,
            repeatDays = WEEKDAYS,
            label = "Wake up",
            maxDifficulty = 2,
        )
    }
}

/** Short three-letter names, because `DayOfWeek.toString()` is verbose in a list row. */
val DayOfWeek.shortName: String
    get() = when (this) {
        DayOfWeek.MONDAY -> "Mon"
        DayOfWeek.TUESDAY -> "Tue"
        DayOfWeek.WEDNESDAY -> "Wed"
        DayOfWeek.THURSDAY -> "Thu"
        DayOfWeek.FRIDAY -> "Fri"
        DayOfWeek.SATURDAY -> "Sat"
        DayOfWeek.SUNDAY -> "Sun"
    }

/**
 * When an alarm should next fire.
 *
 * Pure functions over [ZonedDateTime] so they can be tested without Robolectric,
 * and so DST and timezone changes are handled by `java.time` rather than by
 * hand-rolled millisecond arithmetic.
 */
object AlarmTimes {

    /**
     * The next moment at or after [now] when [alarm] should fire.
     *
     * For a repeating alarm this is the next matching weekday; for a one-shot
     * alarm it is today's time if that is still in the future, otherwise
     * tomorrow's — matching what every clock app does when you set 07:00 at 09:00.
     */
    fun nextTrigger(alarm: Alarm, now: ZonedDateTime): ZonedDateTime {
        val today = now
            .truncatedTo(ChronoUnit.MINUTES)
            .withHour(alarm.hour.coerceIn(0, 23))
            .withMinute(alarm.minute.coerceIn(0, 59))
            .withSecond(0)
            .withNano(0)

        if (alarm.repeatDays.isEmpty()) {
            return if (today.isAfter(now)) today else today.plusDays(1)
        }

        // Eight days covers a full week plus the "already passed today" case.
        for (offset in 0L..8L) {
            val candidate = today.plusDays(offset)
            if (candidate.isAfter(now) && candidate.dayOfWeek in alarm.repeatDays) {
                return candidate
            }
        }
        // Unreachable while repeatDays is non-empty, but never return a past time.
        return today.plusDays(1)
    }

    /** Epoch millis for [AlarmManager], which works in UTC instants. */
    fun nextTriggerMillis(alarm: Alarm, now: ZonedDateTime): Long =
        nextTrigger(alarm, now).toInstant().toEpochMilli()

    /** When a snooze pressed at [now] should ring again. */
    fun snoozeTrigger(now: ZonedDateTime, minutes: Int): ZonedDateTime =
        now.plusMinutes(minutes.coerceIn(1, 60).toLong())

    /**
     * `in 2 h 15 min` style text for the "next alarm" banner.
     *
     * Rounded down to whole minutes, and deliberately worded so it stays correct
     * across a midnight boundary.
     */
    fun relativeLabel(from: ZonedDateTime, to: ZonedDateTime): String {
        if (!to.isAfter(from)) return "now"
        val totalMinutes = ChronoUnit.MINUTES.between(from, to)
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> "in $days d ${hours} h"
            hours > 0 -> "in $hours h $minutes min"
            minutes > 0 -> "in $minutes min"
            else -> "in less than a minute"
        }
    }
}
