# NoteZero — additional book formats: TXT / FictionBook 2 (FB2) / CBZ

**Owner:** engine (`app/src/main/java/com/cipherwell/notezero/book/`). This document is
normative for how NoteZero opens plain-text, FictionBook 2 and comic-archive files, what it
supports, what it refuses and the resource limits the UI can rely on. The EPUB 2/3 engine and
its contract (`docs/EPUB-ENGINE.md`) are unchanged; this increment extends the same package with
a format-agnostic reader surface.

Implementation: pure Kotlin/JVM, zero dependencies, no `android.*` imports — unit tests run on a
plain JVM. Nothing is written to disk, nothing is logged.

| File | Role |
|---|---|
| `BookContent.kt` | frozen public `BookContent` interface + `BookFormat` enum |
| `ComicBook.kt` | frozen public `ComicBook` interface + `LoadedBook` sealed result |
| `BookDetector.kt` | `object BookDetector`: content-based format detection (order below) |
| `BookLoader.kt` | `object BookLoader`: detect + dispatch, the single entry point for the UI |
| `TxtDecoder.kt` | bounded BOM-aware UTF-8/UTF-16 decoding + windows-1252 fallback + text probe |
| `TxtBookParser.kt` | TXT → chapters/paragraphs/TOC with auto-chaptering |
| `BookText.kt` | shared fallback-title / whitespace-collapse / truncate helpers |
| `Fb2BookParser.kt` | streaming, hardened SAX FB2 → metadata/blocks/TOC/images/cover |
| `InlineAccumulator.kt` | bounded inline-run builder with the `BookInline` style flags |
| `Base64Codec.kt` | strict bounded base64 decoder for `<binary>` (API-24-safe, no `java.util.Base64`) |
| `XmlRootProbe.kt` | cheap bounded XML root sniff for detection (no tree allocation) |
| `CbzParser.kt` | CBZ page discovery (natural order, caps) |
| `CbzComic.kt` | lazy `ComicBook` implementation over the retained archive |
| `NaturalOrder.kt` | natural ordering of page names (`page2` < `page10`) |
| `InMemoryDocument.kt` | `BookContent` implementation for TXT/FB2 (bounded image bytes + lifecycle) |
| `ZipArchive.kt`, `EpubPaths.kt`, `SecureXml.kt`, `BookLimits.kt` | reused unchanged for CBZ/FB2 (bounded ZIP reader, path guards, hardened XML factories, every bound) |

Tests: `app/src/test/java/com/cipherwell/notezero/book/` (`BookFormatFixtures.kt`,
`BookDetectorTest`, `TxtBookParserTest`, `Fb2BookParserTest`, `CbzParserTest`, `BookLoaderTest`).
No binary fixture is committed: every archive/XML/text payload is generated in memory.

## 1. Design in one paragraph

`BookDetector.detect(bytes)` sniffs the decrypted bytes (never the extension) and returns
`EPUB`, `CBZ`, `FB2`, `TXT` or null. `BookLoader.load(bytes, fallbackTitle)` then dispatches to
the matching parser and returns a `LoadedBook`: `LoadedBook.Reflowable(BookContent, format)` for
EPUB/FB2/TXT — the existing reader UI consumes `BookContent` — or `LoadedBook.Comic(ComicBook,
format)` for CBZ, which a page-image reader consumes. `EpubDocument` now implements
`BookContent` without any behaviour change (`EpubParser` is untouched). TXT is decoded in
memory and auto-chaptered; FB2 is walked with a hardened streaming SAX handler and its base64
`<binary>` images are decoded eagerly into a bounded map; CBZ keeps only the compressed archive
and inflates one page per `pageBytes` call. Every parser is bounded before or while bytes are
produced, fails closed with null, and never throws for any input (fuzzed).

## 2. Frozen public API

The UI compiles against exactly this surface (no deviations were introduced):

