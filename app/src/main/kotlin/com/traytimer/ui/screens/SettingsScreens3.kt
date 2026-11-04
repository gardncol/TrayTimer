package com.traytimer.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.navigation.NavHostController
import com.traytimer.BuildConfig
import com.traytimer.core.Graph
import com.traytimer.core.logic.Analytics
import com.traytimer.core.services.FaqUrls
import com.traytimer.core.services.SocialUrls
import com.traytimer.core.services.UpdateChecker
import com.traytimer.data.prefs.Keys
import com.traytimer.liveupdates.LiveUpdateService
import com.traytimer.reminders.AlarmScheduler
import kotlinx.coroutines.launch

/** FR-SET-06 / FR-LIV-01..05. */
@Composable
fun LiveUpdatesSettingsScreen(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by Graph.settingsStore.settings.collectAsState(initial = null)
    val s = settings ?: return
    val notifGranted = android.os.Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    SettingsSubScreenScaffold("Live Updates", navController) {
        Text(
            "Show a running wear timer in the status bar and on the lock screen.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(8.dp))
        if (!notifGranted) {
            Text(
                "Setup required: grant notification permission first",
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
        }
        SettingRow("Enable Live Updates", checked = s.liveUpdatesEnabled) { enabled ->
            Graph.settingsStore.update { st, p -> p[Keys.LIVE_UPDATES] = enabled }
            scope.launch {
                if (enabled && notifGranted) LiveUpdateService.start(context)
                else LiveUpdateService.stop(context)
                Analytics.log("live_updates_toggled", mapOf("on" to enabled))
            }
        }
        Text(
            "Requirements: Android 10+, notification permission, and battery-optimization exemption " +
                "where applicable. If your device or OS can't support it, this screen explains why " +
                "instead of failing silently.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

/** FR-SET-07 + FR-WID-*. */
@Composable
fun WidgetsSettingsScreen(navController: NavHostController) {
    val settings by Graph.settingsStore.settings.collectAsState(initial = null)
    val s = settings ?: return
    SettingsSubScreenScaffold("Widgets", navController) {
        Text(
            "To add a widget: long-press your home screen, choose Widgets, then pick TrayTimer.",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(8.dp))
        Text("Small widget", style = MaterialTheme.typography.titleMedium)
        Text("1×1 glanceable wearing status; tap to start or pause.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text("Medium widget", style = MaterialTheme.typography.titleMedium)
        Text(
            "Resizable live timer with start/pause and change-tray buttons. Also available on the lock screen.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        SettingRow("Show time left to goal", checked = s.showTimeLeft) {
            Graph.settingsStore.update { st, p -> p[Keys.SHOW_TIME_LEFT] = it }
        }
        Text(
            "Widgets refresh every 30 minutes and instantly on every timer change (push refresh).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

/** FR-SET-08 + FR-QST-*. */
@Composable
fun TileSettingsScreen(navController: NavHostController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings by Graph.settingsStore.settings.collectAsState(initial = null)
    val s = settings ?: return
    SettingsSubScreenScaffold("Quick Settings", navController) {
        Text(
            "Swipe down twice (or once from the top-right), edit the tiles, and drag the TrayTimer tile into your Quick Settings.",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "The tile toggles the timer — start or pause without unlocking into the app. Its state always reflects live wearing status.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            Spacer(Modifier.height(8.dp))
            AssistChip(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent("android.settings.ACTION_QUICK_SETTINGS_TILE_SETTINGS")
                                .setPackage("com.android.settings")
                        )
                    }
                },
                label = { Text("Open tile settings") }
            )
        }
    }
}

/** FR-SET-09 + FR-WEAR-* (placeholder in this build). */
@Composable
fun WatchSettingsScreen(navController: NavHostController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    SettingsSubScreenScaffold("Watch", navController) {
        Text(
            "Install the TrayTimer watch app from Google Play on your watch. (Wear module not bundled in this build — TODO)",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(8.dp))
        Text("Watch synced", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pausing on the watch updates the phone's Timer tab, Live Update notification and widgets within seconds.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.traytimer.wearable"))
                )
            }
        }) { Text("How to install") }
    }
}

/** FR-SET-10 + FR-VOX-02: browsable voice phrases. */
@Composable
fun VoiceSettingsScreen(navController: NavHostController) {
    SettingsSubScreenScaffold("Voice commands", navController) {
        Text(
            "Use these phrases with Google Assistant or Gemini:",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(12.dp))
        listOf(
            "Hey Google, start wearing",
            "Hey Google, stop wearing",
            "Hey Google, open TrayTimer Calendar",
            "Hey Google, show my TrayTimer stats",
            "Hey Google, take a teethie",
            "Hey Google, switch aligners"
        ).forEach { phrase ->
            Text("• $phrase", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Perfect for checking progress quickly, even when your hands are full. iOS users get the same via Siri: \"Siri, TrayTimer photo\".",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

/** FR-SET-11 + FR-PHO-* settings. */
@Composable
fun PhotoSettingsScreen(navController: NavHostController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by Graph.settingsStore.settings.collectAsState(initial = null)
    val s = settings ?: return
    var status by remember { mutableStateOf<String?>(null) }
    SettingsSubScreenScaffold("Photos", navController) {
        SettingRow("Mirrored selfie", "Make front-camera capture intuitive", s.mirroredSelfie) {
            Graph.settingsStore.update { st, p -> p[Keys.MIRRORED_SELFIE] = it }
        }
        SettingRow("Save Teethies to your photo library", checked = s.saveToLibrary) {
            Graph.settingsStore.update { st, p -> p[Keys.SAVE_TO_LIBRARY] = it }
        }
        Text(
            "Photos are saved in app storage. When enabled, a copy is also written to your device photo library.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            scope.launch {
                Graph.photoRepository.regenerateThumbnails()
                status = "Thumbnails regenerated"
            }
        }) { Text("Regenerate thumbnails") }
        OutlinedButton(onClick = {
            scope.launch {
                val zipDir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
                val outFile = java.io.File(zipDir, "photos_export_${System.currentTimeMillis()}.zip")
                java.util.zip.ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
                    Graph.photoRepository.photosDir.listFiles()?.forEach { f ->
                        zip.putNextEntry(java.util.zip.ZipEntry("photos/${f.name}"))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(
                        Intent.EXTRA_STREAM,
                        androidx.core.content.FileProvider.getUriForFile(
                            context, context.packageName + ".fileprovider", outFile
                        )
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(share, "Export photos"))
            }
        }) { Text("Export photos") }
        status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}

/** FR-SET-12: theme picker. */
@Composable
fun AppearanceSettingsScreen(navController: NavHostController) {
    val settings by Graph.settingsStore.settings.collectAsState(initial = null)
    val s = settings ?: return
    SettingsSubScreenScaffold("Appearance", navController) {
        listOf("SYSTEM" to "System", "LIGHT" to "Light", "DARK" to "Dark").forEach { (mode, label) ->
            Row(Modifier.padding(vertical = 4.dp)) {
                RadioButton(
                    selected = s.themeMode == mode,
                    onClick = { Graph.settingsStore.update { st, p -> p[Keys.THEME_MODE] = mode } }
                )
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
        }
        Text(
            "Wearing-state colors: teal = on track, red = trays out, green/yellow/red goal thresholds.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

/** FR-SET-13: time & locale. */
@Composable
fun TimeSettingsScreen(navController: NavHostController) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings by Graph.settingsStore.settings.collectAsState(initial = null)
    val s = settings ?: return
    SettingsSubScreenScaffold("Time & locale", navController) {
        SettingRow("Use device time zone", checked = s.useDeviceTimeZone) { use ->
            Graph.settingsStore.update { st, p -> p[Keys.USE_DEVICE_TZ] = use }
        }
        if (!s.useDeviceTimeZone) {
            var zoneText by remember(s.chosenTimeZone) { mutableStateOf(s.chosenTimeZone.ifBlank { java.time.ZoneId.systemDefault().id }) }
            OutlinedTextField(
                value = zoneText,
                onValueChange = { zoneText = it },
                label = { Text("Time zone (IANA, e.g. Europe/Berlin)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = {
                runCatching { java.time.ZoneId.of(zoneText) }.fold({
                    Graph.settingsStore.update { st, p -> p[Keys.CHOSEN_TZ] = zoneText }
                    scope.launch { AlarmScheduler.rescheduleAll(context) }
                }, {})
            }) { Text("Apply time zone") }
        }
        SettingRow("Daylight-saving notifications", "Notify when DST changes your schedule", true) {
            // DST notifications are always scheduled; the toggle documents the behavior
        }
        LabeledRow(
            "Day Reset Time %02d:%02d".format(s.dayResetMinutes / 60, s.dayResetMinutes % 60),
            "",
            onClick = {
                val next = (s.dayResetMinutes + 60) % (24 * 60)
                Graph.settingsStore.update { st, p -> p[Keys.DAY_RESET_MINUTES] = next }
                scope.launch { AlarmScheduler.rescheduleAll(context) }
            }
        )
    }
}

/** Privacy settings. */
@Composable
fun AdsPrivacySettingsScreen(navController: NavHostController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    SettingsSubScreenScaffold("Privacy", navController) {
        Text(
            "TrayTimer stores your treatment data on this device only. Nothing is uploaded.",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(12.dp))
        Text("Privacy options", style = MaterialTheme.typography.titleMedium)
        Text(
            "This build shows no ads and does not collect advertising identifiers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://traytimer.com/privacy")))
            }
        }) { Text("Privacy policy") }
    }
}

/** FR-SET-16/17: about, What's New, FAQ, licenses, rate, update policy. */
@Composable
fun AboutScreen(navController: NavHostController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var updateState by remember { mutableStateOf<UpdateChecker.UpdateState?>(null) }
    var faqError by remember { mutableStateOf(false) }

    SettingsSubScreenScaffold("About", navController) {
        Text("TrayTimer", style = MaterialTheme.typography.headlineLarge)
        Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Spacer(Modifier.height(12.dp))
        Text("What's New", style = MaterialTheme.typography.titleMedium)
        Text("• Improved Fullscreen Reminder\n• Photo Timeline\n• Collage Share\n• Automatic Treatment Adjustment\n• Aligner Progress View")
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = {
            scope.launch { updateState = Graph.updateChecker.check(BuildConfig.VERSION_CODE) }
        }) { Text("Check for updates") }
        updateState?.let {
            Text(
                if (it.required) "Update required — this version is no longer supported."
                else if (it.available) "Update available — a new version of TrayTimer is ready."
                else "You're up to date.",
                color = if (it.available) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            runCatching {
                val locale = context.resources.configuration.locales[0].toLanguageTag()
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(FaqUrls.forLocale(locale)))
                )
            }.onFailure { faqError = true }
        }) { Text("FAQ") }
        if (faqError) Text("Not available offline", color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://traytimer.com/privacy")))
            }
        }) { Text("Privacy policy") }
        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://traytimer.com/terms")))
            }
        }) { Text("Terms of use") }
        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.traytimer"))
                )
            }
        }) { Text("Rate the app") }
        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(SocialUrls.INSTAGRAM))
                )
            }
        }) { Text("Follow us on Instagram") }
    }
}