# NoteZero — Library + e-book reader UI (EPUB / FB2 / TXT / CBZ)

Phase-10 reading surface, extended in Phase 13 (increment 14) with the TXT/FB2/CBZ formats. Implementation:

- `app/src/main/java/com/cipherwell/notezero/ui/library/LibraryScreen.kt`
- `app/src/main/java/com/cipherwell/notezero/ui/reader/BookReaderScreen.kt`
- `app/src/main/java/com/cipherwell/notezero/ui/reader/ReaderPrefs.kt`
- `app/src/main/java/com/cipherwell/notezero/ui/reader/ReaderTheme.kt`

Contracts: `docs/CONTRACTS-PHASE9.md` §B/§C. Engine: `com.cipherwell.notezero.book`
(see `docs/EPUB-ENGINE.md`). Reading state: encrypted `Item.readingState`
(`ItemCodec` v4, repository-owned through `NoteRepository.saveReadingState`).

## Library (`LibraryScreen`)

One shelf for every document in the vault, opened from the Notes top bar
(`library_button`) and closed with `library_back`. The screen takes
`NoteRepository`, `onBack` and an optional `Modifier`; it never receives content
outside the repository.

| Area | Behaviour |
|---|---|
| Contents | `PDF_FILE` items, `BOOK_FILE` items, and drawings = `NOTE` items with an attachment whose `displayName` starts with `drawing-` and whose mime type is `image/png`. Sorted by `updatedAt` descending. |
| Rows | Title, type label, encrypted reading progress (page / chapter position + percent), a `Continue` hint when opened before, overflow menu. |
| Filters | All / Books / PDFs / Drawings chips. |
| Import | SAF `OpenDocument` picker for `application/pdf` and `application/pdf`/e-book (`*/*`: providers map book MIME types inconsistently). The picker is bracketed with `beginExternalActivity()`/`endExternalActivity()`; a URI that returns after the vault locked is dropped (no import, no content read). |
| PDF import | Creates `ItemKind.PDF_FILE`, imports the attachment as `application/pdf`, titles the item from `OpenableColumns.DISPLAY_NAME`. Any failure trashes the just-created item. |
| E-book import | Any supported format: reads the SAF bytes (bounded to `AttachmentLimits.MAX_ATTACHMENT_BYTES`), detects the format with `BookDetector` and derives metadata with `BookLoader` on `Dispatchers.IO`, then stores the encrypted attachment with the **detected** MIME (`text/plain`, `application/x-fictionbook+xml`, `application/vnd.comicbook+zip` or `application/epub+zip`) and the title (content title, else cleaned display name, else `Untitled book`) plus encrypted `ReadingState(author, language, total = chapters/pages)`. Unsupported/corrupt/DRM input trashes the just-created item and shows `library_import_unsupported`; any other failure shows `library_import_failed`. The parsed `BookContent`/`ComicBook` is closed immediately and never cached. |
| Drawing preview | Full-screen, in memory only: `attachmentBytes` → `AttachmentImages.decode` (bounded 2048 px, no disk/cache). Failure shows `library_no_preview`. |
| Locked content | Locked + unverified items are removed entirely (fail closed) with `LockVisibility.isItemMasked`, and items inside hidden locked folders are removed as well; the same rule the code workspace uses. |
| Actions | Open, Rename (reuses the notes rename label), Lock/Remove lock (`setItemLocked`), Move to trash (`trashItem`). |

### Library test tags

| Tag | Element |
|---|---|
| `library_screen` | root |
| `library_back` | back button (48dp) |
| `library_import` | import menu button; menu items `library_import_pdf`, `library_import_epub` |
| `library_filter_all`, `library_filter_books`, `library_filter_pdfs`, `library_filter_drawings` | filter chips |
| `library_item` | one document row |
| `library_actions` | row overflow menu button |
| `library_page_progress`, `library_chapter_progress`, `library_progress_percent` | progress line/percent (PDF / EPUB) |
| `library_type_pdf`, `library_type_book`, `library_type_drawing`, `library_continue` | type label and continue hint |
| `library_importing`, `library_notice` | non-secret status text |
| `library_rename_dialog`, `library_rename_field`, `library_rename_save`, `library_rename_cancel` | rename dialog |
| `library_preview`, `library_preview_close`, `library_no_preview` | in-memory drawing preview |