```kotlin
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
object BookDetector { fun detect(bytes: ByteArray): BookFormat? }
object BookLoader { fun load(bytes: ByteArray, fallbackTitle: String): LoadedBook? }
object TxtBookParser { fun parse(bytes: ByteArray, fallbackTitle: String): BookContent? }
object Fb2BookParser { fun parse(bytes: ByteArray, fallbackTitle: String): BookContent? }
object CbzParser { fun parse(bytes: ByteArray, fallbackTitle: String): ComicBook? }
class EpubDocument internal constructor(...) : BookContent   // same members, same behaviour
```

Semantics the UI can rely on:

- `chapters`/`toc` semantics are the ones the reader already uses for EPUB: `index` is the list
  position, `toc.chapterIndex` addresses `chapters`, `depth` starts at 0.
- `href` is a stable synthetic identity for the new formats (`txt#chapter-3`,
  `fb2#section-2`); EPUB keeps its archive-relative document path. The reader should address
  chapters by index.
- `BookBlock.Image.entry` is opaque to the UI: pass it back to `imageBytes(entry)` unchanged.
  EPUB entries are archive paths; FB2 entries are `<binary id="…">` ids.
- Metadata fallback: TXT has no metadata (title = the app-provided fallback, `author` =
  `language` = null); FB2 uses `<book-title>`/first `<author>`/`<lang>` and falls back to the
  app-provided title; CBZ title = the app-provided fallback. Blank fallback → `"Untitled"`.

## 3. Detection (`BookDetector.detect`)

Order is fixed; the first match wins. A null result means "not a supported book".

1. **ZIP signature** (`PK\x03\x04`, empty `PK\x05\x06` or spanned `PK\x07\x08`): open the bounded
   `ZipArchive`. Unreadable archive (truncated, ZIP64, encrypted, >10,000 entries, >128 MiB
   declared) → null. Then:
   - `mimetype` entry equals `application/epub+zip` **and/or** `META-INF/container.xml` exists →
     **EPUB**;
   - otherwise at least one page-image entry (`jpg`/`jpeg`/`png`/`webp`/`gif`, case-insensitive
     extension) → **CBZ**;
   - otherwise null (a ZIP of text is not a supported book).
2. Not a ZIP: a cheap bounded XML root probe (`XmlRootProbe`, first 64 KiB; tolerant of BOM,
   XML prolog, comments and `DOCTYPE` with an internal subset) whose namespace-tolerant root
   element is `FictionBook` → **FB2**. Well-formedness is *not* asserted here; the FB2 parser is
   the authority (a malformed FictionBook document is detected as FB2 and then fails to load,
   so it is never silently rendered as plain text).
3. A bounded text probe (first 64 KiB decoded with the TXT rules below; not blank, no NUL, no
   control-character spam, low U+FFFD ratio) → **TXT**.
4. Anything else → null (empty input, random bytes, truncated archives, ambiguous payloads).

Legacy 8-bit text is detected with the same fallback the parser uses, so detection and parsing
agree for UTF-8 (with/without BOM), UTF-16LE/BE with BOM and windows-1252.

## 4. TXT — plain text

### 4.1 Decoding (`TxtDecoder`)

| Input | Behaviour |
|---|---|
| UTF-8 BOM | stripped; strict UTF-8; malformed input → null |
| UTF-16LE/BE BOM | decoded strictly; malformed/odd trailing byte → null |
| no BOM | lenient UTF-8 decode; if the U+FFFD replacement ratio is ≤ 2% the UTF-8 reading is kept (a few stray bytes), otherwise the whole file is decoded as windows-1252 |
| decoded text | rejected when empty/blank, contains a NUL, or has more than 2% control characters (C0 except tab/LF/FF/CR, DEL, C1) — so binary/corrupt files return null |
| size | at most 8,000,000 characters are decoded; the rest of the input is not read |

UTF-16 without a BOM is rejected (it decodes to NUL-laden text). Other legacy encodings
(Shift-JIS, GBK, KOI8-R, …) are not detected; see limitations.

### 4.2 Mapping

- Line endings: `\r\n`, `\n` and lone `\r` are treated as line breaks.
- A **paragraph** is a blank-line-separated block. Lines inside a block are collapsed
  (whitespace runs → one space) and joined with a single space, then emitted as one
  `BookBlock.Paragraph`. This keeps wrapped prose together and searches naturally.
