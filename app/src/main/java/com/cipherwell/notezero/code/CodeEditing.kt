package com.cipherwell.notezero.code

import java.util.ArrayList

/**
 * A half-open selection range `[start, end)` in a text. Instances come from user selections, so
 * they may be reversed; helpers on [TextRange] normalize and clamp them.
 */
data class TextRange(val start: Int, val end: Int) {
    val length: Int get() = (end - start).coerceAtLeast(0)

    fun isEmpty(): Boolean = end <= start

    fun normalized(): TextRange = if (start <= end) this else TextRange(end, start)

    /** Clamps both offsets into `[0, textLength]` and orders them. */
    fun clampedTo(textLength: Int): TextRange {
        val ordered = normalized()
        val safeStart = ordered.start.coerceIn(0, textLength)
        val safeEnd = ordered.end.coerceIn(safeStart, textLength)
        return TextRange(safeStart, safeEnd)
    }

    /** True when [offset] falls strictly inside the range. */
    fun containsInside(offset: Int): Boolean = offset > start && offset < end
}

/**
 * Pure, stateless text-editing helpers shared by the Compose code editor.
 *
 * Everything is immutable (`String` in, `String`/ranges out); no Android or Compose types are
 * involved, so the behaviour is unit-testable on the JVM. Editing operations never throw on
 * arbitrary offsets/selections: out-of-range inputs are clamped.
 *
 * Indentation is expressed in "units": one tab (`\t`) when [indentUnit] is called with
 * `useTabs = true`, otherwise `tabWidth` spaces (clamped to 1..16).
 */
object CodeEditing {

    /** Default cap for [findMatches]; the editor's find bar can pass a smaller limit. */
    const val DEFAULT_MATCH_LIMIT: Int = 10_000

    private const val MIN_TAB_WIDTH = 1
    private const val MAX_TAB_WIDTH = 16

    /** Maximum supported nesting while matching brackets; beyond this the search gives up. */
    private const val MAX_BRACKET_DEPTH = 100_000

    // ------------------------------------------------------------------ indentation

    /** The text inserted for one indent level: `\t` or `tabWidth` spaces. */
    fun indentUnit(language: CodeLanguage, useTabs: Boolean, tabWidth: Int): String {
        val width = tabWidth.coerceIn(MIN_TAB_WIDTH, MAX_TAB_WIDTH)
        // The editor exposes a tab-vs-spaces preference; all languages follow it. The language
        // parameter is part of the frozen API so per-language overrides stay possible later.
        return if (useTabs) "\t" else " ".repeat(width)
    }

    /**
     * Text to insert at a newline given the line being split (auto-indent).
     *
     * The caller inserts a line break at [cursor] and then appends this string; the returned
     * value contains indentation only (no newline). Rules:
     *
     * - a blank current line returns `""` (repeated Enter does not accumulate indentation);
     * - the new line starts at the current line's leading whitespace;
     * - one extra indent unit after `{`, `(`, `[` in every language;
     * - one extra indent unit after `:` in Python only (suite headers, not dict colons — the
     *   caller may ignore/replace the suggestion in that rare case);
     * - one extra indent unit after an opening XML tag (`<div>`, `<ns:tag attr="x">`) for XML.
     */
    fun autoIndent(
        text: String,
        cursor: Int,
        language: CodeLanguage,
        useTabs: Boolean,
        tabWidth: Int,
    ): String {
        val index = LineIndex.of(text)
        val position = cursor.coerceIn(0, text.length)
        val line = index.lineOf(position)
        val lineStart = index.startOfLine(line)
        val lineEnd = index.endOfLine(text, line)
        val contentEnd = minOf(position, lineEnd)
        val before = text.substring(lineStart, contentEnd)
        val trimmed = before.trimEnd(' ', '\t')
        if (trimmed.isEmpty()) return "" // blank line: no indentation accumulation

        var leading = 0
        while (leading < before.length && (before[leading] == ' ' || before[leading] == '\t')) leading++
        val base = before.substring(0, leading)
        val unit = indentUnit(language, useTabs, tabWidth)
        val trigger = trimmed[trimmed.length - 1]
        val extraIndent = when (trigger) {
            '{', '(', '[' -> true
            ':' -> language == CodeLanguage.PYTHON
            '>' -> language == CodeLanguage.XML && opensMarkupTag(trimmed)
            else -> false
        }
        return if (extraIndent) base + unit else base
    }

    /** True when [trimmed] ends with an opening XML element tag (`` or ...). */
    private fun opensMarkupTag(trimmed: String): Boolean {
        if (!trimmed.endsWith(">")) return false
        val open = trimmed.lastIndexOf('<')
        if (open < 0) return false
        val inner = trimmed.substring(open + 1, trimmed.length - 1)
        if (inner.isEmpty()) return false
        if (inner.startsWith("/")) return false // closing tag
        if (inner.startsWith("!") || inner.startsWith("?")) return false // declaration / PI
        if (inner.endsWith("/")) return false // self-closing
        val first = inner[0]
        return first.isLetter() || first == '_' || first == ':'
    }

