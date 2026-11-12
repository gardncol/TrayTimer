package com.traytimer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.traytimer.core.Graph
import com.traytimer.core.logic.Analytics
import com.traytimer.core.logic.PlanEngine
import com.traytimer.core.notifications.Notifications
import com.traytimer.liveupdates.LiveUpdateService
import com.traytimer.reminders.AlarmScheduler
import com.traytimer.widgets.WidgetRefresher
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Multi-step switch logger (FR-TRT-05): pick the date, the wall-clock time and the
 * aligner that became active. Logging pins that timestamp on the selected tray, which
 * also marks the previous tray as no longer active at the same instant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogSwitchScreen(initialEpochDay: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    val trays by Graph.treatmentRepository.observeTrays().collectAsState(initial = emptyList())
    val settings by Graph.settingsStore.settings.collectAsState(initial = null)

    val sorted = remember(trays) { PlanEngine.sorted(trays) }
    val todayMidnight = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    val currentIdx = remember(sorted) { PlanEngine.currentIndex(sorted, todayMidnight) }

    val now = java.time.ZonedDateTime.now(zone)
    var date by remember { mutableStateOf(LocalDate.ofEpochDay(initialEpochDay)) }
    var hour by remember { mutableIntStateOf(now.hour) }
    var minute by remember { mutableIntStateOf(now.minute) }
    var targetIdx by remember(sorted) {
        mutableIntStateOf(
            if (sorted.isEmpty()) 0
            else (currentIdx + 1).coerceIn(0, sorted.lastIndex)
        )
    }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showAlignerMenu by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val selected = sorted.getOrNull(targetIdx)
    val previous = sorted.getOrNull(targetIdx - 1)
    val switchEpoch = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    val future = switchEpoch > System.currentTimeMillis() + 60_000L
    val dateFmt = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
    val dateTimeText = date.atTime(hour, minute)
        .format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy 'at' HH:mm"))

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
            }
            Text("Log aligner switch", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Choose when you changed and which aligner you put in. " +
                "The previous aligner stops at the same moment.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(16.dp))

        SectionCard {
            LabeledRow("Date", date.format(dateFmt), onClick = { showDatePicker = true })
            LabeledRow(
                "Time",
                "%02d:%02d".format(hour, minute),
                onClick = { showTimePicker = true }
            )
            Box {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = sorted.isNotEmpty()) { showAlignerMenu = true }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Aligner to activate",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        selected?.let { "Aligner ${it.label}" } ?: "No plan",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                DropdownMenu(
                    expanded = showAlignerMenu,
                    onDismissRequest = { showAlignerMenu = false }
                ) {
                    sorted.forEachIndexed { idx, tray ->
                        val isCurrent = idx == currentIdx
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Aligner ${tray.label}" + if (isCurrent) " (current)" else ""
                                )
                            },
                            onClick = {
                                targetIdx = idx
                                message = null
                                showAlignerMenu = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard {
            Text("What this logs", style = MaterialTheme.typography.titleMedium)
            if (selected == null) {
                Text(
                    "No treatment plan yet. Create or rebuild a plan first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                Spacer(Modifier.height(6.dp))
                if (previous != null) {
                    Text(
                        "Aligner ${previous.label} active until $dateTimeText",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Text(
                    "Aligner ${selected.label} active from $dateTimeText",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                if (targetIdx < currentIdx) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "This sets your current aligner back to ${selected.label}. " +
                            "Later aligners will be marked as upcoming.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (future) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "The switch time can't be in the future.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            message?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (selected == null) {
                    message = "No aligner selected."
                    return@Button
                }
                if (future) {
                    message = "Choose a time that isn't in the future."
                    return@Button
                }
                scope.launch {
                    val kind = Graph.treatmentRepository.logSwitch(
                        targetIdx,
                        switchEpoch,
                        settings?.autoAdjustEnabled ?: false
                    )
                    Graph.settingsStore.updateAwait { _, p ->
                        p[com.traytimer.data.prefs.Keys.TRAY_CHANGE_DUE_AT] = 0L
                        p[com.traytimer.data.prefs.Keys.TRAY_CHANGE_DUE_FOR] = -1
                        p[com.traytimer.data.prefs.Keys.TRAY_CHANGE_SNOOZE_AT] = 0L
                    }
                    Notifications.cancel(context, Notifications.NOTIF_TRAY_CHANGE)
                    AlarmScheduler.rescheduleAll(context)
                    WidgetRefresher.pushAll(context)
                    LiveUpdateService.refresh(context, Graph.timerRepository.getOpen() == null)
                    Analytics.log(
                        "tray_switch_logged",
                        mapOf("kind" to kind.javaClass.simpleName, "aligner" to selected.label)
                    )
                    onBack()
                }
            },
            enabled = selected != null && !future,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Log switch") }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(zone).toInstant().toEpochMilli()
        )
        AlertDialog(
            onDismissRequest = { showDatePicker = false },
            title = { Text("Switch date") },
            text = { DatePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                        message = null
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
            title = { Text("Switch time") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    hour = state.hour
                    minute = state.minute
                    message = null
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } }
        )
    }
}
