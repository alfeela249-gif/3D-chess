# 3D Chess — Project Status

## Goal
Native Android APK for two players on the same Wi-Fi network, with no internet or cloud dependency.

## Implemented in the initial prototype
- Android/Kotlin Gradle project (application ID: `com.alfeela.chess3d`).
- Landscape activity and OpenGL ES 2.0 perspective board with simple 3D piece placeholders.
- Host mode listens on TCP port 45454.
- Join mode accepts the host device's local IP address.
- UTF-8 line-based TCP message transport.
- GitHub Actions workflow configured to build a debug APK on pushes to `main`.

## Not implemented yet
- Full chess rules, check/checkmate, castling, en passant and promotion.
- Touch selection, legal move highlighting and animated piece movement.
- Encoding and synchronizing actual chess moves over the LAN connection.
- Automatic peer discovery (currently the joining player enters the host IP).
- Verified successful CI build and downloadable APK.

## Build
The workflow is `.github/workflows/android.yml`. It runs `gradle assembleDebug` and uploads `app-debug.apk` as an Actions artifact.
