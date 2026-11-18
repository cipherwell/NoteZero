package com.cipherwell.notezero.book

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** CBZ page ordering, lazy page reads, caps and corruption behaviour. */
class CbzParserTest {

    private val fallback = BookFormatFixtures.FALLBACK_TITLE

    private fun parse(bytes: ByteArray, title: String = fallback): ComicBook =
        requireNotNull(CbzParser.parse(bytes, title)) { "expected a parsed comic" }

    @Test
    fun `pages are natural-sorted with numeric names in order`() {
        val pages = listOf(
            "page10.jpg" to byteArrayOf(10),
            "page2.jpg" to byteArrayOf(2),
            "page1.jpg" to byteArrayOf(1),
            "Page3.PNG" to byteArrayOf(3),
        )
        val comic = parse(BookFormatFixtures.cbz(*pages.toTypedArray()))
        assertEquals(4, comic.pageCount)
        assertArrayEquals(byteArrayOf(1), comic.pageBytes(0))
        assertArrayEquals(byteArrayOf(2), comic.pageBytes(1))
        assertArrayEquals(byteArrayOf(3), comic.pageBytes(2))
        assertArrayEquals(byteArrayOf(10), comic.pageBytes(3))
    }

    @Test
    fun `directories and non-page entries are ignored`() {
        val bytes = BookFormatFixtures.cbz(
            "ComicInfo.xml" to BookFormatFixtures.utf8("<ComicInfo/>"),
            "chapter1/001.jpg" to BookFormatFixtures.JPEG_BYTES,
            "notes.txt" to BookFormatFixtures.utf8("hello"),
        )
        val comic = parse(bytes)
        assertEquals(1, comic.pageCount)
        assertArrayEquals(BookFormatFixtures.JPEG_BYTES, comic.pageBytes(0))
    }

    @Test
    fun `all supported image extensions are pages`() {
        val bytes = BookFormatFixtures.cbz(
            "a.jpg" to BookFormatFixtures.JPEG_BYTES,
            "b.jpeg" to BookFormatFixtures.JPEG_BYTES,
            "c.png" to BookFormatFixtures.PNG_BYTES,
            "d.webp" to BookFormatFixtures.WEBP_BYTES,
            "e.gif" to BookFormatFixtures.GIF_BYTES,
        )
        val comic = parse(bytes)
        assertEquals(5, comic.pageCount)
        assertArrayEquals(BookFormatFixtures.GIF_BYTES, comic.pageBytes(4))
    }

    @Test
    fun `title comes from the app fallback and blank becomes Untitled`() {
        val comic = parse(BookFormatFixtures.cbz("p1.png" to BookFormatFixtures.PNG_BYTES), "My Comic")
        assertEquals("My Comic", comic.title)
        val untitled = parse(BookFormatFixtures.cbz("p1.png" to BookFormatFixtures.PNG_BYTES), "  ")
        assertEquals("Untitled", untitled.title)
    }

    @Test
    fun `pageBytes returns independent copies`() {
        val comic = parse(BookFormatFixtures.cbz("p1.jpg" to BookFormatFixtures.JPEG_BYTES))
        val first = comic.pageBytes(0)
        assertNotNull(first)
        first!![0] = 0x00
        assertArrayEquals(BookFormatFixtures.JPEG_BYTES, comic.pageBytes(0))
    }

    @Test
    fun `out-of-range pages return null`() {
        val comic = parse(BookFormatFixtures.cbz("p1.jpg" to BookFormatFixtures.JPEG_BYTES))
        assertNull(comic.pageBytes(-1))
        assertNull(comic.pageBytes(1))
        assertNull(comic.pageBytes(Int.MAX_VALUE))
    }

    @Test
    fun `close is idempotent and drops page bytes but keeps metadata`() {
        val comic = parse(BookFormatFixtures.cbz("p1.jpg" to BookFormatFixtures.JPEG_BYTES))
        assertNotNull(comic.pageBytes(0))
        comic.close()
        comic.close()
        assertNull(comic.pageBytes(0))
        assertEquals(1, comic.pageCount)
        assertEquals(fallback, comic.title)
    }

