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
