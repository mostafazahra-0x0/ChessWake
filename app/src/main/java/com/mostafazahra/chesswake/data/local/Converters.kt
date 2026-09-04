package com.mostafazahra.chesswake.data.local

import androidx.room.TypeConverter
import java.time.DayOfWeek

/**
 * Room type converters for the few value types that have no natural SQLite column.
 *
 * Everything is stored as plain text so the database stays readable if you pull
 * it off a device with `adb`, and so no serialisation library is needed.
 */
class Converters {

    @TypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString(SEPARATOR)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split(SEPARATOR)

    @TypeConverter
    fun fromStringSet(value: Set<String>): String = value.joinToString(SEPARATOR)

    @TypeConverter
    fun toStringSet(value: String): Set<String> =
        if (value.isEmpty()) emptySet() else value.split(SEPARATOR).toSet()

    /**
     * Weekdays as a Monday-first ordered string.
     *
     * Sorted on the way in so that `{MON, FRI}` and `{FRI, MON}` produce the same
     * stored value, which keeps equality checks and migrations predictable.
     */
    @TypeConverter
    fun fromDayOfWeekSet(value: Set<DayOfWeek>): String =
        ORDER.filter { it in value }.joinToString(SEPARATOR) { it.name }

    @TypeConverter
    fun toDayOfWeekSet(value: String): Set<DayOfWeek> =
        if (value.isEmpty()) {
            emptySet()
        } else {
            value.split(SEPARATOR).mapNotNull { name ->
                runCatching { DayOfWeek.valueOf(name) }.getOrNull()
            }.toSet()
        }

    @TypeConverter
    fun fromNullableString(value: String?): String = value ?: NULL_MARKER

    @TypeConverter
    fun toNullableString(value: String): String? = if (value == NULL_MARKER) null else value

    companion object {
        /** Comma is safe: no enum name or UCI move contains one. */
        private const val SEPARATOR = ","

        /** Sentinel for null strings, since Room maps `String` columns as NOT NULL. */
        private const val NULL_MARKER = "\u0000"

        private val ORDER: List<DayOfWeek> = listOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
        )
    }
}
