package com.cipherwell.notezero.data

import com.cipherwell.notezero.crypto.Encoding
import com.cipherwell.notezero.model.AttachmentRef
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.ReadingBookmark
import com.cipherwell.notezero.model.ReadingState

/**
 * Versioned binary payload stored **inside** the AES-256-GCM item envelope.
 *
 * Everything that is content (title, body, tags, folder reference, pinned/favourite/trash
 * flags, edit version, attachment references) lives here, i.e. inside the ciphertext; the
 * Room row only carries structural columns. The item id and [ItemKind] are duplicated here
 * deliberately: the AAD binds the row's id/kind and this payload carries them too, so a row
 * whose plaintext structural columns were tampered with is detected as corrupt instead of
 * surfaced.
 *
 * Encoding (all integers big-endian):
 * ```
 * magic        4 bytes  "NZIT"
 * envelope     1 byte   1, 2, 3 or 4 (4 is written; 1-3 are still decoded)
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
 * -- envelope version 2 appends the attachment list: --
 * attachCount  2 bytes  u16 (<= AttachmentLimits.MAX_ATTACHMENTS_PER_NOTE)
 *   idLen      2 bytes  u16 (1..MAX_ATTACHMENT_ID_BYTES) + id UTF-8
 *   nameLen    4 bytes  u32 (1..MAX_ATTACHMENT_NAME_BYTES) + display name UTF-8
 *   mimeLen    2 bytes  u16 (1..MAX_ATTACHMENT_MIME_BYTES) + mime type UTF-8
 *   size       8 bytes  i64 (0..AttachmentLimits.MAX_ATTACHMENT_BYTES)
 *   createdAt  8 bytes  i64 epoch millis (>= 0)
 *   sha256    32 bytes  raw SHA-256 (lowercase hex in the model)
 * -- envelope version 3 appends the lock flag: --
 * locked       1 byte   0 = unlocked, 1 = locked (anything else is corrupt)
 * -- envelope version 4 appends the encrypted reading state (books/PDFs): --
 * hasState     1 byte   0 = none, 1 = present
 * position     4 bytes  u32 chapter/page index (<= ReadingState.MAX_POSITION)
 * fractionBits 4 bytes  u32 IEEE-754 float bits of the progress fraction (0.0..1.0)
 * total        4 bytes  u32 chapters/pages count (0 = unknown, <= ReadingState.MAX_POSITION)
 * metaFlags    1 byte   bit0 author present, bit1 language present
 *   len        2 bytes  u16 UTF-8 bytes (<= ReadingState.MAX_METADATA_BYTES each)
 * bookmarkCount 2 bytes u16 (<= ReadingState.MAX_BOOKMARKS)
 *   position   4 bytes  u32
 *   createdAt  8 bytes  i64 epoch millis (>= 0)
 *   labelLen   2 bytes  u16 UTF-8 bytes (<= ReadingState.MAX_LABEL_BYTES)
 * ```
 * Version compatibility (no re-encryption/migration; the next save writes the newest
 * version): a version-1 payload has no attachment list and no lock byte and decodes to
 * `attachments = emptyList()`, `locked = false`; a version-2 payload keeps its attachment
 * list and decodes to `locked = false` (pre-lock vaults were never locked); a version-3
 * payload carries the lock flag. Version 4 appends the reading state (position, progress,
 * bookmarks, book author/language); a version-3- payload decodes to `readingState = null`.
 * All of it is content — inside the GCM envelope, never in a plaintext Room column, and none of
 * it changes the VMK or the AAD.
 *
 * Decoding fails closed: [decode] returns null on unknown magic/version/flags, truncation,
 * oversized fields, bad attachment metadata or trailing bytes. It never throws and never
 * allocates more than [MAX_PAYLOAD_BYTES].
 */
object ItemCodec {

    /** Version written by this implementation. */
    const val ENVELOPE_VERSION: Int = 4

    /** Oldest payload version still accepted (pre-attachments vaults). */
    const val MIN_DECODE_VERSION: Int = 1

    private const val META_AUTHOR = 0x01
    private const val META_LANGUAGE = 0x02
    private const val KNOWN_META_FLAGS = META_AUTHOR or META_LANGUAGE

    const val MAX_PAYLOAD_BYTES: Int = MAX_DECODED_PAYLOAD_BYTES
    const val MAX_TITLE_BYTES: Int = 8 * 1024
    const val MAX_BODY_BYTES: Int = 16 * 1024 * 1024
    const val MAX_ID_BYTES: Int = 128
    const val MAX_FOLDER_ID_BYTES: Int = 128
    const val MAX_TAGS: Int = 256
    const val MAX_TAG_BYTES: Int = 512

    const val MAX_ATTACHMENT_NAME_BYTES: Int = 1024
    const val MAX_ATTACHMENT_MIME_BYTES: Int = 256
    const val SHA256_HEX_CHARS: Int = 64
    const val SHA256_RAW_BYTES: Int = 32

