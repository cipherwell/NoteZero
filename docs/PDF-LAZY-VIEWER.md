# PDF full-document lazy viewer

Phase-17 increment 18, sections A/B/E of `docs/CONTRACTS-PHASE17.md`. This document describes the
**full-document lazy PDF viewer**: one open page session per viewer, every page reachable, each
page rasterised only while its row is composed, page bitmaps recycled when their rows leave, and
the annotated export moved onto the same all-pages exporter the page-tools copy uses.

Before this increment the viewer eagerly rasterised the **first 8 pages** (`PdfPageRenderer` /
`PdfPages`) into an in-memory bitmap list and showed a "Showing the first 8 pages" notice; the
rest of the document was unreachable. That cap — and the eager `PdfFlattenedExporter` path it fed
— is gone from the viewer. `PdfRender.kt` and `PdfFlattenedExporter.kt` remain as standalone,
instrumented-tested utilities but no viewer code calls them.

Scope stays offline/encrypted: no new dependency, permission, network access, database column or
plaintext cache. Page bitmaps exist only in memory for the viewing session; the decrypted PDF is
written to exactly one app-private cache file which is **unlinked immediately after it is opened**
(no path exists for the whole viewing session), and every page bitmap is recycled when its row
leaves the composition.

## Files

| File | Contents |
|---|---|
| `ui/pdf/PdfPageSession.kt` | `PdfPageSession` (open handle + `pageCount` + `render(page, maxEdgePx)`), `PdfPageSessionFactory` seam, `AndroidPdfPageSessionFactory` / `AndroidPdfPageSession`, `MAX_VIEW_PAGES = 2000`, `PDF_PAGE_MAX_EDGE_PX = 1600`, pure `pageBitmapSize` bounds helper |
| `ui/pdf/PdfViewerScreen.kt` | One session per viewer, `LazyColumn(count = pageCount)` with lazy `PdfPageItem`s, A4 loading/failed placeholders, bitmap recycling, full-range progress/jump/bookmarks/annotations, thumbnails grid, annotated export via `PdfPageToolsExporter` |
| `ui/pdf/PdfRender.kt` | Legacy eager renderer (`PdfPageRenderer`/`PdfPages`, 8-page cap) — kept as a utility; **the viewer no longer uses it** |
| `ui/pdf/PdfFlattenedExporter.kt` | Legacy in-memory flatten helper — kept as a utility; **the viewer no longer uses it** (annotated copy now uses `PdfPageToolsExporter` with `PdfPagePlan.of(pageCount)`) |
| `ui/pdf/PdfThumbnails.kt` | Unchanged in-memory thumbnail set/renderer (100-page, 144 px bounds) used by the Pages overview, bookmarks and page tools |
| `test/.../ui/pdf/PdfPageSessionTest.kt` | Session/factory contract with fakes + bounds math (Robolectric; no native renderer) |
| `test/.../ui/pdf/PdfLazyViewerTest.kt` | 15 lazy-viewer tests (12-page session): one session, only composed pages requested, jump to 10/last, row-dispose recycling, page 10 annotation, page 12 bookmark, 12-page overview grid, identity annotated export, >100-page refusal, lock re-check |
| `test/.../ui/pdf/PdfPageSessionFakes.kt` | `FakePdfPageSession` (records requested pages, bitmaps, closes) and `FakePdfPageSessionFactory` |
| `androidTest/.../ui/pdf/PdfPageSessionInstrumentedTest.kt` | Real 12-page `PdfRenderer` session: arbitrary page 10, bounded downscale, out-of-range, unlinked file, idempotent close, corrupt/empty |

## Page session (`PdfPageSession.kt`)

```kotlin
interface PdfPageSession : AutoCloseable {
    val pageCount: Int
    suspend fun render(pageIndex: Int, maxEdgePx: Int = PDF_PAGE_MAX_EDGE_PX): Bitmap?
}
fun interface PdfPageSessionFactory {
    suspend fun open(context: Context, pdfBytes: ByteArray): PdfPageSession?
}
```

### Lifecycle

