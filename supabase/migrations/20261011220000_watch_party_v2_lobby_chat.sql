-- Watch-Party 2.0: lobby before play, host server pick, chat + emoji reacts.
-- Still position + server identity only — no stream URLs / video relay.

alter table public.watch_parties
    add column if not exists in_lobby boolean not null default true;

-- Parties that were already syncing before this migration leave the lobby.
update public.watch_parties
set in_lobby = false
where ended = false
  and playing = true
  and in_lobby = true;

comment on column public.watch_parties.in_lobby is
    'Watch-Party 2.0: true until the host starts playback for everyone.';

create table if not exists public.watch_party_members (
    id uuid primary key default gen_random_uuid(),
    party_id uuid not null references public.watch_parties(id) on delete cascade,
    member_key text not null,
    display_name text not null default 'Guest',
    is_host boolean not null default false,
    last_seen_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    unique (party_id, member_key),
    constraint watch_party_members_key_chk
        check (member_key ~ '^[A-Za-z0-9_-]{8,64}$'),
    constraint watch_party_members_name_chk
        check (char_length(display_name) between 1 and 40)
);

create index if not exists watch_party_members_party_idx
    on public.watch_party_members (party_id);

create table if not exists public.watch_party_messages (
    id uuid primary key default gen_random_uuid(),
    party_id uuid not null references public.watch_parties(id) on delete cascade,
    member_key text not null,
    display_name text not null default 'Guest',
    kind text not null check (kind in ('chat', 'react')),
    body text not null,
    created_at timestamptz not null default now(),
    constraint watch_party_messages_key_chk
        check (member_key ~ '^[A-Za-z0-9_-]{8,64}$'),
    constraint watch_party_messages_body_chk
        check (char_length(body) between 1 and 280)
);

create index if not exists watch_party_messages_party_created_idx
    on public.watch_party_messages (party_id, created_at);

alter table public.watch_party_members enable row level security;
alter table public.watch_party_messages enable row level security;

-- Guests use security-definer RPCs only (no direct table grants).
-- Hosts may read/delete their own party rows for cleanup.
drop policy if exists "Hosts read party members" on public.watch_party_members;
create policy "Hosts read party members"
    on public.watch_party_members
    for select
    to authenticated
    using (
        exists (
            select 1 from public.watch_parties p
            where p.id = party_id and p.host_user_id = (select auth.uid())
        )
    );

drop policy if exists "Hosts delete party members" on public.watch_party_members;
create policy "Hosts delete party members"
    on public.watch_party_members
    for delete
    to authenticated
    using (
        exists (
            select 1 from public.watch_parties p
            where p.id = party_id and p.host_user_id = (select auth.uid())
        )
    );

drop policy if exists "Hosts read party messages" on public.watch_party_messages;
create policy "Hosts read party messages"
    on public.watch_party_messages
    for select
    to authenticated
    using (
        exists (
            select 1 from public.watch_parties p
            where p.id = party_id and p.host_user_id = (select auth.uid())
        )
    );

drop policy if exists "Hosts delete party messages" on public.watch_party_messages;
create policy "Hosts delete party messages"
    on public.watch_party_messages
    for delete
    to authenticated
    using (
        exists (
            select 1 from public.watch_parties p
            where p.id = party_id and p.host_user_id = (select auth.uid())
        )
    );

-- Refresh guest snapshot with lobby flag + server pick.
create or replace function public.get_watch_party(p_token text)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    result json;
    cleaned text;
begin
    if p_token is null then
        return null;
    end if;
    cleaned := trim(p_token);
    if cleaned !~ '^[A-Za-z0-9_-]{8,128}$' then
        return null;
    end if;

    select json_build_object(
        'id', p.id,
        'share_token', p.share_token,
        'provider', p.provider,
        'media_type', p.media_type,
        'media_id', p.media_id,
        'title', p.title,
        'subtitle', p.subtitle,
        'poster', p.poster,
        'show_id', p.show_id,
        'show_title', p.show_title,
        'show_poster', p.show_poster,
        'season_number', p.season_number,
        'season_title', p.season_title,
        'episode_number', p.episode_number,
        'episode_title', p.episode_title,
        'release_date', p.release_date,
        'imdb_id', p.imdb_id,
        'server_id', p.server_id,
        'server_name', p.server_name,
        'position_ms', p.position_ms,
        'playing', p.playing,
        'in_lobby', p.in_lobby,
        'ended', p.ended,
        'expires_at', p.expires_at,
        'updated_at', p.updated_at
    )
    into result
    from public.watch_parties p
    where p.share_token = cleaned
      and p.expires_at >= now()
      and p.ended = false;

    return result;
