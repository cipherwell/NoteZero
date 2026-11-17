package com.cipherwell.notezero.book

import java.io.ByteArrayInputStream
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/**
 * Streaming XHTML -> [BookBlock] converter.
 *
 * SAX (not DOM) is used deliberately: chapter documents are untrusted and may be up to the
 * 16 MiB entry cap, and the streaming walk never materialises the markup tree, so element
 * nesting and text are bounded by [BookLimits.MAX_ELEMENT_DEPTH] /
 * [BookLimits.MAX_TEXT_PER_CHAPTER] instead of by the XML document size.
 *
 * Supported subset: `p, div, h1..h6, ul/ol/li, blockquote, pre, hr, table` (rows rendered as
 * paragraphs whose cells are joined with " · "), `img`, `br`, `em/i, strong/b, code, a,
 * sup/sub` and transparent containers (`section`, `article`, …). Unknown elements keep their
 * text. Formatting is reduced to [BookInline] flags; anchors keep their label with
 * `link = true` (hrefs are not stored). Scripts/styles/head/svg are dropped entirely.
 *
 * Truncation is clean and deterministic: at most [BookLimits.MAX_BLOCKS_PER_CHAPTER] blocks
 * and [BookLimits.MAX_TEXT_PER_CHAPTER] characters are kept; anything after the cap is
 * ignored rather than half-parsed.
 */
internal object ChapterBlocks {

    /**
     * Parses one XHTML chapter. Returns null when the document is not well-formed XML (a
     * malformed content document fails the whole parse; callers must not return a partial
     * book). Never throws for malformed input.
     */
    fun parse(bytes: ByteArray, chapterPath: String): List<BookBlock>? {
        if (bytes.isEmpty()) return null
        if (SecureXml.isUtf16(bytes)) return null
        if (SecureXml.containsInternalDtdSubset(bytes)) return null
        val payload = SecureXml.replaceNamedEntities(bytes)
        val reader = SecureXml.newSaxReader() ?: return null
        val handler = Handler(chapterPath)
        return try {
            reader.contentHandler = handler
            reader.parse(InputSource(ByteArrayInputStream(payload)))
            handler.finish()
        } catch (_: SAXException) {
            null
        } catch (_: java.io.IOException) {
            null
        }
    }

    private val FORMAT_PLAIN = InlineFormat(false, false, false, false)
    private data class InlineFormat(
        val bold: Boolean,
        val italic: Boolean,
        val code: Boolean,
        val link: Boolean,
    )

    private fun isXmlWhitespace(ch: Char): Boolean =
        ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r'

    private class TextBudget {
        private var used = 0
        var truncated = false
            private set

        fun take(length: Int): Int {
            if (length <= 0) return 0
            val remaining = BookLimits.MAX_TEXT_PER_CHAPTER - used
            if (remaining <= 0) {
                truncated = true
                return 0
            }
            if (length <= remaining) {
                used += length
                return length
            }
            used = BookLimits.MAX_TEXT_PER_CHAPTER
            truncated = true
            return remaining
        }
    }

    private class InlineBuilder(private val budget: TextBudget) {
        private class Run(val format: InlineFormat) {
            val text = StringBuilder()
        }

        private val runs = ArrayList<Run>()
        private var lastWasSpace = true

        fun append(text: String, format: InlineFormat) {
            var index = 0
            val length = text.length
            while (index < length) {
                val ch = text[index]
                if (isXmlWhitespace(ch)) {
                    if (!lastWasSpace) {
                        if (budget.take(1) == 0) return
                        appendCharInternal(' ', format)
                        lastWasSpace = true
                    }
                    index++
                } else {
                    if (budget.take(1) == 0) return
                    appendCharInternal(ch, format)
                    lastWasSpace = false
                    index++
                }
            }
        }

        private fun appendCharInternal(ch: Char, format: InlineFormat) {
            val run = if (runs.isNotEmpty() && runs[runs.size - 1].format == format) {
                runs[runs.size - 1]
            } else {
                Run(format).also { runs.add(it) }
            }
            run.text.append(ch)
        }

        fun isEmpty(): Boolean = runs.all { it.text.isBlank() }

