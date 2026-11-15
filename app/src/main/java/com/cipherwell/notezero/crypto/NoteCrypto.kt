package com.cipherwell.notezero.crypto

import javax.crypto.SecretKey

/**
 * NoteZero cryptographic core — CONTRACT ONLY at bootstrap. The implementation is
 * ported from VaultZero's audited `VaultCrypto` module, renamed to this package and
 * re-tested with independent vectors. See docs/CRYPTO-SPEC.md.
 *
 * Primitives (do not change without a crypto spec revision):
 *  - AEAD: AES-256-GCM, 96-bit random IV per encryption, 128-bit tag.
 *  - KDF: PBKDF2-HMAC-SHA256, 600,000 iterations for NEW envelopes, 128-bit salts.
 *  - VMK: 32 random bytes from SecureRandom; never derived from a password.
 *  - Every AES-GCM use binds an AAD string (see [CryptoAad]); swapping ciphertext
 *    between item ids / types / vaults must fail authentication.
 *
 * Binary blob layout for [encrypt]/[wrapVmk]: iv (12) || ciphertext || tag (16).
 *
 * IMPORTANT: this file must stay pure JVM (no android.* imports) so the crypto
 * tests run as ordinary unit tests without Robolectric.
 */
object NoteCrypto {
    const val VMK_LENGTH_BYTES = 32
    const val GCM_IV_LENGTH_BYTES = 12
    const val GCM_TAG_LENGTH_BITS = 128
    const val GCM_TAG_LENGTH_BYTES = 16
    const val SALT_LENGTH_BYTES = 16
    const val DEFAULT_ITERATIONS = 600_000
    const val MIN_ACCEPTED_ITERATIONS = 10_000

    /**
     * PBKDF2-HMAC-SHA256 over the UTF-8 password. Callers must wipe the returned key
     * bytes when done. [password] is consumed, not retained.
     */
    fun deriveKek(password: CharArray, salt: ByteArray, iterations: Int = DEFAULT_ITERATIONS): SecretKey =
        TODO("crypto-core: implement")

    /**
     * Recovery-phrase KDF. Phrase words are BIP-39 English, lowercased and normalized
     * with exactly one space between them, then used as the PBKDF2 password.
     */
    fun deriveKekFromPhrase(phrase: List<String>, salt: ByteArray, iterations: Int = DEFAULT_ITERATIONS): SecretKey =
        TODO("crypto-core: implement")

    /** Wraps the VMK under a KEK. Result = iv || ciphertext || tag. */
    fun wrapVmk(vmk: ByteArray, kek: SecretKey, aad: ByteArray): ByteArray = TODO("crypto-core: implement")

    /** Unwraps the VMK; returns null on wrong KEK/AAD/tampering (never throws AEADBadTag). */
    fun unwrapVmk(wrappedVmk: ByteArray, kek: SecretKey, aad: ByteArray): ByteArray? =
        TODO("crypto-core: implement")

    /** AES-256-GCM encrypt. Result = iv || ciphertext || tag. */
    fun encrypt(key: SecretKey, plaintext: ByteArray, aad: ByteArray): ByteArray = TODO("crypto-core: implement")

    /** AES-256-GCM decrypt; returns null on any authentication failure. */
    fun decrypt(key: SecretKey, blob: ByteArray, aad: ByteArray): ByteArray? = TODO("crypto-core: implement")

    /** Builds an AES key from raw VMK bytes (32). */
    fun secretKey(keyBytes: ByteArray): SecretKey = TODO("crypto-core: implement")

    fun randomBytes(size: Int): ByteArray = TODO("crypto-core: implement")

    fun randomVmk(): ByteArray = TODO("crypto-core: implement")

    fun sha256(data: ByteArray): ByteArray = TODO("crypto-core: implement")

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = TODO("crypto-core: implement")

    /** Constant-time comparison; length leak is acceptable, content timing is not. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = TODO("crypto-core: implement")

    /** Best-effort zeroization of key material (arrays are overwritten in place). */
    fun wipe(vararg arrays: ByteArray): Unit = TODO("crypto-core: implement")

    /** Best-effort zeroization of password characters. */
    fun wipe(chars: CharArray): Unit = TODO("crypto-core: implement")
}

/**
 * AAD domain-separation strings. Every change is a breaking crypto-spec change:
 * bump the version suffix, never edit a published value.
 */
object CryptoAad {
    const val VMK_WRAP = "notezero.vmk.v1"
    const val RECOVERY_WRAP = "notezero.recovery.v1"
    const val BACKUP = "notezero.backup.v1"

    /** Binds ciphertext to the item id AND its type, per the security requirements. */
    fun item(itemId: String, kind: String): ByteArray = "notezero.item.v1|$itemId|$kind".toByteArray(Charsets.UTF_8)
}
