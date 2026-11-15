package com.cipherwell.notezero.model

/**
 * Pure Markdown/HTML conversion for the "export note" action.
 *
 * The vault is Markdown-native, so [toMarkdown] only prepends the title as a level-1
 * heading. [toHtml] parses the body with [MarkdownBlockCodec] (the same parser the editor
 * preview uses) and renders a standalone, semantic HTML5 document.
 *
 * Security: exported HTML is *untrusted user content*. Every text node, table cell, code
 * fence and URL is HTML-escaped (`& < > " '`), raw HTML in the note is degraded to text by
 * the codec and then escaped, and link targets are restricted to `http`/`https`/`mailto`
 * (plus scheme-less relative targets). A `javascript:`/`data:`/`vbscript:` URL is dropped
 * and only its label is rendered as text, so a note can never inject a script, an event
 * handler or a `style` attribute into the exported file. The converter never reads the
 * filesystem, never logs content and never throws on arbitrary input.
 *
 * Output is UTF-8/`\n`-based and deterministic so it can be unit-tested on the JVM.
 */
object NoteExport {

    /** Allowed link schemes. Everything else (javascript:, data:, vbscript, file:, …) is
     * rendered as plain text: an exported note must not be able to execute anything. */
    private val ALLOWED_LINK_SCHEMES = setOf("http", "https", "mailto")

    private const val ESCAPABLE = "\\`*_~[]()!"

    /**
     * Markdown export: the title becomes a `#` heading followed by a blank line and the
     * body verbatim (line endings normalised to `\n`). A blank title yields the body only;
     * a blank body yields the heading only.
     */
    fun toMarkdown(title: String, body: String): String {
        val heading = singleLine(title)
        val normalisedBody = normaliseNewlines(body)
        val headingLine = if (heading.isEmpty()) "" else "# $heading"
        return when {
            headingLine.isEmpty() -> normalisedBody
            normalisedBody.isEmpty() -> headingLine
            else -> "$headingLine\n\n$normalisedBody"
        }
    }

    /**
     * HTML export: a complete, self-contained document ([title] also becomes the `<h1>`),
     * with headings, paragraphs, nested ordered/unordered lists, disabled checklists,
     * fenced code, quotes, rules and tables. All user text is escaped.
     */
    fun toHtml(title: String, body: String): String {
        val heading = singleLine(title)
        val document = MarkdownBlockCodec.parse(normaliseNewlines(body))
        val out = StringBuilder(512)
        out.append("<!DOCTYPE html>\n")
        out.append("<html lang=\"en\">\n")
        out.append("<head>\n")
        out.append("<meta charset=\"utf-8\">\n")
        out.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        out.append("<title>").append(escapeHtml(heading)).append("</title>\n")
        out.append("</head>\n")
        out.append("<body>\n")
        out.append("<article>\n")
        if (heading.isNotEmpty()) {
            out.append("<h1>").append(escapeHtml(heading)).append("</h1>\n")
        }
        document.blocks.forEach { appendBlock(out, it) }
        out.append("</article>\n")
        out.append("</body>\n")
        out.append("</html>\n")
        return out.toString()
    }

    // ---- block rendering -----------------------------------------------------

    private fun appendBlock(out: StringBuilder, block: Block) {
        when (block) {
            is Block.Heading -> {
                val level = block.level.coerceIn(1, 6)
                out.append("<h").append(level).append('>')
                    .append(renderInline(block.text))
                    .append("</h").append(level).append(">\n")
            }

            is Block.Paragraph ->
                out.append("<p>").append(renderInline(block.text)).append("</p>\n")

            is Block.Bullets -> appendList(out, block.items, ordered = false)

            is Block.Numbered -> appendList(out, block.items, ordered = true)

            is Block.Checklist -> {
                out.append("<ul class=\"checklist\">\n")
                block.items.forEach { item ->
                    out.append("<li><input type=\"checkbox\" disabled")
                    if (item.checked) out.append(" checked")
                    out.append("> ").append(renderInline(item.text)).append("</li>\n")
                }
                out.append("</ul>\n")
            }

            is Block.Code -> {
                val language = sanitiseLanguage(block.language)
                out.append("<pre><code")
                if (language != null) {
                    out.append(" class=\"language-").append(language).append('"')
                }
                out.append('>').append(escapeHtml(block.code)).append("</code></pre>\n")
            }

            is Block.Quote ->
                out.append("<blockquote>\n<p>")
                    .append(renderInline(block.text))
                    .append("</p>\n</blockquote>\n")

            Block.Rule -> out.append("<hr>\n")

            is Block.Table -> appendTable(out, block)
        }
    }

