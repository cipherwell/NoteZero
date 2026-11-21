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

---

## 15. Increment 10 — Library + EPUB/PDF readers + encrypted reading state (2026-11-17)

Scope: the first slice of the all-in-one direction (`docs/ROADMAP-ALL-IN-ONE.md` phase 10).
Research basis: `docs/RESEARCH-ALL-IN-ONE-2026-11-17.md`; policies: DECISIONS #22; contracts:
`docs/CONTRACTS-PHASE9.md`; engine details: `docs/EPUB-ENGINE.md`; reader details:
`docs/READER-UI.md`.

### 15.1 Data layer: encrypted reading state (`ItemCodec` payload v4)

| Check | Result |
|---|---|
| New item kind | `ItemKind.BOOK_FILE` appended (ordinal 3); existing ordinals frozen; EPUB stored as an encrypted `application/epub+zip` `NZAT` attachment |
| Reading state | `ReadingState(position, fraction, total, bookmarks, author, language)` inside the AES-GCM item envelope; payload v4; v1–v3 still decode (`readingState = null`); repository-owned (`saveItem` preserves it; only `saveReadingState` mutates) |
| Tests | `PayloadCodecTest` v4 round-trip + v3 compatibility + bounds; `EncryptedNoteRepositoryTest.readingStateIsPersistedEncryptedAndRepositoryOwned` asserts persistence through lock/unlock, no plaintext bookmark label/author in Room ciphertext, `saveItem` cannot roll it back; `backupRoundTripCarriesReadingState`; legacy v1 backup fixtures still import |
| Backup | Reading state travels inside the existing backup v2 item payload; no format bump outside the envelope |

### 15.2 EPUB engine (pure JVM, no new dependencies)

| Check | Result |
|---|---|
| Delivered | `book/` package: `EpubParser`, `EpubDocument`, block model, ZIP reader, secure XML, TOC/metadata/cover, image resources, `close()` |
| Support | EPUB 2/3, spine order, EPUB3 nav + EPUB2 NCX, derived titles, images/cover; no scripts/CSS; tables/lists approximate by design |
| Safety | bounded entries (≤10k), total ≤128 MiB, entry ≤16 MiB, path traversal rejected, duplicate names first-wins, truncated/ZIP64 rejected, XXE/DTD/external entities disabled, DRM refused (`encryption.xml`/`rights.xml`), never throws on hostile input |
| Tests | **104 JVM tests / 0 failures** (Epub3 23, Epub2 8, ChapterBlocks 21, Security 19, Limits 15, Paths 7, Lifecycle 6, Block model 5), including a 1,000-sample random/malformed fuzz corpus; no temporary files written |

### 15.3 Library + readers (UI)

| Check | Result |
|---|---|
| Library | All/Books/PDFs/Drawings filters, progress + Continue, SVG-free in-memory drawing preview, rename/lock/trash rules shared with notes, masked items hidden; SAF import with generic MIME list (providers report EPUB inconsistently) and parser validation; failed import trashes its placeholder item |
| EPUB reader | TOC, in-book search with snippets, bookmarks, appearance (font/line spacing/margins, paper/sepia/night), progress, tap zones, resume; duplicate chapter-heading artefact found on device and fixed; night code-block contrast found on device and fixed |
| PDF reader | page progress/slider, jump dialog with validation, bookmark add/list/delete, resume position |
| Tests | **40 Library/reader Robolectric tests** (LibraryScreen 12, BookReader 16, helpers 7, prefs 5) + **5 PDF reading-tools tests**; total unit suite **1068 tests / 0 failures / 91 classes**; **15 instrumented tests OK** on the emulator |

### 15.4 Emulator device QA (measured)

| Step | Evidence |
|---|---|
| EPUB import | SAF picker → `quiet-library.epub` imported as `BOOK_FILE`; shelf showed title **The Quiet Library**, author Ada Test, `Chapter 1 of 2`, 50% progress |
| EPUB reader | chapter headings (deduplicated), bold/italic/code inline styles, lists, quote, code block, inline PNG image all rendered; TOC dialog listed both chapters |
| Search | query `secret` → **1 result** in *The Middle* with snippet and navigation |
| Reading tools | enlarged font, night theme applied; bookmark `Saved` added on chapter 1 and **still present after force-stop + unlock**; night code block readable after the contrast fix |
| PDF import | SAF picker → `reader-sample-3p.pdf` (4,231 B, 3 pages) imported as `PDF_FILE` |
| PDF tools | progress `1 / 3` → jump to page 3 → bookmark saved (`Page 2` at 66%, shown as `Page 2 / 3` after resume) → **bookmark and position persisted after force-stop + vault unlock** |
| Library progress | after restarts the shelf showed `qc-3page` = Page 2 of 3 / 66% and the EPUB = Chapter 1 of 2 / 50% |
| Crashes | 0 NoteZero FATAL/ANR during the session (logcat clean) |
| Screenshots | `23-library`, `24-epub-reader`, `25-pdf-viewer-tools`, `26-reader-bookmarks-night` |

### 15.5 Security + release verification

| Check | Result |
|---|---|
| Offline guard | `./scripts/check-offline.sh --gradle` → `OK: NoteZero is offline by construction.` (test updated to allow XML parser namespace identifiers while still failing any real URL literal; engine/reader imports contain no network API) |
| Permissions | release APK: `USE_BIOMETRIC` only (plus the generated dynamic-receiver permission); no INTERNET/STORAGE/CAMERA additions |
| Token lint | `vault_token_lint` clean for `ui/reader` (3 files); no raw hex/ARGB in new library/reader code; no TODO/FIXME/Log/println in `book/`, `ui/library/`, `ui/reader/` |
| FLAG_SECURE | unchanged default path; release builds keep it on (no screenshot exemption added in this increment; `allowScreenshots` remains debug-only) |
| Reproducible APK | **REPRODUCIBLE** — both builds sha256 `fea43e9f918611980738f2d64c9e17d321678ced3130d274270a7b35fb48c3c5` |
| Reproducible AAB | **REPRODUCIBLE** — both builds sha256 `303b187ac6a6dbe67bbdbedde734392f78add3db039bf6e8c78bbcbaee6886f5` |
| Artifacts | `releases/notezero-0.1.0-release.apk` / `.aab` replaced with these builds; `releases/SHA256SUMS` updated |
| Certificate | `CN=Cipherwell, O=Cipherwell, OU=NoteZero, C=US`; SHA-256 digest `FA:CA:DD:67:B2:82:AF:A5:E7:F4:C9:C4:70:61:71:80:C9:EF:9E:2D:12:39:AD:F1:0C:29:C4:F9:95:AA:92:78` (unchanged) |
| Owner phone | release APK installed over the existing vault (data/biometrics intact, `firstInstallTime` unchanged); phone was locked at the lockscreen so no UI interaction was possible in this pass — no app crash was logged; the pushed QA PDF was removed from Downloads; phone left locked |

