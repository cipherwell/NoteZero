package com.cipherwell.notezero.code

import com.cipherwell.notezero.code.TokenType.ATTRIBUTE
import com.cipherwell.notezero.code.TokenType.COMMENT
import com.cipherwell.notezero.code.TokenType.ERROR
import com.cipherwell.notezero.code.TokenType.PUNCTUATION
import com.cipherwell.notezero.code.TokenType.STRING
import com.cipherwell.notezero.code.TokenType.TAG

/**
 * XML/HTML lexer. Elements produce [TokenType.TAG] names, attributes [TokenType.ATTRIBUTE],
 * quoted values [TokenType.STRING]; comments and DOCTYPE declarations are [TokenType.COMMENT];
 * CDATA sections are [TokenType.STRING]. Text content outside tags is left uncoloured (no token),
 * so renderers fall back to the base text colour.
 *
 * Only `<` starts a construct, so plain prose containing brackets never confuses the scanner.
 * Unterminated comments/CDATA/tags stop at end of input — no throw, no runaway scan.
 */
internal class XmlLexer : BaseLexer(CodeLanguage.XML) {

    override fun scan() {
        while (pos < length && !halted) {
            when {
                text[pos] == '<' -> markup()
                text[pos] == '>' -> {
                    emit(pos, pos + 1, PUNCTUATION) // stray '>'
                    pos++
                }
                else -> pos++ // element text content
            }
        }
    }

    private fun markup() {
        val start = pos
        when {
            startsWith("<!--") -> comment(start)
            startsWith("<![CDATA[") -> cdata(start)
            startsWith("<!") -> declaration(start)
            startsWith("<?") -> processingInstruction(start)
            startsWith("</") -> closeTag(start)
            else -> openTag(start)
        }
    }

    private fun comment(start: Int) {
        val end = text.indexOf("-->", start + 4)
        pos = if (end < 0) length else end + 3
        emit(start, pos, COMMENT)
    }

    private fun cdata(start: Int) {
        val end = text.indexOf("]]>", start + 9)
        pos = if (end < 0) length else end + 3
        emit(start, pos, STRING)
    }

    private fun declaration(start: Int) {
        // <!DOCTYPE ...> possibly with an internal [ ... ] subset containing '>' characters.
        pos = start + 2
        var bracketDepth = 0
        while (pos < length && !halted) {
            val c = text[pos]
            when {
                c == '[' -> {
                    bracketDepth++
                    pos++
                }
                c == ']' -> {
                    if (bracketDepth > 0) bracketDepth--
                    pos++
                }
                c == '>' && bracketDepth == 0 -> {
                    pos++
                    break
                }
                else -> pos++
            }
        }
        emit(start, pos, COMMENT)
    }

    private fun processingInstruction(start: Int) {
        emit(start, start + 2, PUNCTUATION) // "<?"
        pos = start + 2
        val nameStart = pos
        while (pos < length && isNameChar(text[pos])) pos++
        if (pos > nameStart) emit(nameStart, pos, TAG)
        tagBody(terminator = "?>", allowSelfClosing = false)
    }

    private fun closeTag(start: Int) {
        emit(start, start + 2, PUNCTUATION) // "</"
        pos = start + 2
        val nameStart = pos
        while (pos < length && isNameChar(text[pos])) pos++
        if (pos > nameStart) emit(nameStart, pos, TAG)
        // Malformed closers are consumed up to '>' (or end of input) as PUNCTUATION.
        while (pos < length && !halted && text[pos] != '>') {
            emit(pos, pos + 1, if (isSpaceOrNewline(text[pos])) PUNCTUATION else ERROR)
            pos++
        }
        if (pos < length) {
            emit(pos, pos + 1, PUNCTUATION)
            pos++
        }
    }

    private fun openTag(start: Int) {
        val nameStart = start + 1
        if (nameStart >= length || !isNameChar(text[nameStart])) {
            // A bare '<' that cannot start a tag is invalid markup (e.g. `1 < 2` in XML text).
            emit(start, start + 1, ERROR)
            pos = start + 1
            return
        }
        emit(start, start + 1, PUNCTUATION) // "<"
        pos = nameStart
        while (pos < length && isNameChar(text[pos])) pos++
        emit(nameStart, pos, TAG)
        tagBody(terminator = ">", allowSelfClosing = true)
    }

    /** Scans attributes until [terminator] (`>` or `?>`), emitting PUNCTUATION for delimiters. */
    private fun tagBody(terminator: String, allowSelfClosing: Boolean) {
        while (pos < length && !halted) {
            val c = text[pos]
            when {
                isSpaceOrNewline(c) -> pos++
                allowSelfClosing && startsWith("/>") -> {
                    emit(pos, pos + 2, PUNCTUATION)
                    pos += 2
                    return
                }
                startsWith(terminator) -> {
                    emit(pos, pos + terminator.length, PUNCTUATION)
                    pos += terminator.length
                    return
                }
                c == '"' || c == '\'' -> quotedValue(c)
                isNameChar(c) -> attribute()
                else -> {
                    emit(pos, pos + 1, ERROR)
                    pos++
                }
            }
        }
    }

    private fun attribute() {
        val start = pos
        while (pos < length && isNameChar(text[pos])) pos++
        emit(start, pos, ATTRIBUTE)
        while (pos < length && isSpaceOrNewline(text[pos])) pos++
        if (pos < length && text[pos] == '=') {
            emit(pos, pos + 1, PUNCTUATION)
            pos++
            while (pos < length && isSpaceOrNewline(text[pos])) pos++
            val valueStart = pos
            if (pos < length && (text[pos] == '"' || text[pos] == '\'')) {
                quotedValue(text[pos])
            } else {
                // Unquoted attribute value: tolerated until whitespace or a tag delimiter.
                while (pos < length && !isSpaceOrNewline(text[pos]) && text[pos] != '>' &&
                    text[pos] != '/'
                ) {
                    pos++
                }
                if (pos > valueStart) emit(valueStart, pos, STRING)
            }
        }
    }

    private fun quotedValue(quote: Char) {
        val start = pos
        pos++ // opening quote
        while (pos < length && !halted && text[pos] != quote) pos++
        if (pos < length) pos++
        emit(start, pos, STRING)
    }

    /** XML name characters: letters, digits, `_`, `-`, `.` and the namespace `:`. */
    private fun isNameChar(c: Char): Boolean =
        c == '_' || c == '-' || c == '.' || c == ':' || c.isLetterOrDigit()
}
