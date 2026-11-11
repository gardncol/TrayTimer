package com.traytimer.ui.screens

import android.content.Intent
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.traytimer.R
import com.traytimer.core.Graph
import com.traytimer.core.logic.Analytics
import com.traytimer.core.logic.DayMath
import com.traytimer.core.notifications.Notifications
import com.traytimer.data.db.OutSession
import com.traytimer.reminders.AlarmScheduler
import com.traytimer.ui.MainViewModel
import com.traytimer.ui.brushing.BrushingActivity
import com.traytimer.ui.nav.Routes
import com.traytimer.ui.nav.TabBus
import com.traytimer.ui.theme.GrayOut
import com.traytimer.ui.theme.TimerInBlue
import com.traytimer.ui.theme.TimerOutRed
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

@Composable
fun TimerScreen(
    vm: MainViewModel,
    modifier: Modifier,
    navController: NavHostController
) {
    val settings by vm.settings.collectAsState()
    val timerState by vm.timerState.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val view = LocalView.current
    val context = LocalContext.current

    var editMode by remember { mutableStateOf(false) }
    var editingSession by remember { mutableStateOf<OutSession?>(null) }
    var showAdjustTimer by remember { mutableStateOf(false) }
    var showGoalDialog by remember { mutableStateOf(false) }
    var deletingSession by remember { mutableStateOf<OutSession?>(null) }
    var showReminderPrompt by remember { mutableStateOf(false) }
    // Non-null while the accidental-tap dialog is open; true = stop, false = start.
    var pendingWillStop by remember { mutableStateOf<Boolean?>(null) }

    val zone = remember(settings) { vm.zone(settings) }
    val goalHours = settings?.wearGoalHours ?: 22

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ---- Header (FR-TMR-10) ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        timerState.currentTray?.let { "Tray ${it.label}" } ?: "No plan",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        "${timerState.daysLeftInTray} days left in tray",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Text(
                    "${timerState.daysLeftInTreatment} days left in treatment",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Spacer(Modifier.height(12.dp))

            // ---- Ring chart (FR-TMR-01..06) ----
            val outNow = timerState.openSession != null
            val stateColor = if (outNow) TimerOutRed else TimerInBlue
            val goalMillis = goalHours * 3_600_000L
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .aspectRatio(1f)
                    .clickable {
                        val willStop = timerState.openSession != null
                        if (confirmNeeded(timerState, settings)) {
                            pendingWillStop = willStop
                        } else {
                            if (settings?.vibrate == true) {
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            }
                            if (willStop) vm.stopOut()
                            else vm.startOut { showReminderPrompt = true }
                        }
                    }
            ) {
                WearRing(
                    inMillis = timerState.inMillis,
                    outMillis = timerState.outMillis,
                    goalMillis = goalMillis,
                    ringColor = stateColor,
                    modifier = Modifier.fillMaxSize()
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("In time", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Text(
                        DayMath.formatHm(timerState.inMillis, settings?.timerShowSeconds ?: false),
                        style = MaterialTheme.typography.headlineLarge.copy(fontSize = 44.sp),
                        color = stateColor
                    )
                    Text(
                        "Out time  ${DayMath.formatHm(timerState.outMillis, settings?.timerShowSeconds ?: false)}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (outNow) TimerOutRed else GrayOut
                    )
                    timerState.remainingMillis?.let { left ->
                        val mins = (left + 59_999L) / 60_000L
                        Text(
                            "$mins min left",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (outNow) TimerOutRed else TimerInBlue
                        )
                    }
                    if (outNow && timerState.openSession != null) {
                        timerState.openSession?.let { open ->
                            val startedAt = Instant.ofEpochMilli(open.startEpoch)
                                .atZone(ZoneId.systemDefault()).toLocalTime()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Started at %02d:%02d".format(startedAt.hour, startedAt.minute),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                                TextButton(onClick = { showAdjustTimer = true }) { Text("Adjust") }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ---- Daily goal summary; tap to adjust (FR-TMR-04/05) ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showGoalDialog = true }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Flag,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Daily wear goal", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Most orthodontists recommend 22 hours",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Text("${goalHours}h", style = MaterialTheme.typography.titleLarge)
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Adjust daily wear goal",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { undoLast(vm, timerState) },
                    enabled = timerState.todaySessions.isNotEmpty()
                ) {
                    Icon(Icons.Default.Undo, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Undo")
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { editMode = !editMode }) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (editMode) "Done" else "Edit")
                }
            }

            if (timerState.openSession != null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(context, BrushingActivity::class.java)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(painterResource(R.drawable.ic_toothbrush), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Brushing mode · 2 min")
                }
            }

            Spacer(Modifier.height(8.dp))

            // ---- Edit mode: today's entries (FR-TMR-11) ----
            if (editMode) {
                SectionCard {
                    Text("Today's time entries", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    if (timerState.todaySessions.isEmpty()) {
                        Text("No time out today", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    timerState.todaySessions.forEach { s ->
                        val startText = formatTime(s.startEpoch, zone)
                        val endText = s.endEpoch?.let { formatTime(it, zone) } ?: "now"
                        val dur = (s.endEpoch ?: System.currentTimeMillis()) - s.startEpoch
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Out $startText – $endText (${DayMath.formatHm(dur)})" +
                                    if (s.endEpoch == null) "  · running" else "",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        if (s.endEpoch == null) showAdjustTimer = true else editingSession = s
                                    }
                            )
                            IconButton(onClick = { deletingSession = s }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "delete entry",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ---- Adjust treatment affordance (FR-TMR-10) ----
            if (timerState.currentTray == null) {
                OutlinedButton(onClick = { navController.navigate(Routes.TREATMENT) }) {
                    Text("Set up your treatment plan")
                }
            } else {
                OutlinedButton(onClick = { navController.navigate(Routes.TREATMENT) }) {
                    Text("Adjust Current Treatment")
                }
                Spacer(Modifier.height(6.dp))
                TrayTotalText(vm, timerState)
            }

            Spacer(Modifier.height(80.dp))
        }
    }

    if (showGoalDialog) {
        GoalDialog(
            goalHours = goalHours,
            onSet = { vm.setGoalHours(it) },
            onDismiss = { showGoalDialog = false }
        )
    }

    // ---- Put-back reminder prompt (FR-TMR-07) ----
    if (showReminderPrompt) {
        PutBackReminderPrompt(
            onChoose = { minutes ->
                showReminderPrompt = false
                scope.launch {
                    val open = Graph.timerRepository.getOpen() ?: return@launch
                    val at = if (minutes != null) open.startEpoch + minutes * 60_000L else 0L
                    Graph.settingsStore.updateAwait { st, p ->
                        p[com.traytimer.data.prefs.Keys.PUT_BACK_SESSION_START] = open.startEpoch
                        p[com.traytimer.data.prefs.Keys.PUT_BACK_AT] = at
                        p[com.traytimer.data.prefs.Keys.PUT_BACK_DECIDED] = true
                        p[com.traytimer.data.prefs.Keys.PUT_BACK_CYCLE_START] = 0L
                        p[com.traytimer.data.prefs.Keys.BREAK_TARGET_AT] = at
                    }
                    if (at > 0) AlarmScheduler.schedulePutBack(Graph.appContext, at)
                    else AlarmScheduler.cancelPutBack(Graph.appContext)
                }
            }
        )
    }

    // ---- Accidental tap protection (FR-TMR-09) ----
    pendingWillStop?.let { willStop ->
        ConfirmDialog(
            title = if (willStop) "Stop the timer?" else "Start the timer?",
            text = if (willStop)
                "You started the timer less than a minute ago. Stop it anyway?"
            else
                "You stopped the timer less than a minute ago. Start it anyway?",
            confirmLabel = "Yes",
            onConfirm = {
                pendingWillStop = null
                if (settings?.vibrate == true) {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                if (willStop) vm.stopOut()
                else vm.startOut { showReminderPrompt = true }
            },
            onDismiss = { pendingWillStop = null }
        )
    }

    // ---- Edit session dialog ----
    editingSession?.let { session ->
        EditSessionDialog(
            session = session,
            zone = zone,
            onSave = { updated ->
                vm.updateSession(updated)
                editingSession = null
            },
            onCancel = { editingSession = null }
        )
    }

    if (showAdjustTimer) {
        AdjustTimerDialog(
            onSet = { minutes ->
                showAdjustTimer = false
                scope.launch {
                    val open = Graph.timerRepository.getOpen() ?: return@launch
                    val at = System.currentTimeMillis() + minutes * 60_000L
                    Graph.settingsStore.updateAwait { st, p ->
                        p[com.traytimer.data.prefs.Keys.PUT_BACK_SESSION_START] = open.startEpoch
                        p[com.traytimer.data.prefs.Keys.PUT_BACK_AT] = at
                        p[com.traytimer.data.prefs.Keys.PUT_BACK_DECIDED] = true
                        p[com.traytimer.data.prefs.Keys.PUT_BACK_CYCLE_START] = 0L
                        p[com.traytimer.data.prefs.Keys.BREAK_TARGET_AT] = at
                    }
                    AlarmScheduler.schedulePutBack(Graph.appContext, at)
                    Notifications.cancel(Graph.appContext, Notifications.NOTIF_PUT_BACK)
                    Analytics.log("timer_adjusted", mapOf("minutes" to minutes))
                }
            },
            onCancel = { showAdjustTimer = false }
        )
    }

    // ---- Delete confirm (FR-TMR-11) ----
    deletingSession?.let { session ->
        ConfirmDialog(
            title = "Delete time entry?",
            text = "Are you sure you want to delete this time entry permanently?",
            confirmLabel = "Delete",
            danger = true,
            onConfirm = {
                vm.deleteSession(session)
                deletingSession = null
            },
            onDismiss = { deletingSession = null }
        )
    }
}

@Composable
private fun TrayTotalText(vm: MainViewModel, timerState: MainViewModel.TimerState) {
    var total by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(timerState.currentTray) {
        val tray = timerState.currentTray ?: return@LaunchedEffect
        while (true) {
            total = vm.wearForCurrentTray(tray.orderIndex)
            kotlinx.coroutines.delay(30_000L)
        }
    }
    Text(
        "Total time in this aligner: ${DayMath.formatHm(total)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
}

/** Accidental-tap detection (FR-TMR-09): started/stopped less than a minute ago. */
private fun confirmNeeded(state: MainViewModel.TimerState, settings: com.traytimer.data.prefs.AppSettings?): Boolean {
    if (settings?.accidentalTapDetection != true) return false
    val now = System.currentTimeMillis()
    val open = state.openSession
    return if (open != null) {
        // about to stop an out-session started < 1 min ago
        now - open.startEpoch < 60_000L
    } else {
        // about to start while last session closed < 1 min ago
        val lastClosed = state.todaySessions.lastOrNull { it.endEpoch != null }
        lastClosed?.let { now - it.endEpoch!! < 60_000L } ?: false
    }
}

/** Undo (FR-TMR-08): reverse the most recent pause/start. */
private fun undoLast(vm: MainViewModel, state: MainViewModel.TimerState) {
    val open = state.openSession
    if (open != null) {
        vm.deleteSession(open) // undo "start out" -> back to wearing
    } else {
        val last = state.todaySessions.lastOrNull { it.endEpoch != null } ?: return
        vm.updateSession(last.copy(endEpoch = null)) // re-open that break
    }
}

@Composable
private fun WearRing(
    inMillis: Long,
    outMillis: Long,
    goalMillis: Long,
    ringColor: Color,
    modifier: Modifier = Modifier
) {
    val trackColor = Color.Gray.copy(alpha = 0.25f)
    Canvas(modifier = modifier) {
        // Thicker ring so the wear/out arcs are easier to read at a glance.
        val stroke = 38f
        val inset = stroke
        val sizeArc = Size(this.size.width - inset * 2, this.size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        val dayMillis = 24f * 3_600_000f

        fun sweep(millis: Long): Float =
            (millis / dayMillis).coerceIn(0f, 1f) * 360f

        // Track
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = sizeArc,
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
        // Out arc (fork-and-knife side)
        drawArc(
            color = GrayOut,
            startAngle = -90f,
            sweepAngle = sweep(outMillis),
            useCenter = false,
            topLeft = topLeft,
            size = sizeArc,
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
        // Wear arc (teal, starts at top)
        drawArc(
            color = ringColor,
            startAngle = -90f,
            sweepAngle = sweep(inMillis),
            useCenter = false,
            topLeft = topLeft,
            size = sizeArc,
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
        // Goal tick (FR-TMR-03)
        val tickAngle = Math.toRadians(((goalMillis / dayMillis).coerceIn(0f, 1f) * 360f - 90f).toDouble())
        val r = sizeArc.width / 2f
        val c = Offset(inset + r, inset + r)
        val inner = r - stroke * 0.9f
        val outer = r + stroke * 0.9f
        drawLine(
            color = Color.White,
            start = Offset(
                c.x + inner * kotlin.math.cos(tickAngle).toFloat(),
                c.y + inner * kotlin.math.sin(tickAngle).toFloat()
            ),
            end = Offset(
                c.x + outer * kotlin.math.cos(tickAngle).toFloat(),
                c.y + outer * kotlin.math.sin(tickAngle).toFloat()
            ),
            strokeWidth = 4f
        )
        // Glyph hints near the top (thumbs-up = wear at start of arc)
        drawCircle(color = Color(0x33000000), radius = 3f, center = c)
    }
}

@Composable
private fun GoalDialog(goalHours: Int, onSet: (Int) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(goalHours) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daily wear goal") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Most orthodontists recommend 22 hours",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(onClick = {
                        if (value > 1) {
                            value -= 1
                            onSet(value)
                        }
                    }) { Icon(Icons.Default.Remove, contentDescription = "lower goal") }
                    Text(
                        "${value}h",
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    IconButton(onClick = {
                        if (value < 23) {
                            value += 1
                            onSet(value)
                        }
                    }) { Icon(Icons.Default.Add, contentDescription = "raise goal") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun PutBackReminderPrompt(onChoose: (Int?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onChoose(null) },
        title = { Text("Put your aligners back in a reminder?") },
        text = {
            Column {
                listOf(15, 30, 45, 60).forEach { minutes ->
                    TextButton(onClick = { onChoose(minutes) }, modifier = Modifier.fillMaxWidth()) {
                        Text("$minutes minutes")
                    }
                }
                var customText by remember { mutableStateOf("") }
                androidx.compose.material3.OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("Custom time (minutes)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                androidx.compose.material3.Button(
                    onClick = { customText.toIntOrNull()?.takeIf { it > 0 }?.let { onChoose(it) } },
                    enabled = customText.toIntOrNull()?.let { it > 0 } == true,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Set custom reminder") }
                TextButton(onClick = { onChoose(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("No Reminder")
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSessionDialog(
    session: OutSession,
    zone: ZoneId,
    onSave: (OutSession) -> Unit,
    onCancel: () -> Unit
) {
    val now = System.currentTimeMillis()
    val startDate = Instant.ofEpochMilli(session.startEpoch).atZone(zone).toLocalDate()
    val endDate = Instant.ofEpochMilli(session.endEpoch ?: now).atZone(zone).toLocalDate()
    val startTime = Instant.ofEpochMilli(session.startEpoch).atZone(zone).toLocalTime()
    val endTime = Instant.ofEpochMilli(session.endEpoch ?: now).atZone(zone).toLocalTime()

    val startState = rememberTimePickerState(startTime.hour, startTime.minute, is24Hour = true)
    val endState = rememberTimePickerState(endTime.hour, endTime.minute, is24Hour = true)

    var editStart by remember { mutableStateOf(false) }
    var editEnd by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Adjust time entry") },
        text = {
            Column {
                Text(
                    "Adjust the out and in times for this entry.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { editStart = true; editEnd = false }) {
                    Text("Out time: %02d:%02d".format(startState.hour, startState.minute))
                }
                if (editStart) TimePicker(state = startState)
                TextButton(onClick = { editEnd = true; editStart = false }) {
                    Text("In time: %02d:%02d".format(endState.hour, endState.minute))
                }
                if (editEnd) TimePicker(state = endState)
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val newStart = startDate.atTime(startState.hour, startState.minute)
                    .atZone(zone).toInstant().toEpochMilli()
                val newEnd = endDate.atTime(endState.hour, endState.minute)
                    .atZone(zone).toInstant().toEpochMilli()
                when {
                    newEnd <= newStart -> error = "In time must be after out time"
                    newEnd > now -> error = "In time can't be in the future"
                    else -> onSave(session.copy(startEpoch = newStart, endEpoch = newEnd))
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

/** Prompts for a new remaining duration for the running trays-out timer. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AdjustTimerDialog(onSet: (Int) -> Unit, onCancel: () -> Unit) {
    var customText by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Adjust timer") },
        text = {
            Column {
                Text(
                    "How much time should remain? The original out time stays the same.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(15, 30, 45, 60, 90).forEach { minutes ->
                        androidx.compose.material3.AssistChip(
                            onClick = { onSet(minutes) },
                            label = { Text("$minutes min") }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("Custom minutes") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = { customText.toIntOrNull()?.takeIf { it > 0 }?.let(onSet) },
                    enabled = (customText.toIntOrNull() ?: 0) > 0,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Set custom timer") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

private fun formatTime(epoch: Long, zone: ZoneId): String {
    val t = Instant.ofEpochMilli(epoch).atZone(zone).toLocalTime()
    return "%02d:%02d".format(t.hour, t.minute)
}