        fun build(): List<BookInline> {
            if (runs.isEmpty()) return emptyList()
            for (index in runs.indices.reversed()) {
                val run = runs[index]
                while (run.text.isNotEmpty() && run.text[run.text.length - 1] == ' ') {
                    run.text.deleteCharAt(run.text.length - 1)
                }
                if (run.text.isEmpty()) runs.removeAt(index) else break
            }
            if (runs.isEmpty()) return emptyList()
            val out = ArrayList<BookInline>(runs.size)
            for (run in runs) {
                if (run.text.isEmpty()) continue
                val format = run.format
                out.add(
                    BookInline(
                        text = run.text.toString(),
                        bold = format.bold,
                        italic = format.italic,
                        code = format.code,
                        link = format.link,
                    ),
                )
            }
            return out
        }

        fun reset() {
            runs.clear()
            lastWasSpace = true
        }
    }

    private enum class Kind {
        ROOT,
        DIV,
        PARAGRAPH,
        HEADING,
        QUOTE,
        PRE,
        LIST,
        ITEM,
        TABLE,
        ROW,
        CELL,
    }

    private class ListState(val ordered: Boolean) {
        val items = ArrayList<BookListItem>()
        var flattenSink: InlineBuilder? = null
    }

    private class Frame(val kind: Kind, val level: Int = 0, budget: TextBudget? = null) {
        private val textKind = kind == Kind.ROOT || kind == Kind.DIV || kind == Kind.PARAGRAPH ||
            kind == Kind.HEADING || kind == Kind.QUOTE || kind == Kind.ITEM || kind == Kind.CELL
        val builder: InlineBuilder? = if (textKind) InlineBuilder(budget ?: TextBudget()) else null
        val blocks: ArrayList<BookBlock>? = if (kind == Kind.ROOT || kind == Kind.DIV) ArrayList() else null
        var listState: ListState? = null
        var listDepth: Int = 0
        var isOutermostList: Boolean = false
        var itemDepth: Int = 0
        var preText: StringBuilder? = null
        var preFlattenSink: InlineBuilder? = null
        val rowCells: ArrayList<List<BookInline>>? = if (kind == Kind.ROW) ArrayList() else null
        val tableRows: ArrayList<List<BookInline>>? = if (kind == Kind.TABLE) ArrayList() else null
        var tableFlattenSink: InlineBuilder? = null
    }

    private class OpenBlock(
        val tag: String,
        val frame: Frame?,
        val flatSink: InlineBuilder?,
        val endSeparator: Boolean = false,
    )

    private class Handler(private val chapterPath: String) : DefaultHandler() {

        private val textBudget = TextBudget()
        private val frames = ArrayList<Frame>()
        private val openBlocks = ArrayList<OpenBlock>()

        private var boldDepth = 0
        private var italicDepth = 0
        private var codeDepth = 0
        private var linkDepth = 0

        private var ignoreDepth = 0
        private var suppressedDepth = 0
        private var preChildDepth = 0

        init {
            frames.add(newFrame(Kind.ROOT))
        }

        private fun newFrame(kind: Kind, level: Int = 0): Frame = Frame(kind, level, textBudget)

        // ---- SAX events ------------------------------------------------------

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            if (ignoreDepth > 0) {
                ignoreDepth++
                return
            }
            if (suppressedDepth > 0) {
                suppressedDepth++
                return
            }
            val tag = tagName(localName, qName)
            if (tag in IGNORED_ELEMENTS) {
                ignoreDepth = 1
                return
            }
            if (frames.size + openBlocks.size >= BookLimits.MAX_ELEMENT_DEPTH * 2) {
                suppressedDepth = 1
                return
            }
            if (frames[frames.size - 1].kind == Kind.PRE) {
                // Inside <pre> every element is transparent; only raw text is kept.
                preChildDepth++
                return
            }

            when (tag) {
                "b", "strong" -> {
                    boldDepth++
                    return
                }
                "i", "em" -> {
                    italicDepth++
                    return
                }
                "code" -> {
                    codeDepth++
                    return
                }
                "a" -> {
                    linkDepth++
                    return
                }
                "br" -> {
                    appendSeparator()
                    return
                }
                "img" -> {
                    handleImage(attributes)
                    return
                }
                "hr" -> {
                    appendRule()
                    return
                }
            }

            if (tag !in BLOCK_ELEMENTS) return