- A paragraph that is exactly **one line**, at most 120 characters long, and starts with one of
  `chapter`, `part`, `book`, `prologue`, `epilogue`, `introduction`, `appendix`
  (case-insensitive, word boundary) starts a new chapter: it becomes the chapter's
  `BookBlock.Heading(1, runs)` and its title.
- Otherwise a new **"Part N"** chapter starts at the next paragraph boundary once the current
  chapter has reached ~20,000 characters (also used for a text with no headings at all, and for
  the text before the first heading). `N` counts auto-parts from 1.
- `toc` = one `BookTocEntry(title, chapterIndex, 0)` per chapter; every TXT chapter has a title
  (heading or `"Part N"`), so the TOC is never empty for a parsed book.
- Caps: 2,000 chapters, 20,000 blocks / 2,000,000 chars per chapter, 200,000 blocks /
  8,000,000 chars per book; extra input is truncated cleanly.

## 5. FB2 — FictionBook 2

### 5.1 Security

`Fb2BookParser` uses the same hardened SAX layer as the EPUB engine (`SecureXml.newSaxReader`):
external general/parameter entities and external-DTD loading disabled, JAXP external access
denied, secure processing on, and an `EntityResolver` answering every external resolution with
empty content (no network, no filesystem). Additionally:

- any `DOCTYPE` with an **internal DTD subset** is rejected before parsing (the only route to
  custom entities / entity-expansion bombs);
- a document that references an entity the parser had to *skip* (i.e. declared in an external
  DTD) is rejected (`skippedEntity` throws), so an XXE payload can never be silently dropped or
  resolved;
- UTF-16 documents are rejected (the byte-level DTD guard would be blind to them);
- the root element must be `FictionBook` (namespace-tolerant prefix/namespace);
- input larger than 32 MiB is refused without parsing;
- malformed XML, a missing `<body>` or any unexpected throwable → null. The parser never throws.

### 5.2 Structural mapping

| FB2 | Result |
|---|---|
| `body` (one or more) | containers of chapters; `name="notes"` bodies are appended as normal chapters |
| top-level `section` | one chapter; its first `<title>` becomes the chapter title |
| `body/title` and content outside sections | a "front matter" chapter (title from the body title) |
| nested `section` | heading + blocks inside the enclosing chapter; its title adds a deeper TOC entry |
| `section/title`, `body/title` | `Heading(1..6)` (level = section nesting depth, clamped to 6); feeds the TOC |
| `subtitle` | `Heading(sectionDepth + 1, max 6)` |
| `p` | `Paragraph(runs)`; inside `epigraph`/`cite` it becomes `Quote(runs)` |
| `emphasis` / `strong` / `code` | `BookInline.italic` / `.bold` / `.code` |
| `a` (`l:href`) | label text with `BookInline.link = true` (href not stored, no navigation) |
| `poem` / `stanza` / `v` | one `Quote` per stanza; line breaks preserved as `"\n"` inside the runs |
| `epigraph`, `cite` | `Quote` per paragraph; `text-author` → italic `Paragraph` |
| `empty-line` | `BookBlock.Rule` |
| `image` at block level | `BookBlock.Image(entry = binary id, alt = alt attribute)` |
| `image` inside a paragraph | cannot be an inline block in the frozen model → its `alt` text is appended (or nothing) |
| `table`/`tr`/`td` | flattened: cell text joins the current paragraph |
| `annotation` and other description elements | metadata only / ignored; loose text inside unknown body elements is dropped |
| `binary` | base64 images (see below), not blocks |

Text rules match the EPUB engine: XML whitespace collapses to one space, block edges are
trimmed, inline runs merge, styles reduce to the four `BookInline` flags. Chapter titles are
collapsed and truncated to 120 chars; TOC entries keep nesting depth (cap 10,000 entries,
depth 16). When no section has a title the TOC is derived as `Chapter N`.

### 5.3 Images and cover

