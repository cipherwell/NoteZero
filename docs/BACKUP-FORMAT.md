# NoteZero — encrypted backup envelope format (v1 and v2)

**Owner:** data layer. Implementation: `app/src/main/java/com/cipherwell/notezero/backup/BackupFormat.kt`
(wire format) + `EncryptedNoteRepository.exportBackup`/`importBackup` (credential handling,
duplicate policy, atomic restore). Tests: `app/src/test/java/com/cipherwell/notezero/data/BackupFormatTest.kt`,
the backup cases in `EncryptedNoteRepositoryTest.kt` and
`EncryptedNoteRepositoryAttachmentsTest.kt` (v2 attachments + frozen v1 fixture import).

The writer emits **format v2** (items + folders + attachment blobs); the reader accepts
**v1** (pre-attachments backups) and **v2**. Attachment storage itself is documented in
[ATTACHMENTS.md](ATTACHMENTS.md).

The format is a self-contained, versioned, authenticated binary envelope designed for SAF:
export writes to an `OutputStream`, import reads one `InputStream`. No Android type is
referenced in `backup/` and the reader needs no seeking, so a `content://` pipe works
unchanged. The file can be stored/synced by any external tool; it is useless without the
vault password, the recovery phrase or the backup password.

## 1. Security model

- The payload contains the **decrypted** items and folders (exactly the in-memory model) plus
  the attachment blobs, and is sealed with AES-256-GCM under the source vault's VMK. Each
  attachment blob is **additionally** still its own `NZAT` envelope sealed under the source
  VMK with `CryptoAad.attachment(noteId, attachmentId)`, so a blob stays bound to its
  note/id inside the payload and the importer can verify its SHA-256 before re-encrypting it
  under the destination vault's VMK.
- AAD = `CryptoAad.BACKUP` (`"notezero.backup.v1"`, UTF-8) followed by the **entire header
  byte sequence** (fixed header + embedded key-envelope block). Every header field is
  therefore authenticated: changing a count, mode, flag or envelope blob makes payload
  decryption fail instead of producing a partially-trusted file.
- Only *wrapped* key material is in the plaintext header: `KeyEnvelope` blobs wrapping the
  VMK under the source password / source recovery phrase, or under a backup password. The
  VMK itself is never present.
- Nothing is written to the destination vault until the whole file has been read,
  authenticated, decoded and validated. The write is one Room transaction.
- Reader is hostile-input safe: bounded allocations, strict length checks, no trailing
  bytes, no exceptions escape (`read`/`decrypt` return `null`).

## 2. Wire layout (big-endian)

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│ off  size  field                                                                        │
│ 0    4     magic "NZBK"                                                                 │
│ 4    1     formatVersion = 2   (writer; a reader also accepts legacy 1)                 │
│ 5    1     mode: 0 = MODE_VAULT_KEY, 1 = MODE_EXTRA_PASSWORD                            │
│ 6    1     flags: bit0 password envelope present, bit1 recovery envelope present        │
│ 7    1     reserved = 0                                                                 │
│ 8    8     exportedAt  (i64 epoch millis, informational)                                │
│ 16   4     itemCount   (u32, must match the payload)                                    │
│ 20   4     folderCount (u32, must match the payload)                                    │
│ 24   4     attachmentCount (u32, v2 only, must match the payload)                       │
│ 28   4     envelopeBlockLen (u32, <= 16 KiB)                                            │
│ 32   n     envelope block:                                                              │
│             count u8 (<= 4)                                                             │
│             per envelope: len u16 (<= KeyEnvelope.MAX_BYTES = 1024) + KeyEnvelope bytes │
└─────────────────────────────────────────────────────────────────────────────────────────┘
    4        payloadLen (u32)
    m        sealed payload = iv(12) || AES-256-GCM ciphertext || tag(16)
