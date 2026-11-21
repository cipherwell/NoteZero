# PDF visual page navigation — in-memory page thumbnails

Phase-16 increment 17, section C of `docs/CONTRACTS-PHASE16.md`. This document describes the
shipped thumbnail renderer, the lazy viewer integration, the three visual surfaces (Pages
overview, bookmark rows, page-tools rows), the resource/security bounds, all test tags,
limitations and the device-QA watch list.

Scope is deliberately small: thumbnails are rendered **in memory only** for the lifetime of one
viewer session. There is no thumbnail cache, no database column, no file in the vault or app
storage and no data-model change; the renderer's one temporary seekable PDF is app-private,
exists only for the duration of the render and is deleted before any bitmap is shown. No new
dependency, permission, network access or top-bar action was added.

## Files

| File | Contents |
|---|---|
| `ui/pdf/PdfThumbnails.kt` | `PdfThumbnails` (bounded bitmap set + idempotent `recycle`), `PdfThumbnailRenderer` seam, `AndroidPdfThumbnailRenderer(maxPages = 100, longEdgePx = 144)` |
| `ui/pdf/PdfViewerScreen.kt` | Lazy-load state/coroutine, dispose/recycle, Pages overview dialog (grid + numeric fallback), bookmark-row thumbnails, injected `thumbnailRenderer`, pass-through to page tools |
| `ui/pdf/PdfPageToolsScreen.kt` | Optional `thumbnails: PdfThumbnails? = null`; 56×72 dp preview per page row (source page rotated by `quarterTurns * 90f`, labelled placeholder for blank entries) |
| `test/.../ui/pdf/PdfThumbnailsTest.kt` | Wrapper unit tests (4) |
| `test/.../ui/pdf/PdfThumbnailsUiTest.kt` | Overview grid/jump/fallback/loading + bookmark-row tests with fake renderer (7) |
| `test/.../ui/pdf/PdfViewerScreenTest.kt` | Extended with lazy-load-once and dispose-recycle viewer tests (13 total) |
| `test/.../ui/pdf/PdfPageToolsScreenTest.kt` | Extended with source/blank preview and viewer-hosted lazy-load tests (23 total) |
| `androidTest/.../ui/pdf/PdfThumbnailsInstrumentedTest.kt` | Real `PdfRenderer` validation on a genuine 3-page PDF (5) |

## Thumbnail renderer

`fun interface PdfThumbnailRenderer { suspend fun render(context, pdfBytes): PdfThumbnails }`.

`PdfThumbnails` is a tiny caller-owned value:

| Member | Behaviour |
|---|---|
| `size` | number of rendered thumbnails; valid page indices are `0 until size` |
| `bitmap(page)` | the 0-based page thumbnail, or `null` for an unrendered/out-of-range page |
| `isEmpty` | true for a failed/empty render (never a "broken" grid) |
| `recycle()` | recycles every bitmap and empties the set; idempotent |

`AndroidPdfThumbnailRenderer` renders with the platform `android.graphics.pdf.PdfRenderer` on a
background dispatcher (`Dispatchers.IO`, the same pattern as `AndroidPdfPageRenderer`):

- one white `ARGB_8888` bitmap per page, scaled so the **long edge is at most 144 px** and never
  upscaled beyond the PDF's own page size (`PdfRenderer.Page.width/height` in points);
- at most **100 pages** are rendered (`maxPages`), so the worst-case heap is bounded to about
  5.8 MiB of ARGB pixels for a document of A4 pages; a zero/negative cap renders nothing;
- **fails closed**: empty bytes, a corrupt PDF, a hostile zero-size page, a missing native
  renderer or an OOM all return an empty `PdfThumbnails` and recycle anything already allocated —
  never a partial/corrupt grid and never an exception to the UI;
- coroutine cancellation is rethrown as-is (and any bitmaps already produced are recycled);
- no logging, no analytics, no persisted thumbnail.

### Temporary file and security

