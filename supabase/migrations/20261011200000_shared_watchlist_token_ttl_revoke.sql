-- Capability-URL hardening: TTL on shared_watchlists + stricter token validation in RPC.
-- Owners revoke by deleting their row (existing RLS DELETE). Republish rotates share_token client-side.

alter table public.shared_watchlists
    add column if not exists expires_at timestamptz;

comment on column public.shared_watchlists.expires_at is
    'Optional expiry for the share capability URL; null means no expiry (legacy rows).';

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
