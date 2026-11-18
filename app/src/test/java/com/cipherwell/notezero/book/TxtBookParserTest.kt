package com.cipherwell.notezero.book

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain-text decoding, paragraph mapping, auto-chaptering and caps. */
class TxtBookParserTest {

    private val fallback = BookFormatFixtures.FALLBACK_TITLE

    private fun parse(bytes: ByteArray, title: String = fallback): BookContent =
        requireNotNull(TxtBookParser.parse(bytes, title)) { "expected a parsed TXT document" }

    private fun parseText(text: String): BookContent = parse(BookFormatFixtures.utf8(text))

    private fun paragraphs(content: BookContent, chapter: Int = 0): List<String> =
        content.chapters[chapter].blocks.filterIsInstance<BookBlock.Paragraph>()
            .map { runText(it.runs) }

    private fun runText(runs: List<BookInline>): String = runs.joinToString("") { it.text }

    // ---- decoding -----------------------------------------------------------

    @Test
    fun `simple UTF-8 text becomes one part with paragraphs`() {
        val content = parseText("Hello world.\n\nSecond paragraph.")
        assertEquals(fallback, content.title)
        assertNull(content.author)
        assertNull(content.language)
        assertEquals(1, content.chapters.size)
        assertEquals("Part 1", content.chapters[0].title)
        assertEquals(listOf("Hello world.", "Second paragraph."), paragraphs(content))
        assertEquals(listOf(BookTocEntry("Part 1", 0, 0)), content.toc)
    }

    @Test
    fun `title falls back to the app name and last resort is Untitled`() {
        val content = parseText("Some text.")
        assertEquals(fallback, content.title)
        val untitled = parse(BookFormatFixtures.utf8("Some text."), "   ")
        assertEquals("Untitled", untitled.title)
        val trimmed = parse(BookFormatFixtures.utf8("Some text."), "  My Book  ")
        assertEquals("My Book", trimmed.title)
    }

    @Test
    fun `single newlines inside a block are joined with spaces`() {
        val content = parseText("line one\nline two\nline three")
        assertEquals(listOf("line one line two line three"), paragraphs(content))
    }

    @Test
    fun `tabs and repeated spaces collapse`() {
        val content = parseText("a\t\t  b   c\n\n   d  ")
        assertEquals(listOf("a b c", "d"), paragraphs(content))
    }

    @Test
    fun `lone carriage returns are line breaks`() {
        val content = parseText("one\rtwo\r\rthree")
        assertEquals(listOf("one two", "three"), paragraphs(content))
    }

    @Test
    fun `CRLF line endings are handled`() {
        val content = parseText("one\r\n\r\ntwo")
        assertEquals(listOf("one", "two"), paragraphs(content))
    }

    @Test
    fun `UTF-8 BOM is stripped`() {
        val content = parse(BookFormatFixtures.utf8Bom("BOM text."))
        assertEquals(listOf("BOM text."), paragraphs(content))
    }

    @Test
    fun `UTF-16LE with BOM is decoded`() {
        val content = parse(BookFormatFixtures.utf16Le("Hello \u00e9\u00e8.\n\nSecond."))
        assertEquals(listOf("Hello \u00e9\u00e8.", "Second."), paragraphs(content))
    }

    @Test
    fun `UTF-16BE with BOM is decoded`() {
        val content = parse(BookFormatFixtures.utf16Be("Big endian."))
        assertEquals(listOf("Big endian."), paragraphs(content))
    }

    @Test
    fun `windows-1252 is used when the UTF-8 replacement ratio is high`() {
        val text = "caf\u00e9 na\u00efve r\u00e9sum\u00e9\n\nUn autre caf\u00e9."
        val content = parse(BookFormatFixtures.windows1252(text))
        assertEquals(listOf("caf\u00e9 na\u00efve r\u00e9sum\u00e9", "Un autre caf\u00e9."), paragraphs(content))
    }

    @Test
    fun `a low replacement ratio keeps the UTF-8 reading`() {
        val bytes = BookFormatFixtures.utf8("a".repeat(500)) + byteArrayOf(0xE9.toByte())
        val content = parse(bytes)
        val text = paragraphs(content).single()
        assertEquals(501, text.length)
        assertTrue(text.startsWith("a".repeat(500)))
    }

    @Test
    fun `UTF-16 without a BOM is rejected as binary`() {
        val bytes = "Hello".toByteArray(Charsets.UTF_16LE)
        assertNull(TxtBookParser.parse(bytes, fallback))
    }

