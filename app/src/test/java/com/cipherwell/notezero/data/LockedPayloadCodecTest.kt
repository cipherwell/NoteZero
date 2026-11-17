package com.cipherwell.notezero.data

import com.cipherwell.notezero.crypto.Encoding
import com.cipherwell.notezero.model.AttachmentRef
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the lock flag inside the encrypted payloads (decision #15).
 *
 * Covers the version tables of `ItemCodec` (v1 → no attachments, not locked; v2 →
 * attachments, not locked; v3 → lock flag) and `FolderCodec` (v1 → not locked; v2 → lock
 * flag), including hand-built legacy fixtures so backward compatibility is pinned to the
 * documented byte layout instead of to the current encoder.
 */
class LockedPayloadCodecTest {

    // ---- fixtures ------------------------------------------------------------

    private fun note(
        id: String = "note-locked-1",
        title: String = "Locked title",
        body: String = "Locked body",
        tags: List<String> = listOf("secret"),
        folderId: String? = "folder-1",
        pinned: Boolean = true,
        locked: Boolean = false,
        attachments: List<AttachmentRef> = emptyList(),
        readingState: com.cipherwell.notezero.model.ReadingState? = null,
    ) = Item(
        id = id,
        kind = ItemKind.NOTE,
        title = title,
        body = body,
        tags = tags,
        folderId = folderId,
        pinned = pinned,
        favorite = false,
        trashed = false,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_001_000L,
        version = 3L,
        attachments = attachments,
        locked = locked,
        readingState = readingState,
    )

    private fun attachment(
        id: String = "11111111-1111-4111-8111-111111111111",
        displayName: String = "diagram.png",
        mimeType: String = "image/png",
        sizeBytes: Long = 4096L,
    ) = AttachmentRef(
        id = id,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        createdAt = 1_700_000_000_500L,
        sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    )

    /** Version-1 layout: fixed header, strings, folder id, no attachment list, no lock byte. */
    private fun legacyV1ItemPayload(): ByteArray {
        val title = "legacy title".toByteArray(Charsets.UTF_8)
        val body = "legacy body".toByteArray(Charsets.UTF_8)
        val id = "legacy-id".toByteArray(Charsets.UTF_8)
        val folder = "legacy-folder".toByteArray(Charsets.UTF_8)
        return BinaryWriter().apply {
            u32(0x4E5A4954L) // NZIT
            u8(1) // envelope version 1
            u8(ItemKind.NOTE.ordinal)
            u8(0x03) // folder present | pinned
            u64(1_700_000_000_000L)
            u64(1_700_000_009_000L)
            u64(9L)
            lenPrefixedU16(id, ItemCodec.MAX_ID_BYTES)
            u32(title.size.toLong())
            bytes(title)
            u32(body.size.toLong())
            bytes(body)
            u16(2)
            lenPrefixedU16("alpha".toByteArray(Charsets.UTF_8), ItemCodec.MAX_TAG_BYTES)
            lenPrefixedU16("beta".toByteArray(Charsets.UTF_8), ItemCodec.MAX_TAG_BYTES)
            lenPrefixedU16(folder, ItemCodec.MAX_FOLDER_ID_BYTES)
        }.toByteArray()
    }

    /** Version-2 layout: v1 fields plus the attachment list, no lock byte. */
    private fun legacyV2ItemPayload(): ByteArray {
        val title = "v2 title".toByteArray(Charsets.UTF_8)
        val body = "v2 body".toByteArray(Charsets.UTF_8)
        val id = "v2-id".toByteArray(Charsets.UTF_8)
        val ref = attachment()
        return BinaryWriter().apply {
            u32(0x4E5A4954L) // NZIT
            u8(2) // envelope version 2
            u8(ItemKind.CODE_FILE.ordinal)
            u8(0x00)
            u64(1_700_000_000_000L)
            u64(1_700_000_002_000L)
            u64(5L)
            lenPrefixedU16(id, ItemCodec.MAX_ID_BYTES)
            u32(title.size.toLong())
            bytes(title)
            u32(body.size.toLong())
            bytes(body)
            u16(1)
            lenPrefixedU16("legacy".toByteArray(Charsets.UTF_8), ItemCodec.MAX_TAG_BYTES)
            u16(1) // one attachment
            lenPrefixedU16(ref.id.toByteArray(Charsets.UTF_8), ItemCodec.MAX_ID_BYTES)
            lenPrefixedU32(ref.displayName.toByteArray(Charsets.UTF_8), ItemCodec.MAX_ATTACHMENT_NAME_BYTES)
            lenPrefixedU16(ref.mimeType.toByteArray(Charsets.UTF_8), ItemCodec.MAX_ATTACHMENT_MIME_BYTES)
            u64(ref.sizeBytes)
            u64(ref.createdAt)
            bytes(Encoding.fromHex(ref.sha256)!!)
        }.toByteArray()
    }

    /** Version-1 folder layout: no lock byte. */
    private fun legacyV1FolderPayload(): ByteArray {
        val id = "folder-legacy".toByteArray(Charsets.UTF_8)
        val name = "Legacy folder".toByteArray(Charsets.UTF_8)
        val parent = "folder-parent".toByteArray(Charsets.UTF_8)
        return BinaryWriter().apply {
            u32(0x4E5A4644L) // NZFD
            u8(1) // envelope version 1
            u8(0x01) // parent present
            u64(1_700_000_000_000L)
            i32(-2)
            lenPrefixedU16(id, FolderCodec.MAX_ID_BYTES)
            u32(name.size.toLong())
            bytes(name)
            lenPrefixedU16(parent, FolderCodec.MAX_ID_BYTES)
        }.toByteArray()
    }

    // ---- ItemCodec version table --------------------------------------------

    @Test
    fun `item codec v1 fixture decodes to unlocked item with no attachments`() {
        val decoded = ItemCodec.decode(legacyV1ItemPayload())
        assertNotNull("v1 payload must stay decodable", decoded)
        assertEquals("legacy-id", decoded!!.id)
        assertEquals(ItemKind.NOTE, decoded.kind)
        assertEquals("legacy title", decoded.title)
        assertEquals("legacy body", decoded.body)
        assertEquals(listOf("alpha", "beta"), decoded.tags)
        assertEquals("legacy-folder", decoded.folderId)
        assertTrue(decoded.pinned)
        assertEquals(9L, decoded.version)
        assertFalse("v1 payloads are never locked", decoded.locked)
        assertTrue(decoded.attachments.isEmpty())
    }

    @Test
    fun `item codec v2 fixture decodes to unlocked item and preserves attachments`() {
        val decoded = ItemCodec.decode(legacyV2ItemPayload())
        assertNotNull("v2 payload must stay decodable", decoded)
        assertEquals("v2-id", decoded!!.id)
        assertEquals(ItemKind.CODE_FILE, decoded.kind)
        assertFalse("v2 payloads are never locked", decoded.locked)
        assertEquals(1, decoded.attachments.size)
        assertEquals(attachment(), decoded.attachments.single())
        assertEquals(5L, decoded.version)
    }

    @Test
    fun `item codec round trips the locked flag and writes the current version`() {
        val locked = note(locked = true, attachments = listOf(attachment()))
        val encoded = ItemCodec.encode(locked)
        assertEquals(6, ItemCodec.ENVELOPE_VERSION)
        assertEquals(ItemCodec.ENVELOPE_VERSION, encoded[4].toInt())
        val decoded = ItemCodec.decode(encoded)
        assertEquals(locked, decoded)
        assertTrue(decoded!!.locked)
        assertEquals(1, decoded.attachments.size)
        assertNull("no reading state was encoded", decoded.readingState)

        val unlocked = note(locked = false, attachments = listOf(attachment()))
        val decodedUnlocked = ItemCodec.decode(ItemCodec.encode(unlocked))
        assertEquals(unlocked, decodedUnlocked)
        assertFalse(decodedUnlocked!!.locked)
    }

    @Test
    fun `version 3 item payloads still decode as unlocked without reading state`() {
        val encodedV6 = ItemCodec.encode(note(locked = true, attachments = emptyList()))
        // v6 suffix is [lock][reading-state][annotation][sketch] presence bytes; strip all
        // three presence bytes and declare the envelope as version 3.
        val v3 = encodedV6.copyOf(encodedV6.size - 3)
        v3[4] = 3
        val decoded = ItemCodec.decode(v3)
        assertNotNull("v3 payload must stay decodable", decoded)
        assertTrue(decoded!!.locked)
        assertNull(decoded.readingState)
        assertNull(decoded.pdfAnnotations)
    }

    @Test
    fun `version 4 item payloads decode without annotations but keep reading state`() {
        val state = com.cipherwell.notezero.model.ReadingState(
            position = 2,
            total = 5,
            bookmarks = emptyList(),
        )
        val encodedV6 = ItemCodec.encode(note(locked = false, readingState = state))
        // Strip the v6 annotation + sketch presence bytes, keep the v4 reading-state bytes.
        val v4 = encodedV6.copyOf(encodedV6.size - 2)
        v4[4] = 4
        val decoded = ItemCodec.decode(v4)
        assertNotNull("v4 payload must stay decodable", decoded)
        assertEquals(state, decoded!!.readingState)
        assertNull(decoded.pdfAnnotations)
    }

    @Test
    fun `item codec lock byte is followed only by presence bytes`() {
        val encoded = ItemCodec.encode(note(locked = true, attachments = emptyList()))
        assertEquals("sketch presence byte", 0, encoded.last().toInt())
        assertEquals("annotation presence byte", 0, encoded[encoded.size - 2].toInt())
        assertEquals("reading-state presence byte", 0, encoded[encoded.size - 3].toInt())
        assertEquals("lock byte", 1, encoded[encoded.size - 4].toInt())

        val corrupt = encoded.copyOf()
        corrupt[corrupt.size - 4] = 2
        assertNull("lock byte must be exactly 0 or 1", ItemCodec.decode(corrupt))

        // Missing bytes (lock or any presence byte) are truncated, never silently unlocked.
        assertNull(ItemCodec.decode(encoded.copyOf(encoded.size - 1)))
        assertNull(ItemCodec.decode(encoded.copyOf(encoded.size - 2)))
        assertNull(ItemCodec.decode(encoded.copyOf(encoded.size - 3)))
        // Trailing bytes after the sketch presence byte are rejected.
        assertNull(ItemCodec.decode(encoded + byteArrayOf(0)))
    }

    @Test
    fun `item codec locked payload differs from unlocked payload only in the lock byte`() {
        val unlocked = ItemCodec.encode(note(locked = false))
        val locked = ItemCodec.encode(note(locked = true))
        assertEquals(unlocked.size, locked.size)
        var differences = 0
        for (index in unlocked.indices) {
            if (unlocked[index] != locked[index]) differences++
        }
        assertEquals("only the lock byte may differ", 1, differences)
        assertEquals(unlocked.size - 4, unlocked.indices.last { unlocked[it] != locked[it] })
    }

    // ---- FolderCodec version table ------------------------------------------

    private fun folder(locked: Boolean = false) = Folder(
        id = "folder-1",
        name = "Projects",
        parentId = "folder-parent",
        sortOrder = 4,
        createdAt = 1_700_000_000_000L,
        locked = locked,
    )

    @Test
    fun `folder codec v1 fixture decodes to unlocked folder`() {
        val decoded = FolderCodec.decode(legacyV1FolderPayload())
        assertNotNull("v1 folder payload must stay decodable", decoded)
        assertEquals("folder-legacy", decoded!!.id)
        assertEquals("Legacy folder", decoded.name)
        assertEquals("folder-parent", decoded.parentId)
        assertEquals(-2, decoded.sortOrder)
        assertFalse(decoded.locked)
    }

    @Test
    fun `folder codec v2 round trips the locked flag and writes version 2`() {
        assertEquals(2, FolderCodec.ENVELOPE_VERSION)
        val locked = FolderCodec.encode(folder(locked = true))
        assertEquals(2, locked[4].toInt())
        assertEquals(folder(locked = true), FolderCodec.decode(locked))
        assertEquals(folder(locked = false), FolderCodec.decode(FolderCodec.encode(folder(false))))
    }

    @Test
    fun `folder codec decodes a current payload re-versioned as v1 to unlocked`() {
        // Same bytes as a v2 folder payload minus the trailing lock byte, version byte 1:
        // this is exactly what a pre-lock build wrote for the same folder.
        val encoded = FolderCodec.encode(folder(locked = true))
        val asV1 = encoded.copyOf(encoded.size - 1)
        asV1[4] = 1
        assertEquals(folder(locked = false), FolderCodec.decode(asV1))
        assertEquals(1, encoded.last().toInt())
    }

    @Test
    fun `folder codec rejects a non-boolean lock byte and truncation`() {
        val encoded = FolderCodec.encode(folder(locked = true))
        val corrupt = encoded.copyOf()
        corrupt[corrupt.size - 1] = 7
        assertNull(FolderCodec.decode(corrupt))
        assertNull(FolderCodec.decode(encoded.copyOf(encoded.size - 1)))
        assertNull(FolderCodec.decode(encoded + byteArrayOf(0)))
        // Unknown future envelope version still fails closed.
        val future = encoded.copyOf()
        future[4] = 3
        assertNull(FolderCodec.decode(future))
        // A v1 marker with a trailing lock byte is trailing garbage.
        val v1WithExtra = legacyV1FolderPayload() + byteArrayOf(1)
        assertNull(FolderCodec.decode(v1WithExtra))
    }
}