### 15.6 Residual notes

- Reader percent is chapter/page-granular (e.g. `Chapter 1 of 2` with a 50% bar); exact
  intra-chapter percentage is deferred with the annotation/positions phase.
- EPUB rendering is semantic, not CSS-faithful (documented in `docs/EPUB-ENGINE.md`); complex
  CSS layouts, scripts, footnotes and SVG are out of scope until a vetted renderer decision.
- PDF bookmarks default label duplicates the position line in the list (cosmetic; label field
  lets the user name it).
- On the emulator, a freshly adb-pushed `.epub` was initially shown disabled by DocumentsUI
  until MediaStore indexed it; after the media scan it imported normally. Real downloads go
  through the download manager and are indexed automatically. The picker tolerates generic
  MIME types; the parser is the validator.

---

## 16. Increment 11 — PDF annotation editor + flattened annotated export (2026-11-17)

Scope: phase 11 first slice (`docs/ROADMAP-ALL-IN-ONE.md`): an encrypted annotation layer over
PDFs plus a flattened annotated-copy export. Contracts: `docs/CONTRACTS-PHASE10.md`; UI details:
`docs/PDF-ANNOTATION-UI.md`; policy: DECISIONS #23.

### 16.1 Data layer: encrypted annotation layer (`ItemCodec` payload v5)

| Check | Result |
|---|---|
| Model | `PdfAnnotations` with ink/highlighter strokes, rectangle highlights and sticky notes; normalised 0..1 geometry, 6 palette indices, 3 width levels; bounds 2,000 strokes / 2..2,000 pts/stroke / 100k total points / 1,000 highlights / 500 notes / 4,000-byte note text / 4 MiB codec blob |
| Codec | `PdfAnnotationCodec` v1 `"NZPA"`, 1/10,000 quantisation, deterministic, null (never throws) on truncation/corruption/out-of-range/trailing bytes/oversize; 1,000-sample random fuzz test |
| Storage | `Item.pdfAnnotations` inside payload **v5** (presence byte + u32 length + codec blob after reading state); v1–v4 still decode (`pdfAnnotations = null`); repository-owned (`savePdfAnnotations` only, null/empty clears, `saveItem` preserves) |
| Tests | `PdfAnnotationCodecTest` 6; `PayloadCodecTest` v5 + v4 compatibility; `LockedPayloadCodecTest` boundary bytes for v5; `EncryptedNoteRepositoryTest.pdfAnnotationsArePersistedEncryptedAndRepositoryOwned` (lock/unlock, locked write rejected, no plaintext note text in Room ciphertext, clear-on-empty) and `backupRoundTripCarriesPdfAnnotations`; legacy v1 backup fixture still imports |

### 16.2 Annotation editor + flattened export (UI)

| Check | Result |
|---|---|
| Editor | annotate-mode toggle (scroll locked, Done resumes scrolling, Back exits mode first), palette ink/highlighter/highlight-box/sticky-note/eraser/undo/redo + 6 token-derived colours + 3 widths; per-page canvas overlay from normalised coordinates; note dialog create/edit/delete; 64-step undo/redo; per-mutation persistence (silent while locked) |
| Export | annotations present → export choice (original / annotated) → existing UNENCRYPTED warning → SAF `CreateDocument`; annotated path renders pages + layer in memory (`AndroidPdfFlattenedExporter`, A4 595×842, scaled-to-fit, note markers, null on OOM/errors, no temp file, 32-page defensive cap), re-checks the unlocked vault, writes bytes only after the check |
| Tests | PDF package **55 unit tests** (new: `PdfAnnotationScreenTest` 14, `PdfAnnotationEditorTest` 16, `PdfAnnotationPaletteTest` 8; existing viewer/inline tests still green); full unit suite **1,118 tests / 0 failures / 95 classes**; **19 instrumented tests OK** (15 previous + 4 new `PdfFlattenedExportInstrumentedTest`: real output accepted by `PdfRenderer` with matching page count, hostile/oversized fail-closed, model bounds, max-density export) |

### 16.3 Emulator device QA (measured)

| Step | Evidence |
|---|---|
| Setup | fresh debug install + vault unlock; imported the 3-page QA PDF (`qc-3page.pdf`, 4,231 B) through the Library SAF flow |
| Annotating | drew an ink stroke, switched colour and drew a highlighter stroke, created a highlight box, added a sticky note ("Remember to test the flattened export") with the note marker rendered; palette/selection/hint all visible; annotate mode locked scrolling as designed |
| Edits | undo removed the note marker (Redo became enabled), redo restored it; eraser drag removed the ink stroke while keeping other annotations |
| Persistence | force-stopped the app, re-launched, unlocked the vault, reopened the PDF: highlight box, highlighter stroke and sticky-note marker were restored from the encrypted `pdfAnnotations` layer (the erased ink stroke stayed erased) |
| Flattened export | export → annotated copy → UNENCRYPTED warning → SAF save: `qc-3page-annotated.pdf` **70,333 B**; `pdfinfo`: **Pages 3, 595×842 pt A4**; `pdftoppm` page 1 shows the original text plus the highlight box, highlighter stroke and note marker |
| Crashes/logs | 0 NoteZero FATAL/ANR in the session; no annotation content logged; exported QA file removed from emulator Downloads afterwards |
| Screenshots | `27-pdf-annotate-tools`, `28-pdf-annotations`, `29-pdf-export-choice`, `30-pdf-flattened-page` |

### 16.4 Security + release verification

| Check | Result |
|---|---|
| Offline guard | `./scripts/check-offline.sh --gradle` → `OK: NoteZero is offline by construction.` |
| Token lint | `ui/pdf/PdfAnnotationPalette.kt` token-clean (only a `0x00FFFFFF` RGB mask; colours derived from `NoteZeroPalette`) |
| Hygiene | no TODO/FIXME/Log/println in new annotation/data files; no network imports; no new permissions (release APK: `USE_BIOMETRIC` only + generated dynamic-receiver permission) |
| Reproducible APK | **REPRODUCIBLE** — both builds sha256 `150ba74269582e697db317213f85339d807a5645a6e070c2a9a52cc0ad4993bf` |
| Reproducible AAB | **REPRODUCIBLE** — both builds sha256 `4b160b50fde8633fbb46800ff201cd8bf5e9b6fd46fcd425b320405b26b8ed9b` |
| Artifacts | `releases/notezero-0.1.0-release.apk` / `.aab` replaced; `releases/SHA256SUMS` updated |
| Certificate | `CN=Cipherwell, O=Cipherwell, OU=NoteZero, C=US`, SHA-256 `FA:CA:DD:67:…:92:78` (unchanged) |
| Owner phone | release APK installed over the existing vault (data/biometrics intact, `firstInstallTime` unchanged); phone was at the lockscreen so no UI interaction was possible this pass; 0 crash entries for NoteZero; left locked |

### 16.5 Residual notes

