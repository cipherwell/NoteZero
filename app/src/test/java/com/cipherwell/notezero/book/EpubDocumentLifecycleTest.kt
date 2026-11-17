package com.cipherwell.notezero.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lifecycle contract: bytes are retained until [EpubDocument.close], then dropped. */
class EpubDocumentLifecycleTest {

    @Test
    fun `bytes are available before close and dropped afterwards`() {
        val document = requireNotNull(EpubParser.parse(Fixtures.epub3()))
        assertNotNull(document.cover)
        assertNotNull(document.imageBytes(Fixtures.PIC))

        document.close()

        assertNull("cover must be dropped by close", document.cover)
        assertNull("image bytes must be dropped by close", document.imageBytes(Fixtures.PIC))
        // Parsed plain-Kotlin state stays usable after close (the UI keeps rendering).
        assertEquals(Fixtures.TITLE, document.title)
        assertEquals(3, document.chapters.size)
        assertEquals(BookTocEntry("Chapter One", 0, 0), document.toc.first())
        assertTrue(document.chapters[0].plainText().contains("Chapter One"))
    }

    @Test
    fun `close is idempotent and never throws`() {
        val document = requireNotNull(EpubParser.parse(Fixtures.epub3()))
        document.close()
        document.close()
        document.close()
        assertNull(document.cover)
        assertNull(document.imageBytes(Fixtures.PIC))
    }

    @Test
    fun `image reads return independent arrays and close does not affect other documents`() {
        val first = requireNotNull(EpubParser.parse(Fixtures.epub3()))
        val second = requireNotNull(EpubParser.parse(Fixtures.epub3()))
        val bytes = first.imageBytes(Fixtures.PIC)
        assertNotNull(bytes)
        bytes!![0] = 0x00
        val again = first.imageBytes(Fixtures.PIC)
        assertNotNull(again)
        assertEquals(Fixtures.PIC_BYTES[0], again!![0])

        first.close()
        assertNull(first.imageBytes(Fixtures.PIC))
        assertNotNull("closing one document must not affect another", second.imageBytes(Fixtures.PIC))
    }

    @Test
    fun `close before any image read still returns null`() {
        val document = requireNotNull(EpubParser.parse(Fixtures.epub3()))
        document.close()
        assertNull(document.imageBytes(Fixtures.PIC))
        assertNull(document.imageBytes("OEBPS/images/pic.png"))
    }

    @Test
    fun `document without image blocks holds no archive and returns null images`() {
        val bytes = Fixtures.book(
            opf = Fixtures.opf(
                manifestInner = Fixtures.item("ch1", "text/ch1.xhtml", "application/xhtml+xml"),
                spineInner = Fixtures.itemref("ch1"),
            ),
            entries = mapOf(Fixtures.CH1 to Fixtures.xhtml("<p>No images here.</p>")),
        )
        val document = requireNotNull(EpubParser.parse(bytes))
        assertNull(document.cover)
        assertNull(document.imageBytes(Fixtures.PIC))
        document.close()
        assertEquals(1, document.chapters.size)
    }

    @Test
    fun `plain text is stable across close`() {
        val document = requireNotNull(EpubParser.parse(Fixtures.epub3()))
        val before = document.chapters[0].plainText()
        document.close()
        val after = document.chapters[0].plainText()
        assertEquals(before, after)
        assertSame(document.chapters, document.chapters)
    }
}
