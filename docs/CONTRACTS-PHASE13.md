# NoteZero — Phase-13 (increment 14) contract: more reading formats (TXT / FB2 / CBZ)

Frozen for increment 14. UI/tests address these names only.

## Scope

Extend `BOOK_FILE` beyond EPUB 2/3 to the three highest-value open formats:
- **TXT** — plain text (UTF-8 / UTF-16 BOM), auto-chaptered for progress.
- **FB2** — FictionBook 2 XML (sections, titles, paragraphs, emphasis/strong/code, poems,
  epigraphs/cites, empty lines, inline base64 images + cover).
- **CBZ** — comic archive (ZIP of page images), page reader.

No new item kind/payload change: `BOOK_FILE` stays the e-book item; the **format is detected from
the decrypted bytes on open** (and used at import for metadata), so existing EPUB items are
unaffected. No new permissions/dependencies.

Explicitly deferred: MOBI/AZW3/KFX (own parser + DRM caveat), DjVu/DOCX, PDF reflow/text search,
EPUB fixed-layout/CSS fidelity beyond today.

## A. Engine (engine-agent; owns `book/**` + tests + `docs/BOOK-FORMATS.md`)

New public API in `com.cipherwell.notezero.book` (keep `BookBlock`/`BookInline`/`BookChapter`/
`BookTocEntry` unchanged):

```kotlin
/** Read-only content of a reflowable book (EPUB/FB2/TXT). */
interface BookContent {
    val title: String
    val author: String?
    val language: String?
    val chapters: List<BookChapter>
    val toc: List<BookTocEntry>
    val cover: ByteArray?
    fun imageBytes(entry: String): ByteArray?
    fun close()
}

enum class BookFormat(val mimeType: String) {
    EPUB("application/epub+zip"),
    FB2("application/x-fictionbook+xml"),
    TXT("text/plain"),
    CBZ("application/vnd.comicbook+zip"),
}

/** A fixed-page comic; pages are decoded lazily in memory. */
interface ComicBook {
    val title: String
    val pageCount: Int
    fun pageBytes(index: Int): ByteArray?   // null when missing/over cap/closed
    fun close()
}

sealed interface LoadedBook {
    data class Reflowable(val content: BookContent, val format: BookFormat) : LoadedBook
    data class Comic(val comic: ComicBook, val format: BookFormat) : LoadedBook
}

object BookDetector { fun detect(bytes: ByteArray): BookFormat? }          // null = unsupported
object BookLoader { fun load(bytes: ByteArray, fallbackTitle: String): LoadedBook? } // null = unsupported/corrupt

object TxtBookParser { fun parse(bytes: ByteArray, fallbackTitle: String): BookContent? }
object Fb2BookParser { fun parse(bytes: ByteArray, fallbackTitle: String): BookContent? }
object CbzParser { fun parse(bytes: ByteArray, fallbackTitle: String): ComicBook? }
```

- `EpubDocument` implements `BookContent` (same members; no behaviour change) and
  `EpubParser` stays as-is; `BookDetector` recognises an EPUB via the `mimetype` entry
  `application/epub+zip` + `META-INF/container.xml`.
- **Detection order**: EPUB (ZIP + container/mimetype) → CBZ (ZIP whose entries are images, no
  EPUB container) → FB2 (XML root `FictionBook`, name-space tolerant, secure XML only) → TXT
  (valid/decodable text, no NULs/control spam). Invalid/ambiguous → null.
- **TXT**: decode UTF-8 (BOM-aware) or UTF-16LE/BE with BOM; reject invalid UTF-8 only if no BOM
  and replacement ratio is high (fall back to windows-1252); split into chapters at
  blank-line-separated headings matching `^(chapter|part|book|prologue|epilogue|introduction|appendix)\\b`
  (case-insensitive) or by ~20,000-char paragraph-boundary blocks ("Part N" titles otherwise);
  one paragraph per blank-line block → `BookBlock.Paragraph` (preserve single newlines as
  paragraphs? choose: single line breaks inside a block are joined with spaces; blank lines split);
  TOC = chapter titles; caps ≤2,000 chapters / 20,000 blocks/chapter / 2M chars/chapter.
