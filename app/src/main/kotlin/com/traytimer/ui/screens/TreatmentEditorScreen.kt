package com.traytimer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.traytimer.core.Graph
import com.traytimer.core.logic.Analytics
import com.traytimer.core.logic.DayMath
import com.traytimer.core.logic.PlanEngine
import com.traytimer.data.db.PlanTray
import com.traytimer.data.prefs.Keys
import com.traytimer.reminders.AlarmScheduler
import com.traytimer.ui.MainViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Treatment plan editor (FR-TRT-01..11): phases, per-tray durations with range
 * application, switch logging with off-schedule confirmation, auto-adjust (Plus).
 */
@Composable
fun TreatmentEditorScreen(onBack: () -> Unit) {
    val vm: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trays by vm.trays.collectAsState()
    val settings by vm.settings.collectAsState()

    var editingTray by remember { mutableStateOf<PlanTray?>(null) }
    var switchTarget by remember { mutableStateOf<Int?>(null) }
    var switchDialogKind by remember { mutableStateOf<PlanEngine.SwitchKind?>(null) }
    var confirmDeletePlan by remember { mutableStateOf(false) }
    var showAddPhase by remember { mutableStateOf(false) }
    var showRebuild by remember { mutableStateOf(false) }

    val zone = remember(settings) { vm.zone(settings) }
    val sorted = remember(trays) { PlanEngine.sorted(trays) }
    val today = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    val currentIdx = PlanEngine.currentIndex(sorted, today)

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
            }
            Text("Treatment plan", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { confirmDeletePlan = true }) {
                Icon(Icons.Default.Delete, contentDescription = "delete plan", tint = MaterialTheme.colorScheme.error)
            }
        }

        if (sorted.isEmpty()) {
            BoxEmptyPlan()
            return@Column
        }

        // Summary (FR-TRT-02)
        val completed = sorted.count { it.completed }
        SectionCard(Modifier.padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Completed: $completed", style = MaterialTheme.typography.titleMedium)
                Text("Remaining: ${sorted.size - completed - 1}", style = MaterialTheme.typography.titleMedium)
                Text("Total: ${sorted.size}", style = MaterialTheme.typography.titleMedium)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { showRebuild = true }) { Text("Rebuild plan…") }
        }

        // Auto-adjust toggle (FR-TRT-07, Plus)
        SettingRow(
            title = "Automatic Treatment Adjustment",
            subtitle = "When you switch trays early or late, your whole plan recalculates automatically.",
            checked = settings?.autoAdjustEnabled ?: false,
            onCheckedChange = { enabled ->
                vm.updateSettings { s, p -> p[Keys.AUTO_ADJUST] = enabled }
            }
        )

        OutlinedButton(
            onClick = {
                val next = currentIdx + 1
                if (next < sorted.size) {
                    switchTarget = next
                    switchDialogKind = PlanEngine.classifySwitch(sorted, next, System.currentTimeMillis(), zone)
                }
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
        ) { Text("Change tray now") }

        LazyColumn(Modifier.weight(1f)) {
            val phases = PlanEngine.phases(sorted)
            phases.forEach { phase ->
                items(phase, key = { it.id }) { tray ->
                    TrayRow(
                        tray = tray,
                        isCurrent = tray.orderIndex == currentIdx,
                        plannedStart = PlanEngine.effectiveStart(tray),
                        plannedEnd = PlanEngine.effectiveEnd(tray),
                        today = today,
                        onTap = { editingTray = tray },
                        onSwitchHere = {
                            val kind = PlanEngine.classifySwitch(sorted, tray.orderIndex, System.currentTimeMillis(), zone)
                            switchTarget = tray.orderIndex
                            switchDialogKind = kind
                        }
                    )
                }
                val phaseTitle = PlanEngine.phaseTitle(phase.first().phaseIndex, phase.first().phaseType)
                item(key = "phase_$phaseTitle") {
                    Text(
                        phaseTitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showAddPhase = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(" Refinement")
            }
            OutlinedButton(onClick = {
                scope.launch {
                    Graph.treatmentRepository.addRetainer(2, 90)
                    Analytics.log("retainer_added")
                }
            }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(" Retainer")
            }
        }
    }

    // ---- Tray duration editor (FR-TRT-04) ----
    editingTray?.let { tray ->
        EditTrayDurationDialog(
            tray = tray,
            totalTrays = sorted.size,
            onApply = { newDays, applyUntil ->
                scope.launch {
                    // Plan edits always recalculate chained start dates (FR-TRT-02/04);
                    // ATA only governs automatic recalculation on early/late switches.
                    Graph.treatmentRepository.changeDuration(
                        tray.orderIndex, newDays, applyUntil,
                        autoAdjust = true
                    )
                    Analytics.log("tray_duration_changed")
                }
                editingTray = null
            },
            onDelete = {
                scope.launch {
                    Graph.treatmentRepository.deleteTray(tray)
                    Analytics.log("tray_deleted")
                }
                editingTray = null
            },
            onCancel = { editingTray = null }
        )
    }

    // ---- Off-schedule switch confirmation (FR-TRT-06) ----
    val target = switchTarget
    val kind = switchDialogKind
    if (target != null && kind != null) {
        val message = when (kind) {
            is PlanEngine.SwitchKind.Early -> "You're skipping ahead of your current plan. You're changing earlier than planned."
            is PlanEngine.SwitchKind.Late -> "You're changing later than planned."
            is PlanEngine.SwitchKind.Backward -> "You're going back to a previous aligner."
            is PlanEngine.SwitchKind.NearBoundary -> "You're only a few hours ahead of the planned date. Tap if you want to bring treatment forward."
            is PlanEngine.SwitchKind.OnSchedule -> "Switch to aligner ${sorted.getOrNull(target)?.label}?"
        }
        AlertDialog(
            onDismissRequest = { switchTarget = null; switchDialogKind = null },
            title = { Text("Change tray") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        Graph.treatmentRepository.logSwitch(
                            target, System.currentTimeMillis(), settings?.autoAdjustEnabled ?: false
                        )
                        Analytics.log("tray_switch_confirmed", mapOf("kind" to kind.javaClass.simpleName))
                        AlarmScheduler.rescheduleAll(context)
                        switchTarget = null; switchDialogKind = null
                    }
                }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { switchTarget = null; switchDialogKind = null }) { Text("Cancel") }
            }
        )
    }

    // ---- Rebuild plan anchored to the current tray (repairs mis-anchored plans) ----
    if (showRebuild) {
        var totalText by remember {
            mutableStateOf((if ((settings?.totalAligners ?: 0) > 0) settings!!.totalAligners else sorted.size).toString())
        }
        var currentText by remember { mutableStateOf(((currentIdx + 1).takeIf { it > 0 } ?: 1).toString()) }
        var daysText by remember {
            mutableStateOf((settings?.daysPerAligner?.takeIf { it > 0 } ?: 7).toString())
        }
        AlertDialog(
            onDismissRequest = { showRebuild = false },
            title = { Text("Rebuild plan") },
            text = {
                Column {
                    Text(
                        "Anchors the current aligner to today and recalculates the schedule. " +
                            "Wear time, notes and appointments are kept.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = totalText,
                        onValueChange = { totalText = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("Total aligners") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = currentText,
                        onValueChange = { currentText = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("Current aligner") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = daysText,
                        onValueChange = { daysText = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Days per aligner") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val total = totalText.toIntOrNull()
                    val current = currentText.toIntOrNull()
                    val days = daysText.toIntOrNull()
                    if (total != null && total > 0 && current != null && current in 1..total &&
                        days != null && days in 1..60
                    ) {
                        scope.launch {
                            val todayMidnight = LocalDate.now(zone).atStartOfDay(zone)
                                .toInstant().toEpochMilli()
                            Graph.treatmentRepository.createPlan(total, current, days, todayMidnight)
                            Graph.settingsStore.updateAwait { st, p ->
                                p[Keys.TOTAL_ALIGNERS] = total
                                p[Keys.DAYS_PER] = days
                            }
                            AlarmScheduler.rescheduleAll(context)
                            Analytics.log("plan_rebuilt", mapOf("current" to current, "total" to total))
                        }
                        showRebuild = false
                    }
                }) { Text("Rebuild") }
            },
            dismissButton = { TextButton(onClick = { showRebuild = false }) { Text("Cancel") } }
        )
    }

    // ---- Add refinement (FR-TRT-01) ----
    if (showAddPhase) {
        var count by remember { mutableStateOf("5") }
        var days by remember { mutableStateOf("7") }
        AlertDialog(
            onDismissRequest = { showAddPhase = false },
            title = { Text("Add refinement") },
            text = {
                Column {
                    OutlinedTextField(
                        value = count,
                        onValueChange = { count = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Number of aligners") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = days,
                        onValueChange = { days = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Days per aligner") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        Graph.treatmentRepository.addRefinement(
                            (count.toIntOrNull() ?: 5).coerceIn(1, 30),
                            (days.toIntOrNull() ?: 7).coerceIn(1, 60)
                        )
                        Analytics.log("refinement_added")
                    }
                    showAddPhase = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddPhase = false }) { Text("Cancel") } }
        )
    }

    // ---- Delete plan confirm (FR-TRT-10) ----
    if (confirmDeletePlan) {
        ConfirmDialog(
            title = "Delete treatment plan?",
            text = "Are you sure you want to delete this aligner switch permanently? Wear time, notes, and appointments are kept. Your previous treatment stays in your history.",
            confirmLabel = "Delete",
            danger = true,
            onConfirm = {
                scope.launch {
                    Graph.treatmentRepository.deletePlan()
                    AlarmScheduler.rescheduleAll(context)
                }
                confirmDeletePlan = false
            },
            onDismiss = { confirmDeletePlan = false }
        )
    }

}

@Composable
private fun BoxEmptyPlan() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("No treatment plan yet.", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Complete onboarding or add aligners to start scheduling tray changes.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun TrayRow(
    tray: PlanTray,
    isCurrent: Boolean,
    plannedStart: Long,
    plannedEnd: Long,
    today: Long,
    onTap: () -> Unit,
    onSwitchHere: () -> Unit
) {
    val fmt = DateTimeFormatter.ofPattern("MMM d, yyyy")
    val startLabel = if (tray.actualStartEpoch != null) {
        val full = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' HH:mm")
        "Started " + Instant.ofEpochMilli(plannedStart).atZone(ZoneId.systemDefault()).format(full)
    } else {
        "Planned " + Instant.ofEpochMilli(plannedStart).atZone(ZoneId.systemDefault())
            .toLocalDate().format(fmt)
    }
    val dayNum = ((today - plannedStart) / DayMath.MILLIS_PER_DAY).toInt() + 1
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            tray.label,
            style = MaterialTheme.typography.titleLarge,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (isCurrent) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "$startLabel · ${tray.plannedDays} days",
                style = MaterialTheme.typography.bodyMedium
            )
            if (isCurrent) {
                Text(
                    "day ${dayNum.coerceAtLeast(1)} of ${tray.plannedDays} · tap the aligner number to adjust",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
        when {
            isCurrent -> AssistChip(onClick = onSwitchHere, label = { Text("Current") })
            tray.completed -> AssistChip(onClick = onSwitchHere, label = { Text("Done") })
            else -> AssistChip(onClick = onSwitchHere, label = { Text("Upcoming") })
        }
    }
}

@Composable
private fun EditTrayDurationDialog(
    tray: PlanTray,
    totalTrays: Int,
    onApply: (Int, Int?) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    var daysText by remember { mutableStateOf(tray.plannedDays.toString()) }
    var applyRange by remember { mutableStateOf(false) }
    var rangeEnd by remember { mutableStateOf((totalTrays).toString()) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Aligner ${tray.label}") },
        text = {
            Column {
                OutlinedTextField(
                    value = daysText,
                    onValueChange = { daysText = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text("Days worn") },
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = applyRange,
                        onCheckedChange = { applyRange = it }
                    )
                    Text("Apply to subsequent aligners", style = MaterialTheme.typography.bodyLarge)
                }
                if (applyRange) {
                    OutlinedTextField(
                        value = rangeEnd,
                        onValueChange = { rangeEnd = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("Apply from ${tray.label} to aligner…") },
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val days = daysText.toIntOrNull() ?: tray.plannedDays
                onApply(days, if (applyRange) rangeEnd.toIntOrNull()?.minus(1) else null)
            }) { Text("Apply") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    )
}