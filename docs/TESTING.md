# NoteZero — testing strategy

## Layers

| Layer | What it proves | Where |
|---|---|---|
| Pure JVM unit tests | Crypto primitives vs published vectors, envelope codecs, markdown codec, search/sort helpers | `app/src/test/java/com/cipherwell/notezero/**` |
| Robolectric unit tests | Room encryption/redaction, repository lifecycle (setup→unlock→lock), UI state machines, Compose screens with a fake repository | same, annotated `@RunWith(RobolectricTestRunner::class)` |
| Instrumented tests | Android Keystore biometric enrolment/invalidation, SAF backup round-trips, no-network runtime check | `app/src/androidTest/java/com/cipherwell/notezero/**` |
| Build-time guards | Offline guarantee (permissions, imports, deps), merged-manifest redaction | `scripts/check-offline.sh`, `OfflineGuaranteeTest`, CI |
| Emulator smoke | App launches, setup/unlock/notes flow, FLAG_SECURE behaviour, screenshots | Lead-run on `VaultZero-Live` AVD |

## Commands

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
./gradlew :app:testDebugUnitTest                  # all unit tests
./gradlew :app:testDebugUnitTest --tests "com.cipherwell.notezero.crypto.*"
./gradlew :app:connectedDebugAndroidTest          # device/emulator attached
./scripts/check-offline.sh --gradle
```

CI runs `assembleDebug`, `testDebugUnitTest`, and `check-offline.sh --gradle` on every push.

## Backup-flow tests (Phase-2 SAF gap)

| Test | What it proves | Tooling |
|---|---|---|
| `data/ExternalActivityLockTest` | The SAF picker's `onStop` does not lock the vault; `endExternalActivity` while backgrounded locks immediately; the idle timeout still locks during an external activity (no permanent suppression); nested brackets; a lost picker result cannot suppress later background locks | Robolectric, real repository + in-memory Room + real crypto |
| `ui.backup/BackupFlowControllerTest` | Vault-key and separate-password export write a non-empty envelope and wipe the password; import report/duplicate counts; cross-vault credential prompt; password **or** phrase submitted joined by single spaces; 3-attempt cap; `INVALID_FILE` structural probe; locked-picker resume via `onVaultUnlocked()`; cancelled/failed picker settles the external-activity bracket | plain JUnit, fake repository + in-memory streams |
| `ui.backup/BackupSectionContentTest` | Every `BackupUiState` renders the contract tags, non-secret copy, credential dialog, confirmation gate and disabled in-progress actions | Robolectric Compose |
| `ui.screens.settings/BackupSettingsSectionTest` | Settings screen shows the "Data & backup" section with stable tags; empty-vault export is refused without launching SAF | Robolectric Compose |

Commands:

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.cipherwell.notezero.data.*" \
  --tests "com.cipherwell.notezero.ui.backup.*" \
  --tests "com.cipherwell.notezero.ui.screens.settings.*"
```

## Required coverage by area (from the security bar)

- **Crypto:** PBKDF2-HMAC-SHA256 RFC 7914 vectors, AES-GCM NIST vectors, BIP-39 official
  English vectors, AAD cross-context swap failures, tamper/truncation rejection, wrong
  password returns failure (never throws AEADBadTag), constant-time compare sanity.
- **Key lifecycle:** setup→unlock; change password re-wraps VMK **without touching item
  ciphertext** (assert byte-identical rows); recovery reset; recovery rotation invalidates
  the old phrase; biometric wrap invalidation falls back to password.
- **Data at rest:** Room rows contain no plaintext title/body/tag/folder; corrupt row is
  skipped not surfaced; item AAD swap rejected; DB copy without VMK is useless (assert
  ciphertext non-plaintext and decrypt fails without VMK).
- **Backup:** export→import round trip, duplicate-safe import (same id+updatedAt counted,
  not overwritten), wrong password rejected, phrase-only mode.
- **UI/security UX:** FLAG_SECURE on by default, recovery phrase displayed once and gated
  behind confirmation, clipboard marked sensitive, password fields masked, no plaintext in
  logs, auto-lock triggers on background/timeout; the SAF picker background is deferred
  without cancelling the idle timeout and later background locks still lock.

## Measured baseline (2026-11-15, Phase 4 complete + Phase 5 hardening)

| Suite | Command | Result |
|---|---|---|
| Unit (JVM + Robolectric) | `./gradlew :app:testDebugUnitTest` | **876 tests / 0 failures / 0 errors / 0 skipped** (73 classes) |
| Instrumented (emulator) | `adb shell am instrument -w com.cipherwell.notezero.test/androidx.test.runner.AndroidJUnitRunner` | **OK (10 tests)** incl. `VaultFlowE2ETest` (notes + backup settings + code workspace) |
| Offline guarantee | `./scripts/check-offline.sh --gradle` | exit 0 |
| Release signing | `apksigner verify --print-certs app-release.apk` | NoteZero upload key SHA-256 `FA:CA:DD:67:…:92:78` |

Per-area counts: crypto 61, data 124, model 61, security 5, code engine 256, ui attachments 88,
ui notes 82, ui editor 74, other UI 62, backup 33, ui locks 18, ui screens 12.
Audit remediation added 24 regression tests (editor state wipe on lock/re-arm, code editor
locked gate, export status gating, stale-session handling, recovery-reset boundaries).
Device QA (Pixel 9 Pro XL, Android 17, font scale 1.15) added 10 more: biometric prompt-error
mapping + enrolled-flag gating (VaultViewModel/AppRoot) and the empty-note-on-leave predicate
(attachments count as content).
Highlights: code engine includes 200 KB lexer performance (Kotlin 6.42 ms, JS 7.85 ms,
XML 3.21 ms), fuzz invariants and `CodeSearch`; attachments cover NZAT blob format,
import limits/quota, AAD note/id swap rejection, tamper + SHA mismatch, trash-vs-delete
lifecycle, orphan sweep, locked fail-closed, backup v2 round trip incl. bytes, duplicate-safe
re-import and a frozen v1 backup fixture; E2E extends the SQLite plaintext scan to attachments.
Device E2E also scans the raw SQLite files for plaintext and captures the screenshots in
`docs/screenshots/`. Full evidence: `docs/VERIFICATION-2026-11-15.md`.

## Conventions

- Tests must assert behaviour, not existence (`assertTrue(true)` is a review reject).
- Deterministic: fixed test vectors/entropy where possible; no sleeps — inject a clock or
  use `kotlinx-coroutines-test` virtual time for autosave/auto-lock.
- No secrets in test fixtures: generated keys only, wiped after use.
- Crypto tests must not require Robolectric or a device.
- Every fixed security bug gets a regression test in the same commit.
