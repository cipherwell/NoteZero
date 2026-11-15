# NoteZero — encrypted attachments (Phase 4)

**Owner:** data layer. This document is normative for attachment storage, integrity,
lifecycle and backup. Implementation:
`data/AttachmentBlob.kt` (pure-JVM `NZAT` format), `data/NoteRepository.kt` (API types),
`data/EncryptedNoteRepository.kt` (storage/quota/lifecycle/backup), `backup/BackupFormat.kt`
(v2 backup wire format). Tests: `data/AttachmentBlobTest`, `data/PayloadCodecTest`,
`data/EncryptedNoteRepositoryAttachmentsTest`, `data/BackupFormatTest`.

Attachments are optional encrypted blobs (images, files) attached to an item. Phase 4 ships
storage + backup only; the editor UI (`ui/attachments/`, owner: attachments-ui) consumes the
repository contract in §7. **No new dependencies, no network, no plaintext on disk.**

## 1. Design in one paragraph

A note's attachment **metadata** (id, display name, MIME type, plaintext size, createdAt,
SHA-256) lives inside the note's own AES-256-GCM envelope (item payload version 2), so names
and sizes leak nothing. The **bytes** live in app-private `filesDir/attachments/<id>.nzb` as
their own AES-256-GCM envelope under the vault VMK, AAD-bound to the note and attachment id.
Every read authenticates the blob and re-verifies the SHA-256 of the decrypted plaintext
before returning it. Import enforces the 10 MiB file limit while reading, the 100-per-note
count and 512 MiB vault quota before writing, writes through a temp file + rename, and only
then adds the ref to the note envelope — a failed import leaves neither an orphan blob nor a
ref. Trash keeps blobs; delete-forever/empty-trash remove them; an unlock-time sweep removes
unreferenced blobs and interrupted temp files, but only when the whole vault loaded cleanly.

## 2. On-disk blob format (`<attachmentId>.nzb`)

```
offset size field
0      4    magic "NZAT"
4      1    blob format version = 1
5      12   GCM IV (random per write)
17     n    AES-256-GCM ciphertext (n = plaintext length)
17+n   16   GCM tag
```

- Key: the vault VMK (`NoteCrypto.secretKey(vmk)`); the VMK itself is never persisted.
- AAD: `CryptoAad.attachment(noteId, attachmentId)` =
  `"notezero.attachment.v1|<noteId>|<attachmentId>"` (UTF-8). Swapping blob files between
  notes or ids fails authentication; so does copying a blob to a different vault (different
  VMK).
- The file name is the random attachment id only — never the display name, and the display
  name/MIME/size are **not** stored in the file.
- On read: file size is bounds-checked against the format (`MIN_BLOB_BYTES..MAX_BLOB_BYTES`),
  magic/version checked, AEAD authenticated, then plaintext length must equal the ref's
  `sizeBytes` **and** `sha256(plaintext)` must equal the ref's `sha256`. Any mismatch is
  counted (`attachmentFailureCount` diagnostic) and treated as `INTEGRITY`: `null` is
  returned, the bytes are never surfaced or logged.
- No plaintext bytes, thumbnails, display names or decoded content are ever written by the
  data layer. Files are written as ciphertext only (temp file + atomic rename).
- IDs used in file paths are validated (`[A-Za-z0-9_-]{1,64}`) before touching the filesystem,
  so a crafted/imported ref cannot path-traverse out of `attachments/`.

Blob size bound: `MAX_BLOB_BYTES = 5 + 12 + 10 MiB + 16` = 10,485,793 bytes.

## 3. Item payload v2 (now v3 after the lock byte; payloads are self-versioned) (attachment metadata)

`ItemCodec` payload **version 2** appends, after the folder-id field:

```
2    attachmentCount (u16, <= 100)
per attachment:
  2   idLen (1..128) + id UTF-8
  4   displayNameLen (1..1024) + display name UTF-8
  2   mimeTypeLen (1..256) + MIME type UTF-8
  8   sizeBytes (i64, 0..10 MiB)
  8   createdAt (i64 epoch millis)
  32  raw SHA-256 (model holds the lowercase 64-char hex string)
```

Version 2 is written from now on. **Version 1 payloads still decode** to an item with an
empty attachment list — no migration, no re-encryption; the next save writes v2. Unknown
versions, a version-1 payload with trailing bytes, oversized counts/fields/bad hex or any
truncation fail closed (row skipped+counted, never plaintext). Payload version table:

| payload version | written by | attachments | reader support |
|---|---|---|---|
| 1 | Phase 1–3 builds | none (empty list) | yes (until a future major format change) |
| 2 | this build | metadata list | yes |

