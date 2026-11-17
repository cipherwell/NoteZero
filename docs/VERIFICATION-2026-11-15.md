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

## 4. UI evidence (real emulator renders, `-PallowScreenshots=true` **debug** build)

The `-PallowScreenshots` / `-PdevDisableAutoLock` properties exist only on the **debug**
variant; the release variant hardcodes both `BuildConfig` fields to `false` (audit F4/D3), so
this section's flag can never reach a shipped release.

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
  normally with `-PallowScreenshots=true` on a debug build. Screenshot-enabled builds are
  debug-only by construction: the release variant hardcodes `ALLOW_SCREENSHOTS = false` and
  `DEV_DISABLE_AUTO_LOCK = false` in `app/build.gradle.kts` (audit F4/D3).
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

---

## 7. Increment 2 (same day): backup UI + code editor

Second pass after Phase 1: SAF encrypted-backup UI and the Phase-3 code editor. Same
environment/emulator; commands below were run in the same tree.

### Build & tests

| Command | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest` | **BUILD SUCCESSFUL — 533 tests, 0 failures, 0 errors, 0 skipped (43 classes)** |
| `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PallowScreenshots=true` | **BUILD SUCCESSFUL** |
| `adb shell am instrument -w com.cipherwell.notezero.test/…AndroidJUnitRunner` | **OK (10 tests)** incl. extended `VaultFlowE2ETest` |
| `./gradlew :app:assembleRelease :app:bundleRelease` (signing env sourced) | **BUILD SUCCESSFUL** — APK `2998c9546e3a1bba90d44ed85c685b95f7aacf437e0b17861b8c166706a53195`, AAB `6020308605816c683305edf65c972c9fbf028a71a471ac5a1d2782df11f521c2`; certificate still `FA:CA:DD:67:…:92:78` |

Test areas: crypto 61 · data 57 (incl. 8 external-activity lock tests) · model 43 · security 5 ·
code engine 126 · ui code 72 · ui backup/settings 37 · ui shell/notes 132.

Code editor engine performance (200 KB inputs, JUnit XML): Kotlin 6.42 ms, JavaScript 7.85 ms,
XML 3.21 ms (target < 250 ms); 200k token cap, 1 MB single token and 500 KB unterminated
constructs bounded; 120 random + 150 construct-sequence fuzz cases per language with no throws.

### Device E2E additions (real UI, real crypto)

`VaultFlowE2ETest` now also proves:
1. Settings renders the encrypted "Data & backup" section (export/import, separate-password
   toggle, encrypted/local-only copy) — screenshot `05-settings-backup.png`.
2. Code workspace opens from the notes screen (`nav_code`), a new file `hello.kt` is created
   through the dialog, edited in the syntax-highlighted editor until save state reads
   `Saved`, and is listed back in the tree — screenshots `06/07/08`.
3. The created code file is persisted as `ItemKind.CODE_FILE` through the same encrypted
   envelope as notes, and the raw SQLite files (db + WAL + SHM) contain **no** plaintext of
   the code file name or body (`CodeE2E_secret` marker absent).
4. External-activity deferral is covered by repository tests (8) rather than SAF UI automation;
   the SAF picker is a system activity and is not driven by instrumentation.

### Defects found by this E2E pass (fixed with regression tests)

- Workspace header always pluralised ("1 files") → fixed `countLabel()` helper + test.
- Settings back button scrolls away with content → E2E uses the system back action
  (routed through the screen's `BackHandler`); product UX unchanged.

### Updated screenshots

`docs/screenshots/00-setup.png` … `08-code-workspace-file.png` (10 PNGs, hashes in git). The
first-run setup, unlock, notes, editor/preview, backup settings, code tree and Kotlin editor
are all real emulator captures from these runs.

### Still open

- Code editor UI is Phase-3 complete for the requested feature list; remaining Phase-3 polish
  is deliberately deferred (search-in-files across the workspace, multi-cursor, file export).
- Byte-for-byte reproducible release builds (Phase 5); F-Droid submission window opens
  2027-02-15.
- Strings are still inline (bootstrap policy); extraction to `res/values/strings.xml` for
  translation is a tracked phase-5 item.

---

## 8. Increment 3 (same day): encrypted attachments + code-workspace polish

### Build & tests

| Command | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest` | **BUILD SUCCESSFUL — 663 tests, 0 failures, 0 errors, 0 skipped (51 classes)** |
| `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PallowScreenshots=true` | **BUILD SUCCESSFUL** |
| `adb shell am instrument -w com.cipherwell.notezero.test/…AndroidJUnitRunner` | **OK (10 tests)** incl. extended `VaultFlowE2ETest` (notes + backup + code + workspace search) |
| `./gradlew :app:assembleRelease :app:bundleRelease` (signing env sourced) | **BUILD SUCCESSFUL** — APK `d498b1f315e2d5acff1c8b494b8c74f1fade05ec4d300aa52743ad084361cc30`, AAB `a270d6d530e48447d0d0f21b2aa453423a866eb806c63f734e67e3ceb545591f`; certificate `FA:CA:DD:67:…:92:78` |
| normal debug build + `adb exec-out screencap -p` | **0-byte capture** → FLAG_SECURE still active |
| `aapt2 dump permissions` on the release APK | `USE_BIOMETRIC` only (+ androidx.core signature-level receiver guard) |
| `./scripts/check-offline.sh --gradle` | exit 0 |