    /**
     * Indents (or outdents) every line touched by [selection].
     *
     * Returns the new text and the selection mapped onto it. When the selection ends exactly at
     * a line start, that line is not touched (standard editor behaviour). Blank lines are
     * indented like any other line; outdent removes one tab or up to one tab width of spaces.
     */
    fun indentSelection(
        text: String,
        selection: TextRange,
        outdent: Boolean,
        useTabs: Boolean,
        tabWidth: Int,
    ): Pair<String, TextRange> {
        val index = LineIndex.of(text)
        val ordered = selection.clampedTo(text.length)
        val start = ordered.start
        val end = ordered.end
        val startLine = index.lineOf(start)
        var endLine = index.lineOf(end)
        if (end > start && endLine > startLine && index.startOfLine(endLine) == end) endLine--
        if (startLine > endLine) return text to TextRange(start, end)

        val unit = indentUnit(CodeLanguage.PLAIN, useTabs, tabWidth)
        val spaceWidth = tabWidth.coerceIn(MIN_TAB_WIDTH, MAX_TAB_WIDTH)
        val builder = StringBuilder(text.length + (endLine - startLine + 1) * unit.length)
        var copyFrom = 0
        var newStart = start
        var newEnd = end
        var line = startLine
        while (line <= endLine) {
            val lineStart = index.startOfLine(line)
            val lineEnd = index.endOfLine(text, line)
            if (lineStart > copyFrom) builder.append(text, copyFrom, lineStart)
            if (outdent) {
                val remove = outdentAmount(text, lineStart, lineEnd, spaceWidth)
                builder.append(text, lineStart + remove, lineEnd)
                newStart = shiftForRemoval(start, newStart, lineStart, remove)
                newEnd = shiftForRemoval(end, newEnd, lineStart, remove)
            } else {
                builder.append(unit)
                builder.append(text, lineStart, lineEnd)
                if (start >= lineStart) newStart += unit.length
                if (end >= lineStart) newEnd += unit.length
            }
            copyFrom = lineEnd
            line++
        }
        if (copyFrom < text.length) builder.append(text, copyFrom, text.length)
        return builder.toString() to TextRange(newStart, newEnd)
    }

    private fun outdentAmount(text: String, lineStart: Int, lineEnd: Int, spaceWidth: Int): Int {
        if (lineStart >= lineEnd) return 0
        if (text[lineStart] == '\t') return 1
        var count = 0
        var i = lineStart
        while (i < lineEnd && text[i] == ' ' && count < spaceWidth) {
            i++
            count++
        }
        return count
    }

    /**
     * Maps an original offset to the new text for an outdent (removal) edit.
     * [current] is the already-shifted position; [original] the position before this edit.
     */
    private fun shiftForRemoval(
        original: Int,
        current: Int,
        removalStart: Int,
        removalLength: Int,
    ): Int = when {
        removalLength <= 0 -> current
        original <= removalStart -> current
        original >= removalStart + removalLength -> current - removalLength
        else -> current - (original - removalStart)
    }

    // ------------------------------------------------------------------ bracket matching

    /**
     * Finds the matching bracket for the cursor at [offset].
     *
     * The bracket under the cursor wins; otherwise the bracket immediately before it. Only
     * `()`, `[]` and `{}` are matched (comparison operators and XML angle brackets would be
     * ambiguous). Brackets inside strings, `//`/`#` line comments and slash-star block comments
     * are skipped. Returns the offset of the matching bracket, or null when there is none or
     * the cursor is inside a string/comment.
     *
     * Heuristic notes: a `#` only starts a comment at a word boundary (so `#privateField` in
     * JS is not a comment), and slash-star block comments are not treated as nested (Kotlin
     * allows nesting; the first comment terminator ends shielding early — best effort by design).
     */
    fun matchingBracket(text: String, offset: Int): Int? {
        if (text.isEmpty()) return null
        val position = offset.coerceIn(0, text.length)
        var anchor = -1
        if (position < text.length && isOpenBracket(text[position])) {
            anchor = position
        } else if (position > 0 && isOpenBracket(text[position - 1])) {
            anchor = position - 1
        }
        if (anchor < 0) {
            if (position < text.length && isCloseBracket(text[position])) {
                anchor = position
            } else if (position > 0 && isCloseBracket(text[position - 1])) {
                anchor = position - 1
            }
        }
        if (anchor < 0) return null
        val anchorChar = text[anchor]
        val isOpening = isOpenBracket(anchorChar)
        val openChar = if (isOpening) anchorChar else openFor(anchorChar)
        val closeChar = closeFor(openChar)

        // Walk from the start of the text to the anchor, tracking comments/strings and (for a
        // closing bracket) a stack of unmatched openers of the same kind.
        val state = ShieldState()
        var stack = IntArray(16)
        var stackSize = 0
        var i = 0
        while (i < anchor) {
            if (state.isNormal()) {
                val c = text[i]
                if (!isOpening && c == openChar) {
                    if (stackSize == stack.size) stack = stack.copyOf(stack.size * 2)
                    stack[stackSize++] = i
                } else if (!isOpening && c == closeChar) {
                    if (stackSize > 0) stackSize--
                }
            }
            i = stepShield(text, i, state)
        }
        if (!state.isNormal()) return null // anchor sits inside a string/comment

        if (!isOpening) {
            return if (stackSize > 0) stack[stackSize - 1] else null
        }

        var depth = 1
        var j = anchor + 1
        while (j < text.length && depth <= MAX_BRACKET_DEPTH) {
            if (state.isNormal()) {
                val c = text[j]
                if (c == openChar) {
                    depth++
                } else if (c == closeChar) {
                    depth--
                    if (depth == 0) return j
                }
            }
            j = stepShield(text, j, state)
        }
        return null
    }

