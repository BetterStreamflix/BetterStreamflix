-- Watch Together Pro: join code, TV-screen/phone-remote sidekick, host controls, sync health.
-- Extends watch_parties; still no stream URLs / media relay.

alter table public.watch_parties
    add column if not exists short_code text,
    add column if not exists display_mode text not null default 'sync_all',
    add column if not exists sidekick_seek_ms bigint,
    add column if not exists sidekick_seek_seq bigint not null default 0,
    add column if not exists sidekick_playing boolean,
    add column if not exists sidekick_play_seq bigint not null default 0,
    add column if not exists sync_revision bigint not null default 0,
    add column if not exists host_controls_locked boolean not null default false;

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'watch_parties_short_code_chk'
    ) then
        alter table public.watch_parties
            add constraint watch_parties_short_code_chk
            check (short_code is null or short_code ~ '^[A-Z2-9]{6}$');
    end if;
    if not exists (
        select 1 from pg_constraint
        where conname = 'watch_parties_display_mode_chk'
    ) then
        alter table public.watch_parties
            add constraint watch_parties_display_mode_chk
            check (display_mode in ('sync_all', 'tv_screen'));
    end if;
end $$;

create unique index if not exists watch_parties_short_code_uidx
    on public.watch_parties (short_code)
    where short_code is not null and ended = false;

comment on column public.watch_parties.short_code is
    '6-char join code (A–Z2–9) for lobby / phone remote pairing.';
comment on column public.watch_parties.display_mode is
    'sync_all = every device plays locally; tv_screen = TV is screen, phone is remote.';
comment on column public.watch_parties.sidekick_seek_ms is
    'Phone scrubber target; TV host applies when sidekick_seek_seq advances.';
comment on column public.watch_parties.sync_revision is
    'Bumped on force resync so guests re-seek even within drift threshold.';
comment on column public.watch_parties.host_controls_locked is
    'When true, guests should follow host playhead only (no local scrub leading).';

create table if not exists public.watch_party_sync_reports (
    id uuid primary key default gen_random_uuid(),
    party_id uuid not null references public.watch_parties(id) on delete cascade,
    member_key text not null,
    drift_ms bigint not null default 0,
    reported_at timestamptz not null default now(),
    constraint watch_party_sync_reports_key_chk
        check (member_key ~ '^[A-Za-z0-9_-]{8,64}$'),
    unique (party_id, member_key)
);

create index if not exists watch_party_sync_reports_party_idx
    on public.watch_party_sync_reports (party_id);

alter table public.watch_party_sync_reports enable row level security;

drop policy if exists "Hosts read sync reports" on public.watch_party_sync_reports;
create policy "Hosts read sync reports"
    on public.watch_party_sync_reports
    for select
    to authenticated
    using (
        exists (
            select 1 from public.watch_parties p
            where p.id = party_id and p.host_user_id = (select auth.uid())
        )
    );

-- Shared JSON snapshot for Pro fields.
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
        'playing', p.playing,
        'in_lobby', p.in_lobby,
        'ended', p.ended,
        'expires_at', p.expires_at,
        'updated_at', p.updated_at,
        'display_mode', p.display_mode,
        'sidekick_seek_ms', p.sidekick_seek_ms,
        'sidekick_seek_seq', p.sidekick_seek_seq,
        'sidekick_playing', p.sidekick_playing,
        'sidekick_play_seq', p.sidekick_play_seq,
        'sync_revision', p.sync_revision,
        'host_controls_locked', p.host_controls_locked
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

create or replace function public.get_watch_party_by_code(p_code text)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    cleaned text;
    token text;
begin
    if p_code is null then
        return null;
    end if;
    cleaned := upper(trim(p_code));
    cleaned := replace(replace(replace(cleaned, '0', 'O'), '1', 'I'), 'L', 'I');
    if cleaned !~ '^[A-Z2-9]{6}$' then
        return null;
    end if;
    select p.share_token into token
    from public.watch_parties p
    where p.short_code = cleaned
      and p.expires_at >= now()
      and p.ended = false
    limit 1;
    if token is null then
        return null;
    end if;
    return public.get_watch_party(token);
end;
$$;

revoke all on function public.get_watch_party_by_code(text) from public;
grant execute on function public.get_watch_party_by_code(text) to anon, authenticated;

create or replace function public._watch_party_active(p_token text)
returns public.watch_parties
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    cleaned text;
    row public.watch_parties;
begin
    if p_token is null then
        return null;
    end if;
    cleaned := trim(p_token);
    if cleaned !~ '^[A-Za-z0-9_-]{8,128}$' then
        return null;
    end if;
    select * into row
    from public.watch_parties p
    where p.share_token = cleaned
      and p.expires_at >= now()
      and p.ended = false;
    return row;
end;
$$;

create or replace function public.watch_party_sidekick_seek(p_token text, p_position_ms bigint)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    party public.watch_parties;
    target bigint;
begin
    party := public._watch_party_active(p_token);
    if party.id is null or p_position_ms is null then
        return null;
    end if;
    target := greatest(0, p_position_ms);
    update public.watch_parties
    set position_ms = target,
        sidekick_seek_ms = target,
        sidekick_seek_seq = sidekick_seek_seq + 1,
        sync_revision = sync_revision + 1,
        updated_at = now()
    where id = party.id;
    return public.get_watch_party(party.share_token);
end;
$$;

