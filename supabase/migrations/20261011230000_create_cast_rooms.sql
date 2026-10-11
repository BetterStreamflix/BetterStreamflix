-- Cast Room: phone controls TV queue (remote companion).
-- Media identity + ordered queue only — no stream URLs/cookies.

create table if not exists public.cast_rooms (
    id uuid primary key default gen_random_uuid(),
    share_token text not null unique,
    short_code text not null unique,
    host_user_id uuid not null references auth.users(id) on delete cascade,
    provider text not null,
    now_playing_index integer not null default 0,
    playing boolean not null default false,
    position_ms bigint not null default 0,
    revision bigint not null default 1,
    ended boolean not null default false,
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint cast_rooms_token_chk check (share_token ~ '^[A-Za-z0-9_-]{8,128}$'),
    constraint cast_rooms_code_chk check (short_code ~ '^[A-Z2-9]{6}$'),
    constraint cast_rooms_index_chk check (now_playing_index >= 0)
);

create table if not exists public.cast_room_items (
    id uuid primary key default gen_random_uuid(),
    room_id uuid not null references public.cast_rooms(id) on delete cascade,
    sort_order integer not null,
    media_type text not null check (media_type in ('movie', 'episode')),
    media_id text not null,
    title text not null default '',
    subtitle text not null default '',
    poster text,
    show_id text,
    show_title text,
    show_poster text,
    season_number integer,
    season_title text,
    episode_number integer,
    episode_title text,
    release_date text,
    imdb_id text,
    preferred_server_id text,
    preferred_server_name text,
    created_at timestamptz not null default now(),
    constraint cast_room_items_order_chk check (sort_order >= 0),
    constraint cast_room_items_unique_order unique (room_id, sort_order)
);

create index if not exists cast_rooms_host_idx
    on public.cast_rooms (host_user_id);

create index if not exists cast_rooms_token_idx
    on public.cast_rooms (share_token);

create index if not exists cast_rooms_code_idx
    on public.cast_rooms (short_code);

create index if not exists cast_room_items_room_idx
    on public.cast_room_items (room_id, sort_order);

comment on table public.cast_rooms is
    'Cast Room sessions: TV host queue controlled by phone companion (no media relay).';

alter table public.cast_rooms enable row level security;
alter table public.cast_room_items enable row level security;

drop policy if exists "Hosts read their cast rooms" on public.cast_rooms;
create policy "Hosts read their cast rooms"
    on public.cast_rooms
    for select
    to authenticated
    using ((select auth.uid()) = host_user_id);

drop policy if exists "Hosts insert their cast rooms" on public.cast_rooms;
create policy "Hosts insert their cast rooms"
    on public.cast_rooms
    for insert
    to authenticated
    with check ((select auth.uid()) = host_user_id);

drop policy if exists "Hosts update their cast rooms" on public.cast_rooms;
create policy "Hosts update their cast rooms"
    on public.cast_rooms
    for update
    to authenticated
    using ((select auth.uid()) = host_user_id)
    with check ((select auth.uid()) = host_user_id);

drop policy if exists "Hosts delete their cast rooms" on public.cast_rooms;
create policy "Hosts delete their cast rooms"
    on public.cast_rooms
    for delete
    to authenticated
    using ((select auth.uid()) = host_user_id);

drop policy if exists "Hosts manage cast room items" on public.cast_room_items;
create policy "Hosts manage cast room items"
    on public.cast_room_items
    for all
    to authenticated
    using (
        exists (
            select 1 from public.cast_rooms r
            where r.id = room_id and r.host_user_id = (select auth.uid())
        )
    )
    with check (
        exists (
            select 1 from public.cast_rooms r
            where r.id = room_id and r.host_user_id = (select auth.uid())
        )
    );

grant select, insert, update, delete on public.cast_rooms to authenticated;
grant select, insert, update, delete on public.cast_room_items to authenticated;

create or replace function public._cast_room_json(p_room public.cast_rooms)
returns json
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    items json;
begin
    select coalesce(json_agg(row_to_json(x) order by x.sort_order), '[]'::json)
    into items
    from (
        select
            i.id,
            i.sort_order,
            i.media_type,
            i.media_id,
            i.title,
            i.subtitle,
            i.poster,
            i.show_id,
            i.show_title,
            i.show_poster,
            i.season_number,
            i.season_title,
            i.episode_number,
            i.episode_title,
            i.release_date,
            i.imdb_id,
            i.preferred_server_id,
            i.preferred_server_name
        from public.cast_room_items i
        where i.room_id = p_room.id
        order by i.sort_order
    ) x;

    return json_build_object(
        'id', p_room.id,
        'share_token', p_room.share_token,
        'short_code', p_room.short_code,
        'provider', p_room.provider,
        'now_playing_index', p_room.now_playing_index,
        'playing', p_room.playing,
        'position_ms', p_room.position_ms,
        'revision', p_room.revision,
        'ended', p_room.ended,
        'expires_at', p_room.expires_at,
        'updated_at', p_room.updated_at,
        'items', items
    );
