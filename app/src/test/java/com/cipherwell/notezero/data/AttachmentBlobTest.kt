package com.cipherwell.notezero.data

import com.cipherwell.notezero.crypto.CryptoAad
import com.cipherwell.notezero.crypto.NoteCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Pure-JVM tests for the `NZAT` attachment blob format and the bounded stream reader.
 * Real AES-256-GCM; no Robolectric, no filesystem.
 */
class AttachmentBlobTest {

    private val vmk = ByteArray(32) { (it * 11 + 5).toByte() }
    private val key = NoteCrypto.secretKey(vmk)
    private val aad = CryptoAad.attachment("note-1", "att-1")
    private val plaintext = "attachment bytes \u0000\u0001 with binary \u00ff\u00fe".toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `encode writes only magic version and ciphertext`() {
        val blob = AttachmentBlob.encode(plaintext, key, aad)
        assertEquals(AttachmentBlob.HEADER_BYTES + NoteCrypto.GCM_IV_LENGTH_BYTES + plaintext.size + NoteCrypto.GCM_TAG_LENGTH_BYTES, blob.size)
        assertArrayEquals(AttachmentBlob.MAGIC, blob.copyOfRange(0, AttachmentBlob.MAGIC.size))
        assertEquals(AttachmentBlob.VERSION, blob[AttachmentBlob.MAGIC.size].toInt())
        // The plaintext must not appear anywhere in the file bytes.
        assertFalse(
            "plaintext leaked into the blob",
            String(blob, Charsets.ISO_8859_1).contains("attachment bytes"),
        )
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("with binary"))
    }

    @Test
    fun `decode round trips empty and non-empty plaintext`() {
        for (content in listOf(ByteArray(0), plaintext, ByteArray(AttachmentLimits.MAX_ATTACHMENT_BYTES.toInt()))) {
            val blob = AttachmentBlob.encode(content, key, aad)
            assertArrayEquals(content, AttachmentBlob.decode(blob, key, aad))
        }
    }

    @Test
    fun `aad binds blob to note and attachment id`() {
        val blob = AttachmentBlob.encode(plaintext, key, aad)
        assertNull("other note id", AttachmentBlob.decode(blob, key, CryptoAad.attachment("note-2", "att-1")))
        assertNull("other attachment id", AttachmentBlob.decode(blob, key, CryptoAad.attachment("note-1", "att-2")))
        assertNull("missing ids", AttachmentBlob.decode(blob, key, CryptoAad.attachment("", "")))
        assertNotNull(AttachmentBlob.decode(blob, key, CryptoAad.attachment("note-1", "att-1")))
    }

    @Test
    fun `wrong key or wrong vmk length fails closed`() {
        val blob = AttachmentBlob.encode(plaintext, key, aad)
        assertNull(AttachmentBlob.decode(blob, NoteCrypto.secretKey(ByteArray(32) { 0x42 }), aad))
        try {
            NoteCrypto.secretKey(ByteArray(31))
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // A non-AES-256 key length is a programming error, never a silent success.
        }
    }

    @Test
    fun `tampered magic version header iv ciphertext or tag fails closed`() {
        val blob = AttachmentBlob.encode(plaintext, key, aad)
        for (index in listOf(0, 3, 4, 5, 6, blob.size - 1, blob.size / 2)) {
            val tampered = blob.copyOf()
            tampered[index] = (tampered[index].toInt() xor 0x01).toByte()
            assertNull("byte $index tamper must not decode", AttachmentBlob.decode(tampered, key, aad))
        }
    }

    @Test
    fun `truncation and trailing bytes fail closed`() {
        val blob = AttachmentBlob.encode(plaintext, key, aad)
        for (cut in listOf(0, 1, 4, 5, 5 + 12, blob.size - 1)) {
            assertNull("truncated at $cut", AttachmentBlob.decode(blob.copyOf(cut), key, aad))
        }
        assertNull(AttachmentBlob.decode(blob + byteArrayOf(0), key, aad))
        assertNull(AttachmentBlob.decode(ByteArray(AttachmentBlob.MAX_BLOB_BYTES.toInt() + 1), key, aad))
        // A file at exactly MAX_BLOB_BYTES is structurally allowed but fails AEAD.
        assertNull(AttachmentBlob.decode(ByteArray(AttachmentBlob.MAX_BLOB_BYTES.toInt()), key, aad))
    }

    @Test
    fun `encode rejects plaintext above the attachment limit`() {
        try {
            AttachmentBlob.encode(ByteArray(AttachmentLimits.MAX_ATTACHMENT_BYTES.toInt() + 1), key, aad)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `sha256 hex matches a published vector and detects changes`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            AttachmentBlob.sha256Hex("abc".toByteArray(Charsets.US_ASCII)),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            AttachmentBlob.sha256Hex(ByteArray(0)),
        )
        assertFalse(AttachmentBlob.sha256Hex(plaintext) == AttachmentBlob.sha256Hex(plaintext + byteArrayOf(0)))
    }

    @Test
    fun `sha256 hex shape validation`() {
        val good = AttachmentBlob.sha256Hex(plaintext)
        assertEquals(64, good.length)
        assertTrue(AttachmentBlob.isSha256Hex(good))
        assertFalse(AttachmentBlob.isSha256Hex(good.uppercase()))
        assertFalse(AttachmentBlob.isSha256Hex(good.dropLast(1)))
        assertFalse(AttachmentBlob.isSha256Hex(good.dropLast(1) + "g"))
        assertFalse(AttachmentBlob.isSha256Hex(""))
    }

    @Test
    fun `readAtMost accepts exactly the limit and rejects one byte more`() {
        val atLimit = ByteArray(100) { it.toByte() }
        assertArrayEquals(atLimit, AttachmentBlob.readAtMost(ByteArrayInputStream(atLimit), 100))

        val overLimit = ByteArray(101) { it.toByte() }
        assertNull(AttachmentBlob.readAtMost(ByteArrayInputStream(overLimit), 100))
        try {
            AttachmentBlob.readAtMost(ByteArrayInputStream(ByteArray(0)), -1)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected: a negative bound is a programming error
        }
    }

    @Test
    fun `readAtMost stops early instead of buffering an unbounded stream`() {
        // A hostile stream that can produce gigabytes; the reader must stop after the limit.
        val hostile = object : InputStream() {
            var served = 0L
            override fun read(): Int {
                served += 1
                return 0x5A
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                served += length
                return length
            }
        }
        val limit = 64L * 1024L
        assertNull(AttachmentBlob.readAtMost(hostile, limit))
        assertTrue("must stop near the limit, served=${hostile.served}", hostile.served <= limit + 16 * 1024L)
    }

    @Test
    fun `readAtMost propagates io errors to the caller`() {
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("boom")
        }
        try {
            AttachmentBlob.readAtMost(failing, 10)
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
            assertEquals("boom", expected.message)
        }
    }
}
