package com.cipherwell.notezero.book

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Detection matrix: content sniffing order and rejection of unknown/truncated/ambiguous input. */
class BookDetectorTest {

    @Test
    fun `detects an EPUB by mimetype and container`() {
        assertEquals(BookFormat.EPUB, BookDetector.detect(Fixtures.epub3()))
        assertEquals(BookFormat.EPUB, BookDetector.detect(Fixtures.epub2()))
    }

    @Test
    fun `detects an EPUB from the mimetype entry alone`() {
        val bytes = ZipBuilder().add("mimetype", EpubParser.MIME).build()
        assertEquals(BookFormat.EPUB, BookDetector.detect(bytes))
    }

    @Test
    fun `detects an EPUB from META-INF container alone`() {
        val bytes = ZipBuilder().add(Fixtures.CONTAINER_PATH, Fixtures.container()).build()
        assertEquals(BookFormat.EPUB, BookDetector.detect(bytes))
    }

    @Test
    fun `detects FB2 with and without namespace prefixes`() {
        assertEquals(BookFormat.FB2, BookDetector.detect(Fb2Fixtures.simple()))

        val prefixed = """<?xml version="1.0" encoding="utf-8"?>
<fb:FictionBook xmlns:fb="${Fb2Fixtures.NS}"><fb:body><fb:section><fb:p>x</fb:p></fb:section></fb:body></fb:FictionBook>"""
        assertEquals(BookFormat.FB2, BookDetector.detect(prefixed.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `detects FB2 behind comments DOCTYPE and BOM`() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<!-- generated -->
<!DOCTYPE FictionBook SYSTEM "http://www.fictionbook.org/FictionBook2.dtd">
<FictionBook xmlns="${Fb2Fixtures.NS}"><body><section><p>x</p></section></body></FictionBook>"""
        val bytes = BookFormatFixtures.utf8Bom(xml)
        assertEquals(BookFormat.FB2, BookDetector.detect(bytes))
    }

    @Test
    fun `detects TXT in its supported encodings`() {
        assertEquals(BookFormat.TXT, BookDetector.detect(BookFormatFixtures.utf8("Plain text book.\n\nChapter two.")))
        assertEquals(BookFormat.TXT, BookDetector.detect(BookFormatFixtures.utf8Bom("Plain text book.")))
        assertEquals(BookFormat.TXT, BookDetector.detect(BookFormatFixtures.utf16Le("Plain text book.")))
        assertEquals(BookFormat.TXT, BookDetector.detect(BookFormatFixtures.utf16Be("Plain text book.")))
        assertEquals(
            BookFormat.TXT,
            BookDetector.detect(BookFormatFixtures.windows1252("caf\u00e9 na\u00efve r\u00e9sum\u00e9")),
        )
    }

    @Test
    fun `detects a CBZ archive of page images`() {
        val cbz = BookFormatFixtures.cbz(
            "page1.jpg" to BookFormatFixtures.JPEG_BYTES,
            "page2.png" to BookFormatFixtures.PNG_BYTES,
        )
        assertEquals(BookFormat.CBZ, BookDetector.detect(cbz))
    }

    @Test
    fun `an EPUB wins over CBZ when the archive carries both markers`() {
        val bytes = BookFormatFixtures.cbz(
            "page1.jpg" to BookFormatFixtures.JPEG_BYTES,
            "mimetype" to EpubParser.MIME.toByteArray(Charsets.UTF_8),
        )
        assertEquals(BookFormat.EPUB, BookDetector.detect(bytes))
    }

    @Test
    fun `a ZIP without EPUB markers or images is not a supported book`() {
        val bytes = ZipBuilder().add("notes.txt", "plain text").add("readme", "hello").build()
        assertNull(BookDetector.detect(bytes))
    }

    @Test
    fun `empty input is unsupported`() {
        assertNull(BookDetector.detect(ByteArray(0)))
    }

    @Test
    fun `whitespace-only and NUL-containing payloads are not TXT`() {
        assertNull(BookDetector.detect(BookFormatFixtures.utf8("   \n\n\t\t ")))
        assertNull(BookDetector.detect(BookFormatFixtures.utf8("text") + byteArrayOf(0x00) + BookFormatFixtures.utf8("more")))
    }

    @Test
    fun `random blobs are rejected`() {
        val random = Random(0x5EED)
        for (length in intArrayOf(1, 2, 3, 8, 64, 1024, 8192)) {
            val bytes = ByteArray(length)
            random.nextBytes(bytes)
            // A blob can only ever be plain text, never one of the binary container formats.
            val format = BookDetector.detect(bytes)
            if (format != null) assertEquals(BookFormat.TXT, format)
        }
    }

    @Test
    fun `truncated EPUB and CBZ archives are rejected`() {
        val epub = Fixtures.epub3()
        assertNull(BookDetector.detect(epub.copyOfRange(0, epub.size / 2)))

        val cbz = BookFormatFixtures.cbz("page1.jpg" to BookFormatFixtures.JPEG_BYTES)
        assertNull(BookDetector.detect(cbz.copyOfRange(0, cbz.size / 2)))
    }

    @Test
    fun `a non-FictionBook XML root is not FB2`() {
        val xhtml = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body><p>x</p></body></html>"""
        assertEquals(BookFormat.TXT, BookDetector.detect(xhtml.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `PK letters at the start of a text file are not a ZIP signature`() {
        val text = "PK is a nice prefix for a text file, isn't it?".toByteArray(Charsets.UTF_8)
        assertEquals(BookFormat.TXT, BookDetector.detect(text))
    }

    @Test
    fun `a corrupt archive with a valid ZIP signature is not a book`() {
        val bytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00)
        assertNull(BookDetector.detect(bytes))
    }

    @Test
    fun `an unknown two-byte-prefix binary is rejected`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00, 0x01, 0x02, 0x03, 0x04)
        assertNull(BookDetector.detect(bytes))
    }

    @Test
    fun `detection is stable and side-effect free`() {
        val bytes = Fb2Fixtures.simple()
        assertEquals(BookDetector.detect(bytes), BookDetector.detect(bytes))
        assertNull(BookDetector.detect(ByteArray(0)))
        assertEquals(BookFormat.EPUB, BookDetector.detect(Fixtures.epub3()))
    }
}
