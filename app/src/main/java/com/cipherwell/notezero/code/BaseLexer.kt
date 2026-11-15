package com.cipherwell.notezero.code

import java.util.ArrayList

/**
 * Shared cursor-based scanning infrastructure for the pure-Kotlin lexers.
 *
 * A lexer instance owns scanner state, so [tokenize] is `@Synchronized`: several UI callers may
 * share the singleton from [CodeLexers] without corrupting each other. Only one tokenization
 * runs at a time per language; lexing 200 KB is a few milliseconds, so contention is irrelevant.
 *
 * Subclasses implement [scan], advancing [pos] and calling [emit]. They must:
 * - always advance [pos] (no infinite loops);
 * - stop when [halted] becomes true (token budget exhausted);
 * - never read outside the text (use [charAt]/[startsWith] helpers, which are bounds safe).
 *
 * All offsets are UTF-16 indices, clamped into `[0, text.length]` by [emit], so a lexer bug
 * cannot produce an out-of-bounds token for the UI.
 */
internal abstract class BaseLexer(override val language: CodeLanguage) : CodeLexer {

    /** The text currently being scanned. Valid only inside [scan]. */
    protected var text: String = ""
        private set

    /** Length of the text currently being scanned. */
    protected var length: Int = 0
        private set

    /** Current scan position. */
    protected var pos: Int = 0

    private val produced: ArrayList<SyntaxToken> = ArrayList(256)

    /** Set once the [MAX_TOKENS] budget is exhausted; scanning loops must stop. */
    protected var halted: Boolean = false
        private set

    @Synchronized
    final override fun tokenize(text: String): List<SyntaxToken> {
        this.text = text
        this.length = text.length
        this.pos = 0
        this.produced.clear()
        this.halted = false
        resetState()
        if (length > 0) {
            try {
                scan()
            } catch (exception: Exception) {
                // Defence in depth: no user input may make highlighting throw. A scanner bug
                // truncates the token list instead of crashing the editor. Fatal JVM errors
                // (OOM, StackOverflow) still propagate deliberately.
                halted = true
            }
        }
        return if (produced.isEmpty()) emptyList() else produced.toList()
    }

    /** Single scan over [text]; subclass implementation. */
    protected abstract fun scan()

    /** Hook for lexers that carry work-in-progress state (e.g. a pending heredoc body). */
    protected open fun resetState() {}

    // ---------------------------------------------------------------- text access

    /** Char at [index], or `'\u0000'` when out of bounds. */
    protected fun charAt(index: Int): Char = if (index in 0 until length) text[index] else '\u0000'

    /** Char at `pos + offset`, or `'\u0000'` when out of bounds. */
    protected fun peek(offset: Int = 0): Char {
        val index = pos + offset
        return if (index in 0 until length) text[index] else '\u0000'
    }

    /** True when [prefix] starts at [index] and fits in the text. */
    protected fun startsWith(prefix: String, index: Int = pos): Boolean =
        index >= 0 && index + prefix.length <= length && text.startsWith(prefix, index)

    protected fun isAsciiDigit(char: Char): Boolean = char in '0'..'9'

    protected fun isAsciiLetter(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z'

    /** Identifier-ish part character: Unicode letter or digit, or an extra allowed char. */
    protected fun isIdentifierPart(char: Char, extra: Char? = null): Boolean =
        char == '_' || char.isLetterOrDigit() || (extra != null && char == extra)

    /** Identifier start character: Unicode letter, `_`, or an extra allowed char. */
    protected fun isIdentifierStart(char: Char, extra: Char? = null): Boolean =
        char == '_' || char.isLetter() || (extra != null && char == extra)

    protected fun isLineBreak(char: Char): Boolean = char == '\n' || char == '\r'

    protected fun isSpaceOrNewline(char: Char): Boolean =
        char == ' ' || char == '\t' || char == '\n' || char == '\r' ||
            char == '\u000B' || char == '\u000C'

    // ---------------------------------------------------------------- emission

    /** Last emitted token type, or null at the start of a scan. */
    protected fun lastTokenType(): TokenType? = produced.lastOrNull()?.type

    /** Source text of the last emitted token, or null. */
    protected fun lastTokenText(): String? = produced.lastOrNull()?.let { text.substring(it.start, it.end) }

    /**
     * Emits `[start, end)` as [type], clamped to the text. Whitespace-only or empty spans are
     * dropped. Once [MAX_TOKENS] tokens exist the scan is [halted] and nothing more is added.
     */
    protected fun emit(start: Int, end: Int, type: TokenType) {
        if (halted) return
        if (produced.size >= MAX_TOKENS) {
            halted = true
            return
        }
        val safeStart = start.coerceIn(0, length)
        val safeEnd = end.coerceIn(0, length)
        if (safeEnd <= safeStart) return
        produced.add(SyntaxToken(safeStart, safeEnd, type))
    }

    /** Skips to the end of the current line (not consuming the line break). */
    protected fun skipToLineEnd() {
        while (pos < length && !isLineBreak(text[pos])) pos++
    }

    /** Skips `//`/`#`-style comments: from [pos] (at the marker) to the end of the line. */
    protected fun lineComment(start: Int = pos) {
        pos = start
        skipToLineEnd()
        emit(start, pos, TokenType.COMMENT)
    }
}
