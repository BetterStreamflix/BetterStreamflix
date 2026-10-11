-- Watch Together Pro security: sidekick gates, sticky kicks, leave, chat membership.
-- CC-001 / CC-025 / CC-065 / CC-066

create table if not exists public.watch_party_kicks (
    party_id uuid not null references public.watch_parties(id) on delete cascade,
    member_key text not null,
    kicked_at timestamptz not null default now(),
    primary key (party_id, member_key),
    constraint watch_party_kicks_key_chk
        check (member_key ~ '^[A-Za-z0-9_-]{8,64}$')
);

create index if not exists watch_party_kicks_party_idx
    on public.watch_party_kicks (party_id);

alter table public.watch_party_kicks enable row level security;

drop policy if exists "Hosts read party kicks" on public.watch_party_kicks;
create policy "Hosts read party kicks"
    on public.watch_party_kicks
    for select
    to authenticated
    using (
        exists (
            select 1 from public.watch_parties p
            where p.id = party_id and p.host_user_id = (select auth.uid())
        )
    );

-- Sidekick seek: tv_screen + lobby member (or authenticated); bump seek seq only.
drop function if exists public.watch_party_sidekick_seek(text, bigint);
create or replace function public.watch_party_sidekick_seek(
    p_token text,
    p_position_ms bigint,
    p_member_key text default null
)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    party public.watch_parties;
    target bigint;
    key_clean text;
    is_member boolean;
begin
    party := public._watch_party_active(p_token);
    if party.id is null or p_position_ms is null then
        return null;
    end if;
    if party.display_mode is distinct from 'tv_screen' then
        return null;
    end if;

    key_clean := trim(coalesce(p_member_key, ''));
    if key_clean ~ '^[A-Za-z0-9_-]{8,64}$' then
        if exists (
            select 1 from public.watch_party_kicks k
            where k.party_id = party.id and k.member_key = key_clean
        ) then
            return null;
        end if;
        select exists (
            select 1 from public.watch_party_members m
            where m.party_id = party.id and m.member_key = key_clean
        ) into is_member;
        if not is_member then
            return null;
        end if;
    elsif (select auth.uid()) is null then
        return null;
    end if;

    target := greatest(0, p_position_ms);
    update public.watch_parties
    set position_ms = target,
        sidekick_seek_ms = target,
        sidekick_seek_seq = sidekick_seek_seq + 1,
        updated_at = now()
    where id = party.id;
    return public.get_watch_party(party.share_token);
end;
$$;

-- Sidekick play: same gates; bump play seq only (no sync_revision).
drop function if exists public.watch_party_sidekick_play(text, boolean);
create or replace function public.watch_party_sidekick_play(
    p_token text,
    p_playing boolean,
    p_member_key text default null
)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    party public.watch_parties;
    key_clean text;
    is_member boolean;
begin
    party := public._watch_party_active(p_token);
    if party.id is null or p_playing is null then
        return null;
    end if;
    if party.display_mode is distinct from 'tv_screen' then
        return null;
    end if;

    key_clean := trim(coalesce(p_member_key, ''));
    if key_clean ~ '^[A-Za-z0-9_-]{8,64}$' then
        if exists (
            select 1 from public.watch_party_kicks k
            where k.party_id = party.id and k.member_key = key_clean
        ) then
            return null;
        end if;
        select exists (
            select 1 from public.watch_party_members m
            where m.party_id = party.id and m.member_key = key_clean
        ) into is_member;
        if not is_member then
            return null;
        end if;
    elsif (select auth.uid()) is null then
        return null;
    end if;

    update public.watch_parties
    set playing = p_playing,
        sidekick_playing = p_playing,
        sidekick_play_seq = sidekick_play_seq + 1,
        updated_at = now()
    where id = party.id;
    return public.get_watch_party(party.share_token);
end;
$$;

revoke all on function public.watch_party_sidekick_seek(text, bigint, text) from public;
grant execute on function public.watch_party_sidekick_seek(text, bigint, text)
    to anon, authenticated;

revoke all on function public.watch_party_sidekick_play(text, boolean, text) from public;
grant execute on function public.watch_party_sidekick_play(text, boolean, text)
    to anon, authenticated;

-- Sticky kick deny-list.
create or replace function public.kick_watch_party_member(p_token text, p_member_key text)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    party public.watch_parties;
    cleaned text;
begin
    party := public._watch_party_active(p_token);
    if party.id is null then
        return null;
    end if;
    if party.host_user_id is distinct from (select auth.uid()) then
        return null;
    end if;
    cleaned := trim(coalesce(p_member_key, ''));
    if cleaned !~ '^[A-Za-z0-9_-]{8,64}$' then
        return null;
    end if;
    delete from public.watch_party_members m
    where m.party_id = party.id
      and m.member_key = cleaned
      and m.is_host = false;
    delete from public.watch_party_sync_reports r
    where r.party_id = party.id
      and r.member_key = cleaned;
    insert into public.watch_party_kicks (party_id, member_key, kicked_at)
    values (party.id, cleaned, now())
    on conflict (party_id, member_key) do update
        set kicked_at = excluded.kicked_at;
    return public.get_watch_party_lobby(party.share_token);