    @Test
    fun `more than 500 pages are capped`() {
        val comic = parse(BookFormatFixtures.cbzPages(BookLimits.MAX_COMIC_PAGES + 1))
        assertEquals(BookLimits.MAX_COMIC_PAGES, comic.pageCount)
        assertNotNull(comic.pageBytes(0))
        assertNotNull(comic.pageBytes(BookLimits.MAX_COMIC_PAGES - 1))
        assertNull(comic.pageBytes(BookLimits.MAX_COMIC_PAGES))
    }

    @Test
    fun `a page over the 16 MiB entry cap is excluded`() {
        val entries = listOf(
            RawZip.Entry(
                name = "page1.jpg",
                data = ByteArray(0),
                method = RawZip.METHOD_STORED,
                compressedSize = 0,
                uncompressedSize = BookLimits.MAX_ENTRY_BYTES.toLong() + 1,
            ),
            RawZip.Entry("page2.jpg", byteArrayOf(2)),
        )
        val bytes = RawZip.build(entries)
        val comic = parse(bytes)
        assertEquals(1, comic.pageCount)
        assertArrayEquals(byteArrayOf(2), comic.pageBytes(0))
    }

    @Test
    fun `an archive declaring over 128 MiB is rejected`() {
        val entries = listOf(
            RawZip.Entry(
                name = "page1.jpg",
                data = ByteArray(0),
                method = RawZip.METHOD_STORED,
                compressedSize = 0,
                uncompressedSize = 70L * 1024L * 1024L,
            ),
            RawZip.Entry(
                name = "page2.jpg",
                data = ByteArray(0),
                method = RawZip.METHOD_STORED,
                compressedSize = 0,
                uncompressedSize = 70L * 1024L * 1024L,
            ),
        )
        assertNull(CbzParser.parse(RawZip.build(entries), fallback))
    }

    @Test
    fun `a corrupt compressed page returns null for that page only`() {
        val entries = listOf(
            RawZip.Entry(
                name = "page1.jpg",
                data = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05),
                method = RawZip.METHOD_DEFLATED,
                compressedSize = 5,
                uncompressedSize = 11,
            ),
            RawZip.Entry("page2.jpg", BookFormatFixtures.JPEG_BYTES),
        )
        val comic = parse(RawZip.build(entries))
        assertEquals(2, comic.pageCount)
        assertNull("corrupt deflate stream must not throw", comic.pageBytes(0))
        assertArrayEquals(BookFormatFixtures.JPEG_BYTES, comic.pageBytes(1))
    }

    @Test
    fun `an archive without pages is not a comic`() {
        assertNull(CbzParser.parse(ZipBuilder().add("notes.txt", "hello").build(), fallback))
        assertNull(CbzParser.parse(ZipBuilder().build(), fallback))
    }

    @Test
    fun `an EPUB archive is not parsed as a comic`() {
        assertNull(CbzParser.parse(Fixtures.epub3(), fallback))
    }

    @Test
    fun `random and truncated input returns null`() {
        assertNull(CbzParser.parse(ByteArray(0), fallback))
        assertNull(CbzParser.parse(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00), fallback))

        val cbz = BookFormatFixtures.cbz("page1.jpg" to BookFormatFixtures.JPEG_BYTES)
        assertNull(CbzParser.parse(cbz.copyOfRange(0, cbz.size / 2), fallback))
    }

    @Test
    fun `duplicate entry names keep the first declaration`() {
        val entries = listOf(
            RawZip.Entry("page1.jpg", byteArrayOf(1)),
            RawZip.Entry("page1.jpg", byteArrayOf(9)),
        )
        val comic = parse(RawZip.build(entries))
        assertEquals(1, comic.pageCount)
        assertArrayEquals(byteArrayOf(1), comic.pageBytes(0))
    }
}
