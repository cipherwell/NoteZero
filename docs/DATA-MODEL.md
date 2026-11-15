# NoteZero — data model and encrypted storage format

**Owner:** data layer. This document is normative for the on-disk format: changing a byte
layout, AAD string or column here is a breaking change and requires a schema/envelope
version bump. Crypto primitives are specified in [CRYPTO-SPEC.md](CRYPTO-SPEC.md); the
backup wire format has its own document, [BACKUP-FORMAT.md](BACKUP-FORMAT.md).

The design rule is simple: **the database stores structure, never content.** Anything a
user typed (title, body, tags, folder name, parent, pin/favourite/trash flags) is sealed
inside a per-record AES-256-GCM envelope. The only plaintext fields are random ids, an item
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

## 2. `ItemCodec` envelope payload

Versioned big-endian binary payload stored **inside** the item ciphertext. Decoding is
strict: unknown magic/version/flags, truncation, oversized fields or trailing bytes return
`null` and the row is treated as corrupt. Limits are enforced on both encode and decode.

```
offset size field
0      4    magic "NZIT"
4      1    envelope version = 1
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
```

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
4      1    envelope version = 1
5      1    flags: bit0 parentId present (other bits 0)
6      8    createdAt  (i64 epoch millis)
14     4    sortOrder  (i32, siblings ordering hint)
18     2    idLen      (u16, 1..128)
20     n    id         (UTF-8)
        4   nameLen    (u32, <= 8 KiB)
        n   name       (UTF-8)
        2   parentIdLen (u16, <= 128) — only when flags.bit0 is set
```

## 4. AAD map (what the ciphertext is bound to)

| Envelope | AES key | AAD | Effect of a mismatch |
|---|---|---|---|
| item | VMK | `CryptoAad.item(rowId, rowKind)` = `"notezero.item.v1|<id>|<KIND>"` | `decrypt` returns null → row skipped+counted; cross-row / cross-kind swaps fail. |
| folder | VMK | `CryptoAad.item(folderId, "FOLDER")` (same domain string, pseudo-kind token) | same |
| password-wrapped VMK | KEK(password) | `CryptoAad.VMK_WRAP` = `"notezero.vmk.v1"` | unwrap fails → `WRONG_PASSWORD`. |
| recovery-wrapped VMK | KEK(phrase) | `CryptoAad.RECOVERY_WRAP` = `"notezero.recovery.v1"` | unwrap fails → `WRONG_RECOVERY_PHRASE`. |
| backup extra-password envelope | KEK(backup password) | `CryptoAad.BACKUP` = `"notezero.backup.v1"` | unwrap fails → import returns null. |
| backup payload | source VMK | `CryptoAad.BACKUP` `||` complete backup header bytes | tampered header/payload → AEAD failure. |

## 5. Vault lifecycle semantics

State is `VaultStatus.SETUP_REQUIRED → UNLOCKED ⇄ LOCKED`. The VMK is a 32-byte
`ByteArray` field, memory-only; it is zeroized (`NoteCrypto.wipe`, hard `fill(0)` fallback)
on `lock()`/`close()`.

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
before publishing new `StateFlow` values.

| Operation | Envelope change | Extra |
|---|---|---|
| `createItem` | new row, `version = 0`, empty content | validates `folderId` if given. |
| `saveItem` | re-encrypt full payload | `updatedAt = max(now, previous + 1)`; `version = max(storedVersion, incomingVersion) + 1` (monotonic; last-write-wins; UI compares versions for conflict UI). Tags normalised. A stale `folderId` (folder deleted while editing) falls back to root instead of failing the autosave. |
| `trashItem` / `restoreItem` | flag bit toggled | via `saveItem` semantics (version/timestamp bump). |
| `deleteItemForever` / `emptyTrash` | row(s) deleted | index entries removed. |
| `setPinned` / `setFavorite` / `moveItem` | flag/folder in payload | `moveItem` rejects an unknown folder id; pin/favourite no-op when unchanged. |
| `createFolder` | new row | `sortOrder = max(sibling sortOrder + 1)`; parent must exist. |
| `renameFolder` | name in payload | blank name rejected; no-op if unchanged. |
| `deleteFolder` | row deleted | **children are never deleted implicitly**: child folders are re-encrypted with `parentId` = deleted folder's parent, items are re-saved with `folderId` = that parent (root when the deleted folder was a root). |

`Item.updatedAt`/`Item.createdAt` are authoritative inside the authenticated payload and are
cross-checked against the structural columns on load; a mismatch is treated as corruption.

## 7. Index loading and corruption policy

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

## 8. Backup import/export effects on the model

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

The whole file is authenticated before any write, and all writes happen in one Room
transaction — an import is all-or-nothing for the accepted set.

## 9. Related pure-model helpers (not persisted)

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
