# NoteZero — reproducible release builds

**Status: the release *APK* is the only artifact verified byte-for-byte reproducible**
(2026-11-15, run recorded in `docs/VERIFICATION-2026-11-15.md` §9). The **AAB is not yet
claimed reproducible**: the script has an `--artifact aab` mode, but no byte-equality run has
been recorded for it. This document describes the method and points at VERIFICATION §9 as the
single source of the measured hashes — do not copy a hash here (audit D8).

Reproducibility is a Phase-5 spec requirement (`SPEC.md`) and an F-Droid prerequisite.
"Reproducible" here means: an independent party, starting from the same source commit and the
documented toolchain, produces **byte-identical signed APKs**.

## Verified toolchain (must match exactly for a byte-identical result)

| Component | Version used for verification |
|---|---|
| JDK | 17.0.20.1 (`/usr/lib/jvm/java-17-openjdk`) |
| Gradle | 9.3.1 (wrapper) |
| Android Gradle Plugin | 9.1.1 |
| Kotlin / KSP | 2.2.10 / 2.3.5 |
| Android SDK | platforms android-36, build-tools 36.0.0 |
| Signing key | NoteZero upload key (`~/.cipherwell-release/notezero-upload.jks`), alias `notezero-upload`; RSA-4096 / PKCS12 |
| `SOURCE_DATE_EPOCH` | `1763054400` (2026-11-15T12:00:00Z) |
| Build cache | **disabled** for verification (`--no-build-cache`), so shared caches cannot mask nondeterminism |
| Working directory | two **different absolute paths** on purpose (path leakage would fail the check) |

## How to verify

```bash
cd notezero
set -a; . ~/.cipherwell-release/notezero.env; set +a   # signing key
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
./scripts/verify-reproducible.sh /tmp/notezero-repro                 # signed APK (verified artifact)
./scripts/verify-reproducible.sh --unsigned /tmp/notezero-repro-ci   # CI mode (no key)
# Not part of the verified claim yet (no measured run recorded):
./scripts/verify-reproducible.sh --artifact aab /tmp/notezero-repro-aab   # signed AAB
```

CI runs the `--unsigned` mode on every push (`.github/workflows/ci.yml` → `reproducibility`
job), so a nondeterministic build regression fails the pipeline without needing signing
secrets.

The script copies the tree into two independent directories, builds `:app:assembleRelease`
in each with the fixed epoch and no build cache, then compares the signed APKs byte-for-byte.
On a mismatch it prints every differing zip entry (SHA-256/CRC/time/size) so the cause can be
tracked instead of guessed.

## Measured result

**Single source of truth:** `docs/VERIFICATION-2026-11-15.md` §9 ("Increment 4"). It records
the exact commands, the published release APK SHA-256 (`18254b31…45eaa`), the certificate and
the two-build evidence block for that run. `releases/SHA256SUMS` must carry the same value.

What that run proved, and what it did not:

- **Proved:** two fresh-copy signed **APK** builds with the pinned toolchain, `--no-daemon
  --no-build-cache` and `SOURCE_DATE_EPOCH=1763054400` are byte-identical (including the APK
  Signing Block; RSA signatures are deterministic).
- **Not claimed:** AAB byte-equality (no measured run), cross-toolchain reproducibility, and
  reproduction from a different machine. The earlier `f7eb6284…` figure that appeared in this
  file was from a pre-release iteration and is **superseded**; it must not be quoted again
  (audit D8).

An earlier check had shown differing hashes; the cause was **incremental in-place builds**
(stale `build/` state + configuration cache), not the release pipeline. Fresh-copy builds with
the documented epoch are identical. Re-run the script on the release commit and record the
result in `docs/VERIFICATION-2026-11-15.md` (or the next `docs/VERIFICATION-*` file) before a
release is tagged — and update `releases/SHA256SUMS` from that run, not from this page.
