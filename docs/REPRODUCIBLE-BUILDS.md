# NoteZero — reproducible release builds

**Status: APK builds verified byte-for-byte reproducible** (2026-11-15) with the pinned
toolchain below. AAB reproducibility is tracked separately (same method, not yet claimed).

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
./scripts/verify-reproducible.sh /tmp/notezero-repro
```

The script copies the tree into two independent directories, builds `:app:assembleRelease`
in each with the fixed epoch and no build cache, then compares the signed APKs byte-for-byte.
On a mismatch it prints every differing zip entry (SHA-256/CRC/time/size) so the cause can be
tracked instead of guessed.

## Measured result

Run on 2026-11-15 (two fresh copies, `--no-daemon --no-build-cache`, oracle:

```
-- building build-a in /tmp/notezero-repro/a
f7eb628439e3c0cb204fdcf7bae49588c48ccec691efa7e91a08ccd473b3b082  build-a.apk
-- building build-b in /tmp/notezero-repro/b
f7eb628439e3c0cb204fdcf7bae49588c48ccec691efa7e91a08ccd473b3b082  build-b.apk
RESULT: REPRODUCIBLE — both builds are byte-identical (sha256 f7eb6284…b082).
```

An earlier check had shown differing hashes; the cause was **incremental in-place builds**
(stale `build/` state + configuration cache), not the release pipeline. Fresh-copy builds with
the documented epoch are identical, including the APK Signing Block (RSA signatures are
deterministic). The verification is re-run on the release commit and the hash is recorded in
`docs/VERIFICATION-2026-11-15.md` before a release is tagged.

## What is verified vs. what is not

Verified:
- signed release **APK** byte equality across two independent clean builds in different paths;
- debug/CI builds green with the same pinned toolchain (`gradle/wrapper`, `libs.versions.toml`);
- no `dependenciesInfo` blob (`includeInApk=false`), static R8 config, no clock-derived
  `versionName`/`versionCode` or build-id strings in the code path;
- fixed `SOURCE_DATE_EPOCH` honoured by the build.

Not claimed (yet):
- **AAB** byte equality (bundle packaging differs; tracked as Phase-5 follow-up; the upload
  artifact is signed and reproducible in content, but bundle-level equality is not measured).
- Cross-toolchain reproducibility (different JDK build, SDK revision or build-tools version may
  change the bytes — F-Droid must use the documented toolchain, which is why this file pins it).
- Different signing key obviously changes the APK (that is expected and desirable).

## F-Droid / third-party builder notes

- The release variant is **unsigned** when `NOTEZERO_KEYSTORE_PATH` is absent, so F-Droid's
  builder signs with F-Droid's own key. Reproducible-build verification compares the
  **unsigned APK content** or re-signs both artifacts with the same test key; signing itself is
  deterministic for RSA PKCS#1 v1.5, as measured above.
- F-Droid builds must use: JDK 17, Gradle 9.3.1 wrapper, AGP 9.1.1, Kotlin 2.2.10, SDK
  android-36, build-tools 36.0.0, and `SOURCE_DATE_EPOCH=1763054400` (see `fdroid/` recipe).
- `docs/RELEASING.md` links here from the release checklist.
