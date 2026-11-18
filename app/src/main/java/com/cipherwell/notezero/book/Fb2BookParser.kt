package com.cipherwell.notezero.book

import java.io.ByteArrayInputStream
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/**
 * FictionBook 2 (FB2) parser, streaming and bounded.
 *
 * Pipeline: hardened SAX (no external entities/DTD fetching; internal DTD subsets rejected) →
 * `description/title-info` metadata → `body` sections as chapters → `section/title` headings →
 * inline content (`p`, `emphasis`, `strong`, `code`, `subtitle`, `poem/stanza/v`, `epigraph`,
 * `cite`, `empty-line`, `text-author`, `a`, `image`) → base64 `<binary>` images + cover.
 *
 * Mapping rules:
 * - every top-level `<section>` of every `<body>` is a chapter; nested sections are heading
 *   blocks inside the enclosing chapter (the chapter title comes from the first top-level
 *   section title, or the body title for content outside sections);
 * - `section/title` becomes `Heading(1..6)` and feeds the TOC (nested titles keep their depth);
 * - `p` becomes `Paragraph` (inside `epigraph`/`cite` it becomes `Quote`); `emphasis`/`strong`/
 *   `code`/`a` set the matching [BookInline] flags; `subtitle` becomes a `Heading`;
 * - a `poem` `stanza` becomes one `Quote` whose line breaks are preserved inside the runs; a
 *   `text-author` becomes an italic `Paragraph`; `empty-line` becomes a `Rule`;
 * - `<binary id=... content-type=image/...>` payloads are base64-decoded (strict, bounded) and
 *   served by [BookContent.imageBytes]; `<image l:href="#id">` blocks reference them, and the
 *   `<coverpage>` image becomes [BookContent.cover].
 *
 * Caps: [BookLimits.MAX_FB2_XML_BYTES] input, [BookLimits.MAX_REFOWABLE_CHAPTERS] chapters,
 * [BookLimits.MAX_BLOCKS_PER_CHAPTER]/[BookLimits.MAX_TEXT_PER_CHAPTER] per chapter,
 * [BookLimits.MAX_TOTAL_BLOCKS]/[BookLimits.MAX_TOTAL_TEXT_CHARS] per book,
 * [BookLimits.MAX_FB2_IMAGES] images of at most [BookLimits.MAX_FB2_IMAGE_BYTES] each and
 * [BookLimits.MAX_FB2_IMAGE_TOTAL_BYTES] in total.
 *
 * Failure policy: malformed XML, a non-FictionBook root, XXE/entity-bomb input (internal DTD
 * subset or unresolvable external entity), a missing `body`, oversized input or any unexpected
 * throwable returns null. The parser never throws for any input.
 */
object Fb2BookParser {

    fun parse(bytes: ByteArray, fallbackTitle: String): BookContent? {
        return try {
            if (bytes.isEmpty()) return null
            if (bytes.size > BookLimits.MAX_FB2_XML_BYTES) return null
            // Same hardening as the EPUB engine: UTF-16 would hide markup from the byte-level
            // DTD guard, and internal DTD subsets are the only route to entity expansion.
            if (SecureXml.isUtf16(bytes)) return null
            if (SecureXml.containsInternalDtdSubset(bytes)) return null
            val reader = SecureXml.newSaxReader() ?: return null
            val handler = Fb2Handler(fallbackTitle)
            reader.contentHandler = handler
            reader.parse(InputSource(ByteArrayInputStream(bytes)))
            handler.finish()
        } catch (_: Throwable) {
            null
        }
    }
}

private const val FB2_MAX_TOC_ENTRIES = 10_000
private const val FB2_MAX_TOC_DEPTH = 16
private const val FB2_MAX_TITLE_CHARS = 500
private const val FB2_MAX_AUTHOR_CHARS = 200
private const val FB2_MAX_LANGUAGE_CHARS = 64
private const val FB2_MAX_ENTRY_CHARS = 256
private const val FB2_MAX_ALT_CHARS = 200

private class Fb2Handler(private val fallbackTitle: String) : DefaultHandler() {

    private class Draft {
        var title: String? = null
        val blocks = ArrayList<BookBlock>()
        val anchors = ArrayList<Anchor>()
        var chars: Int = 0
    }

