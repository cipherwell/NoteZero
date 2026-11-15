package com.cipherwell.notezero.data

import com.cipherwell.notezero.backup.BackupEnvelope
import com.cipherwell.notezero.backup.BackupFormat
import com.cipherwell.notezero.crypto.Bip39Recovery
import com.cipherwell.notezero.crypto.CryptoAad
import com.cipherwell.notezero.crypto.EnvelopeType
import com.cipherwell.notezero.crypto.KeyEnvelope
import com.cipherwell.notezero.crypto.NoteCrypto
import com.cipherwell.notezero.model.AttachmentRef
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Pure-JVM tests for the stream-based encrypted backup envelope (real AES-GCM, no
 * Robolectric): structural parsing, authentication, header binding, both key modes and
 * hostile-input failure modes.
 */
class BackupFormatTest {

    private val vmk = ByteArray(32) { (it * 7 + 3).toByte() }

    private fun item(
        id: String = "item-1",
        kind: ItemKind = ItemKind.NOTE,
        title: String = "Title",
        body: String = "Body",
        tags: List<String> = listOf("t1"),
        folderId: String? = null,
        pinned: Boolean = false,
        favorite: Boolean = false,
        trashed: Boolean = false,
        attachments: List<AttachmentRef> = emptyList(),
    ) = Item(
        id = id,
        kind = kind,
        title = title,
        body = body,
        tags = tags,
        folderId = folderId,
        pinned = pinned,
        favorite = favorite,
        trashed = trashed,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_001_000L,
        version = 3L,
        attachments = attachments,
    )

    private fun folder(id: String = "folder-1", name: String = "Work", parentId: String? = null) =
        Folder(id = id, name = name, parentId = parentId, sortOrder = 0, createdAt = 1_700_000_000_000L)

    private fun contents() = BackupFormat.Contents(
        exportedAt = 1_700_000_002_000L,
        items = listOf(
            item(id = "item-1", title = "café 🔥", body = "line\nwith ünïcode"),
            item(id = "item-2", kind = ItemKind.CODE_FILE, title = "Main.kt", body = "fun main() {}", pinned = true, favorite = true),
            item(id = "item-3", trashed = true, folderId = "folder-1"),
        ),
        folders = listOf(folder(), folder(id = "folder-2", name = "Nested", parentId = "folder-1")),
    )

    private fun attachmentRef(
        id: String = "att-1",
        noteId: String = "item-1",
        plaintext: ByteArray = "attachment plaintext".toByteArray(Charsets.UTF_8),
    ) = AttachmentRef(
        id = id,
        displayName = "photo-caf\u00e9.jpg",
        mimeType = "image/jpeg",
        sizeBytes = plaintext.size.toLong(),
        createdAt = 1_700_000_000_500L,
        sha256 = AttachmentBlob.sha256Hex(plaintext),
    )

    private fun attachmentRecord(
        noteId: String = "item-1",
        attachmentId: String = "att-1",
        plaintext: ByteArray = "attachment plaintext".toByteArray(Charsets.UTF_8),
    ) = BackupFormat.AttachmentBlobRecord(
        noteId = noteId,
        attachmentId = attachmentId,
        blob = AttachmentBlob.encode(
            plaintext,
            NoteCrypto.secretKey(vmk),
            CryptoAad.attachment(noteId, attachmentId),
        ),
    )

    private fun v2Contents(): BackupFormat.Contents = BackupFormat.Contents(
        exportedAt = 1_700_000_002_000L,
        items = listOf(
            item(
                id = "item-1",
                attachments = listOf(attachmentRef()),
            ),
            item(id = "item-2", kind = ItemKind.CODE_FILE, title = "Main.kt"),
        ),
        folders = listOf(folder()),
        attachments = listOf(attachmentRecord()),
    )

    private fun writeV2Backup(contents: BackupFormat.Contents = v2Contents()): ByteArray {
        val stream = ByteArrayOutputStream()
        BackupFormat.write(
            stream,
            contents,
            vmk,
            BackupFormat.MODE_VAULT_KEY,
            listOf(passwordEnvelope("pw".toCharArray())),
        )
        return stream.toByteArray()
    }

