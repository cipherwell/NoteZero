package com.cipherwell.notezero.book

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end EPUB3 behaviour against an in-memory fixture: container -> OPF -> nav -> XHTML
 * chapters -> blocks -> lazy images. Assertions are on the public, frozen API only.
 */
class Epub3ParserTest {

    private fun document(bytes: ByteArray = Fixtures.epub3()): EpubDocument =
        requireNotNull(EpubParser.parse(bytes)) { "fixture failed to parse" }

    @Test
    fun `parses metadata title author language`() {
        val document = document()
        assertEquals(Fixtures.TITLE, document.title)
        assertEquals(Fixtures.AUTHOR, document.author)
        assertEquals(Fixtures.LANGUAGE, document.language)
    }

    @Test
    fun `parses spine chapters in order with archive-relative hrefs and derived titles`() {
        val document = document()
        assertEquals(3, document.chapters.size)
        assertEquals(listOf(0, 1, 2), document.chapters.map { it.index })
        assertEquals(listOf(Fixtures.CH1, Fixtures.CH2, Fixtures.CH3), document.chapters.map { it.href })
        assertEquals("Chapter One", document.chapters[0].title)
        assertEquals("Second Chapter", document.chapters[1].title)
        assertEquals("Third Chapter", document.chapters[2].title)
    }

    @Test
    fun `parses epub3 nav toc and maps fragments to spine chapters with depth`() {
        val document = document()
        assertEquals(
            listOf(
                BookTocEntry("Chapter One", 0, 0),
                BookTocEntry("Part Two", 1, 0),
                BookTocEntry("Chapter Three", 2, 1),
            ),
            document.toc,
        )
    }

    @Test
    fun `parses inline formatting flags`() {
        val paragraph = document().chapters[0].blocks.filterIsInstance<BookBlock.Paragraph>().first()
        fun run(text: String): BookInline = paragraph.runs.first { it.text == text }
        assertTrue("bold", run("bold").bold)
        assertTrue("italic", run("italic").italic)
        assertTrue("code", run("inline code").code)
        assertTrue("link", run("a link").link)
        assertFalse("plain run is not bold", run("Intro with ").bold)
        assertFalse("plain run is not a link", run("Intro with ").link)
    }

    @Test
    fun `parses block types`() {
        val blocks = document().chapters[0].blocks
        assertEquals(BookBlock.Heading(1, listOf(BookInline("Chapter One"))), blocks[0])

        val listBlock = blocks.filterIsInstance<BookBlock.ListBlock>().single()
        assertFalse(listBlock.ordered)
        assertEquals(3, listBlock.items.size)
        assertEquals("Bullet one", inlineRunsText(listBlock.items[0].runs))
        assertEquals(0, listBlock.items[0].depth)
        assertEquals("Bullet two", inlineRunsText(listBlock.items[1].runs))
        assertEquals(0, listBlock.items[1].depth)
        assertEquals("Nested bullet", inlineRunsText(listBlock.items[2].runs))
        assertEquals(1, listBlock.items[2].depth)

        assertEquals(BookBlock.Rule, blocks.filterIsInstance<BookBlock.Rule>().single())
        assertEquals("Quoted wisdom.", inlineRunsText(blocks.filterIsInstance<BookBlock.Quote>().single().runs))
        assertEquals("line one\nline two", blocks.filterIsInstance<BookBlock.Code>().single().text)
    }

    @Test
    fun `parses image blocks with resolved entries and alt text`() {
        val image = document().chapters[0].blocks.filterIsInstance<BookBlock.Image>().single()
        assertEquals(Fixtures.PIC, image.entry)
        assertEquals("A picture", image.alt)
    }

    @Test
    fun `renders table rows as paragraphs with middle-dot separated cells`() {
        val tableRowTexts = document().chapters[0].blocks
            .filterIsInstance<BookBlock.Paragraph>()
            .map { inlineRunsText(it.runs) }
            .filter { it.contains(" \u00b7 ") }
        assertEquals(listOf("Alpha \u00b7 Beta", "Gamma \u00b7 Delta"), tableRowTexts)
    }

    @Test
    fun `decodes entities and keeps non-breaking space`() {
        val text = document().chapters[0].plainText()
        assertTrue("entity decoded", text.contains("Tail\u00a0text & entities"))
        assertFalse("no raw entities", text.contains("&nbsp;"))
        assertFalse("no raw tags", text.contains("<p>"))
        assertFalse("no raw tags", text.contains("<img"))
    }

    @Test
    fun `cover and image bytes are available and are caller-owned copies`() {
        val document = document()
        val cover = document.cover
        assertNotNull(cover)
        assertArrayEquals(Fixtures.COVER_BYTES, cover)
        val image = document.imageBytes(Fixtures.PIC)
        assertNotNull(image)
        assertArrayEquals(Fixtures.PIC_BYTES, image)
        // Mutating a returned copy must not corrupt later reads (no internal array is exposed).
        image!![0] = 0x00
        cover!![0] = 0x00
        assertArrayEquals(Fixtures.PIC_BYTES, document.imageBytes(Fixtures.PIC))
        assertArrayEquals(Fixtures.COVER_BYTES, document.cover)
        // Different array instances on every call.
        assertFalse(document.imageBytes(Fixtures.PIC) === document.imageBytes(Fixtures.PIC))
    }

