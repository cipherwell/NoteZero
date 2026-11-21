# NoteZero — Phase-15 (increment 16) contract: PDF page tools

Frozen for increment 16. UI/tests address these names only.

## Goal

Ship the queued PDF page operations from `docs/ROADMAP-ALL-IN-ONE.md` Phase 11:
**rotate / delete / reorder / insert-blank pages**, applied by creating a **new flattened,
encrypted PDF copy** in the vault. The original PDF and its annotation layer are never modified.
This keeps the existing honest scope: no text-object editing, no external PDF library, no network.

Entry point: the PDF viewer top bar, non-annotate mode, action tag `pdf_page_tools`
(contentDescription `cd_pdf_page_tools`), enabled when a PDF is loaded and
`pages.totalPages > 0`.

## A. Pure page plan (`ui/pdf/PdfPagePlan.kt`, plain JVM)

- `data class PdfPageEntry(val sourcePage: Int?, val quarterTurns: Int)` — `sourcePage == null`
  means a blank page; `quarterTurns` normalized 0..3 (clockwise).
- `class PdfPagePlan` (or immutable data + object operations) with:
  - `fun of(totalPages: Int): PdfPagePlan` (one entry per source page, rotation 0);
  - `fun rotateLeft(position: Int)`, `rotateRight(position: Int)`;
  - `fun moveUp(position: Int)`, `moveDown(position: Int)` (no-op at bounds);
  - `fun delete(position: Int)` (refuse the last remaining entry; return a result/unchanged plan
    so the UI can show `pdf_pages_last_page`);
  - `fun insertBlank(afterPosition: Int? = null)` (append when null; cap at `MAX_PAGES`);
  - `fun reset(): PdfPagePlan` (initial order/rotation);
  - `val entries: List<PdfPageEntry>`, `val size: Int`, `val isValid: Boolean`.
- Bounds: `MAX_PAGES = 100`; total/positions bounded; no duplicates/out-of-range source indices in
  any public plan operation; operations are pure and never throw on hostile positions.
- Tests cover every operation, boundary, rotation normalization, last-page delete, cap, reset and
  hostile args.

## B. Export (`ui/pdf/PdfPageToolsExporter.kt` + shared drawing)

- `fun interface PdfPageToolsExporter { suspend fun export(context: Context, pdfBytes: ByteArray,
  plan: PdfPagePlan, annotations: PdfAnnotations): ByteArray? }`
- `AndroidPdfPageToolsExporter(maxPages = 100, pageWidthPt = A4, pageHeightPt = A4)`:
  - writes decrypted bytes to a **single ephemeral app-private cache file** (same documented
    `PdfRenderer` trade-off as `AndroidPdfPageRenderer`; deleted in `finally`), opens
    `PdfRenderer`, and for each plan entry:
    - blank -> white A4 page;
    - source -> render that source page at a bounded resolution (≤1600 px long edge), draw it
      scaled-to-fit on a white A4 page with the entry's clockwise quarter-turn applied;
    - annotations for that source page are drawn in bitmap space on the same rotated canvas
      (they rotate/move with the page; annotations of deleted pages are gone — warning covers it);
  - returns null on any failure (bad plan, >100 pages, corrupt PDF, missing native renderer, OOM,
    empty bytes) and never throws/logs;
  - recycles every bitmap/page/document resource; output bytes owned only by the caller.
- Extract/share the existing draw-one-page logic with `PdfFlattenedExporter` so annotation
  rendering cannot drift (internal helper; public `PdfFlattenedExporter` behaviour unchanged).
- Instrumented test with a real multi-page PDF verifies page count/order and clean failure for a
  corrupt plan/fixture; Robolectric gets an injected fake so UI tests never touch native PDF.

## C. Saving the copy (`ui/pdf/PdfPageCopySaver.kt`)

- `fun interface PdfPageCopySaver { suspend fun save(repository: NoteRepository, source: Item,
  displayName: String, bytes: ByteArray): String? }` returning the new item id (or null).
- Default implementation: create `ItemKind.PDF_FILE` in the **source item's folder**, title =
  localized/suffixed copy name (`pdf_pages_copy_suffix`, max length bounded), import the bytes via
  `repository.importAttachment(...)`, trash the new item on any import failure so no orphan row/blob
  remains. Never writes plaintext outside the encrypted attachment pipeline; never logs.
