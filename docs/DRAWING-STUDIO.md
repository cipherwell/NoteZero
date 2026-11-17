# Drawing studio (standalone sketches)

The drawing studio is the standalone sketch surface introduced with `ItemKind.SKETCH_FILE`
(Phase-11 increment 12). A sketch is an **editable, encrypted vector document** that lives
inside the normal item envelope; the flattened PNG preview on the Library shelf is a derived,
encrypted attachment, never the source of truth.

Contract: [CONTRACTS-PHASE11.md](CONTRACTS-PHASE11.md) sections A (data layer) and B (studio).
Data-layer ownership (codec `NZSK` v1, `ItemCodec` payload v6, repository `saveSketch`) is
documented there and in [DATA-MODEL.md](DATA-MODEL.md); this file covers the UI.

## Files

| File | Role |
|---|---|
| `ui/sketch/SketchStudioScreen.kt` | Full-screen studio: page, gestures, autosave, exit flush/preview, rename, clear. |
| `ui/sketch/SketchStudioControls.kt` | Top bar, tool/ink/paper rows, zoom overlay, clear + rename dialogs. |
| `ui/sketch/SketchCanvasDrawing.kt` | Compose `DrawScope` rendering of template + strokes + shapes + gesture guides. |
| `ui/sketch/SketchEditorState.kt` | Pure editing state machine: document, tool selection, bounded undo/redo, bounds drop. |
| `ui/sketch/SketchGeometry.kt` | Pure geometry: templates, arrow heads, eraser hit-test, lasso point-in-polygon. |
| `ui/sketch/SketchRenderer.kt` | In-memory `SketchDocument` → flattened PNG (the only Android-aware preview code). |
| `ui/sketch/SketchPalette.kt` | Token-derived paper/ink colours and stroke-width rules (no raw colours elsewhere). |
| `ui/library/LibraryScreen.kt` | Drawings shelf: sketch rows, new-drawing action, opens the studio. |

## Model and page

The frozen model is `model/SketchDocument.kt`:

* A4 portrait page, `width / height = 0.7071`; **all geometry is normalised to 0..1**, so the
  vector survives rotation, zoom, export resolution and the PNG preview's different pixel size.
* Templates: `BLANK`, `RULED`, `GRID`, `DOTTED` (same constants in the Compose preview and the
  PNG renderer, `SketchGeometry.RULED_LINES` / `GRID_COLUMNS` etc.).
* Ink: 7 palette entries derived from `ui/theme/Tokens.kt` (`NoteZeroPalette` + two HSV
  derivations for orange/purple, matching the note-owned drawing palette).
* Widths: `THIN` / `MEDIUM` / `BOLD` = 0.008 / 0.018 / 0.035 of the page short edge.
* Stroke kinds: `PEN` and translucent, wider `HIGHLIGHTER`.
* Shapes: `LINE`, `RECT`, `ELLIPSE`, `ARROW` from two normalised points.

The model enforces the hostile-input bounds itself (≤2,000 strokes, 2..2,000 points/stroke,
≤100,000 total points, ≤500 shapes); the studio never writes an out-of-bounds document — the
state machine clamps/normalises samples, drops non-finite input and ignores strokes/shapes that
would exceed a limit, so the save path cannot throw on user input.

## Studio screen

`SketchStudioScreen(repository, itemId, onClose, modifier = Modifier)` (root tag
`sketch_studio`) loads `item.sketch` once when the decrypted index exposes the item, showing a
loading indicator (`sketch_loading`) until then.

* **Page** (`sketch_page`): A4-portrait sheet centred with margins; templates drawn behind the
  ink. Single-pointer drags draw with the active tool; two pointers pinch-zoom and pan the page
  (bounded `1x..4x`; the page never gaps at `1x`) while draw mode stays active for one pointer.
  Zoom controls (`sketch_zoom_in`, `sketch_zoom_out`, `sketch_zoom_reset`) are pinned to the
  page edge and "fit page" resets zoom and pan.
