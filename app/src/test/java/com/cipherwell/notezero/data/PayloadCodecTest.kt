package com.cipherwell.notezero.data

import com.cipherwell.notezero.model.AttachmentRef
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.PdfAnnotations
import com.cipherwell.notezero.model.PdfHighlightRect
import com.cipherwell.notezero.model.PdfInkKind
import com.cipherwell.notezero.model.PdfInkStroke
import com.cipherwell.notezero.model.PdfNote
import com.cipherwell.notezero.model.PdfPoint
import com.cipherwell.notezero.model.SketchDocument
import com.cipherwell.notezero.model.SketchInk
import com.cipherwell.notezero.model.SketchPoint
import com.cipherwell.notezero.model.SketchShape
import com.cipherwell.notezero.model.SketchShapeKind
import com.cipherwell.notezero.model.SketchStroke
import com.cipherwell.notezero.model.SketchTemplate
import com.cipherwell.notezero.model.SketchWidth
import com.cipherwell.notezero.model.ReadingBookmark
import com.cipherwell.notezero.model.ReadingState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Pure-JVM tests for the binary envelopes that live inside the AES-GCM ciphertext
 * (ItemCodec/FolderCodec). No Robolectric needed: the codecs are Android-free.
 */
class PayloadCodecTest {

    private fun note(
        id: String = "0f8fad5b-d9cb-469f-a165-70867728950e",
        kind: ItemKind = ItemKind.NOTE,
        title: String = "Title",
        body: String = "Body",
        tags: List<String> = listOf("one", "two"),
        folderId: String? = "folder-id",
        pinned: Boolean = true,
        favorite: Boolean = false,
        trashed: Boolean = true,
        createdAt: Long = 1_700_000_000_000L,
        updatedAt: Long = 1_700_000_123_000L,
        version: Long = 7L,
        attachments: List<AttachmentRef> = emptyList(),
        readingState: ReadingState? = null,
        pdfAnnotations: PdfAnnotations? = null,
        sketch: SketchDocument? = null,
    ) = Item(
        id = id,
        kind = kind,
        title = title,
        body = body,
        tags = tags,
        folderId = folderId,
        pinned = pinned,
        favorite = favorite,
        trashed = trashed,
        createdAt = createdAt,
        updatedAt = updatedAt,
        version = version,
        attachments = attachments,
        readingState = readingState,
        pdfAnnotations = pdfAnnotations,
        sketch = sketch,
    )

    private fun attachment(
        id: String = "11111111-1111-4111-8111-111111111111",
        displayName: String = "photo.jpg",
        mimeType: String = "image/jpeg",
        sizeBytes: Long = 1234L,
        createdAt: Long = 1_700_000_100_000L,
        sha256: String = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    ) = AttachmentRef(
        id = id,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        createdAt = createdAt,
        sha256 = sha256,
    )

    // ---- ItemCodec -----------------------------------------------------------

