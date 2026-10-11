-- Device Link-Code: phone (signed in) shows a 6-char code; TV/other looks up host email
-- and completes with normal email/password sign-in (no Auth token minting).

create table if not exists public.device_link_codes (
    id uuid primary key default gen_random_uuid(),
    short_code text not null unique,
    host_user_id uuid not null references auth.users(id) on delete cascade,
    host_email text not null,
    claimed_at timestamptz,
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    constraint device_link_codes_code_chk check (short_code ~ '^[A-Z2-9]{6}$'),
    constraint device_link_codes_email_chk check (char_length(host_email) between 3 and 320)
);

create index if not exists device_link_codes_host_idx
    on public.device_link_codes (host_user_id);

create index if not exists device_link_codes_code_idx
    on public.device_link_codes (short_code);

comment on table public.device_link_codes is
    'Account Link-Code: returns host email for password sign-in on a second device.';

alter table public.device_link_codes enable row level security;

drop policy if exists "Hosts manage their device link codes" on public.device_link_codes;
create policy "Hosts manage their device link codes"
    on public.device_link_codes
    for all
    to authenticated
    using ((select auth.uid()) = host_user_id)
    with check ((select auth.uid()) = host_user_id);

grant select, insert, update, delete on public.device_link_codes to authenticated;

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
    select * into row_data
    from public.device_link_codes c
    where c.short_code = cleaned
      and c.expires_at >= now()
      and c.claimed_at is null;
    if row_data.id is null then
        return null;
    end if;
    return json_build_object(
        'short_code', row_data.short_code,
        'email', row_data.host_email,
        'expires_at', row_data.expires_at
    );
end;
$$;

create or replace function public.claim_device_link_code(p_code text)
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

    update public.device_link_codes c
    set claimed_at = now()
    where c.short_code = cleaned
      and c.expires_at >= now()
      and c.claimed_at is null
    returning * into row_data;

    if row_data.id is null then
        return null;
    end if;
    return json_build_object(
        'short_code', row_data.short_code,
        'email', row_data.host_email,
        'expires_at', row_data.expires_at,
        'claimed_at', row_data.claimed_at
    );
end;
$$;

revoke all on function public.get_device_link_by_code(text) from public;
grant execute on function public.get_device_link_by_code(text) to anon, authenticated;

revoke all on function public.claim_device_link_code(text) from public;
grant execute on function public.claim_device_link_code(text) to anon, authenticated;
