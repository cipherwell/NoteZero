package com.cipherwell.notezero.backup

import com.cipherwell.notezero.crypto.CryptoAad
import com.cipherwell.notezero.crypto.EnvelopeType
import com.cipherwell.notezero.crypto.KeyEnvelope
import com.cipherwell.notezero.crypto.NoteCrypto
import com.cipherwell.notezero.data.AttachmentBlob
import com.cipherwell.notezero.data.BinaryReader
import com.cipherwell.notezero.data.BinaryWriter
import com.cipherwell.notezero.data.FolderCodec
import com.cipherwell.notezero.data.ItemCodec
import com.cipherwell.notezero.data.MAX_DECODED_PAYLOAD_BYTES
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Self-contained, stream-based encrypted backup envelope. **Format v2** (attachments);
 * format-v1 files (no attachments) are still read.
 *
 * SAF-agnostic by design: export writes to an [OutputStream], import reads from an
 * [InputStream]; the UI resolves the `content://` Uri and nothing here needs Android.
 *
 * Security model
 * --------------
 * The payload holds the *decrypted* items and folders plus the attachment blobs, and is
 * sealed with AES-256-GCM under the source vault's VMK. The AAD is `CryptoAad.BACKUP`
 * followed by the exact header bytes, so every header field (counts, mode, embedded key
 * envelopes) is authenticated: a tampered count, swapped envelope blob or truncated
 * payload fails authentication and the import returns null instead of partially restoring.
 *
 * Each attachment blob travels as the same `NZAT` file format used on disk: its own
 * magic+version+AES-256-GCM envelope under the source VMK with
 * `CryptoAad.attachment(noteId, attachmentId)`. So a blob cannot be moved between notes or
 * attachment ids even inside the (already encrypted) backup payload, and the importer can
 * reproduce the exact plaintext bytes and verify their SHA-256 before re-encrypting them
 * under the destination vault's VMK.
 *
 * Only *wrapped* key material travels in the plaintext header — the [KeyEnvelope] blobs
 * that wrap the VMK under either the source vault password, the source recovery phrase,
 * or (in [MODE_EXTRA_PASSWORD]) a password chosen for this backup file. The VMK itself
 * is never written.
 *
 * Wire format (big-endian)
 * ------------------------
 * ```
 * header (authenticated as AAD, not encrypted):
 *   magic            4 bytes  "NZBK"
 *   formatVersion    1 byte   1 (legacy, no attachments) or 2 (attachments)
 *   mode             1 byte   0 = vault key, 1 = extra backup password
 *   flags            1 byte   bit0 password envelope present, bit1 recovery envelope present
 *   reserved         1 byte   0
 *   exportedAt       8 bytes  epoch millis
 *   itemCount        4 bytes  u32 (must match the payload)
 *   folderCount      4 bytes  u32 (must match the payload)
 *   attachmentCount  4 bytes  u32 (v2 only; must match the payload)
 *   envelopeLen      4 bytes  u32 length of the envelope block below
 *   envelopes        envelopeLen bytes:
 *                      count 1 byte  (<= 4)
 *                      each: length u16 + KeyEnvelope.toBytes()
 * sealedBody:
 *   payloadLen       4 bytes  u32
 *   payload          payloadLen bytes: AES-GCM iv(12) || ciphertext || tag(16)
 * ```
 * Decrypted payload v1: `u8 version=1, u32 itemCount, u32 folderCount`, then
 * `ItemCodec`/`FolderCodec` records. Decrypted payload v2 adds `u32 attachmentCount`
 * after the folder count and appends, after the folders:
 * `u16 noteIdLen + noteId, u16 attachmentIdLen + attachmentId, u32 blobLen + NZAT blob`.
 */
object BackupFormat {

    const val MAGIC: String = "NZBK"

    /** Version written by this implementation (attachments included). */
    const val FORMAT_VERSION: Int = 2

