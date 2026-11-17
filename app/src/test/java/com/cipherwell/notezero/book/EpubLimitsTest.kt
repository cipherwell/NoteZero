package com.cipherwell.notezero.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bounds and ZIP-safety behaviour: entry/total-size caps, entry-count cap, duplicate names,
 * missing spine targets, missing images, truncation limits, unknown compression and
 * zip-level encryption.
 */
class EpubLimitsTest {

    private val mib = 1024L * 1024L

    private fun rawEpub(entries: List<RawZip.Entry>): ByteArray = RawZip.build(
        listOf(RawZip.Entry("mimetype", Fixtures.MIMETYPE.toByteArray(Charsets.UTF_8))) + entries,
    )

    private fun rawBook(
        opf: String = Fixtures.opf(
            manifestInner = Fixtures.item("ch1", "text/ch1.xhtml", "application/xhtml+xml"),
            spineInner = Fixtures.itemref("ch1"),
        ),
        entries: List<RawZip.Entry>,
    ): ByteArray = rawEpub(
        listOf(
            RawZip.Entry(Fixtures.CONTAINER_PATH, Fixtures.container().toByteArray(Charsets.UTF_8)),
            RawZip.Entry(Fixtures.OPF_PATH, opf.toByteArray(Charsets.UTF_8)),
        ) + entries,
    )

    @Test
    fun `path traversal and absolute entry names are ignored`() {
        val bytes = Fixtures.epub3Builder()
            .add("../evil.xhtml", Fixtures.xhtml("<p>evil</p>"))
            .add("/absolute.xhtml", Fixtures.xhtml("<p>absolute</p>"))
            .add("OEBPS\\windows.xhtml", Fixtures.xhtml("<p>backslash</p>"))
            .add("OEBPS/../escape.xhtml", Fixtures.xhtml("<p>escape</p>"))
            .build()
        val document = requireNotNull(EpubParser.parse(bytes))
        assertEquals(3, document.chapters.size)
        assertEquals(Fixtures.CH1, document.chapters[0].href)
        // Unsafe names cannot be addressed through the lazy image API either.
        assertNull(document.imageBytes("../evil.xhtml"))
        assertNull(document.imageBytes("/absolute.xhtml"))
        assertNull(document.imageBytes("OEBPS\\windows.xhtml"))
    }

    @Test
    fun `duplicate entry names keep the first occurrence`() {
        val duplicate = Fixtures.xhtml("<h1>Duplicate</h1><p>Second copy of the chapter.</p>")
        val entries = Fixtures.epub3RawEntries() +
            RawZip.Entry(Fixtures.CH1, duplicate.toByteArray(Charsets.UTF_8))
        val document = requireNotNull(EpubParser.parse(RawZip.build(entries)))
        val text = document.chapters[0].plainText()
        assertTrue("first entry wins", text.contains("Intro with bold"))
        assertTrue("duplicate content is not used", !text.contains("Second copy of the chapter."))
    }

    @Test
    fun `more than ten thousand entries rejects the archive`() {
        val entries = ArrayList<RawZip.Entry>(10_001)
        repeat(10_001) { index -> entries.add(RawZip.Entry("filler/$index", ByteArray(0))) }
        assertNull(EpubParser.parse(RawZip.build(entries)))
    }

    @Test
    fun `declared uncompressed total over 128 MiB rejects the archive`() {
        val entries = ArrayList<RawZip.Entry>()
        repeat(9) { index ->
            entries.add(
                RawZip.Entry(
                    name = "OEBPS/data$index.bin",
                    data = ByteArray(8),
                    method = RawZip.METHOD_STORED,
                    compressedSize = 8,
                    uncompressedSize = 15L * mib,
                ),
            )
        }
        assertNull(EpubParser.parse(RawZip.build(entries)))
    }

