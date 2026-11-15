package com.cipherwell.notezero.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-level tests for the versioned [KeyEnvelope] codec.
 *
 * The canonical fixture below is hand-derived from the documented layout in KeyEnvelope.kt
 * (magic "NZEN", type, version, kdf, u32 big-endian iterations, saltLen+salt, u16 wrappedLen,
 * wrapped), not produced by toBytes(); the parser and writer are both checked against it.
 */
class KeyEnvelopeTest {

    private fun hex(text: String): ByteArray = requireNotNull(Encoding.fromHex(text)) { "bad fixture" }

    private val salt: ByteArray = ByteArray(NoteCrypto.SALT_LENGTH_BYTES) { (it + 1).toByte() } // 01..10
    private val wrapped: ByteArray = ByteArray(
        NoteCrypto.GCM_IV_LENGTH_BYTES + NoteCrypto.VMK_LENGTH_BYTES + NoteCrypto.GCM_TAG_LENGTH_BYTES,
    ) { 0xAA.toByte() } // 60 bytes

    /** Hand-built byte layout: 4+1+1+1+4+1+16+2+60 = 90 bytes. */
    private val canonicalHex =
        "4e5a454e" + // "NZEN"
            "00" + // PASSWORD
            "01" + // version 1
            "01" + // PBKDF2-HMAC-SHA256
            "000927c0" + // 600_000 big-endian u32
            "10" + // saltLen 16
            "0102030405060708090a0b0c0d0e0f10" + // salt
            "003c" + // wrappedLen 60
            "aa".repeat(60) // wrapped VMK

    @Test
    fun `toBytes matches the documented byte layout exactly`() {
        val envelope = KeyEnvelope(
            type = EnvelopeType.PASSWORD,
            iterations = 600_000,
            salt = salt,
            wrappedVmk = wrapped,
        )
        assertEquals(canonicalHex, Encoding.toHex(envelope.toBytes()))
        assertEquals(90, envelope.toBytes().size)
    }

    @Test
    fun `fromBytes parses the canonical fixture`() {
        val envelope = KeyEnvelope.fromBytes(hex(canonicalHex)) ?: error("canonical envelope must parse")
        assertEquals(EnvelopeType.PASSWORD, envelope.type)
        assertEquals(KeyEnvelope.VERSION, envelope.version)
        assertEquals(KdfId.PBKDF2_HMAC_SHA256, envelope.kdf)
        assertEquals(600_000, envelope.iterations)
        assertTrue(salt.contentEquals(envelope.salt))
        assertTrue(wrapped.contentEquals(envelope.wrappedVmk))
    }

    @Test
    fun `round trip preserves every field for both envelope types`() {
        for (type in EnvelopeType.entries) {
            val envelope = KeyEnvelope(
                type = type,
                iterations = 42_000,
                salt = salt,
                wrappedVmk = wrapped,
            )
            val parsed = KeyEnvelope.fromBytes(envelope.toBytes()) ?: error("round trip failed for $type")
            assertEquals(type, parsed.type)
            assertEquals(42_000, parsed.iterations)
            assertTrue(salt.contentEquals(parsed.salt))
            assertTrue(wrapped.contentEquals(parsed.wrappedVmk))
            assertEquals(envelope.version, parsed.version)
            assertEquals(envelope.kdf, parsed.kdf)
        }
    }

    @Test
    fun `recovery envelopes are distinct from password envelopes`() {
        val passwordEnvelope = KeyEnvelope(EnvelopeType.PASSWORD, iterations = 10_000, salt = salt, wrappedVmk = wrapped)
        val recoveryEnvelope = KeyEnvelope(EnvelopeType.RECOVERY, iterations = 10_000, salt = salt, wrappedVmk = wrapped)
        assertFalse(passwordEnvelope.toBytes().contentEquals(recoveryEnvelope.toBytes()))
        assertEquals(EnvelopeType.RECOVERY, KeyEnvelope.fromBytes(recoveryEnvelope.toBytes())!!.type)
    }

    @Test
    fun `fromBytes rejects unknown magic version and kdf`() {
        val bytes = hex(canonicalHex)
        val badMagic = bytes.copyOf().also { it[0] = 'X'.code.toByte() }
        assertNull("unknown magic", KeyEnvelope.fromBytes(badMagic))
        val badVersion = bytes.copyOf().also { it[5] = 2 }
        assertNull("unknown version", KeyEnvelope.fromBytes(badVersion))
        val badKdf = bytes.copyOf().also { it[6] = 2 }
        assertNull("unknown kdf", KeyEnvelope.fromBytes(badKdf))
        val unknownType = bytes.copyOf().also { it[4] = 2 }
        assertNull("unknown type", KeyEnvelope.fromBytes(unknownType))
    }