end;
$$;

create or replace function public._cast_room_active(p_token text)
returns public.cast_rooms
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    cleaned text;
    room public.cast_rooms;
begin
    if p_token is null then
        return null;
    end if;
    cleaned := trim(p_token);
    if cleaned !~ '^[A-Za-z0-9_-]{8,128}$' then
        return null;
    end if;
    select * into room
    from public.cast_rooms r
    where r.share_token = cleaned
      and r.expires_at >= now()
      and r.ended = false;
    return room;
end;
$$;

create or replace function public.get_cast_room(p_token text)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    room public.cast_rooms;
begin
    room := public._cast_room_active(p_token);
    if room.id is null then
        return null;
    end if;
    return public._cast_room_json(room);
end;
$$;

create or replace function public.get_cast_room_by_code(p_code text)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    cleaned text;
    room public.cast_rooms;
begin
    if p_code is null then
        return null;
    end if;
    cleaned := upper(trim(p_code));
    if cleaned !~ '^[A-Z2-9]{6}$' then
        return null;
    end if;
    select * into room
    from public.cast_rooms r
    where r.short_code = cleaned
      and r.expires_at >= now()
      and r.ended = false;
    if room.id is null then
        return null;
    end if;
    return public._cast_room_json(room);
end;
$$;

create or replace function public.cast_room_add_item(
    p_token text,
    p_media_type text,
    p_media_id text,
    p_title text default '',
    p_subtitle text default '',
    p_poster text default null,
    p_show_id text default null,
    p_show_title text default null,
    p_show_poster text default null,
    p_season_number integer default null,
    p_season_title text default null,
    p_episode_number integer default null,
    p_episode_title text default null,
    p_release_date text default null,
    p_imdb_id text default null,
    p_preferred_server_id text default null,
    p_preferred_server_name text default null
)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    room public.cast_rooms;
    next_order integer;
begin
    room := public._cast_room_active(p_token);
    if room.id is null then
        return null;
    end if;
    if p_media_type is null or p_media_type not in ('movie', 'episode') then
        return null;
    end if;
    if p_media_id is null or length(trim(p_media_id)) = 0 then
        return null;
    end if;

    select coalesce(max(sort_order), -1) + 1 into next_order
    from public.cast_room_items
    where room_id = room.id;

    insert into public.cast_room_items (
        room_id, sort_order, media_type, media_id, title, subtitle, poster,
        show_id, show_title, show_poster, season_number, season_title,
        episode_number, episode_title, release_date, imdb_id,
        preferred_server_id, preferred_server_name
    ) values (
        room.id, next_order, p_media_type, trim(p_media_id),
        coalesce(p_title, ''), coalesce(p_subtitle, ''), p_poster,
        p_show_id, p_show_title, p_show_poster, p_season_number, p_season_title,
        p_episode_number, p_episode_title, p_release_date, p_imdb_id,
        p_preferred_server_id, p_preferred_server_name
    );

    update public.cast_rooms
    set revision = revision + 1,
        updated_at = now()
    where id = room.id;

    return public.get_cast_room(room.share_token);
end;
$$;

create or replace function public.cast_room_remove_item(p_token text, p_item_id uuid)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    room public.cast_rooms;
    removed_order integer;
begin
    room := public._cast_room_active(p_token);
    if room.id is null or p_item_id is null then
        return null;
    end if;

    select sort_order into removed_order
    from public.cast_room_items
    where id = p_item_id and room_id = room.id;
    if removed_order is null then
        return null;
    end if;

    delete from public.cast_room_items
    where id = p_item_id and room_id = room.id;

    update public.cast_room_items
    set sort_order = sort_order - 1
    where room_id = room.id and sort_order > removed_order;

    update public.cast_rooms
    set now_playing_index = case
            when (select count(*) from public.cast_room_items where room_id = room.id) = 0 then 0
            when now_playing_index > removed_order then greatest(now_playing_index - 1, 0)
            when now_playing_index >= (select count(*) from public.cast_room_items where room_id = room.id)
                then greatest((select count(*)::int - 1 from public.cast_room_items where room_id = room.id), 0)
            else now_playing_index
        end,
        playing = case
            when (select count(*) from public.cast_room_items where room_id = room.id) = 0 then false
            else playing
        end,
        revision = revision + 1,
        updated_at = now()
    where id = room.id;

    return public.get_cast_room(room.share_token);