    /** Attachment count limit of one item payload (same value as the repository limit). */
    const val MAX_ATTACHMENTS_PER_ITEM: Int = AttachmentLimits.MAX_ATTACHMENTS_PER_NOTE

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
        require(item.attachments.size <= MAX_ATTACHMENTS_PER_ITEM) { "too many attachments" }

        var flags = 0
        if (folderId != null) flags = flags or FLAG_FOLDER
        if (item.pinned) flags = flags or FLAG_PINNED
        if (item.favorite) flags = flags or FLAG_FAVORITE
        if (item.trashed) flags = flags or FLAG_TRASHED

        val attachmentBytes = item.attachments.sumOf { attachment ->
            attachment.id.toByteArray(Charsets.UTF_8).size +
                attachment.displayName.toByteArray(Charsets.UTF_8).size +
                attachment.mimeType.toByteArray(Charsets.UTF_8).size + 64
        }
        return BinaryWriter(title.size + body.size + attachmentBytes + 256).apply {
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
            u16(item.attachments.size)
            for (attachment in item.attachments) {
                val attachmentId = attachment.id.toByteArray(Charsets.UTF_8)
                require(attachmentId.isNotEmpty() && attachmentId.size <= MAX_ID_BYTES) {
                    "invalid attachment id"
                }
                val name = attachment.displayName.toByteArray(Charsets.UTF_8)
                require(name.isNotEmpty() && name.size <= MAX_ATTACHMENT_NAME_BYTES) {
                    "attachment name exceeds limit"
                }
                val mime = attachment.mimeType.toByteArray(Charsets.UTF_8)
                require(mime.isNotEmpty() && mime.size <= MAX_ATTACHMENT_MIME_BYTES) {
                    "attachment mime type exceeds limit"
                }
                require(attachment.sizeBytes in 0..AttachmentLimits.MAX_ATTACHMENT_BYTES) {
                    "attachment size out of range"
                }
                require(attachment.createdAt >= 0L) { "attachment createdAt out of range" }
                val sha256 = decodeSha256Hex(attachment.sha256)
                    ?: throw IllegalArgumentException("invalid attachment sha256")
                lenPrefixedU16(attachmentId, MAX_ID_BYTES)
                lenPrefixedU32(name, MAX_ATTACHMENT_NAME_BYTES)
                lenPrefixedU16(mime, MAX_ATTACHMENT_MIME_BYTES)
                u64(attachment.sizeBytes)
                u64(attachment.createdAt)
                bytes(sha256)
            }
            // Envelope version 3 appends the lock flag (content: sealed by the GCM tag).
            u8(if (item.locked) 1 else 0)
            // Envelope version 4 appends the encrypted reading state.
            val state = item.readingState
            if (state == null) {
                u8(0)
            } else {
                require(state.position in 0..ReadingState.MAX_POSITION) { "reading position out of range" }
                require(state.fraction.isFinite() && state.fraction in 0f..1f) {
                    "reading fraction out of range"
                }
                require(state.bookmarks.size <= ReadingState.MAX_BOOKMARKS) { "too many bookmarks" }
                require(state.total in 0..ReadingState.MAX_POSITION) { "reading total out of range" }
                u8(1)
                u32(state.position.toLong())
                u32(state.fraction.toRawBits().toLong() and 0xFFFFFFFFL)
                u32(state.total.toLong())
                var metaFlags = 0
                val authorBytes = state.author?.toByteArray(Charsets.UTF_8)
                if (authorBytes != null) metaFlags = metaFlags or META_AUTHOR
                val languageBytes = state.language?.toByteArray(Charsets.UTF_8)
                if (languageBytes != null) metaFlags = metaFlags or META_LANGUAGE
                u8(metaFlags)
                if (authorBytes != null) {
                    require(authorBytes.size <= ReadingState.MAX_METADATA_BYTES) {
                        "book author exceeds limit"
                    }
                    lenPrefixedU16(authorBytes, ReadingState.MAX_METADATA_BYTES)
                }
                if (languageBytes != null) {
                    require(languageBytes.size <= ReadingState.MAX_METADATA_BYTES) {
                        "book language exceeds limit"
                    }
                    lenPrefixedU16(languageBytes, ReadingState.MAX_METADATA_BYTES)
                }
                u16(state.bookmarks.size)
                for (bookmark in state.bookmarks) {
                    require(bookmark.position in 0..ReadingState.MAX_POSITION) {
                        "bookmark position out of range"
                    }
                    require(bookmark.createdAt >= 0L) { "bookmark createdAt out of range" }
                    val label = bookmark.label.toByteArray(Charsets.UTF_8)
                    require(label.size <= ReadingState.MAX_LABEL_BYTES) { "bookmark label exceeds limit" }
                    u32(bookmark.position.toLong())
                    u64(bookmark.createdAt)
                    lenPrefixedU16(label, ReadingState.MAX_LABEL_BYTES)
                }
            }
        }.toByteArray()
    }

    /** Returns the decoded item, or null when the payload is corrupt/tampered/oversized. */
    fun decode(payload: ByteArray): Item? {
        return try {
            val reader = BinaryReader(payload, MAX_PAYLOAD_BYTES)
            if (reader.u32() != MAGIC) return null
            val envelopeVersion = reader.u8()
            if (envelopeVersion !in MIN_DECODE_VERSION..ENVELOPE_VERSION) return null
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
            val attachments = if (envelopeVersion >= 2) {
                val attachmentCount = reader.u16()
                if (attachmentCount > MAX_ATTACHMENTS_PER_ITEM) return null
                val list = ArrayList<AttachmentRef>(attachmentCount)
                repeat(attachmentCount) {
                    val attachmentId = reader.utf8LenPrefixedU16(MAX_ID_BYTES)
                    if (attachmentId.isEmpty()) return null
                    val displayName = reader.utf8LenPrefixedU32(MAX_ATTACHMENT_NAME_BYTES)
                    if (displayName.isEmpty()) return null
                    val mimeType = reader.utf8LenPrefixedU16(MAX_ATTACHMENT_MIME_BYTES)
                    if (mimeType.isEmpty()) return null
                    val sizeBytes = reader.u64()
                    if (sizeBytes < 0L || sizeBytes > AttachmentLimits.MAX_ATTACHMENT_BYTES) return null
                    val attachmentCreatedAt = reader.u64()
                    if (attachmentCreatedAt < 0L) return null
                    list.add(
                        AttachmentRef(
                            id = attachmentId,
                            displayName = displayName,
                            mimeType = mimeType,
                            sizeBytes = sizeBytes,
                            createdAt = attachmentCreatedAt,
                            sha256 = Encoding.toHex(reader.bytes(SHA256_RAW_BYTES)),
                        ),
                    )
                }
                list
            } else {
                emptyList()
            }
            val locked = if (envelopeVersion >= 3) {
                when (reader.u8()) {
                    0 -> false
                    1 -> true
                    else -> return null
                }
            } else {
                false
            }
            val readingState = if (envelopeVersion >= 4) {
                when (reader.u8()) {
                    0 -> null
                    1 -> decodeReadingState(reader) ?: return null
                    else -> return null
                }
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
                attachments = attachments,
                locked = locked,
                readingState = readingState,
            )
        } catch (t: Throwable) {
            null
        }
    }

    /** Decodes the v4 reading state; null when any field is out of its documented bounds. */
    private fun decodeReadingState(reader: BinaryReader): ReadingState? {
        val position = reader.u32()
        if (position > ReadingState.MAX_POSITION) return null
        val fraction = Float.fromBits(reader.u32().toInt())
        if (!fraction.isFinite() || fraction !in 0f..1f) return null
        val total = reader.u32()
        if (total > ReadingState.MAX_POSITION) return null
        val metaFlags = reader.u8()
        if (metaFlags and KNOWN_META_FLAGS.inv() and 0xFF != 0) return null
        val author = if (metaFlags and META_AUTHOR != 0) {
            reader.utf8LenPrefixedU16(ReadingState.MAX_METADATA_BYTES)
        } else {
            null
        }
        val language = if (metaFlags and META_LANGUAGE != 0) {
            reader.utf8LenPrefixedU16(ReadingState.MAX_METADATA_BYTES)
        } else {
            null
        }
        val bookmarkCount = reader.u16()
        if (bookmarkCount > ReadingState.MAX_BOOKMARKS) return null
        val bookmarks = ArrayList<ReadingBookmark>(bookmarkCount)
        repeat(bookmarkCount) {
            val bookmarkPosition = reader.u32()
            if (bookmarkPosition > ReadingState.MAX_POSITION) return null
            val createdAt = reader.u64()
            if (createdAt < 0L) return null
            val label = reader.utf8LenPrefixedU16(ReadingState.MAX_LABEL_BYTES)
            bookmarks.add(
                ReadingBookmark(
                    position = bookmarkPosition.toInt(),
                    label = label,
                    createdAt = createdAt,
                ),
            )
        }
        return ReadingState(
            position = position.toInt(),
            fraction = fraction,
            total = total.toInt(),
            bookmarks = bookmarks,
            author = author,
            language = language,
        )
    }

    /** Strict lowercase-hex SHA-256 decoding; null when it is not exactly 64 hex chars. */
    private fun decodeSha256Hex(hex: String): ByteArray? {
        if (hex.length != SHA256_HEX_CHARS) return null
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return Encoding.fromHex(hex)
    }
}
