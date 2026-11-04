package com.traytimer.ui.screens

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.traytimer.core.Graph
import com.traytimer.data.prefs.Keys
import com.traytimer.reminders.AlarmScheduler
import com.traytimer.ui.nav.Routes

/** Settings hub (FR-SET-01..18): every feature area is discoverable from here. */
@Composable
fun SettingsHubScreen(navController: NavHostController) {
    val entries = listOf(
        "Timer" to Icons.Default.Timer to Routes.SETTINGS_TIMER,
        "Reminders" to Icons.Default.Alarm to Routes.SETTINGS_REMINDERS,
        "Notifications" to Icons.Default.Notifications to Routes.SETTINGS_NOTIFICATIONS,
        "Calendar" to Icons.Default.CalendarMonth to Routes.SETTINGS_CALENDAR,
        "Live Updates" to Icons.Default.Tune to Routes.SETTINGS_LIVE,
        "Widgets" to Icons.Default.Tune to Routes.SETTINGS_WIDGETS,
        "Quick Settings" to Icons.Default.Tune to Routes.SETTINGS_TILE,
        "Watch" to Icons.Default.Watch to Routes.SETTINGS_WATCH,
        "Voice commands" to Icons.Default.Timer to Routes.SETTINGS_VOICE,
        "Photos" to Icons.Default.CameraAlt to Routes.SETTINGS_PHOTOS,
        "Appearance" to Icons.Default.Palette to Routes.SETTINGS_APPEARANCE,
        "Time & locale" to Icons.Default.Timer to Routes.SETTINGS_TIME,
        "Privacy" to Icons.Default.Payments to Routes.SETTINGS_ADS,
        "About" to Icons.Default.Info to Routes.SETTINGS_ABOUT
    )
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.padding(8.dp)) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
            }
            Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        }
        androidx.compose.foundation.lazy.LazyColumn {
            items(entries.size) { i ->
                val (title, route) = entries[i].let { it.first.first to it.second }
                val icon: ImageVector = entries[i].first.second
                ListItem(
                    headlineContent = { Text(title) },
                    leadingContent = { Icon(icon, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate(route) }
                )
            }
        }
    }
}

@Composable
fun SettingsSubScreenScaffold(
    title: String,
    navController: NavHostController,
    content: @Composable () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Row(Modifier.padding(vertical = 8.dp)) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
            }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        }
        content()
        Spacer(Modifier.height(80.dp))
    }
}

/** FR-SET-01: timer settings. */
@Composable
fun TimerSettingsScreen(navController: NavHostController) {
    val settings by Graph.settingsStore.settings.collectAsState(initial = null)
    val s = settings ?: return
    SettingsSubScreenScaffold("Timer", navController) {
        SettingRow("Timer mode (show seconds)", checked = s.timerShowSeconds) {
            Graph.settingsStore.update { st, p -> p[Keys.TIMER_SHOW_SECONDS] = it }
        }
        SettingRow("Accidental tap detection", "Confirm before stopping a timer started less than a minute ago",
            s.accidentalTapDetection) {
            Graph.settingsStore.update { st, p -> p[Keys.ACCIDENTAL_TAP] = it }
        }
        SettingRow("Keep screen on", checked = s.keepScreenOn) {
            Graph.settingsStore.update { st, p -> p[Keys.KEEP_SCREEN_ON] = it }
        }
        SettingRow("Vibrate on state changes", checked = s.vibrate) {
            Graph.settingsStore.update { st, p -> p[Keys.VIBRATE] = it }
        }
        SectionCard(Modifier.padding(vertical = 12.dp)) {
            NumberStepper(
                label = "Daily wear goal",
                value = s.wearGoalHours,
                onChange = {
                    Graph.settingsStore.update { st, p ->
                        p[Keys.WEAR_GOAL_HOURS] = it
                        p[Keys.GREEN_THRESHOLD] = it
                    }
                },
                min = 1, max = 23, valueText = "${s.wearGoalHours}h"
            )
        }
        SectionCard(Modifier.padding(vertical = 12.dp)) {
            NumberStepper(
                label = "Yellow threshold (hours)",
                value = s.yellowThresholdHours,
                onChange = { Graph.settingsStore.update { st, p -> p[Keys.YELLOW_THRESHOLD] = it } },
                min = 1, max = 23, valueText = "${s.yellowThresholdHours}h"
            )
        }
    }
}

