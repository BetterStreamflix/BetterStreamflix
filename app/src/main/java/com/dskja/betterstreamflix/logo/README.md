# TMDb title-logo pipeline

Layered ownership for BetterStreamflix title logos (Featured, Detail hero/header, TV,
Player, Continue Watching, Search grids).

## Layers

1. **Policy** — `TmdbLogoPicker` (ranking, trust/upgrade, title match, cache keys)
2. **Cache** — `TmdbLogoCache` (memory LRU + disk prefs, hit/miss TTL, fail blacklist, single-flight)
3. **Fetch** — `TmdbLogoFetch` (injectable TMDb seam for unit tests)
4. **Resolve** — `TmdbUtils.resolveTitleLogo` + `TmdbLogoBinder.resolve`
5. **Load** — `TmdbLogoGlide` (size ladder, override, decode guards, Wi‑Fi prefetch)
6. **Bind** — `TitleLogoSurface` (shared Mobile/TV/Featured/Player path) + `TmdbLogoBinder`
7. **Persist** — `LogoPersist` (soft Room write when a row already exists)
8. **Slot** — `TitleLogoSlot` (fixed-height overlay state: title ↔ loading ↔ logo)
9. **Telemetry** — `TmdbLogoTelemetry` (Settings → Title logo stats)

## Surfaces

| Surface | Path | Persist | Network resolve |
|---------|------|---------|-----------------|
| Featured | `TitleLogoSurface` via `FeaturedSwiperChrome` | yes | upgrade + alternate on decode fail |
| Detail hero/header | `TitleLogoSurface` via `DetailHeaderController` | yes | same |
| TV detail | `TitleLogoSurface` in Movie/TvShow ViewHolders | yes | same |
| Player | `TitleLogoSurface.resolveAndBind` | no | once per title |
| Continue Watching | `bindCachedOnly` | no | no (cached URL only) |
| Search / home grids | `bindCachedOnly` | no | no (cached URL only) |

## Cache semantics

- **Memory LRU** (256) is the hot path; **SharedPreferences** disk survives process death.
- **Glide** has its own disk cache for decoded bitmaps — our cache stores *URL choice*, not pixels.
- Miss ≠ network error; only empty successful `logos[]` writes a miss sentinel.
- When logos are **disabled**, `get*ById` does **not** write logo-cache misses (avoids 6h poison).
- Decode-fail with only blacklisted candidates does **not** write a miss — blacklist TTL (30m) applies.
- `TmdbCache` hits filter blacklisted logo URLs before returning them to surfaces.
- Trusted Glide decode failures blacklist the **file identity** (all size tiers) and drop matching
  cache entries so the next resolve can pick an alternate candidate.
- Picker skips blacklisted file paths so a later fetch can choose an alternate candidate.
- Cache language key uses ISO-639-1 primary (`de`, `pt`) — same as TMDb `include_image_language`.
  `get*ById` falls back to the provider language when the caller's language arg is null.

## Sources

`LogoSource`: `PROVIDER` | `TMDB` | `USER` | `UNKNOWN` on `Movie`/`TvShow.logoSource`.
`logo` + `logoLanguage` persist in Room (v10).

## Prefs

- `ENABLE_TMDB_LOGOS` — master toggle (gates resolve, enrich upgrade, and `get*ById` logo fields)
- `TMDB_LOGO_QUALITY` — `original_first` (default) vs `w1280_first`
- `TMDB_LOGO_MISS_TTL_HOURS` / `TMDB_LOGO_HIT_TTL_HOURS`
- `TMDB_LOGO_TELEMETRY` — tap to refresh in-session counters
- Clear: Settings → Clear TMDb cache (also clears logo disk cache) or logo-only clear

## UI polish

- Fixed-height logo slots (FrameLayout) on Featured + Detail — no layout shift on reveal
- Contrast scrim (`bg_title_logo_contrast`) behind decoded logos
- Glide crossfade (~180ms) for reveal timing

## Comments language

Code comments in this package are English for consistency with the rest of the logo module.
