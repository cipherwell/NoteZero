# NoteZero — EPUB parsing engine (`book/`)

**Owner:** EPUB engine (`app/src/main/java/com/cipherwell/notezero/book/`). This document is
normative for how NoteZero opens EPUB 2 / EPUB 3 files, what it supports, what it refuses and
the resource limits the UI can rely on.

Implementation (pure Kotlin, zero dependencies, no `android.*` imports — unit tests run on a
plain JVM):

| File | Role |
|---|---|
| `BookBlocks.kt` | frozen public model: `BookBlock`, `BookInline`, `BookListItem`, `BookChapter.plainText`, `BookTocEntry` |
| `EpubDocument.kt` | frozen public handle: metadata, chapters, TOC, cover, lazy `imageBytes`, `close` |
| `EpubParser.kt` | `object EpubParser`: container → OPF → manifest/spine → chapters → TOC → cover |
| `ChapterBlocks.kt` | streaming SAX XHTML → `BookBlock` converter |
| `ZipArchive.kt` | bounded, random-access ZIP central-directory reader |
| `SecureXml.kt` | hardened DOM/SAX factories, entity/DOM-string guards |
| `EpubPaths.kt` | archive-path normalisation and href resolution (single choke point for ZIP lookups) |
| `BookLimits.kt` | every numeric bound enforced by the engine |

Tests: `app/src/test/java/com/cipherwell/notezero/book/` (fixtures are generated in memory;
no binary EPUB is committed). No new Gradle dependency was added for the engine.

## 1. Design in one paragraph

`EpubParser.parse(ByteArray)` opens the ZIP from memory, refuses DRM-marked books, reads
`META-INF/container.xml` → OPF → `dc:` metadata / manifest / spine, parses each spine XHTML
chapter with a streaming, bounded SAX walk, resolves the TOC from the EPUB3 nav document (else
the EPUB2 NCX, else derived per-chapter titles) and extracts the cover. The result is
`EpubDocument`: immutable parsed Kotlin values plus the compressed archive bytes, retained only
while image blocks exist, until `close()`. Images are decompressed lazily from the retained
archive — one bounded entry at a time — so opening an illustrated book does not decode every
image up front. Every failure mode fails closed with `null`; `parse` never throws, whatever the
bytes are.

## 2. Frozen public API

The UI compiles against exactly this surface (no deviations were introduced):

```kotlin
sealed interface BookBlock {
    data class Heading(val level: Int, val runs: List<BookInline>) : BookBlock
    data class Paragraph(val runs: List<BookInline>) : BookBlock
    data class ListBlock(val ordered: Boolean, val items: List<BookListItem>) : BookBlock
    data class Quote(val runs: List<BookInline>) : BookBlock
    data class Code(val text: String) : BookBlock
    data class Image(val entry: String, val alt: String) : BookBlock
    data object Rule : BookBlock
}
data class BookInline(val text: String, val bold: Boolean = false, val italic: Boolean = false,
                      val code: Boolean = false, val link: Boolean = false)
data class BookListItem(val runs: List<BookInline>, val depth: Int = 0)
data class BookChapter(val index: Int, val title: String?, val href: String, val blocks: List<BookBlock>) {
    fun plainText(): String
}
data class BookTocEntry(val title: String, val chapterIndex: Int, val depth: Int)
class EpubDocument internal constructor(...) {
    val title: String; val author: String?; val language: String?
    val chapters: List<BookChapter>; val toc: List<BookTocEntry>; val cover: ByteArray?
    fun imageBytes(entry: String): ByteArray?
    fun close()
}
object EpubParser { const val MIME = "application/epub+zip"; fun parse(bytes: ByteArray): EpubDocument? }
```

Semantics the UI can rely on:

- `chapters` is spine order; `index` is the list position `0..n-1`. `href` is the archive-root
  relative path of the spine document (`OEBPS/text/ch1.xhtml`), i.e. the OPF-root-relative
  path for the normal case where the OPF sits at the archive root.
- `toc` maps to `chapters[].index`; it is empty only when there are no chapters (a derived
  one-entry-per-chapter TOC is generated whenever chapters exist and no nav/NCX maps).
- `title` falls back to `"Untitled"`. `author`/`language` are the first non-blank
  `dc:creator`/`dc:language` or `null`. Metadata text is whitespace-collapsed.
- `cover` / `imageBytes` return fresh copies owned by the caller; they are `null` after
  `close()`. `imageBytes` only serves entries that a parsed `BookBlock.Image` references — it
  is not a generic archive reader. `close()` is idempotent; `chapters`, `toc` and
  `plainText()` remain usable afterwards (the UI can keep rendering off the parsed values).
