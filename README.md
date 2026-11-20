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

## Supabase (optional cloud features)
Supabase is reserved for optional player profiles and match history when internet is available. It is not used to host, discover, or synchronize local Wi-Fi games. Offline play must remain available without signing in or contacting Supabase.

Database setup and row-level security are documented in [docs/SUPABASE.md](docs/SUPABASE.md). The matching SQL migration is in `supabase/migrations/20261120000000_create_player_profiles_and_match_results.sql`.

Do not commit Supabase service-role keys or other secrets. Android clients may only use a publishable/anon key after authentication and RLS are configured.

## Remaining development
Full chess rules and legal move validation, interactive piece selection, proper 3D chess pieces, move synchronization, automatic LAN discovery, Supabase client integration for optional cloud features, and device testing are still required before this is a complete playable chess game.