```

A **v1** file (`formatVersion = 1`, still read) has the same layout without the
`attachmentCount` field: `envelopeBlockLen` sits at offset 24 and the fixed header is 28
bytes. The v2 writer always emits `formatVersion = 2` (audit D6).

Total header = `32 + envelopeBlockLen` for v2 (`28 + envelopeBlockLen` for a read v1 file,
which has no `attachmentCount`). The AAD is exactly the format domain string
`"notezero.backup.v1".bytes || file[0 until (fixedHeaderBytes + envelopeBlockLen)]`, so every
header field — including the v2 attachment count — is authenticated. Note the AAD domain
string keeps its `v1` suffix in format v2: it names the AAD domain, not the file version.

`payloadLen` is *not* part of the AAD, but the reader requires the stream to end exactly at
`payloadLen` bytes; truncation, appended bytes or a rewritten length fail the structural
parse (`read` returns null), and any change to the sealed payload fails GCM authentication
(`decrypt` returns null). Either way, nothing is imported.

### Decrypted payload layout (v2)

```
u8   payloadVersion = 2
u32  itemCount            (must equal the header's authenticated itemCount)
u32  folderCount          (must equal the header's authenticated folderCount)
u32  attachmentCount      (must equal the header's authenticated attachmentCount)
repeat itemCount:   u32 encodedLen + ItemCodec payload (self-versioned; currently v3, docs/DATA-MODEL.md §2)
repeat folderCount: u32 encodedLen + FolderCodec payload (docs/DATA-MODEL.md §3)
repeat attachmentCount:
    u16 noteIdLen       (1..128) + noteId UTF-8
    u16 attachmentIdLen (1..128) + attachmentId UTF-8
    u32 blobLen         (MIN_BLOB_BYTES..AttachmentBlob.MAX_BLOB_BYTES)
    blobLen bytes       NZAT blob (magic+version+AES-GCM under the source VMK + attachment AAD)
```

A read **v1** file has `payloadVersion = 1` and no `attachmentCount` field at all, and its
item payloads are ItemCodec v1 (no attachment list): it imports with
`attachmentsImported = attachmentsSkipped = 0`. A v1 payload presented under a v2 header (or
a count mismatch) is rejected.

All lengths are bounds-checked against `ItemCodec`/`FolderCodec`/attachment field limits and
the whole payload against the file size cap (empty payloads are allowed: a valid, empty
vault backups to `itemCount = folderCount = attachmentCount = 0`).

## 3. Modes and credentials

| Mode | Header key material | Payload key | Intended restore credential |
|---|---|---|---|
| `MODE_VAULT_KEY` (0) | the source vault's own `KeyEnvelope(PASSWORD)` **and** `KeyEnvelope(RECOVERY)` from `vault_meta` | source VMK | **same-vault restore:** no credential (local VMK matches). **Cross-device/cross-vault restore:** the source master password **or** the source 12-word recovery phrase. |
| `MODE_EXTRA_PASSWORD` (1) | exactly one `KeyEnvelope(PASSWORD)` wrapping the source VMK under a password chosen for this file (fresh 16-byte salt; PBKDF2-HMAC-SHA256 at the exporting app's `kdfIterations`; wrap AAD = `CryptoAad.BACKUP`) | source VMK | the backup password. |

`exportBackup(out, password = null)` produces `MODE_VAULT_KEY` — this is the "phrase-only"
mode of the UI contract (`NoteRepository` KDoc): the envelope is not additionally
password-protected and carries both source key wrappers so either credential works. One of
the two embedded envelopes may be absent in files produced by future versions; at least one
is required by the writer/reader validation.

`exportBackup(out, password)` produces `MODE_EXTRA_PASSWORD`. The VMK is still the payload
key (so a restore on the same vault needs no password); the embedded envelope is only a
transport wrapper for the backup password. `password` is consumed and zeroized by the
caller-side KDF.

The header embeds only standard `KeyEnvelope` blobs (documented in CRYPTO-SPEC §6) —
type/version/KDF/iterations/salt/wrapped-VMK — so the parsing rules and iteration bounds
(`10_000 .. 10_000_000`) are identical to the live vault meta envelopes.

### Item kinds, reading state and books

Items are kind-agnostic on the wire: the kind lives **inside** the encrypted `ItemCodec` payload
(ordinal 0 = `NOTE`, 1 = `CODE_FILE`, 2 = `PDF_FILE`, 3 = `BOOK_FILE`), so backup v1/v2 files keep
importing into this build and a `PDF_FILE`/`BOOK_FILE` restores exactly like any other item — its
single `application/pdf` or `application/epub+zip` attachment travels as an `NZAT` blob (layout in
[ATTACHMENTS.md](ATTACHMENTS.md)) and is re-encrypted under the destination vault key during
import. Restore never inspects or renders the document; duplicate-safe id matching and the
missing/invalid-blob skip rules are unchanged. Item payload **v4** also carries the encrypted
reading state (position/progress/bookmarks/book metadata) inside the same ciphertext, so a
restored PDF or book resumes exactly where it was left on the source vault; older (v1–v3)
payloads decode with no reading state.

## 4. Import algorithm (implemented by `EncryptedNoteRepository.importBackup`)

Preconditions: the destination vault must be **unlocked** (imports are re-encrypted under
the local VMK and need the local AAD). Locked ⇒ `null`. The `password` array, when present,
is consumed/zeroized by the import.

1. Parse + structurally validate the envelope (`BackupFormat.read`). Any failure ⇒ `null`.
2. Resolve a VMK that can open the payload, in order:
   1. the current in-memory VMK — proves a same-vault backup and needs no credential;
   2. if a credential was supplied:
      - `MODE_EXTRA_PASSWORD`: unwrap the embedded `PASSWORD` envelope using the credential
        as the backup password (AAD `BACKUP`);
      - `MODE_VAULT_KEY`: if the credential is exactly 12 valid BIP-39 words, unwrap the
        embedded `RECOVERY` envelope (AAD `RECOVERY_WRAP`); otherwise treat it as the source
        master password and unwrap the embedded `PASSWORD` envelope (AAD `VMK_WRAP`).
   3. no credential / nothing matched ⇒ `null` (indistinguishable from "wrong credential",
      by design).
3. Decrypt + decode the payload (`ItemCodec`/`FolderCodec` strict decode). Any failure ⇒
   `null`; nothing is written.
4. Classify every item/folder record **against the current index and against earlier
   records in the same file**:
   - item id not present: import (one row, re-encrypted under the local VMK + `CryptoAad.item`);
   - item id present with **equal `updatedAt`**: duplicate — counted, never rewritten;
   - item id present with **different `updatedAt`**: conflict — counted as skipped, never
     overwritten (neither newer nor older is written; the destination vault always wins);
   - same id appearing twice in one file: duplicate;
   - folder id already present or repeated in the file: duplicate;
   - record that cannot be re-encoded/re-encrypted (defensive): skipped.
5. **Attachment blobs (v2 only).** Each blob record is matched to the target item in the
   final index (imported or already-present duplicate) and that item's attachment ref with
   the same id:
   - existing `<id>.nzb` already present → counted as duplicate, never rewritten;
   - repeated blob record in one file → duplicate;
   - no target item / no matching ref / unsafe id / missing or invalid blob → counted as
     `attachmentsSkipped` (the note is still restored — one bad attachment never fails it);
   - otherwise: decrypt the inner `NZAT` blob with the **source** VMK (the one that opened
     the payload) + attachment AAD, verify plaintext size and SHA-256 against the ref,
     re-encrypt under the **local** VMK/AAD and write a temp file.
   Attachment refs that travel in the item payload but have no blob record in the file (the
   source could not read that blob at export time) are counted as `attachmentsSkipped`.
6. Write all accepted item/folder rows in a single Room transaction, then rename the
   re-encrypted blob temp files into `attachments/`. A DB failure deletes the temp files and
   aborts the import (`null`), so nothing partial is restored. Then update the in-memory index
   and flows. Return `BackupImportReport(imported, duplicated, skipped, foldersImported,
   attachmentsImported, attachmentsSkipped)` (the attachment counts are 0 for v1 files).

Consequences: importing the same file twice is a no-op (`imported = 0`; items with unchanged
`updatedAt` count as duplicates). Deleting an item and re-importing the same backup restores
it. Items edited after the backup was taken are preserved (counted as skipped), so backups
can never silently roll back newer local work.

## 5. Failure modes (all return null / false, never throw to the UI)

| Input | Result |
|---|---|
| empty / arbitrary / non-`NZBK` stream | `read` ⇒ null |
| truncated stream, bytes appended after payload, wrong `payloadLen` | `read` ⇒ null |
| unknown format version, mode, flag bit or non-zero reserved byte | `read` ⇒ null |
| header count/envelope tamper, embedded envelope tamper | parses structurally, `decrypt`/AAD ⇒ null |
| oversized header count / envelope block (`> 16 KiB`) / file (`> 64 MiB`) | null (stream cap, not an OOM guarantee — see below) |
| `OutOfMemoryError` while reading/decoding the payload (hostile or simply large file on a small heap) | `read`/`decrypt` ⇒ null; `importBackup` ⇒ null |
| payload byte/tag tamper, wrong VMK, cross-vault without credential | `decrypt` ⇒ null |
| wrong backup password / wrong source password / wrong source phrase | `importBackup` ⇒ null |
| import into a locked vault | `importBackup` ⇒ null |
| export while locked, or any IO/encode failure | `exportBackup` ⇒ false |
| `OutOfMemoryError` while encoding/sealing the payload on export | `BackupFormat.write` converts it to an `IllegalStateException`; `exportBackup` ⇒ false (no partial file: everything is encoded before the first byte is written) |
| v2 blob tampered / inner AAD swap / SHA-256 or size mismatch | that attachment skipped+counted; the rest of the import proceeds |
| v2 blob record for an item/ref not in the target index | skipped+counted (note still imported) |
| v2 file whose attachment count exceeds the header/format limit, or header/payload count mismatch | `read` ⇒ null / `decrypt` ⇒ null |

## 6. Limits and versioning

| Limit | Value |
|---|---|
| Max total stream size accepted | 64 MiB (`MAX_BACKUP_BYTES`); attachment blobs count toward it |
| Max attachment blobs in one backup | 100 000 |
| Max one attachment blob | `AttachmentBlob.MAX_BLOB_BYTES` = 10,485,793 bytes (10 MiB plaintext) |
| Max header (fixed + envelope block) | 16 KiB (`MAX_HEADER_BYTES`) |
| Max embedded key envelopes | 4 |
| Max one `KeyEnvelope` | 1024 bytes (`KeyEnvelope.MAX_BYTES`) |
| Max items / folders in one backup | 500 000 / 100 000 |
| Payload version | 2 (v1 still read) |
| Envelope format version | 2 (v1 still read) |

**Peak-memory note (audit F9):** the 64 MiB `MAX_BACKUP_BYTES` cap bounds the *stream*, not
the heap. Reading materialises the file, then the payload copy, then the GCM plaintext, then
up to 500 000 decoded records; export similarly holds the attachment ciphertext blobs plus the
plaintext payload plus the sealed copy. Both boundaries now catch `OutOfMemoryError` and fail
cleanly (`read`/`decrypt`/`importBackup` ⇒ null, `exportBackup` ⇒ false) instead of crashing
the process, but the cap must not be described as an "OOM guard". The real fix is the chunked,
streamed payload of format v3 (decision #14 deferred item).

Rules for future versions: bump `FORMAT_VERSION` (or `payloadVersion`) for any change to
field layout or meaning; add new flags/modes only with a documented mapping, because current
readers reject unknown values; never change a published AAD string (create a new domain
string instead). v1 readers reject every v2 file with `null` rather than misinterpret it;
this build's reader accepts both. The 64 MiB stream cap is also the practical size ceiling
for a v2 backup: a vault whose attachment bytes no longer fit fails the export cleanly
(`false`, no partial file) rather than dropping attachments.

### Format-v1 compatibility matrix

| Reader | v1 file | v2 file |
|---|---|---|
| Phase 1–3 build (v1 reader) | imports | rejected (`null`) |
| this build (v2 reader) | imports, 0 attachment counts | imports items/folders/refs + blobs |

The frozen-v1 wire layout is `LegacyBackupFixture` in the tests (`writeV1`): 28-byte header
without `attachmentCount`, payloadVersion 1, ItemCodec v1 records.

## 7. Interplay with the vault lifecycle

- Backups embed the *current* key envelopes, so a backup keeps opening with the
  password/phrase that was valid **when it was taken**, even after the live vault changes
  password or rotates its recovery kit (old backups are not re-encrypted).
- The payload is sealed under the source VMK, which never changes on password change — an
  "extra password" backup of the same vault remains importable without the backup password
  on that vault (local VMK shortcut), which is intended for re-import/undo flows.
- Backups carry no plaintext titles, bodies, tags, folder names or vault id; a loss of the
  backup file leaks nothing beyond its existence. Losing every credential for the source
  vault makes the backup unrecoverable (zero-knowledge, by design).


## 8. UI flow (SAF) and lock handling

Implementation: `ui/backup/BackupFlowController.kt` (state machine, no Android types),
`ui/backup/BackupFlowViewModel.kt` (Activity-scoped owner of the pending URI) and
`ui/backup/BackupSection.kt` + `ui/screens/settings/SettingsScreen.kt` ("Data & backup").

- **Export.** The user may opt into a separate backup password (password + confirmation).
  Tapping "Export encrypted backup" calls `startExport(mode, password?)`, which arms
  `repository.beginExternalActivity()` and only then launches SAF `CreateDocument` (MIME
  `application/octet-stream`, suggested name `notezero-backup-YYYYMMDD-HHmm.nzbk`). When the
  picker result arrives the controller calls `repository.endExternalActivity()` and then
  `exportBackup(stream, passwordOrNull)`: `EXPORT_VAULT_KEY` passes `null` (the envelope
  embeds the source password/recovery wrappers), `EXPORT_PASSWORD` passes the chosen password
  and wipes that `CharArray` once the write finishes. The UI only resolves the `content://`
  URI to a `ContentResolver` stream; it never sees plaintext.
- **Import.** Launches SAF `OpenDocument` and tries `importBackup(stream, null)` first
  (same-vault backups restore through the local-VMK shortcut, no credential). A `null`
  result is refined with a structural `BackupFormat.read` probe: not an envelope ⇒
  `INVALID_FILE`; valid envelope from another vault ⇒ one prompt accepting the **source
  master password or the source 12-word phrase** (words joined with single spaces; the
  repository auto-detects the phrase form), retried with a freshly opened stream and capped
  at 3 attempts ⇒ `WRONG_CREDENTIAL`. The non-secret `BackupImportReport` counts are shown.
  Nothing is written unless the whole file authenticated and decoded.
- **Resume-after-unlock rule.** The picker stops the Activity, which normally locks the
  vault; `beginExternalActivity`/`endExternalActivity` defer exactly that lock (mechanism in
  [ARCHITECTURE.md](ARCHITECTURE.md) §5). If the vault is nevertheless LOCKED when the
  picker result arrives (idle timeout while the picker was open), the controller keeps the
  pending mode + URI in the Activity-scoped ViewModel, surfaces `Error(LOCKED)` ("Unlock to
  resume — your chosen file is still selected") and resumes through
  `onVaultUnlocked()` after the user unlocks. The chosen file is never silently dropped and
  the user does not have to pick it again.
- **Attachment counts**: the v2 `BackupImportReport` fields `attachmentsImported` /
  `attachmentsSkipped` are non-secret counts; a restore with a note whose blobs were missing
  at export time shows the note plus a skipped-attachment count. Attachment bytes are
  restored by the repository, never by the UI.
- **Errors** are `BackupErrorKind` enums only: `INVALID_FILE` (the picked file is not a
  structurally valid v1 envelope), `WRONG_CREDENTIAL` (3 rejected attempts), `IO` (stream or
  picker failure), `LOCKED` (paused until unlock) and `EMPTY_VAULT` (export refused because
  the vault has no notes or folders). No path, file name, title, content or credential is
  ever logged or rendered by this UI.
