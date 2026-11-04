package com.traytimer.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "traytimer_settings")

data class AppSettings(
    // General / timer
    val onboardingComplete: Boolean = false,
    val wearGoalHours: Int = 22,
    val greenThresholdHours: Int = 22,
    val yellowThresholdHours: Int = 19,
    val dayResetMinutes: Int = 0,
    val startOfWeekSunday: Boolean = true,
    val timerShowSeconds: Boolean = false,
    val accidentalTapDetection: Boolean = true,
    val keepScreenOn: Boolean = false,
    val vibrate: Boolean = true,
    val showTimeLeft: Boolean = false,
    // Reminders
    val defaultTrayOffReminderMinutes: Int = 30,
    val trayChangeReminderMinutesOfDay: Int = 9 * 60,
    val trayChangeReminderEnabled: Boolean = true,
    val trayTotalReminderEnabled: Boolean = false,
    val selfieReminderDays: Int = 7,
    val selfieReminderEnabled: Boolean = false,
    val timeSensitiveNotifications: Boolean = true,
    val notificationSound: Boolean = true,
    val trayOffSnoozeSeconds: Int = 600,
    val trayChangeSnoozeSeconds: Int = 600,
    val selfieSnoozeSeconds: Int = 600,
    // Surfaces
    val liveUpdatesEnabled: Boolean = false,
    val autoAdjustEnabled: Boolean = false,
    // Photos
    val mirroredSelfie: Boolean = false,
    val saveToLibrary: Boolean = false,
    // Appearance / locale
    val themeMode: String = "SYSTEM", // SYSTEM / LIGHT / DARK
    // Time
    val useDeviceTimeZone: Boolean = true,
    val chosenTimeZone: String = "",
    // Calendar sync
    val calendarSyncEnabled: Boolean = false,
    val outputCalendarId: Long = -1L,
    val calendarName: String = "TrayTimer",
    val calendarAlerts: Boolean = true,
    val trayEventTitle: String = "Switch to aligner %s",
    // Brands / plan meta
    val brandName: String = "Invisalign",
    // Update / misc
    val lastVersionCheckEpoch: Long = 0L,
    // Timezone-change prompt (FR-TMR-16): zone that changed while we weren't running
    val pendingZone: String = "",
    val lastPromptedZone: String = "",
    // Onboarding choices kept for plan re-edit
    val totalAligners: Int = 0,
    val daysPerAligner: Int = 7,
    val onbWearGoalAffirmed: Boolean = false,
    val notifPrimingDone: Boolean = false,
    // Put-back reminder decision for the current out-session (survives reboot)
    val putBackSessionStart: Long = 0L,
    val putBackAtEpoch: Long = 0L,
    val putBackDecided: Boolean = false,
    val putBackCycleStart: Long = 0L,
    /** Target end of the current trays-out timer (for the on-screen countdown). */
    val breakTargetAtEpoch: Long = 0L
)

