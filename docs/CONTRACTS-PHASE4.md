# NoteZero — Phase 4 (increment 3) contracts

Scope: **encrypted attachments** (Phase 4 core) + **code-workspace polish** (Phase 3
completion). Same rules as `docs/CONTRACTS.md`: no new dependencies, no network, no plaintext
secrets in logs, stay in scope, no git/build-file/manifest edits, pure-JVM logic where possible.

Current state: Phase 1+2 complete, Phase 3 editor shipped (commit `2ff0ca6`). All prior tests
green: 533 unit / 10 device.

---

## A. Attachments data, storage & backup (owner: attachments-data)

### A.1 Model (`model/Item.kt`, owned by you this increment)

```kotlin
data class AttachmentRef(
    val id: String,            // random UUID, never derived from content
    val displayName: String,   // user-visible name, encrypted inside the item payload
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val sha256: String,        // hex SHA-256 of the plaintext bytes (integrity check on read)
)

data class Item(
    ... existing fields ...,
    val attachments: List<AttachmentRef> = emptyList(),
)
```

`ItemCodec` payload version → **v2** (append attachment list). Must still decode **v1** rows
(empty list) — no re-encryption/migration required; document the version table.

### A.2 Repository API additions (`data/NoteRepository.kt` + `EncryptedNoteRepository`)

```kotlin
object AttachmentLimits {
    const val MAX_ATTACHMENT_BYTES: Long = 10L * 1024 * 1024       // 10 MiB per attachment
    const val MAX_ATTACHMENTS_PER_NOTE: Int = 100
    const val MAX_TOTAL_BYTES: Long = 512L * 1024 * 1024           // 512 MiB vault quota
}

enum class AttachmentError { TOO_LARGE, QUOTA_EXCEEDED, NOTE_NOT_FOUND, LOCKED, IO, INTEGRITY }

sealed interface AttachmentImport {
    data class Success(val ref: AttachmentRef) : AttachmentImport
    data class Failure(val error: AttachmentError) : AttachmentImport
}

// On NoteRepository (defaults are fail-secure no-ops for existing test fakes):
suspend fun importAttachment(noteId: String, displayName: String, mimeType: String, source: InputStream): AttachmentImport
/** Decrypted bytes, or null when locked/locked-out/corrupt/tampered. Never cached to disk. */
suspend fun attachmentBytes(noteId: String, attachmentId: String): ByteArray?
/** Explicit user-initiated decrypted export (SAF target). */
suspend fun exportAttachment(noteId: String, attachmentId: String, out: OutputStream): Boolean
suspend fun deleteAttachment(noteId: String, attachmentId: String): Boolean
```

### A.3 Storage format

- Blob files: `filesDir/attachments/<attachmentId>.nzb` (app-private, never in Room).
- File bytes: magic `NZAT` (4) + version `0x01` (1) + AES-256-GCM envelope (`iv||ct||tag`)
  of the plaintext under the vault VMK.
- AAD: `CryptoAad.attachment(noteId, attachmentId)` → add to `crypto/CryptoAad.kt` as
  `"notezero.attachment.v1|<noteId>|<attachmentId>"`. Swapping blobs between notes/ids must fail.
- `sha256` in the ref is verified against the decrypted plaintext on read; mismatch →
  `AttachmentError.INTEGRITY` (return null / failure, count it, never surface corrupt bytes).
- Import: enforce per-file limit while reading (do not trust ContentResolver size only; stop
  reading past the limit), per-note count and total quota; then write blob, append ref to the
  item envelope and persist atomically. Failure leaves **no** orphan blob and no ref.
- Delete: `deleteAttachment` removes blob + ref. `deleteItemForever`/`emptyTrash` must delete
  the note's blobs; **trash alone must not** delete blobs. Orphan blobs are cleaned on startup
  (scan ids not referenced by any decrypted item) — document the rule.
- Locked vault: all four methods fail closed; VMK never persisted.

### A.4 Encrypted backup v2

- Export writes backup format **v2**: items + folders + attachment refs + attachment blobs
  (each blob encrypted individually inside the backup payload; whole payload still under the
  existing backup envelope/AAD).
- Import: accept v1 (no attachments) and v2; duplicate-safe by item id+updatedAt as today, and
  attachment blobs deduped by attachment id; a v2 restore must reproduce bytes exactly
  (sha256 verified). Restoring a note whose refs exist but blobs are missing → skip that
  attachment, count it, keep the note.
