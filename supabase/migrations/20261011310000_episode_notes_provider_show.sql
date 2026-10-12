-- Optional provider/show identity for episode notes (backward compatible).
-- Composite PK prevents cross-provider / cross-show episode_id collisions.

alter table public.user_episode_notes
    add column if not exists provider_name text not null default '';

alter table public.user_episode_notes
    add column if not exists show_id text not null default '';

alter table public.user_episode_notes
    drop constraint if exists user_episode_notes_pkey;

alter table public.user_episode_notes
    add primary key (user_id, provider_name, show_id, episode_id);
