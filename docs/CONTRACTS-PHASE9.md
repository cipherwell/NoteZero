# NoteZero — Phase-9 (increment 10) contracts: Library, EPUB reader, reading state, PDF tools

Frozen for increment 10. Tests/UI address these names only; renaming requires a matching change here.

## A. Item model + encrypted reading state (data layer, Lead)

| Contract | Value |
|---|---|
| New item kind | `ItemKind.BOOK_FILE` appended after `PDF_FILE` (payload ordinal 3; ordinals frozen) |
| Storage | Book = item with exactly one `application/epub+zip` attachment (`NZAT` blob), like PDFs/images |
| `ReadingState` | `model/ReadingState.kt`: `position` (chapter/page, 0-based), `fraction` (0..1), `total` (chapters/pages, 0 unknown), `bookmarks: List<ReadingBookmark(position,label,createdAt)>`, `author`, `language`. Bounds: ≤500 bookmarks, label ≤1024 UTF-8 bytes, author/language ≤1024 bytes, position/total ≤1,000,000 |
| Persistence | `ItemCodec` payload **v4** appends the reading state; v1–v3 payloads still decode (readingState = null); item JSON/DB columns unchanged (still redacted) |
| Repository API | `suspend fun saveReadingState(itemId, state): Boolean` — repository-owned like attachments: `saveItem` preserves the stored state; returns false when locked/unknown/invalid; state travels with encrypted backup v2 automatically |
| Tests | codec v4 round trip + v3 compatibility + bounds; repository persist/lock-unlock/backup; no plaintext bookmark/author in Room ciphertext |

## B. Library screen (book-ui subagent)

| Element | Contract |
|---|---|
| Entry | Notes top bar book icon (`library_button`) → `LibraryScreen`; back (`library_back`) returns to Notes |
| Root | tag `library_screen`; title "Library"; import action (`library_import`) with menu `library_import_pdf` / `library_import_epub` (SAF OpenDocument; external-activity bracket; failures show `library_import_failed` / `library_import_unsupported`) |
| Filters | chips tags `library_filter_all`, `library_filter_books`, `library_filter_pdfs`, `library_filter_drawings` |
| Rows | tag `library_item` (one per document); show title, type label (`library_type_pdf`/`library_type_book`/`library_type_drawing`), progress (`library_page_progress`/`library_chapter_progress`/`library_progress_percent`), `library_continue`; tap opens the document; overflow (`library_actions`) offers open/rename/lock/trash/export rules (reuse existing vault menu conventions) |
| Contents | PDF_FILE items, BOOK_FILE items, drawings = NOTE items with an attachment whose display name starts with `drawing-` (encrypted PNG); sorted by updatedAt desc; empty state `library_empty_title`/`library_empty_message` |
| Import | PDF: create PDF_FILE item + import attachment + set title from file name (store page count into ReadingState.total when cheaply available via `PdfPageRenderer`); EPUB: create BOOK_FILE item + import attachment + parse with `EpubParser` to derive title/author/language + store `ReadingState(author, language, total = chapters.size)`; failed parse → trash the just-created item (no empty row/orphan) and show `library_import_unsupported` |

## C. EPUB reader (book-ui subagent, engine from epub-engine)

| Element | Contract |
|---|---|
| Entry | `BookReaderScreen(repository, itemId, onClose)`; root tag `book_reader`; back `book_back`; title `book_title` |
| Loading/error | `book_loading` while decrypting/parsing; `book_error` for invalid/DRM/corrupt (never crashes, no content log) |
| Content | LazyColumn of chapters (one item per chapter, tag `book_chapter` for the chapter container/header text), rendered blocks: headings, paragraphs with inline bold/italic/code/link styling, lists, checklists, quotes, code, rules, images (decoded in memory via `EpubDocument.imageBytes`), chapter progress |
| Toolbar | TOC (`book_toc` → dialog/sheet `book_toc_dialog`, rows `book_toc_entry`), search (`book_search` → `book_search_dialog`, field `book_search_query`, results `book_search_result`, empty `book_search_empty`), bookmarks (`book_bookmarks` → `book_bookmarks_dialog`, add `book_bookmark_add`, label dialog `book_bookmark_label`/`book_bookmark_save`/`book_bookmark_cancel`, rows `book_bookmark_row`, delete `book_bookmark_delete`), appearance (`book_appearance` → `book_appearance_dialog` with `book_font_increase`/`book_font_decrease`, `book_theme_paper`/`book_theme_sepia`/`book_theme_night`, line spacing/margins controls), progress bar + `book_progress_percent` |
| Reading state | restore from `item.readingState` (chapter + fraction + total); save on chapter change/scroll settle (≥500 ms debounce) and on bookmark changes via `repository.saveReadingState`; bookmarks `position` = chapter index |
| Navigation | tap zones (`book_tap_left`/`book_tap_right`/`book_tap_center`) → previous/next chapter, toggle chrome; TOC/bookmark/search-result selection scrolls to chapter |
| Settings | non-secret reader preferences (font scale, line height, margins, theme) in app-private SharedPreferences (`ReaderPrefs`); defaults: 1.0 scale, 1.5 line height, 24dp margins, paper theme |
| Tests | Robolectric with `FakeNoteRepository` + generated minimal EPUB bytes (build with `java.util.zip.ZipOutputStream` in the test): loading→content, TOC mapping, in-book search result, bookmark add/jump persisted through repository, appearance toggles, corrupt/DRM → `book_error`, progress saved after scroll |

## D. PDF reader tools (Lead, `ui/pdf/PdfViewerScreen.kt`)

| Element | Contract |
|---|---|
| Existing tags | `pdf_viewer`, `pdf_back`, `pdf_title`, `pdf_export`, `pdf_page` unchanged |
| Progress | `pdf_page_progress` ("n / total"); `pdf_progress_bar` |
| Page jump | `pdf_page_progress` → `pdf_page_jump_dialog`, field `pdf_page_field`, invalid `pdf_page_error`, confirm `pdf_page_jump_confirm`, cancel `pdf_page_jump_cancel` |
| Bookmarks | `pdf_bookmark_add` → `pdf_bookmark_dialog` (label `pdf_bookmark_label`, save `pdf_bookmark_save`, cancel `pdf_bookmark_cancel`); `pdf_bookmarks_open` → `pdf_bookmarks_dialog` (rows `pdf_bookmark_row`, jump on tap, delete `pdf_bookmark_delete`, close `pdf_bookmarks_close`, empty `pdf_bookmarks_empty`) |
| Reading state | restore initial page from `readingState.position`; save total after render and page after debounce; bookmark `position` = 0-based page index; no plaintext persistence |
| Tests | extend `PdfViewerScreenTest`: total persisted after render, bookmark add/list/delete persisted, page-jump validation, resume initial page |

## E. Notes integration (Lead)

- Notes list rows: PDF → `PdfViewerScreen` (unchanged), CODE_FILE → code editor (unchanged), BOOK_FILE → `BookReaderScreen`, NOTE with drawing attachments stays a note.
- Top bar: add library button (`library_button`) next to the code/trash icons.
- Quick capture/create sheet unchanged; imports live in the Library.
- Locked/masked rows keep failing closed (readers must never be reachable for masked items; Library filters masked/locked content like the code workspace).
