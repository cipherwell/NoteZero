package com.cipherwell.notezero.code

import com.cipherwell.notezero.code.TokenType.IDENTIFIER
import com.cipherwell.notezero.code.TokenType.KEYWORD
import com.cipherwell.notezero.code.TokenType.NUMBER
import com.cipherwell.notezero.code.TokenType.OPERATOR
import com.cipherwell.notezero.code.TokenType.PROPERTY
import com.cipherwell.notezero.code.TokenType.PUNCTUATION
import com.cipherwell.notezero.code.TokenType.STRING

/**
 * POSIX/bash shell lexer.
 *
 * - `#` comments (only at a token boundary — `foo#bar` is one word, as in bash);
 * - single-quoted strings (no escapes), double-quoted strings segmented into literal
 *   [TokenType.STRING] pieces and [TokenType.PROPERTY] expansions;
 * - parameter expansion `$VAR`, `${...}`, `$1`, `$?`, `$(...)` and backtick command
 *   substitution as [TokenType.PROPERTY];
 * - redirections/pipes/separators as [TokenType.OPERATOR], brackets as PUNCTUATION;
 * - `VAR=value` names as [TokenType.PROPERTY] with `=` as OPERATOR;
 * - best-effort heredoc (`<<EOF`, `<<-EOF`, `<<"EOF"`): the body is one [TokenType.STRING]
 *   span and the terminator line is [TokenType.IDENTIFIER].
 *
 * Work is single pass and bounded by the input length; an unterminated heredoc/string simply
 * runs to the end of the text.
 */
internal class ShellLexer : BaseLexer(CodeLanguage.SHELL) {

    private companion object {
        /** Characters that terminate a shell word (quoting/meta characters are handled separately). */
        const val WORD_STOP = " \t\r\n|&;<>(){}[]$'\"`"

        const val OPERATOR_CHARS = "|&;<>!"

        const val PUNCTUATION_CHARS = "(){}[]"

        val KEYWORDS: Set<String> = setOf(
            "alias", "bg", "break", "builtin", "case", "cd", "command", "continue", "declare",
            "dirs", "do", "done", "echo", "elif", "else", "enable", "esac", "eval", "exec", "exit",
            "export", "false", "fg", "fi", "for", "function", "hash", "help", "history", "if", "in",
            "jobs", "kill", "let", "local", "logout", "mapfile", "popd", "printf", "pushd", "pwd",
            "read", "readarray", "readonly", "return", "select", "set", "shift", "shopt", "source",
            "test", "then", "time", "times", "trap", "true", "type", "typeset", "ulimit", "umask",
            "unalias", "unset", "until", "wait", "while",
        )

        val HEX_DIGITS: Set<Char> = "0123456789abcdefABCDEF".toSet()
    }

    private var heredocDelimiter: String? = null
    private var heredocStripTabs: Boolean = false

    override fun resetState() {
        heredocDelimiter = null
        heredocStripTabs = false
    }

    override fun scan() {
        while (pos < length && !halted) {
            val c = text[pos]
            when {
                c == '\n' -> {
                    pos++
                    if (heredocDelimiter != null) consumeHeredocBody()
                }
                isSpaceOrNewline(c) -> pos++
                c == '#' && isCommentStart() -> lineComment()
                c == '\'' -> singleQuotedString()
                c == '"' -> doubleQuotedString()
                c == '`' -> backtickSubstitution()
                c == '$' -> expansion()
                c == '\\' -> backslash()
                c == '<' && peek(1) == '<' && peek(2) != '<' -> heredocStart()
                isAsciiDigit(c) -> number()
                c in OPERATOR_CHARS -> operatorRun()
                c in PUNCTUATION_CHARS -> punctuation()
                else -> word()
            }
        }
    }

    private fun isCommentStart(): Boolean =
        pos == 0 || text[pos - 1] in " \t\r\n;|&({"

    // --------------------------------------------------------------- words & assignments

