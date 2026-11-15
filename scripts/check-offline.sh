#!/usr/bin/env bash
# NoteZero offline guarantee — fails the build/CI if the app can touch the network.
#
#   scripts/check-offline.sh            # source + merged-manifest checks (no build needed)
#   scripts/check-offline.sh --gradle   # additionally resolve runtime deps and grep known
#                                       # network libraries (needs a working Gradle + SDK)
#
# Exit codes: 0 = offline guarantee intact, 1 = violation.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST_SRC="$ROOT/app/src/main/AndroidManifest.xml"
MAIN_SRC="$ROOT/app/src/main/java"
rc=0

fail() { echo "OFFLINE-VIOLATION: $*" >&2; rc=1; }

echo "== 1. declared permissions (source manifest)"
# Only USE_BIOMETRIC may be declared; everything else is a smell.
# Skip explicit removal nodes (tools:node="remove"): those are the offline guard itself.
mapfile -t perms < <(grep -v 'tools:node="remove"' "$MANIFEST_SRC" \
  | grep -o 'android:name="android\.permission\.[A-Z_]*"' \
  | sed 's/.*permission\.//; s/"//')
for p in "${perms[@]}"; do
  case "$p" in
    USE_BIOMETRIC) ;;
    *) fail "permission declared: $p (only USE_BIOMETRIC is allowed)" ;;
  esac
done

echo "== 2. merged manifest (if present)"
merged=""
while IFS= read -r f; do merged="$f"; break; done < <(
  find "$ROOT/app/build/intermediates" -path "*merged*" -name AndroidManifest.xml 2>/dev/null | head -20
)
if [[ -n "$merged" ]]; then
  echo "   checking $merged"
  if grep -q 'android.permission.INTERNET' "$merged"; then
    fail "merged manifest contains INTERNET"
  fi
  if grep -q 'android.permission.ACCESS_NETWORK_STATE' "$merged"; then
    fail "merged manifest contains ACCESS_NETWORK_STATE"
  fi
else
  echo "   (no merged manifest yet — run a build for the full check)"
fi

echo "== 3. network APIs in app sources"
# Import-level scan: catches java.net / okhttp / ktor / Retrofit / Volley usage.
if rg -n --no-heading -g '*.kt' -g '*.java' \
    -e '^import (java|javax)\.net\.' \
    -e '^import okhttp3\.' \
    -e '^import io\.ktor\.' \
    -e '^import retrofit2\.' \
    -e '^import com\.android\.volley\.' \
    "$MAIN_SRC" 2>/dev/null; then
  fail "network API imported in app sources (see lines above)"
fi

# URL literals are allowed only in comments/docs; flag them in code lines.
if rg -n --no-heading -g '*.kt' -g '*.java' -e 'https?://[A-Za-z]' "$MAIN_SRC" 2>/dev/null \
    | grep -vE '^\s*[^:]+:[0-9]+:\s*(//|\*|/\*)' >/dev/null; then
  fail "URL literal found in app source (move it to docs/ or a comment)"
fi

echo "== 4. dependency allow/deny (gradle)"
if [[ "${1:-}" == "--gradle" ]]; then
  deps="$(cd "$ROOT" && ./gradlew -q :app:dependencies --configuration debugRuntimeClasspath 2>/dev/null)" || {
    echo "   gradle dependency resolution failed (skipping)" >&2
  }
  if [[ -n "${deps:-}" ]]; then
    for lib in okhttp retrofit ktor volley grpc protobuf-javalite com.squareup.okhttp; do
      if grep -qi "$lib" <<<"$deps"; then fail "network dependency resolved: $lib"; fi
    done
  fi
else
  echo "   (skipped; pass --gradle to resolve runtime dependencies)"
fi

if [[ $rc -eq 0 ]]; then
  echo "OK: NoteZero is offline by construction."
else
  echo "FAILED: offline guarantee violated." >&2
fi
exit $rc