## EPUB reader (`BookReaderScreen`)

Full-screen reader for one `BOOK_FILE` item (`repository`, `itemId`, `onClose`).
Loading, parse and image work run off the main thread; the decrypted archive is
never written anywhere.

| Area | Behaviour |
|---|---|
| Loading / error | `book_loading` while decrypting+parsing; `book_error` for corrupt/DRM/unsupported input (the message never includes content or file names). |
| Content | `LazyColumn` with exactly one item per chapter (index = encrypted reading position). Blocks: headings 1–6, paragraphs with bold/italic/code/link styling, ordered/unordered lists with depth, block quotes, code blocks, horizontal rules, inline images (decoded in memory with a 2048 px bound). |
| Chrome | Tapping the page centre toggles the bars; left/right 48dp edge zones turn chapters (`book_tap_left`/`book_tap_right`). Top chrome: back, title/author, progress percent and TOC/search/bookmarks/appearance actions. A thin progress bar is always visible. |
| TOC | Dialog listing `document.toc`; selecting an entry scrolls to that chapter. |
| Search | Case-insensitive in-memory search over every chapter's `plainText()`, capped at 100 results; each result shows the chapter and a snippet. Selecting scrolls to the chapter. |
| Bookmarks | Add (label dialog), jump, delete. Persisted as `ReadingBookmark(position = chapterIndex, label, createdAt)` through `saveReadingState`; capped at `ReadingState.MAX_BOOKMARKS`. |
| Progress | Restores `readingState.position` on open, saves `position`/`fraction = 0`/`total = chapters.size` after a ≥500 ms debounce when the visible chapter settles, and after bookmark changes. `saveReadingState` returning false while locked is ignored silently. |
| Caps | At most 2,000 rendered blocks and 200,000 text characters per chapter, re-checked in the UI even though the engine already bounds a chapter. |

### Reader test tags

| Tag | Element |
|---|---|
| `book_reader` | root |
| `book_loading`, `book_error` | loading / failure state |
| `book_back`, `book_title` | chrome back and title |
| `book_chapter` | one chapter item |
| `book_progress_percent`, `book_progress_bar` | progress percent text / bar |
| `book_tap_center`, `book_tap_left`, `book_tap_right` | centre toggle + chapter tap zones |
| `book_list` | scrollable chapter list (scroll-to-index target) |
| `book_toc`, `book_toc_dialog`, `book_toc_entry` | table of contents |
| `book_search`, `book_search_dialog`, `book_search_query`, `book_search_result`, `book_search_empty` | in-book search |
| `book_bookmarks`, `book_bookmarks_dialog`, `book_bookmark_row`, `book_bookmark_delete`, `book_bookmark_add`, `book_bookmark_label`, `book_bookmark_save`, `book_bookmark_cancel` | bookmarks |
| `book_appearance`, `book_appearance_dialog`, `book_font_increase`, `book_font_decrease`, `book_line_spacing_increase`, `book_line_spacing_decrease`, `book_margin_slider`, `book_theme_paper`, `book_theme_sepia`, `book_theme_night`, `book_settings_reset` | appearance |
| `book_image_error` | image that cannot be decoded |

## Reader appearance settings (`ReaderPrefs`)

Non-secret, app-private `SharedPreferences` file `notezero_reader_prefs` (no title,
author, position, bookmark label or content is written there; those stay in the
encrypted `ReadingState`).

| Setting | Bounds | Default |
|---|---|---|
| Font scale | 0.8 – 1.6 (step 0.1) | 1.0 |
| Line height multiplier | 1.2 – 2.2 (step 0.1) | 1.5 |
| Margin | 8 – 48 dp (step 4) | 24 dp |
| Theme | `PAPER`, `SEPIA`, `NIGHT` | `PAPER` |

