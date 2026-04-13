package com.localcam.stream

import android.hardware.camera2.CameraCharacteristics

enum class StreamStatus {
    NOT_STREAMING,
    STARTING,
    STREAMING,
    ERROR
}

enum class ResolutionPreset(
    val label: String,
    val width: Int,
    val height: Int
) {
    HD("1280 x 720", 1280, 720),
    FULL_HD("1920 x 1080", 1920, 1080);

    val aspectRatioLabel: String = "16:9"
}

enum class FpsPreset(
    val label: String,
    val fps: Int
) {
    FPS_24("24 FPS", 24),
    FPS_30("30 FPS", 30)
}

enum class BitratePreset(
    val label: String,
    val bitrateMbps: Int,
    val bitrate: Int
) {
    MBPS_4("4 Mbps", 4, 4_000_000),
    MBPS_6("6 Mbps", 6, 6_000_000),
    MBPS_8("8 Mbps", 8, 8_000_000)
}

data class StreamUiState(
    val hasCameraPermission: Boolean = false,
    val streamStatus: StreamStatus = StreamStatus.NOT_STREAMING,
    val selectedCameraLens: Int = CameraCharacteristics.LENS_FACING_BACK,
    val selectedResolution: ResolutionPreset = ResolutionPreset.HD,
    val selectedFps: FpsPreset = FpsPreset.FPS_30,
    val selectedBitrate: BitratePreset = BitratePreset.MBPS_6,
    val portInput: String = "8554",
    val localIpAddress: String? = null,
    val isOnLocalWifi: Boolean = false,
    val streamUrl: String = "",
    val statusMessage: String = "Not streaming",
    val errorMessage: String? = null
) {
    val isStreaming: Boolean = streamStatus == StreamStatus.STREAMING
    val isStarting: Boolean = streamStatus == StreamStatus.STARTING
}
