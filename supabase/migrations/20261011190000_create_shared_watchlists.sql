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