- Flattened export is a **rasterised** copy: text is not selectable/searchable, interactive
  links/forms are gone, only rendered pages are included (viewer cap ≤8; exporter hard-fails
  above 32) and sticky notes flatten as markers — their text remains encrypted in the vault.
  The original decrypted-copy export is still the full untouched file.
- Page operations (rotate/delete/insert/reorder) and PDF text extraction/search are still queued
  for phase 11; they require either a PDF-writing step with fidelity trade-offs or a vetted text
  engine, so they were not rushed into this increment.
- Annotation zoom/pan: the viewer has no pinch-zoom yet, so annotation coordinates are normalised
  to the rendered page; when zoom lands the same model renders correctly (normalised geometry).
- Undo/redo and the in-flight gesture are in-memory only; at most the last gesture is lost on
  process death (persisted state is what was committed per mutation).

---

## 17. Increment 12 — drawing studio: encrypted standalone sketches (2026-11-17)

Scope: phase 12 first slice (`docs/ROADMAP-ALL-IN-ONE.md`): standalone sketch documents,
encrypted vector persistence + in-memory encrypted preview, and a real drawing toolset.
Contracts: `docs/CONTRACTS-PHASE11.md`; UI details: `docs/DRAWING-STUDIO.md`; policy:
DECISIONS #24.

### 17.1 Data layer: encrypted sketch documents (`ItemCodec` payload v6)

| Check | Result |
|---|---|
| Model | `SketchDocument` (template blank/ruled/grid/dotted; strokes with normalised points/7 inks/3 widths/pen-highlighter kind; line/rect/ellipse/arrow shapes; A4-portrait ratio); bounds ≤2,000 strokes / 2..2,000 pts/stroke / ≤100,000 total points / ≤500 shapes, constructor throws outside bounds |
| Codec | `SketchCodec` v1 `"NZSK"`, 1/10,000 quantisation, deterministic, ≤4 MiB, null (never throws) on truncation/corruption/out-of-range/trailing; 1,000-sample fuzz test; dense-cap round-trip |
| Storage | `ItemKind.SKETCH_FILE` appended (ordinal 4); `Item.sketch` inside payload **v6** (presence byte + u32 length + codec after annotations); v1–v5 still decode (`sketch = null`); repository-owned (`saveSketch` only, null/empty clears, `saveItem` preserves), locked vault cannot read/write, backup v2 carries it |
| Tests | `SketchCodecTest` 6; payload v6 round-trip + v5/v4 compatibility; locked-boundary byte offsets updated for v6; `EncryptedNoteRepositoryTest.sketchIsPersistedEncryptedAndRepositoryOwned` + `backupRoundTripCarriesSketchDocument`; legacy v1 backup fixture still imports (v1 suffix now 6 bytes) |

### 17.2 Drawing studio + Library integration

| Check | Result |
|---|---|
| Studio | A4 page with blank/ruled/grid/dotted paper, pen/highlighter/eraser/lasso/shape (line/rect/ellipse/arrow), 7 token-derived inks, 3 widths, undo/redo (≥50), clear-with-confirm, bounded pinch zoom/pan + fit/zoom buttons, rename, empty-state hint; Back flushes pending save |
| Persistence | vector autosaved after each committed mutation; on exit a flattened PNG is rendered in memory and imported as an encrypted image attachment (previous preview deleted via `deleteAttachment`); no plaintext file/cache; preview failures never affect the vector save |
| Library | nullable `LibraryEntry.attachment` so preview-less sketches appear; "New drawing" action creates+saves a default-titled sketch and opens the studio (failed create trashes the placeholder); tap sketch opens studio; rename/lock/trash rules unchanged; note-owned drawings still listed |
| Tests | `SketchEditorStateTest` 19, `SketchRendererTest` 8, `SketchStudioScreenTest` 17, Library 17; full unit suite **1,177 tests / 0 failures / 99 classes**; **23 instrumented tests OK** (+4 `SketchRendererInstrumentedTest`: real in-memory PNG decode/bounds, hostile/oversized fail-closed) |

### 17.3 Emulator device QA (measured)

| Step | Evidence |
|---|---|
| Creation | Library → + → "New drawing": studio opened on a blank A4 page with the full tool/colour/template palette and empty-state hint, title "Drawing 1" |
| Drawing | pen stroke drawn; colour switched to green; shape menu → Rectangle drawn; paper switched to Grid; strokes/shapes/template rendered correctly |
| Rename | top-bar rename dialog updated the encrypted item title (verified in the studio and Library row) |
| Persistence | left the studio (Back flush) → Library listed the sketch under Drawings → reopened it: grid template, black stroke and green rectangle restored exactly from `Item.sketch` |
| Encryption | app-private attachments: preview blob `4679eb0b…nzb` 34,009 B starts with `NZAT` (encrypted envelope), not a PNG signature; no `.png/.jpg/.webp/.pdf/.epub` files anywhere under `files/` or `cache/` |
| Crashes | 0 NoteZero FATAL/ANR during the session |
| Screenshots | `31-drawing-studio`, `32-sketch-drawn`, `33-sketch-library` |

### 17.4 Security + release verification

| Check | Result |
|---|---|
| Offline guard | `./scripts/check-offline.sh --gradle` → `OK: NoteZero is offline by construction.` |
| Token lint | `ui/sketch` 7 Kotlin files token-clean; no raw hex outside the token-derived palette |
| Hygiene | no TEMP DEBUG/TODO/FIXME/Log/println/sketchdbg leftovers in `ui/sketch`/Library; no network imports; no new dependencies/permissions (release APK: `USE_BIOMETRIC` only + generated dynamic-receiver permission) |
| Reproducible APK | **REPRODUCIBLE** — both builds sha256 `16e2753b7180c1483d6a72d1413fa2b551c082dda4786b186e08b36bbce481de` |
| Reproducible AAB | **REPRODUCIBLE** — both builds sha256 `36a2ae5e9d40c6a86fca24b6e0d4bd89431615964ee19fba8b785d3115f101fe` |
| Artifacts | `releases/notezero-0.1.0-release.apk` / `.aab` replaced; `releases/SHA256SUMS` updated |
| Certificate | `CN=Cipherwell, O=Cipherwell, OU=NoteZero, C=US`, SHA-256 `FA:CA:DD:67:…:92:78` (unchanged) |
| Owner phone | release APK installed over the existing vault (data/biometrics intact, `firstInstallTime` unchanged); phone at lockscreen so no UI interaction this pass; 0 crash entries; left locked |

### 17.5 Residual notes

- Drawing studio v1 has no layers, pressure/tilt curves, brush textures, per-element selection/move,
  sketch export, or shape editing after commit. The lasso deletes enclosed elements (no move);
  shapes are commutative/undoable snapshots. These are phase-12 follow-ups.
- The preview PNG is refreshed on studio exit (not live); the Library row still shows the type
  label rather than a thumbnail (consistent with existing PDF/book rows).
