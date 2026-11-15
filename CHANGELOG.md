# Changelog

All notable changes to NoteZero are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow semantic versioning.

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
  APK sha256 `ed520573…e381`, AAB `a1a7741d…868d`); unsigned-release fallback verified.
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
