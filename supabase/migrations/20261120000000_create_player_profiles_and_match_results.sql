create table if not exists public.player_profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  nickname text not null check (char_length(nickname) between 1 and 24),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.match_results (
  id uuid primary key default gen_random_uuid(),
  white_player uuid not null references auth.users(id) on delete cascade,
  black_player uuid not null references auth.users(id) on delete cascade,
  winner uuid references auth.users(id) on delete set null,
  moves_count integer not null default 0 check (moves_count >= 0),
  finished_at timestamptz not null default now(),
  check (white_player <> black_player),
  check (winner is null or winner in (white_player, black_player))
);

create index if not exists match_results_white_player_idx on public.match_results(white_player, finished_at desc);
create index if not exists match_results_black_player_idx on public.match_results(black_player, finished_at desc);

alter table public.player_profiles enable row level security;
alter table public.match_results enable row level security;

drop policy if exists "Players can read own profile" on public.player_profiles;
create policy "Players can read own profile"
on public.player_profiles for select to authenticated
using ((select auth.uid()) = user_id);

drop policy if exists "Players can create own profile" on public.player_profiles;
create policy "Players can create own profile"
on public.player_profiles for insert to authenticated
with check ((select auth.uid()) = user_id);

drop policy if exists "Players can update own profile" on public.player_profiles;
create policy "Players can update own profile"
on public.player_profiles for update to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

drop policy if exists "Players can read their match results" on public.match_results;
create policy "Players can read their match results"
on public.match_results for select to authenticated
using ((select auth.uid()) in (white_player, black_player));

drop policy if exists "Players can record matches they played" on public.match_results;
create policy "Players can record matches they played"
on public.match_results for insert to authenticated
with check ((select auth.uid()) in (white_player, black_player));