    /** Legacy version still accepted on read (pre-attachment backups). */
    const val MIN_DECODE_FORMAT_VERSION: Int = 1

    /**
     * Header embeds the source vault's own password and recovery [KeyEnvelope]s, so the
     * file is restorable with the source vault password or the source 12-word phrase.
     */
    const val MODE_VAULT_KEY: Int = 0

    /** Header embeds a single password-derived [KeyEnvelope] over the source VMK. */
    const val MODE_EXTRA_PASSWORD: Int = 1

    /** Refuse to read/allocate a backup larger than this (OOM guard, not a real limit). */
    const val MAX_BACKUP_BYTES: Long = 64L * 1024 * 1024

    const val MAX_HEADER_BYTES: Int = 16 * 1024
    const val MAX_ITEM_COUNT: Int = 500_000
    const val MAX_FOLDER_COUNT: Int = 100_000
    const val MAX_ATTACHMENT_COUNT: Int = 100_000

    /** Largest payload this format can carry inside the size cap above. */
    val MAX_PAYLOAD_BYTES: Int = (MAX_BACKUP_BYTES - MAX_HEADER_BYTES).toInt()

    private const val HEADER_FIXED_BYTES_V1: Int = 28
    private const val HEADER_FIXED_BYTES_V2: Int = 32
    private const val MAX_ENVELOPES: Int = 4
    private const val PAYLOAD_VERSION_V1: Int = 1
    private const val PAYLOAD_VERSION_V2: Int = 2
    private const val FLAG_PASSWORD_ENVELOPE: Int = 0x01
    private const val FLAG_RECOVERY_ENVELOPE: Int = 0x02
    private const val KNOWN_FLAGS: Int = FLAG_PASSWORD_ENVELOPE or FLAG_RECOVERY_ENVELOPE
    private const val MIN_SEALED_BYTES: Long =
        NoteCrypto.GCM_IV_LENGTH_BYTES + NoteCrypto.GCM_TAG_LENGTH_BYTES + 1L
    private const val MAX_ATTACHMENT_REF_ID_BYTES: Int = 128

    /**
     * One attachment blob as stored in the backup payload: the original `NZAT` blob bytes
     * (already encrypted under the source VMK + attachment AAD), plus the ids binding it.
     */
    data class AttachmentBlobRecord(
        val noteId: String,
        val attachmentId: String,
        val blob: ByteArray,
    ) {
        // ByteArray identity/equality is reference-based; document rather than rely on it.
        override fun equals(other: Any?): Boolean =
            other is AttachmentBlobRecord &&
                noteId == other.noteId &&
                attachmentId == other.attachmentId &&
                blob.contentEquals(other.blob)

        override fun hashCode(): Int = 31 * (31 * noteId.hashCode() + attachmentId.hashCode()) + blob.contentHashCode()
    }

    /** Plaintext contents of one backup; what the UI restores. */
    data class Contents(
        val exportedAt: Long,
        val items: List<Item>,
        val folders: List<Folder>,
        val attachments: List<AttachmentBlobRecord> = emptyList(),
    )