- Note-owned drawings created from the note editor remain flattened encrypted PNG attachments by
  design; only `SKETCH_FILE` items are editable vector sketches. A shared stroke surface between
  notes/PDF annotations and the studio is a later roadmap item.
- Test-harness gotcha (not product code): a Robolectric `@Config` **display qualifier** combined
  with an AlertDialog text field caused an infinite-idle `ShadowLineBreaker` OOM; removing the
  qualifiers fixed it (the project rule is `@Config(sdk=[34])` only for dialog tests). One
  temporary debug SideEffect was removed before the final runs.
- Rename automation via `adb input text` typed only the first token; the feature itself was
  verified by the title update and unit tests, not by the full typed string.

---

## 18. Increment 13 — universal Workspace / IDE layer (2026-11-17)

Scope: phase 13 first slice (`docs/ROADMAP-ALL-IN-ONE.md`): one file-manager/IDE browser over the
encrypted item index, unified offline search, multi-select bulk operations and a storage summary.
Contract: `docs/CONTRACTS-PHASE12.md`; UI details: `docs/WORKSPACE.md`; policy: DECISIONS #25.

### 18.1 Workspace (Files) implementation

| Check | Result |
|---|---|
| Browser | `WorkspaceScreen` reachable from the Notes top bar (files icon); title "Files"; type chips All/Notes/Code/PDFs/Books/Drawings; sort (last updated/name); search box; footer `N items · M attachments · human size` computed from the visible decrypted index |
| Rows | `workspace_item` rows with type label, title, folder name or "No folder", metadata/attachment hint, lock glyph for verified-locked items; fail-closed visibility: masked locked items and items in hidden locked folders excluded from rows, filters, counts and search |
| Openers | NOTE/CODE_FILE deep-link into the editors hosted by the notes shell (`AppRoot` pending item → `NotesListScreen.requestedItemId`); PDF/BOOK/SKETCH open the existing viewer/reader/studio hosted inside the Workspace; verified-locked items re-authenticate through the standard controller |
| Search | Case-insensitive offline search over title/body/tags/attachment display name/book author; body/code matches show a snippet; masked content never contributes; empty/no-match states |
| Bulk | Long-press selection + Select all/Clear; Move (existing `MoveToFolderDialog`), Lock/Unlock (unlock re-auth) and Move to trash (confirmation dialog); only visible selected ids; repository failures show non-secret `workspace_bulk_failed` and clear selection; plural trash title ("1 item"/"N items") |
| Rules | No new data model, payload, permission, dependency or storage surface — a view over the already-decrypted index; no content logging; strings resources; colours from tokens; no debug leftovers |
| Tests | `WorkspaceQueryTest` 16 JVM (visibility/search/sort/summary/selection/folder flattening), `WorkspaceScreenTest` 25 Robolectric (rows/filters/search/sort/summary, masked+hidden exclusion, long-press + select-all, bulk move/lock/trash + confirmation + failures, note/code callbacks, hosted PDF/book/sketch routing seams, re-auth) plus an `AppRootTest` shell-routing test; full suite **1,219 tests / 0 failures / 101 classes**; **23 instrumented tests OK** |

### 18.2 Emulator device QA (measured)

| Step | Evidence |
|---|---|
| Entry | Notes top bar files icon opened Workspace; the locked ("Locked note") item was absent (fail-closed) while 4 visible items rendered with type labels and `4 items · 1 attachments · 431 B` |
| Search | search "E2E" returned the code file `hello.kt` with a matching body snippet and the note `E2E persisted secret title` |
| Selection | long-press row → selection mode with "1 selected", Select all / Clear selection / Move / Lock / Move to trash actions and per-row checkboxes |
| Bulk trash | "Move to trash" opened the confirmation ("Move 1 item to trash?" after the plural fix), confirming removed the row and updated the summary to `3 items · 1 attachments · 431 B` |
| Open note | tapping the note row deep-linked into the note editor with the item loaded (title/body shown), proving workspace → notes-shell navigation |
| Crashes | 0 NoteZero FATAL/ANR during the session |
| Screenshots | `34-workspace`, `35-workspace-selection`, `36-workspace-search`, `37-workspace-trash` |

### 18.3 Security + release verification

| Check | Result |
|---|---|
| Offline guard | `./scripts/check-offline.sh --gradle` → `OK: NoteZero is offline by construction.` |
| Token lint | `ui/workspace` Kotlin files token-clean (no raw colours/hex) |
| Hygiene | no TODO/FIXME/TEMP DEBUG/Log/println in `ui/workspace` or the new AppRoot/Notes wiring; no network imports; no new permissions (release APK: `USE_BIOMETRIC` only + generated dynamic-receiver permission) |
| Reproducible APK | **REPRODUCIBLE** — both builds sha256 `680ea345eae5dfc844b1219856a93d9c1189fc8b73db8d1a1c7772c23d8c6f20` |
| Reproducible AAB | **REPRODUCIBLE** — both builds sha256 `3df3c1d5e8335001343d090f906cc134eb398ba93656a42279fb12015e25223d` |
| Artifacts | `releases/notezero-0.1.0-release.apk` / `.aab` replaced; `releases/SHA256SUMS` updated |
| Certificate | `CN=Cipherwell, O=Cipherwell, OU=NoteZero, C=US`, SHA-256 `FA:CA:DD:67:…:92:78` (unchanged) |
| Owner phone | release APK installed over the existing vault (data/biometrics intact); phone at lockscreen so no UI interaction this pass; 0 NoteZero crash entries; left locked |

### 18.4 Residual notes

- Workspace v1 excludes locked+unverified items entirely (fail-closed) rather than showing masked
  rows, unlike the Notes list; unlocking is done from Notes. This is documented in WORKSPACE.md.
- Search is index-level only: no PDF/EPUB *content* text extraction yet (phase 14 needs a vetted
  text engine), and no fuzzy/prefix ranking.
- No multi-tab editors, split Markdown preview, symbol search, templates, multi-select export or
  disk-usage view yet (phase-13 follow-ups). Storage summary reports attachment-ref sizes, not
  filesystem usage.
- Bulk operations don't have a dedicated undo; trash items are recoverable from Notes Trash.
- Selection entry is long-press (TalkBack exposes long-click); there is no visible "select" mode
  button per row by design (space/strings constraint).

---

## 19. Increment 14 — more reading formats: TXT, FB2, CBZ comics (2026-11-18)

Scope: phase 14 first slice (`docs/ROADMAP-ALL-IN-ONE.md`): content-detected TXT/FictionBook
FB2/CBZ comics sharing the e-book reader surface. Contract: `docs/CONTRACTS-PHASE13.md`;
engine details: `docs/BOOK-FORMATS.md`; UI details: `docs/READER-UI.md` (formats section);
policy: DECISIONS #26.

### 19.1 Format engine (pure JVM, no new dependencies)