    /**
     * Nested `<ul>`/`<ol>` rendering. The first item's depth is normalised to zero so a
     * fragment that starts indented still renders as a valid top-level list.
     */
    private fun appendList(out: StringBuilder, items: List<ListItem>, ordered: Boolean) {
        if (items.isEmpty()) return
        val tag = if (ordered) "ol" else "ul"
        out.append('<').append(tag).append(">\n")
        var index = 0
        while (index < items.size) {
            val item = items[index]
            out.append("<li>").append(renderInline(item.text))
            // Everything deeper than this item is its nested list.
            var next = index + 1
            while (next < items.size && items[next].depth > item.depth) next++
            if (next > index + 1) {
                appendList(out, items.subList(index + 1, next), ordered)
            }
            out.append("</li>\n")
            index = next
        }
        out.append("</").append(tag).append(">\n")
    }

    private fun appendTable(out: StringBuilder, table: Block.Table) {
        if (table.headers.isEmpty()) return
        out.append("<table>\n<thead>\n<tr>")
        table.headers.forEach { header ->
            out.append("<th>").append(renderInline(header)).append("</th>")
        }
        out.append("</tr>\n</thead>\n")
        if (table.rows.isNotEmpty()) {
            out.append("<tbody>\n")
            table.rows.forEach { row ->
                out.append("<tr>")
                row.forEach { cell ->
                    out.append("<td>").append(renderInline(cell)).append("</td>")
                }
                out.append("</tr>\n")
            }
            out.append("</tbody>\n")
        }
        out.append("</table>\n")
    }

    /** Fenced-code info strings become a CSS class; only `[A-Za-z0-9_+.#-]` survive. */
    private fun sanitiseLanguage(language: String?): String? {
        val trimmed = language?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val safe = trimmed
            .filter { (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') || it in "_+.#-" }
            .take(32)
        return safe.ifEmpty { null }
    }

    // ---- inline rendering ----------------------------------------------------

    /**
     * Minimal inline Markdown renderer mirroring the preview's token set: `**bold**`,
     * `*italic*` / `_italic_`, `***both***`, `` `code` ``, `[label](url)` and
     * `~~strikethrough~~`, with backslash escapes. Unknown/unbalanced markers stay literal
     * text. Every emitted text fragment and attribute value is escaped.
     */
    private fun renderInline(text: String): String {
        if (text.isEmpty()) return ""
        val out = StringBuilder(text.length + 16)
        val literal = StringBuilder()
        var index = 0

        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                out.append(escapeHtml(literal.toString()))
                literal.setLength(0)
            }
        }

        while (index < text.length) {
            val char = text[index]

            if (char == '\\' && index + 1 < text.length && ESCAPABLE.indexOf(text[index + 1]) >= 0) {
                literal.append(text[index + 1])
                index += 2
                continue
            }

            if (char == '`') {
                val closing = text.indexOf('`', startIndex = index + 1)
                if (closing > index + 1) {
                    flushLiteral()
                    out.append("<code>")
                        .append(escapeHtml(text.substring(index + 1, closing)))
                        .append("</code>")
                    index = closing + 1
                    continue
                }
            }

            if (char == '[') {
                val link = parseLink(text, index)
                if (link != null) {
                    flushLiteral()
                    val label = text.substring(index + 1, link.closeBracket)
                    val rawUrl = text.substring(link.closeBracket + 2, link.closeParen).trim()
                    val safeUrl = sanitiseLinkUrl(rawUrl)
                    if (safeUrl == null) {
                        // Unsupported/dangerous scheme: keep the words, drop the hyperlink.
                        out.append(escapeHtml(label.ifEmpty { rawUrl }))
                    } else {
                        out.append("<a href=\"").append(escapeHtml(safeUrl)).append("\">")
                            .append(escapeHtml(label))
                            .append("</a>")
                    }
                    index = link.closeParen + 1
                    continue
                }
            }

            if (char == '*' || char == '_') {
                val emphasis = renderEmphasis(text, index, char)
                if (emphasis != null) {
                    flushLiteral()
                    out.append(emphasis.html)
                    index = emphasis.next
                    continue
                }
            }

            if (char == '~' && text.startsWith("~~", index)) {
                val closing = text.indexOf("~~", startIndex = index + 2)
                if (closing > index + 2) {
                    flushLiteral()
                    out.append("<del>")
                        .append(renderInline(text.substring(index + 2, closing)))
                        .append("</del>")
                    index = closing + 2
                    continue
                }
            }

            literal.append(char)
            index++
        }
        flushLiteral()
        return out.toString()
    }