    @Test
    fun `unknown or unreferenced entries are not readable as images`() {
        val document = document()
        assertNull(document.imageBytes("OEBPS/images/missing.png"))
        assertNull(document.imageBytes("OEBPS/nav.xhtml"))
        assertNull(document.imageBytes(""))
        assertNull(document.imageBytes("../../etc/passwd"))
    }

    @Test
    fun `plainText includes headings body list items quotes code and alt text`() {
        val text = document().chapters[0].plainText()
        assertTrue(text.contains("Chapter One"))
        assertTrue(text.contains("Intro with bold, italic"))
        assertTrue(text.contains("Bullet one"))
        assertTrue(text.contains("Nested bullet"))
        assertTrue(text.contains("Quoted wisdom."))
        assertTrue(text.contains("line one\nline two"))
        assertTrue("image alt text contributes to searchable text", text.contains("A picture"))
        // Blocks are joined with newlines, never with markup.
        assertTrue(text.lines().size > 10)
    }

    @Test
    fun `ignores script style and head content`() {
        val chapter = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Hidden title</title><style>p { color: red; }</style></head>
<body>
  <script type="text/javascript">var hidden = "SCRIPT_TEXT";</script>
  <p>Visible text.</p>
</body>
</html>"""
        val bytes = Fixtures.book(
            opf = Fixtures.opf(
                manifestInner = Fixtures.item("ch1", "text/ch1.xhtml", "application/xhtml+xml"),
                spineInner = Fixtures.itemref("ch1"),
            ),
            entries = mapOf(Fixtures.CH1 to chapter),
        )
        val document = document(bytes)
        val text = document.chapters.single().plainText()
        assertEquals("Visible text.", text)
        assertFalse(text.contains("SCRIPT_TEXT"))
        assertFalse(text.contains("Hidden title"))
        assertFalse(text.contains("color: red"))
    }

    @Test
    fun `br becomes a space and sup sub keep their text`() {
        val chapter = Fixtures.xhtml(
            """<p>Line one<br/>Line two<br />Line three</p><p>E=mc<sup>2</sup> and H<sub>2</sub>O</p>""",
        )
        val document = document(
            Fixtures.book(
                opf = Fixtures.opf(
                    manifestInner = Fixtures.item("ch1", "text/ch1.xhtml", "application/xhtml+xml"),
                    spineInner = Fixtures.itemref("ch1"),
                ),
                entries = mapOf(Fixtures.CH1 to chapter),
            ),
        )
        val texts = document.chapters.single().blocks.filterIsInstance<BookBlock.Paragraph>().map { inlineRunsText(it.runs) }
        assertEquals(listOf("Line one Line two Line three", "E=mc2 and H2O"), texts)
    }

    @Test
    fun `ignores images with absolute or data urls`() {
        val chapter = Fixtures.xhtml(
            """<p>Before</p><p><img src="https://example.com/remote.png" alt="Remote"/></p><p><img src="data:image/png;base64,AAAA" alt="Inline"/></p>""",
        )
        val document = document(
            Fixtures.book(
                opf = Fixtures.opf(
                    manifestInner = Fixtures.item("ch1", "text/ch1.xhtml", "application/xhtml+xml"),
                    spineInner = Fixtures.itemref("ch1"),
                ),
                entries = mapOf(Fixtures.CH1 to chapter),
            ),
        )
        assertTrue(document.chapters.single().blocks.filterIsInstance<BookBlock.Image>().isEmpty())
    }

    @Test
    fun `title falls back to Untitled and missing author language are null`() {
        val metadata = """<dc:identifier id="book-id">urn:uuid:bare</dc:identifier>"""
        val bytes = Fixtures.book(
            opf = Fixtures.opf(
                metadataInner = metadata,
                manifestInner = Fixtures.item("ch1", "text/ch1.xhtml", "application/xhtml+xml"),
                spineInner = Fixtures.itemref("ch1"),
            ),
            entries = mapOf(Fixtures.CH1 to Fixtures.chapterTwo()),
        )
        val document = document(bytes)
        assertEquals("Untitled", document.title)
        assertNull(document.author)
        assertNull(document.language)
    }

    @Test
    fun `derives toc from chapter headings when no nav or ncx exists`() {
        val manifest = listOf(
            Fixtures.item("ch1", "text/ch1.xhtml", "application/xhtml+xml"),
            Fixtures.item("ch2", "text/ch2.xhtml", "application/xhtml+xml"),
            Fixtures.item("ch3", "text/ch3.xhtml", "application/xhtml+xml"),
        ).joinToString("\n    ")
        val bytes = Fixtures.book(
            opf = Fixtures.opf(
                manifestInner = manifest,
                spineInner = listOf(Fixtures.itemref("ch1"), Fixtures.itemref("ch2"), Fixtures.itemref("ch3")).joinToString("\n    "),
            ),
            entries = mapOf(
                Fixtures.CH1 to Fixtures.chapterTwo(), // h1 "Second Chapter"
                Fixtures.CH2 to Fixtures.xhtml("<p>No heading here.</p>"),
                Fixtures.CH3 to Fixtures.xhtml("<h2>Third Chapter</h2><p>Body.</p>"),
            ),
        )
        val document = document(bytes)
        assertEquals(
            listOf(
                BookTocEntry("Second Chapter", 0, 0),
                BookTocEntry("Chapter 2", 1, 0),
                BookTocEntry("Third Chapter", 2, 0),
            ),
            document.toc,
        )
    }

    @Test
    fun `nav without epub type attribute still provides the toc`() {
        val nav = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Nav</title></head>
<body><nav><ol><li><a href="text/ch1.xhtml">Only Chapter</a></li></ol></nav></body></html>"""
        val bytes = Fixtures.book(
            opf = Fixtures.opf(
                manifestInner = listOf(
                    Fixtures.item("nav", "nav.xhtml", "application/xhtml+xml", "nav"),
                    Fixtures.item("ch1", "text/ch1.xhtml", "application/xhtml+xml"),
                ).joinToString("\n    "),
                spineInner = Fixtures.itemref("ch1"),
            ),
            entries = mapOf(Fixtures.NAV_PATH to nav, Fixtures.CH1 to Fixtures.chapterTwo()),
        )
        val document = document(bytes)
        assertEquals(listOf(BookTocEntry("Only Chapter", 0, 0)), document.toc)
    }

