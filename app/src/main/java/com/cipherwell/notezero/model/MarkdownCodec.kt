package com.cipherwell.notezero.model

/**
 * Canonical Markdown <-> [BlockDocument] codec for NoteZero.
 *
 * The editor treats Markdown as the source of truth: the block document is a rendered
 * view and this codec is the only bridge in both directions. Properties guaranteed for
 * arbitrary user input (never trust the text field):
 *
 *  - [parse] never throws and never loops unboundedly; unknown constructs degrade to
 *    [Block.Paragraph] or plain text (e.g. HTML, setext headings, indented code).
 *  - [render] never throws; it emits one canonical byte sequence per document.
 *  - Round-trip stability: `parse(render(parse(md))) == parse(md)` for every input,
 *    and `render(parse(docMarkdown)) == docMarkdown` for Markdown this codec produced.
 *
 * Canonical form (all line endings normalised to `\n`):
 *  - Blocks are separated by exactly one blank line; no trailing newline.
 *  - Headings: `#`..`######` + one space + text.
 *  - Paragraphs: consecutive non-blank lines, rendered as-is (inline Markdown kept).
 *  - Bullets: `- `, nested two spaces per depth. Numbered: `1. ` (renumbered per depth).
 *  - Checklists: `- [ ] ` / `- [x] ` (always lowercase x).
 *  - Fenced code: backtick fence grown past the longest backtick run in the code, then
 *    optional language, content, closing fence. Quotes: `> ` per line. Rule: `---`.
 *  - Tables: pipe-delimited, `| --- |` delimiter row, literal pipes escaped as `\|`.
 *
 * The codec is pure Kotlin: it is the only model parser in the app and is unit-tested
 * without Android/Robolectric.
 */
object MarkdownBlockCodec : BlockMarkdownCodec {

    private const val MAX_LIST_DEPTH = 5
    private const val MAX_HEADING_LEVEL = 6
    private const val MAX_LANGUAGE_LENGTH = 64

    // ---- parse ---------------------------------------------------------------

    override fun parse(markdown: String): BlockDocument {
        return try {
            BlockDocument(parseBlocks(markdown))
        } catch (t: Throwable) {
            // A parser bug must never take the editor down: fall back to the raw text as
            // one paragraph so the user can still read/copy/export what they typed.
            BlockDocument(listOf(Block.Paragraph(markdown)))
        }
    }