| Check | Result |
|---|---|
| Abstraction | `BookContent` implemented by `EpubDocument`; `BookFormat`/`BookDetector`/`BookLoader`; `LoadedBook.Reflowable`/`LoadedBook.Comic`; `ComicBook` |
| Detection | bytes-only, order EPUB (mimetype/container) → CBZ (ZIP + image entries, no EPUB marker) → FB2 (secure XML root, namespace tolerant) → TXT (BOM/UTF-8/UTF-16 decodable, control-spam rejected); unsupported/ambiguous → null |
| TXT | BOM-aware UTF-8/UTF-16LE/BE; no-BOM UTF-8 with windows-1252 fallback; blank-line paragraphs (single newlines joined); heading-aware chapters (chapter/part/book/... ≤120 chars, blank-line separated) else "Part N" every ~20k chars; caps 2,000 chapters / 20,000 blocks / 2M chars per chapter + whole-book caps |
| FB2 | secure SAX (no DTD/external entities; UTF-16 refused), metadata title/author/lang, body sections→chapters + TOC (nested depth), p/emphasis/strong/code/a/poem/stanza/v/epigraph/cite/empty-line/subtitle mapping, lazy base64 `<binary>` images (≤4 MiB each, ≤64) and cover; malformed/XXE → null |
| CBZ | natural-sorted jpg/jpeg/png/webp/gif pages, ≤500 pages, ≤16 MiB/page, lazy page byte copies, idempotent close, title = fallback; corrupt/missing → null/placeholder |
| Safety | bounded parsers, no temp files/caches/logging, never throw on hostile input, 1,000-sample fuzz; no `android.*` in `book/` |
| Tests | **211 book-package tests** (104 existing EPUB/book unchanged + 107 new: BookDetector 18, Txt 27, Fb2 35, Cbz 15, BookLoader 12) |

### 19.2 Library + reader UI

| Check | Result |
|---|---|
| Import | format-generic importer: detects format, stores detected MIME, derives title/author (fallback cleaned file name then localised "Untitled book"), encrypted `ReadingState` metadata, thumbnail-less rows show `EPUB`/`FB2`/`TXT`/`Comic` labels; unsupported/corrupt/DRM → placeholder removed + non-secret `library_import_unsupported`; >10 MiB → `library_import_failed` |
| Reflowable reader | EPUB/FB2/TXT share the existing reader over `BookContent`: TOC, in-book search, bookmarks, font/line-spacing/margins, paper/sepia/night, progress/resume; format+author in the metadata line |
| Comic reader | new `ComicReaderScreen`: lazy `BitmapFactory` page decoding in memory (bounded sample, recycled), page images + progress bar + page indicator, page-jump dialog with validation, bookmarks (add/list/delete/jump) persisted through `ReadingState`, resume, loading/page-error/comic-error states; no text search/TOC; comic resource closed on dispose |
| Tests | +25 UI tests: `BookReaderScreenTest` 23 (7 new TXT/FB2/corrupt cases), `ComicReaderScreenTest` 11, `LibraryBookImportTest` 7; full suite **1,351 tests / 0 failures / 108 classes**; **23 instrumented tests OK** |

### 19.3 Emulator device QA (measured)

| Step | Evidence |
|---|---|
| TXT import | SAF → `qa-book.txt` imported as `TXT`; row `qa-book`, Chapter 1 of 1; reader rendered the text, search "txt-words" → **1 result** with snippet; progress/tools present |
| FB2 import | `qa-book.fb2` imported with metadata title **The FB2 Test Book**, author **Ada Fiction**, label **FB2**, Chapter 1 of 3 / 33%; reader rendered book title, both sections, italic/bold/code spans, poem lines (quote styling), epigraph and subtitle; TOC showed **3 entries** (book title + both sections) |
| CBZ import | `qa-comic.cbz` imported as **Comic** (`qa-comic`), pages decoded in the comic reader; page indicator `1 / 3`; bookmark added on page 1, bookmark list persisted **after force-stop + unlock**, page jump dialog opened/validated |
| Unsupported | `qa-unknown.bin` → non-secret "This file is not a supported, DRM-free document." and **no library row** (placeholder cleaned) |
| Crashes | 0 NoteZero FATAL/ANR during the session |
| Screenshots | `38-txt-reader`, `39-fb2-reader`, `40-comic-reader`, `41-library-formats`, `42-comic-bookmarks` |

### 19.4 Security + release verification

| Check | Result |
|---|---|
| Offline guard | `./scripts/check-offline.sh` → `OK: NoteZero is offline by construction.` (new parsers contain no network APIs/URL literals) |
| Token lint | `ui/comic` token-clean; no raw colours in new UI |
| Hygiene | no TODO/FIXME/TEMP DEBUG/Log/println in `book/`/`ui/comic`/`ui/reader`; no debug test files; no new permissions/dependencies (release APK: `USE_BIOMETRIC` only + generated dynamic-receiver permission) |
| Reproducible APK | **REPRODUCIBLE** — both builds sha256 `772192dd4393419fa12291d25037733b0912404742034efa8f74af75aed5fd8a` |
| Reproducible AAB | **REPRODUCIBLE** — both builds sha256 `eb5aeadbac83f996711f6a96f71a04765eea49e10fe5701e022f0fa06e1886cb` |
| Artifacts | `releases/notezero-0.1.0-release.apk` / `.aab` replaced; `releases/SHA256SUMS` updated |
| Certificate | `CN=Cipherwell, O=Cipherwell, OU=NoteZero, C=US`, SHA-256 `FA:CA:DD:67:…:92:78` (unchanged) |
| Owner phone | release APK installed over the existing vault (data/biometrics intact); phone at lockscreen so no UI interaction; 0 NoteZero crash entries; left locked |

### 19.5 Residual notes

- The TXT fixture's headings were not blank-line separated, so they rendered as prose in one
  "Part 1" chapter (documented parser rule); heading detection requires blank-line-separated
  single-line headings ≤120 chars.
- Comic progress/bookmarks are page-based; resume uses a 500 ms debounce, so a force-stop
  immediately after a jump can keep the previous saved page (bookmarks persist immediately).
- The comic page-jump indicator is first-visible-page based; jumping to the last page clamps to
  the maximum scroll offset (the page is still visible), like the PDF viewer.
- Deferred: MOBI/AZW3/KFX (own parser + DRM caveat), comic metadata (`ComicInfo.xml`), TTS,
  hyphenation, per-book appearance overrides, reading statistics, translations, accessibility
  pass and the F-Droid window (opens 2027-02-15).

---

## 20. Increment 15 — IDE-style code workspace: tabs, quick open, templates, split preview (2026-11-18)

Scope: phase-13 follow-ups (`docs/ROADMAP-ALL-IN-ONE.md`) for the code workspace. Contract:
`docs/CONTRACTS-PHASE14.md`; UI details: `docs/CODE-WORKSPACE.md`. No data-model, payload,
permission or dependency changes.

### 20.1 Implementation