- Name: source title/file label base + `pdf_pages_copy_suffix` + `.pdf`, sanitized/bounded
  (reuse existing display-name helpers where possible), e.g. `report (pages).pdf`.
- Injectable so screen tests can assert the save call without repository encryption.

## D. UI (`ui/pdf/PdfPageToolsScreen.kt` + viewer integration)

- Full-screen overlay/dialog (tag `pdf_page_tools_screen`) opened from the viewer action; title
  `pdf_pages_title`; warning text `pdf_pages_warning` visible without scrolling on small screens;
  hint `pdf_pages_hint`; scrollable `LazyColumn` of page rows (`pdf_page_row`).
- Each row: position label (`pdf_pages_position`), source label (`pdf_pages_source` or
  `pdf_pages_blank`), rotation label (`pdf_pages_rotation`), controls with 48dp targets:
  rotate left/right (`pdf_page_rotate_left/right`), move up/down
  (`pdf_page_move_up/down`), delete (`pdf_page_delete`), disabled at bounds with clear semantics.
- Bottom actions: `pdf_page_insert_blank` (appends a blank page at the end), `pdf_pages_reset`,
  `pdf_pages_cancel`, primary `pdf_pages_save_copy` (disabled/labelled `pdf_pages_saving`
  while saving). Last-page delete shows `pdf_pages_last_page`; >100 pages shows
  `pdf_pages_too_many` and no save. Changes set a non-secret dirty flag/notice
  `pdf_pages_changed`.
- Viewer integration:
  - add top-bar action `pdf_page_tools` (right of bookmarks, before export) when loaded;
  - state: show tools, current plan, save busy/notice; initialize plan from `pages.totalPages`;
  - Save flow: read decrypted attachment bytes via `repository.attachmentBytes`, re-check
    vault unlocked and item alive before writing; export on `Dispatchers.Default`; call
    `PdfPageCopySaver`; show `pdf_pages_saved` with the new title, `pdf_pages_export_failed` /
    `pdf_pages_save_failed` / `pdf_pages_locked` accordingly; close the overlay on success and
    leave the original viewer open. No plaintext file/URI is created (copy is an encrypted item).
  - Inject exporter/saver/host-coroutine dispatcher for tests; default real implementations.
  - The annotation layer used is the viewer's current `editor.value` (unsaved annotations are
    included in the copy; original layer untouched).
- Back while tools open closes only the tools overlay. Existing tags/behaviour
  (`pdf_viewer`, `pdf_back`, `pdf_annotate_toggle`, `pdf_export`, `pdf_pages`, etc.) stay stable.

## E. Strings

Pre-added in `res/values/strings.xml`: all `pdf_pages_*`, `pdf_page_*`, `cd_pdf_pages_*`,
`pdf_page_tools`/`cd_pdf_page_tools`. Do not add hardcoded UI text; no logging of titles/content.

## F. Tests

- Pure: `PdfPagePlanTest` (operations/bounds/rotation/cap/last-page/hostile args).
- Robolectric: `PdfPageToolsScreenTest` (open/close, warning, rotate/move/delete/insert/reset,
  last-page notice, save wiring via fake exporter+saver, cancel saves nothing, too-many pages,
  locked/export-failed notices) and extend `PdfViewerScreenTest` for the new action (loaded PDF,
  no PDF disabled, open/close Back).
- Instrumented: real `AndroidPdfPageToolsExporter` on a 2–3 page PDF fixture: page count, order,
  blank insert, rotated page survives (page open), corrupt input null.
- Keep all existing PDF/instrumented/E2E tags green; no new dependencies/permissions.
- Tests must not use display qualifiers with Robolectric (`@Config(sdk=[34])` only).

## G. Docs (agent)

Create `docs/PDF-PAGE-TOOLS.md` (implementation, bounds, security, tags, limitations). Do not edit
README/CHANGELOG/ROADMAP/TESTING/VERIFICATION/DECISIONS — Lead handles those after review.

## H. Lead integration

Review diff, run full suites/build/instrumented, device QA with a multi-page PDF (rotate/delete/
reorder/insert, save copy, verify encrypted new item opens at expected order), screenshots,
README/CHANGELOG/ROADMAP/TESTING/DECISIONS/THREAT-MODEL/VERIFICATION + fastlane, reproducibility,
release artifacts, commit/push/CI/memory.