`Paper` uses the app Material theme ground; `Sepia` is a warm tint derived from
`NoteZeroPalette` tokens and `Night` is the true-black canvas — all colours come
from `ui/theme` tokens (`Color(NoteZeroPalette.*)` and `lerp`), the reader adds
no new hex literals.

## Security notes

- No network access, no WebView: the EPUB is parsed by the dependency-free engine
  (bounded ZIP/XML, XXE off, DRM refused) and rendered with native Compose blocks.
- Decrypted book bytes, parsed text and decoded images live only in memory. The
  `EpubDocument` is closed in `DisposableEffect.onDispose` and again on vault lock;
  images are decoded on demand with a 2048 px sample bound and recycled with the
  composition.
- Library/reader metadata shown on screen comes from the decrypted in-memory index;
  no plaintext cache, thumbnail file or log line is produced. Titles, authors,
  bookmark labels, positions and file names are never logged.
- Locked + unverified documents never reach the shelf or a reader; readers check
  `LockVisibility.isItemMasked` and the vault status before decrypting.
- Imports only write encrypted attachment blobs; failed imports trash the item so
  no orphan/empty row is left behind. The SAF picker bracket prevents the
  background lock from firing while the system picker is open.
- Tags/content descriptions are content-free; all visible strings come from
  `res/values/strings.xml` via `stringResource`.

## Tests

Robolectric + Compose (`@Config(sdk = [34])`, no display qualifiers):

- `app/src/test/java/com/cipherwell/notezero/ui/reader/EpubTestData.kt` builds a
  minimal valid EPUB (container + OPF + nav + two text chapters) with
  `java.util.zip.ZipOutputStream` and seeds `FakeNoteRepository` through
  `createItem`/`importAttachment`/`saveReadingState`.
- `BookReaderScreenTest` — open/title/chapter rendering, TOC scroll, in-book search
  (hit + empty), bookmark add/delete persistence, appearance theme + font
  persistence, corrupt bytes → `book_error`, progress saved after scroll, restore
  from reading state, chrome toggle, tap zones, back, cancelled bookmark dialog.
- `BookReaderHelpersTest` — plain JVM search/snippet/render-cap helpers.
- `ReaderPrefsTest` — defaults, round trip, clamping.
- `LibraryScreenTest` — rows/labels/progress, filters, empty state, locked hiding,
  newest-first order, trash action, rename dialog, book opening, drawing preview,
  import menu, back, and the pure shelf builder.

## Formats (TXT / FB2 / CBZ)

Increment 14 extends the `BOOK_FILE` item to every format the engine in
`com.cipherwell.notezero.book` supports. The item kind, payload and routing are unchanged:
the format is detected from the **decrypted bytes on open** (and used at import for the stored
MIME/metadata), so existing EPUB items are unaffected and no new item kind or permission was
added. The stored attachment MIME is informational only; it is never trusted for dispatch.

| Format | Engine | MIME stored at import | Reader surface |
|---|---|---|---|
| EPUB 2/3 | `EpubParser` → `EpubDocument` | `application/epub+zip` | text reader (unchanged) |
| TXT | `TxtBookParser` → `InMemoryDocument` | `text/plain` | text reader |
| FB2 (FictionBook 2) | `Fb2BookParser` → `InMemoryDocument` | `application/x-fictionbook+xml` | text reader |
| CBZ (comic archive) | `CbzParser` → `ComicBook` | `application/vnd.comicbook+zip` | `ComicReaderScreen` |

### Dispatch (`BookReaderScreen`)

`BookReaderScreen(repository, itemId, onClose)` opens the item's first attachment, decrypts it
and calls `BookLoader.load(bytes, fallbackTitle)` on `Dispatchers.IO`:

- `LoadedBook.Reflowable` — the existing reader runs over `BookContent` (the engine interface
  `EpubDocument` implements). EPUB, FB2 and TXT all get the same TOC, in-book search
  (`chapter.plainText()`), bookmarks, appearance dialog, chapter progress and resume; the
  chrome metadata line shows the detected format (`book_format_*`) next to the author.