    private data class RenderedEmphasis(val html: String, val next: Int)

    private fun renderEmphasis(text: String, start: Int, marker: Char): RenderedEmphasis? {
        val triple = "$marker$marker$marker"
        val double = "$marker$marker"

        if (text.startsWith(triple, startIndex = start)) {
            val closing = text.indexOf(triple, startIndex = start + 3)
            if (closing > start + 3) {
                val content = renderInline(text.substring(start + 3, closing))
                return RenderedEmphasis("<strong><em>$content</em></strong>", closing + 3)
            }
        }
        if (text.startsWith(double, startIndex = start)) {
            val closing = text.indexOf(double, startIndex = start + 2)
            if (closing > start + 2) {
                val content = renderInline(text.substring(start + 2, closing))
                return RenderedEmphasis("<strong>$content</strong>", closing + 2)
            }
        }
        // A single marker that is really the start of a double marker must not be italic.
        if (start + 1 < text.length && text[start + 1] == marker) return null
        val closing = text.indexOf(marker, startIndex = start + 1)
        if (closing > start + 1) {
            if (marker == '_' && start > 0 && text[start - 1].isLetterOrDigit()) return null
            val content = renderInline(text.substring(start + 1, closing))
            return RenderedEmphasis("<em>$content</em>", closing + 1)
        }
        return null
    }

    private data class LinkSpan(val closeBracket: Int, val closeParen: Int)

    private fun parseLink(text: String, start: Int): LinkSpan? {
        val closeBracket = text.indexOf(']', startIndex = start + 1)
        if (closeBracket < 0) return null
        if (closeBracket + 1 >= text.length || text[closeBracket + 1] != '(') return null
        val closeParen = text.indexOf(')', startIndex = closeBracket + 2)
        if (closeParen < 0) return null
        val label = text.substring(start + 1, closeBracket)
        val url = text.substring(closeBracket + 2, closeParen).trim()
        if (label.isEmpty() && url.isEmpty()) return null
        return LinkSpan(closeBracket, closeParen)
    }

    /**
     * Returns the URL when it is safe to place in an `href`, else null.
     *
     * Allowlist: `http`, `https`, `mailto` and scheme-less targets. Control characters are
     * removed before the scheme check so `java\nscript:` cannot slip through; the returned
     * value is HTML-escaped by the caller.
     */
    internal fun sanitiseLinkUrl(raw: String): String? {
        if (raw.isEmpty()) return null
        val cleaned = buildString(raw.length) {
            raw.forEach { character ->
                if (!character.isISOControl()) append(character)
            }
        }.trim()
        if (cleaned.isEmpty()) return null

        val colon = cleaned.indexOf(':')
        if (colon > 0) {
            val firstPathChar = listOf(cleaned.indexOf('/'), cleaned.indexOf('?'), cleaned.indexOf('#'))
                .filter { it >= 0 }
                .minOrNull() ?: Int.MAX_VALUE
            if (colon < firstPathChar) {
                val scheme = cleaned.substring(0, colon)
                    .filterNot { it.isWhitespace() }
                    .lowercase()
                if (scheme !in ALLOWED_LINK_SCHEMES) return null
            }
        }
        return cleaned
    }

    // ---- escaping / normalisation -------------------------------------------

    /** Escapes every character that can change HTML structure or attribute quoting. */
    internal fun escapeHtml(value: String): String {
        if (value.isEmpty()) return ""
        val out = StringBuilder(value.length + 16)
        value.forEach { character ->
            when (character) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&#39;")
                else -> out.append(character)
            }
        }
        return out.toString()
    }

    private fun normaliseNewlines(value: String): String =
        value.replace("\r\n", "\n").replace('\r', '\n')

    /** Collapses a title to one line: headings and `<title>` cannot span lines. */
    private fun singleLine(value: String): String =
        normaliseNewlines(value)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}
