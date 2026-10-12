-- Leanback density travels with Looks prefs (same LWW clock).

alter table public.user_profile_prefs
    add column if not exists leanback_density text null;

alter table public.user_profile_prefs
    drop constraint if exists user_profile_prefs_leanback_density_chk;

alter table public.user_profile_prefs
    add constraint user_profile_prefs_leanback_density_chk
    check (leanback_density is null or leanback_density in ('compact', 'comfort'));
