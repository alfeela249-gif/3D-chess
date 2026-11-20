# Online multiplayer and friends roadmap

## Game modes
- **Computer:** local/offline opponent. The AI runs on-device and does not call Supabase.
- **Local Wi-Fi:** retain the existing direct TCP host/join flow on port 45454. It must work without internet or sign-in.
- **Online:** authenticated players create or join private rooms. Supabase Realtime Broadcast carries move events; Postgres stores the authoritative room snapshot and finished results. Reconnect must reload the latest snapshot and validate move order.

## Friends
- Sign up / sign in with Supabase Auth.
- Search public player handles (never expose email addresses).
- Send, accept, reject, and cancel friend requests.
- Show accepted friends and online presence.
- Invite a friend to an online room.

## Backend tables
- `player_profiles`: public nickname/username; owner-controlled updates.
- `friend_requests`: request sender, recipient, status and timestamps.
- `online_game_rooms`: invite code, participants, game snapshot, turn and status.
- `match_results`: completed match history.

RLS is enabled on the feature tables. The app must use only the Supabase publishable key and the signed-in user's session. Never ship a service-role key in the APK.

## Current implementation status
- Existing Android LAN prototype remains independent of Supabase.
- The Supabase project has the profile and match history tables, plus friendship and online-game tables from earlier setup.
- The `friend_requests` and `online_game_rooms` tables and participant policies have now also been created and verified.
- Android authentication, friend search/list screens, Realtime room synchronization, and computer AI are **not yet wired into the Android UI**.
- The current LAN TCP prototype still needs turn/identity validation, reconnection, and physical-device testing.

## Delivery order
1. Implement on-device chess AI with selectable difficulty and keep it offline.
2. Add account creation/sign-in and editable unique player handles.
3. Add friend search, requests, accepted-friends list, and online status.
4. Add online room create/join, private Realtime move events, authoritative turn validation, reconnect/snapshot recovery, and match history.
5. Test all three modes on physical devices and publish a release-signed APK.

## Current Android work (2026-11-20)
- Added a local 3-ply alpha-beta chess opponent with a material/positional evaluation. The selected default is **medium** and it runs on-device without internet.
- Added an account dialog with email/password sign-up and sign-in against Supabase Auth.
- Added a Google OAuth launch and Android deep-link callback handler. Google login requires the owner to enable Google in Supabase Auth, configure the Google OAuth client ID/secret, and add `com.alfeela.chess3d://auth-callback` to the Supabase Auth URL redirect allow list. Until that dashboard configuration is complete, Google sign-in cannot complete.
- Session tokens are stored in Android app-private preferences. The current REST auth implementation does not yet refresh expired sessions automatically; add refresh/re-auth handling before production release.
- Friend discovery/request screens and online room synchronization are still pending Android integration. The Supabase tables are backend groundwork and do not mean these user-facing features are already working.
