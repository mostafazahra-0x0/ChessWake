package com.mostafazahra.chesswake.alarm.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.mostafazahra.chesswake.alarm.domain.Alarm
import com.mostafazahra.chesswake.puzzle.domain.PuzzleTheme
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek

/**
 * Persisted form of [Alarm].
 *
 * Enums are stored by name so that renaming an enum constant is a visible
 * compile-time problem rather than a silent data corruption at runtime.
 */
@Entity(tableName = "alarms", indices = [Index("enabled"), Index("nextTriggerAt")])
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "hour") val hour: Int,
    @ColumnInfo(name = "minute") val minute: Int,
    @ColumnInfo(name = "repeatDays") val repeatDays: Set<DayOfWeek>,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "puzzleTheme") val puzzleTheme: String?,
    @ColumnInfo(name = "maxDifficulty") val maxDifficulty: Int,
    @ColumnInfo(name = "vibrate") val vibrate: Boolean,
    @ColumnInfo(name = "soundUri") val soundUri: String?,
    @ColumnInfo(name = "volume") val volume: Float,
    @ColumnInfo(name = "snoozeMinutes") val snoozeMinutes: Int,
    @ColumnInfo(name = "maxSnoozes") val maxSnoozes: Int,
    @ColumnInfo(name = "requirePuzzle") val requirePuzzle: Boolean,
    @ColumnInfo(name = "mirrorToSleepAsAndroid") val mirrorToSleepAsAndroid: Boolean,

    /** When this alarm is currently booked to ring, or null when it is off. */
    @ColumnInfo(name = "nextTriggerAt") val nextTriggerAt: Long?,
    @ColumnInfo(name = "createdAt") val createdAt: Long,
    @ColumnInfo(name = "updatedAt") val updatedAt: Long,
)

fun AlarmEntity.toDomain(): Alarm = Alarm(
    id = id,
    hour = hour,
    minute = minute,
    repeatDays = repeatDays,
    enabled = enabled,
    label = label,
    puzzleTheme = puzzleTheme?.let { runCatching { PuzzleTheme.valueOf(it) }.getOrNull() },
    maxDifficulty = maxDifficulty,
    vibrate = vibrate,
    soundUri = soundUri,
    volume = volume,
    snoozeMinutes = snoozeMinutes,
    maxSnoozes = maxSnoozes,
    requirePuzzle = requirePuzzle,
    mirrorToSleepAsAndroid = mirrorToSleepAsAndroid,
)

fun Alarm.toEntity(
    nextTriggerAt: Long?,
    createdAt: Long,
    updatedAt: Long,
    id: Long = this.id,
): AlarmEntity = AlarmEntity(
    id = id,
    hour = hour,
    minute = minute,
    repeatDays = repeatDays,
    enabled = enabled,
    label = label,
    puzzleTheme = puzzleTheme?.name,
    maxDifficulty = maxDifficulty,
    vibrate = vibrate,
    soundUri = soundUri,
    volume = volume,
    snoozeMinutes = snoozeMinutes,
    maxSnoozes = maxSnoozes,
    requirePuzzle = requirePuzzle,
    mirrorToSleepAsAndroid = mirrorToSleepAsAndroid,
    nextTriggerAt = nextTriggerAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

@Dao
interface AlarmDao {

    /** All alarms, soonest-ringing first, for the alarm list. */
    @Query(
        """
        SELECT * FROM alarms
        ORDER BY enabled DESC,
                 CASE WHEN nextTriggerAt IS NULL THEN 1 ELSE 0 END,
                 nextTriggerAt ASC,
                 hour ASC, minute ASC
        """,
    )
    fun observeAll(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms WHERE id = :id")
    fun observeById(id: Long): Flow<AlarmEntity?>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun findById(id: Long): AlarmEntity?

    @Query("SELECT * FROM alarms WHERE enabled = 1")
    suspend fun findAllEnabled(): List<AlarmEntity>

    /** The alarm that will ring soonest, used for the status-bar summary. */
    @Query(
        """
        SELECT * FROM alarms
        WHERE enabled = 1 AND nextTriggerAt IS NOT NULL
        ORDER BY nextTriggerAt ASC LIMIT 1
        """,
    )
    fun observeNext(): Flow<AlarmEntity?>

    /** One-shot read of the next alarm, used to refresh the status-bar summary. */
    @Query(
        """
        SELECT * FROM alarms
        WHERE enabled = 1 AND nextTriggerAt IS NOT NULL
        ORDER BY nextTriggerAt ASC LIMIT 1
        """,
    )
    suspend fun findNext(): AlarmEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AlarmEntity): Long

    @Update
    suspend fun update(entity: AlarmEntity)

    @Delete
    suspend fun delete(entity: AlarmEntity)

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Records the booked trigger time after [com.mostafazahra.chesswake.alarm.AlarmScheduler] succeeds. */
    @Query("UPDATE alarms SET nextTriggerAt = :triggerAt WHERE id = :id")
    suspend fun updateNextTrigger(id: Long, triggerAt: Long?)

    /** One-shot alarms switch themselves off once they have rung. */
    @Query("UPDATE alarms SET enabled = 0, nextTriggerAt = NULL WHERE id = :id AND repeatDays = ''")
    suspend fun disableIfOneShot(id: Long)

    @Query("UPDATE alarms SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("SELECT COUNT(*) FROM alarms")
    suspend fun count(): Int
}
