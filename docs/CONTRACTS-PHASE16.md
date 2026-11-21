# NoteZero — Phase-16 (increment 17) contract: PDF visual page navigation (thumbnails)

Frozen for increment 17. UI/tests address these names only.

## Goal

Add **in-memory PDF page thumbnails** so page navigation and page editing are visual instead of a
number-only dialog: thumbnails in the page-jump/overview dialog, in the bookmark rows, and in the
page-tools page rows. No new top-bar action (the bar already has five 48 dp actions), no plaintext
thumbnail cache, no new dependency/permission, no data-model change.

Entry points (existing):
- `pdf_page_progress` button opens the existing page-jump dialog (`showPageJump` / `pdf_page_jump_dialog`)
  → becomes the **Pages overview**: thumbnail grid + the existing numeric field/Go/Cancel.
- Bookmark panel (`pdf_bookmarks_dialog`, `pdf_bookmark_row`) → each row gets that page's thumbnail.
- Page-tools overlay (`pdf_page_tools_screen`, `pdf_page_row_<i>`) → each planned page row shows the
  source page thumbnail (rotated by the entry's quarter-turns), or a blank-page placeholder.

## A. Thumbnail renderer (`ui/pdf/PdfThumbnails.kt`)

- `class PdfThumbnails(private val bitmaps: List<Bitmap>)`:
  - `val size: Int`, `fun bitmap(page: Int): Bitmap?`, `fun recycle()` (idempotent; recycles every
    bitmap and clears the list), `val isEmpty: Boolean`.
  - Caller owns it; the viewer recycles it on dispose/item change. Never a file/cache.
- `fun interface PdfThumbnailRenderer { suspend fun render(context: Context, pdfBytes: ByteArray): PdfThumbnails }`
  - default `AndroidPdfThumbnailRenderer(maxPages = 100, longEdgePx = 144)`:
    - empty/corrupt bytes → empty `PdfThumbnails` (never throws);
    - one ephemeral app-private cache file (same documented `PdfRenderer` seekable-FD
      trade-off as the existing renderers; deleted in `finally`), renders up to `maxPages` pages at
      the thumbnail long edge on a white ARGB bitmap, no upscale beyond source resolution;
    - any page failure returns an empty set (or a partial set only if pages already rendered are
      valid — simplest: fail closed empty, never a corrupt grid);
    - ≤100 bitmaps × ≤144 px long edge keeps memory bounded (~5 MiB ARGB); catches OOM/Throwable,
      rethrows `CancellationException`; no logging.
- Pure tests for `PdfThumbnails` bounds/recycle (bitmap-level may use Robolectric? prefer
  instrumented for real PDF, plain JVM for list wrapper with mocked bitmaps if possible).

## B. Viewer integration (`PdfViewerScreen.kt`)

- New injectable defaulted param `thumbnailRenderer: PdfThumbnailRenderer = remember { AndroidPdfThumbnailRenderer() }`
  (and reuse the existing `exportDispatcher`/IO dispatcher for CPU/IO; do not add a third dispatcher
  unless necessary).
- Thumbnails are loaded **lazily, once per viewer session**, when the user first opens any
  thumbnail surface (page overview, bookmarks or page tools):
  - state: `thumbnails: PdfThumbnails?`, `thumbnailsLoading`, `thumbnailsLoaded`.
  - coroutine reads the decrypted attachment bytes via `repository.attachmentBytes(itemId, id)`,
    calls `thumbnailRenderer.render`, stores the result, sets loaded; failures/empty → empty set
    (UI shows `pdf_thumbnails_unavailable` / falls back to the numeric field).
  - `DisposableEffect` recycles thumbnails when replaced or when the viewer leaves; no recycling of
    a bitmap still displayed (Compose shows a bitmap; recycle on dispose only).
  - A vault lock/screen leave disposes and recycles; reopening re-renders.
- Do **not** hold PDF bytes in state; re-read at thumbnail-load time only.
- No top-bar changes; all existing tags/behaviour preserved.

## C. UI

### Page overview (existing page-jump dialog)

- Title `pdf_page_overview` (string pre-added); hint `pdf_page_overview_hint`.
- When thumbnails are available: scrollable `LazyVerticalGrid` (3 columns, bounded height ~360 dp,
  tag `pdf_page_overview_grid`) of page cells tag `pdf_page_thumb_<index>`:
  - each cell shows the thumbnail (`Image`, contentDescription `cd_pdf_page_thumbnail` with 1-based
    page), page number label, selected/current-page emphasis; tap scrolls to that page and closes
    the dialog.
  - Existing numeric `OutlinedTextField` (`pdf_page_field`), error (`pdf_page_error`), Go
    (`pdf_page_jump_confirm`) and Cancel (`pdf_page_jump_cancel`) remain below the grid and behave
    exactly as today (this is the fallback for >100-page/thumbnail failure and for keyboard users).
- While loading: a small progress row `pdf_thumbnails_loading` (tag `pdf_thumbnails_loading`);
  unavailable: `pdf_thumbnails_unavailable` (tag `pdf_thumbnails_unavailable`) + numeric field.
- Dialog tag remains `pdf_page_jump_dialog`; no existing tag removed.

### Bookmark rows

- Each bookmark row shows a small thumbnail (48×64 dp, rounded/clipped) for its page when loaded,
  contentDescription `cd_pdf_page_thumbnail`; tag `pdf_bookmark_thumbnail`. If thumbnails are not
  loaded/failed, the row keeps its current text-only layout (no broken icon).
- Opening the bookmark panel triggers the same lazy load; while loading rows render text only.

### Page tools rows

- `PdfPageToolsScreen` gains an optional `thumbnails: PdfThumbnails? = null` parameter.
- Each planned page row shows a small preview (56×72 dp) before/next to the labels:
  - source page entry → that source thumbnail, rotated visually by `quarterTurns * 90f` (a
    `graphicsLayer` rotation is enough); contentDescription `cd_pdf_page_tools_thumbnail`;
  - blank entry → a light placeholder with the blank label (no bitmap).
  - tag `pdf_page_thumb`.
- No layout regression at the 3-row default; rows remain scrollable at 100 pages and 48 dp controls.
- `PdfViewerScreen` passes the lazy-loaded thumbnails into the overlay (opening page tools triggers
  the load).

## D. Strings

Pre-added in `res/values/strings.xml` (count now 660): `pdf_page_overview`,
`pdf_page_overview_hint`, `pdf_thumbnails_loading`, `pdf_thumbnails_unavailable`,
`cd_pdf_page_thumbnail`, `cd_pdf_page_tools_thumbnail`. Do not edit `res/`.

## E. Tests

- JVM/Robolectric: thumbnail UI state (overview grid count, tapping `pdf_page_thumb_2` jumps and
  closes, numeric fallback when unavailable/loading, bookmark thumbnail tag, page-tools thumbnail
  present/blank placeholder) with a fake thumbnail renderer (small fake `Bitmap`s) and existing
  fake repository; no native PDF classes.
- Instrumented: `AndroidPdfThumbnailRenderer` on a real 3-page PDF fixture: count, dimensions
  bounded, white/colour sanity, empty/corrupt input empty, max-page cap; no plaintext file left
  behind (renderer deletes its cache file).
- Update `PdfViewerScreenTest` for lazy load (fake renderer called once; thumbnails recycled on
  dispose path where testable) and keep all existing PDF tests green.
- Keep existing PDF page tools/bookmark/jump tests green (tags/title changes updated in tests).

## F. Docs (agent)

Create `docs/PDF-THUMBNAILS.md` (implementation, bounds, security/temp-file, tags, limitations)
and add a short pointer in `docs/PDF-PAGE-TOOLS.md` if relevant. Do not edit README/CHANGELOG/
ROADMAP/TESTING/VERIFICATION/DECISIONS/THREAT-MODEL — Lead handles those.

## G. Lead integration

Review, full suites/build/instrumented, device QA (3-page + >8-page PDF: open Pages, jump by
thumbnail, bookmark thumbnails, page-tools thumbnail previews incl. rotated and blank row),
screenshots, root docs, reproducibility/release, commit/push/CI/memory.