    private fun parseBlocks(markdown: String): List<Block> {
        if (markdown.isEmpty()) return emptyList()
        val normalised = markdown.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalised.split('\n')
        val blocks = ArrayList<Block>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }
            i = parseBlockAt(lines, i, blocks)
        }
        return blocks
    }

    /** Parses the block starting at [start] and returns the index of the next unconsumed line. */
    private fun parseBlockAt(lines: List<String>, start: Int, out: MutableList<Block>): Int {
        val line = lines[start]

        // Fenced code first: its body may contain lines that look like any other block.
        val fence = FENCE_OPEN.matchEntire(line)
        if (fence != null) {
            return parseFencedCode(lines, start, fence, out)
        }

        HEADING.matchEntire(line)?.let { match ->
            val level = match.groupValues[1].length.coerceIn(1, MAX_HEADING_LEVEL)
            out.add(Block.Heading(level, match.groupValues[2].trimEnd()))
            return start + 1
        }

        if (RULE.matches(line.trim())) {
            out.add(Block.Rule)
            return start + 1
        }

        if (line.trimStart().startsWith(TABLE_PIPE)) {
            val delimiter = lines.getOrNull(start + 1)
            if (delimiter != null && TABLE_DELIMITER.matches(delimiter.trim()) && delimiter.contains(TABLE_PIPE)) {
                return parseTable(lines, start, out)
            }
        }

        if (line.trimStart().startsWith(QUOTE_PREFIX)) {
            return parseQuote(lines, start, out)
        }

        if (CHECK_ITEM.matches(line)) {
            // Group map: 1 = indent, 2 = bullet marker, 3 = checkbox state, 4 = item text.
            // CheckItem has no depth field (frozen model): nested checklist lines are kept in
            // document order, flattened to text.
            val items = ArrayList<CheckItem>()
            var i = start
            while (i < lines.size) {
                val match = CHECK_ITEM.matchEntire(lines[i]) ?: break
                items.add(
                    CheckItem(
                        text = listText(match.groupValues[4]),
                        checked = match.groupValues[3].equals("x", ignoreCase = true),
                    ),
                )
                i++
            }
            out.add(Block.Checklist(items))
            return i
        }

        if (BULLET_ITEM.matches(line)) {
            val items = ArrayList<ListItem>()
            var i = start
            while (i < lines.size) {
                val match = BULLET_ITEM.matchEntire(lines[i]) ?: break
                items.add(ListItem(text = match.groupValues[3], depth = indentDepth(match.groupValues[1])))
                i++
            }
            out.add(Block.Bullets(items))
            return i
        }

        if (NUMBERED_ITEM.matches(line)) {
            val items = ArrayList<ListItem>()
            var i = start
            while (i < lines.size) {
                val match = NUMBERED_ITEM.matchEntire(lines[i]) ?: break
                items.add(ListItem(text = match.groupValues[3], depth = indentDepth(match.groupValues[1])))
                i++
            }
            out.add(Block.Numbered(items))
            return i
        }

        // Everything else is a paragraph: consecutive non-blank lines that do not start a
        // new block. Inline Markdown is preserved verbatim for the UI renderer.
        val paragraph = StringBuilder()
        var i = start
        while (i < lines.size) {
            val current = lines[i]
            if (current.isBlank()) break
            if (i != start && startsNewBlock(lines, i)) break
            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(current)
            i++
        }
        out.add(Block.Paragraph(paragraph.toString()))
        return i
    }

    private fun parseFencedCode(
        lines: List<String>,
        start: Int,
        opening: MatchResult,
        out: MutableList<Block>,
    ): Int {
        val fenceLength = opening.groupValues[2].length
        val language = opening.groupValues[3].trim().takeIf { it.isNotEmpty() }
        val content = StringBuilder()
        var i = start + 1
        var closed = false
        while (i < lines.size) {
            val candidate = lines[i].trim()
            if (candidate.length >= fenceLength && candidate.all { it == '`' }) {
                closed = true
                i++
                break
            }
            if (content.isNotEmpty()) content.append('\n')
            content.append(lines[i])
            i++
        }
        // An unclosed fence runs to the end of the document (CommonMark behaviour): the
        // user's text is never silently dropped.
        out.add(Block.Code(language = language, code = content.toString()))
        return if (closed) i else lines.size
    }

    private fun parseQuote(lines: List<String>, start: Int, out: MutableList<Block>): Int {
        val text = StringBuilder()
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            if (!line.trimStart().startsWith(QUOTE_PREFIX)) break
            val stripped = stripQuotePrefix(line)
            if (text.isNotEmpty()) text.append('\n')
            text.append(stripped)
            i++
        }
        out.add(Block.Quote(text.toString()))
        return i
    }

    private fun parseTable(lines: List<String>, start: Int, out: MutableList<Block>): Int {
        val headers = splitTableRow(lines[start])
        if (headers.isEmpty()) {
            // Not a usable table after all — degrade to a paragraph rather than dropping text.
            out.add(Block.Paragraph(lines[start]))
            return start + 1
        }
        val rows = ArrayList<List<String>>()
        var i = start + 2 // header + delimiter
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank() || !line.trimStart().startsWith(TABLE_PIPE)) break
            rows.add(splitTableRow(line))
            i++
        }
        out.add(Block.Table(headers = headers, rows = rows))
        return i
    }

    private fun startsNewBlock(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        if (line.isBlank()) return true
        if (FENCE_OPEN.matchEntire(line) != null) return true
        if (HEADING.matchEntire(line) != null) return true
        if (RULE.matches(line.trim())) return true
        if (line.trimStart().startsWith(QUOTE_PREFIX)) return true
        if (CHECK_ITEM.matches(line)) return true
        if (BULLET_ITEM.matches(line)) return true
        if (NUMBERED_ITEM.matches(line)) return true
        if (line.trimStart().startsWith(TABLE_PIPE)) {
            val delimiter = lines.getOrNull(index + 1)
            if (delimiter != null && TABLE_DELIMITER.matches(delimiter.trim()) && delimiter.contains(TABLE_PIPE)) {
                return true
            }
        }
        return false
    }

    // ---- render --------------------------------------------------------------

    override fun render(document: BlockDocument): String {
        return try {
            document.blocks
                .map { renderBlock(it) }
                .filter { it.isNotEmpty() }
                .joinToString("\n\n")
        } catch (t: Throwable) {
            ""
        }
    }

    private fun renderBlock(block: Block): String = when (block) {
        is Block.Heading -> {
            val level = block.level.coerceIn(1, MAX_HEADING_LEVEL)
            "#".repeat(level) + " " + block.text
        }

        is Block.Paragraph -> block.text

        is Block.Bullets -> renderListItems(block.items) { item ->
            renderListLine(item.depth, "- ", item.text)
        }

        is Block.Numbered -> {
            val counters = HashMap<Int, Int>()
            block.items.joinToString("\n") { item ->
                val depth = item.depth.coerceIn(0, MAX_LIST_DEPTH)
                // Deeper counters reset when we return to a shallower depth.
                counters.keys.filter { it > depth }.forEach(counters::remove)
                val next = (counters[depth] ?: 0) + 1
                counters[depth] = next
                renderListLine(depth, "$next. ", item.text)
            }
        }

        is Block.Checklist -> block.items.joinToString("\n") { item ->
            val marker = if (item.checked) "- [x] " else "- [ ] "
            indent(0) + marker + item.text
        }

        is Block.Code -> renderCode(block)
        is Block.Quote -> renderQuote(block.text)
        Block.Rule -> "---"
        is Block.Table -> renderTable(block)
    }

    private fun renderListItems(
        items: List<ListItem>,
        render: (ListItem) -> String,
    ): String = items.joinToString("\n", transform = render)

    private fun renderListLine(depth: Int, marker: String, text: String): String =
        indent(depth.coerceIn(0, MAX_LIST_DEPTH)) + marker + text

    private fun renderCode(block: Block.Code): String {
        val language = block.language
            ?.filter { it != '`' && it != '\n' && it != '\r' }
            ?.trim()
            ?.take(MAX_LANGUAGE_LENGTH)
            ?.takeIf { it.isNotEmpty() }
            .orEmpty()
        var longestRun = 0
        var run = 0
        for (c in block.code) {
            if (c == '`') {
                run++
                if (run > longestRun) longestRun = run
            } else {
                run = 0
            }
        }
        val fence = "`".repeat(maxOf(3, longestRun + 1))
        return "$fence$language\n${block.code}\n$fence"
    }

    private fun renderQuote(text: String): String =
        text.split('\n').joinToString("\n") { line ->
            if (line.isEmpty()) QUOTE_PREFIX else "$QUOTE_PREFIX $line"
        }

    private fun renderTable(block: Block.Table): String {
        if (block.headers.isEmpty()) return ""
        val header = tableRow(block.headers)
        val delimiter = tableRow(block.headers.map { "---" })
        val rows = block.rows.joinToString("\n") { tableRow(it) }
        return if (rows.isEmpty()) "$header\n$delimiter" else "$header\n$delimiter\n$rows"
    }

    private fun tableRow(cells: List<String>): String =
        "| " + cells.joinToString(" | ") { it.replace("\\", "\\\\").replace("|", "\\|") } + " |"

    private fun indent(depth: Int): String = "  ".repeat(depth.coerceIn(0, MAX_LIST_DEPTH))

    private fun indentDepth(leadingSpaces: String): Int =
        (leadingSpaces.length / 2).coerceIn(0, MAX_LIST_DEPTH)

    // ---- shared helpers ------------------------------------------------------

    private fun listText(raw: String): String = raw.trimEnd()

    private fun stripQuotePrefix(line: String): String {
        val start = line.indexOf(QUOTE_PREFIX)
        if (start < 0) return line
        val after = line.substring(start + QUOTE_PREFIX.length)
        return after.removePrefix(" ")
    }

    private fun splitTableRow(line: String): List<String> {
        var body = line.trim()
        if (body.startsWith(TABLE_PIPE)) body = body.substring(1)
        if (body.endsWith("|") && !body.endsWith("\\|")) body = body.dropLast(1)
        val cells = ArrayList<String>()
        val cell = StringBuilder()
        var escaped = false
        for (c in body) {
            when {
                escaped -> {
                    cell.append(c)
                    escaped = false
                }

                c == '\\' -> escaped = true
                c == '|' -> {
                    cells.add(cell.toString().trim())
                    cell.setLength(0)
                }

                else -> cell.append(c)
            }
        }
        if (escaped) cell.append('\\')
        if (cell.isNotEmpty() || cells.isEmpty() || body.endsWith("|")) cells.add(cell.toString().trim())
        return cells
    }

    private const val TABLE_PIPE = "|"
    private const val QUOTE_PREFIX = ">"

    private val FENCE_OPEN = Regex("""^( {0,3})(`{3,})([^`]*)$""")
    private val HEADING = Regex("""^(#{1,6})[ \t]+(.*)$""")
    private val RULE = Regex("""^(?:(?:-[ \t]*){3,}|(?:\*[ \t]*){3,}|(?:_[ \t]*){3,})$""")
    private val CHECK_ITEM = Regex("""^( *)([-*+])[ \t]+\[([ xX])\](?:[ \t]+(.*))?$""")
    private val BULLET_ITEM = Regex("""^( *)([-*+])[ \t]+(.*)$""")
    private val NUMBERED_ITEM = Regex("""^( *)(\d{1,9})[.)][ \t]+(.*)$""")
    private val TABLE_DELIMITER = Regex("""^\|?[ \t]*:?-+:?[ \t]*(\|[ \t]*:?-+:?[ \t]*)*\|?$""")
}

/** Convenience for editor/preview code: render a document back to canonical Markdown. */
fun BlockDocument.toMarkdown(): String = MarkdownBlockCodec.render(this)

/** Convenience for editor/preview code: parse Markdown into a renderable document. */
fun parseMarkdown(markdown: String): BlockDocument = MarkdownBlockCodec.parse(markdown)
