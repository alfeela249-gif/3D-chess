create or replace function public.join_online_game_room(p_invite_code text)
returns setof public.online_game_rooms
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null then raise exception 'Authentication required'; end if;
  return query
    update public.online_game_rooms as room
       set guest_id = v_uid, status = 'playing', updated_at = now()
     where room.invite_code = upper(trim(p_invite_code))
       and room.status = 'waiting' and room.guest_id is null and room.host_id <> v_uid
    returning room.*;
  if not found then raise exception 'Room not found or already occupied'; end if;
end;
$$;
revoke all on function public.join_online_game_room(text) from public, anon;
grant execute on function public.join_online_game_room(text) to authenticated;

create or replace function public.submit_online_game_state(p_room_id uuid, p_game_state jsonb, p_next_turn text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare v_uid uuid := auth.uid();
begin
  if v_uid is null then raise exception 'Authentication required'; end if;
  if p_next_turn not in ('white','black') then raise exception 'Invalid next turn'; end if;
  update public.online_game_rooms as room
     set game_state = p_game_state, current_turn = p_next_turn, updated_at = now()
   where room.id = p_room_id and room.status = 'playing'
     and (room.host_id = v_uid or room.guest_id = v_uid)
     and room.current_turn = case when room.host_id = v_uid then 'white' else 'black' end
     and p_next_turn = case when room.host_id = v_uid then 'black' else 'white' end;
  if not found then raise exception 'Not your turn or room unavailable'; end if;
end;
$$;
revoke all on function public.submit_online_game_state(uuid,jsonb,text) from public, anon;
grant execute on function public.submit_online_game_state(uuid,jsonb,text) to authenticated;
