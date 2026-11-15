# Attachments UI (Phase 4, contract B)

Scope: `ui/attachments/**`, additive changes to `ui/editor/NoteEditorScreen.kt`, and their
tests. Owned by `attachments-ui`. Storage/format/AAD/backup rules live in
`docs/ATTACHMENTS.md` (data layer) and `docs/DECISIONS.md`; this file only describes the UI
behaviour and its security invariants.

## Components

| Component | File | Responsibility |
|---|---|---|
| `AttachmentController` | `ui/attachments/AttachmentController.kt` | State machine: picker brackets, import, decrypt-for-display, explicit export, delete. Android-free (injected `NoteRepository`, byte sources, streams, dispatchers). No logging. |
| `AttachmentUiState` / `AttachmentErrorKind` | `ui/attachments/AttachmentUiState.kt` | Non-secret UI state and the friendly message for each failure kind (10 MiB/file, 100/note, 512 MiB quota, locked, integrity, note missing, IO). |
| `AttachmentSource` | `ui/attachments/AttachmentSource.kt` | One picked file: sanitised display name, MIME, optional declared size, lazy stream factory. No bytes, no path. |
| `AttachmentSourceResolver` | `ui/attachments/AndroidAttachmentSource.kt` | The only Android-aware input adapter: one `ContentResolver` query for `OpenableColumns`, safe defaults for misbehaving providers. |
| `AttachmentFormat` / `AttachmentImages` | `ui/attachments/AttachmentFormat.kt`, `AttachmentImages.kt` | Pure size/name/sampling math; in-memory `BitmapFactory` decode with `inSampleSize`. |
| `AttachmentStrip` | `ui/attachments/AttachmentStrip.kt` | The editor row: add button + menu, chips (thumbnail / type badge, name, size), delete confirmation, status/error text. Non-image files use a type badge (e.g. `PDF`) rather than an icon: `material-icons-core` ships no document glyph and adding an icon dependency is out of scope. |
| `AttachmentViewerScreen` | `ui/attachments/AttachmentViewerScreen.kt` | Full-screen decrypted image (or "preview unavailable" placeholder), close, export behind the UNENCRYPTED warning. |
| `NoteEditorScreen` | `ui/editor/NoteEditorScreen.kt` | Renders `AttachmentStrip` between title and body; full-screen viewer overlay; optional controller injection for tests. |

## Security invariants

1. **No plaintext thumbnails on disk.** Images are decrypted through the repository,
   decoded in memory with `inSampleSize`, and live only in composition state. There is no
   file cache, no `Bitmap.compress`, and no path derived from a display name.
2. **Picker bracketing.** Every system picker (`PickVisualMedia`, `OpenDocument`,
   `CreateDocument`) is preceded by `repository.beginExternalActivity()` and followed by
   `endExternalActivity()` exactly once — including cancel and "the vault locked while the
   picker was open". A stale/duplicate result never ends the bracket twice.
3. **Fail closed.** A locked vault refuses to arm a picker or import/export/delete, and a
   result that arrives after an idle lock is discarded with the `LOCKED` message.
4. **No new permissions.** `PickVisualMedia` needs no storage or camera permission;
   `OpenDocument("*/*")` is SAF. No manifest/res changes.
5. **No logging.** Neither the controller nor the composables log names, sizes, ids,
   bytes, URIs or errors.
6. **Explicit plaintext export.** "Export decrypted copy" always shows a dialog whose text
   says the copy is UNENCRYPTED and leaves the vault; only the confirm button arms SAF
   `CreateDocument`. Closing the warning touches nothing.
7. **Fast size pre-check.** The provider's `OpenableColumns.SIZE` is a local reject only;
   the data layer remains authoritative and enforces limits while reading. The stream is
   never opened for a file already known to be over 10 MiB.

## Test tags

| Tag | Meaning |
|---|---|
| `note_attachments` | Strip container (exists even with zero attachments) |
| `attachment_add` | Add button |
| `attachment_add_photo` / `attachment_add_file` | Menu items (PickVisualMedia / OpenDocument) |
| `attachment_status` | Count / "Adding attachment…" / "Attachment error" |
| `attachment_error` | Friendly error text (only while in an error state) |
| `attachment_item_<id>` | One attachment chip (tap opens the viewer) |
| `attachment_delete` | Per-chip delete button (opens the confirm dialog) |
| `attachment_delete_dialog` / `attachment_delete_confirm` / `attachment_delete_cancel` | Delete confirmation gate |
| `attachment_viewer` | Full-screen viewer overlay |
| `attachment_viewer_close` | Close button |
| `attachment_viewer_title` | Display name + MIME/size line |
| `attachment_viewer_image` | Decrypted image (or preview-unavailable placeholder); content description = display name |
| `attachment_viewer_error` | Attachment could not be decrypted/opened |
| `attachment_export` | Export decrypted copy button |
| `attachment_export_warning` | UNENCRYPTED-copy warning dialog |
| `attachment_export_confirm` / `attachment_export_cancel` | Warning gate |
| `attachment_export_status` / `attachment_export_error` | Export result / failure text |

## Tests

- `AttachmentControllerTest` — fake repository + coroutines-test virtual time: success and
  every failure kind, fast size rejection without opening the stream, 100-per-note local
  rejection, bracket order/once-only semantics, cancel, lock mid-picker, decrypt
  quiet/reporting, export write and failure, delete success/failure/locked.
- `AttachmentEditorTest` — Robolectric Compose: strip rendering, add menu, delete confirm
  gate, viewer with a tiny in-memory PNG, export warning gate (and that confirming only
  then arms the bracket).
- `AttachmentFormatTest`, `AttachmentImagesTest` — pure sampling/size/name math and
  in-memory decode of tiny PNGs, corrupt/truncated data returns null (never throws).

No sleeps: controller tests drive `StandardTestDispatcher` virtual time; Robolectric tests
use `UnconfinedTestDispatcher` for the controller and `waitUntil` only for the background
bitmap decode.
