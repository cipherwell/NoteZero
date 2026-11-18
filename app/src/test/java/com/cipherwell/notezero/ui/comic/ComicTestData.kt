package com.cipherwell.notezero.ui.comic

import com.cipherwell.notezero.book.BookFormat
import com.cipherwell.notezero.book.CbzParser
import com.cipherwell.notezero.book.ComicBook
import com.cipherwell.notezero.data.AttachmentImport
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.ReadingState
import com.cipherwell.notezero.ui.FakeNoteRepository
import com.cipherwell.notezero.ui.reader.EpubTestData
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Generated-in-memory CBZ fixtures for the comic reader tests.
 *
 * The pages are hand-built valid RGB PNGs (signature + IHDR/IDAT/IEND chunks with a real zlib
 * stream and CRCs) so `BitmapFactory.decodeByteArray` has something decodable in Robolectric
 * and on a device; the archive is assembled with [ZipOutputStream]. Nothing is committed as a
 * binary fixture and nothing is written to disk.
 */
object ComicTestData {

    const val COMIC_TITLE = "Test Comic"
    const val PAGE_COUNT = 3

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** A valid [pageCount]-page comic; page names sort naturally (`page2` before `page10`). */
    fun validCbz(pageCount: Int = PAGE_COUNT): ByteArray = cbz(
        (1..pageCount).map { index -> "page$index.png" to png() },
    )

    /** A ZIP archive from raw entry names/bytes (used for hostile-page fixtures too). */
    fun cbz(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    /** Bytes that start like a ZIP but have no usable central directory: every detector rejects. */
    fun corruptCbz(): ByteArray = byteArrayOf(
        0x50, 0x4B, 0x03, 0x04, // local file header signature
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    /**
     * A tiny valid PNG: 4x6 truecolour, one scanline filter byte per row, zlib-compressed IDAT.
     * Built by hand so the test has no binary fixture and no Android/graphics dependency.
     */
    fun png(width: Int = 4, height: Int = 6, rgb: Int = 0x3366CC): ByteArray {
        val raw = ByteArrayOutputStream()
        for (row in 0 until height) {
            raw.write(0) // filter type: none
            for (column in 0 until width) {
                raw.write((rgb shr 16) and 0xFF)
                raw.write((rgb shr 8) and 0xFF)
                raw.write(rgb and 0xFF)
            }
        }
        val compressed = ByteArrayOutputStream().also { buffer ->
            DeflaterOutputStream(buffer).use { it.write(raw.toByteArray()) }
        }.toByteArray()
        val header = byteArrayOf(
            (width ushr 24).toByte(), (width ushr 16).toByte(),
            (width ushr 8).toByte(), width.toByte(),
            (height ushr 24).toByte(), (height ushr 16).toByte(),
            (height ushr 8).toByte(), height.toByte(),
            8, // bit depth
            2, // colour type: truecolour
            0, 0, 0, // compression, filter method, interlace
        )
        val png = ByteArrayOutputStream()
        png.write(PNG_SIGNATURE)
        png.write(chunk("IHDR", header))
        png.write(chunk("IDAT", compressed))
        png.write(chunk("IEND", ByteArray(0)))
        return png.toByteArray()
    }

    /** Parses [bytes] with the real [CbzParser] (the same path the app uses on open). */
    fun parseComic(bytes: ByteArray, title: String = COMIC_TITLE): ComicBook =
        requireNotNull(CbzParser.parse(bytes, title)) { "test CBZ fixture failed to parse" }

    /**
     * A one-page comic whose page bytes are unavailable (engine returns null for a missing /
     * over-cap / closed page): drives the reader's non-secret image-error state.
     */
    fun missingPageComic(): ComicBook = object : ComicBook {
        override val title: String = "Missing Page Comic"
        override val pageCount: Int = 1
        override fun pageBytes(index: Int): ByteArray? = null
        override fun close() = Unit
    }

    /** A defensive fake used only to drive the empty-page error state. */
    fun emptyComic(): ComicBook = object : ComicBook {
        override val title: String = "Empty Comic"
        override val pageCount: Int = 0
        override fun pageBytes(index: Int): ByteArray? = null
        override fun close() = Unit
    }

    /**
     * Seeds a [FakeNoteRepository] with a BOOK_FILE item holding [bytes] exactly like the
     * importer does: encrypted attachment path plus optional encrypted reading state.
     */
    fun seedComic(
        repository: FakeNoteRepository,
        itemTitle: String = COMIC_TITLE,
        bytes: ByteArray = validCbz(),
        readingState: ReadingState? = null,
    ): String = runBlocking {
        val created = repository.createItem(ItemKind.BOOK_FILE)
        val renamed = repository.saveItem(created.copy(title = itemTitle))
        val imported = repository.importAttachment(
            noteId = renamed.id,
            displayName = "comic.cbz",
            mimeType = BookFormat.CBZ.mimeType,
            source = ByteArrayInputStream(bytes),
        )
        check(imported is AttachmentImport.Success) { "test fixture import failed" }
        if (readingState != null) {
            repository.saveReadingState(renamed.id, readingState)
        }
        renamed.id
    }

    /** An unlocked fake vault, like the other UI tests use. */
    fun unlockedRepository(): FakeNoteRepository = EpubTestData.unlockedRepository()

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }.value.toInt()
        val output = ByteArrayOutputStream()
        output.write(intToBytes(data.size))
        output.write(typeBytes)
        output.write(data)
        output.write(intToBytes(crc))
        return output.toByteArray()
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