    private class Anchor(val title: String, val depth: Int)

    private enum class BlockKind { NONE, PARAGRAPH, QUOTE, HEADING }

    private enum class CaptureField { TITLE, LANGUAGE, FIRST_NAME, MIDDLE_NAME, LAST_NAME, NICKNAME }

    private val path = ArrayList<String>()

    private var rootSeen = false
    private var bodyDepth = 0
    private var bodiesSeen = 0
    private var sectionDepth = 0

    private val chapters = ArrayList<BookChapter>()
    private val toc = ArrayList<BookTocEntry>()
    private var draft: Draft? = null
    private var totalBlocks = 0
    private var totalChars = 0
    private var anchorTotal = 0
    private var stopped = false

    // description/title-info
    private var bookTitle: String? = null
    private var languageRaw: String? = null
    private var firstName: String? = null
    private var middleName: String? = null
    private var lastName: String? = null
    private var nickname: String? = null
    private var authorCount = 0
    private var coverId: String? = null
    private var capture: StringBuilder? = null
    private var captureField: CaptureField? = null
    private var captureElement: String? = null

    // <binary> images
    private val images = LinkedHashMap<String, ByteArray>()
    private var totalImageBytes = 0
    private var binaryBuffer: StringBuilder? = null
    private var binaryOversized = false
    private var binaryId: String? = null
    private var binaryContentType: String? = null

    // inline/block state
    private var block: InlineAccumulator? = null
    private var blockKind: BlockKind = BlockKind.NONE
    private var blockLevel = 0
    private var blockItalic = false
    private var blockStructuralTitle = false
    private var titleOpen = false
    private var line: InlineAccumulator? = null
    private var stanzaLines: ArrayList<List<BookInline>>? = null
    private var poemDepth = 0
    private var epigraphDepth = 0
    private var citeDepth = 0
    private var boldDepth = 0
    private var italicDepth = 0
    private var codeDepth = 0
    private var linkDepth = 0

