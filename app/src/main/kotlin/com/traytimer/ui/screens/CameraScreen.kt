package com.traytimer.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.traytimer.core.Graph
import com.traytimer.core.logic.Analytics
import com.traytimer.data.repo.PhotoRepository
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * In-app guided camera (FR-PHO-01/02): 9 standard angles, mirrored selfie mode,
 * front/back camera flip, guidance overlay.
 */
@Composable
fun CameraScreen(modifier: Modifier = Modifier, onDone: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasPermission = it }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var angle by remember { mutableStateOf(PhotoRepository.Angle.FACE_FRONT) }
    var mirrored by remember { mutableStateOf(false) }
    var useFront by remember { mutableStateOf(true) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(Unit) {
        mirrored = Graph.settingsStore.current().mirroredSelfie
    }

    // (Re)bind camera whenever the selected camera changes
    LaunchedEffect(useFront, hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        val future = ProcessCameraProvider.getInstance(context)
        val cameraProvider = future.get()
        provider = cameraProvider
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA
        val bound = runCatching {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
        }
        if (bound.isFailure && useFront) {
            runCatching {
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { provider?.unbindAll() }
        }
    }

    if (!hasPermission) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera permission is needed to take Teethies.")
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant permission")
                }
                androidx.compose.material3.TextButton(onClick = onDone) { Text("Cancel") }
            }
        }
        return
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Angle guide overlay (FR-PHO-01)
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(12.dp)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = angleLabel(angle) + " — " +
                        "Find even lighting and hold the camera steady. Take a new Teethie every week to see your progress.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(PhotoRepository.Angle.entries) { a ->
                    FilterChip(
                        selected = a == angle,
                        onClick = { angle = a },
                        label = { Text(angleLabel(a), color = Color.White) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            containerColor = Color.Black.copy(alpha = 0.5f),
                            selectedContainerColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }

        // Bottom controls
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { useFront = !useFront }) {
                Icon(Icons.Default.Cameraswitch, contentDescription = "flip camera", tint = Color.White)
            }
            FloatingActionButton(
                onClick = { capture(imageCapture, angle, mirrored, scope, onDone) },
                shape = CircleShape,
                containerColor = Color.White
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = "capture", tint = Color.Black)
            }
            IconButton(onClick = { mirrored = !mirrored }) {
                Icon(Icons.Default.SwapHoriz, contentDescription = "mirror", tint = if (mirrored) Color(0xFF5EEAD4) else Color.White)
            }
        }

        androidx.compose.material3.TextButton(
            onClick = onDone,
            modifier = Modifier.align(Alignment.TopEnd)
        ) { Text("Close", color = Color.White) }
    }
}

private fun capture(
    imageCapture: ImageCapture,
    angle: PhotoRepository.Angle,
    mirrored: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    onDone: () -> Unit
) {
    imageCapture.takePicture(
        ContextCompat.getMainExecutor(Graph.appContext),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                image.close()
                // Apply mirroring when requested (FR-PHO-02)
                val processed = if (mirrored) mirrorJpeg(bytes) else bytes
                scope.launch {
                    val now = System.currentTimeMillis()
                    Graph.photoRepository.addPhoto(
                        processed, now,
                        currentTrayLabelForCapture(),
                        angle,
                        mirrored
                    )
                    Analytics.log("teethie_captured", mapOf("angle" to angle.key))
                    onDone()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Analytics.log("teethie_capture_failed", mapOf("error" to exception.message))
                onDone()
            }
        }
    )
}

private suspend fun currentTrayLabelForCapture(): String {
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    val trays = Graph.treatmentRepository.getTrays()
    return com.traytimer.core.logic.PlanEngine.currentTray(trays, today)?.label ?: ""
}

private fun mirrorJpeg(bytes: ByteArray): ByteArray {
    return runCatching {
        var bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val matrix = android.graphics.Matrix().apply { setScale(-1f, 1f) }
        val flipped = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        val out = java.io.ByteArrayOutputStream()
        flipped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
        if (flipped != bmp) flipped.recycle()
        bmp.recycle()
        out.toByteArray()
    }.getOrDefault(bytes)
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