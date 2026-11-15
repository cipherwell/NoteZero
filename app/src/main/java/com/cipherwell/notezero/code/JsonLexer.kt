package com.cipherwell.notezero.code

import com.cipherwell.notezero.code.TokenType.ERROR
import com.cipherwell.notezero.code.TokenType.KEYWORD
import com.cipherwell.notezero.code.TokenType.NUMBER
import com.cipherwell.notezero.code.TokenType.PROPERTY
import com.cipherwell.notezero.code.TokenType.PUNCTUATION
import com.cipherwell.notezero.code.TokenType.STRING

/**
 * JSON lexer. Object keys become [TokenType.PROPERTY]; other strings are [TokenType.STRING].
 * Characters that are valid in no JSON token are reported as [TokenType.ERROR] instead of being
 * silently skipped. Uses no state machine — a string is classified by the next non-space char
 * (`:` means property key), which also handles deeply nested documents.
 */
internal class JsonLexer : BaseLexer(CodeLanguage.JSON) {

    private companion object {
        const val STRUCTURAL = "{}[],:\""

        val KEYWORD_VALUES: Set<String> = setOf("true", "false", "null")
    }

    override fun scan() {
        while (pos < length && !halted) {
            val c = text[pos]
            when {
                isSpaceOrNewline(c) -> pos++
                c == '"' -> string()
                c in STRUCTURAL -> {
                    emit(pos, pos + 1, PUNCTUATION)
                    pos++
                }
                c == '-' || isAsciiDigit(c) -> number()
                c.isLetter() -> literal()
                else -> errorRun()
            }
        }
    }

    private fun string() {
        val start = pos
        pos++ // opening quote
        while (pos < length && !halted) {
            val c = text[pos]
            when {
                c == '\\' -> pos += 2
                c == '"' -> {
                    pos++
                    break
                }
                isLineBreak(c) -> break // JSON strings cannot contain raw newlines
                else -> pos++
            }
        }
        if (pos > length) pos = length
        var lookahead = pos
        while (lookahead < length && isSpaceOrNewline(text[lookahead])) lookahead++
        val type = if (charAt(lookahead) == ':') PROPERTY else STRING
        emit(start, pos, type)
    }

    private fun literal() {
        val start = pos
        while (pos < length && text[pos].isLetter()) pos++
        if (pos == start) {
            pos++
            return
        }
        val value = text.substring(start, pos)
        emit(start, pos, if (value in KEYWORD_VALUES) KEYWORD else ERROR)
    }

    private fun number() {
        val start = pos
        if (peek() == '-') pos++
        val integerStart = pos
        while (pos < length && isAsciiDigit(text[pos])) pos++
        if (pos == integerStart) {
            // A lone '-' is not a number; report the character as invalid input.
            pos = start + 1
            emit(start, pos, ERROR)
            return
        }
        if (peek() == '.' && isAsciiDigit(peek(1))) {
            pos++
            while (pos < length && isAsciiDigit(text[pos])) pos++
        }
        if (peek() == 'e' || peek() == 'E') {
            val exponentStart = pos
            pos++
            if (peek() == '+' || peek() == '-') pos++
            val digitsStart = pos
            while (pos < length && isAsciiDigit(text[pos])) pos++
            if (pos == digitsStart) pos = exponentStart
        }
        emit(start, pos, NUMBER)
    }

    /** Groups a run of characters that belong to no JSON token into one ERROR span. */
    private fun errorRun() {
        val start = pos
        while (pos < length) {
            val c = text[pos]
            if (isSpaceOrNewline(c) || c in STRUCTURAL || c == '-' || isAsciiDigit(c) || c.isLetter()) break
            pos++
        }
        if (pos == start) pos++
        emit(start, pos, ERROR)
    }
}
