package com.traytimer.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.traytimer.core.Graph
import com.traytimer.core.logic.Analytics
import com.traytimer.core.logic.DayMath
import com.traytimer.core.logic.PlanEngine
import com.traytimer.core.logic.StatsCalc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Exact-alarm scheduling with graceful degradation (FR-REM-09). All triggers are
 * derived from persisted facts (open session, plan, appointments, last selfie), so
 * everything can be rebuilt after boot / force-stop / app update.
 */
object AlarmScheduler {

    const val ACTION_PUT_BACK = "com.traytimer.action.PUT_BACK"
    const val ACTION_START_WEARING = "com.traytimer.action.START_WEARING"
    const val ACTION_ADD_5 = "com.traytimer.action.ADD_5"
    const val ACTION_ADD_10 = "com.traytimer.action.ADD_10"
    const val ACTION_TRAY_CHANGE = "com.traytimer.action.TRAY_CHANGE"
    const val ACTION_TRAY_TOTAL = "com.traytimer.action.TRAY_TOTAL"
    const val ACTION_SELFIE = "com.traytimer.action.SELFIE"
    const val ACTION_APPOINTMENT = "com.traytimer.action.APPOINTMENT"
    const val ACTION_TOGGLE = "com.traytimer.action.TOGGLE_TIMER"
    const val ACTION_CHANGE_TRAY_CONFIRM = "com.traytimer.action.CHANGE_TRAY_CONFIRM"
    const val ACTION_DST = "com.traytimer.action.DST"

    private const val RC_PUT_BACK = 1001
    private const val RC_TRAY_CHANGE = 1002
    private const val RC_TRAY_TOTAL = 1003
    private const val RC_SELFIE = 1004
    private const val RC_DST = 1005
    private const val RC_APPOINTMENT_BASE = 5000

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || alarmManager(context).canScheduleExactAlarms()

    private fun pending(context: Context, action: String, rc: Int, extraName: String? = null, extraValue: Long = 0): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(action)
        if (extraName != null) intent.putExtra(extraName, extraValue)
        return PendingIntent.getBroadcast(
            context, rc, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Exact when allowed; graceful inexact window fallback otherwise. */
    private fun set(context: Context, triggerAt: Long, pi: PendingIntent) {
        val am = alarmManager(context)
        if (triggerAt <= System.currentTimeMillis()) return
        if (canScheduleExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 10 * 60_000L, pi)
        }
    }

    suspend fun schedulePutBack(context: Context, triggerAt: Long) = withContext(Dispatchers.IO) {
        set(context, triggerAt, pending(context, ACTION_PUT_BACK, RC_PUT_BACK))
        Analytics.log("alarm_scheduled", mapOf("type" to "put_back"))
    }

    fun cancelPutBack(context: Context) {
        alarmManager(context).cancel(pending(context, ACTION_PUT_BACK, RC_PUT_BACK))
    }

    suspend fun scheduleTrayChange(context: Context, triggerAt: Long) = withContext(Dispatchers.IO) {
        set(context, triggerAt, pending(context, ACTION_TRAY_CHANGE, RC_TRAY_CHANGE))
        Analytics.log("alarm_scheduled", mapOf("type" to "tray_change"))
    }

    fun cancelTrayChange(context: Context) {
        alarmManager(context).cancel(pending(context, ACTION_TRAY_CHANGE, RC_TRAY_CHANGE))
    }

    suspend fun scheduleTrayTotal(context: Context, triggerAt: Long) = withContext(Dispatchers.IO) {
        set(context, triggerAt, pending(context, ACTION_TRAY_TOTAL, RC_TRAY_TOTAL))
    }

    fun cancelTrayTotal(context: Context) {
        alarmManager(context).cancel(pending(context, ACTION_TRAY_TOTAL, RC_TRAY_TOTAL))
    }

    suspend fun scheduleSelfie(context: Context, triggerAt: Long) = withContext(Dispatchers.IO) {
        set(context, triggerAt, pending(context, ACTION_SELFIE, RC_SELFIE))
    }

    fun cancelSelfie(context: Context) {
        alarmManager(context).cancel(pending(context, ACTION_SELFIE, RC_SELFIE))
    }

    suspend fun scheduleAppointment(context: Context, appointmentId: Long, triggerAt: Long) = withContext(Dispatchers.IO) {
        set(context, triggerAt, pending(context, ACTION_APPOINTMENT, RC_APPOINTMENT_BASE + appointmentId.toInt(), "id", appointmentId))
    }

    fun cancelAppointment(context: Context, appointmentId: Long) {
        alarmManager(context).cancel(pending(context, ACTION_APPOINTMENT, RC_APPOINTMENT_BASE + appointmentId.toInt(), "id", appointmentId))
    }

    suspend fun scheduleDst(context: Context, triggerAt: Long) = withContext(Dispatchers.IO) {
        set(context, triggerAt, pending(context, ACTION_DST, RC_DST))
    }

    fun cancelDst(context: Context) {
        alarmManager(context).cancel(pending(context, ACTION_DST, RC_DST))
    }