**Attachment refs are repository-owned metadata.** `saveItem` keeps the stored list; only
`importAttachment`/`deleteAttachment` (and backup restore) mutate it. This makes editor
autosave races safe: an autosave with a stale `Item` copy can neither add a phantom ref nor
drop an existing one.

## 4. Limits and failure kinds

| Limit | Value | Enforcement |
|---|---|---|
| Max plaintext per attachment | 10 MiB (`AttachmentLimits.MAX_ATTACHMENT_BYTES`) | while streaming the source (`InputStream`); over-limit bytes are never buffered → `TOO_LARGE` |
| Max attachments per item | 100 (`MAX_ATTACHMENTS_PER_NOTE`) | before reading the source → `QUOTA_EXCEEDED` |
| Max total referenced plaintext bytes | 512 MiB (`MAX_TOTAL_BYTES`) | before writing → `QUOTA_EXCEEDED` |
| Filename length for display name/MIME | 1024 / 256 UTF-8 bytes | trimmed/truncated to the payload budget; blank → `"attachment"` / `"application/octet-stream"` |

`AttachmentError`: `TOO_LARGE`, `QUOTA_EXCEEDED` (count *and* vault byte quota), `NOTE_NOT_FOUND`,
`LOCKED`, `IO`, `INTEGRITY`. Errors are non-secret enums; the data layer logs nothing (no
names, sizes, paths, ids, content, keys).

## 5. Repository API semantics

```kotlin
suspend fun importAttachment(noteId: String, displayName: String, mimeType: String, source: InputStream): AttachmentImport
suspend fun attachmentBytes(noteId: String, attachmentId: String): ByteArray?
suspend fun exportAttachment(noteId: String, attachmentId: String, out: OutputStream): Boolean
suspend fun deleteAttachment(noteId: String, attachmentId: String): Boolean
```

All four are main-safe suspend functions, serialised under the repository mutex, and fail
closed on a locked vault (`LOCKED` / `null` / `false`). The default interface implementations
are fail-secure no-ops for test fakes.

- **importAttachment** — `LOCKED` if locked; `NOTE_NOT_FOUND` if the note is not in the
  decrypted index; `QUOTA_EXCEEDED` for count/quota; reads the source into memory only up to
  the 10 MiB limit (`TOO_LARGE` past it, `IO` on stream failure); generates a random UUID
  (never derived from content), computes SHA-256, encrypts to a temp file and renames it to
  `<id>.nzb`, then persists the note with the appended ref. If the item write fails the blob
  is deleted (`IO`); if blob write fails no ref is added. The item's `updatedAt`/`version` are
  bumped (an attachment is a content change).
- **attachmentBytes** — returns the decrypted plaintext (caller-owned, memory only) or
  `null` when locked, note/ref unknown, blob missing, or AEAD/size/SHA-256 verification
  fails. Never cache or log the result.
- **exportAttachment** — explicit user-initiated decrypted export (SAF `CreateDocument`):
  decrypt + verify, write to the caller's stream, wipe the repository's copy. This is the
  only path that hands plaintext outside the encrypted store; the UI must show an explicit
  "writes an UNENCRYPTED copy outside the vault" confirmation first.
- **deleteAttachment** — removes the ref (re-encrypting the note payload) and then the blob;
  `false` if locked/unknown. Deleting the blob is best-effort after the ref is gone; a
  leftover blob is swept at the next clean unlock.

## 6. Lifecycle and orphan-cleanup rule

| Operation | Attachment effect |
|---|---|
| `trashItem` / `restoreItem` | **none** — refs and blobs are kept so trash is fully restorable. |
| `deleteItemForever` | deletes the note row, then its blobs (best effort). |
| `emptyTrash` | deletes every trashed note row, then their blobs (best effort). |
| `deleteAttachment` | removes ref, then deletes blob. |
| backup v2 export/import | see §8. |
| unlock / setup / biometric unlock (index load) | orphan sweep. |

**Orphan sweep rule:** after a successful index load, scan `attachments/` and delete
(a) `*.nzb` files whose id is not referenced by any decrypted item, and (b) interrupted
`.tmp-*` files from an atomic write. The sweep is **skipped entirely when any row failed to
decode** (`corruptRowCount > 0`): a corrupt item could reference a blob that would otherwise
look orphaned, and silently deleting user data is worse than keeping ciphertext. A failed
delete is harmless (ciphertext only) and is retried on the next clean unlock.

## 7. UI integration contract (owner: attachments-ui)

- Read refs from `Item.attachments` on the repository `items` flow. `AttachmentRef` fields:
  `id: String`, `displayName: String`, `mimeType: String`, `sizeBytes: Long`,
  `createdAt: Long`, `sha256: String` (lowercase hex of plaintext).
