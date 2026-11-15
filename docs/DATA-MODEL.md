# NoteZero — data model and encrypted storage format

**Owner:** data layer. This document is normative for the on-disk format: changing a byte
layout, AAD string or column here is a breaking change and requires a schema/envelope
version bump. Crypto primitives are specified in [CRYPTO-SPEC.md](CRYPTO-SPEC.md); the
backup wire format has its own document, [BACKUP-FORMAT.md](BACKUP-FORMAT.md); encrypted
attachment storage has [ATTACHMENTS.md](ATTACHMENTS.md).

The design rule is simple: **the database stores structure, never content.** Anything a
user typed (title, body, tags, folder name, parent, pin/favourite/trash/lock flags) is
sealed inside a per-record AES-256-GCM envelope. The only plaintext fields are random ids, an item
kind enum name, timestamps and the vault's wrapped-key blobs.

## 1. Room schema (v1)

Database file: `notezero-vault.db` (`NoteZeroDatabase.DATABASE_NAME`), Room
`SCHEMA_VERSION = 1`, `exportSchema = false` (the schema is documented here instead).
There is **no destructive migration fallback**: silently dropping `vault_meta` would destroy
a vault. Every future schema change bumps `SCHEMA_VERSION` and adds an explicit,
non-destructive `Migration`.

### `items`

