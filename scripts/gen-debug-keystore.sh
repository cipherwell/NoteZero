#!/usr/bin/env bash
# Generate the local debug keystore used by app/build.gradle.kts (gitignored).
# A project-local debug key keeps debug installs stable across machines and
# avoids depending on ~/.android/debug.keystore regeneration.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE="$ROOT/debug.keystore"

if [[ -e "$KEYSTORE" ]]; then
  echo "debug keystore already present: $KEYSTORE"
  exit 0
fi

command -v keytool >/dev/null 2>&1 || { echo "keytool not found (install a JDK 17+)" >&2; exit 1; }

keytool -genkeypair \
  -keystore "$KEYSTORE" \
  -storetype PKCS12 \
  -alias androiddebugkey \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass android \
  -keypass android \
  -dname "CN=Android Debug, O=Android, C=US" >/dev/null

echo "debug keystore created: $KEYSTORE (gitignored)"
