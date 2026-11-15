package com.cipherwell.notezero.code

import com.cipherwell.notezero.code.TokenType.CODE_SPAN
import com.cipherwell.notezero.code.TokenType.HEADING
import com.cipherwell.notezero.code.TokenType.LINK

/**
 * Markdown lexer: ATX headings, fenced code blocks (``` and ~~~), inline code spans and
 * links/images. Plain prose is intentionally left uncoloured (no token).
 *
 * A fenced block is emitted as one [TokenType.CODE_SPAN] spanning the opening fence line
 * through the closing fence line (including newlines in between); an unterminated fence runs
 * to the end of the document. Inline constructs are matched line-locally and best-effort:
 * unmatched markers stay plain text rather than being swallowed.
 */
internal class MarkdownLexer : BaseLexer(CodeLanguage.MARKDOWN) {

    private var fenceTokenStart: Int = -1
    private var fenceChar: Char = '\u0000'
    private var fenceRun: Int = 0

    override fun resetState() {
        fenceTokenStart = -1
        fenceChar = '\u0000'
        fenceRun = 0
    }

    override fun scan() {
        while (pos < length && !halted) {
            val lineStart = pos
            val lineEnd = lineContentEnd(lineStart)
            if (fenceTokenStart >= 0) {
                if (isClosingFence(lineStart, lineEnd)) {
                    emit(fenceTokenStart, lineEnd, CODE_SPAN)
                    fenceTokenStart = -1
                }
            } else if (isOpeningFence(lineStart, lineEnd)) {
                // Fence state recorded by isOpeningFence; block token is emitted when it closes.
            } else {
                val headingStart = headingStart(lineStart, lineEnd)
                if (headingStart >= 0) {
                    emit(headingStart, lineEnd, HEADING)
                } else {
                    scanInline(lineStart, lineEnd)
                }
            }
            pos = nextLineStart(lineStart)
        }
        if (fenceTokenStart >= 0) emit(fenceTokenStart, length, CODE_SPAN) // unterminated fence
    }

    private fun lineContentEnd(from: Int): Int {
        var end = text.indexOf('\n', from)
        if (end < 0) end = length
        if (end > from && text[end - 1] == '\r') end--
        return end
    }

    private fun nextLineStart(from: Int): Int {
        val newline = text.indexOf('\n', from)
        return if (newline < 0) length else newline + 1
    }

    // --------------------------------------------------------------- fenced code blocks

    private fun isOpeningFence(lineStart: Int, lineEnd: Int): Boolean {
        var i = lineStart
        var spaces = 0
        while (i < lineEnd && text[i] == ' ' && spaces < 3) {
            i++
            spaces++
        }
        if (i >= lineEnd) return false
        val marker = text[i]
        if (marker != '`' && marker != '~') return false
        var run = 0
        while (i < lineEnd && text[i] == marker) {
            i++
            run++
        }
        if (run < 3) return false
        fenceTokenStart = lineStart
        fenceChar = marker
        fenceRun = run
        return true
    }

    private fun isClosingFence(lineStart: Int, lineEnd: Int): Boolean {
        var i = lineStart
        var spaces = 0
        while (i < lineEnd && text[i] == ' ' && spaces < 3) {
            i++
            spaces++
        }
        if (i >= lineEnd || text[i] != fenceChar) return false
        var run = 0
        while (i < lineEnd && text[i] == fenceChar) {
            i++
            run++
        }
        if (run < fenceRun) return false
        while (i < lineEnd) {
            if (text[i] != ' ') return false
            i++
        }
        return true
    }

    // --------------------------------------------------------------- ATX headings

    /** Returns the index of the first `#` of a valid ATX heading on this line, or -1. */
    private fun headingStart(lineStart: Int, lineEnd: Int): Int {
        var i = lineStart
        var spaces = 0
        while (i < lineEnd && text[i] == ' ' && spaces < 3) {
            i++
            spaces++
        }
        if (i >= lineEnd || text[i] != '#') return -1
        val hashStart = i
        var hashes = 0
        while (i < lineEnd && text[i] == '#') {
            i++
            hashes++
        }
        if (hashes > 6) return -1
        if (i < lineEnd && text[i] != ' ' && text[i] != '\t') return -1
        return hashStart
    }

    // --------------------------------------------------------------- inline constructs

    private fun scanInline(from: Int, to: Int) {
        var i = from
        while (i < to && !halted) {
            val c = text[i]
            when {
                c == '`' -> {
                    var openEnd = i
                    while (openEnd < to && text[openEnd] == '`') openEnd++
                    val run = openEnd - i
                    val closeStart = findBacktickRun(openEnd, to, run)
                    if (closeStart >= 0) {
                        emit(i, closeStart + run, CODE_SPAN)
                        i = closeStart + run
                    } else {
                        i = openEnd // unmatched opener: literal text
                    }
                }
                c == '[' -> {
                    val end = linkEnd(i, to)
                    if (end > i) {
                        emit(i, end, LINK)
                        i = end
                    } else {
                        i++
                    }
                }
                c == '!' && i + 1 < to && text[i + 1] == '[' -> {
                    val end = linkEnd(i + 1, to)
                    if (end > i + 1) {
                        emit(i, end, LINK)
                        i = end
                    } else {
                        i++
                    }
                }
                c == '<' -> {
                    val end = autolinkEnd(i, to)
                    if (end > i) {
                        emit(i, end, LINK)
                        i = end
                    } else {
                        i++
                    }
                }
                else -> i++
            }
        }
    }

    private fun findBacktickRun(from: Int, to: Int, run: Int): Int {
        var i = from
        while (i < to) {
            if (text[i] == '`') {
                val start = i
                var count = 0
                while (i < to && text[i] == '`') {
                    i++
                    count++
                }
                if (count == run) return start
            } else {
                i++
            }
        }
        return -1
    }

    /**
     * Matches `[text](destination)`, `[text][reference]` or `![alt](...)` starting at
     * [bracketStart] (the `[`). Returns the exclusive end offset, or -1 when malformed.
     */
    private fun linkEnd(bracketStart: Int, to: Int): Int {
        var depth = 1
        var i = bracketStart + 1
        while (i < to && depth > 0) {
            when (text[i]) {
                '\\' -> i += 2
                '[' -> {
                    depth++
                    i++
                }
                ']' -> {
                    depth--
                    i++
                }
                else -> i++
            }
        }
        if (depth != 0) return -1
        val afterText = i
        if (afterText >= to) return -1
        return when (text[afterText]) {
            '(' -> balancedEnd(afterText + 1, to, '(', ')')
            '[' -> balancedEnd(afterText + 1, to, '[', ']')
            else -> -1
        }
    }

    private fun balancedEnd(from: Int, to: Int, open: Char, close: Char): Int {
        var depth = 1
        var i = from
        while (i < to && depth > 0) {
            when (text[i]) {
                '\\' -> i += 2
                open -> {
                    depth++
                    i++
                }
                close -> {
                    depth--
                    i++
                }
                else -> i++
            }
        }
        return if (depth == 0) i else -1
    }

    private fun autolinkEnd(start: Int, to: Int): Int {
        if (!startsWith("<http://", start) && !startsWith("<https://", start) &&
            !startsWith("<mailto:", start)
        ) {
            return -1
        }
        var i = start + 1
        while (i < to) {
            if (text[i] == '>') return i + 1
            i++
        }
        return -1
    }
}
