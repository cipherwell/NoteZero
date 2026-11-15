package com.cipherwell.notezero.crypto

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
 */
data class KeyEnvelope(
    val type: EnvelopeType,
    val version: Int = VERSION,
    val kdf: KdfId = KdfId.PBKDF2_HMAC_SHA256,
    val iterations: Int,
    val salt: ByteArray,
    val wrappedVmk: ByteArray,
) {
    fun toBytes(): ByteArray = TODO("crypto-core: implement")

    companion object {
        const val VERSION = 1
        const val MAGIC = "NZEN"
        const val MAX_BYTES = 1024

        /** Returns null for unknown magic/version/kdf or truncated input. */
        fun fromBytes(bytes: ByteArray): KeyEnvelope? = TODO("crypto-core: implement")
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
