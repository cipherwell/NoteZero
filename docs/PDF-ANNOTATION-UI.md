# PDF annotation editor + flattened annotated-PDF export

Phase-10 increment 11, section B of `docs/CONTRACTS-PHASE10.md`. This document describes the
shipped UI, its tags, persistence, export behaviour, limitations and tests. The data layer
(annotation model, codec, encrypted storage, `repository.savePdfAnnotations`) is section A and is
owned by the data implementation, not by this package.

## Files

| File | Contents |
|---|---|
| `ui/pdf/PdfViewerScreen.kt` | Viewer + annotate mode orchestration, note dialog wiring, export choice/warning/annotated flow |
| `ui/pdf/PdfAnnotationEditor.kt` | Editor state machine: mutations, bounds clamping, undo/redo history, hit testing, note-text budget |
| `ui/pdf/PdfAnnotationPalette.kt` | Shared colours/alphas/stroke widths/note-marker geometry (single source of truth for overlay + exporter) |
| `ui/pdf/PdfAnnotationOverlay.kt` | Per-page `Canvas` overlay, draw code, pointer gestures, sticky-note markers |
| `ui/pdf/PdfAnnotationPaletteBar.kt` | Annotate-mode tool/colour/width/undo/redo palette |
| `ui/pdf/PdfAnnotationDialogs.kt` | Sticky-note dialog |
| `ui/pdf/PdfFlattenedExporter.kt` | `PdfFlattenedExporter` interface + `AndroidPdfFlattenedExporter` (`android.graphics.pdf`) |
| `ui/pdf/PdfExportGateway.kt` | SAF `CreateDocument` seam for the annotated export + binary-safe write |
| `test/.../ui/pdf/PdfAnnotationScreenTest.kt` | Robolectric editor/export flows (14 tests) |
| `test/.../ui/pdf/PdfAnnotationEditorTest.kt` | Pure-JVM editor state machine tests (16 tests) |
| `test/.../ui/pdf/PdfAnnotationPaletteTest.kt` | Pure-JVM palette/bounds tests (8 tests) |
| `androidTest/.../ui/pdf/PdfFlattenedExportInstrumentedTest.kt` | Real `PdfDocument`/`PdfRenderer` validation (4 tests) |

## Annotate mode

- The toolbar gains an annotate toggle only when pages rendered successfully:
  - inactive: icon button, tag `pdf_annotate_toggle`, content description `cd_pdf_annotate`;
  - active: text button "Done", tag `pdf_annotate_done`, exact copy `pdf_annotate_done`.
- Entering annotate mode shows the bottom palette and **disables list scrolling** (drawing
  consumes drags; `LazyColumn.userScrollEnabled = false`). The bookmark buttons are hidden while
  annotating; Back/Done leaves the mode and only a second Back closes the viewer.
- The page image keeps its existing `pdf_page` tag; every page gains
  `pdf_annotation_overlay` (a test-tagged `Canvas`) layered over it inside a
  `pdf_page_frame` bounds box with the bitmap aspect ratio.

## Palette

Tags: `pdf_tool_ink`, `pdf_tool_highlighter`, `pdf_tool_rect`, `pdf_tool_eraser`,
`pdf_tool_note`, `pdf_tool_undo`, `pdf_tool_redo`, `pdf_color_0..pdf_color_5`,
`pdf_width_0..pdf_width_2`, hint `pdf_annotate_hint` (tag on the hint text).

- Tools are `FilterChip`s with the pre-added labels; undo/redo are text buttons disabled when the
  bounded history has nothing to undo/redo.
- Colours are 48dp touch targets around a 28dp swatch; the selected colour gets an accent ring.
  Colours are `pdf_tool_colour` + a 1-based ordinal for the content description (no dedicated
  per-colour string exists in `res/values/strings.xml`; not added here by design).
- Widths are three 48dp touch targets drawn with a 2/5/8dp bar, content description
  `pdf_tool_width` + 1-based ordinal.
- Both palette rows scroll horizontally so large-font / narrow-screen layouts stay usable; the
  hint sits below them.

## Tools and gestures

All coordinates are **normalised 0..1 to the displayed page** before they reach the editor
(`x / overlay.width`, clamped), so annotations render identically at any bitmap size and in the
A4 export. The editor clamps/rejects hostile values (non-finite, out of range, degenerate) without
throwing.

