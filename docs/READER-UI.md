# NoteZero — Library + EPUB reader UI

Phase-10 reading surface. Implementation:

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
| Import | SAF `OpenDocument` picker for `application/pdf` and `application/epub+zip`. The picker is bracketed with `beginExternalActivity()`/`endExternalActivity()`; a URI that returns after the vault locked is dropped (no import, no content read). |
| PDF import | Creates `ItemKind.PDF_FILE`, imports the attachment as `application/pdf`, titles the item from `OpenableColumns.DISPLAY_NAME`. Any failure trashes the just-created item. |
| EPUB import | Same, as `ItemKind.BOOK_FILE` with `application/epub+zip`; then decrypts, `EpubParser.parse` on `Dispatchers.IO`, and on success stores the parsed title plus encrypted `ReadingState(author, language, total = chapters.size)`. A null parse trashes the item and shows the unsupported message. The `EpubDocument` is closed immediately. |
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