* **Tools**: pen `sketch_tool_pen`, highlighter `sketch_tool_highlighter`, eraser
  `sketch_tool_eraser`, lasso `sketch_tool_lasso`, shape `sketch_tool_shape` with
  `sketch_shape_line` / `_rect` / `_ellipse` / `_arrow`. Highlighter strokes are translucent and
  wider; a tap becomes a two-point dot.
* **Palette**: 7 ink swatches `sketch_colour_0..6` (content descriptions from
  `cd_drawing_ink_*`), 3 widths `sketch_width_0..2`, template chips
  `sketch_template_blank/ruled/grid/dotted`.
* **Undo/redo** `sketch_undo` / `sketch_redo`: document-snapshot history, bounded to
  `SketchEditorState.MAX_HISTORY = 100` steps (contract minimum 50), disabled when empty.
* **Eraser**: drag removes every stroke/shape whose outline passes within
  `SketchGeometry.ERASER_TOLERANCE` (0.022 normalised) of the drag path; segment-to-segment
  distance, so a fast flick still erases the whole stroke. Undoable.
* **Lasso**: free-path drag; on release, strokes with ≥50 % of their samples inside the polygon
  and shapes with both endpoints or their centre inside are removed (ray-casting
  point-in-polygon). Undoable; hint `sketch_lasso_hint`.
* **Clear** `sketch_clear` opens `sketch_clear_dialog` (`sketch_clear_confirm` /
  `sketch_clear_cancel`), clears the content but keeps the template, and is a normal undo step;
  a transient `sketch_cleared` notice appears.
* **Title** top bar shows `item.title` (fallback `sketch_untitled`); rename `sketch_rename`
  opens a dialog (`_hint`, `_save`) and persists the encrypted title via `saveItem`.
* **Empty state** `sketch_empty_hint` while the page has no content.

All controls are at least 48 dp, rows scroll horizontally so a large system font cannot push a
control off-screen, and every icon-only control has a `contentDescription`. All visible text is
`stringResource`; the palette exposes `selected` state plus a check glyph (never colour-only).

## Autosave and the flattened preview

* **Vector autosave**: every committed mutation (stroke, shape, erase, lasso, template, clear,
  undo/redo) bumps a revision; a debounced effect (`SKETCH_AUTOSAVE_DEBOUNCE_MS = 500`, contract
  minimum 400) then calls `repository.saveSketch(itemId, document.takeUnless { it.isEmpty })`.
  The debounce timer runs on a background dispatcher so it never competes with the UI frame
  clock. A failure while the vault is unlocked shows the non-secret `sketch_save_failed` line
  (`sketch_save_error`); a locked vault fails closed and silently (no write is attempted and no
  plaintext remains outside memory).
* **Exit flush**: `sketch_back` (and system back) calls `requestClose`, which first saves the
  vector, then renders/imports the preview, and only then calls `onClose`. Closing is
  re-entrancy guarded.
* **Preview PNG** (`SketchRenderer`): when the document has content, the studio renders the page
  in memory with `android.graphics.Bitmap`/`Canvas` (A4 portrait, long edge ≤1600 px, encoded
  PNG re-tried at half resolution if it would exceed the 10 MiB attachment cap) and imports it
  through `repository.importAttachment` as one `image/png` attachment named
  `sketch-<timestamp>.png`. The previous preview attachment is deleted
  (`repository.deleteAttachment`) after the new one is in the vault, so a sketch keeps at most
  one preview. For an empty document the stale preview is deleted on exit. Preview failures are
  silent: the vector is already saved and re-opens without it.
* **Security**: the renderer takes no `Context` and touches no file, cache or log; the PNG bytes
  only exist on the heap and are zeroed once the import has consumed them. The preview is a
  normal encrypted attachment (see [ATTACHMENTS.md](ATTACHMENTS.md)) and never a plaintext file.

## Rendering parity

`SketchCanvasDrawing.kt` (Compose) and `SketchRenderer.kt` (Android canvas) share the same
rules: template spacing/counts, ink colours (`SketchPalette`), width fractions, midpoint
quadratic stroke smoothing, highlighter alpha/width multiplier and arrow-head geometry
(`SketchGeometry.arrowHead`). The on-screen page and the flattened preview therefore look the
same; the vector remains authoritative.

## Library integration

