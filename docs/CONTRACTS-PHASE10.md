# NoteZero — Phase-10 (increment 11) contracts: PDF annotation editor + flattened export

Frozen for increment 11. UI/tests address these names only; renaming requires a matching change.

## A. Encrypted annotation layer (data layer, Lead — implemented)

| Contract | Value |
|---|---|
| Model | `model/PdfAnnotations.kt`: `PdfPoint(x,y)` normalised 0..1, `PdfInkKind { INK, HIGHLIGHTER }`, `PdfInkStroke(page, kind, colorIndex, widthIndex, points)`, `PdfHighlightRect(page,left,top,right,bottom,colorIndex)`, `PdfNote(page,x,y,text)` |
| Bounds | ≤2,000 strokes, 2..2,000 points/stroke, ≤100,000 total points, ≤1,000 highlights, ≤500 notes, note ≤4,000 UTF-8 bytes, 6 colours, 3 widths, page ≤100,000; container throws `IllegalArgumentException` outside bounds |
| Codec | `data/PdfAnnotationCodec` v1 ("NZPA"), quantised 1/10,000, ≤4 MiB, deterministic, `decode` returns null (never throws) on corruption/trailing bytes |
| Storage | `Item.pdfAnnotations` inside `ItemCodec` payload **v5** (presence byte + u32 length + codec blob, appended after reading state); v1–v4 decode with `null`; repository-owned |
| Repository | `suspend fun savePdfAnnotations(itemId, annotations: PdfAnnotations?): Boolean` — null/empty clears, false while locked/unknown/invalid; `saveItem` preserves the stored layer; backup v2 carries it |
| Tests | `PdfAnnotationCodecTest` (round-trip, bounds, truncation, 1,000-sample fuzz, density); `PayloadCodecTest` v5 + v4 compat; `EncryptedNoteRepositoryTest` persist/preserve/lock-no-write/no plaintext/backup/clear |

## B. PDF annotation UI (ui-agent; owns `ui/pdf/**`)

File ownership for this increment: `app/src/main/java/com/cipherwell/notezero/ui/pdf/PdfViewerScreen.kt`, new
`app/src/main/java/com/cipherwell/notezero/ui/pdf/PdfAnnotation*.kt`, `docs/PDF-ANNOTATION-UI.md`, and
the corresponding tests under `app/src/test/.../ui/pdf/**` + `app/src/androidTest/.../ui/pdf/**`.

### Toolbar / mode
- Toolbar gains an annotate toggle, tag `pdf_annotate_toggle`, content description from
  `cd_pdf_annotate`; active state shows `pdf_annotate_done` (tag `pdf_annotate_done`).
- While annotate mode is active a tool palette is visible (bottom sheet/bar) with tags:
  `pdf_tool_ink`, `pdf_tool_highlighter`, `pdf_tool_rect`, `pdf_tool_eraser`, `pdf_tool_note`,
  `pdf_tool_undo`, `pdf_tool_redo`, colours `pdf_color_0`..`pdf_color_5`, widths
  `pdf_width_0`..`pdf_width_2`, hint text `pdf_annotate_hint`.
- Page scrolling is disabled/locked while annotate mode is active (drawing consumes drags);
  Done returns to scroll mode. Back exits annotate mode first, then closes the viewer.
- Palette/width indices map to `PdfAnnotations.PALETTE_SIZE = 6` and `WIDTH_LEVELS = 3`; colours
  must come from `NoteZeroPalette` tokens (no new hex literals) via a small
  `PdfAnnotationPalette` object shared with the flatten exporter.

### Drawing
- Every rendered page keeps its existing `pdf_page` tag and gains an overlay
  `pdf_annotation_overlay`; annotations are drawn with `Canvas` using normalised coordinates
  scaled to the displayed page bounds.
- Ink/highlighter: drag collects points (min 2, capped per stroke); on release append a
  `PdfInkStroke` to the in-memory layer and persist (repository.savePdfAnnotations).
- Highlight box: drag creates a normalised `PdfHighlightRect` (start/end, clamp 0..1, require
  non-zero area); translucent fill + border.
- Sticky note: tap opens dialog `pdf_note_dialog` with field `pdf_note_text`, save
  `pdf_note_save`, delete `pdf_note_delete` (existing note), cancel via dismiss; note marker is a
  small icon at (x,y); tapping a marker reopens the note.
- Eraser: drag removes strokes/highlights intersecting (within a normalised tolerance) and
  notes near the point; mutation persists.
- Undo/redo: bounded in-memory history (≥50 steps) of `PdfAnnotations`; disabled buttons when
  empty; eraser/clear are undoable.
- Persistence: load `item.pdfAnnotations` on open; save after each mutation through
  `repository.savePdfAnnotations(itemId, layerOrNull)`; failures while locked are silent.
  Never log annotation content.

### Export
- Existing export action still offers the original decrypted copy. When annotations exist, first
  show dialog `pdf_export_choice` with `pdf_export_original` and `pdf_export_annotated`; both
  require the existing UNENCRYPTED-copy warning flow and SAF `CreateDocument`.
- `pdf_export_annotated` renders pages + annotation layer into a new PDF (A4-ish, one image per
  page) in memory via a new `PdfFlattenedExporter` (android.graphics.pdf + Android Canvas):
  - input: rendered page bitmaps (`PdfPages`) + `PdfAnnotations` + palette
  - output: `ByteArray?` (null on OOM/errors), A4 page size, white background, annotations at
    bitmap scale, bounded (≤ `PdfPages.totalPages` pages, no temp files)
  - no password handling, no plaintext cache; the explicit warning must mention the flattened/
    rasterised nature (`pdf_export_annotated_desc`).
  - status copy `pdf_export_annotated_written` / `pdf_export_annotated_failed`.
- `PdfFlattenedExporter` must be injectable into `PdfViewerScreen` for Robolectric tests;
  instrumented test validates the real exporter output with `PdfRenderer` (≥1 page, valid PDF).
- No new permissions; annotation colours/tool labels are resources (pre-added).

### Tests (ui-agent)
- Robolectric (`FakeNoteRepository`, fake `PdfPageRenderer`, injected fake exporter):
  annotate toggle + palette tags; ink drag creates/persists a stroke; highlighter kind stored;
  rect highlight; sticky note create/edit/delete; eraser removes; undo/redo; annotations restored
  from repository on open; export choice shows annotated option only with annotations; annotated
  export path invokes exporter and writes through the SAF result (fake stream).
- Instrumented: real `PdfDocument` flatten export has ≥1 page and `PdfRenderer` accepts it;
  hostile annotations never crash the exporter (empty/limits).
