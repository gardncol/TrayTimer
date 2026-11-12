package com.traytimer.ui.nav

import kotlinx.coroutines.flow.MutableStateFlow

/** Bus for deep links / voice commands to steer the bottom tab. */
object TabBus {
    val tab = MutableStateFlow(0)

    /** One-shot deep-link event path (consumed by AppRoot). */
    val deepLinkEvent = MutableStateFlow<String?>(null)

    /** Signals the Teethies tab to open the camera immediately ("take a teethie"). */
    val deepLinkCamera = MutableStateFlow(false)

    const val TAB_TIMER = 0
    const val TAB_CALENDAR = 1
    const val TAB_TEETHIES = 2
    const val TAB_STATS = 3

    fun open(tabIndex: Int) {
        tab.value = tabIndex
    }
}

object Routes {
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val CAMERA = "camera"
    const val DAY = "day/{epochDay}"
    const val ADD_EVENT = "add-event/{epochDay}"
    const val ADD_NOTE = "add-note/{epochDay}"
    const val APPOINTMENT = "appointment/{id}"
    const val LOG_SWITCH = "log-switch/{epochDay}"
    const val TREATMENT = "treatment"
    const val SETTINGS = "settings"
    const val SETTINGS_TIMER = "settings/timer"
    const val SETTINGS_REMINDERS = "settings/reminders"
    const val SETTINGS_NOTIFICATIONS = "settings/notifications"
    const val SETTINGS_CALENDAR = "settings/calendar"
    const val SETTINGS_DATA = "settings/data"
    const val SETTINGS_LIVE = "settings/live"
    const val SETTINGS_WIDGETS = "settings/widgets"
    const val SETTINGS_TILE = "settings/tile"
    const val SETTINGS_VOICE = "settings/voice"
    const val SETTINGS_PHOTOS = "settings/photos"
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val SETTINGS_TIME = "settings/time"
    const val SETTINGS_ADS = "settings/ads"
    const val SETTINGS_ABOUT = "settings/about"
    const val SETTINGS_OEM = "settings/oem"
}