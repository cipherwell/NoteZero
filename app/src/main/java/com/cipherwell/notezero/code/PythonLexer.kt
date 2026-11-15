package com.cipherwell.notezero.code

import com.cipherwell.notezero.code.TokenType.ANNOTATION
import com.cipherwell.notezero.code.TokenType.IDENTIFIER
import com.cipherwell.notezero.code.TokenType.KEYWORD
import com.cipherwell.notezero.code.TokenType.NUMBER
import com.cipherwell.notezero.code.TokenType.OPERATOR
import com.cipherwell.notezero.code.TokenType.PUNCTUATION
import com.cipherwell.notezero.code.TokenType.STRING
import com.cipherwell.notezero.code.TokenType.TYPE

/**
 * Python lexer: `#` comments, single/double quoted strings, `'''triple'''` / `"""triple"""`
 * strings (docstrings are simply STRING spans), prefixed strings (`rb`, `f`, ...), f-string
 * `{expr}` nesting, `@decorators`, numbers with `j` suffix.
 */
internal class PythonLexer : BaseLexer(CodeLanguage.PYTHON) {

    private companion object {
        const val OPERATOR_CHARS = "+-*/%=<>!&|^~@"
        const val PUNCTUATION_CHARS = "{}[](),;:."

        val KEYWORDS: Set<String> = setOf(
            "and", "as", "assert", "async", "await", "break", "case", "class", "continue", "def",
            "del", "elif", "else", "except", "finally", "for", "from", "global", "if", "import",
            "in", "is", "lambda", "match", "nonlocal", "not", "or", "pass", "raise", "return",
            "try", "while", "with", "yield",
        )

        val TYPES: Set<String> = setOf(
            "bool", "bytearray", "bytes", "complex", "dict", "float", "frozenset", "int", "list",
            "memoryview", "object", "range", "set", "str", "tuple", "type",
        )

        val CONSTANTS: Set<String> = setOf("False", "None", "True")

        val HEX_DIGITS: Set<Char> = "0123456789abcdefABCDEF".toSet()
    }

    override fun scan() {
        while (pos < length && !halted) {
            val c = text[pos]
            when {
                isSpaceOrNewline(c) -> pos++
                c == '#' -> lineComment()
                c == '\\' && isLineBreak(peek(1)) -> {
                    emit(pos, pos + 1, OPERATOR) // explicit line continuation
                    pos++
                }
                c == '"' || c == '\'' -> {
                    val triple = startsWith("$c$c$c")
                    scanString(tokenStart = pos, quoteStart = pos, quote = c, triple = triple, raw = false, formatted = false)
                }
                c == '.' && isAsciiDigit(peek(1)) -> number()
                c == '@' -> decorator()
                isAsciiDigit(c) -> number()
                isIdentifierStart(c) -> word()
                c in OPERATOR_CHARS -> operatorRun()
                else -> punctuation()
            }
        }
    }

    private fun word() {
        val start = pos
        while (pos < length && isIdentifierPart(text[pos])) pos++
        if (pos == start) {
            pos++
            return
        }
        val value = text.substring(start, pos)
        if (pos < length && (text[pos] == '"' || text[pos] == '\'') && isStringPrefix(value)) {
            val quote = text[pos]
            scanString(
                tokenStart = start,
                quoteStart = pos,
                quote = quote,
                triple = startsWith("$quote$quote$quote"),
                raw = value.contains('r') || value.contains('R'),
                formatted = value.contains('f') || value.contains('F'),
            )
            return
        }
        val type = when {
            value in KEYWORDS -> KEYWORD
            value in TYPES -> TYPE
            value in CONSTANTS -> KEYWORD
            else -> IDENTIFIER
        }
        emit(start, pos, type)
    }

    /** Python string prefixes: r, b, u, f and two-letter combinations thereof. */
    private fun isStringPrefix(value: String): Boolean {
        if (value.length !in 1..2) return false
        for (c in value) if (c !in "rRbBuUfF") return false
        return true
    }

    private fun decorator() {
        val start = pos
        pos++ // '@'
        val nameStart = pos
        while (pos < length && (isIdentifierPart(text[pos]) || text[pos] == '.')) pos++
        emit(start, pos, if (pos > nameStart) ANNOTATION else OPERATOR)
    }

    private fun number() {
        val start = pos
        when {
            peek() == '0' && (peek(1) == 'x' || peek(1) == 'X') -> {
                pos += 2
                while (pos < length && (text[pos] in HEX_DIGITS || text[pos] == '_')) pos++
            }
            peek() == '0' && (peek(1) == 'o' || peek(1) == 'O') -> {
                pos += 2
                while (pos < length && (text[pos] in '0'..'7' || text[pos] == '_')) pos++
            }
            peek() == '0' && (peek(1) == 'b' || peek(1) == 'B') -> {
                pos += 2
                while (pos < length && (text[pos] == '0' || text[pos] == '1' || text[pos] == '_')) pos++
            }
            else -> {
                if (peek() == '.') pos++ // `.5`
                while (pos < length && (isAsciiDigit(text[pos]) || text[pos] == '_')) pos++
                if (peek() == '.') {
                    pos++
                    while (pos < length && (isAsciiDigit(text[pos]) || text[pos] == '_')) pos++
                }
                if (peek() == 'e' || peek() == 'E') {
                    val exponentStart = pos
                    pos++
                    if (peek() == '+' || peek() == '-') pos++
                    val digitsStart = pos
                    while (pos < length && (isAsciiDigit(text[pos]) || text[pos] == '_')) pos++
                    if (pos == digitsStart) pos = exponentStart
                }
            }
        }
        if (pos < length && (text[pos] == 'j' || text[pos] == 'J')) pos++ // imaginary literal
        emit(start, pos, NUMBER)
    }

    private fun scanString(
        tokenStart: Int,
        quoteStart: Int,
        quote: Char,
        triple: Boolean,
        raw: Boolean,
        formatted: Boolean,
    ) {
        pos = quoteStart + if (triple) 3 else 1
        val closing = "$quote$quote$quote"
        while (pos < length && !halted) {
            val c = text[pos]
            when {
                triple && startsWith(closing) -> {
                    pos += 3
                    break
                }
                !triple && c == quote -> {
                    pos++
                    break
                }
                !raw && c == '\\' -> pos += 2
                !triple && isLineBreak(c) -> break // unterminated single-line string
                formatted && c == '{' && peek(1) == '{' -> pos += 2 // literal '{{'
                formatted && c == '}' && peek(1) == '}' -> pos += 2 // literal '}}'
                formatted && c == '{' -> skipFormatExpression()
                else -> pos++
            }
        }
        if (pos > length) pos = length
        emit(tokenStart, pos, STRING)
    }

    /** F-string `{expr}` (and its `:format` tail); braces nest and may contain strings. */
    private fun skipFormatExpression() {
        pos++ // '{'
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
    }

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

    private fun operatorRun() {
        val start = pos
        pos++
        while (pos < length && text[pos] in OPERATOR_CHARS) {
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
}
