package com.traytimer.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.traytimer.core.Graph
import com.traytimer.liveupdates.LiveUpdateService
import com.traytimer.reminders.AlarmScheduler
import com.traytimer.widgets.WidgetRefresher
import kotlinx.coroutines.launch

/** Local data export/import via the system file picker. */
@Composable
fun DataSettingsScreen(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                working = true
                status = "Exporting…"
                val result = Graph.dataPortRepository.exportToZip(uri)
                working = false
                status = result.fold(
                    { "Export complete" },
                    { "Export failed: ${it.message ?: "unknown error"}" }
                )
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                working = true
                status = "Importing…"
                val result = Graph.dataPortRepository.importFromZip(uri)
                working = false
                status = result.fold(
                    {
                        AlarmScheduler.rescheduleAll(context)
                        WidgetRefresher.pushAll(context)
                        LiveUpdateService.refresh(context, Graph.timerRepository.getOpen() == null)
                        "Import complete — all local data was replaced"
                    },
                    { "Import failed: ${it.message ?: "unknown error"}" }
                )
            }
        }
    }

    SettingsSubScreenScaffold("Data export & import", navController) {
        Text(
            "Export a .zip containing your wear sessions, treatment plan, appointments, day notes and Teethies photos. " +
                "Import replaces everything currently stored on this device.",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = {
                if (!working) {
                    exportLauncher.launch("TrayTimer-backup-${java.time.LocalDate.now()}.zip")
                }
            },
            enabled = !working,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Export data (.zip)") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { if (!working) confirmImport = true },
            enabled = !working,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Import data (.zip)") }

        status?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Tip: copy the exported file somewhere safe. TrayTimer has no cloud backup.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }

    if (confirmImport) {
        ConfirmDialog(
            title = "Replace all data?",
            text = "Importing a backup permanently replaces all sessions, treatment plan, appointments, notes and photos on this device. This cannot be undone.",
            confirmLabel = "Choose file",
            danger = true,
            onConfirm = {
                confirmImport = false
                importLauncher.launch(arrayOf("application/zip"))
            },
            onDismiss = { confirmImport = false }
        )
    }
}
