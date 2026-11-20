# 3D Chess — Project Status

## Product target
Native Android application (application ID: `com.alfeela.chess3d`) with a 3D chess board and three play modes:
- Offline computer opponent (medium difficulty).
- Direct local Wi-Fi multiplayer without internet or account sign-in.
- Online private rooms for signed-in players.

## Implemented in the Android app
- Kotlin Android app using OpenGL ES 2.0 for the perspective 3D board and geometric piece models.
- Chess rules engine: legal moves, check, checkmate, stalemate, castling, en passant, and automatic queen promotion.
- On-device computer opponent using 3-ply alpha-beta search; it does not require internet.
- Local Wi-Fi host/join using TCP port `45454`; the joining player currently enters the host's local IP address manually.
- Email/password sign-up and sign-in through Supabase Auth.
- Google OAuth launch and deep-link callback handling. Google provider credentials and redirect allow-list configuration must be completed in the Supabase dashboard before Google sign-in can work.
- Player username/nickname setup, friend requests by username, friend list, incoming request accept/reject, and invitations to share an online-room code.
- Online private-room creation and joining by invite code, move submission, and room-state polling approximately every 1.8 seconds.
- Session token storage and refresh handling.
- GitHub Actions workflow builds a debug APK and publishes it to the `latest` GitHub Release after pushes to `main`.

## Backend and security
- Supabase stores player profiles, friendships, and online game rooms.
- Online room RPCs enforce authenticated access and participant/turn checks.
- The Android app is intended to use only the Supabase publishable key and the signed-in user's access token. Never put a service-role key in the APK.
- Online moves are currently submitted as a move history from the client. Server-side replay and full legal-chess validation are still needed before production use.

## Known limitations / remaining work
- The 3D pieces are geometric placeholders rather than detailed chess-piece meshes.
- The 2D display toggle is not implemented; the board remains 3D.
- LAN discovery is manual; no automatic peer discovery or reconnect flow yet.
- Online synchronization uses polling, not Supabase Realtime push.
- Add robust disconnect/reconnect and online match completion/history flows.
- Validate online move history server-side and harden race handling.
- Test local Wi-Fi and online play on two physical Android devices and different screen sizes.
- Google OAuth requires provider setup in Supabase.
- The current APK is a debug/test build, not a production release signed for public distribution.

## Build and download
The workflow is `.github/workflows/android.yml`. It runs `gradle assembleDebug`, uploads the APK artifact, and updates the `latest` GitHub Release.

- Repository: https://github.com/alfeela249-gif/3D-chess
- Latest APK: https://github.com/alfeela249-gif/3D-chess/releases/download/latest/app-debug.apk
- Releases: https://github.com/alfeela249-gif/3D-chess/releases

## Recommended next development steps
1. Run two-device tests for LAN play, computer play, account sign-in, friend requests, and online rooms.
2. Replace geometric placeholders with detailed 3D chess-piece meshes and refine touch mapping.
3. Replace online polling with Supabase Realtime and add reconnect/snapshot recovery.
4. Validate every submitted online move on the server and persist completed match results.
5. Produce a signed release APK after testing.
