package com.cipherwell.notezero.data

import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind

/**
 * Versioned binary payload stored **inside** the AES-256-GCM item envelope.
 *
 * Everything that is content (title, body, tags, folder reference, pinned/favourite/trash
 * flags, edit version) lives here, i.e. inside the ciphertext; the Room row only carries
 * structural columns. The item id and [ItemKind] are duplicated here deliberately: the
 * AAD binds the row's id/kind and this payload carries them too, so a row whose plaintext
 * structural columns were tampered with is detected as corrupt instead of surfaced.
 *
 * Encoding (all integers big-endian):
 * ```
 * magic        4 bytes  "NZIT"
 * envelope     1 byte   1
 * kind         1 byte   ItemKind ordinal
 * flags        1 byte   bit0 folderId present, bit1 pinned, bit2 favourite, bit3 trashed
 * createdAt    8 bytes  i64 epoch millis
 * updatedAt    8 bytes  i64 epoch millis
 * version      8 bytes  i64 edit version
 * idLen        2 bytes  u16, then id UTF-8 bytes (1..128)
 * titleLen     4 bytes  u32 bounded by MAX_TITLE_BYTES
 * bodyLen      4 bytes  u32 bounded by MAX_BODY_BYTES
 * tagCount     2 bytes  u16 (<= MAX_TAGS)
 *   tagLen     2 bytes  u16 each, then tag UTF-8 bytes
 * folderIdLen  2 bytes  u16 when flag bit0 is set, then folder id UTF-8 bytes
 * ```
 * Decoding fails closed: [decode] returns null on unknown magic/version/flags, truncation,
 * oversized fields or trailing bytes. It never throws and never allocates more than
 * [MAX_PAYLOAD_BYTES].
 */
object ItemCodec {

    const val ENVELOPE_VERSION: Int = 1
    const val MAX_PAYLOAD_BYTES: Int = MAX_DECODED_PAYLOAD_BYTES
    const val MAX_TITLE_BYTES: Int = 8 * 1024
    const val MAX_BODY_BYTES: Int = 16 * 1024 * 1024
    const val MAX_ID_BYTES: Int = 128
    const val MAX_FOLDER_ID_BYTES: Int = 128
    const val MAX_TAGS: Int = 256
    const val MAX_TAG_BYTES: Int = 512

    private const val MAGIC: Long = 0x4E5A4954L // "NZIT"
    private const val FLAG_FOLDER = 0x01
    private const val FLAG_PINNED = 0x02
    private const val FLAG_FAVORITE = 0x04
    private const val FLAG_TRASHED = 0x08
    private const val KNOWN_FLAGS = FLAG_FOLDER or FLAG_PINNED or FLAG_FAVORITE or FLAG_TRASHED

    fun encode(item: Item): ByteArray {
        val id = item.id.toByteArray(Charsets.UTF_8)
        require(id.isNotEmpty() && id.size <= MAX_ID_BYTES) { "invalid item id" }
        val title = item.title.toByteArray(Charsets.UTF_8)
        require(title.size <= MAX_TITLE_BYTES) { "title exceeds limit" }
        val body = item.body.toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_BODY_BYTES) { "body exceeds limit" }
        require(item.tags.size <= MAX_TAGS) { "too many tags" }
        val folderId = item.folderId?.toByteArray(Charsets.UTF_8)
        require(folderId == null || folderId.size <= MAX_FOLDER_ID_BYTES) { "folder id exceeds limit" }

        var flags = 0
        if (folderId != null) flags = flags or FLAG_FOLDER
        if (item.pinned) flags = flags or FLAG_PINNED
        if (item.favorite) flags = flags or FLAG_FAVORITE
        if (item.trashed) flags = flags or FLAG_TRASHED

        return BinaryWriter(title.size + body.size + 256).apply {
            u32(MAGIC)
            u8(ENVELOPE_VERSION)
            u8(item.kind.ordinal)
            u8(flags)
            u64(item.createdAt)
            u64(item.updatedAt)
            u64(item.version)
            lenPrefixedU16(id, MAX_ID_BYTES)
            u32(title.size.toLong())
            bytes(title)
            u32(body.size.toLong())
            bytes(body)
            u16(item.tags.size)
            for (tag in item.tags) lenPrefixedU16(tag.toByteArray(Charsets.UTF_8), MAX_TAG_BYTES)
            if (folderId != null) lenPrefixedU16(folderId, MAX_FOLDER_ID_BYTES)
        }.toByteArray()
    }

    /** Returns the decoded item, or null when the payload is corrupt/tampered/oversized. */
    fun decode(payload: ByteArray): Item? {
        return try {
            val reader = BinaryReader(payload, MAX_PAYLOAD_BYTES)
            if (reader.u32() != MAGIC) return null
            if (reader.u8() != ENVELOPE_VERSION) return null
            val kindOrdinal = reader.u8()
            val kind = ItemKind.entries.getOrNull(kindOrdinal) ?: return null
            val flags = reader.u8()
            if (flags and KNOWN_FLAGS.inv() and 0xFF != 0) return null
            val createdAt = reader.u64()
            val updatedAt = reader.u64()
            val version = reader.u64()
            val id = reader.utf8LenPrefixedU16(MAX_ID_BYTES)
            if (id.isEmpty()) return null
            val title = reader.utf8LenPrefixedU32(MAX_TITLE_BYTES)
            val body = reader.utf8LenPrefixedU32(MAX_BODY_BYTES)
            val tagCount = reader.u16()
            if (tagCount > MAX_TAGS) return null
            val tags = ArrayList<String>(tagCount)
            repeat(tagCount) { tags.add(reader.utf8LenPrefixedU16(MAX_TAG_BYTES)) }
            val folderId = if (flags and FLAG_FOLDER != 0) {
                reader.utf8LenPrefixedU16(MAX_FOLDER_ID_BYTES).takeIf { it.isNotEmpty() }
            } else {
                null
            }
            reader.requireFullyConsumed()
            Item(
                id = id,
                kind = kind,
                title = title,
                body = body,
                tags = tags,
                folderId = folderId,
                pinned = flags and FLAG_PINNED != 0,
                favorite = flags and FLAG_FAVORITE != 0,
                trashed = flags and FLAG_TRASHED != 0,
                createdAt = createdAt,
                updatedAt = updatedAt,
                version = version,
            )
        } catch (t: Throwable) {
            null
        }
    }
}
