package com.traytimer.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.traytimer.core.Graph
import com.traytimer.core.logic.Analytics
import com.traytimer.data.db.Teethie
import com.traytimer.data.repo.PhotoRepository
import com.traytimer.ui.MainViewModel
import com.traytimer.ui.nav.TabBus
import com.traytimer.ui.screens.media.CollageBuilder
import com.traytimer.ui.screens.media.ImageShare
import com.traytimer.ui.screens.media.ProgressVideoBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Teethies tab (FR-PHO-05..12): timeline gallery + compare/collage/video. */
@Composable
fun TeethiesScreen(vm: MainViewModel, modifier: Modifier, navController: NavHostController) {
    val context = LocalContext.current
    val photos by vm.photos.collectAsState()
    val scope = rememberCoroutineScope()

    val selection = remember { mutableStateOf(setOf<Long>()) }
    var detailPhoto by remember { mutableStateOf<Teethie?>(null) }
    var editPhoto by remember { mutableStateOf<Teethie?>(null) }
    var openCamera by remember { mutableStateOf(false) }
    var videoSelection by remember { mutableStateOf<List<Teethie>?>(null) }
    var videoSpeed by remember { mutableStateOf(1) }

    // Voice deep link: "Hey Google, take a teethie" (FR-PHO-14)
    val cameraSignal by TabBus.deepLinkCamera.collectAsState()
    LaunchedEffect(cameraSignal) {
        if (cameraSignal) {
            openCamera = true
            TabBus.deepLinkCamera.value = false
        }
    }

    // Library import (FR-PHO-03)
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val now = System.currentTimeMillis()
                Graph.photoRepository.addFromUri(uri, now, currentTrayLabel(vm), PhotoRepository.Angle.FACE_FRONT)
                Analytics.log("photo_imported")
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (photos.isEmpty()) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Your smile journey starts here — take your first Teethie!",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(photos.asReversed(), key = { it.id }) { photo ->
                        val selected = selection.value.contains(photo.id)
                        AsyncImage(
                            model = Graph.photoRepository.thumbnailFile(photo),
                            contentDescription = "Teethie ${photo.angle}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    if (selection.value.isNotEmpty()) {
                                        selection.value =
                                            if (selected) selection.value - photo.id
                                            else selection.value + photo.id
                                    } else {
                                        detailPhoto = photo
                                    }
                                }
                                .padding(if (selected) 8.dp else 0.dp)
                        )
                    }
                }
            }

            // Selection actions (compare / collage / video)
            if (selection.value.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(
                        onClick = {
                            val chosen = photos.filter { selection.value.contains(it.id) }
                            if (chosen.size == 2) {
                                scope.launch {
                                    val file = ImageShare.renderCompare(context, chosen[0], chosen[1])
                                    if (file != null) ImageShare.shareFile(context, file, "image/jpeg")
                                }
                            }
                            selection.value = emptySet()
                        },
                        enabled = selection.value.size == 2
                    ) { Text("Compare") }
                    OutlinedButton(
                        onClick = {
                            val chosen = photos.filter { selection.value.contains(it.id) }
                            scope.launch {
                                val file = CollageBuilder.build(context, chosen)
                                if (file != null) ImageShare.shareFile(context, file, "image/jpeg")
                            }
                            selection.value = emptySet()
                        },
                        enabled = selection.value.size in 2..6
                    ) { Text("Collage") }
                    OutlinedButton(
                        onClick = {
                            videoSelection = photos.filter { selection.value.contains(it.id) }
                            selection.value = emptySet()
                        },
                        enabled = selection.value.size in 2..10
                    ) { Text("Progress video") }
                    TextButton(onClick = { selection.value = emptySet() }) { Text("Clear") }
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            OutlinedButton(onClick = {
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("Choose from library") }
            Spacer(Modifier.height(8.dp))
            ExtendedFloatingActionButton(onClick = { openCamera = true }) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text("  New Teethie")
            }
        }
    }

    // ---- In-app camera (FR-PHO-01/02) ----
    if (openCamera) {
        CameraScreen(modifier = modifier, onDone = { openCamera = false })
    }

    // ---- Photo detail + editor (FR-PHO-04/09/11) ----
    detailPhoto?.let { photo ->
        PhotoDetailDialog(
            photo = photo,
            onClose = { detailPhoto = null },
            onDelete = {
                scope.launch {
                    Graph.photoRepository.delete(photo)
                    detailPhoto = null
                }
            },
            onFlip = {
                scope.launch { Graph.photoRepository.flipImage(photo) }
                detailPhoto = null
            },
            onShare = {
                scope.launch {
                    ImageShare.shareFile(context, Graph.photoRepository.fileFor(photo), "image/jpeg")
                }
            },
            onSaveToLibrary = {
                scope.launch {
                    val ok = Graph.photoRepository.saveToLibrary(photo)
                    Analytics.log("photo_saved_to_library", mapOf("ok" to ok))
                }
            },
            onEdit = {
                editPhoto = photo
                detailPhoto = null
            }
        )
    }

    // ---- Photo metadata editor (FR-PHO-04: tray number + view angle) ----
    editPhoto?.let { photo ->
        PhotoEditDialog(
            photo = photo,
            onClose = { editPhoto = null },
            onSave = { updated ->
                scope.launch {
                    Graph.photoRepository.update(updated)
                    editPhoto = null
                }
            }
        )
    }

    // ---- Progress video speed chooser (FR-PHO-08) ----
    videoSelection?.let { chosen ->
        AlertDialog(
            onDismissRequest = { videoSelection = null },
            title = { Text("Progress video") },
            text = {
                Column {
                    Text("Select photos: ${chosen.size} · choose playback speed")
                    listOf(1 to "Slow (1s per photo)", 2 to "Normal (2s per photo)", 3 to "Fast (3s per photo)")
                        .forEach { (secs, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.RadioButton(
                                    selected = videoSpeed == secs,
                                    onClick = { videoSpeed = secs }
                                )
                                Text(label)
                            }
                        }
                    Text("a custom video of your before and after photos", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val speed = videoSpeed
                    videoSelection = null
                    scope.launch {
                        val file = ProgressVideoBuilder.build(context, chosen, speed)
                        if (file != null) ImageShare.shareFile(context, file, "video/mp4")
                    }
                }) { Text("Generate") }
            },
            dismissButton = {
                TextButton(onClick = { videoSelection = null }) { Text("Cancel") }
            }
        )
    }
}

