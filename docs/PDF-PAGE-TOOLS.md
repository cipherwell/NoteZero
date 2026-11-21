# PDF page tools (rotate / delete / reorder / insert blank → save copy)

Phase-15 increment 16, section B of `docs/CONTRACTS-PHASE15.md`. This document describes the
shipped page-tools overlay, the pure page plan, the flattened exporter, the encrypted copy saver,
all test tags, bounds/security properties, limitations and the device-QA watch list.

The viewer that hosts these tools is a full-document **lazy pager** (one open `PdfPageSession`,
pages rendered only while composed — see `docs/PDF-LAZY-VIEWER.md`); page tools always plan over
the document's real `pageCount`.

Scope is deliberately honest: the page operations are applied by **flattening** the document into
a brand-new encrypted PDF item. There is no PDF object editing, no external PDF library, no new
dependency, no new permission and no network. The original PDF and its annotation layer are never
modified or re-written — they stay exactly as they were, byte for byte.

## Files

| File | Contents |
|---|---|
| `ui/pdf/PdfPagePlan.kt` | Pure-Kotlin immutable page plan: entries, rotations, reorder, delete, insert blank, reset, bounds |
| `ui/pdf/PdfPageDrawing.kt` | Shared draw-one-page helper (page bitmap + annotations on the A4 canvas, quarter-turn aware) used by **both** exporters |
| `ui/pdf/PdfPageToolsExporter.kt` | `PdfPageToolsExporter` seam + `AndroidPdfPageToolsExporter` (`android.graphics.pdf`, ephemeral cache render file) |
| `ui/pdf/PdfPageCopySaver.kt` | `PdfPageCopySaver` seam + `DefaultPdfPageCopySaver` (new encrypted `PDF_FILE` item via `importAttachment`), copy-name helpers |
| `ui/pdf/PdfPageToolsScreen.kt` | Full-screen tools overlay: warning/hint, page rows (with optional in-memory page-thumbnail previews — see `docs/PDF-THUMBNAILS.md`), controls, notices, bottom actions |
| `ui/pdf/PdfViewerScreen.kt` | Top-bar action `pdf_page_tools`, overlay state, save coroutine, Back handling, injected seams; also the lazy `PdfPageSession` pager and the annotated copy (identity plan through this exporter) |
| `ui/pdf/PdfFlattenedExporter.kt` | Legacy utility: still tested on device, but the viewer no longer uses it — the annotated copy now goes through `PdfPageToolsExporter` with an identity plan (`docs/PDF-LAZY-VIEWER.md`) |
| `ui/pdf/PdfExportGateway.kt` | Extracted shared `pdfDisplayBaseName` display-name helper (annotated-export name unchanged) |
| `test/.../ui/pdf/PdfPagePlanTest.kt` | Pure plan tests (23) |
| `test/.../ui/pdf/PdfPageToolsScreenTest.kt` | Robolectric overlay + viewer save-flow tests (20) |
| `test/.../ui/pdf/PdfPageCopySaverTest.kt` | Saver/name tests via `FakeNoteRepository` (8) |
| `test/.../ui/pdf/PdfViewerScreenTest.kt` | Extended with the new viewer action/Back tests (3 new, 11 total) |
| `androidTest/.../ui/pdf/PdfPageToolsExportInstrumentedTest.kt` | Real `PdfRenderer`/`PdfDocument` validation (4) |

## Page plan (pure JVM)

`PdfPagePlan` describes the **output** copy only: an ordered list of `PdfPageEntry(sourcePage:
Int?, quarterTurns: Int)` where `sourcePage == null` is an inserted blank page and `quarterTurns`
is a clockwise quarter-turn normalised to `0..3` (0°/90°/180°/270°). It contains no bitmap,
annotation or item reference and never any document content — it is a tiny value that is safe to
copy, compare and hold in Compose state.

Operations are pure and return a new plan:

