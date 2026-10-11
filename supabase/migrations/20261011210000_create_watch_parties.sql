-- Watch-Party light: sync playback position + chosen server via shareable token.
-- No stream URLs/cookies — each device resolves its own source locally.

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
    playing boolean not null default true,
    ended boolean not null default false,
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists watch_parties_host_idx
    on public.watch_parties (host_user_id);

create index if not exists watch_parties_token_idx
    on public.watch_parties (share_token);

comment on table public.watch_parties is
    'Watch-Party light sessions: position + server identity only (no media relay).';

alter table public.watch_parties enable row level security;

drop policy if exists "Hosts read their watch parties" on public.watch_parties;
create policy "Hosts read their watch parties"
    on public.watch_parties
    for select
    to authenticated
    using ((select auth.uid()) = host_user_id);

drop policy if exists "Hosts insert their watch parties" on public.watch_parties;
create policy "Hosts insert their watch parties"
    on public.watch_parties
    for insert
    to authenticated
    with check ((select auth.uid()) = host_user_id);

drop policy if exists "Hosts update their watch parties" on public.watch_parties;
create policy "Hosts update their watch parties"
    on public.watch_parties
    for update
    to authenticated
    using ((select auth.uid()) = host_user_id)
    with check ((select auth.uid()) = host_user_id);

drop policy if exists "Hosts delete their watch parties" on public.watch_parties;
create policy "Hosts delete their watch parties"
    on public.watch_parties
    for delete
    to authenticated
    using ((select auth.uid()) = host_user_id);

grant select, insert, update, delete on public.watch_parties to authenticated;

-- Guests (incl. anon) read via security-definer RPC only.
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
    -- Align with client WatchPartyLinks TOKEN_PATTERN: [A-Za-z0-9_-]{8,128}
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

-- Optional Realtime for signed-in hosts/guests watching the same row.
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