    /**
     * Writes a complete backup envelope to [out] and flushes it. The stream is NOT closed
     * (the caller owns the SAF stream). Throws only on programming errors / IO failure;
     * callers treat any throw as "export failed". The whole contents are encoded and
     * sealed before the first byte is written, so a size-limit failure never leaves a
     * partial file.
     */
    fun write(
        out: OutputStream,
        contents: Contents,
        vmk: ByteArray,
        mode: Int,
        envelopes: List<KeyEnvelope>,
    ) {
        require(vmk.size == NoteCrypto.VMK_LENGTH_BYTES) { "VMK must be 32 bytes" }
        validateEnvelopesForMode(mode, envelopes)
        require(contents.items.size <= MAX_ITEM_COUNT) { "too many items" }
        require(contents.folders.size <= MAX_FOLDER_COUNT) { "too many folders" }
        require(contents.attachments.size <= MAX_ATTACHMENT_COUNT) { "too many attachments" }

        val flags = envelopeFlags(envelopes)
        val header = buildHeader(
            formatVersion = FORMAT_VERSION,
            mode = mode,
            flags = flags,
            exportedAt = contents.exportedAt,
            itemCount = contents.items.size,
            folderCount = contents.folders.size,
            attachmentCount = contents.attachments.size,
            envelopes = envelopes,
        )
        val aad = buildAad(header)
        val plaintext = encodePayload(contents)
        try {
            val sealed = NoteCrypto.encrypt(NoteCrypto.secretKey(vmk), plaintext, aad)
            val buffered = BufferedOutputStream(out, 64 * 1024)
            buffered.write(header)
            writeU32(buffered, sealed.size.toLong())
            buffered.write(sealed)
            buffered.flush()
        } finally {
            NoteCrypto.wipe(plaintext)
        }
    }

    /**
     * Parses and authenticates the envelope structure from [input] (the whole stream, up
     * to [MAX_BACKUP_BYTES]). Returns null for anything malformed/oversized — never throws
     * on hostile input. Call [BackupEnvelope.decrypt] with a candidate VMK to open it.
     */
    fun read(input: InputStream): BackupEnvelope? = try {
        val bytes = readBounded(input)
        if (bytes == null || bytes.isEmpty()) null else decodeEnvelope(bytes)
    } catch (t: Throwable) {
        null
    }

    // ---- parsing -------------------------------------------------------------