    // ---- SAX events ----------------------------------------------------------

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
        val name = localNameOf(localName, qName)
        if (path.isEmpty() && name != "FictionBook") throw SAXException("not an FB2 document")
        path.add(name)
        when (name) {
            "FictionBook" -> if (path.size == 1) rootSeen = true
            "body" -> {
                bodyDepth++
                if (bodyDepth == 1) bodiesSeen++
            }
            "binary" -> if (path.size == 2 && bodyDepth == 0) startBinary(attributes)
            else -> {
                if (bodyDepth > 0) {
                    handleContentStart(name, attributes)
                } else {
                    handleMetadataStart(name, attributes)
                }
            }
        }
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        val name = localNameOf(localName, qName)
        if (path.isNotEmpty()) path.removeAt(path.size - 1)
        when (name) {
            "FictionBook" -> Unit
            "body" -> {
                flushDraft()
                if (bodyDepth > 0) bodyDepth--
                if (bodyDepth == 0) sectionDepth = 0
            }
            "binary" -> if (bodyDepth == 0) finishBinary()
            else -> {
                if (bodyDepth > 0) {
                    handleContentEnd(name)
                } else {
                    handleMetadataEnd(name)
                }
            }
        }
    }

    /**
     * The hardened reader never loads external DTDs/entities, so a reference to an entity
     * declared outside the document is reported as skipped. Such a document is treated as
     * malformed: silently dropping text would misrepresent the book (and be an XXE signal).
     */
    override fun skippedEntity(name: String?) {
        throw SAXException("external entity is not supported")
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        val capture = capture
        if (capture != null) {
            appendCaptured(capture, ch, start, length)
            return
        }
        val binary = binaryBuffer
        if (binary != null) {
            appendBinary(binary, ch, start, length)
            return
        }
        if (bodyDepth <= 0) return
        val accumulator = line ?: block ?: return
        accumulator.append(
            chars = ch,
            start = start,
            end = start + length,
            bold = boldDepth > 0,
            italic = italicDepth > 0 || blockItalic,
            code = codeDepth > 0,
            link = linkDepth > 0,
        )
    }

    // ---- metadata (description/title-info) ----------------------------------

    private fun handleMetadataStart(name: String, attributes: Attributes) {
        if (path.size < 2 || path[1] != "description") return
        when {
            path.size == 4 && path[2] == "title-info" -> when (name) {
                "book-title" -> beginCapture(CaptureField.TITLE, name)
                "lang" -> beginCapture(CaptureField.LANGUAGE, name)
                "author" -> authorCount++
            }
            path.size == 5 && path[2] == "title-info" && path[3] == "author" && authorCount == 1 -> when (name) {
                "first-name" -> beginCapture(CaptureField.FIRST_NAME, name)
                "middle-name" -> beginCapture(CaptureField.MIDDLE_NAME, name)
                "last-name" -> beginCapture(CaptureField.LAST_NAME, name)
                "nickname" -> beginCapture(CaptureField.NICKNAME, name)
            }
            path.size == 5 && path[2] == "title-info" && path[3] == "coverpage" && name == "image" -> {
                if (coverId == null) {
                    coverId = imageHref(attributes)
                        ?.removePrefix("#")
                        ?.trim()
                        ?.take(FB2_MAX_ENTRY_CHARS)
                        ?.ifEmpty { null }
                }
            }
        }
    }

    private fun handleMetadataEnd(name: String) {
        if (capture != null && captureElement == name) finishCapture()
    }

    private fun beginCapture(field: CaptureField, element: String) {
        if (capture != null) return
        capture = StringBuilder()
        captureField = field
        captureElement = element
    }

    private fun appendCaptured(builder: StringBuilder, ch: CharArray, start: Int, length: Int) {
        val field = captureField ?: return
        val limit = when (field) {
            CaptureField.TITLE -> FB2_MAX_TITLE_CHARS
            CaptureField.LANGUAGE -> FB2_MAX_LANGUAGE_CHARS
            else -> FB2_MAX_AUTHOR_CHARS
        }
        val room = limit - builder.length
        if (room > 0) builder.append(ch, start, minOf(length, room))
    }

    private fun finishCapture() {
        val builder = capture ?: return
        capture = null
        captureElement = null
        val field = captureField ?: return
        captureField = null
        val raw = builder.toString()
        when (field) {
            CaptureField.TITLE -> if (bookTitle == null) bookTitle = raw
            CaptureField.LANGUAGE -> if (languageRaw == null) languageRaw = raw
            CaptureField.FIRST_NAME -> if (firstName == null) firstName = raw
            CaptureField.MIDDLE_NAME -> if (middleName == null) middleName = raw
            CaptureField.LAST_NAME -> if (lastName == null) lastName = raw
            CaptureField.NICKNAME -> if (nickname == null) nickname = raw
        }
    }

    // ---- body content -------------------------------------------------------

    private fun handleContentStart(name: String, attributes: Attributes) {
        when (name) {
            "section" -> {
                if (sectionDepth == 0) {
                    sectionDepth = 1
                    beginChapter()
                } else {
                    sectionDepth++
                }
            }
            "title" -> if (block == null && !titleOpen) {
                val structural = poemDepth == 0 && epigraphDepth == 0 && citeDepth == 0
                titleOpen = true
                beginBlock(BlockKind.HEADING, if (sectionDepth >= 1) minOf(sectionDepth, 6) else 1)
                blockStructuralTitle = structural
            }
            "subtitle" -> if (block == null) beginBlock(BlockKind.HEADING, minOf(sectionDepth + 1, 6))
            "p" -> if (block == null) {
                beginBlock(
                    if (epigraphDepth > 0 || citeDepth > 0) BlockKind.QUOTE else BlockKind.PARAGRAPH,
                    0,
                )
            }
            "v" -> if (block == null && line == null) {
                if (stanzaLines != null) {
                    line = InlineAccumulator(allowedCharsForBlock())
                } else {
                    beginBlock(BlockKind.PARAGRAPH, 0)
                }
            }
            "stanza" -> if (stanzaLines == null) stanzaLines = ArrayList()
            "poem" -> poemDepth++
            "epigraph" -> epigraphDepth++
            "cite" -> citeDepth++
            "text-author" -> if (block == null) beginBlock(BlockKind.PARAGRAPH, 0, italic = true)
            "image" -> handleImage(attributes)
            "empty-line" -> if (block == null && line == null) emitBlock(BookBlock.Rule, 0)
            "emphasis" -> italicDepth++
            "strong" -> boldDepth++
            "code" -> codeDepth++
            "a" -> linkDepth++
            "tr" -> if (block == null) beginBlock(BlockKind.PARAGRAPH, 0)
            "td", "th" -> if (block == null && line == null) beginBlock(BlockKind.PARAGRAPH, 0)
        }
    }

    private fun handleContentEnd(name: String) {
        when (name) {
            "section" -> {
                if (sectionDepth == 1) flushDraft()
                if (sectionDepth > 0) sectionDepth--
            }
            "title" -> if (titleOpen) {
                titleOpen = false
                finishTitle()
            }
            // Titles carry their text directly or in nested <p> elements; those must not
            // finish (or become) separate blocks.
            "subtitle", "p", "text-author" -> if (!titleOpen) finishBlock()
            "v" -> finishLine()
            "stanza" -> finishStanza()
            "poem" -> if (poemDepth > 0) poemDepth--
            "epigraph" -> if (epigraphDepth > 0) epigraphDepth--
            "cite" -> if (citeDepth > 0) citeDepth--
            "emphasis" -> if (italicDepth > 0) italicDepth--
            "strong" -> if (boldDepth > 0) boldDepth--
            "code" -> if (codeDepth > 0) codeDepth--
            "a" -> if (linkDepth > 0) linkDepth--
            "tr" -> if (block != null && !titleOpen) finishBlock()
        }
    }

    // ---- blocks -------------------------------------------------------------

    private fun beginBlock(kind: BlockKind, level: Int, italic: Boolean = false) {
        if (stopped || block != null) return
        blockKind = kind
        blockLevel = level
        blockItalic = italic
        block = InlineAccumulator(allowedCharsForBlock())
    }

    private fun finishBlock() {
        val accumulator = block ?: return
        block = null
        blockItalic = false
        val kind = blockKind
        val level = blockLevel
        blockKind = BlockKind.NONE
        blockLevel = 0
        if (accumulator.isEmpty()) return
        val runs = accumulator.build()
        if (runs.isEmpty()) return
        val textChars = inlineRunsText(runs).length
        val result = when (kind) {
            BlockKind.HEADING -> BookBlock.Heading(level.coerceIn(1, 6), runs)
            BlockKind.QUOTE -> BookBlock.Quote(runs)
            else -> BookBlock.Paragraph(runs)
        }
        emitBlock(result, textChars)
    }

    private fun finishTitle() {
        val accumulator = block ?: return
        val structural = blockStructuralTitle
        block = null
        blockItalic = false
        blockStructuralTitle = false
        val level = blockLevel
        blockKind = BlockKind.NONE
        blockLevel = 0
        if (accumulator.isEmpty()) return
        val runs = accumulator.build()
        if (runs.isEmpty()) return
        val text = BookText.collapse(inlineRunsText(runs))
        emitBlock(BookBlock.Heading(level.coerceIn(1, 6), runs), text.length)
        if (!structural || text.isBlank()) return
        val title = BookText.truncate(text, BookLimits.MAX_DERIVED_TITLE_CHARS)
        if (sectionDepth <= 1) {
            val current = ensureDraft() ?: return
            if (current.title == null) current.title = title
            addAnchor(title, 0)
        } else {
            addAnchor(title, sectionDepth - 1)
        }
    }

    private fun finishLine() {
        val accumulator = line ?: return
        line = null
        val lines = stanzaLines ?: return
        if (accumulator.isEmpty()) return
        val runs = accumulator.build()
        if (runs.isNotEmpty()) lines.add(runs)
    }

    private fun finishStanza() {
        val lines = stanzaLines ?: return
        stanzaLines = null
        if (lines.isEmpty()) return
        val runs = ArrayList<BookInline>()
        var textChars = 0
        for ((index, lineRuns) in lines.withIndex()) {
            if (index > 0) runs.add(BookInline("\n"))
            runs.addAll(lineRuns)
            textChars += inlineRunsText(lineRuns).length
        }
        if (runs.isEmpty()) return
        emitBlock(BookBlock.Quote(runs), textChars)
    }

    private fun handleImage(attributes: Attributes) {
        val alt = attribute(attributes, "alt")?.trim()?.take(FB2_MAX_ALT_CHARS) ?: ""
        if (block != null || line != null) {
            // Inline images cannot become structural blocks in the frozen model: keep their
            // alt text (if any) so the sentence stays readable and searchable.
            if (alt.isNotEmpty()) {
                val accumulator = line ?: block ?: return
                accumulator.append(
                    text = alt,
                    bold = boldDepth > 0,
                    italic = italicDepth > 0 || blockItalic,
                    code = codeDepth > 0,
                    link = linkDepth > 0,
                )
            }
            return
        }
        val href = imageHref(attributes)?.removePrefix("#")?.trim()?.take(FB2_MAX_ENTRY_CHARS) ?: return
        if (href.isEmpty()) return
        val current = ensureDraft() ?: return
        if (!hasBlockCapacity(current)) return
        emitBlock(BookBlock.Image(entry = href, alt = alt), 0)
    }

    // ---- chapters / budgets -------------------------------------------------

    private fun beginChapter() {
        flushDraft()
        if (stopped) return
        ensureDraft()
    }

    private fun ensureDraft(): Draft? {
        draft?.let { return it }
        if (stopped) return null
        if (chapters.size >= BookLimits.MAX_REFOWABLE_CHAPTERS) {
            stopped = true
            return null
        }
        val created = Draft()
        draft = created
        return created
    }

    private fun flushDraft() {
        val current = draft ?: return
        draft = null
        if (current.blocks.isEmpty()) return
        val index = chapters.size
        if (index >= BookLimits.MAX_REFOWABLE_CHAPTERS) {
            stopped = true
            return
        }
        val title = current.title?.let { BookText.truncate(BookText.collapse(it), BookLimits.MAX_DERIVED_TITLE_CHARS) }
        chapters.add(
            BookChapter(
                index = index,
                title = title,
                href = "fb2#section-${index + 1}",
                blocks = current.blocks.toList(),
            ),
        )
        for (anchor in current.anchors) {
            if (toc.size >= FB2_MAX_TOC_ENTRIES) break
            toc.add(BookTocEntry(anchor.title, index, anchor.depth))
        }
    }

    private fun addAnchor(title: String, depth: Int) {
        if (stopped || anchorTotal >= FB2_MAX_TOC_ENTRIES) return
        val current = ensureDraft() ?: return
        current.anchors.add(Anchor(BookText.truncate(title, BookLimits.MAX_DERIVED_TITLE_CHARS), depth.coerceIn(0, FB2_MAX_TOC_DEPTH)))
        anchorTotal++
    }

    private fun allowedCharsForBlock(): Int {
        val current = ensureDraft() ?: return 0
        if (!hasBlockCapacity(current)) return 0
        val chapterRemaining = BookLimits.MAX_TEXT_PER_CHAPTER - current.chars
        val bookRemaining = BookLimits.MAX_TOTAL_TEXT_CHARS - totalChars
        return minOf(chapterRemaining, bookRemaining).coerceAtLeast(0)
    }

    private fun hasBlockCapacity(current: Draft): Boolean =
        current.blocks.size < BookLimits.MAX_BLOCKS_PER_CHAPTER && totalBlocks < BookLimits.MAX_TOTAL_BLOCKS

    private fun emitBlock(block: BookBlock, textChars: Int) {
        if (stopped) return
        val current = ensureDraft() ?: return
        if (!hasBlockCapacity(current)) return
        if (textChars > 0 && current.chars >= BookLimits.MAX_TEXT_PER_CHAPTER) return
        current.blocks.add(block)
        current.chars += textChars
        totalBlocks++
        totalChars += textChars
        if (totalBlocks >= BookLimits.MAX_TOTAL_BLOCKS || totalChars >= BookLimits.MAX_TOTAL_TEXT_CHARS) {
            stopped = true
        }
    }

    // ---- <binary> images ----------------------------------------------------

    private fun startBinary(attributes: Attributes) {
        binaryBuffer = StringBuilder()
        binaryOversized = false
        binaryId = attribute(attributes, "id")?.trim()?.take(FB2_MAX_ENTRY_CHARS)?.ifEmpty { null }
        binaryContentType = attribute(attributes, "content-type")?.trim()?.lowercase()
    }

    private fun appendBinary(builder: StringBuilder, ch: CharArray, start: Int, length: Int) {
        if (binaryOversized) return
        var index = start
        val end = start + length
        while (index < end) {
            val value = ch[index]
            if (isXmlWhitespace(value)) {
                index++
                continue
            }
            if (builder.length >= BookLimits.MAX_FB2_BASE64_CHARS) {
                binaryOversized = true
                return
            }
            builder.append(value)
            index++
        }
    }

    private fun finishBinary() {
        val builder = binaryBuffer ?: return
        val id = binaryId
        val contentType = binaryContentType
        val oversized = binaryOversized
        binaryBuffer = null
        binaryId = null
        binaryContentType = null
        binaryOversized = false
        if (oversized || id == null) return
        if (contentType != null && !contentType.startsWith("image/")) return
        if (images.containsKey(id) || images.size >= BookLimits.MAX_FB2_IMAGES) return
        val decoded = Base64Codec.decode(builder, BookLimits.MAX_FB2_IMAGE_BYTES) ?: return
        if (totalImageBytes + decoded.size > BookLimits.MAX_FB2_IMAGE_TOTAL_BYTES) return
        images[id] = decoded
        totalImageBytes += decoded.size
    }

    // ---- result -------------------------------------------------------------

    fun finish(): BookContent? {
        if (!rootSeen || bodiesSeen == 0) return null
        flushDraft()
        val rawTitle = bookTitle
        val title = rawTitle?.let { BookText.collapse(it) }?.takeIf { it.isNotBlank() }
            ?.let { BookText.truncate(it, FB2_MAX_TITLE_CHARS) }
            ?: BookText.fallbackTitle(fallbackTitle)
        val language = languageRaw?.let { BookText.collapse(it) }?.takeIf { it.isNotBlank() }
            ?.let { BookText.truncate(it, FB2_MAX_LANGUAGE_CHARS) }
        val resolvedToc = if (toc.isEmpty()) {
            chapters.map { chapter ->
                BookTocEntry(
                    title = chapter.title ?: "Chapter ${chapter.index + 1}",
                    chapterIndex = chapter.index,
                    depth = 0,
                )
            }
        } else {
            toc.toList()
        }
        val cover = coverId?.let { images[it] }?.takeIf { it.size <= BookLimits.MAX_COVER_BYTES }
        return InMemoryDocument(
            title = title,
            author = authorText(),
            language = language,
            chapters = chapters.toList(),
            toc = resolvedToc,
            coverBytes = cover,
            images = images,
        )
    }

    private fun authorText(): String? {
        val parts = ArrayList<String>(3)
        firstName?.let { parts.add(BookText.collapse(it)) }
        middleName?.let { parts.add(BookText.collapse(it)) }
        lastName?.let { parts.add(BookText.collapse(it)) }
        val joined = parts.filter { it.isNotBlank() }.joinToString(" ")
        val author = joined.ifBlank { nickname?.let { BookText.collapse(it) } ?: "" }
        if (author.isBlank()) return null
        return BookText.truncate(author, FB2_MAX_AUTHOR_CHARS)
    }

    // ---- helpers ------------------------------------------------------------

    private fun localNameOf(localName: String?, qName: String?): String {
        val local = localName
        if (!local.isNullOrEmpty()) return local
        val qualified = qName ?: return ""
        return qualified.substringAfter(':')
    }

    private fun attribute(attributes: Attributes, name: String): String? {
        for (index in 0 until attributes.length) {
            if (attributes.getLocalName(index) == name || attributes.getQName(index) == name) {
                return attributes.getValue(index)
            }
        }
        return null
    }

    private fun imageHref(attributes: Attributes): String? {
        for (index in 0 until attributes.length) {
            val local = attributes.getLocalName(index)
            val qualified = attributes.getQName(index)
            if (local == "href" || qualified == "href" || qualified.endsWith(":href")) {
                return attributes.getValue(index)
            }
        }
        return null
    }

    private fun isXmlWhitespace(ch: Char): Boolean =
        ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r'
}