            when (tag) {
                "p" -> openTextBlock(tag, Kind.PARAGRAPH)
                "h1" -> openHeading(tag, 1)
                "h2" -> openHeading(tag, 2)
                "h3" -> openHeading(tag, 3)
                "h4" -> openHeading(tag, 4)
                "h5" -> openHeading(tag, 5)
                "h6" -> openHeading(tag, 6)
                "blockquote" -> openQuote(tag)
                "pre" -> openPre(tag)
                "ul" -> openList(tag, ordered = false)
                "ol" -> openList(tag, ordered = true)
                "li" -> openItem(tag)
                "table" -> openTable(tag)
                "tr" -> openRow(tag)
                "td", "th" -> openCell(tag)
                "thead", "tbody", "tfoot", "colgroup", "caption" -> openTransparent(tag)
                else -> openContainer(tag)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            if (ignoreDepth > 0) {
                ignoreDepth--
                return
            }
            if (suppressedDepth > 0) {
                suppressedDepth--
                return
            }
            val tag = tagName(localName, qName)
            if (frames[frames.size - 1].kind == Kind.PRE) {
                if (preChildDepth > 0) {
                    preChildDepth--
                    return
                }
            }

            when (tag) {
                "b", "strong" -> {
                    if (boldDepth > 0) boldDepth--
                    return
                }
                "i", "em" -> {
                    if (italicDepth > 0) italicDepth--
                    return
                }
                "code" -> {
                    if (codeDepth > 0) codeDepth--
                    return
                }
                "a" -> {
                    if (linkDepth > 0) linkDepth--
                    return
                }
                "br", "img", "hr" -> return
            }

            if (tag !in BLOCK_ELEMENTS) return
            val open = openBlocks.removeLastOrNull() ?: return
            if (open.tag != tag) return
            val frame = open.frame
            if (frame == null) {
                if (open.endSeparator && open.flatSink != null) appendSeparatorTo(open.flatSink, FORMAT_PLAIN)
                return
            }
            if (frames.isNotEmpty() && frames[frames.size - 1] === frame) {
                frames.removeAt(frames.size - 1)
            } else {
                frames.remove(frame)
            }
            finalizeFrame(frame)
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (ignoreDepth > 0 || suppressedDepth > 0 || length <= 0) return
            val top = frames[frames.size - 1]
            if (top.kind == Kind.PRE) {
                val preText = top.preText ?: return
                val allowed = textBudget.take(length)
                if (allowed > 0) preText.append(ch, start, allowed)
                return
            }
            val target = textTarget() ?: return
            target.append(String(ch, start, length), currentFormat())
        }

        fun finish(): List<BookBlock> {
            val root = frames.firstOrNull() ?: return emptyList()
            flushPending(root)
            return root.blocks?.toList() ?: emptyList()
        }

        // ---- text helpers ----------------------------------------------------

        private fun tagName(localName: String?, qName: String?): String {
            val raw = if (!localName.isNullOrEmpty()) localName else qName ?: ""
            return raw.substringAfter(':').lowercase()
        }

        private fun currentFormat(): InlineFormat = InlineFormat(
            bold = boldDepth > 0,
            italic = italicDepth > 0,
            code = codeDepth > 0,
            link = linkDepth > 0,
        )

        private fun textTarget(): InlineBuilder? {
            for (index in frames.indices.reversed()) {
                when (frames[index].kind) {
                    Kind.ROOT, Kind.DIV, Kind.PARAGRAPH, Kind.HEADING, Kind.QUOTE, Kind.ITEM, Kind.CELL ->
                        return frames[index].builder
                    else -> Unit
                }
            }
            return null
        }

        private fun appendSeparator() {
            val target = textTarget() ?: return
            appendSeparatorTo(target, currentFormat())
        }

        private fun appendSeparatorTo(target: InlineBuilder, format: InlineFormat) {
            target.append(" ", format)
        }

        private fun flattenTarget(): InlineBuilder? {
            for (index in frames.indices.reversed()) {
                when (frames[index].kind) {
                    Kind.ITEM, Kind.QUOTE, Kind.CELL, Kind.PARAGRAPH, Kind.HEADING ->
                        return frames[index].builder
                    Kind.ROOT, Kind.DIV -> return null
                    else -> Unit
                }
            }
            return null
        }

        private fun nearestContainer(): Frame? {
            for (index in frames.indices.reversed()) {
                val frame = frames[index]
                if (frame.kind == Kind.ROOT || frame.kind == Kind.DIV) return frame
            }
            return null
        }

        // ---- block openers ---------------------------------------------------

        private fun openTextBlock(tag: String, kind: Kind, level: Int = 0) {
            val flat = flattenTarget()
            if (flat != null) {
                appendSeparatorTo(flat, currentFormat())
                openBlocks.add(OpenBlock(tag, null, flat, endSeparator = true))
                return
            }
            val container = nearestContainer() ?: return
            flushPending(container)
            val frame = newFrame(kind, level)
            frames.add(frame)
            openBlocks.add(OpenBlock(tag, frame, null))
        }

