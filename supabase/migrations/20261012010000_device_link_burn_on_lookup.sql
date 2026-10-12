-- SC-004: burn device-link codes on first successful email lookup so the
-- host email cannot be re-fetched by other devices within the TTL.
-- Password retries on the claimer device use a local client cache of the
-- email returned by the first lookup (see DeviceLinkLookupCache).

alter table public.device_link_codes
    add column if not exists looked_up_at timestamptz;

comment on column public.device_link_codes.looked_up_at is
    'Set atomically on first get_device_link_by_code; further lookups return null (SC-004).';

create or replace function public.get_device_link_by_code(p_code text)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    cleaned text;
    row_data public.device_link_codes;
begin
    if p_code is null then
        return null;
    end if;
    cleaned := upper(trim(p_code));
    cleaned := replace(replace(replace(cleaned, '-', ''), '0', 'O'), '1', 'I');
    cleaned := replace(cleaned, 'L', 'I');
    if cleaned !~ '^[A-Z2-9]{6}$' then
        return null;
    end if;

    -- Atomic burn-on-lookup: only the first reveal returns email.
    update public.device_link_codes c
    set looked_up_at = now()
    where c.short_code = cleaned
      and c.expires_at >= now()
      and c.claimed_at is null
      and c.looked_up_at is null
    returning * into row_data;

    if row_data.id is null then
        return null;
    end if;
    return json_build_object(
        'short_code', row_data.short_code,
        'email', row_data.host_email,
        'expires_at', row_data.expires_at,
        'looked_up_at', row_data.looked_up_at
    );
end;
$$;

comment on function public.get_device_link_by_code(text) is
    'Returns host email once; sets looked_up_at so the code cannot be re-probed (SC-004).';

revoke all on function public.get_device_link_by_code(text) from public;
grant execute on function public.get_device_link_by_code(text) to anon, authenticated;
