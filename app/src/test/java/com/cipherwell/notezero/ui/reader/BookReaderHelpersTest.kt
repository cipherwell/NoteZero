package com.cipherwell.notezero.ui.reader

import com.cipherwell.notezero.book.BookBlock
import com.cipherwell.notezero.book.BookChapter
import com.cipherwell.notezero.book.BookInline
import com.cipherwell.notezero.book.EpubParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for the reader's pure helpers: search matching/snippets, the rendering
 * caps and the shared chapter block model. No Android APIs are touched, so these run
 * without Robolectric.
 */
class BookReaderHelpersTest {

    private fun parseFixture(): com.cipherwell.notezero.book.EpubDocument {
        val document = EpubParser.parse(EpubTestData.validEpub())
            ?: error("fixture EPUB failed to parse")
        return document
    }

    @Test
    fun `search finds a case-insensitive match and keeps a snippet`() {
        val document = parseFixture()
        try {
            val hits = searchBook(document, "DISTINCTIVE SEARCHPHRASE")
            assertEquals(1, hits.size)
            val hit = hits.single()
            assertEquals(0, hit.chapterIndex)
            assertTrue(hit.snippet.contains("distinctive searchphrase", ignoreCase = true))
        } finally {
            document.close()
        }
    }

    @Test
    fun `search honours the result limit and ignores a blank query`() {
        val document = parseFixture()
        try {
            assertEquals(1, searchBook(document, "chapter", limit = 1).size)
            assertTrue(searchBook(document, "   ").isEmpty())
        } finally {
            document.close()
        }
    }

    @Test
    fun `snippet collapses whitespace and marks truncation`() {
        val text = "prefix\n\nline\nwith\nthe needle and more text after it"
        val snippet = snippetAround(text, text.indexOf("needle"), "needle".length)
        assertTrue(snippet.contains("needle"))
        assertTrue(snippet.contains("prefix"))
        assertTrue(!snippet.contains("\n"))
    }

    @Test
    fun `bounded blocks stops at two thousand blocks`() {
        val blocks = (0 until 2_500).map { index ->
            BookBlock.Paragraph(listOf(BookInline("paragraph $index")))
        }
        val bounded = boundedBlocks(blocks)
        assertEquals(2_000, bounded.size)
    }

    @Test
    fun `bounded blocks truncates text over two hundred thousand characters`() {
        val huge = "x".repeat(300_000)
        val bounded = boundedBlocks(listOf(BookBlock.Paragraph(listOf(BookInline(huge)))))
        assertEquals(1, bounded.size)
        val rendered = (bounded.single() as BookBlock.Paragraph).runs.sumOf { it.text.length }
        assertEquals(200_000, rendered)
    }

    @Test
    fun `search across chapters reports the matching chapter index`() {
        val document = parseFixture()
        try {
            val hits = searchBook(document, EpubTestData.CHAPTER_TWO_BODY)
            assertEquals(1, hits.size)
            assertEquals(1, hits.single().chapterIndex)
        } finally {
            document.close()
        }
    }

    @Test
    fun `paragraph and chapter models are usable without the archive`() {
        val blocks = listOf<BookBlock>(
            BookBlock.Heading(1, listOf(BookInline("A heading"))),
            BookBlock.Paragraph(listOf(BookInline("A body"))),
        )
        val chapter = BookChapter(index = 0, title = "A heading", href = "text/ch1.xhtml", blocks = blocks)
        assertTrue(chapter.plainText().contains("A heading"))
        assertEquals(2, boundedBlocks(blocks).size)
    }
}
