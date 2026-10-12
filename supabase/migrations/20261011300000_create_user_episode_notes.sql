-- Episode notes cloud sync (LWW on client_updated_at_millis).
-- Not added to supabase_realtime — clients poll/pull only (see CloudNotesLooksSync / SC-002).

create table if not exists public.user_episode_notes (
    user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
    episode_id text not null,
    text text not null default '',
    client_updated_at_millis bigint not null,
    server_updated_at timestamptz not null default now(),
    primary key (user_id, episode_id),
    constraint user_episode_notes_episode_id_chk check (char_length(episode_id) between 1 and 256),
    constraint user_episode_notes_text_chk check (char_length(text) <= 160)
);

create index if not exists user_episode_notes_user_updated_idx
    on public.user_episode_notes (user_id, server_updated_at desc);

alter table public.user_episode_notes enable row level security;

drop policy if exists "Users read their own episode notes" on public.user_episode_notes;
create policy "Users read their own episode notes"
    on public.user_episode_notes
    for select
    to authenticated
    using ((select auth.uid()) = user_id);

drop policy if exists "Users insert their own episode notes" on public.user_episode_notes;
create policy "Users insert their own episode notes"
    on public.user_episode_notes
    for insert
    to authenticated
    with check ((select auth.uid()) = user_id);

drop policy if exists "Users update their own episode notes" on public.user_episode_notes;
create policy "Users update their own episode notes"
    on public.user_episode_notes
    for update
    to authenticated
    using ((select auth.uid()) = user_id)
    with check ((select auth.uid()) = user_id);

drop policy if exists "Users delete their own episode notes" on public.user_episode_notes;
create policy "Users delete their own episode notes"
    on public.user_episode_notes
    for delete
    to authenticated
    using ((select auth.uid()) = user_id);

grant select, insert, update, delete on public.user_episode_notes to authenticated;

create or replace function public.set_user_episode_notes_server_updated_at()
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

drop trigger if exists set_user_episode_notes_server_updated_at on public.user_episode_notes;
create trigger set_user_episode_notes_server_updated_at
before update on public.user_episode_notes
for each row execute function public.set_user_episode_notes_server_updated_at();