1. **Open** (`AndroidPdfPageSessionFactory.open`, on `Dispatchers.IO`): the decrypted attachment
   bytes are written to one random app-private file `cacheDir/pdf-session-<uuid>.pdf`, opened
   read-only as a `ParcelFileDescriptor`, and the `PdfRenderer` is constructed over that
   descriptor. Empty bytes, a corrupt/truncated document, a missing native renderer, OOM or a
   document with no pages return `null` (never throw, never log; the viewer shows `pdf_error`).
2. **Unlink** happens immediately after the descriptor is open: the file's directory entry is
   removed, so for the entire viewing session no plaintext path exists. If a device filesystem
   refuses the unlink (rare), the session falls back to retaining the path and deleting it on
   close.
3. **Render** (`render`) validates `0 <= pageIndex < pageCount`, clamps the requested long edge
   to `1..1600`, and serialises work through a `Mutex` on `Dispatchers.IO` (`PdfRenderer` is not
   thread-safe). Each call produces a fresh white `ARGB_8888` bitmap, long edge at most the
   requested edge **and never upscaled** beyond the PDF's own page size (A4 ⇒ 595×842 px at the
   default edge). On success the bitmap ownership passes to the caller (the page row).
4. **Close** is idempotent (`AtomicBoolean`): it only closes the renderer/descriptor (and deletes a
   fallback path if one exists) when no render is in flight; an in-flight render releases the
   resources on its exit path, so a page render is never destroyed underneath `PdfRenderer`.
   `close()` is called when the viewer leaves, when the item changes, or when a vault lock
   replaces the viewer.

Failure and cancellation contract: any per-page failure (destroyed page, decode error, OOM, a
closed session, a renderer torn down concurrently) returns `null`; the bitmap under construction
is recycled so a failed or cancelled render cannot leak. `CancellationException` is rethrown (the
composition coroutine must end normally). No logging anywhere.

### Bounds

| Bound | Value | Why |
|---|---|---|
| Open sessions per viewer | 1 | one `PdfRenderer` handle per document |
| `MAX_VIEW_PAGES` | 2 000 | a hostile/corrupt page count fails to `pdf_error` instead of allocating rows |
| Long edge of one page bitmap | ≤ 1600 px, never upscaled | a hostile MediaBox cannot exhaust the heap; display quality is resolution-independent |
| Bitmaps per page | 1 | rendered while composed; recycled on row disposal |
| Plaintext files | 1 anyway (unlinked immediately) | see below |

The page count itself is only an `Int`: `LazyColumn(items(count = pageCount))` allocates row
slots lazily, so opening a 900-page document does no raster work until rows are composed.

## Viewer refactor (`PdfViewerScreen.kt`)

- The screen takes `pageSessionFactory: PdfPageSessionFactory` (default
  `remember { AndroidPdfPageSessionFactory() }`) instead of the old eager `renderer`, and no
  longer takes `flattenedExporter`; `pageToolsExporter`, `pageCopySaver`, `exportGateway`,
  `thumbnailRenderer` seams are unchanged.
- **Load:** read `repository.attachmentBytes`, open exactly one session, read `pageCount`, clamp
  the stored reading position with `coerceIn`, persist `total = pageCount`, and show the ordinary
  `pdf_error` when the session is `null`, empty or above `MAX_VIEW_PAGES`. No page is rasterised
  during load.
- **Lazy rows:** the pager is `LazyColumn(items(count = pageCount))` tagged `pdf_pages`; each row
  is a `PdfPageItem` tagged `pdf_page_<0-based index>`. `LaunchedEffect(session, pageIndex)` calls
  `session.render(pageIndex)`; while the bitmap is being produced the row shows a bounded
  A4-aspect (1:1.414) placeholder with `pdf_page_loading`, on failure a placeholder with
  `pdf_page_render_failed`, and once ready the existing page frame (`pdf_page_frame`), image
  (`pdf_page`) and annotation overlay (`pdf_annotation_overlay`) with the same content
  description and gesture semantics (scrolling disabled in annotate mode).
