create table if not exists public.user_media_state (
    user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
    provider text not null,
    media_type text not null check (media_type in ('movie', 'tv_show', 'episode')),
    media_id text not null,
    parent_show_id text,
    parent_show_title text,
    parent_show_poster text,
    parent_show_banner text,
    season_id text,
    season_number integer,
    season_title text,
    season_poster text,
    episode_number integer,
    title text not null default '',
    poster text,
    banner text,
    is_favorite boolean not null default false,
    favorited_at_millis bigint,
    is_watched boolean not null default false,
    watched_at_millis bigint,
    last_engagement_at_millis bigint,
    playback_position_millis bigint,
    duration_millis bigint,
    is_watching boolean,
    last_played_at_millis bigint,
    last_played_episode_id text,
    client_updated_at_millis bigint not null,
    server_updated_at timestamptz not null default now(),
    primary key (user_id, provider, media_type, media_id)
);

create index if not exists user_media_state_user_updated_idx
    on public.user_media_state (user_id, server_updated_at desc);

alter table public.user_media_state enable row level security;

drop policy if exists "Users read their own media state" on public.user_media_state;
create policy "Users read their own media state"
    on public.user_media_state for select to authenticated
    using ((select auth.uid()) = user_id);

drop policy if exists "Users insert their own media state" on public.user_media_state;
create policy "Users insert their own media state"
    on public.user_media_state for insert to authenticated
    with check ((select auth.uid()) = user_id);

drop policy if exists "Users update their own media state" on public.user_media_state;
create policy "Users update their own media state"
    on public.user_media_state for update to authenticated
    using ((select auth.uid()) = user_id)
    with check ((select auth.uid()) = user_id);

drop policy if exists "Users delete their own media state" on public.user_media_state;
create policy "Users delete their own media state"
    on public.user_media_state for delete to authenticated
    using ((select auth.uid()) = user_id);

grant select, insert, update, delete on public.user_media_state to authenticated;

create or replace function public.set_user_media_state_server_updated_at()
returns trigger language plpgsql security invoker set search_path = ''
as $$
begin
    if new.client_updated_at_millis < old.client_updated_at_millis then
        return null;
    end if;
    new.server_updated_at = now();
    return new;
end;
$$;

drop trigger if exists set_user_media_state_server_updated_at on public.user_media_state;
create trigger set_user_media_state_server_updated_at
before update on public.user_media_state
for each row execute function public.set_user_media_state_server_updated_at();

do $$
begin
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'user_media_state'
    ) then
        alter publication supabase_realtime add table public.user_media_state;
    end if;
end;
$$;
-- Shareable watchlists (Favorites snapshot) with opaque share tokens.
-- Personal cross-device sync remains on user_media_state; this adds list links.

