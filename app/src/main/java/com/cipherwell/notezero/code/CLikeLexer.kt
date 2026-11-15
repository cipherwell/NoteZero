package com.cipherwell.notezero.code

import com.cipherwell.notezero.code.TokenType.ANNOTATION
import com.cipherwell.notezero.code.TokenType.CHAR
import com.cipherwell.notezero.code.TokenType.COMMENT
import com.cipherwell.notezero.code.TokenType.IDENTIFIER
import com.cipherwell.notezero.code.TokenType.KEYWORD
import com.cipherwell.notezero.code.TokenType.NUMBER
import com.cipherwell.notezero.code.TokenType.OPERATOR
import com.cipherwell.notezero.code.TokenType.PUNCTUATION
import com.cipherwell.notezero.code.TokenType.STRING
import com.cipherwell.notezero.code.TokenType.TYPE

/**
 * Configurable single-pass scanner for the brace-and-semicolon family: Kotlin, Java,
 * JavaScript and TypeScript. All language-specific differences are parameters so string,
 * comment and escape handling stays in one reviewed place.
 */
internal open class CLikeLexer(
    language: CodeLanguage,
    private val keywords: Set<String>,
    private val types: Set<String>,
    private val constants: Set<String>,
    private val nestedBlockComments: Boolean = false,
    private val charLiterals: Boolean = true,
    private val tripleQuotedStrings: Boolean = true,
    private val rawTripleQuoted: Boolean = false,
    private val dollarTemplates: Boolean = false,
    private val backtickTemplates: Boolean = false,
    private val backtickIdentifiers: Boolean = false,
    private val regexLiterals: Boolean = false,
    private val dollarInIdentifiers: Boolean = false,
    private val digitSuffixChars: String = "lLfFdDuU",
) : BaseLexer(language) {

    private companion object {
        const val OPERATOR_CHARS = "+-*/%=!<>&|^~?"
        const val PUNCTUATION_CHARS = "{}[](),;:."

        /** Keywords after which a `/` starts a JS/TS regex literal rather than a division. */
        val REGEX_PRECEDING_KEYWORDS: Set<String> = setOf(
            "return", "typeof", "instanceof", "in", "of", "case", "delete", "void", "new",
            "do", "else", "yield", "await", "throw",
        )

        /** Punctuation after which a `/` starts a JS/TS regex literal. */
        val REGEX_PRECEDING_PUNCTUATION: Set<String> = setOf("(", "[", "{", ",", ";", ":")

        val HEX_DIGITS: Set<Char> = "0123456789abcdefABCDEF".toSet()
    }

    override fun scan() {
        while (pos < length && !halted) {
            val c = text[pos]
            when {
                isSpaceOrNewline(c) -> pos++
                c == '/' && peek(1) == '/' -> lineComment()
                c == '/' && peek(1) == '*' -> blockComment()
                c == '/' && regexLiterals && regexAllowed() -> regexLiteral()
                c == '"' && tripleQuotedStrings && startsWith("\"\"\"") -> tripleString()
                c == '"' -> quotedString('"')
                c == '\'' -> if (charLiterals) charLiteral() else quotedString('\'')
                c == '`' -> when {
                    backtickTemplates -> templateString()
                    backtickIdentifiers -> backtickIdentifier()
                    else -> punctuation()
                }
                c == '@' -> annotation()
                isAsciiDigit(c) -> number()
                isIdentifierStart(c, if (dollarInIdentifiers) '$' else null) -> word()
                c in OPERATOR_CHARS -> operatorRun()
                c in PUNCTUATION_CHARS -> punctuation()
                else -> punctuation()
            }
        }
    }

    // ------------------------------------------------------------------ words

    private fun word() {
        val start = pos
        val extra = if (dollarInIdentifiers) '$' else null
        while (pos < length && isIdentifierPart(text[pos], extra)) pos++
        if (pos == start) {
            pos++ // defensive: guarantee progress
            return
        }
        val value = text.substring(start, pos)
        val type = when {
            value in keywords -> KEYWORD
            value in types -> TYPE
            value in constants -> KEYWORD
            else -> IDENTIFIER
        }
        emit(start, pos, type)
    }

    private fun annotation() {
        val start = pos
        pos++ // '@'
        val nameStart = pos
        while (pos < length && isIdentifierPart(text[pos], '$')) pos++
        // Kotlin use-site target: @get:Name, @param:Name, ...
        if (pos > nameStart && peek() == ':') {
            val afterColon = pos + 1
            if (afterColon < length && isIdentifierStart(text[afterColon])) {
                pos = afterColon
                while (pos < length && isIdentifierPart(text[pos])) pos++
            }
        }
        // Qualified annotation name: @a.b.Type
        while (peek() == '.' && isIdentifierStart(peek(1))) {
            pos++ // '.'
            while (pos < length && isIdentifierPart(text[pos])) pos++
        }
        emit(start, pos, if (pos > start + 1) ANNOTATION else PUNCTUATION)
    }

    // ------------------------------------------------------------------ numbers

    private fun number() {
        val start = pos
        if (peek() == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            pos += 2
            while (pos < length && (text[pos] in HEX_DIGITS || text[pos] == '_')) pos++
        } else if (peek() == '0' && (peek(1) == 'b' || peek(1) == 'B')) {
            pos += 2
            while (pos < length && (text[pos] == '0' || text[pos] == '1' || text[pos] == '_')) pos++
        } else {
            while (pos < length && (isAsciiDigit(text[pos]) || text[pos] == '_')) pos++
            if (peek() == '.' && isAsciiDigit(peek(1))) {
                pos++ // '.'
                while (pos < length && (isAsciiDigit(text[pos]) || text[pos] == '_')) pos++
            }
            if (peek() == 'e' || peek() == 'E') {
                val exponentStart = pos
                pos++
                if (peek() == '+' || peek() == '-') pos++
                val digitsStart = pos
                while (pos < length && (isAsciiDigit(text[pos]) || text[pos] == '_')) pos++
                if (pos == digitsStart) pos = exponentStart // `1e` was not an exponent
            }
        }
        while (pos < length && text[pos] in digitSuffixChars) pos++
        emit(start, pos, NUMBER)
    }

    // ------------------------------------------------------------------ strings

    private fun quotedString(quote: Char) {
        val start = pos
        pos++ // opening quote
        while (pos < length && !halted) {
            val c = text[pos]
            when {
                c == '\\' -> pos += 2
                c == quote -> {
                    pos++
                    break
                }
                isLineBreak(c) -> break // unterminated single-line string: stop at the break
                dollarTemplates && c == '$' -> skipDollarTemplate()
                else -> pos++
            }
        }
        if (pos > length) pos = length
        emit(start, pos, STRING)
    }

    private fun charLiteral() {
        val start = pos
        pos++ // opening quote
        while (pos < length && !halted) {
            val c = text[pos]
            when {
                c == '\\' -> pos += 2
                c == '\'' -> {
                    pos++
                    break
                }
                isLineBreak(c) -> break
                else -> pos++
            }
        }
        if (pos > length) pos = length
        emit(start, pos, CHAR)
    }

    /** Kotlin raw string or Java text block: newlines allowed, ends at the next `"""`. */
    private fun tripleString() {
        val start = pos
        pos += 3
        while (pos < length && !halted) {
            if (startsWith("\"\"\"")) {
                pos += 3
                break
            }
            val c = text[pos]
            when {
                dollarTemplates && c == '$' -> skipDollarTemplate()
                c == '\\' && !rawTripleQuoted -> pos += 2
                else -> pos++
            }
        }
        if (pos > length) pos = length
        emit(start, pos, STRING)
    }

    /** Kotlin `"${expr}"` / `"$name"` inside a string; advances past the expansion. */
    private fun skipDollarTemplate() {
        if (peek(1) == '{') {
            pos += 2
            var depth = 1
            while (pos < length && depth > 0 && !halted) {
                val c = text[pos]
                when {
                    c == '{' -> {
                        depth++
                        pos++
                    }
                    c == '}' -> {
                        depth--
                        pos++
                    }
                    c == '"' -> skipQuotedRun('"')
                    c == '\'' -> skipQuotedRun('\'')
                    c == '\\' -> pos += 2
                    else -> pos++
                }
            }
            if (pos > length) pos = length
        } else if (isIdentifierStart(peek(1))) {
            pos++ // '$'
            while (pos < length && isIdentifierPart(text[pos])) pos++
        } else {
            pos++ // literal '$' that is not a template
        }
    }

    /** JS/TS template literal: newlines allowed; `${...}` nesting is stepped over. */
    private fun templateString() {
        val start = pos
        pos++ // opening backtick
        while (pos < length && !halted) {
            val c = text[pos]
            when {
                c == '\\' -> pos += 2
                c == '`' -> {
                    pos++
                    break
                }
                c == '$' && peek(1) == '{' -> skipTemplateInterpolation()
                else -> pos++
            }
        }
        if (pos > length) pos = length
        emit(start, pos, STRING)
    }

    private fun skipTemplateInterpolation() {
        pos += 2 // `${`
        var depth = 1
        while (pos < length && depth > 0 && !halted) {
            val c = text[pos]
            when {
                c == '{' -> {
                    depth++
                    pos++
                }
                c == '}' -> {
                    depth--
                    pos++
                }
                c == '"' -> skipQuotedRun('"')
                c == '\'' -> skipQuotedRun('\'')
                c == '`' -> skipNestedBacktick()
                c == '\\' -> pos += 2
                c == '/' && peek(1) == '/' -> skipToLineEnd()
                c == '/' && peek(1) == '*' -> skipPlainBlockComment()
                else -> pos++
            }
        }
        if (pos > length) pos = length
    }

    /** Kotlin backtick-quoted identifier: `` `my name` ``. */
    private fun backtickIdentifier() {
        val start = pos
        pos++ // opening backtick
        while (pos < length && text[pos] != '`' && !isLineBreak(text[pos])) pos++
        if (peek() == '`') pos++
        if (pos > length) pos = length
        emit(start, pos, IDENTIFIER)
    }

    /** Advances over a quoted run without emitting (used inside template expansions). */
    private fun skipQuotedRun(quote: Char) {
        pos++ // opening quote
        while (pos < length) {
            val c = text[pos]
            when {
                c == '\\' -> pos += 2
                c == quote -> {
                    pos++
                    return
                }
                isLineBreak(c) -> return
                else -> pos++
            }
        }
        if (pos > length) pos = length
    }

    private fun skipNestedBacktick() {
        pos++ // opening backtick
        while (pos < length) {
            val c = text[pos]
            when {
                c == '\\' -> pos += 2
                c == '`' -> {
                    pos++
                    return
                }
                else -> pos++
            }
        }
        if (pos > length) pos = length
    }

    private fun skipPlainBlockComment() {
        val end = text.indexOf("*/", pos + 2)
        pos = if (end < 0) length else end + 2
    }

    // ------------------------------------------------------------------ comments

    private fun blockComment() {
        val start = pos
        if (nestedBlockComments) {
            var depth = 0
            while (pos < length && !halted) {
                when {
                    startsWith("/*") -> {
                        depth++
                        pos += 2
                    }
                    startsWith("*/") -> {
                        depth--
                        pos += 2
                        if (depth <= 0) break
                    }
                    else -> pos++
                }
            }
        } else {
            val end = text.indexOf("*/", pos + 2)
            pos = if (end < 0) length else end + 2
        }
        if (pos > length) pos = length
        emit(start, pos, COMMENT)
    }

    // ------------------------------------------------------------------ operators

    private fun operatorRun() {
        val start = pos
        pos++
        while (pos < length && text[pos] in OPERATOR_CHARS) {
            // Do not swallow the start of a comment that follows an operator: `x=/*c*/1`.
            if (text[pos] == '/' && (charAt(pos + 1) == '/' || charAt(pos + 1) == '*')) break
            pos++
        }
        emit(start, pos, OPERATOR)
    }

    private fun punctuation() {
        val start = pos
        pos++
        emit(start, pos, PUNCTUATION)
    }

    // ------------------------------------------------------------------ JS/TS regex literals

    private fun regexAllowed(): Boolean {
        val previous = lastTokenType() ?: return true
        return when (previous) {
            OPERATOR -> true
            KEYWORD -> lastTokenText()?.let { token -> token in REGEX_PRECEDING_KEYWORDS } ?: false
            PUNCTUATION -> lastTokenText()?.let { token -> token in REGEX_PRECEDING_PUNCTUATION } ?: false
            else -> false
        }
    }

    private fun regexLiteral() {
        val start = pos
        pos++ // '/'
        var inClass = false
        while (pos < length && !halted) {
            val c = text[pos]
            when {
                c == '\\' -> pos += 2
                c == '[' -> {
                    inClass = true
                    pos++
                }
                c == ']' -> {
                    inClass = false
                    pos++
                }
                c == '/' && !inClass -> {
                    pos++
                    break
                }
                isLineBreak(c) -> break
                else -> pos++
            }
        }
        if (pos > length) pos = length
        while (pos < length && isAsciiLetter(text[pos])) pos++ // flags
        emit(start, pos, STRING)
    }
}