    @Test
    fun `single entry over 16 MiB is skipped and the rest of the book parses`() {
        val bytes = rawBook(
            opf = Fixtures.opf(
                manifestInner = listOf(
                    Fixtures.item("ch1", "text/ch1.xhtml", "application/xhtml+xml"),
                    Fixtures.item("ch2", "text/ch2.xhtml", "application/xhtml+xml"),
                ).joinToString("\n    "),
                spineInner = listOf(Fixtures.itemref("ch1"), Fixtures.itemref("ch2")).joinToString("\n    "),
            ),
            entries = listOf(
                RawZip.Entry(
                    name = Fixtures.CH1,
                    data = ByteArray(0),
                    method = RawZip.METHOD_STORED,
                    compressedSize = 0,
                    uncompressedSize = 17L * mib,
                ),
                RawZip.Entry(Fixtures.CH2, Fixtures.chapterTwo().toByteArray(Charsets.UTF_8)),
            ),
        )
        val document = requireNotNull(EpubParser.parse(bytes))
        assertEquals(listOf(Fixtures.CH2), document.chapters.map { it.href })
    }

    @Test
    fun `image over the entry cap keeps the block but imageBytes is null`() {
        val chapter = Fixtures.xhtml("""<p><img src="../images/pic.png" alt="Huge"/></p>""")
        val bytes = rawBook(
            opf = Fixtures.opf(
                manifestInner = listOf(
                    Fixtures.item("ch1", "text/ch1.xhtml", "application/xhtml+xml"),
                    Fixtures.item("pic", "images/pic.png", "image/png"),
                ).joinToString("\n    "),
                spineInner = Fixtures.itemref("ch1"),
            ),
            entries = listOf(
                RawZip.Entry(Fixtures.CH1, chapter.toByteArray(Charsets.UTF_8)),
                RawZip.Entry(
                    name = Fixtures.PIC,
                    data = ByteArray(8),
                    method = RawZip.METHOD_STORED,
                    compressedSize = 8,
                    uncompressedSize = 17L * mib,
                ),
            ),
        )
        val document = requireNotNull(EpubParser.parse(bytes))
        val image = document.chapters.single().blocks.filterIsInstance<BookBlock.Image>().single()
        assertEquals(Fixtures.PIC, image.entry)
        assertNull(document.imageBytes(Fixtures.PIC))
    }

    @Test
    fun `cover over 4 MiB is dropped while the book parses`() {
        val bytes = rawBook(
            opf = Fixtures.opf(
                manifestInner = listOf(
                    Fixtures.item("ch1", "text/ch1.xhtml", "application/xhtml+xml"),
                    Fixtures.item("cover-image", "images/cover.jpg", "image/jpeg", "cover-image"),
                ).joinToString("\n    "),
                spineInner = Fixtures.itemref("ch1"),
            ),
            entries = listOf(
                RawZip.Entry(Fixtures.CH1, Fixtures.chapterTwo().toByteArray(Charsets.UTF_8)),
                RawZip.Entry(
                    name = Fixtures.COVER,
                    data = ByteArray(8),
                    method = RawZip.METHOD_STORED,
                    compressedSize = 8,
                    uncompressedSize = 5L * mib,
                ),
            ),
        )
        val document = requireNotNull(EpubParser.parse(bytes))
        assertEquals(1, document.chapters.size)
        assertNull(document.cover)
    }

    @Test
    fun `missing chapter file is skipped and remaining chapters reindex`() {
        val bytes = Fixtures.epub3Builder().remove(Fixtures.CH2).build()
        val document = requireNotNull(EpubParser.parse(bytes))
        assertEquals(listOf(Fixtures.CH1, Fixtures.CH3), document.chapters.map { it.href })
        assertEquals(listOf(0, 1), document.chapters.map { it.index })
        assertEquals(
            listOf(BookTocEntry("Chapter One", 0, 0), BookTocEntry("Chapter Three", 1, 1)),
            document.toc,
        )
    }

    @Test
    fun `spine itemref to a missing manifest id is skipped`() {
        val spine = listOf(
            Fixtures.itemref("ch1"),
            Fixtures.itemref("ghost"),
            Fixtures.itemref("ch2"),
            Fixtures.itemref("ch3"),
        ).joinToString("\n    ")
        val bytes = Fixtures.epub3Builder()
            .replace(Fixtures.OPF_PATH, Fixtures.opf(manifestInner = Fixtures.epub3Manifest(), spineInner = spine))
            .build()
        val document = requireNotNull(EpubParser.parse(bytes))
        assertEquals(listOf(Fixtures.CH1, Fixtures.CH2, Fixtures.CH3), document.chapters.map { it.href })
        assertEquals(listOf(0, 1, 2), document.chapters.map { it.index })
    }

