package com.cipherwell.notezero.book

import java.nio.charset.Charset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FictionBook 2 mapping, metadata, images and security behaviour. */
class Fb2BookParserTest {

    private val fallback = BookFormatFixtures.FALLBACK_TITLE

    private fun parse(xml: String): BookContent = parse(xml.toByteArray(Charsets.UTF_8))

    private fun parse(bytes: ByteArray): BookContent {
        val content = Fb2BookParser.parse(bytes, fallback)
        return requireNotNull(content) { "expected a parsed FB2 document" }
    }

    private fun parseOrNull(xml: String): BookContent? =
        Fb2BookParser.parse(xml.toByteArray(Charsets.UTF_8), fallback)

    private fun blocks(content: BookContent, chapter: Int = 0): List<BookBlock> = content.chapters[chapter].blocks

    // ---- metadata -----------------------------------------------------------

    @Test
    fun `metadata is read from description title-info`() {
        val content = parse(Fb2Fixtures.simple())
        assertEquals("Fixture FB2", content.title)
        assertEquals("Ada Byron Lovelace", content.author)
        assertEquals("en", content.language)
    }

    @Test
    fun `author falls back to nickname and is null when absent`() {
        val nickname = parse(
            BookFormatFixtures.utf8(
                Fb2Fixtures.document(
                    description = Fb2Fixtures.description(Fb2Fixtures.titleInfo(authors = Fb2Fixtures.author(nickname = "Anon"))),
                    bodies = Fb2Fixtures.body(Fb2Fixtures.section("One", "<p>x</p>")),
                ),
            ),
        )
        assertEquals("Anon", nickname.author)

        val noAuthor = parse(
            BookFormatFixtures.utf8(
                Fb2Fixtures.document(
                    description = Fb2Fixtures.description(Fb2Fixtures.titleInfo(authors = "")),
                    bodies = Fb2Fixtures.body(Fb2Fixtures.section("One", "<p>x</p>")),
                ),
            ),
        )
        assertNull(noAuthor.author)
        assertEquals("en", noAuthor.language)
    }

    @Test
    fun `title falls back to the app-provided name and last resort is Untitled`() {
        val noTitle = parse(
            BookFormatFixtures.utf8(
                Fb2Fixtures.document(
                    description = Fb2Fixtures.description(Fb2Fixtures.titleInfo(bookTitle = null)),
                    bodies = Fb2Fixtures.body(Fb2Fixtures.section("One", "<p>x</p>")),
                ),
            ),
        )
        assertEquals(fallback, noTitle.title)

        val blankFallback = Fb2BookParser.parse(
            BookFormatFixtures.utf8(
                Fb2Fixtures.document(
                    description = Fb2Fixtures.description(Fb2Fixtures.titleInfo(bookTitle = null)),
                    bodies = Fb2Fixtures.body(Fb2Fixtures.section("One", "<p>x</p>")),
                ),
            ),
            "   ",
        )
        assertNotNull(blankFallback)
        assertEquals("Untitled", blankFallback!!.title)
    }

    @Test
    fun `metadata entities are decoded and whitespace collapsed`() {
        val content = parse(
            BookFormatFixtures.utf8(
                Fb2Fixtures.document(
                    description = Fb2Fixtures.description(
                        Fb2Fixtures.titleInfo(bookTitle = "A &amp; B &#8212; C"),
                    ),
                    bodies = Fb2Fixtures.body(Fb2Fixtures.section("One", "<p>x</p>")),
                ),
            ),
        )
        assertEquals("A & B \u2014 C", content.title)
    }

    // ---- chapters / sections ------------------------------------------------

