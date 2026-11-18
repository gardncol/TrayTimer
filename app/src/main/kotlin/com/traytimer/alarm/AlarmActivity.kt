package com.traytimer.alarm

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.traytimer.core.Graph
import com.traytimer.core.logic.DayMath
import com.traytimer.core.logic.PlanEngine
import com.traytimer.core.notifications.Notifications
import com.traytimer.reminders.AlarmScheduler
import com.traytimer.ui.brushing.BrushingActivity
import com.traytimer.reminders.ReminderReceiver
import com.traytimer.widgets.WidgetRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private data class TrayChangeHint(val nextLabel: String, val overdueDays: Int)

/**
 * Full-screen wearing reminder (FR-REM-06/07): shows on the lock screen, shows when
 * locked, and turns the screen on. Launched via the USE_FULL_SCREEN_INTENT
 * notification; rings through [AlarmRingingService] until dismissed.
 */
class AlarmActivity : ComponentActivity() {

    companion object {
        const val EXTRA_KIND = "kind"
        const val KIND_PUT_BACK = "put_back"
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    /** True after the patient silences the sound; the full-screen notice stays. */
    private val muted = androidx.compose.runtime.mutableStateOf(false)
    /** Non-null when today is the tray-change day (or an overdue switch is pending). */
    private val trayHint = MutableStateFlow<TrayChangeHint?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Show over the lock screen with the screen on.
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        AlarmRingingService.start(this)

        scope.launch {
            val hint = loadTrayChangeHint()
            withContext(Dispatchers.Main) { trayHint.value = hint }
        }

        setContent {
            val hint by trayHint.collectAsState()
            MaterialTheme {
                AlarmScreen(
                    muted = muted.value,
                    trayHint = hint,
                    onWearing = { finishWithAction { wornNow() } },
                    onBrush = { brushTeeth() },
                    onAdd5 = { finishWithAction { extend(5) } },
                    onAdd10 = { finishWithAction { extend(10) } },
                    onDismiss = { finishWithAction { dismiss() } },
                    onMute = { muteAlarm() },
                    onUnmute = { unmuteAlarm() }
                )
            }
        }
    }

    /**
     * Tells the alarm screen when it coincides with the current aligner's planned
     * change day (or a later overdue day), so the patient gets a visual nudge.
     */
    private suspend fun loadTrayChangeHint(): TrayChangeHint? = runCatching {
        val s = Graph.settingsStore.current()
        if (!s.trayChangeReminderEnabled) return@runCatching null
        val zone = DayMath.zone(s.useDeviceTimeZone, s.chosenTimeZone)
        val today = LocalDate.now(zone)
        val todayMidnight = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val trays = Graph.treatmentRepository.getTrays()
        val sorted = PlanEngine.sorted(trays)
        val idx = PlanEngine.currentIndex(sorted, todayMidnight)
        val next = sorted.getOrNull(idx + 1) ?: return@runCatching null
        val changeDay = Instant.ofEpochMilli(PlanEngine.effectiveEndInPlan(sorted, idx))
            .atZone(zone).toLocalDate()
        if (today.isBefore(changeDay)) return@runCatching null
        TrayChangeHint(
            nextLabel = next.label,
            overdueDays = ChronoUnit.DAYS.between(changeDay, today).toInt()
        )
    }.getOrNull()

    /** Silences the ringtone/vibration but keeps the full-screen reminder visible. */
    private fun muteAlarm() {
        if (muted.value) return
        muted.value = true
        AlarmRingingService.stop(this)
    }

    private fun unmuteAlarm() {
        if (!muted.value) return
        muted.value = false
        AlarmRingingService.start(this)
    }

    /** Volume-down mutes (volume-up restores), without leaving the alarm screen. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN -> {
            muteAlarm()
            true
        }
        KeyEvent.KEYCODE_VOLUME_UP -> {
            if (muted.value) {
                unmuteAlarm()
                true
            } else {
                super.onKeyDown(keyCode, event)
            }
        }
        else -> super.onKeyDown(keyCode, event)
    }

    private fun finishWithAction(action: suspend () -> Unit) {
        scope.launch {
            action()
        }
        finish()
    }

    private suspend fun wornNow() {
        // The patient says the trays are back in: close the open session.
        Graph.timerRepository.stopOut()
        Graph.settingsStore.updateAwait { s, p ->
            p[com.traytimer.data.prefs.Keys.PUT_BACK_DECIDED] = false
            p[com.traytimer.data.prefs.Keys.PUT_BACK_SESSION_START] = 0L
            p[com.traytimer.data.prefs.Keys.PUT_BACK_AT] = 0L
            p[com.traytimer.data.prefs.Keys.PUT_BACK_CYCLE_START] = 0L
            p[com.traytimer.data.prefs.Keys.BREAK_TARGET_AT] = 0L
        }
        AlarmScheduler.cancelPutBack(this)
        Notifications.cancel(this, Notifications.NOTIF_PUT_BACK)
        WidgetRefresher.pushAll(this)
        AlarmScheduler.rescheduleAll(this)
    }

    /** Launches the 2-minute brushing timer from the alarm (trays are already out). */
    private fun brushTeeth() {
        Notifications.cancel(this, Notifications.NOTIF_PUT_BACK)
        AlarmRingingService.stop(this)
        startActivity(Intent(this, BrushingActivity::class.java))
        finish()
    }