- **Recycling:** the row's `DisposableEffect(bitmap)` recycles the bitmap when the row leaves the
  composition (and when a replacement bitmap arrives, e.g. session/item change). A bitmap that is
  still displayed is never recycled.
- **Full range:** progress bar/`pdf_page_progress` text, page-jump dialog (`pdf_page_jump_field`,
  full `1..pageCount` validation), bookmark jump/save, reading-state position, annotations keyed by
  page index, page tools plan (`PdfPagePlan.of(pageCount)`) and the Pages overview all use
  `pageCount`. Jumping to page 10/50/last works, and a bookmark can be added on any page.
- **Thumbnails:** unchanged lazy loader; the Pages overview grid now covers
  `min(pageCount, thumbnails.size)` (thumbnails are capped at 100 by `AndroidPdfThumbnailRenderer`)
  instead of the old rendered-pages list; the numeric field keeps the full range and bookmark/page-
  tools thumbnails are unchanged.
- **Annotated export:** uses the existing injected `pageToolsExporter` with the **identity plan**
  `PdfPagePlan.of(pageCount)` (one page per source page, original order, no rotation) instead of
  the eager flattened exporter. The decrypted bytes are re-read from the repository at export
  time; above `PdfPagePlan.MAX_PAGES` (100) the viewer shows `pdf_annotated_export_too_many` and
  writes nothing (the destination picker is never opened). The SAF `CreateDocument` bracket, the
  explicit plaintext warning, the vault-unlocked re-check and the notice strings are unchanged.
- `pdf_pages_capped` is no longer shown (and the old string is now unused; `res/` is unchanged).

## Security

### The unlinked session file (trade-off)

`android.graphics.pdf.PdfRenderer` requires a **seekable file descriptor**; the platform offers no
in-memory PDF renderer. The viewer therefore materialises the decrypted document exactly once, in
the app-private `cacheDir` (excluded from backup), and **unlinks it immediately after opening the
descriptor**. On Linux an open descriptor keeps the inode alive after its directory entry is gone,
so:

- for the whole viewing session there is **no path** to the plaintext PDF — another app cannot
  find it, a backup/scan tool cannot include it, and a crash cannot leave it behind;
- the bytes still exist in the process's page cache/disk blocks until the descriptor is closed
  (inherent to the platform API; nothing readable remains afterwards);
