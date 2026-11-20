# 3D Chess for Android

A native Kotlin/OpenGL ES Android chess prototype for two players on the same local Wi-Fi network. Local gameplay does not require internet or a cloud service.

## Implemented
- Landscape Android activity and a perspective OpenGL ES 2.0 board.
- Select a piece and tap its destination square.
- Offline rules engine for legal movement, check, checkmate, stalemate, castling, en passant, and automatic queen promotion.
- Host plays White; Join plays Black.
- TCP move messages between devices on the same Wi-Fi network, using port `45454`.
- New-game reset message shared with the opponent.
- GitHub Actions workflow builds a debug APK and uploads it as an artifact.

## Build
Use Android Studio with JDK 17 and Android SDK 35, or run:

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Play over Wi-Fi
1. Connect both Android devices to the same Wi-Fi network or one phone's hotspot. Internet access is not needed.
2. On the first device, tap **Host (White)**.
3. Find the host device's local IPv4 address in Android Wi-Fi settings.
4. On the second device, tap **Join (Black)** and enter the host IP.
5. Tap one of your pieces, then tap a legal destination square. Only the side whose turn it is can move.

The LAN connection is a direct TCP connection. The app currently expects the host IP to be entered manually. Test on two physical Android devices before relying on it for a match.

## Supabase (optional cloud features)
Supabase is reserved for optional player profiles and match history when internet is available. It is not used to host, discover, or synchronize local Wi-Fi games. Offline play remains available without signing in or contacting Supabase.

Database setup and row-level security are documented in [docs/SUPABASE.md](docs/SUPABASE.md). The matching SQL migration is in `supabase/migrations/20261120000000_create_player_profiles_and_match_results.sql`.

Do not commit Supabase service-role keys or other secrets. Android clients may only use a publishable/anon key after authentication and RLS are configured.

## Known limitations / next work
- Piece models are currently geometric 3D shapes, not detailed chess-piece meshes.
- Touch-to-square mapping and perspective should be validated on different screen sizes.
- No automatic LAN discovery, reconnect handling, clock, draw offer, or resign flow yet.
- Promotion currently defaults to a queen.
- Supabase tables are ready, but optional sign-in/profile/history UI is not yet connected in Android.
- Device testing and a successful CI APK build are required before calling this release-ready.


## Game modes roadmap
See [Online multiplayer and friends](docs/ONLINE_MULTIPLAYER_AND_FRIENDS.md) for the implementation plan covering offline computer opponents, local Wi-Fi games, online rooms, and friend requests.

The Supabase backend now includes friend request and online room tables with RLS. These cloud features are backend groundwork only; Android sign-in, friend screens, online synchronization, and computer AI are not yet integrated into the APK.


## تنزيل تطبيق Android

[**تنزيل أحدث إصدار APK مباشرة**](https://github.com/alfeela249-gif/3D-chess/releases/download/latest/app-debug.apk)

[عرض صفحة الإصدارات والتنزيلات](https://github.com/alfeela249-gif/3D-chess/releases)

يتم تحديث رابط التنزيل تلقائياً بعد نجاح بناء APK على الفرع `main`. النسخة الحالية نسخة Debug للاختبار وليست إصداراً نهائياً موقعاً للنشر العام.