    @Test
    fun `top-level sections become chapters with titles and TOC entries`() {
        val xml = BookFormatFixtures.utf8(
            Fb2Fixtures.document(
                bodies = Fb2Fixtures.body(
                    Fb2Fixtures.section("Chapter One", "<p>First body.</p>") +
                        Fb2Fixtures.section("Chapter Two", "<p>Second body.</p>"),
                ),
            ),
        )
        val content = parse(xml)
        assertEquals(2, content.chapters.size)
        assertEquals("Chapter One", content.chapters[0].title)
        assertEquals("Chapter Two", content.chapters[1].title)
        assertEquals(listOf(0, 1), content.chapters.map { it.index })
        assertEquals(2, content.toc.size)
        assertEquals(BookTocEntry("Chapter One", 0, 0), content.toc[0])
        assertEquals(BookTocEntry("Chapter Two", 1, 0), content.toc[1])
        assertTrue(content.chapters[0].plainText().contains("First body."))
    }

    @Test
    fun `nested sections become heading blocks and deeper TOC entries`() {
        val nested = "<section id=\"nested\">" + Fb2Fixtures.heading("Nested Section") + "<p>Nested body.</p></section>"
        val xml = BookFormatFixtures.utf8(
            Fb2Fixtures.document(
                bodies = Fb2Fixtures.body(Fb2Fixtures.section("Chapter One", "<p>Outer body.</p>" + nested)),
            ),
        )
        val content = parse(xml)
        assertEquals(1, content.chapters.size)
        val blocks = blocks(content)
        assertEquals(BookBlock.Heading(1, listOf(BookInline("Chapter One"))), blocks[0])
        assertEquals(BookBlock.Paragraph(listOf(BookInline("Outer body."))), blocks[1])
        assertEquals(BookBlock.Heading(2, listOf(BookInline("Nested Section"))), blocks[2])
        assertEquals(BookBlock.Paragraph(listOf(BookInline("Nested body."))), blocks[3])
        assertEquals(2, content.toc.size)
        assertEquals(BookTocEntry("Chapter One", 0, 0), content.toc[0])
        assertEquals(BookTocEntry("Nested Section", 0, 1), content.toc[1])
    }

    @Test
    fun `body title and content outside sections become a front-matter chapter`() {
        val xml = BookFormatFixtures.utf8(
            Fb2Fixtures.document(
                bodies = "<body>" + Fb2Fixtures.heading("Front Matter") + "<p>Preface.</p>" +
                    Fb2Fixtures.section("Chapter One", "<p>Body.</p>") + "</body>",
            ),
        )
        val content = parse(xml)
        assertEquals(2, content.chapters.size)
        assertEquals("Front Matter", content.chapters[0].title)
        assertEquals(BookBlock.Heading(1, listOf(BookInline("Front Matter"))), blocks(content, 0)[0])
        assertEquals("Chapter One", content.chapters[1].title)
        assertEquals(BookTocEntry("Front Matter", 0, 0), content.toc[0])
    }

    @Test
    fun `additional named bodies are appended as chapters`() {
        val xml = BookFormatFixtures.utf8(
            Fb2Fixtures.document(
                bodies = Fb2Fixtures.body(Fb2Fixtures.section("Main", "<p>Main text.</p>")) +
                    Fb2Fixtures.body(Fb2Fixtures.section("Notes", "<p>Note text.</p>"), name = "notes"),
            ),
        )
        val content = parse(xml)
        assertEquals(2, content.chapters.size)
        assertEquals("Main", content.chapters[0].title)
        assertEquals("Notes", content.chapters[1].title)
    }

    @Test
    fun `sections without titles get a derived TOC when no titles exist`() {
        val xml = BookFormatFixtures.utf8(
            Fb2Fixtures.document(bodies = Fb2Fixtures.body(Fb2Fixtures.section(null, "<p>Alpha.</p>"))),
        )
        val content = parse(xml)
        assertEquals(1, content.chapters.size)
        assertNull(content.chapters[0].title)
        assertEquals(BookTocEntry("Chapter 1", 0, 0), content.toc[0])
    }

    // ---- inline styles ------------------------------------------------------

