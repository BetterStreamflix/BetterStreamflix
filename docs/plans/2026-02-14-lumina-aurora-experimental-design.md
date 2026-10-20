# Lumina Aurora — Experimental Design Shell (Mobile + TV)

Datum: 2026-02-14 · Status: Implementiert (Phase 1–2)

## Ziel

Ein komplett neues, hochwertiges, experimentelles Design — in den Settings
ein- und ausschaltbar, auf Mobile **und** TV, ohne den klassischen
Cinema-Ink-Look anzufassen.

## Ästhetik: „Lumina Aurora" / Neo-Glass

- **Basis:** Deep-Space-Dunkel (`lumina_space #0A0D14`) statt neutralem Schwarz
- **Signatur:** Aurora-Washes (Teal `#2BB8A6` → Violet `#7C5CFF`) als
  atmosphärische Gradients, nicht als UI-Farbe
- **Material:** Glas-Panels auf Mobile (`bg_exp_glass_*`), Reduced-Glass-Flat-
  Fallback für schwächere Geräte
- **TV:** Akzent-Fokusring (`tvFocusStrokeColor` pro Accent), 10-foot-
  Lesbarkeit, Pure-Black-Variante für OLED
- **Typo:** bestehende Manrope-/M3-Tokens des Lumina-Systems
- **Accents:** Crimson (Default), Ember, Aurora, Slate — je mit normaler +
  Pure-Black-Themevariante

## Architektur

Reaktivierung + Erweiterung des vorhandenen Lumina-Systems statt Parallelbau:

| Komponente | Rolle |
|---|---|
| `ExperimentalMobileDesign` | Zentrales Gate: `isAvailable()`, `enabled()` (Kids-geclamped), `themeRes()` (Mobile), **`tvThemeRes()` + `palette()` (neu, TV)** |
| `ThemeManager` | Bleibt für klassische Looks zuständig; unverändert |
| `MainMobileActivity` | `setTheme(themeRes())` wenn enabled (bereits verdrahtet) |
| `MainTvActivity` | **neu:** `setTheme(tvThemeRes())` + `navPalette()`-Helper |
| `settings_mobile.xml` | Master-Switch + `screen_lumina_options` (6 Optionen) |
| `settings_tv.xml` | Master-Switch + `screen_lumina_options` (TV-relevante 3) |
| `themes.xml` | 8 neue `AppTheme.Tv.Lumina*` Styles (4 Accents × 2 Surfaces) |
| 7 Drawables | Aurora-Gradients, Swiper-Scrims, Splash (normal + pure black) |

## Gates & Safety

- `enabled()` = `experimentalNewAppDesign && !ProfileManager.isKidsActive()`
  → Kids behalten KIDS_BRIGHT
- `enforceAvailabilityGate()` resettet nur noch die tote React-Shell-Pref
- High-Contrast-Overlay hat Vorrang (Palette + Stroke-Attr)
- `useReactShell()` bleibt `false` — React/Vite-Shell nicht reaktiviert
- Toggle → Activity-Restart (Mobile → `MainMobileActivity`, TV → `MainTvActivity`)
- Während Lumina aktiv: `SELECTED_THEME` deaktiviert (Look wird ignoriert)
- Switch-Checked-State spiegelt `enabled()` (Pref bleibt unter Kids erhalten)

## TV-Lumina (neu)

- `AppTheme.Tv.Lumina{,.Ember,.Aurora,.Slate}{,.PureBlack}` — 8 Styles, parent
  `AppTheme.Tv` (Leanback + M3-Attr-Brücke bleibt)
- Aurora-Home-Gradient: Layer-List, Deep-Space-Wash + Teal-Bloom links
- Fokus: `tvFocusStrokeColor` = Accent; HC-Overlay swappt weiter auf Gelb
- `palette()`: Nav/Header/Systembar in Lumina-Tönen, HC → Schwarz/Weiß

## Verifikation

- `:app:compileDebugKotlin` ✅
- `:app:assembleDebug` + `:app:testDebugUnitTest` — Build-Verifikation
- Manuell (auf Gerät ausstehend): Toggle on/off + Restart, Kids-Clamp,
  PureBlack/ReducedGlass, D-Pad-Fokus auf TV

## Nicht-Ziele

- React/Vite-Home-Shell (bleibt retired)
- Änderungen am klassischen Cinema-Ink-Look
- Light-Variante von Lumina (dark-only by design)