    @Test
    fun `fromBytes rejects hostile or weak iteration counts`() {
        val bytes = hex(canonicalHex)
        val tooLow = bytes.copyOf().also {
            it[7] = 0x00; it[8] = 0x00; it[9] = 0x27; it[10] = 0x0f // 9_999
        }
        assertNull("below MIN_ACCEPTED_ITERATIONS", KeyEnvelope.fromBytes(tooLow))
        val tooHigh = bytes.copyOf().also {
            it[7] = 0x7f; it[8] = 0xff.toByte(); it[9] = 0xff.toByte(); it[10] = 0xff.toByte()
        }
        assertNull("absurd iteration count", KeyEnvelope.fromBytes(tooHigh))
    }

    @Test
    fun `fromBytes rejects bad salt and wrapped lengths`() {
        val bytes = hex(canonicalHex)
        val badSaltLength = bytes.copyOf().also { it[11] = 8 }
        assertNull("saltLen must be 16 for PBKDF2", KeyEnvelope.fromBytes(badSaltLength))
        val tooShortWrapped = bytes.copyOf().also {
            it[28] = 0x00; it[29] = 0x1b // 27 bytes: smaller than IV+tag
        }
        assertNull("wrapped shorter than IV+tag", KeyEnvelope.fromBytes(tooShortWrapped))
        val tooLongWrapped = bytes.copyOf().also {
            it[28] = 0x00; it[29] = 0x3d // claims 61 bytes while 60 remain
        }
        assertNull("wrappedLen beyond buffer", KeyEnvelope.fromBytes(tooLongWrapped))
    }

    @Test
    fun `fromBytes rejects every truncated prefix`() {
        val full = hex(canonicalHex)
        for (length in 0 until full.size) {
            assertNull("truncated to $length bytes", KeyEnvelope.fromBytes(full.copyOf(length)))
        }
    }

    @Test
    fun `fromBytes rejects trailing bytes oversized input and empty input`() {
        val full = hex(canonicalHex)
        val trailing = full.copyOf(full.size + 1)
        assertNull("trailing byte", KeyEnvelope.fromBytes(trailing))
        assertNull("empty", KeyEnvelope.fromBytes(ByteArray(0)))
        assertNull(
            "over MAX_BYTES",
            KeyEnvelope.fromBytes(ByteArray(KeyEnvelope.MAX_BYTES + 1)),
        )
    }

    @Test
    fun `fromBytes rejects random garbage`() {
        val random = java.util.Random(7)
        repeat(200) {
            val bytes = ByteArray(90).also(random::nextBytes)
            // Guarantee the parse gets past the magic only when it starts with NZEN.
            if (!bytes.copyOf(4).contentEquals("NZEN".toByteArray(Charsets.US_ASCII))) {
                assertNull(KeyEnvelope.fromBytes(bytes))
            }
        }
    }

    @Test
    fun `toBytes rejects invalid field values`() {
        assertThrowsIllegalArgument {
            KeyEnvelope(EnvelopeType.PASSWORD, iterations = 9_999, salt = salt, wrappedVmk = wrapped).toBytes()
        }
        assertThrowsIllegalArgument {
            KeyEnvelope(EnvelopeType.PASSWORD, iterations = 10_000, salt = ByteArray(8), wrappedVmk = wrapped).toBytes()
        }
        assertThrowsIllegalArgument {
            KeyEnvelope(
                EnvelopeType.PASSWORD,
                iterations = 10_000,
                salt = salt,
                wrappedVmk = ByteArray(27),
            ).toBytes()
        }
        assertThrowsIllegalArgument {
            KeyEnvelope(
                EnvelopeType.PASSWORD,
                version = 2,
                iterations = 10_000,
                salt = salt,
                wrappedVmk = wrapped,
            ).toBytes()
        }
    }

    @Test
    fun `enum ids are part of the frozen format`() {
        assertEquals(0, EnvelopeType.PASSWORD.id)
        assertEquals(1, EnvelopeType.RECOVERY.id)
        assertEquals(EnvelopeType.PASSWORD, EnvelopeType.fromId(0))
        assertEquals(EnvelopeType.RECOVERY, EnvelopeType.fromId(1))
        assertNull(EnvelopeType.fromId(2))
        assertEquals(1, KdfId.PBKDF2_HMAC_SHA256.id)
        assertEquals(KdfId.PBKDF2_HMAC_SHA256, KdfId.fromId(1))
        assertNull(KdfId.fromId(0))
    }

    @Test
    fun `envelope payloads are not interchangeable`() {
        val first = KeyEnvelope(EnvelopeType.PASSWORD, iterations = 10_000, salt = salt, wrappedVmk = wrapped)
            .toBytes()
        val second = KeyEnvelope(EnvelopeType.PASSWORD, iterations = 20_000, salt = salt, wrappedVmk = wrapped)
            .toBytes()
        assertNotEquals(Encoding.toHex(first), Encoding.toHex(second))
        assertNotNull(KeyEnvelope.fromBytes(second))
    }

    private inline fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
