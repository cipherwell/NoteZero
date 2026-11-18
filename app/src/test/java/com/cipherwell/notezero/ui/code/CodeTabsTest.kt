package com.cipherwell.notezero.ui.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the multi-file tab model (docs/CONTRACTS-PHASE14.md §A): open/activate,
 * the 8-tab cap and close-neighbour semantics. Pure list transitions, no Android.
 */
class CodeTabsTest {

    private val ids = listOf("a", "b", "c")

    @Test
    fun `opening a new file appends a tab and activates it`() {
        val opened = CodeTabs.open(openFileIds = ids, activeFileId = "a", itemId = "d")

        assertEquals(listOf("a", "b", "c", "d"), opened.openFileIds)
        assertEquals("d", opened.activeFileId)
        assertFalse(opened.limitReached)
    }

    @Test
    fun `opening an already tabbed file activates it without duplicating`() {
        val opened = CodeTabs.open(openFileIds = ids, activeFileId = "a", itemId = "b")

        assertEquals(ids, opened.openFileIds)
        assertEquals("b", opened.activeFileId)
        assertFalse(opened.limitReached)
    }

    @Test
    fun `opening the active file keeps it active`() {
        val opened = CodeTabs.open(openFileIds = ids, activeFileId = "b", itemId = "b")

        assertEquals(ids, opened.openFileIds)
        assertEquals("b", opened.activeFileId)
    }

    @Test
    fun `opening beyond the cap is refused and reports the limit`() {
        val full = (1..CodeTabs.MAX_OPEN_TABS).map { "file-$it" }

        val opened = CodeTabs.open(openFileIds = full, activeFileId = "file-3", itemId = "new-file")

        assertEquals(full, opened.openFileIds)
        assertEquals("file-3", opened.activeFileId)
        assertTrue(opened.limitReached)
    }

    @Test
    fun `an already tabbed file can still be activated at the cap`() {
        val full = (1..CodeTabs.MAX_OPEN_TABS).map { "file-$it" }

        val opened = CodeTabs.open(openFileIds = full, activeFileId = "file-1", itemId = "file-8")

        assertEquals(full, opened.openFileIds)
        assertEquals("file-8", opened.activeFileId)
        assertFalse(opened.limitReached)
    }

    @Test
    fun `closing the active middle tab activates its left neighbour`() {
        val closed = CodeTabs.close(openFileIds = ids, activeFileId = "b", itemId = "b")

        assertEquals(listOf("a", "c"), closed.openFileIds)
        assertEquals("a", closed.activeFileId)
    }

    @Test
    fun `closing the first active tab activates the next one on the right`() {
        val closed = CodeTabs.close(openFileIds = ids, activeFileId = "a", itemId = "a")

        assertEquals(listOf("b", "c"), closed.openFileIds)
        assertEquals("b", closed.activeFileId)
    }

    @Test
    fun `closing the last active tab returns to the file tree`() {
        val closed = CodeTabs.close(openFileIds = listOf("only"), activeFileId = "only", itemId = "only")

        assertTrue(closed.openFileIds.isEmpty())
        assertNull(closed.activeFileId)
    }

    @Test
    fun `closing an inactive tab keeps the active file`() {
        val closed = CodeTabs.close(openFileIds = ids, activeFileId = "c", itemId = "a")

        assertEquals(listOf("b", "c"), closed.openFileIds)
        assertEquals("c", closed.activeFileId)
    }

    @Test
    fun `closing an unknown tab changes nothing`() {
        val closed = CodeTabs.close(openFileIds = ids, activeFileId = "b", itemId = "missing")

        assertEquals(ids, closed.openFileIds)
        assertEquals("b", closed.activeFileId)
    }
}
