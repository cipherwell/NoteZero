#!/usr/bin/env bash
# Generate the NoteZero release/upload key.
#
# NoteZero has its OWN upload key — never reuse VaultZero's, and never commit the
# key or its passwords. Key material + env file land in $HOME/.cipherwell-release/
# (mode 0700, files 0600), outside the repository.
#
# Usage: scripts/gen-release-keystore.sh
# Refuses to overwrite an existing key: a lost/rotated upload key cannot be
# recovered, and silently replacing it would break Play App Signing continuity.
set -euo pipefail

KEY_DIR="${NOTEZERO_KEY_DIR:-$HOME/.cipherwell-release}"
KEYSTORE="$KEY_DIR/notezero-upload.jks"
ENV_FILE="$KEY_DIR/notezero.env"
ALIAS="notezero-upload"
DNAME="CN=Cipherwell, O=Cipherwell, OU=NoteZero, C=US"

if [[ -e "$KEYSTORE" ]]; then
  echo "refusing to overwrite existing key: $KEYSTORE" >&2
  echo "delete it deliberately (and rotate the Play upload key) if you mean to." >&2
  exit 1
fi

command -v keytool >/dev/null 2>&1 || { echo "keytool not found (install a JDK 17+)" >&2; exit 1; }

umask 077
mkdir -p "$KEY_DIR"
chmod 700 "$KEY_DIR"

# PKCS12 keystores use ONE password for store and key (keypass is ignored), so a
# different key password would make release signing fail at package time.
STORE_PASS="$(openssl rand -hex 24)"
KEY_PASS="$STORE_PASS"

keytool -genkeypair \
  -keystore "$KEYSTORE" \
  -storetype PKCS12 \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -storepass "$STORE_PASS" \
  -keypass "$KEY_PASS" \
  -dname "$DNAME" >/dev/null

cat > "$ENV_FILE" <<EOF
# NoteZero release signing — DO NOT COMMIT. Source this file before a release build:
#   set -a; . $ENV_FILE; set +a; ./gradlew assembleRelease
NOTEZERO_KEYSTORE_PATH=$KEYSTORE
NOTEZERO_STORE_PASSWORD=$STORE_PASS
NOTEZERO_KEY_PASSWORD=$KEY_PASS
NOTEZERO_KEY_ALIAS=$ALIAS
EOF
chmod 600 "$ENV_FILE" "$KEYSTORE"

echo "NoteZero upload key created:"
echo "  keystore : $KEYSTORE"
echo "  env file : $ENV_FILE (0600)"
echo "Back both up offline. This key is independent of VaultZero's."
