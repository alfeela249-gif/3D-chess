# 3D Chess for Android

A native Kotlin/OpenGL ES Android prototype for two players on the same local Wi-Fi network. No internet connection or cloud service is intended for gameplay.

## Current prototype
- Landscape Android activity.
- Perspective 3D chessboard rendered with OpenGL ES 2.0.
- Host mode opens a TCP server on port `45454`.
- Join mode connects to the host using its local Wi-Fi IP address.
- GitHub Actions workflow builds a debug APK and uploads it as an artifact.

## Build
Use Android Studio with JDK 17 and Android SDK 35, or run:

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Play over Wi-Fi (transport prototype)
1. Connect both Android devices to the same Wi-Fi network or hotspot.
2. On the first device, tap **Host game**.
3. Find that device's local IPv4 address in Android Wi-Fi settings.
4. On the second device, tap **Join game** and enter the host IP.

The current network layer is a basic TCP message channel. It does not yet synchronize chess moves.

## Remaining development
Full chess rules and legal move validation, interactive piece selection, proper 3D chess pieces, move synchronization, automatic LAN discovery, and device testing are still required before this is a complete playable chess game.