object Keys {
    val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    val WEAR_GOAL_HOURS = intPreferencesKey("wear_goal_hours")
    val GREEN_THRESHOLD = intPreferencesKey("green_threshold_hours")
    val YELLOW_THRESHOLD = intPreferencesKey("yellow_threshold_hours")
    val DAY_RESET_MINUTES = intPreferencesKey("day_reset_minutes")
    val START_WEEK_SUNDAY = booleanPreferencesKey("start_week_sunday")
    val TIMER_SHOW_SECONDS = booleanPreferencesKey("timer_show_seconds")
    val ACCIDENTAL_TAP = booleanPreferencesKey("accidental_tap")
    val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    val VIBRATE = booleanPreferencesKey("vibrate")
    val SHOW_TIME_LEFT = booleanPreferencesKey("show_time_left")
    val TRAY_OFF_REMINDER_MIN = intPreferencesKey("tray_off_reminder_min")
    val TRAY_CHANGE_TIME = intPreferencesKey("tray_change_time")
    val TRAY_CHANGE_ENABLED = booleanPreferencesKey("tray_change_enabled")
    val TRAY_TOTAL_ENABLED = booleanPreferencesKey("tray_total_enabled")
    val SELFIE_DAYS = intPreferencesKey("selfie_days")
    val SELFIE_ENABLED = booleanPreferencesKey("selfie_enabled")
    val TIME_SENSITIVE = booleanPreferencesKey("time_sensitive")
    val NOTIF_SOUND = booleanPreferencesKey("notif_sound")
    val TRAY_OFF_SNOOZE = intPreferencesKey("tray_off_snooze_seconds")
    val TRAY_CHANGE_SNOOZE = intPreferencesKey("tray_change_snooze_seconds")
    val SELFIE_SNOOZE = intPreferencesKey("selfie_snooze_seconds")
    val LIVE_UPDATES = booleanPreferencesKey("live_updates_enabled")
    val AUTO_ADJUST = booleanPreferencesKey("auto_adjust_enabled")
    val MIRRORED_SELFIE = booleanPreferencesKey("mirrored_selfie")
    val SAVE_TO_LIBRARY = booleanPreferencesKey("save_to_library")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val USE_DEVICE_TZ = booleanPreferencesKey("use_device_tz")
    val CHOSEN_TZ = stringPreferencesKey("chosen_tz")
    val CAL_SYNC_ENABLED = booleanPreferencesKey("cal_sync_enabled")
    val OUTPUT_CAL_ID = longPreferencesKey("output_cal_id")
    val CALENDAR_NAME = stringPreferencesKey("calendar_name")
    val CAL_ALERTS = booleanPreferencesKey("cal_alerts")
    val TRAY_EVENT_TITLE = stringPreferencesKey("tray_event_title")
    val BRAND_NAME = stringPreferencesKey("brand_name")
    val PENDING_ZONE = stringPreferencesKey("pending_zone")
    val LAST_PROMPTED_ZONE = stringPreferencesKey("last_prompted_zone")
    val TOTAL_ALIGNERS = intPreferencesKey("total_aligners")
    val DAYS_PER = intPreferencesKey("days_per_aligner")
    val ONB_GOAL = booleanPreferencesKey("onb_goal")
    val NOTIF_PRIMING_DONE = booleanPreferencesKey("notif_priming_done")
    val PUT_BACK_SESSION_START = longPreferencesKey("put_back_session_start")
    val PUT_BACK_AT = longPreferencesKey("put_back_at")
    val PUT_BACK_DECIDED = booleanPreferencesKey("put_back_decided")
    val PUT_BACK_CYCLE_START = longPreferencesKey("put_back_cycle_start")
    val BREAK_TARGET_AT = longPreferencesKey("break_target_at")
}

