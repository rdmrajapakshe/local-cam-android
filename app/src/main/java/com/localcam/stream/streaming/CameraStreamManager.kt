package com.localcam.stream.streaming

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.net.BindException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraStreamManager(context: Context) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraThread = HandlerThread("LocalCamCamera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()

    private var textureView: AspectRatioTextureView? = null
    private var surfaceReady = CompletableDeferred<Unit>()
    private var activeCameraId: String? = null
    private var activeLensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    private var activeResolution = Size(1280, 720)
    private var activeFps: Int = 30
    private var activeBitrate: Int = 6_000_000
    private var currentSensorRect: Rect? = null
    private var currentRequestBuilder: CaptureRequest.Builder? = null
    private var currentZoomRatio = 1f
    private var maxZoomRatio = 1f

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var videoSurface: Surface? = null
    private var mediaCodec: MediaCodec? = null
    private var encoderJob: kotlinx.coroutines.Job? = null
    private var rtspServer: RtspServer? = null
    private var isStreaming = false

    fun attachPreview(view: AspectRatioTextureView) {
        if (textureView === view) return
        textureView = view
        surfaceReady = CompletableDeferred()
        view.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                surfaceReady.complete(Unit)
                applyPreviewTransform()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                applyPreviewTransform()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                previewSurface?.release()
                previewSurface = null
                surfaceReady = CompletableDeferred()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        if (view.isAvailable) {
            surfaceReady.complete(Unit)
            applyPreviewTransform()
        }
    }

    fun getCurrentZoomRatio(): Float = currentZoomRatio

    fun getMaxZoomRatio(): Float = maxZoomRatio

    fun scaleZoomBy(scaleFactor: Float): Float {
        val sensorRect = currentSensorRect ?: return currentZoomRatio
        val clampedScale = scaleFactor.coerceIn(0.9f, 1.1f)
        val newRatio = (currentZoomRatio * clampedScale).coerceIn(1f, maxZoomRatio)
        currentZoomRatio = newRatio
        val cropRegion = computeCropRegion(sensorRect, newRatio)
        cameraHandler.post {
            currentRequestBuilder?.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
            currentRequestBuilder?.build()?.let { request ->
                runCatching {
                    captureSession?.setRepeatingRequest(request, null, cameraHandler)
                }.onFailure {
                    Log.w(TAG_VIDEO_ENCODER, "Failed to apply zoom: ${it.message}")
                }
            }
        }
        return currentZoomRatio
    }

    fun resetZoom() {
        currentZoomRatio = 1f
        currentSensorRect = currentSensorRect
        cameraHandler.post {
            currentRequestBuilder?.set(CaptureRequest.SCALER_CROP_REGION, currentSensorRect)
            currentRequestBuilder?.build()?.let { request ->
                runCatching {
                    captureSession?.setRepeatingRequest(request, null, cameraHandler)
                }
            }
        }
    }

    suspend fun updatePreview(lensFacing: Int, resolution: com.localcam.stream.ResolutionPreset) {
        stateMutex.withLock {
            activeLensFacing = lensFacing
            activeResolution = Size(resolution.width, resolution.height)
            if (isStreaming) return
            openCameraAndStartSession(includeEncoder = false)
        }
    }

    suspend fun startStreaming(
        lensFacing: Int,
        resolution: com.localcam.stream.ResolutionPreset,
        fps: com.localcam.stream.FpsPreset,
        bitrate: com.localcam.stream.BitratePreset,
        port: Int,
        hostAddress: String
    ): String {
        return stateMutex.withLock {
            activeLensFacing = lensFacing
            activeResolution = Size(resolution.width, resolution.height)
            activeFps = fps.fps
            activeBitrate = bitrate.bitrate

            stopStreamingInternal(restartPreview = false)

            val server = RtspServer(
                port = port,
                hostAddress = hostAddress,
                streamPath = "/camera"
            )

            val formatReady = CompletableDeferred<Unit>()
            try {
                server.start()
                rtspServer = server
                Log.i(TAG_VIDEO_ENCODER, "Preparing encoder for ${activeResolution.width}x${activeResolution.height} @ ${activeFps}fps bitrate=${activeBitrate}")
                prepareEncoder(
                    width = activeResolution.width,
                    height = activeResolution.height,
                    fps = activeFps,
                    bitrate = activeBitrate,
                    onFormatReady = { sps, pps ->
                        server.updateStreamInfo(
                            width = activeResolution.width,
                            height = activeResolution.height,
                            fps = activeFps,
                            sps = sps,
                            pps = pps
                        )
                        Log.i(TAG_VIDEO_ENCODER, "Encoder output format ready; SPS/PPS published to RTSP server")
                        if (!formatReady.isCompleted) formatReady.complete(Unit)
                    },
                    onEncodedData = { accessUnit, ptsUs, isKeyFrame ->
                        server.dispatchSample(accessUnit, ptsUs, isKeyFrame)
                    }
                )
                isStreaming = true
                openCameraAndStartSession(includeEncoder = true)
                withTimeout(5_000) { formatReady.await() }
                Log.i(TAG_VIDEO_ENCODER, "Encoder and RTSP server started successfully")
            } catch (error: BindException) {
                Log.e(TAG_VIDEO_ENCODER, "RTSP port $port is already in use", error)
                stopStreamingInternal(restartPreview = false)
                throw IllegalStateException("Port $port is already in use")
            } catch (error: Exception) {
                Log.e(TAG_VIDEO_ENCODER, "Failed to start encoder/stream: ${error.message}", error)
                stopStreamingInternal(restartPreview = false)
                throw IllegalStateException(buildStartFailureMessage(error), error)
            }

            "rtsp://$hostAddress:$port/camera"
        }
    }

    suspend fun stopStreaming() {
        stopStreaming(closeCameraAfterStop = true)
    }

    suspend fun stopStreaming(closeCameraAfterStop: Boolean) {
        stateMutex.withLock {
            stopStreamingInternal(restartPreview = !closeCameraAfterStop)
            if (closeCameraAfterStop) {
                closeCamera()
                resetZoomState()
            }
        }
    }

    suspend fun shutdown() {
        stateMutex.withLock {
            stopStreamingInternal(restartPreview = false)
            closeCamera()
            cameraThread.quitSafely()
        }
    }

    private suspend fun stopStreamingInternal(restartPreview: Boolean) {
        isStreaming = false
        encoderJob?.cancelAndJoin()
        encoderJob = null
        runCatching { mediaCodec?.stop() }
        runCatching { mediaCodec?.release() }
        mediaCodec = null
        runCatching { videoSurface?.release() }
        videoSurface = null
        rtspServer?.stop()
        rtspServer = null

        if (restartPreview && textureView != null) {
            openCameraAndStartSession(includeEncoder = false)
        } else {
            captureSession?.close()
            captureSession = null
            currentRequestBuilder = null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCameraAndStartSession(includeEncoder: Boolean) {
        awaitPreviewSurface()
        val selectedCameraId = findCameraId(activeLensFacing)
            ?: throw IllegalStateException("Requested camera is not available")

        if (activeCameraId != selectedCameraId) {
            closeCamera()
            activeCameraId = selectedCameraId
        }

        val characteristics = cameraManager.getCameraCharacteristics(selectedCameraId)
        val selectedSize = chooseVideoSize(
            configurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IllegalStateException("Camera stream configuration unavailable"),
            requested = activeResolution
        )
        activeResolution = selectedSize
        currentSensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        maxZoomRatio = maxOf(
            1f,
            characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        )
        currentZoomRatio = currentZoomRatio.coerceIn(1f, maxZoomRatio)
        textureView?.setAspectRatio(selectedSize.width, selectedSize.height)
        applyPreviewTransform()

        val device = cameraDevice ?: suspendCancellableCoroutine { continuation ->
            cameraManager.openCamera(selectedCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    continuation.resume(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("Camera disconnected"))
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("Camera error: $error"))
                    }
                }
            }, cameraHandler)
        }

        previewSurface?.release()
        previewSurface = createPreviewSurface(selectedSize)
        val surfaces = buildList {
            add(previewSurface ?: throw IllegalStateException("Preview surface unavailable"))
            if (includeEncoder) {
                add(videoSurface ?: throw IllegalStateException("Encoder surface unavailable"))
            }
        }

        captureSession?.close()
        captureSession = suspendCancellableCoroutine { continuation ->
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    continuation.resume(session)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    continuation.resumeWithException(IllegalStateException("Failed to configure camera capture session"))
                }
            }, cameraHandler)
        }

        val template = if (includeEncoder) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        val requestBuilder = device.createCaptureRequest(template).apply {
            surfaces.forEach(::addTarget)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            chooseFpsRange(characteristics, activeFps)?.let {
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
            currentSensorRect?.let { set(CaptureRequest.SCALER_CROP_REGION, computeCropRegion(it, currentZoomRatio)) }
        }
        currentRequestBuilder = requestBuilder
        captureSession?.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
    }

    private fun prepareEncoder(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        onFormatReady: (ByteArray, ByteArray) -> Unit,
        onEncodedData: (ByteArray, Long, Boolean) -> Unit
    ) {
        val codec = configureEncoderWithFallback(width, height, fps, bitrate)
        videoSurface = codec.createInputSurface()
        codec.start()
        mediaCodec = codec

        encoderJob = scope.launch {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isActive && isStreaming) {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        val sps = newFormat.getByteBuffer("csd-0")?.toByteArrayWithoutStartCode()
                        val pps = newFormat.getByteBuffer("csd-1")?.toByteArrayWithoutStartCode()
                        if (sps != null && pps != null) onFormatReady(sps, pps)
                    }

                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val accessUnit = ByteArray(bufferInfo.size)
                            outputBuffer.get(accessUnit)
                            val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            val codecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (!codecConfig) {
                                onEncodedData(accessUnit, bufferInfo.presentationTimeUs, isKeyFrame)
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            Log.i(TAG_VIDEO_ENCODER, "Encoder output loop finished")
        }
    }

    private fun configureEncoderWithFallback(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int
    ): MediaCodec {
        val attempts = listOf(
            EncoderConfigAttempt(
                label = "low-latency-high-profile",
                optionalConfig = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        setInteger(MediaFormat.KEY_PRIORITY, 0)
                        setFloat(MediaFormat.KEY_OPERATING_RATE, fps.toFloat())
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                        setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
                    }
                }
            ),
            EncoderConfigAttempt(
                label = "low-latency-baseline",
                optionalConfig = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        setInteger(MediaFormat.KEY_PRIORITY, 0)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                }
            ),
            EncoderConfigAttempt(
                label = "compatible-cbr",
                optionalConfig = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        setInteger(MediaFormat.KEY_PRIORITY, 0)
                    }
                }
            ),
            EncoderConfigAttempt(
                label = "minimal-h264",
                optionalConfig = { }
            )
        )

        var lastError: Exception? = null
        attempts.forEach { attempt ->
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    attempt.optionalConfig(this)
                }
                Log.i(TAG_VIDEO_ENCODER, "Trying encoder config: ${attempt.label}")
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                Log.i(TAG_VIDEO_ENCODER, "Encoder configured with: ${attempt.label}")
                return codec
            } catch (error: Exception) {
                lastError = error
                Log.w(TAG_VIDEO_ENCODER, "Encoder config failed: ${attempt.label}: ${error.message}")
                runCatching { codec.release() }
            }
        }

        throw IllegalStateException("No compatible H.264 encoder configuration found", lastError)
    }

    private fun buildStartFailureMessage(error: Exception): String {
        val message = error.message?.takeIf { it.isNotBlank() } ?: return "Failed to start RTSP stream"
        return when {
            "No compatible H.264 encoder configuration found" in message ->
                "This phone rejected the H.264 encoder settings. Try 1280x720 at 30 FPS."
            "Failed to configure camera capture session" in message ->
                "Camera session could not start. Close other camera apps and try again."
            "Preview texture unavailable" in message ->
                "Preview surface is not ready yet. Try again in a moment."
            "Camera disconnected" in message ->
                "Camera disconnected while starting. Try again."
            else -> message
        }
    }

    private data class EncoderConfigAttempt(
        val label: String,
        val optionalConfig: MediaFormat.() -> Unit
    )

    private suspend fun awaitPreviewSurface() {
        if (textureView == null) throw IllegalStateException("Preview view not attached")
        surfaceReady.await()
    }

    private fun createPreviewSurface(size: Size): Surface {
        val texture = textureView?.surfaceTexture ?: throw IllegalStateException("Preview texture unavailable")
        texture.setDefaultBufferSize(size.width, size.height)
        return Surface(texture)
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        currentRequestBuilder = null
        cameraDevice?.close()
        cameraDevice = null
    }

    private fun resetZoomState() {
        currentZoomRatio = 1f
        currentSensorRect = null
        maxZoomRatio = 1f
    }

    private fun findCameraId(lensFacing: Int): String? {
        return cameraManager.cameraIdList.firstOrNull { cameraId ->
            cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.LENS_FACING) == lensFacing
        }
    }

    private fun chooseVideoSize(configurationMap: StreamConfigurationMap, requested: Size): Size {
        val sizes = configurationMap.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        return sizes.firstOrNull { it.width == requested.width && it.height == requested.height }
            ?: sizes
                .filter { it.width * 9 == it.height * 16 }
                .minByOrNull { kotlin.math.abs(it.width - requested.width) + kotlin.math.abs(it.height - requested.height) }
            ?: requested
    }

    private fun chooseFpsRange(characteristics: CameraCharacteristics, targetFps: Int): Range<Int>? {
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        return ranges.minByOrNull { range ->
            kotlin.math.abs(range.upper - targetFps) + kotlin.math.abs(range.lower - targetFps)
        }
    }

    private fun applyPreviewTransform() {
        val view = textureView ?: return
        if (!view.isAvailable) return
        val previewSize = activeResolution
        val rotation = view.display?.rotation ?: Surface.ROTATION_0
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(
                view.height.toFloat() / previewSize.height,
                view.width.toFloat() / previewSize.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, centerX, centerY)
        }

        view.setTransform(matrix)
    }

    private fun computeCropRegion(sensorRect: Rect, zoomRatio: Float): Rect {
        if (zoomRatio <= 1f) return Rect(sensorRect)
        val centerX = sensorRect.centerX()
        val centerY = sensorRect.centerY()
        val deltaX = (0.5f * sensorRect.width() / zoomRatio).toInt()
        val deltaY = (0.5f * sensorRect.height() / zoomRatio).toInt()
        return Rect(
            centerX - deltaX,
            centerY - deltaY,
            centerX + deltaX,
            centerY + deltaY
        )
    }
}

private const val TAG_VIDEO_ENCODER = "VIDEO_ENCODER"

private fun java.nio.ByteBuffer.toByteArrayWithoutStartCode(): ByteArray {
    val source = duplicate()
    val bytes = ByteArray(source.remaining())
    source.get(bytes)
    return if (bytes.size > 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
        ((bytes[2] == 0.toByte() && bytes[3] == 1.toByte()) || bytes[2] == 1.toByte())
    ) {
        val startCodeLength = if (bytes[2] == 1.toByte()) 3 else 4
        bytes.copyOfRange(startCodeLength, bytes.size)
    } else {
        bytes
    }
}