    /**
     * Rebuild every alarm from persisted state — called at boot, app update,
     * timezone change and after each timer/plan mutation (FR-REM-10/11).
     */
    suspend fun rescheduleAll(context: Context) = withContext(Dispatchers.IO) {
        Analytics.log("reschedule_all")
        val s = Graph.settingsStore.current()
        val zone = DayMath.zone(s.useDeviceTimeZone, s.chosenTimeZone)
        val now = System.currentTimeMillis()

        // --- Put-back alarm (FR-REM-01): honour the user's explicit choice for this
        // out-session ("No Reminder" included); widget/tile/voice starts use the default. ---
        cancelPutBack(context)
        val open = Graph.timerRepository.getOpen()
        if (open != null) {
            val trigger = if (s.putBackDecided && open.startEpoch == s.putBackSessionStart) {
                s.putBackAtEpoch // 0 = explicitly no reminder
            } else {
                open.startEpoch + s.defaultTrayOffReminderMinutes * 60_000L
            }
            if (trigger > now) schedulePutBack(context, trigger)
        }

        // --- Tray-change alarm: planned change day at the reminder time of day ---
        cancelTrayChange(context)
        val trays = Graph.treatmentRepository.getTrays()
        val todayMidnight = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val current = PlanEngine.currentTray(trays, todayMidnight)
        if (current != null && s.trayChangeReminderEnabled) {
            // The next tray's start IS the change moment (e.g. 08:00 on the switch
            // day), so use that calendar day rather than the day before.
            val currentIdx = PlanEngine.currentIndex(trays, todayMidnight)
            val changeInstant = PlanEngine.effectiveEndInPlan(trays, currentIdx)
            val changeDay: LocalDate = Instant.ofEpochMilli(changeInstant).atZone(zone).toLocalDate()
            val t = LocalTime.of(s.trayChangeReminderMinutesOfDay / 60, s.trayChangeReminderMinutesOfDay % 60)
            val plannedTrigger = changeDay.atTime(t).atZone(zone).toInstant().toEpochMilli()
            val trigger = when {
                s.trayChangeSnoozeAt > now -> s.trayChangeSnoozeAt
                plannedTrigger > now -> plannedTrigger
                else -> {
                    // Overdue (e.g. the app was off): nudge at the next occurrence.
                    var day = LocalDate.now(zone)
                    var at = day.atTime(t).atZone(zone).toInstant().toEpochMilli()
                    if (at <= now) {
                        day = day.plusDays(1)
                        at = day.atTime(t).atZone(zone).toInstant().toEpochMilli()
                    }
                    at
                }
            }
            if (trigger > now) scheduleTrayChange(context, trigger)

            // Once the patient actually moves to another tray, drop the pending
            // popover (it belongs to the tray they were being reminded about).
            if (s.trayChangeDueAt > 0 && s.trayChangeDueFor >= 0 &&
                current.orderIndex != s.trayChangeDueFor
            ) {
                Graph.settingsStore.updateAwait { _, p ->
                    p[com.traytimer.data.prefs.Keys.TRAY_CHANGE_DUE_AT] = 0L
                    p[com.traytimer.data.prefs.Keys.TRAY_CHANGE_SNOOZE_AT] = 0L
                    p[com.traytimer.data.prefs.Keys.TRAY_CHANGE_DUE_FOR] = -1
                }
            }
        }

        // --- Tray-total alarm: cumulative wear vs plan hours (FR-REM-03) ---
        cancelTrayTotal(context)
        if (s.trayTotalReminderEnabled && current != null) {
            val sessions = Graph.timerRepository.getSessions()
            val orderIdx = current.orderIndex
            val worn = StatsCalc.wearForTray(sessions, trays, orderIdx, s.dayResetMinutes, now)
            val goalPerDay = s.wearGoalHours * 3_600_000L
            val target = current.plannedDays * goalPerDay
            if (target > worn) {
                val outNow = open != null
                val remaining = target - worn
                val trigger = now + remaining + (if (outNow) (now - open!!.startEpoch) else 0L)
                scheduleTrayTotal(context, trigger)
            }
        }

        // --- Selfie alarm (weekly cadence, FR-REM-04) ---
        cancelSelfie(context)
        if (s.selfieReminderEnabled) {
            val last = Graph.photoRepository.latestTakenAt()
            val base = last ?: now
            val trigger = base + s.selfieReminderDays * DayMath.MILLIS_PER_DAY
            if (trigger > now) scheduleSelfie(context, trigger)
        }

        // --- Appointment alarms (FR-REM-05) ---
        for (a in Graph.appointmentRepository.upcoming(now)) {
            val lead = earliestLeadMillis(a)
            if (lead != null) {
                val trigger = a.startEpoch - lead
                if (trigger > now) scheduleAppointment(context, a.id, trigger)
            }
        }

        // --- DST awareness (FR-REM-16): next zone transition within 14 days ---
        cancelDst(context)
        runCatching {
            val rules = zone.rules
            val probe = Instant.ofEpochMilli(now)
            val next = rules.nextTransition(probe)
            if (next != null) {
                val at = next.instant.toEpochMilli()
                if (at - now < 14L * DayMath.MILLIS_PER_DAY) scheduleDst(context, at)
            }
        }
        Unit
    }

    private fun earliestLeadMillis(a: com.traytimer.data.db.Appointment): Long? {
        val leads = mutableListOf(60L * 60_000L) // default 1 hour
        if (a.remindDayBefore) leads.add(24L * 3_600_000L)
        a.remindHoursBefore?.let { leads.add(it * 3_600_000L) }
        a.remindMinutesBefore?.let { leads.add(it * 60_000L) }
        return leads.maxOrNull()
    }

    /** Convenience used after snoozes. */
    fun snoozeSeconds(kind: String, s: com.traytimer.data.prefs.AppSettings): Int = when (kind) {
        ACTION_TRAY_CHANGE -> s.trayChangeSnoozeSeconds
        ACTION_SELFIE -> s.selfieSnoozeSeconds
        else -> s.trayOffSnoozeSeconds
    }
}