    private fun isOpenBracket(c: Char): Boolean = c == '(' || c == '[' || c == '{'

    private fun isCloseBracket(c: Char): Boolean = c == ')' || c == ']' || c == '}'

    private fun closeFor(open: Char): Char = when (open) {
        '(' -> ')'
        '[' -> ']'
        '{' -> '}'
        else -> open
    }

    private fun openFor(close: Char): Char = when (close) {
        ')' -> '('
        ']' -> '['
        '}' -> '{'
        else -> close
    }

    /** Lexical shield state used by [matchingBracket]. */
    private class ShieldState {
        var lineComment: Boolean = false
        var blockComment: Boolean = false
        var quote: Char = '\u0000'
        var escaped: Boolean = false

        fun isNormal(): Boolean = !lineComment && !blockComment && quote == '\u0000'
    }

    /** Advances one shield-state step at [index]; returns the next index to inspect. */
    private fun stepShield(text: String, index: Int, state: ShieldState): Int {
        val c = text[index]
        if (state.lineComment) {
            if (c == '\n') state.lineComment = false
            return index + 1
        }
        if (state.blockComment) {
            if (c == '*' && index + 1 < text.length && text[index + 1] == '/') {
                state.blockComment = false
                return index + 2
            }
            return index + 1
        }
        if (state.quote != '\u0000') {
            if (state.escaped) {
                state.escaped = false
                return index + 1
            }
            if (c == '\\') {
                state.escaped = true
                return index + 1
            }
            if (c == state.quote) {
                state.quote = '\u0000'
                return index + 1
            }
            // Unterminated single-line string ends at the line break; template literals may span.
            if (c == '\n' && state.quote != '`') state.quote = '\u0000'
            return index + 1
        }
        return when {
            c == '/' && index + 1 < text.length && text[index + 1] == '/' -> {
                state.lineComment = true
                index + 2
            }
            c == '/' && index + 1 < text.length && text[index + 1] == '*' -> {
                state.blockComment = true
                index + 2
            }
            c == '#' && (index == 0 || text[index - 1] in " \t\r\n") -> {
                state.lineComment = true
                index + 1
            }
            c == '"' || c == '\'' || c == '`' -> {
                state.quote = c
                index + 1
            }
            else -> index + 1
        }
    }

    // ------------------------------------------------------------------ search & replace

    /**
     * Literal, non-overlapping find-all. Empty query returns an empty list; [limit] <= 0 also
     * returns empty. Results are ascending and non-overlapping.
     */
    fun findMatches(
        text: String,
        query: String,
        caseSensitive: Boolean,
        limit: Int = DEFAULT_MATCH_LIMIT,
    ): List<TextRange> {
        if (query.isEmpty() || limit <= 0 || text.isEmpty() || query.length > text.length) {
            return emptyList()
        }
        val matches = ArrayList<TextRange>(minOf(limit, 64))
        if (caseSensitive) {
            var index = text.indexOf(query)
            while (index >= 0 && matches.size < limit) {
                matches.add(TextRange(index, index + query.length))
                index = text.indexOf(query, index + query.length)
            }
        } else {
            var index = 0
            val lastStart = text.length - query.length
            while (index <= lastStart && matches.size < limit) {
                if (text.regionMatches(index, query, 0, query.length, ignoreCase = true)) {
                    matches.add(TextRange(index, index + query.length))
                    index += query.length
                } else {
                    index++
                }
            }
        }
        return matches
    }

    /**
     * Literal replace-all. The query and replacement are treated as plain text — `$1`, `\1` and
     * other regex metacharacters have no special meaning (no regex injection). An empty query
     * returns [text] unchanged.
     */
    fun replaceAll(
        text: String,
        query: String,
        replacement: String,
        caseSensitive: Boolean,
    ): String {
        if (query.isEmpty() || text.isEmpty() || query.length > text.length) return text
        return text.replace(query, replacement, ignoreCase = !caseSensitive)
    }

    /** Start offset of [line]; out-of-range lines clamp like [LineIndex.startOfLine]. */
    fun offsetForLine(text: String, line: Int): Int = LineIndex.of(text).startOfLine(line)
}
