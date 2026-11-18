package com.cipherwell.notezero.book

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BookLoader dispatch across formats, plus the never-throw contract on hostile input. */
class BookLoaderTest {

    private val fallback = BookFormatFixtures.FALLBACK_TITLE

    private fun assertReflowable(result: LoadedBook?, format: BookFormat): BookContent {
        assertTrue("expected a reflowable $format", result is LoadedBook.Reflowable)
        val reflowable = result as LoadedBook.Reflowable
        assertEquals(format, reflowable.format)
        return reflowable.content
    }

    private fun LoadedBook.closeLoaded() {
        when (this) {
            is LoadedBook.Reflowable -> content.close()
            is LoadedBook.Comic -> comic.close()
        }
    }

    @Test
    fun `dispatches EPUB to the EPUB parser`() {
        val content = assertReflowable(BookLoader.load(Fixtures.epub3(), fallback), BookFormat.EPUB)
        assertEquals(Fixtures.TITLE, content.title)
        assertEquals(3, content.chapters.size)
        content.close()
    }

    @Test
    fun `dispatches FB2 to the FB2 parser`() {
        val content = assertReflowable(BookLoader.load(Fb2Fixtures.simple(), fallback), BookFormat.FB2)
        assertEquals("Fixture FB2", content.title)
        assertEquals("Ada Byron Lovelace", content.author)
        content.close()
    }

    @Test
    fun `dispatches TXT to the TXT parser`() {
        val content = assertReflowable(
            BookLoader.load(BookFormatFixtures.utf8("Chapter One\n\nHello."), "Notes.txt"),
            BookFormat.TXT,
        )
        assertEquals("Notes.txt", content.title)
        assertEquals(1, content.chapters.size)
        content.close()
    }

    @Test
    fun `dispatches CBZ to the comic parser`() {
        val cbz = BookFormatFixtures.cbz(
            "page1.jpg" to BookFormatFixtures.JPEG_BYTES,
            "page2.png" to BookFormatFixtures.PNG_BYTES,
        )
        val result = BookLoader.load(cbz, "Comic.cbz")
        assertTrue(result is LoadedBook.Comic)
        val comic = (result as LoadedBook.Comic).comic
        assertEquals(BookFormat.CBZ, result.format)
        assertEquals("Comic.cbz", comic.title)
        assertEquals(2, comic.pageCount)
        assertNotNull(comic.pageBytes(1))
        comic.close()
    }

    @Test
    fun `unsupported corrupt and empty input returns null`() {
        assertNull(BookLoader.load(ByteArray(0), fallback))
        assertNull(BookLoader.load(BookFormatFixtures.utf8("   "), fallback))
        assertNull(BookLoader.load(ByteArray(64) { 0x00 }, fallback))
        assertNull(BookLoader.load(Fixtures.epub3().copyOfRange(0, 100), fallback))
        assertNull(BookLoader.load(ZipBuilder().add("notes.txt", "hi").build(), fallback))
    }

    @Test
    fun `malformed XML in a detected FB2 container returns null instead of text`() {
        val rootOnly = """<?xml version="1.0"?><FictionBook xmlns="${Fb2Fixtures.NS}"><body>"""
        assertEquals(BookFormat.FB2, BookDetector.detect(rootOnly.toByteArray(Charsets.UTF_8)))
        assertNull(BookLoader.load(rootOnly.toByteArray(Charsets.UTF_8), fallback))
    }

    @Test
    fun `format MIME types are the contract values`() {
        assertEquals("application/epub+zip", BookFormat.EPUB.mimeType)
        assertEquals("application/x-fictionbook+xml", BookFormat.FB2.mimeType)
        assertEquals("text/plain", BookFormat.TXT.mimeType)
        assertEquals("application/vnd.comicbook+zip", BookFormat.CBZ.mimeType)
        assertEquals(4, BookFormat.entries.size)
    }

    @Test
    fun `EpubDocument satisfies BookContent`() {
        val document = requireNotNull(EpubParser.parse(Fixtures.epub3()))
        val content: BookContent = document
        assertEquals(Fixtures.TITLE, content.title)
        assertNotNull(content.cover)
        assertNotNull(content.imageBytes(Fixtures.PIC))
        content.close()
        assertNull(content.cover)
        assertNull(content.imageBytes(Fixtures.PIC))
        assertTrue(document is BookContent)
    }

    @Test
    fun `loaded content lifecycle is uniform across reflowable formats`() {
        val sources = listOf(
            Fixtures.epub3() to BookFormat.EPUB,
            Fb2Fixtures.simple() to BookFormat.FB2,
            BookFormatFixtures.utf8("Just text.") to BookFormat.TXT,
        )
        for ((bytes, format) in sources) {
            val content = assertReflowable(BookLoader.load(bytes, fallback), format)
            assertTrue(content.toc.isNotEmpty())
            assertTrue(content.chapters.isNotEmpty())
            content.close()
            content.close()
            assertTrue("parsed state survives close for $format", content.chapters.isNotEmpty())
        }
    }

    @Test
    fun `a thousand random blobs never throw and only TXT can be detected`() {
        val random = Random(0xC0FFEE)
        for (index in 0 until 1_000) {
            val length = random.nextInt(4_096)
            val bytes = ByteArray(length)
            random.nextBytes(bytes)
            val format = BookDetector.detect(bytes)
            if (format != null) assertEquals("random blob $index", BookFormat.TXT, format)
            BookLoader.load(bytes, fallback)?.closeLoaded()
        }
    }

    @Test
    fun `mutations of every format fixture never throw`() {
        val random = Random(7)
        val fixtures = listOf(
            Fixtures.epub3(),
            Fb2Fixtures.simple(),
            BookFormatFixtures.utf8("Chapter One\n\nBody text.\n\nChapter Two\n\nMore text."),
            BookFormatFixtures.cbz(
                "page1.jpg" to BookFormatFixtures.JPEG_BYTES,
                "page2.png" to BookFormatFixtures.PNG_BYTES,
            ),
        )
        for (fixture in fixtures) {
            for (iteration in 0 until 100) {
                val mutated = when (iteration % 3) {
                    0 -> {
                        val copy = fixture.copyOf()
                        if (copy.isNotEmpty()) copy[random.nextInt(copy.size)] = random.nextInt(256).toByte()
                        copy
                    }
                    1 -> fixture.copyOfRange(0, if (fixture.isEmpty()) 0 else random.nextInt(fixture.size))
                    else -> fixture.copyOf() + ByteArray(8) { random.nextInt(256).toByte() }
                }
                BookLoader.load(mutated, fallback)?.closeLoaded()
            }
        }
    }

    @Test
    fun `direct parser calls never throw on random blobs`() {
        val random = Random(99)
        for (index in 0 until 1_000) {
            val bytes = ByteArray(random.nextInt(2_048))
            random.nextBytes(bytes)
            TxtBookParser.parse(bytes, fallback)?.close()
            Fb2BookParser.parse(bytes, fallback)?.close()
            CbzParser.parse(bytes, fallback)?.close()
            EpubParser.parse(bytes)?.close()
        }
    }
}
