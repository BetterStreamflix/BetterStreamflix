-- Profile Looks / theme prefs (one row per Supabase user, LWW on client_updated_at_millis).
-- SC-018: not per local profile — one cloud account ↔ one look; multi-profile needs multi-account.

create table if not exists public.user_profile_prefs (
    user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
    look text not null default 'cinema_ink',
    client_updated_at_millis bigint not null,
    server_updated_at timestamptz not null default now(),
    primary key (user_id),
    constraint user_profile_prefs_look_chk check (char_length(look) between 1 and 64)
);

alter table public.user_profile_prefs enable row level security;

drop policy if exists "Users read their own profile prefs" on public.user_profile_prefs;
create policy "Users read their own profile prefs"
    on public.user_profile_prefs
    for select
    to authenticated
    using ((select auth.uid()) = user_id);

drop policy if exists "Users insert their own profile prefs" on public.user_profile_prefs;
create policy "Users insert their own profile prefs"
    on public.user_profile_prefs
    for insert
    to authenticated
    with check ((select auth.uid()) = user_id);

drop policy if exists "Users update their own profile prefs" on public.user_profile_prefs;
create policy "Users update their own profile prefs"
    on public.user_profile_prefs
    for update
    to authenticated
    using ((select auth.uid()) = user_id)
    with check ((select auth.uid()) = user_id);

drop policy if exists "Users delete their own profile prefs" on public.user_profile_prefs;
create policy "Users delete their own profile prefs"
    on public.user_profile_prefs
    for delete
    to authenticated
    using ((select auth.uid()) = user_id);

grant select, insert, update, delete on public.user_profile_prefs to authenticated;

create or replace function public.set_user_profile_prefs_server_updated_at()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
    if new.client_updated_at_millis < old.client_updated_at_millis then
        return null;
    end if;
    new.server_updated_at = now();
    return new;
end;
$$;

drop trigger if exists set_user_profile_prefs_server_updated_at on public.user_profile_prefs;
create trigger set_user_profile_prefs_server_updated_at
before update on public.user_profile_prefs
for each row execute function public.set_user_profile_prefs_server_updated_at();
