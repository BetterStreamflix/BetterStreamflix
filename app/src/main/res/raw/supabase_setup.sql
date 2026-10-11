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
begin
    if p_token is null or length(trim(p_token)) < 8 then
        return null;
    end if;

    select json_build_object(
        'id', w.id,
        'title', w.title,
        'share_token', w.share_token,
        'updated_at', w.updated_at,
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
    where w.share_token = trim(p_token);

    return result;
end;
$$;

revoke all on function public.get_shared_watchlist(text) from public;
grant execute on function public.get_shared_watchlist(text) to anon, authenticated;
