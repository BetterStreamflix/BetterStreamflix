# Supabase cloud sync

For the complete user-facing setup guide, see [`supabase_installation.md`](../supabase_installation.md) or open **Settings → Account & sync → Instructions** in Streamflix.

Create a Supabase project, apply the migrations in `supabase/migrations/`, and use the project URL and **publishable** key in Streamflix under **Settings → Account & sync**.

Do not use a `service_role` or other secret key in the app. The migrations enable RLS and limit each authenticated user to their own `user_media_state` rows. Shareable watchlists (`shared_watchlists` / `shared_watchlist_items`) are owner-writable; public read is only through the `get_shared_watchlist(token)` RPC (token regex `[A-Za-z0-9_-]{8,128}`; rows with `expires_at < now()` are hidden).

After configuring the connection, users can create/sign into their own Supabase Auth account. Account and sync actions remain disabled until both connection fields are valid. Favorites sync across devices when signed in; **Share list** publishes a deep link (`betterstreamflix://list/…`) with a ~30-day TTL. Republish rotates the token; owners can revoke (delete) the shared row from Favorites.
