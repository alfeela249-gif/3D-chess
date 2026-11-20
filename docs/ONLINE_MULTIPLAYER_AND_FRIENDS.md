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

## Latest Android integration
- Added a medium-strength offline computer opponent (3-ply alpha-beta search).
- Added email/password account creation and sign-in, Google OAuth browser launch/callback handling, and token refresh for stored sessions.
- Added profile username/nickname setup and sending friend requests by username.
- Added online room creation, 8-character invite codes, joining through a database RPC, and turn-checked game-state submission. The Android client polls the room snapshot and replays missing moves.
- The online room join/state RPCs are SECURITY DEFINER functions with an explicit `auth.uid()` check; EXECUTE is revoked from `PUBLIC` and `anon`, and granted only to `authenticated`. Supabase's advisor still reports its expected warning because authenticated clients must call these RPCs.
- Remaining: friend list and accept/reject request UI, friend-based invite picker, Realtime push (current client polls), disconnect/reconnect UX, robust validation of submitted chess move history on the server, and physical-device multiplayer testing.
- Google sign-in also requires Google OAuth credentials to be configured in the Supabase dashboard. The app cannot complete Google login until that external provider setup is done.

## Friends management and Data API permissions (20 Nov 2026)
- Android now loads the signed-in user's friendship rows and the other player's public username/nickname, shows accepted friends, incoming requests, and outgoing pending requests, and lets the recipient accept or reject an incoming request.
- Selecting an accepted friend offers an online-game invitation. The app creates a room and opens Android's share sheet with the room invite code, so the user can send it through a messaging app.
- Verified hosted Supabase RLS is enabled on `player_profiles`, `friendships`, and `online_game_rooms`. Added explicit `SELECT, INSERT, UPDATE` grants to `authenticated` and revoked all table privileges from `anon` on these tables. This is needed because new public tables are no longer automatically exposed to the Data API.
- Verified the online room RPC functions are SECURITY DEFINER, check `auth.uid()`, are not executable by `anon`, and are executable by `authenticated`.
- The Android online match transport still polls room snapshots every ~1.8 seconds; it does not yet use Realtime push notifications. Online move history is client-submitted and needs server-side legal-move replay/validation before public production use.

- Security hardening: privileged room mutation functions now live in the non-exposed `private` schema, with small `SECURITY INVOKER` wrappers in `public`. Authenticated execution is intentional; anonymous execution is revoked. The Supabase security advisor was rerun and returned no security lints.
- Explicit authenticated table grants and anonymous revocations are recorded in the online-room migration for fresh deployments; the same grants were applied and verified on the connected hosted project.