- `<binary id="…" content-type="image/…">` payloads are collected as base64 characters
  (whitespace inside the element is ignored), decoded with a strict RFC 4648 decoder, and kept
  in an id-keyed map. Content types other than `image/*` are ignored; ids are first-wins.
- Bounds: at most 64 images, ≤ 4 MiB decoded each, ≤ 32 MiB decoded in total; over-cap
  payloads are dropped (the book still opens).
- `<image l:href="#id">` blocks reference the id; `BookContent.imageBytes(entry)` returns a
  fresh copy of the decoded bytes (`entry` accepts the id with or without the `#`), or null for
  unknown ids and after `close()`.
- `<coverpage><image l:href="#id"/></coverpage>` (first `title-info`) becomes
  `BookContent.cover` (a fresh copy, ≤ 4 MiB, null after `close`).
- The base64 decoder is hand-written because `java.util.Base64` is API 26+ while NoteZero
  supports API 24.

### 5.4 Metadata

`description/title-info`: `book-title` → `title` (fallback: app-provided title), the **first**
`author`'s `first-name`/`middle-name`/`last-name` joined with spaces (`nickname` when no name
parts) → `author` (null when absent), `lang` → `language` (null when absent). XML entities are
decoded by the parser; whitespace is collapsed; metadata is length-capped (500/200/64 chars).

## 6. CBZ — comic archive

- The archive is opened with the bounded `ZipArchive` reader, so the EPUB container limits apply
  on top of the comic caps: ≤ 10,000 central-directory entries, no ZIP64/multi-disk/encrypted
  entries, ≤ 16 MiB decompressed per entry, ≤ 128 MiB declared total; truncated archives are
  rejected.
- A page is an entry whose (case-insensitive) extension is `jpg`/`jpeg`/`png`/`webp`/`gif`.
  Entries over the 16 MiB cap are absent; the remaining names are sorted with `NaturalOrder`
  (digit runs compared numerically, so `page2` < `page10`, `007` == `7` with a deterministic
  tie-break) and the first 500 become pages. `ComicInfo.xml` and any other entry are ignored.
- An EPUB archive passed to `CbzParser` (or a ZIP with no page entries) returns null.
- `ComicBook.title` = the app-provided fallback (blank → `"Untitled"`); `pageCount` is the page
  list size; `pageBytes(index)` inflates that one entry on demand and returns a fresh
  caller-owned copy, or null for out-of-range/oversized/corrupt entries and after `close()`.
  No page is ever decoded up front and no temporary file is used.
- `close()` is idempotent; `title`/`pageCount` remain readable, `pageBytes` returns null.

## 7. Lifecycle (all formats)

- `BookContent`: parsed values (`title`, `author`, `language`, `chapters`, `toc`) stay readable
  forever; `cover`/`imageBytes` return fresh copies and return null after `close()`. `close()`
  is idempotent, never throws and is safe concurrently with lazy image reads. TXT has no byte
  state (both always null); EPUB keeps its compressed archive only while image blocks exist;
  FB2 keeps the bounded decoded image/cover arrays until `close`.
- `ComicBook`: `title`/`pageCount` stay readable; `pageBytes` returns null after `close()`;
  `close()` is idempotent and thread-safe.
- The UI must call `close()` when the reader screen goes away and keep rendering from the
  parsed values; it must not cache `ByteArray`s returned by `cover`/`imageBytes`/`pageBytes`
  beyond the current screen (they are caller-owned copies).

## 8. Limits (all enforced before/while bytes are produced)