private suspend fun currentTrayLabel(vm: MainViewModel): String {
    val settings = vm.settings.value
    val zone = com.traytimer.core.logic.DayMath.zone(true, "")
    val trays = Graph.treatmentRepository.getTrays()
    val today = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    return com.traytimer.core.logic.PlanEngine.currentTray(trays, today)?.label ?: ""
}

@Composable
private fun PhotoDetailDialog(
    photo: Teethie,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onFlip: () -> Unit,
    onShare: () -> Unit,
    onSaveToLibrary: () -> Unit,
    onEdit: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Teethie · ${photo.angle.replaceFirstChar { it.uppercase() }}") },
        text = {
            Column {
                AsyncImage(
                    model = Graph.photoRepository.fileFor(photo),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                )
                Text(
                    "Tray ${photo.trayLabel} · ${com.traytimer.data.repo.PhotoRepository.formatDay(photo.takenAtEpoch)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = onShare) { Icon(Icons.Default.Share, contentDescription = "share") }
                    IconButton(onClick = onFlip) { Icon(Icons.Default.Flip, contentDescription = "flip image") }
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "edit photo"
                        )
                    }
                    IconButton(onClick = onSaveToLibrary) {
                        Icon(
                            androidx.compose.material.icons.Icons.Filled.PhotoCamera,
                            contentDescription = "save to library"
                        )
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = {}
    )
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete photo?",
            text = "Are you sure you want to delete this photo permanently?",
            confirmLabel = "Delete",
            danger = true,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false }
        )
    }
}

/** Edit tray number and view angle (FR-PHO-04). */
@Composable
private fun PhotoEditDialog(
    photo: Teethie,
    onClose: () -> Unit,
    onSave: (Teethie) -> Unit
) {
    var trayLabel by remember { mutableStateOf(photo.trayLabel) }
    var angle by remember { mutableStateOf(PhotoRepository.Angle.fromKey(photo.angle)) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Edit photo") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = trayLabel,
                    onValueChange = { trayLabel = it.take(6) },
                    label = { Text("Tray number") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("View angle", style = MaterialTheme.typography.titleMedium)
                PhotoRepository.Angle.entries.chunked(3).forEach { rowAngles ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        rowAngles.forEach { a ->
                            androidx.compose.material3.FilterChip(
                                selected = angle == a,
                                onClick = { angle = a },
                                label = { Text(angleLabel(a)) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(photo.copy(trayLabel = trayLabel, angle = angle.key))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}

private fun angleLabel(a: PhotoRepository.Angle): String = when (a) {
    PhotoRepository.Angle.FACE_FRONT -> "Face front"
    PhotoRepository.Angle.FACE_LEFT -> "Face left"
    PhotoRepository.Angle.FACE_RIGHT -> "Face right"
    PhotoRepository.Angle.FACE_SMILE -> "Smile"
    PhotoRepository.Angle.BITE_FRONT -> "Bite front"
    PhotoRepository.Angle.BITE_LEFT -> "Bite left"
    PhotoRepository.Angle.BITE_RIGHT -> "Bite right"
    PhotoRepository.Angle.ARCH_UPPER -> "Upper arch"
    PhotoRepository.Angle.ARCH_LOWER -> "Lower arch"
}