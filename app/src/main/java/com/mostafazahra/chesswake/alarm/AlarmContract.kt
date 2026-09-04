package com.mostafazahra.chesswake.alarm

/** Intent extras and actions shared by the scheduler, receiver, service and alarm screen. */
object AlarmContract {

    /** Fired by [android.app.AlarmManager] when an alarm is due. */
    const val ACTION_ALARM = "com.mostafazahra.chesswake.action.ALARM"

    /** Fired by the notification's snooze action. */
    const val ACTION_SNOOZE_NOW = "com.mostafazahra.chesswake.action.SNOOZE_NOW"

    const val EXTRA_ALARM_ID = "extra_alarm_id"
    const val EXTRA_IS_SNOOZE = "extra_is_snooze"
    const val EXTRA_LABEL = "extra_label"
    const val EXTRA_TIME_LABEL = "extra_time_label"
    const val EXTRA_VIBRATE = "extra_vibrate"
    const val EXTRA_SOUND_URI = "extra_sound_uri"
    const val EXTRA_VOLUME = "extra_volume"
    const val EXTRA_REQUIRE_PUZZLE = "extra_require_puzzle"
    const val EXTRA_SNOOZE_MINUTES = "extra_snooze_minutes"
    const val EXTRA_MAX_SNOOZES = "extra_max_snoozes"

    /** Fallbacks used when an extra is missing, e.g. a broadcast from an older booking. */
    const val FALLBACK_SNOOZE_MINUTES = 5
    const val FALLBACK_MAX_SNOOZES = 3
}