    @Test
    fun `p emphasis strong code and links map to inline flags`() {
        val xml = BookFormatFixtures.utf8(
            Fb2Fixtures.document(
                bodies = Fb2Fixtures.body(
                    Fb2Fixtures.section(
                        "Chapter",
                        "<p>Plain <emphasis>italic</emphasis> and <strong>bold</strong> and " +
                            "<code>snippet</code> and <a l:href=\"#note1\">link label</a>.</p>",
                    ),
                ),
            ),
        )
        val content = parse(xml)
        val paragraph = blocks(content).filterIsInstance<BookBlock.Paragraph>().first()
        val italic = paragraph.runs.first { it.text == "italic" }
        assertTrue(italic.italic)
        assertFalse(italic.bold)
        val bold = paragraph.runs.first { it.text == "bold" }
        assertTrue(bold.bold)
        val code = paragraph.runs.first { it.text == "snippet" }
        assertTrue(code.code)
        val link = paragraph.runs.first { it.text == "link label" }
        assertTrue(link.link)
        assertEquals("Plain italic and bold and snippet and link label.", runText(paragraph.runs))
    }

    @Test
    fun `subtitle becomes a heading one level below the section title`() {
        val xml = BookFormatFixtures.utf8(
            Fb2Fixtures.document(
                bodies = Fb2Fixtures.body(
                    Fb2Fixtures.section("Chapter", "<subtitle>Scene One</subtitle><p>Body.</p>"),
                ),
            ),
        )
        val content = parse(xml)
        assertEquals(BookBlock.Heading(2, listOf(BookInline("Scene One"))), blocks(content)[1])
    }

    // ---- poems / epigraphs / cites -----------------------------------------

    @Test
    fun `poem stanzas keep line breaks in a quote block`() {
        val poem = "<poem>" + Fb2Fixtures.heading("A Poem") + "<stanza><v>line one</v><v>line two</v></stanza></poem>"
        val xml = BookFormatFixtures.utf8(
            Fb2Fixtures.document(bodies = Fb2Fixtures.body(Fb2Fixtures.section("Chapter", poem))),
        )
        val content = parse(xml)
        val quote = blocks(content).filterIsInstance<BookBlock.Quote>().single()
        assertEquals("line one\nline two", runText(quote.runs))
        assertEquals(BookBlock.Heading(1, listOf(BookInline("A Poem"))), blocks(content)[1])
        // The poem title must not hijack the chapter title.
        assertEquals("Chapter", content.chapters[0].title)
    }

    @Test
    fun `epigraph and cite become quotes with italic text-author`() {
        val epigraph = "<epigraph><p>Quoted words.</p><text-author>Someone</text-author></epigraph>"
        val cite = "<cite><p>Cited words.</p></cite>"
        val xml = BookFormatFixtures.utf8(
            Fb2Fixtures.document(bodies = Fb2Fixtures.body(Fb2Fixtures.section("Chapter", epigraph + cite))),
        )
        val content = parse(xml)
        val rendered = blocks(content)
        assertEquals(BookBlock.Quote(listOf(BookInline("Quoted words."))), rendered[1])
        val author = rendered[2] as BookBlock.Paragraph
        assertEquals("Someone", runText(author.runs))
        assertTrue(author.runs.all { it.italic })
        assertEquals(BookBlock.Quote(listOf(BookInline("Cited words."))), rendered[3])
    }

    @Test
    fun `empty-line becomes a rule`() {
        val xml = BookFormatFixtures.utf8(
            Fb2Fixtures.document(
                bodies = Fb2Fixtures.body(Fb2Fixtures.section("Chapter", "<p>one</p><empty-line/><p>two</p>")),
            ),
        )
        val content = parse(xml)
        assertEquals(
            listOf(BookBlock.Paragraph(listOf(BookInline("one"))), BookBlock.Rule, BookBlock.Paragraph(listOf(BookInline("two")))),
            blocks(content).drop(1),
        )
    }

    // ---- images / cover -----------------------------------------------------

