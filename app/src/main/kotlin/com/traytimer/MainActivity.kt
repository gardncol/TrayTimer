package com.traytimer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.traytimer.core.Graph
import com.traytimer.core.logic.Analytics
import com.traytimer.reminders.AlarmScheduler
import com.traytimer.ui.nav.TabBus
import com.traytimer.ui.screens.AppRoot
import com.traytimer.ui.theme.TrayTimerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        handleDeepLink(intent)

        setContent {
            val theme by Graph.settingsStore.settings.collectAsStateCompat(initial = null)
            TrayTimerTheme(theme?.themeMode ?: "SYSTEM") {
                AppRoot()
            }
        }

        lifecycleScope.launch {
            // Keep-screen-on option (FR-TMR-14)
            Graph.settingsStore.settings.collect { s ->
                if (s.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * Deep links (FR-VOX-04, FR-CAL-07, FR-STS-05): traytimer://app.traytimer.com/...
     * and verified https://app.traytimer.com links. Voice utterances map to the
     * same paths.
     */
    private fun handleDeepLink(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        val path = data.path?.removePrefix("/") ?: return
        Analytics.log("deep_link", mapOf("path" to path))
        when (path) {
            "view.calendar" -> { TabBus.open(TabBus.TAB_CALENDAR); TabBus.deepLinkEvent.value = path }
            "view.stats" -> { TabBus.open(TabBus.TAB_STATS); TabBus.deepLinkEvent.value = path }
            "take.photo" -> {
                TabBus.open(TabBus.TAB_TEETHIES)
                TabBus.deepLinkCamera.value = true
                TabBus.deepLinkEvent.value = path
            }
            "home" -> { TabBus.open(TabBus.TAB_TIMER); TabBus.deepLinkEvent.value = path }
            "start.timer" -> lifecycleScope.launch {
                // "Hey Google, start wearing" -> trays back in
                Graph.timerRepository.stopOut()
                AlarmScheduler.rescheduleAll(this@MainActivity)
            }
            "stop.timer" -> lifecycleScope.launch {
                Graph.timerRepository.startOut()
                AlarmScheduler.rescheduleAll(this@MainActivity)
            }
            "switch.aligners", "change.trays" -> lifecycleScope.launch { switchTrayFromVoice() }
        }
    }

    private suspend fun switchTrayFromVoice() {
        val s = Graph.settingsStore.current()
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val trays = Graph.treatmentRepository.getTrays()
        val idx = com.traytimer.core.logic.PlanEngine.currentIndex(trays, today)
        if (idx + 1 < trays.size) {
            Graph.treatmentRepository.logSwitch(idx + 1, System.currentTimeMillis(), s.autoAdjustEnabled)
            Analytics.log("tray_changed_from_voice")
            AlarmScheduler.rescheduleAll(this@MainActivity)
        }
    }
}

/** Small compat helper so activity code can use Compose collect syntax. */
@androidx.compose.runtime.Composable
private fun <T> kotlinx.coroutines.flow.Flow<T>.collectAsStateCompat(initial: T): androidx.compose.runtime.State<T> =
    this.collectAsStateWithLifecycle(initialValue = initial)