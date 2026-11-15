package com.cipherwell.notezero.data

import com.cipherwell.notezero.model.Folder

/**
 * Versioned binary payload stored **inside** the AES-256-GCM folder envelope. Folder
 * names and the tree structure (parent reference) are content, so they never appear in
 * plaintext Room columns; only the folder id/created_at are structural row columns.
 *
 * Encoding (all integers big-endian):
 * ```
 * magic        4 bytes  "NZFD"
 * envelope     1 byte   1
 * flags        1 byte   bit0 parentId present (reserved bits must be 0)
 * createdAt    8 bytes  i64 epoch millis
 * sortOrder    4 bytes  i32
 * idLen        2 bytes  u16, then id UTF-8 (1..128)
 * nameLen      4 bytes  u32 bounded by MAX_NAME_BYTES
 * parentIdLen  2 bytes  u16 when flag bit0 set, then parent id UTF-8
 * ```
 * [decode] fails closed (null) on corruption/tampering and never throws.
 */
object FolderCodec {

    const val ENVELOPE_VERSION: Int = 1
    const val MAX_PAYLOAD_BYTES: Int = MAX_DECODED_PAYLOAD_BYTES
    const val MAX_ID_BYTES: Int = 128
    const val MAX_NAME_BYTES: Int = 8 * 1024

    private const val MAGIC: Long = 0x4E5A4644L // "NZFD"
    private const val FLAG_PARENT = 0x01
    private const val KNOWN_FLAGS = FLAG_PARENT

    fun encode(folder: Folder): ByteArray {
        val id = folder.id.toByteArray(Charsets.UTF_8)
        require(id.isNotEmpty() && id.size <= MAX_ID_BYTES) { "invalid folder id" }
        val name = folder.name.toByteArray(Charsets.UTF_8)
        require(name.size <= MAX_NAME_BYTES) { "folder name exceeds limit" }
        val parentId = folder.parentId?.toByteArray(Charsets.UTF_8)
        require(parentId == null || parentId.size <= MAX_ID_BYTES) { "parent id exceeds limit" }
        var flags = 0
        if (parentId != null) flags = flags or FLAG_PARENT
        return BinaryWriter(name.size + 128).apply {
            u32(MAGIC)
            u8(ENVELOPE_VERSION)
            u8(flags)
            u64(folder.createdAt)
            i32(folder.sortOrder)
            lenPrefixedU16(id, MAX_ID_BYTES)
            u32(name.size.toLong())
            bytes(name)
            if (parentId != null) lenPrefixedU16(parentId, MAX_ID_BYTES)
        }.toByteArray()
    }

    /** Returns the decoded folder, or null when the payload is corrupt/tampered/oversized. */
    fun decode(payload: ByteArray): Folder? {
        return try {
            val reader = BinaryReader(payload, MAX_PAYLOAD_BYTES)
            if (reader.u32() != MAGIC) return null
            if (reader.u8() != ENVELOPE_VERSION) return null
            val flags = reader.u8()
            if (flags and KNOWN_FLAGS.inv() and 0xFF != 0) return null
            val createdAt = reader.u64()
            val sortOrder = reader.i32()
            val id = reader.utf8LenPrefixedU16(MAX_ID_BYTES)
            if (id.isEmpty()) return null
            val name = reader.utf8LenPrefixedU32(MAX_NAME_BYTES)
            val parentId = if (flags and FLAG_PARENT != 0) {
                reader.utf8LenPrefixedU16(MAX_ID_BYTES).takeIf { it.isNotEmpty() }
            } else {
                null
            }
            reader.requireFullyConsumed()
            Folder(
                id = id,
                name = name,
                parentId = parentId,
                sortOrder = sortOrder,
                createdAt = createdAt,
            )
        } catch (t: Throwable) {
            null
        }
    }
}
