# NoteZero — architecture

**Status:** Phase 1 (bootstrap / security core) implementation. This document is owned by
the data layer and describes the shipped architecture; crypto details live in
[CRYPTO-SPEC.md](CRYPTO-SPEC.md) and the threat model in [THREAT-MODEL.md](THREAT-MODEL.md).

NoteZero is an offline-only, zero-knowledge Android notes + code editor. There is exactly one
process, one vault per device, no accounts, no network permission, and no code path that
writes decrypted content to disk.

## 1. Module / package map

```
com.cipherwell.notezero
├── NoteZeroApp.kt              Application: creates the single EncryptedNoteRepository(context)
├── MainActivity.kt             FLAG_SECURE, edge-to-edge, lifecycle → repository background hooks
├── crypto/                     AES-256-GCM, PBKDF2, BIP-39, KeyEnvelope, biometric Keystore
│                               (pure JVM except BiometricKeyManager; owned by crypto-core)
├── model/                      UI-facing domain types, all pure Kotlin
│   ├── Item.kt                 Item, Folder, ItemKind, ThemeMode
│   ├── Block.kt                Block sealed hierarchy, BlockDocument, BlockMarkdownCodec iface
│   ├── MarkdownCodec.kt        MarkdownBlockCodec: canonical Markdown ⇄ BlockDocument
│   └── ItemQueries.kt          title/preview, search/filter/sort, tag counts, folder tree,
│                               trash retention — the read-side logic shared by every screen
├── data/                       ENCRYPTED persistence + vault lifecycle (this document)
│   ├── NoteRepository.kt       frozen UI-facing interface (VaultStatus, UnlockResult, VaultInfo …)
│   ├── EncryptedNoteRepository.kt  the implementation (+ session/auto-lock)
│   ├── NoteZeroDatabase.kt     Room DB, schema version, migration policy
│   ├── Entities.kt             ItemEntity / FolderEntity / VaultMetaEntity (redacted columns)
│   ├── Daos.kt                 ItemDao / FolderDao / VaultMetaDao (structural queries only)
│   ├── BinaryCodec.kt          bounds-checked big-endian reader/writer for envelope payloads
│   ├── ItemCodec.kt            versioned item payload (inside the AEAD envelope)
│   ├── FolderCodec.kt          versioned folder payload (inside the AEAD envelope)
│   └── VaultPreferences.kt     DataStore: non-secret UI prefs only (theme, auto-lock minutes)
├── backup/
│   └── BackupFormat.kt         stream-based, authenticated backup envelope (pure JVM)
└── ui/                         Compose UI (shell + notes/editor + theme tokens)
    ├── backup/                 SAF backup UI: BackupFlowController (unit-testable state
    │                           machine + external-activity bracket), BackupFlowViewModel
    │                           (Activity-scoped pending-uri owner), BackupSection (settings)
    └── screens/settings/       SettingsScreen — composes the "Data & backup" section
```

The dependency direction is strictly one-way:

```
ui ──► data (NoteRepository) ──► crypto
ui ──► model
ui/backup ──► backup (structural header validation only, never decryption)
data ──► model, backup, crypto
backup ──► data (codecs), model, crypto
crypto ──► (nothing but JDK/Android Keystore)
model ──► (nothing but Kotlin stdlib)
```

`model/`, `backup/` and everything in `crypto/` except the Keystore manager are pure JVM and
are unit-tested without Robolectric. `data/EncryptedNoteRepository` is the only class that
needs Android (Context, Room, DataStore, Keystore).

## 2. What the data layer guarantees

1. **Nothing decrypted is ever persisted.** The VMK exists only as a `ByteArray` field of
   `EncryptedNoteRepository`; `lock()`/`close()` zeroizes it (best-effort with a hard
   `fill(0)` fallback). The DB stores key *wrappers* (useless without a credential) and
   AES-GCM ciphertext. Plaintext item/folder payloads are zeroized immediately after being
   encrypted or before a decoded copy is handed to the in-memory index.