    @Test
    fun `base64 binary images are served by imageBytes as copies`() {
        val xml = BookFormatFixtures.utf8(
            Fb2Fixtures.document(
                bodies = Fb2Fixtures.body(
                    Fb2Fixtures.section("Chapter", "<p>Before.</p><image l:href=\"#pic.png\"/>"),
                ),
                binaries = Fb2Fixtures.binary("pic.png", BookFormatFixtures.PNG_BYTES),
            ),
        )
        val content = parse(xml)
        val image = blocks(content).filterIsInstance<BookBlock.Image>().single()
        assertEquals("pic.png", image.entry)

        val first = content.imageBytes("pic.png")
        assertNotNull(first)
        assertArrayEquals(BookFormatFixtures.PNG_BYTES, first)
        first!![0] = 0x00
        assertArrayEquals(BookFormatFixtures.PNG_BYTES, content.imageBytes("pic.png"))
        assertArrayEquals(BookFormatFixtures.PNG_BYTES, content.imageBytes("#pic.png"))
        assertNull(content.imageBytes("unknown.png"))
        assertNull(content.cover)
    }

    @Test
    fun `coverpage image becomes the cover and is dropped after close`() {
        val xml = BookFormatFixtures.utf8(
            Fb2Fixtures.document(
                description = Fb2Fixtures.description(Fb2Fixtures.titleInfo(coverpage = "cover.jpg")),
                bodies = Fb2Fixtures.body(Fb2Fixtures.section("Chapter", "<p>Body.</p>")),
                binaries = Fb2Fixtures.binary("cover.jpg", BookFormatFixtures.JPEG_BYTES, "image/jpeg"),
            ),
        )
        val content = parse(xml)
        assertArrayEquals(BookFormatFixtures.JPEG_BYTES, content.cover)
        content.close()
        assertNull(content.cover)
        assertNull(content.imageBytes("cover.jpg"))
        assertEquals("Fixture FB2", content.title)
        content.close()
    }

    @Test
    fun `windows-1251 declared in the XML prolog is honored`() {
        val cyrillicTitle = "\u041f\u0440\u0438\u0432\u0435\u0442 \u043c\u0438\u0440"
        val cyrillicBody = "\u0422\u0435\u043a\u0441\u0442"
        val xml = """<?xml version="1.0" encoding="windows-1251"?>
<FictionBook xmlns="${Fb2Fixtures.NS}">
<description><title-info><book-title>$cyrillicTitle</book-title><lang>ru</lang></title-info></description>
<body><section><p>$cyrillicBody</p></section></body>
</FictionBook>"""
        val content = Fb2BookParser.parse(xml.toByteArray(Charset.forName("windows-1251")), fallback)
        assertNotNull(content)
        assertEquals(cyrillicTitle, content!!.title)
        assertEquals("ru", content.language)
        assertTrue(content.chapters[0].plainText().contains(cyrillicBody))
    }

    @Test
    fun `a coverpage without a matching binary yields no cover`() {
        val xml = BookFormatFixtures.utf8(
            Fb2Fixtures.document(
                description = Fb2Fixtures.description(Fb2Fixtures.titleInfo(coverpage = "missing.jpg")),
                bodies = Fb2Fixtures.body(Fb2Fixtures.section("Chapter", "<p>Body.</p>")),
            ),
        )
        assertNull(parse(xml).cover)
    }

    @Test
    fun `invalid base64 and non-image binaries are ignored`() {
        val xml = BookFormatFixtures.utf8(
            Fb2Fixtures.document(
                bodies = Fb2Fixtures.body(
                    Fb2Fixtures.section("Chapter", "<image l:href=\"#bad\"/><image l:href=\"#font\"/>"),
                ),
                binaries = "<binary id=\"bad\" content-type=\"image/png\">not-base64!!!</binary>" +
                    Fb2Fixtures.binary("font", BookFormatFixtures.PNG_BYTES, contentType = "application/font"),
            ),
        )
        val content = parse(xml)
        assertNull(content.imageBytes("bad"))
        assertNull(content.imageBytes("font"))
    }