    @Test
    fun `missing image is tolerated with the block present and null bytes`() {
        val bytes = Fixtures.epub3Builder().remove(Fixtures.PIC).build()
        val document = requireNotNull(EpubParser.parse(bytes))
        val image = document.chapters[0].blocks.filterIsInstance<BookBlock.Image>().single()
        assertEquals(Fixtures.PIC, image.entry)
        assertEquals("A picture", image.alt)
        assertNull(document.imageBytes(Fixtures.PIC))
    }

    @Test
    fun `chapter block count is capped at twenty thousand`() {
        val paragraphCount = 20_010
        val chapter = Fixtures.xhtml("<p>x</p>".repeat(paragraphCount))
        val bytes = Fixtures.book(
            opf = Fixtures.opf(
                manifestInner = Fixtures.item("ch1", "text/ch1.xhtml", "application/xhtml+xml"),
                spineInner = Fixtures.itemref("ch1"),
            ),
            entries = mapOf(Fixtures.CH1 to chapter),
        )
        val document = requireNotNull(EpubParser.parse(bytes))
        assertEquals(BookLimits.MAX_BLOCKS_PER_CHAPTER, document.chapters.single().blocks.size)
    }

    @Test
    fun `chapter text is capped at two million characters`() {
        val chapter = Fixtures.xhtml("<p>${"a".repeat(2_100_000)}</p>")
        val bytes = Fixtures.book(
            opf = Fixtures.opf(
                manifestInner = Fixtures.item("ch1", "text/ch1.xhtml", "application/xhtml+xml"),
                spineInner = Fixtures.itemref("ch1"),
            ),
            entries = mapOf(Fixtures.CH1 to chapter),
        )
        val document = requireNotNull(EpubParser.parse(bytes))
        assertEquals(BookLimits.MAX_TEXT_PER_CHAPTER, document.chapters.single().plainText().length)
    }

    @Test
    fun `entries with unknown compression are skipped`() {
        val bytes = rawBook(
            opf = Fixtures.opf(
                manifestInner = listOf(
                    Fixtures.item("ch1", "text/ch1.xhtml", "application/xhtml+xml"),
                    Fixtures.item("ch2", "text/ch2.xhtml", "application/xhtml+xml"),
                ).joinToString("\n    "),
                spineInner = listOf(Fixtures.itemref("ch1"), Fixtures.itemref("ch2")).joinToString("\n    "),
            ),
            entries = listOf(
                RawZip.Entry(
                    name = Fixtures.CH1,
                    data = Fixtures.chapterOne().toByteArray(Charsets.UTF_8),
                    method = 99,
                ),
                RawZip.Entry(Fixtures.CH2, Fixtures.chapterTwo().toByteArray(Charsets.UTF_8)),
            ),
        )
        val document = requireNotNull(EpubParser.parse(bytes))
        assertEquals(listOf(Fixtures.CH2), document.chapters.map { it.href })
    }

    @Test
    fun `zip-level encrypted entries are skipped`() {
        val bytes = rawBook(
            entries = listOf(
                RawZip.Entry(
                    name = Fixtures.CH1,
                    data = Fixtures.chapterTwo().toByteArray(Charsets.UTF_8),
                    flags = 1,
                ),
            ),
        )
        val document = requireNotNull(EpubParser.parse(bytes))
        assertTrue(document.chapters.isEmpty())
    }

    @Test
    fun `imageBytes requires the exact normalised block entry`() {
        val document = requireNotNull(EpubParser.parse(Fixtures.epub3()))
        assertNull(document.imageBytes("OEBPS/images/../images/pic.png"))
        assertNotNull(document.imageBytes(Fixtures.PIC))
    }
}
