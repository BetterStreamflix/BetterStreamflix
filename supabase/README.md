# Supabase cloud sync

For the complete user-facing setup guide, see [`supabase_installation.md`](../supabase_installation.md) or open **Settings → Account & sync → Instructions** in Streamflix.

Create a Supabase project, apply the migrations in `supabase/migrations/`, and use the project URL and **publishable** key in Streamflix under **Settings → Account & sync**.

Do not use a `service_role` or other secret key in the app. The migrations enable RLS and limit each authenticated user to their own `user_media_state` rows. Shareable watchlists (`shared_watchlists` / `shared_watchlist_items`) are owner-writable; public read is only through the `get_shared_watchlist(token)` RPC (token regex `[A-Za-z0-9_-]{8,128}`; rows with `expires_at < now()` are hidden).

**Watch-Party light** (`watch_parties`): host-writable rows that sync **playback position + chosen server identity only** (no stream URLs or cookies). Guests read via `get_watch_party(token)` (anon + authenticated). Tokens use the same charset/length rules; parties expire after ~12 hours or when the host ends/`ended=true`. Apply `supabase/migrations/20261011210000_create_watch_parties.sql` (also bundled in Copy SQL).

After configuring the connection, users can create/sign into their own Supabase Auth account. Account and sync actions remain disabled until both connection fields are valid. Favorites sync across devices when signed in; **Share list** publishes a deep link (`betterstreamflix://list/…`) with a ~30-day TTL. Republish rotates the token; owners can revoke (delete) the shared row from Favorites. Watch Party links use `https://betterstreamflix.app/party/<token>`.