end;
$$;

revoke all on function public.get_watch_party(text) from public;
grant execute on function public.get_watch_party(text) to anon, authenticated;

create or replace function public.get_watch_party_lobby(p_token text)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    cleaned text;
    party json;
    members json;
begin
    if p_token is null then
        return null;
    end if;
    cleaned := trim(p_token);
    if cleaned !~ '^[A-Za-z0-9_-]{8,128}$' then
        return null;
    end if;

    party := public.get_watch_party(cleaned);
    if party is null then
        return null;
    end if;

    select coalesce(json_agg(
        json_build_object(
            'member_key', m.member_key,
            'display_name', m.display_name,
            'is_host', m.is_host,
            'last_seen_at', m.last_seen_at
        )
        order by m.is_host desc, m.created_at asc
    ), '[]'::json)
    into members
    from public.watch_party_members m
    join public.watch_parties p on p.id = m.party_id
    where p.share_token = cleaned
      and m.last_seen_at >= now() - interval '5 minutes';

    return json_build_object(
        'party', party,
        'members', members
    );
end;
$$;

revoke all on function public.get_watch_party_lobby(text) from public;
grant execute on function public.get_watch_party_lobby(text) to anon, authenticated;

create or replace function public.join_watch_party_lobby(
    p_token text,
    p_member_key text,
    p_display_name text,
    p_is_host boolean default false
)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    cleaned text;
    key_clean text;
    name_clean text;
    host_claim boolean;
    party record;
begin
    if p_token is null or p_member_key is null then
        return null;
    end if;
    cleaned := trim(p_token);
    key_clean := trim(p_member_key);
    if cleaned !~ '^[A-Za-z0-9_-]{8,128}$' then
        return null;
    end if;
    if key_clean !~ '^[A-Za-z0-9_-]{8,64}$' then
        return null;
    end if;
    name_clean := left(trim(coalesce(p_display_name, 'Guest')), 40);
    if name_clean = '' then
        name_clean := 'Guest';
    end if;

    select * into party
    from public.watch_parties p
    where p.share_token = cleaned
      and p.expires_at >= now()
      and p.ended = false;
    if not found then
        return null;
    end if;

    -- Only the authenticated host may claim is_host.
    host_claim := coalesce(p_is_host, false)
        and (select auth.uid()) is not distinct from party.host_user_id;

    insert into public.watch_party_members as m (
        party_id, member_key, display_name, is_host, last_seen_at
    ) values (
        party.id, key_clean, name_clean, host_claim, now()
    )
    on conflict (party_id, member_key) do update
        set display_name = excluded.display_name,
            is_host = m.is_host or excluded.is_host,
            last_seen_at = now();

    return public.get_watch_party_lobby(cleaned);
end;
$$;

revoke all on function public.join_watch_party_lobby(text, text, text, boolean) from public;
grant execute on function public.join_watch_party_lobby(text, text, text, boolean)
    to anon, authenticated;

create or replace function public.heartbeat_watch_party_member(
    p_token text,
    p_member_key text
)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
    cleaned text;
    key_clean text;
    updated int;
begin
    if p_token is null or p_member_key is null then
        return false;
    end if;
    cleaned := trim(p_token);
    key_clean := trim(p_member_key);
    if cleaned !~ '^[A-Za-z0-9_-]{8,128}$' then
        return false;
    end if;
    if key_clean !~ '^[A-Za-z0-9_-]{8,64}$' then
        return false;
    end if;

    update public.watch_party_members m
    set last_seen_at = now()
    from public.watch_parties p
    where m.party_id = p.id
      and p.share_token = cleaned
      and p.ended = false
      and p.expires_at >= now()
      and m.member_key = key_clean;

    get diagnostics updated = row_count;
    return updated > 0;
end;
$$;

revoke all on function public.heartbeat_watch_party_member(text, text) from public;
grant execute on function public.heartbeat_watch_party_member(text, text)
    to anon, authenticated;

