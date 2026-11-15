package com.cipherwell.notezero.crypto

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * NoteZero cryptographic core. Ported from VaultZero's audited `VaultCrypto` module,
 * renamed to this package and re-tested with independent published vectors.
 * See docs/CRYPTO-SPEC.md.
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
 * All methods are thread-safe (each call builds its own Cipher/Mac instances).
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

    private const val KEY_LENGTH_BITS = 256
    private const val KEY_ALGORITHM = "AES"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val AES_CIPHER = "AES/GCM/NoPadding"
    private const val SHA_256 = "SHA-256"

    /** Smallest blob [decrypt] can ever accept: one IV plus one GCM tag, no ciphertext. */
    private const val MIN_BLOB_BYTES = GCM_IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES

    private val secureRandom = SecureRandom()

    /**
     * PBKDF2-HMAC-SHA256 over the raw password characters (UTF-16 -> PBEKeySpec; the
     * provider encodes with UTF-8 for ASCII, matching RFC 7914 test vectors).
     * [password] is consumed, not retained: this method wipes it.
     */
    fun deriveKek(password: CharArray, salt: ByteArray, iterations: Int = DEFAULT_ITERATIONS): SecretKey {
        require(password.isNotEmpty()) { "Password must not be empty" }
        require(salt.size >= SALT_LENGTH_BYTES) { "Salt must be at least $SALT_LENGTH_BYTES bytes" }
        require(iterations >= MIN_ACCEPTED_ITERATIONS) {
            "PBKDF2 iterations must be at least $MIN_ACCEPTED_ITERATIONS"
        }
        val keyBytes = pbkdf2Raw(password, salt, iterations)
        try {
            return SecretKeySpec(keyBytes, KEY_ALGORITHM)
        } finally {
            wipe(keyBytes)
            wipe(password)
        }
    }

    /**
     * Raw PBKDF2-HMAC-SHA256 output (32 bytes) without the production iteration floor.
     *
     * Exists so the RFC 7914 §11 known-answer vectors (c=1) can be exercised directly; all
     * public entry points enforce [MIN_ACCEPTED_ITERATIONS]. Consumes [password].
     */
    internal fun pbkdf2Raw(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray {
        require(password.isNotEmpty()) { "Password must not be empty" }
        require(salt.isNotEmpty()) { "Salt must not be empty" }
        require(iterations > 0) { "PBKDF2 iterations must be positive" }
        val spec = PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS)
        try {
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            val secret = try {
                factory.generateSecret(spec).encoded
            } catch (e: GeneralSecurityException) {
                throw IllegalStateException("PBKDF2-HMAC-SHA256 unavailable", e)
            }
            try {
                return secret.copyOf()
            } finally {
                wipe(secret)
            }
        } finally {
            spec.clearPassword()
            wipe(password)
        }
    }

    /**
     * Recovery-phrase KDF. The canonical phrase (lowercase, NFKD, single spaces) is the
     * PBKDF2 password. Domain-separated from password KEKs by the [CryptoAad] wrap AAD,
     * not by the KDF itself.
     */
    fun deriveKekFromPhrase(
        phrase: List<String>,
        salt: ByteArray,
        iterations: Int = DEFAULT_ITERATIONS,
    ): SecretKey {
        require(phrase.isNotEmpty()) { "Recovery phrase must not be empty" }
        val chars = Bip39Recovery.canonicalPhrase(phrase).toCharArray()
        try {
            return deriveKek(chars, salt, iterations)
        } finally {
            wipe(chars)
        }
    }

    /**
     * Wraps the VMK under a KEK. Result = iv || ciphertext || tag.
     * [vmk] must be exactly [VMK_LENGTH_BYTES] bytes; [aad] is a domain-separation string.
     */
    fun wrapVmk(vmk: ByteArray, kek: SecretKey, aad: ByteArray): ByteArray {
        require(vmk.size == VMK_LENGTH_BYTES) { "VMK must be $VMK_LENGTH_BYTES bytes" }
        return encrypt(kek, vmk, aad)
    }

    /**
     * Unwraps the VMK; returns null on wrong KEK/AAD/tampering/wrong length
     * (never throws AEADBadTagException).
     */
    fun unwrapVmk(wrappedVmk: ByteArray, kek: SecretKey, aad: ByteArray): ByteArray? {
        val vmk = decrypt(kek, wrappedVmk, aad) ?: return null
        if (vmk.size != VMK_LENGTH_BYTES) {
            wipe(vmk)
            return null
        }
        return vmk
    }

    /** AES-256-GCM encrypt. Result = iv || ciphertext || tag. */
    fun encrypt(key: SecretKey, plaintext: ByteArray, aad: ByteArray): ByteArray {
        return sealWithIv(key, plaintext, aad, randomBytes(GCM_IV_LENGTH_BYTES))
    }

    /**
     * AES-256-GCM decrypt; returns null on any authentication failure or malformed blob
     * (short payload, tampered IV/ciphertext/tag, wrong key or wrong AAD).
     */
    fun decrypt(key: SecretKey, blob: ByteArray, aad: ByteArray): ByteArray? {
        validateAes256Key(key)
        if (blob.size < MIN_BLOB_BYTES) return null
        return try {
            val iv = blob.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val ciphertextWithTag = blob.copyOfRange(GCM_IV_LENGTH_BYTES, blob.size)
            val cipher = Cipher.getInstance(AES_CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertextWithTag)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Builds an AES-256 key from raw 32-byte VMK bytes. */
    fun secretKey(keyBytes: ByteArray): SecretKey {
        require(keyBytes.size == VMK_LENGTH_BYTES) {
            "AES-256 key must be $VMK_LENGTH_BYTES bytes, got ${keyBytes.size}"
        }
        return SecretKeySpec(keyBytes.copyOf(), KEY_ALGORITHM)
    }

    fun randomBytes(size: Int): ByteArray {
        require(size > 0) { "Requested random size must be positive" }
        val out = ByteArray(size)
        secureRandom.nextBytes(out)
        return out
    }

    fun randomVmk(): ByteArray = randomBytes(VMK_LENGTH_BYTES)

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance(SHA_256).digest(data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        require(key.isNotEmpty()) { "HMAC key must not be empty" }
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(data)
    }

    /**
     * Constant-time comparison for identical-length byte arrays; length leak is acceptable,
     * content timing is not. Delegates to the JDK's constant-time implementation.
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /** Best-effort zeroization of key material (arrays are overwritten in place). */
    fun wipe(vararg arrays: ByteArray) {
        for (array in arrays) Arrays.fill(array, 0)
    }

    /** Best-effort zeroization of password characters. */
    fun wipe(chars: CharArray) {
        Arrays.fill(chars, '\u0000')
    }

    /**
     * AES-GCM with a caller-supplied IV. Only tests and deterministic KATs should use this:
     * production encryption always draws a fresh random IV through [encrypt].
     * Returns iv || ciphertext || tag.
     */
    internal fun sealWithIv(
        key: SecretKey,
        plaintext: ByteArray,
        aad: ByteArray,
        iv: ByteArray,
    ): ByteArray {
        require(iv.size == GCM_IV_LENGTH_BYTES) { "GCM IV must be $GCM_IV_LENGTH_BYTES bytes" }
        validateAes256Key(key)
        return try {
            val cipher = Cipher.getInstance(AES_CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            cipher.updateAAD(aad)
            val ciphertextWithTag = cipher.doFinal(plaintext)
            val out = ByteArray(iv.size + ciphertextWithTag.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(ciphertextWithTag, 0, out, iv.size, ciphertextWithTag.size)
            out
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("AES-256-GCM encryption unavailable", e)
        }
    }

    private fun validateAes256Key(key: SecretKey) {
        val encoded = key.encoded
        require(encoded != null && encoded.size == VMK_LENGTH_BYTES) {
            "AES-256 keys must expose $VMK_LENGTH_BYTES raw bytes"
        }
        wipe(encoded)
    }
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
