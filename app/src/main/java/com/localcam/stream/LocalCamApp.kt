package com.localcam.stream

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localcam.stream.streaming.AspectRatioTextureView
import com.localcam.stream.streaming.CameraStreamManager
import com.localcam.stream.ui.theme.LocalCamStreamTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalCamApp(viewModel: MainViewModel) {
    LocalCamStreamTheme {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val clipboardManager = LocalClipboardManager.current
        val coroutineScope = rememberCoroutineScope()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        val latestUiState by rememberUpdatedState(uiState)
        val previewView = remember {
            AspectRatioTextureView(context).apply {
                keepScreenOn = false
            }
        }
        var zoomRatio by remember { mutableStateOf(1f) }
        var maxZoomRatio by remember { mutableStateOf(1f) }
        val cameraManager = remember {
            CameraStreamManager(context.applicationContext)
        }
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted ->
            viewModel.setCameraPermission(granted)
            if (granted) {
                viewModel.refreshLocalNetwork()
            }
        }

        LaunchedEffect(Unit) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            viewModel.setCameraPermission(granted)
            viewModel.refreshLocalNetwork()
            if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP && latestUiState.isStreaming) {
                    coroutineScope.launch {
                        cameraManager.stopStreaming(closeCameraAfterStop = true)
                        viewModel.stopStreamingState()
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                coroutineScope.launch {
                    cameraManager.shutdown()
                }
            }
        }

        DisposableEffect(uiState.isStreaming) {
            previewView.keepScreenOn = uiState.isStreaming
            previewView.setScaleMode(
                if (uiState.isStreaming || uiState.isStarting) {
                    AspectRatioTextureView.ScaleMode.FILL
                } else {
                    AspectRatioTextureView.ScaleMode.FIT
                }
            )
            onDispose { previewView.keepScreenOn = false }
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                PreviewSurface(
                    uiState = uiState,
                    previewView = previewView,
                    maxZoomRatio = maxZoomRatio,
                    onPinchZoom = { scale ->
                        zoomRatio = cameraManager.scaleZoomBy(scale)
                    }
                )

                if (uiState.isStreaming || uiState.isStarting) {
                    StreamingOverlay(
                        uiState = uiState,
                        zoomRatio = zoomRatio,
                        onStopStream = {
                            coroutineScope.launch {
                                cameraManager.stopStreaming(closeCameraAfterStop = true)
                                zoomRatio = cameraManager.getCurrentZoomRatio()
                                maxZoomRatio = cameraManager.getMaxZoomRatio()
                                viewModel.stopStreamingState()
                            }
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF020617))
                    )
                    NormalOverlay(
                        uiState = uiState,
                        onCopyUrl = {
                            if (uiState.streamUrl.isNotBlank()) {
                                clipboardManager.setText(AnnotatedString(uiState.streamUrl))
                            }
                        },
                        onRefreshIp = viewModel::refreshLocalNetwork,
                        onPortChanged = viewModel::updatePortInput,
                        onResolutionSelected = viewModel::selectResolution,
                        onFpsSelected = viewModel::selectFps,
                        onBitrateSelected = viewModel::selectBitrate,
                        onSwitchCamera = {
                            if (!uiState.isStreaming && !uiState.isStarting) {
                                viewModel.toggleCamera()
                            }
                        },
                        onRequestPermission = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        onStartStream = {
                            coroutineScope.launch {
                                viewModel.refreshLocalNetwork()
                                val validationError = viewModel.validateStartRequest()
                                if (validationError != null) {
                                    viewModel.setError(validationError)
                                    return@launch
                                }

                                val port = uiState.portInput.toIntOrNull() ?: return@launch
                                val localIp = uiState.localIpAddress ?: return@launch
                                viewModel.markStarting()

                                runCatching {
                                    cameraManager.attachPreview(previewView)
                                    cameraManager.startStreaming(
                                        lensFacing = uiState.selectedCameraLens,
                                        resolution = uiState.selectedResolution,
                                        fps = uiState.selectedFps,
                                        bitrate = uiState.selectedBitrate,
                                        port = port,
                                        hostAddress = localIp
                                    )
                                }.onSuccess {
                                    zoomRatio = cameraManager.getCurrentZoomRatio()
                                    maxZoomRatio = cameraManager.getMaxZoomRatio()
                                    viewModel.markStreaming()
                                }.onFailure { throwable ->
                                    viewModel.setError(throwable.message ?: "Failed to start RTSP stream")
                                }
                            }
                        },
                        onStopStream = {
                            coroutineScope.launch {
                                cameraManager.stopStreaming(closeCameraAfterStop = true)
                                zoomRatio = cameraManager.getCurrentZoomRatio()
                                maxZoomRatio = cameraManager.getMaxZoomRatio()
                                viewModel.stopStreamingState()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewSurface(
    uiState: StreamUiState,
    previewView: AspectRatioTextureView,
    maxZoomRatio: Float,
    onPinchZoom: (Float) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(uiState.isStreaming, maxZoomRatio) {
                if (uiState.isStreaming && maxZoomRatio > 1f) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) onPinchZoom(zoom)
                    }
                }
            }
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun StreamingOverlay(
    uiState: StreamUiState,
    zoomRatio: Float,
    onStopStream: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.TopStart)
                .padding(18.dp)
                .background(Color(0x99000000), RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text(
                text = uiState.statusMessage,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge
            )
        }

        Box(
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomStart)
                .padding(18.dp)
                .background(Color(0x99000000), RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text(
                text = String.format(java.util.Locale.US, "%.1fx", zoomRatio),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge
            )
        }

        OutlinedButton(
            onClick = onStopStream,
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.TopEnd)
                .padding(18.dp),
            shape = RoundedCornerShape(999.dp)
        ) {
            Icon(Icons.Default.Stop, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text("Stop")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NormalOverlay(
    uiState: StreamUiState,
    onCopyUrl: () -> Unit,
    onRefreshIp: () -> Unit,
    onPortChanged: (String) -> Unit,
    onResolutionSelected: (ResolutionPreset) -> Unit,
    onFpsSelected: (FpsPreset) -> Unit,
    onBitrateSelected: (BitratePreset) -> Unit,
    onSwitchCamera: () -> Unit,
    onRequestPermission: () -> Unit,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit
) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0x22000000),
                        Color(0x990F172A),
                        Color(0xEE0F172A)
                    )
                )
            )
    ) {
        TopAppBar(
            title = {
                Column {
                    Text("LocalCam Stream", fontWeight = FontWeight.Bold)
                    Text(
                        text = uiState.statusMessage,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor(uiState.streamStatus)
                    )
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PreviewPlaceholderCard(
                resolution = uiState.selectedResolution,
                lensFacing = uiState.selectedCameraLens
            )
            StreamInfoCard(
                uiState = uiState,
                onCopyUrl = onCopyUrl,
                onRefreshIp = onRefreshIp
            )
            ControlsCard(
                uiState = uiState,
                onPortChanged = onPortChanged,
                onResolutionSelected = onResolutionSelected,
                onFpsSelected = onFpsSelected,
                onBitrateSelected = onBitrateSelected,
                onSwitchCamera = onSwitchCamera,
                onRequestPermission = onRequestPermission,
                onStartStream = onStartStream,
                onStopStream = onStopStream
            )
            AboutCard(
                onOpenWebsite = {
                    uriHandler.openUri("https://www.dira.lk")
                }
            )
        }
    }
}

@Composable
private fun PreviewPlaceholderCard(
    resolution: ResolutionPreset,
    lensFacing: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(Color(0xFF020617), Color(0xFF111827), Color(0xFF0F172A))
                        )
                    )
                    .padding(18.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(text = "Camera opens on Start")
                    StatusChip(text = resolution.aspectRatioLabel)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Standby Preview",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                            "Back camera stays closed until you tap Start Stream."
                        } else {
                            "Front camera stays closed until you tap Start Stream."
                        },
                        color = Color(0xFFD1D5DB),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Box(
                    modifier = Modifier
                        .background(Color(0x1AFFFFFF), RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "${resolution.label} • RTSP low-latency mode",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(text: String) {
    Box(
        modifier = Modifier
            .background(Color(0x1AFFFFFF), RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun StreamInfoCard(
    uiState: StreamUiState,
    onCopyUrl: () -> Unit,
    onRefreshIp: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("RTSP Stream", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (uiState.streamUrl.isBlank()) "Connect to Wi-Fi to generate the RTSP URL" else uiState.streamUrl,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "Local IP: ${uiState.localIpAddress ?: "Unavailable"}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = if (uiState.isOnLocalWifi) "Wi-Fi ready for OBS" else "Phone is not on local Wi-Fi",
                style = MaterialTheme.typography.bodyMedium,
                color = if (uiState.isOnLocalWifi) Color(0xFF10B981) else MaterialTheme.colorScheme.error
            )
            if (uiState.errorMessage != null) {
                Text(
                    text = uiState.errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onCopyUrl, enabled = uiState.streamUrl.isNotBlank()) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Copy URL")
                }
                TextButton(onClick = onRefreshIp) {
                    Text("Refresh Network")
                }
            }
        }
    }
}

@Composable
private fun AboutCard(
    onOpenWebsite: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = "Developed by Dileepa Rajapakshe",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFE5E7EB)
            )
            Text(
                text = "Website: www.dira.lk",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFBFDBFE)
            )
            Text(
                text = "App Version: ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFD1D5DB)
            )
            TextButton(onClick = onOpenWebsite) {
                Text("Open Website")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlsCard(
    uiState: StreamUiState,
    onPortChanged: (String) -> Unit,
    onResolutionSelected: (ResolutionPreset) -> Unit,
    onFpsSelected: (FpsPreset) -> Unit,
    onBitrateSelected: (BitratePreset) -> Unit,
    onSwitchCamera: () -> Unit,
    onRequestPermission: () -> Unit,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit
) {
    val controlsEnabled = !uiState.isStreaming && !uiState.isStarting

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = uiState.portInput,
                onValueChange = onPortChanged,
                label = { Text("RTSP Port") },
                modifier = Modifier.fillMaxWidth(),
                enabled = controlsEnabled
            )
            PresetDropdown(
                label = "Resolution",
                options = ResolutionPreset.entries,
                selectedOption = uiState.selectedResolution,
                optionLabel = { "${it.label} • ${it.aspectRatioLabel}" },
                enabled = controlsEnabled && uiState.hasCameraPermission,
                onOptionSelected = onResolutionSelected
            )
            PresetDropdown(
                label = "FPS",
                options = FpsPreset.entries,
                selectedOption = uiState.selectedFps,
                optionLabel = { it.label },
                enabled = controlsEnabled && uiState.hasCameraPermission,
                onOptionSelected = onFpsSelected
            )
            PresetDropdown(
                label = "Bitrate",
                options = BitratePreset.entries,
                selectedOption = uiState.selectedBitrate,
                optionLabel = { it.label },
                enabled = controlsEnabled && uiState.hasCameraPermission,
                onOptionSelected = onBitrateSelected
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = onSwitchCamera, enabled = controlsEnabled && uiState.hasCameraPermission) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (uiState.selectedCameraLens == CameraCharacteristics.LENS_FACING_BACK) {
                            "Switch to Front"
                        } else {
                            "Switch to Back"
                        }
                    )
                }
                FilterChip(
                    selected = uiState.selectedCameraLens == CameraCharacteristics.LENS_FACING_BACK,
                    onClick = { },
                    enabled = false,
                    label = { Text(if (uiState.selectedCameraLens == CameraCharacteristics.LENS_FACING_BACK) "Back Camera" else "Front Camera") }
                )
                FilterChip(
                    selected = uiState.isStreaming,
                    onClick = { },
                    enabled = false,
                    label = { Text(if (uiState.isStreaming) "Low-Latency H.264" else "Ready") }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        if (uiState.hasCameraPermission) {
                            onStartStream()
                        } else {
                            onRequestPermission()
                        }
                    },
                    enabled = controlsEnabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Start Stream")
                }
                OutlinedButton(
                    onClick = onStopStream,
                    enabled = uiState.isStreaming || uiState.isStarting,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Stop Stream")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> PresetDropdown(
    label: String,
    options: List<T>,
    selectedOption: T,
    optionLabel: (T) -> String,
    enabled: Boolean,
    onOptionSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded }
    ) {
        OutlinedTextField(
            value = optionLabel(selectedOption),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onOptionSelected(option)
                    }
                )
            }
        }
    }
}

@Composable
private fun statusColor(status: StreamStatus): Color {
    return when (status) {
        StreamStatus.NOT_STREAMING -> MaterialTheme.colorScheme.onSurfaceVariant
        StreamStatus.STARTING -> Color(0xFFF59E0B)
        StreamStatus.STREAMING -> Color(0xFF10B981)
        StreamStatus.ERROR -> MaterialTheme.colorScheme.error
    }
}