| Tool | Gesture | Result |
|---|---|---|
| Ink | drag | `PdfInkStroke(kind = INK)`, min 2 points, capped at `MAX_POINTS_PER_STROKE` |
| Highlighter | drag | `PdfInkStroke(kind = HIGHLIGHTER)`, translucent (alpha 0.42) and slightly wider |
| Highlight box | drag | `PdfHighlightRect` normalised/clamped; tiny drags (<0.004 page) ignored |
| Eraser | drag or tap | removes whole strokes/highlights within a fingertip tolerance and notes near the point |
| Sticky note | tap on empty spot | opens `pdf_note_dialog` |
| Sticky note marker | tap with any tool except the eraser, and any time outside annotate mode | reopens the note for edit/delete |

- Live preview is drawn while dragging (ink polyline, translucent rect) and the in-progress
  points are discarded if the gesture is cancelled.
- Sticky-note markers are amber badges sized relative to the page short edge, drawn over the
  strokes; tapping a marker reopens it even outside annotate mode.
- Note text is capped as the user types to the model's 4,000 UTF-8 byte budget (whole characters,
  no split surrogate pairs); blank notes are rejected. The dialog has `pdf_note_dialog` (title and
  tag), field `pdf_note_text`, save `pdf_note_save`, delete `pdf_note_delete` (existing note
  only), cancel `pdf_note_cancel`.

## Rendering

- Highlights: translucent fill (`HIGHLIGHT_FILL_ALPHA = 0.26`) + border.
- Strokes: width = `fraction(widthIndex) × min(pageWidth, pageHeight)`, fractions
  0.003 / 0.006 / 0.011 (a highlighter is 1.8× wider at the same index); round caps/joins.
  This keeps a stroke visually the same on a phone bitmap and in the A4 export.
- The six palette colours derive from the light-ground `NoteZeroPalette` tokens (dark ink, danger
  red, success green, accent blue) plus two HSV-derived hues (orange, purple) chosen for white
  paper; note markers use the warning/surface tokens. **No new hex literals** — the only literal is
  the `0x00FFFFFF` RGB mask inside `PdfAnnotationPalette`.

## Persistence

- On open the viewer loads `item.pdfAnnotations` once (`PdfAnnotationEditor.load`) after the item
  arrives; loading never enters the undo history.
- After **every** successful mutation (ink, highlighter, rect, note create/edit/delete, erase,
  undo, redo) the screen calls `repository.savePdfAnnotations(itemId, layerOrNull)` in a
  coroutine; an empty layer is saved as `null` (clears the encrypted value).
- Failures (locked vault, unknown item) are silent: the on-screen layer remains usable but
  nothing is written; nothing about the annotation content is logged.
- The layer is content inside the encrypted item envelope (payload v5) and travels with encrypted
  backups — see the data-layer contract.

## Undo / redo

`PdfAnnotationEditor` keeps up to `MAX_HISTORY = 64` immutable `PdfAnnotations` snapshots (≥50
required). All mutations — including eraser removals and the internal `clear()` (no toolbar
button) — are undoable. A new mutation after an undo clears the redo branch. Buttons reflect
`canUndo`/`canRedo` and are disabled when empty.

## Export

Flow when the user taps `pdf_export`:

1. **No annotations** — exactly today's flow: explicit UNENCRYPTED-copy warning
   (`pdf_export_warning`) → SAF `CreateDocument("application/pdf")` → original decrypted bytes
   through `AttachmentController`/repository.
2. **Annotations present** — dialog `pdf_export_choice` first, with
   `pdf_export_original` (+ `pdf_export_original_desc`) and `pdf_export_annotated`
   (+ `pdf_export_annotated_desc`). Both go through the same explicit warning dialog; the warning
   for the annotated copy additionally repeats the flattened/rasterised description.
   - Original: the unchanged decrypted-copy path.
   - Annotated: SAF `CreateDocument("application/pdf")` through the injected `PdfExportGateway`,
     then the pages are flattened in memory on `Dispatchers.Default`, the vault is re-checked for
     `UNLOCKED` + item still present, and only then the bytes are written. Status copy:
     `pdf_export_annotated_written` / `pdf_export_annotated_failed`; a cancelled picker reuses the
     existing "export cancelled" copy and never flattens. If the vault locked while the picker was
     open the pending bytes are dropped and `attach_export_locked` is shown.

`AndroidPdfFlattenedExporter`:

- One page per rendered bitmap, in order; A4 (595×842 pt) white pages; the page bitmap is drawn
  scaled-to-fit and centred, then highlights/strokes/note markers are drawn in the same bitmap
  coordinate space so they keep their relative size.
- Output `ByteArray?`, **null on any failure** (empty/mismatched input, recycled bitmap, platform
  error, `OutOfMemoryError`); never throws and never writes a temp file. The bytes exist only in
  the caller's coroutine until the SAF write.