class SettingsStore(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p -> p.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    /** Fire-and-forget write, safe to call from UI callbacks. */
    fun update(transform: (AppSettings, androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        scope.launch {
            context.settingsDataStore.edit { p ->
                val s = p.toSettings()
                transform(s, p)
            }
        }
    }

    /** Awaited write for flows that must read back the result. */
    suspend fun updateAwait(transform: (AppSettings, androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit { p ->
            val s = p.toSettings()
            transform(s, p)
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            onboardingComplete = get(Keys.ONBOARDING_COMPLETE) ?: d.onboardingComplete,
            wearGoalHours = get(Keys.WEAR_GOAL_HOURS) ?: d.wearGoalHours,
            greenThresholdHours = get(Keys.GREEN_THRESHOLD) ?: d.greenThresholdHours,
            yellowThresholdHours = get(Keys.YELLOW_THRESHOLD) ?: d.yellowThresholdHours,
            dayResetMinutes = get(Keys.DAY_RESET_MINUTES) ?: d.dayResetMinutes,
            startOfWeekSunday = get(Keys.START_WEEK_SUNDAY) ?: d.startOfWeekSunday,
            timerShowSeconds = get(Keys.TIMER_SHOW_SECONDS) ?: d.timerShowSeconds,
            accidentalTapDetection = get(Keys.ACCIDENTAL_TAP) ?: d.accidentalTapDetection,
            keepScreenOn = get(Keys.KEEP_SCREEN_ON) ?: d.keepScreenOn,
            vibrate = get(Keys.VIBRATE) ?: d.vibrate,
            showTimeLeft = get(Keys.SHOW_TIME_LEFT) ?: d.showTimeLeft,
            defaultTrayOffReminderMinutes = get(Keys.TRAY_OFF_REMINDER_MIN) ?: d.defaultTrayOffReminderMinutes,
            trayChangeReminderMinutesOfDay = get(Keys.TRAY_CHANGE_TIME) ?: d.trayChangeReminderMinutesOfDay,
            trayChangeReminderEnabled = get(Keys.TRAY_CHANGE_ENABLED) ?: d.trayChangeReminderEnabled,
            trayTotalReminderEnabled = get(Keys.TRAY_TOTAL_ENABLED) ?: d.trayTotalReminderEnabled,
            selfieReminderDays = get(Keys.SELFIE_DAYS) ?: d.selfieReminderDays,
            selfieReminderEnabled = get(Keys.SELFIE_ENABLED) ?: d.selfieReminderEnabled,
            timeSensitiveNotifications = get(Keys.TIME_SENSITIVE) ?: d.timeSensitiveNotifications,
            notificationSound = get(Keys.NOTIF_SOUND) ?: d.notificationSound,
            trayOffSnoozeSeconds = get(Keys.TRAY_OFF_SNOOZE) ?: d.trayOffSnoozeSeconds,
            trayChangeSnoozeSeconds = get(Keys.TRAY_CHANGE_SNOOZE) ?: d.trayChangeSnoozeSeconds,
            selfieSnoozeSeconds = get(Keys.SELFIE_SNOOZE) ?: d.selfieSnoozeSeconds,
            liveUpdatesEnabled = get(Keys.LIVE_UPDATES) ?: d.liveUpdatesEnabled,
            autoAdjustEnabled = get(Keys.AUTO_ADJUST) ?: d.autoAdjustEnabled,
            mirroredSelfie = get(Keys.MIRRORED_SELFIE) ?: d.mirroredSelfie,
            saveToLibrary = get(Keys.SAVE_TO_LIBRARY) ?: d.saveToLibrary,
            themeMode = get(Keys.THEME_MODE) ?: d.themeMode,
            useDeviceTimeZone = get(Keys.USE_DEVICE_TZ) ?: d.useDeviceTimeZone,
            chosenTimeZone = get(Keys.CHOSEN_TZ) ?: d.chosenTimeZone,
            calendarSyncEnabled = get(Keys.CAL_SYNC_ENABLED) ?: d.calendarSyncEnabled,
            outputCalendarId = get(Keys.OUTPUT_CAL_ID) ?: d.outputCalendarId,
            calendarName = get(Keys.CALENDAR_NAME) ?: d.calendarName,
            calendarAlerts = get(Keys.CAL_ALERTS) ?: d.calendarAlerts,
            trayEventTitle = get(Keys.TRAY_EVENT_TITLE) ?: d.trayEventTitle,
            brandName = get(Keys.BRAND_NAME) ?: d.brandName,
            pendingZone = get(Keys.PENDING_ZONE) ?: d.pendingZone,
            lastPromptedZone = get(Keys.LAST_PROMPTED_ZONE) ?: d.lastPromptedZone,
            totalAligners = get(Keys.TOTAL_ALIGNERS) ?: d.totalAligners,
            daysPerAligner = get(Keys.DAYS_PER) ?: d.daysPerAligner,
            onbWearGoalAffirmed = get(Keys.ONB_GOAL) ?: d.onbWearGoalAffirmed,
            notifPrimingDone = get(Keys.NOTIF_PRIMING_DONE) ?: d.notifPrimingDone,
            putBackSessionStart = get(Keys.PUT_BACK_SESSION_START) ?: d.putBackSessionStart,
            putBackAtEpoch = get(Keys.PUT_BACK_AT) ?: d.putBackAtEpoch,
            putBackDecided = get(Keys.PUT_BACK_DECIDED) ?: d.putBackDecided,
            putBackCycleStart = get(Keys.PUT_BACK_CYCLE_START) ?: d.putBackCycleStart,
            breakTargetAtEpoch = get(Keys.BREAK_TARGET_AT) ?: d.breakTargetAtEpoch
        )
    }
}