    private fun passwordEnvelope(
        password: CharArray,
        aad: ByteArray = CryptoAad.VMK_WRAP.toByteArray(Charsets.UTF_8),
    ): KeyEnvelope {
        val salt = ByteArray(NoteCrypto.SALT_LENGTH_BYTES) { (it * 3 + 1).toByte() }
        return KeyEnvelope(
            type = EnvelopeType.PASSWORD,
            iterations = NoteCrypto.MIN_ACCEPTED_ITERATIONS,
            salt = salt,
            wrappedVmk = NoteCrypto.wrapVmk(
                vmk,
                NoteCrypto.deriveKek(password, salt, NoteCrypto.MIN_ACCEPTED_ITERATIONS),
                aad,
            ),
        )
    }

    private fun recoveryEnvelope(phrase: List<String>): KeyEnvelope {
        val salt = ByteArray(NoteCrypto.SALT_LENGTH_BYTES) { (it * 5 + 2).toByte() }
        return KeyEnvelope(
            type = EnvelopeType.RECOVERY,
            iterations = NoteCrypto.MIN_ACCEPTED_ITERATIONS,
            salt = salt,
            wrappedVmk = NoteCrypto.wrapVmk(
                vmk,
                NoteCrypto.deriveKekFromPhrase(phrase, salt, NoteCrypto.MIN_ACCEPTED_ITERATIONS),
                CryptoAad.RECOVERY_WRAP.toByteArray(Charsets.UTF_8),
            ),
        )
    }

    private fun writeVaultKeyBackup(): ByteArray {
        val stream = ByteArrayOutputStream()
        BackupFormat.write(
            stream,
            contents(),
            vmk,
            BackupFormat.MODE_VAULT_KEY,
            listOf(
                passwordEnvelope("correct horse".toCharArray()),
                recoveryEnvelope(Bip39Recovery.generatePhrase()),
            ),
        )
        return stream.toByteArray()
    }

    private fun read(bytes: ByteArray): BackupEnvelope? =
        BackupFormat.read(ByteArrayInputStream(bytes))

    // ---- round trip ----------------------------------------------------------

    @Test
    fun `vault-key backup round trips items folders and metadata`() {
        val envelope = read(writeVaultKeyBackup())
        assertNotNull(envelope)
        assertEquals(BackupFormat.MODE_VAULT_KEY, envelope!!.mode)
        assertEquals(1_700_000_002_000L, envelope.exportedAt)
        assertEquals(3, envelope.itemCount)
        assertEquals(2, envelope.folderCount)
        assertNotNull(envelope.envelopeOf(EnvelopeType.PASSWORD))
        assertNotNull(envelope.envelopeOf(EnvelopeType.RECOVERY))

        val decoded = envelope.decrypt(vmk)
        assertNotNull(decoded)
        assertEquals(contents(), decoded)
    }

    @Test
    fun `embedded password envelope unwraps with the source password`() {
        val envelope = read(writeVaultKeyBackup())!!
        val passwordEnvelope = envelope.envelopeOf(EnvelopeType.PASSWORD)!!
        assertEquals(NoteCrypto.MIN_ACCEPTED_ITERATIONS, passwordEnvelope.iterations)

        val unwrapped = NoteCrypto.unwrapVmk(
            passwordEnvelope.wrappedVmk,
            NoteCrypto.deriveKek(
                "correct horse".toCharArray(),
                passwordEnvelope.salt,
                passwordEnvelope.iterations,
            ),
            CryptoAad.VMK_WRAP.toByteArray(Charsets.UTF_8),
        )
        assertArrayEquals(vmk, unwrapped)
        assertEquals(contents(), envelope.decrypt(unwrapped!!))

        val wrong = NoteCrypto.unwrapVmk(
            passwordEnvelope.wrappedVmk,
            NoteCrypto.deriveKek("wrong".toCharArray(), passwordEnvelope.salt, passwordEnvelope.iterations),
            CryptoAad.VMK_WRAP.toByteArray(Charsets.UTF_8),
        )
        assertNull(wrong)
    }

