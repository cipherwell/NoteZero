package com.cipherwell.notezero.backup

import com.cipherwell.notezero.crypto.CryptoAad
import com.cipherwell.notezero.crypto.EnvelopeType
import com.cipherwell.notezero.crypto.KeyEnvelope
import com.cipherwell.notezero.crypto.NoteCrypto
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
 * Self-contained, stream-based encrypted backup envelope.
 *
 * SAF-agnostic by design: export writes to an [OutputStream], import reads from an
 * [InputStream]; the UI resolves the `content://` Uri and nothing here needs Android.
 *
 * Security model
 * --------------
 * The payload holds the *decrypted* items and folders and is sealed with AES-256-GCM
 * under the source vault's VMK. The AAD is `CryptoAad.BACKUP` followed by the exact
 * header bytes, so every header field (counts, mode, embedded key envelopes) is
 * authenticated: a tampered count, swapped envelope blob or truncated payload fails
 * authentication and the import returns null instead of partially restoring.
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
 *   magic          4 bytes  "NZBK"
 *   formatVersion  1 byte   1
 *   mode           1 byte   0 = vault key, 1 = extra backup password
 *   flags          1 byte   bit0 password envelope present, bit1 recovery envelope present
 *   reserved       1 byte   0
 *   exportedAt     8 bytes  epoch millis
 *   itemCount      4 bytes  u32 (must match the payload)
 *   folderCount    4 bytes  u32 (must match the payload)
 *   envelopeLen    4 bytes  u32 length of the envelope block below
 *   envelopes      envelopeLen bytes:
 *                    count 1 byte  (<= 4)
 *                    each: length u16 + KeyEnvelope.toBytes()
 * sealedBody:
 *   payloadLen     4 bytes  u32
 *   payload        payloadLen bytes: AES-GCM iv(12) || ciphertext || tag(16)
 * ```
 * The decrypted payload is itself versioned (counts + `ItemCodec`/`FolderCodec` records,
 * all length-prefixed and bounds-checked).
 */
object BackupFormat {

    const val MAGIC: String = "NZBK"

    /** Version written by this implementation; bump on any wire change. */
    const val FORMAT_VERSION: Int = 1

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

    private const val HEADER_FIXED_BYTES: Int = 28
    private const val MAX_ENVELOPES: Int = 4
    private const val PAYLOAD_VERSION: Int = 1
    private const val FLAG_PASSWORD_ENVELOPE: Int = 0x01
    private const val FLAG_RECOVERY_ENVELOPE: Int = 0x02
    private const val KNOWN_FLAGS: Int = FLAG_PASSWORD_ENVELOPE or FLAG_RECOVERY_ENVELOPE
    private const val MIN_SEALED_BYTES: Long = NoteCrypto.GCM_IV_LENGTH_BYTES + NoteCrypto.GCM_TAG_LENGTH_BYTES + 1L

    /** Plaintext contents of one backup; what the UI restores. */
    data class Contents(
        val exportedAt: Long,
        val items: List<Item>,
        val folders: List<Folder>,
    )

    /**
     * Writes a complete backup envelope to [out] and flushes it. The stream is NOT closed
     * (the caller owns the SAF stream). Throws only on programming errors / IO failure;
     * callers treat any throw as "export failed".
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

        val flags = envelopeFlags(envelopes)
        val header = buildHeader(
            mode = mode,
            flags = flags,
            exportedAt = contents.exportedAt,
            itemCount = contents.items.size,
            folderCount = contents.folders.size,
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
        if (bytes.size < HEADER_FIXED_BYTES + 4) return null
        val reader = BinaryReader(bytes, (MAX_BACKUP_BYTES + MAX_HEADER_BYTES).toInt())
        if (String(reader.bytes(MAGIC.length), Charsets.UTF_8) != MAGIC) return null
        if (reader.u8() != FORMAT_VERSION) return null
        val mode = reader.u8()
        if (mode != MODE_VAULT_KEY && mode != MODE_EXTRA_PASSWORD) return null
        val flags = reader.u8()
        if (flags and KNOWN_FLAGS.inv() and 0xFF != 0) return null
        if (reader.u8() != 0) return null // reserved
        val exportedAt = reader.u64()
        val itemCount = reader.u32()
        val folderCount = reader.u32()
        if (itemCount > MAX_ITEM_COUNT || folderCount > MAX_FOLDER_COUNT) return null
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
        val headerBytes = bytes.copyOfRange(0, HEADER_FIXED_BYTES + envelopeBlockLength.toInt())
        return BackupEnvelope(
            mode = mode,
            exportedAt = exportedAt,
            itemCount = itemCount.toInt(),
            folderCount = folderCount.toInt(),
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
        writer.u8(PAYLOAD_VERSION)
        writer.u32(contents.items.size.toLong())
        writer.u32(contents.folders.size.toLong())
        for (item in contents.items) {
            writer.lenPrefixedU32(ItemCodec.encode(item), ItemCodec.MAX_PAYLOAD_BYTES)
            if (writer.size() > MAX_BACKUP_BYTES - MAX_HEADER_BYTES) {
                throw IllegalStateException("backup content exceeds size limit")
            }
        }
        for (folder in contents.folders) {
            writer.lenPrefixedU32(FolderCodec.encode(folder), FolderCodec.MAX_PAYLOAD_BYTES)
        }
        return writer.toByteArray()
    }

    /** Strictly decodes the authenticated plaintext payload; false/null on any anomaly. */
    internal fun decodePayload(
        payload: ByteArray,
        expectedItemCount: Int,
        expectedFolderCount: Int,
    ): Contents? {
        val reader = try {
            BinaryReader(payload, MAX_DECODED_PAYLOAD_BYTES)
        } catch (t: Throwable) {
            return null
        }
        if (reader.u8() != PAYLOAD_VERSION) return null
        val itemCount = reader.u32()
        if (itemCount < 0 || itemCount > MAX_ITEM_COUNT) return null
        val folderCount = reader.u32()
        if (folderCount < 0 || folderCount > MAX_FOLDER_COUNT) return null
        if (itemCount.toInt() != expectedItemCount || folderCount.toInt() != expectedFolderCount) return null
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
        try {
            reader.requireFullyConsumed()
        } catch (t: Throwable) {
            return null
        }
        return Contents(exportedAt = 0L, items = items, folders = folders)
    }

    // ---- header helpers ------------------------------------------------------

    private fun buildHeader(
        mode: Int,
        flags: Int,
        exportedAt: Long,
        itemCount: Int,
        folderCount: Int,
        envelopes: List<KeyEnvelope>,
    ): ByteArray {
        val envelopeBlock = BinaryWriter(512).apply {
            u8(envelopes.size)
            for (envelope in envelopes) {
                lenPrefixedU16(envelope.toBytes(), KeyEnvelope.MAX_BYTES)
            }
        }.toByteArray()
        require(envelopeBlock.size <= MAX_HEADER_BYTES) { "headers too large" }
        return BinaryWriter(HEADER_FIXED_BYTES + envelopeBlock.size).apply {
            bytes(MAGIC.toByteArray(Charsets.UTF_8))
            u8(FORMAT_VERSION)
            u8(mode)
            u8(flags)
            u8(0) // reserved
            u64(exportedAt)
            u32(itemCount.toLong())
            u32(folderCount.toLong())
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
    val mode: Int,
    val exportedAt: Long,
    val itemCount: Int,
    val folderCount: Int,
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
        val decoded = BackupFormat.decodePayload(plaintext, itemCount, folderCount) ?: return null
        return decoded.copy(exportedAt = exportedAt)
    }
}