end;
$$;

create or replace function public.cast_room_reorder(p_token text, p_item_ids uuid[])
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    room public.cast_rooms;
    expected integer;
    i integer;
    current_id uuid;
begin
    room := public._cast_room_active(p_token);
    if room.id is null or p_item_ids is null then
        return null;
    end if;

    select count(*)::int into expected
    from public.cast_room_items
    where room_id = room.id;

    if expected = 0 or array_length(p_item_ids, 1) is distinct from expected then
        return null;
    end if;

    -- Temporary offset to avoid unique(sort_order) collisions while rewriting.
    update public.cast_room_items
    set sort_order = sort_order + 100000
    where room_id = room.id;

    for i in 1 .. array_length(p_item_ids, 1) loop
        current_id := p_item_ids[i];
        update public.cast_room_items
        set sort_order = i - 1
        where id = current_id and room_id = room.id;
        if not found then
            return null;
        end if;
    end loop;

    update public.cast_rooms
    set revision = revision + 1,
        updated_at = now()
    where id = room.id;

    return public.get_cast_room(room.share_token);
end;
$$;

create or replace function public.cast_room_play_at(p_token text, p_index integer)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    room public.cast_rooms;
    item_count integer;
begin
    room := public._cast_room_active(p_token);
    if room.id is null or p_index is null or p_index < 0 then
        return null;
    end if;
    select count(*)::int into item_count
    from public.cast_room_items where room_id = room.id;
    if item_count = 0 or p_index >= item_count then
        return null;
    end if;

    update public.cast_rooms
    set now_playing_index = p_index,
        playing = true,
        position_ms = 0,
        revision = revision + 1,
        updated_at = now()
    where id = room.id;

    return public.get_cast_room(room.share_token);
end;
$$;

create or replace function public.cast_room_skip(p_token text)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    room public.cast_rooms;
    item_count integer;
    next_index integer;
begin
    room := public._cast_room_active(p_token);
    if room.id is null then
        return null;
    end if;
    select count(*)::int into item_count
    from public.cast_room_items where room_id = room.id;
    if item_count = 0 then
        return null;
    end if;
    next_index := room.now_playing_index + 1;
    if next_index >= item_count then
        update public.cast_rooms
        set playing = false,
            revision = revision + 1,
            updated_at = now()
        where id = room.id;
    else
        update public.cast_rooms
        set now_playing_index = next_index,
            playing = true,
            position_ms = 0,
            revision = revision + 1,
            updated_at = now()
        where id = room.id;
    end if;
    return public.get_cast_room(room.share_token);
end;
$$;

create or replace function public.cast_room_set_playing(p_token text, p_playing boolean)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    room public.cast_rooms;
begin
    room := public._cast_room_active(p_token);
    if room.id is null or p_playing is null then
        return null;
    end if;
    update public.cast_rooms
    set playing = p_playing,
        revision = revision + 1,
        updated_at = now()
    where id = room.id;
    return public.get_cast_room(room.share_token);
end;
$$;

revoke all on function public.get_cast_room(text) from public;
grant execute on function public.get_cast_room(text) to anon, authenticated;

revoke all on function public.get_cast_room_by_code(text) from public;
grant execute on function public.get_cast_room_by_code(text) to anon, authenticated;

revoke all on function public.cast_room_add_item(
    text, text, text, text, text, text, text, text, text, integer, text, integer, text, text, text, text, text
) from public;
grant execute on function public.cast_room_add_item(
    text, text, text, text, text, text, text, text, text, integer, text, integer, text, text, text, text, text
) to anon, authenticated;

revoke all on function public.cast_room_remove_item(text, uuid) from public;
grant execute on function public.cast_room_remove_item(text, uuid) to anon, authenticated;

revoke all on function public.cast_room_reorder(text, uuid[]) from public;
grant execute on function public.cast_room_reorder(text, uuid[]) to anon, authenticated;

revoke all on function public.cast_room_play_at(text, integer) from public;
grant execute on function public.cast_room_play_at(text, integer) to anon, authenticated;

revoke all on function public.cast_room_skip(text) from public;
grant execute on function public.cast_room_skip(text) to anon, authenticated;

revoke all on function public.cast_room_set_playing(text, boolean) from public;
grant execute on function public.cast_room_set_playing(text, boolean) to anon, authenticated;

do $$
begin
    if exists (
        select 1 from pg_publication where pubname = 'supabase_realtime'
    ) and not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'cast_rooms'
    ) then
        alter publication supabase_realtime add table public.cast_rooms;
    end if;
end $$;