| Column | SQLite type | Meaning |
|---|---|---|
| `id` | TEXT, PRIMARY KEY | Random UUID at creation, never derived from content. |
| `kind` | TEXT | `ItemKind.name`: `NOTE` or `CODE_FILE`. |
| `created_at` | INTEGER | Epoch millis (structural). |
| `updated_at` | INTEGER | Epoch millis, strictly increased on every save/flag change (structural; drives list order and backup duplicate detection). |
| `ciphertext` | BLOB | `iv(12) || AES-256-GCM ciphertext || tag(16)` over an [ItemCodec](#2-itemcodec-envelope-payload) payload, AAD-bound to `id` + `kind`. |

### `folders`

| Column | SQLite type | Meaning |
|---|---|---|
| `id` | TEXT, PRIMARY KEY | Random UUID. |
| `created_at` | INTEGER | Epoch millis (structural). |
| `ciphertext` | BLOB | AES-GCM envelope over a [FolderCodec](#3-foldercodec-envelope-payload) payload, AAD-bound to `id` + pseudo-kind `FOLDER`. |

Folder names and the parent/tree references are content and are therefore encrypted; the
tree is reconstructed in memory after unlock. Reparenting (see §6) re-encrypts the affected
folder envelopes.

The **lock flags** (`Item.locked` / `Folder.locked`) are content as well: they live at the
end of the existing item/folder envelopes (§2, §3) and there is deliberately **no**
`locked` column, so a copy of the DB file does not even reveal which records are locked.
Locking is a visibility gate, not a second encryption layer — see §7.

### `vault_meta` (singleton, `singleton = 1`)

| Column | SQLite type | Meaning |
|---|---|---|
| `singleton` | INTEGER, PRIMARY KEY | Always `1`; enforces a single vault per device/DB. |
| `vault_id` | TEXT | Random UUID identifying this vault (non-secret; useful for diagnostics/backups). |
| `password_envelope` | BLOB | `KeyEnvelope(PASSWORD, iterations, salt16, wrapVmk(vmk, deriveKek(password), AAD=VMK_WRAP))`. |
| `recovery_envelope` | BLOB | `KeyEnvelope(RECOVERY, iterations, salt16, wrapVmk(vmk, deriveKekFromPhrase(phrase), AAD=RECOVERY_WRAP))`. |
| `schema_version` | INTEGER | `NoteZeroDatabase.SCHEMA_VERSION` recorded at setup (shown in `VaultInfo`). |
| `created_at` | INTEGER | Vault creation epoch millis. |
| `biometric_enabled` | INTEGER (bool) | Non-secret flag: a Keystore-wrapped VMK copy exists. Flipped by the two-phase biometric enrolment. |

The plaintext VMK is **never** stored. Both envelopes are useless without the password /
12-word phrase, and are additionally domain-separated by AAD so a password wrap cannot be
replayed as a recovery wrap (or vice versa) even with the same KEK.

### Device-local state outside Room

| Location | Contents | Secret? |
|---|---|---|
| `filesDir/notezero.vault.presence` | One non-secret byte marker ("1") so the synchronous initial `VaultStatus` is correct before the first DB read. Corrected by an async presence check if it disagrees with `vault_meta`. | No |
| DataStore `notezero-prefs` | `auto_lock_minutes` (int, default 5, 0 disables), `theme_mode` (`SYSTEM`/`LIGHT`/`DARK`). | No |
| crypto-core's private prefs + Android Keystore | Keystore-wrapped VMK copy for biometric unlock; the wrapping key never leaves the Keystore. | Ciphertext only |
| `filesDir/attachments/<attachmentId>.nzb` | Encrypted attachment blobs: magic `NZAT` (4) + version `0x01` (1) + AES-256-GCM envelope (`iv||ct||tag`) under the VMK, AAD = `CryptoAad.attachment(noteId, attachmentId)`. File name is the random attachment id only. See [ATTACHMENTS.md](ATTACHMENTS.md). | Ciphertext only |

## 2. `ItemCodec` envelope payload

Versioned big-endian binary payload stored **inside** the item ciphertext. Decoding is
strict: unknown magic/version/flags, truncation, oversized fields or trailing bytes return
`null` and the row is treated as corrupt. Limits are enforced on both encode and decode.
**Version 2** appends the attachment metadata list, **version 3** appends the lock flag;
**version 1** payloads (pre-attachments vaults) still decode to an item with an empty
attachment list and `locked = false`, and **version 2** payloads (pre-lock vaults) decode
with `locked = false` — no migration or re-encryption is needed, the next save writes v3.
Attachment metadata is described in [ATTACHMENTS.md](ATTACHMENTS.md) §3.

```
offset size field
0      4    magic "NZIT"
4      1    envelope version = 1, 2 or 3 (3 is written)
5      1    kind: ItemKind ordinal (0 = NOTE, 1 = CODE_FILE)
6      1    flags: bit0 folderId present, bit1 pinned, bit2 favourite, bit3 trashed
            (all other bits must be 0)
7      8    createdAt  (i64 epoch millis)
15     8    updatedAt  (i64 epoch millis)
23     8    version    (i64 edit version)
31     2    idLen      (u16, 1..128)
33     n    id         (UTF-8)
        4   titleLen   (u32, <= 8 KiB)
        n   title      (UTF-8)
        4   bodyLen    (u32, <= 16 MiB)
        n   body       (UTF-8)
        2   tagCount   (u16, <= 256)
        ... per tag: 2-byte len (<= 512) + UTF-8 bytes
        2   folderIdLen (u16, <= 128) — only when flags.bit0 is set
        -- version 2 only, appended after the folder id: --
        2   attachmentCount (u16, <= 100)
            per attachment: 2 idLen + id (1..128 bytes),
                            4 displayNameLen + name (1..1024 bytes),
                            2 mimeTypeLen + mime (1..256 bytes),
                            8 sizeBytes (i64, 0..10 MiB),
                            8 createdAt (i64),
                            32 raw SHA-256 (hex lowercase in the model)
        -- version 3 only, appended after the attachment list: --
        1   locked    (0 = unlocked, 1 = locked; any other value is corrupt)
```

Payload version table:

| version | written by | attachments | readable |
|---|---|---|---|
| 1 | Phase 1–3 builds | empty | yes (`locked = false`) |
| 2 | Phase 4 builds | `Item.attachments` | yes (`locked = false`) |
| 3 | this build | `Item.attachments` | yes (`locked` flag) |

`Item.attachments` is repository-owned metadata: `saveItem` preserves the stored list and
only `importAttachment`/`deleteAttachment` change it (so a stale editor autosave can never
add or drop a ref). Attachment **bytes** are not in the payload — they are the encrypted
`NZAT` blob files described above; only names/MIME/size/SHA-256 live in the envelope.

`Item.locked` is repository-owned the same way (see §7): `saveItem` preserves the stored
flag and only `setItemLocked` changes it, so an editor autosave with a stale unlocked copy
cannot silently reveal a locked note.

Why `id` and `kind` appear both in the row and the payload: the AAD binds the *row's*
plaintext columns to the ciphertext, and the payload repeats them so the repository can
detect a tampered structural column (`item.id != row.id`, `item.kind != row.kind`,
timestamps disagreeing) and skip the row rather than trusting it.

The canonical tags stored in the payload are `ItemQueries.normaliseTags`-clean (trimmed,
case-insensitively de-duplicated, order preserving); the repository applies that
normalisation on every `saveItem`.

## 3. `FolderCodec` envelope payload

Same strictness as ItemCodec.

```
offset size field
0      4    magic "NZFD"
4      1    envelope version = 1 or 2 (2 is written)
5      1    flags: bit0 parentId present (other bits 0)
6      8    createdAt  (i64 epoch millis)
14     4    sortOrder  (i32, siblings ordering hint)
18     2    idLen      (u16, 1..128)
20     n    id         (UTF-8)
        4   nameLen    (u32, <= 8 KiB)
        n   name       (UTF-8)
        2   parentIdLen (u16, <= 128) — only when flags.bit0 is set
        -- version 2 only, appended after the parent id: --
        1   locked     (0 = unlocked, 1 = locked; any other value is corrupt)
```

Folder payload version table:

| version | written by | lock flag | readable |
|---|---|---|---|
| 1 | Phases 1–4 builds | none (decodes to `locked = false`) | yes |
| 2 | this build | `Folder.locked` | yes |

## 4. AAD map (what the ciphertext is bound to)

| Envelope | AES key | AAD | Effect of a mismatch |
|---|---|---|---|
| item | VMK | `CryptoAad.item(rowId, rowKind)` = `"notezero.item.v1|<id>|<KIND>"` | `decrypt` returns null → row skipped+counted; cross-row / cross-kind swaps fail. |
| folder | VMK | `CryptoAad.item(folderId, "FOLDER")` (same domain string, pseudo-kind token) | same |
| password-wrapped VMK | KEK(password) | `CryptoAad.VMK_WRAP` = `"notezero.vmk.v1"` | unwrap fails → `WRONG_PASSWORD`. |
| recovery-wrapped VMK | KEK(phrase) | `CryptoAad.RECOVERY_WRAP` = `"notezero.recovery.v1"` | unwrap fails → `WRONG_RECOVERY_PHRASE`. |
| backup extra-password envelope | KEK(backup password) | `CryptoAad.BACKUP` = `"notezero.backup.v1"` | unwrap fails → import returns null. |
| backup payload | source VMK | `CryptoAad.BACKUP` `||` complete backup header bytes | tampered header/payload → AEAD failure. |
| attachment blob (`.nzb`) | VMK | `CryptoAad.attachment(noteId, attachmentId)` = `"notezero.attachment.v1|<noteId>|<attachmentId>"` | `decrypt` returns null → read fails closed, counted; blob swaps between notes/ids/vaults fail. |

## 5. Vault lifecycle semantics

State is `VaultStatus.SETUP_REQUIRED → UNLOCKED ⇄ LOCKED`. The VMK is a 32-byte
`ByteArray` field, memory-only; it is zeroized (`NoteCrypto.wipe`, hard `fill(0)` fallback)
on `lock()`/`close()`. The same calls also clear the session-scoped lock verifications
and the re-authentication rate limiter (§7).

### setup

`setupVault(password)` is accepted only when no `vault_meta` row exists
(`IllegalStateException` otherwise):

1. `vmk = NoteCrypto.randomVmk()` (32 random bytes).
2. fresh 16-byte salts; password envelope = wrap under PBKDF2-HMAC-SHA256 KEK; recovery
   envelope = wrap under BIP-39 phrase KEK (fresh 12-word phrase, unique words, checksum).
3. insert the `vault_meta` row (atomic single row), write the presence marker, publish an
   empty index and enter `UNLOCKED`.
4. return the phrase **once**; it is not stored in plaintext anywhere.
5. `password` is consumed and zeroized by the KDF (repository wipes it again defensively).

### unlock

Load `vault_meta`; decode `password_envelope`; validate the iteration count against
`[10_000, 10_000_000]`; derive KEK with the envelope's stored salt/iterations; unwrap VMK
with `VMK_WRAP` AAD. `null` ⇒ `WRONG_PASSWORD` (index stays empty, status stays `LOCKED`).
Decodeable-but-structurally-wrong envelopes (bad magic/lengths/KDF) ⇒ `CORRUPT_VAULT`.
On success decrypt every row into the in-memory index, skipping and counting corrupt rows,
then `UNLOCKED`.

### change password

Verifies the **current** password by unwrapping the password envelope (works while locked or
unlocked; if unlocked the unwrapped VMK must equal the in-memory VMK), then writes a new
password envelope with a fresh salt and the repository's KDF iteration count. The VMK never
changes and no item/folder row is read or written — ciphertext stays byte-identical.

### recovery reset

Validates the BIP-39 phrase, unwraps the VMK with the recovery envelope, installs a new
password envelope under `newPassword`, re-wraps the recovery envelope under a fresh salt,
and unlocks. **The phrase itself is not changed** by reset: the frozen `NoteRepository`
interface returns no phrase, so rotating to an unseen phrase would silently strand the
user's recovery kit. Deliberate, documented deviation from the terse contract wording
("rotate the kit"); actual phrase rotation is `rotateRecoveryPhrase(currentPassword)`, which
generates and returns a fresh 12-word phrase and replaces only the recovery envelope. Old
phrases stop working after rotation; old backups are unaffected because each backup carries
its own key envelopes.

### biometric unlock

Two-phase, because a per-use-auth Keystore key cannot encrypt/decrypt without a
`BiometricPrompt`-authorised cipher:

- enable (while unlocked): `prepareBiometricEnroll()` → UI shows the prompt with
  `BiometricEnroll.cipher` → `completeBiometricEnroll(enroll)` calls
  `BiometricKeyManager.completeEnroll(enroll, vmk)` (wraps the in-memory VMK under the
  Keystore key) and sets `biometric_enabled`.
- unlock: `prepareBiometricDecrypt()` → prompt → `completeBiometricUnlock(decrypt)` returns
  `BIOMETRIC_FAILED` on any failure, otherwise installs the unwrapped VMK and loads the
  index.
- Keystore invalidation (biometric re-enrolment, lock-screen reset): crypto-core wipes the
  stale wrap and returns null from `prepareDecrypt()`; the repository clears
  `biometric_enabled` and the UI falls back to the password path.
- `disableBiometricUnlock()` deletes the Keystore key + wrapped VMK copy (best effort) and
  clears the flag.

## 6. Mutations and derived state

All mutations run under the repository mutex and update the DB row plus the in-memory index
before publishing new `StateFlow` values. Attachment mutations happen through the dedicated
four-method attachment API (see [ATTACHMENTS.md](ATTACHMENTS.md) §5); trashing keeps blobs,
delete-forever/empty-trash removes them, and an unlock-time sweep removes unreferenced blobs
(only when every row decoded).

| Operation | Envelope change | Extra |
|---|---|---|
| `createItem` | new row, `version = 0`, empty content | validates `folderId` if given. |
| `saveItem` | re-encrypt full payload | `updatedAt = max(now, previous + 1)`; `version = max(storedVersion, incomingVersion) + 1` (monotonic; last-write-wins; UI compares versions for conflict UI). Tags normalised. A stale `folderId` (folder deleted while editing) falls back to root instead of failing the autosave. |
| `trashItem` / `restoreItem` | flag bit toggled | via `saveItem` semantics (version/timestamp bump). |
| `deleteItemForever` / `emptyTrash` | row(s) deleted | index entries removed. |
| `setPinned` / `setFavorite` / `moveItem` | flag/folder in payload | `moveItem` rejects an unknown folder id; pin/favourite no-op when unchanged. |
| `setItemLocked` / `setFolderLocked` | lock byte in the payload (v3 / v2) | Repository-owned flag (a stale `saveItem` cannot change it). Same VMK/AAD, no other row touched; no-op when unchanged; drops that id from `verifiedLockedIds`. |
| `createFolder` | new row | `sortOrder = max(sibling sortOrder + 1)`; parent must exist. |
| `renameFolder` | name in payload | blank name rejected; no-op if unchanged. |
| `deleteFolder` | row deleted | **children are never deleted implicitly**: child folders are re-encrypted with `parentId` = deleted folder's parent, items are re-saved with `folderId` = that parent (root when the deleted folder was a root). |

`Item.updatedAt`/`Item.createdAt` are authoritative inside the authenticated payload and are
cross-checked against the structural columns on load; a mismatch is treated as corruption.

## 7. Locked notes & folders (visibility gate, decision #15)

`Item.locked` / `Folder.locked` are the SPEC "lock note / lock folder" feature. They are a
**visibility gate, not a second encryption layer**: the content is already sealed under the
vault VMK with a per-record AAD, so locking only rewrites the record's own envelope with the
lock byte appended (`ItemCodec` v3 / `FolderCodec` v2). The VMK, the wrapped-key envelopes and
every other row are untouched — context-menu lock/unlock is a single row write, not a key
operation.

### Repository API

| Member | Semantics |
|---|---|
| `setItemLocked(id, locked)` | Persists the flag inside the item envelope. Returns false when the vault is locked or the id is unknown; true when saved or already in the requested state. Same VMK, same `CryptoAad.item(id, kind)` — no re-keying. |
| `setFolderLocked(id, locked)` | Same for the folder envelope (`FolderCodec` v2, AAD pseudo-kind `FOLDER`). Descendants are not rewritten; hiding them is a UI concern. |
| `verifyMasterPassword(password)` | Re-authentication for locked content. Unwraps the stored **password** envelope with the supplied password and compares the VMK to the live one in constant time; the session (status, VMK, index) is unchanged either way. The `CharArray` is wiped on every path. Wrong/blank ⇒ false. Rate limited via the injected clock: failures 1–4 delay the next attempt by 1 s, 2 s, 3 s, 4 s; the 5th consecutive failure opens a 30 s cooldown during which the KDF is not even attempted; success resets the ladder. Limiter state is memory-only and reset when a session starts/ends. Envelope corruption is indistinguishable from a wrong password. |
| `verifiedLockedIds: StateFlow<Set<String>>` | Notes **and** folders whose locked content may be revealed in this session. Memory-only, never persisted. |
| `markLockVerified(id)` | Adds an id after a successful biometric/password re-auth. Ignored while the vault is locked (a verification can never survive into the next session). |
| `clearLockVerifications()` | Re-masks everything ("re-arm locked notes now"). |

### Clearing rules (exact)

`verifiedLockedIds` starts empty and is cleared by:

- `lock()` (manual lock, idle auto-lock, screen-off/background lock, biometric-invalidation lock);
- `close()`;
- `onAppBackgrounded()` **when it locks immediately** — a background lock that actually applies;
- `endExternalActivity()` when it settles a deferred background by locking;
- `setupVault()` / `unlock()` (every new session starts unverified).

It is deliberately **not** cleared when `beginExternalActivity()` is active: that background
was caused by the system SAF picker (decision #13), the vault stays unlocked, and the user did
not leave the app. It is also not cleared by `onAppForegrounded()` (the session continues) or
by a failed `verifyMasterPassword`. Deleting an item/folder or flipping its lock state drops
that id as well (hygiene; a re-locked record must be re-verified).

### Search, counts and rendering

The repository still exposes locked records in `items`/`folders` (the `locked` flag is there so
the UI can draw the mask). It does not hide them, because hiding has to be consistent across
the list, search, counts, previews and deep links, and that decision belongs next to the render
state. **Contract for the UI:** a locked record whose id is absent from `verifiedLockedIds`
must never contribute its title, body, tags, folder path or preview to search results, folder
lists, counts (including `ItemQueries.tagCounts`/word counts) or the editor; it is rendered as
a mask ("Locked note", lock icon) and `ItemQueries.filter`/`matchesQuery` must be fed the
already-masked list (or the masked `Item` copy), never the raw decrypted record.

## 8. Index loading and corruption policy

On unlock, every row is decrypted and decoded. A row is **corrupt** (skipped, counted, never
surfaced, never crashes the vault) when any of the following holds:

- `kind` is not a known `ItemKind` name;
- `ciphertext` is too short / fails GCM authentication (wrong row id/kind, tampering, wrong VMK);
- the payload fails `ItemCodec`/`FolderCodec` bounds/version/flags checks;
- the payload's id/kind/timestamps disagree with the plaintext row columns.

`corruptRowCount` (internal, for diagnostics/tests) reports how many rows were skipped in
the last load. The valid rows are published; the UI never receives partial/suspect content.
If the vault-meta envelopes themselves are unreadable the unlock returns `CORRUPT_VAULT` and
the vault stays locked (the DB file remains untouched, so a future version can attempt
repair).

## 9. Backup import/export effects on the model

Backup envelopes reuse `ItemCodec`/`FolderCodec` inside a doubly-sealed container (see
[BACKUP-FORMAT.md](BACKUP-FORMAT.md)). Import re-encrypts every accepted record under the
**local** vault's VMK and AAD, so rows restored from another vault are indistinguishable
from locally created ones. `BackupImportReport` counts:

| Field | Meaning |
|---|---|
| `imported` | New item ids written (not previously present, not repeated in the file). |
| `duplicated` | Records already present with the same identity — items with same `id` **and** same `updatedAt`; folders with the same `id`; repeated records inside one file. |
| `skipped` | Conflicts/bad records: same item `id` with a *different* `updatedAt` (never overwritten), or a record that could not be re-encrypted/encoded. |
| `foldersImported` | New folder ids written. |
| `attachmentsImported` | v2 attachment blobs restored (decrypted from the source VMK, SHA-256-verified, re-encrypted under the local VMK). |
| `attachmentsSkipped` | v2 attachment refs that could not be restored (missing/invalid blob record, SHA-256/size mismatch, no matching local ref, unsafe id). The note itself is still restored. Zero for v1 backups. |

The whole file is authenticated before any write, and all writes happen in one Room
transaction — an import is all-or-nothing for the accepted set.

Backups embed the current item/folder payloads, so the lock flags travel **inside** the
authenticated records: a restored locked note/folder stays locked, and a restore never
creates a verification (`verifiedLockedIds` is memory-only and per-session). A v1/v2
backup from an older build decodes with `locked = false`.

## 10. Related pure-model helpers (not persisted)

`model/ItemQueries.kt` provides the read-side logic shared by the UI: title/preview
derivation (Markdown-marker stripping), word counts, case-insensitive search over the
decrypted fields, folder/tag/pin/favourite/trash filtering, deterministic sorting
(`RECENT`, `CREATED`, `TITLE`, pinned/favourites first), tag aggregation
(count desc, name asc), trash retention (`DEFAULT_TRASH_RETENTION_DAYS = 30`,
`trashExpiry`, `isTrashExpired`, `expiredTrashIds`) and cycle-safe folder paths/trees.

`model/MarkdownCodec.kt` (`MarkdownBlockCodec`) is the canonical Markdown ⇄ `BlockDocument`
codec for notes: headings 1–6, paragraphs (inline Markdown kept verbatim), bullets,
numbered lists (renumbered canonically), checklists, fenced code (fence grows past inner
backtick runs), quotes, rules and pipe tables. Parse never throws on arbitrary input and
`parse(render(parse(md))) == parse(md)` is fuzz-tested; unknown constructs degrade to
paragraph text.
