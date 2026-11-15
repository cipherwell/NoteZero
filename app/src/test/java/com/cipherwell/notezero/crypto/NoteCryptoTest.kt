package com.cipherwell.notezero.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Arrays

/**
 * Behavioural + known-answer tests for NoteCrypto.
 *
 * Independent oracles (no implementation-derived expectations):
 *  - RFC 7914 §11 PBKDF2-HMAC-SHA256 vectors ("passwd"/"salt"/c=1 and "Password"/"NaCl"/c=80000)
 *    https://www.rfc-editor.org/rfc/rfc7914.txt
 *  - NIST CAVP AES-256-GCM vectors, gcmEncryptExtIV256.rsp (Keylen=256, IVlen=96, Taglen=128)
 *    https://csrc.nist.gov/projects/cryptographic-algorithm-validation-program
 *  - RFC 4231 HMAC-SHA-256 test cases 1 and 2
 *  - FIPS 180-2 SHA-256("abc")
 *
 * Every known-answer vector is written as a hex string literal in this file; the
 * implementation is never used to produce the expected value.
 */
class NoteCryptoTest {

    private fun hex(text: String): ByteArray = requireNotNull(Encoding.fromHex(text)) { "bad test fixture" }

    private fun keyHexHex(keyHex: String) = NoteCrypto.secretKey(hex(keyHex))

    private val fixedSalt = ByteArray(NoteCrypto.SALT_LENGTH_BYTES) { (it + 1).toByte() }

    // ---------------------------------------------------------------- PBKDF2 (RFC 7914 §11)