| Limit | Value | Violation behaviour |
|---|---|---|
| ZIP: entries / entry bytes / total declared | 10,000 / 16 MiB / 128 MiB | archive rejected or entry unavailable (EPUB + CBZ) |
| FB2 input document | 32 MiB | `null` without parsing |
| FB2 decoded image, images, total image bytes | 4 MiB / 64 / 32 MiB | image dropped, book opens |
| FB2 base64 characters per `<binary>` | 6,000,000 | image dropped |
| chapters (TXT/FB2) | 2,000 | further chapters ignored |
| blocks per chapter (TXT/FB2) | 20,000 | further blocks ignored |
| chars per chapter (TXT/FB2) | 2,000,000 | further text truncated |
| total blocks / total text chars (TXT/FB2) | 200,000 / 8,000,000 | further input ignored |
| TXT decoded characters | 8,000,000 | remainder of the file unread |
| TXT auto-part target | 20,000 chars | next paragraph starts a "Part N" chapter |
| TOC entries / depth (FB2) | 10,000 / 16 | extra entries ignored |
| chapter/toc title length | 120 chars | truncated |
| CBZ pages | 500 | extra image entries ignored |
| cover | 4 MiB | cover dropped, book opens |

## 9. Security model

- **No trust in file names or MIME types.** Detection and every read is content-based; the
  stored attachment MIME is only a UI hint.
- **ZIP** (EPUB/CBZ): the reader parses the central directory itself, never `ZipInputStream`:
  first-entry-wins duplicates, safe path normalisation through `EpubPaths` (absolute paths,
  backslashes, NUL, `..`, URI schemes rejected), encrypted/ZIP64/multi-disk/unknown-compression
  entries unavailable, every inflate bounded by both the declared size and the hard cap,
  truncated/lying streams return null for that entry. Decompression never touches disk.
- **XML** (FB2): see §5.1 — no external entity resolution by construction, internal DTD subsets
  refused, skipped external entities refused, UTF-16 refused, parser errors fail the document.
  Images are base64 inside the document; remote/external image references are impossible (any
  `l:href` that is not a `#id` simply has no binary and renders nothing).
- **Allocation bounds:** every parser caps chapters/blocks/characters/images BEFORE creating the
  Kotlin objects (see §8); random/corrupt input returns null rather than allocating.
- **No disclosure:** no logging, no temp files, no plaintext cache, no `android.*` imports, no
  network. Parsed text and image bytes live only in memory until `close()`.

## 10. Failure policy summary

`BookLoader.load` returns null for: empty input; unsupported/corrupt ZIP; ZIP of text; malformed
FictionBook XML, XXE/internal-DTD/UTF-16 input, FB2 without a body or over 32 MiB; undecodable/
binary TXT, blank TXT, or TXT that yields no chapters; CBZ without pages or over archive caps;
anything DRM/zip-encrypted; and any unexpected throwable. It returns a document when an optional
piece is missing (unknown image id, oversized image, missing cover, section without title, a
heading-less TXT) because those remain readable.

Per-parser: `TxtBookParser.parse`, `Fb2BookParser.parse`, `CbzParser.parse` each return null for
invalid input and never throw; `EpubParser.parse` behaviour is unchanged.

## 11. Known limitations (UI must handle)

- **TXT metadata**: none is extracted — `author`/`language` are null and the title is the
  item/file title. Encoding support is UTF-8/UTF-16 (BOM) plus windows-1252 fallback; Shift-JIS,
  GBK, KOI8-R, EBCDIC, … are not detected and will render as mojibake (recommend UTF-8).
- **TXT chaptering** is heuristic: a heading must be a standalone single-line block (blank line
  before/after) of ≤ 120 chars starting with a documented keyword; a standalone prose line like
  "Book the tickets now." is therefore misclassified as a chapter heading. Long single
  paragraphs cannot be split (a part can exceed 20,000 chars). Text beyond the 8,000,000-char
  decode cap and chapters beyond 2,000 are truncated.
- **FB2 subset**: no annotations/translations beyond the first `title-info`/`author`; footnote
  `a` links keep their label but do not navigate; tables are flattened to text; `annotation`
  elements are ignored; images must be embedded `<binary>` (no external files, no `data:`),
  support is limited to `image/*` content types and the caps in §8; poetry line breaks are
  embedded as `"\n"` characters inside a `Quote`'s runs (renderers that ignore newlines will show
  a stanza as one line); UTF-16 FB2 is refused (convert to UTF-8); documents relying on
  entities declared in an external DTD are refused.