Test areas: data 99 · code engine 139 · ui code 100 · ui attachments 47 · ui editor 38 ·
crypto 61 · model 43 · backup/settings 37 · security 5 · shell/notes 94.

Attachment-specific coverage: NZAT v1 blob format + bounds, import limits (10 MiB / 100 per
note / 512 MiB quota) with no orphan blobs, AAD swap between notes/ids rejected, tampered
ciphertext and valid-AEAD/SHA-mismatch rejected, trash-keeps vs delete-forever-removes, orphan
sweep (with corrupt-row guard), locked fail-closed, export sink failure, backup v2 round trip
with exact bytes + duplicate-safe re-import + cross-vault credential + frozen v1 fixture import
+ missing-blob skip-and-count, and a plaintext scan of the attachment directory.

### Device E2E additions

- Code workspace search: toggle → query `CodeE2E_secret` → **1 match** at `hello.kt 2:19` →
  tap result → editor opens at the line. Screenshots `09-code-workspace-search.png` /
  `10-code-editor-search-result.png`.
- Attachment strip renders in the real notes editor ("Attachments · No attachments" + add
  button) — screenshot `02-editor-edit.png`. Actual import/export is covered by Robolectric +
  real-crypto data tests because the Photo Picker / SAF picker are system activities that
  instrumentation cannot drive reliably.

### Screenshots

11 PNGs in `docs/screenshots/` (00-setup … 10-code-editor-search-result), all real emulator
captures from the screenshot-enabled debug build; hashes in git.

### Still open (Phase 4 follow-ups / Phase 5)

- Drawing canvas, camera scan (still no camera permission), tables editing UI, quick capture
  and widgets.
- Chunked/streamed backup format v3 to lift the 64 MiB backup cap for large attachment vaults.
- Byte-for-byte reproducible release builds; string extraction; F-Droid submission after the
  3-month window (2027-02-15).

---

## 9. Increment 4 (same day): locked notes/folders + reproducible release builds

### Build, tests & signing

