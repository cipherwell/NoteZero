# NoteZero — build guide

## Toolchain (verified on this workstation, 2026-11-15)

| Component | Value |
|---|---|
| JDK | **17** at `/usr/lib/jvm/java-17-openjdk` (JDK 27 is the system default and is NOT supported by AGP 9.1) |
| Gradle | 9.3.1 via `./gradlew` |
| AGP | 9.1.1 |
| Kotlin | 2.2.10 / KSP 2.3.5 |
| Android SDK | `$HOME/Android/Sdk` (platforms 34/35/36/36.1, build-tools 36.0.0) |
| compileSdk / targetSdk / minSdk | 36 / 36 / 24 |
| Application ID | `com.cipherwell.notezero` |

`local.properties` with `sdk.dir=/home/cipherwell/Android/Sdk` is machine-local and gitignored.

## Debug build

```bash
cd notezero
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
./scripts/gen-debug-keystore.sh    # once; creates ./debug.keystore (gitignored)
./gradlew :app:assembleDebug
ls app/build/outputs/apk/debug/app-debug.apk
```

The debug build is signed by the project-local debug key (`androiddebugkey`, password
`android`). Never distribute a debug build.

### Screenshot opt-in (debug builds only, automation only)

FLAG_SECURE is on for every normal build, so `adb screencap` returns a **0-byte** file. For
automated UI verification only:

```bash
./gradlew :app:assembleDebug -PallowScreenshots=true
```

The `-PallowScreenshots` and `-PdevDisableAutoLock` flags are **debug-variant only**: they are
declared under `buildTypes.debug` and release builds hardcode both values to `false`, so no
release build can be produced with FLAG_SECURE or the background/idle auto-lock disabled
(verified by the audit remediation).

## Release build

The release key is **NoteZero's own upload key**, independent from VaultZero's. It lives
outside the repo:

- keystore: `~/.cipherwell-release/notezero-upload.jks` (PKCS12, RSA 4096, alias `notezero-upload`)
- env: `~/.cipherwell-release/notezero.env` (0600) — contains the variables below
- certificate SHA-256: `FA:CA:DD:67:B2:82:AF:A5:E7:F4:C9:C4:70:61:71:80:C9:EF:9E:2D:12:39:AD:F1:0C:29:C4:F9:95:AA:92:78`

Generate it (once, never overwrite) with `scripts/gen-release-keystore.sh`. Back up the
keystore + env file offline; a lost upload key cannot be recovered (Play App Signing
continuity breaks, and package updates signed with a different key are rejected).

```bash
set -a; . ~/.cipherwell-release/notezero.env; set +a
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
./gradlew :app:assembleRelease        # -> app/build/outputs/apk/release/app-release.apk
./gradlew :app:bundleRelease          # -> app/build/outputs/bundle/release/app-release.aab
```

`NOTEZERO_KEYSTORE_PATH`, `NOTEZERO_STORE_PASSWORD`, `NOTEZERO_KEY_PASSWORD`
(and optional `NOTEZERO_KEY_ALIAS`) are the only signing inputs. If any is missing the
**release variant is intentionally left unsigned** so F-Droid or another distributor can
sign it with their own key — the build never falls back to a debug key.

Note: PKCS12 keystores ignore a separate key password; `NOTEZERO_STORE_PASSWORD` is
authoritative.

## Offline guarantee check

```bash
./scripts/check-offline.sh              # fast: source + merged manifest
./scripts/check-offline.sh --gradle     # also resolves runtime deps and greps network libs
```

CI runs the `--gradle` form. Also enforced by `OfflineGuaranteeTest` in the unit tests.

## Tests

```bash
./gradlew :app:testDebugUnitTest
# focused:
./gradlew :app:testDebugUnitTest --tests "com.cipherwell.notezero.crypto.*"
```

## Instrumented tests / emulator

```bash
# AVD already provisioned on this box: VaultZero-Live (android-34, google_apis, x86_64)
./gradlew :app:connectedDebugAndroidTest
```

For a visible, screenshottable session build with `-PallowScreenshots=true` and launch
with `QT_QPA_PLATFORM=xcb` on Wayland (XWayland) so a real window appears.

## Reproducible release builds

- `dependenciesInfo { includeInApk = false; includeInBundle = false }` keeps AGP's
  dependency blob metadata out of the artifact.
- No BuildConfig fields derived from the clock or environment; `SOURCE_DATE_EPOCH` is
  honoured by AGP/JDK where possible.
- R8 keeps the same configuration for every release build; no dynamic version names.
- Phase 5 adds byte-for-byte verification and documents the F-Droid build flags.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Unsupported class file major version` / AGP JDK error | `JAVA_HOME` points at JDK 27 — export JDK 17 (see above) |
| `SDK location not found` | create `local.properties` with `sdk.dir=...` |
| `debug.keystore` missing | run `./scripts/gen-debug-keystore.sh` |
| Gradle lock message "Waiting to acquire…" | another teammate/build holds the project lock; let it wait, do not kill daemons |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` on device | uninstall previous NoteZero build (`adb uninstall com.cipherwell.notezero`) |