- **FB2**: secure XML (no DTD/XXE); description/title-info (book-title, first/middle/last-name,
  lang); body sections → chapters (top-level sections, nested sections become headings/blocks;
  TOC from section titles); map p/emphasis/strong/code/poem/stanza/v/epigraph/cite/empty-line/
  subtitle; `<binary id=... content-type=image/...>` base64 images referenced by `<image l:href="#id">`
  (bound: ≤4 MiB decoded per image, ≤64 images, lazily returned via `imageBytes`); cover from
  `<coverpage><image>`; malformed required XML → null (never throw).
- **CBZ**: ZIP entry names natural-sorted (numeric order), image extensions jpg/jpeg/png/webp/gif;
  ≤500 pages, each ≤16 MiB, total ≤128 MiB; pageBytes returns copies from the bounded archive;
  `title` from fallback (no metadata); no comic-info parsing required (optional).
- **Lifecycle/bounds**: `close()` idempotent, later image/page reads return null; no temp files, no
  plaintext caches, no logging, no `android.*` imports; decode fuzz/malformed inputs never throw.

**Tests (engine-agent, plain JVM)**: detector matrix (EPUB/FB2/TXT/CBZ/unknown/truncated/random);
TXT UTF-8/UTF-16 BOM, chapter splitting, long-line/empty input, paragraph mapping,
caps; FB2 metadata/TOC/sections/inline styles/poem/epigraph/cover/base64 image/malformed XML/DTD
refusal/limits; CBZ sorted pages, page bytes, caps, corrupt zip, missing pages; BookLoader dispatch;
fuzz 1,000 random blobs → null/never throw. `docs/BOOK-FORMATS.md`: supported subset, caps,
detection rules, limitations, test evidence.

## B. UI (ui-agent; owns `ui/library/LibraryScreen.kt`, `ui/reader/**`, `ui/comic/**`, tests)

- **Import**: Library's book importer accepts any file (picker already generic), detects the format
  with `BookDetector`, stores the attachment with the detected MIME, derives title/author via
  `BookLoader` (fallback: file display name; title "Untitled book" if blank), and shows
  `library_import_unsupported` for unknown/corrupt/DRM inputs while removing the placeholder item.
  Existing EPUB import keeps working; the picker menu label may stay "E-book (EPUB)" or become
  "E-book (EPUB/FB2/TXT/CBZ)" (resource string update allowed via the Lead's string pre-add — see
  below; do not edit res/ yourself).
- **Library rows**: `BOOK_FILE` rows stay type "E-book"; no format-specific layout required.
- **BookReaderScreen**: load the item's first attachment, `BookLoader.load(bytes, itemTitle)`;
  show `book_error` for null. EPUB/FB2/TXT use the existing reader UI over `BookContent`
  (TOC/search/bookmarks/appearance/progress/resume unchanged; `document` type becomes `BookContent`
  and `EpubDocument`-specific call sites adapt). CBZ routes to a new **ComicReaderScreen**:
  page images rendered from `ComicBook.pageBytes` (in memory, recycled bitmaps), page
  indicator/progress, page jump, bookmarks (page-based, encrypted `ReadingState`), resume, back;
  no text search/TOC.
- Reading state: `position` = chapter index for text formats, page index for comics; total =
  chapter/page count; bookmarks store position + label; repository `saveReadingState` (existing).
- Strings: pre-added `book_format_*`/`comic_*` as needed by the Lead (check `strings.xml` first;
  do not edit res/). Reader title remains the item title; format shown in the metadata line where
  useful.
- Tests (ui-agent, Robolectric): import TXT/FB2/CBZ through a fake repository + SAF result seam
  (format detection + title/author), `.txt` reader renders/search/TOC/progress, FB2 reader TOC and
  body, CBZ comic page navigation/bookmarks/progress persisted, corrupt/unknown → error/no orphan,
  existing EPUB/PDF/reader/library tests stay green. Instrumented: real CBZ page bitmap decode +
  bounded/corrupt inputs never crash (optional if time).
- 48dp targets, large-font safe, no new dependencies/permissions, no content logging.

## C. Lead integration

- NotesListScreen BOOK row routing unchanged (opens `BookReaderScreen`, which dispatches formats).
- Screenshots/docs/verification/reproducible artifacts; Library menu string update if needed.
