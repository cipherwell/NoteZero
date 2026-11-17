package com.cipherwell.notezero.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the XHTML -> blocks converter, independent of ZIP/OPF handling. Exercises
 * element mapping, inline formatting, path resolution and malformed-markup rejection.
 */
class ChapterBlocksTest {

    private val chapterPath = "OEBPS/text/ch.xhtml"

    private fun blocks(body: String): List<BookBlock> =
        requireNotNull(ChapterBlocks.parse(Fixtures.xhtml(body).toByteArray(Charsets.UTF_8), chapterPath))

    private fun text(block: BookBlock): String = when (block) {
        is BookBlock.Heading -> inlineRunsText(block.runs)
        is BookBlock.Paragraph -> inlineRunsText(block.runs)
        is BookBlock.ListBlock -> block.items.joinToString("|") { inlineRunsText(it.runs) }
        is BookBlock.Quote -> inlineRunsText(block.runs)
        is BookBlock.Code -> block.text
        is BookBlock.Image -> block.alt
        BookBlock.Rule -> "rule"
    }

    @Test
    fun `parses paragraphs and heading levels`() {
        val blocks = blocks("<h1>H1</h1><h2>H2</h2><h3>H3</h3><h4>H4</h4><h5>H5</h5><h6>H6</h6><p>Body.</p>")
        assertEquals(listOf(1, 2, 3, 4, 5, 6), blocks.filterIsInstance<BookBlock.Heading>().map { it.level })
        assertEquals("Body.", text(blocks.last()))
    }

    @Test
    fun `div is a block container`() {
        val blocks = blocks("<div>one</div><div><p>two</p></div>")
        assertEquals(listOf("one", "two"), blocks.map { text(it) })
    }

    @Test
    fun `nested lists become flat items with depth and ordered flag`() {
        val blocks = blocks("<ol><li>First</li><li>Second<ul><li>Nested</li></ul></li></ol>")
        val list = blocks.filterIsInstance<BookBlock.ListBlock>().single()
        assertTrue(list.ordered)
        assertEquals(
            listOf("First" to 0, "Second" to 0, "Nested" to 1),
            list.items.map { inlineRunsText(it.runs) to it.depth },
        )
    }

    @Test
    fun `list depth is clamped to eight`() {
        val builder = StringBuilder()
        val levels = 12
        repeat(levels) { builder.append("<ul><li>L").append(it) }
        repeat(levels) { builder.append("</li></ul>") }
        val list = blocks(builder.toString()).filterIsInstance<BookBlock.ListBlock>().single()
        assertEquals(levels, list.items.size)
        assertEquals(8, list.items.map { it.depth }.max())
    }

    @Test
    fun `blockquote flattens nested paragraph text into one quote`() {
        val quote = blocks("<blockquote><p>Alpha</p><p>Beta</p></blockquote>")
            .filterIsInstance<BookBlock.Quote>()
            .single()
        assertEquals("Alpha Beta", inlineRunsText(quote.runs))
    }

    @Test
    fun `pre becomes a code block with preserved line breaks`() {
        val code = blocks("<pre><code>fun main() {\n    val answer = 42\n}</code></pre>")
            .filterIsInstance<BookBlock.Code>()
            .single()
        assertEquals("fun main() {\n    val answer = 42\n}", code.text)
    }

    @Test
    fun `hr becomes a rule`() {
        assertEquals(listOf(BookBlock.Rule), blocks("<hr/>"))
    }

    @Test
    fun `table rows become paragraphs with middle-dot separated cells`() {
        val blocks = blocks("<table><tr><th>H1</th><th>H2</th></tr><tr><td>A</td><td>B</td></tr></table>")
        assertEquals(listOf("H1 \u00b7 H2", "A \u00b7 B"), blocks.map { text(it) })
    }

    @Test
    fun `table with only empty cells produces no paragraphs`() {
        assertTrue(blocks("<table><tr><td></td><td></td></tr></table>").isEmpty())
    }

    @Test
    fun `resolves image sources relative to the chapter`() {
        val images = blocks(
            """<p><img src="../images/pic.png" alt="up"/></p><p><img src="local/pic.png" alt="down"/></p>""",
        ).filterIsInstance<BookBlock.Image>()
        assertEquals(listOf("OEBPS/images/pic.png", "OEBPS/text/local/pic.png"), images.map { it.entry })
        assertEquals(listOf("up", "down"), images.map { it.alt })
    }