* `LibraryEntry.attachment` is nullable: a `SKETCH_FILE` is listed in the Drawings shelf (and
  `ALL`) as soon as its encrypted vector exists, even before a flattened preview has been
  rendered. Sketch rows are labelled `library_type_sketch` ("Drawing") and fall back to
  `sketch_untitled` when the title is blank.
* Tapping a sketch row opens the studio (not the static image preview used by note-owned
  drawings); overflow open/rename/lock/trash keep working through the normal item actions.
* New drawing: the import menu's `library_new_sketch` item and the FAB
  `library_new_sketch_fab` visible in the Drawings filter create `ItemKind.SKETCH_FILE` via
  `repository.createItem`, give it the next default title (`sketch_new_name`, "Drawing N"), save
  it and open the studio. If that save fails, the just-created row is trashed (same pattern as
  the PDF/EPUB import failure path) so no orphan row is left on the shelf; a non-secret
  `sketch_save_failed` notice is shown.

## Tests

| Suite | What it proves |
|---|---|
| `ui/sketch/SketchEditorStateTest` (JVM) | Undo/redo history and bound, clamping/normalisation, dot strokes, template/clear undo, eraser tolerance, lasso selection, shape/ink/width commit, model-bound enforcement, arrow geometry. |
| `ui/sketch/SketchRendererTest` (Robolectric) | Real PNG signature + strict `BitmapFactory` decode, A4 portrait ratio, 1600 px clamp, 10 MiB cap, every template/ink/width/kind/shape, maximum-size hostile document, off-main-thread render, preview naming. |
| `ui/sketch/SketchStudioScreenTest` (Robolectric + `FakeNoteRepository`) | Touch-drawn pen/highlighter strokes, shape drag, eraser, lasso, palette/width/template, undo/redo, clear confirmation + undo, rename, debounced autosave, exit flush, preview attachment import + replacement, stale-preview deletion, empty state and tag surface. |
| `ui/library/LibraryScreenTest` (Robolectric) | Preview-less sketch rows listed/opened in the studio, new-drawing menu + FAB creation, failed creation leaves no orphan row, sketch rows keep the static-preview path for note drawings, nullable `attachment` shelf builder. |
| `ui/sketch/SketchRendererInstrumentedTest` (device/emulator) | Platform encoder/decoder round-trip, valid bounded PNG, A4 ratio, every template/ink/width/shape, maximum-size hostile vector never crashes, clamped unbounded request, in-memory-only (no new files). |

The Robolectric studio tests mount the screen against the shared in-memory repository; the
flattened preview is rendered through the real `SketchRenderer` at a smaller test-only long edge
(`previewMaxDimensionPx`, production default 1600) to keep the shared test JVM heap bounded. The
preview bytes are asserted through the encrypted attachment pipeline (real PNG signature,
decoded with the platform decoder), never through pixels.

## Limitations

* The flattened preview is a snapshot taken when the studio closes (not on every edit), so the
  shelf image can lag the vector until the next exit. Re-opening the sketch always shows the
  current vector.
* The document model has no layer/selection/move/copy-paste support: lasso and eraser are
  delete-only, and shapes have no fill.
* A document that is empty except for a non-blank paper template is the model's "no content"
  value (the repository clears `saveSketch(null/empty)`), so a template chosen on a completely
  blank page is not retained across re-open; choosing a template with any ink is persisted.
* Preview rendering is CPU-work on exit; a maximum-size sketch may take a moment before the
  studio closes.
* No export of the vector (PDF/SVG) is offered; only the encrypted PNG preview exists inside the
  vault.

## Device-QA watch list

* Pinch-zoom while drawing on a stylus (palm rejection is the platform's, not ours) and the
  bounded pan limits on small/landscape phones.
* Text-field cursor/focus behaviour in the rename dialog under the Compose test clock
  (`waitForIdle` is not used while a focused text field is on screen).
* Preview replacement after multiple open/close cycles: exactly one `sketch-*.png` attachment
  should remain per sketch.
* Vault auto-lock while the studio is open: the screen must not write, and the host unmounts it.
* Large system font: the three control rows scroll horizontally and the page keeps usable space.
