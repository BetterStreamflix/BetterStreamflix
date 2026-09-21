#!/usr/bin/env bash
# Fast local debug APK build (no R8, no clean).
# Usage:
#   ./scripts/build-debug.sh              # universal
#   ./scripts/build-debug.sh mobile       # mobile-only manifest
#   ./scripts/build-debug.sh tv           # TV-only manifest
#   ./scripts/build-debug.sh install      # universal + adb install
#   ./scripts/build-debug.sh mobile install
set -euo pipefail
cd "$(dirname "$0")/.."

LAYOUT=""
DO_INSTALL=0
for arg in "$@"; do
  case "$arg" in
    mobile|tv) LAYOUT="$arg" ;;
    install) DO_INSTALL=1 ;;
    universal|"") ;;
    *)
      echo "Unknown arg: $arg" >&2
      echo "Usage: $0 [mobile|tv|universal] [install]" >&2
      exit 1
      ;;
  esac
done

touch local.properties
if [ -n "$LAYOUT" ]; then
  grep -v '^APP_LAYOUT=' local.properties > local.properties.tmp || true
  mv local.properties.tmp local.properties
  echo "APP_LAYOUT=$LAYOUT" >> local.properties
  echo "APP_LAYOUT=$LAYOUT"
else
  if grep -q '^APP_LAYOUT=' local.properties 2>/dev/null; then
    grep -v '^APP_LAYOUT=' local.properties > local.properties.tmp || true
    mv local.properties.tmp local.properties
    echo "APP_LAYOUT cleared (universal)"
  fi
fi

if [ ! -f app/src/main/cpp/native-lib.cpp ] && [ -f app/src/main/cpp/native-lib.cpp.template ]; then
  cp app/src/main/cpp/native-lib.cpp.template app/src/main/cpp/native-lib.cpp
  echo "Created native-lib.cpp from template (placeholders)"
fi

chmod +x ./gradlew
./gradlew :app:assembleDebug --stacktrace

APK=$(ls app/build/outputs/apk/debug/*.apk | head -n 1)
echo ""
echo "Debug APK: $APK"
ls -lh "$APK"

if [ "$DO_INSTALL" -eq 1 ]; then
  if ! command -v adb >/dev/null 2>&1; then
    echo "adb not found — install Android platform-tools or use Android Studio Run." >&2
    exit 1
  fi
  adb install -r "$APK"
fi