| Check | Result |
|---|---|
| Tabs | up to 8 open files (saveable list + active id) with `code_tabs` strip (`code_tab`, `code_tab_close`); switching preserves each file's draft/undo/find/preview state (per-item keyed ViewModels kept alive in a `SaveableStateHolder`); close activates the neighbour (or tree); Back returns to the tree keeping tabs; re-opening a tabbed file activates it; cap shows `code_tab_limit` |
| Quick open | `code_quick_open` dialog with `quick_open_query`/`quick_open_result`, code files only (locked/hidden folders excluded), case-insensitive title/path/extension filter, newest-updated then title, empty `code_quick_open_empty`; opens/activates a tab |
| Templates | `NewCodeFileDialog` template chips (`code_template_blank/kotlin/python/markdown/html/json`) swap suggested name/extension/starter body and return `NewCodeFileRequest(name, body)`; workspace verifies body is persisted; notes-shell create path wired (`NotesViewModel.createCodeFile(name, folderId, body)` + `onConfirmRequest`) |
| Split preview | on wide layouts (≥600dp) the Preview toggle exposes `Split` (`code_split_preview`) rendering `MarkdownPreview` beside the editor; compact keeps the existing single-pane preview; preview/checklist tags unchanged |
| Regression fix | line-number gutter draw crashed with `maxHeight(-46) must be >= minHeight(0)` when the editor was first drawn at zero height (tab creation/rotation); fix draws pre-measured text layouts and returns early for empty frames |

### 20.2 Tests + measured QA

| Check | Result |
|---|---|
| Unit suite | **1,397 tests / 0 failures / 113 classes** (+46 code-IDE tests: tabs 10, quick open 7, templates 5, workspace-tabs screen 6, quick-open screen 7, dialog 9 + extended editor/VM cases); previously 1,351/108 |
| Instrumented | **23 tests OK** on the emulator (unchanged E2E, new tabs/quick-open kept existing tags) |
| Emulator QA | unlocked → code workspace; opened `hello.kt`; quick-open dialog listed `untitled.md`/`hello.kt` and opened the Markdown file as a **second tab**; typed a draft, switched to `hello.kt` and back — draft preserved; toggled Preview (single-pane compact), rotated to landscape and enabled **Split** (editor + rendered preview side by side, two tabs visible) |
| Crash found/fixed | opening the editor via quick-open crashed reproducibly in `CodeEditorField.drawLineNumbers` (negative draw constraints); after the fix the same flow and subsequent tab switches ran without FATAL/ANR |
| Offline guard | `./scripts/check-offline.sh --gradle` → `OK: NoteZero is offline by construction.` |
| Hygiene | no TODO/FIXME/TEMP DEBUG/Log/println in `ui/code`; no debug test files; no new permissions/deps (release: `USE_BIOMETRIC` only) |
| Reproducible APK | **REPRODUCIBLE** — both builds sha256 `2d5932bcfdc21562e4da4e6813514956d2cfb6df4744a1641975da66cae40966` |
| Reproducible AAB | **REPRODUCIBLE** — both builds sha256 `568b341a47d9a5f93673b78e34abc5f62a9c8be8d7a318f8677568ade7b2af2b` |
| Artifacts | `releases/notezero-0.1.0-release.apk` / `.aab` replaced; `releases/SHA256SUMS` updated |
| Certificate | `FA:CA:DD:67:…:92:78` unchanged |
| Owner phone | release installed over existing vault (data/biometrics intact), 0 NoteZero crash entries, left locked |
| Screenshots | `43-code-tabs`, `44-code-quick-open`, `45-code-split-preview` |

### 20.3 Residual notes

- Tabs are an in-memory session view: they reset on vault lock/process death by design (no
  plaintext file names persisted); drafts are autosaved through the normal encrypted editor path.
- Quick open is title/path filtering only (no fuzzy ranking/symbol search) and covers code files
  only; workspace-wide content search remains the existing code-search surface.
- Templates are static built-in snippets (no user-defined templates yet); a template mismatched
  with a chosen extension falls back to a blank body.
- Split preview requires ≥600dp; rotation reflows but the split state is per-tab ephemeral.
- A transient status-bar/tab-strip overlap was observed immediately after an adb-driven rotation;
  it settles on the next frame (final screenshots show correct insets) and was not reproducible
  in normal use.

---

## 21. Increment 16 — PDF page tools: rotate/delete/reorder/insert blank → flattened encrypted copy (2026-11-20)

Scope: `docs/ROADMAP-ALL-IN-ONE.md` Phase 11 page tools. Contract: `docs/CONTRACTS-PHASE15.md`;
implementation/limits: `docs/PDF-PAGE-TOOLS.md`; policy: DECISIONS #27; threat note:
THREAT-MODEL §3.9.7.

### 21.1 Implementation

| Check | Result |
|---|---|
| Page plan | pure `PdfPagePlan`: source/blank entries + clockwise quarter-turns; rotate left/right, move up/down, delete (last page refused), insert blank, reset; 100-page cap; duplicate/out-of-range/hostile positions safe; `isTooLarge` invalid plan for >100 source pages |
| Export | `PdfPageToolsExporter` seam + `AndroidPdfPageToolsExporter`: on-demand `PdfRenderer` per planned page, scaled-to-fit/centred white A4, quarter-turn applied (swapped fit extents), annotations flattened through the shared `PdfPageDrawing` helper (rotate/move with page; deleted pages lose annotations; blanks have none); one ephemeral app-private cache file deleted in `finally`; ≤1600 px/page, ~48 MiB total pixel budget, ≤100 pages; null on any failure, no logging |
| Save | `PdfPageCopySaver` + default: new encrypted `PDF_FILE` item in source folder titled `<base> (pages).pdf`; `importAttachment` (NZAT/AES-256-GCM) only; blank/locked/empty refused; half-created row trashed on failure; original untouched |
| UI | top-bar `pdf_page_tools` action opens full-screen overlay: warning/hint, page rows (48dp rotate/move/delete), insert blank/reset/cancel/save, position/source/rotation labels, dirty/last-page/too-many notices, saving/locked/failure states; Back closes overlay only; save re-reads bytes and re-checks unlocked/item-alive |
| Tests | +54 PDF unit tests (plan 23, screen 20, saver 8, viewer 3) and 4 instrumented real-`PdfRenderer` tests; full suite **1451 / 0 failures / 116 classes**; **27 instrumented tests OK** |

### 21.2 Emulator device QA (measured)

Fixture: 3-page PDF (ALPHA/BETA/GAMMA) imported through Library → PDF file picker.