| Operation | Behaviour |
|---|---|
| `PdfPagePlan.of(totalPages)` | One unrotated entry per source page. `totalPages > MAX_PAGES` → empty, `isTooLarge`, `isValid == false` (an explicit "too many" error instead of a silently truncated plan). Hostile/negative totals clamp to empty. |
| `rotateLeft(position)` / `rotateRight(position)` | Rotates exactly one entry ±1 quarter-turn, wrapping `0..3`; invalid position is a no-op. |
| `moveUp(position)` / `moveDown(position)` | Swap with the neighbouring entry; no-op at the page bounds and for hostile positions. |
| `delete(position)` | Returns `PdfPageDeletion(plan, deleted)`. The **last remaining page is never deleted** (`deleted == false`; the UI shows `pdf_pages_last_page`), hostile positions are refused. |
| `insertBlank(afterPosition = null)` | Appends when `afterPosition` is null (the only UI mode); otherwise inserts after the entry, hostile positions append, and a plan at `MAX_PAGES` is unchanged. |
| `reset()` | Initial order/rotation for the original page count (deleted pages come back). |
| `entries`, `size`, `isValid`, `isTooLarge`, `hasChanges()` | Read-only state; `hasChanges()` compares against `reset()`. |

Invariants: `MAX_PAGES = 100`; a public operation can never produce an out-of-range source index
or a duplicate source page; no operation throws on any `Int` position. `PdfPageEntry` itself
rejects a negative source page or a quarter-turn outside `0..3` at construction (programmer
error), while every plan operation normalises instead of throwing.

Bounded heap matters here too: `of(totalPages)` allocates nothing for a source above the cap, so a
hostile 50,000-page PDF cannot make the tools screen allocate a plan (or rows) for it.

## Export (`AndroidPdfPageToolsExporter`)

`fun interface PdfPageToolsExporter { suspend fun export(context, pdfBytes, plan, annotations): ByteArray? }`.

For each plan entry, on a white A4 page (`595×842 pt`, the same size the annotated export uses):

- **blank entry** → the white page is left empty;
- **source entry** → that source page is rendered with the platform `PdfRenderer`, drawn
  scaled-to-fit and centred with the entry's clockwise quarter-turn applied (a quarter-turn swaps
  the fitted width/height, so a landscape page cannot overflow the paper), and that source page's
  annotations are drawn in the **same bitmap-space transform** — so ink, highlights and note
  markers rotate and move with their page instead of staying horizontal;
- annotations are looked up by **source page index** (`entry.sourcePage`), so a reordered page
  keeps its annotations, a deleted page's annotations are gone, and an inserted blank page has
  none.

The annotation drawing is the shared `PdfPageDrawing.drawPage` helper extracted from
`AndroidPdfFlattenedExporter`: the annotated-copy export and the page-tools copy therefore render
ink/highlights/notes identically. Since increment 18 the viewer's annotated export uses **this**
exporter directly with the identity plan `PdfPagePlan.of(pageCount)` (so it shares the 100-page
cap and the `pdf_annotated_export_too_many` refusal); `PdfFlattenedExporter` is retained only as a
standalone utility and is no longer reachable from the viewer.