- Page count = `min(rendered bitmaps, pages.totalPages)` with a defensive hard cap of 32 pages;
  annotations referencing non-rendered pages are ignored.
- No new permissions, no plaintext cache, no content logging.

### Flattened-export limitations

- Rasterised: text is no longer selectable/searchable; interactive links/forms are gone, as the
  warning copy says.
- Only the pages the viewer rendered are exported (the display renderer caps at 8 pages and
  1,600px edge), so a longer PDF's annotated copy can be a prefix of the document. The original
  export still produces the full untouched file.
- Page aspect differences from A4 are letterboxed with white margins by design (no cropping).
- Sticky notes are flattened as their **marker** only: the note text stays in the encrypted vault
  and is readable in the app, but it is not rendered into the exported PDF (a rasterised copy
  cannot carry interactive popup annotations).
- Nothing is written until the vault re-check passes; the flattened bytes never touch disk.

## Test coverage

Robolectric (JVM, injected fake page renderer / fake `PdfFlattenedExporter` / fake
`PdfExportGateway` / `FakeNoteRepository`; `@Config(sdk=[34])`):

- annotate toggle → palette tags + Done returns to scroll mode; Back leaves annotate mode first;
- ink drag creates and persists a `PdfInkStroke` (page/kind/colour/width/points verified through
  `item.pdfAnnotations`); highlighter kind + selected colour + width;
- rectangle drag stores a normalised, ordered `PdfHighlightRect`;
- sticky note create → reopen → delete (empty layer stored as `null`);
- eraser removes a stroke; undo restores it; redo removes it; disabled states verified;
- annotations loaded from the repository on open (export choice appears, loaded stroke erasable);
- export without annotations skips the choice and shows the warning;
- annotated export invokes the injected exporter with the current layer and writes its bytes
  through the fake SAF destination (suggested name `document-annotated.pdf`), showing
  `pdf_export_annotated_written`; exporter failure writes nothing; cancel writes nothing and does
  not flatten; the original option never invokes the exporter;
- annotate toggle hidden after a render failure.

Instrumented (device/emulator only, real `android.graphics.pdf`):

- flattened export of a rendered `BlankPdf` + non-empty annotations starts with `%PDF`, is
  non-empty and `PdfRenderer` accepts it with the same page count;
- hostile/oversized input fails closed (`null`): no pages, a page list whose accessor throws, a
  page list above the export cap, annotations for non-rendered pages ignored; model bounds reject
  out-of-range values before export;
- the maximum in-bounds annotation density (100,000 points) either exports a valid PDF or returns
  null — never throws.

Counts: `:app:testDebugUnitTest --tests "com.cipherwell.notezero.ui.pdf.*"` → **55 tests, 0
failures** (new: 14 `PdfAnnotationScreenTest` + 16 `PdfAnnotationEditorTest` + 8
`PdfAnnotationPaletteTest`; existing: 8 `PdfViewerScreenTest` + 9 `PdfInlineMarkdownTest`).
Instrumented: 4 tests in `PdfFlattenedExportInstrumentedTest`.

Reproduce with:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
./gradlew :app:testDebugUnitTest --tests "com.cipherwell.notezero.ui.pdf.*" --console=plain
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PallowScreenshots=true --console=plain
```

## Pre-added strings

All required strings existed in `res/values/strings.xml`; **no string was added or changed**.
Notes for future work:

- There is no dedicated `pdf_color_N` / `pdf_width_N` label string; the palette builds the
  accessibility label from `pdf_tool_colour` / `pdf_tool_width` plus a 1-based ordinal.
- `pdf_annotation_saved`, `pdf_annotation_erased`, `pdf_annotation_empty` and
  `pdf_annotation_count` are unused: the contract requires no per-stroke toast and no annotation
  list UI, so they were left for a future increment rather than creating noise on every stroke.

## Device-QA watch list

- Palette rows scroll horizontally on purpose; check that undo/redo and the note tool are
  reachable with large font sizes and in landscape.
- Drawing near the page edge should clamp (never crash/leak coordinates outside 0..1); the eraser
  removes whole strokes, not fragments.
- Rotation/lock: annotations must reload from the repository (they are persisted after every
  mutation, so a lock mid-edit loses at most the in-flight gesture).
- Annotated export on a multi-page document: verify the flattened page count equals the rendered
  pages (≤8) and that the original export is still the full document.
- Note text with emoji/CJK at the byte cap should save truncated whole characters, never crash.
- Export while the vault auto-locks during the SAF picker: no bytes may be written.
