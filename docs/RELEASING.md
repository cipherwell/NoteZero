# NoteZero — release process

## Versioning

- `versionCode` increments monotonically; `versionName` follows `MAJOR.MINOR.PATCH`.
- Pre-1.0: minor bumps for features, patch for fixes. `0.1.x` = Phase 1 (security core +
  notes MVP).
- CHANGELOG.md keeps a dated entry per release; store `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

## Checklist

1. `git status` clean on `main`; all CI jobs green on the release commit.
2. Unit tests: `./gradlew :app:testDebugUnitTest`.
3. Offline guard: `./scripts/check-offline.sh --gradle` exits 0.
4. Manifest audit: merged manifest has only `USE_BIOMETRIC`; `allowBackup=false`;
   `usesCleartextTraffic=false`. Spot-check `app/build/intermediates/merged_manifest/...`.
5. Instrumented tests on the emulator: `./gradlew :app:connectedDebugAndroidTest`.
6. Emulator smoke: setup → recovery phrase → lock/unlock → create/edit note → backup export.
7. Signed build:
   ```bash
   set -a; . ~/.cipherwell-release/notezero.env; set +a
   export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
   ./gradlew clean :app:assembleRelease :app:bundleRelease
   ```
8. Verify the APK signature **and certificate** before publishing:
   ```bash
   $HOME/Android/Sdk/build-tools/36.0.0/apksigner verify --print-certs \
     app/build/outputs/apk/release/app-release.apk
   # expected SHA-256:
   # FA:CA:DD:67:B2:82:AF:A5:E7:F4:C9:C4:70:61:71:80:C9:EF:9E:2D:12:39:AD:F1:0C:29:C4:F9:95:AA:92:78
   ```
9. Record `sha256sum` of the APK/AAB in the CHANGELOG entry (and CI artifact).
10. Tag `vX.Y.Z`, publish source tag + artifact, upload store metadata.
11. Never upload a debug-signed bundle; never reuse VaultZero's key.

## Reproducibility

- CI builds releases with the same JDK 17, Gradle 9.3.1 and AGP 9.1.1 pinned in the repo.
- `dependenciesInfo` metadata is disabled so the AGP dependency blob does not vary.
- R8 config is static; no build-time generated version strings.
- Phase 5 target: document flag-for-flag reproduction (`--no-daemon`, `SOURCE_DATE_EPOCH`,
  `-Pandroid.experimental.enableSourceSetPathsMap=false`) and publish verification docs for
  F-Droid's reproducible-builds infrastructure.

## F-Droid

- Eligibility: the public repository must exist for **3 months** before the
  reproducible-build submission window closes for a given release (SPEC).
- Recipe scaffold: `fdroid/com.cipherwell.notezero.yml` (unsigned release variant is
  supported by design — AGP leaves it unsigned when the signing env vars are absent).
- Metadata: `fastlane/metadata/android/en-US/` (title, short/full description, changelogs,
  screenshots once the UI is final).
- F-Droid builds are signed by F-Droid; users' existing installs must come from the same
  distribution channel (do not mix GitHub-signed APKs with F-Droid installs).

## Fix releases

Security fix releases: patch bump, CHANGELOG "Security" section, advisory link, and a
regression test in the fix commit. Supported-version table lives in SECURITY.md.
