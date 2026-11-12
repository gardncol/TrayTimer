package com.traytimer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.traytimer.core.Graph
import com.traytimer.core.logic.DayMath
import com.traytimer.core.logic.GoalColor
import com.traytimer.data.db.Appointment
import com.traytimer.ui.MainViewModel
import com.traytimer.ui.nav.Routes
import com.traytimer.ui.theme.GreenGoal
import com.traytimer.ui.theme.RedMiss
import com.traytimer.ui.theme.YellowWarn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Month calendar heat map + quick-add (FR-CAL-01..08). */
@Composable
fun CalendarScreen(vm: MainViewModel, modifier: Modifier, navController: NavHostController) {
    val settings by vm.settings.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val trays by vm.trays.collectAsState()
    val appointments by Graph.appointmentRepository.observeAppointments().collectAsState(initial = emptyList())
    val notes = remember { mutableStateOf<Map<Long, String>>(emptyMap()) }

    var month by remember { mutableStateOf(YearMonth.now()) }
    var quickMenu by remember { mutableStateOf(false) }

    val zone = remember(settings) { vm.zone(settings) }
    val resetMinutes = settings?.dayResetMinutes ?: 0
    val goalMillis = (settings?.wearGoalHours ?: 22) * 3_600_000L
    val greenMillis = (settings?.greenThresholdHours ?: 22) * 3_600_000L
    val yellowMillis = (settings?.yellowThresholdHours ?: 19) * 3_600_000L

    // Load notes for the visible month
    LaunchedEffect(month, zone) {
        val map = HashMap<Long, String>()
        for (d in 1..month.lengthOfMonth()) {
            val date = month.atDay(d)
            val note = Graph.appointmentRepository.noteForDay(date.toEpochDay())
            if (note != null) map[date.toEpochDay()] = note.note
        }
        notes.value = map
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.padding(12.dp)) {
            // Month navigation
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "previous month")
                }
                Text(
                    month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                IconButton(onClick = { month = month.plusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "next month")
                }
                TextButton(onClick = { month = YearMonth.now(zone) }) { Text("Today") }
            }

            // Weekday header
            val weekStartsSunday = settings?.startOfWeekSunday ?: true
            val dowLabels = if (weekStartsSunday)
                listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
            else
                listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            Row(Modifier.fillMaxWidth()) {
                dowLabels.forEach {
                    Text(
                        it,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .weight(1f)
                            .padding(2.dp)
                    )
                }
            }

            // Day cells
            val firstDow: Int = month.atDay(1).dayOfWeek.value // Monday=1..7
            val offset = if (weekStartsSunday) (firstDow % 7) else (firstDow - 1)
            val today = LocalDate.now(zone)
            val daysCount = month.lengthOfMonth()

            val firstTrackedDay: Long? = remember(sessions) {
                sessions.minOfOrNull { DayMath.dateAt(it.startEpoch, zone).toEpochDay() }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(offset) { Box(Modifier.aspectRatio(1f)) }
                items(daysCount) { idx ->
                    val day = month.atDay(idx + 1)
                    val epochDay = day.toEpochDay()
                    DayCell(
                        day = day,
                        today = day == today,
                        hasData = firstTrackedDay != null && epochDay >= firstTrackedDay ||
                            epochDay == today.toEpochDay(),
                        inMillis = DayMath.inMillisForDay(
                            epochDay, sessions, resetMinutes, zone,
                            System.currentTimeMillis()
                        ),
                        goalMillis = goalMillis,
                        greenMillis = greenMillis,
                        yellowMillis = yellowMillis,
                        hasAppointment = appointments.any { DayMath.dateAt(it.startEpoch, zone) == day },
                        hasNote = notes.value[day.toEpochDay()] != null,
                        trayLabel = trayLabelForDay(trays, day, zone),
                        onClick = { navController.navigate("day/${day.toEpochDay()}") }
                    )
                }
            }
        }

        // Quick-add FAB (FR-CAL-04)
        ExtendedFloatingActionButton(
            onClick = { quickMenu = true },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("Add") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        )
    }

    if (quickMenu) {
        AlertDialog(
            onDismissRequest = { quickMenu = false },
            title = { Text("Add to calendar") },
            text = {
                Column {
                    TextButton(onClick = {
                        quickMenu = false
                        navController.navigate("add-event/${LocalDate.now(zone).toEpochDay()}")
                    }) { Text("Add out/in event") }
                    TextButton(onClick = {
                        quickMenu = false
                        navController.navigate("log-switch/${LocalDate.now(zone).toEpochDay()}")
                    }) { Text("Log aligner switch") }
                    TextButton(onClick = {
                        quickMenu = false
                        navController.navigate("appointment/-1")
                    }) { Text("Orthodontic appointment") }
                    TextButton(onClick = {
                        quickMenu = false
                        navController.navigate("add-note/${LocalDate.now(zone).toEpochDay()}")
                    }) { Text("Day note") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { quickMenu = false }) { Text("Cancel") } }
        )
    }
}