    private fun word() {
        val start = pos
        while (pos < length && text[pos] !in WORD_STOP) pos++
        if (pos == start) {
            pos++ // defensive: guarantee progress
            return
        }
        val equalsIndex = text.indexOf('=', start)
        if (equalsIndex in (start + 1) until pos && isVariableName(start, equalsIndex)) {
            emit(start, equalsIndex, PROPERTY)
            emit(equalsIndex, equalsIndex + 1, OPERATOR)
            pos = equalsIndex + 1
            return
        }
        val value = text.substring(start, pos)
        emit(start, pos, if (value in KEYWORDS) KEYWORD else IDENTIFIER)
    }

    private fun isVariableName(from: Int, to: Int): Boolean {
        if (from >= to) return false
        if (!isIdentifierStart(text[from])) return false
        for (i in from + 1 until to) if (!isIdentifierPart(text[i])) return false
        return true
    }

    // --------------------------------------------------------------- strings

    private fun singleQuotedString() {
        val start = pos
        pos++ // opening '
        while (pos < length && !halted && text[pos] != '\'') pos++
        if (pos < length) pos++
        if (pos > length) pos = length
        emit(start, pos, STRING)
    }

    private fun doubleQuotedString() {
        val start = pos
        pos++ // opening "
        var segmentStart = start
        while (pos < length && !halted) {
            val c = text[pos]
            when {
                c == '"' -> {
                    pos++
                    emit(segmentStart, pos, STRING)
                    return
                }
                c == '\\' -> pos += 2
                c == '$' -> {
                    emit(segmentStart, pos, STRING)
                    expansion()
                    segmentStart = pos
                }
                c == '`' -> {
                    emit(segmentStart, pos, STRING)
                    backtickSubstitution()
                    segmentStart = pos
                }
                else -> pos++
            }
        }
        if (pos > length) pos = length
        emit(segmentStart, pos, STRING) // unterminated: colour to end of input
    }

    private fun backtickSubstitution() {
        val start = pos
        pos++ // opening `
        while (pos < length && !halted) {
            val c = text[pos]
            when {
                c == '\\' -> pos += 2
                c == '`' -> {
                    pos++
                    break
                }
                else -> pos++
            }
        }
        if (pos > length) pos = length
        emit(start, pos, PROPERTY)
    }

    private fun backslash() {
        val start = pos
        if (isLineBreak(peek(1))) {
            emit(start, start + 1, OPERATOR) // line continuation
            pos++
        } else if (pos + 1 < length) {
            emit(start, start + 2, PUNCTUATION) // escaped literal character
            pos += 2
        } else {
            emit(start, start + 1, PUNCTUATION)
            pos++
        }
    }

    // --------------------------------------------------------------- expansions