    @Test
    fun `extra password mode embeds one password envelope with backup AAD`() {
        val extraEnvelope = passwordEnvelope(
            "backup secret".toCharArray(),
            aad = CryptoAad.BACKUP.toByteArray(Charsets.UTF_8),
        )
        val stream = ByteArrayOutputStream()
        BackupFormat.write(stream, contents(), vmk, BackupFormat.MODE_EXTRA_PASSWORD, listOf(extraEnvelope))
        val envelope = read(stream.toByteArray())!!
        assertEquals(BackupFormat.MODE_EXTRA_PASSWORD, envelope.mode)
        assertEquals(1, envelope.keyEnvelopes.size)
        assertNull(envelope.envelopeOf(EnvelopeType.RECOVERY))

        assertEquals(contents(), envelope.decrypt(vmk))
        val unwrapped = NoteCrypto.unwrapVmk(
            extraEnvelope.wrappedVmk,
            NoteCrypto.deriveKek(
                "backup secret".toCharArray(),
                extraEnvelope.salt,
                extraEnvelope.iterations,
            ),
            CryptoAad.BACKUP.toByteArray(Charsets.UTF_8),
        )
        assertArrayEquals(vmk, unwrapped)
    }

    @Test
    fun `write flushes but does not close the caller stream`() {
        var closed = false
        var flushed = false
        val buffer = ByteArrayOutputStream()
        val sink = object : OutputStream() {
            override fun write(b: Int) {
                buffer.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                buffer.write(b, off, len)
            }

            override fun flush() {
                flushed = true
            }

            override fun close() {
                closed = true
            }
        }
        BackupFormat.write(
            sink,
            BackupFormat.Contents(exportedAt = 1L, items = emptyList(), folders = emptyList()),
            vmk,
            BackupFormat.MODE_VAULT_KEY,
            listOf(passwordEnvelope("pw".toCharArray())),
        )
        assertFalse("write must not close the SAF stream", closed)
        assertTrue("write must flush the SAF stream", flushed)
        assertNotNull(read(buffer.toByteArray()))
    }

    @Test
    fun `empty backup round trips`() {
        val stream = ByteArrayOutputStream()
        BackupFormat.write(
            stream,
            BackupFormat.Contents(exportedAt = 42L, items = emptyList(), folders = emptyList()),
            vmk,
            BackupFormat.MODE_VAULT_KEY,
            listOf(passwordEnvelope("pw".toCharArray())),
        )
        val envelope = read(stream.toByteArray())!!
        assertEquals(0, envelope.itemCount)
        assertEquals(0, envelope.folderCount)
        val decoded = envelope.decrypt(vmk)!!
        assertEquals(emptyList<Item>(), decoded.items)
        assertEquals(emptyList<Folder>(), decoded.folders)
        assertEquals(42L, decoded.exportedAt)
    }

    // ---- authentication / hostile input -------------------------------------

    @Test
    fun `wrong VMK fails closed`() {
        val envelope = read(writeVaultKeyBackup())!!
        assertNull(envelope.decrypt(ByteArray(32) { 0x42.toByte() }))
        assertNull(envelope.decrypt(ByteArray(16)))
    }

    @Test
    fun `tampered payload tag fails authentication`() {
        val bytes = writeVaultKeyBackup()
        for (index in listOf(bytes.size - 1, bytes.size - 5, bytes.size - 16)) {
            val tampered = bytes.copyOf()
            tampered[index] = (tampered[index].toInt() xor 0x01).toByte()
            val envelope = read(tampered)
            assertNotNull(envelope)
            assertNull("payload byte $index tamper must not decrypt", envelope!!.decrypt(vmk))
        }
    }

