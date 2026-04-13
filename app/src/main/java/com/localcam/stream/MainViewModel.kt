package com.localcam.stream

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.hardware.camera2.CameraCharacteristics
import androidx.lifecycle.AndroidViewModel
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val connectivityManager =
        application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val preferences: SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        readSavedSettings().withNetworkInfo(refreshNetworkInfo())
    )
    val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

    fun setCameraPermission(granted: Boolean) {
        _uiState.update {
            it.copy(
                hasCameraPermission = granted,
                errorMessage = if (granted) null else "Camera permission denied"
            )
        }
    }

    fun updatePortInput(input: String) {
        val filtered = input.filter(Char::isDigit).take(5)
        _uiState.update { current ->
            current.copy(portInput = filtered).withUrl().also(::saveSettings)
        }
    }

    fun selectResolution(preset: ResolutionPreset) {
        _uiState.update { current ->
            current.copy(selectedResolution = preset).also(::saveSettings)
        }
    }

    fun selectFps(preset: FpsPreset) {
        _uiState.update { current ->
            current.copy(selectedFps = preset).also(::saveSettings)
        }
    }

    fun selectBitrate(preset: BitratePreset) {
        _uiState.update { current ->
            current.copy(selectedBitrate = preset).also(::saveSettings)
        }
    }

    fun toggleCamera() {
        _uiState.update { current ->
            current.copy(
                selectedCameraLens = if (current.selectedCameraLens == CameraCharacteristics.LENS_FACING_BACK) {
                    CameraCharacteristics.LENS_FACING_FRONT
                } else {
                    CameraCharacteristics.LENS_FACING_BACK
                }
            ).also(::saveSettings)
        }
    }

    fun refreshLocalNetwork() {
        _uiState.update { current ->
            current.withNetworkInfo(refreshNetworkInfo())
        }
    }

    fun markStarting() {
        _uiState.update {
            it.copy(
                streamStatus = StreamStatus.STARTING,
                statusMessage = "Starting",
                errorMessage = null
            ).withNetworkInfo(refreshNetworkInfo())
        }
    }

    fun markStreaming() {
        _uiState.update {
            it.copy(
                streamStatus = StreamStatus.STREAMING,
                statusMessage = "Streaming",
                errorMessage = null
            ).withNetworkInfo(refreshNetworkInfo())
        }
    }

    fun stopStreamingState() {
        _uiState.update {
            it.copy(
                streamStatus = StreamStatus.NOT_STREAMING,
                statusMessage = "Not streaming",
                errorMessage = null
            ).withNetworkInfo(refreshNetworkInfo())
        }
    }

    fun setError(message: String) {
        _uiState.update {
            it.copy(
                streamStatus = StreamStatus.ERROR,
                statusMessage = "Error",
                errorMessage = message
            ).withNetworkInfo(refreshNetworkInfo())
        }
    }

    fun validateStartRequest(): String? {
        val state = _uiState.value
        if (!state.hasCameraPermission) return "Camera permission denied"
        if (!state.isOnLocalWifi) return "Connect the phone to the same Wi-Fi network as OBS"
        if (state.localIpAddress.isNullOrBlank()) return "No local IP found on the active local network"
        val port = state.portInput.toIntOrNull()
        if (port == null || port !in 1..65535) return "Enter a valid RTSP port between 1 and 65535"
        return null
    }

    private fun StreamUiState.withNetworkInfo(networkInfo: NetworkInfo): StreamUiState {
        return copy(
            localIpAddress = networkInfo.localIpAddress,
            isOnLocalWifi = networkInfo.isOnLocalWifi
        ).withUrl()
    }

    private fun StreamUiState.withUrl(): StreamUiState {
        val ip = localIpAddress
        val port = portInput.toIntOrNull()
        val url = if (!ip.isNullOrBlank() && port != null) {
            String.format(Locale.US, "rtsp://%s:%d/camera", ip, port)
        } else {
            ""
        }
        return copy(streamUrl = url)
    }

    private fun refreshNetworkInfo(): NetworkInfo {
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let(connectivityManager::getNetworkCapabilities)
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val localIp = findLocalIpAddress()
        return NetworkInfo(
            localIpAddress = localIp,
            isOnLocalWifi = isWifi && !localIp.isNullOrBlank()
        )
    }

    private fun findLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { address ->
                    !address.isLoopbackAddress &&
                        address.hostAddress?.contains(':') == false &&
                        address.isSiteLocalAddress
                }
                ?.hostAddress
        } catch (_: SocketException) {
            null
        }
    }

    private fun readSavedSettings(): StreamUiState {
        val savedLens = preferences.getInt(KEY_CAMERA_LENS, CameraCharacteristics.LENS_FACING_BACK)
        val savedResolution = ResolutionPreset.entries.firstOrNull {
            it.name == preferences.getString(KEY_RESOLUTION, ResolutionPreset.HD.name)
        } ?: ResolutionPreset.HD
        val savedFps = FpsPreset.entries.firstOrNull {
            it.name == preferences.getString(KEY_FPS, FpsPreset.FPS_30.name)
        } ?: FpsPreset.FPS_30
        val savedBitrate = BitratePreset.entries.firstOrNull {
            it.name == preferences.getString(KEY_BITRATE, BitratePreset.MBPS_6.name)
        } ?: BitratePreset.MBPS_6
        val savedPort = preferences.getString(KEY_PORT, "8554").orEmpty().ifBlank { "8554" }

        return StreamUiState(
            selectedCameraLens = if (savedLens == CameraCharacteristics.LENS_FACING_FRONT) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            },
            selectedResolution = savedResolution,
            selectedFps = savedFps,
            selectedBitrate = savedBitrate,
            portInput = savedPort
        )
    }

    private fun saveSettings(state: StreamUiState) {
        preferences.edit()
            .putInt(KEY_CAMERA_LENS, state.selectedCameraLens)
            .putString(KEY_RESOLUTION, state.selectedResolution.name)
            .putString(KEY_FPS, state.selectedFps.name)
            .putString(KEY_BITRATE, state.selectedBitrate.name)
            .putString(KEY_PORT, state.portInput)
            .apply()
    }
}

private data class NetworkInfo(
    val localIpAddress: String?,
    val isOnLocalWifi: Boolean
)

private const val PREFS_NAME = "localcam_stream_settings"
private const val KEY_CAMERA_LENS = "camera_lens"
private const val KEY_RESOLUTION = "resolution"
private const val KEY_FPS = "fps"
private const val KEY_BITRATE = "bitrate"
private const val KEY_PORT = "port"
