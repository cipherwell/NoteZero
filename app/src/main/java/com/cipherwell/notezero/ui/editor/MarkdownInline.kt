package com.cipherwell.notezero.ui.editor

/**
 * Minimal inline Markdown tokenizer for note preview rendering.
 *
 * Supports `**bold**`, `*italic*` / `_italic_`, `***bold italic***`, `` `code` ``,
 * `[text](url)` and `~~strikethrough~~`, plus backslash escapes for the markers.
 * Unknown or unbalanced markers are emitted as literal text — this never throws and
 * never drops user input.
 */
sealed interface InlineToken {
    data class Text(val text: String) : InlineToken

    data class Bold(val text: String) : InlineToken

    data class Italic(val text: String) : InlineToken

    data class BoldItalic(val text: String) : InlineToken

    data class Code(val text: String) : InlineToken

    data class Link(val text: String, val url: String) : InlineToken

    data class Strikethrough(val text: String) : InlineToken
}

object MarkdownInline {

    private const val ESCAPABLE = "\\`*_~[]()!"

    fun parse(text: String): List<InlineToken> {
        if (text.isEmpty()) return emptyList()
        val tokens = mutableListOf<InlineToken>()
        val literal = StringBuilder()

        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                tokens += InlineToken.Text(literal.toString())
                literal.clear()
            }
        }

        var index = 0
        while (index < text.length) {
            val char = text[index]

            // Backslash escapes the next character.
            if (char == '\\' && index + 1 < text.length && ESCAPABLE.indexOf(text[index + 1]) >= 0) {
                literal.append(text[index + 1])
                index += 2
                continue
            }

            // Code spans win over emphasis (content is literal).
            if (char == '`') {
                val closing = text.indexOf('`', startIndex = index + 1)
                if (closing > index + 1) {
                    flushLiteral()
                    tokens += InlineToken.Code(text.substring(index + 1, closing))
                    index = closing + 1
                    continue
                }
            }

            // Links: [text](url)
            if (char == '[') {
                val link = parseLink(text, index)
                if (link != null) {
                    flushLiteral()
                    tokens += link.first
                    index = link.second
                    continue
                }
            }

            if (char == '*' || char == '_') {
                val emphasis = parseEmphasis(text, index, char)
                if (emphasis != null) {
                    flushLiteral()
                    tokens += emphasis.first
                    index = emphasis.second
                    continue
                }
            }

            if (char == '~' && text.startsWith("~~", index)) {
                val closing = text.indexOf("~~", startIndex = index + 2)
                if (closing > index + 2) {
                    flushLiteral()
                    tokens += InlineToken.Strikethrough(text.substring(index + 2, closing))
                    index = closing + 2
                    continue
                }
            }

            literal.append(char)
            index++
        }
        flushLiteral()
        return tokens
    }

    private fun parseLink(text: String, start: Int): Pair<InlineToken.Link, Int>? {
        val closeBracket = text.indexOf(']', startIndex = start + 1)
        if (closeBracket < 0) return null
        if (closeBracket + 1 >= text.length || text[closeBracket + 1] != '(') return null
        val closeParen = text.indexOf(')', startIndex = closeBracket + 2)
        if (closeParen < 0) return null
        val label = text.substring(start + 1, closeBracket)
        val url = text.substring(closeBracket + 2, closeParen).trim()
        if (label.isEmpty() && url.isEmpty()) return null
        return InlineToken.Link(label, url) to closeParen + 1
    }

    private fun parseEmphasis(text: String, start: Int, marker: Char): Pair<InlineToken, Int>? {
        val triple = "$marker$marker$marker"
        val double = "$marker$marker"

        if (text.startsWith(triple, startIndex = start)) {
            val closing = text.indexOf(triple, startIndex = start + 3)
            if (closing > start + 3) {
                return InlineToken.BoldItalic(text.substring(start + 3, closing)) to closing + 3
            }
        }
        if (text.startsWith(double, startIndex = start)) {
            val closing = text.indexOf(double, startIndex = start + 2)
            if (closing > start + 2) {
                return InlineToken.Bold(text.substring(start + 2, closing)) to closing + 2
            }
        }
        // Single marker italic; skip when it is part of a double marker for a later token.
        if (start + 1 < text.length && text[start + 1] == marker) return null
        val closing = text.indexOf(marker, startIndex = start + 1)
        if (closing > start + 1) {
            val content = text.substring(start + 1, closing)
            // Avoid treating an intraword underscore as emphasis (snake_case).
            if (marker == '_' && start > 0 && text[start - 1].isLetterOrDigit()) return null
            return InlineToken.Italic(content) to closing + 1
        }
        return null
    }
}
