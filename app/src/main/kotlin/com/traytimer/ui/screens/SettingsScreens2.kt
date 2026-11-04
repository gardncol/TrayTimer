package com.traytimer.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.traytimer.BuildConfig
import com.traytimer.core.Graph
import com.traytimer.core.logic.Analytics
import com.traytimer.data.prefs.Keys
import com.traytimer.liveupdates.LiveUpdateService
import com.traytimer.reminders.AlarmScheduler
import kotlinx.coroutines.launch

/** FR-SET-04: calendar settings + system calendar sync (FR-CSYNC-01..05). */
@Composable
fun CalendarSettingsScreen(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by Graph.settingsStore.settings.collectAsState(initial = null)
    val s = settings ?: return

    val calPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            Graph.settingsStore.update { st, p -> p[Keys.CAL_SYNC_ENABLED] = true }
        }
    }

    SettingsSubScreenScaffold("Calendar", navController) {
        LabeledRow(
            "Day Reset Time %02d:%02d".format(s.dayResetMinutes / 60, s.dayResetMinutes % 60),
            "",
            onClick = {
                val next = (s.dayResetMinutes + 60) % (24 * 60)
                Graph.settingsStore.update { st, p -> p[Keys.DAY_RESET_MINUTES] = next }
                scope.launch { AlarmScheduler.rescheduleAll(context) }
            }
        )
        Text(
            "Late-evening meals won't count against the next day.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        SettingRow("Start week on Sunday", checked = s.startOfWeekSunday) {
            Graph.settingsStore.update { st, p -> p[Keys.START_WEEK_SUNDAY] = it }
        }

        HorizontalDividerTitle("System calendar sync")
        SettingRow("Write tray-change events to device calendar", checked = s.calendarSyncEnabled) { enabled ->
            if (enabled) {
                calPermission.launch(
                    arrayOf(
                        android.Manifest.permission.READ_CALENDAR,
                        android.Manifest.permission.WRITE_CALENDAR
                    )
                )
            } else {
                Graph.settingsStore.update { st, p -> p[Keys.CAL_SYNC_ENABLED] = false }
            }
        }
        if (s.calendarSyncEnabled) {
            LabeledRow("Calendar name", s.calendarName)
            OutlinedTextField(
                value = s.calendarName,
                onValueChange = { Graph.settingsStore.update { st, p -> p[Keys.CALENDAR_NAME] = it } },
                label = { Text("Calendar name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = s.trayEventTitle,
                onValueChange = { Graph.settingsStore.update { st, p -> p[Keys.TRAY_EVENT_TITLE] = it } },
                label = { Text("Tray-change event title (%s = aligner label)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            SettingRow("Calendar sync alerts", checked = s.calendarAlerts) {
                Graph.settingsStore.update { st, p -> p[Keys.CAL_ALERTS] = it }
            }
            Text(
                "Managed by TrayTimer. Changes in your calendar may be overwritten.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            OutlinedButton(onClick = {
                scope.launch {
                    val written = CalendarSync.pushTrayChangeEvents(context)
                    Analytics.log("calendar_sync_pushed", mapOf("events" to written))
                }
            }) { Text("Sync now") }
        }
    }
}

/** One-way TrayTimer → CalendarProvider writes (FR-CSYNC-01..05). */
object CalendarSync {
    suspend fun pushTrayChangeEvents(context: Context): Int {
        val s = Graph.settingsStore.current()
        if (!s.calendarSyncEnabled) return 0
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
            context.checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return 0
        val zone = com.traytimer.core.logic.DayMath.zone(s.useDeviceTimeZone, s.chosenTimeZone)
        val trays = Graph.treatmentRepository.getTrays()
        var written = 0
        val resolver = context.contentResolver
        val calId = pickCalendar(context, s.calendarName)
        if (calId == null) return 0
        for (tray in com.traytimer.core.logic.PlanEngine.sorted(trays)) {
            val startDay = com.traytimer.core.logic.PlanEngine.effectiveStart(tray)
            if (startDay <= System.currentTimeMillis()) continue
            val title = s.trayEventTitle.replace("%s", tray.label)
            val dtStart = startDay + 9 * 3_600_000L
            val values = android.content.ContentValues().apply {
                put(android.provider.CalendarContract.Events.CALENDAR_ID, calId)
                put(android.provider.CalendarContract.Events.TITLE, title)
                put(
                    android.provider.CalendarContract.Events.DESCRIPTION,
                    "Managed by TrayTimer. Changes in your calendar may be overwritten."
                )
                put(android.provider.CalendarContract.Events.DTSTART, dtStart)
                put(
                    android.provider.CalendarContract.Events.DTEND,
                    dtStart + 3_600_000L
                )
                put(
                    android.provider.CalendarContract.Events.EVENT_TIMEZONE,
                    zone.id
                )
                put(
                    android.provider.CalendarContract.Events.CUSTOM_APP_PACKAGE,
                    context.packageName
                )
                if (s.calendarAlerts) {
                    put(android.provider.CalendarContract.Events.HAS_ALARM, 1)
                }
            }
            runCatching {
                val evUri = resolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
                val evId = evUri?.lastPathSegment?.toLongOrNull()
                if (s.calendarAlerts && evId != null) {
                    val rem = android.content.ContentValues().apply {
                        put(android.provider.CalendarContract.Reminders.EVENT_ID, evId)
                        put(android.provider.CalendarContract.Reminders.MINUTES, 60)
                        put(android.provider.CalendarContract.Reminders.METHOD, android.provider.CalendarContract.Reminders.METHOD_ALERT)
                    }
                    resolver.insert(android.provider.CalendarContract.Reminders.CONTENT_URI, rem)
                }
                written++
            }
        }
        return written
    }

    fun pickCalendar(context: Context, name: String): Long? {
        val resolver = context.contentResolver
        var id: Long? = null
        runCatching {
            resolver.query(
                android.provider.CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    android.provider.CalendarContract.Calendars._ID,
                    android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idxId = c.getColumnIndex(android.provider.CalendarContract.Calendars._ID)
                    id = c.getLong(idxId)
                }
            }
        }
        if (id != null) return id
        // create a local TrayTimer calendar
        return runCatching {
            val values = android.content.ContentValues().apply {
                put(android.provider.CalendarContract.Calendars.ACCOUNT_NAME, "TrayTimer")
                put(android.provider.CalendarContract.Calendars.ACCOUNT_TYPE, "com.traytimer")
                put(android.provider.CalendarContract.Calendars.NAME, name)
                put(android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, name)
                put(android.provider.CalendarContract.Calendars.CALENDAR_COLOR, 0xFF14B8A6.toInt())
                put(android.provider.CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, android.provider.CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(android.provider.CalendarContract.Calendars.OWNER_ACCOUNT, "TrayTimer")
                put(android.provider.CalendarContract.Calendars.VISIBLE, 1)
                put(android.provider.CalendarContract.Calendars.SYNC_EVENTS, 1)
            }
            resolver.insert(
                android.provider.CalendarContract.Calendars.CONTENT_URI
                    .buildUpon()
                    .appendQueryParameter(android.provider.CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(android.provider.CalendarContract.Calendars.ACCOUNT_NAME, "TrayTimer")
                    .appendQueryParameter(android.provider.CalendarContract.Calendars.ACCOUNT_TYPE, "com.traytimer")
                    .build(),
                values
            )?.lastPathSegment?.toLongOrNull()
        }.getOrNull()
    }
}

@Composable
private fun HorizontalDividerTitle(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
}