        private fun openHeading(tag: String, level: Int) {
            openTextBlock(tag, Kind.HEADING, level)
        }

        private fun openQuote(tag: String) {
            openTextBlock(tag, Kind.QUOTE)
        }

        private fun openContainer(tag: String) {
            val flat = flattenTarget()
            if (flat != null) {
                appendSeparatorTo(flat, currentFormat())
                openBlocks.add(OpenBlock(tag, null, flat, endSeparator = true))
                return
            }
            val container = nearestContainer() ?: return
            flushPending(container)
            val frame = newFrame(Kind.DIV)
            frames.add(frame)
            openBlocks.add(OpenBlock(tag, frame, null))
        }

        private fun openTransparent(tag: String) {
            openBlocks.add(OpenBlock(tag, null, null))
        }

        private fun openPre(tag: String) {
            val flat = flattenTarget()
            if (flat != null) {
                appendSeparatorTo(flat, currentFormat())
                val frame = newFrame(Kind.PRE).apply {
                    preText = StringBuilder()
                    preFlattenSink = flat
                }
                frames.add(frame)
                openBlocks.add(OpenBlock(tag, frame, null))
                return
            }
            val container = nearestContainer() ?: return
            flushPending(container)
            val frame = newFrame(Kind.PRE).apply { preText = StringBuilder() }
            frames.add(frame)
            openBlocks.add(OpenBlock(tag, frame, null))
        }

        private fun openList(tag: String, ordered: Boolean) {
            val enclosingList = topmostFrame(Kind.LIST)
            if (enclosingList != null) {
                // Nested list inside the current <li>: keep structure, flat item list grows.
                flushCurrentItem()
                val frame = newFrame(Kind.LIST).apply {
                    listState = enclosingList.listState
                    listDepth = minOf(enclosingList.listDepth + 1, BookLimits.MAX_LIST_DEPTH)
                    isOutermostList = false
                }
                frames.add(frame)
                openBlocks.add(OpenBlock(tag, frame, null))
                return
            }
            val flat = flattenTarget()
            if (flat != null) {
                val state = ListState(ordered).apply { flattenSink = flat }
                val frame = newFrame(Kind.LIST).apply {
                    listState = state
                    isOutermostList = false
                }
                frames.add(frame)
                openBlocks.add(OpenBlock(tag, frame, null))
                return
            }
            val container = nearestContainer() ?: return
            flushPending(container)
            val frame = newFrame(Kind.LIST).apply {
                listState = ListState(ordered)
                isOutermostList = true
                listDepth = 0
            }
            frames.add(frame)
            openBlocks.add(OpenBlock(tag, frame, null))
        }

        private fun openItem(tag: String) {
            val listFrame = topmostFrame(Kind.LIST)
            if (listFrame == null) {
                val flat = flattenTarget()
                if (flat != null) {
                    appendSeparatorTo(flat, currentFormat())
                    openBlocks.add(OpenBlock(tag, null, flat, endSeparator = true))
                    return
                }
                val container = nearestContainer() ?: return
                flushPending(container)
                val frame = newFrame(Kind.ITEM).apply { listState = null }
                frames.add(frame)
                openBlocks.add(OpenBlock(tag, frame, null))
                return
            }
            val state = listFrame.listState
            val flattened = state?.flattenSink
            if (flattened != null) {
                appendSeparatorTo(flattened, currentFormat())
                openBlocks.add(OpenBlock(tag, null, flattened, endSeparator = true))
                return
            }
            val frame = newFrame(Kind.ITEM).apply {
                listState = state
                itemDepth = listFrame.listDepth
            }
            frames.add(frame)
            openBlocks.add(OpenBlock(tag, frame, null))
        }

        private fun openTable(tag: String) {
            val flat = flattenTarget()
            val frame = newFrame(Kind.TABLE)
            if (flat != null) {
                frame.tableFlattenSink = flat
            } else {
                nearestContainer()?.let { flushPending(it) }
            }
            frames.add(frame)
            openBlocks.add(OpenBlock(tag, frame, null))
        }

        private fun openRow(tag: String) {
            val table = topmostFrame(Kind.TABLE)
            if (table == null) {
                openBlocks.add(OpenBlock(tag, null, null))
                return
            }
            val frame = newFrame(Kind.ROW)
            frames.add(frame)
            openBlocks.add(OpenBlock(tag, frame, null))
        }