/** FR-SET-02: reminder settings. */
@Composable
fun ReminderSettingsScreen(navController: NavHostController) {
    val context = LocalContext.current
    val settings by Graph.settingsStore.settings.collectAsState(initial = null)
    val s = settings ?: return
    SettingsSubScreenScaffold("Reminders", navController) {
        SectionCard(Modifier.padding(vertical = 8.dp)) {
            NumberStepper(
                label = "Trays-out reminder (minutes)",
                value = s.defaultTrayOffReminderMinutes,
                onChange = { Graph.settingsStore.update { st, p -> p[Keys.TRAY_OFF_REMINDER_MIN] = it } },
                min = 5, max = 240
            )
        }
        SettingRow("Tray-change reminder", checked = s.trayChangeReminderEnabled) { enabled ->
            Graph.settingsStore.update { st, p -> p[Keys.TRAY_CHANGE_ENABLED] = enabled }
        }
        if (s.trayChangeReminderEnabled) {
            LabeledRow(
                "Remind at",
                "%02d:%02d".format(s.trayChangeReminderMinutesOfDay / 60, s.trayChangeReminderMinutesOfDay % 60),
                onClick = {
                    val next = (s.trayChangeReminderMinutesOfDay + 60) % (24 * 60)
                    Graph.settingsStore.update { st, p -> p[Keys.TRAY_CHANGE_TIME] = next }
                }
            )
        }
        SettingRow(
            "Tray total-time reminder",
            "Do you want to receive a reminder when total wear time for your current aligner reaches your plan?",
            s.trayTotalReminderEnabled
        ) { enabled ->
            Graph.settingsStore.update { st, p -> p[Keys.TRAY_TOTAL_ENABLED] = enabled }
        }
        SettingRow("Selfie reminder", checked = s.selfieReminderEnabled) { enabled ->
            Graph.settingsStore.update { st, p -> p[Keys.SELFIE_ENABLED] = enabled }
        }
        if (s.selfieReminderEnabled) {
            SectionCard(Modifier.padding(vertical = 8.dp)) {
                NumberStepper(
                    label = "Remind every N days",
                    value = s.selfieReminderDays,
                    onChange = { Graph.settingsStore.update { st, p -> p[Keys.SELFIE_DAYS] = it } },
                    min = 1, max = 30, valueText = "${s.selfieReminderDays} days"
                )
            }
        }
        SettingRow("Time-sensitive notifications", checked = s.timeSensitiveNotifications) {
            Graph.settingsStore.update { st, p -> p[Keys.TIME_SENSITIVE] = it }
        }
        SettingRow("Notification sound", checked = s.notificationSound) {
            Graph.settingsStore.update { st, p -> p[Keys.NOTIF_SOUND] = it }
        }
        SectionCard(Modifier.padding(vertical = 8.dp)) {
            Text("Snooze lengths", style = MaterialTheme.typography.titleMedium)
            NumberStepper("Trays-out snooze (s)", s.trayOffSnoozeSeconds,
                { Graph.settingsStore.update { st, p -> p[Keys.TRAY_OFF_SNOOZE] = it } }, 60, 3600, 30)
            NumberStepper("Tray-change snooze (s)", s.trayChangeSnoozeSeconds,
                { Graph.settingsStore.update { st, p -> p[Keys.TRAY_CHANGE_SNOOZE] = it } }, 60, 3600, 30)
            NumberStepper("Selfie snooze (s)", s.selfieSnoozeSeconds,
                { Graph.settingsStore.update { st, p -> p[Keys.SELFIE_SNOOZE] = it } }, 60, 3600, 30)
        }
        OutlinedButton(onClick = {
            com.traytimer.widgets.WidgetRefresher.pushAll(context)
        }) { Text("Reschedule all reminders") }
    }
}

