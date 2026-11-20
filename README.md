# 3D Chess for Android

A native Kotlin Android chess app with a perspective 3D board and three game modes: offline computer opponent, local Wi-Fi multiplayer, and online private rooms.

## Implemented
- OpenGL ES 2.0 3D board with selectable pieces, legal-destination highlights, and tap-to-move controls.
- Chess rules engine: legal moves, check, checkmate, stalemate, castling, en passant, and automatic queen promotion.
- Offline computer opponent using medium-strength 3-ply alpha-beta search.
- Local Wi-Fi host/join over TCP port `45454`; no internet or sign-in is required for LAN games.
- Email/password sign-up and sign-in through Supabase Auth, plus Google OAuth callback support (Google provider setup is required in Supabase).
- Player username/nickname profiles, friend requests, friend list, and incoming request accept/reject.
- Online private rooms with 8-character invite codes, move submission, and room-state polling.
- GitHub Actions builds a debug APK and publishes it to the `latest` GitHub Release.

## Build
Use Android Studio with JDK 17 and Android SDK 35, or run:

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Play over local Wi-Fi
1. Connect both Android devices to the same Wi-Fi network or one phone's hotspot. Internet access is not needed.
2. On the first device, tap **HOST • الأبيض**.
3. The host screen displays its local IPv4 address and port `45454`; share these with the second player.
4. On the second device, tap **JOIN • الأسود** and enter the host IP.
5. Select a piece, then tap its legal destination square.

The joining player enters the displayed host IP manually. The client now uses a 10-second connection timeout and TCP keep-alive/no-delay options. Test on two physical Android devices before relying on it for a match.

## Online accounts and friends
Sign in or create an account from the account screen. Google sign-in works only after enabling Google as an Auth provider and configuring its OAuth credentials and the `com.alfeela.chess3d://auth-callback` redirect URL in Supabase. Set a unique player username, send friend requests, and accept/reject incoming requests. Accepted friends can be invited by sharing a private room code.

Online rooms currently synchronize through polling (about every 1.8 seconds), not Supabase Realtime. Online move histories still need full server-side chess legality validation before production use.

## Supabase configuration and security
See [docs/SUPABASE.md](docs/SUPABASE.md), [docs/ONLINE_MULTIPLAYER_AND_FRIENDS.md](docs/ONLINE_MULTIPLAYER_AND_FRIENDS.md), and [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md).

The Android client must use only the Supabase publishable key and the signed-in user's session. Never place a service-role key in the APK. Local Wi-Fi and computer games do not depend on Supabase.

## Known limitations
- 3D pieces are lightweight geometric models, not detailed chess-piece meshes.
- The 2D display toggle is not implemented; the board remains 3D.
- LAN host discovery is manual and reconnect handling is not implemented.
- Online Realtime push, robust reconnect recovery, server-side legal-move replay, and completed match history are remaining work.
- Google OAuth needs dashboard configuration.
- The downloadable APK is a debug/test build, not a production-signed release.
- Physical-device testing is still required.

## تنزيل تطبيق Android

[**تنزيل أحدث إصدار APK مباشرة**](https://github.com/alfeela249-gif/3D-chess/releases/download/latest/app-debug.apk)

[عرض صفحة الإصدارات والتنزيلات](https://github.com/alfeela249-gif/3D-chess/releases)

يتم تحديث رابط التنزيل تلقائياً بعد نجاح بناء APK على الفرع `main`. النسخة الحالية نسخة Debug للاختبار وليست إصداراً نهائياً موقعاً للنشر العام.