| Step | Evidence |
|---|---|
| Open page tools | PDF viewer top bar showed **Page tools**; overlay listed 3 pages with rotation 0° and From page 1–3, warning visible |
| Edit plan | rotated page 1 right (Rotation 90°), moved page 3 to the top (rows reordered to From page 3/1/2), inserted a blank page (Page 4 = Blank page), deleted page 2 (From page 2) → final plan From page 3, From page 1 (90°), Blank |
| Save copy | **Save as new PDF** produced the notice `Saved a new copy: qa-pdf-pages (pages).pdf`; no plaintext SAF destination involved; original viewer stayed open |
| Reopen copy | Library listed `qa-pdf-pages (pages).pdf` (71.0 KB); opening it showed 3 pages: **PAGE GAMMA**, then **PAGE ALPHA rotated 90°**, then a white blank page — matching the edited plan |
| Original | Original `qa-pdf-pages` remained 3 pages with unchanged reading state; it was never mutated |
| Crashes | 0 NoteZero FATAL/ANR during the flow |
| Screenshots | `46-pdf-page-tools`, `47-pdf-page-tools-copy`, `48-pdf-viewer-page-tools-action` |

### 21.3 Security + release verification

| Check | Result |
|---|---|
| Instrumented export | `PdfPageToolsExportInstrumentedTest` 4/4 on real `PdfRenderer`/`PdfDocument`: reorder/rotation/blank/deleted pages, annotation rotation behaviour, corrupt/oversized/out-of-range/empty plans return null |
| Offline guard | `./scripts/check-offline.sh --gradle` → `OK: NoteZero is offline by construction.` |
| Token lint | PDF page-tools code token-clean |
| Hygiene | no TODO/FIXME/TEMP DEBUG/logging in the page-tools code; no new permissions/dependencies |
| Reproducible APK | **REPRODUCIBLE** — sha256 `55e3f4da9ce288b0f6a287191010e100099ddf57bb42525ee076ac1da0a2edef` |
| Reproducible AAB | **REPRODUCIBLE** — sha256 `6c185d2ae04466b34a5ed47e9ddfa1feb2b3ee1d4a5417404fe113e5ecc63c2e` |
| Artifacts | `releases/notezero-0.1.0-release.apk` / `.aab` replaced; `releases/SHA256SUMS` updated |
| Certificate | Cipherwell signing certificate unchanged |
| Owner phone | not connected during this run (`adb devices` showed only the emulator); install skipped — release artifact verified on the emulator/debug QA path |

### 21.4 Residual notes

- The copy is **image-only** by design: text, links, form fields, selectable text and accessibility
  semantics of the original are not preserved; this is stated in the overlay warning and is the
  same honest boundary as the annotated export.
- `MAX_PAGES = 100`; above that the overlay shows the "too many pages" error and never truncates.
  Long copies use a lower per-page resolution (bounded total pixel budget) to avoid OOM.
- Page-tools output is a new item; there is no "replace original in place" action (deliberate, so
  the original is always recoverable). Annotations of deleted pages are not in the copy.
- The viewer top bar now has five 48 dp actions; on narrow screens the title ellipsizes earlier
  (observed on the 411 dp emulator). All controls remain reachable and ≥48 dp.
- Actionable page thumbnails, PDF text extraction/search and sticky-note text in flattened copies
  remain queued.

---

## 22. Increment 17 — PDF visual page navigation: in-memory page thumbnails (2026-11-20)

Scope: page-thumbnail navigation for PDFs (Pages overview, bookmark rows, page-tools previews).
Contract: `docs/CONTRACTS-PHASE16.md`; implementation/limits: `docs/PDF-THUMBNAILS.md`;
policy: DECISIONS #28; threat note: THREAT-MODEL §3.9.8.

### 22.1 Implementation

| Check | Result |
|---|---|
| Thumbnail set | `PdfThumbnails`: caller-owned bounded bitmap list, `bitmap(page)`/`size`/`isEmpty`/idempotent `recycle`; no file/db/cache |
| Renderer | `PdfThumbnailRenderer` + `AndroidPdfThumbnailRenderer`: ≤100 pages, ≤144 px long edge, white ARGB, never upscaled; one ephemeral app-private `PdfRenderer` cache file deleted in `finally`; corrupt/empty/OOM → empty set; cancellation rethrown; no logging |
| Lazy viewer load | thumbnails requested only on first Pages/bookmarks/page-tools open; re-reads `attachmentBytes` at that moment and holds no PDF bytes in state; `PdfThumbnails` recycled on replace/dispose |
| Pages overview | existing `pdf_page_jump_dialog` now titled Pages with hint, bounded 3-column grid (`pdf_page_overview_grid`, `pdf_page_thumb_<i>`, current-page emphasis, tap to jump+close), loading/unavailable states; numeric field/error/Go/Cancel kept as fallback |
| Bookmarks | 48×64 dp page thumbnail in each `pdf_bookmark_row` when available (`pdf_bookmark_thumbnail`), text-only while loading/failed |
| Page tools | optional `thumbnails` param; 56×72 dp preview per row (`pdf_page_thumb`, contentDescription `cd_pdf_page_tools_thumbnail`), source preview rotated by quarter-turns, labelled blank placeholder |
| Tests | +16 unit/Robolectric (thumbnail set 4, UI 7, viewer +2, page-tools +3) and +5 instrumented real-`PdfRenderer` thumbnail tests; full suite **1467 / 0 failures / 118 classes**; **32 instrumented tests OK** |

### 22.2 Emulator device QA (measured)

Fixture: 12-page PDF imported through Library → PDF file picker (E2E vault).

| Step | Evidence |
|---|---|
| Pages overview | tapping the viewer page counter opened **Pages** with a 3-column thumbnail grid; current page had a blue emphasis border/page number labels; grid showed the viewer-rasterised pages 1–8 plus the numeric field/Go/Cancel fallback |
| Thumbnail jump | tapping the page-5 thumbnail closed the dialog and moved the viewer to **5 / 12** |
| Bookmark thumbnail | added a bookmark on page 5; the Bookmarks panel showed a **thumbnail of page 5** next to the label/position |
| Page-tools previews | opened Page tools for the 12-page PDF; each row showed its source-page preview, page 2 rotated to **Rotation: 90°** showed the preview sideways; 12 pages available for page operations |
| Crashes | 0 NoteZero FATAL/ANR during the flow |
| Screenshots | `49-pdf-page-overview`, `50-pdf-bookmark-thumbnail`, `51-pdf-page-tools-thumbnails` |

Instrumented validation: real `AndroidPdfThumbnailRenderer` 5/5 (3-page count/dimensions/colour,
blank page white, corrupt/empty fail-closed, max-page/long-edge no-upscale, temp-file cleanup).

### 22.3 Security + release verification

| Check | Result |
|---|---|
| Offline guard | `./scripts/check-offline.sh --gradle` → `OK: NoteZero is offline by construction.` |
| Token lint | PDF thumbnail/viewer code token-clean |
| Hygiene | no TODO/FIXME/TEMP DEBUG/logging in the new thumbnail code; no new permissions/dependencies; no plaintext thumbnail file/cache (only the existing ephemeral `PdfRenderer` file, deleted before use) |
| Reproducible APK | **REPRODUCIBLE** — sha256 `749e86dab63e11f4f0bdbdbec6236fe6a9844e17e1df3b71a060ca6b001f43c1` |
| Reproducible AAB | **REPRODUCIBLE** — sha256 `cb9ce8927ead02d3090ae1757d38407d2b018b017eef9e4473740e480a8a936d` |
| Artifacts | `releases/notezero-0.1.0-release.apk` / `.aab` replaced; `releases/SHA256SUMS` updated |
| Certificate | Cipherwell signing certificate unchanged |
| Owner phone | release APK installed over the existing vault; phone left locked; 0 NoteZero crash entries |