- Import: `repository.importAttachment(noteId, displayName, mimeType, contentResolver.openInputStream(uri)!!)`
  → `AttachmentImport.Success(ref)` / `Failure(error)`. The engine enforces the real limits;
  a `ContentResolver` size pre-check is only a fast reject. Use
  `beginExternalActivity()`/`endExternalActivity()` around the picker.
- Display: images via `attachmentBytes(...)` decoded **in memory** (`inSampleSize`, never a
  thumbnail file); other files show icon + displayName + size. The UI shows errors by kind
  only; it never logs names/content.
- Export: `exportAttachment(noteId, attachmentId, outputStream)` behind an explicit
  unencrypted-copy warning dialog. Delete: `deleteAttachment(noteId, attachmentId)` behind a
  confirm. Quota/limit messages: `TOO_LARGE` → "10 MiB per attachment", `QUOTA_EXCEEDED` →
  "100 per note / 512 MiB vault limit".
- Never construct `AttachmentRef`s or mutate `Item.attachments` yourself; the repository owns
  the metadata (`saveItem` ignores changes to it). Never persist/thumbnail/cache decrypted
  bytes.

## 8. Backup v2

Backup format **v2** (`BACKUP-FORMAT.md`) carries items + folders + attachment refs (inside
item payloads) + the blobs themselves. The whole backup payload is sealed under the source
VMK with the existing backup AAD; **each blob is additionally still an `NZAT` envelope
sealed under the source VMK + attachment AAD**, so it is bound to its note/id even inside the
backup.

- Export reads every referenced blob present and readable (ciphertext copied verbatim). A
  missing/unreadable blob is omitted; its ref still travels inside the item payload, and a
  restore counts it as `attachmentsSkipped` (the note is restored, the attachment is not).
- Import (accepts v1 and v2): for each blob record it finds the target note in the final
  index, requires a matching ref, decrypts the inner blob with the **source** VMK (the one
  that opened the backup payload) and the attachment AAD, verifies plaintext size + SHA-256
  against the ref, then re-encrypts under the **local** VMK/AAD into a temp file. Temp files
  are renamed into place only after the Room transaction commits; failures delete them. Blobs
  are deduped by attachment id (an existing `<id>.nzb` is counted as duplicate, never
  rewritten). Repeated blob records in one file are deduped. Invalid/sha-mismatched/missing
  blobs are skipped+counted and never fail the note.
- A v2 restore reproduces attachment bytes exactly (asserted by test with binary content and
  SHA-256/AAD checks). v1 backups import unchanged with `attachmentsImported = attachmentsSkipped = 0`.
- `BackupImportReport` gains defaulted `attachmentsImported` / `attachmentsSkipped` counts.

**Size cap:** the backup envelope keeps its existing 64 MiB `MAX_BACKUP_BYTES` OOM guard, and
attachment bytes count toward it. A vault whose attachments no longer fit fails the export
cleanly (`exportBackup` returns false, no partial file is written) — it never silently drops
attachments and never pretends a truncated backup is valid. Chunked/streamed v3 backups are
the follow-up if the quota is ever fully used.

## 9. Threat notes / what this does not hide

- A local attacker with filesystem access sees: attachment **existence**, count, approximate
  **ciphertext size** (hence approximate plaintext size), creation order via mtimes, and the
  random attachment ids. They do not see names, MIME types, SHA-256 values or content.
- Blob deletion/restore is not authenticated (the DB ref is): an attacker can delete a blob
  (read fails closed, counted) or roll back an old blob (SHA-256/AAD still verify, but it is
  the content of that older attachment). The same attacker can already delete/roll back the
  whole DB file inside the app sandbox; tamper-evidence across devices is not a goal.
- No screenshot/thumbnail/export file is created by the data layer. The only plaintext write
  is the explicit SAF export the user confirms; the only plaintext outside the VMK is the
  in-memory `ByteArray` handed to the UI.
- Attachments do not change the offline guarantee: no network APIs, no new permissions and no
  new dependencies were added (picker URIs are resolved by the UI through `ContentResolver`).

## 10. Verification

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:compileDebugKotlin
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest \
  --tests "com.cipherwell.notezero.data.*" --tests "com.cipherwell.notezero.model.*"
```

Data-layer attachment coverage: `AttachmentBlobTest` (format/AAD/tamper/bounded streaming/
SHA-256 vectors), `PayloadCodecTest` (v2 round trip, v1 decode, field bounds),
`EncryptedNoteRepositoryAttachmentsTest` (round trip, no-plaintext scan, limits/quota,
AAD swaps, tamper + SHA/size mismatch, trash vs delete-forever/empty-trash, orphan sweep incl.
corrupt-row guard, locked fail-closed, export failure, backup v2 same/cross-vault, v2
duplicate-safe re-import, missing-blob skip, v1 fixture import), `BackupFormatTest` (v2 wire
format + v1 compatibility, header authentication).
