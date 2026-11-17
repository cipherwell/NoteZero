package com.cipherwell.notezero.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** `BookChapter.plainText()` is the search index input; its shape is part of the contract. */
class BookBlocksTest {

    private fun chapter(vararg blocks: BookBlock): BookChapter =
        BookChapter(index = 0, title = "T", href = "OEBPS/text/ch.xhtml", blocks = blocks.toList())

    @Test
    fun `joins block texts with newlines`() {
        val text = chapter(
            BookBlock.Heading(1, listOf(BookInline("Title"))),
            BookBlock.Paragraph(listOf(BookInline("Body one."))),
            BookBlock.Paragraph(listOf(BookInline("Body two."))),
        ).plainText()
        assertEquals("Title\nBody one.\nBody two.", text)
    }

    @Test
    fun `includes list items code and quotes`() {
        val text = chapter(
            BookBlock.ListBlock(
                ordered = true,
                items = listOf(
                    BookListItem(listOf(BookInline("first"))),
                    BookListItem(listOf(BookInline("nested")), depth = 1),
                ),
            ),
            BookBlock.Quote(listOf(BookInline("quoted"))),
            BookBlock.Code("val x = 1"),
        ).plainText()
        assertEquals("first\nnested\nquoted\nval x = 1", text)
    }

    @Test
    fun `images contribute alt text and rules contribute nothing`() {
        val text = chapter(
            BookBlock.Paragraph(listOf(BookInline("before"))),
            BookBlock.Rule,
            BookBlock.Image("OEBPS/images/pic.png", "alt words"),
            BookBlock.Image("OEBPS/images/icon.png", ""),
            BookBlock.Paragraph(listOf(BookInline("after"))),
        ).plainText()
        assertEquals("before\nalt words\nafter", text)
    }

    @Test
    fun `empty chapter has empty plain text`() {
        assertEquals("", chapter().plainText())
    }

    @Test
    fun `inline text concatenates runs without separators`() {
        val runs = listOf(
            BookInline("bold", bold = true),
            BookInline(" and "),
            BookInline("italic", italic = true),
        )
        assertEquals("bold and italic", inlineRunsText(runs))
        assertFalse(inlineRunsText(runs).contains("BookInline"))
    }
}
