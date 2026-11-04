package com.traytimer.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.traytimer.core.Graph
import com.traytimer.core.logic.Analytics
import com.traytimer.core.logic.DayMath
import com.traytimer.core.logic.PlanEngine
import com.traytimer.core.logic.StatsCalc
import com.traytimer.data.db.PlanTray
import com.traytimer.ui.MainViewModel
import kotlinx.coroutines.launch

/** Stats tab (FR-STS-01..06): averages, per-aligner totals, goal attainment, share. */
@Composable
fun StatsScreen(vm: MainViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsState()
    val trays by vm.trays.collectAsState()

    val day7 by remember { vm.dayStats(7) }.collectAsState()
    val day30 by remember { vm.dayStats(30) }.collectAsState()
    val day90 by remember { vm.dayStats(90) }.collectAsState()

    val avg7 = remember(day7) { StatsCalc.average(day7) }
    val avg30 = remember(day30) { StatsCalc.average(day30) }
    val avg90 = remember(day90) { StatsCalc.average(day90) }
    val streak = remember(day7) { StatsCalc.currentStreak(day7) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Your averages", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AverageCard("7-day Average", avg7, Modifier.weight(1f))
            AverageCard("30-day Average", avg30, Modifier.weight(1f))
            AverageCard("90-day Average", avg90, Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))
        Text("Goal attainment (7 days)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        val ratio = if (avg7.daysCounted > 0) avg7.daysMetGoal.toFloat() / avg7.daysCounted else 0f
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier.fillMaxWidth().height(10.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${avg7.daysMetGoal} of ${avg7.daysCounted} days met goal · Current streak: $streak days",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        Spacer(Modifier.height(16.dp))
        Text("Total wear time per aligner", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        val zone = remember(settings) { vm.zone(settings) }
        val sortedTrays = remember(trays) { PlanEngine.sorted(trays) }
        sortedTrays.take(12).forEach { tray ->
            TrayWearRow(vm, tray, zone, settings?.wearGoalHours ?: 22)
        }
        Spacer(Modifier.height(16.dp))

        OutlinedButton(onClick = {
            val body = buildString {
                appendLine("TrayTimer wear stats")
                appendLine("7-day avg: ${DayMath.formatHm(avg7.averageMillis)} (${avg7.daysMetGoal}/${avg7.daysCounted} days met goal)")
                appendLine("30-day avg: ${DayMath.formatHm(avg30.averageMillis)} (${avg30.daysMetGoal}/${avg30.daysCounted} days met goal)")
                appendLine("90-day avg: ${DayMath.formatHm(avg90.averageMillis)} (${avg90.daysMetGoal}/${avg90.daysCounted} days met goal)")
                appendLine("Current streak: $streak days")
            }
            Analytics.log("stats_shared")
            context.startActivity(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, body.toString())
                }.let { Intent.createChooser(it, "Share stats") }
            )
        }) { Text("Share stats with your orthodontist") }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun AverageCard(label: String, avg: StatsCalc.Averages, modifier: Modifier = Modifier) {
    SectionCard(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(DayMath.formatHm(avg.averageMillis), style = MaterialTheme.typography.headlineLarge)
        Text(
            "${avg.daysMetGoal}/${avg.daysCounted} met",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun TrayWearRow(vm: MainViewModel, tray: PlanTray, zone: java.time.ZoneId, goalHours: Int) {
    var wornMillis by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(tray.id) {
        wornMillis = Graph.treatmentRepository.let { vm.wearForCurrentTray(tray.orderIndex) }
    }
    val planned = tray.plannedDays * goalHours * 3_600_000L
    val pct = if (planned > 0) (wornMillis.toFloat() / planned).coerceIn(0f, 1f) else 0f
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Aligner ${tray.label}",
                style = MaterialTheme.typography.titleMedium
            )
            LinearProgressIndicator(
                progress = { pct },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            )
        }
        Text(
            "${DayMath.formatHm(wornMillis)} / ${tray.plannedDays * goalHours}h",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}