    @Test
    fun `tampered authenticated header field fails authentication`() {
        val bytes = writeVaultKeyBackup()
        val tampered = bytes.copyOf()
        tampered[8] = (tampered[8] + 1).toByte() // exportedAt is covered by the AAD
        val envelope = read(tampered)
        assertNotNull("structure still parses", envelope)
        assertNull("header tamper must fail AAD", envelope!!.decrypt(vmk))
    }

    @Test
    fun `tampered embedded envelope blob fails authentication`() {
        val bytes = writeVaultKeyBackup()
        // Layout: 32-byte fixed v2 header, then envelope count byte, then first u16 length.
        val firstEnvelopeDataStart = 32 + 1 + 2
        val tampered = bytes.copyOf()
        tampered[firstEnvelopeDataStart + 20] =
            (tampered[firstEnvelopeDataStart + 20].toInt() xor 0x02).toByte()
        val envelope = read(tampered)
        assertNotNull(envelope)
        assertNull(envelope!!.decrypt(vmk))
    }

    @Test
    fun `truncated and trailing-byte streams are rejected`() {
        val bytes = writeVaultKeyBackup()
        assertNull(read(ByteArray(0)))
        assertNull(read(bytes.copyOf(bytes.size - 1)))
        assertNull(read(bytes.copyOf(bytes.size - 20)))
        assertNull(read(bytes + byteArrayOf(0)))
        assertNull(read(ByteArray(64) { 0x7F.toByte() }))
        assertNull(read("not a backup".toByteArray()))
    }

    @Test
    fun `malformed header fields are rejected`() {
        val bytes = writeVaultKeyBackup()

        assertNull(read(bytes.copyOf().also { it[0] = 'X'.code.toByte() }))
        assertNull(read(bytes.copyOf().also { it[4] = 9.toByte() })) // format version
        assertNull(read(bytes.copyOf().also { it[5] = 9.toByte() })) // mode
        assertNull(read(bytes.copyOf().also { it[7] = 1.toByte() })) // reserved
        assertNull(read(bytes.copyOf().also { it[6] = 0x01.toByte() })) // flags omit the embedded recovery envelope
        assertNull(
            read(
                bytes.copyOf().also {
                    it[28] = 0xFF.toByte()
                    it[29] = 0xFF.toByte()
                },
            ),
        ) // oversized envelope block (offset 28 in v2)
    }

    @Test
    fun `mode and envelope count are validated before writing`() {
        val stream = ByteArrayOutputStream()
        assertThrowsIllegalArgument {
            BackupFormat.write(
                stream,
                contents(),
                vmk,
                BackupFormat.MODE_EXTRA_PASSWORD,
                listOf(passwordEnvelope("a".toCharArray()), passwordEnvelope("b".toCharArray())),
            )
        }
        assertThrowsIllegalArgument {
            BackupFormat.write(stream, contents(), vmk, BackupFormat.MODE_VAULT_KEY, emptyList())
        }
        assertThrowsIllegalArgument {
            BackupFormat.write(
                stream,
                contents(),
                ByteArray(16),
                BackupFormat.MODE_VAULT_KEY,
                listOf(passwordEnvelope("a".toCharArray())),
            )
        }
    }

    // ---- v2 attachments ------------------------------------------------------

    @Test
    fun `v2 backup round trips items refs and individually encrypted blobs`() {
        val contents = v2Contents()
        val bytes = writeV2Backup(contents)
        val envelope = read(bytes)
        assertNotNull(envelope)
        assertEquals(BackupFormat.FORMAT_VERSION, envelope!!.formatVersion)
        assertEquals(2, envelope.itemCount)
        assertEquals(1, envelope.folderCount)
        assertEquals(1, envelope.attachmentCount)

        val decoded = envelope.decrypt(vmk)
        assertEquals(contents, decoded)
        // The blob itself is still the NZAT envelope, sealed under the source VMK + AAD.
        val record = decoded!!.attachments.single()
        assertArrayEquals("the stored blob is reproduced byte for byte", contents.attachments.single().blob, record.blob)
        val innerPlaintext = AttachmentBlob.decode(
            record.blob,
            NoteCrypto.secretKey(vmk),
            CryptoAad.attachment(record.noteId, record.attachmentId),
        )
        assertEquals("attachment plaintext", String(innerPlaintext!!, Charsets.UTF_8))
        // No plaintext attachment bytes/names in the backup file.
        val asText = String(bytes, Charsets.ISO_8859_1)
        assertFalse(asText.contains("attachment plaintext"))
        assertFalse(asText.contains("photo-"))
    }