    private fun decodeEnvelope(bytes: ByteArray): BackupEnvelope? {
        if (bytes.size < HEADER_FIXED_BYTES_V1 + 4) return null
        val reader = BinaryReader(bytes, (MAX_BACKUP_BYTES + MAX_HEADER_BYTES).toInt())
        if (String(reader.bytes(MAGIC.length), Charsets.UTF_8) != MAGIC) return null
        val formatVersion = reader.u8()
        if (formatVersion !in MIN_DECODE_FORMAT_VERSION..FORMAT_VERSION) return null
        val headerFixedBytes = if (formatVersion >= 2) HEADER_FIXED_BYTES_V2 else HEADER_FIXED_BYTES_V1
        val mode = reader.u8()
        if (mode != MODE_VAULT_KEY && mode != MODE_EXTRA_PASSWORD) return null
        val flags = reader.u8()
        if (flags and KNOWN_FLAGS.inv() and 0xFF != 0) return null
        if (reader.u8() != 0) return null // reserved
        val exportedAt = reader.u64()
        val itemCount = reader.u32()
        val folderCount = reader.u32()
        if (itemCount > MAX_ITEM_COUNT || folderCount > MAX_FOLDER_COUNT) return null
        val attachmentCount = if (formatVersion >= 2) {
            val count = reader.u32()
            if (count > MAX_ATTACHMENT_COUNT) return null
            count
        } else {
            0L
        }
        val envelopeBlockLength = reader.u32()
        if (envelopeBlockLength > MAX_HEADER_BYTES) return null
        val envelopeBlock = reader.bytes(envelopeBlockLength.toInt())
        val envelopes = parseEnvelopeBlock(envelopeBlock) ?: return null
        if ((flags and FLAG_PASSWORD_ENVELOPE != 0) != envelopes.any { it.type == EnvelopeType.PASSWORD }) return null
        if ((flags and FLAG_RECOVERY_ENVELOPE != 0) != envelopes.any { it.type == EnvelopeType.RECOVERY }) return null
        try {
            validateEnvelopesForMode(mode, envelopes)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val payloadLength = reader.u32()
        if (payloadLength < MIN_SEALED_BYTES || payloadLength > MAX_BACKUP_BYTES) return null
        val payload = reader.bytes(payloadLength.toInt())
        reader.requireFullyConsumed()
        val headerBytes = bytes.copyOfRange(0, headerFixedBytes + envelopeBlockLength.toInt())
        return BackupEnvelope(
            formatVersion = formatVersion,
            mode = mode,
            exportedAt = exportedAt,
            itemCount = itemCount.toInt(),
            folderCount = folderCount.toInt(),
            attachmentCount = attachmentCount.toInt(),
            keyEnvelopes = envelopes,
            aad = buildAad(headerBytes),
            payload = payload,
        )
    }

    private fun parseEnvelopeBlock(block: ByteArray): List<KeyEnvelope>? {
        return try {
            val reader = BinaryReader(block, MAX_HEADER_BYTES)
            val count = reader.u8()
            if (count > MAX_ENVELOPES) return null
            val envelopes = ArrayList<KeyEnvelope>(count)
            repeat(count) {
                val length = reader.u16()
                if (length > KeyEnvelope.MAX_BYTES) return null
                val envelope = KeyEnvelope.fromBytes(reader.bytes(length)) ?: return null
                envelopes.add(envelope)
            }
            reader.requireFullyConsumed()
            envelopes
        } catch (t: Throwable) {
            null
        }
    }

    private fun readBounded(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > MAX_BACKUP_BYTES) return null
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    // ---- payload -------------------------------------------------------------

    private fun encodePayload(contents: Contents): ByteArray {
        val writer = BinaryWriter(64 * 1024)
        writer.u8(PAYLOAD_VERSION_V2)
        writer.u32(contents.items.size.toLong())
        writer.u32(contents.folders.size.toLong())
        writer.u32(contents.attachments.size.toLong())
        for (item in contents.items) {
            writer.lenPrefixedU32(ItemCodec.encode(item), ItemCodec.MAX_PAYLOAD_BYTES)
            checkPayloadFits(writer)
        }
        for (folder in contents.folders) {
            writer.lenPrefixedU32(FolderCodec.encode(folder), FolderCodec.MAX_PAYLOAD_BYTES)
            checkPayloadFits(writer)
        }
        for (attachment in contents.attachments) {
            val noteId = attachment.noteId.toByteArray(Charsets.UTF_8)
            require(noteId.isNotEmpty() && noteId.size <= MAX_ATTACHMENT_REF_ID_BYTES) {
                "invalid attachment note id"
            }
            val attachmentId = attachment.attachmentId.toByteArray(Charsets.UTF_8)
            require(attachmentId.isNotEmpty() && attachmentId.size <= MAX_ATTACHMENT_REF_ID_BYTES) {
                "invalid attachment id"
            }
            require(attachment.blob.size <= AttachmentBlob.MAX_BLOB_BYTES) { "attachment blob too large" }
            writer.lenPrefixedU16(noteId, MAX_ATTACHMENT_REF_ID_BYTES)
            writer.lenPrefixedU16(attachmentId, MAX_ATTACHMENT_REF_ID_BYTES)
            writer.lenPrefixedU32(attachment.blob, AttachmentBlob.MAX_BLOB_BYTES.toInt())
            checkPayloadFits(writer)
        }
        return writer.toByteArray()
    }

    private fun checkPayloadFits(writer: BinaryWriter) {
        if (writer.size() > MAX_PAYLOAD_BYTES) {
            throw IllegalStateException("backup content exceeds size limit")
        }
    }

    /** Strictly decodes the authenticated plaintext payload; null on any anomaly. */
    internal fun decodePayload(
        payload: ByteArray,
        expectedItemCount: Int,
        expectedFolderCount: Int,
        expectedAttachmentCount: Int = 0,
    ): Contents? {
        val reader = try {
            BinaryReader(payload, MAX_PAYLOAD_BYTES)
        } catch (t: Throwable) {
            return null
        }
        val payloadVersion = reader.u8()
        if (payloadVersion != PAYLOAD_VERSION_V1 && payloadVersion != PAYLOAD_VERSION_V2) return null
        val itemCount = reader.u32()
        if (itemCount < 0 || itemCount > MAX_ITEM_COUNT) return null
        val folderCount = reader.u32()
        if (folderCount < 0 || folderCount > MAX_FOLDER_COUNT) return null
        val attachmentCount = if (payloadVersion >= PAYLOAD_VERSION_V2) {
            val count = reader.u32()
            if (count < 0 || count > MAX_ATTACHMENT_COUNT) return null
            count
        } else {
            0L
        }
        if (itemCount.toInt() != expectedItemCount || folderCount.toInt() != expectedFolderCount) return null
        if (payloadVersion == PAYLOAD_VERSION_V1 && expectedAttachmentCount != 0) return null
        if (attachmentCount.toInt() != expectedAttachmentCount) return null
        val items = ArrayList<Item>(itemCount.toInt())
        repeat(itemCount.toInt()) {
            val encoded = reader.lenPrefixedU32(ItemCodec.MAX_PAYLOAD_BYTES)
            items.add(ItemCodec.decode(encoded) ?: return null)
        }
        val folders = ArrayList<Folder>(folderCount.toInt())
        repeat(folderCount.toInt()) {
            val encoded = reader.lenPrefixedU32(FolderCodec.MAX_PAYLOAD_BYTES)
            folders.add(FolderCodec.decode(encoded) ?: return null)
        }
        val attachments = ArrayList<AttachmentBlobRecord>(attachmentCount.toInt())
        repeat(attachmentCount.toInt()) {
            val noteId = reader.utf8LenPrefixedU16(MAX_ATTACHMENT_REF_ID_BYTES)
            if (noteId.isEmpty()) return null
            val attachmentId = reader.utf8LenPrefixedU16(MAX_ATTACHMENT_REF_ID_BYTES)
            if (attachmentId.isEmpty()) return null
            val blob = reader.lenPrefixedU32(AttachmentBlob.MAX_BLOB_BYTES.toInt())
            if (blob.size < AttachmentBlob.MIN_BLOB_BYTES) return null
            attachments.add(AttachmentBlobRecord(noteId = noteId, attachmentId = attachmentId, blob = blob))
        }
        try {
            reader.requireFullyConsumed()
        } catch (t: Throwable) {
            return null
        }
        return Contents(exportedAt = 0L, items = items, folders = folders, attachments = attachments)
    }

    // ---- header helpers ------------------------------------------------------

    private fun buildHeader(
        formatVersion: Int,
        mode: Int,
        flags: Int,
        exportedAt: Long,
        itemCount: Int,
        folderCount: Int,
        attachmentCount: Int,
        envelopes: List<KeyEnvelope>,
    ): ByteArray {
        val envelopeBlock = BinaryWriter(512).apply {
            u8(envelopes.size)
            for (envelope in envelopes) {
                lenPrefixedU16(envelope.toBytes(), KeyEnvelope.MAX_BYTES)
            }
        }.toByteArray()
        require(envelopeBlock.size <= MAX_HEADER_BYTES) { "headers too large" }
        val fixedBytes = if (formatVersion >= 2) HEADER_FIXED_BYTES_V2 else HEADER_FIXED_BYTES_V1
        return BinaryWriter(fixedBytes + envelopeBlock.size).apply {
            bytes(MAGIC.toByteArray(Charsets.UTF_8))
            u8(formatVersion)
            u8(mode)
            u8(flags)
            u8(0) // reserved
            u64(exportedAt)
            u32(itemCount.toLong())
            u32(folderCount.toLong())
            if (formatVersion >= 2) u32(attachmentCount.toLong())
            u32(envelopeBlock.size.toLong())
            bytes(envelopeBlock)
        }.toByteArray()
    }

    private fun buildAad(headerBytes: ByteArray): ByteArray {
        val domain = CryptoAad.BACKUP.toByteArray(Charsets.UTF_8)
        val aad = ByteArray(domain.size + headerBytes.size)
        System.arraycopy(domain, 0, aad, 0, domain.size)
        System.arraycopy(headerBytes, 0, aad, domain.size, headerBytes.size)
        return aad
    }

    private fun envelopeFlags(envelopes: List<KeyEnvelope>): Int {
        var flags = 0
        if (envelopes.any { it.type == EnvelopeType.PASSWORD }) flags = flags or FLAG_PASSWORD_ENVELOPE
        if (envelopes.any { it.type == EnvelopeType.RECOVERY }) flags = flags or FLAG_RECOVERY_ENVELOPE
        return flags
    }

    private fun validateEnvelopesForMode(mode: Int, envelopes: List<KeyEnvelope>) {
        require(envelopes.size <= MAX_ENVELOPES) { "too many key envelopes" }
        require(envelopes.map { it.type }.distinct().size == envelopes.size) { "duplicate envelope type" }
        when (mode) {
            MODE_EXTRA_PASSWORD -> {
                require(envelopes.size == 1 && envelopes[0].type == EnvelopeType.PASSWORD) {
                    "extra-password backups need exactly one password envelope"
                }
            }

            MODE_VAULT_KEY -> {
                require(envelopes.any { it.type == EnvelopeType.PASSWORD || it.type == EnvelopeType.RECOVERY }) {
                    "vault-key backups need a password or recovery envelope"
                }
            }

            else -> throw IllegalArgumentException("unknown backup mode: $mode")
        }
    }

    private fun writeU32(out: OutputStream, value: Long) {
        require(value in 0..0xFFFFFFFFL) { "u32 out of range" }
        out.write(((value ushr 24) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }
}

/**
 * A structurally valid, not-yet-decrypted backup. Holding one proves the header parsed and
 * the file is within bounds; [decrypt] proves the caller has a credential matching the
 * embedded key envelope.
 */
class BackupEnvelope internal constructor(
    /** 1 = legacy (no attachments), 2 = attachments. */
    val formatVersion: Int,
    val mode: Int,
    val exportedAt: Long,
    val itemCount: Int,
    val folderCount: Int,
    val attachmentCount: Int,
    val keyEnvelopes: List<KeyEnvelope>,
    private val aad: ByteArray,
    private val payload: ByteArray,
) {

    /** The embedded key envelope of [type], when the backup carries one. */
    fun envelopeOf(type: EnvelopeType): KeyEnvelope? =
        keyEnvelopes.firstOrNull { it.type == type }

    /**
     * Decrypts and decodes the payload with [vmk]. Returns null when the VMK does not match
     * the backup, or when the payload is corrupt/tampered (AEAD failure), or when any record
     * fails its bounds-checked decode. The whole file is authenticated before decode.
     *
     * The attachment blobs in the returned [BackupFormat.Contents] are still individually
     * encrypted under [vmk] (same blob that was stored on the source device); the caller
     * (the repository import path) verifies/decrypts them before writing anything.
     */
    fun decrypt(vmk: ByteArray): BackupFormat.Contents? {
        if (vmk.size != NoteCrypto.VMK_LENGTH_BYTES) return null
        val plaintext = try {
            NoteCrypto.decrypt(NoteCrypto.secretKey(vmk), payload, aad)
        } catch (t: Throwable) {
            null
        } ?: return null
        return try {
            decodeDecryptedPayload(plaintext)
        } finally {
            NoteCrypto.wipe(plaintext)
        }
    }

    private fun decodeDecryptedPayload(plaintext: ByteArray): BackupFormat.Contents? {
        // The counts in the authenticated header are cross-checked against the payload by
        // the decoder itself, so a header/payload mismatch fails here.
        val decoded = BackupFormat.decodePayload(
            plaintext,
            expectedItemCount = itemCount,
            expectedFolderCount = folderCount,
            expectedAttachmentCount = attachmentCount,
        ) ?: return null
        return decoded.copy(exportedAt = exportedAt)
    }
}
