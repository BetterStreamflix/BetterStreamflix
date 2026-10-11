-- TV Sidekick Phone: scrubber seek, keyboard text for TV search, party chat.
-- Extends cast_rooms; still no stream URLs / media relay.

alter table public.cast_rooms
    add column if not exists sidekick_seek_ms bigint,
    add column if not exists sidekick_seek_seq bigint not null default 0,
    add column if not exists sidekick_text text,
    add column if not exists sidekick_text_seq bigint not null default 0;

comment on column public.cast_rooms.sidekick_seek_ms is
    'Phone scrubber target; TV host applies when sidekick_seek_seq advances.';
comment on column public.cast_rooms.sidekick_text is
    'Phone keyboard payload for TV search; cleared after host applies.';

create table if not exists public.cast_room_messages (
    id uuid primary key default gen_random_uuid(),
    room_id uuid not null references public.cast_rooms(id) on delete cascade,
    member_key text not null,
    display_name text not null default 'Guest',
    kind text not null check (kind in ('chat', 'react')),
    body text not null,
    created_at timestamptz not null default now(),
    constraint cast_room_messages_key_chk
        check (member_key ~ '^[A-Za-z0-9_-]{8,64}$'),
    constraint cast_room_messages_body_chk
        check (char_length(body) between 1 and 280)
);

create index if not exists cast_room_messages_room_created_idx
    on public.cast_room_messages (room_id, created_at);

alter table public.cast_room_messages enable row level security;

drop policy if exists "Hosts read cast room messages" on public.cast_room_messages;
create policy "Hosts read cast room messages"
    on public.cast_room_messages
    for select
    to authenticated
    using (
        exists (
            select 1 from public.cast_rooms r
            where r.id = room_id and r.host_user_id = (select auth.uid())
        )
    );

drop policy if exists "Hosts delete cast room messages" on public.cast_room_messages;
create policy "Hosts delete cast room messages"
    on public.cast_room_messages
    for delete
    to authenticated
    using (
        exists (
            select 1 from public.cast_rooms r
            where r.id = room_id and r.host_user_id = (select auth.uid())
        )
    );

-- Refresh guest/host snapshot with Sidekick fields.
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
        'sidekick_seek_ms', p_room.sidekick_seek_ms,
        'sidekick_seek_seq', p_room.sidekick_seek_seq,
        'sidekick_text', p_room.sidekick_text,
        'sidekick_text_seq', p_room.sidekick_text_seq,
        'items', items
    );
end;
$$;

create or replace function public.cast_room_seek(p_token text, p_position_ms bigint)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    room public.cast_rooms;
    target bigint;
begin
    room := public._cast_room_active(p_token);
    if room.id is null or p_position_ms is null then
        return null;
    end if;
    target := greatest(0, p_position_ms);
    update public.cast_rooms
    set position_ms = target,
        sidekick_seek_ms = target,
        sidekick_seek_seq = sidekick_seek_seq + 1,
        revision = revision + 1,
        updated_at = now()
    where id = room.id;
    return public.get_cast_room(room.share_token);
end;
$$;

create or replace function public.cast_room_sidekick_text(p_token text, p_text text)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    room public.cast_rooms;
    cleaned text;
begin
    room := public._cast_room_active(p_token);
    if room.id is null or p_text is null then
        return null;
    end if;
    cleaned := left(trim(p_text), 80);
    if cleaned = '' then
        return null;
    end if;
    update public.cast_rooms
    set sidekick_text = cleaned,
        sidekick_text_seq = sidekick_text_seq + 1,
        revision = revision + 1,
        updated_at = now()
    where id = room.id;
    return public.get_cast_room(room.share_token);
end;
$$;

create or replace function public.cast_room_ack_sidekick_text(p_token text)
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
    -- Host-only ack (auth.uid must own the room).
    if room.host_user_id is distinct from (select auth.uid()) then
        return null;
    end if;
    update public.cast_rooms
    set sidekick_text = null,
        updated_at = now()
    where id = room.id;
    return public.get_cast_room(room.share_token);
end;
$$;

create or replace function public.post_cast_room_message(
    p_token text,
    p_member_key text,
    p_display_name text,
    p_kind text,
    p_body text
)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    room public.cast_rooms;
    cleaned_name text;
    cleaned_body text;
begin
    room := public._cast_room_active(p_token);
    if room.id is null then
        return null;
    end if;
    if p_member_key is null or p_member_key !~ '^[A-Za-z0-9_-]{8,64}$' then
        return null;
    end if;
    if p_kind is null or p_kind not in ('chat', 'react') then
        return null;
    end if;
    cleaned_name := left(coalesce(nullif(trim(p_display_name), ''), 'Guest'), 40);
    cleaned_body := left(trim(coalesce(p_body, '')), 280);
    if cleaned_body = '' then
        return null;
    end if;
    insert into public.cast_room_messages (room_id, member_key, display_name, kind, body)
    values (room.id, p_member_key, cleaned_name, p_kind, cleaned_body);
    return json_build_object('ok', true);
end;
$$;

create or replace function public.list_cast_room_messages(p_token text, p_limit integer default 40)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    room public.cast_rooms;
    lim integer;
    result json;
begin
    room := public._cast_room_active(p_token);
    if room.id is null then
        return '[]'::json;
    end if;
    lim := least(greatest(coalesce(p_limit, 40), 1), 80);
    select coalesce(json_agg(row_to_json(x) order by x.created_at), '[]'::json)
    into result
    from (
        select
            m.id,
            m.member_key,
            m.display_name,
            m.kind,
            m.body,
            m.created_at
        from public.cast_room_messages m
        where m.room_id = room.id
        order by m.created_at desc
        limit lim
    ) x;
    return result;
end;
$$;

revoke all on function public.cast_room_seek(text, bigint) from public;
grant execute on function public.cast_room_seek(text, bigint) to anon, authenticated;

revoke all on function public.cast_room_sidekick_text(text, text) from public;
grant execute on function public.cast_room_sidekick_text(text, text) to anon, authenticated;

revoke all on function public.cast_room_ack_sidekick_text(text) from public;
grant execute on function public.cast_room_ack_sidekick_text(text) to authenticated;

revoke all on function public.post_cast_room_message(text, text, text, text, text) from public;
grant execute on function public.post_cast_room_message(text, text, text, text, text) to anon, authenticated;

revoke all on function public.list_cast_room_messages(text, integer) from public;
grant execute on function public.list_cast_room_messages(text, integer) to anon, authenticated;
