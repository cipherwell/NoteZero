package com.cipherwell.notezero.data

import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        wrongVersion[4] = 2.toByte()
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
