#!/usr/bin/env bash
# Verify whether NoteZero release builds are byte-for-byte reproducible.
#
# Builds the same commit twice from two *different* directories (so absolute-path leakage is
# exercised too), with a fixed SOURCE_DATE_EPOCH, then compares the signed APKs byte-for-byte
# and, if they differ, lists the zip entries that differ with their SHA-256.
#
# Requirements: JDK 17, Android SDK (local.properties), release signing env sourced:
#   set -a; . ~/.cipherwell-release/notezero.env; set +a
#
# Usage: scripts/verify-reproducible.sh [workdir]     (default /tmp/notezero-repro)
# Exit 0 = byte-identical (reproducible), 1 = differences found (report printed).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${1:-/tmp/notezero-repro}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
export JAVA_HOME
# Fixed reference instant: 2026-11-15T12:00:00Z. Same for both builds and documented in
# docs/REPRODUCIBLE-BUILDS.md so third parties can re-run the check identically.
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-1763054400}"

fail() { echo "ERROR: $*" >&2; exit 2; }

[[ -n "${NOTEZERO_KEYSTORE_PATH:-}" ]] || fail "source ~/.cipherwell-release/notezero.env first (signed release required)"
[[ -x "$JAVA_HOME/bin/java" ]] || fail "JDK 17 not found at $JAVA_HOME"

echo "== NoteZero reproducible-release check =="
echo "project : $ROOT"
echo "workdir : $WORK"
echo "epoch   : $SOURCE_DATE_EPOCH"
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
  ( cd "$dir" && ./gradlew --no-daemon --no-build-cache --console=plain -q :app:assembleRelease ) \
    || fail "build $label failed"
  local apk="$dir/app/build/outputs/apk/release/app-release.apk"
  [[ -f "$apk" ]] || fail "build $label produced no signed APK at $apk"
  cp "$apk" "$WORK/$label.apk"
  sha256sum "$WORK/$label.apk" | sed "s|$WORK/||"
}

copy_project "$WORK/a"
copy_project "$WORK/b"
# Two distinct absolute paths on purpose: path leakage shows up as a failure.
build_once "$WORK/a" "build-a"
build_once "$WORK/b" "build-b"
echo

A="$WORK/build-a.apk"
B="$WORK/build-b.apk"
HA="$(sha256sum "$A" | cut -d' ' -f1)"
HB="$(sha256sum "$B" | cut -d' ' -f1)"

if [[ "$HA" == "$HB" ]]; then
  echo "RESULT: REPRODUCIBLE — both builds are byte-identical (sha256 $HA)."
  exit 0
fi

echo "RESULT: NOT byte-identical."
echo "  a: $HA"
echo "  b: $HB"
echo
echo "== differing APK entries =="
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
    print("  all zip entries are identical — difference is in the signing block / zip structure")
PY
echo
echo "Record this output in docs/REPRODUCIBLE-BUILDS.md (state the exact causes found)."
exit 1