    private fun expansion() {
        val start = pos
        when {
            peek(1) == '{' -> {
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
                        c == '\\' -> pos += 2
                        c == '\'' -> skipSingleQuoted()
                        c == '"' -> skipDoubleQuoted()
                        c == '`' -> skipBacktick()
                        else -> pos++
                    }
                }
                if (pos > length) pos = length
                emit(start, pos, PROPERTY)
            }
            peek(1) == '(' -> {
                pos += 2
                var depth = 1
                while (pos < length && depth > 0 && !halted) {
                    val c = text[pos]
                    when {
                        c == '(' -> {
                            depth++
                            pos++
                        }
                        c == ')' -> {
                            depth--
                            pos++
                        }
                        c == '\\' -> pos += 2
                        c == '\'' -> skipSingleQuoted()
                        c == '"' -> skipDoubleQuoted()
                        c == '`' -> skipBacktick()
                        else -> pos++
                    }
                }
                if (pos > length) pos = length
                emit(start, pos, PROPERTY)
            }
            isIdentifierStart(peek(1)) -> {
                pos++ // '$'
                while (pos < length && isIdentifierPart(text[pos])) pos++
                emit(start, pos, PROPERTY)
            }
            peek(1) in "0123456789@*#?\$!-" -> {
                pos += 2
                emit(start, pos, PROPERTY)
            }
            else -> {
                pos++ // literal '$'
                emit(start, pos, PUNCTUATION)
            }
        }
    }

    private fun skipSingleQuoted() {
        pos++ // opening '
        while (pos < length && text[pos] != '\'') pos++
        if (pos < length) pos++
    }

    private fun skipDoubleQuoted() {
        pos++ // opening "
        while (pos < length) {
            val c = text[pos]
            when {
                c == '\\' -> pos += 2
                c == '"' -> {
                    pos++
                    return
                }
                else -> pos++
            }
        }
        if (pos > length) pos = length
    }

    private fun skipBacktick() {
        pos++ // opening `
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

    // --------------------------------------------------------------- heredoc

    private fun heredocStart() {
        val start = pos
        pos += 2 // "<<"
        var stripTabs = false
        if (peek() == '-') {
            stripTabs = true
            pos++
        }
        emit(start, pos, OPERATOR)
        while (pos < length && (text[pos] == ' ' || text[pos] == '\t')) pos++
        if (pos >= length) return
        when (val c = text[pos]) {
            '\'', '"' -> {
                val tokenStart = pos
                val delimiterStart = tokenStart + 1
                var scan = delimiterStart
                while (scan < length && text[scan] != c && !isLineBreak(text[scan])) scan++
                if (scan > delimiterStart) heredocDelimiter = text.substring(delimiterStart, scan)
                pos = scan
                if (pos < length && text[pos] == c) pos++
                emit(tokenStart, pos, STRING)
            }
            else -> {
                val delimiterStart = pos
                while (pos < length && text[pos] !in WORD_STOP) pos++
                val delimiter = text.substring(delimiterStart, pos)
                if (delimiter.isNotEmpty()) heredocDelimiter = delimiter
                if (pos > delimiterStart) emit(delimiterStart, pos, IDENTIFIER)
            }
        }
        heredocStripTabs = stripTabs
    }

    private fun consumeHeredocBody() {
        val delimiter = heredocDelimiter ?: return
        val stripTabs = heredocStripTabs
        heredocDelimiter = null
        heredocStripTabs = false
        val bodyStart = pos
        var lineStart = pos
        while (lineStart <= length) {
            var lineEnd = text.indexOf('\n', lineStart)
            if (lineEnd < 0) lineEnd = length
            var contentStart = lineStart
            if (stripTabs) while (contentStart < lineEnd && text[contentStart] == '\t') contentStart++
            var contentEnd = lineEnd
            if (contentEnd > contentStart && text[contentEnd - 1] == '\r') contentEnd--
            if (text.substring(contentStart, contentEnd) == delimiter) {
                if (lineStart > bodyStart) emit(bodyStart, lineStart, STRING)
                if (contentEnd > contentStart) emit(contentStart, contentEnd, IDENTIFIER)
                pos = contentEnd
                return
            }
            if (lineEnd >= length) break
            lineStart = lineEnd + 1
        }
        // Unterminated heredoc: everything left is body text.
        if (length > bodyStart) emit(bodyStart, length, STRING)
        pos = length
    }

    // --------------------------------------------------------------- operators & numbers

    private fun operatorRun() {
        val start = pos
        pos++
        while (pos < length && text[pos] in OPERATOR_CHARS) pos++
        emit(start, pos, OPERATOR)
    }

    private fun punctuation() {
        val start = pos
        pos++
        emit(start, pos, PUNCTUATION)
    }

    private fun number() {
        val start = pos
        if (peek() == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            pos += 2
            while (pos < length && (text[pos] in HEX_DIGITS || text[pos] == '_')) pos++
        } else {
            while (pos < length && (isAsciiDigit(text[pos]) || text[pos] == '_')) pos++
            if (peek() == '.' && isAsciiDigit(peek(1))) {
                pos++
                while (pos < length && (isAsciiDigit(text[pos]) || text[pos] == '_')) pos++
            }
        }
        emit(start, pos, NUMBER)
    }
}