- `LoadedBook.Comic` — rendered inside the same `book_reader` surface by
  `ComicReaderScreen(comic, repository, itemId, onClose)`; there is no text search/TOC.
- `null` — unsupported/corrupt/DRM input shows `book_error` (same as before).

Lifecycle is identical for both branches: the parsed handle is closed by a
`DisposableEffect.onDispose`, again on vault lock (then dropped), and the comic/`BookContent`
is never reachable after the screen leaves. Position semantics: `readingState.position` is the
chapter index for text formats and the page index for comics; `total` is the chapter/page
count; bookmarks are `ReadingBookmark(position = chapter/page index, label, createdAt)`.

### Comic reader (`ui/comic/ComicReaderScreen.kt`)

Paged `LazyColumn` of page images behind the `comic_pages` tag. Each page is inflated lazily
from `ComicBook.pageBytes(index)` (bounded by `comic.pageCount`, re-capped at 500 pages in the
UI) and decoded with `BitmapFactory` in memory through `AttachmentImages.decode` with a
1,600 px sample bound (narrower than the 2,048 px generic viewer bound, because a paged list
can hold several pages in memory). Decoded bitmaps are recycled by the composition that owns
them; no page bytes, plaintext or thumbnails are cached or logged. `ComicBook.close()` runs on
dispose (and again on vault lock via the parent reader).

| Area | Behaviour |
|---|---|
| Chrome | Back (`comic_back`, content description `cd_reader_back` = "Back"), item title, a `book_format_cbz` ("Comic") metadata line with the current `comic_page_progress`, add-bookmark and bookmark-list actions. |
| Progress | `comic_progress_bar` (percent of pages) and the page indicator/progress button (`comic_page_progress`, `%1$d / %2$d`). |
| Page jump | Tapping the page indicator opens `comic_page_jump_dialog`: a digits-only `comic_page_field`; a value outside `1..pageCount` keeps the dialog open and shows `comic_page_invalid` in the `comic_page_error` supporting text, a valid page scrolls the list (`comic_page_jump_confirm` / `comic_page_jump_cancel`). |
| Bookmarks | The chrome add action stores a page bookmark with the default label `comic_bookmark_default` ("Page N") at the current page; the list action opens `comic_bookmarks_dialog` (empty state `comic_bookmarks_empty`). Rows (`comic_bookmark_row`) jump to their page, the trailing delete action (`comic_bookmark_delete`) removes it; both persist through `saveReadingState`, capped at `ReadingState.MAX_BOOKMARKS`. Non-secret notices reuse the reader bookmark strings. |
| Reading state | Restores `readingState.position` on open (list initial index plus a one-shot scroll when the item arrives late), records `total = pageCount` immediately, and saves the settled page after a 500 ms debounce. |
| Loading / error | A page that is still decoding shows a spinner plus `comic_loading`; a page whose bytes cannot be decoded shows `comic_image_failed` (`comic_page_image_error`), never a crash. An empty/defensive comic (`pageCount <= 0`) shows `comic_error`; hostile/corrupt CBZ bytes fail earlier in `BookLoader` and show the text reader's `book_error`. |
| Vault lock | Open dialogs are dismissed when the vault locks and the parent reader drops/close()s the comic. |

Comic test tags: `comic_reader`, `comic_back`, `comic_title`, `comic_pages`, `comic_page`
(per decoded page image), `comic_page_frame`, `comic_page_image_error`, `comic_progress_bar`,
`comic_page_progress`, `comic_page_jump_dialog`, `comic_page_field`, `comic_page_error`,
`comic_page_jump_confirm`, `comic_page_jump_cancel`, `comic_bookmark_add`, `comic_bookmarks`,
`comic_bookmarks_dialog`, `comic_bookmarks_empty`, `comic_bookmark_row`, `comic_bookmark_delete`,
`comic_bookmarks_close`, `comic_notice`, `comic_error`.

