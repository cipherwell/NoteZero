package com.cipherwell.notezero.data

import com.cipherwell.notezero.crypto.CryptoAad
import com.cipherwell.notezero.crypto.EnvelopeType
import com.cipherwell.notezero.crypto.KeyEnvelope
import com.cipherwell.notezero.crypto.NoteCrypto
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Frozen **format-v1** backup fixture writer used by the v1-compatibility tests.
 *
 * It deliberately re-implements the published v1 wire layout (docs/BACKUP-FORMAT.md v1)
 * instead of calling [com.cipherwell.notezero.backup.BackupFormat.write] (which now writes
 * v2), so the regression tests prove that a real pre-attachments file still imports. The
 * decrypted payload uses the ItemCodec **version-1** layout (no attachment list).
 */
internal object LegacyBackupFixture {

    private const val MAGIC = "NZBK"
    private const val MODE_EXTRA_PASSWORD = 1
    private const val FLAG_PASSWORD_ENVELOPE = 0x01

    /** Builds a v1 extra-password backup holding [items]/[folders], importable with [backupPassword]. */
    fun writeV1(
        sourceVmk: ByteArray,
        backupPassword: CharArray,
        items: List<Item>,
        folders: List<Folder> = emptyList(),
        exportedAt: Long = 1_700_000_000_000L,
    ): ByteArray {
        val iterations = NoteCrypto.MIN_ACCEPTED_ITERATIONS
        val salt = NoteCrypto.randomBytes(NoteCrypto.SALT_LENGTH_BYTES)
        val envelope = KeyEnvelope(
            type = EnvelopeType.PASSWORD,
            iterations = iterations,
            salt = salt,
            wrappedVmk = NoteCrypto.wrapVmk(
                sourceVmk,
                NoteCrypto.deriveKek(backupPassword, salt, iterations),
                CryptoAad.BACKUP.toByteArray(Charsets.UTF_8),
            ),
        ).toBytes()

        val envelopeBlock = BinaryWriter(64).apply {
            u8(1)
            lenPrefixedU16(envelope, KeyEnvelope.MAX_BYTES)
        }.toByteArray()

        // v1 fixed header: 28 bytes (no attachmentCount field).
        val header = BinaryWriter(28 + envelopeBlock.size).apply {
            bytes(MAGIC.toByteArray(Charsets.UTF_8))
            u8(1) // formatVersion = 1
            u8(MODE_EXTRA_PASSWORD)
            u8(FLAG_PASSWORD_ENVELOPE)
            u8(0) // reserved
            u64(exportedAt)
            u32(items.size.toLong())
            u32(folders.size.toLong())
            u32(envelopeBlock.size.toLong())
            bytes(envelopeBlock)
        }.toByteArray()

        val payload = BinaryWriter(1024).apply {
            u8(1) // payloadVersion = 1
            u32(items.size.toLong())
            u32(folders.size.toLong())
            for (item in items) {
                require(item.attachments.isEmpty()) { "v1 backups cannot carry attachments" }
                lenPrefixedU32(legacyV1ItemPayload(item), ItemCodec.MAX_PAYLOAD_BYTES)
            }
            for (folder in folders) {
                lenPrefixedU32(FolderCodec.encode(folder), FolderCodec.MAX_PAYLOAD_BYTES)
            }
        }.toByteArray()

        val aad = CryptoAad.BACKUP.toByteArray(Charsets.UTF_8) + header
        val sealed = NoteCrypto.encrypt(NoteCrypto.secretKey(sourceVmk), payload, aad)
        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(
            byteArrayOf(
                ((sealed.size ushr 24) and 0xFF).toByte(),
                ((sealed.size ushr 16) and 0xFF).toByte(),
                ((sealed.size ushr 8) and 0xFF).toByte(),
                (sealed.size and 0xFF).toByte(),
            ),
        )
        out.write(sealed)
        return out.toByteArray()
    }

    /**
     * Rebuilds the ItemCodec version-1 payload from a v2 encoding: drop the appended u16
     * attachment count (always 0 for fixture items) and set the envelope version byte to 1.
     */
    private fun legacyV1ItemPayload(item: Item): ByteArray {
        val encoded = ItemCodec.encode(item)
        val withoutAttachmentList = encoded.copyOf(encoded.size - 2)
        withoutAttachmentList[4] = 1
        return withoutAttachmentList
    }

    /** Writes [bytes] to an [OutputStream] with the same semantics as a SAF stream. */
    fun writeTo(out: OutputStream, bytes: ByteArray) {
        out.write(bytes)
        out.flush()
    }
}