create or replace function public.watch_party_sidekick_play(p_token text, p_playing boolean)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    party public.watch_parties;
begin
    party := public._watch_party_active(p_token);
    if party.id is null or p_playing is null then
        return null;
    end if;
    update public.watch_parties
    set playing = p_playing,
        sidekick_playing = p_playing,
        sidekick_play_seq = sidekick_play_seq + 1,
        sync_revision = sync_revision + 1,
        updated_at = now()
    where id = party.id;
    return public.get_watch_party(party.share_token);
end;
$$;

create or replace function public.watch_party_force_resync(p_token text, p_position_ms bigint)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    party public.watch_parties;
    target bigint;
begin
    party := public._watch_party_active(p_token);
    if party.id is null then
        return null;
    end if;
    if party.host_user_id is distinct from (select auth.uid()) then
        return null;
    end if;
    target := greatest(0, coalesce(p_position_ms, party.position_ms));
    update public.watch_parties
    set position_ms = target,
        sync_revision = sync_revision + 1,
        updated_at = now()
    where id = party.id;
    return public.get_watch_party(party.share_token);
end;
$$;

create or replace function public.set_watch_party_display_mode(p_token text, p_mode text)
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
    cleaned := lower(trim(coalesce(p_mode, '')));
    if cleaned not in ('sync_all', 'tv_screen') then
        return null;
    end if;
    update public.watch_parties
    set display_mode = cleaned,
        updated_at = now()
    where id = party.id;
    return public.get_watch_party(party.share_token);
end;
$$;

create or replace function public.set_watch_party_controls_locked(p_token text, p_locked boolean)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    party public.watch_parties;
begin
    party := public._watch_party_active(p_token);
    if party.id is null or p_locked is null then
        return null;
    end if;
    if party.host_user_id is distinct from (select auth.uid()) then
        return null;
    end if;
    update public.watch_parties
    set host_controls_locked = p_locked,
        sync_revision = sync_revision + 1,
        updated_at = now()
    where id = party.id;
    return public.get_watch_party(party.share_token);
end;
$$;

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
    return public.get_watch_party_lobby(party.share_token);
end;
$$;

create or replace function public.report_watch_party_drift(
    p_token text,
    p_member_key text,
    p_drift_ms bigint
)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    party public.watch_parties;
    cleaned text;
    drift bigint;
begin
    party := public._watch_party_active(p_token);
    if party.id is null then
        return null;
    end if;
    cleaned := trim(coalesce(p_member_key, ''));
    if cleaned !~ '^[A-Za-z0-9_-]{8,64}$' then
        return null;
    end if;
    drift := greatest(0, coalesce(p_drift_ms, 0));
    insert into public.watch_party_sync_reports (party_id, member_key, drift_ms, reported_at)
    values (party.id, cleaned, drift, now())
    on conflict (party_id, member_key) do update
        set drift_ms = excluded.drift_ms,
            reported_at = excluded.reported_at;
    return json_build_object('ok', true, 'drift_ms', drift);
end;
$$;

create or replace function public.list_watch_party_sync_health(p_token text)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    party public.watch_parties;
    result json;
    max_drift bigint;
    stale_count integer;
begin
    party := public._watch_party_active(p_token);
    if party.id is null then
        return null;
    end if;
    select coalesce(max(r.drift_ms), 0),
           coalesce(count(*) filter (where r.reported_at < now() - interval '20 seconds'), 0)
    into max_drift, stale_count
    from public.watch_party_sync_reports r
    where r.party_id = party.id
      and r.reported_at >= now() - interval '2 minutes';

    select json_build_object(
        'sync_revision', party.sync_revision,
        'updated_at', party.updated_at,
        'display_mode', party.display_mode,
        'host_controls_locked', party.host_controls_locked,
        'max_drift_ms', max_drift,
        'stale_reports', stale_count,
        'member_count', (
            select count(*)::int
            from public.watch_party_members m
            where m.party_id = party.id
              and m.last_seen_at >= now() - interval '5 minutes'
        ),
        'reports', coalesce((
            select json_agg(json_build_object(
                'member_key', r.member_key,
                'drift_ms', r.drift_ms,
                'reported_at', r.reported_at
            ) order by r.drift_ms desc)
            from public.watch_party_sync_reports r
            where r.party_id = party.id
              and r.reported_at >= now() - interval '2 minutes'
        ), '[]'::json)
    )
    into result;
    return result;
end;
$$;

revoke all on function public.watch_party_sidekick_seek(text, bigint) from public;
grant execute on function public.watch_party_sidekick_seek(text, bigint) to anon, authenticated;

revoke all on function public.watch_party_sidekick_play(text, boolean) from public;
grant execute on function public.watch_party_sidekick_play(text, boolean) to anon, authenticated;

revoke all on function public.watch_party_force_resync(text, bigint) from public;
grant execute on function public.watch_party_force_resync(text, bigint) to authenticated;

revoke all on function public.set_watch_party_display_mode(text, text) from public;
grant execute on function public.set_watch_party_display_mode(text, text) to authenticated;

revoke all on function public.set_watch_party_controls_locked(text, boolean) from public;
grant execute on function public.set_watch_party_controls_locked(text, boolean) to authenticated;

revoke all on function public.kick_watch_party_member(text, text) from public;
grant execute on function public.kick_watch_party_member(text, text) to authenticated;

revoke all on function public.report_watch_party_drift(text, text, bigint) from public;
grant execute on function public.report_watch_party_drift(text, text, bigint) to anon, authenticated;

revoke all on function public.list_watch_party_sync_health(text) from public;
grant execute on function public.list_watch_party_sync_health(text) to anon, authenticated;
