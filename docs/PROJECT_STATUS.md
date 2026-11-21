# 3D Chess — Project Status

## Product target
Native Android application (application ID: `com.alfeela.chess3d`) with:
- Offline computer opponent.
- Local Wi-Fi multiplayer without internet or account sign-in.
- Online private rooms for signed-in players.
- A consistent 3D chess experience across Android and the browser.

## Implemented in Android
- Kotlin Android application using OpenGL ES 2.0 for the perspective 3D board and procedural Staunton-inspired pieces.
- Chess rules engine with legal moves, check, checkmate, stalemate, castling, en passant and promotion choice.
- On-device computer opponent using alpha-beta search.
- Local Wi-Fi host/join with automatic room discovery on the same Wi-Fi/hotspot network; physical two-device verification remains necessary.
- Email/password authentication through Supabase Auth; Google OAuth callback handling exists but provider configuration is still required in Supabase.
- Player profiles, friend requests/list and invitations to online rooms.
- Online private-room creation/joining, move submission and room-state polling.
- Camera orbit/tilt/zoom gestures and eased camera motion.
- GitHub Actions builds a debug APK and uploads a workflow artifact; the current workflow also updates the `latest` release.

## Web experience
- `docs/home.html` contains the web home screen and navigation.
- `docs/index.html` is a standalone Three.js board/camera preview. It is not yet the same interactive chess game as the Android application; its preview controls do not implement the native rules/network flows.
- Keep the distinction clear in release notes and do not claim web gameplay parity until it is implemented and tested.

## Approved 3D art
- The user approved the saved **Classic Staunton 3D Chess Set** on 2026-11-21; it supersedes the earlier fantasy-medieval direction.
- Blendstra project: `c0999f0c-787a-42a3-b97e-8654892aba1e`
- Version: `version_5e2b0678e7108cbda3671c80385bd19ebde36d5a6aea934c1a13ca71fdee5fa8`
- [Open approved model](https://blendstra.com/studio?workspace=wsp_adb20fdc3deb8b498a5627d0c41c91052953abee&project=c0999f0c-787a-42a3-b97e-8654892aba1c0999f0c-787a-42a3-b97e-8654892aba1c)
- Inspection reports 343 scene objects and a 32-piece set with board. The saved model is not yet integrated into the Android runtime; Android still uses procedural geometry. See `docs/APPROVED_PIECE_ART_DIRECTION.md`.

## Remaining work before calling the project complete
1. Transfer and optimize the approved model into runtime assets, then replace Android procedural piece meshes.
2. Implement real browser gameplay or explicitly limit the web page to a preview; do not call the preview a browser game.
3. Match Android/web camera feel, board framing, lighting and materials and test on target devices.
4. Run two-device tests for Wi-Fi discovery, reconnect and actual move synchronization.
5. Harden online chess validation server-side; the server must replay and validate moves rather than trust a client-submitted history.
6. Add reliable disconnect/reconnect and completed-match history flows.
7. Complete Google OAuth provider configuration if Google sign-in is to be offered.
8. Perform physical-device QA, then produce a signed production APK. Current APKs are debug/test builds.

## Build and download
- Repository: https://github.com/alfeela249-gif/3D-chess
- Latest release URL: https://github.com/alfeela249-gif/3D-chess/releases/download/latest/app-debug.apk
- Releases: https://github.com/alfeela249-gif/3D-chess/releases
- Android build workflow: `.github/workflows/android.yml`
