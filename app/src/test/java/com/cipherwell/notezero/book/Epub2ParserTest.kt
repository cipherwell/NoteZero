package com.cipherwell.notezero.book

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** EPUB2 coverage: NCX navigation, `meta name="cover"`, XHTML-with-DOCTYPE content. */
class Epub2ParserTest {

    private fun document(bytes: ByteArray = Fixtures.epub2()): EpubDocument =
        requireNotNull(EpubParser.parse(bytes)) { "fixture failed to parse" }

    @Test
    fun `parses epub2 metadata`() {
        val document = document()
        assertEquals("An EPUB Two Book", document.title)
        assertEquals("Grace Hopper", document.author)
        assertEquals("en-US", document.language)
    }

    @Test
    fun `maps nested ncx nav map to spine indexes and depths`() {
        val document = document()
        assertEquals(
            listOf(
                BookTocEntry("Chapter One", 0, 0),
                BookTocEntry("Section One A", 0, 1),
                BookTocEntry("Chapter Two", 1, 0),
            ),
            document.toc,
        )
    }

    @Test
    fun `parses epub2 chapters and tolerates external xhtml doctype`() {
        val document = document()
        assertEquals(listOf(Fixtures.CH1, Fixtures.CH2), document.chapters.map { it.href })
        assertEquals(listOf("Chapter One", "Chapter Two"), document.chapters.map { it.title })
        val text = document.chapters[0].plainText()
        assertTrue(text.contains("An EPUB2 paragraph"))
        assertTrue("external-DTD named entity must be decoded", text.contains("\u00a0"))
        assertTrue("accent entity must be decoded", text.contains("\u00e9"))
        val heading = document.chapters[0].blocks.first()
        assertEquals(BookBlock.Heading(1, listOf(BookInline("Chapter One"))), heading)
    }

    @Test
    fun `reads epub2 cover from metadata cover meta`() {
        val document = document()
        val cover = document.cover
        assertNotNull(cover)
        assertArrayEquals(Fixtures.COVER_BYTES, cover)
    }

    @Test
    fun `uses ncx from manifest when the spine has no toc attribute`() {
        val bytes = Fixtures.book(
            opf = Fixtures.opf(
                version = "2.0",
                metadataInner = Fixtures.epub2Metadata(),
                manifestInner = Fixtures.epub2Manifest(),
                spineInner = listOf(Fixtures.itemref("ch1"), Fixtures.itemref("ch2")).joinToString("\n    "),
            ),
            entries = mapOf(
                Fixtures.NCX_PATH to Fixtures.epub2Ncx(),
                Fixtures.CH1 to Fixtures.epub2ChapterOne(),
                Fixtures.CH2 to Fixtures.epub2ChapterTwo(),
            ),
            extraBytes = mapOf(Fixtures.COVER to Fixtures.COVER_BYTES),
        )
        val document = document(bytes)
        assertEquals(3, document.toc.size)
        assertEquals(BookTocEntry("Section One A", 0, 1), document.toc[1])
    }

    @Test
    fun `prefers epub3 nav over an ncx present in the same book`() {
        val manifest = Fixtures.epub3Manifest() + "\n    " +
            Fixtures.item("ncx", "toc.ncx", "application/x-dtbncx+xml")
        val bytes = Fixtures.epub3Builder()
            .replace(
                Fixtures.OPF_PATH,
                Fixtures.opf(
                    manifestInner = manifest,
                    spineInner = Fixtures.epub3Spine(),
                    spineAttributes = """ toc="ncx"""",
                ),
            )
            .add(Fixtures.NCX_PATH, Fixtures.epub2Ncx())
            .build()
        val document = document(bytes)
        assertEquals(listOf("Chapter One", "Part Two", "Chapter Three"), document.toc.map { it.title })
    }

    @Test
    fun `falls back to derived toc when ncx is malformed or missing`() {
        val manifest = listOf(
            Fixtures.item("ncx", "toc.ncx", "application/x-dtbncx+xml"),
            Fixtures.item("ch1", "text/ch1.xhtml", "application/xhtml+xml"),
        ).joinToString("\n    ")
        for (ncx in listOf("<ncx><navMap><navPoint>", "")) {
            val bytes = Fixtures.book(
                opf = Fixtures.opf(
                    version = "2.0",
                    metadataInner = Fixtures.epub2Metadata(),
                    manifestInner = manifest,
                    spineInner = Fixtures.itemref("ch1"),
                    spineAttributes = """ toc="ncx"""",
                ),
                entries = mapOf(Fixtures.NCX_PATH to ncx, Fixtures.CH1 to Fixtures.chapterTwo()),
            )
            val document = document(bytes)
            assertEquals(listOf(BookTocEntry("Second Chapter", 0, 0)), document.toc)
        }
    }

    @Test
    fun `no cover metadata yields a null cover`() {
        val metadata = """<dc:identifier id="book-id">urn:uuid:no-cover</dc:identifier>
            <dc:title>No Cover</dc:title>"""
        val bytes = Fixtures.book(
            opf = Fixtures.opf(
                version = "2.0",
                metadataInner = metadata,
                manifestInner = Fixtures.item("ch1", "text/ch1.xhtml", "application/xhtml+xml"),
                spineInner = Fixtures.itemref("ch1"),
            ),
            entries = mapOf(Fixtures.CH1 to Fixtures.chapterTwo()),
        )
        val document = document(bytes)
        assertNull(document.cover)
        assertEquals("No Cover", document.title)
        assertNull(document.author)
    }
}
