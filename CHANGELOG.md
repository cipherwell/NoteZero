# Changelog

All notable changes to NoteZero are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow semantic versioning.

## [Unreleased] — Phase 5: audit remediation, strings, reproducible APK+AAB (2026-11-15, increment 5)

### Added

**Security audit & remediation**
- Independent adversarial audit `docs/AUDIT-2026-11-15-phase5.md`: **0 HIGH / 0 CRITICAL**,
  2 MEDIUM, 8 LOW, 8 INFO, 12 doc/code mismatches; crypto core, AAD map, envelope parser,
  backup authentication, lock masking and the offline/permission surface held up.
- All findings remediated (remediation log in the report): decrypted editor state is wiped on
  lock/verification re-arm (notes + code, incl. undo/redo/conflict/find state); recovery-reset
  copy/docs now say the phrase is unchanged (rotation stays an explicit settings action);
  credentials no longer live in saved instance state; **security build flags are debug-variant
  only — release builds hardcode FLAG_SECURE and auto-lock on**; rotated recovery phrases are
  dropped on lock; code-editor plaintext export is status-gated; code editor gained a
  locked-content gate; biometric/recovery-reset session boundaries clear lock verifications;
  backup OOM is a clean failure; the auto-lock race surfaces as a stale-session state instead
  of crashing.