### 22.4 Residual notes

- **Existing 8-page viewer render cap:** the Pages overview deliberately shows only pages the
  viewer has rasterised so every thumbnail is a real jump target; the numeric field retains the
  document's full 1..N range, and page tools still plan/export all pages up to 100. A full-document
  lazy-render viewer is queued (not faked in the overview).
- Thumbnails are rendered once per viewer session on demand; opening another thumbnail surface
  reuses the set, and the set is recycled on leaving/replacing the viewer. No thumbnail cache
  survives a lock/reopen.
- The page-tools preview for an unavailable thumbnail is a light placeholder; blank entries show a
  labelled placeholder. The page-tools exporter itself is unchanged.
- No new top-bar action was added (the bar already has five 48 dp actions).

---

## 23. Increment 18 — full-document lazy PDF viewer (2026-11-20)

Scope: remove the viewer's 8-page raster cap. Contract: `docs/CONTRACTS-PHASE17.md`;
implementation/limits: `docs/PDF-LAZY-VIEWER.md`; policy: DECISIONS #29; threat note:
THREAT-MODEL §3.9.9.

### 23.1 Implementation

| Check | Result |
|---|---|
| Page session | `PdfPageSession`/`PdfPageSessionFactory` + `AndroidPdfPageSessionFactory`: one open `PdfRenderer`; decrypted bytes written to an app-private cache file and **unlinked immediately after opening** (fallback delete-on-close); arbitrary page render on IO under a `Mutex`, white ARGB, ≤1600 px long edge, never upscaled; invalid/corrupt/OOM → null; idempotent close; `MAX_VIEW_PAGES = 2000` |
| Viewer | `LazyColumn` over the document's real `pageCount`; each page row renders lazily only while composed with A4 `pdf_page_loading` placeholder and `pdf_page_render_failed` per-page failure; page bitmap recycled when the row leaves composition; full-range progress, page jump, bookmarks, annotations; session closed on dispose/item change; first-8-pages cap/`pdf_pages_capped` gone |
| Thumbnails | Pages overview grid covers all thumbnail-rendered pages (up to 100) instead of the old rasterised prefix; bookmark/page-tools thumbnails unchanged |
| Annotated export | uses the injected all-pages `pageToolsExporter` with `PdfPagePlan.of(pageCount)` (identity plan), re-reads bytes at export time, keeps SAF warning/unlock checks; >100 pages shows `pdf_annotated_export_too_many` and creates/writes nothing |
| Legacy | `PdfPageRenderer`/`PdfPages`/`PdfFlattenedExporter` retained as standalone utilities with their tests; no viewer call site uses them |
| Tests | +23 unit/Robolectric lazy-viewer/session tests and +6 instrumented real-`PdfRenderer` session tests; full suite **1490 / 0 failures / 120 classes**; **38 instrumented tests OK** |

### 23.2 Emulator device QA (measured)

Fixture: 12-page PDF (PAGE 01 … PAGE 12) imported through Library.

| Step | Evidence |
|---|---|
| Full-document scroll | opened the 12-page PDF and scrolled from page 1 to **page 12/12**; pages 9–12 rendered lazily with no capped notice; session stayed responsive |
| Page jump | Pages overview now covered all 12 thumbnails (scrolled grid showed 10/11/12); jumped to **page 10/12** |
| Annotate page 10 | entered annotate mode on page 10, drew an ink stroke, tapped Done; annotation state persisted |
| Annotated export | share → Annotated copy (flattened) → explicit UNENCRYPTED warning → SAF `qa-pdf-12-annotated.pdf`; `pdfinfo` reports **12 pages A4 (595×842 pt)**, and the exported page-10 render shows the ink stroke (`pdftoppm`), with no selectable text (flattened by design) |
| All-page overview | Pages overview scrolled to cells **Page 10 / 11 / 12**; current-page emphasis and page numbers correct |
| Bookmark | sheet bookmark thumbnails still render for the current page; page-12 reachable (overview/jump) |
| Temp/cache | while the viewer was open, `run-as … ls cache` showed **no `pdf-session-*` file** (opened file unlinked); encrypted attachments remained `.nzb`; no plaintext PDF path |
| Crashes | 0 NoteZero FATAL/ANR during the flow |
| Screenshots | `52-pdf-full-document-page12`, `53-pdf-all-pages-overview`, `54-pdf-annotated-export-page10` |

Instrumented validation: real `AndroidPdfPageSessionFactory`/session 6/6 (arbitrary page 10 colour,
close idempotence, no temp file, corrupt/empty/out-of-range).

### 23.3 Security + release verification

| Check | Result |
|---|---|
| Offline guard | `./scripts/check-offline.sh --gradle` → `OK: NoteZero is offline by construction.` |
| Token lint | PDF lazy-viewer/session code token-clean |
| Hygiene | no TODO/FIXME/TEMP DEBUG/logging in the new session/viewer code; no new permissions/dependencies; no page/thumbnail plaintext cache (one unlinked ephemeral session PDF) |
| Reproducible APK | **REPRODUCIBLE** — sha256 `5f6de22d7e8abf8dfffe668b8c8fdc49153f30267ddf6aae7dfee7341c3e80b3` |
| Reproducible AAB | **REPRODUCIBLE** — sha256 `d59bd8a1942b7a27cfe3f64df2885882ed9763dd5d651a6e71e1f88fe5946d9f` |
| Artifacts | `releases/notezero-0.1.0-release.apk` / `.aab` replaced; `releases/SHA256SUMS` updated |
| Certificate | Cipherwell signing certificate unchanged |
| Owner phone | release APK installed over the existing vault; left locked; 0 NoteZero crash entries |

### 23.4 Residual notes

- Pages render on demand; scrolling can briefly show the A4 loading placeholder and a page is
  re-rendered when scrolled away and back. Page bitmaps are not cached beyond composition, which is
  the memory/no-plaintext trade-off (documented).
- The current page is derived from the LazyColumn's first visible row; on the very last page the
  previous page can remain "current" while the last page is partially visible (observed when
  adding a page-12 bookmark). Page 12 is reachable/visible and annotations work there.
- Annotated copies remain **flattened image-only** and are capped at 100 pages; above that the
  viewer refuses with `pdf_annotated_export_too_many` and writes nothing.
- If a platform filesystem refuses unlink after opening, the session falls back to deleting the
  ephemeral PDF at close (same app-private, no-backup, bounded trade-off).
- `MAX_VIEW_PAGES = 2000`; above that the viewer shows the ordinary clean error.
