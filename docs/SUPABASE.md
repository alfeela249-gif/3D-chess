# Supabase integration

Supabase project: `llkynfrcrmzwziakusgb` (region: `eu-west-1`).

## Purpose and offline-first boundary

Supabase is optional cloud support only:
- `player_profiles`: authenticated player's nickname and timestamps.
- `match_results`: completed match participants, winner, move count, and finish time.

The local Wi-Fi TCP host/join transport remains independent of Supabase. Do not require internet, login, or a Supabase request to create or play a LAN match. Queue optional match-history uploads locally and sync only when connectivity and an authenticated session are available.

## Database

Migration: `supabase/migrations/20261120000000_create_player_profiles_and_match_results.sql`.

Both tables have Row Level Security enabled. A signed-in player can read/update only their own profile and read/record results only for matches in which their user ID is one of the players.

## Android client configuration

When cloud features are implemented:
1. Add the official Supabase Kotlin client using a pinned version and commit dependency lock/configuration.
2. Configure the project URL and publishable key through build configuration (not hard-coded in source control).
3. Never put a service-role or secret key in the APK.
4. Implement Supabase Auth before using the `authenticated` table policies.
5. Treat cloud profile/history as optional; the local chess engine and LAN transport must work with no session and no network.

Project URL can be obtained from Supabase project settings. Do not store credentials in this repository.
