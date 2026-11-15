package com.cipherwell.notezero.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RFC 4648 Base64 and lowercase-hex codec tests for the dependency-free [Encoding] helper.
 * Encoding is the persistence codec for encrypted blobs, so strict decoding matters:
 * malformed input must return null, never a partially decoded buffer.
 */
class EncodingTest {

    @Test
    fun `base64 matches RFC 4648 vectors`() {
        val vectors = mapOf(
            "" to "",
            "f" to "Zg==",
            "fo" to "Zm8=",
            "foo" to "Zm9v",
            "foob" to "Zm9vYg==",
            "fooba" to "Zm9vYmE=",
            "foobar" to "Zm9vYmFy",
        )
        for ((plain, encoded) in vectors) {
            assertEquals(encoded, Encoding.base64Encode(plain.toByteArray(Charsets.US_ASCII)))
            assertArrayEquals(plain.toByteArray(Charsets.US_ASCII), Encoding.base64Decode(encoded))
        }
    }

    @Test
    fun `base64 round trips arbitrary bytes`() {
        val bytes = ByteArray(257) { (it * 31).toByte() }
        assertArrayEquals(bytes, Encoding.base64Decode(Encoding.base64Encode(bytes)))
    }

    @Test
    fun `base64Decode rejects malformed input`() {
        assertNull("bad length", Encoding.base64Decode("abc"))
        assertNull("invalid character", Encoding.base64Decode("!!!!"))
        assertNull("padding in the middle", Encoding.base64Decode("Zg==Zg=="))
        assertNull("three padding chars", Encoding.base64Decode("Z==="))
    }

    @Test
    fun `base64Decode ignores whitespace`() {
        assertEquals("Zm9vYmFy", Encoding.base64Encode("foobar".toByteArray(Charsets.US_ASCII)))
        assertArrayEquals(
            "foobar".toByteArray(Charsets.US_ASCII),
            Encoding.base64Decode("Zm9v\nYmFy\r\n"),
        )
    }

    @Test
    fun `hex round trips and is lowercase`() {
        val bytes = byteArrayOf(0x00, 0x0F, 0x7F, -1, 0x42)
        assertEquals("000f7fff42", Encoding.toHex(bytes))
        assertArrayEquals(bytes, Encoding.fromHex("000f7fff42"))
        assertArrayEquals(bytes, Encoding.fromHex("000F7FFF42"))
    }

    @Test
    fun `fromHex rejects malformed input`() {
        assertNull("odd length", Encoding.fromHex("abc"))
        assertNull("invalid character", Encoding.fromHex("zz"))
        assertArrayEquals(ByteArray(0), Encoding.fromHex(""))
    }

    @Test
    fun `utf8 helpers round trip non-ascii text`() {
        val text = "nota secreta \u00e9 \u2713"
        assertEquals(text, Encoding.utf8(Encoding.utf8(text)))
    }
}
