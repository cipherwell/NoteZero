#!/usr/bin/env bash
# Verify whether NoteZero release artifacts are byte-for-byte reproducible.
#
# Builds the same commit twice from two *different* directories (so absolute-path leakage is
# exercised too), with a fixed SOURCE_DATE_EPOCH, then compares the selected artifact
# byte-for-byte and, if it differs, lists the zip entries that differ with their SHA-256.
#
# Usage:
#   scripts/verify-reproducible.sh [workdir]                        # signed release APK
#   scripts/verify-reproducible.sh --unsigned [workdir]             # unsigned release APK (CI)
#   scripts/verify-reproducible.sh --artifact aab [workdir]         # release AAB
#
# Requirements: JDK 17, Android SDK (local.properties). Signed mode additionally requires
# the release signing env:  set -a; . ~/.cipherwell-release/notezero.env; set +a
#
# Exit codes: 0 = byte-identical (reproducible), 1 = differences found (report printed),
#             2 = setup/build error.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
export JAVA_HOME
# Fixed reference instant: 2026-11-15T12:00:00Z. Same for both builds and documented in
# docs/REPRODUCIBLE-BUILDS.md so third parties can re-run the check identically.
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-1763054400}"

ARTIFACT="apk"
UNSIGNED=0
WORK=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --unsigned) UNSIGNED=1 ;;
    --artifact) shift; ARTIFACT="${1:-apk}" ;;
    --artifact=*) ARTIFACT="${1#--artifact=}" ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) WORK="$1" ;;
  esac
  shift
done
WORK="${WORK:-/tmp/notezero-repro-$ARTIFACT$([[ $UNSIGNED -eq 1 ]] && echo '-unsigned')}"

fail() { echo "ERROR: $*" >&2; exit 2; }

if [[ "$ARTIFACT" != "apk" && "$ARTIFACT" != "aab" ]]; then
  fail "--artifact must be 'apk' or 'aab' (got: $ARTIFACT)"
fi
if [[ $UNSIGNED -eq 0 && -z "${NOTEZERO_KEYSTORE_PATH:-}" ]]; then
  fail "signed mode requires the release signing env (source ~/.cipherwell-release/notezero.env); use --unsigned for the CI check"
fi
[[ -x "$JAVA_HOME/bin/java" ]] || fail "JDK 17 not found at $JAVA_HOME"

echo "== NoteZero reproducible-release check =="
echo "project  : $ROOT"
echo "workdir  : $WORK"
echo "epoch    : $SOURCE_DATE_EPOCH"
echo "artifact : $ARTIFACT$([[ $UNSIGNED -eq 1 ]] && echo ' (unsigned)')"
echo

rm -rf "$WORK"
mkdir -p "$WORK"

copy_project() {
  local dest="$1"
  mkdir -p "$dest"
  tar -C "$ROOT" \
    --exclude=./build --exclude=./app/build --exclude=./.gradle --exclude=./.kotlin \
    --exclude=./.git --exclude=./releases --exclude=./docs/screenshots \
    -cf - . | tar -C "$dest" -xf -
}

build_once() {
  local dir="$1" label="$2"
  echo "-- building $label in $dir"
  (
    cd "$dir" || exit 1
    if [[ $UNSIGNED -eq 1 ]]; then
      unset NOTEZERO_KEYSTORE_PATH NOTEZERO_STORE_PASSWORD NOTEZERO_KEY_PASSWORD NOTEZERO_KEY_ALIAS
    fi
    ./gradlew --no-daemon --no-build-cache --console=plain -q \
      :app:assembleRelease :app:bundleRelease
  ) || fail "build $label failed"

  local artifact
  case "$ARTIFACT" in
    apk)
      if [[ $UNSIGNED -eq 1 ]]; then
        artifact="$dir/app/build/outputs/apk/release/app-release-unsigned.apk"
      else
        artifact="$dir/app/build/outputs/apk/release/app-release.apk"
      fi
      ;;
    aab) artifact="$dir/app/build/outputs/bundle/release/app-release.aab" ;;
  esac
  [[ -f "$artifact" ]] || fail "build $label produced no artifact at $artifact"
  cp "$artifact" "$WORK/$label.$ARTIFACT"
  sha256sum "$WORK/$label.$ARTIFACT" | sed "s|$WORK/||"
}

copy_project "$WORK/a"
copy_project "$WORK/b"
# Two distinct absolute paths on purpose: path leakage shows up as a failure.
build_once "$WORK/a" "build-a"
build_once "$WORK/b" "build-b"
echo

A="$WORK/build-a.$ARTIFACT"
B="$WORK/build-b.$ARTIFACT"
HA="$(sha256sum "$A" | cut -d' ' -f1)"
HB="$(sha256sum "$B" | cut -d' ' -f1)"

if [[ "$HA" == "$HB" ]]; then
  echo "RESULT: REPRODUCIBLE — both $ARTIFACT builds are byte-identical (sha256 $HA)."
  exit 0
fi

echo "RESULT: NOT byte-identical."
echo "  a: $HA"
echo "  b: $HB"
echo
echo "== differing archive entries =="
python3 - "$A" "$B" <<'PY'
import hashlib, sys, zipfile

def entries(path):
    out = {}
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            data = z.read(info.filename)
            out[info.filename] = (
                hashlib.sha256(data).hexdigest(),
                info.CRC,
                info.date_time,
                len(data),
            )
    return out

a = entries(sys.argv[1])
b = entries(sys.argv[2])
only_a = sorted(set(a) - set(b))
only_b = sorted(set(b) - set(a))
diff = sorted(k for k in set(a) & set(b) if a[k][0] != b[k][0])

print(f"entries: a={len(a)} b={len(b)} only-in-a={len(only_a)} only-in-b={len(only_b)} content-diff={len(diff)}")
for name in only_a:
    print(f"  only in a: {name}")
for name in only_b:
    print(f"  only in b: {name}")
for name in diff:
    sa, ca, ta, la = a[name]
    sb, cb, tb, lb = b[name]
    print(f"  DIFF {name}")
    print(f"       a sha256={sa} crc={ca:08x} time={ta} size={la}")
    print(f"       b sha256={sb} crc={cb:08x} time={tb} size={lb}")
if not only_a and not only_b and not diff:
    print("  all archive entries are identical — difference is in the signing block / archive structure")
PY
echo
echo "Record this output in docs/REPRODUCIBLE-BUILDS.md (state the exact causes found)."
exit 1