        private fun openCell(tag: String) {
            val row = topmostFrame(Kind.ROW)
            if (row == null) {
                openBlocks.add(OpenBlock(tag, null, null))
                return
            }
            val frame = newFrame(Kind.CELL)
            frames.add(frame)
            openBlocks.add(OpenBlock(tag, frame, null))
        }

        private fun appendRule() {
            val flat = flattenTarget()
            if (flat != null) {
                appendSeparatorTo(flat, currentFormat())
                return
            }
            appendBlock(BookBlock.Rule)
        }

        // ---- blocks ----------------------------------------------------------

        private fun appendBlock(block: BookBlock) {
            val container = nearestContainer() ?: return
            flushPending(container)
            appendBlockTo(container, block)
        }

        private fun appendBlockTo(container: Frame, block: BookBlock) {
            val blocks = container.blocks ?: return
            if (blocks.size >= BookLimits.MAX_BLOCKS_PER_CHAPTER) return
            blocks.add(block)
        }

        private fun flushPending(container: Frame) {
            val builder = container.builder ?: return
            if (builder.isEmpty()) {
                builder.reset()
                return
            }
            val runs = builder.build()
            builder.reset()
            if (runs.isNotEmpty()) appendBlockTo(container, BookBlock.Paragraph(runs))
        }

        private fun flushParagraphFrame(frame: Frame) {
            val builder = frame.builder ?: return
            if (builder.isEmpty()) {
                builder.reset()
                return
            }
            val runs = builder.build()
            builder.reset()
            if (runs.isNotEmpty()) appendBlock(BookBlock.Paragraph(runs))
        }

        private fun flushHeadingFrame(frame: Frame) {
            val builder = frame.builder ?: return
            if (builder.isEmpty()) {
                builder.reset()
                return
            }
            val runs = builder.build()
            builder.reset()
            if (runs.isNotEmpty()) appendBlock(BookBlock.Heading(frame.level, runs))
        }

        private fun handleImage(attributes: Attributes) {
            val source = attribute(attributes, "src")?.trim().orEmpty()
            if (source.isEmpty()) return
            val alt = attribute(attributes, "alt")?.trim().orEmpty()
            val entry = EpubPaths.resolve(chapterPath, source) ?: return
            for (index in frames.indices.reversed()) {
                val frame = frames[index]
                when (frame.kind) {
                    Kind.PRE -> return
                    Kind.ITEM, Kind.CELL, Kind.QUOTE -> {
                        // Inline containers cannot host a block; keep the alt label.
                        if (alt.isNotEmpty()) frame.builder?.append(alt, FORMAT_PLAIN)
                        return
                    }
                    Kind.PARAGRAPH -> {
                        flushParagraphFrame(frame)
                        appendBlock(BookBlock.Image(entry, alt))
                        return
                    }
                    Kind.HEADING -> {
                        flushHeadingFrame(frame)
                        appendBlock(BookBlock.Image(entry, alt))
                        return
                    }
                    Kind.ROOT, Kind.DIV -> {
                        appendBlock(BookBlock.Image(entry, alt))
                        return
                    }
                    Kind.LIST, Kind.TABLE, Kind.ROW -> Unit
                }
            }
        }

        private fun attribute(attributes: Attributes, name: String): String? {
            for (index in 0 until attributes.length) {
                val local = attributes.getLocalName(index)
                val candidate = if (!local.isNullOrEmpty()) local else attributes.getQName(index).substringAfter(':')
                if (candidate.equals(name, ignoreCase = true)) return attributes.getValue(index)
            }
            return null
        }

        private fun topmostFrame(kind: Kind): Frame? {
            for (index in frames.indices.reversed()) {
                if (frames[index].kind == kind) return frames[index]
            }
            return null
        }

        private fun flushCurrentItem() {
            val item = topmostFrame(Kind.ITEM) ?: return
            val builder = item.builder ?: return
            if (builder.isEmpty()) {
                builder.reset()
                return
            }
            val runs = builder.build()
            builder.reset()
            val state = item.listState ?: return
            val flattened = state.flattenSink
            if (flattened != null) {
                appendRunsAsText(flattened, runs)
            } else if (runs.isNotEmpty() && state.items.size < BookLimits.MAX_BLOCKS_PER_CHAPTER) {
                state.items.add(BookListItem(runs, item.itemDepth))
            }
        }

