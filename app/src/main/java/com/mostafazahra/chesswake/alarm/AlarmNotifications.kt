package com.mostafazahra.chesswake.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mostafazahra.chesswake.MainActivity
import com.mostafazahra.chesswake.R
import com.mostafazahra.chesswake.alarm.domain.Alarm
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification channels and the two notifications ChessWake shows.
 *
 * Two separate notifications matter:
 *
 *  1. **Ringing** — a high-importance notification carrying a *full-screen intent*.
 *     On Android 10 and later an app cannot start an activity from the background,
 *     so a full-screen intent is the only supported way to get the puzzle onto a
 *     locked screen. `AlarmReceiver` also attempts a direct `startActivity`, which
 *     still works on many devices; the notification is the path that always does.
 *  2. **Upcoming** — a low-importance, ongoing summary of the next alarm, so the
 *     user can see at a glance that something is booked.
 *
 * The ringing channel deliberately has no sound of its own: [AlarmSoundService]
 * plays the tone on a loop with `USAGE_ALARM`, and a second sound from the
 * channel would overlap it.
 */
@Singleton
class AlarmNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val manager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Creates every channel. Idempotent, safe to call on each app start. */
    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channelGroup =
            NotificationChannelGroup(GROUP_ID, context.getString(R.string.notification_group_alarms))
        manager.createNotificationChannelGroup(channelGroup)

        val ringing = NotificationChannel(
            CHANNEL_RINGING,
            context.getString(R.string.channel_ringing_name),
            NotificationManager.IMPORTANCE_HIGH,
            GROUP_ID,
        ).apply {
            description = context.getString(R.string.channel_ringing_description)
            // The service owns the audio; a channel sound would double up.
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val service = NotificationChannel(
            CHANNEL_SERVICE,
            context.getString(R.string.channel_service_name),
            NotificationManager.IMPORTANCE_HIGH,
            GROUP_ID,
        ).apply {
            description = context.getString(R.string.channel_service_description)
            setSound(null, null)
            enableVibration(false)
        }

        val upcoming = NotificationChannel(
            CHANNEL_UPCOMING,
            context.getString(R.string.channel_upcoming_name),
            NotificationManager.IMPORTANCE_LOW,
            GROUP_ID,
        ).apply {
            description = context.getString(R.string.channel_upcoming_description)
            setShowBadge(false)
        }

        manager.createNotificationChannels(listOf(ringing, service, upcoming))
    }

    /**
     * Posts the full-screen-intent notification that shows the puzzle over the lock screen.
     *
     * @param snoozeAvailable false once the user has used up their snoozes, so the
     *   action is removed instead of being a button that silently does nothing.
     */
    fun showRinging(
        alarmId: Long,
        label: String,
        timeLabel: String,
        requirePuzzle: Boolean,
        snoozeAvailable: Boolean,
        snoozeMinutes: Int,
    ) {
        val fullScreenIntent = Intent(context, AlarmActivity::class.java).apply {
            action = AlarmContract.ACTION_ALARM
            putExtra(AlarmContract.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmContract.EXTRA_LABEL, label)
            putExtra(AlarmContract.EXTRA_TIME_LABEL, timeLabel)
            putExtra(AlarmContract.EXTRA_REQUIRE_PUZZLE, requirePuzzle)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            // Per-alarm request code: two alarms ringing close together must not
            // share (and therefore overwrite) one full-screen PendingIntent.
            REQUEST_FULL_SCREEN + alarmId.toInt(),
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(label.ifBlank { context.getString(R.string.alarm_default_label) })
            .setContentText(
                if (requirePuzzle) {
                    context.getString(R.string.notification_ringing_body)
                } else {
                    context.getString(R.string.notification_ringing_body_no_puzzle)
                },
            )
            .setSubText(timeLabel)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(RINGING_NOTIFICATION_TIMEOUT_MILLIS)
            .addAction(0, context.getString(R.string.action_open), fullScreenPendingIntent)

        if (snoozeAvailable) {
            val snoozeIntent = Intent(context, AlarmReceiver::class.java).apply {
                action = AlarmContract.ACTION_SNOOZE_NOW
                putExtra(AlarmContract.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmContract.EXTRA_SNOOZE_MINUTES, snoozeMinutes)
            }
            builder.addAction(
                R.drawable.ic_snooze,
                context.getString(R.string.action_snooze_minutes, snoozeMinutes),
                PendingIntent.getBroadcast(
                    context,
                    REQUEST_SNOOZE_ACTION + alarmId.toInt(),
                    snoozeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }

        notifySafely(ringingNotificationId(alarmId), builder.build())
    }

    /** Removes the ringing notification for one alarm. */
    fun cancelRinging(alarmId: Long) {
        NotificationManagerCompat.from(context).cancel(ringingNotificationId(alarmId))
    }

    /** Removes every ringing notification, e.g. after all alarms are dismissed. */
    fun cancelAllRinging() {
        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.activeNotifications
            .filter { it.notification.channelId == CHANNEL_RINGING }
            .forEach { notificationManager.cancel(it.id) }
    }

    /**
     * The foreground-service notification shown while the alarm sound is playing.
     *
     * Separate from the ringing notification so that cancelling one (when the
     * puzzle is solved) cannot race the other.
     */
    fun buildServiceNotification(label: String, timeLabel: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(label.ifBlank { context.getString(R.string.alarm_default_label) })
            .setContentText(context.getString(R.string.notification_service_body))
            .setSubText(timeLabel)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    /** Shows or refreshes the "next alarm at 07:00" status-bar summary. */
    fun showUpcoming(alarm: Alarm, triggerAtMillis: Long) {
        val triggerText = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(triggerAtMillis))

        val openIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_UPCOMING)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(
                alarm.label.ifBlank { context.getString(R.string.alarm_default_label) } +
                    " · " + alarm.timeLabel,
            )
            .setContentText(context.getString(R.string.notification_upcoming_body, triggerText))
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()

        notifySafely(NOTIFICATION_UPCOMING, notification)
    }

    fun cancelUpcoming() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_UPCOMING)
    }

    /**
     * Posts a notification, swallowing the `SecurityException` Android 13+ throws
     * when `POST_NOTIFICATIONS` has not been granted.
     *
     * The alarm must still ring in that case — the sound service and the
     * full-screen activity do not depend on notification permission being granted,
     * so a denied permission must never stop the alarm.
     */
    private fun notifySafely(id: Int, notification: Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun ringingNotificationId(alarmId: Long): Int =
        NOTIFICATION_RINGING_BASE + alarmId.toInt()

    companion object {
        const val GROUP_ID = "chesswake_alarms"
        const val CHANNEL_RINGING = "chesswake_ringing"
        const val CHANNEL_SERVICE = "chesswake_service"
        const val CHANNEL_UPCOMING = "chesswake_upcoming"

        const val NOTIFICATION_UPCOMING = 1
        const val NOTIFICATION_RINGING_BASE = 1_000
        const val SERVICE_NOTIFICATION_ID = 2

        /**
         * How long the ringing notification may stay up before the system removes it.
         *
         * Long enough that a heavy sleeper can find it, short enough that a missed
         * alarm does not sit in the shade forever.
         */
        const val RINGING_NOTIFICATION_TIMEOUT_MILLIS = 30 * 60 * 1000L

        private const val REQUEST_FULL_SCREEN = 100
        private const val REQUEST_SNOOZE_ACTION = 200
        private const val REQUEST_OPEN_APP = 300
    }
}