    @Test
    fun `images beyond the 64 image cap are not stored`() {
        val binaries = buildString {
            for (index in 0..64) append(Fb2Fixtures.binary("img$index", byteArrayOf(index.toByte())))
        }
        val refs = buildString {
            for (index in 0..64) append("<image l:href=\"#img$index\"/>")
        }
        val xml = BookFormatFixtures.utf8(
            Fb2Fixtures.document(
                bodies = Fb2Fixtures.body(Fb2Fixtures.section("Chapter", refs)),
                binaries = binaries,
            ),
        )
        val content = parse(xml)
        assertNotNull(content.imageBytes("img0"))
        assertNotNull(content.imageBytes("img63"))
        assertNull("the 65th image is over the cap", content.imageBytes("img64"))
    }

    @Test
    fun `images over 4 MiB decoded are dropped`() {
        val oversized = ByteArray(BookLimits.MAX_FB2_IMAGE_BYTES + 1)
        val xml = BookFormatFixtures.utf8(
            Fb2Fixtures.document(
                bodies = Fb2Fixtures.body(Fb2Fixtures.section("Chapter", "<image l:href=\"#huge\"/>")),
                binaries = Fb2Fixtures.binary("huge", oversized),
            ),
        )
        val content = parse(xml)
        assertNull(content.imageBytes("huge"))
    }

    // ---- security / failure policy -----------------------------------------

    @Test
    fun `malformed XML returns null`() {
        assertNull(parseOrNull("<FictionBook xmlns=\"${Fb2Fixtures.NS}\"><body>"))
        assertNull(parseOrNull("<FictionBook><body><section><p>unclosed</section></body></FictionBook>"))
        assertNull(parseOrNull(""))
        assertNull(parseOrNull("not xml at all"))
    }

    @Test
    fun `non-FictionBook roots return null`() {
        assertNull(parseOrNull("<html><body><p>hi</p></body></html>"))
        assertNull(parseOrNull("<FictionBook2><body/></FictionBook2>"))
    }

    @Test
    fun `internal DTD subsets and entity bombs are refused`() {
        val entityBomb = """<?xml version="1.0"?>
<!DOCTYPE FictionBook [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;">]>
<FictionBook xmlns="${Fb2Fixtures.NS}"><body><section><p>&lol2;</p></section></body></FictionBook>"""
        assertNull(parseOrNull(entityBomb))

        val xxe = """<?xml version="1.0"?>
<!DOCTYPE FictionBook [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
<FictionBook xmlns="${Fb2Fixtures.NS}"><body><section><p>&xxe;</p></section></body></FictionBook>"""
        assertNull(parseOrNull(xxe))
    }