/** Which tray is worn on [day] — used to render the tray number on the cell (FR-CAL-03). */
private fun trayLabelForDay(trays: List<com.traytimer.data.db.PlanTray>, day: LocalDate, zone: ZoneId): String? {
    val epoch = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val sorted = com.traytimer.core.logic.PlanEngine.sorted(trays)
    val current = com.traytimer.core.logic.PlanEngine.currentTray(sorted, epoch) ?: return null
    // Only label days where the tray actually changes (start of a new tray). Compare
    // calendar days so a switch logged at e.g. 08:00 still marks that day.
    val startDay = com.traytimer.core.logic.DayMath
        .dateAt(com.traytimer.core.logic.PlanEngine.effectiveStart(current), zone)
    return if (startDay == day) current.label else null
}

@Composable
private fun DayCell(
    day: LocalDate,
    today: Boolean,
    hasData: Boolean,
    inMillis: Long,
    goalMillis: Long,
    greenMillis: Long,
    yellowMillis: Long,
    hasAppointment: Boolean,
    hasNote: Boolean,
    trayLabel: String?,
    onClick: () -> Unit
) {
    val color = when {
        !hasData -> MaterialTheme.colorScheme.surfaceVariant
        inMillis <= 0 -> MaterialTheme.colorScheme.surfaceVariant
        DayMath.goalColor(inMillis, goalMillis, greenMillis, yellowMillis) == GoalColor.GREEN -> GreenGoal
        DayMath.goalColor(inMillis, goalMillis, greenMillis, yellowMillis) == GoalColor.YELLOW -> YellowWarn
        else -> RedMiss
    }.copy(alpha = 0.85f)

    val onColor = if (color == MaterialTheme.colorScheme.surfaceVariant)
        MaterialTheme.colorScheme.onSurface else Color.White

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(color, RoundedCornerShape(8.dp))
            .border(
                width = if (today) 2.dp else 0.dp,
                color = if (today) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
    ) {
        // Day number — the primary label, centred.
        Text(
            day.dayOfMonth.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = onColor,
            modifier = Modifier.align(Alignment.Center)
        )
        // Aligner number — a small badge so it can't be mistaken for the date.
        if (trayLabel != null) {
            Text(
                "T$trayLabel",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .background(Color.Black.copy(alpha = 0.28f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            )
        }
        Row(Modifier.align(Alignment.BottomStart).padding(3.dp)) {
            if (hasAppointment) {
                Box(Modifier.size(4.dp).background(Color.White, CircleShape))
            }
            if (hasNote) {
                Box(
                    Modifier
                        .padding(start = 2.dp)
                        .size(4.dp)
                        .background(Color(0xFF29B6F6), CircleShape)
                )
            }
        }
    }
}

/** Day editor (FR-CAL-02): retro-logging, notes, session list. */
/** Read-only list of everything recorded for a selected day. */
@Composable
fun DayEventsScreen(epochDay: Long, onBack: () -> Unit, navController: NavHostController) {
    val zone = ZoneId.systemDefault()
    val day = LocalDate.ofEpochDay(epochDay)
    val sessions by Graph.timerRepository.observeSessions().collectAsState(initial = emptyList())
    val appointments by Graph.appointmentRepository.observeAppointments().collectAsState(initial = emptyList())
    val trays by Graph.treatmentRepository.observeTrays().collectAsState(initial = emptyList())
    val settings by Graph.settingsStore.settings.collectAsState(initial = null)
    var note by remember { mutableStateOf<String?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }

    LaunchedEffect(epochDay) {
        note = Graph.appointmentRepository.noteForDay(epochDay)?.note
    }

    val resetMinutes = settings?.dayResetMinutes ?: 0
    val windowStart = DayMath.dayStart(day, resetMinutes, zone)
    val windowEnd = windowStart + DayMath.MILLIS_PER_DAY
    val now = System.currentTimeMillis()
    val daySessions = sessions
        .filter { it.startEpoch < windowEnd && (it.endEpoch ?: now) > windowStart }
        .sortedBy { it.startEpoch }
    val dayAppointments = appointments
        .filter { DayMath.dateAt(it.startEpoch, zone) == day }
        .sortedBy { it.startEpoch }
    val sortedTrays = com.traytimer.core.logic.PlanEngine.sorted(trays)
    val daySwitches = sortedTrays.mapIndexedNotNull { index, tray ->
        val at = tray.actualStartEpoch ?: return@mapIndexedNotNull null
        if (DayMath.dateAt(at, zone) != day) null else Triple(index, tray, at)
    }.sortedBy { it.third }
    val inMillis = DayMath.inMillisForDay(epochDay, sessions, resetMinutes, zone)

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
            }
            Text(day.toString(), style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "In time: ${DayMath.formatHm(inMillis)}",
            style = MaterialTheme.typography.headlineLarge
        )
        Spacer(Modifier.height(12.dp))

        SectionCard {
            Text("Out / in events", style = MaterialTheme.typography.titleMedium)
            if (daySessions.isEmpty()) {
                Text(
                    "No out/in events on this day.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            daySessions.forEach { session ->
                val startText = clock(session.startEpoch, zone)
                val endText = session.endEpoch?.let { clock(it, zone) } ?: "now"
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text("Out $startText  →  In $endText", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (session.endEpoch == null) "Currently out"
                        else DayMath.formatHm(session.endEpoch - session.startEpoch),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        SectionCard {
            Text("Appointments", style = MaterialTheme.typography.titleMedium)
            if (dayAppointments.isEmpty()) {
                Text(
                    "No appointments on this day.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            dayAppointments.forEach { appointment ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(appointment.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        clock(appointment.startEpoch, zone),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        SectionCard {
            Text("Aligner switches", style = MaterialTheme.typography.titleMedium)
            if (daySwitches.isEmpty()) {
                Text(
                    "No aligner switches on this day.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            daySwitches.forEach { (index, tray, at) ->
                val prev = sortedTrays.getOrNull(index - 1)
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        "Aligner ${tray.label} became active at ${clock(at, zone)}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (prev != null) {
                        Text(
                            "Aligner ${prev.label} is no longer active",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        note?.let { text ->
            Spacer(Modifier.height(12.dp))
            SectionCard {
                Text("Day note", style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Use + to add an event, note or appointment.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(96.dp))
        }

        ExtendedFloatingActionButton(
            onClick = { showAddMenu = true },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("Add") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        )
    }

    if (showAddMenu) {
        AlertDialog(
            onDismissRequest = { showAddMenu = false },
            title = { Text("Add to $day") },
            text = {
                Column {
                    TextButton(onClick = {
                        showAddMenu = false
                        navController.navigate("add-event/${day.toEpochDay()}")
                    }) { Text("Add out/in event") }
                    TextButton(onClick = {
                        showAddMenu = false
                        navController.navigate("add-note/${day.toEpochDay()}")
                    }) { Text("Day note") }
                    TextButton(onClick = {
                        showAddMenu = false
                        navController.navigate("appointment/-1")
                    }) { Text("Orthodontic appointment") }
                    TextButton(onClick = {
                        showAddMenu = false
                        navController.navigate("log-switch/${day.toEpochDay()}")
                    }) { Text("Log aligner switch") }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddMenu = false }) { Text("Cancel") }
            }
        )
    }
}

/** Add a trays-out event for any day (reached from the calendar "+"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEventScreen(initialEpochDay: Long, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    var selectedDay by remember { mutableStateOf(LocalDate.ofEpochDay(initialEpochDay)) }
    val now = System.currentTimeMillis()
    val isToday = selectedDay == LocalDate.now(zone)
    val defaultIn = if (isToday) java.time.LocalTime.now(zone) else java.time.LocalTime.of(13, 0)
    val defaultOut = defaultIn.minusHours(1)

    var outHour by remember(selectedDay) { mutableStateOf(defaultOut.hour) }
    var outMinute by remember(selectedDay) { mutableStateOf(defaultOut.minute) }
    var inHour by remember(selectedDay) { mutableStateOf(defaultIn.hour) }
    var inMinute by remember(selectedDay) { mutableStateOf(defaultIn.minute) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showOutPicker by remember { mutableStateOf(false) }
    var showInPicker by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val sessions by Graph.timerRepository.observeSessions().collectAsState(initial = emptyList())
    val settings by Graph.settingsStore.settings.collectAsState(initial = null)
    val resetMinutes = settings?.dayResetMinutes ?: 0
    val epochDay = selectedDay.toEpochDay()
    val windowStart = DayMath.dayStart(selectedDay, resetMinutes, zone)
    val windowEnd = windowStart + DayMath.MILLIS_PER_DAY
    val daySessions = sessions
        .filter { it.startEpoch < windowEnd && (it.endEpoch ?: now) > windowStart }
        .sortedBy { it.startEpoch }

    val eventStart = selectedDay.atTime(outHour, outMinute).atZone(zone).toInstant().toEpochMilli()
    var eventEnd = selectedDay.atTime(inHour, inMinute).atZone(zone).toInstant().toEpochMilli()
    val crossesMidnight = eventEnd <= eventStart
    if (crossesMidnight) eventEnd += DayMath.MILLIS_PER_DAY
    val validation = when {
        eventEnd > now -> "The in time can't be in the future"
        else -> null
    }

    Column(
        Modifier
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
            }
            Text("Add out/in event", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(12.dp))

        SectionCard {
            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Date: $selectedDay") }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { showOutPicker = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Out time: %02d:%02d".format(outHour, outMinute)) }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { showInPicker = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("In time: %02d:%02d".format(inHour, inMinute)) }
            if (crossesMidnight) {
                Text(
                    "In time is on the next day",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            validation?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        Graph.timerRepository.addOutSession(eventStart, eventEnd)
                        com.traytimer.reminders.AlarmScheduler.rescheduleAll(context)
                        message = "Event added"
                    }
                },
                enabled = validation == null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Add event") }
            message?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(12.dp))

        SectionCard {
            Text("Events on $selectedDay", style = MaterialTheme.typography.titleMedium)
            if (daySessions.isEmpty()) {
                Text(
                    "No out/in events yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            daySessions.forEach { session ->
                val startText = clock(session.startEpoch, zone)
                val endText = session.endEpoch?.let { clock(it, zone) } ?: "now"
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text("Out $startText  →  In $endText", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (session.endEpoch == null) "Currently out"
                        else DayMath.formatHm(session.endEpoch - session.startEpoch),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = selectedDay.atStartOfDay(zone).toInstant().toEpochMilli()
        )
        AlertDialog(
            onDismissRequest = { showDatePicker = false },
            title = { Text("Pick date") },
            text = { DatePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        selectedDay = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                        message = null
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        )
    }
    if (showOutPicker) {
        val state = rememberTimePickerState(outHour, outMinute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showOutPicker = false },
            title = { Text("Out time") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    outHour = state.hour; outMinute = state.minute; showOutPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showOutPicker = false }) { Text("Cancel") } }
        )
    }
    if (showInPicker) {
        val state = rememberTimePickerState(inHour, inMinute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showInPicker = false },
            title = { Text("In time") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    inHour = state.hour; inMinute = state.minute; showInPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showInPicker = false }) { Text("Cancel") } }
        )
    }
}

/** Add or replace the note for any day (reached from the calendar "+"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNoteScreen(initialEpochDay: Long, onBack: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    var selectedDay by remember { mutableStateOf(LocalDate.ofEpochDay(initialEpochDay)) }
    var note by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(selectedDay) {
        note = Graph.appointmentRepository.noteForDay(selectedDay.toEpochDay())?.note ?: ""
    }

    Column(
        Modifier
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
            }
            Text("Day note", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(12.dp))
        SectionCard {
            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Date: $selectedDay") }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Day note (pain level, rubber bands…)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                scope.launch {
                    Graph.appointmentRepository.saveNote(selectedDay.toEpochDay(), note)
                    message = "Note saved"
                }
            }) { Text("Save note") }
            message?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = selectedDay.atStartOfDay(zone).toInstant().toEpochMilli()
        )
        AlertDialog(
            onDismissRequest = { showDatePicker = false },
            title = { Text("Pick date") },
            text = { DatePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        selectedDay = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                        message = null
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        )
    }
}

private fun clock(epoch: Long, zone: ZoneId): String {
    val t = Instant.ofEpochMilli(epoch).atZone(zone).toLocalTime()
    return "%02d:%02d".format(t.hour, t.minute)
}

/** Appointment editor (FR-CAL-05): title, date, time, reminder offsets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppointmentEditorScreen(appointmentId: Long, onBack: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var loaded by remember { mutableStateOf<Appointment?>(null) }
    var title by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var hour by remember { mutableStateOf(10) }
    var minute by remember { mutableStateOf(0) }
    var remindDayBefore by remember { mutableStateOf(false) }
    var remindHourBefore by remember { mutableStateOf(true) }
    var remindHoursBefore by remember { mutableStateOf("") }
    var remindMinutesBefore by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(appointmentId) {
        if (appointmentId >= 0) {
            Graph.appointmentRepository.getById(appointmentId)?.let { a ->
                loaded = a
                title = a.title
                val zdt = Instant.ofEpochMilli(a.startEpoch).atZone(ZoneId.systemDefault())
                date = zdt.toLocalDate()
                hour = zdt.hour
                minute = zdt.minute
                remindDayBefore = a.remindDayBefore
                remindHourBefore = a.remindHourBefore
                remindHoursBefore = a.remindHoursBefore?.toString() ?: ""
                remindMinutesBefore = a.remindMinutesBefore?.toString() ?: ""
            }
        }
    }

    Column(Modifier.systemBarsPadding().padding(16.dp)) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
        }
        Text("Orthodontic appointment", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showDatePicker = true }) {
            Text("Date: $date")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showTimePicker = true }) {
            Text("Start time: %02d:%02d".format(hour, minute))
        }
        Spacer(Modifier.height(12.dp))

        SectionCard {
            Text("Remind me", style = MaterialTheme.typography.titleMedium)
            SettingRow("1 day before", checked = remindDayBefore, onCheckedChange = { remindDayBefore = it })
            SettingRow("1 hour before", checked = remindHourBefore, onCheckedChange = { remindHourBefore = it })
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = remindHoursBefore,
                    onValueChange = { remindHoursBefore = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text("X hours before") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = remindMinutesBefore,
                    onValueChange = { remindMinutesBefore = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("X minutes before") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        Row {
            OutlinedButton(onClick = {
                scope.launch {
                    val start = date.atTime(hour, minute)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val appt = Appointment(
                        id = loaded?.id ?: 0,
                        title = title.ifBlank { "Orthodontist appointment" },
                        startEpoch = start,
                        remindDayBefore = remindDayBefore,
                        remindHourBefore = remindHourBefore,
                        remindHoursBefore = remindHoursBefore.toIntOrNull(),
                        remindMinutesBefore = remindMinutesBefore.toIntOrNull()
                    )
                    if (loaded == null) Graph.appointmentRepository.add(appt)
                    else Graph.appointmentRepository.update(appt)
                    com.traytimer.reminders.AlarmScheduler.rescheduleAll(context)
                    onBack()
                }
            }) { Text("Save") }
            if (loaded != null) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete appointment?",
            text = "Are you sure you want to delete this appointment permanently?",
            confirmLabel = "Delete",
            danger = true,
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    loaded?.let { Graph.appointmentRepository.delete(it) }
                    loaded?.let { com.traytimer.reminders.AlarmScheduler.cancelAppointment(context, it.id) }
                    onBack()
                }
            },
            onDismiss = { confirmDelete = false }
        )
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        AlertDialog(
            onDismissRequest = { showDatePicker = false },
            title = { Text("Pick date") },
            text = { DatePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        )
    }
    if (showTimePicker) {
        val state = rememberTimePickerState(hour, minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Start time") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    hour = state.hour; minute = state.minute; showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } }
        )
    }
}