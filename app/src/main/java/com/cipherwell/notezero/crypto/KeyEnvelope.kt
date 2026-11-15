package com.cipherwell.notezero.crypto

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * Versioned key envelope: a VMK wrapped under a password-derived or recovery-phrase-derived
 * KEK. Stored in the vault meta table; the wrapped VMK is useless without the KEK.
 *
 * Binary format (all integers big-endian):
 *
 * ```
 * magic        4 bytes  "NZEN"
 * type         1 byte   0 = PASSWORD, 1 = RECOVERY
 * version      1 byte   1
 * kdf          1 byte   1 = PBKDF2-HMAC-SHA256
 * iterations   4 bytes  u32
 * saltLen      1 byte   currently 16
 * salt         saltLen bytes
 * wrappedLen   2 bytes  u16
 * wrapped      wrappedLen bytes  (iv || ct || tag from NoteCrypto.wrapVmk)
 * ```
 *
 * Parsing is deliberately strict: unknown magic/version/kdf, impossible lengths, weak
 * iteration counts or trailing bytes are all rejected with `null` rather than partially
 * trusted. Callers treat `null` as "this envelope is unusable".
 */
data class KeyEnvelope(
    val type: EnvelopeType,
    val version: Int = VERSION,
    val kdf: KdfId = KdfId.PBKDF2_HMAC_SHA256,
    val iterations: Int,
    val salt: ByteArray,
    val wrappedVmk: ByteArray,
) {
    /** Serializes to the canonical byte layout documented above. */
    fun toBytes(): ByteArray {
        require(version == VERSION) { "Only envelope version $VERSION can be written" }
        require(kdf == KdfId.PBKDF2_HMAC_SHA256) { "Only PBKDF2 envelopes can be written" }
        require(salt.size == NoteCrypto.SALT_LENGTH_BYTES) {
            "Salt must be ${NoteCrypto.SALT_LENGTH_BYTES} bytes for PBKDF2 envelopes"
        }
        require(iterations in NoteCrypto.MIN_ACCEPTED_ITERATIONS..MAX_ITERATIONS) {
            "Iterations must be in ${NoteCrypto.MIN_ACCEPTED_ITERATIONS}..$MAX_ITERATIONS"
        }
        require(wrappedVmk.size >= MIN_WRAPPED_BYTES) {
            "Wrapped VMK must include at least an IV and a GCM tag"
        }
        require(wrappedVmk.size <= MAX_WRAPPED_BYTES) { "Wrapped VMK is too large" }

        val buffer = ByteBuffer.allocate(headerBytes() + wrappedVmk.size)
        buffer.put(MAGIC.toByteArray(Charsets.US_ASCII)) // 4
        buffer.put(type.id.toByte()) // 1
        buffer.put(version.toByte()) // 1
        buffer.put(kdf.id.toByte()) // 1
        buffer.putInt(iterations) // 4
        buffer.put(salt.size.toByte()) // 1
        buffer.put(salt) // saltLen
        buffer.putShort(wrappedVmk.size.toShort()) // 2
        buffer.put(wrappedVmk) // wrappedLen
        return buffer.array()
    }

    companion object {
        const val VERSION = 1
        const val MAGIC = "NZEN"
        const val MAX_BYTES = 1024

        /** Upper bound accepted on read/write so a hostile row cannot burn unbounded PBKDF2. */
        const val MAX_ITERATIONS = 10_000_000

        private const val MAGIC_OFFSET = 0
        private const val TYPE_OFFSET = 4
        private const val VERSION_OFFSET = 5
        private const val KDF_OFFSET = 6
        private const val ITERATIONS_OFFSET = 7
        private const val SALT_LENGTH_OFFSET = 11
        private const val SALT_OFFSET = 12
        private const val WRAPPED_LENGTH_OFFSET = SALT_OFFSET + NoteCrypto.SALT_LENGTH_BYTES
        private const val WRAPPED_OFFSET = WRAPPED_LENGTH_OFFSET + 2

        /** magic(4) + type(1) + version(1) + kdf(1) + iterations(4) + saltLen(1) + salt(16) + wrappedLen(2). */
        private const val HEADER_BYTES = WRAPPED_OFFSET

        private const val MIN_WRAPPED_BYTES =
            NoteCrypto.GCM_IV_LENGTH_BYTES + NoteCrypto.GCM_TAG_LENGTH_BYTES
        private const val MAX_WRAPPED_BYTES = MAX_BYTES - HEADER_BYTES

        private fun headerBytes(): Int = HEADER_BYTES

        /** Returns null for unknown magic/version/kdf, weak/absurd parameters or truncated input. */
        fun fromBytes(bytes: ByteArray): KeyEnvelope? {
            if (bytes.size < HEADER_BYTES || bytes.size > MAX_BYTES) return null
            return try {
                val buffer = ByteBuffer.wrap(bytes)
                val magic = ByteArray(4)
                buffer.get(magic)
                if (String(magic, Charsets.US_ASCII) != MAGIC) return null

                val type = EnvelopeType.fromId(buffer.get().toInt() and 0xFF) ?: return null
                val version = buffer.get().toInt() and 0xFF
                if (version != VERSION) return null
                val kdf = KdfId.fromId(buffer.get().toInt() and 0xFF) ?: return null
                val iterations = buffer.getInt()
                if (iterations < NoteCrypto.MIN_ACCEPTED_ITERATIONS || iterations > MAX_ITERATIONS) {
                    return null
                }
                val saltLength = buffer.get().toInt() and 0xFF
                if (saltLength != NoteCrypto.SALT_LENGTH_BYTES) return null
                if (buffer.remaining() < saltLength + 2) return null
                val salt = ByteArray(saltLength)
                buffer.get(salt)
                val wrappedLength = buffer.getShort().toInt() and 0xFFFF
                if (wrappedLength < MIN_WRAPPED_BYTES || wrappedLength > MAX_WRAPPED_BYTES) {
                    return null
                }
                if (buffer.remaining() != wrappedLength) return null
                val wrapped = ByteArray(wrappedLength)
                buffer.get(wrapped)
                KeyEnvelope(
                    type = type,
                    version = version,
                    kdf = kdf,
                    iterations = iterations,
                    salt = salt,
                    wrappedVmk = wrapped,
                )
            } catch (e: BufferUnderflowException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

enum class EnvelopeType(val id: Int) {
    PASSWORD(0),
    RECOVERY(1);

    companion object {
        fun fromId(id: Int): EnvelopeType? = entries.firstOrNull { it.id == id }
    }
}

enum class KdfId(val id: Int) {
    PBKDF2_HMAC_SHA256(1);

    companion object {
        fun fromId(id: Int): KdfId? = entries.firstOrNull { it.id == id }
    }
}
