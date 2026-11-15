package com.cipherwell.notezero.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the cursor-aware Markdown table helpers (insert, locate, add/remove
 * row and column) used by the editor toolbar.
 */
class MarkdownTablesTest {

    private val table = "| A | B |\n| --- | --- |\n| 1 | 2 |"

    private fun cursorIn(text: String, needle: String): Int {
        val index = text.indexOf(needle)
        assertTrue("needle '$needle' not in text", index >= 0)
        return index
    }

    // ---- insert --------------------------------------------------------------

    @Test
    fun `insert builds a canonical table with header and empty body rows`() {
        val result = MarkdownTableEdit.insertTable(
            text = "",
            selectionStart = 0,
            selectionEnd = 0,
            rows = 2,
            cols = 2,
            headers = listOf("A", "B"),
        )
        assertEquals(
            "| A | B |\n| --- | --- |\n|  |  |\n|  |  |",
            result.text,
        )
        assertEquals(result.text.length, result.cursor)
    }

    @Test
    fun `insert separates the table from surrounding blocks`() {
        val body = "before\n\nafter"
        val result = MarkdownTableEdit.insertTable(
            text = body,
            selectionStart = 8,
            selectionEnd = 8,
            rows = 1,
            cols = 1,
            headers = listOf("H"),
        )
        assertEquals("before\n\n| H |\n| --- |\n|  |\n\nafter", result.text)
    }

    @Test
    fun `insert replaces the selection`() {
        val body = "keep old tail"
        val result = MarkdownTableEdit.insertTable(
            text = body,
            selectionStart = 5,
            selectionEnd = 8,
            rows = 1,
            cols = 1,
            headers = listOf("H"),
        )
        assertFalse(result.text.contains("old"))
        assertTrue(result.text.contains("keep "))
        assertTrue(result.text.contains("| H |"))
    }

    @Test
    fun `insert clamps rows and columns to one through twenty`() {
        val small = MarkdownTableEdit.insertTable("", 0, 0, rows = 0, cols = 0)
        assertEquals(3, small.text.split("\n").size) // header + delimiter + 1 body row
        assertEquals(2, small.text.split("\n")[0].count { it == '|' }) // 1 column

        val large = MarkdownTableEdit.insertTable("", 0, 0, rows = 99, cols = 99)
        val lines = large.text.split("\n")
        assertEquals(2 + MarkdownTableEdit.MAX_SIZE, lines.size)
        assertEquals(MarkdownTableEdit.MAX_SIZE + 1, lines[0].count { it == '|' })
    }

    @Test
    fun `insert without headers leaves blank header cells`() {
        val result = MarkdownTableEdit.insertTable("", 0, 0, rows = 1, cols = 2)
        assertEquals("|  |  |\n| --- | --- |\n|  |  |", result.text)
    }

    // ---- lookup --------------------------------------------------------------

    @Test
    fun `tableAt finds the table containing the cursor`() {
        val body = "intro\n\n$table\n\noutro"
        val found = MarkdownTableEdit.tableAt(body, cursorIn(body, "1 | 2"))
        assertNotNull(found)
        assertEquals(listOf("A", "B"), found!!.header)
        assertEquals(2, found.columnCount)
        assertEquals(1, found.rows.size)
        assertFalse(found.cursorOnHeaderOrDelimiter)
    }

    @Test
    fun `tableAt reports the header row and returns null outside a table`() {
        val body = "intro\n\n$table\n\noutro"
        val header = MarkdownTableEdit.tableAt(body, cursorIn(body, "| A |"))
        assertNotNull(header)
        assertTrue(header!!.cursorOnHeaderOrDelimiter)

        assertNull(MarkdownTableEdit.tableAt(body, 2))
        assertNull(MarkdownTableEdit.tableAt(body, cursorIn(body, "outro")))
        assertNull(MarkdownTableEdit.tableAt("no table here", 3))
    }

    @Test
    fun `tableAt ignores a pipe paragraph without a delimiter row`() {
        assertNull(MarkdownTableEdit.tableAt("| not | a table |\nplain", 3))
    }

    // ---- rows ----------------------------------------------------------------

    @Test
    fun `addRow inserts after the cursor row`() {
        val result = MarkdownTableEdit.addRow(table, cursorIn(table, "1 | 2"))!!
        assertEquals("$table\n|  |  |", result.text)
        assertEquals(result.text.indexOf("|  |  |"), result.cursor)
    }

    @Test
    fun `addRow on the header inserts the first body row`() {
        val result = MarkdownTableEdit.addRow(table, cursorIn(table, "| A |"))!!
        assertEquals(
            "| A | B |\n| --- | --- |\n|  |  |\n| 1 | 2 |",
            result.text,
        )
    }

    @Test
    fun `removeRow removes the cursor row but never the header or delimiter`() {
        val result = MarkdownTableEdit.removeRow(table, cursorIn(table, "1 | 2"))!!
        assertEquals("| A | B |\n| --- | --- |", result.text)

        assertNull(MarkdownTableEdit.removeRow(table, cursorIn(table, "| A |")))
        assertNull(MarkdownTableEdit.removeRow(table, cursorIn(table, "---")))
        assertNull(MarkdownTableEdit.removeRow("plain", 0))
    }

    // ---- columns -------------------------------------------------------------

    @Test
    fun `addColumn appends an empty cell on every line`() {
        val result = MarkdownTableEdit.addColumn(table, cursorIn(table, "2"))!!
        assertEquals(
            "| A | B |  |\n| --- | --- | --- |\n| 1 | 2 |  |",
            result.text,
        )
    }

    @Test
    fun `removeColumn removes the column under the cursor`() {
        val result = MarkdownTableEdit.removeColumn(table, cursorIn(table, "2"))!!
        assertEquals("| A |\n| --- |\n| 1 |", result.text)
    }

    @Test
    fun `removeColumn refuses to remove the last column`() {
        val single = "| A |\n| --- |\n| 1 |"
        assertNull(MarkdownTableEdit.removeColumn(single, cursorIn(single, "1 |")))
    }

    @Test
    fun `addColumn preserves escaped pipes in cell text`() {
        val escaped = "| a \\| b | c |\n| --- | --- |\n| 1 | 2 |"
        val result = MarkdownTableEdit.addColumn(escaped, cursorIn(escaped, "1 | 2"))!!
        assertTrue(result.text.contains("a \\| b"))
    }

    @Test
    fun `row and column edits are null outside a table`() {
        val body = "plain text"
        assertNull(MarkdownTableEdit.addRow(body, 2))
        assertNull(MarkdownTableEdit.removeRow(body, 2))
        assertNull(MarkdownTableEdit.addColumn(body, 2))
        assertNull(MarkdownTableEdit.removeColumn(body, 2))
    }
}
