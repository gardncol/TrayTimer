package com.traytimer.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.traytimer.core.Graph
import com.traytimer.core.logic.Analytics
import com.traytimer.reminders.AlarmScheduler
import com.traytimer.ui.MainViewModel
import com.traytimer.ui.nav.Routes
import com.traytimer.ui.nav.TabBus

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val settings by Graph.settingsStore.settings.collectAsState(initial = null)

    // Deep-link events (voice / shortcuts / widgets)
    LaunchedEffect(Unit) {
        TabBus.deepLinkEvent.collect { path ->
            if (path != null) {
                if (navController.currentDestination?.route?.startsWith(Routes.MAIN) != true) {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.MAIN) { inclusive = false }
                    }
                }
                TabBus.deepLinkEvent.value = null
            }
        }
    }

    val startDestination = when {
        settings == null -> Routes.MAIN // settings still loading; MainScaffold handles onboarding gate
        settings!!.onboardingComplete -> Routes.MAIN
        else -> Routes.ONBOARDING
    }

    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.MAIN) {
            if (settings?.onboardingComplete == false) {
                OnboardingScreen(
                    onComplete = {
                        navController.navigate(Routes.MAIN) { popUpTo(Routes.MAIN) { inclusive = true } }
                    }
                )
            } else {
                MainScaffold(navController)
            }
        }
        composable(Routes.CAMERA) { CameraScreen(onDone = { navController.popBackStack() }) }
        composable(Routes.DAY) { entry ->
            val epochDay = entry.arguments?.getString("epochDay")?.toLongOrNull() ?: 0L
            DayEventsScreen(epochDay = epochDay, onBack = { navController.popBackStack() })
        }
        composable(Routes.ADD_EVENT) { entry ->
            val epochDay = entry.arguments?.getString("epochDay")?.toLongOrNull()
                ?: java.time.LocalDate.now().toEpochDay()
            AddEventScreen(initialEpochDay = epochDay, onBack = { navController.popBackStack() })
        }
        composable(Routes.ADD_NOTE) { entry ->
            val epochDay = entry.arguments?.getString("epochDay")?.toLongOrNull()
                ?: java.time.LocalDate.now().toEpochDay()
            AddNoteScreen(initialEpochDay = epochDay, onBack = { navController.popBackStack() })
        }
        composable(Routes.APPOINTMENT) { entry ->
            val id = entry.arguments?.getString("id")?.toLongOrNull() ?: -1L
            AppointmentEditorScreen(appointmentId = id, onBack = { navController.popBackStack() })
        }
        composable(Routes.TREATMENT) { TreatmentEditorScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.SETTINGS) { SettingsHubScreen(navController) }
        composable(Routes.SETTINGS_TIMER) { TimerSettingsScreen(navController) }
        composable(Routes.SETTINGS_REMINDERS) { ReminderSettingsScreen(navController) }
        composable(Routes.SETTINGS_NOTIFICATIONS) { NotificationSettingsScreen(navController) }
        composable(Routes.SETTINGS_CALENDAR) { CalendarSettingsScreen(navController) }
        composable(Routes.SETTINGS_DATA) { DataSettingsScreen(navController) }
        composable(Routes.SETTINGS_LIVE) { LiveUpdatesSettingsScreen(navController) }
        composable(Routes.SETTINGS_WIDGETS) { WidgetsSettingsScreen(navController) }
        composable(Routes.SETTINGS_TILE) { TileSettingsScreen(navController) }
        composable(Routes.SETTINGS_WATCH) { WatchSettingsScreen(navController) }
        composable(Routes.SETTINGS_VOICE) { VoiceSettingsScreen(navController) }
        composable(Routes.SETTINGS_PHOTOS) { PhotoSettingsScreen(navController) }
        composable(Routes.SETTINGS_APPEARANCE) { AppearanceSettingsScreen(navController) }
        composable(Routes.SETTINGS_TIME) { TimeSettingsScreen(navController) }
        composable(Routes.SETTINGS_ADS) { AdsPrivacySettingsScreen(navController) }
        composable(Routes.SETTINGS_ABOUT) { AboutScreen(navController) }
        composable(Routes.SETTINGS_OEM) { OemGuidesScreen(navController) }
    }

    }

    // Log screen views (analytics stub)
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { entry ->
            Analytics.logScreen(entry.destination.route ?: "unknown")
        }
    }

    // Re-derive alarms whenever the app returns to the foreground surface
    LaunchedEffect(Unit) {
        AlarmScheduler.rescheduleAll(context)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(navController: NavHostController) {
    val vm: MainViewModel = viewModel()
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val selectedTab by TabBus.tab.collectAsState()

    // ---- Notification permission priming (FR-REM-17) ----
    var showNotifPriming by remember { mutableStateOf(false) }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(settings) {
        val s = settings ?: return@LaunchedEffect
        if (!s.notifPrimingDone) {
            Graph.settingsStore.update { st, p ->
                p[com.traytimer.data.prefs.Keys.NOTIF_PRIMING_DONE] = true
            }
            if (Build.VERSION.SDK_INT >= 33) showNotifPriming = true
        }
    }
    if (showNotifPriming) {
        AlertDialog(
            onDismissRequest = { showNotifPriming = false },
            title = { Text("Reminders need notifications") },
            text = { Text("TrayTimer sends put-back reminders, tray-change reminders and appointment alerts. Allow notifications so you never miss one.") },
            confirmButton = {
                TextButton(onClick = {
                    showNotifPriming = false
                    if (Build.VERSION.SDK_INT >= 33) {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { showNotifPriming = false }) { Text("Not now") }
            }
        )
    }

    // ---- Timezone-change prompt (FR-TMR-16) ----
    var pendingZone by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings) {
        val s = settings ?: return@LaunchedEffect
        if (s.pendingZone.isNotEmpty() && s.pendingZone != s.lastPromptedZone) {
            pendingZone = s.pendingZone
        }
    }
    pendingZone?.let { zoneName ->
        AlertDialog(
            onDismissRequest = { pendingZone = null },
            title = { Text("Time zone changed") },
            text = {
                Text("Your device time zone changed to $zoneName. Do you want to switch and show times based on this new time zone?")
            },
            confirmButton = {
                TextButton(onClick = {
                    Graph.settingsStore.update { s, p ->
                        p[com.traytimer.data.prefs.Keys.USE_DEVICE_TZ] = true
                        p[com.traytimer.data.prefs.Keys.PENDING_ZONE] = ""
                        p[com.traytimer.data.prefs.Keys.LAST_PROMPTED_ZONE] = zoneName
                    }
                    pendingZone = null
                }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = {
                    Graph.settingsStore.update { s, p ->
                        p[com.traytimer.data.prefs.Keys.PENDING_ZONE] = ""
                        p[com.traytimer.data.prefs.Keys.LAST_PROMPTED_ZONE] = zoneName
                    }
                    pendingZone = null
                }) { Text("No") }
            }
        )
    }

    val tabs = listOf(
        TabItem("Timer", Icons.Filled.Timer, Icons.Outlined.Timer),
        TabItem("Calendar", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
        TabItem("Teethies", Icons.Filled.Favorite, Icons.Outlined.FavoriteBorder),
        TabItem("Stats", Icons.Filled.BarChart, Icons.Outlined.BarChart)
    )

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(tabs[selectedTab].label) },
                actions = {
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                if (selectedTab == index) tab.filled else tab.outlined,
                                contentDescription = tab.label
                            )
                        },
                        label = { Text(tab.label) },
                        selected = selectedTab == index,
                        onClick = { TabBus.open(index) }
                    )
                }
            }
        }
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (selectedTab) {
            TabBus.TAB_TIMER -> TimerScreen(vm, modifier, navController)
            TabBus.TAB_CALENDAR -> CalendarScreen(vm, modifier, navController)
            TabBus.TAB_TEETHIES -> TeethiesScreen(vm, modifier, navController)
            TabBus.TAB_STATS -> StatsScreen(vm, modifier)
            else -> TimerScreen(vm, modifier, navController)
        }
    }
}

data class TabItem(
    val label: String,
    val filled: androidx.compose.ui.graphics.vector.ImageVector,
    val outlined: androidx.compose.ui.graphics.vector.ImageVector
)