    @Test
    fun `empty spine yields an empty chapter list and toc but still parses`() {
        val bytes = Fixtures.book(
            opf = Fixtures.opf(
                manifestInner = Fixtures.item("ch1", "text/ch1.xhtml", "application/xhtml+xml"),
                spineInner = "",
            ),
            entries = mapOf(Fixtures.CH1 to Fixtures.chapterTwo()),
        )
        val document = document(bytes)
        assertTrue(document.chapters.isEmpty())
        assertTrue(document.toc.isEmpty())
        assertNull(document.cover)
    }

    @Test
    fun `media type constant matches the epub specification`() {
        assertEquals("application/epub+zip", EpubParser.MIME)
    }

    @Test
    fun `prefers the rootfile declared with the oebps package media type`() {
        val container = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/decoy.txt" media-type="text/plain"/>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
        val bytes = Fixtures.epub3Builder().replace(Fixtures.CONTAINER_PATH, container).build()
        assertEquals(Fixtures.TITLE, document(bytes).title)
    }

    @Test
    fun `falls back to the first rootfile when nothing declares the oebps media type`() {
        val container = Fixtures.container(rootfile = Fixtures.OPF_PATH, mediaType = "text/plain")
        val bytes = Fixtures.epub3Builder().replace(Fixtures.CONTAINER_PATH, container).build()
        assertEquals(Fixtures.TITLE, document(bytes).title)
    }

    @Test
    fun `resolves manifest and image hrefs relative to a nested package document`() {
        val opfPath = "OEBPS/opf/content.opf"
        val chapterPath = "OEBPS/text/nested.xhtml"
        val chapter = Fixtures.xhtml("""<h1>Nested Layout</h1><p><img src="../images/pic.png" alt="Pic"/></p>""")
        val opf = Fixtures.opf(
            manifestInner = listOf(
                Fixtures.item("ch1", "../text/nested.xhtml", "application/xhtml+xml"),
                Fixtures.item("pic", "../images/pic.png", "image/png"),
            ).joinToString("\n    "),
            spineInner = Fixtures.itemref("ch1"),
        )
        val bytes = ZipBuilder()
            .add("mimetype", Fixtures.MIMETYPE)
            .add(Fixtures.CONTAINER_PATH, Fixtures.container(rootfile = opfPath))
            .add(opfPath, opf)
            .add(chapterPath, chapter)
            .addBytes(Fixtures.PIC, Fixtures.PIC_BYTES)
            .build()
        val document = document(bytes)
        assertEquals(listOf(chapterPath), document.chapters.map { it.href })
        val image = document.chapters.single().blocks.filterIsInstance<BookBlock.Image>().single()
        assertEquals(Fixtures.PIC, image.entry)
        assertArrayEquals(Fixtures.PIC_BYTES, document.imageBytes(image.entry))
    }

    @Test
    fun `chapter title is truncated to 120 characters`() {
        val longHeading = "H".repeat(200)
        val bytes = Fixtures.book(
            opf = Fixtures.opf(
                manifestInner = Fixtures.item("ch1", "text/ch1.xhtml", "application/xhtml+xml"),
                spineInner = Fixtures.itemref("ch1"),
            ),
            entries = mapOf(Fixtures.CH1 to Fixtures.xhtml("<h1>$longHeading</h1>")),
        )
        val document = document(bytes)
        assertEquals(120, document.chapters.single().title?.length)
    }
}
