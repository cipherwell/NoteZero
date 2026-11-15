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

## Drawing canvas (Phase 6, contract A)

A hand-drawn page is just another encrypted image attachment: it is drawn fully in memory,
rendered to a white PNG, imported through the **same** `NoteRepository.importAttachment`
path as a picked photo, and from then on behaves exactly like one (thumbnail in the strip,
full-screen viewer, delete gate, UNENCRYPTED-copy export gate).

### Components

| Component | File | Responsibility |
|---|---|---|
| `DrawingSketch` / `DrawingPoint` / `DrawingStroke` | `ui/attachments/DrawingSketch.kt` | Pure, immutable stroke model. Coordinates are normalised to the 0..1 page, so the preview and the PNG agree at any pixel size; ink and pen width are value enums. Bounded: 128 strokes, 2048 samples/stroke, 32768 samples total. |
| `DrawingFormat` | `ui/attachments/DrawingFormat.kt` | Pure page geometry (3:4 portrait, long edge clamped to 2048 px) and the deterministic `drawing-<yyyyMMdd-HHmmss>.png` display name (Locale.US, explicit time zone). |
| `DrawingPalette` | `ui/attachments/DrawingPalette.kt` | Ink/paper colours derived from `ui/theme` tokens (no hex literals). Paper is white in both app themes because the saved PNG is white; the two gap hues are HSV derivations of the same palette. |
| `DrawingRenderer` | `ui/attachments/DrawingRenderer.kt` | The only Android-aware part of the save path: `Bitmap` → white page → strokes → PNG bytes. Re-renders at half resolution if the PNG would exceed the 10 MiB attachment cap; never writes or logs. |
| `DrawingCanvasScreen` | `ui/attachments/DrawingCanvasScreen.kt` | Full-screen dialog: pointer-driven canvas (finger or stylus), 7 inks, 3 pen widths, undo, clear, cancel, save. The in-progress stroke is part of the preview. |
| `AttachmentController.importDrawing` | `ui/attachments/AttachmentController.kt` | In-memory import: wraps the PNG in an `AttachmentSource` with `image/png`, reuses every `importSource` guard (note exists, per-note count, 10 MiB declared-size pre-check, vault quota, lock) and zeroes the array once the repository has read it. |
| `AttachmentStrip` | `ui/attachments/AttachmentStrip.kt` | Third add-menu entry "Drawing" (`attachment_add_drawing`) below Photo/File; hosts the canvas dialog and pushes the saved bytes through the controller. |

### Flow

1. Add button → menu → **Drawing**. No system picker is launched, so no
   `beginExternalActivity` bracket is armed (there is nothing external to return from).
2. The canvas dialog appears over the editor. Pointer samples go into `DrawingSketch`
   (`start`/`extend`/`finish`); the preview and the eventual PNG paint the same model.
3. **Save** encodes the page off the main thread (`Dispatchers.Default`), closes the dialog
   and calls `controller.importDrawing(noteId, png)`. **Cancel** closes without importing.
4. `importDrawing` runs the same guards as a picked file and, on success, the strip shows
   the new chip/thumbnail and the status returns to the count. Undo, clear, delete, view and
   export are the existing attachment behaviours.

### Security invariants (drawing path)

1. **No plaintext on disk.** The bitmap and the PNG are heap objects only: no file, no
   cache directory, no `Context`, no `Bitmap.compress` destination outside a
   `ByteArrayOutputStream`.
2. **Same encrypted pipeline.** The drawing is imported with
   `repository.importAttachment(noteId, name, "image/png", ByteArrayInputStream)`; the data
   layer encrypts it, enforces the 10 MiB / per-note count / vault quota limits and stores
   the same AAD-bound blob as any other attachment.
3. **Bounded memory.** Page ≤ 2048 px on the long edge, ≤ 128 strokes, ≤ 32768 samples; the
   renderer re-encodes smaller rather than exceed the per-file cap, and returns null (with
   the non-secret `drawing_save_failed` message) rather than save an oversized image.
4. **Wiped after import.** `importDrawing` streams the array through a
   `ByteArrayInputStream` that zeroes the backing array on close, so the rendered plaintext
   page does not linger after the repository has read it.
5. **No logging, no new permissions, no new dependencies.** Names, strokes and bytes are
   never logged; the canvas needs no camera/storage permission and only the Compose/Material
   libraries already in the build.
6. **Accessibility.** 48dp targets for cancel/save/undo/clear/every ink and width swatch,
   content descriptions for all swatches and the canvas, `selected` semantics on the active
   ink/width (a check glyph shows the selection without relying on colour alone).

### Tags and strings

| Tag | Meaning |
|---|---|
| `attachment_add_drawing` | Third add-menu entry (label `attach_drawing` = "Drawing") |
| `drawing_dialog` | Full-screen canvas dialog |
| `drawing_canvas` | The white drawing page (pointer input + `cd_drawing_canvas`) |
| `drawing_cancel` / `drawing_save` | Header actions (`common_cancel`, `drawing_save`) |
| `drawing_undo` / `drawing_clear` | Footer actions (`common_undo`, `drawing_clear`) |
| `drawing_ink_<black\|grey\|red\|orange\|green\|blue\|purple>` | Ink swatch; content description `cd_drawing_ink_*` |
| `drawing_width_<thin\|medium\|bold>` | Pen width swatch; content description `cd_drawing_width_*` |
| `drawing_save_error` | Non-secret "could not render/save" line (only on failure) |

New strings: `attach_drawing`, `drawing_save`, `drawing_clear`, `drawing_canvas_hint`,
`drawing_save_failed`, `cd_drawing_canvas`, `cd_drawing_ink_*`, `cd_drawing_width_*`;
Undo/Cancel reuse `common_undo`/`common_cancel`. `docs/STRINGS.md` needs the `drawing_`
prefix added to its owner table (Lead-owned file).

### Tests

- `DrawingSketchTest` — pure model: add/extend/finish, clamping, sub-sample filtering, tap
  dots, undo (including an in-progress stroke), clear, tool selection, all three bounds.
- `DrawingFormatTest` — page geometry/aspect/clamps, deterministic locale-independent file
  name, PNG MIME.
- `DrawingRendererTest` — Robolectric: real PNG signature and decoded page dimensions, dot
  strokes, empty sketch renders nothing, every ink/width renders, a full page stays under
  the 10 MiB cap.
- `DrawingImportTest` — controller + `FakeAttachmentRepository`, virtual time: exactly one
  import with `image/png` and a timestamped name, no picker bracket, array wiped after
  import, empty/locked/oversized/missing-note/quota paths.
- `DrawingCanvasTest` — Robolectric Compose: menu entry (and its position below Photo/File),
  canvas + controls + content descriptions + selection state + 48dp targets, cancel imports
  nothing, a stroke enables save/undo/clear, save imports exactly one PNG and the drawing
  joins the strip.

No sleeps: pure/controller tests use virtual time, Compose tests use `waitForIdle` plus
bounded `waitUntil` polling of a Compose-visible outcome for the background PNG encode and
the strip commit (the same pattern as the thumbnail decode).
