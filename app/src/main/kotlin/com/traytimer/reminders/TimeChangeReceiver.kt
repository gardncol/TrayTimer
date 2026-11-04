package com.traytimer.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.traytimer.core.Graph
import com.traytimer.core.logic.Analytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Device clock / timezone change recovery (FR-TMR-16, FR-REM-16): reschedules alarms
 * and prompts the user (via MainActivity comparing zones) to switch display zone.
 */
class TimeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Analytics.log("time_changed", mapOf("action" to intent.action))
                if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
                    Graph.settingsStore.update { s, p ->
                        // surface the prompt in the Timer tab on next resume
                        p[com.traytimer.data.prefs.Keys.PENDING_ZONE] =
                            java.time.ZoneId.systemDefault().id
                    }
                }
                AlarmScheduler.rescheduleAll(context)
            } finally {
                @Suppress("DEPRECATION")
                    pending.finish()
            }
        }
    }
}