- if unlink fails on an unusual filesystem, the session keeps the path and deletes it in
  `close()` — the pre-existing documented trade-off (`DECISIONS` #20 / `THREAT-MODEL`): one file,
  app-private, short-lived, no backup.

Additional properties: no page image, bitmap, thumbnail or extracted text is ever written to disk;
the only other encrypted-export paths are explicit user actions (original/annotated copy/page-tools
copy) that re-read the bytes and re-check the vault before writing; failed opens delete their file;
the session never logs paths, bytes, page text or annotation content.

## Test tags

| Tag | Element |
|---|---|
| `pdf_viewer`, `pdf_back`, `pdf_title` | screen root, back, title (unchanged) |
| `pdf_pages` | lazy pager `LazyColumn` |
| `pdf_page_<index>` | one page row (0-based index; present while composed) |
| `pdf_page_frame` | page/placeholder box (A4 aspect while loading/failed) |
| `pdf_page` | rendered page image (annotation overlay sibling) |
| `pdf_page_loading`, `pdf_page_render_failed` | bounded placeholder copy (one per pending/failed row) |
| `pdf_annotation_overlay` | annotation/gesture overlay of a rendered page (unchanged) |
| `pdf_page_progress`, `pdf_progress_bar`, `pdf_page_*` dialog controls | full-range navigation (unchanged) |
| `pdf_page_thumb_<index>`, `pdf_page_overview_grid`, `pdf_thumbnails_*` | Pages overview (unchanged; now full-range up to the 100-thumbnail cap) |
| `pdf_bookmark_*`, `pdf_page_tools*`, `pdf_export*`, `pdf_notice`, `pdf_error` | unchanged surfaces |

## Tests

- `PdfPageSessionTest` (8, Robolectric): `MAX_VIEW_PAGES`/bounds math, empty and corrupt documents
  fail closed and leave no `pdf-session-*` file, page/edge bounds (never upscaled, hard 1600 px
  clamp, degenerate page/edge), fake-factory contract.
- `PdfLazyViewerTest` (15, Robolectric, fake 12-page session): one session opened per viewer and
  only composed pages requested; jump to page 10 and to page 12; disposed rows recycle their
  bitmap; loading/failed placeholders; hostile page count shows `pdf_error`; session closed once on
  leave; annotate page 10; bookmark page 12 stores position 11/total 12; Pages overview covers all
  12 pages and jumps from the last; page tools plan all 12 pages; annotated export uses the
  identity plan and re-read bytes; >100-page annotated export shows
  `pdf_annotated_export_too_many` and writes nothing; vault-lock re-check and exporter-failure
  paths.
- Updated: `PdfViewerScreenTest`, `PdfAnnotationScreenTest`, `PdfThumbnailsUiTest`,
  `PdfPageToolsScreenTest` now inject a fake page session instead of the eager renderer; the
  annotated-export assertions target the page-tools exporter with an identity plan.
- `PdfPageSessionInstrumentedTest` (6, device/emulator only, real `android.graphics.pdf`): opens a
  genuine 12-page A4 PDF, renders page 10 (colour proves which page) and a bounded downscale,
  rejects out-of-range indices, proves the session file is unlinked while open, `close()` is
  idempotent with no leftover file, and empty/corrupt/truncated bytes open no session.

## Limitations

- **Per-viewer memory only.** Scrolling back to an earlier page re-renders it (the bitmap was
  recycled when the row left the composition). This is the deliberate no-cache trade-off; short
  documents stay fast because visible pages are cheap, long documents stay bounded.
- **One render at a time.** Rows queue on the session mutex; a fast fling composes placeholder rows
  briefly until the visible ones render. Renders are bounded (≤1600 px long edge) and IO-dispatched,
  so the UI thread never rasterises.
- **Annotated copy is capped at 100 pages** because it reuses the page-tools exporter
  (`PdfPagePlan.MAX_PAGES`); above that the user gets an explicit notice, never a truncated copy.
- **`PdfRenderer` support is platform-dependent.** A device without the native PDF renderer (or a
  corrupt/encrypted-outside-the-vault PDF) shows `pdf_error`; individual pages that fail show the
  per-page placeholder while the rest of the document stays usable.
- **The legacy utilities remain unused.** `PdfPageRenderer`/`PdfPages`/`PdfFlattenedExporter` are
  kept only because their instrumented tests still exercise the native drawing/flatten paths; they
  are not part of the viewer and can be removed in a later cleanup.
- No text selection/search in the viewer (rasterised pages) — unchanged limitation.

## Device-QA watch list

- 12-page PDF: open and scroll from the first to the last page; every page appears exactly once and
  placeholders are replaced without layout jumps; fling quickly and confirm no crash/OOM and that
  pages re-render when scrolled back.
- Jump to page 10 and to the last page; the progress text and highlighted Pages overview cell track
  the current page; the Pages overview shows all 12 thumbnails and tapping the last jumps there.
- Annotate page 10 (ink/highlight/note), leave the viewer, reopen: the page-10 annotations are
  loaded from the encrypted layer and still align with the page.
- Bookmark page 12: the bookmark row shows the page-12 thumbnail and jumps to the last page.
- Annotated export of the 12-page document: destination picker → warning → file written; reopen the
  exported PDF and verify 12 pages, same order, annotations flattened. Above 100 pages the
  annotated action must show "Annotated copy supports up to 100 pages." and write nothing.
- Back/leave while a page is rendering (fast scroll then immediately leave, and lock the vault with
  the viewer open): no crash, no ANR, temp file gone; reopen the vault and confirm clean rendering.
- Low-memory device: scroll a long PDF for a while and confirm bitmaps are reclaimed (no
  `OutOfMemoryError`); a per-page failure should show `pdf_page_render_failed` only for that page.
- Small screen (320 dp) / large font: loading/failed placeholders keep the A4 aspect, the pager
  remains scrollable and jump/bookmark/page-tools dialogs keep their 48 dp targets.
