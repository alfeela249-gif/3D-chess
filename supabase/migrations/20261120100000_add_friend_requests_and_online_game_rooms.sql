create table if not exists public.friend_requests (
  id uuid primary key default gen_random_uuid(),
  sender_id uuid not null references auth.users(id) on delete cascade,
  recipient_id uuid not null references auth.users(id) on delete cascade,
  status text not null default 'pending' check (status in ('pending','accepted','rejected','cancelled')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (sender_id <> recipient_id)
);
create unique index if not exists friend_requests_one_pending_pair
  on public.friend_requests (least(sender_id, recipient_id), greatest(sender_id, recipient_id))
  where status = 'pending';
create index if not exists friend_requests_recipient_status_idx
  on public.friend_requests(recipient_id, status, created_at desc);

create table if not exists public.online_game_rooms (
  id uuid primary key default gen_random_uuid(),
  invite_code text not null unique,
  host_id uuid not null references auth.users(id) on delete cascade,
  guest_id uuid references auth.users(id) on delete set null,
  status text not null default 'waiting' check (status in ('waiting','playing','finished','cancelled')),
  game_state jsonb not null default '{}'::jsonb,
  current_turn text not null default 'white' check (current_turn in ('white','black')),
  winner_id uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (guest_id is null or guest_id <> host_id)
);
create index if not exists online_game_rooms_host_idx on public.online_game_rooms(host_id, created_at desc);
create index if not exists online_game_rooms_guest_idx on public.online_game_rooms(guest_id, created_at desc);

alter table public.friend_requests enable row level security;
alter table public.online_game_rooms enable row level security;

drop policy if exists "Players can view their friend requests" on public.friend_requests;
create policy "Players can view their friend requests"
on public.friend_requests for select to authenticated
using ((select auth.uid()) in (sender_id, recipient_id));
drop policy if exists "Players can send friend requests" on public.friend_requests;
create policy "Players can send friend requests"
on public.friend_requests for insert to authenticated
with check ((select auth.uid()) = sender_id and sender_id <> recipient_id);
drop policy if exists "Recipients can respond to friend requests" on public.friend_requests;
create policy "Recipients can respond to friend requests"
on public.friend_requests for update to authenticated
using ((select auth.uid()) = recipient_id and status = 'pending')
with check ((select auth.uid()) = recipient_id and status in ('accepted','rejected'));

drop policy if exists "Players can view rooms they participate in" on public.online_game_rooms;
create policy "Players can view rooms they participate in"
on public.online_game_rooms for select to authenticated
using ((select auth.uid()) in (host_id, guest_id));
drop policy if exists "Players can create their own rooms" on public.online_game_rooms;
create policy "Players can create their own rooms"
on public.online_game_rooms for insert to authenticated
with check ((select auth.uid()) = host_id and guest_id is null);
drop policy if exists "Room participants can update games" on public.online_game_rooms;
create policy "Room participants can update games"
on public.online_game_rooms for update to authenticated
using ((select auth.uid()) in (host_id, guest_id))
with check ((select auth.uid()) in (host_id, guest_id));