### Reading state and metadata

- Import stores `ReadingState(author, language, total)` for reflowable books and
  `ReadingState(total = pageCount)` for comics (no author/language metadata in CBZ). The title
  is the content title when the format/metadata provides one, otherwise the cleaned SAF display
  name (extension dropped), otherwise `Untitled book`. Author is never appended to the title;
  the reader reads it from the content/`ReadingState`.
- No new persistence: positions/bookmarks stay in the encrypted `Item.readingState` payload and
  travel with encrypted backups; nothing is written to `ReaderPrefs` (appearance only) or logs.

### Limitations

- MOBI/AZW3/KFX, DjVu and DOCX remain unsupported (their parsers are explicitly deferred).
- EPUB fixed-layout/CSS fidelity is unchanged; TXT has no inline styling and auto-chaptering is
  heuristic (heading words or ~20,000-character "Part N" blocks); FB2 supports the engine's
  subset (no DTD, no external entities, images bounded to 64 × 4 MiB); CBZ parses no comic-info
  metadata (title comes from the item/display name), exposes at most 500 pages and skips
  entries the bounded ZIP reader rejects.
- Comic page images are decoded one at a time with a 1,600 px sample bound and recycled with
  the composition; there is no zoom/pan, no double-page spread and no pre-rendering cache.
- The import stream is bounded by `AttachmentLimits.MAX_ATTACHMENT_BYTES` (10 MiB), the same
  limit the attachment pipeline enforces, so larger books are rejected with
  `library_import_failed`.
- Robolectric's `BitmapFactory` shadow fabricates a bitmap for undecodable bytes and its lazy
  list only knows item sizes for already-measured pages, so the comic UI tests target page 2 for
  deterministic navigation and fake a missing-page `ComicBook` for the image-error state; real
  decode/scale behaviour is exercised by the optional instrumented test and device QA.

### Tests

Robolectric + Compose (`@Config(sdk = [34])`, no display qualifiers), generated-in-memory
fixtures only:

- `ui/reader/BookFormatsTestData.kt` — TXT (two auto chapters) and FB2 (metadata, two top-level
  sections, one nested TOC entry) fixtures seeded through the encrypted-attachment path.
- `ui/reader/BookReaderScreenTest.kt` (23 tests) — the existing EPUB coverage plus: TXT
  chapters/paragraphs, TXT TOC jump, TXT search, FB2 metadata + sections + nested body, FB2
  nested TOC, FB2 search, and unsupported bytes → `book_error` (the corrupt fixture is now
  binary so it is not detected as TXT).
- `ui/comic/ComicTestData.kt` — hand-built valid RGB PNGs, a real `ZipOutputStream` CBZ, a
  corrupt-ZIP fixture and a missing-page `ComicBook`; pages parse through the real `CbzParser`.
- `ui/comic/ComicReaderScreenTest.kt` (11 tests) — decoded pages/page indicator/no text tools,
  scroll → encrypted position saved, page-jump validation + jump, bookmark add/delete
  persistence, bookmark jump, resume from `readingState`, empty comic → `comic_error`, missing
  page bytes → `comic_image_failed`, corrupt CBZ → `book_error` via the book reader, CBZ
  dispatch + back, and `ComicBook.close()` on dispose.
- `ui/library/LibraryBookImportTest.kt` (7 tests) — the testable `importBookFromBytes` seam:
  TXT mime/title/total, FB2 metadata title/author/language, CBZ mime/page total, the
  `Untitled book` fallback, and corrupt ZIP/empty/corrupt-FB2 cleanup (item trashed, no
  attachment, no orphan shelf row).
- `ui/library/LibraryScreenTest.kt` (17 tests) — unchanged coverage plus the multi-format
  import menu label.
- Full reader/library/comic run: `./gradlew :app:testDebugUnitTest --tests
  "com.cipherwell.notezero.ui.reader.*" --tests "com.cipherwell.notezero.ui.comic.*" --tests
  "com.cipherwell.notezero.ui.library.*"` → 70 tests, 0 failures.