2. **Redacted Room rows.** The only plaintext columns are `id`, `kind`, `created_at`,
   `updated_at` for items (plus `id`, `created_at` for folders). Titles, bodies, tags,
   folder names, tree parents, pinned/favourite/trash flags and edit versions are all
   sealed inside the envelope. Verified by `schemaExposesOnlyRedactedColumnsAndCiphertext`
   and a whole-file scan test (`vaultDatabaseFileContainsNoPlaintextContent`).
3. **AAD binding.** Every item ciphertext is bound to `CryptoAad.item(rowId, rowKind)` and
   every folder ciphertext to `CryptoAad.item(folderId, "FOLDER")`. Copying ciphertext to a
   different row id or kind fails authentication; such rows are skipped, counted and never
   surfaced (`aadBindsCiphertextToRowId`, `aadBindsCiphertextToRowKind`).
4. **Fail closed on corruption.** Decode/decrypt failures, unknown kinds, unknown envelope
   versions, truncated payloads, trailing bytes, out-of-range length prefixes and plaintext
   columns that disagree with the authenticated payload all mean "corrupt row": skipped,
   counted in the internal `corruptRowCount` diagnostic, never a crash and never plaintext
   (`tamperedAndGarbageRowsAreSkippedNotCrashed`, `PayloadCodecTest`).
5. **Main-safe suspend API.** Every suspend function does its work inside
   `withContext(ioDispatcher)` (production: `Dispatchers.IO`), then takes the single
   repository `Mutex`. Non-suspend members (`registerInteraction`, `onAppBackgrounded`,
   `onAppForegrounded`, biometric ciphers, StateFlow reads) are safe from the main thread.
6. **No secrets in logs.** The data layer logs nothing. `ItemEntity`/`FolderEntity`/
   `VaultMetaEntity` override `toString()` to redact ciphertext/envelope bytes, so even an
   accidental exception dump prints ids/counts only.
7. **Deterministic, testable time.** All timestamps come from an injectable `clock`, which
   makes autosave versioning, duplicate detection and auto-lock deterministic in tests.

## 3. Runtime data flow

```
main thread (Compose)                EncryptedNoteRepository                    Room
────────────────────                 ───────────────────────                    ────
observe status/items/folders ◄────── StateFlow updates (inside mutex)
saveItem(item) ─────────────────────► withContext(IO) { mutex {
                                        ItemCodec.encode → encrypt(AAD id+kind)
                                        dao.upsert(row)                     ──────► items
                                        update in-memory index + flows }
                                      }
unlock(password) ───────────────────► deriveKek → unwrapVmk → decrypt every row
                                      (skip+count corrupt) → publish index → UNLOCKED
```

- **In-memory index** = `LinkedHashMap<String, Item>` + `LinkedHashMap<String, Folder>`,
  published as immutable lists sorted deterministically (items: `updatedAt` desc, id;
  folders: `sortOrder`, name, id). The UI applies `ItemQueries.sort/filter` on top for
  list/search/sort preferences; filtering never touches the DB.
- **Mutations** (`saveItem`, trash/restore/delete, pin/favourite/move, folder ops) update the
  DB row *and* the in-memory index under the same mutex, so the flows never show a stale
  index after a suspend call returns.
- **Writes are item-by-item** (one row each); the only multi-row writes are backup import
  (single Room transaction) and folder deletion (sequential reparenting under the mutex).

## 4. Vault lifecycle

Implemented in `EncryptedNoteRepository`; see [DATA-MODEL.md](DATA-MODEL.md) §5 for the exact
state transitions and `docs/CONTRACTS.md` "Vault lifecycle".