    @Test
    fun `item codec round trips every field exactly`() {
        val original = note(
            kind = ItemKind.CODE_FILE,
            title = "café 🔥",
            body = "line 1\nline 2\nÿ",
            tags = listOf("tag one", "tag-two", "ünïcode"),
            pinned = true,
            favorite = true,
            trashed = false,
        )
        val decoded = ItemCodec.decode(ItemCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `pdf items round trip with their attachment`() {
        // PDF_FILE is kind ordinal 2 (appended after NOTE/CODE_FILE, so old payloads decode).
        val pdf = attachment(
            displayName = "document-20261115-221600.pdf",
            mimeType = "application/pdf",
            sizeBytes = 420L,
        )
        val original = note(
            kind = ItemKind.PDF_FILE,
            title = "document-20261115-221600.pdf",
            body = "",
            tags = emptyList(),
            attachments = listOf(pdf),
        )

        val decoded = ItemCodec.decode(ItemCodec.encode(original))

        assertEquals(original, decoded)
        assertEquals(ItemKind.PDF_FILE, decoded?.kind)
        assertEquals("application/pdf", decoded?.attachments?.single()?.mimeType)
    }

    @Test
    fun `item codec round trips encrypted reading state`() {
        val state = ReadingState(
            position = 7,
            fraction = 0.42f,
            total = 24,
            bookmarks = listOf(
                ReadingBookmark(position = 2, label = "Chapter 2 start", createdAt = 1_700_000_500_000L),
                ReadingBookmark(position = 5, label = "Quote", createdAt = 1_700_000_600_000L),
            ),
            author = "Ada Lovelace",
            language = "en",
        )
        val original = note(kind = ItemKind.BOOK_FILE, readingState = state)

        val decoded = ItemCodec.decode(ItemCodec.encode(original))

        assertEquals(original, decoded)
        assertEquals(state, decoded?.readingState)
        assertEquals(ItemKind.BOOK_FILE, decoded?.kind)
    }

    @Test
    fun `version 3 payloads decode with no reading state`() {
        val original = note(kind = ItemKind.PDF_FILE, readingState = null)
        val encodedV6 = ItemCodec.encode(original)
        // Envelope layout: 4-byte magic, then the version byte. v6 with null state, null
        // annotations and null sketch appends three presence bytes; strip all for a v3 payload.
        val legacy = encodedV6.copyOf(encodedV6.size - 3)
        legacy[4] = 3

        val decoded = ItemCodec.decode(legacy)

        assertEquals(original, decoded)
        assertNull(decoded?.readingState)
    }

    @Test
    fun `item codec round trips the encrypted annotation layer`() {
        val annotations = PdfAnnotations(
            strokes = listOf(
                PdfInkStroke(
                    page = 1,
                    kind = PdfInkKind.HIGHLIGHTER,
                    colorIndex = 4,
                    widthIndex = 2,
                    points = listOf(PdfPoint(0.2f, 0.3f), PdfPoint(0.4f, 0.5f)),
                ),
            ),
            highlights = listOf(
                PdfHighlightRect(page = 0, left = 0.1f, top = 0.1f, right = 0.9f, bottom = 0.2f, colorIndex = 0),
            ),
            notes = listOf(PdfNote(page = 2, x = 0.5f, y = 0.5f, text = "remember this")),
        )
        val original = note(kind = ItemKind.PDF_FILE, pdfAnnotations = annotations)

        val decoded = ItemCodec.decode(ItemCodec.encode(original))

        assertEquals(original, decoded)
        assertEquals(annotations, decoded?.pdfAnnotations)
    }

    @Test
    fun `empty annotation layers are not written`() {
        val original = note(kind = ItemKind.PDF_FILE, pdfAnnotations = PdfAnnotations())
        val decoded = ItemCodec.decode(ItemCodec.encode(original))
        assertEquals(original.copy(pdfAnnotations = null), decoded)
        assertNull(decoded?.pdfAnnotations)
    }

    @Test
    fun `version 4 payloads decode without annotations`() {
        val state = ReadingState(position = 1, total = 4)
        val encodedV6 = ItemCodec.encode(note(kind = ItemKind.BOOK_FILE, readingState = state))
        // v6 wraps the annotation + sketch presence bytes at the end; dropping both yields a
        // valid v4 payload that must still decode its reading state.
        val v4 = encodedV6.copyOf(encodedV6.size - 2)
        v4[4] = 4
        val decoded = ItemCodec.decode(v4)
        assertEquals(state, decoded?.readingState)
        assertNull(decoded?.pdfAnnotations)
    }

    @Test
    fun `item codec round trips the encrypted sketch document`() {
        val sketch = SketchDocument(
            template = SketchTemplate.GRID,
            strokes = listOf(
                SketchStroke(
                    points = listOf(SketchPoint(0.1f, 0.2f), SketchPoint(0.3f, 0.4f)),
                    ink = SketchInk.GREEN,
                    width = SketchWidth.BOLD,
                ),
            ),
            shapes = listOf(
                SketchShape(
                    kind = SketchShapeKind.ARROW,
                    start = SketchPoint(0.5f, 0.5f),
                    end = SketchPoint(0.9f, 0.9f),
                    ink = SketchInk.RED,
                    width = SketchWidth.THIN,
                ),
            ),
        )
        val original = note(kind = ItemKind.SKETCH_FILE, sketch = sketch)

        val decoded = ItemCodec.decode(ItemCodec.encode(original))

        assertEquals(original, decoded)
        assertEquals(sketch, decoded?.sketch)
        assertEquals(ItemKind.SKETCH_FILE, decoded?.kind)
    }

    @Test
    fun `version 5 payloads decode without a sketch but keep annotations`() {
        val annotations = PdfAnnotations(
            notes = listOf(PdfNote(page = 0, x = 0.5f, y = 0.5f, text = "kept")),
        )
        val encodedV6 = ItemCodec.encode(
            note(kind = ItemKind.PDF_FILE, pdfAnnotations = annotations, sketch = SketchDocument()),
        )
        // Strip only the v6 sketch-presence byte.
        val v5 = encodedV6.copyOf(encodedV6.size - 1)
        v5[4] = 5
        val decoded = ItemCodec.decode(v5)
        assertEquals(annotations, decoded?.pdfAnnotations)
        assertNull(decoded?.sketch)
    }

    @Test
    fun `reading state bounds are enforced`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReadingState(position = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReadingState(fraction = 1.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReadingState(fraction = Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReadingState(bookmarks = List(ReadingState.MAX_BOOKMARKS + 1) {
                ReadingBookmark(position = 0, label = "x", createdAt = 0L)
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReadingState(bookmarks = listOf(ReadingBookmark(0, "x".repeat(ReadingState.MAX_LABEL_BYTES + 1), 0L)))
        }
        // Encoding an out-of-range position fails closed instead of writing it.
        assertThrows(IllegalArgumentException::class.java) {
            ItemCodec.encode(note(kind = ItemKind.BOOK_FILE, readingState = ReadingState(position = Int.MAX_VALUE)))
        }
    }

    @Test
    fun `item codec is deterministic`() {
        val item = note()
        assertArrayEquals(ItemCodec.encode(item), ItemCodec.encode(item))
    }

    @Test
    fun `item codec round trips empty and null variants`() {
        val minimal = note(
            kind = ItemKind.NOTE,
            title = "",
            body = "",
            tags = emptyList(),
            folderId = null,
            pinned = false,
            favorite = false,
            trashed = false,
            version = 0L,
        )
        assertEquals(minimal, ItemCodec.decode(ItemCodec.encode(minimal)))
    }

    @Test
    fun `item codec rejects empty garbage and truncated payloads`() {
        assertNull(ItemCodec.decode(ByteArray(0)))
        assertNull(ItemCodec.decode(ByteArray(1)))
        assertNull(ItemCodec.decode(ByteArray(64) { 0x7F.toByte() }))

        val valid = ItemCodec.encode(note())
        for (cut in listOf(1, 2, 4, 8, 16, 32, valid.size / 2, valid.size - 1)) {
            if (cut in 1 until valid.size) {
                assertNull("truncated at $cut must not decode", ItemCodec.decode(valid.copyOf(cut)))
            }
        }
    }

    @Test
    fun `item codec rejects trailing bytes and wrong version or magic`() {
        val valid = ItemCodec.encode(note())
        assertNull(ItemCodec.decode(valid + byteArrayOf(0)))

        val wrongVersion = valid.copyOf()
        wrongVersion[4] = (ItemCodec.ENVELOPE_VERSION + 1).toByte()
        assertNull(ItemCodec.decode(wrongVersion))

        val wrongMagic = valid.copyOf()
        wrongMagic[0] = 0x00
        assertNull(ItemCodec.decode(wrongMagic))
    }

    @Test
    fun `item codec rejects unknown kind and unknown flag bits`() {
        val valid = ItemCodec.encode(note())
        val unknownKind = valid.copyOf()
        unknownKind[5] = 9.toByte()
        assertNull(ItemCodec.decode(unknownKind))

        val unknownFlags = valid.copyOf()
        unknownFlags[6] = 0x80.toByte()
        assertNull(ItemCodec.decode(unknownFlags))
    }

    @Test
    fun `item codec bounds a huge length prefix instead of allocating`() {
        // magic, version, kind, flags
        val payload = byteArrayOf(0x4E, 0x5A, 0x49, 0x54, 1, 0, 0) +
            ByteArray(8 + 8 + 8) + // createdAt/updatedAt/version zeros
            byteArrayOf(0x00, 0x01) + // id length 1, then 'a'
            byteArrayOf('a'.code.toByte()) +
            byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) + // 2GB title length
            ByteArray(0)
        assertNull(ItemCodec.decode(payload))
    }

    @Test
    fun `item codec rejects fields over the documented limits on encode`() {
        val hugeTitle = note(title = "x".repeat(ItemCodec.MAX_TITLE_BYTES + 1))
        assertThrowsIllegalArgument { ItemCodec.encode(hugeTitle) }

        val tooManyTags = note(tags = List(ItemCodec.MAX_TAGS + 1) { "t$it" })
        assertThrowsIllegalArgument { ItemCodec.encode(tooManyTags) }

        val emptyId = note(id = "")
        assertThrowsIllegalArgument { ItemCodec.encode(emptyId) }
    }

    @Test
    fun `item codec decode never throws on random bytes`() {
        val random = Random(42)
        repeat(500) {
            val size = random.nextInt(0, 256)
            val bytes = ByteArray(size).also { random.nextBytes(it) }
            val decoded = ItemCodec.decode(bytes)
            // Random data must fail closed; if it ever decodes it must be a coherent item.
            if (decoded != null) {
                assertTrue(decoded.id.isNotEmpty())
            }
        }
    }

    @Test
    fun `item codec accepts very large body within the limit`() {
        val body = "a".repeat(256 * 1024)
        val decoded = ItemCodec.decode(ItemCodec.encode(note(body = body)))
        assertNotNull(decoded)
        assertEquals(body, decoded!!.body)
    }

    // ---- ItemCodec v2 attachments -------------------------------------------

    @Test
    fun `item codec v2 round trips attachment refs exactly`() {
        val original = note(
            title = "with attachments",
            attachments = listOf(
                attachment(),
                attachment(
                    id = "22222222-2222-4222-8222-222222222222",
                    displayName = "café 🔥.txt",
                    mimeType = "text/plain; charset=utf-8",
                    sizeBytes = AttachmentLimits.MAX_ATTACHMENT_BYTES,
                    sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                ),
            ),
        )
        val decoded = ItemCodec.decode(ItemCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals(2, decoded!!.attachments.size)
        assertEquals(AttachmentLimits.MAX_ATTACHMENT_BYTES, decoded.attachments[1].sizeBytes)
    }

    @Test
    fun `item codec writes the current version and still decodes legacy version 1 with no attachments`() {
        val encoded = ItemCodec.encode(note(title = "legacy", attachments = emptyList()))
        assertEquals(ItemCodec.ENVELOPE_VERSION, encoded[4].toInt())

        // Hand-build the version-1 payload layout: same fields, no attachment list.
        val legacy = BinaryWriter().apply {
            u32(0x4E5A4954L) // NZIT
            u8(1) // envelope version 1
            u8(0) // NOTE
            u8(0) // no flags
            u64(1_700_000_000_000L)
            u64(1_700_000_001_000L)
            u64(4L)
            lenPrefixedU16("legacy-id".toByteArray(Charsets.UTF_8), ItemCodec.MAX_ID_BYTES)
            u32("legacy title".toByteArray(Charsets.UTF_8).size.toLong())
            bytes("legacy title".toByteArray(Charsets.UTF_8))
            u32("legacy body".toByteArray(Charsets.UTF_8).size.toLong())
            bytes("legacy body".toByteArray(Charsets.UTF_8))
            u16(1)
            lenPrefixedU16("tag".toByteArray(Charsets.UTF_8), ItemCodec.MAX_TAG_BYTES)
        }.toByteArray()
        val decoded = ItemCodec.decode(legacy)
        assertNotNull(decoded)
        assertEquals("legacy-id", decoded!!.id)
        assertEquals("legacy title", decoded.title)
        assertEquals(listOf("tag"), decoded.tags)
        assertEquals(4L, decoded.version)
        assertTrue("v1 payloads decode to an empty attachment list", decoded.attachments.isEmpty())
    }

    @Test
    fun `item codec rejects malformed attachment metadata`() {
        val valid = ItemCodec.encode(note(attachments = listOf(attachment())))

        // Truncated inside the attachment list.
        for (cut in listOf(valid.size - 1, valid.size - 8, valid.size - 32, valid.size - 40)) {
            if (cut in 1 until valid.size) assertNull(ItemCodec.decode(valid.copyOf(cut)))
        }
        // Trailing bytes after a complete attachment list are rejected.
        assertNull(ItemCodec.decode(valid + byteArrayOf(0)))
        // Corrupt the attachment count: fixed header 7 + timestamps 24 + id(2+36) +
        // title(4+1) + body(4+1) + tagCount(2) = 81, no folder flag on this item.
        val counted = ItemCodec.encode(
            note(title = "t", body = "b", tags = emptyList(), folderId = null, attachments = listOf(attachment())),
        )
        assertEquals(0, counted[81].toInt()) // u16 attachment count, big-endian
        assertEquals(1, counted[82].toInt())
        val brokenCount = counted.copyOf()
        brokenCount[81] = 200.toByte()
        assertNull("over-limit attachment count must fail closed", ItemCodec.decode(brokenCount))
    }

    @Test
    fun `item codec bounds attachment fields on encode`() {
        val tooMany = note(attachments = List(AttachmentLimits.MAX_ATTACHMENTS_PER_NOTE + 1) { attachment(id = "id-$it") })
        assertThrowsIllegalArgument { ItemCodec.encode(tooMany) }

        assertThrowsIllegalArgument {
            ItemCodec.encode(note(attachments = listOf(attachment(sha256 = "not-a-sha"))))
        }
        assertThrowsIllegalArgument {
            ItemCodec.encode(note(attachments = listOf(attachment(sha256 = attachment().sha256.uppercase()))))
        }
        assertThrowsIllegalArgument {
            ItemCodec.encode(
                note(attachments = listOf(attachment(displayName = "x".repeat(ItemCodec.MAX_ATTACHMENT_NAME_BYTES + 1)))),
            )
        }
        assertThrowsIllegalArgument {
            ItemCodec.encode(note(attachments = listOf(attachment(sizeBytes = AttachmentLimits.MAX_ATTACHMENT_BYTES + 1))))
        }
        assertThrowsIllegalArgument {
            ItemCodec.encode(note(attachments = listOf(attachment(sizeBytes = -1L))))
        }
    }

    // ---- FolderCodec ---------------------------------------------------------

    private fun folder(
        id: String = "folder-1",
        name: String = "Projects",
        parentId: String? = null,
        sortOrder: Int = 3,
        createdAt: Long = 1_700_000_000_000L,
    ) = Folder(id = id, name = name, parentId = parentId, sortOrder = sortOrder, createdAt = createdAt)

    @Test
    fun `folder codec round trips and is deterministic`() {
        val cases = listOf(
            folder(),
            folder(name = "Nested ünïcode 🔥", parentId = "parent-1", sortOrder = -5),
            folder(name = "", sortOrder = Int.MAX_VALUE),
        )
        for (original in cases) {
            val encoded = FolderCodec.encode(original)
            assertEquals(original, FolderCodec.decode(encoded))
            assertArrayEquals(encoded, FolderCodec.encode(original))
        }
    }

    @Test
    fun `folder codec rejects corruption`() {
        assertNull(FolderCodec.decode(ByteArray(0)))
        assertNull(FolderCodec.decode(ByteArray(64) { 1.toByte() }))
        val valid = FolderCodec.encode(folder())
        for (cut in listOf(1, 3, 5, valid.size / 2, valid.size - 1)) {
            if (cut in 1 until valid.size) assertNull(FolderCodec.decode(valid.copyOf(cut)))
        }
        assertNull(FolderCodec.decode(valid + byteArrayOf(0)))

        val badVersion = valid.copyOf()
        badVersion[4] = 9.toByte()
        assertNull(FolderCodec.decode(badVersion))

        val badFlags = valid.copyOf()
        badFlags[5] = 0x02.toByte()
        assertNull(FolderCodec.decode(badFlags))
    }

    @Test
    fun `folder name over the limit is rejected on encode`() {
        assertThrowsIllegalArgument {
            FolderCodec.encode(folder(name = "x".repeat(FolderCodec.MAX_NAME_BYTES + 1)))
        }
    }

    // ---- BinaryReader/Writer -------------------------------------------------

    @Test
    fun `binary reader rejects truncation and trailing bytes`() {
        val writer = BinaryWriter().apply {
            u8(1)
            u16(2)
            u32(3)
            u64(4)
            bytes(byteArrayOf(5, 6))
        }
        val bytes = writer.toByteArray()
        assertEquals(1 + 2 + 4 + 8 + 2, bytes.size)

        val reader = BinaryReader(bytes)
        assertEquals(1, reader.u8())
        assertEquals(2, reader.u16())
        assertEquals(3L, reader.u32())
        assertEquals(4L, reader.u64())
        assertArrayEquals(byteArrayOf(5, 6), reader.bytes(2))
        reader.requireFullyConsumed()

        assertThrowsMalformed { BinaryReader(bytes.copyOf(bytes.size - 1)).bytes(bytes.size) }
        assertThrowsMalformed { BinaryReader(ByteArray(4)).u64() }
        assertThrowsMalformed { BinaryReader(bytes, maxBytes = 4) }
        assertThrowsMalformed {
            val trailing = BinaryReader(bytes + byteArrayOf(9))
            trailing.bytes(bytes.size)
            trailing.requireFullyConsumed()
        }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }

    private fun assertThrowsMalformed(block: () -> Unit) {
        try {
            block()
        } catch (expected: MalformedPayloadException) {
            return
        }
        throw AssertionError("expected MalformedPayloadException")
    }
}