        private fun appendRunsAsText(sink: InlineBuilder, runs: List<BookInline>) {
            if (runs.isEmpty()) return
            val text = StringBuilder()
            for (run in runs) text.append(run.text)
            sink.append(text.toString(), FORMAT_PLAIN)
            sink.append(" ", FORMAT_PLAIN)
        }

        // ---- frame finalisers ------------------------------------------------

        private fun finalizeFrame(frame: Frame) {
            when (frame.kind) {
                Kind.PARAGRAPH -> flushParagraphFrame(frame)
                Kind.HEADING -> flushHeadingFrame(frame)
                Kind.QUOTE -> finalizeQuote(frame)
                Kind.DIV -> finalizeContainer(frame)
                Kind.PRE -> finalizePre(frame)
                Kind.LIST -> finalizeList(frame)
                Kind.ITEM -> finalizeItem(frame)
                Kind.TABLE -> finalizeTable(frame)
                Kind.ROW -> finalizeRow(frame)
                Kind.CELL -> finalizeCell(frame)
                Kind.ROOT -> Unit
            }
        }

        private fun finalizeQuote(frame: Frame) {
            val builder = frame.builder ?: return
            if (builder.isEmpty()) {
                builder.reset()
                return
            }
            val runs = builder.build()
            builder.reset()
            if (runs.isNotEmpty()) appendBlock(BookBlock.Quote(runs))
        }

        private fun finalizeContainer(frame: Frame) {
            flushPending(frame)
            val blocks = frame.blocks ?: return
            if (blocks.isEmpty()) return
            for (block in blocks) appendBlock(block)
        }

        private fun finalizePre(frame: Frame) {
            val text = frame.preText?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return
            val flattenSink = frame.preFlattenSink
            if (flattenSink != null) {
                flattenSink.append(text, FORMAT_PLAIN)
                flattenSink.append(" ", FORMAT_PLAIN)
                return
            }
            appendBlock(BookBlock.Code(text))
        }

        private fun finalizeList(frame: Frame) {
            val state = frame.listState ?: return
            if (!frame.isOutermostList) return
            if (state.items.isEmpty()) return
            appendBlock(BookBlock.ListBlock(state.ordered, state.items.toList()))
        }

        private fun finalizeItem(frame: Frame) {
            val builder = frame.builder ?: return
            if (builder.isEmpty()) {
                builder.reset()
                return
            }
            val runs = builder.build()
            builder.reset()
            if (runs.isEmpty()) return
            val state = frame.listState
            if (state == null) {
                appendBlock(BookBlock.Paragraph(runs))
                return
            }
            val flattenSink = state.flattenSink
            if (flattenSink != null) {
                appendRunsAsText(flattenSink, runs)
                return
            }
            if (state.items.size < BookLimits.MAX_BLOCKS_PER_CHAPTER) {
                state.items.add(BookListItem(runs, frame.itemDepth))
            }
        }

        private fun finalizeTable(frame: Frame) {
            val rows = frame.tableRows ?: return
            if (rows.isEmpty()) return
            val flattenSink = frame.tableFlattenSink
            if (flattenSink != null) {
                for (row in rows) appendRunsAsText(flattenSink, row)
                return
            }
            for (row in rows) appendBlock(BookBlock.Paragraph(row))
        }

        private fun finalizeRow(frame: Frame) {
            val cells = frame.rowCells ?: return
            val table = topmostFrame(Kind.TABLE) ?: return
            val joined = ArrayList<BookInline>()
            for (cell in cells) {
                if (cell.isEmpty()) continue
                if (joined.isNotEmpty()) joined.add(BookInline(SEPARATOR_CELL))
                joined.addAll(cell)
            }
            if (joined.isNotEmpty()) table.tableRows?.add(joined)
        }

        private fun finalizeCell(frame: Frame) {
            val builder = frame.builder ?: return
            val runs = builder.build()
            builder.reset()
            val row = topmostFrame(Kind.ROW) ?: return
            row.rowCells?.add(runs)
        }

        private companion object {
            const val SEPARATOR_CELL = " \u00b7 "

            val IGNORED_ELEMENTS = setOf(
                "script", "style", "head", "title", "meta", "link", "base",
                "noscript", "template", "svg",
            )

            val BLOCK_ELEMENTS = setOf(
                "p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
                "ul", "ol", "li", "blockquote", "pre",
                "table", "tr", "td", "th", "thead", "tbody", "tfoot", "colgroup", "caption",
                "section", "article", "header", "footer", "main", "nav", "aside", "figure",
                "figcaption", "body", "html", "dl", "dt", "dd", "center", "details", "summary",
            )
        }
    }
}
