package com.traytimer.ui.screens

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.traytimer.core.Graph
import com.traytimer.core.logic.Analytics
import com.traytimer.core.services.BrandTemplate
import com.traytimer.reminders.AlarmScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Onboarding wizard (FR-ONB-01..09): welcome -> tutorial offer -> goal affirmations ->
 * treatment setup -> building feedback -> tray-change reminder -> Timer tab.
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Wizard inputs
    var brand by remember { mutableStateOf<BrandTemplate?>(null) }
    var totalAligners by remember { mutableStateOf("") }
    var currentAligner by remember { mutableStateOf("") }
    var daysPer by remember { mutableStateOf("7") }
    var goalHours by remember { mutableStateOf(22) }
    var startDate by remember { mutableStateOf(java.time.LocalDate.now()) }
    var trayChangeTime by remember { mutableStateOf(9 * 60) }

    // Affirmations
    val goals = listOf(
        "Finish on time" to Icons.Default.Schedule,
        "Protect the investment" to Icons.Default.Shield,
        "See progress" to Icons.Default.Favorite,
        "Don't time out" to Icons.Default.Timer,
        "Hit wear hours" to Icons.Default.Timelapse
    )
    val selectedGoals = remember { mutableStateOf(setOf<Int>()) }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (step) {
            0 -> Welcome { step = 1 }
            1 -> TutorialOffer(onWatch = { step = 2 }, onSkip = { step = 2 })
            2 -> GoalAffirmations(goals, selectedGoals.value, { i, checked ->
                selectedGoals.value = if (checked) selectedGoals.value + i else selectedGoals.value - i
            }) { step = 3 }
            3 -> BrandPicker(onPick = { picked ->
                brand = picked
                picked.defaultAligners?.let { totalAligners = it.toString() }
                picked.daysPerAligner?.let { daysPer = it.toString() }
                step = 4
            })
            4 -> WizardNumbers(
                totalAligners = totalAligners,
                onTotal = { totalAligners = it },
                currentAligner = currentAligner,
                onCurrent = { currentAligner = it },
                onValid = {
                    step = 5
                },
                onBack = { step = 3 }
            )
            5 -> WizardDays(daysPer, goalHours, startDate,
                onDays = { daysPer = it },
                onGoal = { goalHours = it },
                onDate = { startDate = it },
                onValid = { step = 6 },
                onBack = { step = 4 }
            )
            6 -> BuildingPlan {
                scope.launch {
                    // Persist plan + settings (FR-ONB-04/06)
                    val total = totalAligners.toIntOrNull() ?: 1
                    val current = (currentAligner.toIntOrNull() ?: 1).coerceIn(1, total)
                    val days = (daysPer.toIntOrNull() ?: 7).coerceIn(1, 60)
                    val start = startDate.atStartOfDay(java.time.ZoneId.systemDefault())
                        .toInstant().toEpochMilli()
                    Graph.treatmentRepository.createPlan(total, current, days, start)
                    Graph.settingsStore.update { s, p ->
                        p[com.traytimer.data.prefs.Keys.BRAND_NAME] = brand?.name ?: "Other"
                        p[com.traytimer.data.prefs.Keys.TOTAL_ALIGNERS] = total
                        p[com.traytimer.data.prefs.Keys.DAYS_PER] = days
                        p[com.traytimer.data.prefs.Keys.WEAR_GOAL_HOURS] = goalHours
                        p[com.traytimer.data.prefs.Keys.GREEN_THRESHOLD] = goalHours
                        p[com.traytimer.data.prefs.Keys.TRAY_CHANGE_TIME] = trayChangeTime
                    }
                    AlarmScheduler.rescheduleAll(context)
                    step = 7
                }
            }
            7 -> TrayChangeReminderPicker(trayChangeTime, onPick = {
                trayChangeTime = it
                scope.launch {
                    Graph.settingsStore.updateAwait { s, p ->
                        p[com.traytimer.data.prefs.Keys.TRAY_CHANGE_TIME] = it
                        p[com.traytimer.data.prefs.Keys.ONBOARDING_COMPLETE] = true
                        p[com.traytimer.data.prefs.Keys.NOTIF_PRIMING_DONE] = false
                    }
                    AlarmScheduler.rescheduleAll(context)
                    Analytics.log("onboarding_complete")
                    onComplete()
                }
            })
        }
    }
}

