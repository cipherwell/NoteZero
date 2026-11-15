# Changelog

All notable changes to NoteZero are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow semantic versioning.

## [Unreleased]

### Added — bootstrap 2026-11-15

- Android project scaffold: AGP 9.1.1 / Gradle 9.3.1 / Kotlin 2.2.10 / KSP 2.3.5,
  `compileSdk 36`, `minSdk 24`, package `com.cipherwell.notezero`, MIT license.
- Independent NoteZero signing key generated (`~/.cipherwell-release/`, SHA-256
  `FA:CA:DD:67:…:92:78`), env-var release signing with no debug fallback; debug key script.
- Offline guarantee: no `INTERNET`/`ACCESS_NETWORK_STATE` permission (stripped via
  `tools:node="remove"`), network security config, `scripts/check-offline.sh`,
  `OfflineGuaranteeTest`, dependency policy in `docs/DEPENDENCIES.md`.
- Frozen Phase-1 contracts: `NoteCrypto` (AES-256-GCM, PBKDF2 600k), BIP-39 12-word
  recovery, versioned `KeyEnvelope`, Android Keystore biometric manager, item/folder/block
  model, `NoteRepository` interface, AAD domain separation.
- Decision record `docs/DECISIONS.md` (rich text, attachments, editor engine, item model,
  repo/hosting, signing, non-goals) and module ownership/contract map `docs/CONTRACTS.md`.
- Documentation set: README, SECURITY, BUILDING, TESTING, RELEASING, DEPENDENCIES,
  fastlane + F-Droid recipe scaffolds, CI workflow.

### Security baseline

- No cloud, accounts, telemetry, analytics or ads; no network permissions or APIs.
- Random 256-bit VMK; notes encrypted per-item with AES-256-GCM and AAD bound to item
  id + type; titles/tags/folder names/flags encrypted inside the envelope; Room columns
  hold only ids/kinds/timestamps/ciphertext.
- FLAG_SECURE on by default; encrypted backup excludes OS backup/device transfer.
