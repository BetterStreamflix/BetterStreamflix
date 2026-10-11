# experimental-ui (archive / prototype)

This folder is a **standalone React + Vite prototype** (Lumina explorations). It is
**not** part of the Android app build or CI.

- Android CI (`.github/workflows/pr-ci.yml`, `apk-build.yml`) does **not** install
  or build this package.
- Shipping Experimental UI / Lumina lives inside the Android app
  (`ExperimentalMobileDesign`, theme overlays) — not this Vite tree.
- Keep this directory for design experiments or archive reference; do not couple
  it to Gradle or GitHub Actions unless a maintainer explicitly decides otherwise.

## Local preview (optional)

```bash
cd experimental-ui
npm install
npm run dev
```

## React notes

The Vite template may mention React Compiler / Oxlint; those are local to this
prototype and unrelated to the Android Kotlin codebase.
