package com.cipherwell.notezero.code

/**
 * Immutable line-start index for a source text.
 *
 * Lines are separated by `\n`; a preceding `\r` is treated as part of the line terminator
 * (CRLF counts as one break). The index is a compact `IntArray` of line-start offsets, so
 * lookups are O(log n) and building is a single O(n) pass.
 *
 * A text ending in a newline has one extra (empty) final line, matching editor behaviour:
 * `"a\n"` has two lines and a cursor can sit on line 1 at offset 2. The empty text has one
 * (empty) line.
 */
class LineIndex private constructor(
    private val lineStarts: IntArray,
    private val totalLength: Int,
) {

    /** Number of lines; always >= 1. */
    val lineCount: Int get() = lineStarts.size

    /** Length of the indexed text. */
    val textLength: Int get() = totalLength

    /** 0-based line containing [offset]; out-of-range offsets are clamped to the nearest line. */
    fun lineOf(offset: Int): Int {
        val target = offset.coerceIn(0, totalLength)
        var low = 0
        var high = lineStarts.size - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (lineStarts[middle] <= target) low = middle else high = middle - 1
        }
        return low
    }

    /** Offset of the first character of [line]; out-of-range lines clamp to the first/last line. */
    fun startOfLine(line: Int): Int {
        val index = line.coerceIn(0, lineStarts.size - 1)
        return lineStarts[index]
    }

    /**
     * Offset of the line break (or end of text) for [line], i.e. the exclusive end of the
     * line's content. For CRLF the returned offset points at `\r`, so a selection over the
     * line never includes the terminator. Out-of-range lines clamp to the first/last line.
     */
    fun endOfLine(text: String, line: Int): Int {
        val index = line.coerceIn(0, lineStarts.size - 1)
        val limit = minOf(text.length, totalLength)
        if (index == lineStarts.size - 1) return limit
        var end = minOf(lineStarts[index + 1], limit)
        val lineStart = minOf(lineStarts[index], limit)
        if (end > lineStart && end - 1 < text.length && text[end - 1] == '\n') end--
        if (end > lineStart && end - 1 < text.length && text[end - 1] == '\r') end--
        return end
    }

    companion object {
        /** Builds the index in one pass; never throws. */
        fun of(text: String): LineIndex {
            var lineCount = 1
            for (i in text.indices) if (text[i] == '\n') lineCount++
            val starts = IntArray(lineCount)
            var line = 1
            for (i in text.indices) if (text[i] == '\n') starts[line++] = i + 1
            return LineIndex(starts, text.length)
        }
    }
}
