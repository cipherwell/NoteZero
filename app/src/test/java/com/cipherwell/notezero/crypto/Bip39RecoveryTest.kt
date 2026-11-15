package com.cipherwell.notezero.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest
import java.text.Normalizer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * BIP-39 (English) tests against the official Trezor vectors
 * (https://github.com/trezor/python-mnemonic/blob/master/vectors.json, passphrase "TREZOR")
 * and the canonical wordlist:
 * https://github.com/bitcoin/bips/blob/master/bip-0039/english.txt
 *
 * NoteZero itself derives recovery KEKs with PBKDF2-HMAC-SHA256 over the canonical phrase
 * (see NoteCrypto.deriveKekFromPhrase). The official BIP-39 *seed* (PBKDF2-HMAC-SHA512) is
 * computed here only as an independent check that [Bip39Recovery.canonicalPhrase] emits the
 * exact official mnemonic string, byte for byte.
 */
class Bip39RecoveryTest {

    private data class OfficialVector(
        val entropyHex: String,
        val mnemonic: String,
        val seedHex: String,
    )

    // The eight official English vectors that are 12-word / 128-bit phrases.
    private val officialVectors = listOf(
        OfficialVector(
            "00000000000000000000000000000000",
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
        ),
        OfficialVector(
            "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f",
            "legal winner thank year wave sausage worth useful legal winner thank yellow",
            "2e8905819b8723fe2c1d161860e5ee1830318dbf49a83bd451cfb8440c28bd6fa457fe1296106559a3c80937a1c1069be3a3a5bd381ee6260e8d9739fce1f607",
        ),
        OfficialVector(
            "80808080808080808080808080808080",
            "letter advice cage absurd amount doctor acoustic avoid letter advice cage above",
            "d71de856f81a8acc65e6fc851a38d4d7ec216fd0796d0a6827a3ad6ed5511a30fa280f12eb2e47ed2ac03b5c462a0358d18d69fe4f985ec81778c1b370b652a8",
        ),
        OfficialVector(
            "ffffffffffffffffffffffffffffffff",
            "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
            "ac27495480225222079d7be181583751e86f571027b0497b5b5d11218e0a8a13332572917f0f8e5a589620c6f15b11c61dee327651a14c34e18231052e48c069",
        ),
        OfficialVector(
            "9e885d952ad362caeb4efe34a8e91bd2",
            "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic",
            "274ddc525802f7c828d8ef7ddbcdc5304e87ac3535913611fbbfa986d0c9e5476c91689f9c8a54fd55bd38606aa6a8595ad213d4c9c9f9aca3fb217069a41028",
        ),
        OfficialVector(
            "c0ba5a8e914111210f2bd131f3d5e08d",
            "scheme spot photo card baby mountain device kick cradle pact join borrow",
            "ea725895aaae8d4c1cf682c1bfd2d358d52ed9f0f0591131b559e2724bb234fca05aa9c02c57407e04ee9dc3b454aa63fbff483a8b11de949624b9f1831a9612",
        ),
        OfficialVector(
            "23db8160a31d3e0dca3688ed941adbf3",
            "cat swing flag economy stadium alone churn speed unique patch report train",
            "deb5f45449e615feff5640f2e49f933ff51895de3b4381832b3139941c57b59205a42480c52175b6efcffaa58a2503887c1e8b363a707256bdd2b587b46541f5",
        ),
        OfficialVector(
            "f30f8c1da665478f49b001d94c5fc452",
            "vessel ladder alter error federal sibling chat ability sun glass valve picture",
            "2aaa9242daafcee6aa9d7269f17d4efe271e1b9a529178d7dc139cd18747090bf9d60295d0ce74309a78852a9caadf0af48aae1c6253839624076224374bc63f",
        ),
    )

    private fun hex(text: String): ByteArray = requireNotNull(Encoding.fromHex(text)) { "bad fixture" }

    // ---------------------------------------------------------------- official vectors

    @Test
    fun `entropyToPhrase matches all official 12-word vectors`() {
        for (vector in officialVectors) {
            val entropy = hex(vector.entropyHex)
            val words = Bip39Recovery.entropyToPhrase(entropy)
            assertEquals(vector.mnemonic, words.joinToString(" "))
            assertEquals(Bip39Recovery.WORD_COUNT, words.size)
        }
    }

    @Test
    fun `phraseToEntropy recovers the official entropy and validates the phrase`() {
        for (vector in officialVectors) {
            val words = vector.mnemonic.split(" ")
            assertTrue("vector must validate: ${vector.entropyHex}", Bip39Recovery.validatePhrase(words))
            val recovered = requireNotNull(Bip39Recovery.phraseToEntropy(words)) {
                "official vector must decode: ${vector.entropyHex}"
            }
            assertArrayEquals(hex(vector.entropyHex), recovered)
            assertEquals(Bip39Recovery.ENTROPY_BYTES, recovered.size)
        }
    }

    @Test
    fun `canonical phrase reproduces the official BIP-39 seed`() {
        // Independent oracle: BIP-39 seed = PBKDF2-HMAC-SHA512(mnemonic, "mnemonic"+passphrase,
        // 2048, 64). The Trezor vectors use passphrase "TREZOR". If canonicalPhrase() does not
        // emit the exact official NFKD mnemonic string, these seeds cannot match.
        for (vector in officialVectors.take(4)) {
            val words = Bip39Recovery.canonicalPhrase(vector.mnemonic.split(" ")).split(" ")
            assertEquals(vector.mnemonic, words.joinToString(" "))
            val seed = pbkdf2Sha512(
                password = Bip39Recovery.canonicalPhrase(vector.mnemonic.split(" ")),
                salt = "mnemonicTREZOR",
                iterations = 2_048,
            )
            assertEquals(vector.seedHex, Encoding.toHex(seed))
        }
    }

    @Test
    fun `all-zero entropy produces the published abandon-about phrase`() {
        assertEquals(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            Bip39Recovery.entropyToPhrase(ByteArray(16)).joinToString(" "),
        )
    }

    // ---------------------------------------------------------------- wordlist integrity

    @Test
    fun `wordlist is the canonical 2048-word English list`() {
        val words = Bip39Recovery.WORDLIST
        assertEquals(2048, words.size)
        assertEquals(2048, words.distinct().size)
        assertEquals("sorted", words.sorted(), words)
        assertEquals("abandon", words.first())
        assertEquals("zoo", words.last())
        assertTrue("all words lowercase ascii", words.all { word -> word.all { it in 'a'..'z' } })

        // SHA-256 of the official english.txt (newline-separated, trailing newline).
        val joined = words.joinToString("\n") + "\n"
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        assertEquals(
            "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda",
            Encoding.toHex(digest),
        )
    }

    // ---------------------------------------------------------------- validation rules

    @Test
    fun `validatePhrase rejects wrong word counts`() {
        val words = officialVectors[1].mnemonic.split(" ")
        assertTrue(Bip39Recovery.validatePhrase(words))
        assertFalse("11 words", Bip39Recovery.validatePhrase(words.dropLast(1)))
        assertFalse("13 words", Bip39Recovery.validatePhrase(words + "abandon"))
        assertFalse("empty", Bip39Recovery.validatePhrase(emptyList()))
    }

    @Test
    fun `validatePhrase rejects unknown words and tampered checksums`() {
        val words = officialVectors[1].mnemonic.split(" ")
        val unknown = words.toMutableList().also { it[3] = "notaword" }
        assertFalse("unknown word", Bip39Recovery.validatePhrase(unknown))

        // Any other known word is a 1/16 chance of a valid checksum; pick one that is not.
        val original = words[11]
        val replacement = Bip39Recovery.WORDLIST.first { candidate ->
            candidate != original &&
                !Bip39Recovery.validatePhrase(words.dropLast(1) + candidate)
        }
        val tampered = words.dropLast(1) + replacement
        assertFalse("single-word transcription error must fail checksum", Bip39Recovery.validatePhrase(tampered))
        assertNull(Bip39Recovery.phraseToEntropy(tampered))
    }

    @Test
    fun `validatePhrase accepts duplicate-word phrases for legacy compatibility`() {
        // The official all-zero vector repeats "abandon" eleven times; BIP-39 permits repeats.
        val repeated = officialVectors[0].mnemonic.split(" ")
        assertEquals(1, repeated.count { it != "abandon" })
        assertTrue(Bip39Recovery.validatePhrase(repeated))
    }

    @Test
    fun `validatePhrase is case insensitive and trims whitespace`() {
        val words = officialVectors[2].mnemonic.split(" ").mapIndexed { index, word ->
            if (index % 2 == 0) "  ${word.uppercase()}  " else word
        }
        assertTrue(Bip39Recovery.validatePhrase(words))
    }

    @Test
    fun `entropyToPhrase rejects non-128-bit entropy`() {
        for (size in intArrayOf(0, 15, 17, 32)) {
            try {
                Bip39Recovery.entropyToPhrase(ByteArray(size))
                fail("expected IllegalArgumentException for $size-byte entropy")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    // ---------------------------------------------------------------- generation + canonical form

    @Test
    fun `generatePhrase returns 12 unique valid words with a valid checksum`() {
        repeat(100) {
            val phrase = Bip39Recovery.generatePhrase()
            assertEquals(Bip39Recovery.WORD_COUNT, phrase.size)
            assertEquals("unique words", Bip39Recovery.WORD_COUNT, phrase.distinct().size)
            assertTrue("all words in wordlist", phrase.all { word -> word in Bip39Recovery.WORDLIST })
            assertTrue("checksum valid", Bip39Recovery.validatePhrase(phrase))
            assertEquals(Bip39Recovery.ENTROPY_BYTES, Bip39Recovery.phraseToEntropy(phrase)!!.size)
        }
    }

    @Test
    fun `generatePhrase does not repeat across calls`() {
        assertNotEquals(Bip39Recovery.generatePhrase(), Bip39Recovery.generatePhrase())
    }

    @Test
    fun `canonicalPhrase lowercases normalizes NFKD and collapses to single spaces`() {
        assertEquals(
            "abandon ability able",
            Bip39Recovery.canonicalPhrase(listOf("  Abandon ", "ABILITY", "", " able  ")),
        )
        val composed = "caf\u00e9" // café
        val decomposed = "cafe\u0301" // café as NFKD
        assertEquals(
            Normalizer.normalize(composed, Normalizer.Form.NFKD),
            Bip39Recovery.canonicalPhrase(listOf(composed)),
        )
        assertEquals(
            Bip39Recovery.canonicalPhrase(listOf(composed)),
            Bip39Recovery.canonicalPhrase(listOf(decomposed)),
        )
        assertEquals("", Bip39Recovery.canonicalPhrase(emptyList()))
    }

    // ---------------------------------------------------------------- helpers

    private fun pbkdf2Sha512(password: String, salt: String, iterations: Int): ByteArray {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt.toByteArray(Charsets.UTF_8), iterations, 512)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
            NoteCrypto.wipe(chars)
        }
    }
}