| Command | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest` | **BUILD SUCCESSFUL — 740 tests, 0 failures, 0 errors, 0 skipped (59 classes)** |
| `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PallowScreenshots=true` | **BUILD SUCCESSFUL** |
| `adb shell am instrument -w com.cipherwell.notezero.test/…AndroidJUnitRunner` | **OK (10 tests)** incl. locked-note E2E flow |
| `./scripts/verify-reproducible.sh` (2 clean builds, `--no-daemon --no-build-cache`) | **RESULT: REPRODUCIBLE** — both signed APKs byte-identical |
| published release APK | `sha256 18254b31924321142b292999cf44fd9ef07e9efe04d6aad366b5196b57845eaa` |
| release AAB (not yet claimed reproducible) | `sha256 d26bf2167fadb943e64aa995a0ae6a746df085bafd71d0f31773854b43dc6cf5` |
| `apksigner verify --print-certs` | `CN=Cipherwell, O=Cipherwell, OU=NoteZero`, SHA-256 `FA:CA:DD:67:…:92:78` |
| normal debug build + `adb exec-out screencap -p` | **0-byte capture** → FLAG_SECURE active |
| `aapt2 dump permissions` on the release APK | `USE_BIOMETRIC` only (+ androidx.core signature-level receiver guard) |
| `./scripts/check-offline.sh --gradle` | exit 0 |

### Reproducibility evidence

**This section is the single source for the measured release hashes** (audit D8):
`docs/REPRODUCIBLE-BUILDS.md` points here and must not restate a hash. Only the **APK** is
claimed byte-for-byte reproducible; the AAB entry below records the built artifact but is
explicitly not part of that claim.

```
-- building build-a in /tmp/notezero-repro-final/a
18254b31924321142b292999cf44fd9ef07e9efe04d6aad366b5196b57845eaa  build-a.apk
-- building build-b in /tmp/notezero-repro-final/b
18254b31924321142b292999cf44fd9ef07e9efe04d6aad366b5196b57845eaa  build-b.apk
RESULT: REPRODUCIBLE — both builds are byte-identical
```

Pinned environment: JDK 17.0.20.1, Gradle 9.3.1, AGP 9.1.1, Kotlin 2.2.10, SDK android-36,
build-tools 36.0.0, `SOURCE_DATE_EPOCH=1763054400`, NoteZero upload key, no build cache, two
different absolute paths. Full method/limits: `docs/REPRODUCIBLE-BUILDS.md`.

### Locked-content coverage (unit + device)

- ItemCodec v1/v2 fixtures decode (`locked=false`, attachments preserved), v3 round-trips the
  lock flag; FolderCodec v1/v2 same; the flag appears **only** inside the envelope
  (PRAGMA/decrypted-payload checks + plaintext-row scan).
- Repository: setItemLocked/setFolderLocked persist and survive lock→unlock; `saveItem` cannot
  change the stored flag; `verifyMasterPassword` correct/wrong/blank, growing delay 1–4 s and
  30 s cooldown after 5 failures (injected clock, no real sleeps); verification clears on
  lock/close/real background stop/screen-off/session start, is **not** cleared by the SAF-picker
  deferral, and `markLockVerified` requires a live VMK.
- UI: masked rows hide title/body/tags/paths/attachments, search and aggregates exclude them,
  folders hide names + descendants, editor gate refuses masked content, re-mask on clearance,
  settings re-arm; code workspace hides locked files/descendants and reports the hidden count.
- Device E2E: lock note from row menu → masked "Locked note" row (screenshot 11) → tap →
  re-auth dialog (screenshot 12, node capture of the AlertDialog window) → master password →
  note revealed and opened in the editor (screenshot 13) → after session end the lock flag is
  still set and `verifiedLockedIds` is empty.

### Screenshots

14 PNGs in `docs/screenshots/` (00-setup … 13-locked-note-revealed), all real emulator
captures from the screenshot-enabled debug build; hashes in git.

### Still open (Phase 5)

- AAB byte-equality and cross-toolchain reproducibility (same script can be extended).
- String extraction to `res/values/strings.xml` for translation; store assets/metadata polish.
- Audit/pentest pass and F-Droid submission after the 3-month window (2027-02-15).

---

## 10. Increment 5 (same day): strings, security audit + remediation, reproducibility finalised

### Build, tests & signing (release state)

| Command | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest` | **BUILD SUCCESSFUL — 764 tests, 0 failures, 0 errors, 0 skipped (60 classes)** |
| `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PallowScreenshots=true` | **BUILD SUCCESSFUL** |
| `adb shell am instrument -w com.cipherwell.notezero.test/…AndroidJUnitRunner` | **OK (10 tests)** incl. the extended E2E flow |
| `./scripts/verify-reproducible.sh` (signed APK) | **RESULT: REPRODUCIBLE** — both builds `c48de399ed4cba11dfb89bebec896d7bc7737d8d544a0c832a34ca01484368da` |
| `./scripts/verify-reproducible.sh --artifact aab` | **RESULT: REPRODUCIBLE** — both builds `00a66b1e132ac48af4667c1c3e6f2ba872ddcc8cbad4adb0c3b40d79c9caf22a` |
| published `releases/notezero-0.1.0-release.apk` | `c48de399ed4cba11dfb89bebec896d7bc7737d8d544a0c832a34ca01484368da` (signed, verified certificate) |
| published `releases/notezero-0.1.0-release.aab` | `00a66b1e132ac48af4667c1c3e6f2ba872ddcc8cbad4adb0c3b40d79c9caf22a` |
| `apksigner verify --print-certs` | `CN=Cipherwell, O=Cipherwell, OU=NoteZero`, SHA-256 `FA:CA:DD:67:…:92:78` |
| normal debug build + `adb exec-out screencap -p` | **0-byte capture** → FLAG_SECURE active |
| `aapt2 dump permissions` on the release APK | `USE_BIOMETRIC` only (+ androidx.core signature-level receiver guard) |
| `./scripts/check-offline.sh --gradle` | exit 0 |
| release build with `-PallowScreenshots=true -PdevDisableAutoLock=true` | generated release `BuildConfig`: both fields **false** (debug-variant-only flags, audit F4) |

