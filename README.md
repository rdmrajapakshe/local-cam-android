# LocalCam Stream

LocalCam Stream is an Android app built with Kotlin and Jetpack Compose that streams the phone camera over the local Wi-Fi network using a low-latency RTSP + H.264 pipeline.

## Streaming method

- Protocol: RTSP over TCP
- Video codec: H.264 / AVC
- Encoder: Android MediaCodec hardware encoder
- Camera stack: Camera2
- Default stream URL: `rtsp://PHONE_IP:8554/camera`

This version replaces MJPEG-over-HTTP with a hardware-encoded H.264 stream, which is far more suitable for smooth live camera transfer into OBS on the same local network.

## Defaults

- Resolution: `1280x720`
- Aspect ratio: `16:9`
- FPS: `30`
- Bitrate: `6 Mbps`
- Codec: `H.264`
- Camera: back camera
- Port: `8554`

## Requirements

- Android Studio Hedgehog or newer
- Android SDK Platform 35
- Java 17
- Android phone and OBS computer connected to the same Wi-Fi network

## Build From Terminal

From the project root:

```bash
./gradlew assembleDebug
./gradlew installDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install Debug APK

With Gradle:

```bash
./gradlew installDebug
```

With ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Run The App

1. Open **LocalCam Stream** on the phone.
2. Grant the camera permission.
3. Confirm the app shows a local Wi-Fi IP.
4. Choose resolution, FPS, bitrate, camera, and port.
5. Tap **Start Stream**.
6. Copy the exact RTSP URL shown in the app, for example:

```text
rtsp://192.168.1.20:8554/camera
```

The app keeps the screen awake while streaming and uses landscape orientation to keep a consistent 16:9 output path.

## OBS Setup

### Best option: Media Source

1. In OBS, click `+` in **Sources**.
2. Choose **Media Source**.
3. Create a new source.
4. Disable **Local File**.
5. Paste the RTSP URL from the phone, such as `rtsp://192.168.1.20:8554/camera`.
6. If needed, set **Input** to use network buffering as low as your setup allows.
7. Click **OK**.

### Alternative: VLC Video Source

If your OBS build handles RTSP more reliably through VLC:

1. Install VLC on the OBS computer.
2. Add **VLC Video Source** in OBS.
3. Add the RTSP URL `rtsp://PHONE_IP:PORT/camera`.
4. Confirm the live preview appears.

## Same Wi-Fi Checklist

1. Connect the Android phone to your local Wi-Fi.
2. Connect the OBS computer to the same router or access point.
3. Make sure both devices have local private IPs in the same subnet.
4. If OBS cannot connect, verify the phone stays on-screen and the stream is still marked as **Streaming** in the app.

## Why RTSP + H.264 Is Better Than MJPEG

- MJPEG sends every frame as a full JPEG image, which wastes bandwidth and creates more CPU work.
- H.264 uses inter-frame compression and hardware encoding, so the same picture quality needs much less data.
- MediaCodec avoids per-frame bitmap/JPEG conversions on the CPU.
- RTSP is designed for live media transport and is a much better fit for OBS than an HTTP MJPEG stream.

## Project Notes

- The stream is local-network only.
- No cloud relay or external backend is required.
- The app is optimized for 16:9 modes: `1280x720` and `1920x1080`.
- The app shows the exact RTSP URL needed by OBS.
- Port conflicts, missing Wi-Fi, missing IP, and camera permission errors are surfaced in the UI.
# local-cam-android
