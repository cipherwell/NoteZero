package com.cipherwell.notezero.crypto

import java.security.SecureRandom

/**
 * BIP-39 (English) recovery phrase support — CONTRACT ONLY at bootstrap.
 *
 * NoteZero uses a 12-word phrase = 128 bits entropy + 4-bit BIP-39 checksum.
 * New phrases are generated with UNIQUE words (BIP-39 allows repeats; rejecting them
 * avoids a ~3% confusing-phrase rate). Validation accepts any phrase whose checksum
 * and words are valid, so legacy VaultZero phrases remain recoverable.
 *
 * Pure JVM: the 2048-word list is embedded, no android.* dependency.
 */
object Bip39Recovery {
    const val WORD_COUNT = 12
    const val ENTROPY_BYTES = 16
    const val CHECKSUM_BITS = 4

    /** The standard BIP-39 English wordlist (2048 entries, lowercase). */
    val WORDLIST: List<String> = TODO("crypto-core: embed BIP-39 English list")

    /** Generates a fresh 12-word phrase with unique words. */
    fun generatePhrase(random: SecureRandom = SecureRandom()): List<String> =
        TODO("crypto-core: implement")

    /** Entropy (16 bytes) -> 12 words including checksum bits. */
    fun entropyToPhrase(entropy: ByteArray): List<String> = TODO("crypto-core: implement")

    /** Valid words + valid checksum + correct length; case-insensitive input. */
    fun validatePhrase(phrase: List<String>): Boolean = TODO("crypto-core: implement")

    /** Returns the 16 entropy bytes, or null when the phrase is invalid. */
    fun phraseToEntropy(phrase: List<String>): ByteArray? = TODO("crypto-core: implement")

    /** Canonical PBKDF2 input: lowercase, NFKD-normalized, single spaces. */
    fun canonicalPhrase(phrase: List<String>): String = TODO("crypto-core: implement")
}