    /** "Add 5 / 10 min": re-remind in N minutes while the trays stay out. */
    private suspend fun extend(minutes: Int) {
        val open = Graph.timerRepository.getOpen() ?: run {
            dismiss()
            return
        }
        val now = System.currentTimeMillis()
        val s = Graph.settingsStore.current()
        val cycleStart = if (s.putBackCycleStart > 0) s.putBackCycleStart else now
        val at = now + minutes * 60_000L
        Graph.settingsStore.updateAwait { st, p ->
            p[com.traytimer.data.prefs.Keys.PUT_BACK_CYCLE_START] = cycleStart
            p[com.traytimer.data.prefs.Keys.PUT_BACK_SESSION_START] = open.startEpoch
            p[com.traytimer.data.prefs.Keys.PUT_BACK_DECIDED] = true
            p[com.traytimer.data.prefs.Keys.PUT_BACK_AT] = at
            p[com.traytimer.data.prefs.Keys.BREAK_TARGET_AT] = at
        }
        AlarmScheduler.schedulePutBack(this, at)
        Notifications.cancel(this, Notifications.NOTIF_PUT_BACK)
        AlarmRingingService.stop(this)
    }

    private suspend fun dismiss() {
        // Dismissing stops the repeating reminder until the next state change.
        Graph.settingsStore.updateAwait { s, p ->
            p[com.traytimer.data.prefs.Keys.PUT_BACK_DECIDED] = true
            p[com.traytimer.data.prefs.Keys.PUT_BACK_AT] = 0L
            p[com.traytimer.data.prefs.Keys.PUT_BACK_CYCLE_START] = 0L
            p[com.traytimer.data.prefs.Keys.BREAK_TARGET_AT] = 0L
        }
        AlarmRingingService.stop(this)
        Notifications.cancel(this, Notifications.NOTIF_PUT_BACK)
        AlarmScheduler.cancelPutBack(this)
    }

    override fun onDestroy() {
        AlarmRingingService.stop(this)
        super.onDestroy()
    }
}

@Composable
private fun AlarmScreen(
    muted: Boolean,
    trayHint: TrayChangeHint?,
    onWearing: () -> Unit,
    onBrush: () -> Unit,
    onAdd5: () -> Unit,
    onAdd10: () -> Unit,
    onDismiss: () -> Unit,
    onMute: () -> Unit,
    onUnmute: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF042F2E)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "⏰",
                fontSize = 64.sp,
                color = Color(0xFF5EEAD4)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = androidx.compose.ui.res.stringResource(com.traytimer.R.string.alarm_title),
                color = Color.White,
                fontSize = 28.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = androidx.compose.ui.res.stringResource(com.traytimer.R.string.alarm_body),
                color = Color(0xFFB2DFDB),
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
            trayHint?.let { hint ->
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF7C2D12).copy(alpha = 0.65f), RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (hint.overdueDays <= 0) {
                                "🔁 Today is your aligner change day"
                            } else {
                                "🔁 Aligner change overdue by ${hint.overdueDays} day" +
                                    if (hint.overdueDays == 1) "" else "s"
                            },
                            color = Color(0xFFFDE68A),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Switch to aligner ${hint.nextLabel} after this reminder.",
                            color = Color(0xFFFDE68A),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (muted) {
                Text(
                    text = "Alarm muted — the reminder stays on screen.",
                    color = Color(0xFF80CBC4),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                TextButton(onClick = onUnmute) {
                    Text("Unmute alarm", color = Color(0xFF5EEAD4))
                }
            } else {
                TextButton(onClick = onMute) {
                    Text("🔇 Mute alarm (or press volume down)", color = Color(0xFF80CBC4))
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onWearing,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2DD4BF)),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(com.traytimer.R.string.alarm_im_wearing),
                    color = Color(0xFF00423B),
                    fontSize = 18.sp
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onBrush,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("Brush teeth (2 min)", color = Color(0xFF5EEAD4))
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onAdd5,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                ) {
                    Text("+5 min", color = Color(0xFF5EEAD4))
                }
                OutlinedButton(
                    onClick = onAdd10,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                ) {
                    Text("+10 min", color = Color(0xFF5EEAD4))
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(com.traytimer.R.string.alarm_dismiss),
                    color = Color(0xFF80CBC4)
                )
            }
        }
    }
}