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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.traytimer.core.logic.Analytics
import com.traytimer.core.logic.DayMath
import com.traytimer.core.logic.PlanEngine
import com.traytimer.core.logic.StatsCalc
import com.traytimer.data.db.PlanTray
import com.traytimer.ui.MainViewModel

/** Stats tab (FR-STS-01..06): averages, per-aligner totals, goal attainment, share. */
@Composable
fun StatsScreen(vm: MainViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val trays by vm.trays.collectAsState()
    val sessions by vm.sessions.collectAsState()

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
        Text("Average in time", style = MaterialTheme.typography.titleLarge)
        Text(
            "Per day, across the days you've tracked",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AverageCard("Last 7 days", avg7, Modifier.weight(1f))
            AverageCard("Last 30 days", avg30, Modifier.weight(1f))
            AverageCard("Last 90 days", avg90, Modifier.weight(1f))
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
        Text(
            "Most recent aligner first",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(8.dp))
        val zone = remember(settings) { vm.zone(settings) }
        val goalHours = settings?.wearGoalHours ?: 22
        val sortedTrays = remember(trays) { PlanEngine.sorted(trays) }
        val todayMidnight = remember(zone) {
            java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        }
        val currentIdx = remember(sortedTrays, todayMidnight) {
            PlanEngine.currentIndex(sortedTrays, todayMidnight)
        }
        val wearByTray = remember(sessions, sortedTrays, settings) {
            StatsCalc.wearPerTray(
                sessions, sortedTrays, settings?.dayResetMinutes ?: 0
            )
        }
        val startedTrays = remember(sortedTrays, currentIdx) {
            sortedTrays.take((currentIdx + 1).coerceIn(0, sortedTrays.size)).reversed()
        }
        if (startedTrays.isEmpty()) {
            Text(
                "No aligners have been started yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        startedTrays.forEach { tray ->
            TrayWearRow(
                tray = tray,
                wornMillis = wearByTray[tray.orderIndex] ?: 0L,
                goalHours = goalHours
            )
        }
        Spacer(Modifier.height(16.dp))

        OutlinedButton(onClick = {
            val body = buildString {
                appendLine("TrayTimer wear stats")
                appendLine("7-day avg: ${DayMath.formatHm(avg7.averageMillis)} (${avg7.daysMetGoal}/${avg7.daysCounted} days met goal)")
                appendLine("30-day avg: ${DayMath.formatHm(avg30.averageMillis)} (${avg30.daysMetGoal}/${avg30.daysCounted} days met goal)")
                appendLine("90-day avg: ${DayMath.formatHm(avg90.averageMillis)} (${avg90.daysMetGoal}/${avg90.daysCounted} days met goal)")
                appendLine("Current streak: $streak days")
                appendLine()
                appendLine("Wear by aligner (most recent first):")
                startedTrays.forEach { tray ->
                    appendLine(
                        "Aligner ${tray.label}: " +
                            DayMath.formatHm(wearByTray[tray.orderIndex] ?: 0L)
                    )
                }
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
        Text(DayMath.formatHm(avg.averageMillis), style = MaterialTheme.typography.headlineSmall)
        Text(
            "${avg.daysMetGoal}/${avg.daysCounted} met",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun TrayWearRow(tray: PlanTray, wornMillis: Long, goalHours: Int) {
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
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
        )
    }
}