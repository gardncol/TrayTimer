package com.traytimer.core.logic

/**
 * Re-notification cadence for unanswered reminders: every 5 minutes for the first
 * half hour, then every 15 minutes until the user responds.
 */
object ReminderCadence {
    const val FAST_PERIOD_MILLIS = 30 * 60_000L
    const val FAST_INTERVAL_MILLIS = 5 * 60_000L
    const val SLOW_INTERVAL_MILLIS = 15 * 60_000L

    fun nextDelayMillis(elapsedMillis: Long): Long =
        if (elapsedMillis < FAST_PERIOD_MILLIS) FAST_INTERVAL_MILLIS else SLOW_INTERVAL_MILLIS
}