### Test areas (764 / 60 classes)

data 124 · code engine 139 · ui code 117 · other UI 65 · crypto 61 · ui notes 59 ·
ui editor 49 · ui attachments 47 · model 43 · backup/settings 37 · ui locks 18 · security 5.
+24 tests over increment 4, mostly regression tests for the audit findings (editor state
wipe on lock/re-arm, code editor locked gate, code export status gating, stale-session
handling, recovery-reset session boundary).

### UI strings (Phase-5 store readiness)

- 336 `<string>` resources (325 new, 33 `cd_*` content descriptions) + 6 `<plurals>`;
  all user-visible Composables now use `stringResource`/`pluralStringResource`.
- Deliberate inline exceptions documented in `docs/STRINGS.md` (pure-JVM helpers such as
  relative-time formatting, password policy, enum→message mappings, glyphs like `</>`).
- Full suite stays green; visible English is unchanged except the corrected recovery-reset
  copy (audit F2).

### Security audit & remediation

- `docs/AUDIT-2026-11-15-phase5.md`: independent adversarial audit — **0 HIGH / 0 CRITICAL**,
  2 MEDIUM, 8 LOW, 8 INFO, 12 doc/code mismatches. Crypto core, AAD map, envelope parser,
  backup authentication, lock masking (list/search/tags/counts/folders/code workspace), and
  the offline/permission surface held up.