/** FR-SET-03: notification diagnostics + OEM guides. */
@Composable
fun NotificationSettingsScreen(navController: NavHostController) {
    val context = LocalContext.current
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    SettingsSubScreenScaffold("Notifications", navController) {
        Text("Fix Notification Problems", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        // Diagnostics
        val notifGranted = if (Build.VERSION.SDK_INT >= 33) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
        val exactOk = AlarmScheduler.canScheduleExact(context)
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val batteryOk = Build.VERSION.SDK_INT < 23 ||
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        val remindersBlocked = nm.getNotificationChannel(com.traytimer.core.notifications.Channels.REMINDERS)
            ?.importance == NotificationManager.IMPORTANCE_NONE
        val alarmsBlocked = nm.getNotificationChannel(com.traytimer.core.notifications.Channels.ALARMS)
            ?.importance == NotificationManager.IMPORTANCE_NONE

        StatusRow("Notification permission", if (notifGranted) "granted" else "missing — reminders won't be visible", notifGranted)
        StatusRow("Exact alarms", if (exactOk) "granted" else "not granted — reminders may be delayed", exactOk)
        StatusRow("Battery optimization", if (batteryOk) "exempt" else "on — this app may be delayed or killed", batteryOk)
        StatusRow("Reminders channel", if (remindersBlocked) "blocked" else "active", !remindersBlocked)
        StatusRow("Wearing alarms channel", if (alarmsBlocked) "blocked" else "active", !alarmsBlocked)

        Spacer(Modifier.height(12.dp))
        Row {
            OutlinedButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33) {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    })
                } else {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                    })
                }
            }) { Text("Open app notification settings") }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                })
            }) { Text("Request battery exemption") }
        }
        Spacer(Modifier.height(6.dp))
        if (Build.VERSION.SDK_INT >= 31 && !exactOk) {
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }) { Text("Grant exact alarms") }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { navController.navigate(Routes.SETTINGS_OEM) }) {
            Text("Don't-kill-my-app guides")
        }
        Spacer(Modifier.height(12.dp))
        AssistChip(onClick = {
            nm.deleteNotificationChannel(com.traytimer.core.notifications.Channels.REMINDERS)
            com.traytimer.core.notifications.Channels.createAll(context)
        }, label = { Text("Reset notification channels") })
    }
}

@Composable
private fun StatusRow(label: String, detail: String, ok: Boolean) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Text("•", color = if (ok) androidx.compose.ui.graphics.Color(0xFF4CAF50) else androidx.compose.ui.graphics.Color(0xFFEF5350))
        Spacer(Modifier.padding(2.dp))
        Column {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

/** FR-SET-18 + DontKillMyApp content (FR-REM-14). */
@Composable
fun OemGuidesScreen(navController: NavHostController) {
    SettingsSubScreenScaffold("Stop your phone from killing TrayTimer", navController) {
        Text(
            "Android manufacturers aggressively stop background apps. Follow the guide for your brand so reminders always fire.",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(12.dp))
        val guides = listOf(
            "Stock Pixel / Motorola" to "Settings → Apps → TrayTimer → Battery → Unrestricted. Then Settings → Battery → Battery saver off for TrayTimer.",
            "Samsung" to "Settings → Apps → TrayTimer → Battery → Unrestricted. Also disable \"Put unused apps to sleep\": Settings → Battery → Background usage limits → remove TrayTimer from Sleeping apps and Deep sleeping apps.",
            "Xiaomi / Redmi / POCO" to "Security app → Speed booster → Lock TrayTimer in memory. Settings → Apps → Manage apps → TrayTimer → Autostart ON, Battery saver → No restrictions.",
            "Huawei / Honor" to "Settings → Battery → App launch → TrayTimer: disable Manage automatically, enable all three toggles. Also Protected apps → enable TrayTimer.",
            "OnePlus / Oppo" to "Settings → Battery → Battery optimization → All apps → TrayTimer → Don't optimize. Also Settings → Apps → TrayTimer → Battery usage → Allow background activity.",
            "Vivo / iQOO" to "Settings → Battery → Background power consumption management → allow TrayTimer. i Manager → App manager → Autostart for TrayTimer.",
            "Asus" to "Mobile Manager → Autostart manager → allow TrayTimer. Battery → PowerMaster → unrestrict TrayTimer.",
            "Sony" to "Settings → Battery → STAMINA → Apps active in standby → TrayTimer. Also disable App standby optimization for TrayTimer."
        )
        guides.forEach { (brand, steps) ->
            SectionCard(Modifier.padding(vertical = 4.dp)) {
                Text(brand, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(steps, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}