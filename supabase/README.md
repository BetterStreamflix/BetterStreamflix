# Supabase cloud sync

For the complete user-facing setup guide, see [`supabase_installation.md`](../supabase_installation.md) or open **Settings → Account & sync → Instructions** in Streamflix.

Create a Supabase project, apply the migrations in `supabase/migrations/`, and use the project URL and **publishable** key in Streamflix under **Settings → Account & sync**.

Do not use a `service_role` or other secret key in the app. The migrations enable RLS and limit each authenticated user to their own `user_media_state` rows. Shareable watchlists (`shared_watchlists` / `shared_watchlist_items`) are owner-writable; public read is only through the `get_shared_watchlist(token)` RPC (token regex `[A-Za-z0-9_-]{8,128}`; rows with `expires_at < now()` are hidden).

**Watch Together Pro** (`watch_parties` + members/messages + sync reports): Watch-Party session product — lobby with **6-char join code / QR**, host controls (force resync, display mode, control lock, kick), **Sync-Health** drift reports, and **TV-screen / phone-remote** sidekick seek/play (no stream URLs). RPCs include `get_watch_party_by_code`, `watch_party_sidekick_seek`, `watch_party_force_resync`, `list_watch_party_sync_health`, …. Apply `20261011210000_create_watch_parties.sql`, `20261011220000_watch_party_v2_lobby_chat.sql`, and `20261011270000_watch_together_pro.sql` (also bundled in Copy SQL).

**Cast Room / TV Sidekick** (`cast_rooms` + `cast_room_items` + `cast_room_messages`): TV host opens a room with QR + 6-character code; phone joins as Sidekick — ordered queue plus scrubber seek, keyboard text for TV search, and party chat. Syncs **media identity + queue/play/seek/text/chat only** (no stream URLs). Controllers use token-gated security-definer RPCs (`get_cast_room`, `cast_room_seek`, `cast_room_sidekick_text`, `post_cast_room_message`, …). Apply `20261011230000_create_cast_rooms.sql` and `20261011250000_cast_room_sidekick.sql` (also bundled in Copy SQL). Links: `https://betterstreamflix.app/cast/<token>`.

**Cast Hand-off reverse** (`cast_handoffs`): TV offers a one-shot 6-character code (or QR) while playing; phone claims and continues at the same **position + preferred server** (no stream URLs). Atomic `claim_cast_handoff` + peek via `get_cast_handoff_by_code`. Codes expire after ~30 minutes or when revoked/claimed. Apply `20261011260000_create_cast_handoffs.sql` (also bundled in Copy SQL). Links: `https://betterstreamflix.app/casthand/<code>` (distinct from Session Handoff `/handoff`).

Per-profile caption / Subtitle Studio styling is stored locally only — it is **not** synced to Supabase (notes and look/theme sync separately).

**Realtime scope:** only `user_media_state` is in the `supabase_realtime` publication. Episode notes (`user_episode_notes`) and look/theme prefs (`user_profile_prefs`) are **poll/pull only** — clients sync them via WorkManager / Sync now / login merge, not postgres changes.

After configuring the connection, users can create/sign into their own Supabase Auth account. Account and sync actions remain disabled until both connection fields are valid. Favorites sync across devices when signed in; **Share list** publishes a deep link (`betterstreamflix://list/…`) with a ~30-day TTL. Republish rotates the token; owners can revoke (delete) the shared row from Favorites. Watch Party links use `https://betterstreamflix.app/party/<token>`. Cast Room links use `https://betterstreamflix.app/cast/<token>`.
