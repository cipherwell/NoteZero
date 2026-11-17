# NoteZero — Phase-11 (increment 12) contract: drawing studio (standalone sketches)

Frozen for increment 12. UI/tests address these names only.

## A. Data layer (Lead — implemented)

| Contract | Value |
|---|---|
| Item kind | `ItemKind.SKETCH_FILE` appended after `BOOK_FILE` (payload ordinal 4; ordinals frozen) |
| Model | `model/SketchDocument.kt`: `SketchTemplate { BLANK, RULED, GRID, DOTTED }`, `SketchInk { BLACK, GREY, RED, ORANGE, GREEN, BLUE, PURPLE }`, `SketchWidth { THIN, MEDIUM, BOLD }` (fractions 0.008/0.018/0.035), `SketchInkKind { PEN, HIGHLIGHTER }`, `SketchShapeKind { LINE, RECT, ELLIPSE, ARROW }`, `SketchPoint(x,y)` normalised 0..1, `SketchStroke(points,ink,width,kind)`, `SketchShape(kind,start,end,ink,width)`, `SketchDocument(template,strokes,shapes)`; A4 portrait ratio 0.7071 |
| Bounds | ≤2,000 strokes, 2..2,000 pts/stroke, ≤100,000 total points, ≤500 shapes, blob ≤4 MiB; constructor throws outside bounds |
| Codec | `data/SketchCodec` v1 ("NZSK"), 1/10,000 quantised, deterministic, ≤4 MiB, null (never throws) on corruption/oversize/trailing |
| Storage | `Item.sketch` inside `ItemCodec` payload **v6** (presence byte + u32 blob length appended after annotations); v1–v5 decode with `sketch = null`; repository-owned: `saveSketch(itemId, sketchOrNull)` only writer (null/empty clears), `saveItem` preserves, backup v2 carries it |
| Preview | flattened PNG rendered **in memory** by the studio and imported through the normal encrypted attachment pipeline (`image/png`, ≤10 MiB). It is a preview, not the source of truth; the vector `SketchDocument` is what re-opens for editing. Keep at most one preview attachment per sketch (delete the replaced one). |
| Tests (Lead) | `SketchCodecTest` (round-trip/bounds/corrupt/fuzz), payload v6 + v5 compatibility, v5/v4 fixture updates, repository persist/preserve/locked-write/backup/clear |

## B. Studio UI (ui-agent; owns `ui/sketch/**`, `ui/library/LibraryScreen.kt`, docs/DRAWING-STUDIO.md)

### Screen
- `SketchStudioScreen(repository: NoteRepository, itemId: String, onClose: () -> Unit, modifier: Modifier = Modifier)`; root tag `sketch_studio`; loading state; back tag `sketch_back` exits (flush pending save first).
- A4-portrait page centred with margins; templates drawn behind content: blank/ruled/grid/dotted (token-derived colours); tag `sketch_page`.
- Page supports pinch zoom + pan (bounded; double-tap or button `sketch_zoom_reset` fits page), while single-pointer drawing is active in draw mode. Buttons/tags `sketch_zoom_in`, `sketch_zoom_out`.

### Tools (tags; strings pre-added `sketch_*`)
- Pen `sketch_tool_pen` (`SketchInkKind.PEN`), highlighter `sketch_tool_highlighter` (translucent), eraser `sketch_tool_eraser`, lasso `sketch_tool_lasso`, shape menu `sketch_tool_shape` with `sketch_shape_line`/`sketch_shape_rect`/`sketch_shape_ellipse`/`sketch_shape_arrow`.
- Palette: 7 ink swatches `sketch_colour_0..6`, 3 widths `sketch_width_0..2`, template chips `sketch_template_blank/ruled/grid/dotted`; token-derived colours only (no new hex outside `ui/theme` or a small `SketchPalette` using `NoteZeroPalette`).
- Undo `sketch_undo` / redo `sketch_redo` (bounded ≥50 steps, disabled when empty); clear `sketch_clear` with confirmation dialog `sketch_clear_dialog` (undoable; tags `sketch_clear_confirm`/`sketch_clear_cancel`).
- Lasso: drag a free-path; on release remove strokes/shapes whose sampled points/centre lie inside the lasso polygon (simple point-in-polygon; ≥50% of a stroke's points for strokes, both endpoints or centre for shapes). Undoable; hint `sketch_lasso_hint`.
- Shape: two-point drag creates `SketchShape` (line/rect/ellipse/arrow) rendered with pen/highlighter ink and width.
- Eraser: drag removes strokes/shapes near the path (normalised tolerance); undoable.
- Title: top bar shows `item.title`; rename action `sketch_rename` → dialog `sketch_rename_hint`/`sketch_rename_save`; empty → `sketch_untitled`.
- Autosave: `repository.saveSketch(itemId, documentOrNull)` after each committed mutation (debounce ≥400 ms is fine) and on exit; silent failure while locked (non-secret `sketch_save_failed` may be shown if consistently failing while unlocked). Load `item.sketch` on open.
- Preview PNG: render the document in memory (`android.graphics.Bitmap`+`Canvas`) at a bounded size (e.g. ≤1600 px long edge) and import as the sketch's preview attachment when leaving the studio (or after a pause): reuse `repository.importAttachment` → delete the previous preview via `repository.deleteAttachment`; displayName prefix `sketch-` + `.png`, mime `image/png`; never write a plaintext file/cache; failures are silent (preview only).
- Empty state hint `sketch_empty_hint`; no content logging.

### Library integration (`LibraryScreen.kt`)
- `LibraryEntry.attachment` becomes nullable so a sketch without a preview still appears; drawings filter lists both note-owned drawings (unchanged) and `ItemKind.SKETCH_FILE` entries.
- `library_new_sketch` action (e.g. second item in the import/dropdown or a FAB visible in the Drawings filter): creates `repository.createItem(ItemKind.SKETCH_FILE)` with a non-blank default title (`sketch_new_name`), saves it, then opens the studio.
- Tapping a `SKETCH_FILE` entry opens the studio (not a static preview). Overflow keeps open/rename/lock/trash; rename uses the studio/label dialog.
- New-sketch creation + failed save must leave no orphan row (trash it like the import failure path).

### Tests (ui-agent)
- Robolectric with `FakeNoteRepository`: create/open sketch, draw a pen stroke (touch gesture), highlighter kind, shape drag, eraser removes, lasso removes, template switch persists, undo/redo, rename, autosave persists vector through repository, preview attachment exists after exit (and old preview replaced), library shows sketch entries and the New drawing action.
- Instrumented: real in-memory PNG renderer produces a valid PNG (`BitmapFactory.decodeByteArray` non-null, bounded size) and hostile/oversized sketches never crash; optional studio smoke test.
- All strings via `stringResource`; 48dp targets; no TODO/log/network/plaintext files; no new dependencies/permissions.