create table if not exists public.shared_watchlists (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references auth.users(id) on delete cascade,
    title text not null default 'Watchlist',
    share_token text not null unique,
    expires_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists shared_watchlists_owner_idx
    on public.shared_watchlists (owner_id);

create table if not exists public.shared_watchlist_items (
    list_id uuid not null references public.shared_watchlists(id) on delete cascade,
    provider text not null,
    media_type text not null check (media_type in ('movie', 'tv_show')),
    media_id text not null,
    title text not null default '',
    poster text,
    banner text,
    sort_index integer not null default 0,
    primary key (list_id, provider, media_type, media_id)
);

create index if not exists shared_watchlist_items_list_idx
    on public.shared_watchlist_items (list_id, sort_index);

alter table public.shared_watchlists enable row level security;
alter table public.shared_watchlist_items enable row level security;

drop policy if exists "Owners read their shared watchlists" on public.shared_watchlists;
create policy "Owners read their shared watchlists"
    on public.shared_watchlists
    for select
    to authenticated
    using ((select auth.uid()) = owner_id);

drop policy if exists "Owners insert their shared watchlists" on public.shared_watchlists;
create policy "Owners insert their shared watchlists"
    on public.shared_watchlists
    for insert
    to authenticated
    with check ((select auth.uid()) = owner_id);

drop policy if exists "Owners update their shared watchlists" on public.shared_watchlists;
create policy "Owners update their shared watchlists"
    on public.shared_watchlists
    for update
    to authenticated
    using ((select auth.uid()) = owner_id)
    with check ((select auth.uid()) = owner_id);

drop policy if exists "Owners delete their shared watchlists" on public.shared_watchlists;
create policy "Owners delete their shared watchlists"
    on public.shared_watchlists
    for delete
    to authenticated
    using ((select auth.uid()) = owner_id);

drop policy if exists "Owners read their shared watchlist items" on public.shared_watchlist_items;
create policy "Owners read their shared watchlist items"
    on public.shared_watchlist_items
    for select
    to authenticated
    using (
        exists (
            select 1 from public.shared_watchlists w
            where w.id = list_id and w.owner_id = (select auth.uid())
        )
    );

drop policy if exists "Owners insert their shared watchlist items" on public.shared_watchlist_items;
create policy "Owners insert their shared watchlist items"
    on public.shared_watchlist_items
    for insert
    to authenticated
    with check (
        exists (
            select 1 from public.shared_watchlists w
            where w.id = list_id and w.owner_id = (select auth.uid())
        )
    );

drop policy if exists "Owners update their shared watchlist items" on public.shared_watchlist_items;
create policy "Owners update their shared watchlist items"
    on public.shared_watchlist_items
    for update
    to authenticated
    using (
        exists (
            select 1 from public.shared_watchlists w
            where w.id = list_id and w.owner_id = (select auth.uid())
        )
    )
    with check (
        exists (
            select 1 from public.shared_watchlists w
            where w.id = list_id and w.owner_id = (select auth.uid())
        )
    );

drop policy if exists "Owners delete their shared watchlist items" on public.shared_watchlist_items;
create policy "Owners delete their shared watchlist items"
    on public.shared_watchlist_items
    for delete
    to authenticated
    using (
        exists (
            select 1 from public.shared_watchlists w
            where w.id = list_id and w.owner_id = (select auth.uid())
        )
    );

grant select, insert, update, delete on public.shared_watchlists to authenticated;
grant select, insert, update, delete on public.shared_watchlist_items to authenticated;

create or replace function public.get_shared_watchlist(p_token text)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    result json;
    cleaned text;
begin
    -- Align with client SharedWatchlistLinks TOKEN_PATTERN: [A-Za-z0-9_-]{8,128}
    if p_token is null then
        return null;
    end if;
    cleaned := trim(p_token);
    if cleaned !~ '^[A-Za-z0-9_-]{8,128}$' then
        return null;
    end if;

    select json_build_object(
        'id', w.id,
        'title', w.title,
        'share_token', w.share_token,
        'updated_at', w.updated_at,
        'expires_at', w.expires_at,
        'items', coalesce((
            select json_agg(
                json_build_object(
                    'provider', i.provider,
                    'media_type', i.media_type,
                    'media_id', i.media_id,
                    'title', i.title,
                    'poster', i.poster,
                    'banner', i.banner,
                    'sort_index', i.sort_index
                )
                order by i.sort_index, i.title
            )
            from public.shared_watchlist_items i
            where i.list_id = w.id
        ), '[]'::json)
    )
    into result
    from public.shared_watchlists w
    where w.share_token = cleaned
      and (w.expires_at is null or w.expires_at >= now());

    return result;
end;
$$;

revoke all on function public.get_shared_watchlist(text) from public;
grant execute on function public.get_shared_watchlist(text) to anon, authenticated;

-- Watch-Party 2.0: lobby + host server pick + chat/reacts; position/server sync only (no media relay).
create table if not exists public.watch_parties (
    id uuid primary key default gen_random_uuid(),
    share_token text not null unique,
    host_user_id uuid not null references auth.users(id) on delete cascade,
    provider text not null,
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
    server_id text,
    server_name text,
    position_ms bigint not null default 0,
    playing boolean not null default false,
    in_lobby boolean not null default true,
    ended boolean not null default false,
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

alter table public.watch_parties
    add column if not exists in_lobby boolean not null default true;

create index if not exists watch_parties_host_idx
    on public.watch_parties (host_user_id);

create index if not exists watch_parties_token_idx
    on public.watch_parties (share_token);

alter table public.watch_parties enable row level security;

drop policy if exists "Hosts read their watch parties" on public.watch_parties;
create policy "Hosts read their watch parties"
    on public.watch_parties for select to authenticated
    using ((select auth.uid()) = host_user_id);

drop policy if exists "Hosts insert their watch parties" on public.watch_parties;
create policy "Hosts insert their watch parties"
    on public.watch_parties for insert to authenticated
    with check ((select auth.uid()) = host_user_id);

drop policy if exists "Hosts update their watch parties" on public.watch_parties;
create policy "Hosts update their watch parties"
    on public.watch_parties for update to authenticated
    using ((select auth.uid()) = host_user_id)
    with check ((select auth.uid()) = host_user_id);

drop policy if exists "Hosts delete their watch parties" on public.watch_parties;
create policy "Hosts delete their watch parties"
    on public.watch_parties for delete to authenticated
    using ((select auth.uid()) = host_user_id);

grant select, insert, update, delete on public.watch_parties to authenticated;

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

do $$
begin
    if exists (
        select 1 from pg_publication where pubname = 'supabase_realtime'
    ) and not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'watch_parties'
    ) then
        alter publication supabase_realtime add table public.watch_parties;
    end if;
end $$;

-- Watch-Party 2.0 lobby / chat tables + RPCs (idempotent; matches migration 20261011220000).
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

drop policy if exists "Hosts read party members" on public.watch_party_members;
create policy "Hosts read party members"
    on public.watch_party_members for select to authenticated
    using (
        exists (
            select 1 from public.watch_parties p
            where p.id = party_id and p.host_user_id = (select auth.uid())
        )
    );

drop policy if exists "Hosts delete party members" on public.watch_party_members;
create policy "Hosts delete party members"
    on public.watch_party_members for delete to authenticated
    using (
        exists (
            select 1 from public.watch_parties p
            where p.id = party_id and p.host_user_id = (select auth.uid())
        )
    );

drop policy if exists "Hosts read party messages" on public.watch_party_messages;
create policy "Hosts read party messages"
    on public.watch_party_messages for select to authenticated
    using (
        exists (
            select 1 from public.watch_parties p
            where p.id = party_id and p.host_user_id = (select auth.uid())
        )
    );

drop policy if exists "Hosts delete party messages" on public.watch_party_messages;
create policy "Hosts delete party messages"
    on public.watch_party_messages for delete to authenticated
    using (
        exists (
            select 1 from public.watch_parties p
            where p.id = party_id and p.host_user_id = (select auth.uid())
        )
    );

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
-- Session Handoff: Phone→TV “continue here” with a short code.
-- Transfers media identity + position + preferred server only (no stream URLs).

create table if not exists public.session_handoffs (
    id uuid primary key default gen_random_uuid(),
    share_token text not null unique,
    short_code text not null unique,
    host_user_id uuid not null references auth.users(id) on delete cascade,
    provider text not null,
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
    server_id text,
    server_name text,
    position_ms bigint not null default 0,
    claimed_by uuid references auth.users(id) on delete set null,
    claimed_at timestamptz,
    ended boolean not null default false,
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint session_handoffs_token_chk check (share_token ~ '^[A-Za-z0-9_-]{8,128}$'),
    constraint session_handoffs_code_chk check (short_code ~ '^[A-Z2-9]{6}$'),
    constraint session_handoffs_position_chk check (position_ms >= 0)
);

create index if not exists session_handoffs_host_idx
    on public.session_handoffs (host_user_id);

create index if not exists session_handoffs_token_idx
    on public.session_handoffs (share_token);

create index if not exists session_handoffs_code_idx
    on public.session_handoffs (short_code);

comment on table public.session_handoffs is
    'Phone→TV session handoff: short code carries progress + server (no media relay).';

alter table public.session_handoffs enable row level security;

drop policy if exists "Hosts manage their session handoffs" on public.session_handoffs;
create policy "Hosts manage their session handoffs"
    on public.session_handoffs
    for all
    to authenticated
    using ((select auth.uid()) = host_user_id)
    with check ((select auth.uid()) = host_user_id);

grant select, insert, update, delete on public.session_handoffs to authenticated;

create or replace function public._session_handoff_json(p public.session_handoffs)
returns json
language plpgsql
stable
security definer
set search_path = public
as $$
begin
    return json_build_object(
        'id', p.id,
        'share_token', p.share_token,
        'short_code', p.short_code,
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
        'claimed', p.claimed_at is not null,
        'ended', p.ended,
        'expires_at', p.expires_at,
        'updated_at', p.updated_at
    );
end;
$$;

create or replace function public.get_session_handoff_by_code(p_code text)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    cleaned text;
    row public.session_handoffs;
begin
    if p_code is null then
        return null;
    end if;
    cleaned := upper(trim(p_code));
    cleaned := replace(replace(replace(cleaned, '0', 'O'), '1', 'I'), 'L', 'I');
    if cleaned !~ '^[A-Z2-9]{6}$' then
        return null;
    end if;
    select * into row
    from public.session_handoffs h
    where h.short_code = cleaned
      and h.expires_at >= now()
      and h.ended = false
      and h.claimed_at is null;
    if row.id is null then
        return null;
    end if;
    return public._session_handoff_json(row);
end;
$$;

create or replace function public.claim_session_handoff(p_code text)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    cleaned text;
    row public.session_handoffs;
    uid uuid;
begin
    uid := auth.uid();
    if uid is null then
        return null;
    end if;
    if p_code is null then
        return null;
    end if;
    cleaned := upper(trim(p_code));
    cleaned := replace(replace(replace(cleaned, '0', 'O'), '1', 'I'), 'L', 'I');
    if cleaned !~ '^[A-Z2-9]{6}$' then
        return null;
    end if;

    update public.session_handoffs h
    set claimed_by = uid,
        claimed_at = now(),
        ended = true,
        updated_at = now()
    where h.short_code = cleaned
      and h.expires_at >= now()
      and h.ended = false
      and h.claimed_at is null
    returning * into row;

    if row.id is null then
        return null;
    end if;
    return public._session_handoff_json(row);
end;
$$;

create or replace function public.get_session_handoff(p_token text)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    cleaned text;
    row public.session_handoffs;
begin
    if p_token is null then
        return null;
    end if;
    cleaned := trim(p_token);
    if cleaned !~ '^[A-Za-z0-9_-]{8,128}$' then
        return null;
    end if;
    select * into row
    from public.session_handoffs h
    where h.share_token = cleaned
      and h.expires_at >= now();
    if row.id is null then
        return null;
    end if;
    return public._session_handoff_json(row);
end;
$$;

grant execute on function public.get_session_handoff_by_code(text) to authenticated, anon;
grant execute on function public.claim_session_handoff(text) to authenticated;
grant execute on function public.get_session_handoff(text) to authenticated, anon;

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

-- Cast Hand-off reverse: TV offers a one-shot short code; Phone claims and
-- continues at the same position + chosen server. Media identity only — no
-- stream URLs/cookies.

create table if not exists public.cast_handoffs (
    id uuid primary key default gen_random_uuid(),
    short_code text not null unique,
    host_user_id uuid not null references auth.users(id) on delete cascade,
    provider text not null,
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
    server_id text,
    server_name text,
    position_ms bigint not null default 0,
    claimed_at timestamptz,
    revoked boolean not null default false,
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint cast_handoffs_code_chk check (short_code ~ '^[A-Z2-9]{6}$'),
    constraint cast_handoffs_position_chk check (position_ms >= 0)
);

create index if not exists cast_handoffs_host_idx
    on public.cast_handoffs (host_user_id);

create index if not exists cast_handoffs_code_idx
    on public.cast_handoffs (short_code);

create index if not exists cast_handoffs_active_idx
    on public.cast_handoffs (host_user_id, revoked, claimed_at)
    where revoked = false and claimed_at is null;

comment on table public.cast_handoffs is
    'One-shot TV→Phone cast hand-offs: short code carries media identity + server + position (no stream relay).';

alter table public.cast_handoffs enable row level security;

drop policy if exists "Hosts read their cast handoffs" on public.cast_handoffs;
create policy "Hosts read their cast handoffs"
    on public.cast_handoffs
    for select
    to authenticated
    using ((select auth.uid()) = host_user_id);

drop policy if exists "Hosts insert their cast handoffs" on public.cast_handoffs;
create policy "Hosts insert their cast handoffs"
    on public.cast_handoffs
    for insert
    to authenticated
    with check ((select auth.uid()) = host_user_id);

drop policy if exists "Hosts update their cast handoffs" on public.cast_handoffs;
create policy "Hosts update their cast handoffs"
    on public.cast_handoffs
    for update
    to authenticated
    using ((select auth.uid()) = host_user_id)
    with check ((select auth.uid()) = host_user_id);

drop policy if exists "Hosts delete their cast handoffs" on public.cast_handoffs;
create policy "Hosts delete their cast handoffs"
    on public.cast_handoffs
    for delete
    to authenticated
    using ((select auth.uid()) = host_user_id);

grant select, insert, update, delete on public.cast_handoffs to authenticated;

create or replace function public._cast_handoff_json(p_row public.cast_handoffs)
returns json
language plpgsql
stable
security definer
set search_path = public
as $$
begin
    return json_build_object(
        'id', p_row.id,
        'short_code', p_row.short_code,
        'provider', p_row.provider,
        'media_type', p_row.media_type,
        'media_id', p_row.media_id,
        'title', p_row.title,
        'subtitle', p_row.subtitle,
        'poster', p_row.poster,
        'show_id', p_row.show_id,
        'show_title', p_row.show_title,
        'show_poster', p_row.show_poster,
        'season_number', p_row.season_number,
        'season_title', p_row.season_title,
        'episode_number', p_row.episode_number,
        'episode_title', p_row.episode_title,
        'release_date', p_row.release_date,
        'imdb_id', p_row.imdb_id,
        'server_id', p_row.server_id,
        'server_name', p_row.server_name,
        'position_ms', p_row.position_ms,
        'claimed_at', p_row.claimed_at,
        'revoked', p_row.revoked,
        'expires_at', p_row.expires_at,
        'updated_at', p_row.updated_at
    );
end;
$$;

create or replace function public.get_cast_handoff_by_code(p_code text)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    cleaned text;
    row_data public.cast_handoffs;
begin
    if p_code is null then
        return null;
    end if;
    cleaned := upper(trim(p_code));
    cleaned := replace(replace(replace(cleaned, '0', 'O'), '1', 'I'), 'L', 'I');
    if cleaned !~ '^[A-Z2-9]{6}$' then
        return null;
    end if;
    select * into row_data
    from public.cast_handoffs h
    where h.short_code = cleaned
      and h.expires_at >= now()
      and h.revoked = false
      and h.claimed_at is null;
    if row_data.id is null then
        return null;
    end if;
    return public._cast_handoff_json(row_data);
end;
$$;

create or replace function public.claim_cast_handoff(p_code text)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    cleaned text;
    row_data public.cast_handoffs;
begin
    if p_code is null then
        return null;
    end if;
    cleaned := upper(trim(p_code));
    cleaned := replace(replace(replace(cleaned, '0', 'O'), '1', 'I'), 'L', 'I');
    if cleaned !~ '^[A-Z2-9]{6}$' then
        return null;
    end if;

    update public.cast_handoffs h
    set claimed_at = now(),
        updated_at = now()
    where h.short_code = cleaned
      and h.expires_at >= now()
      and h.revoked = false
      and h.claimed_at is null
    returning * into row_data;

    if row_data.id is null then
        return null;
    end if;
    return public._cast_handoff_json(row_data);
end;
$$;

revoke all on function public.get_cast_handoff_by_code(text) from public;
grant execute on function public.get_cast_handoff_by_code(text) to anon, authenticated;

revoke all on function public.claim_cast_handoff(text) from public;
grant execute on function public.claim_cast_handoff(text) to anon, authenticated;