- Update `docs/BACKUP-FORMAT.md` (v2 section + v1 compat), `docs/DATA-MODEL.md`,
  `docs/ARCHITECTURE.md`, `docs/DECISIONS.md` (#14 attachment storage/AAD/quota),
  and write `docs/ATTACHMENTS.md` (placeholder while UI teammate reads it: format, limits,
  AAD, lifecycle, no-plaintext-cache rule, threat notes).

### A.5 Tests (Robolectric + real crypto/Room, fake streams)

import→bytes round trip; sha256 verified; > limit rejected without orphan blob; per-note count
and total quota; AAD swap between two notes/ids fails; tampered blob → null + corrupt count;
trash keeps blob, delete-forever/empty-trash removes it; orphan cleanup; locked → fail closed;
backup v2 round trip incl. attachment bytes + duplicate-safe import; v1 backup fixture still
imports; raw attachment file scan contains no plaintext marker.

Do NOT edit UI packages, docs/CONTRACTS-PHASE4.md, `code/`, `ui/code/`, build/manifest/res/git.

---

## B. Attachments UI in the notes editor (owner: attachments-ui)

New package `ui/attachments/` + edits to `ui/editor/NoteEditorScreen.kt` (single writer: ui-notes
is inactive; keep changes additive and keep all existing tags/tests working).

- `AttachmentController` (testable, injected repository + byte sources): import image/file,
  calls `repository.beginExternalActivity()`/`endExternalActivity()` around the system picker,
  pre-checks size from `ContentResolver` (fast reject) **and** relies on the engine limit,
  surfaces non-secret states (`AttachmentUiState`: Idle/Importing/Imported/Error(kind)); no logs.
- `AttachmentStrip` composable in the note editor between title and body:
  `testTag("note_attachments")`, horizontal chips/grid with thumbnail for images (decoded
  in-memory with `inSampleSize`, never written to disk), file icon + name + size otherwise,
  per-item delete (`attachment_delete`) with confirm, tap → viewer.
- Add button `attachment_add` → menu `attachment_add_photo` (`ActivityResultContracts.PickVisualMedia`,
  no storage/camera permission) and `attachment_add_file` (`OpenDocument "*/*"`),
  status text `attachment_status`, errors `attachment_error`.
- `ImageViewerScreen` `attachment_viewer`: full-screen decrypted image, close button,
  contentDescription from the display name, FLAG_SECURE already app-wide; "Export decrypted
  copy" (`attachment_export`) via SAF `CreateDocument` with an explicit warning dialog
  ("This writes an UNENCRYPTED copy outside the vault") and confirm.
- 10 MiB / 100-per-note limits surfaced as friendly message; quota error surfaced too.
- Tests: controller state machine with fake repository (incl. failure kinds, external-activity
  bracketing); Robolectric editor tests: strip renders refs, add menu items, delete confirm
  removes ref, viewer shows image for a tiny in-memory PNG, export warning gate. Existing
  editor tests must stay green. No sleeps.

Constraints: no new dependencies; no camera permission; no plaintext thumbnails on disk; no
logging names/content; no edits outside `ui/attachments/`, `ui/editor/NoteEditorScreen.kt`,
its tests, and `docs/ATTACHMENTS-UI.md` if you want notes. README/CHANGELOG are Lead-owned.

---

## C. Code-workspace polish (owner: code-polish)

Scope: `code/**` (pure helper only), `ui/code/**` (single writer: code-ui inactive), tests.

1. **Workspace-wide search** `code/CodeSearch.kt` (pure Kotlin):
   ```kotlin
   data class CodeSearchMatch(val itemId: String, val fileName: String, val line: Int, val column: Int, val snippet: String)
   object CodeSearch {
       fun search(items: List<Item>, query: String, caseSensitive: Boolean, limit: Int = 200): List<CodeSearchMatch>
   }
   ```
   Searches code file titles AND bodies, includes line/column + one-line snippet, bounded,
   never throws, skips trashed items. UI: search bar/sheet in `CodeWorkspaceScreen`
   (`code_search_toggle`/`code_search_query`/`code_search_results`/`code_search_result_row`),
   result tap opens the file at that line (editor gains `initialLine` support), case toggle.
2. **Copy / export**: "Copy" selected-or-all to clipboard with `ClipDescription.EXTRA_IS_SENSITIVE`
   (`code_copy`), "Export decrypted copy" via SAF `CreateDocument` behind an explicit
   "writes an UNENCRYPTED copy" confirm (`code_export`, `code_export_confirm`,
   `code_export_cancel`). Copy is memory+clipboard only — no file writes.
3. **Multi-line indent polish**: toolbar indent/outdent (`code_indent_increase`,
   `code_indent_decrease`) wired to `CodeEditing.indentSelection` preserving selection;
   Tab/Shift+Tab in the editor inserts/removes an indent unit instead of moving focus;
   keep existing auto-indent behaviour and tests green.
4. Tests: `CodeSearchTest` (multi-line, case, snippets, limit, trashed skipped, no-throw on
   weird input); ViewModel tests for search state + open-at-line; Robolectric UI tests for
   search bar/results/copy-sensitivity/export-confirm/indent mutations. No sleeps; no
   logging of content.

Constraints: no new dependencies; no network; no edits outside `code/`, `ui/code/`, tests,
`docs/EDITOR-SPEC.md` if you extend it. Lead owns E2E + AppRoot/notes wiring.

---

## D. Lead integration

- E2E extension: code workspace search flow (search for the `CodeE2E_secret` marker, open a
  result, screenshot), indent round-trip; sync `Item.attachments` model change through existing
  fakes if compilation requires it; full unit suite + device suite; docs/verification;
  signed release; commit/push/CI; memory.
- Attachments device E2E: system picker is a separate activity; crypto/storage correctness is
  covered by the data-layer Robolectric suite (real crypto/Room), UI by Robolectric tests.
  The instrumented E2E stays on notes + backup + code + workspace search.