    @Test
    fun `ignores images that escape the archive root or use remote urls`() {
        val images = blocks(
            """<img src="../../../etc/passwd"/><img src="/absolute.png"/><img src="https://example.com/x.png"/><img src="data:image/png;base64,AA"/>""",
        ).filterIsInstance<BookBlock.Image>()
        assertTrue(images.isEmpty())
    }

    @Test
    fun `image inside paragraph splits surrounding text into paragraphs`() {
        val blocks = blocks("""<p>before<img src="../images/pic.png" alt="alt"/>after</p>""")
        assertEquals(3, blocks.size)
        assertEquals("before", text(blocks[0]))
        assertTrue(blocks[1] is BookBlock.Image)
        assertEquals("after", text(blocks[2]))
    }

    @Test
    fun `nested inline formatting is preserved as flags`() {
        val paragraph = blocks("<p><strong>bold <em>both</em></strong></p>")
            .filterIsInstance<BookBlock.Paragraph>()
            .single()
        val first = paragraph.runs.first { it.text == "bold " }
        assertTrue(first.bold)
        assertFalse(first.italic)
        val both = paragraph.runs.first { it.text == "both" }
        assertTrue(both.bold)
        assertTrue(both.italic)
    }

    @Test
    fun `unknown elements keep their text and markup never leaks`() {
        val paragraph = blocks("<p>a <foo>b</foo> <unknown attr=\"x\">c</unknown></p>")
            .filterIsInstance<BookBlock.Paragraph>()
            .single()
        assertEquals("a b c", inlineRunsText(paragraph.runs))
        assertFalse(inlineRunsText(paragraph.runs).contains("<"))
    }

    @Test
    fun `whitespace is collapsed and trimmed`() {
        val paragraph = blocks("<p>   lots    of\n\t  space   </p>")
            .filterIsInstance<BookBlock.Paragraph>()
            .single()
        assertEquals("lots of space", inlineRunsText(paragraph.runs))
    }

    @Test
    fun `whitespace-only paragraphs are dropped`() {
        assertTrue(blocks("<p>   </p><p>\n</p>").isEmpty())
    }

    @Test
    fun `script style and head content are ignored`() {
        val chapter = """<?xml version="1.0"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>T</title><style>.x{}</style></head>
<body><script>var x = 1;</script><p>kept</p></body>
</html>"""
        val parsed = requireNotNull(ChapterBlocks.parse(chapter.toByteArray(Charsets.UTF_8), chapterPath))
        assertEquals(listOf("kept"), parsed.map { text(it) })
    }

    @Test
    fun `named xhtml entities are decoded`() {
        val paragraph = blocks("<p>A&nbsp;B &eacute;t&eacute; &mdash; done &amp; more</p>")
            .filterIsInstance<BookBlock.Paragraph>()
            .single()
        assertEquals("A\u00a0B \u00e9t\u00e9 \u2014 done & more", inlineRunsText(paragraph.runs))
    }

    @Test
    fun `tolerates external doctype without resolving it`() {
        val chapter = """<?xml version="1.0"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>T</title></head><body><p>doctype body</p></body></html>"""
        val parsed = requireNotNull(ChapterBlocks.parse(chapter.toByteArray(Charsets.UTF_8), chapterPath))
        assertEquals(listOf("doctype body"), parsed.map { text(it) })
    }

    @Test
    fun `rejects malformed xml`() {
        assertNull(ChapterBlocks.parse("<p>unclosed".toByteArray(Charsets.UTF_8), chapterPath))
        assertNull(ChapterBlocks.parse("not xml at all".toByteArray(Charsets.UTF_8), chapterPath))
        assertNull(ChapterBlocks.parse(ByteArray(0), chapterPath))
    }

    @Test
    fun `rejects documents with an internal dtd subset`() {
        val chapter = """<?xml version="1.0"?>
<!DOCTYPE html [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
<html xmlns="http://www.w3.org/1999/xhtml"><body><p>&xxe;</p></body></html>"""
        assertNull(ChapterBlocks.parse(chapter.toByteArray(Charsets.UTF_8), chapterPath))
    }
}