@Composable
private fun Welcome(next: () -> Unit) {
    Spacer(Modifier.height(48.dp))
    Text("🦷", style = MaterialTheme.typography.displayLarge)
    Spacer(Modifier.height(16.dp))
    Text("Welcome to TrayTimer", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(
        "Wear time is the #1 predictor of treatment success. TrayTimer keeps you on track for a perfect finish.",
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge
    )
    Spacer(Modifier.height(8.dp))
    Spacer(Modifier.height(8.dp))
    Spacer(Modifier.height(48.dp))
    Button(onClick = next, modifier = Modifier.fillMaxWidth()) { Text("Get started") }
}

/** FR-ONB-02: tutorial offer — video captions noted; a text walkthrough substitutes here. */
@Composable
private fun TutorialOffer(onWatch: () -> Unit, onSkip: () -> Unit) {
    Spacer(Modifier.height(48.dp))
    Icon(Icons.Default.School, contentDescription = null, modifier = Modifier.height(80.dp))
    Spacer(Modifier.height(16.dp))
    Text("Learn how to use the app", style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(8.dp))
    Text(
        "Would you like to watch a short video to learn how to use the app?",
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge
    )
    Spacer(Modifier.height(24.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text("Quick tour:", style = MaterialTheme.typography.titleMedium)
            Text("• Tap the big circle when you take your aligners out")
            Text("• Tap again when they're back in")
            Text("• The ring shows your daily wear goal progress")
            Text("• Reminders make sure you never overdraw your break time")
        }
    }
    Spacer(Modifier.height(24.dp))
    Button(onClick = onWatch, modifier = Modifier.fillMaxWidth()) { Text("Got it — continue") }
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Skip") }
}

/** FR-ONB-03: goal affirmations. */
@Composable
private fun GoalAffirmations(
    goals: List<Pair<String, ImageVector>>,
    selected: Set<Int>,
    onToggle: (Int, Boolean) -> Unit,
    onNext: () -> Unit
) {
    Spacer(Modifier.height(32.dp))
    Text("Why are you here?", style = MaterialTheme.typography.headlineLarge)
    Text("Pick everything that matters to you", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    Spacer(Modifier.height(24.dp))
    goals.forEachIndexed { i, (label, icon) ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Checkbox(checked = selected.contains(i), onCheckedChange = { onToggle(i, it) })
        }
    }
    Spacer(Modifier.height(24.dp))
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Next") }
}

/** FR-ONB-04 (brand templates from brands.json with bundled fallback). */
@Composable
private fun BrandPicker(onPick: (BrandTemplate) -> Unit) {
    var brands by remember { mutableStateOf<List<BrandTemplate>>(emptyList()) }
    LaunchedEffect(Unit) {
        brands = Graph.brandsRepository.getBrands()
    }
    Spacer(Modifier.height(32.dp))
    Text("Let's set up your treatment", style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(8.dp))
    Text("Which aligner brand are you using?", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(16.dp))
    brands.forEach { b ->
        FilterChip(
            selected = false,
            onClick = { onPick(b) },
            label = { Text(b.name) },
            modifier = Modifier.padding(vertical = 2.dp)
        )
    }
}

@Composable
private fun WizardNumbers(
    totalAligners: String,
    onTotal: (String) -> Unit,
    currentAligner: String,
    onCurrent: (String) -> Unit,
    onValid: () -> Unit,
    onBack: () -> Unit
) {
    Spacer(Modifier.height(32.dp))
    Text("How many aligners are in your treatment?", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = totalAligners,
        onValueChange = { onTotal(it.filter { c -> c.isDigit() }.take(3)) },
        label = { Text("Total aligners") },
        singleLine = true
    )
    Spacer(Modifier.height(16.dp))
    Text("Which aligner are you on right now?", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = currentAligner,
        onValueChange = { onCurrent(it.filter { c -> c.isDigit() }.take(3)) },
        label = { Text("Current aligner number") },
        singleLine = true
    )
    Spacer(Modifier.height(24.dp))
    val total = totalAligners.toIntOrNull()
    val current = currentAligner.toIntOrNull()
    val valid = total != null && total > 0 && current != null && current in 1..total
    Button(onClick = onValid, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text("Next") }
    TextButton(onClick = onBack) { Text("Back") }
    if (!valid) {
        Text(
            "Current aligner can't be greater than the total",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun WizardDays(
    daysPer: String,
    goalHours: Int,
    startDate: java.time.LocalDate,
    onDays: (String) -> Unit,
    onGoal: (Int) -> Unit,
    onDate: (java.time.LocalDate) -> Unit,
    onValid: () -> Unit,
    onBack: () -> Unit
) {
    Spacer(Modifier.height(32.dp))
    Text("How many days do you wear each aligner?", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = daysPer,
        onValueChange = { onDays(it.filter { c -> c.isDigit() }.take(2)) },
        label = { Text("Days per aligner") },
        singleLine = true
    )
    Spacer(Modifier.height(16.dp))
    GoalAdjuster(
        label = "What is your daily wear goal?",
        value = goalHours,
        onChange = onGoal,
        min = 1,
        max = 23,
        hint = "Most orthodontists recommend 22 hours"
    )
    Spacer(Modifier.height(16.dp))
    Text("When did you start this aligner?", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.CalendarMonth, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        TextButton(onClick = { onDate(startDate.minusDays(1)) }) { Text("◀") }
        Text(startDate.toString(), style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = { onDate(startDate.plusDays(1)) }) { Text("▶") }
    }
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onValid,
        enabled = (daysPer.toIntOrNull() ?: 0) in 1..60,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Build my plan") }
    TextButton(onClick = onBack) { Text("Back") }
}

/** FR-ONB-05: step-by-step building feedback + social proof. */
@Composable
private fun BuildingPlan(onDone: () -> Unit) {
    val steps = listOf("Reading plan…", "Reminders…", "Schedule…", "Timeline…", "Wear goal…")
    var idx by remember { mutableStateOf(0) }
    val progress by animateFloatAsState(targetValue = (idx + 1) / steps.size.toFloat())
    LaunchedEffect(Unit) {
        steps.indices.forEach { i ->
            delay(450)
            idx = i
        }
        delay(350)
        onDone()
    }
    Spacer(Modifier.height(96.dp))
    Text("Building your plan…", style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(24.dp))
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(16.dp))
    steps.take(idx + 1).reversed().forEach {
        Text(it, style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(24.dp))
}

/** FR-ONB-06: tray-change reminder time. */
@Composable
private fun TrayChangeReminderPicker(current: Int, onPick: (Int) -> Unit) {
    Spacer(Modifier.height(32.dp))
    Text("When should we remind you to switch aligners?", style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center)
    Spacer(Modifier.height(24.dp))
    listOf(
        8 * 60 to "8:00 AM",
        9 * 60 to "9:00 AM",
        12 * 60 to "12:00 PM",
        18 * 60 to "6:00 PM",
        20 * 60 to "8:00 PM"
    ).forEach { (minutes, label) ->
        OutlinedButton(
            onClick = { onPick(minutes) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) { Text(label) }
    }
}
