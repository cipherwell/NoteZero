package com.cipherwell.notezero.ui.editor

/**
 * Cursor-aware, pure Markdown table editing used by the editor's table toolbar.
 *
 * The editor stores Markdown as the source of truth, so "insert table" and
 * "add/remove row/column" are plain text transforms over the body string plus the current
 * selection. Everything here is JVM-only (no Android/Compose imports) and returns null
 * instead of throwing when the cursor is not inside a table or the edit would destroy it
 * (a table always keeps at least one column; the header/delimiter rows cannot be removed).
 *
 * Tables are recognised exactly as [com.cipherwell.notezero.model.MarkdownBlockCodec]
 * parses them: a row line starts with `|`, and the second line is a delimiter row. Cell
 * text uses the codec's escaping (`\|` for a literal pipe, `\\` for a backslash) so
 * rewrites of an existing table preserve its content.
 */
object MarkdownTableEdit {

    /** Rows/columns are clamped to this range by the dialog and by [insertTable]. */
    const val MAX_SIZE: Int = 20

    /** A rewritten body plus the selection/cursor offset that should follow the edit. */
    data class Result(val text: String, val cursor: Int)

    /**
     * A parsed table block containing [cursorLine] (a zero-based line index). Line indices
     * are inclusive of [startLine] and exclusive of [endLineExclusive].
     */
    data class Table(
        val startLine: Int,
        val endLineExclusive: Int,
        val cursorLine: Int,
        val header: List<String>,
        val delimiter: List<String>,
        val rows: List<List<String>>,
    ) {
        val columnCount: Int get() = header.size

        /** True when the cursor sits on the header or delimiter row. */
        val cursorOnHeaderOrDelimiter: Boolean get() = cursorLine <= startLine + 1
    }

    private val DELIMITER_ROW = Regex("""^\|?[ \t]*:?-+:?[ \t]*(\|[ \t]*:?-+:?[ \t]*)*\|?$""")

    // ---- insertion -----------------------------------------------------------