| Operation | What happens |
|---|---|
| `setupVault(password)` | `randomVmk()` (32 B) → password `KeyEnvelope` (PBKDF2 600k) + recovery `KeyEnvelope` (BIP-39 12 unique words) → insert `vault_meta` → unlock. Returns the phrase **once**; the phrase is never persisted. |
| `unlock(password)` | unwrap VMK from the password envelope → load/decrypt index → `UNLOCKED`; wrong password → `WRONG_PASSWORD` (no state change); envelope unreadable/out-of-bounds KDF → `CORRUPT_VAULT`. |
| `lock()` | cancel auto-lock timer, zeroize VMK, clear index flows, `vaultInfo = null`, `LOCKED`. |
| `changePassword(current, next)` | verify current by unwrapping the password envelope, re-wrap the **same VMK** under a fresh salt; item/folder ciphertext is untouched (byte-identical, asserted). |
| `resetWithRecoveryPhrase(phrase, newPassword)` | validate + unwrap VMK with the recovery envelope, install a new password envelope and re-wrap the recovery envelope under a fresh salt, then unlock. The phrase itself is **kept valid** — see "Deliberate deviations" below. |
| `rotateRecoveryPhrase(currentPassword)` | verify password, generate a **new** phrase, replace only the recovery envelope, return the new phrase once (for the settings screen to display). |
| `validateRecoveryPhrase(phrase)` | format + BIP-39 checksum validation only (no KDF); the credential match happens in `resetWithRecoveryPhrase`. |
| biometric enroll | `prepareBiometricEnroll()` → UI shows `BiometricPrompt` with the returned cipher → `completeBiometricEnroll(enroll)` wraps the in-memory VMK under the Keystore key and sets the `vault_meta.biometric_enabled` flag. Two-phase because per-use-auth Keystore keys cannot encrypt without a prompt. |
| biometric unlock | `prepareBiometricDecrypt()` → prompt → `completeBiometricUnlock(decrypt)` unwraps the VMK and loads the index. If the Keystore reports the key invalidated by re-enrolment, the stale wrap is wiped by crypto-core and the repository clears the flag so the UI falls back to the password. |

`status` is a `StateFlow<VaultStatus>` with the three frozen values. To avoid a wrong-screen
flash on cold start, the constructor derives the initial value synchronously from a
non-secret presence marker file (`filesDir/notezero.vault.presence`); an IO-presence check
immediately corrects marker/DB disagreement (e.g. DB restored from an old backup).

## 5. Session, auto-lock and background lock

- The repository owns the idle timer (nothing in the UI schedules locks).
- `registerInteraction()` is called by the UI on real user input; it re-arms the timer.
- `onAppBackgrounded()` (Activity `onStop`/screen-off) **locks immediately** — the secure
  default for a vault app. The only exceptions are the build flag
  `-PdevDisableAutoLock=true` (`BuildConfig.DEV_DISABLE_AUTO_LOCK`), consumed via the
  `allowAutoLock` constructor parameter, for automation/emulator verification, and an
  active external activity (below).
- **External-activity deferral (the SAF picker).** `beginExternalActivity()` /
  `endExternalActivity()` bracket a system-owned activity that stops the app on purpose
  (`ActivityResultContracts.CreateDocument` / `OpenDocument`). While the bracket is open,
  `onAppBackgrounded()` records the pending background state instead of locking; the idle
  timer is **not** cancelled, so a picker left open past `autoLockMinutes` still locks — no
  permanent suppression. `onAppForegrounded()` clears the pending state (and resets a leaked
  bracket count, so a picker result that never arrives cannot weaken later background
  locks); `endExternalActivity()` while the app is still backgrounded locks immediately.
  The backup UI keeps the pending mode + `content://` uri in an Activity-scoped ViewModel
  (`ui/backup/BackupFlowViewModel`) and resumes the transfer via
  `BackupFlowController.onVaultUnlocked()` after the user unlocks, so the chosen file is
  never silently dropped. The deferral itself writes no plaintext and holds no secret.
- `onAppForegrounded()` re-arms the idle timer when still unlocked.
- `autoLockMinutes` (0 disables) is persisted in DataStore, clamped to `0..240`, default
  `5`. The timer polls every 10 s against the injected clock; the deterministic decision
  lives in `internal suspend fun evaluateAutoLock()` so tests never sleep.
