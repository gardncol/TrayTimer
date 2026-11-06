package com.traytimer.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.traytimer.core.Graph
import com.traytimer.core.logic.Analytics
import com.traytimer.core.logic.DayMath
import com.traytimer.core.logic.PlanEngine
import com.traytimer.core.logic.ReminderCadence
import com.traytimer.core.notifications.Notifications
import com.traytimer.data.repo.TreatmentRepository
import com.traytimer.liveupdates.LiveUpdateService
import com.traytimer.widgets.WidgetRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Central reminder dispatch. Alarm triggers land here; snoozes and quick actions
 * (change tray, toggle timer from the Live Update notification) also route through.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handle(context, intent)
            } finally {
                @Suppress("DEPRECATION")
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, intent: Intent) {
        val settings = Graph.settingsStore.current()
        val now = System.currentTimeMillis()
        when (intent.action) {
            AlarmScheduler.ACTION_PUT_BACK -> {
                val open = Graph.timerRepository.getOpen() ?: return
                // Re-notify on a cadence until the patient responds: 5 min for the
                // first half hour, then every 15 min.
                val cycleStart = if (settings.putBackCycleStart > 0) settings.putBackCycleStart else now
                val next = now + ReminderCadence.nextDelayMillis(now - cycleStart)
                Notifications.postPutBackAlarm(
                    context,
                    now - open.startEpoch,
                    settings.fullScreenAlarm,
                    com.traytimer.core.notifications.Channels.alarmChannelId(context, settings.alarmSoundUri)
                )
                Graph.settingsStore.updateAwait { st, p ->
                    p[com.traytimer.data.prefs.Keys.PUT_BACK_CYCLE_START] = cycleStart
                    p[com.traytimer.data.prefs.Keys.PUT_BACK_SESSION_START] = open.startEpoch
                    p[com.traytimer.data.prefs.Keys.PUT_BACK_DECIDED] = true
                    p[com.traytimer.data.prefs.Keys.PUT_BACK_AT] = next
                }
                AlarmScheduler.schedulePutBack(context, next)
                Analytics.log("reminder_put_back_fired")
            }

            AlarmScheduler.ACTION_START_WEARING -> {
                Graph.timerRepository.stopOut()
                Graph.settingsStore.updateAwait { st, p ->
                    p[com.traytimer.data.prefs.Keys.PUT_BACK_DECIDED] = false
                    p[com.traytimer.data.prefs.Keys.PUT_BACK_SESSION_START] = 0L
                    p[com.traytimer.data.prefs.Keys.PUT_BACK_AT] = 0L
                    p[com.traytimer.data.prefs.Keys.PUT_BACK_CYCLE_START] = 0L
                    p[com.traytimer.data.prefs.Keys.BREAK_TARGET_AT] = 0L
                }
                Notifications.cancel(context, Notifications.NOTIF_PUT_BACK)
                AlarmScheduler.cancelPutBack(context)
                runCatching {
                    context.stopService(
                        android.content.Intent(context, com.traytimer.alarm.AlarmRingingService::class.java)
                    )
                }
                LiveUpdateService.refresh(context, wearing = true)
                WidgetRefresher.pushAll(context)
                AlarmScheduler.rescheduleAll(context)
                Analytics.log("reminder_start_wearing")
            }

            AlarmScheduler.ACTION_ADD_5, AlarmScheduler.ACTION_ADD_10 -> {
                val minutes = if (intent.action == AlarmScheduler.ACTION_ADD_5) 5 else 10
                val open = Graph.timerRepository.getOpen()
                if (open == null) {
                    Notifications.cancel(context, Notifications.NOTIF_PUT_BACK)
                    AlarmScheduler.cancelPutBack(context)
                } else {
                    val cycleStart = if (settings.putBackCycleStart > 0) settings.putBackCycleStart else now
                    val at = now + minutes * 60_000L
                    Graph.settingsStore.updateAwait { st, p ->
                        p[com.traytimer.data.prefs.Keys.PUT_BACK_CYCLE_START] = cycleStart
                        p[com.traytimer.data.prefs.Keys.PUT_BACK_SESSION_START] = open.startEpoch
                        p[com.traytimer.data.prefs.Keys.PUT_BACK_DECIDED] = true
                        p[com.traytimer.data.prefs.Keys.PUT_BACK_AT] = at
                        p[com.traytimer.data.prefs.Keys.BREAK_TARGET_AT] = at
                    }
                    Notifications.cancel(context, Notifications.NOTIF_PUT_BACK)
                    runCatching {
                        context.stopService(
                            android.content.Intent(context, com.traytimer.alarm.AlarmRingingService::class.java)
                        )
                    }
                    AlarmScheduler.schedulePutBack(context, at)
                    Analytics.log("reminder_extended", mapOf("minutes" to minutes))
                }
            }

            AlarmScheduler.ACTION_TRAY_CHANGE -> {
                val zone = DayMath.zone(settings.useDeviceTimeZone, settings.chosenTimeZone)
                val trays = Graph.treatmentRepository.getTrays()
                val current = PlanEngine.currentTray(trays, LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli())
                val next = PlanEngine.sorted(trays).getOrNull((current?.orderIndex ?: 0) + 1)
                Notifications.postTrayChange(context, next?.label ?: "-", settings.timeSensitiveNotifications)
                // keep scheduling the next one in case the patient didn't change yet
                AlarmScheduler.rescheduleAll(context)
                Analytics.log("reminder_tray_change_fired")
            }

            AlarmScheduler.ACTION_TRAY_TOTAL -> {
                Notifications.postTrayTotal(context, settings.timeSensitiveNotifications)
                Analytics.log("reminder_tray_total_fired")
            }

            AlarmScheduler.ACTION_SELFIE -> {
                Notifications.postSelfie(context, settings.timeSensitiveNotifications)
                Analytics.log("reminder_selfie_fired")
            }

            AlarmScheduler.ACTION_APPOINTMENT -> {
                val id = intent.getLongExtra("id", -1)
                val appt = Graph.appointmentRepository.getById(id) ?: return
                val zone = ZoneId.systemDefault()
                val whenText = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(appt.startEpoch), zone)
                    .format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d 'at' HH:mm"))
                Notifications.postAppointment(
                    context, appt.title, whenText,
                    Notifications.NOTIF_APPOINTMENT_BASE + id.toInt(),
                    settings.timeSensitiveNotifications
                )
                Analytics.log("reminder_appointment_fired")
            }

            AlarmScheduler.ACTION_DST -> {
                Notifications.postDst(context)
                AlarmScheduler.rescheduleAll(context)
            }

            AlarmScheduler.ACTION_TOGGLE -> {
                val nowWearing = Graph.timerRepository.toggle()
                LiveUpdateService.refresh(context, nowWearing)
                WidgetRefresher.pushAll(context)
                AlarmScheduler.rescheduleAll(context)
                Analytics.log("timer_toggled_from_notification", mapOf("wearing" to nowWearing))
            }

            AlarmScheduler.ACTION_CHANGE_TRAY_CONFIRM -> {
                changeToNextTray(context, settings)
            }
        }
    }

    /** Logs a switch to the next tray as of today (used by the tray-change notification action and widgets). */
    suspend fun changeToNextTray(context: Context, settings: com.traytimer.data.prefs.AppSettings) {
        val zone = DayMath.zone(settings.useDeviceTimeZone, settings.chosenTimeZone)
        val today = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val trays = Graph.treatmentRepository.getTrays()
        val idx = PlanEngine.currentIndex(trays, today)
        val next = idx + 1
        if (next < PlanEngine.sorted(trays).size) {
            Graph.treatmentRepository.logSwitch(next, today, settings.autoAdjustEnabled)
            Analytics.log("tray_changed_from_notification")
        }
        WidgetRefresher.pushAll(context)
        LiveUpdateService.refresh(context, wearing = true)
        AlarmScheduler.rescheduleAll(context)
    }
}