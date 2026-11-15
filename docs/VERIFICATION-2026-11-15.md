# Verification — Phase 1 bootstrap (2026-11-15)

This is the measured evidence trail for the first NoteZero build. Everything below was run
on this workstation (`cipherwell` / CachyOS) on 2026-11-15 and is reproducible from the tagged
commit; commands are literal.

Environment: JDK 17 (`/usr/lib/jvm/java-17-openjdk`), AGP 9.1.1, Gradle 9.3.1,
Kotlin 2.2.10, compileSdk 36, minSdk 24. Emulator: `VaultZero-Live` (android-34,
google_apis, x86_64, KVM, API 34) with a real visible window.

## 1. Build

| Command | Result |
|---|---|
> **Reproducibility status:** two release builds of the same commit produced different
> packaging hashes (`ed520573…` vs `a23f8972…`). Signing is correct in both; byte-for-byte
> reproducibility is a Phase-5 task (docs/RELEASING.md), not claimed here.

| `./gradlew :app:assembleDebug` | **BUILD SUCCESSFUL**, `app-debug.apk` (`sha256 480893eadc36bdc689d6c411ea868683a229a8fc4bf89e1eb07d82f141258e46`) |
| `./gradlew :app:assembleRelease :app:bundleRelease` (signing env sourced) | **BUILD SUCCESSFUL**, `app-release.apk` (`a23f8972044e51da7fae762267008f1d5c3f924ecbe24f98cab2a9c0c3e328d9`), `app-release.aab` (`1488edd372d634e64a94fdd5ea8c708afa0b0770a0f6a57f02c8670b5d327ba5`) |
| `./gradlew :app:assembleRelease` with signing env **unset** | **BUILD SUCCESSFUL**, emits `app-release-unsigned.apk` — `apksigner verify` says `DOES NOT VERIFY` (no debug-key fallback; F-Droid builder signs it) |

## 2. Unit tests — 290 tests, 0 failures, 0 errors, 0 skipped (27 classes)

`./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL**.

| Package | Tests |
|---|---|
| crypto (NoteCrypto, KeyEnvelope, Bip39Recovery, Encoding) | 61 |
| data (repository, payload codecs, backup) | 49 |
| model (markdown codec, queries) | 43 |
| security (offline guarantee) | 5 |
| ui shell (viewmodel, setup/unlock/recovery/settings, contrast) | 62 |
| ui notes + editor | 70 |

Independent vectors include: RFC 7914 PBKDF2-HMAC-SHA256, 4× NIST CAVP AES-256-GCM KATs,
official BIP-39 English (Trezor) 12-word vectors, RFC 4231 HMAC, RFC 4648 Base64,
2048-word list SHA-256, and AAD id/kind cross-swap rejection.

## 3. Instrumented tests (real device crypto) — 10 tests OK

`adb shell am instrument -w com.cipherwell.notezero.test/androidx.test.runner.AndroidJUnitRunner`
→ `OK (10 tests)` on the emulator (fresh install).

`VaultFlowE2ETest` (59 s on device) asserts, through the real Android crypto stack:

1. real vault setup returns a 12-word phrase that passes BIP-39 validation;
2. **change master password leaves the item ciphertext byte-identical** (raw AES-GCM blob
   read through a second read-only SQLite connection);
3. locked repository exposes no decrypted items; wrong password → `WRONG_PASSWORD`;
   correct password → items decrypted;
4. recovery-phrase reset succeeds, the old password stops working, new password works;
5. **plaintext scan of all DB files** (main + WAL + SHM) finds no note title, body or phrase
   words after locking;
6. real UI flow: unlock screen → notes list → editor → markdown typed → preview toggle →
   back → note persisted (screenshot evidence below);
7. editor-created note decrypts back after re-unlock and is not plaintext on disk.

`BiometricKeyManagerTest` runs with graceful `Assume` skips on an emulator without enrolled
biometrics; it asserts that a prompt-free Keystore `doFinal` is refused by per-use auth and
that unauthenticated enrollment persists nothing.

## 4. UI evidence (real emulator renders, `-PallowScreenshots=true` build)

| Screenshot | What it proves |
|---|---|
| `docs/screenshots/00-setup.png` | first-run vault creation screen, password policy, one accent blue, no recovery backdoor copy |
| `docs/screenshots/00-unlock.png` | locked-state unlock screen ("No account. No network. Just your encrypted vault.") |
| `docs/screenshots/01-notes-list.png` | decrypted notes list after unlock, pill nav, FAB |
| `docs/screenshots/02-editor-edit.png` | markdown editor, word/char count, autosave state |
| `docs/screenshots/03-editor-preview.png` | block preview: heading, tappable checklist, quote, table |
| `docs/screenshots/04-notes-list-two-notes.png` | note created through the editor persisted |

`sha256` of each PNG is recorded in git; the stale pre-fix preview screenshot was deleted.

## 5. Security invariants measured

- **FLAG_SECURE is on by default**: on the normal build, `adb exec-out screencap -p`
  returns a **0-byte file** (secure window cannot be captured); the same screens rendered
  normally with `-PallowScreenshots=true`. Screenshot-enabled builds are debug-only.
- **Offline guarantee**: `scripts/check-offline.sh --gradle` → exit 0.
- **Release APK permissions** (`aapt2 dump permissions app-release-unsigned.apk`):
  `android.permission.USE_BIOMETRIC` plus `androidx.core`'s signature-level
  `com.cipherwell.notezero.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. **No INTERNET, no
  ACCESS_NETWORK_STATE, no USE_FINGERPRINT, no camera/location/storage.**
- **Source hygiene**: 0 `TODO(`/`FIXME` in `app/src/main/java`, 0 `Log.`/`println`, 0 hex
  colour literals outside `ui/theme/Tokens.kt`, 0 network imports.
- **Logcat**: 0 matches for test passwords, note titles or recovery words after a full
  setup→edit→lock→unlock cycle.
- **Signing**: `apksigner verify --print-certs app-release.apk` →
  `CN=Cipherwell, O=Cipherwell, OU=NoteZero, C=US`,
  SHA-256 `facadd67b282afa5e7f4c9c470617180c9ef9e2d1239adf10c29c4f995aa9278`, i.e. the
  independent NoteZero upload key (not VaultZero's, not the debug key).

## 6. Known gaps (honest scope statement)

- Encrypted backup **engine** is implemented and unit-tested (SAF-agnostic stream envelopes,
  duplicate-safe import), but the SAF picker **UI** is not in this build: the picker launches
  another activity, which (correctly) triggers the immediate background lock; the UI flow
  must resume after re-unlock. That wiring is the first item of Phase 2.
- Biometric unlock UI requires Android 9+ (platform BiometricPrompt); on API 24–27 the CTA is
  hidden by design (see `docs/DECISIONS.md` #11). Keystore per-use CryptoObject enforcement is
  unchanged.
- Code editor (phase 3), attachments/camera (phase 4), F-Droid submission (3-month rule) are
  deliberately out of this session.
