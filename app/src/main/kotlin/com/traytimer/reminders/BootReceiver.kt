package com.traytimer.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.traytimer.core.Graph
import com.traytimer.core.logic.Analytics
import com.traytimer.liveupdates.LiveUpdateService
import com.traytimer.widgets.WidgetRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Boot / quickboot / app-update recovery (FR-REM-10/11): reschedules every alarm
 * from persisted state and re-arms the Live Updates service when it was enabled.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Analytics.log("boot_recovery", mapOf("action" to action))
                AlarmScheduler.rescheduleAll(context)
                val settings = Graph.settingsStore.current()
                if (settings.liveUpdatesEnabled) {
                    runCatching { LiveUpdateService.start(context) }
                }
                WidgetRefresher.pushAll(context)
            } finally {
                @Suppress("DEPRECATION")
                pending.finish()
            }
        }
    }
}