    /**
     * Inserts a canonical Markdown table at [selectionStart]..[selectionEnd] (the selection
     * is replaced). The table gets [rows] empty body rows, [cols] columns and header cells
     * from [headers] (blank cells when the caller's "include header" toggle is off and the
     * list is empty). The returned cursor is placed just after the table.
     *
     * Blank-line separation from the surrounding blocks is added when needed, so the
     * inserted table parses as a table rather than merging with a paragraph.
     */
    fun insertTable(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        rows: Int,
        cols: Int,
        headers: List<String> = emptyList(),
    ): Result {
        val rowCount = rows.coerceIn(1, MAX_SIZE)
        val columnCount = cols.coerceIn(1, MAX_SIZE)
        val headerCells = List(columnCount) { index -> headers.getOrElse(index) { "" } }
        val headerLine = renderRow(headerCells)
        val delimiterLine = renderRow(List(columnCount) { "---" })
        val emptyRow = List(columnCount) { "" }
        val bodyLines = List(rowCount) { renderRow(emptyRow) }
        val table = (listOf(headerLine, delimiterLine) + bodyLines).joinToString("\n")

        val start = selectionStart.coerceIn(0, text.length)
        val end = selectionEnd.coerceIn(start, text.length)
        val before = text.substring(0, start)
        val after = text.substring(end)
        val prefix = when {
            before.isEmpty() -> ""
            before.endsWith("\n\n") -> ""
            before.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        val suffix = when {
            after.isEmpty() -> ""
            after.startsWith("\n\n") -> ""
            after.startsWith("\n") -> "\n"
            else -> "\n\n"
        }
        val inserted = before + prefix + table
        return Result(text = inserted + suffix + after, cursor = inserted.length)
    }

    // ---- lookup --------------------------------------------------------------

    /** The table block containing [cursor] (by line), or null when the cursor is elsewhere. */
    fun tableAt(text: String, cursor: Int): Table? {
        val lines = text.split("\n")
        if (lines.isEmpty()) return null
        val starts = lineStarts(lines)
        val safeCursor = cursor.coerceIn(0, text.length)
        var cursorLine = lines.lastIndex
        for (index in lines.indices) {
            val lineEnd = starts[index] + lines[index].length
            if (safeCursor <= lineEnd || index == lines.lastIndex) {
                cursorLine = index
                break
            }
        }

        for (start in cursorLine downTo 0) {
            if (!isRowLine(lines[start])) continue
            if (!isDelimiterRow(lines.getOrNull(start + 1) ?: continue)) continue
            var end = start + 2
            while (end < lines.size && isRowLine(lines[end])) end++
            if (cursorLine !in start until end) continue
            val header = parseCells(lines[start])
            if (header.isEmpty()) continue
            return Table(
                startLine = start,
                endLineExclusive = end,
                cursorLine = cursorLine,
                header = header,
                delimiter = parseCells(lines[start + 1]),
                rows = (start + 2 until end).map { parseCells(lines[it]) },
            )
        }
        return null
    }

    // ---- row / column edits --------------------------------------------------

    /** Inserts an empty body row after the cursor's row (or right after the delimiter). */
    fun addRow(text: String, cursor: Int): Result? {
        val table = tableAt(text, cursor) ?: return null
        val lines = text.split("\n").toMutableList()
        val insertAt = when {
            table.cursorOnHeaderOrDelimiter -> table.startLine + 2
            else -> table.cursorLine + 1
        }.coerceIn(table.startLine + 2, table.endLineExclusive)
        lines.add(insertAt, renderRow(List(table.columnCount) { "" }))
        return resultOf(lines, insertAt)
    }

    /** Removes the body row under the cursor; the header/delimiter rows are never removed. */
    fun removeRow(text: String, cursor: Int): Result? {
        val table = tableAt(text, cursor) ?: return null
        if (table.cursorOnHeaderOrDelimiter) return null
        val lines = text.split("\n").toMutableList()
        lines.removeAt(table.cursorLine)
        val nextLine = table.cursorLine.coerceAtMost(lines.lastIndex)
        return resultOf(lines, nextLine)
    }

    /** Appends one empty cell to every line of the table containing [cursor]. */
    fun addColumn(text: String, cursor: Int): Result? {
        val table = tableAt(text, cursor) ?: return null
        val lines = text.split("\n").toMutableList()
        val oldStarts = lineStarts(text.split("\n"))
        val intraLine = (cursor.coerceIn(0, text.length) - oldStarts[table.cursorLine]).coerceAtLeast(0)
        val columnCount = table.columnCount
        for (index in table.startLine until table.endLineExclusive) {
            val cells = expand(parseCells(lines[index]), columnCount).toMutableList()
            // The delimiter row needs a real `---` cell, otherwise the grown table stops
            // parsing as a table (the codec requires the delimiter row to match exactly).
            cells.add(if (index == table.startLine + 1) "---" else "")
            lines[index] = renderRow(cells)
        }
        val newStarts = lineStarts(lines)
        val lineStart = newStarts[table.cursorLine]
        return Result(
            text = lines.joinToString("\n"),
            cursor = (lineStart + intraLine).coerceIn(lineStart, lineStart + lines[table.cursorLine].length),
        )
    }

    /** Removes the column under the cursor; the last remaining column cannot be removed. */
    fun removeColumn(text: String, cursor: Int): Result? {
        val table = tableAt(text, cursor) ?: return null
        if (table.columnCount <= 1) return null
        val lines = text.split("\n").toMutableList()
        val starts = lineStarts(lines)
        val lineStart = starts[table.cursorLine]
        val intraLine = (cursor.coerceIn(0, text.length) - lineStart).coerceAtLeast(0)
        val column = columnAt(lines[table.cursorLine], intraLine, table.columnCount)
        for (index in table.startLine until table.endLineExclusive) {
            val cells = parseCells(lines[index]).toMutableList()
            if (column in cells.indices) cells.removeAt(column)
            lines[index] = renderRow(cells)
        }
        val newText = lines.joinToString("\n")
        val newLineStart = lineStarts(lines)[table.cursorLine]
        return Result(
            text = newText,
            cursor = (newLineStart + intraLine)
                .coerceIn(newLineStart, newLineStart + lines[table.cursorLine].length),
        )
    }

    // ---- parsing / rendering -------------------------------------------------

    private fun isRowLine(line: String): Boolean = line.trimStart().startsWith("|")

    private fun isDelimiterRow(line: String): Boolean =
        line.contains("|") && DELIMITER_ROW.matches(line.trim())

    private fun expand(cells: List<String>, count: Int): List<String> =
        if (cells.size >= count) cells else cells + List(count - cells.size) { "" }

    /**
     * Mirrors the codec's cell splitter: strips the optional outer pipes, splits on
     * unescaped `|`, and unescapes `\x` to `x` (so `\|` becomes a literal pipe again).
     */
    private fun parseCells(line: String): List<String> {
        var body = line.trim()
        if (body.startsWith("|")) body = body.substring(1)
        if (body.endsWith("|") && !body.endsWith("\\|")) body = body.dropLast(1)
        val cells = ArrayList<String>()
        val cell = StringBuilder()
        var escaped = false
        for (character in body) {
            when {
                escaped -> {
                    cell.append(character)
                    escaped = false
                }

                character == '\\' -> escaped = true
                character == '|' -> {
                    cells.add(cell.toString().trim())
                    cell.setLength(0)
                }

                else -> cell.append(character)
            }
        }
        if (escaped) cell.append('\\')
        if (cell.isNotEmpty() || cells.isEmpty() || body.endsWith("|")) {
            cells.add(cell.toString().trim())
        }
        return cells
    }

    private fun renderRow(cells: List<String>): String =
        "| " + cells.joinToString(" | ") {
            it.replace("\\", "\\\\").replace("|", "\\|")
        } + " |"

    /**
     * Column index of [cursor] inside a table row. Counts unescaped leading pipes: text in
     * the first cell is column 0 even though the line starts with a border pipe.
     */
    private fun columnAt(line: String, cursorInLine: Int, columnCount: Int): Int {
        val limit = cursorInLine.coerceIn(0, line.length)
        var pipes = 0
        var escaped = false
        for (index in 0 until limit) {
            val character = line[index]
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '|' -> pipes++
            }
        }
        val leading = if (line.trimStart().startsWith("|")) 1 else 0
        return (pipes - leading).coerceIn(0, (columnCount - 1).coerceAtLeast(0))
    }

    private fun lineStarts(lines: List<String>): IntArray {
        val starts = IntArray(lines.size)
        var offset = 0
        for (index in lines.indices) {
            starts[index] = offset
            offset += lines[index].length + 1
        }
        return starts
    }

    private fun lineStart(lines: List<String>, index: Int): Int {
        if (index <= 0) return 0
        if (index >= lines.size) {
            return (lines.sumOf { it.length + 1 } - 1).coerceAtLeast(0)
        }
        var offset = 0
        for (lineIndex in 0 until index) offset += lines[lineIndex].length + 1
        return offset
    }

    private fun resultOf(lines: List<String>, cursorLine: Int): Result {
        val text = lines.joinToString("\n")
        return Result(text = text, cursor = lineStart(lines, cursorLine).coerceIn(0, text.length))
    }
}
