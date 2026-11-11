package com.traytimer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.traytimer.core.Graph
import com.traytimer.core.logic.Analytics
import com.traytimer.core.logic.DayMath
import com.traytimer.core.logic.GoalColor
import com.traytimer.core.logic.PlanEngine
import com.traytimer.core.logic.StatsCalc
import com.traytimer.core.notifications.Notifications
import com.traytimer.data.db.OutSession
import com.traytimer.data.db.PlanTray
import com.traytimer.data.db.Teethie
import com.traytimer.data.prefs.AppSettings
import com.traytimer.reminders.AlarmScheduler
import com.traytimer.widgets.WidgetRefresher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * Central state holder shared by all screens. Everything derives from the same
 * timestamped sessions + plan, so tabs can never disagree (FR-STS-06).
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val timerRepo = Graph.timerRepository
    private val treatmentRepo = Graph.treatmentRepository
    private val settingsStore = Graph.settingsStore

    val settings: StateFlow<AppSettings?> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val sessions: StateFlow<List<OutSession>> = timerRepo.observeSessions()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val trays: StateFlow<List<PlanTray>> = treatmentRepo.observeTrays()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val photos: StateFlow<List<Teethie>> = Graph.photoRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val clock = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = clock

    init {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                clock.value = System.currentTimeMillis()
            }
        }
    }

    fun zone(settings: AppSettings?): ZoneId =
        DayMath.zone(settings?.useDeviceTimeZone ?: true, settings?.chosenTimeZone ?: "")

    // ---------- derived timer state ----------

    data class TimerState(
        val inMillis: Long = 0,
        val outMillis: Long = 0,
        val openSession: OutSession? = null,
        val goalColor: GoalColor = GoalColor.RED,
        val todaySessions: List<OutSession> = emptyList(),
        val currentTray: PlanTray? = null,
        val daysLeftInTray: Int = 0,
        val daysLeftInTreatment: Int = 0,
        /** Remaining time on the running trays-out timer, when one is set. */
        val remainingMillis: Long? = null
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val timerState: StateFlow<TimerState> = combine(
        sessions, settings, trays, clock
    ) { sessions, s, trays, now ->
        val s2 = s ?: AppSettings()
        val zone = DayMath.zone(s2.useDeviceTimeZone, s2.chosenTimeZone)
        val today = LocalDate.now(zone)
        val windowStart = DayMath.dayStart(today, s2.dayResetMinutes, zone)
        val windowEnd = windowStart + DayMath.MILLIS_PER_DAY
        val inMillis = DayMath.inMillis(sessions, windowStart, s2.dayResetMinutes, now)
        val open = sessions.firstOrNull { it.endEpoch == null && it.startEpoch <= now }
        // Cumulative out time today (FR-TMR-02), including the running break.
        val outMillis = DayMath.outMillis(sessions, windowStart, windowEnd, now)
        val goalMillis = s2.wearGoalHours * 3_600_000L
        val greenMillis = s2.greenThresholdHours * 3_600_000L
        val yellowMillis = s2.yellowThresholdHours * 3_600_000L
        val todayMidnight = today.atStartOfDay(zone).toInstant().toEpochMilli()
        TimerState(
            inMillis = inMillis,
            outMillis = outMillis,
            openSession = open,
            goalColor = DayMath.goalColor(inMillis, goalMillis, greenMillis, yellowMillis),
            todaySessions = sessions.filter { it.startEpoch >= windowStart && it.startEpoch < windowEnd },
            currentTray = PlanEngine.currentTray(trays, todayMidnight),
            daysLeftInTray = PlanEngine.daysLeftInTray(trays, todayMidnight),
            daysLeftInTreatment = PlanEngine.daysLeftInTreatment(trays, todayMidnight),
            remainingMillis = if (open != null && s2.breakTargetAtEpoch > now) {
                s2.breakTargetAtEpoch - now
            } else null
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TimerState())

    // ---------- actions ----------

    /** Returns true when the session was stopped; false when it was started (wearing = false). */
    fun toggleTimer(fromBackground: Boolean = false, onStarted: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val open = timerRepo.getOpen()
            if (open == null) {
                val started = timerRepo.startOut(fromBackground)
                settingsStore.updateAwait { st, p ->
                    p[com.traytimer.data.prefs.Keys.PUT_BACK_SESSION_START] = started.startEpoch
                    p[com.traytimer.data.prefs.Keys.PUT_BACK_DECIDED] = false
                    p[com.traytimer.data.prefs.Keys.PUT_BACK_AT] = 0L
                    p[com.traytimer.data.prefs.Keys.PUT_BACK_CYCLE_START] = 0L
                    p[com.traytimer.data.prefs.Keys.BREAK_TARGET_AT] = 0L
                }
                Analytics.log("timer_started")
                onStarted(true) // now OUT
            } else {
                timerRepo.stopOut(fromBackground)
                settingsStore.updateAwait { st, p ->
                    p[com.traytimer.data.prefs.Keys.PUT_BACK_DECIDED] = false
                    p[com.traytimer.data.prefs.Keys.PUT_BACK_SESSION_START] = 0L
                    p[com.traytimer.data.prefs.Keys.PUT_BACK_AT] = 0L
                    p[com.traytimer.data.prefs.Keys.PUT_BACK_CYCLE_START] = 0L
                    p[com.traytimer.data.prefs.Keys.BREAK_TARGET_AT] = 0L
                }
                Analytics.log("timer_stopped")
                onStarted(false) // now wearing
            }
            afterTimerChange()
        }
    }

    fun startOut(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val started = timerRepo.startOut()
            settingsStore.updateAwait { st, p ->
                p[com.traytimer.data.prefs.Keys.PUT_BACK_SESSION_START] = started.startEpoch
                p[com.traytimer.data.prefs.Keys.PUT_BACK_DECIDED] = false
                p[com.traytimer.data.prefs.Keys.PUT_BACK_AT] = 0L
                p[com.traytimer.data.prefs.Keys.PUT_BACK_CYCLE_START] = 0L
                p[com.traytimer.data.prefs.Keys.BREAK_TARGET_AT] = 0L
            }
            Analytics.log("timer_started")
            onDone()
            afterTimerChange()
        }
    }

    fun stopOut(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            timerRepo.stopOut()
            settingsStore.updateAwait { st, p ->
                p[com.traytimer.data.prefs.Keys.PUT_BACK_DECIDED] = false
                p[com.traytimer.data.prefs.Keys.PUT_BACK_SESSION_START] = 0L
                p[com.traytimer.data.prefs.Keys.PUT_BACK_AT] = 0L
                p[com.traytimer.data.prefs.Keys.PUT_BACK_CYCLE_START] = 0L
                p[com.traytimer.data.prefs.Keys.BREAK_TARGET_AT] = 0L
            }
            Analytics.log("timer_stopped")
            onDone()
            afterTimerChange()
        }
    }

    fun deleteSession(session: OutSession) {
        viewModelScope.launch {
            timerRepo.deleteSession(session.id)
            afterTimerChange()
        }
    }

    fun updateSession(session: OutSession) {
        viewModelScope.launch {
            val previous = timerRepo.getSession(session.id)
            timerRepo.updateSession(session)
            if (previous != null) {
                if (previous.endEpoch == null && session.endEpoch != null) {
                    // The running break was ended from the editor: drop its pending reminder.
                    settingsStore.updateAwait { st, p ->
                        p[com.traytimer.data.prefs.Keys.PUT_BACK_DECIDED] = false
                        p[com.traytimer.data.prefs.Keys.PUT_BACK_SESSION_START] = 0L
                        p[com.traytimer.data.prefs.Keys.PUT_BACK_AT] = 0L
                        p[com.traytimer.data.prefs.Keys.PUT_BACK_CYCLE_START] = 0L
                        p[com.traytimer.data.prefs.Keys.BREAK_TARGET_AT] = 0L
                    }
                } else if (previous.endEpoch == null && session.endEpoch == null &&
                    previous.startEpoch != session.startEpoch
                ) {
                    // Out time adjusted while still running: shift the chosen reminder with it.
                    settingsStore.updateAwait { st, p ->
                        if (st.putBackDecided && st.putBackSessionStart == previous.startEpoch) {
                            val delta = session.startEpoch - previous.startEpoch
                            p[com.traytimer.data.prefs.Keys.PUT_BACK_SESSION_START] = session.startEpoch
                            if (st.putBackAtEpoch > 0) {
                                p[com.traytimer.data.prefs.Keys.PUT_BACK_AT] =
                                    (st.putBackAtEpoch + delta)
                                        .coerceAtLeast(System.currentTimeMillis() + 60_000L)
                            }
                        }
                    }
                }
            }
            afterTimerChange()
        }
    }

    private suspend fun afterTimerChange() {
        val context = getApplication<Application>()
        AlarmScheduler.rescheduleAll(context)
        WidgetRefresher.pushAll(context)
        com.traytimer.liveupdates.LiveUpdateService.refresh(context, timerRepo.getOpen() == null)
    }

    fun setGoalHours(hours: Int) {
        viewModelScope.launch {
            settingsStore.update { s, p ->
                p[com.traytimer.data.prefs.Keys.WEAR_GOAL_HOURS] = hours
                p[com.traytimer.data.prefs.Keys.GREEN_THRESHOLD] = hours
            }
        }
    }

    fun updateSettings(transform: (AppSettings, androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

    // ---------- stats ----------

    fun dayStats(days: Int): StateFlow<List<StatsCalc.DayStat>> {
        val state = combine(sessions, settings, clock) { sessions, s, now ->
            val s2 = s ?: AppSettings()
            val zone = DayMath.zone(s2.useDeviceTimeZone, s2.chosenTimeZone)
            val today = LocalDate.now(zone).toEpochDay()
            val firstTracked = sessions.minOfOrNull {
                DayMath.dateAt(it.startEpoch, zone).toEpochDay()
            }
            StatsCalc.dailyStats(
                sessions, zone, s2.dayResetMinutes,
                s2.wearGoalHours * 3_600_000L,
                s2.greenThresholdHours * 3_600_000L,
                s2.yellowThresholdHours * 3_600_000L,
                days, today, now,
                firstTrackedEpochDay = firstTracked
            )
        }
        return state.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    }

    suspend fun wearForCurrentTray(orderIndex: Int): Long {
        val s = settingsStore.current()
        val zone = DayMath.zone(s.useDeviceTimeZone, s.chosenTimeZone)
        return StatsCalc.wearForTray(
            timerRepo.getSessions(),
            treatmentRepo.getTrays(),
            orderIndex,
            s.dayResetMinutes
        )
    }

    // ---------- tray change popover ----------

    private var trayChangeInFlight = false

    /** Confirms the due tray change from the in-app popover. */
    fun changeToNextTrayNow() {
        if (trayChangeInFlight) return
        trayChangeInFlight = true
        viewModelScope.launch {
            try {
                val s = settingsStore.current()
                val todayMidnight = LocalDate.now(zone(s)).atStartOfDay(zone(s)).toInstant().toEpochMilli()
                val trays = treatmentRepo.getTrays()
                val idx = PlanEngine.currentIndex(trays, todayMidnight)
                val next = idx + 1
                if (next < PlanEngine.sorted(trays).size) {
                    treatmentRepo.logSwitch(next, System.currentTimeMillis(), s.autoAdjustEnabled)
                }
                settingsStore.updateAwait { _, p ->
                    p[com.traytimer.data.prefs.Keys.TRAY_CHANGE_DUE_AT] = 0L
                    p[com.traytimer.data.prefs.Keys.TRAY_CHANGE_SNOOZE_AT] = 0L
                    p[com.traytimer.data.prefs.Keys.TRAY_CHANGE_DUE_FOR] = -1
                }
                Notifications.cancel(getApplication(), Notifications.NOTIF_TRAY_CHANGE)
                Analytics.log("tray_changed_from_popover")
                afterTimerChange()
            } finally {
                trayChangeInFlight = false
            }
        }
    }

    /** "Later" on the tray-change popover: re-nudge after the snooze interval. */
    fun snoozeTrayChange() {
        viewModelScope.launch {
            val s = settingsStore.current()
            settingsStore.updateAwait { _, p ->
                p[com.traytimer.data.prefs.Keys.TRAY_CHANGE_DUE_AT] = 0L
                p[com.traytimer.data.prefs.Keys.TRAY_CHANGE_SNOOZE_AT] =
                    System.currentTimeMillis() + s.trayChangeSnoozeSeconds * 1000L
                p[com.traytimer.data.prefs.Keys.TRAY_CHANGE_DUE_FOR] = -1
            }
            Notifications.cancel(getApplication(), Notifications.NOTIF_TRAY_CHANGE)
            AlarmScheduler.rescheduleAll(getApplication())
            Analytics.log("tray_change_snoozed")
        }
    }

    // ---------- notifications / permission helpers ----------

    fun cancelPutBackNotification() {
        Notifications.cancel(getApplication(), Notifications.NOTIF_PUT_BACK)
    }
}