end;
$$;

revoke all on function public.kick_watch_party_member(text, text) from public;
grant execute on function public.kick_watch_party_member(text, text) to authenticated;

-- Join refuses kicked members.
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

    if exists (
        select 1 from public.watch_party_kicks k
        where k.party_id = party.id and k.member_key = key_clean
    ) then
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

-- Chat requires existing (non-kicked) member — no auto-rejoin.
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
    updated int;
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

    if exists (
        select 1 from public.watch_party_kicks k
        where k.party_id = party_id and k.member_key = key_clean
    ) then
        return null;
    end if;

    update public.watch_party_members m
    set display_name = name_clean,
        last_seen_at = now()
    where m.party_id = party_id
      and m.member_key = key_clean;
    get diagnostics updated = row_count;
    if updated = 0 then
        return null;
    end if;

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

-- Explicit leave deletes membership row (CC-065).
create or replace function public.leave_watch_party_member(
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
    party_id uuid;
    deleted int;
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

    select p.id into party_id
    from public.watch_parties p
    where p.share_token = cleaned
      and p.expires_at >= now()
      and p.ended = false;
    if party_id is null then
        return false;
    end if;

    delete from public.watch_party_members m
    where m.party_id = party_id
      and m.member_key = key_clean
      and m.is_host = false;
    get diagnostics deleted = row_count;

    delete from public.watch_party_sync_reports r
    where r.party_id = party_id
      and r.member_key = key_clean;

    return deleted > 0;
end;
$$;

revoke all on function public.leave_watch_party_member(text, text) from public;
grant execute on function public.leave_watch_party_member(text, text)
    to anon, authenticated;

-- =============================================================================
-- Cast Companion security (CC-003 / CC-004 / CC-052) — Session + Cast handoffs
-- Appended alongside Watch Together Pro section in this migration file.
-- =============================================================================

-- CC-003: claimed_by + auth-only claim for Cast Hand-off reverse
alter table public.cast_handoffs
    add column if not exists claimed_by uuid references auth.users(id) on delete set null;

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

-- Shared code cleaner aligned with CompanionShortCodes (0/O→Q, 1/I/L→J).
create or replace function public._companion_short_code_clean(p_code text)
returns text
language plpgsql
immutable
as $$
declare
    cleaned text;
    ch text;
    out text := '';
begin
    if p_code is null then
        return null;
    end if;
    cleaned := upper(trim(p_code));
    cleaned := replace(replace(replace(replace(cleaned, '-', ''), ' ', ''), E'\t', ''), E'\n', '');
    for i in 1..char_length(cleaned) loop
        ch := substr(cleaned, i, 1);
        if ch in ('0', 'O') then
            out := out || 'Q';
        elsif ch in ('1', 'I', 'L') then
            out := out || 'J';
        elsif ch ~ '^[A-Z2-9]$' then
            out := out || ch;
        end if;
    end loop;
    if out !~ '^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$' then
        return null;
    end if;
    return out;
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
    if (select auth.uid()) is null then
        return null;
    end if;
    cleaned := public._companion_short_code_clean(p_code);
    if cleaned is null then
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
    uid uuid;
begin
    uid := auth.uid();
    if uid is null then
        return null;
    end if;
    cleaned := public._companion_short_code_clean(p_code);
    if cleaned is null then
        return null;
    end if;

    update public.cast_handoffs h
    set claimed_at = now(),
        claimed_by = uid,
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
revoke all on function public.get_cast_handoff_by_code(text) from anon;
grant execute on function public.get_cast_handoff_by_code(text) to authenticated;

revoke all on function public.claim_cast_handoff(text) from public;
revoke all on function public.claim_cast_handoff(text) from anon;
grant execute on function public.claim_cast_handoff(text) to authenticated;

-- CC-004 / CC-052: Session peek auth + strip share_token from peek JSON
create or replace function public._session_handoff_peek_json(p public.session_handoffs)
returns json
language plpgsql
stable
security definer
set search_path = public
as $$
begin
    return json_build_object(
        'id', p.id,
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
    if (select auth.uid()) is null then
        return null;
    end if;
    cleaned := public._companion_short_code_clean(p_code);
    if cleaned is null then
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
    return public._session_handoff_peek_json(row);
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
    cleaned := public._companion_short_code_clean(p_code);
    if cleaned is null then
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
    -- Claim response may still include share_token for host/debug consumers.
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
    if (select auth.uid()) is null then
        return null;
    end if;
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
    -- Host poll path: authenticated owner already has the token locally.
    return public._session_handoff_peek_json(row);
end;
$$;

revoke all on function public.get_session_handoff_by_code(text) from public;
revoke all on function public.get_session_handoff_by_code(text) from anon;
grant execute on function public.get_session_handoff_by_code(text) to authenticated;

revoke all on function public.claim_session_handoff(text) from public;
grant execute on function public.claim_session_handoff(text) to authenticated;

revoke all on function public.get_session_handoff(text) from public;
revoke all on function public.get_session_handoff(text) from anon;
grant execute on function public.get_session_handoff(text) to authenticated;