- Calls are safe from different threads (`close` vs. lazy image reads included).

## 3. Container / package handling

1. `META-INF/container.xml` must exist, be well-formed and name a `rootfile`. The first
   `rootfile` whose `media-type` is `application/oebps-package+xml` wins; otherwise the first
   `rootfile` with a usable `full-path` is used. A leading `/` on `full-path` is tolerated;
   unsafe paths (`..`, backslashes, NUL, schemes) are not.
2. The OPF is read and its metadata parsed: `dc:title`, `dc:creator` (first only),
   `dc:language` (first only), plus `<meta name="cover" content="id">` for EPUB2 covers.
3. The manifest maps `id` → archive path + `media-type` + `properties`. Hrefs are resolved
   relative to the OPF's own directory and percent-decoded exactly once. Unsafe hrefs (remote
   URLs, `data:`, absolute paths, backslashes, root escapes) are dropped; duplicate ids keep
   the first declaration.
4. The spine is `itemref` order. `itemref`s with an `idref` that has no manifest item are
   skipped. `linear` is not filtered (all spine items are rendered as chapters).
5. Chapters:
   - binary spine media types (`image/*`, `audio/*`, `video/*`, font, CSS, JS) become an empty
     chapter — the spine slot is kept but nothing is rendered/parsed;
   - a spine document whose archive entry is missing, unreadable, over the single-entry cap or
     of unknown compression is **skipped entirely** (chapter list re-indexes);
   - a readable XHTML/XML spine document that is not well-formed XML fails the **whole parse**
     (`null`) — the engine never returns a half-parsed book.
6. Chapter `title` is the plain text of the first non-blank `h1`, else first non-blank `h2`,
   collapsed/trimmed and truncated to 120 chars; `null` when the chapter has neither.

## 4. TOC resolution

Priority, first non-empty result wins:

1. **EPUB3 nav**: manifest item with `properties` containing `nav`; its `<nav>` with
   `epub:type="toc"` (first `<nav>` if no typed one). Nested `<ol>` is walked recursively;
   `depth` starts at 0 for top-level items. Labels come from `<a>` (or `<span>`) text, with
   the `title` attribute as fallback. Hrefs resolve relative to the nav document; fragments
   and queries are stripped and the path must equal a chapter `href`.
2. **EPUB2 NCX**: the manifest item named by `spine@toc`, else the first manifest item whose
   `media-type` is `application/x-dtbncx+xml`. `navMap/navPoint` is walked recursively
   (`navLabel/text` label, `content/@src` target with the same path mapping as nav).
   `depth` follows nesting.
3. **Derived fallback**: one entry per chapter, `title = chapter.title ?: "Chapter N"`
   (`N` = 1-based position), `depth = 0`.

Entries whose target path is not a parsed spine chapter are dropped (e.g. TOC links into
skipped/absent content). Limit: 10,000 TOC entries, nesting depth 16.

## 5. Cover and images

- Cover source: manifest item with `properties="cover-image"`, else `<meta name="cover"
  content="manifestId">`. Anything larger than **4 MiB** is dropped (`cover == null`); a
  missing/oversized/unsupported-encoding cover never fails the book. No cover → `null`.
- `BookBlock.Image.entry` is the image resource path normalised from the chapter document
  (`OEBPS/text/ch1.xhtml` + `../images/pic.png` → `OEBPS/images/pic.png`). `src` with a
  remote/absolute/`data:` URL is dropped (no network, no data-URI decoding), as is a `src`
  that escapes the archive root.
- `imageBytes(entry)` inflates that one entry on demand (bounded by 16 MiB) and returns a copy.
  It is `null` for: entries not referenced by a parsed image block, missing entries, entries
  over the cap, unknown compression, and after `close()`.
- Images inside a list item, table cell or quote cannot become structural blocks in the frozen
  model, so they degrade to their `alt` text there; images in paragraphs/divs split the
  paragraph and become real `BookBlock.Image` blocks.

## 6. XHTML → blocks

Streaming SAX (never a DOM tree) so chapter size is bounded by the caps rather than by markup
depth. Element mapping:

| XHTML | Result |
|---|---|
| `h1`…`h6` | `Heading(level, runs)` |
| `p` | `Paragraph(runs)` |
| `div`, `section`, `article`, `header`, `footer`, `main`, `nav`, `aside`, `figure`, `figcaption`, `body`, `html`, `dl`, `dt`, `dd`, `center`, `details`, `summary` | block container (children become blocks) |
| `ul` / `ol` / `li` | one `ListBlock` with flat `items`; nested lists become `BookListItem.depth + 1`, clamped to depth 8 |
| `blockquote` | `Quote(runs)` (nested paragraphs flatten into the quote text) |
| `pre` / `pre > code` | `Code(text)`, line breaks preserved, edges trimmed |
| `hr` | `Rule` |
| `table` / `tr` / `td` / `th` | each row becomes a `Paragraph` whose non-empty cells are joined with `" · "` |
| `img` | `Image(entry, alt)` (see §5) |
| `br` | single space |
| `em`, `i` | `italic = true` |
| `strong`, `b` | `bold = true` |
| `code` | `code = true` |
| `a` | label text with `link = true` (the href is intentionally not stored) |
| `sup`, `sub`, `span`, `u`, `s`, `small`, `big`, `mark`, … | transparent: text kept, no style |
| unknown elements | transparent: tags dropped, text kept |
| `script`, `style`, `head`, `title`, `meta`, `link`, `base`, `noscript`, `template`, `svg` | whole subtree dropped |

Text rules: XML whitespace runs collapse to one space; each block's runs are trimmed at the
edges; `&nbsp;` and other common XHTML named entities are decoded (`SecureXml` rewrites the
HTML Latin-1 entity set + punctuation to numeric references before parsing — required for real
EPUB2 files whose external DTD is never loaded); markup never appears in output text. Lists are
flat by design: a nested `<ol>` inside `<ul>` keeps its item text/depth but not its own ordered
flag (documented limitation of the frozen model).

## 7. Limits (all enforced before/while bytes are produced)

| Limit | Value | Violation behaviour |
|---|---|---|
| total declared uncompressed archive size | 128 MiB | archive rejected (`null`) |
| single entry uncompressed | 16 MiB | entry skipped; required entry → book rejected |
| archive entries (central directory) | 10,000 | archive rejected (`null`) |
| cover | 4 MiB | cover dropped, book parses |
| blocks per chapter | 20,000 | further blocks ignored (clean truncation) |
| searchable text per chapter | 2,000,000 chars | further text ignored (clean truncation) |
| list item depth | 8 | clamped |
| element nesting handled by the walker | 256 | deeper subtree ignored |
| metadata/container/OPF/NCX/nav XML | 4 MiB (container 256 KiB) | document unusable → fallback/`null` |
| chapters | 5,000 | extra spine items ignored |
| TOC entries / depth | 10,000 / 16 | extra entries ignored |
| derived chapter title | 120 chars | truncated |

## 8. Security model

**ZIP.** The reader parses the central directory manually (no `ZipInputStream` passes, no temp
files) so that it can enforce first-entry-wins duplicate semantics and validate names before
any inflation. Entry names are UTF-8, normalised through `EpubPaths`; absolute paths,
backslashes, NUL bytes, `..` escaping and URI schemes are ignored (never resolved). ZIP64,
multi-disk archives, encrypted ZIP entries, unknown compression methods and oversized entries
are unavailable. Every inflate is bounded by both the declared size and the hard cap, and
truncated/lying streams return `null` for that entry. Truncated archives (no durable EOCD) are
rejected. Decompression never writes to disk.

**XML.** All DOM/SAX parsers are created with external general/parameter entities disabled,
external-DTD loading disabled, JAXP external access attributes denied, secure processing on,
and an `EntityResolver` that answers every external resolution with empty content — no network
and no file access, by construction. `DOCTYPE` declarations are tolerated (real EPUB2 NCX and
XHTML documents carry external DTD references) but never resolved; documents with an internal
DTD subset are rejected outright, which removes the only route to custom entity declarations
(entity-expansion/"billion laughs" bombs). Known named HTML entities are rewritten to numeric
references; unknown entities make the document malformed. UTF-16 documents are rejected (the
byte-level DTD guard would be blind to them). Any parser error is a failure of the document —
the engine never returns partially parsed metadata/TOC/content.

**DRM.** `META-INF/rights.xml` present → `null`. `META-INF/encryption.xml` with any
`EncryptedData` element → `null` (this also refuses Adobe/IDPF font-obfuscated books, because
opening them would require content de-obfuscation; no circumvention is attempted). Malformed
`encryption.xml` fails closed. An `encryption.xml` without `EncryptedData` (e.g. an empty
container document) does not block the book.

**Lifecycle / disclosure.** Titles, text and image bytes are never logged; the engine writes
no files and creates no caches. The document holds the compressed archive bytes only while at
least one image block exists, plus the bounded cover copy, and drops both on `close()`.
Parsed blocks are ordinary Kotlin values with no reference back into the archive.

## 9. Failure policy summary

