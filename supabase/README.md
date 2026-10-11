# Supabase cloud sync

For the complete user-facing setup guide, see [`supabase_installation.md`](../supabase_installation.md) or open **Settings → Account & sync → Instructions** in Streamflix.

Create a Supabase project, apply the migrations in `supabase/migrations/`, and use the project URL and **publishable** key in Streamflix under **Settings → Account & sync**.

Do not use a `service_role` or other secret key in the app. The migrations enable RLS and limit each authenticated user to their own `user_media_state` rows. Shareable watchlists (`shared_watchlists` / `shared_watchlist_items`) are owner-writable; public read is only through the `get_shared_watchlist(token)` RPC (token regex `[A-Za-z0-9_-]{8,128}`; rows with `expires_at < now()` are hidden).

**Watch-Party 2.0** (`watch_parties` + `watch_party_members` + `watch_party_messages`): host-writable party rows with a lobby (`in_lobby`), host server pick, chat/emoji reacts, then sync of **playback position + chosen server identity only** (no stream URLs or cookies). Guests use security-definer RPCs (`get_watch_party`, `join_watch_party_lobby`, `post_watch_party_message`, …). Tokens use the same charset/length rules; parties expire after ~12 hours or when the host ends/`ended=true`. Apply `20261011210000_create_watch_parties.sql` and `20261011220000_watch_party_v2_lobby_chat.sql` (also bundled in Copy SQL).

**Cast Room** (`cast_rooms` + `cast_room_items`): TV host opens a room with QR + 6-character code; phone joins as remote companion and mutates an ordered queue (add / remove / reorder / play-at / skip / pause). Syncs **media identity + queue order + play commands only** (no stream URLs). Controllers use token-gated security-definer RPCs (`get_cast_room`, `cast_room_add_item`, …). Apply `20261011230000_create_cast_rooms.sql` (also bundled in Copy SQL). Links: `https://betterstreamflix.app/cast/<token>`.

After configuring the connection, users can create/sign into their own Supabase Auth account. Account and sync actions remain disabled until both connection fields are valid. Favorites sync across devices when signed in; **Share list** publishes a deep link (`betterstreamflix://list/…`) with a ~30-day TTL. Republish rotates the token; owners can revoke (delete) the shared row from Favorites. Watch Party links use `https://betterstreamflix.app/party/<token>`. Cast Room links use `https://betterstreamflix.app/cast/<token>`.