create or replace function public.post_watch_party_message(
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
    cleaned text;
    key_clean text;
    name_clean text;
    kind_clean text;
    body_clean text;
    party_id uuid;
    msg public.watch_party_messages%rowtype;
begin
    if p_token is null or p_member_key is null or p_kind is null or p_body is null then
        return null;
    end if;
    cleaned := trim(p_token);
    key_clean := trim(p_member_key);
    kind_clean := lower(trim(p_kind));
    body_clean := trim(p_body);
    if cleaned !~ '^[A-Za-z0-9_-]{8,128}$' then
        return null;
    end if;
    if key_clean !~ '^[A-Za-z0-9_-]{8,64}$' then
        return null;
    end if;
    if kind_clean not in ('chat', 'react') then
        return null;
    end if;
    if char_length(body_clean) < 1 or char_length(body_clean) > 280 then
        return null;
    end if;
    -- Emoji reacts: allow only the curated set used by the app.
    if kind_clean = 'react' and body_clean not in ('😂', '🔥', '👏', '😮', '❤️', '👍') then
        return null;
    end if;
    name_clean := left(trim(coalesce(p_display_name, 'Guest')), 40);
    if name_clean = '' then
        name_clean := 'Guest';
    end if;

    select p.id into party_id
    from public.watch_parties p
    where p.share_token = cleaned
      and p.expires_at >= now()
      and p.ended = false;
    if party_id is null then
        return null;
    end if;

    -- Ensure the sender is registered in the lobby (presence).
    insert into public.watch_party_members as m (
        party_id, member_key, display_name, is_host, last_seen_at
    ) values (
        party_id, key_clean, name_clean, false, now()
    )
    on conflict (party_id, member_key) do update
        set display_name = excluded.display_name,
            last_seen_at = now();

    insert into public.watch_party_messages (
        party_id, member_key, display_name, kind, body
    ) values (
        party_id, key_clean, name_clean, kind_clean, body_clean
    )
    returning * into msg;

    return json_build_object(
        'id', msg.id,
        'member_key', msg.member_key,
        'display_name', msg.display_name,
        'kind', msg.kind,
        'body', msg.body,
        'created_at', msg.created_at
    );
end;
$$;

revoke all on function public.post_watch_party_message(text, text, text, text, text) from public;
grant execute on function public.post_watch_party_message(text, text, text, text, text)
    to anon, authenticated;

create or replace function public.list_watch_party_messages(
    p_token text,
    p_after timestamptz default null
)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    cleaned text;
    result json;
begin
    if p_token is null then
        return '[]'::json;
    end if;
    cleaned := trim(p_token);
    if cleaned !~ '^[A-Za-z0-9_-]{8,128}$' then
        return '[]'::json;
    end if;

    select coalesce(json_agg(row_json order by created_at asc), '[]'::json)
    into result
    from (
        select json_build_object(
            'id', m.id,
            'member_key', m.member_key,
            'display_name', m.display_name,
            'kind', m.kind,
            'body', m.body,
            'created_at', m.created_at
        ) as row_json,
        m.created_at
        from public.watch_party_messages m
        join public.watch_parties p on p.id = m.party_id
        where p.share_token = cleaned
          and p.expires_at >= now()
          and p.ended = false
          and (p_after is null or m.created_at > p_after)
        order by m.created_at asc
        limit 100
    ) q;

    return result;
end;
$$;

revoke all on function public.list_watch_party_messages(text, timestamptz) from public;
grant execute on function public.list_watch_party_messages(text, timestamptz)
    to anon, authenticated;

-- Host-only: leave lobby and start synced playback for everyone.
create or replace function public.start_watch_party(
    p_token text,
    p_server_id text default null,
    p_server_name text default null
)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    cleaned text;
    uid uuid;
    updated int;
begin
    uid := auth.uid();
    if uid is null or p_token is null then
        return null;
    end if;
    cleaned := trim(p_token);
    if cleaned !~ '^[A-Za-z0-9_-]{8,128}$' then
        return null;
    end if;

    update public.watch_parties
    set in_lobby = false,
        playing = true,
        server_id = coalesce(nullif(trim(p_server_id), ''), server_id),
        server_name = coalesce(nullif(trim(p_server_name), ''), server_name),
        updated_at = now()
    where share_token = cleaned
      and host_user_id = uid
      and ended = false
      and expires_at >= now();

    get diagnostics updated = row_count;
    if updated = 0 then
        return null;
    end if;
    return public.get_watch_party(cleaned);
end;
$$;

revoke all on function public.start_watch_party(text, text, text) from public;
grant execute on function public.start_watch_party(text, text, text) to authenticated;