- Preferences (`themeMode`, `autoLockMinutes`) are observed from DataStore and mirrored into
  `StateFlow`s; setters update both the store and the flow synchronously.

## 6. Backups

`exportBackup(out, password)` / `importBackup(in, password)` are stream-based so the UI can
hand them a SAF `InputStream`/`OutputStream` without any Android type leaking into
`backup/`. The envelope is a versioned binary format, sealed with AES-256-GCM under the
source vault's VMK, with the entire header (counts, mode, embedded key envelopes) as
additional authenticated data. Two modes:

- `password == null` → **vault-key / phrase-only mode**: the header embeds the source
  vault's password- and recovery-wrapped VMK envelopes, so a restore on another device can
  be authorised with the source master password *or* the source 12-word phrase.
- `password != null` → **extra-password mode**: the header embeds one envelope wrapping the
  source VMK under a password chosen for the backup file.

Import requires an unlocked target vault (it re-encrypts all imported items under the local
VMK and the local AAD), verifies/authenticates the whole file before the first write, then
writes every new row in one Room transaction. Duplicate policy is item-id + `updatedAt`
equal ⇒ counted as duplicate; same id with different `updatedAt` ⇒ conflict, never
overwritten (counted as skipped). Full wire format: [BACKUP-FORMAT.md](BACKUP-FORMAT.md).

The UI flow lives in `ui/backup/` and `ui/screens/settings/` (Settings → "Data & backup"):
export opens SAF `CreateDocument` (MIME `application/octet-stream`, suggested name
`notezero-backup-YYYYMMDD-HHmm.nzbk`) and import opens `OpenDocument`; both are bracketed by
the external-activity deferral above. A cross-vault envelope prompts once for the source
master password **or** the source 12-word phrase (joined with single spaces; max 3 attempts,
then a non-secret `WRONG_CREDENTIAL` error). If the vault locked while the picker was open,
the controller keeps the chosen uri and resumes after unlock instead of re-asking the user to
pick the file again. See [BACKUP-FORMAT.md](BACKUP-FORMAT.md) §8.

## 7. Security boundaries and non-goals

- The only key material persisted is the wrapped VMK (`vault_meta` envelopes) and the
  Keystore-wrapped VMK copy (inside crypto-core's private `SharedPreferences`, protected by
  a per-use-auth Keystore key). The plaintext VMK never touches disk.
- Room is not encrypted with SQLCipher: field-level AEAD envelopes are the storage
  encryption (decision 9 in [DECISIONS.md](DECISIONS.md)). Structural columns are
  intentionally visible to a local attacker (they already can see the DB file).
- The data layer performs no network I/O and imports no network types; CI also runs
  `scripts/check-offline.sh` and `OfflineGuaranteeTest`.
- Attachments/camera/table UI and the code-editor screens are out of scope for Phase 1;
  the item model already carries `ItemKind.CODE_FILE` so phase 3 needs no migration.

## 8. Testing strategy

| Layer | Tests | Tooling |
|---|---|---|
| Markdown codec | `model/MarkdownCodecTest` — parse/render round-trips, canonicalisation, never-throw fuzzing | plain JUnit (pure JVM) |
| Read-side helpers | `model/ItemQueriesTest` — title/preview, search/filter/sort, tags, trash window, folder tree | plain JUnit |
| Binary payloads | `data/PayloadCodecTest` — round-trip, bounds, corruption, fuzzing | plain JUnit |
| Backup envelope | `data/BackupFormatTest` — real AES-GCM, modes, header binding, tamper/truncation | plain JUnit |
| Backup UI flow | `ui.backup/BackupFlowControllerTest` — export/import state machine, credential cap, lock resume; `ui.backup/BackupSectionContentTest` — every state rendering | plain JUnit + Robolectric Compose |
| External-activity lock | `data/ExternalActivityLockTest` — picker background deferral, end-while-background, idle-timeout backstop | Robolectric + in-memory Room, real crypto |
| Repository end-to-end | `data/EncryptedNoteRepositoryTest` — setup/unlock/lock, recovery, AAD swaps, CRUD/folders, auto-lock, redaction, backup import/export | Robolectric + in-memory/on-disk Room, real crypto |