- **CBZ**: no metadata (`ComicInfo.xml` is ignored; title = item title); page order is the
  natural sort of entry names, so archives without numeric names page in lexical order; only
  stored/deflated entries with the five image extensions are pages; archives with more than
  10,000 entries or more than 128 MiB declared are rejected, pages over 16 MiB are skipped and
  only the first 500 pages are exposed; a corrupt page yields `null` (render a placeholder, do
  not crash); CP437-only legacy entry names are skipped like the EPUB reader does.
- **Detection**: extension and MIME are ignored; a ZIP that contains only text/XHTML is not a
  supported book; an XML document whose root is not `FictionBook` is treated as plain text; a
  malformed FictionBook document is detected as FB2 and then fails to load (it is not shown as
  raw text).
- `href` values of the new formats are synthetic identities, not file paths.

## 12. Test evidence

JDK 17 (`/usr/lib/jvm/java-17-openjdk`), single Gradle module `app`, plain JVM tests (no
Robolectric), fixtures generated in memory (reuses `ZipBuilder`/`RawZip` from `EpubFixtures.kt`
and adds TXT/FB2/CBZ builders in `BookFormatFixtures.kt`):

```
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
./gradlew :app:compileDebugKotlin --console=plain
./gradlew :app:testDebugUnitTest --tests "com.cipherwell.notezero.book.*" --console=plain
```

Both succeed (`BUILD SUCCESSFUL`). **211 tests, 0 failures, 0 errors, 0 skipped** — the 104
pre-existing EPUB/book tests are unchanged and green, plus **107 new tests**:

| Test class | Tests | Covers |
|---|---|---|
| `BookDetectorTest` | 18 | EPUB (mimetype/container/both), FB2 (namespace prefix, comments+DOCTYPE+BOM), TXT (UTF-8, BOM, UTF-16LE/BE, windows-1252), CBZ, EPUB-over-CBZ ambiguity, text-only ZIP, empty/whitespace/NUL, random blobs, truncated archives, non-FictionBook XML, corrupt signature |
| `TxtBookParserTest` | 27 | title fallback, paragraph mapping (single newlines/blank lines/tabs/CRLF/lone CR), UTF-8/BOM/UTF-16/windows-1252, low-ratio replacement, UTF-16-without-BOM and binary rejection, NUL rejection, heading chapter split/case-insensitivity/keyword coverage/multi-line and long-line guards, 20,000-char auto-parts, 2,000-chapter cap, 2M-char cap, TOC mirrors, plainText, href stability, `close()` lifecycle |
| `Fb2BookParserTest` | 35 | metadata/author/nickname/language/entities/fallback, sections/nested sections/front matter/named bodies/untitled TOC, inline styles, subtitle, poem line breaks, epigraph/cite/text-author, empty-line rule, base64 images + copies + missing ids, cover + post-close, image/cover caps, windows-1251 prolog, malformed XML, non-FictionBook root, internal-DTD entity bomb, XXE, external DOCTYPE tolerance, skipped external entity, UTF-16, missing body, 32 MiB cap, prefixed namespace, chapter/block/text caps, `close()` lifecycle |
| `CbzParserTest` | 15 | natural page order, ignored non-page entries, all image extensions, title fallback, page copies, out-of-range, `close()` lifecycle, 500-page cap, 16 MiB entry cap, 128 MiB archive rejection, corrupt deflate page, no-page archive, EPUB rejection, random/truncated, duplicate names |
| `BookLoaderTest` | 12 | dispatch per format, null for unsupported/corrupt/empty, malformed FB2 not shown as text, MIME values, `EpubDocument is BookContent`, uniform lifecycle, 1,000 random blobs through detector+loader (never throw; only TXT can be detected), 400 fixture mutations through the loader, 1,000 random blobs through every parser directly |

`grep`-verified: no `TODO`/`FIXME`/`println`/`printStackTrace`/`Log`/`System.out`/`System.err`,
no `android.*`/`androidx.*` imports, no filesystem/temp-file APIs and no `java.util.Base64` in
`book/`. The only pre-existing engine files touched are `BookLimits.kt` (added constants only)
and `EpubDocument.kt` (implements `BookContent`; no behaviour change).