Failure contract: **`null` on any failure and no exception/log output** — empty bytes, an invalid
or oversized plan, a plan that references a page the document does not have, a corrupt PDF, a
missing native renderer, OOM, a render/encode error. Coroutine cancellation is rethrown (the
caller's coroutine must end properly), matching the viewer's `AndroidPdfPageSession`. Every
created `Bitmap`, `PdfRenderer.Page`, `PdfRenderer` and `PdfDocument` is recycled/closed on every
path, including early returns.

### Temporary file and memory bounds

`PdfRenderer` requires a **seekable file descriptor**, so the decrypted bytes are written to a
single ephemeral app-private cache file (`cacheDir/pdf-page-tools-<uuid>.pdf`) for the duration of
the export and deleted in the `finally` block. This is the same documented trade-off as viewing
and the annotated export (DECISIONS #20 / THREAT-MODEL): one file, app-private cache, excluded
from backup, short-lived, created only because the platform offers no in-memory renderer. Nothing
else is written: the produced bytes live in memory and reach disk only through the encrypted
attachment pipeline of the saver. No title, page text or annotation content is ever logged.

Resolution: each rendered source page is capped at **1600 px on the long edge**. Because
`PdfDocument` keeps its recorded page content until `writeTo`, all rendered bitmaps stay alive for
the duration of one export; to keep that heap bounded the exporter also honours a total pixel
budget (~48 MiB at ARGB_8888), so a 100-page copy is rendered at a lower per-page resolution than
a 3-page one. Both caps are hard limits, not targets; `MAX_PAGES = 100` bounds the document.

## Saving the copy (`DefaultPdfPageCopySaver`)

`fun interface PdfPageCopySaver { suspend fun save(repository, source, displayName, bytes): String? }`.

The default implementation:

1. refuses blank bytes, a blank name and a locked vault (`VaultStatus != UNLOCKED`);
2. creates a new `ItemKind.PDF_FILE` item in the **source item's folder** (same `folderId`);
3. saves its title as the copy display name;
4. imports the flattened bytes as an encrypted `application/pdf` attachment through
   `repository.importAttachment` (the same NZAT AES-256-GCM pipeline every attachment uses);
5. on any import failure trashes the freshly created row so no orphan item survives without its
   encrypted blob, and returns `null`.

The copy display name is built in the Compose layer (the saver has no `Context`) with
`pageCopyFileName(sourceLabel, suffix)` + the localized `pdf_pages_copy_suffix`, bounded to 96
characters: `report.pdf` → `report (pages).pdf`. The resource pipeline trims the leading space of
the pre-added suffix string, so the helper rebuilds exactly one separator space from either form.
`boundedPageCopyName` re-sanitises a caller-provided name defensively (control characters and
path separators become spaces, `.pdf` appended when missing, capped) — a display name is never
used as a file name/path anywhere in the pipeline.

Save-time security order in the viewer (`savePageCopy`):

1. read the decrypted attachment bytes **at save time** via `repository.attachmentBytes` (never a
   cached copy from the viewer load);
2. export on the injected dispatcher (`Dispatchers.Default` by default);
3. re-check the vault is `UNLOCKED` and the item is still alive **before** handing the bytes to
   the saver — a vault that locks mid-export drops the plaintext and shows `pdf_pages_locked`;
4. call the injected saver; on success close the overlay, show `pdf_pages_saved` with the new
   title and leave the original viewer open. The new copy is an encrypted vault item, not a
   plaintext file or URI.

The annotation layer used for the copy is the viewer's current `PdfAnnotationEditor.value`, so
unsaved on-screen annotations are included; the original encrypted layer is never re-saved or
modified by this flow.

## UI and viewer integration

The viewer top bar (non-annotate mode) gains an action right of the bookmark buttons and before
the export action: tag `pdf_page_tools`, content description `cd_pdf_page_tools`, always present
in non-annotate mode and **enabled only when the PDF rendered and `totalPages > 0`** (a failed
render shows the action disabled instead of hiding it).

Opening it initialises `PdfPagePlan.of(pages.totalPages)` and raises the full-screen overlay
(`Dialog` with `usePlatformDefaultWidth = false`, like the drawing canvas, so the whole screen is
used on phones). State layout: title/warning/hint/notices are a **fixed header** (the
"flattened image-only copy" warning is visible without scrolling on small screens), page rows are
a scrolling `LazyColumn`, and the actions are a fixed bottom bar. Back while the overlay is open
closes only the overlay (a second Back then closes the viewer); Back is ignored while a save is in
flight so the result is never missed, and the save itself cannot be interrupted (the encrypted
import is already in flight).

Row content: optional 56×72 dp source-page thumbnail preview (tag `pdf_page_thumb`, rotated
with the entry's quarter-turns; blank/loading rows show a light placeholder — see
`docs/PDF-THUMBNAILS.md`), position label (`pdf_pages_position`), source label
(`pdf_pages_source`, or `pdf_pages_blank` for an inserted page), rotation label
(`pdf_pages_rotation`), and five 48 dp icon controls. Move up/down are disabled (with a muted
tint and disabled semantics) at the page bounds; rotate is always meaningful; delete stays
enabled because deleting the only page is a
**refusal with a visible notice**, not a silent disabled control. Changes show the non-secret
dirty notice `pdf_pages_changed`; insert-blank announces `pdf_pages_blank_added`; a refused
last-page delete shows `pdf_pages_last_page`. Above 100 source pages the overlay shows
`pdf_pages_too_many` (formatted with the cap), no rows and a disabled save action.

Tags:

| Tag | Element |
|---|---|
| `pdf_page_tools` | viewer top-bar action (content description `cd_pdf_page_tools`) |
| `pdf_page_tools_screen` | full-screen overlay root |
| `pdf_pages_title`, `pdf_pages_count` | overlay title and "N pages" count |
| `pdf_pages_warning`, `pdf_pages_hint` | fixed warning banner and hint |
| `pdf_pages_list` | scrollable page list |
| `pdf_page_row` | one page row (repeated per page) |
| `pdf_page_row_<index>` | row-scoped tag used to address one row's repeated controls |
| `pdf_page_rotate_left`, `pdf_page_rotate_right`, `pdf_page_move_up`, `pdf_page_move_down`, `pdf_page_delete` | 48 dp row controls (repeat per row; content descriptions `cd_pdf_pages_*`) |
| `pdf_pages_insert_blank`, `pdf_pages_reset`, `pdf_pages_cancel`, `pdf_pages_save_copy` | bottom actions |
| `pdf_pages_changed` | dirty notice |
| `pdf_pages_notice` | combined live-region notice (save failure from the viewer or a local refusal/insert notice) |
| `pdf_pages_last_page`, `pdf_pages_too_many` | last-page and page-cap notices |

Because the five row controls repeat their contract tag on every page, a test or E2E script
addresses one row's control with
`hasTestTag("pdf_page_rotate_right") and hasAnyAncestor(hasTestTag("pdf_page_row_<index>"))`
(and scrolls the `pdf_pages_list` to that index first on small screens).

All existing viewer tags/behaviour are untouched: `pdf_viewer`, `pdf_back`, `pdf_title`,
`pdf_annotate_toggle`, `pdf_annotate_done`, `pdf_export`, `pdf_page`, `pdf_pages`,
`pdf_bookmarks_open`, bookmarks, page jump, annotated export, etc. The annotation editor's
behaviour is unchanged; the page-tools action simply joins the top bar.

## Strings

All UI copy uses the pre-added resources: `pdf_page_tools`/`cd_pdf_page_tools`, `pdf_pages_title`,
`pdf_pages_warning`, `pdf_pages_hint`, `pdf_pages_position`, `pdf_pages_source`,
`pdf_pages_blank`, `pdf_pages_rotation`, `cd_pdf_pages_rotate_left/right`,
`cd_pdf_pages_move_up/down`, `cd_pdf_pages_delete`, `pdf_pages_insert_blank`,
`pdf_pages_reset`, `pdf_pages_cancel`, `pdf_pages_save_copy`, `pdf_pages_saving`,
`pdf_pages_saved`, `pdf_pages_save_failed`, `pdf_pages_export_failed`, `pdf_pages_locked`,
`pdf_pages_last_page`, `pdf_pages_too_many`, `pdf_pages_copy_suffix`, `pdf_pages_count`,
`pdf_pages_blank_added`, `pdf_pages_changed`. **No string was added or changed** and `res/` was
not edited.

## Tests

Unit (`:app:testDebugUnitTest --tests "com.cipherwell.notezero.ui.pdf.*"`) — **109 tests, 0
failures** (55 before this increment; new: 23 `PdfPagePlanTest` + 20 `PdfPageToolsScreenTest` + 8
`PdfPageCopySaverTest` + 3 `PdfViewerScreenTest` additions).

- `PdfPagePlanTest` (23, pure JVM): every operation and boundary, rotation cycling/normalisation,
  independence per position, move bounds, delete + last-page refusal + hostile positions, insert
  blank/append/cap/hostile positions, reset, too-large sources, dirty tracking, value equality,
  object invariants (no duplicate/out-of-range source pages) and construction guards.
- `PdfPageToolsScreenTest` (20, Robolectric `@Config(sdk = [34])`, no display qualifiers): the
  overlay hosted directly with hoisted plan state (title/warning/hint/count, rotate both ways and
  wrap, reorder, bounds-disabled controls, delete, last-page notice, insert blank + announcement,
  reset, dirty notice, cancel-no-save, save hands over the edited plan, saving label/disabled,
  too-many cap, caller notice, 48 dp targets) and `PdfViewerScreen` hosted with fakes (open plan
  from the viewer page count, save wiring: exporter receives the edited plan + current annotation
  layer + re-read decrypted bytes, saver receives the name/bytes/source, overlay closes on
  success and shows `pdf_pages_saved`; export-failed / save-failed / vault-locked notices keep the
  overlay open with the right copy; cancelling never calls exporter or saver).
- `PdfPageCopySaverTest` (8): new item in the source folder with the suffixed title and
  round-tripping encrypted bytes; failed import leaves only a trashed row; locked vault/empty
  bytes/blank name refuse; two saves are independent; name helpers (suffix before extension,
  bounded length, separator/normalisation, hostile long names).
- `PdfViewerScreenTest` (11, +3): action enabled and opens the overlay for a loaded PDF; disabled
  when the render failed; hidden while annotating and restored afterwards; system Back closes only
  the overlay and a second Back closes the viewer.

Instrumented (`PdfPageToolsExportInstrumentedTest`, device/emulator only, real
`android.graphics.pdf`): a real 3-page A4 PDF (red/green/blue) exported through the full plan
verifies page count, order, rotated-page survival and inserted blank pages by rendering the
output back through `PdfRenderer` and comparing page-centre colours; deleted pages drop out;
empty/corrupt bytes, a plan referencing missing pages, an oversized/empty plan fail closed with
`null`; a maximum plan is rejected cleanly while a blank-only plan still exports a valid bounded
document. The fake exporter/saver are used instead in Robolectric so no UI test touches native
PDF classes.

Reproduce with:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
./gradlew :app:compileDebugKotlin --console=plain
./gradlew :app:testDebugUnitTest --tests "com.cipherwell.notezero.ui.pdf.*" --console=plain
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PallowScreenshots=true --console=plain
```

## Limitations

- **Flattened, image-only copy.** The saved PDF is a new rasterised A4 document: text is no longer
  selectable/searchable, links/forms/outlines/metadata are gone, and page size is normalised to
  A4 with white letterbox margins for other aspect ratios. The warning banner states this before
  saving.
- **Annotations are included and flattened.** Current on-screen ink/highlights/note markers are
  drawn onto the copy; sticky-note text stays only in the encrypted vault (a rasterised PDF cannot
  carry interactive popup annotations), exactly like the annotated-export limitation.
- **Deleted-page annotations are gone** from the copy (they have no output page to attach to);
  the warning covers "annotations are included" for the pages that remain. Annotations on
  reordered/rotated pages travel with their page.
- **The original is untouched.** The source item, its encrypted attachment and its encrypted
  annotation layer are never modified; the copy is a separate encrypted `PDF_FILE` item in the
  same folder (a second save creates a second copy; no de-duplication).
- **Page cap.** Source documents above 100 pages cannot use page tools and show
  `pdf_pages_too_many`; the plan also refuses to grow past 100 output pages.
- **Resolution vs page count.** A copy is bounded in heap; long documents get a lower per-page
  raster resolution than short ones (all pages still ≤1600 px long edge).
- No text-object editing, no page cropping, no merge/split with other documents, no network, no
  new dependency or permission.

## Device-QA watch list

- Multi-page PDF (≥3 pages, mixed portrait/landscape): rotate one page, reorder another, delete
  one, insert a blank page, save the copy; open the new item from the library and verify page
  count/order/rotation and that blank pages are white A4.
- Rotated page with ink/highlight/note: annotations must be rotated with the page and keep their
  relative positions; the deleted page's annotations must be absent from the copy.
- Save while the vault auto-locks during the export: no copy may be created and
  `pdf_pages_locked` must be shown; the viewer must stay on the original document.
- 100-page boundary: `pdf_pages_too_many` with a disabled save; 99-page document must still export
  (check the lower per-page resolution is readable, and the app does not OOM).
- Small screen + large font: warning visible without scrolling, bottom actions reachable, 48 dp
  targets, rows scroll; save button shows `Saving…` and ignores double taps.
- Back while saving is ignored; Back afterwards closes only the overlay; a second Back closes the
  viewer.
- Cancel/leave the overlay and reopen: the plan resets to the current source page count.
- Narrow phone (≤360 dp): the top bar now has five 48 dp actions plus the title; verify the title
  stays readable (it ellipsizes) and the new action is reachable. A future increment may move
  secondary actions into an overflow menu.
- Repeated saves create independent encrypted copies in the source folder (trash the extras);
  a failed save must not leave an untitled empty PDF row in the library.