    @Test
    fun `v1 fixture backup still parses and decrypts`() {
        val items = contents().items
        val folders = contents().folders
        val bytes = LegacyBackupFixture.writeV1(
            sourceVmk = vmk,
            backupPassword = "v1 backup password".toCharArray(),
            items = items,
            folders = folders,
        )
        val envelope = read(bytes)
        assertNotNull("v1 backup must still parse", envelope)
        assertEquals(1, envelope!!.formatVersion)
        assertEquals(0, envelope.attachmentCount)
        assertEquals(items.size, envelope.itemCount)
        assertEquals(folders.size, envelope.folderCount)
        assertEquals(BackupFormat.MODE_EXTRA_PASSWORD, envelope.mode)

        val decoded = envelope.decrypt(vmk)
        assertNotNull(decoded)
        assertEquals(items, decoded!!.items)
        assertEquals(folders, decoded.folders)
        assertTrue(decoded.attachments.isEmpty())
    }

    @Test
    fun `v2 header attachment count is authenticated and bounded`() {
        val bytes = writeV2Backup()
        // Header offset 24 is the u32 attachmentCount in v2.
        val countTampered = bytes.copyOf()
        countTampered[27] = 3.toByte() // still structurally valid (3 <= MAX) but no longer matches the AAD
        val envelope = read(countTampered)
        assertNotNull("count change still parses structurally", envelope)
        assertNull("header attachment count must fail the AAD", envelope!!.decrypt(vmk))

        val oversized = bytes.copyOf()
        oversized[24] = 0x7F.toByte()
        oversized[25] = 0xFF.toByte()
        oversized[26] = 0xFF.toByte()
        oversized[27] = 0xFF.toByte()
        assertNull("oversized attachment count must be rejected at parse time", read(oversized))
    }

    @Test
    fun `v2 payload with a corrupted attachment record fails authentication`() {
        val bytes = writeV2Backup()
        val tampered = bytes.copyOf()
        // Flip a byte inside the sealed payload (last 8 bytes are the GCM tag region).
        tampered[bytes.size - 4] = (tampered[bytes.size - 4].toInt() xor 0x01).toByte()
        val envelope = read(tampered)
        assertNotNull(envelope)
        assertNull(envelope!!.decrypt(vmk))
    }

    @Test
    fun `v2 write rejects a blob over the attachment size limit`() {
        val huge = ByteArray(AttachmentLimits.MAX_ATTACHMENT_BYTES.toInt() + 1)
        // Format-level guard: an NZAT blob larger than the maximum cannot be embedded.
        val record = BackupFormat.AttachmentBlobRecord(
            noteId = "item-1",
            attachmentId = "att-1",
            blob = ByteArray(AttachmentBlob.MAX_BLOB_BYTES.toInt() + 1),
        )
        val stream = ByteArrayOutputStream()
        try {
            BackupFormat.write(
                stream,
                BackupFormat.Contents(
                    exportedAt = 1L,
                    items = emptyList(),
                    folders = emptyList(),
                    attachments = listOf(record),
                ),
                vmk,
                BackupFormat.MODE_VAULT_KEY,
                listOf(passwordEnvelope("pw".toCharArray())),
            )
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertEquals("rejected before writing anything", 0, stream.size())
        }
        // sanity: the huge plaintext itself is rejected by the blob encoder
        try {
            AttachmentBlob.encode(huge, NoteCrypto.secretKey(vmk), CryptoAad.attachment("n", "a"))
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
