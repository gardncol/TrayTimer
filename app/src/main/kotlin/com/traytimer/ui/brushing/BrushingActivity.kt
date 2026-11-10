package com.traytimer.ui.brushing

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.traytimer.R
import com.traytimer.core.Graph
import com.traytimer.core.logic.Analytics
import com.traytimer.core.notifications.Notifications
import com.traytimer.liveupdates.LiveUpdateService
import com.traytimer.reminders.AlarmScheduler
import com.traytimer.ui.theme.Teal200
import com.traytimer.ui.theme.Teal400
import com.traytimer.ui.theme.Teal900
import com.traytimer.widgets.WidgetRefresher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Brushing mode: marks the trays out if needed, runs a 2-minute full-screen
 * countdown, then marks the trays back in. Cancelling leaves the trays out.
 */
class BrushingActivity : ComponentActivity() {

    private val remainingMs = MutableStateFlow(TOTAL_MS)
    private val finished = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            val ms by remainingMs.collectAsState()
            val done by finished.collectAsState()
            BrushingScreen(
                remainingMs = ms,
                finished = done,
                onCancel = { finish() },
                onDone = { finish() }
            )
        }

        lifecycleScope.launch {
            // Brushing happens with the trays out. Start a break if one isn't running;
            // unlike the normal timer, no put-back reminder is scheduled here.
            if (Graph.timerRepository.getOpen() == null) {
                Graph.timerRepository.startOut()
            }
            val endAt = System.currentTimeMillis() + TOTAL_MS
            while (isActive) {
                val left = endAt - System.currentTimeMillis()
                remainingMs.value = left.coerceAtLeast(0L)
                if (left <= 0L) break
                delay(100L)
            }
            // Two minutes are up: mark the trays back in.
            Graph.timerRepository.stopOut()
            Graph.settingsStore.updateAwait { _, p ->
                p[com.traytimer.data.prefs.Keys.PUT_BACK_DECIDED] = false
                p[com.traytimer.data.prefs.Keys.PUT_BACK_SESSION_START] = 0L
                p[com.traytimer.data.prefs.Keys.PUT_BACK_AT] = 0L
                p[com.traytimer.data.prefs.Keys.PUT_BACK_CYCLE_START] = 0L
                p[com.traytimer.data.prefs.Keys.BREAK_TARGET_AT] = 0L
            }
            AlarmScheduler.cancelPutBack(this@BrushingActivity)
            Notifications.cancel(this@BrushingActivity, Notifications.NOTIF_PUT_BACK)
            AlarmScheduler.rescheduleAll(this@BrushingActivity)
            WidgetRefresher.pushAll(this@BrushingActivity)
            LiveUpdateService.refresh(this@BrushingActivity, wearing = true)
            Analytics.log("brushing_complete")
            finished.value = true
        }
    }

    companion object {
        const val TOTAL_MS = 2L * 60L * 1000L
    }
}

@Composable
private fun BrushingScreen(
    remainingMs: Long,
    finished: Boolean,
    onCancel: () -> Unit,
    onDone: () -> Unit
) {
    val progress = (remainingMs.toFloat() / 120_000f).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Teal900),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Text("🪥", fontSize = 44.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "Brushing mode",
                color = Teal200,
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(Modifier.height(24.dp))

            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(300.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 18.dp.toPx()
                    val inset = stroke
                    val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                    drawArc(
                        color = Color.White.copy(alpha = 0.12f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = Teal400,
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (finished) {
                        Text(
                            "0:00",
                            fontSize = 64.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    } else {
                        Text(
                            formatRemaining(remainingMs),
                            fontSize = 68.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            "Trays marked in when it ends",
                            color = Color(0xFFB2DFDB),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            if (finished) {
                Text(
                    "Trays marked as in",
                    color = Teal200,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onDone,
                    colors = ButtonDefaults.buttonColors(containerColor = Teal400),
                    shape = RoundedCornerShape(28.dp)
                ) { Text("Done", color = Color(0xFF00423B)) }
            } else {
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("Cancel (trays stay out)", color = Color(0xFF80CBC4))
                }
            }
        }
    }
}

private fun formatRemaining(millis: Long): String {
    val totalSeconds = ((millis + 999L) / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
