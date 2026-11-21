# NoteZero — Phase-17 (increment 18) contract: full-document lazy PDF viewer

Frozen for increment 18. UI/tests address these names only.

## Goal

Remove the viewer's **8-page raster cap**: open any PDF and scroll/jump/bookmark/annotate every
page, rendering each page **lazily only while it is composed** from one open, ephemeral PDF handle.
No plaintext thumbnail/page cache; pages are in-memory bitmaps that are recycled as items leave
composition. Annotated export and page-tools copies both use the existing all-pages
`PdfPageToolsExporter` path (up to its 100-page cap), so the annotated-copy feature no longer
depends on the viewer having rasterised every page up front.

This replaces `PdfPageRenderer`/`PdfPages` for the viewer. `PdfFlattenedExporter` and its tests may
stay as a utility, but the viewer must no longer use the eager first-8-pages renderer.

## A. Page session (`ui/pdf/PdfPageSession.kt`)

```kotlin
interface PdfPageSession : AutoCloseable {
    val pageCount: Int
    suspend fun render(pageIndex: Int, maxEdgePx: Int = 1600): Bitmap?
}
fun interface PdfPageSessionFactory {
    suspend fun open(context: Context, pdfBytes: ByteArray): PdfPageSession?
}
```

- `AndroidPdfPageSessionFactory` implementation:
  - empty bytes / corrupt PDF / missing native renderer → `null` (never throw, no log);
  - writes decrypted bytes to one random app-private `cacheDir/pdf-session-<uuid>.pdf`, opens
    `ParcelFileDescriptor`, **unlinks the file immediately after opening** so no plaintext path
    exists for the session; falls back to delete-on-close if unlink fails;
  - keeps `PdfRenderer` open for the session; `pageCount` from the document; `render(pageIndex,
    maxEdgePx)` validates the index, renders on `Dispatchers.IO` under a `Mutex` (PdfRenderer is
    not thread-safe), white ARGB bitmap, long edge ≤ maxEdgePx (≤1600) and never upscaled; returns
    `null` on any failure, recycles its bitmap on failure; catches OOM/Throwable, rethrows
    `CancellationException`; idempotent `close()` closes pages/renderer/PFD and deletes the temp
    path if it still exists; no logging.
  - hard bounds: one open session per viewer; no page-count allocation beyond an `Int`;
    `render` accepts only `0 <= index < pageCount`; a hostile page count is tolerated by lazy
    item count (add `MAX_VIEW_PAGES = 2000` guard; above that the viewer shows the existing error
    instead of allocating rows).
- Unit tests for the factory contract with fakes; instrumented real `PdfRenderer` test on a
  multi-page PDF: arbitrary page (e.g., 10), page count, `close` idempotence, no temp file left,
  corrupt/empty → null, out-of-range → null.

## B. Viewer refactor (`PdfViewerScreen.kt`)

- Parameters: replace `renderer: PdfPageRenderer` with
  `pageSessionFactory: PdfPageSessionFactory = remember { AndroidPdfPageSessionFactory() }`;
  remove the viewer's use of `flattenedExporter`/`PdfPages`. Keep the existing injected page-tools
  exporter/saver and export gateway; use `pageToolsExporter` for the annotated copy too.
- Load:
  - read attachment bytes, `open` session; `pageCount`; initial position from reading state
    (`coerceIn`); persist total = pageCount; no eager page rendering; on failure show `pdf_error`.
  - close/recycle session on disposal/item change/lock; bitmaps recycled by their page items.
- Lazy rendering + page items:
  - `LazyColumn(items(count = pageCount))` tagged `pdf_pages`; each item is a new
    `PdfPageItem(pageIndex, session, annotations, annotateMode, ...)`:
    - `produceState`/`LaunchedEffect` calls `session.render(pageIndex)`; while loading show a
      bounded A4-aspect placeholder with `pdf_page_loading` (no spinner thrash); on failure show
      `pdf_page_render_failed`; `DisposableEffect` recycles the page bitmap when the item leaves
      composition (never recycle a bitmap still displayed).
    - keep the existing `PageImage`/annotation overlay semantics (annotations keyed by page index,
      normalised coordinates, gestures disabled outside annotate mode); page tag remains
      `pdf_page_<index>`/`pdf_pages` as tests expect.
    - A4 placeholder aspect ratio 1:1.414 until the bitmap gives its aspect.
  - Reading progress/current page/bookmarks/page jump use `pageCount`; scrolling is smooth; jump
    to page 10/50 must work.
  - Remove the `pdf_pages_capped` truncation notice/string usage; old strings may remain unused.
- Thumbnails:
  - existing lazy thumbnail load/renderer unchanged, but the Pages overview grid now covers
    `min(pageCount, thumbnails.size)` (up to 100) instead of `pages.renderedPages`; numeric field
    keeps full range; bookmark thumbnails unchanged. (If thumbnails have not loaded, grid hidden +
    loading/unavailable states as today.)
- Annotated export:
  - replace `flattenedExporter.export(pages, layer)` with
    `pageToolsExporter.export(context, bytes, PdfPagePlan.of(pageCount), layer)`; the plan must be
    valid (≤100 pages) — above `PdfPagePlan.MAX_PAGES` show `pdf_annotated_export_too_many` and do
    not write; re-read bytes at export time; same SAF warning/lock re-check/notice behaviour.
  - keep the existing `flattenedExporter` tests? Viewer no longer accepts it; update tests. The
    `PdfFlattenedExporter` class/tests may remain unused by the viewer.
- Annotations/page tools/thumbnail surfaces must keep working for pages beyond 8 (annotate page 10,
  bookmark page 12, page-tools previews).
- Keep all existing tags/behaviour where possible (`pdf_viewer`, `pdf_back`, `pdf_annotate_*`,
  `pdf_bookmark_*`, `pdf_page_tools`, `pdf_export`, `pdf_page_progress`, `pdf_page_jump_*`,
  bookmark tags, page-tools tags).

## C. Strings

Pre-added: `pdf_page_render_failed`, `pdf_page_loading`, `pdf_annotated_export_too_many`
(count now 663). Do not edit `res/`.

## D. Tests

- New JVM/Robolectric page-session tests with fakes; viewer tests: fake session with pageCount 12
  renders pages lazily (only composed pages requested), scroll/jump page 10 works, page item
  dispose recycles (where observable), annotated export uses `pageToolsExporter` with an identity
  plan and shows too-many above 100, thumbnails grid covers all 12 (fake thumbnails), bookmark on
  page 12 works.
- Instrumented real `PdfRenderer` session test (arbitrary page, close/idempotence, no temp file,
  corrupt/empty/out-of-range).
- Update existing `PdfViewerScreenTest`/PDF tests; keep page-tools/thumbnail tests green.
- Full suite should not regress; report exact counts.

## E. Docs (agent)

Create `docs/PDF-LAZY-VIEWER.md` (session lifecycle, unlinked-temp security trade-off, bounds,
tests, limitations) and update `docs/PDF-THUMBNAILS.md`/`docs/PDF-PAGE-TOOLS.md` pointers if their
"8-page viewer cap" note becomes stale. Do not edit root docs — Lead updates README/CHANGELOG/
ROADMAP/TESTING/DECISIONS/THREAT-MODEL/VERIFICATION.

## F. Lead integration

Review, full suites/build/instrumented, device QA on the 12-page PDF (scroll to last page, jump
10, annotate page 10, bookmark page 12, Pages grid all 12, page tools all 12, annotated export),
screenshots, root docs, reproduc/release, commit/push/CI/memory.