    @Test
    fun `RFC 7914 PBKDF2-HMAC-SHA256 vector 1 (passwd salt c=1)`() {
        val dk = NoteCrypto.pbkdf2Raw("passwd".toCharArray(), "salt".toByteArray(Charsets.US_ASCII), 1)
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc",
            Encoding.toHex(dk),
        )
    }

    @Test
    fun `RFC 7914 PBKDF2-HMAC-SHA256 vector 2 (Password NaCl c=80000)`() {
        val dk = NoteCrypto.pbkdf2Raw("Password".toCharArray(), "NaCl".toByteArray(Charsets.US_ASCII), 80_000)
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56",
            Encoding.toHex(dk),
        )
    }

    @Test
    fun `deriveKek is deterministic and returns a 32-byte AES key`() {
        val first = NoteCrypto.deriveKek("correct horse battery staple".toCharArray(), fixedSalt, 10_000)
        val second = NoteCrypto.deriveKek("correct horse battery staple".toCharArray(), fixedSalt, 10_000)
        assertEquals("AES", first.algorithm)
        assertEquals(NoteCrypto.VMK_LENGTH_BYTES, first.encoded!!.size)
        assertArrayEquals(first.encoded, second.encoded)
    }

    @Test
    fun `deriveKek depends on password salt and iterations`() {
        val base = NoteCrypto.deriveKek("hunter2-test".toCharArray(), fixedSalt, 10_000).encoded!!
        val otherPassword = NoteCrypto.deriveKek("hunter2-test2".toCharArray(), fixedSalt, 10_000).encoded!!
        val otherSalt = NoteCrypto.deriveKek(
            "hunter2-test".toCharArray(),
            ByteArray(NoteCrypto.SALT_LENGTH_BYTES) { 9 },
            10_000,
        ).encoded!!
        val otherIterations = NoteCrypto.deriveKek("hunter2-test".toCharArray(), fixedSalt, 20_000).encoded!!
        assertFalse(base.contentEquals(otherPassword))
        assertFalse(base.contentEquals(otherSalt))
        assertFalse(base.contentEquals(otherIterations))
    }

    @Test
    fun `deriveKek rejects policy violations`() {
        assertThrowsIllegalArgument { NoteCrypto.deriveKek("pw".toCharArray(), ByteArray(8), 10_000) }
        assertThrowsIllegalArgument { NoteCrypto.deriveKek("pw".toCharArray(), fixedSalt, 9_999) }
        assertThrowsIllegalArgument { NoteCrypto.deriveKek("".toCharArray(), fixedSalt, 10_000) }
    }

    @Test
    fun `deriveKek wipes the caller-supplied password characters`() {
        val password = "hunter2-test".toCharArray()
        NoteCrypto.deriveKek(password, fixedSalt, 10_000)
        assertTrue("password chars must be zeroized after derivation", password.all { it == '\u0000' })
    }

    @Test
    fun `deriveKekFromPhrase matches canonical-phrase password derivation`() {
        val words = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")
        val salt = ByteArray(NoteCrypto.SALT_LENGTH_BYTES) { 3 }
        val fromPhrase = NoteCrypto.deriveKekFromPhrase(words, salt, 10_000)
        val fromPassword = NoteCrypto.deriveKek(
            Bip39Recovery.canonicalPhrase(words).toCharArray(),
            salt,
            10_000,
        )
        assertArrayEquals(fromPassword.encoded, fromPhrase.encoded)
    }

    @Test
    fun `deriveKekFromPhrase is case and whitespace insensitive`() {
        val words = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")
        val messy = words.mapIndexed { index, word ->
            if (index % 2 == 0) "  ${word.uppercase()} " else word.replace("e", "E")
        }
        val salt = ByteArray(NoteCrypto.SALT_LENGTH_BYTES) { 5 }
        val clean = NoteCrypto.deriveKekFromPhrase(words, salt, 10_000)
        val dirty = NoteCrypto.deriveKekFromPhrase(messy, salt, 10_000)
        assertArrayEquals(clean.encoded, dirty.encoded)
    }

    @Test
    fun `deriveKekFromPhrase rejects an empty phrase`() {
        assertThrowsIllegalArgument {
            NoteCrypto.deriveKekFromPhrase(emptyList(), fixedSalt, 10_000)
        }
    }

    // ---------------------------------------------------------------- AES-256-GCM (NIST CAVP)

    @Test
    fun `NIST CAVP AES-256-GCM vector empty plaintext and AAD`() {
        val key = keyHexHex("b52c505a37d78eda5dd34f20c22540ea1b58963cf8e5bf8ffa85f9f2492505b4")
        val iv = hex("516c33929df5a3284ff463d7")
        val sealed = NoteCrypto.sealWithIv(key, ByteArray(0), ByteArray(0), iv)
        assertEquals(
            "516c33929df5a3284ff463d7" + "bdc1ac884d332457a1d2664f168c76f0",
            Encoding.toHex(sealed),
        )
        assertArrayEquals(ByteArray(0), NoteCrypto.decrypt(key, sealed, ByteArray(0)))
    }

    @Test
    fun `NIST CAVP AES-256-GCM vector 16-byte plaintext no AAD`() {
        val key = keyHexHex("31bdadd96698c204aa9ce1448ea94ae1fb4a9a0b3c9d773b51bb1822666b8f22")
        val iv = hex("0d18e06c7c725ac9e362e1ce")
        val plaintext = hex("2db5168e932556f8089a0622981d017d")
        val sealed = NoteCrypto.sealWithIv(key, plaintext, ByteArray(0), iv)
        assertEquals(
            "0d18e06c7c725ac9e362e1ce" +
                "fa4362189661d163fcd6a56d8bf0405a" +
                "d636ac1bbedd5cc3ee727dc2ab4a9489",
            Encoding.toHex(sealed),
        )
        assertArrayEquals(plaintext, NoteCrypto.decrypt(key, sealed, ByteArray(0)))
    }

    @Test
    fun `NIST CAVP AES-256-GCM vector with AAD`() {
        val key = keyHexHex("92e11dcdaa866f5ce790fd24501f92509aacf4cb8b1339d50c9c1240935dd08b")
        val iv = hex("ac93a1a6145299bde902f21a")
        val plaintext = hex("2d71bcfa914e4ac045b2aa60955fad24")
        val aad = hex("1e0889016f67601c8ebea4943bc23ad6")
        val sealed = NoteCrypto.sealWithIv(key, plaintext, aad, iv)
        assertEquals(
            "ac93a1a6145299bde902f21a" +
                "8995ae2e6df3dbf96fac7b7137bae67f" +
                "eca5aa77d51d4a0a14d9c51e1da474ab",
            Encoding.toHex(sealed),
        )
        assertArrayEquals(plaintext, NoteCrypto.decrypt(key, sealed, aad))
        assertNull("AAD mismatch must fail authentication", NoteCrypto.decrypt(key, sealed, ByteArray(0)))
    }

    @Test
    fun `NIST CAVP AES-256-GCM vector with large plaintext and AAD`() {
        val key = keyHexHex("148579a3cbca86d5520d66c0ec71ca5f7e41ba78e56dc6eebd566fed547fe691")
        val iv = hex("b08a5ea1927499c6ecbfd4e0")
        val plaintext = hex(
            "9d0b15fdf1bd595f91f8b3abc0f7dec927dfd4799935a1795d9ce00c9b879434" +
                "420fe42c275a7cd7b39d638fb81ca52b49dc41",
        )
        val aad = hex(
            "e4f963f015ffbb99ee3349bbaf7e8e8e6c2a71c230a48f9d59860a29091d2747e01a5ca572347e247" +
                "d25f56ba7ae8e05cde2be3c97931292c02370208ecd097ef692687fecf2f419d3200162a6480a57" +
                "dad408a0dfeb492e2c5d",
        )
        val sealed = NoteCrypto.sealWithIv(key, plaintext, aad, iv)
        assertEquals(
            "b08a5ea1927499c6ecbfd4e0" +
                "2097e372950a5e9383c675e89eea1c314f999159f5611344b298cda45e628437" +
                "16f215f82ee663919c64002a5c198d7878fd3f" +
                "adbecdb0d5c2224d804d2886ff9a5760",
            Encoding.toHex(sealed),
        )
        assertArrayEquals(plaintext, NoteCrypto.decrypt(key, sealed, aad))
    }

    // ---------------------------------------------------------------- envelope behaviour

    @Test
    fun `production encrypt-decrypt round trip with AAD`() {
        val key = NoteCrypto.secretKey(NoteCrypto.randomVmk())
        val aad = CryptoAad.item("note-0001", "NOTE")
        val plaintext = "{\"title\":\"test\"}".toByteArray(Charsets.UTF_8)
        val blob = NoteCrypto.encrypt(key, plaintext, aad)
        assertEquals(
            "iv||ciphertext||tag",
            NoteCrypto.GCM_IV_LENGTH_BYTES + plaintext.size + NoteCrypto.GCM_TAG_LENGTH_BYTES,
            blob.size,
        )
        assertArrayEquals(plaintext, NoteCrypto.decrypt(key, blob, aad))
    }

    @Test
    fun `every encryption uses a fresh random IV`() {
        val key = NoteCrypto.secretKey(NoteCrypto.randomVmk())
        val first = NoteCrypto.encrypt(key, ByteArray(16), ByteArray(0))
        val second = NoteCrypto.encrypt(key, ByteArray(16), ByteArray(0))
        assertFalse(
            "IV prefix must differ between encryptions",
            first.copyOfRange(0, NoteCrypto.GCM_IV_LENGTH_BYTES)
                .contentEquals(second.copyOfRange(0, NoteCrypto.GCM_IV_LENGTH_BYTES)),
        )
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `wrong key wrong AAD and tampering all return null`() {
        val key = NoteCrypto.secretKey(NoteCrypto.randomVmk())
        val wrongKey = NoteCrypto.secretKey(NoteCrypto.randomVmk())
        val aad = CryptoAad.item("note-1", "NOTE")
        val blob = NoteCrypto.encrypt(key, "sensitive".toByteArray(Charsets.UTF_8), aad)

        assertNull("wrong key", NoteCrypto.decrypt(wrongKey, blob, aad))
        assertNull("wrong AAD", NoteCrypto.decrypt(key, blob, CryptoAad.item("note-2", "NOTE")))
        assertNull(
            "cross-kind AAD",
            NoteCrypto.decrypt(key, blob, CryptoAad.item("note-1", "CODE_FILE")),
        )
        for (index in intArrayOf(0, NoteCrypto.GCM_IV_LENGTH_BYTES, blob.size - 1)) {
            val tampered = blob.copyOf()
            tampered[index] = (tampered[index].toInt() xor 0x01).toByte()
            assertNull("tampered byte $index", NoteCrypto.decrypt(key, tampered, aad))
        }
    }

    @Test
    fun `decrypt rejects malformed blobs but accepts the minimal one`() {
        val key = NoteCrypto.secretKey(NoteCrypto.randomVmk())
        for (size in intArrayOf(0, 1, NoteCrypto.GCM_IV_LENGTH_BYTES, 27)) {
            assertNull("blob of $size bytes", NoteCrypto.decrypt(key, ByteArray(size), ByteArray(0)))
        }
        val minimal = NoteCrypto.encrypt(key, ByteArray(0), ByteArray(0))
        assertEquals(
            NoteCrypto.GCM_IV_LENGTH_BYTES + NoteCrypto.GCM_TAG_LENGTH_BYTES,
            minimal.size,
        )
        assertArrayEquals(ByteArray(0), NoteCrypto.decrypt(key, minimal, ByteArray(0)))
    }

    // ---------------------------------------------------------------- VMK wrapping

    @Test
    fun `wrapVmk round trips exactly 32 bytes`() {
        val vmk = NoteCrypto.randomVmk()
        val kek = NoteCrypto.deriveKek("master password".toCharArray(), fixedSalt, 10_000)
        val wrapped = NoteCrypto.wrapVmk(vmk, kek, CryptoAad.VMK_WRAP.toByteArray(Charsets.UTF_8))
        assertEquals(
            NoteCrypto.GCM_IV_LENGTH_BYTES + NoteCrypto.VMK_LENGTH_BYTES + NoteCrypto.GCM_TAG_LENGTH_BYTES,
            wrapped.size,
        )
        assertArrayEquals(
            vmk,
            NoteCrypto.unwrapVmk(wrapped, kek, CryptoAad.VMK_WRAP.toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun `unwrapVmk fails on wrong KEK or wrong wrap domain`() {
        val vmk = NoteCrypto.randomVmk()
        val kek = NoteCrypto.deriveKek("master password".toCharArray(), fixedSalt, 10_000)
        val otherKek = NoteCrypto.deriveKek("other password".toCharArray(), fixedSalt, 10_000)
        val wrapped = NoteCrypto.wrapVmk(vmk, kek, CryptoAad.VMK_WRAP.toByteArray(Charsets.UTF_8))
        assertNull(
            "wrong KEK",
            NoteCrypto.unwrapVmk(wrapped, otherKek, CryptoAad.VMK_WRAP.toByteArray(Charsets.UTF_8)),
        )
        assertNull(
            "recovery domain must not unwrap a password wrap",
            NoteCrypto.unwrapVmk(wrapped, kek, CryptoAad.RECOVERY_WRAP.toByteArray(Charsets.UTF_8)),
        )
        val tampered = wrapped.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x80).toByte() }
        assertNull(
            "tampered wrap",
            NoteCrypto.unwrapVmk(tampered, kek, CryptoAad.VMK_WRAP.toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun `wrapVmk rejects a non-VMK payload and unwrapVmk rejects a short plaintext`() {
        val kek = NoteCrypto.deriveKek("master password".toCharArray(), fixedSalt, 10_000)
        assertThrowsIllegalArgument {
            NoteCrypto.wrapVmk(ByteArray(16), kek, CryptoAad.VMK_WRAP.toByteArray(Charsets.UTF_8))
        }
        val wrongLength = NoteCrypto.encrypt(
            kek,
            ByteArray(16),
            CryptoAad.VMK_WRAP.toByteArray(Charsets.UTF_8),
        )
        assertNull(
            "a decryptable payload that is not 32 bytes must not be accepted as a VMK",
            NoteCrypto.unwrapVmk(wrongLength, kek, CryptoAad.VMK_WRAP.toByteArray(Charsets.UTF_8)),
        )
    }

    // ---------------------------------------------------------------- primitives

    @Test
    fun `secretKey accepts only 32-byte raw keys`() {
        val key = NoteCrypto.secretKey(NoteCrypto.randomVmk())
        assertEquals("AES", key.algorithm)
        for (size in intArrayOf(0, 16, 31, 33)) {
            assertThrowsIllegalArgument { NoteCrypto.secretKey(ByteArray(size)) }
        }
    }

    @Test
    fun `randomVmk is 32 random bytes and randomBytes is bounded`() {
        val first = NoteCrypto.randomVmk()
        val second = NoteCrypto.randomVmk()
        assertEquals(NoteCrypto.VMK_LENGTH_BYTES, first.size)
        assertFalse(first.contentEquals(second))
        assertEquals(1, NoteCrypto.randomBytes(1).size)
        assertEquals(64, NoteCrypto.randomBytes(64).size)
        assertThrowsIllegalArgument { NoteCrypto.randomBytes(0) }
        assertThrowsIllegalArgument { NoteCrypto.randomBytes(-1) }
    }

    @Test
    fun `sha256 matches FIPS 180-2 vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Encoding.toHex(NoteCrypto.sha256("abc".toByteArray(Charsets.US_ASCII))),
        )
    }

    @Test
    fun `hmacSha256 matches RFC 4231 test cases 1 and 2`() {
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            Encoding.toHex(
                NoteCrypto.hmacSha256(
                    ByteArray(20) { 0x0b },
                    "Hi There".toByteArray(Charsets.US_ASCII),
                ),
            ),
        )
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            Encoding.toHex(
                NoteCrypto.hmacSha256(
                    "Jefe".toByteArray(Charsets.US_ASCII),
                    "what do ya want for nothing?".toByteArray(Charsets.US_ASCII),
                ),
            ),
        )
    }

    @Test
    fun `constantTimeEquals behaves like equality`() {
        assertTrue(NoteCrypto.constantTimeEquals(ByteArray(0), ByteArray(0)))
        assertTrue(NoteCrypto.constantTimeEquals(hex("00ff10"), hex("00ff10")))
        assertFalse(NoteCrypto.constantTimeEquals(hex("00ff10"), hex("00ff11")))
        assertFalse(NoteCrypto.constantTimeEquals(hex("00ff10"), hex("00ff")))
    }

    @Test
    fun `wipe zeroizes byte and char arrays`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val chars = charArrayOf('p', 'w')
        NoteCrypto.wipe(bytes, byteArrayOf(9))
        NoteCrypto.wipe(chars)
        assertArrayEquals(ByteArray(4), bytes)
        assertTrue(chars.all { it == '\u0000' })
    }

    // ---------------------------------------------------------------- AAD contract

    @Test
    fun `CryptoAad constants are the frozen v1 domain strings`() {
        assertEquals("notezero.vmk.v1", CryptoAad.VMK_WRAP)
        assertEquals("notezero.recovery.v1", CryptoAad.RECOVERY_WRAP)
        assertEquals("notezero.backup.v1", CryptoAad.BACKUP)
        assertEquals(
            "notezero.item.v1|item-1|NOTE",
            String(CryptoAad.item("item-1", "NOTE"), Charsets.UTF_8),
        )
        assertNotEquals(
            String(CryptoAad.item("item-1", "NOTE"), Charsets.UTF_8),
            String(CryptoAad.item("item-1", "CODE_FILE"), Charsets.UTF_8),
        )
    }

    @Test
    fun `item ciphertext cannot be swapped between ids or kinds`() {
        val key = NoteCrypto.secretKey(NoteCrypto.randomVmk())
        val payload = "{\"body\":\"secret\"}".toByteArray(Charsets.UTF_8)
        val blob = NoteCrypto.encrypt(key, payload, CryptoAad.item("item-a", "NOTE"))
        assertArrayEquals(payload, NoteCrypto.decrypt(key, blob, CryptoAad.item("item-a", "NOTE")))
        assertNull(NoteCrypto.decrypt(key, blob, CryptoAad.item("item-b", "NOTE")))
        assertNull(NoteCrypto.decrypt(key, blob, CryptoAad.item("item-a", "CODE_FILE")))
        assertNull(NoteCrypto.decrypt(key, blob, CryptoAad.BACKUP.toByteArray(Charsets.UTF_8)))
    }

    // ---------------------------------------------------------------- helpers

    private inline fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Suppress("unused")
    private fun unusedImportsGuard() {
        Arrays.fill(ByteArray(0), 0)
        assertNotNull(0)
    }
}