    @Test
    fun `external DTD reference is tolerated but never resolved`() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE FictionBook SYSTEM "http://www.fictionbook.org/FictionBook2.dtd">
<FictionBook xmlns="${Fb2Fixtures.NS}"><body><section><title><p>One</p></title><p>Body.</p></section></body></FictionBook>"""
        val content = Fb2BookParser.parse(xml.toByteArray(Charsets.UTF_8), fallback)
        assertNotNull("an external DOCTYPE must not be fetched but must not fail either", content)
        assertEquals("One", content!!.chapters[0].title)
    }

    @Test
    fun `unresolvable external entity makes the document malformed`() {
        val xml = """<?xml version="1.0"?>
<!DOCTYPE FictionBook SYSTEM "http://example.com/missing.dtd">
<FictionBook xmlns="${Fb2Fixtures.NS}"><body><section><p>&missing;</p></section></body></FictionBook>"""
        assertNull(parseOrNull(xml))
    }

    @Test
    fun `UTF-16 documents are refused`() {
        val xml = Fb2Fixtures.document(bodies = Fb2Fixtures.body(Fb2Fixtures.section("One", "<p>x</p>")))
        assertNull(Fb2BookParser.parse(BookFormatFixtures.utf16Le(xml), fallback))
    }

    @Test
    fun `a document without a body returns null`() {
        val xml = Fb2Fixtures.document(description = Fb2Fixtures.description(Fb2Fixtures.titleInfo()), bodies = "")
        assertNull(parseOrNull(xml))
    }

    @Test
    fun `input over the 32 MiB cap is refused without parsing`() {
        val huge = ByteArray(BookLimits.MAX_FB2_XML_BYTES + 1)
        assertNull(Fb2BookParser.parse(huge, fallback))
    }

    @Test
    fun `namespace-prefixed root is accepted`() {
        val xml = """<?xml version="1.0"?>
<fb:FictionBook xmlns:fb="${Fb2Fixtures.NS}" xmlns:l="${Fb2Fixtures.XLINK}">
<fb:description><fb:title-info><fb:book-title>Prefixed</fb:book-title></fb:title-info></fb:description>
<fb:body><fb:section><fb:title><fb:p>One</fb:p></fb:title><fb:p>Body.</fb:p></fb:section></fb:body>
</fb:FictionBook>"""
        val content = Fb2BookParser.parse(xml.toByteArray(Charsets.UTF_8), fallback)
        assertNotNull(content)
        assertEquals("Prefixed", content!!.title)
        assertEquals("One", content.chapters[0].title)
    }

    // ---- limits -------------------------------------------------------------

    @Test
    fun `chapters are capped at 2000`() {
        val xml = BookFormatFixtures.utf8(
            Fb2Fixtures.document(
                bodies = Fb2Fixtures.body(
                    (0 until 2_100).joinToString("") { index ->
                        Fb2Fixtures.section("Chapter $index", "<p>Body $index.</p>")
                    },
                ),
            ),
        )
        val content = parse(xml)
        assertEquals(BookLimits.MAX_REFOWABLE_CHAPTERS, content.chapters.size)
    }

    @Test
    fun `blocks per chapter are capped at 20000`() {
        val paragraphs = buildString {
            for (index in 0 until 20_001) append("<p>x</p>")
        }
        val xml = BookFormatFixtures.utf8(
            Fb2Fixtures.document(bodies = Fb2Fixtures.body(Fb2Fixtures.section("Chapter", paragraphs))),
        )
        val content = parse(xml)
        assertEquals(1, content.chapters.size)
        assertEquals(BookLimits.MAX_BLOCKS_PER_CHAPTER, blocks(content).size)
    }

    @Test
    fun `text per chapter is capped at 2 million characters`() {
        val longParagraph = "a".repeat(BookLimits.MAX_TEXT_PER_CHAPTER + 100)
        val xml = BookFormatFixtures.utf8(
            Fb2Fixtures.document(bodies = Fb2Fixtures.body(Fb2Fixtures.section(null, "<p>$longParagraph</p>"))),
        )
        val content = parse(xml)
        assertEquals(BookLimits.MAX_TEXT_PER_CHAPTER, content.chapters[0].plainText().length)
    }

    // ---- lifecycle ----------------------------------------------------------

    @Test
    fun `close is idempotent and keeps parsed state readable`() {
        val xml = BookFormatFixtures.utf8(
            Fb2Fixtures.document(
                bodies = Fb2Fixtures.body(Fb2Fixtures.section("Chapter", "<p>Body.</p><image l:href=\"#pic\"/>")),
                binaries = Fb2Fixtures.binary("pic", BookFormatFixtures.PNG_BYTES),
            ),
        )
        val content = parse(xml)
        assertNotNull(content.imageBytes("pic"))
        content.close()
        content.close()
        content.close()
        assertNull(content.imageBytes("pic"))
        assertEquals(1, content.chapters.size)
        assertEquals("Chapter", content.chapters[0].title)
        assertTrue(content.chapters[0].plainText().contains("Body."))
    }

    @Test
    fun `epigraph before the first body section is kept in the front-matter chapter`() {
        val xml = BookFormatFixtures.utf8(
            Fb2Fixtures.document(
                bodies = "<body><epigraph><p>Opening quote.</p></epigraph>" +
                    Fb2Fixtures.section("Chapter One", "<p>Body.</p>") + "</body>",
            ),
        )
        val content = parse(xml)
        assertEquals(2, content.chapters.size)
        assertEquals(BookBlock.Quote(listOf(BookInline("Opening quote."))), blocks(content, 0)[0])
    }

    private fun runText(runs: List<BookInline>): String = runs.joinToString("") { it.text }
}