The repository test injects a `NoteZeroDatabase`, a `VaultPreferences` built on an isolated
DataStore file, a fake clock and `allowAutoLock`, and uses the contract-minimum KDF
iterations (`10_000`) so the suite stays fast; production defaults remain 600 000.

## 9. Wiring and lifecycle ownership

- The app wires exactly one instance: `NoteZeroApp.repository = EncryptedNoteRepository(context)`
  (constructor `EncryptedNoteRepository(context: Context)` is frozen for this reason; all
  other constructor parameters are optional injection points for tests).
- The repository owns its Room database when it creates it (default constructor); when a
  database/preferences object is injected, the caller owns them (`close()` only closes
  what it owns).
- `close()` releases the timer/observers and zeroizes the VMK; the app keeps the single
  instance for the process lifetime, so this is mainly a test/lifecycle hook.
- `MainActivity` forwards `onStop` → `onAppBackgrounded()`, `onStart` → `onAppForegrounded()`
  and the UI input layer forwards `registerInteraction()`. The UI never constructs the
  repository inside a composable and never sees a VMK or ciphertext.
- The Settings "Data & backup" section needs the app-scoped repository: `SettingsScreen`
  takes an optional `backupRepository` parameter (AppRoot may pass its instance) and,
  when absent, reads `NoteZeroApp.repository` through the context chain — it never creates
  a second repository.

## 10. Deliberate deviations / notes

- **Recovery reset keeps the same phrase.** The frozen `NoteRepository.resetWithRecoveryPhrase`
  returns only an `UnlockResult`, so rotating to a phrase the user can never see would
  silently invalidate their only recovery kit. Reset therefore re-wraps the recovery
  envelope under a fresh salt with the *same* phrase; phrase rotation is the explicit
  `rotateRecoveryPhrase(currentPassword)` call (settings screen), which returns the new
  phrase. This is documented in DATA-MODEL §5.
- **`validateRecoveryPhrase` checks format/checksum only** (no 600k-iteration KDF), so the
  recovery screen can give instant feedback; `resetWithRecoveryPhrase` performs the actual
  credential check and returns `WRONG_RECOVERY_PHRASE`.
- **Autosave conflict policy is last-write-wins with a monotonic version.** `saveItem`
  never lowers `version`; it sets `version = max(storedVersion, incomingVersion) + 1` and
  `updatedAt = max(now, storedUpdatedAt + 1)`. The UI detects conflicts by comparing the
  version it loaded with the version in the `items` flow. The frozen interface has no
  conflict-result type, so a hard rejection is not possible without an interface change.
- **Folder envelope AAD uses the pseudo-kind token `FOLDER`** with the same
  `CryptoAad.item(id, kind)` domain string; this keeps a single AAD construction helper and
  gives folder rows id-bound encryption without a crypto-contract change.
- **Vault count semantics:** `VaultInfo.itemCount`/`folderCount` count all rows including
  trashed items; the UI's trash screen derives its own view from the flows.
- **Recovery phrases cannot be zeroized in Kotlin.** Passwords arrive as `CharArray` and are
  wiped; the 12-word phrase arrives as `List<String>` (immutable, JVM-interned strings in
  some cases), so the repository cannot wipe it — the recovery UI owns that lifetime and
  must not persist/hoard it.
- **Trash retention is policy, not a background job:** `ItemQueries.DEFAULT_TRASH_RETENTION_DAYS`
  (30), `trashExpiry`, `isTrashExpired`, `expiredTrashIds` are provided for the notes UI /
  a future WorkManager job; the repository only exposes `emptyTrash()`.