- Documented deliberate non-fix: KDF re-wrap-on-unlock was claimed but never implemented —
  the claim was removed (DECISIONS #17); a vault keeps its creation iteration count until an
  explicit password change re-wraps the envelope.
- Dependency/licence inventory updated (Apache-2.0, protobuf-lite BSD-3 transitive, packaged
  native libs, test-only JUnit EPL-1.0 not shipped).

**Translation-ready UI**
- All user-visible strings extracted to Android resources: **336 strings + 6 plurals**
  (screen-prefixed names, 33 content descriptions), Composables use
  `stringResource`/`pluralStringResource`. Pure-JVM helpers and glyphs stay inline with
  documented reasons in `docs/STRINGS.md`. Visible English is unchanged except the corrected
  recovery-reset copy.

**Reproducibility finalised**
- Signed **APK and AAB are both byte-identical** across two independent clean builds from
  different paths (`--no-daemon --no-build-cache`, `SOURCE_DATE_EPOCH=1763054400`):
  APK `c48de399…68da`, AAB `00a66b1e…f22a`; published artifacts are exactly those builds.
- `scripts/verify-reproducible.sh` gained `--artifact aab` and `--unsigned` modes; CI now runs
  the unsigned reproducibility check on every push, so nondeterministic builds fail the
  pipeline without signing secrets.

**Store readiness**
- `fastlane/metadata/android/en-US/`: title, short/full description, changelog, 8 phone
  screenshots; new `PRIVACY.md`; updated F-Droid recipe (pinned toolchain, reproducibility
  notes, privacy-policy link). README/SECURITY link the audit, strings doc and privacy policy.

### Verified (measured)

- **764 unit tests / 0 failures** (60 classes; +24 audit regression tests) and **10
  instrumented tests OK** on the emulator, including the full E2E flow.
- Reproducible signed APK **and** AAB (hashes in `releases/SHA256SUMS` and
  `docs/VERIFICATION-2026-11-15.md` §10); certificate unchanged
  (`FA:CA:DD:67:…:92:78`).
- FLAG_SECURE active on the normal build (`screencap` 0-byte); a release build passed
  `-PallowScreenshots=true -PdevDisableAutoLock=true` still generates `false` flags.
- Release permissions unchanged (`USE_BIOMETRIC` only + androidx.core signature guard);
  offline guard green; 0 TODO/Log/hex-outside-tokens/network imports; 14 emulator screenshots.

### Still open

- F-Droid submission after the 3-month window (2027-02-15); tag the release.
- UI translations (infrastructure ready), cross-toolchain reproducibility with F-Droid's
  build image, and the remaining INFO/low-impact audit notes.

## [Unreleased] — Locked notes/folders + reproducible release builds (2026-11-15, increment 4)

### Added

**Locked notes & folders (last missing SPEC security/UX feature)**
- `Item.locked` / `Folder.locked` are content: they live **inside** the AES-GCM envelope
  (`ItemCodec` payload v3, `FolderCodec` v2; v1/v2 still decode with defaults, no migration).
  `saveItem` preserves the stored lock flag, so a stale editor autosave can never unlock.
- Masking: locked+unverified notes render a content-free "Locked note" row (no title, preview,
  tags, folder path or attachment names); search, tag chips and content aggregates exclude
  them. Locked folders hide their name and descendants until verified.
- Re-auth: device biometric presence prompt (platform `BiometricPrompt` without a
  CryptoObject — a visibility gate; content stays VMK-encrypted) with master-password
  fallback. `verifyMasterPassword()` compares the unwrapped VMK in constant time with rate
  limiting (1–4 s growing delay, 30 s cooldown after 5 failures) and wipes the input array.
- Verification is memory-only: cleared on lock, real background stop/screen-off, manual lock
  and session end — but **not** when an SAF picker defers the background lock. Backup/restore
  preserves lock flags and never verification.
- Code workspace defense in depth: locked+unverified code files and all descendants of a
  locked+unverified folder are filtered out before tree/counts/search, with a
  "N locked item(s) hidden — verify in Notes" hint (DECISIONS #16).
- Settings: "Locked notes & folders" section with "Re-arm locked notes now"
  (`settings_relock`); note/folder row menus gain Lock/Remove lock.
- Docs: DECISIONS #15 (lock semantics, threat model) and #16 (code-workspace hiding),
  DATA-MODEL version tables, ARCHITECTURE session re-arm, THREAT-MODEL handoff/forensic rows,
  CONTRACTS-PHASE5.

**Reproducible release builds (Phase 5)**
- `scripts/verify-reproducible.sh`: two clean builds from two **different absolute paths**,
  pinned `SOURCE_DATE_EPOCH`, `--no-daemon --no-build-cache`, then byte comparison of the
  signed APKs (plus per-entry diff report on mismatch).
- **Measured: byte-identical APKs** (`sha256 18254b31…5eaa`), including the APK Signing Block;
  methodology, pinned toolchain and limits (AAB equality not claimed) in
  `docs/REPRODUCIBLE-BUILDS.md`; RELEASING links the verification command.

### Verified (measured)

- **740 unit tests / 0 failures** (59 classes): data 123 (incl. 24 lock tests), code engine 139,
  ui code 103 (incl. locked-content hiding), ui notes 57, ui editor 45, ui locks 18,
  crypto 61, model 43, backup/settings 37, attachments 47, security 5, other UI 62.
- **10 instrumented tests OK** on the emulator; `VaultFlowE2ETest` now also covers: lock a note
  from its row menu → masked "Locked note" row → tap → re-auth dialog → master password →
  revealed note opens in the editor; the encrypted lock flag survives the session and
  verification does not.
- **Reproducible APK**: two independent clean builds byte-identical
  (`18254b31…5eaa`); signed with the NoteZero upload key (`FA:CA:DD:67:…:92:78`).
  AAB (not yet claimed reproducible): `d26bf216…6cf5`.
- FLAG_SECURE re-verified (`screencap` 0 bytes on the normal build); release permissions still
  `USE_BIOMETRIC` only; `check-offline.sh` green; 0 TODO/Log/hex-outside-tokens/network imports.
- 14 real-emulator screenshots committed (00-setup … 13-locked-note-revealed).

### Notes / known limits

- Lock is a **visibility gate**, not a second encryption layer: locked content is still under
  the vault VMK; the threat addressed is shoulder-surfing/handoff, not a fully compromised
  unlocked device (documented in THREAT-MODEL residual risks).
- Locked code files can only arise via restore/backup; the code workspace hides them and points
  to Notes for re-auth (no second re-auth surface).
- AAB byte equality and cross-toolchain reproducibility remain open Phase-5 items; string
  extraction and store assets are still pending. F-Droid window opens 2027-02-15.

## [Unreleased] — Phase 4 attachments + Phase 3 polish (2026-11-15, increment 3)

### Added

**Encrypted attachments (Phase 4)**
- `AttachmentRef` metadata (display name, MIME, size, sha256) lives **inside** the item's
  AES-GCM envelope (`ItemCodec` payload v2, still decodes v1 rows); the bytes live in
  app-private `filesDir/attachments/<id>.nzb` as `NZAT` v1 blobs (magic+version+AES-256-GCM
  under the vault VMK, AAD `notezero.attachment.v1|<noteId>|<attachmentId>`), SHA-256 verified
  on read, bounds-checked, atomic import (temp + rename, no orphan blob/ref on failure).
- Limits enforced while streaming: 10 MiB per attachment, 100 per note, 512 MiB vault quota;
  path-traversal-safe ids; locked vault fails closed.
- Lifecycle: trash keeps blobs, delete-forever/empty-trash removes them; startup orphan sweep
  (skipped when corrupt rows exist); attachment refs are repository-owned, so a stale editor
  autosave can never add or drop refs.
- Encrypted backup **format v2** carries attachment bytes; v1 backups still import;
  duplicate-safe restore with SHA-256 verification, missing/invalid blobs skipped+counted.
- Notes editor: attachment strip between title and body — Photo Picker image import
  (`PickVisualMedia`, no camera/storage permission), SAF file import, in-memory thumbnails
  (no plaintext cache), full-screen decrypted viewer, delete with confirm, decrypted export
  behind an explicit UNENCRYPTED-copy warning; all system pickers bracketed with
  `beginExternalActivity`/`endExternalActivity`.

**Code workspace polish (Phase 3 completion)**
- Workspace-wide search (`code/CodeSearch`, pure Kotlin): titles + bodies, line/column +
  snippet, case toggle, bounded; a result opens the file at the matched line.
- Sensitivity-aware copy (selection or all, `EXTRA_IS_SENSITIVE`) and explicit-warning
  decrypted export via SAF; multi-line indent/outdent toolbar actions and Tab/Shift+Tab.
- Docs: `docs/ATTACHMENTS.md`, `docs/ATTACHMENTS-UI.md`, `docs/CONTRACTS-PHASE4.md`,
  `docs/EDITOR-SPEC.md` §10, and `docs/DECISIONS.md` #14 (attachment storage/AAD/quota,
  including the documented 64 MiB backup cap consequence).

### Verified (measured)

- **663 unit tests / 0 failures** (51 classes): data 99, code engine 139, ui code 100,
  ui attachments 47, ui editor 38, crypto 61, model 43, backup/settings 37, security 5,
  shell/notes 94. Includes attachment AAD swap/tamper/SHA-mismatch, quota, trash-vs-delete,
  orphan sweep, backup v2 round trip incl. bytes + frozen v1 fixture, and code-search tests.
- **10 instrumented tests OK**; `VaultFlowE2ETest` extended with the code-workspace search
  flow (search `CodeE2E_secret` → 1 match → result opens at line) and the attachments strip
  renders in the real editor. Plaintext-at-rest scan still clean for note/code content.
- Signed release rebuilt and verified: APK `d498b1f3…cc30`, AAB `a270d6d5…591f`, certificate
  `FA:CA:DD:67:…:92:78`. FLAG_SECURE re-verified (`screencap` 0 bytes on the normal build);
  release permissions unchanged (`USE_BIOMETRIC` only); `check-offline.sh` green.
- Screenshots refreshed (11 PNGs incl. workspace search + search-result editor).

### Notes / known limits

- Backup format v2 keeps the existing 64 MiB `MAX_BACKUP_BYTES` OOM guard; a vault with more
  attachment bytes than that fails export **cleanly** (returns false, no partial file) instead
  of streaming — chunked backup v3 is future work (documented in BACKUP-FORMAT §6).
- Per-note count and vault quota share `AttachmentError.QUOTA_EXCEEDED` (UI differentiates).
- Drawing canvas, camera scan, tables editing UI and widgets remain Phase 4 follow-ups.
- Byte-for-byte reproducible release builds remain a Phase-5 task.

## [Unreleased] — Phase 2 complete + Phase 3 start (2026-11-15, increment 2)

### Added

**Encrypted backup UI (closes the Phase-2 gap)**
- SAF export/import of encrypted backup envelopes: `CreateDocument`/`OpenDocument`, suggested
  name `notezero-backup-YYYYMMDD-HHmm.nzbk`, no plaintext leaves the app.
- External-activity lock deferral: the SAF picker's background stop no longer locks the vault
  (`beginExternalActivity`/`endExternalActivity`); idle timeout still applies, end-while-
  background locks immediately, no permanent suppression, foreground return clears state.
- Export modes: vault-key (phrase-only restore) and optional separate backup password.
- Import: duplicate-safe verified restore, report counts (imported/duplicated/skipped/folders),
  cross-vault credential prompt accepting the source master password **or** 12-word phrase
  (3-attempt cap), lock-resume keeps the chosen file and continues after unlock.
- Settings "Data & backup" section with progress/success/report/error states; passwords are
  zeroized and never logged.

**Code editor (Phase 3)**
- Pure-Kotlin (JVM-testable) engine `com.cipherwell.notezero.code`: lexers for Kotlin, Java,
  JS, TS, Python, JSON, XML, Markdown and Shell (single-pass scanners, 200k token cap, no
  catastrophic regexes), `LineIndex`, and editing operations (indent/outdent, language-aware
  auto-indent, bracket matching, literal find/replace, go-to-line).
- Code workspace UI: nested file/folder tree over the SAME encrypted item model and folder
  tree as notes (`ItemKind.CODE_FILE`), create file/folder, rename, move, trash, empty state.
- Compose code editor: line-number gutter, current-line highlight, lexer token colours,
  auto-indent, bracket-match highlight, find/replace bar (next/prev/replace all/case/count),
  go-to-line, bounded undo/redo, font size 12–24 sp and wrap toggles persisted in non-secret
  prefs, 600 ms autosave with save-on-leave, dirty/conflict banner, 2 MiB open guard and
  >256 KiB plain-mono guard, Markdown preview reuse for .md files.
- Notes screen gains a Code workspace entry; `AppRoot` routes code workspace ↔ notes ↔ settings.
- Docs: `docs/CONTRACTS-PHASE3.md`, `docs/EDITOR-SPEC.md`, backup flow in
  `docs/BACKUP-FORMAT.md`, external-activity mechanism in `docs/ARCHITECTURE.md`,
  `docs/DECISIONS.md` #13.

### Verified (measured)

- **533 unit tests / 0 failures** (43 classes; code engine 126 incl. 200 KB lexer perf
  Kotlin 6.42 ms / JS 7.85 ms / XML 3.21 ms and fuzz invariants, ui code 72, backup 37,
  data 57 incl. 8 external-activity tests).
- **10 instrumented tests OK** on the emulator; `VaultFlowE2ETest` now also covers: Settings
  backup section, and the code workspace (create `hello.kt` → syntax-highlighted editor →
  `Saved` → back → file persisted). The raw SQLite plaintext scan was extended to the code
  file's name and body — both stay encrypted at rest.
- Signed release rebuilt and re-verified with the NoteZero upload key (APK sha256
  `2998c954…3195`, AAB `60203086…21c2`; certificate `FA:CA:DD:67:…:92:78`).
- E2E screenshot review caught two real defects and both were fixed with regression tests:
  always-plural workspace header ("1 files") and a scrolled-away settings back button
  (E2E now uses the system back action).
- `scripts/check-offline.sh` green; no new dependencies; no network imports.

### Notes

- Device E2E capture requires `-PallowScreenshots=true` (debug-only; normal builds keep
  FLAG_SECURE and `screencap` returns 0 bytes).
- Byte-for-byte reproducible release builds remain a Phase-5 task.

## [Unreleased] — Phase 1: security core + notes MVP (2026-11-15)

### Added

**Project & identity**
- Android project scaffold: AGP 9.1.1 / Gradle 9.3.1 / Kotlin 2.2.10 / KSP 2.3.5,
  `compileSdk 36`, `minSdk 24`, package `com.cipherwell.notezero`, MIT licence.
- **Independent NoteZero upload key** (not VaultZero's, not the debug key), generated outside
  the repo at `~/.cipherwell-release/notezero-upload.jks`; release signing via
  `NOTEZERO_KEYSTORE_PATH`/`NOTEZERO_STORE_PASSWORD`/`NOTEZERO_KEY_PASSWORD`; unsigned
  release fallback for F-Droid when secrets are absent. Certificate SHA-256 recorded in
  SECURITY.md.
- Repo `cipherwell/NoteZero` (public GitHub + GitLab mirror), CI (build, unit tests, offline
  audit, committed-key scan), signed-release workflow, fastlane metadata and F-Droid recipe
  scaffolds, dependabot policy.

**Security core (Phase 1)**
- `NoteCrypto`: AES-256-GCM (`iv||ct||tag`, 96-bit random IV, 128-bit tag), PBKDF2-HMAC-SHA256
  600,000 iterations (10,000 floor for legacy envelopes), VMK wrapping/unwrapping, SHA-256,
  HMAC-SHA256, constant-time compare, zeroization; pure-JVM (testable without Robolectric).
- Versioned `KeyEnvelope` (NZEN v1) codec with bounds-checked, hostile-input-safe parsing.
- BIP-39 12-word recovery phrases: embedded 2048-word English list, 128 bits entropy + 4-bit
  checksum, unique-word generation, standard-phrase validation (legacy-compatible), NFKD
  canonical form.
- Android Keystore biometric unlock: two-phase enrollment (`prepareEnroll` → BiometricPrompt →
  `completeEnroll`) and unlock (`prepareDecrypt` → prompt → `completeDecrypt`), StrongBox→TEE,
  per-use `AUTH_BIOMETRIC_STRONG`, invalidated by biometric re-enrolment with stale-wrap wipe.
  Platform BiometricPrompt is used from Android 9 (API 28+); API 24–27 falls back to password.
- Random 256-bit VMK; notes/folders AES-256-GCM encrypted under it with AAD bound to item id
  and kind; password change and recovery reset re-wrap the VMK without re-encrypting data.
- Redacted Room schema: items/folders rows hold only random id, kind, timestamps and
  ciphertext; titles, bodies, tags, folder names and flags live inside the envelope; corrupt
  rows skipped+counted; non-secret presence marker for correct initial lock state.
- In-memory decrypted index (empty when locked), encrypted stream backup export/import
  (vault-key/phrase-only and extra-password modes, duplicate-safe, all-or-nothing import),
  auto-lock (on background immediately + idle timeout) and DataStore preferences.

**Notes MVP UI**
- One UI-inspired Compose design system: true black/#FAFAFA grounds, one accent blue,
  squircle surfaces, floating pill navigation, large collapsing titles, WCAG-AA-checked
  tokens (contrast maths unit-tested), 48 dp targets, light/dark/system.
- Setup (password policy, one-time phrase with sensitive-flagged clipboard, 3-word
  confirmation gate), unlock (password + biometric CTA + recovery route), recovery
  (12-word phrase → new password), settings (vault info, change password, rotate recovery
  kit, two-phase biometric enrollment, auto-lock, theme, lock-now).
- Notes list: pinned/favourites/recent, nested folders + breadcrumbs, tags, in-memory search
  (title/body/tags/folder), trash with restore/delete-forever/empty, long-press actions
  (pin/favourite/rename/move/trash), move-to-folder picker, quick-note FAB.
- Markdown editor: source editing + live block preview (headings, inline bold/italic/code/
  links/strikethrough, bullets, numbered lists, tappable checklists that rewrite the
  markdown, fenced code, quotes, rules, tables), 800 ms debounced autosave, save-on-leave,
  dirty/conflict banner with overwrite/reload, undo/redo, word/char count, monospace option.

**Docs & proof**
- README, SECURITY, BUILDING, TESTING, RELEASING, DEPENDENCIES, DECISIONS, CONTRACTS,
  CRYPTO-SPEC, THREAT-MODEL, ARCHITECTURE, DATA-MODEL, BACKUP-FORMAT and
  `docs/VERIFICATION-2026-11-15.md` (measured evidence).
- Offline guarantee enforced three ways: `tools:node="remove"` for INTERNET /
  ACCESS_NETWORK_STATE (+ deprecated USE_FINGERPRINT), `scripts/check-offline.sh --gradle`,
  `OfflineGuaranteeTest` source/manifest scan. Network security config denies cleartext;
  Android backup/device-transfer excluded.

### Verified (measured, not claimed)

- `:app:assembleDebug` / `:app:assembleRelease` / `:app:bundleRelease` green (signed release
  APK sha256 `a23f8972…28d9`, AAB `1488edd3…27a5`); unsigned-release fallback verified.
  Caveat: two local builds of the same commit produced different packaging hashes, so
  byte-for-byte reproducibility is **not** claimed yet (Phase 5 work, docs/RELEASING.md).
- **290 unit tests / 0 failures** (27 classes) incl. RFC 7914 PBKDF2, NIST AES-GCM KATs,
  official BIP-39 vectors, AAD-swap rejection, ciphertext-unchanged password change.
- **10 instrumented tests OK** on a real emulator incl. an end-to-end vault lifecycle + UI
  flow; raw SQLite scan finds no plaintext title/body/phrase; checklist preview bug found by
  the E2E screenshot and fixed with regression assertions.
- FLAG_SECURE default-on verified (screencap returns 0 bytes on the normal build).
- Release permission list: `USE_BIOMETRIC` + androidx.core signature-level receiver guard only.

### Known gaps (next phases)

- SAF backup **UI** (export/import engine is done; the picker triggers the background lock, so
  the UI must resume post-unlock) — first Phase-2 item.
- Code editor (Phase 3), attachments/camera (Phase 4), reproducible-build verification and
  F-Droid submission after the 3-month window (Phase 5).
- Biometric CTA on API 24–27 is password-only by design (see docs/DECISIONS.md #11).

### Security baseline

- No cloud, accounts, telemetry, analytics or ads; no network permissions or APIs.
- Random 256-bit VMK; notes encrypted per-item with AES-256-GCM and AAD bound to item
  id + type; titles/tags/folder names/flags encrypted inside the envelope; Room columns
  hold only ids/kinds/timestamps/ciphertext.
- FLAG_SECURE on by default; encrypted backup excludes OS backup/device transfer.