`PdfRenderer` again requires a **seekable file descriptor**, so the decrypted bytes are written to
one ephemeral app-private cache file (`cacheDir/pdf-thumbs-<random UUID>.pdf`), deleted in a
`finally` block before the `PdfThumbnails` is returned. This is the documented
`AndroidPdfPageRenderer` trade-off (`DECISIONS` #20 / `THREAT-MODEL`): one file, app-private
cache (excluded from backup), short-lived, and its contents are the same decrypted document the
viewer is already displaying. A random name per render means two renders can never collide. The
decrypted bytes themselves are re-read from the encrypted vault by the viewer at thumbnail-load
time and live only inside that one coroutine — never in composition state, never on disk after the
render, and never longer than the load needs them.

## Viewer integration (lazy, once per session)

`PdfViewerScreen` gained one defaulted injectable seam, `thumbnailRenderer: PdfThumbnailRenderer =
remember { AndroidPdfThumbnailRenderer() }`; tests inject a fake with tiny in-memory bitmaps.

State per viewer session: `thumbnails: PdfThumbnails?`, `thumbnailsLoading`, `thumbnailsLoaded`,
and one-shot `thumbnailsRequested`.

- Nothing is rendered when the document first opens. The first time the user opens **any**
  thumbnail surface — the Pages overview (`pdf_page_progress`), the bookmarks panel
  (`pdf_bookmarks_open`) or the page-tools overlay (`pdf_page_tools`) — that surface sets
  `thumbnailsRequested`, and a single `LaunchedEffect` re-reads
  `repository.attachmentBytes(itemId, attachmentId)`, calls the renderer once, stores the result
  and sets the loaded flag. Later surfaces reuse the in-memory set; the bytes are never held.
- Failure (missing attachment, locked vault mid-load, renderer exception/empty result) stores an
  empty `PdfThumbnails`; the Pages overview then shows `pdf_thumbnails_unavailable` and the
  numeric page field remains the working fallback. The empty result is not retried for that
  viewer session.
- A `DisposableEffect(thumbnails)` recycles the set when it is replaced (item change) and when
  the viewer leaves the composition; a vault lock replaces the viewer at the shell level, so the
  same dispose path frees the bitmaps. Reopening the document renders again. The set is never
  recycled while it is still attached to the composition.
- All existing viewer tags/behaviour are preserved: `pdf_viewer`, `pdf_back`, `pdf_title`,
  `pdf_page_progress`, `pdf_annotate_toggle`, `pdf_export`, `pdf_page_tools`,
  `pdf_bookmarks_open`, annotation editing, page tools, export, reading-state persistence.

## UI and tags

### Pages overview (the upgraded page-jump dialog)

The dialog tag remains `pdf_page_jump_dialog`; the title now shows `pdf_page_overview` ("Pages")
and the hint copy is `pdf_page_overview_hint`.

When thumbnails are available the dialog shows a bound-height (max 360 dp) 3-column
`LazyVerticalGrid` of page cells: thumbnail, 1-based page label and a primary-colour
border/label for the current page. Tapping a cell scrolls the pager to that page and closes the
dialog. The grid only offers pages the viewer has actually rasterised (`PdfPages`), because the
viewer's own page renderer is capped at 8 pages — a cell is never a dead navigation target. Below
the grid the existing numeric `OutlinedTextField` (`pdf_page_field`), error (`pdf_page_error`),
Go (`pdf_page_jump_confirm`) and Cancel (`pdf_page_jump_cancel`) remain unchanged: this is the
keyboard fallback and the only way to address document pages beyond the rendered set.

While the render is in flight the dialog shows a progress row (`pdf_thumbnails_loading`) and the
numeric field is already usable. If the load produced nothing, the dialog shows
`pdf_thumbnails_unavailable` plus the numeric field — never a broken or partial grid.

Tags:

| Tag | Element |
|---|---|
| `pdf_page_jump_dialog` | overview dialog (unchanged) |
| `pdf_page_overview_title`, `pdf_page_overview_hint` | dialog title ("Pages") and hint |
| `pdf_page_overview_grid` | 3-column scrollable thumbnail grid |
| `pdf_page_thumb_<index>` | one page cell (0-based index); tap = jump + close |
| `pdf_thumbnails_loading` | progress row while rendering |
| `pdf_thumbnails_unavailable` | fallback copy when the render failed/was empty |
| `pdf_page_field`, `pdf_page_error`, `pdf_page_jump_confirm`, `pdf_page_jump_cancel` | unchanged numeric fallback |
| `cd_pdf_page_thumbnail` | content description of a thumbnail image (1-based page) |

### Bookmark rows

The bookmarks panel (`pdf_bookmarks_dialog`, rows `pdf_bookmark_row`) triggers the same lazy load.
Each row renders a 48×64 dp rounded thumbnail of the bookmark's page before the label once the
set is loaded, with content description `cd_pdf_page_thumbnail`; tag `pdf_bookmark_thumbnail`.
While loading or after a failure the row keeps its previous text-only layout — no broken image,
no placeholder icon — and the rest of the row (label, page caption, delete action) is unchanged.

### Page-tools rows

`PdfPageToolsScreen` gained `thumbnails: PdfThumbnails? = null` (default keeps every existing
call site/test working). Each planned page row gained a 56×72 dp preview before the position /
source / rotation labels:

- source page entry → that source page's thumbnail, visually rotated clockwise by
  `quarterTurns * 90f` (a `Modifier.rotate` graphics-layer rotation; the bitmap itself is not
  re-rendered), content description `cd_pdf_page_tools_thumbnail`;
- blank entry → a light placeholder showing the blank page label (no bitmap);
- source entry whose thumbnail is unavailable (still loading / failed) → the same light
  placeholder, no broken image.

Existing row tags and controls are untouched; the five 48 dp controls keep their repeatable tags
and are still addressed with `hasAnyAncestor(hasTestTag("pdf_page_row_<index>"))`. Opening the
overlay triggers the lazy load and the viewer passes the loaded set in.

## Strings

Pre-added in `res/values/strings.xml` and used as-is (no new strings, `res/` untouched):
`pdf_page_overview`, `pdf_page_overview_hint`, `pdf_thumbnails_loading`,
`pdf_thumbnails_unavailable`, `cd_pdf_page_thumbnail`, `cd_pdf_page_tools_thumbnail`.

## Tests

- `PdfThumbnailsTest` (4): 0-based lookup/bounds, empty set, recycle-every-bitmap and
  idempotent recycle (Robolectric bitmaps only).
- `PdfThumbnailsUiTest` (7): overview title/hint/grid/cell tags and one cell per rendered page;
  tapping a cell jumps and closes; renderer failure shows `pdf_thumbnails_unavailable` and the
  numeric field still jumps; loading row then grid after render completes; bookmark thumbnail
  appears with the right content description once loaded, stays text-only while loading and is
  absent after a failed render.
- `PdfViewerScreenTest` (13 total, 2 new): thumbnails are not rendered before a thumbnail surface
  opens, are rendered exactly once for the session across Pages/bookmarks/page tools (and receive
  the decrypted attachment bytes), and every bitmap is recycled when the viewer is disposed.
- `PdfPageToolsScreenTest` (23 total, 3 new): source rows show a 56×72 dp preview with the page's
  thumbnail, blank rows show a labelled placeholder without a bitmap, and opening page tools from
  the viewer lazily renders and displays the previews.
- `PdfThumbnailsInstrumentedTest` (5, device/emulator only): real 3-page A4 fixture
  (red/green/blue) gives 3 bounded portrait thumbnails with the right page colours and a white
  blank-page thumbnail; empty/corrupt bytes fail closed to an empty set; `maxPages`/`longEdgePx`
  caps are enforced; a huge long edge never upscales; no `pdf-thumbs-*` cache file survives any
  render (including failures).

Robolectric tests use `@Config(sdk = [34])` only (no display qualifiers), fake thumbnail/page
renderers for every native PDF class, and the existing fake repository. Merged clickable rows are
addressed with `useUnmergedTree = true` for their child thumbnail tags.

Reproduce with:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
./gradlew :app:compileDebugKotlin --console=plain
./gradlew :app:testDebugUnitTest --tests "com.cipherwell.notezero.ui.pdf.*" --console=plain
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PallowScreenshots=true --console=plain
```

## Limitations

- **In-memory only, per session.** Thumbnails are rendered again on every viewer open; a
  100-page document pays one bounded render pass the first time a thumbnail surface opens. They
  are deliberately not persisted (plaintext/cache-free requirement), so no cross-session speedup.
- **Viewer page cap still applies.** The existing viewer rasterises at most 8 pages
  (`AndroidPdfPageRenderer`), so the overview's thumbnail cells only cover those rendered pages;
  the numeric field is the only navigation for the rest of a long document. The renderer itself
  is capped at 100 pages for reuse by the page-tools previews and future work.
- **Thumbnails are approximate page previews**, not a substitute for the rendered page: detail is
  at most 144 px long edge, so fine print is unreadable in a thumbnail by design.
- **Page-tools previews are visual only.** Rotation is applied with a graphics layer; the actual
  flattening rotation remains the existing exporter's job, and previews are not interactive.
- **No retry.** A failed thumbnail load in one session shows `pdf_thumbnails_unavailable` until
  the viewer is reopened (the empty set is kept for that session).
- No text search inside the overview, no thumbnail zoom, no plaintext thumbnail cache by design.

## Device-QA watch list

- 3-page PDF: open Pages — three thumbnails with readable page numbers, the current page
  highlighted; tap page 2/3 and verify the viewer lands on that page and the dialog closes.
- >8-page PDF: Pages shows thumbnails for the rasterised pages only; typing a higher page number
  in the field still navigates/behaves as today; `pdf_pages_capped` copy remains visible.
- >100-page or unreadable PDF: opening Pages must show `pdf_thumbnails_unavailable` (or the
  capped state) and the numeric field must stay usable — no ANR and no broken grid.
- Bookmarks: open the panel — rows show 48×64 dp thumbnails of their pages (independently of the
  current page); while the first load runs, rows are text-only; a failed load leaves rows
  text-only without layout jumps.
- Page tools: open it — every source row shows the correct page preview; rotate a row and check
  the preview turns with it; insert a blank page and verify the labelled placeholder; delete a
  page and verify its thumbnail disappears from the rows.
- Memory/vault: open a large PDF, open Pages, leave the viewer, confirm smooth reopening
  (bitmaps recycled); lock the vault while the viewer is open and confirm no thumbnail/window
  leak and no stale bitmap after unlock.
- Small screen (320 dp) / large font: overview dialog stays scrollable with Go/Cancel reachable,
  grid cells keep tap targets, page-tools rows remain scrollable with the 56×72 dp previews and
  48 dp controls.
- Dark and light theme: thumbnail white page background is still legible, current-page emphasis
  visible, placeholders not invisible.
- Repeated open pages/bookmarks/page tools within one viewer session must not re-render (no
  visible delay, no memory growth in `dumpsys meminfo`).