`EpubParser.parse` returns `null` for: empty input; not a ZIP; truncated/ZIP64/too-many-entries/
over-total-size archive; missing or malformed `container.xml`/OPF; DRM marker
(`rights.xml`/`EncryptedData`); any spine chapter whose bytes are readable but not well-formed
XML; a container/OPF that is itself over the XML cap. It returns a document (possibly with
empty `chapters`/`toc`/`cover`) when optional pieces are absent — missing chapter file, missing
image, missing nav/NCX, binary spine item, oversized cover — because those are recoverable for
reading/search.

## 10. Known limitations (UI must handle)

- Rendering is a semantic block model: no CSS, columns, floats, page breaks, footnotes,
  MathML, inline SVG, `epub:switch`, media overlays, scripts or remote resources. Inline SVG
  subtrees are dropped; SVG documents in the spine become empty chapters.
- Nested lists are flattened (depth preserved, per-level ordered/bullet marker is not).
  Images inside list items / cells / quotes degrade to alt text.
- Font-obfuscated (`EncryptedData`) books are rejected like DRM (see §8).
- A single malformed content document fails the whole book by design (no partial books).
- Missing/unreadable spine documents are skipped, so `chapters[i].index` no longer equals the
  spine position in such broken books; TOC entries pointing at skipped documents are dropped.
- `imageBytes` only serves entries referenced by parsed image blocks; the cover goes through
  `cover`. `imageBytes`/`cover` must be read before `close()`; UI should close when the reader
  screen goes away and keep rendering from `chapters`/`plainText()`.
- `plainText()` is the search corpus (blocks joined by `\n`, list items on their own lines,
  image alt text included, `Rule` contributes nothing). It is capped at 2,000,000 chars per
  chapter, so search does not cover text beyond that cap.
- ZIP entry names are decoded as UTF-8 (the OCF requirement); legacy CP437-only names are
  skipped.

## 11. Test evidence

JDK 17 (`/usr/lib/jvm/java-17-openjdk`), single Gradle module, no Robolectric; fixtures built in
memory with `ZipOutputStream` / a raw header writer:

```
./gradlew :app:compileDebugKotlin --console=plain
./gradlew :app:testDebugUnitTest --tests "com.cipherwell.notezero.book.*" --console=plain
```

Both succeed (`BUILD SUCCESSFUL`). **104 tests, 0 failures, 0 errors, 0 skipped**:

| Test class | Tests | Covers |
|---|---|---|
| `Epub3ParserTest` | 23 | metadata, spine order/hrefs/titles, EPUB3 nav depth + fragments, inline flags, all block types, tables, entities, cover/image copies, `plainText`, script/style ignore, `br`/`sup`/`sub`, unsafe images, fallbacks, rootfile selection, nested-OPF layout |
| `Epub2ParserTest` | 8 | EPUB2 metadata, nested NCX → spine indexes/depths, external XHTML DOCTYPE + named entities, `meta name=cover`, NCX-from-manifest fallback, nav-over-NCX preference, malformed-NCX fallback |
| `ChapterBlocksTest` | 21 | element mapping, div containers, list flattening/depth clamp, blockquote, pre, hr, tables, image path resolution and rejection, paragraph splitting, inline nesting, unknown elements, whitespace, ignored subtrees, entities, DOCTYPE tolerance, malformed XML, internal-DTD rejection |
| `EpubSecurityTest` | 19 | DRM (`EncryptedData`, `rights.xml`, malformed encryption.xml), missing/broken container+OPF, broken chapter, empty/non-zip input, truncation, corrupt EOCD, XXE/internal subset/entity bomb, 1,000 random fuzz samples + 300 mutations never throw, random chapter payload |
| `EpubLimitsTest` | 15 | path traversal names, duplicate entries first-wins, 10,001-entry archive, >128 MiB declared total, >16 MiB entry skip, >4 MiB cover drop, missing chapter reindex, missing manifest id, missing image, 20k block cap, 2M char cap, unknown compression, encrypted ZIP entries |
| `EpubDocumentLifecycleTest` | 6 | bytes before/after `close`, idempotent close, independent copies, no-archive documents, `plainText` stability |
| `EpubPathsTest` | 7 | normalisation, traversal/absolute/scheme rejection, relative resolution, percent decoding, fragments |
| `BookBlocksTest` | 5 | `plainText` shape (newline joins, list/code/quote/image-alt inclusion, rules omitted) |

`grep`-verified: no `TODO`/`FIXME`/`println`/`printStackTrace`/`Log`/`System.out` and no
`android.*`/`androidx.*` imports in the `book/` package; no filesystem APIs in the engine.
