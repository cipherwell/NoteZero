package com.cipherwell.notezero.data

import com.cipherwell.notezero.crypto.CryptoAad
import com.cipherwell.notezero.crypto.Encoding
import com.cipherwell.notezero.crypto.NoteCrypto
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.crypto.SecretKey

/**
 * On-disk format of one encrypted attachment blob (`filesDir/attachments/<id>.nzb`).
 *
 * The file contains **only** ciphertext: a 5-byte header (magic `NZAT` + format version)
 * followed by the standard AES-256-GCM envelope (`iv || ciphertext || tag`) under the
 * vault VMK, AAD-bound to the note and attachment ids via
 * [CryptoAad.attachment]. No plaintext bytes, names, thumbnails or sizes are written to
 * disk; the file name is the random attachment id only.
 *
 * This object is pure JVM (unit-tested without Robolectric) so the format and the
 * size/integrity rules are testable independently of Android file IO.
 */
internal object AttachmentBlob {

    /** File magic; also the first 4 bytes of every `.nzb` file. */
    val MAGIC: ByteArray = byteArrayOf(0x4E, 0x5A, 0x41, 0x54) // "NZAT"

    /** Blob format version written by this implementation; readers reject anything else. */
    const val VERSION: Int = 1

    /** magic (4) + format version (1). */
    const val HEADER_BYTES: Int = 5

    /** Smallest possible blob: header + GCM iv/tag with an empty plaintext. */
    const val MIN_BLOB_BYTES: Int =
        HEADER_BYTES + NoteCrypto.GCM_IV_LENGTH_BYTES + NoteCrypto.GCM_TAG_LENGTH_BYTES

    /** Largest possible blob file: header + iv + max plaintext + tag. */
    const val MAX_BLOB_BYTES: Long =
        HEADER_BYTES + NoteCrypto.GCM_IV_LENGTH_BYTES + AttachmentLimits.MAX_ATTACHMENT_BYTES +
            NoteCrypto.GCM_TAG_LENGTH_BYTES

    /**
     * Encrypts [plaintext] into the wire blob (`NZAT` + version + iv||ct||tag).
     * Rejects payloads over [AttachmentLimits.MAX_ATTACHMENT_BYTES] on encode.
     */
    fun encode(plaintext: ByteArray, key: SecretKey, aad: ByteArray): ByteArray {
        require(plaintext.size <= AttachmentLimits.MAX_ATTACHMENT_BYTES) { "attachment too large" }
        val sealed = NoteCrypto.encrypt(key, plaintext, aad)
        val out = ByteArray(HEADER_BYTES + sealed.size)
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.size)
        out[MAGIC.size] = VERSION.toByte()
        System.arraycopy(sealed, 0, out, HEADER_BYTES, sealed.size)
        return out
    }

    /**
     * Authenticates + decrypts a blob file. Returns null for any malformed header, wrong
     * AAD/key, tampering, truncation or oversized file — never throws and never returns
     * unauthenticated bytes.
     */
    fun decode(blob: ByteArray, key: SecretKey, aad: ByteArray): ByteArray? {
        if (blob.size < MIN_BLOB_BYTES || blob.size > MAX_BLOB_BYTES) return null
        for (i in MAGIC.indices) {
            if (blob[i] != MAGIC[i]) return null
        }
        if (blob[MAGIC.size].toInt() != VERSION) return null
        val sealed = blob.copyOfRange(HEADER_BYTES, blob.size)
        val plaintext = NoteCrypto.decrypt(key, sealed, aad) ?: return null
        if (plaintext.size > AttachmentLimits.MAX_ATTACHMENT_BYTES) {
            NoteCrypto.wipe(plaintext)
            return null
        }
        return plaintext
    }

    /** Lowercase hex SHA-256 of the plaintext bytes (what [com.cipherwell.notezero.model.AttachmentRef.sha256] stores). */
    fun sha256Hex(plaintext: ByteArray): String = Encoding.toHex(NoteCrypto.sha256(plaintext))

    /**
     * Reads at most [limit] bytes from [source]. Returns null as soon as the stream would
     * exceed [limit] (the over-limit bytes are never buffered), so a hostile
     * `ContentResolver` stream cannot force an over-sized allocation. IOErrors propagate to
     * the caller, which maps them to `AttachmentError.IO`.
     */
    fun readAtMost(source: InputStream, limit: Long): ByteArray? {
        require(limit >= 0L) { "limit must not be negative" }
        val out = ByteArrayOutputStream(minOf(limit, 64L * 1024L).toInt())
        val chunk = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = source.read(chunk)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > limit) return null
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    /** Strict lowercase-hex SHA-256 shape check (64 chars). */
    fun isSha256Hex(value: String): Boolean =
        value.length == ItemCodec.SHA256_HEX_CHARS &&
            value.all { it in '0'..'9' || it in 'a'..'f' }
}
