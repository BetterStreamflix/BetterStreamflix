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