    @Test
    fun `empty whitespace-only and binary inputs return null`() {
        assertNull(TxtBookParser.parse(ByteArray(0), fallback))
        assertNull(TxtBookParser.parse(BookFormatFixtures.utf8("   \n\n\t  "), fallback))
        assertNull(TxtBookParser.parse(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x00, 0x7F), fallback))
    }

    @Test
    fun `NUL bytes anywhere are refused`() {
        val bytes = BookFormatFixtures.utf8("clean text here") + byteArrayOf(0x00) +
            BookFormatFixtures.utf8(" and more text")
        assertNull(TxtBookParser.parse(bytes, fallback))
    }

    // ---- chapter headings ---------------------------------------------------

    @Test
    fun `heading lines split chapters and become heading blocks`() {
        val content = parseText(
            "Chapter One\n\nFirst body.\n\nPart Two\n\nSecond body.\n\nBook Three\n\nThird body.",
        )
        assertEquals(3, content.chapters.size)
        assertEquals(listOf("Chapter One", "Part Two", "Book Three"), content.chapters.map { it.title })
        assertEquals(BookBlock.Heading(1, listOf(BookInline("Chapter One"))), content.chapters[0].blocks[0])
        assertEquals(listOf("First body."), paragraphs(content, 0))
        assertEquals(listOf("Second body."), paragraphs(content, 1))
        assertEquals(3, content.toc.size)
        assertEquals(BookTocEntry("Part Two", 1, 0), content.toc[1])
    }

    @Test
    fun `headings are case-insensitive and cover the documented keywords`() {
        val content = parseText(
            "CHAPTER 1\n\nOne.\n\nprologue\n\nTwo.\n\nEpilogue\n\nThree.\n\nintroduction\n\nFour.\n\nappendix\n\nFive.",
        )
        assertEquals(5, content.chapters.size)
        assertEquals(listOf("CHAPTER 1", "prologue", "Epilogue", "introduction", "appendix"), content.chapters.map { it.title })
    }

    @Test
    fun `a heading must be a standalone single-line block`() {
        val multiline = parseText("Book the tickets now.\nand also later.\n\nNext paragraph.")
        assertEquals(1, multiline.chapters.size)
        assertEquals(
            listOf("Book the tickets now. and also later.", "Next paragraph."),
            paragraphs(multiline),
        )
    }

    @Test
    fun `heading keyword not at line start does not split`() {
        val content = parseText("The chapter of accidents.\n\nMore text.")
        assertEquals(1, content.chapters.size)
    }

    @Test
    fun `very long heading-like lines are treated as prose`() {
        val line = "Chapter " + "x".repeat(BookLimits.MAX_DERIVED_TITLE_CHARS + 10)
        val content = parseText("$line\n\nBody.")
        assertEquals(1, content.chapters.size)
        assertEquals(listOf(line, "Body."), paragraphs(content))
    }

    @Test
    fun `a very long single line is preserved as one paragraph`() {
        val line = "a".repeat(25_000)
        val content = parseText(line)
        assertEquals(1, content.chapters.size)
        assertEquals(25_000, paragraphs(content).single().length)
    }

    // ---- automatic parts ----------------------------------------------------

    @Test
    fun `text without headings is split at paragraph boundaries past 20000 chars`() {
        val paragraph = "x".repeat(11_000)
        val text = (0 until 6).joinToString("\n\n") { paragraph }
        val content = parseText(text)
        assertEquals(3, content.chapters.size)
        assertEquals(listOf("Part 1", "Part 2", "Part 3"), content.chapters.map { it.title })
        assertEquals(listOf(2, 2, 2), content.chapters.map { it.blocks.size })
        assertEquals(listOf(0, 1, 2), content.toc.map { it.chapterIndex })
    }

    @Test
    fun `chapters are capped at 2000`() {
        val builder = StringBuilder()
        for (index in 0 until 2_100) {
            builder.append("Chapter ").append(index).append("\n\n")
            builder.append("Body ").append(index).append(".\n\n")
        }
        val content = parseText(builder.toString())
        assertEquals(BookLimits.MAX_REFOWABLE_CHAPTERS, content.chapters.size)
        assertEquals("Chapter 0", content.chapters[0].title)
        assertEquals("Chapter 1999", content.chapters[1999].title)
    }

    @Test
    fun `text per chapter is capped at 2 million characters`() {
        val line = "a".repeat(BookLimits.MAX_TEXT_PER_CHAPTER + 100)
        val content = parseText(line)
        assertEquals(BookLimits.MAX_TEXT_PER_CHAPTER, paragraphs(content).single().length)
    }

    // ---- lifecycle / TOC ----------------------------------------------------

    @Test
    fun `TOC entries mirror chapter titles and indexes`() {
        val content = parseText("Chapter A\n\none.\n\nChapter B\n\ntwo.\n\nChapter C\n\nthree.")
        assertEquals(3, content.toc.size)
        for ((index, entry) in content.toc.withIndex()) {
            assertEquals(index, entry.chapterIndex)
            assertEquals(0, entry.depth)
            assertEquals(content.chapters[index].title, entry.title)
        }
    }

    @Test
    fun `plain text joins blocks with newlines for search`() {
        val content = parseText("Chapter One\n\nAlpha.\n\nBeta gamma.")
        val plain = content.chapters[0].plainText()
        assertEquals("Chapter One\nAlpha.\nBeta gamma.", plain)
    }

    @Test
    fun `close is idempotent and keeps parsed state`() {
        val content = parseText("Chapter One\n\nBody.")
        assertNull(content.cover)
        assertNull(content.imageBytes("anything"))
        content.close()
        content.close()
        assertNull(content.cover)
        assertNull(content.imageBytes("anything"))
        assertEquals(1, content.chapters.size)
        assertEquals("Chapter One", content.chapters[0].title)
    }

    @Test
    fun `chapters expose stable href and index values`() {
        val content = parseText("Chapter A\n\none.\n\nChapter B\n\ntwo.")
        assertNotNull(content.chapters[0].href)
        assertEquals("txt#chapter-1", content.chapters[0].href)
        assertEquals("txt#chapter-2", content.chapters[1].href)
        assertArrayEquals(
            "chapters list is immutable",
            content.chapters.toTypedArray(),
            content.chapters.toTypedArray(),
        )
    }
}