- Remediation (audit remediation log in the report): **F1** editor ViewModels now wipe
  decrypted draft/source, undo/redo, conflict and find state on lock or verification re-arm;
  **F2** recovery-reset copy/docs corrected (phrase is unchanged; rotation is explicit);
  **F3** credentials no longer stored in saved instance state; **F4** security build flags are
  debug-variant only, release hardcodes FLAG_SECURE/auto-lock on; **F5** rotated phrase dropped
  on lock; **F6** code plaintext export is status-gated; **F7** code editor locked-content
  gate; **F8** biometric/reset session boundaries clear lock verifications; **F9** backup OOM
  is a clean failure; **F10** auto-lock race surfaces as a stale-session state, not a crash.
  Doc corrections D1-D12 applied (D1: re-wrap-on-unlock claim removed — deliberately not
  implemented; DECISIONS #17).
- Dependency/licence review: runtime deps Apache-2.0 except protobuf-lite BSD-3; test-only
  JUnit EPL-1.0 is not shipped; no network/telemetry library (DEPENDENCIES.md updated).

### Reproducibility (final)

- Signed **APK and AAB are both byte-identical** across two independent clean builds from
  different absolute paths (`--no-daemon --no-build-cache`, `SOURCE_DATE_EPOCH=1763054400`),
  sha256 recorded above and in `releases/SHA256SUMS`.
- CI now runs `scripts/verify-reproducible.sh --unsigned` on every push (new
  `reproducibility` job), so nondeterminism fails the pipeline without needing signing keys.
- AAB byte equality is now claimed (previously open); cross-toolchain reproducibility is still
  not claimed — F-Droid must use the pinned toolchain in `docs/REPRODUCIBLE-BUILDS.md`.

### Store readiness

- `fastlane/metadata/android/en-US/`: title, short/full description, changelog, and
  8 phone screenshots copied into `images/phoneScreenshots/`.
- New `PRIVACY.md` (+ README/SECURITY links); F-Droid recipe updated with the pinned
  toolchain/reproducible-build notes and `PrivacyPolicy` link.
- 14 emulator screenshots remain the evidence set under `docs/screenshots/`.

### Still open (Phase 5 tail)

- F-Droid submission after the 3-month window (**2027-02-15**); tag the release commit.
- Re-wrap-on-unlock KDF upgrade (deliberately not implemented; document-only for now).
- UI i18n: infrastructure ready (all strings extracted); translations not started.
- Cross-toolchain reproducibility verification with F-Droid's build image.
- Remaining INFO items I1/I5 and the dialog-text `rememberSaveable` note (low impact,
  documented in the audit).

---

## 11. Increment 6 (same day): Phase-4 completion — drawing, export, tables, quick capture

### Build, tests & signing (release state)

| Command | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest` | **BUILD SUCCESSFUL — 866 tests, 0 failures, 0 errors, 0 skipped (72 classes)** |
| `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PallowScreenshots=true` | **BUILD SUCCESSFUL** |
| `adb shell am instrument -w com.cipherwell.notezero.test/…AndroidJUnitRunner` | **OK (10 tests)** incl. the extended E2E flow |
| `./scripts/verify-reproducible.sh` (signed APK) | **RESULT: REPRODUCIBLE** — both builds `04ba7e180981df9a8724d8c9518126d3d40f5db8d6f74dde65a14300b982c71f` |
| `./scripts/verify-reproducible.sh --artifact aab` | **RESULT: REPRODUCIBLE** — both builds `b0f0280ee1e9806ba9bc74d2bd9a7ea058ed442e8dc17b9573515261f0f90898` |
| published `releases/notezero-0.1.0-release.apk` (superseded by §12 after the device-QA fixes) | `04ba7e180981df9a8724d8c9518126d3d40f5db8d6f74dde65a14300b982c71f` |
| published `releases/notezero-0.1.0-release.aab` (superseded by §12) | `b0f0280ee1e9806ba9bc74d2bd9a7ea058ed442e8dc17b9573515261f0f90898` |
| `apksigner verify --print-certs` | `CN=Cipherwell, O=Cipherwell, OU=NoteZero`, SHA-256 `FA:CA:DD:67:…:92:78` |
| normal debug build + `adb exec-out screencap -p` | **0-byte capture** → FLAG_SECURE active |
| `aapt2 dump permissions` on the release APK | `USE_BIOMETRIC` only (+ androidx.core signature-level receiver guard) |
| `./scripts/check-offline.sh --gradle` | exit 0 |

### Test areas (866 / 72 classes)

data 124 · code engine 139 · ui code 117 · ui attachments 88 · ui notes 82 · ui editor 69 ·
crypto 61 · model 61 · other UI 65 · backup/settings 37 · ui locks 18 · security 5.
+102 over increment 5: drawing 41 (sketch/format/renderer/import/canvas), note export 18,
tables 20, export flows 21, quick capture 2.

### Device E2E additions

`VaultFlowE2ETest` now also proves:
1. **Markdown tables**: open the insert-table dialog (screenshot `14-table-insert`), insert a
   GFM table, later assert the persisted note body contains the table separator row;
2. **Drawing canvas**: open it from the attachment add menu (screenshot `15-drawing-canvas`),
   cancel, and assert no attachment was created;
3. **Quick capture**: launch `MainActivity` with `com.cipherwell.notezero.SHORTCUT_NEW_NOTE`
   while the vault is locked → unlock screen → unlock → the pending request opens a new-note
   editor (screenshot `16-quick-capture`) → title typed, back, note persists through the
   encrypted repository.

17 real-emulator screenshots are committed under `docs/screenshots/` (00…16).

### Notes

- Drawing renders are bounded (long edge ≤ 2048 px) and re-rendered at lower resolution if the
  encoded PNG would exceed the 10 MiB attachment cap; bytes are imported through the normal
  encrypted attachment pipeline and never cached as plaintext.
- Note export is the only plaintext-egress path added; it is SAF-only, behind an explicit
  warning, and the HTML renderer escapes everything and drops `javascript:`/`data:` link
  targets (DECISIONS #18).
- Quick capture never bypasses the vault: the request is honoured only after unlock and the
  note is created through the encrypted repository.

### Still open

- Camera scan (adds the CAMERA permission; deliberately last), home-screen widgets, PDF export,
  encrypted per-note version history, translations, and the F-Droid submission window
  (2027-02-15).

---

## 12. Increment 7 (same day): device QA on a real phone (Pixel 9 Pro XL, Android 17)

The release build was installed on the owner's physical device and driven with `adb`/uiautomator
(system UI inspection), with the owner's real settings: **font scale 1.15**, gesture navigation,
enrolled fingerprint + Face unlock, Android 17 / SDK 37, 1344x2992 @ 480dpi.

### Findings and fixes (all reproduced, fixed and re-verified on-device)

| # | Finding (device) | Fix | Re-verified on-device |
|---|---|---|---|
| D1 | Status/navigation bar icons were **white on the light surface** (invisible time/battery) on every screen | `NoteZeroTheme` sets `isAppearanceLightStatusBars` / `isAppearanceLightNavigationBars` from the resolved light/dark theme | Dark icons now readable on the light `#FAFAFA` background (screenshot captured) |
| D2 | Drawing canvas **tool rows clipped below the screen** at font scale 1.15 (Undo/Clear unreachable; ink row half visible) | Fixed 3:4 scroll-contained page (stroke coordinates still normalized; PNG unchanged) + full-screen dialog applies `safeDrawing` insets exactly once (`decorFitsSystemWindows = false`) | Drawn two strokes → Save → encrypted attachment `drawing-*.png` imported, viewer renders it, all tool rows on-screen |
| D3 | Biometric toggle showed **"Biometrics are not available on this device."** when Face didn't match, even though the prompt opened and fingerprint was enrolled | Prompt error codes mapped to `UNAVAILABLE` / `NOT_ENROLLED` / `TEMPORARILY_UNAVAILABLE` / `LOCKOUT` / `CANCELLED` / `FAILED` with specific copy; enrollment null-result no longer claims "unavailable" when hardware is available | Fingerprint enrollment completed; settings shows "Biometric unlock — Enabled…"; biometric unlock from the locked screen unlocked the vault |
| D4 | Unlock screen offered the biometric CTA on a vault that had never enabled biometrics (CTA could only fail) | `NoteRepository.isBiometricUnlockEnrolled()` (wrapped-VMK Keystore check, non-secret) exposed through `VaultUiState.biometricEnrolled`; CTA gated on availability **and** enrollment | Enrolled device: CTA present and works; not-enrolled test (`AppRootTest`) covers the hidden case |
| D5 | Attachment-only note (drawing, no title/body) was **silently moved to trash on back** | `shouldTrashOnLeave(title, body, attachmentCount, …)` keeps any note with content, including attachments | After the fix, a drawing-only note stays in the list (verified on-device once the fixed build was installed) |
| D6 | Stale "Vault locked." banner re-appeared when re-opening Settings later | Banner cleared on Settings entry | Not re-shown on re-entry |
| D7 | Settings counter said "1 locked item(s) revealed" | `plurals.xml` `lock_settings_count` | Unit assertion updated |

Working on-device flows (unchanged features, re-run): password unlock; **biometric unlock**
(fingerprint); lock note → masked row → biometric re-auth → revealed; drawing save/view; table
insert dialog; Markdown note export through SAF (writes the plaintext file; `note.md` existed
in Downloads); encrypted backup export through SAF (`notezero-backup-*.nzbk`, 56 039 bytes,
starts with `NZBK`, contains no plaintext); no camera/storage/network permission prompts.

All QA-created files were deleted from the device (`Downloads/note.md`, the backup file); the
owner's vault data was preserved (same signing key re-installed over it, no uninstall).

### Measurements after the fixes

| Check | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest` | **876 tests / 0 failures / 0 errors / 0 skipped (73 classes)** |
| Instrumented suite (emulator) | **OK (10 tests)**; screenshots refreshed, incl. the new drawing canvas layout |
| `./scripts/check-offline.sh --gradle` | exit 0 |
| Release permissions (`aapt2 dump permissions`) | `USE_BIOMETRIC` only |
| Reproducibility, signed APK | **REPRODUCIBLE** — both builds sha256 `07b2b4bbd7633be35514792892b48a8c4609184ee0b182961c020c6d81c77d60` |
| Reproducibility, AAB | **REPRODUCIBLE** — both builds sha256 `1a93561b62ccf470de8ff8a524f21cb8675641b35493c0bb4d46de1387a9f502` |
| Published `releases/notezero-0.1.0-release.apk` | `07b2b4bbd7633be35514792892b48a8c4609184ee0b182961c020c6d81c77d60` |
| Published `releases/notezero-0.1.0-release.aab` | `1a93561b62ccf470de8ff8a524f21cb8675641b35493c0bb4d46de1387a9f502` |
| Device install of the fixed release | `adb install -r` over the previous build (same upload key) → vault intact, version 0.1.0 |
| Device smoke after install | Unlocks with password + fingerprint; status bar readable; drawing tools visible at 1.15; biometric settings "Enabled"; attachment-only note kept; 0 FATAL/ANR in logcat |

DECISIONS #19 records the policies behind D1–D6.

---

## 13. Increment 8 (same day): create menu, code formats, encrypted PDF documents

Requested by the owner: the Notes "+" should be a pure plus that opens a menu (note, PDF,
drawing, code) with well-known code formats; the chosen reinterpretation of "PDF" is a
first-class blank PDF document item (see DECISIONS #20).

### Build and tests

| Command | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest` | **BUILD SUCCESSFUL — 899 tests, 0 failures, 0 errors, 0 skipped (77 classes)** |
| `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PallowScreenshots=true` | **BUILD SUCCESSFUL** |
| `adb shell am instrument -w …AndroidJUnitRunner` (emulator) | **OK (12 tests)** — 10 previous + `PdfRenderInstrumentedTest` (2) |
| E2E additions | create-note via sheet (screenshot `17-create-sheet`), create+view PDF (`18-pdf-viewer`), PDF item persists with an `application/pdf` attachment |
| `./scripts/check-offline.sh --gradle` | exit 0 |
| Release permissions (`aapt2 dump permissions`) | `USE_BIOMETRIC` only |

New/changed tests (+23): `BlankPdfTest` (wire format/xref/trailer/determinism), `CodeFormatsTest`
(catalog/lookup/extension rules), `NewCodeFileDialogTest` (default/rewrite/validation), 
`PdfViewerScreenTest` (pages/title/back, clean error, export warning), `PayloadCodecTest`
(PDF kind + attachment round trip), `NotesViewModelTest` (`createPdf`/`createCodeFile`, locked
refusal), `NotesListScreenTest` (sheet options + all four create flows), `PdfRenderInstrumentedTest`
(device-only real renderer).

### Reproducibility

| Check | Result |
|---|---|
| `./scripts/verify-reproducible.sh` (signed APK) | **REPRODUCIBLE** — both builds sha256 `4145c3577d6e98d12df472ce159a2a5255d1fa7615765db2f3104803e42fd705` |
| `./scripts/verify-reproducible.sh --artifact aab` | **REPRODUCIBLE** — both builds sha256 `eff9e81fc60d14427c0ffa9c31e283ebf3827b89070b87e2fd28fe1e178840aa` |
| published `releases/notezero-0.1.0-release.apk` | `4145c3577d6e98d12df472ce159a2a5255d1fa7615765db2f3104803e42fd705` |
| published `releases/notezero-0.1.0-release.aab` | `eff9e81fc60d14427c0ffa9c31e283ebf3827b89070b87e2fd28fe1e178840aa` |
| certificate | `CN=Cipherwell, O=Cipherwell, OU=NoteZero`, SHA-256 `FA:CA:DD:67:…:92:78` |

### Device check (Pixel 9 Pro XL, Android 17, font scale 1.15)

- **Plus FAB is icon-only** and opens the create sheet; after switching the sheet from a
  full-screen `Dialog` to an in-window overlay (the dialog window was laid out taller than the
  display, hiding the PDF option), all four options are visible and reachable at font scale
  1.15, and the sheet scrolls/dismisses correctly.
- **Note** creates a Markdown note; **Drawing** creates a note and opens the drawing canvas
  immediately (drawn strokes saved as an encrypted PNG attachment, note kept after back-out);
  **Code** shows the `.txt/.md/.html/.css/…` format picker, resolves typed extensions
  (`script.py`), creates the file and opens the **code editor** with the matching lexer/format;
  **PDF** creates `document-*.pdf` (431 B blank A4 page), renders page 1/1 in the viewer.
- PDF export: explicit `UNENCRYPTED copy` warning → SAF `CreateDocument` → file written to
  Downloads with the suggested name; pulled and validated with `pdfinfo` (**Pages: 1**,
  **JavaScript: no**, valid header/xref/trailer). The file was removed from the device after
  the check.
- Code/PDF rows in Notes open their own viewers; the PDF item survives back-out and appears in
  the list; the drawing-only note is not auto-trashed.
- FLAG_SECURE release screencap still blank; 0 FATAL/ANR; QA items and exported files were
  deleted from the device afterwards (owner's two items untouched).

---

## 14. Increment 9 (2026-11-17): PDF export of notes + quick-note widget

### Build and tests

| Command | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest` | **BUILD SUCCESSFUL — 913 tests, 0 failures, 0 errors, 0 skipped (79 classes)** |
| `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PallowScreenshots=true` | **BUILD SUCCESSFUL** |
| `adb shell am instrument -w …AndroidJUnitRunner` (emulator) | **OK (15 tests)** — 12 previous + `NotePdfRenderInstrumentedTest` (3) |
| `./scripts/check-offline.sh --gradle` | exit 0 |
| Release permissions (`aapt2 dump permissions`) | `USE_BIOMETRIC` only |

New tests (+14): `PdfInlineMarkdownTest` (9: inline grammar, escapes, links, hostile input, run
bounds), PDF request in `NoteExportViewModelTest`, PDF option in `NoteExportScreenTest`,
`QuickNoteWidgetProviderTest` (3: intent contract, RemoteViews inflation, static metadata).
Instrumented: `NotePdfRenderInstrumentedTest` (3): multi-page note accepted by the real
`PdfRenderer` (≥2 pages, real pixels), empty note = valid 1-page PDF, hostile input never
throws.

### PDF export validation (real files)

| Environment | Result |
|---|---|
| Emulator, note with headings/checklist/code/table | 94,980 B file; `pdfinfo`: **Pages 1**, **Page size 595×842 pts (A4)**, **JavaScript no**; `pdftotext` extracts title, "Heading", `[ ] unchecked task`, `[x] done task`, code line and page number `1` |
| Owner's phone (Pixel 9 Pro XL, Android 17) | 11,791 B file; `pdfinfo`: Pages 1, A4, JavaScript no; `pdftotext` extracts the note text; file deleted after the check |
| Export path | row menu → Export note → **PDF (.pdf)** → explicit UNENCRYPTED warning → SAF `CreateDocument`; no temp PDF/cache created; session re-checked after rendering |

### Widget validation

| Check | Result |
|---|---|
| Package receiver | `dumpsys package` / `aapt2`: `QuickNoteWidgetProvider` registered with `APPWIDGET_UPDATE` and provider metadata |
| AppWidget service | `dumpsys appwidget` lists the provider on emulator and phone; parsed info: `updatePeriodMillis=0`, `widgetCategory=1`, `resizeMode=1`, `initialLayout=#7f0b002a` |
| Launcher | tile appears in the widget picker as "New note", "2 × 1" (screenshot `20-widget-picker`); tap-to-add is a user gesture, automated drag not part of CI |
| Content policy | tile shows only glyph + "New note"; no repository access in the provider; click launches the existing quick-capture request (lock-safe) |

### Reproducibility

| Check | Result |
|---|---|
| `./scripts/verify-reproducible.sh` (signed APK) | **REPRODUCIBLE** — both builds sha256 `c72b7cc981712b9d71c98dc88d299c8ab500d51e7350fd226fffbcc546e060ac` |
| `./scripts/verify-reproducible.sh --artifact aab` | **REPRODUCIBLE** — both builds sha256 `b6c900aac80e4a2f33ae36e3fcc0ca2719eac2a7cc477e4d5468a06281e6384b` |
| published `releases/notezero-0.1.0-release.apk` / `.aab` | same hashes as above (`releases/SHA256SUMS`) |
| certificate | `FA:CA:DD:67:…:92:78` unchanged |

### Device state after verification

- Owner's phone: increment-9 release installed over the existing vault (data/biometrics intact),
  app left **locked**, FLAG_SECURE capture blank, **0 NoteZero FATAL/ANR** (the only FATAL in
  logcat was an unrelated app); QA notes and exported files removed from the device.
- Emulator: 15 instrumented tests OK; screenshots `17-create-sheet`, `18-pdf-viewer`,
  `19-export-pdf`, `20-widget-picker` refreshed/added.

DECISIONS #21 records the PDF-export and widget policies.
