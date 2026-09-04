package com.mostafazahra.chesswake.alarm.data

import android.util.Log
import com.mostafazahra.chesswake.alarm.AlarmNotifications
import com.mostafazahra.chesswake.alarm.AlarmScheduler
import com.mostafazahra.chesswake.alarm.domain.Alarm
import com.mostafazahra.chesswake.alarm.domain.AlarmTimes
import com.mostafazahra.chesswake.di.IoDispatcher
import com.mostafazahra.chesswake.sleepasandroid.SleepAsAndroidBridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns alarms: the database rows *and* the matching [android.app.AlarmManager]
 * bookings.
 *
 * Every mutation goes through here so that the two can never disagree — the
 * classic alarm-app bug is a row that says "enabled, 07:00" while nothing is
 * actually booked with the system.
 */
@Singleton
class AlarmRepository @Inject constructor(
    private val alarmDao: AlarmDao,
    private val scheduler: AlarmScheduler,
    private val notifications: AlarmNotifications,
    private val sleepAsAndroidBridge: SleepAsAndroidBridge,
    private val clock: Clock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** All alarms, soonest-ringing first. */
    val alarms: Flow<List<Alarm>> = alarmDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** The alarm that will ring next, or null when none is enabled. */
    val nextAlarm: Flow<Alarm?> = alarmDao.observeNext().map { it?.toDomain() }

    suspend fun byId(id: Long): Alarm? = withContext(ioDispatcher) {
        alarmDao.findById(id)?.toDomain()
    }

    suspend fun allEnabled(): List<Alarm> = withContext(ioDispatcher) {
        alarmDao.findAllEnabled().map { it.toDomain() }
    }

    /**
     * Inserts or updates [alarm] and re-books it with the system.
     *
     * @return the row id, which is newly generated for an insert.
     */
    suspend fun save(alarm: Alarm): Long = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        val existing = if (alarm.id == 0L) null else alarmDao.findById(alarm.id)

        val triggerAt = if (alarm.enabled) AlarmTimes.nextTriggerMillis(alarm, now()) else null
        val entity = alarm.toEntity(
            nextTriggerAt = triggerAt,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            id = existing?.id ?: 0L,
        )

        val id = if (existing == null) alarmDao.insert(entity) else {
            alarmDao.update(entity)
            entity.id
        }

        val saved = alarm.copy(id = id)
        if (saved.enabled && triggerAt != null) {
            scheduler.schedule(saved, triggerAt)
        } else {
            scheduler.cancel(id)
        }
        refreshUpcomingNotification()
        sleepAsAndroidBridge.mirrorAlarm(saved)
        id
    }

    /** Deletes the alarm and cancels any booking. */
    suspend fun delete(id: Long) = withContext(ioDispatcher) {
        scheduler.cancel(id)
        alarmDao.deleteById(id)
        notifications.cancelRinging(id)
        refreshUpcomingNotification()
    }

    /** Toggles an alarm on or off, booking or cancelling as appropriate. */
    suspend fun setEnabled(id: Long, enabled: Boolean) = withContext(ioDispatcher) {
        val alarm = alarmDao.findById(id)?.toDomain() ?: return@withContext
        save(alarm.copy(enabled = enabled))
    }

    /**
     * Books the next occurrence of the alarm that just rang.
     *
     * A one-shot alarm switches itself off, which is what makes "ring once
     * tomorrow" behave like every other clock app.
     */
    suspend fun onAlarmFired(alarmId: Long) = withContext(ioDispatcher) {
        val entity = alarmDao.findById(alarmId) ?: return@withContext
        val alarm = entity.toDomain()

        if (!alarm.isRepeating) {
            alarmDao.setEnabled(alarmId, false)
            alarmDao.updateNextTrigger(alarmId, null)
            scheduler.cancel(alarmId)
        } else {
            val next = AlarmTimes.nextTriggerMillis(alarm, now())
            alarmDao.updateNextTrigger(alarmId, next)
            scheduler.schedule(alarm, next)
        }
        refreshUpcomingNotification()
    }

    /**
     * Snoozes the alarm that is currently ringing.
     *
     * The snooze is booked as a separate `AlarmManager` entry so it cannot
     * overwrite the alarm's regular next occurrence.
     */
    suspend fun snooze(alarmId: Long, minutes: Int): Boolean = withContext(ioDispatcher) {
        val alarm = alarmDao.findById(alarmId)?.toDomain() ?: return@withContext false
        val triggerAt = AlarmTimes.snoozeTrigger(now(), minutes).toInstant().toEpochMilli()
        scheduler.scheduleSnooze(alarm, triggerAt)
        Log.i(TAG, "Alarm $alarmId snoozed until $triggerAt")
        true
    }

    /**
     * Re-books every enabled alarm from scratch.
     *
     * Called after a reboot, a clock or timezone change, and when the exact-alarm
     * permission is granted — all of which invalidate what `AlarmManager` holds.
     *
     * @return how many alarms were booked.
     */
    suspend fun rescheduleAll(): Int = withContext(ioDispatcher) {
        notifications.createChannels()
        val enabled = alarmDao.findAllEnabled()
        val reference = now()
        enabled.forEach { entity ->
            val alarm = entity.toDomain()
            val triggerAt = AlarmTimes.nextTriggerMillis(alarm, reference)
            alarmDao.updateNextTrigger(alarm.id, triggerAt)
            scheduler.schedule(alarm, triggerAt)
        }
        refreshUpcomingNotification()
        enabled.size
    }

    /** Cancels every booking. Used by the "reset app" action in settings. */
    suspend fun cancelAll() = withContext(ioDispatcher) {
        alarmDao.findAllEnabled().forEach { scheduler.cancel(it.id) }
        notifications.cancelUpcoming()
    }

    /** True when the OS will honour an exact alarm; the UI warns when it will not. */
    fun canScheduleExactAlarms(): Boolean = scheduler.canScheduleExactAlarms()

    fun openExactAlarmSettings() = scheduler.openExactAlarmSettings()

    /** The trigger time currently booked with the system, for the reliability card. */
    fun systemBookedTriggerMillis(): Long? = scheduler.nextBookedTriggerMillis()

    private suspend fun refreshUpcomingNotification() {
        val next = alarmDao.findNext()
        if (next == null) {
            notifications.cancelUpcoming()
        } else {
            val triggerAt = next.nextTriggerAt
            if (triggerAt != null) {
                notifications.showUpcoming(next.toDomain(), triggerAt)
            } else {
                notifications.cancelUpcoming()
            }
        }
    }

    private fun now(): ZonedDateTime = ZonedDateTime.now(clock)

    private companion object {
        const val TAG = "AlarmRepository"
    }
}
