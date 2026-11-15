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
  logs, auto-lock triggers on background/timeout.

## Measured baseline (2026-11-15, Phase 1)

| Suite | Command | Result |
|---|---|---|
| Unit (JVM + Robolectric) | `./gradlew :app:testDebugUnitTest` | **290 tests / 0 failures / 0 errors / 0 skipped** (27 classes) |
| Instrumented (emulator) | `adb shell am instrument -w com.cipherwell.notezero.test/androidx.test.runner.AndroidJUnitRunner` | **OK (10 tests)** incl. `VaultFlowE2ETest` |
| Offline guarantee | `./scripts/check-offline.sh --gradle` | exit 0 |
| Release signing | `apksigner verify --print-certs app-release.apk` | NoteZero upload key SHA-256 `FA:CA:DD:67:…:92:78` |

Per-area counts: crypto 61, data 49, model 43, security 5, ui shell 62, ui notes/editor 70.
Device E2E also scans the raw SQLite files for plaintext and captures the screenshots in
`docs/screenshots/`. Full evidence: `docs/VERIFICATION-2026-11-15.md`.

## Conventions

- Tests must assert behaviour, not existence (`assertTrue(true)` is a review reject).
- Deterministic: fixed test vectors/entropy where possible; no sleeps — inject a clock or
  use `kotlinx-coroutines-test` virtual time for autosave/auto-lock.
- No secrets in test fixtures: generated keys only, wiped after use.
- Crypto tests must not require Robolectric or a device.
- Every fixed security bug gets a regression test in the same commit.
